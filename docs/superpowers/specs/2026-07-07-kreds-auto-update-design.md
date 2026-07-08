# Kreds Auto-Update (Phase 2a: signed update mechanism) — Design

**Date:** 2026-07-07
**Status:** Approved (design discussion, this session)
**Basis:** Kreds = Python node (FastAPI, `build_app(node)` serves the web client from module-level `WEB_DIR = Path(__file__).parent/"web"`) + a pywebview desktop shell (feature 15). Ed25519 is already in the stack (`cryptography`, `hearth/identity.py`). No app version constant yet.
**Branch:** `kreds-auto-update` off `main`
**Product context:** in-app updates so users never redownload the whole app. Phase 2a = the signed update MECHANISM, built + tested from source. **Phase 2b (separate next step) = PyInstaller packaging + the on-restart core-swap updater + the build/sign/publish pipeline.** Internal package stays `hearth`.

## Why

To ship fixes/features without users redownloading the app. Because Kreds is a privacy tool, an unsigned auto-update would be a backdoor — so every update is **signed with a release key the app verifies before applying anything.** Most iteration is in the web client (files the node serves), so a signed **web-asset hot-update** covers ~90% of updates with no `.exe` swap; rare **core** updates stage for a swap-on-restart (apply lands in Phase 2b).

## Decisions locked this session

