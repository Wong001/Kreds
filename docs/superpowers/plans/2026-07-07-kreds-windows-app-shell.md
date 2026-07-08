# Kreds Windows App Phase 1 (pywebview Shell) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A frameless Windows app — `python -m hearth app` opens the node's web UI in a borderless pywebview window with custom traffic-light chrome, owns the node lifecycle, and honors a user-set Quit-vs-Keep-running close behavior.

**Architecture:** `hearth/desktop.py` launches `run_serve` in a background thread (fresh asyncio loop) on a free localhost port, then opens a frameless pywebview WebView2 window at that URL; a JS↔Python `Api` bridge drives minimize/maximize/quit; the client renders a custom title bar only inside pywebview. Graceful shutdown via a new `shutdown` asyncio.Event threaded into `run_serve`/`run_node`. Packaging is Phase 2.

**Tech Stack:** Python 3.12, **pywebview (NEW dep)**, FastAPI/uvicorn, pytest; vanilla-JS client. `node --check`.

**Spec:** `docs/superpowers/specs/2026-07-07-kreds-windows-app-shell-design.md`

## Global Constraints

- Branch: `kreds-windows-app-shell` off `main` (already created + checked out — do NOT re-branch).
- Quality over shortcuts. `pip install pywebview` + add to `requirements.txt`. **`import hearth.desktop` MUST work without pywebview installed** (lazy `import webview` inside `launch()`), so the test suite doesn't hard-depend on the GUI lib. Test runner: `timeout 180 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3`; full suite green each commit; `node --check hearth/web/app.js` clean. ASCII-only Python prints.
- **The pywebview GUI cannot be unit-tested headless** — Claude tests the node lifecycle / shutdown event / free-port / data-dir / bridge logic (mock the window); the ACTUAL window (frameless, controls, drag, close behavior) is verified by the USER. Do NOT try to launch a real window in pytest.
- No behavior change to the demo / CLI `run`/`serve` when `shutdown` is None.
- Close behavior: meta `close_behavior` in `("quit","keep")` (default `"quit"`); desktop-only UI (gated on `window.pywebview`).
- Out of scope (Phase 2 / do NOT build): PyInstaller packaging, `.exe`, bundling tor.exe/assets, WebView2 bootstrap, system-tray (`pystray`), single-instance lock.

---

### Task 1: Graceful node shutdown (runner)

**Files:**
- Modify: `hearth/runner.py` (`run_node` + `run_serve` gain `shutdown: asyncio.Event | None`)
- Test: `tests/test_runner.py` (extend)

**Interfaces:**
- Produces: `run_node(..., shutdown=None)`, `run_serve(..., shutdown=None)` — setting the event makes the server(s) exit cleanly.

- [ ] **Step 1: Branch exists — skip; start at Step 2.**

- [ ] **Step 2: Failing test** — extend `tests/test_runner.py`:

```python
import asyncio, socket, httpx
from pathlib import Path
from hearth.runner import run_serve

def _free_port():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); p = s.getsockname()[1]; s.close(); return p

def test_run_serve_stops_on_shutdown_event(tmp_path):
    async def scenario():
        port = _free_port()
        shutdown = asyncio.Event()
        task = asyncio.create_task(run_serve(tmp_path / "n", 0, port, shutdown=shutdown))
        # wait for the bootstrap server to answer
        async with httpx.AsyncClient() as c:
            for _ in range(60):
                try:
                    if (await c.get(f"http://127.0.0.1:{port}/api/bootstrap")).status_code == 200:
                        break
                except Exception:
                    pass
                await asyncio.sleep(0.1)
        shutdown.set()                              # request quit (still in bootstrap phase)
        await asyncio.wait_for(task, timeout=10)    # must return, not hang
    asyncio.run(scenario())
```

- [ ] **Step 3: Run — expect failure.**

- [ ] **Step 4: `run_node` — watcher sets `should_exit`.** Add `shutdown: "asyncio.Event | None" = None` to the signature. Right after the `server = uvicorn.Server(...)` line:

```python
        watcher = None
        if shutdown is not None:
            async def _watch():
                await shutdown.wait()
                server.should_exit = True
            watcher = asyncio.create_task(_watch())
```
and in the `finally` that cancels `loop_task`, also cancel `watcher` (guard `if watcher:`).

- [ ] **Step 5: `run_serve` — thread `shutdown` through + break the bootstrap wait.** Add `shutdown=None` to the signature. In the bootstrap phase, add the shutdown event to the `asyncio.wait` set so a quit during first-run stops cleanly:

```python
        waiters = {ready_task, task}
        sd_task = asyncio.create_task(shutdown.wait()) if shutdown is not None else None
        if sd_task: waiters.add(sd_task)
        done, pending = await asyncio.wait(waiters, return_when=asyncio.FIRST_COMPLETED)
        if sd_task and sd_task in done:              # user quit during first-run
            server.should_exit = True
            for t in (ready_task,): t.cancel()
            await asyncio.gather(task, return_exceptions=True)
            return
        # ... existing task-in-done (fail-loud) + ready path ...
```
Then pass `shutdown=shutdown` to the final `run_node(...)` call. (Cancel/cleanup `sd_task`/`ready_task` on the ready path to avoid pending-task warnings.)

- [ ] **Step 6: Run test + full suite. Commit.**

```powershell
git add hearth/runner.py tests/test_runner.py
git commit -m "feat: graceful node shutdown - run_node/run_serve accept a shutdown asyncio.Event (server.should_exit); demo/CLI unaffected when None"
```

---

### Task 2: `hearth/desktop.py` shell + `python -m hearth app`

**Files:**
- Create: `hearth/desktop.py`
- Modify: `hearth/cli.py` (`app` command), `requirements.txt` (add `pywebview`)
- Test: `tests/test_desktop.py`

**Interfaces:**
- Produces: `default_data_dir()`, `_free_port()`, `class Api` (bridge), `launch(data_dir=None)`.

- [ ] **Step 1: `pip install pywebview`** (`.venv/Scripts/pip install pywebview`) and add `pywebview` to `requirements.txt`.

- [ ] **Step 2: Failing tests** — `tests/test_desktop.py` (NON-GUI only; never call `launch()`):

```python
import asyncio, os
from pathlib import Path
import hearth.desktop as desktop          # must import WITHOUT pywebview being needed

def test_default_data_dir_uses_appdata(monkeypatch, tmp_path):
    monkeypatch.setenv("APPDATA", str(tmp_path))
    assert desktop.default_data_dir() == Path(tmp_path) / "Kreds"

def test_free_port_is_usable():
    p = desktop._free_port()
    assert isinstance(p, int) and 1024 < p < 65536

def test_api_is_desktop():
    assert desktop.Api({}).is_desktop() is True

def test_api_quit_sets_shutdown_event():
    # quit() must signal the node loop's shutdown event (thread-safe) + destroy the window
    loop = asyncio.new_event_loop()
    ev = asyncio.Event()
    holder = {"loop": loop, "ev": ev}
    api = desktop.Api(holder)
    class W:
        destroyed = False
        def destroy(self): self.destroyed = True
    api.window = W()
    api.quit()
    loop.run_until_complete(asyncio.wait_for(ev.wait(), timeout=2))   # event got set
    assert api.window.destroyed
    loop.close()
```

- [ ] **Step 3: Run — expect failure (module missing / functions undefined).**

- [ ] **Step 4: Implement `hearth/desktop.py`:**

```python
"""Kreds Windows desktop shell: launch the node in a background thread and show
its web UI in a frameless pywebview window with our own chrome. Phase 1 (runs
from source); PyInstaller packaging is Phase 2. `import webview` is LAZY so this
module imports without the GUI dep (tests)."""
import asyncio
import os
import socket
import threading
import time
import urllib.request
from pathlib import Path

from .runner import run_serve

def default_data_dir() -> Path:
    base = os.environ.get("APPDATA") or str(Path.home())
    return Path(base) / "Kreds"

def _free_port() -> int:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port

def _wait_http(url: str, timeout: float = 25.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=1) as r:
                if r.status == 200:
                    return True
        except Exception:
            pass
        time.sleep(0.15)
    return False

class Api:
    """window.pywebview.api bridge for the custom chrome."""
    def __init__(self, holder: dict):
        self._holder = holder          # {"loop": asyncio loop, "ev": shutdown Event}
        self.window = None
        self._maximized = False

    def is_desktop(self):
        return True

    def minimize(self):
        if self.window: self.window.minimize()

    def toggle_maximize(self):
        # pywebview's maximize API varies by version; verify against the installed
        # version and use .maximize()/.restore() if present, else toggle_fullscreen().
        if not self.window: return
        if self._maximized: self.window.restore()
        else: self.window.maximize()
        self._maximized = not self._maximized

    def quit(self):
        loop = self._holder.get("loop"); ev = self._holder.get("ev")
        if loop is not None and ev is not None:
            loop.call_soon_threadsafe(ev.set)      # ask the node to shut down cleanly
        if self.window: self.window.destroy()

def launch(data_dir=None):
    import webview                                  # LAZY (GUI dep not needed for import)
    data_dir = Path(data_dir) if data_dir else default_data_dir()
    data_dir.mkdir(parents=True, exist_ok=True)
    port = _free_port()
    holder: dict = {}

    def _node_thread():
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        ev = asyncio.Event()
        holder["loop"] = loop
        holder["ev"] = ev
        try:
            loop.run_until_complete(run_serve(data_dir, 0, port, shutdown=ev))
        finally:
            loop.close()

    t = threading.Thread(target=_node_thread, daemon=True)
    t.start()
    while "loop" not in holder:                     # wait until the loop/ev exist
        time.sleep(0.02)
    if not _wait_http(f"http://127.0.0.1:{port}/api/bootstrap"):
        raise RuntimeError("node did not start")
    api = Api(holder)
    window = webview.create_window(
        "Kreds", url=f"http://127.0.0.1:{port}", frameless=True, js_api=api,
        width=1100, height=760, min_size=(900, 600))
    api.window = window
    webview.start()                                 # blocks until the window is destroyed
    # window gone -> ensure the node is asked to stop, then join
    loop = holder.get("loop"); ev = holder.get("ev")
    if loop is not None and ev is not None:
        loop.call_soon_threadsafe(ev.set)
    t.join(timeout=8)
```

