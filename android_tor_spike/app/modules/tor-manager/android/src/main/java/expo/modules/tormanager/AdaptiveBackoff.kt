package expo.modules.tormanager

/** Task 6 (friend-peering, cadence overhaul): pure interval-math for
 *  TorNodeService's self-rescheduling sync chain, replacing the old fixed
 *  15-min `scheduleAtFixedRate(SYNC_INTERVAL_MS)` cadence. Deliberately has
 *  NO Android dependency (no Context/Service) so it's plain-JVM testable --
 *  see AdaptiveBackoffTest.
 *
 *  Semantics: a sweep that pulled new content (`lastSweepPulledNew = true`)
 *  resets the interval to `baseMs` -- an active peer gets checked again
 *  soon. An idle sweep doubles the interval toward `maxMs` and holds there
 *  once reached -- a quiet peer gets checked less and less often, but never
 *  less often than every `maxMs`. `reset()` is the event-trigger path
 *  (on-compose / on-app-resume): "the user just did something, check again
 *  soon" independent of what any sweep's own outcome was or will be.
 *
 *  Thread-safety: TorNodeService drives `nextInterval` from a single-thread
 *  scheduler, but `reset()` is called from event triggers on a DIFFERENT
 *  thread (the HTTP loopback server thread for on-compose, the RN bridge
 *  thread for on-resume/beatNow) while a sweep may be in flight
 *  concurrently. `current` is the only mutable state; `@Volatile` gives a
 *  concurrent reset()/nextInterval() pair safe cross-thread visibility
 *  without a full lock. The two calls don't need to be atomic WITH EACH
 *  OTHER -- a reset() landing just before or just after a given
 *  nextInterval() call is a correct outcome either way, there is no "right"
 *  ordering between an async event and an in-flight sweep's own math. */
class AdaptiveBackoff(private val baseMs: Long, private val maxMs: Long) {
    @Volatile private var current: Long = baseMs

    /** Computes and stores the next interval, then returns it.
     *  `lastSweepPulledNew = true` -> resets to `baseMs`.
     *  `lastSweepPulledNew = false` -> doubles, clamped to `maxMs`. */
    fun nextInterval(lastSweepPulledNew: Boolean): Long {
        current = if (lastSweepPulledNew) baseMs else minOf(current * 2, maxMs)
        return current
    }

    /** Event-trigger path: rewind to `baseMs` regardless of any in-flight
     *  or prior sweep outcome. The next `nextInterval(false)` call after
     *  this doubles fresh from `baseMs`, same as a brand-new instance. */
    fun reset() {
        current = baseMs
    }
}
