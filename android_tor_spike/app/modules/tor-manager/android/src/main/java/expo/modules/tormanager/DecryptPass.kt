package expo.modules.tormanager

import org.json.JSONArray
import org.json.JSONObject

/** Task 5 (B.2), entitlement rule generalized in B.2c (Task 2): a decrypt
 *  pass over every stored POST/DM message this device is ENTITLED to, via
 *  either the message's own inline wrap (`payload.wraps[phoneDevicePub]`)
 *  or a backfilled wrap_grant (hearth's `maintain_own_device_grants` for
 *  own-authored content, Task 2 B.2; `maintain_received_dm_grants` for
 *  received friend DMs, Task 1 B.2c) targeting the message -- the newest
 *  such grant wins if several exist. Decrypt-on-read: nothing here is
 *  re-persisted as durable plaintext, this is purely a render pass.
 *
 *  B.2's scope fence (own-authored content ONLY) is GONE as of B.2c --
 *  replaced by a per-message entitlement rule (see resolveWrap): a post's
 *  grant must be signed by the post's own author; a DM's grant must be
 *  signed by the DM's author OR, only when WE are the DM's recipient, by
 *  our own identity (hearth's recipient-signed backfill). Friend-authored
 *  content is therefore now readable, but ONLY via a grant signed by an
 *  entitled identity -- never via a grant from anyone else, however
 *  "recipient-shaped" that grant's target/wraps make it look.
 *
 *  Anything that fails at ANY step -- no wrap/grant for this device, a
 *  wrong-device or tampered wrap/body that fails AEAD auth, a malformed
 *  payload missing a needed field, a non-string `text` -- is SKIPPED, never
 *  thrown: one bad or foreign message must not blank the whole feed. Only
 *  `post`/`dm` kinds are considered; B.2/B.2c render no other kind
 *  (reactions/comments/stories/profile may decrypt but are not rendered
 *  here). */
object DecryptPass {
    data class Decrypted(
        val msgId: String, val kind: String, val author: String, val text: String, val createdAt: Double,
        // Task 4 (B.2d): blob/thumb HASH REFERENCES only, never the blob
        // bytes themselves -- those are fetched separately (SyncStore.
        // getBlob) and decrypted with the per-message content key surfaced
        // in Result.keys below. thumbs is List<String?> (not List<String>)
        // because hearth legitimately records a null entry for a photo
        // whose thumbnail generation failed (node.py's `thumbs.append(
        // None)`) -- position in the list still lines up with `blobs`.
        val blobs: List<String>, val thumbs: List<String?>,
        // media/poster (B.2d-2 Task 1): plaintext OUTER PAYLOAD envelope
        // fields, same disclosure class as thumbs above -- hearth's
        // make_post signs `"media": media, "poster": poster` straight into
        // the outer payload (never the encrypted body; validate_payload's
        // KIND_POST branch reads them the same way, messages.py:307-317).
        // media defaults to "photo" when absent (matching make_post's own
        // default param); poster is a hex64 blob-hash reference to a video
        // post's AVIF still, or null for a photo post.
        val media: String, val poster: String?,
        // storyRefMediaHash (B.2d-3 Task 3, gap fix): a DM-only, PLAINTEXT
        // OUTER PAYLOAD field -- hearth's make_dm signs an optional
        // `"story_ref": {"story_id", "media_hash"}` straight into the outer
        // payload (messages.py:138-156, `_valid_story_ref`), never inside
        // the encrypted body -- same disclosure class as media/poster
        // above. Only `media_hash` is surfaced here: it is the sole field
        // the story-reply chip's thumbnail fetch (getStoryImage) needs;
        // `story_id` has no UI consumer yet, so it is read only far enough
        // to shape-guard (see storyRefMediaHash() below), not exposed.
        // null for an ordinary DM, a DM whose story_ref fails the shape
        // guard, or any post (story_ref is DM-only by construction).
        val storyRefMediaHash: String?,
        // vp1 (additive): hearth's /api/feed shape carries these; the native
        // getFeed marshal reads Decrypted by name and ignores them, so adding
        // them is safe. identityPub = the message author's cert.identity_pub
        // (StoredMsg.identityPub) -- used for `mine` + `identity_pub`. scope/
        // expires_at/placement/codec ride in the plaintext OUTER payload
        // (messages.make_post signs them there). Defaults keep incidental
        // constructions (e.g. tests) compiling; decryptOne always sets them.
        val identityPub: String = "",
        val scope: String? = null,
        val expiresAt: Double? = null,
        val placement: String? = null,
        val codec: String? = null)

