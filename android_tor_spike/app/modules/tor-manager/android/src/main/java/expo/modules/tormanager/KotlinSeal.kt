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

/** Kotlin port of hearth.dmcrypt's seal_slots/try_open_slots (B.2d-4 Task 1,
 *  engagement-privacy mutual box): anonymous per-recipient slots, each an
 *  ephemeral-X25519 + ChaCha20-Poly1305 box carrying NO recipient
 *  identifier -- recipients trial-open. Padded with byte-random dummy
 *  slots to a fixed bucket so the slot count only buckets, never measures,
 *  the sender's friend-device count. Byte-matched to hearth.dmcrypt via
 *  the same X25519/HKDF/ChaCha construction KotlinDmcrypt already proved
 *  out; only the HKDF info string, AAD, and padding/shuffle differ. */
object KotlinSeal {

    private val MUTUAL_BOX_AAD = "hearth/mutual-box/v1".toByteArray()
    private val SLOT_BUCKETS = intArrayOf(8, 16, 32, 64)

    // "-kek" suffix exists purely to keep this HKDF info string visually
    // distinct from MUTUAL_BOX_AAD (the AEAD associated data) -- the two
    // are independent mechanisms and never needed to differ, but a shared
    // literal invited misreading them as coupled (mirrors dmcrypt.py's
    // _derive_slot_kek comment).
    private fun deriveSlotKek(shared: ByteArray): ByteArray {
        val out = ByteArray(32)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(shared, null, "hearth/mutual-box-kek/v1".toByteArray()))
        hkdf.generateBytes(out, 0, 32)
        return out
    }

    // Same BouncyCastle AEAD call shape as KotlinDmcrypt.chachaOpen/chachaSeal
    // (not javax.crypto.Cipher) so JVM tests and Android behave identically.
    private fun chachaOpen(key: ByteArray, nonce: ByteArray, ct: ByteArray, aad: ByteArray): ByteArray? = try {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(ct.size))
        var len = cipher.processBytes(ct, 0, ct.size, out, 0)
        len += cipher.doFinal(out, len)
        if (len == out.size) out else out.copyOf(len)
    } catch (e: Exception) { null }

    private fun chachaSeal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(plain.size))
        val n = c.processBytes(plain, 0, plain.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    private fun randomBytes(n: Int): ByteArray =
        ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }

    /** Seal `payload` into one anonymous slot per (well-formed) enc_pub in
     *  `encPubs`, then pad with byte-random dummies up to the smallest
     *  bucket in (8,16,32,64) that is >= the real slot count, then shuffle.
     *  Throws if the real count exceeds the largest bucket (64). */
    fun sealSlots(payload: ByteArray, encPubs: List<String>): List<Map<String, String>> {
        val real = mutableListOf<Map<String, String>>()
        var ctLen = payload.size + 16   // ChaCha20-Poly1305 adds a 16-byte tag; overwritten below once a real ct exists
        for (encPub in encPubs) {
            val peer = try {
                X25519PublicKeyParameters(KotlinWire.fromHex(encPub))
            } catch (e: Exception) { continue }        // skip malformed enc keys, like wrapKey
            val eph = X25519PrivateKeyParameters(java.security.SecureRandom())
            val shared = ByteArray(32)
            X25519Agreement().apply { init(eph) }.calculateAgreement(peer, shared, 0)
            val kek = deriveSlotKek(shared)
            val nonce = randomBytes(12)
            val ct = chachaSeal(kek, nonce, payload, MUTUAL_BOX_AAD)
            ctLen = ct.size
            real.add(mapOf(
                "eph_pub" to KotlinWire.toHex(eph.generatePublicKey().encoded),
                "nonce" to KotlinWire.toHex(nonce),
                "ct" to KotlinWire.toHex(ct)))
        }
        val bucket = SLOT_BUCKETS.firstOrNull { it >= real.size }
            ?: throw IllegalArgumentException("too many recipients for a mutual box")
        while (real.size < bucket) {
            real.add(mapOf(
                "eph_pub" to KotlinWire.toHex(randomBytes(32)),
                "nonce" to KotlinWire.toHex(randomBytes(12)),
                "ct" to KotlinWire.toHex(randomBytes(ctLen))))
        }
        // Shuffle is cosmetic hardening only (breaks "first N slots are the
        // real ones" positional leak) -- it is NOT the anonymity mechanism.
        // Anonymity comes from the sealed-box construction itself: slots
        // carry no recipient identifier and dummies are byte-indistinguishable
        // from real slots, so trial-opening is the only way to find a match.
        // Manual Fisher-Yates with SecureRandom (order is cosmetic, not the
        // anonymity mechanism -- see comment above).
        val rng = java.security.SecureRandom()
        for (i in real.size - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = real[i]; real[i] = real[j]; real[j] = tmp
        }
        return real
    }

    /** Trial-open every slot with this device's enc priv key; the one
     *  sealed to this device decrypts, all others (other recipients' and
     *  dummies) fail AEAD auth. Returns the first authenticating payload,
     *  or null if none authenticate. */
    fun tryOpenSlots(slots: List<Map<String, String>>, encPrivHex: String): ByteArray? {
        val priv = try {
            X25519PrivateKeyParameters(KotlinWire.fromHex(encPrivHex), 0)
        } catch (e: Exception) { return null }
        for (s in slots) {
            try {
                val ephPub = KotlinWire.fromHex(s["eph_pub"] ?: continue)
                val shared = ByteArray(32)
                X25519Agreement().apply { init(priv) }.calculateAgreement(
                    X25519PublicKeyParameters(ephPub, 0), shared, 0)
                val kek = deriveSlotKek(shared)
                val opened = chachaOpen(
                    kek, KotlinWire.fromHex(s["nonce"] ?: continue),
                    KotlinWire.fromHex(s["ct"] ?: continue), MUTUAL_BOX_AAD)
                if (opened != null) return opened
            } catch (e: Exception) { continue }
        }
        return null
    }
}
