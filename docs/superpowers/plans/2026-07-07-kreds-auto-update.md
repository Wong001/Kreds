# Kreds Auto-Update Phase 2a Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A signed in-app update mechanism — verify a release-signed manifest, hot-swap the web assets (with rollback), and stage a core update — built + testable from source.

**Architecture:** `hearth/update.py` holds a baked-in Ed25519 release **public** key and verifies a signed manifest before applying anything. Tier 1 (web): download → sha256-verify → atomic swap of a writable web dir → reload. Tier 2 (core): download → verify → stage (swap-on-restart is Phase 2b). The node does the fetch/verify/filesystem work; the client just shows UI. Dev CLI signs the manifest with the offline private key.

**Tech Stack:** Python 3.12, `cryptography` (Ed25519, already a dep), FastAPI, pytest; vanilla-JS client. `node --check`.

**Spec:** `docs/superpowers/specs/2026-07-07-kreds-auto-update-design.md`

## Global Constraints

- Branch: `kreds-auto-update` off `main` (already created + checked out — do NOT re-branch).
- Quality over shortcuts. No new dependency. Test runner: `timeout 180 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3`; full suite green each commit; `node --check hearth/web/app.js` clean. ASCII-only Python prints.
- **SECURITY: verify-before-apply, always.** Nothing (web or core) is applied unless the manifest's Ed25519 signature verifies against the baked-in `RELEASE_PUBKEY` AND the downloaded bundle's sha256 matches the (signed) manifest. A bad signature / bad hash / downgrade / `min_core_for_web` mismatch → refuse, apply nothing.
- **The private release key is NEVER committed.** Generate the keypair once; bake the PUBLIC hex into `update.py`; write the PRIVATE hex to a gitignored path and tell the developer to move it offline. Tests use a SEPARATE throwaway key (never the real one) — so `verify_manifest`/`sign_manifest` take a key param defaulting to the baked-in pubkey.
- Sign over the **raw manifest.json bytes** (no JSON canonicalization) — the app verifies the signature over the exact bytes it fetched.
- Writable web dir is OPT-IN via `build_app(node, web_dir=None)` — default `None` keeps serving the source `WEB_DIR` so **dev/demo/tests are unchanged**.
- Out of scope (Phase 2b): PyInstaller/.exe, the on-restart core-swap updater, the publish pipeline, update-over-Tor.

---

### Task 1: Trust core — `hearth/update.py` signing/verify + version + dev CLI

**Files:**
- Create: `hearth/update.py`
- Modify: `hearth/__init__.py` (`__version__`), `hearth/cli.py` (`release-build`, `release-sign`), `.gitignore` (private key)
- Test: `tests/test_update_trust.py`

**Interfaces:**
- Produces: `RELEASE_PUBKEY`, `CORE_VERSION`, `verify_manifest(manifest_bytes, sig_bytes, pubkey_hex=RELEASE_PUBKEY) -> bool`, `sign_manifest(manifest_bytes, private_key_hex) -> bytes`, `build_web_bundle(web_dir) -> (bytes, sha256_hex)`, `version_lt(a, b) -> bool`.

- [ ] **Step 1: Branch exists — skip; start at Step 2.**

- [ ] **Step 2: Generate the release keypair (one-time).** Run a throwaway snippet:
  ```
  .venv/Scripts/python.exe -c "from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey; from cryptography.hazmat.primitives import serialization as s; k=Ed25519PrivateKey.generate(); import binascii; pk=k.private_bytes(s.Encoding.Raw,s.PrivateFormat.Raw,s.NoEncryption()); pub=k.public_key().public_bytes(s.Encoding.Raw,s.PublicFormat.Raw); print('PUB',pub.hex()); open('release_private_key.txt','w').write(pk.hex())"
  ```
  Put the printed PUB hex into `RELEASE_PUBKEY` in `update.py` (Step 5). Add `release_private_key.txt` (and `release_private_key*`) to `.gitignore`. In your report, tell the developer: "the release PRIVATE key is at `release_private_key.txt` — move it OFFLINE and delete it from the working dir; it is gitignored and must never be committed."

