package expo.modules.tormanager

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** JVM tests for DecryptPass (B.2 Task 5): decrypts own POST/DM messages via
 *  either an inline `wraps[phoneDevicePub]` entry or a backfilled
 *  wrap_grant, using REAL hearth-parity crypto material from the committed
 *  dmcrypt_vectors.json fixture (Task 1, extended for this task with two
 *  more post cases sharing case 0's enc keypair -- see
 *  make_dmcrypt_vectors.py) -- not hand-rolled ciphertext, so a passing
 *  test proves the AAD/unwrap/decrypt path end-to-end, the same way
 *  KotlinDmcryptTest does for the primitives alone. */
class DecryptPassTest {
    // Arbitrary hex64 "device" pubkeys for these tests -- ingestMessage/
    // verifyDeviceSignature never validate that a device_pub is a REAL
    // derived Ed25519 point (see SyncStoreTest.kt's own msg() helper and
    // devicePair() below); phoneDevicePub is just the map key DecryptPass
    // looks up in payload.wraps / a grant's wraps, exactly like production.
    private val phoneDevicePub = "44".repeat(32)
    private val otherDevicePub = "55".repeat(32)
    // A DIFFERENT (but known -- i.e. a synced "friend") identity, used by
    // the two author-scoping tests below. Distinct from phoneDevicePub/
    // otherDevicePub, which are DEVICE keys, not identities.
    private val foreignIdentityPub = "99".repeat(32)
    // The phone's OWN identity for the own-content-only regression test
    // below -- deliberately distinct from the fixture's "11"-repeat author
    // (make_dmcrypt_vectors.py), which plays the role of a FRIEND there.
    private val phoneOwnIdentityPub = "77".repeat(32)

    private fun cases(): JSONArray {
        val t = javaClass.classLoader!!.getResourceAsStream("dmcrypt_vectors.json")!!
            .readBytes().toString(Charsets.UTF_8)
        return JSONObject(t).getJSONArray("cases")
    }

    // org.json -> plain Kotlin bridge, same idiom as KotlinDmcryptTest/
    // SignedMessageTest/KotlinSyncTest's own private copies.
    private fun jsonToMap(o: JSONObject): Map<String, Any?> =
        o.keys().asSequence().associateWith { unwrap(o.get(it)) }
    private fun unwrap(v: Any?): Any? = when (v) {
        is JSONObject -> jsonToMap(v)
        is JSONArray -> (0 until v.length()).map { unwrap(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }

    // A fresh Ed25519 DEVICE keypair per constructed message. identity_pub
    // is passed straight through as-is (the fixture's "author"/"to" hex
    // strings) -- it is just signed DATA in the canonical message body,
    // never itself verified as a real derived key (only cert.device_pub is,
    // via verifyDeviceSignature). Mirrors SyncStoreTest.kt's msg() helper.
    private fun devicePair(devPrivHex: String): Pair<String, String> =
        devPrivHex to KotlinWire.toHex(
            Ed25519PrivateKeyParameters(KotlinWire.fromHex(devPrivHex), 0).generatePublicKey().encoded)

    private fun signedMessage(identityPub: String, seq: Int, payload: Map<String, Any?>, devPrivHex: String): SignedMessage {
        val (devPriv, devPub) = devicePair(devPrivHex)
        val cert = KotlinWire.CertDict(identityPub, devPub, "d", 1752900000.0, "00")
        val unsigned = SignedMessage(cert, seq, payload, "")
        return unsigned.copy(signature = KotlinWire.signRaw(devPriv, unsigned.body()))
    }

    private fun postPayload(c: JSONObject, wraps: Map<String, Any?>): Map<String, Any?> = mapOf(
        "kind" to "post", "scope" to c.getString("scope"),
        "created_at" to c.getDouble("created_at"),
        "body_nonce" to c.getString("body_nonce"), "body_ct" to c.getString("body_ct"),
        "wraps" to wraps, "blobs" to emptyList<String>(),
    )

    private fun dmPayload(c: JSONObject, wraps: Map<String, Any?>): Map<String, Any?> = mapOf(
        "kind" to "dm", "to" to c.getString("to"),
        "created_at" to c.getDouble("created_at"),
        "body_nonce" to c.getString("body_nonce"), "body_ct" to c.getString("body_ct"),
        "wraps" to wraps, "blobs" to emptyList<String>(),
    )

    @Test fun decryptsPostViaInlineWrap() {
        val c = cases().getJSONObject(0)
        assertEquals("post", c.getString("kind"))
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val msg = signedMessage(c.getString("author"), 1, postPayload(c, wraps), "a1".repeat(32))
        assertTrue(store.ingestMessage(msg))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), c.getString("author")).feed
        assertEquals(1, out.size)
        assertEquals(msg.msgId(), out[0].msgId)
        assertEquals("post", out[0].kind)
        assertEquals(c.getJSONObject("plaintext").getString("text"), out[0].text)
        assertEquals(c.getDouble("created_at"), out[0].createdAt, 0.0)
    }

