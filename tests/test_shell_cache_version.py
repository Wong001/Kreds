"""0.3.2 update-visibility fixes: the web shell is served with
Cache-Control: no-cache (so a hot-swapped update is always revalidated and
shown, not served stale from the webview cache), and /api/update/check reports
the installed web-asset version so the UI can show it."""
from starlette.testclient import TestClient

from hearth.api import build_app
from hearth.node import HearthNode


def _client(tmp_path):
    web = tmp_path / "web"
    web.mkdir()
    (web / "index.html").write_text("<html></html>")
    (web / "app.js").write_text("// app")
    (web / "sw.js").write_text("// sw")
    (web / "style.css").write_text("/* css */")
    (web / "VERSION").write_text("9.9.9")
    node = HearthNode.create(tmp_path / "node", "W", "d")
    return TestClient(build_app(node, web_dir=web))


def test_shell_assets_served_no_cache(tmp_path):
    client = _client(tmp_path)
    for path in ("/", "/sw.js", "/static/app.js", "/static/style.css"):
        r = client.get(path)
        assert r.status_code == 200
        assert r.headers.get("cache-control") == "no-cache", path


def test_update_check_reports_web_version(tmp_path):
    client = _client(tmp_path)
    r = client.get("/api/update/check")
    assert r.status_code == 200
    assert r.json().get("web_version") == "9.9.9"
