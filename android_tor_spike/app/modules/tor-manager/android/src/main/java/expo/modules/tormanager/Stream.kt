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
