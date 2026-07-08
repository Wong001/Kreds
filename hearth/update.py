"""Signed in-app updates. The app carries a baked-in Ed25519 release PUBLIC key
and refuses any update whose manifest signature doesn't verify. The signed
manifest pins each bundle's sha256, so verifying the manifest authenticates the
bundles. The private release key lives OFFLINE with the developer, never here."""
import base64, hashlib, io, json, os, shutil, time, urllib.request, zipfile
from pathlib import Path
from urllib.parse import urljoin
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey, Ed25519PublicKey)

from . import __version__ as CORE_VERSION

RELEASE_PUBKEY = "75600cb464259b4b82d2e93618f970667d1ae786c77ea2e2bea00bd5792f3832"   # 64 hex chars

# The kreds_updater feed: a public GitHub repo (wong001/kreds_updater) whose
# Releases host each version's signed manifest.json + sibling manifest.sig +
# web-<ver>.zip + core-<ver>.zip (hearth/cli.py's release-build/-sign/-publish
# assemble + publish them; RELEASE.md is the runbook). GitHub's
# "/releases/latest/download/<asset>" redirect always resolves to the
# newest published release's asset, so this URL is stable across releases --
# no version number to bump here. HEARTH_UPDATE_FEED overrides it for
# dev/tests (a local file:// feed, never the real release key/feed in tests).
FEED_URL = os.environ.get("HEARTH_UPDATE_FEED") or (
    "https://github.com/wong001/kreds_updater/releases/latest/download/manifest.json")

class BadUpdate(Exception):
    pass

def verify_manifest(manifest_bytes: bytes, sig_bytes: bytes,
                    pubkey_hex: str = RELEASE_PUBKEY) -> bool:
    try:
        Ed25519PublicKey.from_public_bytes(bytes.fromhex(pubkey_hex)).verify(
            sig_bytes, manifest_bytes)
        return True
    except (InvalidSignature, ValueError):
        return False

def sign_manifest(manifest_bytes: bytes, private_key_hex: str) -> bytes:
    return Ed25519PrivateKey.from_private_bytes(
        bytes.fromhex(private_key_hex.strip())).sign(manifest_bytes)

def _zip_dir(src_dir) -> bytes:
    """Deterministic (sorted-path) zip of every file under `src_dir`, each
    stored relative to `src_dir` (posix separators, so the archive is the
    same on Windows/Linux) -- shared by build_web_bundle and
    build_core_bundle, the two release-build inputs."""
    src_dir = Path(src_dir)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted(src_dir.rglob("*")):
            if p.is_file():
                z.write(p, p.relative_to(src_dir).as_posix())
    return buf.getvalue()

def build_web_bundle(web_dir) -> tuple:
    data = _zip_dir(web_dir)
    return data, hashlib.sha256(data).hexdigest()

def build_core_bundle(core_dir) -> tuple:
    """Zip a PyInstaller one-folder payload directory (e.g.
    dist/Kreds/versions/<version>/) for release -- Kreds.exe and
    _internal/ land at the zip root, matching what
    hearth.coreupdate.apply_staged_core expects on extract
    (PAYLOAD_EXE_NAME checked directly at the extracted root)."""
    data = _zip_dir(core_dir)
    return data, hashlib.sha256(data).hexdigest()

def version_lt(a: str, b: str) -> bool:
    def parts(v): return [int(x) for x in v.strip().split(".")]
    return parts(a) < parts(b)


# ---------------------------------------------------------------------------
# Update CLIENT: fetch -> verify -> apply. Every function here is written to
# the rule "verify before apply" -- a bad signature, bad hash, downgrade, or
# min-core mismatch must change NOTHING on disk. check() additionally never
# raises to its caller (offline/feed-down must read as "no update", not a
# crash in the UI).
# ---------------------------------------------------------------------------

