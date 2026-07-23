package expo.modules.tormanager

/** Kotlin port of hearth.identity.DefriendNotice (identity.py:152-178) --
 *  a self-authenticating notice signed by the AUTHOR (the identity doing
 *  the unfriending), not the target: "I am no longer friends with
 *  target_identity, purge what I've given them and forget them."
 *  body()/verify() byte-match the Python dataclass exactly via
 *  KotlinWire.canonical/verifyRaw -- the same canonical()-matching
 *  primitives RevocationCert.kt (Task 3) already reuses for its own
 *  self-authenticating cert, reused here rather than re-implemented, so
 *  this inherits that byte-fidelity guarantee instead of risking a
 *  second, subtly-divergent construction. phone-onion-reachability Task 4. */
data class DefriendNotice(
    val author_identity: String,
    val target_identity: String,
    val created_at: Double,
    val signature: String,
) {
    fun body(): ByteArray = KotlinWire.canonical(mapOf(
        "type" to "defriend", "protocol" to KotlinWire.PROTOCOL,
        "author_identity" to author_identity,
        "target_identity" to target_identity,
        "created_at" to KotlinWire.PyFloat(created_at),
    ))

    /** Self-authenticating: verifies the signature against
     *  `author_identity` (identity.py:167-168's `_sig_ok(self.author_identity,
     *  ...)`) -- the party doing the unfriending, NOT the target. This is
     *  what lets a defriend notice be trusted independent of which device
     *  relayed it over the wire: the author issued it, full stop. */
    fun verify(): Boolean = KotlinWire.verifyRaw(author_identity, signature, body())

    fun toDict(): Map<String, Any?> = mapOf(
        "author_identity" to author_identity, "target_identity" to target_identity,
        "created_at" to created_at, "signature" to signature,
    )

    companion object {
        fun fromDict(d: Map<String, Any?>): DefriendNotice = DefriendNotice(
            d["author_identity"] as String, d["target_identity"] as String,
            (d["created_at"] as Number).toDouble(), d["signature"] as String,
        )
    }
}

/** Mirrors hearth Node.apply_defriend_notice (node.py:1746-1780) -- the
 *  receiving-side retention rule, as a SUBSET (see the omissions list
 *  below). Gate order matches node.py EXACTLY:
 *
 *  1. `notice.target_identity == ownIdentity` -- else false. A notice not
 *     addressed to us is none of our business.
 *  2. `notice.author_identity != ownIdentity` -- else false (self-author
 *     guard, node.py:1754-1761's belt-and-braces check). A notice that
 *     both targets AND claims to be authored by us must never be allowed
 *     to purge our own identity's content -- this can only be a bug or a
 *     forgery (verify() would fail on a real forgery anyway, since it'd
 *     have to be signed by our own identity key, but this guards even
 *     that case ahead of the signature check).
 *  3. `notice.verify()` -- else false.
 *  4. `store.knownIdentities().contains(author)` -- else false. This is
 *     what makes re-delivery idempotent: once step 5 below removes the
 *     author, a re-delivered notice fails this gate and returns false,
 *     exactly like ingestRevocation's is_known gate (RevocationCert.kt).
 *
 *  5. `store.purgeAuthoredBy(author)` + `store.removeIdentity(author)` ->
 *     true.
 *
 *  A free/extension function rather than a `SyncStore` interface member,
 *  for the same reason `ingestRevocation` is one (RevocationCert.kt's doc
 *  comment): it needs no store-impl-specific state beyond already-public
 *  interface methods (`knownIdentities`, `purgeAuthoredBy`,
 *  `removeIdentity`), so implementing it ONCE here gives both
 *  `InMemorySyncStore` and `SqliteSyncStore` identical behavior for free.
 *  Takes `ownIdentity` as an explicit parameter (unlike `ingestRevocation`,
 *  which needs no notion of "self" at all) because gates 1 and 2 above are
 *  both defined relative to it, and the store itself has no dedicated
 *  "which known identity is mine" accessor.
 *
 *  Deliberately NOT full hearth parity -- phone-subset omissions,
 *  matching this task's brief:
 *
 *  1. No peer-table / device-views / disconnected-list cleanup. hearth's
 *     apply_defriend_notice additionally calls store.remove_peer_identity
 *     (stop dialing the ex-friend's address), store.remove_device_views
 *     (drop stale enrollment/revocation rows for them), and
 *     store.add_disconnected (surface them in a "recently disconnected"
 *     UI list) -- node.py:1770-1777. The phone has no peer table or
 *     device-views model at all (a pre-existing gap, same one
 *     RevocationCert.kt's doc already flags for ingestRevocation), and no
 *     disconnected-list UI concept yet. Purging content + dropping the
 *     identity from knownIdentities() is the security-critical part (stop
 *     serving them anything, stop trusting their signed content); the
 *     address-dialing and UI-list pieces are deferred, not fixed here.
 *
 *  2. No `notify()` call -- that is a hearth Node-level UI-refresh hook
 *     with no Kotlin equivalent at this layer; callers (KotlinSync) are
 *     wire-protocol code, not UI. */
fun SyncStore.applyDefriendNotice(notice: DefriendNotice, ownIdentity: String): Boolean {
    if (notice.target_identity != ownIdentity) return false
    if (notice.author_identity == ownIdentity) return false
    if (!notice.verify()) return false
    val author = notice.author_identity
    if (!knownIdentities().contains(author)) return false
    purgeAuthoredBy(author)
    removeIdentity(author)
    return true
}
