"""hearth/coreupdate.py -- the on-restart core-swap updater (Task 3 of the
Kreds Windows packaging plan). Pure temp-dir logic, no real relaunch: builds
a signed pending-core bundle (manifest+sig+zip) with a THROWAWAY Ed25519
key, exactly like feature-16's own update.py tests, then exercises
apply_staged_core's re-verify-before-swap contract.

SECURITY-CRITICAL surface: a bad signature or a tampered/mismatched sha256
must leave `current` untouched and extract nothing -- apply_staged_core is
the last gate before a staged bundle would ever replace what runs on the
next launch."""
import base64
import hashlib
import importlib.util
import io
import json
import zipfile
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import serialization as _s
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from hearth import coreupdate, update


def _key():
    k = Ed25519PrivateKey.generate()
    priv = k.private_bytes(_s.Encoding.Raw, _s.PrivateFormat.Raw, _s.NoEncryption()).hex()
    pub = k.public_key().public_bytes(_s.Encoding.Raw, _s.PublicFormat.Raw).hex()
    return priv, pub


def _core_zip_bytes(marker_text: str = "payload") -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("Kreds.exe", b"fake payload exe: " + marker_text.encode())
        z.writestr("_internal/marker.txt", marker_text)
    return buf.getvalue()


def _stage(staging_dir, version="9.9.9", zip_bytes=None, priv=None, pub=None,
          sign_with_priv=None, include_sig=True, sha256_override=None):
    """Stage a pending-core.zip + pending-core.json exactly like
    hearth.update.stage_core does when `info` came from check() (i.e. the
    marker carries manifest_b64 + sig_b64, not just a bare version)."""
    if priv is None or pub is None:
        priv, pub = _key()
    if zip_bytes is None:
        zip_bytes = _core_zip_bytes(version)
    digest = hashlib.sha256(zip_bytes).hexdigest()
    manifest = {"version": version, "channel": "stable", "core_version": version,
                "min_core_for_web": "0.0.0", "notes": "test",
                "core": {"sha256": digest, "size": len(zip_bytes)}}
    mbytes = json.dumps(manifest).encode()
    signer_priv = sign_with_priv if sign_with_priv is not None else priv
    sig = update.sign_manifest(mbytes, signer_priv)

    staging_dir.mkdir(parents=True, exist_ok=True)
    (staging_dir / "pending-core.zip").write_bytes(zip_bytes)
    marker = {"version": version, "sha256": sha256_override or digest}
    if include_sig:
        marker["manifest_b64"] = base64.b64encode(mbytes).decode()
        marker["sig_b64"] = base64.b64encode(sig).decode()
    (staging_dir / "pending-core.json").write_text(json.dumps(marker))
    return pub


def test_apply_staged_core_swaps_and_flips_current(tmp_path, monkeypatch):
    install_root = tmp_path / "install"
    staging_dir = tmp_path / "staging"
    install_root.mkdir()
    # a prior version is already "installed" and current
    (install_root / "versions" / "0.1.0").mkdir(parents=True)
    (install_root / "versions" / "0.1.0" / "Kreds.exe").write_bytes(b"old exe")
    (install_root / "current").write_text("0.1.0")

    pub = _stage(staging_dir, version="0.2.0")
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)

    result = coreupdate.apply_staged_core(install_root, staging_dir)

    assert result == "0.2.0"
    assert (install_root / "versions" / "0.2.0" / "Kreds.exe").exists()
    assert (install_root / "versions" / "0.2.0" / "_internal" / "marker.txt").exists()
    assert (install_root / "current").read_text().strip() == "0.2.0"
    assert (install_root / "previous").read_text().strip() == "0.1.0"
    # prior version's files are untouched (never overwritten)
    assert (install_root / "versions" / "0.1.0" / "Kreds.exe").read_bytes() == b"old exe"
    # staging cleared
    assert not (staging_dir / "pending-core.zip").exists()
    assert not (staging_dir / "pending-core.json").exists()


