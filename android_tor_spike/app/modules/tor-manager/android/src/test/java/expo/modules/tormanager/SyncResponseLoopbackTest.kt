package expo.modules.tormanager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 8 (outbound-responses slice): the loopback FIDELITY GATE for the
 *  whole slice -- a REAL hearth node (not a mock, not a bare in-process
 *  decrypt) ingests a phone-composed reaction + comment + retract over the
 *  real wire protocol, decrypts each with its OWN device key, independently
 *  reverifies responder_sig against hearth's own _sig_ok/
 *  _response_sig_payload, and a real FRIEND identity's enc key genuinely
 *  opens the mutual_box -- proving AAD byte-fidelity + device-authored
 *  acceptance + engagement-privacy round-trip against real hearth, BEFORE
 *  the on-device run. Reuses the SAME startNode/SocketStream/NodeProcess
 *  harness SyncLoopbackTest.kt/SyncComposeLoopbackTest.kt use and mirrors
 *  the latter's connection-then-awaitEvent pattern.
 *
 *  Scenario ("responses", sync_loopback_node.py's _run_responses): the node
 *  is an OWN DEVICE of the phone's identity (mint_fixture enrolls the
 *  phone's device under the node's own identity, exactly like every other
 *  loopback scenario) with its own enckey already published, an own
 *  JOURNAL POST seeded as the response target (fixture carries its msg_id
 *  as `target_msg_id`, mirroring how `friend_identity_pub` already rides
 *  along for outbound_compose), and a real FRIEND identity with its own
 *  published enckey so the mutual box has a genuine non-dummy slot.
 *
 *  Connection 1 (a plain pull) is a PRIMING round for the same reason
 *  SyncComposeLoopbackTest's is: hearth's own sync.py restricts a round's
 *  `entitled` set (messages_not_in) to identities the PEER already reports
 *  knowing in ITS OWN HAVE frame THAT SAME round, so the friend identity is
 *  pre-seeded into the store BEFORE connection 1 -- see that test's class
 *  doc for the full protocol trace, unchanged here.
 *
 *  Connection 2 pushes a composed reaction ("fire") + a composed comment
 *  together via the pending-outbound queue (ComposeResponse.compose already
 *  calls store.addPendingOutbound internally -- same outbound-queue idiom
 *  SyncComposeLoopbackTest proved for KIND_POST, now proved for
 *  KIND_RESPONSE). The node emits one {"event":"responded",...} line per
 *  response as each decrypts -- oldest created_at first (reaction, then
 *  comment), per sync_loopback_node.py's _find_new_phone_responses.
 *
 *  Connection 3 (controller addition, carried from Task 5's review: retract
 *  otherwise has zero behavioral coverage) pushes a retract naming the
 *  reaction's created_at -- formatted via LocalApi.pyStr, the EXACT
 *  function the real /api/retract route uses (LocalApi.kt's
 *  composeRetract), not a hand-rolled Double.toString() that could drift
 *  from Python's str(float) repr. The node decrypts+verifies it like any
 *  other response (another "responded" line), then runs hearth's REAL
 *  author fold (node.process_responses -> _rebuild_responses_record) and
 *  reports whether the reaction was ACTUALLY withdrawn -- proving the
 *  pyStr-formatted body genuinely string-matched str(the reaction's
 *  created_at) inside node.py:2648-2654's retracted-set comparison, not
 *  merely that the retract message itself was accepted onto the wire. */
class SyncResponseLoopbackTest {

