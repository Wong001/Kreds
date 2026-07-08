# Hearth Vertical Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Three Hearth node processes on one machine (wong-phone, wong-homenode, freja-phone) exchanging signed posts with photos over real TCP gossip, with D2 identity (seen-set + retro-drop), D3 deletion, device revocation, and a localhost web UI per node.

**Architecture:** Each process = one enrolled device = one node with its own SQLite store, gossip TCP port, and localhost HTTP port. Layered: identity (Ed25519 certs/verifier) → messages (payload semantics) → store (SQLite) → node (daemon glue) → sync (gossip protocol) + api/web (FastAPI + vanilla JS). Spec: `docs/superpowers/specs/2026-07-02-hearth-vertical-slice-design.md`.

**Tech Stack:** Python 3.12, `cryptography` (Ed25519/HKDF), FastAPI + uvicorn (HTTP/WebSocket), sqlite3 (stdlib), asyncio TCP (gossip), vanilla HTML/JS/CSS (no build toolchain), pytest.

## Global Constraints

- Python: `.venv` in repo root; run everything as `.\.venv\Scripts\python.exe ...` from `C:\Users\Wong\Desktop\Hearth`.
- Dependencies (exact): `cryptography`, `fastapi`, `uvicorn[standard]`, `httpx`, `python-multipart`, `pytest`. Nothing else.
- Console output ASCII only (cp1252 console — no `…`, `°`, `—`, `←` in print statements).
- Protocol string everywhere: `PROTOCOL = "hearth/v0.1"`.
- Canonical JSON for all signed bodies: `json.dumps(obj, sort_keys=True, separators=(",", ":")).encode()`.
- All keys Ed25519; identity key derived from 32-byte seed via HKDF-SHA256, info `b"hearth/identity-key/v1"`.
- Product code (`hearth/`) MUST NOT import from `hearth_d2_spike/`.
- Blob cap: 5 MB (`MAX_BLOB_BYTES = 5 * 1024 * 1024`). Frame cap: 16 MB.
- Demo port convention: wong-phone 7101(gossip)/7201(http), wong-homenode 7102/7202, freja-phone 7103/7203. All listeners bind `127.0.0.1` only.
- `run/` (node data dirs) is gitignored.
- Run tests from repo root: `.\.venv\Scripts\python.exe -m pytest tests -q` (root `conftest.py` makes `hearth` importable).
- Commit after every task with the trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: Scaffolding + dependencies

**Files:**
- Create: `requirements.txt`, `conftest.py`, `hearth/__init__.py`, `tests/__init__.py` (empty), modify `.gitignore`

**Interfaces:**
- Produces: importable empty `hearth` package; installed deps; `run/` ignored.

- [ ] **Step 1: Create files**

`requirements.txt`:
```
cryptography
fastapi
uvicorn[standard]
httpx
python-multipart
pytest
```

`conftest.py` (repo root — makes `hearth/` importable during pytest):
```python
# Root conftest so pytest adds the repo root to sys.path.
```

`hearth/__init__.py`:
```python
"""Hearth — private P2P social platform, vertical slice v0.1."""
```

`tests/__init__.py`: empty file.

Append to `.gitignore`:
```
run/
```

- [ ] **Step 2: Install dependencies**

Run: `.\.venv\Scripts\python.exe -m pip install -r requirements.txt`
Expected: installs fastapi/uvicorn/httpx/python-multipart (cryptography, pytest already present).

- [ ] **Step 3: Verify imports**

Run: `.\.venv\Scripts\python.exe -c "import hearth, fastapi, uvicorn, httpx; print('ok')"`
Expected: `ok`

- [ ] **Step 4: Commit**

```bash
git add requirements.txt conftest.py hearth tests .gitignore
git commit -m "chore: scaffold hearth package and dependencies"
```

---

### Task 2: Identity core — canonical JSON, keys, certs, envelope, DeviceKeys, ceremony

**Files:**
- Create: `hearth/identity.py`
- Test: `tests/test_identity_core.py`

**Interfaces:**
- Produces (exact, used by every later task):
  - `canonical(obj: dict) -> bytes`
  - `derive_identity_private_key(seed: bytes) -> Ed25519PrivateKey`
  - `pub_hex(key) -> str`, `pub_from_hex(h) -> Ed25519PublicKey`, `priv_hex(key) -> str`, `priv_from_hex(h) -> Ed25519PrivateKey`
  - `EnrollmentCert(identity_pub, device_pub, device_name, enrolled_at, signature)` with `.body()`, `.verify()`, `.to_dict()`, `EnrollmentCert.from_dict(d)`
  - `RevocationCert(identity_pub, device_pub, last_valid_seq, revoked_at, signature)` with same methods
  - `SignedMessage(cert, seq, payload, signature)` with `.body()`, `.msg_id` (property, SHA-256 hex of body), `.verify_device_signature()`, `.to_dict()`, `SignedMessage.from_dict(d)`
  - `DeviceKeys` with `.create(name)`, `.install(cert, identity_priv_hex)`, `.sign_message(payload) -> SignedMessage` (increments `.seq`), `.sign_raw(data: bytes) -> str`, `.enroll_other(device_pub, device_name, now=None) -> EnrollmentCert`, `.make_revocation(device_pub, last_valid_seq, now=None) -> RevocationCert`, `.to_json() -> dict`, `DeviceKeys.from_json(d)`, properties `.device_pub`, `.identity_pub`, `.cert`, `.seq`, `.name`
  - `IdentityCeremony(seed=None)` with `.identity_pub`, `.paper_seed() -> str`, `.enroll_first_device(device: DeviceKeys) -> EnrollmentCert`, `IdentityCeremony.recover(paper_seed_hex)`

- [ ] **Step 1: Write the failing tests**

`tests/test_identity_core.py`:
```python
from hearth.identity import (
    DeviceKeys, IdentityCeremony, EnrollmentCert, SignedMessage, canonical,
)


def make_person():
    ceremony = IdentityCeremony()
    phone = DeviceKeys.create("phone")
    ceremony.enroll_first_device(phone)
    return ceremony, phone


def test_seed_recovery_is_deterministic():
    ceremony, _ = make_person()
    recovered = IdentityCeremony.recover(ceremony.paper_seed())
    assert recovered.identity_pub == ceremony.identity_pub


def test_enrollment_cert_verifies_and_roundtrips():
    _, phone = make_person()
    assert phone.cert.verify()
    clone = EnrollmentCert.from_dict(phone.cert.to_dict())
    assert clone.verify() and clone == phone.cert


def test_tampered_cert_fails():
    from dataclasses import replace
    _, phone = make_person()
    other = DeviceKeys.create("evil")
    forged = replace(phone.cert, device_pub=other.device_pub)
    assert forged.verify() is False


def test_device_enrolls_another_device():
    _, phone = make_person()
    node = DeviceKeys.create("homenode")
    cert = phone.enroll_other(node.device_pub, node.name)
    node.install(cert, phone.to_json()["identity_priv"])
    assert node.cert.verify()
    assert node.identity_pub == phone.identity_pub


def test_sign_message_increments_seq_and_verifies():
    _, phone = make_person()
    m1 = phone.sign_message({"kind": "post", "text": "a"})
    m2 = phone.sign_message({"kind": "post", "text": "b"})
    assert (m1.seq, m2.seq) == (1, 2)
    assert m1.verify_device_signature() and m2.verify_device_signature()
    assert m1.msg_id != m2.msg_id
    assert SignedMessage.from_dict(m1.to_dict()).msg_id == m1.msg_id


def test_devicekeys_json_roundtrip_preserves_seq():
    _, phone = make_person()
    phone.sign_message({"kind": "post", "text": "a"})
    restored = DeviceKeys.from_json(phone.to_json())
    m = restored.sign_message({"kind": "post", "text": "b"})
    assert m.seq == 2 and m.verify_device_signature()


def test_revocation_cert_verifies():
    _, phone = make_person()
    rev = phone.make_revocation("ab" * 32, 7)
    assert rev.verify() and rev.last_valid_seq == 7
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_identity_core.py -q`
Expected: FAIL / collection error — `No module named 'hearth.identity'` (or ImportError).

- [ ] **Step 3: Implement `hearth/identity.py` (identity core half)**

```python
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
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    NoEncryption,
    PrivateFormat,
    PublicFormat,
)

PROTOCOL = "hearth/v0.1"


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
    protection is stated out of scope in the spec."""

    def __init__(self, name: str, device_priv: Ed25519PrivateKey,
                 cert: Optional[EnrollmentCert] = None,
                 identity_priv: Optional[Ed25519PrivateKey] = None,
                 seq: int = 0):
        self.name = name
        self._device_priv = device_priv
        self.device_pub = pub_hex(device_priv.public_key())
        self.cert = cert
        self._identity_priv = identity_priv
        self.seq = seq

    @staticmethod
    def create(name: str) -> "DeviceKeys":
        return DeviceKeys(name, Ed25519PrivateKey.generate())

    @property
    def identity_pub(self) -> Optional[str]:
        if self._identity_priv is None:
            return None
        return pub_hex(self._identity_priv.public_key())

    def _enrolled(self):
        if self.cert is None or self._identity_priv is None:
            raise RuntimeError(f"device '{self.name}' is not enrolled")

    def install(self, cert: EnrollmentCert, identity_priv_hex: str):
        if not cert.verify() or cert.device_pub != self.device_pub:
            raise ValueError("enrollment cert invalid for this device")
        self.cert = cert
        self._identity_priv = priv_from_hex(identity_priv_hex)

    def sign_message(self, payload: dict) -> SignedMessage:
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
        return self._device_priv.sign(data).hex()

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

    def to_json(self) -> dict:
        return {
            "name": self.name,
            "device_priv": priv_hex(self._device_priv),
            "cert": self.cert.to_dict() if self.cert else None,
            "identity_priv": (priv_hex(self._identity_priv)
                              if self._identity_priv else None),
            "seq": self.seq,
        }

    @staticmethod
    def from_json(d: dict) -> "DeviceKeys":
        return DeviceKeys(
            d["name"], priv_from_hex(d["device_priv"]),
            EnrollmentCert.from_dict(d["cert"]) if d["cert"] else None,
            priv_from_hex(d["identity_priv"]) if d["identity_priv"] else None,
            d["seq"],
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_identity_core.py -q`
Expected: 7 passed.

- [ ] **Step 5: Commit**

```bash
git add hearth/identity.py tests/test_identity_core.py
git commit -m "feat: identity core - keys, certs, envelope, ceremony"
```

---

### Task 3: SeenSet — compactable seen-sequence set (Ambush 2 resolution)

**Files:**
- Modify: `hearth/identity.py` (append)
- Test: `tests/test_seenset.py`

**Interfaces:**
- Produces (in `hearth.identity`):
  - `SeenSet(contiguous: int = 0, sparse: set | None = None)` with `.has(seq) -> bool`, `.add(seq) -> bool` (False if already seen or seq < 1; compacts), `.max_seen() -> int`, `.to_json() -> dict` (`{"contiguous": int, "sparse": [int]}`), `SeenSet.from_json(d)`, `SeenSet.summary_has(summary: dict, seq: int) -> bool` (static, works on a `to_json()` dict)

- [ ] **Step 1: Write the failing tests**

`tests/test_seenset.py`:
```python
from hearth.identity import SeenSet


def test_in_order_compacts():
    s = SeenSet()
    for i in (1, 2, 3):
        assert s.add(i) is True
    assert s.to_json() == {"contiguous": 3, "sparse": []}


def test_out_of_order_is_legal_then_compacts():
    s = SeenSet()
    assert s.add(3) is True          # later message arrives first
    assert s.add(1) is True          # earlier one still accepted (Ambush 2)
    assert s.to_json() == {"contiguous": 1, "sparse": [3]}
    assert s.add(2) is True          # gap fills -> full compaction
    assert s.to_json() == {"contiguous": 3, "sparse": []}


def test_reuse_rejected_in_all_positions():
    s = SeenSet()
    for i in (1, 2, 5):
        s.add(i)
    assert s.add(1) is False         # below contiguous
    assert s.add(5) is False         # in sparse
    assert s.add(0) is False and s.add(-4) is False


def test_max_seen_and_roundtrip():
    s = SeenSet()
    for i in (1, 7, 3):
        s.add(i)
    assert s.max_seen() == 7
    clone = SeenSet.from_json(s.to_json())
    assert clone.has(7) and clone.has(1) and not clone.has(2)


def test_summary_has_static():
    s = SeenSet()
    for i in (1, 2, 6):
        s.add(i)
    summary = s.to_json()
    assert SeenSet.summary_has(summary, 2) is True
    assert SeenSet.summary_has(summary, 6) is True
    assert SeenSet.summary_has(summary, 4) is False
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_seenset.py -q`
Expected: FAIL — `ImportError: cannot import name 'SeenSet'`.

- [ ] **Step 3: Append implementation to `hearth/identity.py`**

```python
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_seenset.py -q`
Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
git add hearth/identity.py tests/test_seenset.py
git commit -m "feat: SeenSet - compactable seen-sequence set (Ambush 2)"
```

---

### Task 4: Verifier — always-strict acceptance pipeline with revocation + seen-set

**Files:**
- Modify: `hearth/identity.py` (append)
- Test: `tests/test_verifier.py`

**Interfaces:**
- Produces (in `hearth.identity`):
  - `DeviceView(cert: EnrollmentCert | None, revocation: RevocationCert | None, seen: SeenSet)` — dataclass, mutable
  - `Verifier(identity_pub: str, views: Dict[str, DeviceView])` with:
    - `.verify_message(msg: SignedMessage) -> Tuple[bool, str]` — full pipeline: cert chain, identity match, device signature, auto-learn device, revocation bound, seen-set add. Mutates `views` (auto-learn + seen). Reason strings used by tests and store: `"cert invalid"`, `"wrong identity"`, `"bad device signature"`, `"device revoked"`, `"seq reuse"`, `"ok"`.
    - `.process_revocation(rev: RevocationCert) -> Tuple[bool, str]` — reasons: `"bad revocation signature"`, `"wrong identity"`, `"ok"`. Creates a certless view if the device is unknown (revocation-before-cert case).
  - Retro-drop is applied by the Store (Task 7) using `view.revocation.last_valid_seq`; the Verifier records the fact.

- [ ] **Step 1: Write the failing tests** (the spike's adversarial suite, ported to production semantics — out-of-order now PASSES)

`tests/test_verifier.py`:
```python
from dataclasses import replace

from hearth.identity import (
    DeviceKeys, DeviceView, IdentityCeremony, SeenSet, SignedMessage,
    Verifier, canonical,
)


def wong_with_verifier():
    ceremony = IdentityCeremony()
    phone = DeviceKeys.create("wong-phone")
    node = DeviceKeys.create("wong-homenode")
    ceremony.enroll_first_device(phone)
    node.install(phone.enroll_other(node.device_pub, node.name),
                 phone.to_json()["identity_priv"])
    views = {}
    v = Verifier(ceremony.identity_pub, views)
    return ceremony, phone, node, v, views


def backdated(device, payload, seq):
    """Test-only forge: sign with an arbitrary (reused) seq, as a thief
    with a live stolen device would."""
    from hearth.identity import PROTOCOL
    body = canonical({
        "type": "message", "protocol": PROTOCOL,
        "identity_pub": device.cert.identity_pub,
        "device_pub": device.cert.device_pub,
        "seq": seq, "payload": payload,
    })
    return SignedMessage(device.cert, seq, payload,
                         device._device_priv.sign(body).hex())


