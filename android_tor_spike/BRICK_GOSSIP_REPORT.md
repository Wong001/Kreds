# Kotlin Gossip Server — the Phone's Answering Side (Arc 1) — proof record + arc-1 DoD

**The phone can now answer.** Every prior networking path on the Android
client was initiator-only (`KotlinSync.run` dials out, `KotlinHandshake.
runOverStream` proves the phone to a peer it called) — the always-on
desktop hearth node was the only side that could ever accept a connection.
This arc gives the phone the RESPONDER half of hearth's `_session`: a
`GossipServer` accept loop, bound `127.0.0.1`-only, running inside the
existing foreground `TorNodeService`, that authenticates an inbound peer
(refusing strangers byte-parity with hearth), then serves each authenticated
peer exactly the content it is entitled to (own devices / kreds friends /
inner-ring friends) and ingests what it offers — a full sync round, from the
answering side, proven by a REAL hearth node dialing the phone.

Branch: `brick-gossip-server` (base `69f8828` off main). HEAD: `dc288a8`.
Spec: `docs/superpowers/specs/2026-07-23-kotlin-gossip-server-design.md`.
Plan: ledger section `PLAN: 2026-07-23 kotlin-gossip-server` in
`.superpowers/sdd/progress.md`.

6 tasks, 8 commits. Tasks 1 and 4 were APPROVED clean first pass; Tasks 2, 3,
and 5 were approved after in-branch fix waves that each caught a REAL
protocol-fidelity bug (not cosmetic) — see "Fixes the branch caught" below.
Global constraints held throughout: `feat/fix/docs/test(gossip)` lowercase,
no AI/Co-Authored-By trailers; the give-side filter (`messagesNotIn`, ported
from hearth's `store.py:702-750`) matched EXACTLY — DM never relays, RING is
author-private, POST unions author-keyed grant devices, seen-delta by
summary; stranger refused at AUTH; bind `127.0.0.1` ONLY (the onion is arc
2); the accept path shares `SyncRunner`'s process-wide `ReentrantLock`
(`SyncRunner.kt:55`) with the phone's own outbound sync so the two never
write the shared SQLite store concurrently; the initiator `run`/
`runOverStream` paths are byte-UNCHANGED (zero regressions to the phone's
existing outbound behavior). This is Task 6, the final task: desk-gate
sweep, RELEASE install on the G20, an honestly-scoped on-device capability
probe, this report, and a PAUSE — merge is August's call.

## Fixes the branch caught (not cosmetic — each is a real protocol bug)

- **Task 2 (`2152ecd`/`69a29cb`):** the stranger refusal was written
  PRE-HELLO. Moved to POST-AUTH so `{"t":"refused"}` lands in the exact wire
  slot a real hearth initiator's REVOCATIONS-phase read expects
  (`sync.py:657-662`) — a pre-HELLO refusal would have looked like a
  malformed handshake to a real initiator, not a clean `PeerRefused`.
- **Task 3 (`1a5c8ac`/`9a5070b`):** the MESSAGES phase was implemented in
  write-then-read (initiator) order. The responder's actual I/O order must
  be the REVERSE (`_session`, `sync.py:643-825`) — a buffered fake `Stream`
  in the unit test was blind to this, but a real socket would deadlock on
  any payload exceeding the OS buffer. Fixed to read-then-write; every one
  of the 6 `_swap` phases re-traced against hearth to confirm the correct
  half-duplex interlock.
- **Task 5 (`fda05c3`/`dc288a8`):** the loopback parity gate (below) flaked
  empirically (reproduced 2/2 before the fix) with a Windows
  `ConnectionAbortedError` (WinError 10053) on the stranger-refusal path.
  Root cause: `respondHandshake` writes `{"t":"refused"}` and returns without
  reading the peer's still-in-flight REVOCATIONS frame; a bare `close()`
  with unread received data pending triggers an RST instead of a clean FIN
  on this platform, and the RST can discard our OWN already-written refusal
  frame before the peer's kernel delivers it — `PeerRefused` is lost,
  degrading to a generic I/O failure. Fixed with `gracefulClose`
  (`GossipServer.kt`): flush → `shutdownOutput()` → drain-to-EOF (bounded by
  the existing 30s `SO_TIMEOUT`) → `close()`, run OUTSIDE the shared
  `syncLock` so a stalled peer's drain can only block its own worker thread.
  De-flaked empirically: implementer 15/15 + 10/10, reviewer 4/4 fresh under
  the exact prior-failing conditions.

## Desk gates (all GREEN — this session, against HEAD `dc288a8`)

Commands run from `android_tor_spike/app/android` (gradle),
`android_tor_spike/app` (tsc/vitest), and the repo root (pytest), against a
clean working tree (`git status` clean at session start; no code changes
made this session — desk-gate verification + install + on-device probe
only).

| Gate | Command | Result |
|------|---------|--------|
| Full JVM suite (`tor-manager`) | `JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot" ./gradlew :tor-manager:testDebugUnitTest --rerun-tasks` | **BUILD SUCCESSFUL** in 23s, 62/62 tasks executed (forced fresh — no cached UP-TO-DATE test results) |
| XML result count | glob `android_tor_spike/app/modules/tor-manager/android/build/test-results/testDebugUnitTest/*.xml`, summed `tests`/`failures`/`errors` attrs | **305 tests, 0 failures, 0 errors**, 33 result files — exact match to expected 305/0 |
| Full hearth pytest | `.venv/Scripts/python.exe -m pytest -q` (repo root) | **1105 passed, 9 skipped**, 4 warnings, 83.33s — exact match to expected |
| `npx tsc --noEmit` | from `android_tor_spike/app` | **14 errors, all pre-existing** `@types/node` (`src/__tests__/wire.test.ts`, `test/web-readonly-seam.test.ts`, `tools/handshake_cli.ts`, `tools/node_stream.ts`, `tools/roundtrip_cli.ts` — same file set/count as every prior brick's baseline). **0 new.** |
| `npx vitest run` (full) | from `android_tor_spike/app` | **29/29** (2 test files) — current baseline, verified live this session, not assumed |
| `:app:assembleRelease` | `JAVA_HOME=... ./gradlew :app:assembleRelease` | **BUILD SUCCESSFUL** in 12s, apk at `app/build/outputs/apk/release/app-release.apk` (63,814,661 bytes), 365 tasks (30 executed, 335 up-to-date) |

No new tsc errors, no vitest regressions, no JVM regressions, no pytest
regressions. JVM suite grew across the branch 289 → 292 (Task 1, +3) → 295
(Task 2) → 298 (Task 3) → 302 (Task 4) → 305 (Task 5), matching this
session's independent fresh re-run exactly.

## Parity proof — Task 5's loopback gate (the headline proof, the gate that inverts every prior one)

`SyncServeLoopbackTest.kt`
(`android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/SyncServeLoopbackTest.kt`)
is the first gate in this codebase where the DIRECTION is inverted: every
prior loopback gate (`SyncLoopbackTest`, `SyncDmLoopbackTest`,
`GossipServerTest`'s own in-process client, `SyncPairLoopbackTest`, ...) has
had the phone/Kotlin side dial a spawned python node. Here a REAL hearth
node subprocess (`sync_loopback_node.py`'s new `_run_dial` scenario) DIALS
the Kotlin `GossipServer`, running hearth's actual, unmodified initiator
`_sync_session` (the same bound method `Node.deliver_defriends` uses in
production, `sync.py:149`) against `KotlinHandshake.respondHandshake` +
`KotlinSync.serve` answering on the other end. Three scenarios, each its own
`@Test`, each spawning a fresh node process:

- **`ownSiblingPullsPhoneContentAndPhoneIngestsNodesPush`** — the node's
  identity IS the phone fixture's identity (a genuine sibling device of the
  same identity). Asserts BOTH directions of one bidirectional round for
  real: the node's `received_ids` contains the phone's seeded message (a
  real pull through `KotlinSync.serve`'s MESSAGES-phase give side), and the
  phone's own in-process store (the same `InMemorySyncStore` `GossipServer`
  holds) now contains the node's pushed message (a real ingest through the
  MESSAGES-phase read side).
- **`friendReceivesOnlyEntitledContentOverServeNegative`** — THE SECURITY
  PROOF. The node's identity is a friend the phone already knows. Seeds,
  under the phone's own identity+device: (a) a kreds POST wrapped to the
  friend's device (entitled — should arrive); (b) an inner-ring RING record
  (author-private — must never route to anyone but the author); (c) a kreds
  POST with an EMPTY wraps dict (present but not addressed to the friend —
  the direct over-serve shape); (d) a DM to an unrelated third party (DMs
  relay only author↔recipient). Asserts the node's `received_ids` contains
  ONLY (a) and none of (b)/(c)/(d) — the give-side entitlement filter
  (`KotlinSync.serve` → `SyncStore.messagesNotIn` → `filterMessagesNotIn`,
  Task 1) proven to under-serve correctly against a REAL cryptographically
  authenticated peer on the real wire, not a mock `Stream`.
- **`strangerIsRefusedAtAuthNothingServed`** — the node's identity is never
  added to the phone's store. Asserts the dial fails with `PeerRefused`
  specifically (not a generic I/O failure), and that the refusing party's
  identity in the event equals the PHONE's own `identity_pub` — the
  strongest available proof it was genuinely this phone's refusal.

The gate found the real graceful-close bug documented above (flaked 2/2
before the fix, de-flaked empirically after). Reviewer re-ran fresh:
305/305 JVM, pytest 1105/9skip — the same totals this session's independent
sweep reproduced.

## On-device — G20, HONESTLY (Claude-driven capability probe; full friend-graph DoD remains August's)

Per this task's framing: real Tor reachability is arc 2, and a full
friend-graph behavioral round (own/friend/stranger against a real synced
identity) is task-6-brief's Step 3, which stays August's to drive. What
follows is the narrower "is the accept loop alive on real Android"
capability check, done as honestly and with as much real evidence as could
be gathered without touching the device's real synced identity data or
performing a friend-graph action.

**Install.** Device G20, serial `ZY32DLZQ2N`, package `eu.kreds.torspike`,
RELEASE apk only, built against HEAD `dc288a8`.

```
adb shell am force-stop eu.kreds.torspike   -> (no output, success)
adb install -r -d app-release.apk           -> "Performing Streamed Install" / "Success"
```

Same Play Protect gotcha as every prior sideload cycle: `dumpsys window |
grep mCurrentFocus` showed `PlayProtectDialogsActivity` in focus mid-install;
`adb exec-out screencap -p` confirmed the "Vil du sende appen til
sikkerhedstjek?" prompt for `KredsTorSpike`, dismissed via a tap on "Send
ikke". Confirmed fresh via `adb shell dumpsys package eu.kreds.torspike`:
`firstInstallTime=2026-07-19 05:20:15` (unchanged, predates this session),
`lastUpdateTime=2026-07-23 14:08:26` (this session's install) — the
reinstall updated the APK in place without clearing app storage, so the
device's existing real pairing (from the prior regrants-branch merge
session) survived.

**Launch.** `adb shell monkey -p eu.kreds.torspike -c
android.intent.category.LAUNCHER 1`. Note: `monkey`'s own output listed 32
"New tombstone found" lines and a "First NativeCrash" line — checked and
this is a **monkey false positive, not a real crash**: `adb shell ls -la
/data/tombstones/` shows every one of those files dated 2023-03 through
2023-08 (factory-era, long before this project touched the device) except
one from 2026-07-19 (four days before this session, from prior brick work)
— NONE dated today. `monkey` has no prior-run baseline to diff against, so
it reports every pre-existing tombstone file as "new" on its first
invocation each session; this is a documented quirk of the tool, not
evidence this launch crashed anything. Confirmed the app itself did not
crash: `adb shell ps -A | grep kreds` shows the process alive, and `adb
shell dumpsys window | grep mCurrentFocus` shows `eu.kreds.torspike/
eu.kreds.torspike.MainActivity` in focus after launch.

**Logcat check (as instructed).** `adb logcat -c` before launch, `adb
logcat -d | grep -iE "gossip|TorNodeService|accept.?loop|ServerSocket"`
after: **zero matches.** This is expected, not a failure to find something
that exists — verified directly against source: `grep -n "Log\."
GossipServer.kt TorNodeService.kt LocalWebServer.kt` returns nothing; there
is not one `Log.*` call in any of these three files. **Stated plainly, per
the instruction: no startup log line exists to confirm from logcat; the
module genuinely does not log at this level (the pre-existing ticket
"silent accept-loop death (no log, matches module convention)" — carried
below — is exactly this).**

**What WAS found (bound-port evidence via adb shell, escalated one step further).**

1. `adb shell dumpsys activity services eu.kreds.torspike` shows
   `TorNodeService` running with `isForeground=true`,
   `startForegroundCount=1`, `foregroundNoti=Notification(channel=kreds-node
   ...)` — direct, positive confirmation that `startNode()` ran (it is the
   only code path that calls `startForeground` with that channel), and that
   `startGossipServer()` (called unconditionally and synchronously right
   after, before Tor even bootstraps, per `TorNodeService.kt:69-74`) had the
   opportunity to run.
2. `adb shell cat /proc/net/tcp` / `/proc/net/tcp6`, filtered to
   `LISTEN` (state `0A`): exactly **4** listening sockets exist on the whole
   device, all under this app's uid (`10328`):
   - `127.0.0.1:39050` and `127.0.0.1:39051` — identified with certainty as
     Tor's own `SOCKS_PORT`/`CONTROL_PORT` by matching `TorEngine.kt`'s own
     constants (`SOCKS_PORT = 39050`, `CONTROL_PORT = 39051`) exactly.
   - `[::ffff:127.0.0.1]:44529` — repeatedly hit (multiple `TIME_WAIT`
     entries against it observed across two separate snapshots), consistent
     with a repeatedly-fetched local HTTP server — the best-fit candidate is
     `LocalWebServer` (the WebView UI's asset server; the WebView is
     confirmed actually rendering per logcat's `ReactNativeJS: Running
     "main"` and the visible `MainActivity`).
   - `[::1]:45769` — a bare IPv6-loopback listener with **no** connections
     against it in either snapshot (consistent with an idle server nobody
     has dialed) and not attributable to any other loopback-`ServerSocket`
     class in this module (`MediaServer` did not start — no video playback
     occurred this session). By elimination, the only unaccounted-for
     `ServerSocket`-opening class left is `GossipServer`.
3. **Escalated beyond a passive bound-port read**, since the instruction
   explicitly permits an adb-forwarded local client as evidence: `adb
   forward tcp:47691 tcp:45769`, then a plain Python `socket.connect(('127.
   0.0.1', 47691))` from this machine — **connection accepted**, sent 5
   garbage bytes (`\x00\x00\x00\x04PING`, not a valid frame), and `recv()`
   returned `b''` (clean EOF, not a reset or a timeout) — the exact
   fingerprint of `GossipServer.gracefulClose()`: the garbage frame fails to
   parse inside `respondHandshake`, the per-connection `catch` swallows it,
   and `gracefulClose` runs `shutdownOutput()` (clean FIN) → drain-to-EOF →
   `close()`, which is precisely what a clean-EOF `recv()` on the other end
   looks like (a bare `close()` with unread data — the bug Task 5 fixed —
   would instead have produced a reset). Re-checked immediately after: the
   service was still running and the same port was still `LISTEN` — the
   accept loop survived the malformed connection, matching the "one bad
   connection never kills the loop" guarantee (`GossipServer.kt`'s own
   per-connection catch-all doc).

**What this does and does NOT prove, stated plainly.** This is real,
reproducible, on-device evidence that `GossipServer`'s accept loop is alive
under the real foreground service on real Android, accepting real TCP
connections, and closing them the documented graceful way after a failed
parse — genuinely stronger than "inferred from the service running," and
port-attribution to `GossipServer` specifically (as opposed to a log line
naming it) rests on elimination + the graceful-close behavioral fingerprint,
not a direct label. It does **NOT** constitute a completed authenticated
handshake+serve round: no real identity/cert was presented in this probe
(deliberately — this session did not touch the device's real synced
identity data or attempt a friend-graph action), so it is not the "own
sibling / friend / stranger" round-trip proof task-6-brief's Step 3 asks
for. That full proof exists ONLY as the JVM real-socket parity gate (Task
5, above) today; a genuine on-device authenticated round against the
phone's real paired identity remains open, is August's to drive per the
brief, and real Tor reachability (a friend actually dialing the phone's
onion address from outside) remains arc 2 regardless of anything proven
here.

## Honest boundary

Reproduced verbatim from the design spec's "Honest limits" section
(`docs/superpowers/specs/2026-07-23-kotlin-gossip-server-design.md`):

> - Loopback-only until arc 2; no friend can actually reach the phone yet.
> - Arc 1 does not add outbound peers (the phone still dials only its home
>   node); it teaches the phone to answer, not to reach out to friends.
> - The both-offline availability problem is untouched (arc 4).
> - Serving correctness rests on the ported `messages_not_in` matching
>   hearth exactly; the loopback gate's real-node initiator + the over-serve
>   negative are what prove it.

## Follow-up tickets (non-blocking, carried from the ledger)

- **Revoked-device parity / the phone has no revocation model at all**
  (Task 2, cross-arc). `SyncStore.kt:220` documents "models no revocations";
  `deviceViews` is a plain `Set` with no revocation-awareness, so hearth's
  revoked-device refusal (`sync.py:635-641`) has no phone-side equivalent
  yet. Ties to the queued revocation-self-wipe slice (arc C) and is an
  arc-wide gap, not specific to this server.
- **Self-revoked-skips-DEFRIENDS at the `run()` level** (Task 3, inherited
  pre-existing). The initiator's self-revoked early-return mid-REVOCATIONS
  skips DEFRIENDS, where hearth completes DEFRIENDS first
  (`sync.py:723-731`) — an interop follow-up, not introduced by this arc but
  now sitting next to code that mirrors the responder side of the same
  phase.
- **SQLite give-side path is JVM-untestable — MITIGATED here.** The
  module-wide Robolectric gap (pre-existing) means `SqliteSyncStore`'s
  `messagesNotIn` wiring can't be exercised in the JVM suite; this task's
  on-device install is the mitigation `GossipServer` genuinely uses
  `SqliteSyncStore` on real Android (confirmed running, above) — but no
  on-device test actually drove a real authenticated serve through it this
  session (see "What this does and does NOT prove"), so the column-index
  wiring remains hand-verified rather than run-verified on-device.
- **`stop()`'s `shutdownNow` can't interrupt a blocking socket read** (Task
  4). `store.close()` during teardown can race a stalled worker up to the
  full 30s `SOCKET_TIMEOUT_MS` — caught and swallowed, not corruption, but
  not instant either.
- **Silent accept-loop death** (Task 4). If the accept thread's own loop
  ever exits abnormally (not via `stop()`'s deliberate `ServerSocket.close`
  triggering the `catch...break`), there is no log line anywhere to show it
  died — matches this module's existing no-logging convention throughout,
  confirmed again this session (zero `Log.*` calls in `GossipServer.kt`/
  `TorNodeService.kt`/`LocalWebServer.kt`).
- **Worker-pool unbounded queue** (Task 4). `POOL_SIZE=4`
  (`Executors.newFixedThreadPool(4)`) has an unbounded internal task queue;
  low risk given the loopback-only bind (no external attacker can flood it
  pre-arc-2), but a queue depth cap is undone work if this ever needs
  hardening ahead of arc 2's onion exposure.

**Forward-looking (next arcs, from the spec's own "Out of scope"):**

- **Arc 2 — the phone onion service.** Loopback-proven now; the actual
  `.onion` hidden-service forwarding rule that makes this server reachable
  from outside the device is not yet wired. This is the single biggest gap
  between what this report proves and a friend actually being able to reach
  the phone.
- **Arc 3 — friend peer table + phone-dials-friends directly.** This arc
  only ANSWERS; the phone still dials only its home node outbound. A peer
  table and phone-initiated friend dials are unbuilt.
- **Arc 4 — store-and-forward / the both-offline problem.** Untouched;
  explicitly out of scope for this arc.
- **The nudge/liveness channel** (paused slice B) — per the spec, folds onto
  this server next as an immediate follow-on (a held connection + push
  frames), once arc 2 makes the server reachable enough for that to matter.

## Run gotchas

- **A Play Protect scan-consent dialog can silently block `adb install`**
  (repeated, well-documented occurrence across every prior sideload cycle
  on this device). Check `dumpsys window | grep mCurrentFocus` for
  `PlayProtectDialogsActivity` before assuming a transfer stall; `adb exec-
  out screencap -p` to find the exact "Send ikke" coordinates (they can
  shift with device language/resolution).
- **`adb shell monkey` reports pre-existing tombstones as "new" on its
  first invocation each session** — it has no prior-run baseline to diff
  against. Verify against `adb shell ls -la /data/tombstones/`'s actual
  file timestamps before treating a monkey-reported "New tombstone
  found"/"NativeCrash" line as evidence of a crash from THIS launch; in
  this session every reported tombstone predated the launch (2023-era, one
  four days old), none matched today's timestamp, and the app process was
  confirmed alive by `ps`/`dumpsys window` immediately after.
- **`/proc/net/tcp` only shows IPv4-bound sockets; a loopback bind can land
  in `/proc/net/tcp6` instead**, either as a bare `::1` entry or an IPv4-
  mapped `::ffff:127.0.0.1` entry, depending on how the JVM/OS resolved
  `InetAddress.getLoopbackAddress()`/`getByName("127.0.0.1")` on this
  device. Check both tables when hunting for a bound port by elimination —
  this session's GossipServer candidate port was invisible in `/proc/net/
  tcp` and only showed up in `/proc/net/tcp6`.
- **`adb forward tcp:<local> tcp:<device>` reaches a loopback-bound socket
  on the device without touching Tor at all** — useful for a pure
  capability/liveness probe (TCP-level accept/close behavior) without
  needing real crypto or a friend-graph fixture. Remove the forward after
  (`adb forward --remove tcp:<local>`) since it's a standing tunnel.
- **RELEASE-apk-only + force-stop-first are still the field rules** carried
  from every prior brick: the debug apk fails to load the JS bundle on this
  device config, and skipping `am force-stop` before `adb install -r` can
  hang the install.

## After the run

On a pass, whether this merges to public main is August's call, same as
every prior brick. PAUSE here for human review per the task brief. This
closes arc 1 (phone-as-full-node's answering side) at the desk/capability-
probe level; the full friend-graph on-device DoD (task-6-brief's Step 3) —
own/friend/stranger against the phone's REAL paired identity, driven live —
remains August's to run whenever he chooses; the honest state of that
checklist item is "not yet run," not "passed."
