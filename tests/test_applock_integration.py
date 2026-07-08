"""App-lock full-lifecycle integration: a real node directory, the REAL
Windows DPAPI (no seal/unseal fakes -- this machine is Windows, so
applock.dpapi_seal/dpapi_unseal actually run), a real reboot
(HearthNode(dir) built fresh from disk, not the enable()+lock() in-memory
shortcut test_applock_api.py's _locked() helper uses), and the real HTTP
layer via TestClient.

Unit-level coverage of each piece already exists: tests/test_applock.py
(crypto core, fake seal/unseal), tests/test_applock_node.py (node
lock-state, no HTTP), tests/test_applock_api.py (API + throttle + autolock,
via the in-memory _locked() shortcut). This file proves the pieces compose
correctly across one continuous story none of those files exercises end to
end: create -> enable -> reboot -> boots locked -> 423 over real HTTP ->
unlock -> 200 -> post a profile block -> lock again -> 423 again -> unlock
again -> the block is still readable (the secret bundle, including
storage_key, survives a full DPAPI+scrypt re-seal/unseal round trip without
corruption)."""
import json
import time

from fastapi.testclient import TestClient

from hearth.api import build_app
from hearth.identity import DeviceKeys
from hearth.node import HearthNode


def test_full_applock_lifecycle_real_dpapi(tmp_path):
    t0 = time.time()
    node_dir = tmp_path / "n"

    # -- create + enable --------------------------------------------------
    node = HearthNode.create(node_dir, "Wong", "wong-phone")
    ident = node.identity_pub
    node.enable_applock("1234", "pin")

    # keys.json must never carry a plaintext secret once App-lock is on --
    # checked against the real source-of-truth field list, not a hardcoded
    # copy of it.
    raw = json.loads((node_dir / "keys.json").read_text())
    for f in DeviceKeys.SECRET_FIELDS:
        assert not raw.get(f), f"plaintext {f} leaked into keys.json"
    assert raw.get("applock") is True
    assert (node_dir / "applock.json").exists()

    # -- reboot: a fresh process loading this same directory --------------
    node2 = HearthNode(node_dir)
    assert node2.locked is True
    assert node2.applock_enabled is True
    assert node2.identity_pub == ident            # cert fallback works locked

    # A content op cannot sign while locked. This is enforced by
    # DeviceKeys.sign_message raising on a None device_priv -- not by a
    # node-level flag compose_post could accidentally skip -- so it holds
    # even if a future caller forgets to check node.locked first.
    try:
        node2.compose_post("should never publish", scope="kreds")
        assert False, "compose_post must raise while locked"
    except RuntimeError as e:
        assert "locked" in str(e)

    c = TestClient(build_app(node2))

    # -- 423 while locked; the exact-path allowlist still works -----------
    assert c.get("/api/state").status_code == 423
    assert c.get("/api/applock").status_code == 200   # allowlisted

    # -- unlock via the real API, over the real HTTP layer -----------------
    r = c.post("/api/unlock", json={"credential": "1234"})
    assert r.status_code == 200
    assert node2.locked is False
    assert c.get("/api/state").status_code == 200      # same route, now 200

    # -- post a profile ("Wall") block --------------------------------------
    r2 = c.post("/api/post", data={"text": "hello wall", "scope": "kreds",
                                   "placement": "profile"})
    assert r2.status_code == 200
    mid = r2.json()["msg_id"]

    view = c.get(f"/api/profile/{ident}").json()
    assert any(b["msg_id"] == mid and b["text"] == "hello wall"
              for b in view["wall"])

    # -- lock again: the same route 423s again ------------------------------
    node2.lock()
    assert node2.locked is True
    assert c.get(f"/api/profile/{ident}").status_code == 423

    # -- unlock again: storage_key/enc_priv round-tripped through DPAPI ----
    r3 = c.post("/api/unlock", json={"credential": "1234"})
    assert r3.status_code == 200
    assert node2.locked is False

    view2 = c.get(f"/api/profile/{ident}").json()
    block = next((b for b in view2["wall"] if b["msg_id"] == mid), None)
    assert block is not None and block["text"] == "hello wall", (
        "the profile block must still decrypt after a full lock->unlock "
        "cycle -- its cached content key (sealed under storage_key) and "
        "its envelope wrap (sealed under enc_priv) both live inside the "
        "one DPAPI+scrypt-sealed secret bundle, and this test does not "
        "rotate either key across the cycle, so either path reopening it "
        "proves the bundle round-tripped intact. (The narrower claim that "
        "the CACHE path specifically survives independent of the envelope "
        "-- e.g. after the envelope key itself is gone -- is proven by "
        "tests/test_node_dm.py's "
        "test_recipient_caches_on_first_read_then_survives_key_loss and "
        "test_decrypts_via_retired_key_after_rotation, not repeated here.)")

    assert time.time() - t0 < 15, "lifecycle must terminate fast, not hang"


def test_wrong_credential_unlock_throttles_escalating(tmp_path):
    """Repeated wrong-credential unlocks over the real HTTP API escalate the
    in-memory delay (never auto-wipe anything) -- driven without any real
    sleeping by clearing the in-memory deadline between attempts (the same
    technique test_applock_api.py's test_success_resets_throttle uses), so
    this test is deterministic and fast. Bounds (not exact-equality) match
    test_applock_api.py's test_throttle_escalation_tiers, which pins the
    same tiers at the node-method level with no HTTP/timing involved --
    this test proves the escalation is observable in the actual /api/unlock
    response body, across a real reboot with real DPAPI."""
    node_dir = tmp_path / "n"
    node = HearthNode.create(node_dir, "Wong", "wong-phone")
    node.enable_applock("1234", "pin")
    node2 = HearthNode(node_dir)
    assert node2.locked is True
    c = TestClient(build_app(node2))

    waits = []
    for _ in range(8):
        node2._unlock_next_allowed = 0.0        # elapse any pending window
        r = c.post("/api/unlock", json={"credential": "wrong"})
        assert r.status_code == 401
        waits.append(r.json()["throttle_wait"])

    assert waits[0] == 0 and waits[1] == 0                   # n=1,2: no delay
    assert 0 < waits[2] <= 5 and 0 < waits[3] <= 5            # n=3,4: 5s tier
    assert 5 < waits[4] <= 30 and 5 < waits[5] <= 30 and 5 < waits[6] <= 30
    assert 30 < waits[7] <= 300                               # n=8: 300s tier
    assert node2.locked is True                 # never auto-wiped, never unlocked

    # the RIGHT credential still works once the window has elapsed
    node2._unlock_next_allowed = 0.0
    r_ok = c.post("/api/unlock", json={"credential": "1234"})
    assert r_ok.status_code == 200
    assert node2.locked is False
    assert node2.throttle_wait() == 0           # success resets the counter
