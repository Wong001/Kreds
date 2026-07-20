package expo.modules.tormanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Read-only /api provider for the WebView shell (slice 1). Pure JSON
 *  builders live in the companion (JVM-testable); the instance methods do the
 *  Android store I/O. A fresh SqliteSyncStore(ctx) is constructed per request
 *  (the existing "new instance per op, cheap at ~253 msgs" pattern). Token is
 *  already validated by LocalWebServer before handle() runs. */
class LocalApi(private val ctx: Context) {

    // vp1: msgId -> content key, populated by the /api/feed decrypt pass and
    // reused by /api/post-blob so an image request does NOT re-run a full
    // DecryptPass.run over every message (which would be O(images x messages)).
    // In-memory only; refreshed each /api/feed; postBlob falls back to a fresh
    // run if a hash is requested before any feed() populated the cache.
    @Volatile private var keysCache: Map<String, ByteArray> = emptyMap()

    fun handle(method: String, path: String): HttpResponse? {
        if (method != "GET") return null
        return when {
            path == "/api/bootstrap" -> json(bootstrapJson())
            path == "/api/applock" -> json(applockJson())
            path == "/api/state" -> json(state())
            path == "/api/feed" -> json(feed())
            path == "/api/stories" -> json(stories())
            path.startsWith("/api/post-blob/") -> {
                val seg = path.removePrefix("/api/post-blob/").split("/")
                if (seg.size != 2 || seg[0].isEmpty() || seg[1].isEmpty()) notFound() else postBlob(seg[0], seg[1])
            }
            path.startsWith("/api/blob/") -> {
                val h = path.removePrefix("/api/blob/")
                if (h.isEmpty() || h.contains("/")) notFound() else blob(h)
            }
            else -> null
        }
    }

    private fun feed(): String {
        val fx = fixtureOrNull() ?: return "[]"
        val store = SqliteSyncStore(ctx)
        val (priv, _) = EncKeys.getOrCreate(store)
        val own = fx.cert.identity_pub
        val res = DecryptPass.run(store, fx.device_pub, priv, own)
        keysCache = res.keys                                   // vp1: warm the blob-key cache
        val responses = DecryptPass.responsesPass(store, fx.device_pub, priv, own)
        val arr = JSONArray()
        for (d in res.feed) {                                  // already newest-first
            if (d.kind != "post") continue                     // journal feed = posts only
            if ((d.placement ?: "journal") != "journal") continue
            arr.put(feedRow(d, own, responses[d.msgId]))
        }
        return arr.toString()
    }

    private fun state(): String {
        val fx = fixtureOrNull()
        val store = SqliteSyncStore(ctx)
        val names = store.profileNames()
        val own = fx?.cert?.identity_pub ?: ""
        val friends = store.knownIdentities().filter { it != own }.map { it to (names[it] ?: it.take(8)) }
        return stateJson(
            identityPub = own,
            devicePub = fx?.device_pub ?: "",
            deviceName = fx?.cert?.device_name ?: "phone",
            profileName = names[own] ?: "",
            friends = friends)
    }

    private fun fixtureOrNull(): KotlinHandshake.Fixture? = try {
        KotlinHandshake.parseFixture(File(TorEngine.externalDir(), "spike_phone_fixture.json").readText())
    } catch (e: Exception) { null }

    private fun stories(): String {
        val fx = fixtureOrNull()
        val store = SqliteSyncStore(ctx)
        val own = fx?.cert?.identity_pub ?: ""
        val now = System.currentTimeMillis() / 1000.0
        return storiesJson(store.activeStories(now), store.profileNames(), own)
    }

    // /api/blob/{h}: raw plaintext-at-rest bytes (avatars/plaintext content),
    // NO content-key decrypt -- mirrors getStoryImage's decrypt-skipping path.
    private fun blob(hash: String): HttpResponse {
        val data = SqliteSyncStore(ctx).getBlob(hash) ?: return notFound()
        return mediaResponse(data)
    }

