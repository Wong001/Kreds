import os

import pytest

from hearth.dmcrypt import (
    decrypt_blob, decrypt_body, dm_aad, encrypt_blob, encrypt_body,
    gen_enc_keypair, new_content_key, post_aad, seal_content_key,
    open_content_key, seal_slots, try_open_slots, unwrap_key, wrap_key,
)


def setup():
    a_priv, a_pub = gen_enc_keypair()
    b_priv, b_pub = gen_enc_keypair()
    aad = dm_aad("aa" * 32, "bb" * 32, 1000.0)
    key = new_content_key()
    return a_priv, a_pub, b_priv, b_pub, aad, key


def test_body_roundtrip_and_tamper():
    *_, aad, key = setup()
    nonce, ct = encrypt_body(key, {"text": "hej", "blobs": []}, aad)
    assert decrypt_body(key, nonce, ct, aad) == {"text": "hej", "blobs": []}
    bad_ct = ("00" if ct[:2] != "00" else "11") + ct[2:]
    assert decrypt_body(key, nonce, bad_ct, aad) is None


def test_aad_mismatch_rejected():
    *_, aad, key = setup()
    nonce, ct = encrypt_body(key, {"text": "x", "blobs": []}, aad)
    other_aad = dm_aad("aa" * 32, "bb" * 32, 2000.0)   # different created_at
    assert decrypt_body(key, nonce, ct, other_aad) is None


def test_wrap_unwrap_per_device():
    a_priv, a_pub, b_priv, b_pub, aad, key = setup()
    wraps = wrap_key(key, {"d1" + "0" * 62: a_pub, "d2" + "0" * 62: b_pub},
                     aad)
    assert unwrap_key(wraps, "d1" + "0" * 62, a_priv, aad) == key
    assert unwrap_key(wraps, "d2" + "0" * 62, b_priv, aad) == key
    # device not in wraps
    assert unwrap_key(wraps, "d3" + "0" * 62, a_priv, aad) is None
    # right device slot, wrong private key
    assert unwrap_key(wraps, "d1" + "0" * 62, b_priv, aad) is None


def test_ephemeral_keys_differ_per_wrap():
    a_priv, a_pub, b_priv, b_pub, aad, key = setup()
    wraps = wrap_key(key, {"d1" + "0" * 62: a_pub, "d2" + "0" * 62: b_pub},
                     aad)
    assert (wraps["d1" + "0" * 62]["eph_pub"]
            != wraps["d2" + "0" * 62]["eph_pub"])


def test_blob_roundtrip_and_tamper():
    *_, key = setup()
    data = b"\x89PNG-secret-photo-bytes"
    ct = encrypt_blob(key, data)
    assert ct != data and decrypt_blob(key, ct) == data
    tampered = ct[:-1] + bytes([ct[-1] ^ 1])
    assert decrypt_blob(key, tampered) is None
    assert decrypt_blob(key, b"short") is None


def test_wrap_skips_malformed_enc_pub():
    *_, aad, key = setup()
    _, good_pub = gen_enc_keypair()
    wraps = wrap_key(key, {"d1" + "0" * 62: good_pub,
                           "d2" + "0" * 62: "not-hex",
                           "d3" + "0" * 62: "ab"},     # valid hex, wrong length
                     aad)
    assert set(wraps) == {"d1" + "0" * 62}             # only the good device


def test_content_key_seal_open_roundtrip():
    sk = os.urandom(32).hex()
    key = os.urandom(32)
    sealed = seal_content_key(sk, "aa" * 32, key)
    assert open_content_key(sk, "aa" * 32, sealed) == key


def test_content_key_seal_binds_msg_id():
    sk = os.urandom(32).hex()
    sealed = seal_content_key(sk, "aa" * 32, os.urandom(32))
    assert open_content_key(sk, "bb" * 32, sealed) is None


def test_content_key_wrong_storage_key_or_garbage_rejected():
    sk = os.urandom(32).hex()
    sealed = seal_content_key(sk, "aa" * 32, os.urandom(32))
    assert open_content_key(os.urandom(32).hex(), "aa" * 32,
                            sealed) is None
    assert open_content_key(sk, "aa" * 32, "00ff") is None
    assert open_content_key(sk, "aa" * 32, "zz-not-hex") is None


def test_post_aad_binds_author_scope_time():
    a = post_aad("aa" * 32, "inner", 100.0)
    assert a != post_aad("aa" * 32, "kreds", 100.0)      # scope bound
    assert a != post_aad("bb" * 32, "inner", 100.0)      # author bound
    assert a != post_aad("aa" * 32, "inner", 101.0)      # time bound
    assert isinstance(a, (bytes, bytearray))


def test_sealed_slots_friend_opens_stranger_cannot():
    fpriv, fpub = gen_enc_keypair()
    spriv, spub = gen_enc_keypair()          # stranger keys, never sealed to
    slots = seal_slots(b"identity-payload", [fpub])
    assert try_open_slots(slots, fpriv) == b"identity-payload"
    assert try_open_slots(slots, spriv) is None


def test_sealed_slots_bucket_padding_and_anonymity():
    pubs = [gen_enc_keypair()[1] for _ in range(3)]
    slots = seal_slots(b"x", pubs)
    assert len(slots) == 8                    # 3 real -> 8-bucket
    # no recipient identifiers anywhere in a slot
    assert all(set(s.keys()) == {"eph_pub", "nonce", "ct"} for s in slots)
    pubs17 = [gen_enc_keypair()[1] for _ in range(17)]
    assert len(seal_slots(b"x", pubs17)) == 32
    with pytest.raises(ValueError):
        seal_slots(b"x", [gen_enc_keypair()[1] for _ in range(65)])
    # bucket boundaries: exact bucket size needs no padding, doesn't raise
    pubs8 = [gen_enc_keypair()[1] for _ in range(8)]
    assert len(seal_slots(b"x", pubs8)) == 8
    pubs64 = [gen_enc_keypair()[1] for _ in range(64)]
    assert len(seal_slots(b"x", pubs64)) == 64


def test_sealed_slots_dummy_slots_indistinguishable_shape():
    slots = seal_slots(b"payload", [gen_enc_keypair()[1]])
    # every slot (real or dummy) has hex fields of plausible length;
    # a dummy must not be identifiable by shape alone
    lens = {(len(s["eph_pub"]), len(s["nonce"])) for s in slots}
    assert lens == {(64, 24)}
    # the core anonymity property: ciphertext length is uniform across
    # every slot (real or dummy) - length alone can't single out a slot
    assert len({len(s["ct"]) for s in slots}) == 1


def test_sealed_slots_empty_recipients():
    # a responder with zero friends still produces a full dummy bucket
    slots = seal_slots(b"x", [])
    assert len(slots) == 8
    priv, _ = gen_enc_keypair()
    assert try_open_slots(slots, priv) is None


def test_try_open_slots_rejects_hostile_container():
    # a JSON null/scalar mutual_box is the realistic malformed shape
    priv, _ = gen_enc_keypair()
    assert try_open_slots(None, priv) is None
    assert try_open_slots(42, priv) is None
