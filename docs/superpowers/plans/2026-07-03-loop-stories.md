# Loop Stories Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 24h-ephemeral photo + short-muted-video Stories on Loop, with a video transcode gate (imageio-ffmpeg), a stories strip, and a full-screen viewer.

**Architecture:** A Story item is a new signed message kind (`KIND_STORY`) with a 24h `expires_at`, riding the existing expiry/tombstone sweep, blob store, and gossip. Media passes a server-side gate before storage — photos through the existing Pillow image gate (at 1080px), videos through a new ffmpeg re-encode gate (reject >15s, ≤720p, strip audio, H.264/MP4, extract poster). Spec: `docs/superpowers/specs/2026-07-03-loop-stories-design.md`.

**Tech Stack:** Existing Loop stack (Python 3.12, FastAPI, sqlite3, vanilla JS, Pillow) + `imageio-ffmpeg` (bundled ffmpeg binary, verified: ffmpeg 7.1 runs; full encode/strip-audio/poster pipeline proven in this environment).

## Global Constraints

- Run as `.\.venv\Scripts\python.exe ...` from `C:\Users\Wong\Desktop\Hearth`, branch `hearth-vertical-slice`.
- New dependency: `imageio-ffmpeg` (already installed in the venv during planning; add to requirements.txt). ffmpeg exe via `imageio_ffmpeg.get_ffmpeg_exe()`. NO ffprobe (not bundled) — probe duration by parsing `ffmpeg -i` stderr.
- ASCII only in console prints (cp1252).
- Video gate: reject duration > 15.0s (`ValueError`, no auto-trim); scale to ≤720p keeping aspect (even dims); strip audio (`-an`); re-encode H.264 MP4 (`-c:v libx264 -pix_fmt yuv420p -crf 28 -preset veryfast -movflags +faststart`); reject if output > `MAX_BLOB_BYTES` (5 MB); poster = first frame run through the image gate.
- Photo story media: existing `imagegate.transcode` at `STORY_IMAGE_MAX = 1080`.
- Story message kind `KIND_STORY`; payload `{kind, media_kind:"photo"|"video", media:<hash>, poster:<hash|null>, caption:str<=200, created_at, expires_at}`; `expires_at = created_at + 86400`.
- Server SNIFFS the uploaded bytes to pick photo-vs-video (never trusts a client field): image magic (PNG/JPEG/GIF/WebP/BMP) → photo; else → video.
- Story media + poster hashes MUST be registered in `referenced_blobs()` (else GC deletes them and they never gossip — the profiles lesson).
- NO view/seen metrics anywhere (thesis). "Seen" is client-side localStorage only.
- Full suite stays green; require ZERO failures at each task's full-suite step.
- Commit after every task, trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: Video transcode gate

**Files:**
- Create: `hearth/videogate.py`
- Modify: `requirements.txt`
- Test: `tests/test_videogate.py`

**Interfaces:**
- Consumes: `imageio_ffmpeg`, `hearth.imagegate.transcode`.
- Produces (in `hearth.videogate`):
  - `MAX_VIDEO_SECONDS = 15.0`, `VIDEO_MAX_DIM = 720`, `STORY_IMAGE_MAX = 1080`
  - `probe_duration(data: bytes) -> float` — seconds; raises `ValueError("not a video")` if no Duration line.
  - `transcode_video(data: bytes) -> tuple[bytes, bytes]` — returns `(mp4_bytes, poster_png_bytes)`; raises `ValueError` on: unparseable / duration > 15s / output > 5 MB. Poster is the first frame passed through `imagegate.transcode(frame_png, STORY_IMAGE_MAX)`.

- [ ] **Step 1: Write the failing tests**

