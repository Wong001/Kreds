package expo.modules.tormanager

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.nio.file.Files
import java.security.SecureRandom

/** Task 6 (android first-load pairing, spec 2026-07-22-android-first-load-
 *  pairing-design): the LOOPBACK CEREMONY GATE -- the parity proof of the
 *  whole first-load/pairing slice. Every earlier task (1-5, all done on
 *  this branch before this file existed) was proven either by hearth-side
 *  wire tests (Task 2's tests/test_pair_wire.py) or by Kotlin-JVM tests
 *  against FAKE Streams (KotlinPairingTest.kt's ScriptedStream/
 *  FixedReplyStream/HangingStream). This file is the first place the two
 *  halves actually meet: KotlinPairing.runCeremony (Tasks 3-4) dialing a
 *  REAL socket against a REAL hearth node (sync_loopback_node.py's new
 *  "pairing"/"pairing_deny" scenarios, Task 6 Step 1) whose pre-auth
 *  `hearth-pair-request` wire handler is Task 2's actual, unmodified,
 *  production _handle_pair_request -- the SAME listener every other
 *  *LoopbackTest.kt file in this module already syncs real content
 *  against (SyncLoopbackTest.kt's own class doc: "If this passes, the
 *  whole Kotlin sync port ... is proven against the real protocol before
 *  the phone ever runs it" -- this file extends that same claim to the
 *  ceremony that PRECEDES sync).
 *
 *  Harness: reuses SyncLoopbackTest.kt's SocketStream/spawnNode/
 *  findRepoRoot verbatim (spawnNode was hoisted out of startNode's body
 *  for exactly this reuse -- see its own doc comment there) via a new,
 *  small startPairingNode/PairingNodeProcess pair below, since the
 *  "pairing"/"pairing_deny" scenarios print a differently-shaped first
 *  line ({"event":"pair_ready",...}, no mint_fixture at all -- the whole
 *  point of Task 6 is the phone gets its identity FROM the ceremony) than
 *  every other scenario's {"port":...,"fixture":...,"expect":...} line.
 *
 *  Three tests, each spawning its own fresh node process (mirrors every
 *  other *LoopbackTest.kt file's per-@Test startNode() convention):
 *
 *  1. happyPathLinksPersistsAndSyncsRealContent -- the full positive
 *     path. runCeremony dials the real node, the node's real
 *     _drive_pair_accept auto-accepts (driving the EXACT node-level
 *     operations hearth/api.py's POST /api/pair/accept executes --
 *     node.accept_pairing, never a shortcut), asserts Linked, asserts
 *     pairing.json's persisted identity_priv equals the node's REAL
 *     identity_priv (the "ultimate full-pairing assertion", task brief) --
 *     not a stand-in value, the actual root key the ceremony was supposed
 *     to hand over. THEN builds a KotlinHandshake.Fixture from that
 *     persisted identity and runs the exact same authOnlyOverStream +
 *     KotlinSync.run path every other loopback gate in this module uses,
 *     against a FRESH connection to the SAME node -- proving the cert
 *     accept_pairing minted is not merely well-formed but genuinely
 *     ACCEPTED by the node's own AUTH admission gate, and that the
 *     resulting sync pulls the seeded journal post.
 *
 *  2. denyReturnsDeniedNothingPersistedAndUnenrolledSyncRefused -- the
 *     negative path. The node's driver always denies; runCeremony must
 *     return Denied with NOTHING persisted and the store untouched. The
 *     harder half (negative auth proof, task brief: "assert the failure
 *     mode precisely, don't just try/catch-pass"): a device that was
 *     NEVER handed identity_priv (by construction -- Denied carries no
 *     key material) cannot mint a cert the node will recognize as this
 *     identity's; the only cert such a device COULD self-sign names some
 *     OTHER identity entirely (one it minted itself), which the node's
 *     own is_known gate (hearth/sync.py _session:629-631) correctly
 *     refuses. Driven through authOnlyOverStream + KotlinSync.run -- the
 *     SAME sync-client chain every other real-node test in this module
 *     uses for the ACCEPTED case -- and verified (see the test's own long
 *     comment) to fold to either the idealized SyncResult.Failed(
 *     "revocations","refused") or a transport-level abort, per a
 *     pre-existing, now-confirmed platform quirk KotlinHandshake.kt's own
 *     doc comment already flagged as unverified; a successful sync is the
 *     only outcome that would actually fail this test.
 *
 *  3. wrongCodeIsExpiredBeforeAnyPendingAndRealCodeSurvives -- presents a
 *     link with the SAME dialable address but a code that can never match
 *     (sync_loopback_node.py's `wrong_code_link`, produced by the REAL
 *     hearth invitecodec.encode_pair -- the Kotlin side has no pair-link
 *     ENCODER, only decodeLink, so the harness itself must be the one to
 *     produce a well-formed-but-wrong link). Asserts Expired. "Nothing
 *     parked" is proven indirectly but concretely, per the task brief's
 *     own allowance ("just the Kotlin-side result if the harness can't
 *     easily observe"): hearth's single-active-ceremony admission check
 *     (sync.py:456-467) would refuse ANY second request -- including a
 *     genuinely valid one -- while an earlier one is still parked, so a
 *     REAL ceremony against the ORIGINAL correct link succeeding
 *     immediately afterward is proof nothing from the wrong-code attempt
 *     was ever parked, AND (pairingcodes.py's verify_and_consume: "a
 *     wrong ... attempt leaves the active code untouched") that the real
 *     code survived the wrong guess rather than being silently burned. */
