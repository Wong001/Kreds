# Kreds Profile Canvas — Slice 2: Arrangement Editor + Fixes — Design

**Date:** 2026-07-07
**Status:** Approved (design discussion, this session)
**Basis:** shipped Slice 1 (profile = block canvas of `placement=profile` posts rendered as text/photo blocks; Journal rail; Me = profile). Blocks are immutable, signed, per-recipient-encrypted posts.
**Branch:** `kreds-profile-arrange` off `main`
**Product context:** Slice 2 of the block-layout profile builder. Adds arrangement + two fixes found in Slice-1 testing. Internal package stays `hearth`.

---

## Why

Slice 1 gave a typed block canvas but blocks only render newest-first — you can't "set it up the way you want." Slice 2 adds an **arrangement editor**. Two fixes found while testing Slice 1 are folded in because they live in the same render code:
- **Fix A (real bug):** a viewer sees the profile in the *identity-derived* hue instead of the owner's chosen **accent / uploaded banner / avatar**. `/api/profile` already returns `accent`; the profile page ignores it for non-self.
- **Fix B (UX clarity):** a block's audience (Inner/Kreds) isn't visible, so Inner-scoped posts silently don't reach Kreds-only friends, and — per the shipped **"ring moves re-key future posts only"** rule — moving someone into a ring never unlocks past posts. The model is correct; the UI must make it legible.

## Decisions locked this session

- **Reorder via an "Arrange" mode with Up/Down controls** (keyboard/touch accessible), behind the cogwheel. Drag-and-drop deferred.
- **Editing a block = delete + repost** (honest with immutable signed posts). No in-place/versioned edit this slice.
- **Order persists in a signed, latest-wins profile-layout record** (an ordered list of block `msg_id`s) that gossips to friends, so viewers see the owner's arrangement. Blocks not yet in the record appear at the top (newest-first) until arranged.
- **Fix A:** the profile page uses the profile's own `accent` + uploaded `banner`/`avatar` for every viewer. Identity colors remain for the feed/chips/circle.
- **Fix B:** each block on your *own* canvas shows an audience badge (Inner/Kreds); the profile composer gets a short honest note (Inner is hidden from Kreds-only friends; ring moves only reveal future posts).
- Scope: vertical reorder only (split columns are Slice 3). Photo/text blocks only (video Slice 3).

## Components

### 1. Profile-layout record (backend)
- New signed message kind `KIND_PROFILE_LAYOUT`; `make_profile_layout(device, order, now)` where `order` is a list of `msg_id` hex strings. Latest-wins per author (by `created_at`), like the profile record. `validate_payload`: `order` is a list of hex64 strings (cap the length sanely, e.g. ≤ 500).
- Store: `profile_layout(identity_pub) -> list[str]` returns the latest layout order for that identity (or `[]`).
- The record carries only `msg_id` ordering — no content, no new confidentiality surface (it orders blocks the viewer can already see; unknown/undecryptable ids in the order are simply skipped at render).

### 2. Ordering (node)
- `Node.set_profile_layout(order)` publishes a new layout record (self only).
- `profile_view` returns the `wall` ordered for display: blocks whose `msg_id` is in the latest layout, in layout order; then any wall block NOT in the layout, newest-first, prepended at the top (fresh posts surface until arranged). Undecryptable/expired blocks are already excluded (Slice 1). Unknown ids in the layout are skipped.
- `GET /api/profile/{id}` thus returns `wall` already in display order (client renders in array order); optionally include the raw `layout` list too (not required — the client reorders the returned `wall`).

### 3. API
- `POST /api/profile-layout` JSON `{order: [msg_id, ...]}` → `node.set_profile_layout(order)`; self only (the node signs with its own device). Returns `{ok: true}`.

### 4. Arrange mode (front-end, self only)
- A cogwheel-adjacent **"Arrange"** toggle (self profile only) puts the canvas into arrange mode: each block shows **Up** / **Down** controls (buttons, keyboard-focusable, with aria-labels) and its delete control. Moving a block reorders the DOM only (no network per click). On **"Done"** (exit arrange mode), the final `msg_id` order is POSTed once to `/api/profile-layout` and the canvas re-renders — one signed layout record per arrange session, not per move.
- The current display order (from the ordered `wall`) is the starting order; Up/Down swap adjacent blocks. First block's Up and last block's Down are disabled.

### 5. Fix A — viewer sees the owner's setup
- In `renderProfilePage`, the profile color = `p.accent` (owner's chosen accent; always present, default `#2743d6`) for **all** viewers; the banner uses `p.banner` image if set else the accent fill; the avatar uses `p.avatar` image if set else the accent-colored initial. Remove the `identityColor` usage on the profile page (keep it in feed/chips/circle).

### 6. Fix B — scope legibility
- On your **own** canvas, each block shows a small **audience badge** (Inner / Kreds) derived from `p.scope`. (Others' profiles: no badge — they already only see what they can decrypt.)
- The profile composer shows a one-line honest note near the Inner/Kreds selector: e.g. "Inner posts are visible only to your Inner kreds. Moving someone into a ring only reveals future posts." ASCII/UI copy fine.

## Testing

Backend/API:
- `make_profile_layout` + `validate_payload` (order = hex64 list; rejects non-hex / over-cap); latest-wins (a newer layout supersedes).
- `set_profile_layout` then `profile_view` returns `wall` in the specified order; a block not in the layout is prepended newest-first; an unknown `msg_id` in the layout is skipped without error.
- `POST /api/profile-layout` orders the wall; a viewer (`/api/profile`) sees the owner's arranged order (layout record synced) — for blocks they can decrypt.
- No confidentiality change: a non-recipient still can't see a block just because it's named in the layout.

Web asset/DOM:
- Arrange mode: an "Arrange" toggle (self only); Up/Down controls present in arrange mode; posts to `/api/profile-layout`.
- Fix A: `renderProfilePage` uses `p.accent`/`p.banner`/`p.avatar` (assert it no longer calls `identityColor` for the profile page color; feed/chips/circle still do).
- Fix B: a per-block audience badge on the own canvas; the composer note string present.
- Honesty guard: no receipts popover. `node --check`.

Integration/smoke (record): arrange blocks on your profile → a friend sees the same order; a fresh post appears at the top until arranged; a viewer sees your accent (not the identity hue) and your uploaded banner/avatar; Inner block badge shows on your canvas; delete still works; reorder is keyboard-operable.

All existing tests stay green (update any that asserted newest-first-only wall order). Full suite under an explicit timeout. Watch for the known pre-existing intermittent flake (re-run to confirm green).

## Out of scope (named)

- Drag-and-drop reordering.
- In-place / versioned block content editing.
- Split columns, video blocks, configurable grid layouts (Slice 3).
- Any change to encryption/scoping/placement beyond the ordering record.

## Success criteria

- You can enter an Arrange mode and reorder your blocks (Up/Down, accessible); the order persists and a friend sees your profile in that order.
- Fresh posts surface at the top until you arrange them; deletion still works.
- Viewers see your chosen accent + uploaded banner/avatar (not the identity hue); each block on your own canvas shows its Inner/Kreds audience; the composer explains the scope consequence.
- No confidentiality regression; honesty guards hold; all tests green plus the new ones.
