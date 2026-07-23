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
    //
    // `internal` (gossip server Task 4, was `private`): GossipServer's accept
    // loop must acquire this SAME instance around each inbound handshake+serve
    // so an inbound connection and an outbound sync (this object's own
    // runSync) are mutually exclusive over the shared SQLite writer -- see
    // GossipServer.kt's class doc for the coarse-lock tradeoff this implies.
    // TorNodeService threads this same reference into both runSync (already
    // did, indirectly, via this object) and the new GossipServer instance.
    internal val syncLock = ReentrantLock()

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
     *  and a non-Ok never sets the published marker. `pendingIds` defaults to
     *  empty so every pre-existing 3-arg call site (SyncRunnerTest,
     *  SyncLoopbackTest) keeps compiling unchanged -- those scenarios don't
     *  exercise the pending-outbound queue, so an empty list is a no-op on
     *  Ok's `store.clearPendingOutbound` call, same as if this parameter
     *  didn't exist for them. */
    internal fun mapSyncResultForTest(
        r: SyncResult, prep: EncKeyPrep, store: SyncStore, pendingIds: List<String> = emptyList()
    ) = mapSyncResult(r, prep, store, pendingIds)

    /** Runs the full transport, over EVERY stored peer (friend-peering Task
     *  4 -- was a single hardcoded `fx.onion_addr` dial), under the process-
     *  wide mutex. NEVER decrypts. Returns `ran=false` immediately if a sync
     *  is already in progress.
     *
     *  Peer loop (mirrors hearth sync.py's `_gossip_round`, sync.py:267-297):
     *  reads `store.listPeers()` + the stored `gossip_addr` + the pending-
     *  outbound snapshot ONCE per round (not once per peer -- same as
     *  sync.py reading `own_host`/`list_peers()` once at the top of
     *  `_gossip_round`; the pending-outbound snapshot is this file's own
     *  addition, review Finding 2 fix -- see `shouldClearPendingOutbound`'s
     *  doc), then delegates the actual per-peer skip/dial/aggregate decisions
     *  to `runPeerLoop` (the testable seam -- see its own doc). A revocation
     *  revealed by any peer stops the round immediately via
     *  `TorNodeService.enterRevokedState`; otherwise the pending-outbound
     *  queue is cleared once (gated on at least one ACTUALLY DIALED peer
     *  having succeeded) and the aggregated outcome is returned. */
    fun runSync(
        ctx: Context,
        fx: KotlinHandshake.Fixture,
        onProgress: (String, Int) -> Unit = { _, _ -> },
    ): SyncOutcome {
        if (!syncLock.tryLock()) {
            return SyncOutcome(ran = false, ok = false, 0, 0, 0, false, "sync already in progress")
        }
        try {
            // Throwaway store just to read the peer table + our own
            // published onion, closed immediately -- same idiom as
            // anyBlobsMissing below. A failure here (e.g. DB locked) fails
            // the whole round the same way a pre-Task-4 store-open failure
            // inside runTransport did.
            val (peers, ownGossipAddr) = try {
                val s = SqliteSyncStore(ctx)
                try { s.listPeers() to s.getMeta("gossip_addr") } finally { s.close() }
            } catch (e: Exception) {
                return SyncOutcome(true, false, 0, 0, 0, false, "peers: ${e.message}")
            }

            // Review fix (Finding 2, MEDIUM-HIGH -- delivery): capture the
            // pending-outbound snapshot ONCE for the WHOLE round, same idiom
            // as peers/ownGossipAddr just above -- was read+cleared fresh
            // INSIDE runTransport, per peer, so with multiple peers now
            // dialed in one round (friend-peering Task 4), whichever peer
            // synced successfully FIRST cleared the queue and every peer
            // dialed after it saw an already-empty pending set: a message
            // composed just before this round ran could reach only ONE peer
            // (listPeers() is unordered -- possibly a friend, not the home
            // node) and then be deleted, never reaching the rest. Fixed:
            // this single snapshot is passed to EVERY dialed peer's first
            // transport call (`runPeerLoop`/`syncOnePeer`), and the queue is
            // cleared exactly once, below, after the whole loop.
            val (pending, pendingIds) = try {
                val s = SqliteSyncStore(ctx)
                try {
                    val p = s.pendingOutbound()
                    p to p.map { SignedMessageKt.fromDict(it).msgId() }
                } finally { s.close() }
            } catch (e: Exception) {
                return SyncOutcome(true, false, 0, 0, 0, false, "pending-outbound: ${e.message}")
            }

            // One sync DRAINS all pending blobs FROM EACH PEER. hearth sends
            // blobs smallest-first up to a ~15 MiB per-round budget and
            // leaves the rest "for the next round" (sync.py
            // BLOB_GIVE_BUDGET), so a profile's large banner + wall photos
            // need several rounds. Rather than dripping one batch per 15-min
            // background cycle (leaving big images broken for up to an
            // hour), loop rounds back-to-back until nothing is missing, a
            // round pulls no new blobs (the peer doesn't hold the rest), or
            // a safety cap -- see syncOnePeer/MAX_DRAIN_ROUNDS. In steady
            // state this is a single round per peer.
            val loop = runPeerLoop(peers, ownGossipAddr, pending, lastOnionSync, System.currentTimeMillis()) {
                peer, peerPending -> syncOnePeer(ctx, fx, peer, peerPending, onProgress)
            }

            // Task 6 (phone-onion-reachability): the outbound path's
            // SelfRevoked trigger -- this is the ONE choke point every
            // outbound sync runs through (TorNodeService's own background
            // syncCycle and TorManagerModule's foreground syncNow both call
            // this same runSync), so wiring it here covers both callers at
            // once, rather than duplicating the check at each call site. See
            // TorNodeService.enterRevokedState's doc for the full ordering/
            // idempotency contract; safe to call while still holding
            // `syncLock` above (enterRevokedState never blocks on or
            // re-acquires it). Friend-peering Task 4: a revocation revealed
            // by ANY peer stops the WHOLE cycle immediately (not just that
            // peer, see `runPeerLoop`'s own doc) -- there is no "our own
            // identity" left to sync anything under once this fires, and the
            // wipe enterRevokedState performs is the very store the
            // remaining peers' runTransport calls would otherwise try to
            // read/write. The pending-outbound queue is deliberately left
            // untouched on this path -- the wipe deletes the whole DB file
            // it lives in anyway.
            if (loop.selfRevokedOutcome != null) {
                TorNodeService.enterRevokedState(ctx)
                return loop.selfRevokedOutcome
            }

            if (shouldClearPendingOutbound(loop.results) && pendingIds.isNotEmpty()) {
                // Best-effort: a failure to clear only means the SAME
                // already-delivered messages get harmlessly re-pushed (and
                // deduped on ingest) next round -- same resilience posture
                // as leaving them queued on a Failed/SelfRevoked outcome.
                runCatching {
                    val s = SqliteSyncStore(ctx)
                    try { s.clearPendingOutbound(pendingIds) } finally { s.close() }
                }
            }
            return aggregate(loop.results)
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

    // -- friend-peering Task 4: peer-loop decision helpers -----------------
    //
    // Extracted as pure functions (no Context, no Tor, no store) specifically
    // so the SECURITY-CRITICAL identity-acceptance decision -- and the own-
    // onion-skip / onion-throttle gates around it -- are JVM-unit-testable
    // (SyncRunnerTest) even though runTransport/runSync themselves are not
    // (real Android Context + real Tor dial; see SyncRunnerTest's doc on
    // this section). The live dial is Task 7/8's on-device proof.

    // Mirrors hearth sync.py's ONION_SYNC_INTERVAL (messages.py:67, 45.0
    // seconds): don't re-dial the same onion peer within this window across
    // back-to-back runSync calls (e.g. a manual foreground sync shortly
    // after the background service's own cycle already dialed it).
    private const val ONION_SYNC_INTERVAL_MS = 45_000L

    // address -> last dial attempt's wall-clock millis (onion peers only).
    // Object-level (SyncRunner is itself a process-wide singleton), so
    // consecutive runSync calls across the foreground module and the
    // background service's cycles share the same throttle window per
    // address. Access is already serialized by syncLock (every runSync call
    // holds it for the call's full duration), so a plain mutable map is
    // sufficient -- no separate synchronization needed.
    private val lastOnionSync: MutableMap<String, Long> = mutableMapOf()

    /** The `.onion` host part of `addr` ("host:port"), or null if `addr` is
     *  null/empty or its host doesn't end in `.onion` -- byte-faithful port
     *  of hearth's `tor.onion_host` (tor.py:44-54). */
    internal fun onionHost(addr: String?): String? {
        if (addr.isNullOrEmpty()) return null
        val host = addr.substringBeforeLast(":")
        return if (host.endsWith(".onion")) host else null
    }

    /** True if `peerAddr` is OUR OWN published onion service -- never dial
     *  ourselves (mirrors sync.py:282-286's host-keyed self-skip). Host-
     *  keyed, not identity-keyed or full-address-keyed: a paired sibling
     *  device (the home node) shares our identity_pub but runs its OWN onion
     *  service and is a legitimate peer to dial, while a same-host row at a
     *  DIFFERENT port is still recognized as our own service (an onion host
     *  uniquely identifies one service regardless of the stored port).
     *  `ownGossipAddr` null or non-onion (not yet published, or publish
     *  never ran this boot) never skips anything. */
    internal fun shouldSkipOwnOnion(peerAddr: String, ownGossipAddr: String?): Boolean {
        val ownHost = onionHost(ownGossipAddr) ?: return false
        return onionHost(peerAddr) == ownHost
    }

    /** True if `addr` was dialed within the last `intervalMs` and this dial
     *  should be SKIPPED -- mirrors sync.py:287-292's onion throttle. Records
     *  `now` into `lastOnionSync` ONLY on the allow (non-throttled) path,
     *  exactly like sync.py's `self._last_onion_sync[addr] = t`, which runs
     *  right before the dial and is never reached on the skipped branch --
     *  so a throttled call never refreshes the window (a burst of back-to-
     *  back calls all skip until the ORIGINAL window elapses, not a rolling
     *  one). An address never seen before is never throttled (first dial for
     *  a given address is always allowed). Applies only to onion addresses
     *  by convention of the caller (`runSync` gates this call on `isOnion`);
     *  the function itself doesn't care what kind of address string it's
     *  given. */
    internal fun shouldThrottle(
        addr: String,
        lastOnionSync: MutableMap<String, Long>,
        now: Long,
        intervalMs: Long = ONION_SYNC_INTERVAL_MS,
    ): Boolean {
        val last = lastOnionSync[addr]
        if (last != null && now - last < intervalMs) return true
        lastOnionSync[addr] = now
        return false
    }

    /** THE SECURITY-CRITICAL decision (friend-peering Task 4): should we
     *  accept `peerCertIdentity` (the AUTH'd cert's identity_pub, already
     *  signature-verified by `authOnlyOverStream`) as the peer we intended
     *  to sync with? Two independent gates:
     *   1. `peerCertIdentity` must be a KNOWN identity (friend OR our own
     *      home identity -- `knownIdentities` carries both) -- an unknown
     *      stranger is refused even though its signature verified fine (AUTH
     *      proves the peer CONTROLS that identity's device key, not that we
     *      trust that identity at all).
     *   2. If `expectedIdentity` (the dialed peer ROW's own `identityPub`,
     *      when known) is non-null, the AUTH'd identity must equal it
     *      exactly -- a KNOWN-but-different identity answering at a peer row
     *      we expected a SPECIFIC identity at is refused: a wrong/hostile
     *      node squatting on a friend's stored address slot must not be
     *      synced as if it were the expected friend, even if it separately
     *      authenticates as some OTHER identity we happen to know.
     *  `expectedIdentity == null` (address-only peer row, identity not yet
     *  confirmed) skips gate 2 entirely -- gate 1 (known-identity) is still
     *  the full refusal boundary for that row. Was, pre-Task-4, a single
     *  hardcoded `peerCert.identity_pub != fx.cert.identity_pub` (home-only)
     *  check in `runTransport`. */
    internal fun acceptPeerIdentity(
        peerCertIdentity: String,
        expectedIdentity: String?,
        knownIdentities: Collection<String>,
    ): Boolean {
        if (peerCertIdentity !in knownIdentities) return false
        if (expectedIdentity != null && peerCertIdentity != expectedIdentity) return false
        return true
    }

    /** `runPeerLoop`'s result: `results` is every ACTUALLY DIALED peer's
     *  outcome (skipped peers -- own-onion/throttle -- never appear here),
     *  in dial order; `selfRevokedOutcome` is non-null iff the loop stopped
     *  early because some peer's sync revealed our own revocation, in which
     *  case `results` holds only the peers dialed BEFORE that one (the
     *  revoked peer's own outcome lives in `selfRevokedOutcome`, not
     *  appended to `results` -- mirrors the pre-extraction `runSync`, which
     *  returned that outcome directly rather than folding it in). */
    internal data class PeerLoopResult(val results: List<SyncOutcome>, val selfRevokedOutcome: SyncOutcome?)

    /** The peer-loop CORE (friend-peering Task 4 review fix, Finding 2):
     *  extracted out of `runSync` as a pure, injectable function -- `dial`
     *  replaces the real `syncOnePeer(ctx, fx, peer, pending, onProgress)`
     *  call, so this whole loop (own-onion-skip, onion-throttle, per-peer
     *  dialing, the SAME `pending` set reaching every dialed peer, and the
     *  selfRevoked-stops-the-round short-circuit) is JVM-unit-testable
     *  without a real Context or Tor dial (SyncRunnerTest) -- runSync's own
     *  job then reduces to real I/O: reading peers/pending from the store,
     *  calling this, and (in `runSync`) acting on the result (wipe-on-
     *  revoked, clear-pending-on-any-ok). `pending` is passed to `dial`
     *  UNCHANGED for every peer (the fix itself -- was previously re-read
     *  fresh, and silently emptied after the first success, per peer inside
     *  runTransport; see `runSync`'s own doc). `lastOnionSync` is mutated
     *  in place by `shouldThrottle` (records `now` on the allow path), same
     *  as before this extraction. */
    internal fun runPeerLoop(
        peers: List<Peer>,
        ownGossipAddr: String?,
        pending: List<Map<String, Any?>>,
        lastOnionSync: MutableMap<String, Long>,
        now: Long,
        dial: (peer: Peer, pending: List<Map<String, Any?>>) -> SyncOutcome,
    ): PeerLoopResult {
        val results = mutableListOf<SyncOutcome>()
        for (peer in peers) {
            if (shouldSkipOwnOnion(peer.address, ownGossipAddr)) continue
            if (isOnion(peer.address) && shouldThrottle(peer.address, lastOnionSync, now)) continue

            val outcome = dial(peer, pending)
            if (outcome.selfRevoked) return PeerLoopResult(results, outcome)
            results.add(outcome)
        }
        return PeerLoopResult(results, null)
    }

    /** Should the pending-outbound queue be cleared after this round
     *  (friend-peering Task 4 review fix, Finding 2)? True iff at least one
     *  ACTUALLY DIALED peer succeeded -- `results` (from `PeerLoopResult`)
     *  already excludes skipped peers, so an EMPTY `results` (every peer
     *  skipped this round -- own-onion/throttle -- or an empty peer table)
     *  correctly returns false: the pending messages were never pushed to
     *  ANYONE this round, so clearing them would silently lose them (they
     *  must stay queued for the next round to retry). Same for "all dialed
     *  peers failed": nothing was delivered, keep them queued. Deliberately
     *  NOT the same thing as `aggregate(results).ok` (that field is `true`
     *  on an empty `results` too, for a DIFFERENT reason -- see its own doc,
     *  "nothing was wrong, nothing to dial" -- which would be the WRONG
     *  signal here). */
    internal fun shouldClearPendingOutbound(results: List<SyncOutcome>): Boolean = results.any { it.ok }

    /** Runs one peer's transport, draining blobs FROM THAT PEER across
     *  multiple rounds if needed (friend-peering Task 4 -- the per-peer
     *  scoping of what was, pre-Task-4, `runSync`'s own single-peer drain
     *  loop; see `MAX_DRAIN_ROUNDS`'s doc for the unchanged drain
     *  rationale). A SelfRevoked or failed/unsuccessful first attempt
     *  returns immediately without entering the drain loop at all -- the
     *  caller (`runSync`) is what acts on `selfRevoked` (enterRevokedState +
     *  stop dialing any further peer this round). `pending` (review Finding
     *  2 fix) is pushed on the FIRST attempt only -- a peer's own drain
     *  rounds (2..MAX_DRAIN_ROUNDS, same already-authorized session
     *  continuing) re-dial with an EMPTY pending list, since round 1 already
     *  delivered it; re-sending it every drain round would be harmless
     *  (idempotent, dedup'd on ingest) but wasteful. */
    private fun syncOnePeer(
        ctx: Context,
        fx: KotlinHandshake.Fixture,
        peer: Peer,
        pending: List<Map<String, Any?>>,
        onProgress: (String, Int) -> Unit,
    ): SyncOutcome {
        var last = runTransport(ctx, fx, peer, pending, onProgress)
        if (last.selfRevoked || !last.ran || !last.ok) return last
        var rounds = 1
        while (rounds < MAX_DRAIN_ROUNDS &&
                runCatching { anyBlobsMissing(ctx) }.getOrDefault(false)) {
            val before = last.blobs
            val next = runTransport(ctx, fx, peer, emptyList(), onProgress)
            rounds++
            if (!next.ran || !next.ok) return last   // keep the last good outcome; next cycle retries
            last = next
            if (next.blobs <= before) break          // no new blobs this round -> peer holds no more
        }
        return last
    }

    /** Folds every dialed peer's `SyncOutcome` (friend-peering Task 4) into
     *  the single `SyncOutcome` `runSync` returns to its peer-unaware
     *  callers (`TorManagerModule.syncNow`, `TorNodeService.syncCycle`).
     *  Per-peer resilience (one offline/refused peer never kills the round)
     *  means `ok` is true if ANY dialed peer's sync succeeded, not only when
     *  ALL did: a friend being offline while the home node syncs fine is a
     *  routine, healthy round, not a failure worth flagging red in the UI or
     *  skipping the decrypt-pass `TorManagerModule.syncNow` gates on
     *  `outcome.ok` for. Counts sum across every peer -- a failed/refused
     *  peer's `SyncOutcome` always carries all-0 counts (`mapSyncResult`'s
     *  SelfRevoked/Failed branches), so summing unconditionally is safe and
     *  reduces, for the single-peer (home-node-only) case, to exactly that
     *  peer's own outcome. `error` is display text only (see
     *  `TorManagerModule`'s own doc on `reason`) -- null once anything
     *  succeeded (nothing useful to show), else every distinct failure
     *  reason joined, so a fully-failed round still surfaces diagnostic
     *  text rather than a bare fallback string. An EMPTY `results` (every
     *  peer skipped by own-onion/throttle, or an empty peer table) is a
     *  no-op success: nothing was wrong, there was simply nothing to dial. */
    private fun aggregate(results: List<SyncOutcome>): SyncOutcome {
        if (results.isEmpty()) return SyncOutcome(true, true, 0, 0, 0, false, null)
        val anyOk = results.any { it.ok }
        val messages = results.sumOf { it.messages }
        val blobs = results.sumOf { it.blobs }
        val identities = results.sumOf { it.identities }
        val selfRevoked = results.any { it.selfRevoked }   // always false in practice -- runSync
                                                             // returns as soon as one fires, before
                                                             // it ever reaches this fold
        val error = if (anyOk) null
                    else results.mapNotNull { it.error }.distinct().joinToString("; ").ifEmpty { "sync failed" }
        return SyncOutcome(true, anyOk, messages, blobs, identities, selfRevoked, error)
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
        peer: Peer,
        pending: List<Map<String, Any?>>,
        onProgress: (String, Int) -> Unit,
    ): SyncOutcome {
        try {
            val (host, port) = KotlinHandshake.splitAddr(peer.address)
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

            // SqliteSyncStore construction / addIdentity are SQLite I/O and can throw
            // (e.g. DB locked). authOnlyOverStream succeeding leaves the stream open
            // by contract -- only KotlinSync.run's finally closes it -- so a failure
            // here, before KotlinSync.run ever runs, must close the stream itself or
            // the Tor connection leaks for the process lifetime. Moved ahead of the
            // identity-acceptance check below (friend-peering Task 4): that check now
            // needs `store.knownIdentities()`, so the store must exist first.
            val store = try {
                SqliteSyncStore(ctx).also { it.addIdentity(fx.cert.identity_pub) }
            } catch (e: Exception) {
                stream.close()
                return SyncOutcome(true, false, 0, 0, 0, false, "store: ${e.message}")
            }
            // (own identity is seeded above, inside the try, so ingest's is_known
            // gate admits its own-identity messages; the HAVE phase adds the node's
            // known identities.)

            // THE SECURITY CHANGE (friend-peering Task 4): authOnlyOverStream
            // verifies the peer cert is validly signed but does NOT pin it to any
            // particular identity -- pin it here so dialing a peer row can never
            // sync us into a stranger's node. Was a single hardcoded `==
            // fx.cert.identity_pub` (home-only, pre-Task-4); now accepts ANY known
            // identity (friend or our own home identity -- store.knownIdentities()
            // carries both) at an address-only peer row, AND, when this peer row
            // names an expected identity (`peer.identityPub`), requires the AUTH'd
            // cert to match it exactly -- see acceptPeerIdentity's doc for the full
            // reasoning (the wrong-address guard).
            if (!acceptPeerIdentity(peerCert.identity_pub, peer.identityPub, store.knownIdentities())) {
                stream.close()
                store.close()
                return SyncOutcome(true, false, 0, 0, 0, false, "auth: peer identity not accepted")
            }

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

            // outbound Task 3 / friend-peering Task 4 review fix (Finding 2):
            // `pending` (composed-but-not-yet-pushed messages -- Compose.post
            // queues via store.addPendingOutbound; see SyncStore.
            // pendingOutbound's doc) is now an INJECTED param, not read from
            // the store here -- `runSync` captures ONE snapshot for the whole
            // round and passes it to every dialed peer's first attempt, and
            // clears it ONCE after the whole loop (see runSync's own doc for
            // why: reading+clearing it per-peer, here, used to mean only the
            // FIRST peer to sync successfully ever received it). This
            // function no longer touches `clearPendingOutbound` at all --
            // `mapSyncResult` below is called with no `pendingIds` (defaults
            // to empty), a deliberate no-op on that front.
            //
            // Close the round's store once its result is mapped -- each drain
            // round opens a fresh one, and an unclosed SQLiteOpenHelper leaks a
            // SQLiteConnection (the leak class the LocalApi shared-store fix
            // addresses). mapSyncResult reads store.stats() first, so map before close.
            //
            // ownIdentity = fx.cert.identity_pub (phone-onion-reachability
            // Task 4): without this, KotlinSync.run's DEFRIENDS phase would
            // gate every incoming DefriendNotice against the default ""
            // (applyDefriendNotice's target==own check would never match a
            // real notice) -- this is the ONE production call site of
            // KotlinSync.run, so this is what makes a real friend's defriend
            // notice actually take effect on this device.
            //
            // peerIdentity = peerCert.identity_pub (friend-peering Task 4
            // review fix, Finding 1): the AUTH'd peer's identity, so `run`'s
            // own HAVE-phase own-device-trust gate can tell "this peer IS our
            // own home node" (peerIdentity == ownIdentity, widen known[])
            // apart from "this peer is a friend" (must NOT widen known[]) --
            // see that gate's doc in KotlinSync.kt for the full reasoning.
            val outcome = mapSyncResult(
                KotlinSync.run(stream, store, fx.device_pub, prep.outbound + pending, onProgress,
                    ownIdentity = fx.cert.identity_pub, peerIdentity = peerCert.identity_pub),
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
     *  retries the push. Same rule for `pendingIds` (outbound Task 3): only an
     *  Ok result clears the pending-outbound queue -- a SelfRevoked/Failed
     *  sync leaves every queued message in place so the NEXT sync retries the
     *  push, exactly like the enc-key marker. `pendingIds` defaults to empty
     *  (see mapSyncResultForTest's doc) so clearPendingOutbound is a no-op
     *  for callers that never populated the queue. */
    private fun mapSyncResult(
        r: SyncResult, prep: EncKeyPrep, store: SyncStore, pendingIds: List<String> = emptyList()
    ): SyncOutcome = when (r) {
        is SyncResult.Ok -> {
            if (prep.shouldPublish) store.setPublishedEncPub(prep.encPub)
            if (pendingIds.isNotEmpty()) store.clearPendingOutbound(pendingIds)
            SyncOutcome(true, true, r.messages, r.blobs, r.identities, false, null)
        }
        is SyncResult.SelfRevoked -> SyncOutcome(true, false, 0, 0, 0, true, "self-revoked")
        is SyncResult.Failed -> SyncOutcome(true, false, 0, 0, 0, false, "${r.stage}: ${r.reason}")
    }
}
