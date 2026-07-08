# Kreds Profile Bento Canvas — Phase A — Design

**Date:** 2026-07-07
**Status:** Approved (design discussion, this session)
**Basis:** shipped profile block canvas (Slices 1–3c): blocks = `placement=profile` posts (text / photo / video); `KIND_PROFILE_LAYOUT` record holds per-block `order` + `grids` (photo layout); `set_profile_layout`/`set_block_grid`/`POST /api/profile-layout`/`POST /api/block-grid`; `profile_view` orders + annotates the wall; Arrange mode with pointer-drag reorder + Up/Down + an inline photo-grid `<select>`; `renderBlock` renders blocks in a vertical stack.
**Branch:** `kreds-profile-bento` off `main`
**Product context:** Phase A of the bento-canvas redesign (the profile becomes a sleek grid). Chosen over free-form 2D to de-risk; free x/y placement + corner-drag + two-finger pinch are **Phase B**. Internal package stays `hearth`.

---

## Why

The profile is a vertical stack with a janky reorder (3-line handle + Up/Down). Make it a **bento grid**: blocks choose a width and pack into a 3-column grid, edited via a clean per-block settings modal. This Phase A is deliberately constrained (width-only sizes, auto height, native CSS-grid packing, a menu-based resize) — the sleek-but-low-risk foundation; the tactile corner-drag/pinch is Phase B.

## Decisions locked this session

