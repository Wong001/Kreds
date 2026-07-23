"""Desk loopback: seed a node with own-identity messages + blobs, mint the
phone fixture, serve SyncService on 127.0.0.1, print a JSON handshake line
({port, fixture, expect}), then serve until killed. Driven by
SyncLoopbackTest.kt (BB-5, the desk loopback gate).

Task 6 (B.2) extension: after EVERY connection's session fully completes,
node.maintain_own_device_grants() runs and a `{"event": "maintained"}` line
is printed -- see main() below. This is what lets a second connection prove
it pulls wrap_grants minted from a first connection's pushed enckey,
deterministically (the test blocks on the signal line rather than racing
connection timing).

`expect` is computed from the node's actual store state -- never
hardcoded -- mirroring exactly what store.messages_not_in() /
missingBlobs-equivalent logic the phone will receive during the real
sync session (hearth/sync.py:626-628 HAVE/MESSAGES phase): the phone
(own-identity peer) is entitled to every own-identity message, and to
every blob hash referenced (blobs/poster/thumbs) by those messages that
the node actually holds.

Task 4 (B.2c) extension: an OPT-IN second scenario ("two_node", selected
by an optional second CLI arg -- default "solo" reproduces every byte of
the ORIGINAL single-node behavior above, untouched) adds a second, real
in-process HearthNode ("Freja") befriended with the desk node, so the
desk loopback gate can prove the phone reads FRIEND content (wall posts,
an old DM backfilled via a recipient-signed grant, and a new DM that
inline-wraps the phone directly) end-to-end over the real wire, not just
its own-identity history. See _seed_friend_and_befriend/_run_phase3
below for the scenario and SyncLoopbackTest.kt's
phoneReadsFriendContentEndToEnd for the assertions.

Node-to-node content movement between the desk and friend nodes is DIRECT
IN-PROCESS DELIVERY (store.messages_not_in + store.ingest_message, both
directions, bounded rounds with an assert -- see _deliver/
_gossip_until_converged) rather than a second real network sync: this
script is gating the PHONE<->desk-node wire protocol, which is the only
leg that needs to be real; hearth/demo.py's real Tor/TCP gossip between
two independent people's devices is heavier prior art aimed at a
different question (proving the transport itself), and
tests/test_own_device_grants.py's / tests/test_received_dm_grants.py's
own _befriend_with_enckeys/_deliver helpers already establish this exact
direct-delivery pattern is sufficient and deterministic for moving
content between two HearthNode instances in one process."""
import asyncio
import io
import json
import sys
import time
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))  # repo root
from hearth import dmcrypt, invitecodec
from hearth.identity import _sig_ok
from hearth.messages import _valid_story_ref
from hearth.node import HearthNode
from hearth.sync import SyncService

sys.path.insert(0, str(Path(__file__).resolve().parent))  # this dir (mint.py)
from mint import mint_fixture   # reused from the spike


def _tiny_png() -> bytes:
    """A minimal real (decodable) image -- compose_post's photo gate
    (transcode_photo) opens and re-encodes it, so raw junk bytes would
    raise ValueError("not an image"). An 8x8 solid square is the
    smallest thing that round-trips through the gate + thumbnailer."""
    img = Image.new("RGB", (8, 8), (200, 30, 30))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


def _compute_expect(node) -> dict:
    """Mirrors hearth/sync.py's own-identity HAVE/MESSAGES/BLOBS phases
    exactly (see _session, sync.py:602-663): a fresh phone fixture's
    SyncStore starts knowing only the node's own identity_pub, so
    entitled == {node.identity_pub} and peer_identity == node.identity_pub
    -- messages_not_in({}, {node.identity_pub}, node.identity_pub) is
    exactly the message set the node will hand the phone."""
    to_send = node.store.messages_not_in(
        {}, {node.identity_pub}, node.identity_pub)
    refs = set()
    for m in to_send:
        p = m.payload
        refs |= {b for b in (p.get("blobs") or []) if isinstance(b, str)}
        poster = p.get("poster")
        if isinstance(poster, str) and poster:
            refs.add(poster)
        refs |= {t for t in (p.get("thumbs") or []) if isinstance(t, str) and t}
    expect_blobs = len([h for h in refs if node.store.has_blob(h)])
    return {
        "messages": len(to_send),
        "blobs": expect_blobs,
        # Fresh node, no friends: known_identities() is just the own
        # identity. Computed (not hardcoded 1) so this stays correct if
        # the seeding below ever grows friends -- which the "two_node"
        # scenario (Task 4, B.2c) now does; _compute_expect itself is
        # unaffected either way since `entitled` above is always pinned
        # to {node.identity_pub} regardless of who else node knows.
        "identities": len(node.store.known_identities()),
    }


def _find_composed_post(node, phone_device_pub: str):
    """Task 9 (outbound loopback gate): the KIND_POST authored under the
    phone's own device_pub -- i.e. the message KotlinSync's connection-2
    MESSAGES-phase push just ingested, not any of the node's/friend's own
    seeded content (which is authored under THEIR device_pubs). Returns
    None until it has landed (checked after every connection -- see
    _run_outbound_compose's wrapped _on_conn)."""
    for m in node.store.messages_by_author(node.identity_pub):
        if m.payload.get("kind") == "post" and m.cert.device_pub == phone_device_pub:
            return m
    return None


def _emit_composed_if_ready(node, friend, phone_device_pub: str) -> bool:
    """Task 9: once the phone's pushed KIND_POST has landed, decrypt it
    with the NODE'S OWN device enc key -- via the real hearth.dmcrypt
    primitives directly (unwrap_key -> decrypt_body -> decrypt_blob), not
    node._content_key's convenience wrapper -- proving the node really is
    an own device of this identity, able to read the phone's freshly
    composed content exactly like a real desktop would. Prints
    {"event": "composed", "text":..., "blob_len":..., "blob_ok":...,
    "wrapped_friend":...} once decryption succeeds; returns True so the
    caller stops checking (idempotent -- the event must fire exactly
    once). `blob_ok` is True iff decrypt_blob's AEAD auth actually
    succeeded, which -- given ChaCha20-Poly1305 -- is itself the proof
    the recovered bytes are byte-for-byte what the phone encrypted (any
    corruption/mismatch fails the tag rather than returning wrong bytes),
    so `blob_ok` alone already proves fidelity; `blob_len` rides along so
    the Kotlin side can additionally cross-check it against the original
    plaintext length it composed with. Returns False (no-op, safe to
    retry on the next connection) if the post has not landed yet, or
    if -- unexpectedly -- the node's own wrap does not decrypt (a real
    crypto-fidelity bug in the phone's compose path; this is never
    swallowed into a silent False forever, since the caller re-checks
    after every connection and a wire desync would surface as the test's
    own awaitEvent timing out)."""
    msg = _find_composed_post(node, phone_device_pub)
    if msg is None:
        return False
    p = msg.payload
    aad = dmcrypt.post_aad(msg.cert.identity_pub, p["scope"], p["created_at"])
    wraps = p.get("wraps") or {}
    key = dmcrypt.unwrap_key(wraps, node.device.device_pub,
                             node.device.enc_priv, aad)
    if key is None:
        return False
    body = dmcrypt.decrypt_body(key, p["body_nonce"], p["body_ct"], aad)
    if body is None:
        return False
    blob_hashes = [h for h in (body.get("blobs") or []) if isinstance(h, str)]
    blob_len = None
    blob_ok = False
    if blob_hashes:
        cipher = node.store.get_blob(blob_hashes[0])
        if cipher is not None:
            plain = dmcrypt.decrypt_blob(key, cipher)
            if plain is not None:
                blob_len = len(plain)
                blob_ok = True
    print(json.dumps({
        "event": "composed",
        "text": body.get("text"),
        "blob_len": blob_len,
        "blob_ok": blob_ok,
        "wrapped_friend": friend.device.device_pub in wraps,
    }), flush=True)
    return True


