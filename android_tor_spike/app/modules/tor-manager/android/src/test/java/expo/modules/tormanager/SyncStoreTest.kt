package expo.modules.tormanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class SyncStoreTest {
    private val idp = "11".repeat(32); private val dvp = "22".repeat(32)
    private val idPub = KotlinWire.toHex(org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(KotlinWire.fromHex(idp), 0).generatePublicKey().encoded)
    private fun sha(b: ByteArray) = KotlinWire.toHex(MessageDigest.getInstance("SHA-256").digest(b))

    // Build a REAL signed message via the same primitives (device key = 0x22..).
    private fun msg(seq: Int, payload: Map<String, Any?>): SignedMessage {
        // sign with device priv 0x22.. so verifyDeviceSignature passes;
        // identity_pub/device_pub are the matching pubs.
        val devPriv = "22".repeat(32)
        val idPub = KotlinWire.toHex(org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(KotlinWire.fromHex("11".repeat(32)), 0).generatePublicKey().encoded)
        val dvPub = KotlinWire.toHex(org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(KotlinWire.fromHex(devPriv), 0).generatePublicKey().encoded)
        val cert = KotlinWire.CertDict(idPub, dvPub, "d", 1752900000.0, "00")
        val unsigned = SignedMessage(cert, seq, payload, "")
        return unsigned.copy(signature = KotlinWire.signRaw(devPriv, unsigned.body()))
    }

    @Test fun ingestDedupAndSummary() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)                          // is_known gate: seed sender identity first
        val m1 = msg(1, mapOf("kind" to "post", "text" to "a", "blobs" to emptyList<String>()))
        assertTrue(s.ingestMessage(m1))
        assertFalse(s.ingestMessage(m1))              // dedup by msg_id
        val sum = s.summary().values.first().values.first()
        assertEquals(1, sum["contiguous"])
        assertEquals(1, s.stats().messages)
    }

    @Test fun rejectsBadSignature() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)                          // known identity, so this fails on SIGNATURE specifically
        val good = msg(1, mapOf("kind" to "post", "text" to "a", "blobs" to emptyList<String>()))
        val forged = good.copy(payload = mapOf("kind" to "post", "text" to "EVIL", "blobs" to emptyList<String>()))
        assertFalse(s.ingestMessage(forged))          // sig no longer matches body
        assertEquals(0, s.stats().messages)
    }

    @Test fun rejectsUnknownIdentity() {
        val s = InMemorySyncStore()
        // no addIdentity call -- sender is not yet known
        val m1 = msg(1, mapOf("kind" to "post", "text" to "a", "blobs" to emptyList<String>()))
        assertFalse(s.ingestMessage(m1))
        assertEquals(0, s.stats().messages)
    }

    @Test fun rejectsSeqReuse() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        val m1 = msg(5, mapOf("kind" to "post", "text" to "a", "blobs" to emptyList<String>()))
        assertTrue(s.ingestMessage(m1))
        // same device, same seq, DIFFERENT payload -> different msg_id, so
        // dedup-by-msg_id would not catch it; SeenSet must reject seq reuse.
        val m2 = msg(5, mapOf("kind" to "post", "text" to "b", "blobs" to emptyList<String>()))
        assertFalse(s.ingestMessage(m2))
        assertEquals(1, s.stats().messages)
    }

    @Test fun nextSeqStartsAtOneAndIncrements() {
        val s = InMemorySyncStore()
        // hearth's DeviceKeys.sign_message: self.seq starts at 0, incremented
        // BEFORE first use -- a device's first-ever message is seq=1.
        assertEquals(1, s.nextSeq())
        assertEquals(2, s.nextSeq())
        assertEquals(3, s.nextSeq())
    }

    @Test fun publishedEncPubRoundTrip() {
        val s = InMemorySyncStore()
        assertEquals(null, s.getPublishedEncPub())     // never published yet
        s.setPublishedEncPub("ab".repeat(32))
        assertEquals("ab".repeat(32), s.getPublishedEncPub())
        s.setPublishedEncPub("cd".repeat(32))           // a later publish overwrites, not appends
        assertEquals("cd".repeat(32), s.getPublishedEncPub())
    }

    @Test fun missingBlobsFromPayload() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        val h = "ab".repeat(32)
        s.ingestMessage(msg(1, mapOf("kind" to "post", "text" to "p", "blobs" to listOf(h))))
        assertEquals(listOf(h), s.missingBlobs())
        val data = byteArrayOf(1, 2, 3)
        assertFalse(s.putBlob("00".repeat(32), data))  // wrong hash rejected
        assertTrue(s.putBlob(sha(data), data))
        // now h is still missing (we stored a different blob), sha(data) present
        assertEquals(listOf(h), s.missingBlobs())
        assertEquals(1, s.stats().blobs)
    }
}
