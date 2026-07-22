"""API layer for phone<->desktop pairing (spec 2026-07-22-android-first-
load-pairing-design): POST /api/pair/begin mints a code + the
{gossip_addr, code} link (invitecodec.encode_pair); GET /api/pair/pending
+ POST /api/pair/accept are the desktop-side half of the handoff with
the Tor wire handler (Task 2, sync.py -- not built yet). Since that wire
layer doesn't exist in this task, these tests drive node.pending_pair
directly, in exactly the shape Task 2's coroutine will populate (see
node.py's pending_pair docstring for the handoff contract) -- this file
covers the API's own contract (begin/pending/accept), not the wire
ceremony end to end."""
import json
import time

from fastapi.testclient import TestClient

from hearth import invitecodec
from hearth.api import build_app
from hearth.node import HearthNode


def _fresh(tmp_path, name="n"):
    return HearthNode.create(tmp_path / name, "Wong", "wong-desktop")


def _stub_pending(node, tmp_path, device_name="phone-1"):
    """Stand in for Task 2's wire handler: a phone's pair_request,
    received and parsed, sitting in node.pending_pair waiting for the
    human's Accept/Deny -- exactly the shape node.py's pending_pair
    docstring specifies."""
    request_json = HearthNode.pair_request(tmp_path / "phone", device_name)
    req = json.loads(request_json)
    node.pending_pair = {"device_name": req["device_name"],
                         "device_pub": req["device_pub"],
                         "request_json": request_json}
    return req


# -- POST /api/pair/begin ----------------------------------------------------

def test_pair_begin_400_when_not_serving(tmp_path):
    # No gossip_addr set yet (a fresh node before its listener binds) --
    # a link with no way to reach this node is worse than no link.
    n = _fresh(tmp_path)
    c = TestClient(build_app(n))
    r = c.post("/api/pair/begin")
    assert r.status_code == 400


def test_pair_begin_returns_decodable_link(tmp_path):
    n = _fresh(tmp_path)
    n.store.set_meta("gossip_addr", "127.0.0.1:7101")
    c = TestClient(build_app(n))
    r = c.post("/api/pair/begin")
    assert r.status_code == 200
    body = r.json()
    typ, addr, code = invitecodec.decode(body["link"])
    assert typ == "pair"
    assert addr == "127.0.0.1:7101"
    assert body["expires_at"] == n.pairing.expires_at
    # the minted code is live and redeemable exactly once
    assert n.pairing.verify_and_consume(code, time.time()) is True
    assert n.pairing.verify_and_consume(code, time.time()) is False


def test_pair_begin_replaces_prior_code(tmp_path):
    # Reopening Add-device must invalidate a code left showing from an
    # earlier visit (PairingCodes' "ONE active code" contract) --
    # exercised here through the actual HTTP route.
    n = _fresh(tmp_path)
    n.store.set_meta("gossip_addr", "127.0.0.1:7101")
    c = TestClient(build_app(n))
    r1 = c.post("/api/pair/begin")
    _, _, code1 = invitecodec.decode(r1.json()["link"])
    r2 = c.post("/api/pair/begin")
    _, _, code2 = invitecodec.decode(r2.json()["link"])
    assert code1 != code2
    assert n.pairing.verify_and_consume(code1, time.time()) is False
    assert n.pairing.verify_and_consume(code2, time.time()) is True


# -- GET /api/pair/pending ----------------------------------------------------

def test_pair_pending_null_when_none(tmp_path):
    n = _fresh(tmp_path)
    c = TestClient(build_app(n))
    r = c.get("/api/pair/pending")
    assert r.status_code == 200
    assert r.json() == {"pending": None}


def test_pair_pending_surfaces_device_name_and_pub(tmp_path):
    n = _fresh(tmp_path)
    req = _stub_pending(n, tmp_path)
    c = TestClient(build_app(n))
    r = c.get("/api/pair/pending")
    assert r.status_code == 200
    assert r.json() == {"pending": {"device_name": req["device_name"],
                                    "device_pub": req["device_pub"]}}


# -- POST /api/pair/accept ----------------------------------------------------

def test_pair_accept_400_when_no_pending(tmp_path):
    n = _fresh(tmp_path)
    c = TestClient(build_app(n))
    r = c.post("/api/pair/accept", json={"device_pub": "x", "accept": True})
    assert r.status_code == 400


def test_pair_accept_400_on_device_pub_mismatch(tmp_path):
    n = _fresh(tmp_path)
    _stub_pending(n, tmp_path)
    c = TestClient(build_app(n))
    r = c.post("/api/pair/accept",
               json={"device_pub": "not-the-real-one", "accept": True})
    assert r.status_code == 400
    # a mismatched attempt must not resolve the real pending request --
    # the genuine phone is still waiting on the other end of the wire.
    assert "verdict" not in n.pending_pair
    assert not n.pending_pair_event.is_set()


def test_pair_accept_missing_keys_400(tmp_path):
    n = _fresh(tmp_path)
    _stub_pending(n, tmp_path)
    c = TestClient(build_app(n))
    assert c.post("/api/pair/accept", json={"accept": True}).status_code == 400
    assert c.post("/api/pair/accept", json={}).status_code == 400


def test_pair_accept_true_runs_accept_pairing_and_fires_event(tmp_path):
    n = _fresh(tmp_path)
    req = _stub_pending(n, tmp_path)
    c = TestClient(build_app(n))
    r = c.post("/api/pair/accept",
               json={"device_pub": req["device_pub"], "accept": True})
    assert r.status_code == 200
    assert r.json() == {"ok": True}
    # the handoff dict now carries the verdict + package for Task 2's
    # wire handler to pick up and send back over the held connection
    assert n.pending_pair["verdict"] is True
    pkg = json.loads(n.pending_pair["package"])
    assert pkg["t"] == "hearth-pair-package"
    assert n.pending_pair_event.is_set()
    # the real device-enrollment side effect actually happened (this is
    # node.accept_pairing, unchanged, doing its normal job)
    assert req["device_pub"] in n.store.load_views(n.identity_pub)


def test_pair_accept_false_denies_without_enrolling(tmp_path):
    n = _fresh(tmp_path)
    req = _stub_pending(n, tmp_path)
    c = TestClient(build_app(n))
    r = c.post("/api/pair/accept",
               json={"device_pub": req["device_pub"], "accept": False})
    assert r.status_code == 200
    assert r.json() == {"ok": True}
    assert n.pending_pair["verdict"] is False
    assert "package" not in n.pending_pair
    assert n.pending_pair_event.is_set()
    assert req["device_pub"] not in n.store.load_views(n.identity_pub)


def test_pair_accept_423_while_locked(tmp_path):
    # Mirrors test_applock_api.py / test_friend_add_api.py's locked-guard
    # tests: /api/pair/* is not in _APPLOCK_ALLOWLIST, so locked_gate
    # 423s before the handler runs.
    n = _fresh(tmp_path)
    n.enable_applock("1234", "pin")
    n.lock()
    c = TestClient(build_app(n))
    assert c.post("/api/pair/begin").status_code == 423
    assert c.get("/api/pair/pending").status_code == 423
    assert c.post("/api/pair/accept", json={"device_pub": "x",
                                            "accept": True}).status_code == 423
