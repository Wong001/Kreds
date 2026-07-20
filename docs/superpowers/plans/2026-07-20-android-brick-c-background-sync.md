# Android Brick C — Background Content Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The background foreground-service runs the real content sync every ~15 min (replacing the bare AUTH heartbeat) so the phone's store stays fresh and opening the app shows content that arrived while it was closed — with no notifications and decrypt-on-read preserved (background stores encrypted only).

**Architecture:** Extract the JS-runtime-free sync TRANSPORT out of `TorManagerModule.syncNow` into a shared `SyncRunner` object (mirroring Brick A's `KotlinHandshake` extraction), guarded by a process-wide sync mutex. The background `TorNodeService` and the foreground module both call `SyncRunner.runSync`; only the foreground adds DecryptPass + feed cache + events.

**Tech Stack:** Kotlin (the existing `TorEngine`/`KotlinSync`/`KotlinHandshake`/`EncKeys`/`SqliteSyncStore`/`TorNodeService`/`TorManagerModule`), React Native (App.tsx). No hearth change, no Python.

**Spec:** `docs/superpowers/specs/2026-07-20-android-brick-c-background-sync-design.md`

## Global Constraints

- **Commit messages: NO AI/Co-Authored-By trailers.** Style `feat(brickc):` / `fix(brickc):` / `docs(brickc):` lowercase.
- **Decrypt-on-read preserved:** the background path NEVER decrypts — no DecryptPass, no content keys used for decrypt, no AVIF decode in the background. The store is updated ENCRYPTED; the foreground decrypts on open. DecryptPass + feed cache + `onSyncProgress`/`nodeSync` events stay foreground-only.
- **`SyncRunner` relocates the transport WITHOUT behavior change** — the dial+AUTH+identity-pin, the stream-close-on-failure contract at every fragile step, the enc-key publish guard (`EncKeyPublishGuard` + `nextSeq` + `composeEncKey`), the `setPublishedEncPub`-only-on-full-success rule, and the `KotlinSync.run` call are moved verbatim, not re-designed. The loopback parity gate is the guard.
- **Process-wide mutex, tryLock/skip policy:** a single process-global lock so the background timer and a foreground tap never run two `KotlinSync.run`s concurrently against the same SQLite store/node. If a sync is already running, the second caller returns an "already in progress" outcome (does NOT queue).
- **Never-throw periodic task:** `TorNodeService`'s fixed-rate task keeps Brick A's `runCatching` guard — a failed sync records a failed outcome but must NEVER cancel the schedule (Brick A's documented silent-cancel trap).
- **Same process:** the Expo module and `TorNodeService` run in the SAME process (only `:imagedecode` is isolated), so a top-level Kotlin object/singleton mutex is genuinely shared.
- **App package** `eu.kreds.torspike`; compileSdk 36; the G20 is API 30. Expo v57: any TS/module-surface change follows `android_tor_spike/app/AGENTS.md` (read the versioned docs).
- **Env:** dot-source `android_tor_spike/tools/env.ps1` every PowerShell session touching gradle/adb; gradle from `android_tor_spike\app\android`; generous timeouts (600000 ms). August drives on-device (G20 serial ZY32DLZQ2N); Claude runs desk gates + adb.
- **Pinned facts (2026-07-20):** `KotlinSync.run(stream, store, ownDevicePub, outbound = emptyList(), onProgress = { _, _ -> }): SyncResult` (SyncResult = Ok(messages, blobs, identities)/SelfRevoked/Failed(stage, reason)). syncNow's transport today: `TorEngine.dial` → `KotlinHandshake.authOnlyOverStream(stream, fx)` (returns peerCert) → pin `peerCert.identity_pub == fx.cert.identity_pub` → `SqliteSyncStore(ctx).also{addIdentity(fx.cert.identity_pub)}` → `EncKeys.getOrCreate(store)` + `EncKeyPublishGuard.shouldPublish(pub, store.getPublishedEncPub())` + `store.nextSeq()` + `KotlinSync.composeEncKey(fx, pub, seq, System.currentTimeMillis()/1000.0)` → `KotlinSync.run(...)` → on Ok: `if (shouldPublish) store.setPublishedEncPub(pub)`. Each fragile step closes `stream` on failure. `TorNodeService`: START_STICKY foreground service, `HEARTBEAT_INTERVAL_MS = 300_000L`, `scheduleAtFixedRate({ heartbeat() }, ...)`, `heartbeat()` guarded by `runCatching { recordAndBroadcast(beat) }`, records a `Beat(start, ok, ms, error)` via `HeartbeatStore`. The fixture is `KotlinHandshake.parseFixture` / the service's `fixture()`.

## File Structure

```
android .../tormanager/
  SyncRunner.kt          Task 1: JS-free transport + SyncOutcome + process-wide mutex (new)
  TorManagerModule.kt    Task 1: syncNow refactored onto SyncRunner (transport) + foreground DecryptPass/events
  TorNodeService.kt      Task 2: periodic task calls SyncRunner every 15 min, replacing heartbeat()
  HeartbeatStore.kt      Task 2: Beat carries pulled counts (if not already); "sync" semantics (confirm shape)
  index.ts               Task 3: (if the last-sync event payload changes) — else untouched
android .../src/test/.../
  SyncRunnerTest.kt      Task 1: mutex serialization (JVM)
  SyncLoopbackTest.kt    Task 1: transport-parity via SyncRunner against the real loopback node
android_tor_spike/app/App.tsx   Task 3: "last beat" -> "last sync" + counts + "already in progress" note
android_tor_spike/BRICK_C_REPORT.md   Task 4
```

---

### Task 1: Extract `SyncRunner` (JS-free transport + mutex) + refactor `syncNow`

The load-bearing task. Relocate syncNow's transport into `SyncRunner`, add the process-wide mutex, and refactor the module to call it — proven behavior-preserving by the loopback parity gate.

**Files:**
- Create: `android/src/main/java/expo/modules/tormanager/SyncRunner.kt`
- Modify: `android/src/main/java/expo/modules/tormanager/TorManagerModule.kt` (syncNow)
- Test: `android/src/test/java/expo/modules/tormanager/SyncRunnerTest.kt` (new), `.../SyncLoopbackTest.kt` (add a parity test)

**Interfaces:**
- Produces:
```kotlin
object SyncRunner {
    data class SyncOutcome(
        val ran: Boolean,            // false == skipped because another sync held the lock
        val ok: Boolean,
        val messages: Int, val blobs: Int, val identities: Int,
        val selfRevoked: Boolean,
        val error: String?,
    )
    // Runs the full transport under the process-wide mutex. onProgress is the
    // injected phase callback (foreground passes an event-emitter; background
    // passes the default no-op). NEVER decrypts. Returns ran=false immediately
    // if a sync is already in progress (tryLock).
    fun runSync(ctx: Context, fx: KotlinHandshake.Fixture,
                onProgress: (String, Int) -> Unit = { _, _ -> }): SyncOutcome
}
```
- Consumes: `TorEngine.dial`, `KotlinHandshake.authOnlyOverStream`, `SqliteSyncStore`, `EncKeys.getOrCreate`, `EncKeyPublishGuard`, `KotlinSync.run`/`composeEncKey`, `SyncResult`.
- The foreground `syncNow` additionally uses (after a successful `runSync`): `EncKeys.getOrCreate(store)` (idempotent, returns the persisted priv), `DecryptPass.run`, `feedCache`/`blobKeys`.

- [ ] **Step 1: Write the mutex serialization test** `SyncRunnerTest.kt` — drive the lock directly via a small internal seam so no real Tor/store is needed. Add to `SyncRunner` an internal `@JvmStatic` visible-for-testing entry that runs an arbitrary body under the same lock, OR expose the `tryLock` result. Test: two threads call the guarded body concurrently (first holds via a latch); assert exactly one runs and the other gets the "skipped" path; then sequential calls both run.
```kotlin
package expo.modules.tormanager

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncRunnerTest {
    @Test fun secondConcurrentCallIsSkipped() {
        val ran = AtomicInteger(0); val skipped = AtomicInteger(0)
        val inBody = CountDownLatch(1); val release = CountDownLatch(1)
        val t1 = Thread {
            SyncRunner.withSyncLockForTest(
                body = { ran.incrementAndGet(); inBody.countDown(); release.await() },
                onSkipped = { skipped.incrementAndGet() })
        }
        t1.start(); inBody.await()               // t1 holds the lock
        SyncRunner.withSyncLockForTest(          // t2 on this thread, lock held
            body = { ran.incrementAndGet() }, onSkipped = { skipped.incrementAndGet() })
        release.countDown(); t1.join()
        assertEquals(1, ran.get()); assertEquals(1, skipped.get())
    }
    @Test fun sequentialCallsBothRun() {
        val ran = AtomicInteger(0)
        repeat(2) { SyncRunner.withSyncLockForTest(body = { ran.incrementAndGet() }, onSkipped = {}) }
        assertEquals(2, ran.get())
    }
}
```
> **Implementer:** name the lock helper whatever reads best (`withSyncLockForTest` is a suggestion) but keep the production `runSync` using the SAME lock instance. The lock is a top-level `private val syncLock = java.util.concurrent.locks.ReentrantLock()` in `SyncRunner`; `runSync` does `if (!syncLock.tryLock()) return SyncOutcome(ran=false, ...)` then `try { ... } finally { syncLock.unlock() }`.

- [ ] **Step 2: Run — expect FAIL** (`.\gradlew :tor-manager:testDebugUnitTest --tests "*SyncRunnerTest*"`, unresolved `SyncRunner`).

- [ ] **Step 3: Implement `SyncRunner.kt`** — MOVE the transport out of `TorManagerModule.syncNow` verbatim (read TorManagerModule.kt's syncNow first; relocate the dial→auth→pin→store→enckey-prep→`KotlinSync.run`→`setPublishedEncPub` block). Preserve every stream-close-on-failure branch. `runSync`:
  1. `if (!syncLock.tryLock()) return SyncOutcome(ran=false, ok=false, 0,0,0, false, "sync already in progress")`.
  2. In `try { } finally { syncLock.unlock() }`: `TorEngine.dial` → `authOnlyOverStream` (close stream + return failed SyncOutcome on throw) → identity pin (close + return) → `SqliteSyncStore(ctx).addIdentity` (close + return) → enc-key prep (close + return) → `KotlinSync.run(stream, store, fx.device_pub, outbound, onProgress)`; on `Ok`: `if (shouldPublish) store.setPublishedEncPub(pub)`, return `SyncOutcome(ran=true, ok=true, r.messages, r.blobs, r.identities, false, null)`; on `SelfRevoked`: `SyncOutcome(ran=true, ok=false, ..., selfRevoked=true, "self revoked")`; on `Failed`: `SyncOutcome(ran=true, ok=false, ..., "${stage}: ${reason}")`. NEVER call DecryptPass.

- [ ] **Step 4: Run the mutex test — expect PASS.**

- [ ] **Step 5: Refactor `TorManagerModule.syncNow`** to call `SyncRunner.runSync(ctx, fx, onProgress = { p, c -> sendEvent("onSyncProgress", mapOf("phase" to p, "count" to c)) })`, then:
  - if `!outcome.ran`: `emit(false, 0,0,0, "sync already in progress", false)` (surface the skip; feed unchanged).
  - if `outcome.ok`: open a store handle + `val (priv, _) = EncKeys.getOrCreate(store)`; `val res = DecryptPass.run(store, fx.device_pub, priv, fx.cert.identity_pub)`; `feedCache = res.feed; blobKeys = res.keys`; the swallowed "done" `onSyncProgress`; `emit(true, outcome.messages, outcome.blobs, outcome.identities, null, outcome.selfRevoked)`.
  - else: `emit(false, outcome.messages, outcome.blobs, outcome.identities, outcome.error, outcome.selfRevoked)`.
  Keep the exact `emit(...)` signature/semantics syncNow uses today; the terminal `nodeSync` event is unchanged in shape.
> **Implementer:** the ONE behavioral change allowed is the added "already in progress" skip result; everything else must be byte-equivalent behavior. The fixture is obtained the same way syncNow does today (read it). Confirm no second Tor dial / no double `KotlinSync.run`.

- [ ] **Step 6: Transport parity gate** — in `SyncLoopbackTest.kt`, add a test that drives `SyncRunner.runSync` (with a stubbed/loopback-provided fixture + a `TorEngine` seam OR the same mechanism the existing loopback test uses to feed a stream — READ how `SyncLoopbackTest` currently drives `KotlinSync.run` against the python node; if `runSync` hard-depends on `TorEngine.dial`, add a minimal injectable dial seam OR keep the loopback test at the `KotlinSync.run` level and instead assert `SyncRunner`'s enc-key-prep + publish-guard + outcome-mapping in a focused test using the InMemory store). Assert the outcome counts match what the pre-refactor path pulled (same seeded node, same counts). GATE: full module JVM suite green (incl. all loopback tests).

- [ ] **Step 7: assembleDebug + full module suite green. Commit**
```
feat(brickc): extract SyncRunner (JS-free transport + process-wide sync mutex); refactor syncNow onto it (decrypt stays foreground)
```

---

### Task 2: `TorNodeService` runs the periodic full sync (replacing the heartbeat)

**Files:**
- Modify: `android/src/main/java/expo/modules/tormanager/TorNodeService.kt`
- Modify (if needed): `android/src/main/java/expo/modules/tormanager/HeartbeatStore.kt` (Beat carries pulled counts)

**Interfaces:**
- Consumes: `SyncRunner.runSync(ctx, fx)` (Task 1), the service's existing `fixture()`, `TorEngine`, `HeartbeatStore`.

- [ ] **Step 1** — replace the periodic `heartbeat()` body's bare handshake with a sync: `SYNC_INTERVAL_MS = 900_000L` (15 min) replaces `HEARTBEAT_INTERVAL_MS`; the `scheduleAtFixedRate({ syncCycle() }, SYNC_INTERVAL_MS, SYNC_INTERVAL_MS, ...)` and the immediate first run stay. `syncCycle()`:
```kotlin
    private fun syncCycle() {
        val start = System.currentTimeMillis()
        val beat = try {
            if (!TorEngine.isUp) throw IllegalStateException("tor not up")
            val fx = fixture()
            val o = SyncRunner.runSync(this, fx)     // default no-op progress; NEVER decrypts
            if (!o.ran) Beat(start, false, System.currentTimeMillis() - start, "skipped (in progress)")
            else if (o.ok) Beat(start, true, System.currentTimeMillis() - start, null, o.messages, o.blobs, o.identities)
            else Beat(start, false, System.currentTimeMillis() - start, o.error ?: "sync failed")
        } catch (e: Exception) {
            Beat(start, false, System.currentTimeMillis() - start, "io: ${e.message}")
        }
        runCatching { recordAndBroadcast(beat) }     // never-throw guard PRESERVED
    }
```
> **Implementer:** extend `Beat`/`HeartbeatStore` to carry optional pulled counts (messages/blobs/identities) if it doesn't already — additive, default 0/null for the failure/skip constructors. Confirm the `scheduleAtFixedRate`-cancels-on-throw hazard is still covered by the `runCatching` (it is — `syncCycle` mirrors `heartbeat`'s structure). Rename `heartbeat`->`syncCycle` and update `ACTION_BEAT_NOW`'s `scheduler?.execute { heartbeat() }` accordingly (a manual "beat now" now triggers a sync).

- [ ] **Step 2** — update the foreground-service notification + `recordAndBroadcast`'s broadcast text from "heartbeat" wording to "sync" (e.g. "Last sync: ok, N msgs"); keep the broadcast action constants stable unless the payload genuinely changes (if you add counts to the broadcast, update the module's receiver + App.tsx in Task 3).

- [ ] **Step 3** — the service has no JVM test harness (no Robolectric), same as Brick A; verify via `assembleDebug` + code review that `syncCycle` preserves the never-throw guard and the fixed-rate schedule. Say so. The functional proof is Task 4 on-device.

- [ ] **Step 4: assembleDebug green. Commit** `feat(brickc): TorNodeService runs a full content sync every 15 min (replaces the AUTH heartbeat), never-throw guard preserved`

---

### Task 3: Dashboard — "last sync" + counts + skip note

**Files:**
- Modify: `android_tor_spike/app/App.tsx` (+ `index.ts` only if a broadcast/event payload changed in Task 2)

- [ ] **Step 1** — Follow `android_tor_spike/app/AGENTS.md` (Expo v57). Rename the "last beat" dashboard line to "last sync" and show the pulled counts if the beat/broadcast now carries them (time + "ok, N msgs / M blobs" or the error). If Task 2 didn't change the broadcast payload, this is a label change only. The existing `getFeed`-on-mount already shows the fresh store — no new fetch logic. A `syncNow` result of "sync already in progress" shows a brief neutral note in the status line (not a red error).
- [ ] **Step 2** — `tsc --noEmit` A/B (no new errors) + vitest green. Build BOTH APKs (`assembleDebug assembleRelease`). Install the RELEASE apk on the G20 (`adb -s ZY32DLZQ2N install -r ...\release\app-release.apk`; RELEASE not debug — the "Unable to load script" field finding). Play-Protect/device-absent -> report, defer install to Task 4. Commit `feat(brickc): dashboard shows last-sync (time + counts) + in-progress note`

---

### Task 4: On-device run + report

**Files:**
- Create: `android_tor_spike/BRICK_C_REPORT.md`

- [ ] Report + run steps (mirror `BRICK_B2D1_REPORT.md`, carrying the field lessons: desktop node via `python -m hearth serve --dir %APPDATA%\Kreds --http-port P --gossip-port P --tor`, UNLOCK via the web UI first, install the RELEASE apk). **The Brick C proof is the SLOW one:** on the G20 — set up + one foreground sync, note the current feed; from the desktop, post NEW content; then **fully close the app**, wait ~15+ min (a background sync cycle), reopen → the feed shows the new content that arrived while the app was closed (proves the background sync ran with no app open). Plus: confirm the service survived (Brick A checks — force-stop/process-death then confirm sync resumes via START_STICKY; the dashboard's "last sync" advances while backgrounded), and that decrypt-on-read held (nothing decrypted in the background — the feed only renders after the foreground open decrypts). **PAUSE — human-driven, and slower than prior runs (the 15-min wait).** Fill the verdict. Commit `docs(brickc): on-device background-sync run + report`.

---

## Self-Review (performed at write time)

**Spec coverage:** JS-free `SyncRunner` transport extraction -> Task 1; process-wide tryLock/skip mutex -> Task 1; decrypt stays foreground -> Task 1 (module keeps DecryptPass; SyncRunner never decrypts); 15-min periodic sync replacing the heartbeat + never-throw guard -> Task 2; dashboard last-sync + fresh-on-open (free via getFeed-on-mount) -> Task 3; on-device close-app-15-min proof -> Task 4. Notifications/background-decrypt/adaptive-cadence all correctly OUT (spec's out-of-scope).

**Type consistency:** `SyncRunner.runSync(ctx, fx, onProgress): SyncOutcome`, `SyncOutcome(ran, ok, messages, blobs, identities, selfRevoked, error)` (Tasks 1/2); the module's `emit(...)` unchanged (Task 1); `syncCycle()` + `Beat` w/ counts (Task 2).

**Judgment calls flagged:** the loopback-parity mechanism for `SyncRunner` (Task 1 Step 6) depends on how `SyncLoopbackTest` feeds a stream vs `TorEngine.dial` — implementer READS the existing test and either adds a minimal dial seam or keeps loopback at the `KotlinSync.run` level + tests SyncRunner's prep/guard/mapping on the InMemory store; the mutex itself is fully JVM-gated regardless. `HeartbeatStore`/`Beat` count-carrying is an additive confirm (Task 2). The service + on-device legs are device-only (no Robolectric), same coverage boundary as Brick A/B.2d-1.
