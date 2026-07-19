package expo.modules.tormanager

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Kotlin port of the wire layer (canonical JSON, length-frames, Ed25519),
 *  byte-matched to hearth/identity.py via the committed wire_vectors.json.
 *  Runs with no JS runtime -- this is the background heartbeat's crypto. */
object KotlinWire {
    const val PROTOCOL = "hearth/v0.2"
    const val MAX_FRAME = 16 * 1024 * 1024

    class PyFloat(val value: Double)

    fun toHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append("%02x".format(x.toInt() and 0xff))
        return sb.toString()
    }

    fun fromHex(h: String): ByteArray {
        require(h.length % 2 == 0) { "odd-length hex" }
        return ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    private fun pyFloatRepr(n: Double): String {
        require(n.isFinite()) { "non-finite float unsupported" }
        // Refuse the extreme-magnitude range where Python repr() itself would
        // switch to scientific notation (no spike value is near it).
        if (Math.abs(n) >= 1e16 || (n != 0.0 && Math.abs(n) < 1e-4))
            throw IllegalArgumentException("float out of supported range: $n")
        if (n == Math.floor(n)) return "${n.toLong()}.0"
        // Non-integral: Java's Double.toString() computes the same minimal
        // round-trip decimal DIGITS as Python's repr(), but it switches to
        // scientific notation at a much smaller magnitude (>=1e7 or <1e-3)
        // than Python (>=1e16 or <1e-4) -- e.g. 1752900000.123456 renders as
        // "1.752900000123456E9" in Kotlin. Reformat those digits into plain
        // fixed-point notation instead of re-deriving them (they already
        // match Python bit-for-bit; only the notation choice differs).
        val s = n.toString()
        val eIdx = s.indexOfFirst { it == 'E' || it == 'e' }
        if (eIdx < 0) return s   // already fixed notation; already matches Python
        val mantissa = s.substring(0, eIdx)
        val exp = s.substring(eIdx + 1).toInt()
        val neg = mantissa.startsWith("-")
        val digits = if (neg) mantissa.substring(1) else mantissa
        val dotIdx = digits.indexOf('.')
        val intPart = if (dotIdx < 0) digits else digits.substring(0, dotIdx)
        var fracPart = if (dotIdx < 0) "" else digits.substring(dotIdx + 1)
        // Java's Double.toString() always emits at least one fractional digit,
        // even when the minimal round-trip representation is a single
        // significant digit (e.g. 0.0005 -> "5.0E-4"). That lone "0" is
        // mandatory filler, never a real digit -- a genuine minimal-form
        // fractional part is never exactly "0". Strip it before building
        // allDigits, or it inflates e.g. 0.0005 into "0.00050".
        if (fracPart == "0") fracPart = ""
        val allDigits = intPart + fracPart
        val pointPos = intPart.length + exp   // digits before the decimal point, post-shift
        val sb = StringBuilder()
        if (neg) sb.append('-')
        when {
            pointPos <= 0 -> sb.append("0.").append("0".repeat(-pointPos)).append(allDigits)
            pointPos >= allDigits.length -> sb.append(allDigits).append("0".repeat(pointPos - allDigits.length)).append(".0")
            else -> sb.append(allDigits, 0, pointPos).append('.').append(allDigits, pointPos, allDigits.length)
        }
        return sb.toString()
    }

    private fun escapeString(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            val c = ch.code
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                c == 0x08 -> sb.append("\\b")
                c == 0x09 -> sb.append("\\t")
                c == 0x0a -> sb.append("\\n")
                c == 0x0c -> sb.append("\\f")
                c == 0x0d -> sb.append("\\r")
                c < 0x20 || c > 0x7e -> sb.append("\\u").append("%04x".format(c))
                else -> sb.append(ch)
            }
        }
        return sb.append("\"").toString()
    }

    // Python sorts dict keys by code point; Kotlin String.compareTo is by
    // UTF-16 unit. They diverge only when an astral key meets U+E000..U+FFFF.
    private fun codePointCompare(a: String, b: String): Int {
        val ca = a.codePoints().toArray(); val cb = b.codePoints().toArray()
        val n = minOf(ca.size, cb.size)
        for (i in 0 until n) { val d = ca[i] - cb[i]; if (d != 0) return d }
        return ca.size - cb.size
    }

    fun dumps(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> if (v) "true" else "false"
        is String -> escapeString(v)
        is PyFloat -> pyFloatRepr(v.value)
        is Int -> v.toString()
        is Long -> v.toString()
        is List<*> -> "[" + v.joinToString(",") { dumps(it) } + "]"
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val m = v as Map<String, Any?>
            val keys = m.keys.sortedWith(::codePointCompare)
            "{" + keys.joinToString(",") { escapeString(it) + ":" + dumps(m[it]) } + "}"
        }
        else -> throw IllegalArgumentException("unsupported type in serialization: ${v::class}")
    }

    fun canonical(obj: Map<String, Any?>): ByteArray {
        val s = dumps(obj)                 // pure ASCII (ensure_ascii)
        return ByteArray(s.length) { s[it].code.toByte() }
    }

    fun writeFrameBytes(obj: Map<String, Any?>): ByteArray {
        val payload = canonical(obj)
        require(payload.size <= MAX_FRAME) { "frame too large" }
        val out = ByteArray(4 + payload.size)
        out[0] = (payload.size ushr 24).toByte(); out[1] = (payload.size ushr 16).toByte()
        out[2] = (payload.size ushr 8).toByte(); out[3] = payload.size.toByte()
        payload.copyInto(out, 4)
        return out
    }

    fun signRaw(devicePrivHex: String, data: ByteArray): String {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(fromHex(devicePrivHex), 0))
        signer.update(data, 0, data.size)
        return toHex(signer.generateSignature())
    }

    fun verifyRaw(pubHex: String, sigHex: String, data: ByteArray): Boolean = try {
        val v = Ed25519Signer()
        v.init(false, Ed25519PublicKeyParameters(fromHex(pubHex), 0))
        v.update(data, 0, data.size)
        v.verifySignature(fromHex(sigHex))
    } catch (e: Exception) { false }

    fun authBody(nonceHex: String): ByteArray =
        canonical(mapOf("type" to "gossip-auth", "protocol" to PROTOCOL, "nonce" to nonceHex))

    data class CertDict(
        val identity_pub: String, val device_pub: String,
        val device_name: String, val enrolled_at: Double, val signature: String,
    )

    fun certBody(c: CertDict): ByteArray = canonical(mapOf(
        "type" to "enrollment", "protocol" to PROTOCOL,
        "identity_pub" to c.identity_pub, "device_pub" to c.device_pub,
        "device_name" to c.device_name, "enrolled_at" to PyFloat(c.enrolled_at),
    ))

    fun verifyCert(c: CertDict): Boolean = verifyRaw(c.identity_pub, c.signature, certBody(c))
}
