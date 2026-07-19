package expo.modules.tormanager

import org.json.JSONObject
import java.security.SecureRandom

object KotlinHandshake {
    private const val PROBE_GRACE_MS = 1500L

    data class Fixture(
        val device_priv: String, val device_pub: String,
        val cert: KotlinWire.CertDict, val onion_addr: String,
    )

    sealed class HandshakeResult {
        object Accepted : HandshakeResult()
        object Refused : HandshakeResult()
        data class Failed(val stage: String, val reason: String) : HandshakeResult()
    }

    fun randomHex16(): String {
        val b = ByteArray(16); SecureRandom().nextBytes(b); return KotlinWire.toHex(b)
    }

    fun splitAddr(addr: String): Pair<String, Int> {
        val i = addr.lastIndexOf(':')
        require(i >= 0) { "address has no port: $addr" }
        return addr.substring(0, i) to addr.substring(i + 1).toInt()
    }

    fun parseFixture(json: String): Fixture {
        val o = JSONObject(json); val c = o.getJSONObject("cert")
        return Fixture(
            o.getString("device_priv"), o.getString("device_pub"),
            KotlinWire.CertDict(
                c.getString("identity_pub"), c.getString("device_pub"),
                c.getString("device_name"), c.getDouble("enrolled_at"),
                c.getString("signature")),
            o.getString("onion_addr"))
    }

    private fun readFrame(connId: Int): JSONObject {
        val header = TorEngine.recv(connId, 4)
        val n = ((header[0].toInt() and 0xff) shl 24) or ((header[1].toInt() and 0xff) shl 16) or
                ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
        require(n <= KotlinWire.MAX_FRAME) { "frame too large" }
        val body = TorEngine.recv(connId, n)
        for (bb in body) require((bb.toInt() and 0xff) <= 0x7e) { "non-ascii frame byte" }
        return JSONObject(String(body, Charsets.US_ASCII))
    }

    private fun writeFrame(connId: Int, obj: Map<String, Any?>) =
        TorEngine.send(connId, KotlinWire.writeFrameBytes(obj))

    private fun certToMap(c: KotlinWire.CertDict): Map<String, Any?> = mapOf(
        "identity_pub" to c.identity_pub, "device_pub" to c.device_pub,
        "device_name" to c.device_name,
        "enrolled_at" to KotlinWire.PyFloat(c.enrolled_at), "signature" to c.signature)

    private fun certFromJson(o: JSONObject) = KotlinWire.CertDict(
        o.getString("identity_pub"), o.getString("device_pub"),
        o.getString("device_name"), o.getDouble("enrolled_at"), o.getString("signature"))

    /** Mirror of handshake.ts, phone = initiator, stops at accept/refuse. */
    fun run(connId: Int, fixture: Fixture, rnd: () -> String = ::randomHex16): HandshakeResult {
        try {
            // HELLO
            val myNonce = rnd()
            writeFrame(connId, mapOf("t" to "hello", "cert" to certToMap(fixture.cert), "nonce" to myNonce))
            val peerHello = readFrame(connId)
            if (peerHello.optString("t") != "hello") return HandshakeResult.Failed("hello", "unexpected t=${peerHello.optString("t")}")
            val peerCert = certFromJson(peerHello.getJSONObject("cert"))
            if (!KotlinWire.verifyCert(peerCert)) return HandshakeResult.Failed("hello", "node cert failed verification")

            // AUTH
            writeFrame(connId, mapOf("t" to "auth",
                "sig" to KotlinWire.signRaw(fixture.device_priv, KotlinWire.authBody(peerHello.getString("nonce")))))
            val peerAuth = readFrame(connId)
            if (peerAuth.optString("t") != "auth") return HandshakeResult.Failed("auth", "unexpected t=${peerAuth.optString("t")}")
            if (!KotlinWire.verifyRaw(peerCert.device_pub, peerAuth.getString("sig"), KotlinWire.authBody(myNonce)))
                return HandshakeResult.Failed("auth", "node failed device-key proof")

            // Acceptance probe: one verdict read up front, grace-wait for an
            // unsolicited refusal, then send empty revocations and await the
            // SAME read (see handshake.ts amendment -- write-first races the
            // refusing node's close). recv is blocking, so emulate the grace
            // by setting a short read deadline is not available here; instead
            // we send the revocations frame immediately after AUTH -- on the
            // accepted path the node (responder) is already blocked reading
            // it, and on refusal the node has already written "refused" which
            // arrives ahead of our frame on the wire. A refusing node closing
            // after its write still delivers the buffered "refused" because
            // our very next recv reads it before noticing the close.
            writeFrame(connId, mapOf("t" to "revocations", "revs" to emptyList<Any>()))
            val verdict = readFrame(connId)
            return when (verdict.optString("t")) {
                "refused" -> HandshakeResult.Refused
                "revocations" ->
                    if (peerCert.identity_pub != fixture.cert.identity_pub)
                        HandshakeResult.Failed("probe", "accepted by a non-home-node identity")
                    else HandshakeResult.Accepted
                else -> HandshakeResult.Failed("probe", "unexpected t=${verdict.optString("t")}")
            }
        } catch (e: Exception) {
            return HandshakeResult.Failed("io", e.toString())
        } finally {
            TorEngine.close(connId)
        }
    }
}
