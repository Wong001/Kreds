package expo.modules.tormanager

import java.security.MessageDigest

data class SyncStats(val messages: Int, val blobs: Int, val identities: Int)

/** A stored message's fields DecryptPass (Task 5, B.2) needs: msgId + kind
 *  (both already columns/derived in both store impls) plus payload and
 *  identityPub -- the message's AUTHOR (cert.identity_pub). identityPub is
 *  required, not merely convenient: postAad/dmAad's `from` parameter is the
 *  author, and hearth's own reference (node.py's `_content_key`) reads it
 *  from `msg.cert.identity_pub`, NOT from any field inside the payload --
 *  a real post/dm payload carries no "from"/"author" key at all. */
data class StoredMsg(val msgId: String, val kind: String, val identityPub: String, val payload: Map<String, Any?>)

interface SyncStore {
    fun summary(): Map<String, Map<String, Map<String, Any>>>
    fun knownIdentities(): List<String>
    fun addIdentity(id: String)
    fun ingestMessage(m: SignedMessage): Boolean
    fun missingBlobs(): List<String>
    fun putBlob(hash: String, data: ByteArray): Boolean
    fun stats(): SyncStats
    /** This device's own X25519 enc keypair (encPrivHex, encPubHex), or null
     *  if none has been generated yet. See EncKeys.getOrCreate. */
    fun getEncKey(): Pair<String, String>?
    fun setEncKey(priv: String, pub: String)
    /** This device's next outbound message seq (Task 4, B.2): the phone
     *  tracks its own seq the same way hearth's DeviceKeys.sign_message does
     *  (identity.py:304-316) -- starts at 1 (seq starts at 0, incremented
     *  BEFORE first use, so the first-ever message is seq=1), and each call
     *  returns the seq to use for the NEXT outbound message while persisting
     *  the following value, so a second call never repeats a seq (a repeat
     *  would be rejected at the peer's seq-reuse gate -- hearth
     *  identity.py:577, Verifier.verify_message). */
    fun nextSeq(): Int
    /** The enc_pub (hex) most recently confirmed PUBLISHED to the home node
     *  (Task 7, B.2) -- i.e. included in an outbound `enckey` message during
     *  a sync that completed with `SyncResult.Ok` -- or null if this device
     *  has never successfully published one. Set only via
     *  `setPublishedEncPub`, after such a sync (see
     *  `TorManagerModule.syncNow` and `EncKeyPublishGuard`). This is
     *  DELIBERATELY separate from `getEncKey`/`setEncKey` (the keypair
     *  itself, Task 3): a device generates its enc keypair once at rest, but
     *  "have I told the node about THIS pub yet" is a fact about network
     *  history, not about the key material, and must survive independently
     *  (e.g. a crash between generating a key and successfully syncing it
     *  must leave this null so the next sync retries the push). */
    fun getPublishedEncPub(): String?
    /** Persists `pub` as the published marker (see `getPublishedEncPub`).
     *  Callers must only call this AFTER a sync that pushed `pub` as the
     *  outbound enckey has completed successfully -- never speculatively
     *  before the push is confirmed accepted. */
    fun setPublishedEncPub(pub: String)
    /** Every stored message (Task 5, B.2 DecryptPass): unfiltered by kind or
     *  author. NOTE (corrected -- a prior version of this comment claimed
     *  the store only ever holds own-identity content; that is FALSE):
     *  KotlinSync's HAVE phase adds every identity the node reports as
     *  `known` (the friend list) via `addIdentity`, and the node's
     *  messages_not_in then serves any message an entitled peer's identity
     *  is owed -- so this store CAN and does hold friend-authored messages
     *  (e.g. a friend-authored wrap_grant, see wrapGrantsFor's doc below).
     *  DecryptPass itself filters to kind post/dm; it does not (this task)
     *  additionally filter by author -- see wrapGrantsFor's doc for why
     *  that distinction matters for grants specifically. */
    fun allMessages(): List<StoredMsg>
    /** The `wraps` maps from every stored wrap_grant message whose `target`
     *  equals msgId AND whose SIGNING identity (the grant message's own
     *  cert.identity_pub) is a MEMBER OF acceptedSigners, ordered OLDEST-TO-
     *  NEWEST by (created_at, seq) -- mirrors hearth's `store.wrap_grants(
     *  target, author)` (store.py:773-790), generalized from a single
     *  author to a caller-chosen signer set (B.2c: DecryptPass's entitlement
     *  rule -- posts accept only the post's author; DMs additionally accept
     *  the DM's recipient, but ONLY when that recipient is our own identity,
     *  covering hearth's `maintain_received_dm_grants` recipient-signed
     *  backfill). Callers fold left-to-right over the (already filtered)
     *  result to prefer the newest grant for a given device (DecryptPass
     *  does this).
     *
     *  The signer filter is NOT optional (correcting an earlier version of
     *  this comment, which wrongly reasoned it could be skipped because
     *  "the store only holds own content"): B.1's sync does not admit only
     *  own-authored messages -- see allMessages' doc above. A hostile
     *  mutual friend, once known, can author `wrap_grant{target: <your own
     *  post>, wraps:{yourDevicePub: crafted}, created_at: <future>}` and
     *  have it synced into this same store. Without this filter, a newest-
     *  wins fold over UNFILTERED grants would prefer that crafted grant
     *  over your real (older) own-authored one; unwrapKey would "succeed"
     *  with the wrong key, decryptBody would then fail AEAD auth, and your
     *  own real post would silently vanish from the feed -- a fail-closed
     *  but real denial-of-render. Requiring `acceptedSigners` as a
     *  parameter (rather than a separate unfiltered accessor) is
     *  deliberate: it stops a caller from accidentally reintroducing this
     *  gap -- the caller must name every identity it trusts, explicitly,
     *  every time. */
    fun wrapGrantsFor(msgId: String, acceptedSigners: Set<String>): List<Map<String, Any?>>
}