    /** run()'s result (Task 4, B.2d): the decrypted feed, newest-first
     *  (unchanged from the pre-Task-4 shape), alongside the per-message
     *  content KEY recovered while decrypting -- needed downstream to
     *  decrypt any blob a message references (KotlinBlobCrypt.decryptBlob
     *  takes the same content key `unwrapKey` already recovers here, not a
     *  fresh one). `keys` maps msgId -> contentKey ONLY for messages that
     *  actually carry blobs (`decrypted.blobs` non-empty) -- a blob-less
     *  message's key would never be used for anything, so it is
     *  deliberately omitted rather than surfacing key material nobody asked
     *  for. `keys` is a plain lookup table, not feed-ordered. */
    data class Result(val feed: List<Decrypted>, val keys: Map<String, ByteArray>)

    fun run(store: SyncStore, phoneDevicePub: String, encPrivHex: String, ownIdentityPub: String): Result {
        // Read once per run(), not once per message: profileNames() does a
        // full scan over stored profile messages, same shape as
        // allMessages() itself -- looking it up per-decrypted-message would
        // make this pass O(messages x profiles) for no benefit, since the
        // name map doesn't change mid-run.
        val profileNames = store.profileNames()
        val out = mutableListOf<Decrypted>()
        val keys = mutableMapOf<String, ByteArray>()
        for (m in store.allMessages()) {
            if (m.kind != "post" && m.kind != "dm") continue
            val (decrypted, key) =
                decryptOne(store, m, phoneDevicePub, encPrivHex, ownIdentityPub, profileNames) ?: continue
            out.add(decrypted)
            if (decrypted.blobs.isNotEmpty()) keys[decrypted.msgId] = key
        }
        // Newest-first: the sensible feed order (most recent content on
        // top). sortedByDescending is a stable sort, so messages sharing a
        // createdAt keep allMessages()' relative order rather than an
        // arbitrary shuffle.
        return Result(out.sortedByDescending { it.createdAt }, keys)
    }

