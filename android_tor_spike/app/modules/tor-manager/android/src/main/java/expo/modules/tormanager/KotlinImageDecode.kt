package expo.modules.tormanager

/** Magic-byte format dispatch for a decrypted blob. Raster formats the
 *  platform can already render pass through with their mime; AVIF (the
 *  format hearth stores photos in) is routed to the injected decoder --
 *  wired in Task 3 to the isolatedProcess ImageDecodeService, so the native
 *  decoder never links into the main process. Non-images (e.g. a video mp4)
 *  return null -> UI placeholder. Mirrors imagegate.is_image_bytes + AVIF. */
object KotlinImageDecode {
    var avifDecoder: ((ByteArray) -> ByteArray?)? = null

    private fun starts(b: ByteArray, vararg sig: Int): Boolean {
        if (b.size < sig.size) return false
        for (i in sig.indices) if ((b[i].toInt() and 0xFF) != sig[i]) return false
        return true
    }
    private fun ascii(b: ByteArray, off: Int, s: String): Boolean {
        if (b.size < off + s.length) return false
        for (i in s.indices) if ((b[off + i].toInt() and 0xFF) != s[i].code) return false
        return true
    }

    fun toRenderable(bytes: ByteArray): Pair<String, ByteArray>? {
        return when {
            starts(bytes, 0x89, 0x50, 0x4E, 0x47) -> "image/png" to bytes
            starts(bytes, 0xFF, 0xD8) -> "image/jpeg" to bytes
            starts(bytes, 0x47, 0x49, 0x46, 0x38) -> "image/gif" to bytes
            starts(bytes, 0x42, 0x4D) -> "image/bmp" to bytes
            starts(bytes, 0x49, 0x49, 0x2A, 0x00) || starts(bytes, 0x4D, 0x4D, 0x00, 0x2A) -> "image/tiff" to bytes
            ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP") -> "image/webp" to bytes
            ascii(bytes, 4, "ftyp") && (ascii(bytes, 8, "avif") || ascii(bytes, 8, "avis")) ->
                avifDecoder?.invoke(bytes)?.let { "image/png" to it }
            else -> null
        }
    }
}
