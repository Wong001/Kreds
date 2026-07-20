package expo.modules.tormanager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Task 3 (B.2d): main-process client of the isolated [ImageDecodeService].
 *
 * Wired into `KotlinImageDecode.avifDecoder` at module init (TorManagerModule
 * OnCreate). When the magic-byte dispatcher meets an AVIF blob it calls
 * [decodeAvif], which:
 *   - binds the `:imagedecode` isolated service on first use (idempotent),
 *   - round-trips ONE image's cleartext AVIF bytes over `ParcelFileDescriptor`
 *     pipes -- Binder's ~1 MB transaction cap rules out passing the bytes as
 *     Parcel args, and decoded PNGs routinely exceed it,
 *   - applies a decode timeout so a wedged or crashing decoder yields null
 *     (-> UI placeholder) instead of hanging the feed, and
 *   - rebinds transparently if the isolated process was killed (a decoder crash
 *     drops the connection; the next call rebinds and respawns it).
 *
 * The main process never links the decoder itself -- only [ImageDecodeService]
 * (in the sandbox) does -- so a decoder exploit cannot reach the node's keys,
 * store, or network from here.
 */
object ImageDecodeClient {
    private const val BIND_TIMEOUT_MS = 5_000L
    private const val DECODE_TIMEOUT_MS = 12_000L
    private const val DRAIN_TIMEOUT_MS = 4_000L

    // Cached pool: each decode uses up to three short-lived tasks (writer,
    // reader, and the blocking binder call), which must run concurrently to
    // avoid pipe deadlock against a >64 KB payload.
    private val io = Executors.newCachedThreadPool()

    private val lock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var service: IImageDecodeService? = null
    private var connection: ServiceConnection? = null
    @Volatile private var connectLatch: CountDownLatch? = null

    /** Store the application context so binds survive Activity churn. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Decode AVIF -> PNG in the isolated process. Returns PNG bytes, or null on
     * any failure (unbound, decode failure, timeout, remote death). Safe to call
     * concurrently from multiple feed rows.
     */
    fun decodeAvif(avif: ByteArray): ByteArray? {
        val svc = ensureBound() ?: return null

        val inPipe = try { ParcelFileDescriptor.createPipe() } catch (t: Throwable) { return null }
        val outPipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (t: Throwable) {
            inPipe.forEach { closeQuietly(it) }
            return null
        }
        val inRead = inPipe[0]; val inWrite = inPipe[1]
        val outRead = outPipe[0]; val outWrite = outPipe[1]

        // Feed the AVIF into the input pipe on its own thread and close the
        // write end so the service reader sees EOF.
        val writer = io.submit {
            ParcelFileDescriptor.AutoCloseOutputStream(inWrite).use { it.write(avif) }
        }
        // Drain the output pipe concurrently: a large PNG would otherwise
        // deadlock against the blocking binder call (the service blocks writing
        // >64 KB until we read).
        val reader = io.submit<ByteArray> {
            ParcelFileDescriptor.AutoCloseInputStream(outRead).use { it.readBytes() }
        }
        // The binder call itself blocks until the service returns; run it in a
        // future so the whole operation honours DECODE_TIMEOUT_MS.
        val call = io.submit<Boolean> {
            // Binder DUPS both FDs into the isolated process; our local copies
            // stay open and are ours to close (below).
            svc.decode(inRead, outWrite)
        }

        return try {
            val ok = call.get(DECODE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // Close our local copies of the FDs the service dup'd, so the only
            // remaining output write end is the service's (already closed on
            // return) -> the reader now sees EOF and completes.
            closeQuietly(inRead)
            closeQuietly(outWrite)
            val png = reader.get(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            try { writer.get(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
            if (ok && looksPng(png)) png else null
        } catch (t: Throwable) {
            // Timeout / remote crash / cancellation -> fail closed. Drop the
            // service ref so a wedged isolated process is rebound next call, and
            // tear everything down.
            service = null
            call.cancel(true); reader.cancel(true); writer.cancel(true)
            listOf(inRead, inWrite, outRead, outWrite).forEach { closeQuietly(it) }
            null
        }
    }

    private fun looksPng(b: ByteArray): Boolean =
        b.size >= 4 &&
            (b[0].toInt() and 0xFF) == 0x89 && (b[1].toInt() and 0xFF) == 0x50 &&
            (b[2].toInt() and 0xFF) == 0x4E && (b[3].toInt() and 0xFF) == 0x47

    /** Bind the isolated service, blocking briefly for the connection. Returns
     *  the live binder, or null if binding failed/timed out. */
    private fun ensureBound(): IImageDecodeService? {
        service?.let { return it }
        val ctx = appContext ?: return null
        synchronized(lock) {
            service?.let { return it }
            if (connection == null) {
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
                        service = IImageDecodeService.Stub.asInterface(b)
                        connectLatch?.countDown()
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {
                        // Isolated process died/crashed. Keep the binding (with
                        // BIND_AUTO_CREATE Android respawns it and re-delivers
                        // onServiceConnected on this same connection); just drop
                        // the stale ref so the next call waits for the respawn.
                        service = null
                    }
                    override fun onBindingDied(name: ComponentName?) {
                        service = null
                        connectLatch?.countDown()
                    }
                    override fun onNullBinding(name: ComponentName?) {
                        connectLatch?.countDown()
                    }
                }
                val latch = CountDownLatch(1)
                connectLatch = latch
                connection = conn
                val ok = try {
                    ctx.bindService(
                        Intent(ctx, ImageDecodeService::class.java),
                        conn,
                        Context.BIND_AUTO_CREATE,
                    )
                } catch (t: Throwable) { false }
                if (!ok) {
                    try { ctx.unbindService(conn) } catch (_: Throwable) {}
                    connection = null
                    connectLatch = null
                    return null
                }
                latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                return service
            } else {
                // Already bound once but service is null -> the process is
                // respawning after a crash; wait for the persistent connection
                // to re-deliver onServiceConnected.
                val latch = CountDownLatch(1)
                connectLatch = latch
                latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                return service
            }
        }
    }

    private fun closeQuietly(pfd: ParcelFileDescriptor) {
        try { pfd.close() } catch (_: Throwable) {}
    }
}
