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
}
