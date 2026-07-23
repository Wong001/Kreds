package expo.modules.tormanager

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** Task 2 (arc 1, kotlin-gossip-server): the RESPONDER half of the
 *  handshake -- KotlinHandshake.respondHandshake. Mirrors hearth's
 *  _session responder branch for HELLO+AUTH (sync.py:590-641, `_swap`'s
 *  initiator=False path: read peer's frame first, THEN write ours, each
 *  phase) plus the stranger-refusal gate (sync.py:630-641).
 *
 *  No pre-existing single-sided fake-Stream precedent for a TWO-PHASE
 *  read/write protocol in this module's tests (KotlinPairingTest's
 *  ScriptedStream/FixedReplyStream are one-request/one-reply). Both the
 *  RESPONDER's own nonce (via the injectable `rnd`) and the scripted
 *  peer's nonce are known to the test ahead of time, so every reply frame
 *  the "peer" would send can be precomputed and queued up front --
 *  RespondingStream below is that queued-multi-frame fake, built fresh for
 *  this file, reusing ScriptedStream/FixedReplyStream's write-decoding
 *  idiom (a single Stream.write call always carries one whole frame, see
 *  KotlinHandshake's private writeFrame). */
class KotlinHandshakeTest {

    // =======================================================================
    // Fixtures -- real Ed25519 keys, same idiom as KotlinPairingTest.
    // =======================================================================

    private fun genKeypair(): Pair<String, String> {
        val p = Ed25519PrivateKeyParameters(SecureRandom())
        return KotlinWire.toHex(p.encoded) to KotlinWire.toHex(p.generatePublicKey().encoded)
    }

    private fun signedCert(
        identityPriv: String, identityPub: String, devicePub: String,
        name: String = "Device", enrolledAt: Double = 1752900000.0,
    ): KotlinWire.CertDict {
        val unsigned = KotlinWire.CertDict(identityPub, devicePub, name, enrolledAt, "")
        return unsigned.copy(signature = KotlinWire.signRaw(identityPriv, KotlinWire.certBody(unsigned)))
    }

    private fun certMap(c: KotlinWire.CertDict): Map<String, Any?> = mapOf(
        "identity_pub" to c.identity_pub, "device_pub" to c.device_pub,
        "device_name" to c.device_name, "enrolled_at" to KotlinWire.PyFloat(c.enrolled_at),
        "signature" to c.signature)

    /** Our (the responder's) own identity+device -- separate keys from
     *  whatever "peer" a test scripts. */
    private fun buildFixture(): KotlinHandshake.Fixture {
        val (identityPriv, identityPub) = genKeypair()
        val (devicePriv, devicePub) = genKeypair()
        val cert = signedCert(identityPriv, identityPub, devicePub, name = "Responder Device")
        return KotlinHandshake.Fixture(devicePriv, devicePub, cert, "responder.onion:9997")
    }

    // =======================================================================
    // RespondingStream: a queued sequence of pre-built reply frames served
    // on read, capturing every frame written by the code under test.
    // =======================================================================

    private class RespondingStream(readFrames: List<Map<String, Any?>>) : Stream {
        private val buffer: ByteArray = readFrames.fold(ByteArray(0)) { acc, f -> acc + KotlinWire.writeFrameBytes(f) }
        private var pos = 0
        val written = mutableListOf<JSONObject>()
        var closed = false

        override fun write(bytes: ByteArray) {
            val n = (((bytes[0].toInt() and 0xff) shl 24) or ((bytes[1].toInt() and 0xff) shl 16) or
                     ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff))
            written.add(JSONObject(String(bytes, 4, n, Charsets.US_ASCII)))
        }
        override fun readExactSync(n: Int): ByteArray {
            check(pos + n <= buffer.size) { "RespondingStream exhausted (wanted $n more bytes)" }
            val out = buffer.copyOfRange(pos, pos + n)
            pos += n
            return out
        }
        override fun close() { closed = true }
    }

    // =======================================================================
    // respondHandshake
    // =======================================================================

    /** Happy path: a KNOWN peer completes AUTH -> Ok(peerCert). Pins the
     *  exact frames the responder writes (own HELLO then own AUTH) and the
     *  exact challenge payload each side signs -- byte-identical to
     *  hearth's _auth_body / runOverStream's KotlinWire.authBody, just with
     *  the two nonces' roles swapped (we sign THEIR nonce, verify THEIR
     *  sig over OUR nonce). */
    @Test fun respondHandshakeKnownPeerCompletesAuthReturnsOk() {
        val fixture = buildFixture()
        val myNonce = "aa11".repeat(4)   // fixed via injected rnd -- known to the test ahead of time

        val (peerIdentityPriv, peerIdentityPub) = genKeypair()
        val (peerDevicePriv, peerDevicePub) = genKeypair()
        val peerCert = signedCert(peerIdentityPriv, peerIdentityPub, peerDevicePub, name = "Peer Phone")
        val peerNonce = KotlinHandshake.randomHex16()

        val peerHello = mapOf("t" to "hello", "cert" to certMap(peerCert), "nonce" to peerNonce)
        // Peer proves ITS device key by signing OUR nonce (myNonce) -- the
        // responder verifies this via KotlinWire.verifyRaw(peerCert.device_pub, sig, authBody(myNonce)).
        val peerAuth = mapOf("t" to "auth", "sig" to KotlinWire.signRaw(peerDevicePriv, KotlinWire.authBody(myNonce)))

        val stream = RespondingStream(listOf(peerHello, peerAuth))
        val result = KotlinHandshake.respondHandshake(
            stream, fixture, isKnown = { it == peerIdentityPub }, rnd = { myNonce })

        assertTrue("expected Ok, got $result", result is KotlinHandshake.HandshakeResult.Ok)
        val ok = result as KotlinHandshake.HandshakeResult.Ok
        assertEquals(peerIdentityPub, ok.peerCert.identity_pub)
        assertEquals(peerDevicePub, ok.peerCert.device_pub)
        assertEquals(peerCert, ok.peerCert)

        assertEquals("exactly two frames written: own HELLO then own AUTH", 2, stream.written.size)
        val wroteHello = stream.written[0]
        assertEquals("hello", wroteHello.getString("t"))
        assertEquals(myNonce, wroteHello.getString("nonce"))
        assertEquals(fixture.cert.identity_pub, wroteHello.getJSONObject("cert").getString("identity_pub"))

        val wroteAuth = stream.written[1]
        assertEquals("auth", wroteAuth.getString("t"))
        // Our AUTH signs THEIR nonce (peerNonce) with our device key --
        // exactly hearth's auth = sign_raw(_auth_body(peer_hello["nonce"]))
        // (sync.py:610-611), same authBody payload runOverStream signs.
        assertTrue(KotlinWire.verifyRaw(fixture.device_pub, wroteAuth.getString("sig"), KotlinWire.authBody(peerNonce)))

        // respondHandshake must never own the stream's lifecycle -- that is
        // GossipServer's (Task 4) job. A stray close() here would break a
        // caller that continues straight into the sync responder (Task 3)
        // on the same connection.
        assertTrue("respondHandshake must not close the stream", !stream.closed)
    }

    /** The stranger gate: a peer with a cryptographically VALID cert and a
     *  genuinely correct device-key proof, but an identity `isKnown` never
     *  recognizes, still completes the FULL HELLO+AUTH exchange (own HELLO
     *  sent, own AUTH sent) before being refused -- {"t":"refused"} is
     *  written as a THIRD frame, in the wire slot hearth's real initiator
     *  would otherwise read a REVOCATIONS-phase reply from (sync.py:
     *  630-632 landing in the sync.py:657-662 PeerRefused check). Refusing
     *  any earlier (in place of the HELLO or AUTH reply) is an interop bug:
     *  a real hearth initiator only recognizes {"t":"refused"} as
     *  PeerRefused at that one spot -- anywhere else it just sees a
     *  malformed HELLO/AUTH and fails generically. */
    @Test fun respondHandshakeUnknownPeerCompletesAuthThenWritesRefused() {
        val fixture = buildFixture()
        val myNonce = "cc33".repeat(4)
        val (peerIdentityPriv, peerIdentityPub) = genKeypair()
        val (peerDevicePriv, peerDevicePub) = genKeypair()
        val peerCert = signedCert(peerIdentityPriv, peerIdentityPub, peerDevicePub)
        val peerNonce = KotlinHandshake.randomHex16()
        val peerHello = mapOf("t" to "hello", "cert" to certMap(peerCert), "nonce" to peerNonce)
        // A genuinely valid device-key proof over OUR nonce -- this peer is
        // cryptographically legitimate, just not in isKnown's set.
        val peerAuth = mapOf("t" to "auth", "sig" to KotlinWire.signRaw(peerDevicePriv, KotlinWire.authBody(myNonce)))

        val stream = RespondingStream(listOf(peerHello, peerAuth))
        val result = KotlinHandshake.respondHandshake(stream, fixture, isKnown = { false }, rnd = { myNonce })

        assertTrue("expected Failed, got $result", result is KotlinHandshake.HandshakeResult.Failed)
        val f = result as KotlinHandshake.HandshakeResult.Failed
        assertEquals("auth", f.stage)
        assertEquals("refused", f.reason)

        assertEquals("own HELLO, own AUTH, THEN refused -- full exchange before the gate", 3, stream.written.size)
        assertEquals("hello", stream.written[0].getString("t"))
        assertEquals("auth", stream.written[1].getString("t"))
        // Our AUTH still proves our own device key over the peer's nonce --
        // an unknown identity is refused, not starved of a real AUTH proof
        // (hearth completes AUTH for anyone with a valid cert/sig, sync.py:
        // 609-616, before the is_known check at :630).
        assertTrue(KotlinWire.verifyRaw(fixture.device_pub, stream.written[1].getString("sig"), KotlinWire.authBody(peerNonce)))
        assertEquals("refused", stream.written[2].getString("t"))
        assertEquals(setOf("t"), stream.written[2].keys().asSequence().toSet())

        assertTrue("respondHandshake must not close the stream", !stream.closed)
    }

    /** A peer whose device-key signature over OUR nonce does not verify
     *  (e.g. signed with the wrong private key) -> Failed. Our own HELLO
     *  has already gone out (the peer needs it to build its side of AUTH
     *  at all) but our own AUTH is never written once their proof fails --
     *  mirrors runOverStream's symmetric "node failed device-key proof"
     *  check, from the other direction. */
    @Test fun respondHandshakeBadPeerDeviceSigFails() {
        val fixture = buildFixture()
        val myNonce = "bb22".repeat(4)
        val (peerIdentityPriv, peerIdentityPub) = genKeypair()
        val (_, peerDevicePub) = genKeypair()
        val (wrongDevicePriv, _) = genKeypair()   // does NOT correspond to peerDevicePub
        val peerCert = signedCert(peerIdentityPriv, peerIdentityPub, peerDevicePub)
        val peerNonce = KotlinHandshake.randomHex16()
        val peerHello = mapOf("t" to "hello", "cert" to certMap(peerCert), "nonce" to peerNonce)
        val peerAuth = mapOf("t" to "auth", "sig" to KotlinWire.signRaw(wrongDevicePriv, KotlinWire.authBody(myNonce)))

        val stream = RespondingStream(listOf(peerHello, peerAuth))
        val result = KotlinHandshake.respondHandshake(
            stream, fixture, isKnown = { it == peerIdentityPub }, rnd = { myNonce })

        assertTrue("expected Failed, got $result", result is KotlinHandshake.HandshakeResult.Failed)
        val f = result as KotlinHandshake.HandshakeResult.Failed
        assertEquals("auth", f.stage)

        assertEquals("own HELLO sent, own AUTH withheld once peer's proof fails", 1, stream.written.size)
        assertEquals("hello", stream.written[0].getString("t"))

        assertTrue("respondHandshake must not close the stream", !stream.closed)
    }
}
