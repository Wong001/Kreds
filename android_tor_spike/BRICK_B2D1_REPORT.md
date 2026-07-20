# Brick B.2d-1 report — photos + live sync progress

**Status: DESK-COMPLETE. On-device run PENDING.** All 7 code tasks are done
and reviewed; the whole-branch review verdict is **READY TO MERGE, gated on
this on-device run, no fix wave**. Both APKs are built and the RELEASE apk
is already installed on the G20 (Task 7). This report supplies the run
steps for the human-driven on-device session; the Verdict section below is
intentionally blank pending that run.

Spec: `docs/superpowers/specs/2026-07-20-android-b2d-photos-sync-progress-design.md`
Plan: `docs/superpowers/plans/2026-07-20-android-b2d-photos-sync-progress.md`
Branch: `brick-b2d-photos`, base `4bd616e`, **11 commits**
(`4bd616e..HEAD` = `47c9e87`). Off merged `main`. NOT merged — merge is
August's decision.

## What Brick B.2d-1 builds

B.2/B.2c made text content readable. B.2d-1 adds photos and live sync
feedback, with NO change to `hearth/` (vector-generator-only touch):

- **Blob decrypt** (`KotlinBlobCrypt.decryptBlob`) — a port of
  `hearth.dmcrypt.decrypt_blob`, byte-verified against a committed blob
  vector generated from real hearth.
- **Isolated-process AVIF decode** — the security boundary of this slice.
  AVIF pixels are decoded inside an `android:isolatedProcess` service
  (`:imagedecode`), reached over an AIDL/`ParcelFileDescriptor` pipe; the
  main process only ever hands it one image's cleartext compressed bytes
  and gets back a small PNG. Format dispatch (`KotlinImageDecode`) mirrors
  `imagegate.is_image_bytes` plus AVIF magic bytes, so non-image posts
  (e.g. video) are correctly excluded before any decode is attempted.
- **`getBlobImage`** — lazy, in-memory blob fetch + decrypt + decode,
  keyed by `(msgId, hash)`; a per-message content-key cache; fail-closed to
  `null` on every miss (corrupt bytes, wrong key, unsupported format,
  decoder death) so the UI side always has a clean placeholder path.
- **`DecryptPass` blob/thumb surfacing** — blobs come from the body
  (hearth puts them there); thumbs come from the outer signed payload only
  (DMs have no thumbs) — a body-first/payload-fallback resolution, proven
  against a real hearth-shaped fixture case (not hand-rolled JSON).
- **Feed UI** — thumbnail row per feed item (thumb if present, else the
  full blob), tap-to-fullscreen modal, a distinct "media unavailable"
  placeholder for non-image/undecodable content, and a live
  `onSyncProgress` status line (`Syncing... N messages / M blobs`) that
  resolves to the last-sync timestamp on completion — replacing the prior
  1-2 minutes of dead air during a sync.

## Desk gates (green)

- **Blob vector gate (Task 1):** `KotlinBlobCrypt.decryptBlob` round-tripped
  against a committed blob vector from real `hearth.dmcrypt`; existing
  post/dm vector cases unaffected. Suite 54/54.
- **`KotlinImageDecode` format-dispatch unit tests (Task 2):** magic-byte
  dispatch mirrors `imagegate.is_image_bytes` + AVIF (`ftyp` @4 /
  `avif` @8), bounds-safe on short input, null-safe on a missing/failed
  decoder, `ftypisom` correctly excluded from the AVIF branch. Suite
  58/58.
- **Isolated-process AVIF decode — instrumented gate ALREADY RAN ON THE
  G20 during Task 3** (`connectedDebugAndroidTest`, **3/3**): a real AVIF
  fixture decodes to a 48x32 PNG, garbage input fails closed, and the
  service rebinds after a forced teardown. This is the one functional
  proof of this slice that cannot be desk-gated — it is device-only by
  nature (native `.so` + a real second process) and it already ran on
  hardware; it is not being re-run in Task 8, only re-confirmed
  end-to-end via the feed.
- **`DecryptPass` blobs/thumbs + content-key tests (Task 4):** body-first
  / payload-fallback split proven against a real hearth-shaped fixture
  (blob refs in the body, thumbs only in the outer payload); all 21
  `run()` call sites updated to the new `Result(feed, keys)` shape. 61/61.
- **Module suite 62/62** (JVM, `:tor-manager:testDebugUnitTest`), including
  Task 6's loopback sync-progress phase-order test (`onProgress` fires in
  the correct 5-phase order, additive/no-op-by-default, cannot flip a
  terminal sync result even on a swallowed throw).
- **assembleDebug + tsc A/B (0 new errors) + vitest 20/20** (Task 7's UI
  work: thumbnail row, fullscreen modal, status line, fail-closed
  placeholder on every async miss).

## The security design (2-3 sentences)

