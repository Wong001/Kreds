# Kreds Me-View Reorg + Full Profile Page — Design

**Date:** 2026-07-06
**Status:** Approved (design discussion, this session)
**Basis:** shipped v3 reskin (spec `2026-07-06-kreds-rename-reskin-design.md`); user feedback (screenshot of the Me view; banner/avatar customization is vestigial after the reskin).
**Branch:** `kreds-me-profile` off `main`
**Product context:** "Slice A" of the post-reskin fixes. Internal package stays `hearth`. This restores profile customization the reskin left non-rendering and lays the profile-*page* foundation the later block-based-profile slice extends.

---

## Why

Two post-reskin gaps, reported by the user:
1. **Me view layout.** The Me tab stacks Me card / friends / Devices in one column. The user wants friends + Devices moved into a smaller right-hand column, and the friends section relabeled "Friends".
2. **Profile customization is vestigial.** The profile editor still has every control (name, bio, accent, avatar shape/size/placement, avatar + banner uploads) and they all save, but the reskin's compact profile *modal* renders an identity-color bar (ignoring the uploaded banner image) and a fixed circle avatar (ignoring shape/size/placement). So those settings display nowhere.

Decision (this session): restore a **full profile page** (retiring the compact modal) that honors the uploaded banner + avatar shape/size/placement, and serves as the foundation the block-based-profile slice will extend. The compact modal is removed.

## Scope decisions locked this session

- **Full profile PAGE**, not a modal enhancement. `openProfile` navigates to a `#view-profile` view; the `.pmodal`/`.modalback` markup + CSS are removed (no dead code).
- **"Friends" relabel is Me-view-only.** The circle, chips, and the "Kreds" post scope keep their names. Only the Me-view friend-list heading changes ("Your kreds" → "Friends").
- **Block content system is OUT of scope** (its own later slice) — this page is built so blocks slot into its body later.
- **Unfriend is OUT of scope** (Slice B, next).
- No protocol/crypto/store change; no new API. Reuses `/api/profile/{id}` (already returns name/bio/accent/avatar/avatar_shape/avatar_size/avatar_align/banner/identity_pub/mine/ring/since/posts), `/api/profile` POST, `/api/ring`, `/api/state`, `/api/blob`, `/api/post-blob`.

## Components

### 1. Me view reorg + "Friends" label
- Restructure `#view-me` into a layout with a main column (the Me card + an **Edit-profile** button) and a **smaller right-hand column** containing the **Friends** panel (heading "Friends", the friend rows, and the Add-friend ceremony toggle beneath) and the **Devices** panel.
- Single column on narrow/mobile widths (stack).
- Behavior unchanged: friend rows still open the person's profile; device revoke unchanged; ceremony unchanged.
- **Unify self-edit on the profile page:** the Me-view "Edit profile" button navigates to your own profile page (`openProfile(self)`), which hosts the editor (Section 2) — so there is ONE self-edit surface. The old inline `#me-editor-slot` is removed.
- Only the friend-list heading text changes to "Friends".

### 2. Full profile page (`#view-profile`)
- Add a `#view-profile` view container (a third/fourth peer of `#view-journal`/`#view-messages`/`#view-me`).
- `openProfile(identity)` fetches `/api/profile/{id}` and renders into `#view-profile`:
  - **Banner:** `p.banner` uploaded image (`/api/blob/{banner}`) if present; else the identity-color bar (others) / the user's accent (self).
  - **Avatar:** honor `p.avatar_shape` (circle|squircle|square|triangle), `p.avatar_size` (s|m|l), `p.avatar_align` (left|center|right) — reuse the pre-reskin avatar/`.pfp` shape+size classes and head alignment, re-tokenized to the Kreds palette. Uploaded avatar image if present, else the identity-color initial.
  - **Name**, identity hash (`identity <first8>…`), **bio**.
  - **Ring status** for others: "Inner kreds · since {Month YYYY}" / "Kreds · since {Month YYYY}" (format `since` via `toLocaleDateString(undefined,{month:'long',year:'numeric'})`; omit if `since` falsy). **Message** button (opens the DM). **Move-between-rings** button (label per current ring; POST `/api/ring` toggled, then refresh + re-render the page).
  - For **self**: the Edit-profile editor (reuse the existing `profileEditor`) instead of Message/Move.
  - **Posts:** `p.posts` (already scope-filtered server-side — only decryptable posts are returned), rendered as journal-style entries. Honest empty state when none.
  - A **back control** (← / "Back") that returns to the previously-active view (Journal/Messages/Me). Track the prior view when navigating in.
- **Foundation note:** the page body below the head/actions is the region the block-based-profile slice will populate with block content; keep it a clear container so that slice extends rather than restructures.
- **Fallback:** if `/api/profile/{id}` 404s (a known-but-unsynced friend), render a minimal page (name from `KREDS`/`STATE.friends`, identity-color banner/avatar, "Profile unavailable yet", Message button) — preserve the reskin's no-throw fallback behavior, adapted to the page.

### 3. Navigation
- `openProfile` sets the profile view active (via the existing `setView`/`goView` machinery, extended to include `profile`), recording the prior view so Back returns to it.
- Desktop nav highlights: viewing your own profile keeps "Me" context; viewing another's shows the profile page with no nav tab active (Back returns you).
- Remove the modal open/close code paths and Esc-closes-modal handling (Esc can trigger Back on the profile page instead — optional, keep simple: Back control only).

### 4. Remove the modal
- Delete `#profile-modal`/`.modalback`/`.pmodal` markup from `index.html`, the modal render/close code from `app.js`, and `.modalback`/`.pmodal`/`.pm*`/dead `.pmpost` CSS from `style.css`. No dead code left behind.

## Testing

Asset/DOM tests (`tests/test_web_assets.py`, extended):
- `#view-profile` container present; profile modal markup (`.modalback`/`.pmodal`) GONE.
- Me view: heading "Friends" present (and no "Your kreds" heading in the Me view); a right-column layout class present; Devices present.
- Profile page renders banner from an uploaded image when present (assert the app.js logic references `p.banner` for the banner src, not only a color) and applies avatar shape/size/placement classes (assert the shape/size/align class application is present in app.js).
- Honesty guards still hold: no receipts popover markup/strings; posts render via the decrypt-filtered path.

JS: `node --check hearth/web/app.js`.

Manual/Playwright smoke (recorded in plan): set an avatar shape + upload a banner as self; view a friend's profile page (with ring status), use Move-between-rings, and Back navigation; confirm the Me view shows Friends + Devices in the right column.

All existing tests stay green (update any test that asserted the modal markup).

## Out of scope (named)

- Block content system (grids / big pictures / text blocks / video / split columns) — the dedicated block-based-profile slice; this page is its foundation.
- Unfriend + messaging removal (Slice B).
- Any global "kreds"→"friends" rename beyond the Me-view friend-list heading.
- Protocol/crypto/store/API changes.

## Success criteria

- Me view shows the Me card in the main column and a smaller right column with "Friends" (relabeled) + Devices; stacks on mobile.
- Viewing a profile is a full page showing the uploaded banner (when set) and the avatar's saved shape/size/placement — the editor's controls visibly work again.
- The page carries ring status + Message + Move-between-rings (others) / the editor (self), scope-filtered posts, and Back navigation; the compact modal is gone with no dead code.
- Honesty guards hold; all tests green plus the new/updated asset tests.
