# Kreds Profile Canvas — Slice 3b: Configurable Photo Grids — Design

**Date:** 2026-07-07
**Status:** Approved (design discussion, this session)
**Basis:** shipped Slices 1–3a (profile = block canvas of `placement=profile` posts; photo blocks auto-grid by count; self-only Arrange mode with Up/Down + pointer drag; latest-wins `KIND_PROFILE_LAYOUT` record holds `order`; `profile_view` orders the wall; `POST /api/profile-layout`).
**Branch:** `kreds-profile-grids` off `main`
**Product context:** Sub-slice 3b of the block-layout builder (the second of the tractable three: DnD → grids → video). Internal package stays `hearth`.

---

## Why

Photo blocks currently auto-grid by count (1 = big, several = a 2-col gallery). The author should choose a photo block's layout from **five options**, and — because it's the owner's canvas styling — be able to **change it in place** on an already-posted block, not just at compose time.

## Decisions locked this session

- **Five layouts:** `auto` (default, current behavior), `cols2`, `cols3`, `hero` (first photo large + the rest in a row beneath), `masonry` (CSS multi-column flow, no JS balancing).
- **Re-stylable in place.** Because blocks are immutable signed posts, the grid choice lives NOT in the post but in the **mutable, owner-signed, latest-wins `KIND_PROFILE_LAYOUT` record** — extended from `order` to also carry a per-block grid map. Restyling republishes that record, exactly like reordering; it reuses the arrange path, not a new versioning protocol.
- Two entry points, both writing that one record: the **composer** (initial grid when attaching 2+ photos) and a **per-block grid picker in Arrange mode** (change an existing block).
- **Confidentiality unchanged:** the grid is a style enum keyed on already-disclosed opaque `msg_id`s (the same metadata surface Slice 2 documented); no content or new sensitive metadata.

## Components

### 1. Layout record gains per-block grid (backend)
- `make_profile_layout(device, order, grids=None, now=None)` — payload adds `"grids": grids or {}` (a map `{msg_id: layout}`). Latest-wins unchanged.
- `validate_payload` (`KIND_PROFILE_LAYOUT`): `grids` must be a dict, keys `_is_hex64`, values in `("auto","cols2","cols3","hero","masonry")`, size capped (≤ `MAX_LAYOUT`). `order` validation unchanged. Missing `grids` = `{}` (back-compat with Slice-2 records).
- `Store.profile_layout(identity_pub)` returns the latest record's `{"order": [...], "grids": {...}}` (both from the same latest-wins winner). (Change the return shape from a bare list to this dict; update callers.)

### 2. Node ordering + grid setters
- `Node.set_profile_layout(order)` now **preserves existing grids**: it reads the current record's `grids` and republishes `make_profile_layout(order, grids=current_grids)`. (Reordering never drops styles.)
- `Node.set_block_grid(msg_id, grid)`: validates `grid` in the enum + `msg_id` hex64; reads the current record; sets `grids[msg_id] = grid` (or deletes the entry when `grid == "auto"` to keep the map small); republishes with the current `order` preserved. Raises `ValueError` on bad input (→ 400).
- `profile_view` annotates each ordered wall block with `row["grid"] = grids.get(msg_id, "auto")` so the client just reads `p.grid`. (Ordering logic unchanged; only the grid annotation is added.)

### 3. API
- `POST /api/block-grid` JSON `{msg_id, grid}` → `node.set_block_grid(...)`; `_400` on a bad grid/id (mirrors `/api/profile-layout`). Self only (signs local identity).
- `POST /api/profile-layout` (existing, order-only) keeps working — `set_profile_layout` now preserves grids.

### 4. Front-end
- **Composer grid picker:** when 2+ photos are attached in the profile composer, show a small layout selector (the 5 options, default auto). On submit: post the block (existing flow), read the returned `msg_id`, and if the pick is non-auto `POST /api/block-grid {msg_id, grid}` before the re-render.
- **Per-block grid picker (Arrange mode, self, photo blocks):** each own photo block in Arrange mode shows a grid selector (button group or `<select>`, keyboard-accessible, `aria-label`); changing it `POST /api/block-grid` then re-renders. Bundled into the existing Arrange mode alongside reorder.
- **`renderBlock` applies the grid:** read `p.grid` (default auto). Map to CSS: `auto` → current `.block-photo`/`.block-gallery`; `cols2`/`cols3` → fixed-column grids; `hero` → first photo full-width + the rest in a small row; `masonry` → CSS `column-count` flow. A single-photo block ignores grid (always big).
- CSS for the five, in Kreds tokens; images keep `alt=""`.

## Testing

Backend/API:
- `make_profile_layout(order, grids=...)` round-trips; `validate_payload` accepts a valid grids map, rejects a non-dict, a non-hex64 key, a bad enum value, and an oversized map; missing `grids` = `{}`.
- `set_block_grid` sets a block's grid and PRESERVES order; `auto` removes the entry; `set_profile_layout` (reorder) PRESERVES existing grids; latest-wins across records.
- `profile_view` annotates each wall block with its grid (default `auto`); an unknown id in `grids` is simply not rendered (no throw), consistent with the order handling.
- `POST /api/block-grid` sets the grid; a bad grid/id → 400. `/api/profile-layout` reorder keeps grids.

Web asset/DOM:
- Composer shows a grid picker for multi-photo; posts + sets the grid.
- Arrange mode: a per-block grid selector on own photo blocks; `POST /api/block-grid`.
- `renderBlock` references `p.grid` and has the five layout branches; CSS has `block-grid-2`/`block-grid-3`/`block-hero`/`block-masonry` (plus existing auto classes).
- Honesty guard: no receipts popover. `node --check`.

Integration/smoke (record): set a photo block to `cols3` (and `hero`, `masonry`); after sync a friend sees that block in the chosen layout; restyle it in Arrange mode → friend sees the change after next sync; reorder preserves grids; a single-photo block stays big. Full suite green (watch the now-fixed flakes stay fixed).

## Out of scope (named)

- Video blocks (3c); split-columns + versioned-edit (deferred heavy two).
- Per-photo reordering/cropping within a block, custom column counts beyond 2/3 (YAGNI).
- Any encryption/scoping change.

## Success criteria

- A photo block can be set to one of five layouts (auto/2-col/3-col/hero/masonry) at compose time AND restyled in place afterward via Arrange mode; the choice persists in the layout record and friends see it after sync.
- Reordering never drops a block's grid; a single-photo block stays big; `auto` matches today's behavior.
- No confidentiality regression (grid is a style enum on opaque ids); honesty guards hold; all tests green plus the new ones.