def test_apply_staged_core_rejects_bad_sig(tmp_path, monkeypatch):
    install_root = tmp_path / "install"
    staging_dir = tmp_path / "staging"
    install_root.mkdir()
    (install_root / "current").write_text("0.1.0")

    other_priv, _ = _key()
    pub = _stage(staging_dir, version="0.2.0", sign_with_priv=other_priv)  # signed with the WRONG key
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)

    result = coreupdate.apply_staged_core(install_root, staging_dir)

    assert result is None
    assert (install_root / "current").read_text().strip() == "0.1.0"     # unchanged
    assert not (install_root / "versions" / "0.2.0").exists()             # nothing extracted


def test_apply_staged_core_rejects_marker_manifest_sha256_mismatch(tmp_path, monkeypatch):
    """Renamed from the mis-named "rejects_bad_hash": what this actually
    exercises is the marker's "sha256" field disagreeing with the sha256
    embedded in the (validly-signed) manifest -- caught by the
    manifest/marker sha256 cross-check, before the on-disk-zip-bytes check
    even runs (see test_apply_staged_core_rejects_zip_bytes_swapped_after_staging
    below for that separate case)."""
    install_root = tmp_path / "install"
    staging_dir = tmp_path / "staging"
    install_root.mkdir()
    (install_root / "current").write_text("0.1.0")

    # marker's pinned sha256 doesn't match the actual pending-core.zip bytes
    pub = _stage(staging_dir, version="0.2.0", sha256_override="0" * 64)
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)

    result = coreupdate.apply_staged_core(install_root, staging_dir)

    assert result is None
    assert (install_root / "current").read_text().strip() == "0.1.0"
    assert not (install_root / "versions" / "0.2.0").exists()


def test_apply_staged_core_rejects_zip_bytes_swapped_after_staging(tmp_path, monkeypatch):
    """Marker and manifest sha256 agree with EACH OTHER (both point at the
    legitimately-staged zip's digest) but the actual pending-core.zip
    bytes on disk were swapped out after staging (e.g. a compromised or
    racy staging dir) -- the final actual-bytes-vs-pinned-hash check must
    still catch this even though every marker/manifest cross-check above
    it passes."""
    install_root = tmp_path / "install"
    staging_dir = tmp_path / "staging"
    install_root.mkdir()
    (install_root / "current").write_text("0.1.0")

    pub = _stage(staging_dir, version="0.2.0")  # legit staging, all fields agree
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)

    # Tamper with the zip bytes on disk AFTER staging, without touching
    # pending-core.json at all -- marker and manifest still both name the
    # ORIGINAL (now-stale) digest.
    (staging_dir / "pending-core.zip").write_bytes(_core_zip_bytes("swapped-in-place"))

    result = coreupdate.apply_staged_core(install_root, staging_dir)

    assert result is None
    assert (install_root / "current").read_text().strip() == "0.1.0"
    assert not (install_root / "versions" / "0.2.0").exists()


def test_apply_staged_core_rejects_tampered_marker_hash_even_with_valid_sig(tmp_path, monkeypatch):
    """A staged marker whose "sha256" field was rewritten to match a
    DIFFERENT (malicious) zip, while manifest_b64/sig_b64 still carry the
    ORIGINAL validly-signed manifest, must be rejected: the marker's own
    "sha256" copy is not itself trusted -- it must match the sha256 that is
    actually embedded inside the verified, signed manifest."""
    install_root = tmp_path / "install"
    staging_dir = tmp_path / "staging"
    install_root.mkdir()
    (install_root / "current").write_text("0.1.0")

    priv, pub = _key()
    legit_zip = _core_zip_bytes("legit")
    legit_digest = hashlib.sha256(legit_zip).hexdigest()
    manifest = {"version": "0.2.0", "channel": "stable", "core_version": "0.2.0",
                "min_core_for_web": "0.0.0", "notes": "test",
                "core": {"sha256": legit_digest, "size": len(legit_zip)}}
    mbytes = json.dumps(manifest).encode()
    sig = update.sign_manifest(mbytes, priv)

    malicious_zip = _core_zip_bytes("malicious")
    malicious_digest = hashlib.sha256(malicious_zip).hexdigest()

    staging_dir.mkdir(parents=True)
    (staging_dir / "pending-core.zip").write_bytes(malicious_zip)
    marker = {"version": "0.2.0", "sha256": malicious_digest,
              "manifest_b64": base64.b64encode(mbytes).decode(),
              "sig_b64": base64.b64encode(sig).decode()}
    (staging_dir / "pending-core.json").write_text(json.dumps(marker))
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)

    result = coreupdate.apply_staged_core(install_root, staging_dir)

    assert result is None
    assert (install_root / "current").read_text().strip() == "0.1.0"
    assert not (install_root / "versions" / "0.2.0").exists()


