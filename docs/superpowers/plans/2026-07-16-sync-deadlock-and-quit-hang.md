# Sync Deadlock + Quit Hang Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two diagnosed bugs, one release (0.3.14): the onion service publishes at a FIXED virtual port and `.onion` dials always target it (killing the stale-port sync deadlock non-destructively), and uvicorn's graceful shutdown is bounded so a stuck `/ws` can no longer hang quit and orphan tor.

**Architecture:** Bug 1 — introduce `ONION_VIRTUAL_PORT = 9997`; `publish_onion` gains separate virtual/target ports (virtual fixed, target = ephemeral local bind), `gossip_addr` uses the fixed port, and `TorTransport.connect` normalizes every `.onion` dial to the fixed port regardless of the stored address's port. Bug 2 — set `timeout_graceful_shutdown=3.0` on both `uvicorn.Config` constructions.

**Tech Stack:** Python 3.12, asyncio, uvicorn 0.49, pytest.

**Spec:** `docs/superpowers/specs/2026-07-16-sync-deadlock-and-quit-hang-design.md` (approved).

## Global Constraints

- Work on branch `kreds-fixes-0.3.14` (create from main at start; base = current main HEAD).
- Suite green before every commit: `.venv\Scripts\python.exe -m pytest -q` (baseline at dispatch: 908 passed, 6 skipped).
- NO AI/Co-Authored-By commit trailers; ASCII-only console prints (cp1252).
- Exact values: `ONION_VIRTUAL_PORT = 9997` (module constant in `hearth/tor.py`, imported where needed); `timeout_graceful_shutdown = 3.0`.
- `publish_onion` VIRTUAL port is fixed (9997) and its TARGET port is the ephemeral local gossip bind port — they are now DIFFERENT. `gossip_addr` = `<service_id>.onion:<ONION_VIRTUAL_PORT>`.
- `.onion` dials ignore the port in the stored address and use `ONION_VIRTUAL_PORT`; TCP (non-onion) dials are UNCHANGED (keep their real port).
- Version bump 0.3.14 lockstep (`hearth/__init__.py` + `hearth/web/VERSION`) rides Task 2.

## Verified codebase facts (planning-time)

- `publish_onion(control_port, cookie_path, local_port, key_blob=None)` at tor.py:303-320; today `ADD_ONION {key_spec} Flags=Detach Port={local_port},127.0.0.1:{local_port}` (tor.py:309-310) — virtual==target==local_port.
- `run_node` (runner.py:74-82): calls `publish_onion(control_port, cookie_path, gossip_port, node.onion_key)` then `set_meta("gossip_addr", f"{service_id}.onion:{gossip_port}")`. `gossip_port` is bound by `sync.start("127.0.0.1", gossip_port)` just above (runner.py:73) and is `_free_port()` (random) when tor, `0` otherwise (desktop.py).
- `TorTransport.connect(address)` at transport.py:50-57: `host, port = address.rsplit(":", 1)`; onion → `socks_connect("127.0.0.1", self.socks_port, host, int(port), timeout=60.0)`; else `asyncio.open_connection(host, int(port))`.
- Both `uvicorn.Config(...)` calls: runner.py:89-91 (run_node) and runner.py:155-156 (run_serve bootstrap app). Neither sets `timeout_graceful_shutdown`.
- `/ws` handler blocks in `await q.get()` (api.py:624) — the task that graceful shutdown waits on.
- Tests that fake `publish_onion` (monkeypatch, so signature change is safe there): test_runner_status.py:79,167 use `async def fake_publish(control_port, cookie_path, port, key_blob)` — these must be updated to the new signature. `tests/test_tor_e2e.py:34-39` calls the REAL `publish_onion` (TOR_E2E-gated, normally skipped) and builds `faddr = f"{fid}.onion:{fp}"` — update it to the new signature + fixed-port address.
- No non-e2e test currently asserts the ADD_ONION command text or the gossip_addr port.

---

### Task 1: Stable onion virtual port + onion-dial normalization

**Files:**
- Modify: `hearth/tor.py` (new `ONION_VIRTUAL_PORT` constant near the top-level constants; `publish_onion` 303-320), `hearth/runner.py` (74-82), `hearth/transport.py` (`TorTransport.connect` 50-57)
- Test: `tests/test_tor_no_window.py` (append — it already exercises tor.py with fakes), `tests/test_transport.py` (create if absent; else append), `tests/test_runner_status.py` (fix the two fake_publish signatures), `tests/test_tor_e2e.py` (update the real call — gated/skipped but must stay importable and correct)

