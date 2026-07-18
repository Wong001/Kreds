"""compose_response (responder side, spec 2026-07-18): author-addressed,
private-by-default responses to a journal post, with an anonymous mutual
box carrying the responder's real identity + signature. Ceremony idiom
follows tests/test_wrap_grants_store.py's befriend_with_enckeys (hand-
carried store-level sync, no real sockets needed) rather than
test_three_nodes.py's full invite ceremony + live SyncService -- both
exist in this codebase; this file picks the lighter one since nothing
here needs real network behavior."""
import dataclasses
import time
import types

import pytest

from hearth.dmcrypt import (encrypt_body, new_content_key, response_aad,
                            responses_aad, unwrap_key, decrypt_body, wrap_key)
from hearth.identity import canonical, _sig_ok
from hearth.messages import KIND_RESPONSE, make_response, make_responses
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


def _triangle_no_bc(tmp_path):
    """Three nodes: A is friends with both B and C; B and C are NOT
    friends with each other yet (test_three_nodes.py idiom: plain
    HearthNode.create per node, hand-carried sync). Exercises a viewer
    (C) who is a friend of the post's AUTHOR but a total stranger to the
    RESPONDER (B) -- exactly the case a private mutual-box entry must
    stay aliased for, until B and C separately befriend each other."""
    a = HearthNode.create(tmp_path / "a", "A", "a-dev")
    b = HearthNode.create(tmp_path / "b", "B", "b-dev")
    c = HearthNode.create(tmp_path / "c", "C", "c-dev")
    _befriend(a, b)
    _befriend(a, c)
    return a, b, c


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


def test_cache_message_keys_does_not_crash_on_response_rows(tmp_path):
    """Reviewer-caught Critical (2026-07-18): store.uncached_message_ids
    started including KIND_RESPONSE/KIND_RESPONSES (needed so a response's
    key gets cached on every device, not just the composer's), but
    node._content_key's old bare `else: # post` assumed any non-DM kind
    was a post and read p["scope"] -- a response payload has no "scope",
    so cache_message_keys()'s ~3s gossip-loop sweep KeyError'd on the
    first response row it saw and (caught by the loop's blanket except)
    silently starved caching for every uncached id after it, forever.
    This drives the real sweep end to end on the AUTHOR's node, which
    never composed the response itself and so must pick up its key
    purely from the sweep."""
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    rid = b.compose_response(pid, "comment", "hej")
    _sync(b, a)                      # response reaches A (untouched by compose)
    assert rid in a.store.uncached_message_ids(a.identity_pub)
    a.cache_message_keys()           # must not raise
    assert a.store.cached_message_key(rid) is not None     # key landed in the cache
    assert rid not in a.store.uncached_message_ids(a.identity_pub)  # no work left


def test_content_key_unknown_kind_is_defensive_not_fatal(tmp_path):
    """The final `else` in _content_key must return (None, None) for an
    unrecognized kind rather than raising -- validate_payload already
    refuses to let a real unknown-kind message be ingested (so this can
    never happen via real sync), but _content_key is a general helper
    called from a loop that must never crash on one bad row, so this
    drives it directly with a minimal duck-typed stand-in rather than
    trying to smuggle an invalid kind through ingest_message."""
    a = HearthNode.create(tmp_path / "unk", "A", "a-dev")
    fake = types.SimpleNamespace(
        payload={"kind": "mystery", "created_at": 1.0},
        msg_id="ff" * 32, cert=None)
    assert a._content_key(fake) == (None, None)


# -- Task 4: author sweep + responses record ---------------------------------
#
# _responses_record / _decrypt_record_as: the KIND_RESPONSES analogue of
# this file's own _decrypt_response_as -- _content_key already handles
# BOTH KIND_RESPONSE and KIND_RESPONSES (commit 84d8155), so unlike
# _decrypt_response_as (written before that landed, and left alone here
# since it still passes and touching it is out of this task's scope)
# these helpers can just call it directly.

