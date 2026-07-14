# Wall collage redesign (pins + composer preview + albums) — design

Date: 2026-07-13
Status: approved direction (August, in-session, from his written sketch +
four structural decisions). Spec for review before writing-plans.
Bundles with the parked `kreds-quickwins-0.3.10` branch into one release;
all three slices build on top of that branch.

## Problem

Posting media to a profile is a form, not a canvas. Multiple photos force
a layout through a dropdown ("Auto" / 2 cols / 3 cols / Hero / Masonry);
nothing previews before posting; a block's size is width-only
(small/wide/full in a 3-column grid, auto height); and the Wall is
ultimately a decorated list — the user cannot compose a page. August's
sketch (2026-07-13): a fluid, Apple-like direct-manipulation wall — media
previews in the composer, everything freely resizable on an invisible
grid, photo albums as swipeable stacks that can grow later, and no
dropdown anywhere.

## Decisions made in-session (August)

1. **Grid geometry:** 4 columns of square-ish cells, infinite rows
   downward. Every block spans `w x h` cells. Media crops to fill
   (`object-fit: cover`); text scales type to its span.
2. **Placement model: free x/y pinning.** Blocks hold explicit cell
   coordinates. Empty cells are a legitimate design element ("holes are
   real"). Rejected: flow model (order + size, auto-packed) — less
   control than the collage August wants.
3. **Albums grow via a mutable grouping record** (the layout-record
   idiom), NOT by editing posts. Rejected: punting growth; rejected:
   versioned edit-in-place (parked protocol-level redesign).
4. **The five internal photo-grid layouts and the dropdown die.** A
   multi-photo block renders as a swipeable stack; photo grids are built
   by placing individual blocks on the canvas.

## Design

### 1. Canvas engine

- The Wall renders as a native CSS grid: `grid-template-columns:
  repeat(4, 1fr)`, row height = one CSS calc off the column width so
  cells are square-ish. No library, no absolute-px math (rejected:
  Gridstack/Muuri — new dependency; absolute positioning — breaks text
  auto-height and native reflow for no gain).
- A block with pin `{x, y, w, h}` renders at `grid-column: x+1 / span w;
  grid-row: y+1 / span h` (0-indexed pins, 1-indexed CSS).
- Constraints: `1 <= w <= 4`, `h >= 1` (cap 8), `0 <= x`, `x + w <= 4`,
  `0 <= y` (cap: MAX_LAYOUT rows). Video/photo fill their cells cropped;
  text blocks get span-scaled typography (4-wide = headliner, 1x1 =
  note); overflow ellipsis rather than cell growth — a pinned canvas
  must never have content-driven height surprises.
- **Mobile: the collage scales, never reflows.** The 4-column geometry
  is preserved at every viewport; cells shrink. The user composed a
  page; reflowing it would destroy the composition.

### 2. Pins, the unplaced tray, honest holes

- The existing `profile_layout` record gains a **`pins` map**:
  `{msg_id: {x, y, w, h}}`. Latest-wins, carried forward by every
  layout-record write exactly like `order`/`grids`/`sizes` are today.
- **Pinned** blocks render at their coordinates. Empty cells stay
  empty. A block the viewer cannot decrypt (Inner-scoped, deleted, not
  yet synced) leaves its gap — honest, accepted.
- **Unplaced** blocks (no pin — includes every new post): on the
  owner's own profile, Arrange mode shows an **"Unplaced" tray** above
  the canvas where new blocks wait at their composer-chosen size;
  outside Arrange, and for every visitor, unplaced blocks flow-pack
  *below* the pinned canvas, newest first — fresh content is never
  invisible while uncurated.
- **Legacy walls** (no `pins` yet — every wall today): all blocks are
  unplaced, so visitors see the newest-first flow, close to today's
  rendering. Legacy `sizes` values map to default spans: small -> 1x1,
  wide -> 2x2, full -> 4x2 (media) / 4x1 (text). The `grids` and
  `sizes` maps stop being written and stop being read; old records stay
  wire-valid (validation for those fields is retained), new clients
  ignore them beyond the legacy-default mapping.
- **Update skew** (allowed, same class as prior slices): an old client
  ignores `pins` and renders its Phase-A view; a new client on an old
  record sees the legacy mapping. No protocol bump — `validate_payload`
  already tolerates additive fields, and new-field validation is added
  for records that carry them.
- **Metadata honesty (documented in code where pins are read):** pins
  are plaintext geometry over opaque msg_ids — a Kreds-only friend can
  learn that *something* 2x2 sits at a coordinate they can't open,
  never its content. Same existence-disclosure class as the Slice-2
  `order` list; the same deferred per-scope-record idea would close
  both.

### 3. Direct manipulation (Arrange mode)

- **Move:** dragging a block (the existing pointer-events drag,
  extended from reorder semantics to 2D cell targeting) ghost-snaps to
  the hovered cell; the target highlights valid (free, in bounds) or
  invalid (overlap); release pins it there; an invalid drop snaps back.
  **No auto-push** in v1 — blocks never displace each other; push/
  ripple logic is deliberately rejected until real use demands it.
- **Resize:** a corner handle on each block drags in cell steps,
  clamped by canvas width and neighboring pins. Same valid/invalid
  affordance.
- **Keyboard / screen-reader path (mandatory, not optional):** the
  existing block-settings modal (tap block or its always-focusable gear
  button) gains size presets (1x1, 2x1, 2x2, 4x2, 4x3), nudge arrows
  (left/right/up/down one cell), and "send to tray" / "place on
  canvas". Nothing is pointer-only.
- Dragging from the tray onto the canvas pins a block; dragging off (or
  the modal action) unpins it back to the tray.
- Outside Arrange mode the canvas is inert (lightbox/playback only),
  exactly like today.

### 4. Composer (no dropdown, live preview)

- Attaching media transforms the wall composer: a **live preview card**
  renders the post as it will appear — one photo as a photo block,
  multiple photos as a stacked deck with a count badge, video as a
  local object-URL poster frame (the server gate still transcodes and
  can still reject on post; the preview is honest about being a
  preview).
- A row of **size chips** (1x1 / 2x2 / 4x2 / 4x3) sets the initial
  span, applied to the preview at true proportions. Default 2x2 media,
  4x1 text-only.
- Posting sends the block to the Unplaced tray at that size (the size
  travels as a pin-less `sizes`-successor: a `{w, h}` seed the client
  submits right after `/api/post`, exactly like the old grid seed —
  stored in the layout record's `pins` map is wrong for unplaced
  blocks, so seeds live in a small `spans` map `{msg_id: {w, h}}` used
  only while unplaced; pinning moves the geometry into `pins`).
- The `Auto` dropdown, the five layouts, and the compose-time grid seed
  call are removed.

### 5. Albums

- New **`album` message kind** (`KIND_ALBUM`), signed, latest-wins per
  `(identity, album_id)` — the `profile_layout` idiom, not a new
  mechanism. Payload: `{album_id (random hex64, same width as a msg_id
  so `pins`/`spans` keys stay uniformly 64-hex), members: [post msg_ids,
  ordered], created_at}`. Cap on members (MAX_LAYOUT).
- Members are ordinary immutable, per-recipient-encrypted photo posts
  (`placement=profile`). The album itself carries no content — opaque
  ids only, same honesty class as pins/order.
- **Rendering:** the wall pins the *album* (pin key = `album_id`) as one
  block — a stacked deck: top photo filling the cells, stack-edge
  affordance + member-count badge, swipe / arrow keys / lightbox
  navigation through the photos of members **the viewer can decrypt**
  (a fully-undecryptable album leaves an honest hole like any block).
- **Growing:** "Add photos" on an own album block posts new photo
  post(s) (scope chosen per post, composer default matches the album's
  newest member) and republishes the record with the new ids appended.
- A multi-photo *post* renders as a deck without any record; growing
  one mints an album record wrapping `[that post, new posts...]` — the
  deck is the concatenation of members' photos in member order.
- A post that is a member of an album stops rendering as a standalone
  block (suppression by membership).
- Deleting a member photo is normal delete-everywhere; the album record
  simply resolves to fewer decryptable members. Deleting the album
  record itself un-groups (members reappear standalone, unplaced).
- **Photo-only in v1** — video posts cannot be album members.

### 6. Server surface

- `hearth/messages.py`: `KIND_ALBUM` + `make_album` + validation;
  `pins`/`spans` validation on `KIND_PROFILE_LAYOUT` (bounds above);
  `GRID_LAYOUTS` retained for old-record validity only.
- `hearth/store.py`: `albums(identity_pub)` latest-wins resolver
  (per album_id, `(created_at, seq, device_pub)` tie-break like
  `profile`).
- `hearth/node.py`: `set_block_pin(id, x, y, w, h)` / `unpin(id)` /
  `set_block_span(id, w, h)` (all republish the layout record carrying
  every other map forward), `set_album(album_id, members)`;
  `profile_view` annotates wall blocks with pin/span, resolves albums
  (member posts folded into their album entry, suppressed standalone).
- `hearth/api.py`: `POST /api/block-pin`, `POST /api/block-span`,
  `POST /api/album` (400s mirror `/api/profile-layout`'s shapes).
- No wire-protocol bump, no crypto change, no store-schema migration,
  no new dependency.

## Testing

Per the standing split (Claude: automated + networking; August:
behavioral/UI):

- TDD per task throughout.
- **Two-node integration over real sockets:** pins survive sync (B
  renders A's collage geometry); an album grows across the wire (B sees
  the added photo after sync); an Inner-scoped member is absent from a
  Kreds-only viewer's deck AND its standalone form stays suppressed; a
  legacy no-pins wall renders the newest-first flow on both sides.
- **Web-asset/DOM tests:** canvas markup, pin CSS mapping, tray
  presence, composer preview wiring, dropdown absence, modal
  presets/nudges.
- **Playwright live smoke (UI_E2E-gated, extending the 0.3.10 harness):**
  drag a block to a cell, corner-resize, reload-persistence, a friend's
  synced view matches, album swipe, add-photos round-trip.
- **August's checklist at merge:** drag/resize feel, snap affordances,
  crop aesthetics, text scaling per span, mobile scaled-collage feel,
  album swipe on a real phone browser, dark mode.

## Slicing (one spec, three sequential plans)

- **Slice A — Pin engine:** layout-record `pins`/`spans`, canvas
  rendering (pinned + holes + tray + visitor flow + legacy defaults),
  drag-to-pin, corner resize, modal presets/nudges, retirement of
  `sizes`/`grids` rendering. The foundation and the bulk.
- **Slice B — Composer preview:** preview card, size chips, dropdown
  removal, span seed on post.
- **Slice C — Albums:** record + store + API, deck rendering + swipe +
  lightbox, add-later flow, membership suppression, un-group on delete.

Slices land on top of `kreds-quickwins-0.3.10` (parked, demo-verified);
the bundle ships as one release — version number decided at release
time (core + web move together; Slice A/C touch core).

## Out of scope (named, deliberate)

- Auto-push / ripple collision on drag (revisit only if no-push feels
  wrong in real use).
- Video album members; audio; user-chosen video length (unchanged
  roadmap items).
- Per-scope layout/album records (closes the existence-disclosure
  metadata class — stays on the open-design list with the `order`
  disclosure).
- Versioned edit-in-place of posts (unchanged: parked, no consensus).
- Journal feed and Journal rail rendering (untouched by this redesign).
- iPhone-native swipe polish (the web deck works in mobile browsers;
  native gestures come with the iOS app).

## Amendments (2026-07-14 Slice C final review)

- Section 5's "Add photos... (scope chosen per post...)" ships as
  **inherited scope, surfaced via tooltip/`aria-label` only** — the
  `+` control posts at the block's (or album's newest member's) scope
  automatically and states which scope in its title/aria text; it does
  not yet offer a per-post scope chooser. A true per-post picker is a
  named follow-up, not built in this slice.