**Interfaces:**
- Produces: `hearth.tor.ONION_VIRTUAL_PORT = 9997`; `publish_onion(control_port, cookie_path, virtual_port: int, target_port: int, key_blob=None)` emitting `Port=<virtual_port>,127.0.0.1:<target_port>`; `TorTransport.connect` dialing `ONION_VIRTUAL_PORT` for any `.onion` host.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_tor_no_window.py`:

```python
def test_publish_onion_uses_fixed_virtual_port(monkeypatch, tmp_path):
    # Bug 1 (0.3.14): the onion VIRTUAL port is fixed so a node's dialable
    # address never churns across restarts; the TARGET is the ephemeral
    # local bind port (they now differ).
    import asyncio
    captured = {}

    async def fake_control(control_port, cookie_path, command):
        captured["cmd"] = command
        return ["250-ServiceID=abcdef", "250 OK"]

    monkeypatch.setattr(tor, "_control_command", fake_control)

    async def scenario():
        sid, blob = await tor.publish_onion(
            9051, tmp_path / "cookie", tor.ONION_VIRTUAL_PORT, 54321,
            key_blob="KEY")
        return sid

    sid = asyncio.run(scenario())
    assert sid == "abcdef"
    assert tor.ONION_VIRTUAL_PORT == 9997
    # virtual (public) 9997 -> target (local bind) 54321
    assert "Port=9997,127.0.0.1:54321" in captured["cmd"]
```

Create `tests/test_transport.py` (or append if it exists):

```python
import asyncio

from hearth.tor import ONION_VIRTUAL_PORT
from hearth.transport import TorTransport


def test_onion_dial_normalizes_to_fixed_port(monkeypatch):
    # A stale stored port in an .onion address must be ignored -- the dial
    # always targets ONION_VIRTUAL_PORT, so peers deadlocked on old random
    # ports recover once both ends are on 0.3.14 (no data migration).
    seen = {}

    async def fake_socks(socks_host, socks_port, host, port, timeout=30.0):
        seen["host"] = host
        seen["port"] = port
        return (object(), object())            # (reader, writer) placeholder

    monkeypatch.setattr("hearth.transport.socks_connect", fake_socks)
    t = TorTransport(socks_port=9050)

    async def scenario():
        await t.connect("abcdefghij.onion:1117")   # stale port 1117

    asyncio.run(scenario())
    assert seen["host"] == "abcdefghij.onion"
    assert seen["port"] == ONION_VIRTUAL_PORT       # normalized, not 1117


def test_tcp_dial_keeps_its_real_port(monkeypatch):
    seen = {}

    async def fake_open(host, port):
        seen["host"] = host
        seen["port"] = port
        return (object(), object())

    monkeypatch.setattr("hearth.transport.asyncio.open_connection", fake_open)
    t = TorTransport(socks_port=9050)

    async def scenario():
        await t.connect("127.0.0.1:22299")          # dev TCP, unchanged
    asyncio.run(scenario())
    assert seen == {"host": "127.0.0.1", "port": 22299}
```

- [ ] **Step 2: Run to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_tor_no_window.py tests/test_transport.py -q`
Expected: FAIL — `AttributeError: module 'hearth.tor' has no attribute 'ONION_VIRTUAL_PORT'`; the transport test fails because `connect` currently dials `int("1117")`, not 9997.

- [ ] **Step 3: Implement**

`hearth/tor.py` — add the constant with the other module constants (near the top, after imports):

```python
# The onion service's VIRTUAL port is fixed forever so a node's dialable
# address never changes across restarts. Onion virtual ports are per-
# service namespaced (every Kreds node can use 9997, like every site uses
# :443), and the local TARGET port stays ephemeral. Prior bug (<=0.3.13):
# virtual == a fresh _free_port() each launch, so a coordinated restart
# rotated every node's port at once -> all cached peer addresses stale in
# both directions -> permanent sync deadlock.
ONION_VIRTUAL_PORT = 9997
```

`publish_onion` — split the port arg:

