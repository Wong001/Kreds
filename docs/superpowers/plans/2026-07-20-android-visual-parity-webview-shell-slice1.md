# Android Visual Parity — WebView Shell (Slice 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The phone renders the desktop app's journal (feed) view — text posts, photos, video, the stories strip, and responses (reactions + comments) — by serving hearth's real `hearth/web` UI from a native 127.0.0.1 loopback HTTP server behind a full-screen `react-native-webview`, backed by a read-only `/api/*` subset marshaled from the phone's native SQLite store (decrypt-on-read).

**Architecture:** A generic, JVM-unit-testable loopback server (`LocalWebServer`, evolving `MediaServer`) takes two injected providers — `assets` (bundled static files) and `api` (read-only `/api/*`). A cookie-based session token gates every request before any store access. The Android side injects an assets provider reading `context.assets.open("www/…")` (copied from `hearth/web/` at Gradle build time) and an `api` provider (`LocalApi`) that marshals `DecryptPass`/`SqliteSyncStore` output into hearth's EXACT snake_case JSON shapes so `hearth/web/app.js` runs unchanged. A small shared-UI `body.readonly` seam hides write affordances.

**Tech Stack:** Kotlin (Expo native module `tor-manager`, JUnit4 + org.json JVM tests), TypeScript/React Native (Expo SDK 57.0.7 / RN 0.86.0 / React 19.2.3, `react-native-webview`), Gradle (asset copy), vitest (web-seam guard), the existing `hearth/web` bundle (HTML/CSS/JS).

## Global Constraints

- Commit messages `feat(vp1): …` / `fix(vp1): …`, lowercase. **NO AI / Co-Authored-By / "Generated with" trailers on ANY commit.**
- Decrypt-on-read: NO decrypted bytes or keys are ever written to disk. The server decrypts on demand, in-memory, and streams; caches live only in memory.
- Security (MediaServer pattern): bind **127.0.0.1 ONLY**, OS-assigned ephemeral port; **cookie-based session token** minted as 32 random bytes hex (`SecureRandom`) at server construction; token checked **before any store access**; strict **CSP** header on the served HTML; fail-closed (403/404). Assets are first-party + bundled — never remote.
- Exact hearth JSON shapes: every `/api/*` response must byte/shape-match `hearth/api.py` (SNAKE_CASE: `msg_id`, `created_at`, `identity_pub`, `author_name`, …) so `hearth/web/app.js` runs unchanged. Golden-shape tests are the guard.
- Single source of truth for the web bundle: assets are COPIED from `hearth/web/` into the APK at Gradle build time (`android/app/src/main/assets/www/`) — never duplicated or committed into the RN tree.
- Expo SDK 57.0.7 / RN 0.86.0 / React 19.2.3. Add `react-native-webview` via `npx expo install react-native-webview`. Per `android_tor_spike/app/AGENTS.md`, **verify its Expo-57-compatible version + API against https://docs.expo.dev/versions/v57.0.0/ before writing integration code.**
- The loopback cleartext NSC (`127.0.0.1`/`::1`/`localhost`) and the `android:networkSecurityConfig` manifest attribute ALREADY EXIST (added for MediaServer). The new server needs **NO additional manifest/NSC wiring** — only the Gradle asset-copy + the WebView. The asset-copy is the one new `expo prebuild --clean`-fragile edit; codify it in a config plugin (Task 2).
- Scope of slice 1 endpoints (read-only GET only): `/api/bootstrap`, `/api/applock`, `/api/state`, `/api/feed`, `/api/stories`, `/api/blob/{h}`, `/api/post-blob/{msg_id}/{h}`. **NOT in slice 1:** `/api/conversations`, `/api/dm/*`, `/api/profile/*`, `/api/kreds`, `/api/dm-blob/*`, any write endpoint.

## Pinned design decisions (do NOT re-decide)

1. **Cookie-session token.** WebView's initial URL is `http://127.0.0.1:<port>/?__t=<token>`. The server validates `?__t` on any request, and on the initial navigation replies `Set-Cookie: kreds_session=<token>; Path=/; HttpOnly; SameSite=Strict`. Every later request must present that cookie OR the query token, else 403. app.js's same-origin fetches carry the cookie automatically. Enable cookies in `react-native-webview` (`sharedCookiesEnabled` / `thirdPartyCookiesEnabled`). Token = 32 random bytes hex (`SecureRandom`), minted at server construction.
2. **Server = generic JVM-testable core + injected providers.** `LocalWebServer(assets, api)`. Reuse MediaServer's loopback bind, per-socket daemon thread, 5s soTimeout, 50-header cap, ISO-8859-1 raw write, and range logic. Add static-vs-`/api` dispatch, per-response Content-Type, an `HttpResponse(status, headers, body)` type. Lifecycle in `TorManagerModule` mirrors `ensureMediaServer`/`teardownMediaServer` (same monitor, shared `destroyed` flag).
3. **Feed marshaling.** Run `DecryptPass.run` + `DecryptPass.responsesPass` FRESH per request against a new `SqliteSyncStore(ctx)` (existing "new instance per op, cheap at ~253 msgs" pattern), then marshal each `Decrypted` to hearth's `_decrypt_post_row` snake_case shape. Extend `DecryptPass.Decrypted` ADDITIVELY with `identityPub`, `scope`, `expiresAt`, `placement`, `codec` populated in `decryptOne`. `mine = identityPub == ownIdentityPub`; `author_name = d.author`; `author_avatar = null` (avatars DEFERRED). Flat newest-first list. ONLY `placement == "journal"` rows (default `"journal"` if absent — CONFIRMED present in `make_post`'s signed payload, see Task 4).
4. **Responses shape.** Emit `my_reaction: null`, `can_moderate: false`. Extend `KotlinResponses.Comment` ADDITIVELY with `alias: Boolean`, `aliasSeed: String`, `name: String?`. Marshal each comment to `{name: (alias? null : name), avatar: null, alias, alias_seed: aliasSeed, mine: false, body, created_at}` and OMIT `responder`. A post with no responses record → `responses: null`.
5. **Blob/media.** `/api/post-blob/{msg_id}/{h}`: content-key decrypt (fresh `DecryptPass.Result.keys[msgId]` → `KotlinBlobCrypt.decryptBlob`) → raw plaintext; sniff content-type (Kotlin port of hearth `_sniff`); serve with range support (video seek). `/api/blob/{h}`: raw `store.getBlob(h)` (NO content-key decrypt), sniffed. **AVIF: serve raw AVIF bytes as `image/avif`** — the WebView's Chromium renderer decodes it. (Fallback: isolated `KotlinImageDecode.toRenderable` PNG-transcode if AVIF fails to render on the G20/API30 WebView — deferred ticket.) Bytes in-memory only.
6. **Read-only seam.** `/api/state` returns `"readonly": true`. `hearth/web/app.js` sets `document.body.classList.toggle("readonly", !!STATE.readonly)`; `hearth/web/style.css` adds `body.readonly` rules hiding write affordances while keeping read content (count chips) visible. Desktop omits the field → defaults false → unaffected.
7. **Assets + stubs.** Copied `hearth/web/` → `android/app/src/main/assets/www/` by a Gradle copy task. `/api/bootstrap` → `{"initialized": true, "onboarding_done": true}`; `/api/applock` → `{"enabled": false, "locked": false, "cred_type": null, "settings": {"idle_minutes": 0, "lock_on_sleep": false}, "throttle_wait": 0}`; `/api/state` → real `identity_pub`/`device_pub`/`device_name`/`profile_name`/`friends` plus stubbed `update_status`/`revoked`/`accent`/`peers`/`disconnected`/`devices` + `readonly: true`. `/sw.js` served from assets; app.js's absent `/ws` degrades silently.

---

## File Structure

**Native (Kotlin), all under `android_tor_spike/app/modules/tor-manager/android/src/`:**
- `main/java/expo/modules/tormanager/LocalWebServer.kt` — NEW. Generic loopback server + `HttpResponse` type. JVM-testable, no Android imports.
- `main/java/expo/modules/tormanager/LocalAssets.kt` — NEW. Android assets provider (`context.assets.open("www/…")` + mime + path-traversal guard).
- `main/java/expo/modules/tormanager/LocalApi.kt` — NEW. Read-only `/api/*` provider: pure JSON builders (JVM-testable) + Android orchestration (store I/O).
- `main/java/expo/modules/tormanager/TorManagerModule.kt` — MODIFY. `localServer` field, `ensureLocalServer`/`teardownLocalServer`, `getWebUrl` AsyncFunction, OnDestroy hook.
- `main/java/expo/modules/tormanager/DecryptPass.kt` — MODIFY. Additive fields on `Decrypted`; populate in `decryptOne`.
- `main/java/expo/modules/tormanager/KotlinResponses.kt` — MODIFY. Additive fields on `Comment`; populate in `aggregate`.
- `test/java/expo/modules/tormanager/LocalWebServerTest.kt` — NEW. Full server coverage.
- `test/java/expo/modules/tormanager/LocalApiTest.kt` — NEW. Stub builders, feed golden-shape, stories marshal, sniff.

**RN (TypeScript), under `android_tor_spike/app/`:**
- `WebShell.tsx` — NEW. Full-screen WebView host; owns engine bootstrap for slice 1.
- `index.ts` — MODIFY. Register `WebShell`.
- `modules/tor-manager/index.ts` — MODIFY. Export `getWebUrl`.
- `plugins/withHearthWebAssets.js` — NEW. Expo config plugin injecting the Gradle copy task (prebuild resilience).
- `app.json` — MODIFY. Register the config plugin.
- `test/web-readonly-seam.test.ts` — NEW (vitest). Static guard that the `body.readonly` seam exists.

**Gradle / Android project, under `android_tor_spike/app/android/`:**
- `app/build.gradle` — MODIFY. `copyHearthWeb` Copy task + `preBuild` dependency.

**Web (shared UI), under `hearth/web/`:**
- `app.js` — MODIFY (one line). `body.readonly` toggle.
- `style.css` — MODIFY. `body.readonly` hide rules.

