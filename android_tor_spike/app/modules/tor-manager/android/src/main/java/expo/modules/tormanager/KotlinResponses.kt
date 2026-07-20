package expo.modules.tormanager

import org.json.JSONArray
import org.json.JSONObject

/** B.2d-4 Task 2 -- the SECURITY CORE of the reactions+comments slice.
 *
 *  Ports hearth's `_post_responses_view` verification (node.py) for a
 *  decrypted KIND_RESPONSES record's `entries`, MINUS the deferred
 *  `mutual_box`/seal_slots PRIVATE-resolution branch and the author's
 *  own raw-response lookup (both out of scope for this view-only slice).
 *  What remains is exactly the part that decides, per entry, whether a
 *  PUBLIC comment/reaction may be attributed to its claimed identity or
 *  must render as an anonymous ALIAS.
 *
 *  THE SECURITY CORE (never weaken): a PUBLIC entry is attributed to its
 *  claimed `identity` ONLY when BOTH hold:
 *    1. `KotlinWire.verifyRaw(device_pub, responder_sig,
 *        responseSigPayload(target, rkind, body, created_at, identity))`
 *       -- the responder's DEVICE key really signed this exact entry
 *       (== hearth `_sig_ok`, identity.py:79), AND
 *    2. `deviceBound(identity, device_pub)` -- that device is an ENROLLED
 *       device of the identity (== hearth `_device_bound`: empty views ->
 *       permissive true; non-empty -> membership required; see
 *       SyncStore.deviceViews' doc).
 *  If EITHER fails -> the ALIAS. Sig-alone is forgeable: an attacker mints
 *  a fresh keypair, puts its public half in `device_pub`, and signs -- the
 *  sig verifies but proves nothing about WHOSE key it is. Device-binding is
 *  what catches that, so it is MANDATORY, not an optimization.
 *
 *  Byte-for-byte parity points with hearth:
 *   - `_valid_response_entry` (node.py:93-131) -> [validEntry], including
 *     its `_valid_mutual_box` shape check (node.py:62-90) -> [validMutualBox]
 *     -- the box's SHAPE is validated (so phone/desktop drop the same
 *     entries, and the box is well-formed before the deferred private/
 *     seal_slots branch ever opens it), but it is NEVER opened in this slice.
 *   - `_response_sig_payload` (node.py) -> [responseSigPayload]: the 5
 *     fields `{target, rkind, body, created_at, responder}` canonicalized,
 *     with `created_at` wrapped in `KotlinWire.PyFloat` so it renders as a
 *     Python float (`1784568399.425043`, not `1784568399`), byte-identical
 *     to hearth's `canonical(...)`.
 *   - `web/app.js` `aliasName`/`aliasColor` -> [aliasName]/[aliasColor],
 *     same wordlists, same `parseInt(seed.slice(...),16) % n` derivation.
 */
object KotlinResponses {
    /** A resolved comment for one feed row's engagement view. `display` is
     *  either the responder's real name (profile name, else "friend-<8hex>")
     *  when attribution SUCCEEDED, or an anonymous alias ("Adjective Animal")
     *  when it did not. `aliasColor` is the alias hue (0..359) when `display`
     *  is an alias, and null when `display` is a real name -- so a null color
     *  is itself the "this responder was verified" signal. */
    data class Comment(
        val body: String, val display: String, val aliasColor: Int?, val createdAt: Double,
        // vp1 (additive): let the /api/feed marshal reproduce hearth's comment
        // shape. alias == true when this comment rendered as an anonymous alias
        // (aliasColor != null is the "not a verified real name" signal, per
        // this class's own doc). aliasSeed is the entry's validated hex32 seed.
        // name = the resolved real display name, or null when aliased.
        val alias: Boolean = false, val aliasSeed: String = "", val name: String? = null)

    /** A post's aggregated engagement: reaction tally (token -> count) and
     *  the resolved comment list (entry order preserved). */
    data class Responses(val reactions: Map<String, Int>, val comments: List<Comment>)

    // hearth/messages.py: REACTION_TOKENS + MAX_COMMENT.
    private val REACTION_TOKENS = setOf("heart", "laugh", "wow", "sad", "up", "fire")
    private const val MAX_COMMENT = 500
    // hearth/node.py:59 -- ct hex-length cap for a sealed mutual_box slot.
    private const val MUTUAL_BOX_CT_HEX_MAX = 4096

    // web/app.js ALIAS_ADJECTIVES / ALIAS_ANIMALS -- kept in the SAME order
    // (the index derivation below is order-sensitive; a reorder changes every
    // alias). 16 entries each, so a single seed byte selects each word.
    private val ALIAS_ADJECTIVES = listOf(
        "Quiet", "Bright", "Gentle", "Bold", "Calm", "Swift", "Kind", "Curious",
        "Merry", "Steady", "Lucky", "Sunny", "Brave", "Soft", "Sharp", "Wandering")
    private val ALIAS_ANIMALS = listOf(
        "Fox", "Otter", "Heron", "Wren", "Lynx", "Hare", "Finch", "Badger",
        "Seal", "Crane", "Moth", "Robin", "Deer", "Owl", "Sparrow", "Marten")

