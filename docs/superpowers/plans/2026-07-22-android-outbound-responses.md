# Android Outbound — Reactions + Comments (KIND_RESPONSE) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Compose reactions + comments (+ un-react/retract) on journal posts natively in Kotlin, and de-anon responses on read so the phone shows real names to people you know and an alias to strangers — byte-identical to hearth on every platform.

**Architecture:** Port the seal_slots (seal) + try_open_slots (open) mutual-box crypto to Kotlin (`KotlinSeal`); a `ComposeResponse` orchestrator mirrors hearth `node.compose_response` (reusing `wrapKey`/`encryptBody`/`signRaw`/the pending-outbound queue from outbound-1, plus a new single-response `responseAad` and `deriveAliasSeed`); `POST /api/react` + `/api/comment` (+ remove) JSON routes; `tryOpenSlots` threads into the B.2d-4 `KotlinResponses`/`DecryptPass.responsesPass` read path; the reaction picker + comment composer are revealed from the read-only seam. A real-node loopback gate + a both-directions seal vector round-trip prove cross-platform parity.

**Tech Stack:** Kotlin + BouncyCastle (X25519/ChaCha20/HKDF/HMAC — same primitives as B.2/outbound-1); the loopback `sync_loopback_node.py` real-node harness; the shared `hearth/web` app.js/style.css.

## Global Constraints

- **Branch:** `brick-outbound-responses`. Commit prefix `feat/fix/docs(responses)`, lowercase. **NO AI / Co-Authored-By / "Generated with" trailers.**
- **Scope:** reactions + comments + un-react/retract on JOURNAL posts, private-by-default. Public toggle wiring, author-moderation, profile-wall responses, DM-send, inner scope are OUT.
- **Byte-exact crypto (verbatim from hearth):**
  - `MUTUAL_BOX_AAD = "hearth/mutual-box/v1"`; slot-kek HKDF-SHA256 salt=null len=32 `info="hearth/mutual-box-kek/v1"` (DISTINCT from wrap-kek `"hearth/dm-wrap/v1"`); `_SLOT_BUCKETS = (8, 16, 32, 64)`.
  - `response_aad = canonical({"type":"response-aad","protocol":"hearth/v0.2","from":<responder_identity>,"target":<target>,"created_at":<PyFloat>})`.
  - `deriveAliasSeed(devicePrivRaw, target) = HMAC-SHA256( HKDF-SHA256(devicePrivRaw, salt=null, len=32, info="hearth/alias-seed/v1"), target.utf8 ).hex[:32]`. Keyed on device_priv (raw Ed25519 bytes), NOT enc_priv.
  - `_response_sig_payload = canonical({"target":target,"rkind":rkind,"body":body,"created_at":<PyFloat>,"responder":<responder_identity>})`; signed with `signRaw(device_priv, ...)`.
  - sealed mutual-box payload = `canonical({"identity":<responder_identity>,"device_pub":<responder_device_pub>,"sig":<responder_sig>})`, sealed to **my own friends'** enc_pubs (every `enckeys(friend)` value, friends = knownIdentities − self).
  - response body (encrypted, KIND_RESPONSE) = `{rkind, body, alias_seed, public:false, responder:<own_identity>, responder_sig, mutual_box, created_at}`. `make_response` envelope = `{kind:"response", target, body_nonce, body_ct, wraps, created_at}` (rkind/body/etc live ONLY in the encrypted body).
  - wraps = `wrapKey(key, authorDevs, aad)` ∪ `wrapKey(key, ownEnckeys+thisDevice, aad)` (self-readable for the retract UI).
  - `REACTION_TOKENS = ("heart","laugh","wow","sad","up","fire")`; reaction body ∈ REACTION_TOKENS + `("clear",)`; `MAX_COMMENT = 500`.
- **Parity invariant:** a phone-composed KIND_RESPONSE is byte-identical to a desktop-composed one; the de-anon audience is the responder's friend graph, device-independent. Proven by the loopback gate + seal vector round-trip both ways. (The alias itself is per-device by hearth design — accepted.)
- **Reuse (do NOT re-port):** `KotlinDmcrypt.wrapKey/encryptBody/postAad`, `KotlinWire.signRaw/canonical/PyFloat/toHex/fromHex`, `Compose`'s pending-queue enqueue (`store.addPendingOutbound(msgId, wireDict)`) + `store.ingestMessage`, `store.enckeys(identity)` (outbound-1), the B.2d-4 `KotlinResponses` alias derivation + `DecryptPass.responsesPass`.
- **Decrypt-on-read preserved; device-signs only; the seam still hides every non-response write.**