/** Reference impl (JVM-testable, no Android). Also the shape the SQLite
 *  impl mirrors. */
class InMemorySyncStore : SyncStore {
    private val identities = linkedSetOf<String>()
    private val messages = linkedMapOf<String, SignedMessage>()     // msg_id -> msg
    private val seen = hashMapOf<Pair<String, String>, SeenSet>()   // (ipub,dpub) -> seen
    private val blobs = linkedMapOf<String, ByteArray>()            // hash -> data
    private var encKey: Pair<String, String>? = null                // (encPrivHex, encPubHex)
    private var seqCounter = 0                                      // next nextSeq() call returns seqCounter+1

    private fun sha(b: ByteArray) =
        KotlinWire.toHex(MessageDigest.getInstance("SHA-256").digest(b))

    override fun summary(): Map<String, Map<String, Map<String, Any>>> {
        val out = linkedMapOf<String, MutableMap<String, Map<String, Any>>>()
        for ((k, ss) in seen)
            out.getOrPut(k.first) { linkedMapOf() }[k.second] = ss.toJson()
        return out
    }

    override fun knownIdentities(): List<String> = identities.toList()
    override fun addIdentity(id: String) { identities.add(id) }

    override fun ingestMessage(m: SignedMessage): Boolean {
        // is_known gate (mirrors hearth Store.ingest_message's first check):
        // accept only from an identity we already know -- own identity is
        // seeded before sync, friends are added during HAVE. Do NOT
        // auto-register senders.
        if (m.cert.identity_pub !in identities) return false
        if (!m.verifyDeviceSignature()) return false
        val id = m.msgId()
        if (messages.containsKey(id)) return false            // already have this exact message
        // seq-reuse rejection -- SeenSet's whole purpose (D2 Ambush 2;
        // hearth Verifier.verify_message: `if not seen.add(seq): reject`).
        // A device reusing a seq with DIFFERENT content (different msg_id,
        // so past the dedup above) is rejected here.
        if (!seen.getOrPut(m.cert.identity_pub to m.cert.device_pub) { SeenSet() }.add(m.seq))
            return false
        messages[id] = m
        return true
    }

    /** Blob hashes referenced by stored POST/DM payloads, minus what we hold.
     *  Mirrors hearth.store.referenced_blobs for the KIND_POST/KIND_DM fields
     *  (blobs list + poster str + thumbs list), junk-guarded to strings. */
    override fun missingBlobs(): List<String> {
        val refs = linkedSetOf<String>()
        for (m in messages.values) {
            if (m.kind != "post" && m.kind != "dm") continue
            (m.payload["blobs"] as? List<*>)?.forEach { if (it is String) refs.add(it) }
            (m.payload["poster"] as? String)?.let { if (it.isNotEmpty()) refs.add(it) }
            (m.payload["thumbs"] as? List<*>)?.forEach { if (it is String) refs.add(it) }
        }
        return refs.filter { it !in blobs }
    }

    override fun putBlob(hash: String, data: ByteArray): Boolean {
        if (sha(data) != hash) return false
        blobs[hash] = data
        return true
    }

    override fun stats(): SyncStats = SyncStats(messages.size, blobs.size, identities.size)

    override fun getEncKey(): Pair<String, String>? = encKey
    override fun setEncKey(priv: String, pub: String) { encKey = priv to pub }

    override fun nextSeq(): Int { seqCounter += 1; return seqCounter }

    private var publishedEncPub: String? = null
    override fun getPublishedEncPub(): String? = publishedEncPub
    override fun setPublishedEncPub(pub: String) { publishedEncPub = pub }

    override fun allMessages(): List<StoredMsg> =
        messages.entries.map { (id, m) -> StoredMsg(id, m.kind, m.cert.identity_pub, m.payload) }

    override fun wrapGrantsFor(msgId: String, acceptedSigners: Set<String>): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return messages.values
            .filter {
                it.kind == "wrap_grant" && it.payload["target"] == msgId &&
                    it.cert.identity_pub in acceptedSigners
            }
            .sortedWith(compareBy(
                { (it.payload["created_at"] as? Number)?.toDouble() ?: 0.0 },
                { it.seq }))
            .mapNotNull { it.payload["wraps"] as? Map<String, Any?> }
    }
}
