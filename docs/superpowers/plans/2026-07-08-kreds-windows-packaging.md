# Kreds Windows Packaging Phase 2b Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A distributable Kreds Windows `.exe` that carries its own `tor.exe`, runs the node over Tor, and auto-updates (web hot-swap + core swap-on-restart) — for a real two-machine Tor test before public release.

**Architecture:** PyInstaller one-folder build of the pywebview shell; frozen-mode resource resolution; the node runs over Tor with a bundled `tor.exe`; the web dir is seeded to a writable `%APPDATA%\Kreds\web` (auto-updatable); a stable launcher runs a versioned payload dir so core updates swap without overwriting a running app; a build→sign→publish pipeline to `kreds_updater` Releases.

**Tech Stack:** Python 3.12, **PyInstaller (NEW build-time dep)**, pywebview, FastAPI/uvicorn, `cryptography`; pytest. `node --check`.

**Spec:** `docs/superpowers/specs/2026-07-08-kreds-windows-packaging-design.md`

## Global Constraints

- Branch: `kreds-windows-packaging` off `main` (already created + checked out — do NOT re-branch).
- **NAMING (hard rule):** every user-facing/packaged name is **Kreds** — `Kreds.exe`, `dist/Kreds/`, window "Kreds", `%APPDATA%\Kreds`, PyInstaller `name="Kreds"`, ProductName/CompanyName = Kreds, `kreds_updater`. Only the internal python module stays `hearth`.
- Quality over shortcuts. Full suite green each commit; `node --check` clean. ASCII-only Python prints. Frozen-mode behavior applies ONLY when frozen (`sys.frozen`) / desktop launch — **dev / demo / `serve` / tests are UNCHANGED**.
- **SECURITY:** the core-swap updater MUST re-verify the staged update (Ed25519 manifest signature + bundle sha256, reusing feature-16's staged material) BEFORE swapping; a bad/failed swap changes `current` to nothing (rollback); the release private key is never committed.
- GUI/`.exe`/real-Tor/real-update = the USER verifies (can't headless-test); Claude tests the build-produces-and-launches, the frozen-path resolution, the core-swap logic, and the pipeline assembly.
- Out of scope (public follow-ups): Authenticode, Inno Setup installer, WebView2 bootstrapper, macOS/Linux, CI builds.

---

### Task 1: Frozen paths + desktop Tor/seed/single-instance/freeze-harden

**Files:**
- Create: `hearth/paths.py`, `hearth/web/VERSION`
- Modify: `hearth/desktop.py` (launch: seed web, Tor-on + bundled tor, single-instance, harden), `hearth/tor.py` (`ensure_tor_binary` frozen-default bundled dir)
- Test: `tests/test_paths.py`, `tests/test_desktop.py` (extend)

**Interfaces:**
- Produces: `paths.resource_dir()`, `paths.bundled_web_dir()`, `paths.bundled_tor_dir()`, `paths.is_frozen()`; `paths.seed_web_dir(dst)`; a single-instance guard.

- [ ] **Step 1: Branch exists — skip; start at Step 2.**

- [ ] **Step 2: Failing tests** — `tests/test_paths.py`:

```python
import sys
from pathlib import Path
import hearth.paths as paths

def test_not_frozen_uses_repo(monkeypatch):
    monkeypatch.setattr(paths, "is_frozen", lambda: False)
    assert paths.bundled_web_dir().name == "web"
    assert (paths.bundled_web_dir() / "index.html").exists()   # the repo web dir

def test_resource_dir_frozen(monkeypatch, tmp_path):
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "_MEIPASS", str(tmp_path), raising=False)
    monkeypatch.setattr(paths, "is_frozen", lambda: True)
    assert paths.resource_dir() == tmp_path

def test_seed_web_dir_copies_when_missing(tmp_path, monkeypatch):
    src = tmp_path / "bundled-web"; src.mkdir()
    (src / "index.html").write_text("hi"); (src / "VERSION").write_text("0.2.0")
    monkeypatch.setattr(paths, "bundled_web_dir", lambda: src)
    dst = tmp_path / "appdata-web"
    paths.seed_web_dir(dst)
    assert (dst / "index.html").read_text() == "hi"
    # idempotent: a newer seeded VERSION is not clobbered by an equal/older bundle
    (dst / "index.html").write_text("UPDATED"); (dst / "VERSION").write_text("9.9.9")
    paths.seed_web_dir(dst)
    assert (dst / "index.html").read_text() == "UPDATED"
```

- [ ] **Step 3: Run — expect failure.**

- [ ] **Step 4: `hearth/paths.py`:**

```python
"""Resource paths that differ when PyInstaller-frozen vs running from source.
Non-frozen returns the repo layout so dev/demo/tests are unchanged."""
import shutil, sys
from pathlib import Path

def is_frozen() -> bool:
    return getattr(sys, "frozen", False)

def resource_dir() -> Path:
    if is_frozen():
        return Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
    return Path(__file__).parent          # the hearth package (repo)

def bundled_web_dir() -> Path:
    return (resource_dir() / "hearth" / "web") if is_frozen() \
        else (Path(__file__).parent / "web")

def bundled_tor_dir() -> Path:
    # ensure_tor_binary looks for <dir>/tor/tor.exe
    return resource_dir()

def _read_version(d: Path) -> str:
    f = d / "VERSION"
    return f.read_text().strip() if f.exists() else "0.0.0"

def seed_web_dir(dst: Path) -> None:
    """Copy the bundled web assets into the writable dst when dst is missing
    or older than the bundle (so auto-update's newer VERSION is never
    overwritten by an equal/older bundled seed)."""
    from .update import version_lt          # reuse the version compare
    src = bundled_web_dir()
    if dst.exists() and not version_lt(_read_version(dst), _read_version(src)):
        return
    tmp = dst.with_name(dst.name + ".seed-new")
    if tmp.exists(): shutil.rmtree(tmp)
    shutil.copytree(src, tmp)
    if dst.exists(): shutil.rmtree(dst)
    tmp.rename(dst)
```

- [ ] **Step 5: `hearth/web/VERSION`** — write `0.2.0` (matches `hearth.__version__`; the seed/update version marker).

- [ ] **Step 6: `hearth/tor.py`** — `ensure_tor_binary(bundled_dir=None, ...)`: when `bundled_dir is None`, default to `paths.bundled_tor_dir()` if frozen (so a packaged run finds the bundled `tor.exe`); non-frozen keeps today's behavior (None → cache/download). Import `paths` lazily to avoid a cycle.

- [ ] **Step 7: `hearth/desktop.py` launch changes:**
  - Data dir default stays `%APPDATA%\Kreds`. Compute a writable web dir `web = data_dir / "web"`; `paths.seed_web_dir(web)` before starting the node.
  - The node thread runs `run_serve(data_dir, 0, port, tor=paths.is_frozen(), shutdown=ev)` — **Tor ON when packaged** (frozen); non-frozen `hearth app` from source stays plain TCP (fast dev). Pass the writable web dir into serving: `run_serve`/`run_node`→`build_app` must serve from `web` — add a `web_dir` param to `run_serve`/`run_node` (default None = source), and desktop passes `web`. (Thread the param through, mirroring feature-16's `build_app(web_dir=...)`.)
  - **Single-instance lock:** before starting, acquire a Win32 named mutex (`CreateMutexW("Global\\Kreds-<hash of data_dir>")`) or an exclusive lockfile `data_dir/instance.lock`; if already held → show a small "Kreds is already running" and exit (0). Release on quit.
  - **Freeze-harden:** bound the node-thread startup — if `_wait_http` (raise its timeout to ~60s for the Tor-enabled start) fails OR the node thread died (`not t.is_alive()`), write the traceback to `data_dir/app.log` and open a minimal error page/dialog instead of a frozen blank window; never spin forever.

- [ ] **Step 8: Run tests + full suite + `node --check`. Commit.**

```powershell
git add hearth/paths.py hearth/web/VERSION hearth/desktop.py hearth/tor.py tests/test_paths.py tests/test_desktop.py
git commit -m "feat: frozen-mode resource paths + desktop runs the node over Tor with bundled tor.exe, seeds writable web dir, single-instance lock, freeze-harden"
```

---

### Task 2: PyInstaller one-folder build

**Files:**
- Create: `packaging/kreds_main.py`, `packaging/kreds.spec`, `packaging/build.ps1`, `packaging/README.md`
- Modify: `.gitignore` (build/ dist/), `requirements-dev.txt` (or a note: `pyinstaller`)

- [ ] **Step 1: `pip install pyinstaller`** (build-time dep; add to `requirements-dev.txt` / note in packaging/README).
- [ ] **Step 2: `packaging/kreds_main.py`** — the frozen entry: `from hearth.desktop import launch; launch()` (with a top-level try/except writing `%APPDATA%\Kreds\app.log` on failure).
- [ ] **Step 3: `packaging/kreds.spec`** — one-folder (`COLLECT`), `name="Kreds"`, `console=False`, an icon; `datas` include `hearth/web` (→ `hearth/web`), the `tor/tor.exe` dir (→ `tor`), the imageio_ffmpeg binary; `hiddenimports` for uvicorn (`uvicorn.protocols...`, `uvicorn.lifespan...`), `pywebview`'s edgechromium backend, `cryptography`, `imageio_ffmpeg`, `websockets`. Windows version metadata: ProductName/CompanyName/FileDescription = Kreds.
- [ ] **Step 4: `packaging/build.ps1`** — obtain a `tor.exe` into `packaging/tor/tor.exe` (reuse `hearth.tor.ensure_tor_binary` to fetch/cache one, then copy), run `pyinstaller packaging/kreds.spec`, print `dist/Kreds`.
- [ ] **Step 5: Build + launch smoke (iterate).** Run `build.ps1`; then start `dist/Kreds/Kreds.exe` headless-ish and poll a bootstrap port, OR (since it opens a window) start it, wait, and confirm the process stays up + a local port answers, then kill it. **Add missing `hiddenimports`/`datas` as PyInstaller/runtime errors surface — iterate until `dist/Kreds/Kreds.exe` launches to first-run without a missing-module crash.** Record the iterations + the final working spec in `packaging/README.md`. (The *window* is August's to verify.)
- [ ] **Step 6: Full suite still green** (the build files don't affect the suite). **Commit.**

```powershell
git add packaging/ .gitignore requirements-dev.txt
git commit -m "feat: PyInstaller one-folder Kreds build (bundles tor.exe + web + ffmpeg) - launches to first-run"
```

---

### Task 3: Core-swap-on-restart (launcher + versioned payload)

**Files:**
- Create: `hearth/coreupdate.py`, `packaging/launcher.py` (+ its own tiny PyInstaller target or a stub)
- Modify: `packaging/build.ps1` (produce the launcher + `versions/<ver>/` layout), `hearth/desktop.py` (restart-to-finish relaunches the launcher)
- Test: `tests/test_coreupdate.py`

**Interfaces:**
- Produces: `coreupdate.apply_staged_core(install_root, staging_dir) -> str|None` (the version now current, or None if nothing/failed), `coreupdate.current_version(install_root)`.

- [ ] **Step 1: Failing tests** — `tests/test_coreupdate.py` (pure temp-dir logic; build a staged bundle with a throwaway key like the feature-16 update tests):

```python
# stage a signed pending-core (manifest+sig+zip) for a NEW version, then:
def test_apply_staged_core_swaps_and_flips_current(tmp_path): ...
    # extracts versions/<newver>/Kreds.exe, current -> newver, keeps prior, clears staging
def test_apply_staged_core_rejects_bad_sig(tmp_path): ...     # current unchanged, nothing extracted
def test_apply_staged_core_rejects_bad_hash(tmp_path): ...
def test_apply_staged_core_none_when_no_pending(tmp_path): ...
def test_rollback_reverts_current_on_failed_extract(tmp_path): ...  # simulate a mid-extract failure
```

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: `hearth/coreupdate.py`:**
  - `apply_staged_core(install_root, staging_dir)`: if `staging_dir/pending-core.zip` + `pending-core.json` absent → return None. Read `pending-core.json` (has the signed `manifest` bytes + `sig` + `sha256` + `version` staged by feature-16's `stage_core`; if feature-16 stored only `{version}`, EXTEND `stage_core` to also store the manifest bytes + sig + sha256 — do that here so re-verify is possible). **Re-verify:** `update.verify_manifest(manifest_bytes, sig)` (False → discard staging, return None); sha256 of `pending-core.zip` vs the pinned hash (mismatch → discard, return None). Extract the zip to `install_root/versions/<version>` (a NEW dir; if it exists, treat as already-applied); validate the expected `Kreds.exe` exists (else discard the dir, return None). Atomically write `install_root/current` = `<version>` (keep the prior value as `previous`). Clear the staging dir. Return `<version>`.
  - `current_version(install_root)`: read `install_root/current` (fallback: the newest `versions/*`).
  - Rollback helper `revert_current(install_root)`: set `current` back to `previous` (used by the launcher if a swapped version fails to start).
- [ ] **Step 4: `packaging/launcher.py`** — a tiny stable stub: `apply_staged_core(install_root, appdata/update-staging)`; then `subprocess` run `install_root/versions/<current>/Kreds.exe`; if it exits with a crash marker within a bound, `revert_current` + rerun the prior. (Its own one-file PyInstaller target `Kreds-launcher` → shipped as the top-level `Kreds.exe`; the payload exe is inside `versions/<ver>/`.)
- [ ] **Step 5: `build.ps1`** — restructure the output: `dist/Kreds/` = the launcher (`Kreds.exe`) + `versions/<CORE_VERSION>/` (the payload one-folder build) + a `current` file = `<CORE_VERSION>`. Update `packaging/README.md`.
- [ ] **Step 6: `desktop.py` restart-to-finish** — the shell action that relaunches: spawn the top-level launcher `Kreds.exe` (detached) and quit, so the launcher applies the staged update on the fresh start.
- [ ] **Step 7: Run tests + full suite. Commit.**

```powershell
git add hearth/coreupdate.py packaging/ hearth/desktop.py tests/test_coreupdate.py
git commit -m "feat: on-restart core-swap - re-verify + extract versioned payload + flip current + rollback; stable launcher runs versions/<current>/Kreds.exe"
```

---

### Task 4: Build → sign → publish pipeline + FEED_URL

**Files:**
- Modify: `hearth/cli.py` (`release-build` assembles web+core+manifest; `release-publish`), `hearth/update.py` (`FEED_URL`), `hearth/update.py` `stage_core` (store the manifest+sig for 2b-2 re-verify, if not already)
- Create: `RELEASE.md`
- Test: `tests/test_release_pipeline.py`

- [ ] **Step 1: Failing test** — `release-build` produces a `release/manifest.json` pinning the real sha256 of the assembled `web-<ver>.zip` + `core-<ver>.zip`; signing it (throwaway key) + `update.check()` against the assembled local feed reports available; the private key is not committed.
- [ ] **Step 2: Run — expect failure.**
- [ ] **Step 3: Implement.**
  - `release-build --version <v> --web <hearth/web> --core <dist/Kreds/versions/<v>> --out release/`: build the web bundle (`build_web_bundle`), zip the core payload, compute sha256/size, assemble `manifest.json` with `web`/`core` `{url, sha256, size}` (urls = the `kreds_updater` release-asset URLs for tag `v<v>`), `min_core_for_web`, `notes`. Write bundles + manifest into `release/`.
  - `release-sign` already exists (feature 16) — sign `release/manifest.json` with the offline key.
  - `release-publish --version <v> --dir release/` (optional; else document `gh release create v<v> release/* --repo wong001/kreds_updater`): upload manifest.json + .sig + both zips as assets.
  - `FEED_URL` → the stable `kreds_updater` latest-`manifest.json` URL (a release-asset or a raw pointer; `HEARTH_UPDATE_FEED` override for tests).
  - Ensure feature-16 `stage_core` stores the signed `manifest` bytes + `sig` + `sha256` in `pending-core.json` (needed by 2b-2's re-verify) — add if missing.
- [ ] **Step 4: `RELEASE.md`** — the runbook: build the .exe → `release-build` → `release-sign` (with the book-key, offline) → `release-publish`/`gh` → verify a client updates. Emphasize the key stays offline.
- [ ] **Step 5: Run tests + full suite. Commit.**

```powershell
git add hearth/cli.py hearth/update.py RELEASE.md tests/test_release_pipeline.py
git commit -m "feat: release build->sign->publish pipeline to kreds_updater (assemble web+core bundles + signed manifest) + FEED_URL"
```

---

### Task 5: Integration + docs

**Files:**
- Test: `tests/test_packaging_integration.py`
- Modify: `README.md`, `ROADMAP.md`

- [ ] **Step 1: Integration test** — the end-to-end logic (no real .exe/GUI): a signed release feed (throwaway key) with BOTH a web + a core bundle → `check()` reports both → `apply_web` swaps a temp served web dir → `stage_core` stages the signed core → `coreupdate.apply_staged_core` re-verifies + extracts a versioned payload + flips `current`; a tampered feed → all refused. Terminates fast.
- [ ] **Step 2: Full suite + `node --check`** (run twice). Dev/demo/`serve`/tests all unchanged (frozen paths inert when not frozen). Release private key not committed.
- [ ] **Step 3: README + ROADMAP** — document the Kreds Windows app: `Kreds.exe` (frameless, Tor-bundled, runs the node over Tor, `%APPDATA%\Kreds`, single-instance), the auto-update (web hot-swap + core swap-on-restart via the launcher/versioned-payload, signed, from `kreds_updater`), and the RELEASE runbook. State honestly: **the friend-test build is unsigned (SmartScreen warning); Authenticode + an Inno Setup installer + macOS are public-release follow-ups.** Move Windows packaging to shipped.
- [ ] **Step 4: Commit**

```powershell
git add tests/test_packaging_integration.py README.md ROADMAP.md
git commit -m "test+docs: Windows packaging Phase 2b integration (web+core update chain) + ship notes; Authenticode/installer/macOS = public follow-ups"
```

---

## Completion

After Task 5: whole-branch review (superpowers:requesting-code-review) — focus: frozen-mode paths are inert when not frozen (dev/demo/`serve`/tests fully unchanged); the desktop app runs over Tor with the bundled `tor.exe` (no download) and seeds/serves the writable web dir; single-instance can't run two nodes on one data dir; the freeze-harden surfaces errors instead of hanging; **the core-swap re-verifies the staged signed manifest + sha256 BEFORE swapping and rolls back on any bad/failed apply (a running app is never overwritten)**; the launcher runs `versions/<current>` and can revert; the pipeline's manifest pins the real bundle hashes and `stage_core` stores the material for re-verify; the release private key is not committed anywhere; every user-facing/packaged name is **Kreds**; no security regression to app-lock/onboarding/update. Then superpowers:finishing-a-development-branch — merge to `main`, push. Then: **the real two-machine Tor test (August + Josh)** + fixes, then the website + remaining platform work.
