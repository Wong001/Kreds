"""GET /api/update/check + POST /api/update/apply -- the update.py trust
core (Tasks 1-2) wired into the node's HTTP API. check() never raises to
its caller, but the endpoint wraps it in try/except anyway (belt-and-
braces against a future check() regression) and apply must turn ANY
verify/gate/malformed-manifest failure into a 400, never a 500.

update.check's feed_url defaults to the module-level FEED_URL, bound at
import time -- monkeypatching HEARTH_UPDATE_FEED after import has no
effect on it, so these tests monkeypatch check()'s own __defaults__
tuple directly (a bogus/valid file:// URL) instead, exactly like they'd
monkeypatch any other already-bound default argument."""
import base64
import hashlib
import json
import threading
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import serialization as _s
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from fastapi.testclient import TestClient

from hearth import update
from hearth.api import build_app
from hearth.node import HearthNode


def _key():
    k = Ed25519PrivateKey.generate()
    return (k.private_bytes(_s.Encoding.Raw, _s.PrivateFormat.Raw, _s.NoEncryption()).hex(),
            k.public_key().public_bytes(_s.Encoding.Raw, _s.PublicFormat.Raw).hex())


def _feed(tmp_path, version, min_core="0.0.0", notes="release notes",
         include_web=True, include_core=False):
    manifest = {"version": version, "channel": "stable", "core_version": version,
                "min_core_for_web": min_core, "notes": notes}
    if include_web:
        web = tmp_path / "src"; web.mkdir()
        (web / "index.html").write_text("NEW " + version)
        data, digest = update.build_web_bundle(web)
        (tmp_path / "web.zip").write_bytes(data)
        manifest["web"] = {"url": (tmp_path / "web.zip").as_uri(),
                            "sha256": digest, "size": len(data)}
    if include_core:
        core = tmp_path / "core_src"; core.mkdir()
        (core / "marker.txt").write_text("core " + version)
        data, digest = update.build_web_bundle(core)
        (tmp_path / "core.zip").write_bytes(data)
        manifest["core"] = {"url": (tmp_path / "core.zip").as_uri(),
                             "sha256": digest, "size": len(data)}
    mbytes = json.dumps(manifest).encode()
    priv, pub = _key()
    (tmp_path / "manifest.json").write_bytes(mbytes)
    (tmp_path / "manifest.sig").write_bytes(update.sign_manifest(mbytes, priv))
    return (tmp_path / "manifest.json").as_uri(), pub


def _client(tmp_path, served):
    node = HearthNode.create(tmp_path / "node", "W", "d")
    return TestClient(build_app(node, web_dir=served)), node


def test_check_offline_returns_available_false_not_500(tmp_path, monkeypatch):
    bogus = (tmp_path / "nope" / "manifest.json").as_uri()
    monkeypatch.setattr(update.check, "__defaults__", (bogus, None))
    served = tmp_path / "served"; served.mkdir(); (served / "index.html").write_text("OLD")
    client, node = _client(tmp_path, served)
    r = client.get("/api/update/check")
    assert r.status_code == 200
    d = r.json()
    assert d["available"] is False and d["current"] == update.CORE_VERSION


def test_check_never_500s_even_on_check_exception(tmp_path, monkeypatch):
    def boom(feed_url=None):
        raise RuntimeError("simulated crash inside check()")
    monkeypatch.setattr(update, "check", boom)
    served = tmp_path / "served"; served.mkdir(); (served / "index.html").write_text("OLD")
    client, node = _client(tmp_path, served)
    r = client.get("/api/update/check")
    assert r.status_code == 200
    d = r.json()
    assert d["available"] is False and d["error"] == "check failed"


def test_check_with_signed_local_feed_reports_available(tmp_path, monkeypatch):
    url, pub = _feed(tmp_path, "9.9.9")
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    monkeypatch.setattr(update.check, "__defaults__", (url, None))
    served = tmp_path / "served"; served.mkdir(); (served / "index.html").write_text("OLD")
    client, node = _client(tmp_path, served)
    r = client.get("/api/update/check")
    assert r.status_code == 200
    body = r.json()
    assert body["available"] is True
    assert body["current"] == update.CORE_VERSION
    assert body["version"] == "9.9.9"
    assert body["notes"] == "release notes"
    assert body["web"] is True
    assert body["core"] is False


