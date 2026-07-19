package expo.modules.tormanager

import android.os.Build
import android.util.Base64
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

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

        Events("torProgress", "nodeBeat", "nodeState", "nodeSync")

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

        // -- Brick B.1: foreground-triggered content sync --
        AsyncFunction("syncNow") {
            fun emit(ok: Boolean, messages: Int, blobs: Int, identities: Int, reason: String?) {
                sendEvent("nodeSync", mapOf(
                    "ok" to ok, "messages" to messages, "blobs" to blobs,
                    "identities" to identities, "reason" to reason))
            }
            val ctx = appContext.reactContext
            if (ctx == null) { emit(false, 0, 0, 0, "no context"); return@AsyncFunction Unit }
            if (!TorEngine.isUp) {
                emit(false, 0, 0, 0, "tor not up - start the node first")
                return@AsyncFunction Unit
            }
            try {
                val fx = KotlinHandshake.parseFixture(
                    File(TorEngine.externalDir(), "spike_phone_fixture.json").readText())
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
                    emit(false, 0, 0, 0, "auth: ${e.message}")
                    return@AsyncFunction Unit
                }

                // authOnlyOverStream verifies the peer cert is validly signed but does
                // NOT pin it to our home identity (only runOverStream's accepted branch
                // does that). Pin here so a wrong onion address can never sync us into
                // a stranger's node.
                if (peerCert.identity_pub != fx.cert.identity_pub) {
                    stream.close()
                    emit(false, 0, 0, 0, "auth: node identity is not our home identity")
                    return@AsyncFunction Unit
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
                    emit(false, 0, 0, 0, "store: ${e.message}")
                    return@AsyncFunction Unit
                }
                // (own identity is seeded above, inside the try, so ingest's is_known
                // gate admits its own-identity messages; the HAVE phase adds the node's
                // known identities.)

                when (val r = KotlinSync.run(stream, store, fx.device_pub)) {
                    is SyncResult.Ok -> emit(true, r.messages, r.blobs, r.identities, null)
                    is SyncResult.SelfRevoked -> emit(false, 0, 0, 0, "self-revoked")
                    is SyncResult.Failed -> emit(false, 0, 0, 0, "${r.stage}: ${r.reason}")
                }
            } catch (e: Exception) {
                emit(false, 0, 0, 0, "io: ${e.message}")
            }
        }.runOnQueue(ioScope)

        AsyncFunction("getSyncStats") {
            val ctx = appContext.reactContext
                ?: return@AsyncFunction mapOf("messages" to 0, "blobs" to 0, "identities" to 0)
            val st = SqliteSyncStore(ctx).stats()
            mapOf("messages" to st.messages, "blobs" to st.blobs, "identities" to st.identities)
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
