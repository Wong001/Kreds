"""compose_response (responder side, spec 2026-07-18): author-addressed,
private-by-default responses to a journal post, with an anonymous mutual
box carrying the responder's real identity + signature. Ceremony idiom
follows tests/test_wrap_grants_store.py's befriend_with_enckeys (hand-
carried store-level sync, no real sockets needed) rather than
test_three_nodes.py's full invite ceremony + live SyncService -- both
exist in this codebase; this file picks the lighter one since nothing
here needs real network behavior."""
import pytest

from hearth.dmcrypt import response_aad, unwrap_key, decrypt_body
from hearth.identity import canonical, _sig_ok
from hearth.messages import KIND_RESPONSE
from hearth.node import HearthNode


def _sync(a, b):
    """Hand-carry every message `a` holds (authored by `a`) that `b`
    hasn't ingested yet -- mirrors one direction of a real gossip round
    without needing real sockets (same idiom as test_wrap_grants_store.py's
    befriend_with_enckeys)."""
    for m in a.store.messages_not_in({}, {a.identity_pub}, b.identity_pub):
        b.store.ingest_message(m)


def _befriend(a, b):
    a.store.add_identity(b.identity_pub)
    b.store.add_identity(a.identity_pub)
    a.ensure_enckey()
    b.ensure_enckey()
    _sync(a, b)          # b learns a's enckey
    _sync(b, a)          # a learns b's enckey


def _befriended_pair(tmp_path):
    a = HearthNode.create(tmp_path / "a", "A", "a-dev")
    b = HearthNode.create(tmp_path / "b", "B", "b-dev")
    _befriend(a, b)
    return a, b


def _decrypt_response_as(node, msg):
    """Decrypt a KIND_RESPONSE row as its recipient would: same envelope-
    unwrap primitives node._content_key uses (unwrap_key + decrypt_body),
    with the AAD response_aad computes for this kind (_content_key itself
    doesn't special-case KIND_RESPONSE yet -- that lands with the
    author-side read path)."""
    p = msg.payload
    aad = response_aad(msg.cert.identity_pub, p["target"], p["created_at"])
    for priv in node.device.enc_privs():
        key = unwrap_key(p["wraps"], node.device.device_pub, priv, aad)
        if key is not None:
            return decrypt_body(key, p["body_nonce"], p["body_ct"], aad)
    return None


def test_compose_response_routes_to_author_only(tmp_path):
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("hello journal", "kreds")
    _sync(a, b)                              # b holds the post
    rid = b.compose_response(pid, "reaction", "heart")
    msg = b.store.get_message(rid)
    assert msg.payload["kind"] == KIND_RESPONSE
    # wrapped to A's devices (+ possibly B's own) - never to a third party
    a_devs = set(a.store.enckeys(a.identity_pub))
    assert set(msg.payload["wraps"]) <= a_devs | set(
        b.store.enckeys(b.identity_pub))
    assert a_devs & set(msg.payload["wraps"])


def test_compose_response_validation(tmp_path):
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    with pytest.raises(ValueError):
        b.compose_response(pid, "reaction", "thumbsdown")   # not a token
    with pytest.raises(ValueError):
        b.compose_response(pid, "comment", "x" * 501)       # over cap
    with pytest.raises(ValueError):
        b.compose_response("nope" * 8, "comment", "hi")     # unknown target
    with pytest.raises(ValueError):
        b.compose_response(pid, "sneer", "hi")              # bad rkind


def test_response_body_privacy_fields(tmp_path):
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    rid = b.compose_response(pid, "comment", "hej")
    # decrypt as the AUTHOR would: content key from wraps
    body = _decrypt_response_as(a, b.store.get_message(rid))
    assert body["rkind"] == "comment" and body["body"] == "hej"
    assert body["public"] is False                     # default OFF
    assert len(body["alias_seed"]) == 32
    assert isinstance(body["mutual_box"], list) and len(body["mutual_box"]) >= 8
    # sig verifies over the canonical form compose_response actually
    # signed -- identity.py's real raw-sign/verify pair is
    # DeviceKeys.sign_raw (signer) / module-level _sig_ok (verifier);
    # there is no "verify_over_canonical" name in identity.py.
    #
    # sign_raw signs with the DEVICE key (not the identity key), so the
    # verifying public key is the response envelope's own cert.device_pub
    # -- NOT body["responder"] (that field is the identity_pub, verified
    # separately via the standard cert chain: cert.verify() already
    # proves device_pub is this identity's enrolled device).
    resp_msg = b.store.get_message(rid)
    sig_body = canonical({"target": resp_msg.payload["target"],
                          "rkind": body["rkind"], "body": body["body"],
                          "created_at": body["created_at"],
                          "responder": body["responder"]})
    assert _sig_ok(resp_msg.cert.device_pub, body["responder_sig"], sig_body)
    assert resp_msg.cert.identity_pub == body["responder"]   # cert <-> body agree


def test_compose_response_on_own_post_wraps_to_self(tmp_path):
    """Own-post responses take the SAME code path (spec: no special
    case) -- author == responder still yields a readable, routable
    KIND_RESPONSE wrapped to the responder's own device(s)."""
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("my own post", "kreds")
    rid = a.compose_response(pid, "reaction", "fire")
    msg = a.store.get_message(rid)
    assert msg.payload["kind"] == KIND_RESPONSE
    assert a.device.device_pub in msg.payload["wraps"]
    body = _decrypt_response_as(a, msg)
    assert body["rkind"] == "reaction" and body["body"] == "fire"


def test_response_never_leaks_to_a_third_friend_via_routing(tmp_path):
    """Routing find (Task 2, verified here for KIND_RESPONSE): without the
    store.messages_not_in wrap-set gate, a response would broadcast to
    every peer entitled to sync the responder's messages -- i.e. any of
    the RESPONDER's other friends, not just the addressed author. C is
    befriended with B (the responder) but not with A (the author) and
    must never receive B's response to A."""
    a, b = _befriended_pair(tmp_path)
    c = HearthNode.create(tmp_path / "c", "C", "c-dev")
    _befriend(b, c)
    pid = a.compose_post("hello journal", "kreds")
    _sync(a, b)
    rid = b.compose_response(pid, "reaction", "heart")
    offered_to_c = {m.msg_id for m in b.store.messages_not_in(
        {}, {b.identity_pub}, c.identity_pub)}
    assert rid not in offered_to_c
    offered_to_a = {m.msg_id for m in b.store.messages_not_in(
        {}, {b.identity_pub}, a.identity_pub)}
    assert rid in offered_to_a
