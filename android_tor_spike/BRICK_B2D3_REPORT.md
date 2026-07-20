# Brick B.2d-3 report -- stories

**Status: DESK-COMPLETE.** All 3 code tasks are done and reviewed (each
review APPROVED first pass, including a whole-branch-review follow-up fix --
the hex64 guard on `storyRefMediaHash`); the whole-branch review verdict is
READY TO MERGE. The on-device run is PENDING -- human-driven, August's turn.
The RELEASE apk is already installed on the G20 (carried from Task 3).

Spec: `docs/superpowers/specs/2026-07-20-android-b2d3-stories-design.md`
Plan: `docs/superpowers/plans/2026-07-20-android-b2d3-stories.md`
Branch: `brick-b2d3-stories`, base `dd057c6` off merged main. Commit range
`dd057c6..HEAD` (HEAD = `21afbb1`), 4 commits across 3 code tasks + 1
review-driven fix:

- Task 1 (store `activeStories` + `missingBlobs` story fix): `8058755`
- Task 2 (module `getStories` + plaintext `getStoryImage`/`getStoryVideoUrl`): `5f2ebd8`
- Task 3 (strip + fullscreen viewer + story-reply-DM chip): `df13046`
- Fix (whole-branch review: `storyRefMediaHash` hex64 guard): `21afbb1`

NOT merged -- merge is August's decision, after this run.

## What B.2d-3 builds

B.2d-1/B.2d-2 rendered posts (photo/video), always decrypted. B.2d-3 adds
STORIES: 24h-ephemeral, broadcast, PLAINTEXT content with no content key --
a strip of unexpired stories above the feed, a fullscreen viewer that cycles
an author's stories (photo or video, with caption), and a "replied to your
story" chip on a received DM that carries a plaintext `story_ref`. NO
composing/replying from the phone (deferred), and NO change to `hearth/`.

- **Store** -- `activeStories(now)`: unexpired (`expires_at > now`)
  `KIND_STORY` rows, newest-first, both `InMemorySyncStore` and
  `SqliteSyncStore`. `missingBlobs()` widened to also scan `story` rows and
  pull their `media`/`poster` hashes into the want-set (previously only
  `post`/`dm` were scanned, so story media never downloaded).
- **Module** -- `getStoryImage(hash)` = `getBlob(hash)` ->
  `KotlinImageDecode.toRenderable` (isolated AVIF decode, NO `decryptBlob`,
  NO `blobKeys` lookup) -> data URI or null. `getStoryVideoUrl(hash)` = the
  existing B.2d-2 `MediaServer`, routed via an explicit `STORY_MARKER="story"`
  resolver branch that serves the raw blob instead of decrypting (mutually
  exclusive with the post decrypt branch; `MediaServer.kt` itself untouched).
  `getStories()` = `activeStories(now)` grouped by author with display names
  via `profileNames`.
- **App.tsx** -- a story strip (author chips, hidden when empty, refreshed
  on mount + `onSync`); tapping a chip opens the existing fullscreen `Modal`
  in story mode, cycling that author's stories one at a time (photo via
  `<Image>`, video via `expo-video` sourced from `getStoryVideoUrl`, player
  released on close/advance as B.2d-2); a received DM with `story_ref` shows
  a chip + thumbnail via `getStoryImage(story_ref.media_hash)`.

## Desk gates (green)

- **Store tests (Task 1):** `activeStories` returns only unexpired rows
  (TTL-boundary-tested) with correct fields, newest-first; `missingBlobs`
  now includes a story's `media` + `poster` hashes -- including the
  field-shape-trap negative test: a POST's `media` field is the
  `"photo"/"video"` discriminator, not a hash, and is proven to never enter
  `missingBlobs` (only `kind=="story"` rows contribute a hash there). Both
  `InMemorySyncStore` and `SqliteSyncStore`. 86/86 at this point.
- **The plaintext-vs-decrypt boundary (Task 2):** `getStoryImage`/
  `getStoryVideoUrl` are structurally distinct functions from the decrypting
  `getBlobImage`/`getVideoUrl` -- no `decryptBlob`, no `blobKeys` lookup on
  the story path; the `STORY_MARKER` resolver branch is an explicit
  if/else, so a post hash can never hit the raw-serve path and a story hash
  can never hit the decrypt path. Whole-branch review verified this clean in
  all 4 directions (story image, story video, post image, post video).
- **`storyRefMediaHash` plumbing (Task 3 + the hex64 fix):** a received DM's
  `story_ref.media_hash` is surfaced through `DecryptPass` (DM-only, gated
  on `kind=="dm"`, read from the OUTER plaintext payload, never the
  decrypted body) into `getFeed`/`FeedItem`, shape-guarded to fail closed to
  `null` on a malformed value. The whole-branch review caught that the guard
  only checked "non-empty string," not real hex64 -- the follow-up fix
  (`21afbb1`) makes it genuinely mirror hearth's `_valid_story_ref` (64
  lowercase hex chars), with a dedicated negative test for the sneaky case
  (right length, valid hex digits, wrong case). Module suite 90/90 after
  the fix.
- **`assembleDebug` + tsc A/B (0 new errors) + vitest 20/20** (Task 3's UI
  work: story strip, fullscreen story-mode viewer, story-reply chip).
- **Both APKs built; the RELEASE apk installed on the G20** (Task 3, still
  current for this run).

## The standout property (2 sentences)

