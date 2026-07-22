package expo.modules.tormanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultipartTest {
    @Test fun parsesFieldsAndFileParts() {
        val b = "----b"
        val body = ("--$b\r\nContent-Disposition: form-data; name=\"text\"\r\n\r\nhello\r\n" +
            "--$b\r\nContent-Disposition: form-data; name=\"scope\"\r\n\r\nkreds\r\n" +
            "--$b\r\nContent-Disposition: form-data; name=\"photos\"; filename=\"a.jpg\"\r\n" +
            "Content-Type: image/jpeg\r\n\r\nJPEGBYTES\r\n--$b--\r\n").toByteArray(Charsets.ISO_8859_1)
        val form = Multipart.parse("multipart/form-data; boundary=$b", body)
        assertEquals("hello", form.fields["text"]); assertEquals("kreds", form.fields["scope"])
        assertEquals(1, form.files.size)
        assertEquals("photos", form.files[0].name); assertEquals("a.jpg", form.files[0].filename)
        assertEquals("JPEGBYTES", String(form.files[0].bytes, Charsets.ISO_8859_1))
    }

    @Test fun decodesUtf8FieldValuesAndPreservesBinaryFileBytes() {
        val b = "----b"
        val boundary = "--$b"
        val crlf = "\r\n"
        val utf8Text = "hej Åse ø 🌸"

        // Build multipart body manually: UTF-8 text field + binary file.
        // Headers and boundaries are ASCII/ISO-8859-1; field value is UTF-8 bytes;
        // file bytes are raw binary (never decoded).
        val bodyParts = listOf(
            "$boundary$crlf".toByteArray(Charsets.ISO_8859_1),
            "Content-Disposition: form-data; name=\"text\"$crlf$crlf".toByteArray(Charsets.ISO_8859_1),
            utf8Text.toByteArray(Charsets.UTF_8),  // UTF-8 encoded field value
            "$crlf$boundary$crlf".toByteArray(Charsets.ISO_8859_1),
            "Content-Disposition: form-data; name=\"file\"; filename=\"test.bin\"$crlf".toByteArray(Charsets.ISO_8859_1),
            "Content-Type: application/octet-stream$crlf$crlf".toByteArray(Charsets.ISO_8859_1),
            byteArrayOf(0x89.toByte(), 0xFF.toByte(), 0x00, 0x42),  // Binary file bytes with high/zero bytes
            "$crlf$boundary--$crlf".toByteArray(Charsets.ISO_8859_1)
        )
        val body = bodyParts.fold(byteArrayOf()) { acc, part -> acc + part }

        val form = Multipart.parse("multipart/form-data; boundary=$b", body)

        // Field value should decode correctly from UTF-8 bytes, not mojibake from ISO-8859-1
        assertEquals(utf8Text, form.fields["text"])
        // File bytes should remain exact (no charset mangling)
        assertEquals(1, form.files.size)
        assertEquals("file", form.files[0].name)
        assertEquals("test.bin", form.files[0].filename)
        assertTrue(form.files[0].bytes.contentEquals(byteArrayOf(0x89.toByte(), 0xFF.toByte(), 0x00, 0x42)))
    }
}
