package expo.modules.tormanager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 4 (outbound-dm-send slice): the loopback FIDELITY GATE for the whole
 *  slice -- a REAL hearth node (not a mock, not a bare in-process decrypt)
 *  ingests phone-composed DMs (text, photo, story-reply, expiring) over the
 *  real wire protocol, and a real FRIEND identity's device key -- never the
 *  phone's own -- genuinely decrypts each one as the addressed RECIPIENT
 *  (compose_dm rejects a self-DM, so every DM here targets the friend),
 *  cross-checks the encrypted body's blob refs against the envelope's own
 *  plaintext claim, re-validates story_ref via hearth's real shape guard,
 *  and -- for the expiring DM -- proves hearth's REAL store.sweep_expired
 *  actually reclaims it past its expiry instant. Reuses the SAME
 *  startNode/SocketStream/NodeProcess harness SyncLoopbackTest.kt/
 *  SyncComposeLoopbackTest.kt/SyncResponseLoopbackTest.kt use and mirrors
 *  the latter two's connection-then-awaitEvent pattern.
 *
 *  Scenario ("dm", sync_loopback_node.py's _run_dm): the node is an OWN
 *  DEVICE of the phone's identity (mint_fixture enrolls the phone's device
 *  under the node's own identity, exactly like every other loopback
 *  scenario) with its own enckey already published, and a real FRIEND
 *  identity with its own published enckey -- the DM's actual recipient,
 *  not merely a mutual-box opener like SyncResponseLoopbackTest's friend.
 *
 *  Connection 1 (a plain pull) is a PRIMING round for the same reason
 *  SyncComposeLoopbackTest's/SyncResponseLoopbackTest's are: hearth's own
 *  sync.py restricts a round's `entitled` set (messages_not_in) to
 *  identities the PEER already reports knowing in ITS OWN HAVE frame THAT
 *  SAME round, so the friend identity is pre-seeded into the store BEFORE
 *  connection 1 -- see those tests' class docs for the full protocol trace,
 *  unchanged here. Without this priming pull, ComposeDm.compose below would
 *  either throw ("recipient is not a friend" / "no encryption keys known
 *  for recipient yet") or silently omit the friend as a wrap recipient.
 *
 *  Connection 2 pushes all four composed DMs together via the pending-
 *  outbound queue (ComposeDm.compose already calls store.addPendingOutbound
 *  internally -- the same outbound-queue idiom SyncComposeLoopbackTest/
 *  SyncResponseLoopbackTest proved for KIND_POST/KIND_RESPONSE, now proved
 *  for KIND_DM). The node emits one {"event":"dm",...} line per DM as each
 *  decrypts -- oldest created_at first, per sync_loopback_node.py's
 *  _find_new_phone_dms -- followed immediately by a {"event":"dm_expired",
 *  "swept":...} line for the one DM whose envelope carries a non-null
 *  expires_at (the node runs hearth's real store.sweep_expired with
 *  now = expires_at + 1 right after that DM's "dm" event). */
class SyncDmLoopbackTest {

    @Test fun phoneComposedDmsDecryptOnRealNodeAsRecipientAndExpire() {
        val node = startNode("dm")
        try {
            val fx = KotlinHandshake.parseFixture(node.fixtureJson)
            val friendIdentityPub = JSONObject(node.fixtureJson).getString("friend_identity_pub")

            val store = InMemorySyncStore()
            store.addIdentity(fx.cert.identity_pub)
            // Pre-seed the friend identity BEFORE connection 1's HAVE swap
            // -- see the class doc above for why this is required for a
            // single connection to pull the friend's enckey.
            store.addIdentity(friendIdentityPub)

            // -- Connection 1: plain pull -- the node's own device enckey
            // (published up front by the scenario's node.ensure_enckey())
            // and the friend's enckey (delivered into node.store by the
            // scenario's own gossip seeding), both own-identity/friend-
            // identity messages with no extra wrap-set gating, so a single
            // round trip is enough now that the friend identity is known.
            val stream1 = SocketStream("127.0.0.1", node.port)
            KotlinHandshake.authOnlyOverStream(stream1, fx)
            val res1 = KotlinSync.run(stream1, store, fx.device_pub)
            assertTrue("sync 1 (pull own + friend enckeys): $res1", res1 is SyncResult.Ok)

            // Preconditions (RED-proving if either fails): without these,
            // ComposeDm.compose below would either throw or silently omit
            // the friend as a wrap recipient, and the assertions further
            // down would be proving nothing.
            assertTrue("node's own device enckey must be pulled before compose",
                store.enckeys(fx.cert.identity_pub).isNotEmpty())
            assertTrue("friend's enckey must be pulled before compose",
                store.enckeys(friendIdentityPub).isNotEmpty())

            val (encPriv, encPub) = EncKeys.getOrCreate(store)

            // -- Compose four DMs to the friend. Fixed, distinct, strictly
            // increasing base timestamps -- ComposeDm.compose does not
            // enforce monotonicity itself (unlike ComposeResponse), but
            // ordering these lets the node's own created_at-sorted
            // discovery (_find_new_phone_dms) surface them in the exact
            // order composed below, which the sequential awaitEvent("dm")
            // calls rely on.
            val ts1 = 1753100000.0
            val ts2 = 1753100001.0
            val ts3 = 1753100002.0
            val ts4 = 1753100003.0

            val resText = ComposeDm.compose(store, fx, encPriv, encPub, friendIdentityPub,
                "hej fra telefonen 🌸", emptyList(), null, null, ts1)

            // Arbitrary bytes standing in for a JPEG (mirrors
            // SyncComposeLoopbackTest's own photoBytes fixture and its
            // documented rationale): ComposeDm.compose takes already
            // PhotoPrep-gated bytes -- it never image-decodes them itself
            // -- and neither does this loopback gate's node-side decrypt
            // (AEAD round-trip + cross-check only), so no real JPEG asset
            // is needed to prove blob fidelity here.
            val photoBytes = ByteArray(200) { (it % 256).toByte() }
            val resPhoto = ComposeDm.compose(store, fx, encPriv, encPub, friendIdentityPub,
                "one photo", listOf(photoBytes), null, null, ts2)

            @Suppress("UNCHECKED_CAST")
            val photoPayload = resPhoto.wireDict["payload"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val photoBlobRefs = photoPayload["blobs"] as List<String>
            assertEquals("photo dm should carry exactly one blob ref", 1, photoBlobRefs.size)
            val photoBlobHash = photoBlobRefs[0]   // a real hex64 -- reused below as story_ref's media_hash

            val storyRef = mapOf("story_id" to "seeded-story-1", "media_hash" to photoBlobHash)
            val resStoryReply = ComposeDm.compose(store, fx, encPriv, encPub, friendIdentityPub,
                "story reply", emptyList(), null, storyRef, ts3)

            val expiresSeconds = 60.0
            val resExpiring = ComposeDm.compose(store, fx, encPriv, encPub, friendIdentityPub,
                "this one expires", emptyList(), expiresSeconds, null, ts4)

            val pending = store.pendingOutbound()
            assertEquals("all four composed dms should be queued", 4, pending.size)

            // -- Connection 2: fresh auth, push all four queued DMs via
            // MESSAGES in one round trip; the BLOBS phase of this SAME
            // session honors the resulting blob_want for the photo dm.
            val stream2 = SocketStream("127.0.0.1", node.port)
            KotlinHandshake.authOnlyOverStream(stream2, fx)
            val res2 = KotlinSync.run(stream2, store, fx.device_pub, outbound = pending)
            assertTrue("sync 2 (push four dms + blob): $res2", res2 is SyncResult.Ok)
            store.clearPendingOutbound(listOf(
                resText.msgId, resPhoto.msgId, resStoryReply.msgId, resExpiring.msgId))
            assertTrue("queue drained after a successful push", store.pendingOutbound().isEmpty())

            // The node's wrapped _on_conn (sync_loopback_node.py's
            // _run_dm) decrypts each just-ingested DM as the REAL,
            // ADDRESSED RECIPIENT -- the friend node's own device enc
            // key, never the phone's -- emitting one "dm" line per DM,
            // oldest created_at first (text, photo, story-reply, expiring).

            val textEvent = node.awaitEvent("dm")
            assertEquals("hej fra telefonen 🌸", textEvent.getString("text"))
            assertTrue("node (as recipient) must decrypt the text dm's body",
                textEvent.getBoolean("text_ok"))

            val photoEvent = node.awaitEvent("dm")
            assertTrue("node (as recipient) must decrypt the photo dm's body",
                photoEvent.getBoolean("text_ok"))
            assertTrue("node (as recipient) must AEAD-decrypt the pushed photo blob " +
                "(and its body-vs-envelope blob refs must cross-check)",
                photoEvent.getBoolean("blob_ok"))

            val storyReplyEvent = node.awaitEvent("dm")
            assertTrue("node (as recipient) must decrypt the story-reply dm's body",
                storyReplyEvent.getBoolean("text_ok"))
            assertTrue("node must re-validate story_ref via hearth's real _valid_story_ref",
                storyReplyEvent.getBoolean("story_ref_ok"))

            val expiringEvent = node.awaitEvent("dm")
            assertTrue("node (as recipient) must decrypt the expiring dm's body",
                expiringEvent.getBoolean("text_ok"))
            assertEquals("expires_at must be exactly created_at + expires_seconds",
                ts4 + expiresSeconds, expiringEvent.getDouble("expires_at"), 0.0)

            // Controller check (the outbound-1 lesson's proof): the node
            // then runs hearth's REAL sweep_expired past this dm's expiry
            // instant and reports whether it was actually reclaimed.
            val expiredEvent = node.awaitEvent("dm_expired")
            assertTrue("hearth's real sweep_expired must have reclaimed the expired dm",
                expiredEvent.getBoolean("swept"))
        } finally {
            node.kill()
        }
    }
}
