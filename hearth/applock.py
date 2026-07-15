"""App-lock crypto: encrypt the node's secret key material at rest under a
credential (PIN/passphrase) COMBINED with a Windows-DPAPI-sealed random device
secret, so a short credential is still strong at rest (the device carries the
entropy). No new dependency: cryptography's Scrypt/HKDF/ChaCha20Poly1305 + ctypes DPAPI."""
import ctypes, json, os, sys
from ctypes import wintypes
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.hashes import SHA256
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

SCRYPT_N, SCRYPT_R, SCRYPT_P = 2**15, 8, 1
MAX_SCRYPT_N = 2**20   # a tampered/corrupted record must not be able to make
                       # Scrypt.derive() allocate an unbounded amount of
                       # memory (Scrypt's working set is ~128*n*r bytes) --
                       # whole-branch review, minor #12
MAX_SCRYPT_R = 32      # r multiplies that same ~128*n*r working set -- a
                       # tampered r alone (n left at its normal, in-range
                       # default) still OOMs unless r is clamped too
                       # (whole-branch review, minor B)
MAX_SCRYPT_P = 16      # p only affects parallelism/CPU cost, not memory
                       # per lane, but a huge p is still not anything a
                       # legitimate record needs -- bounded for the same
                       # "reject the tamper before it reaches Scrypt.derive()"
                       # reasoning (whole-branch review, minor B)
DPAPI_AVAILABLE = sys.platform == "win32"
CRYPTPROTECT_UI_FORBIDDEN = 0x1

class BadCredential(Exception):
    pass

# --- Windows DPAPI (per-user) via ctypes -------------------------------------
class _BLOB(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]

if DPAPI_AVAILABLE:
    ctypes.windll.crypt32.CryptProtectData.argtypes = [
        ctypes.POINTER(_BLOB), wintypes.LPCWSTR, ctypes.POINTER(_BLOB),
        ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(_BLOB)]
    ctypes.windll.crypt32.CryptProtectData.restype = wintypes.BOOL
    ctypes.windll.crypt32.CryptUnprotectData.argtypes = [
        ctypes.POINTER(_BLOB), ctypes.POINTER(ctypes.c_wchar_p), ctypes.POINTER(_BLOB),
        ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(_BLOB)]
    ctypes.windll.crypt32.CryptUnprotectData.restype = wintypes.BOOL
    ctypes.windll.kernel32.LocalFree.argtypes = [ctypes.c_void_p]
    ctypes.windll.kernel32.LocalFree.restype = ctypes.c_void_p

def _in_blob(data: bytes) -> _BLOB:
    buf = ctypes.create_string_buffer(data, len(data))
    return _BLOB(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))

def dpapi_seal(data: bytes) -> bytes:
    out = _BLOB()
    if not ctypes.windll.crypt32.CryptProtectData(
            ctypes.byref(_in_blob(data)), u"hearth-applock", None, None, None,
            CRYPTPROTECT_UI_FORBIDDEN, ctypes.byref(out)):
        raise OSError("CryptProtectData failed")
    res = ctypes.string_at(out.pbData, out.cbData)
    ctypes.windll.kernel32.LocalFree(out.pbData)
    return res

def dpapi_unseal(blob: bytes) -> bytes:
    out = _BLOB()
    if not ctypes.windll.crypt32.CryptUnprotectData(
            ctypes.byref(_in_blob(blob)), None, None, None, None,
            CRYPTPROTECT_UI_FORBIDDEN, ctypes.byref(out)):
        raise OSError("CryptUnprotectData failed")
    res = ctypes.string_at(out.pbData, out.cbData)
    ctypes.windll.kernel32.LocalFree(out.pbData)
    return res

# --- key derivation + record -------------------------------------------------
def _master(credential: str, salt: bytes, device_secret: bytes, n: int, r: int, p: int) -> bytes:
    pw = Scrypt(salt=salt, length=32, n=n, r=r, p=p).derive(
        credential.encode("utf-8"))
    return HKDF(algorithm=SHA256(), length=32, salt=device_secret,
                info=b"hearth-applock-v1").derive(pw)