def test_valid_message_accepted_and_device_autolearned():
    _, phone, _, v, views = wong_with_verifier()
    ok, reason = v.verify_message(phone.sign_message({"kind": "post"}))
    assert (ok, reason) == (True, "ok")
    assert phone.device_pub in views


def test_wrong_identity_rejected():
    _, _, _, v, _ = wong_with_verifier()
    mal = DeviceKeys.create("mallory")
    IdentityCeremony().enroll_first_device(mal)
    ok, reason = v.verify_message(mal.sign_message({"kind": "post"}))
    assert (ok, reason) == (False, "wrong identity")


def test_grafted_cert_rejected():
    _, phone, _, v, _ = wong_with_verifier()
    msg = phone.sign_message({"kind": "post"})
    mal = DeviceKeys.create("mallory")
    forged = SignedMessage(replace(msg.cert, device_pub=mal.device_pub),
                           msg.seq, msg.payload, msg.signature)
    ok, reason = v.verify_message(forged)
    assert (ok, reason) == (False, "cert invalid")


def test_out_of_order_delivery_now_legal():
    """Ambush 2 resolved: the spike's failing case must pass in product."""
    _, phone, _, v, _ = wong_with_verifier()
    m1 = phone.sign_message({"n": 1})
    m2 = phone.sign_message({"n": 2})
    assert v.verify_message(m2)[0] is True    # later arrives first
    assert v.verify_message(m1)[0] is True    # earlier still accepted
    assert v.verify_message(m1) == (False, "seq reuse")  # replay rejected


def test_post_revocation_seq_rejected():
    _, phone, node, v, _ = wong_with_verifier()
    for _ in range(3):
        assert v.verify_message(phone.sign_message({"kind": "post"}))[0]
    ok, _ = v.process_revocation(node.make_revocation(phone.device_pub, 3))
    assert ok
    loot = phone.sign_message({"kind": "post", "text": "send crypto"})
    assert v.verify_message(loot) == (False, "device revoked")


def test_backdating_thief_blocked_by_seen_set():
    """Ambush 1: reuse below last_valid_seq is caught by the seen-set."""
    _, phone, node, v, _ = wong_with_verifier()
    for _ in range(3):
        assert v.verify_message(phone.sign_message({"kind": "post"}))[0]
    assert v.process_revocation(node.make_revocation(phone.device_pub, 3))[0]
    assert v.verify_message(backdated(phone, {"kind": "post"}, 2)) == \
        (False, "seq reuse")


def test_backdated_but_unseen_seq_below_bound_accepted():
    """A legitimately-late message (seq <= last_valid, never seen) is OK:
    seen-set + revocation bound compose exactly as the spike report says."""
    _, phone, node, v, _ = wong_with_verifier()
    m1 = phone.sign_message({"n": 1})
    m2 = phone.sign_message({"n": 2})
    assert v.verify_message(m2)[0] is True          # m1 delayed in gossip
    assert v.process_revocation(node.make_revocation(phone.device_pub, 2))[0]
    assert v.verify_message(m1)[0] is True          # late but legit


def test_revocation_before_cert_kills_device_on_arrival():
    _, phone, node, v, _ = wong_with_verifier()
    tablet = DeviceKeys.create("wong-tablet")
    tablet.install(phone.enroll_other(tablet.device_pub, tablet.name),
                   phone.to_json()["identity_priv"])
    assert v.process_revocation(node.make_revocation(tablet.device_pub, 0))[0]
    loot = tablet.sign_message({"kind": "post"})
    assert v.verify_message(loot) == (False, "device revoked")


def test_forged_revocation_rejected():
    _, phone, _, v, _ = wong_with_verifier()
    mal = DeviceKeys.create("mallory")
    IdentityCeremony().enroll_first_device(mal)
    fake = mal.make_revocation(phone.device_pub, 0)
    assert v.process_revocation(fake) == (False, "wrong identity")
    assert v.verify_message(phone.sign_message({"kind": "post"}))[0] is True
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_verifier.py -q`
Expected: FAIL — `ImportError: cannot import name 'DeviceView'`.

- [ ] **Step 3: Append implementation to `hearth/identity.py`**

```python
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_verifier.py tests/test_identity_core.py tests/test_seenset.py -q`
Expected: 21 passed (9 + 7 + 5).

- [ ] **Step 5: Commit**

```bash
git add hearth/identity.py tests/test_verifier.py
git commit -m "feat: always-strict Verifier with seen-set and revocations"
```

---

### Task 5: messages.py — payload semantics (post / profile / delete)

**Files:**
- Create: `hearth/messages.py`
- Test: `tests/test_messages.py`

**Interfaces:**
- Consumes: `DeviceKeys.sign_message`, `SignedMessage` from Task 2.
- Produces (in `hearth.messages`):
  - Constants: `KIND_POST = "post"`, `KIND_PROFILE = "profile"`, `KIND_DELETE = "delete"`, `MAX_TEXT = 4000`, `MAX_NAME = 80`, `MAX_BLOB_BYTES = 5 * 1024 * 1024`
  - `blob_hash(data: bytes) -> str` (SHA-256 hex)
  - `make_post(device, text, blob_refs=(), expires_at=None, now=None) -> SignedMessage` — payload `{"kind","text","blobs","created_at","expires_at"}`
  - `make_profile(device, name, now=None) -> SignedMessage` — payload `{"kind","name","created_at"}`
  - `make_delete(device, target_msg_id, now=None) -> SignedMessage` — payload `{"kind","target","created_at"}`
  - `validate_payload(p: dict) -> tuple[bool, str]`
  - `is_expired(payload, now=None) -> bool`

- [ ] **Step 1: Write the failing tests**

`tests/test_messages.py`:
```python
import time

from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import (
    blob_hash, is_expired, make_delete, make_post, make_profile,
    validate_payload,
)


def device():
    d = DeviceKeys.create("phone")
    IdentityCeremony().enroll_first_device(d)
    return d


def test_post_payload_valid_and_signed():
    d = device()
    ref = blob_hash(b"fake-photo")
    m = make_post(d, "hej", [ref], expires_at=None, now=1000.0)
    assert m.payload == {"kind": "post", "text": "hej", "blobs": [ref],
                         "created_at": 1000.0, "expires_at": None}
    assert validate_payload(m.payload) == (True, "ok")
    assert m.verify_device_signature()


def test_profile_and_delete_payloads_valid():
    d = device()
    assert validate_payload(make_profile(d, "Wong").payload) == (True, "ok")
    target = make_post(d, "x", now=1.0).msg_id
    assert validate_payload(make_delete(d, target).payload) == (True, "ok")


def test_invalid_payloads_rejected():
    ok = lambda p: validate_payload(p)[0]
    assert not ok({"kind": "post", "created_at": 1.0})            # no text
    assert not ok({"kind": "post", "text": "x" * 4001,
                   "blobs": [], "created_at": 1.0})               # too long
    assert not ok({"kind": "post", "text": "x", "blobs": ["zz"],
                   "created_at": 1.0})                            # bad ref
    assert not ok({"kind": "profile", "name": "", "created_at": 1.0})
    assert not ok({"kind": "delete", "target": "nope", "created_at": 1.0})
    assert not ok({"kind": "wat", "created_at": 1.0})
    assert not ok({"kind": "post", "text": "x", "blobs": []})     # no ts


def test_expiry():
    now = time.time()
    p = {"kind": "post", "text": "x", "blobs": [],
         "created_at": now, "expires_at": now - 1}
    assert is_expired(p) is True
    p["expires_at"] = now + 3600
    assert is_expired(p) is False
    p["expires_at"] = None
    assert is_expired(p) is False
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_messages.py -q`
Expected: FAIL — `No module named 'hearth.messages'`.

- [ ] **Step 3: Implement `hearth/messages.py`**

```python
"""Payload semantics for the three Hearth message kinds (spec: Data model)."""
from __future__ import annotations

import hashlib
import time
from typing import Optional, Sequence, Tuple

from .identity import DeviceKeys, SignedMessage

KIND_POST = "post"
KIND_PROFILE = "profile"
KIND_DELETE = "delete"
MAX_TEXT = 4000
MAX_NAME = 80
MAX_BLOB_BYTES = 5 * 1024 * 1024


