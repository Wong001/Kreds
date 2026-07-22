package expo.modules.tormanager

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalApiTest {
    private fun sampleDecrypted(mine: Boolean) = DecryptPass.Decrypted(
        msgId = "m1", kind = "post", author = "Cara", text = "hello",
        createdAt = 1784568399.5, blobs = listOf("b1"), thumbs = listOf<String?>("t1"),
        media = "photo", poster = null, storyRefMediaHash = null,
        identityPub = if (mine) "own" else "cara", scope = "kreds",
        expiresAt = null, placement = "journal", codec = null)

    @Test fun feedRowMatchesHearthFieldSet() {
        val o = LocalApi.feedRow(sampleDecrypted(mine = true), ownIdentityPub = "own", responses = null)
        val keys = o.keys().asSequence().toSet()
        val expected = setOf(
            "msg_id", "identity_pub", "author_name", "author_avatar", "text", "blobs",
            "scope", "created_at", "expires_at", "mine", "placement", "media",
            "poster", "codec", "thumbs", "responses")
        assertEquals(expected, keys)
        assertEquals("m1", o.getString("msg_id"))
        assertEquals("own", o.getString("identity_pub"))
        assertEquals("Cara", o.getString("author_name"))
        assertTrue(o.isNull("author_avatar"))
        assertEquals("kreds", o.getString("scope"))
        assertEquals("journal", o.getString("placement"))
        assertTrue(o.getBoolean("mine"))
        assertTrue(o.isNull("responses"))
        assertEquals("b1", o.getJSONArray("blobs").getString(0))
        assertEquals("t1", o.getJSONArray("thumbs").getString(0))
    }

    @Test fun feedRowMineIsFalseForOtherAuthor() {
        val o = LocalApi.feedRow(sampleDecrypted(mine = false), ownIdentityPub = "own", responses = null)
        assertFalse(o.getBoolean("mine"))
        assertEquals("cara", o.getString("identity_pub"))
    }

    @Test fun feedRowResponsesShape() {
        val resp = KotlinResponses.Responses(
            reactions = linkedMapOf("heart" to 2),
            comments = listOf(
                KotlinResponses.Comment(
                    body = "nice", display = "Quiet Fox", aliasColor = 180, createdAt = 1.0,
                    alias = true, aliasSeed = "aabbccdd", name = null)))
        val o = LocalApi.feedRow(sampleDecrypted(mine = false), ownIdentityPub = "own", responses = resp)
        val r = o.getJSONObject("responses")
        assertEquals(setOf("reactions", "my_reaction", "comments", "can_moderate"), r.keys().asSequence().toSet())
        assertTrue(r.isNull("my_reaction"))
        assertFalse(r.getBoolean("can_moderate"))
        assertEquals(2, r.getJSONObject("reactions").getInt("heart"))
        val c = r.getJSONArray("comments").getJSONObject(0)
        assertEquals(setOf("name", "avatar", "alias", "alias_seed", "mine", "body", "created_at"),
            c.keys().asSequence().toSet())
        assertTrue(c.isNull("name"))          // alias == true -> name null
        assertTrue(c.isNull("avatar"))
        assertTrue(c.getBoolean("alias"))
        assertEquals("aabbccdd", c.getString("alias_seed"))
        assertFalse(c.getBoolean("mine"))
        assertEquals("nice", c.getString("body"))
        // hearth omits `responder` for an unresolved (alias) comment
        // (node.py:1586-1588's `if resolved:`) -- must be ABSENT, not null.
        assertFalse(c.has("responder"))
    }

    @Test fun feedRowResolvedCommentIncludesResponderAndBareIdentityName() {
        // Bug fix (coordinator review of Task 4): a RESOLVED (non-alias)
        // comment must carry `responder` (hearth node.py:1586-1588) so
        // web/app.js:621's `identityColor(c.responder)` doesn't get an
        // undefined -> hsl(NaN...) avatar color. `name` here is hearth's own
        // bare-identity-prefix fallback (node.py:1577), computed independently
        // of KotlinResponses' "friend-"-prefixed `display` value.
        val resp = KotlinResponses.Responses(
            reactions = emptyMap(),
            comments = listOf(
                KotlinResponses.Comment(
                    body = "nice", display = "Cara", aliasColor = null, createdAt = 1.0,
                    alias = false, aliasSeed = "aabbccdd", name = "Cara", responder = "cara_identity")))
        val o = LocalApi.feedRow(sampleDecrypted(mine = false), ownIdentityPub = "own", responses = resp)
        val r = o.getJSONObject("responses")
        val c = r.getJSONArray("comments").getJSONObject(0)
        assertEquals(
            setOf("name", "avatar", "alias", "alias_seed", "mine", "body", "created_at", "responder"),
            c.keys().asSequence().toSet())
        assertEquals("cara_identity", c.getString("responder"))
        assertEquals("Cara", c.getString("name"))
        assertFalse(c.getBoolean("alias"))
    }

    @Test fun notExpiredMatchesHearthBoundary() {
        // hearth _decrypt_post_row (node.py:1594-1598): drop a post iff
        // expires_at is present AND <= now. So keep iff no expiry, or expiry
        // is strictly in the future; exactly-equal-to-now is EXPIRED (`<=`,
        // not `<`). feed() itself is instance/on-device (needs a real store +
        // fixture); this pure helper is the JVM-testable unit it delegates to.
        val now = 1784568399.5
        assertTrue("no expiry -> never expires", LocalApi.notExpired(null, now))
        assertTrue("future expiry -> not yet expired", LocalApi.notExpired(now + 100.0, now))
        assertFalse("past expiry -> expired", LocalApi.notExpired(now - 100.0, now))
        assertFalse("exactly == now -> expired (hearth's <=, not <)", LocalApi.notExpired(now, now))
    }

    @Test fun bootstrapStubShape() {
        val o = JSONObject(LocalApi.bootstrapJson())
        assertTrue(o.getBoolean("initialized"))
        assertTrue(o.getBoolean("onboarding_done"))
    }

    @Test fun applockStubShape() {
        val o = JSONObject(LocalApi.applockJson())
        assertFalse(o.getBoolean("enabled"))
        assertFalse(o.getBoolean("locked"))
        assertTrue(o.isNull("cred_type"))
        val s = o.getJSONObject("settings")
        assertEquals(0, s.getInt("idle_minutes"))
        assertFalse(s.getBoolean("lock_on_sleep"))
        assertEquals(0, o.getInt("throttle_wait"))
    }

    @Test fun kredsJsonRowShape() {
        val arr = org.json.JSONArray(LocalApi.kredsJson(listOf("cc" to "Cara", "dd" to "dd")))
        assertEquals(2, arr.length())
        val r0 = arr.getJSONObject(0)
        assertEquals(setOf("identity_pub", "name", "ring", "since"), r0.keys().asSequence().toSet())
        assertEquals("cc", r0.getString("identity_pub"))
        assertEquals("Cara", r0.getString("name"))
        assertEquals("kreds", r0.getString("ring"))
        assertEquals(0, r0.getInt("since"))
    }

    @Test fun stateShapeHasAllKeysAndReadonly() {
        val json = LocalApi.stateJson(
            identityPub = "aa", devicePub = "bb", deviceName = "phone",
            profileName = "Me", friends = listOf("cc" to "Cara", "dd" to "dd"))
        val o = JSONObject(json)
        assertEquals("aa", o.getString("identity_pub"))
        assertEquals("bb", o.getString("device_pub"))
        assertEquals("phone", o.getString("device_name"))
        assertEquals("Me", o.getString("profile_name"))
        assertTrue(o.getBoolean("readonly"))
        assertFalse(o.getBoolean("revoked"))
        assertEquals("#2743d6", o.getString("accent"))
        assertEquals(2, o.getJSONArray("friends").length())
        assertEquals("cc", o.getJSONArray("friends").getJSONObject(0).getString("identity_pub"))
        assertEquals("Cara", o.getJSONArray("friends").getJSONObject(0).getString("name"))
        // keys hearth's /api/state always emits (peers/disconnected/devices present as arrays)
        assertTrue(o.has("peers")); assertTrue(o.has("disconnected")); assertTrue(o.has("devices"))
        val us = o.getJSONObject("update_status")
        assertFalse(us.getBoolean("available")); assertTrue(us.isNull("kind")); assertTrue(us.isNull("version"))
    }

    // -- Task 5: sniff + storiesJson --

    @Test fun sniffMagicBytes() {
        assertEquals("image/png", LocalApi.sniff(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0)))
        assertEquals("image/jpeg", LocalApi.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0)))
        assertEquals("image/gif", LocalApi.sniff("GIF89a".toByteArray()))
        assertEquals("image/webp", LocalApi.sniff("RIFF....WEBP".toByteArray()))
        assertEquals("application/octet-stream", LocalApi.sniff("zzzz".toByteArray()))
    }

    @Test fun sniffFtypAvifVsMp4() {
        // bytes[4:8] == "ftyp"; bytes[8:12] brand decides avif vs mp4
        val avif = ByteArray(16); "xxxx".toByteArray().copyInto(avif, 0)
        "ftyp".toByteArray().copyInto(avif, 4); "avif".toByteArray().copyInto(avif, 8)
        assertEquals("image/avif", LocalApi.sniff(avif))
        val mp4 = ByteArray(16); "xxxx".toByteArray().copyInto(mp4, 0)
        "ftyp".toByteArray().copyInto(mp4, 4); "isom".toByteArray().copyInto(mp4, 8)
        assertEquals("video/mp4", LocalApi.sniff(mp4))
    }

    @Test fun storiesJsonGroupsByAuthorSelfFirst() {
        val stories = listOf(
            StoredStory("s1", "cara", "photo", "h1", null, "cap1", 10.0),
            StoredStory("s2", "own", "video", "h2", "p2", "cap2", 20.0),
            StoredStory("s3", "cara", "photo", "h3", null, "cap3", 30.0))
        val json = LocalApi.storiesJson(stories, mapOf("cara" to "Cara"), ownIdentityPub = "own")
        val arr = org.json.JSONArray(json)
        // self ("own") first
        assertEquals("own", arr.getJSONObject(0).getString("identity_pub"))
        assertTrue(arr.getJSONObject(0).getBoolean("mine"))
        val cara = arr.getJSONObject(1)
        assertEquals("cara", cara.getString("identity_pub"))
        assertFalse(cara.getBoolean("mine"))
        assertEquals("Cara", cara.getString("name"))
        assertTrue(cara.isNull("avatar"))
        // items OLDEST-first within a group (confirmed against hearth
        // store.py's active_stories(): "SELECT ... ORDER BY created_at ASC"
        // appended in that scan order, with no reversal -- this is a
        // story-viewer playback order (oldest to newest), NOT the
        // newest-first order an unconfirmed reading of the shape might
        // assume). s1 (created_at 10.0) precedes s3 (created_at 30.0).
        val items = cara.getJSONArray("items")
        assertEquals("s1", items.getJSONObject(0).getString("msg_id"))
        assertEquals("s3", items.getJSONObject(1).getString("msg_id"))
        assertEquals(setOf("msg_id", "media_kind", "media", "poster", "caption", "created_at"),
            items.getJSONObject(0).keys().asSequence().toSet())
    }

    // -- coordinator review fixes: post-blob kind gate + stories known-identity filter --

    @Test fun postKeysExcludesDmIncludesPost() {
        fun decrypted(msgId: String, kind: String) = DecryptPass.Decrypted(
            msgId = msgId, kind = kind, author = "Cara", text = "hi", createdAt = 1.0,
            blobs = listOf("b1"), thumbs = listOf<String?>(null),
            media = "photo", poster = null, storyRefMediaHash = null,
            identityPub = "cara", scope = "kreds", expiresAt = null,
            placement = "journal", codec = null)
        val post = decrypted("p1", "post")
        val dm = decrypted("d1", "dm")
        val keys = mapOf("p1" to byteArrayOf(1, 2, 3), "d1" to byteArrayOf(4, 5, 6))
        val result = LocalApi.postKeys(listOf(post, dm), keys)
        assertEquals(setOf("p1"), result.keys)
        assertArrayEquals(byteArrayOf(1, 2, 3), result["p1"])
    }

    @Test fun filterVisibleStoriesKeepsSelfAndKnownDropsUnknown() {
        val stories = listOf(
            StoredStory("s1", "own", "photo", "h1", null, "cap1", 10.0),
            StoredStory("s2", "known", "photo", "h2", null, "cap2", 20.0),
            StoredStory("s3", "unknown", "photo", "h3", null, "cap3", 30.0))
        val visible = LocalApi.filterVisibleStories(stories, own = "own", known = setOf("known"))
        assertEquals(setOf("s1", "s2"), visible.map { it.msgId }.toSet())
    }

    // ---- slice 2 (vp2) Task 1: pure DM grouping ----

    // A raw stored DM: sender=identityPub, payload carries plaintext outer
    // fields (to / created_at / expires_at / story_ref) present regardless of
    // whether THIS device can decrypt the body.
    private fun rawDm(
        msgId: String, sender: String, to: String, createdAt: Double,
        expiresAt: Double? = null, storyRef: Map<String, Any?>? = null,
    ): StoredMsg {
        val p = HashMap<String, Any?>()
        p["to"] = to; p["created_at"] = createdAt
        if (expiresAt != null) p["expires_at"] = expiresAt
        if (storyRef != null) p["story_ref"] = storyRef
        return StoredMsg(msgId, "dm", sender, p)
    }

    // A decrypted-join entry for a DM (only msgId/kind/text/blobs are read by
    // the grouping join; the rest are incidental constructor args).
    private fun decDm(msgId: String, text: String, blobs: List<String> = emptyList()) =
        DecryptPass.Decrypted(
            msgId = msgId, kind = "dm", author = "x", text = text, createdAt = 0.0,
            blobs = blobs, thumbs = emptyList(), media = "photo", poster = null,
            storyRefMediaHash = null)

    @Test fun extractDmMsgsDerivesPartnerAndFromMe() {
        val raw = listOf(
            rawDm("m1", sender = "own", to = "cara", createdAt = 10.0),   // I sent
            rawDm("m2", sender = "cara", to = "own", createdAt = 20.0))   // I received
        val dec = mapOf("m1" to decDm("m1", "hi"), "m2" to decDm("m2", "yo"))
        val out = LocalApi.extractDmMsgs(raw, dec, ownIdentityPub = "own")
        val m1 = out.first { it.msgId == "m1" }
        val m2 = out.first { it.msgId == "m2" }
        assertEquals("cara", m1.partner); assertTrue(m1.fromMe)
        assertEquals("cara", m2.partner); assertFalse(m2.fromMe)
    }

    @Test fun extractDmMsgsKeepsUndecryptableRow() {
        val raw = listOf(rawDm("m1", sender = "cara", to = "own", createdAt = 10.0))
        val out = LocalApi.extractDmMsgs(raw, emptyMap(), ownIdentityPub = "own")
        assertEquals(1, out.size)
        assertTrue(out[0].undecryptable)
        assertNull(out[0].text)
        assertTrue(out[0].blobs.isEmpty())
    }

    @Test fun extractDmMsgsOrdersByCreatedAtAscStable() {
        val raw = listOf(
            rawDm("late", sender = "own", to = "cara", createdAt = 30.0),
            rawDm("tieA", sender = "own", to = "cara", createdAt = 10.0),
            rawDm("tieB", sender = "cara", to = "own", createdAt = 10.0),
            rawDm("mid", sender = "cara", to = "own", createdAt = 20.0))
        val dec = raw.associate { it.msgId to decDm(it.msgId, "t") }
        val out = LocalApi.extractDmMsgs(raw, dec, ownIdentityPub = "own")
        // created_at asc; the two created_at==10 ties keep input (scan) order.
        assertEquals(listOf("tieA", "tieB", "mid", "late"), out.map { it.msgId })
    }

    @Test fun conversationsFromPicksNewestAsLastAndCountsUndecryptable() {
        val raw = listOf(
            rawDm("m1", sender = "own", to = "cara", createdAt = 10.0),
            rawDm("m2", sender = "cara", to = "own", createdAt = 30.0),   // newest, undecryptable
            rawDm("m3", sender = "cara", to = "own", createdAt = 20.0))
        val dec = mapOf("m1" to decDm("m1", "hi"), "m3" to decDm("m3", "middle"))
        val msgs = LocalApi.extractDmMsgs(raw, dec, ownIdentityPub = "own")
        val rows = LocalApi.conversationsFrom(msgs, mapOf("cara" to "Cara"), ownIdentityPub = "own")
        assertEquals(1, rows.size)
        val c = rows[0]
        assertEquals("cara", c.identityPub)
        assertEquals("Cara", c.name)
        assertEquals(3, c.count)                 // includes the undecryptable m2
        assertNull(c.lastText)                   // newest (m2) is undecryptable -> null text
        assertFalse(c.lastFromMe!!)              // m2 was received
        assertEquals(30.0, c.lastAt!!, 0.0)      // newest created_at
    }

    @Test fun conversationsFromSortsByLastAtDesc() {
        val raw = listOf(
            rawDm("a1", sender = "own", to = "alice", createdAt = 5.0),
            rawDm("b1", sender = "own", to = "bob", createdAt = 50.0))
        val dec = raw.associate { it.msgId to decDm(it.msgId, "t") }
        val msgs = LocalApi.extractDmMsgs(raw, dec, ownIdentityPub = "own")
        val rows = LocalApi.conversationsFrom(msgs, emptyMap(), ownIdentityPub = "own")
        assertEquals(listOf("bob", "alice"), rows.map { it.identityPub })  // bob newer -> first
        // no profile name -> first-8 fallback (identity strings here are short)
        assertEquals("bob", rows[0].name)
    }

    // ---- slice 2 (vp2) Task 2: golden JSON shapes ----

    @Test fun conversationsJsonGoldenShape() {
        val rows = listOf(
            LocalApi.Companion.ConvRow("cara", "Cara", lastText = null, lastFromMe = false, lastAt = 30.0, count = 3),
            LocalApi.Companion.ConvRow("bob", "bob01234", lastText = "hey", lastFromMe = true, lastAt = 10.0, count = 1))
        val arr = org.json.JSONArray(LocalApi.conversationsJson(rows))
        assertEquals(2, arr.length())
        val c0 = arr.getJSONObject(0)
        assertEquals(setOf("identity_pub", "name", "last_text", "last_from_me", "last_at", "count"),
            c0.keys().asSequence().toSet())
        assertEquals("cara", c0.getString("identity_pub"))
        assertEquals("Cara", c0.getString("name"))
        assertTrue(c0.isNull("last_text"))            // undecryptable newest -> null
        assertFalse(c0.getBoolean("last_from_me"))
        assertEquals(30.0, c0.getDouble("last_at"), 0.0)
        assertEquals(3, c0.getInt("count"))
        val c1 = arr.getJSONObject(1)
        assertEquals("hey", c1.getString("last_text"))
        assertTrue(c1.getBoolean("last_from_me"))
    }

    @Test fun dmThreadJsonGoldenShape() {
        val msgs = listOf(
            LocalApi.Companion.DmMsg(
                msgId = "m1", fromMe = true, createdAt = 10.0, expiresAt = null,
                text = "hi", blobs = listOf("b1"), undecryptable = false,
                storyRef = null, partner = "cara"),
            LocalApi.Companion.DmMsg(
                msgId = "m2", fromMe = false, createdAt = 20.0, expiresAt = 99.0,
                text = null, blobs = emptyList(), undecryptable = true,
                storyRef = mapOf("story_id" to "s9", "media_hash" to "ab12"),
                partner = "cara"))
        val arr = org.json.JSONArray(LocalApi.dmThreadJson(msgs))
        assertEquals(2, arr.length())
        val a = arr.getJSONObject(0)
        assertEquals(setOf("msg_id", "from_me", "created_at", "expires_at", "text",
            "blobs", "undecryptable", "story_ref"), a.keys().asSequence().toSet())
        assertEquals("m1", a.getString("msg_id"))
        assertTrue(a.getBoolean("from_me"))
        assertEquals(10.0, a.getDouble("created_at"), 0.0)
        assertTrue(a.isNull("expires_at"))
        assertEquals("hi", a.getString("text"))
        assertEquals("b1", a.getJSONArray("blobs").getString(0))
        assertFalse(a.getBoolean("undecryptable"))
        assertTrue(a.isNull("story_ref"))
        // undecryptable row: text null, blobs empty array (not null), story_ref
        // passed through as a dict from the plaintext outer payload.
        val b = arr.getJSONObject(1)
        assertTrue(b.getBoolean("undecryptable"))
        assertTrue(b.isNull("text"))
        assertEquals(0, b.getJSONArray("blobs").length())
        assertEquals(99.0, b.getDouble("expires_at"), 0.0)
        val sr = b.getJSONObject("story_ref")
        assertEquals("s9", sr.getString("story_id"))
        assertEquals("ab12", sr.getString("media_hash"))
    }

    // ---- slice 2 (vp2) Task 3: dm blob key filtering (kind gate) ----

    @Test fun dmKeysExcludesPostKeys() {
        val feed = listOf(
            DecryptPass.Decrypted("post1", "post", "a", "t", 1.0, listOf("pb"), emptyList(),
                "photo", null, null),
            DecryptPass.Decrypted("dm1", "dm", "a", "t", 2.0, listOf("db"), emptyList(),
                "photo", null, null))
        val keys = mapOf("post1" to byteArrayOf(1), "dm1" to byteArrayOf(2))
        val out = LocalApi.dmKeys(feed, keys)
        // only the DM's key survives -- a post's msgId can never resolve a key
        // via the dm-blob route (the kind gate, mirroring hearth's dm_blob
        // `if msg.kind != KIND_DM: return None`).
        assertEquals(setOf("dm1"), out.keys)
        assertArrayEquals(byteArrayOf(2), out["dm1"])
    }

    // -- vp3 slice 3 Task 2: pure wall-assembly builders --

    private fun wallPost(
        msgId: String, createdAt: Double, blobs: List<String> = emptyList(),
        thumbs: List<String?> = emptyList(), media: String = "photo", scope: String = "kreds",
    ) = DecryptPass.Decrypted(
        msgId = msgId, kind = "post", author = "Me", text = "t", createdAt = createdAt,
        blobs = blobs, thumbs = thumbs, media = media, poster = null, storyRefMediaHash = null,
        identityPub = "own", scope = scope, expiresAt = null, placement = "profile", codec = null)

    private val emptyLayout = ProfileLayout(emptyMap(), emptyMap(), emptyMap(), emptyMap())

    @Test fun defaultSpanBranches() {
        val text = wallPost("t", 1.0)                                      // no blobs, photo
        val photo = wallPost("p", 1.0, blobs = listOf("b"))
        val video = wallPost("v", 1.0, media = "video")                    // no blobs but video
        // full (default size) -> 4x1 for pure text, 4x2 for media / video
        assertSpan(1, 1, LocalApi.defaultSpan(text, mapOf("t" to "small")))
        assertSpan(2, 2, LocalApi.defaultSpan(text, mapOf("t" to "wide")))
        assertSpan(4, 1, LocalApi.defaultSpan(text, emptyMap()))           // full + no media
        assertSpan(4, 2, LocalApi.defaultSpan(photo, emptyMap()))          // full + blobs
        assertSpan(4, 2, LocalApi.defaultSpan(video, emptyMap()))          // full + video (has_media)
    }

    private fun assertSpan(w: Int, h: Int, o: JSONObject) {
        assertEquals(w, o.getInt("w")); assertEquals(h, o.getInt("h"))
    }

    @Test fun foldAlbumMembersSmallestAlbumIdWins() {
        // "m1" is in both album "A" and album "B"; sorted iteration hits "A"
        // first, setdefault keeps it -> "A" wins the conflict.
        val member = LocalApi.foldAlbumMembers(mapOf(
            "B" to listOf("m1", "m2"), "A" to listOf("m1", "m3")))
        assertEquals("A", member["m1"])
        assertEquals("B", member["m2"])
        assertEquals("A", member["m3"])
    }

    @Test fun wallBlockJsonPinnedMirrorsPinAndOmitsTextStyleForMedia() {
        val d = wallPost("p", 5.0, blobs = listOf("b"), thumbs = listOf<String?>("th"))
        val layout = ProfileLayout(
            pins = mapOf("p" to mapOf("x" to 1, "y" to 2, "w" to 3, "h" to 2)),
            spans = emptyMap(), sizes = emptyMap(), texts = emptyMap())
        val o = LocalApi.wallBlockJson(d, "own", layout)
        // base feedRow fields present + the three added fields
        assertEquals("p", o.getString("msg_id"))
        assertTrue(o.getBoolean("mine"))                       // identity == own
        assertTrue(o.isNull("responses"))                      // wall rows: responses null
        val pin = o.getJSONObject("pin")
        assertEquals(1, pin.getInt("x")); assertEquals(3, pin.getInt("w"))
        assertSpan(3, 2, o.getJSONObject("span"))              // span mirrors pin w/h
        assertFalse("media block has no text_style", o.has("text_style"))
    }

    @Test fun wallBlockJsonUnpinnedTextHasDefaultedTextStyleWithOverride() {
        val d = wallPost("t", 5.0)                              // pure text
        val layout = ProfileLayout(
            pins = emptyMap(), spans = emptyMap(), sizes = emptyMap(),
            texts = mapOf("t" to mapOf("h" to "center", "size" to "xl")))
        val o = LocalApi.wallBlockJson(d, "own", layout)
        assertTrue(o.isNull("pin"))                            // unpinned -> null
        assertSpan(4, 1, o.getJSONObject("span"))              // default span, full + no media
        val ts = o.getJSONObject("text_style")
        assertEquals("center", ts.getString("h"))              // override
        assertEquals("xl", ts.getString("size"))               // override
        assertEquals("top", ts.getString("v"))                 // default
        assertEquals("sans", ts.getString("font"))             // default
        assertEquals("default", ts.getString("color"))         // hearth's color default
    }

    @Test fun albumBlockJsonShape() {
        val photos = JSONArray().put(JSONObject().put("m", "m1").put("h", "h1").put("t", "t1"))
        val o = LocalApi.albumBlockJson(
            albumId = "A", mine = false, photos = photos, createdAt = 9.0,
            scopeNewest = "inner", pin = null, span = JSONObject().put("w", 2).put("h", 2))
        assertEquals(setOf("album", "msg_id", "mine", "photos", "count",
            "created_at", "scope_newest", "pin", "span"), o.keys().asSequence().toSet())
        assertTrue(o.getBoolean("album"))
        assertEquals("A", o.getString("msg_id"))
        assertFalse(o.getBoolean("mine"))
        assertEquals(1, o.getInt("count"))
        assertEquals("inner", o.getString("scope_newest"))
        assertTrue(o.isNull("pin"))
        assertSpan(2, 2, o.getJSONObject("span"))
        val ph = o.getJSONArray("photos").getJSONObject(0)
        assertEquals("m1", ph.getString("m")); assertEquals("t1", ph.getString("t"))
    }

    @Test fun wallJsonFoldsAlbumsRemovesMembersAndSortsDesc() {
        // p1 (loose, newest), a1+a2 (album A members), p0 (loose, oldest)
        val wall = listOf(
            wallPost("p1", 30.0, blobs = listOf("bp1")),
            wallPost("a1", 20.0, blobs = listOf("ba1"), thumbs = listOf<String?>("ta1"), scope = "inner"),
            wallPost("a2", 25.0, blobs = listOf("ba2"), thumbs = listOf<String?>(null)),
            wallPost("p0", 5.0))
        val albums = mapOf("A" to listOf("a1", "a2"))
        val arr = LocalApi.wallJson(wall, emptyLayout, albums, "own", mine = true)
        // 3 blocks: p1, album A, p0 -- a1/a2 folded away; sorted created_at DESC
        assertEquals(3, arr.length())
        assertEquals("p1", arr.getJSONObject(0).getString("msg_id"))     // 30
        val album = arr.getJSONObject(1)                                  // A: newest member 25
        assertTrue(album.getBoolean("album"))
        assertEquals("A", album.getString("msg_id"))
        assertTrue(album.getBoolean("mine"))
        assertEquals(2, album.getInt("count"))                           // ba1 + ba2
        assertEquals("kreds", album.getString("scope_newest"))           // a2 (25) is newest -> its scope
        assertSpan(2, 2, album.getJSONObject("span"))                    // album default span
        val ph0 = album.getJSONArray("photos").getJSONObject(0)
        assertEquals("a1", ph0.getString("m")); assertEquals("ta1", ph0.getString("t"))
        val ph1 = album.getJSONArray("photos").getJSONObject(1)
        assertEquals("a2", ph1.getString("m")); assertTrue(ph1.isNull("t"))   // null thumb
        assertEquals("p0", arr.getJSONObject(2).getString("msg_id"))     // 5
    }

    @Test fun wallJsonSkipsVideoMembersAndEmptyAlbums() {
        // album with only a video member yields NO photos -> album dropped;
        // the video member is still folded out of the loose list (member_of).
        val wall = listOf(wallPost("v1", 10.0, media = "video", blobs = listOf("bv")))
        val arr = LocalApi.wallJson(wall, emptyLayout, mapOf("A" to listOf("v1")), "own", mine = true)
        assertEquals(0, arr.length())
    }

    // -- vp3 slice 3 Task 3: profileJson top-level shape + own-default record --

    @Test fun profileJsonTopLevelShapeOwn() {
        val rec = mapOf(
            "name" to "Me", "bio" to "hi", "accent" to "#2743d6", "avatar" to null,
            "avatar_shape" to "circle", "avatar_size" to "m", "avatar_align" to "left",
            "banner" to null, "banner_pos" to 50,
            "kind" to "profile", "created_at" to 1.0)                 // extra keys must NOT leak
        val wall = JSONArray().put(JSONObject().put("msg_id", "m1"))
        val o = JSONObject(LocalApi.profileJson(rec, "own", true, "kreds", null, wall, JSONArray()))
        assertEquals(setOf(
            "name", "bio", "accent", "avatar", "avatar_shape", "avatar_size",
            "avatar_align", "banner", "banner_pos", "identity_pub", "mine",
            "ring", "since", "wall", "journal"), o.keys().asSequence().toSet())
        assertEquals("Me", o.getString("name"))
        assertEquals("own", o.getString("identity_pub"))
        assertTrue(o.getBoolean("mine"))
        assertEquals("kreds", o.getString("ring"))
        assertTrue(o.isNull("since"))                                 // own -> null
        assertTrue(o.isNull("avatar"))
        assertEquals(50, o.getInt("banner_pos"))
        assertEquals(1, o.getJSONArray("wall").length())
    }

    @Test fun profileJsonSinceZeroForOtherAndDefaultRecord() {
        val rec = LocalApi.defaultProfileRecord("bob01234")
        val o = JSONObject(LocalApi.profileJson(rec, "bob", false, "kreds", 0, JSONArray(), JSONArray()))
        assertEquals("bob01234", o.getString("name"))
        assertFalse(o.getBoolean("mine"))
        assertEquals(0, o.getInt("since"))                            // other -> 0
        assertEquals("#2743d6", o.getString("accent"))
        assertEquals("circle", o.getString("avatar_shape"))
        assertEquals("m", o.getString("avatar_size"))
        assertEquals("left", o.getString("avatar_align"))
        assertTrue(o.isNull("avatar")); assertTrue(o.isNull("banner"))
        assertEquals(50, o.getInt("banner_pos"))
        assertEquals("", o.getString("bio"))
    }

    // -- Task 5 fix wave: pyStr's org.json numeric-type coverage --
    //
    // Reviewer-caught Important: org.json:json:20240303 parses a
    // DECIMAL-notation JSON number as java.math.BigDecimal (not Double) and
    // an INTEGER-notation one as Integer/Long/BigInteger depending on bit
    // length -- confirmed by decompiling JSONObject.stringToNumber, not
    // assumed. pyStr must format every one of those runtime types the way
    // Python's str() would format whatever json.loads would have produced
    // from the identical JSON text, since this string becomes a retract's
    // compose body and hearth's fold logic (node.py:2648-2653/2681) matches
    // it by exact string equality.

    @Test fun pyStrIntegralTypesMatchPythonStr() {
        // "1752900000" -- a realistic created_at integer part. bitLength
        // ~31, so org.json itself would hand back an Integer for this exact
        // literal (decompiled JSONObject.stringToNumber: <=31 bits ->
        // Integer, <=63 -> Long, else raw BigInteger) -- covering Int here
        // is not a hypothetical, it is what a whole-number timestamp
        // ACTUALLY parses as.
        assertEquals("1752900000", LocalApi.pyStr(1752900000))
        assertEquals("1752900000", LocalApi.pyStr(1752900000L))
        assertEquals("1752900000", LocalApi.pyStr(java.math.BigInteger("1752900000")))
    }

    @Test fun pyStrFractionalTypesMatchPythonStr() {
        // The BigDecimal branch is the one the review caught as previously
        // unreachable (fell into the old catch-all `else -> v.toString()`
        // instead of routing through pyFloatRepr). BigDecimal("...5")'s own
        // toString() happens to already equal Python's str() for THIS
        // literal (no exponent involved) -- that coincidence is exactly why
        // the bug shipped looking correct; the real point of this port is
        // matching Python's repr-equivalent formatting in general (exponent
        // thresholds differ between BigDecimal.toString() and
        // Python str(float)), which is what routing through
        // KotlinWire.pyFloatRepr (via PyFloat) actually guarantees.
        assertEquals("1752900000.5", LocalApi.pyStr(java.math.BigDecimal("1752900000.5")))
        assertEquals("1752900000.5", LocalApi.pyStr(1752900000.5))         // Double branch, same output
        assertEquals("1752900000.0", LocalApi.pyStr(java.math.BigDecimal("1752900000.0")))  // whole-valued BigDecimal
    }

    @Test fun pyStrRejectsNonNumericTypes() {
        // Minor (review): the old `else -> v.toString()` would have turned
        // Kotlin's `true` into the string "true" -- Python's str(True) is
        // "True". Neither string is a real created_at, and hearth's fold
        // can never match either -- reject outright (400 via the caller's
        // catch) rather than silently emitting a body that looks plausible
        // but can never retract anything.
        try { LocalApi.pyStr(true); fail("expected IllegalArgumentException") }
        catch (e: IllegalArgumentException) { /* expected */ }
        try { LocalApi.pyStr("1752900000.5"); fail("expected IllegalArgumentException") }
        catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test fun pyStrMatchesRealOrgJsonParsePipeline() {
        // Closes the review's "nothing downstream tests retract formatting"
        // gap end-to-end: parse actual JSON text through org.json (the same
        // path composeRetract() uses -- JSONObject(String(body, UTF_8)),
        // then .get("created_at")) rather than hand-constructing the
        // BigDecimal/Integer values, proving org.json really does hand back
        // the types the comment above claims for both an integer and a
        // fractional literal.
        val intJson = JSONObject("""{"created_at": 1752900000}""")
        assertTrue("a whole-number literal parses as Integer, not Double/BigDecimal",
            intJson.get("created_at") is Int)
        assertEquals("1752900000", LocalApi.pyStr(intJson.get("created_at")))

        val fracJson = JSONObject("""{"created_at": 1752900000.5}""")
        assertTrue("a decimal-notation literal parses as BigDecimal, not Double",
            fracJson.get("created_at") is java.math.BigDecimal)
        assertEquals("1752900000.5", LocalApi.pyStr(fracJson.get("created_at")))
    }
}
