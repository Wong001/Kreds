package expo.modules.tormanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** ControlPort.addOnion against a scripted fake control-port socket that
 *  speaks Tor's 250 reply grammar -- the Kotlin-side counterpart to the
 *  desktop's hearth/tor.py publish_onion (:325-345) / _parse_control_reply
 *  (:122-131), which this must interop with byte-for-byte on the wire. */
class ControlPortTest {

    /** Minimal scripted control-port server: accepts one connection, reads
     *  (and discards) the AUTHENTICATE line and always replies "250 OK",
     *  captures the next command line verbatim, then writes back [reply]. */
    private class FakeControlPort(private val reply: String) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort
        val receivedCommand = AtomicReference<String?>(null)
        private val handled = CountDownLatch(1)

        init {
            Thread {
                try {
                    server.accept().use { sock ->
                        val out = sock.getOutputStream()
                        val reader = sock.getInputStream().bufferedReader()
                        reader.readLine() // AUTHENTICATE <hex>
                        out.write("250 OK\r\n".toByteArray()); out.flush()
                        receivedCommand.set(reader.readLine())
                        out.write(reply.toByteArray()); out.flush()
                    }
                } catch (_: Exception) {
                    // server closed out from under the accept()/read -- fine, the
                    // test that doesn't care about receivedCommand already got
                    // its (thrown or returned) result via ControlPort directly.
                } finally {
                    handled.countDown()
                }
            }.apply { isDaemon = true; start() }
        }

        /** Block until the scripted exchange above has run once. */
        fun awaitHandled() { assertTrue(handled.await(5, TimeUnit.SECONDS)) }

        override fun close() { server.close() }
    }

    private fun cookieFile(): File {
        val f = File.createTempFile("control_auth_cookie", null)
        f.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        f.deleteOnExit()
        return f
    }

    @Test
    fun addOnionNewReplyReturnsServiceIdAndPrivateKeyBlob() {
        FakeControlPort("250-ServiceID=abc\r\n250-PrivateKey=ED25519-V3:KEY\r\n250 OK\r\n").use { fake ->
            val ctl = ControlPort(fake.port, cookieFile())
            val (serviceId, keyBlob) = ctl.addOnion(9997, 39099)
            assertEquals("abc", serviceId)
            assertEquals("ED25519-V3:KEY", keyBlob)
        }
    }

    @Test
    fun addOnionRepublishReplyKeepsThePassedKeyBlob() {
        FakeControlPort("250-ServiceID=xyz\r\n250 OK\r\n").use { fake ->
            val ctl = ControlPort(fake.port, cookieFile())
            val passedBlob = "ED25519-V3:PASSEDBLOB"
            val (serviceId, keyBlob) = ctl.addOnion(9997, 39100, passedBlob)
            assertEquals("xyz", serviceId)
            assertEquals(passedBlob, keyBlob)
        }
    }

    @Test
    fun addOnionWithNoServiceIdThrows() {
        FakeControlPort("250-SomeOtherField=1\r\n250 OK\r\n").use { fake ->
            val ctl = ControlPort(fake.port, cookieFile())
            try {
                ctl.addOnion(9997, 39101)
                fail("expected RuntimeException when reply has no ServiceID")
            } catch (e: RuntimeException) {
                assertTrue(e.message?.contains("ServiceID") == true)
            }
        }
    }

    @Test
    fun addOnionCommandContainsFlagsDetachAndPortMapping() {
        FakeControlPort("250-ServiceID=abc\r\n250 OK\r\n").use { fake ->
            val ctl = ControlPort(fake.port, cookieFile())
            ctl.addOnion(9997, 39102)
            fake.awaitHandled()
            val cmd = fake.receivedCommand.get() ?: ""
            assertTrue("expected Flags=Detach in: $cmd", cmd.contains("Flags=Detach"))
            assertTrue("expected Port=9997,127.0.0.1:39102 in: $cmd", cmd.contains("Port=9997,127.0.0.1:39102"))
        }
    }

    @Test
    fun parseControlReplyMirrorsTorPyParseControlReply() {
        val lines = listOf("250-ServiceID=abc", "250-PrivateKey=ED25519-V3:KEY", "250 OK")
        val fields = ControlPort.parseControlReply(lines)
        assertEquals("abc", fields["ServiceID"])
        assertEquals("ED25519-V3:KEY", fields["PrivateKey"])
        assertEquals(2, fields.size)
    }
}
