# Kreds Profile Canvas Slice 3c (Video Blocks) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add video blocks to the profile canvas — a `placement=profile` post carrying a transcoded (story gate), per-recipient-encrypted video, rendered inline with a poster + controls.

**Architecture:** Reuse `videogate.transcode_video` (≤15s/720p/≤5MB → mp4 + poster). Encrypt the mp4 AND poster with the post's content key (same path as photos), so a video block is scope-encrypted like any post. New `media`/`poster` payload fields on `KIND_POST`. Front-end video attach + `<video controls>` render. No new crypto, no story-pipeline change.

**Tech Stack:** Python 3.12, sqlite3, FastAPI, `imageio-ffmpeg` (already a dep, used by videogate/stories), pytest; vanilla-JS client; `node --check`.

**Spec:** `docs/superpowers/specs/2026-07-07-kreds-profile-video-design.md`

## Global Constraints

- Branch: `kreds-profile-video` off `main` (already created + checked out — do NOT re-branch).
- Quality over shortcuts. Test runner: `timeout 180 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3` (video transcode is slower — allow 180s); full suite green each commit (TOR_E2E skip; the two prior flakes are fixed — keep them fixed). `node --check hearth/web/app.js` clean. ASCII-only Python prints.
- REUSE `videogate.transcode_video` exactly (untrusted video only ever rendered after our transcode). Do NOT reinvent or change the gate/story pipeline.
- A video block's mp4 AND poster are `encrypt_blob(content_key, ...)` then `put_blob` — per-recipient encrypted like photos (NOT plaintext like stories). One video per block. Playback: poster + `controls`, NO autoplay.
- `media` enum `("photo","video")`, missing = `photo` (back-compat). A video post: exactly 1 blob (the mp4 ref) + a hex64 `poster`. A photo post: no `poster`.
- `MAX_VIDEO_UPLOAD` raw-input cap enforced in `/api/post` BEFORE the gate (over → 413); gate `ValueError` (too long / not a video / >5MB) → 400.
- Honesty guard: no receipts popover.

---

### Task 1: `media`/`poster` on posts + video compose path + API

**Files:**
- Modify: `hearth/messages.py` (`make_post` media+poster; validate)
- Modify: `hearth/node.py` (`compose_post` video path; `_decrypt_post_row` media+poster)
- Modify: `hearth/api.py` (`/api/post` video field + `MAX_VIDEO_UPLOAD`)
- Test: `tests/test_profile_video.py` (new)

**Interfaces:**
- Produces: `make_post(..., media="photo", poster=None)`; `compose_post(..., video=None)` → video block; wall rows gain `media`/`poster`; `/api/post` accepts `video`.

- [ ] **Step 1: Branch exists — skip; start at Step 2.**

- [ ] **Step 2: Failing tests** — `tests/test_profile_video.py` (copy the `clip(seconds)` + `png()` helpers from `tests/test_node_story.py`):

```python
import io, subprocess, tempfile, os
import imageio_ffmpeg, pytest
from PIL import Image
from fastapi.testclient import TestClient
from hearth.api import build_app
from hearth.node import HearthNode

def clip(seconds=2):
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    p = os.path.join(tempfile.mkdtemp(), "c.mp4")
    subprocess.run([ff, "-f", "lavfi", "-i",
        f"testsrc=size=480x360:rate=24:duration={seconds}",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", p],
        check=True, capture_output=True)
    return open(p, "rb").read()

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

def test_video_scope_encrypted(two_friends_kreds_only_and_outsider):
    ...  # reuse the scoped-posts pattern: an out-of-audience friend can't decrypt the video block

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
```

