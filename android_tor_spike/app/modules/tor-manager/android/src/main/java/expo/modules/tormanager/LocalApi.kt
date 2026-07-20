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
        return when (path) {
            "/api/bootstrap" -> json(bootstrapJson())
            "/api/applock" -> json(applockJson())
            "/api/state" -> json(state())
            "/api/feed" -> json(feed())
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

    companion object {
        fun json(body: String) =
            HttpResponse(200, mapOf("Content-Type" to "application/json; charset=utf-8"), body.toByteArray())

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
