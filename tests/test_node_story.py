import io

import imageio_ffmpeg
import pytest
import subprocess
from PIL import Image

from hearth.node import HearthNode


def png(w=300, h=300):
    buf = io.BytesIO()
    Image.new("RGB", (w, h), (30, 80, 180)).save(buf, format="PNG")
    return buf.getvalue()


def clip(seconds=2):
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    import tempfile, os
    p = os.path.join(tempfile.mkdtemp(), "c.mp4")
    subprocess.run([ff, "-f", "lavfi", "-i",
                    f"testsrc=size=480x360:rate=24:duration={seconds}",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", p],
                   check=True, capture_output=True)
    return open(p, "rb").read()


def test_compose_photo_story(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    mid = n.compose_story(png(), caption="hej")
    view = n.stories_view()
    assert view[0]["mine"] is True
    item = view[0]["items"][0]
    assert item["media_kind"] == "photo" and item["poster"] is None
    assert n.store.get_blob(item["media"]) is not None


def test_compose_video_story(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    n.compose_story(clip(2))
    item = n.stories_view()[0]["items"][0]
    assert item["media_kind"] == "video" and item["poster"] is not None
    # both media and poster blobs stored
    assert n.store.get_blob(item["media"]) is not None
    assert n.store.get_blob(item["poster"]) is not None


def test_compose_bad_media_rejected(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    with pytest.raises(ValueError):
        n.compose_story(b"neither image nor video")


def test_over_long_caption_rejected(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    with pytest.raises(ValueError):
        n.compose_story(png(), caption="x" * 201)


def test_story_video_edit_trims_and_stamps_codec(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    mid = n.compose_story(clip(25), "",
                          video_edit={"start": 0, "duration": 6, "poster_t": 2})
    from hearth.videogate import probe_duration
    item = [i for g in n.stories_view() for i in g["items"]
            if i["msg_id"] == mid][0]
    mp4 = n.store.get_blob(item["media"])
    assert 5.0 < probe_duration(mp4) < 7.0
    assert n.store.get_message(mid).payload["codec"] == "h264"


def test_story_image_with_video_edit_rejected(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    with pytest.raises(ValueError):
        n.compose_story(png(), "", video_edit={"start": 0, "duration": 5})


def _sync(a, b):
    """Hand-carry every message `a` holds (authored by `a`) that `b`
    hasn't ingested yet - one direction of a gossip round without real
    sockets (idiom shared with tests/test_responses.py's _sync)."""
    for m in a.store.messages_not_in({}, {a.identity_pub}, b.identity_pub):
        b.store.ingest_message(m)


def _befriend(a, b):
    a.store.add_identity(b.identity_pub)
    b.store.add_identity(a.identity_pub)
    a.ensure_enckey()
    b.ensure_enckey()
    _sync(a, b)          # b learns a's enckey
    _sync(b, a)          # a learns b's enckey


def _befriended_pair(tmp_path):
    a = HearthNode.create(tmp_path / "a", "A", "a-dev")
    b = HearthNode.create(tmp_path / "b", "B", "b-dev")
    _befriend(a, b)
    return a, b


def test_dm_story_ref_round_trip(tmp_path):
    # Task 7: a story reaction/reply is a plain DM to the story owner,
    # carrying an additive story_ref - a whole-node round trip (compose
    # story -> sync -> react via DM -> sync back -> read the thread).
    a, b = _befriended_pair(tmp_path)
    sid = a.compose_story(png(), "min story")
    _sync(a, b)                     # b learns a's story
    item = [i for g in b.stories_view() for i in g["items"]
            if i["msg_id"] == sid][0]
    did = b.compose_dm(a.identity_pub, "\U0001F525", story_ref={
        "story_id": sid, "media_hash": item["media"]})
    _sync(b, a)                     # a learns b's reaction DM
    thread = a.dm_thread(b.identity_pub)
    assert thread[-1]["msg_id"] == did
    assert thread[-1]["text"] == "\U0001F525"
    assert thread[-1]["story_ref"] == {"story_id": sid,
                                       "media_hash": item["media"]}


def test_dm_without_story_ref_carries_none(tmp_path):
    # Additive field: an ordinary DM (no reaction/reply context) must not
    # gain a spurious story_ref - dm_thread rows carry None, not a missing
    # key or an empty dict the client would have to special-case.
    a, b = _befriended_pair(tmp_path)
    a.compose_dm(b.identity_pub, "hej")
    _sync(a, b)
    assert b.dm_thread(a.identity_pub)[-1]["story_ref"] is None


def test_stories_view_includes_friend_and_self_first(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    wong.store.add_identity(freja.identity_pub)
    freja.set_profile("Freja")
    freja.compose_story(png())
    # carry freja's story message + blobs to wong
    for m in freja.store.messages_not_in({}, {freja.identity_pub},
                                         wong.identity_pub):
        wong.store.ingest_message(m)
    for h in list(wong.store.missing_blobs()):
        wong.store.put_blob(freja.store.get_blob(h))
    wong.compose_story(png())
    view = wong.stories_view()
    assert view[0]["mine"] is True                 # self first
    assert any(g["identity_pub"] == freja.identity_pub and not g["mine"]
               for g in view)
    freja_group = [g for g in view
                   if g["identity_pub"] == freja.identity_pub][0]
    assert freja_group["name"] == "Freja"