    /** B.2d-4 Task 3: a target post's msgId -> its aggregated engagement
     *  view (`KotlinResponses.Responses`), one entry per post this device
     *  knows of that has a valid, decryptable KIND_RESPONSES record signed
     *  by that SAME post's own author.
     *
     *  Entitlement (the part `resolveWrap`'s existing per-kind sets do NOT
     *  cover): hearth's own reference (`store.responses_record(target,
     *  author_identity)`, always invoked scoped to the target post's known
     *  author -- see node.py's `_post_responses_view`/`feed()`/
     *  `delete_post`) never trusts a KIND_RESPONSES record for a purpose
     *  other than "the record this post's OWN author most recently
     *  published for it". `resolveWrap`'s per-kind `accepted` set answers a
     *  DIFFERENT question -- who may hand this device a content-key WRAP
     *  for a given responses message (its own signer, via the `else`
     *  branch) -- not whether that message's `target` is one THIS device
     *  should trust as authoritative for that target. Without an explicit
     *  check here, a known-but-hostile identity (any mutual friend, same
     *  threat model as wrapGrantsFor's own doc above) could sign a
     *  KIND_RESPONSES claiming `target = <someone else's real post>`, wrap
     *  it (self-consistently, with their OWN real device key, so it
     *  decrypts "successfully") to this device, and inject fabricated
     *  aliased reactions/comments onto a post they do not own. Restricting
     *  candidates to `postAuthorByMsgId[target] == m.identityPub` closes
     *  that off, matching hearth's own scoping exactly. `postAuthorByMsgId`
     *  is built from this store's own stored "post" messages' cert-proven
     *  `identityPub` (StoredMsg.identityPub) -- no decrypt needed to know
     *  who authored a post, same as resolveWrap's own accepted-set logic.
     *
     *  Latest-wins per (author, target): by `created_at` ONLY -- unlike
     *  `wrapGrantsFor` (which sorts on `(created_at, seq)` on the STORE
     *  side, where `seq` is still attached to the underlying SignedMessage
     *  it reads directly), `StoredMsg` -- what `allMessages()` hands this
     *  pass -- carries no `seq` field, and adding one would mean touching
     *  SyncStore.kt, out of this task's scope. A same-`created_at` collision
     *  between two republishes for one target is the only case this
     *  misorders, no more likely here than anywhere else `created_at` alone
     *  already orders things (DecryptPass.run's own feed sort above).
     *
     *  Fail-closed throughout, per-target: no stored post for `target`,
     *  wrong author, no wrap, failed unwrap/decrypt, or a missing/malformed
     *  `entries` list -- ALL degrade to "this post has no responses entry
     *  in the returned map", never a crash and never a fabricated
     *  attribution. One bad or hostile record must not affect any other
     *  target's entry. */
    fun responsesPass(
        store: SyncStore, phoneDevicePub: String, encPrivHex: String, ownIdentityPub: String,
    ): Map<String, KotlinResponses.Responses> {
        val profileNames = store.profileNames()
        val deviceBound: (String, String) -> Boolean =
            { id, dev -> store.deviceViews(id).let { it.isEmpty() || dev in it } }

        val all = store.allMessages()
        val postAuthorByMsgId = all.filter { it.kind == "post" }.associate { it.msgId to it.identityPub }

        // Finding 1 (final review): hearth's step-1 identity resolution
        // (node.py's `raw_by_created_at`), restricted per this port's scope
        // to responses THIS identity itself composed -- see
        // ownRawByCreatedAt's own doc.
        val ownRaw = ownRawByCreatedAt(all, phoneDevicePub, encPrivHex, ownIdentityPub)

        // Best (latest by created_at) KIND_RESPONSES StoredMsg per target,
        // restricted to records signed by that target's own post author --
        // see this function's doc above for why that restriction is
        // load-bearing, not optional.
        data class Candidate(val msg: StoredMsg, val createdAt: Double)
        val bestByTarget = linkedMapOf<String, Candidate>()
        for (m in all) {
            if (m.kind != "responses") continue
            val target = m.payload["target"] as? String ?: continue
            if (postAuthorByMsgId[target] != m.identityPub) continue
            val createdAt = (m.payload["created_at"] as? Number)?.toDouble() ?: continue
            val cur = bestByTarget[target]
            if (cur == null || createdAt > cur.createdAt) bestByTarget[target] = Candidate(m, createdAt)
        }

        val out = linkedMapOf<String, KotlinResponses.Responses>()
        for ((target, cand) in bestByTarget) {
            val m = cand.msg
            val aad = KotlinDmcrypt.responsesAad(m.identityPub, target, cand.createdAt)
            val wrap = resolveWrap(store, m, phoneDevicePub, ownIdentityPub) ?: continue
            val key = KotlinDmcrypt.unwrapKey(wrap, encPrivHex, aad) ?: continue
            val bodyNonce = m.payload["body_nonce"] as? String ?: continue
            val bodyCt = m.payload["body_ct"] as? String ?: continue
            val body = KotlinDmcrypt.decryptBody(key, bodyNonce, bodyCt, aad) ?: continue
            // Task 6 (read de-anon): thread this device's own encPriv +
            // ownIdentityPub into aggregate's mutual_box trial-open branch
            // (KotlinResponses.resolveViaMutualBox) -- both are already this
            // function's own parameters (encPrivHex is the same key used
            // above to unwrap this very record's content key), so no new
            // plumbing beyond this call site is needed. Finding 1 adds
            // ownRaw[target] (this target's own-raw createdAt->identity
            // map, or empty if this identity never responded to it) as
            // aggregate's step-1 rawByCreatedAt.
            out[target] = KotlinResponses.aggregate(
                entriesList(body["entries"]), target, profileNames, deviceBound, encPrivHex, ownIdentityPub,
                ownRaw[target] ?: emptyMap())
        }
        return out
    }

