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
    rec, _ = applock.enable(SECRETS, "1234", "pin", seal)
    secrets, _ = applock.unlock(rec, "1234", unseal)
    assert secrets == SECRETS

def test_wrong_credential_fails():
    seal, unseal = _seal_pair()
    rec, _ = applock.enable(SECRETS, "1234", "pin", seal)
    with pytest.raises(applock.BadCredential):
        applock.unlock(rec, "9999", unseal)

def test_tampered_ct_fails():
    seal, unseal = _seal_pair()
    rec, _ = applock.enable(SECRETS, "1234", "pin", seal)
    rec = {**rec, "ct_hex": ("00" + rec["ct_hex"][2:])}
    with pytest.raises(applock.BadCredential):
        applock.unlock(rec, "1234", unseal)

def test_needs_device_secret():
    seal, unseal = _seal_pair()
    rec, _ = applock.enable(SECRETS, "1234", "pin", seal)
    bad_unseal = lambda b: b"\x00"*32          # wrong device secret
    with pytest.raises(applock.BadCredential):
        applock.unlock(rec, "1234", bad_unseal)

def test_record_has_no_plaintext_secret():
    seal, unseal = _seal_pair()
    rec, _ = applock.enable(SECRETS, "1234", "pin", seal)
    blob = repr(rec)
    for v in ("aa"*32, "bb"*32, "cc"*32, "dd"*32):
        assert v not in blob

def test_change_credential():
    seal, unseal = _seal_pair()
    rec, _ = applock.enable(SECRETS, "1234", "pin", seal)
    rec2, master2 = applock.change_credential(rec, "1234", "5678", unseal, seal)
    secrets, unlock_master2 = applock.unlock(rec2, "5678", unseal)
    assert secrets == SECRETS
    # The returned master must be usable directly (e.g. for reencrypt())
    # without a second unlock() -- this IS the fix for IMPORTANT #4's
    # stale-master window, so pin it explicitly.
    assert master2 == unlock_master2
    with pytest.raises(applock.BadCredential):
        applock.unlock(rec2, "1234", unseal)

def test_malformed_ct_hex_is_not_bad_credential():
    """A corrupted/truncated record (bad hex) must NOT be reported as a wrong
    credential -- unlock's except clause is narrowed to InvalidTag only, so
    hex-parsing errors on a malformed record propagate as ValueError."""
    seal, unseal = _seal_pair()
    rec, _ = applock.enable(SECRETS, "1234", "pin", seal)
    rec = {**rec, "ct_hex": rec["ct_hex"][:-1]}          # odd-length hex string
    with pytest.raises(ValueError):
        applock.unlock(rec, "1234", unseal)

def test_scrypt_params_read_from_record_not_module_constants():
    """The scrypt params stamped into the record at enable() time must be the
    ones used to re-derive the master at unlock() time -- bumping the module
    constants afterward must not break unlocking an existing record."""
    seal, unseal = _seal_pair()
    rec, _ = applock.enable(SECRETS, "1234", "pin", seal)
    assert rec["kdf"]["n"] == applock.SCRYPT_N
    applock.SCRYPT_N = applock.SCRYPT_N * 2      # simulate bumping the module default
    try:
        secrets, _ = applock.unlock(rec, "1234", unseal)
        assert secrets == SECRETS
    finally:
        applock.SCRYPT_N = applock.SCRYPT_N // 2

def test_unlock_returns_same_master_as_enable():
    """unlock's returned master must equal enable's returned master for the
    same (credential, device secret) pair -- this is now the only sanctioned
    way to obtain a holdable master, since session_master is gone."""
    seal, unseal = _seal_pair()
    rec, enable_master = applock.enable(SECRETS, "1234", "pin", seal)
    _, unlock_master = applock.unlock(rec, "1234", unseal)
    assert enable_master == unlock_master

@pytest.mark.skipif(not applock.DPAPI_AVAILABLE, reason="Windows only")
def test_dpapi_roundtrip():
    blob = applock.dpapi_seal(b"secret-device-key-material-32byte")
    assert blob != b"secret-device-key-material-32byte"
    assert applock.dpapi_unseal(blob) == b"secret-device-key-material-32byte"


# --- additional tests: authenticated master + reencrypt (beyond brief's list) -
#
# session_master() was removed: it derived *a* master for any credential with
# no AEAD verification, so a caller invoking it without a prior successful
# unlock() could reencrypt under a wrong master and corrupt the record. The
# master now comes ONLY from enable()'s or unlock()'s return value, both of
# which have already gone through the AEAD-authenticated derivation.

