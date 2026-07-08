"""release-build -> release-sign -> release-publish pipeline (Task 4,
Windows-packaging Phase 2b-3). release-build assembles the web+core bundles
and a manifest.json that PINS THE REAL sha256 of the bytes it just wrote
(not a placeholder) -- a client that downloads + hashes those exact bundles
must match. release-sign signs that manifest with an offline key file (never
committed) into a sibling manifest.sig, which is the exact shape
hearth.update.check() expects to fetch next to the feed URL. release-publish
is a thin `gh release create` wrapper; --dry-run just prints the command
(no network, no `gh` dependency for the test suite)."""
import hashlib
import json
import zipfile

import pytest
from cryptography.hazmat.primitives import serialization as _s
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from hearth import cli, update


def _throwaway_key():
    k = Ed25519PrivateKey.generate()
    priv = k.private_bytes(_s.Encoding.Raw, _s.PrivateFormat.Raw, _s.NoEncryption()).hex()
    pub = k.public_key().public_bytes(_s.Encoding.Raw, _s.PublicFormat.Raw).hex()
    return priv, pub


def _make_web_dir(tmp_path):
    web = tmp_path / "web_src"
    web.mkdir()
    (web / "index.html").write_text("<html>kreds</html>")
    (web / "sw.js").write_text("// sw")
    return web


def _make_core_dir(tmp_path):
    # A minimal stand-in for a PyInstaller one-folder payload
    # (dist/Kreds/versions/<v>/): Kreds.exe at the root is the one thing
    # hearth.coreupdate.apply_staged_core actually checks for on extract.
    core = tmp_path / "core_src"
    core.mkdir()
    (core / "Kreds.exe").write_bytes(b"fake pyinstaller payload")
    internal = core / "_internal"
    internal.mkdir()
    (internal / "lib.dll").write_bytes(b"fake dep")
    return core


def _run_release_build(tmp_path, version="1.2.3", **extra_args):
    web = _make_web_dir(tmp_path)
    core = _make_core_dir(tmp_path)
    out_dir = tmp_path / "release"
    argv = ["release-build", "--version", version, "--web", str(web),
            "--core", str(core), "--out", str(out_dir)]
    for k, v in extra_args.items():
        argv += ["--" + k.replace("_", "-"), v]
    cli.main(argv)
    return out_dir


def test_release_build_writes_bundles_and_manifest(tmp_path):
    out_dir = _run_release_build(tmp_path, version="1.2.3")
    assert (out_dir / "web-1.2.3.zip").exists()
    assert (out_dir / "core-1.2.3.zip").exists()
    assert (out_dir / "manifest.json").exists()


def test_release_build_manifest_pins_real_sha256_and_size(tmp_path):
    out_dir = _run_release_build(tmp_path, version="1.2.3")
    manifest = json.loads((out_dir / "manifest.json").read_text())

    web_bytes = (out_dir / "web-1.2.3.zip").read_bytes()
    core_bytes = (out_dir / "core-1.2.3.zip").read_bytes()

    assert manifest["web"]["sha256"] == hashlib.sha256(web_bytes).hexdigest()
    assert manifest["web"]["size"] == len(web_bytes)
    assert manifest["core"]["sha256"] == hashlib.sha256(core_bytes).hexdigest()
    assert manifest["core"]["size"] == len(core_bytes)

    # Not placeholder/empty hashes -- a tampered rebuild must change these.
    assert manifest["web"]["sha256"] != manifest["core"]["sha256"]


def test_release_build_manifest_content_is_real(tmp_path):
    """The assembled web-<v>.zip must actually contain the web source
    files (not an empty/placeholder archive) -- build_web_bundle is doing
    real work here, not being stubbed."""
    out_dir = _run_release_build(tmp_path, version="1.2.3")
    with zipfile.ZipFile(out_dir / "web-1.2.3.zip") as z:
        names = z.namelist()
    assert "index.html" in names and "sw.js" in names

    with zipfile.ZipFile(out_dir / "core-1.2.3.zip") as z:
        core_names = z.namelist()
    assert "Kreds.exe" in core_names


