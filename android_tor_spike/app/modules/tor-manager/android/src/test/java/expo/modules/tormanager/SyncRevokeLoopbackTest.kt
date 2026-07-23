package expo.modules.tormanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.concurrent.locks.ReentrantLock
import org.junit.Test

/** Task 8 (phone-onion-reachability, "loopback gate: revoke + defriend at the
 *  real wire"): closes the arc-1 whole-branch-review blocking finding for
 *  real, over the real wire, against a REAL hearth node -- not merely the
 *  already-thorough Kotlin-only unit coverage `RevocationCertTest.kt` /
 *  `DefriendNoticeTest.kt` already give `SyncStore.ingestRevocation` /
 *  `SyncStore.applyDefriendNotice` against hand-built certs/notices. This
 *  file reuses SyncServeLoopbackTest.kt's own INVERSION harness verbatim
 *  (`dialSpec`/`spawnDialNode`/`readDialEvent`/`awaitDialExit`/`signedMsg`/
 *  `wrapsFor`, hoisted `internal` there for exactly this reuse -- see that
 *  file's own doc comment on the hoist): a REAL hearth node, built from
 *  Kotlin-minted Ed25519 keys (`genKeypair`/`signedCert`, hoisted `internal`
 *  in `GossipServerTest.kt`), DIALS the phone's `GossipServer` running over a
 *  store THIS test seeds, driving `KotlinHandshake.respondHandshake` +
 *  `KotlinSync.serve` from the OTHER side of the wire than every earlier
 *  desk-loopback gate in this codebase.
 *
 *  Two sub-scenarios (each its own @Test), matching task-8-brief.md /
 *  the controller's own two-scenario framing exactly:
 *
 *  1. revokedDeviceRefusedNothingServed -- the phone has ALREADY marked
 *     this node's device revoked (`store.markRevoked`, Task 2's store
 *     primitive) before it ever dials. `respondHandshake`'s revoked-device
 *     gate (KotlinHandshake.kt:247-264, phone-onion-reachability Task 5)
 *     sits in the SAME wire slot as the stranger-refusal gate just above
 *     it -- a bare write of `{"t":"refused"}` right after AUTH, landing
 *     exactly where a real initiator's REVOCATIONS-phase read expects it
 *     -- so hearth's real `_session`, dialing as initiator, receives that
 *     frame and raises `PeerRefused` the identical way
 *     `strangerIsRefusedAtAuthNothingServed` (SyncServeLoopbackTest.kt)
 *     already proves for an UNKNOWN identity. The distinguishing setup
 *     here is deliberate: the node's identity IS known to the phone
 *     (`store.addIdentity`) -- this test is specifically pinning the
 *     REVOKED-DEVICE gate, not the stranger gate a sibling test already
 *     covers. Reuses the "friend" dial scenario byte-unchanged (see
 *     `_run_dial`'s own doc in sync_loopback_node.py for why no new
 *     scenario string is needed): a refusal short-circuits before that
 *     scenario's own `received_ids` computation is ever reached, so the
 *     scenario label carries no behavioral weight on this path -- only
 *     what the PHONE'S OWN STORE holds ahead of the dial (`markRevoked`)
 *     determines the outcome.
 *
 *  2. defriendedIdentityNotServedMidSession -- THE OVER-SERVE-STOPS
 *     NEGATIVE, the arc-1 whole-branch-review's own blocking concern,
 *     proven closed at the real wire. The phone knows this node as an
 *     ordinary friend (`store.addIdentity`) and holds a PLAINTEXT marker
 *     authored by the phone's own identity -- content that a normal
 *     friend sync WOULD deliver (see the entitledMarker seeding below for
 *     exactly why this shape, not the wrapped-post shape
 *     `SyncServeLoopbackTest.kt`'s own
 *     `friendReceivesOnlyEntitledContentOverServeNegative` uses for an
 *     UN-defriended friend).
 *
 *     REVISION HISTORY -- READ BEFORE "SIMPLIFYING" THIS TEST BACK: the
 *     first version of this test built the outbound notice via the REAL
 *     `node.unfriend(phoneIdentityPub)` (hearth's own production method,
 *     node.py:1732-1744), which ALSO runs `store.unfriend_teardown` as
 *     part of the same call -- removing the phone from THIS NODE's own
 *     `store.is_known()` before it ever dialed. That made this node's own
 *     `_session`, on its own side, independently stop right after
 *     DEFRIENDS (hearth's own `if not store.is_known(peer_identity):
 *     return`, sync.py:755-758) regardless of what the phone did --
 *     `received_ids` came back empty EITHER WAY, so the test was a FALSE
 *     PASS on the exact gate it claimed to prove. Caught by a real
 *     reviewer mutation test: disabling the phone's own mid-session
 *     re-check (`KotlinSync.serve`, KotlinSync.kt:580-583) did NOT make
 *     that version fail.
 *
 *     THE FIX: `sync_loopback_node.py`'s "defriend" scenario now signs +
 *     queues the notice via `device.make_defriend` + `store.add_outbox`
 *     DIRECTLY, skipping `unfriend_teardown` -- this node's own
 *     `store.is_known(phone)` stays TRUE (seeded via `alsoKnown` below,
 *     same as the "friend" scenario), so its own session genuinely
 *     continues PAST DEFRIENDS and asks for HAVE, exactly like an
 *     ordinary well-behaved friend sync would. The ONLY thing that can
 *     then stop the seeded entitled marker from crossing the wire is the
 *     PHONE's own mid-session re-check.
 *
 *     A CONSEQUENCE OF THIS FIX worth understanding before reading the
 *     assertions below: since this node still believes it is friends with
 *     the phone, when the phone's gate correctly fires, `KotlinSync.serve`
 *     returns WITHOUT ever writing the HAVE reply this node's own `_swap`
 *     is blocked reading -- confirmed empirically, this surfaces as a
 *     generic IO failure (`asyncio.IncompleteReadError`, i.e. the dial's
 *     own `ok=false`), not a clean refusal or a clean "served"
 *     completion. `_run_dial`'s "defriend" branch therefore reports its
 *     own dedicated `defriend_dial_done` event (not the shared
 *     refused/failed/served shape) whose `received_ids` field is computed
 *     directly off `node.store` regardless of how the dial itself
 *     completed or failed -- the one fact that stays unambiguous and
 *     directly observable either way, and the one fact a mutation of the
 *     phone's gate flips (see MUTATION-VERIFICATION EVIDENCE below).
 *
 *     MUTATION-VERIFICATION EVIDENCE (the load-bearing proof this test is
 *     actually falsifiable on the gate it claims to prove -- reviewer-
 *     mandated after the ORIGINAL node.unfriend-based version silently
 *     passed under this exact mutation, see REVISION HISTORY above).
 *     `KotlinSync.serve`'s mid-session re-check (KotlinSync.kt:580-583)
 *     was changed to `if (false && (...))` (dead code -- the gate never
 *     fires) and the full `SyncRevokeLoopbackTest` suite re-run:
 *       - `defriendedIdentityNotServedMidSession` FAILED --
 *         `expected:<0> but was:<1>`, against the dial event
 *         `{"received_ids":["14b4fe3f..."],"applied":["56348da5..."],
 *         "event":"defriend_dial_done","ok":true,"peer_identity":
 *         "4673a6d5..."}` -- `ok:true` (the session ran all the way
 *         through HAVE/MESSAGES/BLOBS to completion, unlike the expected
 *         `ok:false` IO-failure shape with the gate intact), `applied`
 *         non-empty (the DEFRIENDS-phase ack, only reachable via a full
 *         normal return), and `received_ids` naming exactly the seeded
 *         entitledMarker's msg_id -- the over-serve the gate exists to
 *         prevent, reproduced on demand.
 *       - `revokedDeviceRefusedNothingServed` still PASSED, unaffected --
 *         confirms the mutation is scoped to the mid-session gate only,
 *         not a blunt instrument that breaks Scenario 1 too.
 *     The mutation was then reverted (`git diff` on `KotlinSync.kt`
 *     empty, confirming byte-for-byte restoration) and the full suite
 *     re-run clean: both tests pass again, full `:tor-manager:
 *     testDebugUnitTest` 372/372, full hearth pytest 1105 passed / 9
 *     skipped.
 *
 *     A THIRD scenario (a node relaying a REVOCATIONS-phase cert that
 *     marks some OTHER, third device revoked, then a SEPARATE dial from
 *     that third device proving IT gets refused) was considered and
 *     dropped as not cheap: the wire-level REVOCATIONS relay + ingest
 *     mechanics it would exercise are already unit-proven end-to-end by
 *     `RevocationCertTest.ingestRevocationAcceptsKnownIdentityMarksRevokedAndRetroDrops`,
 *     and the "does a revoked device get refused" half is exactly
 *     `revokedDeviceRefusedNothingServed` above -- a third scenario would
 *     mostly be gluing those two already-proven pieces together behind a
 *     SECOND real minted identity and a SECOND full dial, for marginal
 *     new signal.
 */
