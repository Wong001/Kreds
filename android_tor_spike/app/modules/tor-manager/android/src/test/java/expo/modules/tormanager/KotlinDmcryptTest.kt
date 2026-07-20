package expo.modules.tormanager

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
}