async def _run_outbound_compose(data_dir) -> None:
    """Task 9 (B.3 outbound slice): the KEY INTEGRATION PROOF for the
    phone's outbound compose crypto. Seeds a node that is (a) an OWN
    DEVICE of the phone's identity -- mint_fixture below enrolls the
    phone's device under the node's own identity, exactly like every
    other scenario, so the node can decrypt the phone's own-wrapped
    content like a real desktop -- and (b) already knows one FRIEND
    identity with a published enc key, so Compose.post's automatic
    friend-wrap (it wraps to every known identity's enc-keyed devices,
    own + friends) has someone real to wrap to.

    Unlike solo/two_node above, this scenario seeds NO desk journal posts
    (nothing to decrypt but the phone's own compose) and does not run
    node.maintain_own_device_grants()/maintain_wrap_grants() -- the
    node's and friend's enc keys are published UP FRONT (ensure_enckey,
    before the phone ever connects) and Compose.post inline-wraps to
    them directly; no wrap_grant backfill is exercised or needed here
    (that machinery is already proven by phoneDecryptsRealBackfilledContent/
    phoneReadsFriendContentEndToEnd in SyncLoopbackTest.kt -- this
    scenario's whole point is the OUTBOUND direction those don't cover).

    The printed fixture carries an extra `friend_identity_pub` field
    (beyond the usual device_priv/device_pub/cert/onion_addr) so the
    Kotlin test can pre-seed its own store's known-identities with the
    friend BEFORE connection 1's HAVE swap -- hearth/sync.py's
    `entitled` (messages_not_in) is restricted to identities the PEER
    already reports knowing in THAT SAME round's own HAVE frame, so a
    store that only reports knowing itself would not yet be entitled to
    the friend's enckey message on the very first connection. Pre-seeding
    (mirroring how the phone's own identity is always pre-seeded) is what
    makes connection 1 a genuine single-round pull of both the node's own
    enckey and the friend's enckey -- see SyncComposeLoopbackTest.kt's
    class doc for the full protocol trace."""
    node = HearthNode.create(Path(data_dir) / "n", "Desk", "desk")
    node.ensure_enckey()          # node's own device enckey -- so the
                                   # phone's store.enckeys(own) resolves
                                   # this device as a wrap recipient

    friend = HearthNode.create(Path(data_dir) / "f", "Freja", "freja-desk")
    node.store.add_identity(friend.identity_pub)
    friend.store.add_identity(node.identity_pub)
    friend.ensure_enckey()
    _gossip_until_converged(node, friend)   # delivers node's <-> friend's
                                             # enckey/profile messages so
                                             # node.store holds friend's
                                             # enckey message to relay to
                                             # the phone in connection 1

    sync = SyncService(node)

    composed_done = False
    orig_on_conn = sync._on_conn

    async def _on_conn_then_check(reader, writer):
        nonlocal composed_done
        await orig_on_conn(reader, writer)
        if not composed_done:
            composed_done = _emit_composed_if_ready(
                node, friend, fx["device_pub"])

    sync._on_conn = _on_conn_then_check

    port = await sync.start("127.0.0.1", 0)
    try:
        fx = mint_fixture(node)
        fx["onion_addr"] = f"127.0.0.1:{port}"
        fx["friend_identity_pub"] = friend.identity_pub
        expect = _compute_expect(node)
        print(json.dumps({"port": port, "fixture": fx, "expect": expect}),
              flush=True)
        await asyncio.Event().wait()   # serve until killed
    finally:
        await sync.stop()


def _find_new_phone_responses(node, phone_device_pub: str, seen_ids: set):
    """Task 8 (outbound-responses loopback gate): every KIND_RESPONSE
    authored by the phone's device (node identity == phone identity,
    own-identity model -- same shape as Task 9's _find_composed_post)
    that has not already had a "responded" event emitted for it, oldest
    created_at first -- so a reaction always gets processed (and its
    created_at captured for the retract check below) strictly before any
    later retract naming it, regardless of how many land in one
    connection's MESSAGES phase."""
    out = [m for m in node.store.messages_by_author(node.identity_pub)
           if m.payload.get("kind") == "response"
           and m.cert.device_pub == phone_device_pub
           and m.msg_id not in seen_ids]
    out.sort(key=lambda m: m.payload.get("created_at", 0))
    return out


def _decrypt_response_event(node, friend, msg):
    """Task 8: decrypts ONE phone-composed KIND_RESPONSE exactly the way
    _emit_composed_if_ready decrypts a KIND_POST -- via the real
    hearth.dmcrypt primitives directly (response_aad -> unwrap_key ->
    decrypt_body), using the NODE's OWN device enc key. The node is a
    genuine second device of the same identity here (mint_fixture
    enrolled the phone's device under the node's own identity), reachable
    as a wrap recipient only because the scenario's node.ensure_enckey()
    was pulled by the phone's connection-1 HAVE/MESSAGES round BEFORE
    compose -- the same precondition _run_outbound_compose's own class
    doc calls out. A successful unwrap here is therefore proof the node
    really is an author device of this response, able to read it exactly
    like a real second device/desktop would -- not a mock.

    Then independently reverifies responder_sig against
    HearthNode._response_sig_payload/_sig_ok (node.py:1389-1397,
    identity.py:79-84) -- the exact canonical form every real viewer
    (_post_responses_view) re-derives to check a folded entry -- rather
    than trusting the encrypted body's own claim that it is valid.

    Finally simulates a FRIEND opening the mutual_box:
    dmcrypt.try_open_slots(mutual_box, friend.device.enc_priv) -- a real,
    independently-generated X25519 key belonging to the friend node this
    scenario seeded, never a key the phone used to seal the box -- and
    confirms the box opens to exactly {identity, device_pub, sig} naming
    the responding phone device and matching responder_sig. This is the
    real engagement-privacy mechanism (seal_slots/try_open_slots)
    round-tripping the phone's composed disclosure byte-for-byte.

    Returns None on any decrypt/verify failure (unwrap_key/decrypt_body
    already fail closed and return None; nothing here swallows an
    unexpected exception -- a genuinely malformed wire shape is a real
    fidelity bug and should crash this process loudly rather than emit a
    fabricated success line, exactly mirroring _emit_composed_if_ready's
    style)."""
    p = msg.payload
    aad = dmcrypt.response_aad(msg.cert.identity_pub, p["target"], p["created_at"])
    wraps = p.get("wraps") or {}
    key = dmcrypt.unwrap_key(wraps, node.device.device_pub,
                             node.device.enc_priv, aad)
    if key is None:
        return None
    body = dmcrypt.decrypt_body(key, p["body_nonce"], p["body_ct"], aad)
    if body is None:
        return None
    rkind = body.get("rkind")
    rbody = body.get("body")
    created_at = body.get("created_at")
    responder = body.get("responder")
    responder_sig = body.get("responder_sig")
    # Integrity check (mirrors hearth's own _response_event, node.py:
    # 2525-2533 -- itself a reviewer-caught Critical there): body
    # ["responder"] is attacker-controlled plaintext (it lives inside the
    # encrypted body, not the signed envelope), while msg.cert.identity_pub
    # is the one fact ingest_message's Verifier actually proved. A
    # response whose body lies about who sent it must never be attributed
    # to the identity it claims -- fail closed the same return-None way
    # hearth's real fold does, before the claimed responder ever reaches
    # sig_payload/_sig_ok below.
    if responder != msg.cert.identity_pub:
        return None
    sig_payload = HearthNode._response_sig_payload(
        p["target"], rkind, rbody, created_at, responder)
    # responder_sig is a DEVICE-key signature (self.device.sign_raw in
    # compose_response), not an identity-key one -- verify against
    # msg.cert.device_pub (the signed envelope's own device_pub, which
    # for a KIND_RESPONSE this node ingested IS the responder's signing
    # device), exactly as node.py's _post_responses_view docstring
    # spells out ("Verify responder_sig against device_pub, then
    # _device_bound") and as ComposeResponseTest.kt's own independent
    # check does (KotlinWire.verifyRaw(fx.device_pub, responderSig,
    # sigPayload)) -- NOT the responder identity_pub, which is a
    # different keypair entirely.
    sig_ok = _sig_ok(msg.cert.device_pub, responder_sig, sig_payload)

    opened = dmcrypt.try_open_slots(body.get("mutual_box"),
                                    friend.device.enc_priv)
    friend_opened = False
    if opened is not None:
        disclosed = json.loads(opened)
        friend_opened = (
            disclosed.get("identity") == msg.cert.identity_pub
            and disclosed.get("device_pub") == msg.cert.device_pub
            and disclosed.get("sig") == responder_sig)
    return {"rkind": rkind, "body": rbody, "created_at": created_at,
            "sig_ok": sig_ok, "friend_opened": friend_opened}


