package expo.modules.tormanager

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** JVM test for ComposeResponse (Task 4, outbound-responses slice): proves
 *  the FULL compose_response chain -- validation, target lookup, sig-payload
 *  + device signature, mutual-box seal, content encrypt, author+self wraps,
 *  device-sign, local ingest + enqueue -- without a node, mirroring
 *  ComposeTest's idiom (fixture(), SignedMessageSigned()) but round-tripping
 *  through a SECOND real identity (a "friend") wherever the self-wrap alone
 *  would be a tautology: the mutual_box is opened with a friend's
 *  independently-generated X25519 key (never the key ComposeResponse itself
 *  sealed with), and a friend-authored target's author-wrap is opened with
 *  the friend's own enc key, not our own. */
class ComposeResponseTest {

    private fun fixture(devPrivHex: String, identityPubHex: String): KotlinHandshake.Fixture {
        val devPub = KotlinWire.toHex(
            Ed25519PrivateKeyParameters(KotlinWire.fromHex(devPrivHex), 0).generatePublicKey().encoded)
        val cert = KotlinWire.CertDict(identityPubHex, devPub, "d", 1752900000.0, "00")
        return KotlinHandshake.Fixture(devPrivHex, devPub, cert, "dummy.onion:9001")
    }

    private fun own() = fixture("a1".repeat(32), "b2".repeat(32))
    private fun friend() = fixture("c3".repeat(32), "d4".repeat(32))

    // Same throwaway-signature-then-real-signature idiom as ComposeTest.
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

    /** Seeds `fx` as a known identity with a published own enckey, plus a
     *  journal post authored by `fx` to use as a response target. Returns
     *  (encPriv, encPub, targetMsgId). */
    private fun seedOwnJournalPost(s: InMemorySyncStore, fx: KotlinHandshake.Fixture): Triple<String, String, String> {
        s.addIdentity(fx.cert.identity_pub)
        val (encPriv, encPub) = EncKeys.getOrCreate(s)
        publishEnckey(s, fx, encPub)
        val targetMsg = SignedMessageSigned(fx, s.nextSeq(), mapOf(
            "kind" to "post", "placement" to "journal", "created_at" to KotlinWire.PyFloat(1752900000.0)))
        s.ingestMessage(targetMsg)
        return Triple(encPriv, encPub, targetMsg.msgId())
    }

