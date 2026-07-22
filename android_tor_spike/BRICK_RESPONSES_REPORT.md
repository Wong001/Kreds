# Outbound Slice 2 — Reactions + Comments + Read De-anon — on-device report

**The phone can now react and comment, and it can now tell friends apart from
strangers when reading responses.** The Kreds Android client composes and
publishes reactions/comments/retracts on journal posts (`KIND_RESPONSE`,
mirroring hearth's `compose_response`) — sealed into a mutual-box, wrapped,
device-signed, natively in Kotlin, pushed on the next sync — and on the read
side it opens the mutual-box for entries it's entitled to, showing a friend's
**real name** instead of the per-device alias hearth already displayed for
strangers. This is the second outbound (write) slice; it reveals the reaction
picker and comment composer in the read-only seam (journal composer stays
revealed from slice 1; DM composer and profile Arrange stay hidden).

Branch: `brick-outbound-responses` (base `bb14cf1` off main). HEAD: `aaaf4e7`.
Spec: `docs/superpowers/specs/2026-07-22-android-outbound-responses-design.md`.
Plan: `docs/superpowers/plans/2026-07-22-android-outbound-responses.md`.

9 tasks, subagent-driven, all APPROVED at review (2 tasks needed a fix wave:
Task 1's vector-pin hardening, Task 5's `pyStr` BigDecimal fix; Task 8 needed
a medium-severity harness fix, aaaf4e7). Global constraints held throughout:
`feat/fix/docs(responses)` lowercase, no AI/Co-Authored-By trailers;
journal-only, private-by-default; byte-exact crypto (`MUTUAL_BOX_AAD =
hearth/mutual-box/v1`, slot-KEK info `hearth/mutual-box-kek/v1`,
`_SLOT_BUCKETS` 8/16/32/64, `deriveAliasSeed` on raw `device_priv`, sealed
payload `canonical({identity,device_pub,sig})`, mutual-box audience = MY
friends); reused outbound-1's `wrapKey`/`encryptBody`/pending-queue/`enckeys`
and B.2d-4's `KotlinResponses`/`responsesPass`.

## Final-review fix wave (post-HEAD `aaaf4e7`/`b007b40`)

Whole-branch final review found one blocker and two nits, all fixed on this
branch before the on-device run:

- **Own-response read-side (`mine`/`my_reaction`) — the blocker.**
  `LocalApi.responsesJson` hardcoded `mine:false` and `my_reaction:null` for
  EVERY entry, on every post, always — so the retract "×" (`app.js:640,
  if (c.mine)`) never rendered for any comment, ever, and the reaction
  picker never showed your own reaction as active (`app.js`'s `.on` class),
  and your own comment displayed as an anonymous alias to YOU, on your own
  phone. Fixed by porting hearth's step-1 identity resolution
  (`node.py`'s `raw_by_created_at`, `_post_responses_view`) into
  `KotlinResponses.resolve`/`aggregate` and a new `DecryptPass.
  ownRawByCreatedAt` store scan — see "Own-response read-side" under Honest
  boundary below for exactly what this does and does not cover.
- **`KotlinDmcrypt.deriveAliasSeed` HMAC byte-count nit.** `mac.update` used
  `target.length` (a UTF-16 char count) instead of the encoded byte array's
  `.size`. Already flagged as a follow-up ticket at the original review;
  fixed now. Dormant in practice (every real target is a 64-hex `msg_id`,
  where char count == byte count) — no observable behavior change, pinned
  hex vectors stay green.
- **This doc's own wording.** The "Seam" bullet below said `.comment-x` was
  "revealed" in a way that could be misread as functional; corrected inline.

JVM suite grew from 212 to 227 (15 new tests: 8 in `KotlinResponsesTest`
covering the pure step-1 resolution logic, 5 end-to-end in `DecryptPassTest`
using real crypto against a hand-built raw response + folded record, 2 in
`LocalApiTest` proving the JSON marshal). `npx vitest run` stayed 28/28
(no `app.js` changes — the fix is entirely server-side/Kotlin).

## What it does

- **Native compose crypto (`KotlinSeal` + `ComposeResponse`):** `sealSlots`/
  `tryOpenSlots` implement the mutual-box (byte-checked against hearth's
  `dmcrypt.py` — AAD, KEK-HKDF info, bucket sizes, malformed-slot skip,
  dummy-slot padding, Fisher-Yates shuffle). `ComposeResponse.compose` is the
  Kotlin mirror of `node.py`'s `compose_response`: validates `rkind`/comment
  length/target, seals the mutual-box to every friend + own devices, wraps +
  signs, and enqueues onto the same pending-outbound queue slice 1 built.
  Reactions, comments, and retracts are all the same `compose(rkind, ...)`
  call — no separate code path per kind.
- **Routes:** `POST /api/react {msg_id,token}`, `/api/comment {msg_id,text}`,
  `/api/retract {msg_id,created_at}`, matching `api.py:520-546` and the
  existing `app.js` call sites (400 on `IllegalArgumentException`, 500
  otherwise, `{"ok":true}` on success). `/api/response-remove` (author
  moderation/tombstone) is **deliberately not implemented** — unreachable
  today (`can_moderate:false` is hardcoded) and out of this slice's scope.
- **Read de-anon (`KotlinResponses` + `DecryptPass`):** verifies the response
  envelope's outer signature, opens the sealed box (`identity`, `device_pub`,
  `sig`) for entries the reading device is entitled to, and — on a
  sig-verify **and** device-bound match — resolves the real name instead of
  hearth's existing per-device alias. Bug found and fixed along the way:
  aggregate `Comment.responder`/`name` was `null` for de-anon'd private
  entries; both `/api/feed` and `app.js`'s `identityColor` now see a
  consistent resolved identity.
- **Seam:** `.rx-open` (reaction toggle), `.rx-picker` (reaction picker), and
  `.comment-composer`/`.comment-x` (comment box + delete) are now revealed
  from `body.readonly`. Everything else — DM composer, profile Arrange,
  profile-wall composer, moderation controls — stays hidden. **Correction
  (final-review fix wave, below):** "revealed" here described CSS visibility
  only. `.comment-x` (retract) and the reaction picker's `.on` state were
  reachable in the DOM at the time this line was written, but were dead in
  practice — `LocalApi.responsesJson` hardcoded `mine:false` and
  `my_reaction:null` for every entry, so `.comment-x` never actually
  rendered for any comment (`app.js`'s `if (c.mine)` never fired) and the
  reaction picker never showed your own glyph as active. The own-response
  read-side fix below wires the real values through, making both
  genuinely functional, not merely present in the markup.

## Desk gates (all GREEN — Claude, pre-August, this session)

Commands run from `android_tor_spike/app` (tsc/vitest) and
`android_tor_spike/app/android` (gradle), this session, against HEAD `aaaf4e7`
on a clean working tree.

| Gate | Command | Result |
|------|---------|--------|
| Full JVM suite (`tor-manager`) | `JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot" ./gradlew :tor-manager:testDebugUnitTest --rerun-tasks` | **BUILD SUCCESSFUL**, 62/62 tasks executed |
| XML result count | `python` glob over `../modules/tor-manager/android/build/test-results/testDebugUnitTest/*.xml` | **212 tests, 0 failures, 0 errors**, 25 result files |
| `npx tsc --noEmit` | from `android_tor_spike/app` | **14 errors, all pre-existing** `@types/node` in `src/__tests__/wire.test.ts`, `test/web-readonly-seam.test.ts`, `tools/handshake_cli.ts`, `tools/node_stream.ts`, `tools/roundtrip_cli.ts` — same file set/count as the outbound-1 baseline. **0 new.** |
| `npx vitest run test/web-readonly-seam.test.ts` | from `android_tor_spike/app` | **8/8** |
| `npx vitest run` (full) | from `android_tor_spike/app` | **28/28** (2 test files) |
| `:app:assembleRelease` | `./gradlew :app:assembleRelease` (NDK r27.1) | **BUILD SUCCESSFUL**, apk at `app/build/outputs/apk/release/app-release.apk` (365 tasks: 333 executed, 32 up-to-date) |
| `copyHearthWeb` seam sync | unzipped `assets/www/style.css` directly from the built apk (not just the intermediate dir) | Confirmed: the `body.readonly` hide-block still lists `#profile-wall-compose`, `#dm-compose`, `#profile-cog`, `#profile-arrange`, `#profile-addfriend`, `.pact.del`, `.settings-del`, `.story-tile .story-ring.add`, `#profile-actions .ring-move`/`.btn-danger`. **`.rx-open`/`.rx-picker`/`.comment-composer`/`.comment-x` do NOT appear in that block** — they exist only as normal (visible) rule definitions elsewhere in the file. `copyHearthWeb` correctly synced Task 7's seam edit into the packaged release assets. |

No new tsc errors, no JVM regressions, no vitest regressions — the full suite
grew from outbound-1's 201 to 212 JVM tests (T1 through T8 each added
coverage; see the ledger for the per-task deltas) with zero failures at any
point in this sweep.

## Parity proof

Three independent proofs establish crypto/protocol fidelity against real
hearth, in addition to the loopback gate:

### T8 — the loopback fidelity gate (the headline proof)

`SyncResponseLoopbackTest.phoneComposedReactionCommentRetractFidelityGate`
(`modules/tor-manager/android/src/test/java/expo/modules/tormanager/SyncResponseLoopbackTest.kt`)
starts a **real hearth node subprocess** (`sync_loopback_node.py`'s
`_run_responses` scenario — not a mock, not a bare in-process decrypt) as an
own-device of the phone's identity, with a real friend identity's enc-key
seeded so the mutual-box has a genuine non-dummy slot:

- Connection 1 primes the store (own + friend enckeys, the target journal
  post) — the same HAVE-frame priming pattern outbound-1's compose loopback
  established.
- Connection 2 pushes a phone-composed reaction (`"fire"`) and comment
  (`"nice post!"`) through the real pending-outbound queue. The node
  **decrypts each with its own device enc key**, **independently reverifies
  `responder_sig`** against its own `_sig_ok`/`_response_sig_payload` (not
  trusting the phone's claim), and has the **real friend identity's enc_priv
  genuinely open the mutual_box** — asserted per-event (`sig_ok`,
  `friend_opened`) for both the reaction and the comment.
- Connection 3 (controller addition, carried from Task 5's review since
  retract otherwise had zero behavioral coverage) pushes a retract whose body
  is `LocalApi.pyStr(reactionCreatedAt)` — **the exact function the real
  `/api/retract` route uses**, not a hand-rolled `Double.toString()`. The
  node accepts and decrypts it like any other response, then runs **hearth's
  real author fold** (`node.process_responses` -> `_rebuild_responses_record`,
  not a stub) and reports whether the reaction was **actually withdrawn**.
  The test asserts `retracted.applied == true` — proof the pyStr-formatted
  body genuinely string-matched `str(the reaction's created_at)` inside the
  real fold's comparison (`node.py:2648-2653`), not merely that the retract
  message was accepted onto the wire.

If the seal, AAD, signature payload, or HKDF info were a byte off, the node's
own `_sig_ok` check or AEAD tag would fail and the gate would fail closed —
not soft-pass. Production code needed **zero changes** to pass this gate,
which is itself a fidelity signal (the one bug the gate did surface, Task 8's
first RED, was in the *test harness* — it was cross-checking against
`identity_pub` instead of `device_pub`; fixed in `aaaf4e7` to mirror hearth's
own `node.py:2525-2533` fail-closed guard, confirmed against hearth's source,
not adjusted to make the test pass).

### T1 — hearth-sealed vector pins the Python -> Kotlin open direction

`KotlinSealTest.tryOpenSlotsOpensAHearthSealedVector`
(`modules/tor-manager/android/src/test/java/expo/modules/tormanager/KotlinSealTest.kt:45`)
loads `seal_vector.json` — generated by hearth's real `dmcrypt.seal_slots`
(not Kotlin-generated, then round-tripped through hearth's own
`try_open_slots` to confirm it before being written out) — and asserts
`KotlinSeal.tryOpenSlots` recovers the exact payload bytes, plus that a wrong
key opens nothing. This is the Python-seals / Kotlin-opens direction; the
inverse (Kotlin-seals / Python-opens) is what T8's loopback proves. Passed
first run with the hardened vector (an earlier self-round-trip-only version
of this test could not have caught a systematic parity bug — the ledger
flags this as an important finding fixed before merge to this task's base).

### T2 — pinned hearth alias-seed hexes

`KotlinDmcryptTest` (`modules/tor-manager/android/src/test/java/expo/modules/tormanager/KotlinDmcryptTest.kt:140-142`)
cross-checks `deriveAliasSeed` against hex values independently derived from
hearth's `identity.py:323-364` `derive_alias_seed`:

```
derive_alias_seed("22"*32, "post1") == "ca19e85f36529f393784b52a001e6133"[:32]  ->  a1
derive_alias_seed("22"*32, "post2")                                            ->  b
```

(reviewer re-derived both hashes two independent ways and got an exact
match — this exceeded the task brief, which only asked for a self-consistency
check).

## On-device DoD — G20 (August drives)

**Status: PASSED — confirmed by August 2026-07-22** ("It works") after
driving the run below on the G20 with the fix-wave build installed. The
checklist is copied verbatim from `.superpowers/sdd/task-9-brief.md` Step 3.

**Field lessons (do these first, carried from outbound-1 + this slice's
gotchas — see "Run gotchas" below):**
- Desktop node on `serve --tor`, unlocked, with a friend **and a mutual
  friend** synced (mutual-box needs a real friend enc-key to open into).
- Install the **RELEASE** apk. Force-stop the app first, or reinstall hangs.

### Steps

Desktop `serve --tor`, unlocked, with a friend + a mutual friend synced. On
the phone: react + comment on a friend's post AND your own; verify:

- [ ] (a) the reaction picker + comment box are visible
- [ ] (b) your reaction/comment appears **on the desktop feed** right away
      (composed + pushed on next sync); on the **phone's own** feed it may
      take a fold cycle to show `mine`/your reaction as active — see "Own-
      response read-side" under Honest boundary above before treating a
      delay as a bug. Give it ~1 sync interval + the desktop's fold sweep,
      then pull-to-refresh (or re-open the post) before concluding it failed.
- [ ] (c) on the desktop feed it shows with correct attribution (your name to
      mutual friends)
- [ ] (d) a friend's response on your phone shows their **REAL NAME** (not an
      alias) after this build (the read de-anon)
- [ ] (e) a stranger's response shows an alias
- [ ] (f) un-react/retract works on the phone: the retract "×" appears on
      YOUR OWN comment/reaction (this fix wave — it did not render at all
      before, see "Final-review fix wave" above) once the fold in (b) has
      caught up, and un-reacting behaves like (b) — expect the SAME
      fold-cycle lag before the active state clears, not an instant flip.
- [ ] (g) regression: DM composer + profile Arrange stay hidden

### Verdict (August to fill)

> **PASS** — August, 2026-07-22: "It works" (on-device run on the G20,
> fix-wave build). No issues reported.

## Install confirmation (this session, final-review fix wave)

Device: G20, serial `ZY32DLZQ2N`, package `eu.kreds.torspike`, RELEASE apk
only (rebuilt against this fix wave's HEAD).

```
adb shell am force-stop eu.kreds.torspike        -> (no output, success)
adb install -r -d app-release.apk                -> "Performing Streamed Install" / "Success"
```

The install call hung silently for several minutes before returning —
`adb shell dumpsys window | grep mCurrentFocus` showed a Google Play
Protect "send this unrecognized app for a security scan?" dialog had
taken focus (the app had, in fact, already installed by that point — its
icon was visible on the home screen behind the dialog). Dismissed via
`adb shell input tap` on "Send ikke" (Don't send — the privacy-preserving
choice for an unreleased, pre-IP-filing sideloaded APK), after which the
install call returned `Success` immediately. See "Run gotchas" below for
the added field lesson.

No behavioral checks were run beyond confirming the install succeeded — the
DoD above is August's to drive.

## Run gotchas

- **A Play Protect scan-consent dialog can silently block `adb install`
  with no error and no timeout** (hit in this session's reinstall,
  final-review fix wave). If a reinstall hangs with no output after
  "Performing Streamed Install" (or even before it), check `adb shell
  dumpsys window | grep mCurrentFocus` for
  `PlayProtectDialogsActivity` before assuming a transfer stall — the app
  may have already installed and just be waiting on the post-install
  scan-consent prompt. `adb shell input tap` on the dialog's "don't send"
  option unblocks it (screenshot via `adb shell screencap -p /sdcard/x.png`
  + `adb pull` to find the exact coordinates first — they can shift with
  device language/resolution). The original 9-task report already flagged
  hitting this class of dialog once before (Task 8's install); this is the
  second occurrence, now with root-cause + workaround documented.

- **`org.json` parses decimal literals as `BigDecimal`, not `Double`.**
  Task 5 found the route-side `pyStr` had a dead `is Double` branch — retract
  `created_at` round-tripped through JSON parsing arrives as `BigDecimal`/
  `BigInteger`, so the dead branch meant retract only "worked" by
  coincidence. Fixed (`66f6bc2`) to route `BigDecimal`/`BigInteger` through
  the already-proven `PyFloat`/`pyFloatRepr` path and throw (-> 400,
  fail-closed) on anything else. Pinned with a real-`org.json`-pipeline type
  assertion, not a hand-constructed `Double`.
- **The loopback harness's own first RED was a harness bug, not a product
  bug:** Task 8's harness initially cross-checked `responder_sig` against
  `identity_pub` instead of `device_pub`. Fixed in `aaaf4e7` to mirror
  hearth's real fail-closed guard (`node.py:2525-2533`) — verified against
  hearth's source before accepting the fix, specifically to rule out
  pass-chasing.
- **RELEASE-apk-only + force-stop-first are still the field rules** carried
  from outbound-1: the debug apk fails to load the JS bundle on this device
  config, and skipping `am force-stop` before `adb install -r` can hang the
  install.

## Honest boundary

- **Own-response read-side (`mine`/`my_reaction`) — what shows and when.**
  Read this before the on-device run so a delay does not read as a failure.
  The phone has no port of `node.process_responses` (the author-side fold
  that rebuilds `KIND_RESPONSES` from the raw `KIND_RESPONSE` rows) — same
  boundary this doc already documented for the read de-anon side, now
  spelled out for `mine`/`my_reaction` too, because it is exactly hearth's
  own behavior, not a phone-specific gap:
  - **Immediately after composing** (react/comment on ANY post, yours or a
    friend's): the phone's own local store already holds the raw response
    it just wrote (self-readable wrap), but that alone changes NOTHING
    visible — `mine`/`my_reaction` are resolved only from entries inside an
    already-**folded** `KIND_RESPONSES` record (mirroring hearth's
    `_post_responses_view`, which returns nothing at all — not even your
    own entries — when `store.responses_record(...)` is `None`). If the
    post has no folded record yet at all, `responses` stays `null` for
    that row exactly as before this fix; if it has a STALE folded record
    (one that predates your new response), your new response is simply
    absent from `reactions`/`comments`/`my_reaction` until a newer record
    arrives.
  - **Once the post author's node (desktop, running `process_responses`
    on its ~3s sweep) folds and republishes, and that record syncs down
    to the phone:** the phone's new `DecryptPass.ownRawByCreatedAt` step
    matches the folded entry to the phone's own locally-held raw response
    by `created_at` and resolves it to `mine:true` — a real name (not
    "you"; see below), the retract "×", and `my_reaction` — with NO
    further action needed on the phone. For a reaction ON YOUR OWN post,
    this requires YOUR OWN account's desktop device to be the one folding
    (an own-post's `KIND_RESPONSES` is signed by the post's own author);
    for a reaction on a FRIEND's post, it's the friend's own node that
    folds.
  - **Un-reacting ("clear") behaves the same way, not instantly:** hearth's
    fold treats "clear" as removing the previous reaction ENTRY, never as
    an entry itself (`node.py:2698-2699`) — so `my_reaction` stays stuck at
    the pre-clear value until the NEXT fold publishes a record that omits
    the entry. There is no local/optimistic clear; expect the picker's
    active state to lag one fold cycle behind a un-react tap, same as a
    react.
  - **Display uses hearth's own bare name, not a "you" literal.** The
    JSON-facing `comments[].name` field is hearth's exact
    `names.get(identity, identity[:8])` fallback (your stored profile name,
    or your own identity's first-8-hex) — matching what a mutual friend
    would see too, deliberately NOT the native-app-only "you" label
    `KotlinResponses.Comment.display` computes for a possible future native
    UI. `mine:true` is what actually drives the retract affordance in
    `app.js`, not the display string.
  - **What this does NOT extend to:** hearth's OWN step 1
    (`raw_by_created_at`) additionally resolves EVERY responder's identity
    when the reading node IS the post's own author (routing sends every
    raw response there) — e.g. a post author's desktop can name a stranger
    by cert alone, no mutual-box trial-open needed. This fix ported only
    the always-available subset (the viewer's OWN composed responses); the
    phone does not attempt the full author-side breadth. A friend's
    response on your phone still resolves only via the existing mutual-box
    de-anon path (real name to mutual friends, alias to strangers) — that
    part is unchanged by this fix wave.
- **Reactions + comments + retract, journal only.** Same scope boundary as
  outbound-1's journal-only compose — profile-wall responses are a later
  slice (composer stays hidden; `#profile-wall-compose` is unaffected by this
  slice's seam edit).
- **Private-by-default = name-to-your-friends, via the mutual-box.** A
  response is sealed so that only your friends (mutual-box slots) can resolve
  who made it; a public-visibility toggle (`public_engagement`) is **not**
  implemented — everything composed by the phone is private-by-default,
  matching hearth's own default.
- **Alias is per-device, by hearth design** — not a Kotlin shortcut. A
  stranger sees a different alias for the same identity on different devices
  reading the same response, because `deriveAliasSeed` is keyed on the
  *reading* device's raw `device_priv`, matching `identity.py`'s existing
  behavior exactly.
- **Author moderation (`/api/response-remove`) is deliberately out.** It is
  not a thin route away — hearth's `remove_response` tombstones and rebuilds
  the responses record server-side; there is no Kotlin port of that fold.
  It's also currently unreachable (`can_moderate:false` is hardcoded), so
  nothing regresses by deferring it.
- **Profile-wall responses deferred** — same reasoning as the journal-only
  boundary above.

## Follow-up tickets (non-blocking, carried from the ledger)

- **`KotlinDmcrypt.kt:60`** — `mac.update` uses `target.length` (a UTF-16
  char count), not `target.toByteArray().size`. Dormant today because targets
  are always 64-hex `msg_id`s (ASCII), but a non-ASCII target would silently
  truncate the MAC input. One-line defensive fix, flagged for triage at final
  review (Task 2).
- **`SyncStore.messageById`** — lacks the file-convention doc comment that
  would record the plain-`getString`-vs-CursorWindow-chunking decision (the
  chunk precedent applies only to `blobs.data`; `msg_json` TEXT is never near
  the 2 MiB threshold) — a documentation gap, not a correctness gap (Task 3).
- **Public-entry mutual-box fallback** — Kotlin's read path skips the
  mutual-box fallback hearth would attempt for *public* entries (Kotlin only
  resolves via mutual-box; public entries that aren't otherwise resolvable
  are under-attributed rather than mis-attributed). Brief-authorized scope
  cut; wants a doc caveat at the call site (Task 6).
- **Multi-enc-key trial-open** — if the phone ever rotates its own enc key,
  older mutual-box slots sealed to a prior enc key won't open under the
  current single-key `tryOpenSlots` call path. No rotation exists yet, so
  this is dormant; flagged for whenever enc-key rotation is built.
- **`chachaSeal`/`chachaOpen`/`randomBytes` DRY refactor** — `KotlinSeal`
  verbatim-duplicates these three helpers from `KotlinDmcrypt` (the task
  brief mandated the mirror to keep the two crypto surfaces independently
  auditable); a future consolidation is optional hardening, not required
  (Task 1).
- **`public_engagement` toggle** — not implemented; everything is
  private-by-default (see Honest boundary above).
- **Author moderation (`/api/response-remove`)** — needs a Kotlin port of
  hearth's tombstone-and-rebuild fold; currently unreachable
  (`can_moderate:false` hardcoded) so non-blocking.
- **Profile-wall responses** — same shape as outbound-1's journal-only
  boundary; a later slice.

## After the run

On a pass, whether this merges to public main is August's call, same as
outbound-1. PAUSE here for human review per the task brief.
