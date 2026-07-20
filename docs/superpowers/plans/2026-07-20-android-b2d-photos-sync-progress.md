# Android B.2d-1 — Photos + Sync Progress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The phone's feed renders real photos (own + friends') by decrypting blobs with the content key it already recovers and decoding AVIF in an isolated process, plus a live sync-progress status line replacing the 1-2 min of dead air.

**Architecture:** Pure phone-side (no hearth change). A Kotlin blob-decrypt primitive (vector-gated) + a format-dispatch object whose AVIF branch calls an `android:isolatedProcess` decode service (dav1d/libavif behind our boundary, sandboxed from keys/store/network) + DecryptPass surfacing blob refs and per-message content keys + a lazy `getBlobImage` module accessor + RN thumbnail/fullscreen rendering + a `KotlinSync.onProgress` callback surfaced as an `onSyncProgress` event.

**Tech Stack:** Kotlin + BouncyCastle (blob ChaCha20-Poly1305), a maintained AVIF decoder AAR, Android bound `isolatedProcess` service + `ParcelFileDescriptor` IPC, the existing `KotlinSync`/`DecryptPass`/`SqliteSyncStore`/`TorManagerModule`, React Native (App.tsx), Python `hearth.dmcrypt` (vector generator only).

**Spec:** `docs/superpowers/specs/2026-07-20-android-b2d-photos-sync-progress-design.md`

## Global Constraints

- **Commit messages: NO AI/Co-Authored-By trailers.** Style `feat(b2d):` / `fix(b2d):` / `docs(b2d):` lowercase.
- **NO hearth change.** `hearth/` is touched only by the vector generator tool (`android_tor_spike/tools/make_dmcrypt_vectors.py`, extended) which imports `hearth.dmcrypt` read-only. `wire.ts`, `handshake.ts`, `wire_vectors.json`, and the proven Kotlin objects (KotlinDmcrypt/KotlinWire/EncKeys) are consumed, not modified.
- **Decrypt-on-read posture:** no decrypted image bytes or content keys are ever written to disk; they live only in the module's in-memory caches (feedCache lifetime, cleared on next sync, gone on process death).
- **The AVIF decoder runs ONLY inside the isolated service.** The main process never links or calls it directly. The isolated process gets one image's cleartext compressed bytes and returns PNG bytes — never keys, ciphertext, the store, or a socket.
- **Blob crypto (from `hearth/dmcrypt.py`, verified):** `decrypt_blob(key, data)` = ChaCha20-Poly1305, nonce=`data[:12]`, ct=`data[12:]`, aad=`BLOB_AAD = b"hearth/dm-blob/v1"`; null if `len(data) < 13` or auth fails. Blobs use the SAME per-message content key as the body.
- **App:** package `eu.kreds.torspike`; the isolated service process name `:imagedecode`; compileSdk 36; NDK 26.3.11579264; arm64-v8a; the G20 is API 30.
- **Expo v57:** any change under `android_tor_spike/app/` that touches TS/the Expo module surface MUST follow `android_tor_spike/app/AGENTS.md` -> read `https://docs.expo.dev/versions/v57.0.0/` before writing module/TS code.
- **Env:** dot-source `android_tor_spike/tools/env.ps1` in every PowerShell session touching gradle/adb; Python uses `.venv\Scripts\python.exe`; generous timeouts (up to 600000 ms). August drives on-device (G20 serial ZY32DLZQ2N); Claude runs desk gates + adb.
- **Existing interfaces (pinned 2026-07-20):**
  - `KotlinSync.run(stream, store, ownDevicePub, outbound = emptyList()): SyncResult` (KotlinSync.kt:160).
  - `DecryptPass.run(store, phoneDevicePub, encPrivHex, ownIdentityPub): List<Decrypted>`; `Decrypted(msgId, kind, author, text, createdAt)` (DecryptPass.kt:29-32).
  - `SyncStore`: `putBlob(hash, data): Boolean`, `missingBlobs(): List<String>` exist; NO public blob read accessor yet. Payloads carry `blobs: List<String>` and optional `thumbs: List<String?>` (same length when present).
  - Module `feedCache: List<DecryptPass.Decrypted>` set in `syncNow` after sync; `getFeed` maps it; events sent via `sendEvent("name", map)` (TorManagerModule.kt:45,157,257,278).

