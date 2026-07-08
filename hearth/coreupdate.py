"""On-restart core-swap updater (Kreds Windows packaging, Phase 2b segment
2). A stable top-level launcher (packaging/launcher.py, built separately
and rarely rebuilt) calls apply_staged_core() on every start, then runs
whatever version `current` names. The running app's files are NEVER
overwritten in place: a staged update always lands in a brand-new
`versions/<version>/` directory, and only the `current` pointer moves.

SECURITY-CRITICAL: a bundle staged by hearth.update.stage_core() (feature
16) was already verified once, at stage time -- but staging is just a
write to disk, and disk contents can be tampered with between staging and
the next restart (a compromised/buggy staging dir, a crashed prior apply
that left a half-written marker, etc). apply_staged_core() therefore
RE-VERIFIES from scratch before ever extracting or flipping `current`:

    0. The marker MUST carry manifest_b64 + sig_b64 (hearth.update.
       stage_core() always writes them for the only production caller).
       A marker missing either is discarded outright -- there is no
       sha256-only fallback path. Without this, anyone with staging-dir
       write access could stage their own zip plus a marker naming that
       zip's own sha256, with no signature at all, and have it accepted.
    1. hearth.update.verify_manifest() against THIS process's own baked-in
       RELEASE_PUBKEY (never anything read from the staged material itself).
    2. The sha256 that is actually embedded in that freshly-verified
       manifest (manifest["core"]["sha256"]) must match pending-core.json's
       own "sha256" field -- so a marker whose "sha256" was independently
       rewritten to point at a different (malicious) zip, while the
       manifest_b64/sig_b64 bytes are left as a legitimately-signed but
       UNRELATED manifest, is rejected exactly like a bad signature. The
       marker's "sha256" is never trusted on its own merits.
    3. The actual sha256 of pending-core.zip on disk must match that same
       pinned hash.
    4. The marker's "version" -- used verbatim as a `versions/<version>`
       path segment that is later EXECUTED -- must be a plain
       dotted-numeric string (is_valid_version()) AND must match the
       version actually embedded in the freshly-verified manifest. Neither
       check alone is enough: syntax validation stops path traversal
       (".."/"/"\\"/drive letters), the manifest cross-check stops a
       syntactically-valid-but-mislabeled marker. A pre-existing
       `versions/<version>` directory is never trusted by bare existence
       either -- it is always cleared and re-populated with the bytes that
       were just re-verified, never assumed correct because a file named
       Kreds.exe happens to already be sitting there.

Any failure at any of these steps discards the staged material and leaves
`current` untouched -- nothing is ever extracted from a bundle that didn't
fully re-verify. packaging/launcher.py also independently re-validates
`current`'s content with the same is_valid_version() check, defense-in-
depth, right before joining it into a path and executing it.
"""
import base64
import hashlib
import io
import json
import re
import shutil
import zipfile
from pathlib import Path

from . import update

PAYLOAD_EXE_NAME = "Kreds.exe"

# A staged marker's "version" (and, defense-in-depth, install_root/current's
# content -- see packaging/launcher.py) is untrusted on-disk data that gets
# joined into a filesystem path (`versions/<version>/Kreds.exe`) and then
# EXECUTED. pathlib's `/` does not strip ".." or drive letters, and Windows
# treats "/" and "\" as equivalent separators, so an unsanitized version is
# a path-traversal + arbitrary-code-execution primitive. Require a plain
# dotted-numeric string -- this alone rules out "..", "/", "\\", ":", and
# absolute paths.
VERSION_RE = re.compile(r"\A\d+(\.\d+){1,3}\Z")


def is_valid_version(version) -> bool:
    """True iff `version` is safe to use, verbatim, as a single
    `versions/<version>` path segment."""
    return isinstance(version, str) and bool(VERSION_RE.fullmatch(version))


def _clear_staging(staging_dir: Path) -> None:
    for name in ("pending-core.zip", "pending-core.json"):
        p = staging_dir / name
        if p.exists():
            p.unlink()


def _atomic_write_text(path: Path, text: str) -> None:
    """Write `text` to `path` via a same-directory temp file + rename, so a
    reader never observes a partially-written `current`/`previous` file."""
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(text)
    tmp.replace(path)


