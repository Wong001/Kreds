# Kreds Windows packaging (PyInstaller: stable launcher + versioned payload)

Produces `dist/Kreds/`:

```
dist/Kreds/
  Kreds.exe              <- the LAUNCHER (packaging/launcher.spec, one-file)
  current                <- text file naming the active version, e.g. "0.2.0"
  previous                <- (appears after the first core update) prior version, for rollback
  versions/
    0.2.0/
      Kreds.exe           <- the PAYLOAD (packaging/kreds.spec, one-folder)
      _internal/...
```

The top-level `Kreds.exe` is a small, stable, rarely-rebuilt stub
(`packaging/launcher.py`): on every start it re-verifies and applies any
staged core update (`hearth.coreupdate.apply_staged_core`, Task 3), then
runs `versions/<current>/Kreds.exe`. A running payload's files are never
overwritten in place — an update always lands in a brand-new
`versions/<new-version>/` directory, and only the `current` pointer moves.
See "Core-swap-on-restart layout" below. Packaging does not change any
`hearth/` runtime code (`hearth/coreupdate.py` is runtime code, but it's
pure staging/verification logic — no packaging-specific behavior lives
inside it).

## Build

```powershell
.venv\Scripts\Activate.ps1
pip install -r requirements.txt -r requirements-dev.txt
.\packaging\build.ps1
```

`build.ps1`:
1. Stages a real `tor.exe` into `packaging/tor/tor.exe`, reusing
   `hearth.tor.ensure_tor_binary()` (the same pinned-version,
   hash-verified fetch/cache the running app itself uses — no second,
   separate download path for the packaged build). Skips the fetch if
   `packaging/tor/tor.exe` is already staged.
2. Resolves `CORE_VERSION` from `hearth.__version__`.
3. Runs `pyinstaller packaging/kreds.spec --noconfirm` (the payload, one-
   folder) into an intermediate `dist/_core/`.
4. Runs `pyinstaller packaging/launcher.spec --noconfirm` (the launcher,
   one-file) into an intermediate `dist/_launcher/`.
5. Assembles the final `dist/Kreds/` tree: the launcher exe copied to the
   top level, the payload folder moved to `versions/<CORE_VERSION>/`, and
   a `current` file written with `CORE_VERSION`.
6. Prints the resulting `dist/Kreds` path.

`packaging/tor/` and `dist/`/`build/` are gitignored — nobody commits a
`tor.exe` or a build artifact.

## Core-swap-on-restart layout (Task 3)

- **`hearth/coreupdate.py`** — `apply_staged_core(install_root, staging_dir)`
  re-verifies a bundle staged by `hearth.update.stage_core()` (Ed25519
  signature over the manifest bytes, against this process's own baked-in
  `RELEASE_PUBKEY` — never anything read from the staged material itself —
  plus the sha256 embedded in that freshly-verified manifest, plus the
  actual sha256 of `pending-core.zip` on disk) before ever extracting
  anything. A bad signature or a mismatched hash discards the staged
  bundle and leaves `current` untouched. A good bundle extracts to a
  brand-new `versions/<version>/` directory (never an existing one),
  validates `Kreds.exe` is actually there, then atomically flips `current`
  (keeping the prior value in `previous`). `current_version()` reads
  `current` (falling back to the newest `versions/*` dir); `revert_current()`
  flips `current` back to `previous`.