def _responses_record(node, target):
    """The current (latest-wins) KIND_RESPONSES SignedMessage node holds
    for `target`, authored by node itself -- store.responses_record's
    keying (see hearth/store.py) mirrors albums()'s latest-wins fold,
    just scoped on `target` instead of `album_id`."""
    return node.store.responses_record(target, node.identity_pub)


def _decrypt_record_as(node, msg):
    key, aad = node._content_key(msg)
    if key is None:
        return None
    return decrypt_body(key, msg.payload["body_nonce"],
                        msg.payload["body_ct"], aad)


def test_author_sweep_republishes_record(tmp_path):
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    b.compose_response(pid, "comment", "fin kommentar")
    b.compose_response(pid, "reaction", "fire")
    _sync(b, a)                    # response reaches A; A's sweep runs in-sync-hook
    a.process_responses()          # idempotent double-call must be safe
    rec = _responses_record(a, pid)          # helper: find KIND_RESPONSES by target
    body = _decrypt_record_as(a, rec)
    kinds = sorted(e["rkind"] for e in body["entries"])
    assert kinds == ["comment", "reaction"]
    assert all("identity" not in e for e in body["entries"])   # private default


def test_reaction_latest_wins_and_clear(tmp_path):
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    b.compose_response(pid, "reaction", "heart")
    _sync(b, a); a.process_responses()
    b.compose_response(pid, "reaction", "laugh")
    _sync(b, a); a.process_responses()
    body = _decrypt_record_as(a, _responses_record(a, pid))
    reactions = [e for e in body["entries"] if e["rkind"] == "reaction"]
    assert len(reactions) == 1 and reactions[0]["body"] == "laugh"
    b.compose_response(pid, "reaction", "clear")
    _sync(b, a); a.process_responses()
    body = _decrypt_record_as(a, _responses_record(a, pid))
    assert not [e for e in body["entries"] if e["rkind"] == "reaction"]


def test_retract_and_moderation(tmp_path):
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    b.compose_response(pid, "comment", "first")
    b.compose_response(pid, "comment", "second")
    _sync(b, a); a.process_responses()
    body = _decrypt_record_as(a, _responses_record(a, pid))
    first = [e for e in body["entries"] if e["body"] == "first"][0]
    # responder retracts their own first comment
    b.compose_response(pid, "retract", str(first["created_at"]))
    _sync(b, a); a.process_responses()
    body = _decrypt_record_as(a, _responses_record(a, pid))
    assert [e["body"] for e in body["entries"]] == ["second"]
    # author moderates the second away
    a.remove_response(pid, b.identity_pub, [e for e in body["entries"]][0]["created_at"])
    body = _decrypt_record_as(a, _responses_record(a, pid))
    assert body["entries"] == []


def test_compose_response_created_at_strictly_increasing_same_tick(
        tmp_path, monkeypatch):
    """Controller-reported flake (2026-07-18): Windows' time.time()
    granularity is ~15.6ms, so two compose_response calls fired
    back-to-back (e.g. two comments) can land in the exact same tick and
    get an identical created_at. created_at is what retract/moderation/
    reaction-fold key entries by within one responder
    (_rebuild_responses_record), so a same-tick collision made
    retracting one comment silently wipe a DIFFERENT comment from the
    same responder that happened to share the timestamp --
    test_retract_and_moderation flaked exactly this way (controller
    reproduced fail-then-pass on the same commit). Pins the fix directly
    with a constant clock: created_at must still come out
    strictly-increasing, and the retract flow (the exact flake scenario)
    must come out deterministic."""
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    monkeypatch.setattr("hearth.node.time.time", lambda: 1_700_000_000.0)
    r1 = b.compose_response(pid, "comment", "first")
    r2 = b.compose_response(pid, "comment", "second")
    ca1 = b.store.get_message(r1).payload["created_at"]
    ca2 = b.store.get_message(r2).payload["created_at"]
    assert ca1 != ca2
    assert ca2 > ca1          # strictly increasing, not just "different"

    _sync(b, a); a.process_responses()
    body = _decrypt_record_as(a, _responses_record(a, pid))
    first = [e for e in body["entries"] if e["body"] == "first"][0]
    b.compose_response(pid, "retract", str(first["created_at"]))
    _sync(b, a); a.process_responses()
    body = _decrypt_record_as(a, _responses_record(a, pid))
    assert [e["body"] for e in body["entries"]] == ["second"]