def _check_retract_applied(node, target: str, reaction_created_at: float) -> bool:
    """Controller addition (carried from Task 5 review): runs hearth's
    REAL author sweep (node.process_responses -> _rebuild_responses_record,
    node.py:2579-2750) -- not a reimplementation of its fold logic -- then
    decrypts the resulting KIND_RESPONSES record with the node's own
    device key (dmcrypt directly, same rigor as _decrypt_response_event)
    to see what the fold actually produced. True iff the reaction entry
    (matched by created_at, the exact key node.py:2648-2654's `retracted`
    set uses) is no longer present -- i.e. the retract's pyStr-formatted
    body genuinely string-matched str(the reaction's created_at) inside
    the REAL fold, not merely that the retract message itself decrypted
    and verified okay (that is _decrypt_response_event's job, exercised
    separately for every response including this one)."""
    node.process_responses()
    rec = node.store.responses_record(target, node.identity_pub)
    if rec is None:
        return False
    p = rec.payload
    aad = dmcrypt.responses_aad(node.identity_pub, p["target"], p["created_at"])
    key = dmcrypt.unwrap_key(p.get("wraps") or {}, node.device.device_pub,
                             node.device.enc_priv, aad)
    if key is None:
        return False
    body = dmcrypt.decrypt_body(key, p["body_nonce"], p["body_ct"], aad)
    if body is None:
        return False
    entries = body.get("entries") or []
    return not any(e.get("rkind") == "reaction"
                  and e.get("created_at") == reaction_created_at
                  for e in entries)


async def _run_responses(data_dir) -> None:
    """Task 8 (outbound-responses slice): the loopback FIDELITY GATE for
    the whole slice -- a real hearth node decrypts a phone-composed
    reaction + comment with its OWN device key (proving it really is a
    second device of the same identity), independently reverifies
    responder_sig, and a real FRIEND identity's enc key genuinely opens
    the mutual_box (proving the engagement-privacy mechanism round-trips
    byte-for-byte), THEN a phone-composed retract is proven against
    hearth's REAL author fold (node.process_responses ->
    _rebuild_responses_record) -- not just accepted onto the wire, but
    shown to have actually withdrawn the reaction it named. That last
    part is a controller addition (carried from Task 5's review): retract
    otherwise has zero behavioral coverage, and its body carries the
    str(created_at) contract (node.py:2648-2653) that only a real fold
    run can prove.

    Seed mirrors _run_outbound_compose's own-identity + friend pattern
    exactly (see that function's docstring for the full protocol-trace
    rationale for why connection 1 must be a priming pull): the node is
    an OWN DEVICE of the phone's identity (mint_fixture enrolls the
    phone's device under the node's own identity) with its own enckey
    published up front (ensure_enckey, so ComposeResponse.compose's
    authorDevs/self-wrap branches resolve THIS device -- not just the
    phone's own -- as a wrap recipient once pulled), plus a real FRIEND
    identity with its own published enckey (so the mutual box has a
    genuine non-dummy slot to open). Unlike outbound_compose, this
    scenario ALSO seeds an own JOURNAL POST up front -- compose_response
    responds to an EXISTING post, so target_msg_id rides on the fixture
    line exactly like friend_identity_pub does, letting the Kotlin test
    skip rediscovering it via the synced store."""
    node = HearthNode.create(Path(data_dir) / "n", "Desk", "desk")
    node.ensure_enckey()          # node's own device enckey -- so the
                                   # phone's store.enckeys(own) resolves
                                   # this device as a wrap recipient

    friend = HearthNode.create(Path(data_dir) / "f", "Freja", "freja-desk")
    node.store.add_identity(friend.identity_pub)
    friend.store.add_identity(node.identity_pub)
    friend.ensure_enckey()
    _gossip_until_converged(node, friend)   # delivers node's <-> friend's
                                             # enckey/profile messages

    target_id = node.compose_post("respond to this", scope="kreds",
                                  placement="journal")

    sync = SyncService(node)

    emitted_ids: set = set()
    retract_reaction_created_at = None
    retract_checked = False
    orig_on_conn = sync._on_conn

    async def _on_conn_then_check(reader, writer):
        nonlocal retract_reaction_created_at, retract_checked
        await orig_on_conn(reader, writer)
        for msg in _find_new_phone_responses(node, fx["device_pub"], emitted_ids):
            ev = _decrypt_response_event(node, friend, msg)
            emitted_ids.add(msg.msg_id)
            if ev is None:
                # A real fidelity bug -- never fabricate a "responded"
                # line for it. The Kotlin side's awaitEvent("responded")
                # will time out instead of seeing a false success.
                continue
            print(json.dumps({
                "event": "responded", "rkind": ev["rkind"],
                "body": ev["body"], "sig_ok": ev["sig_ok"],
                "friend_opened": ev["friend_opened"],
            }), flush=True)
            if ev["rkind"] == "reaction":
                retract_reaction_created_at = ev["created_at"]
            if ev["rkind"] == "retract" and not retract_checked:
                retract_checked = True
                applied = _check_retract_applied(
                    node, target_id, retract_reaction_created_at)
                print(json.dumps({"event": "retracted", "applied": applied}),
                     flush=True)

    sync._on_conn = _on_conn_then_check

    port = await sync.start("127.0.0.1", 0)
    try:
        fx = mint_fixture(node)
        fx["onion_addr"] = f"127.0.0.1:{port}"
        fx["friend_identity_pub"] = friend.identity_pub
        fx["target_msg_id"] = target_id
        expect = _compute_expect(node)
        print(json.dumps({"port": port, "fixture": fx, "expect": expect}),
              flush=True)
        await asyncio.Event().wait()   # serve until killed
    finally:
        await sync.stop()


