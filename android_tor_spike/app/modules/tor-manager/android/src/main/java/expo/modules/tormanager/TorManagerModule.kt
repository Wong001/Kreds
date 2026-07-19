package expo.modules.tormanager

import android.os.Build
import android.util.Base64
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TorManagerModule : Module() {
    // recv/send/dial block on socket I/O; keep them OFF the single default
    // AsyncFunction HandlerThread (would deadlock the concurrent recv+send
    // of the accepted-path probe). Dispatchers.IO is multi-threaded.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nodeReceiver: android.content.BroadcastReceiver? = null

    override fun definition() = ModuleDefinition {
        Name("TorManager")

        Constants(
            "fixtureDir" to (appContext.reactContext?.getExternalFilesDir(null)?.absolutePath ?: "")
        )

        Events("torProgress", "nodeBeat", "nodeState")

        OnCreate {
            val ctx = appContext.reactContext ?: return@OnCreate
            TorEngine.init(ctx)
            val filter = android.content.IntentFilter().apply {
                addAction(TorNodeService.BROADCAST_BEAT); addAction(TorNodeService.BROADCAST_STATE)
            }
            val rx = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    when (i?.action) {
                        TorNodeService.BROADCAST_BEAT -> sendEvent("nodeBeat", mapOf(
                            "ts" to i.getLongExtra("ts", 0), "ok" to i.getBooleanExtra("ok", false),
                            "latencyMs" to i.getLongExtra("latencyMs", 0), "reason" to i.getStringExtra("reason")))
                        TorNodeService.BROADCAST_STATE -> sendEvent("nodeState",
                            mapOf("state" to i.getStringExtra("state")))
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33)
                ctx.registerReceiver(rx, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            else ctx.registerReceiver(rx, filter)
            nodeReceiver = rx
        }
        OnDestroy { appContext.reactContext?.let { c -> nodeReceiver?.let { c.unregisterReceiver(it) } } }

        AsyncFunction("bootstrap") { promise: Promise ->
            TorEngine.bootstrap(
                onProgress = { p -> sendEvent("torProgress", mapOf("progress" to p)) },
                onDone = { port -> promise.resolve(port) },
                onError = { code, msg -> promise.reject(code, msg, null) },
            )
        }

        Function("socksPort") { SOCKS_PORT }

        AsyncFunction("dial") { host: String, port: Int -> TorEngine.dial(host, port) }.runOnQueue(ioScope)

        AsyncFunction("send") { id: Int, b64: String ->
            // Last expression must be Unit: Expo marshals it back to JS and
            // cannot serialize java.net.SocketOutputStream ("Unknown type",
            // an on-device failure the spike hit). TorEngine.send is a
            // Unit-returning fun, so this is safe -- do not inline it into an
            // apply{} that returns the stream.
            TorEngine.send(id, Base64.decode(b64, Base64.NO_WRAP))
        }.runOnQueue(ioScope)

        AsyncFunction("recv") { id: Int, n: Int ->
            Base64.encodeToString(TorEngine.recv(id, n), Base64.NO_WRAP)
        }.runOnQueue(ioScope)

        Function("closeConn") { id: Int -> TorEngine.close(id) }

        AsyncFunction("suspendTor") { TorEngine.suspend() }

        // -- Brick A: background node control --
        Function("startNode") { appContext.reactContext?.let { TorNodeService.start(it) } }
        Function("stopNode") { appContext.reactContext?.let { TorNodeService.stop(it) } }
        Function("beatNow") { appContext.reactContext?.let { TorNodeService.beatNow(it) } }

        AsyncFunction("getHistory") {
            val ctx = appContext.reactContext ?: return@AsyncFunction emptyList<Map<String, Any?>>()
            HeartbeatStore.history(ctx).map {
                mapOf("ts" to it.ts, "ok" to it.ok, "latencyMs" to it.latencyMs, "reason" to it.reason)
            }
        }

        Function("isBatteryExempt") {
            val ctx = appContext.reactContext ?: return@Function false
            val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }

        Function("requestBatteryExemption") {
            val ctx = appContext.reactContext ?: return@Function Unit
            val i = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${ctx.packageName}"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }
}
