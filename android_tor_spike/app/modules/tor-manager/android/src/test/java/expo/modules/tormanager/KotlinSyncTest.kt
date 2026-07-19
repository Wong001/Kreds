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
}
