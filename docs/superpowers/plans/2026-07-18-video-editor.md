# Video Editor (trim + crop + cover) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user trim any video to a ≤15s window, crop it to an aspect, and pick a cover frame — client simulates against a local preview, the user's own node executes the real ffmpeg cut through the existing videogate.

**Architecture:** Spec: `docs/superpowers/specs/2026-07-18-video-trimmer-design.md`. The browser editor collects `{start, duration, crop, poster_t}` and uploads the ORIGINAL file + a `video_edit` JSON form field over loopback; `hearth/videogate.py` validates and executes (`-ss`/`-t` cut + `crop=` filter + poster at `poster_t`), then the unchanged downscale/strip/H.264 pass. No edit field → behavior-identical to today. Only the ≤5MB gate-produced MP4 ever syncs.

**Tech Stack:** Python 3.12 (FastAPI, bundled imageio-ffmpeg), vanilla JS (`hearth/web/app.js` — single-file convention), pytest (+ Playwright behind `UI_E2E=1`).

## Global Constraints

- Run tests with `.venv\Scripts\python.exe -m pytest ...` (Windows, venv).
- ASCII only in Python print/log strings (cp1252 console).
- app.js is a single file by convention — the editor is added there, not a new script.
- No new dependencies, no external requests from the web client (no-CDN rule).
- Commit style: `type(scope): summary` lines, no AI trailers (August's rule).
- `MAX_VIDEO_SECONDS = 15.0`, `MAX_VIDEO_BYTES = 5 MB`, `_TIMEOUT = 60` stay unchanged.
- The no-edit videogate path stays *behavior*-identical (same rejects/caps/codec); bytes change only via the `-preset slow` rider.
- UI copy drafted here is a DRAFT — August may reword; update pinned asset-test strings together with any reword.

---

### Task 1: videogate — `validate_video_edit` + edit-aware `transcode_video` + preset rider

**Files:**
- Modify: `hearth/videogate.py`
- Test: `tests/test_videogate.py`

**Interfaces:**
- Produces: `validate_video_edit(edit: dict) -> dict` (normalized `{"start": float, "duration": float, "crop": {"x","y","w","h"}|None, "poster_t": float}`, raises `ValueError` on any bad input); `transcode_video(data: bytes, edit: dict | None = None) -> tuple[bytes, bytes]`.
- Consumes: nothing new.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_videogate.py` (the `_make_clip` helper at the top of the file already exists — reuse it):

```python
from hearth.videogate import validate_video_edit


def _dims(png_bytes):
    from PIL import Image
    import io
    return Image.open(io.BytesIO(png_bytes)).size


def test_validate_video_edit_normalizes_and_rejects():
    ok = validate_video_edit({"start": 2, "duration": 10,
                              "crop": {"x": 0.25, "y": 0, "w": 0.5, "h": 1},
                              "poster_t": 3})
    assert ok == {"start": 2.0, "duration": 10.0,
                  "crop": {"x": 0.25, "y": 0.0, "w": 0.5, "h": 1.0},
                  "poster_t": 3.0}
    # crop absent -> None; poster_t defaults 0
    assert validate_video_edit({"start": 0, "duration": 5})["crop"] is None
    assert validate_video_edit({"start": 0, "duration": 5})["poster_t"] == 0.0
    import pytest
    for bad in [
        "nope",                                          # not a dict
        {},                                              # missing fields
        {"start": -1, "duration": 5},                    # negative start
        {"start": 0, "duration": 0},                     # empty window
        {"start": 0, "duration": 15.5},                  # window over cap
        {"start": 0, "duration": 5, "poster_t": 6},      # poster past window
        {"start": 0, "duration": 5, "poster_t": -1},
        {"start": 0, "duration": 5,
         "crop": {"x": 0.8, "y": 0, "w": 0.5, "h": 1}},  # x+w > 1
        {"start": 0, "duration": 5,
         "crop": {"x": 0, "y": 0, "w": 0.05, "h": 1}},   # below 0.1 min
        {"start": "x", "duration": 5},                   # non-numeric
    ]:
        with pytest.raises(ValueError):
            validate_video_edit(bad)


def test_edit_cuts_long_source_to_window():
    src = _make_clip(30)
    mp4, poster = transcode_video(
        src, {"start": 5, "duration": 10, "poster_t": 0})
    d = probe_duration(mp4)
    assert 9.0 < d < 11.0
    # and the SAME source without an edit still rejects (no-edit rule intact)
    import pytest
    with pytest.raises(ValueError):
        transcode_video(src)


def test_edit_start_past_end_rejected():
    import pytest
    with pytest.raises(ValueError):
        transcode_video(_make_clip(5), {"start": 30, "duration": 5})


def test_edit_crop_changes_output_aspect():
    # 640x480 source, crop the middle 50% x 100% -> 320x480-ish output
    mp4, poster = transcode_video(
        _make_clip(2, w=640, h=480),
        {"start": 0, "duration": 2,
         "crop": {"x": 0.25, "y": 0.0, "w": 0.5, "h": 1.0}})
    w, h = _dims(poster)
    assert abs(w / h - (320 / 480)) < 0.05


def test_edit_poster_t_picks_a_different_frame():
    src = _make_clip(10)          # testsrc renders a moving timestamp
    _, p0 = transcode_video(src, {"start": 0, "duration": 10, "poster_t": 0})
    _, p8 = transcode_video(src, {"start": 0, "duration": 10, "poster_t": 8})
    assert p0 != p8


def test_edit_poster_t_at_window_end_clamps_not_fails():
    mp4, poster = transcode_video(
        _make_clip(6), {"start": 0, "duration": 6, "poster_t": 6})
    assert poster[:4] == b"\x89PNG"


def test_rotated_portrait_source_crop_matches_display_frame():
    # A 640x480 clip carrying rotate=90 metadata DISPLAYS as 480x640
    # (portrait). A full-height half-width crop must therefore come out
    # tall, proving ffmpeg autorotate stayed on and crop applies to the
    # display-oriented frame (spec's classic-mismatch pin).
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    import tempfile, os
    d = tempfile.mkdtemp()
    plain = os.path.join(d, "p.mp4")
    rot = os.path.join(d, "r.mp4")
    subprocess.run([ff, "-f", "lavfi", "-i",
                    "testsrc=size=640x480:rate=24:duration=2",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", plain],
                   check=True, capture_output=True)
    subprocess.run([ff, "-i", plain, "-c", "copy",
                    "-metadata:s:v:0", "rotate=90", "-y", rot],
                   check=True, capture_output=True)
    with open(rot, "rb") as f:
        src = f.read()
    mp4, poster = transcode_video(
        src, {"start": 0, "duration": 2,
              "crop": {"x": 0.0, "y": 0.0, "w": 0.5, "h": 1.0}})
    w, h = _dims(poster)
    assert h > w                       # portrait crop of a portrait frame


def test_preset_slow_rider_pinned():
    # Rider (spec 2026-07-18): better quality-per-byte at zero
    # compatibility cost. Source-pin, same style as the web asset tests.
    import hearth.videogate as vg
    from pathlib import Path
    src = Path(vg.__file__).read_text(encoding="utf-8")
    assert '"-preset", "slow"' in src
    assert '"veryfast"' not in src
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest tests\test_videogate.py -q`
Expected: the new tests FAIL (`ImportError: cannot import name 'validate_video_edit'` first); the pre-existing tests still pass.

- [ ] **Step 3: Implement in `hearth/videogate.py`**

Update the module docstring's last two sentences (it currently claims "Reject over-length ... instead of trimming"):

```python
"""Video transcode gate: re-encode Story/wall videos to known-good muted MP4.

Mirrors the image gate: decode the upload and re-encode via a bundled
ffmpeg so viewers only ever render bytes WE produced. Author-side gate
(a modified peer can still put raw bytes behind a hash, as post photos do,
served nosniff and never as HTML). With a video_edit (spec 2026-07-18)
the gate CUTS a <=15s window / crops / picks the poster frame; without
one, over-length is rejected exactly as before. Audio is stripped."""
```

Then add validation and extend `transcode_video` (replacing the whole function) — everything else in the file stays as-is:

```python
def validate_video_edit(edit) -> dict:
    """Normalize a client video_edit (spec 2026-07-18). Raises ValueError.

    {"start": s>=0, "duration": 0<d<=15, "crop": {x,y,w,h} normalized
    to the DISPLAY-oriented frame (0..1, min 0.1, inside bounds) or
    None, "poster_t": 0<=t<=duration into the cut}."""
    if not isinstance(edit, dict):
        raise ValueError("bad video_edit")
    try:
        start = float(edit["start"])
        duration = float(edit["duration"])
        poster_t = float(edit.get("poster_t", 0.0))
    except (KeyError, TypeError, ValueError):
        raise ValueError("bad video_edit")
    if not (start >= 0.0 and 0.0 < duration <= MAX_VIDEO_SECONDS):
        raise ValueError("trim window must be within 15 seconds")
    if not (0.0 <= poster_t <= duration):
        raise ValueError("cover time is outside the trim window")
    crop = edit.get("crop")
    if crop is not None:
        try:
            x, y, w, h = (float(crop[k]) for k in ("x", "y", "w", "h"))
        except (KeyError, TypeError, ValueError):
            raise ValueError("bad crop")
        # 1e-4 slack: client floats may land at 1.0000001 after math
        if not (x >= 0.0 and y >= 0.0 and w >= 0.1 and h >= 0.1
                and x + w <= 1.0 + 1e-4 and y + h <= 1.0 + 1e-4):
            raise ValueError("bad crop")
        crop = {"x": x, "y": y, "w": min(w, 1.0 - x), "h": min(h, 1.0 - y)}
    return {"start": start, "duration": duration,
            "crop": crop, "poster_t": poster_t}


def transcode_video(data: bytes, edit=None) -> tuple[bytes, bytes]:
    if edit is not None:
        edit = validate_video_edit(edit)
    src_dur = probe_duration(data)
    if edit is None and src_dur > MAX_VIDEO_SECONDS:
        raise ValueError("video longer than 15 seconds")
    if edit is not None and edit["start"] >= src_dur:
        raise ValueError("trim window starts past the end of the video")
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "in")
        out = os.path.join(d, "out.mp4")
        frame = os.path.join(d, "poster.png")
        with open(src, "wb") as f:
            f.write(data)
        # Downscale to <=720 tall keeping aspect (even dims), strip audio,
        # H.264 MP4. The comma inside min() must be escaped for ffmpeg's
        # filter parser (raw string), else it reads as a filter separator.
        # Height is forced even (trunc(ih/2)*2) to satisfy yuv420p/libx264.
        vf = f"scale=w=-2:h=min({VIDEO_MAX_DIM}\\,trunc(ih/2)*2)"
        cut = []
        if edit is not None:
            # -ss/-t BEFORE -i: fast keyframe seek; output is
            # frame-accurate anyway because we re-encode.
            cut = ["-ss", f"{edit['start']:.3f}",
                   "-t", f"{edit['duration']:.3f}"]
            if edit["crop"] is not None:
                c = edit["crop"]
                # iw/ih are the DISPLAY-oriented dims (ffmpeg autorotates
                # on decode by default - do not pass -noautorotate; the
                # browser preview the user cropped against agrees).
                vf = (f"crop=iw*{c['w']:.6f}:ih*{c['h']:.6f}"
                      f":iw*{c['x']:.6f}:ih*{c['y']:.6f}," + vf)
        enc = _run(["-protocol_whitelist", "file", *cut, "-i", src,
                    "-vf", vf,
                    "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                    "-crf", "28", "-maxrate", "2500k", "-bufsize", "5000k",
                    "-preset", "slow", "-movflags",
                    "+faststart", "-y", out])
        if enc.returncode != 0 or not os.path.exists(out):
            raise ValueError("could not transcode video")
        with open(out, "rb") as f:
            mp4 = f.read()
        if len(mp4) > MAX_VIDEO_BYTES:
            raise ValueError("transcoded video exceeds 5 MB")
        poster_t = 0.0
        if edit is not None:
            # A start near the source end can yield a near-empty cut -
            # surface that as a trim error, not "not a video".
            try:
                out_dur = probe_duration(mp4)
            except ValueError:
                raise ValueError("trim window is outside the video")
            # clamp: the cut may be shorter than requested (source ended)
            poster_t = min(edit["poster_t"], max(0.0, out_dur - 0.1))
        seek = ["-ss", f"{poster_t:.3f}"] if poster_t > 0 else []
        pf = _run(["-protocol_whitelist", "file", *seek, "-i", out,
                   "-frames:v", "1", "-f", "image2", "-y", frame])
        if pf.returncode != 0 or not os.path.exists(frame):
            raise ValueError("could not extract poster")
        with open(frame, "rb") as f:
            poster_data = f.read()
        poster = image_transcode(poster_data, STORY_IMAGE_MAX)
    return mp4, poster
```

- [ ] **Step 4: Run the videogate tests**

Run: `.venv\Scripts\python.exe -m pytest tests\test_videogate.py -q`
Expected: ALL pass (pre-existing + new). Note `test_over_length_rejected` and friends must pass untouched — they pin the no-edit path.

- [ ] **Step 5: Commit**

```bash
git add hearth/videogate.py tests/test_videogate.py
git commit -m "feat(videogate): video_edit cut/crop/cover execution + preset slow rider (spec 2026-07-18)"
```

---

### Task 2: records — `codec` field + `video_edit` through `compose_post` / `compose_story`

**Files:**
- Modify: `hearth/messages.py:74-85` (`make_post`), `make_story` (same file)
- Modify: `hearth/node.py:573-655` (`compose_post`), `:691-707` (`compose_story`), `:1245-1268` (`_decrypt_post_row`)
- Test: `tests/test_profile_video.py` (append), `tests/test_node_story.py` (append)

**Interfaces:**
- Consumes: `transcode_video(data, edit)` and `validate_video_edit` from Task 1.
- Produces: `compose_post(..., video_edit: dict | None = None)`; `compose_story(media_bytes, caption="", video_edit: dict | None = None)`; post payloads and decrypted post rows carry `"codec": "h264"` for video (None otherwise); story video payloads carry `"codec": "h264"`.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_profile_video.py` (reuse its existing clip helper / node fixture pattern — it already composes video posts; follow the file's local conventions for node construction):

```python
def test_video_edit_trims_wall_post_and_stamps_codec(tmp_path):
    node = _node(tmp_path)              # use this file's existing fixture helper
    src = _clip(30)                     # and its existing clip helper
    mid = node.compose_post("cut", "kreds", video=src,
                            placement="profile",
                            video_edit={"start": 2, "duration": 8,
                                        "poster_t": 1})
    row = [p for p in node.posts_by(node.identity_pub, placement="profile")
           if p["msg_id"] == mid][0]
    assert row["media"] == "video"
    assert row["codec"] == "h264"
    # the stored blob is the CUT artifact: decrypt via the row's blob ref
    from hearth.videogate import probe_duration
    from hearth.dmcrypt import decrypt_blob        # match the file's imports
    key = node._content_key(node.store.message(mid))[0]
    mp4 = decrypt_blob(key, node.store.get_blob(row["blobs"][0]))
    assert 7.0 < probe_duration(mp4) < 9.0


def test_photo_post_has_no_codec(tmp_path):
    node = _node(tmp_path)
    mid = node.compose_post("plain text", "kreds")
    row = [p for p in node.feed() if p["msg_id"] == mid][0]
    assert row["codec"] is None
```

Append to `tests/test_node_story.py` (same pattern reuse):

```python
def test_story_video_edit_trims_and_stamps_codec(tmp_path):
    node = _node(tmp_path)
    mid = node.compose_story(_clip(25), "",
                             video_edit={"start": 0, "duration": 6,
                                         "poster_t": 2})
    from hearth.videogate import probe_duration
    item = [i for g in node.stories_view() for i in g["items"]
            if i["msg_id"] == mid][0]
    mp4 = node.store.get_blob(item["media"])
    assert 5.0 < probe_duration(mp4) < 7.0
    assert node.store.message(mid).payload["codec"] == "h264"


def test_story_image_with_video_edit_rejected(tmp_path):
    node = _node(tmp_path)
    import pytest
    with pytest.raises(ValueError):
        node.compose_story(_png(), "", video_edit={"start": 0, "duration": 5})
```

NOTE for the implementer: `_node`/`_clip`/`_png` names above are stand-ins for whatever helpers those two test files actually define at their top — read the file first and use its real helper names and signatures. If `tests/test_node_story.py` has no clip helper, copy `_make_clip` from `tests/test_videogate.py:10-23` verbatim. If `node.store.message(mid)` doesn't exist, find the store's message-by-id accessor used elsewhere in the tests (grep `store.` in the same file) — do not invent one.

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests\test_profile_video.py tests\test_node_story.py -q`
Expected: new tests FAIL (`TypeError: compose_post() got an unexpected keyword argument 'video_edit'`), old ones pass.

- [ ] **Step 3: Implement**

`hearth/messages.py` — `make_post` gains a `codec` kwarg (additive field, old clients ignore unknowns; mirrors how `poster` rides):

```python
def make_post(device: DeviceKeys, scope: str, body_nonce: str,
              body_ct: str, wraps: dict, blob_refs: Sequence[str] = (),
              created_at: Optional[float] = None,
              expires_at: Optional[float] = None,
              placement: str = "journal", media: str = "photo",
              poster: Optional[str] = None,
              codec: Optional[str] = None) -> SignedMessage:
    return device.sign_message({
        "kind": KIND_POST, "scope": scope, "body_nonce": body_nonce,
        "body_ct": body_ct, "wraps": wraps, "blobs": list(blob_refs),
        "created_at": _now(created_at), "expires_at": expires_at,
        "placement": placement, "media": media, "poster": poster,
        "codec": codec,
    })
```

`make_story` in the same file: add `codec: Optional[str] = None` to the signature and `"codec": codec` to the signed dict, same shape as above.

`hearth/node.py` `compose_post`: signature gains `video_edit=None`; the video branch becomes:

```python
        if video is not None:
            # video gate (spec 2026-07-18): with a video_edit the gate
            # cuts/crops/poster-picks; codec stamped for future
            # negotiation (H.264 -> AV1 -> AV2 ladder, ROADMAP)
            mp4, poster_png = transcode_video(video, video_edit)
            vref = self.store.put_blob(encrypt_blob(key, mp4))
            pref = self.store.put_blob(encrypt_blob(key, poster_png))
            nonce, ct = encrypt_body(key, {"text": text, "blobs": [vref]}, aad)
            wraps = wrap_key(key, pubs, aad)
            mid = self._publish(make_post(self.device, scope, nonce, ct, wraps,
                                          [vref], created_at, expires_at,
                                          placement=placement, media="video",
                                          poster=pref, codec="h264"))
```

Also add right after the `placement` check at the top of `compose_post`:

```python
        if video_edit is not None and video is None:
            raise ValueError("video_edit without a video")
```

`compose_story`: signature `def compose_story(self, media_bytes: bytes, caption: str = "", video_edit=None) -> str:`; in the image branch add first line `if video_edit is not None: raise ValueError("video_edit given for an image")`; the video branch calls `transcode_video(media_bytes, video_edit)` and passes `codec="h264"` to `make_story`.

`_decrypt_post_row`: add `"codec": p.get("codec"),` right after the `"poster"` line (node.py:1267).

- [ ] **Step 4: Run the tests**

Run: `.venv\Scripts\python.exe -m pytest tests\test_profile_video.py tests\test_node_story.py tests\test_story_model.py tests\test_profile_video_integration.py -q`
Expected: ALL pass (the two integration/model files exercise `make_post`/`make_story` signatures — additive kwargs must not break them).

- [ ] **Step 5: Commit**

```bash
git add hearth/messages.py hearth/node.py tests/test_profile_video.py tests/test_node_story.py
git commit -m "feat(node): video_edit through compose_post/compose_story + codec field on video records"
```

---

### Task 3: API — `video_edit` form field + story raw-cap fix + shared image sniff

**Files:**
- Modify: `hearth/imagegate.py` (add `is_image_bytes`), `hearth/node.py:688-695` (use it), `hearth/api.py:361-393` (`/api/post`), `:619-625` (`/api/story`)
- Test: `tests/test_api_story.py` (append), `tests/test_web_assets.py` untouched here

**Interfaces:**
- Consumes: `compose_post(video_edit=)` / `compose_story(video_edit=)` from Task 2.
- Produces: `is_image_bytes(data: bytes) -> bool` in `hearth/imagegate.py`; `/api/post` and `/api/story` accept `video_edit: str = Form("")` (JSON); `/api/story` caps non-image media at `MAX_VIDEO_UPLOAD` (100 MB) with 413 `"video exceeds the 100 MB upload cap"`.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_api_story.py`:

```python
from hearth.videogate import probe_duration


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
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests\test_api_story.py -q`
Expected: new tests FAIL (422/413/ignored-field symptoms), old ones pass.

- [ ] **Step 3: Implement**

`hearth/imagegate.py` — add near the top, below the constants:

```python
# Image magic sniff (moved here from node.py, spec 2026-07-18): shared by
# compose_story's photo-vs-video fork and /api/story's upload-cap choice.
_IMAGE_MAGIC = (b"\x89PNG", b"\xff\xd8", b"GIF8", b"BM",
                b"II*\x00", b"MM\x00*")   # PNG, JPEG, GIF, BMP, TIFF-LE/BE


def is_image_bytes(data: bytes) -> bool:
    return (data[:4] == b"RIFF" and data[8:12] == b"WEBP") \
        or any(data.startswith(m) for m in _IMAGE_MAGIC)
```

`hearth/node.py`: delete the `_IMAGE_MAGIC` class attribute (node.py:688-689); `compose_story` computes `is_image = is_image_bytes(media_bytes)` (import `is_image_bytes` alongside the existing `transcode` import from `.imagegate`).

`hearth/api.py` — `/api/post` gains the field and parse (add `import json` at the top if absent):

```python
    @app.post("/api/post")
    async def post(text: str = Form(""), scope: str = Form("kreds"),
                   expires_seconds: str = Form(""),
                   placement: str = Form("journal"),
                   photos: List[UploadFile] = File(default=[]),
                   video: UploadFile = File(default=None),
                   video_edit: str = Form(default=""),
                   w: Optional[int] = Form(default=None),
                   h: Optional[int] = Form(default=None),
                   place: str = Form("1")):
        # place="0" skips the profile auto-place (spec 2026-07-14): the
        # deck grow flow's album-bound photo must not disturb the wall.
        expiry = float(expires_seconds) if expires_seconds.strip() else None
        auto_place = place != "0"
        edit = None
        if video_edit.strip():
            if video is None:
                raise HTTPException(400, "video_edit without a video")
            try:
                edit = json.loads(video_edit)
            except json.JSONDecodeError:
                raise HTTPException(400, "bad video_edit")
        if video is not None:
            vbytes = await video.read()
            if len(vbytes) > MAX_VIDEO_UPLOAD:
                raise HTTPException(413, "video exceeds upload cap")
            mid = _400(lambda: node.compose_post(text, scope, (), expiry,
                                                 placement=placement, video=vbytes,
                                                 video_edit=edit,
                                                 span_w=w, span_h=h,
                                                 auto_place=auto_place))
            return {"msg_id": mid}
        ...photos branch unchanged...
```

`/api/story` (import `is_image_bytes` from `.imagegate`):

```python
    @app.post("/api/story")
    async def story(media: UploadFile = File(...), caption: str = Form(""),
                    video_edit: str = Form(default="")):
        data = await media.read()
        # Story raw-cap fix (spec 2026-07-18): video sources get the same
        # 100 MB headroom as wall-post video - the trimmer's whole point
        # is a big source; the gossiped artifact stays <=5 MB regardless.
        if is_image_bytes(data):
            if len(data) > MAX_IMAGE_UPLOAD:
                raise HTTPException(413, "media exceeds the 50 MB upload cap")
        elif len(data) > MAX_VIDEO_UPLOAD:
            raise HTTPException(413, "video exceeds the 100 MB upload cap")
        edit = None
        if video_edit.strip():
            try:
                edit = json.loads(video_edit)
            except json.JSONDecodeError:
                raise HTTPException(400, "bad video_edit")
        mid = _400(lambda: node.compose_story(data, caption, video_edit=edit))
        return {"msg_id": mid}
```

(`MAX_VIDEO_UPLOAD` is already imported in api.py for `/api/post`.)

- [ ] **Step 4: Run the tests**

Run: `.venv\Scripts\python.exe -m pytest tests\test_api_story.py tests\test_node_story.py tests\test_story_model.py -q`
Expected: ALL pass.

- [ ] **Step 5: Commit**

```bash
git add hearth/imagegate.py hearth/node.py hearth/api.py tests/test_api_story.py
git commit -m "feat(api): video_edit field on /api/post + /api/story, story video cap 50->100MB, shared is_image_bytes"
```

---

### Task 4: client — editor modal core (overlay, trim, filmstrip, loop preview) + CSS

**Files:**
- Modify: `hearth/web/app.js` (new `openVideoEditor` function, placed directly ABOVE `renderStories`, app.js:~2167)
- Modify: `hearth/web/style.css` (append a `/* Video editor */` section at the end)
- Test: `tests/test_web_assets.py` (append)

**Interfaces:**
- Produces: `openVideoEditor(file, existing, onClose)` — `file`: File; `existing`: prior edit object or `null`; `onClose({action, edit})` where `action` is `"done"` (with `edit = {start, duration, crop, poster_t}`), `"raw"` (file undecodable — post unedited), or `"cancel"` (discard the pick). Constant `VE_MAX_WINDOW = 15`.
- Consumes: `el()` helper (app.js:top).
- NOTE: Task 5 extends this same function with crop + cover; write it with the state slots (`aspect`, `zoom`, `cx`, `cy`, `coverAbs`) already declared as this task's code shows, so Task 5 is additive.

- [ ] **Step 1: Write the failing asset tests**

Append to `tests/test_web_assets.py`:

```python
def test_video_editor_wired():
    # Spec 2026-07-18: trim+crop+cover editor - client simulates, the
    # node executes. Core contract pins.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    ve = _js_fn_body(js, "openVideoEditor")
    assert "VE_MAX_WINDOW" in js and "const VE_MAX_WINDOW = 15" in js
    for needle in ("ve-stage", "ve-strip", "ve-handle", "ve-window",
                   "createObjectURL", "revokeObjectURL",
                   'action: "done"', 'action: "raw"', 'action: "cancel"'):
        assert needle in ve, needle
    # the trim loop wraps playback inside the window
    assert "timeupdate" in ve
    assert "#video-editor" in css and ".ve-handle" in css
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests\test_web_assets.py::test_video_editor_wired -q`
Expected: FAIL (`openVideoEditor` not found).

- [ ] **Step 3: Implement the editor core in `app.js`**

Insert above `renderStories` (keep Task 5's slots — `aspect/zoom/cx/cy` unused this task but declared):

```js
// ---- Video editor (spec 2026-07-18): trim + crop + cover ------------
// The client SIMULATES the edit against a local <video> preview and
// returns PARAMETERS only - the node's videogate executes the real
// ffmpeg cut. onClose({action, edit}): "done" carries {start, duration,
// crop|null, poster_t}; "raw" means the engine can't even read metadata
// (post unedited - the node validates, today's behavior); "cancel"
// discards the pick.
const VE_MAX_WINDOW = 15;
function openVideoEditor(file, existing, onClose) {
  const ov = el("div"); ov.id = "video-editor";
  ov.setAttribute("role", "dialog");
  ov.setAttribute("aria-modal", "true");
  ov.setAttribute("aria-label", "Edit video");
  const stage = el("div", "ve-stage");
  const frame = el("div", "ve-frame");
  const vid = document.createElement("video");
  vid.muted = true; vid.playsInline = true;
  const url = URL.createObjectURL(file);
  vid.src = url;
  frame.append(vid); stage.append(frame);
  const strip = el("div", "ve-strip");
  const track = el("div", "ve-track");
  const selWin = el("div", "ve-window");
  const hL = el("div", "ve-handle ve-h-left");
  const hR = el("div", "ve-handle ve-h-right");
  hL.setAttribute("aria-label", "Trim start");
  hR.setAttribute("aria-label", "Trim end");
  strip.append(track, selWin, hL, hR);
  const times = el("div", "ve-times");
  const chips = el("div", "ve-chips");        // Task 5 fills these
  const btns = el("div", "ve-btns");
  const cancelB = el("button", "ve-btn", "Cancel"); cancelB.type = "button";
  const doneB = el("button", "ve-btn ve-done", "Done"); doneB.type = "button";
  btns.append(cancelB, doneB);
  ov.append(stage, strip, times, chips, btns);
  document.body.append(ov);

  let dur = 0, vw = 0, vh = 0, degraded = false, closed = false;
  let start = 0, end = 0, coverAbs = 0;               // trim + cover state
  let aspect = "orig", zoom = 1, cx = 0.5, cy = 0.5;  // crop state (Task 5)

  const fmt = (t) => t.toFixed(1) + "s";
  const finish = (action, edit) => {
    if (closed) return;
    closed = true;
    URL.revokeObjectURL(url);
    ov.remove();
    onClose(action === "done" ? {action, edit} : {action});
  };
  cancelB.onclick = () => finish("cancel");
  doneB.onclick = () => finish("done", buildEdit());
  ov.addEventListener("keydown", (e) => {
    if (e.key === "Escape") finish("cancel");
  });

  function buildEdit() {
    return {start: Math.round(start * 1000) / 1000,
            duration: Math.round((end - start) * 1000) / 1000,
            crop: cropRect(),                        // null until Task 5
            poster_t: Math.round((coverAbs - start) * 1000) / 1000};
  }
  function cropRect() { return null; }               // replaced in Task 5

  // -- trim geometry: strip x-position <-> time -----------------------
  const px = (t) => strip.clientWidth * t / dur;
  const toT = (x) => Math.min(dur, Math.max(0, x / strip.clientWidth * dur));
  function render() {
    selWin.style.left = px(start) + "px";
    selWin.style.width = Math.max(2, px(end) - px(start)) + "px";
    hL.style.left = px(start) + "px";
    hR.style.left = px(end) + "px";
    renderCover();                                    // Task 5 fills in
    times.textContent = fmt(start) + " - " + fmt(end)
      + "  (" + fmt(end - start) + " of " + fmt(dur) + ")";
  }
  function renderCover() {}                           // replaced in Task 5

  function dragHandle(handle, isLeft) {
    handle.addEventListener("pointerdown", (ev) => {
      if (!ev.isPrimary) return;
      ev.preventDefault();
      handle.setPointerCapture(ev.pointerId);
      const move = (e) => {
        const t = toT(e.clientX - strip.getBoundingClientRect().left);
        if (isLeft) {
          start = Math.min(t, end - 0.5);
          start = Math.max(start, end - VE_MAX_WINDOW);
        } else {
          end = Math.max(t, start + 0.5);
          end = Math.min(end, start + VE_MAX_WINDOW);
        }
        coverAbs = Math.min(Math.max(coverAbs, start), end);
        vid.currentTime = isLeft ? start : Math.max(start, end - 0.1);
        render();
      };
      const up = () => {
        handle.removeEventListener("pointermove", move);
        handle.removeEventListener("pointerup", up);
        vid.currentTime = start; vid.play();
      };
      handle.addEventListener("pointermove", move);
      handle.addEventListener("pointerup", up);
    });
  }
  dragHandle(hL, true);
  dragHandle(hR, false);

  // loop playback inside the window
  vid.addEventListener("timeupdate", () => {
    if (vid.currentTime >= end - 0.03 || vid.currentTime < start - 0.25)
      vid.currentTime = start;
  });

  async function buildThumbs() {
    const tv = document.createElement("video");
    tv.muted = true; tv.preload = "auto"; tv.src = url;
    await new Promise((res, rej) => {
      tv.onloadeddata = res; tv.onerror = rej;
    });
    if (!tv.videoWidth) throw new Error("no frames");
    const N = 8, w = 88,
          h = Math.max(1, Math.round(w * tv.videoHeight / tv.videoWidth));
    for (let i = 0; i < N; i++) {
      tv.currentTime = Math.min(dur - 0.05, (i + 0.5) * dur / N);
      await new Promise((res) => { tv.onseeked = res; });
      const c = document.createElement("canvas");
      c.width = w; c.height = h; c.className = "ve-thumb";
      c.getContext("2d").drawImage(tv, 0, 0, w, h);
      track.append(c);
    }
  }

  vid.addEventListener("error", () => {
    // engine can't read the file at all -> today's behavior (raw post)
    if (!dur) finish("raw");
  });
  vid.addEventListener("loadedmetadata", () => {
    dur = vid.duration; vw = vid.videoWidth; vh = vid.videoHeight;
    if (!isFinite(dur) || dur <= 0) { finish("raw"); return; }
    if (existing) {
      start = Math.min(existing.start, Math.max(0, dur - 0.5));
      end = Math.min(dur, start + existing.duration);
      coverAbs = start + Math.min(existing.poster_t, end - start);
      restoreCrop(existing.crop);                     // Task 5 no-op-safe
    } else {
      start = 0; end = Math.min(dur, VE_MAX_WINDOW); coverAbs = 0;
    }
    initCrop();                                        // Task 5 fills in
    render();
    vid.currentTime = start;
    vid.play();
    buildThumbs().catch(() => {
      // metadata but no decodable frames: slider-only degraded mode -
      // trim still works blind against the times readout; crop stays
      // disabled (can't position what you can't see). Spec 2026-07-18.
      degraded = true;
      track.classList.add("ve-blank");
      chips.classList.add("ve-disabled");
      frame.classList.add("ve-noframes");
    });
  });
  function initCrop() {}                               // replaced in Task 5
  function restoreCrop() {}                            // replaced in Task 5
  ov.tabIndex = -1;
  ov.focus();
}
```

- [ ] **Step 4: Add the CSS**

Append to `hearth/web/style.css`:

```css
/* Video editor (spec 2026-07-18): fullscreen overlay, story-viewer
   family. The frame crops via overflow:hidden + absolutely positioned
   video (Task 5 pans/zooms it); the strip hosts filmstrip thumbs, the
   trim window, two handles, and the cover marker. */
#video-editor { position: fixed; inset: 0; z-index: 60;
  background: color-mix(in srgb, var(--ink) 92%, transparent);
  display: flex; flex-direction: column; align-items: center;
  justify-content: center; gap: 14px; padding: 18px; }
.ve-stage { display: grid; place-items: center; max-width: min(92vw, 720px);
  width: 100%; }
.ve-frame { position: relative; overflow: hidden; max-width: 100%;
  max-height: 52vh; border-radius: 12px; background: #000;
  aspect-ratio: 16 / 9; }
.ve-frame video { position: absolute; left: 0; top: 0;
  width: 100%; height: 100%; object-fit: contain; }
.ve-frame.ve-noframes::after { content: "No preview for this format - "
  "trim by time below"; position: absolute; inset: 0; display: grid;
  place-items: center; color: var(--paper); font-size: 13px;
  text-align: center; padding: 12px; }
.ve-strip { position: relative; width: min(92vw, 720px); height: 52px;
  border-radius: 8px; background: color-mix(in srgb, var(--paper) 12%, transparent);
  touch-action: none; }
.ve-track { position: absolute; inset: 0; display: flex; overflow: hidden;
  border-radius: 8px; }
.ve-thumb { height: 100%; flex: 1 1 0; min-width: 0; object-fit: cover; }
.ve-window { position: absolute; top: 0; bottom: 0;
  border: 2px solid var(--paper); border-radius: 6px; pointer-events: none;
  box-shadow: 0 0 0 200vmax color-mix(in srgb, var(--ink) 45%, transparent); }
.ve-handle { position: absolute; top: -6px; bottom: -6px; width: 14px;
  margin-left: -7px; border-radius: 7px; background: var(--paper);
  cursor: ew-resize; z-index: 2; }
.ve-times { color: var(--paper); font-size: 12.5px;
  font-family: "IBM Plex Mono", monospace; }
.ve-chips { display: flex; gap: 6px; }
.ve-chips.ve-disabled { opacity: .35; pointer-events: none; }
.ve-btns { display: flex; gap: 10px; }
.ve-btn { border: 1px solid color-mix(in srgb, var(--paper) 45%, transparent);
  background: transparent; color: var(--paper); font-size: 13.5px;
  padding: 7px 18px; border-radius: 99px; cursor: pointer; }
.ve-btn.ve-done { background: var(--paper); color: var(--ink); }
```

- [ ] **Step 5: Verify**

Run: `node --check hearth\web\app.js` then `.venv\Scripts\python.exe -m pytest tests\test_web_assets.py -q`
Expected: syntax clean; `test_video_editor_wired` PASSES; every pre-existing asset test still passes.

- [ ] **Step 6: Commit**

```bash
git add hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat(web): video editor core - overlay, filmstrip, trim handles, loop preview"
```

---

### Task 5: client — aspect crop (pan/zoom) + cover marker

**Files:**
- Modify: `hearth/web/app.js` (inside `openVideoEditor` from Task 4 — replace the four stubs `cropRect`, `renderCover`, `initCrop`, `restoreCrop`, fill `chips`, add the cover element + pan/zoom handlers)
- Modify: `hearth/web/style.css` (extend the video-editor section)
- Test: `tests/test_web_assets.py` (append)

**Interfaces:**
- Consumes: Task 4's `openVideoEditor` internals (state slots `aspect`, `zoom`, `cx`, `cy`, `coverAbs` already declared).
- Produces: the wire `edit.crop` (normalized rect or null) and `edit.poster_t` actually reflect UI state.

- [ ] **Step 1: Write the failing asset test**

Append to `tests/test_web_assets.py`:

```python
def test_video_editor_crop_and_cover_wired():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    ve = _js_fn_body(js, "openVideoEditor")
    for needle in ('"orig"', '"1:1"', '"9:16"', '"16:9"',
                   "ve-cover", "wheel", "ve-chip"):
        assert needle in ve, needle
    # pinch: a two-pointer distance path exists
    assert "pointers.size === 2" in ve or "pointers.length === 2" in ve
    assert ".ve-cover" in css and ".ve-chip" in css
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests\test_web_assets.py::test_video_editor_crop_and_cover_wired -q`
Expected: FAIL.

- [ ] **Step 3: Implement**

Inside `openVideoEditor`, delete the four stub functions and the `function renderCover() {}` line; add after the `strip.append(...)` line:

```js
  const cover = el("div", "ve-cover");
  cover.title = "Cover frame";
  cover.setAttribute("aria-label", "Cover frame");
  strip.append(cover);
```

Add the aspect chips right after `const chips = el("div", "ve-chips");`:

```js
  const ASPECTS = {"orig": null, "1:1": 1, "9:16": 9 / 16, "16:9": 16 / 9};
  const chipBtns = {};
  for (const key of Object.keys(ASPECTS)) {
    const c = el("button", "ve-chip", key === "orig" ? "Original" : key);
    c.type = "button";
    c.onclick = () => setAspect(key);
    chipBtns[key] = c;
    chips.append(c);
  }
```

Replace the stubs with the real implementations (place them where the stubs were):

```js
  // -- crop model: aspect presets + pan/zoom (spec 2026-07-18). The
  // crop rect is DERIVED (never stored): largest target-aspect rect in
  // the display-oriented frame, shrunk by zoom, centered on (cx, cy),
  // clamped inside. cropRect() is the single source of the wire value
  // AND the preview transform - they cannot disagree.
  function cropRect() {
    const r = ASPECTS[aspect];
    if (r == null || !vw || !vh) return null;
    const baseW = Math.min(1, (r * vh) / vw);
    const baseH = Math.min(1, vw / (r * vh));
    const w = Math.max(0.1, baseW / zoom);
    const h = Math.max(0.1, baseH / zoom);
    const x = Math.min(Math.max(cx - w / 2, 0), 1 - w);
    const y = Math.min(Math.max(cy - h / 2, 0), 1 - h);
    return {x: Math.round(x * 1e6) / 1e6, y: Math.round(y * 1e6) / 1e6,
            w: Math.round(w * 1e6) / 1e6, h: Math.round(h * 1e6) / 1e6};
  }
  function applyCrop() {
    const c = cropRect();
    if (!c) {
      frame.style.aspectRatio = (vw || 16) + " / " + (vh || 9);
      vid.style.cssText = "";
      return;
    }
    frame.style.aspectRatio = (c.w * vw) + " / " + (c.h * vh);
    const W = frame.clientWidth;
    const dw = W / c.w;                       // displayed full-video width
    const dh = dw * vh / vw;
    vid.style.width = dw + "px";
    vid.style.height = dh + "px";
    vid.style.maxWidth = "none"; vid.style.maxHeight = "none";
    vid.style.objectFit = "fill";
    vid.style.left = (-c.x * dw) + "px";
    vid.style.top = (-c.y * dh) + "px";
  }
  function setAspect(key) {
    aspect = key;
    if (key === "orig") { zoom = 1; cx = 0.5; cy = 0.5; }
    for (const [k, b] of Object.entries(chipBtns))
      b.classList.toggle("active", k === aspect);
    applyCrop();
  }
  function initCrop() { setAspect(aspect); }
  function restoreCrop(c) {
    if (!c) { aspect = "orig"; return; }
    // recover the nearest preset from the rect's real aspect
    const ratio = (c.w * vw) / (c.h * vh);
    let best = "orig", err = Infinity;
    for (const [k, r] of Object.entries(ASPECTS)) {
      if (r == null) continue;
      if (Math.abs(r - ratio) < err) { err = Math.abs(r - ratio); best = k; }
    }
    aspect = best;
    const baseW = Math.min(1, (ASPECTS[best] * vh) / vw);
    zoom = Math.min(10, Math.max(1, baseW / c.w));
    cx = c.x + c.w / 2; cy = c.y + c.h / 2;
  }

  // pan (single pointer) + pinch (two pointers) + wheel zoom
  const pointers = new Map();
  frame.addEventListener("pointerdown", (e) => {
    if (aspect === "orig" || degraded) return;
    e.preventDefault();
    frame.setPointerCapture(e.pointerId);
    pointers.set(e.pointerId, {x: e.clientX, y: e.clientY});
  });
  frame.addEventListener("pointermove", (e) => {
    if (!pointers.has(e.pointerId)) return;
    const prev = pointers.get(e.pointerId);
    const cur = {x: e.clientX, y: e.clientY};
    if (pointers.size === 2) {
      const other = [...pointers.entries()]
        .find(([id]) => id !== e.pointerId)[1];
      const d0 = Math.hypot(prev.x - other.x, prev.y - other.y);
      const d1 = Math.hypot(cur.x - other.x, cur.y - other.y);
      if (d0 > 0) zoomBy(d1 / d0);
    } else {
      const c = cropRect();
      if (c) {
        const dw = frame.clientWidth / c.w;
        cx = Math.min(1, Math.max(0, cx - (cur.x - prev.x) / dw));
        cy = Math.min(1, Math.max(0, cy - (cur.y - prev.y) / (dw * vh / vw)));
        applyCrop();
      }
    }
    pointers.set(e.pointerId, cur);
  });
  const dropPointer = (e) => pointers.delete(e.pointerId);
  frame.addEventListener("pointerup", dropPointer);
  frame.addEventListener("pointercancel", dropPointer);
  function zoomBy(f) {
    zoom = Math.min(10, Math.max(1, zoom * f));
    applyCrop();
  }
  frame.addEventListener("wheel", (e) => {
    if (aspect === "orig" || degraded) return;
    e.preventDefault();
    zoomBy(e.deltaY < 0 ? 1.08 : 1 / 1.08);
  }, {passive: false});

  // -- cover marker: draggable within the trim window; seeks the
  // preview so the user sees the exact poster frame.
  function renderCover() {
    cover.style.left = px(coverAbs) + "px";
  }
  cover.addEventListener("pointerdown", (ev) => {
    if (!ev.isPrimary) return;
    ev.preventDefault(); ev.stopPropagation();
    cover.setPointerCapture(ev.pointerId);
    vid.pause();
    const move = (e) => {
      const t = toT(e.clientX - strip.getBoundingClientRect().left);
      coverAbs = Math.min(Math.max(t, start), end);
      vid.currentTime = coverAbs;
      renderCover();
    };
    const up = () => {
      cover.removeEventListener("pointermove", move);
      cover.removeEventListener("pointerup", up);
    };
    cover.addEventListener("pointermove", move);
    cover.addEventListener("pointerup", up);
  });
```

Note: with the cover drag pausing playback, resume on the next handle drag (`up` in `dragHandle` already calls `vid.play()`); that asymmetry is intended — after picking a cover you're looking at the poster frame.

Append CSS to the video-editor section:

```css
.ve-cover { position: absolute; top: -10px; width: 0; height: 0; z-index: 3;
  margin-left: -7px; cursor: grab;
  border-left: 7px solid transparent; border-right: 7px solid transparent;
  border-top: 10px solid var(--me); }
.ve-chip { border: 1px solid color-mix(in srgb, var(--paper) 45%, transparent);
  background: transparent; color: var(--paper); font-size: 12.5px;
  padding: 4px 12px; border-radius: 99px; cursor: pointer; }
.ve-chip.active { background: var(--paper); color: var(--ink); }
.ve-track.ve-blank { background:
  repeating-linear-gradient(45deg, transparent, transparent 8px,
  color-mix(in srgb, var(--paper) 8%, transparent) 8px,
  color-mix(in srgb, var(--paper) 8%, transparent) 16px); }
```

- [ ] **Step 4: Verify**

Run: `node --check hearth\web\app.js` then `.venv\Scripts\python.exe -m pytest tests\test_web_assets.py -q`
Expected: all pass, including both video-editor tests.

- [ ] **Step 5: Commit**

```bash
git add hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat(web): video editor crop (aspect presets + pan/zoom) and cover marker"
```

---

### Task 6: client — composer integrations + copy fixes

**Files:**
- Modify: `hearth/web/app.js:2079-2122` (wall composer: `videoInput.onchange`, `showPreview`, `form.onsubmit`), `:2189-2212` (story `addInput.onchange` + error copy)
- Test: `tests/test_web_assets.py` (append)

**Interfaces:**
- Consumes: `openVideoEditor(file, existing, onClose)` from Tasks 4–5.
- Produces: `/api/post` and `/api/story` uploads carry `video_edit` (JSON string) when an edit exists.

- [ ] **Step 1: Write the failing asset test**

Append to `tests/test_web_assets.py`:

```python
def test_video_editor_composer_integration():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    composer = _js_fn_body(js, "profilePostComposer")
    assert "openVideoEditor" in composer
    assert 'fd.append("video_edit"' in composer
    # the false promise is dead: the note now reflects the edit
    assert "will be trimmed to the story rules on post" not in js
    # story composer routes video picks through the editor too
    assert js.count("openVideoEditor(") >= 3      # def + 2 call sites
    # "coming soon" promise retired (the editor exists now)
    assert "In-app trimming is coming soon" not in js
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests\test_web_assets.py::test_video_editor_composer_integration -q`
Expected: FAIL.

- [ ] **Step 3: Implement — wall composer**

In `profilePostComposer` (app.js:~2025), add a state slot next to `let objectUrls = []`:

```js
  let videoEdit = null;          // wire params from the editor, or null (raw)
```

Replace `videoInput.onchange` (app.js:2083-2086):

```js
  videoInput.onchange = () => {
    if (!videoInput.files.length) { showPreview(); return; }
    photoInput.value = "";                       // one medium, video wins
    openVideoEditor(videoInput.files[0], null, (res) => {
      if (res.action === "cancel") {
        videoInput.value = ""; videoEdit = null; showPreview(); return;
      }
      videoEdit = res.action === "done" ? res.edit : null;
      showPreview();
    });
  };
```

In `showPreview`'s video branch (app.js:2049-2060), replace the note line and make the preview reopen the editor:

```js
      // editor re-entry: the preview is the edit's handle
      v.style.cursor = "pointer";
      v.onclick = () => openVideoEditor(videoFile, videoEdit, (res) => {
        if (res.action === "cancel") return;     // keep current choices
        videoEdit = res.action === "done" ? res.edit : null;
        showPreview();
      });
      const parts = [];
      if (videoEdit) {
        parts.push("trimmed to " + videoEdit.duration.toFixed(1) + "s");
        if (videoEdit.crop) parts.push("cropped");
        if (videoEdit.poster_t > 0) parts.push("cover set");
      }
      // DRAFT copy (August may reword; update the asset pin with it)
      noteSlot.textContent = videoFile.name
        + (parts.length ? " - " + parts.join(" - ")
                        : " - tap the preview to trim or crop");
      noteSlot.hidden = false;
```

In `form.onsubmit` after `if (videoFile) fd.append("video", videoFile);` (app.js:2102):

```js
    if (videoFile && videoEdit)
      fd.append("video_edit", JSON.stringify(videoEdit));
```

And in the post-success reset block (after `videoInput.value = "";`, app.js:2113): add `videoEdit = null;`.

- [ ] **Step 4: Implement — story composer**

Replace `addInput.onchange` (app.js:2189-2212) — the upload body moves into a helper so the editor callback can call it:

```js
  addInput.onchange = () => {
    const file = addInput.files[0];
    if (!file) return;
    const isVideo = !file.type.startsWith("image/");
    if (!isVideo) { uploadStory(file, null); return; }
    openVideoEditor(file, null, (res) => {
      if (res.action === "cancel") { addInput.value = ""; return; }
      uploadStory(file, res.action === "done" ? res.edit : null);
    });
  };
  async function uploadStory(file, edit) {
    const isVideo = !file.type.startsWith("image/");
    addRing.classList.add("busy");
    addRing.textContent = "";
    addName.textContent = isVideo ? "Processing video..." : "Uploading...";
    const fd = new FormData(); fd.append("media", file);
    fd.append("caption", "");
    if (edit) fd.append("video_edit", JSON.stringify(edit));
    try {
      const r = await fetch("/api/story", {method: "POST", body: fd});
      if (r.ok) { renderStories(); return; }
      const body = await r.text();
      if (r.status === 400 && /longer than 15/i.test(body))
        // only reachable on the raw/degraded path (no editor preview)
        alert("That video is longer than 15 seconds and could not be "
          + "previewed for trimming here. Please shorten it and try again.");
      else if (r.status === 413)
        alert("That file is too large.");
      else alert("Could not post story: " + body);
    } catch (e) {
      alert("Could not post story: " + e.message);
    }
    addInput.value = "";
    renderStories();
  }
```

(The old `"(50 MB max)"` alert text is dropped — the cap is 50 or 100 MB by media type now; the node's 413 detail says which.)

- [ ] **Step 5: Verify**

Run: `node --check hearth\web\app.js` then `.venv\Scripts\python.exe -m pytest tests\test_web_assets.py -q`
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add hearth/web/app.js tests/test_web_assets.py
git commit -m "feat(web): editor wired into wall + story composers, honest video copy"
```

---

### Task 7: live UI smoke (UI_E2E=1)

**Files:**
- Create: `tests/test_ui_smoke_video_editor.py`

**Interfaces:**
- Consumes: everything above; `LiveNode` harness from `tests/test_ui_smoke_seen_badge.py`; clip generation pattern from `tests/test_videogate.py`.

- [ ] **Step 1: Write the smoke**

```python
"""UI_E2E=1-gated live smoke for the video editor (spec 2026-07-18):
pick a 30s clip in the wall composer -> the editor opens -> drag the
right handle to a ~10s window -> pick 1:1 -> drag the cover marker ->
Done -> Post -> the SYNCED artifact (fetched decrypted via
/api/post-blob) is ~10s and square, proving client params executed
node-side. Story leg: same clip through the story "+" -> Done with
defaults -> stored plaintext story blob is <=15s.
"""
import os
import subprocess

import imageio_ffmpeg
import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("UI_E2E") != "1",
    reason="live browser smoke; set UI_E2E=1 (needs playwright chromium)")

from hearth.videogate import probe_duration
from tests.test_ui_smoke_seen_badge import LiveNode


def _clip(tmp_path, seconds=30):
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    p = str(tmp_path / "src.mp4")
    subprocess.run([ff, "-f", "lavfi", "-i",
                    f"testsrc=size=640x480:rate=24:duration={seconds}",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", p],
                   check=True, capture_output=True)
    return p


def _drag(page, el_handle, to_x, to_y):
    box = el_handle.bounding_box()
    page.mouse.move(box["x"] + box["width"] / 2,
                    box["y"] + box["height"] / 2)
    page.mouse.down()
    page.mouse.move(to_x, to_y, steps=10)
    page.mouse.up()


def test_editor_trim_crop_cover_end_to_end(tmp_path):
    from playwright.sync_api import sync_playwright
    from PIL import Image
    import io
    import urllib.request

    a = LiveNode(tmp_path / "a", "Anna", "anna-pc")
    try:
        a.start()
        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            page = browser.new_page(viewport={"width": 1280, "height": 900})
            errors = []
            page.on("pageerror", lambda e: errors.append(str(e)))
            page.goto(f"http://127.0.0.1:{a.http_port}/")
            page.wait_for_selector(".fchip")
            page.click('.navlinks button[data-view="me"]')
            page.wait_for_selector(".profile-composer")

            # -- wall leg -----------------------------------------------
            page.set_input_files(
                '.profile-composer input[accept="video/*"]',
                _clip(tmp_path))
            page.wait_for_selector("#video-editor .ve-thumb", timeout=15000)

            strip = page.locator(".ve-strip").bounding_box()
            # right handle sits at 15s of 30s (= mid-strip); drag to 10/30
            _drag(page, page.locator(".ve-h-right"),
                  strip["x"] + strip["width"] * (10 / 30),
                  strip["y"] + strip["height"] / 2)
            page.click('.ve-chip:has-text("1:1")')
            _drag(page, page.locator(".ve-cover"),
                  strip["x"] + strip["width"] * (5 / 30),
                  strip["y"] + 4)
            page.click(".ve-done")
            page.wait_for_selector("#video-editor", state="detached")
            page.click(".profile-composer .postbtn")
            page.wait_for_selector("#profile-wall .block-video", timeout=30000)

            rows = a.node.posts_by(a.node.identity_pub, placement="profile")
            row = [p for p in rows if p["media"] == "video"][0]
            assert row["codec"] == "h264"
            base = f"http://127.0.0.1:{a.http_port}"
            mp4 = urllib.request.urlopen(
                f"{base}/api/post-blob/{row['msg_id']}/{row['blobs'][0]}"
            ).read()
            d = probe_duration(mp4)
            assert 6.0 < d < 13.0        # drag precision, not exactness
            poster = urllib.request.urlopen(
                f"{base}/api/post-blob/{row['msg_id']}/{row['poster']}"
            ).read()
            w, h = Image.open(io.BytesIO(poster)).size
            assert abs(w - h) <= 2       # 1:1 crop landed

            # -- story leg (defaults: 15s window of the 30s source) -----
            page.click("#nav-journal")
            page.wait_for_selector("#stories .story-ring.add")
            page.set_input_files(
                '#stories input[type="file"]', _clip(tmp_path))
            page.wait_for_selector("#video-editor", timeout=15000)
            page.click(".ve-done")
            page.wait_for_selector("#video-editor", state="detached")
            page.wait_for_function(
                "document.querySelectorAll('#stories .story-tile').length >= 2",
                timeout=30000)
            item = [i for g in a.node.stories_view() for i in g["items"]][0]
            smp4 = a.node.store.get_blob(item["media"])
            assert probe_duration(smp4) <= 15.5

            assert not errors, f"console pageerrors: {errors}"
            browser.close()
    finally:
        a.stop()
```

NOTE for the implementer: selector details (`.profile-composer input[accept="video/*"]`, the stories add-input selector, `.block-video` appearing on the wall) must be verified against the running DOM — adjust selectors to what the app actually renders, keeping the assertions (duration window, square poster, codec) exactly as written. If `posts_by` rows lack `poster`, read it from the raw payload via the store accessor used in `tests/test_profile_video.py`.

- [ ] **Step 2: Run it live**

Run: `$env:UI_E2E = "1"; .venv\Scripts\python.exe -m pytest tests\test_ui_smoke_video_editor.py -q`
Expected: PASS (allow up to ~3 min; the 30s encodes at preset slow are the long poles).

- [ ] **Step 3: Commit**

```bash
git add tests/test_ui_smoke_video_editor.py
git commit -m "test(smoke): live editor trim/crop/cover end-to-end, wall + story legs"
```

---

### Task 8: full verification + docs

**Files:**
- Modify: `docs/engineering-notes.md` (append a short section), `ROADMAP.md` (move the editor entry to shipped once released — for now annotate "built, unreleased")

- [ ] **Step 1: Full suite**

Run: `.venv\Scripts\python.exe -m pytest -q`
Expected: everything passes except the known pre-existing `test_ui_smoke_composer.py` y-pin failure (UI_E2E-gated, skipped in the default run anyway). Then the UI_E2E smokes: `$env:UI_E2E = "1"; .venv\Scripts\python.exe -m pytest tests\test_ui_smoke_video_editor.py tests\test_ui_smoke_albums.py -q` — both green.

- [ ] **Step 2: Timing sanity (spec requirement)**

Run a one-off: transcode a 60s 1080p source with a 15s window and time it (`python -c` script using `time.perf_counter` around `transcode_video`). Expected: well under the 60s `_TIMEOUT` on this machine. If it isn't, bump `_TIMEOUT` to 120 in the same commit with a comment citing the measurement.

- [ ] **Step 3: Docs**

Append to `docs/engineering-notes.md` a section describing: client-simulates/node-executes split, the wire format, why the browser is never trusted (gate re-encodes), the preset-slow rider, the codec field, and the honest degraded-mode ladder. Annotate the ROADMAP editor entry "built on main, in the next release".

- [ ] **Step 4: Commit**

```bash
git add docs/engineering-notes.md ROADMAP.md
git commit -m "docs: video editor engineering notes + ROADMAP status"
```

---

## Self-review notes (done at plan time)

- **Spec coverage:** editor UI (T4/T5), degraded mode (T4 + T6 story copy), wire format + validation (T1/T3), gate execution + rotation pin (T1), story cap fix + shared sniff (T3), copy fixes (T6), riders preset/codec (T1/T2), mixed-version = release-time `min_core_for_web` (out of code scope, noted in ROADMAP), tests incl. portrait + large-source timing (T1/T8).
- **Deliberate deviations:** none from the spec; smoke asserts ranges not exact values (drag precision).
- **Type consistency:** `video_edit` dict keys identical across editor `buildEdit()`, API JSON, `validate_video_edit`, tests. `onClose` contract identical between T4 definition and T6 call sites.
