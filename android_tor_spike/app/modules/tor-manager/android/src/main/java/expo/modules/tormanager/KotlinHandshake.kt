package expo.modules.tormanager

import org.json.JSONObject
import java.security.SecureRandom

object KotlinHandshake {
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

    // Reads/writes over a Stream (blocking readExactSync/write/close) rather
    // than a bare TorEngine connId -- see runOverStream below. Mirrors
    // KotlinSync's private readFrame/writeFrame exactly (4-byte big-endian
    // length prefix, ASCII-only body).
    private fun readFrame(s: Stream): JSONObject {
        val header = s.readExactSync(4)
        val n = (((header[0].toLong() and 0xff) shl 24) or ((header[1].toLong() and 0xff) shl 16) or
                 ((header[2].toLong() and 0xff) shl 8) or (header[3].toLong() and 0xff))
        require(n <= KotlinWire.MAX_FRAME) { "frame too large" }
        val body = s.readExactSync(n.toInt())
        for (bb in body) require((bb.toInt() and 0xff) <= 0x7e) { "non-ascii frame byte" }
        return JSONObject(String(body, Charsets.US_ASCII))
    }

    private fun writeFrame(s: Stream, obj: Map<String, Any?>) =
        s.write(KotlinWire.writeFrameBytes(obj))

    private fun certToMap(c: KotlinWire.CertDict): Map<String, Any?> = mapOf(
        "identity_pub" to c.identity_pub, "device_pub" to c.device_pub,
        "device_name" to c.device_name,
        "enrolled_at" to KotlinWire.PyFloat(c.enrolled_at), "signature" to c.signature)

    private fun certFromJson(o: JSONObject) = KotlinWire.CertDict(
        o.getString("identity_pub"), o.getString("device_pub"),
        o.getString("device_name"), o.getDouble("enrolled_at"), o.getString("signature"))

    /** Mirror of handshake.ts, phone = initiator, stops at accept/refuse.
     *  Runs over an already-open Stream -- TorStream for the phone dialing
     *  its own node over Tor, SocketStream for the BB-5 desk gate dialing a
     *  real node over plain loopback TCP (no Tor). This unifies the desk and
     *  phone AUTH paths behind the one Stream interface KotlinSync already
     *  uses.
     *
     *  Closes the stream itself on Refused/Failed (nothing more will ever
     *  happen on it), but leaves it OPEN on Accepted: the acceptance probe's
     *  write-then-read of {"t":"revocations","revs":[]} is not a fake ping,
     *  it IS the real node's REVOCATIONS phase (hearth/sync.py _session,
     *  responder side: read-then-write, so our probe write unblocks its one
     *  read, and its write back is the same frame KotlinSync.run's own
     *  revocations phase would otherwise send/expect). A caller that wants
     *  to continue straight into KotlinSync.run on this same connection
     *  (BB-5 desk gate, and the eventual phone full-sync path) must get the
     *  still-open, now-just-past-REVOCATIONS stream -- closing it here would
     *  hand KotlinSync.run a dead socket, and redoing the probe there would
     *  send a second REVOCATIONS frame the node never expects, desyncing
     *  every phase after it by one frame (verified against the real node
     *  while building this gate). run(connId, ...) below restores the old
     *  always-closes behavior for the standalone/Tor-heartbeat caller, which
     *  never continues past Accepted anyway. */
    fun runOverStream(stream: Stream, fixture: Fixture, rnd: () -> String = ::randomHex16): HandshakeResult {
        var accepted = false
        try {
            // HELLO
            val myNonce = rnd()
            writeFrame(stream, mapOf("t" to "hello", "cert" to certToMap(fixture.cert), "nonce" to myNonce))
            val peerHello = readFrame(stream)
            if (peerHello.optString("t") != "hello") return HandshakeResult.Failed("hello", "unexpected t=${peerHello.optString("t")}")
            val peerCert = certFromJson(peerHello.getJSONObject("cert"))
            if (!KotlinWire.verifyCert(peerCert)) return HandshakeResult.Failed("hello", "node cert failed verification")

            // AUTH
            writeFrame(stream, mapOf("t" to "auth",
                "sig" to KotlinWire.signRaw(fixture.device_priv, KotlinWire.authBody(peerHello.getString("nonce")))))
            val peerAuth = readFrame(stream)
            if (peerAuth.optString("t") != "auth") return HandshakeResult.Failed("auth", "unexpected t=${peerAuth.optString("t")}")
            if (!KotlinWire.verifyRaw(peerCert.device_pub, peerAuth.getString("sig"), KotlinWire.authBody(myNonce)))
                return HandshakeResult.Failed("auth", "node failed device-key proof")

            // Acceptance probe. The ACCEPTED path (the only path the
            // heartbeat runs operationally -- the phone always presents its
            // valid home-identity cert to its own node) is unambiguous: the
            // node advances to REVOCATIONS and, as responder, reads our
            // frame before writing its own, so write-then-read completes
            // cleanly. The REFUSED path (node writes "refused" then closes)
            // is NOT exercised in normal operation; whether write-then-read
            // reliably surfaces "refused" vs a Failed("io") over Tor/SOCKS on
            // Android is unverified (handshake.ts hit a Windows-loopback RST
            // purge that may not reproduce here). Carried to on-device
            // verification; a future refused-path test would pin it. Do not
            // change handshake.ts.
            writeFrame(stream, mapOf("t" to "revocations", "revs" to emptyList<Any>()))
            val verdict = readFrame(stream)
            val result = when (verdict.optString("t")) {
                "refused" -> HandshakeResult.Refused
                "revocations" ->
                    if (peerCert.identity_pub != fixture.cert.identity_pub)
                        HandshakeResult.Failed("probe", "accepted by a non-home-node identity")
                    else HandshakeResult.Accepted
                else -> HandshakeResult.Failed("probe", "unexpected t=${verdict.optString("t")}")
            }
            accepted = result is HandshakeResult.Accepted
            return result
        } catch (e: Exception) {
            return HandshakeResult.Failed("io", e.toString())
        } finally {
            if (!accepted) stream.close()
        }
    }