    @Test fun decryptsDmViaBackfilledWrapGrantNotInlineWraps() {
        val c = cases().getJSONObject(1)
        assertEquals("dm", c.getString("kind"))
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        // NOT wrapped to our device inline -- only some OTHER device's wrap
        // rides in the payload, so the backfill grant is the ONLY path to
        // the content key.
        val inlineWraps = mapOf(otherDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val msg = signedMessage(c.getString("author"), 1, dmPayload(c, inlineWraps), "b1".repeat(32))
        assertTrue(store.ingestMessage(msg))

        val grantPayload = mapOf(
            "kind" to "wrap_grant", "target" to msg.msgId(),
            "created_at" to c.getDouble("created_at"),
            "wraps" to mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap"))),
        )
        val grant = signedMessage(c.getString("author"), 2, grantPayload, "b1".repeat(32))
        assertTrue(store.ingestMessage(grant))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), c.getString("author")).feed
        assertEquals(1, out.size)
        assertEquals("dm", out[0].kind)
        assertEquals(c.getJSONObject("plaintext").getString("text"), out[0].text)
    }

    @Test fun skipsWrongDeviceAndTamperedCiphertextWithoutCrashing() {
        val c = cases().getJSONObject(0)
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))

        // (a) wrapped only to a DIFFERENT device, no grant at all -- our
        // device has no way in.
        val wrongDeviceWraps = mapOf(otherDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val m1 = signedMessage(c.getString("author"), 1, postPayload(c, wrongDeviceWraps), "c1".repeat(32))
        assertTrue(store.ingestMessage(m1))

        // (b) wrapped to OUR device, but body_ct is tampered -- decryptBody
        // must fail AEAD auth; DecryptPass must skip it, not throw.
        val goodCt = c.getString("body_ct")
        val tamperedCt = goodCt.dropLast(1) + (if (goodCt.last() == '0') '1' else '0')
        val tamperedPayload = postPayload(c, mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap"))))
            .toMutableMap().apply { put("body_ct", tamperedCt) }
        val m2 = signedMessage(c.getString("author"), 2, tamperedPayload, "c2".repeat(32))
        assertTrue(store.ingestMessage(m2))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), c.getString("author")).feed
        assertEquals("neither message should decrypt (wrong device / tampered ct)", 0, out.size)
    }

    @Test fun ordersNewestFirstByCreatedAt() {
        val cs = cases()
        val postCases = (0 until cs.length()).map { cs.getJSONObject(it) }.filter { it.getString("kind") == "post" }
        val encPriv = postCases[0].getString("enc_priv")
        // Only the cases sharing ONE recipient enc keypair can all be
        // decrypted by a single DecryptPass.run call (a real phone has one
        // enc key) -- the fixture's ordering-specific cases (2-3) share
        // case 0's enc_priv by construction; see make_dmcrypt_vectors.py.
        val sameKeyCases = postCases.filter { it.getString("enc_priv") == encPriv }
        assertTrue("need >=3 post cases sharing one enc key for a meaningful ordering assertion",
            sameKeyCases.size >= 3)

        val store = InMemorySyncStore()
        store.addIdentity(sameKeyCases[0].getString("author"))
        for ((i, c) in sameKeyCases.withIndex()) {
            val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
            val msg = signedMessage(c.getString("author"), i + 1, postPayload(c, wraps), ("d%d".format(i + 1)).repeat(32))
            assertTrue(store.ingestMessage(msg))
        }

        val out = DecryptPass.run(store, phoneDevicePub, encPriv, sameKeyCases[0].getString("author")).feed
        assertEquals(sameKeyCases.size, out.size)
        val expectedOrder = sameKeyCases.map { it.getDouble("created_at") }.sortedDescending()
        assertEquals(expectedOrder, out.map { it.createdAt })
    }

    @Test fun ignoresForeignAuthoredGrantAndPrefersOlderOwnAuthoredGrant() {
        // Reviewer-caught (2026-07-19): B.1's sync admits more than
        // literally-own-authored messages into this store -- a known
        // friend's wrap_grant naming OUR device as a target for OUR OWN
        // post can be synced in too (KotlinSync's HAVE phase adds every
        // identity the node reports as `known`; the node's messages_not_in
        // then serves anything an entitled peer is owed). Unfiltered,
        // DecryptPass's newest-wins fold over wrap_grant would prefer a
        // hostile, NEWER, foreign-authored grant over the real (older)
        // own-authored one -- unwrapKey "succeeds" with the wrong key,
        // decryptBody then fails AEAD auth, and the real post silently
        // vanishes (a fail-closed but real denial-of-render).
        // wrapGrantsFor(msgId, acceptedSigners) -- accepted = {author} for a
        // post -- must reject the foreign grant regardless of its timestamp.
        val c = cases().getJSONObject(0)
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        store.addIdentity(foreignIdentityPub)

        // Not wrapped inline -- both the real content key and the "attack"
        // must come from a wrap_grant.
        val noInlineWraps = mapOf(otherDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val msg = signedMessage(c.getString("author"), 1, postPayload(c, noInlineWraps), "a2".repeat(32))
        assertTrue(store.ingestMessage(msg))

        // The legitimate own-authored grant: OLDER created_at, the REAL wrap.
        val ownGrantPayload = mapOf(
            "kind" to "wrap_grant", "target" to msg.msgId(),
            "created_at" to c.getDouble("created_at") - 10.0,
            "wraps" to mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap"))),
        )
        val ownGrant = signedMessage(c.getString("author"), 2, ownGrantPayload, "a3".repeat(32))
        assertTrue(store.ingestMessage(ownGrant))

        // The hostile grant: signed by a DIFFERENT (but known/"friend")
        // identity, NEWER created_at, a bogus (syntactically valid, semantically
        // wrong) wrap that would fail to decrypt if it were ever tried.
        val bogusWrap = mapOf("eph_pub" to "ab".repeat(32), "nonce" to "cd".repeat(12), "wrapped_key" to "ef".repeat(48))
        val foreignGrantPayload = mapOf(
            "kind" to "wrap_grant", "target" to msg.msgId(),
            "created_at" to c.getDouble("created_at") + 10.0,
            "wraps" to mapOf(phoneDevicePub to bogusWrap),
        )
        val foreignGrant = signedMessage(foreignIdentityPub, 1, foreignGrantPayload, "e1".repeat(32))
        assertTrue(store.ingestMessage(foreignGrant))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), c.getString("author")).feed
        assertEquals("the foreign grant must be ignored; the real, older own grant must still decrypt",
            1, out.size)
        assertEquals(c.getJSONObject("plaintext").getString("text"), out[0].text)
    }

    @Test fun skipsWhenOnlyAForeignAuthoredGrantExists() {
        val c = cases().getJSONObject(0)
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        store.addIdentity(foreignIdentityPub)

        val noInlineWraps = mapOf(otherDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val msg = signedMessage(c.getString("author"), 1, postPayload(c, noInlineWraps), "a4".repeat(32))
        assertTrue(store.ingestMessage(msg))

        // Only a foreign-authored grant exists for this target -- no own
        // grant, no inline wrap. It carries the REAL wrap (so a naive "any
        // grant targeting this msgId" fold WOULD decrypt it) -- rejection
        // here must come purely from the author mismatch, not from the
        // wrap being corrupt.
        val foreignGrantPayload = mapOf(
            "kind" to "wrap_grant", "target" to msg.msgId(),
            "created_at" to c.getDouble("created_at"),
            "wraps" to mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap"))),
        )
        val foreignGrant = signedMessage(foreignIdentityPub, 1, foreignGrantPayload, "e2".repeat(32))
        assertTrue(store.ingestMessage(foreignGrant))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), c.getString("author")).feed
        assertEquals("only a foreign-authored grant exists -- must be skipped, not decrypted", 0, out.size)
    }

    @Test fun includesFriendAuthoredPostWithAValidInlineWrap() {
        // B.2c supersedes this test's former B.2 behavior: B.2's Global
        // Constraint ("own-authored content ONLY") was that slice's scope
        // fence, deliberately removed by B.2c's entitlement rule (see the
        // brief/spec: "friends' content readable"). A friend's own wall
        // post, inline-wrapped to this device by the friend's OWN signed
        // payload, must now decrypt -- the inline-wrap path never consulted
        // wrapGrantsFor/entitlement to begin with (resolveWrap returns the
        // payload's own `wraps[phoneDevicePub]` immediately), so removing
        // the own-author-only outer filter is sufficient on its own to
        // surface this content.
        val c = cases().getJSONObject(0)
        assertEquals("post", c.getString("kind"))
        val store = InMemorySyncStore()
        // The fixture's "author" plays a KNOWN FRIEND here (not the phone's
        // own identity, which is phoneOwnIdentityPub) -- addIdentity mirrors
        // B.1 sync's HAVE phase admitting a friend's identity as known.
        store.addIdentity(c.getString("author"))
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val msg = signedMessage(c.getString("author"), 1, postPayload(c, wraps), "f1".repeat(32))
        assertTrue(store.ingestMessage(msg))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), phoneOwnIdentityPub).feed
        assertEquals("friend-authored post with a genuinely valid wrap must now decrypt (B.2c)",
            1, out.size)
        assertEquals(c.getJSONObject("plaintext").getString("text"), out[0].text)
    }

    @Test fun decryptsFriendAuthoredPostViaAuthorSignedGrant() {
        // Brief behavior (a): friend-authored post + author-signed grant to
        // the phone -> decrypts. No inline wrap at all here -- the ONLY path
        // to the content key is a backfilled wrap_grant signed by the
        // post's own author (a friend, not our own identity), proving the
        // entitlement rule's post branch (`accepted = setOf(m.identityPub)`)
        // now admits a friend author, not just an own-authored one.
        val c = cases().getJSONObject(0)
        assertEquals("post", c.getString("kind"))
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))

        val noInlineWraps = mapOf(otherDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val msg = signedMessage(c.getString("author"), 1, postPayload(c, noInlineWraps), "a5".repeat(32))
        assertTrue(store.ingestMessage(msg))

        val grantPayload = mapOf(
            "kind" to "wrap_grant", "target" to msg.msgId(),
            "created_at" to c.getDouble("created_at"),
            "wraps" to mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap"))),
        )
        val grant = signedMessage(c.getString("author"), 2, grantPayload, "a5".repeat(32))
        assertTrue(store.ingestMessage(grant))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), phoneOwnIdentityPub).feed
        assertEquals("friend-authored post via an author-signed grant must decrypt", 1, out.size)
        assertEquals(c.getJSONObject("plaintext").getString("text"), out[0].text)
    }

    @Test fun decryptsReceivedFriendDmViaRecipientSignedGrantOnly() {
        // Brief behavior (b): friend-authored DM addressed to OUR identity,
        // with the phone's device key reachable ONLY via a wrap_grant SIGNED
        // BY THE RECIPIENT (our own identity, hearth's
        // maintain_received_dm_grants backfill) -- proves the DM branch's
        // recipient-trust half (`setOf(m.identityPub, ownIdentityPub)` when
        // `to == ownIdentityPub`).
        val c = cases().getJSONObject(1)
        assertEquals("dm", c.getString("kind"))
        val ownIdentityPub = c.getString("to")   // fixture's DM recipient plays "us"
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))   // the friend who sent the DM
        store.addIdentity(ownIdentityPub)           // our own identity, also known to itself

        // Not wrapped inline to the phone -- only some OTHER device's wrap
        // rides in the payload, so the recipient-signed grant is the ONLY
        // path to the content key.
        val noInlineWraps = mapOf(otherDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val msg = signedMessage(c.getString("author"), 1, dmPayload(c, noInlineWraps), "a6".repeat(32))
        assertTrue(store.ingestMessage(msg))

        val grantPayload = mapOf(
            "kind" to "wrap_grant", "target" to msg.msgId(),
            "created_at" to c.getDouble("created_at"),
            "wraps" to mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap"))),
        )
        // Signed by the RECIPIENT (own identity), not the author.
        val grant = signedMessage(ownIdentityPub, 1, grantPayload, "a7".repeat(32))
        assertTrue(store.ingestMessage(grant))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), ownIdentityPub).feed
        assertEquals("received DM must decrypt via a recipient-signed grant alone", 1, out.size)
        assertEquals(c.getJSONObject("plaintext").getString("text"), out[0].text)
    }

    @Test fun skipsReceivedDmGrantSignedByAHostileThirdIdentity() {
        // Brief behavior (c), REQUIRED negative: the same received-DM setup
        // as above, but the "recipient-style" grant is signed by a THIRD
        // identity -- neither the DM's author NOR its recipient (us). This
        // must stay untrusted regardless of how genuinely valid the wrap
        // inside it is (it IS the real fixture wrap) -- trust here must
        // come purely from the signer's identity, not the wrap's validity.
        val c = cases().getJSONObject(1)
        assertEquals("dm", c.getString("kind"))
        val ownIdentityPub = c.getString("to")
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        store.addIdentity(ownIdentityPub)
        store.addIdentity(foreignIdentityPub)   // the hostile third identity, also known

        val noInlineWraps = mapOf(otherDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val msg = signedMessage(c.getString("author"), 1, dmPayload(c, noInlineWraps), "a8".repeat(32))
        assertTrue(store.ingestMessage(msg))

        val grantPayload = mapOf(
            "kind" to "wrap_grant", "target" to msg.msgId(),
            "created_at" to c.getDouble("created_at"),
            "wraps" to mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap"))),
        )
        // Signed by neither the author nor the recipient.
        val hostileGrant = signedMessage(foreignIdentityPub, 1, grantPayload, "a9".repeat(32))
        assertTrue(store.ingestMessage(hostileGrant))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), ownIdentityPub).feed
        assertEquals("a third-identity-signed 'recipient-style' grant must never be trusted",
            0, out.size)
    }

    @Test fun ownPostAndOwnSentDmStillDecryptTogetherNewestFirst() {
        // Brief behavior (d)+(e) combined: the B.2 own-content regression
        // (an own-authored post AND an own-sent DM both still decrypt) PLUS
        // newest-first ordering across posts, now that the own-author-only
        // outer filter is gone -- own content must keep working via the
        // SAME entitlement rule (post: author-only; DM: author-only here
        // too, since we ARE the author and `to` is some other identity, not
        // ownIdentityPub -- so the DM branch's recipient-trust half never
        // even applies to our own outgoing DMs).
        //
        // The DM fixture case (index 1) was generated for a DIFFERENT
        // recipient enc keypair than the post cases (0/2/3) -- a real phone
        // has exactly one enc key, so a single run() call can only decrypt
        // messages wrapped to THAT key. Two run() calls (one per enc_priv)
        // against the SAME store therefore double as their own proof that
        // an undecryptable-with-this-key message (the DM, when using the
        // posts' key, and vice versa) is silently skipped rather than
        // disrupting the other kind's decrypt/ordering.
        val postCase = cases().getJSONObject(0)
        val dmCase = cases().getJSONObject(1)
        val newerPostCase = cases().getJSONObject(3)  // created_at ...100.123456 (newest)
        val olderPostCase = cases().getJSONObject(2)  // created_at ...900.123456 (oldest)
        val ownIdentityPub = postCase.getString("author")
        assertEquals("fixture precondition: dm and post cases share one AUTHOR identity",
            ownIdentityPub, dmCase.getString("author"))
        assertEquals("fixture precondition: cases sharing one enc key for a single run() call",
            postCase.getString("enc_priv"), newerPostCase.getString("enc_priv"))
        assertEquals(postCase.getString("enc_priv"), olderPostCase.getString("enc_priv"))

        val store = InMemorySyncStore()
        store.addIdentity(ownIdentityPub)

        // Own post, inline-wrapped (B.2's original decrypt path).
        val ownPostWraps = mapOf(phoneDevicePub to jsonToMap(postCase.getJSONObject("wrap")))
        val ownPost = signedMessage(ownIdentityPub, 1, postPayload(postCase, ownPostWraps), "b2".repeat(32))
        assertTrue(store.ingestMessage(ownPost))

        // Own-sent DM (we are m.identityPub, i.e. the author), backfilled via
        // an author-signed grant -- B.2's other original decrypt path.
        val noInlineDmWraps = mapOf(otherDevicePub to jsonToMap(dmCase.getJSONObject("wrap")))
        val ownDm = signedMessage(ownIdentityPub, 2, dmPayload(dmCase, noInlineDmWraps), "b3".repeat(32))
        assertTrue(store.ingestMessage(ownDm))
        val dmGrantPayload = mapOf(
            "kind" to "wrap_grant", "target" to ownDm.msgId(),
            "created_at" to dmCase.getDouble("created_at"),
            "wraps" to mapOf(phoneDevicePub to jsonToMap(dmCase.getJSONObject("wrap"))),
        )
        val dmGrant = signedMessage(ownIdentityPub, 3, dmGrantPayload, "b3".repeat(32))
        assertTrue(store.ingestMessage(dmGrant))

        // Two more own posts at different createdAt, for the ordering check.
        val newerWraps = mapOf(phoneDevicePub to jsonToMap(newerPostCase.getJSONObject("wrap")))
        val newerPost = signedMessage(ownIdentityPub, 4, postPayload(newerPostCase, newerWraps), "b4".repeat(32))
        assertTrue(store.ingestMessage(newerPost))
        val olderWraps = mapOf(phoneDevicePub to jsonToMap(olderPostCase.getJSONObject("wrap")))
        val olderPost = signedMessage(ownIdentityPub, 5, postPayload(olderPostCase, olderWraps), "b5".repeat(32))
        assertTrue(store.ingestMessage(olderPost))

        // Posts, via the posts' shared enc key: the DM (wrapped under a
        // DIFFERENT key) must fail AEAD auth and be silently skipped,
        // leaving exactly the 3 posts, newest-first.
        val outPosts = DecryptPass.run(store, phoneDevicePub, postCase.getString("enc_priv"), ownIdentityPub).feed
        assertEquals(3, outPosts.size)
        val expectedPostOrder = listOf(
            newerPostCase.getDouble("created_at"),
            postCase.getDouble("created_at"),
            olderPostCase.getDouble("created_at"),
        )
        assertEquals(expectedPostOrder, outPosts.map { it.createdAt })

        // The own-sent DM, via ITS OWN enc key: the 3 posts (wrapped under a
        // different key) fail AEAD auth and are skipped, leaving exactly
        // the DM.
        val outDm = DecryptPass.run(store, phoneDevicePub, dmCase.getString("enc_priv"), ownIdentityPub).feed
        assertEquals(1, outDm.size)
        assertEquals("dm", outDm[0].kind)
        assertEquals(dmCase.getJSONObject("plaintext").getString("text"), outDm[0].text)
    }

    // -- B.2c Task 3: author-name resolution --
    // KIND_PROFILE payloads are PLAINTEXT (hearth/messages.py:104-115 --
    // make_profile signs {"kind":"profile","name":...,"created_at":...} with
    // no wraps/body_ct at all), so these tests build profile SignedMessages
    // directly with a minimal payload (kind/name/created_at) -- no
    // encryption fixture material needed, unlike the post/dm cases above.

    private fun profilePayload(name: String, createdAt: Double): Map<String, Any?> =
        mapOf("kind" to "profile", "name" to name, "created_at" to createdAt)

    @Test fun resolvesAuthorFromStoredFriendProfile() {
        val c = cases().getJSONObject(0)
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val msg = signedMessage(c.getString("author"), 1, postPayload(c, wraps), "a1".repeat(32))
        assertTrue(store.ingestMessage(msg))
        val profile = signedMessage(
            c.getString("author"), 2, profilePayload("Alice", c.getDouble("created_at")), "a1".repeat(32))
        assertTrue(store.ingestMessage(profile))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), phoneOwnIdentityPub).feed
        assertEquals(1, out.size)
        assertEquals("a friend with a stored profile message resolves to its name",
            "Alice", out[0].author)
    }

    @Test fun fallsBackToIdentityPrefixWithoutAStoredProfile() {
        val c = cases().getJSONObject(0)
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val msg = signedMessage(c.getString("author"), 1, postPayload(c, wraps), "a1".repeat(32))
        assertTrue(store.ingestMessage(msg))
        // No profile message stored for this identity at all.

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), phoneOwnIdentityPub).feed
        assertEquals(1, out.size)
        assertEquals("no stored profile -> falls back to \"friend-\" + identityPub.take(8)",
            "friend-" + c.getString("author").take(8), out[0].author)
    }

    @Test fun fallsBackToIdentityPrefixWhenStoredProfileNameIsBlank() {
        // Hardening (post-review): a stored profile message with an empty
        // (or whitespace-only) name must NOT render as a blank author
        // segment -- it must be treated the same as "no profile stored",
        // falling back to the "friend-" + prefix. Blank here is a strictly
        // WORSE candidate than absent, not a valid empty display name.
        val c = cases().getJSONObject(0)
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val msg = signedMessage(c.getString("author"), 1, postPayload(c, wraps), "a1".repeat(32))
        assertTrue(store.ingestMessage(msg))
        val blankProfile = signedMessage(
            c.getString("author"), 2, profilePayload("   ", c.getDouble("created_at")), "a1".repeat(32))
        assertTrue(store.ingestMessage(blankProfile))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), phoneOwnIdentityPub).feed
        assertEquals(1, out.size)
        assertEquals("a blank stored name must fall back to the \"friend-\" prefix, not render blank",
            "friend-" + c.getString("author").take(8), out[0].author)
    }

    @Test fun resolvesOwnAuthorToOwnStoredProfileName() {
        val c = cases().getJSONObject(0)
        val ownIdentityPub = c.getString("author")   // we authored this post ourselves
        val store = InMemorySyncStore()
        store.addIdentity(ownIdentityPub)
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val msg = signedMessage(ownIdentityPub, 1, postPayload(c, wraps), "a1".repeat(32))
        assertTrue(store.ingestMessage(msg))
        val ownProfile = signedMessage(
            ownIdentityPub, 2, profilePayload("Me Myself", c.getDouble("created_at")), "a1".repeat(32))
        assertTrue(store.ingestMessage(ownProfile))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), ownIdentityPub).feed
        assertEquals(1, out.size)
        assertEquals("own identity with a stored profile resolves to OUR OWN name, not \"me\"",
            "Me Myself", out[0].author)
    }

    @Test fun resolvesOwnAuthorToMeWithoutAStoredOwnProfile() {
        val c = cases().getJSONObject(0)
        val ownIdentityPub = c.getString("author")
        val store = InMemorySyncStore()
        store.addIdentity(ownIdentityPub)
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val msg = signedMessage(ownIdentityPub, 1, postPayload(c, wraps), "a1".repeat(32))
        assertTrue(store.ingestMessage(msg))
        // No profile message stored for our own identity.

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), ownIdentityPub).feed
        assertEquals(1, out.size)
        assertEquals("own identity, no stored profile -> literal \"me\"", "me", out[0].author)
    }

    @Test fun latestProfileMessageWinsForAuthorResolution() {
        val c = cases().getJSONObject(0)
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val msg = signedMessage(c.getString("author"), 1, postPayload(c, wraps), "a1".repeat(32))
        assertTrue(store.ingestMessage(msg))
        // Two profile messages for the same identity, older stored SECOND
        // (seq alone would get this right too, but createdAt is the
        // documented tiebreak -- this asserts on createdAt ordering, not
        // insertion/seq order).
        val newer = signedMessage(
            c.getString("author"), 2, profilePayload("New Name", c.getDouble("created_at")), "a1".repeat(32))
        assertTrue(store.ingestMessage(newer))
        val older = signedMessage(
            c.getString("author"), 3,
            profilePayload("Old Name", c.getDouble("created_at") - 100.0), "a1".repeat(32))
        assertTrue(store.ingestMessage(older))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), phoneOwnIdentityPub).feed
        assertEquals(1, out.size)
        assertEquals("the profile with the latest created_at wins, regardless of seq/insertion order",
            "New Name", out[0].author)
    }

    // -- B.2d Task 4: blob/thumb ref surfacing + per-message content keys --
    // Case 4 in the fixture (make_dmcrypt_vectors.py) is a post whose
    // DECRYPTED BODY carries real blob hash refs -- mirrors hearth's
    // compose_post exactly (encrypt_body(key, {"text": text, "blobs":
    // refs}, aad)) -- while its "thumbs" rides ONLY in the outer
    // (never-encrypted) payload, because hearth never puts thumbs in the
    // body (make_post's thumbs param is a plaintext envelope field
    // alongside poster/codec, same disclosure class as make_dm's
    // story_ref). These tests exercise DecryptPass against that REAL
    // ciphertext, not hand-rolled JSON.

    private fun jsonStringList(a: JSONArray): List<String> =
        (0 until a.length()).map { a.getString(it) }
    private fun jsonNullableStringList(a: JSONArray): List<String?> =
        (0 until a.length()).map { if (a.isNull(it)) null else a.getString(it) }

    @Test fun surfacesBlobsFromBodyAndThumbsFromPayloadAlongsideContentKey() {
        val c = cases().getJSONObject(4)
        assertEquals("post", c.getString("kind"))
        assertEquals("post with photos", c.getJSONObject("plaintext").getString("text"))
        val expectedBlobs = jsonStringList(c.getJSONObject("plaintext").getJSONArray("blobs"))
        assertTrue("fixture precondition: this case's body actually carries blob refs",
            expectedBlobs.isNotEmpty())
        val expectedThumbs = jsonNullableStringList(c.getJSONArray("thumbs"))

        // Reviewer-caught (2026-07-20): a DECOY outer-payload "blobs" list,
        // deliberately DIFFERENT from the body's real blobs -- a real
        // message's outer payload mirrors the body's blobs exactly
        // (validate_payload), so asserting against a mirrored value can't
        // tell "read from body" apart from "fell through to payload". Using
        // a differing decoy makes this test fail if the body/payload
        // branch order for blobs is ever flipped.
        val decoyPayloadBlobs = listOf("ff".repeat(32))
        assertTrue("decoy precondition: must actually differ from the body's real blobs",
            decoyPayloadBlobs != expectedBlobs)

        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        // Outer payload carries the DECOY blobs (never the real ones) plus
        // "thumbs" -- the ONLY place hearth ever puts them.
        val payload = postPayload(c, wraps).toMutableMap().apply {
            put("blobs", decoyPayloadBlobs)
            put("thumbs", expectedThumbs)
        }
        val msg = signedMessage(c.getString("author"), 1, payload, "b6".repeat(32))
        assertTrue(store.ingestMessage(msg))

        val result = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), c.getString("author"))
        assertEquals(1, result.feed.size)
        assertEquals("blobs must be read from the DECRYPTED BODY, not the decoy outer payload",
            expectedBlobs, result.feed[0].blobs)
        assertEquals(
            "thumbs must fall back to the outer payload (hearth never puts them in the body)",
            expectedThumbs, result.feed[0].thumbs)
        assertArrayEquals(
            "Result.keys must expose the real content key for a blob-carrying message",
            KotlinWire.fromHex(c.getString("content_key")), result.keys[msg.msgId()])
    }

    // -- B.2d-2 Task 1: post media/poster surfacing --
    // media/poster are PLAINTEXT OUTER PAYLOAD fields (hearth messages.py's
    // make_post: `"media": media, "poster": poster` -- validate_payload's
    // KIND_POST branch, messages.py:307-317), never inside the encrypted
    // body -- same disclosure class as thumbs (Task 4 above). Built the same
    // way as the thumbs decoy test: start from postPayload(c, wraps) and
    // overlay media/poster via toMutableMap().apply.

    @Test fun surfacesVideoMediaAndPosterFromOuterPayload() {
        val c = cases().getJSONObject(0)
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val posterHash = "ab".repeat(32)
        val payload = postPayload(c, wraps).toMutableMap().apply {
            put("media", "video")
            put("poster", posterHash)
            put("blobs", listOf("cd".repeat(32)))
        }
        val msg = signedMessage(c.getString("author"), 1, payload, "e3".repeat(32))
        assertTrue(store.ingestMessage(msg))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), c.getString("author")).feed
        assertEquals(1, out.size)
        assertEquals("video", out[0].media)
        assertEquals(posterHash, out[0].poster)
    }

    @Test fun defaultsToPhotoMediaWithNullPosterWhenAbsentFromOuterPayload() {
        val c = cases().getJSONObject(0)
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        // postPayload(c, wraps) carries no "media"/"poster" key at all --
        // the ordinary photo-post shape.
        val msg = signedMessage(c.getString("author"), 1, postPayload(c, wraps), "e4".repeat(32))
        assertTrue(store.ingestMessage(msg))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), c.getString("author")).feed
        assertEquals(1, out.size)
        assertEquals("photo", out[0].media)
        assertEquals(null, out[0].poster)
    }

    // -- B.2d-3 Task 3 (gap fix): DM story_ref surfacing --
    // story_ref is a PLAINTEXT OUTER PAYLOAD field, DM-only (hearth
    // messages.py's make_dm: `"story_ref": story_ref` -- `_valid_story_ref`
    // shape-guards {"story_id", "media_hash"}), never inside the encrypted
    // body -- same disclosure class as media/poster above. This was
    // referenced only in a comment before this task (no field on
    // Decrypted); App.tsx's story-reply chip (Task 3) needs media_hash to
    // fetch the thumbnail, so it is added here following the exact same
    // pattern as the media/poster tests above.

    @Test fun surfacesStoryRefMediaHashFromDmOuterPayload() {
        val c = cases().getJSONObject(1)
        assertEquals("dm", c.getString("kind"))
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val mediaHash = "cd".repeat(32)
        val payload = dmPayload(c, wraps).toMutableMap().apply {
            put("story_ref", mapOf("story_id" to "s-" + "11".repeat(8), "media_hash" to mediaHash))
        }
        val msg = signedMessage(c.getString("author"), 1, payload, "f1".repeat(32))
        assertTrue(store.ingestMessage(msg))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), c.getString("author")).feed
        assertEquals(1, out.size)
        assertEquals(mediaHash, out[0].storyRefMediaHash)
    }

    @Test fun nullStoryRefMediaHashWhenAbsentFromOrdinaryDm() {
        val c = cases().getJSONObject(1)
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        // dmPayload(c, wraps) carries no "story_ref" key at all -- the
        // ordinary (non-story-reply) DM shape.
        val msg = signedMessage(c.getString("author"), 1, dmPayload(c, wraps), "f2".repeat(32))
        assertTrue(store.ingestMessage(msg))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), c.getString("author")).feed
        assertEquals(1, out.size)
        assertEquals(null, out[0].storyRefMediaHash)
    }

    @Test fun postNeverSurfacesStoryRefEvenIfPresentInOuterPayload() {
        // Defensive regression, mirroring the media-field-shape-trap
        // caution elsewhere in this slice: story_ref is DM-only by
        // construction (hearth's make_post never sets it), but DecryptPass
        // must not surface it even if a payload somehow carried the key
        // under kind == "post" -- the m.kind == "dm" gate in decryptOne
        // must never be bypassed.
        val c = cases().getJSONObject(0)
        assertEquals("post", c.getString("kind"))
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val payload = postPayload(c, wraps).toMutableMap().apply {
            put("story_ref", mapOf("story_id" to "s1", "media_hash" to "ab".repeat(32)))
        }
        val msg = signedMessage(c.getString("author"), 1, payload, "f3".repeat(32))
        assertTrue(store.ingestMessage(msg))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), c.getString("author")).feed
        assertEquals(1, out.size)
        assertEquals("story_ref is DM-only -- a post payload carrying one must never surface it",
            null, out[0].storyRefMediaHash)
    }

    @Test fun nullStoryRefMediaHashWhenMediaHashIsNotHex64() {
        // Review follow-up (2026-07-20): storyRefMediaHash's shape guard must
        // genuinely mirror hearth's `_valid_story_ref` (messages.py:241-255)
        // for media_hash, not just check "non-empty string" -- media_hash is
        // the literal blob-store lookup key getStoryImage feeds it into, so
        // a wrong-shaped value here should fail closed to null, same as a
        // missing field. This value is the sneaky case: right length (64),
        // valid hex digits, but UPPERCASE -- `_is_hex64` requires lowercase
        // only, and a naive case-insensitive check would wrongly accept it.
        val c = cases().getJSONObject(1)
        assertEquals("dm", c.getString("kind"))
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val notHex64MediaHash = "AB".repeat(32)   // 64 chars, valid hex digits, wrong case
        assertEquals("test precondition: exactly 64 chars", 64, notHex64MediaHash.length)
        val payload = dmPayload(c, wraps).toMutableMap().apply {
            put("story_ref", mapOf("story_id" to "s-" + "11".repeat(8), "media_hash" to notHex64MediaHash))
        }
        val msg = signedMessage(c.getString("author"), 1, payload, "f4".repeat(32))
        assertTrue(store.ingestMessage(msg))

        val out = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), c.getString("author")).feed
        assertEquals(1, out.size)
        assertEquals("a non-hex64 media_hash must fail the shape guard, even at the right length",
            null, out[0].storyRefMediaHash)
    }

    @Test fun blobLessMessageIsAbsentFromResultKeys() {
        // Case 0's body carries "blobs": [] -- a message with genuinely no
        // blobs must still decrypt into the feed (with an empty blobs
        // list), but its content key must NOT appear in Result.keys --
        // nothing downstream would ever use it to decrypt a blob.
        val c = cases().getJSONObject(0)
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val msg = signedMessage(c.getString("author"), 1, postPayload(c, wraps), "b7".repeat(32))
        assertTrue(store.ingestMessage(msg))

        val result = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), c.getString("author"))
        assertEquals(1, result.feed.size)
        assertTrue("a blob-less message's body must yield an empty blobs list",
            result.feed[0].blobs.isEmpty())
        assertFalse("a blob-less message must be absent from Result.keys",
            result.keys.containsKey(msg.msgId()))
    }

    // -- B.2d-4 Task 3: responses decrypt pass (DecryptPass.responsesPass) --
    // Uses the fixture's single real "responses" case (Task 1's committed
    // KIND_RESPONSES vector -- a real hearth-aggregated record: a valid
    // PUBLIC comment plus a PRIVATE reaction, same case KotlinResponsesTest
    // exercises against KotlinResponses directly). These tests exercise the
    // surrounding pass: entitlement (signer must equal the target post's
    // own author), latest-wins selection, and fail-closed misses.

    private fun responsesCase(): JSONObject {
        val cs = cases()
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            if (c.getString("kind") == "responses") return c
        }
        throw AssertionError("no \"responses\" case in fixture")
    }

    private fun responsesPayload(c: JSONObject, wraps: Map<String, Any?>, createdAt: Double = c.getDouble("created_at")): Map<String, Any?> = mapOf(
        "kind" to "responses", "target" to c.getString("target"),
        "created_at" to createdAt,
        "body_nonce" to c.getString("body_nonce"), "body_ct" to c.getString("body_ct"),
        "wraps" to wraps,
    )

    /** responsesPass's entitlement check needs a stored "post" StoredMsg
     *  whose OWN msgId equals a KIND_RESPONSES record's `target` -- but a
     *  real post's msgId is a SHA-256 hash of its own signed envelope
     *  (SignedMessage.msgId()), which cannot be steered to match an
     *  arbitrary pre-baked fixture string (that would mean brute-forcing a
     *  hash preimage). This thin SyncStore delegate injects a synthetic
     *  "post" StoredMsg directly into what allMessages() returns instead --
     *  everything else (wrapGrantsFor/profileNames/deviceViews/etc.) still
     *  goes through a real, unmodified InMemorySyncStore. */
    private class TargetInjectingStore(
        private val backing: InMemorySyncStore, private val extraPosts: List<StoredMsg>,
    ) : SyncStore by backing {
        override fun allMessages(): List<StoredMsg> = backing.allMessages() + extraPosts
    }

    private fun withInjectedPost(store: InMemorySyncStore, target: String, author: String): SyncStore =
        TargetInjectingStore(store, listOf(StoredMsg(target, "post", author, emptyMap())))

    @Test fun decryptsLatestResponsesRecordForItsOwnPostAuthor() {
        val c = responsesCase()
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val msg = signedMessage(c.getString("author"), 1, responsesPayload(c, wraps), "c3".repeat(32))
        assertTrue(store.ingestMessage(msg))
        val injected = withInjectedPost(store, c.getString("target"), c.getString("author"))

        val out = DecryptPass.responsesPass(injected, phoneDevicePub, c.getString("enc_priv"), c.getString("author"))
        val r = out[c.getString("target")]
        assertTrue("a responses entry must be present for the post's target", r != null)
        assertEquals(mapOf("heart" to 1), r!!.reactions)
        assertEquals(1, r.comments.size)
        assertEquals("nice post!", r.comments[0].body)
        // No profile name stored for the responder -> "friend-" + prefix,
        // same fallback DecryptPass.resolveAuthor uses for feed authors.
        assertEquals("friend-" + c.getString("public_responder_identity").take(8), r.comments[0].display)
        assertEquals("a real, verified+device-bound public comment resolves to a name, not an alias",
            null, r.comments[0].aliasColor)
    }

    @Test fun latestByCreatedAtResponsesRecordWinsOverAnOlderOne() {
        val c = responsesCase()
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))

        // The REAL, valid vector record -- ingested FIRST (seq 1) but is
        // the NEWER of the two candidates by created_at. If responsesPass
        // picked "whichever was iterated last" instead of genuinely
        // comparing created_at, it would instead try the bogus-wrapped
        // older record below (ingested last) and fail to decrypt -- so a
        // passing assertion here proves created_at comparison actually
        // happened, not merely that the fixture's one real record works.
        val realWraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val realMsg = signedMessage(c.getString("author"), 1, responsesPayload(c, realWraps), "c4".repeat(32))
        assertTrue(store.ingestMessage(realMsg))

        // An OLDER competing record for the SAME (author, target), with a
        // syntactically-valid but semantically-bogus wrap -- created_at is
        // strictly earlier than the real record's, so it must never even
        // be attempted for decryption.
        val bogusWrap = mapOf(
            "eph_pub" to "ab".repeat(32), "nonce" to "cd".repeat(12), "wrapped_key" to "ef".repeat(48))
        val olderMsg = signedMessage(
            c.getString("author"), 2,
            responsesPayload(c, mapOf(phoneDevicePub to bogusWrap), c.getDouble("created_at") - 100.0),
            "c5".repeat(32))
        assertTrue(store.ingestMessage(olderMsg))

        val injected = withInjectedPost(store, c.getString("target"), c.getString("author"))
        val out = DecryptPass.responsesPass(injected, phoneDevicePub, c.getString("enc_priv"), c.getString("author"))
        val r = out[c.getString("target")]
        assertTrue("the newer (real) record must be selected and decrypt", r != null)
        assertEquals(1, r!!.comments.size)
        assertEquals("nice post!", r.comments[0].body)
    }

    @Test fun ignoresResponsesRecordWhenSignerDiffersFromTheTargetPostsRealAuthor() {
        // Reviewer-motivated addition: hearth's own reference
        // (store.responses_record(target, author_identity)) is ALWAYS
        // scoped to the target post's known author -- responsesPass must
        // mirror that, not merely "whoever signed a wrap_grant for this
        // responses message" (a different, already-covered question --
        // see resolveWrap). This message is FULLY internally crypto-valid
        // (the fixture's real author + real AAD/wrap/ciphertext, identical
        // to the positive test above) -- the ONLY difference is that the
        // injected "post" for this target is authored by someone else, the
        // exact shape of a hostile known-identity claiming a target they
        // do not own.
        val c = responsesCase()
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val msg = signedMessage(c.getString("author"), 1, responsesPayload(c, wraps), "c6".repeat(32))
        assertTrue(store.ingestMessage(msg))
        val injected = withInjectedPost(store, c.getString("target"), foreignIdentityPub)

        val out = DecryptPass.responsesPass(injected, phoneDevicePub, c.getString("enc_priv"), c.getString("author"))
        assertTrue(
            "a responses record signed by anyone other than the target post's real author must be ignored",
            out[c.getString("target")] == null)
    }

    @Test fun ignoresResponsesRecordWhenTargetPostIsNotKnownToThisStore() {
        val c = responsesCase()
        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        val msg = signedMessage(c.getString("author"), 1, responsesPayload(c, wraps), "c7".repeat(32))
        assertTrue(store.ingestMessage(msg))
        // No injected "post" StoredMsg at all -- allMessages() has no post
        // row for this target, so postAuthorByMsgId has nothing to match.

        val out = DecryptPass.responsesPass(store, phoneDevicePub, c.getString("enc_priv"), c.getString("author"))
        assertTrue("no known post for target -> no responses entry, fail-closed", out.isEmpty())
    }

    // -- Finding 1 (final review): own-response read-side (mine/my_reaction)
    // via responsesPass' new ownRawByCreatedAt step -- end-to-end, real
    // crypto throughout (encryptBody/wrapKey/responseAad/responsesAad), NOT
    // via ComposeResponse.compose: that call would put a raw
    // KotlinWire.PyFloat into an InMemorySyncStore's payload["created_at"]
    // (no JSON round-trip happens for this store impl, unlike the real
    // on-device SqliteSyncStore, which always round-trips through
    // MsgJson.serialize/jsonToMap -- see ownRawByCreatedAt's own doc for why
    // that round-trip is what makes `payload["created_at"] as? Number`
    // safe in production), which would make `ownRawByCreatedAt`'s own outer-
    // payload read silently find nothing. Hand-building with a plain Double
    // sidesteps that InMemorySyncStore-only artifact and is, if anything,
    // MORE representative of what responsesPass actually sees on-device.

    private fun genEncKeyPair(): Pair<String, String> {   // (privHex, pubHex)
        val p = X25519PrivateKeyParameters(SecureRandom())
        return KotlinWire.toHex(p.encoded) to KotlinWire.toHex(p.generatePublicKey().encoded)
    }

    /** A real own journal post (kind="post", placement="journal", authored
     *  by `identityPub`) -- ingested so its REAL msgId (a hash of its own
     *  signed envelope) is available as a response target, the same
     *  seedOwnJournalPost idiom ComposeResponseTest.kt uses. */
    private fun journalPost(store: InMemorySyncStore, identityPub: String, devPrivHex: String): String {
        val msg = signedMessage(identityPub, store.nextSeq(),
            mapOf("kind" to "post", "placement" to "journal", "created_at" to 1752900000.0), devPrivHex)
        assertTrue(store.ingestMessage(msg))
        return msg.msgId()
    }

    /** Hand-builds ONE raw kind="response" (singular -- ComposeResponse's
     *  own envelope shape) SignedMessage, wrapped to `phoneDevicePub` (this
     *  class's own test constant for "the reading device") via a real
     *  X25519 wrap -- mirrors ComposeResponse.kt's own self-readable-wrap
     *  construction, minus the author/mutual-box wrapping this test doesn't
     *  need (ownRawByCreatedAt only ever reads the phoneDevicePub wrap).
     *  Deliberately WRAPPED-TO, not SIGNED-BY, `phoneDevicePub` -- the
     *  message's cert.device_pub is whatever `devPrivHex` derives (via
     *  `signedMessage`), decoupled from the wrap-map key, same as every
     *  other wraps-map test in this file (e.g. decryptsPostViaInlineWrap's
     *  `mapOf(phoneDevicePub to ...)` alongside a message signed by a
     *  DIFFERENT device key). `mutualBox` defaults to null (a public=false
     *  entry's box is never opened by step 1, so an unopenable/absent box
     *  is fine here; validEntry still requires it be shape-valid, and null
     *  passes that). */
    private fun rawResponseMessage(
        identityPub: String, devPrivHex: String, phoneEncPub: String,
        target: String, rkind: String, body: String, createdAt: Double, seq: Int,
        mutualBox: Any? = null,
    ): SignedMessage {
        val aad = KotlinDmcrypt.responseAad(identityPub, target, createdAt)
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val (nonceHex, ctHex) = KotlinDmcrypt.encryptBody(key, mapOf(
            "rkind" to rkind, "body" to body,
            "alias_seed" to "ab".repeat(16), "public" to false,
            "responder" to identityPub, "responder_sig" to "00".repeat(64),
            "mutual_box" to mutualBox, "created_at" to createdAt), aad)
        val wraps = KotlinDmcrypt.wrapKey(key, mapOf(phoneDevicePub to phoneEncPub), aad)
        val payload: Map<String, Any?> = mapOf(
            "kind" to "response", "target" to target,
            "body_nonce" to nonceHex, "body_ct" to ctHex, "wraps" to wraps,
            "created_at" to createdAt)
        return signedMessage(identityPub, seq, payload, devPrivHex)
    }

    /** Hand-builds a folded kind="responses" (plural -- the aggregate
     *  record) SignedMessage carrying exactly `entries`, wrapped to
     *  `phoneDevicePub` (see rawResponseMessage's doc for the same
     *  wrapped-to/signed-by decoupling) -- mirrors hearth's
     *  `_rebuild_responses_record` entry shape (node.py:2707-2722): a
     *  private (public=false) entry carries no cleartext identity/
     *  device_pub, only rkind/body/created_at/alias_seed/public/
     *  responder_sig/mutual_box. */
    private fun foldedResponsesRecord(
        authorIdentityPub: String, devPrivHex: String, phoneEncPub: String,
        target: String, entries: List<Map<String, Any?>>, createdAt: Double, seq: Int,
    ): SignedMessage {
        val aad = KotlinDmcrypt.responsesAad(authorIdentityPub, target, createdAt)
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val (nonceHex, ctHex) = KotlinDmcrypt.encryptBody(key, mapOf("entries" to entries), aad)
        val wraps = KotlinDmcrypt.wrapKey(key, mapOf(phoneDevicePub to phoneEncPub), aad)
        val payload: Map<String, Any?> = mapOf(
            "kind" to "responses", "target" to target,
            "body_nonce" to nonceHex, "body_ct" to ctHex, "wraps" to wraps,
            "created_at" to createdAt)
        return signedMessage(authorIdentityPub, seq, payload, devPrivHex)
    }

    private fun privateEntryShape(rkind: String, body: String, createdAt: Double): Map<String, Any?> = mapOf(
        "rkind" to rkind, "body" to body, "created_at" to createdAt,
        "alias_seed" to "ab".repeat(16), "public" to false,
        "responder_sig" to "00".repeat(64), "mutual_box" to null)

    @Test fun ownRawReactionResolvesMyReactionAndMineTrue() {
        val store = InMemorySyncStore()
        store.addIdentity(phoneOwnIdentityPub)
        val (encPriv, encPub) = genEncKeyPair()
        val target = journalPost(store, phoneOwnIdentityPub, "e1".repeat(32))

        // The raw response THIS device composed (mirrors ComposeResponse's
        // self-readable wrap).
        val raw = rawResponseMessage(
            phoneOwnIdentityPub, "e1".repeat(32), encPub, target, "reaction", "fire", 1752900100.0, store.nextSeq())
        assertTrue(store.ingestMessage(raw))

        // The folded record the post's own author (same identity, e.g. a
        // desktop device) would eventually republish, carrying the SAME
        // created_at for this responder's reaction -- see
        // _rebuild_responses_record's entry construction.
        val entries = listOf(privateEntryShape("reaction", "fire", 1752900100.0))
        val record = foldedResponsesRecord(
            phoneOwnIdentityPub, "f1".repeat(32), encPub, target, entries, 1752900200.0, store.nextSeq())
        assertTrue(store.ingestMessage(record))

        val out = DecryptPass.responsesPass(store, phoneDevicePub, encPriv, phoneOwnIdentityPub)
        val r = out[target]
        assertTrue("a responses entry must be present", r != null)
        assertEquals(mapOf("fire" to 1), r!!.reactions)
        assertEquals("own reaction must resolve via step 1 -> my_reaction", "fire", r.myReaction)
    }

    @Test fun ownRawCommentResolvesMineNonAliasDisplayAndCreatedAt() {
        val store = InMemorySyncStore()
        store.addIdentity(phoneOwnIdentityPub)
        val (encPriv, encPub) = genEncKeyPair()
        val target = journalPost(store, phoneOwnIdentityPub, "e2".repeat(32))

        val raw = rawResponseMessage(
            phoneOwnIdentityPub, "e2".repeat(32), encPub, target, "comment", "my own comment", 1752900300.0, store.nextSeq())
        assertTrue(store.ingestMessage(raw))

        val entries = listOf(privateEntryShape("comment", "my own comment", 1752900300.0))
        val record = foldedResponsesRecord(
            phoneOwnIdentityPub, "f2".repeat(32), encPub, target, entries, 1752900400.0, store.nextSeq())
        assertTrue(store.ingestMessage(record))

        val out = DecryptPass.responsesPass(store, phoneDevicePub, encPriv, phoneOwnIdentityPub)
        val r = out[target]!!
        assertEquals(1, r.comments.size)
        val c = r.comments[0]
        assertTrue("own comment must resolve mine=true via step 1", c.mine)
        assertFalse("a resolved (step-1-matched) comment must never render as an alias", c.alias)
        assertEquals("my own comment", c.body)
        // The exact created_at app.js's retract POST needs (web/app.js:649,
        // `created_at: c.created_at`) -- unaffected by this fix, but proven
        // here alongside mine/alias since retract only works if BOTH hold.
        assertEquals(1752900300.0, c.createdAt, 0.0)
        // No profile name stored -> hearth's own bare identity[:8] fallback
        // (node.py:1577), same as every other resolved comment -- Finding 1
        // does not special-case "you" for the API-facing `name` field (see
        // KotlinResponses' own doc: the "you" override only ever touches
        // `display`, a separate native-app-only field LocalApi does not
        // serialize).
        assertEquals(phoneOwnIdentityPub.take(8), c.name)
        assertEquals(phoneOwnIdentityPub, c.responder)
    }

    @Test fun ownRawClearComposeDoesNotClearMyReactionBeforeTheNextFold() {
        // Finding 1's documented boundary: hearth's own reference
        // (_post_responses_view) only ever reflects a FOLDED record's
        // entries -- a "clear" reaction never becomes an entry (it REMOVES
        // one during the fold, node.py:2698-2699), so composing "clear"
        // locally does nothing to my_reaction until the post's author
        // re-folds and republishes a record that omits the entry. This
        // proves that fold-dependency is preserved: the folded record here
        // is STALE (still carries the pre-clear "fire" entry -- exactly
        // what a real record looks like before the next fold sweep), even
        // though this device's own store ALSO already holds a later
        // "reaction: clear" raw response for the same target.
        val store = InMemorySyncStore()
        store.addIdentity(phoneOwnIdentityPub)
        val (encPriv, encPub) = genEncKeyPair()
        val target = journalPost(store, phoneOwnIdentityPub, "e3".repeat(32))

        val originalReaction = rawResponseMessage(
            phoneOwnIdentityPub, "e3".repeat(32), encPub, target, "reaction", "fire", 1752900500.0, store.nextSeq())
        assertTrue(store.ingestMessage(originalReaction))
        // A later "clear" compose -- ingested locally (mirrors
        // ComposeResponse.compose's own immediate local ingest), but the
        // folded record below has NOT caught up with it yet.
        val clearReaction = rawResponseMessage(
            phoneOwnIdentityPub, "e3".repeat(32), encPub, target, "reaction", "clear", 1752900600.0, store.nextSeq())
        assertTrue(store.ingestMessage(clearReaction))

        val staleEntries = listOf(privateEntryShape("reaction", "fire", 1752900500.0))
        val staleRecord = foldedResponsesRecord(
            phoneOwnIdentityPub, "f3".repeat(32), encPub, target, staleEntries, 1752900550.0, store.nextSeq())
        assertTrue(store.ingestMessage(staleRecord))

        val out = DecryptPass.responsesPass(store, phoneDevicePub, encPriv, phoneOwnIdentityPub)
        assertEquals(
            "my_reaction must stay stuck at the pre-clear value until the author's NEXT fold omits the entry -- " +
                "there is no raw-path bypass for a cleared reaction, matching hearth's own fold-only semantics",
            "fire", out[target]!!.myReaction)
    }

    @Test fun myReactionClearsOnceANewerFoldOmitsTheEntry() {
        // The other half of the fold-dependency proof above: once a NEWER
        // (by created_at) folded record arrives that simply has no reaction
        // entry for this responder at all -- exactly what the author's next
        // process_responses sweep would publish after honoring the "clear"
        // -- my_reaction correctly goes back to null.
        val store = InMemorySyncStore()
        store.addIdentity(phoneOwnIdentityPub)
        val (encPriv, encPub) = genEncKeyPair()
        val target = journalPost(store, phoneOwnIdentityPub, "e4".repeat(32))

        val originalReaction = rawResponseMessage(
            phoneOwnIdentityPub, "e4".repeat(32), encPub, target, "reaction", "fire", 1752900700.0, store.nextSeq())
        assertTrue(store.ingestMessage(originalReaction))

        // The NEXT fold: newer created_at, entries list omits the (now
        // cleared) reaction entirely.
        val freshRecord = foldedResponsesRecord(
            phoneOwnIdentityPub, "f4".repeat(32), encPub, target, emptyList(), 1752900800.0, store.nextSeq())
        assertTrue(store.ingestMessage(freshRecord))

        val out = DecryptPass.responsesPass(store, phoneDevicePub, encPriv, phoneOwnIdentityPub)
        assertNull("the fresh fold omits the entry -> my_reaction is null again", out[target]!!.myReaction)
        assertTrue(out[target]!!.reactions.isEmpty())
    }

    @Test fun ownRawByCreatedAtNeverCreditsAnotherIdentitysComposedResponse() {
        // Direct regression test of Finding 1's own documented scope limit
        // ("cert.identity_pub == own identity"): a DIFFERENT known
        // identity's raw "response" row, sharing this store (e.g. this
        // device is the post's author and also received a friend's raw
        // response via routing), must NEVER be credited to `mine` just
        // because its created_at happens to match a folded entry -- only
        // THIS identity's own composed rows populate ownRawByCreatedAt.
        val store = InMemorySyncStore()
        store.addIdentity(phoneOwnIdentityPub)
        val friendIdentity = "cd".repeat(32)
        store.addIdentity(friendIdentity)
        val (encPriv, encPub) = genEncKeyPair()
        val target = journalPost(store, phoneOwnIdentityPub, "e5".repeat(32))

        // A friend's raw response, ALSO wrapped to this phone's device (the
        // shape it would carry if this device were the post's author and
        // therefore routed every raw response) -- but authored by
        // friendIdentity, not phoneOwnIdentityPub.
        val friendRaw = rawResponseMessage(
            friendIdentity, "d5".repeat(32), encPub, target, "reaction", "wow", 1752900900.0, store.nextSeq())
        assertTrue(store.ingestMessage(friendRaw))

        val entries = listOf(privateEntryShape("reaction", "wow", 1752900900.0))
        val record = foldedResponsesRecord(
            phoneOwnIdentityPub, "f5".repeat(32), encPub, target, entries, 1752901000.0, store.nextSeq())
        assertTrue(store.ingestMessage(record))

        val out = DecryptPass.responsesPass(store, phoneDevicePub, encPriv, phoneOwnIdentityPub)
        val r = out[target]!!
        assertEquals(mapOf("wow" to 1), r.reactions)
        assertNull(
            "a friend's own composed response must never resolve to MY my_reaction, even though it decrypts " +
                "successfully via the same self/author wrap shape",
            r.myReaction)
    }
}
