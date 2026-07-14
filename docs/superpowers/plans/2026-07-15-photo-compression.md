# Photo Compression on Upload + 10 MB Blob Cap — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Big phone photos upload successfully: a 50 MB raw image is accepted at the HTTP door, compressed server-side (2560 px long edge, JPEG quality ladder), and stored under a raised 10 MB protocol blob cap; animated GIFs pass through untouched.

**Architecture:** Mirrors the existing video pattern (`MAX_VIDEO_UPLOAD` raw in → transcode gate → output bound by `MAX_BLOB_BYTES`). A new `imagegate.transcode_photo` runs inside `node.compose_post` / `node.compose_dm` before `encrypt_blob`, so every composer path (wall post, deck grow, album add, DM) is gated in one place and the API stays a dumb door. `MAX_BLOB_BYTES` 5→10 MB is a protocol change shipped in a single release (August's decision): peers on ≤0.3.10 refuse >5 MB blobs at sync until they auto-update — documented, accepted window.

**Tech Stack:** Python 3.12, FastAPI, Pillow (already a dependency), pytest.

**Spec:** `docs/superpowers/specs/2026-07-15-photo-compression-design.md` (approved 2026-07-15). One addendum made during planning, flagged to August: **DM photos get the same gate** — `/api/dm` shares the raw-photo path and would otherwise die at the store cap with a confusing error.

## Global Constraints

- Suite must stay green: `.venv\Scripts\python.exe -m pytest -q` from repo root (791 passed / 6 skipped baseline before this work).
- Commit messages: NO AI/Co-Authored-By trailers (repo policy; README discloses AI assistance instead).
- Console prints ASCII only (Windows cp1252).
- Constants live in `hearth/messages.py`; the gate lives in `hearth/imagegate.py`; comments explain constraints, not narration.
- Error message strings are part of the deliverable — copy them exactly as written here.

---

### Task 1: Raise the protocol blob cap, add the image upload cap

**Files:**
- Modify: `hearth/messages.py:51-54`
- Modify: `hearth/store.py:209-210`
- Test: `tests/test_store.py` (append)

**Interfaces:**
- Produces: `MAX_BLOB_BYTES == 10 * 1024 * 1024` and new
  `MAX_IMAGE_UPLOAD == 50 * 1024 * 1024`, both importable from
  `hearth.messages`. Tasks 2–4 import these.

- [ ] **Step 1: Write the failing boundary test**

Append to `tests/test_store.py` (it already has a store fixture pattern — reuse the file's existing store constructor, matching however its first test builds one):

```python
def test_blob_cap_is_10mb(tmp_path):
    from hearth.messages import MAX_BLOB_BYTES
    from hearth.store import Store
    assert MAX_BLOB_BYTES == 10 * 1024 * 1024
    s = Store(tmp_path / "s.db")
    s.put_blob(b"x" * MAX_BLOB_BYTES)          # exactly at cap: accepted
    with pytest.raises(ValueError, match="10 MB"):
        s.put_blob(b"x" * (MAX_BLOB_BYTES + 1))
```

Adjust the `Store(...)` construction to match the file's existing fixture/convention if it differs (open `tests/test_store.py` and copy how its first test creates a store). Ensure `import pytest` exists at the top.

- [ ] **Step 2: Run it to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_store.py::test_blob_cap_is_10mb -q`
Expected: FAIL — `MAX_BLOB_BYTES == 10 * 1024 * 1024` assertion (it is 5 MB today).

- [ ] **Step 3: Bump the constants**

In `hearth/messages.py` replace lines 51–54:

```python
MAX_BLOB_BYTES = 10 * 1024 * 1024      # PROTOCOL: store + sync + doors all
                                       # key off this. Raised 5->10 MB in
                                       # 0.3.11; peers on <=0.3.10 refuse
                                       # bigger blobs until they update
                                       # (accepted single-release window).
MAX_IMAGE_UPLOAD = 50 * 1024 * 1024    # raw image upload cap, checked before
                                       # the photo gate (gate output is still
                                       # bound by MAX_BLOB_BYTES)
MAX_VIDEO_UPLOAD = 100 * 1024 * 1024   # raw upload cap, checked before the
                                       # transcode gate (transcoded output is
                                       # still bound by MAX_BLOB_BYTES)
```

In `hearth/store.py` line 210, change the message:

```python
            raise ValueError("blob exceeds 10 MB cap")
```

- [ ] **Step 4: Run the new test, then the whole suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_store.py::test_blob_cap_is_10mb -q` → PASS.
Run: `.venv\Scripts\python.exe -m pytest -q` → expect a small number of failures ONLY in tests that assert the old "5 MB" message or boundary; update those assertions to the new constant/message (do not delete the tests). Anything else failing is a real regression — stop and investigate.

- [ ] **Step 5: Commit**

```bash
git add hearth/messages.py hearth/store.py tests/
git commit -m "feat(blobs): protocol blob cap 5->10 MB + MAX_IMAGE_UPLOAD door constant - single-release window documented at the constant"
```

---

### Task 2: `imagegate.transcode_photo` — the compression gate

**Files:**
- Modify: `hearth/imagegate.py`
- Test: `tests/test_imagegate.py` (append)

**Interfaces:**
- Consumes: `MAX_BLOB_BYTES` from `hearth.messages` (Task 1).
- Produces: `transcode_photo(data: bytes, cap: int = PHOTO_CAP) -> bytes`
  raising `ValueError` on non-images and over-cap GIFs; constants
  `PHOTO_MAX = 2560`, `PHOTO_CAP = MAX_BLOB_BYTES - 64`. Task 3 calls
  `transcode_photo(p)` per photo.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_imagegate.py` (helpers `png_bytes` and `animated_gif_bytes` already exist at the top of the file):

```python
import os

from hearth.imagegate import PHOTO_MAX, PHOTO_CAP, transcode_photo


def noise_jpeg_bytes(w, h, quality=95):
    # Random noise compresses terribly - the cheapest way to make a
    # genuinely multi-megabyte JPEG without a fixture file.
    img = Image.frombytes("RGB", (w, h), os.urandom(w * h * 3))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=quality)
    return buf.getvalue()


def test_big_photo_compressed_under_cap_and_downscaled():
    src = noise_jpeg_bytes(4000, 3000)
    assert len(src) > 5 * 1024 * 1024        # premise: bigger than the OLD cap
    out = transcode_photo(src)
    assert len(out) <= PHOTO_CAP
    img = Image.open(io.BytesIO(out))
    assert img.format == "JPEG"
    assert max(img.size) <= PHOTO_MAX
    assert abs(img.size[0] / img.size[1] - 4 / 3) < 0.05   # aspect kept


def test_orientation_baked_in_and_exif_stripped():
    img = Image.new("RGB", (100, 50), (10, 200, 30))
    exif = Image.Exif()
    exif[274] = 6                             # Orientation: rotate 90 CW
    buf = io.BytesIO()
    img.save(buf, format="JPEG", exif=exif)
    out = transcode_photo(buf.getvalue())
    outimg = Image.open(io.BytesIO(out))
    assert outimg.size == (50, 100)           # transpose applied to pixels
    assert dict(outimg.getexif()) == {}       # no metadata carried (incl. GPS)


def test_png_screenshot_stays_png():
    out = transcode_photo(png_bytes(800, 600))
    assert Image.open(io.BytesIO(out)).format == "PNG"


def test_never_upscales():
    out = transcode_photo(png_bytes(64, 64))
    assert Image.open(io.BytesIO(out)).size == (64, 64)


def test_animated_gif_passes_through_byte_identical():
    src = animated_gif_bytes()
    assert transcode_photo(src) == src


def test_oversized_gif_rejected_honestly():
    src = animated_gif_bytes()
    with pytest.raises(ValueError, match="animations can't be compressed"):
        transcode_photo(src, cap=len(src) - 1)


def test_non_image_rejected():
    with pytest.raises(ValueError, match="not an image"):
        transcode_photo(b"definitely not pixels" * 100)


def test_dimension_ladder_when_quality_floor_is_not_enough():
    # A tiny artificial cap forces the gate past q55 into halving the
    # dimensions - output must still be a valid image under the cap.
    out = transcode_photo(noise_jpeg_bytes(800, 600), cap=15_000)
    assert len(out) <= 15_000
    img = Image.open(io.BytesIO(out))
    assert img.format == "JPEG"
    assert max(img.size) <= 400               # at least one halving happened
```

(`pytest` is already imported at the top of the file.)

- [ ] **Step 2: Run to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_imagegate.py -q`
Expected: new tests FAIL with `ImportError: cannot import name 'transcode_photo'`; the four pre-existing tests still PASS.

- [ ] **Step 3: Implement the gate**

In `hearth/imagegate.py`: change the import line to include `ImageOps`, add the constants and function below after the existing `transcode`. Extend the module docstring's final sentence so the honesty note names the new exception, replacing the last sentence (`This holds for images ... never as HTML).`) with:

```
This holds for images
uploaded through this node; over gossip a modified peer can still
reference raw image bytes behind a hash, so the viewer's browser image
decoder is still exposed to peer-supplied bytes (served with nosniff,
never as HTML). Post/DM photos go through transcode_photo below, with
one deliberate exception: animated GIFs pass through raw so animation
survives -- for that one format the local decoder-exploit surface is
accepted, unchanged from the pre-gate behavior.
```

```python
from PIL import Image, ImageOps, UnidentifiedImageError
```

```python
PHOTO_MAX = 2560                       # long edge for post/DM photos
_PHOTO_QUALITIES = (85, 75, 65, 55)    # JPEG ladder before dimensions halve
PHOTO_CAP = MAX_BLOB_BYTES - 64        # encrypt_blob adds nonce+tag (28 B);
                                       # 64 B margin keeps ciphertext under
                                       # the store's MAX_BLOB_BYTES check


def transcode_photo(data: bytes, cap: int = PHOTO_CAP) -> bytes:
    """Post/DM photo gate: orientation baked in, metadata (EXIF/GPS)
    dropped, downscaled to PHOTO_MAX, recompressed until the result fits
    `cap` (so the encrypted blob fits MAX_BLOB_BYTES). PNG input stays
    PNG when the lossless re-encode fits (screenshots stay crisp);
    everything else lands as JPEG. Animated GIFs pass through raw --
    animation preserved, cannot be compressed here, over-cap refused."""
    if data[:4] == b"GIF8":
        if len(data) > cap:
            raise ValueError("animated GIF exceeds the 10 MB cap - "
                             "animations can't be compressed")
        return data
    try:
        img = Image.open(io.BytesIO(data))
        img.load()                       # force decode (raises on truncated)
    except (UnidentifiedImageError, OSError, ValueError,
            Image.DecompressionBombError):
        raise ValueError("not an image")
    src_png = img.format == "PNG"        # .format is lost after transpose
    if getattr(img, "is_animated", False):
        img.seek(0)                      # first frame (animated webp etc.)
    img = ImageOps.exif_transpose(img)   # bake orientation BEFORE exif drops
    if max(img.size) > PHOTO_MAX:
        img.thumbnail((PHOTO_MAX, PHOTO_MAX), Image.LANCZOS)
    if src_png:
        out = io.BytesIO()
        img.save(out, format="PNG")      # re-encode: no source chunks carried
        if out.tell() <= cap:
            return out.getvalue()
    rgb = img
    if rgb.mode != "RGB":
        bg = Image.new("RGB", rgb.size, (255, 255, 255))
        rgba = rgb.convert("RGBA")
        bg.paste(rgba, mask=rgba.split()[-1])
        rgb = bg
    while True:
        for q in _PHOTO_QUALITIES:
            out = io.BytesIO()
            rgb.save(out, format="JPEG", quality=q, optimize=True)
            if out.tell() <= cap:
                return out.getvalue()
        w, h = rgb.size
        if max(w, h) <= 64:              # unreachable for any real photo;
            raise ValueError("image cannot fit the blob cap")
        rgb = rgb.resize((max(1, w // 2), max(1, h // 2)), Image.LANCZOS)
```

Add the messages import at the top of the file:

```python
from .messages import MAX_BLOB_BYTES
```

- [ ] **Step 4: Run the imagegate tests**

Run: `.venv\Scripts\python.exe -m pytest tests/test_imagegate.py -q`
Expected: ALL PASS (new + the four pre-existing avatar tests).

- [ ] **Step 5: Commit**

```bash
git add hearth/imagegate.py tests/test_imagegate.py
git commit -m "feat(imagegate): transcode_photo - 2560px/q85 ladder to fit the blob cap, EXIF/GPS stripped, PNG-stays-PNG, animated GIF raw passthrough w/ honest over-cap refusal"
```

---

### Task 3: Gate wiring in `node.compose_post` and `node.compose_dm`

**Files:**
- Modify: `hearth/node.py:19` (import), `hearth/node.py:523` (compose_post photos branch), `hearth/node.py:1565` (compose_dm)
- Test: `tests/test_node.py` (append), plus fixture migration across `tests/`

**Interfaces:**
- Consumes: `transcode_photo` from Task 2.
- Produces: `compose_post`/`compose_dm` accept raw camera bytes and store
  gated bytes; junk photo bytes now raise `ValueError("not an image")`
  (surfaced as 400 by the API's `_400` wrapper). Task 4's API tests rely
  on this.

- [ ] **Step 1: Write the failing integration tests**

Append to `tests/test_node.py`, reusing that file's existing node fixture convention (open the file; its first test builds a node — copy that construction) and the helpers from `tests/test_imagegate.py`:

```python
def test_compose_post_gates_photos(node_factory_or_fixture, tmp_path):
    import io as _io
    from PIL import Image as _Image
    from tests.test_imagegate import noise_jpeg_bytes

    n = ...  # build a node exactly as this file's first test does
    big = noise_jpeg_bytes(4000, 3000)
    assert len(big) > 5 * 1024 * 1024
    mid = n.compose_post("beach", photos=[big])
    feed = n.feed()
    ref = feed[0]["blobs"][0]
    plain = n.post_blob(mid, ref)
    assert plain != big                        # gate re-encoded
    img = _Image.open(_io.BytesIO(plain))
    assert img.format == "JPEG" and max(img.size) <= 2560


def test_compose_post_gif_survives_byte_identical():
    from tests.test_imagegate import animated_gif_bytes
    n = ...  # same construction
    gif = animated_gif_bytes()
    mid = n.compose_post("loop", photos=[gif])
    ref = n.feed()[0]["blobs"][0]
    assert n.post_blob(mid, ref) == gif


def test_compose_post_rejects_junk_photo_bytes():
    n = ...  # same construction
    with pytest.raises(ValueError, match="not an image"):
        n.compose_post("x", photos=[b"not-pixels"])


def test_compose_dm_gates_photos():
    from tests.test_imagegate import noise_jpeg_bytes
    a, b = ...  # build two friended nodes as this file's DM tests do
    big = noise_jpeg_bytes(4000, 3000)
    mid = a.compose_dm(b.identity_pub, "pic", photos=[big])
    thread = a.dm_thread(b.identity_pub)
    ref = thread[-1]["blobs"][0]
    plain = a.dm_blob(mid, ref)
    assert plain is not None and plain != big and len(plain) < len(big)
```

The `...` constructions are the ONLY intentional gaps: copy them from
the neighboring tests in the same file (node construction and the
two-friended-nodes DM setup are established patterns there). Everything
else lands as written.

- [ ] **Step 2: Run to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_node.py -q -k "gates_photos or gif_survives or rejects_junk"`
Expected: FAIL — blobs currently store raw input, junk bytes are accepted.

- [ ] **Step 3: Wire the gate**

`hearth/node.py` line 19, extend the import:

```python
from .imagegate import AVATAR_MAX, BANNER_MAX, transcode, transcode_photo
```

Line 523 (`compose_post`, photos branch), gate before encrypt:

```python
            gated = [transcode_photo(p) for p in photos]   # raises ValueError
            refs = [self.store.put_blob(encrypt_blob(key, p)) for p in gated]
```

Line 1565 (`compose_dm`), same shape:

```python
        gated = [transcode_photo(p) for p in photos]       # raises ValueError
        refs = [self.store.put_blob(encrypt_blob(key, p)) for p in gated]
```

- [ ] **Step 4: Migrate existing photo fixtures**

Run: `.venv\Scripts\python.exe -m pytest -q`

Many existing tests pass junk bytes (e.g. `b"photo"`) as photos — the gate now rejects those with `ValueError("not an image")`. Fix by RECIPE, not case-by-case judgment:

1. Grep the failures for `compose_post(`/`compose_dm(`/photo fixtures.
2. Where a test just needs "a photo", swap junk bytes for
   `png_bytes(8, 8)` (import the helper from `tests.test_imagegate`, or
   inline the 3-line equivalent where import ergonomics are poor).
3. Where a test asserts the stored/decrypted blob EQUALS the input
   (e.g. `tests/test_node.py:28`), use `animated_gif_bytes()` as the
   fixture if byte-identity is the point being tested (GIFs pass through
   untouched), or relax to "decrypts to a valid image" if the point is
   decryptability. Prefer the GIF swap — it keeps the stronger assertion.
4. Do NOT weaken any security assertion (ciphertext != plaintext etc.).

Expected end state: full suite green.

- [ ] **Step 5: Commit**

```bash
git add hearth/node.py tests/
git commit -m "feat(photos): compose_post/compose_dm run the photo gate before encrypt - camera-size uploads land compressed, junk bytes refused; test fixtures now real images (GIF where byte-identity is the assertion)"
```

---

### Task 4: API doors, web copy, version bump

**Files:**
- Modify: `hearth/api.py:19` (import), `:372-373`, `:484-485`, `:577-578`, `:606-607`
- Modify: `hearth/web/app.js:2133`
- Modify: `hearth/__init__.py:2`, `hearth/web/VERSION`, `ROADMAP.md`
- Test: `tests/test_api.py` (append; if door tests live elsewhere, follow where existing 413 tests sit — grep `413` under `tests/`)

**Interfaces:**
- Consumes: `MAX_IMAGE_UPLOAD` (Task 1); gate behavior via node (Task 3).
- Produces: image doors accept ≤50 MB raw; error copy below is final.

- [ ] **Step 1: Write the failing door tests**

Append to the file where existing `/api/post` 413 tests live (grep: `.venv\Scripts\python.exe -m pytest --collect-only -q | findstr 413` or `rg "413" tests/` — follow that file's client fixture):

```python
def test_big_photo_upload_now_accepted(client):
    from tests.test_imagegate import noise_jpeg_bytes
    big = noise_jpeg_bytes(4000, 3000)
    assert len(big) > 5 * 1024 * 1024
    r = client.post("/api/post", data={"text": "big", "scope": "kreds"},
                    files=[("photos", ("p.jpg", big, "image/jpeg"))])
    assert r.status_code == 200


def test_image_over_upload_cap_413(client):
    from hearth.messages import MAX_IMAGE_UPLOAD
    r = client.post("/api/post", data={"text": "too big", "scope": "kreds"},
                    files=[("photos", ("p.jpg",
                            b"\xff\xd8" + b"x" * MAX_IMAGE_UPLOAD,
                            "image/jpeg"))])
    assert r.status_code == 413
    assert "50 MB" in r.text
```

Match the surrounding tests' client fixture name and post shape exactly (scope/data fields may differ slightly — copy a neighboring `/api/post` test's call and change only the photo bytes).

- [ ] **Step 2: Run to verify the first fails**

Run: `.venv\Scripts\python.exe -m pytest <that file> -q -k "big_photo or upload_cap"`
Expected: `test_big_photo_upload_now_accepted` FAILS with 413 (door still checks `MAX_BLOB_BYTES`); the second may already pass by accident — fine.

- [ ] **Step 3: Swap the four doors**

`hearth/api.py` line 19: add `MAX_IMAGE_UPLOAD` to the `from .messages import (...)` list.

Line 372–373 (`/api/post` photos):

```python
            if len(data) > MAX_IMAGE_UPLOAD:
                raise HTTPException(413, "photo exceeds the 50 MB upload cap")
```

Line 484–485 (`/api/profile` avatar/banner):

```python
                if len(data) > MAX_IMAGE_UPLOAD:
                    raise HTTPException(413, "image exceeds the 50 MB upload cap")
```

Line 577–578 (`/api/dm` photos):

```python
            if len(data) > MAX_IMAGE_UPLOAD:
                raise HTTPException(413, "photo exceeds the 50 MB upload cap")
```

Line 606–607 (`/api/story` media — images AND video stories now share the
50 MB raw door; both transcode gates bound the output, and story videos
were previously capped at a 5 MB raw upload, which this quietly fixes):

```python
        if len(data) > MAX_IMAGE_UPLOAD:
            raise HTTPException(413, "media exceeds the 50 MB upload cap")
```

`hearth/web/app.js` line 2133:

```javascript
        alert("That file is too large (50 MB max).");
```

- [ ] **Step 4: Version + ROADMAP**

- `hearth/__init__.py`: `__version__ = "0.3.11"`
- `hearth/web/VERSION`: `0.3.11` (lockstep rule)
- `ROADMAP.md`: add a shipped-note covering: photo gate (2560/q85 ladder, EXIF/GPS stripped, PNG-stays-PNG, GIF passthrough), blob cap 5→10 MB with the honest mixed-version window ("photos posted from 0.3.11 may not display on ≤0.3.10 peers until they update"), 50 MB upload door, story-video raw door 5→50 MB side effect.

- [ ] **Step 5: Run the full suite**

Run: `.venv\Scripts\python.exe -m pytest -q`
Expected: green. `tests/test_web_assets.py` may pin the old alert copy — if it fails, update the pinned string to the new copy.

- [ ] **Step 6: Commit**

```bash
git add hearth/api.py hearth/web/app.js hearth/__init__.py hearth/web/VERSION ROADMAP.md tests/
git commit -m "feat(photos): image doors accept 50 MB raw (gate bounds output), story raw door rides along, copy refreshed; version 0.3.11"
```

---

## Self-Review Notes (done at planning time)

- Spec coverage: constants (T1), gate incl. GIF/EXIF/PNG/ladder (T2), wiring in compose paths (T3), doors + copy + release bookkeeping (T4). DM gate is a planning addendum — flagged in the header and to August.
- The `...` node constructions in Task 3 are deliberate copy-from-neighboring-test instructions (fixture conventions vary per file), not placeholders for unwritten design.
- Type consistency: `transcode_photo(data, cap=PHOTO_CAP)` is the only new public callable; T3/T4 use it exactly as T2 defines it.
