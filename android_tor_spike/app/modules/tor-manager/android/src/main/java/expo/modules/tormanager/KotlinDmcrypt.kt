package expo.modules.tormanager

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONObject

/** Kotlin port of hearth.dmcrypt's reader half (unwrap + body decrypt),
 *  byte-matched to hearth via dmcrypt_vectors.json. AAD via KotlinWire. */
object KotlinDmcrypt {

    fun postAad(author: String, scope: String, createdAt: Double): ByteArray =
        KotlinWire.canonical(mapOf(
            "type" to "post-aad", "protocol" to KotlinWire.PROTOCOL,
            "from" to author, "scope" to scope,
            "created_at" to KotlinWire.PyFloat(createdAt)))

    fun dmAad(sender: String, to: String, createdAt: Double): ByteArray =
        KotlinWire.canonical(mapOf(
            "type" to "dm-aad", "protocol" to KotlinWire.PROTOCOL,
            "from" to sender, "to" to to,
            "created_at" to KotlinWire.PyFloat(createdAt)))

    private fun deriveKek(shared: ByteArray): ByteArray {
        val out = ByteArray(32)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(shared, null, "hearth/dm-wrap/v1".toByteArray()))
        hkdf.generateBytes(out, 0, 32)
        return out
    }

    /** ChaCha20-Poly1305 decrypt (12-byte nonce), returns null on auth failure.
     *  BouncyCastle's AEAD (not javax.crypto.Cipher) so JVM tests and Android
     *  behave identically with no minSdk-28 gate (binding resolution, B.2 Task 1). */
    private fun chachaOpen(key: ByteArray, nonce: ByteArray, ct: ByteArray, aad: ByteArray): ByteArray? = try {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(ct.size))
        var len = cipher.processBytes(ct, 0, ct.size, out, 0)
        len += cipher.doFinal(out, len)
        if (len == out.size) out else out.copyOf(len)
    } catch (e: Exception) { null }

    fun unwrapKey(wrap: Map<String, Any?>, encPrivHex: String, aad: ByteArray): ByteArray? {
        return try {
            val ephPub = KotlinWire.fromHex(wrap["eph_pub"] as String)
            val priv = X25519PrivateKeyParameters(KotlinWire.fromHex(encPrivHex), 0)
            val shared = ByteArray(32)
            X25519Agreement().apply { init(priv) }.calculateAgreement(
                X25519PublicKeyParameters(ephPub, 0), shared, 0)
            val kek = deriveKek(shared)
            chachaOpen(kek, KotlinWire.fromHex(wrap["nonce"] as String),
                KotlinWire.fromHex(wrap["wrapped_key"] as String), aad)
        } catch (e: Exception) { null }
    }

    fun decryptBody(contentKey: ByteArray, bodyNonceHex: String, bodyCtHex: String, aad: ByteArray): Map<String, Any?>? {
        val plain = chachaOpen(contentKey, KotlinWire.fromHex(bodyNonceHex), KotlinWire.fromHex(bodyCtHex), aad)
            ?: return null
        val o = JSONObject(String(plain, Charsets.UTF_8))
        return o.keys().asSequence().associateWith { o.get(it) }
    }
}
