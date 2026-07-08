"""Hearth identity layer - production evolution of the D2 spike.

Per concept capture v0.3 / SPIKE_REPORT: seen-SET acceptance (not a
high-water mark), retro-drop on revocation, always-strict verification.
The naive/attack configurations exist only in hearth_d2_spike/.
"""
from __future__ import annotations

import hashlib
import json
import os
import time
from dataclasses import dataclass, field
from typing import Dict, Optional, Set, Tuple

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    NoEncryption,
    PrivateFormat,
    PublicFormat,
)

PROTOCOL = "hearth/v0.2"   # bumped: DEFRIENDS is now a mandatory wire phase
                           # (whole-branch review, Fix 9) -- baked into every
                           # signed AUTH/message body via canonical(), so a
                           # mismatched-version peer simply fails the AUTH
                           # device-key proof instead of misaligning frames.
ENC_ROTATION_PERIOD = 24 * 3600.0    # rotate the DM enc key daily
ENC_GRACE = 7 * 24 * 3600.0          # retired enc keys die after 7 days


def canonical(obj: dict) -> bytes:
    """Deterministic JSON bytes: what gets signed must be byte-stable."""
    return json.dumps(obj, sort_keys=True, separators=(",", ":")).encode()


def derive_identity_private_key(seed: bytes) -> Ed25519PrivateKey:
    if len(seed) != 32:
        raise ValueError("seed must be exactly 32 bytes")
    key_material = HKDF(
        algorithm=hashes.SHA256(), length=32, salt=None,
        info=b"hearth/identity-key/v1",
    ).derive(seed)
    return Ed25519PrivateKey.from_private_bytes(key_material)


def pub_hex(key: Ed25519PublicKey) -> str:
    return key.public_bytes(Encoding.Raw, PublicFormat.Raw).hex()


def pub_from_hex(h: str) -> Ed25519PublicKey:
    return Ed25519PublicKey.from_public_bytes(bytes.fromhex(h))


def priv_hex(key: Ed25519PrivateKey) -> str:
    return key.private_bytes(
        Encoding.Raw, PrivateFormat.Raw, NoEncryption()).hex()


def priv_from_hex(h: str) -> Ed25519PrivateKey:
    return Ed25519PrivateKey.from_private_bytes(bytes.fromhex(h))


def _gen_x25519_pair() -> Tuple[str, str]:
    priv = X25519PrivateKey.generate()
    return (priv.private_bytes_raw().hex(),
            priv.public_key().public_bytes_raw().hex())


def _sig_ok(pub: str, sig: str, body: bytes) -> bool:
    try:
        pub_from_hex(pub).verify(bytes.fromhex(sig), body)
        return True
    except (InvalidSignature, ValueError):
        return False


@dataclass(frozen=True)
class EnrollmentCert:
    identity_pub: str
    device_pub: str
    device_name: str
    enrolled_at: float
    signature: str

    def body(self) -> bytes:
        return canonical({
            "type": "enrollment", "protocol": PROTOCOL,
            "identity_pub": self.identity_pub, "device_pub": self.device_pub,
            "device_name": self.device_name, "enrolled_at": self.enrolled_at,
        })

    def verify(self) -> bool:
        return _sig_ok(self.identity_pub, self.signature, self.body())

    def to_dict(self) -> dict:
        return {
            "identity_pub": self.identity_pub, "device_pub": self.device_pub,
            "device_name": self.device_name, "enrolled_at": self.enrolled_at,
            "signature": self.signature,
        }

    @staticmethod
    def from_dict(d: dict) -> "EnrollmentCert":
        return EnrollmentCert(d["identity_pub"], d["device_pub"],
                              d["device_name"], d["enrolled_at"],
                              d["signature"])


