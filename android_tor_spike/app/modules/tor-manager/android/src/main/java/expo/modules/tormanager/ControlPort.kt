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

    /** Publish an ephemeral (or republished, if [keyBlob] is given) onion
     *  service via ADD_ONION and return (serviceId, keyBlob). Ports the
     *  desktop's hearth/tor.py publish_onion (:325-345). Flags=Detach is
     *  REQUIRED: ControlPort opens one Socket per call and closes it when
     *  this function returns -- without Detach, Tor tears the onion down
     *  the instant that control connection closes. [virtualPort] is the
     *  fixed public port peers dial; [targetPort] is the local loopback
     *  listener the onion forwards onto. */
    fun addOnion(virtualPort: Int, targetPort: Int, keyBlob: String? = null): Pair<String, String?> {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5_000
            val out = s.getOutputStream()
            val reader = s.getInputStream().bufferedReader()
            val cookie = cookieFile.readBytes().joinToString("") { "%02x".format(it) }
            out.write("AUTHENTICATE $cookie\r\n".toByteArray()); out.flush()
            val authReply = reader.readLine() ?: ""
            if (!authReply.startsWith("250")) {
                throw RuntimeException("tor control auth failed: $authReply")
            }
            val keySpec = keyBlob ?: "NEW:ED25519-V3"
            out.write(
                "ADD_ONION $keySpec Flags=Detach Port=$virtualPort,127.0.0.1:$targetPort\r\n"
                    .toByteArray()
            )
            out.flush()
            val lines = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: break
                lines.add(line)
                if (line.startsWith("250 ")) break
            }
            val fields = parseControlReply(lines)
            val serviceId = fields["ServiceID"]
                ?: throw RuntimeException("ADD_ONION returned no ServiceID: " + lines.joinToString(" | "))
            // On a NEW key Tor returns PrivateKey=ED25519-V3:<blob>; on
            // republish from a saved blob it echoes nothing, so keep what
            // we sent.
            val returned = fields["PrivateKey"]
            return serviceId to (returned ?: keyBlob)
        }
    }

    companion object {
        /** Pull KEY=VALUE fields out of a Tor control 250 reply block.
         *  Lines look like "250-ServiceID=..." / "250 OK". Ports the
         *  desktop's hearth/tor.py _parse_control_reply (:122-131). */
        fun parseControlReply(lines: List<String>): Map<String, String> {
            val out = mutableMapOf<String, String>()
            for (line in lines) {
                val body = if (line.length > 4 && line[3] in "-+ ") line.substring(4) else line
                val idx = body.indexOf('=')
                if (idx >= 0) {
                    out[body.substring(0, idx).trim()] = body.substring(idx + 1).trim()
                }
            }
            return out
        }
    }
}
