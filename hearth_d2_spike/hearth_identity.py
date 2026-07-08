"""
hearth_identity — D2 device-enrollment model spike.

Implements the identity/device architecture locked in
hearth_concept_capture_v0_2.md, decision D2:

  * One IDENTITY per person, rooted in a 32-byte seed (paper-recoverable).
  * The identity key signs an ENROLLMENT CERTIFICATE for each device's own
    key (phone, home node, tablet...). Every enrolled device is a
    first-class holder of identity: the identity private key is replicated
    to each enrolled device, so any surviving device can enroll
    replacements or revoke lost ones.
  * Losing any one device loses nothing. Losing all devices at once is
    recoverable from the paper seed. Only losing all devices AND the seed
    is identity death.
  * In-person QR friend-adds are signed on the spot by the phone's own
    enrolled device key — the verifier never contacts the home node.
  * Revocation is an identity-signed statement naming a device key and a
    last-valid sequence number. Device signatures carry strictly
    monotonic per-device sequence numbers so that "signed before the
    theft" and "signed by the thief afterwards" can be told apart —
    *provided verifiers track sequence numbers they have seen* (this is
    the ambush the test suite probes).

Crypto: Ed25519 for everything; HKDF-SHA256 for seed -> identity key.
Payloads are canonical JSON (sorted keys, compact separators) so that
signatures are stable across implementations.

This is a SPIKE: single-process, in-memory, no network, no persistence,
no secure-element handling. Its job is to prove or break the design
stories, not to be the product.
"""

from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass, field
from typing import Dict, List, Optional

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.exceptions import InvalidSignature

PROTOCOL = "hearth-identity-spike-v0.1"


# --------------------------------------------------------------------------
# Canonical serialization + low-level helpers
# --------------------------------------------------------------------------

def canonical(obj: dict) -> bytes:
    """Deterministic JSON bytes: what gets signed must be byte-stable."""
    return json.dumps(obj, sort_keys=True, separators=(",", ":")).encode()


def derive_identity_private_key(seed: bytes) -> Ed25519PrivateKey:
    """Seed (paper backup) -> identity key, deterministically."""
    if len(seed) != 32:
        raise ValueError("seed must be exactly 32 bytes")
    key_material = HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=None,
        info=b"hearth/identity-key/v1",
    ).derive(seed)
    return Ed25519PrivateKey.from_private_bytes(key_material)


def pub_hex(key: Ed25519PublicKey) -> str:
    from cryptography.hazmat.primitives.serialization import (
        Encoding, PublicFormat,
    )
    return key.public_bytes(Encoding.Raw, PublicFormat.Raw).hex()


def pub_from_hex(h: str) -> Ed25519PublicKey:
    return Ed25519PublicKey.from_public_bytes(bytes.fromhex(h))


# --------------------------------------------------------------------------
# Wire objects (all plain dicts + detached hex signatures, spike-simple)
# --------------------------------------------------------------------------

@dataclass(frozen=True)
class EnrollmentCert:
    """Identity's signed statement: 'this device key speaks for me'."""
    identity_pub: str          # hex
    device_pub: str            # hex
    device_name: str
    enrolled_at: float
    signature: str             # hex, by identity key over body()

    def body(self) -> bytes:
        return canonical({
            "type": "enrollment",
            "protocol": PROTOCOL,
            "identity_pub": self.identity_pub,
            "device_pub": self.device_pub,
            "device_name": self.device_name,
            "enrolled_at": self.enrolled_at,
        })

    def verify(self) -> bool:
        try:
            pub_from_hex(self.identity_pub).verify(
                bytes.fromhex(self.signature), self.body())
            return True
        except (InvalidSignature, ValueError):
            return False


@dataclass(frozen=True)
class RevocationCert:
    """Identity's signed statement: 'this device no longer speaks for me;
    distrust anything it signs with seq > last_valid_seq'."""
    identity_pub: str
    device_pub: str
    last_valid_seq: int
    revoked_at: float
    signature: str

    def body(self) -> bytes:
        return canonical({
            "type": "revocation",
            "protocol": PROTOCOL,
            "identity_pub": self.identity_pub,
            "device_pub": self.device_pub,
            "last_valid_seq": self.last_valid_seq,
            "revoked_at": self.revoked_at,
        })

    def verify(self) -> bool:
        try:
            pub_from_hex(self.identity_pub).verify(
                bytes.fromhex(self.signature), self.body())
            return True
        except (InvalidSignature, ValueError):
            return False


