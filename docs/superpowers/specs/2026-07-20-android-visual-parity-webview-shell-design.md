# Android visual parity — shared-UI WebView shell (Option B) — design, 2026-07-20

The first slice after the richer-feed arc (photos -> video -> stories ->
responses) completed and merged. Goal: the phone should **look and feel like
the desktop app**, so a user who starts on desktop and later links a phone (or
vice versa) has one continuous experience — "same, but slightly different where
the platform demands it."

## Decision (August, 2026-07-20): reuse the desktop UI in a WebView

Rather than rebuild each desktop view as native React Native components
(Option A), the phone runs hearth's **existing** web UI (`hearth/web/index.html`
+ `app.js` + `style.css`), bundled locally, inside a full-screen WebView,
backed by a thin **read-only local API over the phone's native store**
(decrypt-on-read).

Why B over a native rebuild:
- **Parity by construction.** It is literally the same UI code as the desktop —
  it cannot drift. A native rebuild is a second implementation permanently
  chasing the first; drift is exactly the "feels like two platforms" outcome we
  want to avoid.
- **Three-platform unity, not two.** The web UI runs in a WebView on Android +
  iOS and *is* the app on desktop — one UI across all three. A native RN rebuild
  would unify Android + iOS but leave desktop as a separate UI that can still
  drift from mobile.
- **The desktop is already responsive.** Its own `@media (max-width: 760px)`
  mode already collapses to a single column with a sticky **bottom tab bar** and
  keeps the date "space line" separators — most of "the mobile version" already
  exists in the shared CSS.
- **"Slightly different per platform" is what media queries are for.** The two
  differences August named are free: window chrome (close/min/max) is the
  desktop OS frame, not part of the UI, so a WebView simply has none; bottom nav
  already exists in the mobile CSS.
- **iOS reuse.** The UI never needs an iOS rewrite (it is a WebView of the same
  web code). The only iOS-specific work is the native *engine* port (Swift Tor
  manager, background model, media backends) — which is required regardless of
  UI approach and stays Mac-gated.

The native engine already built (Tor manager, background sync, SQLite store,
Kotlin crypto/`DecryptPass`, `MediaServer`, isolated AVIF decode) is unchanged —
it becomes the **service layer** the local server reads from.

## Architecture

```
 WebView (full-screen RN screen)
   |  loads  http://127.0.0.1:<port>/?<token>
   v
 Native local loopback server  (evolve the MediaServer pattern:
   127.0.0.1-only bind, per-session token, CSP-locked, fail-closed)
   |
   +-- serves bundled web assets:  index.html, app.js, style.css, /sw.js (stub)
   +-- serves read-only /api/*  --> marshals native store into hearth's exact
                                     JSON shapes (decrypt-on-read)
   |
   v
 Native engine (unchanged): SQLite store, DecryptPass/getFeed/getStories/
   getBlobImage/getVideoUrl, isolated AVIF ImageDecodeService, MediaServer
   (video), Tor + SyncRunner.
```

One native server serves both the app shell and the API from the same
loopback origin, so `app.js`'s relative `/api/...` fetches resolve without CORS
or cross-origin config. This is uniform across Android and iOS (both can host a
loopback server), unlike per-platform request-interception hooks.

**Security posture (unchanged in spirit from MediaServer):** bind 127.0.0.1
only; a per-session token gates every request before any store access; a strict
CSP on the WebView locks it to the local origin (no remote fetch/script);
decrypt-on-read preserved (no decrypted bytes or keys to disk — the server
decrypts on demand and streams); friend-authored images still pass through the
isolated AVIF decoder before bytes leave native. The web assets are first-party
and bundled (never fetched remotely), so there is no remote-code surface.

## The read-only API shim

Implement the GET endpoints the views need, marshaling the native store into
the **exact JSON shapes** `hearth/api.py` returns (copied field-for-field so
`app.js` consumes them unchanged). The shapes — not new UI — are the bulk of
the work.

Endpoints in scope (read-only), mapped to native:

| Endpoint | Drives | Native source |
|----------|--------|---------------|
| `GET /api/bootstrap` | app-shell "already set up" gate | static "set up" stub |
| `GET /api/applock` | lock state | native applock state (or unlocked stub) |
| `GET /api/state` | journal view top state (self, scope chips, friends) | store: own profile, friends, rings |
| `GET /api/feed` | journal feed | `DecryptPass`/`getFeed` (+ responses from B.2d-4) |
| `GET /api/stories` | stories strip | `getStories` (B.2d-3) |
| `GET /api/conversations` | Messages list | store: DM threads (B.2c) |
| `GET /api/dm/{identity_pub}` | a chat thread | store DM thread, decrypt-on-read |
| `GET /api/profile/{identity_pub}` | Me/profile + wall layout | store: profile, wall layout, posts |
| `GET /api/kreds` | the circle graph rail | store: friends + rings |
| `GET /api/blob/{h}`, `GET /api/post-blob/{msg}/{h}`, `GET /api/dm-blob/{msg}/{h}` | images | decrypt-on-read + isolated AVIF decode; video via MediaServer |

All **writes** (`/api/post`, `/api/react`, `/api/comment`, `/api/dm`,
`/api/story`, `/api/profile`, `/api/block-*`, `/api/wall-autoplace`,
`/api/friend/*`, `/api/ring`, `/api/delete`, `/api/lock`/`/api/unlock` where
applicable, ...) return a uniform read-only response; the UI hides the
affordances that trigger them (below). Inapplicable app-shell flows
(`/api/bootstrap/*` pairing, onboarding, update) get read-only stubs so the UI
lands straight in the app.

