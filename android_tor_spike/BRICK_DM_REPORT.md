# Outbound Slice 3 — DM Send (text + photo + story-reply + expiry) — on-device report

**The phone can now send DMs.** The Kreds Android client composes and
publishes direct messages (`KIND_DM`, mirroring hearth's `compose_dm`,
node.py:2308-2345) natively in Kotlin — text, photo attachments, story
replies, and expiring DMs — encrypted, wrapped to every recipient device
(including the sender's own, so the sent bubble renders immediately),
device-signed, and pushed on the next sync. This closes the last named gap
in the Android client ("not possible to send DMs yet"). This is the third
outbound (write) slice; it reveals the DM composer in the read-only seam
(journal composer + reactions/comments composer stay revealed from slices
1 and 2; profile-wall composer and profile Arrange stay hidden).

Branch: `brick-outbound-dm` (base `c2ae953` off main). HEAD: `83b4f65`.
Spec: `docs/superpowers/specs/2026-07-22-android-outbound-dm-send-design.md`.
Plan: ledger section `PLAN: 2026-07-22 android-outbound-dm-send` in
`.superpowers/sdd/progress.md`.

4 code tasks (Task 1 crypto/model, Task 2 route, Task 3 seam, Task 4
loopback fidelity gate), all APPROVED at review first pass with zero
Critical/Important findings — no fix wave needed on those 4 tasks. Global
constraints held throughout: `feat/fix/docs/test(dm)` lowercase, no
AI/Co-Authored-By trailers; byte-exact vs `node.py:2308-2345 compose_dm`
(`dmAad` reused from the read side, `_dm_device_pubs` theirs+mine merge,
exact hearth validation error strings, `expires_at` honored with a
dedicated loopback assertion — the outbound-1 privacy lesson, this time
caught before merge); `story_ref` rides plaintext on the envelope by
hearth design, shape-guarded via a port of `_valid_story_ref`; reused
outbound-1's `wrapKey`/`encryptBody`/`encryptBlob`/`Multipart`/`PhotoPrep`/
pending-outbound-queue/`enckeys`; `KotlinSeal` (the mutual-box machinery)
deliberately NOT involved in DMs. The **whole-branch final review** (after
Task 5's on-device-prep report, below) found one Important finding, fixed
in the pre-merge fix wave documented immediately below.

## Final-review fix wave (post-HEAD `83b4f65`)

Whole-branch final review found one Important finding, fixed on this
branch before merge:

- **Expired DMs never disappeared on the phone.** Desktop expires a DM by
  DELETING its row: `store.sweep_expired()` (`store.py:432`) runs every
  sync tick (`sync.py:273`). The phone's read path already applies the
  equivalent `notExpired` guard to posts (`feed()`/`profile()`,
  `LocalApi.kt:260`/`289`/`293`) and to stories (`activeStories`), but
  never to DMs — `extractDmMsgs` (`LocalApi.kt:503-525`) carried
  `expires_at` into every row and `dmThreadJson` emitted it, but nothing
  ever filtered on it, so an expired DM would linger forever in both the
  thread view and the conversations list (including as a stale
  last-message preview, and inflating the unread count). Fixed by a new
  `LocalApi.notExpiredDms()`, applied once in `loadDms()` — the single
  decrypt-pass read both `conversations()` and `dmThread()` consume — so
  the guard covers the thread, the conversations list, the last-message
  preview, and the per-conversation count together. In-memory filter
  only: no store mutation, no delete — the stored row itself is
  untouched. A durable phone-side sweep (actually deleting expired rows,
  the way desktop's sweep does) is a follow-up, not this fix — see
  Follow-up tickets below.
- **On-device DoD item (e) was unwriteable as originally specified.** It
  asked August to "drive an expiring DM from the phone," which no UI on
  either platform can do: every `/api/dm` caller in the shared
  `web/app.js` hardcodes `expires_seconds=""` (the DM composer,
  `app.js:4684`, and the story-reply chip, `app.js:3207`) —
  `expires_seconds` is an API-only capability, not reachable from either
  platform's UI, so this checklist step could never have passed as
  written. Corrected in place below (see item (e)) to point at the real
  coverage instead: Task 4's loopback gate (real `store.sweep_expired`
  sweeping a real phone-composed expiring DM on a real node) for the wire
  proof, and the four new `notExpiredDms`-covering `LocalApiTest.kt`
  cases for the phone read-side, with an optional (non-blocking)
  curl-driven desktop check offered in place of the impossible phone-UI
  step.

JVM suite grew from 242 to 246 (4 new tests in `LocalApiTest.kt`: past-
vs-future-vs-null expiry, the exact-`now`-is-expired boundary matching
hearth's `<=`, an expired DM absent from both the thread and the
conversation preview/count while a live one from the same partner
survives, and a partner whose entire history expired dropping out of the
conversations list entirely). `:app:assembleRelease` rebuilt and
reinstalled on the G20 this fix wave (see "Install confirmation" below).

## What it does

- **Native compose crypto (`ComposeDm.kt`):** `ComposeDm.compose` is the
  Kotlin mirror of `node.py`'s `compose_dm` — validates in hearth's exact
  order (self-DM → recipient-not-a-friend → bad `story_ref` shape →
  recipient-has-no-enckeys) with hearth's exact error strings, builds the
  recipient wrap set as `{**theirs, **mine}` (mine wins on a shared
  `device_pub`, self-readable so the sent bubble renders on every own
  device), derives a single `created_at` used identically for the AAD and
  the envelope (no drift — this exact class of bug was outbound-1's
  final-review privacy catch), honors `expires_seconds` into
  `expires_at = created_at + expires_seconds` (present in the envelope
  even when null, matching hearth's always-present-key wire shape),
  encrypts photos via `KotlinBlobCrypt.encryptBlob` (ciphertext hash as
  the blob ref), encrypts the body (`{text, blobs}`) via
  `KotlinDmcrypt.encryptBody`/`wrapKey` (wrap AAD == body AAD, both reused
  unchanged from outbound-1/2), device-signs, ingests locally, and enqueues
  onto the same pending-outbound queue outbound-1 built.
- **Route:** `POST /api/dm` in `LocalApi.kt` — multipart via the existing
  `Multipart.parse`, matching desktop's contract (`api.py:666-681`):
  `to`, `text` (default `""`), `expires_seconds` (blank → null, else
  `toDoubleOrNull` — a deliberate, documented, stricter-fail-closed
  divergence from desktop's bare `float(...)`), `story_ref` (JSON string →
  parsed dict or null via the same `BigDecimal`-aware `MsgJson.jsonToMap`
  bridge used elsewhere, not a hand-rolled converter), `photos` (repeated
  file parts, every one mandatorily gated through `PhotoPrep` — no bypass,
  and the gate runs BEFORE any compose/store side effect so a bad photo
  aborts the whole send with no partial write). `{"msg_id": mid}` on
  success; `IllegalArgumentException` → 400, other → 500 (same split as
  the existing `/api/react`/`/api/comment`/`/api/post` routes). Warms
  `dmKeysCache` on send so the thread renders without waiting for a
  decrypt pass. `/api/dm` never collides with the existing
  `/api/dm-blob/` prefix dispatch (exact-match vs `startsWith`, already
  documented at `LocalApi.kt:64`).
- **Seam:** `#dm-compose` is now revealed from `body.readonly` — verified
  the ONLY change (the remaining hide-set — `#profile-wall-compose`,
  `#profile-arrange`, `#profile-cog`, `#profile-addfriend`, `.pact.del`,
  `.settings-del`, `.story-tile .story-ring.add`,
  `#profile-actions .ring-move`/`.btn-danger` — stays intact). The
  `#dm-compose` form is photo input + textarea + send only (no other
  write affordance inside it), and it POSTs to `/api/dm`. The story-reply
  chip in the story viewer (`app.js:3134-3236`, already shipped, POSTs
  `/api/dm` with `story_ref`) was never seam-hidden by a separate
  selector — it goes live for free with this reveal, verified (not
  assumed) against the actual selectors.
- **Sent-DM read-back:** no new read-side code — own sent DMs decrypt via
  the existing conversations pass (`loadDms`) through the self-wrap
  `ComposeDm.compose` includes in the recipient set. The slice's job was
  to VERIFY this renders (unit-proven via the own-wrap decrypt test below;
  on-device is DoD item (f)) rather than assume it — `dmKeysCache` warm in
  `ComposeDm`/`LocalApi` is the designated fix if the phone's own fresh
  compose is ever missed by the existing decrypt pass.

## Desk gates (all GREEN — this session, against HEAD `83b4f65`)

Commands run from `android_tor_spike/app` (tsc/vitest) and
`android_tor_spike/app/android` (gradle), against a clean working tree
matching `git status` at session start.

| Gate | Command | Result |
|------|---------|--------|
| Full JVM suite (`tor-manager`) | `JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot" ./gradlew :tor-manager:testDebugUnitTest --rerun-tasks` | **BUILD SUCCESSFUL**, 62/62 tasks executed |
| XML result count | `python` glob over `../modules/tor-manager/android/build/test-results/testDebugUnitTest/*.xml` | **242 tests, 0 failures, 0 errors**, 27 result files |
| `npx tsc --noEmit` | from `android_tor_spike/app` | **14 errors, all pre-existing** `@types/node` in `src/__tests__/wire.test.ts`, `test/web-readonly-seam.test.ts`, `tools/handshake_cli.ts`, `tools/node_stream.ts`, `tools/roundtrip_cli.ts` — same file set/count as the outbound-1/outbound-2 baseline. **0 new.** |
| `npx vitest run test/web-readonly-seam.test.ts` | from `android_tor_spike/app` | **9/9** |
| `npx vitest run` (full) | from `android_tor_spike/app` | **29/29** (2 test files) |
| `:app:assembleRelease` | `./gradlew :app:assembleRelease` | **BUILD SUCCESSFUL**, apk at `app/build/outputs/apk/release/app-release.apk`, 365 tasks (34 executed, 331 up-to-date) |
| `copyHearthWeb` seam sync | unzipped `assets/www/style.css` directly from the built apk (not just the intermediate dir) | Confirmed: the `body.readonly` hide-block (`#profile-cog`/`#profile-arrange`/`#profile-addfriend`/`#profile-wall-compose`/`#profile-actions .ring-move`/`.btn-danger`/`.pact.del`/`.settings-del`/`.story-tile .story-ring.add`) still lists **`#profile-wall-compose`**. **`#dm-compose` does NOT appear in that block** — it appears only as an ordinary (visible) rule (`#dm-compose button[type=submit] {...}` and `.dm-compose-bar {...}`) elsewhere in the file. `copyHearthWeb` correctly synced the seam edit into the packaged release assets. |

JVM suite grew from outbound-2's 227 to 242 (15 new tests across the 4
tasks: `ComposeDmTest` 8, `LocalApiTest` route additions, `SyncDmLoopbackTest`
1 end-to-end scenario asserting 5 distinct events). No new tsc errors, no
vitest regressions, no JVM regressions at any point in this sweep.

## Parity proof

Two independent proofs establish crypto/protocol fidelity against real
hearth, matching the pattern outbound-1/outbound-2 established.

### Task 4 — the loopback fidelity gate (the headline proof)

`SyncDmLoopbackTest.phoneComposedDmsDecryptOnRealNodeAsRecipientAndExpire`
(`modules/tor-manager/android/src/test/java/expo/modules/tormanager/SyncDmLoopbackTest.kt`)
starts a **real hearth node subprocess** (`sync_loopback_node.py`'s
`_run_dm` scenario) as an own-device of the phone's identity, WITH a real
**friend** identity + published enckey — the DM's actual, addressed
recipient, never the phone's own key:

- Connection 1 primes the store (own + friend enckeys) — the same
  HAVE-frame priming pattern outbound-1/outbound-2's loopback gates
  established, required because hearth's `sync.py` restricts a round's
  entitled set to identities the peer already reports knowing that same
  round.
- Connection 2 pushes all **four** phone-composed DM variants through the
  real pending-outbound queue in one round trip: a plain text DM, a photo
  DM, a story-reply DM (`story_ref` carrying a real hex64 `media_hash`
  reused from the photo DM's own blob ref), and an expiring DM
  (`expires_seconds = 60.0`). The node decrypts each **as the RECIPIENT**
  — the friend identity's own device enc key, via real
  `dmcrypt.unwrap_key`/`decrypt_body`, with `dm_aad` **recomputed from the
  envelope's own fields** (not trusting the phone's claim, matching
  `node.py:2816-2817`'s `_content_key`) — asserting per-event `text_ok`
  for all four, `blob_ok` (AEAD-verified photo decrypt, body-vs-envelope
  blob refs cross-checked) for the photo DM, `story_ref_ok` (hearth's
  real `_valid_story_ref` re-validation) for the story-reply DM, and
  ingestion gated through hearth's real `validate_payload` (a rejected
  message times out rather than false-passing, per
  `sync.py:633-636`).
- The **expiry proof** (the outbound-1 lesson's dedicated assertion): the
  expiring DM's event asserts `expires_at == created_at + expires_seconds`
  exactly (`ts4 + expiresSeconds`, 0.0 delta), then the node runs hearth's
  **real `store.sweep_expired`** with `now = expires_at + 1` and reports
  whether the DM was **actually reclaimed** — asserted via a
  `dm_expired {"swept": true}` event, not a reimplementation of expiry
  semantics.

The gate **passed first attempt** — zero production changes needed to make
it pass, itself a fidelity signal. UTF-8 (the text DM's body is
`"hej fra telefonen 🌸"`) round-trips byte-exact. Photo bytes are an
arbitrary 200-byte pattern, not a decoded JPEG — `ComposeDm.compose`
never image-decodes (it consumes already-`PhotoPrep`-gated bytes), and
neither does this gate's node-side decrypt (AEAD round-trip + cross-check
only), so no real JPEG asset was needed to prove blob fidelity here. (See
"Follow-up tickets" below — the Task 4 review flagged this as a framing
correction, not an engineering gap: the original report's wording implied
brief-permitted arbitrary bytes when it is actually a sound,
precedent-consistent divergence from the brief's literal "valid JPEG
fixture bytes" wording.)

### Task 1 — independent-keypair recipient wrap (non-tautological)

`ComposeDmTest.composeTextDmDecryptsViaOwnWrapAndRecipientWrap`
(`modules/tor-manager/android/src/test/java/expo/modules/tormanager/ComposeDmTest.kt:58`)
constructs a recipient identity with its own independent X25519 keypair
(`frEnc`, generated fresh, never the key `ComposeDm` itself sealed with)
and asserts the recipient's wrap opens with the **friend's own**
`enc_priv` to recover the identical content key the sender's own-device
wrap recovered (`assertArrayEquals(key, fkey)`) — proving the recipient
wrap is genuinely openable by someone who is NOT the composer, not merely
a self-consistency round-trip. The same test also verifies the envelope
shape (`kind`, `to`, `expires_at`/`story_ref` present-but-null when
absent), that the body decrypts to the exact plaintext via the
already-proven `unwrapKey`/`decryptBody` inverses, that the message is
locally ingested (`store.messageById`), and that it is queued exactly
once onto the pending-outbound queue.

## On-device DoD — G20 (August drives)

**Status: PENDING.** The checklist below is copied verbatim from
`.superpowers/sdd/task-5-brief.md` Step 3 / the design spec's testing
section. No behavioral checks beyond confirming install `Success` were run
this session — August drives the run.

Desktop `serve --tor`, unlocked, with a friend synced. From the phone:

- [ ] (a) DM composer visible in a thread
- [ ] (b) text DM arrives on desktop, right thread/sender
- [ ] (c) photo DM arrives, renders both ends, EXIF stripped
- [ ] (d) story reply from the phone story viewer lands as a DM with story
      context on desktop
- [x] (e) **expiring DM — DESK-PROVEN, not phone-UI-driveable.** Corrected
      (final review, pre-merge): item (e) as originally written asked
      August to "drive an expiring DM from the phone," which is
      impossible on EITHER platform. `expires_seconds` is an API-only
      capability — every `/api/dm` caller in the shared `web/app.js`
      (used by both desktop and the phone's WebView shell) hardcodes
      `expires_seconds=""`: the DM composer
      (`document.getElementById("dm-compose").onsubmit`, `app.js:4684`)
      and the story-reply chip (`app.js:3207`) both do
      `fd.append("expires_seconds", "")`. There is no UI control on
      either platform that sets it to anything else, so this checklist
      step could never have passed as written. Two things now stand in
      for the impossible on-device UI check:
      1. **Wire-level expiry proof (desk-proven):** Task 4's loopback gate
         (`SyncDmLoopbackTest.phoneComposedDmsDecryptOnRealNodeAsRecipientAndExpire`)
         composes a real phone-side expiring DM
         (`expires_seconds = 60.0`), asserts `expires_at == created_at +
         expires_seconds` exactly, then runs hearth's REAL
         `store.sweep_expired` (`store.py:432`) on a real node past that
         expiry and asserts the DM was actually reclaimed (`dm_expired
         {"swept": true}`) — proving the compose/expiry-honoring path
         and the node-side sweep, byte-exact against hearth.
      2. **Phone read-side coverage (desk-proven, this fix):** the phone
         never ran a sweep of its own — `extractDmMsgs` carried
         `expires_at` into every DM row but nothing filtered on it, so
         an expired DM would have lingered forever in both the thread
         view and the conversations list/preview/count. Fixed by
         `LocalApi.notExpiredDms()`, applied once in `loadDms()` (the
         single read shared by `conversations()` and `dmThread()`), and
         covered by four new `LocalApiTest.kt` cases: past-vs-future-vs-
         null expiry, the exact-`now`-is-expired boundary (matching
         hearth's `<=`), an expired DM absent from both the thread and
         the conversation preview/count while a live one from the same
         partner survives, and a partner whose entire history expired
         dropping out of the conversations list entirely.
      - **Optional realistic on-device check (August, if desired):**
        since the phone can only ever COMPOSE a non-expiring DM through
        its own UI, the only way to exercise expiry end-to-end
        live is from the API directly — e.g. `curl` an expiring DM at
        the **desktop's** `/api/dm` with `expires_seconds` set, confirm
        it renders normally at first, then confirm it is gone from the
        desktop's own thread after TTL (desktop's own
        `store.sweep_expired` tick). This exercises the same node-side
        sweep Task 4 already proved and is optional, not blocking — the
        wire proof + the new read-filter test are the real coverage for
        this item.
      - A durable phone-side sweep (deleting expired DM rows locally,
        the way desktop's periodic sweep does, instead of only
        filtering them at read time) is a follow-up, not part of this
        fix — see Follow-up tickets below.
- [ ] (f) sent bubble renders on the phone immediately (dmKeysCache warm)
- [ ] (g) regression: profile wall compose / Arrange / story-add stay
      hidden

## Install confirmation (original session, pre-fix-wave)

Device: G20, serial `ZY32DLZQ2N`, package `eu.kreds.torspike`, RELEASE
apk only (built against HEAD `83b4f65`).

```
adb shell am force-stop eu.kreds.torspike        -> (no output, success)
adb install -r -d app-release.apk                -> "Performing Streamed Install" / "Success"
```

The install call hung silently past the tool's 180s foreground timeout
before returning — `adb shell dumpsys window | grep mCurrentFocus` showed
`PlayProtectDialogsActivity` had taken focus (the app had, in fact,
already installed by that point — its icon was visible on the home screen
behind the dialog, matching the mechanism the outbound-2/responses report
already documented). Confirmed via `adb exec-out screencap` that the
dialog was Play Protect's "send this unrecognized app for a security
scan?" prompt (Danish: "Vil du sende appen til sikkerhedstjek?"),
dismissed via `adb shell input tap` on "Send ikke" (Don't send — the
privacy-preserving choice for an unreleased, pre-IP-filing sideloaded
APK), after which the backgrounded install call's output showed
`Success` immediately.

No behavioral checks were run beyond confirming the install succeeded —
the DoD above is August's to drive.

## Install confirmation (this session, final-review fix wave)

Device: G20, serial `ZY32DLZQ2N`, package `eu.kreds.torspike`, RELEASE apk
only (rebuilt against this fix wave's HEAD, after `LocalApi.notExpiredDms`
+ the four new `LocalApiTest.kt` cases landed and the full JVM suite
passed 246/246).

```
adb shell am force-stop eu.kreds.torspike        -> (no output, success)
adb install -r -d app-release.apk                -> "Performing Streamed Install" / "Success"
```

Same Play Protect gotcha as every prior sideload cycle on this device
(now a fourth documented occurrence across the outbound family):
`dumpsys window | grep mCurrentFocus` showed
`PlayProtectDialogsActivity` in focus mid-install; `adb exec-out
screencap -p` confirmed the "Vil du sende appen til sikkerhedstjek?"
prompt for `KredsTorSpike`, dismissed via a tap on "Send ikke" (Don't
send), after which focus returned to the launcher and the backgrounded
install call's output showed `Success`. No behavioral checks were run
beyond confirming the install succeeded — the on-device DoD checklist
above remains August's to drive; nothing in this fix wave changes what
it needs to cover beyond the corrected item (e).

## Run gotchas

- **A Play Protect scan-consent dialog can silently block `adb install`
  with no error and no timeout** (hit in the original session, a third
  documented occurrence across the outbound family — outbound-2/responses'
  report documented the second — and hit AGAIN in this fix wave's
  reinstall, a fourth occurrence; see "Install confirmation (this
  session, final-review fix wave)" above). If a reinstall hangs with no output
  after "Performing Streamed Install" (or even before it), check
  `adb shell dumpsys window | grep mCurrentFocus` for
  `PlayProtectDialogsActivity` before assuming a transfer stall — the app
  may have already installed and just be waiting on the post-install
  scan-consent prompt. `adb shell input tap` on the dialog's "don't send"
  option (`Send ikke`) unblocks it; screenshot via `adb exec-out
  screencap -p` first to find the exact coordinates — they can shift with
  device language/resolution/layout.
- **RELEASE-apk-only + force-stop-first are still the field rules**
  carried from outbound-1/outbound-2: the debug apk fails to load the JS
  bundle on this device config, and skipping `am force-stop` before
  `adb install -r` can hang the install.
- **Own-DM sent bubble renders because `dmKeysCache` is warmed on send** —
  `ComposeDm`/`LocalApi` proactively warm the in-memory `dmKeysCache` at
  compose time (mirroring hearth's own `_cache_message_key`) specifically
  so the sender doesn't have to wait for a decrypt pass to see their own
  just-sent message. If DoD item (f) fails, this is the first place to
  look — the fix, per the spec, is strengthening this warm path, not
  adding a new read path.
- **The recipient-enckey precondition produces hearth's exact error
  string.** If the phone tries to DM someone whose enckey hasn't synced
  down yet, `ComposeDm.compose` throws `"no encryption keys known for
  recipient yet"` (→ 400 at the route) — same failure mode and same
  wording as desktop. Not a bug if hit on a freshly-added friend; it means
  the friend's own device hasn't published (or synced) an enckey yet.

## Honest boundary

Reproduced verbatim from the design spec's "Honest limits" section
(`docs/superpowers/specs/2026-07-22-android-outbound-dm-send-design.md`):

> - `story_ref` is plaintext envelope metadata by hearth design: a mutual
>   of both parties can tell WHICH story a DM was about (documented
>   correlation caveat, messages.py:139-150). The phone changes nothing
>   about this disclosure class.
> - `to` is plaintext on the envelope (hearth wire design; same on
>   desktop) — DM existence/recipient is store-visible metadata; only
>   content is encrypted.
> - Expiry is cooperative (compliant clients honor expires_at; same trust
>   model as desktop).
> - The recipient must already have published enckeys the phone has
>   synced ("no encryption keys known for recipient yet" otherwise) —
>   same failure mode as desktop.

## Follow-up tickets (non-blocking, carried from the ledger)

- **Phone-side durable expiry sweep (new, final review, pre-merge).**
  `LocalApi.notExpiredDms()` (this fix) hides an expired DM at READ time
  only — it filters `loadDms()`'s output before `conversations()`/
  `dmThread()` see it, but never touches the stored row. Desktop instead
  runs a real `store.sweep_expired()` sweep (`sync.py:273`, `store.py:432`)
  that deletes expired rows outright. The phone has no equivalent sweep:
  an expired DM's ciphertext, blobs, and row stay in `SqliteSyncStore`
  forever, unlike desktop where the row is actually gone. This is
  behaviorally invisible today (every read path is filtered), but it is a
  storage-growth and hygiene gap, not full desktop parity. Candidate: a
  periodic (or app-start) `SqliteSyncStore.sweepExpiredDms()`-style pass
  mirroring desktop's sweep, or reusing the same trigger point if/when the
  phone gains a background sync tick equivalent for DMs.
- **`ComposeDm.kt` — `encPriv` unused-parameter doc note missing.**
  `ComposeResponse`'s precedent documents why an unused `encPriv`
  parameter is kept in the signature (shape consistency across the
  outbound family); `ComposeDm` doesn't yet carry the equivalent doc note.
  One-line documentation fix (Task 1).
- **Task 1's "mine wins" test name overclaims.** The test asserting
  `{**theirs, **mine}` merge semantics is named for the mine-wins
  precedence rule, but no test actually constructs a cross-identity
  `device_pub` collision to exercise that precedence — the merge is
  correct by construction (Kotlin `putAll`/`putAll` ordering), just not
  adversarially exercised. Test-strengthening candidate, not a product
  gap (Task 1).
- **`MAX_IMAGE_UPLOAD` (50MB) cap omission on `/api/dm`'s photo parts.**
  Precedent-consistent with the existing `/api/post`/`/api/comment` route
  family (none of them enforce this cap either); honestly flagged rather
  than silently accepted — the whole multipart body is already fully read
  before the route dispatches, so enforcing the cap here wouldn't reduce
  peak memory anyway. Candidate for a shared pre-route guard if ever
  addressed (Task 2).
- **`story_ref` parse-layer vs shape-layer 400 divergence.** A bare JSON
  array or number for `story_ref` 400s at the route's parse layer
  (`MsgJson.jsonToMap` bridge rejects non-object JSON) rather than at
  `ComposeDm`'s shape-validation layer the way desktop's
  `_valid_story_ref` would reject it. Same HTTP result (400) either way;
  the mechanism differs. Documented divergence, not a behavior gap
  (Task 2).
- **Task 4 report photo-bytes framing.** The loopback gate's photo DM
  uses an arbitrary 200-byte pattern rather than real JPEG bytes — sound
  and precedent-consistent (verified: no image decode happens on either
  side of this gate, so a real JPEG asset would prove nothing extra), but
  the original task report's wording described this as "brief-permitted"
  when the brief's literal text asked for "valid JPEG fixture bytes." The
  engineering choice is correct; the report's framing needed this
  correction. No code change — a documentation-accuracy note carried
  into this report per the ledger.

## After the run

On a pass, whether this merges to public main is August's call, same as
outbound-1 and outbound-2. PAUSE here for human review per the task
brief. This closes the DM-send gap named at the end of the outbound-2/
responses slice ("finish the Android app generally — DM SEND is the named
gap").
