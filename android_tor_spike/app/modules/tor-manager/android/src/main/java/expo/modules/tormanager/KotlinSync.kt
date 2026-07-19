package expo.modules.tormanager

import org.json.JSONArray
import org.json.JSONObject

sealed class SyncResult {
    data class Ok(val messages: Int, val blobs: Int, val identities: Int) : SyncResult()
    object SelfRevoked : SyncResult()
    data class Failed(val stage: String, val reason: String) : SyncResult()
}

/** Runs the post-AUTH sync phases (hearth/sync.py _session) as INITIATOR,
 *  over an already-authenticated Stream. Read-only pull: sends empty
 *  msgs/blobs; ingests the node's own-identity messages + blobs.
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
        else -> v
    }

    fun run(stream: Stream, store: SyncStore, ownDevicePub: String): SyncResult {
        try {
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

            // -- MESSAGES -- (send empty, ingest node's)
            writeFrame(stream, mapOf("t" to "messages", "msgs" to emptyList<Any>()))
            val msgs = readFrame(stream)
            val msgArr = msgs.optJSONArray("msgs") ?: JSONArray()
            for (i in 0 until msgArr.length()) {
                val m = SignedMessageKt.fromDict(toMap(msgArr.getJSONObject(i)))
                store.ingestMessage(m)   // verifies + dedups internally
            }

            // -- BLOBS -- (want swap, then blobs swap)
            writeFrame(stream, mapOf("t" to "blob_want", "hashes" to store.missingBlobs()))
            readFrame(stream)   // node's want; we give nothing
            writeFrame(stream, mapOf("t" to "blobs", "blobs" to emptyMap<String, Any>()))
            val blobs = readFrame(stream)
            val given = blobs.optJSONObject("blobs") ?: JSONObject()
            for (h in given.keys()) {
                // java.util.Base64, not android.util.Base64: keeps KotlinSync
                // Android-free so the desk gate (BB-5) can run it on a plain
                // JVM. Available on both JVM and Android API 26+.
                val data = java.util.Base64.getDecoder().decode(given.getString(h))
                store.putBlob(h, data)   // verifies hash internally
            }

            val st = store.stats()
            return SyncResult.Ok(st.messages, st.blobs, st.identities)
        } catch (e: Exception) {
            return SyncResult.Failed("io", e.toString())
        } finally {
            stream.close()
        }
    }
}
