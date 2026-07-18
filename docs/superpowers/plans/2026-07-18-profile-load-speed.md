# Profile-Load Speed Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A new friend's wall paints in seconds instead of minutes: images render placeholders (never broken glyphs) and self-heal as blobs arrive, small blobs sync first, tiles paint from ~25 KB AVIF thumbnails carried on post records, and full photos shrink ~40-50% by moving the gate from JPEG to AVIF.

**Architecture:** Spec: `docs/superpowers/specs/2026-07-18-profile-load-speed-design.md`. Four independent layers: client render honesty (app.js), sync give-loop ordering (sync.py), additive `thumbs` field through gate/records/render (imagegate/messages/node/store/app.js), and the gate's output format (imagegate). No protocol change; no `min_core_for_web` gating — blobs are opaque to sync and Chromium decodes AVIF client-side.

**Tech Stack:** Python 3.12 (Pillow 12 with native AVIF — verified `features.check('avif') == True`, no new dependency), vanilla JS single-file `hearth/web/app.js`, pytest (+ Playwright behind `UI_E2E=1`).

## Global Constraints

- Run tests with `.venv\Scripts\python.exe -m pytest ...` (Windows, venv). ASCII only in Python strings.
- Commit style `type(scope): summary`, NO AI trailers.
- Tiles/journal images keep their EXACT rendered dimensions — this slice changes loading, never layout.
- `thumbs` is additive and lenient: absent/None on old records is fine everywhere; a thumb entry may be null (generation failed) and every consumer must fall back to the full blob. Thumbs NEVER block a post.
- Thumbs are encrypted with the SAME per-post content key as their parent blob (story media stays plaintext-parented — but stories get no thumbs, see spec scope).
- Avatars/banners stay PNG. Animated-GIF passthrough (raw, no thumb) unchanged.
- DM photos: format change only (Task 1's gate change applies — `compose_dm` calls the same `transcode_photo`), NO thumbs.
- `min_core_for_web` stays untouched this release.

---

### Task 1: imagegate — AVIF ladder, `photo_thumb`, `transcode` format param

**Files:**
- Modify: `hearth/imagegate.py`
- Test: `tests/test_imagegate.py` (append)

**Interfaces:**
- Produces: `photo_thumb(gated: bytes) -> bytes` (AVIF, long edge <= `THUMB_MAX` 640; raises `ValueError` for GIF input and undecodable bytes — the CALLER maps that to a null thumb); `transcode(data, max_dim, fmt="png")` (fmt in `("png", "avif")`); `transcode_photo` emits AVIF (ladder `_PHOTO_QUALITIES = (70, 60, 50, 40)`) instead of JPEG, PNG-stays-PNG rule retired, GIF passthrough kept; constants `THUMB_MAX = 640`, `THUMB_QUALITY = 50`.
- Consumes: nothing new.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_imagegate.py` (reuse its existing image-builder helpers — read the file first; if it lacks a photo-bytes helper, add one small local `_jpeg(w, h)` using PIL):

```python
import io

from PIL import Image

from hearth.imagegate import (photo_thumb, transcode, transcode_photo,
                              THUMB_MAX, PHOTO_MAX)


def _photo_bytes(w=3000, h=2000, fmt="JPEG"):
    buf = io.BytesIO()
    img = Image.new("RGB", (w, h))
    # gradient, so lossy encoders have real work (a flat color compresses
    # to nothing and hides size regressions)
    px = img.load()
    for x in range(0, w, 4):
        for y in range(0, h, 4):
            px[x, y] = (x % 256, y % 256, (x + y) % 256)
    img.save(buf, format=fmt)
    return buf.getvalue()


def _fmt(data):
    return Image.open(io.BytesIO(data)).format


def test_photo_gate_emits_avif_now():
    out = transcode_photo(_photo_bytes())
    assert _fmt(out) == "AVIF"
    assert max(Image.open(io.BytesIO(out)).size) <= PHOTO_MAX


def test_png_input_becomes_avif_too():
    # PNG-stays-PNG retired (spec Part 4): screenshots gain the most.
    out = transcode_photo(_photo_bytes(800, 600, fmt="PNG"))
    assert _fmt(out) == "AVIF"


def test_gif_passthrough_unchanged():
    buf = io.BytesIO()
    frames = [Image.new("P", (60, 60), c) for c in (0, 100)]
    frames[0].save(buf, format="GIF", save_all=True,
                   append_images=frames[1:])
    raw = buf.getvalue()
    assert transcode_photo(raw) == raw


def test_photo_thumb_small_avif():
    gated = transcode_photo(_photo_bytes())
    th = photo_thumb(gated)
    assert _fmt(th) == "AVIF"
    assert max(Image.open(io.BytesIO(th)).size) <= THUMB_MAX
    assert len(th) < 100 * 1024          # generous ceiling; typical ~25KB


def test_photo_thumb_rejects_gif_and_junk():
    import pytest
    buf = io.BytesIO()
    Image.new("P", (60, 60)).save(buf, format="GIF")
    with pytest.raises(ValueError):
        photo_thumb(buf.getvalue())      # GIF: no thumb (animation on tiles)
    with pytest.raises(ValueError):
        photo_thumb(b"not an image")


def test_transcode_fmt_param():
    src = _photo_bytes(900, 900, fmt="PNG")
    assert _fmt(transcode(src, 512)) == "PNG"            # default unchanged
    assert _fmt(transcode(src, 512, fmt="avif")) == "AVIF"
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests\test_imagegate.py -q`
Expected: new tests FAIL (`ImportError: cannot import name 'photo_thumb'`); pre-existing tests pass. NOTE: some pre-existing tests may assert JPEG output — those pins are now WRONG per spec; update them to AVIF in Step 3 and say so in your report (they are the old format's regression pins, superseded).

- [ ] **Step 3: Implement**

In `hearth/imagegate.py` — `transcode` gains the fmt param (replace the final two lines of the function):

```python
def transcode(data: bytes, max_dim: int, fmt: str = "png") -> bytes:
    ...existing body unchanged until the save...
    out = io.BytesIO()
    if fmt == "avif":
        # story stills / video posters (spec 2026-07-18 Part 4): ~half the
        # bytes of PNG at fullscreen sizes; decode is the client Chromium's
        # native job. speed=6: encode-time sanity on big frames.
        img.save(out, format="AVIF", quality=60, speed=6)
    else:
        img.save(out, format="PNG")
    return out.getvalue()
```

`transcode_photo`: replace the docstring's format sentences, delete the `src_png` branch (lines 87, 93-97), and swap the ladder encode:

```python
_PHOTO_QUALITIES = (70, 60, 50, 40)    # AVIF ladder before dimensions halve
```

```python
    while True:
        for q in _PHOTO_QUALITIES:
            out = io.BytesIO()
            rgb.save(out, format="AVIF", quality=q, speed=6)
            if out.tell() <= cap:
                return out.getvalue()
        ...halving loop unchanged...
```

Updated docstring for `transcode_photo`:

```python
    """Post/DM photo gate: orientation baked in, metadata (EXIF/GPS)
    dropped, downscaled to PHOTO_MAX, AVIF-recompressed down a quality
    ladder until the result fits `cap` (spec 2026-07-18: ~40-50% smaller
    than the old JPEG ladder at equal quality; decode is native in every
    client Chromium and iOS16+/Android12+ for the future mobile floor).
    Animated GIFs pass through raw -- animation preserved, cannot be
    compressed here, over-cap refused."""
```

Add after `transcode_photo`:

```python
THUMB_MAX = 640                        # long edge for wall/journal tiles
THUMB_QUALITY = 50


def photo_thumb(gated: bytes) -> bytes:
    """Tile-resolution AVIF thumbnail of an already-gated photo (spec
    2026-07-18 Part 3). Raises ValueError for GIF (tiles keep the
    animated full blob) and undecodable bytes -- the caller maps a raise
    to a null thumb entry; a thumbnail must never block a post."""
    if gated[:4] == b"GIF8":
        raise ValueError("no thumbs for animated GIFs")
    try:
        img = Image.open(io.BytesIO(gated))
        img.load()
    except (UnidentifiedImageError, OSError, ValueError,
            Image.DecompressionBombError):
        raise ValueError("not an image")
    if img.mode != "RGB":
        img = img.convert("RGB")
    if max(img.size) > THUMB_MAX:
        img.thumbnail((THUMB_MAX, THUMB_MAX), Image.LANCZOS)
    out = io.BytesIO()
    img.save(out, format="AVIF", quality=THUMB_QUALITY, speed=6)
    return out.getvalue()
```

- [ ] **Step 4: Size sanity check (spec requirement, do not skip)**

Run a one-off in the scratchpad: gate a real-ish 2560px gradient photo through the OLD JPEG ladder (temporarily via PIL directly at quality 85) and the new AVIF ladder; print both sizes. Expected: AVIF at-or-below the JPEG size. If AVIF comes out LARGER on this synthetic, test again with an actual photograph-like image (load any real JPEG if present on disk, else add noise to the gradient); if still larger, raise the ladder start to 75 and note the measurement in your report. Then run: `.venv\Scripts\python.exe -m pytest tests\test_imagegate.py -q` — ALL pass (including your updates to superseded JPEG pins).

- [ ] **Step 5: Commit**

```bash
git add hearth/imagegate.py tests/test_imagegate.py
git commit -m "feat(imagegate): AVIF photo ladder + photo_thumb tile thumbnails + transcode format param"
```

---

### Task 2: records — `thumbs` through make_post / validation / compose / store / rows

**Files:**
- Modify: `hearth/messages.py:74-87` (`make_post`), `:235-248` (validate_payload POST branch)
- Modify: `hearth/node.py` (`compose_post` photo+video branches, `compose_story` story-still fmt, `_decrypt_post_row`), `hearth/videogate.py:97` (poster via AVIF)
- Modify: `hearth/store.py:1024-1059` (`referenced_blobs`)
- Test: `tests/test_profile_video.py`, `tests/test_store_scoped_posts.py` or the store test file that exercises `referenced_blobs` (read first, append where the fixtures live)

**Interfaces:**
- Consumes: `photo_thumb` from Task 1.
- Produces: post payloads carry `"thumbs": [hash-or-None, ...]` index-aligned with `blobs` (or `None` for old/thumbless posts); decrypted post rows carry `"thumbs"`; `referenced_blobs()` includes thumb hashes; story stills and video posters are AVIF.

- [ ] **Step 1: Write the failing tests**

Append (adapting helper names to each file's real local conventions — read them first; `_node`/`_clip`/`png` below are stand-ins ONLY where that file lacks the exact name):

```python
def test_post_thumbs_aligned_and_served(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-pc")
    mid = node.compose_post("pics", "kreds", photos=[_photo(), _photo()])
    row = [p for p in node.feed() if p["msg_id"] == mid][0]
    assert len(row["thumbs"]) == len(row["blobs"]) == 2
    assert all(t for t in row["thumbs"])
    from PIL import Image
    import io
    th = node.post_blob(mid, row["thumbs"][0])       # same content key
    assert Image.open(io.BytesIO(th)).format == "AVIF"
    assert max(Image.open(io.BytesIO(th)).size) <= 640
    # a hash from ANOTHER post cannot be fetched through this post's id:
    # the per-post content key fails AEAD auth (the crypto IS the guard)
    other = node.compose_post("x", "kreds", photos=[_photo()])
    orow = [p for p in node.feed() if p["msg_id"] == other][0]
    assert node.post_blob(mid, orow["blobs"][0]) is None


def test_gif_post_gets_null_thumb(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-pc")
    mid = node.compose_post("gif", "kreds", photos=[_gif()])
    row = [p for p in node.feed() if p["msg_id"] == mid][0]
    assert row["thumbs"] == [None]


def test_video_post_thumb_of_poster(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-pc")
    mid = node.compose_post("v", "kreds", video=_clip(2))
    row = [p for p in node.feed() if p["msg_id"] == mid][0]
    assert len(row["thumbs"]) == 1 and row["thumbs"][0]
    from PIL import Image
    import io
    th = node.post_blob(mid, row["thumbs"][0])
    assert Image.open(io.BytesIO(th)).format == "AVIF"


def test_referenced_blobs_includes_thumbs(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-pc")
    mid = node.compose_post("pics", "kreds", photos=[_photo()])
    payload = node.store.get_message(mid).payload
    refs = node.store.referenced_blobs()
    assert payload["thumbs"][0] in refs
    # delete drops thumbs with the post (gc)
    node.delete_post(mid)
    node.store.gc_blobs()
    assert node.store.get_blob(payload["thumbs"][0]) is None


def test_story_still_and_video_poster_are_avif(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-pc")
    sid = node.compose_story(_photo(), "")
    item = [i for g in node.stories_view() for i in g["items"]][0]
    from PIL import Image
    import io
    assert Image.open(io.BytesIO(
        node.store.get_blob(item["media"]))).format == "AVIF"
```

Validation matrix (append to `tests/test_messages.py`, matching its local style):

```python
def test_post_thumbs_validation():
    base = _valid_post_payload()          # file's existing helper, or build
    base["blobs"] = ["a" * 64, "b" * 64]
    base["thumbs"] = ["c" * 64, None]     # aligned, null entry ok
    assert validate_payload(base)[0]
    base["thumbs"] = ["c" * 64]           # length mismatch
    assert not validate_payload(base)[0]
    base["thumbs"] = "junk"               # not a list
    assert not validate_payload(base)[0]
    base["thumbs"] = ["zz", None]         # non-hex entry
    assert not validate_payload(base)[0]
    del base["thumbs"]                    # absent = old record, fine
    assert validate_payload(base)[0]
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests\test_profile_video.py tests\test_messages.py -q`
Expected: new tests FAIL (KeyError "thumbs" / TypeError), old pass.

- [ ] **Step 3: Implement**

`hearth/messages.py` `make_post`: add `thumbs=None` kwarg after `codec`; payload gains `"thumbs": list(thumbs) if thumbs is not None else None`. In `validate_payload`'s POST branch, after the `blobs` check:

```python
        thumbs = p.get("thumbs")
        if thumbs is not None:
            if (not isinstance(thumbs, list) or len(thumbs) != len(blobs)
                    or not all(t is None or _is_hex64(t) for t in thumbs)):
                return False, "bad thumbs"
```

`hearth/node.py` `compose_post` photo branch (gated photos exist as `gated`):

```python
            gated = [transcode_photo(p) for p in photos]   # raises ValueError
            refs = [self.store.put_blob(encrypt_blob(key, p)) for p in gated]
            # Tile thumbnails (spec 2026-07-18): same content key, aligned
            # with blobs; a failed thumb is a null entry, never a failed post.
            thumbs = []
            for g in gated:
                try:
                    thumbs.append(self.store.put_blob(
                        encrypt_blob(key, photo_thumb(g))))
                except ValueError:
                    thumbs.append(None)
```

and pass `thumbs=thumbs if refs else None` to `make_post`. Video branch: after `pref`, add

```python
            try:
                tref = self.store.put_blob(
                    encrypt_blob(key, photo_thumb(poster_png)))
            except ValueError:
                tref = None
```

and pass `thumbs=[tref]`. `compose_story` image branch: `transcode(media_bytes, STORY_IMAGE_MAX, fmt="avif")`. `hearth/videogate.py:97`: `image_transcode(poster_data, STORY_IMAGE_MAX, fmt="avif")` (import stays the same aliased function). `_decrypt_post_row`: add `"thumbs": p.get("thumbs"),` after `"codec"`.

`hearth/store.py` `referenced_blobs`, after the poster guard:

```python
                # thumbs (spec 2026-07-18): same junk-guard as poster --
                # the query spans KIND_DM whose payload isn't thumb-validated
                for t in (p.get("thumbs") or []):
                    if isinstance(t, str) and t:
                        refs.add(t)
```

- [ ] **Step 4: Run the tests**

Run: `.venv\Scripts\python.exe -m pytest tests\test_profile_video.py tests\test_messages.py tests\test_node_story.py tests\test_store_scoped_posts.py tests\test_api_story.py -q`
Expected: ALL pass.

- [ ] **Step 5: Commit**

```bash
git add hearth/messages.py hearth/node.py hearth/videogate.py hearth/store.py tests/
git commit -m "feat(node): thumbs on post records - same-key encrypted, referenced for sync, AVIF story stills + posters"
```

---

### Task 3: sync — small blobs first

**Files:**
- Modify: `hearth/sync.py:627-641` (give loop), `hearth/store.py` (add `blob_sizes`)
- Test: `tests/test_sync_session.py` (append; read its two-node session harness first and reuse it exactly)

**Interfaces:**
- Produces: `store.blob_sizes(hashes: list[str]) -> dict[str, int]`; the give loop fills the budget smallest-first (ties: hash order).

- [ ] **Step 1: Write the failing test**

Append to `tests/test_sync_session.py` (adapt to its real harness names):

```python
def test_blob_give_is_smallest_first_under_budget(tmp_path, monkeypatch):
    # Giver holds one big blob and several small ones the requester wants.
    # With a budget that can't fit the big one plus the smalls, round 1
    # must deliver ALL smalls and NOT the big (spec 2026-07-18 Part 2:
    # thumbnails/avatars land first; one large video can no longer starve
    # twenty small images).
    import hearth.sync as sync_mod
    big = b"B" * (300 * 1024)
    smalls = [bytes([i]) * 4096 for i in range(10)]
    # budget: fits all smalls (~55KB b64) but not big (400KB b64)
    monkeypatch.setattr(sync_mod, "BLOB_GIVE_BUDGET", 100 * 1024)
    a, b = _two_synced_nodes(tmp_path)      # harness helper: befriended pair
    # seed the giver with blobs the requester will want: reference them
    # via a real post so missing_blobs() on the requester names them
    hashes = _post_with_raw_blobs(a, [big] + smalls)   # see NOTE below
    _run_session(a, b)                      # harness's one-session runner
    have_b = {h for h in hashes if b.store.get_blob(h) is not None}
    small_hashes = set(hashes[1:])
    assert small_hashes <= have_b           # every small arrived round 1
    assert hashes[0] not in have_b          # big deferred to round 2
    _run_session(a, b)
    assert b.store.get_blob(hashes[0]) is not None
```

NOTE for the implementer: the harness in this file already has helpers for befriended pairs and running one session (read them). For `_post_with_raw_blobs`, the cleanest real-world path is composing a post on `a` with several photos of very different sizes; if gate compression makes sizes unpredictable, instead put raw blobs in `a.store` AND reference them via a hand-built signed post message ingested into both stores (the file has precedent for hand-built messages — follow it). What matters: the requester's `missing_blobs()` names one big + many small hashes, and the assertion stays exactly as written.

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests\test_sync_session.py -q`
Expected: new test FAILS (hash-order give means some smalls lose to the big or ordering luck), old pass. If it flakily PASSES on hash luck, tighten by asserting the give ORDER via the store contents, not luck — e.g. make the big blob's hash sort FIRST by regenerating content (`big = b"A" * ...` variants) until `sorted([...])[0]` is the big hash, pinning the failure deterministically. Do this in Step 1, not after.

- [ ] **Step 3: Implement**

`hearth/store.py` (next to `missing_blobs`):

```python
    def blob_sizes(self, hashes) -> dict:
        """{hash: stored byte length} for the hashes we hold (others absent)."""
        with self._lock:
            if not hashes:
                return {}
            qs = ",".join("?" for _ in hashes)
            return {h: n for (h, n) in self._db.execute(
                f"SELECT hash, LENGTH(data) FROM blobs WHERE hash IN ({qs})",
                list(hashes))}
```

`hearth/sync.py` give loop — replace the iteration source:

```python
        # Smallest-first (spec 2026-07-18): thumbnails, avatars, and small
        # photos fill round 1; one big video can't starve twenty small
        # images. Ties keep hash order for determinism. Unknown hashes
        # sort last and fall out at get_blob(None-check) as before.
        wanted = peer_want.get("hashes", [])
        sizes = store.blob_sizes(wanted)
        for h in sorted(wanted, key=lambda x: (sizes.get(x, 1 << 62), x)):
```

(body of the loop unchanged).

- [ ] **Step 4: Run**

Run: `.venv\Scripts\python.exe -m pytest tests\test_sync_session.py tests\test_three_nodes.py -q`
Expected: ALL pass.

- [ ] **Step 5: Commit**

```bash
git add hearth/sync.py hearth/store.py tests/test_sync_session.py
git commit -m "feat(sync): smallest-first blob giving - small media lands in round 1"
```

---

### Task 4: client — placeholders, thumb-first tiles, profile self-heal

**Files:**
- Modify: `hearth/web/app.js` (`buildEntry` journal imgs ~:346-351, `blockPhotoItems` ~:380, `renderDeck` img, `renderBlock` single-photo + video poster, `refresh` ~:4165)
- Modify: `hearth/web/style.css` (append `.img-pending`)
- Test: `tests/test_web_assets.py` (append)

**Interfaces:**
- Consumes: post rows now carry `thumbs` (null for old posts) from Task 2.
- Produces: `blobImg(msgId, hash, thumbHash)` helper — an `<img>` whose src prefers the thumb and which swaps itself for a `.img-pending` placeholder on error; `blockPhotoItems` items become `{m, h, t}`; `refresh()` heals an open profile.

- [ ] **Step 1: Write the failing asset test**

```python
def test_profile_load_render_honesty():
    # Spec 2026-07-18 Parts 1+3: no broken glyphs (onerror -> .img-pending
    # placeholder), tiles prefer the thumb hash, lightbox keeps full-res,
    # and refresh() heals an open profile (guarded: not while arranging /
    # modal open - a re-render mid-drag would tear the drag surface away).
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert "function blobImg(" in js
    bi = _js_fn_body(js, "blobImg")
    assert "img-pending" in bi and "onerror" in bi
    assert ".img-pending" in css
    items = _js_fn_body(js, "blockPhotoItems")
    assert '"t"' in items or "t:" in items          # thumb rides the item
    deck = _js_fn_body(js, "renderDeck")
    assert "blobImg" in deck or ".t ||" in deck
    lb = _js_fn_body(js, "openLightbox")
    assert ".t" not in lb                            # lightbox is full-res only
    rf = _js_fn_body(js, "refresh")
    assert "openProfile(CURRENT_PROFILE)" in rf
    assert "ARRANGING" in rf                         # the drag guard
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests\test_web_assets.py::test_profile_load_render_honesty -q` — FAIL.

- [ ] **Step 3: Implement**

Add near `blockPhotoItems` (app.js ~:380):

```js
// blobImg (spec 2026-07-18): every post image renders through this -
// prefers the tile-resolution thumb when the record carries one (old
// posts don't - full blob, exactly as before), and swaps itself for a
// neutral .img-pending placeholder instead of the browser's broken-image
// glyph while the blob hasn't gossiped in yet. The WS "changed" re-render
// (refresh) replaces the placeholder with a fresh <img> that retries.
function blobImg(msgId, hash, thumbHash) {
  const img = document.createElement("img");
  img.src = "/api/post-blob/" + msgId + "/" + (thumbHash || hash);
  img.alt = "";
  img.onerror = () => {
    const ph = el("div", "img-pending");
    ph.setAttribute("aria-hidden", "true");
    img.replaceWith(ph);
  };
  return img;
}
```

`blockPhotoItems` carries thumbs (index-aligned; albums fold members — mirror however `p.photos` rows are built, adding `t` there too, null-safe):

```js
function blockPhotoItems(p) {
  if (p.album) return p.photos;                    // album rows gain .t where
                                                   // _fold_album_members builds
                                                   // them (server already sends
                                                   // thumbs on member rows)
  const th = p.thumbs || [];
  return (p.blobs || []).map((h, i) => ({m: p.msg_id, h, t: th[i] || null}));
}
```

(If album `p.photos` rows are server-built without thumbs, extend the server fold — find `_fold_album_members` in node.py and carry the member post's aligned thumb into each photo row as `t`; keep it null-safe for old members. Report which side you did it on.)

Journal `buildEntry` (~:346): the loop becomes

```js
  const eth = p.thumbs || [];
  p.blobs.forEach((h, i) => {
    body.append(blobImg(p.msg_id, h, eth[i] || null));
  });
```

`renderDeck`'s `show()` builds `img.src` — switch to prefer `items[i].t`:

```js
    img.src = "/api/post-blob/" + items[i].m + "/" + (items[i].t || items[i].h);
```

and give the deck img the same onerror-placeholder treatment as `blobImg` (deck img is long-lived across flips, so on error replace SRC handling: set a flag class `img-pending-bg` on the deck instead of replacing the element — simplest: `img.onerror = () => img.classList.add("img-hidden");` plus a `.block-deck.pending::before` — implementer picks the minimal variant that never shows a broken glyph and never breaks the flip logic; pin your choice in the asset test if it deviates from `blobImg`). Single-photo block (`renderBlock` items.length === 1): use `blobImg(items[0].m, items[0].h, items[0].t)` but keep `img.onclick`/cursor/tabIndex wiring — `blobImg` returns the element, so wire those on the returned img. Video block: `if (p.thumbs && p.thumbs[0]) v.poster = "/api/post-blob/" + p.msg_id + "/" + p.thumbs[0]; else if (p.poster) ...` (existing line as fallback). `openLightbox` stays on `items[i].h` — full resolution, untouched.

`refresh()` — after `renderStories();` add:

```js
  // Profile self-heal (spec 2026-07-18 Part 1): a friend's wall fills in
  // as blobs gossip in, instead of staying broken until re-navigation.
  // Guards: never re-render mid-Arrange (tears the drag surface from
  // under the pointer) or under the block-settings modal (it holds a
  // reference to its block element). Next tick heals after they close.
  if (currentView() === "profile" && CURRENT_PROFILE && !ARRANGING
      && document.getElementById("block-settings").classList.contains("hidden"))
    openProfile(CURRENT_PROFILE);
```

(VERIFY the modal's hidden-state mechanism in index.html/app.js — if it uses `hidden` attribute or a different class, adapt the check and the asset needle accordingly; the ARRANGING guard is the non-negotiable one.)

`style.css` (append):

```css
/* Not-yet-synced post image (spec 2026-07-18): neutral pending tile in
   place of the browser's broken-image glyph; the WS-driven re-render
   retries it. Sized by the container (block cell / journal image box). */
.img-pending { width: 100%; height: 100%; min-height: 120px;
  border-radius: 12px;
  background: linear-gradient(100deg, var(--line-2) 40%,
    color-mix(in srgb, var(--line-2) 55%, transparent) 50%,
    var(--line-2) 60%);
  background-size: 200% 100%;
  animation: img-pending-sheen 1.6s ease-in-out infinite; }
@keyframes img-pending-sheen {
  from { background-position: 120% 0; } to { background-position: -20% 0; } }
@media (prefers-reduced-motion: reduce) {
  .img-pending { animation: none; } }
```

- [ ] **Step 4: Verify**

Run: `node --check hearth\web\app.js` then `.venv\Scripts\python.exe -m pytest tests\test_web_assets.py -q` — ALL green (several existing asset tests grep these functions; if a pre-existing needle broke because a line moved INSIDE a function you edited, fix your edit, not the needle — needles pin behavior that must survive).

- [ ] **Step 5: Commit**

```bash
git add hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat(web): pending placeholders, thumb-first tiles, profile self-heal on sync"
```

---

### Task 5: live UI smoke (UI_E2E)

**Files:**
- Create: `tests/test_ui_smoke_profile_load.py`

**Interfaces:**
- Consumes: everything above; `LiveNode`/`befriend` from `tests/test_ui_smoke_seen_badge.py`; two-node sync patterns from `tests/test_ui_smoke_collage.py`.

- [ ] **Step 1: Write the smoke**

```python
"""UI_E2E=1-gated live smoke for the profile-load slice (spec 2026-07-18):
Anna posts wall photos -> Bo syncs -> Bo's view of Anna's wall renders
tile <img>s pointing at the THUMB hashes (small AVIF), and opening the
lightbox loads the FULL hash. Also: a post whose blobs haven't arrived
renders .img-pending, not a broken glyph (forced by deleting the blob
bytes from Bo's store before rendering).
"""
import os

import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("UI_E2E") != "1",
    reason="live browser smoke; set UI_E2E=1 (needs playwright chromium)")

from tests.test_ui_smoke_seen_badge import LiveNode, befriend


def _pngs(tmp_path, n):
    from PIL import Image
    paths = []
    for i in range(n):
        p = tmp_path / f"p{i}.png"
        img = Image.new("RGB", (1600, 1200))
        px = img.load()
        for x in range(0, 1600, 4):
            for y in range(0, 1200, 4):
                px[x, y] = (x % 256, (x + i * 40) % 256, y % 256)
        img.save(p, "PNG")
        paths.append(str(p))
    return paths


def test_friend_wall_thumb_first_and_pending(tmp_path):
    from playwright.sync_api import sync_playwright

    a = LiveNode(tmp_path / "a", "Anna", "anna-pc")
    b = LiveNode(tmp_path / "b", "Bo", "bo-pc")
    try:
        befriend(a, b)
        a.start(); b.start()
        a.sync_with(b)                        # enckeys first (collage lesson)
        mid = a.node.compose_post("wall pics", "kreds",
                                  photos=[open(p, "rb").read()
                                          for p in _pngs(tmp_path, 2)],
                                  placement="profile")
        a.sync_with(b)                        # records + blobs across

        row = [p for p in b.node.posts_by(a.node.identity_pub,
                                          placement="profile")
               if p["msg_id"] == mid][0]
        assert row["thumbs"] and all(row["thumbs"])

        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            page = browser.new_page(viewport={"width": 1280, "height": 900})
            errors = []
            page.on("pageerror", lambda e: errors.append(str(e)))
            page.goto(f"http://127.0.0.1:{b.http_port}/")
            page.wait_for_selector(".fchip")
            page.click("#nav-me")
            page.click("#profile-cog")
            page.wait_for_selector("#friends .friend")
            page.click(".friend:has-text('Anna')")
            page.wait_for_selector("#profile-wall .block-deck img")

            src = page.locator("#profile-wall .block-deck img").first \
                .get_attribute("src")
            assert row["thumbs"][0] in src        # tile renders the THUMB
            # lightbox opens the FULL hash
            page.click("#profile-wall .block-deck img")
            page.wait_for_selector("#lightbox-img")
            lsrc = page.locator("#lightbox-img").get_attribute("src")
            assert row["blobs"][0] in lsrc and row["thumbs"][0] not in lsrc
            page.keyboard.press("Escape")

            # pending placeholder: nuke the blob bytes on Bo's store and
            # re-render - the tile must show .img-pending, never a glyph
            for h in row["thumbs"] + row["blobs"]:
                b.node.store._db.execute("DELETE FROM blobs WHERE hash=?",
                                         (h,))
            b.node.store._db.commit()
            page.reload()
            page.wait_for_selector(".fchip")
            page.click("#nav-me")
            page.click("#profile-cog")
            page.wait_for_selector("#friends .friend")
            page.click(".friend:has-text('Anna')")
            page.wait_for_selector("#profile-wall .img-pending",
                                   timeout=8000)

            assert not errors, f"console pageerrors: {errors}"
            browser.close()
    finally:
        try:
            a.stop()
        finally:
            b.stop()
```

NOTE: the direct `store._db` delete is test-only store surgery; if the store exposes a cleaner blob-delete (check `gc_blobs` siblings), prefer it. Selector paths (`#profile-cog` → friends → profile) mirror `test_ui_smoke_collage.py` — verify against the live DOM and adjust selectors only, never assertions.

- [ ] **Step 2: Run it live**

Run: `$env:UI_E2E = "1"; .venv\Scripts\python.exe -m pytest tests\test_ui_smoke_profile_load.py -q`
Expected: PASS. Then the neighbors: `$env:UI_E2E = "1"; .venv\Scripts\python.exe -m pytest tests\test_ui_smoke_albums.py tests\test_ui_smoke_video_editor.py -q` — still green (deck rendering changed; these must not regress).

- [ ] **Step 3: Commit**

```bash
git add tests/test_ui_smoke_profile_load.py
git commit -m "test(smoke): thumb-first friend wall, full-res lightbox, pending placeholder"
```

---

### Task 6: full verification + docs

**Files:**
- Modify: `docs/engineering-notes.md` (append), `ROADMAP.md` (profile-load entry → built status)

- [ ] **Step 1: Full suite + smokes**

`.venv\Scripts\python.exe -m pytest -q` — report the exact tail (expected ~everything passing; the known pre-existing UI_E2E composer y-pin failure is out of scope). Then `$env:UI_E2E = "1"; .venv\Scripts\python.exe -m pytest tests\test_ui_smoke_profile_load.py tests\test_ui_smoke_albums.py tests\test_ui_smoke_video_editor.py -q`.

- [ ] **Step 2: Transfer-size sanity (spec's honest-math check)**

Scratchpad one-off: gate 5 representative photos (mixed sizes) through the new pipeline; print total full-blob bytes (AVIF) vs the old JPEG ladder (encode the same inputs with the pre-change ladder inline in the script) and total thumb bytes. Report the three numbers — they substantiate the spec's ~40-50% and ~25KB/thumb claims, or correct them in the docs you write next.

- [ ] **Step 3: Docs**

Append an engineering-notes section: the three measured causes (budget/45s cadence, random give order, no profile heal), the four-layer fix, the crypto note (thumbs under the parent post's content key; AEAD auth is why `post_blob` needs no reference check), the no-gating compat argument, and the measured numbers from Step 2. Update the ROADMAP profile-load entry status. Commit: `docs: profile-load slice engineering notes + ROADMAP status`.

---

## Self-review notes (done at plan time)

- **Spec coverage:** Part 1 → Task 4 (placeholder + heal); Part 2 → Task 3; Part 3 → Tasks 1/2/4/5; Part 4 → Tasks 1/2; testing map → Tasks 1-5; out-of-scope respected (no DM thumbs — compose_dm untouched by Task 2).
- **Spec correction folded in:** the spec said `post_blob` "must reject hashes not referenced by that post, as today" — today's guard is actually the AEAD decrypt failing under the wrong content key (post_blob has no reference check; node.py:1902-1910). Task 2's test pins the cryptographic guard instead; docs task records it.
- **Type consistency:** `photo_thumb` raises → compose maps to null (Tasks 1/2 agree); rows carry `thumbs` (Task 2) consumed as `p.thumbs`/`items[i].t` (Task 4/5); `blob_sizes` produced and consumed in Task 3 only.
