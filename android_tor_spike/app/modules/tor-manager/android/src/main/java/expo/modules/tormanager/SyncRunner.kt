package expo.modules.tormanager

import android.content.Context
import java.util.concurrent.locks.ReentrantLock

/** Brick C Task 1: the JS-runtime-free content-sync TRANSPORT, lifted verbatim
 *  out of `TorManagerModule.syncNow` so BOTH the foreground module (this task)
 *  and the background service (Brick C Task 2) can drive a sync without a
 *  React/JS runtime -- mirroring how Brick A extracted the handshake into
 *  `KotlinHandshake` so the heartbeat runs without JS.
 *
 *  Scope boundary (binding): `SyncRunner` owns dial -> AUTH -> identity pin ->
 *  store-seed -> enc-key prep/publish-guard -> `KotlinSync.run` -> mark-
 *  published-on-Ok, and NOTHING else. It NEVER decrypts: no `DecryptPass`, no
 *  content keys, no feed/blob-key cache. It returns a `SyncOutcome` (transport
 *  result + the counts `KotlinSync.run` reported). The DecryptPass +
 *  feedCache/blobKeys + the `onSyncProgress`/`nodeSync` events stay in
 *  `TorManagerModule.syncNow`, which calls `runSync` for the transport and
 *  then decrypts on a returned success. `onProgress` is an injected callback:
 *  the foreground passes its event-emitter lambda, the background caller (Task
 *  2) takes the default no-op.
 *
 *  Process-wide mutex: the Expo module and the background service run in the
 *  SAME process (only `:imagedecode` is isolated), so this top-level object's
 *  `syncLock` is genuinely shared between them. `runSync` `tryLock`s and, if a
 *  concurrent sync already holds it, returns `ran=false` immediately -- it does
 *  NOT queue. */
object SyncRunner {

    data class SyncOutcome(
        val ran: Boolean,            // false == skipped because another sync held the lock
        val ok: Boolean,
        val messages: Int, val blobs: Int, val identities: Int,
        val selfRevoked: Boolean,
        val error: String?,
    )

    /** The enc-key push decision for one sync: the outbound messages (at most
     *  one -- this device's freshly-composed, freshly-seq'd `enckey`), the
     *  current enc_pub, and whether this sync should publish it (see
     *  `EncKeyPublishGuard`). NO encPriv -- `SyncRunner` never decrypts, so the
     *  private half never enters this object; the foreground re-derives it via
     *  the idempotent `EncKeys.getOrCreate` for its own DecryptPass. */
    data class EncKeyPrep(
        val outbound: List<Map<String, Any?>>,
        val encPub: String,
        val shouldPublish: Boolean,
    )

    // Process-global mutex shared by the foreground module and the background
    // service (same process). A single top-level instance -- NOT per-call --
    // is the whole point: two callers in different components contend on the
    // exact same lock. Reentrant so the visible-for-testing helper and runSync
    // share one lock object.
    private val syncLock = ReentrantLock()

    /** Visible-for-testing entry that runs an arbitrary body under the SAME
     *  `syncLock` production `runSync` uses -- lets the process-wide mutex be
     *  JVM-tested without real Tor/store (SyncRunnerTest). `body` runs iff the
     *  lock was free; otherwise `onSkipped` runs and `body` does not. */
    internal fun withSyncLockForTest(body: () -> Unit, onSkipped: () -> Unit) {
        if (!syncLock.tryLock()) { onSkipped(); return }
        try { body() } finally { syncLock.unlock() }
    }

    /** Visible-for-testing seam onto the enc-key prep + publish-guard logic
     *  `runSync` runs inline (SyncRunnerTest Step 6, Option B) -- driven
     *  directly on an InMemory store, no node/Context. */
    internal fun prepareEncKeyOutboundForTest(fx: KotlinHandshake.Fixture, store: SyncStore) =
        prepareEncKeyOutbound(fx, store)

    /** Visible-for-testing seam onto the outcome mapping `runSync` applies to
     *  `KotlinSync.run`'s result -- proves Ok marks-published-on-shouldPublish
     *  and maps counts, SelfRevoked/Failed map to the right skip/error shape,
     *  and a non-Ok never sets the published marker. */
    internal fun mapSyncResultForTest(r: SyncResult, prep: EncKeyPrep, store: SyncStore) =
        mapSyncResult(r, prep, store)