- [ ] **Step 3: Failing tests** — `tests/test_update_trust.py`:

```python
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
```

- [ ] **Step 4: Run — expect failure.**

- [ ] **Step 5: Implement `hearth/update.py` (trust half):**

```python
"""Signed in-app updates. The app carries a baked-in Ed25519 release PUBLIC key
and refuses any update whose manifest signature doesn't verify. The signed
manifest pins each bundle's sha256, so verifying the manifest authenticates the
bundles. The private release key lives OFFLINE with the developer, never here."""
import hashlib, io, zipfile
from pathlib import Path
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey, Ed25519PublicKey)

from . import __version__ as CORE_VERSION

RELEASE_PUBKEY = "<PUT THE GENERATED PUBLIC HEX HERE>"   # 64 hex chars

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

def build_web_bundle(web_dir) -> tuple:
    web_dir = Path(web_dir)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted(web_dir.rglob("*")):
            if p.is_file():
                z.write(p, p.relative_to(web_dir).as_posix())
    data = buf.getvalue()
    return data, hashlib.sha256(data).hexdigest()

def version_lt(a: str, b: str) -> bool:
    def parts(v): return [int(x) for x in v.strip().split(".")]
    return parts(a) < parts(b)
```

- [ ] **Step 6: `hearth/__init__.py`** — add `__version__ = "0.2.0"` (if `__init__` has other content, just add the line).

- [ ] **Step 7: `cli.py` — `release-build` + `release-sign`** (dev tooling):
  - `release-build --web <dir> --out <manifest.json> [--version X --web-url U]`: build the web bundle (write `web-<version>.zip` next to the manifest), assemble a manifest JSON (version, sha256, size, min_core_for_web=CORE_VERSION, etc.), write it.
  - `release-sign --manifest <manifest.json> --key <private_key_path>`: read the manifest bytes + the private key hex from the file, write `<manifest>.sig` = `sign_manifest(bytes, key)`.
  (These are developer commands; keep them simple. The point is producing a testable signed feed.)

- [ ] **Step 8: Run tests + full suite. Commit** (verify `release_private_key.txt` is gitignored + NOT staged):

```powershell
git add hearth/update.py hearth/__init__.py hearth/cli.py .gitignore tests/test_update_trust.py
git status   # confirm release_private_key.txt is NOT listed
git commit -m "feat: auto-update trust core - Ed25519 signed-manifest verify/sign + web-bundle builder + release-build/sign CLI; release pubkey baked in, private key gitignored"
```

---

### Task 2: Update client — check / apply_web / stage_core + writable web dir

**Files:**
- Modify: `hearth/update.py` (`check`, `apply_web`, `stage_core`, `FEED_URL`, `_download`)
- Modify: `hearth/api.py` (`build_app(node, web_dir=None)`), `hearth/bootstrap.py` (`build_bootstrap_app(..., web_dir=None)`)
- Test: `tests/test_update_client.py`

**Interfaces:**
- Consumes: `verify_manifest`, `version_lt`, `CORE_VERSION`.
- Produces: `check(feed_url=FEED_URL) -> dict|None`, `apply_web(info, web_dir) -> str`, `stage_core(info, staging_dir) -> None`; `build_app`/`build_bootstrap_app` `web_dir` param.

- [ ] **Step 1: Failing tests** — `tests/test_update_client.py` builds a LOCAL signed feed (a temp dir with `manifest.json`+`manifest.sig`+`web.zip`, a throwaway key, `HEARTH_UPDATE_FEED=file://...` or a fixture that fetches from the dir):

