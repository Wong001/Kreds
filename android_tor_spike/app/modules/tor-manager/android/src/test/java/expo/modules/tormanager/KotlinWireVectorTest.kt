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

    /** Gates the exported writeFrameBytes (Task 3's KotlinHandshake depends
     *  on it). NOT a byte-exact frame_hex comparison: hearth.transport
     *  .write_frame (Python) uses plain json.dumps with NO sort_keys, so
     *  frame_hex preserves each vector's original dict-literal key order
     *  (e.g. {"t":"hello","nonce":"00ff"} has "t" first, which the
     *  generator at tools/make_wire_vectors.py:92 confirms). Only
     *  hearth.identity.canonical() sorts. writeFrameBytes deliberately
     *  mirrors wire.ts's writeFrame, which always reuses the SORTED
     *  canonical() serializer regardless -- its own docstring calls this
     *  safe "because the Python reader is key-order-agnostic" -- and
     *  wire.test.ts's frame cases only round-trip (write then read back
     *  and compare parsed objects), never assert byte-exact frame_hex,
     *  for exactly this reason. Matching that same structural contract
     *  here (length-prefix correctness + order-agnostic JSON equality)
     *  is what actually gates writeFrameBytes' correctness; asserting
     *  raw hex equality would fail on frame_case 0/1 even though
     *  writeFrameBytes is doing exactly what its accepted contract says. */
    @Test fun frameVectorsVerify() {
        val cases = vectors().getJSONArray("frame_cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            @Suppress("UNCHECKED_CAST")
            val obj = revive(c.getJSONObject("obj")) as Map<String, Any?>
            val frame = KotlinWire.writeFrameBytes(obj)

            val declaredLen = ((frame[0].toInt() and 0xff) shl 24) or ((frame[1].toInt() and 0xff) shl 16) or
                ((frame[2].toInt() and 0xff) shl 8) or (frame[3].toInt() and 0xff)
            assertEquals("frame_case $i length prefix", frame.size - 4, declaredLen)

            val gotPayload = JSONObject(String(frame, 4, frame.size - 4, Charsets.US_ASCII))
            val frameHex = c.getString("frame_hex")
            val wantPayload = JSONObject(
                String(KotlinWire.fromHex(frameHex.substring(8)), Charsets.US_ASCII))
            // JSONObject.similar() isn't available on this compile classpath
            // (the Android SDK's stub org.json.JSONObject shadows the real
            // org.json:20240303 jar here); compare manually instead. All
            // frame_cases values are plain strings, so key-set + value
            // equality is exact structural equality.
            val gotKeys = gotPayload.keys().asSequence().toSet()
            val wantKeys = wantPayload.keys().asSequence().toSet()
            assertEquals("frame_case $i keys", wantKeys, gotKeys)
            for (k in wantKeys) assertEquals("frame_case $i key $k", wantPayload.get(k), gotPayload.get(k))
        }
    }

    /** Direct-assertion gate (Task 3 review finding, no shared-vector change):
     *  Java's Double.toString() pads single-significant-digit mantissas with
     *  a mandatory ".0" (e.g. 0.0005 -> "5.0E-4"), and pyFloatRepr's reformat
     *  used to treat that filler "0" as a real digit, over-rendering
     *  "0.0005" as "0.00050". Pin the fix with literal expected bytes so it
     *  can't regress silently. */
    @Test fun pyFloatSmallMagnitude() {
        assertEquals("{\"t\":0.0005}",
            String(KotlinWire.canonical(mapOf("t" to KotlinWire.PyFloat(0.0005))), Charsets.US_ASCII))
        assertEquals("{\"t\":0.0001}",
            String(KotlinWire.canonical(mapOf("t" to KotlinWire.PyFloat(0.0001))), Charsets.US_ASCII))
        assertEquals("{\"t\":0.00025}",
            String(KotlinWire.canonical(mapOf("t" to KotlinWire.PyFloat(0.00025))), Charsets.US_ASCII))
        assertEquals("{\"t\":1234.5}",
            String(KotlinWire.canonical(mapOf("t" to KotlinWire.PyFloat(1234.5))), Charsets.US_ASCII))
    }
}
