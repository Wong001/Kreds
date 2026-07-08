"""End-to-end Windows packaging Phase 2b integration (Task 5): the full
UPDATE CHAIN a real release actually drives -- check() -> apply_web() ->
stage_core() -> coreupdate.apply_staged_core() -- against a signed release
feed built with a THROWAWAY Ed25519 key (never the real release key), and
no real .exe or GUI anywhere. Combines what tests/test_update_integration.py
(Phase 2a, web-only) and tests/test_coreupdate.py (core-swap re-verify)
each cover in isolation into the single chain a combined web+core release
walks:

  1. Build ONE signed manifest.json (+ sibling manifest.sig) carrying BOTH
     a web bundle (a tiny real zip via update.build_web_bundle) and a core
     bundle (a fake PyInstaller payload zip with Kreds.exe at its root,
     mirroring test_coreupdate.py's _core_zip_bytes) -- the same shape
     hearth/cli.py's release-build/-sign assemble, built in-process here
     (no subprocess, no real .exe).
  2. update.check() against that feed reports BOTH web_available and
     core_available.
  3. update.apply_web() swaps a temp served web dir in place and bumps its
     VERSION file.
  4. update.stage_core() stages the signed core bundle (zip + a marker
     carrying the manifest bytes/sig for later re-verification).
  5. coreupdate.apply_staged_core() RE-VERIFIES the staged material from
     scratch -- independent of step 2's check() having already passed --
     extracts versions/<newver>/Kreds.exe, and flips `current`.
  6. A TAMPERED feed is refused at every step it reaches: a bad signature
     is caught by check() (nothing else ever runs) and, simulating an
     attacker who bypasses check() and writes tampered material straight
     into the staging dir (coreupdate.py's own documented threat model),
     is independently caught again by apply_staged_core()'s re-verify. A
     bad bundle hash (valid signature, swapped bundle bytes) is caught by
     apply_web()/stage_core() themselves. Either way, nothing is ever
     applied and `current` is left exactly as it was.

Every test uses tmp_path and a throwaway key generated on the spot,
monkeypatches update.RELEASE_PUBKEY directly, and only ever talks to
file:// URIs -- no real network I/O, no subprocess, no GUI -- so this
suite terminates fast."""
import base64
import hashlib
import io
import json
import zipfile

import pytest
from cryptography.hazmat.primitives import serialization as _s
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from hearth import coreupdate, update


def _throwaway_key():
    k = Ed25519PrivateKey.generate()
    priv = k.private_bytes(_s.Encoding.Raw, _s.PrivateFormat.Raw, _s.NoEncryption()).hex()
    pub = k.public_key().public_bytes(_s.Encoding.Raw, _s.PublicFormat.Raw).hex()
    return priv, pub


