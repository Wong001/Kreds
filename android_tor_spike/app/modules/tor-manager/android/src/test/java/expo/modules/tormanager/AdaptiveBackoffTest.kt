package expo.modules.tormanager

import org.junit.Assert.assertEquals
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
}