class SyncPairLoopbackTest {

    // -- pairing-scenario process wrapper (Task 6) ---------------------------

    /** Blocks until the node process prints a line whose "event" field
     *  equals `name` -- same generalized-skip-past-other-lines shape as
     *  NodeProcess.awaitEvent (SyncLoopbackTest.kt), duplicated rather
     *  than shared: NodeProcess is keyed to the OTHER scenarios' port/
     *  fixture/expect construction (its constructor requires all three),
     *  which the pairing scenarios never print at all -- reusing it would
     *  mean widening NodeProcess's constructor for a shape it structurally
     *  doesn't have, a bigger touch than this task's "strictly-needed
     *  hoist" allowance covers (only the common process-spawn plumbing,
     *  spawnNode, was hoisted). */
    private class PairingNodeProcess(
        val link: String,
        val wrongCodeLink: String,
        val identityPriv: String,
        val identityPub: String,
        val friendIdentityPub: String,
        private val proc: Process,
        private val stdout: BufferedReader,
    ) {
        fun kill() {
            proc.destroy()
            if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly()
        }

        fun awaitEvent(name: String): JSONObject {
            while (true) {
                val line = stdout.readLine()
                    ?: throw RuntimeException(
                        "node process closed stdout before printing a " +
                        "\"$name\" event line")
                val obj = JSONObject(line)
                if (obj.optString("event") == name) return obj
            }
        }
    }

    /** `deny`: false (default) spawns the "pairing" scenario (auto-accept
     *  driver); true spawns "pairing_deny" (auto-deny driver) -- see
     *  sync_loopback_node.py's _run_pairing/_drive_pair_accept docstrings
     *  for the full contract. Reuses spawnNode -- the SAME repo-root
     *  discovery, venv python, and "no line -> dump stderr" diagnostics
     *  startNode uses -- but parses the differently-shaped pair_ready
     *  line instead of a fixture/port/expect one. */
    private fun startPairingNode(deny: Boolean = false): PairingNodeProcess {
        val (proc, stdout, line) = spawnNode(if (deny) "pairing_deny" else "pairing")
        val info = JSONObject(line)
        if (info.optString("event") != "pair_ready") {
            proc.destroy()
            throw RuntimeException("expected a pair_ready line, got: $line")
        }
        return PairingNodeProcess(
            info.getString("link"), info.getString("wrong_code_link"),
            info.getString("identity_priv"), info.getString("identity_pub"),
            info.getString("friend_identity_pub"), proc, stdout)
    }

    /** The dial lambda every runCeremony call in this file uses: a real
     *  TCP socket to the loopback node, wrapped in the module's own
     *  Stream type -- the SAME SocketStream every other *LoopbackTest.kt
     *  file in this module dials real content sync against (hoisted
     *  top-level, internal, in SyncLoopbackTest.kt), not a fake. */
    private val realDial: (String, Int) -> Stream = { host, port -> SocketStream(host, port) }

    // -- fresh-keypair helpers for the negative-auth-proof test -------------
    // Mirrors KotlinPairingTest.kt's own genKeypair/signedCert (that file's
    // private helpers, not shared -- each test file in this module mints
    // its own small crypto fixtures rather than reaching into another
    // test file's private members).

    private fun genKeypair(): Pair<String, String> {
        val p = Ed25519PrivateKeyParameters(SecureRandom())
        return KotlinWire.toHex(p.encoded) to KotlinWire.toHex(p.generatePublicKey().encoded)
    }

    private fun signedCert(
        identityPriv: String, identityPub: String, devicePub: String,
        name: String = "Rogue Phone", enrolledAt: Double = 1752900000.0,
    ): KotlinWire.CertDict {
        val unsigned = KotlinWire.CertDict(identityPub, devicePub, name, enrolledAt, "")
        return unsigned.copy(signature = KotlinWire.signRaw(identityPriv, KotlinWire.certBody(unsigned)))
    }

