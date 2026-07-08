# Kreds Desktop Onboarding (first-run flow) — Design

**Date:** 2026-07-07
**Status:** Approved (design discussion, this session)
**Basis:** Kreds = Python node (FastAPI, `build_app(node)` closes over `node` across all routes) serving a vanilla-JS client. Node lifecycle today: `HearthNode.create(dir, person, device)`, device pairing `HearthNode.pair_request(dir, device)` / `node.accept_pairing(request)` / `HearthNode.pair_install(dir, package)`. `runner.run_node(dir, gossip_port, http_port, ...)` serves one node. CLI (`hearth/cli.py`): `init`/`run`/`pair-*`/`demo` — **no single "serve, bootstrapping if no identity yet."** App-lock (feature 13) already gates the client behind a lock screen via `GET /api/applock`.
**Branch:** `kreds-desktop-onboarding` off `main`
**Product context:** the branded desktop first-run experience (layers 1+2 of the desktop-app sketch). The frameless custom window chrome (traffic-light controls, no Windows title bar) is the SEPARATE next step (the pywebview Windows app). Internal package stays `hearth`.

## Why

Opening a fresh desktop install today has no first-run: `HearthNode` refuses to start without `keys.json`, and only the demo pre-creates nodes. Build the branded onboarding: **Create New Node** (new identity) or **Connect to Existing Node** (pair a device), a one-time setup wizard (App-lock + phone/iOS note), the circular-logo animation, and a restyled lock screen — all as web + a node "bootstrap mode," testable in the browser now.

## Decisions locked this session

- **Two-phase `hearth serve`**: no `keys.json` → bootstrap app (first-run) → on create/pair → full node app, same port.
- Create wizard: name (at create) → **App-lock setup, offered + SKIPPABLE** → **honest iOS/phone card** (no PWA — the iOS app is the real phone path, built after the Windows app; device-pairing already exists and slots in later) → done, shown once.
- **Connect** = copy-paste device pairing now (QR/camera lands with the iOS app).
- Logo breathing/rotation + interaction animations; App-lock screen restyled to match (keep the keypad).
- Frameless chrome / packaging = the NEXT step, not this one.

## Components

