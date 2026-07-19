package expo.modules.tormanager

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for DecryptPass (B.2 Task 5): decrypts own POST/DM messages via
 *  either an inline `wraps[phoneDevicePub]` entry or a backfilled
 *  wrap_grant, using REAL hearth-parity crypto material from the committed
 *  dmcrypt_vectors.json fixture (Task 1, extended for this task with two
 *  more post cases sharing case 0's enc keypair -- see
 *  make_dmcrypt_vectors.py) -- not hand-rolled ciphertext, so a passing
 *  test proves the AAD/unwrap/decrypt path end-to-end, the same way
 *  KotlinDmcryptTest does for the primitives alone. */
class DecryptPassTest {
    // Arbitrary hex64 "device" pubkeys for these tests -- ingestMessage/
    // verifyDeviceSignature never validate that a device_pub is a REAL
    // derived Ed25519 point (see SyncStoreTest.kt's own msg() helper and
    // devicePair() below); phoneDevicePub is just the map key DecryptPass
    // looks up in payload.wraps / a grant's wraps, exactly like production.
    private val phoneDevicePub = "44".repeat(32)
    private val otherDevicePub = "55".repeat(32)

    private fun cases(): JSONArray {
        val t = javaClass.classLoader!!.getResourceAsStream("dmcrypt_vectors.json")!!
            .readBytes().toString(Charsets.UTF_8)
        return JSONObject(t).getJSONArray("cases")
    }