    /** Finding 1 (final review): hearth node.py's step-1 identity
     *  resolution (`raw_by_created_at`, node.py:1510-1521) -- restricted,
     *  per this task's documented scope, to responses THIS identity
     *  itself composed (see KotlinResponses' class doc: the mutual_box
     *  branch can never resolve the viewer's own identity, since
     *  compose_response's box excludes the responder's own devices from
     *  its audience -- this is therefore the ONLY path that can ever
     *  attribute a folded entry to `ownIdentityPub`, driving the retract
     *  UI / `my_reaction`).
     *
     *  Builds target -> (createdAt -> identity) from every stored
     *  kind="response" (KIND_RESPONSE, singular -- the raw per-response
     *  envelope ComposeResponse.compose writes, NOT the folded
     *  kind="responses" aggregate) message THIS identity authored
     *  (StoredMsg.identityPub == ownIdentityPub), decrypted via this
     *  device's own self-readable wrap (ComposeResponse.kt's "self-readable
     *  (retract UI)" wrap -- `wraps[phoneDevicePub]`) using the SAME
     *  response_aad every other KIND_RESPONSE consumer uses
     *  (KotlinDmcrypt.responseAad(ownIdentityPub, target, createdAt) --
     *  matches hearth's own `_content_key`'s KIND_RESPONSE branch,
     *  node.py:2818-2820, which derives the AAD from `msg.cert.
     *  identity_pub`, i.e. the responder itself, exactly `ownIdentityPub`
     *  here since this scan is already filtered to own-authored rows).
     *
     *  Only rkind "comment"/"reaction" populate the map -- mirrors
     *  node.py's own `ev["rkind"] in ("comment", "reaction")` filter: a
     *  "retract" raw response has no corresponding row in a folded
     *  record's `entries` list (retracting REMOVES an entry during the
     *  fold, it does not become one), so a retract's created_at could
     *  never usefully match anything here. The map KEY is the DECRYPTED
     *  BODY's `created_at` (matching node.py's `ev["created_at"]`, which
     *  also comes from the decrypted body, not the outer envelope) --
     *  these are the same value by construction (ComposeResponse.compose
     *  writes the identical `effectiveCreatedAt` to both places), but
     *  reading from the body is what actually mirrors hearth's own
     *  source of truth.
     *
     *  Fail-closed per response, same idiom as decryptOne/responsesPass:
     *  no self-wrap, failed unwrap, failed decrypt, or a malformed body --
     *  all degrade to "this response contributes nothing", never a crash,
     *  never a fabricated match. Trusting the resolved identity directly
     *  (no signature re-check) is deliberate and matches hearth: this
     *  device's own store only ever holds a "response" row after
     *  ingestMessage's own Verifier already proved cert.identity_pub/
     *  device_pub really signed it (SyncStore.ingestMessage /
     *  SignedMessage.verifyDeviceSignature) -- re-verifying here would be
     *  redundant, exactly hearth's own reasoning for why raw_by_
     *  created_at's step-1 needs no sig_ok/_device_bound gate the way
     *  steps 2/3 do. `device_pub` (hearth's raw_by_created_at also
     *  captures it per entry) is deliberately NOT threaded through: hearth
     *  itself never reads that half of the tuple again after step 1's
     *  assignment (only `identity` feeds `mine`/comment["name"] downstream
     *  in node.py), so carrying it here would be dead weight, not a
     *  simplification that loses anything hearth actually uses. */
    @Suppress("UNCHECKED_CAST")
    private fun ownRawByCreatedAt(
        all: List<StoredMsg>, phoneDevicePub: String, encPrivHex: String, ownIdentityPub: String,
    ): Map<String, Map<Double, String>> {
        val out = linkedMapOf<String, MutableMap<Double, String>>()
        for (m in all) {
            if (m.kind != "response" || m.identityPub != ownIdentityPub) continue
            val target = m.payload["target"] as? String ?: continue
            val outerCreatedAt = (m.payload["created_at"] as? Number)?.toDouble() ?: continue
            val aad = KotlinDmcrypt.responseAad(ownIdentityPub, target, outerCreatedAt)
            val wrap = (m.payload["wraps"] as? Map<*, *>)?.get(phoneDevicePub) as? Map<String, Any?> ?: continue
            val key = KotlinDmcrypt.unwrapKey(wrap, encPrivHex, aad) ?: continue
            val bodyNonce = m.payload["body_nonce"] as? String ?: continue
            val bodyCt = m.payload["body_ct"] as? String ?: continue
            val body = KotlinDmcrypt.decryptBody(key, bodyNonce, bodyCt, aad) ?: continue
            val rkind = body["rkind"] as? String ?: continue
            if (rkind != "comment" && rkind != "reaction") continue
            val bodyCreatedAt = (body["created_at"] as? Number)?.toDouble() ?: continue
            out.getOrPut(target) { linkedMapOf() }[bodyCreatedAt] = ownIdentityPub
        }
        return out
    }

