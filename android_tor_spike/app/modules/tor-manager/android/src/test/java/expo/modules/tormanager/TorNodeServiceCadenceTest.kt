package expo.modules.tormanager

import org.junit.Assert.assertEquals
import org.junit.Test

/** Foreground-fast cadence (post-Task-6 follow-up, friend-peering arc):
 *  while the app is foreground/active, TorNodeService's self-rescheduling
 *  sweep chain must use a short, fixed FOREGROUND_SYNC_MS interval instead
 *  of the AdaptiveBackoff value -- so passively-arriving content shows up
 *  in well under AdaptiveBackoff's ~10 min base while the user is actually
 *  watching the feed. While backgrounded, the existing adaptive backoff
 *  (Task 6) is unchanged.
 *
 *  `chooseSweepDelay` is the pure decision extracted out of
 *  `sweepAndReschedule` for exactly this reason -- no Android dependency
 *  (no Context/Service instantiation), so it's plain-JVM testable, same
 *  discipline as AdaptiveBackoffTest's own doc note. The scheduler WIRING
 *  (the appForeground flag flip via ACTION_SET_FOREGROUND, and the RN
 *  AppState listener that drives it) is NOT exercised here -- that part is
 *  on-device-DoD-only, mirroring Task 6's own posture for the scheduler
 *  chain itself (see AdaptiveBackoffTest's class doc). Every assertion
 *  below is an exact value. */
class TorNodeServiceCadenceTest {
    @Test fun foregroundAlwaysReturnsForegroundMsRegardlessOfBackoffNext() {
        assertEquals(30_000L, TorNodeService.chooseSweepDelay(foreground = true, foregroundMs = 30_000L, backoffNext = 600_000L))
        assertEquals(30_000L, TorNodeService.chooseSweepDelay(foreground = true, foregroundMs = 30_000L, backoffNext = 3_600_000L))
        assertEquals(30_000L, TorNodeService.chooseSweepDelay(foreground = true, foregroundMs = 30_000L, backoffNext = 0L))
    }

    @Test fun backgroundAlwaysReturnsBackoffNextRegardlessOfForegroundMs() {
        assertEquals(600_000L, TorNodeService.chooseSweepDelay(foreground = false, foregroundMs = 30_000L, backoffNext = 600_000L))
        assertEquals(1_200_000L, TorNodeService.chooseSweepDelay(foreground = false, foregroundMs = 30_000L, backoffNext = 1_200_000L))
        assertEquals(3_600_000L, TorNodeService.chooseSweepDelay(foreground = false, foregroundMs = 30_000L, backoffNext = 3_600_000L))
    }

    @Test fun realConstantsForegroundBeatsBaseBackoff() {
        // Falsifiable end-to-end sanity check with the REAL FOREGROUND_SYNC_MS
        // this ships with, against BASE_SYNC_MS hardcoded here (mirrors
        // AdaptiveBackoffTest's own "hardcoded, not imported -- zero
        // coupling" convention for TorNodeService's private BASE_SYNC_MS/
        // MAX_SYNC_MS -- see that file's class doc): foreground (30s) must be
        // strictly shorter than even the shortest background interval (10
        // min) -- if this ever regressed to foreground >= base, the
        // foreground path would be pointless (never faster than
        // backgrounded).
        val baseSyncMs = 600_000L   // mirrors TorNodeService's private BASE_SYNC_MS
        val foregroundNext = TorNodeService.chooseSweepDelay(
            foreground = true, foregroundMs = TorNodeService.FOREGROUND_SYNC_MS, backoffNext = baseSyncMs)
        assertEquals(TorNodeService.FOREGROUND_SYNC_MS, foregroundNext)
        assert(foregroundNext < baseSyncMs) {
            "FOREGROUND_SYNC_MS ($foregroundNext) must be shorter than BASE_SYNC_MS ($baseSyncMs)"
        }
    }
}
