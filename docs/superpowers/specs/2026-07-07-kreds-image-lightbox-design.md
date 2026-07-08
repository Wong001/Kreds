# Kreds Image Lightbox — Design

**Date:** 2026-07-07
**Status:** Approved (design discussion, this session)
**Basis:** shipped profile block canvas + bento (blocks render `<img>` inside `.block-photo` / `.block-gallery` / `.block-grid-2/3` / `.block-hero` / `.block-masonry`, `src=/api/post-blob/{msg_id}/{hash}` — already served decrypted). Fullscreen-overlay precedent: `#story-viewer`. Photo block's photos are `p.blobs` (a hash list) on each wall/feed row.
**Branch:** `kreds-image-lightbox` off `main`
**Product context:** click-to-enlarge + swipe through a photo block's photos, on own and others' profiles. Front-end only. Internal package stays `hearth`.

---

## Why

Photos on a profile are only viewable at their in-grid size. Let a viewer click/tap a photo to open it fullscreen and swipe/arrow through the photos in that block — the expected gallery interaction, identical on your own and friends' profiles (view-only).

## Decisions locked this session

- **Swipe scope = within the clicked block** (`p.blobs`), not across the whole profile. A single-photo block shows the one photo, no arrows.
- **Fit-to-screen + swipe, no zoom** (matches the story viewer; zoom is a later add).
- **View-only, own + others** identical. **Excluded:** video blocks (own `<video>` player) and Arrange mode (there a tap opens the block settings modal — the lightbox fires only in normal view, gated on `!ARRANGING`).
- **Scope = profile photo blocks.** Home-feed photos are a cheap later follow-up (named, not built).
- No backend/crypto change — blobs already serve decrypted via `/api/post-blob`.

## Components

### 1. `#lightbox` overlay (index.html + CSS)
- A fullscreen overlay mirroring `#story-viewer`: `position: fixed; inset: 0;` dark backdrop, `role="dialog" aria-modal="true" aria-label="Photo"`, `hidden` by default. Contains: a fit-to-screen `<img id="lightbox-img">` (`max-width/height: 100%`, `object-fit: contain`); a **prev** and **next** button (`aria-label` "Previous photo"/"Next photo"); a **counter** (`#lightbox-count`, e.g. "3 / 5"); a **close** ✕ button (`aria-label "Close"`). CSS in Kreds tokens.

### 2. Lightbox controller (app.js)
- `openLightbox(msgId, blobs, index)`: store `{msgId, blobs, i}` in module state; set the img `src=/api/post-blob/{msgId}/{blobs[i]}`; update the counter; show/hide arrows (hidden when `blobs.length === 1`, prev disabled at 0, next disabled at last — clamp, no wrap); unhide the overlay; move focus to the close button; remember the opener element to restore focus on close.
- `lightboxGo(delta)`: clamp `i+delta` to `[0, blobs.length-1]`; swap the img src + counter + arrow disabled states.
- `closeLightbox()`: hide the overlay; restore focus to the opener.
- Navigation: prev/next buttons; **ArrowLeft/ArrowRight** keys; **Esc** closes; backdrop click (target is the overlay itself) closes; **touch swipe** (pointerdown→pointerup horizontal delta past a threshold, e.g. 40px → prev/next). Keyboard handlers active only while open.

### 3. Trigger (renderBlock, app.js)
- In the photo branch (`else if (p.blobs && p.blobs.length)`), give each `<img>` a click handler: `img.style.cursor = "zoom-in"; img.onclick = () => { if (!ARRANGING) openLightbox(p.msg_id, p.blobs, idx); };` where `idx` is the photo's index in `p.blobs`. In Arrange mode the block's pointerdown/tap opens the settings modal (unchanged) and the lightbox does not fire.
- Video blocks unchanged (no lightbox). This wiring lives wherever a photo block renders (profile wall); the feed renderer is out of scope.

## Testing

Web asset/DOM (mirror existing `tests/test_web_assets.py` structure):
- `#lightbox` present in index.html with img/prev/next/counter/close; `openLightbox` + `closeLightbox` + arrow/Esc/swipe wiring present in app.js; the photo `<img>` gets a click opener gated on `!ARRANGING`; `object-fit: contain` (or the fit rule) + a `#lightbox` fixed-position rule in CSS. `node --check`. Honesty guard: no receipts.
- (Behavioral verification — click-to-open, swipe, arrows, Esc/backdrop close, clamp at ends, focus return, not-in-Arrange, single-photo-no-arrows — is done by the user per the testing-workflow split; the automated tests assert presence/wiring, not pixels.)

## Out of scope (named)

- Zoom/pan (pinch, double-tap); home-feed photo lightbox (cheap follow-up); banner/avatar enlarge; cross-block/whole-profile swipe; video in the lightbox; any backend/encryption change.

## Success criteria

- Clicking a photo in a profile photo block (own or friend's, normal view) opens it fullscreen; ←/→, on-screen arrows, and touch swipe move through that block's photos (clamped; single photo = no arrows); Esc/✕/backdrop close and focus returns to the clicked photo; the lightbox never fires in Arrange mode or on video blocks.
- Front-end only, no backend/crypto change; automated asset tests + full suite green; honesty guards hold.
