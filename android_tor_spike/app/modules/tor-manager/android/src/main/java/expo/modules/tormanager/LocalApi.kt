package expo.modules.tormanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Read-only /api provider for the WebView shell (slice 1). Pure JSON
 *  builders live in the companion (JVM-testable); the instance methods do the
 *  Android store I/O over a SINGLE shared SqliteSyncStore (see `sharedStore`).
 *  Token is already validated by LocalWebServer before handle() runs. */
class LocalApi(private val ctx: Context) {

    // ONE store for every request. SqliteSyncStore is a SQLiteOpenHelper --
    // designed to be a long-lived singleton whose internal connection pool
    // serves concurrent reads. Constructing a fresh one per request (the old
    // pattern) leaked a SQLiteConnection each time ("SQLiteConnection ... was
    // leaked!"); a profile wall fires ~20 concurrent /api/post-blob requests,
    // so the pool got exhausted mid-burst and later (larger, lower-in-the-wall)
    // images failed to serve -- their requests threw before responding, so
    // they showed broken with no server-side trace. A shared helper is both
    // the leak fix and the correct SQLiteOpenHelper usage. Lazy + thread-safe:
    // first request opens it, all others reuse it.
    private val sharedStore: SqliteSyncStore by lazy { SqliteSyncStore(ctx) }

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

