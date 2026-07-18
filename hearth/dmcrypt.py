"""DM encryption: X25519 key agreement + ChaCha20-Poly1305.

v0.1 has NO forward secrecy: content keys are wrapped against static
per-device encryption keys (spec: stated plainly, ratchet is the named
follow-up). Do not describe this module as forward-secret anywhere.
"""
from __future__ import annotations

import json
import os
import random
from typing import Dict, Optional, Tuple

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from .identity import PROTOCOL, canonical

BLOB_AAD = b"hearth/dm-blob/v1"


def gen_enc_keypair() -> Tuple[str, str]:
    priv = X25519PrivateKey.generate()
    return (priv.private_bytes_raw().hex(),
            priv.public_key().public_bytes_raw().hex())


def new_content_key() -> bytes:
    return os.urandom(32)


def dm_aad(sender_identity: str, to_identity: str,
           created_at: float) -> bytes:
    return canonical({"type": "dm-aad", "protocol": PROTOCOL,
                      "from": sender_identity, "to": to_identity,
                      "created_at": created_at})


def post_aad(author_identity: str, scope: str, created_at: float) -> bytes:
    return canonical({"type": "post-aad", "protocol": PROTOCOL,
                      "from": author_identity, "scope": scope,
                      "created_at": created_at})


def _derive_kek(shared: bytes) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=None,
                info=b"hearth/dm-wrap/v1").derive(shared)


def encrypt_body(key: bytes, body: dict, aad: bytes) -> Tuple[str, str]:
    nonce = os.urandom(12)
    ct = ChaCha20Poly1305(key).encrypt(nonce, canonical(body), aad)
    return nonce.hex(), ct.hex()


def decrypt_body(key: bytes, nonce_hex: str, ct_hex: str,
                 aad: bytes) -> Optional[dict]:
    try:
        plain = ChaCha20Poly1305(key).decrypt(
            bytes.fromhex(nonce_hex), bytes.fromhex(ct_hex), aad)
        return json.loads(plain)
    except (InvalidTag, ValueError):
        return None


def wrap_key(key: bytes, device_enc_pubs: Dict[str, str],
             aad: bytes) -> dict:
    wraps = {}
    for device_pub, enc_pub in device_enc_pubs.items():
        try:
            peer = X25519PublicKey.from_public_bytes(bytes.fromhex(enc_pub))
        except ValueError:
            continue                      # skip devices with a bad enc key
        eph = X25519PrivateKey.generate()
        kek = _derive_kek(eph.exchange(peer))
        nonce = os.urandom(12)
        wraps[device_pub] = {
            "eph_pub": eph.public_key().public_bytes_raw().hex(),
            "nonce": nonce.hex(),
            "wrapped_key": ChaCha20Poly1305(kek).encrypt(nonce, key,
                                                         aad).hex(),
        }
    return wraps


def unwrap_key(wraps: dict, device_pub: str, enc_priv_hex: str,
               aad: bytes) -> Optional[bytes]:
    w = wraps.get(device_pub)
    if w is None:
        return None
    try:
        priv = X25519PrivateKey.from_private_bytes(
            bytes.fromhex(enc_priv_hex))
        shared = priv.exchange(
            X25519PublicKey.from_public_bytes(bytes.fromhex(w["eph_pub"])))
        kek = _derive_kek(shared)
        return ChaCha20Poly1305(kek).decrypt(
            bytes.fromhex(w["nonce"]), bytes.fromhex(w["wrapped_key"]),
            aad)
    except (InvalidTag, ValueError, KeyError):
        return None


def encrypt_blob(key: bytes, data: bytes) -> bytes:
    nonce = os.urandom(12)
    return nonce + ChaCha20Poly1305(key).encrypt(nonce, data, BLOB_AAD)


def decrypt_blob(key: bytes, data: bytes) -> Optional[bytes]:
    if len(data) < 13:
        return None
    try:
        return ChaCha20Poly1305(key).decrypt(data[:12], data[12:],
                                             BLOB_AAD)
    except InvalidTag:
        return None


