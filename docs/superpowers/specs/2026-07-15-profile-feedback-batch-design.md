# Profile feedback batch (0.3.12) — design

Date: 2026-07-15
Status: approved (August, from annotated screenshot + in-session Q&A).
Six items from real use of the profile. Amends the dynamic-placement
spec (2026-07-14) on creation placement only; everything else is
client-side UI plus one additive profile-record field.

## 1. Pin revert: new posts fill the first open gap

August's own call: the 2026-07-14 "creation auto-places at the top and
pushes" was a mistake — a new post should take an open slot, not shove
the arrangement around.

- **Creation placement (server, `create_post` auto-place):** replace
  pin-at-(0,0)-with-push by **first-fit**: scan cells in `(y, x)` order,
  top-to-bottom then left-to-right; pin the new post at the first
  position where its `w x h` footprint (composer-chosen size, or the
  media/text default) overlaps no existing pin and fits inside the
  4-column canvas. **No other block ever moves on post.** A gap too
  small for the footprint is skipped; if no gap fits, the scan naturally
  lands below everything already placed.
- **Failure surface:** first-fit always finds a spot, so the only
  creation-time layout error left is the existing `MAX_LAYOUT` count
  cap (still a 400 with the post orphaned-unplaced — the honest
  degradation notes in node.py stay valid; do NOT hide the post).
- **Ungroup (album):** switches from top-insert-with-push to the same
  first-fit rule. Members are restored **newest-first** so the newest
  member claims the highest open slot. No push.
- **Unchanged:** drag-drop, modal size presets, nudges, and corner
  resize keep push-on-collision (`set_block_pin` stays push-aware) —
  that half of dynamic placement is liked and stays. The one-time
  `/api/wall-autoplace` migration is untouched. `auto_place=False`
  (deck-grow flow) untouched.

## 2. Banner crop position

The banner cover-crops from the center; August's dog loses its head.

- **Record:** new optional field `banner_pos` on KIND_PROFILE — integer
  0–100, the vertical `background-position-y` percent (0 = show top of
  image, 100 = bottom), **default 50** (today's center). Validated only
  when present (int, 0–100, bool excluded — the span-validation idiom).
  Additive and mixed-version safe: old validators check only known
  fields, old clients render centered. No protocol bump.
- **Plumbing:** `make_profile` carries it; `set_profile` preserves it
  like `banner` itself (an editor save without touching the crop keeps
  the stored value); store profile fold + `profile_view`/API return it;
  fallback profile default 50.
- **Client render:** `#profile-banner` gets
  `background-position: center <pos>%`.
- **Editor control:** in the profile editor's Banner group, a live
  preview (same aspect as the real banner strip) that August **drags
  up/down** to set the crop, plus a visually-paired range slider as the
  keyboard/a11y path — both drive the same value. Shown whenever a
  banner exists (stored or freshly picked; a fresh pick previews via
  object URL). Saved with the rest of the profile record on Save (not
  immediate-apply — the editor is a form, unlike the wall's
  immediate-POST controls).

## 3. "Delete everywhere" moves into block settings

- The always-visible `.pact del` button on own wall blocks is removed
  from `renderBlock`.
- The per-block settings modal (`openBlockSettings`) gains a **Delete**
  group at the bottom: the same Delete-everywhere action through the
  shared `deleteEverywhere` helper (same confirm), then the existing
  refresh + profile re-open flow. After a successful delete the modal
  closes (the block is gone; `reopenAfterAction` already handles the
  deleted-meanwhile case).
- **Gear outside Arrange:** the block gear (`.block-settings-btn`)
  becomes available on own blocks at ALL times, not just Arrange —
  same top-right spot; hover-reveal with a mouse, always visible on
  touch/coarse pointers. Without this, deleting would require entering
  Arrange first. The modal's existing controls (size, nudge, text
  style, ungroup) work outside Arrange too — they were only
  arrange-gated by reachability, not by design.
- **Unchanged:** journal entries keep their inline Delete everywhere
  (`buildEntry`); albums keep the ungroup-first rule (no direct album
  delete).

## 4. Scope tag: smaller, ringless, bottom-right

`.block-scope` (the self-only Inner/Kreds badge):

- border ring removed; smaller (~9px) dimmed mono text, no pill.
- moves from top-left to **bottom-right** of the block (the delete
  button's departure frees the bottom edge).
- **Arrange collision:** `.block-resize`'s corner handle owns
  bottom-right on pinned blocks during Arrange, so the tag hides while
  `.block.arranging` — it's informational and Arrange is when it's
  least needed.

## 5. Self-only side panels move to a Settings page

The right-column stack (Friends, Devices, App-lock, Desktop, Updates)
overflows and misaligns the profile. It moves to its own page.

- **New view `#view-settings`** ("Settings"), self-only, sibling of the
  existing views in `showView`. Entry: the profile topbar **cog now
  opens Settings** (it stops opening the edit overlay). Back returns to
  the profile.
- **Sections, in order:** Edit profile, Friends (list + full add-friend
  ceremony), Devices, App-lock, Desktop (desktop builds only — same
  `body.desktop` gating as today), Updates. Each section is
  independently collapsible; open/closed state remembered client-side
  (localStorage; a UI preference, not synced state).
- **Re-homing, not rewriting:** the existing container IDs (`#friends`,
  `#ceremony`, `#devices`, `#applock-settings`, `#desktop-settings`,
  `#update-settings`) move into the settings view so
  `renderFriends`/`ceremonyUI`/`renderApplockSettings`/etc. keep
  working untouched. The profile editor renders into the Edit-profile
  section; the `#profile-edit-overlay` dies (the Me-card entry point
  routes to Settings too).
- **Profile right column** keeps ONLY the Journal rail — identical
  structure on own and friends' profiles, which fixes the alignment
  complaint.
- **Copy tweak:** the idstrip's "manage in Me" hint points at Settings.

## 6. Friends quick-add "+" in the profile topbar

- Small round self-only "+" button in the profile topbar, next to
  Arrange/Done and the cog.
- Opens the add-friend ceremony (Share my code / Enter a code tabs) as
  a centered overlay — same dialog pattern as `#circle-overlay`
  (role=dialog, close button, Esc) — so adding a friend never leaves
  the profile.
- The ceremony UI is built by one function; it renders into either
  home (Settings > Friends section, or this popover) without
  duplication.

## Testing

- **Node:** first-fit creation placement — fills the first fitting gap,
  skips too-small gaps, appends below when nothing fits, no existing
  pin moves on create; ungroup first-fit newest-first; count-cap 400
  still orphan-honest. `banner_pos` — validation bounds (reject
  non-int/bool/out-of-range), default-absent OK, set_profile carry,
  fold + profile_view/API round-trip.
- **Client asset tests:** wall block markup has no inline
  Delete-everywhere; gear present outside Arrange; scope tag's new
  class/position and arrange-hide; settings view markup with the six
  collapsible sections and re-homed IDs; edit overlay gone; banner
  position style wired; topbar "+" present.
- **August (behavioral):** post lands in a gap without shoving the
  wall; banner drag crops right and survives an unrelated profile
  edit; delete via gear (in and out of Arrange); tag look at each
  block size; Settings page collapse + state memory; "+" popover
  ceremony end-to-end with a real friend.

## Out of scope

Sideways/gravity packing (unchanged from dynamic placement); horizontal
banner crop (vertical only — the strip is full-width); syncing
settings-section collapse state across devices; a picker for which
scope Add-photos posts at (still the named follow-up from 2026-07-14).
