# Android Outbound — Compose Text+Photo Journal Post — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the phone compose and publish a text + photo journal post (`kreds` scope), signed by the enrolled device, so it appears in the phone's own feed immediately and — after a sync — on the desktop web feed and in friends' feeds.

**Architecture:** Port the outbound crypto (the byte-exact inverse of B.2's decrypt path) to Kotlin: a random content key encrypts the body + photo blobs (ChaCha20-Poly1305), the key is X25519-wrapped to every recipient device (own + all friends), and a `make_post`-shaped KIND_POST is device-signed and stored locally. A new `POST /api/post` route (the first non-GET route) parses the composer's multipart upload; the sync MESSAGES phase pushes the message and an extended BLOBS phase pushes the photo. Only the journal `.composer` is un-hidden from the read-only seam.

**Tech Stack:** Kotlin (BouncyCastle for X25519/ChaCha20/HKDF, already used by the decrypt side), the existing loopback `LocalWebServer`/`LocalApi`, `KotlinSync`, `SqliteSyncStore`; Android `BitmapFactory`/`Bitmap` for photo processing; the shared `hearth/web` app.js/style.css; the `sync_loopback_node.py` real-node test harness.

## Global Constraints

- **Branch:** `brick-outbound-post`. Commit prefix `feat(outbound)` / `fix(outbound)` / `docs(outbound)`, lowercase. **NO AI / Co-Authored-By / "Generated with" trailers on any commit.**
- **Scope this slice:** journal placement, `kreds` scope ONLY. `inner` scope, reactions/comments/DM-send/story/profile-edit/deletes/albums are OUT (each a later slice).
- **Byte-exact crypto** — mirror hearth verbatim: post AAD = `canonical({"type":"post-aad","protocol":"hearth/v0.2","from":<identity_pub>,"scope":<scope>,"created_at":<PyFloat>})`; HKDF wrap info = `"hearth/dm-wrap/v1"` (salt null, len 32); blob AAD = `"hearth/dm-blob/v1"`; ChaCha20-Poly1305 tag = 128 bits; the encrypted body plaintext is `KotlinWire.canonical(body)` (sorted-key canonical JSON), NOT `org.json` toString. The **wrap AAD is the SAME post AAD as the body** (not a separate wrap AAD).
- **`make_post` payload shape (verbatim, `hearth/messages.py:78-93`):** keys `kind`("post"), `scope`, `body_nonce`, `body_ct`, `wraps`, `blobs`, `created_at`, `expires_at`, `placement`, `media`, `poster`, `codec`, `thumbs`. `text` is NOT an envelope field — it lives inside the encrypted body `{"text":..., "blobs":[...]}`. The envelope `blobs` is the plaintext list of the same blob-hash refs.
- **`validate_payload` KIND_POST gate the output MUST pass (`hearth/messages.py:286-321`):** `scope`∈("inner","kreds"); `placement`∈("journal","profile"); `body_nonce`=24 lowercase-hex; `body_ct`=non-empty lowercase-hex; `wraps` valid (each key hex64 → `{eph_pub:hex64, nonce:24-hex, wrapped_key:non-empty-hex}`); `blobs`=list of hex64; `thumbs` absent/null OR list same length as `blobs` (hex64-or-null each); `media`∈("photo","video"); `poster` required+hex64 iff `media=="video"` else must be null.
- **Blob size:** `MAX_BLOB_BYTES = 10*1024*1024`; photo long-edge downscale cap `PHOTO_MAX = 2560`; ciphertext must stay ≤ `MAX_BLOB_BYTES` (target the plaintext to `PHOTO_CAP = MAX_BLOB_BYTES - 64`).
- **Decrypt-on-read preserved:** the content key + plaintext exist in memory only during compose; only the ciphertext blob + wrapped keys + signed message are persisted.
- **Reuse `sharedStore`** (the lazy singleton in `LocalApi`) — never construct a second `SqliteSyncStore` per request (connection-leak fix).
- **Known limitation to document (not fix here):** the Kotlin store has NO revocation modeling, so `enckeys()` cannot exclude a friend's revoked device the way hearth's `enckey_records` does; a revoked friend device would still be wrapped-to. Pre-existing gap (the phone processes no revocations); flag in the report, do not scope revocation tracking into this slice.

**Test commands** (Windows git-bash; set `JAVA_HOME=/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot`, `ANDROID_HOME=~/AppData/Local/Android/Sdk`):
- Kotlin JVM: from `android_tor_spike/app/android` → `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.<Class>"`
- vitest: from `android_tor_spike/app` → `npx vitest run test/web-readonly-seam.test.ts`
- Release APK: from `android_tor_spike/app/android` → `./gradlew :app:assembleRelease` (NDK r27.1; apk at `app/build/outputs/apk/release/app-release.apk`)

---

## File Structure

- `KotlinDmcrypt.kt` (modify) — add `wrapKey` + `encryptBody` (inverses of `unwrapKey`/`decryptBody`), and a shared `chachaSeal` next to `chachaOpen`.
- `KotlinBlobCrypt.kt` (modify) — add `encryptBlob` (inverse of `decryptBlob`).
- `SyncStore.kt` (modify) — add `enckeys(identityPub): Map<String,String>` to the interface + `InMemorySyncStore`.
- `SqliteSyncStore.kt` (modify) — implement `enckeys` (SQL, latest-wins).
- `Compose.kt` (create) — the outbound orchestrator: recipient resolution → content key → encrypt body/blobs → wrap → `make_post` payload → device-sign → local `ingestMessage`. Returns the composed msg_id + blob (hash,bytes) list.
- `SignedMessageKt.kt` (modify) — add a shared `toDict()` extracted from `composeEncKey`'s inline serialization (DRY; used by both enckey + post compose).
- `KotlinSync.kt` (modify) — factor the enckey wire-dict via the new `toDict`; extend the BLOBS phase to serve `store.getBlob(hash)` for hashes the node's `blob_want` asks for.
- `LocalWebServer.kt` (modify) — read the POST request body (Content-Length); add a `body: ByteArray?` param to the `api` provider type.
- `LocalApi.kt` (modify) — `handle(method, path, body)` gains `POST /api/post`; parse multipart; call `Compose`.
- `Multipart.kt` (create) — a minimal `multipart/form-data` parser (fields + file parts).
- `PhotoPrep.kt` (create) — decode → EXIF-strip → downscale → JPEG encode.
- `TorManagerModule.kt` (modify) — thread the new `body` param through the `LocalWebServer` construction to `LocalApi.handle`.
- `hearth/web/style.css` (modify) — remove `.composer` from the `body.readonly` block.
- Tests: `KotlinDmcryptTest.kt`, `KotlinBlobCryptTest.kt`, `SyncStoreTest.kt`, `ComposeTest.kt` (new), `MultipartTest.kt` (new), `SyncComposeLoopbackTest.kt` (new), `web-readonly-seam.test.ts`.

---

## Task 1: `enckeys(identity)` store accessor

**Files:**
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/SyncStore.kt`
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/SqliteSyncStore.kt`
- Test: `android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/SyncStoreTest.kt`

**Interfaces:**
- Produces: `SyncStore.enckeys(identityPub: String): Map<String, String>` (device_pub → enc_pub), latest-wins per device by `(created_at, seq)` over KIND_ENCKEY messages of that identity. NO revocation exclusion (documented limitation). Both `InMemorySyncStore` and `SqliteSyncStore` implement it.

- [ ] **Step 1: Write the failing test** — append inside `class SyncStoreTest`:

```kotlin
    @Test fun enckeysLatestWinsPerDeviceOverEnckeyMessages() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        // msg() signs with device priv 0x22.. -> a single device_pub for idPub.
        // Two enckey messages from that device: newer created_at wins.
        assertTrue(s.ingestMessage(msg(1, mapOf(
            "kind" to "enckey", "enc_pub" to "aa".repeat(32), "created_at" to 100.0))))
        assertTrue(s.ingestMessage(msg(2, mapOf(
            "kind" to "enckey", "enc_pub" to "bb".repeat(32), "created_at" to 200.0))))
        val dvPub = KotlinWire.toHex(
            org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(
                KotlinWire.fromHex("22".repeat(32)), 0).generatePublicKey().encoded)
        val ks = s.enckeys(idPub)
        assertEquals(mapOf(dvPub to "bb".repeat(32)), ks)                 // latest enc_pub
        assertTrue("unknown identity -> empty", s.enckeys("ff".repeat(32)).isEmpty())
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.SyncStoreTest"`
Expected: FAIL / compile error — `enckeys` unresolved.

- [ ] **Step 3: Add to the `SyncStore` interface** (in `SyncStore.kt`, near `profileNames()`):

```kotlin
    /** device_pub -> enc_pub over this identity's KIND_ENCKEY messages,
     *  latest-wins per device by (created_at, seq). Mirrors hearth
     *  store.enckeys, EXCEPT it cannot exclude revoked devices (the Kotlin
     *  store models no revocations) -- a documented outbound limitation. */
    fun enckeys(identityPub: String): Map<String, String>
```

- [ ] **Step 4: Implement in `InMemorySyncStore`** (in `SyncStore.kt`, mirror the `profileNames`/`Cand` latest-wins idiom):

```kotlin
    override fun enckeys(identityPub: String): Map<String, String> {
        data class Cand(val createdAt: Double, val seq: Int, val encPub: String)
        val best = linkedMapOf<String, Cand>()
        for (m in messages.values) {
            if (m.kind != "enckey" || m.identityPub != identityPub) continue
            val enc = m.payload["enc_pub"] as? String ?: continue
            val ca = (m.payload["created_at"] as? Number)?.toDouble() ?: continue
            val dev = m.devicePub
            val cur = best[dev]
            if (cur == null || ca > cur.createdAt || (ca == cur.createdAt && m.seq > cur.seq))
                best[dev] = Cand(ca, m.seq, enc)
        }
        return best.mapValues { it.value.encPub }
    }
```

(Note: `StoredMsg`/the in-memory message exposes `identityPub`, `devicePub`, `seq`, `kind`, `payload` — mirror whatever `profileNames()` reads; if the in-memory message type lacks `devicePub`, read it the same way `deviceViews` does.)

- [ ] **Step 5: Implement in `SqliteSyncStore`** (mirror `profileRecord`'s seq-column SQL + fold, `SqliteSyncStore.kt:522-539`):

```kotlin
    override fun enckeys(identityPub: String): Map<String, String> {
        data class Cand(val createdAt: Double, val seq: Int, val encPub: String)
        val best = linkedMapOf<String, Cand>()
        readableDatabase.rawQuery(
            "SELECT device_pub, seq, msg_json FROM messages WHERE kind = 'enckey' AND identity_pub = ?",
            arrayOf(identityPub)
        ).use { c ->
            while (c.moveToNext()) {
                val dev = c.getString(0); val seq = c.getInt(1)
                val payload = JSONObject(c.getString(2)).optJSONObject("payload") ?: continue
                val enc = payload.opt("enc_pub") as? String ?: continue
                val ca = (payload.opt("created_at") as? Number)?.toDouble() ?: continue
                val cur = best[dev]
                if (cur == null || ca > cur.createdAt || (ca == cur.createdAt && seq > cur.seq))
                    best[dev] = Cand(ca, seq, enc)
            }
        }
        return best.mapValues { it.value.encPub }
    }
```

- [ ] **Step 6: Run tests + full module suite**

Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.SyncStoreTest"` then `./gradlew :tor-manager:testDebugUnitTest`
Expected: PASS (new test) + full suite green.

- [ ] **Step 7: Commit**

```bash
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/SyncStore.kt android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/SqliteSyncStore.kt android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/SyncStoreTest.kt
git commit -m "feat(outbound): enckeys(identity) accessor for recipient resolution"
```

---

## Task 2: `KotlinDmcrypt.wrapKey` + `encryptBody` (encrypt inverses)

**Files:**
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/KotlinDmcrypt.kt`
- Test: `android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/KotlinDmcryptTest.kt`

**Interfaces:**
- Consumes: `KotlinDmcrypt.postAad`, `deriveKek`, existing `unwrapKey`/`decryptBody` (for round-trip tests); `KotlinWire.canonical`/`toHex`/`fromHex`.
- Produces:
  - `KotlinDmcrypt.encryptBody(contentKey: ByteArray, body: Map<String, Any?>, aad: ByteArray): Pair<String, String>` — returns `(bodyNonceHex, bodyCtHex)`; plaintext = `KotlinWire.canonical(body)`.
  - `KotlinDmcrypt.wrapKey(contentKey: ByteArray, deviceEncPubs: Map<String, String>, aad: ByteArray): Map<String, Map<String, String>>` — `{device_pub: {eph_pub, nonce, wrapped_key}}`, one fresh X25519 ephemeral per device; devices with a malformed enc_pub are skipped.

- [ ] **Step 1: Write the failing tests** — append inside `class KotlinDmcryptTest`:

```kotlin
    @Test fun encryptBodyRoundTripsThroughDecryptBody() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val aad = KotlinDmcrypt.postAad("id".repeat(32), "kreds", 1752900000.5)
        val body = mapOf("text" to "hello world", "blobs" to listOf("ab".repeat(32)))
        val (nonceHex, ctHex) = KotlinDmcrypt.encryptBody(key, body, aad)
        assertEquals("24-hex nonce", 24, nonceHex.length)
        assertTrue("hex ct", ctHex.isNotEmpty() && ctHex.all { it in "0123456789abcdef" })
        val back = KotlinDmcrypt.decryptBody(key, nonceHex, ctHex, aad)!!
        assertEquals("hello world", back["text"])
        assertEquals(listOf("ab".repeat(32)), back["blobs"])
        // wrong AAD -> null (binding holds)
        val badAad = KotlinDmcrypt.postAad("id".repeat(32), "inner", 1752900000.5)
        assertNull(KotlinDmcrypt.decryptBody(key, nonceHex, ctHex, badAad))
    }

    @Test fun wrapKeyRoundTripsThroughUnwrapKey() {
        // recipient X25519 keypair via EncKeys' generator shape
        val recipPriv = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(java.security.SecureRandom())
        val recipPrivHex = KotlinWire.toHex(recipPriv.encoded)
        val recipPubHex = KotlinWire.toHex(recipPriv.generatePublicKey().encoded)
        val key = ByteArray(32) { (it + 7).toByte() }
        val aad = KotlinDmcrypt.postAad("id".repeat(32), "kreds", 1752900000.5)
        val wraps = KotlinDmcrypt.wrapKey(key, mapOf("dev01234" to recipPubHex), aad)
        val w = wraps["dev01234"]!!
        assertEquals(64, w["eph_pub"]!!.length); assertEquals(24, w["nonce"]!!.length)
        assertTrue(w["wrapped_key"]!!.isNotEmpty())
        // unwrapKey (already-proven inverse) recovers the key
        val recovered = KotlinDmcrypt.unwrapKey(w, recipPrivHex, aad)!!
        assertArrayEquals(key, recovered)
        // malformed enc_pub is skipped, not thrown
        assertTrue(KotlinDmcrypt.wrapKey(key, mapOf("bad" to "zz"), aad).isEmpty())
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.KotlinDmcryptTest"`
Expected: FAIL — `encryptBody`/`wrapKey` unresolved.

- [ ] **Step 3: Add a seal (encrypt) primitive + the two functions** to `KotlinDmcrypt.kt`. Next to the existing `chachaOpen`, add `chachaSeal` (same BouncyCastle `ChaCha20Poly1305`, `init(true, ...)`, 128-bit tag), then the inverses:

```kotlin
    // encrypt counterpart of chachaOpen (init(true,...)); returns nonce-less ct+tag.
    private fun chachaSeal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val c = org.bouncycastle.crypto.modes.ChaCha20Poly1305()
        c.init(true, org.bouncycastle.crypto.params.AEADParameters(
            org.bouncycastle.crypto.params.KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(plain.size))
        val n = c.processBytes(plain, 0, plain.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    private fun randomBytes(n: Int): ByteArray =
        ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }

    /** Inverse of decryptBody: plaintext = canonical(body). */
    fun encryptBody(contentKey: ByteArray, body: Map<String, Any?>, aad: ByteArray): Pair<String, String> {
        val nonce = randomBytes(12)
        val ct = chachaSeal(contentKey, nonce, KotlinWire.canonical(body), aad)
        return KotlinWire.toHex(nonce) to KotlinWire.toHex(ct)
    }

    /** Inverse of unwrapKey: {device_pub -> {eph_pub, nonce, wrapped_key}}, one
     *  fresh ephemeral X25519 per device; wrap AAD == the body AAD (post_aad). */
    fun wrapKey(contentKey: ByteArray, deviceEncPubs: Map<String, String>, aad: ByteArray): Map<String, Map<String, String>> {
        val out = linkedMapOf<String, Map<String, String>>()
        for ((devicePub, encPubHex) in deviceEncPubs) {
            val peer = try {
                org.bouncycastle.crypto.params.X25519PublicKeyParameters(KotlinWire.fromHex(encPubHex), 0)
            } catch (e: Exception) { continue }        // skip malformed enc keys
            val eph = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(java.security.SecureRandom())
            val shared = ByteArray(32); eph.generateSecret(peer, shared, 0)
            val kek = deriveKek(shared)
            val nonce = randomBytes(12)
            out[devicePub] = mapOf(
                "eph_pub" to KotlinWire.toHex(eph.generatePublicKey().encoded),
                "nonce" to KotlinWire.toHex(nonce),
                "wrapped_key" to KotlinWire.toHex(chachaSeal(kek, nonce, contentKey, aad)))
        }
        return out
    }
```

(If `deriveKek` is `private`, keep `wrapKey` in the same object so it can call it. Confirm the X25519 shared-secret API used by `unwrapKey` and mirror it exactly — `X25519PrivateKeyParameters.generateSecret(peerPub, out, off)`.)

- [ ] **Step 4: Run tests**

Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.KotlinDmcryptTest"`
Expected: PASS (both new tests + existing).

- [ ] **Step 5: Commit**

```bash
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/KotlinDmcrypt.kt android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/KotlinDmcryptTest.kt
git commit -m "feat(outbound): wrapKey + encryptBody (encrypt inverses of B.2 decrypt)"
```

---

## Task 3: `KotlinBlobCrypt.encryptBlob`

**Files:**
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/KotlinBlobCrypt.kt`
- Test: `android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/KotlinBlobCryptTest.kt`

**Interfaces:**
- Produces: `KotlinBlobCrypt.encryptBlob(contentKey: ByteArray, data: ByteArray): ByteArray` — `nonce(12) || ct+tag`, AAD = `BLOB_AAD`. Inverse of `decryptBlob`.

- [ ] **Step 1: Write the failing test:**

```kotlin
    @Test fun encryptBlobRoundTripsThroughDecryptBlob() {
        val key = ByteArray(32) { (it + 3).toByte() }
        val data = ByteArray(5000) { (it % 256).toByte() }
        val cipher = KotlinBlobCrypt.encryptBlob(key, data)
        assertTrue("nonce+ct+tag longer than plain", cipher.size == data.size + 12 + 16)
        assertArrayEquals(data, KotlinBlobCrypt.decryptBlob(key, cipher))
        // wrong key -> null
        assertNull(KotlinBlobCrypt.decryptBlob(ByteArray(32), cipher))
    }
```

- [ ] **Step 2: Run to verify it fails** — Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.KotlinBlobCryptTest"` → FAIL (`encryptBlob` unresolved).

- [ ] **Step 3: Implement** in `KotlinBlobCrypt.kt` (mirror `decryptBlob`'s cipher with `init(true,...)`):

```kotlin
    fun encryptBlob(contentKey: ByteArray, data: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(contentKey), 128, nonce, BLOB_AAD))
        val out = ByteArray(c.getOutputSize(data.size))
        val n = c.processBytes(data, 0, data.size, out, 0)
        c.doFinal(out, n)
        return nonce + out
    }
```

- [ ] **Step 4: Run tests** — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/KotlinBlobCrypt.kt android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/KotlinBlobCryptTest.kt
git commit -m "feat(outbound): encryptBlob (inverse of blob decrypt)"
```

---

## Task 4: `SignedMessage.toDict()` (DRY the wire serialization)

**Files:**
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/SignedMessageKt.kt`
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/KotlinSync.kt` (use it in `composeEncKey`)
- Test: `android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/KotlinWireVectorTest.kt` (or the file holding `SignedMessage` tests)

**Interfaces:**
- Produces: `SignedMessage.toDict(): Map<String, Any?>` = `{cert: <certMap>, seq, payload, signature}`, matching `composeEncKey`'s inline `mapOf(...)`. `certMap` = `{identity_pub, device_pub, device_name, enrolled_at, signature}`.

- [ ] **Step 1: Write the failing test** — a round-trip: `SignedMessageKt.fromDict(m.toDict())` equals `m`:

```kotlin
    @Test fun signedMessageToDictRoundTripsThroughFromDict() {
        val cert = KotlinWire.CertDict("aa".repeat(32), "bb".repeat(32), "phone", 1752900000.0, "cc".repeat(64))
        val m = SignedMessage(cert, 5, mapOf("kind" to "enckey", "enc_pub" to "dd".repeat(32), "created_at" to 1752900001.0), "ee".repeat(64))
        val back = SignedMessageKt.fromDict(m.toDict())
        assertEquals(m.cert.identity_pub, back.cert.identity_pub)
        assertEquals(m.cert.device_pub, back.cert.device_pub)
        assertEquals(m.seq, back.seq); assertEquals(m.signature, back.signature)
        assertEquals(m.payload["enc_pub"], back.payload["enc_pub"])
    }
```

- [ ] **Step 2: Run to verify it fails** — FAIL (`toDict` unresolved).

- [ ] **Step 3: Add `toDict()` to `SignedMessage`** (in `SignedMessageKt.kt`):

```kotlin
    fun toDict(): Map<String, Any?> = mapOf(
        "cert" to mapOf(
            "identity_pub" to cert.identity_pub, "device_pub" to cert.device_pub,
            "device_name" to cert.device_name, "enrolled_at" to cert.enrolled_at,
            "signature" to cert.signature),
        "seq" to seq, "payload" to payload, "signature" to signature)
```

- [ ] **Step 4: Refactor `composeEncKey`** (in `KotlinSync.kt`) to return `signed.toDict()` instead of the inline `mapOf("cert" to certToMap(...), ...)`; delete the now-unused `certToMap` if nothing else uses it. Keep behavior byte-identical.

- [ ] **Step 5: Run the module suite** — Run: `./gradlew :tor-manager:testDebugUnitTest` → all green (the existing enckey-compose loopback still passes, proving byte-identity).

- [ ] **Step 6: Commit**

```bash
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/SignedMessageKt.kt android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/KotlinSync.kt android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/KotlinWireVectorTest.kt
git commit -m "feat(outbound): SignedMessage.toDict() shared wire serializer"
```

---

## Task 5: `Compose` — the outbound orchestrator (text + optional blobs)

**Files:**
- Create: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/Compose.kt`
- Test: `android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/ComposeTest.kt`

**Interfaces:**
- Consumes: `enckeys` (Task 1), `wrapKey`/`encryptBody` (Task 2), `encryptBlob` (Task 3), `toDict` (Task 4), `KotlinDmcrypt.postAad`, `KotlinWire.signRaw`, `SqliteSyncStore` (`knownIdentities`, `nextSeq`, `putBlob`, `ingestMessage`, `enckeys`), `EncKeys.getOrCreate`, `KotlinHandshake.Fixture`, `SignedMessage`, and `sha256hex(bytes)`.
- Produces: `Compose.post(store, fx, encPriv, encPub, text, photos: List<ByteArray>, scope: String, createdAt: Double): Result` where photos are ALREADY encrypted-ready plaintext JPEG bytes; `Result(msgId: String, blobs: List<Pair<String, ByteArray>>)` (hash→ciphertext, already `putBlob`'d). The function performs the full compose + local ingest.

**Design notes (from the reference):** content key = 32 random bytes; each photo → `encryptBlob(key, jpeg)` → `hash = sha256(cipher).hex` → `store.putBlob(hash, cipher)`; envelope `blobs` = the hash list; body = `{"text": text, "blobs": <hashes>}`; `body_nonce, body_ct = encryptBody(key, body, aad)`; recipients = union of `enckeys(friend)` for all friends + `enckeys(own)` + THIS device `(fx.device_pub, encPub)`; `wraps = wrapKey(key, recipients, aad)`; `aad = postAad(fx.cert.identity_pub, scope, createdAt)`; payload built in `make_post` shape; sign via `SignedMessage(fx.cert, store.nextSeq(), payload, "")` → `signRaw(fx.device_priv, unsigned.body())` → `.copy(signature=...)`; `store.ingestMessage(signed)`.

- [ ] **Step 1: Write the failing test** — a compose that round-trips through the existing `DecryptPass`/`decryptBody`, proving AAD + wrap fidelity WITHOUT a node. Use `InMemorySyncStore`, seed own identity + own enckey, compose, then decrypt the body with the OWN enc key:

```kotlin
    @Test fun composePostBuildsDecryptableKredsPost() {
        val s = InMemorySyncStore()
        val fx = testFixture()                      // helper: Fixture w/ known device_priv/pub + cert.identity_pub
        s.addIdentity(fx.cert.identity_pub)
        val (encPriv, encPub) = EncKeys.getOrCreate(s)          // own X25519
        // publish own enckey so enckeys(own) resolves this device
        s.ingestMessage(SignedMessageSigned(fx, s.nextSeq(), mapOf(
            "kind" to "enckey", "enc_pub" to encPub, "created_at" to 100.0)))
        val res = Compose.post(s, fx, encPriv, encPub, "hello kreds", emptyList(), "kreds", 1752900000.5)
        // the composed message is now in the store; find it + decrypt its body
        val stored = s.allMessages().first { it.msgId == res.msgId }
        val payload = stored.payload
        assertEquals("post", payload["kind"]); assertEquals("kreds", payload["scope"])
        assertEquals("journal", payload["placement"]); assertEquals("photo", payload["media"])
        assertNull(payload["poster"])
        val aad = KotlinDmcrypt.postAad(fx.cert.identity_pub, "kreds", 1752900000.5)
        @Suppress("UNCHECKED_CAST") val wraps = payload["wraps"] as Map<String, Any?>
        val myWrap = wraps[fx.device_pub] as Map<String, Any?>       // wrapped to own device
        val key = KotlinDmcrypt.unwrapKey(myWrap, encPriv, aad)!!
        val body = KotlinDmcrypt.decryptBody(key, payload["body_nonce"] as String, payload["body_ct"] as String, aad)!!
        assertEquals("hello kreds", body["text"])
        assertEquals(emptyList<String>(), body["blobs"])
    }
```

(Provide the two helpers in the test file: `testFixture()` returns a `KotlinHandshake.Fixture` with a locally-minted device key + cert whose `identity_pub` you control; `SignedMessageSigned(fx, seq, payload)` signs a payload with `fx.device_priv` — copy the sign idiom from `composeEncKey`.)

- [ ] **Step 2: Run to verify it fails** — FAIL (`Compose` unresolved).

- [ ] **Step 3: Implement `Compose.kt`:**

```kotlin
package expo.modules.tormanager

import java.security.MessageDigest
import java.security.SecureRandom

object Compose {
    data class Result(val msgId: String, val blobs: List<Pair<String, ByteArray>>)

    private fun sha256hex(b: ByteArray): String =
        KotlinWire.toHex(MessageDigest.getInstance("SHA-256").digest(b))

    /** Compose + LOCALLY ingest a kreds-scope journal post. `photos` are
     *  ready-to-encrypt plaintext (JPEG) bytes. Wraps to own + ALL friends'
     *  enc-keyed devices (kreds). */
    fun post(
        store: SyncStore, fx: KotlinHandshake.Fixture, encPriv: String, encPub: String,
        text: String, photos: List<ByteArray>, scope: String, createdAt: Double,
    ): Result {
        require(scope == "kreds") { "only kreds scope this slice" }
        val own = fx.cert.identity_pub
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val aad = KotlinDmcrypt.postAad(own, scope, createdAt)

        val blobPairs = photos.map { jpeg ->
            val cipher = KotlinBlobCrypt.encryptBlob(key, jpeg)
            val hash = sha256hex(cipher)
            store.putBlob(hash, cipher)
            hash to cipher
        }
        val hashes = blobPairs.map { it.first }

        val (nonceHex, ctHex) = KotlinDmcrypt.encryptBody(
            key, mapOf("text" to text, "blobs" to hashes), aad)

        // recipients: own devices + all friends' devices + THIS device explicit.
        val recipients = linkedMapOf<String, String>()
        for (f in store.knownIdentities()) if (f != own) recipients.putAll(store.enckeys(f))
        recipients.putAll(store.enckeys(own))
        recipients[fx.device_pub] = encPub
        val wraps = KotlinDmcrypt.wrapKey(key, recipients, aad)

        val payload: Map<String, Any?> = mapOf(
            "kind" to "post", "scope" to scope, "body_nonce" to nonceHex,
            "body_ct" to ctHex, "wraps" to wraps, "blobs" to hashes,
            "created_at" to KotlinWire.PyFloat(createdAt), "expires_at" to null,
            "placement" to "journal", "media" to "photo", "poster" to null,
            "codec" to null, "thumbs" to null)

        val unsigned = SignedMessage(fx.cert, store.nextSeq(), payload, "")
        val signed = unsigned.copy(signature = KotlinWire.signRaw(fx.device_priv, unsigned.body()))
        store.ingestMessage(signed)
        return Result(signed.msgId(), blobPairs)
    }
}
```

**IMPORTANT — `created_at` PyFloat/double consistency:** the payload's `created_at` is a `KotlinWire.PyFloat(createdAt)` so canonical signing/serialization byte-matches Python; the AAD's `created_at` is ALSO built from the same `createdAt` via `postAad` (which wraps it in `PyFloat` internally). They MUST be the identical double. Do not round or re-read it.

- [ ] **Step 4: Run the test** — Expected: PASS (body decrypts with the own-device wrap, proving the whole encrypt+wrap+sign chain).

- [ ] **Step 5: Run the full module suite** — green.

- [ ] **Step 6: Commit**

```bash
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/Compose.kt android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/ComposeTest.kt
git commit -m "feat(outbound): Compose orchestrator (content key, encrypt, wrap, sign, ingest)"
```

---

## Task 6: `Multipart` parser + request-body reading in `LocalWebServer` + `api` signature

**Files:**
- Create: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/Multipart.kt`
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalWebServer.kt`
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/TorManagerModule.kt` (thread the body param)
- Test: `android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/MultipartTest.kt`

**Interfaces:**
- Produces: `Multipart.parse(contentType: String, body: ByteArray): Multipart.Form` where `Form(fields: Map<String,String>, files: List<Part>)` and `Part(name: String, filename: String?, bytes: ByteArray)`. Boundary is read from the `contentType` (`multipart/form-data; boundary=...`).
- Changes the `api` provider type to include `body: ByteArray?`: `(method, path, query, cookieToken, queryToken, body) -> HttpResponse?`.

- [ ] **Step 1: Write the failing test** for `Multipart.parse` with a hand-built multipart body (one text field `text`, one file part `photo`):

```kotlin
    @Test fun parsesFieldsAndFileParts() {
        val b = "----b"
        val body = ("--$b\r\nContent-Disposition: form-data; name=\"text\"\r\n\r\nhello\r\n" +
            "--$b\r\nContent-Disposition: form-data; name=\"scope\"\r\n\r\nkreds\r\n" +
            "--$b\r\nContent-Disposition: form-data; name=\"photos\"; filename=\"a.jpg\"\r\n" +
            "Content-Type: image/jpeg\r\n\r\nJPEGBYTES\r\n--$b--\r\n").toByteArray(Charsets.ISO_8859_1)
        val form = Multipart.parse("multipart/form-data; boundary=$b", body)
        assertEquals("hello", form.fields["text"]); assertEquals("kreds", form.fields["scope"])
        assertEquals(1, form.files.size)
        assertEquals("photos", form.files[0].name); assertEquals("a.jpg", form.files[0].filename)
        assertEquals("JPEGBYTES", String(form.files[0].bytes, Charsets.ISO_8859_1))
    }
```

- [ ] **Step 2: Run to verify it fails** — FAIL (`Multipart` unresolved).

- [ ] **Step 3: Implement `Multipart.kt`** — a minimal, byte-safe splitter on `--boundary`, parsing each part's headers (Content-Disposition name/filename) then the raw body up to the trailing `\r\n` before the next boundary. Operate on `ByteArray` (parts may be binary). (Full implementation: locate `--boundary` delimiters by byte search; for each part, split headers block at the first `\r\n\r\n`; parse `name=` / `filename=` from Content-Disposition; the content is the bytes between the header terminator and the `\r\n` preceding the next `--boundary`.)

- [ ] **Step 4: Run the parser test** — PASS.

- [ ] **Step 5: Add request-body reading to `LocalWebServer.handle`** — after the header loop, capture `Content-Length` and `Content-Type`; if a body is present, read exactly `Content-Length` chars from the SAME `BufferedReader` (it wraps the stream as ISO-8859-1, a 1:1 byte↔char map, so `reader.read(charBuf)` then `String(charBuf).toByteArray(ISO_8859_1)` recovers the raw bytes) — do NOT read from the raw socket stream (the BufferedReader may hold buffered bytes). Pass the resulting `ByteArray?` + the content-type through to `api(...)`. Update the `api` lambda type (add `body: ByteArray?`) and `writeResponse` usage unchanged.

```kotlin
    // inside handle(), after the header loop parses cookie/range, also capture:
    var contentLength = 0; var contentType: String? = null
    // (in the header loop) if (line.startsWith("Content-Length:", true)) contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
    // (in the header loop) if (line.startsWith("Content-Type:", true)) contentType = line.substringAfter(":").trim()
    val body: ByteArray? = if (contentLength > 0) {
        val cbuf = CharArray(contentLength); var got = 0
        while (got < contentLength) { val r = reader.read(cbuf, got, contentLength - got); if (r < 0) break; got += r }
        String(cbuf, 0, got).toByteArray(Charsets.ISO_8859_1)
    } else null
    // dispatch: api(method, path, query, cookieToken, queryToken, contentType, body)
```

(Adjust the `api` type to also carry `contentType` OR fold it into the body handling — simplest: pass both `contentType` and `body`. Update the provider signature accordingly.)

- [ ] **Step 6: Thread the new params in `TorManagerModule`** where `LocalWebServer(assets, api)` is constructed — the `api` lambda now forwards `(method, path, query, cookie, qtok, contentType, body)` into `LocalApi.handle(method, path, contentType, body)` (Task 7 adds that overload; for THIS task, extend the lambda + `LocalApi.handle` signature to accept and ignore `contentType`/`body`, keeping GET behavior identical).

- [ ] **Step 7: Run the module suite** — green (GET paths unaffected; body is null for GETs).

- [ ] **Step 8: Commit**

```bash
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/Multipart.kt android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalWebServer.kt android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/TorManagerModule.kt android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/MultipartTest.kt
git commit -m "feat(outbound): multipart parser + POST request-body reading in loopback server"
```

---

## Task 7: `PhotoPrep` + `POST /api/post` route wiring

**Files:**
- Create: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/PhotoPrep.kt`
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalApi.kt`

**Interfaces:**
- Produces: `PhotoPrep.toUploadJpeg(raw: ByteArray): ByteArray?` — decode → EXIF-strip (re-encode via `Bitmap` drops EXIF) → downscale long edge to ≤ `PHOTO_MAX (2560)` → JPEG compress (quality loop) to ≤ `PHOTO_CAP (MAX_BLOB_BYTES-64)`; null on undecodable input.
- `LocalApi.handle(method, path, contentType, body)` handles `POST /api/post`.

- [ ] **Step 1: Implement `PhotoPrep.kt`** (main process — own bytes, no sandbox). Decode with `BitmapFactory.decodeByteArray`; if a dimension > `PHOTO_MAX`, create a scaled `Bitmap` (`Bitmap.createScaledBitmap`, preserve aspect); `bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)` starting quality 90, stepping down (e.g. 90→80→70→60) until `out.size() <= PHOTO_CAP`; return bytes. Re-encoding through `Bitmap` **drops all EXIF** (the privacy requirement) since `Bitmap` holds only pixels. Return null if `decodeByteArray` returns null.

```kotlin
object PhotoPrep {
    private const val PHOTO_MAX = 2560
    private const val PHOTO_CAP = 10 * 1024 * 1024 - 64
    fun toUploadJpeg(raw: ByteArray): ByteArray? {
        var bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val w = bmp.width; val h = bmp.height; val long = maxOf(w, h)
        if (long > PHOTO_MAX) {
            val s = PHOTO_MAX.toFloat() / long
            bmp = android.graphics.Bitmap.createScaledBitmap(bmp, (w * s).toInt(), (h * s).toInt(), true)
        }
        for (q in intArrayOf(90, 80, 70, 60, 50)) {
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, out)
            if (out.size() <= PHOTO_CAP) return out.toByteArray()
        }
        return null   // still too big at q50 -> reject (caller 4xx)
    }
}
```

(No unit test — `Bitmap`/`BitmapFactory` need Android runtime; exercised on-device in Task 10. This mirrors how the SQLite-only accessors are on-device-verified.)

- [ ] **Step 2: Add the `POST /api/post` route** in `LocalApi.handle`. Change the guard so `POST /api/post` is allowed; parse multipart; prep photos; compose:

```kotlin
    fun handle(method: String, path: String, contentType: String?, body: ByteArray?): HttpResponse? {
        if (method == "POST" && path == "/api/post") return composePost(contentType, body)
        if (method != "GET") return null
        return when { /* ...existing GET routes... */ }
    }

    private fun composePost(contentType: String?, body: ByteArray?): HttpResponse {
        val ct = contentType ?: return badRequest("no content-type")
        val bytes = body ?: return badRequest("no body")
        val form = try { Multipart.parse(ct, bytes) } catch (e: Exception) { return badRequest("bad multipart") }
        val text = form.fields["text"].orEmpty()
        val scope = form.fields["scope"] ?: "kreds"
        if (scope != "kreds") return badRequest("only kreds this slice")
        if (text.isBlank() && form.files.isEmpty()) return badRequest("empty post")
        val jpegs = ArrayList<ByteArray>()
        for (f in form.files) { val j = PhotoPrep.toUploadJpeg(f.bytes) ?: return badRequest("bad image"); jpegs.add(j) }
        val fx = fixtureOrNull() ?: return badRequest("no fixture")
        val (encPriv, encPub) = EncKeys.getOrCreate(sharedStore)
        val createdAt = System.currentTimeMillis() / 1000.0
        return try {
            Compose.post(sharedStore, fx, encPriv, encPub, text, jpegs, scope, createdAt)
            json("{\"ok\":true}")
        } catch (e: Exception) { HttpResponse(500, mapOf("Content-Type" to "text/plain"), ("compose failed: ${e.message}").toByteArray()) }
    }

    private fun badRequest(msg: String) = HttpResponse(400, mapOf("Content-Type" to "text/plain"), msg.toByteArray())
```

- [ ] **Step 3: Run the module suite** — green (no new JVM test; the route is exercised by the loopback gate in Task 9 + on-device).

- [ ] **Step 4: Commit**

```bash
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/PhotoPrep.kt android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalApi.kt
git commit -m "feat(outbound): PhotoPrep (EXIF-strip/downscale/JPEG) + POST /api/post route"
```

---

## Task 8: Outbound blob push in `KotlinSync` BLOBS phase

**Files:**
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/KotlinSync.kt`
- Test: (covered by the loopback gate, Task 9 — no isolated JVM test since it needs the wire exchange)

**Interfaces:**
- The BLOBS phase must now READ the node's `blob_want` frame and RESPOND with `{hash: base64(getBlob(hash))}` for every wanted hash this store holds (currently it sends `emptyMap()` unconditionally).

- [ ] **Step 1: Read the current BLOBS phase** (`KotlinSync.kt:225-244`). Today: send our `blob_want`, read node's want and IGNORE it, send empty blobs, read node's blobs. Change the middle to honor the node's want:

```kotlin
            // -- BLOBS -- (want swap, then blobs swap)
            writeFrame(stream, mapOf("t" to "blob_want", "hashes" to store.missingBlobs()))
            val peerWant = readFrame(stream)                       // node's want -- NOW honored
            val give = linkedMapOf<String, String>()
            (peerWant.optJSONArray("hashes"))?.let { arr ->
                for (i in 0 until arr.length()) {
                    val h = arr.optString(i)
                    val data = store.getBlob(h) ?: continue
                    give[h] = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
                }
            }
            writeFrame(stream, mapOf("t" to "blobs", "blobs" to give))
            val blobs = readFrame(stream)
            // ... existing store-received-blobs loop unchanged ...
```

(Confirm the base64 flavor matches what the RECEIVE side decodes — the receive loop uses `Base64Portable.decode`; use the SAME codec to ENCODE, i.e. `Base64Portable.encode` if it exists, else `android.util.Base64.NO_WRAP` matching hearth's `base64.b64encode`. Verify against `Base64Portable` and hearth `sync.py`'s `base64.b64encode(data).decode()`.)

- [ ] **Step 2: (validation deferred to Task 9's loopback gate)** — build the module: `./gradlew :tor-manager:testDebugUnitTest` compiles + existing sync tests still pass (they push no blobs, so `give` stays empty for them → behavior unchanged for the read-only paths).

- [ ] **Step 3: Commit**

```bash
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/KotlinSync.kt
git commit -m "feat(outbound): honor peer blob_want -- push held blobs on sync"
```

---

## Task 9: Loopback fidelity gate — compose → node ingest+decrypt round-trip

**Files:**
- Create: `android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/SyncComposeLoopbackTest.kt`
- Modify: `android_tor_spike/tools/sync_loopback_node.py` (add a scenario that, after receiving the phone's messages+blobs, decrypts the composed post server-side and emits the plaintext for assertion)

**Interfaces:**
- Consumes: the `startNode(scenario)` / `SocketStream` / `authOnlyOverStream` / `KotlinSync.run(..., outbound=...)` harness (`SyncLoopbackTest.kt`).

- [ ] **Step 1: Extend `sync_loopback_node.py`** with an outbound-compose scenario: seed a node whose identity == the phone fixture's identity, with at least one FRIEND identity + that friend's enckey (so the phone wraps to a friend too), and (for the phone to wrap to own devices) the phone's own enckey. After the session ingests the phone's `messages` + `blobs`, the node: finds the KIND_POST, uses ITS own device enc key (an own device) to `unwrap_key` + `decrypt_body` + `decrypt_blob`, and prints `{"event":"composed", "text":..., "blob_sha_plain":..., "wrapped_friend": true/false}` on stdout for the test to read. (The node IS an own device of the identity, so it must be able to decrypt exactly as a real desktop would.)

- [ ] **Step 2: Write the test** — authenticate, compose a text+1-photo post via `Compose.post` into the Kotlin store, push it (`KotlinSync.run(stream, store, fx.device_pub)` — the MESSAGES phase sends stored own-identity messages incl. the new post; the BLOBS phase now serves the photo), then read the node's `{"event":"composed",...}` line and assert: text matches, the decrypted blob SHA matches the original JPEG, and `wrapped_friend == true` (proves friend-wrapping happened). This proves AAD fidelity + device-authored acceptance + blob push end-to-end, against a REAL hearth node, before the phone.

```kotlin
    @Test fun phoneComposedPostDecryptsOnRealNode() {
        val node = startNode("outbound_compose")
        val fx = KotlinHandshake.parseFixture(node.fixtureJson)
        val store = InMemorySyncStore(); store.addIdentity(fx.cert.identity_pub)
        // seed friend + friend enckey + own enckey by pulling from the node first
        run { val st = SocketStream("127.0.0.1", node.port); KotlinHandshake.authOnlyOverStream(st, fx)
              assertTrue(KotlinSync.run(st, store, fx.device_pub) is SyncResult.Ok) }
        val (encPriv, encPub) = EncKeys.getOrCreate(store)
        val jpeg = javaClass.getResourceAsStream("/tiny.jpg")!!.readBytes()    // a real tiny JPEG test asset
        val res = Compose.post(store, fx, encPriv, encPub, "hi from phone", listOf(jpeg /* already jpeg */), "kreds", 1752900000.5)
        val st2 = SocketStream("127.0.0.1", node.port); KotlinHandshake.authOnlyOverStream(st2, fx)
        assertTrue(KotlinSync.run(st2, store, fx.device_pub) is SyncResult.Ok)
        val composed = node.awaitEvent("composed")
        assertEquals("hi from phone", composed.getString("text"))
        assertTrue("friend was wrapped", composed.getBoolean("wrapped_friend"))
    }
```

(Note: `Compose.post` expects ALREADY-prepped JPEG bytes; in this JVM test pass a small real JPEG asset directly — `PhotoPrep` is Android-only and covered on-device. Add `node.awaitEvent(name)` to the harness mirroring `awaitMaintained`.)

- [ ] **Step 3: Run the loopback gate** — Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.SyncComposeLoopbackTest"`
Expected: PASS — the real node decrypts the phone-composed post + blob and confirms friend-wrapping.

- [ ] **Step 4: Run the full suite** — green.

- [ ] **Step 5: Commit**

```bash
git add android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/SyncComposeLoopbackTest.kt android_tor_spike/tools/sync_loopback_node.py android_tor_spike/app/modules/tor-manager/android/src/test/resources/tiny.jpg
git commit -m "test(outbound): loopback gate -- real node decrypts phone-composed post+blob"
```

---

## Task 10: Seam flip + on-device DoD + report + PAUSE

**Files:**
- Modify: `hearth/web/style.css`
- Modify: `android_tor_spike/app/test/web-readonly-seam.test.ts`
- Create: `android_tor_spike/BRICK_OUTBOUND1_REPORT.md`

- [ ] **Step 1: Update the vitest guard** — assert `.composer` is NO LONGER in the `body.readonly` block, while every OTHER seam selector still is:

```ts
  it("outbound: journal composer is revealed but every other write stays hidden (outbound-1)", () => {
    const css = web("style.css");
    const block = css.slice(css.indexOf("body.readonly"), css.indexOf("display: none !important"));
    expect(block).not.toMatch(/body\.readonly\s+\.composer\b/);      // journal composer revealed
    for (const sel of ["#profile-wall-compose", ".comment-composer", ".rx-open", "#dm-compose"])
      expect(css).toContain("body.readonly " + sel);                 // others still hidden
  });
```

- [ ] **Step 2: Run to verify it fails** — `npx vitest run test/web-readonly-seam.test.ts` → FAIL (`.composer` still in the block).

- [ ] **Step 3: Remove `.composer` from the `body.readonly` selector list** in `hearth/web/style.css` (the block added across slices 1-3). Leave `#profile-wall-compose`, `.comment-composer`, `.rx-open`/`.rx-picker`, `#dm-compose`, and the profile-arrange controls in place. Update the block's comment to note the journal composer is now enabled by the first outbound slice.

- [ ] **Step 4: Run the guard** — PASS. Also confirm nothing else in the seam changed.

- [ ] **Step 5: Full desk-gate sweep** — `./gradlew :tor-manager:testDebugUnitTest` (whole JVM suite incl. the loopback compose gate), `npx tsc --noEmit` (0 new), `npx vitest run test/web-readonly-seam.test.ts`, `./gradlew :app:assembleRelease` (confirm `copyHearthWeb` re-syncs the style.css edit). Record outputs.

- [ ] **Step 6: Build + install the RELEASE apk** on the G20 (`adb install -r ...release/app-release.apk`; force-stop first to avoid the reinstall-prompt hang). Confirm the WebView's file-chooser works for the composer's photo input (react-native-webview handles `<input type=file>` on Android; verify camera/gallery permission is granted — if the picker never opens, that's the integration gap to fix here).

- [ ] **Step 7: On-device DoD (human-driven, August)** — desktop node on `serve --tor`, unlocked; at least one friend synced. On the phone: open the Journal, the composer is now visible; type text + attach a photo; submit. Verify:
  - The post appears in the phone's own Journal immediately (local ingest), text + photo rendering.
  - After a sync, the post appears on the **desktop web feed** with the same text + photo.
  - The post appears in a **friend's** feed (friend-wrapped at compose).
  - The delivered photo blob is EXIF-stripped (no GPS) and displays.
  - Regression: reactions/comments/DM-compose/profile-arrange remain hidden (only the journal composer is enabled).

- [ ] **Step 8: Write `BRICK_OUTBOUND1_REPORT.md`** — desk-gate table; the on-device DoD with pass/fail; run gotchas (RELEASE apk; desktop `serve --tor`; WebView file-chooser permission); honest boundary (kreds journal text+photo only; inner/reactions/comments/DM/story/profile-edit/albums deferred; JPEG not AVIF; no thumbnails [full-image fallback]); follow-up tickets: (a) **no revocation modeling** — `enckeys()` may wrap to a friend's revoked device; (b) `inner` scope needs KIND_RING processing; (c) server-side/tile thumbnails; (d) delete/retract from the phone; (e) the WebView file-chooser camera-capture UX; (f) multipart parser hardening (size caps, malformed-part fuzz).

- [ ] **Step 9: Commit + PAUSE**

```bash
git add hearth/web/style.css android_tor_spike/app/test/web-readonly-seam.test.ts android_tor_spike/BRICK_OUTBOUND1_REPORT.md
git commit -m "feat(outbound): reveal journal composer + on-device proof record"
```

Then PAUSE for human review. Whether to merge is August's call.

---

## Self-Review

**1. Spec coverage:**
- Native compose pipeline (recipients → content key → encrypt body/blobs → wrap → make_post → device-sign → local insert) → Tasks 1-5. ✓
- `POST /api/post` + multipart → Tasks 6-7. ✓
- Photo pipeline (EXIF-strip/downscale/JPEG) → Task 7 (`PhotoPrep`). ✓
- `enckeys` recipient accessor → Task 1. ✓
- Seam flip (only `.composer`) → Task 10. ✓
- Outbound push (message + blob) → the MESSAGES phase already pushes stored own messages; the BLOBS phase gap → Task 8. ✓
- Round-trip loopback fidelity gate → Task 9. ✓
- Scope=kreds-only, journal-only, JPEG, mandatory EXIF-strip, decrypt-on-read, device-sign, no-revocation limitation → Global Constraints + enforced in Compose/PhotoPrep + reported. ✓
- On-device DoD → Task 10. ✓

**2. Placeholder scan:** No TBD/TODO. `PhotoPrep`/route lack isolated JVM tests by necessity (Android runtime) — explicitly on-device-verified, mirroring the established SQLite-only pattern; not a placeholder.

**3. Type consistency:** `enckeys(identityPub): Map<String,String>` consistent across interface/both impls/Compose. `wrapKey(...): Map<String,Map<String,String>>`, `encryptBody(...): Pair<String,String>`, `encryptBlob(...): ByteArray`, `Compose.post(...): Compose.Result` consistent between definition (Tasks 2/3/5) and use (Task 5/9). `SignedMessage.toDict()` used by composeEncKey (Task 4) + is the shape a node parses. `api` provider gains `contentType, body` (Task 6) consumed by `LocalApi.handle(method, path, contentType, body)` (Task 7). `Multipart.parse(contentType, body): Form` / `Form(fields, files)` / `Part(name, filename, bytes)` consistent (Task 6 → Task 7).

**Note for the implementer:** Task 8's base64 codec MUST match the receive-side decoder (`Base64Portable`) and hearth's `base64.b64encode`; confirm before relying on `android.util.Base64.NO_WRAP`. Task 6's body read must drain the `BufferedReader`, not the raw socket stream (buffering trap). Task 9 needs a real tiny JPEG test asset committed under `src/test/resources/`.