@dataclass(frozen=True)
class RevocationCert:
    identity_pub: str
    device_pub: str
    last_valid_seq: int
    revoked_at: float
    signature: str

    def body(self) -> bytes:
        return canonical({
            "type": "revocation", "protocol": PROTOCOL,
            "identity_pub": self.identity_pub, "device_pub": self.device_pub,
            "last_valid_seq": self.last_valid_seq,
            "revoked_at": self.revoked_at,
        })

    def verify(self) -> bool:
        return _sig_ok(self.identity_pub, self.signature, self.body())

    def to_dict(self) -> dict:
        return {
            "identity_pub": self.identity_pub, "device_pub": self.device_pub,
            "last_valid_seq": self.last_valid_seq,
            "revoked_at": self.revoked_at, "signature": self.signature,
        }

    @staticmethod
    def from_dict(d: dict) -> "RevocationCert":
        return RevocationCert(d["identity_pub"], d["device_pub"],
                              d["last_valid_seq"], d["revoked_at"],
                              d["signature"])


@dataclass(frozen=True)
class DefriendNotice:
    author_identity: str
    target_identity: str
    created_at: float
    signature: str

    def body(self) -> bytes:
        return canonical({
            "type": "defriend", "protocol": PROTOCOL,
            "author_identity": self.author_identity,
            "target_identity": self.target_identity,
            "created_at": self.created_at,
        })

    def verify(self) -> bool:
        return _sig_ok(self.author_identity, self.signature, self.body())

    def to_dict(self) -> dict:
        return {"author_identity": self.author_identity,
                "target_identity": self.target_identity,
                "created_at": self.created_at, "signature": self.signature}

    @staticmethod
    def from_dict(d: dict) -> "DefriendNotice":
        return DefriendNotice(d["author_identity"], d["target_identity"],
                              d["created_at"], d["signature"])


@dataclass(frozen=True)
class SignedMessage:
    cert: EnrollmentCert
    seq: int
    payload: dict
    signature: str

    def body(self) -> bytes:
        return canonical({
            "type": "message", "protocol": PROTOCOL,
            "identity_pub": self.cert.identity_pub,
            "device_pub": self.cert.device_pub,
            "seq": self.seq, "payload": self.payload,
        })

    @property
    def msg_id(self) -> str:
        return hashlib.sha256(self.body()).hexdigest()

    def verify_device_signature(self) -> bool:
        return _sig_ok(self.cert.device_pub, self.signature, self.body())

    def to_dict(self) -> dict:
        return {"cert": self.cert.to_dict(), "seq": self.seq,
                "payload": self.payload, "signature": self.signature}

    @staticmethod
    def from_dict(d: dict) -> "SignedMessage":
        return SignedMessage(EnrollmentCert.from_dict(d["cert"]), d["seq"],
                             d["payload"], d["signature"])


