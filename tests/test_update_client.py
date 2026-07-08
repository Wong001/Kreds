import base64, json, hashlib, os, io, zipfile, pytest
from pathlib import Path
from hearth import update
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization as _s

def _key():
    k = Ed25519PrivateKey.generate()
    return (k.private_bytes(_s.Encoding.Raw,_s.PrivateFormat.Raw,_s.NoEncryption()).hex(),
            k.public_key().public_bytes(_s.Encoding.Raw,_s.PublicFormat.Raw).hex())

def _feed(tmp_path, version, min_core="0.0.0", corrupt_hash=False):
    web = tmp_path / "src"; web.mkdir(); (web / "index.html").write_text("NEW " + version)
    data, digest = update.build_web_bundle(web)
    (tmp_path / "web.zip").write_bytes(data)
    if corrupt_hash: digest = "0"*64
    manifest = {"version": version, "channel": "stable", "core_version": version,
                "min_core_for_web": min_core,
                "web": {"url": (tmp_path/"web.zip").as_uri(), "sha256": digest, "size": len(data)},
                "notes": "test"}
    mbytes = json.dumps(manifest).encode()
    priv, pub = _key()
    (tmp_path / "manifest.json").write_bytes(mbytes)
    (tmp_path / "manifest.sig").write_bytes(update.sign_manifest(mbytes, priv))
    return (tmp_path/"manifest.json").as_uri(), pub

def test_check_reports_newer(tmp_path, monkeypatch):
    url, pub = _feed(tmp_path, "9.9.9")
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    info = update.check(url)
    assert info and info["web_available"]

def test_check_none_when_not_newer(tmp_path, monkeypatch):
    url, pub = _feed(tmp_path, update.CORE_VERSION)
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    assert update.check(url) is None

def test_check_bad_signature_refused(tmp_path, monkeypatch):
    url, pub = _feed(tmp_path, "9.9.9")
    _, other = _key()
    monkeypatch.setattr(update, "RELEASE_PUBKEY", other)   # wrong pubkey
    assert update.check(url) is None                        # unsigned/bad -> no update

def test_check_never_raises_on_fetch_error(tmp_path, monkeypatch):
    # no manifest at all at this URL -- must return None, never raise
    bogus = (tmp_path / "nope" / "manifest.json").as_uri()
    assert update.check(bogus) is None

def test_apply_web_swaps_and_versions(tmp_path, monkeypatch):
    url, pub = _feed(tmp_path, "9.9.9")
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    served = tmp_path / "served"; served.mkdir(); (served/"index.html").write_text("OLD")
    info = update.check(url)
    result = update.apply_web(info, served)
    assert result == "reload"
    assert (served/"index.html").read_text().startswith("NEW")
    assert (served/"VERSION").read_text().strip() == "9.9.9"

def test_apply_web_rejects_bad_hash(tmp_path, monkeypatch):
    url, pub = _feed(tmp_path, "9.9.9", corrupt_hash=True)
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    served = tmp_path / "served"; served.mkdir(); (served/"index.html").write_text("OLD")
    info = update.check(url)
    with pytest.raises(update.BadUpdate):
        update.apply_web(info, served)
    assert (served/"index.html").read_text() == "OLD"       # untouched on reject

def test_apply_web_gated_on_min_core(tmp_path, monkeypatch):
    """min_core=99.0.0 means check() itself now refuses to offer this web
    update at all (web_available folds in the min_core gate -- whole-branch
    review, IMPORTANT #1), so this loads the signed manifest directly
    (bypassing check()) to prove apply_web ALSO enforces the gate on its
    own -- defense in depth for a stale/hand-built info dict reaching
    apply_web some other way."""
    url, pub = _feed(tmp_path, "9.9.9", min_core="99.0.0")
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    served = tmp_path / "served"; served.mkdir(); (served/"index.html").write_text("OLD")
    assert update.check(url) is None                        # not offered at all
    info = json.loads((tmp_path / "manifest.json").read_bytes())
    with pytest.raises(update.BadUpdate):
        update.apply_web(info, served)

