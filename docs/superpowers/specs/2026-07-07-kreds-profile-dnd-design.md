# Kreds Profile Canvas — Slice 3a: Drag-and-Drop Reorder — Design

**Date:** 2026-07-07
**Status:** Approved (design discussion, this session)
**Basis:** shipped Slices 1–2 (profile = block canvas of `placement=profile` posts; self-only Arrange mode with Up/Down; latest-wins `KIND_PROFILE_LAYOUT` record + `POST /api/profile-layout` persist order; `Store.profile_layout`; `profile_view` orders the wall).
**Branch:** `kreds-profile-dnd` off `main`
**Product context:** Sub-slice 3a of the block-layout builder — the first of the "tractable three" (DnD → grids → video). Internal package stays `hearth`.

---

## Why

Slice 2's Up/Down Arrange mode works but isn't sleek. This adds **pointer drag-and-drop** to reorder blocks — the polished gesture — while keeping Up/Down as the accessibility backbone. Per the project's quality-over-shortcuts principle, this is **hand-rolled on Pointer Events**, not the native HTML5 DnD API (which has no touch support, an ugly uncustomizable drag ghost, and a janky feel). Pointer Events give one code path for mouse + touch + pen, full styling control, and no dependency.

## Decisions locked this session

- **Hand-rolled Pointer-Events drag** (mouse + touch + pen), NOT native HTML5 DnD. No library — Pointer Events are a web standard; keeps the no-dependency/self-hosted ethos.
- **Up/Down retained** as the keyboard/screen-reader path (pointer drag has no keyboard story). Drag is additive, not a replacement.
- **Persistence unchanged:** drag reorders the DOM only; **"Done" publishes the resulting order** via the existing `KIND_PROFILE_LAYOUT` record + `POST /api/profile-layout`. No backend/protocol change — front-end only.
- Drag is active only in the existing **self-only Arrange mode**; a visitor can never drag/reorder another profile (already enforced server-side: `set_profile_layout` signs the local identity).
- Vertical single-column reorder only (split-columns / 2D remain deferred).

## Components (all front-end: `hearth/web/app.js`, `style.css`; a drag-handle element in the block)

### 1. Drag interaction (Pointer Events)
- In Arrange mode, each own block gets a **drag handle** (grip icon). `pointerdown` on the handle starts a drag: `setPointerCapture`, record the block + start Y; add `touch-action: none` on the handle (via CSS) so a touch-drag doesn't scroll the page.
- `pointermove`: the dragged block follows the pointer via a `transform: translateY(...)` (or a floating clone styled in Kreds tokens); compute the target insertion index from the pointer Y relative to the other blocks' midpoints; show a **drop-line indicator** between blocks at the target position. Prefer **live reordering during `pointermove`** (blocks shift to make room as you drag — the sleeker feel) over deferring to drop; either way the DOM must reflect the final order when the drag ends so "Done" reads it correctly.
- `pointerup`/`pointercancel`: settle the block into the drop slot (a short transform transition for smoothness), remove the indicator, release capture.
- **Edge auto-scroll:** while dragging near the top/bottom of the scroll container, auto-scroll so long canvases are reorderable.
- Cleanliness: all listeners attached on drag start and removed on end; no leaked global handlers; a drag that starts then `pointercancel`s restores the original order.

### 2. Up/Down retained (a11y)
- The Slice-2 Up/Down buttons remain in Arrange mode exactly as they are (keyboard-focusable, `aria-label`, end-buttons disabled). Drag and Up/Down manipulate the same DOM order; "Done" reads it the same way. No change to their behavior.

### 3. Publish on Done (unchanged)
- Exiting Arrange mode ("Done") reads `#profile-wall` children's `dataset.msgId` and POSTs once to `/api/profile-layout`, then re-renders (the exact Slice-2 flow, including the `r.ok` failure handling that preserves the session). Drag does not itself publish; it only reorders the DOM.

### 4. Chrome
- Drag handle only visible in Arrange mode, self-only. A subtle "dragging" style on the lifted block; a Kreds-token drop-line. Respect `prefers-reduced-motion` (skip the settle transition).

## Testing

Web asset/DOM tests (`tests/test_web_assets.py`):
- A drag handle is present on blocks in Arrange mode; `pointerdown`/`pointermove`/`pointerup` handlers are wired (assert the handler names / `setPointerCapture` usage in app.js); `touch-action: none` on the handle in CSS.
- Up/Down controls still present in Arrange mode (not removed).
- "Done" still reads `dataset.msgId` order and POSTs to `/api/profile-layout` (unchanged).
- No new dependency added (no `<script src>` beyond app.js; no library import).
- Honesty guard: no receipts popover. `node --check hearth/web/app.js`.

Manual/Playwright smoke (record): in Arrange mode, drag a block with the mouse to a new position → the drop-line shows → releasing reorders it; "Done" persists and a friend sees the order after sync; a **touch** drag (emulated) reorders too; **keyboard** Up/Down still reorders; a drag near the bottom auto-scrolls; `pointercancel` mid-drag restores order. (Playwright installed; else document the limitation and assert via DOM.)

Backend unchanged; all existing tests stay green. Full suite under an explicit timeout (watch for the known pre-existing intermittent flake — re-run to confirm green).

## Out of scope (named)

- Configurable photo-grid layouts (3b), video blocks (3c), split-columns + versioned-edit (deferred).
- Any backend/protocol/store change (reuses the Slice-2 layout record).
- Reordering across columns / 2D layout.

## Success criteria

- In Arrange mode you can drag blocks (mouse and touch) to reorder, with a styled drag affordance + drop indicator and edge auto-scroll — sleek, no library, no backend change.
- Up/Down still work for keyboard/screen-reader users; "Done" persists the order and friends see it after sync.
- A cancelled drag restores the order; reduced-motion respected; no leaked listeners; honesty guards hold; all tests green plus the new asset tests.