def _core_zip_bytes(marker_text: str = "payload") -> bytes:
    """A fake PyInstaller one-folder payload zip -- Kreds.exe at the zip
    root, matching what coreupdate.PAYLOAD_EXE_NAME expects on extract
    (mirrors tests/test_coreupdate.py's identical helper)."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("Kreds.exe", b"fake payload exe: " + marker_text.encode())
        z.writestr("_internal/marker.txt", marker_text)
    return buf.getvalue()


def _build_feed(feed_dir, version: str, priv: str, web_body: str = "NEW CONTENT",
                core_marker: str | None = None, min_core_for_web: str = "0.0.0") -> str:
    """Assemble a real signed manifest.json + manifest.sig + web.zip +
    core.zip in feed_dir -- the same shape hearth/cli.py's release-build/
    -sign produce (web/core bundle + sha256/size, Ed25519-signed manifest),
    built directly with update.build_web_bundle + a hand-built core zip
    rather than shelling out to the CLI. Returns the manifest's file://
    URI (what update.check()'s feed_url expects)."""
    web_src = feed_dir / "web_src"
    web_src.mkdir()
    (web_src / "index.html").write_text(web_body)
    web_data, web_digest = update.build_web_bundle(web_src)
    (feed_dir / "web.zip").write_bytes(web_data)

    core_data = _core_zip_bytes(core_marker or version)
    core_digest = hashlib.sha256(core_data).hexdigest()
    (feed_dir / "core.zip").write_bytes(core_data)

    manifest = {
        "version": version,
        "core_version": version,
        "min_core_for_web": min_core_for_web,
        "web": {"url": (feed_dir / "web.zip").as_uri(), "sha256": web_digest,
                "size": len(web_data)},
        "core": {"url": (feed_dir / "core.zip").as_uri(), "sha256": core_digest,
                 "size": len(core_data)},
        "notes": "packaging integration test release",
    }
    mbytes = json.dumps(manifest).encode()
    (feed_dir / "manifest.json").write_bytes(mbytes)
    (feed_dir / "manifest.sig").write_bytes(update.sign_manifest(mbytes, priv))
    return (feed_dir / "manifest.json").as_uri()


def _served_dir(tmp_path, body: str = "OLD", version: str = "0.1.0"):
    served = tmp_path / "served"
    served.mkdir()
    (served / "index.html").write_text(body)
    (served / "VERSION").write_text(version)
    return served


def _install_root(tmp_path, current_version: str = "0.1.0"):
    install_root = tmp_path / "install"
    (install_root / "versions" / current_version).mkdir(parents=True)
    (install_root / "versions" / current_version / "Kreds.exe").write_bytes(b"old exe")
    (install_root / "current").write_text(current_version)
    return install_root


# ---------------------------------------------------------------------------
# 1. The happy path: one signed feed, both bundles, the FULL chain.
# ---------------------------------------------------------------------------

def test_web_and_core_update_chain_end_to_end(tmp_path, monkeypatch):
    priv, pub = _throwaway_key()
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)

    feed_dir = tmp_path / "feed"
    feed_dir.mkdir()
    feed_url = _build_feed(feed_dir, "9.9.9", priv, web_body="NEW CONTENT 9.9.9")

    served = _served_dir(tmp_path)
    install_root = _install_root(tmp_path)

    # check() sees BOTH bundles as available (CORE_VERSION 0.2.0 < 9.9.9,
    # installed web 0.1.0 < 9.9.9, min_core_for_web 0.0.0 already met).
    info = update.check(feed_url, web_dir=served)
    assert info is not None
    assert info["web_available"] is True
    assert info["core_available"] is True
    assert info["version"] == "9.9.9"

    # apply_web: atomic swap of the served dir + VERSION bump.
    result = update.apply_web(info, served)
    assert result == "reload"
    assert (served / "index.html").read_text() == "NEW CONTENT 9.9.9"
    assert (served / "VERSION").read_text().strip() == "9.9.9"

    # stage_core: download + hash-verify + write the pending marker
    # (carrying the manifest bytes/sig from check() for later re-verify).
    staging_dir = tmp_path / "staging"
    update.stage_core(info, staging_dir)
    assert (staging_dir / "pending-core.zip").exists()
    marker = json.loads((staging_dir / "pending-core.json").read_text())
    assert marker["version"] == "9.9.9"
    assert "manifest_b64" in marker and "sig_b64" in marker

    # apply_staged_core: independent re-verify against THIS process's own
    # RELEASE_PUBKEY, extraction to versions/<new>/, and the current flip.
    applied = coreupdate.apply_staged_core(install_root, staging_dir)
    assert applied == "9.9.9"
    assert (install_root / "versions" / "9.9.9" / "Kreds.exe").exists()
    assert (install_root / "versions" / "9.9.9" / "_internal" / "marker.txt").exists()
    assert (install_root / "current").read_text().strip() == "9.9.9"
    assert (install_root / "previous").read_text().strip() == "0.1.0"
    # the prior version's own files are never touched
    assert (install_root / "versions" / "0.1.0" / "Kreds.exe").read_bytes() == b"old exe"
    # staging is cleared once applied
    assert not (staging_dir / "pending-core.zip").exists()
    assert not (staging_dir / "pending-core.json").exists()


# ---------------------------------------------------------------------------
# 2. A forged-signature feed: check() refuses outright, and -- simulating
#    an attacker who bypasses check() entirely and writes tampered material
#    straight into the staging dir (exactly the threat model
#    hearth/coreupdate.py's own module docstring names) -- apply_staged_core
#    independently refuses too. Nothing is ever applied; current unchanged.
# ---------------------------------------------------------------------------