    /** HELLO+AUTH only -- no acceptance probe, does not close the stream.
     *  For a caller that is about to continue straight into a post-AUTH
     *  protocol phase on the SAME stream (KotlinSync.run, the BB-5 desk gate
     *  and the eventual phone full-sync path): runOverStream's probe writes
     *  and reads a REAL {"t":"revocations","revs":[]} frame -- it's not a
     *  throwaway ping, it consumes the node's actual once-per-connection
     *  REVOCATIONS phase (see runOverStream's doc comment). Chaining
     *  runOverStream straight into KotlinSync.run (whose own first phase
     *  also sends "revocations") sends that frame TWICE; the real node only
     *  expects it once, and the connection desyncs one phase at a time until
     *  it dies -- reproduced empirically while building this gate (BB-5):
     *  runOverStream returned Accepted, then KotlinSync.run failed with a
     *  mid-session SocketException a couple of frames later.
     *
     *  Accept/refuse determination is intentionally NOT this function's job:
     *  an unknown/revoked peer is refused at the admission check right after
     *  AUTH (hearth/sync.py _session:472-483), strictly before REVOCATIONS,
     *  and KotlinSync.run's own first phase already surfaces that refusal as
     *  SyncResult.Failed("revocations", "refused") -- no separate probe is
     *  needed once the caller is about to run sync right after AUTH anyway.
     *
     *  Throws on a HELLO/AUTH failure; returns the peer's verified cert on
     *  success (callers that need the Accepted/Refused verdict in isolation,
     *  with no follow-on sync, should use runOverStream/run instead). */
    fun authOnlyOverStream(stream: Stream, fixture: Fixture, rnd: () -> String = ::randomHex16): KotlinWire.CertDict {
        val myNonce = rnd()
        writeFrame(stream, mapOf("t" to "hello", "cert" to certToMap(fixture.cert), "nonce" to myNonce))
        val peerHello = readFrame(stream)
        if (peerHello.optString("t") != "hello")
            throw IllegalStateException("hello: unexpected t=${peerHello.optString("t")}")
        val peerCert = certFromJson(peerHello.getJSONObject("cert"))
        if (!KotlinWire.verifyCert(peerCert))
            throw IllegalStateException("hello: node cert failed verification")

        writeFrame(stream, mapOf("t" to "auth",
            "sig" to KotlinWire.signRaw(fixture.device_priv, KotlinWire.authBody(peerHello.getString("nonce")))))
        val peerAuth = readFrame(stream)
        if (peerAuth.optString("t") != "auth")
            throw IllegalStateException("auth: unexpected t=${peerAuth.optString("t")}")
        if (!KotlinWire.verifyRaw(peerCert.device_pub, peerAuth.getString("sig"), KotlinWire.authBody(myNonce)))
            throw IllegalStateException("auth: node failed device-key proof")
        return peerCert
    }

    /** Thin wrapper: dial via TorEngine's bare connId (the phone's own path,
     *  unchanged behavior) by adapting it onto a TorStream and running the
     *  same logic as runOverStream, then closing regardless of verdict --
     *  restoring run()'s original always-closes-at-the-end behavior (this
     *  standalone/Tor caller never continues past Accepted on the same
     *  connection, unlike the BB-5 desk gate). */
    fun run(connId: Int, fixture: Fixture, rnd: () -> String = ::randomHex16): HandshakeResult {
        val stream = TorStream(connId)
        val result = runOverStream(stream, fixture, rnd)
        if (result is HandshakeResult.Accepted) stream.close()
        return result
    }
}
