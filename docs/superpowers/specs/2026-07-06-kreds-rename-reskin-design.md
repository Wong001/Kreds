# Kreds Rename + v3 Visual Reskin — Design

**Date:** 2026-07-06
**Status:** Approved (design discussion, this session)
**Basis:** Kreds redesign brief + `kreds_design_v3.html` (authoritative mockup, this session); shipped scoped-posts model (spec `2026-07-06-kreds-scoped-posts-design.md`).
**Branch:** `kreds-reskin` off `main`
**Product context:** Second Kreds-redesign slice. Internal package stays `hearth`; product name is Kreds. The client this produces is exactly what the later Windows-packaging slice will wrap and what iOS ships as a PWA.

---

## Goal

Rewrite the node-served web client to the v3 design — journal-first, circle-as-navigation, people-as-destinations — rename Loop→Kreds throughout the UI, derive per-person identity colors deterministically, and make the client an installable PWA. Reuse the existing API plus two small read-only additions. No protocol or crypto change.

## Approach

Full rewrite of the three web files (`hearth/web/index.html`, `style.css`, `app.js`) to the v3 structure; the journal-first IA differs enough from the current tabbed layout that rebuilding is cleaner than patching. The API is reused as-is plus two read-only additions (Section 5). `hearth/api.py`'s static mount and `index.html` serving are unchanged in shape.

## Decisions locked this session

