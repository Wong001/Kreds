package expo.modules.tormanager

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinDmcryptTest {
    private fun cases(): JSONArray {
        val t = javaClass.classLoader!!.getResourceAsStream("dmcrypt_vectors.json")!!
            .readBytes().toString(Charsets.UTF_8)
        return JSONObject(t).getJSONArray("cases")
    }
    private fun map(o: JSONObject): Map<String, Any?> =
        o.keys().asSequence().associateWith { o.get(it) }

    @Test fun unwrapAndDecrypt() {
        val cs = cases()
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            // The shared fixture also carries "blob" cases (KotlinBlobCryptTest,
            // B.2d Task 1) which have no wrap/body_nonce/body_ct -- this test
            // covers only the post/dm wrap+body path.
            if (c.getString("kind") != "post" && c.getString("kind") != "dm") continue
            val aad = if (c.getString("kind") == "post")
                KotlinDmcrypt.postAad(c.getString("author"), c.getString("scope"), c.getDouble("created_at"))
            else
                KotlinDmcrypt.dmAad(c.getString("author"), c.getString("to"), c.getDouble("created_at"))
            val key = KotlinDmcrypt.unwrapKey(map(c.getJSONObject("wrap")), c.getString("enc_priv"), aad)
            assertNotNull("case $i unwrap", key)
            assertArrayEquals("case $i key", KotlinWire.fromHex(c.getString("content_key")), key)
            val body = KotlinDmcrypt.decryptBody(key!!, c.getString("body_nonce"), c.getString("body_ct"), aad)
            assertNotNull("case $i decrypt", body)
            assertEquals(c.getJSONObject("plaintext").getString("text"), body!!["text"])
        }
    }

    /** KIND_RESPONSES round-trip (B.2d-4 Task 1): the "responses" case is a
     *  REAL hearth-aggregated record (author compose_post + two responders'
     *  compose_response + the author's process_responses fold) -- not
     *  hand-built JSON -- so decrypting it here proves responsesAad's AAD
     *  byte-matches hearth.dmcrypt.responses_aad against genuine ciphertext. */
    @Test fun responsesRecordDecrypts() {
        val cs = cases()
        var found = false
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            if (c.getString("kind") != "responses") continue
            found = true
            val aad = KotlinDmcrypt.responsesAad(
                c.getString("author"), c.getString("target"), c.getDouble("created_at"))
            val key = KotlinDmcrypt.unwrapKey(map(c.getJSONObject("wrap")), c.getString("enc_priv"), aad)
            assertNotNull("responses case unwrap", key)
            assertArrayEquals("responses case key", KotlinWire.fromHex(c.getString("content_key")), key)
            val body = KotlinDmcrypt.decryptBody(key!!, c.getString("body_nonce"), c.getString("body_ct"), aad)
            assertNotNull("responses case decrypt", body)
            val entries = body!!["entries"] as JSONArray
            assertEquals(c.getJSONArray("entries").length(), entries.length())
            var sawPublicComment = false
            var sawPrivateReaction = false
            for (j in 0 until entries.length()) {
                val e = entries.getJSONObject(j)
                when (e.getString("rkind")) {
                    "comment" -> {
                        assertEquals("nice post!", e.getString("body"))
                        assertEquals(c.getString("public_responder_identity"), e.getString("identity"))
                        assertEquals(c.getString("public_responder_device"), e.getString("device_pub"))
                        assertEquals(c.getString("public_responder_sig"), e.getString("responder_sig"))
                        sawPublicComment = true
                    }
                    "reaction" -> {
                        assertEquals("heart", e.getString("body"))
                        assertFalse("private entry must not carry identity", e.has("identity"))
                        sawPrivateReaction = true
                    }
                }
            }
            assertTrue("public comment entry missing", sawPublicComment)
            assertTrue("private reaction entry missing", sawPrivateReaction)
        }
        assertTrue("no \"responses\" case found in fixture", found)
    }

    @Test fun encryptBodyRoundTripsThroughDecryptBody() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val aad = KotlinDmcrypt.postAad("id".repeat(32), "kreds", 1752900000.5)
        val body = mapOf("text" to "hello world", "blobs" to listOf("ab".repeat(32)))
        val (nonceHex, ctHex) = KotlinDmcrypt.encryptBody(key, body, aad)
        assertEquals("24-hex nonce", 24, nonceHex.length)
        assertTrue("hex ct", ctHex.isNotEmpty() && ctHex.all { it in "0123456789abcdef" })
        val back = KotlinDmcrypt.decryptBody(key, nonceHex, ctHex, aad)!!
        assertEquals("hello world", back["text"])
        // decryptBody returns JSON arrays as org.json.JSONArray (see
        // responsesRecordDecrypts), not a Kotlin List, so compare element-wise.
        val blobsBack = back["blobs"] as JSONArray
        assertEquals(1, blobsBack.length())
        assertEquals("ab".repeat(32), blobsBack.getString(0))
        // wrong AAD -> null (binding holds)
        val badAad = KotlinDmcrypt.postAad("id".repeat(32), "inner", 1752900000.5)
        assertNull(KotlinDmcrypt.decryptBody(key, nonceHex, ctHex, badAad))
    }

    @Test fun wrapKeyRoundTripsThroughUnwrapKey() {
        // recipient X25519 keypair via EncKeys' generator shape
        val recipPriv = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(java.security.SecureRandom())
        val recipPrivHex = KotlinWire.toHex(recipPriv.encoded)
        val recipPubHex = KotlinWire.toHex(recipPriv.generatePublicKey().encoded)
        val key = ByteArray(32) { (it + 7).toByte() }
        val aad = KotlinDmcrypt.postAad("id".repeat(32), "kreds", 1752900000.5)
        val wraps = KotlinDmcrypt.wrapKey(key, mapOf("dev01234" to recipPubHex), aad)
        val w = wraps["dev01234"]!!
        assertEquals(64, w["eph_pub"]!!.length); assertEquals(24, w["nonce"]!!.length)
        assertTrue(w["wrapped_key"]!!.isNotEmpty())
        // unwrapKey (already-proven inverse) recovers the key
        val recovered = KotlinDmcrypt.unwrapKey(w, recipPrivHex, aad)!!
        assertArrayEquals(key, recovered)
        // malformed enc_pub is skipped, not thrown
        assertTrue(KotlinDmcrypt.wrapKey(key, mapOf("bad" to "zz"), aad).isEmpty())
    }

    @Test fun responseAadShapeAndAliasSeedDeterministic() {
        val aad = KotlinDmcrypt.responseAad("id".repeat(32), "t".repeat(32), 1752900000.5)
        val s = String(aad)
        assertTrue(s.contains("\"type\":\"response-aad\"") && s.contains("\"from\":\"" + "id".repeat(32) + "\""))
        assertTrue(s.contains("\"target\":\"" + "t".repeat(32) + "\"") && s.contains("\"created_at\":1752900000.5"))
        // deriveAliasSeed: deterministic per (device, target), 32 hex chars, differs by target
        val dpriv = "22".repeat(32)
        val a1 = KotlinDmcrypt.deriveAliasSeed(dpriv, "post1")
        val a2 = KotlinDmcrypt.deriveAliasSeed(dpriv, "post1")
        val b = KotlinDmcrypt.deriveAliasSeed(dpriv, "post2")
        assertEquals(32, a1.length)
        assertEquals(a1, a2)
        assertNotEquals(a1, b)
        // Cross-check with Python: derive_alias_seed("22"*32, "post1") = "ca19e85f36529f393784b52a001e6133"
        assertEquals("ca19e85f36529f393784b52a001e6133", a1)
        assertEquals("63befa3c46778d1b346f5a3d805f5880", b)
    }
}
