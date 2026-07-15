# Tor orphan window — drain on exit, patience on launch — design

Date: 2026-07-15
Status: approved approach (August, 2026-07-15: "A + C"); spec pending review
Slice: 0.3.12 candidate (first fix after the 0.3.11 release)

## Problem

First launch after the 0.3.11 update failed with "tor exited before
bootstrap after 2 attempts" (August, live, app.log 15:27:31). Root cause
chain, verified in code:

- On quit/restart, `launch()` waits at most 8s for the node thread
  (`desktop.py:589` `t.join(timeout=8)`), but a fully graceful tor stop
  is bounded at ~16s worst case (5s SIGNAL SHUTDOWN grace + 10s
  terminate-wait + kill, `tor.py:204-231`). Past 8s the process exits,
  the daemon node thread dies mid-stop, and tor.exe is orphaned.
- An orphaned tor notices its dead owner via `__OwningControllerProcess`
  polling only within ~15s. Until then it holds the fixed socks/control
  ports and the tordata lock, so a freshly spawned tor exits instantly.
- The spawn retry budget (2 attempts, 5s gap ≈ 6-8s total for
  instant-exit failures) fits inside that orphan window. The update
  restart hands off ~5s after the old process exits — inside it too.
- 0.3.10 made orphans near-certain (unbounded gossip dials delayed the
  tor stop past the 8s cap); 0.3.11 narrowed but did not close the hole,
  and no exit-side fix can ever cover a killed/crashed process.

The 0.3.11 loading page rendered the failure honestly and a relaunch
recovered — the failure is now visible and recoverable, but it should not
happen at all.

## Decision (August, 2026-07-15)

Fix both sides, no visible "closing" UI:

- **A — invisible drain on exit:** quit feels instant (window dies
  immediately) but the process stays alive until the node thread — and
  therefore the tor stop — actually finishes, within a hard bound.
- **C — patience on launch:** a freshly spawned tor that exits instantly
  is retried for long enough to outlive any orphan, and the loading page
  says what it is waiting for. This is the only side that also covers
  kill/crash orphans.

Rejected: **B — visible "Closing Kreds..." window** (every quit feels
slower; keeps the webview alive through teardown, enlarging the lifecycle
surface that just needed a hardening wave).

## Design

### A. Exit drain (`hearth/desktop.py`)

- `SHUTDOWN_DRAIN_TIMEOUT = 30.0` module constant, replacing the literal
  8 in `launch()`'s `t.join(...)`. Rationale comment: must exceed
  TorProcess.stop()'s own worst-case internal budget (~16s) plus
  sync teardown overhead; still bounded so a wedged node thread cannot
  turn quit into a zombie process.
- If the join still times out, `_log_error(data_dir, "shutdown drain
  timed out; a tor orphan may linger ~15s")` — evidence in app.log, no
  other behavior change.
- Restart handoff budget: `acquire_single_instance` wait on
  `KREDS_RESTARTING` rises 15.0 → 30.0 (`RESTART_LOCK_WAIT = 30.0`
  constant next to the drain constant; comment ties the two: the new
  instance must out-wait the old instance's drain).

### C. Spawn-exit retry + waiting stage (`hearth/tor.py`, `hearth/runner.py`, `hearth/desktop.py` loading page, `hearth/web/app.js`)

- `TorProcess.start` distinguishes its two failure kinds:
  - **Bootstrap timeout** (tor alive but slow): unchanged — 2 attempts of
    `bootstrap_timeout` each.
  - **Spawn-exit** (`tor exited before bootstrap`, the orphan signature):
    retry every `SPAWN_RETRY_GAP = 5.0`s while total elapsed
    `< SPAWN_RETRY_WINDOW = 30.0`s (covers the ~15s orphan poll with 2x
    margin; each failed attempt costs <1s so this is ~6 attempts).
  - Budget check: worst case start() duration ≈ 30 + 2x90 + 5 = 215s,
    still under `READY_TIMEOUT_TOR = 240` — that relationship gets a
    test pin.
- New optional `waiting` callback on `TorProcess.start(...,
  status=None, waiting=None)`: called (no args, exception-swallowed like
  `status`) each time a spawn-exit retry is scheduled. The existing
  `status(pct)` contract is untouched.
- `run_node` wires `waiting=lambda: status("tor-waiting")`. Stage
  contract gains one name: `tor-waiting` (between `tor-bootstrap`
  emissions; display-only like all stages).
- Copy (DRAFT — August words final): loading page `_LOADING_HTML` and
  app.js `pollForFullApp` map `tor-waiting` →
  "Waiting for a previous Kreds to finish closing...". Indeterminate
  (pulsing) bar while in this stage.

### Interactions / edge cases

- A successful spawn after waiting returns to the normal
  `tor-bootstrap` percent flow — no state to unwind.
- Kill/crash orphans: only C applies; 30s window outlives the 15s poll
  regardless of how the previous process died.
- Update restart (old version = 0.3.11): the OLD code's 8s cap still
  governs that one handoff; C on the NEW side absorbs it. From 0.3.12 on,
  both sides hold.
- `hearth serve` CLI: same retry behavior; no `waiting` callback wired
  (default None), stdout unaffected.
- Proactive orphan kill (pid-file + terminate stale tor at startup):
  deliberately NOT shipped — YAGNI while the passive fix suffices;
  recorded as future hardening if the 30s window ever proves too short.

## Tests (behavior change is intended)

- tor: spawn-exit retries until success within the window (fake procs:
  N instant-exits then a good one; patched gap/window constants);
  spawn-exit gives up after the window with the existing error message;
  bootstrap-timeout path still exactly 2 attempts; `waiting` callback
  fires per retry and its exceptions never kill startup.
- runner: `tor-waiting` appears in the stage sequence when the fake
  TorProcess signals waiting.
- desktop: constants pinned (`SHUTDOWN_DRAIN_TIMEOUT == 30.0`,
  `RESTART_LOCK_WAIT == 30.0`, and `30 + 2*90 + 5 < READY_TIMEOUT_TOR`
  as an arithmetic relationship test); drain-timeout logs to app.log;
  `_LOADING_HTML` content pin for the waiting copy.
- web assets: `pollForFullApp` maps `tor-waiting` (content pin).

## Out of scope

- Visible closing UI (rejected B).
- Proactive stale-tor kill / pid tracking (future hardening).
- Dynamic tor ports (would break stable onion service mapping
  assumptions; not needed once both sides hold).
