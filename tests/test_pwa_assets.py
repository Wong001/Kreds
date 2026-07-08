import json
from pathlib import Path

WEB = Path(__file__).resolve().parents[1] / "hearth" / "web"


def test_fonts_selfhosted_present():
    fonts = list((WEB / "fonts").glob("*.woff2"))
    assert len(fonts) >= 3          # at least the three families


def test_manifest_valid():
    m = json.loads((WEB / "manifest.json").read_text(encoding="utf-8"))
    assert m["name"] == "Kreds"
    assert m["display"] == "standalone"
    assert len(m["icons"]) >= 1


def test_service_worker_shell_only():
    sw = (WEB / "sw.js").read_text(encoding="utf-8")
    # network-only for API + ws; never caches data
    assert "/api/" in sw and "/ws" in sw
    assert "caches" in sw           # uses the Cache API for the shell
    # spec Sec.6: offline launch keeps fonts + icons, so the install-time
    # SHELL precache must include at least one of each (not just app shell
    # files like index.html/style.css/app.js).
    assert "/static/fonts/" in sw and ".woff2" in sw
    assert "/static/icons/" in sw and ".png" in sw


def test_icons_present():
    assert list((WEB / "icons").glob("*.png"))
