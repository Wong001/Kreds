# Android B.2d-3 (stories) — design, 2026-07-20

Ninth slice of the Kreds Android client, after the Tor-dial spike (PR #1),
Brick A (#2), B.1 (#3), B.2 (#4), B.2c (#5), B.2d-1 photos (#6), Brick C
(#7), B.2d-2 video (#8). This slice renders STORIES: a 24h-ephemeral strip +
simple viewer. Pure phone-side, no hearth change.

## The standout fact (verified against hearth): stories are PLAINTEXT

`KIND_STORY` (messages.py:15) carries `{media_kind: "photo"|"video", media:
<hex64 blob hash>, poster: <hex64|None>, caption: <=200, created_at,
expires_at}` — ALL plaintext outer payload, NO `body_ct`/`wraps`/content
key. `compose_story` (node.py:815-833) stores blobs RAW: photo =
`put_blob(transcode(media, fmt="avif"))`, video = `put_blob(mp4)` +
`put_blob(poster_png)` — NO `encrypt_blob`. `_content_key` returns
`(None, None)` for `KIND_STORY` (it's not in the kind dispatch). 24h TTL
(`STORY_TTL=86400`, enforced at compose + validate). Stories broadcast to
every friend unconditionally (no scope gate). So: the phone renders story
media WITHOUT decryption — the blob bytes ARE the image/mp4.

Consequence for the render path: story photo = raw AVIF → decode WITHOUT
`decryptBlob` (but STILL through the isolated `ImageDecodeService` — a
story AVIF is friend-authored attacker-influenceable bytes, same RCE-surface
reasoning as post photos; only the decrypt step is dropped). Story video =
raw mp4 → `MediaServer` serving raw (no decrypt). Posture: story blobs are
plaintext BY DESIGN (broadcast, no content key), so serving/decoding them
raw introduces no confidentiality regression; the loopback token still gates
the video route.

## Scope (August, 2026-07-20)

Strip + simple viewer (dev-dashboard-minimal — real visual polish is the
later cross-platform-parity slice, per the mobile-architecture note). Plus
rendering RECEIVED story-reply DMs (a plaintext `story_ref` chip). NO
composing/replying from the phone (the first phone->outbound slice, deferred).

## Components

### 1. Store: story accessors + `missingBlobs` fix

- `activeStories(nowSeconds: Double): List<StoredStory>` — unexpired
  (`payload.expires_at > now`) `KIND_STORY` rows, each carrying
  `author`(=cert identity_pub), `mediaKind`, `media`(hash),
  `poster`(hash?), `caption`, `createdAt`. Newest-first; the module groups
  by author. Both `InMemorySyncStore` + `SqliteSyncStore`.
- **`missingBlobs()` fix** — today it scans `WHERE kind IN ('post','dm')`
  and pulls `blobs`/`thumbs` (SqliteSyncStore.kt:215; InMemory mirrors).
  Widen to also scan `story` and add its `media` + `poster` (single hex64
  fields, NOT lists) to the want-set — otherwise story media never
  downloads (the current real gap). Both impls.
- `getBlob(hash)` (exists) serves the raw plaintext story blob.

### 2. Module: plaintext render paths + story surface

- `getStoryImage(hash: String): String?` (async, ioScope) — `getBlob(hash)`
  → `KotlinImageDecode.toRenderable` (isolated AVIF decode; NO `decryptBlob`,
  NO `blobKeys` lookup) → `data:<mime>;base64,...` or null. Distinct from
  `getBlobImage` (which decrypts) — stories are plaintext.
- `getStoryVideoUrl(hash: String): String?` — the `MediaServer` serves the
  raw story blob on an explicit STORY route: `mediaServer.urlFor(STORY_MARKER, hash)`
  where the module's resolver does `if (msgId == STORY_MARKER)
  store.getBlob(hash) else <the existing decrypt path>`. Explicit routing —
  a post hash under the story marker would serve unplayable ciphertext (no
  plaintext leak), and a story hash is plaintext-by-design anyway. The
  token/loopback-only guarantees are unchanged (MediaServer itself is not
  modified — only the injected resolver gains the story branch).
- `getStories(): List<StoryItem>` — `store.activeStories(now)` grouped by
  author, author display name via the existing `profileNames`; each item:
  `{author, authorName, mediaKind, media, poster, caption, createdAt}`.
- `index.ts`: `getStories()`, `getStoryImage`, `getStoryVideoUrl` +
  `StoryItem` type.

### 3. App.tsx: strip + viewer + story-reply chip

- A story strip above the feed: `getStories()` → author chips (name +
  unexpired count), refreshed on mount + after sync (`onSync`).
- Tap an author → the existing fullscreen `Modal` cycles that author's
  stories one at a time: photo via `getStoryImage` (an `<Image>` from the
  data URI), video via the `expo-video` player pointed at
  `getStoryVideoUrl`, plus the `caption`. Tap to advance to the next story;
  tap/back past the last one closes. Release the player on close (as B.2d-2).
- A received DM whose payload carries `story_ref` renders a "replied to your
  story" chip + thumbnail via `getStoryImage(story_ref.media_hash)` on the
  DM row (the DM itself already renders from B.2c; the chip is additive and
  reads the plaintext `story_ref`).
- Non-story surfaces (feed, photos, video, sync status) unchanged.

## Data flow

```
sync -> KIND_STORY stored; missingBlobs now includes story media/poster -> blobs fetched
strip: getStories() -> activeStories(now) grouped by author -> chips
tap author -> Modal cycles their stories:
   photo: getStoryImage(hash) = getBlob -> isolated AVIF decode (NO decrypt) -> data URI
   video: getStoryVideoUrl(hash) = MediaServer STORY route -> raw blob (NO decrypt)
received DM w/ story_ref -> chip + getStoryImage(story_ref.media_hash)
```

## Testing

- **Store (JVM, InMemory + committed KIND_STORY messages):** `activeStories`
  returns only unexpired rows (an `expires_at <= now` story is excluded),
  grouped/orderable by author, with the right fields; `missingBlobs` now
  includes a story's `media` + `poster` hashes (and still includes post/dm
  blobs — regression).
- **Plaintext routing:** the module resolver's STORY-marker branch serves
  `getBlob` raw (no decrypt) vs the decrypt path for post hashes — module-
  level (no Robolectric), verified by code review + on-device; `MediaServer`
  itself is unchanged (its token/range/loopback gate stays as B.2d-2 proved).
- **On-device (the proof):** an unexpired story shows in the strip; tapping
  shows the photo (isolated-decoded) or plays the video + caption; an
  expired story is absent from the strip; a received story-reply DM shows
  the chip + thumbnail. The desktop store already holds stories (post one
  from the desktop if needed — 24h TTL, so post fresh before the run).
- Existing gates stay green (module JVM suite, tsc A/B, vitest); the
  post/photo/video paths and the `getBlobImage` decrypt path are untouched.

## Definition of done

The G20 shows a story strip of unexpired stories; tapping an author cycles
their photo/video stories with captions; expired stories don't appear;
received story-reply DMs show a chip. Story media downloads (the
`missingBlobs` fix). No decryption in the story path; no hearth change.
Desk-proven first (the store accessors + missingBlobs), then on the G20.

## Risks / honest unknowns (resolve during build)

- **The plaintext-vs-decrypt split** — the render path must NEVER run
  `decryptBlob` on a story blob (there's no key; it would just fail) and
  must never run the raw path on a post blob (would serve ciphertext). The
  split is explicit (separate `getStoryImage`/`getStoryVideoUrl` + the
  STORY-marker resolver branch), not inferred — confirm no crossover.
- **`missingBlobs` field shape** — story `media`/`poster` are single hex64
  strings, NOT the `blobs`/`thumbs` LISTS that posts/dms use; the widened
  scan must handle both shapes.
- **TTL boundary** — filter `expires_at > now` at read; an expired story
  message may still sit in the store (GC is the desktop's job) — the phone
  just excludes it from the strip.
- **Story media size / MediaServer** — story video reuses the <=5MB-capped
  MediaServer path; story photos are AVIF like posts. No new size concern.
- **Isolated decode still applies to story photos** — a friend-authored
  story AVIF is still attacker-influenceable; it MUST route through the
  isolated `ImageDecodeService`, not decode in-process. `getStoryImage`
  calls `KotlinImageDecode.toRenderable` (which routes AVIF to the isolated
  service) — same as posts, just without the preceding decrypt.

## Out of scope (named)

Composing/replying to stories (the first phone->outbound slice); progress
bars / auto-advance / swipe / polish (the visual-parity slice); posting
stories from the phone; responses/reactions (B.2d-4, the final richer-feed
slice); any hearth change.
