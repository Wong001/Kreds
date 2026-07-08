"""PyInstaller frozen entry point for Kreds.

Kept intentionally tiny: the top-level try/except is the last line of
defense against a packaged app that silently vanishes on startup (no
console window to see a traceback in, since the build is console=False).
Any exception before/around launch() gets written to
%APPDATA%/Kreds/app.log so a failed launch leaves evidence.

hearth.desktop.launch() has its own internal error handling for node
startup failures (shows an error window); this wraps the OUTER import +
launch call, catching anything that happens before that machinery is even
running (e.g. a missing frozen import).
"""
import os
import sys
import traceback
from pathlib import Path


def _log_path() -> Path:
    base = os.environ.get("APPDATA") or str(Path.home())
    d = Path(base) / "Kreds"
    d.mkdir(parents=True, exist_ok=True)
    return d / "app.log"


def _log_fatal(message: str) -> None:
    try:
        with open(_log_path(), "a", encoding="utf-8") as f:
            f.write(message + "\n")
    except Exception:
        pass  # nothing more we can do without a console


def _patch_none_streams() -> None:
    """PyInstaller windowed (console=False) builds have no console attached,
    so Python leaves sys.stdout/stderr/stdin as None -- unlike a normal
    process where they're always at least a file object. Several
    dependencies assume a real stream and crash on that None (uvicorn's
    default logging setup calls sys.stdout.isatty() while configuring its
    ColourizedFormatter, raising AttributeError: 'NoneType' object has no
    attribute 'isatty', which kills the node thread before it can start
    serving). Redirect the None streams to os.devnull once, up front, so
    every downstream `sys.stdout.write(...)`/`.isatty()` call hits a real
    (no-op) file object instead."""
    devnull_out = None
    for name in ("stdin", "stdout", "stderr"):
        if getattr(sys, name, None) is None:
            if devnull_out is None:
                devnull_out = open(os.devnull, "r+")
            setattr(sys, name, devnull_out)


def main() -> None:
    try:
        _patch_none_streams()
        from hearth.desktop import launch
        launch()
    except SystemExit:
        # The single-instance guard (hearth.desktop) calls sys.exit(0) on a
        # normal double-launch (an existing instance is already running) --
        # a clean, expected exit, not a crash. Let it propagate as-is
        # rather than falling into the BaseException handler below, which
        # would otherwise mislabel it "FATAL" in app.log.
        raise
    except BaseException:
        _log_fatal("FATAL (kreds_main): " + traceback.format_exc())
        raise


if __name__ == "__main__":
    main()
