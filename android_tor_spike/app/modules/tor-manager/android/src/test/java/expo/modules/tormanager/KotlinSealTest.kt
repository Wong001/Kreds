package expo.modules.tormanager

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B.2d-4 Task 1 -- KotlinSeal: anonymous per-recipient mutual-box slots
 *  (sealSlots/tryOpenSlots), Kotlin port of hearth.dmcrypt.seal_slots /
 *  try_open_slots. Byte-matches the Python via the same X25519 + HKDF +
 *  ChaCha20-Poly1305 construction KotlinDmcrypt already proved out. */
class KotlinSealTest {
    @Test fun sealSlotsOpenRoundTripAndStrangerAndBucket() {
        fun kp() = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(java.security.SecureRandom())
        val a = kp(); val b = kp(); val stranger = kp()
        val aPubHex = KotlinWire.toHex(a.generatePublicKey().encoded)
        val bPubHex = KotlinWire.toHex(b.generatePublicKey().encoded)
        val payload = "hi".repeat(20).toByteArray()
        val slots = KotlinSeal.sealSlots(payload, listOf(aPubHex, bPubHex))
        assertEquals("padded to first bucket >= 2", 8, slots.size)   // _SLOT_BUCKETS
        // a recipient recovers the payload; a stranger does not
        assertArrayEquals(payload, KotlinSeal.tryOpenSlots(slots, KotlinWire.toHex(a.encoded)))
        assertArrayEquals(payload, KotlinSeal.tryOpenSlots(slots, KotlinWire.toHex(b.encoded)))
        assertNull(KotlinSeal.tryOpenSlots(slots, KotlinWire.toHex(stranger.encoded)))
        // malformed enc_pub skipped, not thrown
        assertEquals(8, KotlinSeal.sealSlots(payload, listOf(aPubHex, "zz")).size)
    }

    @Test fun sealSlotsBucketsGrow() {
        val payload = "x".toByteArray()
        val pubs = (0 until 9).map { KotlinWire.toHex(
            org.bouncycastle.crypto.params.X25519PrivateKeyParameters(java.security.SecureRandom()).generatePublicKey().encoded) }
        assertEquals("9 real -> bucket 16", 16, KotlinSeal.sealSlots(payload, pubs).size)
    }
}
