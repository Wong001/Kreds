package expo.modules.tormanager

import java.security.MessageDigest
import java.security.SecureRandom

object Compose {
    /** `messageDict` (Task 9, outbound loopback gate): the exact
     *  `SignedMessage.toDict()` of the message this call just locally
     *  ingested -- i.e. wire-frame-ready for `KotlinSync.run`'s `outbound`
     *  parameter. Retained as a direct, no-store-round-trip convenience for
     *  a caller (e.g. the Task 9/outbound-Task-8 loopback gates) that wants
     *  to push THIS EXACT dict itself in the same session, without waiting
     *  for a later sync to drain the queue -- re-deriving it instead would
     *  consume a second, different `store.nextSeq()` value and diverge from
     *  the message already sitting in the local store. `post` ALSO queues
     *  `signed.msgId()`/`signed.toDict()` via `store.addPendingOutbound`
     *  (outbound Task 3) so an ordinary caller that does nothing further
     *  still gets it pushed on the next `SyncRunner` sync -- `messageDict`
     *  is no longer the only way a composed post reaches the wire, just the
     *  immediate-push shortcut.
     *  Existing callers (LocalApi's HTTP route, ComposeTest) only ever read
     *  `msgId`/`blobs` and are unaffected by this field. */
    data class Result(val msgId: String, val blobs: List<Pair<String, ByteArray>>, val messageDict: Map<String, Any?>)

    private fun sha256hex(b: ByteArray): String =
        KotlinWire.toHex(MessageDigest.getInstance("SHA-256").digest(b))

    /** Compose + LOCALLY ingest a kreds-scope journal post. `photos` are
     *  ready-to-encrypt plaintext (JPEG) bytes. Wraps to own + ALL friends'
     *  enc-keyed devices (kreds). `expiresSeconds` is optional: if non-null,
     *  expires_at = createdAt + expiresSeconds; if null, expires_at = null. */
    fun post(
        store: SyncStore, fx: KotlinHandshake.Fixture, encPriv: String, encPub: String,
        text: String, photos: List<ByteArray>, scope: String, createdAt: Double,
        expiresSeconds: Double? = null,
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
            "created_at" to KotlinWire.PyFloat(createdAt),
            "expires_at" to (if (expiresSeconds != null) KotlinWire.PyFloat(createdAt + expiresSeconds) else null),
            "placement" to "journal", "media" to "photo", "poster" to null,
            "codec" to null, "thumbs" to null)

        val unsigned = SignedMessage(fx.cert, store.nextSeq(), payload, "")
        val signed = unsigned.copy(signature = KotlinWire.signRaw(fx.device_priv, unsigned.body()))
        store.ingestMessage(signed)
        // outbound Task 3: queue this composed post so the NEXT sync
        // actually pushes it onward (see SyncRunner.runTransport, which
        // drains store.pendingOutbound() into KotlinSync.run's outbound list
        // and clears it on SyncResult.Ok) -- without this, a composed post
        // sat in local storage and never left the phone (see this file's
        // class-level context: `messageDict` alone only let a CALLER push it
        // manually, e.g. the Task 9 loopback gate; ordinary callers like
        // LocalApi's HTTP route never did).
        //
        // outbound review wave (FIX 1): pass `signed.toDict()` -- the native
        // wire dict, with created_at as a Double/PyFloat and seq as an Int --
        // not just the msgId. The queue now stores this dict directly (see
        // SyncStore.addPendingOutbound's doc), so re-send never has to round-
        // trip through the lossy org.json `messages.msg_json` storage.
        store.addPendingOutbound(signed.msgId(), signed.toDict())
        return Result(signed.msgId(), blobPairs, signed.toDict())
    }
}