    // contentType/body: accepted here (Task 6, loopback body-reading plumbing)
    // but IGNORED -- every route below is still GET-only. Task 7 wires the
    // compose POST route and starts reading them.
    fun handle(method: String, path: String, contentType: String? = null, body: ByteArray? = null): HttpResponse? {
        if (method != "GET") return null
        return when {
            path == "/api/bootstrap" -> json(bootstrapJson())
            path == "/api/applock" -> json(applockJson())
            path == "/api/state" -> json(state())
            path == "/api/feed" -> json(feed())
            path == "/api/stories" -> json(stories())
            path == "/api/kreds" -> json(kreds())
            path.startsWith("/api/profile/") -> {
                val id = path.removePrefix("/api/profile/")
                if (id.isEmpty() || id.contains("/")) notFound()
                else profile(id)?.let { json(it) } ?: notFound()
            }
            path == "/api/conversations" -> json(conversations())
            // vp2 Task 3: placed ahead of the /api/dm/ branch so the longer
            // prefix wins clearly, though there is no actual collision --
            // "/api/dm-blob/..." never starts with "/api/dm/" (the char after
            // "dm" is "-", not "/"); ordering here is for readability only.
            path.startsWith("/api/dm-blob/") -> {
                val seg = path.removePrefix("/api/dm-blob/").split("/")
                if (seg.size != 2 || seg[0].isEmpty() || seg[1].isEmpty()) notFound() else dmBlob(seg[0], seg[1])
            }
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
        val store = sharedStore
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

    // vp3: the profile route body. Returns null in exactly hearth's 404 cases
    // (node.py:1262-1265): no fixture; identity is neither own nor known; or
    // identity is known-but-not-own with no stored profile record. A null
    // return -> the server 404s -> app.js's openProfile try/catch degrades to
    // fallbackProfile (it does NOT blank the page). When identity == own with
    // no record, hearth's hardcoded default is used instead of 404ing.
    private fun profile(identityPub: String): String? {
        val fx = fixtureOrNull() ?: return null
        val store = sharedStore
        val own = fx.cert.identity_pub
        val isOwn = identityPub == own
        if (!isOwn && identityPub !in store.knownIdentities()) return null
        val names = store.profileNames()
        val record = store.profileRecord(identityPub)
            ?: (if (isOwn) defaultProfileRecord(names[own] ?: own.take(8)) else return null)
        val (priv, _) = EncKeys.getOrCreate(store)
        val res = DecryptPass.run(store, fx.device_pub, priv, own)
        keysCache = postKeys(res.feed, res.keys)                 // warm blob cache (same as feed())
        val responses = DecryptPass.responsesPass(store, fx.device_pub, priv, own)
        val now = System.currentTimeMillis() / 1000.0
        // res.feed is already newest-first; both filters preserve that order.
        val wallPosts = res.feed.filter {
            it.kind == "post" && it.identityPub == identityPub &&
                it.placement == "profile" && notExpired(it.expiresAt, now)
        }
        val railPosts = res.feed.filter {
            it.kind == "post" && it.identityPub == identityPub &&
                (it.placement ?: "journal") == "journal" && notExpired(it.expiresAt, now)
        }
        val wall = wallJson(wallPosts, store.profileLayout(identityPub), store.albums(identityPub), own, isOwn)
        val journal = JSONArray()
        for (d in railPosts) journal.put(feedRow(d, own, responses[d.msgId]))
        // vp3: ring/since are a DELIBERATE dashboard simplification (same as the
        // kreds() route): the phone doesn't process KIND_RING yet, so a friend's
        // ring is hardcoded "kreds" and since is 0 (falsy -> app.js omits the
        // "since <month year>" line). Own profile is exact (hearth also uses
        // ("kreds", None) for self). Real ring membership needs new rings()/
        // ring_since() SyncStore accessors -- a later slice (report ticket T3).
        val since: Any? = if (isOwn) null else 0
        return profileJson(record, identityPub, isOwn, "kreds", since, wall, journal)
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
        val store = sharedStore
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
        val store = sharedStore
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
        val store = sharedStore
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
        val store = sharedStore
        val own = fx?.cert?.identity_pub ?: ""
        val now = System.currentTimeMillis() / 1000.0
        val visible = filterVisibleStories(store.activeStories(now), own, store.knownIdentities().toSet())
        return storiesJson(visible, store.profileNames(), own)
    }

    // /api/blob/{h}: raw plaintext-at-rest bytes (avatars/plaintext content),
    // NO content-key decrypt -- mirrors getStoryImage's decrypt-skipping path.
    private fun blob(hash: String): HttpResponse {
        val data = sharedStore.getBlob(hash) ?: return notFound()
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
        val store = sharedStore
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

    // vp2: content-key-decrypted DM blob bytes ONLY, streamed decrypt-on-read.
    // Mirrors postBlob's cache+fallback but keyed off dmKeysCache (DM-only), so
    // a post's msgId is never decryptable here (kind gate). The response OMITS
    // Cache-Control -- hearth's dm_blob route sets NONE (contrast post_blob/
    // blob, which set immutable) -- so it uses dmMediaResponse, not
    // mediaResponse (which hardcodes the immutable Cache-Control).
    private fun dmBlob(msgId: String, hash: String): HttpResponse {
        val store = sharedStore
        var key = dmKeysCache[msgId]
        if (key == null) {
            val fx = fixtureOrNull() ?: return notFound()
            val (priv, _) = EncKeys.getOrCreate(store)
            val res = DecryptPass.run(store, fx.device_pub, priv, fx.cert.identity_pub)
            val dms = dmKeys(res.feed, res.keys)
            dmKeysCache = dms
            key = dms[msgId] ?: return notFound()
        }
        val cipher = store.getBlob(hash) ?: return notFound()
        val plain = KotlinBlobCrypt.decryptBlob(key, cipher) ?: return notFound()
        return dmMediaResponse(plain)
    }

    // vp2: DM blob response headers -- Content-Type (sniffed) + nosniff, and
    // deliberately NO Cache-Control (hearth dm_blob sets none; do not reuse
    // mediaResponse's immutable header for a DM).
    private fun dmMediaResponse(bytes: ByteArray) = HttpResponse(200, mapOf(
        "Content-Type" to sniff(bytes),
        "X-Content-Type-Options" to "nosniff"), bytes)

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

        // vp3: text_style defaults = each TEXT_STYLE_ENUMS tuple's first value
        // (messages.py:30-37) plus color="default" (node.py profile_view sets
        // it separately). A pure-text wall block's text_style is these defaults
        // merged with the layout's per-block override (layout.texts[msgId]).
        val TEXT_STYLE_DEFAULTS = linkedMapOf(
            "h" to "left", "v" to "top", "size" to "auto",
            "font" to "sans", "weight" to "normal", "style" to "normal",
            "color" to "default")

        // vp3: a plain Kotlin map -> JSONObject (null values -> JSON null).
        fun mapToJson(m: Map<String, Any?>): JSONObject {
            val o = JSONObject()
            for ((k, v) in m) o.put(k, v ?: JSONObject.NULL)
            return o
        }

        // vp3: port of hearth _fold_album_members (node.py:1178-1195). For each
        // album_id in SORTED order, the first album to claim a member keeps it
        // (Python setdefault) -- so the smallest album_id wins a member that two
        // albums list. An explicit `!in` check (not Map.putIfAbsent, which is
        // API 24+) keeps this min-SDK-safe. Returns member msgId -> album_id.
        fun foldAlbumMembers(albums: Map<String, List<String>>): Map<String, String> {
            val memberOf = linkedMapOf<String, String>()
            for (aid in albums.keys.sorted())
                for (mid in albums[aid] ?: emptyList())
                    if (mid !in memberOf) memberOf[mid] = aid
            return memberOf
        }

        // vp3: port of hearth profile_view's _default_span (node.py:1272-1279).
        // size = sizes[msgId] or "full"; small -> 1x1; wide -> 2x2; full ->
        // 4x2 when the post has media (blobs non-empty OR media=="video"),
        // else 4x1.
        fun defaultSpan(d: DecryptPass.Decrypted, sizes: Map<String, String>): JSONObject {
            val size = sizes[d.msgId] ?: "full"
            if (size == "small") return JSONObject().put("w", 1).put("h", 1)
            if (size == "wide") return JSONObject().put("w", 2).put("h", 2)
            val hasMedia = d.blobs.isNotEmpty() || d.media == "video"
            return if (hasMedia) JSONObject().put("w", 4).put("h", 2)
                   else JSONObject().put("w", 4).put("h", 1)
        }

        // vp3: a post wall-block = the feedRow shape + pin/span/text_style
        // (node.py profile_view's per-post annotation loop). responses is null
        // on wall rows (renderBlock never reads it). pin = layout.pins[msgId]
        // or null; span = (pin ? {pin.w,pin.h} : layout.spans[msgId] ?:
        // defaultSpan); text_style only on pure-text blocks (no blobs, not
        // video), defaults merged with layout.texts[msgId].
        fun wallBlockJson(d: DecryptPass.Decrypted, ownIdentityPub: String, layout: ProfileLayout): JSONObject {
            val o = feedRow(d, ownIdentityPub, null)
            val pin = layout.pins[d.msgId]
            o.put("pin", if (pin != null) mapToJson(pin) else JSONObject.NULL)
            val span: JSONObject = when {
                pin != null -> JSONObject().put("w", pin["w"]).put("h", pin["h"])
                layout.spans[d.msgId] != null -> mapToJson(layout.spans[d.msgId]!!)
                else -> defaultSpan(d, layout.sizes)
            }
            o.put("span", span)
            if (d.blobs.isEmpty() && d.media != "video") {
                val ts = JSONObject()
                for ((k, v) in TEXT_STYLE_DEFAULTS) ts.put(k, v)
                layout.texts[d.msgId]?.let { for ((k, v) in it) ts.put(k, v ?: JSONObject.NULL) }
                o.put("text_style", ts)
            }
            return o
        }

        // vp3: an album pseudo-block (node.py profile_view's album-fold append)
        // -- a REDUCED shape, NOT the post fields. count = photos.length().
        fun albumBlockJson(
            albumId: String, mine: Boolean, photos: JSONArray, createdAt: Double,
            scopeNewest: String, pin: Map<String, Any?>?, span: JSONObject,
        ): JSONObject = JSONObject()
            .put("album", true)
            .put("msg_id", albumId)
            .put("mine", mine)
            .put("photos", photos)
            .put("count", photos.length())
            .put("created_at", createdAt)
            .put("scope_newest", scopeNewest)
            .put("pin", if (pin != null) mapToJson(pin) else JSONObject.NULL)
            .put("span", span)

        // vp3: the whole wall list, reproducing node.py profile_view's
        // annotate-then-album-fold. `wall` is this identity's placement==
        // "profile" posts, ALREADY newest-first. Posts whose msgId is an album
        // member are removed; each album with >=1 photo becomes an album-block
        // (photos = {m,h,t} per member's blobs, video members skipped;
        // created_at = newest member's; scope_newest = newest member's scope;
        // span defaults to 2x2). The folded list is re-sorted created_at DESC
        // (stable -> ties keep wall order).
        fun wallJson(
            wall: List<DecryptPass.Decrypted>, layout: ProfileLayout,
            albums: Map<String, List<String>>, ownIdentityPub: String, mine: Boolean,
        ): JSONArray {
            val byId = wall.associateBy { it.msgId }
            val memberOf = foldAlbumMembers(albums)
            val folded = mutableListOf<Pair<Double, JSONObject>>()
            for (d in wall)
                if (d.msgId !in memberOf)
                    folded.add(d.createdAt to wallBlockJson(d, ownIdentityPub, layout))
            for (aid in albums.keys.sorted()) {
                val photos = JSONArray()
                var newest: Double? = null
                var scopeNewest = "kreds"
                for (mid in albums[aid] ?: emptyList()) {
                    val p = byId[mid] ?: continue
                    if (memberOf[mid] != aid) continue
                    if (p.media == "video") continue
                    for ((i, h) in p.blobs.withIndex())
                        photos.put(JSONObject().put("m", mid).put("h", h)
                            .put("t", p.thumbs.getOrNull(i) ?: JSONObject.NULL))
                    val cur = newest
                    if (cur == null || p.createdAt > cur) {
                        newest = p.createdAt
                        scopeNewest = p.scope ?: "kreds"
                    }
                }
                if (photos.length() == 0) continue
                val at = newest ?: continue
                val pin = layout.pins[aid]
                val span: JSONObject = when {
                    pin != null -> JSONObject().put("w", pin["w"]).put("h", pin["h"])
                    layout.spans[aid] != null -> mapToJson(layout.spans[aid]!!)
                    else -> JSONObject().put("w", 2).put("h", 2)
                }
                folded.add(at to albumBlockJson(aid, mine, photos, at, scopeNewest, pin, span))
            }
            val out = JSONArray()
            for ((_, obj) in folded.sortedByDescending { it.first }) out.put(obj)
            return out
        }

        // vp3: hearth's hardcoded own-profile default (node.py:1266-1270) used
        // when identity == own and NO KIND_PROFILE record exists yet -- so the
        // own profile always renders something instead of 404ing. name =
        // profileNames[own] or own[:8] (the caller supplies the resolved name).
        fun defaultProfileRecord(name: String): Map<String, Any?> = linkedMapOf(
            "name" to name, "bio" to "", "accent" to "#2743d6", "avatar" to null,
            "avatar_shape" to "circle", "avatar_size" to "m", "avatar_align" to "left",
            "banner" to null, "banner_pos" to 50)

        // vp3: the profile route's top-level JSON (node.py profile_view's
        // return). The nine display fields come from `record` (selected BY
        // NAME so a record's incidental kind/created_at keys never leak into
        // the response), each with hearth's default as a fallback; then
        // identity_pub/mine/ring/since/wall/journal. `since` is null (own) or
        // an Int (others); pass JSONObject.NULL-safe via `since ?: NULL`.
        fun profileJson(
            record: Map<String, Any?>, identityPub: String, mine: Boolean,
            ring: String, since: Any?, wall: JSONArray, journal: JSONArray,
        ): String = JSONObject()
            .put("name", record["name"] ?: "")
            .put("bio", record["bio"] ?: "")
            .put("accent", record["accent"] ?: "#2743d6")
            .put("avatar", record["avatar"] ?: JSONObject.NULL)
            .put("avatar_shape", record["avatar_shape"] ?: "circle")
            .put("avatar_size", record["avatar_size"] ?: "m")
            .put("avatar_align", record["avatar_align"] ?: "left")
            .put("banner", record["banner"] ?: JSONObject.NULL)
            .put("banner_pos", record["banner_pos"] ?: 50)
            .put("identity_pub", identityPub)
            .put("mine", mine)
            .put("ring", ring)
            .put("since", since ?: JSONObject.NULL)
            .put("wall", wall)
            .put("journal", journal)
            .toString()

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
