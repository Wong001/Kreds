package expo.modules.tormanager

import org.json.JSONArray
import org.json.JSONObject

sealed class SyncResult {
    data class Ok(val messages: Int, val blobs: Int, val identities: Int) : SyncResult()
    object SelfRevoked : SyncResult()
    data class Failed(val stage: String, val reason: String) : SyncResult()
}

private const val MAX_BLOB_BYTES = 10 * 1024 * 1024   // hearth/messages.py:56

/** RFC-4648 standard-alphabet base64 decoder, hand-rolled instead of either
 *  android.util.Base64 (Android-only, breaks the BB-5 JVM desk gate) or
 *  java.util.Base64 (API 26+, this module's minSdkVersion is 24 -- would
 *  NoSuchMethodError on API 24/25 devices, uncaught by assembleDebug since
 *  lintOptions.abortOnError is false). Works on any API level and on a
 *  plain JVM. The node encodes blobs with Python's base64.b64encode,
 *  which uses this same standard alphabet. */
object Base64Portable {
    private val DEC = IntArray(128) { -1 }.also {
        val a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for (i in a.indices) it[a[i].code] = i
    }
    fun decode(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buf = 0; var bits = 0
        for (c in s) {
            if (c == '=' || c == '\n' || c == '\r' || c == ' ') continue
            val v = if (c.code < 128) DEC[c.code] else -1
            require(v >= 0) { "bad base64 char" }
            buf = (buf shl 6) or v; bits += 6
            if (bits >= 8) { bits -= 8; out.write((buf shr bits) and 0xff) }
        }
        return out.toByteArray()
    }
}

/** Runs the post-AUTH sync phases (hearth/sync.py _session) as INITIATOR,
 *  over an already-authenticated Stream. Sends empty revs/notices/blobs, and
 *  (since B.2 Task 4) the caller-supplied `outbound` messages -- for B.2, at
 *  most one: the phone's device-signed enckey (see composeEncKey below).
 *  `outbound` defaults to empty, so a bare pull (B.1's original shape) is
 *  still `run(stream, store, ownDevicePub)`. Always ingests the node's
 *  own-identity messages + blobs.
 *
 *  Blocking, like KotlinHandshake.run -- callers (BB-7) must invoke this
 *  off the main thread (Dispatchers.IO), same reason TorEngine's own
 *  send/recv must be. */
object KotlinSync {

    private fun writeFrame(s: Stream, obj: Map<String, Any?>) =
        s.write(KotlinWire.writeFrameBytes(obj))

    // Mirrors KotlinHandshake's private readFrame exactly (4-byte big-endian
    // length prefix, ASCII-only body -- the node always sends ensure_ascii
    // JSON) but over a Stream instead of a bare TorEngine connId, so the
    // same phase logic can run over TorStream (phone) or the desk gate's
    // SocketStream (BB-5).
    private fun readFrame(s: Stream): JSONObject {
        val header = s.readExactSync(4)
        val n = (((header[0].toLong() and 0xff) shl 24) or ((header[1].toLong() and 0xff) shl 16) or
                 ((header[2].toLong() and 0xff) shl 8) or (header[3].toLong() and 0xff))
        require(n <= KotlinWire.MAX_FRAME) { "frame too large" }
        val body = s.readExactSync(n.toInt())
        for (b in body) require((b.toInt() and 0xff) <= 0x7e) { "non-ascii frame byte" }
        return JSONObject(String(body, Charsets.US_ASCII))
    }

    // org.json -> plain Kotlin bridge (JSONObject -> Map, JSONArray -> List).
    // SignedMessageKt.fromDict and SeenSet.fromJson take Map/List, not
    // org.json types -- a raw JSONObject/JSONArray fed to them throws
    // ClassCastException. Needed only on the read path (parsing frames the
    // node sent us); frames we build ourselves are already plain Kotlin
    // (store.summary()/knownIdentities()/missingBlobs() all return Kotlin
    // Map/List), so KotlinWire.writeFrameBytes serializes those directly.
    private fun toMap(o: JSONObject): Map<String, Any?> =
        o.keys().asSequence().associateWith { unwrap(o.get(it)) }

