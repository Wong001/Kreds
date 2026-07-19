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
 *   3. phoneDecryptsRealBackfilledContent (Task 6, B.2) -- the end-to-end
 *      decrypt gate: sync 1 authenticates, then runs KotlinSync.run with the
 *      phone's device-signed enckey as `outbound` (KotlinSync.composeEncKey,
 *      Task 4), pushing it into the node's real store via the SAME generic
 *      MESSAGES-phase ingestion every other synced message goes through --
 *      no script-side injection. node.awaitMaintained() then blocks on the
 *      node process's "maintained" signal line (sync_loopback_node.py's
 *      wrapped _on_conn, Task 6) -- a deterministic proof that
 *      node.maintain_own_device_grants() (Task 2) has ALREADY run against
 *      the just-ingested enckey before this test opens a second connection,
 *      rather than racing that timing. Sync 2 (a fresh connection, plain
 *      pull, no outbound) then pulls the resulting wrap_grants. DecryptPass.
 *      run (Task 5) is asserted to reproduce the exact seeded plaintexts,
 *      and -- since the precondition (none of the seeded posts carry an
 *      inline wrap to the phone; they predate the enckey) is asserted first
 *      -- every successful decrypt is proven to have come via a backfilled
 *      wrap_grant, not an inline wrap.
 *   4. phoneReadsFriendContentEndToEnd (Task 4, B.2c) -- the two-node desk
 *      gate: startNode("two_node") spawns the SAME script with a second,
 *      real in-process friend node ("Freja") befriended with the desk node
 *      (sync_loopback_node.py's _seed_friend_and_befriend), already holding
 *      two kreds wall posts and an OLD DM to the desk node BEFORE this test
 *      ever connects. Sync 1 pushes the phone's enckey exactly like test 3
 *      above; node.awaitMaintained() then blocks on the node process's
 *      Task-4 choreography (_run_phase3): the enckey gossips to the friend
 *      node, friend's STOCK (untouched) maintain_wrap_grants mints wall
 *      grants naming the phone, the desk node's maintain_received_dm_grants
 *      backfills the OLD dm via a RECIPIENT-signed grant, the friend then
 *      composes a NEW dm that inline-wraps the phone directly (its enckey
 *      is known by now), and everything gossips back to the desk node. Sync
 *      2 pulls all of it over the real wire. Assertions: both friend wall
 *      texts, the OLD dm (via a grant independently verified to be SIGNED
 *      BY THE OWN IDENTITY -- the recipient-signed backfill, not trusted
 *      via DecryptPass's own resolution), the NEW dm (independently
 *      verified INLINE-wrapped, not via any grant), the original B.2
 *      own-content regression, and author-name resolution to the friend
 *      node's published profile name ("Freja").
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
                               private val proc: Process, private val stdout: BufferedReader) {
        fun kill() {
            proc.destroy()
            if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly()
        }

        /** Task 6 (B.2): blocks until the node process prints its next
         *  "maintained" signal line -- emitted once per connection, right
         *  after node.maintain_own_device_grants() has FULLY run for that
         *  connection (sync_loopback_node.py's wrapped _on_conn). This is
         *  the deterministic handoff for the two-sync flow: without it,
         *  opening a second connection immediately after the first one's
         *  KotlinSync.run() returns would be racing this process's
         *  server-side maintenance sweep instead of waiting on it. */
        fun awaitMaintained() {
            val line = stdout.readLine()
                ?: throw RuntimeException(
                    "node process closed stdout before printing a " +
                    "maintained-signal line")
            val obj = JSONObject(line)
            if (obj.optString("event") != "maintained") {
                throw RuntimeException(
                    "expected a maintained-signal line, got: $line")
            }
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

    /** `scenario`: null (every pre-Task-4 call site) reproduces the
     *  ORIGINAL single-node invocation byte-for-byte (sync_loopback_node.py
     *  defaults to "solo" with no second arg). "two_node" (Task 4, B.2c)
     *  passes the opt-in scenario flag that spawns the second, real friend
     *  node -- see that script's module docstring. */
    private fun startNode(scenario: String? = null): NodeProcess {
        val repo = findRepoRoot()
        val venvPy = File(repo, ".venv/Scripts/python.exe")
        val script = File(repo, "android_tor_spike/tools/sync_loopback_node.py")
        val tmp = Files.createTempDirectory("syncgate").toFile()
        val args = mutableListOf(venvPy.absolutePath, script.absolutePath, tmp.absolutePath)
        if (scenario != null) args.add(scenario)
        val proc = ProcessBuilder(args)
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
        return NodeProcess(port, fx.toString(), info.getJSONObject("expect"), proc, stdout)
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

    @Test fun phoneDecryptsRealBackfilledContent() {
        val node = startNode()
        try {
            val fixture = KotlinHandshake.parseFixture(node.fixtureJson)
            val phoneDevicePub = fixture.device_pub

            val store = InMemorySyncStore()
            store.addIdentity(fixture.cert.identity_pub)
            val (encPriv, encPub) = EncKeys.getOrCreate(store)

            // -- Sync 1: authenticate, then push the phone's device-signed
            // enckey message alongside the normal pull. This is the REAL
            // path (Task 4's outbound + hearth/sync.py's already-generic
            // MESSAGES-phase ingestion, unmodified) -- not script-side
            // injection.
            val stream1 = SocketStream("127.0.0.1", node.port)
            KotlinHandshake.authOnlyOverStream(stream1, fixture)
            val encKeyMsg = KotlinSync.composeEncKey(
                fixture, encPub, store.nextSeq(), System.currentTimeMillis() / 1000.0)
            val res1 = KotlinSync.run(stream1, store, phoneDevicePub, outbound = listOf(encKeyMsg))
            assertTrue("sync 1 (push enckey): $res1", res1 is SyncResult.Ok)

            // Deterministic handoff: block until the node process confirms
            // node.maintain_own_device_grants() has ALREADY run against the
            // enckey this connection just delivered -- see NodeProcess.
            // awaitMaintained's doc. Without this, sync 2 below could open
            // and complete before the node's sweep, and the wrap_grant
            // assertions further down would be racing server-side timing
            // instead of testing real behavior.
            node.awaitMaintained()

            // Precondition (RED-proving if it fails): the seeded posts
            // predate the phone's enckey, so NONE of them may carry an
            // inline wrap to this device yet. If this precondition doesn't
            // hold, the decrypts below could succeed via the inline-wrap
            // path instead of proving the wrap_grant backfill path -- the
            // whole point of this gate.
            val posts = store.allMessages().filter { it.kind == "post" }
            assertEquals("seeded posts", 3, posts.size)
            for (m in posts) {
                @Suppress("UNCHECKED_CAST")
                val wraps = m.payload["wraps"] as? Map<String, Any?>
                assertTrue(
                    "post ${m.msgId} must not already be inline-wrapped to the phone " +
                        "(it predates the enckey) -- precondition for the wrap_grant proof below",
                    wraps == null || phoneDevicePub !in wraps)
            }

            // -- Sync 2: a fresh connection, plain pull (no outbound) --
            // pulls the wrap_grants node.maintain_own_device_grants() minted
            // against the enckey pushed in sync 1.
            val stream2 = SocketStream("127.0.0.1", node.port)
            KotlinHandshake.authOnlyOverStream(stream2, fixture)
            val res2 = KotlinSync.run(stream2, store, phoneDevicePub)
            assertTrue("sync 2 (pull grants): $res2", res2 is SyncResult.Ok)

            val decrypted = DecryptPass.run(store, phoneDevicePub, encPriv, fixture.cert.identity_pub)
            val expectedTexts = setOf("hello from desk", "second post, still text", "with pic")
            assertEquals("decrypted text must match the seeded post bodies exactly",
                expectedTexts, decrypted.map { it.text }.toSet())
            assertEquals(expectedTexts.size, decrypted.size)

            // Every decrypted post must have its content key resolved via a
            // backfilled wrap_grant -- the precondition above already ruled
            // out the inline-wrap path, so this confirms the grant path is
            // the one actually exercised, not merely assumed.
            var viaGrant = 0
            for (m in posts) {
                if (store.wrapGrantsFor(m.msgId, setOf(m.identityPub)).any { phoneDevicePub in it }) viaGrant++
            }
            assertEquals("every seeded post must be covered by a wrap_grant to the phone",
                posts.size, viaGrant)
        } finally {
            node.kill()
        }
    }

    @Test fun phoneReadsFriendContentEndToEnd() {
        val node = startNode(scenario = "two_node")
        try {
            val fixture = KotlinHandshake.parseFixture(node.fixtureJson)
            val phoneDevicePub = fixture.device_pub
            val ownIdentityPub = fixture.cert.identity_pub

            val store = InMemorySyncStore()
            store.addIdentity(ownIdentityPub)
            val (encPriv, encPub) = EncKeys.getOrCreate(store)

            // -- Sync 1: authenticate, push the phone's device-signed
            // enckey -- the real path (composeEncKey + the generic MESSAGES-
            // phase ingestion), exactly as in phoneDecryptsRealBackfilledContent
            // above. This is what the node process's Task-4 choreography
            // (sync_loopback_node.py's _run_phase3) reacts to.
            val stream1 = SocketStream("127.0.0.1", node.port)
            KotlinHandshake.authOnlyOverStream(stream1, fixture)
            val encKeyMsg = KotlinSync.composeEncKey(
                fixture, encPub, store.nextSeq(), System.currentTimeMillis() / 1000.0)
            val res1 = KotlinSync.run(stream1, store, phoneDevicePub, outbound = listOf(encKeyMsg))
            assertTrue("sync 1 (push enckey): $res1", res1 is SyncResult.Ok)

            // Deterministic handoff (see NodeProcess.awaitMaintained's doc
            // and sync_loopback_node.py's _run_phase3): blocks until the
            // enckey has gossiped to the friend node, friend's STOCK
            // maintain_wrap_grants has minted the wall grants naming the
            // phone, the desk node's maintain_received_dm_grants has
            // backfilled the old DM, the friend's new DM has been composed,
            // and everything has gossiped back to the desk node -- all of
            // it BEFORE this test opens the second connection below.
            node.awaitMaintained()

            // -- Sync 2: fresh connection, plain pull -- brings in the
            // friend's wall posts + wrap_grant, the old DM + the desk
            // node's recipient-signed grant, and the new (now inline-
            // wrapped) DM, alongside the original B.2 own-identity content.
            val stream2 = SocketStream("127.0.0.1", node.port)
            KotlinHandshake.authOnlyOverStream(stream2, fixture)
            val res2 = KotlinSync.run(stream2, store, phoneDevicePub)
            assertTrue("sync 2 (pull friend content): $res2", res2 is SyncResult.Ok)

            val decrypted = DecryptPass.run(store, phoneDevicePub, encPriv, ownIdentityPub)
            val texts = decrypted.map { it.text }.toSet()

            // Own-content regression: the original B.2 desk posts must
            // still decrypt alongside the new friend content.
            val ownTexts = setOf("hello from desk", "second post, still text", "with pic")
            assertTrue("own B.2 content must still decrypt: $texts", texts.containsAll(ownTexts))

            // Friend wall content, via friend's STOCK, untouched
            // maintain_wrap_grants -- the "no friend-side-change" claim.
            assertTrue("friend wall post one must decrypt: $texts",
                texts.contains("friend wall post one"))
            assertTrue("friend wall post two must decrypt: $texts",
                texts.contains("friend wall post two"))

            // New DM: composed by the friend AFTER the phone's enckey was
            // already known to it, so it inline-wraps the phone directly.
            // Verified independently of DecryptPass -- not merely that the
            // text decrypted, but that it decrypted via the INLINE path.
            assertTrue("new dm from friend must decrypt: $texts", texts.contains("new dm from friend"))
            val newDmId = decrypted.single { it.text == "new dm from friend" }.msgId
            val newDmMsg = store.allMessages().single { it.msgId == newDmId }
            @Suppress("UNCHECKED_CAST")
            val newDmWraps = newDmMsg.payload["wraps"] as? Map<String, Any?>
            assertTrue(
                "new dm from friend must be INLINE-wrapped to the phone " +
                    "(composed after the phone's enckey was already known)",
                newDmWraps != null && phoneDevicePub in newDmWraps)

            // Old DM: composed BEFORE the phone's enckey existed anywhere,
            // so it can only decrypt via a BACKFILLED grant -- precondition
            // rules out the inline path, mirroring
            // phoneDecryptsRealBackfilledContent's own precondition check.
            assertTrue("old dm from friend must decrypt: $texts", texts.contains("old dm from friend"))
            val oldDmId = decrypted.single { it.text == "old dm from friend" }.msgId
            val oldDmMsg = store.allMessages().single { it.msgId == oldDmId }
            @Suppress("UNCHECKED_CAST")
            val oldDmWraps = oldDmMsg.payload["wraps"] as? Map<String, Any?>
            assertTrue(
                "old dm from friend must NOT be inline-wrapped to the phone (it predates the enckey)",
                oldDmWraps == null || phoneDevicePub !in oldDmWraps)
            // Independent grant-path check (mirrors
            // phoneDecryptsRealBackfilledContent's viaGrant check, adapted
            // for a RECEIVED dm): query wrapGrantsFor with acceptedSigners
            // restricted to ONLY the own identity (never the friend, who
            // authored the DM but never grants it) -- proving the covering
            // grant is the RECIPIENT-signed backfill (hearth's
            // maintain_received_dm_grants) specifically, independent of
            // DecryptPass's own (wider) entitlement resolution.
            val ownSignedGrants = store.wrapGrantsFor(oldDmId, setOf(ownIdentityPub))
            assertTrue(
                "old dm's covering grant must be signed by the OWN identity (recipient-signed backfill)",
                ownSignedGrants.any { phoneDevicePub in it })

            // Author-name resolution (B.2c Task 3): friend-authored content
            // resolves to the friend node's published profile name, not a
            // raw-id fallback -- proves the friend's profile message was
            // itself delivered, not just its content.
            val friendTexts = setOf("friend wall post one", "friend wall post two", "new dm from friend", "old dm from friend")
            val friendAuthors = decrypted.filter { it.text in friendTexts }.map { it.author }.toSet()
            assertEquals("friend-authored content must resolve to Freja's profile name",
                setOf("Freja"), friendAuthors)
        } finally {
            node.kill()
        }
    }
}
