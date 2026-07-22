package expo.modules.tormanager

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/** One HTTP response: status + headers (Content-Type etc.) + full body bytes.
 *  The server applies Range slicing to `body` itself, so a provider always
 *  returns the COMPLETE body and never has to reason about ranges. */
data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)

/** Loopback web server for the WebView shell. Generalizes MediaServer:
 *   - binds 127.0.0.1 ONLY, OS-assigned ephemeral port, backlog 50
 *   - one daemon accept-loop thread; each socket on its own daemon thread
 *   - 5s soTimeout, 50-header cap, ISO-8859-1 raw HTTP write, Range support
 *   - a 32-byte SecureRandom hex token minted at construction
 *   - cookie/query token gate BEFORE any provider call
 *   - two injected providers (assets, api), so it is fully JVM-unit-testable.
 *  Token flow: the WebView's FIRST request carries `?__t=<token>`; the server
 *  validates it and replies Set-Cookie: kreds_session=<token>. Every later
 *  same-origin request carries that cookie automatically. A request with
 *  NEITHER a valid query token NOR the cookie gets 403 before routing. */
class LocalWebServer(
    private val assets: (path: String) -> Pair<String, ByteArray>?,
    private val api: (method: String, path: String, query: String?, cookieToken: String?, queryToken: String?,
                       contentType: String?, body: ByteArray?) -> HttpResponse?,
) {
    val token: String = SecureRandom().let { r -> ByteArray(32).also { r.nextBytes(it) } }
        .joinToString("") { "%02x".format(it) }

    private var server: ServerSocket? = null
    @Volatile private var running = false
    private var port = -1

    @Synchronized fun start(): Int {
        if (running) return port
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))  // loopback ONLY
        server = s; port = s.localPort; running = true
        thread(isDaemon = true, name = "local-web-server") {
            while (running) {
                val sock = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { runCatching { handle(sock) }; runCatching { sock.close() } }
            }
        }
        return port
    }

    @Synchronized fun stop() {
        running = false
        runCatching { server?.close() }
        server = null; port = -1
    }

    /** Full URL (with the one-time query token) the WebView loads first. */
    fun rootUrl(): String = "http://127.0.0.1:$port/?__t=$token"
    fun portOrNull(): Int = port

    private fun handle(sock: Socket) {
        sock.soTimeout = 5000
        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        var cookieHeader: String? = null
        var range: String? = null
        var contentLength = 0
        var contentType: String? = null
        var headerLines = 0
        while (true) {
            if (++headerLines > 50) break
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Cookie:", true)) cookieHeader = line.substringAfter(":").trim()
            if (line.startsWith("Range:", true)) range = line.substringAfter(":").trim()
            if (line.startsWith("Content-Length:", true)) contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
            if (line.startsWith("Content-Type:", true)) contentType = line.substringAfter(":").trim()
        }
        // The body (if any) MUST be read from this SAME BufferedReader, not
        // sock.getInputStream() directly -- the reader may already hold
        // buffered bytes past the blank line (its own internal buffer reads
        // ahead of what readLine() consumed), so reading the raw stream here
        // could miss them entirely. The reader was constructed over
        // ISO-8859-1, a 1:1 byte<->char map for 0-255, so reading exactly
        // `contentLength` CHARS and re-encoding via ISO-8859-1 recovers the
        // original bytes losslessly (including binary file-part bytes).
        val body: ByteArray? = if (contentLength > 0) {
            val cbuf = CharArray(contentLength)
            var got = 0
            while (got < contentLength) {
                val r = reader.read(cbuf, got, contentLength - got)
                if (r < 0) break
                got += r
            }
            String(cbuf, 0, got).toByteArray(Charsets.ISO_8859_1)
        } else null
        val out = sock.getOutputStream()
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0) ?: return respond(out, 400, "bad request")
        val rawPath = parts.getOrNull(1) ?: return respond(out, 400, "bad request")

        val qIdx = rawPath.indexOf('?')
        val path = if (qIdx >= 0) rawPath.substring(0, qIdx) else rawPath
        val query = if (qIdx >= 0) rawPath.substring(qIdx + 1) else null

        val queryToken = query?.split("&")
            ?.firstOrNull { it.startsWith("__t=") }?.substringAfter("=")
        val cookieToken = cookieHeader?.split(";")
            ?.map { it.trim() }?.firstOrNull { it.startsWith("kreds_session=") }?.substringAfter("=")

        // Token gate BEFORE any provider/store access.
        if (queryToken != token && cookieToken != token) return respond(out, 403, "forbidden")
        val setCookie = queryToken == token   // stamp the cookie on the initial navigation

        if (path.startsWith("/api/")) {
            val resp = api(method, path, query, cookieToken, queryToken, contentType, body)
                ?: return respond(out, 404, "not found")
            writeResponse(out, resp, range, setCookie)
            return
        }
        // static: "/" -> index.html; else the path minus its leading slash.
        val key = if (path == "/" || path.isEmpty()) "index.html" else path.trimStart('/')
        val asset = assets(key) ?: return respond(out, 404, "not found")
        writeResponse(out, HttpResponse(200, mapOf("Content-Type" to asset.first), asset.second), range, setCookie)
    }

    private fun writeResponse(out: OutputStream, resp: HttpResponse, range: String?, setCookie: Boolean) {
        val bytes = resp.body
        val total = bytes.size
        var start = 0; var end = total - 1; var code = resp.status; var status = statusText(code)
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").split("-")
            val a = spec.getOrNull(0)?.toIntOrNull()
            val b = spec.getOrNull(1)?.toIntOrNull()
            var parsed = false
            if (a != null) { start = a; end = b ?: (total - 1); parsed = true }
            else if (b != null) { start = maxOf(0, total - b); end = total - 1; parsed = true }
            if (parsed) {
                if (start < 0 || start >= total || start > end) return respond(out, 416, "range not satisfiable")
                if (end >= total) end = total - 1
                code = 206; status = "Partial Content"
            }
        }
        val len = end - start + 1
        val ctype = resp.headers["Content-Type"] ?: "application/octet-stream"
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $status\r\n")
        sb.append("Content-Type: $ctype\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Content-Length: $len\r\n")
        if (code == 206) sb.append("Content-Range: bytes $start-$end/$total\r\n")
        if (setCookie) sb.append("Set-Cookie: kreds_session=$token; Path=/; HttpOnly; SameSite=Strict\r\n")
        if (ctype.startsWith("text/html"))
            sb.append("Content-Security-Policy: default-src 'self'; img-src 'self' data: blob:; " +
                "media-src 'self' blob:; style-src 'self' 'unsafe-inline'; script-src 'self'; " +
                "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'\r\n")
        for ((k, v) in resp.headers) if (!k.equals("Content-Type", true)) sb.append("$k: $v\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(bytes, start, len)
        out.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 403 -> "Forbidden"
        404 -> "Not Found"; 416 -> "Range Not Satisfiable"; else -> "OK"
    }

    private fun respond(out: OutputStream, code: Int, msg: String) {
        val body = msg.toByteArray()
        out.write(("HTTP/1.1 $code $msg\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
            .toByteArray(Charsets.ISO_8859_1))
        out.write(body); out.flush()
    }
}