def test_release_build_manifest_urls_point_at_kreds_updater(tmp_path):
    out_dir = _run_release_build(tmp_path, version="1.2.3")
    manifest = json.loads((out_dir / "manifest.json").read_text())
    assert manifest["web"]["url"] == (
        "https://github.com/wong001/kreds_updater/releases/download/"
        "v1.2.3/web-1.2.3.zip")
    assert manifest["core"]["url"] == (
        "https://github.com/wong001/kreds_updater/releases/download/"
        "v1.2.3/core-1.2.3.zip")


def test_release_build_manifest_version_fields(tmp_path):
    out_dir = _run_release_build(tmp_path, version="1.2.3")
    manifest = json.loads((out_dir / "manifest.json").read_text())
    assert manifest["version"] == "1.2.3"
    # core_version defaults to the same version (this pipeline always
    # builds+publishes web+core together as one release). min_core_for_web
    # defaults to "0.0.0" (no floor) -- most releases' web bundle doesn't
    # require a specific new core, so the common case shouldn't need
    # --min-core-for-web threaded through every release-build invocation;
    # only a release whose web bundle genuinely needs new core APIs passes
    # it explicitly.
    assert manifest["core_version"] == "1.2.3"
    assert manifest["min_core_for_web"] == "0.0.0"


def test_release_build_min_core_for_web_override(tmp_path):
    out_dir = _run_release_build(tmp_path, version="1.2.3",
                                 min_core_for_web="1.0.0")
    manifest = json.loads((out_dir / "manifest.json").read_text())
    assert manifest["min_core_for_web"] == "1.0.0"


def test_release_build_omits_released_at_by_default(tmp_path):
    """No wall-clock default -- release-build's output must be
    deterministic/reproducible when --released-at isn't given."""
    out_dir = _run_release_build(tmp_path, version="1.2.3")
    manifest = json.loads((out_dir / "manifest.json").read_text())
    assert "released_at" not in manifest


def test_release_build_accepts_explicit_released_at(tmp_path):
    out_dir = _run_release_build(tmp_path, version="1.2.3",
                                 released_at="2026-07-08T00:00:00Z")
    manifest = json.loads((out_dir / "manifest.json").read_text())
    assert manifest["released_at"] == "2026-07-08T00:00:00Z"


def test_release_sign_writes_sibling_manifest_sig(tmp_path):
    """update.check() fetches a file literally named manifest.sig next to
    the feed URL (hearth/update.py's _sibling_url) -- release-sign must
    produce that exact name next to manifest.json, not manifest.json.sig."""
    out_dir = _run_release_build(tmp_path, version="1.2.3")
    priv, pub = _throwaway_key()
    key_path = tmp_path / "release_private_key.hex"
    key_path.write_text(priv)

    cli.main(["release-sign", "--manifest", str(out_dir / "manifest.json"),
             "--key", str(key_path)])

    sig_path = out_dir / "manifest.sig"
    assert sig_path.exists()
    assert not (out_dir / "manifest.json.sig").exists()
    assert update.verify_manifest(
        (out_dir / "manifest.json").read_bytes(), sig_path.read_bytes(), pub)


def test_full_pipeline_client_check_reports_web_and_core_available(tmp_path, monkeypatch):
    """End to end: release-build assembles the feed, release-sign signs it
    with a throwaway key, and update.check() against that assembled+signed
    LOCAL feed (HEARTH_UPDATE_FEED-style file:// URL, RELEASE_PUBKEY
    monkeypatched to the throwaway public key) reports both bundles
    available -- exactly the shape a real client sees against the real
    kreds_updater feed."""
    out_dir = _run_release_build(tmp_path, version="9.9.9")
    priv, pub = _throwaway_key()
    key_path = tmp_path / "release_private_key.hex"
    key_path.write_text(priv)
    cli.main(["release-sign", "--manifest", str(out_dir / "manifest.json"),
             "--key", str(key_path)])
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)

    feed_url = (out_dir / "manifest.json").as_uri()
    info = update.check(feed_url)

    assert info is not None
    assert info["web_available"] is True
    assert info["core_available"] is True
    assert info["version"] == "9.9.9"

    # And the pinned hashes actually verify the real bundle bytes end to
    # end (not just present -- correct).
    web_bytes = (out_dir / "web-9.9.9.zip").read_bytes()
    assert hashlib.sha256(web_bytes).hexdigest() == info["web"]["sha256"]


