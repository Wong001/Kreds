package expo.modules.tormanager

import java.security.MessageDigest

data class SyncStats(val messages: Int, val blobs: Int, val identities: Int)

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
}

/** Reference impl (JVM-testable, no Android). Also the shape the SQLite
 *  impl mirrors. */
class InMemorySyncStore : SyncStore {
    private val identities = linkedSetOf<String>()
    private val messages = linkedMapOf<String, SignedMessage>()     // msg_id -> msg
    private val seen = hashMapOf<Pair<String, String>, SeenSet>()   // (ipub,dpub) -> seen
    private val blobs = linkedMapOf<String, ByteArray>()            // hash -> data
    private var encKey: Pair<String, String>? = null                // (encPrivHex, encPubHex)

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
}
