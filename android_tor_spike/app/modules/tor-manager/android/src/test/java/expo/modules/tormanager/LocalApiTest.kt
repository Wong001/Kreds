package expo.modules.tormanager

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
}
