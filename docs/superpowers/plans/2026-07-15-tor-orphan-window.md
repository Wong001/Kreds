# Tor Orphan Window Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** First launch after an update (or after any unclean death of the previous instance) never fails on the ~15s tor orphan window — the exiting process drains its tor stop fully (A), and a fresh launch waits out any orphan with an honest loading message (C).

**Architecture:** Two independent sides. Exit side: `launch()`'s node-thread join cap rises 8s → 30s (`SHUTDOWN_DRAIN_TIMEOUT`), out-waiting `TorProcess.stop()`'s own ~16s internal worst case, with the restart lock-wait raised in lockstep (`RESTART_LOCK_WAIT = 30.0`). Launch side: `TorProcess.start()` splits its two failure kinds — bootstrap *timeouts* keep the 2-attempt budget, spawn-*exits* (the orphan signature) retry every 5s for up to 30s, signaling a new optional `waiting` callback that runner surfaces as a new display-only stage `tor-waiting`.

**Tech Stack:** Python 3.12, asyncio, pytest; existing status-stage contract (desktop loading page + app.js).

**Spec:** `docs/superpowers/specs/2026-07-15-tor-orphan-window-design.md` (approved).

## Global Constraints

- Work on branch `kreds-fixes-0.3.12` (create from main at start; never commit to main directly).
- Suite green before every commit: `.venv\Scripts\python.exe -m pytest -q` (baseline at dispatch: 870 passed, 6 skipped).
- NO AI/Co-Authored-By commit trailers; ASCII-only console prints (cp1252).
- Exact values: `SPAWN_RETRY_GAP = 5.0`, `SPAWN_RETRY_WINDOW = 30.0` (class attrs `_SPAWN_RETRY_GAP`/`_SPAWN_RETRY_WINDOW`, patchable like the existing `_SHUTDOWN_GRACE`), `SHUTDOWN_DRAIN_TIMEOUT = 30.0`, `RESTART_LOCK_WAIT = 30.0`. Budget relationship pinned by test: `30 + 2*90 + 5 < 240` (`READY_TIMEOUT_TOR`).
- Stage contract gains exactly one name: `tor-waiting`. Draft copy (August words final): `"Waiting for a previous Kreds to finish closing..."` — used verbatim in `_LOADING_HTML` and `app.js`.
- Bootstrap-timeout behavior is UNCHANGED: exactly 2 attempts of `bootstrap_timeout` each.
- `hearth serve` CLI passes no `waiting` callback; default `None` must keep it working unchanged.

## Verified codebase facts (do not re-derive)

- `tor.py:167-190` is the current flat 2-attempt loop; both failure kinds funnel through `except (RuntimeError, asyncio.TimeoutError)`. Spawn-exit surfaces as `RuntimeError("tor exited before bootstrap")` from `_await_bootstrap` (tor.py:155).
- `tor.py` has no `import time` today — add it.
- `desktop.py:589` is `t.join(timeout=8)`; `desktop.py:539-540` computes `_restart_wait = 15.0 ...` for `acquire_single_instance`.
- `_log_error(data_dir, message)` appends to `data_dir/app.log` (desktop.py).
- Loading-page stage copy lives in `_LOADING_HTML`'s `COPY` js dict (desktop.py); non-`tor-bootstrap` stages use the pulsing bar automatically.
- `app.js` `pollForFullApp` maps stages inside its poll loop (`tor-bootstrap` and `failed` branches exist).
- Test conventions: `tests/test_tor_no_window.py` fakes procs (classes with async `readline`/`wait`, `terminate`) and monkeypatches `tor.asyncio.create_subprocess_exec`; tests are sync defs wrapping `asyncio.run(scenario())`. `tests/test_runner_status.py` has `_FakeTorProcess` + stage-sequence tests. `tests/test_desktop_status.py` pins constants and `_LOADING_HTML` content. `tests/test_web_assets.py` content-pins app.js via `_js_fn_body`.
- No existing test asserts the exact "after 2 attempts" error string (safe to parameterize the count).