```python
import json, hashlib, os, io, zipfile, pytest
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

def test_apply_web_swaps_and_versions(tmp_path, monkeypatch):
    url, pub = _feed(tmp_path, "9.9.9")
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    served = tmp_path / "served"; served.mkdir(); (served/"index.html").write_text("OLD")
    info = update.check(url)
    update.apply_web(info, served)
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
    url, pub = _feed(tmp_path, "9.9.9", min_core="99.0.0")
    monkeypatch.setattr(update, "RELEASE_PUBKEY", pub)
    served = tmp_path / "served"; served.mkdir(); (served/"index.html").write_text("OLD")
    info = update.check(url)
    with pytest.raises(update.BadUpdate):
        update.apply_web(info, served)

def test_build_app_web_dir_param(tmp_path):
    from fastapi.testclient import TestClient
    from hearth.api import build_app
    from hearth.node import HearthNode
    (tmp_path/"index.html").write_text("<html>custom</html>"); (tmp_path/"sw.js").write_text("//x")
    node = HearthNode.create(tmp_path/"n", "W", "d")
    c = TestClient(build_app(node, web_dir=tmp_path))
    assert "custom" in c.get("/").text
```

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: Implement the client half of `hearth/update.py`:**
  - `FEED_URL = os.environ.get("HEARTH_UPDATE_FEED") or "<github releases manifest URL>"` (a placeholder GitHub Releases URL; env overrides for tests/dev).
  - `_fetch(url) -> bytes`: read `file://` or `http(s)://` via `urllib.request.urlopen` (supports the test `file://` feed).
  - `check(feed_url=FEED_URL)`: fetch `manifest.json` bytes + `manifest.sig` (sibling URL); `verify_manifest` → False? return None. Parse; `version_lt(CORE_VERSION, manifest["version"])`? build `{web_available: bool, core_available: bool, info: manifest}`; else None. Wrap fetch errors → return None (never raise to the caller/UI).
  - `apply_web(info, web_dir)`: gate `if version_lt(CORE_VERSION, info["min_core_for_web"]): raise BadUpdate("core too old")`; `_fetch(info["web"]["url"])`; `if sha256 != info["web"]["sha256"]: raise BadUpdate`; extract the zip to `web_dir.parent/".web-new"`; **atomic swap**: remove a stale `.web-bak`; `os.rename(web_dir, .web-bak)`; `os.rename(.web-new, web_dir)`; on ANY exception in the swap, restore (`.web-bak` → `web_dir`) and re-raise; write `web_dir/VERSION = info["version"]`; return `"reload"`.
  - `stage_core(info, staging_dir)`: `_fetch(info["core"]["url"])`; sha256 verify (raise BadUpdate on mismatch); write to `staging_dir/pending-core.zip` + a `pending-core.json` marker (version). (No apply here.)
- [ ] **Step 4: `api.py` + `bootstrap.py` web_dir param.** `build_app(node, web_dir=None)`: `wd = web_dir or WEB_DIR`; use `wd` for the `/static` mount, `GET /`, `GET /sw.js`. `build_bootstrap_app(data_dir, on_ready, web_dir=None)`: same. Default None → source dir (unchanged).

- [ ] **Step 5: Run tests + full suite. Commit.**

```powershell
git add hearth/update.py hearth/api.py hearth/bootstrap.py tests/test_update_client.py
git commit -m "feat: update client - signed check(), web hot-swap apply_web() (verify+rollback+min-core gate), stage_core(); build_app web_dir param"
```

---

### Task 3: Update API + client Updates UI

**Files:**
- Modify: `hearth/api.py` (`GET /api/update/check`, `POST /api/update/apply`)
- Modify: `hearth/node.py` (a small `served_web_dir` accessor / staging dir helper if needed), `hearth/web/app.js`, `index.html`, `style.css`
- Test: `tests/test_update_api.py`, `tests/test_web_assets.py`

- [ ] **Step 1: Failing tests** — `GET /api/update/check` with `HEARTH_UPDATE_FEED` unset or unreachable → `{available:false}` (200, not 500); with a local signed feed (monkeypatch pubkey + env) → `{available:true, ...}`. Asset test: an Updates UI + `/api/update/check`/`/api/update/apply` in app.js.

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: API** (in build_app; the node knows its served web dir + a staging dir under the data dir):

