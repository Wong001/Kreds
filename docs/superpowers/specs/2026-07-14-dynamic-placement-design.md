# Dynamic placement (tray removal + push-on-collision) — design

Date: 2026-07-14
Status: approved direction (August, in-session, after real use of the
collage). Amends the collage redesign's placement model (spec 2026-07-13
sections 2-3); same bundle branch.

## Problem (August's own findings)

1. The Unplaced tray confused real use: half his posts were unplaced,
   "Done" appeared to shuffle his arrangement (the unplaced half rendered
   elsewhere). The split was invisible at posting time.
2. Rearranging takes too many steps: drops were only allowed on FREE
   cells, so making room at the top meant manually moving everything.

Decision: v1's "no auto-push" was explicitly provisional ("revisit only
if real use demands it") — real use demanded it.

## Design

**One rule everywhere: place at the target, push only the blocks in the
way, straight down (cascading, never sideways).**

- **Push algorithm (server-authoritative, deterministic):** the placed
  block is the anchor at its exact target; every other pinned block is
  processed in `(y, x, id)` order and settles at its own `(x, w, h)`,
  moved down past anything already settled that it overlaps. Blocks not
  in the way never move; chain collisions cascade downward; identical
  inputs give identical outputs on every device (the record carries the
  RESULT — peers never re-run the algorithm). A push that would exceed
  the row cap (MAX_LAYOUT) is a 400.
- **Creation auto-places at the top (dense):** a new wall post is pinned
  at `(0, 0)` with its composer-chosen size (the size chips now travel
  as optional `w`/`h` fields ON `/api/post` — the separate span-seed
  call dies), pushed-place applied. A 1x1 lands beside whatever fits;
  only overlapped blocks slide down. Newest lives at the top, the user
  rearranges after if they wish.
- **`set_block_pin` becomes push-aware** — drag-drop, modal size
  presets, nudges, and corner-resize all inherit it. The client's
  overlap veto disappears: the ghost's invalid state remains ONLY for
  out-of-bounds; nudges are disabled only at canvas edges; the preset
  "no room" alert dies.
- **The tray dies entirely** (August confirmed): the `#profile-tray`
  UI, "Send to tray", and drag-off-canvas-to-unpin are all removed.
  `/api/block-unpin` stays as a wire-compat endpoint with no UI caller.
- **Ungroup top-inserts** the restored members (oldest processed first
  so the newest ends on top), same push rule — no limbo.
- **Migration:** opening YOUR OWN profile with unpinned blocks triggers
  one `POST /api/wall-autoplace` — a single layout write pinning every
  unplaced own block at the top (newest on top), push rule applied.
  Visitors of a not-yet-migrated wall keep the flow-below fallback
  (rendering unchanged); the spans map stays wire-valid for legacy.

No record-shape change, no protocol bump, no new dependency. Honest
boundaries unchanged: pins remain plaintext geometry over opaque ids.

## Testing

Node: push determinism (dense placement beside, cascade chains, non-
colliding blocks never move, row-cap 400); create-auto-places (with and
without w/h fields); ungroup top-insert; autoplace bulk single-publish;
carry-forward intact. Integration: pushed layout syncs identically.
Client asset tests: tray markup gone, ghost overlap-veto gone, composer
sends w/h. Smokes updated: post → block pinned at top immediately;
drop-on-occupied pushes (both smoke files lose their tray choreography).
August: the push FEEL (does down-only cascading match intuition), and
whether newest-at-top creation behaves as expected in real posting flow.

## Out of scope

Sideways/gravity packing (blocks never move horizontally on a push);
undo for pushes (re-drag restores); a "park it" holding area (killed
with the tray — revisit only if missed in practice).
