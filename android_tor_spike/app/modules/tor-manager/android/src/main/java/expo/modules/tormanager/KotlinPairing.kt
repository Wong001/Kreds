package expo.modules.tormanager

import org.bouncycastle.crypto.digests.SHA3Digest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Android first-load pairing (spec 2026-07-22-android-first-load-pairing-
 *  design), Task 3: the phone-side counterpart to hearth's `invitecodec.py`
 *  type-4 ("pair") branch (Task 1) and the `hearth-pair-request`/
 *  `hearth-pair-package` wire frames (Task 2, node.py:2004-2059). No JS
 *  runtime dependency -- decodeLink/installPackage run from the pairing
 *  screen (JVM+Android callable), same posture as KotlinWire/KotlinHandshake. */
object KotlinPairing {
    /** Internal sentinel for "this link/frame is malformed" -- caught at
     *  decodeLink's boundary and turned into a null return, mirroring
     *  hearth's decode() catching struct.error/IndexError into a single
     *  ValueError("malformed invite"). Never escapes this file. */
    private class Malformed : RuntimeException()

    // -- base58 (byte-for-byte port of invitecodec.b58decode) -------------
    private const val B58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val B58_INDEX = IntArray(128) { -1 }.also {
        for (i in B58_ALPHABET.indices) it[B58_ALPHABET[i].code] = i
    }

    private fun b58decode(s: String): ByteArray {
        var n = BigInteger.ZERO
        val base = BigInteger.valueOf(58)
        for (ch in s) {
            val code = ch.code
            val idx = if (code < 128) B58_INDEX[code] else -1
            if (idx < 0) throw Malformed()
            n = n.multiply(base).add(BigInteger.valueOf(idx.toLong()))
        }
        // BigInteger.toByteArray() is minimal two's-complement -- for a
        // non-negative value it only ever prepends a single extra 0x00 (to
        // keep the sign bit clear), never more; strip it if present so the
        // byte count matches Python's n.to_bytes((n.bit_length()+7)//8, "big").
        val body = if (n.signum() == 0) ByteArray(0) else {
            val raw = n.toByteArray()
            if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        }
        var pad = 0
        for (ch in s) { if (ch == '1') pad++ else break }
        return ByteArray(pad) + body
    }

    // -- base32 (RFC 4648, no padding -- matches base64.b32encode(...).rstrip("=")) --
    private const val B32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private fun base32EncodeNoPad(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder()
        var bits = 0
        var value = 0
        for (b in data) {
            value = (value shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(B32_ALPHABET[(value ushr (bits - 5)) and 0x1f])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(B32_ALPHABET[(value shl (5 - bits)) and 0x1f])
        return sb.toString()
    }

    // -- onion_join (invitecodec.onion_join) -------------------------------
    private fun sha3_256(vararg parts: ByteArray): ByteArray {
        val d = SHA3Digest(256)
        for (p in parts) d.update(p, 0, p.size)
        val out = ByteArray(d.digestSize)
        d.doFinal(out, 0)
        return out
    }

    private fun onionJoin(pub: ByteArray, port: Int): String {
        val ver = byteArrayOf(3)
        val chk = sha3_256(".onion checksum".toByteArray(Charsets.UTF_8), pub, ver).copyOfRange(0, 2)
        val b32 = base32EncodeNoPad(pub + chk + ver).lowercase()
        return "$b32.onion:$port"
    }

    // -- unpack_addr (invitecodec.unpack_addr) -----------------------------
    /** Returns (addr-or-null, new-offset); throws Malformed on any
     *  truncation/unknown-flag, exactly mirroring unpack_addr's ValueErrors. */
    private fun unpackAddr(b: ByteArray, offset: Int): Pair<String?, Int> {
        var off = offset
        if (b.size - off < 1) throw Malformed()
        val flag = b[off].toInt() and 0xff; off += 1
        return when (flag) {
            0 -> {
                if (b.size - off < 34) throw Malformed()
                val pub = b.copyOfRange(off, off + 32); off += 32
                val port = ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff); off += 2
                onionJoin(pub, port) to off
            }
            1 -> {
                if (b.size - off < 2) throw Malformed()
                val n = ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff); off += 2
                if (b.size - off < n) throw Malformed()
                val s = String(b, off, n, Charsets.UTF_8); off += n
                s to off
            }
            2 -> null to off
            else -> throw Malformed()
        }
    }