    // =========================================================================

    @Test fun happyPathLinksPersistsAndSyncsRealContent() {
        val node = startPairingNode()
        try {
            val store = InMemorySyncStore()
            val dir = Files.createTempDirectory("pair-ceremony-linked").toFile()

            val result = KotlinPairing.runCeremony(
                dial = realDial, link = node.link, deviceName = "Test Phone",
                timeoutMs = 15000, store = store, dir = dir)

            assertTrue("expected Linked, got $result", result is KotlinPairing.CeremonyResult.Linked)
            val identity = (result as KotlinPairing.CeremonyResult.Linked).identity

            // The ULTIMATE full-pairing assertion (task brief): the
            // persisted identity_priv is the node's REAL identity_priv --
            // not a stand-in -- proving the ceremony genuinely carried the
            // node's own root key to the phone over the real wire.
            assertEquals("persisted identity_priv must equal the node's real identity_priv",
                node.identityPriv, identity.identity_priv)
            assertEquals(node.identityPub, identity.cert.identity_pub)

            // The REAL /api/pair/accept node-level path enrolled this exact
            // device -- confirmed independently by the node process reading
            // it back off node.store.load_views (sync_loopback_node.py's
            // _drive_pair_accept), not merely trusted from the wire reply.
            val paired = node.awaitEvent("paired")
            assertEquals(identity.device_pub, paired.getString("device_pub"))

            // pairing.json actually persisted (full round trip through the
            // real ceremony, not a hand-built package).
            val loaded = PairingStore.load(dir)
            assertEquals(identity, loaded)

            // accept_pairing's own `friends` list (node.py:2032-2033) rode
            // along INSIDE THE PACKAGE ITSELF, before any sync ever ran.
            assertTrue("installPackage must have added self",
                store.knownIdentities().contains(node.identityPub))
            assertTrue("installPackage must have added the friend from the package's friends list",
                store.knownIdentities().contains(node.friendIdentityPub))

            // -- THEN a REAL authenticated sync with the freshly-persisted
            // fixture -- NOT the pre-baked mint_fixture path every OTHER
            // loopback test in this module uses. onion_addr is pinned to
            // the DIALED link address (runCeremony's own contract).
            val fixture = PairingStore.toFixture(identity)
            val (host, port) = KotlinHandshake.splitAddr(fixture.onion_addr)
            val stream = SocketStream(host, port)
            val peerCert = KotlinHandshake.authOnlyOverStream(stream, fixture)
            assertEquals("node cert identity", node.identityPub, peerCert.identity_pub)

            val res = KotlinSync.run(stream, store, fixture.device_pub)
            assertTrue("sync: $res", res is SyncResult.Ok)

            // Pulls the seeded journal post -- proof the enrolled cert is
            // real and this is a genuinely working device of the identity,
            // not merely a cert that LOOKS valid.
            assertTrue("seeded post must be pulled by the post-ceremony sync",
                store.allMessages().any { it.kind == "post" && it.identityPub == node.identityPub })
        } finally {
            node.kill()
        }
    }

