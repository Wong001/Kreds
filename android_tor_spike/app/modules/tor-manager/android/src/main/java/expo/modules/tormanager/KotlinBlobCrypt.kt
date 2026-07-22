package expo.modules.tormanager

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/** Kotlin port of hearth.dmcrypt.decrypt_blob: ChaCha20-Poly1305 over
 *  cipher[12:] with nonce cipher[:12] and the constant BLOB_AAD. Blobs use
 *  the SAME per-message content key as the body. Vector-gated. */
object KotlinBlobCrypt {
    private val BLOB_AAD = "hearth/dm-blob/v1".toByteArray()

    fun decryptBlob(contentKey: ByteArray, cipher: ByteArray): ByteArray? {
        if (cipher.size < 13) return null
        return try {
            val nonce = cipher.copyOfRange(0, 12)
            val ct = cipher.copyOfRange(12, cipher.size)
            val c = ChaCha20Poly1305()
            c.init(false, AEADParameters(KeyParameter(contentKey), 128, nonce, BLOB_AAD))
            val out = ByteArray(c.getOutputSize(ct.size))
            var len = c.processBytes(ct, 0, ct.size, out, 0)
            len += c.doFinal(out, len)   // throws on auth failure
            if (len == out.size) out else out.copyOf(len)
        } catch (e: Exception) { null }
    }

    fun encryptBlob(contentKey: ByteArray, data: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(contentKey), 128, nonce, BLOB_AAD))
        val out = ByteArray(c.getOutputSize(data.size))
        val n = c.processBytes(data, 0, data.size, out, 0)
        c.doFinal(out, n)
        return nonce + out
    }
}