    // /api/post-blob/{msg_id}/{h}: content-key-decrypted post/DM blob bytes,
    // streamed decrypt-on-read. AVIF is served raw (image/avif) -- the WebView
    // Chromium renderer decodes it (it never has our keys; we hand it already-
    // decrypted plaintext), matching desktop exactly.
    private fun postBlob(msgId: String, hash: String): HttpResponse {
        val store = SqliteSyncStore(ctx)
        // vp1: use the cache warmed by /api/feed; only fall back to a full
        // DecryptPass.run if this blob's key isn't cached yet (first paint before
        // any feed(), or a key that aged out).
        var key = keysCache[msgId]
        if (key == null) {
            val fx = fixtureOrNull() ?: return notFound()
            val (priv, _) = EncKeys.getOrCreate(store)
            val res = DecryptPass.run(store, fx.device_pub, priv, fx.cert.identity_pub)
            keysCache = res.keys
            key = res.keys[msgId] ?: return notFound()
        }
        val cipher = store.getBlob(hash) ?: return notFound()
        val plain = KotlinBlobCrypt.decryptBlob(key, cipher) ?: return notFound()
        return mediaResponse(plain)
    }

    private fun mediaResponse(bytes: ByteArray) = HttpResponse(200, mapOf(
        "Content-Type" to sniff(bytes),
        "X-Content-Type-Options" to "nosniff",
        "Cache-Control" to "private, max-age=31536000, immutable"), bytes)

    private fun notFound() = HttpResponse(404, mapOf("Content-Type" to "text/plain"), "not found".toByteArray())

