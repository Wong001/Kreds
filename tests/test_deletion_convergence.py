"""Adverse-order deletion convergence over real gossip sockets.

Proves the spec's system-level claim: a node that receives a delete tag
BEFORE the content it targets (possible whenever tag and content travel
by different paths) converges to deleted, for DMs and stories, on every
compliant node. Fails on pre-fix stores, where the late content
resurrects permanently.
"""
import asyncio
import json as _json

from hearth.identity import DeviceKeys, DeviceView
from hearth.messages import make_story
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


def pair_home_node(owner, hd, device_name):
    hd.mkdir()
    home_dev = DeviceKeys.create(device_name)
    home_dev.install(
        owner.device.enroll_other(home_dev.device_pub, home_dev.name),
        owner.device.to_json()["identity_priv"])
    (hd / "keys.json").write_text(_json.dumps(home_dev.to_json()))
    home = HearthNode(hd)
    home.store.add_identity(home.identity_pub, is_self=True)
    home.store.save_views(
        home.identity_pub,
        {home_dev.device_pub: DeviceView(cert=home_dev.cert)})
    return home


def test_dm_delete_tag_beats_late_content(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "fp", "Freja", "freja-phone")
        home = pair_home_node(freja, tmp_path / "fh", "freja-homenode")
        befriend(wong, freja)
        home.store.add_identity(wong.identity_pub)
        for n in (wong, freja, home):
            n.ensure_enckey()
        sw, wa = await started(wong)
        sf, fa = await started(freja)
        sh, ha = await started(home)
        # everyone learns everyone's enckeys
        await sw.sync_with(fa); await sw.sync_with(ha)
        await sf.sync_with(ha)
        # DM lands on freja-home only; freja-phone stays out of the loop
        mid = wong.compose_dm(freja.identity_pub, "fortryd mig")
        await sw.sync_with(ha)
        assert home.store.get_message(mid) is not None
        # freja-home goes offline BEFORE the delete
        await sh.stop()
        wong.delete_post(mid)          # tombstones immediately on wong
        # freja-phone can now only ever hear the tag from wong
        await sw.sync_with(fa)
        assert freja.store.get_message(mid) is None
        # tag with absent target must NOT pre-tombstone (authorization
        # is only checkable when the content arrives)
        assert not freja.store.is_tombstoned(mid)
        # freja-home returns holding the live DM; phone must not resurrect
        sh2, ha2 = await started(home)
        await sf.sync_with(ha2)
        assert freja.dm_thread(wong.identity_pub) == []
        assert freja.store.is_tombstoned(mid)
        # and the phone's tag killed it on the home node too
        assert home.store.get_message(mid) is None
        assert home.store.is_tombstoned(mid)
        for s in (sw, sf, sh2):
            await s.stop()
    asyncio.run(scenario())


def test_story_delete_tag_beats_late_content(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        mads = HearthNode.create(tmp_path / "m", "Mads", "mads-phone")
        befriend(wong, freja); befriend(wong, mads); befriend(freja, mads)
        sw, wa = await started(wong)
        sf, fa = await started(freja)
        sm, ma = await started(mads)
        # wong authors a story at store level (sidesteps the Pillow/ffmpeg
        # transcode gates; gossip treats it identically)
        media = wong.store.put_blob(b"story-media-bytes")
        story = make_story(wong.device, "photo", media)
        assert wong.store.ingest_message(story).accepted
        # freja receives story + blob, then goes offline
        await sf.sync_with(wa)
        assert freja.store.get_message(story.msg_id) is not None
        assert freja.store.has_blob(media)
        await sf.stop()
        # wong deletes; mads hears only the tag; then wong goes offline
        wong.delete_post(story.msg_id)
        await sm.sync_with(wa)
        assert mads.store.get_message(story.msg_id) is None
        await sw.stop()
        # freja returns holding live content; mads must not resurrect it,
        # and mads' tag must kill it on freja
        sf2, fa2 = await started(freja)
        await sm.sync_with(fa2)
        assert mads.store.get_message(story.msg_id) is None
        assert mads.store.is_tombstoned(story.msg_id)
        assert not mads.store.has_blob(media)
        assert freja.store.is_tombstoned(story.msg_id)
        assert not freja.store.has_blob(media)
        for s in (sf2, sm):
            await s.stop()
    asyncio.run(scenario())
