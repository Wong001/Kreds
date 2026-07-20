package expo.modules.tormanager

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/** Foreground-only localhost server that streams DECRYPTED media bytes to the
 *  platform video player without ever writing plaintext to disk. Security:
 *  binds 127.0.0.1 ONLY, random port, a per-session token in the path (403 on
 *  mismatch), and it is NOT a file server -- it only maps (msgId, hash) to the
 *  injected `resolve`. Localhost is reachable by other apps on the device, so
 *  the unguessable token is what keeps them out. */
class MediaServer(private val resolve: (msgId: String, hash: String) -> ByteArray?) {
    val token: String = SecureRandom().let { r -> ByteArray(32).also { r.nextBytes(it) } }
        .joinToString("") { "%02x".format(it) }

    private var server: ServerSocket? = null
    @Volatile private var running = false
    private var port = -1

    @Synchronized fun start(): Int {
        if (running) return port
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))  // loopback ONLY
        server = s; port = s.localPort; running = true
        thread(isDaemon = true, name = "media-server") {
            while (running) {
                val sock = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { runCatching { handle(sock) }; runCatching { sock.close() } }
            }
        }
        return port
    }

    fun urlFor(msgId: String, hash: String) =
        "http://127.0.0.1:$port/media/$token/$msgId/$hash"

    @Synchronized fun stop() {
        running = false
        runCatching { server?.close() }
        server = null; port = -1
    }

    private fun handle(sock: Socket) {
        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        var range: String? = null
        while (true) {                       // consume headers
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", true)) range = line.substringAfter(":").trim()
        }
        val out = sock.getOutputStream()
        // request line: "GET /media/<token>/<msgId>/<hash> HTTP/1.1"
        val path = requestLine.split(" ").getOrNull(1) ?: return respond(out, 400, "bad request")
        val parts = path.trimStart('/').split("/")
        // ["media", token, msgId, hash]
        if (parts.size != 4 || parts[0] != "media") return respond(out, 404, "not found")
        if (parts[1] != token) return respond(out, 403, "forbidden")   // token gate
        val bytes = resolve(parts[2], parts[3]) ?: return respond(out, 404, "not found")
        writeMedia(out, bytes, range)
    }

    private fun writeMedia(out: OutputStream, bytes: ByteArray, range: String?) {
        val total = bytes.size
        var start = 0; var end = total - 1; var code = 200; var status = "OK"
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").split("-")
            val a = spec.getOrNull(0)?.toIntOrNull()
            val b = spec.getOrNull(1)?.toIntOrNull()
            if (a != null) { start = a; end = b ?: (total - 1) }
            else if (b != null) { start = maxOf(0, total - b); end = total - 1 }   // suffix range
            if (start < 0 || start >= total || end >= total || start > end)
                return respond(out, 416, "range not satisfiable")
            code = 206; status = "Partial Content"
        }
        val len = end - start + 1
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $status\r\n")
        sb.append("Content-Type: video/mp4\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Content-Length: $len\r\n")
        if (code == 206) sb.append("Content-Range: bytes $start-$end/$total\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(bytes, start, len)
        out.flush()
    }

    private fun respond(out: OutputStream, code: Int, msg: String) {
        val body = msg.toByteArray()
        out.write(("HTTP/1.1 $code $msg\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
            .toByteArray(Charsets.ISO_8859_1))
        out.write(body); out.flush()
    }
}
