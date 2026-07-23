package expo.modules.tormanager

import java.util.concurrent.atomic.AtomicLong

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
 *  Thread-safety (Task 6 review fix, Finding 3): TorNodeService drives
 *  `nextInterval` from a single-thread scheduler, but `reset()` is called
 *  from event triggers on a DIFFERENT thread (the HTTP loopback server
 *  thread for on-compose, the RN bridge thread for on-resume/beatNow) while
 *  a sweep may be in flight concurrently. `current` was originally a plain
 *  `@Volatile var`, which made `nextInterval`'s read-modify-write
 *  (`current = if (...) baseMs else minOf(current * 2, maxMs)`) a NON-atomic
 *  sequence with respect to a concurrent `reset()` write on another thread --
 *  a `reset()` landing between the read and the write of a `nextInterval`
 *  call could be silently clobbered (lost update) or, symmetrically, an
 *  in-flight `nextInterval` that had already read the pre-reset value could
 *  re-double straight past a `reset()` that just landed. `current` is now
 *  an `AtomicLong`: `nextInterval` uses `updateAndGet` (the whole
 *  read-compute-write happens atomically against any concurrent `set`) and
 *  `reset()` uses a plain atomic `set`. The two calls still don't need to be
 *  ordered WITH EACH OTHER -- a `reset()` landing just before or just after
 *  a given `nextInterval()` call is a correct outcome either way, there is
 *  no "right" ordering between an async event and an in-flight sweep's own
 *  math -- only each call's OWN internal read-modify-write needed to stop
 *  being tearable, which `AtomicLong` guarantees. */
class AdaptiveBackoff(private val baseMs: Long, private val maxMs: Long) {
    private val current = AtomicLong(baseMs)

    /** Computes and stores the next interval, then returns it.
     *  `lastSweepPulledNew = true` -> resets to `baseMs`.
     *  `lastSweepPulledNew = false` -> doubles, clamped to `maxMs`. */
    fun nextInterval(lastSweepPulledNew: Boolean): Long =
        current.updateAndGet { cur -> if (lastSweepPulledNew) baseMs else minOf(cur * 2, maxMs) }

    /** Event-trigger path: rewind to `baseMs` regardless of any in-flight
     *  or prior sweep outcome. The next `nextInterval(false)` call after
     *  this doubles fresh from `baseMs`, same as a brand-new instance. */
    fun reset() {
        current.set(baseMs)
    }

    companion object {
        /** Task 6 review fix (Finding 1): pure, JVM-testable decision for
         *  whether a background sweep "pulled new content" -- the value fed
         *  into `nextInterval` above. `newTotal`/`prevTotal` are ABSOLUTE
         *  store totals (SyncRunner's messages+blobs+identities counts,
         *  themselves an unconditional `SELECT COUNT(*)` of the whole table
         *  via `store.stats()` -- monotonically non-decreasing, never "new
         *  this round" on their own). The correct signal is whether the
         *  total GREW since the previous round, not whether it's merely
         *  positive: the phone's own identity is always seeded (identities
         *  >= 1 on every successful AUTH) and messages/blobs never shrink,
         *  so `newTotal > 0` was true on essentially every successful sync
         *  -- the interval reset to base every time and never backed off
         *  toward `maxMs`, making the whole adaptive feature dead in
         *  production (a fixed cadence at `baseMs`, indistinguishable from
         *  the old fixed-timer behavior it was meant to replace).
         *
         *  `prevTotal < 0` (the `-1L` sentinel TorNodeService seeds before
         *  any round has completed this process) always returns true
         *  regardless of `newTotal` -- the very first real sweep counts as
         *  "pulled new", resetting to base, which is the desired startup
         *  behavior (stay responsive right after boot).
         *
         *  `ran`/`ok` gate first: a skipped (mutex-contended) or failed
         *  round is never "pulled new" no matter what the totals say --
         *  callers must also NOT advance their stored `prevTotal` on such a
         *  round (TorNodeService.syncCycle only updates `lastSyncTotal`
         *  when `ran && ok`), otherwise a skip/failure would corrupt the
         *  next round's delta baseline. */
        fun pulledNewContent(prevTotal: Long, newTotal: Long, ran: Boolean, ok: Boolean): Boolean =
            ran && ok && (prevTotal < 0 || newTotal > prevTotal)
    }
}
