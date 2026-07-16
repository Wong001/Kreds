# Sync deadlock (stale onion port) + quit hang (ws shutdown) — design

Date: 2026-07-16
Status: approved in principle (August: "do the real fix, and the second
real bug"); dial-normalization refinement flagged for his spec review
Slice: 0.3.14 — two diagnosed bugs, one release

## Bug 1: total sync deadlock after a coordinated update+restart

### Root cause (locked, with live + code evidence)

`publish_onion` (tor.py:309) publishes the node's onion service with its
VIRTUAL port set to `local_port` == `gossip_port` == `_free_port()`
(desktop.py) — **a fresh random port every launch**. `gossip_addr`
becomes `<onion>.onion:<random>`. The onion ADDRESS is stable (persisted
key); the PORT churns per launch.

Peers cache each other's `gossip_addr`. When a node restarts it gets a
new random virtual port, so every peer's cached address for it points at
a DEAD port (the onion service no longer listens there → SOCKS
`REP=0x05 connection refused`).

Normally self-healing: when ONE node restarts, the still-running peer is
unchanged and dialable, so the restarted node dials out, connects, and
the fresh `gossip_addr` re-exchanges. One working direction repairs both.

**The 0.3.13 update crashed all nodes at once** (desktop, laptop, Josh —
confirmed). Every node restarted in the same window, every node rotated
its port, so every cached address went stale in BOTH directions
simultaneously with no healthy peer to repair through → permanent
deadlock. Live proof from August's store: own `gossip_addr` =
`r7nyng….onion:22299` (dial → CONNECT OK), but the cached self-peer row
= `r7nyng….onion:1117` (dial → REP 0x05). Same onion, stale port.

This is a LATENT bug present since the Tor transport shipped; the
simultaneous crash-restart is the first time it had no repair path. NOT
the wrap-grants change (that store is clean: 4 grants, pruned, quiet).

Immediate recovery already proven by August: re-adding the friend
refreshes the existing peer's address in place (no duplicate) — but that
is manual and per-pair.

### Fix: fixed onion virtual port + onion-dial normalization

- New constant `ONION_VIRTUAL_PORT = 9997` (onion virtual ports are
  per-service namespaced — every node using 9997 is fine, like every site
  using :443). Never changes again.
- `publish_onion(control_port, cookie, virtual_port, target_port, key)`:
  `ADD_ONION … Port=<virtual_port>,127.0.0.1:<target_port>` — virtual
  fixed (9997), target = the still-ephemeral local gossip listener bind
  port. `gossip_addr` = `<onion>.onion:9997`, STABLE across launches.
- `TorTransport.connect` (transport.py:50): for `.onion` hosts, dial
  `ONION_VIRTUAL_PORT` **regardless of the port in the stored address**.
  This makes stale cached ports irrelevant — no destructive data
  migration needed, and a deadlocked peer recovers the moment both ends
  reach 0.3.14. TCP (dev) addresses keep their real port unchanged.

Why dial-normalization over a peer-address migration (my first pitch to
August): a migration that rewrote cached ports would still be wrong for
peers not yet on the fixed-port build, and it destructively edits stored
data. Normalizing at dial time is non-destructive, self-correcting, and
also fixes any future stale port. `gossip_addr` still re-exchanges to
`:9997` on the next successful sync, so stored data heals too — belt and
suspenders.

**Rollout transient (accepted, self-healing):** while one end is on
0.3.14 and the other isn't, the upgraded node dials the old node at :9997
but the old node still publishes at its random port → that link is down
until both upgrade. Strictly better than the current permanent deadlock,
and the update ships over HTTP (GitHub feed), not peer sync, so the
deadlock never blocks getting the fix.

## Bug 2: tor orphaned on every quit ("shutdown drain timed out")

### Root cause (locked, code)

The `/ws` WebSocket handler (api.py:618-629) blocks forever in
`await q.get()` — it only returns on a push or a `WebSocketDisconnect`.
On quit, `run_node` sets `server.should_exit` and awaits
`server.serve()`; uvicorn's graceful shutdown has **no
`timeout_graceful_shutdown` configured** (uvicorn.Config, runner.py:89 &
155), so it waits indefinitely for that stuck task. `server.serve()`
doesn't return, the node thread blows past the 30s drain, the thread is
abandoned, and tor is orphaned (app.log: "shutdown drain timed out; a tor
orphan may linger ~15s" on every quit).

Present since the webview shipped; 0.3.12's exit-drain only started
LOGGING it (pre-0.3.12 the 8s join silently abandoned the thread the same
way). Compounds bug 1's churn: an unclean tor exit is one more restart
that rotates the port.

### Fix

Set `timeout_graceful_shutdown=3.0` on both `uvicorn.Config`
constructions. uvicorn force-closes the lingering `/ws` after 3s →
`server.serve()` returns → `run_node`'s finally cancels gossip, stops
sync, and stops tor GRACEFULLY (SIGNAL SHUTDOWN, no orphan) — total
shutdown well under the 30s drain. Uvicorn 0.49 (in venv) supports the
knob (added 0.19).

Bounded catch-all: any stuck request/ws, not just this one endpoint, can
no longer hold shutdown open. Minimal surface; no handler rewrite.

## Tests

- Bug 1: `publish_onion` emits `Port=9997,127.0.0.1:<target>` and returns
  gossip_addr on 9997 (fake control command capturing the ADD_ONION
  line); `TorTransport.connect` dials `ONION_VIRTUAL_PORT` even when the
  address says `:1117` (monkeypatch `socks_connect`, assert the port arg);
  TCP addresses still dial their real port.
- Bug 2: both `uvicorn.Config`s carry `timeout_graceful_shutdown == 3.0`
  (pin); and, if practical, an integration test — real node with an open
  `/ws`, set shutdown, assert `run_node`/`run_serve` returns within a few
  seconds instead of hanging (extend tests/test_runner.py's live pattern).

## Out of scope

- Persisting/pinning the local gossip bind port (target stays ephemeral;
  only the advertised virtual port needs to be stable).
- Making the `/ws` handler itself shutdown-aware (the uvicorn timeout is
  the right-sized catch-all; a subscriber-wake is redundant surface).
- Any peer-address data migration (dial normalization supersedes it).
