"""The spec's success criterion as one test: three devices, friendship,
photo propagation, offline catch-up via the home node, deletion, and
revocation with cross-network retro-drop."""
import asyncio

from hearth.node import HearthNode
from hearth.sync import SyncService
from tests.test_imagegate import animated_gif_bytes


def test_three_node_story(tmp_path):
    asyncio.run(_story(tmp_path))


async def _story(tmp_path):
    # Cast: wong-phone creates the identity, pairs wong-homenode,
    # friend-ceremonies freja-phone.
    wong = HearthNode.create(tmp_path / "wp", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "fp", "Freja", "freja-phone")

    sw = SyncService(wong)
    sf = SyncService(freja)
    wa = f"127.0.0.1:{await sw.start('127.0.0.1', 0)}"
    fa = f"127.0.0.1:{await sf.start('127.0.0.1', 0)}"
    wong.store.set_meta("gossip_addr", wa)
    freja.store.set_meta("gossip_addr", fa)

    req = HearthNode.pair_request(tmp_path / "wh", "wong-homenode")
    home = HearthNode.pair_install(tmp_path / "wh", wong.accept_pairing(req))
    sh = SyncService(home)
    ha = f"127.0.0.1:{await sh.start('127.0.0.1', 0)}"
    home.store.set_meta("gossip_addr", ha)
    home.store.add_peer(wa, wong.identity_pub)

    final = wong.finalize_invite(
        freja.respond_to_invite(wong.create_invite()))
    freja.complete_invite(final)

    # Exchange enc keys (wong <-> freja, then wong <-> home) so every scoped
    # post below is already wrapped for the right devices when composed.
    for n in (wong, freja, home):
        n.ensure_enckey()
    await sw.sync_with(fa)
    await sh.sync_with(wa)

    # 1. Wong posts a photo; it reaches Freja over real sockets.
    photo = animated_gif_bytes()            # byte-identity is the point below
    p1 = wong.compose_post("aftensol over kanalen", photos=[photo])
    assert await sw.sync_with(fa)
    feed = freja.feed()
    assert feed[0]["text"] == "aftensol over kanalen"
    assert freja.store.get_blob(feed[0]["blobs"][0]) != photo  # ciphertext
    assert freja.post_blob(feed[0]["msg_id"], feed[0]["blobs"][0]) == photo

    # 2. Home node catches up from the phone (own-device replication),
    #    learning the friend list and Freja's address along the way.
    assert await sh.sync_with(wa)
    assert freja.identity_pub in home.store.known_identities()
    assert len(home.feed()) == len(wong.feed())

    # 3. Offline catch-up: phone goes dark; a post authored on the phone
    #    earlier still reaches Freja VIA THE HOME NODE.
    p2 = wong.compose_post("inden telefonen doede")
    assert await sh.sync_with(wa)                 # home has p2
    await sw.stop()                               # phone offline
    assert await sf.sync_with(ha)                 # freja <- home node
    assert {p["text"] for p in freja.feed()} == {
        "aftensol over kanalen", "inden telefonen doede"}

    # 4. Deletion propagates through the mesh (home relays the tag).
    #    Wong deletes p1 on the phone; phone comes back online.
    wa = f"127.0.0.1:{await sw.start('127.0.0.1', 0)}"
    wong.store.set_meta("gossip_addr", wa)
    wong.delete_post(p1)
    assert await sw.sync_with(fa)
    assert [p["text"] for p in freja.feed()] == ["inden telefonen doede"]
    assert freja.store.is_tombstoned(p1)

    # 5. Revocation + retro-drop: the phone is stolen; loot reaches Freja
    #    in the gossip-lag window; Wong revokes FROM THE HOME NODE; the
    #    revocation propagates and Freja retro-drops.
    from hearth.messages import make_post
    loot = make_post(wong.device, "kreds", body_nonce="ab" * 12,
                     body_ct="deadbeef", wraps={})
    assert freja.store.ingest_message(loot).accepted   # window exposure
    home.revoke_device(wong.device.device_pub)
    assert await sh.sync_with(fa)
    assert [p["text"] for p in freja.feed()] == ["inden telefonen doede"]
    assert freja.store.is_tombstoned(loot.msg_id)

    await sw.stop()
    await sh.stop()
    await sf.stop()