def test_apply_no_update_available_is_400(tmp_path, monkeypatch):
    bogus = (tmp_path / "nope" / "manifest.json").as_uri()
    monkeypatch.setattr(update.check, "__defaults__", (bogus, None))
    served = tmp_path / "served"; served.mkdir(); (served / "index.html").write_text("OLD")
    client, node = _client(tmp_path, served)
    r = client.post("/api/update/apply")
    assert r.status_code == 400


def test_apply_web_swaps_the_actually_served_dir(tmp_path, monkeypatch):
    url, pub = _feed(tmp_path, "9.9.9")
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    monkeypatch.setattr(update.check, "__defaults__", (url, None))
    served = tmp_path / "served"; served.mkdir(); (served / "index.html").write_text("OLD")
    client, node = _client(tmp_path, served)
    r = client.post("/api/update/apply")
    assert r.status_code == 200
    assert r.json() == {"applied": "web", "reload": True}
    # the SERVED dir (build_app's wd), not some other path, got swapped
    assert (served / "index.html").read_text().startswith("NEW")
    assert (served / "VERSION").read_text().strip() == "9.9.9"


# ---------------------------------------------------------------------------
# Whole-branch review, Finding 1 -- update_status must be CLEARED after a
# successful web apply. Otherwise: a web update applies -> location.reload()
# -> the fresh client fetches /api/state, which still reports the stale
# {"available": True, "kind": "web"} (only maybe_check_update's own tick,
# up to UPDATE_CHECK_INTERVAL away, ever rewrites update_status) -> the
# just-updated client re-shows "A Kreds update is ready" -> a click re-checks,
# gets None (version now current), 400s "no update available", and the
# banner button is left dead. The staged-CORE branch is deliberately NOT
# touched: a core update restarts the process, whose own first post-restart
# check clears update_status naturally, and the banner honestly still
# "applies" (restart pending) until then.
# ---------------------------------------------------------------------------

class _FakeQueue:
    """Stands in for the asyncio.Queue node.notify() pushes to -- just
    counts puts, no event loop required."""
    def __init__(self):
        self.puts = 0

    def put_nowait(self, item):
        self.puts += 1


def test_apply_web_success_clears_update_status_and_notifies(tmp_path, monkeypatch):
    url, pub = _feed(tmp_path, "9.9.9")
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    monkeypatch.setattr(update.check, "__defaults__", (url, None))
    served = tmp_path / "served"; served.mkdir(); (served / "index.html").write_text("OLD")
    client, node = _client(tmp_path, served)
    # Simulate the stale status maybe_check_update left behind from its
    # last tick, still sitting there when the apply comes in.
    node.update_status = {"available": True, "kind": "web", "version": "9.9.9"}
    q = _FakeQueue()
    node.subscribers.add(q)

    r = client.post("/api/update/apply")

    assert r.status_code == 200
    assert r.json() == {"applied": "web", "reload": True}
    assert node.update_status == {"available": False, "kind": None,
                                  "version": None}
    assert q.puts >= 1        # notify() pushed the cleared status out over /ws


def test_apply_core_stage_does_not_clear_update_status(tmp_path, monkeypatch):
    # Contrast case: the staged-core branch must NOT clear update_status --
    # the process restarts into the new version, whose own first check
    # clears it naturally; clearing it here would make the banner vanish
    # for the (possibly long) window before that restart actually happens.
    url, pub = _feed(tmp_path, "9.9.9", include_web=False, include_core=True)
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    monkeypatch.setattr(update.check, "__defaults__", (url, None))
    served = tmp_path / "served"; served.mkdir(); (served / "index.html").write_text("OLD")
    client, node = _client(tmp_path, served)
    node.update_status = {"available": True, "kind": "core", "version": "9.9.9"}

    r = client.post("/api/update/apply")

    assert r.status_code == 200
    assert r.json() == {"staged": "core", "restart_required": True}
    assert node.update_status == {"available": True, "kind": "core",
                                  "version": "9.9.9"}


