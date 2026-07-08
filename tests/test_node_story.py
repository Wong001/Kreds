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
