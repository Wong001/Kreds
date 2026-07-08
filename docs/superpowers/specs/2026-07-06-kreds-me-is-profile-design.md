# Kreds "Me tab = your profile" — Design

**Date:** 2026-07-06
**Status:** Approved (design discussion, this session)
**Basis:** shipped curated-profile slice (`#view-profile` page with header/Wall/Journal/cogwheel; the retired `#view-me` card summary).
**Branch:** `kreds-me-is-profile` off `main`
**Product context:** small IA refinement on top of feature 12. Internal package stays `hearth`.

---

## Why

The Me tab currently shows a small card summary (name + cogwheel + Friends + Devices); you have to click in to see your actual profile. The user wants the **Me tab to open straight to your profile** (the full `#view-profile` page — banner/avatar/bio/composer/Wall/Journal), immediately.

## Decisions locked this session

- **Me tab → your profile page directly** (`openProfile(self)`), on both the desktop nav and the mobile tab bar. The `#view-me` card summary is retired.
- **Friends + Devices become a compact strip visible ONLY to the profile owner.** On your own profile you see a right-hand strip with Friends (+ Add-friend ceremony) and Devices (+ revoke). On anyone else's profile there is no strip — just their profile.
- **Back button:** hidden on your own profile (Me is a top-level tab); shown when you've drilled into someone else's profile.
- **Cogwheel:** unchanged — opens the edit-profile overlay.
- No backend/API/protocol change. Pure client IA.

## Components

### 1. Me tab routes to the profile page
- The desktop nav "Me" button and the mobile tab-bar "Me" tab both call `openProfile(STATE.identity_pub)` instead of showing `#view-me`.
- `openProfile(self)` already renders `#view-profile` with `p.mine` true and highlights the Me nav (from the earlier slice) — reuse that.
- `restoreView()` for a remembered `"me"` view opens the self profile.
- Remove the `#view-me` container and its render path (`renderMe`) once its contents are re-homed (below). `setView`'s view list drops `"me"`.

### 2. Self-only Friends + Devices strip on the profile page
- Add a `#profile-side` region to `#view-profile`. When `p.mine`, `renderProfilePage` lays the page out as two columns — the profile card (existing) in the main column, `#profile-side` (Friends + Devices) in a narrower right column. When `!p.mine`, the strip is hidden and the layout is single-column (unchanged).
- Move the Friends panel (heading "Friends", the friend rows opening profiles, the `#ceremony` Add-friend toggle) and the Devices panel (rows + revoke) out of the old `#view-me` markup into `#profile-side`. Reuse the existing `renderMe` logic (friend rows, device revoke, ceremony wiring) to populate them — call it only when `p.mine`.
- Behavior of those panels is unchanged (friend rows → `openProfile`; revoke → `/api/device/revoke`; ceremony unchanged).

### 3. Back button visibility
- Hide `#profile-back` when `p.mine` (top-level Me); show it otherwise (drill-in). The Me nav highlight already distinguishes self.

## Testing

Web asset/DOM tests (`tests/test_web_assets.py`, extended):
- The Me nav button / mobile Me tab wiring calls `openProfile` (not a `#view-me` card).
- `#view-profile` contains a `#profile-side` region; Friends ("Friends" heading) + Devices markup present there; `#view-me` container is gone.
- Back button is conditionally hidden for self (assert the app.js logic gates `#profile-back` on `p.mine`).
- Honesty guard unchanged: no receipts popover.

`node --check hearth/web/app.js`.

Manual/Playwright smoke (record): clicking Me opens your profile immediately (banner/avatar/Wall/Journal), with the Friends + Devices strip present; a friend's profile shows no strip and a Back button; Add-friend + device-revoke still work from the strip; the cogwheel still opens the editor.

All existing tests stay green (update any that assumed a `#view-me` card or a "Me" entry in `setView`'s view list).

## Out of scope (named)

- Any change to the profile content itself (Wall/Journal/composer/cogwheel) — unchanged.
- The block-layout builder (still a later slice).
- Backend/API/protocol changes.

## Success criteria

- The Me tab (desktop + mobile) opens your profile page directly — no intermediate card.
- Your own profile shows a compact Friends + Devices strip; others' profiles show only the profile (no strip) and a Back button.
- Add-friend and device-revoke work from the strip; the cogwheel still edits; honesty guards hold; all tests green plus the new/updated asset tests.