def test_apply_web_mid_swap_failure_restores_served_dir(tmp_path, monkeypatch):
    """If the second rename (.web-new -> web_dir) blows up on EVERY attempt
    (a persistent failure, not a transient Windows sharing-violation blip --
    see _rename_with_retry, which now retries each rename for ~3s before
    giving up), the original web_dir must still be restored from .web-bak,
    not left missing/partial. time.sleep is stubbed out so the ~3s retry
    budget this exhausts doesn't slow the test down."""
    url, pub = _feed(tmp_path, "9.9.9")
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    monkeypatch.setattr(update.time, "sleep", lambda *_: None)
    served = tmp_path / "served"; served.mkdir(); (served/"index.html").write_text("OLD")
    info = update.check(url)

    real_rename = os.rename
    new_dir = served.parent / ".web-new"
    def flaky_rename(src, dst):
        if str(src) == str(new_dir):    # the .web-new -> web_dir step, every attempt
            raise OSError("simulated persistent failure mid-swap")
        return real_rename(src, dst)
    monkeypatch.setattr(update.os, "rename", flaky_rename)

    with pytest.raises(OSError):
        update.apply_web(info, served)

    assert served.is_dir()
    assert (served/"index.html").read_text() == "OLD"        # restored, not new/missing

def test_stage_core_writes_pending_marker(tmp_path, monkeypatch):
    web = tmp_path / "src"; web.mkdir(); (web/"index.html").write_text("x")
    data, digest = update.build_web_bundle(web)
    (tmp_path/"core.zip").write_bytes(data)
    manifest = {"version": "9.9.9", "channel": "stable", "core_version": "9.9.9",
                "min_core_for_web": "0.0.0",
                "core": {"url": (tmp_path/"core.zip").as_uri(), "sha256": digest, "size": len(data)},
                "notes": "test"}
    info = {"info": manifest}
    staging = tmp_path / "staging"; staging.mkdir()
    update.stage_core(manifest, staging)
    assert (staging/"pending-core.zip").exists()
    marker = json.loads((staging/"pending-core.json").read_text())
    assert marker["version"] == "9.9.9"

def test_stage_core_rejects_bad_hash(tmp_path):
    web = tmp_path / "src"; web.mkdir(); (web/"index.html").write_text("x")
    data, digest = update.build_web_bundle(web)
    (tmp_path/"core.zip").write_bytes(data)
    manifest = {"version": "9.9.9", "core_version": "9.9.9", "min_core_for_web": "0.0.0",
                "core": {"url": (tmp_path/"core.zip").as_uri(), "sha256": "0"*64, "size": len(data)}}
    staging = tmp_path / "staging"; staging.mkdir()
    with pytest.raises(update.BadUpdate):
        update.stage_core(manifest, staging)
    assert not (staging/"pending-core.zip").exists()

def test_build_app_web_dir_param(tmp_path):
    from fastapi.testclient import TestClient
    from hearth.api import build_app
    from hearth.node import HearthNode
    (tmp_path/"index.html").write_text("<html>custom</html>"); (tmp_path/"sw.js").write_text("//x")
    node = HearthNode.create(tmp_path/"n", "W", "d")
    c = TestClient(build_app(node, web_dir=tmp_path))
    assert "custom" in c.get("/").text

def test_build_app_web_dir_default_unchanged(tmp_path):
    from fastapi.testclient import TestClient
    from hearth.api import build_app, WEB_DIR
    from hearth.node import HearthNode
    node = HearthNode.create(tmp_path/"n", "W", "d")
    c = TestClient(build_app(node))
    resp = c.get("/")
    assert resp.status_code == 200
    assert resp.content == (WEB_DIR / "index.html").read_bytes()