class SyncRevokeLoopbackTest {

    @Test fun revokedDeviceRefusedNothingServed() {
        val (nodeIdentityPriv, nodeIdentityPub) = genKeypair()
        val (nodeDevicePriv, nodeDevicePub) = genKeypair()
        val nodeCert = signedCert(nodeIdentityPriv, nodeIdentityPub, nodeDevicePub, "Node Device")

        val (phoneIdentityPriv, phoneIdentityPub) = genKeypair()
        val (phoneDevicePriv, phoneDevicePub) = genKeypair()
        val phoneCert = signedCert(phoneIdentityPriv, phoneIdentityPub, phoneDevicePub, "Phone Device")
        val phoneFixture = KotlinHandshake.Fixture(phoneDevicePriv, phoneDevicePub, phoneCert, "unused.onion:9997")

        val store = InMemorySyncStore()
        store.addIdentity(phoneIdentityPub)
        store.addIdentity(nodeIdentityPub)   // KNOWN identity -- this test is
                                              // specifically about the REVOKED-
                                              // DEVICE gate, not the unknown-
                                              // identity ("stranger") gate a
                                              // sibling test already covers.
        store.markRevoked(nodeDevicePub, 0)  // Task 2's store primitive -- the
                                              // exact state respondHandshake's
                                              // isRevoked lambda (SyncStore.
                                              // isRevokedDevice, wired in
                                              // GossipServer.kt) consults.

        val gossipServer = GossipServer(store, { phoneFixture }, ReentrantLock(), 0)
        val port = gossipServer.start()
        try {
            val spec = dialSpec(
                scenario = "friend", port = port, identityPriv = nodeIdentityPriv,
                devicePriv = nodeDevicePriv, devicePub = nodeDevicePub, deviceName = "Node Device",
                cert = nodeCert, alsoKnown = listOf(phoneIdentityPub), phoneIdentityPub = phoneIdentityPub)
            val (proc, stdout) = spawnDialNode(spec)
            try {
                val event = readDialEvent(proc, stdout)
                assertEquals("dial event: $event -- a divergence here (over-serve, or a wrong " +
                    "failure shape) is a REAL security-gate failure, not a harness issue",
                    "refused", event.optString("event"))
                // PeerRefused.peer_identity names the party that refused US
                // (the phone), not this node's own dialing identity -- see
                // strangerIsRefusedAtAuthNothingServed's own doc for the
                // empirical trace pinning this down; same wire mechanism,
                // same assertion shape, different phone-side gate.
                assertEquals("phone must be the identity that refused -- proves this was the " +
                    "revoked-device gate's own refusal, not an unrelated failure",
                    phoneIdentityPub, event.optString("peer_identity"))
            } finally {
                awaitDialExit(proc)
            }
        } finally {
            gossipServer.stop()
        }
    }

