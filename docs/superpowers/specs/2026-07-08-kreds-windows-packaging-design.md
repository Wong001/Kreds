# Kreds Windows Packaging + Full Auto-Update (Phase 2b) — Design

**Date:** 2026-07-08
**Status:** Approved (design discussion, this session)
**Basis:** pywebview desktop shell (feature 15, `hearth/desktop.py` `launch()` runs `run_serve` in a bg thread + a frameless window); signed auto-update mechanism (feature 16, `hearth/update.py`: `check`/`apply_web`/`stage_core`, `RELEASE_PUBKEY`, `FEED_URL`, `build_app(node, web_dir=None)`, staged `pending-core.zip` + `pending-core.json` with the signed manifest+sig for re-verify). `hearth/tor.py` `ensure_tor_binary(bundled_dir)` finds `<dir>/tor/tor.exe`. `imageio_ffmpeg` ships an ffmpeg exe. CORE_VERSION = `hearth.__version__`.
**Branch:** `kreds-windows-packaging` off `main`
**Product context:** turn Kreds into a distributable Windows `.exe` with seamless auto-update, for a real two-machine Tor test (August + Josh/Miami) before public release. Three segments (2b-1 packaging, 2b-2 core-swap updater, 2b-3 publish pipeline), all before sharing. Internal package stays `hearth`.

## Why

Kreds runs from source today. To hand it to a non-developer (Josh) and test P2P-over-Tor, it must be a real installable app that carries its own `tor.exe`/deps, stores data per-user, and updates itself (web fixes seamlessly; core fixes via swap-on-restart) so testing iterations don't mean re-sending files. Packaging + the on-restart updater must not weaken the signed-update trust model (feature 16) or the security posture.

## Naming (hard rule)

Every **user-facing + packaged** name is **Kreds** — never "hearth" or "loop": the executable (`Kreds.exe`), the app/install folder (`Kreds`), the window title ("Kreds"), the data dir (`%APPDATA%\Kreds`), the icon, the PyInstaller `name=`, the app version metadata (CompanyName/ProductName), the zip/installer, and the feed repo (`kreds_updater`). Only the **internal Python module** stays `hearth` (invisible to users; changing it is pure churn). Anything a tester or the OS sees says Kreds.

## Decisions locked this session

- **PyInstaller one-folder** (not one-file) — needed for the versioned-payload swap, faster startup, clean `tor.exe`/ffmpeg bundling.
- **Core-swap layout = stable launcher + versioned payload dirs.** A running app is never overwritten; updates drop a new payload dir and the launcher runs the newest valid one (rollback keeps the prior).
- **The desktop app runs the node over Tor** (bundled `tor.exe`) — that's what lets two machines connect via `.onion` (and finally live-validates Tor).
- **Unsigned** for the friend test (SmartScreen "unknown publisher" accepted); **Authenticode** + a real **installer (Inno Setup)** are public-release follow-ups.
- Feed = **`github.com/wong001/kreds_updater`** Releases; the manifest is signed **locally** with the offline key.

## Components

### Segment 2b-1 — the packaged `.exe`

- **Frozen-mode resource resolution** (`hearth/paths.py` or in desktop.py): `resource_dir()` = `sys._MEIPASS` when `getattr(sys, "frozen", False)` else the repo; `bundled_web_dir()`, `bundled_tor_dir()`, ffmpeg path — resolve into the bundle when frozen, the repo otherwise. Non-frozen behavior UNCHANGED (dev/tests).
- **PyInstaller `.spec`** (`packaging/kreds.spec`, one-folder): entry = a thin `packaging/kreds_main.py` calling `hearth.desktop.launch()`; `datas` = `hearth/web/**`, `hearth/web/fonts` (already under web), the **`tor/tor.exe`** dir, the imageio_ffmpeg binary; `hiddenimports`/hooks for uvicorn, fastapi, pywebview's Windows (edgechromium) backend, cryptography, imageio_ffmpeg, websockets. Name `Kreds`, windowed (no console), icon.
- **Desktop launch when packaged:** seed the writable `%APPDATA%\Kreds\web` from `bundled_web_dir()` on first run (or when the bundled `VERSION` is newer than the seeded one), serve via `build_app(node, web_dir=<writable web>)`; run the node **over Tor** — `run_serve(..., tor=True)` — with `ensure_tor_binary(bundled_dir=resource_dir())` so it finds the bundled `tor.exe` (no download). Data dir stays `%APPDATA%\Kreds`.
- **Single-instance lock:** a named Win32 mutex (or an exclusive lockfile in the data dir); a second launch exits (optionally signals the first to focus). Prevents two nodes on one data dir/port.
- **Harden the node-create freeze:** wrap the two-phase handoff / first-run so a hang can't freeze silently — bound the `run_serve` bootstrap→full transition, surface any node-thread exception to a visible error (log to `%APPDATA%\Kreds\app.log` + a webview error page) instead of a frozen blank window; ensure `_wait_http` timing tolerates the Tor-enabled slower start.
- **`hearth build` / a `packaging/build.ps1`** helper to run PyInstaller reproducibly. Output `dist/Kreds/` → a portable zip.

