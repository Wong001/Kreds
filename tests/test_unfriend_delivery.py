"""Direct-only delivery of a defriend notice: the `defriends` session
phase (hearth/sync.py) plus the outbox delivery pass (Node.deliver_
defriends). There is no `running_pair` fixture in this repo -- this
mirrors the real live-node idiom used throughout tests/test_sync_session.py
and tests/test_scoped_posts_e2e.py: HearthNode.create() + store-level
befriend() + a started() helper that stands up a real SyncService over a
loopback TCP socket, then drives sync_with()/deliver_defriends() directly
(no fixture indirection)."""
import asyncio
import os
import time

from hearth.identity import DefriendNotice, DeviceKeys, PROTOCOL, canonical
from hearth.messages import DEFRIEND_RETRY
from hearth.node import HearthNode
from hearth.sync import SyncService
from hearth.transport import read_frame, write_frame


async def _malicious_auth(m: HearthNode, reader, writer):
    """Perform HELLO+AUTH honestly as node m, acting as the responder in
    a hand-rolled (malicious) session: m authenticates as ITSELF -- it
    cannot forge another identity's cert/signature, it doesn't hold that
    identity's private key -- but the caller drives every frame after
    this by hand, free to deviate from the honest protocol. Leaves
    reader/writer positioned right after AUTH."""
    a_hello = await read_frame(reader)
    m_nonce = os.urandom(16).hex()
    await write_frame(writer, {"t": "hello", "cert": m.device.cert.to_dict(),
                                "nonce": m_nonce})
    await read_frame(reader)                          # a's auth frame
    sig = m.device.sign_raw(canonical({
        "type": "gossip-auth", "protocol": PROTOCOL,
        "nonce": a_hello["nonce"]}))
    await write_frame(writer, {"t": "auth", "sig": sig})


def _befriend(a: HearthNode, b: HearthNode):
    a.store.add_identity(b.identity_pub)
    b.store.add_identity(a.identity_pub)


async def _started(node: HearthNode):
    svc = SyncService(node)
    port = await svc.start("127.0.0.1", 0)
    addr = f"127.0.0.1:{port}"
    node.store.set_meta("gossip_addr", addr)
    return svc, addr


def test_delivery_applies_on_recipient_then_drops(tmp_path):
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Alice", "alice-phone")
        b = HearthNode.create(tmp_path / "b", "Bob", "bob-phone")
        _befriend(a, b)
        for n in (a, b):
            n.ensure_enckey()
        sa, aa = await _started(a)
        sb, ba = await _started(b)

        # One real sync round: each learns the other's gossip_addr (the
        # HAVE phase populates a's peer row for b, which is where
        # unfriend() reads b's "best-known address" from) and enc key
        # (needed so the post below is wrapped for b's device already).
        assert await sa.sync_with(ba)
        mid = a.compose_post("hello from a")
        assert await sa.sync_with(ba)               # carries the post to b
        assert b.store.messages_by_author(a.identity_pub)
        assert b.store.get_message(mid) is not None

        a.unfriend(b.identity_pub)                  # queues a notice -> b
        ob = a.store.list_outbox()
        assert ob and ob[0]["address"] == ba         # real address captured

        await a.deliver_defriends()                 # direct delivery to b

        # b applied the notice: purged a's content, forgot a, marked
        # disconnected.
        assert b.store.messages_by_author(a.identity_pub) == []
        assert not b.store.is_known(a.identity_pub)
        assert any(d["identity_pub"] == a.identity_pub
                  for d in b.store.list_disconnected())
        # b's applied ack (carried in-band on its DEFRIENDS response
        # frame) named a -> the outbox record is dropped, no re-delivery
        # needed.
        assert a.store.list_outbox() == []

        await sa.stop()
        await sb.stop()

    asyncio.run(scenario())