```python
    @app.get("/api/update/check")
    async def update_check():
        try:
            info = update.check()
        except Exception:
            return {"available": False, "error": "check failed"}
        if not info:
            return {"available": False, "current": update.CORE_VERSION}
        return {"available": True, "current": update.CORE_VERSION,
                "version": info["info"]["version"], "notes": info["info"].get("notes", ""),
                "web": info["web_available"], "core": info["core_available"]}

    @app.post("/api/update/apply")
    async def update_apply():
        info = update.check()
        if not info:
            raise HTTPException(400, "no update available")
        try:
            if info["web_available"]:
                update.apply_web(info["info"], _served_web_dir)      # the dir this app serves
                return {"applied": "web", "reload": True}
            update.stage_core(info["info"], node.data_dir / "update-staging")
            return {"staged": "core", "restart_required": True}
        except update.BadUpdate as e:
            raise HTTPException(400, str(e))
```
(`_served_web_dir` = the `web_dir` build_app resolved — capture it so apply swaps the RIGHT dir. In dev/source mode this is the repo web dir; that's fine for testing the flow, though real hot-swap only matters for the packaged writable dir.)

- [ ] **Step 4: Client Updates UI** (`app.js` + `index.html` + `style.css`): an **Updates** section in Settings/Me — a "Check for updates" button → `GET /api/update/check` → show current/available + notes; an **Apply** button → `POST /api/update/apply` → web: alert "updated, reloading" + `location.reload()`; core: "downloaded — restart Kreds to finish." Keyboard-accessible. (A subtle "update available" dot is optional.)

- [ ] **Step 5: Run tests + `node --check` + full suite. Commit.**

```powershell
git add hearth/api.py hearth/node.py hearth/web/app.js hearth/web/index.html hearth/web/style.css tests/test_update_api.py tests/test_web_assets.py
git commit -m "feat: update API (/api/update/check + /apply) + Updates UI (check/apply, web reload / core restart-notice)"
```

---

### Task 4: Integration + docs

**Files:**
- Test: `tests/test_update_integration.py`
- Modify: `README.md`, `ROADMAP.md`

- [ ] **Step 1: End-to-end (local feed) integration test** — build a signed feed (throwaway key), point a node's served web dir at a temp dir, `GET /api/update/check` → available, `POST /api/update/apply` → web applied + the served `/` now returns the new content + VERSION bumped. Assert a bad-signature feed → check reports not-available and apply refuses. Terminates fast.

- [ ] **Step 2: Full suite + `node --check`** — all pass (run twice). Dev/demo/tests still serve the source web dir; the release private key is not committed.

- [ ] **Step 3: README + ROADMAP** — document auto-update Phase 2a: signed manifest (Ed25519 release key, verify-before-apply), web-asset hot-update (no redownload) + core staging, GitHub Releases feed, the writable-web-dir model. State the **honest boundary**: this is the mechanism from source; **Phase 2b (packaging + the on-restart core-swap updater + the publish pipeline) is next**, and the app isn't a distributable `.exe` yet. Note the release private key is held offline by the developer. Increment/feature entry.

- [ ] **Step 4: Commit**

```powershell
git add tests/test_update_integration.py README.md ROADMAP.md
git commit -m "test+docs: auto-update Phase 2a integration (signed web hot-update, local feed) + ship notes; packaging = Phase 2b"
```

---

## Completion

After Task 4: whole-branch review (superpowers:requesting-code-review) — focus: **verify-before-apply is airtight** (no path applies web or core without a valid manifest signature over the fetched bytes AND a matching bundle sha256); a bad signature / wrong key / tampered manifest / bad hash / downgrade / min-core mismatch each refuse and change NOTHING; `apply_web`'s swap is atomic with a working rollback (served dir intact on any mid-swap failure); the **private release key is not committed** and tests use a throwaway key (never the real one); `check()` never raises to the UI (offline → available:false, not 500); the `web_dir=None` default leaves dev/demo/tests serving the source dir (no regression); `stage_core` verifies before staging; no secret exposed; `/api/update/*` can't be abused to write outside the served/staging dirs. Then superpowers:finishing-a-development-branch — merge to `main`, push. Next: **Phase 2b — PyInstaller packaging + the on-restart core-swap updater + build/sign/publish-to-GitHub-Releases pipeline**, then the video trimmer.
