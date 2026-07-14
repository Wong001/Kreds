# Photo compression on upload + 10 MB blob cap — design

Date: 2026-07-15
Status: approved (August, 2026-07-15)
Ships as: 0.3.11 (core + web)

## Problem

Post photos are stored raw and capped at `MAX_BLOB_BYTES` (5 MB), enforced
at the API door, the blob store, and the sync blob-fetch. A typical modern
phone JPEG exceeds 5 MB, so uploads fail with "photo exceeds 5 MB cap" and
the person has to shrink the file themselves. Avatars/banners (imagegate)
and story images (1080px) are already transcoded; post photos never got the
same treatment — they are also the one image path where viewers render
peer-supplied raw bytes by design.

## Decisions (August, 2026-07-15)

- **Both** compress on upload **and** raise the stored cap: 5 MB → 10 MB
  headroom for detail-heavy shots.
- Fidelity target: **2560 px long edge, JPEG q~85**, quality stepping down
  until the result fits the cap.
- Rollout: **single release, accept the window** — peers still on ≤0.3.10
  refuse >5 MB blobs at the sync layer and show a missing photo until they
  auto-update. Small fleet, short window; documented honestly (ROADMAP +
  release notes say "update all your devices").
- Animated GIFs: **raw passthrough** (animation preserved, exactly today's
  behavior). A GIF over the cap is rejected honestly — we cannot compress
  animations in this pipeline.

## Design

### 1. Constants (`hearth/messages.py`)

- `MAX_BLOB_BYTES = 10 * 1024 * 1024` (was 5 MB). Protocol-level: store,
  sync, API all key off it. Mixed-version window per the rollout decision.
- New `MAX_IMAGE_UPLOAD = 50 * 1024 * 1024` — raw upload cap checked at the
  HTTP door before the gate, mirroring `MAX_VIDEO_UPLOAD` (100 MB raw in,
  bounded transcode out).

### 2. The gate (`hearth/imagegate.py` — new `transcode_photo`)

New function alongside the avatar/banner `transcode`, same philosophy
(viewers only render bytes WE produced), photo-appropriate output:

1. **GIF magic bytes (`GIF8`) → return input unchanged.** Deliberate
   exception: animation preserved; decoder-exposure honesty note stays for
   this one format. A GIF over `MAX_BLOB_BYTES` raises `ValueError`
   ("animated GIF exceeds the 10 MB cap - animations can't be
   compressed") from the gate itself, surfaced as a 400 like other
   compose errors.
2. Decode via Pillow with its decompression-bomb guard active; truncated /
   non-image input raises `ValueError` ("not an image"), surfaced as 400.
3. **Apply EXIF orientation (`ImageOps.exif_transpose`) first, then strip
   all metadata** — re-encoding drops EXIF including GPS. Privacy win:
   today a post photo carries whatever the phone stamped on it.
4. Downscale to **2560 px long edge** (LANCZOS), never upscale.
5. Output format:
   - Input PNG whose downscaled **lossless PNG** re-encode fits
     `MAX_BLOB_BYTES` → stays PNG (screenshots/graphics stay crisp).
   - Otherwise **JPEG** (RGB, flattened on white): q85, stepping
     85 → 75 → 65 → 55; if still over the cap at q55, halve the long edge
     and repeat the ladder. Output is ≤ cap by construction.

### 3. Wiring (`hearth/node.py`, `hearth/api.py`)

- The gate runs in **`node.compose_post`**, per photo, before
  `encrypt_blob`/`put_blob` — same placement as the story image gate — so
  every composer path (wall post, deck grow via album flow, album add)
  gets it for free, and the API layer stays a dumb door.
- Every image door check in `api.py` that currently compares against
  `MAX_BLOB_BYTES` (post photos, profile images, story images/media)
  swaps to `MAX_IMAGE_UPLOAD`; the plan maps the exact call sites. The
  video path is untouched (`MAX_VIDEO_UPLOAD` already models this).
  Every "exceeds 5 MB cap" error string is refreshed to match reality
  (door messages name the 50 MB upload cap; the store's own message
  reflects the 10 MB blob cap).
- Web client: any hardcoded 5 MB copy/checks in `app.js` are updated;
  the composer needs no behavior change (server remains authoritative).

### 4. Explicitly out of scope

- Client-side (browser) pre-compression before upload — later additive
  optimization if Tor upload time on huge originals annoys; one
  authoritative code path for now.
- Re-encoding animations (GIF → mp4 via the video gate) — would change
  GIF posts into video blocks; separate slice if ever wanted.
- Any change to avatars/banners/stories — their gates already exist.

### 5. Tests

- **imagegate unit:** oversized JPEG → ≤ cap, long edge ≤ 2560, EXIF
  (incl. GPS tags) absent, orientation baked into pixels; small photo
  passes with metadata still stripped; PNG screenshot stays PNG; animated
  GIF passthrough byte-identical; decompression bomb / non-image →
  `ValueError`; pathological input that defeats q55 still lands ≤ cap via
  dimension stepping.
- **API:** 8 MB phone JPEG now 200s and the stored blob is ≤ 10 MB;
  60 MB image → 413; oversized GIF → honest 400/413.
- **store/sync:** blob boundary tests both sides of 10 MB (store accepts
  10 MB, refuses over; sync fetch accepts ≤ 10 MB).
- Existing 5 MB-cap tests updated to the new constants, not deleted.

### 6. Release

0.3.11, core + web in lockstep. Release notes must carry the
mixed-version caveat: photos posted from 0.3.11 may not display on
devices still on ≤ 0.3.10 until those update.