    /** Runs the full transport under the process-wide mutex. NEVER decrypts.
     *  Returns `ran=false` immediately if a sync is already in progress. */
    fun runSync(
        ctx: Context,
        fx: KotlinHandshake.Fixture,
        onProgress: (String, Int) -> Unit = { _, _ -> },
    ): SyncOutcome {
        if (!syncLock.tryLock()) {
            return SyncOutcome(ran = false, ok = false, 0, 0, 0, false, "sync already in progress")
        }
        try {
            // One sync DRAINS all pending blobs. hearth sends blobs
            // smallest-first up to a ~15 MiB per-round budget and leaves the
            // rest "for the next round" (sync.py BLOB_GIVE_BUDGET), so a
            // profile's large banner + wall photos need several rounds. Rather
            // than dripping one batch per 15-min background cycle (leaving big
            // images broken for up to an hour), loop rounds back-to-back until
            // nothing is missing, a round pulls no new blobs (the peer doesn't
            // hold the rest), or a safety cap. In steady state missingBlobs()
            // is empty after round 1, so this is a single round -- the extra
            // rounds happen only when there is a real backlog to drain.
            var last = runTransport(ctx, fx, onProgress)
            if (!last.ran || !last.ok) return last
            var rounds = 1
            while (rounds < MAX_DRAIN_ROUNDS &&
                    runCatching { anyBlobsMissing(ctx) }.getOrDefault(false)) {
                val before = last.blobs
                val next = runTransport(ctx, fx, onProgress)
                rounds++
                if (!next.ran || !next.ok) return last   // keep the last good outcome; next cycle retries
                last = next
                if (next.blobs <= before) break          // no new blobs this round -> peer holds no more
            }
            return last
        } finally {
            syncLock.unlock()
        }
    }

    // Hard cap on blob-drain rounds per sync (see runSync). 12 rounds x the
    // ~15 MiB per-round budget is ~180 MiB -- far beyond any profile's blob
    // set, and the loop normally exits well before this via the missingBlobs/
    // no-progress conditions. The cap only backstops a pathological peer.
    private const val MAX_DRAIN_ROUNDS = 12

    // Drain-gate helper: open a throwaway store JUST to check whether any blobs
    // are still missing, and CLOSE it (SQLiteOpenHelper leaks its connection
    // otherwise -- the same leak class the LocalApi shared-store fix addresses).
    private fun anyBlobsMissing(ctx: Context): Boolean {
        val s = SqliteSyncStore(ctx)
        return try { s.missingBlobs().isNotEmpty() } finally { s.close() }
    }

