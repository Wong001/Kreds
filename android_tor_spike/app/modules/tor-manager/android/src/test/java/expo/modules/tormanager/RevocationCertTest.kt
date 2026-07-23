package expo.modules.tormanager

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** JVM tests for RevocationCert (phone-onion-reachability Task 3): the
 *  Kotlin port of hearth.identity.RevocationCert (identity.py:120-149) --
 *  a self-authenticating cert signed by the IDENTITY key -- plus
 *  SyncStore.ingestRevocation (mirrors store.ingest_revocation,
 *  store.py:410-430). Real Ed25519 keypairs throughout (KotlinPairingTest's
 *  genKeypair idiom, KotlinPairingTest.kt:117-125) -- verify() does real
 *  crypto, so hand-rolled "11".repeat(32)-style fixtures would not exercise
 *  it meaningfully. */
class RevocationCertTest {

    private fun genKeypair(): Pair<String, String> {
        val p = Ed25519PrivateKeyParameters(SecureRandom())
        return KotlinWire.toHex(p.encoded) to KotlinWire.toHex(p.generatePublicKey().encoded)
    }

    private fun devPub(privHex: String) = KotlinWire.toHex(
        Ed25519PrivateKeyParameters(KotlinWire.fromHex(privHex), 0).generatePublicKey().encoded)

    /** A REALLY identity-signed RevocationCert -- `identityPriv` signs
     *  `body()`, the same two-step "unsigned then .copy(signature=...)"
     *  idiom KotlinPairingTest's signedCert/EnrollmentCert-analog uses
     *  (KotlinPairingTest.kt:131-136). */
    private fun signedRevocation(
        identityPriv: String, identityPub: String, devicePub: String,
        lastValidSeq: Int, revokedAt: Double = 1752900000.0,
    ): RevocationCert {
        val unsigned = RevocationCert(identityPub, devicePub, lastValidSeq, revokedAt, "")
        return unsigned.copy(signature = KotlinWire.signRaw(identityPriv, unsigned.body()))
    }

    // Builds a SIGNED message for an explicit identity_pub/device, mirroring
    // SyncStoreTest's identityMsg idiom exactly.
    private fun identityMsg(identityPub: String, seq: Int, payload: Map<String, Any?>, devPrivHex: String): SignedMessage {
        val devicePub = devPub(devPrivHex)
        val cert = KotlinWire.CertDict(identityPub, devicePub, "d", 1752900000.0, "00")
        val unsigned = SignedMessage(cert, seq, payload, "")
        return unsigned.copy(signature = KotlinWire.signRaw(devPrivHex, unsigned.body()))
    }

    // =======================================================================
    // RevocationCert.verify() / body() / toDict() / fromDict()
    // =======================================================================

    @Test fun verifyReturnsTrueForValidIdentitySignedCert() {
        val (identityPriv, identityPub) = genKeypair()
        val devicePub = "22".repeat(32)
        val rev = signedRevocation(identityPriv, identityPub, devicePub, lastValidSeq = 7)
        assertTrue(rev.verify())
        assertEquals(7, rev.last_valid_seq)
    }

    @Test fun verifyReturnsFalseWhenFieldTamperedAfterSigning() {
        val (identityPriv, identityPub) = genKeypair()
        val devicePub = "22".repeat(32)
        val good = signedRevocation(identityPriv, identityPub, devicePub, lastValidSeq = 7)
        assertTrue(good.verify())
        // Raising last_valid_seq post-signature (e.g. trying to un-revoke
        // more messages than the identity owner actually authorized) must
        // invalidate the signature -- body() covers every field.
        val tampered = good.copy(last_valid_seq = 999)
        assertFalse(tampered.verify())
    }

    @Test fun verifyReturnsFalseForWrongSigner() {
        // Mirrors hearth's test_forged_revocation_rejected (test_verifier.py):
        // a DIFFERENT identity's key signs a revocation naming itself as
        // identity_pub is fine (self-consistent) but signing with a key that
        // does NOT match the claimed identity_pub must fail verification.
        val (identityPriv, identityPub) = genKeypair()
        val (mallory, _) = genKeypair()
        val devicePub = "22".repeat(32)
        val unsigned = RevocationCert(identityPub, devicePub, 0, 1752900000.0, "")
        val forged = unsigned.copy(signature = KotlinWire.signRaw(mallory, unsigned.body()))
        assertFalse(forged.verify())
    }