    /** Mirrors hearth `_is_hexn`: exactly `n` lowercase hex chars. */
    private fun isHexN(s: String?, n: Int): Boolean =
        s != null && s.length == n && s.all { it in "0123456789abcdef" }

    // Junk-guarded field reads -- an entry Map's values may be plain Kotlin
    // scalars (built by a caller) or org.json-derived scalars (a decrypted
    // body's shallow-converted JSONObject); either way a wrong-typed or
    // missing field degrades to null, never a cast crash.
    private fun str(e: Map<String, Any?>, k: String): String? = e[k] as? String
    private fun num(e: Map<String, Any?>, k: String): Double? = (e[k] as? Number)?.toDouble()

    /** Read a string field from one mutual_box slot, which may be a plain
     *  Kotlin Map (caller-built) or a raw org.json JSONObject (a decrypted
     *  body is only shallow-converted, so its nested slots stay org.json --
     *  same dual-shape reason as DecryptPass.stringList). */
    private fun slotStr(slot: Any?, k: String): String? = when (slot) {
        is Map<*, *> -> slot[k] as? String
        is JSONObject -> slot.opt(k) as? String
        else -> null
    }

    /** Shape-check ONLY (never open) a mutual_box, mirroring hearth
     *  `_valid_mutual_box` (node.py:62-90). None (a public entry carries no
     *  box) is valid -- both Kotlin null and org.json's NULL sentinel (a
     *  shallow-converted body leaves a JSON null as `JSONObject.NULL`, not
     *  Kotlin null). Otherwise it must be a list (Kotlin List OR org.json
     *  JSONArray) of dicts, every slot well-shaped: `eph_pub` a 64-hex
     *  X25519 pub, `nonce` a 24-hex ChaCha20-Poly1305 nonce, `ct` a
     *  non-empty lowercase-hex string bounded by MUTUAL_BOX_CT_HEX_MAX. A
     *  single malformed slot fails the whole box (hearth's "a partially-
     *  hostile mutual_box is exactly the close-enough heuristic hostile
     *  input slips through"). Fail-closed: any wrong shape -> false. This
     *  slice never trial-opens the box (that private/seal_slots branch is
     *  deferred) -- but validating its shape here keeps phone/desktop drop
     *  decisions identical AND ensures the box is already well-formed the
     *  moment that deferred branch lands and does open it. */
    private fun validMutualBox(v: Any?): Boolean {
        if (v == null || v == JSONObject.NULL) return true
        val slots: List<Any?> = when (v) {
            is List<*> -> v
            is JSONArray -> (0 until v.length()).map { v.opt(it) }
            else -> return false
        }
        for (s in slots) {
            if (s !is Map<*, *> && s !is JSONObject) return false
            if (!isHexN(slotStr(s, "eph_pub"), 64)) return false
            if (!isHexN(slotStr(s, "nonce"), 24)) return false
            val ct = slotStr(s, "ct")
            if (ct == null || ct.isEmpty() || ct.length > MUTUAL_BOX_CT_HEX_MAX ||
                !ct.all { it in "0123456789abcdef" }) return false
        }
        return true
    }

    /** web/app.js `aliasName`: adjective from seed byte 0, animal from seed
     *  byte 1. Requires `seed` to be valid hex of length >= 4 (guaranteed by
     *  [validEntry], which every entry reaching [aggregate]/[resolveDisplay]
     *  has passed -- hex32). */
    fun aliasName(seed: String): String {
        val a = seed.substring(0, 2).toInt(16) % ALIAS_ADJECTIVES.size
        val b = seed.substring(2, 4).toInt(16) % ALIAS_ANIMALS.size
        return ALIAS_ADJECTIVES[a] + " " + ALIAS_ANIMALS[b]
    }

    /** web/app.js `aliasColor`: HSL hue only (the app applies fixed
     *  saturation/lightness `55% 45%` around it). `parseInt(seed.slice(0,6),
     *  16) % 360`; six hex chars max 0xFFFFFF fits an Int. */
    fun aliasColor(seed: String): Int =
        seed.substring(0, 6).toInt(16) % 360

    /** The exact canonical bytes hearth's `compose_response` signs and every
     *  viewer re-derives to reverify a folded entry (hearth
     *  `_response_sig_payload`). Five fields; `created_at` is wrapped in
     *  `KotlinWire.PyFloat` so it serializes as a Python float. Key ORDER
     *  here is irrelevant -- `KotlinWire.canonical` sorts keys by code point,
     *  matching Python's `json.dumps(sort_keys=True)`. */
    fun responseSigPayload(target: String, rkind: String, body: String, createdAt: Double, responder: String): ByteArray =
        KotlinWire.canonical(mapOf(
            "target" to target,
            "rkind" to rkind,
            "body" to body,
            "created_at" to KotlinWire.PyFloat(createdAt),
            "responder" to responder))

