package expo.modules.tormanager

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/** Gossip server -- arc 1 Task 4: the phone's ANSWERING side. Accepts inbound
 *  loopback-Tor connections (a friend's node dialing OUR onion address) and
 *  runs `KotlinHandshake.respondHandshake` (Task 2) then, on success,
 *  `KotlinSync.serve` (Task 3) -- the responder counterpart to
 *  `SyncRunner.runSync`'s outbound (initiator) path.
 *
 *  Binds `InetAddress.getLoopbackAddress()` ONLY (127.0.0.1) -- this server
 *  is never meant to be reachable directly off-device; a real friend reaches
 *  it by dialing this phone's onion address, which the Tor daemon forwards
 *  to this loopback port as a hidden-service target (the standard Tor
 *  onion-service pattern: the .onion listener is a LOCAL forwarding rule, not
 *  a public bind). Binding anything wider (0.0.0.0 or a real interface
 *  address) would make this reachable to every other app/process on the LAN,
 *  which is never the intent.
 *
 *  LOCK (coarse, and DELIBERATELY so -- read this before changing it): every
 *  accepted connection's ENTIRE handshake+serve runs while holding the SAME
 *  process-wide `ReentrantLock` `SyncRunner.runSync` uses (now exposed as
 *  `internal val SyncRunner.syncLock`, `TorNodeService` threads the same
 *  instance into both). The reason is the shared writer underneath both
 *  paths: an inbound `serve()` and an outbound `SyncRunner.runSync` each open
 *  their own `SqliteSyncStore` (a fresh `SQLiteOpenHelper` connection) onto
 *  the SAME `sync_store.db` file -- Android's default SQLite journal mode
 *  gives no safe story for two connections writing concurrently, so the
 *  writer must be serialized at a level ABOVE the store, not inside it (the
 *  store itself has no locking of its own).
 *
 *  TRADEOFF this accepts: locking the whole handshake+serve, not just the
 *  store calls inside it, means a slow or stalled peer on ONE side can block
 *  the OTHER side (a friend dialing in blocks our own outbound heartbeat
 *  sync, and vice versa) for as long as that connection takes. This is
 *  correct-but-not-concurrent, which is the right tradeoff for arc 1:
 *  correctness over throughput, given the shared SQLite writer leaves no
 *  finer-grained safe alternative without new store-level locking (out of
 *  this task's scope). The `SOCKET_TIMEOUT_MS` SO_TIMEOUT set on every
 *  accepted socket bounds the worst case -- a stalled/hostile peer can hold
 *  the lock for at most that long, not forever.
 *
 *  Bounded worker pool (`POOL_SIZE`, small and fixed): the accept loop itself
 *  never blocks on the lock -- it only `accept()`s and hands the socket to
 *  the pool, so TCP-level connects are never refused just because a prior
 *  connection is mid-serve; only the PROTOCOL work (which needs the lock)
 *  queues. One bad connection (garbage frame, peer closes early, malformed
 *  JSON, ...) is caught per-connection in `handle` and never escapes to kill
 *  the accept loop or another connection's worker thread. */
class GossipServer(
    private val store: SyncStore,
    private val fixtureProvider: () -> KotlinHandshake.Fixture?,
    private val lock: ReentrantLock,
    private val port: Int = 0,
) {
    companion object {
        // Bounds how long an accepted connection's read/write calls may block
        // -- and, since the whole handshake+serve runs under `lock` (see
        // class doc), how long a stalled/hostile peer can hold that lock.
        // No existing "one full protocol session" constant exists elsewhere
        // in this module to mirror (ControlPort/MediaServer/LocalWebServer's
        // 5s are single-HTTP-request budgets, far too short for a
        // multi-phase, potentially multi-MB blob exchange); this instead
        // mirrors the desk-loopback harness's OWN dialing `SocketStream`
        // (SyncLoopbackTest.kt), which already picked 30s as the budget for
        // exactly this same shape of connection (a full AUTH+sync round
        // trip) against a real node.
        const val SOCKET_TIMEOUT_MS = 30_000
        private const val BACKLOG = 50
        private const val POOL_SIZE = 4
    }

    @Volatile private var serverSocket: ServerSocket? = null
    private var pool: ExecutorService? = null
    private var acceptThread: Thread? = null

    /** The bound port once `start()` has run, or -1 before start / after stop. */
    @Volatile var boundPort: Int = -1
        private set

    /** Opens the ServerSocket (loopback only) and begins accepting on a
     *  background thread. Returns the bound port (useful when `port == 0`,
     *  i.e. an OS-assigned ephemeral port, the normal case in tests and --
     *  since the real bind is behind the onion service, not a fixed contract
     *  -- likely in production too). Idempotent: a second call while already
     *  running just returns the existing port. */
    @Synchronized
    fun start(): Int {
        serverSocket?.let { return boundPort }
        val s = ServerSocket(port, BACKLOG, InetAddress.getLoopbackAddress())
        serverSocket = s
        boundPort = s.localPort
        val executor = Executors.newFixedThreadPool(POOL_SIZE)
        pool = executor
        acceptThread = thread(isDaemon = true, name = "gossip-accept") {
            while (true) {
                val sock = try { s.accept() } catch (e: Exception) { break }   // s.close() in stop() lands here
                try {
                    executor.execute { handle(sock) }
                } catch (e: RejectedExecutionException) {
                    runCatching { sock.close() }   // pool already shutting down (racing stop())
                }
            }
        }
        return boundPort
    }

    /** Closes the ServerSocket (unblocks the accept loop's `accept()` call,
     *  so subsequent connects are refused) and drains the worker pool: lets
     *  in-flight handlers finish (bounded), then forces a shutdown if any are
     *  still running past that. Idempotent. */
    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundPort = -1
        acceptThread?.join(5_000)
        acceptThread = null
        pool?.let { p ->
            p.shutdown()
            if (!p.awaitTermination(5, TimeUnit.SECONDS)) p.shutdownNow()
        }
        pool = null
    }

    // One accepted connection, always on a pool thread. Never throws out --
    // every path (refused fixture, handshake failure, serve failure, a
    // garbage/malformed first frame) is caught here so it can never kill the
    // accept loop or another connection's worker.
    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = SOCKET_TIMEOUT_MS
            val stream = SocketAdapterStream(sock)
            // No identity yet (first-load pairing incomplete) -- there is
            // nothing to authenticate WITH, so refuse by simply closing
            // (the `finally` below) rather than attempting a handshake that
            // can only fail deep inside respondHandshake's writeFrame.
            val fixture = fixtureProvider() ?: return
            lock.lock()
            try {
                val result = KotlinHandshake.respondHandshake(
                    stream, fixture, isKnown = { store.knownIdentities().contains(it) })
                if (result is KotlinHandshake.HandshakeResult.Ok) {
                    KotlinSync.serve(stream, store, fixture, result.peerCert)
                }
                // Refused/Failed: respondHandshake never closes the stream by
                // contract (its own doc comment -- a caller that continues on
                // the same connection, like this one on Ok, must own
                // closing); this handler's `finally` does it uniformly for
                // every outcome, success or not.
            } finally {
                lock.unlock()
            }
        } catch (e: Exception) {
            // Per-connection catch-all (garbage first frame, peer closed
            // mid-read, malformed JSON, an oversized length prefix, ...): one
            // bad connection must never kill this worker thread or the
            // accept loop -- swallow and fall through to close.
        } finally {
            runCatching { sock.close() }
        }
    }
}