@pytest.mark.parametrize("traversal_version", [
    "..\\..\\..\\Temp\\evil",
    "../../../Temp/evil",
])
def test_apply_staged_core_rejects_path_traversal_version(tmp_path, monkeypatch, traversal_version):
    """The marker's "version" is untrusted on-disk data that gets joined
    into `versions/<version>` and later EXECUTED by the launcher. A
    traversal string (backslash or forward-slash) must be rejected before
    anything is extracted or `current` is touched -- and must not reach
    outside install_root/versions at all, i.e. apply_staged_core must not
    even create install_root/versions for a rejected version."""
    install_root = tmp_path / "install"
    staging_dir = tmp_path / "staging"
    install_root.mkdir()
    (install_root / "current").write_text("0.1.0")

    pub = _stage(staging_dir, version=traversal_version)
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)

    result = coreupdate.apply_staged_core(install_root, staging_dir)

    assert result is None
    assert (install_root / "current").read_text().strip() == "0.1.0"     # unchanged
    assert not (install_root / "versions").exists()                      # nothing touched, nothing extracted


def test_apply_staged_core_rejects_version_mismatch_with_manifest(tmp_path, monkeypatch):
    """A marker whose "version" doesn't match the version actually inside
    the just-verified, signed manifest must be rejected -- even though the
    marker's "version" is syntactically a valid dotted-numeric string (so
    the traversal-syntax guard alone would not catch this), and even
    though every sha256 agrees (a same-content bundle simply mislabeled)."""
    install_root = tmp_path / "install"
    staging_dir = tmp_path / "staging"
    install_root.mkdir()
    (install_root / "current").write_text("0.1.0")

    priv, pub = _key()
    zip_bytes = _core_zip_bytes("0.3.0")
    digest = hashlib.sha256(zip_bytes).hexdigest()
    manifest = {"version": "0.3.0", "channel": "stable", "core_version": "0.3.0",
                "min_core_for_web": "0.0.0", "notes": "test",
                "core": {"sha256": digest, "size": len(zip_bytes)}}
    mbytes = json.dumps(manifest).encode()
    sig = update.sign_manifest(mbytes, priv)

    staging_dir.mkdir(parents=True)
    (staging_dir / "pending-core.zip").write_bytes(zip_bytes)
    marker = {"version": "0.2.0", "sha256": digest,   # marker claims 0.2.0...
              "manifest_b64": base64.b64encode(mbytes).decode(),
              "sig_b64": base64.b64encode(sig).decode()}   # ...manifest says 0.3.0
    (staging_dir / "pending-core.json").write_text(json.dumps(marker))
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)

    result = coreupdate.apply_staged_core(install_root, staging_dir)

    assert result is None
    assert (install_root / "current").read_text().strip() == "0.1.0"
    assert not (install_root / "versions" / "0.2.0").exists()
    assert not (install_root / "versions" / "0.3.0").exists()


def test_apply_staged_core_none_when_no_pending(tmp_path):
    install_root = tmp_path / "install"
    staging_dir = tmp_path / "staging"
    install_root.mkdir()
    (install_root / "current").write_text("0.1.0")

    assert coreupdate.apply_staged_core(install_root, staging_dir) is None
    assert (install_root / "current").read_text().strip() == "0.1.0"


def test_apply_staged_core_none_when_staging_dir_missing(tmp_path):
    install_root = tmp_path / "install"
    install_root.mkdir()
    assert coreupdate.apply_staged_core(install_root, tmp_path / "no-such-staging") is None


