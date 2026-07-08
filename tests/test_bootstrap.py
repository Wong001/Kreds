import json
from pathlib import Path
from fastapi.testclient import TestClient
from hearth.bootstrap import build_bootstrap_app
from hearth.api import build_app
from hearth.node import HearthNode
from hearth.runner import _enrolled

def test_bootstrap_status_and_create(tmp_path):
    d = tmp_path / "n"; fired = []
    c = TestClient(build_bootstrap_app(d, lambda: fired.append(1)))
    assert c.get("/api/bootstrap").json() == {"initialized": False}
    r = c.post("/api/bootstrap/create", json={"name": "Wong", "device": "wong-pc"})
    assert r.status_code == 200 and r.json()["ok"] is True
    assert (d / "keys.json").exists()
    raw = json.loads((d / "keys.json").read_text())
    assert raw.get("cert") is not None            # enrolled
    assert fired == [1]                            # on_ready fired


# -- whole-branch review, MINOR #3: bootstrap mutation endpoints stay live
# for a beat after on_ready() (uvicorn's graceful shutdown isn't instant) --
# a second create/pair-request/pair-install landing in that gap must not be
# able to clobber the freshly-enrolled keys.json. -----------------------------

def test_bootstrap_second_create_after_ready_409(tmp_path):
    d = tmp_path / "n"; fired = []
    c = TestClient(build_bootstrap_app(d, lambda: fired.append(1)))
    r1 = c.post("/api/bootstrap/create", json={"name": "Wong", "device": "wong-pc"})
    assert r1.status_code == 200
    first_keys = (d / "keys.json").read_text()
    r2 = c.post("/api/bootstrap/create", json={"name": "Someone Else", "device": "other-pc"})
    assert r2.status_code == 409
    assert (d / "keys.json").read_text() == first_keys   # not clobbered
    assert fired == [1]                                    # on_ready only fired once


def test_bootstrap_pair_request_after_ready_409(tmp_path):
    c = TestClient(build_bootstrap_app(tmp_path / "n", lambda: None))
    c.post("/api/bootstrap/create", json={"name": "Wong", "device": "wong-pc"})
    r = c.post("/api/bootstrap/pair-request", json={"device": "phone"})
    assert r.status_code == 409


def test_bootstrap_pair_install_after_ready_409(tmp_path):
    d = tmp_path / "n"
    c = TestClient(build_bootstrap_app(d, lambda: None))
    c.post("/api/bootstrap/create", json={"name": "Wong", "device": "wong-pc"})
    r = c.post("/api/bootstrap/pair-install", json={"package": "whatever"})
    assert r.status_code == 409


# -- whole-branch review, MINOR #5: an already-enrolled data dir skips the
# bootstrap phase entirely (run_serve's _enrolled check) - build_app on that
# dir alone must already answer /api/bootstrap with initialized:true, since
# nothing ever mounts the bootstrap app for it. Focused unit, no live
# server needed. --------------------------------------------------------------

def test_enrolled_helper_true_for_created_node_dir(tmp_path):
    d = tmp_path / "n"
    node = HearthNode.create(d, "Wong", "wong-pc")
    node.close()
    assert _enrolled(d) is True


def test_enrolled_dir_serves_full_app_directly(tmp_path):
    d = tmp_path / "n"
    node = HearthNode.create(d, "Wong", "wong-pc")
    assert _enrolled(d) is True                  # run_serve would skip bootstrap for this dir
    c = TestClient(build_app(node))
    b = c.get("/api/bootstrap").json()
    assert b["initialized"] is True and b["onboarding_done"] is False

def test_bootstrap_create_requires_name(tmp_path):
    c = TestClient(build_bootstrap_app(tmp_path / "n", lambda: None))
    assert c.post("/api/bootstrap/create", json={"name": "  "}).status_code == 400

def test_bootstrap_pair_request(tmp_path):
    c = TestClient(build_bootstrap_app(tmp_path / "n", lambda: None))
    req = c.post("/api/bootstrap/pair-request", json={"device": "phone"}).json()["request"]
    assert isinstance(req, str) and len(req) > 0

def test_full_app_bootstrap_status(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-pc")
    c = TestClient(build_app(node))
    b = c.get("/api/bootstrap").json()
    assert b["initialized"] is True and b["onboarding_done"] is False
    assert c.post("/api/onboarding-done").status_code == 200
    assert c.get("/api/bootstrap").json()["onboarding_done"] is True