`tests/test_videogate.py`:
```python
import subprocess

import imageio_ffmpeg
import pytest

from hearth.videogate import MAX_VIDEO_SECONDS, probe_duration, transcode_video


def _make_clip(seconds, w=640, h=480, with_audio=True):
    """Generate a test clip (with audio) as bytes, via the bundled ffmpeg."""
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    import tempfile, os
    path = os.path.join(tempfile.mkdtemp(), "src.mp4")
    args = [ff, "-f", "lavfi", "-i",
            f"testsrc=size={w}x{h}:rate=24:duration={seconds}"]
    if with_audio:
        args += ["-f", "lavfi", "-i", f"sine=frequency=440:duration={seconds}",
                 "-c:a", "aac"]
    args += ["-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", path]
    subprocess.run(args, check=True, capture_output=True)
    with open(path, "rb") as f:
        return f.read()


def _has_audio(mp4_bytes):
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    import tempfile, os
    p = os.path.join(tempfile.mkdtemp(), "x.mp4")
    with open(p, "wb") as f:
        f.write(mp4_bytes)
    return "Audio:" in subprocess.run([ff, "-i", p],
                                      capture_output=True, text=True).stderr


def test_probe_duration_reads_seconds():
    d = probe_duration(_make_clip(3))
    assert 2.5 < d < 3.5


def test_transcode_strips_audio_and_returns_poster():
    mp4, poster = transcode_video(_make_clip(3, with_audio=True))
    assert mp4[:4] and not _has_audio(mp4)          # audio gone
    from PIL import Image
    import io
    im = Image.open(io.BytesIO(poster))
    assert im.format == "PNG"                        # poster is a PNG
    assert max(im.size) <= 1080


def test_over_length_rejected():
    with pytest.raises(ValueError):
        transcode_video(_make_clip(int(MAX_VIDEO_SECONDS) + 3))


def test_non_video_rejected():
    with pytest.raises(ValueError):
        transcode_video(b"this is not a video at all")
    with pytest.raises(ValueError):
        probe_duration(b"nope")


def test_downscales_large_video():
    from PIL import Image
    import io
    mp4, poster = transcode_video(_make_clip(2, w=1920, h=1080))
    # poster reflects the re-encoded (<=720p tall) frame, capped again by image gate
    assert max(Image.open(io.BytesIO(poster)).size) <= 1080
    assert len(mp4) <= 5 * 1024 * 1024
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_videogate.py -q`
Expected: FAIL — `No module named 'hearth.videogate'`.

- [ ] **Step 3: Implement `hearth/videogate.py`**

```python
"""Video transcode gate: re-encode Story videos to known-good muted MP4.

Mirrors the image gate: decode the upload and re-encode via a bundled
ffmpeg so viewers only ever render bytes WE produced. Author-side gate
(a modified peer can still put raw bytes behind a hash, as post photos do,
served nosniff and never as HTML). Reject over-length/over-size instead of
trimming, for predictability. Audio is stripped in v1 (muted stories)."""
from __future__ import annotations

import os
import re
import subprocess
import tempfile

import imageio_ffmpeg

from .imagegate import transcode as image_transcode

MAX_VIDEO_SECONDS = 15.0
VIDEO_MAX_DIM = 720
STORY_IMAGE_MAX = 1080
_MAX_OUTPUT = 5 * 1024 * 1024

_DUR = re.compile(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)")


def _ff() -> str:
    return imageio_ffmpeg.get_ffmpeg_exe()


def _run(args):
    return subprocess.run([_ff(), *args], capture_output=True, text=False)


def probe_duration(data: bytes) -> float:
    with tempfile.TemporaryDirectory() as d:
        p = os.path.join(d, "in")
        with open(p, "wb") as f:
            f.write(data)
        stderr = subprocess.run([_ff(), "-i", p], capture_output=True,
                                text=True).stderr
    m = _DUR.search(stderr)
    if not m:
        raise ValueError("not a video")
    h, mm, ss = m.groups()
    return int(h) * 3600 + int(mm) * 60 + float(ss)


def transcode_video(data: bytes) -> tuple[bytes, bytes]:
    if probe_duration(data) > MAX_VIDEO_SECONDS:
        raise ValueError("video longer than 15 seconds")
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "in")
        out = os.path.join(d, "out.mp4")
        frame = os.path.join(d, "poster.png")
        with open(src, "wb") as f:
            f.write(data)
        # Downscale to <=720 tall keeping aspect (even dims), strip audio,
        # H.264 MP4. The comma inside min() must be escaped for ffmpeg's
        # filter parser (raw string), else it reads as a filter separator.
        vf = r"scale=w=-2:h=min(720\,ih)"
        enc = _run(["-i", src, "-vf", vf, "-an", "-c:v", "libx264",
                    "-pix_fmt", "yuv420p", "-crf", "28", "-preset",
                    "veryfast", "-movflags", "+faststart", "-y", out])
        if enc.returncode != 0 or not os.path.exists(out):
            raise ValueError("could not transcode video")
        mp4 = open(out, "rb").read()
        if len(mp4) > _MAX_OUTPUT:
            raise ValueError("transcoded video exceeds 5 MB")
        pf = _run(["-i", out, "-frames:v", "1", "-f", "image2", "-y", frame])
        if pf.returncode != 0 or not os.path.exists(frame):
            raise ValueError("could not extract poster")
        poster = image_transcode(open(frame, "rb").read(), STORY_IMAGE_MAX)
    return mp4, poster
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_videogate.py -q`
Expected: 5 passed (subprocess-driven, a few seconds).

