package expo.modules.tormanager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.concurrent.locks.ReentrantLock
import org.junit.Test

/** Task 7 (friend-peering arc): the loopback FIDELITY GATE for the arc's
 *  headline capability. Tasks 1-6 gave the phone a peer table (addPeer/
 *  listPeers/removePeer/addressFor), made `SyncRunner`'s peer loop dial
 *  EVERY stored peer -- friends, not just the hardcoded home node -- fixed a
 *  friend-injection hole and an addr-attribution bug (Task 4's review), and
 *  added adaptive cadence (Task 6). None of that has yet been proven against
 *  a REAL hearth node over a real socket. This file is that proof, both
 *  directions:
 *
 *   1. phoneDialsFriendPullsEntitledContentPushesOwnAndAttributesAddress --
 *      PHONE-DIALS-FRIEND, the genuinely NEW direction: every prior loopback
 *      gate in this codebase (SyncLoopbackTest, SyncComposeLoopbackTest,
 *      SyncResponseLoopbackTest, SyncDmLoopbackTest, SyncRegrantLoopbackTest,
 *      GossipServerTest's own in-process client) has the served node SHARE
 *      the phone's own identity. Mirrors SyncComposeLoopbackTest.kt's idiom
 *      (SocketStream + authOnlyOverStream + KotlinSync.run) -- but unlike
 *      that file's home-node-only call, which omits ownIdentity/peerIdentity
 *      entirely, this passes BOTH, exercising friend-peering Task 4's
 *      friend-acceptance path (peerIdentity != ownIdentity) and Task 4
 *      review Finding 1's addr-attribution fix (a friend's advertised addr
 *      must land under the FRIEND's identity_pub, never the phone's own).
 *      Also drives `SyncRunner.acceptPeerIdentity` -- the actual production
 *      security gate `runTransport` calls before ever handing a connection
 *      to `KotlinSync.run` -- against a REAL AUTH'd identity over a real
 *      socket, not just the pure-string fixtures SyncRunnerTest already
 *      covers it with, at both an address-only peer row (identity not yet
 *      confirmed, gate 1 only) and, for the second connection, a row
 *      `run`'s own HAVE-phase merge has since corrected (gate 2 too).
 *
 *      Entitlement (over-pull negative): the friend node also holds an
 *      inner-ring record naming the phone's identity as a member -- RING is
 *      author-private (an IDENTITY-level routing gate, not a device-level
 *      one -- see sync_loopback_node.py's `_run_peer_friend` doc for exactly
 *      why this shape, not a second wrapped post, is what proves the
 *      negative without an own-device-fanout loophole). Asserted absent from
 *      the phone's own store after a sync that DID successfully pull a
 *      DIFFERENT, genuinely entitled post from the same friend -- the
 *      strongest available shape: a peer that would deliver extra content if
 *      the entitlement filter were bypassed, not one that simply never
 *      offers anything.
 *
 *   2. friendDialingInAdvertisesAddressUpdatingPhonesPeerTable -- fills one
 *      genuine, narrow gap in SyncServeLoopbackTest.kt's existing
 *      friend-dials-phone coverage. That file's
 *      friendReceivesOnlyEntitledContentOverServeNegative and
 *      strangerIsRefusedAtAuthNothingServed already comprehensively prove
 *      the CONTENT side of a friend dialing in (entitled-post delivery, the
 *      3-way over-serve negative, stranger refusal) -- re-proving any of
 *      that here would be pure duplication, so this file does not. What NONE
 *      of those tests exercise: `KotlinSync.serve`'s own HAVE-phase address
 *      merge (`store.mergePeerAddress(peerCert.identity_pub, peerAddr)`, the
 *      RESPONDER-side twin of `run`'s already-proven copy) -- every existing
 *      `_run_dial` sub-scenario dials with NO `gossip_addr` ever set on the
 *      dialing node (`_node_from_external_keys` never calls
 *      `store.set_meta`), so `have["addr"]` is always empty on the wire and
 *      that merge branch never actually runs in any prior real-wire test.
 *      Exercised here via a minimal, additive `_run_dial`/`dialSpec`
 *      extension (`gossip_addr`/`gossipAddr`, consumed only if present --
 *      every pre-existing call site omits it and is byte-unchanged) and
 *      asserted against the phone's OWN peer table (`store.addressFor`).
 */