    /** Decodes a phone first-load pairing link (hearth invitecodec.encode_pair's
     *  type-4 output) into (gossip addr, pairing code), or null if `link` is
     *  not a well-formed pair link -- NEVER throws (malformed input, wrong
     *  version/type byte, truncated body, bad base58 chars, and a flag=2
     *  "no address" encoding -- nonsensical for a pairing link, whose whole
     *  purpose is carrying a dialable address -- all fall through to null).
     *  Byte-parity with hearth invitecodec.decode's type==4 branch. */
    fun decodeLink(link: String): Pair<String, String>? = try {
        val raw = b58decode(link)
        if (raw.size < 2 || raw[0] != 1.toByte()) throw Malformed()
        val typ = raw[1].toInt() and 0xff
        if (typ != 4) throw Malformed()
        val body = raw.copyOfRange(2, raw.size)
        val (addr, off0) = unpackAddr(body, 0)
        if (addr == null) throw Malformed()
        if (body.size - off0 < 2) throw Malformed()
        val clen = ((body[off0].toInt() and 0xff) shl 8) or (body[off0 + 1].toInt() and 0xff)
        val off1 = off0 + 2
        if (body.size - off1 < clen) throw Malformed()
        val code = String(body, off1, clen, Charsets.UTF_8)
        addr to code
    } catch (e: Exception) { null }

    /** The `hearth-pair-request` wire frame (Task 2 report's "Wire contract",
     *  client -> server): exactly 5 keys, PROTOCOL reused from KotlinWire (the
     *  same constant every other Kotlin wire frame builder uses). */
    fun buildRequestFrame(devicePub: String, deviceName: String, code: String): Map<String, Any?> = mapOf(
        "t" to "hearth-pair-request",
        "protocol" to KotlinWire.PROTOCOL,
        "device_pub" to devicePub,
        "device_name" to deviceName,
        "code" to code,
    )

