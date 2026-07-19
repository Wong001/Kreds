package expo.modules.tormanager

import android.content.Context
import java.io.File
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

const val SOCKS_PORT = 39050
const val CONTROL_PORT = 39051
private const val BOOTSTRAP_TIMEOUT_MS = 300_000L

/** Process-global Tor owner. Extracted from TorManagerModule so Tor
 *  survives Activity destruction and is shared by the Module (foreground
 *  JS) and TorNodeService (background heartbeat). */
object TorEngine {
    @Volatile private var appContext: Context? = null
    @Volatile private var torThread: Thread? = null
    @Volatile private var torExitCode: Int? = null
    private val conns = ConcurrentHashMap<Int, Socket>()
    private val nextConn = AtomicInteger(1)

    val isUp: Boolean get() = torThread?.isAlive == true

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun ctx(): Context = appContext ?: error("TorEngine.init not called")
    private fun dataDir(): File = File(ctx().filesDir, "tordata").apply { mkdirs() }
    fun externalDir(): File = ctx().getExternalFilesDir(null)!!

    /** Idempotent: if tor is already up, calls onDone immediately. */
    fun bootstrap(
        onProgress: (Int) -> Unit,
        onDone: (Int) -> Unit,
        onError: (String, String) -> Unit,
    ) {
        if (isUp) { onDone(SOCKS_PORT); return }
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
                    val detail = when (val code = torExitCode) {
                        -100 -> "dlopen(libtor.so) failed"
                        -101 -> "tor_api symbol missing"
                        -102 -> "tor_main_configuration_set_command_line failed"
                        null -> "no exit code captured"
                        else -> "tor exited with code $code"
                    }
                    onError("TOR_DIED", "tor thread exited during bootstrap: $detail (see tor.log if present)")
                    return@thread
                }
                val p = ctl.bootstrapProgress()
                if (p != null && p != last) { last = p; onProgress(p) }
                if (p == 100) { onDone(SOCKS_PORT); return@thread }
                Thread.sleep(1000)
            }
            onError("TOR_TIMEOUT", "bootstrap did not reach 100% in 300s")
        }
    }

    fun dial(host: String, port: Int): Int {
        val s = socksDial(SOCKS_PORT, host, port)
        val id = nextConn.getAndIncrement()
        conns[id] = s
        return id
    }

    fun send(id: Int, data: ByteArray) {
        val s = conns[id] ?: throw IllegalArgumentException("no conn $id")
        val out = s.getOutputStream()
        out.write(data)
        out.flush()
    }

    fun recv(id: Int, n: Int): ByteArray {
        val s = conns[id] ?: throw IllegalArgumentException("no conn $id")
        return s.getInputStream().readExact(n)
    }

    fun close(id: Int) { conns.remove(id)?.close() }

    fun suspend() {
        ControlPort(CONTROL_PORT, File(dataDir(), "control_auth_cookie")).signalShutdown()
        torThread?.join(10_000)
        conns.values.forEach { runCatching { it.close() } }
        conns.clear()
        torThread = null
    }
}
