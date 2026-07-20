# Brick B.2d-2 report -- video posts

**Status: DESK-COMPLETE.** All 3 code tasks are done and reviewed; the
whole-branch review verdict was READY TO MERGE (no fix wave), gated on this
run. The on-device video-playback run has **NOT happened yet** -- the
verdict section below is intentionally blank pending that run. Both APKs
are built and the RELEASE apk is already installed on the G20 (from Task 3).

Spec: `docs/superpowers/specs/2026-07-20-android-b2d2-video-design.md`
Plan: `docs/superpowers/plans/2026-07-20-android-b2d2-video.md`
Branch: `brick-b2d2-video`, base `b5c4cef`, off merged `main`. Commit range
`b5c4cef..HEAD` (HEAD = `8044949`), 5 commits across 3 code tasks:

- Task 1 (`DecryptPass` media/poster): `38cb443`
- Task 2 (`MediaServer`): `5c3bf76`, `1a616b2`
- Task 3 (`getVideoUrl` + `expo-video` playback): `b10e5bf`, `8044949`

NOT merged -- merge is August's decision, after this run.

## What B.2d-2 builds

B.2d-1 rendered photos. B.2d-2 adds video: a video post is a normal
`KIND_POST` (already decryptable; its AVIF poster already renders through
the existing `getBlobImage` path), so the only genuinely new work is MP4
PLAYBACK. NO change to `hearth/`.

- **`DecryptPass` media/poster surfacing** -- `Decrypted` gains `media`
  (default `"photo"`) and `poster` (nullable), read unconditionally from the
  OUTER plaintext payload (never the decrypted body), exactly like B.2d-1
  read `thumbs`.
- **`MediaServer`** -- a new, dependency-free Kotlin loopback HTTP server:
  binds `127.0.0.1` ONLY on a random ephemeral port, mints a fresh
  per-session unguessable token at start, and serves
  `GET /media/<token>/<msgId>/<hash>` by resolving `(msgId, hash)` to a
  decrypted byte array (no filesystem, no path/file access -- NOT a file
  server) with correct HTTP range support (`206`/`Content-Range`) so the
  player can seek.
- **`getVideoUrl(msgId, hash)`** -- lazily starts/reuses the module's
  `MediaServer`, wires its resolver to the existing
  `blobKeys -> getBlob -> KotlinBlobCrypt.decryptBlob` chain, and returns
  the loopback URL (or `null` if no cached content key).
- **Feed UI** -- a `media === "video"` row renders its poster via the
  existing `getBlobImage` (AVIF, isolated decode) with a play (>) overlay;
  tapping opens the existing fullscreen `Modal`, now hosting an `expo-video`
  player sourced from `getVideoUrl`, released on close. A photo post is
  unchanged.

## Desk gates (green)

- **`DecryptPass` media/poster tests (Task 1):** a real video-post fixture
  (`media="video"`, `poster=<hex64>`, one `blobs` entry) asserts
  `media`/`poster` surface correctly; a photo-post fixture asserts the
  `"photo"`/`null` regression defaults. 73/73.
- **The `MediaServer` JVM security gate (Task 2) -- 9 cases:** right-token
  full body is `200` with exact bytes; a `Range` request is `206` with the
  correct slice; wrong/missing token is `403` (checked before any resolve or
  path use -- structurally unreachable on bad token); unknown `(msgId,hash)`
  is `404`; the listener binds `127.0.0.1` ONLY (a non-loopback connect is
  refused); plus the review-driven fixes: a range end past EOF now CLAMPS to
  `total-1` + `206` (was `416` -- would have broken seek near the tail, the
  exact territory Task 4 exercises); a suffix range (`bytes=-N`) resolves
  correctly; a garbage `Range` header falls back to `200` full body instead
  of erroring; a 5-second socket read timeout plus a header-line cap closes
  a thread-exhaustion DoS angle. All RED-proven before the fix, GREEN after.
- **Module suite 82/82** (JVM, `:tor-manager:testDebugUnitTest`).
- **`assembleDebug` + tsc A/B (0 new errors) + vitest 20/20** (Task 3's UI
  work: video poster + play overlay + fullscreen `expo-video` player,
  released on modal close).
- **Both APKs built; the RELEASE apk installed on the G20** (Task 3).

## The security design (2-3 sentences)

The mp4 is decrypted on demand and streamed to the platform player
(`expo-video`/ExoPlayer, deliberately mediaserver-sandboxed rather than
self-isolated like the AVIF decoder) over a `127.0.0.1`-ONLY loopback server
guarded by a per-session token; decrypt-on-read is preserved -- nothing is
ever decrypted to disk, verified including the player side (`expo-video`
caching off). A loopback-scoped `network_security_config` permits the
cleartext `http://127.0.0.1` traffic ExoPlayer needs on API 30; it is NOT a
blanket cleartext allow -- no base-config, no app-wide
`usesCleartextTraffic`, loopback domains only.

## On-device run steps (August drives)

### a. Preconditions