def test_rollback_reverts_current_on_failed_extract(tmp_path, monkeypatch):
    install_root = tmp_path / "install"
    staging_dir = tmp_path / "staging"
    install_root.mkdir()
    (install_root / "versions" / "0.1.0").mkdir(parents=True)
    (install_root / "versions" / "0.1.0" / "Kreds.exe").write_bytes(b"old exe")
    (install_root / "current").write_text("0.1.0")

    pub = _stage(staging_dir, version="0.2.0")
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)

    # Simulate a mid-extract failure (disk full / interrupted / corrupt
    # member) -- extractall raises partway through.
    def boom(self, path=None, members=None, pwd=None):
        raise OSError("simulated mid-extract failure")
    monkeypatch.setattr(coreupdate.zipfile.ZipFile, "extractall", boom)

    result = coreupdate.apply_staged_core(install_root, staging_dir)

    assert result is None
    assert (install_root / "current").read_text().strip() == "0.1.0"      # unchanged
    assert not (install_root / "versions" / "0.2.0").exists()             # no half-written dir left behind
    assert (install_root / "versions" / "0.1.0" / "Kreds.exe").read_bytes() == b"old exe"

    # revert_current is available for the launcher's own use if a
    # newly-*applied* (not just staged) version fails to even start:
    assert coreupdate.current_version(install_root) == "0.1.0"
    reverted = coreupdate.revert_current(install_root)  # no-op-ish: nothing to revert TO here
    assert reverted is None                              # no "previous" was ever set
    assert coreupdate.current_version(install_root) == "0.1.0"


def test_apply_staged_core_missing_exe_in_zip_discards_and_returns_none(tmp_path, monkeypatch):
    install_root = tmp_path / "install"
    staging_dir = tmp_path / "staging"
    install_root.mkdir()
    (install_root / "current").write_text("0.1.0")

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("not_the_right_exe.txt", "oops")
    bad_zip = buf.getvalue()
    pub = _stage(staging_dir, version="0.2.0", zip_bytes=bad_zip)
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)

    result = coreupdate.apply_staged_core(install_root, staging_dir)

    assert result is None
    assert (install_root / "current").read_text().strip() == "0.1.0"
    assert not (install_root / "versions" / "0.2.0").exists()


def test_apply_staged_core_no_signature_material_is_refused(tmp_path, monkeypatch):
    """An older/hand-built pending-core.json with no manifest_b64/sig_b64
    must be refused outright -- there is no sha256-only fallback path.
    hearth.update.stage_core() ALWAYS writes manifest_b64/sig_b64 for the
    only production caller (check()'s own return value), so a marker
    missing it is, by definition, not something this process staged."""
    install_root = tmp_path / "install"
    staging_dir = tmp_path / "staging"
    install_root.mkdir()
    (install_root / "current").write_text("0.1.0")

    pub = _stage(staging_dir, version="0.2.0", include_sig=False)
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)

    result = coreupdate.apply_staged_core(install_root, staging_dir)

    assert result is None
    assert (install_root / "current").read_text().strip() == "0.1.0"     # unchanged
    assert not (install_root / "versions" / "0.2.0").exists()             # nothing extracted
    assert not (staging_dir / "pending-core.zip").exists()                # staging cleared
    assert not (staging_dir / "pending-core.json").exists()


def test_apply_staged_core_no_signature_material_refused_even_with_correct_sha256(tmp_path):
    """THE ATTACK: an attacker with staging-dir write access (the exact
    adversary this module's docstring names) writes their own
    pending-core.zip plus a hand-crafted marker naming that same zip's own
    (correctly-computed) sha256, but with NO manifest_b64/sig_b64 at all --
    no forged signature is needed because there previously was a sha256-
    only fallback path that skipped re-verification entirely. This must
    now be refused: a correct sha256 pin is not a substitute for a valid
    Ed25519 signature, and there is no legitimate marker that omits one."""
    install_root = tmp_path / "install"
    staging_dir = tmp_path / "staging"
    install_root.mkdir()
    (install_root / "current").write_text("0.1.0")

    # No RELEASE_PUBKEY monkeypatch here: this marker was never signed by
    # anyone, so there is no key that would make it verify -- the fix must
    # reject it before verify_manifest is even relevant.
    _stage(staging_dir, version="0.2.0", include_sig=False)

    result = coreupdate.apply_staged_core(install_root, staging_dir)

    assert result is None
    assert (install_root / "current").read_text().strip() == "0.1.0"     # unchanged
    assert not (install_root / "versions" / "0.2.0").exists()             # nothing extracted
    assert not (staging_dir / "pending-core.zip").exists()                # staging cleared
    assert not (staging_dir / "pending-core.json").exists()