### Segment 2b-2 — on-restart core-swap updater

- **Installed layout:** `Kreds/launcher.exe` (a tiny stable stub) + `Kreds/versions/<version>/` (each a full one-folder payload). A `current` pointer file names the active version. The launcher: (1) applies any staged update (below), (2) runs `versions/<current>/Kreds.exe`.
- **`hearth/coreupdate.py` `apply_staged_core(install_root, staging_dir)`** (run by the launcher at startup): if `staging_dir/pending-core.zip` + `pending-core.json` exist → **re-verify** (`update.verify_manifest` over the staged manifest bytes+sig, then sha256 of the zip vs the manifest — reusing feature-16's staged material) → extract to `versions/<newver>/` (a NEW dir, never touching the running files) → validate (the expected `Kreds.exe` exists) → atomically flip `current` → newver; keep the prior version for **rollback**; clear staging. On any failure: leave `current` unchanged, discard the half-extracted dir (nothing applied). Prune versions older than the prior-good after a successful run.
- **Rollback:** if a newly-swapped version fails to start (launcher detects a crash marker / non-start within a bound), revert `current` to the prior version.
- **Shell "restart to finish":** after `stage_core`, the shell's restart action relaunches `launcher.exe` and exits; the launcher applies the staged update then starts the new version.
- Claude tests `apply_staged_core` (re-verify gate, new-dir extraction, current-flip, rollback on bad/failed swap) as pure logic against temp dirs — no real relaunch needed in tests.

### Segment 2b-3 — build → sign → publish pipeline

- **`hearth release-build`** (extend feature-16's): assemble the **web bundle** (`build_web_bundle(hearth/web)`) + the **core bundle** (zip of the PyInstaller `dist/Kreds/versions/<ver>` payload) + a **manifest** (`version`, `core_version`, `min_core_for_web`, `web`/`core` each `{url (kreds_updater asset), sha256, size}`, `notes`, `released_at`). Write `manifest.json` + the bundles into a `release/` staging dir.
- **`hearth release-sign --manifest --key <book-key path>`** — August signs `manifest.json` locally with the offline private key → `manifest.sig`. (Signing stays manual; the key never enters CI/scripts.)
- **`hearth release-publish`** (or documented `gh release create`): upload `manifest.json` + `manifest.sig` + `web-<ver>.zip` + `core-<ver>.zip` as assets to a `wong001/kreds_updater` GitHub Release tagged `v<version>`.
- **`FEED_URL`** in `update.py` → the `kreds_updater` "latest" `manifest.json` asset URL (stable across releases; overridable via `HEARTH_UPDATE_FEED`).
- A short **RELEASE.md** runbook: build → assemble → sign (with the book-key) → publish → verify a client updates.

## Testing

Claude-owned (build + logic, on this Windows box):
- The PyInstaller build **produces `dist/Kreds/`** and the resulting `Kreds.exe` **launches** to the first-run screen (smoke: start it, poll the local port answers, kill it) — iterate the `.spec` until it builds + starts. (The *window* itself is August's.)
- `resource_dir()`/`bundled_*` resolve to the repo when non-frozen (dev/tests unchanged) and to `sys._MEIPASS` when frozen.
- `apply_staged_core`: re-verify rejects a bad-sig/bad-hash staged bundle (nothing applied); a valid one extracts to a new `versions/<ver>`, flips `current`, keeps the prior; a failed/half extraction leaves `current` unchanged; rollback reverts `current`.
- `release-build`/`sign`/publish assembly: the manifest pins the real bundle hashes; a client `check()` against the assembled+signed local feed verifies + reports available; the release private key is NOT committed.
- Single-instance: a second `launch()` attempt detects the lock (mockable) and exits.
- Full suite + `node --check` stay green; dev/demo/`serve`/tests unchanged (frozen paths only apply when frozen; desktop-Tor only in the packaged launch).

August-owned (the real app, can't be automated): unzip + run on his machine and Josh's; **connect over Tor between the two machines** (August + Josh/Miami) — the live Tor validation; a real end-to-end update (publish a web fix → both auto-apply; publish a core update → swap-on-restart); no freeze on node-create.

## Out of scope (named — public-release follow-ups)

- Authenticode code-signing; an Inno Setup **installer** (Start Menu, uninstaller, auto-start); a WebView2 **bootstrapper** (assume present on the testers' Win10/11); macOS/Linux packaging; CI-built releases; delta/binary-diff core updates; auto-check-on-a-timer (manual "Check for updates" is fine).

## Success criteria

- A portable Kreds Windows build launches into first-run, carries its own `tor.exe`, runs the node **over Tor**, stores data in `%APPDATA%\Kreds`, and refuses a second instance; August + Josh install it and **connect over Tor between two machines**; a signed web update auto-applies (no redownload) and a signed core update applies via the stable-launcher/versioned-payload swap-on-restart with rollback, all re-verified against the baked-in release key; the node-create freeze is gone; the release private key stays offline; dev/demo/tests + the full suite are unchanged/green. Packaging gotchas are resolved iteratively; Authenticode + installer are public-release follow-ups.