def test_full_pipeline_client_rejects_tampered_bundle(tmp_path, monkeypatch):
    """If a bundle on disk is tampered with after signing, the pinned
    sha256 in the (still validly signed) manifest no longer matches --
    apply_web must refuse it. Proves the manifest is really pinning
    content, not just present alongside it. (release-build's web/core
    "url" fields always point at the real kreds_updater release-asset
    URLs -- checked separately above -- so this substitutes the LOCAL
    zip's file:// URI for the fetch step, the same way
    tests/test_update_client.py's own local-feed tests do.)"""
    out_dir = _run_release_build(tmp_path, version="9.9.9")
    priv, pub = _throwaway_key()
    key_path = tmp_path / "release_private_key.hex"
    key_path.write_text(priv)
    cli.main(["release-sign", "--manifest", str(out_dir / "manifest.json"),
             "--key", str(key_path)])
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)

    feed_url = (out_dir / "manifest.json").as_uri()
    info = update.check(feed_url)
    assert info is not None

    web_zip = out_dir / "web-9.9.9.zip"
    info["web"] = dict(info["web"])
    info["web"]["url"] = web_zip.as_uri()   # fetch the local file, not github.com
    web_zip.write_bytes(b"tampered bytes, different content")

    served = tmp_path / "served"
    served.mkdir()
    (served / "index.html").write_text("OLD")
    with pytest.raises(update.BadUpdate):
        update.apply_web(info, served)


def test_release_private_key_not_committed(tmp_path):
    """The private key used above is a throwaway file under tmp_path, never
    part of the repo -- and .gitignore's release_private_key* pattern (plus
    this ls-files check) is what keeps a REAL release key from ever landing
    in git history."""
    import pathlib
    import subprocess
    tracked = subprocess.run(
        ["git", "ls-files"], capture_output=True, text=True,
        cwd=pathlib.Path(update.__file__).parents[1]).stdout
    assert "release_private_key" not in tracked


def test_release_publish_dry_run_prints_gh_command_without_running_it(tmp_path, capsys):
    out_dir = _run_release_build(tmp_path, version="1.2.3")
    priv, pub = _throwaway_key()
    key_path = tmp_path / "release_private_key.hex"
    key_path.write_text(priv)
    cli.main(["release-sign", "--manifest", str(out_dir / "manifest.json"),
             "--key", str(key_path)])

    cli.main(["release-publish", "--version", "1.2.3", "--dir", str(out_dir),
             "--dry-run"])
    out = capsys.readouterr().out
    assert "gh release create v1.2.3" in out
    assert "wong001/kreds_updater" in out
    assert "manifest.json" in out and "manifest.sig" in out
    assert "web-1.2.3.zip" in out and "core-1.2.3.zip" in out


def test_release_publish_refuses_when_assets_missing(tmp_path):
    out_dir = tmp_path / "empty_release"
    out_dir.mkdir()
    with pytest.raises(SystemExit):
        cli.main(["release-publish", "--version", "1.2.3", "--dir", str(out_dir),
                 "--dry-run"])

def test_release_publish_includes_installer_when_passed(tmp_path, capsys):
    # The website's stable download URL (…/releases/latest/download/
    # KredsSetup.exe) only stays alive if every release uploads the
    # installer - release-publish takes --installer and includes it in the
    # gh command; a bad path fails fast instead of silently publishing a
    # release that breaks the download page.
    out_dir = _run_release_build(tmp_path, version="1.2.3")
    priv, pub = _throwaway_key()
    key_path = tmp_path / "release_private_key.hex"
    key_path.write_text(priv)
    cli.main(["release-sign", "--manifest", str(out_dir / "manifest.json"),
             "--key", str(key_path)])

    setup = tmp_path / "KredsSetup.exe"
    setup.write_bytes(b"not a real installer")
    cli.main(["release-publish", "--version", "1.2.3", "--dir", str(out_dir),
             "--installer", str(setup), "--dry-run"])
    out = capsys.readouterr().out
    assert "KredsSetup.exe" in out

    with pytest.raises(SystemExit) as e:
        cli.main(["release-publish", "--version", "1.2.3",
                 "--dir", str(out_dir),
                 "--installer", str(tmp_path / "missing.exe"), "--dry-run"])
    assert "missing" in str(e.value)
