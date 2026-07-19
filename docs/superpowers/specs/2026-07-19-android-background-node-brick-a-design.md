# Android background node — Brick A (design, 2026-07-19)

First real slice of the Kreds Android client, following the Tor-dial spike
(proven on the G20 and merged to main via PR #1, 2026-07-19). The spike
gives us three known-good foundations reused here unchanged: `wire.ts`,
`handshake.ts`, and the `TorManager` Expo module
(`android_tor_spike/app/...`).

## Where this sits (decomposition)

The chosen destination is a **persistent background Android node** — the
phone stays a genuinely reachable node in the background, not just an
intermittent foreground satellite. That is too large for one spec and has
a dependency knot (the lifecycle infrastructure and the content-sync
payload are separable, and the payload is not yet ported). It decomposes
into three bricks:

- **Brick A (THIS SPEC) — Background-node lifecycle infrastructure.** A
  continuous foreground `Service` owns Tor and fires a periodic **AUTH
  heartbeat** (the spike's proven handshake) as the payload. Proves a Tor
  node survives Android's background/Doze/process-death regime, end to end,
  with the simplest real payload — needing nothing from the unported
  content layer.
- **Brick B (later) — Content-sync port.** HAVE/MESSAGES/BLOBS + X25519 /
  content-key decryption + message formats → TypeScript. The real payload.
- **Brick C (later) — Swap the heartbeat for real sync** inside the
  already-built scheduler, plus battery/frequency tuning.

Brick A is first because it is exactly the "Tor lifecycle first" priority,
it is spike-shaped (prove the riskiest new thing — a Tor node under
Android background constraints — in isolation), and it reuses the proven
handshake so it depends on nothing unbuilt.

## Deliberate divergence from the satellite model

The locked satellite model (see mobile-architecture notes) has the phone
as **foreground-only**, driven specifically by the iOS constraint that
forbids persistent background execution. Android has no such constraint, so
Brick A uses a foreground `Service` to make the **Android client more
capable than the iOS one** — a conscious "use the platform for what it can
do" divergence, not drift. The iOS foreground-only model still holds for
the eventual iOS client; the two platforms will maintain two lifecycle
designs.

## What Brick A proves (and what it does not)

**Proves:** a continuously-running foreground `Service` keeps Tor
bootstrapped and the node reachable across app background, Android Doze,
OEM battery-killing, and process death — completing real authenticated
(HELLO/AUTH) reconnections to the home node over Tor on a schedule,
unattended.

**Does NOT (deferred, by decomposition):** content sync
(HAVE/MESSAGES/BLOBS + decryption) — the payload stays AUTH-only; the real
device-enrollment ceremony — enrollment stays the adb-pushed fixture stub;
the iOS lifecycle; multi-ABI; notification/UI polish; an in-app
interval-config UI.

## Decisions (August, 2026-07-19)

- **Keep-alive: continuous foreground service.** Tor bootstraps **once**
  when the service starts and stays up; an in-process timer fires the
  heartbeat every N minutes (cheap — reuses the live circuit). A foreground
  service + notification is largely exempt from Doze's network suspension,
  so the node stays genuinely reachable when idle. Continuous modest
  battery drain is the accepted cost of "persistent."
- **Heartbeat payload = the proven handshake, PORTED TO KOTLIN**
  (amended 2026-07-19). The background heartbeat must run with no live JS
  runtime (Doze, and especially after a `START_STICKY` process-restart
  where no Activity/RN context exists), so the wire layer (canonical JSON,
  length-frames, Ed25519) and the HELLO/AUTH+probe are re-ported to Kotlin
  and validated against the **same committed `wire_vectors.json`** plus a
  loopback check against the real node. `wire.ts`/`handshake.ts` remain the
  source for the foreground/future-content path; the background heartbeat
  is pure native so it survives Doze/process-death without a JS runtime.
  The heartbeat stops at AUTH-accepted; no content ever flows.
- **Interval N = 5 minutes** default (short enough to observe while
  dogfooding; configurable later, not in this slice).
- **Enrollment stays the adb-pushed fixture stub** — Brick A does not touch
  pairing. The service reads the same `spike_phone_fixture.json`.

## The load-bearing change: Tor ownership moves Activity → Service

Today Tor lives in the RN native module bound to the Activity's
`appContext`; destroying the Activity (background/kill) tears Tor down.
Brick A moves Tor ownership into a **started foreground `Service`**
(`startForeground` + persistent notification) that outlives the Activity.
The RN module becomes a thin client that talks *to* the service; the
Activity coming and going no longer touches Tor. This is the central new
engineering work; the existing `TorManager` narrow interface
(`bootstrap()/socksPort()/dial()/suspend()`) is reused unchanged, only its
*owner* changes.

## Components

- **`TorNodeService` (Android foreground Service, new).** The owner. On
  start: reads the fixture, drives `TorManager.bootstrap()` once and keeps
  it up, then runs an in-process scheduled timer firing the heartbeat every
  N minutes. Holds the `startForeground` notification. `START_STICKY` so
  the OS restarts it after process death. Owns clean shutdown
  (`TorManager.suspend()` + `stopForeground`) on explicit stop.
- **`TorManager` (existing, unchanged).** Reused as-is
  (bootstrap/dial/suspend + the Dispatchers.IO fix + the `send`-returns-Unit
  fix). Owner changes from RN module to the Service.
- **`TorEngine` (process-global Kotlin singleton, new).** The Tor-owning
  state (tor thread, SOCKS conns, bootstrap/dial/send/recv/close/suspend)
  is extracted out of `TorManagerModule` into a process-global object so it
  survives Activity destruction and is shared by both the Module (foreground
  JS calls) and the Service (background heartbeat). The Module becomes a thin
  delegator; its JS interface is unchanged.
- **`KotlinWire` + `KotlinHandshake` (new, native heartbeat).** The Kotlin
  port of the wire layer and HELLO/AUTH+probe, gated by the committed
  vectors. Each beat: `TorEngine.dial(onion, 9997)` → `KotlinHandshake`
  accepted-path → record `{timestamp, ok, latencyMs, reason?}` → disconnect.
  Ed25519 via BouncyCastle (API 30 has no platform Ed25519).
- **Notification** — required by the foreground service and the primary
  observability surface: `Kreds node up · last beat HH:MM ✓ · <n> beats`
  (or `✗ <reason>`). Tapping opens the app. A notification action stops the
  node.
- **Heartbeat history (persisted ring buffer)** — the last ~50 beats
  (timestamp, ok, latency, reason) persisted so the record survives Activity
  restarts and can be reviewed after backgrounded runs.
- **RN status screen (evolves `App.tsx`).** Start/stop the service, show
  service state, list the last N heartbeats (time + ✓/✗ + latency), and a
  "beat now" button. The spike's one-shot Connect flow is subsumed here.
- **Battery-optimization exemption prompt.** On first run, request
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Motorola's OEM killer is
  aggressive; without the exemption even a foreground service can be reaped,
  silently defeating the slice.

## Data flow

```
app launch ─► start TorNodeService ─► startForeground(notification)
                     │
                     ├─ read fixture (spike_phone_fixture.json)
                     ├─ TorManager.bootstrap()          (once, stays up)
                     └─ every N min ─► dial(onion,9997) ─► handshake (AUTH)
                                            │
                                            ├─ record {ts, ok, latencyMs, reason?}
                                            ├─ update notification
                                            └─ push to RN screen (if foreground)
Activity backgrounded/destroyed ─► service + Tor keep running ─► beats continue
Process killed by OS ─► START_STICKY restarts service ─► re-bootstrap ─► resume
User stops node (screen or notification action)
        ─► timer cancel ─► TorManager.suspend() ─► stopForeground
```

## On-device verification plan (the Brick A proof)

- **Background continuity** — background the app; confirm beats keep firing
  (notification count climbs / logcat).
- **Doze survival** — `adb shell dumpsys deviceidle force-idle`; confirm a
  beat still lands; `adb shell dumpsys deviceidle unforce` to restore.
- **Process-death recovery** — `adb shell am kill eu.kreds.torspike`; the
  `START_STICKY` service restarts, re-bootstraps Tor, resumes beats
  unattended.
- **OEM-killer + battery** — leave it running ~1 hour on the G20 (Motorola's
  killer is the real adversary); confirm survival and a rough battery read.
- **Battery-exemption** — confirm the prompt appears and that granting it
  improves survival.

## Testing

- **Automated / adb-driven (Claude):** the build gate (both APK variants
  compile, service + module packaged), and the adb scripting for
  Doze-force-idle, `am kill`, and logcat capture of heartbeat lines.
- **Phone-side (August):** the eyeball on notification/history over a real
  backgrounded run and the "is overnight battery acceptable" judgment.
- **New Kotlin-port gate (desk, JVM):** a gradle JVM unit test loads the
  same committed `android_tor_spike/fixtures/wire_vectors.json` and asserts
  `KotlinWire` reproduces every canonical byte sequence and verifies every
  Ed25519 signature/cert — the identical contract `wire.test.ts` enforces,
  so the Kotlin port cannot silently drift from Python. The existing spike
  pytest 9/9 + vitest 20/20 continue to guard `wire.ts`/`handshake.ts`
  (unchanged).
- **Loopback check (desk):** the `test_handshake_desk.py` real-node
  listener remains; the Kotlin handshake's ultimate correctness proof is
  the on-device heartbeat completing AUTH against the real node.

## Definition of done

The G20, backgrounded and Doze-forced, keeps completing AUTH heartbeats to
the home node over Tor on the N-minute schedule — visible in the persistent
notification and the in-app history — survives a process kill and resumes
unattended, with the battery-optimization exemption prompt in place.

## Risks / honest unknowns (resolve during build, none blocking)

- **Expo dev-build + a raw Android Service.** Expo local modules don't
  ship a Service scaffold; the Service is hand-added to the module's
  Android sources and started from the module. Confirm the module's
  manifest merges the `<service android:foregroundServiceType=...>` entry
  and the `FOREGROUND_SERVICE` / `POST_NOTIFICATIONS` (API 33+)
  permissions.
- **`foregroundServiceType` on API 34+.** Newer Android requires a declared
  type; `dataSync` (or `connectedDevice`) is the likely fit. The G20 is API
  30 (no type required there), but declare it for forward-compatibility.
- **OEM killers vs. a foreground service.** Motorola may still reap the
  service despite the exemption; the ~1-hour run is the real test, and the
  exemption prompt is the mitigation. Documented honestly if it still gets
  killed.
- **Heartbeat vs. a live circuit that has gone stale.** After a long idle,
  the reused circuit may be dead; the heartbeat's `dial` should tolerate a
  failed reuse and let `TorManager` rebuild, recording a `✗` beat rather
  than crashing the service.
- **RN ↔ Service communication.** The screen needs live service state and
  the heartbeat stream; decide binder/bound-service vs. a broadcast/event
  bridge during build (interface stays: the screen observes, the service
  owns).
- **Kotlin Ed25519 / canonical-JSON fidelity.** The re-port must match
  Python byte-for-byte (sorted keys, compact separators, `ensure_ascii`,
  the `enrolled_at` float rendering). The committed-vector JVM gate is the
  guard; BouncyCastle supplies Ed25519 on API 30.

## Out of scope (named, so it does not creep in)

Content sync (Brick B); the real device-enrollment ceremony (fixture stub
retained); the iOS lifecycle; multi-ABI builds; notification/UI polish; an
in-app interval-configuration UI; battery-usage optimization beyond "does
it survive and is it tolerable."