    /** Installs a `hearth-pair-package` (node.accept_pairing's exact return
     *  shape, node.py:2028-2036) into `store`, mirroring `pair_install`'s
     *  store-mutation semantics (node.py:2039-2059) as closely as the
     *  phone's SyncStore API allows:
     *
     *  - `t` must be "hearth-pair-package" -- else IllegalArgumentException
     *    (pair_install raises ValueError("not a pairing package") for the
     *    same case).
     *  - `store.addIdentity(cert.identity_pub)` -- mirrors
     *    `node.store.add_identity(device.identity_pub, is_self=True)`. The
     *    phone's SyncStore.addIdentity has no `is_self` flag at all (see
     *    SyncStore.kt): the store never models "which identity is mine" as
     *    its own concept, so this is an ordinary addIdentity call, same as
     *    for a friend. "Which one is self" lives entirely in the returned
     *    PairingStore.Identity / the KotlinHandshake.Fixture derived from it.
     *  - "save own device view w/ cert" (`node.store.save_views(identity_pub,
     *    {device_pub: DeviceView(cert)})`) has NO SyncStore equivalent:
     *    SyncStore.deviceViews() is read-only, derived from stored messages'
     *    verified cert.device_pub at ingest time (see its doc comment) -- there
     *    is no table to write a view into directly. The "own device view"
     *    this phone needs (its own cert, presented at every HELLO) is exactly
     *    the returned Identity.cert; the caller persists it via
     *    PairingStore.save(...) on the returned record.
     *  - `store.addIdentity(ident)` per `friends` entry -- direct mirror of
     *    `node.store.add_identity(ident)` in the pair_install loop.
     *  - `peers` + `my_addr` (`node.store.add_peer(address, identity_pub)`)
     *    -- DELIBERATELY DROPPED for `peers`: the phone's SyncStore has no
     *    peer table / addPeer method at all (grepped SyncStore.kt -- none
     *    exists), and the phone only ever syncs with its own home node
     *    today, so friends' addresses have no consumer yet. `my_addr` (the
     *    home node's own address) is NOT dropped -- it's carried out via the
     *    returned Identity.onion_addr, which IS this phone's one peer record
     *    (its home node's dial address, used by KotlinHandshake/SyncRunner).
     *
     *  `deviceName` is accepted for interface-shape parity with
     *  buildRequestFrame's params (the pairing screen has it in scope from
     *  the same ceremony) but is not independently validated here -- the
     *  authoritative device_name is `cert.device_name` (the desktop echoes
     *  back exactly what buildRequestFrame sent, node.py:2022-2023).
     *
     *  VERIFY BEFORE MUTATING (post-review fix): mirrors hearth's own
     *  `pair_install` -> `DeviceKeys.install` (identity.py:295-303), which
     *  runs its checks BEFORE `pair_install`'s store writes
     *  (node.py:2044-2050). Three checks, in this order, all before any
     *  `store.addIdentity` call:
     *   1. `KotlinWire.verifyCert(cert)` -- the cert's own signature
     *      (already used for exactly this CertDict shape at
     *      KotlinHandshake.kt:97/169).
     *   2. `cert.device_pub == devicePub` -- the package must be enrolling
     *      THIS device's own keypair (the one `devicePriv`/`devicePub`
     *      belong to), not some other device's cert.
     *   3. `identityPriv` actually derives `cert.identity_pub` (Ed25519
     *      public-key derivation via BouncyCastle, the same idiom
     *      EncKeys.kt:35-41 uses for the X25519 analog) -- a cert can be
     *      validly signed by a DIFFERENT identity than the one whose
     *      "identity_priv" the package hands us; this catches that.
     *  Each failure throws IllegalArgumentException with a distinct
     *  message. Any malformed/missing-field JSON (bad `t`-adjacent
     *  structure aside, which keeps its own "not a pairing package"
     *  message) surfaces as IllegalArgumentException("bad package") rather
     *  than a raw org.json.JSONException, so callers (Task 4's
     *  runCeremony) only ever need to catch one exception family. */
    fun installPackage(
        store: SyncStore,
        pkgJson: String,
        devicePriv: String,
        devicePub: String,
        deviceName: String,
    ): PairingStore.Identity {
        val pkg = try { JSONObject(pkgJson) } catch (e: JSONException) {
            throw IllegalArgumentException("bad package")
        }
        if (pkg.optString("t") != "hearth-pair-package")
            throw IllegalArgumentException("not a pairing package")

        val cert: KotlinWire.CertDict
        val identityPriv: String
        val friends: List<String>
        val onionAddr: String
        try {
            val certJson = pkg.getJSONObject("cert")
            cert = KotlinWire.CertDict(
                certJson.getString("identity_pub"), certJson.getString("device_pub"),
                certJson.getString("device_name"), certJson.getDouble("enrolled_at"),
                certJson.getString("signature"))
            identityPriv = pkg.getString("identity_priv")
            val friendsArr = pkg.optJSONArray("friends")
            friends = if (friendsArr != null) (0 until friendsArr.length()).map { friendsArr.getString(it) }
                      else emptyList()
            onionAddr = pkg.optString("my_addr", "")
        } catch (e: JSONException) {
            throw IllegalArgumentException("bad package")
        }

        // -- verify BEFORE mutating (see doc above) --------------------------
        if (!KotlinWire.verifyCert(cert))
            throw IllegalArgumentException("cert signature invalid")
        if (cert.device_pub != devicePub)
            throw IllegalArgumentException("cert device_pub does not match this device's device_pub")
        if (!identityPrivMatchesPub(identityPriv, cert.identity_pub))
            throw IllegalArgumentException("identity_priv does not derive cert.identity_pub")

        store.addIdentity(cert.identity_pub)
        for (f in friends) store.addIdentity(f)
        // peers: intentionally dropped (see doc above)

        return PairingStore.Identity(devicePriv, devicePub, cert, identityPriv, onionAddr)
    }