Match the real scoped-posts multi-node idiom for `test_video_scope_encrypted` (mirror `tests/test_profile_posts.py`'s inner/kreds visibility test).

- [ ] **Step 3: Run — expect failure.**

- [ ] **Step 4: messages.py — make_post + validate**

```python
def make_post(device, scope, body_nonce, body_ct, wraps, blob_refs=(),
              created_at=None, expires_at=None, placement="journal",
              media="photo", poster=None) -> SignedMessage:
    return device.sign_message({
        "kind": KIND_POST, "scope": scope, "body_nonce": body_nonce,
        "body_ct": body_ct, "wraps": wraps, "blobs": list(blob_refs),
        "created_at": _now(created_at), "expires_at": expires_at,
        "placement": placement, "media": media, "poster": poster,
    })
```

In `validate_payload`'s `KIND_POST` block, after the existing `blobs` check and before the `expires_at` check:

```python
        media = p.get("media", "photo")
        if media not in ("photo", "video"):
            return False, "bad media"
        poster = p.get("poster")
        if media == "video":
            if len(blobs) != 1:
                return False, "video needs one blob"
            if not _is_hex64(poster):
                return False, "bad poster"
        elif poster is not None:
            return False, "poster on non-video"
```

- [ ] **Step 5: node.py — `compose_post` video path + row fields**

In `compose_post`, add `video=None` to the signature; after computing `pubs`/`created_at`/`expires_at`/`aad`/`key`, branch on video BEFORE the photo path:

```python
    def compose_post(self, text: str, scope: str = "kreds",
                     photos=(), expires_seconds=None,
                     placement: str = "journal", video=None) -> str:
        if scope not in ("inner", "kreds"):
            raise ValueError("scope must be inner or kreds")
        if placement not in ("journal", "profile"):
            raise ValueError("placement must be journal or profile")
        pubs = self._scope_device_pubs(scope)
        created_at = time.time()
        expires_at = (created_at + expires_seconds
                      if expires_seconds is not None else None)
        aad = post_aad(self.identity_pub, scope, created_at)
        key = new_content_key()
        if video is not None:
            mp4, poster_png = transcode_video(video)      # story gate; raises ValueError
            vref = self.store.put_blob(encrypt_blob(key, mp4))
            pref = self.store.put_blob(encrypt_blob(key, poster_png))
            nonce, ct = encrypt_body(key, {"text": text, "blobs": [vref]}, aad)
            wraps = wrap_key(key, pubs, aad)
            mid = self._publish(make_post(self.device, scope, nonce, ct, wraps,
                                          [vref], created_at, expires_at,
                                          placement=placement, media="video", poster=pref))
            self._cache_message_key(mid, key)
            return mid
        refs = [self.store.put_blob(encrypt_blob(key, p)) for p in photos]
        nonce, ct = encrypt_body(key, {"text": text, "blobs": refs}, aad)
        wraps = wrap_key(key, pubs, aad)
        mid = self._publish(make_post(self.device, scope, nonce, ct, wraps,
                                      refs, created_at, expires_at,
                                      placement=placement))
        self._cache_message_key(mid, key)
        return mid
```

`transcode_video` is already imported (`node.py:17`). In `_decrypt_post_row`'s returned dict, add:

```python
            "media": p.get("media", "photo"),
            "poster": p.get("poster"),
```

- [ ] **Step 6: api.py — `/api/post` video field + input cap**

Add near the other caps (define `MAX_VIDEO_UPLOAD = 100 * 1024 * 1024` in api.py or messages.py):

```python
    @app.post("/api/post")
    async def post(text: str = Form(""), scope: str = Form("kreds"),
                   expires_seconds: str = Form(""),
                   placement: str = Form("journal"),
                   photos: List[UploadFile] = File(default=[]),
                   video: UploadFile = File(default=None)):
        expiry = float(expires_seconds) if expires_seconds.strip() else None
        if video is not None:
            vbytes = await video.read()
            if len(vbytes) > MAX_VIDEO_UPLOAD:
                raise HTTPException(413, "video exceeds upload cap")
            mid = _400(lambda: node.compose_post(text, scope, (), expiry,
                                                 placement=placement, video=vbytes))
            return {"msg_id": mid}
        blobs = []
        for up in photos:
            data = await up.read()
            if len(data) > MAX_BLOB_BYTES:
                raise HTTPException(413, "photo exceeds 5 MB cap")
            blobs.append(data)
        mid = _400(lambda: node.compose_post(text, scope, blobs, expiry,
                                             placement=placement))
        return {"msg_id": mid}
```

- [ ] **Step 7: Run tests + full suite** (allow 180s). All pass.

- [ ] **Step 8: Commit**

```powershell
git add hearth/messages.py hearth/node.py hearth/api.py tests/test_profile_video.py
git commit -m "feat: video blocks - media/poster on posts, encrypted transcoded video via the story gate, /api/post video"
```

---

### Task 2: Composer video attach + `<video>` render (front-end)

**Files:**
- Modify: `hearth/web/app.js` (`renderBlock` video branch; composer video picker; skip grid for video)
- Modify: `hearth/web/style.css` (`.block-video`)
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: `p.media`/`p.poster`/`p.blobs[0]`; `/api/post` `video` field; `/api/post-blob`.

- [ ] **Step 1: Failing asset test** — append to `tests/test_web_assets.py`:

```python
def test_video_block_render_and_composer():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert 'p.media === "video"' in js or "p.media==='video'" in js
    assert "createElement(\"video\")" in js or "createElement('video')" in js
    assert "autoplay" not in js.split("renderBlock")[1][:1200]   # no autoplay in the block render
    assert 'accept="video/*"' in js or "accept='video/*'" in js  # composer video picker
    assert "block-video" in css
```

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: `renderBlock` — video branch** (before the photo `if (p.blobs...)`):

```javascript
  if (p.media === "video" && p.blobs && p.blobs.length) {
    const wrap = el("div", "block-video");
    const v = document.createElement("video");
    v.controls = true; v.playsInline = true; v.preload = "metadata";
    if (p.poster) v.poster = "/api/post-blob/" + p.msg_id + "/" + p.poster;
    const src = document.createElement("source");
    src.src = "/api/post-blob/" + p.msg_id + "/" + p.blobs[0];
    v.append(src); wrap.append(v); block.append(wrap);
  } else if (p.blobs && p.blobs.length) {
    // ... existing photo branch (photoGridClass) unchanged ...
  }
```

And in the arrange-mode grid picker (Slice 3b), gate it on photo blocks only — change `if (p.blobs && p.blobs.length > 1)` to `if (p.media !== "video" && p.blobs && p.blobs.length > 1)` so a video block shows no grid selector.

- [ ] **Step 4: Composer video picker** — in `profilePostComposer`, add a labelled video `<input type="file" accept="video/*">` (keyboard-reachable via `visually-hidden` + a label, like the photo input). On submit: if a video file is chosen, send it as `video` in the FormData (and do NOT send photos); else the existing photo/text path. Show a "video attached: <name>" cue. Read the current submit handler and branch cleanly (video wins over photos). Keep the existing `r.ok` handling.

- [ ] **Step 5: style.css** — `.block-video video { width: 100%; border-radius: 12px; display: block; }`

- [ ] **Step 6: Run asset tests + node --check + full suite** — all pass.

- [ ] **Step 7: Commit**

```powershell
git add hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat: video block render (poster + controls, no autoplay) + composer video attach; grid picker skipped for video"
```

---

### Task 3: Integration smoke + docs

**Files:**
- Test: `tests/test_profile_video_integration.py` (new)
- Modify: `README.md`, `ROADMAP.md`

- [ ] **Step 1: Two-node integration test** (mirror `tests/test_profile_grids_integration.py`, use `clip()`): A posts a video block (scope=kreds); sync A→B (B is an in-audience friend); assert B's `profile_view(A)["wall"]` block has `media=="video"` and B can `post_blob`-decrypt the mp4 + poster. Add an out-of-audience case (scope=inner, B not inner) → B's wall doesn't include it (can't decrypt). Terminates; under a generous timeout (transcode).