    /** Per-entry shape gate (hearth `_valid_response_entry`, minus the
     *  deferred `_valid_mutual_box` line). This is the ONLY thing standing
     *  between a hostile/modified author -- who hand-built a record instead
     *  of running the honest fold -- and every viewer that decrypts it, so
     *  it fails closed on every malformed field. */
    fun validEntry(e: Map<String, Any?>): Boolean {
        val rkind = str(e, "rkind")
        if (rkind != "comment" && rkind != "reaction") return false
        val body = str(e, "body") ?: return false
        // hearth counts code points (Python len); use codePointCount for
        // parity at the boundary rather than UTF-16 units.
        if (rkind == "comment") {
            val len = body.codePointCount(0, body.length)
            if (!(len in 1..MAX_COMMENT)) return false
        }
        if (rkind == "reaction" && body !in REACTION_TOKENS) return false
        if (num(e, "created_at") == null) return false
        if (!isHexN(str(e, "alias_seed"), 32)) return false
        val public = e["public"] as? Boolean ?: return false
        if (!isHexN(str(e, "responder_sig"), 128)) return false
        // Shape-check the mutual_box (never open it) at hearth's position --
        // node.py:126, after responder_sig, before the public identity/
        // device_pub check. Matching hearth's drop decision here keeps
        // phone/desktop parity (an entry hearth drops for a garbage box must
        // not render as an alias on the phone) and guarantees the box is
        // well-formed before the deferred private/seal_slots branch opens it.
        if (!validMutualBox(e["mutual_box"])) return false
        if (public && !(isHexN(str(e, "identity"), 64) && isHexN(str(e, "device_pub"), 64))) return false
        return true
    }

    /** Resolve ONE entry to a display label. Returns `(name, null)` when the
     *  entry is a PUBLIC one whose responder signature verifies AND whose
     *  device is bound to the claimed identity; otherwise `(alias, hue)`.
     *  Fail-closed: any missing/short-circuiting field, a failed verify, or a
     *  false `deviceBound` all fall through to the alias -- never a crash,
     *  never a mis-attribution. Callers pass entries that have already
     *  cleared [validEntry] (so `alias_seed` is valid hex32 for the alias
     *  helpers); [aggregate] enforces that. */
    fun resolveDisplay(
        e: Map<String, Any?>, target: String,
        profileNames: Map<String, String>,
        deviceBound: (identity: String, devicePub: String) -> Boolean,
    ): Pair<String, Int?> {
        val aliasSeed = str(e, "alias_seed") ?: ""
        fun alias(): Pair<String, Int?> = aliasName(aliasSeed) to aliasColor(aliasSeed)

        val public = e["public"] as? Boolean ?: return alias()
        if (!public) return alias()
        val identity = str(e, "identity") ?: return alias()
        val devicePub = str(e, "device_pub") ?: return alias()
        val rkind = str(e, "rkind") ?: return alias()
        val body = str(e, "body") ?: return alias()
        val createdAt = num(e, "created_at") ?: return alias()
        val sig = str(e, "responder_sig") ?: return alias()

        val payload = responseSigPayload(target, rkind, body, createdAt, identity)
        // BOTH conditions mandatory -- sig proves the device signed it,
        // device-binding proves the device is really the identity's.
        val attributed = KotlinWire.verifyRaw(devicePub, sig, payload) && deviceBound(identity, devicePub)
        if (!attributed) return alias()
        return (profileNames[identity] ?: ("friend-" + identity.take(8))) to null
    }

    /** Aggregate a decrypted record's raw `entries` into a post's view:
     *  drop anything failing [validEntry], tally reactions by token, and
     *  resolve each comment's display via [resolveDisplay]. Entry order is
     *  preserved for comments; reaction insertion order is preserved in the
     *  tally map. */
    fun aggregate(
        entries: List<Map<String, Any?>>, target: String,
        profileNames: Map<String, String>,
        deviceBound: (String, String) -> Boolean,
    ): Responses {
        val reactions = linkedMapOf<String, Int>()
        val comments = mutableListOf<Comment>()
        for (e in entries) {
            if (!validEntry(e)) continue
            val rkind = str(e, "rkind") ?: continue
            val body = str(e, "body") ?: continue
            val createdAt = num(e, "created_at") ?: continue
            if (rkind == "reaction") {
                reactions[body] = (reactions[body] ?: 0) + 1
            } else {
                val (display, color) = resolveDisplay(e, target, profileNames, deviceBound)
                val isAlias = color != null           // a null aliasColor == verified real name
                val name = if (isAlias) null else display
                val seed = str(e, "alias_seed") ?: ""
                comments.add(Comment(body, display, color, createdAt, isAlias, seed, name))
            }
        }
        return Responses(reactions, comments)
    }
}