**Docs, under `android_tor_spike/`:**
- `BRICK_VP1_REPORT.md` — NEW. On-device DoD + follow-ups.
- `.gitignore` (`android_tor_spike/app/.gitignore`) — MODIFY. Ignore the copied `assets/www/`.

**Test command reference** (Windows: use `.\gradlew.bat`; git-bash: `./gradlew`):
- Kotlin JVM tests: from `android_tor_spike/app/android` → `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.<Class>"`
- TypeScript: from `android_tor_spike/app` → `npx tsc --noEmit`
- vitest: from `android_tor_spike/app` → `npx vitest run test/web-readonly-seam.test.ts`
- Release APK: from `android_tor_spike/app/android` → `./gradlew :app:assembleRelease` (apk at `app/build/outputs/apk/release/app-release.apk`)

---

## Task 1: `LocalWebServer` core

**Files:**
- Create: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalWebServer.kt`
- Test: `android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/LocalWebServerTest.kt`

**Interfaces:**
- Consumes: nothing (pure JVM; no Android imports).
- Produces:
  - `data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)`
  - `class LocalWebServer(assets: (path: String) -> Pair<String, ByteArray>?, api: (method: String, path: String, query: String?, cookieToken: String?, queryToken: String?) -> HttpResponse?)`
  - `LocalWebServer.token: String` (32-byte hex), `start(): Int`, `stop()`, `rootUrl(): String` (= `http://127.0.0.1:<port>/?__t=<token>`), `portOrNull(): Int`

- [ ] **Step 1: Write the failing test**

Create `LocalWebServerTest.kt`:

```kotlin
package expo.modules.tormanager

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL

class LocalWebServerTest {
    private var srv: LocalWebServer? = null
    @After fun tearDown() { srv?.stop() }

    private val indexHtml = "<html><body>hi</body></html>".toByteArray()
    private val big = ByteArray(5000) { (it % 251).toByte() }

    private fun server(): LocalWebServer {
        val assets: (String) -> Pair<String, ByteArray>? = { p ->
            when (p) {
                "index.html" -> "text/html; charset=utf-8" to indexHtml
                "static/big.bin" -> "application/octet-stream" to big
                else -> null
            }
        }
        val api: (String, String, String?, String?, String?) -> HttpResponse? = { _, path, _, _, _ ->
            if (path == "/api/state")
                HttpResponse(200, mapOf("Content-Type" to "application/json"), "{\"ok\":true}".toByteArray())
            else null
        }
        val s = LocalWebServer(assets, api); s.start(); srv = s; return s
    }

    private fun portOf(s: LocalWebServer): Int =
        Regex(":(\\d+)/").find(s.rootUrl())!!.groupValues[1].toInt()

    private fun conn(s: LocalWebServer, path: String, cookie: String? = null, range: String? = null): HttpURLConnection {
        val c = URL("http://127.0.0.1:${portOf(s)}$path").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        if (cookie != null) c.setRequestProperty("Cookie", cookie)
        if (range != null) c.setRequestProperty("Range", range)
        return c
    }

    @Test fun initialNavWithQueryTokenServesIndexAndSetsCookie() {
        val s = server()
        val c = conn(s, "/?__t=${s.token}")
        assertEquals(200, c.responseCode)
        assertArrayEquals(indexHtml, c.inputStream.readBytes())
        val sc = c.getHeaderField("Set-Cookie")
        assertNotNull(sc)
        assertTrue(sc.contains("kreds_session=${s.token}"))
        assertTrue(sc.contains("HttpOnly"))
    }

    @Test fun htmlResponseCarriesCsp() {
        val s = server()
        val c = conn(s, "/?__t=${s.token}")
        assertEquals(200, c.responseCode)
        assertNotNull(c.getHeaderField("Content-Security-Policy"))
    }

    @Test fun staticWithCookieServes200() {
        val s = server()
        val c = conn(s, "/static/big.bin", cookie = "kreds_session=${s.token}")
        assertEquals(200, c.responseCode)
        assertArrayEquals(big, c.inputStream.readBytes())
    }

    @Test fun noTokenNoCookieIs403() {
        val s = server()
        assertEquals(403, conn(s, "/static/big.bin").responseCode)
    }

    @Test fun wrongTokenIs403() {
        val s = server()
        assertEquals(403, conn(s, "/static/big.bin", cookie = "kreds_session=deadbeef").responseCode)
    }

    @Test fun unknownStaticIs404() {
        val s = server()
        assertEquals(404, conn(s, "/static/missing.js", cookie = "kreds_session=${s.token}").responseCode)
    }

    @Test fun apiRouteDispatchesToProvider() {
        val s = server()
        val c = conn(s, "/api/state", cookie = "kreds_session=${s.token}")
        assertEquals(200, c.responseCode)
        assertEquals("{\"ok\":true}", String(c.inputStream.readBytes()))
    }

    @Test fun apiUnknownIs404() {
        val s = server()
        assertEquals(404, conn(s, "/api/nope", cookie = "kreds_session=${s.token}").responseCode)
    }

    @Test fun rangeRequestIs206Slice() {
        val s = server()
        val c = conn(s, "/static/big.bin", cookie = "kreds_session=${s.token}", range = "bytes=100-199")
        assertEquals(206, c.responseCode)
        assertEquals("bytes 100-199/5000", c.getHeaderField("Content-Range"))
        assertArrayEquals(big.copyOfRange(100, 200), c.inputStream.readBytes())
    }

    @Test fun bindsLoopbackOnly() {
        val s = server()
        val nonLoopback = InetAddress.getAllByName(InetAddress.getLocalHost().hostName)
            .firstOrNull { !it.isLoopbackAddress }
        if (nonLoopback != null) {
            var refused = false
            try { Socket(nonLoopback, portOf(s)).close() } catch (e: Exception) { refused = true }
            assertTrue("server must bind 127.0.0.1 only", refused)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run (from `android_tor_spike/app/android`): `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.LocalWebServerTest"`
Expected: FAIL / compile error — `LocalWebServer` and `HttpResponse` are unresolved references.

- [ ] **Step 3: Write minimal implementation**

Create `LocalWebServer.kt`:

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

/** One HTTP response: status + headers (Content-Type etc.) + full body bytes.
 *  The server applies Range slicing to `body` itself, so a provider always
 *  returns the COMPLETE body and never has to reason about ranges. */
data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)

/** Loopback web server for the WebView shell. Generalizes MediaServer:
 *   - binds 127.0.0.1 ONLY, OS-assigned ephemeral port, backlog 50
 *   - one daemon accept-loop thread; each socket on its own daemon thread
 *   - 5s soTimeout, 50-header cap, ISO-8859-1 raw HTTP write, Range support
 *   - a 32-byte SecureRandom hex token minted at construction
 *   - cookie/query token gate BEFORE any provider call
 *   - two injected providers (assets, api), so it is fully JVM-unit-testable.
 *  Token flow: the WebView's FIRST request carries `?__t=<token>`; the server
 *  validates it and replies Set-Cookie: kreds_session=<token>. Every later
 *  same-origin request carries that cookie automatically. A request with
 *  NEITHER a valid query token NOR the cookie gets 403 before routing. */
