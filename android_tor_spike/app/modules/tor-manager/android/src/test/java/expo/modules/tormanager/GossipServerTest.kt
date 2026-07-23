package expo.modules.tormanager

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ConnectException
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Task 4 (arc 1, kotlin-gossip-server): GossipServer's accept loop + the
 *  process-wide lock coordination with SyncRunner. REAL-SOCKET JVM tests --
 *  no fake Stream here (unlike KotlinHandshakeTest/KotlinSyncTest's queued
 *  RespondingStream) -- start a real GossipServer on an ephemeral loopback
 *  port and drive it with a real `java.net.Socket` client, reusing
 *  KotlinHandshake.authOnlyOverStream + KotlinSync.run as the in-process
 *  initiator (the SAME pairing SyncRunner.runTransport uses against a real
 *  node): respondHandshake (Task 2) never touches REVOCATIONS at all, and
 *  serve() (Task 3) runs it as its own first phase, exactly mirroring
 *  authOnlyOverStream (HELLO+AUTH only) followed by KotlinSync.run (whose
 *  first phase is the one-and-only REVOCATIONS round). Chaining
 *  runOverStream's acceptance-probe first would send a SECOND premature
 *  REVOCATIONS frame into serve()'s DEFRIENDS slot -- the exact double-frame
 *  desync KotlinHandshake.kt's own doc comment (runOverStream, "Blocking
 *  the whole handshake+serve" paragraph) and KotlinSync.kt's authOnlyOverStream
 *  doc already document happening against a REAL hearth node; the same
 *  wire-shape mismatch applies identically here since serve()'s phase order
 *  mirrors run()'s exactly.
 *
 *  SocketStream (dial-side, 30s soTimeout) is reused unmodified from
 *  SyncLoopbackTest.kt -- an `internal` top-level class in this same test
 *  source set, already shared cross-file by SyncComposeLoopbackTest.kt.
 */

// -- Shared key/cert minting helpers (Task 5, gossip-server loopback gate) --
//
// genKeypair/signedCert originally lived as private members of
// GossipServerTest. Hoisted to top-level `internal` declarations (same
// file, same package) so SyncServeLoopbackTest.kt (Task 5 -- the INVERTED
// loopback gate, where a real hearth node dials the phone and the Kotlin
// test must mint that node's identity/device/cert itself, since the node
// process is spawned only AFTER the port is known) can reuse the exact
// same real-Ed25519-keypair-plus-signed-EnrollmentCert idiom byte-for-byte
// instead of duplicating it -- "mirror it, don't reinvent", the same
// rationale SyncLoopbackTest.kt's own hoist of SocketStream/NodeProcess/
// findRepoRoot/spawnNode already established. Purely a scope move: both
// function bodies are unchanged, and GossipServerTest's own call sites
// (buildFixture) keep working unmodified -- an unqualified `genKeypair()`/
// `signedCert(...)` now resolves to the top-level function instead of a
// private member, with identical behavior.
internal fun genKeypair(): Pair<String, String> {
    val p = Ed25519PrivateKeyParameters(SecureRandom())
    return KotlinWire.toHex(p.encoded) to KotlinWire.toHex(p.generatePublicKey().encoded)
}

internal fun signedCert(
    identityPriv: String, identityPub: String, devicePub: String, name: String,
): KotlinWire.CertDict {
    val unsigned = KotlinWire.CertDict(identityPub, devicePub, name, 1752900000.0, "")
    return unsigned.copy(signature = KotlinWire.signRaw(identityPriv, KotlinWire.certBody(unsigned)))
}

class GossipServerTest {

    // =======================================================================
    // Fixtures -- real Ed25519 keys, same idiom as KotlinHandshakeTest/KotlinSyncTest.
    // =======================================================================

    private fun devPub(privHex: String) = KotlinWire.toHex(
        Ed25519PrivateKeyParameters(KotlinWire.fromHex(privHex), 0).generatePublicKey().encoded)

    /** A full identity+device fixture, own keys generated fresh. */
    private fun buildFixture(name: String): KotlinHandshake.Fixture {
        val (identityPriv, identityPub) = genKeypair()
        val (devicePriv, devicePub) = genKeypair()
        val cert = signedCert(identityPriv, identityPub, devicePub, name)
        return KotlinHandshake.Fixture(devicePriv, devicePub, cert, "unused.onion:9997")
    }

