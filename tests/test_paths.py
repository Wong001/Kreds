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