    @Test fun toDictFromDictRoundTrips() {
        val (identityPriv, identityPub) = genKeypair()
        val devicePub = "33".repeat(32)
        val rev = signedRevocation(identityPriv, identityPub, devicePub, lastValidSeq = 12, revokedAt = 1752900555.25)
        val restored = RevocationCert.fromDict(rev.toDict())
        assertEquals(rev, restored)
        assertTrue(restored.verify())
    }

    // =======================================================================
    // SyncStore.ingestRevocation -- mirrors store.ingest_revocation
    // (store.py:410-430): is_known gate, verify(), markRevoked (Task 2's
    // retro-drop).
    // =======================================================================

    @Test fun ingestRevocationAcceptsKnownIdentityMarksRevokedAndRetroDrops() {
        val store = InMemorySyncStore()
        val (identityPriv, identityPub) = genKeypair()
        val devicePriv = "44".repeat(32)
        val devicePub = devPub(devicePriv)
        store.addIdentity(identityPub)

        val m1 = identityMsg(identityPub, 1, mapOf("kind" to "profile", "name" to "s1", "created_at" to 1.0), devicePriv)
        val m2 = identityMsg(identityPub, 2, mapOf("kind" to "profile", "name" to "s2", "created_at" to 2.0), devicePriv)
        val m3 = identityMsg(identityPub, 3, mapOf("kind" to "profile", "name" to "s3", "created_at" to 3.0), devicePriv)
        assertTrue(store.ingestMessage(m1))
        assertTrue(store.ingestMessage(m2))
        assertTrue(store.ingestMessage(m3))

        val rev = signedRevocation(identityPriv, identityPub, devicePub, lastValidSeq = 2)
        assertTrue("known identity + valid signature -> accepted", store.ingestRevocation(rev))
        assertTrue("device now revoked", store.isRevokedDevice(devicePub))

        val remaining = store.allMessages().map { it.msgId }.toSet()
        assertTrue("seq<=lastValid kept (seq 1)", m1.msgId() in remaining)
        assertTrue("seq<=lastValid kept (seq 2)", m2.msgId() in remaining)
        assertFalse("seq>lastValid retro-dropped (seq 3) -- cross-check with Task 2's markRevoked", m3.msgId() in remaining)
    }

    @Test fun ingestRevocationRejectsUnknownIdentity() {
        val store = InMemorySyncStore()
        val (identityPriv, identityPub) = genKeypair()   // deliberately NOT addIdentity'd
        val devicePub = "55".repeat(32)
        val rev = signedRevocation(identityPriv, identityPub, devicePub, lastValidSeq = 0)

        assertFalse("unknown identity -> rejected, even with a valid signature", store.ingestRevocation(rev))
        assertFalse("markRevoked must never have been called", store.isRevokedDevice(devicePub))
    }

    @Test fun ingestRevocationRejectsTamperedSignature() {
        val store = InMemorySyncStore()
        val (identityPriv, identityPub) = genKeypair()
        val devicePriv = "66".repeat(32)
        val devicePub = devPub(devicePriv)
        store.addIdentity(identityPub)

        val m1 = identityMsg(identityPub, 1, mapOf("kind" to "profile", "name" to "s1", "created_at" to 1.0), devicePriv)
        assertTrue(store.ingestMessage(m1))

        val good = signedRevocation(identityPriv, identityPub, devicePub, lastValidSeq = 0)
        val tampered = good.copy(last_valid_seq = 999)   // signature no longer matches body()
        assertFalse(tampered.verify())

        assertFalse("bad signature -> rejected", store.ingestRevocation(tampered))
        assertFalse("markRevoked must never have been called", store.isRevokedDevice(devicePub))
        assertTrue("no retro-drop happened either", store.allMessages().any { it.msgId == m1.msgId() })
    }
}
