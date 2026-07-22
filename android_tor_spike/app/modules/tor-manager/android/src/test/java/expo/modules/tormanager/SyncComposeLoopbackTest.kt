package expo.modules.tormanager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 9 (B.3 outbound slice): the KEY INTEGRATION PROOF for the phone's
 *  outbound compose crypto -- a REAL hearth node (not a mock, not a bare
 *  in-process decrypt) ingests a phone-composed post + photo over the real
 *  wire protocol and decrypts BOTH with its own device enc key, proving
 *  AAD byte-fidelity + device-authored acceptance + friend-wrapping + blob
 *  push against real hearth, BEFORE the on-device run. Reuses the SAME
 *  startNode/SocketStream/NodeProcess harness SyncLoopbackTest.kt's gates
 *  use (hoisted to top-level `internal` there for this reuse -- "mirror
 *  it, don't reinvent") and mirrors phoneDecryptsRealBackfilledContent's
 *  two-connection pattern (auth -> KotlinSync.run, twice, with a
 *  deterministic node-side signal line between them).
 *
 *  Scenario ("outbound_compose", sync_loopback_node.py's
 *  _run_outbound_compose): the node is an OWN DEVICE of the phone's
 *  identity (mint_fixture enrolls the phone's device under the node's own
 *  identity, exactly like every other loopback scenario) AND already
 *  knows one FRIEND identity with a published enc key. Compose.post wraps
 *  a kreds post to every known identity's enc-keyed devices (own +
 *  friends) automatically, so this scenario is what proves the friend-
 *  wrap path exercises REAL recipient resolution -- not just the
 *  own-device path phoneDecryptsRealBackfilledContent already covers.
 *
 *  Connection 1 (a plain pull) is a PRIMING round, not incidental, and
 *  the reason this test pre-seeds the friend identity before it. hearth's
 *  own sync.py restricts a round's `entitled` set (messages_not_in) to
 *  identities the PEER already reports knowing in ITS OWN HAVE frame
 *  THAT SAME round -- a store that (like every fresh InMemorySyncStore)
 *  starts out reporting only its own identity would NOT yet be entitled
 *  to the friend's enckey message on the very first connection, even
 *  though the node already knows the friend. So the node's fixture line
 *  carries an extra `friend_identity_pub` field, and this test calls
 *  `store.addIdentity(friendIdentityPub)` BEFORE connection 1 -- exactly
 *  mirroring how it always pre-seeds its own identity -- which is what
 *  makes connection 1 a genuine single-round pull of both the node's own
 *  enckey (so `store.enckeys(own)` resolves the node's device as an
 *  "own" wrap recipient) and the friend's enckey (so the friend-wrap has
 *  someone real to wrap to).
 *
 *  KotlinSync.run's MESSAGES phase only ever sends the caller-supplied
 *  `outbound` list verbatim -- it never auto-pushes stored own-authored
 *  content. Originally (Task 9) this test pushed connection 2's `outbound`
 *  as `listOf(res.messageDict)`, the exact `.toDict()` of the message
 *  Compose.post had just locally ingested, proving a freshly-composed
 *  message is node-accepted. The outbound task (pending-outbound queue,
 *  Task 8 here) changes what THIS test drives: Compose.post now ALSO calls
 *  `store.addPendingOutbound(signed.msgId())`, and connection 2 pushes
 *  `store.pendingOutbound()` instead -- the queue's own reconstruction of
 *  the wire dict from the stored `messages` row (via the store's
 *  toDict()/jsonToMap bridge), not the directly-returned dict. That
 *  reconstruction is the thing that needs proving now: if it silently
 *  diverged (e.g. a float-precision or canonical-shape mismatch), the
 *  node's device-signature check below would reject it and this test would
 *  fail RED. `messageDict` itself is unchanged and still available on
 *  `Compose.Result` for any caller that wants an immediate push without
 *  waiting on the queue.
 *
 *  Single-connection message+blob delivery (sharp edge, confirmed against
 *  hearth/sync.py's real _session): connection 2's MESSAGES phase ingests
 *  the pushed post into node.store BEFORE the BLOBS phase of that SAME
 *  session computes `store.missing_blobs()` -- so the newly-referenced
 *  photo hash is already in the node's want-list by the time it asks, and
 *  KotlinSync.run's Task-8 blob-give logic (it holds the ciphertext
 *  locally, from Compose.post's own store.putBlob call) honors it in the
 *  same round trip. No extra connection is needed. */
class SyncComposeLoopbackTest {

    @Test fun phoneComposedPostDecryptsOnRealNode() {
        val node = startNode("outbound_compose")
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
            // Compose.post below would silently skip a recipient (wrapKey
            // just omits devices it has no enc key for) and the assertions
            // further down would be proving nothing.
            assertTrue("node's own device enckey must be pulled before compose",
                store.enckeys(fx.cert.identity_pub).isNotEmpty())
            assertTrue("friend's enckey must be pulled before compose",
                store.enckeys(friendIdentityPub).isNotEmpty())

            val (encPriv, encPub) = EncKeys.getOrCreate(store)
            // Arbitrary bytes standing in for a JPEG -- Compose.post takes
            // already-prepped bytes (PhotoPrep, the real EXIF-strip/
            // downscale/JPEG gate, is Android-only and covered on-device
            // separately); encryptBlob/decrypt_blob round-trip ANY bytes,
            // and the node's dmcrypt.decrypt_blob below just recovers them
            // -- no real JPEG asset is needed for this gate (mirrors
            // ComposeTest's own arbitrary-bytes photo fixtures).
            val photoBytes = ByteArray(400) { (it % 256).toByte() }
            val createdAt = 1752900000.5   // fixed, deterministic -- the node
                // recomputes the AAD from the post's own created_at field,
                // so no coordination beyond Compose.post's own guarantee
                // that the payload and the AAD it was sealed under agree.
            val res = Compose.post(store, fx, encPriv, encPub, "hi from phone",
                listOf(photoBytes), "kreds", createdAt)

            // outbound Task 8: push via the QUEUE (Compose.post already
            // called store.addPendingOutbound(signed.msgId()) internally),
            // NOT res.messageDict directly -- this is what proves the queue
            // itself (msg_id -> stored `messages` row -> reconstructed wire
            // dict via the store's toDict()/jsonToMap bridge) produces a
            // node-accepted, decryptable message, not just that a freshly-
            // composed dict handed straight through works.
            val pending = store.pendingOutbound()
            assertEquals("exactly the one composed post should be queued", 1, pending.size)

            // -- Connection 2: fresh auth, push the queued post via MESSAGES;
            // the BLOBS phase of this SAME session then honors the node's
            // resulting blob_want (Task 8's outbound push) in one round trip.
            val stream2 = SocketStream("127.0.0.1", node.port)
            KotlinHandshake.authOnlyOverStream(stream2, fx)
            val res2 = KotlinSync.run(stream2, store, fx.device_pub, outbound = pending)
            assertTrue("sync 2 (push queued composed post + blob): $res2", res2 is SyncResult.Ok)

            // clearPendingOutbound is exercised end-to-end by SyncRunner in
            // production (on SyncResult.Ok); mirror that here so this test's
            // store state matches what a real successful sync would leave.
            store.clearPendingOutbound(listOf(res.msgId))
            assertTrue("queue drained after a successful push", store.pendingOutbound().isEmpty())

            // The node's wrapped _on_conn (sync_loopback_node.py) decrypts
            // the just-ingested post with ITS OWN device enc key -- via the
            // real hearth.dmcrypt primitives directly (unwrap_key ->
            // decrypt_body -> decrypt_blob) -- and prints this line once
            // decryption succeeds.
            val composed = node.awaitEvent("composed")
            assertEquals("hi from phone", composed.getString("text"))
            assertTrue("friend must be named in the post's wraps (real friend-wrap resolution)",
                composed.getBoolean("wrapped_friend"))
            // blob_ok is true iff decrypt_blob's ChaCha20-Poly1305 AEAD auth
            // succeeded -- which, given AEAD, IS the proof the node
            // recovered exactly the bytes the phone encrypted (any
            // mismatch fails the tag rather than returning wrong bytes).
            // blob_len is asserted too as an independent, human-checkable
            // cross-check against the original plaintext length.
            assertTrue("node must decrypt the pushed blob (AEAD-authentic recovery)",
                composed.getBoolean("blob_ok"))
            assertEquals("decrypted blob length must match the original photo bytes",
                photoBytes.size, composed.getInt("blob_len"))
        } finally {
            node.kill()
        }
    }
}
