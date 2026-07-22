# Android Outbound — DM Send (text + photo + story-reply) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Send DMs (text, photos, story replies, optional expiry) from the phone, byte-identical to desktop-composed KIND_DMs, with the story-reply chip live and sent bubbles rendering immediately.

**Architecture:** A third sibling orchestrator `ComposeDm` mirrors hearth `node.compose_dm` (node.py:2308-2345) reusing the outbound-1/2 crypto (`dmAad` already ported); a multipart `POST /api/dm` route mirrors api.py:666-681; the seam reveals `#dm-compose`; a real-node loopback gate has hearth act as the DM RECIPIENT (friend enc keys) and drives `store.sweep_expired` for the expiry proof. Spec: `docs/superpowers/specs/2026-07-22-android-outbound-dm-send-design.md`.

**Tech Stack:** Kotlin + BouncyCastle (existing KotlinDmcrypt/KotlinBlobCrypt/KotlinWire), Multipart.parse + PhotoPrep (outbound-1), sync_loopback_node.py harness, shared hearth/web app.js/style.css.

## Global Constraints

- **Branch:** `brick-outbound-dm`. Commit prefix `feat/fix/docs/test(dm)`, lowercase. **NO AI / Co-Authored-By / "Generated with" trailers.**
- **Scope:** 1:1 DMs, journal-independent; text + photos + story_ref + expires_seconds. OUT: group DMs, unsend, read receipts, story posting, profile slices.
- **Byte-exact (verbatim from hearth):**
  - AAD = `dm_aad(sender, to, created_at)` = `canonical({"type":"dm-aad","protocol":"hearth/v0.2","from":<sender>,"to":<to>,"created_at":<PyFloat>})` — REUSE `KotlinDmcrypt.dmAad` (KotlinDmcrypt.kt:25), do NOT re-derive. Wrap AAD == body AAD.
  - Recipient set `_dm_device_pubs` (node.py:2308-2315): `theirs = enckeys(to)` REQUIRED non-empty else error `"no encryption keys known for recipient yet"`; `mine = enckeys(own) + [fx.device_pub -> encPub]`; merged theirs-then-mine.
  - Validation order + exact strings (node.py:2320-2330): `to == own` → `"cannot DM yourself"`; `to !in knownIdentities()` → `"recipient is not a friend"`; present-but-bad story_ref → `"bad story_ref"`.
  - story_ref shape guard (messages.py:241+ `_valid_story_ref`): dict; `story_id` non-empty String (opaque, NOT hex64-required); `media_hash` hex64 blob hash; extra keys PASS.
  - Encrypted body = `{"text": text, "blobs": refs}`; envelope = make_dm (messages.py:151-156): `{kind:"dm", to, body_nonce, body_ct, wraps, blobs: refs, created_at: PyFloat, expires_at: PyFloat|null, story_ref: dict|null}`. Text ONLY inside the body; story_ref PLAINTEXT envelope (named disclosure).
  - `expires_at = created_at + expires_seconds` when provided else null — HONORED (outbound-1's final-review lesson; dedicated loopback assertion).
  - Blobs: PhotoPrep-gated JPEG → `KotlinBlobCrypt.encryptBlob(key, jpeg)` → stored; refs = ciphertext hashes, in BOTH body and envelope.
- **Reuse (do NOT re-port):** `KotlinDmcrypt.wrapKey/encryptBody/dmAad`, `KotlinBlobCrypt.encryptBlob`, `KotlinWire.signRaw/canonical/PyFloat`, `Multipart.parse`, `PhotoPrep.toUploadJpeg`, `store.enckeys/knownIdentities/nextSeq/ingestMessage/addPendingOutbound`, the `SignedMessage` device-sign tail from `Compose.kt`/`ComposeResponse.kt`.
- **Decrypt-on-read preserved (dmKeysCache warm is in-memory only); device-signs only; the seam reveals ONLY `#dm-compose`.**

**Test commands** (git-bash; `JAVA_HOME=/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot`; from `android_tor_spike/app/android` unless noted):
- Kotlin JVM: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.<Class>"`; XML count glob is `../modules/tor-manager/android/build/test-results/testDebugUnitTest/*.xml`.
- vitest (from `android_tor_spike/app`): `npx vitest run test/web-readonly-seam.test.ts`.
- Release APK: `./gradlew :app:assembleRelease`; G20 install: force-stop `eu.kreds.torspike` first, `adb install -r -d`, watch for the Play Protect consent dialog.

---

## File Structure

- `ComposeDm.kt` (create) — the write orchestrator.
- `LocalApi.kt` (modify) — `POST /api/dm` multipart route + parse helpers (companion, testable) + dmKeysCache warm.
- `hearth/web/style.css` (modify) — reveal `#dm-compose`.
- `android_tor_spike/tools/sync_loopback_node.py` (modify) — `dm` scenario (node as recipient).
- Tests: `ComposeDmTest.kt` (new), `LocalApiTest.kt` (extend), `web-readonly-seam.test.ts` (extend), `SyncDmLoopbackTest.kt` (new).
- `android_tor_spike/BRICK_DM_REPORT.md` (create, Task 5).

---

## Task 1: `ComposeDm` orchestrator

**Files:**
- Create: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/ComposeDm.kt`
- Test: `.../src/test/java/expo/modules/tormanager/ComposeDmTest.kt`

**Interfaces:**
- Consumes: `KotlinDmcrypt.dmAad/wrapKey/encryptBody`, `KotlinBlobCrypt.encryptBlob`, `KotlinWire.PyFloat/canonical/signRaw`, `store.enckeys/knownIdentities/nextSeq/ingestMessage/addPendingOutbound`, `KotlinHandshake.Fixture`, `SignedMessage` (mirror the device-sign + enqueue tail of `ComposeResponse.kt` — read it first).
- Produces: `ComposeDm.compose(store, fx, encPriv, encPub, to: String, text: String, photoJpegs: List<ByteArray>, expiresSeconds: Double?, storyRef: Map<String, Any?>?, createdAt: Double): Result`; `data class Result(val msgId: String, val wireDict: Map<String, Any?>, val contentKey: ByteArray)` (contentKey is what Task 2 warms dmKeysCache with — hearth's `_cache_message_key` analog).
- Also produces: `ComposeDm.validStoryRef(sref: Map<String, Any?>?): Boolean` (the `_valid_story_ref` mirror, public for Task 2's route test).

**Design (mirror node.py:2308-2345 EXACTLY; Global Constraints has the shapes):**
1. Validations in hearth's order, `IllegalArgumentException` with hearth's exact strings: self-DM; not-a-friend (`to !in store.knownIdentities()` — hearth `store.is_known`); `storyRef != null && !validStoryRef(storyRef)`.
2. `theirs = store.enckeys(to)`; require non-empty (`"no encryption keys known for recipient yet"`). `pubs = theirs + enckeys(own) + (fx.device_pub to encPub)` (LinkedHashMap, theirs first — merge semantics of `{**theirs, **mine}`: mine WINS on a shared device_pub key).
3. `expiresAt = expiresSeconds?.let { createdAt + it }`; `aad = KotlinDmcrypt.dmAad(own, to, createdAt)`; `key = 32 random bytes` (mirror the sibling's key generation).
4. Photos are ALREADY PhotoPrep-gated JPEGs when they reach compose (the route gates; tests pass raw bytes) — for each: `ct = KotlinBlobCrypt.encryptBlob(key, jpeg)`, store it and collect the ciphertext-hash ref EXACTLY the way `Compose.kt` (posts) handles its blob storage/refs — read Compose.kt first and match its mechanics (hearth does `store.put_blob(encrypt_blob(...))` inline; if Compose defers storage to its caller via Result, ComposeDm defers identically and Task 2 mirrors the /api/post route's storage step — state which in the report).
5. `(nonce, ct) = encryptBody(key, {"text": text, "blobs": refs}, aad)`; `wraps = wrapKey(key, pubs, aad)`.
6. Envelope payload: `{"kind":"dm", "to":to, "body_nonce":nonce, "body_ct":ct, "wraps":wraps, "blobs":refs, "created_at":PyFloat(createdAt), "expires_at": expiresAt?.let{PyFloat(it)}, "story_ref": storyRef}` — null expires_at/story_ref serialize as JSON null (make_dm always emits both keys; match key presence exactly).
7. Device-sign / `ingestMessage` / `addPendingOutbound(msgId, toDict)` — identical tail to `ComposeResponse.kt`. Return `Result(msgId, toDict, key)`.

- [ ] **Step 1: Write the failing tests** (ComposeDmTest.kt; mirror ComposeResponseTest's store/fixture scaffolding — read it first):

```kotlin
    @Test fun composeTextDmDecryptsViaOwnWrapAndRecipientWrap() {
        // scaffold: fixture fx (own), InMemory store; seed own enckey AND a
        // friend identity "to" with an enckey whose priv the test holds
        // (independent X25519 keypair -> non-tautological recipient check).
        val r = ComposeDm.compose(store, fx, encPriv, encPub, friendId,
            "hej fra telefonen", emptyList(), null, null, 1753100000.25)
        val env = r.wireDict["payload"] as Map<*, *>
        assertEquals("dm", env["kind"]); assertEquals(friendId, env["to"])
        assertTrue(env.containsKey("expires_at")); assertNull(env["expires_at"])
        assertTrue(env.containsKey("story_ref")); assertNull(env["story_ref"])
        // own-device wrap -> proven inverses recover the body
        val aad = KotlinDmcrypt.dmAad(fx.cert.identity_pub, friendId, 1753100000.25)
        val key = KotlinDmcrypt.unwrapKey((env["wraps"] as Map<*, *>)[fx.device_pub] as Map<*, *>, encPriv, aad)!!
        val body = KotlinDmcrypt.decryptBody(key, env["body_nonce"] as String, env["body_ct"] as String, aad)!!
        assertEquals("hej fra telefonen", body["text"]); assertEquals(emptyList<Any>(), body["blobs"])
        // recipient wrap opens with the FRIEND's independent enc_priv
        val fkey = KotlinDmcrypt.unwrapKey((env["wraps"] as Map<*, *>)[friendDevPub] as Map<*, *>, friendEncPrivHex, aad)
        assertNotNull(fkey)
        // pending-outbound enqueued
        assertTrue(store.pendingOutbound().any { it.first == r.msgId })
        assertArrayEquals(key, r.contentKey)
    }

    @Test fun composeDmValidationMirrorsHearth() {
        assertThrowsMsg("cannot DM yourself") { ComposeDm.compose(..., to = fx.cert.identity_pub, ...) }
        assertThrowsMsg("recipient is not a friend") { ComposeDm.compose(..., to = strangerId, ...) }
        assertThrowsMsg("bad story_ref") { ComposeDm.compose(..., storyRef = mapOf("story_id" to ""), ...) }
        assertThrowsMsg("no encryption keys known for recipient yet") { /* friend known but no enckey seeded */ }
    }

    @Test fun expiryAndStoryRefRideTheEnvelope() {
        val sref = mapOf("story_id" to "s1", "media_hash" to "ab".repeat(32), "extra" to 1)
        assertTrue(ComposeDm.validStoryRef(sref))   // extra keys pass
        assertFalse(ComposeDm.validStoryRef(mapOf("story_id" to "s1")))          // media_hash missing
        assertFalse(ComposeDm.validStoryRef(mapOf("story_id" to "s1", "media_hash" to "zz")))
        val r = ComposeDm.compose(..., expiresSeconds = 60.0, storyRef = sref, createdAt = 1753100000.25)
        val env = r.wireDict["payload"] as Map<*, *>
        assertEquals(1753100060.25, (env["expires_at"] as KotlinWire.PyFloat).value)  // exact float
        assertEquals(sref, env["story_ref"])
    }

    @Test fun photoDmBlobRefsAreCiphertextHashesDecryptableByContentKey() {
        val jpeg = byteArrayOf(1, 2, 3, 4, 5)
        val r = ComposeDm.compose(..., photoJpegs = listOf(jpeg), ...)
        val env = r.wireDict["payload"] as Map<*, *>
        val refs = env["blobs"] as List<*>; assertEquals(1, refs.size)
        // body refs == envelope refs; blob decrypts back to the jpeg with the content key
        // (retrieve the stored ciphertext per Compose.kt's storage mechanics)
        assertArrayEquals(jpeg, KotlinBlobCrypt.decryptBlob(r.contentKey, storedCiphertext))
    }
```

(Exact PyFloat/typing assertions may need the same adjustments ComposeResponseTest made — adjust mechanics, never the meaning.)

- [ ] **Step 2: Run → fail** (`--tests "expo.modules.tormanager.ComposeDmTest"`), unresolved ComposeDm.
- [ ] **Step 3: Implement ComposeDm.kt** per the Design. Read `ComposeResponse.kt` + `Compose.kt` + hearth node.py:2308-2345/messages.py:134-156 FIRST.
- [ ] **Step 4: Run → pass. Full module suite.**
- [ ] **Step 5: Commit** — `feat(dm): ComposeDm orchestrator (encrypt + wrap + sign + enqueue, expiry honored)`.

---

## Task 2: `POST /api/dm` route

**Files:**
- Modify: `.../tormanager/LocalApi.kt`
- Test: `.../test/.../LocalApiTest.kt` (parse helpers only; route behavior gated by Task 4 loopback + Task 5 on-device, matching the /api/post + /api/react precedent)

**Interfaces:**
- Consumes: `ComposeDm.compose` + `ComposeDm.validStoryRef` (T1), `Multipart.parse`, `PhotoPrep.toUploadJpeg`, `sharedStore`, `fixtureOrNull`, `EncKeys.getOrCreate`, the existing `dmKeysCache`.
- Produces (companion, for tests): `parseExpiresSeconds(raw: String): Double?` — blank/whitespace → null; else `toDoubleOrNull() ?: throw IllegalArgumentException("bad expires_seconds")` (spec-documented deliberate 400-vs-desktop-500 divergence, unreachable from app.js); `parseStoryRef(raw: String): Map<String, Any?>?` — blank → null; bad JSON → `IllegalArgumentException("bad story_ref")` (mirrors `_parse_json_field` naming the field, api.py:68-77); JSON object → map via the module's established org.json→Map conversion (read how existing code converts JSONObject — MsgJson/KotlinSync idiom, BigDecimal-aware).

**Route (mirror api.py:666-681; insert with the other POST routes before the GET guard):** `POST /api/dm`, multipart: `to` (required), `text` (default ""), `expires_seconds`, `story_ref`, `photos` file parts. Every file part through `PhotoPrep.toUploadJpeg` (mandatory EXIF strip; null → 400 `"bad image"` BEFORE compose — no partial sends). Then `ComposeDm.compose(sharedStore, fx, encPriv, encPub, to, text, jpegs, parseExpiresSeconds(...), parseStoryRef(...), System.currentTimeMillis()/1000.0)`. On success: warm the cache — `dmKeysCache = dmKeysCache + (r.msgId to r.contentKey)` — and return 200 `{"msg_id": r.msgId}` (desktop's exact success shape). `IllegalArgumentException` → 400 with the message; other → 500 `"compose failed: ..."` (the established split). If T1 deferred blob storage to the caller, store blobs here exactly as the /api/post route does.

- [ ] **Step 1: Failing tests** for the two companion parsers (blank → null; " 60 " handling per toDoubleOrNull semantics — pin actual behavior; garbage → throws; story_ref bad JSON → throws with `"bad story_ref"`; valid JSON object round-trips incl a fractional number field). 
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Implement** parsers + route.
- [ ] **Step 4: Run → pass. Full module suite (route compiles; 400-mapping consistent with /api/react's catch).**
- [ ] **Step 5: Commit** — `feat(dm): POST /api/dm multipart route (photos gated, expiry + story_ref parsed, sent-key warm)`.

---

## Task 3: Seam reveal + story-reply path verification

**Files:**
- Modify: `hearth/web/style.css`
- Test: `android_tor_spike/app/test/web-readonly-seam.test.ts`

- [ ] **Step 1: Update the vitest guard** — `#dm-compose` NO LONGER hidden in `body.readonly`; `#profile-wall-compose`, `#profile-arrange`, `.story-tile .story-ring.add`, `#profile-cog`, `#profile-addfriend`, `.pact.del`, `.settings-del`, ring-move, btn-danger STILL hidden (mirror outbound-2 Task 7's structure).
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Remove `#dm-compose`** from the `body.readonly` list; update the block comment (dm slice reveals it).
- [ ] **Step 4: Story-reply verification (report, no code expected):** grep `hearth/web/app.js` for the story viewer's reply UI (the `sendReply`/replyForm block around app.js:3200-3228) — confirm (a) it POSTs `/api/dm` with `story_ref` + `to`, (b) none of its selectors appear in the `body.readonly` hide list (it was never seam-hidden; it 404'd pre-slice), (c) `#dm-compose` shares no class/id with any still-hidden surface. Record the grep evidence.
- [ ] **Step 5: Run → pass + full vitest.**
- [ ] **Step 6: Commit** — `feat(dm): reveal dm composer in the seam`.

