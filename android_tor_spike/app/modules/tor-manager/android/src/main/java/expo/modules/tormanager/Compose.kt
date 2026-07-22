package expo.modules.tormanager

import java.security.MessageDigest
import java.security.SecureRandom

object Compose {
    data class Result(val msgId: String, val blobs: List<Pair<String, ByteArray>>)

    private fun sha256hex(b: ByteArray): String =
        KotlinWire.toHex(MessageDigest.getInstance("SHA-256").digest(b))

    /** Compose + LOCALLY ingest a kreds-scope journal post. `photos` are
     *  ready-to-encrypt plaintext (JPEG) bytes. Wraps to own + ALL friends'
     *  enc-keyed devices (kreds). */
    fun post(
        store: SyncStore, fx: KotlinHandshake.Fixture, encPriv: String, encPub: String,
        text: String, photos: List<ByteArray>, scope: String, createdAt: Double,
    ): Result {
        require(scope == "kreds") { "only kreds scope this slice" }
        val own = fx.cert.identity_pub
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val aad = KotlinDmcrypt.postAad(own, scope, createdAt)

        val blobPairs = photos.map { jpeg ->
            val cipher = KotlinBlobCrypt.encryptBlob(key, jpeg)
            val hash = sha256hex(cipher)
            store.putBlob(hash, cipher)
            hash to cipher
        }
        val hashes = blobPairs.map { it.first }

        val (nonceHex, ctHex) = KotlinDmcrypt.encryptBody(
            key, mapOf("text" to text, "blobs" to hashes), aad)

        // recipients: own devices + all friends' devices + THIS device explicit.
        val recipients = linkedMapOf<String, String>()
        for (f in store.knownIdentities()) if (f != own) recipients.putAll(store.enckeys(f))
        recipients.putAll(store.enckeys(own))
        recipients[fx.device_pub] = encPub
        val wraps = KotlinDmcrypt.wrapKey(key, recipients, aad)

        val payload: Map<String, Any?> = mapOf(
            "kind" to "post", "scope" to scope, "body_nonce" to nonceHex,
            "body_ct" to ctHex, "wraps" to wraps, "blobs" to hashes,
            "created_at" to KotlinWire.PyFloat(createdAt), "expires_at" to null,
            "placement" to "journal", "media" to "photo", "poster" to null,
            "codec" to null, "thumbs" to null)

        val unsigned = SignedMessage(fx.cert, store.nextSeq(), payload, "")
        val signed = unsigned.copy(signature = KotlinWire.signRaw(fx.device_priv, unsigned.body()))
        store.ingestMessage(signed)
        return Result(signed.msgId(), blobPairs)
    }
}
