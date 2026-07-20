package expo.modules.tormanager

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL

class MediaServerTest {
    private var srv: MediaServer? = null
    @After fun tearDown() { srv?.stop() }

    private val payload = ByteArray(5000) { (it % 251).toByte() }   // deterministic body
    private fun server(): MediaServer {
        val s = MediaServer { msgId, hash -> if (msgId == "m1" && hash == "h1") payload else null }
        s.start(); srv = s; return s
    }
    private fun get(url: String, range: String? = null): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        if (range != null) c.setRequestProperty("Range", range)
        return c
    }

    @Test fun rightTokenFullBodyIs200AndExactBytes() {
        val s = server()
        val c = get(s.urlFor("m1", "h1"))
        assertEquals(200, c.responseCode)
        val got = c.inputStream.readBytes()
        assertArrayEquals(payload, got)
    }

    @Test fun rangeRequestIs206AndCorrectSlice() {
        val s = server()
        val c = get(s.urlFor("m1", "h1"), range = "bytes=100-199")
        assertEquals(206, c.responseCode)
        assertEquals("bytes 100-199/5000", c.getHeaderField("Content-Range"))
        val got = c.inputStream.readBytes()
        assertArrayEquals(payload.copyOfRange(100, 200), got)
    }

    @Test fun wrongTokenIs403() {
        val s = server()
        val bad = s.urlFor("m1", "h1").replace(s.token, "deadbeef")
        assertEquals(403, get(bad).responseCode)
    }

    @Test fun unknownResourceIs404() {
        val s = server()
        val c = get("http://127.0.0.1:" + portOf(s) + "/media/" + s.token + "/m1/nope")
        assertEquals(404, c.responseCode)
    }

    @Test fun rangeEndPastEofClampsTo206WithLastBytes() {
        // RFC 7233: an end past EOF is clamped to the last byte, not rejected -- a
        // 416 here would break player seek/tail playback near the end of the file.
        val s = server()
        val c = get(s.urlFor("m1", "h1"), range = "bytes=4990-6000")
        assertEquals(206, c.responseCode)
        assertEquals("bytes 4990-4999/5000", c.getHeaderField("Content-Range"))
        val got = c.inputStream.readBytes()
        assertArrayEquals(payload.copyOfRange(4990, 5000), got)
    }

    @Test fun rangeStartAtOrPastEofIs416() {
        val s = server()
        val c = get(s.urlFor("m1", "h1"), range = "bytes=6000-7000")
        assertEquals(416, c.responseCode)
    }

    @Test fun suffixRangeIs206WithLastNBytes() {
        val s = server()
        val c = get(s.urlFor("m1", "h1"), range = "bytes=-100")
        assertEquals(206, c.responseCode)
        assertEquals("bytes 4900-4999/5000", c.getHeaderField("Content-Range"))
        val got = c.inputStream.readBytes()
        assertArrayEquals(payload.copyOfRange(4900, 5000), got)
    }

    @Test fun garbageRangeIsIgnoredServesFullBody200() {
        val s = server()
        val c = get(s.urlFor("m1", "h1"), range = "bytes=abc")
        assertEquals(200, c.responseCode)
        val got = c.inputStream.readBytes()
        assertArrayEquals(payload, got)
    }

    @Test fun bindsLoopbackOnly() {
        val s = server()
        // The server must NOT be reachable on a non-loopback local address.
        val nonLoopback = InetAddress.getAllByName(InetAddress.getLocalHost().hostName)
            .firstOrNull { !it.isLoopbackAddress }
        // If the host has a routable address, a direct socket to it on the port must fail.
        if (nonLoopback != null) {
            val port = portOf(s)
            var refused = false
            try { Socket(nonLoopback, port).close() } catch (e: Exception) { refused = true }
            assertTrue("server must bind 127.0.0.1 only, not $nonLoopback", refused)
        }
    }

    // helper: parse the port out of a urlFor() result
    private fun portOf(s: MediaServer): Int =
        Regex(":(\\d+)/").find(s.urlFor("x", "y"))!!.groupValues[1].toInt()
}