def test_bad_signature_feed_refuses_check_and_apply_staged_core(tmp_path, monkeypatch):
    _, trusted_pub = _throwaway_key()          # the pubkey the app trusts
    forged_priv, _ = _throwaway_key()          # attacker signs with a DIFFERENT key
    monkeypatch.setattr(update, "RELEASE_PUBKEY", trusted_pub)

    feed_dir = tmp_path / "feed"
    feed_dir.mkdir()
    feed_url = _build_feed(feed_dir, "9.9.9", forged_priv, web_body="EVIL CONTENT")

    served = _served_dir(tmp_path)
    install_root = _install_root(tmp_path)

    # check() must refuse outright -- nothing available, so production code
    # (hearth/api.py's /api/update/*) never even calls apply_web/stage_core.
    info = update.check(feed_url, web_dir=served)
    assert info is None
    assert (served / "index.html").read_text() == "OLD"          # untouched
    assert (served / "VERSION").read_text().strip() == "0.1.0"

    # Simulate the tampered-staging-dir threat model apply_staged_core's
    # re-verify exists for: material lands in the staging dir some other
    # way (a compromised disk, a crashed prior process) carrying this same
    # forged-but-well-formed manifest/signature -- apply_staged_core must
    # catch it independently, not rely on check() having already run.
    manifest_bytes = (feed_dir / "manifest.json").read_bytes()
    sig_bytes = (feed_dir / "manifest.sig").read_bytes()
    manifest = json.loads(manifest_bytes)

    staging_dir = tmp_path / "staging"
    staging_dir.mkdir()
    (staging_dir / "pending-core.zip").write_bytes((feed_dir / "core.zip").read_bytes())
    marker = {"version": manifest["version"], "sha256": manifest["core"]["sha256"],
              "manifest_b64": base64.b64encode(manifest_bytes).decode(),
              "sig_b64": base64.b64encode(sig_bytes).decode()}
    (staging_dir / "pending-core.json").write_text(json.dumps(marker))

    applied = coreupdate.apply_staged_core(install_root, staging_dir)

    assert applied is None
    assert (install_root / "current").read_text().strip() == "0.1.0"     # unchanged
    assert not (install_root / "versions" / "9.9.9").exists()            # nothing extracted


# ---------------------------------------------------------------------------
# 3. A validly-signed manifest whose bundle BYTES were swapped afterward
#    (the signature over the manifest is genuine -- only the sha256 pin no
#    longer matches what's actually served). check() still reports the
#    update available (it only verifies the manifest signature, never
#    fetches the bundles), but apply_web/stage_core -- which DO re-hash the
#    downloaded bytes -- must both refuse, and apply_staged_core must find
#    nothing staged to apply.
# ---------------------------------------------------------------------------

def test_tampered_bundle_bytes_refuse_apply_web_and_stage_core(tmp_path, monkeypatch):
    priv, pub = _throwaway_key()
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)

    feed_dir = tmp_path / "feed"
    feed_dir.mkdir()
    feed_url = _build_feed(feed_dir, "9.9.9", priv, web_body="NEW CONTENT 9.9.9")

    # Swap BOTH bundles' bytes on disk AFTER signing -- the manifest's
    # pinned sha256 values (computed from the original bytes) no longer
    # match what a client would actually download.
    (feed_dir / "web.zip").write_bytes(b"PK\x03\x04not the signed web bundle")
    (feed_dir / "core.zip").write_bytes(_core_zip_bytes("evil-swap"))

    served = _served_dir(tmp_path)
    install_root = _install_root(tmp_path)

    info = update.check(feed_url, web_dir=served)
    assert info is not None            # the manifest's own signature is still genuine
    assert info["web_available"] is True
    assert info["core_available"] is True

    with pytest.raises(update.BadUpdate):
        update.apply_web(info, served)
    assert (served / "index.html").read_text() == "OLD"          # nothing applied
    assert (served / "VERSION").read_text().strip() == "0.1.0"

    staging_dir = tmp_path / "staging"
    with pytest.raises(update.BadUpdate):
        update.stage_core(info, staging_dir)
    assert not (staging_dir / "pending-core.zip").exists()        # nothing staged
    assert not (staging_dir / "pending-core.json").exists()

    # With nothing staged, apply_staged_core has nothing to apply.
    applied = coreupdate.apply_staged_core(install_root, staging_dir)
    assert applied is None
    assert (install_root / "current").read_text().strip() == "0.1.0"
    assert not (install_root / "versions" / "9.9.9").exists()
