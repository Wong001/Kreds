# Brick A report — background-node lifecycle

**Status: PROVEN ON HARDWARE (2026-07-19, G20).** All six DoD checks pass.
The persistent background node bootstraps Tor, beats every 5 minutes with
real authenticated (HELLO/AUTH) reconnections to the home node over Tor,
survives backgrounding + deep Doze, self-recovers from a process kill via
`START_STICKY`, and ran a full hour on battery (screen-locked) with the same
process alive throughout — no OEM-killer gap, negligible drain. Brick A's
goal (prove the persistent-background-node lifecycle) is met; content sync
(Brick B) is the next slice.

Spec: `docs/superpowers/specs/2026-07-19-android-background-node-brick-a-design.md`
Plan: `docs/superpowers/plans/2026-07-19-android-background-node-brick-a.md`

## What Brick A builds

A continuous foreground `TorNodeService` (`START_STICKY`) that owns Tor and
fires a periodic **native (Kotlin) AUTH heartbeat** to the home node over
Tor — proving the persistent-background-node lifecycle before content sync
is ported. Everything runs with no JS runtime, so it survives Doze and
process-death.

- **`TorEngine`** — process-global Tor owner extracted from the RN module
  (survives Activity destruction; shared by module + service).
- **`KotlinWire`** — canonical JSON / frames / Ed25519 (BouncyCastle),
  vector-gated against the same committed `wire_vectors.json` the TS port
  uses. Float fidelity for real timestamps proven empirically (millions of
  differential values on JDK 17, zero mismatches).
- **`KotlinHandshake`** — native HELLO/AUTH + probe, mirrors `handshake.ts`.
- **`HeartbeatStore`** — persisted 50-beat ring buffer.
- **`TorNodeService`** — foreground service: bootstrap once, heartbeat
  every 5 min, notification, persisted beats, `START_STICKY`.
- **Module surface + dashboard** — start/stop/beat-now/history + beat/state
  events + battery-exemption; RN dashboard screen.

## Desk gates (green)

- Kotlin JVM vector gate: 5/5 (`KotlinWire` canonical/auth/cert/small-float/frames).
- `HeartbeatStore`: 2/2 (append/cap + JSON round-trip).
- The spike's desk gates remain green (wire vitest 20/20, spike pytest 9/9) —
  `wire.ts`/`handshake.ts` unchanged.
- Both APK variants build; the release APK installs on the G20.

## Deliberate divergence

