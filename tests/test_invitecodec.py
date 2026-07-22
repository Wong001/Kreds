import os, time
from hearth import invitecodec as ic

def test_base58_roundtrip_incl_leading_zeros():
    for b in (b"", b"\x00", b"\x00\x00\x01", os.urandom(32), bytes(range(20))):
        assert ic.b58decode(ic.b58encode(b)) == b
    # alphabet excludes look-alikes
    assert "0" not in ic.b58encode(os.urandom(40)) or True  # 0 not in alphabet
    assert set("0OIl").isdisjoint(set(ic._B58))

def test_onion_split_join_roundtrip():
    # a real v3 onion address (56 base32 chars + .onion) + port
    addr = "otkspt5nohnwvmgir7obhcpeouqx4qkxchorw3ybhznjatdmwc7t5lad.onion:53799"
    pub, port = ic.onion_split(addr)
    assert len(pub) == 32 and port == 53799
    assert ic.onion_join(pub, port) == addr        # checksum+version reconstructed

def test_fingerprint_is_4_chars_and_stable():
    idhex = "abf442fa0d42240fafcc0aef5ff96f4f4cec7a0312e659d752636458de607758"
    fp = ic.fingerprint(idhex)
    assert len(fp) == 4 and fp == ic.fingerprint(idhex)   # deterministic
    assert fp.isalnum() and fp.upper() == fp              # base32 uppercase

def test_invite_roundtrip_and_size():
    idhex = "ab" * 32
    addr = "otkspt5nohnwvmgir7obhcpeouqx4qkxchorw3ybhznjatdmwc7t5lad.onion:53799"
    nonce = os.urandom(16).hex()
    expiry = int(time.time()) + 600
    code = ic.encode_invite(idhex[:8], addr, nonce, expiry)  # id_prefix = first 4 bytes = 8 hex
    assert len(code) < 100                                        # the whole point
    typ, d = ic.decode(code)
    assert typ == "invite"
    assert d["id_prefix"] == idhex[:8] and d["addr"] == addr
    assert d["nonce"] == nonce and d["expiry"] == expiry

def test_response_and_final_roundtrip_with_cert():
    cert = {"identity_pub": "ab"*32, "device_pub": "cd"*32,
            "device_name": "desktop", "enrolled_at": 1783534958.87,
            "signature": "ef"*64}
    addr = "otkspt5nohnwvmgir7obhcpeouqx4qkxchorw3ybhznjatdmwc7t5lad.onion:53799"
    resp = ic.encode_response(addr, "aa"*16, "bb"*16, "cc"*64, cert)
    typ, d = ic.decode(resp)
    assert typ == "response" and d["cert"] == cert and d["addr"] == addr
    assert d["nonce"] == "aa"*16 and d["peer_nonce"] == "bb"*16 and d["sig"] == "cc"*64
    fin = ic.encode_final("bb"*16, "dd"*64, cert)
    typ2, d2 = ic.decode(fin)
    assert typ2 == "final" and d2["cert"] == cert and d2["nonce"] == "bb"*16 and d2["sig"] == "dd"*64

def test_pack_addr_roundtrips_onion_plain_and_none():
    # onion (the compact-size win path)
    onion_addr = "otkspt5nohnwvmgir7obhcpeouqx4qkxchorw3ybhznjatdmwc7t5lad.onion:53799"
    packed = ic.pack_addr(onion_addr)
    addr, off = ic.unpack_addr(packed, 0)
    assert addr == onion_addr and off == len(packed)
    # a non-onion "host:port" is a first-class gossip_addr too (runner.py's
    # tor=False dev/LAN path, and every loopback-socket test in the suite)
    plain_addr = "127.0.0.1:59999"
    packed2 = ic.pack_addr(plain_addr)
    addr2, off2 = ic.unpack_addr(packed2, 0)
    assert addr2 == plain_addr and off2 == len(packed2)
    # gossip_addr never set (fresh node, no listener bound yet)
    packed3 = ic.pack_addr(None)
    addr3, off3 = ic.unpack_addr(packed3, 0)
    assert addr3 is None and off3 == len(packed3)