def test_reencrypt_roundtrip_with_enable_master():
    seal, unseal = _seal_pair()
    rec, master = applock.enable(SECRETS, "1234", "pin", seal)

    new_secrets = {**SECRETS, "storage_key": "ee"*32}
    rec2 = applock.reencrypt(rec, new_secrets, master)

    secrets, _ = applock.unlock(rec2, "1234", unseal)
    assert secrets == new_secrets

def test_reencrypt_changes_ciphertext_and_nonce_keeps_device_secret():
    seal, unseal = _seal_pair()
    rec, master = applock.enable(SECRETS, "1234", "pin", seal)

    rec2 = applock.reencrypt(rec, SECRETS, master)

    assert rec2["nonce_hex"] != rec["nonce_hex"]
    assert rec2["ct_hex"] != rec["ct_hex"]
    # same sealed device secret + salt (only ct/nonce rotate)
    assert rec2["sealed_device_secret_hex"] == rec["sealed_device_secret_hex"]
    assert rec2["kdf"]["salt_hex"] == rec["kdf"]["salt_hex"]


# -- minor #12: a tampered record cannot OOM Scrypt.derive() ------------------

def test_tampered_huge_n_rejected_before_scrypt_runs():
    seal, unseal = _seal_pair()
    rec, _ = applock.enable(SECRETS, "1234", "pin", seal)
    rec["kdf"]["n"] = applock.MAX_SCRYPT_N + 1
    with pytest.raises(applock.BadCredential):
        applock.unlock(rec, "1234", unseal)


def test_n_at_the_ceiling_still_works():
    seal, unseal = _seal_pair()
    rec, _ = applock.enable(SECRETS, "1234", "pin", seal)
    assert rec["kdf"]["n"] <= applock.MAX_SCRYPT_N   # sanity: default is in range


# -- minor B: the clamp covers r and p too, not just n ------------------------

def test_tampered_huge_r_rejected_before_scrypt_runs():
    """scrypt's working set is ~128*n*r bytes -- clamping n alone still lets
    a tampered r OOM Scrypt.derive() on its own."""
    seal, unseal = _seal_pair()
    rec, _ = applock.enable(SECRETS, "1234", "pin", seal)
    rec["kdf"]["r"] = applock.MAX_SCRYPT_R + 1
    with pytest.raises(applock.BadCredential):
        applock.unlock(rec, "1234", unseal)


def test_tampered_huge_p_rejected_before_scrypt_runs():
    seal, unseal = _seal_pair()
    rec, _ = applock.enable(SECRETS, "1234", "pin", seal)
    rec["kdf"]["p"] = applock.MAX_SCRYPT_P + 1
    with pytest.raises(applock.BadCredential):
        applock.unlock(rec, "1234", unseal)


def test_r_and_p_at_the_ceiling_still_work():
    seal, unseal = _seal_pair()
    rec, _ = applock.enable(SECRETS, "1234", "pin", seal)
    assert rec["kdf"]["r"] <= applock.MAX_SCRYPT_R   # sanity: default is in range
    assert rec["kdf"]["p"] <= applock.MAX_SCRYPT_P
    secrets, _ = applock.unlock(rec, "1234", unseal)   # still unlocks fine
    assert secrets == SECRETS


def test_scrypt_r_p_read_from_record_not_module_constants():
    """Mirrors test_scrypt_params_read_from_record_not_module_constants for
    n: the clamp must not have become a hardcoded r/p -- bumping the
    module defaults after enable() must not break unlocking an existing
    record, since unlock() derives using the record's own r/p."""
    seal, unseal = _seal_pair()
    rec, _ = applock.enable(SECRETS, "1234", "pin", seal)
    assert rec["kdf"]["r"] == applock.SCRYPT_R
    assert rec["kdf"]["p"] == applock.SCRYPT_P
    applock.SCRYPT_R, applock.SCRYPT_P = applock.SCRYPT_R * 2, applock.SCRYPT_P + 1
    try:
        secrets, _ = applock.unlock(rec, "1234", unseal)
        assert secrets == SECRETS
    finally:
        applock.SCRYPT_R, applock.SCRYPT_P = applock.SCRYPT_R // 2, applock.SCRYPT_P - 1