- [ ] **Step 5: Add dependency + commit**

Append `imageio-ffmpeg` to `requirements.txt`.
```bash
git add hearth/videogate.py tests/test_videogate.py requirements.txt
git commit -m "feat: video transcode gate (imageio-ffmpeg) - reject>15s, strip audio, poster"
```

---

### Task 2: Story message kind

**Files:**
- Modify: `hearth/messages.py`
- Test: `tests/test_story_model.py`

**Interfaces:**
- Consumes: existing `_is_hex64`, `validate_payload` pattern.
- Produces (in `hearth.messages`):
  - `KIND_STORY = "story"`, `MAX_CAPTION = 200`, `STORY_TTL = 86400`
  - `make_story(device, media_kind, media, poster=None, caption="", now=None) -> SignedMessage` — payload `{kind:"story", media_kind, media, poster, caption, created_at, expires_at}` with `expires_at = created_at + STORY_TTL`.
  - `validate_payload` story branch: `media_kind` in {"photo","video"}; `media` 64-hex; `poster` None-or-64-hex; `caption` str <= MAX_CAPTION; `expires_at` numeric.

- [ ] **Step 1: Write the failing tests**

`tests/test_story_model.py`:
```python
from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import STORY_TTL, make_story, validate_payload


def device():
    d = DeviceKeys.create("phone")
    IdentityCeremony().enroll_first_device(d)
    return d


def test_photo_story_valid():
    d = device()
    m = make_story(d, "photo", "ab" * 32, caption="hej", now=100.0)
    assert m.payload["media_kind"] == "photo"
    assert m.payload["poster"] is None
    assert m.payload["expires_at"] == 100.0 + STORY_TTL
    assert validate_payload(m.payload) == (True, "ok")
    assert m.verify_device_signature()


def test_video_story_valid():
    d = device()
    m = make_story(d, "video", "ab" * 32, poster="cd" * 32, caption="", now=1.0)
    assert m.payload["poster"] == "cd" * 32
    assert validate_payload(m.payload) == (True, "ok")


def test_invalid_stories_rejected():
    ok = lambda p: validate_payload(p)[0]
    base = {"kind": "story", "media_kind": "photo", "media": "ab" * 32,
            "poster": None, "caption": "", "created_at": 1.0,
            "expires_at": 2.0}
    assert ok(base)
    assert not ok({**base, "media_kind": "gif"})
    assert not ok({**base, "media": "zz"})
    assert not ok({**base, "poster": "zz"})
    assert not ok({**base, "caption": "x" * 201})
    assert not ok({**base, "media": None})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_story_model.py -q`
Expected: FAIL — `make_story` / `STORY_TTL` import error.

- [ ] **Step 3: Modify `hearth/messages.py`**

Add constants near the other kinds:
```python
KIND_STORY = "story"
MAX_CAPTION = 200
STORY_TTL = 86400
```

Add the constructor (after `make_dm`):
```python
def make_story(device: DeviceKeys, media_kind: str, media: str,
               poster: Optional[str] = None, caption: str = "",
               now: Optional[float] = None) -> SignedMessage:
    created = _now(now)
    return device.sign_message({
        "kind": KIND_STORY, "media_kind": media_kind, "media": media,
        "poster": poster, "caption": caption, "created_at": created,
        "expires_at": created + STORY_TTL,
    })
```

Add the validation branch (before the final `return False, "unknown kind"`):
```python
    if kind == KIND_STORY:
        if p.get("media_kind") not in ("photo", "video"):
            return False, "bad media_kind"
        if not _is_hex64(p.get("media")):
            return False, "bad media"
        poster = p.get("poster")
        if poster is not None and not _is_hex64(poster):
            return False, "bad poster"
        cap = p.get("caption", "")
        if not isinstance(cap, str) or len(cap) > MAX_CAPTION:
            return False, "bad caption"
        exp = p.get("expires_at")
        if exp is not None and not isinstance(exp, (int, float)):
            return False, "bad expires_at"
        return True, "ok"
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_story_model.py tests/test_messages.py -q`
Expected: all pass.

- [ ] **Step 5: Full suite + commit**

Run: `.\.venv\Scripts\python.exe -m pytest tests -q`
Expected: zero failures.
```bash
git add hearth/messages.py tests/test_story_model.py
git commit -m "feat: story message kind with 24h expiry"
```