    companion object {
        fun json(body: String) =
            HttpResponse(200, mapOf("Content-Type" to "application/json; charset=utf-8"), body.toByteArray())

        // Kotlin port of hearth api.py `_sniff` (magic-byte content-type).
        fun sniff(data: ByteArray): String {
            fun startsWith(prefix: ByteArray): Boolean {
                if (data.size < prefix.size) return false
                for (i in prefix.indices) if (data[i] != prefix[i]) return false
                return true
            }
            if (startsWith(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))) return "image/png"
            if (startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))) return "image/jpeg"
            if (startsWith("GIF8".toByteArray(Charsets.ISO_8859_1))) return "image/gif"
            if (startsWith("RIFF".toByteArray(Charsets.ISO_8859_1))) return "image/webp"
            if (data.size >= 12 && String(data, 4, 4, Charsets.ISO_8859_1) == "ftyp") {
                val brand = String(data, 8, 4, Charsets.ISO_8859_1)
                if (brand == "avif" || brand == "avis") return "image/avif"
                return "video/mp4"
            }
            return "application/octet-stream"
        }

        // hearth /api/stories (node.py stories_view + store.py active_stories):
        // one group per author, self ("mine") first, then other groups by
        // last-created-at desc (store.active_stories() already sorts groups
        // that way; node.py's stable `sort(key=lambda g: (not g["mine"],))`
        // only pulls the self group to the front, otherwise preserving that
        // order). Items within a group are OLDEST-first: store.py's SQL is
        // `ORDER BY created_at ASC` and items are appended in that scan
        // order with NO reversal -- confirmed against hearth source (not
        // reversed the way a naive "recent stories" reading might assume);
        // this is a story-viewer playback order (oldest to newest). Avatars
        // deferred (null) -- vp1 slice 1 has no avatar-blob accessor yet.
        fun storiesJson(stories: List<StoredStory>, profileNames: Map<String, String>, ownIdentityPub: String): String {
            val groups = linkedMapOf<String, MutableList<StoredStory>>()
            for (s in stories) groups.getOrPut(s.author) { mutableListOf() }.add(s)
            data class G(val mine: Boolean, val last: Double, val obj: JSONObject)
            val built = groups.map { (ipub, items) ->
                val itemsArr = JSONArray()
                for (it in items.sortedBy { it.createdAt }) {
                    itemsArr.put(JSONObject()
                        .put("msg_id", it.msgId)
                        .put("media_kind", it.mediaKind)
                        .put("media", it.media)
                        .put("poster", it.poster ?: JSONObject.NULL)
                        .put("caption", it.caption)
                        .put("created_at", it.createdAt))
                }
                val mine = ipub == ownIdentityPub
                G(mine, items.maxOf { it.createdAt }, JSONObject()
                    .put("identity_pub", ipub)
                    .put("items", itemsArr)
                    .put("mine", mine)
                    .put("name", profileNames[ipub] ?: ipub.take(8))
                    .put("avatar", JSONObject.NULL))
            }.sortedWith(compareByDescending<G> { it.mine }.thenByDescending { it.last })
            val out = JSONArray()
            for (g in built) out.put(g.obj)
            return out.toString()
        }

        fun bootstrapJson(): String =
            JSONObject().put("initialized", true).put("onboarding_done", true).toString()

        fun applockJson(): String =
            JSONObject()
                .put("enabled", false).put("locked", false).put("cred_type", JSONObject.NULL)
                .put("settings", JSONObject().put("idle_minutes", 0).put("lock_on_sleep", false))
                .put("throttle_wait", 0).toString()

        fun stateJson(identityPub: String, devicePub: String, deviceName: String,
                      profileName: String, friends: List<Pair<String, String>>): String {
            val friendsArr = JSONArray()
            for ((ipub, name) in friends)
                friendsArr.put(JSONObject().put("identity_pub", ipub).put("name", name))
            return JSONObject()
                .put("identity_pub", identityPub)
                .put("device_pub", devicePub)
                .put("device_name", deviceName)
                .put("profile_name", profileName)
                .put("devices", JSONArray())
                .put("friends", friendsArr)
                .put("peers", JSONArray())
                .put("disconnected", JSONArray())
                .put("revoked", false)
                .put("accent", "#2743d6")
                .put("update_status",
                    JSONObject().put("available", false).put("kind", JSONObject.NULL).put("version", JSONObject.NULL))
                .put("readonly", true)
                .toString()
        }

        fun feedRow(d: DecryptPass.Decrypted, ownIdentityPub: String, responses: KotlinResponses.Responses?): JSONObject {
            val blobs = JSONArray(); d.blobs.forEach { blobs.put(it) }
            val thumbs: Any = if (d.thumbs.isEmpty()) JSONObject.NULL
                else JSONArray().also { arr -> d.thumbs.forEach { arr.put(it ?: JSONObject.NULL) } }
            return JSONObject()
                .put("msg_id", d.msgId)
                .put("identity_pub", d.identityPub)
                .put("author_name", d.author)
                .put("author_avatar", JSONObject.NULL)        // avatars deferred (slice 1)
                .put("text", d.text)
                .put("blobs", blobs)
                .put("scope", d.scope ?: JSONObject.NULL)
                .put("created_at", d.createdAt)
                .put("expires_at", d.expiresAt ?: JSONObject.NULL)
                .put("mine", d.identityPub == ownIdentityPub)
                .put("placement", d.placement ?: "journal")
                .put("media", d.media)
                .put("poster", d.poster ?: JSONObject.NULL)
                .put("codec", d.codec ?: JSONObject.NULL)
                .put("thumbs", thumbs)
                .put("responses", responsesJson(responses))
        }

        private fun responsesJson(r: KotlinResponses.Responses?): Any {
            if (r == null) return JSONObject.NULL
            val reactions = JSONObject()
            for ((k, v) in r.reactions) reactions.put(k, v)
            val comments = JSONArray()
            for (c in r.comments) {
                val co = JSONObject()
                    .put("name", if (c.alias) JSONObject.NULL else (c.name ?: JSONObject.NULL))
                    .put("avatar", JSONObject.NULL)           // comment-author avatars deferred
                    .put("alias", c.alias)
                    .put("alias_seed", c.aliasSeed)
                    .put("mine", false)                        // read-only
                    .put("body", c.body)
                    .put("created_at", c.createdAt)
                // `responder` is emitted CONDITIONALLY -- present only for a
                // resolved (non-alias) comment, mirroring hearth's own
                // conditional assignment (node.py:1586-1588, `if resolved:
                // comment["responder"] = identity`). An alias comment must
                // never carry it: web/app.js:621 derives a resolved comment's
                // avatar color via identityColor(c.responder), and an
                // unconditional omission previously left that call as
                // identityColor(undefined) -> hsl(NaN...) for every resolved
                // comment served from /api/feed (bug fix, vp1).
                if (c.responder != null) co.put("responder", c.responder)
                comments.put(co)
            }
            return JSONObject()
                .put("reactions", reactions)
                .put("my_reaction", JSONObject.NULL)          // read-only
                .put("comments", comments)
                .put("can_moderate", false)                    // read-only
        }
    }
}
