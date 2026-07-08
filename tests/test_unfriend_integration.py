"""Slice B safety integration tests: the resurrection guard (a former
friend's content cannot come back from a mutual friend once purged), proof
that a bare refusal/offline session never triggers a purge (there is no
refusal-trigger code path in this repo -- this confirms that absence
holds), and that re-friending through the real invite ceremony clears the
'no longer connected' marker. Modelled on tests/test_unfriend_delivery.py
and tests/test_scoped_posts_e2e.py: this repo has no conftest.py/pytest
fixtures -- real HearthNode.create() + store-level befriend() + a
started() helper stand up a real SyncService over a loopback TCP socket,
driving sync_with()/deliver_defriends() directly."""
import asyncio

from hearth.node import HearthNode
from hearth.sync import SyncService


def befriend(a: HearthNode, b: HearthNode):
    a.store.add_identity(b.identity_pub)
    b.store.add_identity(a.identity_pub)


async def started(node: HearthNode):
    svc = SyncService(node)
    port = await svc.start("127.0.0.1", 0)
    addr = f"127.0.0.1:{port}"
    node.store.set_meta("gossip_addr", addr)
    return svc, addr


def test_purged_content_not_resurrected_via_mutual_friend(tmp_path):
    """Core safety property. A, B, C are all mutual friends and A's post
    reaches both B and C. A.unfriend(B) then delivers a real notice: B
    purges A's authored content and forgets A. C (unaffected -- A never
    unfriended C) still holds A's post and syncs with B for real: B's
    HAVE frame no longer lists A as known, so C's entitled-set filter
    (Store.messages_not_in) never even offers the post -- B does not
    re-acquire it. Defense in depth is exercised directly too: even if
    A's post reached B's ingest path some other way, Store.ingest_
    message's unknown-identity gate rejects it outright."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Alice", "alice-phone")
        b = HearthNode.create(tmp_path / "b", "Bob", "bob-phone")
        c = HearthNode.create(tmp_path / "c", "Carol", "carol-phone")
        befriend(a, b); befriend(a, c); befriend(b, c)   # all mutual friends
        for n in (a, b, c):
            n.ensure_enckey()
        sa, aa = await started(a)
        sb, ba = await started(b)
        sc, ca = await started(c)

        # Learn addresses + exchange enc keys (also gives a.unfriend() a
        # real address for b, captured off the peer table below).
        assert await sa.sync_with(ba)
        assert await sa.sync_with(ca)

        mid = a.compose_post("hygge for alle")
        assert await sa.sync_with(ba)          # carries the post to b
        assert await sa.sync_with(ca)          # carries the post to c
        assert b.store.get_message(mid) is not None
        assert c.store.get_message(mid) is not None
        post = c.store.get_message(mid)        # c's own copy, reused below

        a.unfriend(b.identity_pub)             # queues a notice -> b
        await a.deliver_defriends()            # b applies it for real

        assert b.store.messages_by_author(a.identity_pub) == []
        assert not b.store.is_known(a.identity_pub)

        # c still holds a's post and syncs with b over a real socket: b no
        # longer declares a as known, so c never even offers it.
        assert await sc.sync_with(ba)
        assert b.store.messages_by_author(a.identity_pub) == []
        assert b.store.get_message(mid) is None

        # Defense in depth: direct ingest of a's post into b's store is
        # rejected by the unknown-identity gate, not silently dropped.
        res = b.store.ingest_message(post)
        assert not res.accepted
        assert res.reason == "unknown identity"
        assert b.store.get_message(mid) is None

        await sa.stop(); await sb.stop(); await sc.stop()

    asyncio.run(scenario())


def test_refusal_alone_never_purges(tmp_path):
    """No refusal-trigger code path exists anywhere in this codebase --
    this test confirms that absence holds. A cuts B out of its own
    identities directly, WITHOUT ever calling unfriend() (no notice is
    minted, nothing is queued to A's outbox). When B then dials A, AUTH
    refuses the session (A no longer knows B, and there is no pending
    outbox notice targeting B to admit it) -- but B never receives, let
    alone applies, a defriend notice, so B must keep everything."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Alice", "alice-phone")
        b = HearthNode.create(tmp_path / "b", "Bob", "bob-phone")
        befriend(a, b)
        for n in (a, b):
            n.ensure_enckey()
        sa, aa = await started(a)
        sb, ba = await started(b)
        assert await sa.sync_with(ba)          # learn addr + enc key

        mid = a.compose_post("stadig venner")
        assert await sa.sync_with(ba)          # carries the post to b
        assert b.store.get_message(mid) is not None

        # Simulate a cut on a's side only: no unfriend() call, so no
        # notice is minted and nothing is queued to a's outbox.
        a.store.remove_identity(b.identity_pub)

        # b tries to sync with a and is refused at AUTH.
        assert await sb.sync_with(aa) is False

        # No notice was ever received or applied -- b must not have
        # purged anything, and must still consider a known.
        assert b.store.messages_by_author(a.identity_pub)   # still there
        assert b.store.get_message(mid) is not None
        assert b.store.is_known(a.identity_pub)

        await sa.stop(); await sb.stop()

    asyncio.run(scenario())


def test_refriend_clears_disconnected(tmp_path):
    """Re-friending through the real invite ceremony must clear the 'no
    longer connected' marker a previously-applied defriend notice left
    behind -- otherwise a re-added friend would still show as
    disconnected in the UI."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Alice", "alice-phone")
        b = HearthNode.create(tmp_path / "b", "Bob", "bob-phone")
        befriend(a, b)
        sa, aa = await started(a)
        sb, ba = await started(b)
        assert await sa.sync_with(ba)

        a.unfriend(b.identity_pub)             # queues a notice -> b
        await a.deliver_defriends()            # b applies it for real
        assert not b.store.is_known(a.identity_pub)
        assert any(d["identity_pub"] == a.identity_pub
                  for d in b.store.list_disconnected())

        # Real re-friend ceremony -- both sides forgot each other above.
        inv = a.create_invite()
        resp = b.respond_to_invite(inv)
        fin = a.finalize_invite(resp)
        b.complete_invite(fin)

        assert b.store.is_known(a.identity_pub)
        assert all(d["identity_pub"] != a.identity_pub
                  for d in b.store.list_disconnected())

        await sa.stop(); await sb.stop()

    asyncio.run(scenario())
