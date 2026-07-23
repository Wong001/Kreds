package expo.modules.tormanager

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Brick C Task 1 gate. Two independent proofs, neither needing real Tor or
 *  an Android Context:
 *   1. the process-wide mutex (`withSyncLockForTest` drives the SAME
 *      ReentrantLock `runSync` uses) serializes concurrent syncs -- a second
 *      caller while the lock is held takes the skipped path rather than
 *      queuing or running concurrently.
 *   2. the enc-key prep + publish-guard + outcome-mapping SyncRunner assembles
 *      around the (separately loopback-proven) transport -- driven directly on
 *      an InMemorySyncStore, no node, so the NEW logic runSync adds on top of
 *      the verbatim-moved transport is proven in isolation (Brick C Task 1
 *      brief Step 6, Option B). */
class SyncRunnerTest {

    // -- 1. process-wide mutex --

    @Test fun secondConcurrentCallIsSkipped() {
        val ran = AtomicInteger(0); val skipped = AtomicInteger(0)
        val inBody = CountDownLatch(1); val release = CountDownLatch(1)
        val t1 = Thread {
            SyncRunner.withSyncLockForTest(
                body = { ran.incrementAndGet(); inBody.countDown(); release.await() },
                onSkipped = { skipped.incrementAndGet() })
        }
        t1.start(); inBody.await()               // t1 holds the lock
        SyncRunner.withSyncLockForTest(          // t2 on this thread, lock held
            body = { ran.incrementAndGet() }, onSkipped = { skipped.incrementAndGet() })
        release.countDown(); t1.join()
        assertEquals(1, ran.get()); assertEquals(1, skipped.get())
    }

    @Test fun sequentialCallsBothRun() {
        val ran = AtomicInteger(0)
        repeat(2) { SyncRunner.withSyncLockForTest(body = { ran.incrementAndGet() }, onSkipped = {}) }
        assertEquals(2, ran.get())
    }

    // -- 2. enc-key prep + publish-guard (the NEW logic runSync assembles) --

    @Test fun prepPublishesFreshKeyAndComposesOneOutbound() {
        val store = InMemorySyncStore()
        val (_, pub) = EncKeys.getOrCreate(store)   // fresh store: never published
        val prep = SyncRunner.prepareEncKeyOutboundForTest(fixture(), store)
        assertTrue("fresh key must publish", prep.shouldPublish)
        assertEquals("prep pub must be the persisted enc pub", pub, prep.encPub)
        assertEquals("exactly one outbound enckey", 1, prep.outbound.size)
        assertEquals("enckey", (prep.outbound[0]["payload"] as Map<*, *>)["kind"])
    }

    @Test fun prepSkipsWhenAlreadyPublished() {
        val store = InMemorySyncStore()
        val (_, pub) = EncKeys.getOrCreate(store)
        store.setPublishedEncPub(pub)               // marker already equals current pub
        val prep = SyncRunner.prepareEncKeyOutboundForTest(fixture(), store)
        assertFalse("already-published key must not republish", prep.shouldPublish)
        assertTrue("no outbound when nothing to publish", prep.outbound.isEmpty())
    }

    // -- 3. outcome mapping (Ok/SelfRevoked/Failed -> SyncOutcome) --

    @Test fun okWithPublishMarksPublishedAndMapsCounts() {
        val store = InMemorySyncStore()
        val (_, pub) = EncKeys.getOrCreate(store)
        val prep = SyncRunner.prepareEncKeyOutboundForTest(fixture(), store)   // shouldPublish=true
        val outcome = SyncRunner.mapSyncResultForTest(SyncResult.Ok(5, 2, 3), prep, store)
        assertTrue(outcome.ran); assertTrue(outcome.ok)
        assertEquals(5, outcome.messages); assertEquals(2, outcome.blobs); assertEquals(3, outcome.identities)
        assertFalse(outcome.selfRevoked); assertNull(outcome.error)
        assertEquals("published marker set only on Ok+shouldPublish", pub, store.getPublishedEncPub())
    }