## File Structure

```
android .../tormanager/
  KotlinBlobCrypt.kt          Task 1: decryptBlob primitive
  KotlinImageDecode.kt        Task 2: magic-byte format dispatch (pure) + AVIF->service hook
  ImageDecodeService.kt       Task 3: isolatedProcess AVIF decode service + FD-pipe IPC
  SyncStore.kt / SqliteSyncStore.kt   Task 4: getBlob(hash) read accessor
  DecryptPass.kt              Task 4: Decrypted + blobs/thumbs; run() returns keys map
  KotlinSync.kt               Task 6: onProgress callback
  TorManagerModule.kt         Task 5/6: getBlobImage + key cache + getFeed marshal; onSyncProgress
  index.ts                    Task 5/6: getBlobImage, FeedItem.blobs/thumbs, onSyncProgress
  AndroidManifest.xml         Task 3: <service android:isolatedProcess :imagedecode>
  build.gradle                Task 3: AVIF decoder dependency
android .../src/test/.../
  KotlinBlobCryptTest.kt      Task 1
  KotlinImageDecodeTest.kt    Task 2
android .../src/androidTest/.../
  ImageDecodeInstrumentedTest.kt  Task 3: on-G20 AVIF decode gate
android_tor_spike/tools/make_dmcrypt_vectors.py + fixtures/dmcrypt_vectors.json  Task 1 (+ AVIF fixture)
android_tor_spike/app/App.tsx   Task 7
android_tor_spike/BRICK_B2D1_REPORT.md   Task 8
```

---

### Task 1: `KotlinBlobCrypt` + committed blob vector

**Files:**
- Create: `android/src/main/java/expo/modules/tormanager/KotlinBlobCrypt.kt`
- Modify: `android_tor_spike/tools/make_dmcrypt_vectors.py` (+ a blob case) -> regenerate `android_tor_spike/fixtures/dmcrypt_vectors.json`
- Test: `android/src/test/java/expo/modules/tormanager/KotlinBlobCryptTest.kt`

**Interfaces:**
- Consumes: `KotlinWire.fromHex`, BouncyCastle `ChaCha20Poly1305`.
- Produces: `object KotlinBlobCrypt { fun decryptBlob(contentKey: ByteArray, cipher: ByteArray): ByteArray? }`.

- [ ] **Step 1: Add a blob case to the vector generator.** In `make_dmcrypt_vectors.py`'s `build()`, add (import `encrypt_blob` from `hearth.dmcrypt`):
```python
    # a blob encrypted with a content key (BLOB_AAD, no per-message aad)
    from hearth.dmcrypt import encrypt_blob
    bkey = new_content_key()
    blob_plain = b"\x89PNG\r\n\x1a\n" + b"kreds-blob-vector-bytes" * 4
    blob_cipher = encrypt_blob(bkey, blob_plain)
    cases.append({
        "kind": "blob", "content_key": bkey.hex(),
        "cipher": blob_cipher.hex(), "plain": blob_plain.hex(),
    })
```
Run: `.venv\Scripts\python.exe android_tor_spike\tools\make_dmcrypt_vectors.py` and confirm the new `"kind":"blob"` case is in the fixture.

