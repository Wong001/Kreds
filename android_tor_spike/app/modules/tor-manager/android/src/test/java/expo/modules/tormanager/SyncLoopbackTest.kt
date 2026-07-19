package expo.modules.tormanager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Socket
import java.nio.file.Files

/** BB-5, the desk loopback gate: the spine gate for Brick B.1's content
 *  sync. Spawns a REAL seeded Python node (sync_loopback_node.py) and drives
 *  Kotlin AUTH + sync against it over plain loopback TCP -- no Tor, no
 *  TorEngine -- mirroring the desk pattern test_handshake_desk.py already
 *  proved for AUTH alone, extended all the way through sync into an
 *  InMemorySyncStore. If this passes, the whole Kotlin sync port (framing,
 *  crypto, phase sequencing, store ingestion) is proven against the real
 *  protocol before the phone ever runs it.
 *
 *  Two connections to the one seeded node, each proving a different thing:
 *   1. authProbeConnectionIsAccepted -- KotlinHandshake.runOverStream's
 *      HELLO/AUTH+acceptance-probe logic (the required refactor), run over
 *      a real Stream/TCP socket instead of TorEngine, against a real node.
 *      Closed once the verdict is read; nothing else happens on it.
 *   2. syncsRealOwnIdentityContent -- the actual sync proof. Discovered
 *      while building this gate: runOverStream's probe is not a disposable
 *      ping, it consumes the node's real once-per-connection REVOCATIONS
 *      phase (hearth/sync.py _session, responder side: read-then-write).
 *      Chaining KotlinSync.run (whose own first phase also sends
 *      "revocations") straight onto a stream that already went through
 *      runOverStream's probe sends that frame twice; the node only expects
 *      it once, and the session desyncs one phase at a time until the
 *      socket dies (reproduced here: runOverStream returned Accepted, then
 *      KotlinSync.run failed a couple of frames later with a
 *      SocketException). So this connection authenticates via the new
 *      KotlinHandshake.authOnlyOverStream (HELLO+AUTH, no probe, stream
 *      stays open) and hands straight off to KotlinSync.run, whose own
 *      first phase performs the (single, correctly-placed) REVOCATIONS
 *      round trip. Two independent connections to the same node is fine --
 *      hearth/sync.py's _on_conn runs an independent _session per
 *      connection and nothing here mutates state connection 1 depends on.
 */
class SyncLoopbackTest {

    // Minimal blocking Stream over a TCP socket (JVM-only, test-side) --
    // mirrors TorStream's shape (Stream.kt) so KotlinHandshake/KotlinSync run
    // unmodified over it.
    private class SocketStream(host: String, port: Int) : Stream {
        private val sock = Socket(host, port).apply { soTimeout = 30000 }
        private val inp = sock.getInputStream()
        private val out = sock.getOutputStream()
        override fun readExactSync(n: Int): ByteArray {
            val b = ByteArray(n); var off = 0
            while (off < n) {
                val r = inp.read(b, off, n - off)
                if (r < 0) throw RuntimeException("EOF after $off/$n bytes")
                off += r
            }
            return b
        }
        override fun write(bytes: ByteArray) { out.write(bytes); out.flush() }
        override fun close() { sock.close() }
    }

    private class NodeProcess(val port: Int, val fixtureJson: String, val expect: JSONObject,
                               private val proc: Process) {
        fun kill() {
            proc.destroy()
            if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly()
        }
    }

    /** Walk up from the JVM test's working directory looking for the repo
     *  root, identified by the two things this test actually needs:
     *  android_tor_spike/tools/sync_loopback_node.py and
     *  .venv/Scripts/python.exe. Gradle's default Test task working
     *  directory is the declaring module's projectDir
     *  (.../modules/tor-manager/android), several levels below the repo
     *  root -- but hardcoding a fixed number of parentFile hops is brittle
     *  against any future gradle/module reshuffle, so this searches instead
     *  of counting. */
    private fun findRepoRoot(): File {
        val userDir = System.getProperty("user.dir")
            ?: throw RuntimeException("user.dir system property is not set")
        var dir: File? = File(userDir).absoluteFile
        var hops = 0
        while (hops < 12) {
            val cur = dir ?: break
            val script = File(cur, "android_tor_spike/tools/sync_loopback_node.py")
            val venvPy = File(cur, ".venv/Scripts/python.exe")
            if (script.isFile && venvPy.isFile) return cur
            dir = cur.parentFile
            hops++
        }
        throw RuntimeException(
            "could not locate the repo root by walking up from user.dir=" +
            "${System.getProperty("user.dir")} (looked for " +
            "android_tor_spike/tools/sync_loopback_node.py + " +
            ".venv/Scripts/python.exe at each level)")
    }

    private fun startNode(): NodeProcess {
        val repo = findRepoRoot()
        val venvPy = File(repo, ".venv/Scripts/python.exe")
        val script = File(repo, "android_tor_spike/tools/sync_loopback_node.py")
        val tmp = Files.createTempDirectory("syncgate").toFile()
        val proc = ProcessBuilder(venvPy.absolutePath, script.absolutePath, tmp.absolutePath)
            .redirectErrorStream(false)
            .start()
        val stdout = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
        val line = stdout.readLine()
        if (line == null) {
            val err = proc.errorStream.bufferedReader().readText()
            proc.destroy()
            throw RuntimeException(
                "no handshake line from sync_loopback_node.py " +
                "(venvPy=${venvPy.absolutePath} exists=${venvPy.isFile}, " +
                "script=${script.absolutePath} exists=${script.isFile}); stderr:\n$err")
        }
        val info = JSONObject(line)
        val fx = info.getJSONObject("fixture")
        val port = info.getInt("port")
        return NodeProcess(port, fx.toString(), info.getJSONObject("expect"), proc)
    }

    @Test fun authProbeConnectionIsAccepted() {
        val node = startNode()
        try {
            val fixture = KotlinHandshake.parseFixture(node.fixtureJson)
            val stream = SocketStream("127.0.0.1", node.port)
            val hs = KotlinHandshake.runOverStream(stream, fixture)
            assertTrue("auth: $hs", hs is KotlinHandshake.HandshakeResult.Accepted)
            stream.close()   // Accepted leaves it open for a chained caller; this test has none.
        } finally {
            node.kill()
        }
    }

    @Test fun syncsRealOwnIdentityContent() {
        val node = startNode()
        try {
            val fixture = KotlinHandshake.parseFixture(node.fixtureJson)
            val stream = SocketStream("127.0.0.1", node.port)

            val peerCert = KotlinHandshake.authOnlyOverStream(stream, fixture)
            assertEquals("node cert identity", fixture.cert.identity_pub, peerCert.identity_pub)

            val store = InMemorySyncStore()
            store.addIdentity(fixture.cert.identity_pub)
            val res = KotlinSync.run(stream, store, fixture.device_pub)
            assertTrue("sync: $res", res is SyncResult.Ok)

            val stats = store.stats()
            val expect = node.expect
            assertEquals("messages", expect.getInt("messages"), stats.messages)
            assertEquals("blobs", expect.getInt("blobs"), stats.blobs)
            assertEquals("identities", expect.getInt("identities"), stats.identities)
        } finally {
            node.kill()
        }
    }
}
