import asyncio

from hearth.node import HearthNode
from hearth.sync import SyncService
from tests.test_imagegate import animated_gif_bytes


def befriend(a: HearthNode, b: HearthNode):
    """Direct store-level friendship (the ceremony is Task 13)."""
    a.store.add_identity(b.identity_pub)
    b.store.add_identity(a.identity_pub)


async def started(node):
    svc = SyncService(node)
    port = await svc.start("127.0.0.1", 0)
    addr = f"127.0.0.1:{port}"
    node.store.set_meta("gossip_addr", addr)
    return svc, addr


def test_posts_and_blobs_propagate_between_friends(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        befriend(wong, freja)
        sw, wa = await started(wong)
        sf, fa = await started(freja)
        for n in (wong, freja):
            n.ensure_enckey()
        await sw.sync_with(fa)                  # exchange enc keys first
        photo = animated_gif_bytes()         # byte-identity is the point below
        wong.compose_post("hej Freja", photos=[photo])
        assert await sw.sync_with(fa) is True
        feed = freja.feed()
        assert [p["text"] for p in feed] == ["hej Freja"]
        assert feed[0]["author_name"] == "Wong"     # profile synced too
        assert freja.store.get_blob(feed[0]["blobs"][0]) != photo  # ciphertext
        assert freja.post_blob(feed[0]["msg_id"], feed[0]["blobs"][0]) == photo
        await sw.stop()
        await sf.stop()

    asyncio.run(scenario())


def test_stranger_is_refused(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        mallory = HearthNode.create(tmp_path / "m", "Mallory", "mal-phone")
        # NOT friends: no add_identity in either direction.
        sw, wa = await started(wong)
        sm, _ = await started(mallory)
        wong.compose_post("private thoughts")
        assert await sm.sync_with(wa) is False       # refused at AUTH
        assert mallory.feed() == []
        assert mallory.store.known_identities() == [mallory.identity_pub]
        await sw.stop()
        await sm.stop()

    asyncio.run(scenario())


def test_deletion_propagates(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        befriend(wong, freja)
        sw, _ = await started(wong)
        sf, fa = await started(freja)
        for n in (wong, freja):
            n.ensure_enckey()
        await sw.sync_with(fa)                  # exchange enc keys first
        mid = wong.compose_post("regret this")
        await sw.sync_with(fa)
        assert len(freja.feed()) == 1
        wong.delete_post(mid)
        await sw.sync_with(fa)
        assert freja.feed() == []
        assert freja.store.is_tombstoned(mid)
        await sw.stop()
        await sf.stop()

    asyncio.run(scenario())


def test_revocation_first_and_retro_drop_across_network(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        befriend(wong, freja)
        sw, _ = await started(wong)
        sf, fa = await started(freja)
        for n in (wong, freja):
            n.ensure_enckey()
        await sw.sync_with(fa)                        # exchange enc keys first
        wong.compose_post("legit post")
        await sw.sync_with(fa)                        # freja has seq so far

        # Enroll + compromise a tablet; its loot reaches freja BEFORE any
        # revocation (the gossip-lag window).
        from hearth.identity import DeviceKeys, DeviceView
        from hearth.messages import make_post
        tablet = DeviceKeys.create("wong-tablet")
        cert = wong.device.enroll_other(tablet.device_pub, tablet.name)
        tablet.install(cert, wong.device.to_json()["identity_priv"])
        loot = make_post(tablet, "kreds", body_nonce="ab" * 12,
                         body_ct="deadbeef", wraps={})
        assert freja.store.ingest_message(loot).accepted  # exposed window

        # Wong revokes the tablet; next sync must retro-drop at freja.
        wong.revoke_device(tablet.device_pub)
        await sw.sync_with(fa)
        assert [p["text"] for p in freja.feed()] == ["legit post"]
        assert freja.store.is_tombstoned(loot.msg_id)
        await sw.stop()
        await sf.stop()

    asyncio.run(scenario())


def test_own_devices_adopt_friend_list(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        befriend(wong, freja)
        # Home node paired at store level (ceremony is Task 13): same
        # identity, distinct device.
        from hearth.identity import DeviceKeys, DeviceView
        import json as _json
        from pathlib import Path
        home_dir = tmp_path / "h"
        home_dir.mkdir()
        home_dev = DeviceKeys.create("wong-homenode")
        home_dev.install(
            wong.device.enroll_other(home_dev.device_pub, home_dev.name),
            wong.device.to_json()["identity_priv"])
        (home_dir / "keys.json").write_text(_json.dumps(home_dev.to_json()))
        home = HearthNode(home_dir)
        home.store.add_identity(home.identity_pub, is_self=True)
        home.store.save_views(home.identity_pub, {
            home_dev.device_pub: DeviceView(cert=home_dev.cert)})

        sw, wa = await started(wong)
        sh, _ = await started(home)
        assert await sh.sync_with(wa) is True         # own-device session
        # home adopted wong's friend list (freja) + wong's messages
        assert freja.identity_pub in home.store.known_identities()
        assert len(home.feed()) == len(wong.feed())
        await sw.stop()
        await sh.stop()

    asyncio.run(scenario())
