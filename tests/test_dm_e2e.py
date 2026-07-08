"""Spec success criterion: Wong DMs Freja (text+photo) while her phone is
offline; it lands on freja-homenode; her phone returns and decrypts it; a
mutual observer never receives it; revoking Wong's phone self-logs-it-out."""
import asyncio, json
from pathlib import Path

from hearth.identity import DeviceKeys, DeviceView
from hearth.node import HearthNode
from hearth.sync import SyncService


def test_dm_e2e_story(tmp_path):
    asyncio.run(_story(tmp_path))


async def _pair_home(parent: HearthNode, path: Path, name: str) -> HearthNode:
    path.mkdir()
    dev = DeviceKeys.create(name)
    dev.install(parent.device.enroll_other(dev.device_pub, dev.name),
                parent.device.to_json()["identity_priv"])
    (path / "keys.json").write_text(json.dumps(dev.to_json()))
    home = HearthNode(path)
    home.store.add_identity(home.identity_pub, is_self=True)
    home.store.save_views(home.identity_pub,
                          {dev.device_pub: DeviceView(cert=dev.cert)})
    return home


async def _story(tmp_path):
    wong = HearthNode.create(tmp_path / "wp", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "fp", "Freja", "freja-phone")
    mads = HearthNode.create(tmp_path / "mp", "Mads", "mads-phone")
    for a, b in ((wong, freja), (wong, mads), (freja, mads)):
        a.store.add_identity(b.identity_pub)
        b.store.add_identity(a.identity_pub)
    freja_home = await _pair_home(freja, tmp_path / "fh", "freja-homenode")
    freja_home.store.add_identity(wong.identity_pub)

    svcs = {}
    async def up(node):
        s = SyncService(node); p = await s.start("127.0.0.1", 0)
        node.store.set_meta("gossip_addr", f"127.0.0.1:{p}")
        node.ensure_enckey()
        svcs[node] = (s, f"127.0.0.1:{p}")
        return svcs[node][1]
    wa = await up(wong); fa = await up(freja)
    ma = await up(mads); ha = await up(freja_home)

    # Everyone exchanges enckeys (and Freja's home learns Wong).
    for _ in range(2):
        await svcs[wong][0].sync_with(fa); await svcs[wong][0].sync_with(ma)
        await svcs[freja][0].sync_with(ma); await svcs[freja][0].sync_with(ha)
        await svcs[freja_home][0].sync_with(wa)

    # Freja's phone goes offline. Wong DMs her (text + photo).
    await svcs[freja][0].stop()
    photo = b"\x89PNG-aftensmad"
    mid = wong.compose_dm(freja.identity_pub, "kommer du til middag?", [photo])
    await svcs[wong][0].sync_with(ha)             # lands on Freja's home node
    assert freja_home.store.get_message(mid) is not None
    # Mads (mutual friend) is synced with but NEVER gets the DM.
    await svcs[wong][0].sync_with(ma)
    assert mads.store.get_message(mid) is None

    # Freja's phone returns, picks up from home, decrypts text + photo.
    s2 = SyncService(freja); p2 = await s2.start("127.0.0.1", 0)
    freja.store.set_meta("gossip_addr", f"127.0.0.1:{p2}")
    await s2.sync_with(ha)
    thread = freja.dm_thread(wong.identity_pub)
    assert [t["text"] for t in thread] == ["kommer du til middag?"]
    assert freja.dm_blob(thread[0]["msg_id"], thread[0]["blobs"][0]) == photo

    # Revoke Wong's phone from... Wong has no home node here; use a second
    # Wong device to revoke. Simplest: revoke via freja? No - must be same
    # identity. Pair a Wong home node and revoke from it.
    wong_home = await _pair_home(wong, tmp_path / "wh", "wong-homenode")
    wh_s = SyncService(wong_home); whp = await wh_s.start("127.0.0.1", 0)
    wong_home.store.set_meta("gossip_addr", f"127.0.0.1:{whp}")
    wong_home.revoke_device(wong.device.device_pub)
    assert await wh_s.sync_with(wa)               # phone hears its revocation
    assert wong.revoked is True and wong.feed() == []

    for s, _ in list(svcs.values()) + [(s2, ""), (wh_s, "")]:
        await s.stop()
