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
}
