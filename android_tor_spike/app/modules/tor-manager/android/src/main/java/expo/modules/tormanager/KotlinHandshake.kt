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
        /** RESPONDER-only success (respondHandshake): the peer's verified
         *  CertDict, so the caller (the sync responder, arc 1 Task 3) can
         *  scope give-side entitlement on the AUTHENTICATED identity, never
         *  a frame claim. Distinct from Accepted -- that is the INITIATOR's
         *  node-trust verdict from runOverStream's acceptance probe (a
         *  phase respondHandshake never runs); completing AUTH here means
         *  "authenticated", not yet "entitled to anything". */
        data class Ok(val peerCert: KotlinWire.CertDict) : HandshakeResult()
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

    /** RESPONDER counterpart to runOverStream (arc 1, kotlin-gossip-server
     *  Task 2) -- the phone answering an inbound dial instead of making
     *  one. Mirrors hearth's _session responder half for HELLO+AUTH
     *  (sync.py:590-641): `_swap`'s initiator=False branch reads the peer's
     *  frame first and writes ours after, for EACH phase (read-then-write,
     *  already documented above runOverStream at :76) -- so HELLO is
     *  read-peer/write-ours, and AUTH is independently read-peer/write-ours
     *  again (not one combined swap). This works because by the time AUTH
     *  is read, both nonces are already known to both sides from the HELLO
     *  phase, so there is no ordering dependency across the read/write
     *  split.
     *
     *  The stranger-refusal gate (hearth sync.py:630-632) runs AFTER a full
     *  HELLO+AUTH round -- NOT right after the peer's HELLO cert verifies
     *  (a review-caught interop bug in an earlier version of this function
     *  moved it too early). This placement is load-bearing, not cosmetic:
     *  hearth's real initiator only ever recognizes {"t":"refused"} as
     *  PeerRefused in ONE spot, the REVOCATIONS-phase reply (sync.py:
     *  657-662) -- an initiator that instead got "refused" back in place of
     *  the HELLO or AUTH reply would just fail generically ("bad hello" /
     *  unexpected-t), not see a clean refusal. hearth's responder writes
     *  the refused frame as a bare `write_frame` right after AUTH succeeds
     *  and BEFORE it would otherwise enter the REVOCATIONS swap (sync.py:
     *  630-632, no read precedes this write) -- from the wire's point of
     *  view this frame lands exactly where the REVOCATIONS-phase reply
     *  would have gone, which is what makes a real initiator's `_swap`
     *  (write REVOCATIONS-request, then read) receive it and correctly
     *  raise PeerRefused. So here too: complete the full HELLO+AUTH
     *  exchange for ANY cryptographically-valid peer cert and device
     *  signature FIRST, and only then check `isKnown` and write refused --
     *  this function stops at that point (arc 1's responder does not model
     *  REVOCATIONS at all yet; that is Task 3's job), but the refused frame
     *  it writes here is byte-identical to, and lands in the same wire slot
     *  as, hearth's own.
     *
     *  A bad CERT signature or a bad device-key AUTH proof (as opposed to a
     *  cryptographically-valid but UNKNOWN identity) is NOT given a refused
     *  frame at all, matching hearth exactly (sync.py:606-607 and :615-616
     *  both raise with no write) and runOverStream's own analogous cases
     *  just below (no refused frame for "node cert failed verification" or
     *  "node failed device-key proof" either).
     *
     *  hearth ALSO refuses post-AUTH when the peer's own device is revoked
     *  in our views (sync.py:635-641, `peer_identity != own` guarded). NOW
     *  MIRRORED (phone-onion-reachability Task 5, closing the arc-1
     *  whole-branch-review blocking finding): the revoked-device gate below,
     *  same wire slot as the stranger gate just above it, backed by the
     *  `isRevoked` lambda (SyncStore.isRevokedDevice, plumbed by the store
     *  primitives/wire-ingest work of Tasks 2-3). Was previously tracked as
     *  a parity follow-up (see task-2-report.md) when the store had no
     *  revocation state to consult at all.
     *
     *  Never closes the stream on any path (including refusal) -- unlike
     *  runOverStream, whose caller (the standalone Tor heartbeat) never
     *  continues past the verdict on the same connection. Here the caller
     *  (GossipServer, Task 4) always owns the accepted socket's lifecycle
     *  in its own finally, exactly mirroring hearth: the responder raises
     *  on refusal/failure and the OUTER connection handler's finally closes
     *  (sync.py:560-567), not the responder itself. */
    fun respondHandshake(
        stream: Stream, fixture: Fixture, isKnown: (identityPub: String) -> Boolean,
        // Revoked-device check (phone-onion-reachability Task 5), threaded
        // the same way as `isKnown` -- a lambda, not the store directly, so
        // this function stays store-agnostic. Defaults to "never revoked" so
        // every pre-existing call site/test that doesn't care about
        // revocation compiles and behaves identically unchanged.
        isRevoked: (devicePub: String) -> Boolean = { false },
        rnd: () -> String = ::randomHex16,
    ): HandshakeResult {
        // HELLO -- read peer's first.
        val peerHello = readFrame(stream)
        if (peerHello.optString("t") != "hello") return HandshakeResult.Failed("hello", "unexpected t=${peerHello.optString("t")}")
        val peerCert = certFromJson(peerHello.getJSONObject("cert"))
        if (!KotlinWire.verifyCert(peerCert)) return HandshakeResult.Failed("hello", "peer cert failed verification")
        val myNonce = rnd()
        writeFrame(stream, mapOf("t" to "hello", "cert" to certToMap(fixture.cert), "nonce" to myNonce))

        // AUTH -- read peer's proof over OUR nonce first, then prove
        // ourselves over THEIRS. Byte-identical authBody payload to
        // runOverStream/hearth's _auth_body, directions swapped: we verify
        // the peer's sig over myNonce (they read it from our HELLO above),
        // and we sign peerHello's nonce (which we read at the top).
        val peerAuth = readFrame(stream)
        if (peerAuth.optString("t") != "auth") return HandshakeResult.Failed("auth", "unexpected t=${peerAuth.optString("t")}")
        if (!KotlinWire.verifyRaw(peerCert.device_pub, peerAuth.getString("sig"), KotlinWire.authBody(myNonce)))
            return HandshakeResult.Failed("auth", "peer failed device-key proof")
        writeFrame(stream, mapOf("t" to "auth",
            "sig" to KotlinWire.signRaw(fixture.device_priv, KotlinWire.authBody(peerHello.getString("nonce")))))

        // Stranger gate -- AFTER AUTH, matching hearth's wire position
        // exactly (see doc comment above): a bare write, no read first,
        // landing in the slot a real initiator's REVOCATIONS-swap read
        // expects and recognizes as PeerRefused.
        if (!isKnown(peerCert.identity_pub)) {
            writeFrame(stream, mapOf("t" to "refused"))
            return HandshakeResult.Failed("auth", "refused")
        }

        // Revoked-device gate (phone-onion-reachability Task 5, mirrors
        // sync.py:637-641) -- SAME wire slot as the stranger gate just
        // above (a bare write, no read first, landing where a real
        // initiator's REVOCATIONS-swap read expects it): a KNOWN peer whose
        // DEVICE we already hold as revoked (`isRevoked`, backed by
        // SyncStore.isRevokedDevice) is refused here too, before this
        // device ever answers REVOCATIONS/DEFRIENDS/HAVE/MESSAGES/BLOBS for
        // it. Own-identity peers are EXEMPT (guarded by
        // `peerCert.identity_pub != fixture.cert.identity_pub`), exactly
        // mirroring hearth's own comment at sync.py:633-636: this is the
        // very channel a sibling device learns of ITS OWN revocation over
        // (KotlinSync.serve's REVOCATIONS phase -> SelfRevoked -> wipe), so
        // it must never be refused admission here for being the target of a
        // revocation it doesn't yet know about.
        if (peerCert.identity_pub != fixture.cert.identity_pub && isRevoked(peerCert.device_pub)) {
            writeFrame(stream, mapOf("t" to "refused"))
            return HandshakeResult.Failed("auth", "revoked")
        }

        return HandshakeResult.Ok(peerCert)
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