    // Builds a SIGNED message for an explicit identity_pub -- mirrors
    // KotlinSyncTest's identityMsg idiom exactly.
    private fun identityMsg(identityPub: String, seq: Int, payload: Map<String, Any?>, devPrivHex: String): SignedMessage {
        val devicePub = devPub(devPrivHex)
        val cert = KotlinWire.CertDict(identityPub, devicePub, "d", 1752900000.0, "00")
        val unsigned = SignedMessage(cert, seq, payload, "")
        return unsigned.copy(signature = KotlinWire.signRaw(devPrivHex, unsigned.body()))
    }

    // In-process initiator: authOnlyOverStream + KotlinSync.run, see class doc.
    private fun clientSync(port: Int, fixture: KotlinHandshake.Fixture, store: SyncStore): SyncResult {
        val stream = SocketStream("127.0.0.1", port)
        KotlinHandshake.authOnlyOverStream(stream, fixture)
        return KotlinSync.run(stream, store, fixture.device_pub)
    }

    // =======================================================================
    // 1. Happy path: full handshake+serve over a real socket, seeded store.
    // =======================================================================

    @Test fun realSocketClientCompletesHandshakeAndServeAgainstSeededStore() {
        val serverFixture = buildFixture("Server Device")
        val clientFixture = buildFixture("Client Device")
        val friendPriv = "f2".repeat(32)
        val friendPub = "f1".repeat(32)

        val serverStore = InMemorySyncStore()
        // AUTH gate: the server must know the client's identity, or respondHandshake refuses.
        serverStore.addIdentity(clientFixture.cert.identity_pub)
        // A mutual friend both sides report as known -- profile kind has no
        // audience gate (SyncStore.messagesNotIn's doc), so it is servable
        // to any entitled peer purely off the entitled-identity intersection.
        serverStore.addIdentity(friendPub)
        val seeded = identityMsg(friendPub, 1, mapOf("kind" to "profile", "name" to "Friend", "created_at" to 1.0), friendPriv)
        assertTrue(serverStore.ingestMessage(seeded))

        val gossipServer = GossipServer(serverStore, { serverFixture }, ReentrantLock(), 0)
        val port = gossipServer.start()
        try {
            val clientStore = InMemorySyncStore()
            clientStore.addIdentity(friendPub)   // client reports the same mutual friend as known
            val result = clientSync(port, clientFixture, clientStore)

            assertTrue("expected Ok, got $result", result is SyncResult.Ok)
            assertTrue("the seeded profile message must have been served to the client",
                clientStore.allMessages().any { it.msgId == seeded.msgId() })
        } finally {
            gossipServer.stop()
        }
    }

    // =======================================================================
    // 2. Coarse lock: a second connection mid-serve is serialized, not lost
    //    or deadlocked.
    // =======================================================================

    @Test fun secondConnectionWhileFirstHoldsTheLockIsSerializedThenBothComplete() {
        val serverFixture = buildFixture("Server Device")
        val clientFixture1 = buildFixture("Client One")
        val clientFixture2 = buildFixture("Client Two")

        val realStore = InMemorySyncStore()
        realStore.addIdentity(clientFixture1.cert.identity_pub)
        realStore.addIdentity(clientFixture2.cert.identity_pub)

        // Blocks the FIRST-EVER call to knownIdentities() (respondHandshake's
        // isKnown gate is the first store call any connection makes) until
        // the test releases it -- holding connection 1's worker (and thus the
        // shared lock) open long enough to prove connection 2 queues behind
        // it instead of running concurrently or deadlocking.
        val firstCallGate = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slowStore = object : SyncStore by realStore {
            override fun knownIdentities(): List<String> {
                if (firstCallGate.compareAndSet(false, true)) {
                    entered.countDown()
                    release.await()
                }
                return realStore.knownIdentities()
            }
        }

        val gossipServer = GossipServer(slowStore, { serverFixture }, ReentrantLock(), 0)
        val port = gossipServer.start()
        try {
            val t1Result = AtomicReference<SyncResult>()
            val t1 = Thread {
                t1Result.set(clientSync(port, clientFixture1, InMemorySyncStore()))
            }
            t1.start()
            assertTrue("connection 1's worker must reach the gated store call",
                entered.await(5, TimeUnit.SECONDS))

            val t2Done = CountDownLatch(1)
            val t2Result = AtomicReference<SyncResult>()
            val t2 = Thread {
                t2Result.set(clientSync(port, clientFixture2, InMemorySyncStore()))
                t2Done.countDown()
            }
            t2.start()

            // Connection 2 must NOT complete while connection 1 holds the lock --
            // proves serialization (not concurrent execution, which would race
            // past this window) without proving a deadlock (the next step below
            // resolves it).
            assertFalse("connection 2 must still be waiting on the coarse lock",
                t2Done.await(400, TimeUnit.MILLISECONDS))

            release.countDown()   // let connection 1 finish and release the lock
            t1.join(5000)
            assertTrue("connection 2 must complete once the lock is released",
                t2Done.await(5, TimeUnit.SECONDS))
            t2.join(5000)

            assertTrue("connection 1 expected Ok, got ${t1Result.get()}", t1Result.get() is SyncResult.Ok)
            assertTrue("connection 2 expected Ok, got ${t2Result.get()}", t2Result.get() is SyncResult.Ok)
        } finally {
            gossipServer.stop()
        }
    }