def test_delivery_drops_on_expiry_without_purging(tmp_path):
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Alice", "alice-phone")
        b = HearthNode.create(tmp_path / "b", "Bob", "bob-phone")
        _befriend(a, b)
        mid = b.compose_post("hello from b")
        a.store.ingest_message(b.store.get_message(mid))  # a has b's content

        a.unfriend(b.identity_pub)
        rec = a.store.list_outbox()[0]
        assert rec["target_identity"] == b.identity_pub

        # Force expiry: no live session is ever attempted (b is never
        # dialed -- deliver_defriends checks the expiry window before
        # opening a connection), so b never learns anything.
        await a.deliver_defriends(now=rec["expires_at"] + 1)

        assert a.store.list_outbox() == []           # given up, cleaned up
        # b was never contacted -> still knows a (no false purge triggered
        # by a giving up on delivery).
        assert b.store.is_known(a.identity_pub)

    asyncio.run(scenario())


def test_delivery_kept_when_recipient_does_not_apply(tmp_path):
    """T3 review, Fix 1: session-completion is NOT an application-level
    ack. If the recipient's apply_defriend_notice declines (tampered
    plaintext defriends frame failing notice.verify() on plain TCP, or
    any other decline), the sender's outbox record must survive so
    delivery keeps retrying until the 14-day window expires -- and the
    recipient-side session must fail fast rather than hang waiting on a
    HAVE frame the sender's cutoff will never send."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Alice", "alice-phone")
        b = HearthNode.create(tmp_path / "b", "Bob", "bob-phone")
        _befriend(a, b)
        sa, aa = await _started(a)
        sb, ba = await _started(b)
        assert await sa.sync_with(ba)          # a learns b's gossip_addr

        a.unfriend(b.identity_pub)             # queues a notice -> b
        ob = a.store.list_outbox()
        assert ob and ob[0]["address"] == ba

        # Simulate a decline on b's side (stand-in for a tampered notice
        # failing notice.verify()): apply_defriend_notice always fails.
        b.apply_defriend_notice = lambda notice: False

        # Explicit timeout: a prior async test in this area HUNG and was
        # falsely reported green, so this must actually terminate.
        await asyncio.wait_for(a.deliver_defriends(), timeout=10)

        # Not acked -> kept for retry, no false purge on b's side either.
        assert a.store.list_outbox() != []
        assert b.store.is_known(a.identity_pub)

        await sa.stop()
        await sb.stop()

    asyncio.run(scenario())


def test_delivery_drops_immediately_when_no_address(tmp_path):
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Alice", "alice-phone")
        b = HearthNode.create(tmp_path / "b", "Bob", "bob-phone")
        _befriend(a, b)
        # No sync round ever happened, so a never learned b's gossip
        # address: address_for(b) is None -> unfriend() queues the
        # outbox record with address == "".
        a.unfriend(b.identity_pub)
        rec = a.store.list_outbox()[0]
        assert rec["address"] == ""

        await a.deliver_defriends()            # no dial possible; give up

        assert a.store.list_outbox() == []

    asyncio.run(scenario())


def test_refriend_clears_pending_outbox_notice(tmp_path):
    """T3 review, Fix 2: unfriending then re-adding before the 14-day
    notice delivers must not leave the stale notice queued -- otherwise
    it could later deliver and silently undo the re-friend."""
    a = HearthNode.create(tmp_path / "a", "Alice", "alice-phone")
    b = HearthNode.create(tmp_path / "b", "Bob", "bob-phone")
    _befriend(a, b)

    a.unfriend(b.identity_pub)
    assert a.store.list_outbox()                       # notice queued

    # Real friend-add ceremony: a re-adds b.
    inv = a.create_invite()
    resp = b.respond_to_invite(inv)
    fin = a.finalize_invite(resp)
    b.complete_invite(fin)

    assert a.store.is_known(b.identity_pub)
    assert a.store.list_outbox() == []                  # stale notice cleared


def test_deliver_defriends_rejects_ack_from_wrong_authenticated_peer(tmp_path):
    """Whole-branch review, Fix 1 (CRITICAL): a poisoned peers-table entry
    pointing B's address at a malicious mutual friend M must not let M's
    lie ('applied': [A]) fool A into dropping B's outbox record. M
    authenticates honestly as ITSELF (it cannot forge B's identity -- it
    doesn't hold B's identity private key) but its raw wire responses lie
    about what it applied. deliver_defriends must bind the ack to the
    AUTHENTICATED peer (M's own identity, proven via the AUTH device-key
    challenge) rather than trusting the bare 'applied' list, so it must
    NOT drop the record -- B (never actually contacted) still knows A
    and must keep it for retry."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Alice", "alice-phone")
        b = HearthNode.create(tmp_path / "b", "Bob", "bob-phone")
        m = HearthNode.create(tmp_path / "m", "Mallory", "mal-phone")
        _befriend(a, b)
        _befriend(a, m)          # m is a genuine mutual friend of a

        async def handler(reader, writer):
            try:
                await _malicious_auth(m, reader, writer)
                await read_frame(reader)                 # a's revocations
                await write_frame(writer, {"t": "revocations", "revs": []})
                await read_frame(reader)                 # a's defriends
                # THE LIE: claim to have applied a's notice, even though
                # m never received any such notice (a's outbox targets b,
                # not m, so the real 'notices' list a sent is empty).
                await write_frame(writer, {"t": "defriends", "notices": [],
                                            "applied": [a.identity_pub]})
                await read_frame(reader)                 # a's have
                await write_frame(writer, {"t": "have", "summary": {},
                                            "known": [], "peers": [],
                                            "addr": None})
                await read_frame(reader)                 # a's messages
                await write_frame(writer, {"t": "messages", "msgs": []})
                await read_frame(reader)                 # a's blob_want
                await write_frame(writer, {"t": "blob_want", "hashes": []})
                await read_frame(reader)                 # a's blobs
                await write_frame(writer, {"t": "blobs", "blobs": {}})
            except Exception:
                pass
            finally:
                writer.close()

        server = await asyncio.start_server(handler, "127.0.0.1", 0)
        m_addr = f"127.0.0.1:{server.sockets[0].getsockname()[1]}"

        # Poison a's peer table: b's identity now resolves to m's
        # address, as if m had earlier fed a a HAVE-phase peer row
        # claiming to be b.
        a.store.add_peer(m_addr, b.identity_pub)

        sa = SyncService(a)                      # wires a._dial; a never listens
        a.unfriend(b.identity_pub)               # captures the poisoned address
        rec = a.store.list_outbox()[0]
        assert rec["address"] == m_addr

        # Explicit timeout: a prior async test in this area HUNG and was
        # falsely reported green, so this must actually terminate.
        await asyncio.wait_for(a.deliver_defriends(), timeout=10)

        # m's lie must not be trusted: the ack is bound to the peer
        # actually authenticated this session (m), not the record's
        # target (b) -- so the record survives for retry, and b, never
        # actually contacted, still knows a.
        assert a.store.list_outbox() != []
        assert b.store.is_known(a.identity_pub)

        server.close()
        await server.wait_closed()

    asyncio.run(scenario())