def test_post_delete_tombstones_record(tmp_path):
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    b.compose_response(pid, "comment", "hej")
    _sync(b, a); a.process_responses()
    rec = _responses_record(a, pid)
    a.delete_post(pid)
    assert _responses_record(a, pid) is None or \
        a.store.get_message(rec.msg_id) is None


# -- reviewer follow-up fixes (2026-07-18) ------------------------------------

def _hostile_response(node, target, author_devs, overrides, created_at=None):
    """Build a KIND_RESPONSE exactly as node's REAL device would sign one
    (make_response -> device.sign_message, legitimate crypto identity end
    to end), but with an ATTACKER-CONTROLLED plaintext body -- this is
    what a modified client sends: a real, validly-signed envelope wrapping
    hostile decrypted content. validate_payload/ingest_message never see
    the plaintext (it is opaque ciphertext to them), so this ingests
    clean; only node._response_event's own body-shape validation can ever
    catch it."""
    created_at = created_at if created_at is not None else time.time()
    body = {
        "rkind": "comment", "body": "innocuous", "alias_seed": "a" * 32,
        "public": False, "responder": node.identity_pub,
        # 128 hex chars: a VALID-shaped (though not cryptographically
        # real) Ed25519 sig by default, so each hostile_variants override
        # below isolates exactly one bad field instead of every variant
        # failing on this one regardless of what it's actually testing.
        "responder_sig": "a" * 128, "mutual_box": None,
        "created_at": created_at,
    }
    body.update(overrides)
    key = new_content_key()
    aad = response_aad(node.identity_pub, target, created_at)
    nonce, ct = encrypt_body(key, body, aad)
    wraps = wrap_key(key, author_devs, aad)
    return make_response(node.device, target, nonce, ct, wraps,
                         created_at=created_at)


def test_hostile_response_content_never_folds(tmp_path):
    """Critical (reviewer-repro'd, two rounds): _response_event used to
    type-check only rkind/created_at -- a hand-crafted oversized comment,
    a dict-typed reaction body, a malformed alias_seed, a junk
    mutual_box, an oversized responder_sig, or a mutual_box slot with
    junk (non-eph_pub/nonce/ct) keys all survived verbatim into the
    audience-broadcast record. Every hostile variant below must ingest
    fine (envelope-level shape is valid) but never appear in the
    republished record, and the sweep must never raise."""
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    author_devs = a.store.enckeys(a.identity_pub)
    hostile_variants = [
        {"rkind": "comment", "body": "x" * 5000},          # oversized comment
        {"rkind": "reaction", "body": {"evil": True}},      # dict body
        {"rkind": "reaction", "body": "thumbsdown"},        # non-token reaction
        {"alias_seed": "not-hex!!!!!!!!!!!!!!!!!!!!!!!!"},  # junk alias shape
        {"alias_seed": "ab" * 10},                          # wrong-length hex
        {"mutual_box": "not-a-list"},                       # junk box (not a list)
        {"mutual_box": [1, 2, 3]},                          # list of non-dicts
        {"public": "yes"},                                  # non-bool public
        {"responder_sig": "f" * 3_000_000},                 # oversized sig (reviewer repro)
        {"responder_sig": "short"},                         # wrong-length sig
        # reviewer's exact repro: a dict slot with junk keys (no
        # eph_pub/nonce/ct at all) passing a bare isinstance(s, dict) check
        {"mutual_box": [{"junk": "J" * 2_000_000}]},
    ]
    t = time.time()
    for i, overrides in enumerate(hostile_variants):
        msg = _hostile_response(b, pid, author_devs, overrides,
                                created_at=t + i)
        result = b.store.ingest_message(msg)
        assert result.accepted, overrides   # envelope-level shape is valid
    _sync(b, a)
    n = a.process_responses()               # must never raise
    assert n == 0
    rec = _responses_record(a, pid)
    assert rec is None                      # nothing hostile ever folded

    # A legitimate response alongside the hostile batch still folds fine
    # (fail-closed drops the bad rows, not the whole sweep).
    b.compose_response(pid, "comment", "a real comment")
    _sync(b, a)
    a.process_responses()
    body = _decrypt_record_as(a, _responses_record(a, pid))
    assert [e["body"] for e in body["entries"]] == ["a real comment"]