def blob_hash(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _now(now: Optional[float]) -> float:
    return now if now is not None else time.time()


def make_post(device: DeviceKeys, text: str,
              blob_refs: Sequence[str] = (),
              expires_at: Optional[float] = None,
              now: Optional[float] = None) -> SignedMessage:
    return device.sign_message({
        "kind": KIND_POST, "text": text, "blobs": list(blob_refs),
        "created_at": _now(now), "expires_at": expires_at,
    })


def make_profile(device: DeviceKeys, name: str,
                 now: Optional[float] = None) -> SignedMessage:
    return device.sign_message({
        "kind": KIND_PROFILE, "name": name, "created_at": _now(now),
    })


def make_delete(device: DeviceKeys, target_msg_id: str,
                now: Optional[float] = None) -> SignedMessage:
    return device.sign_message({
        "kind": KIND_DELETE, "target": target_msg_id,
        "created_at": _now(now),
    })


def _is_hex64(s) -> bool:
    return (isinstance(s, str) and len(s) == 64
            and all(c in "0123456789abcdef" for c in s))


def validate_payload(p: dict) -> Tuple[bool, str]:
    if not isinstance(p, dict):
        return False, "payload not a dict"
    if not isinstance(p.get("created_at"), (int, float)):
        return False, "bad created_at"
    kind = p.get("kind")
    if kind == KIND_POST:
        if not isinstance(p.get("text"), str) or len(p["text"]) > MAX_TEXT:
            return False, "bad text"
        blobs = p.get("blobs")
        if not isinstance(blobs, list) or not all(_is_hex64(b) for b in blobs):
            return False, "bad blobs"
        exp = p.get("expires_at")
        if exp is not None and not isinstance(exp, (int, float)):
            return False, "bad expires_at"
        return True, "ok"
    if kind == KIND_PROFILE:
        name = p.get("name")
        if not isinstance(name, str) or not (1 <= len(name) <= MAX_NAME):
            return False, "bad name"
        return True, "ok"
    if kind == KIND_DELETE:
        if not _is_hex64(p.get("target")):
            return False, "bad target"
        return True, "ok"
    return False, "unknown kind"


def is_expired(payload: dict, now: Optional[float] = None) -> bool:
    exp = payload.get("expires_at")
    return exp is not None and exp <= _now(now)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_messages.py -q`
Expected: 4 passed.

- [ ] **Step 5: Commit**

```bash
git add hearth/messages.py tests/test_messages.py
git commit -m "feat: message payload semantics - post, profile, delete"
```

---

### Task 6: Store part 1 — schema, identities, device views, peers, blobs

**Files:**
- Create: `hearth/store.py`
- Test: `tests/test_store_persistence.py`

**Interfaces:**
- Consumes: `DeviceView`, `SeenSet`, `EnrollmentCert`, `RevocationCert`, `Verifier`, `SignedMessage` (Tasks 2-4); `MAX_BLOB_BYTES`, `blob_hash`, kind constants, `validate_payload` (Task 5).
- Produces (class `hearth.store.Store(path)` — sqlite, thread-safe via RLock, `check_same_thread=False`, WAL):
  - `set_meta(k, v)`, `get_meta(k) -> str | None`
  - `add_identity(identity_pub, is_self=False)`, `known_identities() -> list[str]`, `is_known(pub) -> bool`, `self_identity() -> str | None`
  - `load_views(identity_pub) -> dict[str, DeviceView]`, `save_views(identity_pub, views)`
  - `all_summaries() -> dict` (`{identity_pub: {device_pub: seen.to_json()}}`), `list_revocations() -> list[RevocationCert]`
  - `add_peer(address, identity_pub=None)`, `list_peers() -> list[dict]` (`{"address", "identity_pub"}`)
  - `put_blob(data) -> str` (raises `ValueError` over `MAX_BLOB_BYTES`), `get_blob(h) -> bytes | None`, `has_blob(h) -> bool`
  - `IngestResult` dataclass: `accepted: bool, reason: str, msg_id: str | None = None, retro_dropped: list[str] = [], deleted_target: str | None = None`
  - (Ingest/feed/tombstones/GC arrive in Task 7 — same file.)

- [ ] **Step 1: Write the failing tests**

`tests/test_store_persistence.py`:
```python
import pytest

from hearth.identity import (
    DeviceKeys, DeviceView, IdentityCeremony, RevocationCert, SeenSet,
)
from hearth.store import Store


def make_store(tmp_path, name="a.db"):
    return Store(tmp_path / name)


def test_identities_and_meta(tmp_path):
    s = make_store(tmp_path)
    s.add_identity("aa" * 32, is_self=True)
    s.add_identity("bb" * 32)
    s.add_identity("bb" * 32)                    # idempotent
    assert s.self_identity() == "aa" * 32
    assert set(s.known_identities()) == {"aa" * 32, "bb" * 32}
    assert s.is_known("bb" * 32) and not s.is_known("cc" * 32)
    s.set_meta("gossip_addr", "127.0.0.1:7101")
    assert s.get_meta("gossip_addr") == "127.0.0.1:7101"
    assert s.get_meta("nope") is None


def test_device_views_persist_across_reopen(tmp_path):
    phone = DeviceKeys.create("phone")
    IdentityCeremony().enroll_first_device(phone)
    ident = phone.identity_pub
    rev = phone.make_revocation("dd" * 32, 5)

    s = make_store(tmp_path)
    s.add_identity(ident, is_self=True)
    seen = SeenSet()
    for i in (1, 2, 7):
        seen.add(i)
    views = {phone.device_pub: DeviceView(cert=phone.cert, seen=seen),
             "dd" * 32: DeviceView(cert=None, revocation=rev)}
    s.save_views(ident, views)

    s2 = make_store(tmp_path)                    # reopen same file
    loaded = s2.load_views(ident)
    assert loaded[phone.device_pub].cert == phone.cert
    assert loaded[phone.device_pub].seen.has(7)
    assert not loaded[phone.device_pub].seen.has(3)
    assert loaded["dd" * 32].revocation == rev
    assert [r for r in s2.list_revocations()] == [rev]
    summaries = s2.all_summaries()
    assert summaries[ident][phone.device_pub] == {"contiguous": 2,
                                                  "sparse": [7]}


def test_peers(tmp_path):
    s = make_store(tmp_path)
    s.add_peer("127.0.0.1:7102", "aa" * 32)
    s.add_peer("127.0.0.1:7102", "aa" * 32)      # idempotent
    s.add_peer("127.0.0.1:7103")
    addrs = {p["address"] for p in s.list_peers()}
    assert addrs == {"127.0.0.1:7102", "127.0.0.1:7103"}


def test_blobs_roundtrip_and_size_cap(tmp_path):
    from hearth.messages import MAX_BLOB_BYTES, blob_hash
    s = make_store(tmp_path)
    data = b"photo-bytes"
    h = s.put_blob(data)
    assert h == blob_hash(data)
    assert s.get_blob(h) == data and s.has_blob(h)
    assert s.get_blob("ee" * 32) is None
    with pytest.raises(ValueError):
        s.put_blob(b"x" * (MAX_BLOB_BYTES + 1))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_store_persistence.py -q`
Expected: FAIL — `No module named 'hearth.store'`.

- [ ] **Step 3: Implement `hearth/store.py` (part 1)**

```python
"""SQLite persistence for one Hearth node (one device)."""
from __future__ import annotations

import json
import sqlite3
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Set

from .identity import (
    DeviceView, EnrollmentCert, RevocationCert, SeenSet, SignedMessage,
    Verifier,
)
from .messages import (
    KIND_DELETE, KIND_POST, KIND_PROFILE, MAX_BLOB_BYTES, blob_hash,
    validate_payload,
)

_SCHEMA = """
CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT);
CREATE TABLE IF NOT EXISTS identities(
  identity_pub TEXT PRIMARY KEY, is_self INTEGER NOT NULL,
  added_at REAL NOT NULL);
CREATE TABLE IF NOT EXISTS device_views(
  identity_pub TEXT NOT NULL, device_pub TEXT NOT NULL,
  cert_json TEXT, revocation_json TEXT, seen_json TEXT NOT NULL,
  PRIMARY KEY(identity_pub, device_pub));
CREATE TABLE IF NOT EXISTS messages(
  msg_id TEXT PRIMARY KEY, identity_pub TEXT NOT NULL,
  device_pub TEXT NOT NULL, seq INTEGER NOT NULL, kind TEXT NOT NULL,
  target_id TEXT, msg_json TEXT NOT NULL,
  created_at REAL NOT NULL, expires_at REAL);
CREATE TABLE IF NOT EXISTS tombstones(
  msg_id TEXT PRIMARY KEY, reason TEXT NOT NULL, at REAL NOT NULL);
CREATE TABLE IF NOT EXISTS blobs(hash TEXT PRIMARY KEY, data BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS peers(address TEXT PRIMARY KEY,
  identity_pub TEXT);
"""


@dataclass
class IngestResult:
    accepted: bool
    reason: str
    msg_id: Optional[str] = None
    retro_dropped: List[str] = field(default_factory=list)
    deleted_target: Optional[str] = None


class Store:
    def __init__(self, path):
        self._lock = threading.RLock()
        self._db = sqlite3.connect(str(path), check_same_thread=False)
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.executescript(_SCHEMA)
        self._db.commit()

    # -- meta ---------------------------------------------------------------

    def set_meta(self, k: str, v: str):
        with self._lock:
            self._db.execute(
                "INSERT OR REPLACE INTO meta VALUES(?,?)", (k, v))
            self._db.commit()

    def get_meta(self, k: str) -> Optional[str]:
        with self._lock:
            row = self._db.execute(
                "SELECT v FROM meta WHERE k=?", (k,)).fetchone()
            return row[0] if row else None

    # -- identities -----------------------------------------------------------

    def add_identity(self, identity_pub: str, is_self: bool = False):
        with self._lock:
            self._db.execute(
                "INSERT OR IGNORE INTO identities VALUES(?,?,?)",
                (identity_pub, 1 if is_self else 0, time.time()))
            self._db.commit()

    def known_identities(self) -> List[str]:
        with self._lock:
            return [r[0] for r in
                    self._db.execute("SELECT identity_pub FROM identities")]

    def is_known(self, identity_pub: str) -> bool:
        with self._lock:
            return self._db.execute(
                "SELECT 1 FROM identities WHERE identity_pub=?",
                (identity_pub,)).fetchone() is not None

    def self_identity(self) -> Optional[str]:
        with self._lock:
            row = self._db.execute(
                "SELECT identity_pub FROM identities WHERE is_self=1"
            ).fetchone()
            return row[0] if row else None

    # -- device views ----------------------------------------------------------

    def load_views(self, identity_pub: str) -> Dict[str, DeviceView]:
        with self._lock:
            out: Dict[str, DeviceView] = {}
            for dpub, cj, rj, sj in self._db.execute(
                    "SELECT device_pub, cert_json, revocation_json, seen_json"
                    " FROM device_views WHERE identity_pub=?",
                    (identity_pub,)):
                out[dpub] = DeviceView(
                    cert=(EnrollmentCert.from_dict(json.loads(cj))
                          if cj else None),
                    revocation=(RevocationCert.from_dict(json.loads(rj))
                                if rj else None),
                    seen=SeenSet.from_json(json.loads(sj)))
            return out

    def save_views(self, identity_pub: str, views: Dict[str, DeviceView]):
        with self._lock:
            for dpub, v in views.items():
                self._db.execute(
                    "INSERT OR REPLACE INTO device_views VALUES(?,?,?,?,?)",
                    (identity_pub, dpub,
                     json.dumps(v.cert.to_dict()) if v.cert else None,
                     json.dumps(v.revocation.to_dict()) if v.revocation
                     else None,
                     json.dumps(v.seen.to_json())))
            self._db.commit()

    def all_summaries(self) -> dict:
        with self._lock:
            out: dict = {}
            for ipub, dpub, sj in self._db.execute(
                    "SELECT identity_pub, device_pub, seen_json"
                    " FROM device_views"):
                out.setdefault(ipub, {})[dpub] = json.loads(sj)
            return out

    def list_revocations(self) -> List[RevocationCert]:
        with self._lock:
            return [RevocationCert.from_dict(json.loads(rj))
                    for (rj,) in self._db.execute(
                        "SELECT revocation_json FROM device_views"
                        " WHERE revocation_json IS NOT NULL")]

    # -- peers ------------------------------------------------------------------

    def add_peer(self, address: str, identity_pub: Optional[str] = None):
        with self._lock:
            self._db.execute("INSERT OR REPLACE INTO peers VALUES(?,?)",
                             (address, identity_pub))
            self._db.commit()

    def list_peers(self) -> List[dict]:
        with self._lock:
            return [{"address": a, "identity_pub": i} for a, i in
                    self._db.execute(
                        "SELECT address, identity_pub FROM peers")]

    # -- blobs --------------------------------------------------------------------

    def put_blob(self, data: bytes) -> str:
        if len(data) > MAX_BLOB_BYTES:
            raise ValueError("blob exceeds 5 MB cap")
        h = blob_hash(data)
        with self._lock:
            self._db.execute("INSERT OR IGNORE INTO blobs VALUES(?,?)",
                             (h, data))
            self._db.commit()
        return h

    def get_blob(self, h: str) -> Optional[bytes]:
        with self._lock:
            row = self._db.execute(
                "SELECT data FROM blobs WHERE hash=?", (h,)).fetchone()
            return bytes(row[0]) if row else None

    def has_blob(self, h: str) -> bool:
        return self.get_blob(h) is not None
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_store_persistence.py -q`
Expected: 4 passed.

- [ ] **Step 5: Commit**

```bash
git add hearth/store.py tests/test_store_persistence.py
git commit -m "feat: store part 1 - schema, identities, views, peers, blobs"
```

---

### Task 7: Store part 2 — ingest pipeline, deletion, retro-drop, expiry, GC, feed

**Files:**
- Modify: `hearth/store.py` (append methods to `Store`)
- Test: `tests/test_store_ingest.py`

**Interfaces:**
- Produces (methods on `Store`):
  - `ingest_message(msg: SignedMessage, now=None) -> IngestResult` — full pipeline; idempotent (`reason="duplicate"`); rejects unknown identities (`"unknown identity"`) and tombstoned ids (`"tombstoned"`); applies delete tags (`deleted_target` set when applied; `"delete not authorized"` on identity mismatch); posts arriving after their delete tag are tombstoned on arrival (`reason="deleted on arrival"`, accepted True).
  - `ingest_revocation(rev: RevocationCert) -> IngestResult` — records revocation, retro-drops stored messages with `seq > last_valid_seq` (tombstone reason `"retro-drop"`), returns their ids in `retro_dropped`.
  - `sweep_expired(now=None) -> list[str]` — tombstones expired posts (reason `"expired"`), GCs blobs.
  - `is_tombstoned(msg_id) -> bool`
  - `feed(now=None) -> list[dict]` — posts newest-first, keys: `msg_id, identity_pub, device_pub, device_name, author_name, text, blobs, created_at, expires_at`.
  - `profiles() -> dict[str, str]` — identity_pub to latest display name.
  - `messages_not_in(summaries: dict, entitled: set[str]) -> list[SignedMessage]` — stored messages whose author is in `entitled` and whose seq the peer's summary lacks.
  - `referenced_blobs() -> set[str]`, `missing_blobs() -> set[str]`, `gc_blobs() -> int`.

- [ ] **Step 1: Write the failing tests**

`tests/test_store_ingest.py`:
```python
import time

from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import blob_hash, make_delete, make_post, make_profile
from hearth.store import Store


def wong(tmp_path, db="w.db"):
    phone = DeviceKeys.create("wong-phone")
    node = DeviceKeys.create("wong-homenode")
    IdentityCeremony().enroll_first_device(phone)
    node.install(phone.enroll_other(node.device_pub, node.name),
                 phone.to_json()["identity_priv"])
    s = Store(tmp_path / db)
    s.add_identity(phone.identity_pub, is_self=True)
    return s, phone, node


def test_ingest_accept_duplicate_unknown(tmp_path):
    s, phone, _ = wong(tmp_path)
    m = make_post(phone, "hej")
    assert s.ingest_message(m).accepted is True
    assert s.ingest_message(m).reason == "duplicate"
    stranger = DeviceKeys.create("stranger")
    IdentityCeremony().enroll_first_device(stranger)
    r = s.ingest_message(make_post(stranger, "let me in"))
    assert (r.accepted, r.reason) == (False, "unknown identity")


def test_feed_with_profile_names(tmp_path):
    s, phone, _ = wong(tmp_path)
    s.ingest_message(make_profile(phone, "Wong"))
    s.ingest_message(make_post(phone, "first", now=100.0))
    s.ingest_message(make_post(phone, "second", now=200.0))
    feed = s.feed()
    assert [p["text"] for p in feed] == ["second", "first"]
    assert feed[0]["author_name"] == "Wong"
    assert s.profiles()[phone.identity_pub] == "Wong"


def test_delete_tag_removes_post_and_blocks_resurrection(tmp_path):
    s, phone, _ = wong(tmp_path)
    data = b"photo"
    ref = s.put_blob(data)
    post = make_post(phone, "delete me", [ref])
    s.ingest_message(post)
    r = s.ingest_message(make_delete(phone, post.msg_id))
    assert r.accepted and r.deleted_target == post.msg_id
    assert s.feed() == []
    assert s.is_tombstoned(post.msg_id)
    assert not s.has_blob(ref)                       # GC'd
    r2 = s.ingest_message(post)                      # gossip echo
    assert (r2.accepted, r2.reason) == (False, "tombstoned")


def test_delete_from_wrong_identity_rejected(tmp_path):
    s, phone, _ = wong(tmp_path)
    post = make_post(phone, "mine")
    s.ingest_message(post)
    mal = DeviceKeys.create("mallory")
    IdentityCeremony().enroll_first_device(mal)
    s.add_identity(mal.identity_pub)                 # mallory IS a friend
    r = s.ingest_message(make_delete(mal, post.msg_id))
    assert (r.accepted, r.reason) == (False, "delete not authorized")
    assert len(s.feed()) == 1


def test_delete_tag_arriving_before_post(tmp_path):
    s, phone, _ = wong(tmp_path)
    post = make_post(phone, "never seen")
    tag = make_delete(phone, post.msg_id)
    assert s.ingest_message(tag).accepted            # tag first
    r = s.ingest_message(post)                       # post second
    assert r.accepted and r.reason == "deleted on arrival"
    assert s.feed() == [] and s.is_tombstoned(post.msg_id)


def test_revocation_retro_drop(tmp_path):
    s, phone, node = wong(tmp_path)
    keep = make_post(phone, "before theft")
    s.ingest_message(keep)
    loot1 = make_post(phone, "send crypto")
    loot2 = make_post(phone, "more loot")
    s.ingest_message(loot1)
    s.ingest_message(loot2)
    rev = node.make_revocation(phone.device_pub, last_valid_seq=keep.seq)
    r = s.ingest_revocation(rev)
    assert r.accepted
    assert set(r.retro_dropped) == {loot1.msg_id, loot2.msg_id}
    assert [p["text"] for p in s.feed()] == ["before theft"]
    assert s.ingest_message(loot1).reason == "tombstoned"


def test_sweep_expired(tmp_path):
    s, phone, _ = wong(tmp_path)
    now = time.time()
    s.ingest_message(make_post(phone, "gone", expires_at=now - 10))
    s.ingest_message(make_post(phone, "stays", expires_at=now + 3600))
    swept = s.sweep_expired(now)
    assert len(swept) == 1
    assert [p["text"] for p in s.feed(now)] == ["stays"]


def test_messages_not_in_respects_entitlement_and_summaries(tmp_path):
    s, phone, _ = wong(tmp_path)
    m1 = make_post(phone, "one")
    m2 = make_post(phone, "two")
    s.ingest_message(m1)
    s.ingest_message(m2)
    ident = phone.identity_pub
    # peer has seen seq 1 only
    summaries = {ident: {phone.device_pub: {"contiguous": 1, "sparse": []}}}
    missing = s.messages_not_in(summaries, entitled={ident})
    assert [m.msg_id for m in missing] == [m2.msg_id]
    # not entitled -> nothing, regardless of summaries
    assert s.messages_not_in({}, entitled=set()) == []
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_store_ingest.py -q`
Expected: FAIL — `AttributeError: 'Store' object has no attribute 'ingest_message'`.

- [ ] **Step 3: Append ingest/read methods to `Store` in `hearth/store.py`**

```python
    # -- tombstones ---------------------------------------------------------------

    def is_tombstoned(self, msg_id: str) -> bool:
        with self._lock:
            return self._db.execute(
                "SELECT 1 FROM tombstones WHERE msg_id=?",
                (msg_id,)).fetchone() is not None

    def _tombstone(self, msg_id: str, reason: str):
        self._db.execute("INSERT OR IGNORE INTO tombstones VALUES(?,?,?)",
                         (msg_id, reason, time.time()))
        self._db.execute("DELETE FROM messages WHERE msg_id=?", (msg_id,))

    # -- ingest ---------------------------------------------------------------------

    def ingest_message(self, msg: SignedMessage,
                       now: Optional[float] = None) -> IngestResult:
        now = now if now is not None else time.time()
        with self._lock:
            identity = msg.cert.identity_pub
            if not self.is_known(identity):
                return IngestResult(False, "unknown identity")
            mid = msg.msg_id
            if self.is_tombstoned(mid):
                return IngestResult(False, "tombstoned", mid)
            if self._db.execute("SELECT 1 FROM messages WHERE msg_id=?",
                                (mid,)).fetchone():
                return IngestResult(False, "duplicate", mid)
            ok, why = validate_payload(msg.payload)
            if not ok:
                return IngestResult(False, why, mid)
            views = self.load_views(identity)
            ok, why = Verifier(identity, views).verify_message(msg)
            self.save_views(identity, views)
            if not ok:
                return IngestResult(False, why, mid)

            kind = msg.payload["kind"]
            target = (msg.payload.get("target")
                      if kind == KIND_DELETE else None)
            deleted_target = None
            if kind == KIND_DELETE:
                row = self._db.execute(
                    "SELECT identity_pub FROM messages WHERE msg_id=?",
                    (target,)).fetchone()
                if row is not None:
                    if row[0] != identity:
                        return IngestResult(False, "delete not authorized",
                                            mid)
                    self._tombstone(target, "deleted")
                    deleted_target = target
            elif kind == KIND_POST:
                # A delete tag for this post may have gossiped in first.
                if self._db.execute(
                        "SELECT 1 FROM messages WHERE kind=? AND target_id=?"
                        " AND identity_pub=?",
                        (KIND_DELETE, mid, identity)).fetchone():
                    self._tombstone(mid, "deleted")
                    self._db.commit()
                    return IngestResult(True, "deleted on arrival", mid)

            self._db.execute(
                "INSERT INTO messages VALUES(?,?,?,?,?,?,?,?,?)",
                (mid, identity, msg.cert.device_pub, msg.seq, kind, target,
                 json.dumps(msg.to_dict()),
                 msg.payload.get("created_at", now),
                 msg.payload.get("expires_at")))
            self._db.commit()
            if deleted_target:
                self.gc_blobs()
            return IngestResult(True, "ok", mid,
                                deleted_target=deleted_target)

    def ingest_revocation(self, rev: RevocationCert) -> IngestResult:
        with self._lock:
            identity = rev.identity_pub
            if not self.is_known(identity):
                return IngestResult(False, "unknown identity")
            views = self.load_views(identity)
            ok, why = Verifier(identity, views).process_revocation(rev)
            if not ok:
                return IngestResult(False, why)
            self.save_views(identity, views)
            dropped = []
            for (mid,) in self._db.execute(
                    "SELECT msg_id FROM messages WHERE device_pub=?"
                    " AND seq>?", (rev.device_pub, rev.last_valid_seq)):
                dropped.append(mid)
            for mid in dropped:
                self._tombstone(mid, "retro-drop")
            self._db.commit()
            if dropped:
                self.gc_blobs()
            return IngestResult(True, "ok", retro_dropped=dropped)

    def sweep_expired(self, now: Optional[float] = None) -> List[str]:
        now = now if now is not None else time.time()
        with self._lock:
            swept = [mid for (mid,) in self._db.execute(
                "SELECT msg_id FROM messages WHERE expires_at IS NOT NULL"
                " AND expires_at<=?", (now,))]
            for mid in swept:
                self._tombstone(mid, "expired")
            self._db.commit()
            if swept:
                self.gc_blobs()
            return swept

    # -- reads -----------------------------------------------------------------------

    def profiles(self) -> Dict[str, str]:
        with self._lock:
            best: Dict[str, tuple] = {}
            for ipub, mj in self._db.execute(
                    "SELECT identity_pub, msg_json FROM messages"
                    " WHERE kind=?", (KIND_PROFILE,)):
                p = json.loads(mj)["payload"]
                if ipub not in best or p["created_at"] > best[ipub][0]:
                    best[ipub] = (p["created_at"], p["name"])
            return {k: v[1] for k, v in best.items()}

    def feed(self, now: Optional[float] = None) -> List[dict]:
        now = now if now is not None else time.time()
        with self._lock:
            names = self.profiles()
            out = []
            for mid, ipub, dpub, mj in self._db.execute(
                    "SELECT msg_id, identity_pub, device_pub, msg_json"
                    " FROM messages WHERE kind=? ORDER BY created_at DESC",
                    (KIND_POST,)):
                m = json.loads(mj)
                p = m["payload"]
                if (p.get("expires_at") is not None
                        and p["expires_at"] <= now):
                    continue
                out.append({
                    "msg_id": mid, "identity_pub": ipub, "device_pub": dpub,
                    "device_name": m["cert"]["device_name"],
                    "author_name": names.get(ipub, ipub[:8]),
                    "text": p["text"], "blobs": p["blobs"],
                    "created_at": p["created_at"],
                    "expires_at": p.get("expires_at"),
                })
            return out

    def messages_not_in(self, summaries: dict,
                        entitled: Set[str]) -> List[SignedMessage]:
        with self._lock:
            out = []
            for ipub, dpub, seq, mj in self._db.execute(
                    "SELECT identity_pub, device_pub, seq, msg_json"
                    " FROM messages ORDER BY seq ASC"):
                if ipub not in entitled:
                    continue
                dev = summaries.get(ipub, {}).get(dpub)
                if dev is not None and SeenSet.summary_has(dev, seq):
                    continue
                out.append(SignedMessage.from_dict(json.loads(mj)))
            return out

    # -- blob GC -----------------------------------------------------------------------

    def referenced_blobs(self) -> Set[str]:
        with self._lock:
            refs: Set[str] = set()
            for (mj,) in self._db.execute(
                    "SELECT msg_json FROM messages WHERE kind=?",
                    (KIND_POST,)):
                refs.update(json.loads(mj)["payload"]["blobs"])
            return refs

    def missing_blobs(self) -> Set[str]:
        with self._lock:
            have = {h for (h,) in
                    self._db.execute("SELECT hash FROM blobs")}
            return self.referenced_blobs() - have

    def gc_blobs(self) -> int:
        with self._lock:
            refs = self.referenced_blobs()
            gone = [h for (h,) in self._db.execute("SELECT hash FROM blobs")
                    if h not in refs]
            for h in gone:
                self._db.execute("DELETE FROM blobs WHERE hash=?", (h,))
            self._db.commit()
            return len(gone)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_store_ingest.py tests/test_store_persistence.py -q`
Expected: 12 passed.

- [ ] **Step 5: Commit**

```bash
git add hearth/store.py tests/test_store_ingest.py
git commit -m "feat: store part 2 - ingest, deletion, retro-drop, expiry, GC"
```

---

### Task 8: HearthNode — daemon core (single node, no network yet)

**Files:**
- Create: `hearth/node.py`
- Test: `tests/test_node.py`

**Interfaces:**
- Consumes: everything above.
- Produces (`hearth.node.HearthNode`):
  - `HearthNode(data_dir)` — loads existing `keys.json` + `hearth.db`; raises `FileNotFoundError` if not initialized.
  - `HearthNode.create(data_dir, person_name, device_name, seed=None) -> HearthNode` — identity ceremony, first device, writes `keys.json` + `paper_seed.txt`, registers self identity + view, sets profile.
  - Properties/attrs: `.device` (DeviceKeys), `.store` (Store), `.identity_pub`, `.subscribers` (set of asyncio queues), `.data_dir`.
  - `.compose_post(text, photos: list[bytes] = (), expires_seconds=None) -> str` (msg_id), `.set_profile(name) -> str`, `.delete_post(msg_id) -> str`
  - `.feed() -> list[dict]` (store feed + `"mine"` bool)
  - `.devices() -> list[dict]` (`device_pub, name, revoked, this_device`)
  - `.revoke_device(device_pub) -> IngestResult` (bound = revoked device's max seen seq)
  - `.notify()` — pushes `"changed"` to all subscriber queues.
  - Own-message publishing persists `keys.json` after every sign (seq continuity across restarts).

- [ ] **Step 1: Write the failing tests**

`tests/test_node.py`:
```python
import pytest

from hearth.node import HearthNode


def test_create_and_reload(tmp_path):
    d = tmp_path / "wong-phone"
    n = HearthNode.create(d, "Wong", "wong-phone")
    assert n.identity_pub and (d / "paper_seed.txt").exists()
    n2 = HearthNode(d)                      # reload from disk
    assert n2.identity_pub == n.identity_pub


def test_uninitialized_dir_raises(tmp_path):
    with pytest.raises(FileNotFoundError):
        HearthNode(tmp_path / "empty")


def test_compose_feed_delete_with_photo(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "phone")
    photo = b"\x89PNG fake bytes"
    mid = n.compose_post("hello hearth", [photo])
    feed = n.feed()
    assert len(feed) == 1 and feed[0]["mine"] is True
    assert feed[0]["author_name"] == "Wong"
    ref = feed[0]["blobs"][0]
    assert n.store.get_blob(ref) == photo
    n.delete_post(mid)
    assert n.feed() == []
    assert n.store.get_blob(ref) is None    # blob GC'd


def test_seq_survives_restart(tmp_path):
    d = tmp_path / "n"
    n = HearthNode.create(d, "Wong", "phone")
    n.compose_post("one")
    seq_before = n.device.seq
    n2 = HearthNode(d)                      # simulated process restart
    n2.compose_post("two")
    assert n2.device.seq == seq_before + 1
    assert len(n2.feed()) == 2              # no seq reuse rejection


def test_expiring_post(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "phone")
    n.compose_post("brief", expires_seconds=0.0)
    n.store.sweep_expired()
    assert n.feed() == []


def test_revoke_device(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "phone")
    from hearth.identity import DeviceKeys
    tablet = DeviceKeys.create("tablet")
    cert = n.device.enroll_other(tablet.device_pub, tablet.name)
    tablet.install(cert, n.device.to_json()["identity_priv"])
    from hearth.messages import make_post
    n.store.ingest_message(make_post(tablet, "from tablet"))
    r = n.revoke_device(tablet.device_pub)
    assert r.accepted and len(r.retro_dropped) == 1
    assert n.feed() == []
    devices = {d["device_pub"]: d for d in n.devices()}
    assert devices[tablet.device_pub]["revoked"] is True
    assert devices[n.device.device_pub]["this_device"] is True
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_node.py -q`
Expected: FAIL — `No module named 'hearth.node'`.

- [ ] **Step 3: Implement `hearth/node.py`**

```python
"""HearthNode - one device's daemon state: keys + store + change events."""
from __future__ import annotations

import json
import time
from pathlib import Path
from typing import List, Optional, Sequence

from .identity import DeviceKeys, DeviceView, IdentityCeremony
from .messages import make_delete, make_post, make_profile
from .store import IngestResult, Store


class HearthNode:
    def __init__(self, data_dir):
        self.data_dir = Path(data_dir)
        keys_path = self.data_dir / "keys.json"
        if not keys_path.exists():
            raise FileNotFoundError(
                f"no keys.json in {self.data_dir}; initialize first")
        self.device = DeviceKeys.from_json(json.loads(keys_path.read_text()))
        self.store = Store(self.data_dir / "hearth.db")
        self.subscribers: set = set()

    @classmethod
    def create(cls, data_dir, person_name: str, device_name: str,
               seed: Optional[bytes] = None) -> "HearthNode":
        data_dir = Path(data_dir)
        data_dir.mkdir(parents=True, exist_ok=True)
        ceremony = IdentityCeremony(seed)
        device = DeviceKeys.create(device_name)
        ceremony.enroll_first_device(device)
        (data_dir / "keys.json").write_text(json.dumps(device.to_json()))
        (data_dir / "paper_seed.txt").write_text(ceremony.paper_seed())
        node = cls(data_dir)
        node.store.add_identity(ceremony.identity_pub, is_self=True)
        node.store.save_views(ceremony.identity_pub, {
            device.device_pub: DeviceView(cert=device.cert)})
        node.set_profile(person_name)
        return node

    @property
    def identity_pub(self) -> str:
        return self.device.identity_pub

    def _save_keys(self):
        (self.data_dir / "keys.json").write_text(
            json.dumps(self.device.to_json()))

    def notify(self):
        for q in list(self.subscribers):
            try:
                q.put_nowait("changed")
            except Exception:
                pass

    def _publish(self, msg) -> str:
        result = self.store.ingest_message(msg)
        self._save_keys()
        if not result.accepted:
            raise RuntimeError(f"own message rejected: {result.reason}")
        self.notify()
        return result.msg_id

    def compose_post(self, text: str, photos: Sequence[bytes] = (),
                     expires_seconds: Optional[float] = None) -> str:
        refs = [self.store.put_blob(p) for p in photos]
        expires_at = (time.time() + expires_seconds
                      if expires_seconds is not None else None)
        return self._publish(make_post(self.device, text, refs, expires_at))

    def set_profile(self, name: str) -> str:
        return self._publish(make_profile(self.device, name))

    def delete_post(self, target_msg_id: str) -> str:
        return self._publish(make_delete(self.device, target_msg_id))

    def feed(self) -> List[dict]:
        rows = self.store.feed()
        for r in rows:
            r["mine"] = (r["identity_pub"] == self.identity_pub)
        return rows

    def devices(self) -> List[dict]:
        views = self.store.load_views(self.identity_pub)
        return [{
            "device_pub": dpub,
            "name": v.cert.device_name if v.cert else "(unknown)",
            "revoked": v.revocation is not None,
            "this_device": dpub == self.device.device_pub,
        } for dpub, v in views.items()]

    def revoke_device(self, device_pub: str) -> IngestResult:
        views = self.store.load_views(self.identity_pub)
        view = views.get(device_pub)
        bound = view.seen.max_seen() if view else 0
        rev = self.device.make_revocation(device_pub, bound)
        result = self.store.ingest_revocation(rev)
        self.notify()
        return result
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_node.py -q`
Expected: 6 passed.

Note: `test_compose_feed_delete_with_photo` exercises own-message ingest through the full store pipeline — if it fails with "own message rejected", the create() flow did not register the self view correctly; check the `save_views` call in `create`.

- [ ] **Step 5: Commit**

```bash
git add hearth/node.py tests/test_node.py
git commit -m "feat: HearthNode daemon core - compose, feed, delete, revoke"
```

---

### Task 9: HTTP API + web UI (single node, browser-usable)

**Files:**
- Create: `hearth/api.py`, `hearth/web/index.html`, `hearth/web/style.css`, `hearth/web/app.js`
- Test: `tests/test_api.py`

**Interfaces:**
- Consumes: `HearthNode` (Task 8).
- Produces: `hearth.api.build_app(node: HearthNode) -> FastAPI` with routes:
  - `GET /` → index.html; `GET /static/*` → web assets
  - `GET /api/state` → `{identity_pub, device_pub, device_name, profile_name, devices: [...], friends: [{identity_pub, name}], peers: [...]}`
  - `GET /api/feed` → node.feed()
  - `POST /api/post` — multipart form: `text` (str), `expires_seconds` (str, empty or seconds), `photos` (0..n files) → `{"msg_id": ...}`; 413 on oversized photo
  - `POST /api/delete` — JSON `{"msg_id": ...}`
  - `POST /api/profile` — JSON `{"name": ...}`
  - `POST /api/device/revoke` — JSON `{"device_pub": ...}`
  - `GET /api/blob/{h}` → image bytes (content-type sniffed: PNG/JPEG/GIF/WebP, else octet-stream); 404 unknown
  - `WS /ws` → server pushes the text `"changed"` whenever the node's store changes
- Friend-ceremony endpoints and their UI arrive in Task 13. The Friends panel in this task renders the friends list only (plus an empty `#ceremony` container for Task 13 to fill).

- [ ] **Step 1: Write the failing tests**

`tests/test_api.py`:
```python
from fastapi.testclient import TestClient

from hearth.api import build_app
from hearth.node import HearthNode

PNG = b"\x89PNG\r\n\x1a\nfakepixels"


def client(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    return TestClient(build_app(node)), node


def test_state_and_index(tmp_path):
    c, node = client(tmp_path)
    assert c.get("/").status_code == 200
    s = c.get("/api/state").json()
    assert s["identity_pub"] == node.identity_pub
    assert s["profile_name"] == "Wong"
    assert s["device_name"] == "wong-phone"
    assert s["friends"] == []


def test_post_feed_blob_delete_cycle(tmp_path):
    c, _ = client(tmp_path)
    r = c.post("/api/post",
               data={"text": "hello", "expires_seconds": ""},
               files=[("photos", ("p.png", PNG, "image/png"))])
    assert r.status_code == 200
    mid = r.json()["msg_id"]
    feed = c.get("/api/feed").json()
    assert feed[0]["text"] == "hello" and feed[0]["mine"] is True
    blob = c.get(f"/api/blob/{feed[0]['blobs'][0]}")
    assert blob.status_code == 200
    assert blob.headers["content-type"] == "image/png"
    assert blob.content == PNG
    assert c.post("/api/delete", json={"msg_id": mid}).status_code == 200
    assert c.get("/api/feed").json() == []


def test_expiring_post_via_api(tmp_path):
    c, node = client(tmp_path)
    c.post("/api/post", data={"text": "brief", "expires_seconds": "3600"})
    feed = c.get("/api/feed").json()
    assert feed[0]["expires_at"] is not None


def test_profile_update(tmp_path):
    c, _ = client(tmp_path)
    c.post("/api/profile", json={"name": "Wong II"})
    assert c.get("/api/state").json()["profile_name"] == "Wong II"


def test_unknown_blob_404(tmp_path):
    c, _ = client(tmp_path)
    assert c.get("/api/blob/" + "ab" * 32).status_code == 404


def test_ws_notified_on_post(tmp_path):
    c, _ = client(tmp_path)
    with c.websocket_connect("/ws") as ws:
        c.post("/api/post", data={"text": "ping", "expires_seconds": ""})
        assert ws.receive_text() == "changed"
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_api.py -q`
Expected: FAIL — `No module named 'hearth.api'`.

- [ ] **Step 3: Implement `hearth/api.py`**

```python
"""Localhost HTTP + WebSocket API for one Hearth node."""
from __future__ import annotations

import asyncio
from pathlib import Path
from typing import List

from fastapi import (Body, FastAPI, File, HTTPException, Form, UploadFile,
                     WebSocket, WebSocketDisconnect)
from fastapi.responses import FileResponse, Response
from fastapi.staticfiles import StaticFiles

from .messages import MAX_BLOB_BYTES
from .node import HearthNode

WEB_DIR = Path(__file__).parent / "web"

_MAGIC = [(b"\x89PNG", "image/png"), (b"\xff\xd8", "image/jpeg"),
          (b"GIF8", "image/gif"), (b"RIFF", "image/webp")]


def _sniff(data: bytes) -> str:
    for magic, mime in _MAGIC:
        if data.startswith(magic):
            return mime
    return "application/octet-stream"


def build_app(node: HearthNode) -> FastAPI:
    app = FastAPI(title="Hearth node")
    app.mount("/static", StaticFiles(directory=WEB_DIR), name="static")

    @app.get("/")
    async def index():
        return FileResponse(WEB_DIR / "index.html")

    @app.get("/api/state")
    async def state():
        names = node.store.profiles()
        return {
            "identity_pub": node.identity_pub,
            "device_pub": node.device.device_pub,
            "device_name": node.device.name,
            "profile_name": names.get(node.identity_pub, ""),
            "devices": node.devices(),
            "friends": [{"identity_pub": i, "name": names.get(i, i[:8])}
                        for i in node.store.known_identities()
                        if i != node.identity_pub],
            "peers": node.store.list_peers(),
        }

    @app.get("/api/feed")
    async def feed():
        return node.feed()

    @app.post("/api/post")
    async def post(text: str = Form(""),
                   expires_seconds: str = Form(""),
                   photos: List[UploadFile] = File(default=[])):
        blobs = []
        for up in photos:
            data = await up.read()
            if len(data) > MAX_BLOB_BYTES:
                raise HTTPException(413, "photo exceeds 5 MB cap")
            blobs.append(data)
        expiry = float(expires_seconds) if expires_seconds.strip() else None
        mid = node.compose_post(text, blobs, expiry)
        return {"msg_id": mid}

    @app.post("/api/delete")
    async def delete(body: dict = Body(...)):
        node.delete_post(body["msg_id"])
        return {"ok": True}

    @app.post("/api/profile")
    async def profile(body: dict = Body(...)):
        node.set_profile(body["name"])
        return {"ok": True}

    @app.post("/api/device/revoke")
    async def revoke(body: dict = Body(...)):
        result = node.revoke_device(body["device_pub"])
        return {"ok": result.accepted, "retro_dropped": result.retro_dropped}

    @app.get("/api/blob/{h}")
    async def blob(h: str):
        data = node.store.get_blob(h)
        if data is None:
            raise HTTPException(404, "unknown blob")
        return Response(content=data, media_type=_sniff(data))

    @app.websocket("/ws")
    async def ws(sock: WebSocket):
        await sock.accept()
        q: asyncio.Queue = asyncio.Queue()
        node.subscribers.add(q)
        try:
            while True:
                await sock.send_text(await q.get())
        except WebSocketDisconnect:
            pass
        finally:
            node.subscribers.discard(q)

    return app
```

- [ ] **Step 4: Create the web UI**

`hearth/web/index.html`:
```html
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Hearth</title>
<link rel="stylesheet" href="/static/style.css">
</head>
<body>
<header>
  <div class="brand">Hearth</div>
  <div class="who"><span id="profile-name"></span>
    <span id="device-name" class="dim"></span></div>
</header>
<main>
  <section class="col feed-col">
    <form id="compose">
      <textarea id="text" rows="3"
        placeholder="Say something to your people..."></textarea>
      <div class="compose-row">
        <input type="file" id="photos" accept="image/*" multiple>
        <select id="expiry">
          <option value="">keeps</option>
          <option value="3600">1 hour</option>
          <option value="86400">1 day</option>
          <option value="604800">7 days</option>
        </select>
        <button type="submit">Post</button>
      </div>
    </form>
    <div id="feed"></div>
  </section>
  <aside class="col side-col">
    <div class="panel">
      <h2>Me</h2>
      <div class="row"><input id="name-input" placeholder="Display name">
        <button id="name-save">Save</button></div>
      <div class="dim tiny" id="identity-pub"></div>
    </div>
    <div class="panel">
      <h2>Friends</h2>
      <div id="friends"></div>
      <div id="ceremony"></div>
    </div>
    <div class="panel">
      <h2>Devices</h2>
      <div id="devices"></div>
    </div>
  </aside>
</main>
<script src="/static/app.js"></script>
</body>
</html>
```

`hearth/web/style.css`:
```css
:root {
  --bg: #16181d; --panel: #1e2128; --ink: #e8e6e1; --dim: #8a8f98;
  --accent: #e0a458; --line: #2a2e37; --danger: #c25e5e;
}
* { box-sizing: border-box; margin: 0; }
body {
  background: var(--bg); color: var(--ink);
  font: 15px/1.5 "Segoe UI", system-ui, sans-serif;
}
header {
  display: flex; justify-content: space-between; align-items: baseline;
  padding: 14px 22px; border-bottom: 1px solid var(--line);
}
.brand { color: var(--accent); font-weight: 600; letter-spacing: .08em; }
.dim { color: var(--dim); } .tiny { font-size: 11px; word-break: break-all; }
main {
  display: grid; grid-template-columns: minmax(0,1fr) 320px;
  gap: 18px; max-width: 980px; margin: 18px auto; padding: 0 18px;
}
.panel, #compose, .post {
  background: var(--panel); border: 1px solid var(--line);
  border-radius: 10px; padding: 14px; margin-bottom: 14px;
}
h2 { font-size: 12px; text-transform: uppercase; letter-spacing: .1em;
     color: var(--dim); margin-bottom: 10px; }
textarea, input, select, button {
  font: inherit; color: var(--ink); background: var(--bg);
  border: 1px solid var(--line); border-radius: 6px; padding: 7px 10px;
}
textarea { width: 100%; resize: vertical; }
.compose-row { display: flex; gap: 8px; margin-top: 8px; align-items: center; }
.compose-row input[type=file] { flex: 1; border: none; background: none; }
button { cursor: pointer; }
button:hover { border-color: var(--accent); }
.post .meta { display: flex; gap: 8px; align-items: baseline;
              margin-bottom: 6px; }
.post .author { color: var(--accent); font-weight: 600; }
.post img { max-width: 100%; border-radius: 8px; margin-top: 8px; }
.post .actions { margin-top: 8px; }
.post .actions button { color: var(--danger); font-size: 12px; }
.row { display: flex; gap: 8px; }
.row input { flex: 1; }
.friend, .device { padding: 6px 0; border-bottom: 1px solid var(--line);
  display: flex; justify-content: space-between; align-items: center; }
.revoked { text-decoration: line-through; color: var(--dim); }
#ceremony textarea { margin-top: 8px; font-size: 11px; }
#ceremony button { margin-top: 6px; margin-right: 6px; font-size: 12px; }
.hint { font-size: 12px; color: var(--dim); margin-top: 8px; }
```

`hearth/web/app.js`:
```javascript
let STATE = null;

async function j(url, opts) {
  const r = await fetch(url, opts);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function expiryLabel(expiresAt) {
  if (!expiresAt) return "";
  const s = expiresAt - Date.now() / 1000;
  if (s <= 0) return "expired";
  if (s < 3600) return "expires in " + Math.ceil(s / 60) + "m";
  if (s < 86400) return "expires in " + Math.ceil(s / 3600) + "h";
  return "expires in " + Math.ceil(s / 86400) + "d";
}

function renderFeed(feed) {
  const root = document.getElementById("feed");
  root.replaceChildren();
  for (const p of feed) {
    const post = el("div", "post");
    const meta = el("div", "meta");
    meta.append(el("span", "author", p.author_name),
                el("span", "dim", p.device_name),
                el("span", "dim",
                   new Date(p.created_at * 1000).toLocaleString()),
                el("span", "dim", expiryLabel(p.expires_at)));
    post.append(meta, el("div", "", p.text));
    for (const h of p.blobs) {
      const img = document.createElement("img");
      img.src = "/api/blob/" + h;
      post.append(img);
    }
    if (p.mine) {
      const actions = el("div", "actions");
      const btn = el("button", "", "delete everywhere");
      btn.onclick = async () => {
        await j("/api/delete", {method: "POST",
          headers: {"Content-Type": "application/json"},
          body: JSON.stringify({msg_id: p.msg_id})});
        refresh();
      };
      actions.append(btn);
      post.append(actions);
    }
    root.append(post);
  }
}

function renderState() {
  document.getElementById("profile-name").textContent = STATE.profile_name;
  document.getElementById("device-name").textContent =
    " on " + STATE.device_name;
  document.getElementById("identity-pub").textContent =
    "identity " + STATE.identity_pub;
  document.getElementById("name-input").value = STATE.profile_name;

  const friends = document.getElementById("friends");
  friends.replaceChildren();
  if (!STATE.friends.length) friends.append(el("div", "hint",
    "No friends yet. Exchange codes below - in person."));
  for (const f of STATE.friends) {
    const row = el("div", "friend");
    row.append(el("span", "", f.name),
               el("span", "dim tiny", f.identity_pub.slice(0, 8)));
    friends.append(row);
  }

  const devices = document.getElementById("devices");
  devices.replaceChildren();
  for (const d of STATE.devices) {
    const row = el("div", "device" + (d.revoked ? " revoked" : ""));
    row.append(el("span", "",
      d.name + (d.this_device ? " (this device)" : "")));
    if (!d.revoked && !d.this_device) {
      const btn = el("button", "", "revoke");
      btn.onclick = async () => {
        await j("/api/device/revoke", {method: "POST",
          headers: {"Content-Type": "application/json"},
          body: JSON.stringify({device_pub: d.device_pub})});
        refresh();
      };
      row.append(btn);
    }
    devices.append(row);
  }
}

async function refresh() {
  STATE = await j("/api/state");
  renderState();
  renderFeed(await j("/api/feed"));
}

document.getElementById("compose").onsubmit = async (ev) => {
  ev.preventDefault();
  const fd = new FormData();
  fd.append("text", document.getElementById("text").value);
  fd.append("expires_seconds", document.getElementById("expiry").value);
  for (const f of document.getElementById("photos").files)
    fd.append("photos", f);
  await fetch("/api/post", {method: "POST", body: fd});
  document.getElementById("text").value = "";
  document.getElementById("photos").value = "";
  refresh();
};

document.getElementById("name-save").onclick = async () => {
  await j("/api/profile", {method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify(
      {name: document.getElementById("name-input").value})});
  refresh();
};

function connectWs() {
  const ws = new WebSocket("ws://" + location.host + "/ws");
  ws.onmessage = () => refresh();
  ws.onclose = () => setTimeout(connectWs, 2000);
}

refresh();
connectWs();
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_api.py -q`
Expected: 6 passed.

- [ ] **Step 6: Manual smoke check (optional but recommended)**

Run (PowerShell, from repo root):
```
.\.venv\Scripts\python.exe -c "from hearth.node import HearthNode; from hearth.api import build_app; import uvicorn; n = HearthNode.create('run/smoke', 'Wong', 'phone'); uvicorn.run(build_app(n), host='127.0.0.1', port=7201)"
```
Open http://127.0.0.1:7201 - post text + a photo, delete it, rename profile. Ctrl+C to stop. Delete `run/smoke` afterward.

- [ ] **Step 7: Commit**

```bash
git add hearth/api.py hearth/web tests/test_api.py
git commit -m "feat: localhost API and dark web UI - single node usable"
```

---

### Task 10: Transport — length-prefixed JSON frames over TCP

**Files:**
- Create: `hearth/transport.py`
- Test: `tests/test_transport.py`

**Interfaces:**
- Produces (in `hearth.transport`):
  - `MAX_FRAME = 16 * 1024 * 1024`
  - `async write_frame(writer, obj: dict)` — 4-byte big-endian length prefix + compact JSON; raises `ValueError` if oversized
  - `async read_frame(reader) -> dict` — raises `ValueError` on oversized, `asyncio.IncompleteReadError` on closed stream
  - `TcpTransport` with `async connect(address: str) -> (reader, writer)` (address `"host:port"`) and `async serve(host, port, handler) -> asyncio.Server`
- This is the swap point for Tor later (D1): a SOCKS-dialing transport replaces `TcpTransport` without touching the protocol.

- [ ] **Step 1: Write the failing tests**

`tests/test_transport.py`:
```python
import asyncio

import pytest

from hearth.transport import MAX_FRAME, TcpTransport, read_frame, write_frame


def test_frame_roundtrip_over_real_socket():
    async def scenario():
        t = TcpTransport()

        async def echo(reader, writer):
            frame = await read_frame(reader)
            await write_frame(writer, {"echo": frame})
            writer.close()

        server = await t.serve("127.0.0.1", 0, echo)
        port = server.sockets[0].getsockname()[1]
        reader, writer = await t.connect(f"127.0.0.1:{port}")
        await write_frame(writer, {"t": "hello", "n": 42})
        reply = await read_frame(reader)
        writer.close()
        server.close()
        await server.wait_closed()
        return reply

    assert asyncio.run(scenario()) == {"echo": {"t": "hello", "n": 42}}


def test_oversized_frame_rejected():
    async def scenario():
        class W:                       # capture-only fake writer
            def __init__(self):
                self.data = b""
            def write(self, b):
                self.data += b
            async def drain(self):
                pass

        with pytest.raises(ValueError):
            await write_frame(W(), {"x": "a" * (MAX_FRAME + 1)})

    asyncio.run(scenario())
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_transport.py -q`
Expected: FAIL — `No module named 'hearth.transport'`.

- [ ] **Step 3: Implement `hearth/transport.py`**

```python
"""Gossip transport: length-prefixed JSON frames over TCP.

Deliberately tiny surface so a Tor SOCKS dialer can replace TcpTransport
without touching the sync protocol (spec: D1 standing requirement)."""
from __future__ import annotations

import asyncio
import json
import struct

MAX_FRAME = 16 * 1024 * 1024


async def write_frame(writer, obj: dict):
    data = json.dumps(obj, separators=(",", ":")).encode()
    if len(data) > MAX_FRAME:
        raise ValueError("frame too large")
    writer.write(struct.pack(">I", len(data)) + data)
    await writer.drain()


async def read_frame(reader) -> dict:
    header = await reader.readexactly(4)
    (n,) = struct.unpack(">I", header)
    if n > MAX_FRAME:
        raise ValueError("frame too large")
    return json.loads(await reader.readexactly(n))


class TcpTransport:
    async def connect(self, address: str):
        host, port = address.rsplit(":", 1)
        return await asyncio.open_connection(host, int(port))

    async def serve(self, host: str, port: int, handler):
        return await asyncio.start_server(handler, host, port)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_transport.py -q`
Expected: 2 passed.

- [ ] **Step 5: Commit**

```bash
git add hearth/transport.py tests/test_transport.py
git commit -m "feat: framed TCP transport with Tor-shaped dial interface"
```

---

### Task 11: Sync session — auth, revocations-first, have/want, messages, blobs

**Files:**
- Create: `hearth/sync.py`
- Test: `tests/test_sync_session.py`

**Interfaces:**
- Consumes: transport (Task 10), `HearthNode` (Task 8), store methods (Tasks 6-7), `_sig_ok` + `canonical` + certs from `hearth.identity`.
- Produces (`hearth.sync.SyncService`):
  - `SyncService(node, transport=None)` (defaults to `TcpTransport`)
  - `async start(host, port) -> int` — starts responder server, returns actual bound port (pass port 0 in tests)
  - `async stop()`
  - `async sync_with(address: str) -> bool` — one full initiator session; returns False on any error (peer offline, refused, protocol violation) — gossip is retry-based, errors are non-fatal
  - `async gossip_loop(interval: float)` — arrives in Task 12
  - Session frame order (initiator sends first in each phase; responder mirrors): `hello` → `auth` → `revocations` → `have` → `messages` → `blob_want` → `blobs`
  - AUTH: mutual device-key proof over a domain-separated body (`{"type": "gossip-auth", ...}` — NEVER a raw peer-supplied byte string, to prevent signature-oracle forgery)
  - Unknown identities and revoked devices are refused at AUTH (`{"t": "refused"}` then close) — the structural anti-stranger rule at the socket layer
  - Own-device trust: when the peer is a device of MY OWN identity, adopt its known-identities list (friend-list replication between own devices)
  - Peer-address adoption: learn `addr` of the peer + announced peer addresses for identities I already know
  - Calls `node.notify()` once at session end if anything changed

- [ ] **Step 1: Write the failing tests**

`tests/test_sync_session.py`:
```python
import asyncio

from hearth.node import HearthNode
from hearth.sync import SyncService


def befriend(a: HearthNode, b: HearthNode):
    """Direct store-level friendship (the ceremony is Task 13)."""
    a.store.add_identity(b.identity_pub)
    b.store.add_identity(a.identity_pub)


async def started(node):
    svc = SyncService(node)
    port = await svc.start("127.0.0.1", 0)
    addr = f"127.0.0.1:{port}"
    node.store.set_meta("gossip_addr", addr)
    return svc, addr


def test_posts_and_blobs_propagate_between_friends(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        befriend(wong, freja)
        sw, wa = await started(wong)
        sf, fa = await started(freja)
        photo = b"\x89PNG-fake-sunset"
        wong.compose_post("hej Freja", [photo])
        assert await sw.sync_with(fa) is True
        feed = freja.feed()
        assert [p["text"] for p in feed] == ["hej Freja"]
        assert feed[0]["author_name"] == "Wong"     # profile synced too
        assert freja.store.get_blob(feed[0]["blobs"][0]) == photo
        await sw.stop()
        await sf.stop()

    asyncio.run(scenario())


def test_stranger_is_refused(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        mallory = HearthNode.create(tmp_path / "m", "Mallory", "mal-phone")
        # NOT friends: no add_identity in either direction.
        sw, wa = await started(wong)
        sm, _ = await started(mallory)
        wong.compose_post("private thoughts")
        assert await sm.sync_with(wa) is False       # refused at AUTH
        assert mallory.feed() == []
        assert mallory.store.known_identities() == [mallory.identity_pub]
        await sw.stop()
        await sm.stop()

    asyncio.run(scenario())


def test_deletion_propagates(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        befriend(wong, freja)
        sw, _ = await started(wong)
        sf, fa = await started(freja)
        mid = wong.compose_post("regret this")
        await sw.sync_with(fa)
        assert len(freja.feed()) == 1
        wong.delete_post(mid)
        await sw.sync_with(fa)
        assert freja.feed() == []
        assert freja.store.is_tombstoned(mid)
        await sw.stop()
        await sf.stop()

    asyncio.run(scenario())


def test_revocation_first_and_retro_drop_across_network(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        befriend(wong, freja)
        sw, _ = await started(wong)
        sf, fa = await started(freja)
        wong.compose_post("legit post")
        await sw.sync_with(fa)                        # freja has seq so far

        # Enroll + compromise a tablet; its loot reaches freja BEFORE any
        # revocation (the gossip-lag window).
        from hearth.identity import DeviceKeys, DeviceView
        from hearth.messages import make_post
        tablet = DeviceKeys.create("wong-tablet")
        cert = wong.device.enroll_other(tablet.device_pub, tablet.name)
        tablet.install(cert, wong.device.to_json()["identity_priv"])
        loot = make_post(tablet, "send crypto")
        assert freja.store.ingest_message(loot).accepted  # exposed window

        # Wong revokes the tablet; next sync must retro-drop at freja.
        wong.revoke_device(tablet.device_pub)
        await sw.sync_with(fa)
        assert [p["text"] for p in freja.feed()] == ["legit post"]
        assert freja.store.is_tombstoned(loot.msg_id)
        await sw.stop()
        await sf.stop()

    asyncio.run(scenario())


def test_own_devices_adopt_friend_list(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        befriend(wong, freja)
        # Home node paired at store level (ceremony is Task 13): same
        # identity, distinct device.
        from hearth.identity import DeviceKeys, DeviceView
        import json as _json
        from pathlib import Path
        home_dir = tmp_path / "h"
        home_dir.mkdir()
        home_dev = DeviceKeys.create("wong-homenode")
        home_dev.install(
            wong.device.enroll_other(home_dev.device_pub, home_dev.name),
            wong.device.to_json()["identity_priv"])
        (home_dir / "keys.json").write_text(_json.dumps(home_dev.to_json()))
        home = HearthNode(home_dir)
        home.store.add_identity(home.identity_pub, is_self=True)
        home.store.save_views(home.identity_pub, {
            home_dev.device_pub: DeviceView(cert=home_dev.cert)})

        sw, wa = await started(wong)
        sh, _ = await started(home)
        assert await sh.sync_with(wa) is True         # own-device session
        # home adopted wong's friend list (freja) + wong's messages
        assert freja.identity_pub in home.store.known_identities()
        assert len(home.feed()) == len(wong.feed())
        await sw.stop()
        await sh.stop()

    asyncio.run(scenario())
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_sync_session.py -q`
Expected: FAIL — `No module named 'hearth.sync'`.

- [ ] **Step 3: Implement `hearth/sync.py`**

```python
"""One gossip session: hello/auth -> revocations -> have -> messages ->
blobs. Revocations always travel before content (v0.3 binding finding).
Strangers are refused at AUTH: they never receive anything - the
structural half of the thesis, enforced at the socket layer."""
from __future__ import annotations

import asyncio
import base64
import os
from typing import Optional

from .identity import (
    PROTOCOL, EnrollmentCert, RevocationCert, SignedMessage, canonical,
    _sig_ok,
)
from .messages import MAX_BLOB_BYTES, blob_hash
from .transport import TcpTransport, read_frame, write_frame


def _auth_body(nonce_hex: str) -> bytes:
    # Domain-separated: never sign raw peer-supplied bytes.
    return canonical({"type": "gossip-auth", "protocol": PROTOCOL,
                      "nonce": nonce_hex})


class SyncService:
    def __init__(self, node, transport=None):
        self.node = node
        self.transport = transport or TcpTransport()
        self._server = None

    async def start(self, host: str, port: int) -> int:
        self._server = await self.transport.serve(host, port, self._on_conn)
        return self._server.sockets[0].getsockname()[1]

    async def stop(self):
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()
            self._server = None

    async def _on_conn(self, reader, writer):
        try:
            await self._session(reader, writer, initiator=False)
        except Exception:
            pass                        # malformed peer: drop connection
        finally:
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass

    async def sync_with(self, address: str) -> bool:
        try:
            reader, writer = await self.transport.connect(address)
        except OSError:
            return False                # peer offline: retry next round
        try:
            await self._session(reader, writer, initiator=True)
            return True
        except Exception:
            return False
        finally:
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass

    async def _swap(self, reader, writer, initiator: bool,
                    frame: dict) -> dict:
        """Send our frame and receive the peer's, initiator first."""
        if initiator:
            await write_frame(writer, frame)
            return await read_frame(reader)
        reply = await read_frame(reader)
        await write_frame(writer, frame)
        return reply

    async def _session(self, reader, writer, initiator: bool):
        node, store = self.node, self.node.store
        changed = False

        # -- HELLO --
        my_nonce = os.urandom(16).hex()
        hello = {"t": "hello", "cert": node.device.cert.to_dict(),
                 "nonce": my_nonce}
        peer_hello = await self._swap(reader, writer, initiator, hello)
        if peer_hello.get("t") != "hello":
            raise ValueError("bad hello")
        peer_cert = EnrollmentCert.from_dict(peer_hello["cert"])
        if not peer_cert.verify():
            raise ValueError("bad peer cert")

        # -- AUTH (mutual device-key proof) --
        auth = {"t": "auth",
                "sig": node.device.sign_raw(_auth_body(peer_hello["nonce"]))}
        peer_auth = await self._swap(reader, writer, initiator, auth)
        if (peer_auth.get("t") != "auth"
                or not _sig_ok(peer_cert.device_pub, peer_auth["sig"],
                               _auth_body(my_nonce))):
            raise ValueError("peer failed device-key proof")
        peer_identity = peer_cert.identity_pub
        if not store.is_known(peer_identity):
            await write_frame(writer, {"t": "refused"})
            raise ValueError("unknown identity refused")
        peer_view = store.load_views(peer_identity).get(peer_cert.device_pub)
        if peer_view is not None and peer_view.revocation is not None:
            await write_frame(writer, {"t": "refused"})
            raise ValueError("revoked device refused")

        # -- REVOCATIONS (always before content) --
        revs = {"t": "revocations",
                "revs": [r.to_dict() for r in store.list_revocations()]}
        peer_revs = await self._swap(reader, writer, initiator, revs)
        if peer_revs.get("t") == "refused":
            raise ValueError("refused by peer")
        for rd in peer_revs.get("revs", []):
            rev = RevocationCert.from_dict(rd)
            if store.is_known(rev.identity_pub):
                res = store.ingest_revocation(rev)
                if res.accepted and res.retro_dropped:
                    changed = True

        # -- HAVE (summaries, known identities, peer addresses) --
        have = {"t": "have", "summary": store.all_summaries(),
                "known": store.known_identities(),
                "peers": store.list_peers(),
                "addr": store.get_meta("gossip_addr")}
        peer_have = await self._swap(reader, writer, initiator, have)
        peer_known = set(peer_have.get("known", []))

        # Own-device trust: my other devices replicate my friend list.
        if peer_identity == node.identity_pub:
            for ident in peer_known:
                if not store.is_known(ident):
                    store.add_identity(ident)
                    changed = True
        my_addr = store.get_meta("gossip_addr")
        if peer_have.get("addr") and peer_have["addr"] != my_addr:
            store.add_peer(peer_have["addr"], peer_identity)
        for p in peer_have.get("peers", []):
            if (p.get("identity_pub") and store.is_known(p["identity_pub"])
                    and p["address"] != my_addr):
                store.add_peer(p["address"], p["identity_pub"])

        # -- MESSAGES (peer gets only identities it already knows) --
        entitled = {i for i in store.known_identities() if i in peer_known}
        to_send = store.messages_not_in(peer_have.get("summary", {}),
                                        entitled)
        msgs = {"t": "messages", "msgs": [m.to_dict() for m in to_send]}
        peer_msgs = await self._swap(reader, writer, initiator, msgs)
        for md in peer_msgs.get("msgs", []):
            res = store.ingest_message(SignedMessage.from_dict(md))
            if res.accepted:
                changed = True

        # -- BLOBS --
        want = {"t": "blob_want", "hashes": sorted(store.missing_blobs())}
        peer_want = await self._swap(reader, writer, initiator, want)
        give = {}
        for h in peer_want.get("hashes", []):
            data = store.get_blob(h)
            if data is not None:
                give[h] = base64.b64encode(data).decode()
        blobs = {"t": "blobs", "blobs": give}
        peer_blobs = await self._swap(reader, writer, initiator, blobs)
        for h, b64 in peer_blobs.get("blobs", {}).items():
            data = base64.b64decode(b64)
            if len(data) <= MAX_BLOB_BYTES and blob_hash(data) == h:
                store.put_blob(data)
                changed = True

        if changed:
            node.notify()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_sync_session.py -q`
Expected: 5 passed.

- [ ] **Step 5: Run the whole suite**

Run: `.\.venv\Scripts\python.exe -m pytest tests -q`
Expected: all tests pass (56 by this point).

- [ ] **Step 6: Commit**

```bash
git add hearth/sync.py tests/test_sync_session.py
git commit -m "feat: gossip sync session - auth, revocations-first, have/want, blobs"
```

---

### Task 12: Gossip loop + node runner (daemon wiring)

**Files:**
- Modify: `hearth/sync.py` (add `gossip_loop` method)
- Create: `hearth/runner.py`
- Test: `tests/test_gossip_loop.py`

**Interfaces:**
- Produces:
  - `SyncService.gossip_loop(interval: float = 3.0)` — forever: sync with every known peer address, sweep expired posts (notify if any swept), sleep.
  - `hearth.runner.run_node(data_dir, gossip_port, http_port, interval=3.0)` — async: loads node, starts SyncService (records actual `gossip_addr` in meta), serves the FastAPI app via uvicorn, runs the gossip loop concurrently. This is what `cli run` and `cli demo` call.

- [ ] **Step 1: Write the failing test**

`tests/test_gossip_loop.py`:
```python
import asyncio

from hearth.node import HearthNode
from hearth.sync import SyncService


def test_gossip_loop_carries_posts_and_sweeps_expiry(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        wong.store.add_identity(freja.identity_pub)
        freja.store.add_identity(wong.identity_pub)

        sw, sf = SyncService(wong), SyncService(freja)
        wp = await sw.start("127.0.0.1", 0)
        fp = await sf.start("127.0.0.1", 0)
        wong.store.set_meta("gossip_addr", f"127.0.0.1:{wp}")
        freja.store.set_meta("gossip_addr", f"127.0.0.1:{fp}")
        wong.store.add_peer(f"127.0.0.1:{fp}", freja.identity_pub)

        loop_task = asyncio.create_task(sw.gossip_loop(interval=0.05))
        wong.compose_post("carried by the loop")
        wong.compose_post("gone soon", expires_seconds=0.0)

        for _ in range(100):                     # up to ~5s
            await asyncio.sleep(0.05)
            texts = [p["text"] for p in freja.feed()]
            if texts == ["carried by the loop"] and \
                    "gone soon" not in [p["text"] for p in wong.feed()]:
                break
        else:
            raise AssertionError("gossip loop did not converge")

        loop_task.cancel()
        await sw.stop()
        await sf.stop()

    asyncio.run(scenario())
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_gossip_loop.py -q`
Expected: FAIL — `AttributeError: 'SyncService' object has no attribute 'gossip_loop'`.

- [ ] **Step 3: Add `gossip_loop` to `SyncService` in `hearth/sync.py`**

```python
    async def gossip_loop(self, interval: float = 3.0):
        while True:
            for peer in self.node.store.list_peers():
                await self.sync_with(peer["address"])
            if self.node.store.sweep_expired():
                self.node.notify()
            await asyncio.sleep(interval)
```

- [ ] **Step 4: Create `hearth/runner.py`**

```python
"""Wire one node's daemon: gossip listener + loop + localhost HTTP."""
from __future__ import annotations

import asyncio

import uvicorn

from .api import build_app
from .node import HearthNode
from .sync import SyncService


async def run_node(data_dir, gossip_port: int, http_port: int,
                   interval: float = 3.0):
    node = HearthNode(data_dir)
    sync = SyncService(node)
    port = await sync.start("127.0.0.1", gossip_port)
    node.store.set_meta("gossip_addr", f"127.0.0.1:{port}")
    server = uvicorn.Server(uvicorn.Config(
        build_app(node), host="127.0.0.1", port=http_port,
        log_level="warning"))
    try:
        await asyncio.gather(server.serve(), sync.gossip_loop(interval))
    finally:
        await sync.stop()
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_gossip_loop.py -q`
Expected: 1 passed.

- [ ] **Step 6: Commit**

```bash
git add hearth/sync.py hearth/runner.py tests/test_gossip_loop.py
git commit -m "feat: gossip loop and node runner"
```

---

### Task 13: Ceremonies — friend-add (QR stand-in) + device pairing, node/API/UI

**Files:**
- Modify: `hearth/node.py` (ceremony methods), `hearth/api.py` (5 endpoints), `hearth/web/app.js` (ceremony panel)
- Test: `tests/test_ceremonies.py`

**Interfaces:**
- Produces (methods on `HearthNode`; all payloads are JSON strings — the copy-paste stand-in for QR codes / the secure pairing channel):
  - Friend ceremony (mutual, 3 pastes; every signature over a domain-separated `{"type": "friend-add", "protocol", "nonce"}` body):
    - A: `create_invite() -> str` — cert + gossip addr + fresh nonce (A stores it pending)
    - B: `respond_to_invite(invite_json) -> str` — verifies A's cert; returns B's cert + addr + B's signature over A's nonce + B's own fresh nonce (stashed pending)
    - A: `finalize_invite(response_json) -> str` — verifies B's cert + signature; ADDS B as friend; returns A's signature over B's nonce
    - B: `complete_invite(final_json)` — verifies; ADDS A as friend
    - `ValueError` on any verification failure or unknown nonce
  - Device pairing:
    - New device (classmethod): `HearthNode.pair_request(data_dir, device_name) -> str` — writes unenrolled `keys.json`, returns request JSON
    - Existing device: `accept_pairing(request_json) -> str` — enrolls the device key, registers it in own views, returns package JSON (cert + identity-key replica + friend list + peer addresses + own gossip addr)
    - New device (classmethod): `HearthNode.pair_install(data_dir, package_json) -> HearthNode` — installs cert + identity key, registers self identity/view/friends/peers
- API additions (`hearth/api.py`), all wrapping `ValueError` as HTTP 400:
  - `POST /api/friend/invite` → `{"payload": <str>}`; `POST /api/friend/respond` body `{"payload"}` → `{"payload"}`; `POST /api/friend/finalize` body `{"payload"}` → `{"payload"}`; `POST /api/friend/complete` body `{"payload"}` → `{"ok": true}`
  - `POST /api/pair/accept` body `{"payload"}` → `{"payload"}`

- [ ] **Step 1: Write the failing tests**

`tests/test_ceremonies.py`:
```python
import json

import pytest

from hearth.node import HearthNode


def test_friend_ceremony_full_flow(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    wong.store.set_meta("gossip_addr", "127.0.0.1:7101")
    freja.store.set_meta("gossip_addr", "127.0.0.1:7103")

    invite = wong.create_invite()
    response = freja.respond_to_invite(invite)
    assert freja.store.is_known(wong.identity_pub) is False  # not yet
    final = wong.finalize_invite(response)
    assert wong.store.is_known(freja.identity_pub)           # A added
    freja.complete_invite(final)
    assert freja.store.is_known(wong.identity_pub)           # B added
    # both learned each other's gossip address
    assert any(p["address"] == "127.0.0.1:7103"
               for p in wong.store.list_peers())
    assert any(p["address"] == "127.0.0.1:7101"
               for p in freja.store.list_peers())


def test_tampered_final_rejected(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mallory = HearthNode.create(tmp_path / "m", "Mallory", "mal-phone")

    invite = wong.create_invite()
    response = freja.respond_to_invite(invite)
    wong.finalize_invite(response)
    # Mallory intercepts and substitutes her own signature.
    forged = json.dumps({"t": "hearth-final",
                         "nonce": json.loads(response)["peer_nonce"],
                         "sig": "ab" * 64})
    with pytest.raises(ValueError):
        freja.complete_invite(forged)
    assert freja.store.is_known(wong.identity_pub) is False


def test_replayed_invite_nonce_rejected(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    invite = wong.create_invite()
    response = freja.respond_to_invite(invite)
    wong.finalize_invite(response)
    with pytest.raises(ValueError):                 # nonce consumed
        wong.finalize_invite(response)


def test_pairing_full_flow(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    wong.store.set_meta("gossip_addr", "127.0.0.1:7101")
    # existing friendship so the package carries it
    invite = wong.create_invite()
    freja.complete_invite(wong.finalize_invite(
        freja.respond_to_invite(invite)))

    req = HearthNode.pair_request(tmp_path / "h", "wong-homenode")
    pkg = wong.accept_pairing(req)
    home = HearthNode.pair_install(tmp_path / "h", pkg)

    assert home.identity_pub == wong.identity_pub    # same person
    assert home.device.cert.verify()
    assert home.device.device_pub != wong.device.device_pub
    assert freja.identity_pub in home.store.known_identities()
    assert any(p["address"] == "127.0.0.1:7101"
               for p in home.store.list_peers())
    # wong's own view now includes the home node
    names = {d["name"] for d in wong.devices()}
    assert "wong-homenode" in names


def test_api_ceremony_endpoints(tmp_path):
    from fastapi.testclient import TestClient
    from hearth.api import build_app
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    cw, cf = TestClient(build_app(wong)), TestClient(build_app(freja))

    invite = cw.post("/api/friend/invite").json()["payload"]
    response = cf.post("/api/friend/respond",
                       json={"payload": invite}).json()["payload"]
    final = cw.post("/api/friend/finalize",
                    json={"payload": response}).json()["payload"]
    assert cf.post("/api/friend/complete",
                   json={"payload": final}).status_code == 200
    assert len(cw.get("/api/state").json()["friends"]) == 1
    assert len(cf.get("/api/state").json()["friends"]) == 1
    # garbage payload -> 400, not 500
    assert cf.post("/api/friend/respond",
                   json={"payload": "not json"}).status_code == 400
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_ceremonies.py -q`
Expected: FAIL — `AttributeError: 'HearthNode' object has no attribute 'create_invite'`.

- [ ] **Step 3: Add ceremony methods to `hearth/node.py`**

Add imports at top of the file:
```python
import os

from .identity import (DeviceKeys, DeviceView, EnrollmentCert,
                       IdentityCeremony, PROTOCOL, canonical, _sig_ok)
```
(replacing the existing narrower `.identity` import), add `self._pending_invites = {}` and `self._pending_responses = {}` at the end of `__init__`, then append to the class:

```python
    # -- friend ceremony (copy-paste stands in for the QR scan) -----------------

    def create_invite(self) -> str:
        nonce = os.urandom(16).hex()
        self._pending_invites[nonce] = True
        return json.dumps({
            "t": "hearth-invite", "protocol": PROTOCOL,
            "cert": self.device.cert.to_dict(),
            "addr": self.store.get_meta("gossip_addr"), "nonce": nonce,
        })

    def respond_to_invite(self, invite_json: str) -> str:
        try:
            inv = json.loads(invite_json)
        except json.JSONDecodeError:
            raise ValueError("invite is not valid JSON")
        if inv.get("t") != "hearth-invite":
            raise ValueError("not an invite")
        cert = EnrollmentCert.from_dict(inv["cert"])
        if not cert.verify():
            raise ValueError("invalid cert in invite")
        my_nonce = os.urandom(16).hex()
        self._pending_responses[my_nonce] = (cert, inv.get("addr"))
        return json.dumps({
            "t": "hearth-response", "protocol": PROTOCOL,
            "cert": self.device.cert.to_dict(),
            "addr": self.store.get_meta("gossip_addr"),
            "nonce": inv["nonce"],
            "sig": self.device.sign_raw(_friend_add_body(inv["nonce"])),
            "peer_nonce": my_nonce,
        })

    def finalize_invite(self, response_json: str) -> str:
        try:
            resp = json.loads(response_json)
        except json.JSONDecodeError:
            raise ValueError("response is not valid JSON")
        nonce = resp.get("nonce")
        if resp.get("t") != "hearth-response" \
                or nonce not in self._pending_invites:
            raise ValueError("no matching invite")
        cert = EnrollmentCert.from_dict(resp["cert"])
        if not cert.verify() or not _sig_ok(
                cert.device_pub, resp["sig"], _friend_add_body(nonce)):
            raise ValueError("invalid response signature")
        del self._pending_invites[nonce]
        self._add_friend(cert, resp.get("addr"))
        return json.dumps({
            "t": "hearth-final", "protocol": PROTOCOL,
            "nonce": resp["peer_nonce"],
            "sig": self.device.sign_raw(
                _friend_add_body(resp["peer_nonce"])),
        })

    def complete_invite(self, final_json: str):
        try:
            fin = json.loads(final_json)
        except json.JSONDecodeError:
            raise ValueError("final is not valid JSON")
        entry = self._pending_responses.pop(fin.get("nonce"), None)
        if fin.get("t") != "hearth-final" or entry is None:
            raise ValueError("no matching response")
        cert, addr = entry
        if not _sig_ok(cert.device_pub, fin["sig"],
                       _friend_add_body(fin["nonce"])):
            raise ValueError("invalid final signature")
        self._add_friend(cert, addr)

    def _add_friend(self, cert: EnrollmentCert, addr):
        self.store.add_identity(cert.identity_pub)
        views = self.store.load_views(cert.identity_pub)
        if cert.device_pub not in views:
            views[cert.device_pub] = DeviceView(cert=cert)
            self.store.save_views(cert.identity_pub, views)
        if addr:
            self.store.add_peer(addr, cert.identity_pub)
        self.notify()

    # -- device pairing (copy-paste stands in for the secure local channel) ------

    @classmethod
    def pair_request(cls, data_dir, device_name: str) -> str:
        data_dir = Path(data_dir)
        data_dir.mkdir(parents=True, exist_ok=True)
        device = DeviceKeys.create(device_name)
        (data_dir / "keys.json").write_text(json.dumps(device.to_json()))
        return json.dumps({
            "t": "hearth-pair-request", "protocol": PROTOCOL,
            "device_pub": device.device_pub, "device_name": device_name,
        })

    def accept_pairing(self, request_json: str) -> str:
        try:
            req = json.loads(request_json)
        except json.JSONDecodeError:
            raise ValueError("pairing request is not valid JSON")
        if req.get("t") != "hearth-pair-request":
            raise ValueError("not a pairing request")
        cert = self.device.enroll_other(req["device_pub"],
                                        req["device_name"])
        views = self.store.load_views(self.identity_pub)
        views[req["device_pub"]] = DeviceView(cert=cert)
        self.store.save_views(self.identity_pub, views)
        self.notify()
        return json.dumps({
            "t": "hearth-pair-package", "protocol": PROTOCOL,
            "cert": cert.to_dict(),
            "identity_priv": self.device.to_json()["identity_priv"],
            "friends": [i for i in self.store.known_identities()
                        if i != self.identity_pub],
            "peers": self.store.list_peers(),
            "my_addr": self.store.get_meta("gossip_addr"),
        })

    @classmethod
    def pair_install(cls, data_dir, package_json: str) -> "HearthNode":
        data_dir = Path(data_dir)
        pkg = json.loads(package_json)
        if pkg.get("t") != "hearth-pair-package":
            raise ValueError("not a pairing package")
        device = DeviceKeys.from_json(
            json.loads((data_dir / "keys.json").read_text()))
        device.install(EnrollmentCert.from_dict(pkg["cert"]),
                       pkg["identity_priv"])
        (data_dir / "keys.json").write_text(json.dumps(device.to_json()))
        node = cls(data_dir)
        node.store.add_identity(device.identity_pub, is_self=True)
        node.store.save_views(device.identity_pub, {
            device.device_pub: DeviceView(cert=device.cert)})
        for ident in pkg.get("friends", []):
            node.store.add_identity(ident)
        for p in pkg.get("peers", []):
            node.store.add_peer(p["address"], p.get("identity_pub"))
        if pkg.get("my_addr"):
            node.store.add_peer(pkg["my_addr"], device.identity_pub)
        return node
```

And module-level, at the bottom of `hearth/node.py`:

```python
def _friend_add_body(nonce: str) -> bytes:
    return canonical({"type": "friend-add", "protocol": PROTOCOL,
                      "nonce": nonce})
```

- [ ] **Step 4: Add ceremony endpoints to `hearth/api.py`** (inside `build_app`, before the websocket route)

```python
    def _400(fn, *args):
        try:
            return fn(*args)
        except ValueError as e:
            raise HTTPException(400, str(e))

    @app.post("/api/friend/invite")
    async def friend_invite():
        return {"payload": node.create_invite()}

    @app.post("/api/friend/respond")
    async def friend_respond(body: dict = Body(...)):
        return {"payload": _400(node.respond_to_invite, body["payload"])}

    @app.post("/api/friend/finalize")
    async def friend_finalize(body: dict = Body(...)):
        return {"payload": _400(node.finalize_invite, body["payload"])}

    @app.post("/api/friend/complete")
    async def friend_complete(body: dict = Body(...)):
        _400(node.complete_invite, body["payload"])
        return {"ok": True}

    @app.post("/api/pair/accept")
    async def pair_accept(body: dict = Body(...)):
        return {"payload": _400(node.accept_pairing, body["payload"])}
```

- [ ] **Step 5: Add the ceremony panel to `hearth/web/app.js`** (append before the final `refresh();` line, and add `ceremonyUI();` right after `connectWs();`)

```javascript
function ceremonyUI() {
  const root = document.getElementById("ceremony");
  root.replaceChildren();
  const ta = document.createElement("textarea");
  ta.rows = 4;
  ta.placeholder = "Paste a code here, or click Show my code";
  const status = el("div", "hint",
    "Friend-add: exchange codes in person, one paste at a time.");
  const step = async (url, sendPayload, nextText, clearAfter) => {
    try {
      const body = sendPayload
        ? {method: "POST", headers: {"Content-Type": "application/json"},
           body: JSON.stringify({payload: ta.value})}
        : {method: "POST"};
      const r = await j(url, body);
      ta.value = clearAfter ? "" : (r.payload || "");
      status.textContent = nextText;
      refresh();
    } catch (e) { status.textContent = "Rejected: " + e.message; }
  };
  const mk = (label, fn) => {
    const b = el("button", "", label);
    b.onclick = fn;
    return b;
  };
  root.append(ta,
    mk("1. Show my code", () => step("/api/friend/invite", false,
      "Give this code to your friend. They paste it and press 2.")),
    mk("2. Respond", () => step("/api/friend/respond", true,
      "Send this response back. They paste it and press 3.")),
    mk("3. Finalize", () => step("/api/friend/finalize", true,
      "Friend added on your side. Send this final code; they press 4.")),
    mk("4. Complete", () => step("/api/friend/complete", true,
      "Friend added.", true)),
    status);
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_ceremonies.py -q`
Expected: 5 passed.

Run: `.\.venv\Scripts\python.exe -m pytest tests -q`
Expected: whole suite passes.

- [ ] **Step 7: Commit**

```bash
git add hearth/node.py hearth/api.py hearth/web/app.js tests/test_ceremonies.py
git commit -m "feat: friend-add and device-pairing ceremonies (node, API, UI)"
```

---

### Task 14: CLI, three-node demo, end-to-end test, README

**Files:**
- Create: `hearth/cli.py`, `hearth/__main__.py`, `hearth/demo.py`, `README.md`
- Modify: `hearth/store.py` (add `close()`), `hearth/node.py` (add `close()`)
- Test: `tests/test_three_nodes.py`

**Interfaces:**
- `python -m hearth init --dir D --person NAME --device NAME`
- `python -m hearth run --dir D --gossip-port N --http-port N [--interval S]`
- `python -m hearth pair-request --dir D --device NAME` (prints request JSON), `pair-accept --dir D --request-file F --package-file F`, `pair-install --dir D --package-file F`
- `python -m hearth demo` — builds the cast on first run (`run/wong-phone` 7101/7201, `run/wong-homenode` 7102/7202, `run/freja-phone` 7103/7203; pairing + friendship pre-wired), then serves all three nodes in one process. ASCII-only prints.
- `Store.close()` / `HearthNode.close()` — release the sqlite handle (the demo builds the cast, closes, then reopens inside `run_node`).

- [ ] **Step 1: Write the failing end-to-end test**

`tests/test_three_nodes.py`:
```python
"""The spec's success criterion as one test: three devices, friendship,
photo propagation, offline catch-up via the home node, deletion, and
revocation with cross-network retro-drop."""
import asyncio

from hearth.node import HearthNode
from hearth.sync import SyncService


def test_three_node_story(tmp_path):
    asyncio.run(_story(tmp_path))


async def _story(tmp_path):
    # Cast: wong-phone creates the identity, pairs wong-homenode,
    # friend-ceremonies freja-phone.
    wong = HearthNode.create(tmp_path / "wp", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "fp", "Freja", "freja-phone")

    sw = SyncService(wong)
    sf = SyncService(freja)
    wa = f"127.0.0.1:{await sw.start('127.0.0.1', 0)}"
    fa = f"127.0.0.1:{await sf.start('127.0.0.1', 0)}"
    wong.store.set_meta("gossip_addr", wa)
    freja.store.set_meta("gossip_addr", fa)

    req = HearthNode.pair_request(tmp_path / "wh", "wong-homenode")
    home = HearthNode.pair_install(tmp_path / "wh", wong.accept_pairing(req))
    sh = SyncService(home)
    ha = f"127.0.0.1:{await sh.start('127.0.0.1', 0)}"
    home.store.set_meta("gossip_addr", ha)
    home.store.add_peer(wa, wong.identity_pub)

    final = wong.finalize_invite(
        freja.respond_to_invite(wong.create_invite()))
    freja.complete_invite(final)

    # 1. Wong posts a photo; it reaches Freja over real sockets.
    photo = b"\x89PNG-canal-sunset"
    p1 = wong.compose_post("aftensol over kanalen", [photo])
    assert await sw.sync_with(fa)
    feed = freja.feed()
    assert feed[0]["text"] == "aftensol over kanalen"
    assert freja.store.get_blob(feed[0]["blobs"][0]) == photo

    # 2. Home node catches up from the phone (own-device replication),
    #    learning the friend list and Freja's address along the way.
    assert await sh.sync_with(wa)
    assert freja.identity_pub in home.store.known_identities()
    assert len(home.feed()) == len(wong.feed())

    # 3. Offline catch-up: phone goes dark; a post authored on the phone
    #    earlier still reaches Freja VIA THE HOME NODE.
    p2 = wong.compose_post("inden telefonen doede")
    assert await sh.sync_with(wa)                 # home has p2
    await sw.stop()                               # phone offline
    assert await sf.sync_with(ha)                 # freja <- home node
    assert {p["text"] for p in freja.feed()} == {
        "aftensol over kanalen", "inden telefonen doede"}

    # 4. Deletion propagates through the mesh (home relays the tag).
    #    Wong deletes p1 on the phone; phone comes back online.
    wa = f"127.0.0.1:{await sw.start('127.0.0.1', 0)}"
    wong.store.set_meta("gossip_addr", wa)
    wong.delete_post(p1)
    assert await sw.sync_with(fa)
    assert [p["text"] for p in freja.feed()] == ["inden telefonen doede"]
    assert freja.store.is_tombstoned(p1)

    # 5. Revocation + retro-drop: the phone is stolen; loot reaches Freja
    #    in the gossip-lag window; Wong revokes FROM THE HOME NODE; the
    #    revocation propagates and Freja retro-drops.
    from hearth.messages import make_post
    loot = make_post(wong.device, "send crypto til denne konto")
    assert freja.store.ingest_message(loot).accepted   # window exposure
    home.revoke_device(wong.device.device_pub)
    assert await sh.sync_with(fa)
    assert [p["text"] for p in freja.feed()] == ["inden telefonen doede"]
    assert freja.store.is_tombstoned(loot.msg_id)

    await sw.stop()
    await sh.stop()
    await sf.stop()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_three_nodes.py -q`
Expected: FAIL initially only if earlier tasks are incomplete; if Tasks 2-13 are done this may PASS immediately — that is fine, it is the integration checkpoint. Either way, continue.

- [ ] **Step 3: Add `close()` to store and node**

Append to `Store` in `hearth/store.py`:
```python
    def close(self):
        with self._lock:
            self._db.close()
```

Append to `HearthNode` in `hearth/node.py`:
```python
    def close(self):
        self.store.close()
```

- [ ] **Step 4: Create `hearth/demo.py`**

```python
"""Three-node demo cast, pre-wired: wong-phone + wong-homenode +
freja-phone on fixed local ports. ASCII-only prints (cp1252 console)."""
from __future__ import annotations

import asyncio
from pathlib import Path

from .node import HearthNode
from .runner import run_node

CAST = [
    ("run/wong-phone", 7101, 7201),
    ("run/wong-homenode", 7102, 7202),
    ("run/freja-phone", 7103, 7203),
]


def build_cast():
    if Path("run/wong-phone/keys.json").exists():
        return False
    wong = HearthNode.create("run/wong-phone", "Wong", "wong-phone")
    wong.store.set_meta("gossip_addr", "127.0.0.1:7101")

    req = HearthNode.pair_request("run/wong-homenode", "wong-homenode")
    home = HearthNode.pair_install("run/wong-homenode",
                                   wong.accept_pairing(req))
    home.store.set_meta("gossip_addr", "127.0.0.1:7102")
    home.store.add_peer("127.0.0.1:7101", wong.identity_pub)

    freja = HearthNode.create("run/freja-phone", "Freja", "freja-phone")
    freja.store.set_meta("gossip_addr", "127.0.0.1:7103")

    final = wong.finalize_invite(
        freja.respond_to_invite(wong.create_invite()))
    freja.complete_invite(final)

    freja.compose_post("Hej Wong! Fint at vaere her.")
    wong.compose_post("Velkommen til Hearth, Freja.")

    for n in (wong, home, freja):
        n.close()
    return True


async def demo():
    fresh = build_cast()
    print("Hearth demo - three nodes on this machine")
    print("  (cast was %s)" % ("just created" if fresh else "reused"))
    print()
    print("  Wong's phone      http://127.0.0.1:7201")
    print("  Wong's home node  http://127.0.0.1:7202")
    print("  Freja's phone     http://127.0.0.1:7203")
    print()
    print("Open Wong's phone and Freja's phone side by side.")
    print("Post something - it appears on the other side within seconds.")
    print("Ctrl+C stops all three nodes. Data lives in run/.")
    await asyncio.gather(*(run_node(d, gp, hp, interval=2.0)
                           for d, gp, hp in CAST))
```

- [ ] **Step 5: Create `hearth/cli.py` and `hearth/__main__.py`**

`hearth/cli.py`:
```python
"""Hearth command line: init, run, pairing, demo."""
from __future__ import annotations

import argparse
import asyncio
from pathlib import Path

from .node import HearthNode


def main(argv=None):
    p = argparse.ArgumentParser(prog="hearth",
                                description="Hearth vertical slice")
    sub = p.add_subparsers(dest="cmd", required=True)

    sp = sub.add_parser("init", help="create a new identity + first device")
    sp.add_argument("--dir", required=True)
    sp.add_argument("--person", required=True)
    sp.add_argument("--device", required=True)

    sp = sub.add_parser("run", help="run one node daemon")
    sp.add_argument("--dir", required=True)
    sp.add_argument("--gossip-port", type=int, required=True)
    sp.add_argument("--http-port", type=int, required=True)
    sp.add_argument("--interval", type=float, default=3.0)

    sp = sub.add_parser("pair-request",
                        help="new device: create keys + print request")
    sp.add_argument("--dir", required=True)
    sp.add_argument("--device", required=True)

    sp = sub.add_parser("pair-accept",
                        help="existing device: turn request into package")
    sp.add_argument("--dir", required=True)
    sp.add_argument("--request-file", required=True)
    sp.add_argument("--package-file", required=True)

    sp = sub.add_parser("pair-install",
                        help="new device: install package")
    sp.add_argument("--dir", required=True)
    sp.add_argument("--package-file", required=True)

    sub.add_parser("demo", help="run the three-node demo cast")

    args = p.parse_args(argv)

    if args.cmd == "init":
        node = HearthNode.create(args.dir, args.person, args.device)
        print("identity: " + node.identity_pub)
        print("paper seed written to "
              + str(Path(args.dir) / "paper_seed.txt"))
        node.close()
    elif args.cmd == "run":
        from .runner import run_node
        asyncio.run(run_node(args.dir, args.gossip_port, args.http_port,
                             args.interval))
    elif args.cmd == "pair-request":
        print(HearthNode.pair_request(args.dir, args.device))
    elif args.cmd == "pair-accept":
        node = HearthNode(args.dir)
        pkg = node.accept_pairing(
            Path(args.request_file).read_text())
        Path(args.package_file).write_text(pkg)
        print("package written to " + args.package_file)
        node.close()
    elif args.cmd == "pair-install":
        node = HearthNode.pair_install(
            args.dir, Path(args.package_file).read_text())
        print("device enrolled; identity: " + node.identity_pub)
        node.close()
    elif args.cmd == "demo":
        from .demo import demo
        try:
            asyncio.run(demo())
        except KeyboardInterrupt:
            print("demo stopped")
```

`hearth/__main__.py`:
```python
from .cli import main

main()
```

- [ ] **Step 6: Create `README.md`**

```markdown
# Hearth — vertical slice v0.1

Private, non-commercial, peer-to-peer social space. Your data lives on
hardware you own; you can only add people you have physically met; the
influencer economy is impossible by architecture. This repo holds the
concept documents, the D2 identity spike, and the local vertical slice:
three real node processes gossiping signed posts over TCP.

Spec: `docs/superpowers/specs/2026-07-02-hearth-vertical-slice-design.md`
Concept: `hearth_concept_capture_v0_3.md`

## Run the demo

    .venv\Scripts\python.exe -m hearth demo

Then open http://127.0.0.1:7201 (Wong's phone) and
http://127.0.0.1:7203 (Freja's phone) side by side.

## Tests

    .venv\Scripts\python.exe -m pytest tests -q

## Honest deviations from real Hearth (slice only)

- Copy-paste stands in for the QR camera scan and the secure pairing
  channel. `127.0.0.1:port` stands in for a Tor onion address.
- Transport is signed but PLAINTEXT TCP on localhost. Mandatory before
  any real network: transport encryption. Not wired: Tor (the transport
  interface is shaped for it), encryption at rest, OS-keystore key
  protection, notifications.
```

- [ ] **Step 7: Run the full suite + the demo smoke check**

Run: `.\.venv\Scripts\python.exe -m pytest tests -q`
Expected: all tests pass (including the three-node story).

Run: `.\.venv\Scripts\python.exe -m hearth demo` (leave running ~20 s)
Expected: three URLs print; opening 7201 and 7203 shows the seeded posts within a few gossip rounds; Ctrl+C stops. `run/` is gitignored.

- [ ] **Step 8: Commit**

```bash
git add hearth/cli.py hearth/__main__.py hearth/demo.py hearth/store.py hearth/node.py README.md tests/test_three_nodes.py
git commit -m "feat: CLI, three-node demo cast, end-to-end story test, README"
```

---

## Plan self-review notes (written at planning time)

- **Spec coverage:** identity w/ seen-set + retro-drop (T2-4), payloads + expiry (T5), store + tombstones + GC (T6-7), single-node daemon + API + dark web UI (T8-9), transport interface for Tor swap (T10), sync with auth/revocations-first/have-want/blobs + structural stranger refusal (T11), gossip loop + runner (T12), QR-stand-in friend ceremony + pairing + UI panel (T13), demo cast + success-criterion e2e + README (T14). Friends-replicate-your-feed appears as entitlement + own-device adoption (T11) and is exercised by the offline catch-up step in T14.
- **Deliberate deviations carried from spec:** UI pairing is CLI-first (`pair-request`/`pair-accept`/`pair-install`); the Devices panel shows devices + revoke. The spec's "pair new device" UI button is satisfied via `/api/pair/accept` + CLI on the new-device side — full browser pairing flow is follow-up polish.
- **Type consistency spot-checks:** `IngestResult` fields used by sync/T11 tests match T6 definition; `SeenSet.summary_has` signature consistent T3/T7/T11; reason strings in T4/T7 tests match implementations; `sign_raw`/`_sig_ok` used in T11/T13 defined in T2; `gossip_addr` meta key consistent T11/T12/T13/T14.
- **Known risks for executors:** (1) `TestClient` requires `httpx`; file upload field name must be `photos` (repeated) to match `List[UploadFile]`. (2) On Windows, `asyncio.run` per-test opens/closes real sockets — if a port-in-use flake appears, it is a leaked server from an earlier failure; all tests bind port 0. (3) SQLite WAL files (`*.db-wal`) appear next to databases in `run/` — covered by the `run/` gitignore entry. (4) The `from .identity import _sig_ok` import is intentional same-package use of a private helper.
