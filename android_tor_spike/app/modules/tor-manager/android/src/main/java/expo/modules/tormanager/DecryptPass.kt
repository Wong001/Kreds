package expo.modules.tormanager

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
    data class Decrypted(val msgId: String, val kind: String, val text: String, val createdAt: Double)

    fun run(store: SyncStore, phoneDevicePub: String, encPrivHex: String, ownIdentityPub: String): List<Decrypted> {
        val out = mutableListOf<Decrypted>()
        for (m in store.allMessages()) {
            if (m.kind != "post" && m.kind != "dm") continue
            decryptOne(store, m, phoneDevicePub, encPrivHex, ownIdentityPub)?.let { out.add(it) }
        }
        // Newest-first: the sensible feed order (most recent content on
        // top). sortedByDescending is a stable sort, so messages sharing a
        // createdAt keep allMessages()' relative order rather than an
        // arbitrary shuffle.
        return out.sortedByDescending { it.createdAt }
    }

    private fun decryptOne(
        store: SyncStore, m: StoredMsg, phoneDevicePub: String, encPrivHex: String, ownIdentityPub: String
    ): Decrypted? {
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
        return Decrypted(m.msgId, m.kind, text, createdAt)
    }

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