def test_apply_staged_core_no_signature_material_still_enforces_sha256(tmp_path):
    install_root = tmp_path / "install"
    staging_dir = tmp_path / "staging"
    install_root.mkdir()
    (install_root / "current").write_text("0.1.0")

    _stage(staging_dir, version="0.2.0", include_sig=False, sha256_override="0" * 64)

    result = coreupdate.apply_staged_core(install_root, staging_dir)

    assert result is None
    assert (install_root / "current").read_text().strip() == "0.1.0"
    assert not (install_root / "versions" / "0.2.0").exists()


def test_apply_staged_core_preexisting_target_dir_not_trusted_by_bare_existence(tmp_path, monkeypatch):
    """A pre-existing versions/<version> directory (e.g. planted by anyone
    with staging-write access -- the SAME trust level the sha256 check's
    target has -- or left over from a half-applied prior run) must NEVER
    be trusted by bare existence, even if it already contains a file
    named Kreds.exe. apply_staged_core must clear it and place the bytes
    that were JUST re-verified above, never flip `current` onto content
    that wasn't just checked."""
    install_root = tmp_path / "install"
    staging_dir = tmp_path / "staging"
    install_root.mkdir()
    (install_root / "versions" / "0.2.0").mkdir(parents=True)
    (install_root / "versions" / "0.2.0" / "Kreds.exe").write_bytes(b"planted / not verified")
    (install_root / "current").write_text("0.1.0")

    pub = _stage(staging_dir, version="0.2.0")
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)

    result = coreupdate.apply_staged_core(install_root, staging_dir)

    assert result == "0.2.0"
    # re-extracted from the just-verified zip -- placed bytes equal the
    # verified zip's Kreds.exe bytes, NOT the planted content
    verified_zip = _core_zip_bytes("0.2.0")  # _stage()'s default zip content for version="0.2.0"
    with zipfile.ZipFile(io.BytesIO(verified_zip)) as z:
        expected_exe_bytes = z.read("Kreds.exe")
    placed = (install_root / "versions" / "0.2.0" / "Kreds.exe").read_bytes()
    assert placed == expected_exe_bytes
    assert placed != b"planted / not verified"
    assert (install_root / "current").read_text().strip() == "0.2.0"
    assert not (staging_dir / "pending-core.zip").exists()


# ---------------------------------------------------------------------------
# current_version / revert_current
# ---------------------------------------------------------------------------

def test_current_version_reads_current_file(tmp_path):
    install_root = tmp_path / "install"
    install_root.mkdir()
    (install_root / "current").write_text("1.2.3")
    assert coreupdate.current_version(install_root) == "1.2.3"


def test_current_version_falls_back_to_newest_versions_dir(tmp_path):
    install_root = tmp_path / "install"
    (install_root / "versions" / "0.1.0").mkdir(parents=True)
    (install_root / "versions" / "0.10.0").mkdir(parents=True)
    (install_root / "versions" / "0.2.0").mkdir(parents=True)
    assert coreupdate.current_version(install_root) == "0.10.0"    # version-aware, not lexicographic


def test_current_version_none_when_nothing_installed(tmp_path):
    install_root = tmp_path / "install"
    install_root.mkdir()
    assert coreupdate.current_version(install_root) is None


def test_revert_current_sets_current_back_to_previous(tmp_path):
    install_root = tmp_path / "install"
    install_root.mkdir()
    (install_root / "current").write_text("0.2.0")
    (install_root / "previous").write_text("0.1.0")

    reverted = coreupdate.revert_current(install_root)

    assert reverted == "0.1.0"
    assert coreupdate.current_version(install_root) == "0.1.0"


def test_revert_current_none_when_no_previous(tmp_path):
    install_root = tmp_path / "install"
    install_root.mkdir()
    (install_root / "current").write_text("0.1.0")
    assert coreupdate.revert_current(install_root) is None
    assert coreupdate.current_version(install_root) == "0.1.0"     # unchanged


