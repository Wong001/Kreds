package expo.modules.tormanager

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinWireVectorTest {
    private fun vectors(): JSONObject {
        val text = javaClass.classLoader!!.getResourceAsStream("wire_vectors.json")!!
            .readBytes().toString(Charsets.UTF_8)
        return JSONObject(text)
    }

    /** Turn a vector "obj" (with {"__pyfloat__": n} markers) into the
     *  Map<String,Any?> / PyFloat shape KotlinWire.canonical expects. */
    private fun revive(v: Any?): Any? = when (v) {
        is JSONObject -> {
            if (v.length() == 1 && v.has("__pyfloat__")) KotlinWire.PyFloat(v.getDouble("__pyfloat__"))
            else v.keys().asSequence().associateWith { revive(v.get(it)) }
        }
        is JSONArray -> (0 until v.length()).map { revive(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }

    @Test fun canonicalMatchesPython() {
        val cases = vectors().getJSONArray("canonical_cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            @Suppress("UNCHECKED_CAST")
            val obj = revive(c.getJSONObject("obj")) as Map<String, Any?>
            assertEquals(c.getString("name"), c.getString("bytes_hex"),
                KotlinWire.toHex(KotlinWire.canonical(obj)))
        }
    }

    @Test fun authVectorsVerify() {
        val cases = vectors().getJSONArray("auth_cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val body = KotlinWire.authBody(c.getString("nonce"))
            assertEquals(c.getString("body_hex"), KotlinWire.toHex(body))
            assertEquals(c.getString("sig"), KotlinWire.signRaw(c.getString("device_priv"), body))
            assertTrue(KotlinWire.verifyRaw(c.getString("device_pub"), c.getString("sig"), body))
        }
    }

    @Test fun certVectorsVerify() {
        val cases = vectors().getJSONArray("cert_cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val cd = c.getJSONObject("cert")
            val cert = KotlinWire.CertDict(
                cd.getString("identity_pub"), cd.getString("device_pub"),
                cd.getString("device_name"), cd.getDouble("enrolled_at"),
                cd.getString("signature"))
            assertEquals("cert_case $i", c.getBoolean("valid"),
                KotlinWire.verifyCert(cert))
            if (!c.isNull("body_hex"))
                assertEquals(c.getString("body_hex"), KotlinWire.toHex(KotlinWire.certBody(cert)))
        }
    }
}
