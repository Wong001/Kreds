from hearth.dmcrypt import (
    dm_aad, encrypt_body, gen_enc_keypair, new_content_key, wrap_key,
)
from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import make_dm, make_enckey, validate_payload


def device():
    d = DeviceKeys.create("phone")
    IdentityCeremony().enroll_first_device(d)
    return d


def test_devicekeys_carry_x25519_pair_and_roundtrip():
    d = device()
    assert d.enc_priv and d.enc_pub and d.enc_priv != d.enc_pub
    restored = DeviceKeys.from_json(d.to_json())
    assert (restored.enc_priv, restored.enc_pub) == (d.enc_priv, d.enc_pub)


def test_from_json_without_enc_fields_generates_fresh_pair():
    d = device()
    j = d.to_json()
    del j["enc_priv"], j["enc_pub"]        # pre-DM keys.json
    restored = DeviceKeys.from_json(j)
    assert restored.enc_priv and restored.enc_pub


def test_enckey_message_valid():
    d = device()
    m = make_enckey(d, now=5.0)
    assert m.payload == {"kind": "enckey", "enc_pub": d.enc_pub,
                         "created_at": 5.0}
    assert validate_payload(m.payload) == (True, "ok")
    assert m.verify_device_signature()


def test_dm_message_valid_and_signed():
    d = device()
    to = "bb" * 32
    key = new_content_key()
    aad = dm_aad(d.identity_pub, to, 100.0)
    nonce, ct = encrypt_body(key, {"text": "hemmelig", "blobs": []}, aad)
    _, peer_pub = gen_enc_keypair()
    wraps = wrap_key(key, {d.device_pub: d.enc_pub, "cc" * 32: peer_pub},
                     aad)
    m = make_dm(d, to, nonce, ct, wraps, created_at=100.0)
    assert validate_payload(m.payload) == (True, "ok")
    assert m.verify_device_signature()
    assert m.payload["to"] == to and m.payload["created_at"] == 100.0


def test_invalid_dm_payloads_rejected():
    ok = lambda p: validate_payload(p)[0]
    base = {"kind": "dm", "to": "bb" * 32, "body_nonce": "0" * 24,
            "body_ct": "ab", "wraps": {}, "created_at": 1.0,
            "expires_at": None}
    assert ok(base)
    assert not ok({**base, "to": "nope"})
    assert not ok({**base, "body_nonce": "0" * 23})
    assert not ok({**base, "wraps": {"xy": {}}})
    assert not ok({**base, "wraps": {"aa" * 32: {"eph_pub": "0" * 64,
                                                 "nonce": "0" * 24}}})
    assert not ok({"kind": "enckey", "created_at": 1.0})   # no enc_pub
