# Brick A report — background-node lifecycle

**Status: CODE COMPLETE, on-device run PENDING.** All 8 code tasks are
implemented and reviewed; the whole-branch review has run. The remaining
step is the human-driven G20 run (the `[PENDING RUN]` sections below are
filled after it).

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

## [PENDING RUN] Verdict (per DoD)

_Filled after the run:_
- Background continuity (beats continue backgrounded)? 
- Doze survival (beat lands under force-idle)? 
- Process-death recovery (resumes after `am kill`)? 
- ~1h survival + battery read? 
- Battery-exemption prompt present + effective? 

## [PENDING RUN] Timings + issues

_Filled after the run:_ cold vs warm bootstrap, heartbeat latency (from the
`OK <ms>` rows), and any bug found + fix.