- **Ed25519 release key:** public baked into the app; private held by the developer offline; a dev `hearth release-sign` command signs the manifest. Verify-before-apply, always.
- **Signed manifest is the source of truth:** it pins each bundle's `sha256`, so the manifest signature transitively authenticates the bundles (no per-bundle signature needed).
- **Two tiers:** Tier 1 web-asset hot-update (download→verify→swap the writable web dir→reload) — fully built here; Tier 2 core update — detect/download/verify/**stage** here, the actual swap-on-restart is Phase 2b.
- **Source: GitHub Releases** (configurable feed URL; a local test feed for tests). One `stable` channel.
- Writable web dir: opt-in via a `build_app(node, web_dir=None)` param — **dev/demo/tests keep serving the source dir unchanged.**

## Components

### 1. `hearth/update.py` — trust + manifest + client
- **`RELEASE_PUBKEY`** — an Ed25519 public key (hex) baked in as a constant. Its private half is generated ONCE during implementation, delivered to the developer as a file to keep OFFLINE, and **never committed** (gitignored). (Implementation step: generate the keypair, put the pubkey in the constant, write the private key to a gitignored path + tell the developer to move it offline.)
- **`CORE_VERSION`** — `hearth/__init__.py __version__` (e.g. `"0.2.0"`). Current web version tracked in a `VERSION` file inside the served web dir.
- **Manifest** (JSON): `{version, channel, core_version, min_core_for_web, web:{url,sha256,size}, core:{url,sha256,size}, notes, released_at}`. `verify_manifest(manifest_bytes, sig_bytes) -> bool` (Ed25519 verify with `RELEASE_PUBKEY` over canonical manifest bytes).
- **`check(feed_url=FEED_URL) -> dict|None`** — fetch `manifest.json` + `manifest.sig`; verify signature (reject on fail); compare `version` to current; return `{web_available, core_available, info}` or None. `FEED_URL` defaults to the GitHub Releases location, overridable via env `HEARTH_UPDATE_FEED` (tests point it at a local signed feed).
- **`apply_web(info, web_dir) -> "reload"`** — gate on `min_core_for_web <= CORE_VERSION`; download the web bundle; **verify sha256** against the manifest (reject mismatch); extract to a temp dir; **atomically swap** the served web dir (rename old → `.bak`, move new in; on any failure restore the `.bak` — rollback); write the new `VERSION`. Never leaves a half-written served dir.
- **`stage_core(info, staging_dir)`** — download the core bundle; verify sha256; place it in a `pending-update` staging dir + write a marker. (The Phase-2b updater consumes it on next launch; here we build + test download+verify+stage.)
- **Dev tooling:** `sign_manifest(manifest_path, private_key_path) -> sig` and a `build_web_bundle(web_dir) -> (zip_bytes, sha256)` helper, exposed via CLI `hearth release-build` (assemble web bundle + manifest skeleton) and `hearth release-sign --manifest <f> --key <priv>` (produce `manifest.sig`). Enough to produce a testable signed feed.

### 2. Writable web dir (api.py + bootstrap.py)
- `build_app(node, web_dir=None)`: `wd = web_dir or WEB_DIR`; use `wd` for the `/static` mount, `GET /`, `GET /sw.js`. `build_bootstrap_app(data_dir, on_ready, web_dir=None)` likewise. **Default `None` → the source `WEB_DIR` → dev/demo/tests unchanged.**
- The desktop shell / packaged app passes a **writable** `%APPDATA%\Kreds\web`, seeded from the bundled defaults on first run (copied if missing or older than the bundled `VERSION`). Web updates swap that dir.

### 3. Update API + client UI
- `GET /api/update/check` → node runs `check()`, returns `{current, available, web, core, notes}` (or `{available:false}`); errors (offline/feed down) → a clean `{available:false, error}` (never a 500).
- `POST /api/update/apply` → if a web update: `apply_web()` then return `{applied:"web", reload:true}` (client reloads the webview); if core: `stage_core()` then `{staged:"core", restart_required:true}`.
- **Client UI:** an **Updates** section (in Settings/Me) + a subtle "update available" affordance: Check → shows current/available + notes → Apply → web: "updated, reloading" (reload) / core: "downloaded — restart Kreds to finish." Keyboard-accessible.

## Testing

Claude-owned (crypto + logic — test hard):
- `verify_manifest`: a correctly-signed manifest verifies; a tampered manifest OR tampered signature fails; a manifest signed by the WRONG key fails.
- `check()` against a **local signed feed** (temp dir + `HEARTH_UPDATE_FEED`): returns available when the manifest version is newer, None when equal/older; a bad signature → treated as no-update (never applies).
- `apply_web()`: applies a valid signed web bundle (swaps the dir, writes VERSION); **rejects** a bundle whose sha256 doesn't match the (signed) manifest; **rejects/gates** when `min_core_for_web > CORE_VERSION`; **rolls back** to the `.bak` on a mid-swap failure (assert the served dir is intact after a simulated failure).
- `stage_core()`: stages a verified bundle; rejects a hash mismatch.
- `build_app(node, web_dir=<temp>)` serves from the temp dir; `web_dir=None` serves the source dir (default unchanged).
- `/api/update/check` offline/feed-down → `{available:false}` not 500.
- Asset test: the Updates UI markup + `/api/update/check`/`apply` wiring.
- The **private release key is NOT in the repo** (a test/assert that no private key material is committed; the pubkey constant is present).

August-owned (real feed + GUI): a real GitHub-Releases feed + the desktop reload/restart flow are verified once packaging (Phase 2b) exists; from source, August can point `HEARTH_UPDATE_FEED` at a test feed and watch a web update apply + reload in the browser.

## Out of scope (named — Phase 2b or later)

- **PyInstaller packaging, the `.exe`, bundling tor.exe/assets/fonts, WebView2 bootstrap, single-instance lock.**
- **The on-restart core-swap updater/bootstrapper** (Tier-2 apply) + partial-write recovery/rollback of the core.
- The **release build→sign→publish-to-GitHub-Releases pipeline** wiring (the dev signs manually via `release-sign` here).
- Update-over-Tor (metadata privacy) — later; HTTPS + signature is the integrity boundary now. Multiple channels, delta/binary-diff updates, background auto-download.

## Success criteria

- A signed manifest + web bundle on a (test) feed is fetched, its signature verified against the baked-in release pubkey, and the web update applied by swapping the writable web dir (with rollback) + a reload — with a bad signature, bad hash, downgrade, or min-core mismatch all refused; a core update detects/verifies/stages (apply deferred to Phase 2b); the dev can sign a manifest with the offline private key; the private key is never committed; dev/demo/tests serve the source web dir unchanged; crypto/logic tests + full suite pass.