    // org.json -> plain Kotlin bridge, same idiom as KotlinDmcryptTest/
    // SignedMessageTest/KotlinSyncTest's own private copies.
    private fun jsonToMap(o: JSONObject): Map<String, Any?> =
        o.keys().asSequence().associateWith { unwrap(o.get(it)) }
    private fun unwrap(v: Any?): Any? = when (v) {
        is JSONObject -> jsonToMap(v)
        is JSONArray -> (0 until v.length()).map { unwrap(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }

    // A fresh Ed25519 DEVICE keypair per constructed message. identity_pub
    // is passed straight through as-is (the fixture's "author"/"to" hex
    // strings) -- it is just signed DATA in the canonical message body,
    // never itself verified as a real derived key (only cert.device_pub is,
    // via verifyDeviceSignature). Mirrors SyncStoreTest.kt's msg() helper.
    private fun devicePair(devPrivHex: String): Pair<String, String> =
        devPrivHex to KotlinWire.toHex(
            Ed25519PrivateKeyParameters(KotlinWire.fromHex(devPrivHex), 0).generatePublicKey().encoded)

    private fun signedMessage(identityPub: String, seq: Int, payload: Map<String, Any?>, devPrivHex: String): SignedMessage {
        val (devPriv, devPub) = devicePair(devPrivHex)
        val cert = KotlinWire.CertDict(identityPub, devPub, "d", 1752900000.0, "00")
        val unsigned = SignedMessage(cert, seq, payload, "")
        return unsigned.copy(signature = KotlinWire.signRaw(devPriv, unsigned.body()))
    }

    private fun postPayload(c: JSONObject, wraps: Map<String, Any?>): Map<String, Any?> = mapOf(
        "kind" to "post", "scope" to c.getString("scope"),
        "created_at" to c.getDouble("created_at"),
        "body_nonce" to c.getString("body_nonce"), "body_ct" to c.getString("body_ct"),
        "wraps" to wraps, "blobs" to emptyList<String>(),
    )

    private fun dmPayload(c: JSONObject, wraps: Map<String, Any?>): Map<String, Any?> = mapOf(
        "kind" to "dm", "to" to c.getString("to"),
        "created_at" to c.getDouble("created_at"),
        "body_nonce" to c.getString("body_nonce"), "body_ct" to c.getString("body_ct"),
        "wraps" to wraps, "blobs" to emptyList<String>(),
    )

    @Test fun decryptsPostViaInlineWrap() {
        val c = cases().getJSONObject(0)
        assertEquals("post", c.getString("kind"))
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val msg = signedMessage(c.getString("author"), 1, postPayload(c, wraps), "a1".repeat(32))
        assertTrue(store.ingestMessage(msg))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"))
        assertEquals(1, out.size)
        assertEquals(msg.msgId(), out[0].msgId)
        assertEquals("post", out[0].kind)
        assertEquals(c.getJSONObject("plaintext").getString("text"), out[0].text)
        assertEquals(c.getDouble("created_at"), out[0].createdAt, 0.0)
    }

    @Test fun decryptsDmViaBackfilledWrapGrantNotInlineWraps() {
        val c = cases().getJSONObject(1)
        assertEquals("dm", c.getString("kind"))
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        // NOT wrapped to our device inline -- only some OTHER device's wrap
        // rides in the payload, so the backfill grant is the ONLY path to
        // the content key.
        val inlineWraps = mapOf(otherDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val msg = signedMessage(c.getString("author"), 1, dmPayload(c, inlineWraps), "b1".repeat(32))
        assertTrue(store.ingestMessage(msg))

        val grantPayload = mapOf(
            "kind" to "wrap_grant", "target" to msg.msgId(),
            "created_at" to c.getDouble("created_at"),
            "wraps" to mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap"))),
        )
        val grant = signedMessage(c.getString("author"), 2, grantPayload, "b1".repeat(32))
        assertTrue(store.ingestMessage(grant))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"))
        assertEquals(1, out.size)
        assertEquals("dm", out[0].kind)
        assertEquals(c.getJSONObject("plaintext").getString("text"), out[0].text)
    }

    @Test fun skipsWrongDeviceAndTamperedCiphertextWithoutCrashing() {
        val c = cases().getJSONObject(0)
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))

        // (a) wrapped only to a DIFFERENT device, no grant at all -- our
        // device has no way in.
        val wrongDeviceWraps = mapOf(otherDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val m1 = signedMessage(c.getString("author"), 1, postPayload(c, wrongDeviceWraps), "c1".repeat(32))
        assertTrue(store.ingestMessage(m1))

        // (b) wrapped to OUR device, but body_ct is tampered -- decryptBody
        // must fail AEAD auth; DecryptPass must skip it, not throw.
        val goodCt = c.getString("body_ct")
        val tamperedCt = goodCt.dropLast(1) + (if (goodCt.last() == '0') '1' else '0')
        val tamperedPayload = postPayload(c, mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap"))))
            .toMutableMap().apply { put("body_ct", tamperedCt) }
        val m2 = signedMessage(c.getString("author"), 2, tamperedPayload, "c2".repeat(32))
        assertTrue(store.ingestMessage(m2))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"))
        assertEquals("neither message should decrypt (wrong device / tampered ct)", 0, out.size)
    }

    @Test fun ordersNewestFirstByCreatedAt() {
        val cs = cases()
        val postCases = (0 until cs.length()).map { cs.getJSONObject(it) }.filter { it.getString("kind") == "post" }
        val encPriv = postCases[0].getString("enc_priv")
        // Only the cases sharing ONE recipient enc keypair can all be
        // decrypted by a single DecryptPass.run call (a real phone has one
        // enc key) -- the fixture's ordering-specific cases (2-3) share
        // case 0's enc_priv by construction; see make_dmcrypt_vectors.py.
        val sameKeyCases = postCases.filter { it.getString("enc_priv") == encPriv }
        assertTrue("need >=3 post cases sharing one enc key for a meaningful ordering assertion",
            sameKeyCases.size >= 3)

        val store = InMemorySyncStore()
        store.addIdentity(sameKeyCases[0].getString("author"))
        for ((i, c) in sameKeyCases.withIndex()) {
            val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
            val msg = signedMessage(c.getString("author"), i + 1, postPayload(c, wraps), ("d%d".format(i + 1)).repeat(32))
            assertTrue(store.ingestMessage(msg))
        }

        val out = DecryptPass.run(store, phoneDevicePub, encPriv)
        assertEquals(sameKeyCases.size, out.size)
        val expectedOrder = sameKeyCases.map { it.getDouble("created_at") }.sortedDescending()
        assertEquals(expectedOrder, out.map { it.createdAt })
    }
}