    /** Author display-name resolution (B.2c Task 3): `profileNames`'s
     *  latest-stored-profile name for `identityPub`, if any. Own identity
     *  falls back to the literal "me" when no own profile is stored yet;
     *  any other identity falls back to "friend-" + the first 8 hex chars
     *  of its identity_pub -- both fallbacks are deliberately readable-but-
     *  distinguishable placeholders, never blank/null, so the feed always
     *  has SOMETHING to show in the author position. */
    private fun resolveAuthor(profileNames: Map<String, String>, identityPub: String, ownIdentityPub: String): String {
        profileNames[identityPub]?.let { return it }
        return if (identityPub == ownIdentityPub) "me" else "friend-" + identityPub.take(8)
    }

    /** Junk-guarded blob-hash-list read: `v` must be either a plain Kotlin
     *  List (the shape every OUTER payload value already has -- both store
     *  impls hand back real Kotlin List/Map, see SqliteSyncStore's
     *  jsonToMap/unwrapJson) OR a raw org.json JSONArray (the shape a
     *  DECRYPTED BODY value has -- KotlinDmcrypt.decryptBody only
     *  shallow-converts its top-level JSONObject into a Map, it does NOT
     *  recursively unwrap nested JSONArray/JSONObject values the way the
     *  stores' own payload readers do, and decryptBody is untouchable
     *  consumed API here, not something this pass can fix at the source).
     *  Only String elements survive (a wrong-type element is dropped, not
     *  fatal) -- same "keep what's valid, skip what isn't" idiom as
     *  SyncStore.missingBlobs' own blobs/thumbs reads. Anything else
     *  (missing key, neither shape) -> empty, never a crash. */
    private fun stringList(v: Any?): List<String> = when (v) {
        is List<*> -> v.filterIsInstance<String>()
        is JSONArray -> (0 until v.length()).mapNotNull { v.opt(it) as? String }
        else -> emptyList()
    }

    /** Same as stringList but position-preserving and per-element
     *  null-tolerant: a thumbs list legitimately carries a null entry for a
     *  photo whose thumbnail generation failed (hearth node.py's
     *  `thumbs.append(None)`), and dropping that entry would desync the
     *  list's alignment with `blobs` by index. A junk (non-null, non-
     *  String) element becomes null too -- same fail-closed spirit as
     *  stringList, just position-preserving. Handles both the List shape
     *  (outer payload) and the raw JSONArray shape (decrypted body) --
     *  see stringList's doc for why both are needed. */
    private fun nullableStringList(v: Any?): List<String?> = when (v) {
        is List<*> -> v.map { it as? String }
        is JSONArray -> (0 until v.length()).map { i -> if (v.isNull(i)) null else v.opt(i) as? String }
        else -> emptyList()
    }

    /** B.2d-4 Task 3: bridges a decrypted responses body's `entries` field
     *  into the `List<Map<String, Any?>>` shape `KotlinResponses.aggregate`
     *  expects. Same dual-shape reasoning as `stringList`/
     *  `nullableStringList` above -- `KotlinDmcrypt.decryptBody` only
     *  shallow-converts its TOP-level JSONObject (see those functions' own
     *  doc), so `entries` itself arrives as a raw `org.json.JSONArray` of
     *  `JSONObject`s in production, never a Kotlin List of Maps; a caller-
     *  built List<Map<...>> (e.g. a test) is accepted too. Each element is
     *  shallow-converted ONE level (mirroring KotlinDmcrypt.decryptBody's
     *  own top-level conversion, and KotlinResponsesTest's `map()` helper)
     *  -- nested values (e.g. a `mutual_box` slot list) stay raw org.json,
     *  which is exactly the shape KotlinResponses' own slot readers (
     *  `slotStr`) already handle. A non-object element, or `entries` being
     *  missing/wrong-shaped entirely, degrades to an empty/partial list --
     *  never a crash -- and `KotlinResponses.validEntry` drops anything
     *  malformed that still made it through as a Map. */
    private fun entriesList(v: Any?): List<Map<String, Any?>> = when (v) {
        is JSONArray -> (0 until v.length()).mapNotNull { i ->
            (v.opt(i) as? JSONObject)?.let { o -> o.keys().asSequence().associateWith { k -> o.get(k) } }
        }
        is List<*> -> v.filterIsInstance<Map<String, Any?>>()
        else -> emptyList()
    }

