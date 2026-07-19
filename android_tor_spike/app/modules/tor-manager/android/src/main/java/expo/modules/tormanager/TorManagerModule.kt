package expo.modules.tormanager

import android.util.Base64
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private const val SOCKS_PORT = 39050
private const val CONTROL_PORT = 39051
private const val BOOTSTRAP_TIMEOUT_MS = 300_000L

class TorManagerModule : Module() {
    @Volatile private var torThread: Thread? = null
    @Volatile private var torExitCode: Int? = null
    private val conns = ConcurrentHashMap<Int, Socket>()
    private val nextConn = AtomicInteger(1)

    // recv/send/dial block on real socket I/O and must NOT run on
    // expo-modules-core's default AsyncFunction queue: Queues.DEFAULT is a
    // single HandlerThread (AppContext.modulesQueue, "expo.modules.AsyncFunctionQueue"),
    // so a parked recv (readExact waiting on the node's reply) would
    // serialize behind a concurrently-queued send on that one thread and
    // deadlock the accepted-path handshake (grace-read-then-write). Dispatchers.IO
    // is a multi-threaded pool, so overlapping calls actually run concurrently.
    // SupervisorJob so one call's exception (e.g. "no conn $id") can't cancel
    // other in-flight recv/send/dial calls sharing this scope.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // tor state (guards/consensus) stays app-internal; the LOG goes to the
    // external files dir so `adb pull` reaches it without run-as.
    private fun dataDir(): File =
        File(appContext.reactContext!!.filesDir, "tordata").apply { mkdirs() }
    private fun externalDir(): File =
        appContext.reactContext!!.getExternalFilesDir(null)!!

    override fun definition() = ModuleDefinition {
        Name("TorManager")

        Constants(
            "fixtureDir" to (appContext.reactContext?.getExternalFilesDir(null)?.absolutePath ?: "")
        )

        Events("torProgress")

        AsyncFunction("bootstrap") { promise: Promise ->
            if (torThread?.isAlive == true) {
                promise.resolve(SOCKS_PORT)
                return@AsyncFunction
            }
            val dir = dataDir()
            val logFile = File(externalDir(), "tor.log")
            val args = arrayOf(
                "tor",
                "--SocksPort", "127.0.0.1:$SOCKS_PORT",
                "--ControlPort", "127.0.0.1:$CONTROL_PORT",
                "--CookieAuthentication", "1",
                "--DataDirectory", dir.absolutePath,
                "--Log", "notice file ${logFile.absolutePath}",
            )
            torThread = thread(name = "tor-main") { torExitCode = TorRunner.nativeRunTor(args) }
            thread(name = "tor-bootstrap-watch") {
                val ctl = ControlPort(CONTROL_PORT, File(dir, "control_auth_cookie"))
                val deadline = System.currentTimeMillis() + BOOTSTRAP_TIMEOUT_MS
                var last = -1
                while (System.currentTimeMillis() < deadline) {
                    if (torThread?.isAlive != true) {
                        val code = torExitCode
                        val detail = when (code) {
                            -100 -> "dlopen(libtor.so) failed"
                            -101 -> "tor_api symbol missing"
                            -102 -> "tor_main_configuration_set_command_line failed"
                            null -> "no exit code captured"
                            else -> "tor exited with code $code"
                        }
                        promise.reject("TOR_DIED", "tor thread exited during bootstrap: $detail (see tor.log if present)", null)
                        return@thread
                    }
                    val p = ctl.bootstrapProgress()
                    if (p != null && p != last) {
                        last = p
                        sendEvent("torProgress", mapOf("progress" to p))
                    }
                    if (p == 100) {
                        promise.resolve(SOCKS_PORT)
                        return@thread
                    }
                    Thread.sleep(1000)
                }
                promise.reject("TOR_TIMEOUT", "bootstrap did not reach 100% in 300s", null)
            }
        }

        Function("socksPort") { SOCKS_PORT }

        AsyncFunction("dial") { host: String, port: Int ->
            val s = socksDial(SOCKS_PORT, host, port)
            val id = nextConn.getAndIncrement()
            conns[id] = s
            id
        }.runOnQueue(ioScope)

        AsyncFunction("send") { id: Int, b64: String ->
            val s = conns[id] ?: throw IllegalArgumentException("no conn $id")
            // Must return Unit, NOT the OutputStream: Expo marshals an
            // AsyncFunction's last expression back to JS, and it cannot
            // serialize java.net.SocketOutputStream (fails at runtime with
            // "Unknown type"). apply{} would return the stream; keep the
            // final expression a Unit-returning call.
            val out = s.getOutputStream()
            out.write(Base64.decode(b64, Base64.NO_WRAP))
            out.flush()
        }.runOnQueue(ioScope)

        AsyncFunction("recv") { id: Int, n: Int ->
            val s = conns[id] ?: throw IllegalArgumentException("no conn $id")
            Base64.encodeToString(s.getInputStream().readExact(n), Base64.NO_WRAP)
        }.runOnQueue(ioScope)

        Function("closeConn") { id: Int ->
            conns.remove(id)?.close()
        }

        AsyncFunction("suspendTor") {
            ControlPort(CONTROL_PORT, File(dataDir(), "control_auth_cookie")).signalShutdown()
            torThread?.join(10_000)
            conns.values.forEach { runCatching { it.close() } }
            conns.clear()
        }
    }
}