AVIF is decoded by a maintained dav1d/libavif-backed library
(`io.github.awxkee:avif-coder:2.2.1`, MIT) running inside an
`android:isolatedProcess` Android service that holds no keys, no store
access, no network, and no file access — the untrusted decode surface is
sealed off from everything sensitive. The main process only ever passes
the sandbox one image's already-decrypted, still-compressed bytes over a
pipe and gets back a small re-encoded PNG (which the main process then
decodes with the platform's own hardened PNG path, not the risky AVIF
code); every hop — dispatch, decode, decrypt, teardown/rebind — fails
closed to the placeholder rather than surfacing bad output.

## On-device run steps (the checklist August drives)

### a. Preconditions

- The B.2/B.2c grants and the phone's enc key already exist from prior
  on-device runs, so a normal two-sync should be enough to surface new
  content on this slice — no new backfill sweep was added (no `hearth/`
  change beyond the vector generator).
- **The B.2/B.2c field lessons still apply and are load-bearing here too:**
  1. A source-run desktop MUST use
     `python -m hearth serve --dir %APPDATA%\Kreds --http-port <p> --gossip-port <p> --tor`
     — plain `hearth app` from source runs WITHOUT Tor (`_tor_enabled()` is
     packaged-only), and the phone's syncs will time out against a stale
     descriptor.
  2. A headless `serve` node starts LOCKED (keys are applock-encrypted at
     rest) and refuses sync sessions by design — the refusal frame is
     purged by Windows RST, so the phone just sees a bare EOF
     (`stream closed at 0/4`). **Unlock via the web UI
     (`http://127.0.0.1:<http-port>`) before the phone syncs.**
  3. Check for stray duplicate `hearth app`/`serve` processes fighting over
     the data dir if anything misbehaves.
- The RELEASE apk is already installed on the G20 (done in Task 7) — do
  NOT reinstall the debug apk over it (debug embeds no JS bundle and
  produces "Unable to load script" on first open, per the B.2 field
  finding).

### b. The two-sync flow

1. Open the app.
2. Tap **Sync now** (sync #1).
3. Wait roughly 15 seconds.
4. Tap **Sync now** again (sync #2).
5. If a real friend's node happens to sync with the desktop around this
   window, a later sync can pick up their photos too — best effort, not
   required for the core verification.

### c. What to verify (the DoD + whole-branch-review checklist)

- **Real photos render as THUMBNAILS in the feed**, both for the phone's
  own posts and for friends' posts.
- **Tapping a thumbnail opens the FULL image full-screen.**
- **A non-image/video post shows the "media unavailable" placeholder**
  (not a broken image, not a crash).
- **The live sync status line ticks up during a real sync** (`Syncing...
  N messages / M blobs`) instead of 1-2 minutes of dead air.
- **`adb shell ps | grep imagedecode` shows the isolated `:imagedecode`
  process DURING a decode** — confirms the isolation is real at runtime,
  not just declared in the manifest.
- **Own + friend text/DMs still correct** — a B.2/B.2c regression check;
  this slice must not have disturbed prior readable-text behavior.

### d. What to capture for the verdict

- Which photos rendered, and whether each was own-authored or a friend's.
- Thumbnail vs fullscreen behavior for each (correct image, correct
  orientation, tap-to-close working).
- The `ps :imagedecode` observation — process name seen, roughly when
  relative to the tap/decode, whether it disappears afterward.
- Any placeholder cases: which post(s) showed "media unavailable" and
  whether that was the expected non-image/video case or an unexpected
  miss.
- Sync-status-line behavior: whether it ticked live with numbers moving,
  and whether it correctly resolved to the last-sync timestamp at the end.
- Any anomalies: timing, missing items, unexpected content, error strings,
  or an isolation process that never appeared / stayed resident
  unexpectedly.

## Verdict

**[PENDING RUN]**

## Known deferred items / follow-up tickets

From the whole-branch final review plus carried-forward per-task minors:

- **Throttle/coalesce `onSyncProgress` re-renders** — high-frequency
  progress events currently re-render the whole `App`/`FlatList`; possible
  jank on long feeds during an active sync.
- **Revisit `-Xskip-metadata-version-check` + avif-coder CVE-watch** on the
  next Kotlin/Expo bump (avif-coder was built against Kotlin 2.3.0 vs this
  project's 2.1.20; on-device decode success is counter-evidence today, but
  this is a watch-item, not a closed question).
- **Seed `make_dmcrypt_vectors.py`** for deterministic fixtures — ephemeral
  keys currently re-churn all cases' crypto on every regeneration, producing
  noisy fixture diffs (bit Tasks 1 and 4 during this slice).
- **No cross-item blob cache** — a repeated hash is re-fetched/re-decoded
  per feed item rather than cached; acceptable at dev-dashboard scope.
- **The standing Robolectric/instrumented coverage gap** — still open,
  now also covering this slice's `getBlobImage`/`DecryptPass`
  blob-surfacing paths; needed before Brick C backgrounds this sync.
- **Residual note:** the main process decodes the isolated service's
  *returned* bytes (a re-encoded PNG) using the platform's own PNG path —
  this is the hardened, well-trodden decode surface, not the risky AVIF
  code, which never runs outside `:imagedecode`.
