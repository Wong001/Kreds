import sys
from pathlib import Path
import hearth.paths as paths

def test_not_frozen_uses_repo(monkeypatch):
    monkeypatch.setattr(paths, "is_frozen", lambda: False)
    assert paths.bundled_web_dir().name == "web"
    assert (paths.bundled_web_dir() / "index.html").exists()   # the repo web dir

def test_resource_dir_frozen(monkeypatch, tmp_path):
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "_MEIPASS", str(tmp_path), raising=False)
    monkeypatch.setattr(paths, "is_frozen", lambda: True)
    assert paths.resource_dir() == tmp_path

def test_seed_web_dir_copies_when_missing(tmp_path, monkeypatch):
    src = tmp_path / "bundled-web"; src.mkdir()
    (src / "index.html").write_text("hi"); (src / "VERSION").write_text("0.2.0")
    monkeypatch.setattr(paths, "bundled_web_dir", lambda: src)
    dst = tmp_path / "appdata-web"
    paths.seed_web_dir(dst)
    assert (dst / "index.html").read_text() == "hi"
    # idempotent: a newer seeded VERSION is not clobbered by an equal/older bundle
    (dst / "index.html").write_text("UPDATED"); (dst / "VERSION").write_text("9.9.9")
    paths.seed_web_dir(dst)
    assert (dst / "index.html").read_text() == "UPDATED"

def test_tray_icon_path_source_and_frozen(monkeypatch, tmp_path):
    # Spec 2026-07-08-kreds-tray-icon: the tray reuses packaging/kreds.ico,
    # bundled under <resource_dir>/packaging/ when frozen (kreds.spec datas
    # must match), resolved from the repo from source.
    from pathlib import Path as _P
    p = paths.tray_icon_path()
    assert p.name == "kreds.ico" and p.parent.name == "packaging"
    assert p.is_file()                     # the repo really carries it
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "_MEIPASS", str(tmp_path), raising=False)
    assert paths.tray_icon_path() == _P(str(tmp_path)) / "packaging" / "kreds.ico"


def test_pystray_declared_and_bundled():
    root = Path(__file__).resolve().parents[1]
    assert "pystray" in (root / "requirements.txt").read_text()
    spec = (root / "packaging" / "kreds.spec").read_text()
    assert '"packaging"' in spec           # kreds.ico datas destination