    /** The transport block, moved verbatim from `TorManagerModule.syncNow`.
     *  Every fragile step closes the Tor stream on failure: `authOnlyOverStream`
     *  leaves the stream OPEN by contract (only `KotlinSync.run`'s finally
     *  closes it), so a failure before `KotlinSync.run` ever runs must close
     *  the stream itself or the Tor connection leaks for the process lifetime.
     *  Preserved one-for-one from the original inline code. */
    private fun runTransport(
        ctx: Context,
        fx: KotlinHandshake.Fixture,
        onProgress: (String, Int) -> Unit,
    ): SyncOutcome {
        try {
            val (host, port) = KotlinHandshake.splitAddr(fx.onion_addr)
            val stream = TorStream(TorEngine.dial(host, port))

            // AUTH ONLY -- do NOT use KotlinHandshake.run/runOverStream here: their
            // acceptance probe IS the sync REVOCATIONS swap, and chaining straight
            // into KotlinSync.run (whose own first phase also sends "revocations")
            // would send that frame twice and desync the session -- proved on the
            // BB-5 desk gate. authOnlyOverStream does HELLO+AUTH only, leaves the
            // stream open, and throws (rather than returning a verdict) on failure --
            // accept/refuse is surfaced by KotlinSync.run's own revocations phase.
            val peerCert = try {
                KotlinHandshake.authOnlyOverStream(stream, fx)
            } catch (e: Exception) {
                stream.close()
                return SyncOutcome(true, false, 0, 0, 0, false, "auth: ${e.message}")
            }

            // authOnlyOverStream verifies the peer cert is validly signed but does
            // NOT pin it to our home identity (only runOverStream's accepted branch
            // does that). Pin here so a wrong onion address can never sync us into
            // a stranger's node.
            if (peerCert.identity_pub != fx.cert.identity_pub) {
                stream.close()
                return SyncOutcome(true, false, 0, 0, 0, false, "auth: node identity is not our home identity")
            }

            // SqliteSyncStore construction / addIdentity are SQLite I/O and can throw
            // (e.g. DB locked). authOnlyOverStream succeeding leaves the stream open
            // by contract -- only KotlinSync.run's finally closes it -- so a failure
            // here, before KotlinSync.run ever runs, must close the stream itself or
            // the Tor connection leaks for the process lifetime.
            val store = try {
                SqliteSyncStore(ctx).also { it.addIdentity(fx.cert.identity_pub) }
            } catch (e: Exception) {
                stream.close()
                return SyncOutcome(true, false, 0, 0, 0, false, "store: ${e.message}")
            }
            // (own identity is seeded above, inside the try, so ingest's is_known
            // gate admits its own-identity messages; the HAVE phase adds the node's
            // known identities.)

            // This device's own enc keypair (generated once, persisted --
            // EncKeys.getOrCreate), the already-published guard
            // (EncKeyPublishGuard) deciding whether THIS sync's outbound push
            // needs a freshly-composed, freshly-seq'd `enckey` message, and
            // building that message when it does. EncKeys.getOrCreate /
            // store.getPublishedEncPub / store.nextSeq are all SQLite I/O and CAN
            // throw (e.g. DB locked -- plausible under overlapping syncNow calls
            // racing on the same DB file). Same stream-close reasoning, same
            // shape, as the store-init try/catch immediately above.
            val prep = try {
                prepareEncKeyOutbound(fx, store)
            } catch (e: Exception) {
                stream.close()
                store.close()   // SQLiteOpenHelper leaks its connection if not closed
                return SyncOutcome(true, false, 0, 0, 0, false, "enckey: ${e.message}")
            }

            // Close the round's store once its result is mapped -- each drain
            // round opens a fresh one, and an unclosed SQLiteOpenHelper leaks a
            // SQLiteConnection (the leak class the LocalApi shared-store fix
            // addresses). mapSyncResult reads store.stats() first, so map before close.
            val outcome = mapSyncResult(
                KotlinSync.run(stream, store, fx.device_pub, prep.outbound, onProgress),
                prep, store)
            store.close()
            return outcome
        } catch (e: Exception) {
            return SyncOutcome(true, false, 0, 0, 0, false, "io: ${e.message}")
        }
    }

    /** Enc-key resolve/guard/compose, factored out of `runTransport` so it is
     *  JVM-testable on an InMemory store (no node/Context). Byte-for-byte the
     *  logic `syncNow` ran inline before this extraction. */
    private fun prepareEncKeyOutbound(fx: KotlinHandshake.Fixture, store: SyncStore): EncKeyPrep {
        val (_, pub) = EncKeys.getOrCreate(store)
        val publish = EncKeyPublishGuard.shouldPublish(pub, store.getPublishedEncPub())
        val outbound = if (publish) {
            listOf(KotlinSync.composeEncKey(fx, pub, store.nextSeq(), System.currentTimeMillis() / 1000.0))
        } else emptyList()
        return EncKeyPrep(outbound, pub, publish)
    }

    /** Maps `KotlinSync.run`'s result to a `SyncOutcome`. On Ok, marks the
     *  pushed enc_pub published ONLY once the carrying sync has FULLY succeeded
     *  (EncKeyPublishGuard's rule) -- a non-Ok result never reaches
     *  setPublishedEncPub, so the marker stays stale/absent and the next sync
     *  retries the push. */
    private fun mapSyncResult(r: SyncResult, prep: EncKeyPrep, store: SyncStore): SyncOutcome = when (r) {
        is SyncResult.Ok -> {
            if (prep.shouldPublish) store.setPublishedEncPub(prep.encPub)
            SyncOutcome(true, true, r.messages, r.blobs, r.identities, false, null)
        }
        is SyncResult.SelfRevoked -> SyncOutcome(true, false, 0, 0, 0, true, "self-revoked")
        is SyncResult.Failed -> SyncOutcome(true, false, 0, 0, 0, false, "${r.stage}: ${r.reason}")
    }
}
