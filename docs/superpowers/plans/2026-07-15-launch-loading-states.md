# Launch Loading States Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The window opens immediately with live startup progress (Tor bootstrap percent included); timeouts exceed Tor's own retry budget; Tor stops gracefully; the onboarding poll never dead-ends.

**Architecture:** A `status` callback threads from `desktop._start_node` through `runner.run_serve`/`run_node` into `TorProcess.start`, writing `{stage, pct}` into the existing `holder` dict. `launch()` creates the webview window right after the node thread starts, pointed at inline loading HTML that polls `pywebview.api.get_startup_status()`; a watcher thread navigates to the app on readiness or renders failure in place. Tor gains `SIGNAL SHUTDOWN` before `terminate()`.

**Tech Stack:** Python 3.12, asyncio, pywebview/WebView2, pytest.

**Spec:** `docs/superpowers/specs/2026-07-15-launch-loading-states-design.md` (approved).

## Global Constraints

- Suite green: `.venv\Scripts\python.exe -m pytest -q` (baseline at dispatch time — see task context; UI_E2E smokes stay gated/skipped).
- NO AI/Co-Authored-By commit trailers; ASCII-only console prints (cp1252).
- `hearth serve` CLI and every existing test must run unchanged with the default no-op status callback.
- Stage names are a contract, exactly: `starting`, `tor-binary`, `tor-bootstrap`, `onion-publish`, `serving`, `ready`, `failed`.
- Timeouts: outer ready-wait 240.0 (tor) / 25.0 (non-tor); Tor inter-attempt retry sleep 5.0.

---

### Task 1: Tor bootstrap progress + graceful stop (`hearth/tor.py`)

