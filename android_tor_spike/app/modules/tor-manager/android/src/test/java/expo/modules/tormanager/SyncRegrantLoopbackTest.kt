package expo.modules.tormanager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 4 (recipient-post-regrants): the loopback PARITY GATE for the whole
 *  slice -- a REAL second hearth node (not a mock) reproduces the exact
 *  field case (spec: docs/superpowers/specs/2026-07-23-recipient-post-
 *  regrants-design.md's Goal paragraph: "a friend's ... old journal posts
 *  are visible on August's desktop but undecryptable on his phone until the
 *  FRIEND comes back online") and proves hearth's REAL
 *  `maintain_received_post_grants` sweep (node.py:2261+) -- not any other
 *  mechanism -- is what unlocks it on the phone. Reuses the SAME
 *  startNode/SocketStream/NodeProcess harness SyncLoopbackTest.kt/
 *  SyncDmLoopbackTest.kt/SyncResponseLoopbackTest.kt use, and mirrors
 *  SyncLoopbackTest's phoneReadsFriendContentEndToEnd (the DM sibling's own
 *  two-node parity gate) most closely of all of them.
 *
 *  Scenario ("regrants"/"regrants_no_sweep", sync_loopback_node.py's
 *  _run_regrants): B ("Desk") is the phone's OWN identity, as in every
 *  other scenario (mint_fixture enrolls the phone's device under B's own
 *  identity). A ("Freja") is a SECOND real HearthNode, the friend/author.
 *  B and A befriend and exchange enc keys BEFORE A ever composes, so B's
 *  own PRIMARY device already has a published enc key at compose time. A
 *  then composes ONE kreds journal post WITH A PHOTO -- compose_post's
 *  automatic friend-wrap wraps the content key to every enc-keyed device A
 *  knows of at that instant, which is ONLY B's primary device (the phone's
 *  device does not exist anywhere yet, not even as a cert) -- exactly
 *  "wrapped only to keys B held THEN". The post (+ its photo blob) is
 *  gossiped into B's store, where B can already decrypt it via that inline
 *  wrap. A then goes OFFLINE for the rest of the scenario -- the field
 *  case -- and is never touched again.
 *
 *  The phone's own enc key is published fresh AFTER, over the REAL wire,
 *  via the exact same path phoneDecryptsRealBackfilledContent/
 *  phoneReadsFriendContentEndToEnd use (KotlinSync.composeEncKey +
 *  hearth/sync.py's already-generic MESSAGES-phase ingestion) -- A never
 *  saw this key and could not possibly have wrapped anything to it.
 *
 *  Connection 1 pushes that fresh enc key. The node's wrapped _on_conn
 *  (sync_loopback_node.py's _run_regrants) then -- for the "regrants"
 *  scenario only -- runs hearth's REAL maintain_received_post_grants and
 *  POLLS the store's actual resulting state (never assumes success just
 *  because the sweep ran) for a grant covering the phone's fresh device
 *  key, printing {"event":"regrants_ready"} once, and only once, that is
 *  true. This is the deterministic handoff this test blocks on before
 *  opening connection 2 -- exactly the same reasoning
 *  NodeProcess.awaitMaintained's own doc gives for why a client-observed
 *  "sync 1 succeeded" is not enough proof that SERVER-side post-processing
 *  (here, the sweep) has already run.
 *
 *  For the "regrants_no_sweep" negative control, the scenario NEVER calls
 *  the sweep at all (see _run_regrants's own docstring: this harness's
 *  SyncService.start() never starts the periodic gossip_loop the sweep
 *  call actually lives inside, so "never calling it" is already the
 *  cleanest possible suppression -- no monkeypatch needed), so
 *  "regrants_ready" never appears; this test instead blocks on the
 *  scenario's unconditional {"event":"conn_done"} marker, which fires
 *  after every connection in BOTH scenarios.
 *
 *  Connection 2 (fresh, plain pull) brings in the friend's post, its
 *  recipient-signed grant (if the sweep ran), and its blob. */
class SyncRegrantLoopbackTest {

    @Test fun offlineAuthorFriendPostRendersViaRecipientReGrant() {
        val node = startNode("regrants")
        try {
            val fx = KotlinHandshake.parseFixture(node.fixtureJson)
            val phoneDevicePub = fx.device_pub
            val ownIdentityPub = fx.cert.identity_pub
            val fxJson = JSONObject(node.fixtureJson)
            val friendIdentityPub = fxJson.getString("friend_identity_pub")
            val targetMsgId = fxJson.getString("target_msg_id")

            val store = InMemorySyncStore()
            store.addIdentity(ownIdentityPub)
            // Pre-seed the friend identity BEFORE connection 1's HAVE swap --
            // same reason every two-node scenario's test does this (see
            // SyncLoopbackTest/SyncResponseLoopbackTest's own class docs):
            // hearth's sync.py restricts a round's `entitled` set to
            // identities the PEER already reports knowing in ITS OWN HAVE
            // frame that same round.
            store.addIdentity(friendIdentityPub)
            val (encPriv, encPub) = EncKeys.getOrCreate(store)

            // -- Connection 1: authenticate, then push the phone's FRESH
            // device-signed enckey -- the real path (composeEncKey + the
            // generic MESSAGES-phase ingestion), exactly as
            // phoneDecryptsRealBackfilledContent/phoneReadsFriendContentEndToEnd
            // do. This is what the node process's _run_regrants wrapper
            // reacts to.
            val stream1 = SocketStream("127.0.0.1", node.port)
            KotlinHandshake.authOnlyOverStream(stream1, fx)
            val encKeyMsg = KotlinSync.composeEncKey(
                fx, encPub, store.nextSeq(), System.currentTimeMillis() / 1000.0)
            val res1 = KotlinSync.run(stream1, store, phoneDevicePub, outbound = listOf(encKeyMsg))
            assertTrue("sync 1 (push fresh enckey): $res1", res1 is SyncResult.Ok)

            // Deterministic handoff (see this file's class doc + NodeProcess.
            // awaitMaintained's doc for the general reasoning): blocks until
            // B's REAL maintain_received_post_grants sweep has minted a
            // grant that ACTUALLY covers the phone's fresh key -- not merely
            // that the sweep function was called.
            node.awaitEvent("regrants_ready")

            // -- Connection 2: fresh connection, plain pull -- brings in
            // the friend's post, its recipient-signed grant, and its photo
            // blob.
            val stream2 = SocketStream("127.0.0.1", node.port)
            KotlinHandshake.authOnlyOverStream(stream2, fx)
            val res2 = KotlinSync.run(stream2, store, phoneDevicePub)
            assertTrue("sync 2 (pull friend post + grant + blob): $res2", res2 is SyncResult.Ok)

            // Precondition (RED-proving if it fails): the post must carry
            // NO inline wrap for the phone's fresh device key -- it was
            // composed while A held only B's THEN-current key, before the
            // phone's key existed anywhere. Without this, a successful
            // render below could be explained by an inline wrap instead of
            // proving the recipient-grant path -- the whole point of this
            // gate.
            val postMsg = store.allMessages().single { it.msgId == targetMsgId }
            @Suppress("UNCHECKED_CAST")
            val wraps = postMsg.payload["wraps"] as? Map<String, Any?>
            assertTrue(
                "friend post must NOT be inline-wrapped to the phone's fresh key " +
                    "(it was composed before that key existed anywhere)",
                wraps == null || phoneDevicePub !in wraps)
            // Also rules out the OTHER legitimate path (an author-signed
            // grant): no grant signed by the friend/author may cover the
            // phone's key either, leaving the recipient-signed backfill as
            // the only candidate that could possibly unlock it below.
            assertTrue(
                "no author-signed grant may cover the phone's fresh key either",
                store.wrapGrantsFor(targetMsgId, setOf(friendIdentityPub))
                    .none { phoneDevicePub in it })

            val result = DecryptPass.run(store, phoneDevicePub, encPriv, ownIdentityPub)
            val post = result.feed.singleOrNull { it.msgId == targetMsgId }
            assertNotNull(
                "the offline author's friend post must render on the phone via the " +
                    "recipient re-grant",
                post)
            assertEquals("frejas gamle indlaeg", post!!.text)

            // Independent grant-path proof (mirrors phoneReadsFriendContentEndToEnd's
            // own ownSignedGrants check for the DM sibling): the covering
            // grant must be signed by OUR OWN identity specifically -- never
            // the friend/author -- i.e. it really is
            // maintain_received_post_grants' RECIPIENT-signed backfill, not
            // some other mechanism that happens to also render the post.
            val ownSignedGrants = store.wrapGrantsFor(targetMsgId, setOf(ownIdentityPub))
            assertTrue(
                "the covering grant must be the recipient-signed backfill " +
                    "(signed by our own identity)",
                ownSignedGrants.any { phoneDevicePub in it })

            // Photo blob decrypts via the recovered content key
            // (decrypt-on-read path) -- mirrors SyncDmLoopbackTest's own
            // blob_ok proof style, driven here through the REAL phone-side
            // DecryptPass + KotlinBlobCrypt path rather than a node-side
            // dmcrypt check.
            assertEquals("friend post must carry exactly one photo blob ref", 1, post.blobs.size)
            val contentKey = result.keys[targetMsgId]
            assertNotNull("content key must be recovered for the blob-carrying post", contentKey)
            val cipher = store.getBlob(post.blobs[0])
            assertNotNull("phone's store must hold the synced photo blob", cipher)
            val plain = KotlinBlobCrypt.decryptBlob(contentKey!!, cipher!!)
            assertNotNull(
                "photo blob must AEAD-decrypt via the content key recovered through " +
                    "the recipient-signed grant",
                plain)
        } finally {
            node.kill()
        }
    }

    @Test fun noSweepControlPostDoesNotRender() {
        val node = startNode("regrants_no_sweep")
        try {
            val fx = KotlinHandshake.parseFixture(node.fixtureJson)
            val phoneDevicePub = fx.device_pub
            val ownIdentityPub = fx.cert.identity_pub
            val fxJson = JSONObject(node.fixtureJson)
            val friendIdentityPub = fxJson.getString("friend_identity_pub")
            val targetMsgId = fxJson.getString("target_msg_id")

            val store = InMemorySyncStore()
            store.addIdentity(ownIdentityPub)
            store.addIdentity(friendIdentityPub)
            val (encPriv, encPub) = EncKeys.getOrCreate(store)

            // -- Connection 1: identical to the happy path -- push the
            // phone's fresh enckey over the real wire. The SAME fixture/seed
            // as the happy-path test; the only difference is server-side --
            // this scenario's node NEVER calls maintain_received_post_grants
            // at all (see _run_regrants's own docstring for why "never
            // calling it" is already the cleanest suppression).
            val stream1 = SocketStream("127.0.0.1", node.port)
            KotlinHandshake.authOnlyOverStream(stream1, fx)
            val encKeyMsg = KotlinSync.composeEncKey(
                fx, encPub, store.nextSeq(), System.currentTimeMillis() / 1000.0)
            val res1 = KotlinSync.run(stream1, store, phoneDevicePub, outbound = listOf(encKeyMsg))
            assertTrue("sync 1 (push fresh enckey): $res1", res1 is SyncResult.Ok)

            // Deterministic completion marker -- fires in BOTH scenarios
            // after every connection (see _run_regrants's docstring);
            // "regrants_ready" never appears here since the sweep never
            // runs, so this test blocks on "conn_done" instead.
            node.awaitEvent("conn_done")

            // -- Connection 2: fresh connection, plain pull.
            val stream2 = SocketStream("127.0.0.1", node.port)
            KotlinHandshake.authOnlyOverStream(stream2, fx)
            val res2 = KotlinSync.run(stream2, store, phoneDevicePub)
            assertTrue("sync 2 (pull): $res2", res2 is SyncResult.Ok)

            // The post itself still syncs (B holds it, and it is entitled --
            // messages_not_in routes a KIND_POST to a peer wrap-covered by
            // ANY of the post's device wraps, own-primary-device included,
            // regardless of grants) -- but with NO covering wrap/grant for
            // the phone's fresh key, since the sweep that would have minted
            // one never ran.
            val postMsg = store.allMessages().singleOrNull { it.msgId == targetMsgId }
            if (postMsg != null) {
                @Suppress("UNCHECKED_CAST")
                val wraps = postMsg.payload["wraps"] as? Map<String, Any?>
                assertTrue(
                    "precondition: still no inline wrap for the phone's key",
                    wraps == null || phoneDevicePub !in wraps)
            }
            assertTrue(
                "precondition: still no author-signed grant covers the phone's key",
                store.wrapGrantsFor(targetMsgId, setOf(friendIdentityPub))
                    .none { phoneDevicePub in it })
            assertTrue(
                "no recipient-signed (own-identity) grant may exist either -- the " +
                    "sweep that mints it never ran",
                store.wrapGrantsFor(targetMsgId, setOf(ownIdentityPub))
                    .none { phoneDevicePub in it })

            // Negative control: without the sweep, the SAME post must NOT
            // render -- proving the recipient grant (not some other path)
            // is what unlocks it in the happy-path test above.
            val result = DecryptPass.run(store, phoneDevicePub, encPriv, ownIdentityPub)
            val post = result.feed.singleOrNull { it.msgId == targetMsgId }
            assertNull(
                "without the sweep, the friend post must NOT render on the phone",
                post)
        } finally {
            node.kill()
        }
    }
}
