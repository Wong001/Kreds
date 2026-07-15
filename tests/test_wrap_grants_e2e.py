"""'A wall is a wall' end-to-end over real sockets (spec 2026-07-15):
wall posts made BEFORE a friendship reach the new friend after the
author's sweep; journal and inner posts from before the friendship stay
invisible; the row-before-grant ordering un-poisons the negative cache."""
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


def test_wall_back_catalog_opens_to_new_friend_over_sync(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        josh = HearthNode.create(tmp_path / "j", "Josh", "josh-phone")
        wong.ensure_enckey()
        # pre-friendship back catalog: wall (with a real photo blob, for
        # the blob follow-through assertion) + journal + inner wall.
        # PNG fixture idiom shared with test_imagegate.py.
        import io
        from PIL import Image
        buf = io.BytesIO()
        Image.new("RGB", (64, 64), (30, 80, 180)).save(buf, format="PNG")
        png = buf.getvalue()
        wall = wong.compose_post("bagkatalog", scope="kreds",
                                 placement="profile", photos=[png])
        journal = wong.compose_post("dagbog", scope="kreds")
        inner_wall = wong.compose_post("hemmelig", scope="inner",
                                       placement="profile")
        befriend(wong, josh)
        josh.ensure_enckey()
        sw, wa = await started(wong)
        sj, ja = await started(josh)
        await sw.sync_with(ja)              # enckeys cross
        await sj.sync_with(wa)
        wong.maintain_wrap_grants()         # the sweep (gossip-loop hook)
        await sw.sync_with(ja)              # grant + post row flow
        await sw.sync_with(ja)              # second round: blob want/give
        # NOTE (plan amended after Task 4): feed() is journal-only
        # (node.py:1196), wall posts render via posts_by/profile_view.
        wall_texts = [p["text"] for p in
                      josh.posts_by(wong.identity_pub, "profile")]
        assert "bagkatalog" in wall_texts
        assert josh.store.get_message(wall) is not None
        assert josh.store.missing_blobs() == set()          # photo followed
        assert josh.store.get_message(journal) is None      # journal: never
        assert josh.store.get_message(inner_wall) is None   # inner: never
        for s in (sw, sj):
            await s.stop()
    asyncio.run(scenario())


def test_stale_grant_poison_then_remint_unpoisons(tmp_path):
    # A post the recipient was never wrapped for is not a decrypt
    # CANDIDATE (uncached_message_ids gates on payload-or-grant
    # coverage), so a bare row can never be poisoned. The REAL
    # permanent-poisoning path is a STALE grant: sealed to an enc key
    # the recipient no longer holds -> the post IS a candidate, decrypt
    # fails, negative-cached -- and without grant-ingest clearing, the
    # author's re-mint could never heal it. Also exercises the rotation
    # re-mint flow end to end.
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        wong.ensure_enckey()
        mid = wong.compose_post("foerst raekken", scope="kreds",
                                placement="profile")
        befriend(wong, freja)
        freja.ensure_enckey()
        sw, wa = await started(wong)
        sf, fa = await started(freja)
        await sf.sync_with(wa)              # enckeys cross
        # Simulate a grant minted before "rotation": author-signed, names
        # freja's device, but sealed to a key she never had; the enc_pub
        # annotation records that stale key so the sweep can see it.
        from hearth.messages import make_wrap_grant
        stale = {freja.device.device_pub: {
            "eph_pub": "ab" * 32, "nonce": "cd" * 12,
            "wrapped_key": "ef" * 48, "enc_pub": "00" * 32}}
        wong.store.ingest_message(make_wrap_grant(wong.device, mid, stale))
        await sw.sync_with(fa)              # row + stale grant arrive
        freja.cache_message_keys()          # decrypt fails -> poisoned
        assert mid in freja.store.undecryptable_ids()
        # the author's sweep detects the stale enc_pub and re-mints
        wong.maintain_wrap_grants()
        await sw.sync_with(fa)              # real grant arrives -> clears
        assert mid not in freja.store.undecryptable_ids()   # un-poisoned
        freja.cache_message_keys()          # next gossip-round sweep
        assert "foerst raekken" in [p["text"] for p in
                                    freja.posts_by(wong.identity_pub,
                                                   "profile")]
        for s in (sw, sf):
            await s.stop()
    asyncio.run(scenario())


def test_delete_then_sweep_mints_no_grant(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        wong.ensure_enckey()
        mid = wong.compose_post("fortrydes", scope="kreds",
                                placement="profile")
        # author deletes BEFORE any friendship exists
        from hearth.messages import make_delete
        wong.store.ingest_message(make_delete(wong.device, mid))
        befriend(wong, freja)
        freja.ensure_enckey()
        sw, wa = await started(wong)
        sf, fa = await started(freja)
        await sf.sync_with(wa)
        wong.maintain_wrap_grants()
        assert wong.store.wrap_grants(mid, wong.identity_pub) == {}
        await sw.sync_with(fa)
        assert freja.store.get_message(mid) is None
        for s in (sw, sf):
            await s.stop()
    asyncio.run(scenario())
