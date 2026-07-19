package expo.modules.tormanager

import java.io.File
import java.net.Socket

/** Minimal tor control-port client: cookie AUTHENTICATE + one GETINFO.
 *  Mirrors the desktop's control-port bootstrap watching (hearth/tor.py),
 *  which is why the spike watches the control port rather than stdout. */
class ControlPort(private val port: Int, private val cookieFile: File) {

    /** Current bootstrap percentage, or null while the control port /
     *  cookie are not up yet. */
    fun bootstrapProgress(): Int? = try {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5_000
            val out = s.getOutputStream()
            val reader = s.getInputStream().bufferedReader()
            val cookie = cookieFile.readBytes().joinToString("") { "%02x".format(it) }
            out.write("AUTHENTICATE $cookie\r\n".toByteArray()); out.flush()
            if (!(reader.readLine() ?: "").startsWith("250")) return null
            out.write("GETINFO status/bootstrap-phase\r\n".toByteArray()); out.flush()
            var progress: Int? = null
            while (true) {
                val line = reader.readLine() ?: break
                Regex("PROGRESS=(\\d+)").find(line)?.let {
                    progress = it.groupValues[1].toInt()
                }
                if (line.startsWith("250 ")) break
            }
            progress
        }
    } catch (_: Exception) {
        null
    }

    fun signalShutdown() = try {
        Socket("127.0.0.1", port).use { s ->
            val cookie = cookieFile.readBytes().joinToString("") { "%02x".format(it) }
            s.getOutputStream().apply {
                write("AUTHENTICATE $cookie\r\nSIGNAL SHUTDOWN\r\n".toByteArray())
                flush()
            }
        }
    } catch (_: Exception) { }
}
