package expo.modules.tormanager

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            if (!c.isNull("msg_id")) {
                assertEquals(c.getString("body_hex"), KotlinWire.toHex(m.body()))
                assertEquals(c.getString("msg_id"), m.msgId())
            }
        }
    }
}
