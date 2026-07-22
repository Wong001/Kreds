package expo.modules.tormanager

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SignedMessageTest {
    private fun cases(): JSONArray {
        val text = javaClass.classLoader!!.getResourceAsStream("message_vectors.json")!!
            .readBytes().toString(Charsets.UTF_8)
        return JSONObject(text).getJSONArray("cases")
    }

    // JSONObject -> Map<String,Any?> (payload is arbitrary JSON)
    private fun toMap(o: JSONObject): Map<String, Any?> =
        o.keys().asSequence().associateWith { unwrap(o.get(it)) }
    private fun unwrap(v: Any?): Any? = when (v) {
        is JSONObject -> toMap(v)
        is JSONArray -> (0 until v.length()).map { unwrap(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }

    @Test fun verifyAndMsgId() {
        val cs = cases()
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            val m = SignedMessageKt.fromDict(toMap(c.getJSONObject("dict")))
            assertEquals("case $i valid", c.getBoolean("valid"), m.verifyDeviceSignature())
            assertEquals("case $i kind", c.getString("kind"), m.kind)
            if (!c.isNull("msg_id")) {
                assertEquals(c.getString("body_hex"), KotlinWire.toHex(m.body()))
                assertEquals(c.getString("msg_id"), m.msgId())
            }
        }
    }

    @Test fun signedMessageToDictRoundTripsThroughFromDict() {
        val cert = KotlinWire.CertDict("aa".repeat(32), "bb".repeat(32), "phone", 1752900000.0, "cc".repeat(64))
        val m = SignedMessage(cert, 5, mapOf("kind" to "enckey", "enc_pub" to "dd".repeat(32), "created_at" to 1752900001.0), "ee".repeat(64))
        val back = SignedMessageKt.fromDict(m.toDict())
        assertEquals(m.cert.identity_pub, back.cert.identity_pub)
        assertEquals(m.cert.device_pub, back.cert.device_pub)
        assertEquals(m.seq, back.seq); assertEquals(m.signature, back.signature)
        assertEquals(m.payload["enc_pub"], back.payload["enc_pub"])
    }
}
