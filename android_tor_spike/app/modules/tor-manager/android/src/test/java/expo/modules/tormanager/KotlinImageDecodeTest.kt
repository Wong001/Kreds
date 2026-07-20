package expo.modules.tormanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class KotlinImageDecodeTest {
    @After fun tearDown() { KotlinImageDecode.avifDecoder = null }

    private fun png() = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(8)
    private fun jpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(8)
    private fun gif() = "GIF89a".toByteArray() + ByteArray(8)
    private fun avif() = ByteArray(4) + "ftypavif".toByteArray() + ByteArray(8)   // ....ftypavif...
    private fun mp4() = ByteArray(4) + "ftypisom".toByteArray() + ByteArray(8)

    @Test fun passesThroughKnownRasterFormats() {
        assertEquals("image/png", KotlinImageDecode.toRenderable(png())!!.first)
        assertEquals("image/jpeg", KotlinImageDecode.toRenderable(jpeg())!!.first)
        assertEquals("image/gif", KotlinImageDecode.toRenderable(gif())!!.first)
    }

    @Test fun avifRoutesToDecoderAndReturnsPng() {
        val fakePng = png()
        KotlinImageDecode.avifDecoder = { _ -> fakePng }
        val r = KotlinImageDecode.toRenderable(avif())!!
        assertEquals("image/png", r.first)
        assertTrue(r.second.contentEquals(fakePng))
    }

    @Test fun avifWithNoDecoderOrFailedDecodeIsNull() {
        KotlinImageDecode.avifDecoder = null
        assertNull(KotlinImageDecode.toRenderable(avif()))
        KotlinImageDecode.avifDecoder = { _ -> null }
        assertNull(KotlinImageDecode.toRenderable(avif()))
    }

    @Test fun nonImageBytesAreNull() {
        assertNull(KotlinImageDecode.toRenderable(mp4()))
        assertNull(KotlinImageDecode.toRenderable(ByteArray(3)))
    }
}
