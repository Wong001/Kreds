"""Compact friend-invite wire codec (spec 2026-07-10-compact-invite).
The three ceremony messages (invite/response/final) pack to base58 instead
of JSON. Nonces/certs cross this API as hex strings / dicts (the ceremony
in node.py stays hex-and-EnrollmentCert internally); this module converts
to raw bytes only on the wire. No dependency - base58 is hand-rolled."""
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

def encode_invite(id_prefix_hex, onion_pub, port, nonce_hex, expiry) -> str:
    body = (bytes.fromhex(id_prefix_hex) + onion_pub + struct.pack(">H", port)
            + bytes.fromhex(nonce_hex) + struct.pack(">I", int(expiry)))
    return _wrap(1, body)

def encode_response(onion_pub, port, nonce_hex, peer_nonce_hex, sig_hex, cert) -> str:
    body = (onion_pub + struct.pack(">H", port) + bytes.fromhex(nonce_hex)
            + bytes.fromhex(peer_nonce_hex) + bytes.fromhex(sig_hex) + pack_cert(cert))
    return _wrap(2, body)

def encode_final(nonce_hex, sig_hex, cert) -> str:
    body = bytes.fromhex(nonce_hex) + bytes.fromhex(sig_hex) + pack_cert(cert)
    return _wrap(3, body)

def decode(code: str):
    raw = b58decode(code)
    if len(raw) < 2 or raw[0] != _VER:
        raise ValueError("unrecognized invite")
    typ, body = raw[1], raw[2:]
    try:
        if typ == 1:
            if len(body) < 58:
                raise ValueError("truncated invite")
            return "invite", {
                "id_prefix": body[0:4].hex(),
                "addr": onion_join(body[4:36], struct.unpack(">H", body[36:38])[0]),
                "nonce": body[38:54].hex(),
                "expiry": struct.unpack(">I", body[54:58])[0]}
        if typ == 2:
            if len(body) < 268:
                raise ValueError("truncated response")
            pub = body[0:32]; port = struct.unpack(">H", body[32:34])[0]
            nonce = body[34:50].hex(); peer = body[50:66].hex(); sig = body[66:130].hex()
            cert, _ = unpack_cert(body, 130)
            return "response", {"addr": onion_join(pub, port), "nonce": nonce,
                                "peer_nonce": peer, "sig": sig, "cert": cert}
        if typ == 3:
            if len(body) < 218:
                raise ValueError("truncated final")
            nonce = body[0:16].hex(); sig = body[16:80].hex()
            cert, _ = unpack_cert(body, 80)
            return "final", {"nonce": nonce, "sig": sig, "cert": cert}
        raise ValueError("unknown message type")
    except (struct.error, IndexError) as e:
        raise ValueError("malformed invite") from e
