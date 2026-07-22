package expo.modules.tormanager

import org.json.JSONArray
import org.json.JSONObject

/** B.2d-4 Task 2 -- the SECURITY CORE of the reactions+comments slice.
 *
 *  Ports hearth's `_post_responses_view` verification (node.py) for a
 *  decrypted KIND_RESPONSES record's `entries`. Task 6 (read de-anon)
 *  added the `mutual_box`/seal_slots PRIVATE-resolution branch (node.py:
 *  1541-1561, see [resolveViaMutualBox]) on top of Task 2's original
 *  PUBLIC-only attribution; the author's own raw-response lookup
 *  (node.py's step 1, `raw_by_created_at` -- resolves ONLY the viewer's
 *  own entries, via messages this device's own store separately holds)
 *  remains out of scope, deferred to a future task. What remains is the
 *  part that decides, per entry, whether a comment/reaction may be
 *  attributed to its claimed identity or must render as an anonymous
 *  ALIAS.
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
 *  Task 6 extends the SAME two-part gate to a NON-PUBLIC (private) entry
 *  that carries a `mutual_box`: [resolveViaMutualBox] trial-opens the box
 *  with this device's `encPriv` (KotlinSeal.tryOpenSlots -- succeeds only
 *  for a mutual friend the sender's box was actually sealed to), parses
 *  the opened `{identity, device_pub, sig}`, then re-verifies with THE
 *  SAME sig+device-bound gate above -- but critically against the ENTRY's
 *  own outer `responder_sig` field, not the box's own "sig" key (hearth
 *  node.py:1554-1559 only ever reads `box.get("identity")`/
 *  `box.get("device_pub")`; box["sig"] is written by compose_response for
 *  shape symmetry but never read back here). Reusing the outer
 *  responder_sig, re-payloaded with the BOX's claimed identity, is what
 *  makes this fail-closed against forgery: that signature was produced by
 *  signing over a payload whose `responder` field was the responder's
 *  REAL identity, so re-deriving the payload with a DIFFERENT (forged)
 *  claimed identity makes the signature check fail. An unopened box, an
 *  opened-but-malformed payload, a failed sig, or a failed device-bound
 *  check all degrade to the alias -- exactly like the public path.
 *
 *  Byte-for-byte parity points with hearth:
 *   - `_valid_response_entry` (node.py:93-131) -> [validEntry], including
 *     its `_valid_mutual_box` shape check (node.py:62-90) -> [validMutualBox]
 *     -- the box's SHAPE is validated (so phone/desktop drop the same
 *     entries) whether or not it ends up opened.
 *   - `_response_sig_payload` (node.py) -> [responseSigPayload]: the 5
 *     fields `{target, rkind, body, created_at, responder}` canonicalized,
 *     with `created_at` wrapped in `KotlinWire.PyFloat` so it renders as a
 *     Python float (`1784568399.425043`, not `1784568399`), byte-identical
 *     to hearth's `canonical(...)`.
 *   - `web/app.js` `aliasName`/`aliasColor` -> [aliasName]/[aliasColor],
 *     same wordlists, same `parseInt(seed.slice(...),16) % n` derivation.
 *
 *  Android-specific (not a hearth divergence, an existing established
 *  simplification -- see DecryptPass's own single-encPriv threading):
 *  hearth's node.py loops `for priv in self.device.enc_privs()` (current
 *  key + retired keys, a key-rotation grace period). This port takes a
 *  single `encPriv` (the device's current key only, threaded from
 *  DecryptPass.responsesPass) -- no retired-key grace period on Android
 *  yet, matching every other encPriv consumer in this codebase
 *  (KotlinDmcrypt.unwrapKey call sites), not a new gap Task 6 introduces.
 *  Own-identity display: unlike hearth (which has no "you" special case
 *  for a comment's identity -- `_post_responses_view` never resolves a
 *  responder's OWN private entries via this trial-open branch at all,
 *  since compose_response's mutual box audience deliberately EXCLUDES the
 *  responder's own devices, node.py:2417-2420/1462-1467), this port adds
 *  an own-identity override to `display` ONLY (never to `name`, which
 *  stays hearth's own bare-identity[:8] API-parity fallback) -- see
 *  [resolve]'s doc.
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
        // name = hearth's OWN comment-name fallback (node.py:1577,
        // `names.get(identity, identity[:8])`) -- the BARE 8-char identity
        // prefix when unnamed, deliberately NOT `display`'s "friend-"-
        // prefixed value (that prefix is this class's own choice for the
        // native app's author line; the two must not be conflated). null
        // when aliased. responder = the resolved identity_pub, present only
        // when NOT aliased (mirrors node.py:1586-1588's `if resolved:
        // comment["responder"] = identity`) -- web/app.js:621 keys a
        // resolved comment's avatar color off `c.responder` via
        // identityColor(), so an alias comment must never carry it.
        val alias: Boolean = false, val aliasSeed: String = "", val name: String? = null,
        val responder: String? = null)

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

    /** Bridges one entry's `mutual_box` value into the `List<Map<String,
     *  String>>` shape `KotlinSeal.tryOpenSlots` expects. Mirrors
     *  [slotStr]'s dual-shape handling: a raw org.json JSONArray of
     *  JSONObject slots (a decrypted body's `mutual_box`, in production) or
     *  a plain Kotlin `List<Map<String,String>>` (a caller-built entry, as
     *  in tests, or [KotlinSeal.sealSlots]'s own return shape). A slot
     *  missing any of eph_pub/nonce/ct is dropped rather than passed
     *  through partially -- [validEntry]/[validMutualBox] already reject a
     *  malformed slot for any entry that reaches [aggregate], but
     *  [resolveDisplay] can be called directly (as several tests do)
     *  without that gate, so this stays defensive on its own. */
    private fun mutualBoxSlots(v: Any?): List<Map<String, String>> {
        val slots: List<Any?> = when (v) {
            is List<*> -> v
            is JSONArray -> (0 until v.length()).map { v.opt(it) }
            else -> return emptyList()
        }
        return slots.mapNotNull { s ->
            val ephPub = slotStr(s, "eph_pub") ?: return@mapNotNull null
            val nonce = slotStr(s, "nonce") ?: return@mapNotNull null
            val ct = slotStr(s, "ct") ?: return@mapNotNull null
            mapOf("eph_pub" to ephPub, "nonce" to nonce, "ct" to ct)
        }
    }

    /** Task 6 -- hearth node.py:1541-1561, the trial-open ("private"/
     *  mutual_box) identity-resolution branch: trial-open the entry's
     *  `mutual_box` with this device's `encPriv`
     *  ([KotlinSeal.tryOpenSlots]); on success, parse the opened canonical
     *  JSON `{identity, device_pub, sig}` (written by ComposeResponse.kt /
     *  hearth's compose_response, node.py:2434-2436); then re-verify with
     *  the SAME sig+device-bound gate [resolve]'s public branch uses --
     *  against the box's claimed `identity`/`device_pub`, but the ENTRY's
     *  own outer `responder_sig` field, never the box's own "sig" key (see
     *  this class's top-level doc for why that asymmetry is the actual
     *  security gate, not a simplification). Returns the verified
     *  identity, or null on ANY failure -- no mutual_box, empty/malformed
     *  slots, the box doesn't open with `encPriv`, the opened bytes aren't
     *  valid JSON / not an object, `identity`/`device_pub` aren't hex64,
     *  the sig doesn't verify, or the device isn't bound to the claimed
     *  identity. Every branch is a plain `return null`, matching hearth's
     *  own fail-closed shape (node.py wraps `json.loads` in
     *  try/except ValueError/UnicodeDecodeError; the JSONObject parse
     *  below is wrapped the same way for the same reason -- an opened
     *  payload from a hostile/buggy sender is untrusted bytes until
     *  proven otherwise). */
    private fun resolveViaMutualBox(
        e: Map<String, Any?>, target: String,
        deviceBound: (identity: String, devicePub: String) -> Boolean,
        encPriv: String,
    ): String? {
        if (encPriv.isEmpty()) return null
        val slots = mutualBoxSlots(e["mutual_box"])
        if (slots.isEmpty()) return null
        val opened = KotlinSeal.tryOpenSlots(slots, encPriv) ?: return null
        val box = try {
            JSONObject(String(opened, Charsets.UTF_8))
        } catch (ex: Exception) { return null }
        val candId = box.opt("identity") as? String ?: return null
        val candDev = box.opt("device_pub") as? String ?: return null
        if (!isHexN(candId, 64) || !isHexN(candDev, 64)) return null
        val rkind = str(e, "rkind") ?: return null
        val body = str(e, "body") ?: return null
        val createdAt = num(e, "created_at") ?: return null
        val sig = str(e, "responder_sig") ?: return null
        val payload = responseSigPayload(target, rkind, body, createdAt, candId)
        // BOTH conditions mandatory, same as the public path -- sig proves
        // the device signed it, device-binding proves the device is really
        // the claimed identity's.
        return if (KotlinWire.verifyRaw(candDev, sig, payload) && deviceBound(candId, candDev)) candId else null
    }

    /** Full identity resolution for one entry: `identity` is the verified
     *  responder (null when unresolved -- the alias case), `display`/
     *  `color` are [resolveDisplay]'s return shape. Internal core shared by
     *  [resolveDisplay] (display/color only) and [aggregate] (which also
     *  needs the resolved `identity` for Comment.responder/name -- see
     *  that call site's own comment for why str(e,"identity") alone is
     *  wrong for a mutual_box-resolved PRIVATE entry, which carries no
     *  cleartext `identity` field at all).
     *
     *  Own-identity display (Task 6): when the mutual_box branch resolves
     *  to `ownIdentityPub`, `display` becomes the literal "you" -- this is
     *  new Android-only UX texture, not a hearth behavior to mirror (see
     *  this class's top-level doc: hearth's own trial-open branch can
     *  never actually resolve the viewer's OWN identity, since
     *  compose_response's mutual box deliberately excludes the responder's
     *  own devices from its audience). Deliberately does NOT touch the
     *  public branch (unchanged, no own-identity special case there,
     *  preserving byte-identical behavior with pre-Task-6 `display`
     *  values) and deliberately does NOT touch `name` (computed separately
     *  in [aggregate] from the resolved `identity`, hearth's own bare-
     *  prefix fallback, no "you" case in node.py either). */
    private data class Resolved(val identity: String?, val display: String, val color: Int?)

    private fun resolve(
        e: Map<String, Any?>, target: String,
        profileNames: Map<String, String>,
        deviceBound: (identity: String, devicePub: String) -> Boolean,
        encPriv: String, ownIdentityPub: String,
    ): Resolved {
        val aliasSeed = str(e, "alias_seed") ?: ""
        fun alias() = Resolved(null, aliasName(aliasSeed), aliasColor(aliasSeed))

        val public = e["public"] as? Boolean ?: return alias()
        if (!public) {
            val identity = resolveViaMutualBox(e, target, deviceBound, encPriv) ?: return alias()
            val display = if (identity == ownIdentityPub) "you"
                          else (profileNames[identity] ?: ("friend-" + identity.take(8)))
            return Resolved(identity, display, null)
        }
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
        return Resolved(identity, profileNames[identity] ?: ("friend-" + identity.take(8)), null)
    }

    /** Resolve ONE entry to a display label. Returns `(name, null)` when
     *  the entry attributes to a real identity -- a PUBLIC entry whose
     *  responder signature verifies AND whose device is bound to the
     *  claimed identity, OR (Task 6) a non-public entry whose mutual_box
     *  opens with `encPriv` and whose opened identity clears the same
     *  sig+device-bound gate; otherwise `(alias, hue)`. Fail-closed: any
     *  missing/short-circuiting field, a failed verify, or a false
     *  `deviceBound` all fall through to the alias -- never a crash, never
     *  a mis-attribution. `encPriv`/`ownIdentityPub` default to "" (the
     *  mutual_box branch then never attempts to open anything, and the
     *  own-identity display override never matches) -- existing PUBLIC-
     *  entry call sites that omit them keep their pre-Task-6 behavior
     *  byte-identical. Callers pass entries that have already cleared
     *  [validEntry] (so `alias_seed` is valid hex32 for the alias
     *  helpers); [aggregate] enforces that. */
    fun resolveDisplay(
        e: Map<String, Any?>, target: String,
        profileNames: Map<String, String>,
        deviceBound: (identity: String, devicePub: String) -> Boolean,
        encPriv: String = "", ownIdentityPub: String = "",
    ): Pair<String, Int?> {
        val r = resolve(e, target, profileNames, deviceBound, encPriv, ownIdentityPub)
        return r.display to r.color
    }

    /** Aggregate a decrypted record's raw `entries` into a post's view:
     *  drop anything failing [validEntry], tally reactions by token, and
     *  resolve each comment's display via [resolve]. Entry order is
     *  preserved for comments; reaction insertion order is preserved in the
     *  tally map. `encPriv`/`ownIdentityPub` thread straight into [resolve]
     *  -- see [resolveDisplay]'s doc for their "" defaults' effect. */
    fun aggregate(
        entries: List<Map<String, Any?>>, target: String,
        profileNames: Map<String, String>,
        deviceBound: (String, String) -> Boolean,
        encPriv: String = "", ownIdentityPub: String = "",
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
                val resolved = resolve(e, target, profileNames, deviceBound, encPriv, ownIdentityPub)
                val isAlias = resolved.color != null   // a null aliasColor == verified real name
                // vp1 fields, hearth-parity (node.py:1562-1588): `responder` is the
                // RESOLVED identity_pub, present only when NOT alias. Task 6 fix:
                // this used to read str(e, "identity") straight off the entry,
                // which is correct for a PUBLIC entry (its cleartext "identity"
                // field IS the attributed identity by construction) but wrong for
                // a mutual_box-resolved PRIVATE entry -- a private entry carries
                // no cleartext "identity" field at all (validEntry only requires
                // it `if public`), so that read would silently come back null even
                // though `resolved` (and isAlias) correctly reflect a successful
                // de-anon. `resolved.identity` is right for BOTH paths. `name` is
                // hearth's OWN fallback (bare identity[:8]), independent of
                // `display`'s "friend-"/"you" native-app values.
                val responder = if (isAlias) null else resolved.identity
                val name = if (isAlias) null else (responder?.let { profileNames[it] ?: it.take(8) })
                val seed = str(e, "alias_seed") ?: ""
                comments.add(Comment(body, resolved.display, resolved.color, createdAt, isAlias, seed, name, responder))
            }
        }
        return Responses(reactions, comments)
    }
}