def enable(secrets: dict, credential: str, cred_type: str, seal) -> tuple[dict, bytes]:
    device_secret = os.urandom(32)
    salt = os.urandom(16)
    master = _master(credential, salt, device_secret, SCRYPT_N, SCRYPT_R, SCRYPT_P)
    nonce = os.urandom(12)
    ct = ChaCha20Poly1305(master).encrypt(
        nonce, json.dumps(secrets).encode("utf-8"), b"hearth-applock")
    record = {
        "version": 1, "cred_type": cred_type,
        "kdf": {"name": "scrypt", "n": SCRYPT_N, "r": SCRYPT_R, "p": SCRYPT_P,
                "salt_hex": salt.hex()},
        "sealed_device_secret_hex": seal(device_secret).hex(),
        "nonce_hex": nonce.hex(), "ct_hex": ct.hex(),
        "settings": {"idle_minutes": 0, "lock_on_sleep": False},
        "settings_v": 2,
    }
    return record, master

def migrate_settings(record: dict) -> tuple[dict, bool]:
    """One-time 0.3.11 migration: lock_on_sleep shipped default-ON and
    nobody chose it; flip unmarked records to OFF. settings_v marks the
    record so a user who re-enables it afterwards keeps their choice."""
    if record.get("settings_v", 1) >= 2:
        return record, False
    settings = record.setdefault("settings",
                                 {"idle_minutes": 0, "lock_on_sleep": False})
    settings["lock_on_sleep"] = False
    record["settings_v"] = 2
    return record, True

def unlock(record: dict, credential: str, unseal) -> tuple[dict, bytes]:
    device_secret = unseal(bytes.fromhex(record["sealed_device_secret_hex"]))
    salt = bytes.fromhex(record["kdf"]["salt_hex"])
    k = record["kdf"]
    if (k.get("n", 0) > MAX_SCRYPT_N or k.get("r", 0) > MAX_SCRYPT_R
            or k.get("p", 0) > MAX_SCRYPT_P):
        # Fails the same way a wrong credential does (BadCredential, not a
        # 500) -- tampered/oversized kdf params are indistinguishable from
        # "this record cannot be unlocked" from the caller's point of
        # view, and must never reach Scrypt.derive(). Params are still
        # read from the record (not hardcoded) -- only bounded, so a
        # legitimate record's own n/r/p keep working unchanged.
        raise BadCredential("kdf parameters out of range")
    master = _master(credential, salt, device_secret, k["n"], k["r"], k["p"])
    try:
        pt = ChaCha20Poly1305(master).decrypt(
            bytes.fromhex(record["nonce_hex"]),
            bytes.fromhex(record["ct_hex"]), b"hearth-applock")
    except InvalidTag:
        raise BadCredential("wrong credential")
    return json.loads(pt), master

def reencrypt(record: dict, secrets: dict, master: bytes) -> dict:
    """Re-seal the secret bundle under the SAME device secret + master (fresh
    nonce). Used by _save_keys when a rotation mutates secrets while unlocked.
    `master` must come from a prior successful enable()/unlock() call -- there
    is no standalone way to derive it without passing the AEAD check."""
    nonce = os.urandom(12)
    ct = ChaCha20Poly1305(master).encrypt(
        nonce, json.dumps(secrets).encode("utf-8"), b"hearth-applock")
    return {**record, "nonce_hex": nonce.hex(), "ct_hex": ct.hex()}

def change_credential(record: dict, old: str, new: str, unseal,
                      seal) -> tuple[dict, bytes]:
    """Returns (new_record, new_master) -- the master comes from THIS same
    enable() call, so a caller never needs a second unlock() just to
    re-derive it (whole-branch review, IMPORTANT #4: that second
    derivation could raise after the new record was already persisted,
    leaving a caller holding a master that no longer matches its own
    on-disk record)."""
    secrets, _ = unlock(record, old, unseal)              # verifies old
    rec, master = enable(secrets, new, record["cred_type"], seal)
    rec["settings"] = record.get("settings", rec["settings"])   # keep settings
    return rec, master