- [ ] **Step 5: `cli.py` — `app` command:**

```python
    sp = sub.add_parser("app", help="launch the Kreds desktop app (frameless window)")
    sp.add_argument("--dir", default=None, help="data dir (default %APPDATA%/Kreds)")
```
```python
    elif args.cmd == "app":
        from .desktop import launch
        launch(args.dir)
```

- [ ] **Step 6: Run tests + full suite + `node --check`. Commit.**

```powershell
git add hearth/desktop.py hearth/cli.py requirements.txt tests/test_desktop.py
git commit -m "feat: hearth/desktop.py - pywebview frameless shell launching the node (bg thread) + Api bridge; 'hearth app' CLI"
```

---

### Task 3: Custom chrome (client) + close-behavior setting

**Files:**
- Modify: `hearth/web/index.html` (title bar), `hearth/web/app.js` (desktop-detect + chrome wiring + close-per-pref + wizard step + settings), `hearth/web/style.css` (title bar + traffic lights)
- Modify: `hearth/api.py` (`GET`/`POST /api/settings` with `close_behavior`)
- Test: `tests/test_web_assets.py`, `tests/test_applock_api.py` (or a settings test)

**Interfaces:**
- Consumes: `window.pywebview.api` (`minimize`/`toggle_maximize`/`quit`/`is_desktop`), `GET/POST /api/settings`.

- [ ] **Step 1: Failing tests** — asset test + a settings API test:

```python
# tests/test_web_assets.py
def test_desktop_custom_chrome():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert "window.pywebview" in js                       # desktop detection
    assert "pywebview-drag-region" in html or "pywebview-drag-region" in js  # drag region
    assert ".titlebar" in css                             # custom chrome styled
    assert "close_behavior" in js and "/api/settings" in js

# tests/test_settings_api.py (new)
def test_settings_close_behavior(tmp_path):
    from fastapi.testclient import TestClient
    from hearth.api import build_app
    from hearth.node import HearthNode
    c = TestClient(build_app(HearthNode.create(tmp_path/"n", "W", "d")))
    assert c.get("/api/settings").json()["close_behavior"] == "quit"     # default
    assert c.post("/api/settings", json={"close_behavior": "keep"}).status_code == 200
    assert c.get("/api/settings").json()["close_behavior"] == "keep"
    assert c.post("/api/settings", json={"close_behavior": "bad"}).status_code == 400
```

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: `api.py` — settings endpoints** (mirror the `_400` idiom):

```python
    @app.get("/api/settings")
    async def get_settings():
        return {"close_behavior": node.store.get_meta("close_behavior") or "quit"}

    @app.post("/api/settings")
    async def set_settings(body: dict = Body(...)):
        cb = body.get("close_behavior")
        if cb not in ("quit", "keep"):
            raise HTTPException(400, "bad close_behavior")
        node.store.set_meta("close_behavior", cb)
        return {"ok": True}
```