class SyncPeerLoopbackTest {

    @Test fun phoneDialsFriendPullsEntitledContentPushesOwnAndAttributesAddress() {
        val node = startNode("peer_friend")
        try {
            val fx = KotlinHandshake.parseFixture(node.fixtureJson)
            val info = JSONObject(node.fixtureJson)
            val friendIdentityPub = info.getString("friend_identity_pub")
            val nonEntitledMsgId = info.getString("nonentitled_msg_id")
            val friendAddr = "127.0.0.1:${node.port}"

            val store = InMemorySyncStore()
            store.addIdentity(fx.cert.identity_pub)
            // Task 7 brief: seed the phone store with the FRIEND identity
            // (needed both for local ingest admission -- SyncStore.
            // ingestMessage's is_known gate -- and for the FRIEND's real
            // hearth-side entitled-set computation, sync.py:784:
            // `entitled = known & peer_known`, which requires OUR reported
            // `known[]` in HAVE to include the friend) + the friend's
            // loopback address in the peer table, with the identity left
            // NOT yet confirmed (null) -- mirrors a realistic pre-merge
            // state (an address learned some other way, e.g. HAVE-relay or
            // pairing, before the very first sync against it) and, unlike
            // seeding the already-correct mapping up front, is what makes
            // the address-attribution assertions below a genuine proof that
            // `run`'s own HAVE-phase merge performed the attribution --
            // not a restatement of what this test already seeded.
            store.addIdentity(friendIdentityPub)
            store.addPeer(friendAddr, null)

            // -- Connection 1: AUTH, then mirror SyncRunner.runTransport's
            // OWN security gate (acceptPeerIdentity) against this REAL
            // AUTH'd cert -- not the pure-string fixtures SyncRunnerTest
            // already covers it with -- before ever handing the stream to
            // KotlinSync.run, exactly production order. expectedIdentity is
            // null here (gate 2 skipped): the seeded peer row's identity is
            // not yet confirmed, so only gate 1 (known-identity) applies.
            val stream1 = SocketStream("127.0.0.1", node.port)
            val peerCert1 = KotlinHandshake.authOnlyOverStream(stream1, fx)
            assertEquals("AUTH must authenticate as the friend identity we dialed",
                friendIdentityPub, peerCert1.identity_pub)
            assertTrue("SyncRunner's real acceptPeerIdentity gate must accept this AUTH'd friend " +
                "at an address-only peer row (identity not yet confirmed)",
                SyncRunner.acceptPeerIdentity(peerCert1.identity_pub, null, store.knownIdentities()))

            // Push the phone's own device-signed enckey -- real content
            // authored by the phone's own device, exactly what a real sync
            // always pushes first (SyncRunner.prepareEncKeyOutbound) -- "the
            // phone pushes its own" half of the brief, and simultaneously
            // primes friend's compose_post friend-wrap so the ENTITLED post
            // (connection 2) can be wrapped straight to the phone's device
            // inline (see sync_loopback_node.py's _run_peer_friend doc).
            val (_, encPub) = EncKeys.getOrCreate(store)
            val pushedEnckey = KotlinSync.composeEncKey(fx, encPub, store.nextSeq(), 1752900500.0)
            val pushedMsgId = SignedMessageKt.fromDict(pushedEnckey).msgId()

            val res1 = KotlinSync.run(stream1, store, fx.device_pub, outbound = listOf(pushedEnckey),
                ownIdentity = fx.cert.identity_pub, peerIdentity = peerCert1.identity_pub)
            assertTrue("sync 1 (push own enckey, prime the friend-wrap): $res1", res1 is SyncResult.Ok)

            // Deterministic handoff -- see _run_peer_friend's own doc: this
            // event can only fire once friend's REAL store already holds
            // the phone's just-pushed enckey message (read directly off
            // ingested messages, not a script-side shortcut); its own
            // `pushed_ids` field is the friend's independent confirmation
            // of exactly what it received under the phone's identity.
            val ready = node.awaitEvent("entitled_ready")
            val entitledMsgId = ready.getString("msg_id")
            val pushedIds = ready.getJSONArray("pushed_ids")
            val pushedIdSet = (0 until pushedIds.length()).map { pushedIds.getString(it) }.toSet()
            assertTrue("friend must have genuinely ingested the phone's pushed enckey under the phone's identity",
                pushedIdSet.contains(pushedMsgId))

            // -- Connection 2: fresh AUTH (a new TCP connection). By now
            // connection 1's HAVE-phase merge should have corrected the
            // peer row's identity (see the address-attribution assertions
            // below) -- re-derive `expectedIdentity` from the store fresh,
            // exactly like a real second SyncRunner round would, so THIS
            // round's acceptPeerIdentity call also exercises gate 2
            // (expected-identity match), not just gate 1 like connection
            // 1's address-only row did.
            val expectedNow = store.listPeers().firstOrNull { it.address == friendAddr }?.identityPub
            val stream2 = SocketStream("127.0.0.1", node.port)
            val peerCert2 = KotlinHandshake.authOnlyOverStream(stream2, fx)
            assertTrue("SyncRunner's real acceptPeerIdentity gate must accept this AUTH'd friend " +
                "at its now-confirmed address row",
                SyncRunner.acceptPeerIdentity(peerCert2.identity_pub, expectedNow, store.knownIdentities()))

            val res2 = KotlinSync.run(stream2, store, fx.device_pub,
                ownIdentity = fx.cert.identity_pub, peerIdentity = peerCert2.identity_pub)
            assertTrue("sync 2 (pull the entitled post): $res2", res2 is SyncResult.Ok)

            assertTrue("phone must receive the post the friend genuinely wrapped to its device",
                store.allMessages().any { it.msgId == entitledMsgId })
            // THE OVER-PULL NEGATIVE: the friend also holds an inner-ring
            // record naming own's identity as a member -- RING is
            // author-private (hearth store.py:723-724 / SyncStore.kt's
            // identical port), an IDENTITY-level gate with no
            // own-device-fanout loophole (see sync_loopback_node.py's
            // _run_peer_friend doc for exactly why this shape, not a
            // second wrapped post, is what proves the negative). This is
            // genuinely falsifiable, not a tautology: the phone's own
            // store.ingestMessage is_known gate would happily ACCEPT this
            // message if it ever arrived (friendIdentityPub is already
            // known) -- so if hearth's real RING routing gate (store.py's
            // `peer_identity != ipub` check) were ever bypassed or removed,
            // this message WOULD cross the real wire and land here,
            // failing this assertion RED.
            assertFalse("phone must NEVER receive the friend's author-private ring record -- OVER-PULL",
                store.allMessages().any { it.msgId == nonEntitledMsgId })

            // THE PEER-TABLE ADDR-ATTRIBUTION PROOF (friend-peering Task 3 /
            // Task 4 review Finding 1, wire-level): the friend's own
            // advertised address (HAVE's `addr`, set via
            // friend.store.set_meta("gossip_addr", ...) in the scenario)
            // must be attributed to the FRIEND's identity_pub -- never the
            // phone's own, the exact bug Finding 1 fixed (the merge used to
            // hardcode ownIdentity, which -- since `peers` is keyed by
            // address as PRIMARY KEY -- would have overwritten this SAME
            // address-keyed row with the phone's own identity_pub instead,
            // permanently breaking addressFor(friendIdentityPub) and every
            // future acceptPeerIdentity check against this row). Both
            // assertions are independently falsifiable: a reintroduction of
            // that bug fails the first (addressFor(friend) would return
            // null, since the one row would map to ownIdentity instead) AND
            // the second (addressFor(own) would then return this address).
            assertEquals("friend's advertised address must map to the FRIEND's identity",
                friendAddr, store.addressFor(friendIdentityPub))
            assertNull("the friend's address must never be attributed to the phone's OWN identity",
                store.addressFor(fx.cert.identity_pub))
        } finally {
            node.kill()
        }
    }

