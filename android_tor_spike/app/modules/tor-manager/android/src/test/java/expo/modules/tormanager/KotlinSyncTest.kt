package expo.modules.tormanager

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