    // =======================================================================
    // 3. stop() closes the ServerSocket cleanly; a later connect is refused.
    // =======================================================================

    @Test fun stopClosesServerSocketAndSubsequentConnectIsRefused() {
        val server = GossipServer(InMemorySyncStore(), { null }, ReentrantLock(), 0)
        val port = server.start()
        // Sanity: the port accepts a raw TCP connection before stop().
        Socket("127.0.0.1", port).close()

        server.stop()

        try {
            Socket("127.0.0.1", port).close()
            fail("expected the connection to be refused after stop()")
        } catch (e: ConnectException) {
            // expected
        }
    }

    // =======================================================================
    // 4. A garbage first frame kills only that connection -- the accept loop
    //    (and the server) survive to serve a later, normal connection.
    // =======================================================================

    @Test fun garbageFirstFrameDoesNotKillTheAcceptLoopAndALaterConnectionStillWorks() {
        val serverFixture = buildFixture("Server Device")
        val clientFixture = buildFixture("Client Device")
        val friendPriv = "e2".repeat(32)
        val friendPub = "e1".repeat(32)

        val serverStore = InMemorySyncStore()
        serverStore.addIdentity(clientFixture.cert.identity_pub)
        serverStore.addIdentity(friendPub)
        val seeded = identityMsg(friendPub, 1, mapOf("kind" to "profile", "name" to "Friend", "created_at" to 1.0), friendPriv)
        assertTrue(serverStore.ingestMessage(seeded))

        val gossipServer = GossipServer(serverStore, { serverFixture }, ReentrantLock(), 0)
        val port = gossipServer.start()
        try {
            // -- connection A: a normal, complete sync (baseline "it works"). --
            val storeA = InMemorySyncStore(); storeA.addIdentity(friendPub)
            val resultA = clientSync(port, clientFixture, storeA)
            assertTrue("connection A expected Ok, got $resultA", resultA is SyncResult.Ok)

            // -- connection B: garbage first frame (a length prefix far past
            //    KotlinWire.MAX_FRAME, no valid body) -- respondHandshake's
            //    readFrame throws; GossipServer's per-connection try/catch
            //    must swallow it and close, never propagating out of the
            //    accept loop. --
            val badSock = Socket("127.0.0.1", port)
            badSock.soTimeout = 5000
            badSock.getOutputStream().write(byteArrayOf(0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
            badSock.getOutputStream().flush()
            badSock.close()

            // -- connection C: a THIRD, later connection -- proves the accept
            //    loop is still alive and the server still works normally. --
            val storeC = InMemorySyncStore(); storeC.addIdentity(friendPub)
            val clientFixtureC = buildFixture("Client Device C")
            serverStore.addIdentity(clientFixtureC.cert.identity_pub)
            val resultC = clientSync(port, clientFixtureC, storeC)
            assertTrue("connection C expected Ok, got $resultC", resultC is SyncResult.Ok)
            assertTrue("connection C must also have received the seeded profile message",
                storeC.allMessages().any { it.msgId == seeded.msgId() })
        } finally {
            gossipServer.stop()
        }
    }
}