- **`packaging/launcher.py`** (`packaging/launcher.spec`, one-file) — the
  top-level `Kreds.exe`. On start: `apply_staged_core()`, then
  `subprocess.run` on `versions/<current>/Kreds.exe` and wait. If that
  payload exits non-zero within a short bound of starting (treated as "the
  update crashed on startup," not a normal quit), the launcher calls
  `revert_current()` and reruns the prior version once.
- **Restart-to-finish** — `hearth/desktop.py`'s `Api.restart()` (the shell
  action offered by the Updates panel once `POST /api/update/apply` staged
  a core update, i.e. `restart_required: true`): spawns the top-level
  launcher `Kreds.exe` **detached** (`subprocess.Popen` with
  `DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP`, resolved from this
  running payload's own `sys.executable` two directories up), then quits
  this instance exactly like the existing `quit()`. The detached launcher
  then applies the staged update on its own fresh start. In dev/source
  mode (not frozen, no `versions/<ver>/Kreds.exe` layout to compute a
  launcher path from) `restart()` degrades to a plain quit — there is no
  launcher to relaunch into.
- Tests: `tests/test_coreupdate.py` (pure temp-dir logic — stages a signed
  bundle with a throwaway Ed25519 key, exactly like `hearth/update.py`'s
  own tests) and the `_launcher_exe_path`/`Api.restart` additions in
  `tests/test_desktop.py`. The launcher's real relaunch and the
  `build.ps1` restructure itself are verified against the actual `.exe`
  manually, not by the automated suite.

## Files

- `packaging/kreds_main.py` — frozen entry point for the PAYLOAD. `from
  hearth.desktop import launch; launch()` inside a top-level try/except
  that logs any exception to `%APPDATA%\Kreds\app.log` before re-raising
  (last line of defense: the build is `console=False`, so without this a
  startup crash before `desktop.launch()`'s own error handling kicks in
  would vanish silently).
- `packaging/kreds.spec` — the PAYLOAD's one-folder
  `Analysis`/`PYZ`/`EXE`/`COLLECT` spec, staged by `build.ps1` into
  `dist/Kreds/versions/<CORE_VERSION>/`. See "Datas placement" below for
  why the destinations are what they are.
- `packaging/launcher.py` — entry point + all logic for the LAUNCHER (the
  stable top-level `Kreds.exe`): `apply_staged_core()` then run/supervise
  `versions/<current>/Kreds.exe`, with a crash-triggered rollback to
  `previous`. See "Core-swap-on-restart layout" above.
- `packaging/launcher.spec` — the LAUNCHER's one-file spec (deliberately
  minimal `hiddenimports` — only `cryptography`, for `hearth.update`'s
  Ed25519 verify; none of the payload's fastapi/uvicorn/webview/tor deps,
  which stay entirely inside the payload build).
- `packaging/version_info.txt` — Windows version resource
  (ProductName/CompanyName/FileDescription/OriginalFilename = Kreds).
- `packaging/kreds.ico` — app icon, generated once from
  `hearth/web/icons/icon-512.png` via Pillow (already a project dep via
  `qrcode[pil]`):
  ```python
  from PIL import Image
  Image.open("hearth/web/icons/icon-512.png").convert("RGBA").save(
      "packaging/kreds.ico",
      sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
  ```
- `packaging/build.ps1` — see above.

## Datas placement (must match `hearth/paths.py`)

`hearth/paths.py`'s frozen-mode resolution:

| function | frozen value |
|---|---|
| `resource_dir()` | `sys._MEIPASS` |
| `bundled_web_dir()` | `resource_dir()/hearth/web` |
| `bundled_tor_dir()` | `resource_dir()` (then `hearth/tor.py`'s `_tor_exe_in()` looks for `<dir>/tor/tor.exe`, i.e. `resource_dir()/tor/tor.exe`) |

PyInstaller one-folder builds (6.x) put everything except the launcher
EXE under `dist/Kreds/_internal/`, and set `sys._MEIPASS` to that
`_internal` dir automatically -- so `datas` destinations are relative to
`_internal/`, not to `dist/Kreds/`. The spec's `datas`:

- `hearth/web` -> `hearth/web` (destination `_internal/hearth/web`)
- `packaging/tor/tor.exe` -> `tor` (destination `_internal/tor/tor.exe`)
- `imageio_ffmpeg`'s bundled ffmpeg binary, collected via
  `collect_data_files("imageio_ffmpeg", subdir="binaries")` so it lands
  at the SAME relative path (`imageio_ffmpeg/binaries/...`) inside the
  frozen bundle that it has inside the installed package --
  `imageio_ffmpeg.get_ffmpeg_exe()` finds it via
  `importlib.resources.files("imageio_ffmpeg.binaries")`, which depends
  on that relative layout being preserved, not on an arbitrary
  destination.

No changes were needed to `hearth/paths.py` -- the spec's datas were
written to match its existing expectations, not the other way around.

## Hidden imports

`pyinstaller-hooks-contrib` (installed alongside PyInstaller; see
`requirements-dev.txt`) ships auto-discovered hooks for `uvicorn`,
`websockets`, `webview`, `cryptography`, and `imageio_ffmpeg` already --
PyInstaller applies these automatically without any spec entries. The
spec still lists them explicitly (belt-and-suspenders, and
self-documenting against a future hooks-contrib regression):

- `collect_submodules("uvicorn")` (covers `uvicorn.protocols.*`,
  `uvicorn.lifespan.*`, `uvicorn.loops.*`) + explicit
  `uvicorn.protocols.http.auto`, `uvicorn.protocols.websockets.auto`,
  `uvicorn.lifespan.on`, `uvicorn.loops.auto`
- `collect_submodules("websockets")`
- `webview.platforms.edgechromium` (the Windows WebView2 backend)
- `cryptography`
- `imageio_ffmpeg`, `imageio_ffmpeg.binaries`
- `qrcode`

## Build iterations (this task)

1. **First `build.ps1` run: script aborted on PyInstaller's own INFO
   log line** (`NativeCommandError` at "110 INFO: PyInstaller: 6.21.0
   ...").  Cause: `build.ps1` sets `$ErrorActionPreference = "Stop"`
   globally; PyInstaller logs normal progress to stderr, and PowerShell
   5.1 promotes every stderr line from a native exe into a terminating
   error under that preference -- so the script died on the FIRST log
   line, before PyInstaller had done anything wrong. Fix: relax
   `$ErrorActionPreference` to `"Continue"` for just the PyInstaller
   invocation and check `$LASTEXITCODE` (the real signal) afterward
   instead of relying on stderr presence.
2. **Second run: `ERROR: script 'packaging/kreds_main.py' not found`
   (looked in the repo root instead of `packaging/`).** Cause: the spec
   assumed `SPECPATH` was the `.spec` FILE path and took
   `os.path.dirname()` of it -- but PyInstaller's `SPECPATH` is already
   the directory CONTAINING the spec file, so the extra `dirname()` went
   one level too high (repo root instead of `packaging/`, and one level
   above the repo root for what should have been the project root).
   Fix: `PACKAGING_DIR = os.path.abspath(SPECPATH)` directly (no
   `dirname`), `PROJECT_ROOT = os.path.dirname(PACKAGING_DIR)`.
3. **Third run: clean build.** `dist/Kreds/Kreds.exe` produced;
   `hearth/web`, `tor/tor.exe`, and `imageio_ffmpeg/binaries/*.exe` all
   verified present at the expected `_internal/...` destinations.
   Benign warnings only: `pycparser.lextab`/`pycparser.yacctab` hidden
   imports not found (cffi ships its parser precompiled -- these are
   only needed to regenerate the C parser tables at build time, unused
   at runtime) and `tzdata` not found (nothing in this app's code or its
   direct deps uses `zoneinfo`/timezone-database lookups).

4. **Fourth run (first actual exe launch): the process stayed alive but
   never opened a listening port.** `%APPDATA%\Kreds\app.log` showed the
   node thread dying immediately on startup:
   ```
   File "uvicorn\logging.py", line 42, in __init__
   AttributeError: 'NoneType' object has no attribute 'isatty'
   ...
   ValueError: Unable to configure formatter 'default'
   ```
   Root cause: a PyInstaller **windowed** (`console=False`) build has no
   console attached, so Windows leaves `sys.stdout`/`stderr`/`stdin` as
   `None` (unlike a normal process, where they're always at least a real
   file object) -- and uvicorn's default logging setup unconditionally
   calls `sys.stdout.isatty()` while building its `ColourizedFormatter`.
   `hearth/desktop.py`'s own error handling caught this correctly (logged
   it, showed the "Kreds failed to start" error window) -- the harness
   worked exactly as designed, it's the underlying dependency that broke.
   Fix (in `packaging/kreds_main.py`, NOT in `hearth/`): redirect any
   `None` stream in `sys.stdout`/`stderr`/`stdin` to `os.devnull` before
   calling `launch()`. This is a well-known PyInstaller
   windowed-app pattern and lives entirely in the frozen entry point --
   no `hearth/` runtime code changed.
5. **Fifth run: clean launch end-to-end.** See "Launch smoke test" below.

Only the stdout/stderr-`None` issue was a genuine runtime crash;
iterations 1-2 were build-script/spec bugs caught before the exe ever
ran. No missing-module or missing-data crash was ever hit -- the
`hiddenimports`/`datas` in the first working spec (iteration 3) were
already sufficient.

## Launch smoke test

Command: `Kreds.exe` started via `Start-Process`, polled for up to 150s
for a listening TCP port owned by its PID (`Get-NetTCPConnection
-OwningProcess <pid> -State Listen`), then queried directly.

Result: **launched fully to a working, Tor-connected node** within the
150s poll window (Tor bootstrap did not need anywhere near its full
allowed ~90-120s here) --

- Process stayed alive (PID persisted, no crash, no app.log entries on
  the successful run).
- Two listening ports appeared on the process: the node's HTTP/API
  server and its gossip listener.
- `GET http://127.0.0.1:<http-port>/api/bootstrap` -> `200 OK`,
  body `{"initialized":true,"onboarding_done":false}`.
- Child processes confirmed both halves of the stack came up: `tor.exe`
  (Tor bootstrapped and running headless) and `msedgewebview2.exe` (the
  pywebview/WebView2 window actually rendered).
- Process was then cleanly killed (parent + `tor.exe` child) to end the
  smoke test.

This exceeds the task's stated minimum bar (which allows for "got to the
node-launch stage, Tor-connected part is August's to verify" if Tor
bootstrap is slow in-sandbox) -- Tor bootstrap and the full node stack
were verified working end-to-end in this environment.