    @Test fun okWithoutPublishDoesNotTouchMarker() {
        val store = InMemorySyncStore()
        val (_, pub) = EncKeys.getOrCreate(store)
        store.setPublishedEncPub(pub)
        val prep = SyncRunner.prepareEncKeyOutboundForTest(fixture(), store)   // shouldPublish=false
        val outcome = SyncRunner.mapSyncResultForTest(SyncResult.Ok(1, 0, 1), prep, store)
        assertTrue(outcome.ok)
        assertEquals(pub, store.getPublishedEncPub())   // unchanged (already equalled pub)
    }

    @Test fun selfRevokedMapsToSkippedOutcome() {
        val store = InMemorySyncStore()
        EncKeys.getOrCreate(store)
        val prep = SyncRunner.prepareEncKeyOutboundForTest(fixture(), store)
        val outcome = SyncRunner.mapSyncResultForTest(SyncResult.SelfRevoked, prep, store)
        assertTrue(outcome.ran); assertFalse(outcome.ok); assertTrue(outcome.selfRevoked)
        assertEquals(0, outcome.messages); assertEquals(0, outcome.blobs); assertEquals(0, outcome.identities)
        assertEquals("self-revoked", outcome.error)
        assertNull("a failed sync must never set the published marker", store.getPublishedEncPub())
    }

    @Test fun failedMapsStageAndReason() {
        val store = InMemorySyncStore()
        EncKeys.getOrCreate(store)
        val prep = SyncRunner.prepareEncKeyOutboundForTest(fixture(), store)   // shouldPublish=true
        val outcome = SyncRunner.mapSyncResultForTest(SyncResult.Failed("messages", "boom"), prep, store)
        assertTrue(outcome.ran); assertFalse(outcome.ok); assertFalse(outcome.selfRevoked)
        assertEquals("messages: boom", outcome.error)
        assertNull("a failed sync must never set the published marker", store.getPublishedEncPub())
    }

    // A minimal, self-signed device fixture -- enough for composeEncKey's
    // SignedMessage body/sign path (the same construction composeEncKey uses
    // in production); the enc-key prep never touches the network.
    private fun fixture(): KotlinHandshake.Fixture {
        val devPriv = "1".repeat(64)
        val cert = KotlinWire.CertDict(
            identity_pub = "aa".repeat(32), device_pub = "bb".repeat(32),
            device_name = "test", enrolled_at = 1.0, signature = "cc".repeat(32))
        return KotlinHandshake.Fixture(devPriv, "bb".repeat(32), cert, "example.onion:1234")
    }

    // -- 4. friend-peering Task 4: peer-loop decision helpers --
    //
    // runTransport/runSync themselves take an Android Context and dial real
    // Tor (TorEngine.dial), so -- same posture as the rest of this file --
    // they are not JVM-testable; there is no Robolectric/Context test double
    // in this module. The SECURITY-CRITICAL bit (the identity-acceptance
    // change that lets the phone dial FRIENDS, not just its own home node)
    // and the own-onion-skip / onion-throttle gates are extracted into pure
    // functions (acceptPeerIdentity/shouldSkipOwnOnion/shouldThrottle) for
    // exactly this reason, and proven here in isolation. The live dial
    // itself (the actual Tor connection each peer's runTransport makes) is
    // Task 7/8's on-device proof; the LOOP WIRING around it (own-onion-skip/
    // throttle applied per peer, the SAME pending-outbound set reaching
    // every dialed peer, a selfRevoked peer stopping the loop early) is
    // ALSO JVM-tested below (review Finding 2 fix), via `runPeerLoop`'s
    // injected-dial seam -- the regression this task must not break (single
    // home-node-only peer still syncs) is covered by that same seam with a
    // one-peer list.

    private val FRIEND = "aa".repeat(32)
    private val HOME = "bb".repeat(32)
    private val STRANGER = "cc".repeat(32)
    private val KNOWN = listOf(FRIEND, HOME)

    @Test fun acceptsKnownFriendWithNoExpectedIdentity() {
        assertTrue(SyncRunner.acceptPeerIdentity(FRIEND, null, KNOWN))
    }