    @Test fun composeReactionOnOwnPostDecryptsViaOwnWrapAndOpensMutualBox() {
        val s = InMemorySyncStore()
        val fx = own()
        val (encPriv, encPub, target) = seedOwnJournalPost(s, fx)

        // a friend, registered so the mutual box has a real (non-dummy) slot
        // to genuinely open -- own is never its own friend (excluded from
        // friendPubs), so this is the only way to open the box for real.
        val fr = friend()
        s.addIdentity(fr.cert.identity_pub)
        val frEnc = genEncKey()
        publishEnckey(s, fr, frEnc.pubHex)

        val res = ComposeResponse.compose(s, fx, encPriv, encPub, target, "reaction", "heart", 1752900100.0)

        @Suppress("UNCHECKED_CAST") val payload = res.wireDict["payload"] as Map<String, Any?>
        assertEquals("response", payload["kind"])
        assertEquals(target, payload["target"])
        val effectiveCreatedAt = (payload["created_at"] as KotlinWire.PyFloat).value

        val aad = KotlinDmcrypt.responseAad(fx.cert.identity_pub, target, effectiveCreatedAt)
        @Suppress("UNCHECKED_CAST") val wraps = payload["wraps"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val myWrap = wraps[fx.device_pub] as Map<String, Any?>
        val key = KotlinDmcrypt.unwrapKey(myWrap, encPriv, aad)!!
        val body = KotlinDmcrypt.decryptBody(key, payload["body_nonce"] as String, payload["body_ct"] as String, aad)!!

        assertEquals("reaction", body["rkind"])
        assertEquals("heart", body["body"])
        assertEquals(fx.cert.identity_pub, body["responder"])
        assertEquals(false, body["public"])
        val responderSig = body["responder_sig"] as String
        assertTrue(responderSig.isNotEmpty())
        assertEquals(KotlinDmcrypt.deriveAliasSeed(fx.device_priv, target), body["alias_seed"])

        // mutual_box: well-formed, bucket-sized list...
        val mutualBoxArray = body["mutual_box"] as org.json.JSONArray
        assertTrue(mutualBoxArray.length() in setOf(8, 16, 32, 64))
        val slots = (0 until mutualBoxArray.length()).map { i ->
            val o = mutualBoxArray.getJSONObject(i)
            mapOf("eph_pub" to o.getString("eph_pub"), "nonce" to o.getString("nonce"), "ct" to o.getString("ct"))
        }
        // ...that genuinely opens for a real friend using THEIR OWN,
        // independently-generated priv key -- NOT the key ComposeResponse
        // itself sealed with, so this is not a tautology.
        val opened = KotlinSeal.tryOpenSlots(slots, frEnc.privHex)!!
        val openedJson = JSONObject(String(opened, Charsets.UTF_8))
        assertEquals(fx.cert.identity_pub, openedJson.getString("identity"))
        assertEquals(fx.device_pub, openedJson.getString("device_pub"))
        assertEquals(responderSig, openedJson.getString("sig"))

        // and that sig genuinely verifies against fx's device key over the
        // exact _response_sig_payload hearth signs (node.py:1389-1397) --
        // independently re-derived here, not reused from ComposeResponse.
        val sigPayload = KotlinResponses.responseSigPayload(target, "reaction", "heart", effectiveCreatedAt, fx.cert.identity_pub)
        assertTrue(KotlinWire.verifyRaw(fx.device_pub, responderSig, sigPayload))

        // locally ingested + queued for the next sync (design step 8)
        assertNotNull(s.messageById(res.msgId))
        assertEquals(1, s.pendingOutbound().size)
    }

    @Test fun composeCommentOnFriendsPostWrapsToAuthorAndSelfWithSameAliasSeed() {
        val s = InMemorySyncStore()
        val fx = own()
        s.addIdentity(fx.cert.identity_pub)
        val (encPriv, encPub) = EncKeys.getOrCreate(s)
        publishEnckey(s, fx, encPub)

        val fr = friend()
        s.addIdentity(fr.cert.identity_pub)
        val frEnc = genEncKey()
        publishEnckey(s, fr, frEnc.pubHex)

        // friend's journal post -- the response target; author != own here.
        val targetMsg = SignedMessageSigned(fr, s.nextSeq(), mapOf(
            "kind" to "post", "placement" to "journal", "created_at" to KotlinWire.PyFloat(1752900000.0)))
        s.ingestMessage(targetMsg)
        val target = targetMsg.msgId()

        val res = ComposeResponse.compose(s, fx, encPriv, encPub, target, "comment", "nice shot!", 1752900200.0)

        @Suppress("UNCHECKED_CAST") val payload = res.wireDict["payload"] as Map<String, Any?>
        val effectiveCreatedAt = (payload["created_at"] as KotlinWire.PyFloat).value
        val aad = KotlinDmcrypt.responseAad(fx.cert.identity_pub, target, effectiveCreatedAt)
        @Suppress("UNCHECKED_CAST") val wraps = payload["wraps"] as Map<String, Any?>

        // the AUTHOR (friend) can decrypt via their own device wrap, using
        // THEIR OWN enc priv key (never ours).
        @Suppress("UNCHECKED_CAST") val authorWrap = wraps[fr.device_pub] as Map<String, Any?>
        val keyForAuthor = KotlinDmcrypt.unwrapKey(authorWrap, frEnc.privHex, aad)!!
        val bodyForAuthor = KotlinDmcrypt.decryptBody(
            keyForAuthor, payload["body_nonce"] as String, payload["body_ct"] as String, aad)!!
        assertEquals("comment", bodyForAuthor["rkind"])
        assertEquals("nice shot!", bodyForAuthor["body"])
        assertEquals(fx.cert.identity_pub, bodyForAuthor["responder"])
        assertEquals(KotlinDmcrypt.deriveAliasSeed(fx.device_priv, target), bodyForAuthor["alias_seed"])

        // AND own (self-readable, retract UI) decrypts the SAME content key.
        @Suppress("UNCHECKED_CAST") val ownWrap = wraps[fx.device_pub] as Map<String, Any?>
        val keyForOwn = KotlinDmcrypt.unwrapKey(ownWrap, encPriv, aad)!!
        assertArrayEquals(keyForAuthor, keyForOwn)
    }

    // ---- validation-rejection coverage (mirrors hearth compose_response's
    // exact raises, node.py:2366-2383) ----

    @Test fun rejectsBadRkind() {
        val s = InMemorySyncStore()
        val fx = own()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ComposeResponse.compose(s, fx, "00".repeat(32), "00".repeat(32), "ff".repeat(32), "bogus", "x", 1.0)
        }
        assertEquals("bad response kind", ex.message)
    }

