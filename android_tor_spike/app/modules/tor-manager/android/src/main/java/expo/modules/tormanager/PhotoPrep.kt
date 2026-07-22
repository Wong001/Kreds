package expo.modules.tormanager

/** Outbound photo prep for the composer's upload path (main process -- own
 *  bytes, no sandbox; contrast the AVIF decode side, which sandboxes FRIEND
 *  bytes in an isolatedProcess). Re-encoding through `Bitmap` drops ALL EXIF
 *  (the privacy requirement) because `Bitmap` holds only decoded pixels --
 *  no metadata survives decode+re-encode. Downscales the long edge to
 *  PHOTO_MAX, then steps JPEG quality down until under PHOTO_CAP (mirrors
 *  MAX_BLOB_BYTES-64 headroom for wrap/AAD framing added later in Compose).
 *  No JVM unit test -- BitmapFactory/Bitmap need an Android runtime;
 *  exercised on-device in Task 10 (same posture as the SQLite-only
 *  accessors). */
object PhotoPrep {
    private const val PHOTO_MAX = 2560
    private const val PHOTO_CAP = 10 * 1024 * 1024 - 64

    fun toUploadJpeg(raw: ByteArray): ByteArray? {
        var bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val w = bmp.width; val h = bmp.height; val long = maxOf(w, h)
        if (long > PHOTO_MAX) {
            val s = PHOTO_MAX.toFloat() / long
            bmp = android.graphics.Bitmap.createScaledBitmap(bmp, (w * s).toInt(), (h * s).toInt(), true)
        }
        for (q in intArrayOf(90, 80, 70, 60, 50)) {
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, out)
            if (out.size() <= PHOTO_CAP) return out.toByteArray()
        }
        return null   // still too big at q50 -> reject (caller 4xx)
    }
}
