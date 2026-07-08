# Kreds App-Lock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A credential (PIN or passphrase) that gates the running node — encrypting its key material at rest under a Windows-sealed device secret, booting locked, refusing all content APIs until unlocked, and auto-locking on idle/sleep.

**Architecture:** New `hearth/applock.py` (scrypt-KDF + HKDF + ChaCha20-Poly1305 + Windows DPAPI, no new dep). When enabled, the node's secret key fields live only in an encrypted `applock.json`; `keys.json` keeps non-secret fields + `"applock": true`. The node boots locked (no private keys in memory), and `unlock` rebuilds the full `DeviceKeys` via `from_json(non-secret ∪ decrypted-secrets)`. A locked-guard returns 423 for content APIs; auto-lock is node-tracked (idle timer + wall-clock-jump sleep detection).

**Tech Stack:** Python 3.12, `cryptography` (Scrypt, HKDF, ChaCha20Poly1305 — already a dep), `ctypes` (DPAPI, stdlib), FastAPI, pytest; vanilla-JS client.

**Spec:** `docs/superpowers/specs/2026-07-07-kreds-app-lock-design.md`

## Global Constraints

- Branch: `kreds-app-lock` off `main` (already created + checked out — do NOT re-branch).
- Quality over shortcuts. NO new dependency (use `cryptography`'s Scrypt + `ctypes` DPAPI). Test runner: `timeout 180 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3`; full suite green each commit; `node --check hearth/web/app.js` clean. ASCII-only Python prints.
- **Secret bundle** (encrypt at rest): `device_priv`, `identity_priv`, `enc_priv`, `retired_enc`, `storage_key`. **Non-secret** (plaintext keys.json): `name`, `cert`, `seq`, `enc_pub`, `device_pub`, `applock`. When App-lock is ON, plaintext secrets must NOT exist on disk.
- **No auto-wipe** on failed unlock (keys = identity). Unlock is throttled in-memory (escalating delay).
- App-lock is **opt-in per node**: no `applock.json` → the node behaves exactly as today (the 4-node demo path is unchanged). Never break the non-App-lock path.
- KDF: `Scrypt(salt=16B, length=32, n=2**15, r=8, p=1)` then `HKDF(SHA256, length=32, salt=device_secret, info=b"hearth-applock-v1")`. AEAD: `ChaCha20Poly1305`, `aad=b"hearth-applock"`. A wrong credential → `InvalidTag` → `BadCredential` (the tag IS the verifier).
- Crypto/security testing is the implementer's + Claude's (the controller verifies); the client lock-screen/settings UX is verified by the USER (per the testing-workflow split) — but still add asset/DOM tests.

---

### Task 1: `hearth/applock.py` — crypto core

**Files:**
- Create: `hearth/applock.py`
- Test: `tests/test_applock.py`

**Interfaces:**
- Produces: `dpapi_seal(bytes)->bytes`, `dpapi_unseal(bytes)->bytes` (+ off-Windows fallback via a `data_dir` keyfile); `enable(secrets, credential, cred_type, seal) -> record`; `unlock(record, credential, unseal) -> secrets`; `session_master(record, credential, unseal) -> bytes` (re-derive the master so the node can hold it while unlocked WITHOUT keeping the credential); `reencrypt(record, secrets, master) -> record` (re-seal the secret bundle under the same device secret, fresh nonce, for the save path); `change_credential(record, old, new, unseal, seal) -> record`; `class BadCredential(Exception)`.

- [ ] **Step 1: Branch exists — skip; start at Step 2.**

- [ ] **Step 2: Failing tests** — `tests/test_applock.py`:

```python
import pytest
from hearth import applock

SECRETS = {"device_priv": "aa"*32, "identity_priv": "bb"*32,
           "enc_priv": "cc"*32, "retired_enc": [], "storage_key": "dd"*32}

def _seal_pair():
    store = {}
    def seal(b): store["s"] = b; return b[::-1]          # fake reversible seal for unit test
    def unseal(b): return b[::-1]
    return seal, unseal

def test_enable_unlock_roundtrip():
    seal, unseal = _seal_pair()
    rec = applock.enable(SECRETS, "1234", "pin", seal)
    assert applock.unlock(rec, "1234", unseal) == SECRETS

def test_wrong_credential_fails():
    seal, unseal = _seal_pair()
    rec = applock.enable(SECRETS, "1234", "pin", seal)
    with pytest.raises(applock.BadCredential):
        applock.unlock(rec, "9999", unseal)

def test_tampered_ct_fails():
    seal, unseal = _seal_pair()
    rec = applock.enable(SECRETS, "1234", "pin", seal)
    rec = {**rec, "ct_hex": ("00" + rec["ct_hex"][2:])}
    with pytest.raises(Exception):
        applock.unlock(rec, "1234", unseal)

def test_needs_device_secret():
    seal, unseal = _seal_pair()
    rec = applock.enable(SECRETS, "1234", "pin", seal)
    bad_unseal = lambda b: b"\x00"*32          # wrong device secret
    with pytest.raises(applock.BadCredential):
        applock.unlock(rec, "1234", bad_unseal)

def test_record_has_no_plaintext_secret():
    seal, unseal = _seal_pair()
    rec = applock.enable(SECRETS, "1234", "pin", seal)
    blob = repr(rec)
    for v in ("aa"*32, "bb"*32, "cc"*32, "dd"*32):
        assert v not in blob

def test_change_credential():
    seal, unseal = _seal_pair()
    rec = applock.enable(SECRETS, "1234", "pin", seal)
    rec2 = applock.change_credential(rec, "1234", "5678", unseal, seal)
    assert applock.unlock(rec2, "5678", unseal) == SECRETS
    with pytest.raises(applock.BadCredential):
        applock.unlock(rec2, "1234", unseal)

@pytest.mark.skipif(not applock.DPAPI_AVAILABLE, reason="Windows only")
def test_dpapi_roundtrip():
    blob = applock.dpapi_seal(b"secret-device-key-material-32byte")
    assert blob != b"secret-device-key-material-32byte"
    assert applock.dpapi_unseal(blob) == b"secret-device-key-material-32byte"
```

- [ ] **Step 3: Run — expect failure.**

- [ ] **Step 4: Implement `hearth/applock.py`:**

```python
"""App-lock crypto: encrypt the node's secret key material at rest under a
credential (PIN/passphrase) COMBINED with a Windows-DPAPI-sealed random device
secret, so a short credential is still strong at rest (the device carries the
entropy). No new dependency: cryptography's Scrypt/HKDF/ChaCha20Poly1305 + ctypes DPAPI."""
import ctypes, json, os, sys
from ctypes import wintypes
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.hashes import SHA256
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

SCRYPT_N, SCRYPT_R, SCRYPT_P = 2**15, 8, 1
DPAPI_AVAILABLE = sys.platform == "win32"

class BadCredential(Exception):
    pass

# --- Windows DPAPI (per-user) via ctypes -------------------------------------
class _BLOB(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]

def _in_blob(data: bytes) -> _BLOB:
    buf = ctypes.create_string_buffer(data, len(data))
    return _BLOB(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))

def dpapi_seal(data: bytes) -> bytes:
    out = _BLOB()
    if not ctypes.windll.crypt32.CryptProtectData(
            ctypes.byref(_in_blob(data)), u"hearth-applock", None, None, None, 0,
            ctypes.byref(out)):
        raise OSError("CryptProtectData failed")
    res = ctypes.string_at(out.pbData, out.cbData)
    ctypes.windll.kernel32.LocalFree(out.pbData)
    return res

def dpapi_unseal(blob: bytes) -> bytes:
    out = _BLOB()
    if not ctypes.windll.crypt32.CryptUnprotectData(
            ctypes.byref(_in_blob(blob)), None, None, None, None, 0,
            ctypes.byref(out)):
        raise OSError("CryptUnprotectData failed")
    res = ctypes.string_at(out.pbData, out.cbData)
    ctypes.windll.kernel32.LocalFree(out.pbData)
    return res

# --- key derivation + record -------------------------------------------------
def _master(credential: str, salt: bytes, device_secret: bytes) -> bytes:
    pw = Scrypt(salt=salt, length=32, n=SCRYPT_N, r=SCRYPT_R, p=SCRYPT_P).derive(
        credential.encode("utf-8"))
    return HKDF(algorithm=SHA256(), length=32, salt=device_secret,
                info=b"hearth-applock-v1").derive(pw)

def enable(secrets: dict, credential: str, cred_type: str, seal) -> dict:
    device_secret = os.urandom(32)
    salt = os.urandom(16)
    master = _master(credential, salt, device_secret)
    nonce = os.urandom(12)
    ct = ChaCha20Poly1305(master).encrypt(
        nonce, json.dumps(secrets).encode("utf-8"), b"hearth-applock")
    return {
        "version": 1, "cred_type": cred_type,
        "kdf": {"name": "scrypt", "n": SCRYPT_N, "r": SCRYPT_R, "p": SCRYPT_P,
                "salt_hex": salt.hex()},
        "sealed_device_secret_hex": seal(device_secret).hex(),
        "nonce_hex": nonce.hex(), "ct_hex": ct.hex(),
        "settings": {"idle_minutes": 0, "lock_on_sleep": True},
    }

def unlock(record: dict, credential: str, unseal) -> dict:
    device_secret = unseal(bytes.fromhex(record["sealed_device_secret_hex"]))
    salt = bytes.fromhex(record["kdf"]["salt_hex"])
    master = _master(credential, salt, device_secret)
    try:
        pt = ChaCha20Poly1305(master).decrypt(
            bytes.fromhex(record["nonce_hex"]),
            bytes.fromhex(record["ct_hex"]), b"hearth-applock")
    except Exception:
        raise BadCredential("wrong credential")
    return json.loads(pt)

def session_master(record: dict, credential: str, unseal) -> bytes:
    """Re-derive the AEAD master. The node calls this at unlock/enable time
    (when it briefly has the credential) and holds the master while unlocked so
    it can re-persist secret-bundle changes -- it never retains the credential."""
    device_secret = unseal(bytes.fromhex(record["sealed_device_secret_hex"]))
    return _master(credential, bytes.fromhex(record["kdf"]["salt_hex"]), device_secret)

def reencrypt(record: dict, secrets: dict, master: bytes) -> dict:
    """Re-seal the secret bundle under the SAME device secret + master (fresh
    nonce). Used by _save_keys when a rotation mutates secrets while unlocked."""
    nonce = os.urandom(12)
    ct = ChaCha20Poly1305(master).encrypt(
        nonce, json.dumps(secrets).encode("utf-8"), b"hearth-applock")
    return {**record, "nonce_hex": nonce.hex(), "ct_hex": ct.hex()}

def change_credential(record: dict, old: str, new: str, unseal, seal) -> dict:
    secrets = unlock(record, old, unseal)                # verifies old
    rec = enable(secrets, new, record["cred_type"], seal)
    rec["settings"] = record.get("settings", rec["settings"])   # keep settings
    return rec
```

(Note: the fake `seal`/`unseal` in tests inject DPAPI so the crypto is testable off the real keystore; the node passes real `dpapi_seal`/`dpapi_unseal`. `test_tampered_ct_fails` expects the reversed-seal to still make the master wrong OR the tag to fail — since the fake seal is reversible and deterministic, tampering ct alone triggers `InvalidTag`; keep the assertion as "raises".)

- [ ] **Step 5: Run tests green. Commit.**

```powershell
git add hearth/applock.py tests/test_applock.py
git commit -m "feat: applock crypto core - scrypt+HKDF+ChaCha20Poly1305 over a DPAPI-sealed device secret"
```

---

### Task 2: DeviceKeys locked support + node lock-state + keys.json/applock.json split

**Files:**
- Modify: `hearth/identity.py` (`DeviceKeys`: allow `device_priv=None`/locked; `identity_pub` cert-fallback; `sign_*` raise when locked; a `locked_from_json` builder; `secret_fields`/`nonsecret_fields` split helpers)
- Modify: `hearth/node.py` (`__init__` boot-locked; `enable_applock`/`unlock`/`lock`; `_save_keys` routing; `_applock_path`)
- Test: `tests/test_applock_node.py`

**Interfaces:**
- Consumes: `applock.enable/unlock`, `dpapi_seal/unseal`.
- Produces: `node.locked` (bool), `node.enable_applock(credential, cred_type)`, `node.unlock(credential)`, `node.lock()`, `node.applock_enabled` (bool); `DeviceKeys` locked construction.

- [ ] **Step 1: Failing tests** — `tests/test_applock_node.py` (use the real DPAPI on Windows):

```python
import json, pytest
from hearth.node import HearthNode

def _node(tmp_path):
    return HearthNode.create(tmp_path / "n", "Wong", "wong-phone")

def test_enable_strips_plaintext_secrets(tmp_path):
    n = _node(tmp_path)
    n.enable_applock("1234", "pin")
    raw = json.loads((n.data_dir / "keys.json").read_text())
    assert raw.get("applock") is True
    for f in ("device_priv", "identity_priv", "enc_priv", "storage_key"):
        assert not raw.get(f)                      # no plaintext secret on disk
    assert (n.data_dir / "applock.json").exists()

def test_boot_locked_and_unlock(tmp_path):
    n = _node(tmp_path); ident = n.identity_pub
    n.enable_applock("1234", "pin")
    n2 = HearthNode(n.data_dir)                    # reboot
    assert n2.locked is True
    with pytest.raises(Exception):                 # can't sign while locked
        n2.compose_post("hi", scope="kreds")
    n2.unlock("1234")
    assert n2.locked is False and n2.identity_pub == ident
    mid = n2.compose_post("hi", scope="kreds")     # works after unlock
    assert mid

def test_wrong_credential_stays_locked(tmp_path):
    n = _node(tmp_path); n.enable_applock("1234", "pin")
    n2 = HearthNode(n.data_dir)
    with pytest.raises(Exception):
        n2.unlock("0000")
    assert n2.locked is True

def test_lock_drops_keys(tmp_path):
    n = _node(tmp_path); n.enable_applock("1234", "pin"); n.unlock("1234")
    a = n.compose_post("a", scope="kreds", placement="profile")
    n.lock()
    assert n.locked is True
    with pytest.raises(Exception):
        n.compose_post("b", scope="kreds")

def test_enc_rotation_persists_while_unlocked(tmp_path):
    n = _node(tmp_path); n.enable_applock("1234", "pin"); n.unlock("1234")
    n.device.rotate_enc(); n._save_keys()          # rotation mutates secret bundle
    n2 = HearthNode(n.data_dir); n2.unlock("1234")
    assert len(n2.device.retired_enc) == len(n.device.retired_enc)

def test_no_applock_path_unchanged(tmp_path):
    n = _node(tmp_path)
    assert n.locked is False and n.applock_enabled is False
    assert n.compose_post("x", scope="kreds")      # normal node still works
```

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: `DeviceKeys` changes (`identity.py`):**
  - `__init__`: allow `device_priv=None`; add optional `device_pub: str = None`; set `self.device_pub = pub_hex(device_priv.public_key()) if device_priv is not None else device_pub`.
  - `identity_pub` property: `if self._identity_priv is not None: return pub_hex(...)` else `return self.cert.identity_pub if self.cert else None` (cert-fallback so a locked node knows its own public identity). **Verify revocation tests still pass** (revoked node has cert but no priv; identity_pub now returns the cert value — guards use `self.revoked`, so confirm nothing asserts revoked→identity_pub None; if one does, adjust the test's intent, not the security property).
  - `sign_message`/`sign_raw`: `if self._device_priv is None: raise RuntimeError("locked")`.
  - Add `SECRET_FIELDS = ("device_priv", "identity_priv", "enc_priv", "retired_enc", "storage_key")`. Add `@staticmethod locked_from_json(nonsecret: dict)` → build a locked `DeviceKeys` (device_priv=None, device_pub from `nonsecret["device_pub"]`, cert, enc_pub, seq, all privs None).
  - `to_json` already emits everything; add `"device_pub": self.device_pub` to it (needed for the non-secret split + locked boot).

- [ ] **Step 4: node.py changes:**
  - `_applock_path` = `self.data_dir / "applock.json"`; `self.applock_enabled = _applock_path.exists()`; `self._applock_master = None` (held while unlocked to re-persist secrets).
  - `__init__`: if `self.applock_enabled`: `raw = json.loads(keys.json)`; `self.device = DeviceKeys.locked_from_json(raw)`; `self.locked = True`; skip the revoked/legacy-storage-key branches while locked. Else current path + `self.locked = False`.
  - `enable_applock(credential, cred_type)`: (requires unlocked/keys present) `full = self.device.to_json()`; `secrets = {k: full[k] for k in DeviceKeys.SECRET_FIELDS}`; `rec = applock.enable(secrets, credential, cred_type, applock.dpapi_seal)`; write `applock.json` = rec; rewrite `keys.json` = non-secret subset + `{"applock": true}`; set `applock_enabled=True`, hold `self._applock_master`? (enable can return the master or re-derive lazily; simplest: after enable, we're still unlocked with the live `self.device` — set `_applock_master` by re-deriving on next save, or store it from enable — extend `applock.enable` to also return the master for the session. Simplest: keep a private `self._applock_credential`? NO — don't hold the credential. Instead, hold the master: have `enable`/`unlock` also return the master bytes for the session, stored in `self._applock_master`.)
  - **`applock.enable` returns `(record, master)` and `applock.unlock` returns `(secrets, master)`** (the master comes ONLY from an AEAD-verified unlock — `session_master` was removed as an unauthenticated footgun). The node keeps that `master` in `self._applock_master` while unlocked (NEVER the credential); `_save_keys` uses `applock.reencrypt(record, secrets, master)` to persist secret-bundle changes (e.g. enc-key rotation). So `enable_applock` unpacks `(rec, master)` and `unlock` unpacks `(secrets, master)`.
  - `unlock(credential)`: `rec = json.load(applock.json)`; `secrets, master = applock.unlock(rec, credential, applock.dpapi_unseal)`; `merged = {**nonsecret_keys_json, **secrets}`; `self.device = DeviceKeys.from_json(merged)`; `self._applock_master = master`; `self.locked = False`; `self._touch()`. Re-run the deferred boot checks (revoked view, etc.) now that keys are present.
  - `lock()`: clear `self.device` private material (rebuild as `locked_from_json`, or null the private attrs) + `self._applock_master = None` + `self.locked = True`.
  - `_save_keys()`: if `applock_enabled` and not locked → split: write non-secret subset to keys.json (+applock flag), and re-encrypt the secret subset into applock.json using `self._applock_master` (a new `applock.reencrypt(record, secrets, master)` that keeps salt/sealed-secret, re-encrypts ct with a fresh nonce). If applock_enabled and locked → raise (can't persist secret changes while locked — shouldn't happen). Else (no applock) → current behavior.

- [ ] **Step 5: Run tests + full suite (revocation included). Commit.**

```powershell
git add hearth/identity.py hearth/node.py tests/test_applock_node.py
git commit -m "feat: node boot-locked + enable/unlock/lock, keys.json/applock.json secret split, DeviceKeys locked support"
```

---

### Task 3: API — locked guard + endpoints + throttle + auto-lock

**Files:**
- Modify: `hearth/api.py` (locked-guard dependency; unlock/lock/applock endpoints; throttle)
- Modify: `hearth/node.py` (`last_activity` + `maybe_autolock()`; wire the auto-lock check into the existing periodic loop)
- Test: `tests/test_applock_api.py`

- [ ] **Step 1: Failing tests** — locked node: `GET /api/applock` 200 (`{enabled, locked}`); a content route (`/api/state` or `/api/profile/{id}`) → **423** while locked; `POST /api/unlock {credential}` → 200 + subsequently unlocked; wrong credential → 401/403 with a `throttle_wait` that increases on repeat; `POST /api/lock` → locked again; setup on an unlocked fresh node; a non-applock node → all routes normal. (Use `TestClient`.)

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: Implement.**
  - **Locked guard:** a FastAPI dependency (or middleware) that, when `node.locked`, returns `JSONResponse(status_code=423, ...)` for all `/api/*` except an allowlist (`/api/unlock`, `/api/applock`). Prefer middleware keyed on `request.url.path` to avoid editing every route.
  - **Endpoints:** `GET /api/applock` → `{enabled, locked, cred_type, settings, throttle_wait}`; `POST /api/unlock {credential}` (throttle check first → `node.unlock` → on `BadCredential` record a fail + return 401 with `throttle_wait`); `POST /api/lock`; `POST /api/applock/setup {credential, cred_type}`; `POST /api/applock/settings {idle_minutes, lock_on_sleep}` (persist into `applock.json`'s `settings`); `POST /api/applock/change {old, new}`; `POST /api/applock/disable {credential}` (verify → restore plaintext keys.json → delete applock.json).
  - **Throttle:** in-memory on the node/app: `fail_count`, `next_allowed_ts`; escalate (≥3 → 5s, ≥5 → 30s, ≥8 → 300s); a successful unlock resets it; `throttle_wait = max(0, next_allowed_ts - now)`.
  - **Auto-lock:** `node._touch()` sets `last_activity = time.time()` (call it from the locked-guard middleware on any allowed authenticated request, or from `/api/state`); `node.maybe_autolock()` — if unlocked + `idle_minutes>0` and `now-last_activity > idle_minutes*60` → `lock()`; if `lock_on_sleep` and the periodic loop sees a wall-clock jump (track `_last_tick`; if `now-_last_tick > interval + 30s` → suspended → `lock()`). Call `maybe_autolock()` from the existing gossip/periodic loop tick. (`time.time()` is real here — this is runtime, not a workflow script.)

- [ ] **Step 4: Run tests + full suite. Commit.**

```powershell
git add hearth/api.py hearth/node.py tests/test_applock_api.py
git commit -m "feat: applock API - 423 locked-guard, unlock/lock/setup/settings/change/disable, throttle, node-side idle+sleep auto-lock"
```

---

### Task 4: Client — lock screen + settings UI

**Files:**
- Modify: `hearth/web/app.js`, `hearth/web/index.html`, `hearth/web/style.css`
- Test: `tests/test_web_assets.py`

- [ ] **Step 1: Failing asset test** — `#lock-screen` markup present; `app.js` has an applock-status check on load, a `423`-triggered lock, an unlock POST, a "Lock now" control, and a settings section (enable/idle-select/sleep-toggle/change/disable); `node --check`.

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: Implement.**
  - On load: `GET /api/applock`; if `locked`, render a full-screen `#lock-screen` gating the app — a PIN keypad (numeric) or passphrase field per `cred_type`; submit → `POST /api/unlock`; on failure show the `throttle_wait` countdown. Wrap the app's normal boot so nothing else renders while locked. Any `fetch` getting `423` → show the lock screen (helper around fetch, or a check on key calls).
  - **Settings section** (in the Me/profile settings area, self-only): "Enable App-lock" (pick PIN/passphrase + confirm → `/api/applock/setup`), idle-timeout `<select>` (Off/5/10/15 → `/api/applock/settings`), lock-on-sleep checkbox, "Change credential", "Disable" (asks current credential), and a **"Lock now"** button (`POST /api/lock` → show lock screen).
  - **Client hooks:** on `visibilitychange`→hidden, and on a detected wall-clock gap (a `setInterval` heartbeat noticing a jump), re-`GET /api/applock` and show the lock screen if now locked.
  - Keyboard-accessible (focus the PIN/passphrase field on show; Enter submits).

- [ ] **Step 4: Run asset tests + node --check + full suite. Commit.**

```powershell
git add hearth/web/app.js hearth/web/index.html hearth/web/style.css tests/test_web_assets.py
git commit -m "feat: app-lock client - lock screen (PIN/passphrase), settings (enable/idle/sleep/change/disable/lock-now)"
```

---

### Task 5: Integration + docs

**Files:**
- Test: `tests/test_applock_integration.py`
- Modify: `README.md`, `ROADMAP.md`

- [ ] **Step 1: Integration test** — full lifecycle on a real node dir: create → enable_applock → reboot (`HearthNode(dir)`) boots locked → content API 423 → unlock → API 200 → post a profile block → lock() → 423 again → unlock → the block is still there (storage_key survived the round-trip so cached content keys still open). Assert `keys.json` never contains a plaintext secret after enable. Terminates fast.

- [ ] **Step 2: Full suite + node --check** — all pass (run twice). Confirm the non-applock demo path + revocation tests are unaffected.

- [ ] **Step 3: README + ROADMAP** — document App-lock: PIN/passphrase, DPAPI-sealed-device-secret + scrypt at-rest model, boot-locked node + 423 guard, node-tracked idle/sleep/manual auto-lock, no auto-wipe, throttle. State the **honest threat model** (protects idle/asleep/stolen-off + stolen-file-without-Windows-login; does NOT protect malware-as-you-while-unlocked; OS-suspend detection is heuristic until the packaged wrapper; off-Windows fallback is weaker). Note deferred: pattern credential, biometric, true OS-suspend hooks, cross-device settings sync. Move App-lock from "Near-term" to shipped in the feature list.

- [ ] **Step 4: Commit**

```powershell
git add tests/test_applock_integration.py README.md ROADMAP.md
git commit -m "test+docs: app-lock integration + honest threat-model writeup"
```

---

## Completion

After Task 5: whole-branch review (superpowers:requesting-code-review) — focus: **crypto** (master needs BOTH credential + DPAPI-sealed device secret; wrong credential → BadCredential via AEAD tag; scrypt params sane; no plaintext secret on disk when enabled; nonce fresh per re-encrypt); **boot-locked** genuinely prevents sign/decrypt (private material absent, not just a flag); **423 guard** allowlist can't be bypassed to a content route; **throttle** escalates + resets on success; **auto-lock** idle + wall-clock-jump correct; **no-wipe**; the **non-applock path + revocation** are unbroken (identity_pub cert-fallback didn't regress revocation); enc-key rotation re-persists into applock.json while unlocked (forward secrecy intact); DPAPI per-user residual documented; honesty guard. Then superpowers:finishing-a-development-branch — merge to `main`, push. Client lock-screen/settings behavior is the USER's to verify (hand a checklist on merge). Next: in-app video trimmer.