1. **Full v3 including the radial circle map** (rail widget + expandable overlay) and the mobile Circle-as-home tab bar — not a subset.
2. **Unread markers are a per-device localStorage stopgap** now (a "last opened" watermark, like the shipped stories seen-state): honest and real on this device, labeled as not-yet-synced. The synced-across-devices version is the deferred read-state slice (#5). No fake data.
3. **Receipts "who holds this post" popover is DEFERRED** — real receipts need delivery-ack propagation (deferred slice). The reskin shows the real scope pill (Inner/Kreds) but never a "N copies / who holds it" popover, because that would draw data we do not have.
4. **iOS path (recorded, not built here):** React Native + Expo/EAS, built from Windows on Expo's hosted macOS (no local Mac/Xcode) — needs an Apple Developer account and TestFlight/App Store for device distribution; the reachability/background problem is unchanged by tooling. The PWA (this slice) is the first iOS presence.

## Components

### 1. Rename + brand
- `<title>` → `Kreds`; wordmark → the 3-arc-and-dot SVG mark + "kreds" in Bricolage Grotesque; revoked-banner text → "...logged out of Kreds."; no user-facing "Loop" remains.
- Favicon + PWA icon set derived from the mark (PNG sizes for manifest + apple-touch-icon).
- Vocabulary: friend list = "your kreds"; scopes Inner / Kreds.
- **Fonts self-hosted**: Bricolage Grotesque (display), Instrument Sans (UI), IBM Plex Mono (hashes/timestamps/scope labels) fetched as woff2 into `hearth/web/fonts/`, referenced via `@font-face` — the mockup's Google-Fonts CDN links violate the node-served/offline/no-external-host property and are not used.
- Design tokens (day + night) copied from `kreds_design_v3.html` `:root` / `[data-theme="night"]`; theme toggle persists (localStorage), defaulting to the existing behavior.

### 2. Layout / IA
- **Desktop, journal-first:** day-grouped chronological feed (newest first) with the "That's everything — your kreds is quiet" end state; a chip-bar filter above it (Everyone / Inner kreds / per-person chips with localStorage unread dots); composer with the Inner/Kreds keeps selector + expiry; the compact **circle rail** that expands to the full **radial overlay**.
- **Mobile:** tab bar Circle / Journal / Me — Circle is home.
- **Messages** remains its own view, restyled to Kreds tokens (the v3 mockup does not redesign the DM screen; the shipped chat UI is re-tokenized, not rebuilt).
- **Me** view keeps profile edit + friends + devices, re-tokenized.

### 3. People are destinations (profile modal)
- Clicking a person anywhere (chip, avatar, name, map node) opens a profile modal: identity-color banner, name, bio, ring status ("Inner kreds · since <date>"), Message button, Move-between-rings button (calls `/api/ring`), and recent posts.
- Recent posts obey the shipped invariant: only posts this device can decrypt render (a viewer sees what that person shared with them; honest empty state otherwise).

### 4. Identity colors (deterministic)
- `identityColor(fingerprint)`: hue = (first bytes of the identity fingerprint) mod 360; fixed saturation/lightness chosen to read on both day and night tokens. Same function used for avatars, rings, and map nodes so every device renders a person identically.
- Replaces per-profile accent for *others*; the user's own chosen accent still applies to their own surfaces.

### 5. API additions (read-only, small)
- `GET /api/kreds` → `[{identity_pub, name, ring, since}]` for every friend (ring from `store.rings(self)`, default "kreds"; `since` from the ring record's `created_at` if ringed else the identity's `added_at`). Color is NOT returned — the client derives it via `identityColor(identity_pub)` so the derivation lives in exactly one place. Drives the chip bar and the radial map.
- `profile_view` / `GET /api/profile/{id}` gains `ring` (this viewer's ring assignment for that identity, default "kreds") and `since`. Drives the profile modal's ring status.
- No other backend change. Day grouping, per-person feed filtering, and the end state are client-side over the existing `feed()`.

### 6. PWA-readiness
- `manifest.json`: name "Kreds", short_name, standalone display, background/theme colors from tokens, icon set (multiple sizes) + maskable icon.
- `<link rel="manifest">` + `apple-touch-icon` + theme-color meta in `index.html`.
- A service worker (`/static/sw.js`, registered from `app.js`) caches **only the app shell** — `index.html`, `style.css`, `app.js`, fonts, icons, manifest — via a versioned cache, cache-first for the shell, network-only for everything under `/api/` and `/ws`. Never caches API data (live from the node). Result: installable "Add to Home Screen" on iOS/Android; the shell loads offline but data requires the node.
- Service worker is served from a path that scopes it to the app root (served at `/sw.js` or registered with an explicit scope) — implementer confirms the scope covers `/`.

### 7. Circle map
- Compact rail widget: the small radial SVG (you at center, friends placed on inner/kreds rings, localStorage freshness dots), an "Expand" control.
- Full overlay: the larger radial map with ring labels (inner kreds / kreds), nodes clickable to open the profile modal, Esc/close to dismiss.
- Node ring placement uses `/api/kreds` ring field; node color uses `identityColor`. Keyboard-reachable nodes (the mockup already uses `tabindex`/`role=button`).

## Testing

Asset/DOM tests (`tests/test_web_assets.py`, extended):
- `<title>Kreds`; the 3-arc mark markup present; no user-facing "Loop"; no `fonts.googleapis.com`/`gstatic` links (fonts self-hosted); `@font-face` present.
- `manifest.json` linked + present with icons; `sw.js` present and registered in `app.js`; apple-touch-icon + theme-color present.
- Composer keeps selector (Inner/Kreds) present; day-group + end-state markup/strings present; chip-bar present; circle rail + overlay markup present.
- **No receipts "who holds it" popover markup** (honesty guard); unread markers are localStorage-driven (assert the localStorage key usage in app.js, no server "unread" field consumed).

API tests:
- `GET /api/kreds` returns friends with `ring`/`since`/`identity_pub` (no `color` field — client-derived); a ringed friend shows "inner"; default "kreds".
- `/api/profile` includes `ring` + `since`.

JS sanity: `node --check hearth/web/app.js` and `node --check hearth/web/sw.js`.

Manual (recorded in plan): load the app, confirm journal + chips + circle rail/overlay + profile modal render and the theme toggle works; install as PWA and confirm the shell loads.

## Out of scope (named)

- Receipts / delivery-acks (deferred slice).
- Synced read-state (deferred slice #5; localStorage stopgap ships here).
- Curated-profile wall / block-based profile (deferred slice #3).
- Windows packaging (next slice — this reskin produces the client it wraps).
- iOS reachability/cert story + the Expo/EAS native app (recorded path, later).
- Any protocol/crypto/store change beyond the two read-only API additions.

## Success criteria

- The client renders the v3 journal-first layout with a working chip filter, composer keeps, day grouping + end state, circle rail + expandable radial overlay, and profile modals opened from anywhere a person appears.
- Loop→Kreds is complete in the UI (title, mark, copy, icons); fonts self-hosted (no external host); identity colors deterministic across devices.
- The app is installable as a PWA (manifest + shell-caching service worker); the shell loads offline, data requires the node.
- Honesty boundaries hold: no receipts popover; unread markers localStorage-only and labeled.
- `/api/kreds` and profile ring/since back the map/chips/modal; all existing tests stay green plus the new asset/API tests.
