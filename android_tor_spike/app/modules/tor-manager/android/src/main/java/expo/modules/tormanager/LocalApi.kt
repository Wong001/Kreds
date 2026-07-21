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

    // vp2: msgId -> content key for DM blobs, warmed by loadDms() (the pass the
    // conversations/dm-thread routes already run) and reused by dmBlob() so a
    // DM image request does NOT re-run a full DecryptPass.run. Separate from
    // keysCache (posts) so the two blob routes stay kind-isolated. In-memory
    // only; never persisted (decrypt-on-read).
    @Volatile private var dmKeysCache: Map<String, ByteArray> = emptyMap()

    fun handle(method: String, path: String): HttpResponse? {
        if (method != "GET") return null
        return when {
            path == "/api/bootstrap" -> json(bootstrapJson())
            path == "/api/applock" -> json(applockJson())
            path == "/api/state" -> json(state())
            path == "/api/feed" -> json(feed())
            path == "/api/stories" -> json(stories())
            path == "/api/kreds" -> json(kreds())
            path == "/api/conversations" -> json(conversations())
            path.startsWith("/api/dm/") -> {
                val id = path.removePrefix("/api/dm/")
                if (id.isEmpty() || id.contains("/")) notFound() else json(dmThread(id))
            }
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
        keysCache = postKeys(res.feed, res.keys)                // vp1: warm the blob-key cache, POSTS ONLY
        val responses = DecryptPass.responsesPass(store, fx.device_pub, priv, own)
        val now = System.currentTimeMillis() / 1000.0
        val arr = JSONArray()
        for (d in res.feed) {                                  // already newest-first
            if (d.kind != "post") continue                     // journal feed = posts only
            if ((d.placement ?: "journal") != "journal") continue
            // hearth _decrypt_post_row (node.py:1594-1598) drops an expired
            // post before it ever reaches the client -- an expired journal
            // post must never render on the phone either.
            if (!notExpired(d.expiresAt, now)) continue
            arr.put(feedRow(d, own, responses[d.msgId]))
        }
        return arr.toString()
    }

    // vp2: one decrypt pass feeding BOTH the conversations and dm-thread routes.
    // Warms dmKeysCache. Returns null only when the fixture is missing (the
    // routes then serve an empty list, keeping the load-bearing 2xx contract:
    // app.js's j() throws on any non-2xx and refresh() awaits the conversations
    // route every tick). rawDms carries EVERY stored DM (incl. undecryptable);
    // decryptedById is the subset this device could decrypt.
    private data class DmLoad(val msgs: List<DmMsg>, val names: Map<String, String>, val own: String)

    private fun loadDms(): DmLoad? {
        val fx = fixtureOrNull() ?: return null
        val store = SqliteSyncStore(ctx)
        val (priv, _) = EncKeys.getOrCreate(store)
        val own = fx.cert.identity_pub
        val res = DecryptPass.run(store, fx.device_pub, priv, own)
        dmKeysCache = dmKeys(res.feed, res.keys)                 // warm DM blob-key cache
        val decryptedById = res.feed.filter { it.kind == "dm" }.associateBy { it.msgId }
        val rawDms = store.allMessages().filter { it.kind == "dm" }
        val msgs = extractDmMsgs(rawDms, decryptedById, own)
        return DmLoad(msgs, store.profileNames(), own)
    }

    private fun conversations(): String {
        val d = loadDms() ?: return "[]"
        return conversationsJson(conversationsFrom(d.msgs, d.names, d.own))
    }

    private fun dmThread(identityPub: String): String {
        val d = loadDms() ?: return "[]"
        return dmThreadJson(threadFor(d.msgs, identityPub))
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

    // hearth /api/kreds (node.kreds_list, node.py:801-813): known identities
    // (excluding self) as {identity_pub, name, ring, since}. LOAD-BEARING for
    // the journal: app.js's refresh() does `KREDS = await j("/api/kreds")`
    // (app.js:4780) BEFORE renderJournal(), and j() throws on a non-2xx, so a
    // missing /api/kreds aborts the entire journal render (chipbar + feed +
    // stories). ring/since default to "kreds"/0 -- the phone doesn't process
    // KIND_RING yet, so accurate ring membership + the circle view are a later
    // slice; the default is enough to render the journal + a flat chip bar.
    private fun kreds(): String {
        val fx = fixtureOrNull()
        val store = SqliteSyncStore(ctx)
        val names = store.profileNames()
        val own = fx?.cert?.identity_pub ?: ""
        val friends = store.knownIdentities().filter { it != own }.map { it to (names[it] ?: it.take(8)) }
        return kredsJson(friends)
    }

    // hearth stories_view (node.py:836-841) keeps a group only for
    // self/known identities -- an unfriended author's already-synced
    // (unexpired) story must not surface here even though its row still
    // exists in the store.
    private fun stories(): String {
        val fx = fixtureOrNull()
        val store = SqliteSyncStore(ctx)
        val own = fx?.cert?.identity_pub ?: ""
        val now = System.currentTimeMillis() / 1000.0
        val visible = filterVisibleStories(store.activeStories(now), own, store.knownIdentities().toSet())
        return storiesJson(visible, store.profileNames(), own)
    }

    // /api/blob/{h}: raw plaintext-at-rest bytes (avatars/plaintext content),
    // NO content-key decrypt -- mirrors getStoryImage's decrypt-skipping path.
    private fun blob(hash: String): HttpResponse {
        val data = SqliteSyncStore(ctx).getBlob(hash) ?: return notFound()
        return mediaResponse(data)
    }

    // /api/post-blob/{msg_id}/{h}: content-key-decrypted POST blob bytes ONLY,
    // streamed decrypt-on-read -- mirrors hearth's post_blob (node.py:2956-
    // 2964), which does `if msg.kind != KIND_POST: return None` before
    // decrypting. DecryptPass.run's keys map covers both post AND dm kinds
    // (DecryptPass.kt), so both the warm cache (feed()) and this fallback
    // MUST be filtered through postKeys -- otherwise a DM's msgId would
    // decrypt+serve here with the posts-only immutable Cache-Control,
    // leaking DM media through the wrong (unauthenticated-by-kind) route.
    // AVIF is served raw (image/avif) -- the WebView Chromium renderer
    // decodes it (it never has our keys; we hand it already-decrypted
    // plaintext), matching desktop exactly.
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
            val posts = postKeys(res.feed, res.keys)
            keysCache = posts
            key = posts[msgId] ?: return notFound()
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

        // hearth kreds_list (node.py:801-813) row shape: {identity_pub, name,
        // ring, since}. Slice-1 defaults ring="kreds"/since=0 (see kreds()).
        fun kredsJson(friends: List<Pair<String, String>>): String {
            val arr = JSONArray()
            for ((ipub, name) in friends)
                arr.put(JSONObject()
                    .put("identity_pub", ipub)
                    .put("name", name)
                    .put("ring", "kreds")
                    .put("since", 0))
            return arr.toString()
        }

        // vp2 slice 2: one DM thread message, built by joining a RAW stored DM
        // (StoredMsg, which survives even when this device cannot decrypt it)
        // with the msgId->Decrypted map from DecryptPass.run. `undecryptable`
        // is true exactly when the raw DM had no Decrypted entry -- reproducing
        // hearth dm_thread's one-entry-per-stored-row-including-undecryptable
        // behavior. storyRef rides the plaintext outer payload, so it is
        // present even for an undecryptable row (hearth messages.py:139-150).
        data class DmMsg(
            val msgId: String, val fromMe: Boolean, val createdAt: Double, val expiresAt: Double?,
            val text: String?, val blobs: List<String>, val undecryptable: Boolean,
            val storyRef: Map<*, *>?, val partner: String)

        // vp2: one conversation-list summary row (hearth node.conversations()).
        data class ConvRow(
            val identityPub: String, val name: String, val lastText: String?,
            val lastFromMe: Boolean?, val lastAt: Double?, val count: Int)

        // vp2: build the flat DM message list from RAW DMs joined with the
        // decrypted map. Partner derivation mirrors hearth dm_conversations:
        // sender = StoredMsg.identityPub, recipient = payload["to"]; the partner
        // is whichever of {sender, recipient} is NOT own. A DM missing a
        // created_at or a `to` in its plaintext payload is malformed (a valid
        // signed DM always carries both) and is skipped. Stable sort by
        // created_at ASC: same-created_at ties keep allMessages()' scan order,
        // approximating hearth's `created_at ASC, rowid ASC` tiebreak (rowid is
        // not exposed on StoredMsg -- flagged as a follow-up, not fixed here).
        fun extractDmMsgs(
            rawDms: List<StoredMsg>,
            decryptedById: Map<String, DecryptPass.Decrypted>,
            ownIdentityPub: String,
        ): List<DmMsg> =
            rawDms.mapNotNull { m ->
                val createdAt = (m.payload["created_at"] as? Number)?.toDouble() ?: return@mapNotNull null
                val to = m.payload["to"] as? String ?: return@mapNotNull null
                val sender = m.identityPub
                val fromMe = sender == ownIdentityPub
                val partner = if (fromMe) to else sender
                val d = decryptedById[m.msgId]
                DmMsg(
                    msgId = m.msgId,
                    fromMe = fromMe,
                    createdAt = createdAt,
                    expiresAt = (m.payload["expires_at"] as? Number)?.toDouble(),
                    text = d?.text,
                    blobs = d?.blobs ?: emptyList(),
                    undecryptable = d == null,
                    storyRef = m.payload["story_ref"] as? Map<*, *>,
                    partner = partner)
            }.sortedBy { it.createdAt }

        // vp2: the ascending thread for one partner. extractDmMsgs already
        // collapsed both directions onto a single `partner` per message and
        // sorted ASC, so this is a stable filter preserving that order.
        fun threadFor(all: List<DmMsg>, partner: String): List<DmMsg> =
            all.filter { it.partner == partner }

        // vp2: conversation summary rows (hearth node.conversations()). Group by
        // partner (only partners WITH history appear -- friends are unioned in
        // client-side by renderConversations, NOT added here). `last` = the
        // newest message (the ASC list's last element); last_text/last_from_me/
        // last_at come from it, including when it is undecryptable (text null,
        // but from_me/created_at still populated -- matching hearth's unread
        // semantics). count includes undecryptable rows. Sorted by last_at DESC
        // (a null/empty last_at sorts as 0); the stable sort keeps
        // first-appearance order among equal last_at.
        fun conversationsFrom(
            all: List<DmMsg>, names: Map<String, String>, ownIdentityPub: String,
        ): List<ConvRow> {
            val byPartner = linkedMapOf<String, MutableList<DmMsg>>()
            for (m in all) byPartner.getOrPut(m.partner) { mutableListOf() }.add(m)
            return byPartner.map { (partner, msgs) ->
                val last = msgs.last()
                ConvRow(
                    identityPub = partner,
                    name = names[partner] ?: partner.take(8),
                    lastText = last.text,
                    lastFromMe = last.fromMe,
                    lastAt = last.createdAt,
                    count = msgs.size)
            }.sortedByDescending { it.lastAt ?: 0.0 }
        }

        // vp2: hearth node.conversations() row list. last_text/last_from_me/
        // last_at are null only for an empty thread -- which never occurs here
        // (a partner is listed only if it has >=1 message), but the null-coalesce
        // keeps the shape faithful to hearth's field types.
        fun conversationsJson(rows: List<ConvRow>): String {
            val arr = JSONArray()
            for (c in rows) arr.put(JSONObject()
                .put("identity_pub", c.identityPub)
                .put("name", c.name)
                .put("last_text", c.lastText ?: JSONObject.NULL)
                .put("last_from_me", (c.lastFromMe as Boolean?) ?: JSONObject.NULL)
                .put("last_at", c.lastAt ?: JSONObject.NULL)
                .put("count", c.count))
            return arr.toString()
        }

        // vp2: hearth node.dm_thread() flat list (NOT wrapped in {messages,
        // partner}). blobs is always an array ([] when undecryptable, never
        // null). story_ref passes the plaintext outer-payload dict through
        // key-for-key (or null), matching hearth's msg.payload.get("story_ref").
        fun dmThreadJson(msgs: List<DmMsg>): String {
            val arr = JSONArray()
            for (m in msgs) {
                val blobs = JSONArray(); m.blobs.forEach { blobs.put(it) }
                val storyRef: Any = m.storyRef?.let { sr ->
                    JSONObject().also { o -> for ((k, v) in sr) o.put(k.toString(), v ?: JSONObject.NULL) }
                } ?: JSONObject.NULL
                arr.put(JSONObject()
                    .put("msg_id", m.msgId)
                    .put("from_me", m.fromMe)
                    .put("created_at", m.createdAt)
                    .put("expires_at", m.expiresAt ?: JSONObject.NULL)
                    .put("text", m.text ?: JSONObject.NULL)
                    .put("blobs", blobs)
                    .put("undecryptable", m.undecryptable)
                    .put("story_ref", storyRef))
            }
            return arr.toString()
        }

        // hearth post_blob (node.py:2956-2964) serves ONLY KIND_POST blobs
        // (`if msg.kind != KIND_POST: return None`), even though the
        // content-key wrap/decrypt machinery is shared with DMs. DecryptPass.
        // run's Result.keys is keyed by msgId across BOTH kinds -- this
        // narrows it to post-authored keys only, so a DM's msgId can never
        // resolve a key via the /api/post-blob route.
        fun postKeys(feed: List<DecryptPass.Decrypted>, keys: Map<String, ByteArray>): Map<String, ByteArray> =
            feed.filter { it.kind == "post" }.mapNotNull { d -> keys[d.msgId]?.let { d.msgId to it } }.toMap()

        // vp2: hearth dm_blob (node.py:2946-2954) serves ONLY KIND_DM blobs,
        // even though the content-key machinery is shared with posts. Result.
        // keys is keyed by msgId across BOTH kinds -- this narrows it to
        // dm-authored keys only, so a post's msgId can never resolve a key via
        // the dm-blob route (the reverse of postKeys' post-only narrowing).
        // Added here (ahead of Task 3's dmBlob route) because loadDms() -- this
        // task's own decrypt pass -- must warm dmKeysCache; the route consuming
        // the cache is Task 3's, not this task's.
        fun dmKeys(feed: List<DecryptPass.Decrypted>, keys: Map<String, ByteArray>): Map<String, ByteArray> =
            feed.filter { it.kind == "dm" }.mapNotNull { d -> keys[d.msgId]?.let { d.msgId to it } }.toMap()

        // hearth stories_view (node.py:836-841): a story group is visible
        // only for self or a known (friended) identity -- an unfriended
        // author's already-synced, unexpired story row must not surface
        // through /api/stories even though the row still exists locally.
        fun filterVisibleStories(stories: List<StoredStory>, own: String, known: Set<String>): List<StoredStory> =
            stories.filter { it.author == own || it.author in known }

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

        // hearth _decrypt_post_row (node.py:1594-1598): `if p.get("expires_at")
        // is not None and p["expires_at"] <= now: return None` -- keep a post
        // iff it has no expiry, or its expiry is strictly in the future.
        // Exactly-equal-to-now is treated as expired (`<=`, not `<`), matching
        // hearth's own boundary.
        fun notExpired(expiresAt: Double?, now: Double): Boolean = expiresAt == null || expiresAt > now

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