def test_deliver_defriends_ignores_ack_from_wrong_peer_unit(tmp_path):
    """Fix 1, minimal unit-level companion to the fuller attack-simulation
    test above: directly patch node._dial to return an authenticated
    identity that does not match the outbox record's target, carrying a
    (lying) ack that names us -- deliver_defriends must not drop the
    record."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Alice", "alice-phone")
        b = HearthNode.create(tmp_path / "b", "Bob", "bob-phone")
        _befriend(a, b)
        a.unfriend(b.identity_pub)
        a.store.add_outbox(
            DefriendNotice.from_dict(a.store.list_outbox()[0]["notice"]),
            "10.0.0.1:9000", expires_at=time.time() + 1000)

        async def lying_dial(address):
            return True, "attacker" + "0" * 56, [a.identity_pub]
        a._dial = lying_dial

        await asyncio.wait_for(a.deliver_defriends(), timeout=10)
        assert a.store.list_outbox() != []          # not dropped

    asyncio.run(scenario())


def test_deliver_defriends_ignores_refusal_from_non_target_peer(tmp_path):
    """Whole-branch review, Fix 3: the refusal-cleanup path must only fire
    for the record's own target. A poisoned-address node that
    authenticates as someone else entirely and refuses the session must
    not let A give up on B's outbox record -- a refusal from a DIFFERENT
    authenticated identity is not evidence B ever received anything."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Alice", "alice-phone")
        b = HearthNode.create(tmp_path / "b", "Bob", "bob-phone")
        m = HearthNode.create(tmp_path / "m", "Mallory", "mal-phone")
        _befriend(a, b)
        _befriend(a, m)

        async def handler(reader, writer):
            try:
                await _malicious_auth(m, reader, writer)
                await read_frame(reader)                 # a's revocations
                await write_frame(writer, {"t": "refused"})
            except Exception:
                pass
            finally:
                writer.close()

        server = await asyncio.start_server(handler, "127.0.0.1", 0)
        m_addr = f"127.0.0.1:{server.sockets[0].getsockname()[1]}"
        a.store.add_peer(m_addr, b.identity_pub)          # poisoned

        sa = SyncService(a)
        a.unfriend(b.identity_pub)
        rec = a.store.list_outbox()[0]
        assert rec["address"] == m_addr

        await asyncio.wait_for(a.deliver_defriends(), timeout=10)

        # m refused -- but m is not b, so this must not be treated as "b
        # already processed the notice."
        assert a.store.list_outbox() != []
        assert b.store.is_known(a.identity_pub)

        server.close()
        await server.wait_closed()

    asyncio.run(scenario())


