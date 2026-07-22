package expo.modules.tormanager

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinBlobCryptTest {
    private fun blobCase(): JSONObject {
        val t = javaClass.classLoader!!.getResourceAsStream("dmcrypt_vectors.json")!!
            .readBytes().toString(Charsets.UTF_8)
        val cs = JSONObject(t).getJSONArray("cases")
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            if (c.getString("kind") == "blob") return c
        }
        throw IllegalStateException("no blob vector")
    }

    @Test fun decryptsBlobToExactBytes() {
        val c = blobCase()
        val key = KotlinWire.fromHex(c.getString("content_key"))
        val cipher = KotlinWire.fromHex(c.getString("cipher"))
        val got = KotlinBlobCrypt.decryptBlob(key, cipher)
        assertArrayEquals(KotlinWire.fromHex(c.getString("plain")), got)
    }

    @Test fun wrongKeyReturnsNull() {
        val c = blobCase()
        val bad = ByteArray(32) { 0x11 }
        assertNull(KotlinBlobCrypt.decryptBlob(bad, KotlinWire.fromHex(c.getString("cipher"))))
    }

    @Test fun shortInputReturnsNull() {
        assertNull(KotlinBlobCrypt.decryptBlob(ByteArray(32), ByteArray(5)))
    }

    @Test fun encryptBlobRoundTripsThroughDecryptBlob() {
        val key = ByteArray(32) { (it + 3).toByte() }
        val data = ByteArray(5000) { (it % 256).toByte() }
        val cipher = KotlinBlobCrypt.encryptBlob(key, data)
        assertTrue("nonce+ct+tag longer than plain", cipher.size == data.size + 12 + 16)
        assertArrayEquals(data, KotlinBlobCrypt.decryptBlob(key, cipher))
        // wrong key -> null
        assertNull(KotlinBlobCrypt.decryptBlob(ByteArray(32), cipher))
    }
}
