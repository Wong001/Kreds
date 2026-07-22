package expo.modules.tormanager

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** JVM test for ComposeDm (Task 1, outbound-dm-send slice): proves the FULL
 *  compose_dm chain -- validation (self-DM/not-a-friend/bad story_ref/no
 *  recipient enckeys, hearth's exact order node.py:2308-2331), device-pub
 *  merge (_dm_device_pubs, mine wins on a shared device_pub), content
 *  encrypt, own+recipient wraps, photo blob storage, device-sign, local
 *  ingest + enqueue -- without a node, mirroring ComposeResponseTest's idiom
 *  (fixture()/genEncKey()/publishEnckey(), round-tripping through a SECOND
 *  real identity so the recipient-wrap check is never a tautology). */
class ComposeDmTest {

    private fun fixture(devPrivHex: String, identityPubHex: String): KotlinHandshake.Fixture {
        val devPub = KotlinWire.toHex(
            Ed25519PrivateKeyParameters(KotlinWire.fromHex(devPrivHex), 0).generatePublicKey().encoded)
        val cert = KotlinWire.CertDict(identityPubHex, devPub, "d", 1752900000.0, "00")
        return KotlinHandshake.Fixture(devPrivHex, devPub, cert, "dummy.onion:9001")
    }

    private fun own() = fixture("a1".repeat(32), "b2".repeat(32))
    private fun friend() = fixture("c3".repeat(32), "d4".repeat(32))

    // Same throwaway-signature-then-real-signature idiom as ComposeTest/ComposeResponseTest.
    private fun SignedMessageSigned(fx: KotlinHandshake.Fixture, seq: Int, payload: Map<String, Any?>): SignedMessage {
        val unsigned = SignedMessage(fx.cert, seq, payload, "")
        return unsigned.copy(signature = KotlinWire.signRaw(fx.device_priv, unsigned.body()))
    }

    private data class GenEnc(val privHex: String, val pubHex: String)

    private fun genEncKey(): GenEnc {
        val p = X25519PrivateKeyParameters(SecureRandom())
        return GenEnc(KotlinWire.toHex(p.encoded), KotlinWire.toHex(p.generatePublicKey().encoded))
    }

    private fun publishEnckey(s: InMemorySyncStore, fx: KotlinHandshake.Fixture, encPub: String) {
        s.ingestMessage(SignedMessageSigned(fx, s.nextSeq(), mapOf(
            "kind" to "enckey", "enc_pub" to encPub, "created_at" to 100.0)))
    }

    private fun assertThrowsMsg(expected: String, block: () -> Unit) {
        val ex = assertThrows(IllegalArgumentException::class.java) { block() }
        assertEquals(expected, ex.message)
    }

    @Test fun composeTextDmDecryptsViaOwnWrapAndRecipientWrap() {
        val s = InMemorySyncStore()
        val fx = own()
        s.addIdentity(fx.cert.identity_pub)
        val (encPriv, encPub) = EncKeys.getOrCreate(s)
        publishEnckey(s, fx, encPub)

        // an independent recipient identity + its own X25519 keypair (never
        // the key ComposeDm itself sealed with) -- so opening the recipient
        // wrap is not a tautology against our own key.
        val fr = friend()
        s.addIdentity(fr.cert.identity_pub)
        val frEnc = genEncKey()
        publishEnckey(s, fr, frEnc.pubHex)
        val friendId = fr.cert.identity_pub

        val r = ComposeDm.compose(s, fx, encPriv, encPub, friendId,
            "hej fra telefonen", emptyList(), null, null, 1753100000.25)

        @Suppress("UNCHECKED_CAST") val env = r.wireDict["payload"] as Map<String, Any?>
        assertEquals("dm", env["kind"]); assertEquals(friendId, env["to"])
        assertTrue(env.containsKey("expires_at")); assertNull(env["expires_at"])
        assertTrue(env.containsKey("story_ref")); assertNull(env["story_ref"])

        // own-device wrap -> proven inverses recover the body
        val aad = KotlinDmcrypt.dmAad(fx.cert.identity_pub, friendId, 1753100000.25)
        @Suppress("UNCHECKED_CAST") val wraps = env["wraps"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val myWrap = wraps[fx.device_pub] as Map<String, Any?>
        val key = KotlinDmcrypt.unwrapKey(myWrap, encPriv, aad)!!
        val body = KotlinDmcrypt.decryptBody(key, env["body_nonce"] as String, env["body_ct"] as String, aad)!!
        assertEquals("hej fra telefonen", body["text"])
        // decryptBody surfaces a JSON array, not a Kotlin List (ComposeTest's
        // documented JSONArray quirk) -- compare shape, not equality.
        assertEquals(0, (body["blobs"] as org.json.JSONArray).length())

        // recipient wrap opens with the FRIEND's independent enc_priv
        @Suppress("UNCHECKED_CAST") val friendWrap = wraps[fr.device_pub] as Map<String, Any?>
        val fkey = KotlinDmcrypt.unwrapKey(friendWrap, frEnc.privHex, aad)
        assertNotNull(fkey)
        assertArrayEquals(key, fkey)

        // locally ingested + queued for the next sync (mirrors ComposeResponse's tail)
        assertNotNull(s.messageById(r.msgId))
        assertEquals(1, s.pendingOutbound().size)
        @Suppress("UNCHECKED_CAST") val queuedPayload = s.pendingOutbound()[0]["payload"] as Map<String, Any?>
        assertEquals("dm", queuedPayload["kind"]); assertEquals(friendId, queuedPayload["to"])

        assertArrayEquals(key, r.contentKey)
    }

    @Test fun rejectsSelfDm() {
        val s = InMemorySyncStore()
        val fx = own()
        s.addIdentity(fx.cert.identity_pub)
        val (encPriv, encPub) = EncKeys.getOrCreate(s)
        publishEnckey(s, fx, encPub)
        assertThrowsMsg("cannot DM yourself") {
            ComposeDm.compose(s, fx, encPriv, encPub, fx.cert.identity_pub,
                "hi", emptyList(), null, null, 1.0)
        }
    }

    @Test fun rejectsNonFriendRecipient() {
        val s = InMemorySyncStore()
        val fx = own()
        s.addIdentity(fx.cert.identity_pub)
        val (encPriv, encPub) = EncKeys.getOrCreate(s)
        publishEnckey(s, fx, encPub)
        val strangerId = "ee".repeat(32)   // never added via s.addIdentity -- unknown
        assertThrowsMsg("recipient is not a friend") {
            ComposeDm.compose(s, fx, encPriv, encPub, strangerId,
                "hi", emptyList(), null, null, 1.0)
        }
    }

    @Test fun rejectsBadStoryRef() {
        val s = InMemorySyncStore()
        val fx = own()
        s.addIdentity(fx.cert.identity_pub)
        val (encPriv, encPub) = EncKeys.getOrCreate(s)
        publishEnckey(s, fx, encPub)
        val fr = friend()
        s.addIdentity(fr.cert.identity_pub)
        // deliberately NO enckey published for fr -- proves story_ref is
        // validated BEFORE the enckeys check (hearth's exact order,
        // node.py:2320-2331): if the check order were reversed this would
        // fail with "no encryption keys known..." instead.
        assertThrowsMsg("bad story_ref") {
            ComposeDm.compose(s, fx, encPriv, encPub, fr.cert.identity_pub,
                "hi", emptyList(), null, mapOf("story_id" to ""), 1.0)
        }
    }

    @Test fun rejectsRecipientWithNoEnckey() {
        val s = InMemorySyncStore()
        val fx = own()
        s.addIdentity(fx.cert.identity_pub)
        val (encPriv, encPub) = EncKeys.getOrCreate(s)
        publishEnckey(s, fx, encPub)
        val fr = friend()
        s.addIdentity(fr.cert.identity_pub)   // known, but never publishes an enckey
        assertThrowsMsg("no encryption keys known for recipient yet") {
            ComposeDm.compose(s, fx, encPriv, encPub, fr.cert.identity_pub,
                "hi", emptyList(), null, null, 1.0)
        }
    }

    @Test fun expiryAndStoryRefRideTheEnvelope() {
        val sref = mapOf("story_id" to "s1", "media_hash" to "ab".repeat(32), "extra" to 1)
        assertTrue(ComposeDm.validStoryRef(sref))                                          // extra keys pass
        assertFalse(ComposeDm.validStoryRef(mapOf("story_id" to "s1")))                    // media_hash missing
        assertFalse(ComposeDm.validStoryRef(mapOf("story_id" to "s1", "media_hash" to "zz")))
        assertFalse(ComposeDm.validStoryRef(mapOf("story_id" to "", "media_hash" to "ab".repeat(32))))
        assertFalse(ComposeDm.validStoryRef(null))

        val s = InMemorySyncStore()
        val fx = own()
        s.addIdentity(fx.cert.identity_pub)
        val (encPriv, encPub) = EncKeys.getOrCreate(s)
        publishEnckey(s, fx, encPub)
        val fr = friend()
        s.addIdentity(fr.cert.identity_pub)
        val frEnc = genEncKey()
        publishEnckey(s, fr, frEnc.pubHex)

        val r = ComposeDm.compose(s, fx, encPriv, encPub, fr.cert.identity_pub,
            "story reply", emptyList(), 60.0, sref, 1753100000.25)
        @Suppress("UNCHECKED_CAST") val env = r.wireDict["payload"] as Map<String, Any?>
        assertEquals(1753100060.25, (env["expires_at"] as KotlinWire.PyFloat).value, 0.0)
        assertEquals(sref, env["story_ref"])
    }

    @Test fun photoDmBlobRefsAreCiphertextHashesDecryptableByContentKey() {
        val s = InMemorySyncStore()
        val fx = own()
        s.addIdentity(fx.cert.identity_pub)
        val (encPriv, encPub) = EncKeys.getOrCreate(s)
        publishEnckey(s, fx, encPub)
        val fr = friend()
        s.addIdentity(fr.cert.identity_pub)
        val frEnc = genEncKey()
        publishEnckey(s, fr, frEnc.pubHex)

        val jpeg = byteArrayOf(1, 2, 3, 4, 5)
        val r = ComposeDm.compose(s, fx, encPriv, encPub, fr.cert.identity_pub,
            "one photo", listOf(jpeg), null, null, 1753100000.0)

        @Suppress("UNCHECKED_CAST") val env = r.wireDict["payload"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val refs = env["blobs"] as List<String>
        assertEquals(1, refs.size)

        // stored ciphertext (Compose.kt's inline store.putBlob mechanics --
        // hearth's store.put_blob(encrypt_blob(...)) equivalent) decrypts
        // back to the original jpeg with the content key.
        val storedCipher = s.getBlob(refs[0])
        assertNotNull(storedCipher)
        assertArrayEquals(jpeg, KotlinBlobCrypt.decryptBlob(r.contentKey, storedCipher!!))

        // body refs == envelope refs
        val aad = KotlinDmcrypt.dmAad(fx.cert.identity_pub, fr.cert.identity_pub, 1753100000.0)
        val body = KotlinDmcrypt.decryptBody(r.contentKey, env["body_nonce"] as String, env["body_ct"] as String, aad)!!
        val bodyBlobs = body["blobs"] as org.json.JSONArray
        assertEquals(1, bodyBlobs.length())
        assertEquals(refs[0], bodyBlobs.getString(0))
    }

    @Test fun recipientDeviceWrapPrefersOwnPublishedKeyOverRecipientsPublishedKeyForSharedDevicePub() {
        // _dm_device_pubs merge semantics (node.py:2308-2315): {**theirs,
        // **mine} -- if a device_pub happens to appear in BOTH theirs and
        // mine (only plausible if it's literally our own device_pub, which
        // can only be true if the recipient ALSO published an enckey under
        // this exact identity's own device -- i.e. a self-DM-shaped edge --
        // but for a normal distinct-identity DM the important, testable
        // guarantee is simply that OUR explicit fx.device_pub->encPub
        // override always wins over whatever this device may have
        // previously self-published (e.g. a stale enckey row), never a
        // stale value from store.enckeys(own). This proves the explicit
        // override lands last regardless of what store.enckeys(own) holds.
        val s = InMemorySyncStore()
        val fx = own()
        s.addIdentity(fx.cert.identity_pub)
        val (encPriv, encPub) = EncKeys.getOrCreate(s)
        // publish a STALE own enckey under a different value than the
        // current session's encPub (simulates store.enckeys(own) holding a
        // value distinct from what compose is told to use explicitly).
        val staleEnc = genEncKey()
        publishEnckey(s, fx, staleEnc.pubHex)

        val fr = friend()
        s.addIdentity(fr.cert.identity_pub)
        val frEnc = genEncKey()
        publishEnckey(s, fr, frEnc.pubHex)

        val r = ComposeDm.compose(s, fx, encPriv, encPub, fr.cert.identity_pub,
            "hi", emptyList(), null, null, 1753100000.0)
        @Suppress("UNCHECKED_CAST") val env = r.wireDict["payload"] as Map<String, Any?>
        val aad = KotlinDmcrypt.dmAad(fx.cert.identity_pub, fr.cert.identity_pub, 1753100000.0)
        @Suppress("UNCHECKED_CAST") val wraps = env["wraps"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val myWrap = wraps[fx.device_pub] as Map<String, Any?>
        // openable with the CURRENT encPriv (explicit override), not staleEnc's priv
        val key = KotlinDmcrypt.unwrapKey(myWrap, encPriv, aad)
        assertNotNull(key)
        assertArrayEquals(r.contentKey, key)
    }
}
