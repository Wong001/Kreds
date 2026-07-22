package expo.modules.tormanager

import java.security.MessageDigest
import java.security.SecureRandom

/** Kotlin port of hearth.node.Node.compose_dm (+ _dm_device_pubs),
 *  node.py:2308-2345, and messages.make_dm (messages.py:134-156) +
 *  _valid_story_ref (messages.py:241-255). Sibling of Compose.post /
 *  ComposeResponse.compose -- same fixture/ingest/enqueue idiom, `kind:"dm"`
 *  envelope wrapped to the RECIPIENT's devices (plus our own, for a
 *  self-readable copy) instead of a whole friend list or a single post's
 *  author.
 *
 *  Blob storage mirrors Compose.post's mechanics exactly (read first): hearth
 *  stores each encrypted blob inline via `store.put_blob(encrypt_blob(...))`
 *  at compose time (node.py:2338), computing the content-addressed hash
 *  itself. Compose.kt matches this by computing the ciphertext's SHA-256 hash
 *  locally and calling `store.putBlob(hash, cipher)` inline (rather than
 *  deferring storage to the caller) -- ComposeDm does the identical inline
 *  store.putBlob, not a Result.blobs deferral (see task-1-report.md). */
object ComposeDm {
    data class Result(val msgId: String, val wireDict: Map<String, Any?>, val contentKey: ByteArray)

    private fun sha256hex(b: ByteArray): String =
        KotlinWire.toHex(MessageDigest.getInstance("SHA-256").digest(b))

    private fun isHex64(s: Any?): Boolean =
        s is String && s.length == 64 && s.all { it in "0123456789abcdef" }

    /** hearth's `_valid_story_ref` (messages.py:241-255) mirror, public so
     *  Task 2's route can pre-validate before calling compose. The None/null
     *  case is the CALLER's job in hearth (compose_dm only calls this when
     *  story_ref is not None) -- but this standalone accessor treats a null
     *  argument as invalid outright (there is no "absent is fine" context
     *  once a caller is asking the shape question directly). */
    fun validStoryRef(sref: Map<String, Any?>?): Boolean {
        if (sref == null) return false
        val storyId = sref["story_id"]
        if (storyId !is String || storyId.isEmpty()) return false
        return isHex64(sref["media_hash"])
    }

    /** Compose + LOCALLY ingest a DM (text + optional photos) to `to`,
     *  wrapped to the recipient's known devices plus our own (so this
     *  composing device can read back what it just sent). `createdAt` is the
     *  caller-supplied wall-clock reading (seconds) -- unlike ComposeResponse,
     *  hearth's compose_dm does not enforce strict monotonicity on this
     *  value (node.py:2332 just reads `time.time()` once), so it is used
     *  as-is. `expiresSeconds`, when non-null, sets `expires_at = createdAt +
     *  expiresSeconds`; `storyRef`, when non-null, must satisfy
     *  `validStoryRef` and rides the envelope in the clear (spec
     *  2026-07-18's named disclosure -- see messages.py:139-150). */
    fun compose(
        store: SyncStore, fx: KotlinHandshake.Fixture, encPriv: String, encPub: String,
        to: String, text: String, photoJpegs: List<ByteArray>, expiresSeconds: Double?,
        storyRef: Map<String, Any?>?, createdAt: Double,
    ): Result {
        val own = fx.cert.identity_pub

        // Validations in hearth's exact order (node.py:2320-2331): self-DM,
        // not-a-friend, bad story_ref -- ALL before the recipient-enckeys
        // check inside _dm_device_pubs below.
        require(to != own) { "cannot DM yourself" }
        require(to in store.knownIdentities()) { "recipient is not a friend" }
        if (storyRef != null) require(validStoryRef(storyRef)) { "bad story_ref" }

        // _dm_device_pubs (node.py:2308-2315): theirs first, non-empty
        // required; mine (own devices + this device's explicit current
        // encPub) merged on top -- {**theirs, **mine} semantics, so OUR
        // current encPub always wins over whatever store.enckeys(own) may
        // still hold for this same device_pub (e.g. a stale published row).
        val theirs = store.enckeys(to)
        require(theirs.isNotEmpty()) { "no encryption keys known for recipient yet" }
        val mine = store.enckeys(own).toMutableMap()
        mine[fx.device_pub] = encPub
        val pubs = linkedMapOf<String, String>().apply { putAll(theirs); putAll(mine) }

        val expiresAt = expiresSeconds?.let { createdAt + it }
        val aad = KotlinDmcrypt.dmAad(own, to, createdAt)
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

        // Photos are already PhotoPrep-gated JPEGs by the time they reach
        // this orchestrator (the route gates) -- mirrors Compose.post's
        // inline store.putBlob(hash, cipher), hash computed over the
        // CIPHERTEXT (hearth's blob_hash likewise hashes the encrypted blob).
        val refs = photoJpegs.map { jpeg ->
            val cipher = KotlinBlobCrypt.encryptBlob(key, jpeg)
            val hash = sha256hex(cipher)
            store.putBlob(hash, cipher)
            hash
        }

        val (nonceHex, ctHex) = KotlinDmcrypt.encryptBody(key, mapOf("text" to text, "blobs" to refs), aad)
        val wraps = KotlinDmcrypt.wrapKey(key, pubs, aad)

        // make_dm's exact field set (messages.py:151-156) -- expires_at and
        // story_ref keys ALWAYS present (null when absent), matching a
        // desktop node's canonical wire form byte-for-byte.
        val payload: Map<String, Any?> = mapOf(
            "kind" to "dm", "to" to to, "body_nonce" to nonceHex, "body_ct" to ctHex,
            "wraps" to wraps, "blobs" to refs,
            "created_at" to KotlinWire.PyFloat(createdAt),
            "expires_at" to expiresAt?.let { KotlinWire.PyFloat(it) },
            "story_ref" to storyRef)

        val unsigned = SignedMessage(fx.cert, store.nextSeq(), payload, "")
        val signed = unsigned.copy(signature = KotlinWire.signRaw(fx.device_priv, unsigned.body()))
        store.ingestMessage(signed)
        // outbound Task 3 idiom (see Compose.post/ComposeResponse.compose):
        // queue this composed DM so the NEXT sync actually pushes it onward.
        store.addPendingOutbound(signed.msgId(), signed.toDict())
        return Result(signed.msgId(), signed.toDict(), key)
    }
}
