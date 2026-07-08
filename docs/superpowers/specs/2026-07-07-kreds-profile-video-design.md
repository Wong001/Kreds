# Kreds Profile Canvas — Slice 3c: Video Blocks — Design

**Date:** 2026-07-07
**Status:** Approved (design discussion, this session)
**Basis:** shipped Slices 1–3b (profile block canvas; blocks = `placement=profile` posts, per-recipient encrypted; photo blocks with grids; arrange/DnD). Video gate `hearth/videogate.py` (`transcode_video(bytes) -> (mp4, poster_png)`, ≤15s / 720p / ≤5MB output) already used by `compose_story`. Photo blobs are per-recipient encrypted via `encrypt_blob(content_key, ...)` (`node.py:137`), decrypted by `post_blob` (`node.py:774`).
**Branch:** `kreds-profile-video` off `main`
**Product context:** Sub-slice 3c — the last of the tractable three (DnD → grids → video). Internal package stays `hearth`.

---

## Why

The block canvas supports text and photo blocks; add **video blocks**. A video block is a `placement=profile` post carrying a transcoded, encrypted video, rendered inline with poster + controls. The untrusted video is re-encoded through the existing hardened story gate — never rendered as author-supplied bytes.

## Decisions locked this session

- **Reuse `videogate.transcode_video`** (the story gate): ≤15s, 720p, ≤5MB output MP4 + a poster PNG. Do NOT reinvent. Untrusted input is only ever rendered after our own transcode.
- **Encrypted like a photo block** (NOT like a story). Stories store video plaintext (broadcast); a video block is scoped (Inner/Kreds), so the transcoded MP4 **and** poster are `encrypt_blob`'d with the post's content key and wrapped to the audience — same path as photos. `post_blob` decrypts unchanged.
- **One video per block** + optional caption text. **Playback: poster + `controls`, no autoplay** (a wall of autoplaying videos is hostile). Reuse the story limits.
- Grid pickers do not apply to a video block (it's a single video).

## Components

### 1. `media` + `poster` on `KIND_POST` (backend)
- `make_post(...)` gains `media: str = "photo"` and `poster: Optional[str] = None` in the payload.
- `validate_payload` (`KIND_POST`): `media` in `("photo","video")` (missing = `"photo"`, back-compat). If `media == "video"`: `blobs` is exactly one hex64 (the encrypted MP4 ref) and `poster` is a hex64 (the encrypted poster ref) — reject if the poster is missing/non-hex64 or the blob count != 1. If `media == "photo"`: the existing photo blob rules apply and `poster` must be absent/None (a photo post never carries a poster; `make_post` defaults it to None).
- `_decrypt_post_row` includes `"media"` and `"poster"` in the row.

### 2. Compose path (node)
- Extend `compose_post(text, scope="kreds", photos=(), expires_seconds=None, placement="journal", video=None)`. When `video` (raw bytes) is given: it's a video block — run `transcode_video(video)` → `(mp4, poster_png)`; derive the content key/AAD/wraps exactly as today; `video_ref = put_blob(encrypt_blob(key, mp4))`, `poster_ref = put_blob(encrypt_blob(key, poster_png))`; `make_post(..., media="video", blob_refs=[video_ref], poster=poster_ref)`; cache the key. `photos` is ignored when `video` is present (a block is one medium). `transcode_video` raising (too long / not a video / >5MB) surfaces as `ValueError` → 400.
- Journal video is out of scope here (this slice targets the profile composer); `video` works for any placement but only the profile composer exposes it.

### 3. API
- `POST /api/post` accepts an optional `video: UploadFile`. Enforce a raw-input cap (`MAX_VIDEO_UPLOAD`, e.g. 100 MB) BEFORE handing bytes to the gate (avoid transcoding a giant upload); over-cap → 413. If a video is present, call `compose_post(..., video=bytes)` (photos ignored). `_400` wraps `compose_post` so a gate `ValueError` → 400.

### 4. Front-end
- **Composer video attach:** the profile composer gains a video picker (`<input type=file accept="video/*">`, keyboard-reachable via a labelled control like the photo one). Selecting a video posts a video block (sends the `video` field; if both a photo and a video are somehow selected, video wins — one medium). Show the chosen filename / a "video attached" cue.
- **`renderBlock`:** when `p.media === "video"`, render `<video controls playsinline preload="metadata" poster="/api/post-blob/{msg_id}/{p.poster}">` with a `<source src="/api/post-blob/{msg_id}/{video_ref}">` (video_ref = `p.blobs[0]`). No autoplay. The grid picker + grid classes are skipped for video blocks. Caption text renders as today.
- CSS: `.block-video video { width:100%; border-radius:12px; }`; respect the canvas width.

## Testing

Backend/API:
- `make_post(media="video", poster=...)` round-trips; `validate_payload` accepts a valid video post (1 blob + hex64 poster), rejects `media="video"` with 0 or 2 blobs / a missing/bad poster / a bad media value; a `media="photo"` post with a `poster` set is rejected; missing `media` = photo and round-trips with no poster.
- `compose_post(video=<tiny valid mp4>)` produces a `media="video"` post with an encrypted MP4 + poster blob (assert `post_blob` returns the decrypted bytes; the stored blob != plaintext); a video block is scope-encrypted (an out-of-audience friend can't decrypt it) — reuse the scoped-posts test pattern. Use a tiny real test video fixture (or a generated one via the gate) — mirror how `tests/` exercise `compose_story`/`transcode_video` if such a fixture exists; if video fixtures are heavy, monkeypatch `transcode_video` to return small known bytes and assert the wiring (encrypt/put_blob/payload/media/poster), and separately keep one real-gate test if a fixture is available.
- `POST /api/post` with a video file → a video block; an over-`MAX_VIDEO_UPLOAD` upload → 413; a non-video (gate raises) → 400.

Web asset/DOM:
- Composer has a video input; `renderBlock` has a `p.media === "video"` branch rendering `<video controls poster=...>` (no `autoplay`); grid picker skipped for video. `node --check`.
- Honesty guard: no receipts popover.

Integration/smoke (record): post a video block on the profile; it transcodes + encrypts; a friend in-audience sees the video block (poster + controls, plays) after sync; an out-of-audience friend does not; a single-photo/photo-grid/text block still render; the composer rejects a >15s or non-video file with a clear error. Full suite green (keep the fixed flakes fixed).

## Out of scope (named)

- Video in the journal/home-feed composer (this slice is the profile composer); split-columns + versioned-edit (deferred heavy two); multiple videos per block; client-side trimming/thumbnails beyond the gate's poster.
- Changing the story pipeline or its limits.

## Success criteria

- You can attach a video to a profile post; it's transcoded through the story gate, encrypted per-recipient, and renders as an inline video block (poster + controls, no autoplay) that in-audience friends can play after sync; out-of-audience friends cannot decrypt it.
- Over-limit / non-video input fails cleanly (413 / 400); photo/text blocks and grids are unaffected; honesty guards hold; all tests green plus the new ones.
