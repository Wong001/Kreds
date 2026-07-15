# App-lock Misfire Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The sleep-detection autolock stops misfiring on slow gossip rounds; peer dials are bounded; `lock_on_sleep` defaults off with a one-time migration.

**Architecture:** Move the autolock tick out of `_gossip_round` (where the measured gap includes unbounded peer-dial I/O) to `gossip_loop`'s own sleep boundary, so the gap only ever measures sleep-duration + genuine suspend time. Independently bound each peer dial with `asyncio.wait_for`. Flip the `lock_on_sleep` default at all three definition sites and migrate existing records once, guarded by a `settings_v` marker.

**Tech Stack:** Python 3.12, asyncio, pytest.

**Spec:** `docs/superpowers/specs/2026-07-15-applock-misfire-design.md` (approved).

## Global Constraints

- Suite green: `.venv\Scripts\python.exe -m pytest -q` from repo root (baseline 809 passed / 6 skipped).
- Commit messages: NO AI/Co-Authored-By trailers.
- Console prints ASCII only (cp1252).
- The `interval + 30` threshold and idle-timeout semantics are UNCHANGED.

---

### Task 1: Move the autolock tick to the sleep boundary

**Files:**
- Modify: `hearth/node.py:405-427` (docstring + new baseline method)
- Modify: `hearth/sync.py:213-244` (`_gossip_round` loses the call; `gossip_loop` gains stamp+tick)
- Test: `tests/test_applock_api.py` (append), `tests/test_gossip_loop.py` (append)

**Interfaces:**
- Produces: `HearthNode.stamp_autolock_tick(now=None)` — sets the gap baseline. `gossip_loop` calls it before `asyncio.sleep`, then calls `maybe_autolock(interval)` after. `maybe_autolock` signature unchanged.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_applock_api.py` (follow the file's existing `maybe_autolock` test conventions around lines 279-329 — they hand-set `_last_tick`/`last_activity` on a node fixture; copy that construction):

```python
def test_slow_round_does_not_fake_a_suspend():
    # New contract: the baseline is stamped at sleep START, so the gap
    # maybe_autolock sees is sleep-duration only -- a 60s gossip round
    # cannot masquerade as a suspend anymore.
    n = ...  # build node exactly as this file's other autolock tests do,
             # applock enabled, lock_on_sleep True in settings
    t0 = time.time()
    n.stamp_autolock_tick(now=t0)
    # 3s of sleep elapsed, regardless of how long the round before it took:
    n.maybe_autolock(interval=3.0, now=t0 + 3.1)
    assert not n.locked


def test_real_suspend_still_locks():
    n = ...  # same construction, lock_on_sleep True
    t0 = time.time()
    n.stamp_autolock_tick(now=t0)
    n.maybe_autolock(interval=3.0, now=t0 + 3.0 + 31.0)   # woke up 31s late
    assert n.locked
```

Append to `tests/test_gossip_loop.py` (it drives `gossip_loop` with a fake instant `sync_with`; follow its fixture conventions):

```python
async def test_gossip_loop_ticks_autolock_at_sleep_boundary(...):
    # Pin the wiring order: round -> stamp -> sleep -> maybe_autolock.
    calls = []
    node.stamp_autolock_tick = lambda now=None: calls.append("stamp")
    node.maybe_autolock = lambda interval: calls.append("tick")
    # patch service._gossip_round to append "round" and asyncio.sleep to
    # append "sleep" then cancel the loop after two iterations
    ...
    assert calls[:4] == ["round", "stamp", "sleep", "tick"]
```

(The `...` bodies follow the file's existing loop-driving pattern — run the loop as a task, cancel after N events.)

- [ ] **Step 2: Run to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_applock_api.py tests/test_gossip_loop.py -q -k "slow_round or real_suspend or sleep_boundary"`
Expected: FAIL — `stamp_autolock_tick` does not exist.

- [ ] **Step 3: Implement**

`hearth/node.py` — add below `_touch` (node.py:296-300):

```python
    def stamp_autolock_tick(self, now: Optional[float] = None):
        """Set the sleep-gap baseline. Called by gossip_loop immediately
        BEFORE its inter-round sleep, so maybe_autolock (called right
        after the sleep) measures sleep-duration + suspend time ONLY --
        a slow gossip round's dial time can no longer masquerade as a
        suspend (0.3.11 misfire fix). Honest limit: a suspend that
        happens mid-round is not detected by this heuristic; the idle
        timer remains the backstop for long absences."""
        self._last_tick = now if now is not None else time.time()
```

