package expo.modules.tormanager

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

        val store = InMemorySyncStore()
        store.addIdentity(c.getString("author"))
        val wraps = mapOf(phoneDevicePub to jsonToMap(c.getJSONObject("wrap")))
        // Outer payload mirrors the body's blobs (as validate_payload does
        // for a real message) and additionally carries "thumbs" -- the
        // ONLY place hearth ever puts them.
        val payload = postPayload(c, wraps).toMutableMap().apply {
            put("blobs", expectedBlobs)
            put("thumbs", expectedThumbs)
        }
        val msg = signedMessage(c.getString("author"), 1, payload, "b6".repeat(32))
        assertTrue(store.ingestMessage(msg))

        val result = DecryptPass.run(store, phoneDevicePub, c.getString("enc_priv"), c.getString("author"))
        assertEquals(1, result.feed.size)
        assertEquals("blobs must be read from the DECRYPTED BODY", expectedBlobs, result.feed[0].blobs)
        assertEquals(
            "thumbs must fall back to the outer payload (hearth never puts them in the body)",
            expectedThumbs, result.feed[0].thumbs)
        assertArrayEquals(
            "Result.keys must expose the real content key for a blob-carrying message",
            KotlinWire.fromHex(c.getString("content_key")), result.keys[msg.msgId()])
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
}