def test_forged_responder_dropped(tmp_path):
    """The body-vs-cert identity mismatch guard (already implemented):
    a response whose body lies about who sent it (claims some OTHER
    identity_pub as `responder`, while the cryptographic sender is really
    B) must never fold into the record under either identity."""
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    author_devs = a.store.enckeys(a.identity_pub)
    forged = _hostile_response(b, pid, author_devs,
                               {"responder": a.identity_pub,
                                "body": "i am forging someone else"})
    result = b.store.ingest_message(forged)
    assert result.accepted
    _sync(b, a)
    n = a.process_responses()
    assert n == 0
    assert _responses_record(a, pid) is None


def test_remove_response_requires_own_post(tmp_path):
    """Important (reviewer): remove_response had no ownership check --
    an author could be tricked (or a caller could pass the wrong
    post_id) into moderating a FRIEND-authored post. Must raise, mirroring
    compose_response's defensive ValueError style."""
    a, b = _befriended_pair(tmp_path)
    pid = b.compose_post("b's own post", "kreds")
    _sync(b, a)
    with pytest.raises(ValueError):
        a.remove_response(pid, b.identity_pub, 123.0)


def test_reaction_moderation_cutoff_suppresses_older_reaction(tmp_path):
    """Important (reviewer-repro'd, previously flagged as an unverified
    judgment call): moderating a responder's LATEST reaction must not let
    an OLDER reaction from that same responder resurface as the new
    latest-wins winner. Moderating a reaction is a cutoff -- every
    reaction from that responder at or before the removed timestamp is
    suppressed. Comment moderation stays exact-match (proven by
    test_retract_and_moderation, unchanged)."""
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    b.compose_response(pid, "reaction", "heart")
    _sync(b, a); a.process_responses()
    b.compose_response(pid, "reaction", "laugh")
    _sync(b, a); a.process_responses()
    body = _decrypt_record_as(a, _responses_record(a, pid))
    laugh = [e for e in body["entries"] if e["rkind"] == "reaction"][0]
    assert laugh["body"] == "laugh"
    a.remove_response(pid, b.identity_pub, laugh["created_at"])
    body = _decrypt_record_as(a, _responses_record(a, pid))
    assert not [e for e in body["entries"] if e["rkind"] == "reaction"]


def test_removed_responses_migration_heals_existing_node_db(tmp_path):
    """CRITICAL (reviewer-repro'd, 2026-07-18) end-to-end through a real
    HearthNode: see test_store.py's store-level pin for the mechanism.
    Pre-create A's data dir with the OLD 3-column removed_responses
    table at the exact path HearthNode.create will open (hearth.db),
    boot A normally, and confirm process_responses' fold AND
    remove_response's moderation write path both work against the
    healed schema -- not just that the migration runs, but that the
    whole responses feature is usable afterward."""
    import sqlite3

    data_dir = tmp_path / "a"
    data_dir.mkdir(parents=True)
    conn = sqlite3.connect(str(data_dir / "hearth.db"))
    conn.execute(
        "CREATE TABLE removed_responses("
        "target TEXT NOT NULL, responder TEXT NOT NULL,"
        " created_at REAL NOT NULL,"
        " PRIMARY KEY(target, responder, created_at))")
    conn.commit()
    conn.close()

    a = HearthNode.create(data_dir, "A", "a-dev")   # must not raise
    b = HearthNode.create(tmp_path / "b", "B", "b-dev")
    _befriend(a, b)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    b.compose_response(pid, "reaction", "heart")
    _sync(b, a)
    n = a.process_responses()          # fold: must not raise on the healed table
    assert n == 1
    body = _decrypt_record_as(a, _responses_record(a, pid))
    assert body["entries"][0]["body"] == "heart"
    entry_created_at = body["entries"][0]["created_at"]
    a.remove_response(pid, b.identity_pub, entry_created_at)   # write: must not raise
    body = _decrypt_record_as(a, _responses_record(a, pid))
    assert body["entries"] == []