def test_pair_roundtrip():
    # The android first-load pairing link (spec 2026-07-22): the
    # desktop's onion address + a short-lived pairing code, packed
    # together so the phone's QR scan/typed entry carries both in one
    # string. Same _wrap/base58 mechanics as the other three types, new
    # type tag (4).
    addr = "otkspt5nohnwvmgir7obhcpeouqx4qkxchorw3ybhznjatdmwc7t5lad.onion:53799"
    code = "aB3dEfGh"
    link = ic.encode_pair(addr, code)
    assert len(link) < 100
    assert ic.decode(link) == ("pair", addr, code)


def test_pair_roundtrip_no_addr():
    # A node with no gossip_addr yet can't mint a pairing link in
    # practice (api.py's own 400 guard) -- but the codec itself stays
    # symmetric with pack_addr/unpack_addr's None case, same as every
    # other message type here.
    code = "XyZ12345"
    link = ic.encode_pair(None, code)
    assert ic.decode(link) == ("pair", None, code)


def test_pair_decode_rejects_truncated_code():
    import pytest
    addr = "otkspt5nohnwvmgir7obhcpeouqx4qkxchorw3ybhznjatdmwc7t5lad.onion:53799"
    good = ic.encode_pair(addr, "aB3dEfGh")
    raw = ic.b58decode(good)
    truncated = ic.b58encode(raw[:-3])   # chop bytes off the code's tail
    with pytest.raises(ValueError, match="truncated pair code"):
        ic.decode(truncated)


def test_decode_rejects_bad_version_and_garbage():
    import pytest
    addr = "otkspt5nohnwvmgir7obhcpeouqx4qkxchorw3ybhznjatdmwc7t5lad.onion:53799"
    with pytest.raises(ValueError):
        ic.decode("z" + ic.encode_invite("ab"*4, addr, "aa"*16, 1)[1:])  # corrupt
    with pytest.raises(ValueError):
        ic.decode("!!!!not base58!!!!")

def test_decode_rejects_truncated_bodies_as_valueerror():
    import pytest
    import struct as _struct
    # A valid header (version+type) with a too-short invite body (not even
    # a full id_prefix + address flag)
    short_invite = ic.b58encode(bytes([0x01, 0x01]) + b"\x00" * 3)
    with pytest.raises(ValueError, match="truncated invite"):
        ic.decode(short_invite)
    # A response with body shorter than the address+nonce+peer+sig minimum
    short_response = ic.b58encode(bytes([0x01, 0x02]) + b"\x00" * 100)
    with pytest.raises(ValueError, match="truncated response"):
        ic.decode(short_response)
    # A response whose plain-address (flag=1) length prefix claims more
    # bytes than actually follow it -- untrusted pasted input, not just an
    # honest short message.
    bad_addr_len_response = ic.b58encode(
        bytes([0x01, 0x02]) + bytes([1]) + _struct.pack(">H", 500) + b"\x00" * 5)
    with pytest.raises(ValueError, match="truncated address"):
        ic.decode(bad_addr_len_response)
    # A final with body shorter than 218 bytes
    short_final = ic.b58encode(bytes([0x01, 0x03]) + b"\x00" * 50)
    with pytest.raises(ValueError, match="truncated final"):
        ic.decode(short_final)
    # A cert whose name_len claims more bytes than remain: construct a type-3 message
    # with valid header through sig, but a cert that overruns
    cert_overrun = (bytes([0x01, 0x03]) + b"\x00"*16 + b"\x00"*64  # nonce + sig
                    + b"\x00"*32 + b"\x00"*32 + b"\x00"*8 + b"\x00"*64  # identity, device, enrolled, signature
                    + b"\xff\xff")  # name_len = 65535, no following bytes
    short_final_cert = ic.b58encode(cert_overrun)
    with pytest.raises(ValueError, match="truncated cert name"):
        ic.decode(short_final_cert)