def _fetch(url: str, timeout: float = 15, max_bytes: int | None = None) -> bytes:
    """Read a manifest/bundle from a file:// or http(s):// URL, bounded by
    `timeout` (never hang the caller forever on a slow/dead feed or origin)
    and, if `max_bytes` is given, capped at that many bytes (never read an
    unbounded stream into memory before the sha256/signature check runs --
    whole-branch review, IMPORTANT #3). Still runs synchronously on the
    request thread; moving it into a threadpool so it can't block the
    asyncio event loop under real concurrent load is a Phase-2b refinement
    now that it's at least timeout- and size-bounded."""
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        if max_bytes is None:
            return resp.read()
        data = resp.read(max_bytes + 1)
        if len(data) > max_bytes:
            raise BadUpdate("update too large")
        return data


def _sibling_url(url: str, filename: str) -> str:
    """URL for a file next to `url` (manifest.json -> manifest.sig)."""
    base, _, _ = url.rpartition("/")
    return f"{base}/{filename}"


_MAX_MANIFEST_BYTES = 1_000_000
_MAX_SIG_BYTES = 10_000


def _installed_web_version(web_dir) -> str:
    """The version currently on disk in `web_dir` (its VERSION file),
    falling back to CORE_VERSION if `web_dir` is None or has never been
    stamped (dev/demo/first-run) -- the persisted anti-rollback floor for
    web hot-updates (whole-branch review, IMPORTANT #1)."""
    if web_dir is None:
        return CORE_VERSION
    vfile = Path(web_dir) / "VERSION"
    if not vfile.exists():
        return CORE_VERSION
    return vfile.read_text().strip()


def check(feed_url: str = FEED_URL, web_dir=None) -> dict | None:
    """Fetch + verify the signed manifest at `feed_url`. Returns None if
    there's nothing new to offer, the fetch fails, the manifest doesn't
    parse, or the signature doesn't verify against RELEASE_PUBKEY -- NEVER
    raises to the caller.

    web_available and core_available are gated independently (whole-branch
    review, IMPORTANT #1 + #2):
      - web_available requires the manifest's version to be newer than the
        web bundle ACTUALLY INSTALLED in `web_dir` (read from its VERSION
        file, not CORE_VERSION) -- so a replayed old-but-signed manifest
        can't downgrade an already-updated web client, and an already-
        applied update is never re-offered. It also requires CORE_VERSION
        to already meet min_core_for_web.
      - core_available requires CORE_VERSION to be older than the
        manifest's core_version (defaulting to its version).
    A combined web+core release where the web bundle needs the NEW core
    (min_core_for_web == the release version) therefore reports
    core_available only, until a later check (after that core is applied)
    sees CORE_VERSION meet the floor and offers the web update too."""
    try:
        manifest_bytes = _fetch(feed_url, max_bytes=_MAX_MANIFEST_BYTES)
        sig_bytes = _fetch(_sibling_url(feed_url, "manifest.sig"),
                           max_bytes=_MAX_SIG_BYTES)
        if not verify_manifest(manifest_bytes, sig_bytes, RELEASE_PUBKEY):
            return None
        manifest = json.loads(manifest_bytes)

        # Resolve possibly-relative bundle URLs against the feed URL (the
        # GitHub-Releases-friendly shape: a manifest ships with a bare
        # asset filename that resolves against the manifest's own location)
        # -- whole-branch review, MINOR #5.
        for kind in ("web", "core"):
            if kind in manifest and "url" in manifest[kind]:
                manifest[kind]["url"] = urljoin(feed_url, manifest[kind]["url"])

        installed_web = _installed_web_version(web_dir)
        web_available = (
            "web" in manifest
            and version_lt(installed_web, manifest["version"])
            and not version_lt(CORE_VERSION, manifest["min_core_for_web"]))
        core_available = (
            "core" in manifest
            and version_lt(CORE_VERSION,
                           manifest.get("core_version", manifest["version"])))
        if not (web_available or core_available):
            return None
    except Exception:
        return None
    return {**manifest,
            "web_available": web_available,
            "core_available": core_available,
            "info": manifest,
            "_manifest_bytes": manifest_bytes,
            "_sig_bytes": sig_bytes}


