package expo.modules.tormanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class TorNodeService : Service() {
    companion object {
        const val CHANNEL_ID = "kreds-node"
        const val NOTIF_ID = 1
        // Task 6 (friend-peering, cadence overhaul): replaces the old fixed
        // SYNC_INTERVAL_MS = 900_000L (15 min, was Brick C's 5-min AUTH
        // heartbeat before that) with an adaptive floor/ceiling -- see
        // AdaptiveBackoff. BASE is the interval used right after a sweep
        // pulls new content AND right after any event trigger (on-compose /
        // on-app-resume); MAX is the ceiling idle sweeps double toward and
        // hold at, so a quiet peer is still checked at least once an hour.
        private const val BASE_SYNC_MS = 600_000L    // 10 min
        private const val MAX_SYNC_MS = 3_600_000L   // 1 hr
        // Foreground-fast cadence (post-Task-6 follow-up, friend-peering
        // arc): Task 6's adaptive backoff (above) is correct for BACKGROUND
        // (battery/Doze), but while the app is FOREGROUND (screen on, user
        // watching the feed) it left passively-arriving content waiting up
        // to BASE_SYNC_MS (10 min) even with the app open -- the desktop
        // node syncs near-real-time (a ~3s gossip loop). FOREGROUND_SYNC_MS
        // is the sweep interval used instead of the adaptive-backoff value
        // while `appForeground` is true (see chooseSweepDelay below).
        // `internal`, not `private`: mirrors ONION_VIRTUAL_PORT's own
        // precedent just below -- referenced directly from a JVM test
        // (TorNodeServiceCadenceTest) with zero Android dependency, same
        // reasoning as that constant's doc.
        //
        // 30s, not shorter: SyncRunner's ONION_SYNC_INTERVAL_MS (~45s) is
        // the per-peer onion-dial floor and is UNCHANGED by this task -- a
        // sweep more frequent than that throttle window dials the same
        // onion address before its own floor has elapsed and is a no-op for
        // that peer. 30s sweeps mean a given peer is effectively dialed
        // roughly once per throttle window, which is the practical
        // near-real-time ceiling over Tor today; a true push (the nudge
        // channel, a carried follow-up ticket from the peering arc's DoD)
        // is the eventual upgrade past this polling floor.
        internal const val FOREGROUND_SYNC_MS = 30_000L   // 30 sec
        // Task 7 (phone-onion-reachability): the public port peers dial on
        // this phone's onion service. FIXED FOREVER (mirrors hearth's own
        // tor.py:41 ONION_VIRTUAL_PORT) -- re-picking this once deadlocked
        // every node (0.3.14's outage), and TorTransport.connect on the
        // desktop side normalizes every .onion dial to this exact port
        // regardless of what a stored address says, so a value that ever
        // drifted from the desktop's own constant would silently strand
        // dialbacks.
        // `internal` (whole-branch review fix, Finding 2 -- was `private`):
        // SyncRunner's dial path (`runTransport`) needs this same constant
        // to normalize every `.onion` dial to this fixed port, mirroring
        // hearth transport.py's `TorTransport.connect` -- see
        // `SyncRunner.dialTarget`'s own doc for the DoS this normalization
        // closes (a poisoned/relayed same-host, different-port peer row
        // evicting the correct `:9997` row via `mergePeerAddress`'s host-
        // keyed eviction, whose own safety premise -- documented on
        // `SyncStore.kt`'s `mergePeerAddress` -- assumes this normalization
        // already holds at dial time).
        internal const val ONION_VIRTUAL_PORT = 9997
        // No pre-existing android.util.Log usage anywhere in this file to
        // mirror; TorManagerModule.kt set the "module name as tag" precedent
        // (its own TAG doc comment) -- followed here with this class's name.
        private const val TAG = "TorNodeService"
        const val ACTION_STOP = "eu.kreds.torspike.STOP"
        const val ACTION_BEAT_NOW = "eu.kreds.torspike.BEAT_NOW"
        // Foreground-fast cadence: the app's AppState transition bridge (RN
        // WebShell.tsx -> TorManagerModule.setAppForeground -> here) flips
        // `appForeground`, which sweepAndReschedule's next tick picks up via
        // chooseSweepDelay. Deliberately a SEPARATE action from
        // ACTION_BEAT_NOW: entering foreground both flips this flag AND (via
        // the RN side calling beatNow() alongside, unchanged from Task 6)
        // kicks one immediate sweep -- see setForeground's own doc for why
        // this action does not ALSO trigger a sweep itself (that would
        // double a sweep already requested by the same transition).
        const val ACTION_SET_FOREGROUND = "eu.kreds.torspike.SET_FOREGROUND"
        const val EXTRA_FOREGROUND = "foreground"
        const val BROADCAST_BEAT = "eu.kreds.torspike.BEAT"
        const val BROADCAST_STATE = "eu.kreds.torspike.STATE"
        // Task 6 (phone-onion-reachability): self-revoke -> full wipe to
        // First-Load. TorManagerModule's own BroadcastReceiver (mirroring
        // its existing BROADCAST_BEAT/BROADCAST_STATE handling) listens for
        // this and bridges it to the RN-facing "revoked" event.
        const val BROADCAST_REVOKED = "eu.kreds.torspike.REVOKED"

        fun start(ctx: Context) {
            val i = Intent(ctx, TorNodeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, TorNodeService::class.java).setAction(ACTION_STOP))
        fun beatNow(ctx: Context) = ctx.startService(Intent(ctx, TorNodeService::class.java).setAction(ACTION_BEAT_NOW))

        /** Foreground-fast cadence: sets `appForeground` on whatever
         *  TorNodeService instance is currently running (a bare
         *  `startService` + action, exactly like `beatNow`/`stop` above --
         *  if the service isn't running, this starts a fresh instance whose
         *  `onStartCommand` sees ACTION_SET_FOREGROUND, sets the flag, and
         *  returns WITHOUT calling `startNode()` -- see onStartCommand's
         *  `when` block, the same no-op-safe shape `ACTION_BEAT_NOW` already
         *  has for a not-yet-started service: `scheduler` stays null, no
         *  Tor/gossip/sync work is kicked off, nothing throws). Does NOT
         *  itself trigger a sweep -- the RN caller (WebShell.tsx's AppState
         *  listener) calls `beatNow()` alongside this on the SAME
         *  active-transition, so a redundant sweep here would just double
         *  that one up. Only the flag flips; the single self-rescheduling
         *  chain (sweepAndReschedule) picks up the new interval on its own
         *  next tick, per chooseSweepDelay below. */
        fun setForeground(ctx: Context, active: Boolean) =
            ctx.startService(Intent(ctx, TorNodeService::class.java)
                .setAction(ACTION_SET_FOREGROUND)
                .putExtra(EXTRA_FOREGROUND, active))

        /** Self-revoke -> full wipe to First-Load (Task 6; node.py:3144's
         *  `enter_revoked_state` is the desktop analog: destroy ALL key
         *  material, drop the whole synced store). The phone-side
         *  counterpart destroys pairing.json (device_priv + identity_priv,
         *  PairingStore.wipe) and the legacy external fixture, and drops
         *  the whole sync_store.db (identities/messages/blobs/keys --
         *  INCLUDING the enc keypair, which lives in that DB's `keys`
         *  table -- /pending_outbound/meta/revoked_devices; see
         *  SqliteSyncStore.wipe's doc for why no separate blob-cache clear
         *  is needed beyond that).
         *
         *  A STATIC/companion function taking a bare Context, not an
         *  instance method: it has TWO independent call sites with no
         *  shared TorNodeService instance -- SyncRunner.runSync (the
         *  outbound path; reached from BOTH this service's own syncCycle
         *  AND the foreground TorManagerModule.syncNow, since both funnel
         *  through the same SyncRunner choke point) and GossipServer's
         *  onSelfRevoked callback (the inbound serve path, wired in
         *  startGossipServer() below). Only the latter is even reached FROM
         *  a running TorNodeService instance -- the former can fire with no
         *  TorNodeService instance alive at all (a foreground-only sync,
         *  background service never started).
         *
         *  Order: wipe identity + store FIRST -- a caller polling
         *  hasIdentity() must never observe a window where the old identity
         *  is still readable after SelfRevoked has already been detected --
         *  THEN best-effort stop() the foreground service (a harmless
         *  intent even if no instance is currently running; if one IS
         *  running, its own shutdown() closes gossipStore/gossipServer and
         *  cancels the sync scheduler) -- THEN broadcast, so
         *  TorManagerModule's receiver can sendEvent("revoked", ...) to the
         *  RN layer (FirstLoad.tsx re-checks hasIdentity(), now false, and
         *  un-links back to the menu).
         *
         *  Idempotent: PairingStore.wipe/SqliteSyncStore.wipe both
         *  delete-if-present (never throws on an absent target), and
         *  stop()/the broadcast are harmless repeats -- a second
         *  SelfRevoked observed after the first wipe (e.g. the inbound
         *  serve path noticing moments after an outbound sync already
         *  wiped) does nothing further. */
        fun enterRevokedState(ctx: Context) {
            PairingStore.wipe(ctx)
            SqliteSyncStore.wipe(ctx)
            stop(ctx)
            ctx.sendBroadcast(Intent(BROADCAST_REVOKED).setPackage(ctx.packageName))
        }

        /** Foreground-fast cadence: the pure choice `sweepAndReschedule`
         *  makes between the two candidate next-sweep intervals it already
         *  has in hand -- `foregroundMs` (FOREGROUND_SYNC_MS) while the app
         *  is active, else whatever AdaptiveBackoff.nextInterval just
         *  computed (`backoffNext`). Extracted into its own function (rather
         *  than an inline `if` at the call site) purely so it's unit-testable
         *  on a plain JVM with no Android/Service dependency -- same
         *  reasoning as AdaptiveBackoff.pulledNewContent's own doc for why
         *  that decision is a standalone function rather than inlined into
         *  syncCycle. `internal`: referenced directly from
         *  TorNodeServiceCadenceTest, mirroring ONION_VIRTUAL_PORT's own
         *  internal-for-JVM-test-access precedent above. */
        internal fun chooseSweepDelay(foreground: Boolean, foregroundMs: Long, backoffNext: Long): Long =
            if (foreground) foregroundMs else backoffNext
    }

    @Volatile private var scheduler: ScheduledExecutorService? = null
    @Volatile private var syncs = 0
    @Volatile private var lastLine = "starting"
    // Foreground-fast cadence: flipped by ACTION_SET_FOREGROUND (from
    // TorManagerModule.setAppForeground, driven by WebShell.tsx's AppState
    // listener) -- true while the app is active/visible. Read by
    // sweepAndReschedule (the single self-rescheduling chain's own thread)
    // via chooseSweepDelay; written from onStartCommand, which can run on a
    // different thread (the same cross-thread shape ACTION_BEAT_NOW's
    // backoff.reset() already has) -- @Volatile for the same
    // cross-thread-visibility discipline as every other mutable field on
    // this class, not because reads/writes need to be atomic-compound (a
    // plain boolean flag has no read-modify-write to protect, unlike
    // AdaptiveBackoff's AtomicLong `current`).
    @Volatile private var appForeground: Boolean = false
    // Task 6 review fix (Finding 1): previous round's absolute store total
    // (messages+blobs+identities), the baseline `syncCycle` diffs against
    // to derive `pulledNew` -- see AdaptiveBackoff.pulledNewContent's own
    // doc for why an absolute-total>0 check was wrong. -1L sentinel == "no
    // successful (ran && ok) round yet this process"; the very first real
    // sweep then counts as pulled-new, resetting the cadence to base, which
    // is the desired startup behavior. Only ever written from syncCycle,
    // which always runs on the single scheduler thread -- @Volatile for the
    // same cross-thread-visibility discipline as `syncs`/`lastLine` above,
    // not because there's a concurrent writer.
    @Volatile private var lastSyncTotal: Long = -1L

    // Task 6: one AdaptiveBackoff per service instance, driving the
    // self-rescheduling chain in startNode()'s onDone below. reset() is
    // called from ACTION_BEAT_NOW (a different thread than the scheduler
    // chain) -- see AdaptiveBackoff's own thread-safety doc.
    private val backoff = AdaptiveBackoff(BASE_SYNC_MS, MAX_SYNC_MS)

    // Gossip server Task 4: the inbound (answering) counterpart to the
    // outbound syncCycle below. One SqliteSyncStore, opened once here and
    // held for the service's whole lifetime (NOT the per-round open/close
    // SyncRunner.runTransport does for its own outbound store -- GossipServer's
    // constructor takes a single injected store instance, so it must be
    // long-lived by construction; concurrent SQLiteOpenHelper connections to
    // the same DB file are already how this app runs today, and writes across
    // them are serialized by the shared SyncRunner.syncLock both this
    // server and runSync acquire -- see GossipServer.kt's class doc).
    // fixtureProvider uses readFixtureOrNull (never throws): before first-load
    // pairing completes there is no identity to authenticate inbound peers
    // with, and GossipServer.handle already refuses/closes cleanly on a null
    // fixture rather than crashing a worker thread.
    private var gossipStore: SqliteSyncStore? = null
    private var gossipServer: GossipServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            // Task 6: an event trigger (on-compose / on-app-resume, both
            // funneled through TorNodeService.beatNow -> this action) resets
            // the adaptive cadence to base REGARDLESS of what this extra
            // sweep itself finds, then runs one sweep right now. It does NOT
            // start a second recurring chain -- only sweepAndReschedule
            // (the startup chain) reschedules itself; this is a one-off.
            ACTION_BEAT_NOW -> { backoff.reset(); scheduler?.execute { syncCycle() }; return START_STICKY }
            // Foreground-fast cadence: flips `appForeground` only -- no
            // sweep kicked here (see setForeground's own doc for why: the RN
            // caller already fires beatNow() on the same transition). Safe
            // on a not-yet-started instance exactly like ACTION_BEAT_NOW
            // above -- this branch never touches `scheduler`, so there is
            // nothing here that can NPE or need it non-null; the flag simply
            // sits set until (or unless) startNode() ever runs. The single
            // self-rescheduling chain (sweepAndReschedule) picks the new
            // interval up on its own next tick via chooseSweepDelay.
            ACTION_SET_FOREGROUND -> {
                appForeground = intent.getBooleanExtra(EXTRA_FOREGROUND, false)
                return START_STICKY
            }
        }
        if (scheduler == null) startNode()
        return START_STICKY      // OS restarts us after process death
    }

    private fun startNode() {
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification("Kreds node starting..."))
        broadcastState("bootstrapping")
        TorEngine.init(this)
        startGossipServer()
        scheduler = Executors.newSingleThreadScheduledExecutor()
        // Bootstrap once on the scheduler thread; on success, begin the sync cadence.
        scheduler!!.execute {
            TorEngine.bootstrap(
                onProgress = { p -> updateNotification("Tor bootstrap $p%") },
                onDone = {
                    // onDone fires on TorEngine's watcher thread, NOT the
                    // scheduler thread. Post ALL sync work back onto the
                    // single scheduler thread so syncs never overlap (a beatNow
                    // queued during bootstrap would otherwise race the first
                    // sync). scheduler?. + runCatching guard the stop-during-
                    // bootstrap race (shutdown() nulls/shuts the executor);
                    // without them scheduler!!.schedule NPEs or throws
                    // RejectedExecutionException on this thread and kills the process.
                    broadcastState("up")
                    val s = scheduler
                    runCatching {
                        // Task 7: publish/republish the onion FIRST, still on
                        // the single scheduler thread so it can never overlap
                        // a sync (same overlap concern the comment above this
                        // block already explains for syncCycle). By the time
                        // this callback fires, startGossipServer() (called
                        // synchronously in startNode(), well before
                        // TorEngine.bootstrap even began) has already
                        // returned, so gossipServer.boundPort is already
                        // valid -- Tor being up is the only thing this
                        // callback itself was waiting on. Submitted BEFORE
                        // the immediate first sync below so that sync's own
                        // HAVE has the best chance of already advertising the
                        // (possibly freshly republished) gossip_addr.
                        s?.execute { publishOnion() }
                        // Task 6: immediate first sweep, then self-reschedules
                        // adaptively -- replaces the old immediate
                        // s?.execute { syncCycle() } + s?.scheduleAtFixedRate(
                        // syncCycle, SYNC_INTERVAL_MS, SYNC_INTERVAL_MS) pair
                        // with the ONE call below. See sweepAndReschedule's doc.
                        s?.execute { sweepAndReschedule() }
                    }
                },
                onError = { code, msg ->
                    broadcastState("error")
                    updateNotification("Tor failed: $code")
                    recordAndBroadcast(Beat(System.currentTimeMillis(), false, 0, "$code: $msg"))
                },
            )
        }
    }

    // Dual-read (Task 3, first-load pairing): internal pairing.json first,
    // else the legacy external spike_phone_fixture.json exactly as before.
    // Throws on failure (both call sites here already wrap this in a
    // try/catch) -- see PairingStore.readFixture.
    private fun fixture(): KotlinHandshake.Fixture = PairingStore.readFixture(this)

    // Gossip server Task 4: brings up the inbound (answering) side alongside
    // Tor. GossipServer binds loopback-only and the real onion-service
    // forwarding rule only starts working once TorEngine's bootstrap
    // actually completes, so starting the accept loop this early (right
    // after TorEngine.init, before bootstrap finishes) is harmless -- it is
    // simply not reachable from outside until Tor is up, same as how a
    // friend can't dial an onion address that hasn't bootstrapped yet.
    // readFixtureOrNull (never throws): before first-load pairing completes
    // there is no identity to authenticate inbound peers with;
    // GossipServer.handle already closes cleanly on a null fixture rather
    // than needing this to throw.
    private fun startGossipServer() {
        val store = SqliteSyncStore(this)
        gossipStore = store
        gossipServer = GossipServer(
            store, { PairingStore.readFixtureOrNull(this) }, SyncRunner.syncLock, port = 0,
            // Task 6: an inbound sync revealing our own revocation drives
            // the same wipe the outbound path (SyncRunner.runSync) does --
            // see GossipServer's onSelfRevoked doc and enterRevokedState's
            // own doc for the full ordering/idempotency contract.
            onSelfRevoked = { enterRevokedState(this) },
        ).also { it.start() }
    }

    private fun stopGossipServer() {
        runCatching { gossipServer?.stop() }; gossipServer = null
        runCatching { gossipStore?.close() }; gossipStore = null
    }

    /** Publish (or republish) this device's onion service (Task 7,
     *  phone-onion-reachability), forwarding to GossipServer's own bound
     *  loopback port so a friend/sibling dialing our .onion reaches it.
     *  Called from `startNode`'s `onDone`, once Tor has bootstrapped --
     *  `gossipServer.boundPort` is ALREADY valid by then regardless (
     *  `startGossipServer()` runs synchronously in `startNode`, well before
     *  `TorEngine.bootstrap` is even kicked off), so the only thing this
     *  callback actually waits on is Tor being up enough for the control
     *  port to answer AUTHENTICATE.
     *
     *  Uses `gossipStore` -- the SAME long-lived `SqliteSyncStore` instance
     *  `startGossipServer()` already opened onto `sync_store.db` (a fixed
     *  DB_NAME) -- rather than opening a second connection: the meta rows
     *  written here (`onion_key`/`gossip_addr`) must be immediately visible
     *  to `KotlinSync.run`/`serve`'s HAVE phase on EITHER sync path
     *  (`SyncRunner.runTransport`'s own `SqliteSyncStore(ctx)` for the
     *  outbound path, `serve`'s caller -- this same `gossipStore` -- for the
     *  inbound path); since both opens target the same on-disk file, reusing
     *  `gossipStore` here is simply the cheaper of two equally-correct
     *  options.
     *
     *  Key persistence: `savedBlob` is whatever `addOnion` returned last
     *  boot (null on the very first run ever). Passing it back in makes Tor
     *  mint the exact SAME onion identity again (`ControlPort.addOnion`'s
     *  own contract: a `NEW:ED25519-V3` key spec only when no saved blob
     *  exists) -- a stable `.onion` across restarts, even though
     *  `boundPort` itself (the loopback forwarding target) is a fresh
     *  ephemeral port every boot. Re-issued unconditionally on EVERY start,
     *  not just the first: `Flags=Detach` keeps a PRIOR boot's onion alive
     *  in the Tor daemon only as long as that daemon process lives, and a
     *  fresh `tor` process (this boot's `TorEngine.bootstrap`) starts with
     *  no onions published at all -- so every boot must ADD_ONION again
     *  regardless of whether the key is new or reused.
     *
     *  Best-effort by design (per the Task 7 brief): a control-port hiccup,
     *  a cookie-auth race, or a store I/O failure here must never crash this
     *  foreground service -- reachability (friends/the desktop dialing IN)
     *  simply degrades for this boot, while the phone continues working
     *  fine as a sync-INITIATING client regardless. Logged, not rethrown;
     *  the next restart's attempt is entirely independent of this one's
     *  outcome. */
    private fun publishOnion() {
        runCatching {
            val store = gossipStore ?: return@runCatching
            val port = gossipServer?.boundPort ?: -1
            if (port <= 0) return@runCatching
            val ctl = ControlPort(CONTROL_PORT, TorEngine.cookieFile())
            val savedBlob = store.getMeta("onion_key")
            val (serviceId, blob) = ctl.addOnion(ONION_VIRTUAL_PORT, port, savedBlob)
            if (blob != null) store.setMeta("onion_key", blob)
            store.setMeta("gossip_addr", "$serviceId.onion:$ONION_VIRTUAL_PORT")
        }.onFailure { e ->
            Log.w(TAG, "onion publish failed (best-effort -- reachability degrades this boot): ${e.message}")
        }
    }

    // Brick C Task 2: was the bare AUTH heartbeat (dial + KotlinHandshake.run).
    // Now drives the full content-sync transport via SyncRunner.runSync, which
    // owns dial -> AUTH -> identity pin -> store-seed -> enc-key prep -> sync ->
    // mark-published, under the process-wide sync mutex shared with the
    // foreground module's syncNow. SyncRunner NEVER decrypts (background
    // stores encrypted only; decrypt-on-read happens in the foreground), so
    // no decrypt/content-key/feed-cache work is introduced here.
    // Task 6: now RETURNS whether this sweep pulled new content --
    // sweepAndReschedule below feeds this straight into
    // AdaptiveBackoff.nextInterval to pick the next interval. Everything
    // else is unchanged from before Task 6.
    // Task 6 review fix (Finding 1): "pulled new" used to be `ran && ok &&
    // (total > 0)`, where `total` is SyncRunner's messages+blobs+identities
    // -- but those are ABSOLUTE store totals (store.stats() is an
    // unconditional SELECT COUNT(*)), not "new this round". Own identity is
    // always seeded (identities >= 1 on every successful AUTH) and
    // messages/blobs never shrink, so `total > 0` was true on essentially
    // every successful sync -- the adaptive feature never actually backed
    // off. Now derived as a DELTA against `lastSyncTotal` via
    // AdaptiveBackoff.pulledNewContent; see that function's doc for the
    // full rationale.
    //
    // Whole-branch review fix (Finding 4): `nowTotal` used to be
    // `o.messages + o.blobs + o.identities` off the AGGREGATED (summed-
    // across-peers) SyncOutcome -- but each peer's own counts are already
    // this device's ABSOLUTE whole-store totals (SyncRunner.runTransport's
    // `mapSyncResult` reads them straight from `store.stats()` per peer), so
    // with N successful peers `nowTotal` scaled to roughly N times the real
    // store size, and a flapping peer count alone could spuriously reset or
    // suppress the backoff. `o.storeTotalAfter` is SyncRunner's own single
    // post-round `store.stats()` read (peer-count-independent) -- see that
    // field's doc.
    private fun syncCycle(): Boolean {
        val start = System.currentTimeMillis()
        var pulledNew = false
        val beat = try {
            if (!TorEngine.isUp) throw IllegalStateException("tor not up")
            val fx = fixture()
            val o = SyncRunner.runSync(this, fx)     // default no-op progress; NEVER decrypts
            val nowTotal = o.storeTotalAfter
            pulledNew = AdaptiveBackoff.pulledNewContent(lastSyncTotal, nowTotal, o.ran, o.ok)
            // Advance the baseline ONLY on a real ran&&ok round -- a
            // skipped (mutex-contended) or failed round must not overwrite
            // it, or it would corrupt the NEXT round's delta (see
            // pulledNewContent's doc).
            if (o.ran && o.ok) lastSyncTotal = nowTotal
            // skipped = true (Brick C Task 3 fix): SyncRunner.ran == false is
            // a benign mutex contention (the foreground syncNow held the
            // lock), not a real failure -- flagged with a dedicated boolean
            // rather than relying on callers to string-match this reason
            // text, so the Recent-syncs list can render it neutral instead
            // of red FAIL.
            if (!o.ran) Beat(start, false, System.currentTimeMillis() - start, "skipped (in progress)", skipped = true)
            else if (o.ok) Beat(start, true, System.currentTimeMillis() - start, null, o.messages, o.blobs, o.identities)
            else Beat(start, false, System.currentTimeMillis() - start, o.error ?: "sync failed")
        } catch (e: Exception) {
            Beat(start, false, System.currentTimeMillis() - start, "io: ${e.message}")
        }
        // recordAndBroadcast does prefs I/O + notify + sendBroadcast, all of
        // which can throw on-device. An uncaught throwable here must not
        // propagate to the scheduler thread -- see sweepAndReschedule's own
        // doc for why an uncaught throwable there would silently end the
        // whole cadence. syncCycle() therefore never throws to the caller.
        runCatching { recordAndBroadcast(beat) }
        return pulledNew
    }

    /** Task 6: self-rescheduling replacement for the old
     *  `s.scheduleAtFixedRate(syncCycle, SYNC_INTERVAL_MS, SYNC_INTERVAL_MS)`
     *  fixed cadence. Runs one sweep, then reschedules ITSELF after an
     *  interval AdaptiveBackoff computes from whether that sweep pulled new
     *  content -- never a fixed period.
     *
     *  Task 6 review fix (Finding 2): interval computation and the
     *  reschedule call are TWO INDEPENDENT runCatching blocks, not one.
     *  Under the original single-runCatching shape, a throw out of
     *  `backoff.nextInterval` would skip the `scheduler?.schedule(...)`
     *  call entirely -- silently ending the whole chain, since nothing else
     *  re-arms it, killing the sync cadence for the rest of the process's
     *  life. Now a failed interval computation falls back to
     *  `BASE_SYNC_MS` and the reschedule still happens unconditionally;
     *  only a failure in the `schedule()` call itself (e.g. the
     *  stop-during-bootstrap race nulling `scheduler`) can end the chain,
     *  exactly as intended before.
     *
     *  This is the ONLY function that reschedules itself. ACTION_BEAT_NOW
     *  (the on-compose / on-app-resume event trigger, via beatNow) does NOT
     *  call this -- it only resets `backoff` to base and runs one extra,
     *  one-off `syncCycle()` alongside (see onStartCommand). There is
     *  therefore always exactly one live chain per running service
     *  instance, regardless of how many event triggers fire in between.
     *
     *  Whole-branch review fix, Finding M1 (MINOR -- chain can die on a JVM
     *  Error): `syncCycle()` itself is documented to "never throw to the
     *  caller" (see its own closing comment), but that guarantee is built on
     *  `runCatching`/try-catch blocks that only catch `Exception`, not
     *  `Throwable` -- a JVM `Error` (e.g. `OutOfMemoryError` during the
     *  1-2 min sync) would propagate straight out of `syncCycle()`, past
     *  this call, and kill this scheduler thread -- ending the cadence chain
     *  permanently, contradicting this doc's own "always exactly one live
     *  chain" guarantee. `runCatching { syncCycle() }.getOrDefault(false)`
     *  closes that last gap: now only a failure in `scheduler?.schedule`
     *  itself (e.g. the stop-during-bootstrap race nulling `scheduler`) can
     *  ever end the chain, matching the Task 6 review fix (Finding 2)
     *  two-independent-runCatching-blocks reasoning just below -- a bad
     *  sweep must never take the reschedule down with it.
     *
     *  Foreground-fast cadence: the interval actually scheduled is now
     *  `chooseSweepDelay(appForeground, FOREGROUND_SYNC_MS, backoffNext)`,
     *  not the raw adaptive-backoff value -- while the app is foreground
     *  (`appForeground == true`, flipped by ACTION_SET_FOREGROUND) every
     *  tick reschedules at the short, fixed FOREGROUND_SYNC_MS regardless of
     *  what AdaptiveBackoff itself is holding; while backgrounded, behavior
     *  is byte-for-byte the Task 6 adaptive value, unchanged.
     *
     *  `backoff.nextInterval` is deliberately SKIPPED (not just discarded)
     *  while foreground, rather than computed-but-unused every tick: calling
     *  it anyway would silently advance its internal state once per
     *  FOREGROUND_SYNC_MS tick regardless of the result being needed, and
     *  `nextInterval(false)` DOUBLES toward MAX_SYNC_MS on every idle sweep
     *  (a foreground sweep that pulls nothing new is exactly that) -- a long
     *  foreground session with a quiet peer would silently walk the backoff
     *  all the way to the 1 hr cap in the background, undetected because
     *  nothing was reading it. The moment the app then backgrounds,
     *  `sweepAndReschedule`'s NEXT tick would read that stale, fully-doubled
     *  value instead of the short interval the user just had while
     *  watching -- a jarring cliff exactly backwards from Task 6's intent
     *  (idle backs off gradually, not in one leap). `beatNow()` already
     *  calls `backoff.reset()` at the START of every foreground session
     *  (fired alongside `setAppForeground(true)` on the same 'active'
     *  transition, see `setForeground`'s doc) -- freezing the backoff
     *  untouched for the REST of that session means it is still sitting at
     *  exactly `BASE_SYNC_MS` whenever foreground ends, which is precisely
     *  the "next reschedule starts from base" behavior the background
     *  transition is specified to have. */
    private fun sweepAndReschedule() {
        val pulledNew = runCatching { syncCycle() }.getOrDefault(false)
        val foreground = appForeground
        val backoffNext = if (foreground) BASE_SYNC_MS
            else runCatching { backoff.nextInterval(pulledNew) }.getOrDefault(BASE_SYNC_MS)
        val next = chooseSweepDelay(foreground, FOREGROUND_SYNC_MS, backoffNext)
        runCatching { scheduler?.schedule({ sweepAndReschedule() }, next, TimeUnit.MILLISECONDS) }
    }

    private fun recordAndBroadcast(beat: Beat) {
        HeartbeatStore.record(this, beat)
        syncs++
        val hhmm = SimpleDateFormat("HH:mm", Locale.US).format(Date(beat.ts))
        lastLine = if (beat.ok) "last sync $hhmm OK, ${beat.messages} msgs, ${beat.latencyMs}ms - $syncs syncs"
                   else "last sync $hhmm FAIL (${beat.reason}) - $syncs syncs"
        updateNotification(lastLine)
        // Payload intentionally unchanged from Brick A (ts/ok/latencyMs/reason)
        // -- the new pulled counts live on Beat/HeartbeatStore for now. Adding
        // them here would require updating TorManagerModule's BROADCAST_BEAT
        // receiver + App.tsx (Task 3); flagged in the Task 2 report rather
        // than done here to keep this change surgical.
        sendBroadcast(Intent(BROADCAST_BEAT).setPackage(packageName).apply {
            putExtra("ts", beat.ts); putExtra("ok", beat.ok)
            putExtra("latencyMs", beat.latencyMs); putExtra("reason", beat.reason)
        })
    }

    private fun broadcastState(state: String) =
        sendBroadcast(Intent(BROADCAST_STATE).setPackage(packageName).putExtra("state", state))

    private fun shutdown() {
        scheduler?.shutdownNow(); scheduler = null
        stopGossipServer()
        runCatching { TorEngine.suspend() }
        broadcastState("stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopGossipServer()   // idempotent -- a no-op if shutdown() already ran
        runCatching { TorEngine.suspend() }
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Kreds node", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kreds node")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)   // placeholder system icon
            .setOngoing(true)
            .addAction(0, "Stop", android.app.PendingIntent.getService(
                this, 1, Intent(this, TorNodeService::class.java).setAction(ACTION_STOP),
                android.app.PendingIntent.FLAG_IMMUTABLE))
            .build()

    private fun updateNotification(text: String) =
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
}
