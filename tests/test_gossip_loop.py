import asyncio

from hearth import sync
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


def test_gossip_loop_ticks_autolock_at_sleep_boundary(tmp_path, monkeypatch):
    # Pin the wiring order: round -> stamp -> sleep -> maybe_autolock (the
    # 0.3.11 misfire fix -- the tick must bracket ONLY the sleep, not the
    # round, so a slow round's dial time can't masquerade as a suspend).
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    svc = SyncService(node)
    calls = []

    async def fake_round(interval=3.0, now=None):
        calls.append("round")
    svc._gossip_round = fake_round

    node.stamp_autolock_tick = lambda now=None: calls.append("stamp")
    node.maybe_autolock = lambda interval: calls.append("tick")

    async def fake_sleep(interval):
        calls.append("sleep")
        if calls.count("sleep") >= 2:
            raise asyncio.CancelledError
    monkeypatch.setattr(sync.asyncio, "sleep", fake_sleep)

    async def scenario():
        loop_task = asyncio.create_task(svc.gossip_loop(interval=0.0))
        try:
            await loop_task
        except asyncio.CancelledError:
            pass
    asyncio.run(scenario())

    assert calls[:4] == ["round", "stamp", "sleep", "tick"]


def test_gossip_loop_survives_raising_autolock_tick(tmp_path, monkeypatch):
    # maybe_autolock reads+parses applock.json; a transient OSError (a
    # Windows read racing the settings endpoint's os.replace) must never
    # kill the loop -- it sits OUTSIDE _gossip_round's guard, so it needs
    # its own. Without the fix the first raise propagates out of gossip_loop
    # and no second round ever runs.
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    svc = SyncService(node)
    rounds = []

    async def fake_round(interval=3.0, now=None):
        rounds.append(1)
    svc._gossip_round = fake_round

    node.stamp_autolock_tick = lambda now=None: None
    calls = {"n": 0}

    def boom(interval):
        calls["n"] += 1
        if calls["n"] == 1:
            raise OSError("applock.json read raced os.replace")
    node.maybe_autolock = boom

    async def fake_sleep(interval):
        if len(rounds) >= 2:
            raise asyncio.CancelledError
    monkeypatch.setattr(sync.asyncio, "sleep", fake_sleep)

    async def scenario():
        loop_task = asyncio.create_task(svc.gossip_loop(interval=0.0))
        try:
            await loop_task
        except asyncio.CancelledError:
            pass
    asyncio.run(scenario())

    assert calls["n"] >= 1        # the raising maybe_autolock actually ran
    assert len(rounds) >= 2       # and the loop survived into another round