KEYCACHE_AAD = b"hearth/dm-keycache/v1/"


def seal_content_key(storage_key_hex: str, msg_id: str,
                     key: bytes) -> str:
    """Encrypt a DM content key for the local dm_keys cache. AAD binds
    the msg_id so a cached key cannot be transplanted between rows."""
    nonce = os.urandom(12)
    ct = ChaCha20Poly1305(bytes.fromhex(storage_key_hex)).encrypt(
        nonce, key, KEYCACHE_AAD + msg_id.encode())
    return (nonce + ct).hex()


def open_content_key(storage_key_hex: str, msg_id: str,
                     sealed_hex: str) -> Optional[bytes]:
    try:
        data = bytes.fromhex(sealed_hex)
        if len(data) < 13:
            return None
        return ChaCha20Poly1305(bytes.fromhex(storage_key_hex)).decrypt(
            data[:12], data[12:], KEYCACHE_AAD + msg_id.encode())
    except (InvalidTag, ValueError):
        return None


MUTUAL_BOX_AAD = b"hearth/mutual-box/v1"
_SLOT_BUCKETS = (8, 16, 32, 64)


def _derive_slot_kek(shared: bytes) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=None,
                info=b"hearth/mutual-box/v1").derive(shared)


def seal_slots(payload: bytes, enc_pubs) -> list:
    """Anonymous per-recipient slots (spec 2026-07-18, engagement
    privacy): each real slot is an ephemeral-X25519 + ChaCha20-Poly1305
    box with NO recipient identifier - recipients trial-open. Padded
    with byte-random dummy slots to a fixed bucket so the slot count
    only buckets, never measures, the sender's friend-device count."""
    real = []
    for enc_pub in enc_pubs:
        try:
            peer = X25519PublicKey.from_public_bytes(bytes.fromhex(enc_pub))
        except ValueError:
            continue                      # skip bad keys, like wrap_key
        eph = X25519PrivateKey.generate()
        kek = _derive_slot_kek(eph.exchange(peer))
        nonce = os.urandom(12)
        real.append({"eph_pub": eph.public_key().public_bytes_raw().hex(),
                     "nonce": nonce.hex(),
                     "ct": ChaCha20Poly1305(kek).encrypt(
                         nonce, payload, MUTUAL_BOX_AAD).hex()})
    bucket = next((b for b in _SLOT_BUCKETS if b >= len(real)), None)
    if bucket is None:
        raise ValueError("too many recipients for a mutual box")
    ct_len = len(real[0]["ct"]) if real else (len(payload) + 16) * 2
    while len(real) < bucket:
        real.append({"eph_pub": os.urandom(32).hex(),
                     "nonce": os.urandom(12).hex(),
                     "ct": os.urandom(ct_len // 2).hex()})
    # Shuffle is cosmetic hardening only (breaks "first N slots are the
    # real ones" positional leak) - it is NOT the anonymity mechanism.
    # Anonymity comes from the sealed-box construction itself: slots
    # carry no recipient identifier and dummies are byte-indistinguishable
    # from real slots, so trial-opening is the only way to find a match.
    random.shuffle(real)
    return real


def try_open_slots(slots, enc_priv_hex: str):
    """Trial-open every slot; the one sealed to this device decrypts,
    all others (other recipients' and dummies) fail AEAD auth."""
    try:
        priv = X25519PrivateKey.from_private_bytes(
            bytes.fromhex(enc_priv_hex))
    except ValueError:
        return None
    for s in slots:
        if not isinstance(s, dict):
            continue
        try:
            shared = priv.exchange(X25519PublicKey.from_public_bytes(
                bytes.fromhex(s["eph_pub"])))
            kek = _derive_slot_kek(shared)
            return ChaCha20Poly1305(kek).decrypt(
                bytes.fromhex(s["nonce"]), bytes.fromhex(s["ct"]),
                MUTUAL_BOX_AAD)
        except (InvalidTag, ValueError, KeyError, TypeError):
            continue
    return None
