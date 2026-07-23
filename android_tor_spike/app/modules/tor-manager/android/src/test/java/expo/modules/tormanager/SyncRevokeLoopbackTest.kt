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
 *     ordinary friend (`store.addIdentity` + a registrar profile message,
 *     the exact same shape `SyncServeLoopbackTest.kt`'s own
 *     `friendReceivesOnlyEntitledContentOverServeNegative` uses to
 *     register `deviceViews`) and holds a kreds POST wrapped to this
 *     node's device -- exactly the kind of content that sibling test
 *     proves DOES get served to an ordinary, non-defriended friend. This
 *     node, instead of dialing empty-handed, first calls the REAL
 *     `node.unfriend(phoneIdentityPub)` (hearth's own production method,
 *     node.py:1732-1744 -- never a hand-built notice): local teardown,
 *     then queues a genuinely identity-signed `DefriendNotice` for
 *     direct-only delivery on this very dial (Task 8's new "defriend"
 *     scenario in `sync_loopback_node.py` -- see `_run_dial`'s own doc
 *     there for the exact mechanics).
 *
 *     PROTOCOL-SYMMETRY NOTE (read before "fixing" this test to look more
 *     like the entitlement-negative above): `node.unfriend`'s local
 *     teardown runs BEFORE the notice is queued, so by the time this node
 *     dials, ITS OWN `store.is_known(phoneIdentityPub)` is already False
 *     -- meaning this node's own `_session`, on its own side, ALSO
 *     independently stops right after the DEFRIENDS phase (hearth's own
 *     `if not store.is_known(peer_identity): return`, sync.py:755-758),
 *     never itself asking for HAVE/MESSAGES. This is REAL production
 *     behavior (`Node.deliver_defriends` always dials a target it has
 *     already forgotten locally), not a test artifact -- but it does mean
 *     `received_ids` coming back empty, by itself, cannot distinguish
 *     "the phone's gate fired" from "this node never asked in the first
 *     place". Two further checks close that gap:
 *       - `applied` (the DEFRIENDS-phase application-level ack the peer
 *         reports back, `_session`'s own `applied_by_peer`) must name
 *         THIS node's identity_pub -- proof the phone's OWN
 *         `store.applyDefriendNotice` genuinely ran, matched this exact
 *         authenticated session, and returned true; not merely that a
 *         notice crossed the wire.
 *       - After the dial, `store.knownIdentities()` (the phone's own
 *         `InMemorySyncStore` -- the SAME reference `GossipServer` holds,
 *         still this JVM process) must no longer contain this node's
 *         identity -- `removeIdentity` genuinely landed, not just an
 *         in-flight wire claim.
 *     Together with the seeded entitled post (content a normal friend
 *     sync WOULD deliver -- the positive baseline
 *     `friendReceivesOnlyEntitledContentOverServeNegative` already
 *     establishes), an empty `received_ids` here is the real proof that
 *     `KotlinSync.serve`'s mid-session re-check (KotlinSync.kt:558-583,
 *     phone-onion-reachability Task 5) -- which runs AFTER REVOCATIONS
 *     and DEFRIENDS but BEFORE HAVE -- is what stands between a
 *     just-defriended peer and content it would otherwise have received,
 *     at the real wire, not merely in the already-thorough Kotlin-only
 *     unit coverage `DefriendNoticeTest.kt` gives `applyDefriendNotice`
 *     in isolation.
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

        // Registers deviceViews(nodeIdentityPub) = {nodeDevicePub} -- mirrors
        // SyncServeLoopbackTest.kt's own friendReceivesOnlyEntitledContentOverServeNegative
        // exactly: the exact device the REAL connecting node will authenticate
        // as. PLAINTEXT (KIND_PROFILE, no audience gate), signed by the
        // node's own device.
        val nodeRegistrar = signedMsg(nodeCert, nodeDevicePriv, 1,
            mapOf("kind" to "profile", "name" to "Node", "created_at" to KotlinWire.PyFloat(1.0)))
        assertTrue(store.ingestMessage(nodeRegistrar))

        // An entitled kreds post -- wrapped to the node's device -- that a
        // normal (non-defriended) friend sync WOULD serve, exactly the
        // positive case friendReceivesOnlyEntitledContentOverServeNegative
        // already proves for an unrelated friend. This is what makes the
        // empty received_ids assertion below meaningful rather than vacuous:
        // there IS real entitled content on the wire this session, and it
        // must not cross it.
        val entitledPost = signedMsg(phoneCert, phoneDevicePriv, 2, mapOf(
            "kind" to "post", "scope" to "kreds", "placement" to "journal",
            "body_nonce" to "11".repeat(12), "body_ct" to "ab".repeat(8),
            "wraps" to wrapsFor(nodeDevicePub), "blobs" to emptyList<String>(),
            "created_at" to KotlinWire.PyFloat(2.0), "media" to "photo"))
        assertTrue(store.ingestMessage(entitledPost))

        val gossipServer = GossipServer(store, { phoneFixture }, ReentrantLock(), 0)
        val port = gossipServer.start()
        try {
            val spec = dialSpec(
                scenario = "defriend", port = port, identityPriv = nodeIdentityPriv,
                devicePriv = nodeDevicePriv, devicePub = nodeDevicePub, deviceName = "Node Device",
                cert = nodeCert, phoneIdentityPub = phoneIdentityPub)
            val (proc, stdout) = spawnDialNode(spec)
            try {
                val event = readDialEvent(proc, stdout)
                assertEquals("dial event: $event", "served", event.optString("event"))
                assertTrue("session must complete without an unrelated failure: $event",
                    event.getBoolean("ok"))

                // THE REAL APPLICATION-LEVEL ACK: the phone's own
                // store.applyDefriendNotice genuinely returned true for THIS
                // authenticated session, credited to the node's real
                // identity -- not merely "a notice was accepted onto the
                // wire".
                val applied = event.getJSONArray("applied")
                val appliedSet = (0 until applied.length()).map { applied.getString(it) }.toSet()
                assertTrue("phone must have genuinely applied the defriend notice this session: $event",
                    appliedSet.contains(nodeIdentityPub))

                // THE OVER-SERVE-STOPS NEGATIVE: despite the entitled post
                // above being exactly the kind of content a known-friend
                // sync would otherwise deliver, this node received NOTHING
                // -- KotlinSync.serve's mid-session re-check (after
                // REVOCATIONS + DEFRIENDS) cut the session before
                // HAVE/MESSAGES ever ran.
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
