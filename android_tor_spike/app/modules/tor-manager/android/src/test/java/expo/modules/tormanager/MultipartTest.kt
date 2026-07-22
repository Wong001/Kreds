package expo.modules.tormanager

import org.junit.Assert.assertEquals
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
}
