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

    @Test fun failedSyncNeverUpdatesMarkerSoNextCallStillPublishes() {
        // A failed sync (per TorManagerModule.syncNow's contract) never
        // calls setPublishedEncPub, so the marker a subsequent call sees is
        // UNCHANGED from before the failed attempt -- confirm the guard
        // still says "publish" in that state (i.e. the marker being stale/
        // absent, not the guard itself, is what drives the retry).
        assertTrue(EncKeyPublishGuard.shouldPublish(pubA, publishedMarker = null))
    }
}
