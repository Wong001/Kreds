package expo.modules.tormanager

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
            publicEntry(), target(), emptyMap()) { _, _ -> true }
        assertEquals("friend-15a2d123", display)   // no profile name stored -> friend- prefix
        assertNull(color)                          // resolved -> no alias color
    }

    @Test fun publicEntryUsesProfileNameWhenKnown() {
        val (display, color) = KotlinResponses.resolveDisplay(
            publicEntry(), target(), mapOf(publicIdentity() to "Alice")) { _, _ -> true }
        assertEquals("Alice", display)
        assertNull(color)
    }

    @Test fun corruptedSigFallsToAlias() {
        val e = publicEntry().toMutableMap()
        val sig = e["responder_sig"] as String
        e["responder_sig"] = "0000" + sig.substring(4)   // still hex128, but no longer a valid sig
        val (display, color) = KotlinResponses.resolveDisplay(
            e, target(), emptyMap()) { _, _ -> true }
        assertEquals("Curious Crane", display)
        assertEquals(351, color)
    }

    @Test fun validSigButNotDeviceBoundFallsToAlias() {
        // Sig verifies, but the device is NOT enrolled to the identity ->
        // sig-alone is forgeable, so we MUST render the alias, never the name.
        val (display, color) = KotlinResponses.resolveDisplay(
            publicEntry(), target(), emptyMap()) { _, _ -> false }
        assertEquals("Curious Crane", display)
        assertEquals(351, color)
    }

    @Test fun wrongTargetFallsToAlias() {
        // The sig is bound to the real target; verifying against a different
        // target must fail -> alias.
        val (display, color) = KotlinResponses.resolveDisplay(
            publicEntry(), "ff".repeat(32), emptyMap()) { _, _ -> true }
        assertEquals("Curious Crane", display)
        assertEquals(351, color)
    }

    @Test fun privateEntryAlwaysAlias() {
        // public == false -> never attributed, regardless of deviceBound.
        val (display, color) = KotlinResponses.resolveDisplay(
            privateEntry(), target(), emptyMap()) { _, _ -> true }
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
        val r = KotlinResponses.aggregate(entries, target(), emptyMap()) { _, _ -> true }
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
            listOf(bad, reaction("wow", "33".repeat(16))), target(), emptyMap()) { _, _ -> true }
        assertEquals(1, r.reactions["wow"])
        assertTrue(r.comments.isEmpty())
        assertFalse(r.reactions.containsKey("x"))
    }
}
