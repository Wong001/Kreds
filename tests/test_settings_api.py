"""Desktop app settings (Kreds Windows shell, Task 3): GET/POST /api/settings
for close_behavior ("quit" the app vs "keep" it running in the background on
window close). Node meta is the source of truth; this just round-trips it."""
from fastapi.testclient import TestClient

from hearth.api import build_app
from hearth.node import HearthNode


def _fresh(tmp_path):
    return HearthNode.create(tmp_path / "n", "W", "d")


def test_settings_close_behavior(tmp_path):
    c = TestClient(build_app(_fresh(tmp_path)))
    assert c.get("/api/settings").json()["close_behavior"] == "quit"     # default
    assert c.post("/api/settings", json={"close_behavior": "keep"}).status_code == 200
    assert c.get("/api/settings").json()["close_behavior"] == "keep"
    assert c.post("/api/settings", json={"close_behavior": "bad"}).status_code == 400


def test_settings_bad_close_behavior_does_not_change_stored_value(tmp_path):
    c = TestClient(build_app(_fresh(tmp_path)))
    c.post("/api/settings", json={"close_behavior": "keep"})
    c.post("/api/settings", json={"close_behavior": "nonsense"})
    assert c.get("/api/settings").json()["close_behavior"] == "keep"


def test_settings_readable_while_locked(tmp_path):
    """The desktop titlebar's close handler reads /api/settings on every close;
    it must work while app-lock is engaged so a locked 'keep running' node isn't
    silently quit on close (whole-branch review)."""
    from fastapi.testclient import TestClient
    from hearth.api import build_app
    from hearth.node import HearthNode
    node = HearthNode.create(tmp_path / "n", "W", "d")
    node.enable_applock("1234", "pin")
    c = TestClient(build_app(HearthNode(node.data_dir)))   # reboots LOCKED
    r = c.get("/api/settings")
    assert r.status_code == 200 and r.json()["close_behavior"] == "quit"
    # a content route is still gated (423) while locked -- allowlisting settings
    # didn't widen the gate
    assert c.get("/api/state").status_code == 423


def test_settings_write_blocked_while_locked(tmp_path):
    """The allowlist only needs to cover the GET (close handler reading the
    pref) -- a POST while locked must still 423, otherwise someone at a locked
    machine could flip keep->quit (whole-branch review, MINOR #2)."""
    from fastapi.testclient import TestClient
    from hearth.api import build_app
    from hearth.node import HearthNode
    node = HearthNode.create(tmp_path / "n", "W", "d")
    node.enable_applock("1234", "pin")
    c = TestClient(build_app(HearthNode(node.data_dir)))   # reboots LOCKED
    r = c.post("/api/settings", json={"close_behavior": "keep"})
    assert r.status_code == 423
    # and the GET still works (unaffected by the write-side gate)
    assert c.get("/api/settings").status_code == 200