def _find_new_phone_dms(node, friend_identity_pub: str, phone_device_pub: str,
                        seen_ids: set):
    """Task 4 (outbound-dm loopback gate): every KIND_DM authored by the
    phone's device (node identity == phone identity, own-identity model --
    same shape as _find_new_phone_responses/_find_composed_post) addressed
    to the seeded FRIEND identity that has not already had a "dm" event
    emitted for it, oldest created_at first. compose_dm rejects a self-DM
    (node.py:2320-2321: `to_identity == self.identity_pub` raises), so
    every DM this scenario's phone composes necessarily targets the friend
    -- the `payload["to"] == friend_identity_pub` filter is therefore not
    narrowing away some other traffic, it is just being explicit about
    what is already structurally guaranteed, the same way
    _run_phase3/_seed_friend_and_befriend's comments are explicit about
    guarantees that follow from hearth's own validation. Sorting by
    created_at (not insertion/discovery order) is what makes the four
    DMs the Kotlin test composes with strictly increasing createdAt values
    always surface in the same order they were written, regardless of how
    many land in one connection's MESSAGES phase."""
    out = [m for m in node.store.messages_by_author(node.identity_pub)
           if m.payload.get("kind") == "dm"
           and m.cert.device_pub == phone_device_pub
           and m.payload.get("to") == friend_identity_pub
           and m.msg_id not in seen_ids]
    out.sort(key=lambda m: m.payload.get("created_at", 0))
    return out


def _decrypt_dm_event(node, friend, msg):
    """Task 4 (outbound-dm loopback gate): decrypts ONE phone-composed
    KIND_DM as the REAL RECIPIENT -- the friend node's own device enc key,
    via dmcrypt.unwrap_key/decrypt_body directly (never node._content_key's
    convenience wrapper), exactly mirroring _decrypt_response_event's own
    rigor for KIND_RESPONSE and _emit_composed_if_ready's for KIND_POST.
    compose_dm always rejects a self-DM, so every DM in this scenario is
    addressed to the friend identity -- a successful unwrap against
    friend.device.enc_priv is therefore proof the friend really is the
    named recipient, able to read the phone's freshly composed DM exactly
    like a real second person's device would. (The SAME wraps dict also
    carries an own-device copy -- compose_dm's `_dm_device_pubs` always
    merges 'mine' alongside 'theirs', node.py:2308-2315 -- but proving
    THAT wrap path is already _emit_composed_if_ready's job for KIND_POST;
    this function deliberately decrypts via the recipient's key only, the
    thing nothing else in this harness covers yet.)

    AAD is RECOMPUTED from the envelope's own cert/payload fields --
    dm_aad(msg.cert.identity_pub, payload["to"], payload["created_at"]) --
    never phone-supplied out-of-band, exactly matching hearth's own
    _content_key for KIND_DM (node.py:2816-2817).

    body["blobs"] is cross-checked against payload["blobs"] BEFORE either
    is trusted -- mirrors _decrypt_response_event's responder-identity
    cross-check (itself mirroring hearth's real _response_event guard,
    node.py:2525-2533): the encrypted body's own claim about what it
    references is attacker-controlled plaintext once decrypted, so a body
    that lies about its own blob refs must fail this closed rather than
    have blob_ok computed against a value the body merely asserts.

    story_ref rides the envelope IN THE CLEAR (messages.py:139-150, never
    inside the encrypted body) -- re-validated here via hearth's REAL
    _valid_story_ref (messages.py:241-255), not a hand-rolled shape check,
    exactly as compose_dm itself validates it before ever publishing
    (node.py:2329-2330). Absent (None) is vacuously fine -- only a
    present-but-malformed story_ref should ever fail this.

    Returns None on any decrypt/verify failure (unwrap_key/decrypt_body
    already fail closed and return None; a body/envelope blob mismatch is
    treated identically) -- nothing here swallows an unexpected exception,
    so a genuinely malformed wire shape crashes this process loudly rather
    than emitting a fabricated "dm" line; the caller never emits an event
    for a None result, so the Kotlin side's awaitEvent("dm") times out
    instead of seeing a false success."""
    p = msg.payload
    aad = dmcrypt.dm_aad(msg.cert.identity_pub, p["to"], p["created_at"])
    wraps = p.get("wraps") or {}
    key = dmcrypt.unwrap_key(wraps, friend.device.device_pub,
                             friend.device.enc_priv, aad)
    if key is None:
        return None
    body = dmcrypt.decrypt_body(key, p["body_nonce"], p["body_ct"], aad)
    if body is None:
        return None
    payload_blobs = p.get("blobs") or []
    body_blobs = body.get("blobs") or []
    if body_blobs != payload_blobs:
        return None
    text = body.get("text")
    text_ok = isinstance(text, str)
    blob_ok = True
    for h in payload_blobs:
        cipher = node.store.get_blob(h)
        if cipher is None:
            blob_ok = False
            break
        if dmcrypt.decrypt_blob(key, cipher) is None:
            blob_ok = False
            break
    sref = p.get("story_ref")
    story_ref_ok = _valid_story_ref(sref) if sref is not None else True
    return {
        "msg_id": msg.msg_id,
        "text": text,
        "text_ok": text_ok,
        "blob_ok": blob_ok,
        "story_ref_ok": story_ref_ok,
        "expires_at": p.get("expires_at"),
    }


