package expo.modules.tormanager

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun getBlobRoundTripsAndMissingHashReturnsNull() {
        val s = InMemorySyncStore()
        val hash = "aa".repeat(32)
        val data = byteArrayOf(9, 8, 7, 6)
        // putBlob's own hash gate means the hash must actually match --
        // reuse the real sha() helper rather than an arbitrary hash, same
        // as missingBlobsFromPayload above.
        val realHash = sha(data)
        assertTrue(s.putBlob(realHash, data))
        assertArrayEquals(data, s.getBlob(realHash))
        assertEquals("a hash never stored returns null, not throw", null, s.getBlob(hash))
    }

    // -- B.2d-3 Task 1: activeStories + missingBlobs story fix --

    private fun storyPayload(
        media: String, createdAt: Double, expiresAt: Double,
        mediaKind: String = "photo", poster: String? = null, caption: String = "cap"
    ): Map<String, Any?> = mapOf(
        "kind" to "story", "media_kind" to mediaKind, "media" to media,
        "poster" to poster, "caption" to caption,
        "created_at" to createdAt, "expires_at" to expiresAt
    )

    @Test fun activeStoriesReturnsOnlyUnexpiredStoryWithFields() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        val now = 1752900000.0
        val fresh = msg(1, storyPayload(
            media = "aa".repeat(32), createdAt = now - 5.0, expiresAt = now + 1000.0,
            mediaKind = "video", poster = "bb".repeat(32), caption = "hello"
        ))
        val expired = msg(2, storyPayload(media = "cc".repeat(32), createdAt = now - 2000.0, expiresAt = now - 10.0))
        val post = msg(3, mapOf("kind" to "post", "text" to "p", "blobs" to emptyList<String>()))
        assertTrue(s.ingestMessage(fresh))
        assertTrue(s.ingestMessage(expired))
        assertTrue(s.ingestMessage(post))

        val active = s.activeStories(now)
        assertEquals(1, active.size)
        val story = active.first()
        assertEquals(idPub, story.author)
        assertEquals("video", story.mediaKind)
        assertEquals("aa".repeat(32), story.media)
        assertEquals("bb".repeat(32), story.poster)
        assertEquals("hello", story.caption)
        assertEquals(now - 5.0, story.createdAt, 0.0)
    }

    @Test fun activeStoriesExcludesExpiresAtExactlyNow() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        val now = 1752900000.0
        // expires_at == now is NOT "unexpired" -- the filter is strictly
        // `> nowSeconds`, mirroring the design doc's `payload.expires_at > now`.
        val boundary = msg(1, storyPayload(media = "aa".repeat(32), createdAt = now - 100.0, expiresAt = now))
        assertTrue(s.ingestMessage(boundary))
        assertTrue(s.activeStories(now).isEmpty())
    }

    @Test fun activeStoriesOrderedNewestFirst() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        val now = 1752900000.0
        val older = msg(1, storyPayload(media = "aa".repeat(32), createdAt = now - 100.0, expiresAt = now + 900.0))
        val newer = msg(2, storyPayload(media = "bb".repeat(32), createdAt = now - 10.0, expiresAt = now + 900.0))
        assertTrue(s.ingestMessage(older))
        assertTrue(s.ingestMessage(newer))
        assertEquals(listOf("bb".repeat(32), "aa".repeat(32)), s.activeStories(now).map { it.media })
    }

    @Test fun missingBlobsIncludesStoryMediaAndPosterNotPostMediaDiscriminator() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        val storyMedia = "aa".repeat(32)
        val storyPoster = "bb".repeat(32)
        val postBlob = "cc".repeat(32)
        val story = msg(1, storyPayload(media = storyMedia, poster = storyPoster, createdAt = 1.0, expiresAt = 2.0))
        val post = msg(2, mapOf("kind" to "post", "text" to "p", "blobs" to listOf(postBlob)))
        // The field-shape trap: a POST's "media" is the photo/video
        // DISCRIMINATOR, not a hash -- it must never leak into missingBlobs.
        val postWithMediaDiscriminator = msg(3, mapOf(
            "kind" to "post", "text" to "v", "media" to "video", "blobs" to emptyList<String>()
        ))
        assertTrue(s.ingestMessage(story))
        assertTrue(s.ingestMessage(post))
        assertTrue(s.ingestMessage(postWithMediaDiscriminator))

        val missing = s.missingBlobs()
        assertTrue("story media missing", missing.contains(storyMedia))
        assertTrue("story poster missing", missing.contains(storyPoster))
        assertTrue("regression: post blobs still tracked", missing.contains(postBlob))
        assertFalse("field-shape trap: post's media discriminator must not appear", missing.contains("video"))
    }

    @Test fun missingBlobsIncludesProfileAvatarAndBannerNotNulls() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        val avatar = "a1".repeat(32)
        val banner = "b2".repeat(32)
        val postBlob = "cc".repeat(32)
        // A KIND_PROFILE's avatar/banner are blob-hash references (hearth
        // referenced_blobs scans KIND_PROFILE for exactly these), so the phone
        // must request them or the profile header images render broken.
        val profile = msg(1, mapOf(
            "kind" to "profile", "name" to "Me", "bio" to "b",
            "avatar" to avatar, "banner" to banner, "avatar_shape" to "circle",
            "avatar_size" to "m", "avatar_align" to "left", "banner_pos" to 50, "created_at" to 1.0))
        // A KIND_PROFILE with NO avatar/banner (nulls) must add no bogus refs.
        val bare = msg(2, mapOf(
            "kind" to "profile", "name" to "You", "bio" to "",
            "avatar" to null, "banner" to null, "created_at" to 2.0))
        val post = msg(3, mapOf("kind" to "post", "text" to "p", "blobs" to listOf(postBlob)))
        assertTrue(s.ingestMessage(profile))
        assertTrue(s.ingestMessage(bare))
        assertTrue(s.ingestMessage(post))

        val missing = s.missingBlobs()
        assertTrue("profile avatar missing", missing.contains(avatar))
        assertTrue("profile banner missing", missing.contains(banner))
        assertTrue("regression: post blobs still tracked", missing.contains(postBlob))
        assertEquals("null avatar/banner add no bogus refs", 3, missing.size)
    }

    // -- B.2d-4 Task 2: deviceViews (the device-binding source for
    //    KotlinResponses' responder attribution) --

    @Test fun deviceViewsReturnsDistinctDevicePubsOfIdentityAndEmptyForUnknown() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        // msg() signs every message with device priv 0x22.., so its device_pub
        // is the pub of that key -- the same value deviceViews must surface for idPub.
        val dvPub = KotlinWire.toHex(
            org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(
                KotlinWire.fromHex("22".repeat(32)), 0).generatePublicKey().encoded)
        assertTrue(s.ingestMessage(msg(1, mapOf("kind" to "post", "text" to "a", "blobs" to emptyList<String>()))))
        assertTrue(s.ingestMessage(msg(2, mapOf("kind" to "post", "text" to "b", "blobs" to emptyList<String>()))))
        // Two stored messages, one device -> a single distinct device_pub.
        assertEquals(setOf(dvPub), s.deviceViews(idPub))
        // hearth _device_bound treats EMPTY views as permissive; an identity
        // we hold no messages for returns an empty set (the caller's predicate
        // then permits, matching _device_bound's `if not views: return True`).
        assertTrue(s.deviceViews("ff".repeat(32)).isEmpty())
    }

    // -- vp3 slice 3 Task 1: profileRecord / profileLayout / albums (plaintext) --

    @Test fun profileRecordLatestWinsAndReadsPlaintextFields() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        s.ingestMessage(msg(1, mapOf(
            "kind" to "profile", "name" to "Old", "bio" to "b0", "accent" to "#111111",
            "avatar" to null, "avatar_shape" to "circle", "avatar_size" to "m",
            "avatar_align" to "left", "banner" to null, "banner_pos" to 50, "created_at" to 100.0)))
        s.ingestMessage(msg(2, mapOf(
            "kind" to "profile", "name" to "New", "bio" to "b1", "accent" to "#2743d6",
            "avatar" to "aa".repeat(32), "avatar_shape" to "squircle", "avatar_size" to "l",
            "avatar_align" to "center", "banner" to "bb".repeat(32), "banner_pos" to 30,
            "created_at" to 200.0)))
        val rec = s.profileRecord(idPub)!!
        assertEquals("New", rec["name"])                 // newer created_at wins
        assertEquals("b1", rec["bio"])
        assertEquals("squircle", rec["avatar_shape"])
        assertEquals("bb".repeat(32), rec["banner"])
        assertEquals(30, (rec["banner_pos"] as Number).toInt())
        assertNull("unknown identity -> null (drives hearth's 404)", s.profileRecord("ff".repeat(32)))
    }

    @Test fun profileRecordSameCreatedAtHigherSeqWins() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        s.ingestMessage(msg(1, mapOf("kind" to "profile", "name" to "A", "created_at" to 100.0)))
        s.ingestMessage(msg(2, mapOf("kind" to "profile", "name" to "B", "created_at" to 100.0)))
        assertEquals("B", s.profileRecord(idPub)!!["name"])   // seq tie-break
    }

    @Test fun profileLayoutLatestWinsWithPinsSpansSizesTexts() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        s.ingestMessage(msg(1, mapOf(
            "kind" to "profile_layout",
            "pins" to mapOf("m1" to mapOf("x" to 0, "y" to 0, "w" to 2, "h" to 2)),
            "spans" to mapOf("m2" to mapOf("w" to 1, "h" to 1)),
            "sizes" to mapOf("m3" to "wide"),
            "texts" to mapOf("m4" to mapOf("h" to "center", "size" to "l")),
            "order" to emptyList<String>(), "grids" to emptyMap<String, Any?>(),
            "created_at" to 100.0)))
        val layout = s.profileLayout(idPub)
        assertEquals(2, (layout.pins["m1"]?.get("w") as Number).toInt())
        assertEquals(1, (layout.spans["m2"]?.get("h") as Number).toInt())
        assertEquals("wide", layout.sizes["m3"])
        assertEquals("center", layout.texts["m4"]?.get("h"))
        val empty = s.profileLayout("ff".repeat(32))     // never null; empty maps
        assertTrue(empty.pins.isEmpty() && empty.spans.isEmpty() &&
            empty.sizes.isEmpty() && empty.texts.isEmpty())
    }

    @Test fun albumsLatestWinsPerAlbumId() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        s.ingestMessage(msg(1, mapOf("kind" to "album", "album_id" to "A",
            "members" to listOf("m1", "m2"), "created_at" to 100.0)))
        s.ingestMessage(msg(2, mapOf("kind" to "album", "album_id" to "A",
            "members" to listOf("m1", "m2", "m3"), "created_at" to 200.0)))   // newer A
        s.ingestMessage(msg(3, mapOf("kind" to "album", "album_id" to "B",
            "members" to listOf("m9"), "created_at" to 150.0)))
        val albums = s.albums(idPub)
        assertEquals(listOf("m1", "m2", "m3"), albums["A"])   // newest A wins, per-album
        assertEquals(listOf("m9"), albums["B"])               // B unaffected by A's re-publish
        assertTrue(s.albums("ff".repeat(32)).isEmpty())
    }

    // -- outbound Task 1: enckeys (recipient device resolution) --

    // -- outbound Task 2: pending-outbound push queue --

    private fun postPayload(text: String, createdAt: Double, bodyCt: String): Map<String, Any?> = mapOf(
        "kind" to "post", "scope" to "kreds", "body_nonce" to "ab".repeat(12),
        "body_ct" to bodyCt, "wraps" to emptyMap<String, Any?>(),
        "blobs" to emptyList<String>(), "created_at" to createdAt, "expires_at" to null,
        "placement" to "journal", "media" to "photo", "poster" to null,
        "codec" to null, "thumbs" to null)

    @Test fun pendingOutboundQueueAddPushAndClear() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        // DISTINCT body_ct per fixture (review fix, Minor) -- the two
        // messages must be tell-apart-able by content, not just by count,
        // so the assertions below actually distinguish m1 from m2 rather
        // than merely confirming "two messages of some kind are queued".
        val bodyCt1 = "bb".repeat(20)
        val bodyCt2 = "dd".repeat(20)
        val m1 = msg(1, postPayload("first", 100.0, bodyCt1))
        val m2 = msg(2, postPayload("second", 200.0, bodyCt2))
        assertTrue(s.ingestMessage(m1))
        assertTrue(s.ingestMessage(m2))
        val id1 = m1.msgId(); val id2 = m2.msgId()

        assertTrue("nothing queued yet", s.pendingOutbound().isEmpty())

        s.addPendingOutbound(id1, m1.toDict())
        s.addPendingOutbound(id2, m2.toDict())
        s.addPendingOutbound(id1, m1.toDict())          // idempotent -- must not duplicate
        val pending = s.pendingOutbound()
        assertEquals(2, pending.size)
        @Suppress("UNCHECKED_CAST")
        val bodyCts = pending.map { (it["payload"] as Map<String, Any?>)["body_ct"] }
        // Stable insertion order (m1 then m2), each fixture's DISTINCT
        // body_ct -- proves pendingOutbound() actually distinguishes the
        // two queued messages by content, not just a matching count.
        assertEquals(listOf(bodyCt1, bodyCt2), bodyCts)

        s.clearPendingOutbound(listOf(id1))
        val remaining = s.pendingOutbound()
        assertEquals(1, remaining.size)
        @Suppress("UNCHECKED_CAST")
        val remainingCert = remaining.first()["cert"] as Map<String, Any?>
        assertEquals(m2.cert.identity_pub, remainingCert["identity_pub"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("the SPECIFIC surviving message (m2, not m1) by its distinct body_ct",
            bodyCt2, (remaining.first()["payload"] as Map<String, Any?>)["body_ct"])
    }

    @Test fun enckeysLatestWinsPerDeviceOverEnckeyMessages() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        // msg() signs with device priv 0x22.. -> a single device_pub for idPub.
        // Two enckey messages from that device: newer created_at wins.
        assertTrue(s.ingestMessage(msg(1, mapOf(
            "kind" to "enckey", "enc_pub" to "aa".repeat(32), "created_at" to 100.0))))
        assertTrue(s.ingestMessage(msg(2, mapOf(
            "kind" to "enckey", "enc_pub" to "bb".repeat(32), "created_at" to 200.0))))
        val dvPub = KotlinWire.toHex(
            org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(
                KotlinWire.fromHex("22".repeat(32)), 0).generatePublicKey().encoded)
        val ks = s.enckeys(idPub)
        assertEquals(mapOf(dvPub to "bb".repeat(32)), ks)                 // latest enc_pub
        assertTrue("unknown identity -> empty", s.enckeys("ff".repeat(32)).isEmpty())
    }

    // -- outbound Task 3: messageById store accessor --

    @Test fun messageByIdReturnsStoredMessageAndNullForMissing() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        val payload = mapOf("kind" to "post", "text" to "hello", "blobs" to emptyList<String>(), "placement" to "journal")
        val m = msg(1, payload)
        assertTrue(s.ingestMessage(m))

        // messageById returns the stored message with accessible fields
        val retrieved = s.messageById(m.msgId())!!
        assertEquals(m.msgId(), retrieved.msgId())
        assertEquals("post", retrieved.kind)
        assertEquals("journal", (retrieved.payload["placement"] as? String))
        assertEquals("hello", (retrieved.payload["text"] as? String))

        // messageById returns null for non-existent message
        assertNull(s.messageById("ff".repeat(32)))
    }

    // -- gossip server Task 1: messagesNotIn give-side entitlement filter --
    // SECURITY-CRITICAL: an over-serve here is a privacy breach. Exact port
    // of hearth's store.messages_not_in (store.py:702-750).

    // Builds a SIGNED message for an EXPLICIT identity_pub (unlike msg()
    // above, which is hardwired to a single fixed identity) -- entitlement
    // scenarios need several distinct identities (friend/peer/hostile
    // friend/third party) in one store. Mirrors DecryptPassTest's own
    // signedMessage() helper exactly: device_pub is a REAL derived Ed25519
    // point (verifyDeviceSignature must pass), identity_pub is passed
    // straight through as signed DATA, never itself verified as a derived
    // key (ingestMessage/verifyDeviceSignature never check that).
    private fun identityMsg(identityPub: String, seq: Int, payload: Map<String, Any?>, devPrivHex: String): SignedMessage {
        val devPub = devicePubOf(devPrivHex)
        val cert = KotlinWire.CertDict(identityPub, devPub, "d", 1752900000.0, "00")
        val unsigned = SignedMessage(cert, seq, payload, "")
        return unsigned.copy(signature = KotlinWire.signRaw(devPrivHex, unsigned.body()))
    }

    private fun devicePubOf(devPrivHex: String): String = KotlinWire.toHex(
        org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(KotlinWire.fromHex(devPrivHex), 0).generatePublicKey().encoded)

    @Test fun messagesNotInServesEntitledAndNeverOverServes() {
        val store = InMemorySyncStore()
        val friendPub = "b1".repeat(32)
        val friendDevPriv = "b2".repeat(32)
        val ringAuthorPub = "c1".repeat(32)
        val ringAuthorDevPriv = "c2".repeat(32)
        val peerPub = "d1".repeat(32)
        val peerDevPriv = "d2".repeat(32)
        val thirdPartyPub = "e1".repeat(32)
        val peerDevPub = devicePubOf(peerDevPriv)

        store.addIdentity(friendPub)
        store.addIdentity(ringAuthorPub)
        store.addIdentity(peerPub)

        // Seed the peer's OWN device so deviceViews(peerPub) is non-empty --
        // deviceViews is derived purely from stored (identity_pub,
        // device_pub) pairs, so the peer needs at least one message of its
        // own on record for its device set to be known.
        val peerOwnMsg = identityMsg(peerPub, 1,
            mapOf("kind" to "profile", "name" to "Peer", "created_at" to 1.0), peerDevPriv)
        assertTrue(store.ingestMessage(peerOwnMsg))

        // A friend POST wrapped to the peer's device -- entitled author,
        // peer's device is in the wrap-set -> must be served.
        val kredsFriendPost = identityMsg(friendPub, 1, mapOf(
            "kind" to "post", "scope" to "kreds", "text" to "hi",
            "wraps" to mapOf(peerDevPub to mapOf("x" to 1)), "blobs" to emptyList<String>()), friendDevPriv)
        assertTrue(store.ingestMessage(kredsFriendPost))

        // An inner-ring RING record, authored by someone who is NOT the
        // peer -- RING is author-private, must never relay through a friend
        // no matter how entitled that friend otherwise is.
        val innerRingRecord = identityMsg(ringAuthorPub, 1, mapOf(
            "kind" to "ring", "member" to "cc".repeat(32), "ring" to "inner", "created_at" to 1.0), ringAuthorDevPriv)
        assertTrue(store.ingestMessage(innerRingRecord))

        // A DM whose recipient is a THIRD party -- the peer is neither its
        // author nor its recipient -> DMs never relay through friends.
        val dmToThirdParty = identityMsg(friendPub, 2, mapOf(
            "kind" to "dm", "to" to thirdPartyPub, "body_nonce" to "ab".repeat(12), "body_ct" to "cd".repeat(20),
            "wraps" to emptyMap<String, Any?>(), "blobs" to emptyList<String>()), friendDevPriv)
        assertTrue(store.ingestMessage(dmToThirdParty))

        // A POST wrapped to some OTHER device, NOT the peer's -- entitled
        // author, but outside the wrap-set -> wrap-set gate blocks it.
        val postNotWrappedToPeer = identityMsg(friendPub, 3, mapOf(
            "kind" to "post", "scope" to "kreds", "text" to "nope",
            "wraps" to mapOf("ff".repeat(32) to mapOf("x" to 1)), "blobs" to emptyList<String>()), friendDevPriv)
        assertTrue(store.ingestMessage(postNotWrappedToPeer))

        val entitled = setOf(friendPub, ringAuthorPub)
        val out = store.messagesNotIn(emptyMap(), entitled, peerPub).map { it.msgId() }
        assertTrue("entitled + wrapped to the peer -> served", kredsFriendPost.msgId() in out)
        assertFalse("RING author-private -> never relayed", innerRingRecord.msgId() in out)
        assertFalse("DM to a third party -> never relayed to the peer", dmToThirdParty.msgId() in out)
        assertFalse("not wrapped to the peer -> wrap-set gate", postNotWrappedToPeer.msgId() in out)
    }

    @Test fun messagesNotInDropsWhatPeerAlreadyHas() {
        val store = InMemorySyncStore()
        val peerPub = "f1".repeat(32)
        val peerDevPriv = "f2".repeat(32)
        val peerDevPub = devicePubOf(peerDevPriv)
        store.addIdentity(peerPub)

        // Messages authored by the peer itself -- peerIdentity == author
        // bypasses the wrap-set gate entirely (store.py's `peer_identity !=
        // ipub` guard is false), isolating the seen-delta behavior under
        // test from the wrap-set gate exercised above.
        val m1 = identityMsg(peerPub, 1,
            mapOf("kind" to "post", "scope" to "kreds", "text" to "one", "blobs" to emptyList<String>()), peerDevPriv)
        val m2 = identityMsg(peerPub, 2,
            mapOf("kind" to "post", "scope" to "kreds", "text" to "two", "blobs" to emptyList<String>()), peerDevPriv)
        assertTrue(store.ingestMessage(m1))
        assertTrue(store.ingestMessage(m2))

        // The peer's own summary claims seq 1 (only) already seen.
        val summaries = mapOf((peerPub to peerDevPub) to SeenSet(contiguous = 1))
        val out = store.messagesNotIn(summaries, setOf(peerPub), peerPub).map { it.msgId() }
        assertFalse("seq already covered by the peer's summary -> dropped", m1.msgId() in out)
        assertTrue("seq NOT covered by the peer's summary -> still served", m2.msgId() in out)
    }

    @Test fun messagesNotInPostUnionsAuthorKeyedGrantDevices() {
        val authorPub = "12".repeat(32)
        val authorDevPriv1 = "13".repeat(32)
        val authorDevPriv2 = "14".repeat(32)
        val hostileFriendPub = "15".repeat(32)
        val hostileFriendDevPriv = "16".repeat(32)
        val peerPub = "17".repeat(32)
        val peerDevPriv = "18".repeat(32)
        val peerDevPub = devicePubOf(peerDevPriv)

        // Positive case: a friend POST with NO inline wrap for the peer at
        // all, but an AUTHOR-signed wrap_grant naming the peer's device --
        // the grant-union must open the gate.
        val store = InMemorySyncStore()
        store.addIdentity(authorPub)
        store.addIdentity(peerPub)
        assertTrue(store.ingestMessage(identityMsg(peerPub, 1,
            mapOf("kind" to "profile", "name" to "Peer", "created_at" to 1.0), peerDevPriv)))
        val post = identityMsg(authorPub, 1, mapOf(
            "kind" to "post", "scope" to "kreds", "text" to "grant test",
            "wraps" to mapOf("ff".repeat(32) to mapOf("x" to 1)), "blobs" to emptyList<String>()), authorDevPriv1)
        assertTrue(store.ingestMessage(post))
        // Signed by a DIFFERENT device of the SAME author identity -- proves
        // the grant match is on identity_pub, not device_pub.
        val authorGrant = identityMsg(authorPub, 2, mapOf(
            "kind" to "wrap_grant", "target" to post.msgId(), "created_at" to 1.0,
            "wraps" to mapOf(peerDevPub to mapOf("x" to 1))), authorDevPriv2)
        assertTrue(store.ingestMessage(authorGrant))

        val out1 = store.messagesNotIn(emptyMap(), setOf(authorPub), peerPub).map { it.msgId() }
        assertTrue("author-signed grant naming the peer's device opens the gate", post.msgId() in out1)

        // Negative case (REQUIRED, over-serve guard): a SEPARATE store, same
        // shape, but the grant naming the peer's device is signed by a
        // HOSTILE FRIEND (not the post's author) -- grant_devs is
        // author-keyed, so this must NEVER widen the post's audience,
        // however genuinely the grant names the peer's real device.
        val store2 = InMemorySyncStore()
        store2.addIdentity(authorPub)
        store2.addIdentity(hostileFriendPub)
        store2.addIdentity(peerPub)
        assertTrue(store2.ingestMessage(identityMsg(peerPub, 1,
            mapOf("kind" to "profile", "name" to "Peer", "created_at" to 1.0), peerDevPriv)))
        val post2 = identityMsg(authorPub, 1, mapOf(
            "kind" to "post", "scope" to "kreds", "text" to "grant test 2",
            "wraps" to mapOf("ff".repeat(32) to mapOf("x" to 1)), "blobs" to emptyList<String>()), authorDevPriv1)
        assertTrue(store2.ingestMessage(post2))
        val hostileGrant = identityMsg(hostileFriendPub, 1, mapOf(
            "kind" to "wrap_grant", "target" to post2.msgId(), "created_at" to 1.0,
            "wraps" to mapOf(peerDevPub to mapOf("x" to 1))), hostileFriendDevPriv)
        assertTrue(store2.ingestMessage(hostileGrant))

        val out2 = store2.messagesNotIn(emptyMap(), setOf(authorPub, hostileFriendPub), peerPub).map { it.msgId() }
        assertFalse("a non-author-signed grant must never widen the post's audience", post2.msgId() in out2)
    }

    // -- phone-onion-reachability Task 2: store primitives for
    //    revocation/defriend + onion meta -- removeIdentity, purgeAuthoredBy,
    //    markRevoked/isRevokedDevice, getMeta/setMeta. Mirror hearth store.py:
    //    remove_identity (store.py:162), purge_authored_by (store.py:1036),
    //    the retro-drop loop inside ingest_revocation (store.py:421-427), and
    //    the meta k/v table (store.py:121-133/set_meta/get_meta). Task 3
    //    (RevocationCert type + wire ingest) builds on these; this task is
    //    the store primitives alone.

    @Test fun removeIdentityDropsFromKnownIdentities() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        assertTrue(idPub in s.knownIdentities())
        s.removeIdentity(idPub)
        assertFalse(idPub in s.knownIdentities())
    }

    // Cross-check with the arc-1 give-side filter (messagesNotIn): removeIdentity
    // itself only touches the identities table -- messagesNotIn's `entitled` set
    // is caller-supplied, not read from knownIdentities() internally -- so this
    // demonstrates the REAL production composition: a caller that (correctly)
    // re-derives `entitled` from knownIdentities() after a removeIdentity call
    // stops serving that author, because the author has fallen out of that
    // derived set. Uses kind=profile (no audience gate in filterMessagesNotIn --
    // falls through to the entitled + seen-delta checks only), isolating this
    // from the wrap/DM/ring audience gates exercised elsewhere in this file.
    @Test fun removeIdentityStopsMessagesNotInServingViaEntitledFromKnownIdentities() {
        val s = InMemorySyncStore()
        val authorPub = "31".repeat(32)
        val authorDevPriv = "32".repeat(32)
        s.addIdentity(authorPub)
        val profileMsg = identityMsg(authorPub, 1,
            mapOf("kind" to "profile", "name" to "Author", "created_at" to 1.0), authorDevPriv)
        assertTrue(s.ingestMessage(profileMsg))

        val entitledBefore = s.knownIdentities().toSet()
        val outBefore = s.messagesNotIn(emptyMap(), entitledBefore, "ff".repeat(32)).map { it.msgId() }
        assertTrue("author still known -> entitled -> served", profileMsg.msgId() in outBefore)

        s.removeIdentity(authorPub)

        val entitledAfter = s.knownIdentities().toSet()
        val outAfter = s.messagesNotIn(emptyMap(), entitledAfter, "ff".repeat(32)).map { it.msgId() }
        assertFalse("author no longer known -> excluded from entitled -> not served", profileMsg.msgId() in outAfter)
    }

    @Test fun purgeAuthoredByDeletesOnlyThatAuthorsMessagesAndReturnsCount() {
        val s = InMemorySyncStore()
        val authorPub = "41".repeat(32)
        val authorDevPriv = "42".repeat(32)
        val otherPub = "43".repeat(32)
        val otherDevPriv = "44".repeat(32)
        s.addIdentity(authorPub)
        s.addIdentity(otherPub)
        val m1 = identityMsg(authorPub, 1, mapOf("kind" to "profile", "name" to "A1", "created_at" to 1.0), authorDevPriv)
        val m2 = identityMsg(authorPub, 2, mapOf("kind" to "profile", "name" to "A2", "created_at" to 2.0), authorDevPriv)
        val other = identityMsg(otherPub, 1, mapOf("kind" to "profile", "name" to "O", "created_at" to 1.0), otherDevPriv)
        assertTrue(s.ingestMessage(m1))
        assertTrue(s.ingestMessage(m2))
        assertTrue(s.ingestMessage(other))

        val count = s.purgeAuthoredBy(authorPub)
        assertEquals(2, count)

        val remaining = s.allMessages().map { it.msgId }
        assertFalse(m1.msgId() in remaining)
        assertFalse(m2.msgId() in remaining)
        assertTrue("a different author's message is untouched", other.msgId() in remaining)
    }

    // Cross-check with the arc-1 give-side filter, DISTINCT from the
    // removeIdentity cross-check above: here `entitled` still names the
    // purged author (the store doesn't require the caller to also call
    // removeIdentity), yet the message is still not served -- because
    // purgeAuthoredBy physically removed the row messagesNotIn scans, not
    // because of the entitled-set gate.
    @Test fun purgeAuthoredByMessagesNotInNoLongerServesPurgedAuthorEvenIfStillEntitled() {
        val s = InMemorySyncStore()
        val authorPub = "51".repeat(32)
        val authorDevPriv = "52".repeat(32)
        s.addIdentity(authorPub)
        val m = identityMsg(authorPub, 1, mapOf("kind" to "profile", "name" to "A", "created_at" to 1.0), authorDevPriv)
        assertTrue(s.ingestMessage(m))

        val entitled = setOf(authorPub)
        val before = s.messagesNotIn(emptyMap(), entitled, "ff".repeat(32)).map { it.msgId() }
        assertTrue("served before purge", m.msgId() in before)

        assertEquals(1, s.purgeAuthoredBy(authorPub))

        val after = s.messagesNotIn(emptyMap(), entitled, "ff".repeat(32)).map { it.msgId() }
        assertFalse("purged author's message gone even though still in `entitled`", m.msgId() in after)
    }

    @Test fun markRevokedSetsIsRevokedDeviceTrueForThatDeviceOnly() {
        val s = InMemorySyncStore()
        val devicePub = "61".repeat(32)
        val otherDevicePub = "62".repeat(32)
        assertFalse("never revoked -> false", s.isRevokedDevice(devicePub))
        s.markRevoked(devicePub, 5)
        assertTrue(s.isRevokedDevice(devicePub))
        assertFalse("a different device is unaffected", s.isRevokedDevice(otherDevicePub))
    }

    // Mirrors store.ingest_revocation's retro-drop loop (store.py:421-427):
    // `WHERE device_pub=? AND seq>?`, dropped; seq<=last_valid_seq survives
    // (it was validly signed before the revocation took effect).
    @Test fun markRevokedRetroDropsSeqAboveLastValidKeepsSeqAtOrBelow() {
        val s = InMemorySyncStore()
        val authorPub = "71".repeat(32)
        val devPriv = "72".repeat(32)
        val devPub = devicePubOf(devPriv)
        s.addIdentity(authorPub)
        val m1 = identityMsg(authorPub, 1, mapOf("kind" to "profile", "name" to "s1", "created_at" to 1.0), devPriv)
        val m2 = identityMsg(authorPub, 2, mapOf("kind" to "profile", "name" to "s2", "created_at" to 2.0), devPriv)
        val m3 = identityMsg(authorPub, 3, mapOf("kind" to "profile", "name" to "s3", "created_at" to 3.0), devPriv)
        val m4 = identityMsg(authorPub, 4, mapOf("kind" to "profile", "name" to "s4", "created_at" to 4.0), devPriv)
        assertTrue(s.ingestMessage(m1))
        assertTrue(s.ingestMessage(m2))
        assertTrue(s.ingestMessage(m3))
        assertTrue(s.ingestMessage(m4))

        s.markRevoked(devPub, 2)

        val remaining = s.allMessages().map { it.msgId }.toSet()
        assertTrue("seq<=lastValid kept (seq 1)", m1.msgId() in remaining)
        assertTrue("seq<=lastValid kept (seq 2)", m2.msgId() in remaining)
        assertFalse("seq>lastValid retro-dropped (seq 3)", m3.msgId() in remaining)
        assertFalse("seq>lastValid retro-dropped (seq 4)", m4.msgId() in remaining)
    }

    @Test fun metaGetSetRoundTripsNullOnAbsentAndOverwrites() {
        val s = InMemorySyncStore()
        assertNull("never set -> null", s.getMeta("some_key"))
        s.setMeta("some_key", "v1")
        assertEquals("v1", s.getMeta("some_key"))
        s.setMeta("some_key", "v2")             // a later set overwrites, not appends
        assertEquals("v2", s.getMeta("some_key"))
        assertNull("a different key stays absent", s.getMeta("other_key"))
    }

    // -- friend-peering Task 1: peer table (addPeer/listPeers/removePeer/
    //    addressFor) -- mirrors hearth store.py's peers table (schema
    //    store.py:39-40; add_peer store.py:217-221; list_peers store.py:
    //    223-227; remove_peer store.py:229-232; address_for store.py:
    //    234-239).

    @Test fun addPeerListPeersRoundTripsAddressAndIdentityPub() {
        val s = InMemorySyncStore()
        val address = "abc123.onion:9001"
        s.addPeer(address, idPub)
        val peers = s.listPeers()
        assertEquals(1, peers.size)
        assertEquals(address, peers[0].address)
        assertEquals(idPub, peers[0].identityPub)
    }

    @Test fun addPeerSameAddressReplacesIdentityPubNotDuplicates() {
        val s = InMemorySyncStore()
        val address = "abc123.onion:9001"
        s.addPeer(address, "11".repeat(32))
        s.addPeer(address, "22".repeat(32))     // same address, new identity -> INSERT OR REPLACE
        val peers = s.listPeers()
        assertEquals("still one row for that address", 1, peers.size)
        assertEquals("22".repeat(32), peers[0].identityPub)
    }

    @Test fun removePeerDropsIt() {
        val s = InMemorySyncStore()
        val address = "abc123.onion:9001"
        s.addPeer(address, idPub)
        assertEquals(1, s.listPeers().size)
        s.removePeer(address)
        assertTrue("removed -> no longer listed", s.listPeers().isEmpty())
    }

    @Test fun addressForReturnsAddressForIdentityNullWhenAbsent() {
        val s = InMemorySyncStore()
        val address = "abc123.onion:9001"
        s.addPeer(address, idPub)
        assertEquals(address, s.addressFor(idPub))
        assertNull("no peer holds this identity", s.addressFor("ff".repeat(32)))
    }

    @Test fun addPeerNullIdentityPubRoundTrips() {
        val s = InMemorySyncStore()
        val address = "abc123.onion:9001"
        s.addPeer(address, null)
        val peers = s.listPeers()
        assertEquals(1, peers.size)
        assertEquals(address, peers[0].address)
        assertNull(peers[0].identityPub)
    }
}
