package expo.modules.tormanager

import org.bouncycastle.crypto.digests.SHA3Digest
import org.json.JSONObject
import java.math.BigInteger

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
     *  NOTE (deferred, flagged not silently skipped): hearth's own
     *  `pair_install` -> `DeviceKeys.install` (identity.py:295-303) verifies
     *  `cert.verify()`, `cert.device_pub == self.device_pub`, and that
     *  `identity_priv` actually derives `cert.identity_pub`, before
     *  accepting a package. This Kotlin mirror does not repeat those checks
     *  (out of Task 3's brief scope) -- a malformed/forged package would
     *  currently install without complaint here, and only fail later, the
     *  first time this identity's cert is presented at a real HELLO
     *  (KotlinHandshake.verifyCert). Fail-eventually, not fail-fast; carried
     *  as a follow-up. */
    fun installPackage(
        store: SyncStore,
        pkgJson: String,
        devicePriv: String,
        devicePub: String,
        deviceName: String,
    ): PairingStore.Identity {
        val pkg = JSONObject(pkgJson)
        if (pkg.optString("t") != "hearth-pair-package")
            throw IllegalArgumentException("not a pairing package")

        val certJson = pkg.getJSONObject("cert")
        val cert = KotlinWire.CertDict(
            certJson.getString("identity_pub"), certJson.getString("device_pub"),
            certJson.getString("device_name"), certJson.getDouble("enrolled_at"),
            certJson.getString("signature"))
        val identityPriv = pkg.getString("identity_priv")

        store.addIdentity(cert.identity_pub)

        val friends = pkg.optJSONArray("friends")
        if (friends != null) for (i in 0 until friends.length()) store.addIdentity(friends.getString(i))

        // peers: intentionally dropped (see doc above)
        val onionAddr = pkg.optString("my_addr", "")

        return PairingStore.Identity(devicePriv, devicePub, cert, identityPriv, onionAddr)
    }
}