---

## Task 4: Loopback fidelity gate — real node RECEIVES the phone's DMs

**Files:**
- Modify: `android_tor_spike/tools/sync_loopback_node.py` (add a `dm` scenario)
- Create: `.../test/.../SyncDmLoopbackTest.kt`

- [ ] **Step 1: Extend the harness** — scenario seeds: node identity == phone fixture identity (own-identity model), a FRIEND identity + enc keypair the node holds the priv for (self-DM is rejected — the DM targets the friend), phone's own enckey. After ingesting each pushed KIND_DM, the node acts as the RECIPIENT with REAL hearth crypto: `dmcrypt.unwrap_key(wraps[friend_device], friend_enc_priv, dm_aad(...))` with the AAD RECOMPUTED from the envelope's own cert/fields (never phone-supplied out-of-band); `decrypt_body` → text; `decrypt_blob` for each ref (AEAD-verified photo bytes); `story_ref` re-validated via hearth's real `_valid_story_ref` + ingest `validate_payload` accepting the message at all; cross-check `body["blobs"] == payload["blobs"]`. Emits `{"event":"dm","text_ok","blob_ok","story_ref_ok","expires_at"}` per DM — fail-closed, no try/except soft passes (outbound-2's harness discipline, incl the body-vs-cert cross-checks where hearth's ingest has them — read `_response_event`'s guard style).
  For the EXPIRING DM: after the event, call `store.sweep_expired(now = created_at + expires_seconds + 1)` (hearth's REAL sweep, store.py:432-447) and emit `{"event":"dm_expired","swept": <msg_id in swept list>}`.
- [ ] **Step 2: Write SyncDmLoopbackTest.kt** — mirror `SyncResponseLoopbackTest`'s two-connection pattern: pull seed state (connection 1); compose four DMs via the REAL `ComposeDm.compose` — (1) text `"hej fra telefonen 🌸"` (UTF-8 through multipart is Task 2's; here compose-level), (2) photo (small valid JPEG fixture bytes), (3) story-reply (`story_ref = {"story_id": <seeded story id or opaque string>, "media_hash": <a seeded blob hash>}`), (4) `expiresSeconds = 60.0`; push via `store.pendingOutbound()` (connection 2); `awaitEvent("dm")` ×4 asserting `text_ok/blob_ok/story_ref_ok` true where applicable and event-4 `expires_at == created_at + 60.0` exactly; `awaitEvent("dm_expired")` asserting `swept == true`.
- [ ] **Step 3: Run.** If the node cannot decrypt/validate — REAL fidelity bug: report BLOCKED with the byte difference; NEVER weaken an assertion.
- [ ] **Step 4: Full suite.**
- [ ] **Step 5: Commit** — `test(dm): loopback gate -- real node receives, decrypts, and expires phone dms`.

