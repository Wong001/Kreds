# App-lock misfire fix — design

Date: 2026-07-15
Status: approved (August, 2026-07-15 — investigation findings + decisions)
Slice: 1 of 3 in the 0.3.11 fixes bundle (smallest first)

## Problem

The app locks "randomly" and appears to lock on window blur. Investigation
(2026-07-15) found a single root cause and refuted the blur/drag theories:

- The only automatic lock paths are `maybe_autolock()`'s two heuristics
  (`hearth/node.py:405-427`): idle timeout and "lock on sleep" (wall-clock
  gap > `interval + 30` seconds between gossip rounds, meant to detect OS
  suspend/resume).
- `maybe_autolock` is called at the END of `_gossip_round`
  (`hearth/sync.py:236`), and the gap it measures INCLUDES the round's own
  network time. Peer dials are effectively unbounded — plain-TCP
  `asyncio.open_connection` has no timeout (`hearth/transport.py:33-35`),
  onion dials are allowed 60 s (`transport.py:50-57`). One offline friend
  makes a round exceed the 33 s threshold and the node locks itself while
  the user is active.
- Nothing locks on blur/minimize/drag. `visibilitychange`
  (`app.js:2627-2629`) and the fetch-423 gate only *reveal* an
  already-locked node — which is why the lock seems to appear when
  tapping out of the window.
- No test covers slow-peer × autolock interaction (gossip tests use an
  instant fake `sync_with`).

## Decisions (August, 2026-07-15)

- Fix the misfire mechanism.
- `lock_on_sleep` becomes **default OFF** ("kinda annoying when you don't
  want it either"). The idle timer and the manual Lock button are the
  default lock surfaces.

## Design

### 1. Decouple suspend detection from gossip I/O time

Move the autolock tick to `gossip_loop` (`hearth/sync.py:238-244`) around
its own `asyncio.sleep(interval)`: stamp `node._last_tick` immediately
BEFORE the sleep, call `node.maybe_autolock(interval)` immediately AFTER
it. The measured gap is then `sleep-duration + suspend-time` only — a
round's dial time can no longer masquerade as a suspend. `_gossip_round`
loses its `maybe_autolock` call. The `interval + 30` threshold is
unchanged (it is now measuring what it was designed to measure).

### 2. Bound peer dials (independent hardening, same slice)

Wrap each dial in `sync_with` with `asyncio.wait_for`: 20 s for plain
TCP, 75 s for `.onion` (above the transport's own 60 s budget so the
transport's error surfaces first; the wait_for is the backstop). A timed-out
peer is skipped for that round exactly like a failed dial today. This
fixes the adjacent real problem that one dead peer stalls every other
peer's sync that round.

### 3. `lock_on_sleep` default off + one-time migration

- `hearth/applock.py:93`: record-creation default becomes
  `"lock_on_sleep": False`.
- Existing installs stored `True` at record creation — nobody chose it.
  One-time migration: a version marker in the applock record (e.g.
  `"settings_v": 2`); on load, records without the marker get
  `lock_on_sleep` flipped to `False` and the marker set. A user who
  re-enables it afterwards keeps it (the marker prevents re-flipping).
  Release notes mention the setting exists and is now off by default.
- Settings UI (`app.js:2713-2725`): unchanged apart from reflecting the
  stored value, which it already does.

## Tests

- Regression: a `sync_with` stub that sleeps > threshold inside a round
  with `lock_on_sleep` enabled must NOT lock the node (would fail on
  today's code); a genuine `_last_tick` jump (simulated suspend) still
  locks when the setting is on.
- `lock_on_sleep` off (new default): wall-clock jump does NOT lock.
- Migration: v1 record with `lock_on_sleep: True` loads as `False` with
  marker set; marked record with `True` (user re-enabled) stays `True`.
- Dial bound: a peer whose connect hangs past the bound is skipped and
  the round proceeds to the next peer (real-socket test with a
  never-accepting listener).

## Out of scope

- Any change to the idle-timeout semantics or the manual Lock button.
- Round-parallel dialing (bounding is enough here; parallelism is its own
  slice if ever needed).
