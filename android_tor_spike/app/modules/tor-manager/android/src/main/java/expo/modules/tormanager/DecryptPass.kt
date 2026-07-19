package expo.modules.tormanager

/** Task 5 (B.2): a decrypt pass over the store's own POST/DM messages, via
 *  either the message's own inline wrap (`payload.wraps[phoneDevicePub]`)
 *  or a backfilled wrap_grant (hearth's `maintain_own_device_grants`,
 *  Task 2) targeting the message -- the newest such grant wins if several
 *  exist. Decrypt-on-read: nothing here is re-persisted as durable
 *  plaintext, this is purely a render pass.
 *
 *  Anything that fails at ANY step -- no wrap/grant for this device, a
 *  wrong-device or tampered wrap/body that fails AEAD auth, a malformed
 *  payload missing a needed field, a non-string `text` -- is SKIPPED, never
 *  thrown: one bad or foreign message must not blank the whole feed. Only
 *  `post`/`dm` kinds are considered; B.2 renders no other kind (reactions/
 *  comments/stories/profile may decrypt but are not rendered here). */
object DecryptPass {
    data class Decrypted(val msgId: String, val kind: String, val text: String, val createdAt: Double)

    fun run(store: SyncStore, phoneDevicePub: String, encPrivHex: String): List<Decrypted> {
        val out = mutableListOf<Decrypted>()
        for (m in store.allMessages()) {
            if (m.kind != "post" && m.kind != "dm") continue
            decryptOne(store, m, phoneDevicePub, encPrivHex)?.let { out.add(it) }
        }
        // Newest-first: the sensible feed order (most recent content on
        // top). sortedByDescending is a stable sort, so messages sharing a
        // createdAt keep allMessages()' relative order rather than an
        // arbitrary shuffle.
        return out.sortedByDescending { it.createdAt }
    }

    private fun decryptOne(store: SyncStore, m: StoredMsg, phoneDevicePub: String, encPrivHex: String): Decrypted? {
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
        val wrap = resolveWrap(store, m, phoneDevicePub) ?: return null
        val key = KotlinDmcrypt.unwrapKey(wrap, encPrivHex, aad) ?: return null
        val bodyNonce = p["body_nonce"] as? String ?: return null
        val bodyCt = p["body_ct"] as? String ?: return null
        val body = KotlinDmcrypt.decryptBody(key, bodyNonce, bodyCt, aad) ?: return null
        val text = body["text"] as? String ?: return null
        return Decrypted(m.msgId, m.kind, text, createdAt)
    }

    /** Content-key source, in priority order: (1) the message's own inline
     *  wrap -- new content auto-wraps to the phone once its enckey is known
     *  (hearth's `_scope_device_pubs`), no grant needed; (2) a backfilled
     *  wrap_grant targeting this message -- wrapGrantsFor returns them
     *  oldest-to-newest, so folding left-to-right and keeping the LAST
     *  match yields the newest grant, matching hearth's own per-device
     *  latest-wins semantics (store.wrap_grants' `out.update(...)` fold). */
    @Suppress("UNCHECKED_CAST")
    private fun resolveWrap(store: SyncStore, m: StoredMsg, phoneDevicePub: String): Map<String, Any?>? {
        (m.payload["wraps"] as? Map<*, *>)?.get(phoneDevicePub)?.let {
            return it as? Map<String, Any?>
        }
        var newest: Map<String, Any?>? = null
        for (wraps in store.wrapGrantsFor(m.msgId)) {
            (wraps[phoneDevicePub] as? Map<String, Any?>)?.let { newest = it }
        }
        return newest
    }
}
