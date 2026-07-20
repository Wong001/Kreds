# Brick C report -- background content sync

**Status: PROVEN ON HARDWARE (G20, 2026-07-20).** All 3 code tasks
(SyncRunner extraction + mutex, TorNodeService 15-min periodic sync,
dashboard last-sync) are done and reviewed; the whole-branch review verdict
was READY TO MERGE (no fix wave), gated on this run. The run passed:
background sync worked -- content arrived while the app was closed and was
visible on reopen without a manual sync. The RELEASE apk is installed on the
G20 (from Task 3's live dashboard verification).
This document is the run steps + report skeleton for the on-device leg,
which is human-driven (August) and has **NOT happened yet** -- the verdict
section below is intentionally blank pending that run.

Spec: `docs/superpowers/specs/2026-07-20-android-brick-c-background-sync-design.md`
Plan: `docs/superpowers/plans/2026-07-20-android-brick-c-background-sync.md`
Branch: `brick-c-background-sync`, base `7f33001`, off merged `main`.
Commit range `7f33001..HEAD` (HEAD = `bb8b7f4`), 3 code-task commits
(`4c7107d`, `9b3debd`, `a65206c`/`bb8b7f4`). NOT merged -- merge is August's
decision, after this run.

## What Brick C builds

Brick A gave the phone a persistent foreground node that survives
Doze/process-death (proven by a periodic AUTH heartbeat). B.1/B.2/B.2c/B.2d-1
made content sync work, decrypt, and render -- but only in the FOREGROUND,
via a manual "Sync now" tap while the app is open. Brick C makes that same
content sync run in the background:

- The background service (`TorNodeService`, already Doze-exempt and
  START_STICKY from Brick A) now runs the **full content sync every 15 min**,
  replacing the bare AUTH heartbeat it ran before.
- The transport is a newly extracted, JS-free `SyncRunner` object -- the sync
  logic that used to live only inline in the Expo module's `syncNow`, moved
  out verbatim so both the background service and the foreground module call
  the same code path instead of two copies.
- A **process-wide mutex** (`tryLock`/skip) stops the background timer and a
  foreground "Sync now" tap from ever running two syncs against the same
  SQLite store concurrently; a skipped caller gets a neutral "already in
  progress" outcome, not an error.
- **Decrypt stays foreground.** The background path pulls and stores content
  ENCRYPTED only -- no plaintext, no content keys, no AVIF decode ever run in
  the background. The foreground module still runs `DecryptPass` on open/tap
  (decrypt-on-read), unchanged from B.2/B.2c/B.2d-1.
- **No notifications** (named out-of-scope this slice). **No `hearth/`
  change** -- this is a vector-generator/Android-only touch, same as B.2d-1.

## Desk gates (green)

- **SyncRunner mutex test (JVM, Task 1):** a real latch-based concurrency
  test -- two concurrent `runSync` calls, exactly one runs and the other
  returns "already in progress"; sequential calls both run. Deterministic,
  8/8.
- **Transport-parity gate (JVM, Task 1):** `SyncRunner.runSync` against the
  existing real-node loopback harness pulls the same content the module's
  prior inline path did -- proves the extraction is behavior-preserving, not
  a rewrite. All 4 stream-close-on-failure branches, the single dial, the
  single `KotlinSync.run` call, and the publish-guard/`setPublishedEncPub`
  logic were moved verbatim.
- **Module JVM suite green** (`:tor-manager:testDebugUnitTest`), including
  the service-wiring shape: `syncCycle()` calls `SyncRunner.runSync` (never a
  bare handshake), and the never-throw `runCatching` guard around
  `recordAndBroadcast` is structurally preserved -- a failing sync records a
  failed outcome but never cancels the fixed-rate schedule (Brick A's
  documented silent-cancel trap).
- **HeartbeatStore back-compat 2/2:** `Beat`'s new pulled-count fields
  (messages/blobs/identities) are additive with safe defaults for
  pre-upgrade persisted JSON.
- **assembleDebug + tsc A/B (0 new errors) + vitest 20/20** (Task 3's
  dashboard work: "last sync" line + counts + in-progress note).
- **Dashboard already verified LIVE on the G20 in Task 3** -- the last-sync
  line showed real counts (e.g. "ok, 316 msgs / 35 blobs"), and both
  mutex-skip paths (a foreground tap-race and a background self-skip) render
  NEUTRAL gray, not red. This was a live confirmation, not just a desk gate.

## The architecture (2-3 sentences)

`SyncRunner` is the JS-free transport -- extracted verbatim from `syncNow`,
every stream-close-on-failure branch preserved -- shared by the background
service and the foreground module under a single process-wide
tryLock/skip mutex. The background path calls `SyncRunner.runSync` on a
timer, pulls, and stores ENCRYPTED only, and never decrypts. The foreground
module calls the same `SyncRunner.runSync`, then (foreground only) runs
`DecryptPass` and emits progress/terminal events -- decrypt-on-read is
preserved end to end.

## On-device run steps (the SLOW proof -- August drives)

### a. Preconditions

- **Desktop node MUST run via**
  `python -m hearth serve --dir %APPDATA%\Kreds --http-port <p> --gossip-port <p> --tor`
  -- plain `hearth app` from source runs WITHOUT Tor (`_tor_enabled()` is
  packaged-only) and the phone's background syncs will time out against a
  stale descriptor.
- A headless `serve` node starts LOCKED (keys are applock-encrypted at rest)
  and refuses sync sessions by design -- the refusal frame is purged by
  Windows RST, so the phone just sees a bare EOF. **Unlock via the web UI
  (`http://127.0.0.1:<http-port>`) BEFORE any sync, foreground or
  background.**
- Check for stray duplicate `hearth app`/`serve` processes fighting over the
  data dir if anything misbehaves.
- The RELEASE apk is already installed on the G20 (confirmed live during
  Task 3) -- do NOT reinstall the debug apk over it (debug embeds no JS
  bundle and produces "Unable to load script" on first open, per the B.2/B.2c
  field finding).

### b. THE PROOF (close-app, 15-min background sync)

1. Open the app. Do one foreground **Sync now**. Note the current feed
   contents and the dashboard's last-sync time/counts.
2. From the DESKTOP node, post NEW content -- a wall post or a DM to self --
   that the phone has not yet seen.
3. **Fully close the Kreds app** on the phone (swipe it away from Recents, or
   force-stop the Activity) -- but leave the foreground service running.
   That's the point: the service (and its 15-min timer) must keep running
   with no Activity in the foreground.
4. **Wait ~15+ minutes** for a background sync cycle to fire. The
   dashboard's "last sync" time (visible via the persistent notification, or
   by peeking at the app briefly and then re-closing it, or simply on
   reopen in step 5) should advance on its own during this wait, with no app
   open.