async def _run_dm(data_dir) -> None:
    """Task 4 (outbound-dm-send slice): the loopback FIDELITY GATE for the
    whole slice -- a real hearth node ingests phone-composed DMs (text,
    photo, story-reply, expiring) over the real wire protocol, and a real
    FRIEND identity's device key -- never the phone's own -- genuinely
    decrypts each one as the addressed recipient, cross-checks the
    envelope's plaintext blob refs against the encrypted body's own claim,
    re-validates story_ref via hearth's real shape guard, and -- for the
    expiring DM -- runs hearth's REAL store.sweep_expired (store.py:432-
    447) past its expiry instant and confirms the swept list actually
    names it. Structurally the same own-identity + friend seed as
    _run_outbound_compose/_run_responses (see either's own docstring for
    the full protocol-trace rationale on why connection 1 must be a
    priming pull): the node is an OWN DEVICE of the phone's identity
    (mint_fixture enrolls the phone's device under the node's own
    identity) with its own enckey published up front, plus a real FRIEND
    identity with its own published enckey -- here the friend is not just
    a mutual-box opener (as in _run_responses) but the DM's actual named
    recipient, since compose_dm rejects addressing yourself.

    The node's wrapped _on_conn (see main()'s own for the untouched
    "solo"/"two_node" path) decrypts every newly-landed phone-authored DM
    after each connection fully completes, emitting one {"event":"dm",...}
    line per DM in created_at order (_find_new_phone_dms). Immediately
    after a DM whose envelope carries a non-null expires_at, the sweep
    runs and a {"event":"dm_expired","swept":...} line follows -- `now`
    is expires_at + 1 (equivalent to the brief's created_at +
    expires_seconds + 1, since expires_at IS created_at + expires_seconds
    on the wire; the script never sees expires_seconds as a separate
    field, only the envelope's own expires_at)."""
    node = HearthNode.create(Path(data_dir) / "n", "Desk", "desk")
    node.ensure_enckey()          # node's own device enckey -- so the
                                   # phone's store.enckeys(own) resolves
                                   # this device as a wrap recipient

    friend = HearthNode.create(Path(data_dir) / "f", "Freja", "freja-desk")
    node.store.add_identity(friend.identity_pub)
    friend.store.add_identity(node.identity_pub)
    friend.ensure_enckey()
    _gossip_until_converged(node, friend)   # delivers node's <-> friend's
                                             # enckey/profile messages

    sync = SyncService(node)

    emitted_ids: set = set()
    expiry_checked_ids: set = set()
    orig_on_conn = sync._on_conn

    async def _on_conn_then_check(reader, writer):
        await orig_on_conn(reader, writer)
        for msg in _find_new_phone_dms(
                node, friend.identity_pub, fx["device_pub"], emitted_ids):
            ev = _decrypt_dm_event(node, friend, msg)
            emitted_ids.add(msg.msg_id)
            if ev is None:
                # A real fidelity bug -- never fabricate a "dm" line for
                # it. The Kotlin side's awaitEvent("dm") will time out
                # instead of seeing a false success.
                continue
            print(json.dumps({"event": "dm", **ev}), flush=True)
            expires_at = ev["expires_at"]
            if expires_at is not None and msg.msg_id not in expiry_checked_ids:
                expiry_checked_ids.add(msg.msg_id)
                swept = node.store.sweep_expired(now=expires_at + 1)
                print(json.dumps({
                    "event": "dm_expired",
                    "swept": msg.msg_id in swept,
                }), flush=True)

    sync._on_conn = _on_conn_then_check

    port = await sync.start("127.0.0.1", 0)
    try:
        fx = mint_fixture(node)
        fx["onion_addr"] = f"127.0.0.1:{port}"
        fx["friend_identity_pub"] = friend.identity_pub
        expect = _compute_expect(node)
        print(json.dumps({"port": port, "fixture": fx, "expect": expect}),
              flush=True)
        await asyncio.Event().wait()   # serve until killed
    finally:
        await sync.stop()


def _deliver_referenced_blobs(dst, src, msg_id: str) -> None:
    """Task 4 (recipient-post-regrants loopback gate): copies every blob
    hash `msg_id`'s payload references (blobs/poster/thumbs -- the same
    reference extraction _compute_expect uses to mirror hearth/sync.py's
    own BLOBS-phase reference scan) from src's store into dst's, for any
    hash src holds that dst does not yet. Blobs are content-addressed
    (store.put_blob hashes its OWN input via blob_hash and is an
    idempotent INSERT OR IGNORE), so re-delivering an already-held blob is
    always a safe no-op and the resulting hash in dst is guaranteed
    identical to src's. This is the direct-in-process substitute for the
    real BLOBS phase's want/have exchange between the two SCRIPT-SEEDED
    nodes (A and B) -- exactly as _deliver/_gossip_until_converged already
    substitute direct message delivery for the real MESSAGES phase between
    them (see that function's own module-level rationale). The REAL wire's
    BLOBS phase is still exercised for real between B and the phone
    (Kotlin) -- this helper only gets the bytes from A into B in the first
    place, the same way _gossip_until_converged gets the post's message
    row from A into B."""
    msg = dst.store.get_message(msg_id)
    p = msg.payload
    refs = {b for b in (p.get("blobs") or []) if isinstance(b, str)}
    poster = p.get("poster")
    if isinstance(poster, str) and poster:
        refs.add(poster)
    refs |= {t for t in (p.get("thumbs") or []) if isinstance(t, str) and t}
    for h in refs:
        if src.store.has_blob(h) and not dst.store.has_blob(h):
            dst.store.put_blob(src.store.get_blob(h))


async def _run_regrants(data_dir, no_sweep: bool = False) -> None:
    """Task 4 (recipient-post-regrants): the loopback FIDELITY GATE for the
    whole slice -- reproduces the field case exactly (spec: docs/
    superpowers/specs/2026-07-23-recipient-post-regrants-design.md's Goal
    paragraph: 'a friend's ... old journal posts are visible on August's
    desktop but undecryptable on his phone until the FRIEND comes back
    online') against a REAL second hearth node, then proves hearth's REAL
    maintain_received_post_grants sweep (node.py:2261+) -- not some other
    path -- is what unlocks it on the phone.

    Seed: B ("Desk") is the phone's OWN identity, exactly like every other
    scenario (mint_fixture enrolls the phone's device under B's own
    identity). A ("Freja") is a SECOND real HearthNode, the friend/author.
    B and A befriend and exchange enc keys FIRST (mirrors tests/
    test_received_post_grants.py's _befriend_with_enckeys) -- so B's own
    PRIMARY device already has a published enc key at the moment A
    composes. A then composes ONE kreds journal post WITH A PHOTO
    (_tiny_png(), reused from main()'s own seed); compose_post's automatic
    friend-wrap (_scope_device_pubs("kreds"), node.py:659-676) wraps the
    content key to every enc-keyed device A currently knows of -- at this
    instant that is ONLY B's primary device (the phone's device does not
    exist anywhere yet, not even as a cert) -- exactly 'wrapped only to
    keys B held THEN'. Gossiping (message via _gossip_until_converged,
    then the referenced photo blob via _deliver_referenced_blobs) brings
    the post into B's store, where B can already decrypt it via that
    inline wrap. This is asserted below as a PRECONDITION (a harness-side
    sanity check on how the scenario is built, not the thing Kotlin's test
    proves -- that is the recipient-grant path, gated behind
    "regrants_ready" below) -- B being unable to decrypt at all would be a
    scenario-construction bug.

    A is then never touched again: 'the field case' is the friend/author
    being OFFLINE for the rest of this scenario, matching production where
    the laptop account may be offline for days.

    The phone's own enc key is published AFTER, over the REAL wire, via
    the SAME path every sibling scenario uses (KotlinSync.composeEncKey +
    hearth/sync.py's already-generic MESSAGES-phase ingestion) -- never
    seeded here directly, and never anything A could have wrapped to.

    node.maintain_received_post_grants() is NEVER invoked automatically by
    this harness: SyncService.start() (used here, as everywhere else in
    this file) only starts the TCP accept loop -- the periodic sweep
    (hearth/sync.py's _gossip_round, where the sweep call actually lives,
    sync.py:246-252) is driven exclusively by SyncService.gossip_loop,
    which this script never starts or awaits (verified by reading
    sync.py: _gossip_round has exactly one caller, gossip_loop's own
    while-loop, sync.py:289+; _on_conn/_session, the only thing
    SyncService.start's accept loop ever invokes per connection, never
    calls it either). So the `no_sweep` sub-scenario needs no monkeypatch
    at all -- the cleanest possible suppression mechanism is simply never
    calling the method, which is exactly what an unmodified production
    accept path already does without any sweep call of its own. The
    `no_sweep` flag below governs only the ONE explicit call this script
    itself makes, mirroring _run_phase3's own explicit (not tick-driven)
    sweep calls for the sibling DM scenario.

    Wrapped _on_conn (mirrors every other scenario's pattern): after EVERY
    connection fully completes, runs the real sweep (unless no_sweep) and
    then POLLS the store's actual resulting state (store.wrap_grants,
    never assumed just because the sweep was called) for a grant covering
    the phone's fresh device key -- only then, and only once, prints
    {"event":"regrants_ready"}. {"event":"conn_done"} always follows, in
    BOTH scenarios, as a deterministic per-connection completion marker
    the no_sweep negative control blocks on instead (it will never see
    "regrants_ready", by construction)."""
    node = HearthNode.create(Path(data_dir) / "n", "Desk", "desk")           # B
    friend = HearthNode.create(Path(data_dir) / "f", "Freja", "freja-desk")  # A

    node.store.add_identity(friend.identity_pub)
    friend.store.add_identity(node.identity_pub)
    node.ensure_enckey()            # B's own primary device enc key --
                                     # published BEFORE A composes, so A's
                                     # friend-wrap covers it inline
    friend.ensure_enckey()
    _gossip_until_converged(node, friend)   # cross-registers device views
                                             # + delivers both enc keys

    post_id = friend.compose_post("frejas gamle indlaeg", scope="kreds",
                                  photos=[_tiny_png()])
    _gossip_until_converged(node, friend)          # B receives the post
    _deliver_referenced_blobs(node, friend, post_id)  # + its photo blob

    # Precondition (harness sanity check, not the proof under test): B can
    # ALREADY decrypt A's post via the inline wrap -- the real field state
    # before any own-device-fanout concern exists.
    rmsg = node.store.get_message(post_id)
    key, _aad = node._content_key(rmsg)
    assert key is not None, \
        "scenario bug: B must be able to decrypt A's post via its inline wrap"

    # A goes OFFLINE for the rest of this scenario -- `friend` is never
    # touched again below.

    sync = SyncService(node)

    ready_emitted = False
    orig_on_conn = sync._on_conn

    async def _on_conn_then_check(reader, writer):
        nonlocal ready_emitted
        await orig_on_conn(reader, writer)
        if not no_sweep:
            node.maintain_received_post_grants()
        if not ready_emitted:
            grants = node.store.wrap_grants(post_id, node.identity_pub)
            if fx["device_pub"] in grants:
                ready_emitted = True
                print(json.dumps({"event": "regrants_ready"}), flush=True)
        print(json.dumps({"event": "conn_done"}), flush=True)

    sync._on_conn = _on_conn_then_check

    port = await sync.start("127.0.0.1", 0)
    try:
        fx = mint_fixture(node)
        fx["onion_addr"] = f"127.0.0.1:{port}"
        fx["friend_identity_pub"] = friend.identity_pub
        fx["target_msg_id"] = post_id
        expect = _compute_expect(node)
        print(json.dumps({"port": port, "fixture": fx, "expect": expect}),
              flush=True)
        await asyncio.Event().wait()   # serve until killed
    finally:
        await sync.stop()


