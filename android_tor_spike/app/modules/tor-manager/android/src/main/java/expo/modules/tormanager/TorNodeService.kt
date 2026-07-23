package expo.modules.tormanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
        private const val SYNC_INTERVAL_MS = 900_000L    // N = 15 min (Brick C: was the 5-min AUTH heartbeat)
        const val ACTION_STOP = "eu.kreds.torspike.STOP"
        const val ACTION_BEAT_NOW = "eu.kreds.torspike.BEAT_NOW"
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
    }

    @Volatile private var scheduler: ScheduledExecutorService? = null
    @Volatile private var syncs = 0
    @Volatile private var lastLine = "starting"

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
            ACTION_BEAT_NOW -> { scheduler?.execute { syncCycle() }; return START_STICKY }   // manual "beat now" now triggers a full sync
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
                        s?.execute { syncCycle() }   // immediate first sync, on the scheduler thread
                        s?.scheduleAtFixedRate({ syncCycle() },
                            SYNC_INTERVAL_MS, SYNC_INTERVAL_MS, TimeUnit.MILLISECONDS)
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

    // Brick C Task 2: was the bare AUTH heartbeat (dial + KotlinHandshake.run).
    // Now drives the full content-sync transport via SyncRunner.runSync, which
    // owns dial -> AUTH -> identity pin -> store-seed -> enc-key prep -> sync ->
    // mark-published, under the process-wide sync mutex shared with the
    // foreground module's syncNow. SyncRunner NEVER decrypts (background
    // stores encrypted only; decrypt-on-read happens in the foreground), so
    // no decrypt/content-key/feed-cache work is introduced here.
    private fun syncCycle() {
        val start = System.currentTimeMillis()
        val beat = try {
            if (!TorEngine.isUp) throw IllegalStateException("tor not up")
            val fx = fixture()
            val o = SyncRunner.runSync(this, fx)     // default no-op progress; NEVER decrypts
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
        // which can throw on-device. An uncaught throwable in a
        // scheduleAtFixedRate task SILENTLY CANCELS all future syncs, so this
        // MUST be guarded -- a transient notify/prefs hiccup must not
        // permanently kill the sync cadence. syncCycle() therefore never
        // throws to the executor.
        runCatching { recordAndBroadcast(beat) }
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
