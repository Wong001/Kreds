package expo.modules.tormanager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the Task 7 (B.2) already-published guard: extracted out of
 *  `TorManagerModule.syncNow` into `EncKeyPublishGuard` specifically so this
 *  decision is directly testable without instantiating the Expo module
 *  class. Exercises the exact scenarios the Task 4 review flagged (see
 *  `.superpowers/sdd/task-4-report.md` concern 2) and this task must
 *  resolve: first sync composes+pushes, second sync (marker now matches)
 *  skips, and a rotated/regenerated key (marker no longer matches)
 *  republishes. */
class EncKeyPublishGuardTest {
    private val pubA = "ab".repeat(32)
    private val pubB = "cd".repeat(32)

    @Test fun firstSyncNeverPublishedYetMustPublish() {
        // publishedMarker == null: this device has never successfully
        // published any enc_pub -- the very first syncNow call.
        assertTrue(EncKeyPublishGuard.shouldPublish(pubA, publishedMarker = null))
    }

    @Test fun secondSyncAfterConfirmedPublishMustSkip() {
        // Simulates the state right after a syncNow that pushed pubA and
        // completed SyncResult.Ok, so the caller persisted pubA as the
        // marker -- a following sync with the SAME enc_pub must not burn a
        // fresh seq/message.
        assertFalse(EncKeyPublishGuard.shouldPublish(pubA, publishedMarker = pubA))
    }

    @Test fun rotatedKeyMismatchedMarkerMustRepublish() {
        // The stored marker reflects an OLDER enc_pub (e.g. EncKeys self-
        // healed a corrupt pair, or a future rotation feature); the
        // CURRENT enc_pub no longer matches, so publishing resumes
        // automatically -- no separate "is this a rotation" branch needed.
        assertTrue(EncKeyPublishGuard.shouldPublish(pubB, publishedMarker = pubA))
    }

    // NOTE: a fourth case, "a failed sync never updates the marker, so the
    // next call still publishes," was deliberately NOT added as a separate
    // test here (an earlier version of this file had one, byte-identical to
    // firstSyncNeverPublishedYetMustPublish -- caught in review). The guard
    // is a pure function of (currentEncPub, publishedMarker) with no notion
    // of "this call failed" -- from its point of view, "never published yet"
    // and "published attempt failed, marker was never set" are the exact
    // same input (publishedMarker == null or == some OLDER value), so they
    // cannot be expressed as distinct test cases at this layer. That
    // "failed syncs don't touch the marker" invariant is a property of
    // TorManagerModule.syncNow's control flow (setPublishedEncPub is only
    // reachable from the SyncResult.Ok branch), not of this guard -- see
    // syncNow's own comments and the task report's self-review for that
    // claim instead.
}
