package expo.modules.tormanager

import java.io.EOFException
import java.io.InputStream
import java.net.Socket

/** InputStream.readNBytes is API 33+; the G20 is API 30. */
fun InputStream.readExact(n: Int): ByteArray {
    val buf = ByteArray(n)
    var off = 0
    while (off < n) {
        val r = read(buf, off, n - off)
        if (r < 0) throw EOFException("stream closed at $off/$n")
        off += r
    }
    return buf
}

/** SOCKS5 (RFC 1928) no-auth CONNECT via the local tor SOCKS port.
 *  ATYP=3 (domain name) so tor resolves the .onion itself. */
fun socksDial(socksPort: Int, host: String, port: Int): Socket {
    val s = Socket("127.0.0.1", socksPort)
    try {
        s.soTimeout = 120_000   // onion circuit build can take tens of seconds
        val out = s.getOutputStream()
        val inp = s.getInputStream()
        out.write(byteArrayOf(5, 1, 0)); out.flush()
        val method = inp.readExact(2)
        require(method[0].toInt() == 5 && method[1].toInt() == 0) { "socks method refused" }
        val hb = host.toByteArray(Charsets.US_ASCII)
        require(hb.size in 1..255) { "host too long for socks" }
        out.write(byteArrayOf(5, 1, 0, 3, hb.size.toByte()) + hb +
                  byteArrayOf(((port shr 8) and 0xff).toByte(), (port and 0xff).toByte()))
        out.flush()
        val rep = inp.readExact(4)
        require(rep[1].toInt() == 0) { "socks connect failed, REP=${rep[1]}" }
        when (rep[3].toInt()) {          // drain BND.ADDR + BND.PORT
            1 -> inp.readExact(4 + 2)
            3 -> { val l = inp.readExact(1)[0].toInt() and 0xff; inp.readExact(l + 2) }
            4 -> inp.readExact(16 + 2)
            else -> throw IllegalStateException("bad socks ATYP ${rep[3]}")
        }
        return s
    } catch (e: Exception) {
        s.close()
        throw e
    }
}
