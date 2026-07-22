package expo.modules.tormanager

/** Minimal, byte-safe multipart/form-data parser for the loopback server's
 *  POST body (the journal composer's upload: text fields + photo file
 *  parts). Operates on raw ByteArray throughout for PART CONTENT -- a file
 *  part is binary and must never round-trip through a String (which could
 *  corrupt bytes outside a charset's range). The per-part HEADER block is
 *  short, ASCII-only wire framing (Content-Disposition etc.), so it is safe
 *  to decode via ISO-8859-1 (a 1:1 byte<->char mapping for 0-255, same
 *  reasoning LocalWebServer.handle uses for its BufferedReader) just to
 *  locate `name=`/`filename=`, without ever touching the content bytes. */
object Multipart {
    data class Part(val name: String, val filename: String?, val bytes: ByteArray)
    data class Form(val fields: Map<String, String>, val files: List<Part>)

    private val CRLF = byteArrayOf(13, 10)
    private val CRLFCRLF = byteArrayOf(13, 10, 13, 10)

    /** `contentType` is the request's raw Content-Type header value, e.g.
     *  `multipart/form-data; boundary=----b`. Returns an empty Form (no
     *  fields, no files) if no boundary can be found, rather than throwing --
     *  a malformed/missing boundary is a caller error the route layer turns
     *  into a 4xx, not a parser crash. */
    fun parse(contentType: String, body: ByteArray): Form {
        val boundary = boundaryOf(contentType) ?: return Form(emptyMap(), emptyList())
        val delim = ("--" + boundary).toByteArray(Charsets.ISO_8859_1)
        val marks = findAll(body, delim)

        val fields = linkedMapOf<String, String>()
        val files = mutableListOf<Part>()

        // Every mark except the LAST is a real part's opening delimiter; the
        // last mark is the closing "--boundary--" (findAll matches on the
        // "--boundary" PREFIX of that closing marker too, so it naturally
        // ends up as the final entry without any special-casing). A part's
        // content therefore runs from just after marks[i]'s delimiter+CRLF
        // to just before marks[i+1]'s delimiter, minus the CRLF that always
        // precedes a following boundary line.
        for (i in 0 until marks.size - 1) {
            var start = marks[i] + delim.size
            if (matchesAt(body, start, CRLF)) start += 2
            var end = marks[i + 1]
            if (end - start >= 2 && body[end - 2] == 13.toByte() && body[end - 1] == 10.toByte()) end -= 2
            if (start > end) continue
            val part = parsePart(body, start, end) ?: continue
            if (part.filename != null) files.add(part)
            else fields[part.name] = String(part.bytes, Charsets.UTF_8)
        }
        return Form(fields, files)
    }

    /** One part = its header block (up to the first blank line) + raw
     *  content bytes after it. Returns null if there's no Content-Disposition
     *  header or no `name=` -- an unnamed part is meaningless to the caller. */
    private fun parsePart(body: ByteArray, start: Int, end: Int): Part? {
        val sep = findBytes(body, CRLFCRLF, start, end) ?: return null
        val headerText = String(body, start, sep - start, Charsets.ISO_8859_1)
        val disposition = headerText.split("\r\n")
            .firstOrNull { it.startsWith("Content-Disposition", ignoreCase = true) } ?: return null
        val name = attr(disposition, "name") ?: return null
        val filename = attr(disposition, "filename")
        val contentStart = sep + CRLFCRLF.size
        val bytes = body.copyOfRange(contentStart, end)
        return Part(name, filename, bytes)
    }

    private fun attr(header: String, key: String): String? =
        Regex("$key=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)

    /** Pulls the boundary token out of a `Content-Type: multipart/form-data;
     *  boundary=...` value, unquoting it if the client quoted it and cutting
     *  off any further `;`-separated parameters after it. */
    private fun boundaryOf(contentType: String): String? {
        val idx = contentType.indexOf("boundary=", ignoreCase = true)
        if (idx < 0) return null
        val rest = contentType.substring(idx + "boundary=".length).trim()
        if (rest.startsWith("\"")) {
            val close = rest.indexOf('"', 1)
            return if (close > 0) rest.substring(1, close) else rest.trim('"')
        }
        val semi = rest.indexOf(';')
        return if (semi >= 0) rest.substring(0, semi).trim() else rest
    }

    /** Every non-overlapping occurrence of `needle` in `hay`, left to right. */
    private fun findAll(hay: ByteArray, needle: ByteArray): List<Int> {
        val out = mutableListOf<Int>()
        var i = 0
        while (i + needle.size <= hay.size) {
            if (matchesAt(hay, i, needle)) { out.add(i); i += needle.size } else i++
        }
        return out
    }

    private fun findBytes(hay: ByteArray, needle: ByteArray, from: Int, to: Int): Int? {
        var i = from
        while (i + needle.size <= to) {
            if (matchesAt(hay, i, needle)) return i
            i++
        }
        return null
    }

    private fun matchesAt(hay: ByteArray, at: Int, needle: ByteArray): Boolean {
        if (at < 0 || at + needle.size > hay.size) return false
        for (j in needle.indices) if (hay[at + j] != needle[j]) return false
        return true
    }
}
