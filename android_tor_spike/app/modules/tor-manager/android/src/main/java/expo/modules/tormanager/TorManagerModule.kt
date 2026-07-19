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

private const val SOCKS_PORT = 39050
private const val CONTROL_PORT = 39051
private const val BOOTSTRAP_TIMEOUT_MS = 300_000L

class TorManagerModule : Module() {
    private var torThread: Thread? = null
    private val conns = ConcurrentHashMap<Int, Socket>()
    private val nextConn = AtomicInteger(1)

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
            torThread = thread(name = "tor-main") { TorRunner.nativeRunTor(args) }
            thread(name = "tor-bootstrap-watch") {
                val ctl = ControlPort(CONTROL_PORT, File(dir, "control_auth_cookie"))
                val deadline = System.currentTimeMillis() + BOOTSTRAP_TIMEOUT_MS
                var last = -1
                while (System.currentTimeMillis() < deadline) {
                    if (torThread?.isAlive != true) {
                        promise.reject("TOR_DIED", "tor thread exited during bootstrap (see tor.log)", null)
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
        }

        AsyncFunction("send") { id: Int, b64: String ->
            val s = conns[id] ?: throw IllegalArgumentException("no conn $id")
            s.getOutputStream().apply {
                write(Base64.decode(b64, Base64.NO_WRAP))
                flush()
            }
        }

        AsyncFunction("recv") { id: Int, n: Int ->
            val s = conns[id] ?: throw IllegalArgumentException("no conn $id")
            Base64.encodeToString(s.getInputStream().readExact(n), Base64.NO_WRAP)
        }

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