- [ ] **Step 2: Write the failing test** `KotlinBlobCryptTest.kt`:
```kotlin
package expo.modules.tormanager

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KotlinBlobCryptTest {
    private fun blobCase(): JSONObject {
        val t = javaClass.classLoader!!.getResourceAsStream("dmcrypt_vectors.json")!!
            .readBytes().toString(Charsets.UTF_8)
        val cs = JSONObject(t).getJSONArray("cases")
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            if (c.getString("kind") == "blob") return c
        }
        throw IllegalStateException("no blob vector")
    }

    @Test fun decryptsBlobToExactBytes() {
        val c = blobCase()
        val key = KotlinWire.fromHex(c.getString("content_key"))
        val cipher = KotlinWire.fromHex(c.getString("cipher"))
        val got = KotlinBlobCrypt.decryptBlob(key, cipher)
        assertArrayEquals(KotlinWire.fromHex(c.getString("plain")), got)
    }

    @Test fun wrongKeyReturnsNull() {
        val c = blobCase()
        val bad = ByteArray(32) { 0x11 }
        assertNull(KotlinBlobCrypt.decryptBlob(bad, KotlinWire.fromHex(c.getString("cipher"))))
    }

    @Test fun shortInputReturnsNull() {
        assertNull(KotlinBlobCrypt.decryptBlob(ByteArray(32), ByteArray(5)))
    }
}
```

- [ ] **Step 3: Run — expect FAIL** (unresolved `KotlinBlobCrypt`). Gradle from `android_tor_spike\app\android`: `.\gradlew :tor-manager:testDebugUnitTest --tests "*KotlinBlobCryptTest*"`.