@dataclass(frozen=True)
class SignedMessage:
    """Anything a device signs on behalf of the identity: posts, deletion
    tags, sync frames. Carries the device's enrollment cert so it is
    self-contained, and a strictly-increasing per-device seq."""
    cert: EnrollmentCert
    seq: int
    payload: dict
    signature: str             # by DEVICE key over body()

    def body(self) -> bytes:
        return canonical({
            "type": "message",
            "protocol": PROTOCOL,
            "identity_pub": self.cert.identity_pub,
            "device_pub": self.cert.device_pub,
            "seq": self.seq,
            "payload": self.payload,
        })

    def verify_device_signature(self) -> bool:
        try:
            pub_from_hex(self.cert.device_pub).verify(
                bytes.fromhex(self.signature), self.body())
            return True
        except (InvalidSignature, ValueError):
            return False


@dataclass(frozen=True)
class QRPayload:
    """What one phone shows the other at an in-person friend-add.
    Contains everything needed to verify OFFLINE: identity pub, the
    device's enrollment cert, and a device signature over the meeting
    nonce (proves live possession of the device key, blocks replay of a
    photographed QR at a later meeting)."""
    cert: EnrollmentCert
    nonce: str                 # hex, supplied by the SCANNING party
    signature: str             # by device key over body()

    def body(self) -> bytes:
        return canonical({
            "type": "qr-friend-add",
            "protocol": PROTOCOL,
            "identity_pub": self.cert.identity_pub,
            "device_pub": self.cert.device_pub,
            "nonce": self.nonce,
        })

    def verify_device_signature(self) -> bool:
        try:
            pub_from_hex(self.cert.device_pub).verify(
                bytes.fromhex(self.signature), self.body())
            return True
        except (InvalidSignature, ValueError):
            return False


# --------------------------------------------------------------------------
# Device
# --------------------------------------------------------------------------

class Device:
    """A phone, home node, tablet... Owns its own device key. Once
    enrolled, also holds a replica of the identity private key (D2:
    'every enrolled device is a first-class holder of identity')."""

    def __init__(self, name: str):
        self.name = name
        self._device_priv = Ed25519PrivateKey.generate()
        self.device_pub = pub_hex(self._device_priv.public_key())
        self.cert: Optional[EnrollmentCert] = None
        self._identity_priv: Optional[Ed25519PrivateKey] = None
        self._seq = 0
        self.destroyed = False

    # -- state guards ------------------------------------------------------

    def _alive(self):
        if self.destroyed:
            raise RuntimeError(f"device '{self.name}' is destroyed")

    def _enrolled(self):
        self._alive()
        if self.cert is None or self._identity_priv is None:
            raise RuntimeError(f"device '{self.name}' is not enrolled")

    # -- enrollment (receiving side) ----------------------------------------

    def install_enrollment(self, cert: EnrollmentCert,
                           identity_priv: Ed25519PrivateKey):
        """Called during pairing: an existing enrolled device (or the
        identity ceremony itself) hands this device its cert and the
        identity-key replica over a secure local channel."""
        self._alive()
        if not cert.verify() or cert.device_pub != self.device_pub:
            raise ValueError("enrollment cert invalid for this device")
        self.cert = cert
        self._identity_priv = identity_priv

    # -- identity-holder powers (any enrolled device can do these) ----------

    def enroll_new_device(self, other: "Device",
                          now: Optional[float] = None) -> EnrollmentCert:
        """Phone enrolls a replacement phone; home node enrolls a new
        phone; etc. This is the 'walk to your desk' story."""
        self._enrolled()
        cert = _make_enrollment(self._identity_priv, other.device_pub,
                                other.name, now or time.time())
        other.install_enrollment(cert, self._identity_priv)
        return cert

    def revoke_device(self, device_pub: str, last_valid_seq: int,
                      now: Optional[float] = None) -> RevocationCert:
        self._enrolled()
        return _make_revocation(self._identity_priv, device_pub,
                                last_valid_seq, now or time.time())

    # -- everyday signing ----------------------------------------------------

    def sign_message(self, payload: dict) -> SignedMessage:
        self._enrolled()
        self._seq += 1
        body = canonical({
            "type": "message",
            "protocol": PROTOCOL,
            "identity_pub": self.cert.identity_pub,
            "device_pub": self.cert.device_pub,
            "seq": self._seq,
            "payload": payload,
        })
        sig = self._device_priv.sign(body).hex()
        return SignedMessage(self.cert, self._seq, payload, sig)

    def sign_message_with_seq(self, payload: dict, seq: int) -> SignedMessage:
        """ATTACK HELPER (spike only): sign with an arbitrary sequence
        number, as a thief with a stolen device would after seeing a
        revocation — trying to backdate below last_valid_seq."""
        self._enrolled()
        body = canonical({
            "type": "message",
            "protocol": PROTOCOL,
            "identity_pub": self.cert.identity_pub,
            "device_pub": self.cert.device_pub,
            "seq": seq,
            "payload": payload,
        })
        sig = self._device_priv.sign(body).hex()
        return SignedMessage(self.cert, seq, payload, sig)

    def make_qr(self, scanner_nonce: str) -> QRPayload:
        """In-person friend-add: sign the scanning party's fresh nonce
        with THIS DEVICE's key. No home-node round trip — this is the
        delegated-signing claim under test."""
        self._enrolled()
        body = canonical({
            "type": "qr-friend-add",
            "protocol": PROTOCOL,
            "identity_pub": self.cert.identity_pub,
            "device_pub": self.cert.device_pub,
            "nonce": scanner_nonce,
        })
        sig = self._device_priv.sign(body).hex()
        return QRPayload(self.cert, scanner_nonce, sig)

    @property
    def current_seq(self) -> int:
        return self._seq

    def destroy(self):
        """House fire / drowned phone / reformatted PC. Keys gone."""
        self.destroyed = True
        self._device_priv = None
        self._identity_priv = None


