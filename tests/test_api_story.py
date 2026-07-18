import io
import subprocess

import imageio_ffmpeg
from fastapi.testclient import TestClient
from PIL import Image

from hearth.api import build_app
from hearth.node import HearthNode
from hearth.videogate import probe_duration


def png():
    buf = io.BytesIO()
    Image.new("RGB", (400, 400), (30, 80, 180)).save(buf, format="PNG")
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


def client(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    return TestClient(build_app(node)), node


def test_post_photo_story_and_list(tmp_path):
    c, node = client(tmp_path)
    r = c.post("/api/story", data={"caption": "hej"},
               files=[("media", ("p.png", png(), "image/png"))])
    assert r.status_code == 200
    stories = c.get("/api/stories").json()
    assert stories[0]["mine"] is True
    item = stories[0]["items"][0]
    assert item["media_kind"] == "photo"
    assert c.get("/api/blob/" + item["media"]).status_code == 200


def test_post_video_story(tmp_path):
    c, node = client(tmp_path)
    r = c.post("/api/story", data={"caption": ""},
               files=[("media", ("v.mp4", clip(2), "video/mp4"))])
    assert r.status_code == 200
    item = c.get("/api/stories").json()[0]["items"][0]
    assert item["media_kind"] == "video" and item["poster"]
    assert c.get("/api/blob/" + item["media"]).status_code == 200
    assert c.get("/api/blob/" + item["poster"]).status_code == 200


def test_post_bad_media_400(tmp_path):
    c, _ = client(tmp_path)
    r = c.post("/api/story", data={"caption": ""},
               files=[("media", ("x.bin", b"not media", "application/octet-stream"))])
    assert r.status_code == 400


def test_story_video_edit_field_trims(tmp_path):
    c, node = client(tmp_path)
    r = c.post("/api/story", data={"caption": "",
                                   "video_edit":
                                   '{"start": 0, "duration": 4, "poster_t": 1}'},
               files=[("media", ("v.mp4", clip(20), "video/mp4"))])
    assert r.status_code == 200
    item = c.get("/api/stories").json()[0]["items"][0]
    mp4 = c.get("/api/blob/" + item["media"]).content
    assert 3.0 < probe_duration(mp4) < 5.0


def test_story_bad_video_edit_400(tmp_path):
    c, _ = client(tmp_path)
    r = c.post("/api/story", data={"caption": "", "video_edit": "{not json"},
               files=[("media", ("v.mp4", clip(2), "video/mp4"))])
    assert r.status_code == 400
    r = c.post("/api/story",
               data={"caption": "",
                     "video_edit": '{"start": 0, "duration": 99}'},
               files=[("media", ("v.mp4", clip(2), "video/mp4"))])
    assert r.status_code == 400


def test_story_video_uses_video_cap_not_image_cap(tmp_path):
    # 50MB < payload < 100MB video: was 413 under the image cap, now ok
    # to *enter* the gate (the gate itself will reject junk - use a
    # padded real clip so only the CAP is under test).
    c, _ = client(tmp_path)
    real = clip(2)
    padded = real + b"\x00" * (60 * 1024 * 1024 - len(real))
    r = c.post("/api/story", data={"caption": ""},
               files=[("media", ("v.mp4", padded, "video/mp4"))])
    assert r.status_code != 413          # cap no longer trips at 60MB
    over = real + b"\x00" * (101 * 1024 * 1024 - len(real))
    r = c.post("/api/story", data={"caption": ""},
               files=[("media", ("v.mp4", over, "video/mp4"))])
    assert r.status_code == 413
    # images keep the 50 MB cap
    big_png = png() + b"\x00" * (60 * 1024 * 1024)
    r = c.post("/api/story", data={"caption": ""},
               files=[("media", ("p.png", big_png, "image/png"))])
    assert r.status_code == 413


def test_post_video_edit_field_trims_wall_video(tmp_path):
    c, node = client(tmp_path)
    r = c.post("/api/post",
               data={"text": "", "scope": "kreds", "placement": "profile",
                     "video_edit": '{"start": 1, "duration": 3, "poster_t": 0}'},
               files=[("video", ("v.mp4", clip(20), "video/mp4"))])
    assert r.status_code == 200
    mid = r.json()["msg_id"]
    row = [p for p in node.posts_by(node.identity_pub, placement="profile")
           if p["msg_id"] == mid][0]
    assert row["codec"] == "h264"
    mp4 = c.get(f"/api/post-blob/{mid}/{row['blobs'][0]}").content
    assert 2.0 < probe_duration(mp4) < 4.0


def test_post_video_edit_without_video_400(tmp_path):
    c, _ = client(tmp_path)
    r = c.post("/api/post",
               data={"text": "hi", "scope": "kreds",
                     "video_edit": '{"start": 0, "duration": 3}'})
    assert r.status_code == 400
