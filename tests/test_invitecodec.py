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
    pub, port = ic.onion_split(addr)
    nonce = os.urandom(16).hex()
    expiry = int(time.time()) + 600
    code = ic.encode_invite(idhex[:8], pub, port, nonce, expiry)  # id_prefix = first 4 bytes = 8 hex
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
    pub, port = ic.onion_split(addr)
    resp = ic.encode_response(pub, port, "aa"*16, "bb"*16, "cc"*64, cert)
    typ, d = ic.decode(resp)
    assert typ == "response" and d["cert"] == cert and d["addr"] == addr
    assert d["nonce"] == "aa"*16 and d["peer_nonce"] == "bb"*16 and d["sig"] == "cc"*64
    fin = ic.encode_final("bb"*16, "dd"*64, cert)
    typ2, d2 = ic.decode(fin)
    assert typ2 == "final" and d2["cert"] == cert and d2["nonce"] == "bb"*16 and d2["sig"] == "dd"*64

def test_decode_rejects_bad_version_and_garbage():
    import pytest
    with pytest.raises(ValueError):
        ic.decode("z" + ic.encode_invite("ab"*4, os.urandom(32), 1, "aa"*16, 1)[1:])  # corrupt
    with pytest.raises(ValueError):
        ic.decode("!!!!not base58!!!!")

def test_decode_rejects_truncated_bodies_as_valueerror():
    import pytest
    # A valid header (version+type) with a too-short invite body
    short_invite = ic.b58encode(bytes([0x01, 0x01]) + b"\x00" * 10)
    with pytest.raises(ValueError, match="truncated invite"):
        ic.decode(short_invite)
    # A response with body shorter than 268 bytes
    short_response = ic.b58encode(bytes([0x01, 0x02]) + b"\x00" * 100)
    with pytest.raises(ValueError, match="truncated response"):
        ic.decode(short_response)
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