**Files:**
- Modify: `hearth/tor.py:136-208` (`TorProcess.start`, `TorProcess.stop`)
- Test: `tests/test_tor_process.py` (append; if that file doesn't exist, grep `tests/` for the file that unit-tests `TorProcess`/`tor.py` and append there)

**Interfaces:**
- Produces: `TorProcess.start(bootstrap_timeout: float = 90.0, status=None)` — `status(pct: int)` called for each `Bootstrapped NN%` line seen (including 100). `TorProcess.stop()` now: cancel drain → try `SIGNAL SHUTDOWN` via control port with 5 s grace → `terminate()` fallback → `kill()` fallback (existing). Retry sleep 1.5 → 5.0.

- [ ] **Step 1: Write the failing tests**

```python
import re

def test_bootstrap_percent_reported(...):
    # Feed synthetic tor stdout through _await_bootstrap's parsing by
    # stubbing self._proc.stdout.readline with a scripted sequence:
    lines = [b"May 1 [notice] Bootstrapped 10% (conn): Connecting\n",
             b"May 1 [notice] Bootstrapped 45% (loading): Loading\n",
             b"May 1 [notice] Bootstrapped 100% (done): Done\n"]
    seen = []
    # build a TorProcess with a fake proc whose stdout.readline pops `lines`
    # (follow this test file's existing fake-proc conventions if present;
    # otherwise a minimal object with an async readline works)
    await tp.start(bootstrap_timeout=5.0, status=seen.append)  # via the fake spawn
    assert seen == [10, 45, 100]


async def test_stop_sends_shutdown_signal_before_terminate(monkeypatch):
    tp = TorProcess(Path("tor.exe"), tmp_path)
    calls = []
    async def fake_control(port, cookie, command):
        calls.append(command); return []
    monkeypatch.setattr("hearth.tor._control_command", fake_control)
    tp._proc = FakeProc(exits_after_signal=True)   # .wait() returns once signalled
    await tp.stop()
    assert calls == ["SIGNAL SHUTDOWN"]
    assert not tp._proc.terminated                 # graceful path won; no hard kill


async def test_stop_falls_back_to_terminate_when_signal_ignored(monkeypatch):
    tp = TorProcess(Path("tor.exe"), tmp_path)
    async def fake_control(port, cookie, command):
        return []
    monkeypatch.setattr("hearth.tor._control_command", fake_control)
    tp._proc = FakeProc(exits_after_signal=False)  # ignores the signal
    await tp.stop()
    assert tp._proc.terminated                     # fallback fired
```

`FakeProc` is a small local helper: `returncode` None until "exited"; `wait()` an async fn honoring `exits_after_signal` (hang past the 5 s grace — use a short patched grace constant, see Step 3); `terminate()` sets a flag and unblocks `wait()`. Spawn faking for the first test: monkeypatch `asyncio.create_subprocess_exec` to return the fake proc. Follow existing conventions in the tor test file if any exist.

- [ ] **Step 2: Run to verify they fail** (`status` param unknown / no SIGNAL SHUTDOWN sent).

- [ ] **Step 3: Implement**

In `TorProcess.start` — signature `async def start(self, bootstrap_timeout: float = 90.0, status=None):` and `_await_bootstrap` becomes:

```python
        _BOOTSTRAP_RE = re.compile(rb"Bootstrapped (\d+)%")
        async def _await_bootstrap():
            while True:
                raw = await self._proc.stdout.readline()
                if not raw:
                    raise RuntimeError("tor exited before bootstrap")
                m = _BOOTSTRAP_RE.search(raw)
                if m:
                    pct = int(m.group(1))
                    if status is not None:
                        try:
                            status(pct)
                        except Exception:
                            pass       # progress display must never kill tor startup
                    if pct >= 100:
                        return
```

(`import re` at top of file.) Retry sleep at tor.py:180: `await asyncio.sleep(5.0)  # let the prior tor's ports actually free (was 1.5s: too tight after an update restart)`.

`TorProcess.stop` — graceful first:

```python
    _SHUTDOWN_GRACE = 5.0

    async def stop(self):
        if self._drain_task is not None:
            self._drain_task.cancel()
        if self._proc is None:
            return
        # Graceful first: a hard TerminateProcess can leave tor's consensus
        # cache unclean, forcing the NEXT start into a cold bootstrap (the
        # post-update always-fails pattern, 0.3.11). SIGNAL SHUTDOWN lets
        # tor flush state; fall back to the old hard path if it's ignored.
        try:
            await _control_command(self.control_port, self.cookie_path,
                                   "SIGNAL SHUTDOWN")
            await asyncio.wait_for(self._proc.wait(),
                                   timeout=self._SHUTDOWN_GRACE)
            self._proc = None
            return
        except Exception:
            pass                        # control conn dead / ignored: hard path
        try:
            self._proc.terminate()
        except ProcessLookupError:
            pass
        try:
            await asyncio.wait_for(self._proc.wait(), timeout=10.0)
        except asyncio.TimeoutError:
            self._proc.kill()
            await self._proc.wait()
        self._proc = None
```

Tests may patch `TorProcess._SHUTDOWN_GRACE` small.

- [ ] **Step 4: Run tor tests, then full suite.** Any existing test calling `stop()` with a fake proc may need the fake to tolerate the control-command attempt (it raises → falls through to terminate: old behavior preserved). Fix fakes, not production code.

- [ ] **Step 5: Commit**

```bash
git add hearth/tor.py tests/
git commit -m "feat(tor): bootstrap percent surfaced via status callback; graceful SIGNAL SHUTDOWN before terminate (unclean-cache cold-bootstrap fix); retry gap 5s"
```

---

### Task 2: Status threading through `hearth/runner.py`

**Files:**
- Modify: `hearth/runner.py:17-53` (`run_node`), `runner.py:96-155` (`run_serve`)
- Test: `tests/test_runner_status.py` (create)

**Interfaces:**
- Consumes: `TorProcess.start(status=...)` from Task 1.
- Produces: `run_node(..., status=None)` and `run_serve(..., status=None)` where `status(stage: str, pct: int | None = None)`. Stage sequence (tor path): `starting` → `tor-binary` → `tor-bootstrap` (with pct per callback) → `onion-publish` → `serving` → `ready`. Non-tor: `starting` → `serving` → `ready`. `run_serve` forwards `status` to `run_node`; the bootstrap (onboarding) phase emits `starting` before serving the bootstrap app and re-emits the full sequence when handing off to `run_node`. `ready` fires right before `server.serve()` is awaited (the port binds inside serve; emit `serving` before constructing the server, `ready` immediately before `await server.serve()`).

- [ ] **Step 1: Write the failing test**

```python
import asyncio
from hearth import runner

async def test_non_tor_status_sequence(tmp_path):
    events = []
    def status(stage, pct=None):
        events.append(stage)
    shutdown = asyncio.Event()
    task = asyncio.create_task(runner.run_node(
        tmp_path, gossip_port=0, http_port=free_port(), tor=False,
        shutdown=shutdown, status=status))
    # wait until "ready" appears or timeout
    for _ in range(200):
        if "ready" in events:
            break
        await asyncio.sleep(0.05)
    shutdown.set()
    await asyncio.wait_for(task, timeout=10)
    assert events[0] == "starting"
    assert events.index("serving") < events.index("ready")
```

(`free_port` helper: copy the socket-bind pattern from `hearth/desktop.py:_free_port` or an existing test. Node construction needs an enrolled identity — copy however existing runner/serve tests enroll one; if none exist, enroll via `HearthNode` test helpers used in `tests/test_node.py`.) Tor-path sequence test: monkeypatch `runner.ensure_tor_binary` and `runner.TorProcess` with fakes (fake `start(status=...)` calls `status(50)` then `status(100)`; fake `publish_onion`) and assert the full sequence order including a `tor-bootstrap` entry.

- [ ] **Step 2: Run to verify it fails** (unexpected keyword `status`).

- [ ] **Step 3: Implement**

`run_node`: add `status=None` param; normalize `status = status or (lambda stage, pct=None: None)`. Emissions:

```python
    status("starting")
    ...
    if tor:
        if tor_process is None:
            status("tor-binary")
            exe = ensure_tor_binary()
            own_tor = TorProcess(exe, node.data_dir / "tordata")
            status("tor-bootstrap", 0)
            await own_tor.start(
                status=lambda pct: status("tor-bootstrap", pct))
            tor_process = own_tor
        ...
        if publish:
            status("onion-publish")
            ...
    ...
    status("serving")
    server = uvicorn.Server(...)
    ...
    status("ready")
    await server.serve()
```

(`ready` goes immediately before `await server.serve()` inside the existing try block — the outer `_await_node_ready` HTTP poll remains the authoritative readiness check; this stage is display-only.) `run_serve`: add `status=None`, emit `status("starting")` before the bootstrap-app phase, pass `status=status` to `run_node`.

- [ ] **Step 4: Run new tests + full suite** (existing callers pass no `status`; default no-op keeps them green).

- [ ] **Step 5: Commit**

```bash
git add hearth/runner.py tests/test_runner_status.py
git commit -m "feat(runner): startup stage callback threads through run_serve/run_node - display-only, no-op by default"
```

---

### Task 3: Window-first launch (`hearth/desktop.py`)

**Files:**
- Modify: `hearth/desktop.py` — `_start_node` (327-360), `_await_node_ready` (362-373), `launch()` (404-464), `Api` (78+), `_notify_already_running` (251-261), new `_LOADING_HTML` + `_watch_ready`
- Test: `tests/test_desktop_status.py` (create; logic-level tests only — no real webview)

**Interfaces:**
- Consumes: `run_serve(..., status=...)` from Task 2.
- Produces: `holder["startup"] = {"stage": str, "pct": int|None}` maintained by the node thread; `Api.get_startup_status()` returns it (plus `{"stage": "failed", "error": ...}` when `holder["error"]` is set); window created immediately, navigated by a watcher thread on readiness.

- [ ] **Step 1: Write the failing tests**

```python
def test_api_get_startup_status_reads_holder():
    holder = {"startup": {"stage": "tor-bootstrap", "pct": 45}}
    api = desktop.Api(holder)
    assert api.get_startup_status() == {"stage": "tor-bootstrap", "pct": 45}

def test_api_get_startup_status_failure_state():
    holder = {"startup": {"stage": "tor-bootstrap", "pct": 45},
              "error": "RuntimeError: tor failed"}
    api = desktop.Api(holder)
    s = api.get_startup_status()
    assert s["stage"] == "failed" and "tor failed" in s["error"]

def test_ready_wait_timeout_constants():
    # conscious-edit pin per spec
    assert desktop.READY_TIMEOUT_TOR == 240.0
    assert desktop.READY_TIMEOUT_PLAIN == 25.0
```

- [ ] **Step 2: Run to verify they fail.**

- [ ] **Step 3: Implement**

1. Module constants `READY_TIMEOUT_TOR = 240.0` (comment: must exceed tor.py's 2x90s+5s retry budget + AV-scan overhead on fresh binaries; was 120 — the post-update always-fails bug) and `READY_TIMEOUT_PLAIN = 25.0`; `launch()` uses them.
2. `_start_node`: initialize `holder["startup"] = {"stage": "starting", "pct": None}` and build `def _status(stage, pct=None): holder["startup"] = {"stage": stage, "pct": pct}`; pass `status=_status` into `run_serve`.
3. `Api.get_startup_status(self)`: returns `dict(self._holder.get("startup") or {"stage": "starting", "pct": None})`, overridden to `{"stage": "failed", "error": str(holder["error"])}` when `"error"` in holder; include the app.log pointer text in the error payload.
4. `_LOADING_HTML`: inline page (same technique as `_show_error_window`) — Kreds name, a stage line, a simple progress bar, ~500 ms `setInterval` polling `window.pywebview.api.get_startup_status()` (guard for the bridge not being injected yet: retry until `window.pywebview` exists). Stage → copy mapping lives in the page JS: `starting/tor-binary` → "Starting Kreds...", `tor-bootstrap` → "Connecting to Tor - NN%", `onion-publish` → "Publishing your address...", `serving/ready` → "Almost there...", `failed` → show error text + "See app.log in the Kreds data folder." ASCII only.
5. `launch()` restructure: after `_start_node`, immediately `window = webview.create_window("Kreds", html=_LOADING_HTML, js_api=api, frameless=True, ...same size args...)`; start a `threading.Thread(target=_watch_ready, args=(...), daemon=True)`; then tray setup as today; then `webview.start(...)` (blocks). `_watch_ready(t, holder, port, window, use_tor)`: runs the existing `_await_node_ready` logic with the new timeout; on success `window.load_url(f"http://127.0.0.1:{port}")`; on failure log via `_log_error` exactly as `_handle_start_failure` does today and set `holder["error"]` if unset (the loading page's own polling then renders the failed state — no separate error window). `_show_error_window` stays for any other caller; `_handle_start_failure`'s log-format behavior is preserved inside `_watch_ready` (reuse the function's reason strings verbatim).
6. `_notify_already_running` copy: "Kreds is already running or still starting up. If it just launched, give it a moment - the window appears when it is ready. Otherwise check the system tray." (final wording flagged for August in review).
7. Window close during loading: webview.start() returning while the watcher still runs — `launch()`'s existing `finally`/shutdown path already signals the node thread; the watcher thread is daemon and exits with the process. Verify `window.load_url` after window destruction raises harmlessly — wrap in try/except with a comment.

- [ ] **Step 4: Run new tests + full suite.** Manual smoke is August's (real install); note it in the report as NOT verified here.

- [ ] **Step 5: Commit**

```bash
git add hearth/desktop.py tests/test_desktop_status.py
git commit -m "feat(desktop): window-first launch - loading page with live tor progress via pywebview api; ready-wait 240s (> tor's own retry budget); already-running copy covers the bootstrap window"
```

---

### Task 4: Onboarding poll never dead-ends (`hearth/web/app.js`)

**Files:**
- Modify: `hearth/web/app.js:2333-2349` (`pollForFullApp` + its comment)
- Test: `tests/test_web_assets.py` (append content pins, following that file's existing asset-assertion conventions)

**Interfaces:**
- Consumes: `Api.get_startup_status()` (Task 3) — reachable from the onboarding page as `window.pywebview?.api?.get_startup_status?.()`; absent in a plain browser (dev), so every use is optional-chained with a text-only fallback.

- [ ] **Step 1: Write the failing content-pin test**

```python
def test_onboarding_poll_never_gives_up():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "You can leave this page open" not in js       # the dead-end lie
    assert "while (true)" in js.split("async function pollForFullApp")[1][:2000]
```

(Adapt the second assertion to however the loop lands; keep it a content pin in this file's existing style.)

- [ ] **Step 2: Run to verify it fails.**

- [ ] **Step 3: Implement** — replace `pollForFullApp`:

```javascript
// The bootstrap->full-app handoff closes the bootstrap uvicorn and starts
// the full node app on the SAME port (see runner.run_serve). The full app
// only binds after Tor bootstrap + onion publish, which can take minutes
// cold - so poll FOREVER with backoff (500ms -> 2s) and surface the
// desktop shell's startup stage when available (pywebview api; absent in
// a plain browser, where the text fallback carries it).
async function pollForFullApp(statusEl) {
  let delay = 500;
  while (true) {
    try {
      const r = await fetch("/api/state");
      if (r.ok) { location.reload(); return; }
    } catch (e) { /* mid-handoff / tor still bootstrapping - keep polling */ }
    if (statusEl) {
      let msg = "Starting your node - this can take a few minutes...";
      try {
        const s = await window.pywebview?.api?.get_startup_status?.();
        if (s && s.stage === "tor-bootstrap" && s.pct != null)
          msg = "Connecting to Tor - " + s.pct + "%";
        else if (s && s.stage === "failed")
          msg = "Startup hit a problem - see app.log in the Kreds data folder.";
      } catch (e) { /* bridge absent (plain browser): keep the fallback text */ }
      statusEl.textContent = msg;
    }
    await new Promise(res => setTimeout(res, delay));
    delay = Math.min(delay * 1.5, 2000);
  }
}
```

- [ ] **Step 4: Run test_web_assets + full suite.**

- [ ] **Step 5: Commit**

```bash
git add hearth/web/app.js tests/test_web_assets.py
git commit -m "fix(web): onboarding poll never gives up - backoff forever, live tor stage when the desktop bridge exists; the false 'leave this page open' dead-end dies"
```

---

## Self-Review Notes (planning time)

- Spec coverage: T1 progress+graceful stop+retry gap; T2 stage threading; T3 window-first+timeouts+copy; T4 poll. Launcher rollback blindness and HTTP-before-Tor stay out (spec: roadmap/tech-debt).
- Stage names identical across T2 producer, T3 holder/Api, T4 consumer.
- T3 is the judgment-heavy task (threading + webview lifecycle); its Step 3 enumerates the exact seams; the implementer must flag DONE_WITH_CONCERNS if webview thread-safety of `load_url` can't be confirmed from pywebview docs/source in the venv.