class DeviceKeys:
    """This process's own keys: device keypair + (once enrolled) the
    identity-key replica. Plaintext at rest for the slice; OS-keystore
    protection is stated out of scope in the spec.

    App-lock (Kreds security slice): `device_priv` may be None (a LOCKED
    instance, e.g. right after boot with applock.json present and no
    credential entered yet) as long as `device_pub` is supplied instead --
    a locked node still needs to know its own device_pub for e.g. the
    `devices()` listing and `this_device` comparisons. `sign_message` /
    `sign_raw` raise while locked; nothing else about the object changes
    shape, so locked/unlocked share one class."""

    SECRET_FIELDS = ("device_priv", "identity_priv", "enc_priv",
                     "retired_enc", "storage_key")

    def __init__(self, name: str, device_priv: Optional[Ed25519PrivateKey],
                 cert: Optional[EnrollmentCert] = None,
                 identity_priv: Optional[Ed25519PrivateKey] = None,
                 seq: int = 0,
                 enc_priv: Optional[str] = None,
                 enc_pub: Optional[str] = None,
                 retired_enc: Optional[list] = None,
                 storage_key: Optional[str] = None,
                 device_pub: Optional[str] = None):
        self.name = name
        self._device_priv = device_priv
        if device_priv is not None:
            self.device_pub = pub_hex(device_priv.public_key())
        else:
            if device_pub is None:
                raise ValueError(
                    "device_pub is required when device_priv is None (locked)")
            self.device_pub = device_pub
        self.cert = cert
        self._identity_priv = identity_priv
        self.seq = seq
        if enc_priv is None and enc_pub is None:
            enc_priv, enc_pub = _gen_x25519_pair()
        self.enc_priv = enc_priv
        self.enc_pub = enc_pub
        self.retired_enc = list(retired_enc or [])
        if storage_key is None and device_priv is not None:
            storage_key = os.urandom(32).hex()
        self.storage_key = storage_key

    @staticmethod
    def create(name: str) -> "DeviceKeys":
        return DeviceKeys(name, Ed25519PrivateKey.generate())

    @staticmethod
    def locked_from_json(nonsecret: dict) -> "DeviceKeys":
        """Build a LOCKED DeviceKeys from the non-secret subset persisted in
        keys.json when App-lock is enabled: no private material at all, just
        enough public state (device_pub, cert, enc_pub, seq) to answer
        `identity_pub` (via the cert fallback), list devices, and know this
        device's own device_pub -- everything a locked boot needs before a
        credential is entered."""
        cert = (EnrollmentCert.from_dict(nonsecret["cert"])
               if nonsecret.get("cert") else None)
        return DeviceKeys(
            nonsecret["name"], None, cert, None,
            nonsecret.get("seq", 0),
            None, nonsecret.get("enc_pub"), None, None,
            device_pub=nonsecret["device_pub"])

    @property
    def identity_pub(self) -> Optional[str]:
        if self._identity_priv is not None:
            return pub_hex(self._identity_priv.public_key())
        # Locked (or revoked) view: fall back to the cert's identity_pub so
        # a locked node still knows its own public identity without holding
        # the identity private key. Security-sensitive call sites (e.g.
        # revocation, enckey maintenance) already guard on `self.revoked`/
        # `self.locked` explicitly rather than on identity_pub being None,
        # so this fallback does not weaken those checks.
        return self.cert.identity_pub if self.cert else None

    def _enrolled(self):
        if self.cert is None or self._identity_priv is None:
            raise RuntimeError(f"device '{self.name}' is not enrolled")

    def install(self, cert: EnrollmentCert, identity_priv_hex: str):
        if not cert.verify() or cert.device_pub != self.device_pub:
            raise ValueError("enrollment cert invalid for this device")
        identity_priv = priv_from_hex(identity_priv_hex)
        if pub_hex(identity_priv.public_key()) != cert.identity_pub:
            raise ValueError("identity key does not match enrollment cert")
        self.cert = cert
        self._identity_priv = identity_priv

    def sign_message(self, payload: dict) -> SignedMessage:
        if self._device_priv is None:
            raise RuntimeError("locked")
        self._enrolled()
        self.seq += 1
        body = canonical({
            "type": "message", "protocol": PROTOCOL,
            "identity_pub": self.cert.identity_pub,
            "device_pub": self.cert.device_pub,
            "seq": self.seq, "payload": payload,
        })
        return SignedMessage(self.cert, self.seq, payload,
                             self._device_priv.sign(body).hex())

    def sign_raw(self, data: bytes) -> str:
        if self._device_priv is None:
            raise RuntimeError("locked")
        return self._device_priv.sign(data).hex()

    def rotate_enc(self, now: Optional[float] = None):
        """Retire the current enc keypair and generate a fresh one.
        The grace clock starts NOW (retirement), not at key creation."""
        now = now if now is not None else time.time()
        self.retired_enc.append({
            "enc_priv": self.enc_priv, "enc_pub": self.enc_pub,
            "retired_at": now,
        })
        self.enc_priv, self.enc_pub = _gen_x25519_pair()
        self.prune_retired(now)

    def prune_retired(self, now: Optional[float] = None) -> bool:
        """Permanently delete retired keys past grace. This deletion IS
        the forward secrecy — never widen the retention window."""
        now = now if now is not None else time.time()
        keep = [r for r in self.retired_enc
                if now - r["retired_at"] <= ENC_GRACE]
        changed = len(keep) != len(self.retired_enc)
        self.retired_enc = keep
        return changed

    def enc_privs(self) -> list:
        """Decryption candidates: current key first, then retired."""
        out = []
        if self.enc_priv is not None:
            out.append(self.enc_priv)
        out.extend(r["enc_priv"] for r in self.retired_enc
                   if r.get("enc_priv"))
        return out

    def enroll_other(self, device_pub: str, device_name: str,
                     now: Optional[float] = None) -> EnrollmentCert:
        self._enrolled()
        return _make_enrollment(self._identity_priv, device_pub, device_name,
                                now if now is not None else time.time())

    def make_revocation(self, device_pub: str, last_valid_seq: int,
                        now: Optional[float] = None) -> RevocationCert:
        self._enrolled()
        return _make_revocation(self._identity_priv, device_pub,
                                last_valid_seq,
                                now if now is not None else time.time())

    def make_defriend(self, target_identity: str,
                      now: Optional[float] = None) -> DefriendNotice:
        self._enrolled()
        return _make_defriend(self._identity_priv, target_identity,
                              now if now is not None else time.time())

    def to_json(self) -> dict:
        return {
            "name": self.name,
            "device_priv": (priv_hex(self._device_priv)
                            if self._device_priv else None),
            "device_pub": self.device_pub,
            "cert": self.cert.to_dict() if self.cert else None,
            "identity_priv": (priv_hex(self._identity_priv)
                              if self._identity_priv else None),
            "seq": self.seq,
            "enc_priv": self.enc_priv,
            "enc_pub": self.enc_pub,
            "retired_enc": self.retired_enc,
            "storage_key": self.storage_key,
        }

    @staticmethod
    def from_json(d: dict) -> "DeviceKeys":
        # A revoked device's keys.json has device_priv: None (the device
        # signing key is destroyed on revocation -- crypto review CRITICAL)
        # while still going through the normal (non-applock) boot path, so
        # this tolerates a null device_priv the same way it already
        # tolerates a null identity_priv, and passes device_pub through so
        # the device's public identity survives without the private key
        # (mirrors locked_from_json).
        return DeviceKeys(
            d["name"],
            priv_from_hex(d["device_priv"]) if d.get("device_priv") else None,
            EnrollmentCert.from_dict(d["cert"]) if d["cert"] else None,
            priv_from_hex(d["identity_priv"]) if d["identity_priv"] else None,
            d["seq"],
            d.get("enc_priv"), d.get("enc_pub"),
            d.get("retired_enc"), d.get("storage_key"),
            device_pub=d.get("device_pub"),
        )