Update `maybe_autolock`'s docstring (node.py:407-414): replace "Ticked by the gossip/periodic loop" sentence with "Ticked by gossip_loop immediately after its inter-round sleep; stamp_autolock_tick sets the baseline immediately before it."

`hearth/sync.py` — delete lines 230-236 (the autolock comment + `self.node.maybe_autolock(interval)` in `_gossip_round`), and change `gossip_loop`:

```python
    async def gossip_loop(self, interval: float = 3.0, now=None):
        while True:
            try:
                await self._gossip_round(interval=interval, now=now)
            except Exception:
                pass                    # never let one bad round kill gossip
            # Auto-lock tick brackets ONLY the sleep: baseline before,
            # check after, so the gap can't include the round's dial time
            # (the 0.3.11 misfire: one offline peer > 33s round -> lock).
            self.node.stamp_autolock_tick()
            await asyncio.sleep(interval)
            self.node.maybe_autolock(interval)
```

- [ ] **Step 4: Run the tests, then the full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_applock_api.py tests/test_gossip_loop.py -q` → PASS.
Run: `.venv\Scripts\python.exe -m pytest -q` → any failures should only be tests pinning the OLD call site (a `maybe_autolock` called-from-`_gossip_round` assertion, if one exists) — update those to the new wiring; anything else is a regression, stop and investigate.

- [ ] **Step 5: Commit**

```bash
git add hearth/node.py hearth/sync.py tests/
git commit -m "fix(applock): sleep-gap tick brackets only gossip_loop's sleep - a slow round (offline peer, 60s onion dial) can no longer masquerade as a suspend"
```

---

### Task 2: Bound peer dials

**Files:**
- Modify: `hearth/sync.py:177-197` (`_sync_session` dial)
- Test: `tests/test_sync_session.py` (append)

**Interfaces:**
- Produces: module constants `TCP_DIAL_TIMEOUT = 20.0`, `ONION_DIAL_TIMEOUT = 75.0` in `hearth/sync.py`; a dial that exceeds its bound behaves exactly like a refused/offline peer (returns `False, None, []`).

- [ ] **Step 1: Write the failing test**

Append to `tests/test_sync_session.py` (follow its SyncService/store fixture conventions):

```python
async def test_hanging_dial_is_bounded(monkeypatch, ...):
    svc = ...  # build a SyncService as this file's other tests do
    async def hang(address):
        await asyncio.sleep(30)
    monkeypatch.setattr(svc.transport, "connect", hang)
    monkeypatch.setattr("hearth.sync.TCP_DIAL_TIMEOUT", 0.2)
    t0 = time.monotonic()
    ok = await svc.sync_with("127.0.0.1:9")
    assert ok is False
    assert time.monotonic() - t0 < 2.0   # bounded, not the 30s hang
```

- [ ] **Step 2: Run to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_sync_session.py -q -k hanging_dial`
Expected: FAIL — `TCP_DIAL_TIMEOUT` does not exist (AttributeError on monkeypatch).

- [ ] **Step 3: Implement**

`hearth/sync.py` — add constants near the top (beside `ONION_SYNC_INTERVAL` import usage):

```python
TCP_DIAL_TIMEOUT = 20.0     # plain-TCP connect bound; open_connection has none
ONION_DIAL_TIMEOUT = 75.0   # backstop ABOVE TorTransport's own 60s budget so
                            # the transport's error surfaces first
```

In `_sync_session` (sync.py:194-197) wrap the dial:

```python
        try:
            timeout = ONION_DIAL_TIMEOUT if _is_onion(address) else TCP_DIAL_TIMEOUT
            reader, writer = await asyncio.wait_for(
                self.transport.connect(address), timeout=timeout)
        except (OSError, asyncio.TimeoutError):
            return False, None, []      # peer offline/unreachable: next round
```

