package expo.modules.tormanager

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM test for Compose (Task 5, outbound slice 1): proves the FULL compose
 *  chain -- content key, encryptBody, wrapKey, make_post-shaped payload,
 *  device-sign, local ingest -- without a node, by round-tripping the
 *  composed message's OWN-DEVICE wrap back through the already-proven
 *  KotlinDmcrypt.unwrapKey/decryptBody. */
class ComposeTest {
    // A locally-minted device keypair + a cert whose identity_pub we
    // control. Mirrors DecryptPassTest's devicePair()/signedMessage()
    // idiom: cert.signature is never itself verified on this path (only
    // ingestMessage's verifyDeviceSignature is exercised, which checks
    // cert.device_pub against a signature made with fx.device_priv).
    private fun testFixture(): KotlinHandshake.Fixture {
        val devPriv = "a1".repeat(32)
        val devPub = KotlinWire.toHex(
            Ed25519PrivateKeyParameters(KotlinWire.fromHex(devPriv), 0).generatePublicKey().encoded)
        val identityPub = "b2".repeat(32)
        val cert = KotlinWire.CertDict(identityPub, devPub, "d", 1752900000.0, "00")
        return KotlinHandshake.Fixture(devPriv, devPub, cert, "dummy.onion:9001")
    }

    // Signs a payload with fx.device_priv -- copies composeEncKey's
    // (KotlinSync.kt) sign idiom: throwaway-signature SignedMessage, then
    // .copy(signature = ...) with the real Ed25519 signature over body().
    private fun SignedMessageSigned(fx: KotlinHandshake.Fixture, seq: Int, payload: Map<String, Any?>): SignedMessage {
        val unsigned = SignedMessage(fx.cert, seq, payload, "")
        return unsigned.copy(signature = KotlinWire.signRaw(fx.device_priv, unsigned.body()))
    }

    @Test fun composePostBuildsDecryptableKredsPost() {
        val s = InMemorySyncStore()
        val fx = testFixture()                      // helper: Fixture w/ known device_priv/pub + cert.identity_pub
        s.addIdentity(fx.cert.identity_pub)
        val (encPriv, encPub) = EncKeys.getOrCreate(s)          // own X25519
        // publish own enckey so enckeys(own) resolves this device
        s.ingestMessage(SignedMessageSigned(fx, s.nextSeq(), mapOf(
            "kind" to "enckey", "enc_pub" to encPub, "created_at" to 100.0)))
        val res = Compose.post(s, fx, encPriv, encPub, "hello kreds", emptyList(), "kreds", 1752900000.5)
        // the composed message is now in the store; find it + decrypt its body
        val stored = s.allMessages().first { it.msgId == res.msgId }
        val payload = stored.payload
        assertEquals("post", payload["kind"]); assertEquals("kreds", payload["scope"])
        assertEquals("journal", payload["placement"]); assertEquals("photo", payload["media"])
        assertNull(payload["poster"])
        val aad = KotlinDmcrypt.postAad(fx.cert.identity_pub, "kreds", 1752900000.5)
        @Suppress("UNCHECKED_CAST") val wraps = payload["wraps"] as Map<String, Any?>
        val myWrap = wraps[fx.device_pub] as Map<String, Any?>       // wrapped to own device
        val key = KotlinDmcrypt.unwrapKey(myWrap, encPriv, aad)!!
        val body = KotlinDmcrypt.decryptBody(key, payload["body_nonce"] as String, payload["body_ct"] as String, aad)!!
        assertEquals("hello kreds", body["text"])
        // decryptBody returns JSON arrays as org.json.JSONArray, not a Kotlin
        // List (same quirk KotlinDmcryptTest's encryptBodyRoundTripsThrough-
        // DecryptBody documents) -- emptyList<String>().equals(JSONArray) is
        // always false since JSONArray doesn't implement java.util.List, so
        // compare shape instead of using assertEquals against a Kotlin list.
        assertEquals(0, (body["blobs"] as org.json.JSONArray).length())
    }
}
