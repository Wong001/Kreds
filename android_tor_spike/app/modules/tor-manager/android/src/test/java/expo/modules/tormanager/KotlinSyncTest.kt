package expo.modules.tormanager

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for KotlinSync's outbound-push additions (B.2 Task 4): the
 *  MESSAGES phase can now push outbound messages (for B.2, exactly the
 *  phone's device-signed enckey), built by the new `composeEncKey` helper.
 *  No network/store involved here -- SyncLoopbackTest (Task 6) is the
 *  end-to-end gate that proves a real hearth node accepts the pushed
 *  message and mints grants against it. */
class KotlinSyncTest {

    private fun devPub(privHex: String) = KotlinWire.toHex(
        Ed25519PrivateKeyParameters(KotlinWire.fromHex(privHex), 0).generatePublicKey().encoded)

    // org.json -> Map bridge, identical to SignedMessageTest's/KotlinSync's own
    // (unwrap normalizes org.json's BigDecimal to plain Double -- see
    // KotlinSync.unwrap's comment on why). Round-tripping composeEncKey's
    // result through REAL wire bytes (KotlinWire.canonical/dumps) and back
    // through org.json is the realistic path: it's exactly what the MESSAGES
    // phase's writeFrame does to this same map, and exactly what a real peer
    // does parsing it back. Asserting against the raw Kotlin map alone would
    // miss any formatting divergence that only shows up once real JSON bytes
    // are involved.
    private fun toMap(o: JSONObject): Map<String, Any?> =
        o.keys().asSequence().associateWith { unwrap(o.get(it)) }
    private fun unwrap(v: Any?): Any? = when (v) {
        is JSONObject -> toMap(v)
        is JSONArray -> (0 until v.length()).map { unwrap(v.get(it)) }
        JSONObject.NULL -> null
        is java.math.BigDecimal -> v.toDouble()
        else -> v
    }

    private fun roundTrip(m: Map<String, Any?>): SignedMessage {
        val bytes = KotlinWire.canonical(m)
        val parsed = JSONObject(String(bytes, Charsets.US_ASCII))
        return SignedMessageKt.fromDict(toMap(parsed))
    }

    @Test fun composeEncKeySignatureVerifiesAndPayloadIsExact() {
        val devPriv = "33".repeat(32)
        val cert = KotlinWire.CertDict("11".repeat(32), devPub(devPriv), "phone", 1752900000.5, "00")
        val fixture = KotlinHandshake.Fixture(devPriv, devPub(devPriv), cert, "unused.onion:9000")

        val encPub = "ab".repeat(32)
        val createdAt = 1752900500.25
        val result = KotlinSync.composeEncKey(fixture, encPub, 1, createdAt)

        // Exact wire shape -- SignedMessage.to_dict()'s four keys, nothing more.
        assertEquals(setOf("cert", "seq", "payload", "signature"), result.keys)
        assertEquals(1, result["seq"])
        @Suppress("UNCHECKED_CAST")
        val payload = result["payload"] as Map<String, Any?>
        assertEquals(mapOf("kind" to "enckey", "enc_pub" to encPub, "created_at" to createdAt), payload)
        @Suppress("UNCHECKED_CAST")
        val certOut = result["cert"] as Map<String, Any?>
        assertEquals(
            mapOf("identity_pub" to cert.identity_pub, "device_pub" to cert.device_pub,
                "device_name" to cert.device_name, "enrolled_at" to cert.enrolled_at,
                "signature" to cert.signature),
            certOut)

        val msg = roundTrip(result)
        assertTrue("device signature must verify against cert.device_pub", msg.verifyDeviceSignature())
        assertEquals("enckey", msg.kind)
        assertEquals(1, msg.seq)
        assertEquals(encPub, msg.payload["enc_pub"])
        assertEquals(createdAt, msg.payload["created_at"] as Double, 0.0)
    }

    @Test fun composeEncKeyUsesRealCommittedVectorDeviceKey() {
        // Pin against the committed message_vectors.json fixture (the same one
        // SignedMessageTest gates SignedMessage verification against): its
        // cert's device_pub is the public half of the THROWAWAY key hardcoded
        // in android_tor_spike/tools/make_message_vectors.py as
        // `DVP = priv_from_hex("22" * 32)`. Reusing that exact (non-secret,
        // committed, throwaway) value here confirms composeEncKey's signing +
        // body construction is correct against a real hearth-verified device
        // keypair, not merely internally self-consistent.
        val text = javaClass.classLoader!!.getResourceAsStream("message_vectors.json")!!
            .readBytes().toString(Charsets.UTF_8)
        val vectorCert = JSONObject(text).getJSONArray("cases")
            .getJSONObject(0).getJSONObject("dict").getJSONObject("cert")

        val devPriv = "22".repeat(32)
        assertEquals(
            "the vector's cert.device_pub must be this throwaway key's public half",
            vectorCert.getString("device_pub"), devPub(devPriv))

        val cert = KotlinWire.CertDict(
            vectorCert.getString("identity_pub"), vectorCert.getString("device_pub"),
            vectorCert.getString("device_name"), vectorCert.getDouble("enrolled_at"),
            vectorCert.getString("signature"))
        val fixture = KotlinHandshake.Fixture(devPriv, cert.device_pub, cert, "unused.onion:9000")

        val result = KotlinSync.composeEncKey(fixture, "cd".repeat(32), 1, 1752900999.0)
        val msg = roundTrip(result)
        assertTrue("signature must verify against the real vector device key", msg.verifyDeviceSignature())
        assertEquals("enckey", msg.kind)
    }

    /** Task 8 (outbound blob push): Base64Portable.encode is new -- the BLOBS
     *  phase now uses it to serialize held blobs for the wire, and the SAME
     *  object's `decode` (already proven against real hearth-sent blobs by
     *  SyncLoopbackTest) must recover exactly what `encode` produced, for
     *  arbitrary bytes including the edge cases a naive codec gets wrong:
     *  empty input, high bytes (0xff, sign-extension bugs), zero bytes, and
     *  lengths that land on each of the three byte-count-mod-3 padding
     *  cases (0/1/2 trailing bytes -> 0/2/1 '=' pad chars). This is a pure
     *  self round-trip (no node involved) -- the actual wire-format parity
     *  with Python's base64.b64encode/hearth's decoder is asserted by
     *  inspection of the shared standard alphabet + padding (see
     *  Base64Portable's doc comment) and exercised live by
     *  SyncLoopbackTest's real node exchanges. */
    @Test fun base64PortableEncodeRoundTripsThroughDecode() {
        val cases = listOf(
            ByteArray(0),
            byteArrayOf(0),
            byteArrayOf(0, 0, 0),
            byteArrayOf(-1),                          // 0xff
            byteArrayOf(-1, -1),                       // 0xff 0xff
            byteArrayOf(-1, -1, -1),                   // 0xff 0xff 0xff
            byteArrayOf(0, -1, 127, -128, 1, 2, 3),
            ByteArray(256) { it.toByte() },            // every byte value 0..255
        )
        for (bytes in cases) {
            val encoded = Base64Portable.encode(bytes)
            // Padding shape must match standard base64: encoded length is a
            // multiple of 4, '=' only ever at the end.
            if (bytes.isNotEmpty()) assertEquals(0, encoded.length % 4)
            val decoded = Base64Portable.decode(encoded)
            assertTrue(
                "round trip must recover the original bytes exactly for ${bytes.toList()}",
                bytes.contentEquals(decoded))
        }

        // Pin against a known standard-base64 vector (padding included) so
        // this isn't merely internally self-consistent.
        assertEquals("Zm9vYmFy", Base64Portable.encode("foobar".toByteArray(Charsets.US_ASCII)))
        assertEquals("Zm9v", Base64Portable.encode("foo".toByteArray(Charsets.US_ASCII)))
        assertEquals("Zg==", Base64Portable.encode("f".toByteArray(Charsets.US_ASCII)))
    }

    // =======================================================================
    // run() -- REVOCATIONS phase ingest (phone-onion-reachability Task 3).
    // `genKeypair` is reused unqualified from GossipServerTest.kt (top-level
    // `internal fun` in the same package/test source set -- that file's own
    // doc comment already states the "mirror it, don't reinvent" rationale
    // for sharing it rather than duplicating a THIRD copy here).
    // RespondingStream (declared below, this file) works identically for
    // `run` as for `serve`: it only cares about the ORDER of `readExactSync`
    // calls, which is REVOCATIONS -> DEFRIENDS -> HAVE -> MESSAGES ->
    // BLOB_WANT -> BLOBS either way -- `run`'s writes (recorded into
    // `written`, never consulted by these tests) are simply interleaved
    // differently (write-then-read per phase, vs. serve's read-then-write).
    // =======================================================================

    /** A REALLY identity-signed RevocationCert -- mirrors RevocationCertTest's
     *  own `signedRevocation` helper exactly (duplicated here per this
     *  file's own stated precedent for RespondingStream: "a fresh
     *  file-scoped fake per test file" rather than a shared one). */
    private fun signedRevocation(
        identityPriv: String, identityPub: String, devicePub: String,
        lastValidSeq: Int, revokedAt: Double = 1752900000.0,
    ): RevocationCert {
        val unsigned = RevocationCert(identityPub, devicePub, lastValidSeq, revokedAt, "")
        return unsigned.copy(signature = KotlinWire.signRaw(identityPriv, unsigned.body()))
    }

    @Test fun runIngestsValidPeerRevocationForKnownFriendDeviceAndRetroDrops() {
        val store = InMemorySyncStore()
        val (identityPriv, identityPub) = genKeypair()
        val devicePriv = "44".repeat(32)
        val devicePub = devPub(devicePriv)
        store.addIdentity(identityPub)
        val m1 = identityMsg(identityPub, 1, mapOf("kind" to "profile", "name" to "s1", "created_at" to 1.0), devicePriv)
        val m2 = identityMsg(identityPub, 2, mapOf("kind" to "profile", "name" to "s2", "created_at" to 2.0), devicePriv)
        val m3 = identityMsg(identityPub, 3, mapOf("kind" to "profile", "name" to "s3", "created_at" to 3.0), devicePriv)
        assertTrue(store.ingestMessage(m1)); assertTrue(store.ingestMessage(m2)); assertTrue(store.ingestMessage(m3))

        val rev = signedRevocation(identityPriv, identityPub, devicePub, lastValidSeq = 2)
        val stream = RespondingStream(listOf(
            mapOf("t" to "revocations", "revs" to listOf(rev.toDict())),
            mapOf("t" to "defriends", "notices" to emptyList<Any?>()),
            mapOf("t" to "have", "summary" to emptyMap<String, Any?>(), "known" to emptyList<Any?>(),
                "peers" to emptyList<Any?>(), "addr" to null),
            mapOf("t" to "messages", "msgs" to emptyList<Any?>()),
            mapOf("t" to "blob_want", "hashes" to emptyList<Any?>()),
            mapOf("t" to "blobs", "blobs" to emptyMap<String, Any?>()),
        ))

        val result = KotlinSync.run(stream, store, ownDevicePub = "ff".repeat(32))
        assertTrue("expected Ok, got $result", result is SyncResult.Ok)
        assertTrue("device now revoked via wire-ingested cert", store.isRevokedDevice(devicePub))
        val remaining = store.allMessages().map { it.msgId }.toSet()
        assertTrue("seq<=lastValid kept (seq 1)", m1.msgId() in remaining)
        assertTrue("seq<=lastValid kept (seq 2)", m2.msgId() in remaining)
        assertFalse("seq>lastValid retro-dropped (seq 3) -- cross-check with Task 2's markRevoked", m3.msgId() in remaining)
    }

    @Test fun runDoesNotIngestRevocationForUnknownIdentity() {
        val store = InMemorySyncStore()
        val (identityPriv, identityPub) = genKeypair()   // deliberately NOT addIdentity'd
        val devicePub = "55".repeat(32)
        val rev = signedRevocation(identityPriv, identityPub, devicePub, lastValidSeq = 0)
        val stream = RespondingStream(listOf(
            mapOf("t" to "revocations", "revs" to listOf(rev.toDict())),
            mapOf("t" to "defriends", "notices" to emptyList<Any?>()),
            mapOf("t" to "have", "summary" to emptyMap<String, Any?>(), "known" to emptyList<Any?>(),
                "peers" to emptyList<Any?>(), "addr" to null),
            mapOf("t" to "messages", "msgs" to emptyList<Any?>()),
            mapOf("t" to "blob_want", "hashes" to emptyList<Any?>()),
            mapOf("t" to "blobs", "blobs" to emptyMap<String, Any?>()),
        ))
        val result = KotlinSync.run(stream, store, ownDevicePub = "ff".repeat(32))
        assertTrue("expected Ok, got $result", result is SyncResult.Ok)
        assertFalse("unknown identity -> never ingested", store.isRevokedDevice(devicePub))
    }

    @Test fun runSurfacesSelfRevokedWhenPeerRevocationNamesOwnDeviceRegardlessOfIngestOutcome() {
        val store = InMemorySyncStore()
        // A DIFFERENT identity, not known to us -- proves SelfRevoked fires
        // off the raw device_pub comparison, independent of whether
        // ingestRevocation itself would have accepted this cert.
        val (identityPriv, identityPub) = genKeypair()
        val ownDevicePub = "aa".repeat(32)
        val rev = signedRevocation(identityPriv, identityPub, ownDevicePub, lastValidSeq = 0)
        val stream = RespondingStream(listOf(
            mapOf("t" to "revocations", "revs" to listOf(rev.toDict())),
            // run() returns before reading any further frame.
        ))
        val result = KotlinSync.run(stream, store, ownDevicePub = ownDevicePub)
        assertEquals(SyncResult.SelfRevoked, result)
        assertFalse("unknown-identity cert was still not ingested", store.isRevokedDevice(ownDevicePub))
    }

    @Test fun runDoesNotIngestRevocationWithTamperedSignature() {
        val store = InMemorySyncStore()
        val (identityPriv, identityPub) = genKeypair()
        val devicePub = "66".repeat(32)
        store.addIdentity(identityPub)
        val good = signedRevocation(identityPriv, identityPub, devicePub, lastValidSeq = 0)
        val tampered = good.copy(last_valid_seq = 999)   // signature no longer matches body()
        val stream = RespondingStream(listOf(
            mapOf("t" to "revocations", "revs" to listOf(tampered.toDict())),
            mapOf("t" to "defriends", "notices" to emptyList<Any?>()),
            mapOf("t" to "have", "summary" to emptyMap<String, Any?>(), "known" to emptyList<Any?>(),
                "peers" to emptyList<Any?>(), "addr" to null),
            mapOf("t" to "messages", "msgs" to emptyList<Any?>()),
            mapOf("t" to "blob_want", "hashes" to emptyList<Any?>()),
            mapOf("t" to "blobs", "blobs" to emptyMap<String, Any?>()),
        ))
        val result = KotlinSync.run(stream, store, ownDevicePub = "ff".repeat(32))
        assertTrue("expected Ok, got $result", result is SyncResult.Ok)
        assertFalse("tampered signature -> never ingested", store.isRevokedDevice(devicePub))
    }

    /** Code review fix: a GENUINE self-revocation is NOT the "never known"
     *  case the other run() revocation tests exercise -- our own identity_pub
     *  IS seeded into knownIdentities() in production, both at pairing
     *  (KotlinPairing.kt's installPackage: `store.addIdentity(cert.identity_pub)`)
     *  and on every sync (SyncRunner.kt's runTransport:
     *  `SqliteSyncStore(ctx).also { it.addIdentity(fx.cert.identity_pub) }`).
     *  This test seeds `store.addIdentity(ownIdentityPub)` FIRST (mirroring
     *  that production seeding) so a real, validly identity-signed
     *  self-revocation cert actually reaches ingestRevocation's is_known
     *  gate and passes it -- proving `markRevoked` genuinely runs (own
     *  device marked revoked, own prior messages retro-dropped) BEFORE
     *  `SelfRevoked` is returned, not that `SelfRevoked` merely fires on an
     *  ingest that was rejected. If the `SelfRevoked` return were ever
     *  dropped from `run`, this test would fail differently than the
     *  assertion below expects: with only one canned frame in the
     *  `RespondingStream`, `run` would fall through to the DEFRIENDS read
     *  and throw ("RespondingStream exhausted"), producing
     *  `SyncResult.Failed(...)`, not `SyncResult.Ok` -- either way NOT
     *  `SyncResult.SelfRevoked`, so `assertEquals(SyncResult.SelfRevoked,
     *  result)` below would fail. */
    @Test fun runIngestsGenuineOwnSelfRevocationThenStillReturnsSelfRevoked() {
        val store = InMemorySyncStore()
        val (ownIdentityPriv, ownIdentityPub) = genKeypair()
        val ownDevicePriv = "cc".repeat(32)
        val ownDevicePub = devPub(ownDevicePriv)
        store.addIdentity(ownIdentityPub)   // mirrors production seeding (KotlinPairing.kt / SyncRunner.kt)

        val m1 = identityMsg(ownIdentityPub, 1, mapOf("kind" to "profile", "name" to "s1", "created_at" to 1.0), ownDevicePriv)
        val m2 = identityMsg(ownIdentityPub, 2, mapOf("kind" to "profile", "name" to "s2", "created_at" to 2.0), ownDevicePriv)
        val m3 = identityMsg(ownIdentityPub, 3, mapOf("kind" to "profile", "name" to "s3", "created_at" to 3.0), ownDevicePriv)
        assertTrue(store.ingestMessage(m1)); assertTrue(store.ingestMessage(m2)); assertTrue(store.ingestMessage(m3))

        val rev = signedRevocation(ownIdentityPriv, ownIdentityPub, ownDevicePub, lastValidSeq = 2)
        val stream = RespondingStream(listOf(
            mapOf("t" to "revocations", "revs" to listOf(rev.toDict())),
            // run() must return SelfRevoked right after this element -- no further frame is ever read.
        ))

        val result = KotlinSync.run(stream, store, ownDevicePub = ownDevicePub)
        assertEquals("SelfRevoked must still surface for the Task 6 wipe hook, even though " +
            "ingestRevocation genuinely ran first (own identity IS known)", SyncResult.SelfRevoked, result)
        assertTrue("ingestRevocation's real effects happened: own device now marked revoked",
            store.isRevokedDevice(ownDevicePub))
        val remaining = store.allMessages().map { it.msgId }.toSet()
        assertTrue("seq<=lastValid kept (seq 1)", m1.msgId() in remaining)
        assertTrue("seq<=lastValid kept (seq 2)", m2.msgId() in remaining)
        assertFalse("seq>lastValid retro-dropped (seq 3) -- OWN messages, the real path", m3.msgId() in remaining)
    }

    // =======================================================================
    // serve() -- the RESPONDER content phases (gossip server Task 3).
    // Mirrors _session (sync.py:643-825) in REVERSE I/O order vs. `run`
    // above (responder = read-then-write per phase, KotlinHandshake.kt:76).
    // Called AFTER KotlinHandshake.respondHandshake already authenticated
    // the peer -- serve() never re-verifies peerCert, it trusts it as the
    // scoping identity (never a frame claim).
    //
    // RespondingStream mirrors KotlinHandshakeTest's own fake of the same
    // name (queued read-frames, captured writes) -- duplicated here rather
    // than shared, following that file's own precedent of a fresh
    // file-scoped fake per test file (KotlinHandshakeTest.kt:16-25).
    // =======================================================================

    private class RespondingStream(readFrames: List<Map<String, Any?>>) : Stream {
        private val buffer: ByteArray = readFrames.fold(ByteArray(0)) { acc, f -> acc + KotlinWire.writeFrameBytes(f) }
        private var pos = 0
        val written = mutableListOf<JSONObject>()
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
        override fun close() {}
    }

    // Builds a SIGNED message for an EXPLICIT identity_pub, mirroring
    // SyncStoreTest's identityMsg/devicePubOf idiom exactly (reusing this
    // file's own devPub() in place of a second devicePubOf()).
    private fun identityMsg(identityPub: String, seq: Int, payload: Map<String, Any?>, devPrivHex: String): SignedMessage {
        val devicePub = devPub(devPrivHex)
        val cert = KotlinWire.CertDict(identityPub, devicePub, "d", 1752900000.0, "00")
        val unsigned = SignedMessage(cert, seq, payload, "")
        return unsigned.copy(signature = KotlinWire.signRaw(devPrivHex, unsigned.body()))
    }

    // This device's (the RESPONDER's) own fixture -- serve() reads
    // fixture.cert.identity_pub (own-device-trust comparison) and
    // fixture.device_pub (REVOCATIONS self-revoked check). No real
    // signature needed: serve() never calls KotlinWire.verifyCert on its
    // own fixture.cert.
    private fun buildFixture(identityPub: String, devicePriv: String = "aa".repeat(32)): KotlinHandshake.Fixture {
        val devicePub = devPub(devicePriv)
        val cert = KotlinWire.CertDict(identityPub, devicePub, "Own Device", 1752900000.0, "00")
        return KotlinHandshake.Fixture(devicePriv, devicePub, cert, "unused.onion:9000")
    }

    private fun sha(b: ByteArray) = KotlinWire.toHex(java.security.MessageDigest.getInstance("SHA-256").digest(b))

    /** MESSAGES phase: the entitled delta is written, a RING record is
     *  excluded even though its author IS entitled (author-private gate --
     *  mirrors SyncStoreTest's own messagesNotInServesEntitledAndNeverOverServes,
     *  but driven through serve()'s wire protocol instead of calling
     *  store.messagesNotIn directly), a POST from an author known to US but
     *  NOT reported in the peer's own HAVE.known is excluded by the
     *  entitled-SET INTERSECTION specifically (code review, MEDIUM -- the
     *  ring-record exclusion above is independent of entitled entirely, since
     *  RING's author-private gate fires regardless of entitlement, so it does
     *  NOT by itself prove serve() computes `entitled = knownIdentities ∩
     *  peerKnown` rather than just `entitled = knownIdentities()`; this case
     *  is wrapped to the peer's device, so the wrap-set gate alone WOULD
     *  serve it -- only the intersection excludes it, and this assertion
     *  fails if that intersection is bypassed), an offered message from an
     *  already-known identity is ingested, and -- since the peer here is NOT
     *  fixture's own sibling device -- own-device trust must NOT adopt an
     *  identity the peer's `known` reports that we don't already know (the
     *  HAVE-phase negative). */
    @Test fun serveMessagesPhaseServesEntitledDeltaExcludesRingAndIngestsOfferedMessage() {
        val store = InMemorySyncStore()
        val fixture = buildFixture("aa".repeat(32))   // unrelated to the peer below

        val friendPub = "b1".repeat(32); val friendDevPriv = "b2".repeat(32)
        val ringAuthorPub = "c1".repeat(32); val ringAuthorDevPriv = "c2".repeat(32)
        val peerPub = "d1".repeat(32); val peerDevPriv = "d2".repeat(32)
        val peerDevPub = devPub(peerDevPriv)
        val unknownToUsPub = "ee".repeat(32)   // peer reports it as "known"; we must never adopt it (non-sibling)
        // Known to US (addIdentity below) but NOT reported by the peer's own
        // HAVE.known -- excluded ONLY by the entitled intersection, since its
        // post below IS wrapped to the peer's device (wrap-set gate alone
        // would pass it).
        val notMutualFriendPub = "f1".repeat(32); val notMutualFriendDevPriv = "f2".repeat(32)

        store.addIdentity(friendPub)
        store.addIdentity(ringAuthorPub)
        store.addIdentity(peerPub)
        store.addIdentity(notMutualFriendPub)
        // Seed the peer's own device so deviceViews(peerPub) is non-empty --
        // needed for the wrap-set gate on the POSTs below.
        assertTrue(store.ingestMessage(identityMsg(peerPub, 1,
            mapOf("kind" to "profile", "name" to "Peer", "created_at" to 1.0), peerDevPriv)))

        val kredsFriendPost = identityMsg(friendPub, 1, mapOf(
            "kind" to "post", "scope" to "kreds", "text" to "hi",
            "wraps" to mapOf(peerDevPub to mapOf("x" to 1)), "blobs" to emptyList<String>()), friendDevPriv)
        assertTrue(store.ingestMessage(kredsFriendPost))

        val innerRingRecord = identityMsg(ringAuthorPub, 1, mapOf(
            "kind" to "ring", "member" to "cc".repeat(32), "ring" to "inner", "created_at" to 1.0), ringAuthorDevPriv)
        assertTrue(store.ingestMessage(innerRingRecord))

        // Wrapped to the peer's device -- the wrap-set gate alone would
        // serve this. Only exclusion from `entitled` (peerKnown does NOT
        // list notMutualFriendPub below) can block it.
        val postFromNonMutualFriend = identityMsg(notMutualFriendPub, 1, mapOf(
            "kind" to "post", "scope" to "kreds", "text" to "not mutual",
            "wraps" to mapOf(peerDevPub to mapOf("x" to 1)), "blobs" to emptyList<String>()), notMutualFriendDevPriv)
        assertTrue(store.ingestMessage(postFromNonMutualFriend))

        val peerCert = KotlinWire.CertDict(peerPub, peerDevPub, "Peer Phone", 1752900000.0, "")

        // The peer OFFERS a new message from the already-entitled friend
        // identity -- must be ingested via the existing verify/ingest gates.
        val offered = identityMsg(friendPub, 2,
            mapOf("kind" to "profile", "name" to "Friend", "created_at" to 2.0), friendDevPriv)

        val stream = RespondingStream(listOf(
            mapOf("t" to "revocations", "revs" to emptyList<Any?>()),
            mapOf("t" to "defriends", "notices" to emptyList<Any?>()),
            mapOf("t" to "have", "summary" to emptyMap<String, Any?>(),
                // notMutualFriendPub deliberately absent -- the peer does not
                // report it as known, so it must fall out of the intersection.
                "known" to listOf(friendPub, ringAuthorPub, unknownToUsPub),
                "peers" to emptyList<Any?>(), "addr" to "127.0.0.1:9999"),
            mapOf("t" to "messages", "msgs" to listOf(offered.toDict())),
            mapOf("t" to "blob_want", "hashes" to emptyList<Any?>()),
            mapOf("t" to "blobs", "blobs" to emptyMap<String, Any?>()),
        ))

        val result = KotlinSync.serve(stream, store, fixture, peerCert)
        assertTrue("expected Ok, got $result", result is SyncResult.Ok)

        assertEquals("6 frames written: revocations, defriends, have, messages, blob_want, blobs",
            6, stream.written.size)
        val wroteMessages = stream.written[3]
        assertEquals("messages", wroteMessages.getString("t"))
        val msgsArr = wroteMessages.getJSONArray("msgs")
        val servedIds = (0 until msgsArr.length()).map { SignedMessageKt.fromDict(toMap(msgsArr.getJSONObject(it))).msgId() }
        assertTrue("entitled post wrapped to the peer's device -> served", kredsFriendPost.msgId() in servedIds)
        assertFalse("RING is author-private -> never relayed to a friend, even an entitled one",
            innerRingRecord.msgId() in servedIds)
        assertFalse("author known to us but NOT reported by the peer's HAVE.known -> excluded by " +
            "the entitled INTERSECTION, even though the wrap-set gate alone would pass it " +
            "(this must fail if serve() used entitled=knownIdentities() instead of the peerKnown intersection)",
            postFromNonMutualFriend.msgId() in servedIds)

        assertTrue("offered message from an already-known identity is ingested",
            store.allMessages().any { it.msgId == offered.msgId() })

        assertFalse("a non-sibling peer's `known` must never auto-widen our friend graph",
            store.knownIdentities().contains(unknownToUsPub))

        val wroteHave = stream.written[2]
        assertEquals("have", wroteHave.getString("t"))
        assertEquals("peers dropped (arc 3, no peer table yet)", 0, wroteHave.getJSONArray("peers").length())
        assertEquals("no loopback addr concept yet at this arc", "", wroteHave.getString("addr"))
    }

    /** BLOBS phase: smallest-first within BLOB_GIVE_BUDGET (a wanted blob
     *  too large to fit alongside a smaller one is excluded, proving the
     *  sort -- not just "everything requested was given"), and an offered
     *  blob from the peer is verified + stored. BLOB_GIVE_BUDGET is
     *  temporarily shrunk (its whole purpose -- see its doc comment in
     *  KotlinSync.kt) since the production budget (~15 MiB) can't
     *  realistically be exceeded by small JVM-test fixtures. */
    @Test fun serveBlobsPhaseGivesSmallestFirstWithinBudgetAndStoresOfferedBlob() {
        val store = InMemorySyncStore()
        val fixture = buildFixture("aa".repeat(32))
        val peerPub = "d1".repeat(32); val peerDevPriv = "d2".repeat(32)
        val peerCert = KotlinWire.CertDict(peerPub, devPub(peerDevPriv), "Peer", 1752900000.0, "")

        val smallData = ByteArray(10) { it.toByte() }
        val largeData = ByteArray(200) { it.toByte() }
        val smallHash = sha(smallData); val largeHash = sha(largeData)
        assertTrue(store.putBlob(smallHash, smallData))
        assertTrue(store.putBlob(largeHash, largeData))
        val smallB64Len = Base64Portable.encode(smallData).length

        val savedBudget = BLOB_GIVE_BUDGET
        BLOB_GIVE_BUDGET = smallB64Len + 1   // small alone fits; small+large does not
        try {
            val offeredData = byteArrayOf(9, 9, 9)
            val offeredHash = sha(offeredData)
            val stream = RespondingStream(listOf(
                mapOf("t" to "revocations", "revs" to emptyList<Any?>()),
                mapOf("t" to "defriends", "notices" to emptyList<Any?>()),
                mapOf("t" to "have", "summary" to emptyMap<String, Any?>(), "known" to emptyList<Any?>(),
                    "peers" to emptyList<Any?>(), "addr" to ""),
                mapOf("t" to "messages", "msgs" to emptyList<Any?>()),
                // Order deliberately large-then-small on the wire -- the
                // give side must sort by size itself, not rely on request order.
                mapOf("t" to "blob_want", "hashes" to listOf(largeHash, smallHash)),
                mapOf("t" to "blobs", "blobs" to mapOf(offeredHash to Base64Portable.encode(offeredData))),
            ))

            val result = KotlinSync.serve(stream, store, fixture, peerCert)
            assertTrue("expected Ok, got $result", result is SyncResult.Ok)

            val wroteBlobs = stream.written[5]
            assertEquals("blobs", wroteBlobs.getString("t"))
            val givenKeys = wroteBlobs.getJSONObject("blobs").keys().asSequence().toSet()
            assertEquals("only the SMALLER wanted blob fits the constrained budget",
                setOf(smallHash), givenKeys)

            assertArrayEquals("offered blob was verified (hash+size) and stored",
                offeredData, store.getBlob(offeredHash))
        } finally {
            BLOB_GIVE_BUDGET = savedBudget
        }
    }

    /** HAVE phase, own-device trust: a peer authenticated under fixture's
     *  OWN identity (a sibling device, sync.py:768-772) has its reported
     *  `known` identities adopted into our own knownIdentities(). */
    @Test fun serveOwnDeviceSiblingPeerAdoptsPeersKnownIdentities() {
        val store = InMemorySyncStore()
        val ownIdentityPub = "aa".repeat(32)
        val fixture = buildFixture(ownIdentityPub)
        val siblingDevPriv = "bb".repeat(32)
        val peerCert = KotlinWire.CertDict(ownIdentityPub, devPub(siblingDevPriv), "Sibling Phone", 1752900000.0, "")

        val newFriendPub = "cc".repeat(32)
        assertFalse("not yet known before this sync round", store.knownIdentities().contains(newFriendPub))

        val stream = RespondingStream(listOf(
            mapOf("t" to "revocations", "revs" to emptyList<Any?>()),
            mapOf("t" to "defriends", "notices" to emptyList<Any?>()),
            mapOf("t" to "have", "summary" to emptyMap<String, Any?>(), "known" to listOf(newFriendPub),
                "peers" to emptyList<Any?>(), "addr" to ""),
            mapOf("t" to "messages", "msgs" to emptyList<Any?>()),
            mapOf("t" to "blob_want", "hashes" to emptyList<Any?>()),
            mapOf("t" to "blobs", "blobs" to emptyMap<String, Any?>()),
        ))

        val result = KotlinSync.serve(stream, store, fixture, peerCert)
        assertTrue("expected Ok, got $result", result is SyncResult.Ok)
        assertTrue("own-device trust adopts the sibling's known identity",
            store.knownIdentities().contains(newFriendPub))
    }

    // =======================================================================
    // serve() -- REVOCATIONS phase ingest (phone-onion-reachability Task 3).
    // Same read-call ORDER as `run`'s own REVOCATIONS tests above (REVOCATIONS
    // -> DEFRIENDS -> HAVE -> MESSAGES -> BLOB_WANT -> BLOBS) -- only the
    // write/read INTERLEAVING per phase differs (responder: read-then-write).
    // =======================================================================

    @Test fun serveIngestsValidPeerRevocationForKnownFriendDeviceAndRetroDrops() {
        val store = InMemorySyncStore()
        val fixture = buildFixture("aa".repeat(32))
        val peerPub = "d1".repeat(32); val peerDevPriv = "d2".repeat(32)
        val peerCert = KotlinWire.CertDict(peerPub, devPub(peerDevPriv), "Peer", 1752900000.0, "")

        val (identityPriv, identityPub) = genKeypair()
        val devicePriv = "77".repeat(32)
        val devicePub = devPub(devicePriv)
        store.addIdentity(identityPub)
        val m1 = identityMsg(identityPub, 1, mapOf("kind" to "profile", "name" to "s1", "created_at" to 1.0), devicePriv)
        val m2 = identityMsg(identityPub, 2, mapOf("kind" to "profile", "name" to "s2", "created_at" to 2.0), devicePriv)
        val m3 = identityMsg(identityPub, 3, mapOf("kind" to "profile", "name" to "s3", "created_at" to 3.0), devicePriv)
        assertTrue(store.ingestMessage(m1)); assertTrue(store.ingestMessage(m2)); assertTrue(store.ingestMessage(m3))

        val rev = signedRevocation(identityPriv, identityPub, devicePub, lastValidSeq = 2)
        val stream = RespondingStream(listOf(
            mapOf("t" to "revocations", "revs" to listOf(rev.toDict())),
            mapOf("t" to "defriends", "notices" to emptyList<Any?>()),
            mapOf("t" to "have", "summary" to emptyMap<String, Any?>(), "known" to emptyList<Any?>(),
                "peers" to emptyList<Any?>(), "addr" to ""),
            mapOf("t" to "messages", "msgs" to emptyList<Any?>()),
            mapOf("t" to "blob_want", "hashes" to emptyList<Any?>()),
            mapOf("t" to "blobs", "blobs" to emptyMap<String, Any?>()),
        ))

        val result = KotlinSync.serve(stream, store, fixture, peerCert)
        assertTrue("expected Ok, got $result", result is SyncResult.Ok)
        assertTrue("device now revoked via wire-ingested cert", store.isRevokedDevice(devicePub))
        val remaining = store.allMessages().map { it.msgId }.toSet()
        assertTrue("seq<=lastValid kept (seq 1)", m1.msgId() in remaining)
        assertTrue("seq<=lastValid kept (seq 2)", m2.msgId() in remaining)
        assertFalse("seq>lastValid retro-dropped (seq 3) -- cross-check with Task 2's markRevoked", m3.msgId() in remaining)
    }

    @Test fun serveDoesNotIngestRevocationForUnknownIdentity() {
        val store = InMemorySyncStore()
        val fixture = buildFixture("aa".repeat(32))
        val peerPub = "d1".repeat(32); val peerDevPriv = "d2".repeat(32)
        val peerCert = KotlinWire.CertDict(peerPub, devPub(peerDevPriv), "Peer", 1752900000.0, "")

        val (identityPriv, identityPub) = genKeypair()   // deliberately NOT addIdentity'd
        val devicePub = "88".repeat(32)
        val rev = signedRevocation(identityPriv, identityPub, devicePub, lastValidSeq = 0)
        val stream = RespondingStream(listOf(
            mapOf("t" to "revocations", "revs" to listOf(rev.toDict())),
            mapOf("t" to "defriends", "notices" to emptyList<Any?>()),
            mapOf("t" to "have", "summary" to emptyMap<String, Any?>(), "known" to emptyList<Any?>(),
                "peers" to emptyList<Any?>(), "addr" to ""),
            mapOf("t" to "messages", "msgs" to emptyList<Any?>()),
            mapOf("t" to "blob_want", "hashes" to emptyList<Any?>()),
            mapOf("t" to "blobs", "blobs" to emptyMap<String, Any?>()),
        ))

        val result = KotlinSync.serve(stream, store, fixture, peerCert)
        assertTrue("expected Ok, got $result", result is SyncResult.Ok)
        assertFalse("unknown identity -> never ingested", store.isRevokedDevice(devicePub))
    }

    @Test fun serveSurfacesSelfRevokedWhenPeerRevocationNamesOwnDeviceRegardlessOfIngestOutcome() {
        val ownIdentityPub = "aa".repeat(32)
        val fixture = buildFixture(ownIdentityPub)   // fixture.device_pub is devPub("aa"-fixture's default "aa".repeat(32) priv)
        val store = InMemorySyncStore()
        val peerPub = "d1".repeat(32); val peerDevPriv = "d2".repeat(32)
        val peerCert = KotlinWire.CertDict(peerPub, devPub(peerDevPriv), "Peer", 1752900000.0, "")

        // A DIFFERENT identity, not known to us -- proves SelfRevoked fires
        // off the raw device_pub comparison, independent of whether
        // ingestRevocation itself would have accepted this cert.
        val (identityPriv, identityPub) = genKeypair()
        val rev = signedRevocation(identityPriv, identityPub, fixture.device_pub, lastValidSeq = 0)
        val stream = RespondingStream(listOf(
            mapOf("t" to "revocations", "revs" to listOf(rev.toDict())),
            // serve() returns before reading any further frame.
        ))

        val result = KotlinSync.serve(stream, store, fixture, peerCert)
        assertEquals(SyncResult.SelfRevoked, result)
        assertFalse("unknown-identity cert was still not ingested", store.isRevokedDevice(fixture.device_pub))
    }

    @Test fun serveDoesNotIngestRevocationWithTamperedSignature() {
        val store = InMemorySyncStore()
        val fixture = buildFixture("aa".repeat(32))
        val peerPub = "d1".repeat(32); val peerDevPriv = "d2".repeat(32)
        val peerCert = KotlinWire.CertDict(peerPub, devPub(peerDevPriv), "Peer", 1752900000.0, "")

        val (identityPriv, identityPub) = genKeypair()
        val devicePub = "99".repeat(32)
        store.addIdentity(identityPub)
        val good = signedRevocation(identityPriv, identityPub, devicePub, lastValidSeq = 0)
        val tampered = good.copy(last_valid_seq = 999)   // signature no longer matches body()
        val stream = RespondingStream(listOf(
            mapOf("t" to "revocations", "revs" to listOf(tampered.toDict())),
            mapOf("t" to "defriends", "notices" to emptyList<Any?>()),
            mapOf("t" to "have", "summary" to emptyMap<String, Any?>(), "known" to emptyList<Any?>(),
                "peers" to emptyList<Any?>(), "addr" to ""),
            mapOf("t" to "messages", "msgs" to emptyList<Any?>()),
            mapOf("t" to "blob_want", "hashes" to emptyList<Any?>()),
            mapOf("t" to "blobs", "blobs" to emptyMap<String, Any?>()),
        ))

        val result = KotlinSync.serve(stream, store, fixture, peerCert)
        assertTrue("expected Ok, got $result", result is SyncResult.Ok)
        assertFalse("tampered signature -> never ingested", store.isRevokedDevice(devicePub))
    }

    /** Code review fix -- serve() mirror of
     *  runIngestsGenuineOwnSelfRevocationThenStillReturnsSelfRevoked: our own
     *  identity IS known in production (seeded at pairing/every sync, and
     *  serve() reads/writes the SAME on-disk store as run()), so this seeds
     *  `store.addIdentity(ownIdentityPub)` FIRST and proves a genuine,
     *  validly-signed self-revocation actually runs ingestRevocation's real
     *  effects (own device marked revoked, own prior messages retro-dropped)
     *  before `SelfRevoked` is returned -- not that `SelfRevoked` merely
     *  fires on a rejected ingest. */
    @Test fun serveIngestsGenuineOwnSelfRevocationThenStillReturnsSelfRevoked() {
        val (ownIdentityPriv, ownIdentityPub) = genKeypair()
        val ownDevicePriv = "dd".repeat(32)
        val fixture = buildFixture(ownIdentityPub, ownDevicePriv)
        val store = InMemorySyncStore()
        store.addIdentity(ownIdentityPub)   // mirrors production seeding (KotlinPairing.kt / SyncRunner.kt)
        val peerPub = "d1".repeat(32); val peerDevPriv = "d2".repeat(32)
        val peerCert = KotlinWire.CertDict(peerPub, devPub(peerDevPriv), "Peer", 1752900000.0, "")

        val m1 = identityMsg(ownIdentityPub, 1, mapOf("kind" to "profile", "name" to "s1", "created_at" to 1.0), ownDevicePriv)
        val m2 = identityMsg(ownIdentityPub, 2, mapOf("kind" to "profile", "name" to "s2", "created_at" to 2.0), ownDevicePriv)
        val m3 = identityMsg(ownIdentityPub, 3, mapOf("kind" to "profile", "name" to "s3", "created_at" to 3.0), ownDevicePriv)
        assertTrue(store.ingestMessage(m1)); assertTrue(store.ingestMessage(m2)); assertTrue(store.ingestMessage(m3))

        val rev = signedRevocation(ownIdentityPriv, ownIdentityPub, fixture.device_pub, lastValidSeq = 2)
        val stream = RespondingStream(listOf(
            mapOf("t" to "revocations", "revs" to listOf(rev.toDict())),
            // serve() must return SelfRevoked right after this element -- no further frame is ever read.
        ))

        val result = KotlinSync.serve(stream, store, fixture, peerCert)
        assertEquals("SelfRevoked must still surface for the Task 6 wipe hook, even though " +
            "ingestRevocation genuinely ran first (own identity IS known)", SyncResult.SelfRevoked, result)
        assertTrue("ingestRevocation's real effects happened: own device now marked revoked",
            store.isRevokedDevice(fixture.device_pub))
        val remaining = store.allMessages().map { it.msgId }.toSet()
        assertTrue("seq<=lastValid kept (seq 1)", m1.msgId() in remaining)
        assertTrue("seq<=lastValid kept (seq 2)", m2.msgId() in remaining)
        assertFalse("seq>lastValid retro-dropped (seq 3) -- OWN messages, the real path", m3.msgId() in remaining)
    }
}
