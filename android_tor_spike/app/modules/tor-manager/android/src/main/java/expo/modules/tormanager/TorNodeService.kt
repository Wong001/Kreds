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
import java.io.File
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

        fun start(ctx: Context) {
            val i = Intent(ctx, TorNodeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, TorNodeService::class.java).setAction(ACTION_STOP))
        fun beatNow(ctx: Context) = ctx.startService(Intent(ctx, TorNodeService::class.java).setAction(ACTION_BEAT_NOW))
    }

    @Volatile private var scheduler: ScheduledExecutorService? = null
    @Volatile private var syncs = 0
    @Volatile private var lastLine = "starting"

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

    private fun fixture(): KotlinHandshake.Fixture {
        val f = File(TorEngine.externalDir(), "spike_phone_fixture.json")
        return KotlinHandshake.parseFixture(f.readText())
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
        runCatching { TorEngine.suspend() }
        broadcastState("stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { runCatching { TorEngine.suspend() }; super.onDestroy() }

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
