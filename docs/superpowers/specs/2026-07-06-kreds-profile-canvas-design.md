# Kreds Profile Block Canvas — Slice 1 Design

**Date:** 2026-07-06
**Status:** Approved (design discussion, this session)
**Basis:** shipped curated profile (`#view-profile` page; profile posts = `placement=profile`, per-recipient encrypted, text + photos already supported by `compose_post`/`/api/post`; Wall + Journal; Me tab = your profile with a self-only Friends/Devices strip).
**Branch:** `kreds-profile-canvas` off `main`
**Product context:** Slice 1 of the block-layout profile builder (Model B: the profile becomes an arrangeable canvas of typed blocks). Internal package stays `hearth`.

---

## Why

The profile should become an arrangeable canvas of typed blocks (the user's vision). Chosen architecture: **blocks = profile posts** rendered by type — reusing the shipped encryption/scoping/photos/deletion rather than a new content system. Decomposition: **Slice 1** = the canvas render + photo upload + Journal relocation; **Slice 2** = arrangement editor (reorder/edit/delete); **Slice 3** = rich blocks (video, split columns, grid layouts). This spec is Slice 1 only.

## Scope decisions locked this session

- **Blocks = profile posts, type inferred from content.** Text-only → text block; has photos → photo block. No stored `block_type` this slice (inferred at render); no arrangement record this slice. Those land in Slices 2–3 where reordering/video/split need them.
- **v1 block types: text + photo** (photo block renders 1 photo big, several as a gallery grid). **Video deferred to Slice 3** (needs the ffmpeg/transcode pipeline posts don't have yet).
- **Journal relocates:** desktop = a compact **Journal rail** in the right column (recent decryptable journal posts of the profile being viewed); on your own profile, **Friends + Devices** stack below the rail (self-only). Visitors see only the rail. Mobile = Journal opens via a **tab/button**; Friends/Devices stack below the canvas (self-only).
- **No protocol/crypto/store change.** Photos on profile posts already work; this is a front-end slice plus (at most) a read-shape convenience.
- Blocks render **newest-first** for now; **drag-to-arrange is Slice 2**.
- Unchanged: Inner/Kreds scope selector on the composer, cogwheel editor overlay, deletion path, "profile posts never appear in the home feed," honesty (no receipts popover), decrypt-filtering (you see only blocks/journal you can decrypt).

## Components

### 1. Profile composer: photo attach
- The "Post to your profile" composer (self-only, above the canvas) gains a photo picker, mirroring the journal composer's photo `<input type=file multiple accept=image/*>` pattern. On submit it sends `text` + `scope` + `placement=profile` + `photos[]` to `/api/post` (all already accepted server-side). Keeps the Inner/Kreds selector (default Kreds).
- Clears photos + text on success; on `!r.ok` alert + keep (matches the existing composer's error handling).

### 2. Profile main area = block canvas
- Replace the plain "Wall" list render with a **block-canvas renderer**. For each of the profile's `wall` posts (already returned decrypt-filtered by `/api/profile`), render a **block** by inferred type:
  - **Text block:** the post's text, styled as a canvas text block.
  - **Photo block:** the post's photos (via `/api/post-blob/{msg_id}/{h}`). **One photo → big** (full-width of the canvas column); **several → a gallery grid**. Caption/text (if any) shown with it.
- Blocks render newest-first (by `created_at`), full-width in the canvas column. This is a dedicated renderer (`renderBlock`), NOT the small journal `buildEntry` — the canvas is visually distinct (large media). Delete-everywhere affordance per block (self), routing through the existing `deleteEverywhere` + re-render (the profile re-render fix from the prior slice).
- Honest empty state ("Your profile is a blank canvas — post something." for self; "Nothing here yet." for others).

### 3. Journal relocation
- Remove the inline "Journal" section from the profile main area.
- **Desktop:** the right column shows a compact **Journal rail** — the profile's `journal` posts (decrypt-filtered, from `/api/profile`), rendered compactly (small entries, scrollable). This shows on every profile (yours and others'). On **your own** profile, the existing **Friends** and **Devices** panels stack **below** the Journal rail (self-only, unchanged behavior). Visitors see only the Journal rail.
- **Mobile:** the right column collapses; the Journal is reachable via an **in-page "Journal" button/disclosure** on the profile that reveals (toggles) the journal list — NOT a change to the mobile tab bar (whose "Me" tab now maps to the profile). Friends/Devices stack below the canvas (self-only), as today's mobile stack.
- The Journal rail content = the same `journal` array already in the profile payload; no new endpoint.

### 4. Layout
- `#view-profile` two-column layout: main column = header (banner/avatar/bio) + composer (self) + **block canvas**; right column = **Journal rail** (+ self-only Friends/Devices below). Single-column stack on mobile with the Journal behind a tab/button.
- Reuse existing tokens/panels; the block canvas gets its own styles (big photo, gallery grid, text block).

## Testing

Web asset/DOM tests (`tests/test_web_assets.py`, extended):
- Profile composer has a photo input (self); posts with `placement=profile` + photos.
- The profile main area renders a block canvas (`renderBlock`/canvas container present), not the old flat Wall list; a Journal **rail** container exists in the right column; the inline profile "Journal" section is gone from the main area.
- Photo block logic: assert the renderer references `/api/post-blob` and distinguishes single vs multiple photos (big vs gallery).
- Self-only: Friends/Devices strip still gated to `p.mine`; visitors see only the Journal rail.
- Honesty guard: no receipts popover.
- `node --check hearth/web/app.js`.

Integration/smoke (record): a profile post with a photo appears as a photo block on the canvas and NOT in the home feed; a text post → text block; a journal post → the Journal rail (not the canvas) and the home feed; big vs gallery renders by photo count; on your own profile the Journal rail + Friends/Devices stack; a visitor sees the canvas + Journal rail only (no Friends/Devices); delete-everywhere on a block removes it from the canvas.

Backend behavior is unchanged; existing tests stay green. Full suite under an explicit timeout.

## Out of scope (named)

- Arrangement editor — reorder/drag/edit-in-place (Slice 2).
- Explicit stored `block_type` + the arrangement/layout record (Slices 2–3).
- Video blocks, split-column blocks, configurable multi-photo grid layouts (Slice 3).
- Any protocol/crypto/store change.

## Success criteria

- You can attach photos to a profile post; it renders as a photo block (big for one, gallery for several) on your profile canvas, and never in the home feed.
- The profile main area is a block canvas (text + photo blocks, newest-first); the Journal is a compact right-column rail (desktop) / tab-or-button (mobile), with your Friends/Devices stacked below the rail on your own profile only.
- Visitors see the canvas + Journal rail; never your Friends/Devices. Honesty guards hold; all tests green plus the new asset tests.