def test_legacy_comment_tombstone_removes_reaction_exact_match(tmp_path):
    """Reviewer-caught bug (2026-07-18, confirmation pass): a
    migration-defaulted 'comment'-rkind tombstone (Store.__init__'s
    ALTER TABLE ... DEFAULT 'comment' backfills any pre-rkind row this
    way -- there is no way to recover whether such a row was originally
    a comment or a reaction removal) that actually named a REACTION
    event was a silent no-op -- the reaction fold branch never
    consulted removed_exact at all (only reaction_cutoff), and a
    'comment'-rkind row always lands in removed_exact, never
    reaction_cutoff. Exercises the real migration path (pre-seed the
    OLD 3-column table before first boot) AND the specific
    legacy-tombstone-targets-a-reaction scenario, confirming the fold
    now honors it as an exact match."""
    import sqlite3

    data_dir = tmp_path / "a"
    data_dir.mkdir(parents=True)
    conn = sqlite3.connect(str(data_dir / "hearth.db"))
    conn.execute(
        "CREATE TABLE removed_responses("
        "target TEXT NOT NULL, responder TEXT NOT NULL,"
        " created_at REAL NOT NULL,"
        " PRIMARY KEY(target, responder, created_at))")
    conn.commit()
    conn.close()

    a = HearthNode.create(data_dir, "A", "a-dev")   # migration runs here
    b = HearthNode.create(tmp_path / "b", "B", "b-dev")
    _befriend(a, b)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    b.compose_response(pid, "reaction", "heart")
    _sync(b, a)
    a.process_responses()
    body = _decrypt_record_as(a, _responses_record(a, pid))
    reaction = [e for e in body["entries"] if e["rkind"] == "reaction"][0]
    assert reaction["body"] == "heart"

    # Simulate the legacy tombstone directly: rkind='comment' is exactly
    # what a pre-migration row would carry after the ALTER's DEFAULT
    # backfills it (the store has no way to know it was really a
    # reaction) -- naming THIS reaction's own (responder, created_at).
    a.store.mark_response_removed(pid, b.identity_pub,
                                  reaction["created_at"], "comment")
    a.process_responses()
    body = _decrypt_record_as(a, _responses_record(a, pid))
    assert not [e for e in body["entries"] if e["rkind"] == "reaction"]


# -- Task 5: viewer assembly (_post_responses_view via feed()) ---------------

def test_feed_rows_carry_responses(tmp_path):
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    b.compose_response(pid, "reaction", "heart")
    b.compose_response(pid, "comment", "hej fra B")
    # _sync(b, a): the raw responses reach A first (mirrors test_author_
    # sweep_republishes_record's own "response reaches A" comment) so A's
    # fold has something to fold; _sync(a, b) afterward hands B the
    # freshly-rebuilt record so b.feed() below can decrypt it.
    _sync(b, a); a.process_responses(); _sync(a, b)
    row_a = [p for p in a.feed() if p["msg_id"] == pid][0]
    assert row_a["responses"]["reactions"] == {"heart": 1}
    assert row_a["responses"]["comments"][0]["name"]      # author sees real B
    assert row_a["responses"]["can_moderate"] is True
    row_b = [p for p in b.feed() if p["msg_id"] == pid][0]
    assert row_b["responses"]["my_reaction"] == "heart"
    assert row_b["responses"]["comments"][0]["mine"] is True