---

### Task 3: Store — story blob references + active-stories query

**Files:**
- Modify: `hearth/store.py`
- Test: `tests/test_store_story.py`

**Interfaces:**
- Consumes: `KIND_STORY` (Task 2).
- Produces (in `hearth.store`):
  - `referenced_blobs()` ALSO counts `KIND_STORY` `media` + `poster` hashes (so GC-safe + gossip-fetchable). Add a KIND_STORY loop alongside the existing KIND_POST/KIND_DM and KIND_PROFILE loops.
  - `active_stories(now=None) -> list[dict]` — non-expired story rows grouped by author identity, each `{identity_pub, items:[{msg_id, media_kind, media, poster, caption, created_at}]}`, items time-ASCending, groups by most-recent-item DESC. Import `KIND_STORY` in the messages-import line.

- [ ] **Step 1: Write the failing tests**

`tests/test_store_story.py`:
```python
import time

from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import make_story
from hearth.store import Store


def person(name):
    d = DeviceKeys.create(name)
    IdentityCeremony().enroll_first_device(d)
    return d


def store_with(tmp_path, *identities):
    s = Store(tmp_path / "s.db")
    for i, ident in enumerate(identities):
        s.add_identity(ident, is_self=(i == 0))
    return s


def test_story_blobs_referenced_and_gc_safe(tmp_path):
    from hearth.messages import make_post
    wong = person("wong-phone")
    s = store_with(tmp_path, wong.identity_pub)
    media = s.put_blob(b"video-bytes")
    poster = s.put_blob(b"poster-bytes")
    s.ingest_message(make_story(wong, "video", media, poster=poster))
    refs = s.referenced_blobs()
    assert media in refs and poster in refs
    s.ingest_message(make_post(wong, "unrelated"))
    s.gc_blobs()                                   # must NOT delete story blobs
    assert s.get_blob(media) is not None and s.get_blob(poster) is not None


def test_story_blob_reported_missing_when_absent(tmp_path):
    wong = person("wong-phone")
    s = store_with(tmp_path, wong.identity_pub)
    s.ingest_message(make_story(wong, "photo", "ab" * 32))
    assert "ab" * 32 in s.missing_blobs()          # gossip will fetch it


def test_active_stories_grouped_and_expiry(tmp_path):
    wong, freja = person("wong-phone"), person("freja-phone")
    s = store_with(tmp_path, wong.identity_pub, freja.identity_pub)
    now = time.time()
    s.ingest_message(make_story(wong, "photo", "a1" * 32, now=now - 10))
    s.ingest_message(make_story(wong, "photo", "a2" * 32, now=now - 5))
    s.ingest_message(make_story(freja, "photo", "b1" * 32, now=now - 8))
    groups = s.active_stories(now)
    by = {g["identity_pub"]: g for g in groups}
    assert [i["media"] for i in by[wong.identity_pub]["items"]] == \
        ["a1" * 32, "a2" * 32]                      # time-ascending within author
    assert len(by[freja.identity_pub]["items"]) == 1
    # an expired story (created 25h ago) is excluded
    s.ingest_message(make_story(wong, "photo", "c1" * 32,
                                now=now - 25 * 3600))
    medias = [i["media"] for g in s.active_stories(now)
              for i in g["items"]]
    assert "c1" * 32 not in medias
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_store_story.py -q`
Expected: FAIL — `Store` has no `active_stories`; story blobs not referenced.

- [ ] **Step 3: Modify `hearth/store.py`**