- [ ] **Step 4: `index.html` — title bar** (hidden by default; shown only in desktop). A `.titlebar` with a `.titlebar-drag pywebview-drag-region` span (the draggable area — the app title/logo) and a `.titlebar-controls` group holding three buttons (minimize / maximize / close) — the buttons must NOT have the drag class.

- [ ] **Step 5: `app.js` — desktop detection + chrome + close-per-pref.**
  - Early in boot, if `window.pywebview` exists, add a `desktop` class to `<body>` (shows the title bar, shifts content down) and wire the three controls: min → `window.pywebview.api.minimize()`; max → `toggle_maximize()`; **close** → fetch `GET /api/settings`, then `close_behavior === "keep" ? api.minimize() : api.quit()`.
  - `window.pywebview` may not be ready at first paint — pywebview fires `pywebviewready` on `window`; wire the chrome on that event (and also check on boot in case it's already ready).
- [ ] **Step 6: Onboarding wizard step + Settings toggle (desktop-only).**
  - In `renderOnboardingWizard` (feature 14), add a **desktop-only** step (only when `window.pywebview`): "When you close the window: **Quit the app** / **Keep running in the background**" → `POST /api/settings {close_behavior}`. Place it before the Done step.
  - In the Settings/Me area (desktop-only), a toggle reflecting/POSTing `close_behavior`.
- [ ] **Step 7: `style.css` — title bar + traffic lights** (fixed top bar, drag region, three round controls top-right in Kreds tokens; content offset by the bar height under `body.desktop`).

- [ ] **Step 8: Run asset+settings tests + `node --check` + full suite. Commit.**

```powershell
git add hearth/web/index.html hearth/web/app.js hearth/web/style.css hearth/api.py tests/test_web_assets.py tests/test_settings_api.py
git commit -m "feat: desktop custom chrome (frameless title bar + traffic-light controls, drag region) + close-behavior setting (wizard step + Settings, /api/settings)"
```

---

### Task 4: Integration + docs

**Files:**
- Test: `tests/test_desktop_integration.py` (optional — see step 1)
- Modify: `README.md`, `ROADMAP.md`

- [ ] **Step 1: Lifecycle integration test** — reuse Task 1's shutdown-event proof (run_serve starts on a free port, answers, then a shutdown event stops it cleanly and the task returns) as the core lifecycle guarantee the shell relies on. (A real pywebview window can't be driven headless — do NOT attempt; the window itself is August's to verify.) If Task 1's test already covers this, note it and skip a duplicate.

- [ ] **Step 2: Full suite + `node --check`** — all pass (run twice). Demo + CLI `run`/`serve` + all existing paths unaffected. Confirm `import hearth.desktop` works even though the test env may/may not have pywebview.

- [ ] **Step 3: README + ROADMAP** — document `hearth app` (the frameless desktop shell): custom traffic-light chrome, node launched into `%APPDATA%\Kreds`, the Quit-vs-Keep-running close setting (onboarding + Settings; keep = minimize, node stays syncing, restore from taskbar). State clearly that **PyInstaller packaging → distributable `.exe` (bundling tor.exe + assets + WebView2 bootstrap) and a real system-tray icon are Phase 2 / follow-ups**, and note the new `pywebview` dependency. Increment/added feature entry.

- [ ] **Step 4: Commit**

```powershell
git add tests/test_desktop_integration.py README.md ROADMAP.md
git commit -m "test+docs: Windows app Phase 1 (pywebview shell) lifecycle + ship notes; packaging + tray = Phase 2"
```

---

## Completion

After Task 4: whole-branch review (superpowers:requesting-code-review) — focus: the `shutdown` event stops `run_node` AND a mid-bootstrap `run_serve` cleanly (no hang, no orphaned server/thread); `shutdown=None` leaves the demo/CLI untouched; `import hearth.desktop` works WITHOUT pywebview (lazy import) so the suite doesn't gain a hard GUI dep; the node runs in a background thread with its own loop and `Api.quit()` signals it thread-safely (`call_soon_threadsafe`) then destroys the window; the shell joins the node thread on exit (no zombie node/port held); the custom chrome + close-behavior are gated on `window.pywebview` (a plain browser is completely unaffected — no title bar, no wizard step); `close_behavior` validated (400 on bad); `/api/settings` doesn't leak anything; the free-port pick can't collide with the bound server; no secret exposed. The GUI itself (frameless window, controls, drag, close behavior) is the USER's to verify. Then superpowers:finishing-a-development-branch — merge to `main`, push. Next: **Phase 2 — packaging** (PyInstaller .exe, bundle tor.exe/assets, WebView2, tray), then the video trimmer.