    @Test fun friendDialingInAdvertisesAddressUpdatingPhonesPeerTable() {
        val (phoneIdentityPriv, phoneIdentityPub) = genKeypair()
        val (phoneDevicePriv, phoneDevicePub) = genKeypair()
        val phoneCert = signedCert(phoneIdentityPriv, phoneIdentityPub, phoneDevicePub, "Phone Device")
        val phoneFixture = KotlinHandshake.Fixture(phoneDevicePriv, phoneDevicePub, phoneCert, "unused.onion:9997")

        val (friendIdentityPriv, friendIdentityPub) = genKeypair()
        val (friendDevicePriv, friendDevicePub) = genKeypair()
        val friendCert = signedCert(friendIdentityPriv, friendIdentityPub, friendDevicePub, "Friend Device")

        val store = InMemorySyncStore()
        store.addIdentity(phoneIdentityPub)
        store.addIdentity(friendIdentityPub)   // isKnown gate: the friend IS known

        // Registers deviceViews(friendIdentityPub) -- not load-bearing for
        // THIS test's own assertion, but required so the real dialing
        // session completes an ordinary, ok=true sync rather than
        // short-circuiting for an unrelated reason (mirrors
        // friendReceivesOnlyEntitledContentOverServeNegative's own use of
        // this exact registrar-message idiom).
        val friendRegistrar = signedMsg(friendCert, friendDevicePriv, 1,
            mapOf("kind" to "profile", "name" to "Friend", "created_at" to KotlinWire.PyFloat(1.0)))
        assertTrue(store.ingestMessage(friendRegistrar))

        val gossipServer = GossipServer(store, { phoneFixture }, ReentrantLock(), 0)
        val port = gossipServer.start()
        try {
            // An arbitrary loopback-shaped address -- this process is never
            // actually dialed AT this address (only its VALUE travels the
            // wire, as the friend's own self-reported `addr`), so it need
            // not be a real listener.
            val friendAddr = "127.0.0.1:19191"
            val spec = dialSpec(
                scenario = "friend", port = port, identityPriv = friendIdentityPriv,
                devicePriv = friendDevicePriv, devicePub = friendDevicePub, deviceName = "Friend Device",
                cert = friendCert, alsoKnown = listOf(phoneIdentityPub), phoneIdentityPub = phoneIdentityPub,
                gossipAddr = friendAddr)
            val (proc, stdout) = spawnDialNode(spec)
            try {
                val event = readDialEvent(proc, stdout)
                assertEquals("dial event: $event", "served", event.optString("event"))
                assertTrue("sync must have completed ok: $event", event.getBoolean("ok"))
            } finally {
                awaitDialExit(proc)
            }

            // THE GAP THIS TEST FILLS: KotlinSync.serve's own HAVE-phase
            // address merge (`store.mergePeerAddress(peerCert.identity_pub,
            // peerAddr)`) must have attributed the friend's self-reported
            // address to the FRIEND's identity -- proven here for the first
            // time at the real wire on the RESPONDER side (the phone
            // answering a friend's dial-in), mirroring `run`'s own
            // already-proven copy of this same merge (this file's other
            // test) and KotlinSyncTest's pure-fake-Stream unit coverage of
            // the same `serve` branch. Falsifiable: with no prior seeding
            // of this address on the phone's side, a broken/bypassed merge
            // leaves the peer table untouched and `addressFor` returns
            // null, failing this assertion RED.
            assertEquals("the dialing friend's advertised address must land under ITS OWN identity",
                friendAddr, store.addressFor(friendIdentityPub))
        } finally {
            gossipServer.stop()
        }
    }
}