# ---------------------------------------------------------------------------
# IMPORTANT #1 -- web anti-rollback: a replayed old-but-signed manifest must
# not downgrade an already-updated web bundle, and an applied update must
# not be re-offered forever.
# ---------------------------------------------------------------------------

def test_check_web_not_offered_when_older_than_installed(tmp_path, monkeypatch):
    """A signed manifest offering version 1.0.0 is a legitimate, validly-
    signed manifest -- but if web_dir/VERSION already says 2.0.0 (e.g. a
    later update already applied, or this is literally a replayed OLD
    manifest), check() must not report it available."""
    url, pub = _feed(tmp_path, "1.0.0")
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    web_dir = tmp_path / "served"; web_dir.mkdir()
    (web_dir / "VERSION").write_text("2.0.0")
    assert update.check(url, web_dir=web_dir) is None


def test_apply_web_rejects_not_newer_than_installed(tmp_path, monkeypatch):
    """apply_web re-asserts the floor itself (persisted anti-rollback):
    even handed a validly-signed info dict, it refuses to "apply" a version
    that isn't strictly newer than what's already on disk in web_dir."""
    url, pub = _feed(tmp_path, "9.9.9")
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    served = tmp_path / "served"; served.mkdir()
    (served / "index.html").write_text("CURRENT")
    (served / "VERSION").write_text("9.9.9")           # already at this version
    info = json.loads((tmp_path / "manifest.json").read_bytes())
    with pytest.raises(update.BadUpdate):
        update.apply_web(info, served)
    assert (served / "index.html").read_text() == "CURRENT"    # untouched


def test_applied_web_update_not_reoffered(tmp_path, monkeypatch):
    """Once apply_web has actually applied a version, a subsequent check()
    against the SAME still-signed manifest must stop offering it -- no
    apply-forever loop."""
    url, pub = _feed(tmp_path, "9.9.9")
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    served = tmp_path / "served"; served.mkdir(); (served/"index.html").write_text("OLD")
    info = update.check(url, web_dir=served)
    assert info is not None and info["web_available"]
    update.apply_web(info, served)
    assert update.check(url, web_dir=served) is None    # not offered again


# ---------------------------------------------------------------------------
# IMPORTANT #3 -- _fetch: timeout + size caps.
# ---------------------------------------------------------------------------

def test_fetch_rejects_oversized_content(tmp_path):
    p = tmp_path / "big.bin"
    p.write_bytes(b"x" * 2000)
    with pytest.raises(update.BadUpdate):
        update._fetch(p.as_uri(), max_bytes=1000)


def test_fetch_passes_timeout_to_urlopen(tmp_path, monkeypatch):
    p = tmp_path / "small.bin"
    p.write_bytes(b"hi")
    seen = {}
    real_urlopen = update.urllib.request.urlopen
    def spy(url, timeout=None):
        seen["timeout"] = timeout
        return real_urlopen(url, timeout=timeout)
    monkeypatch.setattr(update.urllib.request, "urlopen", spy)
    update._fetch(p.as_uri(), timeout=7)
    assert seen["timeout"] == 7


def test_check_rejects_oversized_manifest(tmp_path):
    """A manifest.json above the 1MB cap must never be parsed -- check()
    reads it into memory with an bounded read() and refuses (surfaces as
    None, per check()'s never-raise contract)."""
    huge = tmp_path / "manifest.json"
    huge.write_bytes(b"{" + b" " * 1_200_000 + b"}")
    (tmp_path / "manifest.sig").write_bytes(b"x" * 64)
    assert update.check(huge.as_uri()) is None


