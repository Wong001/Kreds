"""API layer for the easier friend-add flow (Task 3): POST /api/friend/add
wraps node.add_friend_via_invite (Task 2) -- B pastes A's invite code and
the API tries to auto-connect over Tor, falling back to a manual response
code when A is unreachable. /api/friend/respond|finalize|complete (Task 1,
unchanged) remain the manual copy-paste fallback the client hands the user
when this returns "manual"."""
import json

from fastapi.testclient import TestClient

from hearth.api import build_app
from hearth.node import HearthNode


def _pair(tmp_path):
    a = HearthNode.create(tmp_path / "a", "A", "a-dev")
    b = HearthNode.create(tmp_path / "b", "B", "b-dev")
    # add_friend_via_invite only attempts a dial when the invite carries an
    # address (truthy) -- doesn't need to be reachable since _friend_dial is
    # stubbed below, just present.
    a.store.set_meta("gossip_addr", "127.0.0.1:9")
    return a, b


def test_friend_add_connected_when_dial_returns_valid_final(tmp_path):
    a, b = _pair(tmp_path)
    invite = a.create_invite()

    # Stand in for the real Tor dial: drive A's own finalize_invite
    # in-process (exactly what a reachable A would compute and hand back
    # over the wire) so the stub returns a genuinely valid friend-final.
    async def fake_dial(address, response_json):
        return a.finalize_invite(response_json)
    b._friend_dial = fake_dial

    c = TestClient(build_app(b))
    r = c.post("/api/friend/add", json={"payload": invite})
    assert r.status_code == 200
    body = r.json()
    # No profile message has been exchanged yet (that's a separate gossip
    # step) -- add_friend_via_invite's "friend" name falls back to A's
    # identity-pub prefix, same as node.py's own fallback.
    assert body == {"status": "connected", "friend": a.identity_pub[:8]}
    assert b.store.is_known(a.identity_pub)
    assert a.store.is_known(b.identity_pub)


def test_friend_add_manual_when_dial_returns_none(tmp_path):
    a, b = _pair(tmp_path)
    invite = a.create_invite()

    async def fake_dial(address, response_json):
        return None                      # A unreachable
    b._friend_dial = fake_dial

    c = TestClient(build_app(b))
    r = c.post("/api/friend/add", json={"payload": invite})
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "manual"
    assert body["response"]              # B's response, for the manual fallback
    # nobody added yet -- the manual path still requires finalize+complete
    assert not b.store.is_known(a.identity_pub)
    assert not a.store.is_known(b.identity_pub)


def test_friend_add_manual_result_still_completes_by_hand(tmp_path):
    """The response returned in the "manual" branch is exactly what
    /api/friend/finalize (A's side) + /api/friend/complete (B's side)
    already accept -- the offline fallback the client hands the user must
    still work end to end."""
    a, b = _pair(tmp_path)
    invite = a.create_invite()

    async def fake_dial(address, response_json):
        return None
    b._friend_dial = fake_dial

    c = TestClient(build_app(b))
    r = c.post("/api/friend/add", json={"payload": invite})
    response = r.json()["response"]

    final = a.finalize_invite(response)
    b.complete_invite(final)
    assert a.store.is_known(b.identity_pub) and b.store.is_known(a.identity_pub)


def test_friend_add_no_dial_wired_falls_back_to_manual(tmp_path):
    # A node with no SyncService attached (_friend_dial is None, the node.py
    # default) must behave exactly like an unreachable peer -- manual, not
    # a crash on calling a None dialer.
    a, b = _pair(tmp_path)
    invite = a.create_invite()
    assert b._friend_dial is None

    c = TestClient(build_app(b))
    r = c.post("/api/friend/add", json={"payload": invite})
    assert r.status_code == 200
    assert r.json()["status"] == "manual"


def test_friend_add_malformed_json_400(tmp_path):
    b = HearthNode.create(tmp_path / "b", "B", "b-dev")
    c = TestClient(build_app(b))
    r = c.post("/api/friend/add", json={"payload": "not json"})
    assert r.status_code == 400


def test_friend_add_missing_cert_key_400(tmp_path):
    # Valid JSON, right "t", but missing the "cert" key -> KeyError inside
    # respond_to_invite's EnrollmentCert.from_dict -- must 400, not 500.
    b = HearthNode.create(tmp_path / "b", "B", "b-dev")
    c = TestClient(build_app(b))
    bad = json.dumps({"t": "hearth-invite", "nonce": "x"})
    r = c.post("/api/friend/add", json={"payload": bad})
    assert r.status_code == 400


def test_friend_add_missing_payload_key_400(tmp_path):
    # body has no "payload" key at all -> body["payload"] raises KeyError
    # in the handler itself.
    b = HearthNode.create(tmp_path / "b", "B", "b-dev")
    c = TestClient(build_app(b))
    r = c.post("/api/friend/add", json={})
    assert r.status_code == 400


def test_friend_add_actually_expired_invite_falls_back_to_manual_not_400(tmp_path):
    # Honesty note: respond_to_invite (B's side) never reads the invite's
    # own expires_at field -- only A's finalize_invite enforces TTL, against
    # its own _pending_invites bookkeeping. So a genuinely-expired invite
    # (A's pending nonce aged out) still produces a well-formed response on
    # B's side; it just can't be completed automatically, so it falls
    # through the SAME manual branch as "unreachable" rather than 400ing.
    # The client's own countdown UI is what stops a user from even trying
    # to submit an already-expired code (see the Share tab's countdown +
    # "Code expired" state in app.js).
    a, b = _pair(tmp_path)
    invite = a.create_invite(ttl_seconds=-1)    # already expired on A's side

    async def fake_dial(address, response_json):
        # A's finalize_invite RAISES ValueError("invite expired") rather
        # than returning None -- the real network dial (SyncService.
        # deliver_friend_add) never lets that escape to the caller, it just
        # returns None on any refusal. Mirror that contract here.
        try:
            return a.finalize_invite(response_json)
        except ValueError:
            return None
    b._friend_dial = fake_dial

    c = TestClient(build_app(b))
    r = c.post("/api/friend/add", json={"payload": invite})
    assert r.status_code == 200
    assert r.json()["status"] == "manual"


def test_friend_add_423_while_locked(tmp_path):
    # Mirrors test_applock_api.py's locked-guard tests: /api/friend/add is
    # not in _APPLOCK_ALLOWLIST, so locked_gate 423s before the handler (and
    # any node.add_friend_via_invite call) ever runs.
    n = HearthNode.create(tmp_path / "n", "B", "b-dev")
    n.enable_applock("1234", "pin")
    n.lock()
    c = TestClient(build_app(n))
    r = c.post("/api/friend/add", json={"payload": "irrelevant-while-locked"})
    assert r.status_code == 423
