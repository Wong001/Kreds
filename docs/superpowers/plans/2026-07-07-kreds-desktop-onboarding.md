# Kreds Desktop Onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A branded first-run flow — a `hearth serve` that bootstraps into Create-New-Node / Connect-to-Existing when there's no identity yet, a one-time setup wizard (App-lock + iOS note), the circular-logo animation, and a restyled lock screen.

**Architecture:** A node-less **bootstrap app** (`hearth/bootstrap.py`) serves the web client + create/pair endpoints while unenrolled; a two-phase `run_serve` runs it until a node is created/paired, then gracefully hands off to the full `run_node` on the same port/process. The client checks `GET /api/bootstrap` first and branches to first-run / lock / wizard / normal. Frameless chrome + packaging are a SEPARATE later step.

**Tech Stack:** Python 3.12, FastAPI, uvicorn, pytest; vanilla-JS client; `node --check`.

**Spec:** `docs/superpowers/specs/2026-07-07-kreds-desktop-onboarding-design.md`

## Global Constraints

- Branch: `kreds-desktop-onboarding` off `main` (already created + checked out — do NOT re-branch).
- Quality over shortcuts. Test runner: `timeout 180 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3`; full suite green each commit; `node --check hearth/web/app.js` clean. ASCII-only Python prints. No new dependency.
- **"Initialized" means ENROLLED**, not "keys.json exists": `pair_request` writes an unenrolled `keys.json` (cert=null) mid-pairing. Enrolled ⇔ `keys.json` present AND its `cert` is non-null.
- App-lock (feature 13): full app 423s all `/api/*` while locked except the allowlist. `GET /api/bootstrap` must be ADDED to that allowlist (non-sensitive flags) so a locked node still answers the client's first boot call.
- **Windows sqlite locking:** any `HearthNode`/`HearthNode.create`/`pair_install` opened in the bootstrap app MUST be `.close()`d before `run_node` re-opens the same `hearth.db`, or the handoff deadlocks on the file lock.
- Backend lifecycle testing is Claude's (controller verifies); the first-run/wizard/animation UX is the USER's to verify (hand a checklist) — but still add asset/DOM tests. Do NOT run Playwright/manual UI smoke.
- Out of scope (do NOT build): the pywebview wrapper, frameless chrome, PyInstaller packaging, QR/camera pairing, PWA.

---

### Task 1: Bootstrap app + two-phase `serve` + full-app endpoints

**Files:**
- Create: `hearth/bootstrap.py`
- Modify: `hearth/runner.py` (`run_serve`), `hearth/cli.py` (`serve` command), `hearth/api.py` (`GET /api/bootstrap`, `POST /api/onboarding-done`, allowlist), `hearth/node.py` (`pair_install` sets the onboarding meta)
- Test: `tests/test_bootstrap.py`

**Interfaces:**
- Produces: `build_bootstrap_app(data_dir, on_ready) -> FastAPI`; `run_serve(data_dir, gossip_port, http_port, interval=3.0, tor=False)`; `GET /api/bootstrap` (both apps); `POST /api/onboarding-done`.

- [ ] **Step 1: Branch exists — skip; start at Step 2.**

- [ ] **Step 2: Failing tests** — `tests/test_bootstrap.py` (use `TestClient`):

```python
import json
from pathlib import Path
from fastapi.testclient import TestClient
from hearth.bootstrap import build_bootstrap_app
from hearth.api import build_app
from hearth.node import HearthNode

def test_bootstrap_status_and_create(tmp_path):
    d = tmp_path / "n"; fired = []
    c = TestClient(build_bootstrap_app(d, lambda: fired.append(1)))
    assert c.get("/api/bootstrap").json() == {"initialized": False}
    r = c.post("/api/bootstrap/create", json={"name": "Wong", "device": "wong-pc"})
    assert r.status_code == 200 and r.json()["ok"] is True
    assert (d / "keys.json").exists()
    raw = json.loads((d / "keys.json").read_text())
    assert raw.get("cert") is not None            # enrolled
    assert fired == [1]                            # on_ready fired

def test_bootstrap_create_requires_name(tmp_path):
    c = TestClient(build_bootstrap_app(tmp_path / "n", lambda: None))
    assert c.post("/api/bootstrap/create", json={"name": "  "}).status_code == 400

def test_bootstrap_pair_request(tmp_path):
    c = TestClient(build_bootstrap_app(tmp_path / "n", lambda: None))
    req = c.post("/api/bootstrap/pair-request", json={"device": "phone"}).json()["request"]
    assert isinstance(req, str) and len(req) > 0

def test_full_app_bootstrap_status(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-pc")
    c = TestClient(build_app(node))
    b = c.get("/api/bootstrap").json()
    assert b["initialized"] is True and b["onboarding_done"] is False
    assert c.post("/api/onboarding-done").status_code == 200
    assert c.get("/api/bootstrap").json()["onboarding_done"] is True
```