    @Test fun rejectsOversizedComment() {
        val s = InMemorySyncStore()
        val fx = own()
        val (encPriv, encPub, target) = seedOwnJournalPost(s, fx)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ComposeResponse.compose(s, fx, encPriv, encPub, target, "comment", "x".repeat(501), 1752900100.0)
        }
        assertEquals("comment must be 1-500 characters", ex.message)
    }

    @Test fun rejectsEmptyComment() {
        val s = InMemorySyncStore()
        val fx = own()
        val (encPriv, encPub, target) = seedOwnJournalPost(s, fx)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ComposeResponse.compose(s, fx, encPriv, encPub, target, "comment", "", 1752900100.0)
        }
        assertEquals("comment must be 1-500 characters", ex.message)
    }

    @Test fun rejectsUnknownReactionToken() {
        val s = InMemorySyncStore()
        val fx = own()
        val (encPriv, encPub, target) = seedOwnJournalPost(s, fx)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ComposeResponse.compose(s, fx, encPriv, encPub, target, "reaction", "shrug", 1752900100.0)
        }
        assertEquals("unknown reaction", ex.message)
    }

    @Test fun acceptsReactionClearToken() {
        val s = InMemorySyncStore()
        val fx = own()
        val (encPriv, encPub, target) = seedOwnJournalPost(s, fx)
        val res = ComposeResponse.compose(s, fx, encPriv, encPub, target, "reaction", "clear", 1752900100.0)
        assertNotNull(res.msgId)
    }

    @Test fun acceptsRetractWithEmptyBody() {
        val s = InMemorySyncStore()
        val fx = own()
        val (encPriv, encPub, target) = seedOwnJournalPost(s, fx)
        val res = ComposeResponse.compose(s, fx, encPriv, encPub, target, "retract", "", 1752900100.0)
        assertNotNull(res.msgId)
    }

    @Test fun rejectsUnknownTarget() {
        val s = InMemorySyncStore()
        val fx = own()
        s.addIdentity(fx.cert.identity_pub)
        val (encPriv, encPub) = EncKeys.getOrCreate(s)
        publishEnckey(s, fx, encPub)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ComposeResponse.compose(s, fx, encPriv, encPub, "ab".repeat(32), "comment", "hi", 1752900100.0)
        }
        assertEquals("not a journal post", ex.message)
    }

    @Test fun rejectsNonJournalPlacement() {
        val s = InMemorySyncStore()
        val fx = own()
        s.addIdentity(fx.cert.identity_pub)
        val (encPriv, encPub) = EncKeys.getOrCreate(s)
        publishEnckey(s, fx, encPub)
        val targetMsg = SignedMessageSigned(fx, s.nextSeq(), mapOf(
            "kind" to "post", "placement" to "wall", "created_at" to KotlinWire.PyFloat(1752900000.0)))
        s.ingestMessage(targetMsg)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ComposeResponse.compose(s, fx, encPriv, encPub, targetMsg.msgId(), "comment", "hi", 1752900100.0)
        }
        assertEquals("not a journal post", ex.message)
    }

    @Test fun rejectsNonPostKind() {
        val s = InMemorySyncStore()
        val fx = own()
        s.addIdentity(fx.cert.identity_pub)
        val (encPriv, encPub) = EncKeys.getOrCreate(s)
        publishEnckey(s, fx, encPub)
        val targetMsg = SignedMessageSigned(fx, s.nextSeq(), mapOf(
            "kind" to "dm", "created_at" to KotlinWire.PyFloat(1752900000.0)))
        s.ingestMessage(targetMsg)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ComposeResponse.compose(s, fx, encPriv, encPub, targetMsg.msgId(), "comment", "hi", 1752900100.0)
        }
        assertEquals("not a journal post", ex.message)
    }

    @Test fun rejectsWhenAuthorHasNoReachableDevices() {
        val s = InMemorySyncStore()
        val fx = own()
        s.addIdentity(fx.cert.identity_pub)
        val (encPriv, encPub) = EncKeys.getOrCreate(s)
        publishEnckey(s, fx, encPub)

        // friend is known (so ingestMessage's is_known gate passes for their
        // post) but never publishes an enckey -- store.enckeys(friend) is
        // empty, and author != own, so authorDevs stays empty.
        val fr = friend()
        s.addIdentity(fr.cert.identity_pub)
        val targetMsg = SignedMessageSigned(fr, s.nextSeq(), mapOf(
            "kind" to "post", "placement" to "journal", "created_at" to KotlinWire.PyFloat(1752900000.0)))
        s.ingestMessage(targetMsg)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            ComposeResponse.compose(s, fx, encPriv, encPub, targetMsg.msgId(), "comment", "hi", 1752900100.0)
        }
        assertEquals("no reachable devices for the author", ex.message)
    }
}