    private fun unwrap(v: Any?): Any? = when (v) {
        is JSONObject -> toMap(v)
        is JSONArray -> (0 until v.length()).map { unwrap(v.get(it)) }
        JSONObject.NULL -> null
        // BB-5 desk-gate finding: a real node's created_at (time.time(),
        // ~17 significant decimal digits) parses through org.json as
        // java.math.BigDecimal, not Double -- org.json's number parser
        // keeps the exact-value type whenever Java's own Double.toString()
        // doesn't echo the original literal character-for-character (a
        // FORMATTING mismatch, e.g. scientific-notation threshold, the same
        // Java/Python repr() divergence KotlinWire.pyFloatRepr already
        // works around elsewhere -- not an actual precision loss). Every
        // hand-built fixture/vector used a "clean" literal that happens to
        // round-trip through Double.toString() exactly, so this never
        // surfaced before a real node's wall-clock timestamps hit it here.
        // Normalize to a plain Double (not KotlinWire.PyFloat): the SAME
        // parsed value also flows through plain `as Number` casts elsewhere
        // (SignedMessageKt.fromDict reads a message's embedded cert.
        // enrolled_at that way), which a PyFloat wrapper would break.
        // KotlinWire.dumps gained a matching `is Double` case (this same
        // BB-5 fix) so re-serialization (message body/msgId/signature
        // verification) still reproduces the exact canonical bytes the node
        // signed -- BigDecimal.toDouble() is a correctly-rounded decimal ->
        // IEEE754 conversion (same contract java.lang.Double.parseDouble
        // uses), recovering the identical double bit pattern Python's
        // repr() encoded.
        is java.math.BigDecimal -> v.toDouble()
        else -> v
    }

    /** Builds a `SignedMessage.to_dict()`-shaped map for a KIND_ENCKEY payload
     *  ({"kind":"enckey","enc_pub":...,"created_at":...}), signed by this
     *  device -- byte-matches hearth's `messages.make_enckey` +
     *  `identity.DeviceKeys.sign_message` (hearth/messages.py:126-131,
     *  hearth/identity.py:304-316): the canonical signed body is
     *  {"type":"message","protocol":PROTOCOL,"identity_pub":...,
     *  "device_pub":...,"seq":...,"payload":...}, which is exactly what
     *  `SignedMessage.body()` (SignedMessageKt.kt) already builds and
     *  `SignedMessageTest` already vector-proves byte-correct against real
     *  hearth output for other payload kinds (post/dm) -- reused here
     *  (via a throwaway-signature `SignedMessage` + `.copy`) rather than
     *  re-implemented, so this inherits that byte-fidelity proof instead of
     *  risking a second, subtly-divergent construction.
     *
     *  `createdAt` and `seq` are supplied by the caller, not computed here:
     *  the phone needs a real wall-clock reading (`System.currentTimeMillis()
     *  /1000.0`, no `Date.now()`-style call inside this object) and its own
     *  persisted next-seq counter (`SyncStore.nextSeq()`, starts at 1 -- see
     *  that method's doc comment). */
    fun composeEncKey(
        fixture: KotlinHandshake.Fixture,
        encPub: String,
        seq: Int,
        createdAt: Double,
    ): Map<String, Any?> {
        val payload: Map<String, Any?> = mapOf(
            "kind" to "enckey", "enc_pub" to encPub, "created_at" to createdAt,
        )
        val unsigned = SignedMessage(fixture.cert, seq, payload, "")
        val signed = unsigned.copy(signature = KotlinWire.signRaw(fixture.device_priv, unsigned.body()))
        return mapOf(
            "cert" to certToMap(signed.cert), "seq" to signed.seq,
            "payload" to signed.payload, "signature" to signed.signature,
        )
    }

    // Mirrors KotlinHandshake's private certToMap (identity_pub/device_pub/
    // device_name/enrolled_at/signature) but with enrolled_at as a plain
    // Double rather than a KotlinWire.PyFloat wrapper -- KotlinWire.dumps'
    // `is Double` case (the BB-5 fix, see KotlinSync.unwrap's comment above)
    // formats a bare Double identically to a PyFloat-wrapped one, so the two
    // produce byte-identical wire output; plain Double is simpler here and
    // lets composeEncKey's own result be compared with ordinary Kotlin Map
    // equality in tests (a PyFloat instance has no value-based equals()).
    private fun certToMap(c: KotlinWire.CertDict): Map<String, Any?> = mapOf(
        "identity_pub" to c.identity_pub, "device_pub" to c.device_pub,
        "device_name" to c.device_name, "enrolled_at" to c.enrolled_at,
        "signature" to c.signature)

