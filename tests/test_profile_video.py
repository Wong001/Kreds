"""Video blocks: placement="profile" posts carrying a transcoded, encrypted
video (mp4 + poster). Reuses hearth/videogate.py's transcode_video (the
Story gate: 15s/720p/5MB) but -- unlike Stories, which store plaintext --
the mp4 and poster are encrypted with the post's content key, same as photo
blobs (hearth/dmcrypt.py encrypt_blob), so only the post's audience (scope
wraps) can decrypt them.

clip()/png() are copied from tests/test_node_story.py (same synthetic-video
helper via imageio_ffmpeg's bundled ffmpeg + a plain PNG helper)."""
import io
import os
import subprocess
import tempfile

import imageio_ffmpeg
import pytest
from PIL import Image
from fastapi.testclient import TestClient

from hearth.api import build_app
from hearth.node import HearthNode


def png(w=300, h=300):
    buf = io.BytesIO()
    Image.new("RGB", (w, h), (30, 80, 180)).save(buf, format="PNG")
    return buf.getvalue()


def clip(seconds=2):
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    p = os.path.join(tempfile.mkdtemp(), "c.mp4")
    subprocess.run([ff, "-f", "lavfi", "-i",
        f"testsrc=size=480x360:rate=24:duration={seconds}",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", p],
        check=True, capture_output=True)
    return open(p, "rb").read()


def gif():
    buf = io.BytesIO()
    Image.new("P", (60, 60)).save(buf, format="GIF")
    return buf.getvalue()


def befriend_with_enckeys(a, b):
    # copied idiom (tests/test_node_scoped_posts.py, tests/test_profile_posts.py):
    # mutual friends with enc keys exchanged so posts can be wrapped/unwrapped
    # in-process without a real sync round.
    a.store.add_identity(b.identity_pub); b.store.add_identity(a.identity_pub)
    a.ensure_enckey(); b.ensure_enckey()
    for src, dst in ((a, b), (b, a)):
        for m in src.store.messages_not_in({}, {src.identity_pub}, dst.identity_pub):
            dst.store.ingest_message(m)


def test_compose_video_block(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    mid = n.compose_post("clip", scope="kreds", placement="profile", video=clip(1))
    row = next(p for p in n.profile_view(n.identity_pub)["wall"] if p["msg_id"] == mid)
    assert row["media"] == "video"
    assert len(row["blobs"]) == 1 and row["poster"]
    # mp4 + poster decrypt via post_blob; stored blob is ciphertext (encrypted)
    mp4 = n.post_blob(mid, row["blobs"][0]); assert mp4 and mp4[:12] != b""  # decrypts
    assert n.store.get_blob(row["blobs"][0]) != mp4                          # stored != plaintext
    assert n.post_blob(mid, row["poster"])                                  # poster decrypts


def test_video_scope_encrypted(tmp_path):
    # a and b are friends with b promoted to "inner"; m is a friend who stays
    # kreds-only -- an out-of-audience friend for an inner-scoped post. m
    # ends up holding the ciphertext (as any friend/relay could, via sync)
    # but must not be able to decrypt the video or poster blobs.
    a = HearthNode.create(tmp_path / "a", "Wong", "wong-phone")
    b = HearthNode.create(tmp_path / "b", "Freja", "freja-phone")
    m = HearthNode.create(tmp_path / "m", "Mikkel", "mikkel-phone")
    befriend_with_enckeys(a, b); befriend_with_enckeys(a, m)
    a.set_ring(b.identity_pub, "inner")             # b inner; m stays kreds-only (outsider)
    mid = a.compose_post("clip", scope="inner", placement="profile", video=clip(1))
    row = next(p for p in a.profile_view(a.identity_pub)["wall"] if p["msg_id"] == mid)
    msg = a.store.get_message(mid)
    m.store.ingest_message(msg)                     # outsider holds ciphertext only
    assert m.post_blob(mid, row["blobs"][0]) is None
    assert m.post_blob(mid, row["poster"]) is None


def test_compose_video_rejects_too_long(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    with pytest.raises(ValueError): n.compose_post("x", placement="profile", video=clip(20))


def test_video_payload_validation():
    from hearth.messages import validate_payload, KIND_POST
    base = {"kind": KIND_POST, "scope": "kreds", "created_at": 1.0,
            "body_nonce": "0"*24, "body_ct": "ab", "wraps": {}, "placement": "profile"}
    ok,_ = validate_payload({**base, "blobs": ["a"*64], "media": "video", "poster": "b"*64}); assert ok
    ok,_ = validate_payload({**base, "blobs": [], "media": "video", "poster": "b"*64}); assert not ok
    ok,_ = validate_payload({**base, "blobs": ["a"*64], "media": "video"}); assert not ok  # no poster
    ok,_ = validate_payload({**base, "blobs": ["a"*64,"c"*64], "media": "video", "poster": "b"*64}); assert not ok
    ok,_ = validate_payload({**base, "blobs": ["a"*64], "media": "photo", "poster": "b"*64}); assert not ok
    ok,_ = validate_payload({**base, "blobs": ["a"*64]}); assert ok           # missing media = photo


def test_api_post_video(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    c = TestClient(build_app(node))
    r = c.post("/api/post", data={"scope":"kreds","placement":"profile"},
               files=[("video", ("v.mp4", clip(1), "video/mp4"))])
    assert r.status_code == 200
    mid = r.json()["msg_id"]
    assert next(p for p in node.profile_view(node.identity_pub)["wall"]
                if p["msg_id"] == mid)["media"] == "video"
    # non-video -> 400
    r2 = c.post("/api/post", data={"scope":"kreds","placement":"profile"},
                files=[("video", ("x.txt", b"not a video", "video/mp4"))])
    assert r2.status_code == 400


def test_api_post_video_exceeds_cap_413(tmp_path, monkeypatch):
    import hearth.api as api_mod
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    c = TestClient(build_app(node))
    monkeypatch.setattr(api_mod, "MAX_VIDEO_UPLOAD", 10)   # tiny cap for the test
    r = c.post("/api/post", data={"scope":"kreds","placement":"profile"},
               files=[("video", ("v.mp4", clip(1), "video/mp4"))])
    assert r.status_code == 413


def test_video_edit_trims_wall_post_and_stamps_codec(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    mid = n.compose_post("cut", scope="kreds", placement="profile",
                         video=clip(30),
                         video_edit={"start": 2, "duration": 8, "poster_t": 1})
    row = next(p for p in n.posts_by(n.identity_pub, placement="profile")
               if p["msg_id"] == mid)
    assert row["media"] == "video"
    assert row["codec"] == "h264"
    # the stored blob is the CUT artifact: decrypt via post_blob (same
    # content-key path profile_view/feed rows use) and probe its duration
    from hearth.videogate import probe_duration
    mp4 = n.post_blob(mid, row["blobs"][0])
    assert 7.0 < probe_duration(mp4) < 9.0


def test_photo_post_has_no_codec(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    mid = n.compose_post("plain text", scope="kreds")
    row = next(p for p in n.feed() if p["msg_id"] == mid)
    assert row["codec"] is None


def test_referenced_blobs_tolerates_junk_poster(tmp_path):
    """A modified friend client could persist a KIND_DM carrying a junk
    (non-str) `poster` (DM payloads aren't poster-validated on ingest).
    referenced_blobs()/missing_blobs()/gc_blobs() must not brick on it."""
    import json
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    st = n.store
    bad = json.dumps({"payload": {"kind": "dm", "poster": {"x": 1}, "blobs": []}})
    with st._lock:
        st._db.execute(
            "INSERT INTO messages VALUES(?,?,?,?,?,?,?,?,?,?)",
            ("m" * 64, "a" * 64, "b" * 64, 1, "dm", None, "c" * 64, bad, 1.0, None))
        st._db.commit()
    # None of these may raise (before the fix, the dict poster bricked them):
    assert isinstance(st.referenced_blobs(), set)
    st.missing_blobs()
    st.gc_blobs()


def test_referenced_blobs_tolerates_junk_thumbs(tmp_path):
    """A modified friend client could persist a KIND_DM carrying a junk,
    truthy, NON-LIST `thumbs` (DM payloads aren't thumb-validated on
    ingest -- validate_payload's `dm` branch never looks at `thumbs`, so
    this signs and ingests cleanly). referenced_blobs()/missing_blobs()
    must not brick on it (mirrors test_referenced_blobs_tolerates_junk_
    poster above, for the thumbs guard)."""
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    msg = node.device.sign_message({
        "kind": "dm", "to": "cc" * 32, "body_nonce": "ab" * 12,
        "body_ct": "de", "wraps": {}, "blobs": [], "created_at": 1.0,
        "expires_at": None, "thumbs": 1,
    })
    result = node.store.ingest_message(msg)
    assert result.accepted, result.reason
    # None of these may raise (before the fix, "for t in (p.get('thumbs')
    # or [])" TypeErrors on the truthy int 1 -- "or []" only substitutes
    # on a FALSY value):
    refs = node.store.referenced_blobs()
    assert isinstance(refs, set)
    assert 1 not in refs
    node.store.missing_blobs()


def test_post_thumbs_aligned_and_served(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    mid = node.compose_post("pics", "kreds", photos=[png(), png()])
    row = [p for p in node.feed() if p["msg_id"] == mid][0]
    assert len(row["thumbs"]) == len(row["blobs"]) == 2
    assert all(t for t in row["thumbs"])
    th = node.post_blob(mid, row["thumbs"][0])       # same content key
    assert Image.open(io.BytesIO(th)).format == "AVIF"
    assert max(Image.open(io.BytesIO(th)).size) <= 640
    # a hash from ANOTHER post cannot be fetched through this post's id:
    # the per-post content key fails AEAD auth (the crypto IS the guard)
    other = node.compose_post("x", "kreds", photos=[png()])
    orow = [p for p in node.feed() if p["msg_id"] == other][0]
    assert node.post_blob(mid, orow["blobs"][0]) is None


def test_gif_post_gets_null_thumb(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    mid = node.compose_post("gif", "kreds", photos=[gif()])
    row = [p for p in node.feed() if p["msg_id"] == mid][0]
    assert row["thumbs"] == [None]


def test_video_post_thumb_of_poster(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    mid = node.compose_post("v", "kreds", video=clip(2))
    row = [p for p in node.feed() if p["msg_id"] == mid][0]
    assert len(row["thumbs"]) == 1 and row["thumbs"][0]
    th = node.post_blob(mid, row["thumbs"][0])
    assert Image.open(io.BytesIO(th)).format == "AVIF"


def test_referenced_blobs_includes_thumbs(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    mid = node.compose_post("pics", "kreds", photos=[png()])
    payload = node.store.get_message(mid).payload
    refs = node.store.referenced_blobs()
    assert payload["thumbs"][0] in refs
    # delete drops thumbs with the post (gc)
    node.delete_post(mid)
    node.store.gc_blobs()
    assert node.store.get_blob(payload["thumbs"][0]) is None


def test_story_still_and_video_poster_are_avif(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    node.compose_story(png(), "")
    item = [i for g in node.stories_view() for i in g["items"]][0]
    assert Image.open(io.BytesIO(
        node.store.get_blob(item["media"]))).format == "AVIF"
