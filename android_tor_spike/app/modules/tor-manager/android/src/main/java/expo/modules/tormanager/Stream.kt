package expo.modules.tormanager

/** Blocking byte-stream abstraction so sync logic (KotlinSync) can run over
 *  either transport that drives it: the phone dials its own node over Tor
 *  (TorStream, this file), and the desk gate (BB-5) dials over plain TCP
 *  (SocketStream, added there). KotlinHandshake predates this interface and
 *  still talks to TorEngine directly via a bare connId -- left alone here
 *  (BB-5 refactors it onto Stream via runOverStream); TorStream below is
 *  just a thin adapter over the same TorEngine calls KotlinHandshake uses. */
interface Stream {
    /** Block until exactly n bytes are read, or throw. */
    fun readExactSync(n: Int): ByteArray
    fun write(bytes: ByteArray)
    fun close()
}

class TorStream(private val connId: Int) : Stream {
    override fun readExactSync(n: Int): ByteArray = TorEngine.recv(connId, n)
    override fun write(bytes: ByteArray) = TorEngine.send(connId, bytes)
    override fun close() = TorEngine.close(connId)
}

/** Adapts an already-connected `java.net.Socket` onto Stream -- gossip server
 *  Task 4: GossipServer wraps each ACCEPTED inbound socket (from
 *  `ServerSocket.accept()`) in this so `KotlinHandshake.respondHandshake`/
 *  `KotlinSync.serve` run unmodified over it, the exact same contract
 *  TorStream gives the dial side. Same read/write shape as the test-only
 *  dialing `SocketStream` (SyncLoopbackTest.kt: `Socket(host, port)` then
 *  loop-read to `n` bytes or EOF) -- kept as a separate, `main`-source class
 *  rather than reusing that one: it lives in `src/test` and production code
 *  (this file) cannot depend on test sources, and its constructor DIALS
 *  (`Socket(host, port)`) where this one ADAPTS an already-accepted socket. */
class SocketAdapterStream(private val sock: java.net.Socket) : Stream {
    private val inp = sock.getInputStream()
    private val out = sock.getOutputStream()
    override fun readExactSync(n: Int): ByteArray {
        val b = ByteArray(n); var off = 0
        while (off < n) {
            val r = inp.read(b, off, n - off)
            if (r < 0) throw RuntimeException("EOF after $off/$n bytes")
            off += r
        }
        return b
    }
    override fun write(bytes: ByteArray) { out.write(bytes); out.flush() }
    override fun close() = sock.close()
}
