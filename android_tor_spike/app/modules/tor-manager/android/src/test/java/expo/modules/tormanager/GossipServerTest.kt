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

    // Builds a SIGNED message for an explicit identity -- mirrors
    // KotlinSyncTest's identityMsg idiom exactly.
    //
    // Security-fix note: takes the identity's PRIVATE key (identityPrivHex),
    // not a bare identity_pub -- ingestMessage's enrollment-cert gate
    // (KotlinWire.verifyCert) requires the cert be genuinely signed by
    // identity_pub's own private key. Call sites that previously hardcoded a
    // bare "xPub" literal now hold the corresponding "xIdentityPriv" literal
    // instead and derive the pub via `devPub` (a plain Ed25519-priv-to-pub
    // derivation, despite the name -- same helper already used for device
    // keys just below).
    private fun identityMsg(identityPrivHex: String, seq: Int, payload: Map<String, Any?>, devPrivHex: String): SignedMessage {
        val identityPub = devPub(identityPrivHex)
        val devicePub = devPub(devPrivHex)
        val cert = signedCert(identityPrivHex, identityPub, devicePub, "d")
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
        val friendIdentityPriv = "f1".repeat(32)
        val friendPub = devPub(friendIdentityPriv)

        val serverStore = InMemorySyncStore()
        // AUTH gate: the server must know the client's identity, or respondHandshake refuses.
        serverStore.addIdentity(clientFixture.cert.identity_pub)
        // A mutual friend both sides report as known -- profile kind has no
        // audience gate (SyncStore.messagesNotIn's doc), so it is servable
        // to any entitled peer purely off the entitled-identity intersection.
        serverStore.addIdentity(friendPub)
        val seeded = identityMsg(friendIdentityPriv, 1, mapOf("kind" to "profile", "name" to "Friend", "created_at" to 1.0), friendPriv)
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
        val friendIdentityPriv = "e1".repeat(32)
        val friendPub = devPub(friendIdentityPriv)

        val serverStore = InMemorySyncStore()
        serverStore.addIdentity(clientFixture.cert.identity_pub)
        serverStore.addIdentity(friendPub)
        val seeded = identityMsg(friendIdentityPriv, 1, mapOf("kind" to "profile", "name" to "Friend", "created_at" to 1.0), friendPriv)
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

    // =======================================================================
    // 5. Task 6 (phone-onion-reachability): serve() returning SelfRevoked
    //    must drive GossipServer's injected onSelfRevoked callback -- the
    //    wiring this task adds. KotlinSync.serve's own SelfRevoked detection
    //    (including the remote-wipe fix's tightened gate -- see KotlinSync.kt's
    //    serve() REVOCATIONS-phase doc comment for the full exploit/fix
    //    writeup) is already unit-proven at KotlinSyncTest's level
    //    (serveGenuineSelfRevocationStillReturnsSelfRevoked /
    //    serveForgedRevocationFromThrowawayIdentityDoesNotSelfRevoke /
    //    serveKnownFriendsValidRevocationNamingOurDeviceDoesNotSelfRevoke)
    //    -- the only NEW thing to prove here is that GossipServer.handle
    //    actually captures that result and invokes (or correctly withholds)
    //    the callback.
    //
    //    A raw hand-rolled client, NOT KotlinSync.run: run()'s own REVOCATIONS
    //    write is UNCONDITIONALLY an empty list ("the phone authors none",
    //    see KotlinSync.kt's run() doc) -- a genuine wire-level revocation,
    //    the same shape a real hearth node would send, has to be crafted by
    //    hand here. Completes AUTH via authOnlyOverStream (same as
    //    clientSync), then writes ONE raw REVOCATIONS frame carrying a real
    //    RevocationCert naming the SERVER's own device_pub, then reads the
    //    server's reply frame (serve() writes its own empty ack BEFORE the
    //    self-revoked check -- see KotlinSync.kt's serve() phase order).
    //
    //    invoked (a CountDownLatch) is awaited rather than asserted
    //    immediately: onSelfRevoked runs on the SERVER's own worker thread,
    //    strictly AFTER handle()'s lock.unlock() -- there is no synchronous
    //    signal back to this (client) thread proving that has happened (or
    //    definitely will not happen) yet by the time the read above returns.
    // =======================================================================

    /** Mirrors KotlinSyncTest's own private `signedRevocation` helper --
     *  duplicated here rather than shared, per that file's own stated
     *  precedent ("a fresh file-scoped fake per test file"). */
    private fun signedRevocation(
        identityPriv: String, identityPub: String, devicePub: String, lastValidSeq: Int,
    ): RevocationCert {
        val unsigned = RevocationCert(identityPub, devicePub, lastValidSeq, 1752900000.0, "")
        return unsigned.copy(signature = KotlinWire.signRaw(identityPriv, unsigned.body()))
    }

    /** Minimal raw frame reader -- mirrors KotlinSync's own private
     *  `readFrame` (4-byte big-endian length prefix, ASCII JSON body).
     *  Needed here because this test drives the wire protocol directly
     *  rather than through KotlinSync.run (see this section's doc comment
     *  for why). */
    private fun readRawFrame(stream: Stream): org.json.JSONObject {
        val header = stream.readExactSync(4)
        val n = (((header[0].toInt() and 0xff) shl 24) or ((header[1].toInt() and 0xff) shl 16) or
                 ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff))
        return org.json.JSONObject(String(stream.readExactSync(n), Charsets.US_ASCII))
    }

    /** SECURITY REGRESSION TEST (remote-wipe fix, Task 6 review) -- the
     *  GossipServer-level proof that a FORGED revocation can no longer
     *  trigger the wipe callback. This used to be
     *  `serveSelfRevokedInvokesOnSelfRevokedCallback`, which used a
     *  DIFFERENT (throwaway, unrelated) identity to author the revocation
     *  and asserted the callback FIRED -- i.e. it asserted the exploit
     *  worked. Under the OLD `KotlinSync.serve` code (a bare
     *  `rev.device_pub == fixture.device_pub` check) this test's assertion
     *  below (`invoked.await(...)` must be FALSE) FAILS, because the old
     *  code invokes the callback here -- that is what makes this a real
     *  regression guard for the end-to-end GossipServer wiring, not merely
     *  a restatement of the KotlinSyncTest-level fix. */
    @Test fun serveForgedRevocationFromThrowawayIdentityDoesNotInvokeOnSelfRevokedCallback() {
        val serverFixture = buildFixture("Server Device")
        val clientFixture = buildFixture("Client Device")
        val serverStore = InMemorySyncStore()
        serverStore.addIdentity(clientFixture.cert.identity_pub)   // AUTH's isKnown gate

        // A throwaway, unrelated identity -- NOT the server's own -- self-signs
        // a revocation naming the SERVER's device_pub (the exact forged shape
        // an authenticated-but-malicious peer could send, having learned the
        // server's device_pub from AUTH's exchanged CertDict).
        val (throwawayPriv, throwawayPub) = genKeypair()
        val rev = signedRevocation(throwawayPriv, throwawayPub, serverFixture.device_pub, lastValidSeq = 1)

        val invoked = CountDownLatch(1)
        val gossipServer = GossipServer(
            serverStore, { serverFixture }, ReentrantLock(), 0,
            onSelfRevoked = { invoked.countDown() },
        )
        val port = gossipServer.start()
        try {
            val stream = SocketStream("127.0.0.1", port)
            KotlinHandshake.authOnlyOverStream(stream, clientFixture)

            stream.write(KotlinWire.writeFrameBytes(mapOf("t" to "revocations", "revs" to listOf(rev.toDict()))))
            // REVOCATIONS ack -- proves serve() got past the forged
            // revocation without self-revoking. Abandon right here (same
            // reasoning as serveOrdinaryPeerRevocationDoesNotInvoke... just
            // below): serve()'s NEXT phase (DEFRIENDS) blocks on its own
            // readFrame, so closing now makes that read fail fast (EOF)
            // instead of idling until SOCKET_TIMEOUT_MS -- either way
            // serve() returns something other than SelfRevoked, which is
            // all this test needs: proof onSelfRevoked was never invoked.
            readRawFrame(stream)
            stream.close()

            assertFalse("onSelfRevoked must NOT fire for a forged (non-own-identity-signed) " +
                "revocation -- this is the remote-wipe exploit this fix closes",
                invoked.await(2, TimeUnit.SECONDS))
        } finally {
            gossipServer.stop()
        }
    }

    /** POSITIVE case: a GENUINE self-revocation -- signed by the server's
     *  OWN identity key, exactly mirroring how the desktop (hearth) would
     *  revoke this phone device -- must still drive the callback under the
     *  tightened gate. Builds the fixture/identity manually (rather than
     *  via `buildFixture`, which discards the generated identity_priv) so
     *  the SAME identity_priv that signs `serverCert` is available here to
     *  also sign the revocation. */
    @Test fun serveGenuineSelfRevocationInvokesOnSelfRevokedCallback() {
        val (ownIdentityPriv, ownIdentityPub) = genKeypair()
        val (ownDevicePriv, ownDevicePub) = genKeypair()
        val serverCert = signedCert(ownIdentityPriv, ownIdentityPub, ownDevicePub, "Server Device")
        val serverFixture = KotlinHandshake.Fixture(ownDevicePriv, ownDevicePub, serverCert, "unused.onion:9997")
        val clientFixture = buildFixture("Client Device")
        val serverStore = InMemorySyncStore()
        serverStore.addIdentity(clientFixture.cert.identity_pub)   // AUTH's isKnown gate
        serverStore.addIdentity(ownIdentityPub)                    // mirrors production seeding (own identity IS known)

        val rev = signedRevocation(ownIdentityPriv, ownIdentityPub, ownDevicePub, lastValidSeq = 1)

        val invoked = CountDownLatch(1)
        val gossipServer = GossipServer(
            serverStore, { serverFixture }, ReentrantLock(), 0,
            onSelfRevoked = { invoked.countDown() },
        )
        val port = gossipServer.start()
        try {
            val stream = SocketStream("127.0.0.1", port)
            KotlinHandshake.authOnlyOverStream(stream, clientFixture)

            stream.write(KotlinWire.writeFrameBytes(mapOf("t" to "revocations", "revs" to listOf(rev.toDict()))))
            readRawFrame(stream)   // server's empty-ack REVOCATIONS reply, written before the self-revoked check
            // Close now, rather than leave the connection open across the
            // latch wait below -- see the section doc / the forged test
            // above for why (avoids gracefulClose's drain blocking
            // gossipServer.stop()'s cleanup).
            stream.close()

            assertTrue("GossipServer must invoke onSelfRevoked for a GENUINE, own-identity-signed " +
                "self-revocation", invoked.await(5, TimeUnit.SECONDS))
        } finally {
            gossipServer.stop()
        }
    }

    /** A peer revocation naming some OTHER device (not ours) must NOT fire
     *  onSelfRevoked -- the callback is scoped to genuinely OUR OWN device,
     *  never any revocation a peer happens to relay. */
    @Test fun serveOrdinaryPeerRevocationDoesNotInvokeOnSelfRevokedCallback() {
        val serverFixture = buildFixture("Server Device")
        val clientFixture = buildFixture("Client Device")
        val serverStore = InMemorySyncStore()
        serverStore.addIdentity(clientFixture.cert.identity_pub)

        val (identityPriv, identityPub) = genKeypair()
        val (_, someOtherDevicePub) = genKeypair()   // NOT serverFixture.device_pub
        val rev = signedRevocation(identityPriv, identityPub, someOtherDevicePub, lastValidSeq = 1)

        val invoked = CountDownLatch(1)
        val gossipServer = GossipServer(
            serverStore, { serverFixture }, ReentrantLock(), 0,
            onSelfRevoked = { invoked.countDown() },
        )
        val port = gossipServer.start()
        try {
            val stream = SocketStream("127.0.0.1", port)
            KotlinHandshake.authOnlyOverStream(stream, clientFixture)
            stream.write(KotlinWire.writeFrameBytes(mapOf("t" to "revocations", "revs" to listOf(rev.toDict()))))
            // REVOCATIONS ack -- proves serve() got past the (non-matching)
            // revocation without self-revoking. Deliberately abandon the
            // connection right here rather than complete DEFRIENDS/HAVE/etc:
            // serve()'s NEXT phase (DEFRIENDS) blocks on its own readFrame,
            // so closing now makes that read fail fast (EOF) instead of
            // idling until SOCKET_TIMEOUT_MS -- either way serve() returns
            // something other than SelfRevoked (Failed("io", ...) via the
            // closed stream), which is all this test needs: proof
            // onSelfRevoked was never invoked.
            readRawFrame(stream)
            stream.close()

            assertFalse("onSelfRevoked must not fire for a revocation naming a different device",
                invoked.await(2, TimeUnit.SECONDS))
        } finally {
            gossipServer.stop()
        }
    }
}
