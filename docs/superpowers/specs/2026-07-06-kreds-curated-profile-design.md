# Kreds Curated Profile (Wall) — Design

**Date:** 2026-07-06
**Status:** Approved (design discussion, this session)
**Basis:** shipped scoped-posts model (`KIND_POST`, scope inner/kreds, per-recipient encrypted), Slice A profile page (`#view-profile`, `#profile-body` reserved for exactly this), the profile editor.
**Branch:** `kreds-curated-profile` off `main`
**Product context:** activates the parked "curated profile" slice, narrowed to the **wall MVP** the user asked for. Internal package stays `hearth`; product name Kreds.

---

## Why

User feedback on the shipped profile page:
1. Your own profile page dumps the **edit form inline** instead of showing your profile the way others see it; the uploaded pic + banner don't render there.
2. Editing should be **subtle** — a small cogwheel, not a prominent button.
3. You should be able to **post to your profile, separate from the journal feed**. A journal post must never auto-land on the profile; ephemeral/auto-deleting journal posts must not take a slot on the curated profile.

Decision: give a profile two distinct areas — a curated **Wall** (intentional profile posts) and a separate scrollable **Journal** section (that person's journal posts you can decrypt) — render the self profile as others see it, and move editing behind a cogwheel overlay.

## Scope decisions locked this session

- **Wall MVP**, not the block-layout builder. The Wall is a simple reverse-chronological feed of profile posts. Picture grids / big pictures / split columns / custom arrangement stay a later slice.
- **Self profile page renders the profile as others see it** (banner/avatar/bio/Wall/Journal). The editor is NOT inline; it opens from a cogwheel as an overlay.
- **Two placements, fully separate.** A post carries `placement`: `"journal"` (default) or `"profile"`. Journal posts appear in the home feed and in a profile's Journal section; profile posts appear only on the Wall. Neither bleeds into the other. A journal post never auto-appears on the Wall.
- **Profile posts reuse the scoped-posts machinery** unchanged (per-recipient encrypted; Inner/Kreds audience selector, default Kreds). The only new field is `placement`.
- Honesty unchanged: on another person's profile you see only the Wall/Journal posts you can decrypt; no receipts popover.

## Components

### 1. `placement` on `KIND_POST` (small protocol addition)
- `make_post` / `compose_post` gain `placement: str = "journal"` (values `"journal"` | `"profile"`), carried in the signed payload.
- `validate_payload` for `KIND_POST` accepts only `"journal"`/`"profile"` (reject others; treat a missing field as `"journal"` for backward-compatibility with any pre-existing posts).
- `_decrypt_post_row` includes `placement` in the row dict.
- No change to scope, encryption, wraps, routing, or the AAD (placement is not part of the audience; it is authored content like scope).

### 2. Node query methods
- `feed()` → journal posts only (`placement == "journal"`) across all authors. (Profile posts never surface in the home feed.)
- `posts_by(identity_pub, placement=None)` → gains an optional `placement` filter; `placement="profile"` returns the author's Wall posts, `placement="journal"` returns their Journal-section posts. Default `None` preserves current callers (returns both) — but the profile page calls it twice with explicit placements.
- `compose_post(text, scope, photos, expires_seconds, placement="journal")` — the new arg; profile posts pass `placement="profile"`.

### 3. Profile page (two sections)
Restructure `renderProfilePage` so **every** profile (self and others) renders:
- **Header** — banner (uploaded image `p.banner` else identity-color fill), avatar (uploaded `p.avatar` honoring shape/size/placement else initial), name, identity hash, bio. (Fix item 1: confirm the self path passes `p.avatar`/`p.banner`; the render code exists — ensure it runs for `p.mine` too and isn't shadowed by the editor.)
- **Wall** — the author's profile posts (`posts_by(author, "profile")`), reverse-chronological, rendered as entries (reuse `buildEntry`). Honest empty state. On your own profile, a **compose-to-profile** affordance sits above the Wall (a composer that posts with `placement="profile"` + the Inner/Kreds selector, default Kreds).
- **Journal** — a separate, clearly-labeled scrollable section of the author's journal posts (`posts_by(author, "journal")`), reverse-chronological (this is the old "recent posts" area, now explicitly the Journal section). Ephemeral/expiring posts live here and scroll away; they never appear on the Wall.
- The two sections live in `#profile-body`; they are visually distinct areas (e.g. a Wall region then a "Journal" region), not interleaved.

### 4. Cogwheel editing (self)
- Remove the inline editor from the self profile page and the prominent "Edit profile" button on the Me card.
- Add a small **cogwheel** icon button on the self profile page (top corner) — and reachable from the Me card — that opens the existing `profileEditor` as an **overlay/modal** over the profile. Save re-renders the profile page (the existing self-save-re-renders behavior), then closes the overlay.
- The cogwheel appears only on your own profile (`p.mine`), never on others'.

### 5. API
- `/api/post` (compose) accepts an optional `placement` form field (default `"journal"`); profile-post composer sends `placement=profile`.
- `/api/profile/{id}` already returns the author's posts; split its post payload into `wall` (profile placement) and `journal` (journal placement) — or the client calls the feed/posts endpoints with a placement filter. Prefer: `/api/profile/{id}` returns `wall: [...]` and `journal: [...]` (both decrypt-filtered server-side, only what the viewer can see), so the client renders both sections from one fetch.
- Home `/api/feed` returns journal-placement posts only (exclude profile posts).

## Testing

Backend/API:
- `compose_post(placement="profile")` produces a post that `feed()` does NOT return and that `posts_by(author, "profile")` DOES return; a journal post is the inverse. A profile post is still per-recipient encrypted and honors inner/kreds scope (an inner profile post is invisible to a kreds-only friend).
- `validate_payload` accepts journal/profile placement, rejects others, treats missing as journal.
- `/api/profile/{id}` returns `wall` + `journal` split, each decrypt-filtered (a non-recipient sees neither the wall nor journal post they can't decrypt).
- `/api/feed` excludes profile posts.

Web asset/DOM:
- Self profile page renders the header + Wall + Journal sections (not the inline editor); a cogwheel button present (self only); no prominent "Edit profile" button; the editor opens as an overlay.
- A profile-post composer present on the self profile with the Inner/Kreds selector.
- Honesty guard: no receipts popover.
- `node --check hearth/web/app.js`.

Manual/integration smoke: post to profile → appears on the Wall, not the home feed; a journal post → appears in the feed and the profile's Journal section, not the Wall; an expiring journal post scrolls in the Journal section and never on the Wall; self profile shows uploaded banner+avatar; cogwheel opens/saves/closes.

All existing tests stay green (update any that assumed `feed()`/`posts_by` return both placements, or that the profile page renders the editor inline). Full suite under an explicit timeout.

## Out of scope (named)

- Block layout builder (grids / big pictures / split columns / custom arrangement) — later slice; the Wall is a plain feed for now.
- A public/pseudonymous audience for profile posts (still Inner/Kreds only).
- Reordering/pinning/editing individual Wall posts (delete already exists via the shared deletion path).
- Any protocol change beyond the `placement` field.

## Success criteria

- Your own profile page shows your real profile (banner/avatar/bio/Wall/Journal) exactly as others see it; uploaded pic+banner render; editing is a subtle cogwheel overlay.
- You can post to your profile Wall separately from the journal; journal posts never auto-appear on the Wall; profile posts never appear in the home feed.
- Visiting a profile shows the curated Wall plus a separate scrollable Journal of that person's feed posts you can decrypt.
- Profile posts remain per-recipient encrypted with the Inner/Kreds selector; honesty guards hold; all tests green plus the new ones.