The Android client uses a foreground service (a persistent background node)
where the iOS client stays foreground-only — a conscious "use the platform"
divergence (iOS forbids background execution; Android doesn't). Two
lifecycle designs, on purpose.

## Known limitations carried into the run / follow-ups (from review)

- **Refused-path detection unverified.** The heartbeat only runs the
  ACCEPTED path operationally (the phone always presents its valid home cert
  to its own node), which works. The REFUSED path (write-before-read races
  the node close) is not exercised in normal operation and its detection
  over Tor/SOCKS on Android is unverified — a misconfigured heartbeat might
  show `FAIL io` where `refused` is expected. A future refused-path test
  would pin it.
- **Dashboard `state:` line goes stale on remount.** `nodeState` is a
  non-sticky broadcast with no synchronous query, so reopening the app over
  an already-running service shows `state: stopped` until the next
  transition. The **notification is the authoritative live status** (the
  service updates it every beat) and the history list is accurate.
  Follow-up: persist node-state + a `getNodeState()` read on mount.
- **Battery-exemption re-check timing.** The 500ms re-check fires before the
  user answers the system dialog; the banner may persist until a re-render.
  Follow-up: an `AppState` resume listener.
- **ART vs JVM float repr (watch on the run).** `KotlinWire`'s float
  rendering is vector-proven on OpenJDK 17, but the heartbeat runs on
  Android's ART, whose `Double.toString` is not proven digit-identical to
  Python for the runtime cert `enrolled_at` (a value in no vector). If it
  diverges, EVERY beat fails uniformly with `hello: node cert failed
  verification` — that uniform pattern means float-repr divergence, NOT a
  network problem. Cheap future de-risk: an on-device (androidTest/ART)
  assertion that `KotlinWire` reproduces the committed vectors.
- **No auto-recovery from bootstrap-failure / tor-thread death.** The beat
  cadence is armed only on bootstrap success. If the initial cold bootstrap
  times out (`TOR_TIMEOUT`/`TOR_DIED` — possible if Doze lands mid-bootstrap)
  the service stays foreground but idle (no retry); if the tor thread dies
  after cadence starts, beats FAIL every 5 min with no re-bootstrap.
  `START_STICKY` only rescues full process death. In practice the foreground
  service keeps Tor up through Doze, so the risk is mainly the
  bootstrap-never-completed case. Watch for a permanently-stuck
  `Tor bootstrap X%` or an unbroken FAIL streak. Follow-up (natural Brick-A
  hardening): re-bootstrap on tor-death + retry on bootstrap timeout.

## On-device run (ON_DEVICE_CHECKLIST steps 1-2 for mint/push, then)

1. `. .\android_tor_spike\tools\env.ps1`
2. `adb -s ZY32DLZQ2N install -r android_tor_spike\app\android\app\build\outputs\apk\release\app-release.apk`
3. Open the app, grant the battery-optimization exemption if prompted, tap **Start node**.
4. Confirm the notification appears and beats land (notification count climbs; in-app history fills with `OK <ms>` rows).
5. Background the app; confirm beats continue (notification).
6. `. .\android_tor_spike\tools\brick_a_ondevice.ps1` then run `DozeOn`, wait ~6 min, confirm a beat still lands, `DozeOff`.
7. `KillApp`; confirm `START_STICKY` restarts the service, Tor warm-re-bootstraps, beats resume unattended.
8. Leave it ~1 hour; confirm survival (Motorola OEM killer) + a rough battery read.
9. If any beat fails: `PullLogs`, share the notification line + `logcat.txt` / `tor.log`.

## On-device result (2026-07-19, G20)

**Core PROVEN.** Foreground `TorNodeService` bootstrapped Tor to 100% on the
phone (warm re-bootstrap ~5 s, reusing cached consensus; SOCKS on 39050),
and the native Kotlin heartbeat completed real HELLO/AUTH over a live Tor
circuit against the home node — **all beats green, latency 1780–3574 ms.**

- **ART float-repr risk RESOLVED.** Green beats mean the cert verified on
  Android's ART, so `KotlinWire`'s float rendering matches Python on the
  real runtime (not just the JVM the vector gate runs on). The review's
  top latent risk did not materialize.
- **Native heartbeat runs with no JS runtime** — the whole beat path is
  Kotlin (TorEngine/KotlinHandshake/KotlinWire), as designed for
  Doze/process-death survival.

DoD lifecycle checks:
- [x] **Background continuity** — survived 4m10s backgrounded, still up, beats continued.
- [x] **Process-death recovery** — `am kill` bounced off (foreground service resists background reclamation — a positive persistence result); `am crash` killed the process (pid 27459→28074), and `START_STICKY` restarted the service unattended: foreground restored, Tor warm-re-bootstrapped ~4 s, cadence resumed. No intervention.
- [x] **Doze survival** — deep Doze forced (state IDLE, held through screen-on) 14:02:57; a green beat landed at 14:11 while in deep idle. The foreground service + Doze whitelist kept the network; the native heartbeat completed AUTH over Tor under Doze.
- [x] **~1h survival + battery read** — ran ~1h on battery, screen-locked. Beats landed every 5 min with no gap (no OEM-killer reap); the SAME process (pid 28074, from the earlier crash-recovery) stayed alive the whole hour with Tor continuously up (no re-bootstrap in tor.log). Battery 97% → 98% (charging on reconnect) = negligible net drain.
  - **Battery caveat (interpretation):** this test unit is a heavily-aged device — years of daily cycling followed by ~4-5 years of dormant shelf storage, so the cell has both severe cycle aging AND calendar aging. A degraded cell reports a much steeper %-drop per unit energy (low effective capacity), so the hour's %-drain is only DIRECTIONAL, not a representative power cost — a real figure needs a healthy device / longer controlled baseline. The load-bearing result of this box is *survival* (beats kept landing across the hour, no OEM-killer gap), not the %.
- [x] **Battery-exemption** — granted; app confirmed on the Doze whitelist (`user,eu.kreds.torspike`), which is what exempts the heartbeat from Doze network restrictions.

### Timings observed
- Cold bootstrap (earlier run): reached 100% normally.
- Warm re-bootstrap (this run): ~5 s (cached consensus).
- Heartbeat round-trip (dial + HELLO/AUTH over Tor): 1780–3574 ms.
