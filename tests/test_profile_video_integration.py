"""Two-node integration test for video blocks (Slice 3c), proven over real
sync sockets. Mirrors tests/test_profile_grids_integration.py and
tests/test_scoped_posts_e2e.py: real SyncService, started() helper,
store-level befriend(). clip() is copied from tests/test_node_story.py
(synthetic ffmpeg-generated mp4 via imageio_ffmpeg's bundled binary).

tests/test_profile_video.py already proves the transcode-gate + per-recipient
encryption at the single-node/in-process level (test_compose_video_block,
test_video_scope_encrypted); this file proves the same guarantees survive a
real sync round (encrypt -> wire -> decrypt) rather than in-process
store.ingest_message calls: an in-audience friend's profile_view shows the
video block and can post_blob-decrypt both the mp4 and the poster after a
real gossip sync, and -- mirroring
test_scoped_posts_e2e.py::test_inner_post_never_reaches_non_inner_over_sync
-- an Inner-scoped video block never reaches (and so can't appear on the
wall of) a friend who stays Kreds-only.

The whole scenario is wrapped in asyncio.wait_for with a generous timeout:
this repo has a documented history of async tests that hung and were
falsely reported green (see tests/test_unfriend_delivery.py), so an explicit
terminating timeout is required, not optional. Transcoding two short clips
(ffmpeg subprocess calls) is the slow part; 60s is generous headroom for
that on a slow CI box."""
import asyncio
import os
import subprocess
import tempfile

import imageio_ffmpeg

from hearth.node import HearthNode
from hearth.sync import SyncService

SCENARIO_TIMEOUT = 60


def clip(seconds=1):
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    p = os.path.join(tempfile.mkdtemp(), "c.mp4")
    subprocess.run([ff, "-f", "lavfi", "-i",
        f"testsrc=size=480x360:rate=24:duration={seconds}",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", p],
        check=True, capture_output=True)
    return open(p, "rb").read()


def befriend(a, b):
    a.store.add_identity(b.identity_pub)
    b.store.add_identity(a.identity_pub)


async def started(node):
    svc = SyncService(node)
    port = await svc.start("127.0.0.1", 0)
    node.store.set_meta("gossip_addr", f"127.0.0.1:{port}")
    return svc, f"127.0.0.1:{port}"


def test_friend_sees_and_decrypts_video_block_over_sync_and_outsider_cannot(tmp_path):
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Anna", "anna-phone")
        b = HearthNode.create(tmp_path / "b", "Bo", "bo-phone")
        c = HearthNode.create(tmp_path / "c", "Cleo", "cleo-phone")
        befriend(a, b)                              # b: in-audience friend
        befriend(a, c)                              # c: stays kreds-only (outsider for inner)
        for n in (a, b, c):
            n.ensure_enckey()
        sa, aa = await started(a)
        sb, ba = await started(b)
        sc, ca = await started(c)
        await sa.sync_with(ba)                      # exchange enckeys, a<->b
        await sa.sync_with(ca)                      # exchange enckeys, a<->c

        # --- in-audience case: kreds-scoped video block reaches b ---------
        vid = a.compose_post("clip", scope="kreds", placement="profile",
                             video=clip(1))
        await sa.sync_with(ba)
        await sa.sync_with(ca)

        view_b = b.profile_view(a.identity_pub)
        wall_b = {p["msg_id"]: p for p in view_b["wall"]}
        assert vid in wall_b
        row = wall_b[vid]
        assert row["media"] == "video"
        assert len(row["blobs"]) == 1 and row["poster"]

        mp4 = b.post_blob(vid, row["blobs"][0])
        assert mp4                                              # b decrypts the mp4
        assert b.store.get_blob(row["blobs"][0]) != mp4          # stored bytes are ciphertext
        poster = b.post_blob(vid, row["poster"])
        assert poster                                           # b decrypts the poster too

        # --- out-of-audience case: inner-scoped video never reaches c -----
        a.set_ring(b.identity_pub, "inner")          # b promoted; c stays kreds-only
        secret_vid = a.compose_post("secret clip", scope="inner",
                                    placement="profile", video=clip(1))
        await sa.sync_with(ba)
        await sa.sync_with(ca)

        secret_row = next(p for p in a.profile_view(a.identity_pub)["wall"]
                          if p["msg_id"] == secret_vid)
        view_c = c.profile_view(a.identity_pub)
        wall_c_ids = [p["msg_id"] for p in view_c["wall"]]
        assert secret_vid not in wall_c_ids           # c can't decrypt -> absent from wall
        # c never even received the row over sync (inner scope, c is kreds-only)
        assert c.store.get_message(secret_vid) is None
        assert c.post_blob(secret_vid, secret_row["blobs"][0]) is None

        # b (now inner) still sees and decrypts the original kreds video
        view_b2 = b.profile_view(a.identity_pub)
        assert vid in [p["msg_id"] for p in view_b2["wall"]]

        for s in (sa, sb, sc):
            await s.stop()
    asyncio.run(asyncio.wait_for(scenario(), timeout=SCENARIO_TIMEOUT))
