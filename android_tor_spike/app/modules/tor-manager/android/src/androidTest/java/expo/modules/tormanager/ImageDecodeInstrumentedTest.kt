package expo.modules.tormanager

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task 3 (B.2d): on-device gate for the isolated AVIF decode boundary. NOT
 * JVM-testable -- the decoder is a native library (.so) that will not load in a
 * plain JVM unit test, and the isolation is a real second OS process. Runs on
 * the G20 (API 30): `.\gradlew :tor-manager:connectedDebugAndroidTest`.
 *
 * The fixture `test_photo.avif` is a real AVIF produced by hearth's photo gate
 * (`hearth.imagegate.transcode_photo`, the exact function that authors photo
 * blobs) from a 48x32 solid-colour image -- ground truth for the decode.
 *
 * Sanity-check that the decode really runs in the separate process by observing
 * `:imagedecode` in `adb shell ps` during a run (see the task report).
 */
@RunWith(AndroidJUnit4::class)
class ImageDecodeInstrumentedTest {

    @Before
    fun setUp() {
        // The target app context binds the :imagedecode isolated service.
        ImageDecodeClient.init(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
    }

    private fun fixtureAvif(): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("test_photo.avif").use { it.readBytes() }

    @Test
    fun decodesCommittedAvifFixtureToPng() {
        val avif = fixtureAvif()
        // The fixture really is an AVIF the KotlinImageDecode dispatcher routes
        // to this decoder (ftyp box + avif brand).
        assertEquals("ftyp", String(avif, 4, 4, Charsets.US_ASCII))
        assertEquals("avif", String(avif, 8, 4, Charsets.US_ASCII))

        val png = ImageDecodeClient.decodeAvif(avif)
        assertNotNull("isolated decode returned null for a valid AVIF", png)
        png!!

        // PNG magic 89 50 4E 47.
        assertTrue(
            "output is not PNG",
            png.size >= 4 &&
                (png[0].toInt() and 0xFF) == 0x89 && (png[1].toInt() and 0xFF) == 0x50 &&
                (png[2].toInt() and 0xFF) == 0x4E && (png[3].toInt() and 0xFF) == 0x47,
        )

        // Decoded dimensions match the fixture (48x32) -- proves real pixels,
        // not just a well-formed PNG header.
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertNotNull("decoded PNG did not parse to a Bitmap", bmp)
        assertEquals(48, bmp.width)
        assertEquals(32, bmp.height)
    }

    @Test
    fun garbageBytesFailClosed() {
        // Hostile / non-AVIF bytes handed straight to the boundary must yield
        // null (-> UI placeholder), never a hang or an app crash. This exercises
        // the fail-closed path across the real IPC round-trip.
        val junk = ByteArray(256) { (it and 0xFF).toByte() }
        assertNull(ImageDecodeClient.decodeAvif(junk))
    }
}