def _flip_current(install_root: Path, version: str) -> None:
    current_file = install_root / "current"
    prev = current_file.read_text().strip() if current_file.exists() else None
    if prev and prev != version:
        _atomic_write_text(install_root / "previous", prev)
    _atomic_write_text(current_file, version)


def _extract_to_new_dir(zip_bytes: bytes, target_dir: Path) -> bool:
    """Extract into a temp sibling dir, then rename into `target_dir` only
    on full, validated success. A mid-extract failure (disk full, a
    corrupt member, an interrupted process) can therefore never leave a
    half-written `target_dir` behind, and extraction never touches an
    existing directory (it always targets a brand-new version name)."""
    tmp_dir = target_dir.with_name(target_dir.name + ".extracting")
    if tmp_dir.exists():
        shutil.rmtree(tmp_dir, ignore_errors=True)
    tmp_dir.mkdir(parents=True)
    try:
        with zipfile.ZipFile(io.BytesIO(zip_bytes)) as z:
            z.extractall(tmp_dir)
    except Exception:
        shutil.rmtree(tmp_dir, ignore_errors=True)
        return False
    if not (tmp_dir / PAYLOAD_EXE_NAME).exists():
        shutil.rmtree(tmp_dir, ignore_errors=True)
        return False
    try:
        tmp_dir.rename(target_dir)
    except OSError:
        # e.g. target_dir sprang into existence between the caller's check
        # and this rename -- fail safe (discard the validated-but-unplaced
        # extraction) rather than let an OSError escape apply_staged_core
        # uncaught and leave current in an unknown state.
        shutil.rmtree(tmp_dir, ignore_errors=True)
        return False
    return True


