# Profile-load speed: render honesty + sync ordering + thumbnails + AVIF (design, 2026-07-18)

Loading a new friend's profile takes 1-2 minutes today. Three compounding
causes, mapped in code:

1. Blob bytes move separately from posts, ~11 MB per sync round
   (`BLOB_GIVE_BUDGET`, sync.py), and onion peers sync once per 45s
   (`ONION_SYNC_INTERVAL`) - a photo-heavy backlog needs several rounds.
2. The give order is hash-sorted (effectively random) - one 9 MB video
   can eat a round while twenty small images wait.
3. Sharpest: the profile page never self-heals. Not-yet-synced images
   render as broken glyphs (no onerror handler), and the WS "changed"
   refresh re-renders the journal but NOT an open profile - the wall
   stays broken until the user navigates away and back.

One combined slice (August, 2026-07-18), four parts. No visual size
change anywhere - tiles keep their exact dimensions; this is loading
strategy only.

## Part 1 - Render honesty

- Post/deck/journal `<img>`s get an `onerror` fallback: a `.img-pending`
  placeholder (soft neutral shimmer tile, sized by the same cell/box the
  image occupies), mirroring the avatar letter-fallback pattern
  (app.js:299-305). No broken glyphs, ever.
- `refresh()` (WS "changed", app.js:4165) also re-renders the CURRENT
  profile when the profile view is active - that is how pending tiles
  heal as blobs arrive. Guards: skip while `ARRANGING` (a re-render
  would tear the drag surface out from under the pointer) and while the
  block-settings modal is open (it holds a reference to its block
  element). Both states are transient; the next "changed" tick after
  they end heals the wall.
- The lightbox is its own overlay with a captured items list - a wall
  re-render underneath it is harmless and does not close it.

## Part 2 - Sync ordering (small blobs first)

- The blob give loop (sync.py:632-639) sorts the peer's wanted hashes
  ascending by local blob size before filling the budget. Hashes the
  giver doesn't hold are skipped as today; ties keep hash order for
  determinism.
- Node-side only - no wire/protocol change, no compatibility event. An
  old peer talking to a new one simply receives smaller blobs first;
  the reverse direction behaves as today.
- Effect: thumbnails (Part 3), avatars, and small photos all land in
  round 1; large media fills later rounds.

## Part 3 - Thumbnails on post records

- **Generate:** new `photo_thumb(gated_bytes) -> bytes` in
  `hearth/imagegate.py`: decode the already-gated full image, downscale
  to <=640 long edge, encode AVIF quality ~50 (Pillow 12 native AVIF -
  no new dependency), target <=25 KB typical. `transcode_photo`'s
  signature stays unchanged.
- **Record:** additive `thumbs` list on post payloads (`make_post`),
  index-aligned with `blobs` (same length; entries may be null if thumb
  generation failed - never block a post on its thumbnail). Same
  additive pattern as `poster`/`codec`: old clients/cores ignore it.
- **Storage/crypto:** each thumb is encrypted with the SAME per-post
  content key as its parent blob and stored via `put_blob` like any
  blob. `referenced_blobs()` (store.py) includes `thumbs` so they sync,
  and tombstoning a post drops its thumbs with its blobs.
- **Serving:** `node.post_blob(mid, h)` accepts a hash that appears in
  the post's `blobs`, `thumbs`, OR `poster` (it must reject hashes not
  referenced by that post, as today).
- **Video posts:** `thumbs = [thumb-of-poster]` (AVIF of the poster
  frame). The wall `<video poster=...>` uses the thumb when present.
- **Render:** wall blocks, decks, album items, and journal photos use
  the thumb hash when the row carries one, full hash otherwise (old
  posts render exactly as today - no retro-thumbnailing). Tiles render
  thumbs PERMANENTLY - at card size they are indistinguishable - and
  only the lightbox loads full resolution (thumb data is already local
  by then in the common case; if the full blob hasn't synced yet the
  lightbox shows the upscaled thumb under a pending shimmer until the
  next heal tick).
  - `profile_view` / `feed` rows and `blockPhotoItems` carry the thumb
    hash alongside `{m, h}` (as `t`, null when absent).