def test_stranger_sees_alias_mutual_sees_name(tmp_path):
    # A authors; B and C are A's friends but NOT each other's
    a, b, c = _triangle_no_bc(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b); _sync(a, c)
    b.compose_response(pid, "comment", "privat hilsen")
    # _sync(b, a): B's raw comment reaches A (the author) first, same
    # direction fix as test_feed_rows_carry_responses above; _sync(a, c)
    # then hands C (a friend of A, NOT of B) the rebuilt record.
    _sync(b, a); a.process_responses(); _sync(a, c)
    row_c = [p for p in c.feed() if p["msg_id"] == pid][0]
    entry = row_c["responses"]["comments"][0]
    assert entry["alias"] is True and "name" not in entry or entry.get("name") is None
    # now with B<->C friends, C resolves the real name
    _befriend(b, c); _sync(b, c)
    b2 = b.compose_response(pid, "comment", "nu venner")
    _sync(b, a); a.process_responses(); _sync(a, c)
    row_c = [p for p in c.feed() if p["msg_id"] == pid][0]
    named = [e for e in row_c["responses"]["comments"] if e["body"] == "nu venner"]
    assert named and named[0]["alias"] is False and named[0]["name"]
    # the earlier comment legitimately stays aliased for C: it was sealed
    # to B's friends AT THAT TIME, and C wasn't one of them yet.
    assert [e for e in row_c["responses"]["comments"]
           if e["body"] == "privat hilsen"][0]["alias"] is True


def test_public_engagement_resolves_even_for_a_stranger_of_the_responder(tmp_path):
    """public_engagement's whole point (spec 2026-07-18): the responder's
    real identity should be visible even to a viewer who is a total
    stranger to THEM (only a friend of the post's author) -- unlike the
    private/mutual-box default, a public entry must not need C to be
    B's friend at all. This also exercises _device_bound's permissive-
    when-no-data-held branch: C has never exchanged a single message
    with B (they aren't friends), so C's store holds no device_views row
    for B whatsoever -- the binding check can't refute the claim, so
    (per its documented v1 bar) it lets the sig-verified identity
    through rather than aliasing a legitimate public comment."""
    a, b, c = _triangle_no_bc(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b); _sync(a, c)
    b.store.set_meta("public_engagement", "1")
    b.compose_response(pid, "comment", "public hello")
    _sync(b, a); a.process_responses(); _sync(a, c)
    row_c = [p for p in c.feed() if p["msg_id"] == pid][0]
    entry = row_c["responses"]["comments"][0]
    assert entry["alias"] is False
    assert entry["responder"] == b.identity_pub
    # C never synced B's profile (they aren't friends) -- identity
    # resolution (the thing this test targets) is independent of name
    # display, which falls back the same way author_name/kreds_list do.
    assert entry["name"] == b.identity_pub[:8]


def test_feed_row_has_no_responses_key_when_untouched(tmp_path):
    a = HearthNode.create(tmp_path / "a", "A", "a-dev")
    pid = a.compose_post("post with no engagement", "kreds")
    row = [p for p in a.feed() if p["msg_id"] == pid][0]
    assert row["responses"] is None


