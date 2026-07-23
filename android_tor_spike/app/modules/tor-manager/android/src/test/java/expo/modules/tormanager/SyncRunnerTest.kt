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

    // -- whole-branch review fix, Finding 2 (IMPORTANT -- host-keyed
    //    eviction imported without dial-time port normalization) --

    @Test fun dialTargetNormalizesOnionAddressToTheFixedVirtualPortRegardlessOfStoredPort() {
        assertEquals("friend.onion" to TorNodeService.ONION_VIRTUAL_PORT,
            SyncRunner.dialTarget("friend.onion:1234"))
        assertEquals("a stored port that already happens to be 9997 stays 9997",
            "friend.onion" to TorNodeService.ONION_VIRTUAL_PORT,
            SyncRunner.dialTarget("friend.onion:9997"))
    }

    @Test fun dialTargetDialsNonOnionAddressAtItsLiteralStoredPort() {
        assertEquals("192.168.1.5" to 8080, SyncRunner.dialTarget("192.168.1.5:8080"))
    }

    // -- whole-branch review fix, Finding 3 (IMPORTANT -- pre-arc installs
    //    upgrade into an empty peer table and stop dialing out) --

    @Test fun ensureHomePeerSeededAddsHomeNodeRowWhenPeerTableIsEmpty() {
        val store = InMemorySyncStore()
        val fx = fixture()   // fixture()'s onion_addr = "example.onion:1234"
        assertTrue("precondition: a fresh store's peer table is empty", store.listPeers().isEmpty())

        SyncRunner.ensureHomePeerSeeded(store, fx)

        assertEquals("the home node's own identity+onion must now be a stored peer",
            listOf(Peer(fx.onion_addr, fx.cert.identity_pub)), store.listPeers())
    }

    @Test fun ensureHomePeerSeededIsANoOpWhenThePeerTableIsAlreadyNonEmpty() {
        val store = InMemorySyncStore()
        val fx = fixture()
        // Some OTHER peer already on record (e.g. a friend merged in by a
        // normal round) -- the table is non-empty, so this is NOT the
        // pre-arc-install case this fix targets.
        store.addPeer("friend.onion:9997", "bb".repeat(32))

        SyncRunner.ensureHomePeerSeeded(store, fx)

        assertEquals("must not add the home node row when the table was already non-empty",
            listOf(Peer("friend.onion:9997", "bb".repeat(32))), store.listPeers())
    }

    @Test fun ensureHomePeerSeededIsIdempotentAcrossRepeatedCalls() {
        val store = InMemorySyncStore()
        val fx = fixture()
        SyncRunner.ensureHomePeerSeeded(store, fx)
        SyncRunner.ensureHomePeerSeeded(store, fx)   // second call: table is no longer empty
        assertEquals(listOf(Peer(fx.onion_addr, fx.cert.identity_pub)), store.listPeers())
    }

    // -- whole-branch review fix, Finding 4 (IMPORTANT -- pulledNew delta
    //    uses a peer-count-dependent summed total) --
    //
    // The bug: TorNodeService.syncCycle used to derive `nowTotal` from the
    // AGGREGATED SyncOutcome's messages+blobs+identities -- each PEER's own
    // counts are already this device's absolute whole-store totals, so
    // summing across N successful peers scaled `nowTotal` to roughly N x the
    // real store size. `storeTotalAfter` fixes this by being a single,
    // peer-count-independent post-round `store.stats()` read, set ONLY on
    // the final outcome `runSync` returns (via `.copy(...)`, after
    // `aggregate`) -- never folded by `aggregate` itself. These tests prove
    // that structural separation directly.

    @Test fun aggregateNeverFoldsStoreTotalAfterAcrossPeers() {
        // Simulates what WOULD happen if some caller mistakenly attached a
        // per-peer storeTotalAfter reading before folding (the shape of the
        // original bug, just on this field instead of messages/blobs/
        // identities) -- aggregate() must ignore it entirely, not sum it.
        val results = listOf(
            SyncRunner.SyncOutcome(true, true, 5, 2, 1, false, null, storeTotalAfter = 100L),
            SyncRunner.SyncOutcome(true, true, 3, 0, 1, false, null, storeTotalAfter = 250L))
        val out = SyncRunner.aggregateForTest(results)
        assertEquals("storeTotalAfter must NEVER be derived by folding per-peer values",
            0L, out.storeTotalAfter)
    }

    @Test fun aggregateStillSumsTheLegitimateDisplayCountsAcrossPeers() {
        // Regression guard: messages/blobs/identities (the per-peer DISPLAY
        // counts, unaffected by this fix) must still sum -- only
        // storeTotalAfter's derivation changed.
        val results = listOf(
            SyncRunner.SyncOutcome(true, true, 5, 2, 1, false, null),
            SyncRunner.SyncOutcome(true, true, 3, 0, 1, false, null))
        val out = SyncRunner.aggregateForTest(results)
        assertEquals(8, out.messages); assertEquals(2, out.blobs); assertEquals(2, out.identities)
    }

    /** THE flapping-peer-count scenario the finding describes, worked
     *  through `AdaptiveBackoff.pulledNewContent` directly: round 1 has TWO
     *  successful peers, round 2 has only ONE (a friend went offline) --
     *  content genuinely grew by a little in between. Under the OLD
     *  peer-summed shape, round 1's `nowTotal` was inflated by the peer
     *  count (~2x the real store size) while round 2's was not (~1x) --
     *  round 2's smaller-looking total could then read as "no growth" (or
     *  even look like SHRINKAGE, which never happens to a real store total)
     *  purely because a peer dropped out, wrongly suppressing the backoff
     *  reset. The FIX (a single peer-count-independent total) does not have
     *  this failure mode: the assertions below use the REAL single-total
     *  values (peer count never enters the math), and `pulledNewContent`
     *  correctly reports growth. */
    @Test fun pulledNewContentIsPeerCountIndependent() {
        // Round 1: real single-store-total read = 20 (whatever the peer
        // count was that round -- storeTotalAfter never scales with it).
        val afterRound1 = 20L
        // Round 2: one more message arrived (single-store-total read = 21),
        // even though FEWER peers succeeded this round than the last.
        val afterRound2 = 21L
        assertTrue("content genuinely grew -- must read as pulled-new " +
            "regardless of how many peers succeeded either round",
            AdaptiveBackoff.pulledNewContent(prevTotal = afterRound1, newTotal = afterRound2, ran = true, ok = true))
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

    // -- 6. whole-branch review fix, Finding 1 (CRITICAL -- the pending-
    //    outbound push bypasses hearth's per-peer kind gate) --
    //
    // `pendingIdsToClear` REPLACES the old whole-queue boolean
    // `shouldClearPendingOutbound` (`results.any { it.ok }`, clear
    // everything once ANY peer synced ok) -- these three tests are the
    // direct successors of the three `shouldClearPending*` tests this
    // section used to have, rewritten against the new per-message API but
    // proving the SAME three shapes: any-ok clears what was actually sent,
    // all-failed clears nothing, no-peers-dialed clears nothing.

    @Test fun pendingIdsToClearUnionsSentIdsAcrossOkPeers() {
        val toClear = SyncRunner.pendingIdsToClear(listOf(
            SyncRunner.SyncOutcome(true, false, 0, 0, 0, false, "offline"),
            SyncRunner.SyncOutcome(true, true, 1, 0, 0, false, null, sentPendingIds = setOf("m1", "m2"))))
        assertEquals(setOf("m1", "m2"), toClear)
    }

    @Test fun pendingIdsToClearIsEmptyWhenAllPeersFailed() {
        val toClear = SyncRunner.pendingIdsToClear(listOf(
            SyncRunner.SyncOutcome(true, false, 0, 0, 0, false, "offline", sentPendingIds = setOf("m1")),
            SyncRunner.SyncOutcome(true, false, 0, 0, 0, false, "refused")))
        assertTrue("a failed outcome's sentPendingIds must never count, even if non-empty", toClear.isEmpty())
    }

    @Test fun pendingIdsToClearIsEmptyWhenNoPeersWereDialed() {
        assertTrue("empty results (every peer skipped this round) must never clear an unpushed queue",
            SyncRunner.pendingIdsToClear(emptyList()).isEmpty())
    }

    /** THE finding-3 falsifiable case: a DM to friendA, where friendA itself
     *  is unreachable/failed this round and the ONLY peer that synced ok
     *  (friendB) correctly never received the DM in the first place (it
     *  isn't the recipient -- see the filterPendingForPeer tests below).
     *  Under the OLD whole-queue boolean (`results.any { it.ok }` -> clear
     *  the ENTIRE original pending snapshot), friendB's lone success would
     *  have cleared the DM anyway, even though it never reached anyone
     *  entitled to it. `friendAFailed` carries `dmId` in its OWN
     *  `sentPendingIds` -- the per-peer filter DID include the DM for
     *  friendA (it is the recipient), the ATTEMPT to reach friendA is simply
     *  what failed this round -- so a mutant that unions sentPendingIds
     *  across ALL peers (gated only on "did ANY peer succeed", not "did
     *  THIS peer, who actually held the message, succeed") reproduces the
     *  original whole-queue bug exactly and is falsified by this test
     *  (mutation-verified in task-8-report.md: reverting
     *  `pendingIdsToClear` to
     *  `if (results.any { it.ok }) results.flatMap { it.sentPendingIds }.toSet() else emptySet()`
     *  -- dropping the `.filter { it.ok }` -- makes this assertion fail). */
    @Test fun dmToUnreachableFriendStaysPendingWhenOnlyAnotherPeerSucceeded() {
        val dmId = "dm-1"
        val friendAFailed = SyncRunner.SyncOutcome(true, false, 0, 0, 0, false, "offline", sentPendingIds = setOf(dmId))
        val friendBOk = SyncRunner.SyncOutcome(true, true, 3, 0, 1, false, null, sentPendingIds = emptySet())
        val toClear = SyncRunner.pendingIdsToClear(listOf(friendAFailed, friendBOk))
        assertFalse("the DM never actually reached anyone entitled to it this round -- must stay pending",
            dmId in toClear)
        assertTrue(toClear.isEmpty())
    }

    // -- filterPendingForPeer (whole-branch review fix, Finding 1) --
    //
    // A minimal self-signed wire dict builder: filterPendingForPeer/
    // peerMayReceive never verify a signature (that already happened at
    // ingest, long before a message reaches the pending-outbound queue), so
    // an arbitrary signature string is fine -- only kind/payload/cert.
    // identity_pub/msgId matter here. Mirrors SyncStoreTest's own `msg`/
    // `identityMsg` helpers' spirit, simplified since no real Ed25519
    // signing is needed for this pure, unauthenticated-parse path.
    private fun outboundWire(author: String, payload: Map<String, Any?>): SignedMessage {
        val cert = KotlinWire.CertDict(author, "dev-$author".padEnd(64, '0').take(64), "d", 1.0, "00")
        return SignedMessage(cert, 1, payload, "00")
    }

    @Test fun filterPendingForPeerExcludesDmFromEveryoneButAuthorAndRecipient() {
        val home = "aa".repeat(32)      // ownIdentity -- the DM's author
        val friendA = "bb".repeat(32)   // the DM's recipient
        val friendB = "cc".repeat(32)   // neither author nor recipient
        val dm = outboundWire(home, mapOf("kind" to "dm", "to" to friendA, "wraps" to emptyMap<String, Any?>()))
        val pending = listOf(dm.toDict())

        val toFriendB = SyncRunner.filterPendingForPeer(pending, friendB, emptySet())
        assertTrue("a DM to friendA must NOT reach friendB", toFriendB.wireDicts.isEmpty())
        assertFalse(dm.msgId() in toFriendB.msgIds)

        val toFriendA = SyncRunner.filterPendingForPeer(pending, friendA, emptySet())
        assertEquals("the DM must reach its recipient", listOf(dm.toDict()), toFriendA.wireDicts)
        assertTrue(dm.msgId() in toFriendA.msgIds)

        val toHome = SyncRunner.filterPendingForPeer(pending, home, emptySet())
        assertEquals("the DM must reach the home node (peerIdentity == author)", listOf(dm.toDict()), toHome.wireDicts)
        assertTrue(dm.msgId() in toHome.msgIds)
    }

    @Test fun filterPendingForPeerExcludesResponseFromPeerWhoseDeviceIsNotInTheWrapSet() {
        val home = "aa".repeat(32)          // ownIdentity -- the response's author
        val friendA = "bb".repeat(32)
        val friendADevice = "11".repeat(32) // the ONLY device the response is wrapped to
        val friendB = "cc".repeat(32)
        val friendBDevice = "22".repeat(32) // not in the wrap-set
        val response = outboundWire(home, mapOf(
            "kind" to "response", "target" to "ff".repeat(32),
            "wraps" to mapOf(friendADevice to mapOf("x" to 1))))
        val pending = listOf(response.toDict())

        val toFriendB = SyncRunner.filterPendingForPeer(pending, friendB, setOf(friendBDevice))
        assertTrue("a response wrapped only to friendA's device must not reach friendB -- " +
            "this is the responder-anonymity boundary (kind \"response\" gets NO grant-union)",
            toFriendB.wireDicts.isEmpty())

        val toFriendA = SyncRunner.filterPendingForPeer(pending, friendA, setOf(friendADevice))
        assertEquals("the response must reach the friend whose device is in the wrap-set",
            listOf(response.toDict()), toFriendA.wireDicts)

        val toHome = SyncRunner.filterPendingForPeer(pending, home, emptySet())
        assertEquals("the response must reach the home node (peerIdentity == author)",
            listOf(response.toDict()), toHome.wireDicts)
    }

    @Test fun filterPendingForPeerDropsUnparseableItemsFailClosed() {
        val malformed = mapOf("not" to "a valid signed message dict")
        val result = SyncRunner.filterPendingForPeer(listOf(malformed), "aa".repeat(32), emptySet())
        assertTrue("an unparseable pending item must be dropped, not passed through", result.wireDicts.isEmpty())
        assertTrue(result.msgIds.isEmpty())
    }

    @Test fun filterPendingForPeerNeverFiltersEnckeyItself() {
        // enckey isn't part of `pending` in production (it lives in
        // prep.outbound, concatenated on top, never routed through this
        // filter at all) -- but peerMayReceive's own `else -> true` branch
        // is what makes that safe even if it ever WERE routed through here,
        // since it has no explicit kind clause. Proven directly: an
        // "enckey" item passed through filterPendingForPeer must survive
        // regardless of an empty/unrelated peerDevices set.
        val home = "aa".repeat(32)
        val enckey = outboundWire(home, mapOf("kind" to "enckey", "enc_pub" to "ab".repeat(32)))
        val toStranger = SyncRunner.filterPendingForPeer(listOf(enckey.toDict()), "cc".repeat(32), emptySet())
        assertEquals(listOf(enckey.toDict()), toStranger.wireDicts)
    }
}