- [ ] **Step 3: Run — expect failure.**

- [ ] **Step 4: `hearth/bootstrap.py`:**

```python
"""Node-less first-run server: serves the web client + create/pair endpoints
until an identity is enrolled, then run_serve hands off to the full node app."""
from pathlib import Path
from fastapi import FastAPI, Body, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from .api import WEB_DIR
from .node import HearthNode

def build_bootstrap_app(data_dir, on_ready) -> FastAPI:
    data_dir = Path(data_dir)
    app = FastAPI(title="Hearth bootstrap")
    app.mount("/static", StaticFiles(directory=WEB_DIR), name="static")

    @app.get("/")
    async def index():
        return FileResponse(WEB_DIR / "index.html")

    @app.get("/api/bootstrap")
    async def status():
        return {"initialized": False}

    @app.post("/api/bootstrap/create")
    async def create(body: dict = Body(...)):
        name = (body.get("name") or "").strip()
        device = (body.get("device") or "this-device").strip() or "this-device"
        if not name:
            raise HTTPException(400, "name required")
        node = HearthNode.create(data_dir, name, device)
        node.close()                     # release hearth.db before run_node re-opens it
        on_ready()
        return {"ok": True}

    @app.post("/api/bootstrap/pair-request")
    async def pair_request(body: dict = Body(...)):
        device = (body.get("device") or "this-device").strip() or "this-device"
        return {"request": HearthNode.pair_request(data_dir, device)}

    @app.post("/api/bootstrap/pair-install")
    async def pair_install(body: dict = Body(...)):
        package = body.get("package") or ""
        try:
            node = HearthNode.pair_install(data_dir, package)
        except Exception as e:
            raise HTTPException(400, str(e))
        node.store.set_meta("onboarding_done", "1")   # a paired device is already set up
        node.close()
        on_ready()
        return {"ok": True}

    return app
```

- [ ] **Step 5: `runner.py` — `run_serve` (two-phase):**

```python
import json
from pathlib import Path

def _enrolled(data_dir) -> bool:
    kp = Path(data_dir) / "keys.json"
    if not kp.exists():
        return False
    try:
        return json.loads(kp.read_text()).get("cert") is not None
    except Exception:
        return False

async def run_serve(data_dir, gossip_port: int, http_port: int,
                    interval: float = 3.0, tor: bool = False):
    if not _enrolled(data_dir):
        from .bootstrap import build_bootstrap_app
        ready = asyncio.Event()
        app = build_bootstrap_app(data_dir, ready.set)
        server = uvicorn.Server(uvicorn.Config(
            app, host="127.0.0.1", port=http_port, log_level="warning"))
        task = asyncio.create_task(server.serve())
        await ready.wait()               # a create or pair-install completed
        server.should_exit = True
        await task                        # graceful shutdown frees the port
    await run_node(data_dir, gossip_port, http_port, interval, tor=tor)
```

- [ ] **Step 6: `api.py` — `GET /api/bootstrap`, `POST /api/onboarding-done`, allowlist.** Add `"/api/bootstrap"` to `_APPLOCK_ALLOWLIST`. Add routes:

```python
    @app.get("/api/bootstrap")
    async def bootstrap_status():
        return {"initialized": True,
                "onboarding_done": node.store.get_meta("onboarding_done") == "1"}

    @app.post("/api/onboarding-done")
    async def onboarding_done():
        node.store.set_meta("onboarding_done", "1")
        return {"ok": True}
```

- [ ] **Step 7: `cli.py` — `serve` command:**