- **Three sizes, width-only (auto height):** `small` = 1 column, `wide` = 2 columns, `full` = 3 columns. New blocks default to **`full`** (so the canvas looks like today's stack until you shrink blocks into a bento).
- **Packing:** native **CSS 3-column grid**, blocks span their size, `grid-auto-flow: row dense`. No hand-rolled packing/collision engine. Honest tradeoff: with width-only sizes + auto height this is a column-spanning row grid, not true masonry — a shorter block can leave a small gap beneath it. Acceptable for Phase A (masonry/height-spans are Phase B territory).
- **Resize + per-block edit via a settings modal:** in Arrange mode, **tap/click a block → a small "block settings" modal** with: **size** (Small / Wide / Full), the **photo-grid layout** for multi-photo blocks (the 3b options, moved here from the cramped inline `<select>`), and **Move up / Move down** (keyboard/touch-accessible reorder). Delete-everywhere stays where it is (outside Arrange).
- **Tap vs drag:** in Arrange mode a **small drag reorders** the block (existing pointer-drag), a **tap/click without movement opens the settings modal** (distinguished by a movement threshold). The 3-line drag handle and the inline Up/Down + grid `<select>` are **removed from the block face** — the block body is the drag target, settings live in the modal.
- Grid choice (3b) still arranges photos **inside** a multi-photo block's cell — orthogonal to the block's bento size.
- Confidentiality unchanged: `size` is a style enum on already-disclosed opaque `msg_id`s.

## Components

### 1. `sizes` in the layout record (backend)
- `make_profile_layout(order, grids=None, sizes=None, now=None)` — payload adds `"sizes": sizes or {}` (map `{msg_id: "small"|"wide"|"full"}`). Latest-wins unchanged.
- `validate_payload` (`KIND_PROFILE_LAYOUT`): `sizes` is a dict, keys `_is_hex64`, values in `("small","wide","full")`, size ≤ `MAX_LAYOUT`; missing = `{}` (back-compat with Slice-2/3b records).
- `Store.profile_layout` returns `{"order":[...], "grids":{...}, "sizes":{...}}` (extend the dict; the single latest-wins winner backs all three).

### 2. Node
- `set_profile_layout(order)` and `set_block_grid(msg_id, grid)` now **preserve `sizes`** (read current, republish full record) — same read-modify-republish discipline already used for grids.
- New `set_block_size(msg_id, size)`: validate `size` in the enum + `msg_id` hex64 (→ ValueError → 400); read current record; `full` may be stored explicitly or treated as default — store the chosen value, or delete the entry when it equals the default to keep the map small (pick: store non-default, delete on default `full`); preserve `order` + `grids`; republish; cap-check `len(sizes) ≤ MAX_LAYOUT`.
- `profile_view` annotates each wall block with `row["size"] = sizes.get(msg_id, "full")` (default full).

### 3. API
- `POST /api/block-size` JSON `{msg_id, size}` → `node.set_block_size(...)`; `_400` on bad size/id. Self only.

### 4. Render (front-end)
- The wall becomes a **CSS 3-column grid** container: `display:grid; grid-template-columns:repeat(3,1fr); grid-auto-flow:row dense; gap`. Each block gets `grid-column: span N` from `p.size` (small=1, wide=2, full=3). Height auto. Mobile (≤ ~560px): 2 columns, spans clamped (full→2, wide→2, small→1). Blocks fill their column; media `object-fit: cover`; text blocks padded; the intra-block photo grid (3b) renders within the cell.
- `renderBlock` reads `p.size` (default full) for the span; existing media/text/video rendering unchanged inside the cell.

### 5. Edit interaction (front-end, Arrange mode, self)
- **Block settings modal** (`#block-settings` overlay, reusing the existing overlay pattern): opened by tapping a block in Arrange mode. Contains: size buttons (Small/Wide/Full, current highlighted) → `POST /api/block-size`; for a multi-photo block, the photo-grid options (Auto/2-col/3-col/Hero/Masonry) → `POST /api/block-grid`; Move up / Move down buttons (reorder + publish). Each action applies + updates the record + re-renders (in place where possible, else re-open). Esc/backdrop closes. All keyboard-accessible.
- **Tap vs drag:** on a block in Arrange mode, `pointerdown`→`pointermove` past a threshold (~6px) starts the existing pointer-drag reorder; a release under the threshold opens the settings modal. Remove the 3-line handle, the inline Up/Down buttons, and the inline grid `<select>` from the block face (their functions now live in drag + the modal).
- Non-Arrange (normal view + visitors): blocks render read-only at their sizes; no modal, no drag (unchanged gating: self + Arrange only).

## Testing

Backend/API (mirror the 3b grids tests):
- `make_profile_layout(sizes=...)` round-trips; `validate_payload` accepts a valid sizes map, rejects non-dict / non-hex64 key / bad enum / oversized; missing = `{}`.
- `set_block_size` sets a size, PRESERVES order + grids; default handling correct; `set_profile_layout`/`set_block_grid` PRESERVE sizes; latest-wins.
- `profile_view` annotates `size` (default full); unknown id in sizes inert (no throw).
- `POST /api/block-size` 200 + bad → 400.

Web asset/DOM:
- Wall is a 3-col grid (`grid-template-columns` / `span` classes present); `renderBlock` reads `p.size`.
- Block settings modal markup + size buttons + `/api/block-size`; the inline 3-line handle / inline grid `<select>` are gone from the block face; tap-vs-drag threshold logic present. Grid options now in the modal.
- `node --check`; honesty guard (no receipts).

Integration/smoke (record): set a block to `wide`/`small`/`full`; after sync a friend sees the block at that span in the bento; reorder preserves sizes; a multi-photo block's grid still set from the modal and preserved; mobile clamps to 2 columns; tap opens the modal, a drag reorders. Full suite green (keep flakes fixed).

## Out of scope (named)

- **Phase B:** free x/y placement, corner-drag resize, two-finger pinch, height/row spans (tall/big), true masonry, richer spring physics.
- Any encryption/scoping/protocol change beyond the `sizes` field on the existing record.

## Success criteria

- A block can be Small/Wide/Full via a per-block settings modal opened by tapping it in Arrange mode; blocks pack into a clean 3-column bento (2 on mobile); friends see your bento after sync.
- Tap opens settings, a drag reorders; the janky 3-line handle + inline buttons/select are gone (settings consolidated in the modal); size persists and survives reorder/grid changes.
- No confidentiality regression; honesty guards hold; all tests green plus the new ones. Free-form/pinch cleanly deferred to Phase B.
