# Android Background Node — Brick A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A continuously-running foreground Android `Service` that owns Tor and fires a periodic native (Kotlin) AUTH heartbeat to the home node over Tor — surviving background, Doze, and process-death — proving the persistent-background-node lifecycle before content sync is ported.

**Architecture:** Extract the Tor-owning state out of the RN module into a process-global `TorEngine` singleton so it outlives the Activity. Port the wire layer + HELLO/AUTH handshake to Kotlin (vector-gated against the same `wire_vectors.json`) so the heartbeat runs with no JS runtime. A `START_STICKY` foreground `TorNodeService` bootstraps Tor once, runs a scheduled heartbeat, persists a history ring buffer, and drives a status notification. The RN screen becomes a thin observer/controller.

**Tech Stack:** Expo local module (Kotlin + existing JNI/tor-android 0.4.9.6), BouncyCastle (Ed25519 on API 30), Android foreground Service + NotificationManager + SharedPreferences, gradle JVM unit tests (JUnit) for the Kotlin-port vector gate, React Native/TypeScript status screen.

**Spec:** `docs/superpowers/specs/2026-07-19-android-background-node-brick-a-design.md`

## Global Constraints

- **Commit messages: NO AI/Co-Authored-By trailers.** Style `feat(brick-a): ...` / `fix(brick-a): ...` / `docs(brick-a): ...` lowercase, ASCII hyphens.
- **Do not modify `hearth/` production code, `wire.ts`, `handshake.ts`, or `fixtures/wire_vectors.json`.** The Kotlin port is validated against the committed vectors; the vectors are the source of truth and are never edited to fit the port.
- **App package id:** `eu.kreds.torspike` (unchanged from the spike).
- **tor-android pinned `0.4.9.6`**, NDK `26.3.11579264`, **arm64-v8a only** (project is compileSdk 36; do not bump).
- **Phone:** Moto G20, serial `ZY32DLZQ2N`, API 30.
- **Env:** every PowerShell session touching gradle/adb dot-sources `android_tor_spike/tools/env.ps1` first (User env vars are not inherited by tool shells). Gradle native/JVM builds take minutes — use generous timeouts. Python gates use `.venv\Scripts\python.exe`.
- **Heartbeat interval N = 5 minutes** (constant `HEARTBEAT_INTERVAL_MS = 300_000L`); the heartbeat stops at AUTH-accepted, never advances to content.
- **Enrollment stays the adb-pushed fixture stub** (`spike_phone_fixture.json` in the app's external files dir). Brick A does not touch pairing.
- **The Kotlin heartbeat must not depend on a live JS runtime** — it runs entirely in the Service/native so it survives Doze and `START_STICKY` restart.
- **ONION dial port 9997** (`ONION_VIRTUAL_PORT`); `SOCKS_PORT = 39050`, `CONTROL_PORT = 39051` (unchanged).

## File Structure

All paths under `android_tor_spike/app/modules/tor-manager/` unless noted.

```
android/src/main/java/expo/modules/tormanager/
  TorEngine.kt            Task 1: process-global Tor owner (bootstrap/dial/send/recv/close/suspend); extracted from the Module
  TorManagerModule.kt     Task 1: thin delegator to TorEngine (JS interface unchanged); Task 7 adds node-control fns
  KotlinWire.kt           Task 2: canonical JSON + length-frames + Ed25519 (BouncyCastle), byte-matched to Python
  KotlinHandshake.kt      Task 3: HELLO/AUTH + acceptance probe over a TorEngine conn; returns a verdict
  HeartbeatStore.kt       Task 4: persisted ring buffer (last 50 beats) via SharedPreferences
  TorNodeService.kt       Task 5: foreground START_STICKY service; owns bootstrap-once + heartbeat timer + notification
  Socks.kt                (existing) readExact + socksDial
  ControlPort.kt          (existing) bootstrap-progress + shutdown
  TorRunner.kt            (existing) JNI entry
android/src/main/cpp/...  (existing JNI shim, unchanged)
android/src/test/java/expo/modules/tormanager/
  KotlinWireVectorTest.kt Task 2: JVM gate reading the committed wire_vectors.json
android/src/main/AndroidManifest.xml   Task 6: <service> + FOREGROUND_SERVICE/POST_NOTIFICATIONS/battery perms
android/build.gradle      Task 2: BouncyCastle dep + JUnit test deps + fixtures copy task
index.ts                  Task 7: node-control surface (startNode/stopNode/beatNow/getHistory/state events)
../../App.tsx             Task 8: status screen (start/stop, state, history, beat-now)
../../../tools/brick_a_ondevice.ps1   Task 9: adb Doze/kill/logcat verification script
../../../BRICK_A_REPORT.md            Task 9: outcome report
```

---

### Task 1: Extract `TorEngine` (process-global Tor owner)

Move the Tor-owning state out of the Module instance so it survives Activity destruction and can be driven by the Service. Behavior-preserving refactor; the JS interface is unchanged.

**Files:**
- Create: `android/src/main/java/expo/modules/tormanager/TorEngine.kt`
- Modify: `android/src/main/java/expo/modules/tormanager/TorManagerModule.kt`

**Interfaces:**
- Produces `TorEngine` (Kotlin `object`):
  - `fun init(context: Context)` — idempotent; stores `applicationContext`.
  - `fun bootstrap(onProgress: (Int) -> Unit, onDone: (Int) -> Unit, onError: (String, String) -> Unit)` — starts tor thread + watcher if not already up; `onDone(socksPort)` at 100%.
  - `val socksPort: Int` (= 39050)
  - `fun dial(host: String, port: Int): Int` — returns conn id (blocking)
  - `fun send(id: Int, data: ByteArray)` ; `fun recv(id: Int, n: Int): ByteArray` ; `fun close(id: Int)`
  - `fun suspend()` — SIGNAL SHUTDOWN + close conns
  - `val isUp: Boolean`
- Consumes: existing `TorRunner`, `ControlPort`, `socksDial`, `readExact`.

- [ ] **Step 1: Write `TorEngine.kt`** (lift the state + methods verbatim from the current Module, generalized to a Context)

```kotlin
package expo.modules.tormanager

import android.content.Context
import java.io.File
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

const val SOCKS_PORT = 39050
const val CONTROL_PORT = 39051
private const val BOOTSTRAP_TIMEOUT_MS = 300_000L

/** Process-global Tor owner. Extracted from TorManagerModule so Tor
 *  survives Activity destruction and is shared by the Module (foreground
 *  JS) and TorNodeService (background heartbeat). */
object TorEngine {
    @Volatile private var appContext: Context? = null
    @Volatile private var torThread: Thread? = null
    @Volatile private var torExitCode: Int? = null
    private val conns = ConcurrentHashMap<Int, Socket>()
    private val nextConn = AtomicInteger(1)

    val isUp: Boolean get() = torThread?.isAlive == true

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun ctx(): Context = appContext ?: error("TorEngine.init not called")
    private fun dataDir(): File = File(ctx().filesDir, "tordata").apply { mkdirs() }
    fun externalDir(): File = ctx().getExternalFilesDir(null)!!

    /** Idempotent: if tor is already up, calls onDone immediately. */
    fun bootstrap(
        onProgress: (Int) -> Unit,
        onDone: (Int) -> Unit,
        onError: (String, String) -> Unit,
    ) {
        if (isUp) { onDone(SOCKS_PORT); return }
        val dir = dataDir()
        val logFile = File(externalDir(), "tor.log")
        val args = arrayOf(
            "tor",
            "--SocksPort", "127.0.0.1:$SOCKS_PORT",
            "--ControlPort", "127.0.0.1:$CONTROL_PORT",
            "--CookieAuthentication", "1",
            "--DataDirectory", dir.absolutePath,
            "--Log", "notice file ${logFile.absolutePath}",
        )
        torThread = thread(name = "tor-main") { torExitCode = TorRunner.nativeRunTor(args) }
        thread(name = "tor-bootstrap-watch") {
            val ctl = ControlPort(CONTROL_PORT, File(dir, "control_auth_cookie"))
            val deadline = System.currentTimeMillis() + BOOTSTRAP_TIMEOUT_MS
            var last = -1
            while (System.currentTimeMillis() < deadline) {
                if (torThread?.isAlive != true) {
                    val detail = when (val code = torExitCode) {
                        -100 -> "dlopen(libtor.so) failed"
                        -101 -> "tor_api symbol missing"
                        -102 -> "tor_main_configuration_set_command_line failed"
                        null -> "no exit code captured"
                        else -> "tor exited with code $code"
                    }
                    onError("TOR_DIED", "tor thread exited during bootstrap: $detail (see tor.log if present)")
                    return@thread
                }
                val p = ctl.bootstrapProgress()
                if (p != null && p != last) { last = p; onProgress(p) }
                if (p == 100) { onDone(SOCKS_PORT); return@thread }
                Thread.sleep(1000)
            }
            onError("TOR_TIMEOUT", "bootstrap did not reach 100% in 300s")
        }
    }

    fun dial(host: String, port: Int): Int {
        val s = socksDial(SOCKS_PORT, host, port)
        val id = nextConn.getAndIncrement()
        conns[id] = s
        return id
    }

    fun send(id: Int, data: ByteArray) {
        val s = conns[id] ?: throw IllegalArgumentException("no conn $id")
        val out = s.getOutputStream()
        out.write(data)
        out.flush()
    }

    fun recv(id: Int, n: Int): ByteArray {
        val s = conns[id] ?: throw IllegalArgumentException("no conn $id")
        return s.getInputStream().readExact(n)
    }

    fun close(id: Int) { conns.remove(id)?.close() }

    fun suspend() {
        ControlPort(CONTROL_PORT, File(dataDir(), "control_auth_cookie")).signalShutdown()
        torThread?.join(10_000)
        conns.values.forEach { runCatching { it.close() } }
        conns.clear()
        torThread = null
    }
}
```

- [ ] **Step 2: Rewrite `TorManagerModule.kt` to delegate to `TorEngine`** (JS interface identical; base64 bridge + Dispatchers.IO stay; sockets now go through `TorEngine`)

```kotlin
package expo.modules.tormanager

import android.util.Base64
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TorManagerModule : Module() {
    // recv/send/dial block on socket I/O; keep them OFF the single default
    // AsyncFunction HandlerThread (would deadlock the concurrent recv+send
    // of the accepted-path probe). Dispatchers.IO is multi-threaded.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun definition() = ModuleDefinition {
        Name("TorManager")

        Constants(
            "fixtureDir" to (appContext.reactContext?.getExternalFilesDir(null)?.absolutePath ?: "")
        )

        Events("torProgress")

        OnCreate {
            appContext.reactContext?.let { TorEngine.init(it) }
        }

        AsyncFunction("bootstrap") { promise: Promise ->
            TorEngine.bootstrap(
                onProgress = { p -> sendEvent("torProgress", mapOf("progress" to p)) },
                onDone = { port -> promise.resolve(port) },
                onError = { code, msg -> promise.reject(code, msg, null) },
            )
        }

        Function("socksPort") { SOCKS_PORT }

        AsyncFunction("dial") { host: String, port: Int -> TorEngine.dial(host, port) }.runOnQueue(ioScope)

        AsyncFunction("send") { id: Int, b64: String ->
            TorEngine.send(id, Base64.decode(b64, Base64.NO_WRAP))
        }.runOnQueue(ioScope)

        AsyncFunction("recv") { id: Int, n: Int ->
            Base64.encodeToString(TorEngine.recv(id, n), Base64.NO_WRAP)
        }.runOnQueue(ioScope)

        Function("closeConn") { id: Int -> TorEngine.close(id) }

        AsyncFunction("suspendTor") { TorEngine.suspend() }
    }
}
```

- [ ] **Step 3: Build to verify the refactor compiles and packages**

```powershell
. .\android_tor_spike\tools\env.ps1; cd android_tor_spike\app\android; .\gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`. (Behavior-preserving; the on-device heartbeat in later tasks exercises the sockets. There is no desk-runnable unit test for the socket path — the gate here is compile + packaging.)

- [ ] **Step 4: Commit**

```powershell
cd C:\Users\Wong\Desktop\Hearth
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/TorEngine.kt android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/TorManagerModule.kt
git commit -m "feat(brick-a): extract TorEngine process-global Tor owner - survives Activity destruction, shared by module + (coming) service"
```

---

### Task 2: `KotlinWire` + the committed-vector JVM gate

Port canonical JSON, length-frames, and Ed25519 to Kotlin, proven against the **same** `wire_vectors.json` the TS port uses. This is the correctness spine of the native heartbeat.

**Files:**
- Create: `android/src/main/java/expo/modules/tormanager/KotlinWire.kt`
- Create: `android/src/test/java/expo/modules/tormanager/KotlinWireVectorTest.kt`
- Modify: `android/build.gradle` (BouncyCastle + JUnit + a task copying the fixtures into test resources)

**Interfaces:**
- Consumes: `android_tor_spike/fixtures/wire_vectors.json` (schema: `canonical_cases[{name,obj,bytes_hex}]` with `{"__pyfloat__":n}` float markers; `auth_cases[{device_priv,device_pub,nonce,body_hex,sig}]`; `cert_cases[{cert,body_hex,valid}]`; `frame_cases[{obj,frame_hex}]`).
- Produces (`object KotlinWire`):
  - `const val PROTOCOL = "hearth/v0.2"` ; `const val MAX_FRAME = 16 * 1024 * 1024`
  - `class PyFloat(val value: Double)` — marks a JSON number Python renders as float
  - `fun canonical(obj: Map<String, Any?>): ByteArray`
  - `fun dumps(v: Any?): String` (the serializer canonical uses)
  - `fun signRaw(devicePrivHex: String, data: ByteArray): String` ; `fun verifyRaw(pubHex: String, sigHex: String, data: ByteArray): Boolean`
  - `fun authBody(nonceHex: String): ByteArray`
  - `data class CertDict(val identity_pub: String, val device_pub: String, val device_name: String, val enrolled_at: Double, val signature: String)`
  - `fun certBody(c: CertDict): ByteArray` ; `fun verifyCert(c: CertDict): Boolean`
  - `fun toHex(b: ByteArray): String` ; `fun fromHex(h: String): ByteArray`

- [ ] **Step 1: Add deps + fixtures-copy task to `android/build.gradle`**

Add to `dependencies { }`:
```gradle
  implementation "org.bouncycastle:bcprov-jdk18on:1.78.1"
  testImplementation "junit:junit:4.13.2"
  testImplementation "org.json:json:20240303"
  testImplementation "org.bouncycastle:bcprov-jdk18on:1.78.1"
```
Add at the top level of the file (so the JVM test can read the one committed fixture without duplicating it):
```gradle
// Copy the single source-of-truth wire vectors into test resources so the
// KotlinWire gate reads the SAME bytes the TS vitest gate does.
tasks.register('copyWireVectors', Copy) {
  from "${projectDir}/../../../fixtures/wire_vectors.json"
  into "${projectDir}/src/test/resources"
}
tasks.named('preBuild') { dependsOn 'copyWireVectors' }
tasks.withType(Test).configureEach { dependsOn 'copyWireVectors' }
```
Add `src/test/resources/wire_vectors.json` to `android_tor_spike/app/modules/tor-manager/android/.gitignore` (create it if absent) — it is a generated copy, not committed.

- [ ] **Step 2: Write the failing JVM vector test**

`android/src/test/java/expo/modules/tormanager/KotlinWireVectorTest.kt`:
```kotlin
package expo.modules.tormanager

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinWireVectorTest {
    private fun vectors(): JSONObject {
        val text = javaClass.classLoader!!.getResourceAsStream("wire_vectors.json")!!
            .readBytes().toString(Charsets.UTF_8)
        return JSONObject(text)
    }

    /** Turn a vector "obj" (with {"__pyfloat__": n} markers) into the
     *  Map<String,Any?> / PyFloat shape KotlinWire.canonical expects. */
    private fun revive(v: Any?): Any? = when (v) {
        is JSONObject -> {
            if (v.length() == 1 && v.has("__pyfloat__")) KotlinWire.PyFloat(v.getDouble("__pyfloat__"))
            else v.keys().asSequence().associateWith { revive(v.get(it)) }
        }
        is JSONArray -> (0 until v.length()).map { revive(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }

    @Test fun canonicalMatchesPython() {
        val cases = vectors().getJSONArray("canonical_cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            @Suppress("UNCHECKED_CAST")
            val obj = revive(c.getJSONObject("obj")) as Map<String, Any?>
            assertEquals(c.getString("name"), c.getString("bytes_hex"),
                KotlinWire.toHex(KotlinWire.canonical(obj)))
        }
    }

    @Test fun authVectorsVerify() {
        val cases = vectors().getJSONArray("auth_cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val body = KotlinWire.authBody(c.getString("nonce"))
            assertEquals(c.getString("body_hex"), KotlinWire.toHex(body))
            assertEquals(c.getString("sig"), KotlinWire.signRaw(c.getString("device_priv"), body))
            assertTrue(KotlinWire.verifyRaw(c.getString("device_pub"), c.getString("sig"), body))
        }
    }

    @Test fun certVectorsVerify() {
        val cases = vectors().getJSONArray("cert_cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val cd = c.getJSONObject("cert")
            val cert = KotlinWire.CertDict(
                cd.getString("identity_pub"), cd.getString("device_pub"),
                cd.getString("device_name"), cd.getDouble("enrolled_at"),
                cd.getString("signature"))
            assertEquals("cert_case $i", c.getBoolean("valid"),
                KotlinWire.verifyCert(cert))
            if (!c.isNull("body_hex"))
                assertEquals(c.getString("body_hex"), KotlinWire.toHex(KotlinWire.certBody(cert)))
        }
    }
}
```

- [ ] **Step 3: Run it — expect FAIL (KotlinWire absent)**

```powershell
. .\android_tor_spike\tools\env.ps1; cd android_tor_spike\app\android; .\gradlew :tor-manager:testDebugUnitTest
```
Expected: FAIL (unresolved reference: KotlinWire).

- [ ] **Step 4: Write `KotlinWire.kt`** (canonical mirrors `wire.ts` `dumps`; escaping/sort/float rules identical)

```kotlin
package expo.modules.tormanager

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Kotlin port of the wire layer (canonical JSON, length-frames, Ed25519),
 *  byte-matched to hearth/identity.py via the committed wire_vectors.json.
 *  Runs with no JS runtime -- this is the background heartbeat's crypto. */
object KotlinWire {
    const val PROTOCOL = "hearth/v0.2"
    const val MAX_FRAME = 16 * 1024 * 1024

    class PyFloat(val value: Double)

    fun toHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append("%02x".format(x.toInt() and 0xff))
        return sb.toString()
    }

    fun fromHex(h: String): ByteArray {
        require(h.length % 2 == 0) { "odd-length hex" }
        return ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    private fun pyFloatRepr(n: Double): String {
        require(n.isFinite()) { "non-finite float unsupported" }
        // Python repr and Kotlin agree in plain fixed notation; refuse the
        // extreme-magnitude range where notations diverge (no spike value is near it).
        if (Math.abs(n) >= 1e16 || (n != 0.0 && Math.abs(n) < 1e-4))
            throw IllegalArgumentException("float out of supported range: $n")
        return if (n == Math.floor(n)) "${n.toLong()}.0" else {
            val s = n.toString()
            if (s.contains('E') || s.contains('e')) throw IllegalArgumentException("unexpected exp notation: $n")
            s
        }
    }

    private fun escapeString(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            val c = ch.code
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                c == 0x08 -> sb.append("\\b")
                c == 0x09 -> sb.append("\\t")
                c == 0x0a -> sb.append("\\n")
                c == 0x0c -> sb.append("\\f")
                c == 0x0d -> sb.append("\\r")
                c < 0x20 || c > 0x7e -> sb.append("\\u").append("%04x".format(c))
                else -> sb.append(ch)
            }
        }
        return sb.append("\"").toString()
    }

    // Python sorts dict keys by code point; Kotlin String.compareTo is by
    // UTF-16 unit. They diverge only when an astral key meets U+E000..U+FFFF.
    private fun codePointCompare(a: String, b: String): Int {
        val ca = a.codePoints().toArray(); val cb = b.codePoints().toArray()
        val n = minOf(ca.size, cb.size)
        for (i in 0 until n) { val d = ca[i] - cb[i]; if (d != 0) return d }
        return ca.size - cb.size
    }

    fun dumps(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> if (v) "true" else "false"
        is String -> escapeString(v)
        is PyFloat -> pyFloatRepr(v.value)
        is Int -> v.toString()
        is Long -> v.toString()
        is List<*> -> "[" + v.joinToString(",") { dumps(it) } + "]"
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val m = v as Map<String, Any?>
            val keys = m.keys.sortedWith(::codePointCompare)
            "{" + keys.joinToString(",") { escapeString(it) + ":" + dumps(m[it]) } + "}"
        }
        else -> throw IllegalArgumentException("unsupported type in serialization: ${v::class}")
    }

    fun canonical(obj: Map<String, Any?>): ByteArray {
        val s = dumps(obj)                 // pure ASCII (ensure_ascii)
        return ByteArray(s.length) { s[it].code.toByte() }
    }

    fun writeFrameBytes(obj: Map<String, Any?>): ByteArray {
        val payload = canonical(obj)
        require(payload.size <= MAX_FRAME) { "frame too large" }
        val out = ByteArray(4 + payload.size)
        out[0] = (payload.size ushr 24).toByte(); out[1] = (payload.size ushr 16).toByte()
        out[2] = (payload.size ushr 8).toByte(); out[3] = payload.size.toByte()
        payload.copyInto(out, 4)
        return out
    }

    fun signRaw(devicePrivHex: String, data: ByteArray): String {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(fromHex(devicePrivHex), 0))
        signer.update(data, 0, data.size)
        return toHex(signer.generateSignature())
    }

    fun verifyRaw(pubHex: String, sigHex: String, data: ByteArray): Boolean = try {
        val v = Ed25519Signer()
        v.init(false, Ed25519PublicKeyParameters(fromHex(pubHex), 0))
        v.update(data, 0, data.size)
        v.verifySignature(fromHex(sigHex))
    } catch (e: Exception) { false }

    fun authBody(nonceHex: String): ByteArray =
        canonical(mapOf("type" to "gossip-auth", "protocol" to PROTOCOL, "nonce" to nonceHex))

    data class CertDict(
        val identity_pub: String, val device_pub: String,
        val device_name: String, val enrolled_at: Double, val signature: String,
    )

    fun certBody(c: CertDict): ByteArray = canonical(mapOf(
        "type" to "enrollment", "protocol" to PROTOCOL,
        "identity_pub" to c.identity_pub, "device_pub" to c.device_pub,
        "device_name" to c.device_name, "enrolled_at" to PyFloat(c.enrolled_at),
    ))

    fun verifyCert(c: CertDict): Boolean = verifyRaw(c.identity_pub, c.signature, certBody(c))
}
```

- [ ] **Step 5: Run the JVM gate — expect PASS**

```powershell
. .\android_tor_spike\tools\env.ps1; cd android_tor_spike\app\android; .\gradlew :tor-manager:testDebugUnitTest
```
Expected: PASS (canonicalMatchesPython, authVectorsVerify, certVectorsVerify). If a canonical case fails, fix `KotlinWire`, never the vectors.

- [ ] **Step 6: Commit**

```powershell
cd C:\Users\Wong\Desktop\Hearth
git add android_tor_spike/app/modules/tor-manager/android/build.gradle android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/KotlinWire.kt android_tor_spike/app/modules/tor-manager/android/src/test android_tor_spike/app/modules/tor-manager/android/.gitignore
git commit -m "feat(brick-a): KotlinWire port (canonical/frames/ed25519) green against the committed wire vectors - native heartbeat crypto, no JS runtime"
```

---

### Task 3: `KotlinHandshake` (HELLO/AUTH + probe, native)

Port `handshake.ts` to Kotlin over a `TorEngine` connection, including the amended probe (single up-front verdict read, grace window, identity pin in the accepted branch).

**Files:**
- Create: `android/src/main/java/expo/modules/tormanager/KotlinHandshake.kt`

**Interfaces:**
- Consumes: `KotlinWire`, `TorEngine.dial/send/recv/close`.
- Produces:
  - `data class Fixture(val device_priv: String, val device_pub: String, val cert: KotlinWire.CertDict, val onion_addr: String)`
  - `sealed class HandshakeResult { object Accepted; object Refused; data class Failed(val stage: String, val reason: String) }`
  - `fun run(connId: Int, fixture: Fixture, randomHex16: () -> String): HandshakeResult`
  - `fun parseFixture(json: String): Fixture` (reads `spike_phone_fixture.json`)
  - `fun splitAddr(addr: String): Pair<String, Int>`

- [ ] **Step 1: Write `KotlinHandshake.kt`** (frame read via length prefix over `TorEngine.recv`; mirrors handshake.ts exactly)

```kotlin
package expo.modules.tormanager

import org.json.JSONObject
import java.security.SecureRandom

object KotlinHandshake {
    private const val PROBE_GRACE_MS = 1500L

    data class Fixture(
        val device_priv: String, val device_pub: String,
        val cert: KotlinWire.CertDict, val onion_addr: String,
    )

    sealed class HandshakeResult {
        object Accepted : HandshakeResult()
        object Refused : HandshakeResult()
        data class Failed(val stage: String, val reason: String) : HandshakeResult()
    }

    fun randomHex16(): String {
        val b = ByteArray(16); SecureRandom().nextBytes(b); return KotlinWire.toHex(b)
    }

    fun splitAddr(addr: String): Pair<String, Int> {
        val i = addr.lastIndexOf(':')
        require(i >= 0) { "address has no port: $addr" }
        return addr.substring(0, i) to addr.substring(i + 1).toInt()
    }

    fun parseFixture(json: String): Fixture {
        val o = JSONObject(json); val c = o.getJSONObject("cert")
        return Fixture(
            o.getString("device_priv"), o.getString("device_pub"),
            KotlinWire.CertDict(
                c.getString("identity_pub"), c.getString("device_pub"),
                c.getString("device_name"), c.getDouble("enrolled_at"),
                c.getString("signature")),
            o.getString("onion_addr"))
    }

    private fun readFrame(connId: Int): JSONObject {
        val header = TorEngine.recv(connId, 4)
        val n = ((header[0].toInt() and 0xff) shl 24) or ((header[1].toInt() and 0xff) shl 16) or
                ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
        require(n <= KotlinWire.MAX_FRAME) { "frame too large" }
        val body = TorEngine.recv(connId, n)
        for (bb in body) require((bb.toInt() and 0xff) <= 0x7e) { "non-ascii frame byte" }
        return JSONObject(String(body, Charsets.US_ASCII))
    }

    private fun writeFrame(connId: Int, obj: Map<String, Any?>) =
        TorEngine.send(connId, KotlinWire.writeFrameBytes(obj))

    private fun certToMap(c: KotlinWire.CertDict): Map<String, Any?> = mapOf(
        "identity_pub" to c.identity_pub, "device_pub" to c.device_pub,
        "device_name" to c.device_name,
        "enrolled_at" to KotlinWire.PyFloat(c.enrolled_at), "signature" to c.signature)

    private fun certFromJson(o: JSONObject) = KotlinWire.CertDict(
        o.getString("identity_pub"), o.getString("device_pub"),
        o.getString("device_name"), o.getDouble("enrolled_at"), o.getString("signature"))

    /** Mirror of handshake.ts, phone = initiator, stops at accept/refuse. */
    fun run(connId: Int, fixture: Fixture, rnd: () -> String = ::randomHex16): HandshakeResult {
        try {
            // HELLO
            val myNonce = rnd()
            writeFrame(connId, mapOf("t" to "hello", "cert" to certToMap(fixture.cert), "nonce" to myNonce))
            val peerHello = readFrame(connId)
            if (peerHello.optString("t") != "hello") return HandshakeResult.Failed("hello", "unexpected t=${peerHello.optString("t")}")
            val peerCert = certFromJson(peerHello.getJSONObject("cert"))
            if (!KotlinWire.verifyCert(peerCert)) return HandshakeResult.Failed("hello", "node cert failed verification")

            // AUTH
            writeFrame(connId, mapOf("t" to "auth",
                "sig" to KotlinWire.signRaw(fixture.device_priv, KotlinWire.authBody(peerHello.getString("nonce")))))
            val peerAuth = readFrame(connId)
            if (peerAuth.optString("t") != "auth") return HandshakeResult.Failed("auth", "unexpected t=${peerAuth.optString("t")}")
            if (!KotlinWire.verifyRaw(peerCert.device_pub, peerAuth.getString("sig"), KotlinWire.authBody(myNonce)))
                return HandshakeResult.Failed("auth", "node failed device-key proof")

            // Acceptance probe: one verdict read up front, grace-wait for an
            // unsolicited refusal, then send empty revocations and await the
            // SAME read (see handshake.ts amendment -- write-first races the
            // refusing node's close). recv is blocking, so emulate the grace
            // by setting a short read deadline is not available here; instead
            // we send the revocations frame immediately after AUTH -- on the
            // accepted path the node (responder) is already blocked reading
            // it, and on refusal the node has already written "refused" which
            // arrives ahead of our frame on the wire. A refusing node closing
            // after its write still delivers the buffered "refused" because
            // our very next recv reads it before noticing the close.
            writeFrame(connId, mapOf("t" to "revocations", "revs" to emptyList<Any>()))
            val verdict = readFrame(connId)
            return when (verdict.optString("t")) {
                "refused" -> HandshakeResult.Refused
                "revocations" ->
                    if (peerCert.identity_pub != fixture.cert.identity_pub)
                        HandshakeResult.Failed("probe", "accepted by a non-home-node identity")
                    else HandshakeResult.Accepted
                else -> HandshakeResult.Failed("probe", "unexpected t=${verdict.optString("t")}")
            }
        } catch (e: Exception) {
            return HandshakeResult.Failed("io", e.toString())
        } finally {
            TorEngine.close(connId)
        }
    }
}
```

> **Implementer note (probe ordering):** the desk gate proved the JS probe needed a grace-read-before-write because Node's non-blocking loop + Windows loopback RST purged the buffered `refused`. Kotlin's `recv` is *blocking* and reads sequentially, so issuing the revocations write then the single read is the natural ordering — the node's `refused` (written before it closed) is still in our socket receive buffer and is read before the close is observed. If the on-device run (Task 9) ever shows a refusal misreported as `failed/io`, revisit with a pre-read of one frame with a short `soTimeout` before writing. Do not change `handshake.ts`.

- [ ] **Step 2: Build to verify it compiles**

```powershell
. .\android_tor_spike\tools\env.ps1; cd android_tor_spike\app\android; .\gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`. (Runtime correctness is proven by the on-device heartbeat in Task 9; the wire bytes are already vector-gated in Task 2.)

- [ ] **Step 3: Commit**

```powershell
cd C:\Users\Wong\Desktop\Hearth
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/KotlinHandshake.kt
git commit -m "feat(brick-a): KotlinHandshake - native HELLO/AUTH+probe over TorEngine, mirrors handshake.ts"
```

---

### Task 4: `HeartbeatStore` (persisted ring buffer)

**Files:**
- Create: `android/src/main/java/expo/modules/tormanager/HeartbeatStore.kt`
- Create: `android/src/test/java/expo/modules/tormanager/HeartbeatStoreTest.kt`

**Interfaces:**
- Produces:
  - `data class Beat(val ts: Long, val ok: Boolean, val latencyMs: Long, val reason: String?)`
  - `object HeartbeatStore { fun record(ctx: Context, beat: Beat); fun history(ctx: Context): List<Beat>; fun toJsonArray(list: List<Beat>): String }`
  - Ring buffer cap `MAX_BEATS = 50`. Backed by SharedPreferences (`"brick_a_beats"`), stored as a JSON array string. Serialization is pure (testable without Android via the JSON helpers).

- [ ] **Step 1: Write the failing pure-serialization test** (the JSON round-trip + cap logic factored out so it runs on the JVM without Android)

`android/src/test/java/expo/modules/tormanager/HeartbeatStoreTest.kt`:
```kotlin
package expo.modules.tormanager

import org.junit.Assert.assertEquals
import org.junit.Test

class HeartbeatStoreTest {
    @Test fun capsAtFiftyKeepingNewest() {
        var list = emptyList<Beat>()
        for (i in 1..60) list = HeartbeatStore.append(list, Beat(i.toLong(), true, 10, null))
        assertEquals(50, list.size)
        assertEquals(60L, list.first().ts)   // newest first
        assertEquals(11L, list.last().ts)    // oldest kept
    }

    @Test fun jsonRoundTrips() {
        val list = listOf(Beat(5, false, 0, "io"), Beat(4, true, 123, null))
        assertEquals(list, HeartbeatStore.fromJsonArray(HeartbeatStore.toJsonArray(list)))
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (`.\gradlew :tor-manager:testDebugUnitTest`). Expected: unresolved `HeartbeatStore`.

- [ ] **Step 3: Write `HeartbeatStore.kt`**

```kotlin
package expo.modules.tormanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Beat(val ts: Long, val ok: Boolean, val latencyMs: Long, val reason: String?)

object HeartbeatStore {
    private const val PREFS = "brick_a_beats"
    private const val KEY = "beats"
    const val MAX_BEATS = 50

    /** Pure: prepend newest, cap at MAX_BEATS. */
    fun append(list: List<Beat>, beat: Beat): List<Beat> =
        (listOf(beat) + list).take(MAX_BEATS)

    fun toJsonArray(list: List<Beat>): String {
        val arr = JSONArray()
        for (b in list) arr.put(JSONObject().apply {
            put("ts", b.ts); put("ok", b.ok); put("latencyMs", b.latencyMs)
            put("reason", b.reason ?: JSONObject.NULL)
        })
        return arr.toString()
    }

    fun fromJsonArray(s: String): List<Beat> {
        val arr = JSONArray(s)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Beat(o.getLong("ts"), o.getBoolean("ok"), o.getLong("latencyMs"),
                if (o.isNull("reason")) null else o.getString("reason"))
        }
    }

    fun history(ctx: Context): List<Beat> {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return if (s == null) emptyList() else fromJsonArray(s)
    }

    fun record(ctx: Context, beat: Beat) {
        val next = append(history(ctx), beat)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, toJsonArray(next)).apply()
    }
}
```

- [ ] **Step 4: Run — expect PASS.** Expected: both tests pass.

- [ ] **Step 5: Commit**

```powershell
cd C:\Users\Wong\Desktop\Hearth
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/HeartbeatStore.kt android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/HeartbeatStoreTest.kt
git commit -m "feat(brick-a): HeartbeatStore - persisted 50-beat ring buffer, pure append/JSON tested on the JVM"
```

---

### Task 5: `TorNodeService` (foreground, START_STICKY)

The persistent owner: bootstrap Tor once, run the heartbeat every N minutes, hold the notification, persist beats, survive process death.

**Files:**
- Create: `android/src/main/java/expo/modules/tormanager/TorNodeService.kt`

**Interfaces:**
- Consumes: `TorEngine`, `KotlinHandshake`, `HeartbeatStore`, the fixture file `spike_phone_fixture.json` in `TorEngine.externalDir()`.
- Produces (static control API the Module calls in Task 7):
  - `companion object { fun start(ctx: Context); fun stop(ctx: Context); fun beatNow(ctx: Context) }`
  - Broadcasts `eu.kreds.torspike.BEAT` (extras: `ts`,`ok`,`latencyMs`,`reason`) and `eu.kreds.torspike.STATE` (extra: `state` in {`bootstrapping`,`up`,`stopped`,`error`}) for the screen to observe (Task 7 subscribes).
  - `const val CHANNEL_ID = "kreds-node"`, `NOTIF_ID = 1`.

- [ ] **Step 1: Write `TorNodeService.kt`**

```kotlin
package expo.modules.tormanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class TorNodeService : Service() {
    companion object {
        const val CHANNEL_ID = "kreds-node"
        const val NOTIF_ID = 1
        private const val HEARTBEAT_INTERVAL_MS = 300_000L    // N = 5 min
        const val ACTION_STOP = "eu.kreds.torspike.STOP"
        const val ACTION_BEAT_NOW = "eu.kreds.torspike.BEAT_NOW"
        const val BROADCAST_BEAT = "eu.kreds.torspike.BEAT"
        const val BROADCAST_STATE = "eu.kreds.torspike.STATE"

        fun start(ctx: Context) {
            val i = Intent(ctx, TorNodeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, TorNodeService::class.java).setAction(ACTION_STOP))
        fun beatNow(ctx: Context) = ctx.startService(Intent(ctx, TorNodeService::class.java).setAction(ACTION_BEAT_NOW))
    }

    @Volatile private var scheduler: ScheduledExecutorService? = null
    @Volatile private var beats = 0
    @Volatile private var lastLine = "starting"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_BEAT_NOW -> { scheduler?.execute { heartbeat() }; return START_STICKY }
        }
        if (scheduler == null) startNode()
        return START_STICKY      // OS restarts us after process death
    }

    private fun startNode() {
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification("Kreds node starting..."))
        broadcastState("bootstrapping")
        TorEngine.init(this)
        scheduler = Executors.newSingleThreadScheduledExecutor()
        // Bootstrap once on the scheduler thread; on success, begin the beat cadence.
        scheduler!!.execute {
            TorEngine.bootstrap(
                onProgress = { p -> updateNotification("Tor bootstrap $p%") },
                onDone = {
                    // onDone fires on TorEngine's watcher thread, NOT the
                    // scheduler thread. Post ALL heartbeat work back onto the
                    // single scheduler thread so beats never overlap (a beatNow
                    // queued during bootstrap would otherwise race the first
                    // beat). scheduler?. + runCatching guard the stop-during-
                    // bootstrap race (shutdown() nulls/shuts the executor);
                    // without them scheduler!!.schedule NPEs or throws
                    // RejectedExecutionException on this thread and kills the process.
                    broadcastState("up")
                    val s = scheduler
                    runCatching {
                        s?.execute { heartbeat() }   // immediate first beat, on the scheduler thread
                        s?.scheduleAtFixedRate({ heartbeat() },
                            HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    }
                },
                onError = { code, msg ->
                    broadcastState("error")
                    updateNotification("Tor failed: $code")
                    recordAndBroadcast(Beat(System.currentTimeMillis(), false, 0, "$code: $msg"))
                },
            )
        }
    }

    private fun fixture(): KotlinHandshake.Fixture {
        val f = File(TorEngine.externalDir(), "spike_phone_fixture.json")
        return KotlinHandshake.parseFixture(f.readText())
    }

    private fun heartbeat() {
        val start = System.currentTimeMillis()
        val beat = try {
            if (!TorEngine.isUp) throw IllegalStateException("tor not up")
            val fx = fixture()
            val (host, port) = KotlinHandshake.splitAddr(fx.onion_addr)
            val conn = TorEngine.dial(host, port)
            when (val r = KotlinHandshake.run(conn, fx)) {
                is KotlinHandshake.HandshakeResult.Accepted ->
                    Beat(start, true, System.currentTimeMillis() - start, null)
                is KotlinHandshake.HandshakeResult.Refused ->
                    Beat(start, false, System.currentTimeMillis() - start, "refused")
                is KotlinHandshake.HandshakeResult.Failed ->
                    Beat(start, false, System.currentTimeMillis() - start, "${r.stage}: ${r.reason}")
            }
        } catch (e: Exception) {
            Beat(start, false, System.currentTimeMillis() - start, "io: ${e.message}")
        }
        // recordAndBroadcast does prefs I/O + notify + sendBroadcast, all of
        // which can throw on-device. An uncaught throwable in a
        // scheduleAtFixedRate task SILENTLY CANCELS all future beats, so this
        // MUST be guarded -- a transient notify/prefs hiccup must not
        // permanently kill the heartbeat. heartbeat() therefore never throws
        // to the executor.
        runCatching { recordAndBroadcast(beat) }
    }

    private fun recordAndBroadcast(beat: Beat) {
        HeartbeatStore.record(this, beat)
        beats++
        val hhmm = SimpleDateFormat("HH:mm", Locale.US).format(Date(beat.ts))
        lastLine = if (beat.ok) "last beat $hhmm OK, ${beat.latencyMs}ms - $beats beats"
                   else "last beat $hhmm FAIL (${beat.reason}) - $beats beats"
        updateNotification(lastLine)
        sendBroadcast(Intent(BROADCAST_BEAT).setPackage(packageName).apply {
            putExtra("ts", beat.ts); putExtra("ok", beat.ok)
            putExtra("latencyMs", beat.latencyMs); putExtra("reason", beat.reason)
        })
    }

    private fun broadcastState(state: String) =
        sendBroadcast(Intent(BROADCAST_STATE).setPackage(packageName).putExtra("state", state))

    private fun shutdown() {
        scheduler?.shutdownNow(); scheduler = null
        runCatching { TorEngine.suspend() }
        broadcastState("stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { runCatching { TorEngine.suspend() }; super.onDestroy() }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Kreds node", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kreds node")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)   // placeholder system icon
            .setOngoing(true)
            .addAction(0, "Stop", android.app.PendingIntent.getService(
                this, 1, Intent(this, TorNodeService::class.java).setAction(ACTION_STOP),
                android.app.PendingIntent.FLAG_IMMUTABLE))
            .build()

    private fun updateNotification(text: String) =
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
}
```

- [ ] **Step 2: Add `androidx.core` (for NotificationCompat) to `android/build.gradle`** `dependencies { }` if not already resolvable (expo modules usually provide it transitively; add explicitly to be safe):
```gradle
  implementation "androidx.core:core-ktx:1.13.1"
```

- [ ] **Step 3: Build to verify it compiles** (`.\gradlew assembleDebug`). Expected: `BUILD SUCCESSFUL`. Manifest wiring for the `<service>` is Task 6 — the class compiling is this task's gate.

- [ ] **Step 4: Commit**

```powershell
cd C:\Users\Wong\Desktop\Hearth
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/TorNodeService.kt android_tor_spike/app/modules/tor-manager/android/build.gradle
git commit -m "feat(brick-a): TorNodeService - START_STICKY foreground service, bootstrap-once + 5min heartbeat + notification + persisted beats"
```

---

### Task 6: Manifest — service, foreground-service type, permissions

**Files:**
- Create/Modify: `android/src/main/AndroidManifest.xml` (the module's manifest; merged into the app)

**Interfaces:** Produces the `<service>` registration + permissions the Service needs; consumed implicitly by `TorNodeService.start`.

- [ ] **Step 1: Write the module manifest** (if the module has none yet, create it; if it exists, merge these nodes)

`android/src/main/AndroidManifest.xml`:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <application>
        <service
            android:name="expo.modules.tormanager.TorNodeService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
    </application>
</manifest>
```

- [ ] **Step 2: Build + confirm the merged manifest carries the service** (`.\gradlew assembleDebug`), then:
```powershell
& "$env:JAVA_HOME\bin\jar.exe" -xf app\build\outputs\apk\debug\app-debug.apk AndroidManifest.xml -C $env:TEMP 2>$null
# Simpler: confirm via the merged manifest report
Select-String -Path app\build\intermediates\merged_manifests\debug\AndroidManifest.xml -Pattern "TorNodeService|FOREGROUND_SERVICE|POST_NOTIFICATIONS"
```
Expected: matches for `TorNodeService`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`.

- [ ] **Step 3: Commit**

```powershell
cd C:\Users\Wong\Desktop\Hearth
git add android_tor_spike/app/modules/tor-manager/android/src/main/AndroidManifest.xml
git commit -m "feat(brick-a): module manifest - TorNodeService + foreground-service/notifications/battery permissions"
```

---

### Task 7: Module node-control surface + `index.ts`

Expose start/stop/beat-now/history and the beat/state event stream to JS, and request the battery-optimization exemption.

**Files:**
- Modify: `android/src/main/java/expo/modules/tormanager/TorManagerModule.kt`
- Modify: `index.ts`

**Interfaces:**
- Consumes: `TorNodeService`, `HeartbeatStore`, Android battery-optimization APIs.
- Produces (JS, added to the existing exports):
  - `startNode(): void` ; `stopNode(): void` ; `beatNow(): void`
  - `getHistory(): Promise<Beat[]>` where `Beat = { ts: number; ok: boolean; latencyMs: number; reason: string | null }`
  - `onBeat(cb: (b: Beat) => void): () => void` ; `onState(cb: (s: string) => void): () => void`
  - `requestBatteryExemption(): void` ; `isBatteryExempt(): boolean`

- [ ] **Step 1: Add node-control functions to `TorManagerModule.kt`** (inside `ModuleDefinition`, after `suspendTor`; register a `BroadcastReceiver` in `OnCreate`/`OnDestroy` that forwards Service broadcasts to JS events `nodeBeat`/`nodeState`)

```kotlin
        // -- Brick A: background node control --
        Events("torProgress", "nodeBeat", "nodeState")

        Function("startNode") { appContext.reactContext?.let { TorNodeService.start(it) } }
        Function("stopNode") { appContext.reactContext?.let { TorNodeService.stop(it) } }
        Function("beatNow") { appContext.reactContext?.let { TorNodeService.beatNow(it) } }

        AsyncFunction("getHistory") {
            val ctx = appContext.reactContext ?: return@AsyncFunction emptyList<Map<String, Any?>>()
            HeartbeatStore.history(ctx).map {
                mapOf("ts" to it.ts, "ok" to it.ok, "latencyMs" to it.latencyMs, "reason" to it.reason)
            }
        }

        Function("isBatteryExempt") {
            val ctx = appContext.reactContext ?: return@Function false
            val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }

        Function("requestBatteryExemption") {
            val ctx = appContext.reactContext ?: return@Function
            val i = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${ctx.packageName}"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
```
And register the receiver in `OnCreate` (extend the existing `OnCreate`):
```kotlin
        OnCreate {
            val ctx = appContext.reactContext ?: return@OnCreate
            TorEngine.init(ctx)
            val filter = android.content.IntentFilter().apply {
                addAction(TorNodeService.BROADCAST_BEAT); addAction(TorNodeService.BROADCAST_STATE)
            }
            val rx = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    when (i?.action) {
                        TorNodeService.BROADCAST_BEAT -> sendEvent("nodeBeat", mapOf(
                            "ts" to i.getLongExtra("ts", 0), "ok" to i.getBooleanExtra("ok", false),
                            "latencyMs" to i.getLongExtra("latencyMs", 0), "reason" to i.getStringExtra("reason")))
                        TorNodeService.BROADCAST_STATE -> sendEvent("nodeState",
                            mapOf("state" to i.getStringExtra("state")))
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33)
                ctx.registerReceiver(rx, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            else ctx.registerReceiver(rx, filter)
            nodeReceiver = rx
        }
        OnDestroy { appContext.reactContext?.let { c -> nodeReceiver?.let { c.unregisterReceiver(it) } } }
```
Add the field `private var nodeReceiver: android.content.BroadcastReceiver? = null` to the class and `import android.os.Build`.

- [ ] **Step 2: Add the JS surface to `index.ts`**

```ts
export interface Beat { ts: number; ok: boolean; latencyMs: number; reason: string | null }

export function startNode(): void { native.startNode(); }
export function stopNode(): void { native.stopNode(); }
export function beatNow(): void { native.beatNow(); }
export function getHistory(): Promise<Beat[]> { return native.getHistory(); }
export function isBatteryExempt(): boolean { return native.isBatteryExempt(); }
export function requestBatteryExemption(): void { native.requestBatteryExemption(); }

export function onBeat(cb: (b: Beat) => void): () => void {
  const sub = native.addListener("nodeBeat", (e: Beat) => cb(e));
  return () => sub.remove();
}
export function onState(cb: (s: string) => void): () => void {
  const sub = native.addListener("nodeState", (e: { state: string }) => cb(e.state));
  return () => sub.remove();
}
```

- [ ] **Step 3: Build to verify** (`.\gradlew assembleDebug`). Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```powershell
cd C:\Users\Wong\Desktop\Hearth
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/TorManagerModule.kt android_tor_spike/app/modules/tor-manager/index.ts
git commit -m "feat(brick-a): module node-control surface - start/stop/beatNow/getHistory, beat+state events, battery-exemption request"
```

---

### Task 8: RN status screen

Replace the one-shot Connect flow with a node dashboard: start/stop, live state, beat-now, battery-exemption prompt, and the heartbeat history list.

**Files:**
- Modify: `../../App.tsx` (`android_tor_spike/app/App.tsx`)

**Interfaces:** Consumes `startNode/stopNode/beatNow/getHistory/onBeat/onState/isBatteryExempt/requestBatteryExemption/Beat` from `./modules/tor-manager`.

- [ ] **Step 1: Write `App.tsx`**

```tsx
import React, { useCallback, useEffect, useState } from "react";
import { Button, FlatList, SafeAreaView, StyleSheet, Text, View } from "react-native";
import {
  Beat, beatNow, getHistory, isBatteryExempt, onBeat, onState,
  requestBatteryExemption, startNode, stopNode,
} from "./modules/tor-manager";

export default function App() {
  const [state, setState] = useState("stopped");
  const [beats, setBeats] = useState<Beat[]>([]);
  const [exempt, setExempt] = useState(true);

  const refresh = useCallback(async () => setBeats(await getHistory()), []);

  useEffect(() => {
    setExempt(isBatteryExempt());
    refresh();
    const offState = onState(setState);
    const offBeat = onBeat((b) => setBeats((prev) => [b, ...prev].slice(0, 50)));
    return () => { offState(); offBeat(); };
  }, [refresh]);

  return (
    <SafeAreaView style={styles.container}>
      <Text style={styles.title}>Kreds node</Text>
      <Text style={styles.state}>state: {state}</Text>
      {!exempt && (
        <View style={styles.warn}>
          <Text style={styles.warnText}>Battery optimization may kill the node.</Text>
          <Button title="Exempt Kreds from battery optimization"
            onPress={() => { requestBatteryExemption(); setTimeout(() => setExempt(isBatteryExempt()), 500); }} />
        </View>
      )}
      <View style={styles.row}>
        <Button title="Start node" onPress={startNode} />
        <Button title="Stop node" onPress={stopNode} />
        <Button title="Beat now" onPress={beatNow} />
      </View>
      <Text style={styles.subtitle}>Heartbeats ({beats.length})</Text>
      <FlatList
        style={styles.list}
        data={beats}
        keyExtractor={(b) => String(b.ts)}
        renderItem={({ item }) => (
          <Text style={item.ok ? styles.ok : styles.fail}>
            {new Date(item.ts).toLocaleTimeString()} {item.ok ? `OK ${item.latencyMs}ms` : `FAIL ${item.reason}`}
          </Text>
        )}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 20, gap: 10 },
  title: { fontSize: 22, fontWeight: "700" },
  subtitle: { fontSize: 16, fontWeight: "600", marginTop: 8 },
  state: { fontSize: 16 },
  row: { flexDirection: "row", gap: 8, flexWrap: "wrap" },
  list: { flex: 1 },
  ok: { fontSize: 14, color: "#1a7f37", paddingVertical: 2 },
  fail: { fontSize: 14, color: "#b00020", paddingVertical: 2 },
  warn: { backgroundColor: "#fff3cd", padding: 10, borderRadius: 6, gap: 6 },
  warnText: { fontSize: 14 },
});
```

- [ ] **Step 2: Build both variants + install** (`.\gradlew assembleDebug assembleRelease`, then `adb -s ZY32DLZQ2N install -r ...\app-release.apk`). Expected: `BUILD SUCCESSFUL`, `Success`.

- [ ] **Step 3: Commit**

```powershell
cd C:\Users\Wong\Desktop\Hearth
git add android_tor_spike/app/App.tsx
git commit -m "feat(brick-a): node dashboard screen - start/stop/beat-now, live state+beats, battery-exemption prompt, history list"
```

---

### Task 9: On-device verification + report

**Files:**
- Create: `android_tor_spike/tools/brick_a_ondevice.ps1`
- Create: `android_tor_spike/BRICK_A_REPORT.md`

**Interfaces:** Consumes the installed release APK, the pushed fixture, the running desktop node.

- [ ] **Step 1: Write the adb verification script** `android_tor_spike/tools/brick_a_ondevice.ps1`:

```powershell
# Brick A on-device checks. Desktop Kreds must be ONLINE over Tor and the
# fixture already minted+pushed (see ON_DEVICE_CHECKLIST.md). Dot-source env first.
param([string]$Serial = "ZY32DLZQ2N")
function Beats { adb -s $Serial logcat -d -s TorNodeService:* | Select-String "beat" }

Write-Output "== confirm app + fixture =="
adb -s $Serial shell ls -l /sdcard/Android/data/eu.kreds.torspike/files/spike_phone_fixture.json
Write-Output "== force Doze, wait 6 min, expect a beat to still land =="
adb -s $Serial shell dumpsys deviceidle force-idle
Write-Output "(leave it; check the notification / rerun Beats after ~6 min)"
Write-Output "== to restore from Doze =="
Write-Output "adb -s $Serial shell dumpsys deviceidle unforce"
Write-Output "== process-death recovery: kill, confirm START_STICKY restart + resumed beats =="
Write-Output "adb -s $Serial shell am kill eu.kreds.torspike   # then watch Beats"
Write-Output "== pull tor.log if a beat fails =="
Write-Output "adb -s $Serial pull /sdcard/Android/data/eu.kreds.torspike/files/tor.log android_tor_spike\"
```

- [ ] **Step 2: Hand August the run** (per testing-workflow-division). August: start desktop node online; open the app; grant battery exemption; Start node; confirm beats land (notification + list); background the app and confirm beats continue; run the Doze force-idle + `am kill` checks; ~1-hour survival read. Collect: does a beat land while backgrounded? while Doze-forced? after `am kill`? rough battery. **PAUSE — human-driven.**

- [ ] **Step 3: Write `BRICK_A_REPORT.md`** with the observed results (verdict per DoD bullet: background continuity, Doze survival, process-death recovery, ~1h survival + battery, exemption prompt), any bugs found + fixes, and what Brick B inherits. No placeholders — fill every result from the actual run.

- [ ] **Step 4: Commit**

```powershell
cd C:\Users\Wong\Desktop\Hearth
git add android_tor_spike/tools/brick_a_ondevice.ps1 android_tor_spike/BRICK_A_REPORT.md
git commit -m "docs(brick-a): on-device verification script + report - background/Doze/process-death results"
```

---

## Self-Review (performed at write time)

**Spec coverage:** foreground Service owning Tor → Task 5; Tor-ownership Activity→Service extraction → Task 1; Kotlin heartbeat (vector-gated) → Tasks 2+3; heartbeat history ring buffer → Task 4; notification + observability → Tasks 5+8; battery-exemption prompt → Tasks 6+7+8; manifest/foreground-service-type/permissions → Task 6; RN status screen → Task 8; Doze/process-death/battery on-device verification → Task 9; interval N=5min → Task 5 constant; enrollment stays fixture stub → Task 5 `fixture()`. Scope boundaries (no content sync, no real pairing, no iOS) respected.

**Type consistency:** `Beat{ts,ok,latencyMs,reason}` identical across Kotlin (Task 4), the module map (Task 7), and TS (Tasks 7/8). `KotlinWire.CertDict` / `PyFloat` / `authBody` / `certBody` used consistently in Tasks 2/3. `TorEngine` method signatures (Task 1) match their callers in Tasks 3/5. Service broadcast actions/extras (Task 5) match the receiver (Task 7). `HandshakeResult` variants (Task 3) match the `heartbeat()` `when` (Task 5).

**Known judgment calls (flagged):**
- The Kotlin probe uses blocking sequential `recv` rather than JS's grace-race; the reasoning and the fallback are documented inline in Task 3. Runtime-proven by Task 9.
- `foregroundServiceType="dataSync"` chosen for API-34 forward-compat though the G20 is API 30 (no type required); flagged in the spec risks. If a future device on API 35+ hits the dataSync 6-hour cap, revisit with `connectedDevice`.
- Notification uses a placeholder system icon (`stat_sys_data_bluetooth`); a real icon is out of Brick A's scope (UI polish).
