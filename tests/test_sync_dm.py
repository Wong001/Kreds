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


def test_dm_reaches_recipient_but_not_mutual_friend(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        mads = HearthNode.create(tmp_path / "m", "Mads", "mads-phone")
        befriend(wong, freja); befriend(wong, mads); befriend(freja, mads)
        for n in (wong, freja, mads):
            n.ensure_enckey()
        sw, wa = await started(wong)
        sf, fa = await started(freja)
        sm, ma = await started(mads)
        # exchange enckeys first
        await sw.sync_with(fa); await sw.sync_with(ma)
        await sf.sync_with(ma)
        mid = wong.compose_dm(freja.identity_pub, "kun os to")
        await sw.sync_with(fa)             # to Freja
        await sw.sync_with(ma)             # and a sync with Mads too
        assert [t["text"] for t in freja.dm_thread(wong.identity_pub)] \
            == ["kun os to"]
        # Mads, a mutual friend, never receives the DM row at all
        assert mads.store.get_message(mid) is None
        for s in (sw, sf, sm):
            await s.stop()
    asyncio.run(scenario())


def test_dm_via_home_node_mailbox_while_phone_offline(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "fp", "Freja", "freja-phone")
        befriend(wong, freja)
        # Freja pairs a home node (same identity, second device)
        import json as _json
        from hearth.identity import DeviceKeys, DeviceView
        hd = tmp_path / "fh"; hd.mkdir()
        home_dev = DeviceKeys.create("freja-homenode")
        home_dev.install(
            freja.device.enroll_other(home_dev.device_pub, home_dev.name),
            freja.device.to_json()["identity_priv"])
        (hd / "keys.json").write_text(_json.dumps(home_dev.to_json()))
        home = HearthNode(hd)
        home.store.add_identity(home.identity_pub, is_self=True)
        home.store.save_views(home.identity_pub,
                              {home_dev.device_pub: DeviceView(cert=home_dev.cert)})
        home.store.add_identity(wong.identity_pub)
        for n in (wong, freja, home):
            n.ensure_enckey()
        sw, wa = await started(wong)
        sf, fa = await started(freja)
        sh, ha = await started(home)
        # everyone learns everyone's enckeys; home learns Freja's + Wong's
        await sf.sync_with(wa); await sf.sync_with(ha)
        await sh.sync_with(wa)
        # Freja's phone goes offline; Wong DMs Freja; only home is reachable
        await sf.stop()
        mid = wong.compose_dm(freja.identity_pub, "besked mens du sover")
        await sw.sync_with(ha)             # DM lands on Freja's home node
        assert home.store.get_message(mid) is not None
        # phone returns, picks it up from home
        sf2, fa2 = await started(freja)
        await sf2.sync_with(ha)
        assert [t["text"] for t in freja.dm_thread(wong.identity_pub)] \
            == ["besked mens du sover"]
        for s in (sw, sh, sf2):
            await s.stop()
    asyncio.run(scenario())


def test_revocation_triggers_self_logout_over_sync(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        # Wong pairs a home node, then the home node revokes the phone
        import json as _json
        from hearth.identity import DeviceKeys, DeviceView
        hd = tmp_path / "h"; hd.mkdir()
        home_dev = DeviceKeys.create("wong-homenode")
        home_dev.install(
            wong.device.enroll_other(home_dev.device_pub, home_dev.name),
            wong.device.to_json()["identity_priv"])
        (hd / "keys.json").write_text(_json.dumps(home_dev.to_json()))
        home = HearthNode(hd)
        home.store.add_identity(home.identity_pub, is_self=True)
        home.store.save_views(home.identity_pub,
                              {home_dev.device_pub: DeviceView(cert=home_dev.cert)})
        wong.compose_post("last words from the phone")
        sw, wa = await started(wong)
        sh, ha = await started(home)
        home.revoke_device(wong.device.device_pub)   # revoke the phone
        assert await sh.sync_with(wa)                 # phone hears it
        assert wong.revoked is True                   # self-logged-out
        assert wong.feed() == []                      # store wiped
        # a revoked node refuses further sync
        assert await sw.sync_with(ha) is False
        for s in (sw, sh):
            await s.stop()
    asyncio.run(scenario())


def test_revoked_sibling_cannot_inject_friend_list(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        import json as _json
        from hearth.identity import DeviceKeys, DeviceView
        hd = tmp_path / "h"; hd.mkdir()
        home_dev = DeviceKeys.create("wong-homenode")
        home_dev.install(
            wong.device.enroll_other(home_dev.device_pub, home_dev.name),
            wong.device.to_json()["identity_priv"])
        (hd / "keys.json").write_text(_json.dumps(home_dev.to_json()))
        home = HearthNode(hd)
        home.store.add_identity(home.identity_pub, is_self=True)
        home.store.save_views(home.identity_pub,
                              {home_dev.device_pub: DeviceView(cert=home_dev.cert)})
        # home node injects a bogus identity into its friend list
        bogus = "cc" * 32
        home.store.add_identity(bogus)
        # wong's phone REVOKES the home node and knows it locally
        wong.revoke_device(home_dev.device_pub)
        sw, wa = await started(wong)
        sh, ha = await started(home)
        # phone syncs with the (now revoked, non-compliant) home node
        await sw.sync_with(ha)
        # the bogus identity must NOT have entered wong's friend list
        assert bogus not in wong.store.known_identities()
        for s in (sw, sh):
            await s.stop()
    asyncio.run(scenario())


def test_known_revoked_sibling_served_no_content(tmp_path):
    # A "stolen device that kept its keys" scenario: wong's phone still has
    # secret content; the paired home node has been revoked (say, reported
    # stolen) and, per the scenario, ignores its own revoked status and
    # keeps trying to sync as if nothing happened. It must hear its
    # revocation and then get NOTHING else -- no post, no friend list, no
    # blobs -- from the honest phone.
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        wong.compose_post("secret post the thief should not get")
        import json as _json
        from hearth.identity import DeviceKeys, DeviceView
        hd = tmp_path / "h"; hd.mkdir()
        home_dev = DeviceKeys.create("wong-homenode")
        home_dev.install(
            wong.device.enroll_other(home_dev.device_pub, home_dev.name),
            wong.device.to_json()["identity_priv"])
        (hd / "keys.json").write_text(_json.dumps(home_dev.to_json()))
        home = HearthNode(hd)
        home.store.add_identity(home.identity_pub, is_self=True)
        home.store.save_views(home.identity_pub,
                              {home_dev.device_pub: DeviceView(cert=home_dev.cert)})
        # the home node is later reported stolen; wong's phone revokes it
        wong.revoke_device(home_dev.device_pub)
        sw, wa = await started(wong)
        sh, ha = await started(home)
        # the stolen device initiates a pull from the honest phone, as a
        # thief ignoring its own revocation would keep trying to do
        await sh.sync_with(wa)
        # it must not have pulled wong's post -- content never leaves the
        # REVOCATIONS phase for a peer already known to be revoked
        assert home.feed() == []
        for s in (sw, sh):
            await s.stop()
    asyncio.run(scenario())