### 1. Bootstrap app + two-phase `serve` (backend)
- `hearth/bootstrap.py` (new): `build_bootstrap_app(data_dir, on_ready)` — a minimal FastAPI that serves the SAME web client (`index.html` + `/static`) and exposes:
  - `GET /api/bootstrap` → `{"initialized": false}` (the client's boot signal to show first-run).
  - `POST /api/bootstrap/create {name, device}` → `HearthNode.create(data_dir, name, device)`; on success call `on_ready()` and return `{ok: true}`.
  - `POST /api/bootstrap/pair-request {device}` → `HearthNode.pair_request(data_dir, device)` → returns the request string (the user carries it to their existing device).
  - `POST /api/bootstrap/pair-install {package}` → `HearthNode.pair_install(data_dir, package)` → `on_ready()` → `{ok: true}`.
  - (`accept_pairing` happens on the EXISTING device via its normal app/Settings — not a bootstrap endpoint.)
- `runner.run_serve(data_dir, gossip_port, http_port, ...)`: if `keys.json` absent → run `build_bootstrap_app` under uvicorn; `on_ready` sets an `asyncio.Event`; await it, then `server.should_exit = True` (graceful stop), then fall through to `run_node(data_dir, ...)` on the same port. If `keys.json` present → `run_node(...)` directly. One process, one port, clean hand-off.
- The **full app** also answers `GET /api/bootstrap` → `{"initialized": true, "onboarding_done": <bool>}` (so the client, after the reload, knows bootstrap is done AND whether to show the one-time wizard). `onboarding_done` = `store.get_meta("onboarding_done") == "1"`.
- CLI: `hearth serve --dir --http-port [--gossip-port] [--interval] [--tor]`.
- `onboarding_done` state: a new `POST /api/onboarding-done` on the full app calls `store.set_meta("onboarding_done", "1")` when the wizard finishes (so it shows once). A fresh `HearthNode.create` leaves the meta unset → `onboarding_done:false` → the client shows the wizard; a paired (Connect) device is already set up, so `pair_install` sets the meta so the wizard is skipped on that device.

### 2. Client: first-run + create + connect + transition
- **Boot logic** (`app.js`): `GET /api/bootstrap`; if `initialized === false` → render the first-run screen and STOP the normal boot. Else proceed to the existing App-lock/normal boot.
- **First-run screen** (`#first-run`): the circular logo + two option cards — **Create New Node** / **Connect to Existing Node** (matching the sketch).
- **Create**: a name field → `POST /api/bootstrap/create` → show "Setting up…" → poll `GET /api/state` (only answers once the full app is up) → then boot the full app (which shows the wizard because `onboarding_done` is unset).
- **Connect**: `POST /api/bootstrap/pair-request` → display the request string to copy (with instructions: "on your other device, open Settings → Add this device, paste this, and paste the result back here") → a field to paste the returned package → `POST /api/bootstrap/pair-install` → transition as above.

### 3. Client: one-time post-create wizard
- After a fresh create, gated on `onboarding_done` unset, a small wizard: **(a) App-lock** — "Protect this device with a PIN or passphrase" with a clear **Skip** (reuses the feature-13 `/api/applock/setup`); **(b) phone/iOS card** — the honest "Kreds for iPhone is in development; pair your phone when it ships; pair any device anytime from Settings" note (informational, no action); **(c) Done** → `POST` marks `onboarding_done` → normal app. Keyboard-accessible; Skip advances.

### 4. Animations + lock-screen restyle
- Circular-logo **breathing** (subtle scale/opacity pulse) and/or slow **rotation** animation (CSS keyframes; honor `prefers-reduced-motion`). Subtle hover/press animation on the option cards.
- **Restyle the App-lock lock screen** to share the first-run look (same logo treatment / background), keeping the existing PIN keypad + passphrase field intact.

## Testing

Claude-owned (backend lifecycle):
- `build_bootstrap_app`: `GET /api/bootstrap` → `initialized:false`; `POST /api/bootstrap/create` creates `keys.json` + fires `on_ready`; `pair-request` returns a request; the full app's `GET /api/bootstrap` → `initialized:true`.
- `run_serve`: with no `keys.json`, serves bootstrap; after a create, transitions to the full node app on the same port (integration-style, under timeout — assert a full-app route works post-transition). With `keys.json`, goes straight to the node app. Demo path (`run_node`) unchanged.
- `onboarding_done` meta round-trips; a fresh create leaves it unset.

August-owned (UX — checklist): first-run screen shows on a fresh dir; Create → name → wizard (App-lock skippable, iOS card) → app; Connect pairing round-trips; logo animation + card interactions feel right; restyled lock screen matches; reduced-motion respected.

Web asset/DOM tests: `#first-run` markup; `/api/bootstrap` + create/pair wiring; the wizard markup + `/api/applock/setup` + skip; logo-animation CSS + `prefers-reduced-motion`. `node --check`.

## Out of scope (named)

- **The pywebview desktop wrapper + frameless custom chrome (traffic-light min/max/close) + PyInstaller packaging + bundling `tor.exe`** — the NEXT step (the Windows app).
- QR/camera pairing (with the iOS app); the iOS app itself; multi-identity on one install; PWA (dropped).

## Success criteria

- A fresh data dir launched with `hearth serve` shows the branded first-run screen; **Create New Node** (name → skippable App-lock → iOS card → done) transitions into the running app in one process on one port; **Connect to Existing Node** pairs a device via copy-paste and transitions the same way; the logo animates (reduced-motion honored) and the lock screen matches; the wizard shows only once; the demo + all existing paths are unaffected; backend lifecycle tests + full suite green.
