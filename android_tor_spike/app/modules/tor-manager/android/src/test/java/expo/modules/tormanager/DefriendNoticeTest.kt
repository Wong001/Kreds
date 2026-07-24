package expo.modules.tormanager

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for DefriendNotice (phone-onion-reachability Task 4): the
 *  Kotlin port of hearth.identity.DefriendNotice (identity.py:152-178) --
 *  a notice signed by the AUTHOR (the identity doing the unfriending),
 *  targeting `target_identity` -- plus SyncStore.applyDefriendNotice
 *  (mirrors node.apply_defriend_notice's SUBSET, node.py:1746-1780). Real
 *  Ed25519 keypairs throughout (RevocationCertTest's genKeypair idiom) --
 *  verify() does real crypto, so hand-rolled "11".repeat(32)-style
 *  fixtures would not exercise it meaningfully. */
class DefriendNoticeTest {

    private fun genKeypair(): Pair<String, String> {
        val p = Ed25519PrivateKeyParameters(java.security.SecureRandom())
        return KotlinWire.toHex(p.encoded) to KotlinWire.toHex(p.generatePublicKey().encoded)
    }

    private fun devPub(privHex: String) = KotlinWire.toHex(
        Ed25519PrivateKeyParameters(KotlinWire.fromHex(privHex), 0).generatePublicKey().encoded)

    /** A REALLY author-signed DefriendNotice -- `authorPriv` signs body(),
     *  the same two-step "unsigned then .copy(signature=...)" idiom
     *  RevocationCertTest's signedRevocation uses. */
    private fun signedDefriend(
        authorPriv: String, authorPub: String, targetPub: String,
        createdAt: Double = 1752900000.0,
    ): DefriendNotice {
        val unsigned = DefriendNotice(authorPub, targetPub, createdAt, "")
        return unsigned.copy(signature = KotlinWire.signRaw(authorPriv, unsigned.body()))
    }

    // Builds a SIGNED message for an explicit identity_pub/device, mirroring
    // RevocationCertTest's identityMsg idiom exactly.
    private fun identityMsg(identityPub: String, seq: Int, payload: Map<String, Any?>, devPrivHex: String): SignedMessage {
        val devicePub = devPub(devPrivHex)
        val cert = KotlinWire.CertDict(identityPub, devicePub, "d", 1752900000.0, "00")
        val unsigned = SignedMessage(cert, seq, payload, "")
        return unsigned.copy(signature = KotlinWire.signRaw(devPrivHex, unsigned.body()))
    }

    // =======================================================================
    // DefriendNotice.verify() / body() / toDict() / fromDict()
    // =======================================================================

    @Test fun verifyReturnsTrueForValidAuthorSignedNotice() {
        val (authorPriv, authorPub) = genKeypair()
        val targetPub = "22".repeat(32)
        val notice = signedDefriend(authorPriv, authorPub, targetPub)
        assertTrue(notice.verify())
        assertEquals(targetPub, notice.target_identity)
    }

    @Test fun verifyReturnsFalseWhenFieldTamperedAfterSigning() {
        val (authorPriv, authorPub) = genKeypair()
        val targetPub = "22".repeat(32)
        val good = signedDefriend(authorPriv, authorPub, targetPub)
        assertTrue(good.verify())
        // Retargeting post-signature (e.g. trying to redirect a notice at
        // someone the author never actually named) must invalidate the
        // signature -- body() covers every field.
        val tampered = good.copy(target_identity = "33".repeat(32))
        assertFalse(tampered.verify())
    }

    @Test fun verifyReturnsFalseForWrongSigner() {
        // Mirrors RevocationCertTest's verifyReturnsFalseForWrongSigner: a
        // DIFFERENT identity's key signing a notice that CLAIMS
        // author_identity is fine self-consistently, but a signature that
        // does not match the claimed author_identity must fail.
        val (authorPriv, authorPub) = genKeypair()
        val (mallory, _) = genKeypair()
        val targetPub = "22".repeat(32)
        val unsigned = DefriendNotice(authorPub, targetPub, 1752900000.0, "")
        val forged = unsigned.copy(signature = KotlinWire.signRaw(mallory, unsigned.body()))
        assertFalse(forged.verify())
    }

    @Test fun toDictFromDictRoundTrips() {
        val (authorPriv, authorPub) = genKeypair()
        val targetPub = "33".repeat(32)
        val notice = signedDefriend(authorPriv, authorPub, targetPub, createdAt = 1752900555.25)
        val restored = DefriendNotice.fromDict(notice.toDict())
        assertEquals(notice, restored)
        assertTrue(restored.verify())
    }

    // =======================================================================
    // SyncStore.applyDefriendNotice -- mirrors node.apply_defriend_notice
    // SUBSET (node.py:1746-1780): target==own, author!=own (self-guard),
    // verify(), isKnown(author) -> purgeAuthoredBy + removeIdentity -> true.
    // =======================================================================

    @Test fun appliesValidNoticeTargetingUsFromKnownAuthorRemovesIdentityAndPurgesContent() {
        val store = InMemorySyncStore()
        val ownIdentity = "aa".repeat(32)
        val (authorPriv, authorPub) = genKeypair()
        val authorDevPriv = "44".repeat(32)
        store.addIdentity(authorPub)

        val m1 = identityMsg(authorPub, 1, mapOf("kind" to "profile", "name" to "s1", "created_at" to 1.0), authorDevPriv)
        val m2 = identityMsg(authorPub, 2, mapOf("kind" to "profile", "name" to "s2", "created_at" to 2.0), authorDevPriv)
        assertTrue(store.ingestMessage(m1)); assertTrue(store.ingestMessage(m2))

        val notice = signedDefriend(authorPriv, authorPub, ownIdentity)
        assertTrue("valid notice targeting us from a known author -> applied",
            store.applyDefriendNotice(notice, ownIdentity))

        assertFalse("author no longer known", store.knownIdentities().contains(authorPub))
        val remaining = store.allMessages().map { it.msgId }.toSet()
        assertFalse("author's messages purged (m1)", m1.msgId() in remaining)
        assertFalse("author's messages purged (m2)", m2.msgId() in remaining)
    }

    @Test fun reDeliveredNoticeIsIdempotentAfterFirstApply() {
        val store = InMemorySyncStore()
        val ownIdentity = "aa".repeat(32)
        val (authorPriv, authorPub) = genKeypair()
        store.addIdentity(authorPub)

        val notice = signedDefriend(authorPriv, authorPub, ownIdentity)
        assertTrue("first delivery applies", store.applyDefriendNotice(notice, ownIdentity))
        assertFalse("re-delivered notice -- author no longer known -> false",
            store.applyDefriendNotice(notice, ownIdentity))
    }

    @Test fun noticeNotTargetingUsIsRejectedAndDoesNotRemove() {
        val store = InMemorySyncStore()
        val ownIdentity = "aa".repeat(32)
        val (authorPriv, authorPub) = genKeypair()
        val someoneElse = "bb".repeat(32)
        store.addIdentity(authorPub)

        val notice = signedDefriend(authorPriv, authorPub, someoneElse)   // targets someone else, not us
        assertFalse("notice does not target us -> rejected", store.applyDefriendNotice(notice, ownIdentity))
        assertTrue("author still known -- nothing removed", store.knownIdentities().contains(authorPub))
    }

    @Test fun selfAuthoredNoticeIsRejectedByGuard() {
        // A notice that targets AND claims to be authored by us must never
        // purge our own identity's content -- node.py:1754-1761's
        // belt-and-braces self-author guard.
        val store = InMemorySyncStore()
        val (ownPriv, ownIdentity) = genKeypair()
        store.addIdentity(ownIdentity)

        val notice = signedDefriend(ownPriv, ownIdentity, ownIdentity)   // author == target == own
        assertFalse("self-authored notice -> rejected by guard", store.applyDefriendNotice(notice, ownIdentity))
        assertTrue("own identity still known -- guard fired before any removal",
            store.knownIdentities().contains(ownIdentity))
    }

    @Test fun badSignatureIsRejectedAndDoesNotRemove() {
        val store = InMemorySyncStore()
        val ownIdentity = "aa".repeat(32)
        val (authorPriv, authorPub) = genKeypair()
        store.addIdentity(authorPub)

        val good = signedDefriend(authorPriv, authorPub, ownIdentity)
        val tampered = good.copy(created_at = good.created_at + 1.0)   // signature no longer matches body()
        assertFalse(tampered.verify())
        assertFalse("bad signature -> rejected", store.applyDefriendNotice(tampered, ownIdentity))
        assertTrue("author still known -- nothing removed", store.knownIdentities().contains(authorPub))
    }

    @Test fun unknownAuthorIsRejectedEvenWithValidSignatureAndTarget() {
        val store = InMemorySyncStore()
        val ownIdentity = "aa".repeat(32)
        val (authorPriv, authorPub) = genKeypair()   // deliberately NOT addIdentity'd

        val notice = signedDefriend(authorPriv, authorPub, ownIdentity)
        assertFalse("unknown author -> rejected, even with a valid signature and target",
            store.applyDefriendNotice(notice, ownIdentity))
    }

    // =======================================================================
    // friend-peering Task 5: applyDefriendNotice ALSO drops the defriended
    // author's peer-table row(s) (mirrors hearth remove_peer_identity,
    // node.py:1770) so SyncRunner's peer-loop (Task 4) stops dialing them.
    // =======================================================================

    @Test fun appliesValidNoticeAlsoRemovesAllPeerRowsForAuthor() {
        val store = InMemorySyncStore()
        val ownIdentity = "aa".repeat(32)
        val (authorPriv, authorPub) = genKeypair()
        store.addIdentity(authorPub)
        // Two peer rows for the same identity -- multiple known device
        // addresses for the ex-friend -- BOTH must go, not just the first.
        store.addPeer("onion1.onion:9050", authorPub)
        store.addPeer("onion2.onion:9050", authorPub)
        // An unrelated peer row must survive untouched.
        store.addPeer("unrelated.onion:9050", "cc".repeat(32))

        val notice = signedDefriend(authorPriv, authorPub, ownIdentity)
        assertTrue("valid notice targeting us from a known author -> applied",
            store.applyDefriendNotice(notice, ownIdentity))

        val remainingPeers = store.listPeers()
        assertTrue("author's peer rows all removed",
            remainingPeers.none { it.identityPub == authorPub })
        assertTrue("unrelated peer row untouched",
            remainingPeers.any { it.address == "unrelated.onion:9050" })
        // Existing defriend effects (arc 2) must still hold alongside the
        // new peer-removal effect.
        assertFalse("author no longer known", store.knownIdentities().contains(authorPub))
    }

    @Test fun defriendOfAuthorWithNoPeerRowIsNoopNotAnError() {
        val store = InMemorySyncStore()
        val ownIdentity = "aa".repeat(32)
        val (authorPriv, authorPub) = genKeypair()
        store.addIdentity(authorPub)
        // Deliberately no addPeer call for authorPub.

        val notice = signedDefriend(authorPriv, authorPub, ownIdentity)
        assertTrue("valid notice still applies even with no peer row to remove",
            store.applyDefriendNotice(notice, ownIdentity))
        assertFalse("author no longer known", store.knownIdentities().contains(authorPub))
    }

    @Test fun noticeFailingIsKnownGateDoesNotRemovePeerRow() {
        // Falsifiable against a reordering bug: seed a peer row for an
        // author who is deliberately NOT addIdentity'd, so the notice is
        // validly signed and correctly targeted but fails gate 4
        // (isKnown(author)). If peer removal were hoisted outside the
        // gated block, this row would vanish despite the gate failing.
        val store = InMemorySyncStore()
        val ownIdentity = "aa".repeat(32)
        val (authorPriv, authorPub) = genKeypair()   // deliberately NOT addIdentity'd
        store.addPeer("onion1.onion:9050", authorPub)

        val notice = signedDefriend(authorPriv, authorPub, ownIdentity)
        assertFalse("unknown author -> rejected", store.applyDefriendNotice(notice, ownIdentity))
        assertTrue("peer row untouched -- gate failed before any removal",
            store.listPeers().any { it.address == "onion1.onion:9050" && it.identityPub == authorPub })
    }
}