    /** Decrypts one stored post/dm message, returning its Decrypted view
     *  alongside the CONTENT KEY recovered along the way (Task 4, B.2d) --
     *  run() surfaces that key in Result.keys for blob-carrying messages
     *  only; see Result's own doc for why. */
    private fun decryptOne(
        store: SyncStore, m: StoredMsg, phoneDevicePub: String, encPrivHex: String, ownIdentityPub: String,
        profileNames: Map<String, String>,
    ): Pair<Decrypted, ByteArray>? {
        val p = m.payload
        val createdAt = (p["created_at"] as? Number)?.toDouble() ?: return null
        val aad = when (m.kind) {
            "post" -> {
                val scope = p["scope"] as? String ?: return null
                KotlinDmcrypt.postAad(m.identityPub, scope, createdAt)
            }
            "dm" -> {
                val to = p["to"] as? String ?: return null
                KotlinDmcrypt.dmAad(m.identityPub, to, createdAt)
            }
            else -> return null   // unreachable: run() already filters to post/dm
        }
        val wrap = resolveWrap(store, m, phoneDevicePub, ownIdentityPub) ?: return null
        val key = KotlinDmcrypt.unwrapKey(wrap, encPrivHex, aad) ?: return null
        val bodyNonce = p["body_nonce"] as? String ?: return null
        val bodyCt = p["body_ct"] as? String ?: return null
        val body = KotlinDmcrypt.decryptBody(key, bodyNonce, bodyCt, aad) ?: return null
        val text = body["text"] as? String ?: return null
        val author = resolveAuthor(profileNames, m.identityPub, ownIdentityPub)
        // blobs/thumbs (Task 4, B.2d): read from the DECRYPTED BODY --
        // the content the key actually protects, not the outer plaintext
        // payload -- falling back to the outer payload only when the body
        // doesn't carry the field at all. Verified against hearth's own
        // reference decrypt (node.py's _decrypt_post_row/dm_thread) and
        // compose (node.py compose_post/compose_dm): the encrypted body is
        // always built as `{"text": text, "blobs": refs}` -- "blobs" IS in
        // the body in practice, so the body branch is the one actually
        // taken -- but "thumbs" is NEVER put in the body by hearth; it
        // rides in the outer SIGNED payload only (make_post's `thumbs`
        // param, alongside poster/codec -- same "plaintext envelope
        // metadata" disclosure class as make_dm's story_ref), and DMs never
        // carry thumbs at all (make_dm has no thumbs parameter). So thumbs
        // always falls through to the outer payload here -- confirmed
        // present-day hearth behavior, not merely a defensive hedge.
        val blobs = if (body.containsKey("blobs")) stringList(body["blobs"]) else stringList(p["blobs"])
        val thumbs = if (body.containsKey("thumbs")) nullableStringList(body["thumbs"]) else nullableStringList(p["thumbs"])
        // media/poster (B.2d-2 Task 1): OUTER payload only, never the
        // decrypted body -- see Decrypted.media's doc above for why.
        val media = (p["media"] as? String) ?: "photo"
        val poster = p["poster"] as? String
        // storyRefMediaHash (B.2d-3 Task 3, gap fix): DM-only, OUTER payload
        // only -- see Decrypted.storyRefMediaHash's doc above. Gated on
        // m.kind == "dm" even though storyRefMediaHash() below would return
        // null for any non-Map value anyway (a post payload never carries
        // this key) -- the explicit gate documents the DM-only contract at
        // the call site rather than leaving it implicit in the helper.
        val storyRefMediaHash = if (m.kind == "dm") storyRefMediaHash(p["story_ref"]) else null
        val scopeField = p["scope"] as? String
        val expiresAt = (p["expires_at"] as? Number)?.toDouble()
        val placement = p["placement"] as? String
        val codec = p["codec"] as? String
        return Decrypted(
            m.msgId, m.kind, author, text, createdAt, blobs, thumbs, media, poster, storyRefMediaHash,
            m.identityPub, scopeField, expiresAt, placement, codec) to key
    }