def apply_staged_core(install_root, staging_dir) -> str | None:
    """Re-verify + apply a core update staged by hearth.update.stage_core().

    Returns the version now current, or None if there was nothing staged,
    or re-verification/extraction failed (in which case `current` is left
    exactly as it was and nothing is extracted)."""
    install_root = Path(install_root)
    staging_dir = Path(staging_dir)

    zip_path = staging_dir / "pending-core.zip"
    json_path = staging_dir / "pending-core.json"
    if not (zip_path.exists() and json_path.exists()):
        return None

    try:
        marker = json.loads(json_path.read_text())
    except (OSError, ValueError):
        _clear_staging(staging_dir)
        return None

    version = marker.get("version")
    pinned_sha256 = marker.get("sha256")
    if not pinned_sha256 or not is_valid_version(version):
        # The marker's "version" is used, further down, as a
        # `versions/<version>` path segment that later gets executed --
        # reject anything that isn't a plain dotted-numeric string (no
        # "..", "/", "\\", drive letters, ...) before any crypto
        # re-verification even runs. Never trust it on its own merits.
        _clear_staging(staging_dir)
        return None

    manifest_b64 = marker.get("manifest_b64")
    sig_b64 = marker.get("sig_b64")
    if not (manifest_b64 and sig_b64):
        # No manifest_b64/sig_b64 staged -- there is nothing here to
        # cryptographically re-verify. hearth.update.stage_core() ALWAYS
        # writes this material (marker["manifest_b64"]/marker["sig_b64"])
        # when `info` came from check() -- the only production caller --
        # so a marker missing it is, by definition, not something this
        # process staged. Refuse it outright rather than fall back to a
        # sha256-only check pinned by the SAME untrusted marker: anyone
        # with staging-dir write access (the exact adversary this
        # module's docstring names) could otherwise just write their own
        # pending-core.zip plus a marker naming that zip's own sha256,
        # with no signature at all, and have it accepted -- a re-
        # verification bypass leading to arbitrary code execution on the
        # next restart. There is no legitimate no-signature marker.
        _clear_staging(staging_dir)
        return None
    try:
        manifest_bytes = base64.b64decode(manifest_b64)
        sig_bytes = base64.b64decode(sig_b64)
    except Exception:
        _clear_staging(staging_dir)
        return None
    # Pass update.RELEASE_PUBKEY explicitly (a call-time module-global
    # lookup) rather than relying on verify_manifest's default arg,
    # which is bound to RELEASE_PUBKEY at hearth.update's IMPORT time --
    # the same pattern hearth.update.check() itself uses internally, and
    # what lets tests substitute a throwaway key via monkeypatch.
    if not update.verify_manifest(manifest_bytes, sig_bytes, update.RELEASE_PUBKEY):
        _clear_staging(staging_dir)
        return None
    try:
        manifest = json.loads(manifest_bytes)
        manifest_sha256 = manifest["core"]["sha256"]
    except Exception:
        _clear_staging(staging_dir)
        return None
    if manifest_sha256 != pinned_sha256:
        # pending-core.json's own "sha256" field doesn't match the one
        # actually inside the just-verified, signed manifest -- the
        # marker was tampered with after staging. Reject exactly like
        # a bad signature: the marker's "sha256" is never trusted on
        # its own, only as a value that must AGREE with the manifest.
        _clear_staging(staging_dir)
        return None
    if version != manifest.get("version"):
        # Likewise, bind the marker's "version" to the version actually
        # inside the just-verified, signed manifest -- the version
        # string was already syntax-checked above, but that alone only
        # proves it's *path-safe*, not that it's the version this
        # signed bundle actually is. Reject a marker whose "version"
        # was independently rewritten (even to another syntactically
        # valid version number) while manifest_b64/sig_b64 were left
        # as a legitimately-signed but different manifest.
        _clear_staging(staging_dir)
        return None

    try:
        zip_bytes = zip_path.read_bytes()
    except OSError:
        _clear_staging(staging_dir)
        return None
    if hashlib.sha256(zip_bytes).hexdigest() != pinned_sha256:
        _clear_staging(staging_dir)
        return None

    versions_dir = install_root / "versions"
    versions_dir.mkdir(parents=True, exist_ok=True)
    target_dir = versions_dir / version

    if target_dir.exists():
        # Never trust a pre-existing versions/<version> directory's
        # content by bare existence (e.g. Kreds.exe merely being present)
        # -- it could have been planted, corrupted, or left over from a
        # half-applied prior run, independent of the zip bytes that were
        # JUST re-verified above. Always place the bytes that were just
        # verified: clear whatever is there and extract fresh, rather than
        # ever flipping `current` onto content that wasn't just checked.
        try:
            shutil.rmtree(target_dir)
        except OSError:
            # Couldn't clear it (e.g. a file inside is locked -- in
            # principle target_dir could be the currently-running
            # version). Fail safe: leave `current` untouched rather than
            # flip it onto a directory we couldn't refresh with verified
            # bytes.
            _clear_staging(staging_dir)
            return None

    if not _extract_to_new_dir(zip_bytes, target_dir):
        _clear_staging(staging_dir)
        return None

    _flip_current(install_root, version)
    _clear_staging(staging_dir)
    return version


def _version_sort_key(v: str):
    try:
        return (0, [int(x) for x in v.split(".")])
    except ValueError:
        return (1, v)


def current_version(install_root) -> str | None:
    """The active version: `install_root/current`'s content if present,
    else the newest `versions/*` directory name (version-aware sort)."""
    install_root = Path(install_root)
    current_file = install_root / "current"
    if current_file.exists():
        v = current_file.read_text().strip()
        if v:
            return v
    versions_dir = install_root / "versions"
    if not versions_dir.exists():
        return None
    candidates = [p.name for p in versions_dir.iterdir() if p.is_dir()]
    if not candidates:
        return None
    candidates.sort(key=_version_sort_key)
    return candidates[-1]


def revert_current(install_root) -> str | None:
    """Set `current` back to `previous` (used by the launcher when a
    newly-swapped version fails to even start). Returns the reverted-to
    version, or None if there was no `previous` to revert to."""
    install_root = Path(install_root)
    prev_file = install_root / "previous"
    if not prev_file.exists():
        return None
    prev = prev_file.read_text().strip()
    if not is_valid_version(prev):
        # Same untrusted-on-disk-data rule as the staged marker's
        # "version": `previous` gets written verbatim into `current`,
        # which is later joined into a `versions/<version>` path and
        # executed. Never write an unvalidated value there.
        return None
    _atomic_write_text(install_root / "current", prev)
    return prev