- [ ] **Step 4: Implement** `KotlinBlobCrypt.kt` (mirror `KotlinDmcrypt`'s BouncyCastle AEAD path):
```kotlin
package expo.modules.tormanager

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/** Kotlin port of hearth.dmcrypt.decrypt_blob: ChaCha20-Poly1305 over
 *  cipher[12:] with nonce cipher[:12] and the constant BLOB_AAD. Blobs use
 *  the SAME per-message content key as the body. Vector-gated. */
object KotlinBlobCrypt {
    private val BLOB_AAD = "hearth/dm-blob/v1".toByteArray()

    fun decryptBlob(contentKey: ByteArray, cipher: ByteArray): ByteArray? {
        if (cipher.size < 13) return null
        return try {
            val nonce = cipher.copyOfRange(0, 12)
            val ct = cipher.copyOfRange(12, cipher.size)
            val c = ChaCha20Poly1305()
            c.init(false, AEADParameters(KeyParameter(contentKey), 128, nonce, BLOB_AAD))
            val out = ByteArray(c.getOutputSize(ct.size))
            val n = c.processBytes(ct, 0, ct.size, out, 0)
            c.doFinal(out, n)   // throws on auth failure
            out
        } catch (e: Exception) { null }
    }
}
```
> **Implementer:** confirm BouncyCastle's `ChaCha20Poly1305` here matches the exact init/doFinal shape `KotlinDmcrypt.chachaOpen` uses (read KotlinDmcrypt.kt) — reuse its idiom verbatim so both go through the identical AEAD path.

- [ ] **Step 5: Run — expect PASS.** Also ensure the gradle `copyDmcryptVectors` task still copies the regenerated fixture into test resources.

- [ ] **Step 6: Commit**
```powershell
cd C:\Users\Wong\Desktop\Hearth
git add android_tor_spike/tools/make_dmcrypt_vectors.py android_tor_spike/fixtures/dmcrypt_vectors.json android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/KotlinBlobCrypt.kt android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/KotlinBlobCryptTest.kt
git commit -m "feat(b2d): KotlinBlobCrypt - decrypt_blob port green against a committed blob vector from real hearth"
```

---

### Task 2: `KotlinImageDecode` format dispatch (pure)

The magic-byte dispatch — pure, fully JVM-testable. The AVIF branch calls an injectable decoder function so the dispatch is testable without a native `.so`; Task 3 wires the real isolated-service decoder in.

**Files:**
- Create: `android/src/main/java/expo/modules/tormanager/KotlinImageDecode.kt`
- Test: `android/src/test/java/expo/modules/tormanager/KotlinImageDecodeTest.kt`

**Interfaces:**
- Produces:
```kotlin
object KotlinImageDecode {
    // The AVIF decoder, injected so dispatch is testable and the real
    // decoder (Task 3) stays behind the isolated-service boundary.
    // Returns raw decoded PNG bytes, or null on decode failure.
    var avifDecoder: ((ByteArray) -> ByteArray?)? = null

    // (mime, bytes) ready for a data URI, or null if not a renderable image.
    fun toRenderable(bytes: ByteArray): Pair<String, ByteArray>?
}
```

- [ ] **Step 1: Write the failing test** `KotlinImageDecodeTest.kt`:
```kotlin
package expo.modules.tormanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class KotlinImageDecodeTest {
    @After fun tearDown() { KotlinImageDecode.avifDecoder = null }

    private fun png() = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(8)
    private fun jpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(8)
    private fun gif() = "GIF89a".toByteArray() + ByteArray(8)
    private fun avif() = ByteArray(4) + "ftypavif".toByteArray() + ByteArray(8)   // ....ftypavif...
    private fun mp4() = ByteArray(4) + "ftypisom".toByteArray() + ByteArray(8)

    @Test fun passesThroughKnownRasterFormats() {
        assertEquals("image/png", KotlinImageDecode.toRenderable(png())!!.first)
        assertEquals("image/jpeg", KotlinImageDecode.toRenderable(jpeg())!!.first)
        assertEquals("image/gif", KotlinImageDecode.toRenderable(gif())!!.first)
    }

    @Test fun avifRoutesToDecoderAndReturnsPng() {
        val fakePng = png()
        KotlinImageDecode.avifDecoder = { _ -> fakePng }
        val r = KotlinImageDecode.toRenderable(avif())!!
        assertEquals("image/png", r.first)
        assertTrue(r.second.contentEquals(fakePng))
    }

    @Test fun avifWithNoDecoderOrFailedDecodeIsNull() {
        KotlinImageDecode.avifDecoder = null
        assertNull(KotlinImageDecode.toRenderable(avif()))
        KotlinImageDecode.avifDecoder = { _ -> null }
        assertNull(KotlinImageDecode.toRenderable(avif()))
    }

    @Test fun nonImageBytesAreNull() {
        assertNull(KotlinImageDecode.toRenderable(mp4()))
        assertNull(KotlinImageDecode.toRenderable(ByteArray(3)))
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (unresolved). `.\gradlew :tor-manager:testDebugUnitTest --tests "*KotlinImageDecodeTest*"`.

- [ ] **Step 3: Implement** `KotlinImageDecode.kt`:
```kotlin
package expo.modules.tormanager

/** Magic-byte format dispatch for a decrypted blob. Raster formats the
 *  platform can already render pass through with their mime; AVIF (the
 *  format hearth stores photos in) is routed to the injected decoder --
 *  wired in Task 3 to the isolatedProcess ImageDecodeService, so the native
 *  decoder never links into the main process. Non-images (e.g. a video mp4)
 *  return null -> UI placeholder. Mirrors imagegate.is_image_bytes + AVIF. */
object KotlinImageDecode {
    var avifDecoder: ((ByteArray) -> ByteArray?)? = null

    private fun starts(b: ByteArray, vararg sig: Int): Boolean {
        if (b.size < sig.size) return false
        for (i in sig.indices) if ((b[i].toInt() and 0xFF) != sig[i]) return false
        return true
    }
    private fun ascii(b: ByteArray, off: Int, s: String): Boolean {
        if (b.size < off + s.length) return false
        for (i in s.indices) if ((b[off + i].toInt() and 0xFF) != s[i].code) return false
        return true
    }

    fun toRenderable(bytes: ByteArray): Pair<String, ByteArray>? {
        return when {
            starts(bytes, 0x89, 0x50, 0x4E, 0x47) -> "image/png" to bytes
            starts(bytes, 0xFF, 0xD8) -> "image/jpeg" to bytes
            starts(bytes, 0x47, 0x49, 0x46, 0x38) -> "image/gif" to bytes
            starts(bytes, 0x42, 0x4D) -> "image/bmp" to bytes
            starts(bytes, 0x49, 0x49, 0x2A, 0x00) || starts(bytes, 0x4D, 0x4D, 0x00, 0x2A) -> "image/tiff" to bytes
            ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP") -> "image/webp" to bytes
            ascii(bytes, 4, "ftyp") && (ascii(bytes, 8, "avif") || ascii(bytes, 8, "avis")) ->
                avifDecoder?.invoke(bytes)?.let { "image/png" to it }
            else -> null
        }
    }
}
```

- [ ] **Step 4: Run — expect PASS.** Commit:
```powershell
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/KotlinImageDecode.kt android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/KotlinImageDecodeTest.kt
git commit -m "feat(b2d): KotlinImageDecode - magic-byte format dispatch, AVIF via an injectable decoder (pure, JVM-tested)"
```

---

### Task 3: Isolated-process AVIF decode service (the native security boundary)

The heavy task. NOT JVM-testable (native `.so` + a real second process); its gate is an **instrumented androidTest run ON THE G20** (August connects the device; established Brick-A pattern) plus code review. Functional confirmation also lands in Task 8.

**Files:**
- Create: `android/src/main/java/expo/modules/tormanager/ImageDecodeService.kt`
- Modify: `android/src/main/AndroidManifest.xml` (declare the isolated service)
- Modify: `android/build.gradle` (AVIF decoder dependency)
- Wire: `KotlinImageDecode.avifDecoder` <- a client that binds the service and round-trips bytes (set once at module init, Task 5 confirms the wiring point; declare the client here).
- Test: `android/src/androidTest/java/expo/modules/tormanager/ImageDecodeInstrumentedTest.kt`

**Interfaces:**
- Produces: an `ImageDecodeService` (bound service, `android:isolatedProcess="true"`, `android:process=":imagedecode"`) exposing a synchronous "decode these AVIF bytes -> PNG bytes (or null)" call over IPC; and an `ImageDecodeClient.decodeAvif(bytes): ByteArray?` the module wires into `KotlinImageDecode.avifDecoder`.

- [ ] **Step 1: Choose + add the decoder dependency.** Research a MAINTAINED dav1d/libavif-backed AVIF decoder AAR that: decodes on API 30, ships arm64-v8a `.so`s, and has an AGPL-3.0-compatible license. Candidate: `com.github.awxkee:avif-coder` (verify current version, license, ABIs). Add to `android/build.gradle` dependencies. **If none qualifies, STOP and report NEEDS_CONTEXT — do not hand-roll a decoder and do not ship AVIF as placeholders.** Document the chosen lib + version + license in the report.

- [ ] **Step 2: Declare the isolated service** in `AndroidManifest.xml` inside `<application>`:
```xml
<service
    android:name="expo.modules.tormanager.ImageDecodeService"
    android:isolatedProcess="true"
    android:process=":imagedecode"
    android:exported="false" />
```
> The `isolatedProcess` UID has no app-data/permission/network access — that is the security value. All data crosses via IPC.

- [ ] **Step 3: Implement `ImageDecodeService` + client.** The service receives compressed AVIF bytes, decodes to a `Bitmap` via the chosen decoder, `Bitmap.compress(PNG)`, returns PNG bytes; null on any failure. Because decoded PNGs and some AVIFs exceed Binder's ~1 MB transaction cap, transfer bytes over a `ParcelFileDescriptor` pipe (write input to a pipe, read output from a pipe), NOT plain Parcel args. The client binds on first use, applies a decode timeout (a wedged/crashing decoder yields null, never hangs the caller), and unbinds when idle. Wire `KotlinImageDecode.avifDecoder = { bytes -> ImageDecodeClient.decodeAvif(bytes) }` at module init.
> **Implementer:** the FD-pipe IPC + service lifecycle + timeout is the crux — pin it against the Expo v57 / Android docs. Keep the decoder call the ONLY thing that touches the AVIF lib, entirely inside the `:imagedecode` process. Structure the service so a decode crash restarts the isolated process without taking down the app (bound-service reconnect).

- [ ] **Step 4: Instrumented gate (on the G20).** `ImageDecodeInstrumentedTest.kt` (androidTest): load the committed AVIF fixture bytes (add a tiny AVIF fixture generated via hearth `transcode_photo` from a solid-color test image, committed under androidTest resources), call `ImageDecodeClient.decodeAvif(fixture)`, assert the result is non-null and starts with the PNG magic `89 50 4E 47`, and (optionally) that `BitmapFactory.decodeByteArray` yields the expected dimensions. Run on the G20: from `android_tor_spike\app\android`, `.\gradlew :tor-manager:connectedDebugAndroidTest` (device connected). Document the run (August connects the G20; Claude runs the gradle/adb).
> If the device isn't available when this task runs, report it: build must succeed (`assembleDebug`) and the instrumented gate is deferred to the Task 8 window — say so explicitly, do not mark the functional decode proven without a real run.

- [ ] **Step 5: Build + commit.** `.\gradlew :tor-manager:assembleDebug` green. Commit:
```
feat(b2d): AVIF decode in an android:isolatedProcess service (dav1d/libavif behind our boundary, FD-pipe IPC), instrumented gate on the G20
```

---

### Task 4: Store `getBlob` + DecryptPass surfaces blobs/thumbs + content keys

**Files:**
- Modify: `SyncStore.kt` (interface + `InMemorySyncStore`) + `SqliteSyncStore.kt` — add `getBlob(hash): ByteArray?`
- Modify: `DecryptPass.kt` — `Decrypted` gains `blobs`/`thumbs`; `run` returns keys too
- Test: `DecryptPassTest.kt` (blob-ref surfacing + key map)

**Interfaces:**
- Produces:
  - `SyncStore.getBlob(hash: String): ByteArray?` (InMemory: `blobs[hash]`; SQLite: `SELECT data FROM blobs WHERE hash=?`).
  - `DecryptPass.Decrypted(msgId, kind, author, text, createdAt, blobs: List<String>, thumbs: List<String?>)`.
  - `DecryptPass.run(...): Result` where `data class Result(val feed: List<Decrypted>, val keys: Map<String, ByteArray>)` — `keys` maps `msgId -> contentKey` for decrypted messages that carry blobs (empty list -> not included). Preserve newest-first order of `feed`.

- [ ] **Step 1: Add `getBlob`** to the interface + both impls; a JVM test (InMemory): `putBlob("aa".repeat(32), bytes)` then `getBlob` returns the bytes, missing hash returns null.

- [ ] **Step 2: Failing DecryptPass test** — extend `DecryptPassTest.kt`: build a post whose decrypted body carries `blobs`/`thumbs` (reuse the fixture-material helpers); assert the returned `Result.feed[0].blobs`/`.thumbs` match the payload lists AND `Result.keys[msgId]` equals the message's content key (the test knows it). Add: a decrypted message with NO blobs is absent from `keys`.

- [ ] **Step 3: Implement.** In `decryptOne`, after recovering `key` and the body, read `body["blobs"]`/`body["thumbs"]` (the decrypted body JSON — junk-guard to `List<String>`/`List<String?>`, default empty) into `Decrypted`; have `decryptOne` also return the `key` so `run` can build the `keys` map for blob-carrying messages. Change `run`'s return to `Result`. Update ALL existing callers/tests to the new return type (the module `syncNow` reads `.feed`; loopback test reads `.feed`) — mechanical, assertions unchanged.
> **Implementer:** the blobs/thumbs come from the DECRYPTED body (`decryptBody` result), not the outer payload — the outer payload's `blobs` are the SAME hashes (validate_payload mirrors them), but read them from the body you already decrypted to avoid a second source of truth. Confirm which the body carries; if the body lacks them, fall back to the outer `m.payload["blobs"]/["thumbs"]` and note it.

- [ ] **Step 4: Full module suite + assembleDebug green. Commit** `feat(b2d): DecryptPass surfaces blob/thumb refs + per-message content keys; store getBlob accessor`

---

### Task 5: Module `getBlobImage` + key cache + feed marshal

**Files:**
- Modify: `TorManagerModule.kt` (key cache, `getBlobImage`, `getFeed` marshal), `index.ts`

**Interfaces:**
- Consumes: `DecryptPass.run(...).keys`, `KotlinBlobCrypt.decryptBlob`, `KotlinImageDecode.toRenderable`, `SyncStore.getBlob`.
- Produces: module `AsyncFunction("getBlobImage") { msgId: String, hash: String -> String? }` returning a `data:<mime>;base64,<...>` URI or null; `getFeed` items gain `blobs: List<String>` and `thumbs: List<String?>`; `index.ts` exports `getBlobImage(msgId, hash): Promise<string | null>` and `FeedItem` gains `blobs: string[]`, `thumbs: (string | null)[]`.

- [ ] **Step 1** — where `syncNow` sets `feedCache = DecryptPass.run(...)` (TorManagerModule.kt:257), capture the new `Result`: `feedCache = res.feed` and add `@Volatile private var blobKeys: Map<String, ByteArray> = emptyMap()` set to `res.keys` (in-memory, same lifetime/cleared-next-sync as feedCache; never persisted — mirror feedCache's doc comment). Update `getFeed`'s map to include `"blobs"` and `"thumbs"`.
- [ ] **Step 2** — implement `getBlobImage(msgId, hash)` on `ioScope`/Dispatchers.IO: `val key = blobKeys[msgId] ?: return null`; `val cipher = store.getBlob(hash) ?: return null`; `val plain = KotlinBlobCrypt.decryptBlob(key, cipher) ?: return null`; `val (mime, bytes) = KotlinImageDecode.toRenderable(plain) ?: return null`; return `"data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)`. Ensure `KotlinImageDecode.avifDecoder` is wired (Task 3) at module init.
- [ ] **Step 3** — `index.ts`: add `getBlobImage`, extend `FeedItem`. Follow `android_tor_spike/app/AGENTS.md` (Expo v57 docs) for the module function/TS surface. Build (`assembleDebug`) + `tsc --noEmit` A/B (no new errors) green.
- [ ] **Step 4: Commit** `feat(b2d): module getBlobImage (lazy in-memory blob decrypt+decode) + feed carries blob/thumb refs`

---

### Task 6: Sync progress (`onProgress` -> `onSyncProgress`)

**Files:**
- Modify: `KotlinSync.kt` (`onProgress` param + calls), `TorManagerModule.kt` (forward as `onSyncProgress`), `index.ts` (event type)

**Interfaces:**
- Produces: `KotlinSync.run(stream, store, ownDevicePub, outbound = emptyList(), onProgress: (String, Int) -> Unit = { _, _ -> }): SyncResult`; module event `onSyncProgress` payload `{phase: String, count: Int}`.

- [ ] **Step 1** — add the `onProgress` param (default no-op, so every existing caller/test is unaffected). Invoke it at the phase boundaries already in `run()`: `onProgress("connecting", 0)` at entry; `onProgress("handshake", 0)` after HAVE; inside the MESSAGES ingest loop `onProgress("messages", i + 1)` (KotlinSync.kt:190-193); inside the BLOBS store loop `onProgress("blobs", n)`; `onProgress("decrypting", 0)` before returning Ok; the module emits `onProgress("done", <msgCount>)` after DecryptPass. A JVM test (against the loopback node, extending `SyncLoopbackTest`) collects the callback invocations and asserts the phase sequence appears in order and the `messages` count is non-decreasing.
- [ ] **Step 2** — in `syncNow`, pass `onProgress = { phase, count -> sendEvent("onSyncProgress", mapOf("phase" to phase, "count" to count)) }`. `index.ts`: declare the `onSyncProgress` event. Build + suite green. Commit `feat(b2d): KotlinSync.onProgress -> onSyncProgress event (live sync status)`

---

### Task 7: Feed UI — photos + fullscreen + sync status line

**Files:**
- Modify: `android_tor_spike/app/App.tsx`

- [ ] **Step 1** — Follow `android_tor_spike/app/AGENTS.md` (Expo v57). In each feed row, render an image thumbnail row: for each hash in `item.blobs` (using `item.thumbs[i] ?? item.blobs[i]`), call `getBlobImage(item.msgId, hash)` lazily (state per hash), render `<Image source={{uri}} style={thumb}/>` when a data URI comes back, else a "media not supported yet" placeholder chip (null = missing/decrypt-fail/non-image). Tap a thumbnail -> a full-screen `<Modal>` rendering the full image via `getBlobImage(item.msgId, item.blobs[i])`, tap-to-close, no pinch-zoom.
- [ ] **Step 2** — a live status line under the Sync button, subscribed to `onSyncProgress`: show e.g. `Syncing… ${count} ${phase}` (or a friendlier `Syncing… N messages / M blobs` by tracking the latest count per phase), resolving to the existing "synced: N msgs / N blobs / N friends" (or the error) on the terminal `onSync`/`nodeSync` event. Keep the existing dashboard intact.
- [ ] **Step 3** — `tsc --noEmit` A/B clean, vitest still green. Build BOTH APKs (`assembleDebug assembleRelease`). Install the RELEASE apk on the G20 (`adb -s ZY32DLZQ2N install -r ...\release\app-release.apk` — release embeds the JS bundle; debug does not, per the B.2c field finding). If Play Protect blocks, report for August's tap. Commit `feat(b2d): feed renders photo thumbnails + fullscreen viewer + live sync status line`

---

### Task 8: On-device run + report

**Files:**
- Create: `android_tor_spike/BRICK_B2D1_REPORT.md`

- [ ] Report + run steps (mirror `BRICK_B2_REPORT.md`/`BRICK_B2C_REPORT.md`, carrying the field lessons: desktop via `python -m hearth serve --dir %APPDATA%\Kreds --http-port P --gossip-port P --tor`, UNLOCK via the web UI first, install the RELEASE apk). Verify: real photos render in the feed (own + friends'), AVIF decodes (a photo actually appears, not a placeholder), tapping opens full-screen, a video/non-image post shows the placeholder, the sync status line ticks live, `adb shell ps | grep imagedecode` shows the isolated process during a decode (isolation is real), own+friend text still correct (B.2/B.2c regression). **PAUSE — human-driven.** Fill the verdict. Commit `docs(b2d): on-device photos + sync-progress run + report`.

---

## Self-Review (performed at write time)

**Spec coverage:** blob decrypt primitive -> Task 1; format dispatch -> Task 2; isolated AVIF decode + threat-model isolation -> Task 3; blob/thumb refs + content-key surfacing + `getBlob` -> Task 4; lazy `getBlobImage` + key cache (decrypt-on-read, in-memory) -> Task 5; sync progress -> Task 6; feed thumbnails + fullscreen + status line -> Task 7; on-device (incl. the isolation `ps` check + AVIF-decode proof) -> Task 8. Decrypt-on-read/no-disk, decoder-only-in-isolated-process, no-hearth-change all held.

**Type consistency:** `KotlinBlobCrypt.decryptBlob(ByteArray, ByteArray): ByteArray?` (Tasks 1/5); `KotlinImageDecode.toRenderable(ByteArray): Pair<String,ByteArray>?` + `avifDecoder` (Tasks 2/3/5); `DecryptPass.Result(feed, keys)` + `Decrypted(..., blobs, thumbs)` (Tasks 4/5); `SyncStore.getBlob` (Tasks 4/5); `KotlinSync.run(..., onProgress)` (Task 6); `getBlobImage(msgId, hash)` + `onSyncProgress{phase,count}` (Tasks 5/6/7).

**Judgment calls flagged:** the AVIF decoder library choice + FD-pipe IPC mechanism are Task-3 implementer-confirms (research + pin against Expo v57/Android docs); if no acceptable lib -> NEEDS_CONTEXT, never hand-roll. Task 3's functional proof is device-only (instrumented gate on the G20 + Task 8), honestly not desk-gateable. blobs/thumbs source = the decrypted body (Task 4 confirms vs outer payload).
