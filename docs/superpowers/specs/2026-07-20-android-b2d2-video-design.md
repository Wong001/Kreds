# Android B.2d-2 (video posts) — design, 2026-07-20

Eighth slice of the Kreds Android client, after the Tor-dial spike (PR #1),
Brick A (#2), B.1 (#3), B.2 (#4), B.2c (#5), B.2d-1 photos (#6), Brick C (#7).
B.2d-1 rendered photos; this slice renders + plays VIDEO posts. Pure
phone-side, no hearth change.

## Decomposition note (the richer feed is 3 slices, not 5)

Exploring the hearth content model corrected the naive "threads / reactions /
comments / stories / video" list:
- **Threads/replies is not a buildable thing** — post comments are a FLAT
  list (no nested replies); the only "thread" is a DM conversation (already
  done, B.2c). "Story-replies-as-DMs" are plain DMs with a plaintext
  `story_ref` field (fold into the stories slice).
- **Reactions and comments are the SAME mechanism** (`KIND_RESPONSE`/
  `KIND_RESPONSES`, distinguished by an `rkind` field inside the encrypted
  body) — one slice, not two.
So the remaining work is THREE slices: **Video** (this), **Stories**
(plaintext; the standout finding — stories carry no content key at all),
**Responses** (reactions+comments; the crypto-heaviest — two new AAD ports +
author-only wrap audience + optional anonymous-slot de-anonymization). Order:
video → stories → responses.

## What a video post is (verified against hearth)

A video post is a normal `KIND_POST` (messages.py:10) — NOT a distinct kind.
The plaintext outer payload carries the discriminators (validate_payload
KIND_POST branch, messages.py:307-317): `media: "photo"|"video"` (default
"photo"), and for video exactly ONE entry in `blobs` (the mp4) plus a
required `poster` (hex64 blob hash, an AVIF still), plus `thumbs` (len==blobs,
a tile AVIF), plus `codec` (a static "h264" stamp today — informational, not
negotiated). All three blob refs (mp4 `blobs[0]`, `poster`, `thumbs[0]`) are
`encrypt_blob`'d with the SAME post content key (node.py:702-721) —
crypto-identical to a photo post. So video is already decryptable with the
existing `KotlinDmcrypt`/`DecryptPass` path; the poster/thumb already render
through B.2d-1's `getBlobImage` (AVIF isolated decode). The only genuinely
new work is MP4 PLAYBACK.

## Security: platform player, not our own sandbox (deliberate, unlike AVIF)

Video decode is an RCE surface on friend-authored bytes, like AVIF. But here
we USE THE PLATFORM media player (Expo's video component → Android
ExoPlayer/MediaCodec) rather than isolating it — and that is the MORE
hardened choice, not a shortcut: Android runs media extraction + hardware
decode in the separate, sandboxed **mediaserver** process (hardened
post-Stagefright); the input is gate-capped at <=5 MB (videogate.py); and it
is the same continuously-patched decoder every Android app relies on. Caging
a 30fps player in our own `isolatedProcess` (as we did the AVIF decoder) is
impractical and likely LESS safe. This is the one place we lean on the OS
instead of self-sandboxing, for good reasons.

## Decrypted-bytes delivery: in-memory localhost loopback (decrypt-on-read preserved)

Video blobs are encrypted (post content key), so the phone must `decryptBlob`
the mp4 before the player sees it — but unlike a photo we cannot base64 a
multi-MB video into a data URI. Decision (August, 2026-07-20): stream the
decrypted mp4 to the player over an in-app **localhost server**, so NO
decrypted bytes ever touch disk (the decrypt-on-read posture photos held to
is preserved, not relaxed).

## Components

### 1. `DecryptPass.Decrypted` gains media discriminators

`Decrypted(msgId, kind, author, text, createdAt, blobs, thumbs, media: String, poster: String?)`.
`media` (default "photo") and `poster` are read from the OUTER plaintext
payload (`m.payload["media"]`/`["poster"]`), exactly like `thumbs` in B.2d-1
(NOT from the decrypted body). A photo post → `media="photo"`, `poster=null`.

### 2. `MediaServer` (new Kotlin — the security-critical component)

A tiny HTTP server for decrypted-media playback:
- Binds **`127.0.0.1` ONLY** (never `0.0.0.0`), on a **random ephemeral
  port**. Started lazily on the first video tap; torn down with the module
  (foreground playback aid, not a background service).
- Mints a fresh **unguessable per-session token** (e.g. 32 random bytes hex)
  at start, held in memory.
- `GET /media/<token>/<msgId>/<hash>`: reject with **403** on token
  mismatch; look up the content key via the module's in-memory `blobKeys`
  (same cache `getBlobImage` uses); `getBlob(hash)` → **404** if absent;
  `KotlinBlobCrypt.decryptBlob` → **500/404** if it fails; else stream the
  plaintext bytes with **HTTP range support** (`Range` request → `206
  Partial Content` + correct `Content-Range`/`Content-Length`; players seek
  via ranges). It is NOT a file server — it maps only `(msgId, hash)` → the
  in-memory decrypt, no path/file access.
- Decrypted bytes live only in the response stream; nothing is persisted.

### 3. Module `getVideoUrl(msgId, hash): String?`

Ensures `MediaServer` is running, returns `http://127.0.0.1:<port>/media/<token>/<msgId>/<hash>`
(or null if no cached key). Async, `ioScope`, mirroring `getBlobImage`.

### 4. Feed UI (App.tsx)

A `media === "video"` feed row renders its **poster** via the existing
`getBlobImage(msgId, poster)` (AVIF, isolated decode) with a play (▶) overlay
chip; tap → the existing fullscreen `Modal` hosts the Expo video player
(`expo-video`) with `source = getVideoUrl(msgId, blobs[0])`, controls on,
autoplay on open, tap/back to close + release the player. Reuses the B.2d-1
thumbnail+modal structure; a photo post is unchanged.

### 5. Dependency + surface

Add `expo-video` (Expo v57 — follow `android_tor_spike/app/AGENTS.md`).
`index.ts`: `getVideoUrl(msgId, hash): Promise<string | null>`, and
`FeedItem` gains `media: string` + `poster: string | null`.

## Data flow

```
feed row media=="video" -> poster via getBlobImage(msgId, poster)  [AVIF, existing isolated decode]
tap ▶ -> getVideoUrl(msgId, blobs[0]) -> http://127.0.0.1:<port>/media/<token>/<msgId>/<hash>
expo-video GETs it (with Range) -> MediaServer: token check -> blobKeys[msgId]
   -> getBlob(hash) -> KotlinBlobCrypt.decryptBlob -> stream plaintext mp4 (range-sliced)
   -> ExoPlayer/MediaCodec (platform, mediaserver-sandboxed) -> plays in the Modal
```
No decrypted video byte is written to disk; content key + plaintext live only
in memory for the stream's duration.

## Testing

- **`MediaServer` (JVM, the security gate) — fully desk-testable** (real
  socket + `KotlinBlobCrypt` + InMemory store, no Tor/UI): a committed
  encrypted blob; assert (a) right-token GET returns bytes ==
  `decryptBlob` output; (b) a `Range` request returns the correct slice with
  `206` + `Content-Range`; (c) missing/wrong token → **403**; (d) unknown
  `(msgId,hash)` → **404**; (e) the listener binds `127.0.0.1` only (a
  connect to a non-loopback local address is refused/not served).
- **DecryptPass:** `media`/`poster` surfaced from the outer payload for a
  video post; a photo post → `media="photo"`, `poster=null` (regression).
- **On-device (the playback proof):** a real video post in the feed shows
  its poster + ▶; tapping plays the decrypted mp4 full-screen with working
  seek (range requests); the desktop store already holds video posts.
- Existing gates stay green (module JVM suite, tsc A/B, vitest); the AVIF
  poster path is unchanged.

## Definition of done

The G20 feed shows video posts by their poster with a play affordance;
tapping plays the decrypted mp4 full-screen with seek, streamed in-memory
over localhost (nothing decrypted on disk). Desk-proven first (the
MediaServer token/range/loopback-binding gate + the DecryptPass surfacing).

## Risks / honest unknowns (resolve during build)

- **expo-video custom-source support** — confirm the Expo v57 video component
  plays an `http://127.0.0.1` URL with range requests cleanly (it should —
  it's a normal HTTP source to ExoPlayer). If a quirk blocks it, the
  loopback-server design still holds; only the RN wiring shifts.
- **Loopback server lifecycle** — start-once/reuse across taps, port/token
  stability within a session, clean teardown (no leaked listener thread) on
  module destroy. Pin the lifecycle like the B.2d-1 decode-service client.
- **Range-request correctness** — players issue byte-range and re-range on
  seek; the server's `Content-Range`/`Content-Length`/`206` must be exact or
  seeking breaks. The JVM gate covers this.
- **Token/loopback threat** — localhost is reachable by other apps on the
  device; the per-session token + 127.0.0.1-only bind + `(msgId,hash)`-only
  mapping are the mitigations. Confirm no path-traversal / arbitrary-hash
  read.
- **Player memory/teardown** — release the ExoPlayer on modal close so a
  long video doesn't leak; confirm on-device.

## Out of scope (named)

Video STORIES (next slice — reuse this player + MediaServer, but plaintext
and a different surface); the `media` (post) vs `media_kind` (story)
field-name difference (the stories slice's concern); composing/posting video
from the phone; background prefetch; adaptive streaming / codec negotiation
(`codec` is a static stamp); PiP / casting; any hearth change.
