# Android B.2d-2 — Video Posts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The phone's feed shows video posts by their poster with a play affordance, and tapping plays the decrypted mp4 full-screen with seek — streamed in-memory over a token-guarded 127.0.0.1 loopback server so nothing decrypted ever touches disk.

**Architecture:** Pure phone-side, no hearth change. A video post is a normal `KIND_POST` (already decryptable; its AVIF poster already renders via the B.2d-1 `getBlobImage` isolated-decode path). New work: DecryptPass surfaces `media`/`poster`; a tiny loopback `MediaServer` decrypts+streams the mp4 with HTTP range support to the platform video player (Expo `expo-video` → ExoPlayer/MediaCodec, the mediaserver-sandboxed path).

**Tech Stack:** Kotlin (`KotlinBlobCrypt`/`SqliteSyncStore`/`TorManagerModule` from prior slices; a raw `ServerSocket`-based loopback HTTP server), `expo-video` (Expo v57), React Native (App.tsx).

**Spec:** `docs/superpowers/specs/2026-07-20-android-b2d2-video-design.md`

## Global Constraints

- **Commit messages: NO AI/Co-Authored-By trailers.** Style `feat(b2d2):` / `fix(b2d2):` / `docs(b2d2):` lowercase.
- **NO hearth change.** Phone-side only. The proven Kotlin objects (`KotlinBlobCrypt`, `KotlinImageDecode`, `KotlinDmcrypt`, `DecryptPass` except the additive fields below, the B.2d-1 isolated `ImageDecodeService`) are consumed, not modified.
- **Decrypt-on-read preserved:** decrypted mp4 bytes are NEVER written to disk — they stream from memory over the loopback. Content keys stay in the module's in-memory `blobKeys` cache (feedCache lifetime), never persisted.
- **`MediaServer` security (binding):** binds **`127.0.0.1` ONLY** (never `0.0.0.0`) on a **random ephemeral port**; every request path carries a fresh **per-session random token** (403 on mismatch); it maps only `(msgId, hash)` → an injected decrypt resolver (NOT a file server — no path/file access, no arbitrary-hash read beyond what the resolver returns). Foreground-only; torn down with the module.
- **`media`/`poster` are OUTER plaintext payload fields** (`p["media"]` default "photo", `p["poster"]`), read like `thumbs` in B.2d-1 — NOT from the decrypted body.
- **We use the PLATFORM player, not an isolated decoder** (deliberate — Android's mediaserver sandbox + the <=5 MB gate cap make it the more-hardened choice; caging a 30fps player is impractical). No new native decode dependency.
- **App package** `eu.kreds.torspike`; the G20 is API 30. Expo v57: any TS/module-surface/dependency change follows `android_tor_spike/app/AGENTS.md` — read `https://docs.expo.dev/versions/v57.0.0/` before adding `expo-video` or touching the module/TS surface.
- **Env:** dot-source `android_tor_spike/tools/env.ps1` every PowerShell session touching gradle/adb; gradle from `android_tor_spike\app\android`; generous timeouts (600000 ms). August drives on-device (G20 serial ZY32DLZQ2N); Claude runs desk gates + adb.
- **Pinned interfaces (2026-07-20):** `DecryptPass.Decrypted(msgId, kind, author, text, createdAt, blobs: List<String>, thumbs: List<String?>)` (DecryptPass.kt:31-40); `media`/`poster`/`codec`/`thumbs` are outer-payload (validate_payload KIND_POST, messages.py:302-317: video → exactly one `blobs` entry + required hex64 `poster`). `KotlinBlobCrypt.decryptBlob(contentKey: ByteArray, cipher: ByteArray): ByteArray?`. Module: `@Volatile blobKeys: Map<String, ByteArray>` (msgId→contentKey, in-memory), `getBlob(hash): ByteArray?` on the store, `getBlobImage(msgId, hash)` the lazy-decrypt pattern to mirror, `ioScope`/`Dispatchers.IO`.

## File Structure

```
android .../tormanager/
  DecryptPass.kt          Task 1: Decrypted gains media/poster
  TorManagerModule.kt     Task 1: getFeed marshals media/poster; Task 3: getVideoUrl + MediaServer wiring
  MediaServer.kt          Task 2: loopback HTTP server (token/range/127.0.0.1) (new)
  index.ts                Task 1: FeedItem media/poster; Task 3: getVideoUrl
  build.gradle / package.json  Task 3: expo-video dependency
android .../src/test/.../
  MediaServerTest.kt      Task 2: the security + range gate (JVM)
  DecryptPassTest.kt      Task 1: media/poster surfacing
android_tor_spike/app/App.tsx   Task 3: video poster + play + fullscreen player
android_tor_spike/BRICK_B2D2_REPORT.md   Task 4
```

---

### Task 1: DecryptPass surfaces `media`/`poster` + feed marshal

**Files:**
- Modify: `android/src/main/java/expo/modules/tormanager/DecryptPass.kt`
- Modify: `android/src/main/java/expo/modules/tormanager/TorManagerModule.kt` (getFeed map)
- Modify: `android/src/main/java/expo/modules/tormanager/index.ts` (FeedItem)
- Test: `android/src/test/java/expo/modules/tormanager/DecryptPassTest.kt`

**Interfaces:**
- Produces: `Decrypted(msgId, kind, author, text, createdAt, blobs, thumbs, media: String, poster: String?)`; `getFeed` items gain `"media"`/`"poster"`; `FeedItem` gains `media: string`, `poster: string | null`.

- [ ] **Step 1: Failing test** — in `DecryptPassTest.kt`, add a case building a VIDEO post (outer payload `media="video"`, `poster=<hex64>`, one `blobs` entry) via the fixture-material helpers; assert `result.feed[0].media == "video"` and `.poster == <that hash>`. And a photo-post regression: `media == "photo"`, `poster == null`.
- [ ] **Step 2: Run — expect FAIL** (`Decrypted` has no `media`/`poster`).
- [ ] **Step 3: Implement** — add `media`/`poster` to `Decrypted`; in `decryptOne`, read them from the OUTER payload `p` (like B.2d-1 read `thumbs` from `p` for the payload path): `val media = (p["media"] as? String) ?: "photo"`; `val poster = p["poster"] as? String`. (They are plaintext envelope fields — never in the decrypted body.) Update `getFeed`'s per-item `mapOf` to add `"media" to d.media, "poster" to d.poster`. `index.ts`: `FeedItem` gains `media: string; poster: string | null`. Update any existing `Decrypted(...)` construction/tests for the new fields (mechanical).
- [ ] **Step 4: Full module JVM suite + assembleDebug + tsc A/B green. Commit** `feat(b2d2): DecryptPass surfaces post media/poster; feed carries them`

---

### Task 2: `MediaServer` — token-guarded loopback range server (the security gate)

**Files:**
- Create: `android/src/main/java/expo/modules/tormanager/MediaServer.kt`
- Test: `android/src/test/java/expo/modules/tormanager/MediaServerTest.kt`

**Interfaces:**
- Produces:
```kotlin
class MediaServer(private val resolve: (msgId: String, hash: String) -> ByteArray?) {
    val token: String            // fresh 32-byte hex, minted at construction
    fun start(): Int             // binds 127.0.0.1:0, returns the chosen port; idempotent
    fun urlFor(msgId: String, hash: String): String   // http://127.0.0.1:<port>/media/<token>/<msgId>/<hash>
    fun stop()
}
```
- Consumes: nothing from other tasks — `resolve` is injected (the module wires it to `blobKeys`+`getBlob`+`KotlinBlobCrypt` in Task 3). Keeping MediaServer key/store-agnostic is what makes it fully JVM-testable.

- [ ] **Step 1: Write the failing test** `MediaServerTest.kt`:
```kotlin
package expo.modules.tormanager

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL

class MediaServerTest {
    private var srv: MediaServer? = null
    @After fun tearDown() { srv?.stop() }

    private val payload = ByteArray(5000) { (it % 251).toByte() }   // deterministic body
    private fun server(): MediaServer {
        val s = MediaServer { msgId, hash -> if (msgId == "m1" && hash == "h1") payload else null }
        s.start(); srv = s; return s
    }
    private fun get(url: String, range: String? = null): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        if (range != null) c.setRequestProperty("Range", range)
        return c
    }

    @Test fun rightTokenFullBodyIs200AndExactBytes() {
        val s = server()
        val c = get(s.urlFor("m1", "h1"))
        assertEquals(200, c.responseCode)
        val got = c.inputStream.readBytes()
        assertArrayEquals(payload, got)
    }

    @Test fun rangeRequestIs206AndCorrectSlice() {
        val s = server()
        val c = get(s.urlFor("m1", "h1"), range = "bytes=100-199")
        assertEquals(206, c.responseCode)
        assertEquals("bytes 100-199/5000", c.getHeaderField("Content-Range"))
        val got = c.inputStream.readBytes()
        assertArrayEquals(payload.copyOfRange(100, 200), got)
    }

    @Test fun wrongTokenIs403() {
        val s = server()
        val bad = s.urlFor("m1", "h1").replace(s.token, "deadbeef")
        assertEquals(403, get(bad).responseCode)
    }

    @Test fun unknownResourceIs404() {
        val s = server()
        val c = get("http://127.0.0.1:" + portOf(s) + "/media/" + s.token + "/m1/nope")
        assertEquals(404, c.responseCode)
    }

    @Test fun bindsLoopbackOnly() {
        val s = server()
        // The server must NOT be reachable on a non-loopback local address.
        val nonLoopback = InetAddress.getAllByName(InetAddress.getLocalHost().hostName)
            .firstOrNull { !it.isLoopbackAddress }
        // If the host has a routable address, a direct socket to it on the port must fail.
        if (nonLoopback != null) {
            val port = portOf(s)
            var refused = false
            try { Socket(nonLoopback, port).close() } catch (e: Exception) { refused = true }
            assertTrue("server must bind 127.0.0.1 only, not $nonLoopback", refused)
        }
    }

    // helper: parse the port out of a urlFor() result
    private fun portOf(s: MediaServer): Int =
        Regex(":(\\d+)/").find(s.urlFor("x", "y"))!!.groupValues[1].toInt()
}
```
> **Implementer:** if the `bindsLoopbackOnly` host has no routable address (CI), the assertion is skipped by the `if` — that is intentional; the binding is still enforced in code (Step 3 binds `InetAddress.getByName("127.0.0.1")`).

- [ ] **Step 2: Run — expect FAIL** (unresolved `MediaServer`).

- [ ] **Step 3: Implement `MediaServer.kt`** — a minimal single-purpose HTTP/1.1 GET server on a raw `ServerSocket` bound to loopback. Own it (auditable, dependency-free) rather than pulling an HTTP lib — this is the security boundary. Handle exactly: the request line, an optional `Range: bytes=a-b`, token + path match, and a 200/206/403/404 response with correct `Content-Length`/`Content-Range`/`Accept-Ranges`. Serve `resolve(msgId, hash)` bytes from memory.
```kotlin
package expo.modules.tormanager

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/** Foreground-only localhost server that streams DECRYPTED media bytes to the
 *  platform video player without ever writing plaintext to disk. Security:
 *  binds 127.0.0.1 ONLY, random port, a per-session token in the path (403 on
 *  mismatch), and it is NOT a file server -- it only maps (msgId, hash) to the
 *  injected `resolve`. Localhost is reachable by other apps on the device, so
 *  the unguessable token is what keeps them out. */
class MediaServer(private val resolve: (msgId: String, hash: String) -> ByteArray?) {
    val token: String = SecureRandom().let { r -> ByteArray(32).also { r.nextBytes(it) } }
        .joinToString("") { "%02x".format(it) }

    private var server: ServerSocket? = null
    @Volatile private var running = false
    private var port = -1

    @Synchronized fun start(): Int {
        if (running) return port
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))  // loopback ONLY
        server = s; port = s.localPort; running = true
        thread(isDaemon = true, name = "media-server") {
            while (running) {
                val sock = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { runCatching { handle(sock) }; runCatching { sock.close() } }
            }
        }
        return port
    }

    fun urlFor(msgId: String, hash: String) =
        "http://127.0.0.1:$port/media/$token/$msgId/$hash"

    @Synchronized fun stop() {
        running = false
        runCatching { server?.close() }
        server = null; port = -1
    }

    private fun handle(sock: Socket) {
        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        var range: String? = null
        while (true) {                       // consume headers
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", true)) range = line.substringAfter(":").trim()
        }
        val out = sock.getOutputStream()
        // request line: "GET /media/<token>/<msgId>/<hash> HTTP/1.1"
        val path = requestLine.split(" ").getOrNull(1) ?: return respond(out, 400, "bad request")
        val parts = path.trimStart('/').split("/")
        // ["media", token, msgId, hash]
        if (parts.size != 4 || parts[0] != "media") return respond(out, 404, "not found")
        if (parts[1] != token) return respond(out, 403, "forbidden")   // token gate
        val bytes = resolve(parts[2], parts[3]) ?: return respond(out, 404, "not found")
        writeMedia(out, bytes, range)
    }

    private fun writeMedia(out: OutputStream, bytes: ByteArray, range: String?) {
        val total = bytes.size
        var start = 0; var end = total - 1; var code = 200; var status = "OK"
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").split("-")
            val a = spec.getOrNull(0)?.toIntOrNull()
            val b = spec.getOrNull(1)?.toIntOrNull()
            if (a != null) { start = a; end = b ?: (total - 1) }
            else if (b != null) { start = maxOf(0, total - b); end = total - 1 }   // suffix range
            if (start < 0 || start >= total || end >= total || start > end)
                return respond(out, 416, "range not satisfiable")
            code = 206; status = "Partial Content"
        }
        val len = end - start + 1
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $status\r\n")
        sb.append("Content-Type: video/mp4\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Content-Length: $len\r\n")
        if (code == 206) sb.append("Content-Range: bytes $start-$end/$total\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(bytes, start, len)
        out.flush()
    }

    private fun respond(out: OutputStream, code: Int, msg: String) {
        val body = msg.toByteArray()
        out.write(("HTTP/1.1 $code $msg\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
            .toByteArray(Charsets.ISO_8859_1))
        out.write(body); out.flush()
    }
}
```
> **Implementer notes:** (1) the token check MUST run before `resolve` and before any path use — a wrong token never reaches the resolver. (2) `resolve` returning null → 404 (covers unknown hash, missing key, decrypt failure — all fail-closed, no distinction leaked). (3) keep it GET-only; anything else → 404/400. (4) Confirm the `bindsLoopbackOnly` test passes on the desk (it enforces the loopback bind).

- [ ] **Step 4: Run — expect PASS (5/5).** Full module suite + assembleDebug. Commit `feat(b2d2): MediaServer - token-guarded 127.0.0.1 loopback range server for in-memory decrypted media`

---

### Task 3: Module `getVideoUrl` + `expo-video` feed playback

**Files:**
- Modify: `TorManagerModule.kt` (a lazily-started `MediaServer` + `getVideoUrl`), `index.ts` (`getVideoUrl`), `App.tsx` (video render + player), `build.gradle`/`package.json` (`expo-video`)

**Interfaces:**
- Consumes: `MediaServer` (Task 2), `blobKeys`/`getBlob`/`KotlinBlobCrypt`, `Decrypted.media`/`.poster` + `getFeed` fields (Task 1).
- Produces: `getVideoUrl(msgId, hash): Promise<string | null>`.

- [ ] **Step 1: Module wiring** — in `TorManagerModule`, add `@Volatile private var mediaServer: MediaServer? = null`; a lazy getter that constructs it once with the resolver `{ msgId, hash -> blobKeys[msgId]?.let { key -> SqliteSyncStore(ctx).getBlob(hash)?.let { KotlinBlobCrypt.decryptBlob(key, it) } } }` and calls `start()`. `AsyncFunction("getVideoUrl") { msgId: String, hash: String -> if (blobKeys[msgId] == null) null else ensureMediaServer().urlFor(msgId, hash) }.runOnQueue(ioScope)`. Tear the server down in the module's destroy/`OnDestroy` (mirror how the B.2d-1 `ImageDecodeClient`/other resources are released) so no listener thread leaks.
> **Implementer:** the resolver decrypts on demand per range request — a seek re-reads+re-decrypts the whole blob then slices; acceptable for the <=5 MB gate cap. If a future large-media case makes that costly, cache the decrypted bytes for the currently-playing hash only (in-memory, evicted on stop) — note it, don't build it now.
- [ ] **Step 2: `expo-video` dependency** — add per Expo v57 (`android_tor_spike/app/AGENTS.md`; read the versioned docs for the exact install + config-plugin steps). `index.ts`: `getVideoUrl(msgId, hash): Promise<string | null>`.
- [ ] **Step 3: `App.tsx`** — a `media === "video"` feed row renders its poster via the existing `getBlobImage(msgId, poster)` with a ▶ overlay; tap → the fullscreen `Modal` hosts the `expo-video` player with `source = { uri: await getVideoUrl(msgId, blobs[0]) }`, native controls, autoplay, and RELEASE the player on modal close (avoid a leaked player). A photo post is unchanged (it has no `poster`/`media==="video"`). Follow `AGENTS.md`.
- [ ] **Step 4** — tsc A/B clean, vitest green, `assembleDebug` + module suite green; build BOTH APKs; install the RELEASE apk on the G20. Commit `feat(b2d2): getVideoUrl + expo-video feed playback (poster + fullscreen)`

---

### Task 4: On-device run + report

**Files:**
- Create: `android_tor_spike/BRICK_B2D2_REPORT.md`

- [ ] Report + run steps (mirror `BRICK_B2D1_REPORT.md`, carrying the field lessons: desktop via `python -m hearth serve --dir %APPDATA%\Kreds --http-port P --gossip-port P --tor`, UNLOCK via the web UI first, install the RELEASE apk). Verify: a real video post shows its poster + ▶ in the feed; tapping plays the decrypted mp4 full-screen with WORKING SEEK (range requests); playback is smooth; a photo post still renders as before (regression); and confirm the decrypt-on-read posture held (no decrypted mp4 written to disk — the loopback stream is the only path). The desktop store already holds video posts (compose one from the desktop if needed). **PAUSE — human-driven.** Fill the verdict. Commit `docs(b2d2): on-device video playback run + report`.

---

## Self-Review (performed at write time)

**Spec coverage:** media/poster surfacing → Task 1; the loopback MediaServer (token/range/127.0.0.1, the security gate) → Task 2; getVideoUrl + expo-video playback + poster reuse → Task 3; on-device seek proof → Task 4. Decrypt-on-read (in-memory stream, no disk), platform-player decision, poster-via-existing-getBlobImage all honored. No hearth change.

**Type consistency:** `Decrypted(..., media: String, poster: String?)` (Tasks 1/3); `MediaServer(resolve).token/start()/urlFor()/stop()` (Tasks 2/3); `getVideoUrl(msgId, hash): Promise<string|null>` + `FeedItem.media/poster` (Tasks 1/3).

**Judgment calls flagged:** `MediaServer` is raw-`ServerSocket` (own the security boundary, no HTTP dep) with a thorough JVM range/token/binding gate; `expo-video` exact install is an Expo-v57 implementer-confirm (AGENTS.md); the resolver re-decrypts per range request (fine at the <=5 MB cap; per-playing-hash cache is a noted, un-built follow-up). The playback itself is device-only (Task 4), same coverage boundary as prior media slices.