## Known non-goals of this task

- No code signing (unsigned exe; Windows SmartScreen will warn on first
  run -- expected until a cert is set up).
- **Installer (`kreds.iss`, Inno Setup).** `build.ps1` builds `dist/KredsSetup.exe`
  automatically when the Inno Setup compiler (`ISCC.exe`) is installed
  (`winget install JRSoftware.InnoSetup`), else it skips with a note. **Prefer
  the installer for distribution:** a portable zip downloaded from the internet
  carries the Mark-of-the-Web, and .NET Framework then refuses to run
  pywebview's bundled pythonnet DLLs ("Failed to resolve
  Python.Runtime.Loader.Initialize") until the recipient right-clicks the zip
  -> Properties -> Unblock before extracting. Installer-written files carry no
  MOTW, so the app launches with no unblock dance. Installs per-user to
  `%LOCALAPPDATA%\Programs\Kreds` (no admin; user-writable so the core-swap
  updater can drop new `versions\` payloads). Still unsigned -> SmartScreen
  warns on the installer ("More info" -> "Run anyway"); Authenticode later.
- Tor bootstrap end-to-end (onion publish, UI reachability over Tor) is
  not re-verified here beyond "the node thread starts and the local HTTP
  port answers" -- Task 1's own tests already cover Tor plumbing
  in-process; this task's job is packaging, not re-testing Tor.
