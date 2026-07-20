package expo.modules.tormanager

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL

class LocalWebServerTest {
    private var srv: LocalWebServer? = null
    @After fun tearDown() { srv?.stop() }

    private val indexHtml = "<html><body>hi</body></html>".toByteArray()
    private val big = ByteArray(5000) { (it % 251).toByte() }

    private fun server(): LocalWebServer {
        val assets: (String) -> Pair<String, ByteArray>? = { p ->
            when (p) {
                "index.html" -> "text/html; charset=utf-8" to indexHtml
                "static/big.bin" -> "application/octet-stream" to big
                else -> null
            }
        }
        val api: (String, String, String?, String?, String?) -> HttpResponse? = { _, path, _, _, _ ->
            if (path == "/api/state")
                HttpResponse(200, mapOf("Content-Type" to "application/json"), "{\"ok\":true}".toByteArray())
            else null
        }
        val s = LocalWebServer(assets, api); s.start(); srv = s; return s
    }

    private fun portOf(s: LocalWebServer): Int =
        Regex(":(\\d+)/").find(s.rootUrl())!!.groupValues[1].toInt()

    private fun conn(s: LocalWebServer, path: String, cookie: String? = null, range: String? = null): HttpURLConnection {
        val c = URL("http://127.0.0.1:${portOf(s)}$path").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        if (cookie != null) c.setRequestProperty("Cookie", cookie)
        if (range != null) c.setRequestProperty("Range", range)
        return c
    }

    @Test fun initialNavWithQueryTokenServesIndexAndSetsCookie() {
        val s = server()
        val c = conn(s, "/?__t=${s.token}")
        assertEquals(200, c.responseCode)
        assertArrayEquals(indexHtml, c.inputStream.readBytes())
        val sc = c.getHeaderField("Set-Cookie")
        assertNotNull(sc)
        assertTrue(sc.contains("kreds_session=${s.token}"))
        assertTrue(sc.contains("HttpOnly"))
    }

    @Test fun htmlResponseCarriesCsp() {
        val s = server()
        val c = conn(s, "/?__t=${s.token}")
        assertEquals(200, c.responseCode)
        assertNotNull(c.getHeaderField("Content-Security-Policy"))
    }

    @Test fun staticWithCookieServes200() {
        val s = server()
        val c = conn(s, "/static/big.bin", cookie = "kreds_session=${s.token}")
        assertEquals(200, c.responseCode)
        assertArrayEquals(big, c.inputStream.readBytes())
    }

    @Test fun noTokenNoCookieIs403() {
        val s = server()
        assertEquals(403, conn(s, "/static/big.bin").responseCode)
    }

    @Test fun wrongTokenIs403() {
        val s = server()
        assertEquals(403, conn(s, "/static/big.bin", cookie = "kreds_session=deadbeef").responseCode)
    }

    @Test fun unknownStaticIs404() {
        val s = server()
        assertEquals(404, conn(s, "/static/missing.js", cookie = "kreds_session=${s.token}").responseCode)
    }

    @Test fun apiRouteDispatchesToProvider() {
        val s = server()
        val c = conn(s, "/api/state", cookie = "kreds_session=${s.token}")
        assertEquals(200, c.responseCode)
        assertEquals("{\"ok\":true}", String(c.inputStream.readBytes()))
    }

    @Test fun apiUnknownIs404() {
        val s = server()
        assertEquals(404, conn(s, "/api/nope", cookie = "kreds_session=${s.token}").responseCode)
    }

    @Test fun rangeRequestIs206Slice() {
        val s = server()
        val c = conn(s, "/static/big.bin", cookie = "kreds_session=${s.token}", range = "bytes=100-199")
        assertEquals(206, c.responseCode)
        assertEquals("bytes 100-199/5000", c.getHeaderField("Content-Range"))
        assertArrayEquals(big.copyOfRange(100, 200), c.inputStream.readBytes())
    }

    @Test fun bindsLoopbackOnly() {
        val s = server()
        val nonLoopback = InetAddress.getAllByName(InetAddress.getLocalHost().hostName)
            .firstOrNull { !it.isLoopbackAddress }
        if (nonLoopback != null) {
            var refused = false
            try { Socket(nonLoopback, portOf(s)).close() } catch (e: Exception) { refused = true }
            assertTrue("server must bind 127.0.0.1 only", refused)
        }
    }
}