def _make_enrollment(identity_priv: Ed25519PrivateKey, device_pub: str,
                     device_name: str, ts: float) -> EnrollmentCert:
    identity_pub = pub_hex(identity_priv.public_key())
    body = canonical({
        "type": "enrollment",
        "protocol": PROTOCOL,
        "identity_pub": identity_pub,
        "device_pub": device_pub,
        "device_name": device_name,
        "enrolled_at": ts,
    })
    return EnrollmentCert(identity_pub, device_pub, device_name, ts,
                          identity_priv.sign(body).hex())


def _make_revocation(identity_priv: Ed25519PrivateKey, device_pub: str,
                     last_valid_seq: int, ts: float) -> RevocationCert:
    identity_pub = pub_hex(identity_priv.public_key())
    body = canonical({
        "type": "revocation",
        "protocol": PROTOCOL,
        "identity_pub": identity_pub,
        "device_pub": device_pub,
        "last_valid_seq": last_valid_seq,
        "revoked_at": ts,
    })
    return RevocationCert(identity_pub, device_pub, last_valid_seq, ts,
                          identity_priv.sign(body).hex())


# --------------------------------------------------------------------------
# Identity ceremony + seed recovery
# --------------------------------------------------------------------------

class IdentityCeremony:
    """The one-time (or recovery-time) act of holding the raw identity
    key. Exists only long enough to enroll the first device(s); the seed
    goes on paper, the key replicas live on enrolled devices."""

    def __init__(self, seed: Optional[bytes] = None):
        self.seed = seed if seed is not None else os.urandom(32)
        self._identity_priv = derive_identity_private_key(self.seed)
        self.identity_pub = pub_hex(self._identity_priv.public_key())

    def enroll_first_device(self, device: Device,
                            now: Optional[float] = None) -> EnrollmentCert:
        cert = _make_enrollment(self._identity_priv, device.device_pub,
                                device.name, now or time.time())
        device.install_enrollment(cert, self._identity_priv)
        return cert

    def paper_seed(self) -> str:
        """What goes in the drawer. Hex for the spike; word-list encoding
        is a UX question for the real build."""
        return self.seed.hex()

    @staticmethod
    def recover(paper_seed_hex: str) -> "IdentityCeremony":
        return IdentityCeremony(bytes.fromhex(paper_seed_hex))


# --------------------------------------------------------------------------
# PeerVerifier — a FRIEND's view of your identity
# --------------------------------------------------------------------------

@dataclass
class _DeviceView:
    cert: EnrollmentCert
    max_seen_seq: int = 0
    revocation: Optional[RevocationCert] = None


