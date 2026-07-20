package expo.modules.tormanager

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.radzivon.bartoshyk.avif.coder.HeifCoder

/**
 * Task 3 (B.2d): the native AVIF decode boundary, run in a SANDBOX.
 *
 * Declared in the manifest with android:isolatedProcess="true" +
 * android:process=":imagedecode" + android:exported="false": Android runs it
 * under an ephemeral, permissionless UID with NO app-data access and NO
 * network. That isolation is the whole security value here -- an image decoder
 * is a classic RCE surface (cf. libwebp CVE-2023-4863), and blob bytes received
 * over gossip are NOT re-gated (hearth/imagegate.py:8-12): a malicious friend
 * can author a post carrying a hand-crafted AVIF whose only hash guarantee is
 * that we got the bytes the author committed to, not that they are benign. If
 * the decoder is exploited it is trapped in THIS process, away from the node's
 * keys, store, and Tor socket.
 *
 * This is the ONLY class that links the maintained dav1d/libavif-backed decoder
 * (avif-coder's HeifCoder). The main process never references HeifCoder, so its
 * native library only ever loads inside :imagedecode. The service touches
 * NOTHING but the input bytes handed to it over the pipe -- no files, no store,
 * no keys, no sockets.
 */
class ImageDecodeService : Service() {

    private val binder = object : IImageDecodeService.Stub() {
        override fun decode(
            inputRead: ParcelFileDescriptor,
            outputWrite: ParcelFileDescriptor,
        ): Boolean {
            // Read the compressed AVIF fully from the input pipe. AutoClose
            // closes inputRead on every path (including a read failure).
            val avif = try {
                ParcelFileDescriptor.AutoCloseInputStream(inputRead).use { it.readBytes() }
            } catch (t: Throwable) {
                // reading input failed -> close the output end so the caller's
                // reader unblocks with EOF, then fail closed.
                closeQuietly(outputWrite)
                return false
            }

            return try {
                val bitmap: Bitmap = HeifCoder().decode(avif)
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(outputWrite).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } finally {
                    bitmap.recycle()
                }
                true
            } catch (t: Throwable) {
                // Any decode/encode failure (malformed / hostile AVIF, OOM, a
                // native crash surfaced as an exception) fails CLOSED: close the
                // output so the caller gets EOF -> null -> placeholder. A hard
                // native crash instead takes down only :imagedecode; the bound
                // client times out and rebinds, the app never dies.
                closeQuietly(outputWrite)
                false
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun closeQuietly(pfd: ParcelFileDescriptor) {
        try { pfd.close() } catch (_: Throwable) {}
    }
}
