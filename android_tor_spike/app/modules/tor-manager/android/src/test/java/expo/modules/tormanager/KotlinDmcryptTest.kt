package expo.modules.tormanager

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
