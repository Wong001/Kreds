import asyncio
import os

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


def test_blob_sync_survives_oversized_want_set(tmp_path):
    """Whole-branch review, Finding 1 (CRITICAL): the BLOBS phase used to
    collect EVERY blob a peer wants into one give dict, base64-encoded,
    sent as ONE JSON frame. Two ~7 MB blobs (base64 ~9.3 MB each,
    ~18.7 MB combined) exceed transport.MAX_FRAME (16 MB); before the
    fix, write_frame's "frame too large" would kill the session at the
    blob phase and, since store.missing_blobs() is stable, the SAME
    oversized frame would rebuild every round -- blob sync wedged
    permanently. This reproduces the exact ordinary-use scenario named
    in the finding (one big GIF + one photo posted while a peer is
    offline) with real sockets and real stores, and confirms sync
    converges across rounds instead of dying."""
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        befriend(wong, freja)
        sw, wa = await started(wong)
        sf, fa = await started(freja)
        for n in (wong, freja):
            n.ensure_enckey()
        await sw.sync_with(fa)                  # exchange enc keys first

        # Two ~7 MB "photos": raw bytes tagged with the animated-GIF magic
        # so transcode_photo's byte-identity passthrough (same trick as
        # test_posts_and_blobs_propagate_between_friends above) returns
        # them unmodified -- the stored blob size is exactly under our
        # control, which is the point of this test.
        big_a = b"GIF89a" + os.urandom(7 * 1024 * 1024)
        big_b = b"GIF89a" + os.urandom(7 * 1024 * 1024)
        wong.compose_post("two big photos", photos=[big_a, big_b])

        # First round: the BLOBS phase must not raise ("frame too large")
        # even though the full want-set can't fit in one frame.
        assert await sw.sync_with(fa) is True
        feed = freja.feed()
        assert [p["text"] for p in feed] == ["two big photos"]
        refs = feed[0]["blobs"]
        assert len(refs) == 2

        # A single round cannot have delivered both budgeted-under-
        # MAX_FRAME blobs -- confirms the give-budget actually trimmed
        # something, not merely that sync survived by coincidence.
        got_after_one_round = sum(1 for h in refs if freja.store.has_blob(h))
        assert got_after_one_round < 2

        # Further rounds drain the remainder: store.missing_blobs() keeps
        # re-requesting whatever didn't arrive, so this converges without
        # the session ever dying.
        for _ in range(5):
            if all(freja.store.has_blob(h) for h in refs):
                break
            assert await sw.sync_with(fa) is True
        assert all(freja.store.has_blob(h) for h in refs)
        assert freja.post_blob(feed[0]["msg_id"], refs[0]) == big_a
        assert freja.post_blob(feed[0]["msg_id"], refs[1]) == big_b
        await sw.stop()
        await sf.stop()

    asyncio.run(scenario())


def test_sync_fetch_accepts_blob_at_exact_cap(tmp_path):
    """Whole-branch review, Finding 1: the spec-mandated sync-fetch
    boundary test Task 1 skipped. Task 1 only covered Store.put_blob's
    own cap (tests/test_store.py::test_blob_cap_is_10mb) -- this covers
    the SEPARATE receive-side check in _session's BLOBS phase
    (hearth/sync.py, `if len(data) <= MAX_BLOB_BYTES and blob_hash(data)
    == h: store.put_blob(data)`). A blob of exactly MAX_BLOB_BYTES must
    still sync through cleanly. Built via put_blob + make_post directly
    (bypassing the photo gate, whose own PHOTO_CAP margin sits 64 bytes
    under MAX_BLOB_BYTES for encrypt_blob headroom) so the blob handed to
    the wire is exactly MAX_BLOB_BYTES, testing the sync boundary itself
    rather than the gate's margin around it."""
    from hearth.messages import MAX_BLOB_BYTES, make_post

    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        befriend(wong, freja)
        sw, wa = await started(wong)
        sf, fa = await started(freja)
        for n in (wong, freja):
            n.ensure_enckey()
        await sw.sync_with(fa)

        exact = os.urandom(MAX_BLOB_BYTES)
        exact_hash = wong.store.put_blob(exact)          # allowed: == cap

        wraps = {freja.device.device_pub: {
            "eph_pub": "00" * 32, "nonce": "00" * 12,
            "wrapped_key": "00" * 32}}
        msg = make_post(wong.device, "kreds", body_nonce="ab" * 12,
                        body_ct="deadbeef", wraps=wraps,
                        blob_refs=[exact_hash])
        assert wong.store.ingest_message(msg).accepted

        assert await sw.sync_with(fa) is True
        assert freja.store.has_blob(exact_hash)
        assert freja.store.get_blob(exact_hash) == exact
        await sw.stop()
        await sf.stop()

    asyncio.run(scenario())


def test_sync_fetch_refuses_blob_over_cap(tmp_path):
    """Whole-branch review, Finding 1: the flip side of the boundary
    above. A compliant peer's own store can never hold an over-cap blob
    (Store.put_blob refuses it at write time), so the only way to reach
    the receive-side over-cap branch is a peer that hands over more than
    its own store would ever allow -- modeled here by inserting directly
    into the giving side's blobs table, bypassing put_blob's guard (a
    modified client, or a store carrying a blob from a hypothetically
    different cap, would look the same on the wire). The receiving side
    must refuse it silently: no exception, not stored, still tracked as
    wanted (not dropped from the sync contract)."""
    from hearth.messages import MAX_BLOB_BYTES, blob_hash, make_post

    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        befriend(wong, freja)
        sw, wa = await started(wong)
        sf, fa = await started(freja)
        for n in (wong, freja):
            n.ensure_enckey()
        # Exchanging enckey messages this round is what registers each
        # side's device_pub in the other's device_views (Verifier.
        # verify_message registers the signing device from the cert
        # riding on the message) -- needed so the wraps-gated post below
        # actually clears messages_not_in's entitlement filter, exactly
        # like the other cross-peer tests in this file.
        await sw.sync_with(fa)

        over = os.urandom(MAX_BLOB_BYTES + 1)
        over_hash = blob_hash(over)
        # Bypass put_blob's own guard: a compliant store can never reach
        # this state through the public API.
        wong.store._db.execute("INSERT OR IGNORE INTO blobs VALUES(?,?)",
                               (over_hash, over))
        wong.store._db.commit()

        wraps = {freja.device.device_pub: {
            "eph_pub": "00" * 32, "nonce": "00" * 12,
            "wrapped_key": "00" * 32}}
        msg = make_post(wong.device, "kreds", body_nonce="ab" * 12,
                        body_ct="deadbeef", wraps=wraps,
                        blob_refs=[over_hash])
        assert wong.store.ingest_message(msg).accepted

        assert await sw.sync_with(fa) is True
        assert not freja.store.has_blob(over_hash)
        assert over_hash in freja.store.missing_blobs()
        await sw.stop()
        await sf.stop()

    asyncio.run(scenario())
