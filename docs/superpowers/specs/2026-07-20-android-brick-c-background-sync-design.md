# Android Brick C (background the content sync) — design, 2026-07-20

Seventh slice of the Kreds Android client, after the Tor-dial spike (PR #1),
Brick A (#2), B.1 (#3), B.2 (#4), B.2c (#5), B.2d-1 (#6). Brick A gave the
phone a persistent foreground node that survives Doze/process-death, proven
by a periodic AUTH heartbeat. B.1/B.2/B.2c/B.2d-1 made content sync +
readable + rendered, but sync runs only in the FOREGROUND (a manual "Sync
now" while the app is open). Brick C makes the content sync run in the
background service so the store stays fresh and opening the app shows current
content instantly, with no 1-2 min sync wait.

## Goal (August, 2026-07-20): fresh-on-open, no notification

The background sync silently keeps the phone's SQLite store up to date. No
notifications this slice (a later slice, if wanted, adds a content-free "new
activity" ping). "Fresh on open" falls out for free: the background pull
keeps the store current, and the existing `getFeed`-on-mount already
decrypts from the store, so opening the app shows fresh content without a
network wait.

## The infrastructure is already there

`TorNodeService` (Brick A) is a START_STICKY, Doze-exempt foreground service
that keeps Tor up (`TorEngine`) and runs a periodic task every 5 min
(`scheduleAtFixedRate` on a scheduler thread) — but that task only does the
bare AUTH heartbeat (`KotlinHandshake.run`), NOT the content sync. Brick C
runs the real content sync in that task instead. Everything the sync needs
is already in the service: Tor, the fixture, the SQLite store.

## Architecture

Extract the sync TRANSPORT (JS-runtime-free) into a shared `SyncRunner`
object that both the background service and the foreground module call —
mirroring how Brick A extracted the handshake to `KotlinHandshake` so the
heartbeat runs with no JS. The background service runs pure transport (pull +
store ENCRYPTED); the foreground module wraps `SyncRunner` and adds the
foreground-only DecryptPass + feed cache + `onSyncProgress` events.
**Decrypt-on-read is preserved:** no plaintext, no content keys, and no AVIF
decode ever happen in the background — the store stays encrypted; the
foreground decrypts on open.

## Components

### 1. `SyncRunner` (new, JS-free Kotlin)

The transport orchestration currently inline in `TorManagerModule.syncNow`,
relocated verbatim (behavior unchanged, just moved + made callable without
the Expo module):

- `fun runSync(ctx: Context, onProgress: (String, Int) -> Unit = { _, _ -> }): SyncOutcome`
  — build the store (`SqliteSyncStore(ctx)`), AUTH over a `TorEngine.dial`
  stream (`authOnlyOverStream` equivalent), enc-key publish prep
  (`EncKeys.getOrCreate` → `EncKeyPublishGuard.shouldPublish` /
  `store.nextSeq` / `KotlinSync.composeEncKey`), then
  `KotlinSync.run(stream, store, devicePub, outbound, onProgress)`. Returns a
  `SyncOutcome` (ok, messages, blobs, identities, error, selfRevoked). The
  enc-key is the only WRITE; once its published-marker is set (from any
  sync), later syncs are pull-only. NO DecryptPass, NO events emitted from
  here — `onProgress` is an injected callback (foreground passes the
  event-emitter; background passes the default no-op).
- Depends only on `TorEngine`, the store, and the existing Kotlin sync
  objects. Carries the existing stream-close-on-failure contract (the
  fragile-path try/catch that closes the Tor stream if enc-key prep throws
  before `KotlinSync.run` takes ownership — TorManagerModule.kt's current
  logic, preserved).

### 2. Process-wide sync mutex

A single lock (`SyncRunner` holds it) so the background timer and a
foreground "Sync now" tap never run two `KotlinSync.run`s against the same
SQLite store / node concurrently — the exact "overlapping syncNow calls
racing on the same DB file" hazard the current code comments already flag.
Policy: **`tryLock` / skip** — if a sync is already running, the second
caller does NOT queue; it returns a `SyncOutcome` of "already in progress".
A skipped foreground tap is harmless: the in-flight sync is already doing the
work, and the feed refreshes when it completes or on the next mount.

### 3. `TorNodeService` — periodic full sync

The periodic task calls `SyncRunner.runSync(this)` (default no-op progress)
every ~15 min instead of `heartbeat()`'s bare handshake. `HEARTBEAT_INTERVAL_MS`
becomes a 15-min `SYNC_INTERVAL_MS`. START_STICKY / foreground /
Doze-exemption / the `runCatching` never-throw guard around the periodic task
ALL unchanged (a failed sync records a failed outcome but must never cancel
the fixed-rate schedule — Brick A's documented silent-cancel trap). The
recorded "beat" becomes a "sync" outcome (reachable + pulled counts); the
notification/broadcast text updates from "heartbeat" to "sync".

### 4. `TorManagerModule.syncNow` — refactored onto `SyncRunner`

`syncNow` calls `SyncRunner.runSync(context, onProgress = { p, c -> sendEvent("onSyncProgress", ...) })`
for the transport, then (foreground only) runs `DecryptPass.run` →
`feedCache`/`blobKeys` (single Result capture, B.2d-1) and emits the terminal
`nodeSync`. Same external behavior as today; the transport half is now shared
code, not a second copy. The mutex means a manual tap during a background
sync returns "already in progress" (surface it in the existing status line).

### 5. Dashboard (App.tsx) — minimal

The existing "last beat" line becomes "last sync" (time + pulled counts).
`getFeed`-on-mount already decrypts the fresh store, so no new fetch logic is
needed — opening the app shows current content. A "sync already in progress"
tap result shows a brief note rather than an error.

## Data flow

```
[background, ~15 min, no JS]  TorNodeService timer
   -> SyncRunner.runSync (mutex tryLock) -> TorEngine.dial -> KotlinSync.run
   -> store updated ENCRYPTED; SyncOutcome recorded; NO decrypt
[foreground] app open -> getFeed-on-mount decrypts the already-fresh store
   -> feed shows current content instantly (no network wait)
   -> manual "Sync now" -> SyncRunner.runSync (same mutex) + DecryptPass + events
```

## Testing

- **`SyncRunner` mutex (JVM):** two concurrent `runSync` calls with a latch —
  exactly one runs, the other returns "already in progress"; sequential
  calls both run. Deterministic.
- **Transport parity (JVM):** `SyncRunner.runSync` against the existing
  real-node loopback gate pulls the same content the module's inline path
  did — the logic is relocated, not modified, so the gate proves the
  extraction is behavior-preserving. Reuse the B.1/B.2 loopback harness.
- **Service wiring:** the periodic task calls `SyncRunner` and records a sync
  outcome; the `runCatching` guard holds on a failed `runSync` (a failing
  sync does not cancel the schedule) — the same guard shape Brick A tests.
- **Module regression:** existing `syncNow`/feed/`onSyncProgress` behavior
  unchanged after the refactor (module JVM suite + the loopback tests stay
  green).
- **On-device (the real proof):** on the G20, set up + one foreground sync,
  then CLOSE the app, wait ~15+ min, reopen → the feed shows content that
  arrived while the app was closed (proves the background sync ran with no
  app open). Plus Brick A's survival checks: force process death, confirm the
  sync resumes after START_STICKY resurrection; confirm the sync fires under
  Doze (the service is already battery-exempt from Brick A).

## Definition of done

With the app closed, the background service syncs content into the phone's
store every ~15 min; reopening the app shows content that arrived while it
was closed, instantly (no sync wait). Decrypt-on-read preserved (background
stores encrypted only). Desk-proven first (mutex + transport-parity +
service-wiring gates), then on the G20.

## Risks / honest unknowns (resolve during build)

- **Extraction fidelity** — `SyncRunner` must relocate the syncNow transport
  WITHOUT behavior change, including the stream-close-on-failure contract and
  the enc-key publish guard. The loopback parity gate is the guard; diff the
  moved code against the original carefully.
- **Mutex ownership across process components** — the lock must be a single
  process-global instance shared by the service thread and the module's
  ioScope (a top-level object/singleton, not per-instance). Confirm the
  service and the Expo module run in the SAME process (they do — the module
  is not `isolatedProcess`; only `:imagedecode` is).
- **Battery over a real day** — a full Tor pull every 15 min on top of the
  always-on Tor service. Most cycles pull nothing (HAVE shows nothing
  missing), so cost is a dial + empty pull; watch the endurance read on the
  G20 (directional only — aged cell). If it's heavy, adaptive cadence is a
  named follow-up.
- **Foreground/background outcome recording** — both paths now produce a
  `SyncOutcome`; ensure the dashboard's last-sync line reflects whichever ran
  most recently without the two clobbering each other confusingly.

## Out of scope (named)

Notifications of any kind (fresh-on-open only — a later slice); background
decrypt or background AVIF decode (foreground-only, decrypt-on-read
preserved); user-configurable or adaptive/battery-aware cadence (fixed ~15
min this slice); any change to what sync pulls (transport relocated, not
modified); iOS background (Mac-gated).