def _rename_with_retry(src, dst, attempts: int = 30, delay: float = 0.1) -> None:
    """os.rename with short bounded retries. On Windows, renaming a
    directory fails with a transient sharing violation (PermissionError /
    WinError 5) if any file inside it still has an open handle -- e.g. a
    StaticFiles/FileResponse request that is mid-stream out of that exact
    directory (confirmed directly: apply_web's swap reliably lost this race
    under real concurrent HTTP load in tests/test_update_integration.py's
    test_apply_while_actively_serving_swaps_ok_on_windows before this fix).
    Such handles are normally released within milliseconds -- Starlette
    closes them as soon as a response finishes streaming -- so a short
    retry loop resolves the transient case without weakening atomicity: if
    every attempt is exhausted, the final OSError still propagates and the
    caller's existing rollback logic still runs unchanged."""
    for attempt in range(attempts):
        try:
            os.rename(src, dst)
            return
        except OSError:
            if attempt == attempts - 1:
                raise
            time.sleep(delay)


def apply_web(info: dict, web_dir) -> str:
    """Download, verify, and hot-swap the web bundle into `web_dir`.
    Atomic swap with rollback: if anything after the rename to .web-bak
    fails, .web-bak is restored to web_dir before re-raising, so the served
    directory is never left missing or half-written. Each rename is
    retried (see _rename_with_retry) to ride out a transient Windows
    sharing violation from a request still actively being served out of
    `web_dir` at the moment of the swap."""
    web_dir = Path(web_dir)
    if version_lt(CORE_VERSION, info["min_core_for_web"]):
        raise BadUpdate("core too old for this web bundle")

    # Re-assert the anti-rollback floor here too, independent of whatever
    # check() decided -- the persisted, on-disk VERSION file is the source
    # of truth for "is this actually newer than what's installed", so even
    # a stale/forged `info` handed straight to apply_web can't downgrade or
    # re-apply an already-applied update (whole-branch review, IMPORTANT #1).
    installed_web = _installed_web_version(web_dir)
    if not version_lt(installed_web, info["version"]):
        raise BadUpdate("not newer than installed")

    data = _fetch(info["web"]["url"], max_bytes=info["web"]["size"] + 1024)
    if hashlib.sha256(data).hexdigest() != info["web"]["sha256"]:
        raise BadUpdate("web bundle sha256 mismatch")

    new_dir = web_dir.parent / ".web-new"
    if new_dir.exists():
        shutil.rmtree(new_dir)
    new_dir.mkdir(parents=True)
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        z.extractall(new_dir)

    bak_dir = web_dir.parent / ".web-bak"
    if bak_dir.exists():
        shutil.rmtree(bak_dir)
    try:
        _rename_with_retry(web_dir, bak_dir)
        _rename_with_retry(new_dir, web_dir)
    except Exception:
        if bak_dir.exists() and not web_dir.exists():
            _rename_with_retry(bak_dir, web_dir)  # restore -- served dir stays intact
        raise

    (web_dir / "VERSION").write_text(info["version"])
    return "reload"


def stage_core(info: dict, staging_dir) -> None:
    """Download + verify a core bundle and stage it for the (Phase 2b)
    on-restart updater. Does not apply anything itself.

    pending-core.json carries not just the version but the pinned sha256
    and, when available (i.e. `info` came from check(), not a hand-built
    dict), the raw signed manifest bytes + signature (base64) -- so the
    Phase 2b on-restart updater can RE-VERIFY the staged bundle against the
    release pubkey before ever swapping it into place, instead of trusting
    a bare version string left behind by this process (whole-branch
    review, MINOR #7)."""
    staging_dir = Path(staging_dir)
    data = _fetch(info["core"]["url"], max_bytes=info["core"]["size"] + 1024)
    if hashlib.sha256(data).hexdigest() != info["core"]["sha256"]:
        raise BadUpdate("core bundle sha256 mismatch")

    staging_dir.mkdir(parents=True, exist_ok=True)
    (staging_dir / "pending-core.zip").write_bytes(data)
    marker = {"version": info["version"], "sha256": info["core"]["sha256"]}
    if "_manifest_bytes" in info and "_sig_bytes" in info:
        marker["manifest_b64"] = base64.b64encode(info["_manifest_bytes"]).decode()
        marker["sig_b64"] = base64.b64encode(info["_sig_bytes"]).decode()
    (staging_dir / "pending-core.json").write_text(json.dumps(marker))
