"""Stable Kreds launcher stub -- the top-level `Kreds.exe` (see
packaging/launcher.spec, a ONE-FILE PyInstaller build, separate from
packaging/kreds.spec's one-folder payload build). This file should change
as rarely as possible: it never carries the app's own features, only the
plumbing to apply a staged core update (re-verifying it against its OWN
bundled copy of the release pubkey, independent of whatever the staged
payload claims) and then run whichever versioned payload is current.

Install layout this expects (see packaging/build.ps1):
    <install_root>/Kreds.exe                  <- this launcher
    <install_root>/current                    <- text file, the active version
    <install_root>/previous                   <- text file, the prior version (rollback)
    <install_root>/versions/<version>/Kreds.exe   <- a payload build (packaging/kreds.spec)

Kept intentionally tiny, mirroring packaging/kreds_main.py's top-level
try/except: this is also a `console=False` windowed PyInstaller build, so
an unhandled exception here would otherwise vanish silently instead of
leaving any evidence.
"""
import os
import subprocess
import sys
import time
import traceback
from pathlib import Path

from hearth import coreupdate

# A payload that exits non-zero within this many seconds of being started is
# treated as a bad update (crashed on startup) rather than a normal user
# quit, and triggers an automatic rollback + rerun of the prior version.
CRASH_WINDOW_SECONDS = 8.0


def _log_path() -> Path:
    base = os.environ.get("APPDATA") or str(Path.home())
    d = Path(base) / "Kreds"
    d.mkdir(parents=True, exist_ok=True)
    return d / "app.log"


def _log(message: str) -> None:
    try:
        with open(_log_path(), "a", encoding="utf-8") as f:
            f.write(time.strftime("[%Y-%m-%d %H:%M:%S] ") + message + "\n")
    except Exception:
        pass  # nothing more we can do without a console


def install_root() -> Path:
    """The directory this launcher itself lives in -- `sys.executable`'s
    parent when frozen (the real, shipped case), else a dev-mode fallback
    so `python packaging/launcher.py` from a checkout is at least runnable
    against a hand-built dist/Kreds/ during iteration."""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent.parent / "dist" / "Kreds"


def staging_dir() -> Path:
    base = os.environ.get("APPDATA") or str(Path.home())
    return Path(base) / "Kreds" / "update-staging"


def _payload_exe(root: Path, version: str) -> Path:
    return root / "versions" / version / "Kreds.exe"


def _run_payload(exe: Path) -> tuple[int, bool]:
    """Run `exe` and wait for it to exit. Returns (returncode, crashed_fast)
    where crashed_fast means it exited non-zero within CRASH_WINDOW_SECONDS
    of starting -- i.e. it never really got going, as opposed to a normal
    (possibly nonzero, e.g. a user Alt-F4 mid-shutdown) exit after actually
    running for a while."""
    start = time.monotonic()
    proc = subprocess.run([str(exe)])
    elapsed = time.monotonic() - start
    crashed_fast = proc.returncode != 0 and elapsed < CRASH_WINDOW_SECONDS
    return proc.returncode, crashed_fast


def main() -> int:
    root = install_root()

    applied = coreupdate.apply_staged_core(root, staging_dir())
    if applied:
        _log("applied staged core update -> " + applied)

    version = coreupdate.current_version(root)
    if version is None:
        _log("no installed version found under " + str(root / "versions"))
        return 1
    if not coreupdate.is_valid_version(version):
        # Defense-in-depth: `current` is untrusted on-disk state (the same
        # trust level as the marker apply_staged_core() already sanitizes).
        # A traversal/absolute-path string here would otherwise be joined
        # straight into a filesystem path and EXECUTED below -- refuse
        # rather than run it, even though apply_staged_core() should never
        # write anything but a validated version into `current` itself.
        _log(f"refusing to run: current names an invalid version {version!r}")
        return 1

    exe = _payload_exe(root, version)
    if not exe.exists():
        # `current` names a version whose payload is missing/broken --
        # fall back to the prior version rather than failing to launch.
        _log(f"payload missing for current version {version} ({exe}); reverting")
        reverted = coreupdate.revert_current(root)
        if reverted is None:
            return 1
        if not coreupdate.is_valid_version(reverted):
            _log(f"refusing to run reverted version: invalid {reverted!r}")
            return 1
        version = reverted
        exe = _payload_exe(root, version)
        if not exe.exists():
            _log(f"payload also missing for reverted version {version} ({exe})")
            return 1

    returncode, crashed_fast = _run_payload(exe)
    if crashed_fast:
        _log(f"version {version} crashed within {CRASH_WINDOW_SECONDS}s "
             f"of starting (exit {returncode}); attempting rollback")
        reverted = coreupdate.revert_current(root)
        if reverted and reverted != version and coreupdate.is_valid_version(reverted):
            exe = _payload_exe(root, reverted)
            if exe.exists():
                returncode, _ = _run_payload(exe)
            else:
                _log(f"rollback target {reverted} has no payload ({exe})")
        elif reverted and reverted != version:
            _log(f"refusing crash-rollback to invalid version {reverted!r}")
    return returncode


if __name__ == "__main__":
    try:
        sys.exit(main())
    except BaseException:
        _log("FATAL (launcher): " + traceback.format_exc())
        raise