```python
    sp = sub.add_parser("serve", help="run one node, bootstrapping first-run if no identity yet")
    sp.add_argument("--dir", required=True)
    sp.add_argument("--http-port", type=int, required=True)
    sp.add_argument("--gossip-port", type=int, default=0)
    sp.add_argument("--interval", type=float, default=3.0)
    sp.add_argument("--tor", action="store_true")
```
```python
    elif args.cmd == "serve":
        from .runner import run_serve
        asyncio.run(run_serve(args.dir, args.gossip_port, args.http_port,
                              args.interval, tor=args.tor))
```
(If `gossip_port=0` isn't already handled by `sync.start`, pass a sensible default like `http_port + 1000`; check `run_node`/`sync.start` and match existing behavior.)

- [ ] **Step 8: Run tests + full suite. Commit.**

```powershell
git add hearth/bootstrap.py hearth/runner.py hearth/cli.py hearth/api.py hearth/node.py tests/test_bootstrap.py
git commit -m "feat: node bootstrap app + two-phase 'hearth serve' (first-run create/pair -> full node), /api/bootstrap + onboarding-done"
```

---

### Task 2: Client first-run screen + create + connect + transition

**Files:**
- Modify: `hearth/web/app.js` (boot branch + `renderFirstRun`), `hearth/web/index.html` (`#first-run`), `hearth/web/style.css`
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: `GET /api/bootstrap`, `POST /api/bootstrap/create`, `/pair-request`, `/pair-install`, `GET /api/state`.
- Produces: `renderFirstRun()`; a boot branch that shows first-run when `!initialized`.

- [ ] **Step 1: Failing asset test** — append to `tests/test_web_assets.py`:

```python
def test_first_run_onboarding():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert 'id="first-run"' in html
    assert "renderFirstRun" in js
    assert "/api/bootstrap/create" in js and "/api/bootstrap/pair-request" in js
    assert "/api/bootstrap/pair-install" in js
```

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: `index.html` — `#first-run` shell** (hidden by default; the logo + two option cards + sub-panels for create/connect). Reuse the app's logo markup/mark. Keep it minimal; the JS builds the interactive bits.

- [ ] **Step 4: `app.js` — boot branch.** At the TOP of `boot()` (before `getApplockStatus`):

```javascript
  let b;
  try { b = await (await fetch("/api/bootstrap")).json(); } catch (e) { b = {initialized: true}; }
  if (!b.initialized) { renderFirstRun(); return; }
  const status = await getApplockStatus();
  if (status.locked) { renderLockScreen(status); return; }
  if (!b.onboarding_done) NEEDS_WIZARD = true;   // module flag consumed by bootData (Task 3)
  await bootData();
```

- [ ] **Step 5: `app.js` — `renderFirstRun()`** shows `#first-run` (hide `#app`/tabbar like the lock screen does), with **Create New Node** and **Connect to Existing Node**:
  - **Create:** a name input → `POST /api/bootstrap/create {name, device: "desktop"}` → on ok, show "Setting up…" and poll `GET /api/state` every ~500ms (it errors/refuses during the bootstrap→full handoff, then 200s) → on 200, `location.reload()` (simplest: the reloaded page boots the full app, sees `onboarding_done:false`, shows the wizard).
  - **Connect:** `POST /api/bootstrap/pair-request {device}` → show the returned request string in a copyable field + instructions ("On your other device: Settings → add device, paste this, paste the result back below") → a textarea for the returned package → `POST /api/bootstrap/pair-install {package}` (400 → show the error) → poll `/api/state` → reload.
  - Keyboard-accessible; Enter submits the focused form.

- [ ] **Step 6: `style.css`** — first-run layout (centered logo + cards), matching Kreds tokens. (Animation is Task 3.)

- [ ] **Step 7: Run asset tests + `node --check` + full suite. Commit.**

```powershell
git add hearth/web/app.js hearth/web/index.html hearth/web/style.css tests/test_web_assets.py
git commit -m "feat: desktop first-run screen - Create New Node / Connect to Existing (copy-paste pairing) + bootstrap->full transition"
```

---

### Task 3: One-time wizard + logo animation + lock-screen restyle

**Files:**
- Modify: `hearth/web/app.js` (`NEEDS_WIZARD` handling + `renderOnboardingWizard`), `hearth/web/index.html`, `hearth/web/style.css`
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: `NEEDS_WIZARD` (Task 2), `/api/applock/setup`, `POST /api/onboarding-done`.

- [ ] **Step 1: Failing asset test:**

```python
def test_onboarding_wizard_and_logo_anim():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert "renderOnboardingWizard" in js and "/api/onboarding-done" in js
    assert "Skip" in js                                   # app-lock is skippable
    assert "prefers-reduced-motion" in css                # animation honors reduced motion
    assert "@keyframes" in css                            # logo breathing/rotation
```

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: `renderOnboardingWizard()`** — in `bootData()` (or right after it), if `NEEDS_WIZARD`, render a one-time modal/overlay wizard BEFORE the normal app:
  - **Step A — App-lock:** "Protect this device with a PIN or passphrase" + a **Skip** button. Reuse the existing App-lock setup control/flow (`/api/applock/setup`); Skip advances.
  - **Step B — Phone/iOS card:** the honest informational note — "Kreds for iPhone is in development. You'll pair your phone to this node when it ships; you can pair any device anytime from Settings." No action button beyond **Continue**.
  - **Done** → `POST /api/onboarding-done` → dismiss → normal app. Set `NEEDS_WIZARD = false`. Keyboard-accessible.

- [ ] **Step 4: Logo animation** — CSS `@keyframes` for a subtle **breathing** (scale/opacity pulse) and/or slow **rotation** on the circular logo (first-run + optionally the lock screen), wrapped in `@media (prefers-reduced-motion: no-preference)` (or a `@media (prefers-reduced-motion: reduce)` that disables it). Subtle hover/active transition on the first-run option cards.

- [ ] **Step 5: Restyle the App-lock lock screen** (`renderLockScreen`) to share the first-run look — same logo treatment + background — KEEPING the existing PIN keypad + passphrase field intact (structure unchanged, styling aligned).

- [ ] **Step 6: Run asset tests + `node --check` + full suite. Commit.**

```powershell
git add hearth/web/app.js hearth/web/index.html hearth/web/style.css tests/test_web_assets.py
git commit -m "feat: one-time onboarding wizard (skippable app-lock + iOS card) + logo breathing/rotation animation + lock-screen restyle"
```

---

### Task 4: Integration + docs

**Files:**
- Test: `tests/test_onboarding_integration.py`
- Modify: `README.md`, `ROADMAP.md`

- [ ] **Step 1: Two-phase serve integration test** — start `run_serve` on a fresh dir on a free port (background task); assert `GET /api/bootstrap` → `initialized:false`; `POST /api/bootstrap/create` → `ok`; then poll until `GET /api/bootstrap` → `initialized:true` (the handoff to the full app completed on the same port); assert a full-app route works and `onboarding_done:false`; `POST /api/onboarding-done` → `bootstrap.onboarding_done:true`. Use httpx against the real running server; generous timeout; tear down cleanly (no hang). (Mirror how existing tests run a live server if any; else `uvicorn`/`run_serve` in a task + `httpx.AsyncClient`.)

- [ ] **Step 2: Full suite + `node --check`** — all pass (run twice). Demo (`run_node`) + all existing paths unaffected.

- [ ] **Step 3: README + ROADMAP** — document `hearth serve` + the first-run flow (Create/Connect, one-time wizard with skippable App-lock + the honest iOS note, logo animation, restyled lock screen). State clearly that **the frameless custom window chrome + PyInstaller packaging + bundling tor.exe are the NEXT step (the pywebview Windows app)**, and PWA is dropped in favor of the iOS app. Increment/added feature entry.

- [ ] **Step 4: Commit**

```powershell
git add tests/test_onboarding_integration.py README.md ROADMAP.md
git commit -m "test+docs: desktop onboarding integration (two-phase serve) + ship notes; frameless chrome/packaging = next step"
```

---

## Completion

After Task 4: whole-branch review (superpowers:requesting-code-review) — focus: the two-phase `serve` hands off cleanly (bootstrap uvicorn fully stops + frees the port before `run_node`; the created/paired node's `hearth.db` is closed before re-open — no Windows file-lock deadlock); "initialized" = enrolled (an unenrolled `keys.json` from a mid-pairing `pair_request` still shows first-run, doesn't falsely boot the full app); `GET /api/bootstrap` allowlisted so a locked node answers it; the client boot order (bootstrap → lock → wizard → normal) has no gap where content shows before the gate; the wizard shows exactly once (onboarding_done meta) and App-lock is genuinely skippable; a paired device skips the wizard; reduced-motion honored; the demo + App-lock + all existing paths unbroken. Then superpowers:finishing-a-development-branch — merge to `main`, push. Client first-run/wizard/animation BEHAVIOR is the USER's to verify (hand a checklist on merge). Next: the pywebview Windows app (frameless chrome + packaging), then the video trimmer.