**Test commands** (git-bash; `JAVA_HOME=/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot`, `ANDROID_HOME=~/AppData/Local/Android/Sdk`; from `android_tor_spike/app/android` unless noted):
- Kotlin JVM: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.<Class>"`
- vitest (from `android_tor_spike/app`): `npx vitest run test/web-readonly-seam.test.ts`
- Release APK: `./gradlew :app:assembleRelease`

---

## File Structure

- `KotlinSeal.kt` (create) — `sealSlots` + `tryOpenSlots` (mutual-box crypto).
- `KotlinDmcrypt.kt` (modify) — add `responseAad` (single-response AAD) + `deriveAliasSeed`.
- `ComposeResponse.kt` (create) — the write orchestrator.
- `SyncStore.kt`/`SqliteSyncStore.kt`/`InMemorySyncStore` (modify) — a `messageById(msgId): SignedMessage?` helper (allMessages-scan for the target's author/kind/placement).
- `KotlinResponses.kt` (modify) — thread `tryOpenSlots` de-anon into attribution.
- `DecryptPass.kt` (modify) — pass the phone's `encPriv` into the responses attribution (already available in `responsesPass`).
- `LocalApi.kt` (modify) — `POST /api/react`, `/api/comment`, remove route (JSON).
- `hearth/web/style.css` (modify) — reveal `.rx-open`/`.rx-picker`/`.comment-composer`/`.comment-x`.
- Tests: `KotlinSealTest.kt`, `KotlinDmcryptTest.kt`, `ComposeResponseTest.kt`, `KotlinResponsesTest.kt`, `SyncResponseLoopbackTest.kt` (new), `web-readonly-seam.test.ts`; `sync_loopback_node.py` (extend).

---

## Task 1: `KotlinSeal` — sealSlots + tryOpenSlots (the mutual-box crypto)

**Files:**
- Create: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/KotlinSeal.kt`
- Test: `android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/KotlinSealTest.kt`

**Interfaces:**
- Produces: `KotlinSeal.sealSlots(payload: ByteArray, encPubs: List<String>): List<Map<String,String>>` (each slot `{eph_pub,nonce,ct}` hex; padded to a `_SLOT_BUCKETS` size with byte-random dummies; shuffled). `KotlinSeal.tryOpenSlots(slots: List<Map<String,String>>, encPrivHex: String): ByteArray?` (trial-open; first authenticating slot's payload, else null).

**Design (mirror `hearth/dmcrypt.py` seal_slots/try_open_slots + `KotlinDmcrypt`'s X25519/chacha helpers):** For each enc_pub: `peer = X25519PublicKeyParameters(fromHex(encPub))` (skip malformed); fresh `X25519PrivateKeyParameters(SecureRandom())`; `shared = X25519Agreement().apply{init(eph)}.calculateAgreement(peer,...)` (SAME API as `KotlinDmcrypt.wrapKey`); `kek = HKDF-SHA256(shared, salt=null, len=32, info="hearth/mutual-box-kek/v1")`; 12-byte nonce; `ct = ChaCha20Poly1305(kek).encrypt(nonce, payload, "hearth/mutual-box/v1")` (use the same seal helper shape as `KotlinDmcrypt.chachaSeal`). Slot = `{eph_pub:hex, nonce:hex, ct:hex}`. Bucket = smallest of `(8,16,32,64)` ≥ real count (throw if >64); pad with dummies `{eph_pub:random32.hex, nonce:random12.hex, ct:random(ctLen).hex}` where ctLen = `payload.size + 16`; then shuffle (use a SecureRandom shuffle — order is cosmetic, not the anonymity mechanism). `tryOpenSlots`: for each slot, `shared = X25519Agreement(myPriv).calculateAgreement(slot.eph_pub)`; `kek = sameHKDF`; attempt `ChaCha20Poly1305(kek).decrypt(slot.nonce, slot.ct, MUTUAL_BOX_AAD)`; first success → bytes; all fail → null. A dummy/other-recipient slot fails the AEAD tag.

- [ ] **Step 1: Write the failing tests:**

```kotlin
    @Test fun sealSlotsOpenRoundTripAndStrangerAndBucket() {
        fun kp() = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(java.security.SecureRandom())
        val a = kp(); val b = kp(); val stranger = kp()
        val aPubHex = KotlinWire.toHex(a.generatePublicKey().encoded)
        val bPubHex = KotlinWire.toHex(b.generatePublicKey().encoded)
        val payload = "hi".repeat(20).toByteArray()
        val slots = KotlinSeal.sealSlots(payload, listOf(aPubHex, bPubHex))
        assertEquals("padded to first bucket >= 2", 8, slots.size)   // _SLOT_BUCKETS
        // a recipient recovers the payload; a stranger does not
        assertArrayEquals(payload, KotlinSeal.tryOpenSlots(slots, KotlinWire.toHex(a.encoded)))
        assertArrayEquals(payload, KotlinSeal.tryOpenSlots(slots, KotlinWire.toHex(b.encoded)))
        assertNull(KotlinSeal.tryOpenSlots(slots, KotlinWire.toHex(stranger.encoded)))
        // malformed enc_pub skipped, not thrown
        assertEquals(8, KotlinSeal.sealSlots(payload, listOf(aPubHex, "zz")).size)
    }

    @Test fun sealSlotsBucketsGrow() {
        val payload = "x".toByteArray()
        val pubs = (0 until 9).map { KotlinWire.toHex(
            org.bouncycastle.crypto.params.X25519PrivateKeyParameters(java.security.SecureRandom()).generatePublicKey().encoded) }
        assertEquals("9 real -> bucket 16", 16, KotlinSeal.sealSlots(payload, pubs).size)
    }
```

- [ ] **Step 2: Run → fail** (`--tests "expo.modules.tormanager.KotlinSealTest"`), unresolved `KotlinSeal`.
- [ ] **Step 3: Implement `KotlinSeal.kt`** per the Design above. Read `KotlinDmcrypt.kt`'s `wrapKey`/`chachaSeal`/`chachaOpen`/`deriveKek` FIRST and mirror the exact BouncyCastle X25519/ChaCha calls (only the HKDF info + AAD constants + the padding/shuffle differ). Reuse `KotlinWire.toHex/fromHex`.
- [ ] **Step 4: Run → pass.** Then full module suite.
- [ ] **Step 5: Commit** — stage `KotlinSeal.kt` + `KotlinSealTest.kt`; `feat(responses): KotlinSeal sealSlots + tryOpenSlots (mutual-box crypto)`.

---

## Task 2: `KotlinDmcrypt.responseAad` + `deriveAliasSeed`

**Files:**
- Modify: `.../tormanager/KotlinDmcrypt.kt`
- Test: `.../test/.../KotlinDmcryptTest.kt`

**Interfaces:**
- Produces: `KotlinDmcrypt.responseAad(responder: String, target: String, createdAt: Double): ByteArray` (mirrors `postAad`/`responsesAad`, `type="response-aad"`, `from=responder`); `KotlinDmcrypt.deriveAliasSeed(devicePrivHex: String, target: String): String` (HMAC-SHA256(HKDF-SHA256(raw devicePriv, info="hearth/alias-seed/v1"), target) hex, first 32 chars).

- [ ] **Step 1: Write failing tests:**

```kotlin
    @Test fun responseAadShapeAndAliasSeedDeterministic() {
        val aad = KotlinDmcrypt.responseAad("id".repeat(32), "t".repeat(32), 1752900000.5)
        val s = String(aad)
        assertTrue(s.contains("\"type\":\"response-aad\"") && s.contains("\"from\":\"" + "id".repeat(32) + "\""))
        assertTrue(s.contains("\"target\":\"" + "t".repeat(32) + "\"") && s.contains("\"created_at\":1752900000.5"))
        // deriveAliasSeed: deterministic per (device, target), 32 hex chars, differs by target
        val dpriv = "22".repeat(32)
        val a1 = KotlinDmcrypt.deriveAliasSeed(dpriv, "post1"); val a2 = KotlinDmcrypt.deriveAliasSeed(dpriv, "post1")
        val b = KotlinDmcrypt.deriveAliasSeed(dpriv, "post2")
        assertEquals(32, a1.length); assertEquals(a1, a2); assertNotEquals(a1, b)
    }
```

(If a hearth-produced alias-seed vector is available, assert the exact hex; otherwise the determinism + shape test is the JVM gate and the cross-lang exactness is covered by the loopback gate reading a phone alias vs a hearth alias for the same device.)

- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Implement.** `responseAad` mirrors the existing `responsesAad` exactly (copy it, change `"type"` to `"response-aad"` and the `from` to the responder param). `deriveAliasSeed`: `raw = KotlinWire.fromHex(devicePrivHex)`; `subkey = HKDF-SHA256(raw, salt=null, len=32, info="hearth/alias-seed/v1".toByteArray())` (BouncyCastle `HKDFBytesGenerator(SHA256Digest())`); `mac = HMac(SHA256Digest()); mac.init(KeyParameter(subkey)); mac.update(target.toByteArray()); ...doFinal`; `KotlinWire.toHex(out).substring(0,32)`. Confirm `devicePrivHex` is the RAW Ed25519 device private (fixture `device_priv`) — the same bytes hearth uses.
- [ ] **Step 4: Run → pass.** Full suite.
- [ ] **Step 5: Commit** — `KotlinDmcrypt.kt` + `KotlinDmcryptTest.kt`; `feat(responses): responseAad + deriveAliasSeed`.

---

## Task 3: `messageById` store accessor

**Files:**
- Modify: `SyncStore.kt` (interface + `InMemorySyncStore`), `SqliteSyncStore.kt`
- Test: `SyncStoreTest.kt`

**Interfaces:**
- Produces: `SyncStore.messageById(msgId: String): SignedMessage?` — the stored message (for `ComposeResponse` to resolve a target post's author/kind/placement). `InMemory`: map/scan lookup. `Sqlite`: `SELECT msg_json FROM messages WHERE msg_id = ? LIMIT 1`, parse via `MsgJson.toMap` → `SignedMessageKt.fromDict`.

- [ ] **Step 1: Failing test** — ingest a KIND_POST, assert `messageById(its msgId)` returns it (payload kind/placement readable) and `messageById("ff"*32)` is null.
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Implement** both impls (mirror how `DecryptPass.responsesPass` currently finds a target via `allMessages().firstOrNull{...}` — this accessor makes it a first-class lookup; Sqlite uses the indexed `msg_id` PK).
- [ ] **Step 4: Run → pass.** Full suite.
- [ ] **Step 5: Commit** — `feat(responses): messageById store accessor`.

---

## Task 4: `ComposeResponse` orchestrator

**Files:**
- Create: `.../tormanager/ComposeResponse.kt`
- Test: `.../test/.../ComposeResponseTest.kt`

**Interfaces:**
- Consumes: `KotlinSeal.sealSlots` (T1); `responseAad`/`deriveAliasSeed` (T2); `messageById` (T3); `KotlinDmcrypt.wrapKey/encryptBody`; `KotlinWire.signRaw/canonical/PyFloat`; `store.enckeys`, `store.knownIdentities`, `store.nextSeq`, `store.ingestMessage`, `store.addPendingOutbound`; `KotlinHandshake.Fixture`; `SignedMessage`.
- Produces: `ComposeResponse.compose(store, fx, encPriv, encPub, target: String, rkind: String, body: String, createdAt: Double): Result(msgId, wireDict)`; and a per-instance monotonic-clock guard (a `@Volatile var lastTs` on the object OR passed in — mirror hearth's `_last_response_ts`; the ROUTE supplies `System.currentTimeMillis()/1000.0` and ComposeResponse bumps it if `<= lastTs`).

**Design (mirror hearth compose_response, Global Constraints has the exact shapes):**
1. Validate rkind ∈ (reaction,comment,retract); comment `0<len<=500`; reaction body ∈ REACTION_TOKENS+("clear",).
2. `msg = store.messageById(target)`; require `payload.kind=="post"` && `(payload.placement ?: "journal")=="journal"` else error. `author = msg.cert.identity_pub`. `authorDevs = store.enckeys(author).toMutableMap()`; if `author==own` add `[fx.device_pub]=encPub`; require non-empty.
3. Monotonic `createdAt` (bump past `lastTs`).
4. `sigPayload = canonical({"target":target,"rkind":rkind,"body":body,"created_at":PyFloat(createdAt),"responder":own})`; `responderSig = signRaw(fx.device_priv, sigPayload)`.
5. `friendPubs = knownIdentities().filter{it!=own}.flatMap{ enckeys(it).values }`; `box = sealSlots(canonical({"identity":own,"device_pub":fx.device_pub,"sig":responderSig}), friendPubs)`.
6. `key = random32`; `aad = responseAad(own, target, createdAt)`; `(nonce,ct) = encryptBody(key, {"rkind":rkind,"body":body,"alias_seed":deriveAliasSeed(fx.device_priv,target),"public":false,"responder":own,"responder_sig":responderSig,"mutual_box":box,"created_at":PyFloat(createdAt)}, aad)`.
7. `wraps = wrapKey(key, authorDevs, aad)` then merge `wrapKey(key, enckeys(own)+[fx.device_pub->encPub], aad)` (self-readable).
8. Build `make_response` envelope `{kind:"response", target, body_nonce:nonce, body_ct:ct, wraps, created_at:PyFloat(createdAt)}`; device-sign (`SignedMessage(fx.cert, nextSeq(), payload, "")` → signRaw → copy); `store.ingestMessage(signed)`; `store.addPendingOutbound(signed.msgId(), signed.toDict())`; return `Result(signed.msgId(), signed.toDict())`.

- [ ] **Step 1: Failing test** — compose a `reaction "heart"` on an own post (seed own enckey), then via the OWN-device wrap: `unwrapKey(wraps[fx.device_pub], encPriv, aad)` → `decryptBody` → assert body `rkind=="reaction"`, `body=="heart"`, `responder==own`, `responder_sig` present, `mutual_box` present (list); AND `tryOpenSlots(mutual_box, encPriv)` returns the sealed `{identity,device_pub,sig}` (own is its own friend? no — own isn't in friendPubs; instead assert the mutual_box is a well-formed list of the right bucket size). Also a `comment` case (body text). Assert `alias_seed` == `deriveAliasSeed(fx.device_priv, target)`.
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Implement `ComposeResponse.kt`.**
- [ ] **Step 4: Run → pass.** Full suite.
- [ ] **Step 5: Commit** — `feat(responses): ComposeResponse orchestrator (seal + encrypt + wrap + sign + enqueue)`.

---

## Task 5: `POST /api/react` + `/api/comment` + remove routes

**Files:**
- Modify: `LocalApi.kt`
- Test: (route is store+fixture-touching → exercised by the loopback gate T8 + on-device T9; no isolated JVM test, matching the `/api/post` pattern)

**Interfaces:** `handle()` gains, for POST: `path=="/api/react"` → parse JSON `{msg_id, token}` (token in REACTION_TOKENS or `"clear"`) → `ComposeResponse.compose(..., rkind = if(token=="clear") "reaction" else "reaction", body = token)`; `path=="/api/comment"` → JSON `{msg_id, text}` → `rkind="comment", body=text`; the remove route the composer uses (confirm exact path/JSON in Step 1 — likely `/api/response-remove` `{msg_id}` or `/api/retract`) → `rkind="retract"`. JSON body parse (the body reader exists from outbound-1; parse via `org.json.JSONObject(String(body, UTF_8))`). 400 on validation, 500 on compose error, 200 `{"ok":true}`.

- [ ] **Step 1: Confirm the exact route paths + JSON shapes** by reading `hearth/web/app.js` (`/api/react` `{msg_id, token}`; `/api/comment` `{msg_id, text}`; the removal route). Report any difference.
- [ ] **Step 2: Add the routes** to `LocalApi.handle` (before the GET guard, like `POST /api/post`), reusing `sharedStore`, `fixtureOrNull`, `EncKeys.getOrCreate(sharedStore)`, `System.currentTimeMillis()/1000.0`.
- [ ] **Step 3: Run the full module suite** (compiles; behavior verified in T8/T9).
- [ ] **Step 4: Commit** — `feat(responses): POST /api/react + /api/comment + remove routes`.

---

## Task 6: Read de-anon — thread `tryOpenSlots` into the attribution

**Files:**
- Modify: `KotlinResponses.kt`, `DecryptPass.kt`
- Test: `KotlinResponsesTest.kt`

**Interfaces:** In the responses attribution (where a non-public entry currently falls back to the client alias), if the entry has a `mutual_box`, call `KotlinSeal.tryOpenSlots(mutualBox, encPriv)`; on success parse the sealed `{identity, device_pub, sig}` (canonical JSON) and — mirroring hearth's read path — verify the responder's identity is the one it claims (the sealed `sig` re-verifies against `_response_sig_payload` for that responder+device, as hearth does), then attribute to that identity's real name (or "you" if `identity==own`); else keep the alias. `encPriv` is already available in `DecryptPass.responsesPass` — thread it into `KotlinResponses`' attribution function.

- [ ] **Step 1: Read the current `KotlinResponses` attribution** (the public-sig path + the alias fallback) + hearth `node.py`'s try-open block (`for priv in enc_privs(): try_open_slots(...)`; parse `{identity,device_pub}`; the sig re-verify). Report the exact seam.
- [ ] **Step 2: Failing test** — a KIND_RESPONSES entry (hearth-shaped) with a `mutual_box` sealed to the phone's own enc key → attribution returns the sealed identity's name (not alias); an entry whose box is sealed to a stranger → alias.
- [ ] **Step 3: Run → fail.**
- [ ] **Step 4: Implement** the de-anon branch in `KotlinResponses` + thread `encPriv` from `DecryptPass.responsesPass`. Keep the public path + alias fallback unchanged; the new branch runs only for non-public entries with a mutual_box.
- [ ] **Step 5: Run → pass.** Full suite.
- [ ] **Step 6: Commit** — `feat(responses): de-anon via tryOpenSlots (show real names to those you know)`.

---

## Task 7: Seam reveal (reaction picker + comment composer)

**Files:**
- Modify: `hearth/web/style.css`
- Test: `android_tor_spike/app/test/web-readonly-seam.test.ts`

- [ ] **Step 1: Update the vitest guard** — assert `.rx-open`, `.rx-picker`, `.comment-composer`, `.comment-x` are NO LONGER in the `body.readonly` block, while `#profile-wall-compose`, `#dm-compose`, `#profile-arrange` still are. (Adjust `HIDDEN_SELECTORS`/`LOAD_BEARING_SELECTORS` — remove the now-revealed ones — as Task 10 of outbound-1 did for `.composer`.)
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Remove `.rx-open`, `.rx-picker`, `.comment-composer`, `.comment-x` from the `body.readonly` selector list** in `style.css`; update the block comment (responses slice reveals them). Confirm no OTHER element shares those classes that should stay hidden (grep app.js — reactions/comments live only in the feed entry, not the profile/DM composers).
- [ ] **Step 4: Run → pass** + full vitest.
- [ ] **Step 5: Commit** — `feat(responses): reveal reaction picker + comment composer in the seam`.

---

## Task 8: Loopback fidelity gate — real node decrypts a phone-composed react + comment + opens the mutual_box

**Files:**
- Modify: `android_tor_spike/tools/sync_loopback_node.py` (add a `responses` scenario)
- Create: `.../test/.../SyncResponseLoopbackTest.kt`

- [ ] **Step 1: Extend `sync_loopback_node.py`** with a scenario that seeds the node's identity == the phone fixture's identity, an own journal POST (the response target), a FRIEND identity + enckey (so the mutual_box seals to a friend), and the phone's own enckey. After ingesting the phone's pushed KIND_RESPONSE, the node: decrypts it with its own device key (it's an author device — the response is wrapped to author), verifies `responder_sig` via hearth's `_sig_ok`/`_response_sig_payload`, and `try_open_slots(mutual_box, friend.enc_priv)` (simulating the FRIEND opening it) → confirms it recovers `{identity: phone_identity, ...}`. Emits `{"event":"responded","rkind","body","sig_ok","friend_opened"}`.
- [ ] **Step 2: Write the test** — mirror `SyncComposeLoopbackTest`: pull the seeded post+friend+enckeys (connection 1), `ComposeResponse.compose(...)` a `reaction "fire"` then a `comment`, push via `store.pendingOutbound()` (connection 2), `node.awaitEvent("responded")` for each; assert `rkind`/`body`, `sig_ok==true`, `friend_opened==true`. This proves byte-parity: a real node + a real friend-key process the phone's response identically.
- [ ] **Step 3: Run** (`--tests "expo.modules.tormanager.SyncResponseLoopbackTest"`); if the node CANNOT decrypt/verify/open, that's a REAL fidelity bug — report BLOCKED, do NOT weaken the assertions.
- [ ] **Step 4: Full suite.**
- [ ] **Step 5: Commit** — `test(responses): loopback gate -- real node decrypts + friend opens phone response`.

---

## Task 9: On-device DoD + report + PAUSE

**Files:**
- Create: `android_tor_spike/BRICK_RESPONSES_REPORT.md`

- [ ] **Step 1: Desk-gate sweep** — `:tor-manager:testDebugUnitTest` (full JVM), `npx tsc --noEmit` (0 new), `npx vitest run test/web-readonly-seam.test.ts`, `:app:assembleRelease` (confirm `copyHearthWeb` synced the seam edit). Record outputs.
- [ ] **Step 2: Build + install the RELEASE apk** on the G20 (`adb install -r`, force-stop first).
- [ ] **Step 3: On-device DoD (August drives)** — desktop `serve --tor`, unlocked, with a friend + a mutual friend synced. On the phone: react + comment on a friend's post AND your own; verify — (a) the reaction picker + comment box are visible; (b) your reaction/comment appears; (c) on the desktop feed it shows with correct attribution (your name to mutual friends); (d) a friend's response on your phone shows their REAL NAME (not an alias) after this build (the read de-anon); (e) a stranger's response shows an alias; (f) un-react/retract works; (g) regression: DM composer + profile Arrange stay hidden.
- [ ] **Step 4: Write `BRICK_RESPONSES_REPORT.md`** — desk-gates table; the loopback + vector parity proof; the on-device DoD; run gotchas; honest boundary (reactions+comments+retract, journal only, private-by-default = name-to-your-friends; public toggle + author moderation + profile-wall responses deferred; alias is per-device by hearth design); follow-up tickets (public_engagement toggle; author moderation; profile-wall responses; multi-enc-key trial-open if the phone ever rotates enc keys).
- [ ] **Step 5: Commit + PAUSE** — `docs(responses): on-device proof record + DoD`. PAUSE for human review; merge is August's call.

---

## Self-Review

**1. Spec coverage:** seal_slots/try_open_slots crypto → T1; responseAad/deriveAliasSeed → T2; messageById → T3; ComposeResponse (compose_response mirror) → T4; react/comment/remove routes → T5; read de-anon → T6; seam reveal → T7; loopback + parity → T8; on-device/report → T9. Reactions + comments + retract all via one `compose(rkind,...)`. Private-by-default (public toggle deferred) ✓. Read de-anon ✓. Parity invariant → T8 gate. All spec sections mapped.

**2. Placeholder scan:** No TBD/"similar to". T5 (routes) + the on-device steps lack isolated JVM tests by necessity (store/fixture/Android-runtime), explicitly gated by T8's real-node loopback + T9 on-device — the established pattern (matching `/api/post` in outbound-1), not a placeholder. The exact remove-route path is a read-and-confirm Step (T5 Step 1) with the fallback shape stated.

**3. Type consistency:** `sealSlots(payload,encPubs): List<Map<String,String>>` / `tryOpenSlots(slots,encPrivHex): ByteArray?` (T1) consumed in T4 (seal) + T6 (open). `responseAad(responder,target,createdAt)` / `deriveAliasSeed(devicePrivHex,target)` (T2) consumed in T4. `messageById(msgId): SignedMessage?` (T3) consumed in T4. `ComposeResponse.compose(store,fx,encPriv,encPub,target,rkind,body,createdAt): Result(msgId,wireDict)` (T4) consumed in T5 (routes) + T8 (gate). `wrapKey`/`encryptBody`/`signRaw`/`enckeys`/`addPendingOutbound`/`ingestMessage` reused from outbound-1 with their established signatures. The sealed-box payload `{identity,device_pub,sig}` written in T4 is the exact shape T6/T8 open+parse. `responder_sig` = `signRaw(device_priv, canonical({target,rkind,body,created_at,responder}))` consistent across T4 (write) + T6/T8 (verify).

**Implementer notes:** all crypto constants are in Global Constraints — use verbatim (a byte off = a friend on desktop can't open a phone-sealed box). `deriveAliasSeed` keys on `device_priv` (raw Ed25519), NOT enc_priv. The mutual-box audience is MY friends, never the author's. Reuse `KotlinDmcrypt`'s existing X25519/chacha helpers for `KotlinSeal` — only the HKDF info + AAD + padding differ.
