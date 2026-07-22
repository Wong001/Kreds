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
  profile-wall composer, moderation controls — stays hidden.

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

**Status: PENDING.** Not run by Claude — per this task's instructions, only
the install was verified (see below). This checklist is copied verbatim from
`.superpowers/sdd/task-9-brief.md` Step 3.

**Field lessons (do these first, carried from outbound-1 + this slice's
gotchas — see "Run gotchas" below):**
- Desktop node on `serve --tor`, unlocked, with a friend **and a mutual
  friend** synced (mutual-box needs a real friend enc-key to open into).
- Install the **RELEASE** apk. Force-stop the app first, or reinstall hangs.

### Steps

Desktop `serve --tor`, unlocked, with a friend + a mutual friend synced. On
the phone: react + comment on a friend's post AND your own; verify:

- [ ] (a) the reaction picker + comment box are visible
- [ ] (b) your reaction/comment appears
- [ ] (c) on the desktop feed it shows with correct attribution (your name to
      mutual friends)
- [ ] (d) a friend's response on your phone shows their **REAL NAME** (not an
      alias) after this build (the read de-anon)
- [ ] (e) a stranger's response shows an alias
- [ ] (f) un-react/retract works
- [ ] (g) regression: DM composer + profile Arrange stay hidden

### Verdict (August to fill)

> _(pass / partial / fail + notes)_

## Install confirmation (this session)

Device: G20, serial `ZY32DLZQ2N`, package `eu.kreds.torspike`, RELEASE apk
only.

```
adb shell am force-stop eu.kreds.torspike        -> (no output, success)
adb install -r -d app-release.apk                -> "Performing Streamed Install" / "Success"
```

No behavioral checks were run beyond confirming the install succeeded — the
DoD above is August's to drive.

## Run gotchas

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