def test_apply_core_only_stages_and_reports_restart_required(tmp_path, monkeypatch):
    url, pub = _feed(tmp_path, "9.9.9", include_web=False, include_core=True)
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    monkeypatch.setattr(update.check, "__defaults__", (url, None))
    served = tmp_path / "served"; served.mkdir(); (served / "index.html").write_text("OLD")
    client, node = _client(tmp_path, served)
    r = client.post("/api/update/apply")
    assert r.status_code == 200
    assert r.json() == {"staged": "core", "restart_required": True}
    staging = node.data_dir / "update-staging"
    assert (staging / "pending-core.zip").exists()
    marker = json.loads((staging / "pending-core.json").read_text())
    assert marker["version"] == "9.9.9"
    # web dir untouched -- this was a core-only manifest
    assert (served / "index.html").read_text() == "OLD"


def test_apply_bad_hash_web_bundle_is_400_not_500(tmp_path, monkeypatch):
    url, pub = _feed(tmp_path, "9.9.9")
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    monkeypatch.setattr(update.check, "__defaults__", (url, None))
    # corrupt the bundle after signing so the manifest's pinned hash no
    # longer matches -- apply_web must raise BadUpdate, caught -> 400.
    (tmp_path / "web.zip").write_bytes(b"corrupted")
    served = tmp_path / "served"; served.mkdir(); (served / "index.html").write_text("OLD")
    client, node = _client(tmp_path, served)
    r = client.post("/api/update/apply")
    assert r.status_code == 400
    assert (served / "index.html").read_text() == "OLD"      # untouched on reject


def test_apply_malformed_signed_manifest_generic_exception_is_400(tmp_path, monkeypatch):
    """A signed manifest that verifies fine and legitimately offers a core
    update (core_available True), but whose "core" bundle sub-dict is
    missing the fields stage_core needs (no url/sha256/size), makes
    stage_core raise a bare KeyError, not update.BadUpdate -- the endpoint
    must still turn that into a 400, never an unhandled 500. (A manifest
    with neither "web" nor "core" at all is now refused earlier, by
    check() itself -- see test_apply_no_update_available_is_400 -- so this
    needs a "core" key present, just an incomplete one, to reach stage_core
    at all.)"""
    version = "9.9.9"
    manifest = {"version": version, "channel": "stable", "core_version": version,
                "min_core_for_web": "0.0.0", "notes": "malformed", "core": {}}
    mbytes = json.dumps(manifest).encode()
    priv, pub = _key()
    (tmp_path / "manifest.json").write_bytes(mbytes)
    (tmp_path / "manifest.sig").write_bytes(update.sign_manifest(mbytes, priv))
    url = (tmp_path / "manifest.json").as_uri()
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    monkeypatch.setattr(update.check, "__defaults__", (url, None))
    served = tmp_path / "served"; served.mkdir(); (served / "index.html").write_text("OLD")
    client, node = _client(tmp_path, served)
    r = client.post("/api/update/apply")
    assert r.status_code == 400


# ---------------------------------------------------------------------------
# IMPORTANT #2 -- a combined web+core release (the web bundle needs the NEW
# core) must stage the core, not dead-end 400ing out of apply_web's own
# min-core gate.
# ---------------------------------------------------------------------------

def test_apply_combined_web_and_core_stages_core_not_400(tmp_path, monkeypatch):
    url, pub = _feed(tmp_path, "9.9.9", min_core="9.9.9",
                     include_web=True, include_core=True)
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    monkeypatch.setattr(update.check, "__defaults__", (url, None))
    served = tmp_path / "served"; served.mkdir(); (served / "index.html").write_text("OLD")
    client, node = _client(tmp_path, served)

    r = client.get("/api/update/check")
    assert r.status_code == 200
    body = r.json()
    assert body["available"] is True and body["core"] is True and body["web"] is False

    r2 = client.post("/api/update/apply")
    assert r2.status_code == 200
    assert r2.json() == {"staged": "core", "restart_required": True}
    staging = node.data_dir / "update-staging"
    assert (staging / "pending-core.zip").exists()
    # web NOT touched -- this release needs a core restart before the web
    # bundle (which requires that new core) can even be offered.
    assert (served / "index.html").read_text() == "OLD"


# ---------------------------------------------------------------------------
# IMPORTANT #3 -- concurrent /api/update/apply calls must serialize, not
# race the same on-disk swap/stage.
# ---------------------------------------------------------------------------