    /** Same idiom as EncKeys.pubMatchesPriv/derivePub (X25519 analog): any
     *  derivation failure (malformed hex, wrong length, etc.) is treated as
     *  "does not match", not propagated as a crash -- installPackage turns
     *  a `false` here into its own IllegalArgumentException. */
    private fun identityPrivMatchesPub(privHex: String, pubHex: String): Boolean = try {
        val derived = KotlinWire.toHex(
            Ed25519PrivateKeyParameters(KotlinWire.fromHex(privHex), 0).generatePublicKey().encoded)
        derived == pubHex
    } catch (e: Exception) { false }

    // -- frame I/O (Task 4) ------------------------------------------------
    // Mirrors KotlinHandshake's and KotlinSync's own private readFrame/
    // writeFrame byte-for-byte (4-byte big-endian length prefix, ASCII-only
    // body) rather than a shared call -- neither of those is a public
    // function this file could call instead, and the brief for this task is
    // "same length-prefix mechanics, do NOT hand-roll [something different]",
    // not "extract a shared helper" (an out-of-scope refactor of two other
    // files this task doesn't touch).
    private fun writeFrame(s: Stream, obj: Map<String, Any?>) =
        s.write(KotlinWire.writeFrameBytes(obj))

    private fun readFrame(s: Stream): JSONObject {
        val header = s.readExactSync(4)
        val n = (((header[0].toLong() and 0xff) shl 24) or ((header[1].toLong() and 0xff) shl 16) or
                 ((header[2].toLong() and 0xff) shl 8) or (header[3].toLong() and 0xff))
        require(n <= KotlinWire.MAX_FRAME) { "frame too large" }
        val body = s.readExactSync(n.toInt())
        for (b in body) require((b.toInt() and 0xff) <= 0x7e) { "non-ascii frame byte" }
        return JSONObject(String(body, Charsets.US_ASCII))
    }

    /** Task 4: the phone-side pairing CEREMONY -- decodeLink -> generate a
     *  fresh device Ed25519 keypair -> dial the link's address -> send
     *  `hearth-pair-request` -> read exactly ONE reply frame (bound by
     *  `timeoutMs`) -> dispatch on the reply, mirroring the Task 2 wire
     *  contract (client sends one request, server sends EXACTLY ONE of
     *  hearth-pair-package / pair-denied / pair-expired, then closes).
     *
     *  `store`/`dir` are explicit params rather than read off a Context, so
     *  this stays JVM-testable with no Android runtime -- the bridge
     *  (TorManagerModule.pairWithNode) passes the real SqliteSyncStore and
     *  ctx.filesDir; tests pass an InMemorySyncStore and a temp dir.
     *
     *  `dial` is not called at all for a malformed link -- decodeLink's null
     *  short-circuits to BadLink before `dial` is ever referenced, so a
     *  caller can prove "no network attempt on a bad link" by asserting
     *  their dial lambda never ran.
     *
     *  The persisted identity's onion_addr is PINNED to the address this
     *  ceremony actually dialed (the link's own addr, from decodeLink) --
     *  NOT installPackage's pkg.my_addr passthrough. The link is what the
     *  user/QR code vouched for; a package's self-reported my_addr is only
     *  ever server say-so (could be stale, misconfigured, or -- since the
     *  ceremony has not yet proven this peer controls the identity the cert
     *  names, only that it answered on the dialed address -- untrustworthy)
     *  and every subsequent dial to this identity (KotlinHandshake/
     *  SyncRunner) should keep using the address this ceremony itself
     *  proved reachable.
     *
     *  Bounding: `timeoutMs` wraps the write+read of the single reply frame
     *  in a Future.get(timeoutMs) (the same bounded-blocking-call idiom
     *  ImageDecodeClient uses for its isolated-process IPC) -- Stream has no
     *  built-in per-call timeout knob, so this is the generic way to bound
     *  an arbitrary Stream impl (TorStream, a JVM test fake, ...) uniformly.
     *  A timeout, any other I/O failure, an unrecognized reply `t`, or a
     *  rejected (IllegalArgumentException) package all fold to
     *  Unreachable -- the ceremony has exactly three "good" outcomes
     *  (Linked/Denied/Expired) and everything else is "could not complete
     *  the ceremony", with `reason` carrying detail for logging/debugging
     *  only (never parsed by a caller). */
    sealed class CeremonyResult {
        data class Linked(val identity: PairingStore.Identity) : CeremonyResult()
        object Denied : CeremonyResult()
        object Expired : CeremonyResult()
        data class Unreachable(val reason: String) : CeremonyResult()
        object BadLink : CeremonyResult()
    }