---

### Task 1: Spawn-exit retry window + `waiting` callback (`hearth/tor.py`)

**Files:**
- Modify: `hearth/tor.py` — `TorProcess.start` (137-190), class attrs near `_SHUTDOWN_GRACE` (204), add `import time` to the imports block
- Test: `tests/test_tor_no_window.py` (append)

**Interfaces:**
- Produces: `TorProcess.start(bootstrap_timeout: float = 90.0, status=None, waiting=None)` — `waiting()` called (no args, exceptions swallowed) each time a spawn-exit retry is scheduled. Class attrs `_SPAWN_RETRY_GAP = 5.0`, `_SPAWN_RETRY_WINDOW = 30.0`. Timeout failures: unchanged 2-attempt budget. Error message on giving up (either kind): `f"tor failed to bootstrap after {attempts} attempts: {last_err}"` with the real attempt count.

- [ ] **Step 1: Write the failing tests** (append; reuse this file's `_FakeProc`/`_FailThenEofProc`/`_PctFakeProc` idioms)

```python
class _ExitNTimesThenGood:
    """Factory state for create_subprocess_exec fakes: the first `n` procs
    exit before bootstrap (stdout EOF immediately), later ones bootstrap."""
    def __init__(self, n):
        self.n = n
        self.calls = 0

    async def __call__(self, *args, **kwargs):
        self.calls += 1
        return _FailThenEofProc() if self.calls <= self.n else _FakeProc()


def test_spawn_exit_retries_past_two_attempts_within_window(monkeypatch, tmp_path):
    # The orphan case: 4 instant-exits, then success -- must NOT give up
    # at 2 attempts like the old flat loop did.
    fake = _ExitNTimesThenGood(4)
    waits = []

    async def scenario():
        monkeypatch.setattr(tor.asyncio, "create_subprocess_exec", fake)
        tp = tor.TorProcess(exe="tor", data_dir=tmp_path)
        tp._SPAWN_RETRY_GAP = 0.01          # fast test; window stays ample
        await asyncio.wait_for(
            tp.start(bootstrap_timeout=5, waiting=waits.append), timeout=8)

    asyncio.run(scenario())
    assert fake.calls == 5                  # 4 failures + the success
    assert len(waits) == 4                  # waiting() per scheduled retry
    # waits entries are None (waiting takes no args); presence is the signal


def test_spawn_exit_gives_up_after_window(monkeypatch, tmp_path):
    fake = _ExitNTimesThenGood(10 ** 6)     # never succeeds

    async def scenario():
        monkeypatch.setattr(tor.asyncio, "create_subprocess_exec", fake)
        tp = tor.TorProcess(exe="tor", data_dir=tmp_path)
        tp._SPAWN_RETRY_GAP = 0.01
        tp._SPAWN_RETRY_WINDOW = 0.05
        await tp.start(bootstrap_timeout=5)

    import pytest
    with pytest.raises(RuntimeError, match="tor failed to bootstrap"):
        asyncio.run(scenario())
    assert fake.calls >= 2                  # it did keep retrying first


def test_waiting_callback_exception_never_kills_startup(monkeypatch, tmp_path):
    fake = _ExitNTimesThenGood(1)

    def bad_waiting():
        raise ValueError("boom")

    async def scenario():
        monkeypatch.setattr(tor.asyncio, "create_subprocess_exec", fake)
        tp = tor.TorProcess(exe="tor", data_dir=tmp_path)
        tp._SPAWN_RETRY_GAP = 0.01
        await asyncio.wait_for(
            tp.start(bootstrap_timeout=5, waiting=bad_waiting), timeout=8)

    asyncio.run(scenario())                 # must not raise
    assert fake.calls == 2


class _HangForeverProc:
    """Never emits a bootstrap line and never EOFs: forces the TIMEOUT
    failure kind (asyncio.TimeoutError), not the spawn-exit kind."""
    def __init__(self):
        self.stdout = self
        self.returncode = None

    async def readline(self):
        await asyncio.sleep(3600)

    def terminate(self):
        self.returncode = 1

    async def wait(self):
        return 1


def test_bootstrap_timeout_budget_still_two_attempts(monkeypatch, tmp_path):
    calls = {"n": 0}

    async def fake_exec(*args, **kwargs):
        calls["n"] += 1
        return _HangForeverProc()

    async def scenario():
        monkeypatch.setattr(tor.asyncio, "create_subprocess_exec", fake_exec)
        tp = tor.TorProcess(exe="tor", data_dir=tmp_path)
        tp._SPAWN_RETRY_GAP = 0.01
        await tp.start(bootstrap_timeout=0.05)

    import pytest
    with pytest.raises(RuntimeError, match="tor failed to bootstrap"):
        asyncio.run(scenario())
    assert calls["n"] == 2                  # timeout budget unchanged
```

(`import pytest` at module top instead of inline if the file already imports it — check first. `_FailThenEofProc` sets `returncode` on first readline; `_FakeProc` bootstraps immediately — both already in this file.)

- [ ] **Step 2: Run to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_tor_no_window.py -q`
Expected: the four new tests FAIL — `start() got an unexpected keyword argument 'waiting'` for two of them; `test_spawn_exit_retries_past_two_attempts_within_window` fails with the "after 2 attempts" RuntimeError from the old flat loop; the timeout test may PASS already (2 attempts is the old behavior — keep it anyway, it pins the budget against this change).

- [ ] **Step 3: Implement**

Add `import time` to tor.py's imports. Class attrs next to `_SHUTDOWN_GRACE`:

```python
    # Spawn-exit retry (0.3.12): a tor that EXITS instantly at spawn is the
    # signature of a prior instance's orphaned tor still holding the fixed
    # socks/control ports + tordata lock. The orphan self-reaps within ~15s
    # of its owner's death (__OwningControllerProcess polling), so retry for
    # 2x that. Bootstrap TIMEOUTS are a different failure (tor alive but
    # slow) and keep their own 2-attempt budget.
    _SPAWN_RETRY_GAP = 5.0
    _SPAWN_RETRY_WINDOW = 30.0
```

Signature: `async def start(self, bootstrap_timeout: float = 90.0, status=None, waiting=None):`

Replace the `last_err = None` / `for attempt in range(2):` loop (tor.py:167-190) with:

```python
        last_err = None
        attempts = 0
        timeout_attempts = 0
        first_spawn = time.monotonic()
        while True:
            attempts += 1
            self._proc = await asyncio.create_subprocess_exec(
                str(self.exe), "-f", str(torrc),
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.STDOUT,
                creationflags=creationflags)
            try:
                await asyncio.wait_for(_await_bootstrap(),
                                       timeout=bootstrap_timeout)
                break                                   # bootstrapped
            except (RuntimeError, asyncio.TimeoutError) as e:
                last_err = e
                try:                                    # reap the failed tor
                    if self._proc.returncode is None:
                        self._proc.terminate()
                        await asyncio.wait_for(self._proc.wait(), timeout=5.0)
                except Exception:
                    pass
                if isinstance(e, asyncio.TimeoutError):
                    # tor alive but slow: same 2-attempt budget as always
                    timeout_attempts += 1
                    if timeout_attempts >= 2:
                        raise RuntimeError(
                            f"tor failed to bootstrap after {attempts} "
                            f"attempts: {last_err}")
                else:
                    # spawn-exit: the orphan signature (see class attrs)
                    if time.monotonic() - first_spawn >= self._SPAWN_RETRY_WINDOW:
                        raise RuntimeError(
                            f"tor failed to bootstrap after {attempts} "
                            f"attempts: {last_err}")
                    if waiting is not None:
                        try:
                            waiting()
                        except Exception:
                            pass    # display must never kill tor startup
                await asyncio.sleep(self._SPAWN_RETRY_GAP)
```

(The old `# Retry the spawn+bootstrap once` comment block at tor.py:146-149 updates to describe the two-kind split — spawn-exits retry within `_SPAWN_RETRY_WINDOW`, timeouts keep 2 attempts.)

- [ ] **Step 4: Run tor tests, then the full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_tor_no_window.py -q` then `.venv\Scripts\python.exe -m pytest -q`
Expected: all pass. `test_tor_retries_once_on_bootstrap_failure` (existing, one spawn-exit then success) must still pass — the new loop retries spawn-exits at least as eagerly as the old one.

- [ ] **Step 5: Commit**

```bash
git add hearth/tor.py tests/test_tor_no_window.py
git commit -m "fix(tor): spawn-exit retries ride a 30s window (2x the orphan's ~15s self-reap) with a waiting callback; bootstrap timeouts keep the 2-attempt budget"
```

---

### Task 2: Exit drain (`hearth/desktop.py`)

**Files:**
- Modify: `hearth/desktop.py` — constants near `READY_TIMEOUT_TOR`, `launch()` (539-540 restart wait, 589 join)
- Test: `tests/test_desktop_status.py` (append)

**Interfaces:**
- Produces: `SHUTDOWN_DRAIN_TIMEOUT = 30.0`, `RESTART_LOCK_WAIT = 30.0` module constants; `launch()` joins the node thread with `SHUTDOWN_DRAIN_TIMEOUT` and logs `"shutdown drain timed out; a tor orphan may linger ~15s"` via `_log_error` if the thread is still alive after it; the `KREDS_RESTARTING` lock wait uses `RESTART_LOCK_WAIT`.

- [ ] **Step 1: Write the failing tests** (append to `tests/test_desktop_status.py`)

```python
def test_shutdown_drain_constants():
    # Exit drain (0.3.12): the join must out-wait TorProcess.stop()'s own
    # internal worst case (~16s: 5s SIGNAL SHUTDOWN grace + 10s terminate
    # wait + kill); the restart lock-wait must out-wait the drain.
    assert desktop.SHUTDOWN_DRAIN_TIMEOUT == 30.0
    assert desktop.RESTART_LOCK_WAIT == 30.0
    assert desktop.RESTART_LOCK_WAIT >= desktop.SHUTDOWN_DRAIN_TIMEOUT


def test_spawn_window_plus_bootstrap_budget_fits_ready_wait():
    # C's spawn window + the unchanged 2x bootstrap budget + one retry gap
    # must stay under the shell's ready-wait, or a worst-case-but-
    # successful startup gets declared failed mid-recovery.
    from hearth.tor import TorProcess
    worst = (TorProcess._SPAWN_RETRY_WINDOW + 2 * 90.0
             + TorProcess._SPAWN_RETRY_GAP)
    assert worst < desktop.READY_TIMEOUT_TOR


def test_drain_timeout_leaves_evidence(tmp_path, monkeypatch):
    # A node thread still alive after the drain window must log the orphan
    # warning to app.log. Drive the same code launch() runs post-join.
    class _StuckThread:
        def join(self, timeout=None): pass
        def is_alive(self): return True
    desktop._drain_node_thread(_StuckThread(), tmp_path)
    log = (tmp_path / "app.log").read_text(encoding="utf-8")
    assert "shutdown drain timed out" in log


def test_drain_clean_exit_logs_nothing(tmp_path):
    class _DoneThread:
        def join(self, timeout=None): pass
        def is_alive(self): return False
    desktop._drain_node_thread(_DoneThread(), tmp_path)
    assert not (tmp_path / "app.log").exists()
```

- [ ] **Step 2: Run to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_desktop_status.py -q`
Expected: FAIL — `module 'hearth.desktop' has no attribute 'SHUTDOWN_DRAIN_TIMEOUT'` / `'_drain_node_thread'`.

- [ ] **Step 3: Implement**

Constants, directly under `READY_TIMEOUT_PLAIN`:

```python
# Exit drain (0.3.12): quit destroys the window instantly, but the process
# must stay alive until the node thread's finally has actually stopped tor
# (TorProcess.stop() is internally bounded at ~16s worst case) -- otherwise
# the daemon thread dies mid-stop and tor is orphaned for ~15s, colliding
# with the next launch's fixed ports (the update-restart failure, 0.3.12).
# Still bounded: a wedged node thread must not turn quit into a zombie.
SHUTDOWN_DRAIN_TIMEOUT = 30.0
# A restarting instance must out-wait the exiting instance's drain.
RESTART_LOCK_WAIT = 30.0
```

New helper next to `_signal_shutdown`:

```python
def _drain_node_thread(t, data_dir: Path) -> None:
    """Bounded wait for the node thread's shutdown (sync teardown + tor
    stop). On timeout, leave evidence -- the orphaned tor will hold the
    fixed ports for ~15s and the NEXT launch's spawn-retry window is what
    absorbs it (tor.py _SPAWN_RETRY_WINDOW)."""
    t.join(timeout=SHUTDOWN_DRAIN_TIMEOUT)
    if t.is_alive():
        _log_error(data_dir,
                   "shutdown drain timed out; a tor orphan may linger ~15s")
```

In `launch()`: `_restart_wait = RESTART_LOCK_WAIT if os.environ.pop("KREDS_RESTARTING", None) else 0.0` (replaces the literal 15.0), and the post-`webview.start()` teardown becomes:

```python
        # window gone -> ensure the node is asked to stop, then drain fully
        _signal_shutdown(holder)
        _drain_node_thread(t, data_dir)
```

(replacing `t.join(timeout=8)`).

- [ ] **Step 4: Run desktop tests, then the full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_desktop_status.py tests/test_desktop.py -q` then `.venv\Scripts\python.exe -m pytest -q`
Expected: all pass (no existing test pins the 8s literal or the 15.0 restart wait — verified at planning time; if one does, that is a behavior change this plan intends, update it and say so in the report).

- [ ] **Step 5: Commit**

```bash
git add hearth/desktop.py tests/test_desktop_status.py
git commit -m "fix(desktop): quit drains the node thread for up to 30s (tor stop is ~16s worst case) instead of 8 - clean exits can no longer orphan tor; restart lock-wait rises in lockstep"
```

---

### Task 3: `tor-waiting` stage plumbing + copy + version bump

**Files:**
- Modify: `hearth/runner.py` (the `own_tor.start(...)` call), `hearth/desktop.py` (`_LOADING_HTML` COPY dict), `hearth/web/app.js` (`pollForFullApp`), `hearth/__init__.py` + `hearth/web/VERSION` (0.3.11 → 0.3.12)
- Test: `tests/test_runner_status.py` (append), `tests/test_desktop_status.py` (extend the `_LOADING_HTML` pin), `tests/test_web_assets.py` (extend the poll pin)

**Interfaces:**
- Consumes: `TorProcess.start(..., waiting=...)` from Task 1; stage-callback chain from the 0.3.11 loading-states slice.
- Produces: stage `tor-waiting` emitted between bootstrap attempts; copy string (DRAFT, August words final): `Waiting for a previous Kreds to finish closing...` in both surfaces.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_runner_status.py` (reuse its `_FakeTorProcess`; add a waiting-signaling variant):

```python
class _WaitingFakeTorProcess(_FakeTorProcess):
    async def start(self, bootstrap_timeout: float = 90.0, status=None,
                    waiting=None):
        if waiting is not None:
            waiting()                       # one spawn-exit retry happened
        await super().start(bootstrap_timeout, status)


def test_tor_waiting_stage_surfaces(tmp_path, monkeypatch):
    node_dir = tmp_path / "n"
    HearthNode.create(node_dir, "Test Person", "test-device")
    events = []

    def status(stage, pct=None):
        events.append(stage)

    async def fake_publish(control_port, cookie_path, port, key_blob):
        return "fakesvcid", None

    async def scenario():
        monkeypatch.setattr(runner, "ensure_tor_binary", lambda: "tor")
        monkeypatch.setattr(runner, "TorProcess", _WaitingFakeTorProcess)
        monkeypatch.setattr(runner, "publish_onion", fake_publish)
        shutdown = asyncio.Event()
        task = asyncio.create_task(runner.run_node(
            node_dir, gossip_port=0, http_port=_free_port(), tor=True,
            shutdown=shutdown, status=status))
        for _ in range(200):
            if "ready" in events:
                break
            await asyncio.sleep(0.05)
        shutdown.set()
        await asyncio.wait_for(task, timeout=10)

    asyncio.run(scenario())
    assert "tor-waiting" in events
    assert events.index("tor-waiting") < events.index("ready")
```

Extend the existing `_LOADING_HTML` pin in `tests/test_desktop_status.py` (`test_loading_html_polls_status_and_maps_stages`) with:

```python
    assert "Waiting for a previous Kreds to finish closing..." in html
    assert "tor-waiting" in html
```

Extend `tests/test_web_assets.py::test_onboarding_poll_never_gives_up` with:

```python
    assert "tor-waiting" in body
    assert "Waiting for a previous Kreds to finish closing..." in body
```

- [ ] **Step 2: Run to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_runner_status.py tests/test_desktop_status.py tests/test_web_assets.py -q`
Expected: the three touched tests FAIL (`tor-waiting` absent everywhere; the runner fake's `waiting` kwarg unused by `run_node`).

- [ ] **Step 3: Implement**

`hearth/runner.py` — the tor start call gains the waiting wire:

```python
                await own_tor.start(           # inside try: a failed start
                    status=lambda pct: status("tor-bootstrap", pct),
                    waiting=lambda: status("tor-waiting"))
```

(This sits inside the Task-4-of-0.3.11 `start_task = asyncio.ensure_future(own_tor.start(...))` wrapper — apply the kwargs there; the surrounding race logic is untouched.)

`hearth/desktop.py` `_LOADING_HTML` — add to the `COPY` dict:

```javascript
               "tor-waiting": "Waiting for a previous Kreds to finish closing...",
```

(No other page change: non-`tor-bootstrap` stages already use the pulsing bar.)

`hearth/web/app.js` `pollForFullApp` — add a branch after the `tor-bootstrap` one:

```javascript
        else if (s && s.stage === "tor-waiting")
          msg = "Waiting for a previous Kreds to finish closing...";
```

Version bump (lockstep, per RELEASE.md): `hearth/__init__.py` `__version__ = "0.3.12"` and `hearth/web/VERSION` → `0.3.12`.

- [ ] **Step 4: Run the three test files, then the full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_runner_status.py tests/test_desktop_status.py tests/test_web_assets.py -q` then `.venv\Scripts\python.exe -m pytest -q`
Expected: all pass. (If any test pins the version string, update it — say so in the report.)

- [ ] **Step 5: Commit**

```bash
git add hearth/runner.py hearth/desktop.py hearth/web/app.js hearth/web/VERSION hearth/__init__.py tests/test_runner_status.py tests/test_desktop_status.py tests/test_web_assets.py
git commit -m "feat(shell): tor-waiting stage - loading page and onboarding poll say what a post-update launch is waiting for; version 0.3.12"
```

---

## Self-Review Notes (planning time)

- **Spec coverage:** A → Task 2 (constants, drain helper, restart wait, evidence log). C → Task 1 (retry split + window + waiting callback) + Task 3 (stage plumbing + copy in both surfaces). Budget pin → Task 2's arithmetic test. CLI-unchanged → Task 1's default-None + no CLI edits anywhere. Rejected-B and proactive-kill stay unshipped (no task touches them). Version bump → Task 3.
- **Type consistency:** `waiting=None` no-arg callback in Task 1 = what Task 3's `_WaitingFakeTorProcess` and runner lambda use; constant names identical across tasks and tests.
- **Placeholder scan:** clean — every step carries the exact code; the one deliberately-draft item (the waiting copy) is flagged as August's to reword, used verbatim until then.
- **Known simplification:** `_drain_node_thread` is extracted precisely so the drain-timeout path is testable without a real launch(); launch() calls it verbatim.