def test_concurrent_apply_calls_serialize_via_lock(tmp_path, monkeypatch):
    """Two concurrent POST /api/update/apply for the SAME single available
    web update must not race: the lock makes them run one at a time, so
    exactly one actually applies (200) and the other then finds nothing
    left to apply (400) -- not two concurrent apply_web()s stepping on the
    same .web-new/.web-bak swap."""
    url, pub = _feed(tmp_path, "9.9.9")
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    monkeypatch.setattr(update.check, "__defaults__", (url, None))
    served = tmp_path / "served"; served.mkdir(); (served / "index.html").write_text("OLD")
    node = HearthNode.create(tmp_path / "node", "W", "d")
    app = build_app(node, web_dir=served)

    # Two concurrent applies against the ONE app in ONE event loop (a real
    # server's model). asyncio.gather interleaves them; the async update_lock
    # serializes the apply critical section (the blocking swap runs in a
    # threadpool so it can't block the loop and defeat the lock). The loser
    # re-checks inside the lock, sees the just-bumped web VERSION, and 400s --
    # so no two apply_web()s ever race the same .web-new/.web-bak swap.
    import asyncio, httpx

    async def scenario():
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://t") as c:
            r1, r2 = await asyncio.gather(
                c.post("/api/update/apply"), c.post("/api/update/apply"))
        return sorted([r1.status_code, r2.status_code])

    assert asyncio.run(scenario()) == [200, 400]
    assert (served / "index.html").read_text().startswith("NEW")


# ---------------------------------------------------------------------------
# MINOR #4 -- wider apply catch list: ValueError and zipfile.BadZipFile ->
# 400, never an unhandled 500.
# ---------------------------------------------------------------------------

def test_apply_corrupted_nonzip_bundle_is_400_not_500(tmp_path, monkeypatch):
    """A signed manifest whose pinned sha256 matches CORRUPTED (non-zip)
    bundle bytes -- e.g. a legitimate release whose build artifact got
    mangled after signing -- makes apply_web's zipfile.ZipFile(...) raise
    zipfile.BadZipFile. Must be a 400, never an unhandled 500."""
    version = "9.9.9"
    bad_bytes = b"not a zip file, just garbage bytes" * 10
    digest = hashlib.sha256(bad_bytes).hexdigest()
    (tmp_path / "web.zip").write_bytes(bad_bytes)
    manifest = {"version": version, "channel": "stable", "core_version": version,
                "min_core_for_web": "0.0.0",
                "web": {"url": (tmp_path / "web.zip").as_uri(), "sha256": digest,
                        "size": len(bad_bytes)}, "notes": "corrupted build"}
    mbytes = json.dumps(manifest).encode()
    priv, pub = _key()
    (tmp_path / "manifest.json").write_bytes(mbytes)
    (tmp_path / "manifest.sig").write_bytes(update.sign_manifest(mbytes, priv))
    url = (tmp_path / "manifest.json").as_uri()
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    monkeypatch.setattr(update.check, "__defaults__", (url, None))
    served = tmp_path / "served"; served.mkdir(); (served / "index.html").write_text("OLD")
    client, node = _client(tmp_path, served)
    r = client.post("/api/update/apply")
    assert r.status_code == 400
    assert (served / "index.html").read_text() == "OLD"


def test_apply_malformed_min_core_field_is_400_not_500(tmp_path, monkeypatch):
    """A field that's syntactically valid JSON but not a valid dotted
    version (e.g. a release-tooling bug -- signature verification only
    guarantees byte integrity, not semantic validity of fields) makes
    apply_web's own version_lt() call raise a bare ValueError. Must be a
    400, never an unhandled 500."""
    def fake_check(feed_url=None, web_dir=None):
        return {"version": "9.9.9", "min_core_for_web": "not-a-version",
                "web_available": True, "core_available": False,
                "web": {"url": "http://example.invalid/x.zip",
                        "sha256": "0" * 64, "size": 10}}
    monkeypatch.setattr(update, "check", fake_check)
    served = tmp_path / "served"; served.mkdir(); (served / "index.html").write_text("OLD")
    client, node = _client(tmp_path, served)
    r = client.post("/api/update/apply")
    assert r.status_code == 400
    assert (served / "index.html").read_text() == "OLD"
