package expo.modules.tormanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Outbound review wave, FIX 2: pure-JVM round-trip fidelity tests for
 *  `MsgJson` (extracted from `SqliteSyncStore`'s `serialize()`/`jsonSafe`
 *  write side and `jsonToMap()`/`unwrapJson` read side -- no Android/DB
 *  dependency, so this runs on a plain JVM with no Robolectric).
 *
 *  Two things are proven here:
 *
 *  1. `serialize()` -> `toMap()` preserves a locally-composed payload's
 *     shape: `created_at` (PyFloat-wrapped, as Compose.post builds it)
 *     reads back as a Number, explicit-null fields stay PRESENT (not
 *     dropped), at every nesting depth. This is the `jsonSafe` fix -- KEPT
 *     from the original pending-outbound queue work because it is still
 *     needed: without it, `DecryptPass` cannot read a composed post's
 *     `created_at` back out of `messages.msg_json`, so the post silently
 *     vanishes from the composer's OWN feed. This is independent of the
 *     pending-outbound queue's OWN fidelity fix (below) -- messages.msg_json
 *     is used for every stored message, not just queued ones.
 *
 *  2. The CANONICAL round trip (`KotlinWire.dumps` -> `MsgJson.toMap` ->
 *     `KotlinWire.dumps` again) reproduces byte-IDENTICAL output. This is
 *     the regression proof for FIX 1: the pending-outbound queue now stores
 *     `wire_json = KotlinWire.dumps(wireDict)` and reconstructs it for
 *     re-send via `MsgJson.toMap`, so THIS exact round trip is what
 *     `SqliteSyncStore.pendingOutbound()` performs in production. Before
 *     FIX 1, the queue went through `messages.msg_json` (the org.json
 *     `serialize()` path) instead, which reads an INTEGRAL Double like
 *     150.0 back as a bare Kotlin Int -- `KotlinWire.dumps` then renders
 *     "150", not "150.0", a canonical-byte mismatch the receiving node's
 *     device-signature check would reject. 150.0 is exactly that
 *     regression case, included below. */
class MsgJsonTest {
    private val idPub = "aa".repeat(32)
    private val devPub = "bb".repeat(32)

    // The magnitudes FIX 1/FIX 2 must all be byte-exact for: zero, the
    // small-integral regression case (150.0), a larger integral value,
    // a small non-integral value, and production-shaped magnitude
    // (System.currentTimeMillis()/1000.0 with sub-second precision).
    private val magnitudes = listOf(0.0, 150.0, 9999999.0, 100.5, 1752900000.5)

    private fun certFixture() = KotlinWire.CertDict(idPub, devPub, "d", 1752900000.0, "00")

    private fun payloadFor(createdAt: Double): Map<String, Any?> = mapOf(
        "kind" to "post", "created_at" to KotlinWire.PyFloat(createdAt),
        "expires_at" to null, "poster" to null, "codec" to null, "thumbs" to null,
        "wraps" to mapOf("devA" to mapOf("eph_pub" to "ab", "nonce" to null))
    )

    private fun msg(createdAt: Double) =
        SignedMessage(certFixture(), 5, payloadFor(createdAt), "sig-placeholder")

    @Test fun serializeToMapPreservesCreatedAtAsNumberAndNullsAtEveryDepth() {
        for (x in magnitudes) {
            val m = msg(x)
            val map = MsgJson.toMap(MsgJson.serialize(m))
            @Suppress("UNCHECKED_CAST")
            val payload = map["payload"] as Map<String, Any?>

            // created_at must read back as a Number (not the nested
            // {"value": x} object org.json's bean-introspection fallback
            // would otherwise produce for an unwrapped PyFloat) -- this is
            // exactly what DecryptPass needs (`payload["created_at"] as
            // Number`).
            val createdAt = payload["created_at"] as? Number
            assertTrue("created_at must be a Number for x=$x (was: ${payload["created_at"]})", createdAt != null)
            assertEquals("created_at.toDouble() must equal original for x=$x", x, createdAt!!.toDouble(), 0.0)

            // Explicit-null top-level fields: PRESENT as null, not dropped.
            for (k in listOf("expires_at", "poster", "codec", "thumbs")) {
                assertTrue("payload must still contain key '$k' for x=$x", payload.containsKey(k))
                assertNull("payload['$k'] must be null (not absent) for x=$x", payload[k])
            }

            // Nested null (inside wraps.devA.nonce): also preserved.
            @Suppress("UNCHECKED_CAST")
            val wraps = payload["wraps"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val devA = wraps["devA"] as Map<String, Any?>
            assertTrue("nested wraps.devA must still contain 'nonce' for x=$x", devA.containsKey("nonce"))
            assertNull("nested wraps.devA.nonce must be null (not absent) for x=$x", devA["nonce"])
            assertEquals("ab", devA["eph_pub"])
        }
    }

    @Test fun canonicalRoundTripByteExactForSmallMagnitudeCreatedAt() {
        // THE regression case: 150.0 is exactly the integral, small-
        // magnitude value that a msg_json-mediated re-send (pre-FIX-1)
        // would corrupt to "150". Reproducing it here at the MsgJson layer
        // proves the canonical round trip FIX 1's pending_outbound storage
        // actually uses survives it, independent of any store impl.
        val m = msg(150.0)
        val wireDict = m.toDict()
        val firstDump = KotlinWire.dumps(wireDict)
        assertTrue("sanity: canonical form must render created_at as \"150.0\", not \"150\"",
            firstDump.contains("\"created_at\":150.0"))

        val roundTripped = MsgJson.toMap(firstDump)
        val secondDump = KotlinWire.dumps(roundTripped)
        assertEquals("canonical dumps must round-trip byte-exact through MsgJson.toMap",
            firstDump, secondDump)
    }

    @Test fun canonicalRoundTripByteExactAcrossMagnitudes() {
        // Broader confidence beyond the single 150.0 regression case above --
        // every magnitude the pending queue could plausibly see, including
        // the non-integral production-shaped value.
        for (x in magnitudes) {
            val m = msg(x)
            val firstDump = KotlinWire.dumps(m.toDict())
            val secondDump = KotlinWire.dumps(MsgJson.toMap(firstDump))
            assertEquals("canonical round-trip must be byte-exact for x=$x", firstDump, secondDump)
        }
    }
}