def _make_enrollment(identity_priv, device_pub, device_name, ts):
    identity_pub = pub_hex(identity_priv.public_key())
    body = canonical({
        "type": "enrollment", "protocol": PROTOCOL,
        "identity_pub": identity_pub, "device_pub": device_pub,
        "device_name": device_name, "enrolled_at": ts,
    })
    return EnrollmentCert(identity_pub, device_pub, device_name, ts,
                          identity_priv.sign(body).hex())


def _make_revocation(identity_priv, device_pub, last_valid_seq, ts):
    identity_pub = pub_hex(identity_priv.public_key())
    body = canonical({
        "type": "revocation", "protocol": PROTOCOL,
        "identity_pub": identity_pub, "device_pub": device_pub,
        "last_valid_seq": last_valid_seq, "revoked_at": ts,
    })
    return RevocationCert(identity_pub, device_pub, last_valid_seq, ts,
                          identity_priv.sign(body).hex())


def _make_defriend(identity_priv, target_identity, ts):
    identity_pub = pub_hex(identity_priv.public_key())
    body = canonical({
        "type": "defriend", "protocol": PROTOCOL,
        "author_identity": identity_pub,
        "target_identity": target_identity, "created_at": ts,
    })
    return DefriendNotice(identity_pub, target_identity, ts,
                          identity_priv.sign(body).hex())