    @Test fun denyReturnsDeniedNothingPersistedAndUnenrolledSyncRefused() {
        val node = startPairingNode(deny = true)
        try {
            val store = InMemorySyncStore()
            val dir = Files.createTempDirectory("pair-ceremony-denied").toFile()

            val result = KotlinPairing.runCeremony(
                dial = realDial, link = node.link, deviceName = "Test Phone",
                timeoutMs = 15000, store = store, dir = dir)

            assertEquals(KotlinPairing.CeremonyResult.Denied, result)
            assertNull("nothing must persist on denial", PairingStore.load(dir))
            assertTrue("store must be untouched on denial", store.knownIdentities().isEmpty())

            // -- Negative auth proof (task brief: "assert the failure mode
            // precisely, don't just try/catch-pass"). A device denied by
            // the ceremony was, BY CONSTRUCTION, never handed identity_priv
            // (CeremonyResult.Denied carries no key material at all -- see
            // KotlinPairing.kt) -- so it structurally cannot mint a cert
            // the node will recognize as ITS identity. The only cert such
            // a device COULD self-sign names an identity of its own
            // choosing, which the node's real admission gate (hearth/
            // sync.py _session:629-631, store.is_known) correctly
            // recognizes as a total stranger and refuses -- driven through
            // authOnlyOverStream + KotlinSync.run, the SAME sync-client
            // chain every other real-node test in this module already
            // uses for the ACCEPTED case (syncsRealOwnIdentityContent
            // etc.), so this is a true negative mirror of that established
            // path, not a different mechanism.
            //
            // Platform note (verified, not assumed): hearth's OWN
            // responder writes a clean `{"t":"refused"}` frame and closes
            // immediately (sync.py:629-631) -- confirmed directly against
            // this exact scenario with a bare `hearth.transport` asyncio
            // client (no JVM/socket layer involved at all), which reads
            // that exact frame cleanly every time. But by the time the
            // server decides to refuse, our own already-sent "revocations"
            // frame (KotlinSync.run writes before it reads, per the
            // wire protocol's initiator-writes-first REVOCATIONS phase)
            // is still SITTING UNREAD in the server's socket receive
            // buffer -- the server never gets to its own REVOCATIONS swap
            // before raising and closing. Unread inbound data at close is
            // the textbook trigger for a hard RST instead of a graceful
            // FIN, and empirically (4/4 reruns, deterministic, both via
            // this exact authOnlyOverStream+run chain AND via
            // KotlinHandshake.runOverStream's own probe) that is exactly
            // what java.net.Socket surfaces on this Windows dev box: the
            // reply frame's own bytes never make it through readExactSync,
            // which throws SocketException, folded by KotlinSync.run's
            // blanket `catch (e: Exception)` into Failed("io", ...) rather
            // than the idealized Failed("revocations","refused"). This is
            // NOT a new gap Task 6 introduces -- KotlinHandshake.
            // runOverStream's own pre-existing doc comment already flags
            // "whether write-then-read reliably surfaces 'refused' vs a
            // Failed('io') ... is unverified (handshake.ts hit a
            // Windows-loopback RST purge that may not reproduce here)";
            // this test is what verifies it, on this platform, for real.
            // Both outcomes are therefore accepted as the one, single
            // "refused" signal this transport can produce -- an
            // OK/successful sync is the only thing that would actually
            // fail this assertion.
            val (fakeIdentityPriv, fakeIdentityPub) = genKeypair()
            val (rogueDevicePriv, rogueDevicePub) = genKeypair()
            val rogueCert = signedCert(fakeIdentityPriv, fakeIdentityPub, rogueDevicePub)
            val addr = KotlinPairing.decodeLink(node.link)!!.first
            val (host, port) = KotlinHandshake.splitAddr(addr)
            val rogueFixture = KotlinHandshake.Fixture(rogueDevicePriv, rogueDevicePub, rogueCert, addr)

            val stream = SocketStream(host, port)
            KotlinHandshake.authOnlyOverStream(stream, rogueFixture)
            val syncStore = InMemorySyncStore()
            val res = KotlinSync.run(stream, syncStore, rogueDevicePub)
            val cleanRefusal = res == SyncResult.Failed("revocations", "refused")
            val transportAbortedRefusal = res is SyncResult.Failed && res.stage == "io" &&
                res.reason.contains("SocketException")
            assertTrue(
                "an unenrolled/unknown device must be REFUSED, not accepted -- got $res",
                cleanRefusal || transportAbortedRefusal)
        } finally {
            node.kill()
        }
    }

    @Test fun wrongCodeIsExpiredBeforeAnyPendingAndRealCodeSurvives() {
        val node = startPairingNode()
        try {
            val store = InMemorySyncStore()
            val dir = Files.createTempDirectory("pair-ceremony-wrongcode").toFile()

            val result = KotlinPairing.runCeremony(
                dial = realDial, link = node.wrongCodeLink, deviceName = "Test Phone",
                timeoutMs = 15000, store = store, dir = dir)

            assertEquals(KotlinPairing.CeremonyResult.Expired, result)
            assertNull(PairingStore.load(dir))

            // "Nothing parked" (task brief), proven concretely rather than
            // merely asserting the Kotlin-side result in isolation: a REAL
            // ceremony against the ORIGINAL, correct link, run immediately
            // after, succeeding completely (Linked) is only possible if
            // (a) hearth's single-active-ceremony admission check
            // (sync.py:456-467) never saw a stray pending ceremony to
            // collide with, and (b) the real code survived the wrong-code
            // guess untouched (pairingcodes.py's verify_and_consume: "a
            // wrong ... attempt leaves the active code untouched") -- if
            // either had failed, this second ceremony would come back
            // Expired too, not Linked.
            val store2 = InMemorySyncStore()
            val dir2 = Files.createTempDirectory("pair-ceremony-wrongcode-retry").toFile()
            val result2 = KotlinPairing.runCeremony(
                dial = realDial, link = node.link, deviceName = "Test Phone 2",
                timeoutMs = 15000, store = store2, dir = dir2)
            assertTrue("the real code must still work after a wrong-code guess: $result2",
                result2 is KotlinPairing.CeremonyResult.Linked)
        } finally {
            node.kill()
        }
    }
}