def _deliver(src, dst) -> bool:
    """One-directional content transfer: every message `src` has authored
    that `dst` does not yet hold, via the real store.messages_not_in +
    store.ingest_message pair -- the same two calls a real sync session
    drives, just without the socket in between. Mirrors
    tests/test_received_dm_grants.py's _deliver helper. Returns whether
    anything new was actually accepted (dedup-aware: re-delivering
    already-held messages is a safe no-op, not "changed")."""
    changed = False
    for m in src.store.messages_not_in({}, {src.identity_pub}, dst.identity_pub):
        res = dst.store.ingest_message(m)
        changed = changed or res.accepted
    return changed


def _gossip_until_converged(a, b, max_rounds=4) -> int:
    """Bounded-rounds direct-in-process gossip standing in for real
    network sync between the desk and friend nodes (see module docstring
    for why direct delivery was chosen over a second real transport).
    Delivers both directions every round and asserts convergence (a round
    that moves nothing) within max_rounds, rather than assuming any fixed
    round count or sleeping -- a regression that stopped content from
    ever settling would fail loudly here, not as a silent short read
    downstream. Returns the round index convergence was detected at."""
    for i in range(max_rounds):
        moved_ab = _deliver(a, b)
        moved_ba = _deliver(b, a)
        if not (moved_ab or moved_ba):
            return i
    raise RuntimeError(
        "direct in-process gossip between the desk and friend nodes did "
        f"not converge within {max_rounds} rounds")


def _seed_friend_and_befriend(node, friend_dir) -> "HearthNode":
    """Task 4 (B.2c) scenario steps 1-2: a second real HearthNode
    ("Freja"), befriended with `node` both ways via the same lightweight
    store.add_identity + ensure_enckey + direct-delivery pattern already
    proven by tests/test_own_device_grants.py's and
    tests/test_received_dm_grants.py's _befriend_with_enckeys helper --
    not the full invite/pairing ceremony (hearth/demo.py's heavier prior
    art for befriending two independent people): this gate exercises
    content sync, not pairing, so the already-proven lighter pattern is
    enough. Delivering each side's ensure_enckey message is what TOFU-
    registers a DeviceView for the OTHER identity's device
    (identity.py's Verifier.verify_message) -- load-bearing for
    messages_not_in's KIND_POST/KIND_WRAP_GRANT peer-device gating, not
    merely cosmetic; skip it and cross-identity content silently stops
    relaying.

    Composes friend's wall posts + the OLD DM here, before this
    function's own final gossip round -- i.e. all before the phone ever
    connects (scenario steps 1-2), and before the phone's device or enc
    key exist anywhere: HearthNode.create's own set_profile("Freja") call
    is what publishes the friend's profile (delivered to `node` by the
    same final gossip round), satisfying the "friend has a real,
    delivered profile" requirement the author-name-resolution assertion
    needs."""
    friend = HearthNode.create(friend_dir, "Freja", "freja-desk")
    node.store.add_identity(friend.identity_pub)
    friend.store.add_identity(node.identity_pub)
    node.ensure_enckey()
    friend.ensure_enckey()
    _gossip_until_converged(node, friend)   # cross-registers device views

    friend.compose_post("friend wall post one", scope="kreds",
                        placement="profile")
    friend.compose_post("friend wall post two", scope="kreds",
                        placement="profile")
    friend.compose_dm(node.identity_pub, "old dm from friend")
    _gossip_until_converged(node, friend)
    return friend


def _run_phase3(node, friend) -> None:
    """Task 4 (B.2c) scenario step 3's second half -- reactive to the
    phone's real enckey push, which has ALREADY landed in node.store by
    the time this runs (the just-finished connection's real MESSAGES
    phase ingested it generically; no script-side injection). Runs
    exactly once per node process -- see main()'s guard -- the first
    time any connection completes.

    Order mirrors hearth/sync.py's _gossip_round for this keyspace
    (maintain_wrap_grants; maintain_own_device_grants;
    maintain_received_dm_grants), split across the two nodes: friend's
    STOCK, untouched maintain_wrap_grants mints the wall grants naming
    the phone (it now knows the phone's enc key, delivered below);
    node's maintain_received_dm_grants (already called once,
    unconditionally, in main()'s wrapper before this runs -- see
    maintain_own_device_grants there) backfills the OLD dm to the phone
    via a RECIPIENT-signed grant. RED-CONTROL: comment out ONLY the
    maintain_received_dm_grants call below to reproduce the required
    RED state (task-4-brief.md) -- the old-DM assertion must then fail
    while the wall + new-DM assertions still pass."""
    _deliver(node, friend)               # phone's enckey -> friend
    friend.maintain_wrap_grants()        # STOCK, untouched: mints wall
                                         # grants naming the phone
    node.maintain_received_dm_grants()   # backfills the OLD dm to the
                                         # phone via a recipient-signed grant
    friend.compose_dm(node.identity_pub, "new dm from friend")  # phone's
                                         # enc key is known now -> inline
    _deliver(friend, node)               # bring it all back to node