- [ ] **Step 2: Full suite (180s) + node --check** — all pass; run twice to confirm the fixed flakes stay green.

- [ ] **Step 3: Playwright/HTTP smoke (record)** — isolated node + a friend on FREE ports (not demo). Attach a short video in the profile composer → it transcodes + posts a video block; the block renders a `<video>` with poster + controls (no autoplay); it plays; a friend sees + plays it after sync; a >15s or non-video file gives a clear error (400); photo/text/grid blocks still render; the arrange grid picker doesn't appear on the video block. Record observations. Playwright installed; else HTTP + DOM assertions, say so.

- [ ] **Step 4: README + ROADMAP** — document 3c (video blocks: story-gate transcode, per-recipient encrypted mp4+poster, poster+controls playback, composer video attach). Note the tractable three (DnD/grids/video) are DONE; only the deferred heavy two (split-columns, versioned edit) remain on the block builder. Increment on the profile-canvas feature.

- [ ] **Step 5: Commit**

```powershell
git add tests/test_profile_video_integration.py README.md ROADMAP.md
git commit -m "test+docs: video blocks (3c) integration + ship notes; tractable three complete"
```

---

## Completion

After Task 3: whole-branch review (superpowers:requesting-code-review) — focus: untrusted video ONLY rendered after `transcode_video` (gate reused, not bypassed); mp4 + poster per-recipient encrypted (out-of-audience friend can't decrypt; stored blobs are ciphertext); `media`/`poster` validation (video = 1 blob + hex64 poster; photo = no poster); input cap → 413 + gate error → 400 (no 500); no autoplay; grid picker skipped for video; no story-pipeline change; honesty guard; no shipped behavior broken. Then superpowers:finishing-a-development-branch — merge to `main`, push. This completes the tractable three; split-columns + versioned-edit remain the deferred heavy two.