def test_deliver_defriends_treats_target_refusal_as_delivered(tmp_path):
    """Design Component 2: a delivery attempt refused BY THE AUTHENTICATED
    TARGET itself means the target already processed the notice -- treat
    it as delivered and drop the record, rather than retrying it forever
    against someone who no longer knows us."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Alice", "alice-phone")
        b = HearthNode.create(tmp_path / "b", "Bob", "bob-phone")
        _befriend(a, b)
        sa, aa = await _started(a)
        sb, ba = await _started(b)
        assert await sa.sync_with(ba)             # a learns b's address

        a.unfriend(b.identity_pub)                # a forgets b, queues notice

        # Simulate b having already applied a's notice on some earlier
        # round without a's outbox record having been dropped yet: b
        # independently forgets a, so this dial finds b treating a as a
        # stranger (b has no outbox notice targeting a either).
        b.store.remove_identity(a.identity_pub)

        await asyncio.wait_for(a.deliver_defriends(), timeout=10)

        # b's AUTH-gate admission refuses a outright -- a must treat this
        # refusal, from b's own authenticated identity, as delivered.
        assert a.store.list_outbox() == []

        await sa.stop(); await sb.stop()

    asyncio.run(scenario())


def test_deliver_defriends_backs_off_after_failed_attempt(tmp_path):
    """Whole-branch review, Fix 3: a failed/declined delivery attempt must
    not be re-dialed on every single gossip tick. After one attempt the
    record's next_attempt_at is pushed out by DEFRIEND_RETRY; a round run
    inside that window must skip the dial entirely, and one run after it
    elapses must dial again."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Alice", "alice-phone")
        b = HearthNode.create(tmp_path / "b", "Bob", "bob-phone")
        _befriend(a, b)
        sa, aa = await _started(a)
        sb, ba = await _started(b)
        assert await sa.sync_with(ba)

        a.unfriend(b.identity_pub)
        b.apply_defriend_notice = lambda notice: False   # always declines

        t0 = time.time()
        await asyncio.wait_for(a.deliver_defriends(now=t0), timeout=10)
        rec = a.store.list_outbox()[0]
        assert rec["next_attempt_at"] >= t0 + DEFRIEND_RETRY

        calls = []
        real_dial = a._dial
        async def counting_dial(address):
            calls.append(address)
            return await real_dial(address)
        a._dial = counting_dial

        # Still inside the backoff window: skipped outright, no dial.
        await asyncio.wait_for(
            a.deliver_defriends(now=t0 + 10), timeout=10)
        assert calls == []

        # Backoff window elapsed: dials again.
        await asyncio.wait_for(
            a.deliver_defriends(now=t0 + DEFRIEND_RETRY + 10), timeout=10)
        assert calls == [rec["address"]]

        await sa.stop(); await sb.stop()

    asyncio.run(scenario())