async def main(data_dir, scenario="solo"):
    node = HearthNode.create(Path(data_dir) / "n", "Desk", "desk")
    # Seed own-identity content: two text posts + one post with a photo
    # (-> a photo blob + its thumbnail blob, both referenced from the
    # post's payload).
    node.compose_post("hello from desk")
    node.compose_post("second post, still text")
    node.compose_post("with pic", photos=[_tiny_png()])

    # Task 4 (B.2c): opt-in only -- "solo" (the default, and every call
    # site that predates this task) never constructs a friend node at
    # all, so authProbeConnectionIsAccepted / syncsRealOwnIdentityContent
    # / phoneDecryptsRealBackfilledContent see byte-identical behavior to
    # before this task.
    friend = None
    if scenario == "two_node":
        friend = _seed_friend_and_befriend(node, Path(data_dir) / "f")

    sync = SyncService(node)

    # Task 6 (B.2): the desk decrypt gate's two-sync flow needs
    # node.maintain_own_device_grants() (Task 2) to run AFTER the phone's
    # first sync pushes its device-signed enckey message (via KotlinSync's
    # `outbound`, ingested generically by hearth/sync.py's MESSAGES phase --
    # untouched, no script-side injection needed) and BEFORE the phone's
    # second sync pulls the resulting wrap_grants. In production this sweep
    # rides the periodic gossip loop (hearth/sync.py _gossip_round,
    # SyncService.gossip_loop) alongside maintain_enckey/maintain_wrap_grants
    # -- this desk gate never starts that loop, so it is called directly
    # here instead (spec: "for the loopback script call it directly").
    #
    # Wrapping _on_conn (rather than editing hearth/sync.py, which is
    # untouchable production code) means the real, unmodified _on_conn/
    # _session logic runs exactly as production does; this only appends the
    # sweep once a connection's session has FULLY finished (the connection's
    # writer already closed). The signal line printed afterward lets the
    # test BLOCK on the sweep actually having run, rather than racing the
    # timing of opening a second connection against this coroutine's
    # completion on the node's event loop -- deterministic handoff, not a
    # timing assumption.
    #
    # Task 4 (B.2c) extension: when a friend node exists (scenario
    # "two_node"), the FIRST connection to fully complete also runs
    # _run_phase3 (see its own docstring) -- gated by phase3_done so it
    # fires exactly once, not on every connection (the new test's own
    # second sync must not re-trigger friend.compose_dm a second time).
    orig_on_conn = sync._on_conn
    phase3_done = False

    async def _on_conn_then_maintain(reader, writer):
        nonlocal phase3_done
        await orig_on_conn(reader, writer)
        node.maintain_own_device_grants()
        if friend is not None and not phase3_done:
            phase3_done = True
            _run_phase3(node, friend)
        print(json.dumps({"event": "maintained"}), flush=True)

    sync._on_conn = _on_conn_then_maintain

    port = await sync.start("127.0.0.1", 0)
    try:
        fx = mint_fixture(node)
        fx["onion_addr"] = f"127.0.0.1:{port}"
        expect = _compute_expect(node)
        print(json.dumps({"port": port, "fixture": fx, "expect": expect}),
              flush=True)
        await asyncio.Event().wait()   # serve until killed
    finally:
        await sync.stop()


async def _drive_pair_accept(node, deny: bool) -> None:
    """Task 6 (android first-load pairing loopback gate, spec 2026-07-22-
    android-first-load-pairing-design): the auto-accept/deny driver for
    the "pairing"/"pairing_deny" scenarios (see _run_pairing's own
    docstring for the full contract). Runs as a background task for the
    life of the node process, so it can drive more than one ceremony if a
    test opens more than one pairing attempt against the same spawned
    node (SyncPairLoopbackTest.kt's wrong-code test does exactly this: a
    wrong-code attempt that never parks anything, followed by a REAL
    ceremony against the same running node) -- though most attempts each
    spawn their own node, this loop makes no such assumption, looping
    forever rather than firing once.

    Polling (asyncio.sleep(0.01)), not an Event -- mirrors tests/
    test_pair_wire.py's own _wait_for_pending helper exactly (the same
    "no pending -> pending" transition it polls for); there is no
    existing signal for "pending_pair just appeared" to await instead
    (pending_pair_event is the OTHER direction -- verdict-set, which
    _handle_pair_request awaits and this function is the one that sets).

    Drives the EXACT node-level operations hearth/api.py's real POST
    /api/pair/accept route executes (api.py:709-734) -- not a shortcut
    around accept_pairing: on accept, node.accept_pairing(pending[
    "request_json"]) (untouched production code) mints the real package
    and enrolls the device; either way, pending["verdict"] is set and
    node.pending_pair_event.set() wakes the held wire connection
    (sync.py's _handle_pair_request), exactly as the human's Accept/Deny
    click would in the real desktop UI.

    On accept, the enrollment is read back OFF THE STORE (node.store.
    load_views(node.identity_pub)), not merely trusted from `pending` --
    proving accept_pairing's store mutation actually happened -- before
    emitting {"event":"paired","device_pub":...} (task brief: "from
    node.store.load_views"). A device_pub that accept_pairing's own
    return value claims to have enrolled but that does not show up in
    views would be a real fidelity bug -- this raises loudly rather than
    fabricating a "paired" line, the same fail-closed posture every other
    scenario in this file uses (see e.g. _emit_composed_if_ready's own
    doc comment)."""
    while True:
        while node.pending_pair is None:
            await asyncio.sleep(0.01)
        pending = node.pending_pair
        if deny:
            pending["verdict"] = False
        else:
            pending["package"] = node.accept_pairing(pending["request_json"])
            pending["verdict"] = True
            views = node.store.load_views(node.identity_pub)
            device_pub = pending["device_pub"]
            if device_pub not in views:
                raise RuntimeError(
                    "accept_pairing did not enroll the paired device's "
                    f"view (device_pub={device_pub!r})")
            print(json.dumps({"event": "paired", "device_pub": device_pub}),
                 flush=True)
        node.pending_pair_event.set()
        while node.pending_pair is not None:
            await asyncio.sleep(0.01)