## Read-only + mobile adaptations (small, shared-UI change)

- **Read-only mode.** Add a `body.readonly` signal (set from a field in
  `/api/state`, or a boot flag) that the **shared** web UI respects to hide the
  compose bars, the reaction-add picker, the comment inputs, Arrange, and
  settings-edit. This is a small, clean, reusable addition to `hearth/web`
  (CSS + a few guards) and doubles as the **seam for the future outbound
  slice**: flip `readonly` off and wire the writes. It changes nothing about how
  the desktop renders (desktop is never read-only).
- **Mobile nav.** The desktop's existing mobile `@media` already renders a
  sticky bottom tab bar. Add the **Messages** tab to that mobile bar (desktop's
  mobile tabbar is currently Circle/Journal/Me). Keep the 4-column profile wall
  at phone width (August: keep the cell density, do not collapse to one column).
- **No window chrome** — a WebView has none; nothing to do.
- **Date "space line" separators** (`TODAY · MANDAG 20. JULI` + rule) — already
  in the desktop feed; render unchanged.

## Media

- **Images:** `/api/blob|post-blob|dm-blob` served by the local server, which
  decrypts on read and routes AVIF through the existing isolated
  `ImageDecodeService` before returning bytes (friend-authored bytes never
  decoded in-process). Same fail-closed-to-placeholder posture.
- **Video:** the web UI's `<video>` points at the existing token-guarded
  `MediaServer` loopback URL (or the local server proxies to it) — decrypted
  mp4 streamed in-memory, never to disk, exactly as B.2d-2.

## What happens to the B.2d native rendering

The native feed/photo/video/stories **screens** (App.tsx) are superseded at the
presentation layer by the web UI. Their **backends** — `MediaServer`, the
isolated decoder, `DecryptPass`/`getBlobImage`/`getVideoUrl`, sync, store —
remain and are re-fronted as the services the local server calls. The RN app's
role shrinks to: host the WebView, own the native engine lifecycle, and (later)
a small native debug/settings surface.

## Decomposition (this is a multi-plan slice)

1. **Local server + WebView host + journal feed, read-only, end-to-end.** The
   loopback server serving bundled assets + `/api/bootstrap`, `/api/applock`,
   `/api/state`, `/api/feed`, `/api/blob*`; the WebView screen; `body.readonly`.
   Proves the whole approach on the primary view (feed with posts, photos,
   video, stories strip, responses — everything the richer-feed arc built,
   now rendered by the desktop UI).
2. **Messages** — `/api/conversations`, `/api/dm/{id}`, DM blobs.
3. **Me/profile** — `/api/profile/{id}` incl. the 4-column wall layout,
   `/api/kreds` circle rail.
4. **Mobile polish** — add the Messages tab to the mobile bar, theme
   (light/dark, follow system), read-only affordance sweep, service-worker
   handling.

Each plan is its own spec-plan-build-prove cycle. This spec covers the whole
architecture; the first plan scopes to slice 1.

## Testing

- **Golden-JSON shape tests (desk):** for each endpoint, assert the phone's
  marshaled response byte-matches (or shape-matches, field-for-field) hearth's
  real `api.py` response for the same data — this is what guarantees `app.js`
  runs unchanged. Drive from a seeded store, compare to hearth's output.
- **Local-server security (desk, JVM):** loopback-only bind, token-gated before
  any store access, read-only writes rejected uniformly, CSP present, no
  path traversal — mirror the MediaServer gate.
- **On-device (per slice):** the view renders in the WebView identically to the
  desktop; images (incl. AVIF via the sandbox) and video play; read-only
  affordances are hidden; theme + bottom nav correct.

## Risks / honest unknowns (resolve during build)

- **Service worker / PWA bits.** `app.js` may register `/sw.js`; in a WebView a
  service worker is unnecessary and could cache stale shells — serve a no-op
  stub or strip the registration for the WebView build.
- **App-shell assumptions.** The web UI expects onboarding/bootstrap/applock/
  update endpoints; read-only stubs must land the UI directly in the app
  without a setup flow.
- **Exact JSON shapes.** `/api/state`, `/api/feed`, `/api/profile` are rich
  (scope chips, rings, wall layout, responses, banner) — the marshaling must be
  faithful; the golden-JSON tests are the guard.
- **Loopback-cleartext WebView config** has the same hazard as B.2d-2's video
  (`expo prebuild --clean` drops the manual NSC/ATS edits) — needs the same
  care, and ideally a config plugin this time.
- **WebView performance** on the low-end G20 (3.5 GB) for the wall's many images
  — lazy-load / thumbnail via the API, watch scroll.
- **Auth/session.** The local API is single-user (own identity on-device); no
  auth beyond the loopback token. Confirm no web-UI flow assumes a login.

## Out of scope (named)

- Any **outbound/write** capability (posting, reacting, commenting, DMs,
  profile edits, wall arrange) — read-only this arc; `body.readonly` is the seam
  for the later outbound slice.
- iOS (Mac-gated: the Swift engine port + a loopback server + WKWebView; the UI
  itself needs no port).
- Bootstrapping/onboarding/pairing/device-enrollment flows from the phone.
- A visual redesign of the desktop UI itself — we reuse it as-is.
- Replacing the native engine — Tor/sync/crypto/MediaServer/isolated-decode are
  unchanged.
