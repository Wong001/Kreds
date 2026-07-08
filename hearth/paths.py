"""Resource paths that differ when PyInstaller-frozen vs running from source.
Non-frozen returns the repo layout so dev/demo/tests are unchanged."""
import shutil, sys
from pathlib import Path

def is_frozen() -> bool:
    return getattr(sys, "frozen", False)

def resource_dir() -> Path:
    if is_frozen():
        return Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
    return Path(__file__).parent          # the hearth package (repo)

def bundled_web_dir() -> Path:
    return (resource_dir() / "hearth" / "web") if is_frozen() \
        else (Path(__file__).parent / "web")

def bundled_tor_dir() -> Path:
    # ensure_tor_binary looks for <dir>/tor/tor.exe
    return resource_dir()

def _read_version(d: Path) -> str:
    f = d / "VERSION"
    return f.read_text().strip() if f.exists() else "0.0.0"

def seed_web_dir(dst: Path) -> None:
    """Copy the bundled web assets into the writable dst when dst is missing
    or older than the bundle (so auto-update's newer VERSION is never
    overwritten by an equal/older bundled seed)."""
    from .update import version_lt          # reuse the version compare
    src = bundled_web_dir()
    if dst.exists() and not version_lt(_read_version(dst), _read_version(src)):
        return
    tmp = dst.with_name(dst.name + ".seed-new")
    if tmp.exists(): shutil.rmtree(tmp)
    shutil.copytree(src, tmp)
    if dst.exists(): shutil.rmtree(dst)
    tmp.rename(dst)
