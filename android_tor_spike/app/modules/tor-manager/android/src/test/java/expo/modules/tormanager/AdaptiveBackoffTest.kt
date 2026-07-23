package expo.modules.tormanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 6 (friend-peering, cadence overhaul): AdaptiveBackoff is the pure
 *  interval-math extracted from TorNodeService's old fixed
 *  scheduleAtFixedRate(SYNC_INTERVAL_MS) cadence -- the class itself has
 *  zero Android dependency (no Context/Service), so these run on a plain
 *  JVM with no Robolectric, same discipline as MsgJsonTest's own doc note.
 *
 *  The scheduler WIRING (TorNodeService's actual self-rescheduling chain,
 *  and the on-compose/on-resume triggers that call reset() from another
 *  thread) is NOT exercised here -- that part is on-device/partial-verified
 *  per the Task 6 brief. This file is the falsifiable math underneath it:
 *  every assertion below is an exact value, not a loose ">" bound, so a
 *  wrong doubling/clamp/reset implementation fails a test here rather than
 *  only showing up as a wrong on-device sync cadence.
 *
 *  BASE/MAX below mirror TorNodeService's BASE_SYNC_MS/MAX_SYNC_MS
 *  (600_000L / 3_600_000L = 10 min / 1 hr) but are hardcoded, not imported
 *  -- this test has zero coupling to TorNodeService (an Android Service
 *  class); if those constants ever change, this file's numbers must be
 *  updated deliberately, not silently along for the ride. */
class AdaptiveBackoffTest {
    private val BASE = 600_000L      // 10 min
    private val MAX = 3_600_000L     // 1 hr

    @Test fun firstIdleSweepDoublesBaseTowardCap() {
        val b = AdaptiveBackoff(BASE, MAX)
        assertEquals(1_200_000L, b.nextInterval(false))
    }

    @Test fun idleSweepsDoubleTowardCapThenStayClampedAtCap() {
        val b = AdaptiveBackoff(BASE, MAX)
        assertEquals(1_200_000L, b.nextInterval(false))   // 10m -> 20m
        assertEquals(2_400_000L, b.nextInterval(false))   // 20m -> 40m
        assertEquals(3_600_000L, b.nextInterval(false))   // 40m -> 80m WOULD exceed the 60m cap
        assertEquals(3_600_000L, b.nextInterval(false))   // stays at the cap
        assertEquals(3_600_000L, b.nextInterval(false))   // still at the cap, never exceeds it
    }

    @Test fun exactCapClampBoundary() {
        // Hand-picked small numbers so the "would-exceed" value is
        // unambiguous: base=10, max=35 -> the idle sequence is 20, then 40
        // WOULD exceed 35, so it must clamp to EXACTLY 35 -- not 40, and not
        // some other rounded-down value.
        val b = AdaptiveBackoff(10L, 35L)
        assertEquals(20L, b.nextInterval(false))
        assertEquals(35L, b.nextInterval(false))   // 40 clamped to exactly 35
        assertEquals(35L, b.nextInterval(false))   // still exactly 35
    }

    @Test fun sweepThatPulledNewResetsToBaseEvenAfterDoubling() {
        val b = AdaptiveBackoff(BASE, MAX)
        b.nextInterval(false)   // 20m
        b.nextInterval(false)   // 40m
        assertEquals(BASE, b.nextInterval(true))   // pulled new content -> back to base
    }

    @Test fun pulledNewOnVeryFirstSweepReturnsBase() {
        val b = AdaptiveBackoff(BASE, MAX)
        assertEquals(BASE, b.nextInterval(true))
    }

    @Test fun repeatedPulledNewStaysAtBase() {
        val b = AdaptiveBackoff(BASE, MAX)
        assertEquals(BASE, b.nextInterval(true))
        assertEquals(BASE, b.nextInterval(true))
        assertEquals(BASE, b.nextInterval(true))
    }

    @Test fun resetSetsCurrentToBaseSoNextIdleDoublesFromBaseAgain() {
        val b = AdaptiveBackoff(BASE, MAX)
        b.nextInterval(false)   // 20m
        b.nextInterval(false)   // 40m
        b.reset()
        // Falsifiable check: if reset() were a no-op, the next idle sweep's
        // doubling would continue from 40m (clamped to the 60m cap) instead
        // of restarting from base (20m).
        assertEquals(1_200_000L, b.nextInterval(false))
    }

    @Test fun resetAfterAlreadyAtBaseIsIdempotent() {
        val b = AdaptiveBackoff(BASE, MAX)
        b.reset()
        assertEquals(1_200_000L, b.nextInterval(false))
    }

    // --- pulledNewContent (Task 6 review fix, Finding 1) ---
    // Pure decision fed into nextInterval above. SyncRunner's counts are
    // ABSOLUTE store totals (store.stats() is an unconditional
    // SELECT COUNT(*)), so "pulled new" must be a DELTA against the
    // previous round's total -- never `newTotal > 0` alone. Every case
    // below is an exact assertion, and the totalUnchangedButPositive case
    // in particular is chosen to FAIL under the old `(ran && ok && total >
    // 0)` logic (5 -> 5 is "positive" but not new) -- that's the bug this
    // fix closes; see the mutation-verify note in the Task 6 review-fix
    // report for a live demonstration.

    @Test fun veryFirstRoundEverCountsAsPulledNewRegardlessOfTotal() {
        // prevTotal < 0 is TorNodeService's startup sentinel (-1L, "no
        // successful round yet this process") -- always true so the very
        // first real sweep resets the cadence to base.
        assertTrue(AdaptiveBackoff.pulledNewContent(prevTotal = -1L, newTotal = 0L, ran = true, ok = true))
        assertTrue(AdaptiveBackoff.pulledNewContent(prevTotal = -1L, newTotal = 5L, ran = true, ok = true))
    }

    @Test fun totalUnchangedButPositiveIsNotPulledNew() {
        // THE bug this fix closes: own-identity seeding means the absolute
        // total is essentially always > 0. Under the old `sum > 0` logic
        // this case (5 -> 5, nothing new) wrongly returned true; the
        // delta-based helper correctly returns false.
        assertFalse(AdaptiveBackoff.pulledNewContent(prevTotal = 5L, newTotal = 5L, ran = true, ok = true))
    }

    @Test fun totalIncreasedIsPulledNew() {
        assertTrue(AdaptiveBackoff.pulledNewContent(prevTotal = 5L, newTotal = 8L, ran = true, ok = true))
    }

    @Test fun totalIncreasedButNotRanIsNotPulledNew() {
        // A skipped (mutex-contended) round never counts, even if the
        // shared store's total happens to have grown meanwhile (e.g. a
        // foreground syncNow ran concurrently).
        assertFalse(AdaptiveBackoff.pulledNewContent(prevTotal = 5L, newTotal = 8L, ran = false, ok = true))
    }

    @Test fun ranAndOkButTotalNotIncreasedIsNotPulledNew() {
        assertFalse(AdaptiveBackoff.pulledNewContent(prevTotal = 5L, newTotal = 5L, ran = true, ok = true))
        assertFalse(AdaptiveBackoff.pulledNewContent(prevTotal = 8L, newTotal = 5L, ran = true, ok = true))
    }

    @Test fun notOkIsNeverPulledNewEvenIfTotalIncreased() {
        assertFalse(AdaptiveBackoff.pulledNewContent(prevTotal = 5L, newTotal = 8L, ran = true, ok = false))
    }
}
