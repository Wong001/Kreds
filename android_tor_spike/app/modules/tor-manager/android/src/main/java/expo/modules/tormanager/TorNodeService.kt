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
        private const val HEARTBEAT_INTERVAL_MS = 300_000L    // N = 5 min
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

    private var scheduler: ScheduledExecutorService? = null
    @Volatile private var beats = 0
    @Volatile private var lastLine = "starting"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_BEAT_NOW -> { scheduler?.execute { heartbeat() }; return START_STICKY }
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
        // Bootstrap once on the scheduler thread; on success, begin the beat cadence.
        scheduler!!.execute {
            TorEngine.bootstrap(
                onProgress = { p -> updateNotification("Tor bootstrap $p%") },
                onDone = {
                    broadcastState("up")
                    heartbeat()   // immediate first beat
                    scheduler!!.scheduleAtFixedRate({ heartbeat() },
                        HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
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

    private fun heartbeat() {
        val start = System.currentTimeMillis()
        val beat = try {
            if (!TorEngine.isUp) throw IllegalStateException("tor not up")
            val fx = fixture()
            val (host, port) = KotlinHandshake.splitAddr(fx.onion_addr)
            val conn = TorEngine.dial(host, port)
            when (val r = KotlinHandshake.run(conn, fx)) {
                is KotlinHandshake.HandshakeResult.Accepted ->
                    Beat(start, true, System.currentTimeMillis() - start, null)
                is KotlinHandshake.HandshakeResult.Refused ->
                    Beat(start, false, System.currentTimeMillis() - start, "refused")
                is KotlinHandshake.HandshakeResult.Failed ->
                    Beat(start, false, System.currentTimeMillis() - start, "${r.stage}: ${r.reason}")
            }
        } catch (e: Exception) {
            Beat(start, false, System.currentTimeMillis() - start, "io: ${e.message}")
        }
        recordAndBroadcast(beat)
    }

    private fun recordAndBroadcast(beat: Beat) {
        HeartbeatStore.record(this, beat)
        beats++
        val hhmm = SimpleDateFormat("HH:mm", Locale.US).format(Date(beat.ts))
        lastLine = if (beat.ok) "last beat $hhmm OK, ${beat.latencyMs}ms - $beats beats"
                   else "last beat $hhmm FAIL (${beat.reason}) - $beats beats"
        updateNotification(lastLine)
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
