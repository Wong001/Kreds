# Loop Stories — Design (24h ephemeral, photo + short video)

**Date:** 2026-07-03
**Status:** Approved (design discussion; video included, ≤15s muted, imageio-ffmpeg)
**Basis:** Loop on branch `hearth-vertical-slice` (posts, DMs, profiles; existing image transcode gate, expiry/tombstone sweep, blob GC, gossip sync)
**Working name:** Loop (display); package stays `hearth`.

---

## What a Story is

A **Story item** is a single full-screen photo or short muted video with an optional caption that auto-deletes after 24 hours. A person's items from the last 24h form their "story": shown as a ring on their avatar in a strip, tapped to play through in order, then auto-advancing to the next person. Instagram/Snap model, adapted to Loop's friends-only, no-metrics world — **no view counts, no "seen by" list** (surveillance the thesis rejects); "seen" is tracked only locally on the viewer's own device.

## Rides existing machinery

A Story item is a **new signed message kind `KIND_STORY`**, gossiped and validated exactly like posts/profiles, inheriting the spine:
- **24h ephemerality** = existing `expires_at` + `sweep_expired` + tombstone path. `expires_at = created_at + 86400`.
- **Media** = content-addressed blobs; story media + poster hashes are registered in `referenced_blobs()` (the profiles lesson: else GC deletes them and they never sync to friends).
- **Confidentiality/ephemerality honesty** = strangers never receive stories (structural); friends do, over gossip; the 24h delete is protocol-enforced among friends, not DRM (a friend could screenshot — same honest caveat as all Loop content).

Payload: `{kind:"story", media_kind:"photo"|"video", media:<hash>, poster:<hash|null>, caption:str≤200, created_at, expires_at}`. `poster` is null for photos, the extracted first-frame hash for videos.

## Video transcode gate (the net-new subsystem)

`hearth/videogate.py`, mirroring the image gate's "every viewer only renders bytes Loop produced" philosophy:
- **Dependency: `imageio-ffmpeg`** — a pip-installable *bundled* ffmpeg binary (no system install). New dependency (unlike Pillow, which was already present).
- **The gate** `transcode_video(data) -> (mp4_bytes, poster_png_bytes)`: decode the upload; **reject if duration > 15s** (`ValueError`, no auto-trim — predictable); downscale to ≤720p; **strip the audio track**; re-encode to MP4/H.264 at a capped bitrate targeting < 5 MB output (reject if still over); extract the **first frame** and run it through the existing image gate to produce the poster PNG. Non-video / unparseable / over-length / over-size → `ValueError` → clean 400.
- **Photo stories** reuse the existing `imagegate.transcode` at a larger max dimension (`STORY_IMAGE_MAX = 1080`, since stories are full-screen).
- **Honest scope:** author-side gate (a modified peer can still put raw bytes behind a hash, as with post photos), served `nosniff`, never as HTML. ffmpeg re-encode is the accepted mitigation and the shared pipeline DMs/profiles video would reuse later.

## Store / node / API

- **Store:** `referenced_blobs()` also counts `KIND_STORY` `media` + `poster` hashes; `active_stories(now)` → non-expired story rows grouped by author, time-ascending. `sweep_expired` unchanged handles the 24h delete.
- **Node:** `compose_story(media_bytes, caption)` — sniffs the bytes to pick the gate (image → photo story; video → video gate), stores media (+ poster for video), publishes the story message with `media_kind` set from the sniff and `expires_at = now + 86400`. `stories_view()` → `[{identity, name, avatar, mine, items:[{msg_id, media_kind, media, poster, caption, created_at}]}]` for self + friends with active stories, self first.
- **API:** `POST /api/story` (multipart: `media` file, `caption`); 400 on gate failure, 413 over cap. `GET /api/stories` → `stories_view()`. Media served by existing `GET /api/blob/{hash}` (already `nosniff`). The server determines `media_kind` by **sniffing the uploaded bytes** (image magic → photo/image gate; otherwise → video gate), never trusting a client-supplied kind.

## UI

- **Stories strip** across the top of Feed: round avatars, an accent ring on anyone with unseen items, the viewer's own tile first with a `＋ Add to story` affordance (opens a file picker; posts via `/api/story`).
- **Full-screen viewer:** photos hold ~5s then auto-advance; videos autoplay muted (tap to unmute) and advance on `ended`; tap right/left to skip within and across people; roll from one person's items into the next; close returns to Feed.
- **Seen state** in `localStorage` (like DM unread) — a set of seen story `msg_id`s; never transmitted.

## Testing

- **videogate unit:** a real short clip (generated with imageio-ffmpeg in the test) re-encodes to MP4 + yields a poster PNG; a >15s clip rejected; non-video bytes rejected; output < 5 MB; audio absent in output.
- **image-story path:** photo story uses the image gate at 1080 max.
- **store:** story media+poster in `referenced_blobs` (GC-safe + missing_blobs fetch); `active_stories` groups by author and excludes expired; `sweep_expired` tombstones a story past 24h.
- **node:** `compose_story` gates+stores+publishes; `stories_view` groups self+friends, self first; a friend's story propagates (message + blobs) across two stores.
- **api:** `POST /api/story` round-trip (photo and video); `GET /api/stories`; 400 on bad media; 413 over cap.
- Full suite stays green.

## Out of scope (v1) — with disposition per this discussion

- **Audio in videos** — muted v1; **wanted later** (re-encode to AAC; adds an audio-decode path to the gate).
- **Longer / user-chosen video length** — v1 caps ~15s because that's what single-blob transport carries under the 5 MB cap; **follow-up gated on chunked/streamed blob transfer**, after which length becomes a user choice within a mesh-safe ceiling (recorded per user request — the length cap is a transport-cost artifact, not product dogma).
- **Seen-among-friends receipts** — deferred; **may be cut** as anti-thesis (no view counts/seen-by in v1 regardless).
- **Story replies/reactions** — follow-up.
- Highlights/archives (stories are meant to vanish), text/sticker overlays, reusing the video gate for DM/profile video — later.

## Deviations from real Loop (unchanged)

Copy-paste for QR; localhost for onion address; plaintext TCP for Tor. This slice adds Stories + a video gate; it does not touch transport.