    /** `onProgress` (Task 6, B.2d): purely additive observability -- fired at
     *  the phase boundaries already present below (connecting/handshake/
     *  messages/blobs/decrypting) so a caller (the module) can surface live
     *  sync status while the ~1-2 min sync runs. Defaults to a no-op so
     *  every pre-existing caller/test (KotlinSyncTest, SyncLoopbackTest's
     *  earlier tests) compiles and behaves identically without passing it.
     *  Never affects control flow -- the phases/order/data below are
     *  unchanged; this only observes them. The trailing "done" phase is
     *  emitted by the MODULE after DecryptPass, not here (this object has
     *  no knowledge of DecryptPass). */
    fun run(stream: Stream, store: SyncStore, ownDevicePub: String,
            outbound: List<Map<String, Any?>> = emptyList(),
            onProgress: (phase: String, count: Int) -> Unit = { _, _ -> }): SyncResult {
        try {
            onProgress("connecting", 0)
            // -- REVOCATIONS -- (initiator writes then reads)
            writeFrame(stream, mapOf("t" to "revocations", "revs" to emptyList<Any>()))
            val revs = readFrame(stream)
            if (revs.optString("t") == "refused") return SyncResult.Failed("revocations", "refused")
            val revArr = revs.optJSONArray("revs") ?: JSONArray()
            for (i in 0 until revArr.length()) {
                val r = revArr.getJSONObject(i)
                if (r.optString("device_pub") == ownDevicePub) return SyncResult.SelfRevoked
            }

            // -- DEFRIENDS --
            writeFrame(stream, mapOf("t" to "defriends", "notices" to emptyList<Any>()))
            readFrame(stream)   // read node's, ignore (own-identity, B.1)

            // -- HAVE --
            writeFrame(stream, mapOf("t" to "have",
                "summary" to store.summary(), "known" to store.knownIdentities(),
                "peers" to emptyList<Any>(), "addr" to null))
            val have = readFrame(stream)
            val known = have.optJSONArray("known") ?: JSONArray()
            for (i in 0 until known.length()) store.addIdentity(known.getString(i))
            onProgress("handshake", 0)

            // -- MESSAGES -- (push outbound -- for B.2, at most the phone's
            // device-signed enckey, see composeEncKey -- and ingest node's)
            writeFrame(stream, mapOf("t" to "messages", "msgs" to outbound))
            val msgs = readFrame(stream)
            val msgArr = msgs.optJSONArray("msgs") ?: JSONArray()
            for (i in 0 until msgArr.length()) {
                val m = SignedMessageKt.fromDict(toMap(msgArr.getJSONObject(i)))
                store.ingestMessage(m)   // verifies + dedups internally
                onProgress("messages", i + 1)
            }

            // -- BLOBS -- (want swap, then blobs swap)
            writeFrame(stream, mapOf("t" to "blob_want", "hashes" to store.missingBlobs()))
            readFrame(stream)   // node's want; we give nothing
            writeFrame(stream, mapOf("t" to "blobs", "blobs" to emptyMap<String, Any>()))
            val blobs = readFrame(stream)
            val given = blobs.optJSONObject("blobs") ?: JSONObject()
            var storedBlobs = 0
            for (h in given.keys()) {
                val data = Base64Portable.decode(given.getString(h))
                // Size bound mirrors hearth/sync.py:661 (len(data) <=
                // MAX_BLOB_BYTES and blob_hash==h) -- store.putBlob only
                // hash-verifies; an oversized blob (bounded only by
                // MAX_FRAME, ~16 MiB > MAX_BLOB_BYTES's 10 MiB) from a
                // hostile/buggy node must be rejected before it's stored.
                if (data.size <= MAX_BLOB_BYTES) {
                    store.putBlob(h, data)   // store.putBlob does the hash check
                    storedBlobs++
                    onProgress("blobs", storedBlobs)
                }
            }

            onProgress("decrypting", 0)
            val st = store.stats()
            return SyncResult.Ok(st.messages, st.blobs, st.identities)
        } catch (e: Exception) {
            return SyncResult.Failed("io", e.toString())
        } finally {
            stream.close()
        }
    }
}