    @Test fun phoneComposedReactionCommentRetractFidelityGate() {
        val node = startNode("responses")
        try {
            val fx = KotlinHandshake.parseFixture(node.fixtureJson)
            val fxJson = JSONObject(node.fixtureJson)
            val friendIdentityPub = fxJson.getString("friend_identity_pub")
            val targetMsgId = fxJson.getString("target_msg_id")

            val store = InMemorySyncStore()
            store.addIdentity(fx.cert.identity_pub)
            // Pre-seed the friend identity BEFORE connection 1's HAVE swap
            // -- see the class doc above (and SyncComposeLoopbackTest's, in
            // more depth) for why this is required for a single connection
            // to pull the friend's enckey alongside the node's own.
            store.addIdentity(friendIdentityPub)

            // -- Connection 1: plain pull -- the node's own device enckey
            // (published up front by the scenario's node.ensure_enckey())
            // and the friend's enckey (delivered into node.store by the
            // scenario's gossip seeding), plus the seeded target journal
            // post, all own-identity/friend-identity messages with no
            // extra wrap-set gating, so a single round trip is enough now
            // that the friend identity is known.
            val stream1 = SocketStream("127.0.0.1", node.port)
            KotlinHandshake.authOnlyOverStream(stream1, fx)
            val res1 = KotlinSync.run(stream1, store, fx.device_pub)
            assertTrue("sync 1 (pull own + friend enckeys + target post): $res1", res1 is SyncResult.Ok)

            // Preconditions (RED-proving if any fails): without these,
            // ComposeResponse.compose below would silently omit a
            // recipient (wrapKey/sealSlots just skip devices/keys they
            // have none for) and the assertions further down would be
            // proving nothing.
            assertTrue("node's own device enckey must be pulled before compose",
                store.enckeys(fx.cert.identity_pub).isNotEmpty())
            assertTrue("friend's enckey must be pulled before compose",
                store.enckeys(friendIdentityPub).isNotEmpty())
            assertTrue("target journal post must be pulled before compose",
                store.messageById(targetMsgId) != null)

            val (encPriv, encPub) = EncKeys.getOrCreate(store)

            // -- Compose a reaction, then a comment, on the seeded post.
            // Fixed, deterministic base timestamps -- ComposeResponse's own
            // strictly-increasing per-process clock (its `lastTs`) is what
            // actually orders them; these are just distinct valid seeds.
            val resReaction = ComposeResponse.compose(
                store, fx, encPriv, encPub, targetMsgId, "reaction", "fire", 1752900100.0)
            val resComment = ComposeResponse.compose(
                store, fx, encPriv, encPub, targetMsgId, "comment", "nice post!", 1752900101.0)

            @Suppress("UNCHECKED_CAST")
            val reactionPayload = resReaction.wireDict["payload"] as Map<String, Any?>
            val reactionCreatedAt = (reactionPayload["created_at"] as KotlinWire.PyFloat).value

            // outbound Task 3/8 idiom (see SyncComposeLoopbackTest): push
            // via the QUEUE, not the directly-returned wireDict -- proving
            // the queue's own stored-row -> reconstructed-wire-dict bridge
            // produces node-accepted, decryptable messages for KIND_RESPONSE
            // too, not just KIND_POST.
            val pending1 = store.pendingOutbound()
            assertEquals("reaction + comment should both be queued", 2, pending1.size)

            // -- Connection 2: fresh auth, push both queued responses via
            // MESSAGES in one round trip.
            val stream2 = SocketStream("127.0.0.1", node.port)
            KotlinHandshake.authOnlyOverStream(stream2, fx)
            val res2 = KotlinSync.run(stream2, store, fx.device_pub, outbound = pending1)
            assertTrue("sync 2 (push reaction + comment): $res2", res2 is SyncResult.Ok)
            store.clearPendingOutbound(listOf(resReaction.msgId, resComment.msgId))
            assertTrue("queue drained after a successful push", store.pendingOutbound().isEmpty())

            // The node's wrapped _on_conn (sync_loopback_node.py's
            // _run_responses) decrypts each just-ingested response with ITS
            // OWN device enc key, reverifies responder_sig independently,
            // and simulates the FRIEND opening the mutual_box -- emitting
            // one "responded" line per response, oldest created_at first
            // (reaction, then comment).
            val reactionEvent = node.awaitEvent("responded")
            assertEquals("reaction", reactionEvent.getString("rkind"))
            assertEquals("fire", reactionEvent.getString("body"))
            assertTrue("node must reverify responder_sig for the reaction",
                reactionEvent.getBoolean("sig_ok"))
            assertTrue("a real friend must open the reaction's mutual_box",
                reactionEvent.getBoolean("friend_opened"))

            val commentEvent = node.awaitEvent("responded")
            assertEquals("comment", commentEvent.getString("rkind"))
            assertEquals("nice post!", commentEvent.getString("body"))
            assertTrue("node must reverify responder_sig for the comment",
                commentEvent.getBoolean("sig_ok"))
            assertTrue("a real friend must open the comment's mutual_box",
                commentEvent.getBoolean("friend_opened"))

            // -- Controller addition (carried from Task 5's review): retract
            // has zero behavioral coverage otherwise. Body is EXACTLY what
            // the real /api/retract route would send -- LocalApi.pyStr,
            // not a hand-rolled format -- proving the pyStr(created_at)
            // contract (node.py:2648-2653) against the real fold.
            val retractBody = LocalApi.pyStr(reactionCreatedAt)
            val resRetract = ComposeResponse.compose(
                store, fx, encPriv, encPub, targetMsgId, "retract", retractBody, 1752900200.0)

            val pending2 = store.pendingOutbound()
            assertEquals("only the retract should be queued now", 1, pending2.size)

            // -- Connection 3: fresh auth, push the retract.
            val stream3 = SocketStream("127.0.0.1", node.port)
            KotlinHandshake.authOnlyOverStream(stream3, fx)
            val res3 = KotlinSync.run(stream3, store, fx.device_pub, outbound = pending2)
            assertTrue("sync 3 (push retract): $res3", res3 is SyncResult.Ok)
            store.clearPendingOutbound(listOf(resRetract.msgId))

            val retractEvent = node.awaitEvent("responded")
            assertEquals("retract", retractEvent.getString("rkind"))
            assertEquals("the retract body must be the exact pyStr(reaction created_at) the real route sends",
                retractBody, retractEvent.getString("body"))
            assertTrue("node must reverify responder_sig for the retract",
                retractEvent.getBoolean("sig_ok"))
            assertTrue("a real friend must open the retract's mutual_box",
                retractEvent.getBoolean("friend_opened"))

            // The node then runs hearth's REAL author fold
            // (node.process_responses -> _rebuild_responses_record) and
            // reports whether the reaction was ACTUALLY withdrawn -- this
            // is the proof the pyStr-formatted body genuinely string-
            // matched str(the reaction's created_at) inside the real fold's
            // retracted-set comparison, not just that the retract message
            // was accepted onto the wire.
            val retracted = node.awaitEvent("retracted")
            assertTrue("the real hearth fold must have actually withdrawn the retracted reaction",
                retracted.getBoolean("applied"))
        } finally {
            node.kill()
        }
    }
}