Add `KIND_STORY` to the messages import line (merge, don't duplicate):
```python
from .messages import (
    KIND_DELETE, KIND_DM, KIND_ENCKEY, KIND_POST, KIND_PROFILE, KIND_STORY,
    MAX_BLOB_BYTES, blob_hash, validate_payload,
)
```

In `referenced_blobs()`, after the existing KIND_PROFILE loop, add:
```python
            for (mj,) in self._db.execute(
                    "SELECT msg_json FROM messages WHERE kind=?",
                    (KIND_STORY,)):
                p = json.loads(mj)["payload"]
                for h in (p.get("media"), p.get("poster")):
                    if h:
                        refs.add(h)
```

Append the query method to `Store` (`msg_id` is a column on the messages table — select it directly):
```python
    def active_stories(self, now: Optional[float] = None) -> List[dict]:
        now = now if now is not None else time.time()
        with self._lock:
            groups: Dict[str, dict] = {}
            for mid, ipub, mj in self._db.execute(
                    "SELECT msg_id, identity_pub, msg_json FROM messages"
                    " WHERE kind=? ORDER BY created_at ASC", (KIND_STORY,)):
                p = json.loads(mj)["payload"]
                if p.get("expires_at") is not None and p["expires_at"] <= now:
                    continue
                g = groups.setdefault(ipub, {"identity_pub": ipub,
                                             "items": [], "_last": 0})
                g["items"].append({
                    "msg_id": mid, "media_kind": p["media_kind"],
                    "media": p["media"], "poster": p.get("poster"),
                    "caption": p.get("caption", ""),
                    "created_at": p["created_at"],
                })
                g["_last"] = max(g["_last"], p["created_at"])
            out = sorted(groups.values(), key=lambda g: g["_last"],
                         reverse=True)
            for g in out:
                del g["_last"]
            return out
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_store_story.py tests/test_store_ingest.py -q`
Expected: all pass.

- [ ] **Step 5: Full suite + commit**

Run: `.\.venv\Scripts\python.exe -m pytest tests -q`
Expected: zero failures.
```bash
git add hearth/store.py tests/test_store_story.py
git commit -m "feat: store story blob refs + active_stories grouped query"
```

---

### Task 4: Node — compose_story (sniff + gate) + stories_view

**Files:**
- Modify: `hearth/node.py`
- Test: `tests/test_node_story.py`

**Interfaces:**
- Consumes: `videogate.transcode_video`/`STORY_IMAGE_MAX`, `imagegate.transcode`, `make_story`, store `active_stories`/`profile`.
- Produces (on `HearthNode`):
  - `compose_story(media_bytes: bytes, caption: str = "") -> str` — sniff bytes: if image magic (PNG `\x89PNG`, JPEG `\xff\xd8`, GIF `GIF8`, WebP `RIFF`, BMP `BM`) → photo path: `imagegate.transcode(bytes, STORY_IMAGE_MAX)` → put_blob → make_story("photo", media, poster=None); else → video path: `transcode_video(bytes)` → put_blob(mp4) + put_blob(poster) → make_story("video", media, poster). Raises ValueError from the gate on bad media. Publishes the story message.
  - `stories_view() -> list[dict]` — `active_stories()` filtered to self + known friends, each group enriched with `{name, avatar, mine}` from `store.profile(identity)`; self group first, then others by recency.

- [ ] **Step 1: Write the failing tests**

`tests/test_node_story.py`:
```python
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_node_story.py -q`
Expected: FAIL — `compose_story` / `stories_view` missing.

- [ ] **Step 3: Modify `hearth/node.py`**

Extend imports:
```python
from .imagegate import transcode as image_transcode
from .videogate import STORY_IMAGE_MAX, transcode_video
from .messages import make_story
```
(merge `make_story` into the existing `.messages` import line; add the imagegate/videogate imports — note `image_transcode` may already be imported under a different alias in node.py; check and reuse. If node.py already imports transcode from imagegate for profiles, reuse that name.)

Append methods:
```python
    _IMAGE_MAGIC = (b"\x89PNG", b"\xff\xd8", b"GIF8", b"RIFF", b"BM")

    def compose_story(self, media_bytes: bytes, caption: str = "") -> str:
        is_image = any(media_bytes.startswith(m) for m in self._IMAGE_MAGIC)
        if is_image:
            media = self.store.put_blob(
                image_transcode(media_bytes, STORY_IMAGE_MAX))
            msg = make_story(self.device, "photo", media, poster=None,
                             caption=caption)
        else:
            mp4, poster_png = transcode_video(media_bytes)
            media = self.store.put_blob(mp4)
            poster = self.store.put_blob(poster_png)
            msg = make_story(self.device, "video", media, poster=poster,
                             caption=caption)
        return self._publish(msg)

    def stories_view(self):
        known = set(self.store.known_identities())
        out = []
        for g in self.store.active_stories():
            ipub = g["identity_pub"]
            if ipub != self.identity_pub and ipub not in known:
                continue
            prof = self.store.profile(ipub) or {}
            g = {**g, "mine": ipub == self.identity_pub,
                 "name": prof.get("name", ipub[:8]),
                 "avatar": prof.get("avatar")}
            out.append(g)
        out.sort(key=lambda g: (not g["mine"],))    # self first, keep order
        return out
```
Note: `_IMAGE_MAGIC` is a class attribute; place it with the other class-level names or inline the tuple in the method. `RIFF`/`BM` cover WebP/BMP. If `image_transcode` is already imported in node.py (profiles), do not re-import — reuse the existing name and drop the alias line.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_node_story.py tests/test_node.py tests/test_node_profile.py -q`
Expected: all pass.

- [ ] **Step 5: Full suite + commit**

Run: `.\.venv\Scripts\python.exe -m pytest tests -q`
Expected: zero failures.
```bash
git add hearth/node.py tests/test_node_story.py
git commit -m "feat: node compose_story (sniff+gate) and stories_view"
```

---

### Task 5: API — POST /api/story + GET /api/stories

**Files:**
- Modify: `hearth/api.py`
- Test: `tests/test_api_story.py`

**Interfaces:**
- Consumes: node `compose_story`/`stories_view` (Task 4); `MAX_BLOB_BYTES`.
- Produces (routes in `build_app`, before the websocket route):
  - `GET /api/stories` -> `node.stories_view()`.
  - `POST /api/story` — multipart: `media` file (required), `caption` (str, default ""). Read the file; 413 if over `MAX_BLOB_BYTES`; pass bytes to `node.compose_story` wrapped in `_400` (a bad/over-length/non-media upload raises ValueError -> 400). Returns `{"msg_id": ...}`.

- [ ] **Step 1: Write the failing tests**

`tests/test_api_story.py`:
```python
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_api_story.py -q`
Expected: FAIL — no story routes.

- [ ] **Step 3: Modify `hearth/api.py`**

Add the two routes before the websocket route (reuse existing `Form`, `File`, `UploadFile`, `HTTPException`, `_400`, `MAX_BLOB_BYTES`):
```python
    @app.get("/api/stories")
    async def stories():
        return node.stories_view()

    @app.post("/api/story")
    async def story(media: UploadFile = File(...), caption: str = Form("")):
        data = await media.read()
        if len(data) > MAX_BLOB_BYTES:
            raise HTTPException(413, "media exceeds 5 MB cap")
        mid = _400(lambda: node.compose_story(data, caption))
        return {"msg_id": mid}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_api_story.py tests/test_api.py -q`
Expected: all pass.

- [ ] **Step 5: Full suite + commit**

Run: `.\.venv\Scripts\python.exe -m pytest tests -q`
Expected: zero failures.
```bash
git add hearth/api.py tests/test_api_story.py
git commit -m "feat: story API - POST /api/story (sniff+gate), GET /api/stories"
```

---

### Task 6: Stories strip + full-screen viewer (JS), demo seed, smoke

**Files:**
- Modify: `hearth/web/app.js`, `hearth/web/style.css`, `hearth/demo.py`
- Test: (manual smoke — JS behavior)

**Interfaces:**
- Consumes: `GET /api/stories`, `POST /api/story`, `GET /api/blob/{h}` (Task 5).
- Produces: a stories strip at the top of the Feed view (own tile with `+ Add to story`, friends with an accent ring on unseen), and a full-screen viewer that plays through items and auto-advances. Seen-state in localStorage. No view/seen metrics sent.

- [ ] **Step 1: Extend `hearth/web/app.js`**

Add a stories strip render + viewer. Append these functions and call `renderStories()` from `refresh()` when the feed view is active. Add near the other feed rendering:
```javascript
function seenSet() {
  try { return new Set(JSON.parse(localStorage.getItem("loop_seen") || "[]")); }
  catch (e) { return new Set(); }
}
function markSeen(ids) {
  const s = seenSet(); ids.forEach(i => s.add(i));
  localStorage.setItem("loop_seen", JSON.stringify([...s]));
}

async function renderStories() {
  let strip = document.getElementById("stories");
  if (!strip) {
    strip = el("div"); strip.id = "stories";
    const feedCol = document.querySelector("#view-feed .feed-col");
    feedCol.insertBefore(strip, feedCol.firstChild);
  }
  const groups = await j("/api/stories");
  const seen = seenSet();
  strip.replaceChildren();
  // own "add" tile always first
  const me = groups.find(g => g.mine);
  const addTile = el("div", "story-tile");
  const addRing = el("div", "story-ring add");
  addRing.textContent = "+";
  const addInput = document.createElement("input");
  addInput.type = "file"; addInput.accept = "image/*,video/*";
  addInput.style.display = "none";
  addInput.onchange = async () => {
    if (!addInput.files[0]) return;
    const fd = new FormData(); fd.append("media", addInput.files[0]);
    fd.append("caption", "");
    const r = await fetch("/api/story", {method: "POST", body: fd});
    if (r.ok) renderStories(); else alert("Story failed: " + await r.text());
  };
  addRing.onclick = () => addInput.click();
  addTile.append(addRing, addInput, el("div", "story-name", "Your story"));
  strip.append(addTile);
  for (const g of groups) {
    if (g.mine && (!me || !me.items.length)) continue;
    const unseen = g.items.some(i => !seen.has(i.msg_id));
    const tile = el("div", "story-tile");
    const ring = el("div", "story-ring" + (unseen ? " unseen" : ""));
    ring.style.setProperty("--me",
      /* accent from viewer's own profile is not needed; ring uses ink */ "");
    if (g.avatar) { const im = document.createElement("img");
      im.src = "/api/blob/" + g.avatar; ring.append(im); }
    else ring.textContent = (g.name || "?").slice(0, 1).toUpperCase();
    ring.onclick = () => openStoryViewer(groups, g.identity_pub);
    tile.append(ring, el("div", "story-name", g.mine ? "You" : g.name));
    strip.append(tile);
  }
}

function openStoryViewer(groups, startIdentity) {
  const flat = [];
  for (const g of groups)
    for (const it of g.items) flat.push({...it, name: g.mine ? "You" : g.name});
  let idx = flat.findIndex(x =>
    groups.find(g => g.identity_pub === startIdentity).items
      .some(i => i.msg_id === x.msg_id));
  if (idx < 0) idx = 0;
  const ov = el("div"); ov.id = "story-viewer";
  let timer = null;
  function show() {
    if (idx < 0 || idx >= flat.length) { close(); return; }
    const it = flat[idx];
    markSeen([it.msg_id]);
    ov.replaceChildren();
    const bar = el("div", "sv-bars");
    flat.forEach((_, i) => bar.append(
      el("div", "sv-bar" + (i < idx ? " done" : i === idx ? " active" : ""))));
    const media = el("div", "sv-media");
    if (it.media_kind === "video") {
      const v = document.createElement("video");
      v.src = "/api/blob/" + it.media; v.autoplay = true; v.muted = true;
      v.playsInline = true; v.onended = next; media.append(v);
      v.onclick = () => { v.muted = !v.muted; };
    } else {
      const im = document.createElement("img"); im.src = "/api/blob/" + it.media;
      media.append(im); clearTimeout(timer); timer = setTimeout(next, 5000);
    }
    const cap = it.caption ? el("div", "sv-cap", it.caption) : null;
    const who = el("div", "sv-who", it.name);
    const x = el("div", "sv-close", "×"); x.onclick = close;
    const L = el("div", "sv-nav sv-left"); L.onclick = prev;
    const R = el("div", "sv-nav sv-right"); R.onclick = next;
    ov.append(bar, who, x, media, L, R);
    if (cap) ov.append(cap);
  }
  function next() { clearTimeout(timer); idx++; show(); }
  function prev() { clearTimeout(timer); idx = Math.max(0, idx - 1); show(); }
  function close() { clearTimeout(timer); ov.remove();
    document.body.classList.remove("sv-open"); renderStories(); }
  document.body.append(ov); document.body.classList.add("sv-open");
  show();
}
```
Then in `refresh()`, after rendering the feed (when the feed view is active), call `renderStories();`. Locate where `renderFeed(await j("/api/feed"))` is called and add `renderStories();` right after it (guarded so it only runs when not revoked — it's inside the same non-revoked block).

- [ ] **Step 2: Add `hearth/web/style.css`**

Append:
```css
#stories { display: flex; gap: 12px; overflow-x: auto; padding: 4px 2px 14px;
  border-bottom: 1px solid var(--line-2); margin-bottom: 14px; }