Stories are PLAINTEXT (no content key), so the render path does NOT decrypt
(`getStoryImage` = `getBlob` -> `toRenderable`; `getStoryVideoUrl` = the
`MediaServer` STORY route serving raw) -- but a story photo STILL routes
through the isolated AVIF `ImageDecodeService`, since it is friend-authored,
attacker-influenceable bytes (same RCE-surface reasoning as post photos,
only the decrypt step is dropped). This slice also closes a real gap:
`missingBlobs` previously scanned only `post`/`dm` rows, so story media
never downloaded before B.2d-3's fix.

## On-device run steps (August drives)

### a. Preconditions

- **The B.2/B.2c/B.2d-1/B.2d-2/Brick C field lessons still apply and are
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
- **The standing prebuild-drops-the-loopback-NSC hazard (from B.2d-2) still
  applies to video stories**: story video reuses the same `MediaServer`
  loopback route as post video, gated by the same manually-edited
  `network_security_config.xml` + `AndroidManifest.xml`
  `networkSecurityConfig` attribute, with no config plugin backing them. If
  anyone ran an `expo prebuild --clean` (or any native regeneration) since
  B.2d-2, both edits would be silently gone and BOTH post video and story
  video would fail identically (ExoPlayer rejecting `http://127.0.0.1` on
  API 30, no build error). Confirm the files are still present before this
  run if there is any doubt.
- **Stories have a 24h TTL.** Post FRESH stories from the desktop right
  before this run -- a story posted more than 24h ago will correctly be
  absent from the strip, and that is expected behavior, not a bug. Post at
  least one photo story and one video story.
- **To exercise the story-reply-DM chip**, the store needs a DM whose
  payload carries `story_ref` (referencing one of the posted stories'
  media hash). Compose/send one from the desktop side before syncing if
  none already exists.

### b. Sync flow

1. Open the app.
2. Tap **Sync now**; if needed, tap it again roughly 15 seconds later so
   any story/DM posted just before this run is picked up.

### c. What to VERIFY (the DoD + whole-branch-review device checklist)

- **A photo story appears in the strip under its author's chip**, and
  tapping the chip shows the photo in the fullscreen viewer (isolated
  AVIF-decoded on the plaintext path, no decrypt).
- **A video story plays**, and tapping through from one video story to the
  next (video -> video) releases the previous player cleanly -- no leaked
  player, no crash, no stale audio/video.
- **A story that EXPIRES between two syncs drops out of the strip** --
  confirm the strip reflects only currently-unexpired stories after a
  re-sync.
- **A received story-reply DM shows the "replied to your story" chip +
  thumbnail.**
- **Story media is actually downloaded and rendered** (the `missingBlobs`
  fix) -- the story shows the real photo/video, NOT a "media unavailable"
  placeholder.
- **Own posts/photos/video/feed rendering is unchanged** (regression check
  -- this slice must not have disturbed the existing post paths).

### d. What to capture for the verdict

- Which stories rendered (photo vs. video) and under which author chip.
- The strip grouping (one chip per author, correct unexpired counts).
- Expiry drop-out behavior: did an expired story correctly disappear from
  the strip on re-sync.
- The story-reply chip: did it appear with the correct text + thumbnail (or
  chip-without-thumbnail if that story's media is not yet held).
- Any placeholder ("media unavailable") cases -- which story, and whether
  it is expected (not-yet-downloaded, resolves on next sync) or a bug.
- Any anomalies: crashes, stalls, timing, error strings, or unexpected
  placeholder states.

## Verdict

**[PENDING RUN]**

## Known deferred items / follow-up tickets

Carried forward from the per-task reviews and the whole-branch review:

- **`getStories` `authorName` drops the "me" case for own stories** -- if
  the viewer's own device ever holds its own story, the display name falls
  back to the generic `"friend-" + <identity prefix>` rather than something
  like "you"/"me". Needs own-identity awareness (an `isOwn` check) to fix;
  not expected to surface in this run since stories under test are
  friend-authored, but worth a ticket.
- **Own OUTGOING story-reply DM shows "replied to your story" in the wrong
  direction** -- the chip is rendered off the plaintext `story_ref` alone,
  with no sense of who authored the DM, so a reply the viewer's own device
  sent would (if ever surfaced in the feed) read as if it were received.
  Needs `isOwn` on `FeedItem` to fix; the current slice only renders
  RECEIVED story-reply DMs, so this is a latent gap rather than an active
  bug today.
- **`missingBlobs` re-requests expired story rows forever** -- the widened
  scan does not skip `expires_at <= now` story rows, so an expired story's
  now-unsatisfiable `media`/`poster` hashes get re-added to the want-set on
  every ~15-minute sync indefinitely. Fix: skip story rows whose
  `expires_at <= now` in the `missingBlobs` scan; longer-term, the phone
  side should also GC expired story rows rather than holding them forever.
- **The standing loopback-NSC-no-config-plugin hazard** (from B.2d-2,
  restated above under preconditions since it now also covers story video):
  `network_security_config.xml` and the `AndroidManifest.xml`
  `networkSecurityConfig` attribute are MANUAL edits with no Expo config
  plugin reproducing them. A future `expo prebuild --clean` would silently
  drop both, breaking video playback (post AND story) with no build error
  to flag it. Whoever runs a prebuild in the future must either manually
  re-add both edits or add a config plugin that applies them automatically.
- **`StoryItem.poster` is plumbed but unused** -- Task 2/3 carry the field
  through the module and type surface, but the current strip/viewer does
  not render it (the strip shows author chips, not per-story poster
  thumbnails). Reserved for the later visual-parity slice.