def test_malformed_responses_record_body_does_not_crash_feed(tmp_path):
    """Reviewer-caught Critical (2026-07-18): decrypt_body's Optional[dict]
    return type is a hint, not enforced -- json.loads happily returns
    whatever JSON type the plaintext root actually is. A KIND_RESPONSES
    record whose encrypted body is a JSON list (not an object) -- a buggy
    client, or a hostile one skipping the honest process_responses fold --
    used to reach body.get("entries") on a list, raising an uncaught
    AttributeError that 500'd feed()/posts_by() for EVERY post, not just
    the malformed one. Must degrade to responses=None for just this row;
    a second, healthy post's responses must still assemble normally."""
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    healthy_pid = a.compose_post("healthy post", "kreds")
    _sync(a, b)
    b.compose_response(healthy_pid, "reaction", "heart")
    _sync(b, a); a.process_responses()

    # Hand-craft the malformed record directly (bypassing make_responses'
    # normal {"entries": [...]} body) -- exactly what a hostile/buggy
    # author's own client could publish for one of their own posts.
    pubs = a._scope_device_pubs("kreds")
    key = new_content_key()
    created_at = time.time()
    aad = responses_aad(a.identity_pub, pid, created_at)
    nonce, ct = encrypt_body(key, ["not", "a", "dict"], aad)
    wraps = wrap_key(key, pubs, aad)
    a._publish(make_responses(a.device, pid, nonce, ct, wraps,
                              created_at=created_at))

    rows = a.feed()                       # must not raise
    row = [p for p in rows if p["msg_id"] == pid][0]
    assert row["responses"] is None
    healthy_row = [p for p in rows if p["msg_id"] == healthy_pid][0]
    assert healthy_row["responses"]["reactions"] == {"heart": 1}


# -- Task 8: mixed-version / old-core compat ---------------------------------

def test_old_core_refuses_response_kind_and_keeps_reoffering(tmp_path):
    """Mixed-version compat, mirroring tests/test_wrap_grant_messages.py's
    test_unknown_kind_still_refused (the "fallthrough old peers rely on"
    pin, written for KIND_WRAP_GRANT) plus the messages_not_in offer-set
    idiom used throughout tests/test_wrap_grants_store.py: an old core
    that predates KIND_RESPONSE has no branch for it in validate_payload,
    so it falls through to the exact same "unknown kind" refusal every
    unrecognized kind gets today. Simulated per the plan's "simplest
    honest simulation" by mangling a REAL, validly-signed KIND_RESPONSE's
    kind string to something no branch recognizes ("response-future")
    rather than monkeypatching the KIND_RESPONSE branch out of
    validate_payload -- the fallthrough is identical either way, and this
    needs no crypto workaround because Store.ingest_message runs
    validate_payload BEFORE signature verification (store.py: the
    validate_payload call sits above the Verifier() call), so a mangled
    payload is refused on shape alone, never reaching signature checks.

    Per wrap_grant's own mixed-version note (ROADMAP item 20 / the
    2026-07-15-wall-wrap-grants plan Sec.5, "verified at planning time"):
    a refused seq never enters the old peer's seen-set, so the NEW peer
    keeps re-offering it every round until the old peer updates -- bounded
    chatter, not silently dropped, and not a one-shot retry. Checked here
    by querying messages_not_in twice (two simulated rounds) and
    confirming the REAL response b actually holds is offered to a both
    times, since nothing in b's own bookkeeping ever marks a delivery
    attempt as done -- that only happens via a's own reported summary,
    and an old a can never report having it."""
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    rid = b.compose_response(pid, "comment", "hi from the future")
    real_msg = b.store.get_message(rid)

    # simulate what an old core's validate_payload sees for a kind it
    # doesn't know about yet: the real envelope, kind mangled
    mangled = dataclasses.replace(
        real_msg, payload={**real_msg.payload, "kind": "response-future"})
    result = a.store.ingest_message(mangled)
    assert not result.accepted and result.reason == "unknown kind"
    assert a.store.get_message(mangled.msg_id) is None
    # the real (unmangled) response is untouched by the simulation above
    # and never separately reached a's store at all
    assert a.store.get_message(rid) is None

    # round 1: b still offers the real response to a
    offered_round1 = {m.msg_id for m in b.store.messages_not_in(
        {}, {b.identity_pub}, a.identity_pub)}
    assert rid in offered_round1
    # round 2: nothing changed on a's side (old core still refuses it on
    # ingest), so b keeps re-offering -- the bounded-forever-chatter shape
    # wrap_grant's mixed-version note describes, confirmed idempotent
    # rather than a single retry that then gives up
    offered_round2 = {m.msg_id for m in b.store.messages_not_in(
        {}, {b.identity_pub}, a.identity_pub)}
    assert rid in offered_round2