.story-tile { display: flex; flex-direction: column; align-items: center;
  gap: 5px; flex-shrink: 0; width: 64px; }
.story-ring { width: 58px; height: 58px; border-radius: 50%; cursor: pointer;
  display: grid; place-items: center; overflow: hidden; background: var(--fill);
  color: var(--ink-2); font-weight: 700; border: 2px solid var(--line);
  box-sizing: border-box; }
.story-ring img { width: 100%; height: 100%; object-fit: cover; }
.story-ring.unseen { border: 2px solid var(--ink); box-shadow: 0 0 0 2px var(--paper) inset; }
.story-ring.add { border-style: dashed; font-size: 24px; color: var(--faint); }
.story-name { font-size: 11px; color: var(--muted); max-width: 62px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
body.sv-open { overflow: hidden; }
#story-viewer { position: fixed; inset: 0; z-index: 100; background: #000;
  display: grid; place-items: center; }
.sv-media { max-width: 460px; width: 100%; height: 100%; display: grid;
  place-items: center; }
.sv-media img, .sv-media video { max-width: 100%; max-height: 100vh; }
.sv-bars { position: absolute; top: 10px; left: 12px; right: 12px;
  display: flex; gap: 4px; z-index: 2; }
.sv-bar { flex: 1; height: 3px; border-radius: 2px; background: rgba(255,255,255,.3); }
.sv-bar.done { background: #fff; } .sv-bar.active { background: rgba(255,255,255,.75); }
.sv-who { position: absolute; top: 22px; left: 14px; color: #fff;
  font-weight: 650; z-index: 2; font-size: 14px; }
.sv-close { position: absolute; top: 16px; right: 16px; color: #fff;
  font-size: 26px; cursor: pointer; z-index: 3; line-height: 1; }
.sv-cap { position: absolute; bottom: 40px; left: 0; right: 0; text-align: center;
  color: #fff; font-size: 15px; padding: 0 20px; z-index: 2;
  text-shadow: 0 1px 3px rgba(0,0,0,.6); }
.sv-nav { position: absolute; top: 0; bottom: 0; width: 33%; z-index: 1; cursor: pointer; }
.sv-left { left: 0; } .sv-right { right: 0; }
```

- [ ] **Step 3: Seed a demo story + `hearth/demo.py`**

In `hearth/demo.py`'s `build_cast`, after profiles are set and before nodes close, seed a photo story for Freja so the strip is non-empty (ASCII only; generate a tiny PNG inline with Pillow):
```python
    import io as _io
    from PIL import Image as _Image
    _buf = _io.BytesIO()
    _Image.new("RGB", (720, 1080), (192, 86, 59)).save(_buf, format="PNG")
    freja.compose_story(_buf.getvalue(), caption="aftenlys")
```
(Place it right after `freja.set_profile(...)`. Wong will see it once gossip carries the story message + blob.)

- [ ] **Step 4: Full suite + demo smoke check (headless)**

Run: `.\.venv\Scripts\python.exe -m pytest tests -q`
Expected: zero failures.

Then background `.\.venv\Scripts\python.exe -m hearth demo`, wait ~12s (stories + blobs need a gossip round to reach Wong), and verify headlessly:
- `GET http://127.0.0.1:7203/api/stories` (Freja's node) returns a group with `mine:true` and one photo item.
- `GET http://127.0.0.1:7201/api/stories` (Wong's node) eventually includes Freja's story group (poll up to ~15s) — proves the story message AND its media blob gossiped across.
Kill the demo process(es); delete `run/`.

- [ ] **Step 5: Commit**

```bash
git add hearth/web/app.js hearth/web/style.css hearth/demo.py
git commit -m "feat: stories strip + full-screen viewer, demo story seed"
```

---

## Plan self-review notes

- **Spec coverage:** video gate w/ reject>15s + strip audio + poster + size cap (T1); KIND_STORY message + 24h expiry (T2); store blob refs + active_stories grouped/expiry (T3); node sniff+gate compose_story + stories_view self-first (T4); API POST/GET + 400/413 (T5); strip + full-screen viewer + seen-localStorage + demo seed (T6). No view/seen metrics anywhere. Server sniffs media kind (T4). Length cap as transport artifact recorded in spec, not enforced beyond 15s here.
- **Blob-GC lesson applied:** T3 adds KIND_STORY media+poster to referenced_blobs, with explicit gc-safe + missing_blobs tests (the exact gap the profiles final review caught) — and T6's smoke check verifies a story blob actually gossips across two nodes.
- **Dependency verified at plan time:** imageio-ffmpeg installed, ffmpeg 7.1 runs, and the encode/strip-audio/poster pipeline was proven in this environment before writing the plan.
- **Known risks:** (1) ffmpeg subprocess tests are slower (seconds each) but deterministic. (2) The `-vf` comma inside `min()` MUST be a raw string `r"scale=w=-2:h=min(720\,ih)"` (escaped comma) or ffmpeg reads it as a filter separator — called out in T1. (3) `probe_duration` parses `ffmpeg -i` stderr (no ffprobe bundled) — robust for real media, raises ValueError when no Duration line. (4) T6 is JS; verified only by the demo smoke check, not unit tests. (5) node.py may already import `transcode` from imagegate (profiles) — T4 says reuse, don't double-import.