async def _run_pairing(data_dir, deny: bool = False) -> None:
    """Task 6 (android first-load pairing loopback gate, spec 2026-07-22-
    android-first-load-pairing-design): the CEREMONY gate -- proves the
    phone's Kotlin ceremony client (KotlinPairing.runCeremony, Tasks 3-4)
    against a REAL hearth node whose pre-auth `hearth-pair-request` wire
    handler (Task 2, sync.py's _handle_pair_request) is the SAME,
    unmodified, production listener every other scenario in this file
    serves (SyncService(node) below is byte-identical construction to
    main()'s own). This scenario is structurally different from every
    one above it in one load-bearing way: no mint_fixture at all -- the
    entire point of Task 6 is that the phone gets its identity FROM the
    ceremony, not from a pre-baked fixture handed to it out of band the
    way every other scenario's phone identity works.

    Seeds one own-identity journal post (so a post-pairing sync has real
    content to pull) and one FRIEND identity with a published enc key
    (HearthNode.create + ensure_enckey + _gossip_until_converged, the
    same helper every two-node scenario in this file already uses).
    accept_pairing's own `friends` list (node.py:2032-2033, `[i for i in
    self.store.known_identities() if i != self.identity_pub]`) then hands
    the friend's identity_pub to the phone AS PART OF THE PAIRING PACKAGE
    ITSELF, before any sync ever runs -- the Kotlin test's post-ceremony
    sync is what pulls the seeded POST specifically.

    A background driver task (_drive_pair_accept, see its own docstring)
    watches node.pending_pair and reacts the instant a real phone's
    hearth-pair-request frame parks a ceremony there. `deny=False` (the
    "pairing" scenario, default): always accepts. `deny=True` ("
    pairing_deny"): always denies, proving the negative path -- nothing
    enrolled, and (per SyncPairLoopbackTest.kt) a subsequent sync attempt
    with unenrolled device keys is refused by the node's own AUTH gate.

    The wrong-code ("Expired") case needs NO scenario-side driver support
    at all -- hearth's own verify_and_consume (pairingcodes.py) fails
    closed before node.pending_pair is ever touched, so the driver task
    simply never has anything to react to. What IS needed is a second,
    deliberately-wrong-code link sharing the SAME dialable address (the
    Kotlin side has no pair-link ENCODER, only decodeLink -- encoding is
    exclusively a desktop/hearth-side operation in this system, mirroring
    how only hearth's own /api/pair/begin ever calls invitecodec.
    encode_pair for real) -- `wrong_code_link` below, packed with a code
    that can never match the one actually minted.

    Prints one `{"event":"pair_ready", "link":..., "wrong_code_link":...,
    "identity_priv":..., "identity_pub":..., "friend_identity_pub":...}`
    line once the listener is live and the code is minted. `link` is
    exactly what hearth's own /api/pair/begin would return
    (invitecodec.encode_pair against the REAL listening 127.0.0.1:<port>
    address -- not a fake onion, so SyncPairLoopbackTest.kt's dial lambda
    can actually reach this process, per the task brief's own note).
    `identity_priv`/`identity_pub` are the node's OWN real identity keys
    -- the ultimate full-pairing assertion (task brief): pairing.json's
    persisted identity_priv must equal this exact value, proving the
    ceremony really did hand the phone the node's own identity key, not
    some other value. Printing the real identity_priv here (regardless of
    the eventual accept/deny outcome) is a test-harness-only shortcut --
    a real desktop never discloses it before a human's Accept click; see
    node.accept_pairing, which only ever returns it as part of an
    accepted package."""
    node = HearthNode.create(Path(data_dir) / "n", "Desk", "desk")
    node.compose_post("paired phone should see this post")

    friend = HearthNode.create(Path(data_dir) / "f", "Freja", "freja-desk")
    node.store.add_identity(friend.identity_pub)
    friend.store.add_identity(node.identity_pub)
    friend.ensure_enckey()
    _gossip_until_converged(node, friend)   # delivers friend's enckey/profile
                                             # so accept_pairing's own
                                             # known_identities() lists the
                                             # friend in the pairing package

    sync = SyncService(node)
    port = await sync.start("127.0.0.1", 0)
    # Real /api/pair/begin reads this same meta key for its own `addr`
    # (api.py:672) -- mirrored here so accept_pairing's `my_addr` field
    # (node.py:2035) is realistic too, even though KotlinPairing.
    # runCeremony pins the persisted onion_addr to the DIALED link
    # address, never to my_addr (see runCeremony's own doc).
    node.store.set_meta("gossip_addr", f"127.0.0.1:{port}")

    driver_task = asyncio.create_task(_drive_pair_accept(node, deny))
    try:
        code = node.pairing.mint(time.time())
        link = invitecodec.encode_pair(f"127.0.0.1:{port}", code)
        # A code that can never match the one just minted -- same address,
        # deliberately wrong code, produced via the REAL hearth codec
        # rather than hand-corrupting b58 bytes (which the Kotlin side has
        # no way to re-encode after decoding -- see docstring above).
        wrong_code_link = invitecodec.encode_pair(
            f"127.0.0.1:{port}", "WRONGCODEZZZZZ")
        print(json.dumps({
            "event": "pair_ready",
            "link": link,
            "wrong_code_link": wrong_code_link,
            "identity_priv": node.device.to_json()["identity_priv"],
            "identity_pub": node.identity_pub,
            "friend_identity_pub": friend.identity_pub,
        }), flush=True)
        await asyncio.Event().wait()   # serve until killed
    finally:
        driver_task.cancel()
        await sync.stop()


if __name__ == "__main__":
    _scenario = sys.argv[2] if len(sys.argv) > 2 else "solo"
    if _scenario == "outbound_compose":
        # Task 9: a structurally different seed (own enckey + a friend's
        # enckey up front, no desk journal posts) from solo/two_node above
        # -- kept as its own entry point rather than folded into main() as
        # a third branch, so solo/two_node's behavior stays provably
        # byte-identical to every pre-Task-9 call site (main() itself is
        # untouched).
        asyncio.run(_run_outbound_compose(sys.argv[1]))
    elif _scenario == "responses":
        # Task 8: a structurally different seed (own enckey + a friend's
        # enckey + an own journal POST up front, no other desk journal
        # content) from solo/two_node/outbound_compose above -- kept as
        # its own entry point for the same reason outbound_compose is:
        # every pre-Task-8 call site (main(), _run_outbound_compose)
        # stays provably byte-identical to before this task.
        asyncio.run(_run_responses(sys.argv[1]))
    elif _scenario == "dm":
        # Task 4 (outbound-dm-send slice): a structurally different seed
        # (own enckey + a friend's enckey up front, no desk journal
        # content) from solo/two_node above -- kept as its own entry
        # point for the same reason outbound_compose/responses are: every
        # pre-Task-4 call site (main(), _run_outbound_compose,
        # _run_responses) stays provably byte-identical to before this
        # task.
        asyncio.run(_run_dm(sys.argv[1]))
    elif _scenario in ("regrants", "regrants_no_sweep"):
        # Task 4 (recipient-post-regrants loopback gate): structurally
        # different from every scenario above -- TWO real nodes from the
        # very start (not an opt-in "two_node" addition layered onto
        # main()'s own-identity seed the way two_node is), the friend
        # author composing BEFORE the phone's enc key exists anywhere, and
        # the real maintain_received_post_grants sweep (or its deliberate
        # absence, for the "regrants_no_sweep" negative control) as the
        # one thing under test. Kept as its own entry point for the same
        # reason outbound_compose/responses/dm are: every earlier call
        # site stays byte-identical to before this task.
        asyncio.run(_run_regrants(
            sys.argv[1], no_sweep=(_scenario == "regrants_no_sweep")))
    elif _scenario in ("pairing", "pairing_deny"):
        # Task 6 (android first-load pairing loopback gate): a
        # structurally different entry point from every scenario above --
        # no mint_fixture at all (the whole point is the phone gets its
        # identity FROM the ceremony, not a pre-baked fixture), a minted
        # pairing code instead, and a background accept/deny driver
        # instead of a wrapped _on_conn. Kept as its own entry point for
        # the same reason outbound_compose/responses/dm are: every
        # pre-Task-6 call site (main(), _run_outbound_compose,
        # _run_responses, _run_dm) stays provably byte-identical to
        # before this task.
        asyncio.run(_run_pairing(sys.argv[1], deny=(_scenario == "pairing_deny")))
    else:
        asyncio.run(main(sys.argv[1], _scenario))