- **The B.2/B.2c/B.2d-1/Brick C field lessons still apply and are
  load-bearing here too:**
  1. A source-run desktop node MUST use
     `python -m hearth serve --dir %APPDATA%\Kreds --http-port <p> --gossip-port <p> --tor`
     -- plain `hearth app` from source runs WITHOUT Tor (`_tor_enabled()` is
     packaged-only), and the phone's syncs will time out against a stale
     descriptor.
  2. A headless `serve` node starts LOCKED (keys are applock-encrypted at
     rest) and refuses sync sessions by design -- the refusal frame is
     purged by Windows RST, so the phone just sees a bare EOF. **Unlock via
     the web UI (`http://127.0.0.1:<http-port>`) BEFORE the phone syncs.**
  3. Check for stray duplicate `hearth app`/`serve` processes fighting over
     the data dir if anything misbehaves.
- The RELEASE apk is already installed on the G20 (Task 3) -- do NOT
  reinstall the debug apk over it (debug embeds no JS bundle and produces
  "Unable to load script" on first open, per the standing field finding).
- The desktop store already holds video posts. If none are visible from the
  phone side, compose one from the desktop app -- record a short clip as a
  video post -- before syncing.

### b. Sync flow

1. Open the app.
2. Tap **Sync now**; if needed, tap it again roughly 15 seconds later so any
   video post posted just before this run is picked up.

### c. What to VERIFY (the DoD + whole-branch-review checklist)

- **A real video post shows its POSTER** (AVIF still, via the existing
  isolated decode path) **and a play (>) affordance** in the feed.
- **Tapping it plays the decrypted mp4 FULL-SCREEN with working controls.**
- **SEEK works -- specifically test SEEK NEAR THE TAIL/END of the clip.**
  This is the range-clamp fix's territory (Task 2): a naive server would
  have 416'd a seek near the end before the fix; confirm it now plays
  cleanly.
- **PAUSE for more than 5 seconds, then RESUME.** The server's socket read
  timeout is 5 seconds and TCP backpressure applies while paused; confirm
  playback resumes cleanly and does not die or need a re-open.
- **A PHOTO post still renders as before** -- a B.2d-1 regression check;
  this slice must not have disturbed photo rendering.
- **Decrypt-on-read held:** confirm no decrypted mp4 is ever written to
  disk -- the loopback stream is the only path a decrypted frame ever takes.

### d. What to capture for the verdict

- Whether the video played at all, and from poster-tap to first frame.
- Seek behavior, specifically near the tail/end (did it play cleanly, stall,
  or error).
- Pause-for-more-than-5s-then-resume behavior (clean resume vs. stall vs.
  dead stream).
- Any playback stutter or quality issue.
- The photo-post regression result (rendered correctly, as before).
- Any anomalies: timing, error strings, crashes, or unexpected placeholder
  states.

## Verdict

**[PENDING RUN]**

## Known deferred items / follow-up tickets

Carried forward from the per-task reviews plus one new hazard surfaced
while writing this report:

- **Loopback-server hardening (`MediaServer`), several small items bundled
  as one follow-up:**
  - GET/HEAD method enforcement (today `HEAD` falls through the same path
    and returns a body -- an HTTP-correctness nit, not a security issue,
    since the token gate runs first regardless of method).
  - A bounded handler thread pool -- each connection currently spawns a new
    daemon thread; fine at this slice's single-viewer scope, but unbounded
    under adversarial load.
  - A single `SqliteSyncStore` instance for the resolver, rather than one
    per request -- the current resolver opens a fresh store handle per
    range request, which is expected to produce `SQLiteConnection`-leaked
    warnings under a seek-heavy session (many range requests in quick
    succession); correctness is unaffected but the warning noise and
    per-request open/close cost should be cleaned up.
  - Constant-time token compare -- the current compare is not
    constant-time, but was reviewed and judged negligible given the
    256-bit `SecureRandom` token (timing attack is not practically
    feasible); still worth closing as a defense-in-depth follow-up.
- **`expo-video` via autolinking only** (package.json, no manual gradle
  wiring) -- noted as a judgment call in Task 3's review, not a defect.
- **Video row gated on `media === "video" && poster`** -- if a video ever
  had a null poster (an upstream invariant violation, not expected from
  hearth today), it would silently fall through to the photo path and show
  "media unavailable" rather than a video-specific error. Cross-task
  dependency note, not a bug in this slice.

**PROMINENT HAZARD -- manual native-config edits with no restore path:**
The `network_security_config.xml` file and the `AndroidManifest.xml`
`android:networkSecurityConfig` attribute added in Task 3 are **MANUAL**
edits in the checked-in `android/` directory
(`android_tor_spike/app/android/app/src/main/res/xml/network_security_config.xml`
and
`android_tor_spike/app/android/app/src/main/AndroidManifest.xml`).
`app.json` has **NO config plugin** that reproduces them. A future
`expo prebuild --clean` (or any regeneration of the native `android/`
project) would **SILENTLY DROP both edits**, and video playback would break
again -- ExoPlayer would reject `http://127.0.0.1` on API 30 exactly as it
did before Task 3's fix, with no build error to flag it. Whoever runs a
prebuild in the future must either manually re-add both edits or, better,
add a small Expo config plugin that applies them automatically.