- [ ] **Step 4: Run tests, then full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_sync_session.py -q` → PASS.
Run: `.venv\Scripts\python.exe -m pytest -q` → green.

- [ ] **Step 5: Commit**

```bash
git add hearth/sync.py tests/test_sync_session.py
git commit -m "fix(sync): bound peer dials (20s tcp / 75s onion backstop) - one dead peer no longer stalls the whole gossip round"
```

---

### Task 3: `lock_on_sleep` default off + one-time migration

**Files:**
- Modify: `hearth/applock.py:87-95` (`enable` record: default False + `settings_v: 2`), new `migrate_settings` function
- Modify: `hearth/node.py:302-312` (status fallbacks → False; migration call at applock init — find where `self.applock_enabled`/`self._applock_path` are set in `__init__`/load and run the migration there), `hearth/node.py:422` (`settings.get("lock_on_sleep", True)` → `False`)
- Test: `tests/test_applock_api.py` (append; also update any test asserting the old default True)

**Interfaces:**
- Produces: `applock.migrate_settings(record: dict) -> tuple[dict, bool]` — flips unmarked records' `lock_on_sleep` to False, stamps `settings_v: 2`; idempotent (marked records returned unchanged, `False`).

- [ ] **Step 1: Write the failing tests**

```python
def test_new_records_default_lock_on_sleep_off(...):
    record, _ = applock.enable({"k": "v"}, "1234", "pin", seal=lambda b: b)
    assert record["settings"]["lock_on_sleep"] is False
    assert record["settings_v"] == 2


def test_migration_flips_unmarked_record_once():
    v1 = {"settings": {"idle_minutes": 5, "lock_on_sleep": True}}
    migrated, changed = applock.migrate_settings(dict(v1))
    assert changed and migrated["settings"]["lock_on_sleep"] is False
    assert migrated["settings"]["idle_minutes"] == 5      # untouched
    assert migrated["settings_v"] == 2
    # user re-enables afterwards: marker prevents re-flip
    migrated["settings"]["lock_on_sleep"] = True
    again, changed2 = applock.migrate_settings(dict(migrated))
    assert not changed2 and again["settings"]["lock_on_sleep"] is True


def test_status_fallback_defaults_off(...):
    n = ...  # node WITHOUT applock enabled, as this file constructs one
    assert n.applock_status()["settings"]["lock_on_sleep"] is False
```

- [ ] **Step 2: Run to verify they fail** (`migrate_settings` missing; defaults still True).

- [ ] **Step 3: Implement**

`hearth/applock.py`: in `enable`, `"settings": {"idle_minutes": 0, "lock_on_sleep": False}, "settings_v": 2,`. Add:

```python
def migrate_settings(record: dict) -> tuple[dict, bool]:
    """One-time 0.3.11 migration: lock_on_sleep shipped default-ON and
    nobody chose it; flip unmarked records to OFF. settings_v marks the
    record so a user who re-enables it afterwards keeps their choice."""
    if record.get("settings_v", 1) >= 2:
        return record, False
    settings = record.setdefault("settings",
                                 {"idle_minutes": 0, "lock_on_sleep": False})
    settings["lock_on_sleep"] = False
    record["settings_v"] = 2
    return record, True
```

`hearth/node.py`: at applock load (where `applock_enabled` is determined at init — locate `_applock_path` initialization), run: read record → `migrate_settings` → `_atomic_write` if changed. Flip the two `{"idle_minutes": 0, "lock_on_sleep": True}` fallbacks (node.py:307, 312) and the `settings.get("lock_on_sleep", True)` at node.py:422 to `False`. `update_applock_settings` (node.py:320-321) must preserve `settings_v` (it rewrites only `record["settings"]` — verify, don't clobber).

- [ ] **Step 4: Full suite**

Run: `.venv\Scripts\python.exe -m pytest -q` — update any existing test that pinned the old True default (expected in `tests/test_applock_api.py`); anything else failing is a regression.

- [ ] **Step 5: Commit**

```bash
git add hearth/applock.py hearth/node.py tests/
git commit -m "fix(applock): lock_on_sleep defaults OFF + one-time settings_v migration - the suspend lock is opt-in now that it only fires on real suspends"
```

---

## Self-Review Notes (planning time)

- Spec coverage: tick decoupling (T1), dial bounds (T2), default+migration (T3). Threshold and idle semantics untouched throughout.
- The `...` constructions are copy-from-neighboring-test instructions, deliberate.
- Suspend-mid-round nondetection is documented in `stamp_autolock_tick`'s docstring per the spec's honesty requirement.