- **DMs: no thumbs** (scope call: chat renders one or two images at
  bubble size; the pain is walls). DM photos DO get Part 4's format
  change.

## Part 4 - AVIF for the big bytes

- `transcode_photo`'s JPEG quality ladder becomes an AVIF ladder
  (qualities tuned during build to land at-or-below today's JPEG sizes
  at equal visual quality; expected ~40-50% smaller). The
  PNG-input-stays-PNG rule is retired - screenshots gain the most from
  AVIF. Animated-GIF passthrough is unchanged.
- Story stills switch PNG -> AVIF (`transcode()` gains a format param;
  stories pass AVIF). Video posters (videogate's `image_transcode`
  call) switch to AVIF the same way. Avatars and banners STAY PNG -
  small, plaintext, not worth the churn.
- The dimension-halving fallback and the `PHOTO_CAP` byte ceiling keep
  working unchanged around the new encoder.
- **Compatibility: no gating event.** Blobs are opaque to store/sync;
  decoding happens in the client's Chromium (WebView2/desktop browsers
  decode AVIF natively; iOS 16+/Android 12+ cover the future mobile
  floor). Only CREATING AVIF needs the new core, which is just the
  update itself. `min_core_for_web` stays untouched this release.

## Expected effect (the honest math)

A wall of ~20 photos: visible payload drops from ~20-60 MB of JPEG to
~0.5 MB of thumbs, which fit in the FIRST sync round alongside the
records - the wall paints in seconds even at Tor speeds, sharpening
tile-by-tile is gone (thumbs are the tile-resolution artifact), and the
full-res AVIFs finish syncing in the background at roughly half today's
byte count, needed only when a lightbox opens.

## Testing

- imagegate: thumb is AVIF, <=640 long edge, small (assert a sane byte
  ceiling for a synthetic photo); AVIF ladder output decodes as AVIF and
  respects `PHOTO_CAP`; GIF passthrough untouched; failure path (thumb
  gen error) yields null thumb + successful post.
- store/messages: `thumbs` validates leniently, `referenced_blobs`
  includes them, delete drops them.
- node: post round-trip carries index-aligned thumbs; `post_blob`
  serves thumb hashes and still rejects unreferenced hashes; video post
  thumb-of-poster.
- sync: two-node integration - a backlog with one large + several small
  blobs arrives small-first under the budget (assert the large blob is
  NOT in round 1's give when the budget forces a choice); thumbs from a
  fresh post sync to the friend.
- client: asset pins for `.img-pending`, onerror fallback, thumb-hash
  preference, profile-heal-on-WS with the ARRANGING/modal guards.
- UI smoke (UI_E2E): post photos on A, friend B's wall renders tile
  `<img>` pointing at the THUMB hash; lightbox opens the full hash.

## Out of scope (named)

DM thumbnails; retro-thumbnailing old posts; avatar/banner format
change; blur-up transitions; chunked/streamed blob transfer; sketch
based have-exchange (separate ROADMAP item); any visual redesign.

## Addendum (approved by August, 2026-07-18): journal feed photo cap

A portrait photo posted to the journal rendered at full natural height
and ate the page (the feed rule was max-width only). Fix, riding this
slice: journal FEED photos get `max-height: min(480px, 60vh)` with
width free within the column (aspect preserved; landscape effectively
unaffected). Scoped to `#view-journal` via an `.epic` class set in
`buildEntry` - the profile journal rail (narrow column, sizing already
right per August) is untouched, and the class scoping leaves the 0.3.13
`.eavatar` specificity fix unchallenged. Journal photos now also open
the shared fullscreen lightbox (full-res hash), so the cap is never the
only view of a photo.
