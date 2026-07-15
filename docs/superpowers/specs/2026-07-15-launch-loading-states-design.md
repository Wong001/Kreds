# Launch loading states + startup reliability — design

Date: 2026-07-15
Status: approved (August, 2026-07-15 — option A + bugfixes; HTTP-before-Tor
restructure deferred to its own roadmap slice)
Slice: 2 of 3 in the 0.3.11 fixes bundle

## Problem

Launch "crashes": blank screen then "Kreds failed to start" on slow
launches, launch-after-update "always fails", onboarding dead-ends.
Investigation (2026-07-15) found:

1. **Timeout mismatch (unconditional bug):** `desktop.py:426` waits 120 s
   for readiness, but `tor.py` allows 2×90 s + 1.5 s ≈ 181.5 s of
   bootstrap budget. Slow-but-healthy launches get declared failed. The
   window opens only after full readiness, so the user sees NOTHING until
   then (`launch()` gates `create_window` on `_await_node_ready`).
2. **Post-update pile-up:** instant relaunch + freshly-extracted tor.exe
   (AV first-run scan) + `TorProcess.stop()` hard-kills tor
   (`terminate()`, never a control-port SHUTDOWN → risk of unclean
   consensus cache → cold-ish next bootstrap) + hardcoded
   SocksPort/ControlPort (9250/9251) colliding with the still-exiting old
   tor → "tor exited before bootstrap" (matches app.log), retried once
   after only 1.5 s.
3. **Onboarding handoff:** after profile create, the HTTP port is unbound
   for the whole Tor bootstrap; `pollForFullApp` (`app.js:2339-2349`)
   gives up after ~20 s with "Still starting up... You can leave this
   page open" and NEVER polls again — the message is false.
4. **Impatience kill:** relaunching during the blank window shows
   "already running... check your system tray" — but the tray is created
   only after readiness, so it doesn't exist yet; users then Task-Manager
   the healthy instance.
5. The launcher's crash-rollback net (non-zero exit within 8 s) is blind
   to all of this (timeout path exits 0, late) — recorded as tech debt,
   not fixed here.

## Design

### 1. Window-first with real progress

`launch()` creates the webview window IMMEDIATELY after the node thread
starts, pointed at a bundled loading page (same inline-HTML technique as
`_show_error_window`), with a `js_api` exposing `get_startup_status()`
read from the existing `holder` dict. The loading page polls it (~500 ms)
and renders stage + percentage. On readiness, the window navigates to
`http://127.0.0.1:{port}` (`window.load_url`). On failure it renders the
error state in place (replacing `_show_error_window`'s separate window
for this path).

Stages reported via `holder["startup"]` (set by the node thread):
`starting` → `tor-binary` (resolve/download) → `tor-bootstrap` (with
percent) → `onion-publish` → `serving` → `ready`; or `failed` with the
captured error. Percent source: `tor.py:_drain` already reads Tor's
`Bootstrapped NN%` lines and discards them (`tor.py:186-192`) — it gains
an optional callback that stashes the latest percent/message into the
holder. `run_node`/`run_serve` gain an optional `status` callback
threaded from `desktop._start_node` (default no-op: `hearth serve` CLI
and tests are unaffected).

### 2. Timeout corrections

- Outer ready-wait (`desktop.py:426`): 120 s → **240 s** with Tor
  (> tor.py's own 181.5 s worst case + margin for AV overhead); non-Tor
  stays 25 s. With the loading window up, a long wait is visible progress,
  not a hang.
- Onboarding `pollForFullApp`: poll FOREVER at a backing-off interval
  (500 ms → 2 s), showing the same startup stages (the loading page's
  status source is reachable from the onboarding page via
  `window.pywebview.api`). The dead-end message dies.

### 3. Graceful Tor shutdown + restart hygiene

- `TorProcess.stop()`: send `SIGNAL SHUTDOWN` over the already-open
  control connection, wait up to 5 s for exit, then `terminate()` as the
  fallback. Reduces unclean-cache cold bootstraps after updates/restarts.
- Inter-attempt sleep in `TorProcess.start()` retry: 1.5 s → 5 s (gives a
  dying predecessor's ports time to free).

### 4. Copy fix for the already-running dialog

`_notify_already_running` message covers the bootstrap window: "Kreds is
already running or still starting up. If it just launched, give it a
moment - the window appears when it's ready. Otherwise check the system
tray." (final wording August's to veto in review — it is user-facing).

## Tests

- `tor.py` drain callback: feed synthetic `Bootstrapped 45%` lines →
  callback receives 45; 100% still flips the ready event.
- Graceful stop: fake control connection records `SIGNAL SHUTDOWN` sent
  before terminate; terminate still called if no exit within the grace
  window (mock process).
- Status threading: `run_serve`/`run_node` with a recording status
  callback emit the stage sequence in order (non-Tor path:
  `starting → serving → ready`).
- Timeout values pinned (240/25) so a future edit is a conscious one.
- Onboarding poll: JS-side logic is exercised by the existing UI_E2E
  smoke pattern where feasible; at minimum the backoff/never-give-up
  loop is unit-adjacent (extracted, testable).
- Manual verification (August, behavioral pass): update-restart on the
  real install shows the loading window with percent instead of failing.

## Out of scope (roadmap items, recorded)

- HTTP-before-Tor startup reorder (structural fix; deferred by decision).
- Launcher rollback blindness (needs a health-file handshake; tech debt).
- Parallel peer dialing.