class LocalWebServer(
    private val assets: (path: String) -> Pair<String, ByteArray>?,
    private val api: (method: String, path: String, query: String?, cookieToken: String?, queryToken: String?) -> HttpResponse?,
) {
    val token: String = SecureRandom().let { r -> ByteArray(32).also { r.nextBytes(it) } }
        .joinToString("") { "%02x".format(it) }

    private var server: ServerSocket? = null
    @Volatile private var running = false
    private var port = -1

    @Synchronized fun start(): Int {
        if (running) return port
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))  // loopback ONLY
        server = s; port = s.localPort; running = true
        thread(isDaemon = true, name = "local-web-server") {
            while (running) {
                val sock = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { runCatching { handle(sock) }; runCatching { sock.close() } }
            }
        }
        return port
    }

    @Synchronized fun stop() {
        running = false
        runCatching { server?.close() }
        server = null; port = -1
    }

    /** Full URL (with the one-time query token) the WebView loads first. */
    fun rootUrl(): String = "http://127.0.0.1:$port/?__t=$token"
    fun portOrNull(): Int = port

    private fun handle(sock: Socket) {
        sock.soTimeout = 5000
        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        var cookieHeader: String? = null
        var range: String? = null
        var headerLines = 0
        while (true) {
            if (++headerLines > 50) break
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Cookie:", true)) cookieHeader = line.substringAfter(":").trim()
            if (line.startsWith("Range:", true)) range = line.substringAfter(":").trim()
        }
        val out = sock.getOutputStream()
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0) ?: return respond(out, 400, "bad request")
        val rawPath = parts.getOrNull(1) ?: return respond(out, 400, "bad request")

        val qIdx = rawPath.indexOf('?')
        val path = if (qIdx >= 0) rawPath.substring(0, qIdx) else rawPath
        val query = if (qIdx >= 0) rawPath.substring(qIdx + 1) else null

        val queryToken = query?.split("&")
            ?.firstOrNull { it.startsWith("__t=") }?.substringAfter("=")
        val cookieToken = cookieHeader?.split(";")
            ?.map { it.trim() }?.firstOrNull { it.startsWith("kreds_session=") }?.substringAfter("=")

        // Token gate BEFORE any provider/store access.
        if (queryToken != token && cookieToken != token) return respond(out, 403, "forbidden")
        val setCookie = queryToken == token   // stamp the cookie on the initial navigation

        if (path.startsWith("/api/")) {
            val resp = api(method, path, query, cookieToken, queryToken) ?: return respond(out, 404, "not found")
            writeResponse(out, resp, range, setCookie)
            return
        }
        // static: "/" -> index.html; else the path minus its leading slash.
        val key = if (path == "/" || path.isEmpty()) "index.html" else path.trimStart('/')
        val asset = assets(key) ?: return respond(out, 404, "not found")
        writeResponse(out, HttpResponse(200, mapOf("Content-Type" to asset.first), asset.second), range, setCookie)
    }

    private fun writeResponse(out: OutputStream, resp: HttpResponse, range: String?, setCookie: Boolean) {
        val bytes = resp.body
        val total = bytes.size
        var start = 0; var end = total - 1; var code = resp.status; var status = statusText(code)
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").split("-")
            val a = spec.getOrNull(0)?.toIntOrNull()
            val b = spec.getOrNull(1)?.toIntOrNull()
            var parsed = false
            if (a != null) { start = a; end = b ?: (total - 1); parsed = true }
            else if (b != null) { start = maxOf(0, total - b); end = total - 1; parsed = true }
            if (parsed) {
                if (start < 0 || start >= total || start > end) return respond(out, 416, "range not satisfiable")
                if (end >= total) end = total - 1
                code = 206; status = "Partial Content"
            }
        }
        val len = end - start + 1
        val ctype = resp.headers["Content-Type"] ?: "application/octet-stream"
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $status\r\n")
        sb.append("Content-Type: $ctype\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Content-Length: $len\r\n")
        if (code == 206) sb.append("Content-Range: bytes $start-$end/$total\r\n")
        if (setCookie) sb.append("Set-Cookie: kreds_session=$token; Path=/; HttpOnly; SameSite=Strict\r\n")
        if (ctype.startsWith("text/html"))
            sb.append("Content-Security-Policy: default-src 'self'; img-src 'self' data: blob:; " +
                "media-src 'self' blob:; style-src 'self' 'unsafe-inline'; script-src 'self'; " +
                "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'\r\n")
        for ((k, v) in resp.headers) if (!k.equals("Content-Type", true)) sb.append("$k: $v\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(bytes, start, len)
        out.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 403 -> "Forbidden"
        404 -> "Not Found"; 416 -> "Range Not Satisfiable"; else -> "OK"
    }

    private fun respond(out: OutputStream, code: Int, msg: String) {
        val body = msg.toByteArray()
        out.write(("HTTP/1.1 $code $msg\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
            .toByteArray(Charsets.ISO_8859_1))
        out.write(body); out.flush()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.LocalWebServerTest"`
Expected: PASS (all 10 tests). If `bindsLoopbackOnly` is skipped for lack of a routable address, that is fine.

- [ ] **Step 5: Commit**

```bash
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalWebServer.kt \
        android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/LocalWebServerTest.kt
git commit -m "feat(vp1): loopback web server core with cookie token gate and range support"
```

---

## Task 2: Asset bundling + Android wiring + shell serves

**Files:**
- Create: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalAssets.kt`
- Create: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalApi.kt`
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/TorManagerModule.kt`
- Modify: `android_tor_spike/app/android/app/build.gradle`
- Create: `android_tor_spike/app/plugins/withHearthWebAssets.js`
- Modify: `android_tor_spike/app/app.json`
- Modify: `android_tor_spike/app/.gitignore` (create if absent)
- Test: `android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/LocalApiTest.kt`

**Interfaces:**
- Consumes: `HttpResponse`, `LocalWebServer` (Task 1); `KotlinHandshake.parseFixture(json): Fixture` where `Fixture.cert: KotlinWire.CertDict` has `identity_pub`, `device_pub`, `device_name`, and `Fixture.device_pub`; `SqliteSyncStore(ctx)`, `store.profileNames(): Map<String,String>`, `store.knownIdentities(): List<String>`; `TorEngine.externalDir(): File`.
- Produces:
  - `object LocalAssets { fun provide(ctx: Context, key: String): Pair<String, ByteArray>? }`
  - `class LocalApi(ctx: Context) { fun handle(method: String, path: String): HttpResponse? }` with companion `bootstrapJson()`, `applockJson()`, `stateJson(identityPub, devicePub, deviceName, profileName, friends: List<Pair<String,String>>): String`, `json(body: String): HttpResponse`.
  - `TorManagerModule.getWebUrl` AsyncFunction returning `String?` (= `LocalWebServer.rootUrl()`).

- [ ] **Step 1: Write the failing test**

Create `LocalApiTest.kt`:

```kotlin
package expo.modules.tormanager

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalApiTest {
    @Test fun bootstrapStubShape() {
        val o = JSONObject(LocalApi.bootstrapJson())
        assertTrue(o.getBoolean("initialized"))
        assertTrue(o.getBoolean("onboarding_done"))
    }

    @Test fun applockStubShape() {
        val o = JSONObject(LocalApi.applockJson())
        assertFalse(o.getBoolean("enabled"))
        assertFalse(o.getBoolean("locked"))
        assertTrue(o.isNull("cred_type"))
        val s = o.getJSONObject("settings")
        assertEquals(0, s.getInt("idle_minutes"))
        assertFalse(s.getBoolean("lock_on_sleep"))
        assertEquals(0, o.getInt("throttle_wait"))
    }

    @Test fun stateShapeHasAllKeysAndReadonly() {
        val json = LocalApi.stateJson(
            identityPub = "aa", devicePub = "bb", deviceName = "phone",
            profileName = "Me", friends = listOf("cc" to "Cara", "dd" to "dd"))
        val o = JSONObject(json)
        assertEquals("aa", o.getString("identity_pub"))
        assertEquals("bb", o.getString("device_pub"))
        assertEquals("phone", o.getString("device_name"))
        assertEquals("Me", o.getString("profile_name"))
        assertTrue(o.getBoolean("readonly"))
        assertFalse(o.getBoolean("revoked"))
        assertEquals("#2743d6", o.getString("accent"))
        assertEquals(2, o.getJSONArray("friends").length())
        assertEquals("cc", o.getJSONArray("friends").getJSONObject(0).getString("identity_pub"))
        assertEquals("Cara", o.getJSONArray("friends").getJSONObject(0).getString("name"))
        // keys hearth's /api/state always emits (peers/disconnected/devices present as arrays)
        assertTrue(o.has("peers")); assertTrue(o.has("disconnected")); assertTrue(o.has("devices"))
        val us = o.getJSONObject("update_status")
        assertFalse(us.getBoolean("available")); assertTrue(us.isNull("kind")); assertTrue(us.isNull("version"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.LocalApiTest"`
Expected: FAIL / compile error — `LocalApi` is unresolved.

- [ ] **Step 3: Write `LocalApi.kt`**

Create `LocalApi.kt`:

```kotlin
package expo.modules.tormanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Read-only /api/* provider for the WebView shell (slice 1). Pure JSON
 *  builders live in the companion (JVM-testable); the instance methods do the
 *  Android store I/O. A fresh SqliteSyncStore(ctx) is constructed per request
 *  (the existing "new instance per op, cheap at ~253 msgs" pattern). Token is
 *  already validated by LocalWebServer before handle() runs. */
class LocalApi(private val ctx: Context) {

    fun handle(method: String, path: String): HttpResponse? {
        if (method != "GET") return null
        return when (path) {
            "/api/bootstrap" -> json(bootstrapJson())
            "/api/applock" -> json(applockJson())
            "/api/state" -> json(state())
            else -> null
        }
    }

    private fun state(): String {
        val fx = fixtureOrNull()
        val store = SqliteSyncStore(ctx)
        val names = store.profileNames()
        val own = fx?.cert?.identity_pub ?: ""
        val friends = store.knownIdentities().filter { it != own }.map { it to (names[it] ?: it.take(8)) }
        return stateJson(
            identityPub = own,
            devicePub = fx?.device_pub ?: "",
            deviceName = fx?.cert?.device_name ?: "phone",
            profileName = names[own] ?: "",
            friends = friends)
    }

    private fun fixtureOrNull(): KotlinHandshake.Fixture? = try {
        KotlinHandshake.parseFixture(File(TorEngine.externalDir(), "spike_phone_fixture.json").readText())
    } catch (e: Exception) { null }

    companion object {
        fun json(body: String) =
            HttpResponse(200, mapOf("Content-Type" to "application/json; charset=utf-8"), body.toByteArray())

        fun bootstrapJson(): String =
            JSONObject().put("initialized", true).put("onboarding_done", true).toString()

        fun applockJson(): String =
            JSONObject()
                .put("enabled", false).put("locked", false).put("cred_type", JSONObject.NULL)
                .put("settings", JSONObject().put("idle_minutes", 0).put("lock_on_sleep", false))
                .put("throttle_wait", 0).toString()

        fun stateJson(identityPub: String, devicePub: String, deviceName: String,
                      profileName: String, friends: List<Pair<String, String>>): String {
            val friendsArr = JSONArray()
            for ((ipub, name) in friends)
                friendsArr.put(JSONObject().put("identity_pub", ipub).put("name", name))
            return JSONObject()
                .put("identity_pub", identityPub)
                .put("device_pub", devicePub)
                .put("device_name", deviceName)
                .put("profile_name", profileName)
                .put("devices", JSONArray())
                .put("friends", friendsArr)
                .put("peers", JSONArray())
                .put("disconnected", JSONArray())
                .put("revoked", false)
                .put("accent", "#2743d6")
                .put("update_status",
                    JSONObject().put("available", false).put("kind", JSONObject.NULL).put("version", JSONObject.NULL))
                .put("readonly", true)
                .toString()
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.LocalApiTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit the API stubs**

```bash
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalApi.kt \
        android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/LocalApiTest.kt
git commit -m "feat(vp1): read-only api stubs for bootstrap, applock, and state"
```

- [ ] **Step 6: Write the Android assets provider**

Create `LocalAssets.kt`:

```kotlin
package expo.modules.tormanager

import android.content.Context

/** Static asset provider for LocalWebServer, reading the bundled hearth/web
 *  bundle from `assets/www/`. Mapping: LocalWebServer passes the request path
 *  minus its leading slash ("index.html", "static/style.css", "sw.js", …).
 *  hearth mounts its bundle at /static, so a "static/" prefix maps to the
 *  bundle root; "index.html" and "sw.js" map directly. A `..` anywhere is
 *  refused (path-traversal guard) so a crafted request can never escape www/. */
object LocalAssets {
    fun provide(ctx: Context, key: String): Pair<String, ByteArray>? {
        val rel = key.removePrefix("static/")
        if (rel.contains("..") || rel.startsWith("/")) return null
        val bytes = try {
            ctx.assets.open("www/$rel").use { it.readBytes() }
        } catch (e: Exception) { return null }
        return mimeFor(rel) to bytes
    }

    private fun mimeFor(name: String): String = when {
        name.endsWith(".html") -> "text/html; charset=utf-8"
        name.endsWith(".js") -> "application/javascript; charset=utf-8"
        name.endsWith(".css") -> "text/css; charset=utf-8"
        name.endsWith(".json") -> "application/json; charset=utf-8"
        name.endsWith(".webmanifest") -> "application/manifest+json"
        name.endsWith(".woff2") -> "font/woff2"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".ico") -> "image/x-icon"
        name.endsWith(".txt") -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }
}
```

- [ ] **Step 7: Wire the Gradle asset-copy task**

In `android_tor_spike/app/android/app/build.gradle`, first CONFIRM the path from `rootProject.projectDir` (= `android_tor_spike/app/android`) to the repo's `hearth/web`. From that dir, `../../../hearth/web` resolves to `<repo>/hearth/web` (android → app → android_tor_spike → repo → hearth/web). Add near the top of the file (after the `apply`/`plugins` block):

```gradle
// vp1: single source of truth for the WebView shell's UI. Copy hearth/web into
// the APK assets at build time so the bundle is never duplicated into the RN
// tree. `../../../hearth/web` is relative to android_tor_spike/app/android.
def hearthWebDir = new File(rootProject.projectDir, "../../../hearth/web")
tasks.register('copyHearthWeb', Copy) {
    from hearthWebDir
    into "${projectDir}/src/main/assets/www"
}
preBuild.dependsOn 'copyHearthWeb'
```

- [ ] **Step 8: Confirm the copy resolves**

Run (from `android_tor_spike/app/android`): `./gradlew :app:copyHearthWeb`
Then verify (from repo root): `ls android_tor_spike/app/android/app/src/main/assets/www/index.html android_tor_spike/app/android/app/src/main/assets/www/app.js android_tor_spike/app/android/app/src/main/assets/www/style.css android_tor_spike/app/android/app/src/main/assets/www/sw.js`
Expected: all four listed. If `copyHearthWeb` reports "NO-SOURCE", the relative path is wrong — adjust `../../../hearth/web` (list `hearthWebDir.canonicalPath` from a throwaway `println` in the task) until it resolves, then rerun.

- [ ] **Step 9: Gitignore the copied bundle**

Append to `android_tor_spike/app/.gitignore` (create the file if it does not exist):

```
# vp1: hearth/web is copied in at build time (single source of truth) — never commit the copy
/android/app/src/main/assets/www/
```

- [ ] **Step 10: Write the Expo config plugin (prebuild resilience)**

`android/` is Expo continuous-native-generation output; `expo prebuild --clean` regenerates it and would drop the `copyHearthWeb` edit. Codify it. Create `android_tor_spike/app/plugins/withHearthWebAssets.js`:

```javascript
const { withAppBuildGradle } = require("@expo/config-plugins");

// vp1: re-inject the copyHearthWeb Gradle task after `expo prebuild --clean`
// regenerates android/. Idempotent: skips if the marker is already present.
const MARKER = "vp1:copyHearthWeb";
const SNIPPET = `
// ${MARKER} — single source of truth for the WebView shell UI (see withHearthWebAssets.js)
def hearthWebDir = new File(rootProject.projectDir, "../../../hearth/web")
tasks.register('copyHearthWeb', Copy) {
    from hearthWebDir
    into "\${projectDir}/src/main/assets/www"
}
preBuild.dependsOn 'copyHearthWeb'
`;

module.exports = function withHearthWebAssets(config) {
  return withAppBuildGradle(config, (cfg) => {
    if (!cfg.modResults.contents.includes(MARKER)) {
      cfg.modResults.contents += SNIPPET;
    }
    return cfg;
  });
};
```

Register it in `android_tor_spike/app/app.json` — add `"./plugins/withHearthWebAssets"` to the `expo.plugins` array (create the array if absent). Read `app.json` first to confirm the existing `plugins` shape, then add the entry without disturbing other plugins.

- [ ] **Step 11: Wire `ensureLocalServer` / `teardownLocalServer` / `getWebUrl` in `TorManagerModule.kt`**

Add a field next to the existing `@Volatile private var mediaServer: MediaServer? = null` (around `TorManagerModule.kt:91`):

```kotlin
    // vp1: the loopback web server the WebView loads. Same lazy-construct /
    // shared-`destroyed`-flag lifecycle as mediaServer (see ensureMediaServer's
    // doc). One instance per module; torn down in OnDestroy so its accept-loop
    // daemon thread never outlives the module.
    @Volatile private var localServer: LocalWebServer? = null
```

Add these two methods next to `ensureMediaServer` / `teardownMediaServer` (around `TorManagerModule.kt:169`–`184`), reusing the SAME `destroyed` flag and `this` monitor:

```kotlin
    /** vp1: lazily starts (once) the WebView shell's loopback server, mirroring
     *  ensureMediaServer's lifecycle exactly (shared `destroyed` flag + `this`
     *  monitor close the same construct/destroy race). The assets provider
     *  reads the bundled hearth/web from assets/www; the api provider is
     *  LocalApi (read-only /api/*). Returns null once the module is destroyed. */
    @Synchronized
    private fun ensureLocalServer(ctx: android.content.Context): LocalWebServer? {
        if (destroyed) return null
        localServer?.let { return it }
        val api = LocalApi(ctx)
        val s = LocalWebServer(
            assets = { path -> LocalAssets.provide(ctx, path) },
            api = { method, path, _, _, _ -> api.handle(method, path) },
        )
        s.start()
        localServer = s
        return s
    }

    @Synchronized
    private fun teardownLocalServer() {
        destroyed = true
        localServer?.stop()
        localServer = null
    }
```

In the existing `OnDestroy { … }` block (which already calls `teardownMediaServer()`), add `teardownLocalServer()` on the next line.

Add the `getWebUrl` AsyncFunction next to `getVideoUrl` (around `TorManagerModule.kt:556`):

```kotlin
        // vp1: the full loopback URL (with one-time query token) the WebView
        // loads. Starts the server on first call (lazy), null once destroyed.
        AsyncFunction("getWebUrl") {
            val ctx = appContext.reactContext ?: return@AsyncFunction null
            ensureLocalServer(ctx)?.rootUrl()
        }.runOnQueue(ioScope)
```

- [ ] **Step 12: Verify the whole module + tests compile and pass**

Run: `./gradlew :tor-manager:testDebugUnitTest`
Expected: PASS — the full existing JVM suite plus `LocalWebServerTest` and `LocalApiTest`, no compile errors.

- [ ] **Step 13: Commit**

```bash
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalAssets.kt \
        android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/TorManagerModule.kt \
        android_tor_spike/app/android/app/build.gradle \
        android_tor_spike/app/plugins/withHearthWebAssets.js \
        android_tor_spike/app/app.json \
        android_tor_spike/app/.gitignore
git commit -m "feat(vp1): bundle hearth/web assets and serve shell over loopback server"
```

---

## Task 3: WebView screen (on-device checkpoint)

**Files:**
- Create: `android_tor_spike/app/WebShell.tsx`
- Modify: `android_tor_spike/app/index.ts`
- Modify: `android_tor_spike/app/modules/tor-manager/index.ts`
- Modify: `android_tor_spike/app/package.json` (via `npx expo install`, do not hand-edit the version)

**Interfaces:**
- Consumes: `getWebUrl` AsyncFunction (Task 2); `bootstrap()`, `startNode()` from `modules/tor-manager` (existing exports).
- Produces: `getWebUrl(): Promise<string | null>` export; `WebShell` default-export React component registered as the app root.

- [ ] **Step 1: Confirm the react-native-webview version + API, then install**

Read https://docs.expo.dev/versions/v57.0.0/ (the `react-native-webview` entry) and CONFIRM: (a) the Expo-57-compatible version that `npx expo install react-native-webview` selects; (b) that the `WebView` props used below still exist and are spelled as written — `source={{uri}}`, `sharedCookiesEnabled`, `thirdPartyCookiesEnabled`, `javaScriptEnabled`, `domStorageEnabled`, `originWhitelist`, `mediaPlaybackRequiresUserAction`, `allowsInlineMediaPlayback`, `onShouldStartLoadWithRequest`. If any prop was renamed/removed in the Expo-57 version, use the documented equivalent (cookies + inline media playback + navigation-gating are the load-bearing capabilities — keep those working; a cosmetic prop can be dropped).

Then install (from `android_tor_spike/app`): `npx expo install react-native-webview`
Expected: `package.json` gains a `react-native-webview` dependency at the Expo-57-selected version. Do NOT run `expo prebuild --clean` (it drops the hand-applied NSC/manifest edits and the `copyHearthWeb` task; the config plugin from Task 2 protects the copy task, but avoid `--clean` this slice).

- [ ] **Step 2: Export `getWebUrl` from the module surface**

In `android_tor_spike/app/modules/tor-manager/index.ts`, locate the native-module handle (the `requireNativeModule("TorManager")` result — confirm its local variable name; the existing `getVideoUrl` export shows the pattern) and add next to `getVideoUrl`:

```ts
export function getWebUrl(): Promise<string | null> {
  return TorManager.getWebUrl();
}
```

(Use the same handle name the file already uses for `getVideoUrl` — do not introduce a new `requireNativeModule` call.)

- [ ] **Step 3: Write the WebView host component**

Create `android_tor_spike/app/WebShell.tsx`:

```tsx
import React, { useEffect, useState } from "react";
import { ActivityIndicator, StyleSheet, Text, View } from "react-native";
import { WebView } from "react-native-webview";
import { bootstrap, startNode, syncNow, getWebUrl } from "./modules/tor-manager";

/** vp1: full-screen host for the desktop web UI, served by the native loopback
 *  server. For slice 1 this component also owns the engine bootstrap (Tor +
 *  node) so a fresh launch has content to render; background sync (Brick C)
 *  keeps it current. The WebView loads getWebUrl() — the one-time-token URL —
 *  with cookies enabled so app.js's same-origin /api fetches authenticate. */
export default function WebShell() {
  const [uri, setUri] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      try {
        await bootstrap();          // start Tor
        startNode();                // start the background node (Brick C sync)
        syncNow();                  // vp1: kick an immediate foreground sync so a
                                    // fresh launch has current content to render
                                    // (do not await — fire-and-forget; the feed
                                    // reflects the last successful sync, and the
                                    // 15-min background cycle keeps it current).
        const url = await getWebUrl();
        if (!url) { setErr("web server not available"); return; }
        setUri(url);
      } catch (e: any) {
        setErr(String(e?.message ?? e));
      }
    })();
  }, []);

  if (err) return (<View style={styles.center}><Text>{err}</Text></View>);
  if (!uri) return (<View style={styles.center}><ActivityIndicator size="large" /></View>);

  return (
    <WebView
      source={{ uri }}
      style={styles.web}
      originWhitelist={["http://127.0.0.1*"]}
      sharedCookiesEnabled
      thirdPartyCookiesEnabled
      javaScriptEnabled
      domStorageEnabled
      mediaPlaybackRequiresUserAction={false}
      allowsInlineMediaPlayback
      onShouldStartLoadWithRequest={(req) => req.url.startsWith("http://127.0.0.1")}
    />
  );
}

const styles = StyleSheet.create({
  web: { flex: 1 },
  center: { flex: 1, alignItems: "center", justifyContent: "center" },
});
```

- [ ] **Step 4: Register `WebShell` as the app root**

Replace `android_tor_spike/app/index.ts` contents with:

```ts
import { registerRootComponent } from "expo";

import WebShell from "./WebShell";

// vp1: the phone's UI is now the desktop web app in a WebView (WebShell owns
// the engine lifecycle for slice 1). App.tsx (the old dev dashboard) is left in
// the tree, unregistered, for reference / engine-control fallback.
registerRootComponent(WebShell);
```

- [ ] **Step 5: Typecheck**

Run (from `android_tor_spike/app`): `npx tsc --noEmit`
Expected: no errors. If `react-native-webview` types are missing, confirm the package installed and its types resolve; the Expo-57 version ships its own types (no separate `@types` needed).

- [ ] **Step 6: On-device checkpoint (RELEASE apk)**

Build + install the RELEASE apk (from `android_tor_spike/app/android`): `./gradlew :app:assembleRelease`, then install `app/build/outputs/apk/release/app-release.apk` on the G20 (`adb install -r …`). Do NOT install a debug build (field lesson: a debug build yields "Unable to load script"). Launch the app.
Expected: the real Kreds shell renders in the WebView — the desktop nav/chrome and an (empty, if not yet synced) journal — served entirely from the phone loopback server. This proves the novel architecture before any feed marshaling. If the page is blank, check `adb logcat` for CSP violations or a 403 (token/cookie) and reconcile against Task 1's cookie flow.

- [ ] **Step 7: Commit**

```bash
git add android_tor_spike/app/WebShell.tsx android_tor_spike/app/index.ts \
        android_tor_spike/app/modules/tor-manager/index.ts android_tor_spike/app/package.json
git commit -m "feat(vp1): full-screen webview host loading the loopback shell"
```

---

## Task 4: `/api/feed` marshaling

**Files:**
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/DecryptPass.kt`
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/KotlinResponses.kt`
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalApi.kt`
- Test: `android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/LocalApiTest.kt`

**Interfaces:**
- Consumes: `DecryptPass.Decrypted` (extended), `KotlinResponses.Responses`/`Comment` (extended); `DecryptPass.run(store, phoneDevicePub, encPrivHex, ownIdentityPub): Result`; `DecryptPass.responsesPass(...): Map<String, KotlinResponses.Responses>`; `EncKeys.getOrCreate(store): Pair<String,String>`.
- Produces: `LocalApi.feedRow(d: DecryptPass.Decrypted, ownIdentityPub: String, responses: KotlinResponses.Responses?): JSONObject` (companion, pure). `/api/feed` route in `handle`.

- [ ] **Step 1: Write the failing golden-shape test**

Add to `LocalApiTest.kt`:

```kotlin
    private fun sampleDecrypted(mine: Boolean) = DecryptPass.Decrypted(
        msgId = "m1", kind = "post", author = "Cara", text = "hello",
        createdAt = 1784568399.5, blobs = listOf("b1"), thumbs = listOf<String?>("t1"),
        media = "photo", poster = null, storyRefMediaHash = null,
        identityPub = if (mine) "own" else "cara", scope = "kreds",
        expiresAt = null, placement = "journal", codec = null)

    @Test fun feedRowMatchesHearthFieldSet() {
        val o = LocalApi.feedRow(sampleDecrypted(mine = true), ownIdentityPub = "own", responses = null)
        val keys = o.keys().asSequence().toSet()
        val expected = setOf(
            "msg_id", "identity_pub", "author_name", "author_avatar", "text", "blobs",
            "scope", "created_at", "expires_at", "mine", "placement", "media",
            "poster", "codec", "thumbs", "responses")
        assertEquals(expected, keys)
        assertEquals("m1", o.getString("msg_id"))
        assertEquals("own", o.getString("identity_pub"))
        assertEquals("Cara", o.getString("author_name"))
        assertTrue(o.isNull("author_avatar"))
        assertEquals("kreds", o.getString("scope"))
        assertEquals("journal", o.getString("placement"))
        assertTrue(o.getBoolean("mine"))
        assertTrue(o.isNull("responses"))
        assertEquals("b1", o.getJSONArray("blobs").getString(0))
        assertEquals("t1", o.getJSONArray("thumbs").getString(0))
    }

    @Test fun feedRowMineIsFalseForOtherAuthor() {
        val o = LocalApi.feedRow(sampleDecrypted(mine = false), ownIdentityPub = "own", responses = null)
        assertFalse(o.getBoolean("mine"))
        assertEquals("cara", o.getString("identity_pub"))
    }

    @Test fun feedRowResponsesShape() {
        val resp = KotlinResponses.Responses(
            reactions = linkedMapOf("heart" to 2),
            comments = listOf(
                KotlinResponses.Comment(
                    body = "nice", display = "Quiet Fox", aliasColor = 180, createdAt = 1.0,
                    alias = true, aliasSeed = "aabbccdd", name = null)))
        val o = LocalApi.feedRow(sampleDecrypted(mine = false), ownIdentityPub = "own", responses = resp)
        val r = o.getJSONObject("responses")
        assertEquals(setOf("reactions", "my_reaction", "comments", "can_moderate"), r.keys().asSequence().toSet())
        assertTrue(r.isNull("my_reaction"))
        assertFalse(r.getBoolean("can_moderate"))
        assertEquals(2, r.getJSONObject("reactions").getInt("heart"))
        val c = r.getJSONArray("comments").getJSONObject(0)
        assertEquals(setOf("name", "avatar", "alias", "alias_seed", "mine", "body", "created_at"),
            c.keys().asSequence().toSet())
        assertTrue(c.isNull("name"))          // alias == true -> name null
        assertTrue(c.isNull("avatar"))
        assertTrue(c.getBoolean("alias"))
        assertEquals("aabbccdd", c.getString("alias_seed"))
        assertFalse(c.getBoolean("mine"))
        assertEquals("nice", c.getString("body"))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.LocalApiTest"`
Expected: FAIL / compile error — `DecryptPass.Decrypted` has no `identityPub`/`scope`/… params, `KotlinResponses.Comment` has no `alias`/`aliasSeed`/`name`, `LocalApi.feedRow` is unresolved.

- [ ] **Step 3: Extend `DecryptPass.Decrypted` additively**

In `DecryptPass.kt`, change the `Decrypted` data class (currently ending `…val storyRefMediaHash: String?)`) to append five defaulted fields:

```kotlin
        val storyRefMediaHash: String?,
        // vp1 (additive): hearth's /api/feed shape carries these; the native
        // getFeed marshal reads Decrypted by name and ignores them, so adding
        // them is safe. identityPub = the message author's cert.identity_pub
        // (StoredMsg.identityPub) — used for `mine` + `identity_pub`. scope/
        // expires_at/placement/codec ride in the plaintext OUTER payload
        // (messages.make_post signs them there). Defaults keep incidental
        // constructions (e.g. tests) compiling; decryptOne always sets them.
        val identityPub: String = "",
        val scope: String? = null,
        val expiresAt: Double? = null,
        val placement: String? = null,
        val codec: String? = null)
```

In `decryptOne`, at the return site (currently `return Decrypted(m.msgId, m.kind, author, text, createdAt, blobs, thumbs, media, poster, storyRefMediaHash) to key`), read the new outer-payload fields and pass them:

```kotlin
        val scopeField = p["scope"] as? String
        val expiresAt = (p["expires_at"] as? Number)?.toDouble()
        val placement = p["placement"] as? String
        val codec = p["codec"] as? String
        return Decrypted(
            m.msgId, m.kind, author, text, createdAt, blobs, thumbs, media, poster, storyRefMediaHash,
            m.identityPub, scopeField, expiresAt, placement, codec) to key
```

- [ ] **Step 4: Extend `KotlinResponses.Comment` additively**

In `KotlinResponses.kt`, change the `Comment` data class to append three defaulted fields:

```kotlin
    data class Comment(
        val body: String, val display: String, val aliasColor: Int?, val createdAt: Double,
        // vp1 (additive): let the /api/feed marshal reproduce hearth's comment
        // shape. alias == true when this comment rendered as an anonymous alias
        // (aliasColor != null is the "not a verified real name" signal, per
        // this class's own doc). aliasSeed is the entry's validated hex32 seed.
        // name = the resolved real display name, or null when aliased.
        val alias: Boolean = false, val aliasSeed: String = "", val name: String? = null)
```

In `aggregate`, in the comment branch (`} else { … }`), populate the new fields from what `resolveDisplay` already computed:

```kotlin
            } else {
                val (display, color) = resolveDisplay(e, target, profileNames, deviceBound)
                val isAlias = color != null           // a null aliasColor == verified real name
                val name = if (isAlias) null else display
                val seed = str(e, "alias_seed") ?: ""
                comments.add(Comment(body, display, color, createdAt, isAlias, seed, name))
            }
```

- [ ] **Step 5: Update existing tests that construct `Decrypted`/`Comment` for equality**

Read `DecryptPassTest.kt` and `KotlinResponsesTest.kt`. If either asserts equality against a hand-built `Decrypted(...)` or `Comment(...)` (rather than reading fields off the result), update those expected objects: for a decrypted post, the new trailing args are `identityPub = <the message's author identity_pub>, scope = <the post's scope>, expiresAt = <or null>, placement = <"journal" unless the fixture set it>, codec = <or null>`; for a resolved comment, `alias = <aliasColor != null>, aliasSeed = <the entry's alias_seed>, name = <resolved name or null>`. If the tests only read fields (no whole-object `assertEquals`), no change is needed.

- [ ] **Step 6: Write `LocalApi.feedRow` + the `/api/feed` route**

In `LocalApi.kt` companion, add the pure marshal helpers:

```kotlin
        fun feedRow(d: DecryptPass.Decrypted, ownIdentityPub: String, responses: KotlinResponses.Responses?): JSONObject {
            val blobs = JSONArray(); d.blobs.forEach { blobs.put(it) }
            val thumbs: Any = if (d.thumbs.isEmpty()) JSONObject.NULL
                else JSONArray().also { arr -> d.thumbs.forEach { arr.put(it ?: JSONObject.NULL) } }
            return JSONObject()
                .put("msg_id", d.msgId)
                .put("identity_pub", d.identityPub)
                .put("author_name", d.author)
                .put("author_avatar", JSONObject.NULL)        // avatars deferred (slice 1)
                .put("text", d.text)
                .put("blobs", blobs)
                .put("scope", d.scope ?: JSONObject.NULL)
                .put("created_at", d.createdAt)
                .put("expires_at", d.expiresAt ?: JSONObject.NULL)
                .put("mine", d.identityPub == ownIdentityPub)
                .put("placement", d.placement ?: "journal")
                .put("media", d.media)
                .put("poster", d.poster ?: JSONObject.NULL)
                .put("codec", d.codec ?: JSONObject.NULL)
                .put("thumbs", thumbs)
                .put("responses", responsesJson(responses))
        }

        private fun responsesJson(r: KotlinResponses.Responses?): Any {
            if (r == null) return JSONObject.NULL
            val reactions = JSONObject()
            for ((k, v) in r.reactions) reactions.put(k, v)
            val comments = JSONArray()
            for (c in r.comments) {
                comments.put(JSONObject()
                    .put("name", if (c.alias) JSONObject.NULL else (c.name ?: JSONObject.NULL))
                    .put("avatar", JSONObject.NULL)           // comment-author avatars deferred
                    .put("alias", c.alias)
                    .put("alias_seed", c.aliasSeed)
                    .put("mine", false)                        // read-only
                    .put("body", c.body)
                    .put("created_at", c.createdAt))
                    // NOTE: `responder` deliberately OMITTED (hearth omits it when unresolved)
            }
            return JSONObject()
                .put("reactions", reactions)
                .put("my_reaction", JSONObject.NULL)          // read-only
                .put("comments", comments)
                .put("can_moderate", false)                    // read-only
        }
```

Add a per-instance content-key cache field just below the `class LocalApi(private val ctx: Context) {` line (this `LocalApi` is one long-lived instance held by `ensureLocalServer`, so the cache persists across requests — mirrors the module's own `@Volatile blobKeys` pattern; in-memory only, never persisted, so decrypt-on-read holds):

```kotlin
    // vp1: msgId -> content key, populated by the /api/feed decrypt pass and
    // reused by /api/post-blob so an image request does NOT re-run a full
    // DecryptPass.run over every message (which would be O(images x messages)).
    // In-memory only; refreshed each /api/feed; postBlob falls back to a fresh
    // run if a hash is requested before any feed() populated the cache.
    @Volatile private var keysCache: Map<String, ByteArray> = emptyMap()
```

Add the instance orchestration method (it refreshes `keysCache` from the pass it already runs):

```kotlin
    private fun feed(): String {
        val fx = fixtureOrNull() ?: return "[]"
        val store = SqliteSyncStore(ctx)
        val (priv, _) = EncKeys.getOrCreate(store)
        val own = fx.cert.identity_pub
        val res = DecryptPass.run(store, fx.device_pub, priv, own)
        keysCache = res.keys                                   // vp1: warm the blob-key cache
        val responses = DecryptPass.responsesPass(store, fx.device_pub, priv, own)
        val arr = JSONArray()
        for (d in res.feed) {                                  // already newest-first
            if (d.kind != "post") continue                     // journal feed = posts only
            if ((d.placement ?: "journal") != "journal") continue
            arr.put(feedRow(d, own, responses[d.msgId]))
        }
        return arr.toString()
    }
```

Add `"/api/feed" -> json(feed())` to the `when (path)` in `handle`.

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :tor-manager:testDebugUnitTest`
Expected: PASS — the new `feedRow*` tests plus the full existing suite (confirm `DecryptPassTest`/`KotlinResponsesTest` still green after Step 5).

- [ ] **Step 8: Commit**

```bash
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/DecryptPass.kt \
        android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/KotlinResponses.kt \
        android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalApi.kt \
        android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/LocalApiTest.kt
git commit -m "feat(vp1): marshal /api/feed into hearth snake_case journal shape"
```

---

## Task 5: `/api/stories` + `/api/blob` + `/api/post-blob`

**Files:**
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalApi.kt`
- Test: `android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/LocalApiTest.kt`

**Interfaces:**
- Consumes: `SqliteSyncStore.activeStories(nowSeconds: Double): List<StoredStory>` where `StoredStory(msgId, author, mediaKind, media, poster, caption, createdAt)`; `store.getBlob(hash): ByteArray?`; `KotlinBlobCrypt.decryptBlob(contentKey, cipher): ByteArray?`; `DecryptPass.run(...).keys: Map<String, ByteArray>`.
- Produces (companion, pure): `LocalApi.storiesJson(stories: List<StoredStory>, profileNames: Map<String,String>, ownIdentityPub: String): String`; `LocalApi.sniff(data: ByteArray): String`. Routes `/api/stories`, `/api/blob/{h}`, `/api/post-blob/{msg_id}/{h}` in `handle`.

- [ ] **Step 1: Confirm the `/api/stories` shape against hearth, THEN write the failing tests**

FIRST confirm the real shape — the grouped shape below is inferred, and if it's wrong the stories strip silently fails to render. Read `hearth/api.py` (`GET /api/stories`, ~line 698) into `hearth/node.py`'s `stories()` builder and the web consumer `renderStories()`/the stories-strip code in `hearth/web/app.js`. Verify: (a) is the response grouped-by-author (`[{identity_pub, items:[...], mine, name, avatar}]`) or a flat list of story objects? (b) the exact per-item/per-group field names (snake_case) and the sort (self-first? newest-first?). If hearth's actual shape differs from the grouped shape encoded in the test below and the `storiesJson` implementation, **use hearth's real shape** in BOTH — the goldens exist to match hearth, not the other way round. Then add these tests (adjusting field names/structure to the confirmed shape):

```kotlin
    @Test fun sniffMagicBytes() {
        assertEquals("image/png", LocalApi.sniff(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0)))
        assertEquals("image/jpeg", LocalApi.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0)))
        assertEquals("image/gif", LocalApi.sniff("GIF89a".toByteArray()))
        assertEquals("image/webp", LocalApi.sniff("RIFF....WEBP".toByteArray()))
        assertEquals("application/octet-stream", LocalApi.sniff("zzzz".toByteArray()))
    }

    @Test fun sniffFtypAvifVsMp4() {
        // bytes[4:8] == "ftyp"; bytes[8:12] brand decides avif vs mp4
        val avif = ByteArray(16); "xxxx".toByteArray().copyInto(avif, 0)
        "ftyp".toByteArray().copyInto(avif, 4); "avif".toByteArray().copyInto(avif, 8)
        assertEquals("image/avif", LocalApi.sniff(avif))
        val mp4 = ByteArray(16); "xxxx".toByteArray().copyInto(mp4, 0)
        "ftyp".toByteArray().copyInto(mp4, 4); "isom".toByteArray().copyInto(mp4, 8)
        assertEquals("video/mp4", LocalApi.sniff(mp4))
    }

    @Test fun storiesJsonGroupsByAuthorSelfFirst() {
        val stories = listOf(
            StoredStory("s1", "cara", "photo", "h1", null, "cap1", 10.0),
            StoredStory("s2", "own", "video", "h2", "p2", "cap2", 20.0),
            StoredStory("s3", "cara", "photo", "h3", null, "cap3", 30.0))
        val json = LocalApi.storiesJson(stories, mapOf("cara" to "Cara"), ownIdentityPub = "own")
        val arr = org.json.JSONArray(json)
        // self ("own") first
        assertEquals("own", arr.getJSONObject(0).getString("identity_pub"))
        assertTrue(arr.getJSONObject(0).getBoolean("mine"))
        val cara = arr.getJSONObject(1)
        assertEquals("cara", cara.getString("identity_pub"))
        assertFalse(cara.getBoolean("mine"))
        assertEquals("Cara", cara.getString("name"))
        assertTrue(cara.isNull("avatar"))
        // items newest-first within a group; each item carries hearth's keys
        val items = cara.getJSONArray("items")
        assertEquals("s3", items.getJSONObject(0).getString("msg_id"))
        assertEquals(setOf("msg_id", "media_kind", "media", "poster", "caption", "created_at"),
            items.getJSONObject(0).keys().asSequence().toSet())
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.LocalApiTest"`
Expected: FAIL — `LocalApi.sniff` and `LocalApi.storiesJson` unresolved.

- [ ] **Step 3: Implement `sniff`, `storiesJson`, and blob/stories routes**

In `LocalApi.kt` companion, add:

```kotlin
        /** Kotlin port of hearth api.py `_sniff` (magic-byte content-type). */
        fun sniff(data: ByteArray): String {
            fun startsWith(prefix: ByteArray): Boolean {
                if (data.size < prefix.size) return false
                for (i in prefix.indices) if (data[i] != prefix[i]) return false
                return true
            }
            if (startsWith(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))) return "image/png"
            if (startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))) return "image/jpeg"
            if (startsWith("GIF8".toByteArray(Charsets.ISO_8859_1))) return "image/gif"
            if (startsWith("RIFF".toByteArray(Charsets.ISO_8859_1))) return "image/webp"
            if (data.size >= 12 && String(data, 4, 4, Charsets.ISO_8859_1) == "ftyp") {
                val brand = String(data, 8, 4, Charsets.ISO_8859_1)
                if (brand == "avif" || brand == "avis") return "image/avif"
                return "video/mp4"
            }
            return "application/octet-stream"
        }

        /** hearth /api/stories: one group per author, self-first then last-
         *  created-at desc; items newest-first. Avatars deferred (null). */
        fun storiesJson(stories: List<StoredStory>, profileNames: Map<String, String>, ownIdentityPub: String): String {
            val groups = linkedMapOf<String, MutableList<StoredStory>>()
            for (s in stories) groups.getOrPut(s.author) { mutableListOf() }.add(s)
            data class G(val mine: Boolean, val last: Double, val obj: JSONObject)
            val built = groups.map { (ipub, items) ->
                val itemsArr = JSONArray()
                for (it in items.sortedByDescending { it.createdAt }) {
                    itemsArr.put(JSONObject()
                        .put("msg_id", it.msgId)
                        .put("media_kind", it.mediaKind)
                        .put("media", it.media)
                        .put("poster", it.poster ?: JSONObject.NULL)
                        .put("caption", it.caption)
                        .put("created_at", it.createdAt))
                }
                val mine = ipub == ownIdentityPub
                G(mine, items.maxOf { it.createdAt }, JSONObject()
                    .put("identity_pub", ipub)
                    .put("items", itemsArr)
                    .put("mine", mine)
                    .put("name", profileNames[ipub] ?: ipub.take(8))
                    .put("avatar", JSONObject.NULL))
            }.sortedWith(compareByDescending<G> { it.mine }.thenByDescending { it.last })
            val out = JSONArray()
            for (g in built) out.put(g.obj)
            return out.toString()
        }
```

Add instance methods:

```kotlin
    private fun stories(): String {
        val fx = fixtureOrNull()
        val store = SqliteSyncStore(ctx)
        val own = fx?.cert?.identity_pub ?: ""
        val now = System.currentTimeMillis() / 1000.0
        return storiesJson(store.activeStories(now), store.profileNames(), own)
    }

    /** /api/blob/{h}: raw plaintext-at-rest bytes (avatars/plaintext content),
     *  NO content-key decrypt — mirrors getStoryImage's decrypt-skipping path. */
    private fun blob(hash: String): HttpResponse {
        val data = SqliteSyncStore(ctx).getBlob(hash) ?: return notFound()
        return mediaResponse(data)
    }

    /** /api/post-blob/{msg_id}/{h}: content-key-decrypted post/DM blob bytes,
     *  streamed decrypt-on-read. AVIF is served raw (image/avif) — the WebView
     *  Chromium renderer decodes it (it never has our keys; we hand it already-
     *  decrypted plaintext), matching desktop exactly. */
    private fun postBlob(msgId: String, hash: String): HttpResponse {
        val store = SqliteSyncStore(ctx)
        // vp1: use the cache warmed by /api/feed; only fall back to a full
        // DecryptPass.run if this blob's key isn't cached yet (first paint before
        // any feed(), or a key that aged out).
        var key = keysCache[msgId]
        if (key == null) {
            val fx = fixtureOrNull() ?: return notFound()
            val (priv, _) = EncKeys.getOrCreate(store)
            val res = DecryptPass.run(store, fx.device_pub, priv, fx.cert.identity_pub)
            keysCache = res.keys
            key = res.keys[msgId] ?: return notFound()
        }
        val cipher = store.getBlob(hash) ?: return notFound()
        val plain = KotlinBlobCrypt.decryptBlob(key, cipher) ?: return notFound()
        return mediaResponse(plain)
    }

    private fun mediaResponse(bytes: ByteArray) = HttpResponse(200, mapOf(
        "Content-Type" to sniff(bytes),
        "X-Content-Type-Options" to "nosniff",
        "Cache-Control" to "private, max-age=31536000, immutable"), bytes)

    private fun notFound() = HttpResponse(404, mapOf("Content-Type" to "text/plain"), "not found".toByteArray())
```

`sniff` and `mediaResponse` reference each other across companion/instance — keep `sniff` in the companion and call it from the instance as `sniff(bytes)` (companion members are visible unqualified inside the class). Extend the `handle` dispatch to cover the new routes:

```kotlin
    fun handle(method: String, path: String): HttpResponse? {
        if (method != "GET") return null
        return when {
            path == "/api/bootstrap" -> json(bootstrapJson())
            path == "/api/applock" -> json(applockJson())
            path == "/api/state" -> json(state())
            path == "/api/feed" -> json(feed())
            path == "/api/stories" -> json(stories())
            path.startsWith("/api/post-blob/") -> {
                val seg = path.removePrefix("/api/post-blob/").split("/")
                if (seg.size != 2 || seg[0].isEmpty() || seg[1].isEmpty()) notFound() else postBlob(seg[0], seg[1])
            }
            path.startsWith("/api/blob/") -> {
                val h = path.removePrefix("/api/blob/")
                if (h.isEmpty() || h.contains("/")) notFound() else blob(h)
            }
            else -> null
        }
    }
```

(Note: `handle` now returns an explicit 404 `HttpResponse` for malformed blob paths — that is a fail-closed answer, not a `null`; `null` still means "no such route" which `LocalWebServer` also renders as 404.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :tor-manager:testDebugUnitTest`
Expected: PASS — new `sniff*`/`storiesJson*` tests plus the full existing suite. (The blob decrypt + range + AVIF paths are exercised on-device in Task 7, since they need a real store + WebView renderer.)

- [ ] **Step 5: Commit**

```bash
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalApi.kt \
        android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/LocalApiTest.kt
git commit -m "feat(vp1): serve stories and blob/post-blob media with sniff and range"
```

---

## Task 6: Read-only seam (shared UI)

**Files:**
- Modify: `hearth/web/app.js` (one line)
- Modify: `hearth/web/style.css`
- Test: `android_tor_spike/app/test/web-readonly-seam.test.ts` (vitest, static guard)

**Interfaces:**
- Consumes: `/api/state` `"readonly": true` (already emitted by `LocalApi.stateJson`, Task 2).
- Produces: `body.readonly` class toggled in `app.js`; `body.readonly` hide rules in `style.css`. Desktop's node omits `readonly` → toggle false → unaffected.

- [ ] **Step 1: Discover the exact write-affordance selectors**

Run (from repo root) to confirm class names before editing:
`grep -n "Say something\|comment-composer\|rx-open\|rx-picker\|rx-count-chip\|pact del\|settings-del\|story-ring add\|story-tile" hearth/web/app.js hearth/web/index.html hearth/web/style.css`
Confirm these (as of the current bundle — adjust the CSS below if a name differs): journal + wall composers share class `.composer` (journal form is `<form class="composer" id="composer">`, wall is `.composer.profile-composer`); comment input form is `.comment-composer`; the reaction-add opener is `.rx-open` and the read-only count chips are `.rx-count-chip` (KEEP these); the six-glyph picker container expands from `.rx-open` — confirm its class (search `rx-` around app.js:545–575; commonly `.rx-picker`); "Delete everywhere" buttons are `.pact.del` (journal) and `.settings-del` (settings menu); the story-add tile is a `.story-tile` whose ring is `.story-ring.add`.

- [ ] **Step 2: Write the vitest static guard (failing)**

Create `android_tor_spike/app/test/web-readonly-seam.test.ts`:

```ts
import { readFileSync } from "fs";
import { resolve } from "path";
import { describe, it, expect } from "vitest";

// hearth/web is at the repo root, two levels up from android_tor_spike/app.
const web = (f: string) => readFileSync(resolve(__dirname, "../../../hearth/web", f), "utf8");

describe("vp1 read-only seam", () => {
  it("app.js toggles body.readonly from STATE.readonly", () => {
    const js = web("app.js");
    expect(js).toMatch(/classList\.toggle\(\s*["']readonly["']\s*,\s*!!STATE\.readonly\s*\)/);
  });

  it("style.css hides write affordances but keeps read chips", () => {
    const css = web("style.css");
    expect(css).toMatch(/body\.readonly/);
    // the composers, comment input, reaction opener, delete, and story-add are hidden
    expect(css).toMatch(/body\.readonly\s+\.composer/);
    expect(css).toMatch(/body\.readonly\s+\.comment-composer/);
    expect(css).toMatch(/body\.readonly\s+\.rx-open/);
    // the read-only count chips are NOT hidden by a body.readonly rule
    expect(css).not.toMatch(/body\.readonly\s+\.rx-count-chip\s*\{[^}]*display\s*:\s*none/);
  });
});
```

- [ ] **Step 3: Run the guard to verify it fails**

Run (from `android_tor_spike/app`): `npx vitest run test/web-readonly-seam.test.ts`
Expected: FAIL — the toggle line and `body.readonly` rules do not exist yet.

- [ ] **Step 4: Add the `body.readonly` toggle in `app.js`**

In `hearth/web/app.js`, in `refresh()`, immediately after the line `STATE = await j("/api/state");` (currently app.js:4756), insert:

```javascript
  // vp1: read-only mirror seam. A phone's /api/state returns readonly:true; the
  // desktop node omits the field (=> falsy => desktop never read-only). This is
  // the single toggle the future outbound slice flips off to re-enable writes.
  document.body.classList.toggle("readonly", !!STATE.readonly);
```

- [ ] **Step 5: Add the `body.readonly` hide rules in `style.css`**

Append to `hearth/web/style.css` (correcting any selector name per Step 1's grep; the picker selector may be `.rx-picker` — use the confirmed class):

```css
/* vp1: read-only mirror. Hide every write affordance while keeping read content
   (reaction count chips, comments, posts) fully visible. Desktop never sets
   body.readonly, so this is inert there. */
body.readonly .composer,
body.readonly .comment-composer,
body.readonly .rx-open,
body.readonly .rx-picker,
body.readonly .pact.del,
body.readonly .settings-del,
body.readonly .story-tile .story-ring.add {
  display: none !important;
}
/* keep the story-add tile's label from leaving an empty column */
body.readonly .story-tile:has(.story-ring.add) {
  display: none !important;
}
```

If the target WebView's Chromium does not support `:has()` (verify on the G20 in Task 7 — Chrome ≥105 supports it; the Play-updatable System WebView on API 30 is typically newer), replace the `:has()` rule with a JS guard in `renderStories()` that skips appending the add-tile when `document.body.classList.contains("readonly")`.

- [ ] **Step 6: Run the guard to verify it passes**

Run (from `android_tor_spike/app`): `npx vitest run test/web-readonly-seam.test.ts`
Expected: PASS (2 tests).

- [ ] **Step 7: Confirm desktop is unaffected**

Verify by inspection: `STATE.readonly` is undefined for a desktop node (its `/api/state` never sets the key), so `!!STATE.readonly === false` and `classList.toggle("readonly", false)` removes/omits the class — desktop renders exactly as before. No desktop test change required.

- [ ] **Step 8: Commit**

```bash
git add hearth/web/app.js hearth/web/style.css android_tor_spike/app/test/web-readonly-seam.test.ts
git commit -m "feat(vp1): read-only seam hides write affordances in the shared web ui"
```

---

## Task 7: On-device integration + report + PAUSE

**Files:**
- Create: `android_tor_spike/BRICK_VP1_REPORT.md`

**Interfaces:**
- Consumes: everything from Tasks 1–6 (built into one RELEASE apk).
- Produces: the on-device proof record + follow-up tickets; a review PAUSE.

- [ ] **Step 1: Full desk-gate sweep**

Run and record outputs:
- `./gradlew :tor-manager:testDebugUnitTest` (from `android_tor_spike/app/android`) — expect the whole JVM suite green incl. `LocalWebServerTest`, `LocalApiTest`.
- `npx tsc --noEmit` (from `android_tor_spike/app`) — expect no errors.
- `npx vitest run test/web-readonly-seam.test.ts` (from `android_tor_spike/app`) — expect green.

- [ ] **Step 2: Build + install the RELEASE apk**

From `android_tor_spike/app/android`: `./gradlew :app:assembleRelease`. Install `app/build/outputs/apk/release/app-release.apk` on the G20 (`adb install -r …`). RELEASE, not debug (debug → "Unable to load script"). Confirm the `copyHearthWeb` task ran during the build (assets present in the apk).

- [ ] **Step 3: Run the on-device DoD (human-driven)**

Preconditions (field lessons): the desktop peer must be reachable over Tor — run the desktop node with `serve --tor` (a bare `hearth app` has Tor OFF and the sync EOFs; a locked node refuses sync). Post fresh content from the desktop (a text post, a photo post, a video post, a story, and a reaction + comment on a post) so the phone has something to render.

DoD checklist — tick each:
- [ ] App launches straight into the Kreds journal (no onboarding/lock/setup screen) — bootstrap/applock stubs land it in the app.
- [ ] The journal renders via the desktop web UI (nav chrome, date "space line" separators), NOT the old native dashboard.
- [ ] A text post renders with correct author name + timestamp.
- [ ] A photo post renders (thumbnail + full-size). AVIF decodes in the WebView (raw `image/avif`). If a photo is blank, note it and apply the AVIF fallback ticket (isolated PNG transcode).
- [ ] A video post plays and seeks (range requests to `/api/post-blob` return 206).
- [ ] The stories strip renders; opening a story shows its media.
- [ ] Responses render: reaction count chips + comments (aliased names/colors match desktop).
- [ ] Read-only affordances are hidden: no composer, no comment input, no reaction-add opener, no "Delete everywhere", no "Your story" add tile — while count chips + comments remain visible.
- [ ] No token/CSP errors in `adb logcat` during a full scroll.

- [ ] **Step 4: Write `BRICK_VP1_REPORT.md`**

Mirror the prior `BRICK_*_REPORT.md` structure. Include: (a) a desk-gates table (each command + green/red); (b) the on-device DoD checklist above with pass/fail + notes; (c) run gotchas (RELEASE apk only; desktop `serve --tor`; post fresh content first; `?__t` token + cookie flow); (d) the honest boundary (read-only only; slice 1 = journal/feed only — no Messages/profile/kreds rail; avatars deferred to null); (e) follow-up tickets:
  - Avatars (`author_avatar` + comment `avatar`) — deferred; needs a profile-avatar blob-hash accessor on the phone store.
  - AVIF fallback — if the G20 WebView can't render raw `image/avif`, route `/api/post-blob` image bytes through `KotlinImageDecode.toRenderable` (isolated PNG transcode) as `/api/blob`-style; keep video/mp4 raw.
  - Perf — `/api/post-blob` runs a full `DecryptPass.run` per image request; cache the `keys` map on the `LocalApi`/server instance, invalidated on sync, to avoid O(images × messages) decrypts.
  - `/ws` — no websocket endpoint this slice; app.js `connectWs()` fails silently. Confirm it degrades cleanly; a later slice may add live push.
  - `expo prebuild --clean` — verify `withHearthWebAssets` re-injects `copyHearthWeb` (and that the pre-existing NSC/manifest loopback edits survive) before relying on a clean prebuild.
  - `accent` — `/api/state` returns the default `#2743d6`; source the real own-profile accent when a profile-accent accessor exists.

- [ ] **Step 5: Commit + PAUSE**

```bash
git add android_tor_spike/BRICK_VP1_REPORT.md
git commit -m "docs(vp1): on-device proof record and follow-up tickets for slice 1"
```

Then PAUSE for human review before starting slice 2 (Messages). Do not proceed past this checkpoint without sign-off.

---

## Self-Review

**1. Spec coverage.** Every slice-1 spec item maps to a task:
- Loopback server (bind/token/CSP/fail-closed) → Task 1. Cookie-token resolution of the "absolute-path assets, no path token" gap → Task 1 (query token → Set-Cookie → cookie).
- Bundled assets + single source of truth + config-plugin/prebuild resilience → Task 2 (Gradle copy, `.gitignore`, `withHearthWebAssets`).
- `/api/bootstrap`, `/api/applock`, `/api/state` (incl. `readonly:true`, friends) → Task 2.
- WebView host + cookies + CSP + on-device architecture proof → Task 3.
- `/api/feed` exact snake_case shape, journal-only, additive `Decrypted`/`Comment`, responses shape → Task 4.
- `/api/stories`, `/api/blob`, `/api/post-blob`, `_sniff` port, range, raw AVIF → Task 5.
- `body.readonly` seam (state field + app.js toggle + css) → Task 6.
- On-device DoD + report + PAUSE → Task 7.
- NOT-in-slice-1 endpoints (`/api/conversations`, `/api/dm/*`, `/api/profile/*`, `/api/kreds`, writes) — correctly excluded; no task implements them.

**2. Placeholder scan.** No "TBD"/"similar to Task N"/"add error handling". The three facts requiring implementation-time confirmation are written as read→confirm→implement with an explicit fallback: `placement` in the signed payload (CONFIRMED in Task 4 via `make_post` — `messages.py:82,90` — default `"journal"`); the react-native-webview Expo-57 version/props (Task 3 Step 1, fallback = keep the load-bearing cookie/inline-media/nav-gate capabilities); the write-affordance selectors (Task 6 Step 1 grep, real class names provided, fallback JS guard for `:has()`).

**3. Type consistency.**
- `HttpResponse(status, headers, body)` — defined Task 1, consumed identically in Tasks 2/5.
- `LocalWebServer(assets: (String)->Pair<String,ByteArray>?, api: (String,String,String?,String?,String?)->HttpResponse?)` — the `api` lambda's 5-arg arity matches the `TorManagerModule` wiring in Task 2 (`{ method, path, _, _, _ -> api.handle(method, path) }`).
- `LocalApi.handle(method, path)` — single arity throughout (Tasks 2/4/5 all extend the same `when`).
- `DecryptPass.Decrypted` — five additive trailing fields (`identityPub, scope, expiresAt, placement, codec`) defined Task 4, consumed by `LocalApi.feedRow` Task 4; the `sampleDecrypted` test constructor uses the exact same order/names.
- `KotlinResponses.Comment` — three additive trailing fields (`alias, aliasSeed, name`) defined Task 4, consumed by `responsesJson` Task 4; the test constructor matches.
- `StoredStory(msgId, author, mediaKind, media, poster, caption, createdAt)` — used in Task 5 tests/marshal exactly as defined in `SyncStore.kt`.
- `getWebUrl` — Kotlin AsyncFunction (Task 2) ↔ TS export (Task 3) name match.
- Test task name `:tor-manager:testDebugUnitTest` — consistent across all tasks and matches the module's Gradle project.

No inconsistencies found.