5. **Reopen the app.** The feed should show the NEW content posted in step 2
   -- arrived while the app was fully closed, with no manual sync tap.
   `getFeed`-on-mount reads the already-fresh, already-decrypted-on-open
   store, so this should render instantly with no additional network wait.

### c. Brick A survival checks (carried forward, re-confirm under Brick C)

- Force process death (`adb shell am force-stop eu.kreds.torspike`, or a
  crash) and confirm **START_STICKY** resurrection -- the service restarts
  and the 15-min sync resumes (not just the old bare heartbeat).
- Confirm the sync still fires under Doze (the service remains
  battery-exempt from Brick A; no new Doze-related change this slice).

### d. Decrypt-on-read check

- Confirm nothing decrypts in the background: while the app is closed, the
  store should be updated but there is no way to observe plaintext (no
  notification, no UI) until the foreground reopens and decrypts. The
  proof is indirect -- correct behavior looks like "nothing visible happens
  while closed, then the feed is already current the instant the app
  reopens" -- there should be no perceptible decrypt/render delay on reopen
  beyond normal app-open cost, since the background pull already did the
  network work and only decrypt-on-read remains.

## What to VERIFY / CAPTURE for the verdict

- The last-sync time/counts advancing while the app was fully closed
  (capture the before/after values).
- The new content (posted from the desktop in step b.2) appearing on reopen
  WITHOUT a manual sync tap.
- The background cycle's pulled counts (messages/blobs/identities) for the
  cycle that picked up the new content.
- **The reviewer's watch item:** whether a manual "Sync now" tap that
  coincides with a background cycle ever surfaces a transient
  `"io: database is locked"` error (the unlocked-foreground-decrypt /
  background-write overlap flagged in review). If it never surfaces during
  this run, note that explicitly. If it does surface, it's self-healing
  (retry succeeds) but becomes the flagged follow-up (see below).
- A directional battery read over the run (the G20 is an aged cell; this is
  observational, not a pass/fail gate).
- Any anomalies: timing, missing content, unexpected errors, the service
  failing to resurrect, or decrypted content appearing to leak into the
  background path.

## Verdict

**PROVEN.**

- Background sync works on the G20 (August, 2026-07-20): with the app fully
  closed, the foreground service ran a content-sync cycle, and content that
  arrived while the app was closed was visible on reopen with no manual sync
  -- the slice's definition of done. The dashboard half (last-sync line +
  counts + both mutex-skip paths rendering neutral) was already proven live
  during Task 3.
- Decrypt-on-read held by construction: the background path never decrypts
  (it stores encrypted only); content only rendered after the foreground
  reopen decrypted the now-fresh store.
- Not separately reported this run (non-blocking, tracked): the
  concurrent-tap "io: database is locked" watch item (the unlocked-decrypt /
  background-write overlap -- self-healing if it occurs; wrap decrypt under
  the mutex only if it surfaces in practice), and the directional battery
  read over a full day (the 15-min Tor-pull honest-unknown).
- Overall: **PROVEN** -- Brick C's definition of done met on hardware.

## Known deferred items / follow-up tickets

Carried forward from the whole-branch review (READY TO MERGE, no fix wave)
plus per-task minors:

- **Unlocked foreground-decrypt / background-write overlap** -- a manual
  "Sync now" tap that overlaps a background sync cycle's DB writes can, in
  principle, surface a transient `"io: database is locked"` error on the
  decrypt-on-read path; expected to be self-healing (SQLite retry / next
  read succeeds), but not exercised on-device yet. If it surfaces during
  this run, wrap the foreground decrypt read under the same mutex as a
  follow-up; if it never surfaces, this stays a watch item rather than a
  fix.
- **Widen `syncCycle`'s `catch (Exception)` to `catch (Throwable)`** -- a
  pre-existing Brick A silent-cancel trap (a bare `Error`/`OOM` could
  currently escape the guard and cancel the fixed-rate schedule), not a
  Brick C regression, but Brick C's `syncCycle` inherits it from Brick A's
  `heartbeat()` shape. Fixing it here would close the gap for both bricks.
- **Battery endurance / adaptive cadence** -- named honest-unknown. A full
  Tor pull every 15 min on top of the always-on Tor service; most cycles are
  expected to be cheap (dial + empty pull, since HAVE typically shows
  nothing missing), but this has not been measured over a real day. If the
  on-device run's directional read shows heavy drain, adaptive cadence is
  the named follow-up (out of scope this slice).
- **`EncKeys.getOrCreate` runs twice per successful sync** -- once inside
  `SyncRunner.runSync`'s enc-key publish prep, once again in the foreground
  module's `DecryptPass` path recovering the private key. Idempotent, no
  correctness issue, just a micro-optimization opportunity.
