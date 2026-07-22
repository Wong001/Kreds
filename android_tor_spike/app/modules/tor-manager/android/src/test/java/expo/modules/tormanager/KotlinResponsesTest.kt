package expo.modules.tormanager

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** B.2d-4 Task 2 -- the SECURITY CORE of the reactions+comments slice.
 *  Exercises KotlinResponses against Task 1's committed "responses" vector
 *  (a REAL hearth-aggregated KIND_RESPONSES record: a valid PUBLIC comment
 *  with responder identity/device_pub/signature, plus a PRIVATE reaction).
 *  The three attribution cases below are the whole point of the task:
 *   - valid public sig + device-bound  -> the real name,
 *   - corrupted responder_sig          -> the alias (sig failed),
 *   - valid sig but NOT device-bound   -> the alias (sig alone is forgeable).
 */
class KotlinResponsesTest {
    private fun cases(): JSONArray {
        val t = javaClass.classLoader!!.getResourceAsStream("dmcrypt_vectors.json")!!
            .readBytes().toString(Charsets.UTF_8)
        return JSONObject(t).getJSONArray("cases")
    }

    private fun map(o: JSONObject): Map<String, Any?> =
        o.keys().asSequence().associateWith { o.get(it) }

    private fun responsesCase(): JSONObject {
        val cs = cases()
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            if (c.getString("kind") == "responses") return c
        }
        throw AssertionError("no \"responses\" case in fixture")
    }

    // entries[0] = the public comment; entries[1] = the private reaction.
    private fun publicEntry(): Map<String, Any?> =
        map(responsesCase().getJSONArray("entries").getJSONObject(0))
    private fun privateEntry(): Map<String, Any?> =
        map(responsesCase().getJSONArray("entries").getJSONObject(1))
    private fun target(): String = responsesCase().getString("target")
    private fun publicIdentity(): String = responsesCase().getString("public_responder_identity")

    // ---- alias derivation (byte-for-byte with web/app.js) ----

    @Test fun aliasNameMatchesAppJs() {
        // 0x67 % 16 = 7 -> "Curious"; 0x29 % 16 = 9 -> "Crane".
        assertEquals("Curious Crane", KotlinResponses.aliasName("6729573541a71c19037ac2a37622a1d6"))
        // 0xd5 % 16 = 5 -> "Swift"; 0xa8 % 16 = 8 -> "Seal".
        assertEquals("Swift Seal", KotlinResponses.aliasName("d5a870ac22207ae6ffc26e7c25ca6456"))
    }

    @Test fun aliasColorMatchesAppJs() {
        // 0x672957 % 360 = 351; 0xd5a870 % 360 = 88.
        assertEquals(351, KotlinResponses.aliasColor("6729573541a71c19037ac2a37622a1d6"))
        assertEquals(88, KotlinResponses.aliasColor("d5a870ac22207ae6ffc26e7c25ca6456"))
    }

    // ---- validEntry (mirrors hearth _valid_response_entry, minus mutual_box) ----

    @Test fun validEntryAcceptsGoodCommentAndReaction() {
        assertTrue("real public comment", KotlinResponses.validEntry(publicEntry()))
        assertTrue("real private reaction", KotlinResponses.validEntry(privateEntry()))
    }

    @Test fun validEntryRejectsBadRkind() {
        val e = publicEntry().toMutableMap(); e["rkind"] = "sticker"
        assertFalse(KotlinResponses.validEntry(e))
    }

    @Test fun validEntryRejectsOversizedComment() {
        val e = publicEntry().toMutableMap(); e["body"] = "x".repeat(501)
        assertFalse(KotlinResponses.validEntry(e))
    }

    @Test fun validEntryRejectsEmptyComment() {
        val e = publicEntry().toMutableMap(); e["body"] = ""
        assertFalse(KotlinResponses.validEntry(e))
    }

    @Test fun validEntryRejectsUnknownReactionToken() {
        val e = privateEntry().toMutableMap(); e["body"] = "thumbsup"
        assertFalse(KotlinResponses.validEntry(e))
    }

    @Test fun validEntryRejectsMalformedResponderSig() {
        val e = publicEntry().toMutableMap(); e["responder_sig"] = "deadbeef"   // not 128 hex
        assertFalse(KotlinResponses.validEntry(e))
    }

    @Test fun validEntryRejectsPublicMissingIdentity() {
        val e = publicEntry().toMutableMap(); e.remove("identity")
        assertFalse(KotlinResponses.validEntry(e))
    }

    @Test fun validEntryRejectsPublicMissingDevicePub() {
        val e = publicEntry().toMutableMap(); e.remove("device_pub")
        assertFalse(KotlinResponses.validEntry(e))
    }

    @Test fun validEntryRejectsBadAliasSeed() {
        val e = privateEntry().toMutableMap(); e["alias_seed"] = "abcd"   // not 32 hex
        assertFalse(KotlinResponses.validEntry(e))
    }

    @Test fun validEntryRejectsMalformedMutualBox() {
        // hearth _valid_mutual_box (node.py:62-90): a slot's nonce must be
        // 24-hex; "bad" is not -> the whole box (and entry) is dropped. The
        // real private entry's mutual_box (validEntryAcceptsGoodComment...)
        // still passes, so this proves the shape check is real, not a reject-all.
        val e = privateEntry().toMutableMap()
        e["mutual_box"] = listOf(mapOf(
            "eph_pub" to "aa".repeat(32), "nonce" to "bad", "ct" to "cc"))
        assertFalse(KotlinResponses.validEntry(e))
    }

    // ---- responseSigPayload (5 fields, PyFloat created_at, canonical) ----

    @Test fun responseSigPayloadByteMatchesHearth() {
        // Verified against hearth _response_sig_payload (venv canonical()):
        // {"body":...,"created_at":1784568399.425043,"responder":...,"rkind":...,"target":...}
        val expected =
            "{\"body\":\"nice post!\",\"created_at\":1784568399.425043," +
            "\"responder\":\"15a2d12336536699d59d1cd355f2162031b5713481c520b29f7f08b7b4e556ee\"," +
            "\"rkind\":\"comment\"," +
            "\"target\":\"d722d9a309cb43e145dfa7143b8bb54f1dce76816f6ea512f2474cc2bc51b32a\"}"
        val got = KotlinResponses.responseSigPayload(
            target(), "comment", "nice post!", 1784568399.425043, publicIdentity())
        assertEquals(expected, got.toString(Charsets.US_ASCII))
    }

    // ---- THE SECURITY CORE ----

    @Test fun publicEntryValidSigAndDeviceBoundResolvesName() {
        val (display, color) = KotlinResponses.resolveDisplay(
            publicEntry(), target(), emptyMap(), { _, _ -> true })
        assertEquals("friend-15a2d123", display)   // no profile name stored -> friend- prefix
        assertNull(color)                          // resolved -> no alias color
    }

    @Test fun publicEntryUsesProfileNameWhenKnown() {
        val (display, color) = KotlinResponses.resolveDisplay(
            publicEntry(), target(), mapOf(publicIdentity() to "Alice"), { _, _ -> true })
        assertEquals("Alice", display)
        assertNull(color)
    }

    @Test fun corruptedSigFallsToAlias() {
        val e = publicEntry().toMutableMap()
        val sig = e["responder_sig"] as String
        e["responder_sig"] = "0000" + sig.substring(4)   // still hex128, but no longer a valid sig
        val (display, color) = KotlinResponses.resolveDisplay(
            e, target(), emptyMap(), { _, _ -> true })
        assertEquals("Curious Crane", display)
        assertEquals(351, color)
    }

    @Test fun validSigButNotDeviceBoundFallsToAlias() {
        // Sig verifies, but the device is NOT enrolled to the identity ->
        // sig-alone is forgeable, so we MUST render the alias, never the name.
        val (display, color) = KotlinResponses.resolveDisplay(
            publicEntry(), target(), emptyMap(), { _, _ -> false })
        assertEquals("Curious Crane", display)
        assertEquals(351, color)
    }

    @Test fun wrongTargetFallsToAlias() {
        // The sig is bound to the real target; verifying against a different
        // target must fail -> alias.
        val (display, color) = KotlinResponses.resolveDisplay(
            publicEntry(), "ff".repeat(32), emptyMap(), { _, _ -> true })
        assertEquals("Curious Crane", display)
        assertEquals(351, color)
    }

    @Test fun privateEntryAlwaysAliasWhenNoEncPrivSupplied() {
        // public == false routes to the mutual_box de-anon path (Task 6,
        // see "THE MUTUAL_BOX DE-ANON BRANCH" below) -- but this call site
        // passes NO encPriv (defaults to ""), so [resolveViaMutualBox]
        // short-circuits before ever attempting KotlinSeal.tryOpenSlots
        // (mirrors a device with no enc key yet / an entitlement pass that
        // never threaded one through) -> alias, same observable outcome as
        // pre-Task-6. privateEntry()'s box is a REAL hearth-sealed vector
        // (dmcrypt_vectors.json case "responses"), not a synthetic one.
        val (display, color) = KotlinResponses.resolveDisplay(
            privateEntry(), target(), emptyMap(), { _, _ -> true })
        assertEquals("Swift Seal", display)
        assertEquals(88, color)
    }

    // ---- aggregate ----

    private fun reaction(body: String, seed: String): Map<String, Any?> = mapOf(
        "rkind" to "reaction", "body" to body, "created_at" to 1.0,
        "alias_seed" to seed, "public" to false, "responder_sig" to "00".repeat(64),
        "mutual_box" to null)

    @Test fun aggregateTalliesReactionsAndBuildsComments() {
        val entries = listOf(
            reaction("heart", "00".repeat(16)),
            reaction("heart", "11".repeat(16)),
            reaction("fire", "22".repeat(16)),
            publicEntry())   // the valid public comment
        val r = KotlinResponses.aggregate(entries, target(), emptyMap(), { _, _ -> true })
        assertEquals(2, r.reactions["heart"])
        assertEquals(1, r.reactions["fire"])
        assertEquals(1, r.comments.size)
        assertEquals("nice post!", r.comments[0].body)
        assertEquals("friend-15a2d123", r.comments[0].display)
        assertNull(r.comments[0].aliasColor)
    }

    @Test fun aggregateDropsInvalidEntries() {
        val bad = mapOf("rkind" to "sticker", "body" to "x")   // fails validEntry
        val r = KotlinResponses.aggregate(
            listOf(bad, reaction("wow", "33".repeat(16))), target(), emptyMap(), { _, _ -> true })
        assertEquals(1, r.reactions["wow"])
        assertTrue(r.comments.isEmpty())
        assertFalse(r.reactions.containsKey("x"))
    }

    // ---- vp1 Comment.responder/name (bug fix: coordinator review of Task 4) ----
    // hearth node.py:1562-1588: `resolved = identity is not None`; the comment
    // dict's "name" fallback is the BARE `identity[:8]` (node.py:1577,
    // `names.get(identity, identity[:8])`) -- NOT the "friend-"-prefixed
    // `display` value KotlinResponses computes for the native app's own
    // author line -- and `responder` is set on the dict ONLY `if resolved`
    // (node.py:1586-1588). These prove `aggregate` computes both correctly,
    // independent of the LocalApi marshal layer (LocalApiTest covers that
    // separately with hand-built Comment values).

    private fun aliasComment(body: String, seed: String): Map<String, Any?> = mapOf(
        "rkind" to "comment", "body" to body, "created_at" to 1.0,
        "alias_seed" to seed, "public" to false, "responder_sig" to "00".repeat(64),
        "mutual_box" to null)

    @Test fun aggregateSetsResponderAndBareIdentityNameFallbackForResolvedComment() {
        val r = KotlinResponses.aggregate(listOf(publicEntry()), target(), emptyMap(), { _, _ -> true })
        assertEquals(1, r.comments.size)
        val c = r.comments[0]
        assertFalse("a resolved comment must not be marked alias", c.alias)
        assertEquals("responder must be the resolved identity_pub", publicIdentity(), c.responder)
        assertEquals(
            "name must be hearth's bare identity[:8] fallback, no \"friend-\" prefix",
            publicIdentity().take(8), c.name)
        assertEquals(
            "display keeps its own \"friend-\"-prefixed value for the native app path, unchanged",
            "friend-" + publicIdentity().take(8), c.display)
    }

    @Test fun aggregateOmitsResponderAndNameForAliasComment() {
        val r = KotlinResponses.aggregate(
            listOf(aliasComment("hi there", "44".repeat(16))), target(), emptyMap(), { _, _ -> true })
        assertEquals(1, r.comments.size)
        val c = r.comments[0]
        assertTrue("a private (public=false) comment must render as an alias", c.alias)
        assertNull("an alias comment must never carry a responder", c.responder)
        assertNull("an alias comment must never carry a resolved name", c.name)
    }

    // ---- THE MUTUAL_BOX DE-ANON BRANCH (Task 6: thread tryOpenSlots into
    // attribution) ----
    //
    // hearth node.py:1541-1561 -- the trial-open branch _post_responses_view
    // runs for a non-public entry: trial-open mutual_box with this device's
    // enc_priv, parse the opened `{identity, device_pub, sig}`, then
    // RE-VERIFY using the SAME sig+device-bound gate the public path uses
    // (_sig_ok + _device_bound) before trusting the sealed identity. The
    // load-bearing precision point (easy to misread from the brief's
    // "the sealed sig re-verifies" phrasing): hearth's verify call is
    // `_sig_ok(cand_dev, e["responder_sig"], sig_payload)` -- it checks the
    // ENTRY's own outer `responder_sig` field against a payload built with
    // the BOX's claimed identity, NOT the box's own "sig" key (box["sig"]
    // is written by compose_response but never read back on this path,
    // node.py:1554 only reads box.get("identity")/box.get("device_pub")).
    // This is exactly what makes forgery fail: a forger who reuses a real
    // responder_sig but claims a different identity in the box has a sig
    // that was signed over a payload whose `responder` field was the REAL
    // identity, not the forged one -- reconstructing the payload with the
    // forged identity makes verification fail. KotlinResponses mirrors this
    // by design: [resolveViaMutualBox] never reads the opened box's "sig"
    // field at all, only "identity"/"device_pub".

    private fun genDeviceKey(): Pair<String, String> {   // (privHex, pubHex)
        val p = Ed25519PrivateKeyParameters(SecureRandom())
        return KotlinWire.toHex(p.encoded) to KotlinWire.toHex(p.generatePublicKey().encoded)
    }

    private fun genEncKey(): Pair<String, String> {   // (privHex, pubHex)
        val p = X25519PrivateKeyParameters(SecureRandom())
        return KotlinWire.toHex(p.encoded) to KotlinWire.toHex(p.generatePublicKey().encoded)
    }

    /** Builds a hearth-shaped PRIVATE response entry (public=false) whose
     *  mutual_box is a REAL KotlinSeal.sealSlots box (not a fixture) sealed
     *  to `sealToEncPubs`, carrying `{identity, device_pub, sig}` where
     *  `sig` is a REAL Ed25519 signature from `devicePriv` over
     *  responseSigPayload -- exactly ComposeResponse.compose's own
     *  construction (node.py:2434-2436 / ComposeResponse.kt:112-115). The
     *  outer `responder_sig` field is the SAME signature (honest-composer
     *  shape); tests that need to exercise the tamper/forgery gate corrupt
     *  it afterward via toMutableMap(). KotlinSeal's own Python-parity is
     *  already pinned by the hearth seal_vector (KotlinSealTest), so a
     *  Kotlin-sealed box is a legitimate de-anon-success fixture here. */
    private fun mutualBoxEntry(
        rkind: String, body: String, createdAt: Double, identity: String,
        devicePriv: String, devicePub: String, sealToEncPubs: List<String>,
        aliasSeed: String = "55".repeat(16),
    ): Map<String, Any?> {
        val sigPayload = KotlinResponses.responseSigPayload(target(), rkind, body, createdAt, identity)
        val responderSig = KotlinWire.signRaw(devicePriv, sigPayload)
        val box = KotlinSeal.sealSlots(
            KotlinWire.canonical(mapOf(
                "identity" to identity, "device_pub" to devicePub, "sig" to responderSig)),
            sealToEncPubs)
        return mapOf(
            "rkind" to rkind, "body" to body, "created_at" to createdAt,
            "alias_seed" to aliasSeed, "public" to false,
            "responder_sig" to responderSig, "mutual_box" to box)
    }

    @Test fun mutualBoxDeAnonResolvesProfileNameWhenBoxOpensAndVerifies() {
        val (devPriv, devPub) = genDeviceKey()
        val identity = "77".repeat(32)
        val (myEncPriv, myEncPub) = genEncKey()
        val e = mutualBoxEntry("comment", "hi there", 42.0, identity, devPriv, devPub, listOf(myEncPub))
        val (display, color) = KotlinResponses.resolveDisplay(
            e, target(), mapOf(identity to "Bob"), { _, _ -> true }, myEncPriv)
        assertEquals("de-anon success -> the sealed identity's real name, not the alias", "Bob", display)
        assertNull("resolved -> no alias color", color)
    }

    @Test fun mutualBoxDeAnonFallsBackToFriendPrefixWhenNameUnknown() {
        val (devPriv, devPub) = genDeviceKey()
        val identity = "88".repeat(32)
        val (myEncPriv, myEncPub) = genEncKey()
        val e = mutualBoxEntry("comment", "hi", 42.0, identity, devPriv, devPub, listOf(myEncPub))
        val (display, color) = KotlinResponses.resolveDisplay(
            e, target(), emptyMap(), { _, _ -> true }, myEncPriv)
        assertEquals("friend-" + identity.take(8), display)
        assertNull(color)
    }

    @Test fun mutualBoxSealedToStrangerStaysAlias() {
        // The REAL cross-lang asset: dmcrypt_vectors.json's "responses" case
        // entries[1] is a genuine hearth (Python) seal_slots box, sealed to
        // that fixture author's OWN friends -- which do NOT include this
        // case's enc_priv (verified: it cannot open this box). Proves the
        // stranger-box path against a real Python-sealed box, not just a
        // synthetic Kotlin-sealed one.
        val encPriv = responsesCase().getString("enc_priv")
        val (display, color) = KotlinResponses.resolveDisplay(
            privateEntry(), target(), emptyMap(), { _, _ -> true }, encPriv)
        assertEquals("Swift Seal", display)
        assertEquals(88, color)
    }

    @Test fun mutualBoxOpensButTamperedResponderSigStaysAlias() {
        // The box opens and names a real identity, but the OUTER entry's
        // responder_sig has been corrupted after the fact (simulating a
        // hostile/modified author who reuses a stranger's real sealed box
        // shape but forges the outer signature, or simple bit-rot) -- the
        // re-verify (against the CLAIMED identity from the box) must fail,
        // so the claimed name must NEVER surface, alias only.
        val (devPriv, devPub) = genDeviceKey()
        val identity = "99".repeat(32)
        val (myEncPriv, myEncPub) = genEncKey()
        val e = mutualBoxEntry("comment", "hi", 42.0, identity, devPriv, devPub, listOf(myEncPub))
            .toMutableMap()
        val sig = e["responder_sig"] as String
        e["responder_sig"] = "0000" + sig.substring(4)   // still hex128, no longer a valid sig
        val (display, color) = KotlinResponses.resolveDisplay(
            e, target(), mapOf(identity to "Eve"), { _, _ -> true }, myEncPriv)
        assertNotEquals("a tampered outer sig must never surface the claimed name", "Eve", display)
        val seed = e["alias_seed"] as String
        assertEquals(KotlinResponses.aliasName(seed), display)
        assertEquals(KotlinResponses.aliasColor(seed), color)
    }

    @Test fun mutualBoxDeAnonNotDeviceBoundStaysAlias() {
        // Symmetric with the public path's validSigButNotDeviceBoundFallsToAlias:
        // the box opens and the sig verifies against the claimed device, but
        // that device is not enrolled to the claimed identity -> sig alone
        // is forgeable (mint a fresh keypair, sign, claim any identity), so
        // this MUST fall to alias, mirroring hearth's `_device_bound` gate.
        val (devPriv, devPub) = genDeviceKey()
        val identity = "aa".repeat(32)
        val (myEncPriv, myEncPub) = genEncKey()
        val e = mutualBoxEntry("comment", "hi", 42.0, identity, devPriv, devPub, listOf(myEncPub))
        val (display, color) = KotlinResponses.resolveDisplay(
            e, target(), mapOf(identity to "Mallory"), { _, _ -> false }, myEncPriv)
        assertNotEquals("Mallory", display)
        val seed = e["alias_seed"] as String
        assertEquals(KotlinResponses.aliasName(seed), display)
        assertEquals(KotlinResponses.aliasColor(seed), color)
    }

    @Test fun mutualBoxDeAnonOwnIdentityDisplaysYou() {
        val (devPriv, devPub) = genDeviceKey()
        val own = "bb".repeat(32)
        val (myEncPriv, myEncPub) = genEncKey()
        val e = mutualBoxEntry("comment", "hi", 42.0, own, devPriv, devPub, listOf(myEncPub))
        val (display, color) = KotlinResponses.resolveDisplay(
            e, target(), emptyMap(), { _, _ -> true }, myEncPriv, own)
        assertEquals("you", display)
        assertNull(color)
    }

    @Test fun aggregateResolvesResponderAndNameViaMutualBoxDeAnon() {
        // The correctness fix this task also requires: aggregate's own
        // responder/name computation previously read str(e, "identity")
        // straight off the entry -- which is absent for a PRIVATE entry (no
        // cleartext identity field), so a mutual_box-resolved comment would
        // have display/color correctly resolved but responder/name wrongly
        // null. This proves aggregate threads the RESOLVED identity (from
        // the opened box) into Comment.responder/name too.
        val (devPriv, devPub) = genDeviceKey()
        val identity = "cc".repeat(32)
        val (myEncPriv, myEncPub) = genEncKey()
        val e = mutualBoxEntry("comment", "hi from the shadows", 42.0, identity, devPriv, devPub, listOf(myEncPub))
        val r = KotlinResponses.aggregate(
            listOf(e), target(), mapOf(identity to "Carol"), { _, _ -> true }, myEncPriv)
        assertEquals(1, r.comments.size)
        val c = r.comments[0]
        assertFalse("a de-anonymized comment must not be marked alias", c.alias)
        assertEquals("Carol", c.display)
        assertEquals("responder must be the identity resolved from the opened box", identity, c.responder)
        assertEquals("Carol", c.name)
    }

    @Test fun aggregateNameStaysBareFallbackNotYouEvenWhenDisplayIsYou() {
        // display and name are deliberately DIFFERENT fields (see the class
        // doc): display is the native app's own "you"/"friend-"-prefixed
        // value, name is hearth's OWN API-parity bare identity[:8] fallback
        // (node.py:1577 `names.get(identity, identity[:8])`, which has no
        // "you" special case at all) -- the own-identity override must only
        // ever touch display, never name.
        val (devPriv, devPub) = genDeviceKey()
        val own = "dd".repeat(32)
        val (myEncPriv, myEncPub) = genEncKey()
        val e = mutualBoxEntry("comment", "hi", 42.0, own, devPriv, devPub, listOf(myEncPub))
        val r = KotlinResponses.aggregate(
            listOf(e), target(), emptyMap(), { _, _ -> true }, myEncPriv, own)
        assertEquals(1, r.comments.size)
        val c = r.comments[0]
        assertEquals("you", c.display)
        assertEquals(own.take(8), c.name)
        assertEquals(own, c.responder)
    }
}
