import asyncio

from hearth.messages import make_enckey
from hearth.node import HearthNode
from hearth.sync import SyncService


def test_gossip_loop_carries_posts_and_sweeps_expiry(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        wong.store.add_identity(freja.identity_pub)
        freja.store.add_identity(wong.identity_pub)
        freja.ensure_enckey()                  # wong learns it via the loop

        sw, sf = SyncService(wong), SyncService(freja)
        wp = await sw.start("127.0.0.1", 0)
        fp = await sf.start("127.0.0.1", 0)
        wong.store.set_meta("gossip_addr", f"127.0.0.1:{wp}")
        freja.store.set_meta("gossip_addr", f"127.0.0.1:{fp}")
        wong.store.add_peer(f"127.0.0.1:{fp}", freja.identity_pub)

        loop_task = asyncio.create_task(sw.gossip_loop(interval=0.05))
        posted = {}

        for _ in range(100):                     # up to ~5s
            await asyncio.sleep(0.05)
            if not posted:
                if wong.store.enckeys(freja.identity_pub):
                    # loop learned freja's enc key; posts can now be wrapped
                    # for her before they are ever composed.
                    posted["ok"] = wong.compose_post("carried by the loop")
                    posted["doomed"] = wong.compose_post(
                        "gone soon", expires_seconds=0.0)
                continue
            texts = [p["text"] for p in freja.feed()]
            if texts == ["carried by the loop"] and \
                    wong.store.is_tombstoned(posted["doomed"]):
                break
        else:
            raise AssertionError("gossip loop did not converge")

        loop_task.cancel()
        await sw.stop()
        await sf.stop()

    asyncio.run(scenario())


def test_gossip_loop_publishes_enckey_and_caches_dm_keys(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        wong.store.add_identity(freja.identity_pub)
        freja.store.add_identity(wong.identity_pub)
        freja.ensure_enckey()                  # wong's key comes from loop
        sw, sf = SyncService(wong), SyncService(freja)
        wp = await sw.start("127.0.0.1", 0)
        fp = await sf.start("127.0.0.1", 0)
        wong.store.set_meta("gossip_addr", f"127.0.0.1:{wp}")
        freja.store.set_meta("gossip_addr", f"127.0.0.1:{fp}")
        wong.store.add_peer(f"127.0.0.1:{fp}", freja.identity_pub)
        loop_task = asyncio.create_task(sw.gossip_loop(interval=0.05))
        mid = None
        for _ in range(100):                   # up to ~5s
            await asyncio.sleep(0.05)
            if mid is None:
                if freja.store.enckeys(wong.identity_pub):
                    # loop published wong's enckey and gossiped it over;
                    # now freja can DM wong
                    mid = freja.compose_dm(wong.identity_pub, "til wong")
                continue
            if wong.store.cached_message_key(mid) is not None:
                break                          # loop swept + cached it
        else:
            raise AssertionError("loop did not publish/cache in time")
        loop_task.cancel()
        await sw.stop()
        await sf.stop()
    asyncio.run(scenario())


def test_gossip_loop_prunes_superseded_enckeys(tmp_path):
    """sync.py's _gossip_round wiring (wart 2): a stale enckey row (e.g. a
    prior day's rotation) left over on THIS node's own identity is
    tombstoned by the loop's own prune pass, without needing a peer round-
    trip at all."""
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        wong.store.add_identity(freja.identity_pub)
        freja.store.add_identity(wong.identity_pub)
        wong.ensure_enckey()                  # today's row: the one to keep
        stale = make_enckey(wong.device, now=1.0)   # yesterday's leftover
        assert wong.store.ingest_message(stale).accepted

        sw, sf = SyncService(wong), SyncService(freja)
        wp = await sw.start("127.0.0.1", 0)
        fp = await sf.start("127.0.0.1", 0)
        wong.store.set_meta("gossip_addr", f"127.0.0.1:{wp}")
        freja.store.set_meta("gossip_addr", f"127.0.0.1:{fp}")
        wong.store.add_peer(f"127.0.0.1:{fp}", freja.identity_pub)

        loop_task = asyncio.create_task(sw.gossip_loop(interval=0.05))
        for _ in range(100):                     # up to ~5s
            await asyncio.sleep(0.05)
            if wong.store.message_kind(stale.msg_id) is None:
                break
        else:
            raise AssertionError("gossip loop did not prune the stale enckey")
        assert wong.store.is_tombstoned(stale.msg_id)
        # today's row is untouched and still resolves
        assert wong.store.enckeys(wong.identity_pub)[wong.device.device_pub] \
            == wong.device.enc_pub

        loop_task.cancel()
        await sw.stop()
        await sf.stop()
    asyncio.run(scenario())
