package expo.modules.tormanager

import java.security.MessageDigest

/** Kotlin port of hearth.identity.SignedMessage -- parse/verify/msg_id.
 *  All crypto/canonical via KotlinWire; body matches the Python signer. */
data class SignedMessage(
    val cert: KotlinWire.CertDict,
    val seq: Int,
    val payload: Map<String, Any?>,
    val signature: String,
) {
    // The payload's kind discriminator is "kind" (KIND_POST="post",
    // KIND_DM="dm" per hearth/messages.py), NOT "type" -- "type" is the
    // signed ENVELOPE field ("message"). Reading "type" here returns "" for
    // every real message and silently breaks missingBlobs' kind filter.
    val kind: String get() = payload["kind"] as? String ?: ""

    fun body(): ByteArray = KotlinWire.canonical(mapOf(
        "type" to "message", "protocol" to KotlinWire.PROTOCOL,
        "identity_pub" to cert.identity_pub, "device_pub" to cert.device_pub,
        "seq" to seq, "payload" to payload,
    ))

    fun msgId(): String =
        KotlinWire.toHex(MessageDigest.getInstance("SHA-256").digest(body()))

    fun verifyDeviceSignature(): Boolean =
        KotlinWire.verifyRaw(cert.device_pub, signature, body())

    fun toDict(): Map<String, Any?> = mapOf(
        "cert" to mapOf(
            "identity_pub" to cert.identity_pub, "device_pub" to cert.device_pub,
            "device_name" to cert.device_name, "enrolled_at" to cert.enrolled_at,
            "signature" to cert.signature),
        "seq" to seq, "payload" to payload, "signature" to signature)
}

object SignedMessageKt {
    @Suppress("UNCHECKED_CAST")
    fun fromDict(d: Map<String, Any?>): SignedMessage {
        val c = d["cert"] as Map<String, Any?>
        return SignedMessage(
            KotlinWire.CertDict(
                c["identity_pub"] as String, c["device_pub"] as String,
                c["device_name"] as String, (c["enrolled_at"] as Number).toDouble(),
                c["signature"] as String),
            (d["seq"] as Number).toInt(),
            d["payload"] as Map<String, Any?>,
            d["signature"] as String)
    }
}