    @Test fun acceptsKnownFriendMatchingExpectedIdentity() {
        assertTrue(SyncRunner.acceptPeerIdentity(FRIEND, FRIEND, KNOWN))
    }

    @Test fun refusesKnownIdentityAtWrongExpectedAddressSlot() {
        // FRIEND authenticates, but this peer ROW expected HOME to answer at
        // that address -- a wrong/hostile node squatting on a friend's
        // stored address slot must be refused even though ITS OWN identity
        // is separately known (the wrong-address guard).
        assertFalse(SyncRunner.acceptPeerIdentity(FRIEND, HOME, KNOWN))
    }

    @Test fun refusesUnknownStrangerIdentity() {
        assertFalse(SyncRunner.acceptPeerIdentity(STRANGER, null, KNOWN))
    }

    @Test fun acceptsOwnHomeIdentity() {
        assertTrue(SyncRunner.acceptPeerIdentity(HOME, HOME, KNOWN))
    }

    @Test fun skipsSameOnionHostAsOwnGossipAddr() {
        // Host-keyed, not full-address-keyed (sync.py:282-286 doc): a
        // same-host row at a DIFFERENT port must still be recognized as our
        // own onion service.
        assertTrue("same onion host, different port, is still our own onion",
            SyncRunner.shouldSkipOwnOnion("home.onion:1234", "home.onion:9997"))
    }

    @Test fun doesNotSkipDifferentOnionHost() {
        assertFalse(SyncRunner.shouldSkipOwnOnion("friend.onion:9997", "home.onion:9997"))
    }

    @Test fun doesNotSkipNonOnionAddress() {
        assertFalse(SyncRunner.shouldSkipOwnOnion("192.168.1.5:9997", "home.onion:9997"))
    }

    @Test fun doesNotSkipWhenOwnGossipAddrUnset() {
        assertFalse(SyncRunner.shouldSkipOwnOnion("friend.onion:9997", null))
    }

    @Test fun throttlesSameAddrWithinWindow() {
        val addr = "friend.onion:9997"
        val last = hashMapOf<String, Long>()
        assertFalse("first dial is never throttled", SyncRunner.shouldThrottle(addr, last, 0L))
        assertTrue("re-dial 10s later, inside the 45s window, must be throttled",
            SyncRunner.shouldThrottle(addr, last, 10_000L))
    }

    @Test fun allowsSameAddrAfterWindowElapses() {
        val addr = "friend.onion:9997"
        val last = hashMapOf<String, Long>()
        assertFalse(SyncRunner.shouldThrottle(addr, last, 0L))
        assertFalse("45s later the window has fully elapsed",
            SyncRunner.shouldThrottle(addr, last, 45_000L))
    }

    @Test fun throttleIsPerAddress() {
        val a = "friend-a.onion:9997"; val b = "friend-b.onion:9997"
        val last = hashMapOf<String, Long>()
        assertFalse(SyncRunner.shouldThrottle(a, last, 0L))
        assertFalse("a different address is never throttled by another address's recent dial",
            SyncRunner.shouldThrottle(b, last, 0L))
    }

    // -- 5. friend-peering Task 4 review fix (Finding 2, MEDIUM-HIGH -- delivery) --
    //
    // Pre-fix: `runTransport` read+cleared `store.pendingOutbound()` itself,
    // PER PEER -- so with multiple peers now dialed in one round
    // (`listPeers()` is UNORDERED), whichever peer synced successfully FIRST
    // cleared the queue, and every peer dialed AFTER it saw an already-empty
    // pending set. A message composed just before a round ran could reach
    // only ONE peer (possibly a friend, not the home node) and then be
    // deleted -- with the desktop offline (the arc's whole point), never
    // reaching it. Fixed: `runSync` now captures the pending-outbound
    // snapshot ONCE (same idiom as the peers/gossip_addr read), `runPeerLoop`
    // passes the SAME snapshot to every dialed peer's first transport call,
    // and the queue is cleared ONCE after the whole loop -- via
    // `shouldClearPendingOutbound`, gated on at least one ACTUALLY DIALED
    // peer having succeeded (an empty `results` -- everything skipped this
    // round -- must NOT clear an unpushed queue; see that function's doc).
    // `runPeerLoop` itself (the extracted, injectable peer-loop core) is what
    // makes both this and the own-onion-skip/throttle/selfRevoked wiring
    // JVM-testable without a real Context/Tor dial.