    /** `onProgress` (post-review addition): purely additive observability,
     *  same posture as KotlinSync.run's own `onProgress` callback -- default
     *  null, exceptions from the caller's lambda are NOT swallowed here
     *  (unlike KotlinSync's terminal-outcome protection, there is no
     *  "already succeeded, don't let a side-channel flip the result" risk:
     *  both call sites happen strictly BEFORE the outcome is known). Fires
     *  "dialing" right before `dial` runs (never fires at all for a
     *  malformed link -- BadLink returns first) and "waiting" right after
     *  the request frame is written, before the (possibly multi-minute)
     *  blocking read for the human's Accept/Deny click on the desktop --
     *  see PAIR_TIMEOUT_MS's doc (TorManagerModule.kt) for why that wait
     *  can legitimately run for most of 10 minutes. Runs on whatever thread
     *  calls runCeremony ("dialing") and on the executor's background
     *  thread ("waiting") -- the bridge's lambda (sendEvent) is safe to
     *  call from either. */
    fun runCeremony(
        dial: (host: String, port: Int) -> Stream,
        link: String,
        deviceName: String,
        timeoutMs: Long,
        store: SyncStore,
        dir: File,
        onProgress: ((String) -> Unit)? = null,
    ): CeremonyResult {
        val (addr, code) = decodeLink(link) ?: return CeremonyResult.BadLink
        val (host, port) = KotlinHandshake.splitAddr(addr)

        onProgress?.invoke("dialing")
        val stream = try {
            dial(host, port)
        } catch (e: Exception) {
            return CeremonyResult.Unreachable("dial: ${e.message}")
        }

        val devicePriv = Ed25519PrivateKeyParameters(SecureRandom())
        val devicePrivHex = KotlinWire.toHex(devicePriv.encoded)
        val devicePubHex = KotlinWire.toHex(devicePriv.generatePublicKey().encoded)
        val requestFrame = buildRequestFrame(devicePubHex, deviceName, code)

        val executor = Executors.newSingleThreadExecutor()
        try {
            val reply: JSONObject = try {
                executor.submit<JSONObject> {
                    writeFrame(stream, requestFrame)
                    onProgress?.invoke("waiting")
                    readFrame(stream)
                }.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                return CeremonyResult.Unreachable("timeout")
            } catch (e: Exception) {
                return CeremonyResult.Unreachable("io: ${e.message}")
            }

            return when (reply.optString("t")) {
                "hearth-pair-package" -> {
                    val identity = try {
                        installPackage(store, reply.toString(), devicePrivHex, devicePubHex, deviceName)
                    } catch (e: IllegalArgumentException) {
                        return CeremonyResult.Unreachable(e.message ?: "bad package")
                    }
                    val pinned = identity.copy(onion_addr = addr)
                    PairingStore.save(dir, pinned)
                    CeremonyResult.Linked(pinned)
                }
                "pair-denied" -> CeremonyResult.Denied
                "pair-expired" -> CeremonyResult.Expired
                else -> CeremonyResult.Unreachable("unexpected reply t=${reply.optString("t")}")
            }
        } finally {
            executor.shutdownNow()
            try { stream.close() } catch (e: Exception) {}
        }
    }
}
