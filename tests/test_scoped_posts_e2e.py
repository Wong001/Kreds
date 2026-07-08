"""Structural scope assertions for Kreds/Inner posts over real sockets.
Modelled on tests/test_sync_dm.py: real SyncService, started() helper,
store-level befriend(). The DM suite proves a non-recipient mutual friend
never receives a DM row at all; this file proves the analogous claims for
scoped posts: every Kreds friend decrypts, a non-inner friend never
receives an Inner post's row, and a ring demotion re-keys future posts
only (the friend keeps what they already had, never gets what comes
after the demotion)."""
import asyncio

from hearth.node import HearthNode
from hearth.sync import SyncService


def befriend(a, b):
    a.store.add_identity(b.identity_pub)
    b.store.add_identity(a.identity_pub)


async def started(node):
    svc = SyncService(node)
    port = await svc.start("127.0.0.1", 0)
    node.store.set_meta("gossip_addr", f"127.0.0.1:{port}")
    return svc, f"127.0.0.1:{port}"


def test_kreds_post_reaches_every_friend_over_sync(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        mikkel = HearthNode.create(tmp_path / "m", "Mikkel", "mikkel-phone")
        befriend(wong, freja); befriend(wong, mikkel)
        for n in (wong, freja, mikkel):
            n.ensure_enckey()
        sw, wa = await started(wong); sf, fa = await started(freja)
        sm, ma = await started(mikkel)
        await sw.sync_with(fa); await sw.sync_with(ma)     # exchange enckeys
        mid = wong.compose_post("til alle", scope="kreds")
        await sw.sync_with(fa); await sw.sync_with(ma)
        # every friend's node received AND decrypted the row
        assert [p["text"] for p in freja.feed()] == ["til alle"]
        assert [p["text"] for p in mikkel.feed()] == ["til alle"]
        assert freja.store.get_message(mid) is not None
        assert mikkel.store.get_message(mid) is not None
        for s in (sw, sf, sm): await s.stop()
    asyncio.run(scenario())


def test_inner_post_never_reaches_non_inner_over_sync(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        mikkel = HearthNode.create(tmp_path / "m", "Mikkel", "mikkel-phone")
        befriend(wong, freja); befriend(wong, mikkel)
        for n in (wong, freja, mikkel):
            n.ensure_enckey()
        sw, wa = await started(wong); sf, fa = await started(freja)
        sm, ma = await started(mikkel)
        await sw.sync_with(fa); await sw.sync_with(ma)     # exchange enckeys
        await sf.sync_with(wa); await sm.sync_with(wa)
        wong.set_ring(freja.identity_pub, "inner")
        mid = wong.compose_post("kun inner", scope="inner")
        await sw.sync_with(fa); await sw.sync_with(ma)
        assert [p["text"] for p in freja.feed()] == ["kun inner"]   # inner sees
        assert mikkel.store.get_message(mid) is None                # never got row
        for s in (sw, sf, sm): await s.stop()
    asyncio.run(scenario())


def test_ring_demotion_rekeys_future_posts_only_over_sync(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        befriend(wong, freja)
        for n in (wong, freja):
            n.ensure_enckey()
        sw, wa = await started(wong); sf, fa = await started(freja)
        await sw.sync_with(fa)                             # exchange enckeys

        wong.set_ring(freja.identity_pub, "inner")
        mid1 = wong.compose_post("inner 1", scope="inner")
        await sw.sync_with(fa)
        assert [p["text"] for p in freja.feed()] == ["inner 1"]

        wong.set_ring(freja.identity_pub, "kreds")         # demote
        mid2 = wong.compose_post("inner 2", scope="inner")
        await sw.sync_with(fa)

        # holds the post from while she was inner...
        assert [p["text"] for p in freja.feed()] == ["inner 1"]
        assert freja.store.get_message(mid1) is not None
        # ...but the post authored after demotion never reaches her at all
        assert freja.store.get_message(mid2) is None
        for s in (sw, sf): await s.stop()
    asyncio.run(scenario())