```python
async def publish_onion(control_port: int, cookie_path: Path,
                        virtual_port: int, target_port: int,
                        key_blob=None):
    key_spec = key_blob if key_blob else "NEW:ED25519-V3"
    # Flags=Detach: without it, Tor removes an ephemeral onion the moment the
    # control connection that created it closes -- and _control_command closes
    # per call, so a non-detached service would die the instant this returns.
    # virtual_port is the FIXED public port peers dial; target_port is the
    # ephemeral local gossip listener the onion maps onto.
    cmd = (f"ADD_ONION {key_spec} Flags=Detach "
           f"Port={virtual_port},127.0.0.1:{target_port}")
    reply_lines = await _control_command(control_port, cookie_path, cmd)
    fields = _parse_control_reply(reply_lines)
    service_id = fields.get("ServiceID")
    if service_id is None:
        raise RuntimeError(
            "ADD_ONION returned no ServiceID: " + " | ".join(reply_lines))
    returned = fields.get("PrivateKey")
    return service_id, (returned if returned else key_blob)
```

`hearth/runner.py` (74-82) — pass the fixed virtual port, target = the bound gossip port, advertise the fixed port. Add `ONION_VIRTUAL_PORT` to the existing `from .tor import ...`:

```python
            if publish:
                status("onion-publish")
                service_id, blob = await publish_onion(
                    tor_process.control_port, tor_process.cookie_path,
                    ONION_VIRTUAL_PORT, gossip_port, node.onion_key)
                if blob and blob != node.onion_key:
                    node.save_onion_key(blob)
                node.store.set_meta(
                    "gossip_addr",
                    f"{service_id}.onion:{ONION_VIRTUAL_PORT}")
```

`hearth/transport.py` `TorTransport.connect` — normalize onion dials (import the constant at top of the module: `from .tor import ONION_VIRTUAL_PORT`):

```python
    async def connect(self, address: str):
        host, port = address.rsplit(":", 1)
        if host.endswith(".onion"):
            # Always dial the FIXED virtual port, ignoring the port in the
            # stored address: pre-0.3.14 peers advertised a random per-
            # launch port, so a cached address's port is unreliable. Once
            # both ends are on 0.3.14 every onion listens at
            # ONION_VIRTUAL_PORT, so this recovers stale-port deadlocks
            # without a destructive peer-address migration.
            return await socks_connect("127.0.0.1", self.socks_port,
                                       host, ONION_VIRTUAL_PORT,
                                       timeout=60.0)
        return await asyncio.open_connection(host, int(port))
```

Fix the two fakes in `tests/test_runner_status.py` (79, 167): change `async def fake_publish(control_port, cookie_path, port, key_blob)` to `async def fake_publish(control_port, cookie_path, virtual_port, target_port, key_blob)`. Update `tests/test_tor_e2e.py:34-39` to the new signature and fixed-port address:

```python
            wid, wblob = await publish_onion(tor.control_port,
                                             tor.cookie_path,
                                             ONION_VIRTUAL_PORT, wp, None)
            fid, fblob = await publish_onion(tor.control_port,
                                             tor.cookie_path,
                                             ONION_VIRTUAL_PORT, fp, None)
            wong.save_onion_key(wblob); freja.save_onion_key(fblob)
            faddr = f"{fid}.onion:{ONION_VIRTUAL_PORT}"
```