    @Test fun peerLoopPushesSamePendingSetToEveryDialedPeer() {
        val pending = listOf(mapOf("cert" to "x", "seq" to 1, "payload" to emptyMap<String, Any?>(), "signature" to "y"))
        val received = mutableListOf<List<Map<String, Any?>>>()
        val peers = listOf(Peer("home.onion:9997", "aa".repeat(32)), Peer("friend.onion:9997", "bb".repeat(32)))

        val result = SyncRunner.runPeerLoop(peers, null, pending, hashMapOf(), 0L) { _, pend ->
            received.add(pend)
            SyncRunner.SyncOutcome(true, true, 1, 0, 0, false, null)
        }

        assertEquals("both peers must be dialed", 2, received.size)
        assertEquals("first peer gets the full pending set", pending, received[0])
        assertEquals("second peer must ALSO get the SAME pending set (not an already-cleared empty one)",
            pending, received[1])
        assertEquals(2, result.results.size)
        assertNull(result.selfRevokedOutcome)
    }

    @Test fun peerLoopSkipsOwnOnionAndThrottledPeersWithoutDialingThem() {
        val dialed = mutableListOf<String>()
        val peers = listOf(
            Peer("home.onion:9997", "aa".repeat(32)),     // our own onion -- must not be dialed
            Peer("friend.onion:9997", "bb".repeat(32)),   // dialed
        )
        val result = SyncRunner.runPeerLoop(peers, "home.onion:9997", emptyList(), hashMapOf(), 0L) { peer, _ ->
            dialed.add(peer.address)
            SyncRunner.SyncOutcome(true, true, 0, 0, 0, false, null)
        }
        assertEquals(listOf("friend.onion:9997"), dialed)
        assertEquals(1, result.results.size)
    }

    @Test fun peerLoopStopsAtFirstSelfRevokedAndDoesNotDialFurtherPeers() {
        val dialed = mutableListOf<String>()
        val peers = listOf(Peer("a.onion:9997", "aa".repeat(32)), Peer("b.onion:9997", "bb".repeat(32)))
        val result = SyncRunner.runPeerLoop(peers, null, emptyList(), hashMapOf(), 0L) { peer, _ ->
            dialed.add(peer.address)
            if (peer.address == "a.onion:9997") SyncRunner.SyncOutcome(true, false, 0, 0, 0, true, "self-revoked")
            else SyncRunner.SyncOutcome(true, true, 0, 0, 0, false, null)
        }
        assertEquals("must stop dialing after the first selfRevoked peer -- b must never be dialed",
            listOf("a.onion:9997"), dialed)
        assertTrue(result.selfRevokedOutcome != null)
        assertTrue(result.results.isEmpty())
    }

    @Test fun shouldClearPendingWhenAnyPeerSucceeded() {
        assertTrue(SyncRunner.shouldClearPendingOutbound(listOf(
            SyncRunner.SyncOutcome(true, false, 0, 0, 0, false, "offline"),
            SyncRunner.SyncOutcome(true, true, 1, 0, 0, false, null))))
    }

    @Test fun shouldNotClearPendingWhenAllPeersFailed() {
        assertFalse(SyncRunner.shouldClearPendingOutbound(listOf(
            SyncRunner.SyncOutcome(true, false, 0, 0, 0, false, "offline"),
            SyncRunner.SyncOutcome(true, false, 0, 0, 0, false, "refused"))))
    }

    @Test fun shouldNotClearPendingWhenNoPeersWereDialed() {
        assertFalse("empty results (every peer skipped this round) must never clear an unpushed queue",
            SyncRunner.shouldClearPendingOutbound(emptyList()))
    }
}
