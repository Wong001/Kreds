# Profile polish batch (0.3.13) — topbar spacing, rail clipping, journal avatars — design

Date: 2026-07-15
Status: approved (August, 2026-07-15, item-by-item in session; avatar ring
kept over photos, letter+identity-color fallback unchanged)
Slice: first fixes after 0.3.12, from August's live pass

## Problems (all three reported with screenshots, 0.3.12 live)

1. **Topbar crowding:** the profile topbar's right group renders
   `Arrange · + · cog`; `.profile-arrange` carries `margin-right: 8px`
   but `.profile-addfriend` (+) has none, so + sits flush against the cog.
2. **Rail clips the avatar ring:** `.profile-side #profile-journal-rail`
   is a scroll container (`max-height: 60vh; overflow-y: auto`,
   style.css:364) with no inner padding, while `.eavatar::after` draws the
   identity ring at `inset: -4px` (style.css:320) — outside the avatar,
   inside nothing. The scroll box clips the ring at its edges.
3. **Journal entries never show the uploaded profile picture:** day-one
   gap, not a 0.3.12 regression. `buildEntry` (app.js:284-300, used by
   BOTH the journal page and the profile rail) always renders the letter
   circle; feed/journal rows carry no avatar data to render.

## Decision (August, 2026-07-15)

- Fix 1: give `.profile-addfriend` the same `margin-right: 8px` rhythm.
- Fix 2: inner padding on the rail's scroll box so the ring fits; nothing
  else moves.
- Fix 3: server enriches post rows with the author's avatar blob hash
  (stories-strip precedent, node.py `stories_view`); `buildEntry` renders
  the image filling the circle when present. **The identity ring stays on
  top of photos** (identity color remains visible), and the letter +
  accent/identity-color circle stays as the fallback for authors with no
  uploaded image — both explicitly per August.

## Design

### Server (fix 3)

- New `store.profile_avatars() -> Dict[str, str]` mirroring `profiles()`
  (store.py:472-482): latest-wins per author by the same
  `(created_at, seq, device_pub)` tie-break, value = the payload's
  `avatar` field; authors whose latest profile has no avatar are simply
  absent from the map.
- `node._decrypt_post_row(msg, names, now, avatars)` gains the fourth
  parameter and emits `"author_avatar": avatars.get(ipub)` in the row.
- Both callers (`feed()` node.py:1231, `posts_by()`) fetch
  `avatars = self.store.profile_avatars()` once beside `names` and pass
  it through. `profile_view`'s journal/wall ride `posts_by` and inherit
  the field (wall ignores it — harmless).

### Web (fixes 1-3)

- style.css: `.profile-addfriend { margin-right: 8px; }` (merged into its
  existing rule); `.profile-side #profile-journal-rail` gains
  `padding: 6px;` (covers the 4px ring overhang + breathing room).
- `buildEntry`: when `p.author_avatar` is truthy, append
  `<img src="/api/blob/" + p.author_avatar>` inside `.eavatar` instead of
  the letter text (same `/api/blob/` idiom as the profile header
  app.js:1773 and story rings app.js:2178-2180). The `--ring` /
  background color assignment stays unconditional — ring over photo,
  colored letter circle as fallback. `.eavatar img { width: 100%;
  height: 100%; border-radius: 50%; object-fit: cover; display: block; }`.

### Mixed-version

`author_avatar` is additive and the client guards on truthiness, so new
web on an old core just keeps letter circles — **no `min_core_for_web`
gate needed** (unlike 0.3.12). Old web on new core ignores the field.

## Out of scope

- Avatars in DM threads / conversations list (separate surface, own pass
  if August wants it).
- Any change to wall block rendering (rows carry the new field inertly).
