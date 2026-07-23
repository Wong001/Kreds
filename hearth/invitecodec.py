"""Compact friend-invite wire codec (spec 2026-07-10-compact-invite).
The three ceremony messages (invite/response/final) pack to base58 instead
of JSON. Nonces/certs cross this API as hex strings / dicts (the ceremony
in node.py stays hex-and-EnrollmentCert internally); this module converts
to raw bytes only on the wire. No dependency - base58 is hand-rolled.

Addresses cross this API as the plain "host:port" string the ceremony
already uses (node.py never splits/joins onion pubkeys itself - see
pack_addr/unpack_addr): a real onion address reconstructs from its 32-byte
pubkey (the actual size win), a non-onion address (a first-class
gossip_addr too - see hearth/runner.py's tor=False path and sync.py's
_is_onion dual-stack handling) falls back to a length-prefixed string."""
import base64
import hashlib
import struct

_B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

def b58encode(b: bytes) -> str:
    n = int.from_bytes(b, "big")
    out = ""
    while n > 0:
        n, r = divmod(n, 58)
        out = _B58[r] + out
    pad = len(b) - len(b.lstrip(b"\x00"))
    return "1" * pad + out

def b58decode(s: str) -> bytes:
    n = 0
    for c in s:
        i = _B58.find(c)
        if i < 0:
            raise ValueError("bad base58 char")
        n = n * 58 + i
    body = n.to_bytes((n.bit_length() + 7) // 8, "big") if n else b""
    pad = len(s) - len(s.lstrip("1"))
    return b"\x00" * pad + body

def onion_split(addr: str):
    host, _, port = addr.partition(":")
    b32 = host[:-6]                                   # strip ".onion"
    raw = base64.b32decode(b32.upper() + "=" * ((8 - len(b32) % 8) % 8))
    return raw[:32], int(port)

def onion_join(pub: bytes, port: int) -> str:
    ver = b"\x03"
    chk = hashlib.sha3_256(b".onion checksum" + pub + ver).digest()[:2]
    b32 = base64.b32encode(pub + chk + ver).decode().lower().rstrip("=")
    return f"{b32}.onion:{port}"

def fingerprint(identity_pub_hex: str) -> str:
    pre = bytes.fromhex(identity_pub_hex)[:4]
    return base64.b32encode(pre).decode().rstrip("=")[:4]

def fp_from_prefix(id_prefix_hex: str) -> str:
    """Same base32 derivation as fingerprint(), but from an already-carried
    4-byte id_prefix (e.g. an invite's id_prefix field) instead of a full
    identity pubkey -- lets /api/friend/add show the peer's fingerprint
    without needing their full identity_pub."""
    return base64.b32encode(bytes.fromhex(id_prefix_hex)).decode().rstrip("=")[:4]

def pack_addr(addr) -> bytes:
    """Pack a gossip address. A real Tor v3 onion address (the production
    case - run_node's tor=True path in hearth/runner.py) reconstructs from
    its 32-byte pubkey; that's the actual size win this codec exists for.
    A non-onion "host:port" address is ALSO a first-class gossip_addr in
    this system (run_node's tor=False dev/LAN path, plus sync.py's
    TcpTransport / _is_onion dual-stack handling) but has no fixed-size
    form, so it's carried as a length-prefixed UTF-8 string instead. A
    missing address (gossip_addr never set) packs to a single flag byte,
    mirroring the old JSON encoding's `null`. A 1-byte flag selects which
    of the three; all round-trip exactly via unpack_addr."""
    if not addr:
        return bytes([2])
    host = addr.rsplit(":", 1)[0]
    if host.endswith(".onion"):
        pub, port = onion_split(addr)
        return bytes([0]) + pub + struct.pack(">H", port)
    raw = addr.encode("utf-8")
    if len(raw) > 0xFFFF:
        raise ValueError("address too long")
    return bytes([1]) + struct.pack(">H", len(raw)) + raw

def unpack_addr(b: bytes, off: int):
    if len(b) - off < 1:
        raise ValueError("truncated address")
    flag = b[off]; off += 1
    if flag == 0:
        if len(b) - off < 34:
            raise ValueError("truncated onion address")
        pub = b[off:off+32]; off += 32
        port = struct.unpack(">H", b[off:off+2])[0]; off += 2
        return onion_join(pub, port), off
    if flag == 1:
        if len(b) - off < 2:
            raise ValueError("truncated address length")
        n = struct.unpack(">H", b[off:off+2])[0]; off += 2
        if len(b) - off < n:
            raise ValueError("truncated address")
        return b[off:off+n].decode("utf-8"), off + n
    if flag == 2:
        return None, off
    raise ValueError("unknown address encoding")

def pack_cert(c: dict) -> bytes:
    name = c["device_name"].encode("utf-8")
    return (bytes.fromhex(c["identity_pub"]) + bytes.fromhex(c["device_pub"])
            + struct.pack(">d", float(c["enrolled_at"]))
            + bytes.fromhex(c["signature"])
            + struct.pack(">H", len(name)) + name)

def unpack_cert(b: bytes, off: int):
    # Check fixed-width portion exists: 32+32+8+64+2 = 138 bytes
    if len(b) - off < 138:
        raise ValueError("truncated cert")
    ident = b[off:off+32].hex();      off += 32
    dev = b[off:off+32].hex();        off += 32
    enrolled = struct.unpack(">d", b[off:off+8])[0]; off += 8
    sig = b[off:off+64].hex();        off += 64
    nl = struct.unpack(">H", b[off:off+2])[0]; off += 2
    # Check name buffer exists
    if off + nl > len(b):
        raise ValueError("truncated cert name")
    name = b[off:off+nl].decode("utf-8"); off += nl
    return {"identity_pub": ident, "device_pub": dev, "device_name": name,
            "enrolled_at": enrolled, "signature": sig}, off

_VER = 0x01
def _wrap(typ: int, body: bytes) -> str:
    return b58encode(bytes([_VER, typ]) + body)

def encode_invite(id_prefix_hex, addr, nonce_hex, expiry) -> str:
    body = (bytes.fromhex(id_prefix_hex) + pack_addr(addr)
            + bytes.fromhex(nonce_hex) + struct.pack(">I", int(expiry)))
    return _wrap(1, body)

def encode_response(addr, nonce_hex, peer_nonce_hex, sig_hex, cert) -> str:
    body = (pack_addr(addr) + bytes.fromhex(nonce_hex)
            + bytes.fromhex(peer_nonce_hex) + bytes.fromhex(sig_hex) + pack_cert(cert))
    return _wrap(2, body)

def encode_final(nonce_hex, sig_hex, cert) -> str:
    body = bytes.fromhex(nonce_hex) + bytes.fromhex(sig_hex) + pack_cert(cert)
    return _wrap(3, body)

def encode_pair(addr, code: str) -> str:
    """The android first-load pairing link (spec 2026-07-22-android-
    first-load-pairing-design): the desktop's own gossip address plus a
    short-lived pairing code (hearth/pairingcodes.py), packed together
    so a phone's QR scan/typed entry carries both the "where" and the
    "one-time authorization" in one string. addr uses the same
    pack_addr encoding (and the same None/onion/plain fallback) as
    every other message here; code is length-prefixed UTF-8, same
    convention as pack_cert's device_name field."""
    code_b = code.encode("utf-8")
    if len(code_b) > 0xFFFF:
        raise ValueError("code too long")
    body = pack_addr(addr) + struct.pack(">H", len(code_b)) + code_b
    return _wrap(4, body)

def decode(code: str):
    raw = b58decode(code)
    if len(raw) < 2 or raw[0] != _VER:
        raise ValueError("unrecognized invite")
    typ, body = raw[1], raw[2:]
    try:
        if typ == 1:
            if len(body) < 4:
                raise ValueError("truncated invite")
            id_prefix = body[0:4].hex()
            addr, off = unpack_addr(body, 4)
            if len(body) - off < 20:            # nonce(16) + expiry(4)
                raise ValueError("truncated invite")
            nonce = body[off:off+16].hex(); off += 16
            expiry = struct.unpack(">I", body[off:off+4])[0]
            return "invite", {"id_prefix": id_prefix, "addr": addr,
                              "nonce": nonce, "expiry": expiry}
        if typ == 2:
            addr, off = unpack_addr(body, 0)
            if len(body) - off < 96:            # nonce(16)+peer_nonce(16)+sig(64)
                raise ValueError("truncated response")
            nonce = body[off:off+16].hex(); off += 16
            peer = body[off:off+16].hex(); off += 16
            sig = body[off:off+64].hex(); off += 64
            cert, _ = unpack_cert(body, off)
            return "response", {"addr": addr, "nonce": nonce,
                                "peer_nonce": peer, "sig": sig, "cert": cert}
        if typ == 3:
            if len(body) < 218:
                raise ValueError("truncated final")
            nonce = body[0:16].hex(); sig = body[16:80].hex()
            cert, _ = unpack_cert(body, 80)
            return "final", {"nonce": nonce, "sig": sig, "cert": cert}
        if typ == 4:
            addr, off = unpack_addr(body, 0)
            if len(body) - off < 2:
                raise ValueError("truncated pair code length")
            clen = struct.unpack(">H", body[off:off+2])[0]; off += 2
            if len(body) - off < clen:
                raise ValueError("truncated pair code")
            code = body[off:off+clen].decode("utf-8")
            # A "pair" result is a 3-tuple (not the ("type", {dict})
            # shape the other three messages use) -- it carries exactly
            # two scalar fields, no nested cert/dict worth naming.
            return "pair", addr, code
        raise ValueError("unknown message type")
    except (struct.error, IndexError) as e:
        raise ValueError("malformed invite") from e