class PeerVerifier:
    """Everything a friend's client knows about you and does with it.

    track_seqs=False models a naive client that verifies signatures and
    revocations but does NOT remember sequence numbers it has seen — the
    configuration expected to be vulnerable to backdating by a thief.
    """

    def __init__(self, name: str, track_seqs: bool = True):
        self.name = name
        self.track_seqs = track_seqs
        self.friend_identity_pub: Optional[str] = None
        self.devices: Dict[str, _DeviceView] = {}
        self.log: List[str] = []

    # -- friend-add ----------------------------------------------------------

    def fresh_nonce(self) -> str:
        return os.urandom(16).hex()

    def add_friend_via_qr(self, qr: QRPayload, expected_nonce: str) -> bool:
        """Fully offline verification of an in-person QR."""
        if qr.nonce != expected_nonce:
            self.log.append("QR rejected: nonce mismatch (replay?)")
            return False
        if not qr.cert.verify():
            self.log.append("QR rejected: enrollment cert signature bad")
            return False
        if not qr.verify_device_signature():
            self.log.append("QR rejected: device signature bad")
            return False
        if (self.friend_identity_pub is not None
                and qr.cert.identity_pub != self.friend_identity_pub):
            self.log.append("QR rejected: identity mismatch (impostor?)")
            return False
        self.friend_identity_pub = qr.cert.identity_pub
        self.devices[qr.cert.device_pub] = _DeviceView(cert=qr.cert)
        self.log.append(f"friend added: identity {qr.cert.identity_pub[:8]}… "
                        f"via device '{qr.cert.device_name}'")
        return True

    # -- gossip intake --------------------------------------------------------

    def process_revocation(self, rev: RevocationCert) -> bool:
        if not rev.verify():
            self.log.append("revocation rejected: bad identity signature")
            return False
        if rev.identity_pub != self.friend_identity_pub:
            self.log.append("revocation rejected: not my friend's identity")
            return False
        view = self.devices.get(rev.device_pub)
        if view is None:
            # Revocation for a device we never saw — store it anyway so a
            # later-arriving cert for that device is dead on arrival.
            view = _DeviceView(cert=None)  # type: ignore[arg-type]
            self.devices[rev.device_pub] = view
        view.revocation = rev
        self.log.append(f"revocation recorded for device "
                        f"{rev.device_pub[:8]}… last_valid_seq="
                        f"{rev.last_valid_seq}")
        return True

    # -- message verification --------------------------------------------------

    def verify_message(self, msg: SignedMessage) -> bool:
        """The full acceptance pipeline a real client would run."""
        # 1. cert chain: identity -> device
        if not msg.cert.verify():
            self.log.append("msg rejected: enrollment cert invalid")
            return False
        if msg.cert.identity_pub != self.friend_identity_pub:
            self.log.append("msg rejected: not my friend's identity")
            return False
        # 2. device signature over body
        if not msg.verify_device_signature():
            self.log.append("msg rejected: device signature invalid")
            return False
        # 3. auto-learn devices enrolled after we became friends
        view = self.devices.get(msg.cert.device_pub)
        if view is None:
            view = _DeviceView(cert=msg.cert)
            self.devices[msg.cert.device_pub] = view
            self.log.append(f"learned new device '{msg.cert.device_name}' "
                            f"({msg.cert.device_pub[:8]}…) from valid cert")
        elif view.cert is None:
            view.cert = msg.cert
        # 4. revocation check
        if view.revocation is not None:
            if msg.seq > view.revocation.last_valid_seq:
                self.log.append(f"msg rejected: device revoked "
                                f"(seq {msg.seq} > last_valid "
                                f"{view.revocation.last_valid_seq})")
                return False
            # seq <= last_valid_seq: only acceptable if we can rule out
            # reuse — which requires seq tracking.
            if not self.track_seqs:
                self.log.append(f"msg ACCEPTED (naive client): seq {msg.seq} "
                                f"<= last_valid; cannot detect reuse")
                return True
        # 5. monotonicity / reuse defense
        if self.track_seqs:
            if msg.seq <= view.max_seen_seq:
                self.log.append(f"msg rejected: seq {msg.seq} not above "
                                f"max seen {view.max_seen_seq} "
                                f"(replay/backdate)")
                return False
            view.max_seen_seq = msg.seq
        self.log.append(f"msg accepted: seq {msg.seq} from "
                        f"'{msg.cert.device_name}'")
        return True