def test_expired_outbox_notice_does_not_admit_stranger(tmp_path):
    """Whole-branch review, Fix 8: an expired-but-not-yet-pruned outbox
    notice must not admit a stranger at AUTH. B (whom a has fully
    forgotten) dialing in while a's only outbox record for b is already
    expired must still be refused."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Alice", "alice-phone")
        b = HearthNode.create(tmp_path / "b", "Bob", "bob-phone")
        _befriend(a, b)
        sa, aa = await _started(a)
        sb, ba = await _started(b)
        assert await sa.sync_with(ba)

        a.unfriend(b.identity_pub)                # queues an outbox record for b
        rec = a.store.list_outbox()[0]
        notice = DefriendNotice.from_dict(rec["notice"])
        # Force the record to already be expired (simulating a stale,
        # not-yet-pruned entry) without touching anything else about it.
        a.store.add_outbox(notice, rec["address"], expires_at=time.time() - 1)

        # b (now a stranger to a) dials a directly -- must be refused,
        # since the only outbox record that could admit it is expired.
        assert await sb.sync_with(aa) is False

        await sa.stop(); await sb.stop()

    asyncio.run(scenario())


def test_defriends_outbox_only_peer_gets_empty_revocations(tmp_path):
    """Whole-branch review, Fix 2: a peer admitted ONLY via the pending-
    outbox carve-out (a's side no longer knows them at all) must not be
    handed a's full revocation set -- that would leak the revocation
    state of every OTHER identity a knows to someone no longer entitled
    to any of a's metadata."""
    import hearth.sync as sync_mod

    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Alice", "alice-phone")
        b = HearthNode.create(tmp_path / "b", "Bob", "bob-phone")
        _befriend(a, b)
        sa, aa = await _started(a)
        sb, ba = await _started(b)
        assert await sa.sync_with(ba)             # learn addresses first

        # Give a a revocation record (of one of a's OWN other devices) so
        # list_revocations() is non-empty and would normally be shared
        # with any ordinary friend.
        tablet = DeviceKeys.create("alice-tablet")
        cert = a.device.enroll_other(tablet.device_pub, tablet.name)
        tablet.install(cert, a.device.to_json()["identity_priv"])
        rev = a.device.make_revocation(tablet.device_pub, 0)
        assert a.store.ingest_revocation(rev).accepted
        assert a.store.list_revocations()

        a.unfriend(b.identity_pub)                # a forgets b, queues notice

        sent = []
        real_write_frame = sync_mod.write_frame

        async def spying_write_frame(writer, obj):
            if obj.get("t") == "revocations":
                sent.append(obj)
            await real_write_frame(writer, obj)

        sync_mod.write_frame = spying_write_frame
        try:
            await asyncio.wait_for(a.deliver_defriends(), timeout=10)
        finally:
            sync_mod.write_frame = real_write_frame

        # a's own outgoing revocations frame (written first, as the
        # initiator, before b can reply) must be empty -- b is admitted
        # only via the outbox now.
        assert sent and sent[0]["revs"] == []

        await sa.stop(); await sb.stop()

    asyncio.run(scenario())
