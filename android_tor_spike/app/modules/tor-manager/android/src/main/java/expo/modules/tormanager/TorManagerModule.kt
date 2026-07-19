package expo.modules.tormanager

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

    override fun definition() = ModuleDefinition {
        Name("TorManager")

        Constants(
            "fixtureDir" to (appContext.reactContext?.getExternalFilesDir(null)?.absolutePath ?: "")
        )

        Events("torProgress")

        OnCreate {
            appContext.reactContext?.let { TorEngine.init(it) }
        }

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
            TorEngine.send(id, Base64.decode(b64, Base64.NO_WRAP))
        }.runOnQueue(ioScope)

        AsyncFunction("recv") { id: Int, n: Int ->
            Base64.encodeToString(TorEngine.recv(id, n), Base64.NO_WRAP)
        }.runOnQueue(ioScope)

        Function("closeConn") { id: Int -> TorEngine.close(id) }

        AsyncFunction("suspendTor") { TorEngine.suspend() }
    }
}
