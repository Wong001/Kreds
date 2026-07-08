# Kreds Windows App — Phase 1: pywebview Shell — Design

**Date:** 2026-07-07
**Status:** Approved (design discussion, this session)
**Basis:** `runner.run_serve(data_dir, gossip_port, http_port, ...)` (two-phase bootstrap → full node, feature 14) serves one node's web UI on a localhost port. `run_node` builds a `uvicorn.Server` and `await server.serve()` in a try/finally (no external stop handle yet). App-lock (13) + onboarding (14) shipped. The web client detects desktop context by `window.pywebview` (absent in a plain browser).
**Branch:** `kreds-windows-app-shell` off `main`
**Product context:** the desktop-app sketch's frameless custom chrome. Phase 1 = the shell (runs from source, testable now). **Phase 2 = PyInstaller packaging + bundling `tor.exe`/assets + WebView2 handling** — separate, NOT this slice. Internal package stays `hearth`.

## Why

Kreds today runs in a browser pointed at the node. The sketch wants a real desktop app: OUR frameless window (no Windows title bar) with custom traffic-light chrome, launching + owning the node, so it feels like a native Kreds app, not a web page.

## Decisions locked this session

- **pywebview** (OS WebView2, Python-native) — a new runtime GUI dependency; the minimal wrapper choice.
- **Close behavior is the user's choice, set at onboarding + changeable in Settings:** **Quit** (stop node + exit) or **Keep running** (minimize; the node keeps syncing; restore from the taskbar).
- **Keep-running = minimize-to-taskbar now; a real system-tray icon (`pystray`) is a later follow-up** (avoids a 2nd GUI dep in Phase 1).
- Frameless window; custom chrome renders ONLY inside pywebview.
- Per-user data dir `%APPDATA%\Kreds`.
- Packaging is Phase 2.

## Components

### 1. Graceful node shutdown (backend)
- `run_node` / `run_serve` gain an optional `shutdown: asyncio.Event | None`. When passed, a watcher task awaits it and sets `server.should_exit = True` (unblocking `server.serve()` → the existing `finally` stops sync/tor cleanly). During `run_serve`'s bootstrap phase, the same event also breaks the `await ready.wait()` (wait on both, like the port-bind guard). No behavior change when `shutdown` is None (demo/CLI unaffected).

### 2. `hearth/desktop.py` — the shell + `python -m hearth app`
- **Lazy import** of `webview` INSIDE `launch()` (so `import hearth.desktop` works in tests without pywebview installed).
- `default_data_dir()` → `%APPDATA%\Kreds` (`os.environ["APPDATA"]`), created if absent.
- `launch(data_dir=None, ...)`:
  - `data_dir = data_dir or default_data_dir()`; pick a **free localhost port** (bind a socket to `127.0.0.1:0`, read the port, release).
  - Start the node in a **background thread**: a fresh asyncio loop running `run_serve(data_dir, gossip_port=0, http_port=port, shutdown=<event>)`. Wait until the port answers (`GET /api/bootstrap` 200) before showing the window (a short poll).
  - Create a **frameless** window (`webview.create_window(title="Kreds", url=f"http://127.0.0.1:{port}", frameless=True, js_api=Api(...))`, easy_drag off — we use our own drag region).
  - `webview.start()` on the main thread (blocks until the window is destroyed).
  - On window destroy / quit: set the shutdown event (thread-safe), join the node thread with a timeout, exit.
- **`Api`** (the `window.pywebview.api` bridge): `is_desktop()`→True; `minimize()`; `toggle_maximize()`; `quit()` (set shutdown event → destroy window → process exits). "Keep running" close uses `minimize()`. All methods operate on the active `webview.windows[0]`.
- CLI: `hearth app [--dir]` (default `%APPDATA%\Kreds`).
- Add `pywebview` to `requirements.txt`.

### 3. Close-behavior setting (backend + client)
- Meta `close_behavior` in `("quit","keep")`; `GET /api/settings` returns it (default `"quit"`); `POST /api/settings {close_behavior}` sets it (validated). (A tiny settings surface; reuse the `_400` idiom.)
- **Onboarding wizard** gains a step (desktop-only — shown when `window.pywebview`): "When you close the window: **Quit** / **Keep running in the background**" → `POST /api/settings`.
- **Settings** (in the Me area, desktop-only) exposes the same toggle.

### 4. Custom chrome (client)
- Rendered ONLY when `window.pywebview` exists (feature-detect). A fixed **title bar** whose drag area carries pywebview's built-in **`pywebview-drag-region`** CSS class (pywebview moves the window when that region is dragged — the supported mechanism, no `-webkit-app-region`), plus the **traffic-light controls top-right**: minimize (`api.minimize()`), maximize/restore (`api.toggle_maximize()`), and **close** (reads `close_behavior`: `quit` → `api.quit()`, `keep` → `api.minimize()`). The control buttons themselves must NOT carry the drag class (so clicks register).
- The web UI shifts down by the title-bar height inside the shell; a plain browser renders no bar (unchanged).

## Testing

Claude-owned (non-GUI + lifecycle):
- `run_node`/`run_serve` with a `shutdown` event: the server exits when the event is set (integration-style, free port, terminates); `shutdown=None` unchanged.
- `hearth.desktop` imports WITHOUT pywebview installed (lazy import); `default_data_dir()` → `%APPDATA%\Kreds`; free-port picker returns a usable port; `Api.quit()` sets the shutdown event; `is_desktop()`→True. (Mock `webview`/window where needed; do NOT launch a real GUI in tests.)
- `close_behavior` meta round-trips; `GET/POST /api/settings` validate.

August-owned (GUI — checklist): `python -m hearth app` opens a frameless window (no Windows title bar); traffic-light minimize/maximize/close work; the bar drags the window; **Quit** vs **Keep running** honored per the setting (keep = minimizes, node stays up, restore from taskbar); onboarding shows the close-behavior step inside the app; Settings toggles it; a plain browser shows no custom bar.

## Out of scope (named)

- **Phase 2: PyInstaller packaging → distributable `.exe`, bundling `tor.exe` + web assets + fonts, WebView2 bootstrap, installer, single-instance lock.**
- A real **system-tray icon** (`pystray`) for keep-running — later follow-up (taskbar-minimize now).
- macOS/Linux shells; auto-update; auto-start-on-login.

## Success criteria

- `python -m hearth app` launches the node into `%APPDATA%\Kreds` and shows a **frameless** Kreds window with working custom traffic-light chrome (minimize/maximize/close) + a draggable bar; **close** honors the user's Quit-vs-Keep-running choice (set at onboarding, changeable in Settings); quitting shuts the node down cleanly; a plain browser is unaffected (no custom bar); the node lifecycle + settings tests + full suite pass. Packaging remains Phase 2.
