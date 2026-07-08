import io
import subprocess

import imageio_ffmpeg
from fastapi.testclient import TestClient
from PIL import Image

from hearth.api import build_app
from hearth.node import HearthNode


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
