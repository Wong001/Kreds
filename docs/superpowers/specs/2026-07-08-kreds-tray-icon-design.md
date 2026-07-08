# Kreds system tray icon — design

Date: 2026-07-08
Status: approved (August, in-session)

## Problem

"Keep running in the background" (close_behavior=keep, feature 15) only
minimizes the window to the taskbar. There is no tray icon, so a
"kept-running" Kreds is indistinguishable from a closed one except for a
lingering taskbar button — the exact complaint. Restore-from-tray, a Quit
menu item, and a first-minimize notification have been a named follow-up
since features 15/17.

## Decision (August, 2026-07-08)

**Always-in-tray model** (Signal/Discord style): the tray icon is present
whenever the app runs, not only while hidden. Close with "keep" hides the
window entirely (no taskbar entry); the tray icon is the way back.

## Design

### Dependency

`pystray` (MIT, wraps Win32 `Shell_NotifyIcon`) + Pillow (already a
dependency) for icon loading. `import pystray` is **lazy** — inside the
tray-creation function only, mirroring the lazy `import webview` pattern —
so `import hearth.desktop` and the test suite gain no GUI dependency.
Added to `requirements.txt`; PyInstaller picks it up from the import in
the frozen build (verify in the packaged smoke).

### Tray lifecycle (hearth/desktop.py)

- `_create_tray(api, data_dir)` builds a `pystray.Icon` from
  `packaging/kreds.ico` (frozen: bundled via the spec's datas; source
  runs: resolved from the repo; a missing icon falls back to a
  Pillow-drawn 3-arc placeholder so dev never crashes).
- The icon runs on its own daemon thread (`icon.run_detached()` if
  available, else `threading.Thread(target=icon.run)`), started in
  `launch()` right after the window is created, stopped (`icon.stop()`)
  in `Api.quit()` before window destroy — one place, so titlebar-quit,
  tray-quit, and `Api.restart()` (which calls `quit()`) all remove the
  icon. No ghost icons.
- Menu: **Open Kreds** (default item — double-click triggers it) →
  `api.show_window()`; **Quit Kreds** → `api.quit()`. Nothing else.
- pywebview `Window.hide()/.show()` are documented thread-safe (they
  marshal to the GUI thread); the implementer verifies against the
  installed pywebview version during Task 1 and notes the result in the
  code comment.

### Api changes (hearth/desktop.py)

- `Api.hide_to_tray()` — `window.hide()` plus the one-time balloon (see
  below). Called by the web client's close handler when
  `close_behavior == "keep"` (replacing `api.minimize()` there; the
  titlebar minimize button keeps calling `minimize()` — minimize is
  still taskbar-minimize, only CLOSE goes to tray).
- `Api.show_window()` — `window.show()` then `window.restore()` (in case
  it was minimized before hiding); used by the tray menu/double-click.
- `Api.quit()` gains `self._tray.stop()` (guarded, best-effort try/except
  — a dead tray thread must never block shutdown).

### First-hide balloon (one-time)

On the first `hide_to_tray()` per data dir, show a pystray notification:
"Kreds keeps running in the background. Click the tray icon to open it
again." Marker: a `tray_notified` flag file in the data dir
(shell-owned state like `instance.lock`; not node meta — the node
doesn't own shell UX state). Balloon failures are swallowed (pystray
notify support varies; the hide must never fail because a balloon did).

### Client change (hearth/web/app.js)

`wireDesktopChrome()`'s close handler: `close_behavior === "keep"` calls
`api.hide_to_tray()` instead of `api.minimize()`, with a fallback to
`api.minimize()` when `hide_to_tray` is absent (an updated web payload
talking to an older frozen shell — the web/core version skew the update
system explicitly allows). Copy updates:

- Onboarding wizard close-behavior step: "Keep running in the background
  (in the system tray)".
- Settings toggle label: same wording.
- Desktop "already running" notice (`_notify_already_running`): append
  "It may be running in the background — check your system tray."

### Out of scope

- No per-notification tray badges/counts, no unread indicators.
- No "start minimized to tray" / autostart option (separate feature).
- No macOS/Linux tray (the shell itself is Windows-only today).

## Edge cases

- Tray thread dies (pystray/Win32 hiccup): the app keeps working; close
  with "keep" still hides IF the window can be restored some other way —
  it can't, so `hide_to_tray()` first checks the tray thread is alive
  and falls back to `minimize()` when it isn't. Never strand the user
  with a hidden window and no way back.
- Quit while hidden: tray Quit works with the window hidden (destroy on
  a hidden window is fine).
- Second-instance launch while first is hidden: existing single-instance
  notice fires (now mentioning the tray).
- Dev `hearth app` run from source: same tray (icon resolved from repo
  path), so the feature is testable without freezing.

## Testing

- **Automated (pytest):** lazy-import guard (`import hearth.desktop`
  without pystray installed still works — mirror the existing webview
  test if present, else add both); `hide_to_tray()` fallback to
  `minimize()` when tray is dead (pure-Python, mockable); the one-time
  flag file (set on first call, balloon skipped on second); `Api.quit()`
  stops the tray best-effort (a raising tray never blocks shutdown);
  web-asset statics: close handler calls `hide_to_tray` with `minimize`
  fallback, wizard/Settings copy updated, `pystray` in requirements.txt.
- **August (by hand, on the away-laptop after release):** icon appears on
  launch; close with "keep" hides fully (no taskbar button) + balloon
  first time only; double-click and Open Kreds restore; Quit Kreds fully
  exits (no ghost icon, node stops); close with "quit" unchanged;
  restart-to-finish update removes the icon cleanly.

## Rollout

Ships as the next release (core change — new shell code — so it rides
the core-swap update path; the web payload's close-handler change is
backward-compatible via the `hide_to_tray` fallback).