def test_apply_web_rejects_oversized_bundle(tmp_path, monkeypatch):
    """The manifest pins the bundle's size; if the bytes actually served at
    that URL are much larger than size+slack, _fetch must cap and raise
    BadUpdate rather than reading an unbounded stream into memory before
    the sha256 check even runs."""
    url, pub = _feed(tmp_path, "9.9.9")
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    info = json.loads((tmp_path / "manifest.json").read_bytes())
    (tmp_path / "web.zip").write_bytes(b"x" * (info["web"]["size"] + 100_000))
    served = tmp_path / "served"; served.mkdir(); (served/"index.html").write_text("OLD")
    with pytest.raises(update.BadUpdate):
        update.apply_web(info, served)
    assert (served/"index.html").read_text() == "OLD"


# ---------------------------------------------------------------------------
# MINOR #5 -- relative bundle URLs resolve against the feed URL.
# ---------------------------------------------------------------------------

def test_relative_bundle_url_resolves_against_feed_url(tmp_path, monkeypatch):
    """release-build's default web.url is a bare filename (the GitHub-
    Releases-friendly shape: an asset sitting next to manifest.json). check()
    must resolve it to an absolute URL _fetch can actually open."""
    web = tmp_path / "src"; web.mkdir(); (web / "index.html").write_text("NEW REL")
    data, digest = update.build_web_bundle(web)
    (tmp_path / "web.zip").write_bytes(data)          # sits NEXT TO the manifest
    manifest = {"version": "9.9.9", "channel": "stable", "core_version": "9.9.9",
                "min_core_for_web": "0.0.0",
                "web": {"url": "web.zip", "sha256": digest, "size": len(data)},
                "notes": "test"}
    mbytes = json.dumps(manifest).encode()
    priv, pub = _key()
    (tmp_path / "manifest.json").write_bytes(mbytes)
    (tmp_path / "manifest.sig").write_bytes(update.sign_manifest(mbytes, priv))
    url = (tmp_path / "manifest.json").as_uri()
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)

    info = update.check(url)
    assert info is not None
    assert info["web"]["url"] == (tmp_path / "web.zip").as_uri()

    served = tmp_path / "served"; served.mkdir(); (served / "index.html").write_text("OLD")
    result = update.apply_web(info, served)
    assert result == "reload"
    assert (served / "index.html").read_text() == "NEW REL"


# ---------------------------------------------------------------------------
# MINOR #7 -- stage_core writes verification material for Phase 2b.
# ---------------------------------------------------------------------------

def test_stage_core_writes_verification_material_for_phase_2b(tmp_path, monkeypatch):
    """pending-core.json must carry enough for Phase 2b's on-restart
    updater to RE-VERIFY the staged bundle against the release pubkey
    before ever swapping it in -- the pinned sha256, plus the raw signed
    manifest bytes + signature (base64), not just a bare version string."""
    core = tmp_path / "core_src"; core.mkdir(); (core / "marker.txt").write_text("core 9.9.9")
    data, digest = update.build_web_bundle(core)
    (tmp_path / "core.zip").write_bytes(data)
    manifest = {"version": "9.9.9", "channel": "stable", "core_version": "9.9.9",
                "min_core_for_web": "0.0.0",
                "core": {"url": (tmp_path / "core.zip").as_uri(), "sha256": digest,
                         "size": len(data)}, "notes": "test"}
    mbytes = json.dumps(manifest).encode()
    priv, pub = _key()
    (tmp_path / "manifest.json").write_bytes(mbytes)
    (tmp_path / "manifest.sig").write_bytes(update.sign_manifest(mbytes, priv))
    url = (tmp_path / "manifest.json").as_uri()
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)

    info = update.check(url)
    assert info is not None and info["core_available"]
    staging = tmp_path / "staging"; staging.mkdir()
    update.stage_core(info, staging)

    marker = json.loads((staging / "pending-core.json").read_text())
    assert marker["version"] == "9.9.9"
    assert marker["sha256"] == digest
    manifest_bytes = base64.b64decode(marker["manifest_b64"])
    sig_bytes = base64.b64decode(marker["sig_b64"])
    assert manifest_bytes == mbytes
    assert update.verify_manifest(manifest_bytes, sig_bytes, pub) is True