    /** Shape-guards a DM's optional outer-payload `story_ref` value (see
     *  Decrypted.storyRefMediaHash's doc) and, if valid, returns its
     *  `media_hash`. Genuinely mirrors hearth's own `_valid_story_ref`
     *  (messages.py:241-255) now: `story_id` must be a present, non-empty
     *  string (checked but not returned -- see the field's doc for why it
     *  has no consumer yet); `media_hash` must additionally pass the same
     *  hex64 shape hearth's `_is_hex64` enforces (exactly 64 lowercase hex
     *  chars) -- not just "non-empty" -- since media_hash is the literal
     *  blob-store lookup key this value feeds into (getStoryImage), a
     *  tighter fail-closed guard here is real input validation for that
     *  fetch, not merely cosmetic parity. Any shape failure -> null, same
     *  fail-closed idiom as stringList/nullableStringList above (malformed
     *  or missing degrades to "no story_ref", never a crash). */
    private fun storyRefMediaHash(v: Any?): String? {
        val ref = v as? Map<*, *> ?: return null
        val storyId = ref["story_id"] as? String
        val mediaHash = ref["media_hash"] as? String
        if (storyId.isNullOrEmpty() || !isHex64(mediaHash)) return null
        return mediaHash
    }

    /** Mirrors hearth's `_is_hex64` (messages.py): exactly 64 lowercase
     *  hex characters. Used to validate `media_hash` above -- a real blob-
     *  store lookup key, not just an opaque display string, so the shape
     *  check is load-bearing input validation, not decoration. */
    private fun isHex64(s: String?): Boolean =
        s != null && s.length == 64 && s.all { it in "0123456789abcdef" }

    /** Content-key source, in priority order: (1) the message's own inline
     *  wrap -- new content auto-wraps to the phone once its enckey is known
     *  (hearth's `_scope_device_pubs`), no grant needed, and no entitlement
     *  check applies here either: the wrap lives inside the message's OWN
     *  payload, already covered by that message's own device signature
     *  (verified at ingest), so an inline wrap to this device is authorized
     *  by construction -- (2) a backfilled wrap_grant targeting this
     *  message, signed by an identity in the ENTITLEMENT SET for this
     *  message (see below) -- wrapGrantsFor(msgId, acceptedSigners) already
     *  filters to that set (mirroring hearth's store.wrap_grants(target,
     *  author), generalized; see the SyncStore interface doc for why that
     *  filtering is load-bearing, not optional) and returns them oldest-to-
     *  newest, so folding left-to-right and keeping the LAST match yields
     *  the newest grant, matching hearth's own per-device latest-wins
     *  semantics (store.wrap_grants' `out.update(...)` fold).
     *
     *  Entitlement set (B.2c): posts trust a grant from the post's own
     *  author only (unchanged rule from B.2 -- friend authors are now
     *  allowed to BE that author, since the outer own-author-only filter is
     *  gone). DMs trust the author OR the recipient (our own identity) --
     *  but recipient-trust applies ONLY when we ARE that DM's recipient
     *  (`payload["to"] == ownIdentityPub`); a hostile "recipient-signed"
     *  grant from any other identity, targeting a DM not addressed to us,
     *  must never be trusted just because its shape looks like a recipient
     *  backfill grant. */
    @Suppress("UNCHECKED_CAST")
    private fun resolveWrap(store: SyncStore, m: StoredMsg, phoneDevicePub: String, ownIdentityPub: String): Map<String, Any?>? {
        (m.payload["wraps"] as? Map<*, *>)?.get(phoneDevicePub)?.let {
            return it as? Map<String, Any?>
        }
        // posts: grants trusted from the author only (unchanged rule, friend
        // authors now allowed). DMs: author OR the recipient (own identity), and
        // recipient-trust applies only when WE are the DM's recipient -- a
        // hostile "recipient-signed" grant from anyone else stays untrusted.
        val to = m.payload["to"] as? String
        val accepted: Set<String> = when {
            m.kind == "post" -> setOf(m.identityPub)
            m.kind == "dm" && to == ownIdentityPub -> setOf(m.identityPub, ownIdentityPub)
            else -> setOf(m.identityPub)
        }
        val grants = store.wrapGrantsFor(m.msgId, accepted)
        var newest: Map<String, Any?>? = null
        for (wraps in grants) {
            (wraps[phoneDevicePub] as? Map<String, Any?>)?.let { newest = it }
        }
        return newest
    }
}