(add `ONION_VIRTUAL_PORT` to test_tor_e2e.py's `from hearth.tor import ...`).

- [ ] **Step 4: Run the touched tests, then the full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_tor_no_window.py tests/test_transport.py tests/test_runner_status.py -q` then `.venv\Scripts\python.exe -m pytest -q`
Expected: all pass. Watch `tests/test_scoped_posts_e2e.py` / `tests/test_sync_dm.py` (real-socket, non-tor `SyncService` over `127.0.0.1`) — they use `TcpTransport`, not `TorTransport`, so the onion normalization must not touch them; confirm green.

- [ ] **Step 5: Commit**

```bash
git add hearth/tor.py hearth/runner.py hearth/transport.py tests/
git commit -m "fix(tor): fixed onion virtual port 9997 + onion dials normalize to it - a node's address no longer churns per launch, killing the coordinated-restart sync deadlock; no data migration"
```

---

### Task 2: Bounded uvicorn graceful shutdown + version bump

**Files:**
- Modify: `hearth/runner.py` (both `uvicorn.Config` calls: 89-91, 155-156), `hearth/__init__.py` + `hearth/web/VERSION` (0.3.13 → 0.3.14)
- Test: `tests/test_runner.py` (append — it already runs `run_serve` live)

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: both `uvicorn.Config` carry `timeout_graceful_shutdown=3.0`.

- [ ] **Step 1: Write the failing test**

Append to `tests/test_runner.py` (reuse its `_free_port` + live-server idiom):

```python
def test_run_serve_shutdown_is_bounded_with_open_ws(tmp_path):
    # Bug 2 (0.3.14): the /ws handler blocks forever in await q.get();
    # without a bounded graceful shutdown uvicorn waits on it and quit
    # hangs (tor orphaned). With timeout_graceful_shutdown set, an open
    # /ws cannot hold shutdown past a few seconds.
    import asyncio, time
    import websockets
    from hearth.node import HearthNode

    async def scenario():
        node_dir = tmp_path / "n"
        HearthNode.create(node_dir, "Wong", "wong-phone")   # enrolled: skip bootstrap
        port = _free_port()
        shutdown = asyncio.Event()
        task = asyncio.create_task(run_serve(node_dir, 0, port, shutdown=shutdown))
        # wait for the full node app to answer
        import httpx
        async with httpx.AsyncClient() as c:
            for _ in range(80):
                try:
                    if (await c.get(f"http://127.0.0.1:{port}/api/state")).status_code == 200:
                        break
                except Exception:
                    pass
                await asyncio.sleep(0.1)
        # hold a /ws open (the real handler parks in await q.get())
        ws = await websockets.connect(f"ws://127.0.0.1:{port}/ws")
        shutdown.set()
        t0 = time.monotonic()
        await asyncio.wait_for(task, timeout=10)     # must return, not hang
        assert time.monotonic() - t0 < 8             # bounded (~3s graceful)
        await ws.close()

    asyncio.run(scenario())
```

(If `websockets` isn't already a test dep, it is a transitive dep of uvicorn — importable in the venv; if the import fails, fall back to opening the ws via `httpx`'s ws support or an `asyncio.open_connection` + manual handshake, but prefer `websockets`. Confirm the enrolled-dir path skips the bootstrap phase so `/api/state` is the full app — mirror how `tests/test_onboarding_integration.py` builds an enrolled node.)

- [ ] **Step 2: Run to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_runner.py::test_run_serve_shutdown_is_bounded_with_open_ws -q`
Expected: FAIL — the `await asyncio.wait_for(task, timeout=10)` times out (uvicorn waits indefinitely on the parked `/ws`), or the elapsed assertion exceeds 8s.

- [ ] **Step 3: Implement**

Both `uvicorn.Config(...)` calls in `hearth/runner.py` gain the kwarg. run_node (89-91):

```python
        server = uvicorn.Server(uvicorn.Config(
            build_app(node, web_dir=web_dir), host="127.0.0.1", port=http_port,
            log_level="warning", timeout_graceful_shutdown=3))
```

run_serve bootstrap app (155-156):

```python
        server = uvicorn.Server(uvicorn.Config(
            app, host="127.0.0.1", port=http_port, log_level="warning",
            timeout_graceful_shutdown=3))
```

(Comment at the run_node one: `# bound graceful shutdown: the /ws handler parks in await q.get() and never self-exits, so an unbounded shutdown hangs quit and orphans tor (0.3.14).`)

Version bump: `hearth/__init__.py` `__version__ = "0.3.14"`, `hearth/web/VERSION` → `0.3.14`.

- [ ] **Step 4: Run the new test + full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_runner.py -q` then `.venv\Scripts\python.exe -m pytest -q`
Expected: green. (If a test pins the version string, update it — say so in the report.)

- [ ] **Step 5: Commit**

```bash
git add hearth/runner.py hearth/web/VERSION hearth/__init__.py tests/test_runner.py
git commit -m "fix(runner): bound uvicorn graceful shutdown (3s) so a parked /ws can't hang quit and orphan tor; version 0.3.14"
```

---

## Self-Review Notes (planning time)

- Spec coverage: Bug 1 fixed-port publish → T1 (`publish_onion` + runner); dial normalization → T1 (`TorTransport.connect`); constant 9997 → T1. Bug 2 bounded shutdown → T2 (both Configs). Version bump → T2. No peer-address migration (spec out-of-scope) — nothing does it. E2E test signature updates → T1 Step 3.
- Type consistency: `publish_onion(..., virtual_port, target_port, key_blob=None)` used identically in runner and both test fakes; `ONION_VIRTUAL_PORT` imported in runner, transport, and tests from `hearth.tor`.
- Placeholder scan: clean — every step carries exact code. The one soft edge (websockets import in T2's test) has a named fallback, not a TODO.
- Rollout note (from spec, no code): the fix can't reach a not-yet-upgraded peer during the split-version window; that's inherent and documented, not a plan gap.