class IdentityCeremony:
    def __init__(self, seed: Optional[bytes] = None):
        self.seed = seed if seed is not None else os.urandom(32)
        self._identity_priv = derive_identity_private_key(self.seed)
        self.identity_pub = pub_hex(self._identity_priv.public_key())

    def enroll_first_device(self, device: DeviceKeys) -> EnrollmentCert:
        cert = _make_enrollment(self._identity_priv, device.device_pub,
                                device.name, time.time())
        device.install(cert, priv_hex(self._identity_priv))
        return cert

    def paper_seed(self) -> str:
        return self.seed.hex()

    @staticmethod
    def recover(paper_seed_hex: str) -> "IdentityCeremony":
        return IdentityCeremony(bytes.fromhex(paper_seed_hex))


class SeenSet:
    """Per-device set of accepted sequence numbers, compactable.

    v0.3 binding finding (Ambush 2): accept any UNSEEN seq, reject reuse.
    Storage compacts to (contiguous, sparse-above): all seqs 1..contiguous
    are seen, plus the sparse set above that bound.
    """

    def __init__(self, contiguous: int = 0, sparse: Optional[Set[int]] = None):
        self.contiguous = contiguous
        self.sparse: Set[int] = set(sparse or ())

    def has(self, seq: int) -> bool:
        return 1 <= seq <= self.contiguous or seq in self.sparse

    def add(self, seq: int) -> bool:
        if seq < 1 or self.has(seq):
            return False
        self.sparse.add(seq)
        while self.contiguous + 1 in self.sparse:
            self.contiguous += 1
            self.sparse.discard(self.contiguous)
        return True

    def max_seen(self) -> int:
        return max(self.sparse) if self.sparse else self.contiguous

    def to_json(self) -> dict:
        return {"contiguous": self.contiguous,
                "sparse": sorted(self.sparse)}

    @staticmethod
    def from_json(d: dict) -> "SeenSet":
        return SeenSet(d["contiguous"], set(d["sparse"]))

    @staticmethod
    def summary_has(summary: dict, seq: int) -> bool:
        return 1 <= seq <= summary["contiguous"] or seq in summary["sparse"]


@dataclass
class DeviceView:
    """A peer's (or our own store's) knowledge of one device."""
    cert: Optional[EnrollmentCert]
    revocation: Optional[RevocationCert] = None
    seen: SeenSet = field(default_factory=SeenSet)


class Verifier:
    """Always-strict acceptance pipeline (v0.3: sequence tracking is part
    of the security model, not an optimization)."""

    def __init__(self, identity_pub: str, views: Dict[str, DeviceView]):
        self.identity_pub = identity_pub
        self.views = views

    def verify_message(self, msg: SignedMessage) -> Tuple[bool, str]:
        if not msg.cert.verify():
            return False, "cert invalid"
        if msg.cert.identity_pub != self.identity_pub:
            return False, "wrong identity"
        if not msg.verify_device_signature():
            return False, "bad device signature"
        view = self.views.get(msg.cert.device_pub)
        if view is None:
            view = DeviceView(cert=msg.cert)
            self.views[msg.cert.device_pub] = view
        elif view.cert is None:
            view.cert = msg.cert
        if (view.revocation is not None
                and msg.seq > view.revocation.last_valid_seq):
            return False, "device revoked"
        if not view.seen.add(msg.seq):
            return False, "seq reuse"
        return True, "ok"

    def process_revocation(self, rev: RevocationCert) -> Tuple[bool, str]:
        if not rev.verify():
            return False, "bad revocation signature"
        if rev.identity_pub != self.identity_pub:
            return False, "wrong identity"
        view = self.views.get(rev.device_pub)
        if view is None:
            view = DeviceView(cert=None)
            self.views[rev.device_pub] = view
        view.revocation = rev
        return True, "ok"