@pytest.mark.parametrize("bad_previous", [
    "..\\..\\..\\Temp\\evil", "../../../Temp/evil", "",
    "C:\\Windows\\System32\\evil",
])
def test_revert_current_rejects_invalid_previous(tmp_path, bad_previous):
    """`previous` is untrusted on-disk data at the same trust level as the
    staged marker's "version" -- it gets written verbatim into `current`,
    which is later joined into a `versions/<version>` path and executed.
    revert_current must validate it with is_valid_version() before writing
    it, exactly like every other on-disk read in this module, instead of
    trusting it by virtue of merely being present in `previous`."""
    install_root = tmp_path / "install"
    install_root.mkdir()
    (install_root / "current").write_text("0.1.0")
    (install_root / "previous").write_text(bad_previous)

    reverted = coreupdate.revert_current(install_root)

    assert reverted is None
    assert coreupdate.current_version(install_root) == "0.1.0"     # unchanged


# ---------------------------------------------------------------------------
# is_valid_version
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("version", [
    "1.2.3", "0.1.0", "9.9.9", "1.0", "1.2.3.4",
])
def test_is_valid_version_accepts_plain_dotted_numeric(version):
    assert coreupdate.is_valid_version(version) is True


@pytest.mark.parametrize("version", [
    "..\\..\\..\\Temp\\evil", "../../../Temp/evil", "..", "1.2.3\\..\\..\\evil",
    "C:\\Windows\\System32\\evil", "\\\\server\\share\\evil", "1.2.3/../../evil",
    "", None, 123, ["1.2.3"], "1.2.3 ", " 1.2.3", "1.2.3\n",
])
def test_is_valid_version_rejects_anything_not_plain_dotted_numeric(version):
    assert coreupdate.is_valid_version(version) is False


# ---------------------------------------------------------------------------
# packaging/launcher.py -- defense-in-depth version guard before exec
# ---------------------------------------------------------------------------

def _load_launcher():
    """packaging/launcher.py is not part of an importable `hearth`
    package, and the top-level directory name `packaging` collides with
    the unrelated PyPI `packaging` library that is also installed in this
    project's venv -- `import packaging.launcher` would silently resolve
    to the wrong module (or fail). Load the file directly by path
    instead."""
    path = Path(__file__).resolve().parent.parent / "packaging" / "launcher.py"
    spec = importlib.util.spec_from_file_location("kreds_test_launcher", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_launcher_refuses_to_run_traversal_current_even_if_exe_exists_there(tmp_path, monkeypatch):
    """`current` is untrusted on-disk state at the same trust level as the
    staged marker's "version" -- apply_staged_core() should never write
    anything but a validated version into it, but the launcher must not
    rely on that alone. If `current` names a traversal string, and an
    attacker has planted a real Kreds.exe at the path that string resolves
    to outside install_root/versions, the launcher must refuse to run it."""
    launcher = _load_launcher()

    install_root = tmp_path / "install"
    install_root.mkdir()
    traversal = "..\\..\\outside\\evil"
    (install_root / "current").write_text(traversal)

    # Simulate the attacker-planted payload at the path the traversal
    # string actually resolves to (two levels up from install_root/versions).
    planted_dir = tmp_path / "outside" / "evil"
    planted_dir.mkdir(parents=True)
    (planted_dir / "Kreds.exe").write_bytes(b"malicious payload")
    assert (install_root / "versions" / traversal / "Kreds.exe").exists()  # sanity: resolves as expected

    monkeypatch.setattr(launcher, "install_root", lambda: install_root)
    monkeypatch.setattr(launcher, "staging_dir", lambda: tmp_path / "no-such-staging")
    monkeypatch.setenv("APPDATA", str(tmp_path / "appdata"))

    ran = {"called": False}

    def fake_run_payload(exe):
        ran["called"] = True
        return 0, False
    monkeypatch.setattr(launcher, "_run_payload", fake_run_payload)

    rc = launcher.main()

    assert rc == 1
    assert ran["called"] is False   # the planted exe was never executed