---

## Task 5: On-device DoD + report + PAUSE

**Files:**
- Create: `android_tor_spike/BRICK_DM_REPORT.md`

- [ ] **Step 1: Desk-gate sweep** — full JVM (XML count), `npx tsc --noEmit` (0 new vs the 14 known @types/node), vitest seam + full, `:app:assembleRelease` with the seam edit verified INSIDE the apk (unzip check: `#dm-compose` absent from body.readonly, `#profile-wall-compose` present).
- [ ] **Step 2: Install RELEASE apk on the G20** (force-stop first; Play Protect dialog gotcha).
- [ ] **Step 3: On-device DoD (August drives)** — desktop `serve --tor`, unlocked, friend synced. From the phone: (a) DM composer visible in a thread; (b) text DM arrives on desktop, right thread/sender; (c) photo DM arrives, renders both ends, EXIF stripped; (d) story reply from the phone story viewer lands as a DM with story context on desktop; (e) expiring DM disappears after TTL on both ends; (f) sent bubble renders on the phone immediately (dmKeysCache warm); (g) regression: profile wall compose / Arrange / story-add stay hidden.
- [ ] **Step 4: Write BRICK_DM_REPORT.md** — desk-gates table, loopback proof section, DoD checklist (PENDING until August runs it), run gotchas, honest boundary (spec's Honest limits verbatim: story_ref + `to` plaintext disclosures, cooperative expiry, recipient-enckey precondition), follow-up tickets.
- [ ] **Step 5: Commit + PAUSE** — `docs(dm): on-device proof record + DoD`. Merge is August's call.

---

## Self-Review

**1. Spec coverage:** ComposeDm model + validations + expiry + story_ref guard → T1; route contract + parsers + PhotoPrep gate + dmKeysCache warm → T2; seam + story-reply-free verification → T3; parity gate incl node-as-recipient, story_ref/ingest validation, real sweep_expired → T4; DoD/report/PAUSE → T5. Sent-DM read-back = verify-only per spec: unit (T1 own-wrap decrypt), on-device (T5 DoD f), with the dmKeysCache warm as the designated fix — covered.
**2. Placeholder scan:** T1 Step-4 blob-storage mechanics is a read-and-match against Compose.kt with both branches stated (inline store vs caller-deferred) — explicit fallback, not a TBD. T2 route has no isolated JVM test by the established /api/post precedent, gated by T4+T5. No other deferrals.
**3. Type consistency:** `ComposeDm.compose(store, fx, encPriv, encPub, to, text, photoJpegs, expiresSeconds, storyRef, createdAt): Result(msgId, wireDict, contentKey)` consistent across T1 (defines), T2 (route calls), T4 (gate calls). `validStoryRef` defined T1, used T2. `parseExpiresSeconds/parseStoryRef` defined+tested T2, used only there. Event names `dm`/`dm_expired` consistent T4 harness/test.

**Implementer notes:** constants live in Global Constraints — verbatim; a byte off = desktop can't decrypt the phone's DM. `expires_at` must flow (the outbound-1 lesson). The mutual-box machinery is NOT involved in DMs — do not import KotlinSeal here.
