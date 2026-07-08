import pytest
from hearth import update
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization as _s

def _throwaway_key():
    k = Ed25519PrivateKey.generate()
    priv = k.private_bytes(_s.Encoding.Raw, _s.PrivateFormat.Raw, _s.NoEncryption()).hex()
    pub = k.public_key().public_bytes(_s.Encoding.Raw, _s.PublicFormat.Raw).hex()
    return priv, pub

def test_sign_verify_roundtrip():
    priv, pub = _throwaway_key()
    m = b'{"version":"0.3.0"}'
    sig = update.sign_manifest(m, priv)
    assert update.verify_manifest(m, sig, pub) is True

def test_tampered_manifest_fails():
    priv, pub = _throwaway_key()
    sig = update.sign_manifest(b'{"version":"0.3.0"}', priv)
    assert update.verify_manifest(b'{"version":"0.4.0"}', sig, pub) is False

def test_wrong_key_fails():
    priv, _ = _throwaway_key()
    _, other_pub = _throwaway_key()
    m = b'{"version":"0.3.0"}'
    assert update.verify_manifest(m, update.sign_manifest(m, priv), other_pub) is False

def test_release_pubkey_present_and_no_private_committed():
    assert isinstance(update.RELEASE_PUBKEY, str) and len(update.RELEASE_PUBKEY) == 64
    import pathlib, subprocess
    tracked = subprocess.run(["git", "ls-files"], capture_output=True, text=True,
                             cwd=pathlib.Path(update.__file__).parents[1]).stdout
    assert "release_private_key" not in tracked         # private key never committed

def test_version_lt():
    assert update.version_lt("0.2.0", "0.3.0") and not update.version_lt("0.3.0", "0.3.0")

def test_build_web_bundle(tmp_path):
    (tmp_path / "index.html").write_text("hi")
    data, digest = update.build_web_bundle(tmp_path)
    import hashlib, zipfile, io
    assert hashlib.sha256(data).hexdigest() == digest
    assert "index.html" in zipfile.ZipFile(io.BytesIO(data)).namelist()
