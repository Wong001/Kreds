package expo.modules.tormanager

import org.json.JSONArray

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
        val storyRefMediaHash: String?)

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
        return Decrypted(m.msgId, m.kind, author, text, createdAt, blobs, thumbs, media, poster, storyRefMediaHash) to key
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