    @Test fun defriendedIdentityNotServedMidSession() {
        val (phoneIdentityPriv, phoneIdentityPub) = genKeypair()
        val (phoneDevicePriv, phoneDevicePub) = genKeypair()
        val phoneCert = signedCert(phoneIdentityPriv, phoneIdentityPub, phoneDevicePub, "Phone Device")
        val phoneFixture = KotlinHandshake.Fixture(phoneDevicePriv, phoneDevicePub, phoneCert, "unused.onion:9997")

        val (nodeIdentityPriv, nodeIdentityPub) = genKeypair()
        val (nodeDevicePriv, nodeDevicePub) = genKeypair()
        val nodeCert = signedCert(nodeIdentityPriv, nodeIdentityPub, nodeDevicePub, "Node Device")

        val store = InMemorySyncStore()
        store.addIdentity(phoneIdentityPub)
        store.addIdentity(nodeIdentityPub)   // the phone knows the node AS A
                                              // FRIEND -- applyDefriendNotice's
                                              // own knownIdentities(author) gate
                                              // requires this (DefriendNotice.kt).

        // ENTITLED content -- a PLAINTEXT marker authored by the PHONE
        // itself (KIND_PROFILE: "Every other kind [besides dm/ring/post/
        // wrap_grant/response/responses] ... is unconditionally servable
        // once past the entitled+seen-delta checks", SyncStore.kt's own
        // messagesNotIn doc) -- that a normal (non-defriended) friend sync
        // WOULD deliver, gated on nothing but `entitled` (this node's own
        // store.is_known(phone), which -- unlike the phone's -- is never
        // torn down here; see the class doc's REVISION HISTORY).
        //
        // Deliberately NOT a "post" wrapped to the node's device (the
        // shape friendReceivesOnlyEntitledContentOverServeNegative uses
        // for an UN-defriended friend, where it's the right choice): a
        // wrap-audience check needs deviceViews(nodeIdentityPub)
        // populated, which in THIS store (InMemorySyncStore.deviceViews,
        // SyncStore.kt:657-661) is derived PURELY from messages this
        // node itself authored -- the exact same messages
        // applyDefriendNotice's purgeAuthoredBy(node) unconditionally
        // deletes as soon as the notice is applied during DEFRIENDS,
        // REGARDLESS of whether the mid-session re-check under test ever
        // runs. A first attempt at this fix used exactly that "post"
        // shape and was caught, by directly inspecting the dial event
        // under the very mutation below, silently filtering the post out
        // for the WRONG reason (a purged deviceViews entry, not the
        // re-check) -- reintroducing a false pass one layer deeper than
        // the original one. A phone-authored, audience-gate-free marker
        // is immune to that purge (node never authored it) and its only
        // gate is `entitled` -- so the mid-session re-check is once again
        // the ONLY thing that can stop it from reaching this node.
        val entitledMarker = signedMsg(phoneCert, phoneDevicePriv, 1,
            mapOf("kind" to "profile", "name" to "PhoneProfile", "created_at" to KotlinWire.PyFloat(1.0)))
        assertTrue(store.ingestMessage(entitledMarker))

        val gossipServer = GossipServer(store, { phoneFixture }, ReentrantLock(), 0)
        val port = gossipServer.start()
        try {
            val spec = dialSpec(
                scenario = "defriend", port = port, identityPriv = nodeIdentityPriv,
                devicePriv = nodeDevicePriv, devicePub = nodeDevicePub, deviceName = "Node Device",
                cert = nodeCert,
                // Load-bearing for THIS fix (see the class doc's REVISION
                // HISTORY): this node must keep believing it knows the
                // phone for the whole dial, so its own session genuinely
                // continues past DEFRIENDS into HAVE instead of
                // independently stopping on its own local gate.
                alsoKnown = listOf(phoneIdentityPub), phoneIdentityPub = phoneIdentityPub)
            val (proc, stdout) = spawnDialNode(spec)
            try {
                val event = readDialEvent(proc, stdout)
                assertEquals("dial event: $event -- a divergence in event shape here is itself " +
                    "informative (see _run_dial's own doc for the expected IO-failure shape) but " +
                    "the load-bearing check is received_ids below, not this event name",
                    "defriend_dial_done", event.optString("event"))

                // THE OVER-SERVE-STOPS NEGATIVE -- the ONLY thing that can
                // make this empty is the PHONE's own mid-session re-check
                // (KotlinSync.serve, KotlinSync.kt:580-583) firing BEFORE
                // HAVE/MESSAGES ever ran: this node's own session does NOT
                // independently stop itself (unlike the original,
                // node.unfriend-based construction -- see REVISION HISTORY
                // above), so if that phone-side gate were missing or
                // mis-ordered, the seeded entitled post WOULD show up here.
                // Proven falsifiable by mutation-testing this exact gate --
                // see MUTATION-VERIFICATION EVIDENCE in the class doc.
                val receivedIds = event.getJSONArray("received_ids")
                assertEquals("defriended peer must receive NOTHING, not even already-entitled " +
                    "content -- an over-serve here is a REAL security-gate failure: $event",
                    0, receivedIds.length())
            } finally {
                awaitDialExit(proc)
            }

            // Independent check, on the PHONE's OWN store (still this JVM
            // process -- GossipServer holds the same `store` reference): the
            // defriend genuinely landed -- removeIdentity ran for real, not
            // just an in-flight wire claim the dial event alone could be
            // mistaken for.
            assertFalse("phone must no longer know the defriended node's identity",
                store.knownIdentities().contains(nodeIdentityPub))
        } finally {
            gossipServer.stop()
        }
    }
}
