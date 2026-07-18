# Kreds — vertical slice v0.1

Private, non-commercial, peer-to-peer social space. Your data lives on
hardware you own; you can only add people you already know (a single-use
invite code that expires in ten minutes — no discovery, no lookup); the
influencer economy is impossible by architecture. This repo holds the
concept documents, the D2 identity spike, and the local vertical slice:
four real node processes gossiping signed posts and encrypted DMs over TCP.

Spec: `docs/superpowers/specs/2026-07-02-hearth-vertical-slice-design.md`
Concept: `hearth_concept_capture_v0_4.md`

## The app

Kreds is journal-first: a day-grouped feed of what your circle shared,
with the circle itself doubling as navigation (a compact rail that
expands into a radial map of who's Inner and who's Kreds; click anyone
to open their profile).
The expanded circle fills the screen and is a camera, not a fixed
picture: ring radii grow with friend count (constant node spacing -
the circle gets bigger, never denser), the wheel or a two-finger pinch
zooms anchored under the pointer, dragging pans (same Pointer Events
plumbing as the profile canvas: pointercancel + lostpointercapture
teardown, 6px tap-vs-drag threshold so node clicks stay clicks), and
double-click/double-tap or the Fit button resets to the full view.
Name labels hide when nodes are tighter than ~56px on screen and fade
back in as you zoom (instant, no fade, under prefers-reduced-motion).
The compact rail minimap is unchanged.
It installs as a PWA - "Install" on desktop,
"Add to Home Screen" on Android - and is served entirely by your own
node: there is no shared kreds.eu server in this picture. The web client
you load *is* your node's UI; the app shell caches for offline load, but
all data still comes live from the node you're pointed at. **iOS is no
longer a PWA target**: a native iOS app is now the real phone path (see
"Desktop first run" below and ROADMAP) - a full add-to-home-screen PWA
has real background/notification limits on iOS Safari that a native app
doesn't. The manifest/service-worker shell still loads fine in Safari
today; it just isn't the intended way onto an iPhone going forward.
Internal package/module names remain `hearth`.

Every person - opened from the journal, the chip bar, your friends
list, or the circle map - is a full profile **page**, not a modal:
banner image, a customizable avatar (shape/size/placement), ring status
with a since-date, a Message action, and move-between-rings; a Back
button returns you to wherever you came from. Editing your own profile
happens on that same page. Me (desktop nav and mobile tab) opens your
own profile page directly - no separate card view - with Back hidden
and a self-only Friends + Devices strip in its place; a friend's
profile never shows that strip, only Back.

## Run the demo

    .venv\Scripts\python.exe -m hearth demo

Then open:
- http://127.0.0.1:7201 (Wong's phone)
- http://127.0.0.1:7202 (Wong's home node)
- http://127.0.0.1:7203 (Freja's phone)
- http://127.0.0.1:7204 (Freja's home node)

Open the Me tab to customize your profile - accent, picture shape and
placement, banner, bio.

## Desktop first run

Outside the demo (which pre-creates four identities on fixed ports
7201-7204), a real single node starts with nothing:

    .venv\Scripts\python.exe -m hearth serve --dir <your-data-dir> --http-port <port>

`serve` is two-phase, on one process and one port. With no enrolled
`keys.json` yet, it runs a minimal, node-less **bootstrap app**
(`hearth/bootstrap.py`) that serves the same web client plus create/pair
endpoints; once you create a node or finish pairing a device, it hands
off gracefully - the bootstrap server shuts down and frees the port,
then the full node app (`run_node`) takes over that SAME port, so the
URL you opened before create/pair still works after. `GET
/api/bootstrap` is how the client tells which phase it's talking to
(`{"initialized": false}` before create/pair, `{"initialized": true,
"onboarding_done": <bool>}` after).

Loading that URL for a fresh dir shows the branded **first-run
screen**: **Create New Node** (a name -> a new identity, same underlying
ceremony as `hearth init`) or **Connect to Existing Node** (copy-paste
device pairing - paste the request generated here into your existing
device's Settings, then paste the resulting package back). Either path
polls until the handoff to the full app completes, then loads it.

The first time a node comes up, the client shows a **one-time setup
wizard**: an App-lock offer (PIN or passphrase) that is genuinely
**skippable** - Skip advances without ever calling the setup endpoint -
and an honest **"Kreds for iPhone is in development"** card (you'll pair
your phone to this node when it ships; you can pair any device anytime
from Settings). There is deliberately no "install as a PWA" step here -
iOS reachability is now a native-app plan, not a PWA one (see the intro
above and ROADMAP). The wizard is gated on a node-side `onboarding_done`
flag (`POST /api/onboarding-done` sets it once it's dismissed, so it
never shows again on this device); a device that arrives via **Connect**
is already considered set up and skips the wizard entirely. The circular
logo gets a subtle breathing/rotation animation on the first-run screen
(`@media (prefers-reduced-motion: no-preference)` - it's simply absent,
not just paused, for anyone who has that OS setting on), and the
App-lock lock screen has been restyled to match the same look (logo +
background) with the existing PIN keypad / passphrase field unchanged
underneath.

Proven end-to-end by `tests/test_onboarding_integration.py` against a
real running `run_serve` process (not the in-process `TestClient` the
unit-level `tests/test_bootstrap.py` uses for each app in isolation):
bootstrap phase up and unenrolled, create, poll through the actual
handoff gap, the full app answering on the same port, a real full-app
route, and the onboarding-done round trip.

**`hearth serve` itself is unchanged** and still opens in a normal
browser tab/window, exactly as described above. A separate **Windows
desktop app** (`hearth app`) now also exists as an additional path, not
a replacement - see "Windows desktop app" below, and "Windows desktop
app (packaged)" for the distributable `Kreds.exe` built from it,
including its system-tray icon backing "Keep running" (see
"Quit vs. Keep-running" below).

## Windows desktop app

    .venv\Scripts\python.exe -m hearth app [--dir <data-dir>]

`hearth app` runs the exact same node as `hearth serve`, but shows it in
a **frameless** `pywebview` window instead of a browser tab: no native
Windows title bar, with the app drawing its own custom title bar and
traffic-light **minimize / maximize / close** controls
(`hearth/web/index.html`'s `#titlebar`, wired by `wireDesktopChrome()`
in `hearth/web/app.js`). All of this - the title bar, the controls, the
one-time close-behavior wizard step below - is gated on
`window.pywebview`: a plain browser tab (the demo, `hearth serve`
without the app shell, any other existing path) gets none of it and is
completely unaffected.

With no `--dir` given, the node's data lives in `%APPDATA%\Kreds`
(`hearth/desktop.py`'s `default_data_dir()`) rather than a path you pick
yourself - the right default for a real installed app, vs. `hearth
serve --dir <dir>`'s explicit dir for development/multi-node testing.
The node itself runs in a background thread with its own asyncio event
loop, on a free localhost port `hearth/desktop.py` picks automatically
(`_free_port()`); the window simply loads that URL.

**Quit vs. Keep-running.** Closing the window asks (via `GET
/api/settings`, read live, never cached, so a change in Settings takes
effect on the very next close) what closing should mean: **Quit** stops
the node and closes the window, or **Keep running in the background**
hides the window fully (no taskbar entry) while the node keeps syncing,
backed by an **always-present system-tray icon**
(`hearth/desktop.py`'s `_create_tray()`, on `pystray`, imported lazily
inside that one function so the test suite never needs the GUI dep).
The tray's menu is just **Open Kreds** (also the default action, so a
double-click does the same thing) and **Quit Kreds**; the first time a
window is hidden to tray, a one-time balloon explains where it went
("Kreds keeps running in the background...") via a flag file in the
data dir, never shown again after that. If the tray thread isn't alive
for any reason, `hide_to_tray()` falls back to a plain
taskbar-minimize instead of stranding the user with a hidden window and
no way back (an older frozen shell missing `hide_to_tray` entirely -
web/core version skew - gets the same minimize fallback from the JS
side). The tray icon is stopped on every exit path - a normal quit, a
tray-menu quit, and the Updates panel's "restart to finish" (which
calls `quit()` under the hood) - so closing or restarting the app never
leaves an orphaned icon behind. The choice defaults to Quit, is asked
once as the last step of the one-time setup wizard (desktop-only - a
plain browser never sees this step), and can be changed anytime after
in Settings (`renderDesktopSettings()`, same `/api/settings` endpoint).
`/api/settings` is allowlisted while the node is locked (App-lock), so a
locked "keep running" node's close handler can still read the pref
instead of silently falling back to quit.

**Lifecycle underneath, for anyone touching this code.** The custom
close button calls `window.pywebview.api.quit()`
(`hearth/desktop.py`'s `Api.quit`), which thread-safely signals the
node's own `asyncio.Event` (`loop.call_soon_threadsafe(ev.set)`) and
then destroys the window; the node's `run_serve`/`run_node`
(`hearth/runner.py`) see that event and shut down `uvicorn` cleanly
(`server.should_exit = True`), whether the node is still in the
bootstrap phase or already fully running. `launch()` then joins the
node thread before returning, so the port is never left held by a
zombie node. This exact guarantee - shutdown event in, clean stop and
no hang, whether started or mid-bootstrap - is proven by
`tests/test_runner.py::test_run_serve_stops_on_shutdown_event`, and
`tests/test_desktop_integration.py` proves the same thing through
`hearth.desktop`'s own `default_data_dir()`/`_free_port()` composition
(the exact functions `launch()` calls), not just a hand-built
`tmp_path`/port pair. `import hearth.desktop` never requires
`pywebview` to be installed (`import webview` is lazy, inside `launch()`
only) - the suite doesn't gain a hard GUI dependency, and every non-GUI
path (demo, `hearth run`/`serve`, the browser client) is unaffected.

**Dependency and runtime notes.** This adds one new dependency,
`pywebview` (listed unpinned in `requirements.txt`; 6.2.1 is what's
currently installed in `.venv`). On Windows it renders through the OS-provided
**Microsoft Edge WebView2** runtime, not a bundled browser engine;
WebView2 ships pre-installed on current Windows 10/11, so no separate
install step is needed on the machines this targets today.

**This section describes `hearth app` run from source** (this repo's
`.venv`, like every other CLI subcommand) - the Phase 1 shell. Phase 2b
has since built a real PyInstaller-packaged, distributable `Kreds.exe`
on top of this exact same `hearth/desktop.py` machinery (bundled
`tor.exe`, the node running over Tor, a writable auto-updatable web
copy) - see "Windows desktop app (packaged)" below. The system-tray
icon backing "Keep running" described above (see "Quit vs.
Keep-running") is the same code path in both the source run and the
packaged build - it's not a packaged-only addition.

**The GUI itself - the actual window, its chrome, dragging, minimize/
maximize/close, the wizard step's look - is not exercised by automated
tests** (a real pywebview window can't be driven headless) and is
August's to verify by hand, per this project's established
testing-workflow split (App-lock's and the onboarding wizard's UX were
verified the same way). What IS automated: the lifecycle guarantee
above, `hearth/desktop.py`'s pure-Python helpers
(`tests/test_desktop.py`, `tests/test_desktop_integration.py`), and the
`/api/settings` contract including its locked-allowlist behavior
(`tests/test_settings_api.py`).

## Windows desktop app (packaged)

A real, distributable Windows build of the app above: `Kreds.exe`, built
with **PyInstaller** (one-folder), that runs on a machine with no Python
installed. Produced by `.\packaging\build.ps1` (see `packaging/README.md`
for the full build/layout writeup); this section states what the result
*does*, not how it's built.

**Same frameless shell, now packaged and Tor-native.** It's the identical
`hearth/desktop.py` window/chrome/close-behavior from "Windows desktop
app" above, frozen into an executable. Two things differ only when
frozen (`hearth/paths.py`'s `is_frozen()`), never when running from
source: the node runs **over Tor** with a **bundled `tor.exe`** (no
first-run download - `hearth/tor.py`'s existing bundled-first resolution
just finds it already staged in the bundle), and the web UI is served
from a writable, auto-updatable copy seeded from the bundled assets into
`%APPDATA%\Kreds\web` (`paths.seed_web_dir`) rather than the read-only
bundle directly - so a web hot-update (see "Auto-update" below) has
somewhere writable to land. Data still lives in `%APPDATA%\Kreds`,
unchanged from Phase 1.

**Single-instance.** A second launch against the same data dir doesn't
start a second, port-colliding node - it detects an already-held
exclusive lock (`instance.lock`) and shows a "Kreds is already running"
notice instead.

**Freeze-harden.** A packaged app has no console to show a startup
traceback in, and Tor bootstrap is slow (up to ~120s allowed) - a
straightforward port to a frozen build risks looking like a hung blank
window on any failure. Startup is bounded end-to-end: a node-thread
death or a timed-out readiness wait surfaces a small in-window error
page ("The Kreds node failed to start") instead of hanging, and every
failure is logged to `%APPDATA%\Kreds\app.log` so a bad run leaves
evidence.

**Core-swap-on-restart (the launcher / versioned-payload layout).** The
installed tree is a small, stable top-level `Kreds.exe` **launcher**
(`packaging/launcher.py`, rarely rebuilt) plus `versions/<version>/`
payload directories (each a full `packaging/kreds.spec` build) and a
`current` pointer file. On every start the launcher re-verifies and
applies any staged core update (`hearth/coreupdate.py`'s
`apply_staged_core` - re-checks the Ed25519 signature against its own
baked-in `RELEASE_PUBKEY`, the sha256 the manifest actually pins, and
the sha256 of the bytes on disk, independent of whatever was already
checked at stage time) before running `versions/<current>/Kreds.exe`; a
running version's files are **never overwritten in place** - an update
always lands in a brand-new `versions/<new>/` directory, and only the
`current` pointer moves, with the prior version kept for rollback if the
new one crashes on startup. The Updates panel's "restart to finish"
action (after a staged core update) relaunches this stable launcher
detached, then quits the current instance - the launcher applies the
update on its own fresh start.

**Honest, stated plainly.** This build is currently **unsigned** - no
Authenticode code-signing certificate yet - so Windows SmartScreen shows
an "unknown publisher" warning on first run; that's expected for the
friend test, not a bug. **The Inno Setup installer is built** (`dist/
KredsSetup.exe` via `build.ps1` - Start Menu entry, uninstaller,
auto-start); **Authenticode signing is in progress** (certificate
identity validation underway as of 2026-07-08); **macOS packaging
remains a follow-up**. The system-tray icon
(see "Windows desktop app" above, "Quit vs. Keep-running") is bundled
into this build too: `pystray` ships compiled into `Kreds.exe` itself
(PyInstaller only extracts non-Python assets like DLLs into
`_internal`; pure-Python dependencies such as `pystray` are zipped
straight into the executable), confirmed both by PyInstaller's own
module analysis picking up `pystray._win32` and by a packaged run
producing no "tray unavailable" entry in `app.log`. Proven so far: the
build launches end-to-end (polled for its listening port, `GET
/api/bootstrap` -> `200`, both `tor.exe` and the WebView2 host process
confirmed running as children, then cleanly killed) and the full signed
update chain - a combined web+core release, `check()`/`apply_web()`/
`stage_core()`/`apply_staged_core()` end to end, plus a tampered feed
(bad signature, and separately a bad bundle hash) refused at every step
with nothing applied - is proven by `tests/test_packaging_integration.py`
(logic-level, no real `.exe`/GUI) alongside `tests/test_coreupdate.py`
(the re-verify/rollback contract in isolation) and `tests/test_paths.py`
(frozen-vs-source path resolution). **What is not yet proven by an
automated test or a live run: the real two-machine Tor connection**
(August + Josh) - the next validation step, see ROADMAP.

**Releasing a build.** `RELEASE.md` is the full runbook: bump the
version, build the `.exe`, assemble the release bundles
(`hearth release-build`), sign the manifest with the offline release
private key (`hearth release-sign` - **the key never touches this repo,
CI, or any unattended script**), and publish to the public
`wong001/kreds_updater` GitHub Releases feed (`hearth release-publish`,
a thin `gh release create` wrapper). Publishing a new release is the
only "make it live" step - every installed client's next "Check for
updates" sees it automatically via GitHub's stable `/releases/latest/
download/manifest.json` redirect.

## Auto-update

Signed, verify-before-apply auto-update, from the mechanism
(`hearth/update.py`, `hearth/api.py`'s `/api/update/*`, a Updates panel in
Settings) through to a packaged app that actually applies it
(`hearth/coreupdate.py`, `packaging/launcher.py`) and a real publish
pipeline to a public feed (`RELEASE.md`) — see the honest boundary below
for exactly what's proven and what's still ahead.

**Trust model.** Every update starts as a `manifest.json` describing one
release (`version`, `min_core_for_web`, and a `web` and/or `core` bundle
entry, each `{url, sha256, size}`), signed with an offline **Ed25519
release private key** into a sibling `manifest.sig`. The app carries only
the corresponding **public** key baked in (`update.RELEASE_PUBKEY`) and
refuses anything that doesn't verify: `update.check()` fetches the manifest
+ signature, verifies the signature against that baked-in key, and returns
"no update" — never raises — on a bad signature, a fetch failure, a
malformed manifest, or a version that isn't actually newer than
`hearth.__version__` (no downgrades). Verifying the manifest signature
authenticates the bundles too, because the manifest pins each bundle's
sha256; `apply_web`/`stage_core` re-hash the downloaded bundle and refuse
(`BadUpdate`) on any mismatch. **The private release key never lives in
this repo** — it's generated once, gitignored (`release_private_key*`), and
held **offline by the developer**; `hearth/cli.py`'s `release-build` /
`release-sign` subcommands are the developer-side tooling that builds and
signs a release manifest from a local key file.

**Web-asset hot-update (no redownload of the app).** A "web" bundle is a
zip of `hearth/web/` (`update.build_web_bundle`, deterministic — sorted
file order, `ZIP_DEFLATED`). `apply_web(info, web_dir)` downloads it,
verifies the hash, extracts to a sibling `.web-new` dir, and atomically
swaps it into place with rollback: rename `web_dir` -> `.web-bak`, rename
`.web-new` -> `web_dir`; if the second rename fails, `.web-bak` is renamed
straight back so the served directory is never left missing or
half-written. Each rename goes through a short bounded retry
(`_rename_with_retry`, ~3s budget) — **a real bug found and fixed while
writing the Task 4 integration test**: on Windows, renaming a directory
transiently fails with `PermissionError [WinError 5]` if any file inside it
still has an open handle, which a request actively being served out of
that exact directory can briefly hold; the retry rides out that transient
window without weakening the rollback guarantee (if every attempt is
exhausted, the original error still propagates and rollback still runs).
`GET /api/update/check` / `POST /api/update/apply` apply against whichever
directory `build_app(node, web_dir=...)` is actually serving — the repo's
`hearth/web` in dev/source mode, or (packaged, Phase 2b) the writable
`%APPDATA%\Kreds\web` copy `hearth/desktop.py` seeds and points `web_dir`
at — never a hardcoded path, so a hot-swap always lands where the app is
actually serving from. On success the client gets
`{"applied": "web", "reload": true}` and reloads.

**Core staging + on-restart swap.** A "core" bundle (everything else — the
Python app itself, a PyInstaller payload zip) is downloaded and
hash-verified the same way, but only **staged** by the running process
(`stage_core`, writes `update-staging/pending-core.zip` + a version
marker carrying the signed manifest bytes for later re-verification) —
nothing about the running process changes. The API reports
`{"staged": "core", "restart_required": true}` and the Updates panel
offers "Restart to finish": this spawns the stable top-level launcher
(`packaging/launcher.py`) detached and quits the current instance; the
launcher **independently re-verifies** the staged bundle from scratch
(`hearth/coreupdate.py`'s `apply_staged_core` — signature, the sha256
pinned inside that freshly-verified manifest, and the actual sha256 of
the bytes on disk, never trusting anything left behind by the staging
step alone) before extracting it to a brand-new `versions/<version>/`
directory and flipping the `current` pointer — a running install's files
are never overwritten in place, and the prior version is kept for
rollback. See "Windows desktop app (packaged)" above for the full
packaged layout.

**Feed model.** The manifest + signature are published as GitHub Releases
assets on the public `wong001/kreds_updater` repo (`FEED_URL` defaults to
its `.../releases/latest/download/manifest.json` redirect, always the
newest release); `HEARTH_UPDATE_FEED` overrides it for dev/tests — every
test in this repo (`tests/test_update_trust.py`, `test_update_client.py`,
`test_update_api.py`, `test_update_integration.py`,
`tests/test_coreupdate.py`, `tests/test_packaging_integration.py`) points
at a local `file://` feed signed with a **throwaway** key generated on
the spot, never the real release key.

**Client.** A self-only Updates panel in Settings (`renderUpdateSettings()`
in `hearth/web/app.js`) offers a manual "Check for updates" ->
`GET /api/update/check`, then "Apply update" -> `POST /api/update/apply`;
a web update reloads the page, a core-only update shows "downloaded -
restart to finish." No background auto-check loop — checking is
user-initiated only.

**Honest boundary — what this is and isn't yet.** Phase 2a built the
signed-manifest mechanism from source (verify-before-apply, the web-asset
hot-swap, core staging, the Updates UI), proven end-to-end in
`tests/test_update_integration.py`. **Phase 2b has since shipped the rest
of the chain**: a real distributable `Kreds.exe` (PyInstaller packaging —
see "Windows desktop app (packaged)" above), the on-restart core-swap
updater that actually consumes `update-staging/pending-core.zip`
(`hearth/coreupdate.py` + `packaging/launcher.py`), and a real
build → sign → publish pipeline (`RELEASE.md`) that pushes signed
releases to the public `wong001/kreds_updater` GitHub Releases feed —
`FEED_URL`'s real, live target now, not just a shape tests point
elsewhere. The full combined web+core chain, plus a tampered-feed refusal
at every step, is proven end-to-end in
`tests/test_packaging_integration.py`. **What's still honestly missing**
before this is a public release channel: the build is **unsigned**
(Windows SmartScreen warns on first run — expected for the current
friend test); **Authenticode code-signing, a real installer (Inno
Setup), and macOS packaging are public-release follow-ups**, not built
yet. And the one thing no test can prove: **a real update has not yet
been published and picked up by a real installed client on another
machine** — that's the next live-validation step (see ROADMAP), alongside
the two-machine Tor connection test itself.

## Tests

    .venv\Scripts\python.exe -m pytest tests -q

## Honest deviations from real Kreds (slice only)

- Copy-paste stands in for the QR camera scan. As of 0.3.0 only one side
  pastes (see "Adding a friend" below) - B's response auto-delivers to A
  over Tor when both are online - but it's still text you type/paste, not
  a scanned code; the offline fallback is still copy-paste on both ends.
  Device pairing still uses the same secure copy-paste channel too.
- The default demo transport is signed but PLAINTEXT TCP on localhost.
  Tor is now wired (see "Running over Tor" below) and is the real-network
  transport; the localhost demo stays plaintext for speed. Encryption at
  rest / OS-keystore key protection has since shipped as App-lock (see
  "App-lock" below) - Windows-only, opt-in per node. Still not wired:
  notifications.

## Running over Tor

    .venv\Scripts\python.exe -m hearth demo --tor

Each node becomes reachable at a stable `.onion` address and dials peers
through Tor. The user never installs Tor: Kreds resolves `tor.exe`
(bundled with the app, else cached, else a pinned, hash-verified first-run
download of ~21MB) and runs it headless on loopback.

What Tor mode gives, honestly: the transport is encrypted and
endpoint-authenticated to the onion service, and routing is metadata-blind
(no relay of ours ever sees who talks to whom) - on top of the existing
device-key handshake. Costs, stated plainly: first run downloads the Tor
bundle unless it is packaged with the app; onion services take 10-47s to
publish on start; a first sync between two nodes can take about a minute
(steady-state syncs are faster but still seconds, so onion peers sync on a
slower interval than the localhost demo). Windows-x86_64 only for now.

Validation status, stated honestly: the full onion-gossip path was proven
end-to-end by the feasibility spike (`docs/spikes/`, 12/12 real syncs on
this machine). A control-protocol correctness fix (detaching onion
services so they outlive the publishing connection) landed after the
spike; the automated real-Tor test (`TOR_E2E=1`) and the `--tor` demo
exercise the fixed path, but a green live re-run has not yet been
captured on a network where Tor bootstraps cleanly. The transport code is
unit-tested offline; the live re-confirmation is pending.

## Adding a friend

Shipped as **0.3.0**; the code itself was compacted in a follow-up slice
(spec `2026-07-10-compact-invite`). Becoming friends still starts with
someone you already know - there is no server-side discovery or lookup, so
a stranger has no way in - and it takes one code instead of a two-way
paste. The code is deliberately safe to send over an existing channel:
single-use, single-active, and expired after ten minutes, so sharing it
means trusting that channel for ten minutes at most (in person removes
even that dependency - share it like a house key, not a flyer).

**The code is compact**: ~80 characters of base58, not the ~600-char JSON
blob the first cut of this shipped with (full cert + onion + nonce +
expiry, all hex) - hand-typing that between two clipboard-isolated PCs
nearly killed the first real two-machine Tor test. The shrink comes mostly
from moving A's enrollment cert out of the invite: the cert never actually
defended the invite (anyone who can rewrite the code in transit can swap
the cert too, and B has no prior knowledge of A's identity to catch it) -
what authenticates the handshake is the one-time nonce inside the code,
which only A and whoever A shared it with know. So the cert now travels
one message later, in the **final** handshake frame that B's node delivers
back to A over Tor, after the nonce/signature proof has already run. The
UI shows the code truncated as `kreds·invite·<FP>…<last four chars>`
with a Copy button that copies the full string; `<FP>` is a 4-character
fingerprint derived from the first 4 bytes of A's identity key.

**A** opens Add Friend and shares **one code** - read aloud, AirDropped,
texted, whatever's convenient. **B** pastes that single code in and hits
Add; B's screen shows "Connecting to someone whose ID starts with `<FP>`" -
the same 4 characters visible on A's card, so B can glance across and
confirm before anything completes. From there B's node dials A directly
over Tor and delivers its half of the handshake automatically: A verifies
it, both sides add each other, and B sees "Connected" - **A never pastes
anything back**. The code carries A's onion address and a one-time nonce,
so the auto-connect only works while both devices are online and reachable
over Tor; the underlying cryptographic ceremony (mutual device-key proof,
signed by both sides) is unchanged from the original four-box flow, only
the second copy-paste round trip is gone. `complete_invite` also enforces
a binding check now that the cert arrives later than the fingerprint:
whoever's cert shows up in the final must match the invite's carried
`id_prefix`, or the add is refused outright - so the 4-char fingerprint
isn't just cosmetic, it's mechanically enforced too, not merely displayed.

**The code is deliberately short-lived and single-shot**: single-use
(consumed the moment a valid response lands), single-active (generating a
new code immediately kills the old one - only ever one live invite per
node), and expires in **10 minutes**, with a live "expires in MM:SS"
countdown next to it and a Regenerate button once it lapses. A's own
`finalize_invite` is the only authenticator on the inbound handshake frame
- a stranger with no live, matching, non-expired nonce (a guessed code, a
replayed old one, a forged signature) is refused outright and nothing is
added to A's friend list; that inbound path is also rate-limited so it
can't be hammered.

**Honest limit of the 4-char fingerprint, stated plainly**: it is a
*casual* integrity check, nothing more. It catches accidents - a mis-pasted
code, a cross-wired share, an unsophisticated attempt to swap the code for
a different one - because a wrong or tampered code shows a fingerprint that
doesn't match what the sender is holding up. It is **not proof against a
determined attacker**: someone who intercepts the trusted channel the code
travels over and grinds a look-alike identity key matching those same 4
characters (a 32-bit space - feasible in seconds on a GPU) can substitute
their own identity and the fingerprint will still read as correct. Real
MITM proof needs comparing the *full* identity, not 4 characters of it -
that's a named, deferred follow-up (see ROADMAP: "full-identity
verification screen"), not something this slice claims to provide.

**Offline falls back to the original copy-paste flow, unchanged.** If B's
auto-connect attempt can't reach A (A's node is offline, or not reachable
over Tor at that moment), B's node still produces its response payload and
shows it for manual copy-paste - the same "hand your device to the other
person, they paste it into their Add Friend screen, then hand back the
result" ceremony this app has always used, and the same underlying
`respond_to_invite` / `finalize_invite` / `complete_invite` calls (now
carrying the same compact codec as the invite), reached as a fallback
instead of the only path.

Honest limits, stated plainly: this is still **copy-paste**, not a camera
scan - a real QR flow (scan A's code with B's camera) is deferred to the
native iOS app (see ROADMAP), where a camera is actually available. And
the auto-connect step depends on Tor being up and both onion services
reachable; on a bad network it degrades to the same manual fallback that
has always worked.

## Encrypted messages

Friends can exchange end-to-end encrypted DMs (text + photos). A DM is
encrypted to each of the recipient's (and your own) enrolled devices, so
your home node receives it while your phone is offline and your phone
picks it up later. Friends never relay DMs - not even ciphertext - so no
one else learns you are talking.

Honest limits (v0.2): DM forward secrecy is WINDOWED, not per-message.
Each device rotates its encryption key daily and permanently deletes
retired keys after 7 days, so a leaked keys.json or stolen backup
cannot decrypt DM ciphertexts older than that window - and an attacker
who loses access stops reading new DMs after the next rotation. What
this does NOT protect, stated plainly: a thief holding an unlocked,
actively-running device reads whatever the app can display (revoke the
device) - a boundary App-lock (see below) does not change, since its own
protection is idle/asleep/off, not a device someone is actively using
unlocked; and stealing the whole node directory while the node is
unlocked reads cached history exactly as before. With App-lock enabled
and the node locked, that same stolen directory no longer yields the
device/identity/encryption keys or the storage key that opens cached
content - see "App-lock" below for exactly what is sealed and, stated
just as plainly, what a locked directory still exposes (the Tor onion
key; recent story media and the friend/peer list in the local database).
Revoking a device logs it out on compliant clients
(wipes its keys and store) and structurally cuts it off from anything
new; a modified client cannot be forced to wipe.

Superseded daily-rotation enckey announcements are tombstone-pruned from
storage on each sweep, a growth fix; retired PRIVATE keys and the
seven-day grace window are untouched, so forward secrecy is unchanged.

Permanently-undecryptable envelopes — messages encrypted to keys
permanently deleted — are negative-cached by the background sweep only
(retries skipped on future sweeps); views still attempt decryption every
time a message is displayed so a materializing key is never stale. The
cache is never persisted while locked and clears entirely on unlock or
the moment a matching key materializes.

## Posts and scopes

Posts are encrypted to a ring, exactly like DMs - there is no plaintext
post and no universal public wall. Every post picks a scope: **Kreds**
(all your current friends) or **Inner** (a hand-picked subset you manage
per friend). The post body is encrypted once and its content key is
wrapped individually for every device in the chosen ring plus your own
devices, so the post exists only on its audience's storage; anyone
outside the ring - including a mutual friend relaying the raw
ciphertext - holds bytes they cannot read.

Honest limit: the wrap-set travels with the post, so recipients (and
anyone relaying it) can see which devices a post was addressed to, even
though only those devices can decrypt it. Ring membership itself (who is
Inner) is never gossiped to anyone but your own other devices.

## Curated profile

Every post also picks a **placement**: Journal (the default - shows in
your home feed and in your profile's Journal section) or Profile (shows
only on your profile's Wall - never in anyone's home feed). Placement is
orthogonal to scope: a profile post is still encrypted to whichever ring
(Kreds/Inner) you choose, exactly like a journal post - there is no
separate, less-protected posting channel. (Like `scope`, `placement` is
plaintext metadata in the signed post, so a device relaying the ciphertext
can tell a wall post from a journal post - it just cannot read either.)
The split is enforced at read
time, in the same decrypt-and-filter path the feed already uses: a
journal post can never leak onto a wall, a wall post never leaks into
the feed or a Journal section, and this holds for expiring posts too -
an expiring journal post shows in Journal until it expires and never on
the Wall, regardless of its expiry.

Every profile page - yours or a friend's - is a **block canvas**: your
Wall (profile posts, posted intentionally to that page) rendered as
typed blocks by content, newest-first - a text-only post is a **text
block**; a post with one photo is a **big photo block**; a post with
several photos is a **gallery grid block** - plus a compact **Journal
rail** in the right column (their journal posts you can currently
decrypt), which shows on every profile, self or visitor. Your own
profile page renders exactly the way a friend sees it - same banner,
avatar, header, canvas, rail - with two differences: a small cogwheel
button opens an edit overlay (name, bio, accent, avatar shape/size/
placement, banner) on top of the page instead of an inline dump, and a
composer above your own canvas (with its own photo picker, mirroring
the journal composer's) posts directly with `placement=profile`. On
narrow screens the Journal rail collapses behind a "Journal" button and
expands on tap, instead of a fixed side column.

The Me tab is just a shortcut to this same page: it opens your own
profile immediately, with Friends and Devices stacked below the Journal
rail, self-only (nobody visiting your profile sees that part - visitors
see the canvas and the rail only).

**Slice 2 - Arrange mode.** Your own Wall can now be put into a
specific order, not just newest-first. A self-only "Arrange" button
next to the cogwheel switches each of your blocks into Up/Down
reorder controls (real keyboard-operable `<button>`s, not
drag-and-drop yet); tapping "Done" publishes the new order once, as a
signed, latest-wins `profile_layout` record naming your block ids in
order. Anyone viewing your Wall - including a friend, after their next
sync - renders your blocks in that chosen order; any block you post
*after* arranging (not yet in the record) surfaces at the very top,
newest-first, until you arrange again. Naming a block in the order
adds no *content* surface: the order only ever reorders posts a
viewer could already decrypt - an id they can't decrypt, or that
doesn't exist, is simply absent from what they render, so no post
body ever leaks. It is **not** fully metadata-free, though: the
`profile_layout` record is a plaintext list of block ids, so a
Kreds-only friend can see *how many* arranged blocks exist that they
can't open (opaque ids only, no content) - the same class of
existence/count disclosure documented for the friend-graph `have`
frame. A per-scope layout record that hides that count is deferred to
Slice 3; for now it's a known, documented limit. Two more fixes ship
alongside: a visitor to your profile now sees *your* chosen accent
color and uploaded banner/avatar (not their own identity-hue
derivation) - the profile header is finally "your page," not
"however I'd render you"; and each of your own blocks now shows a
small Inner/Kreds scope badge (visible to you only, on your own
canvas), with a one-line composer note reminding you that moving
someone into a ring only ever reveals *future* posts, not the back
catalog. Proven end-to-end by a two-node integration test over real
sync sockets (`tests/test_profile_arrange_integration.py`): A arranges
three Wall blocks into a chosen order, B's synced view matches it for
every block B can decrypt, and a fourth block A posts afterward lands
at the top of B's view, ahead of the arranged three.

**Slice 3a - Pointer-events drag-and-drop.** Arrange mode's reorder
controls are no longer Up/Down-only. Each of your own blocks now also
shows a drag handle (grip icon); pressing and dragging it live-reorders
the canvas by neighbor-midpoint, the same feel as Trello/Sortable -
built hand-rolled on the **Pointer Events API** (mouse, touch, and pen
through one code path), deliberately not native HTML5 drag-and-drop
(no touch support, an uncustomizable ghost image) and with no
drag-and-drop library dependency. Up/Down buttons are unchanged and
stay the keyboard/screen-reader path - dragging is additive, not a
replacement. "Done" still just reads the resulting DOM order and
publishes it through the exact same Slice-2 `profile_layout` record -
no protocol, backend, or store change. A drag that nears the top or
bottom edge auto-scrolls the page so a long canvas stays reorderable
without lifting the pointer, and a cancelled gesture (`pointercancel`)
restores the pre-drag order rather than leaving a half-completed
reorder on screen. Verified end-to-end with Playwright against two
isolated real nodes on free, non-demo ports: a real mouse drag
live-reorders and lands on release; "Done" persists across a full page
reload; a friend's node picks up the new order over a real gossip
sync, in both its raw `profile_view` and its own rendered UI; an
**emulated touch drag** - driven through Chromium's real touch-input
pipeline via CDP `Input.dispatchTouchEvent` (genuine `pointerType:
"touch"` active pointers with a real drag gesture, not a scripted
`dispatchEvent` - Playwright's own touchscreen API only exposes a
single tap, not a multi-point drag) - also live-reorders and lands;
keyboard Up/Down still works via focus + Enter/Space on the real
buttons; a drag lingering at the bottom edge measurably auto-scrolls
the page; and a genuine `pointercancel` (via CDP `touchCancel`, away
from any edge) restores the exact pre-drag order.
**Edge case found by this smoke pass, and its fix:** when a drag lingers
inside the auto-scroll margin long enough for several `window.scrollBy`
calls to fire, Chromium can intermittently fail to deliver the
terminating `pointerup`/`pointercancel` to the dragged block (reproduced
several times, not every attempt, via both mouse and CDP-touch
automation) - a scroll/pointer-capture interaction in the browser's own
input pipeline, not app logic naming the wrong element. The stuck-state
half is **fixed**: the drag also listens for `lostpointercapture`, which
the browser guarantees to fire whenever pointer capture is released, even
when it drops the up/cancel - so `finish()` (idempotent) always runs, no
block is left in `.dragging`, and no listener leaks. The honest residue:
on such a dropped event we finish with the *current dragged order* (the
right outcome for a completed drag), so a dropped `pointercancel`
specifically keeps the dragged order instead of restoring - away from the
margin, cancel-restore is 100% reliable. The candidate improvement for the
underlying dropped-event behavior (driving auto-scroll off one
`requestAnimationFrame` loop instead of a `scrollBy` per `pointermove`)
remains a follow-up.

**Slice 3b - Configurable photo grids.** A multi-photo block can now
be re-styled into one of five layouts - Auto (the original gallery
grid), 2 columns, 3 columns, Hero (first photo full-width, the rest in
a row beneath), or Masonry (Pinterest-style flowing columns) - and
restyled again later, in place. The style lives in the same
`profile_layout` record Slice 2 introduced, as a new `grids` map
(`{msg_id: layout}`) alongside `order`: a block's *content* is still
immutable (delete-and-repost only), but its *presentation* is a
mutable, re-publishable choice, because it's stored in the layout
record rather than the post itself. `node.set_block_grid(msg_id,
grid)` validates the style against a fixed enum, folds it into the
current `grids` map (dropping the entry entirely for `auto`, so the
map only ever holds explicit overrides), and republishes through the
exact same latest-wins record - carrying the *current* `order`
forward, so restyling a block never disturbs its position. Symmetric
fix the other way: `set_profile_layout` now carries the current
`grids` map forward when it republishes a reordered `order`, so
reordering never drops a block's chosen style. `profile_view`
annotates every wall block with `p.grid` (`grids.get(msg_id, "auto")`
- an unlisted/unknown id is simply `"auto"`, never an error). New `POST
/api/block-grid` (`{msg_id, grid}` -> 400 on an invalid grid or a
malformed id, mirroring `/api/profile-layout`'s error shape). **Front
end:** `renderBlock` picks the container class from `p.grid` - single-
photo blocks always stay `.block-photo` (big) regardless of any grid
choice, since a lone photo has no columns to configure; multi-photo
blocks get `.block-gallery` (auto) / `.block-grid-2` / `.block-grid-3`
/ `.block-hero` / `.block-masonry`. Two pickers, both self-only: a
`<select>` in Arrange mode next to each multi-photo block's Up/Down
controls restyles an *existing* block (posts to `/api/block-grid`,
then re-renders); a second `<select>` in the Wall composer, hidden
until 2+ photos are attached, sets the *initial* style of a new block
before it's ever posted. Proven end-to-end by a two-node integration
test over real sync sockets
(`tests/test_profile_grids_integration.py`, mirrors
`tests/test_profile_arrange_integration.py`): A posts a multi-photo
block, sets it to `cols3`, and B's synced `profile_view` shows
`cols3` for the block B can decrypt; A restyles it to `hero` and a
further sync shows B `hero`; A then reorders the wall, and the `hero`
style survives on B's side. Full suite green (320 passed, 1
pre-existing skip, confirmed clean across 3 consecutive runs) plus
`node --check` clean. Verified live with Playwright (29/29 checks)
against two isolated real nodes on free, non-demo ports: the composer
picker's initial choice (`cols3`) rendered correctly on the new block
and matched what `/api/profile` reported; cycling a block through all
five layouts in Arrange mode rendered each distinctly by DOM class and
computed style (`cols2`/`cols3` grid-template-columns of 2/3 tracks,
`hero`'s first image spanning the full container width while the rest
don't, `masonry`'s `column-count: 3`) - with one honest exception
recorded rather than papered over: **`auto` and `cols2` render
pixel-identical** for a given set of photos, by design (both are a
2-column grid with the same gap) - the DOM class differs
(`.block-gallery` vs `.block-grid-2`) but there is no visual
difference to assert for that specific case, so the smoke asserted the
class instead and says so here; "Done" persisted the chosen layout
across a full page reload; a single-photo block stayed `.block-photo`
throughout; reordering the wall kept the restyled block's layout
class; and a friend's node, after a real gossip sync, rendered the
same layout the owner chose and had no Arrange button or grid picker
of its own (visitor, not owner) - zero console/page errors throughout.

**Slice 3c - Video blocks.** A video block is a Wall post carrying a
transcoded, per-recipient-**encrypted** video (mp4 + poster), reusing
the exact Story gate (`hearth/videogate.py`'s `transcode_video`,
unmodified: probes duration, rejects over 15s, downscales to 720
tall, strips audio, re-encodes H.264 mp4, extracts and transcodes a
poster frame, enforces a 5 MB output cap) - but unlike Stories
(plaintext, ephemeral, no audience concept), the mp4 and poster are
both run through the same `encrypt_blob` path as photo blobs, wrapped
to the post's chosen Inner/Kreds scope, so only that audience can ever
decrypt either one. Posts gain `media` (`"photo"`/`"video"`) and
`poster` fields; `media="video"` requires exactly one blob and a valid
poster hash, `media="photo"` (or absent, the default - every
pre-existing post stays valid) must not carry one.
`node.compose_post(..., video=bytes)` branches before the photo path;
`/api/post` gains a `video` upload field, gated by its own generous
raw-upload cap (`MAX_VIDEO_UPLOAD`, 100 MB, checked and rejected with
**413** before the slow gate ever runs) independent of the gate's own
stricter 5 MB output cap - a >15s clip or a non-video file surfaces as
a clean **400** (the gate's `ValueError` message, e.g. "video longer
than 15 seconds" or "not a video," via the existing `_400` mapping).
**Front end:** `renderBlock` gets a video branch - a real `<video>`
with `controls`, `playsInline`, and a `poster` frame, deliberately
**no `autoplay`** anywhere in the branch or the file; the Wall
composer gets a video picker (mirroring the photo picker's
keyboard-reachable hidden-input pattern) that is mutually exclusive
with the photo picker (video wins if both are somehow attached, one
medium per block); Arrange mode's per-block grid-layout `<select>` is
explicitly skipped for video blocks (a video has no columns to
configure) while Up/Down reordering stays available on them like any
other block. Proven end-to-end by a two-node integration test over
real sync sockets (`tests/test_profile_video_integration.py`): a
Kreds-scoped video block reaches a friend, who decrypts both the mp4
and the poster from the synced ciphertext; an Inner-scoped video block
never reaches (and so never appears on the wall of) a friend who stays
Kreds-only. **Bug this test caught, fixed in the same pass:**
`Store.referenced_blobs()` - which drives both what a sync round
requests from a peer (`missing_blobs()`) and what survives local blob
GC (`gc_blobs()`) - collected a `KIND_POST`'s `blobs` list but never
its new `poster` field (it already did this correctly for
`KIND_STORY`). In practice this meant a video block's poster blob
silently never transferred to a friend over a real sync round (the mp4
would arrive and decrypt fine; the poster would not, `post_blob`
returning `None` for it) and was structurally at risk of being GC'd
out from under the *author's own* node, too - a gap invisible to the
single-node tests in `tests/test_profile_video.py`, which never
exercise a second store. Fixed by adding the post's `poster` (when
present) to the same reference set the story/profile media already
use; the fix is one file (`hearth/store.py`), touches no protocol or
crypto, and is covered by the new integration test going green.
Full suite green (328 passed, 1 pre-existing skip, confirmed clean
across 2 consecutive runs) plus `node --check` clean. Verified live
with Playwright (35/35 checks) against two isolated real nodes on
free, non-demo ports: attaching a short clip in the composer
transcoded and posted a video block whose `<video>` carried a real
poster and controls and no `autoplay` attribute anywhere in its
markup; **real playback succeeded in headless Chromium** on both the
poster's own node and, after a real gossip sync, on the friend's node
(`video.play()` measurably advanced `currentTime` on both) - the
brief's documented headless-unreliability fallback (DOM + a decrypted
`/api/post-blob` 200 check) was exercised as a belt-and-braces
confirmation alongside actual playback, not needed as a substitute for
it; a 20-second clip and a non-video file each surfaced a clear 400 +
composer alert and posted no new block; text and multi-photo/grid
blocks kept rendering exactly as before, and Arrange mode showed the
grid-layout picker on the photo block but correctly never on the video
block (Up/Down present on both); the friend's synced view rendered the
same video block, decrypting both mp4 and poster through its own node,
with no Arrange button (visitor, not owner); zero unexpected console
errors (the two deliberate error-case posts' own expected "400" resource-load
messages were identified and excluded explicitly, not silently
dropped from the count).

**Bento canvas Phase A.** The Wall stops being a single-column vertical
stack and becomes a real **bento grid**: every block picks a **width
size** - `small` (1 column), `wide` (2 columns), or `full` (3 columns,
the default, so an unarranged Wall still looks like the old stack) -
and packs into a native **CSS 3-column grid**
(`grid-template-columns: repeat(3, 1fr)`, blocks span their size via
`grid-column`). On narrow screens the grid clamps to 2 columns and
`full`/`wide` both clamp to span 2, so nothing overflows. This is
deliberately **width-only, auto-height** packing - a hand-rolled
masonry/collision engine is out of scope for Phase A, so a short block
can leave a small visual gap beneath it; true masonry is a named Phase
B item, not a bug. The size lives in the same mutable
`profile_layout` record Slice 2/3b already extended - a third map,
`sizes` (`{msg_id: "small"|"wide"|"full"}`), alongside `order` and
`grids` - so a block's *width* is as re-stylable as its *photo-grid
layout* already was, without touching the immutable post.
`node.set_block_size(msg_id, size)` validates the enum, folds it into
the current `sizes` map (dropping the entry for the default `full`, to
keep the map minimal), and republishes carrying `order` and `grids`
forward unchanged; symmetrically, `set_profile_layout` and
`set_block_grid` now carry the current `sizes` map forward too, so
reordering the Wall or restyling a block's photo grid never resets its
width. `profile_view` annotates every wall block with `p.size`
(`sizes.get(msg_id, "full")`). New `POST /api/block-size`
(`{msg_id, size}` -> 400 on a bad size or malformed id, mirroring
`/api/block-grid`'s error shape).

**The editing model changes too.** The old per-block controls on the
block face - a 3-line drag handle, inline Move Up/Down arrows, and an
inline photo-grid `<select>` (Slice 3b) - are gone. In Arrange mode,
**tapping a block opens a settings modal** (`#block-settings`)
consolidating everything that used to be scattered across the block
face: **Size** (Small/Wide/Full buttons, current one highlighted),
**Photo layout** (the Slice 3b grid options, shown only for a
multi-photo block), and **Move** (Up/Down, the same reorder Slice 2
shipped, now modal-housed instead of inline). Each control applies
immediately - posts to the relevant endpoint, updates the live DOM
node in place, and refreshes the modal's highlighted option - so a
setting change doesn't wait for "Done." Tap and drag share one
`pointerdown` handler and are told apart by movement: a release under
a ~6px threshold opens the modal, movement past it hands off to the
existing pointer-drag reorder (now 2D-aware over a multi-column grid -
it finds the *nearest block center*, not just a vertical midpoint, so
dragging across columns works correctly). A block also gets a small,
always-visible gear button (`.block-settings-btn`) that opens the
identical modal - a real, natively focusable `<button>`, reachable by
Tab and activated by Enter/Space, giving Arrange mode a keyboard path
that tap-to-open alone never had (a pointer gesture has no keyboard
equivalent). Esc and a backdrop click still close the modal, as
before. Proven end-to-end by a two-node integration test over real
sync sockets (`tests/test_profile_bento_integration.py`, mirrors
`tests/test_profile_grids_integration.py`): A sets three Wall blocks
to `wide`/`small`/`full`, and B's synced `profile_view` shows the
matching size for each block B can decrypt; A then reorders the Wall
and restyles one block's photo grid, and every block's size survives
both changes on B's side. Full suite green (339 passed, 1 pre-existing
skip, confirmed clean across 2 consecutive runs) plus `node --check`
clean. Verified live with Playwright (29/29 checks) against two
isolated real nodes on free, non-demo ports: a tap on a block (a
sub-threshold click) opened the settings modal; picking Wide/Small/Full
changed the block's `size-*` class and its rendered width, applying
immediately server-side rather than waiting for "Done"; the gear
button opened the same modal, and a real Tab press from the block's
own "Delete everywhere" button landed on the gear next, with Enter
opening the modal from there - genuine keyboard reachability, not just
a focusability check; a multi-photo block's modal showed the Photo
layout group (a text-only block's did not) and setting "3 cols" applied
`block-grid-3` to it; a deliberate >6px drag reordered the block without
ever opening the modal; "Done" persisted the dragged order
server-side; the old inline `<select>`, `.drag-handle`, and inline
Up/Down buttons were confirmed absent from the block face; a friend's
node, after a real gossip sync, rendered the same sizes, the same
photo-grid style, and the same post-drag order the owner had set, with
no controls of its own (visitor, not owner); and a 390px mobile
viewport packed the wall into 2 grid columns with no block overflowing
it.

Honest limit / what's next: a block's *content* still can't be edited
in place - delete-and-repost remains the only way to change what a
block says (its *presentation*, now including *width*, is re-stylable
in place). **With Slice 3c, the tractable three - drag-and-drop,
configurable grids, and video blocks - shipped; with bento canvas
Phase A, per-block width sizing and a consolidated settings modal ship
too.** **Phase B of the bento canvas** - free x/y placement,
corner-drag resize, two-finger pinch, height/row spans, true masonry -
is the deferred next step for the canvas itself. **Split-column
blocks** and **versioned edit-in-place** remain separately deferred -
larger redesigns of the canvas model and the post-immutability
assumption, respectively - with no design consensus yet on either.

**Image lightbox.** Clicking (or tapping) a photo in any profile photo
block - single, gallery, or a grid/hero/masonry layout, on your own
profile or a friend's - opens it fullscreen. Left/right arrow keys,
on-screen prev/next buttons, and touch swipe move through *that block's*
photos (clamped at the ends; a single-photo block shows no arrows); Esc,
the close button, or a tap on the backdrop dismiss it, returning focus
to the photo you opened. It is view-only and identical on your own and
others' profiles, never fires in Arrange mode (there a tap opens the
block settings), and skips video blocks (they keep their own player).
No zoom/pan yet, and the home-feed photos don't open the lightbox -
both are named follow-ups.

**Three more Phase A limits, surfaced by a whole-branch review, stated
honestly rather than silently patched over:** the bento grid packs with
`grid-auto-flow: row dense`, which lets the browser reflow a block into
an earlier gap to avoid a hole in the layout - so the *visual* position
of blocks can end up out of step with the *DOM* order that Move Up/Down
and drag-reorder actually operate on, and a "move" can therefore land a
block somewhere that doesn't look adjacent to its new neighbors on
screen. It is still a correct DOM move; only `dense`'s visual
reshuffling makes it look otherwise, and true masonry (Phase B) is the
eventual fix for the gap this packing mode papers over. Separately,
every block in Arrange mode sets `touch-action: none` so its own drag
surface doesn't fight the page's scroll gesture - which also means a
tall Wall is hard to scroll by touch while arranging (mouse wheel and
the drag's own edge auto-scroll both still work); Phase B's
long-press-to-arm drag (touch-action stays default until a hold starts
one) is the named fix, leaving ordinary touch-scroll free the rest of
the time. Finally, `/api/block-size`, `/api/block-grid`, and
`/api/profile-layout` are each a read-modify-republish of the one
shared `profile_layout` record, not an atomic update - two concurrent
writes to the same profile (two open tabs or devices editing at once)
can race, and the second write's republish can silently overwrite the
first's change. This is the same latent property `order`/`grids` already
had before Phase A's `sizes` map; fine for the single-user-loopback
usage today, not yet safe for genuinely concurrent multi-device editing.

## Deletion

Deletion is structural against strangers - they never had it - and
automatic among friends running compliant clients. Deleting sends a
signed delete tag that gossips to every device holding the content;
compliant clients tombstone the message, drop its media blobs, and
refuse re-ingest - in any arrival order (a delete tag arriving before
its content still wins; fixed and tested for all message kinds).

The boundary, stated honestly: this is compliant-client behavior, not
DRM. A modified client can keep what it already received, and a
screenshot survives everything. Only the author of a message can
delete it - a friend's delete tag for someone else's content is
rejected.

Delete tags are structurally immune to deletion: a delete tag naming
another delete tag is refused at creation and refused again on ingest.
A lurking meta-delete is tombstoned as invalid once its target is
confirmed to be a tag, closing a divergence path where deleting a delete
tag halted its propagation — some nodes would apply it before the
meta-delete arrived, others would never apply it.

## Unfriend

Removing a friend is signed-notice-driven and direct-only - never a
block. On your device, `unfriend()` tears down the relationship
immediately and locally (drops the friend, their content, your DM
thread with them, and any ring record about them) and queues a signed,
self-authenticated defriend notice. Delivery dials the friend directly -
never mesh/broadcast: the notice rides on a session with its exact
target and nothing else is served on that connection - and keeps
retrying, un-acked, for up to 14 days.

On the recipient's side, an applied notice makes their device
self-delete everything the remover authored, forget the remover's
identity, and mark them "no longer connected" in the UI - a
return-and-see state, not a ban. There is no block list: the only way
back is the same in-person invite ceremony used to become friends in
the first place, which also clears the "no longer connected" marker.

Resurrection is structurally blocked, not just discouraged: once a
recipient has forgotten the remover, a mutual friend who still holds
the remover's old content cannot hand it back - the same
unknown-identity gate that refuses strangers' messages also refuses a
forgotten friend's, even relayed by a mutual friend. Proven directly by
a live three-node integration test: purge a friend on one device, sync
it with a third node that still holds the removed friend's post, and
the content is never re-accepted.

Honest limits, stated plainly: this is compliant-client behavior, like
deletion. An honest client deletes the remover's content the moment it
applies the notice; a modified client can keep what it already
received, and a screenshot survives everything. A device that never
comes back online within the 14-day delivery window never receives the
notice at all, and keeps the remover's content until it does (or until
re-friending supersedes it). A bare refusal or an offline peer never
triggers a purge - only a verified, delivered notice does.

## App-lock

A device-local screen lock for the desktop client. Pick a PIN or a
passphrase - a UI-only distinction (numeric keypad vs. text field); the
credential is just a string either way, and the crypto path underneath
is identical - and the node comes up **locked on every restart** until
you enter it.

**The at-rest model.** Your device's secret key material - the device
signing key, the identity-key replica, the paper backup seed (the same
seed `paper_seed.txt` holds at identity creation - see below), the
DM/post encryption key plus its retired grace-period keys, and the
content-key cache's storage key - is sealed into `applock.json` under a
master key derived two ways at once: `scrypt(credential)`, combined via
HKDF with a random 32-byte device secret that exists only sealed behind
**Windows DPAPI** (tied to your Windows login) inside that same file.
Both factors are required: a wrong credential fails the
ChaCha20-Poly1305 authentication tag before anything decrypts, and the
sealed device secret cannot be unsealed on a different Windows account or
a different machine. This is why a short PIN is safe here at rest - the
device secret supplies the real entropy, the credential is just what you
type. `keys.json` never holds any of those secret fields once App-lock is
on, only public/non-secret state plus an `"applock": true` marker so a
reboot knows to come up locked; `paper_seed.txt`, if it still existed on
this device, is folded into the same sealed bundle and deleted from disk
the moment App-lock turns on, and only written back out in plaintext if
App-lock is later disabled.

**Boot-locked, not just screen-locked.** A locked node holds no private
key material in memory at all - signing and decrypting raise outright,
they don't silently no-op - and the API layer returns **423** for every
route except unlocking and reading lock status (an exact-path allowlist,
not a prefix match: `/api/applock/settings`, for instance, is still
gated even though it shares a prefix with the allowlisted
`/api/applock`). Unlocking rebuilds the real keys from the decrypted
bundle; locking - manually, or automatically (below) - drops them from
memory again.

**Auto-lock is node-tracked, not browser-tracked.** The node's own
periodic loop, not the HTTP layer (request timing isn't a reliable clock
for detecting sleep), locks on either: idle - no allowed API request for
N minutes, off by default - or a wall-clock jump bigger than the loop
interval plus margin, the signature of the process having been suspended
and just resumed. A manual "Lock now" is also always available in
Settings. There is **no auto-wipe** on a wrong credential, ever - only
an escalating in-memory delay (0, then 5s, 30s, 300s as failures mount)
that slows down guessing and resets on success or process restart.

**Honest threat model, stated plainly.** App-lock protects exactly the
bundle sealed into `applock.json`: the device signing key, the
identity-key replica, the paper backup seed, the DM/post encryption key
plus its retired grace-period keys, and the content-key cache's storage
key. A device sitting idle or asleep, a device stolen while off, and a
stolen `keys.json` + `applock.json` pair on their own all fail to yield
any of that - without also having the Windows login that seals the
device secret, DPAPI cannot unseal it.

**What a stolen, locked directory still exposes, named plainly (not
sealed by this pass):** the Tor **`onion_key`** file - your onion
service's private key - stays plaintext, so a thief can stand up your
`.onion` address elsewhere (address impersonation) but reads no content
from it, since every real message still sits behind the device-key
handshake above. `hearth.db` also stays plaintext at rest: a thief reads
your friend graph and known peer addresses, plus any story media less
than 24 hours old (older stories are already gone to expiry regardless of
lock state). Sealing `onion_key` and encrypting the database at rest are
both tracked as follow-up work, not attempted in this pass.

It does **not** protect: malware, or a shoulder-surfer, while the app is
genuinely unlocked and running - that was never what a lock screen is
for. OS-suspend detection is a heuristic (a wall-clock jump the node
notices on its next tick), not a real OS suspend/resume hook, until a
packaged desktop wrapper can subscribe to that event directly. App-lock
is **Windows-only for now**: DPAPI is a Windows API, and no off-Windows
fallback has been built, so the feature is simply unavailable - not
weaker - on other platforms today. Deferred: a pattern-style credential,
biometric unlock, true OS-suspend hooks, and syncing lock settings across
your devices.

## Video editor: trim, crop, cover

A composer video pick - wall/profile post or a Story - no longer either
takes the raw file as-is or bounces off a hard 15-second reject with an
apologetic "coming soon." It opens a fullscreen editor
(`openVideoEditor` in `hearth/web/app.js`) that lets the user choose a
<=15s window, an aspect crop, and a cover frame, then hands the result
to the same node-side gate that already existed
(`hearth/videogate.py`'s `transcode_video`), which now optionally
executes it in one ffmpeg pass instead of only validating the whole
file (spec `docs/superpowers/specs/2026-07-18-video-trimmer-design.md`).

**Client simulates, node executes - there is still no server in this
picture.** Kreds has never had a server component; "processing" a video
always meant the user's own node, the local Python process the web UI
already talks to over loopback. The editor doesn't change that
boundary, it just adds a step in front of it: the modal drives a local
`<video>` element against the picked File via an object URL, drawing a
filmstrip by seeking and canvas-capturing frames client-side, and
everything the user does - dragging the trim handles, panning/zooming
under an aspect frame, dragging the cover marker - is pure UI state
(`start`, `end`, `aspect`/`zoom`/`cx`/`cy`, `coverAbs`) that never
touches a video byte. On Done, `buildEdit()` reduces that state to the
wire object below, and the ORIGINAL file (not a re-encoded one) uploads
to the node alongside it - the upload is still local-machine-to-local-
process, so shipping the whole original is nearly free. The node's
bundled ffmpeg then does the one real cut/crop/transcode. **The trust
property this preserves, restated because it's the whole point:** a
friend's node only ever renders bytes a Kreds videogate produced -
never the browser's preview, never the original the author picked. The
editor's live preview, the filmstrip, the crop transform - all of it is
cosmetic scaffolding for choosing parameters; if the browser's crop math
and ffmpeg's crop math ever disagreed, the wire format is defined
against the DISPLAY-oriented frame precisely so they can't (see below).

**Wire format.** One new optional multipart field, identical on
`/api/post` and `/api/story`:

    video_edit = {"start": float,      # seconds into the source
                  "duration": float,   # 0 < d <= 15.0
                  "crop": {"x","y","w","h"} | null,  # normalized 0..1
                  "poster_t": float}   # seconds into the CUT, 0 <= t <= duration

`crop`, when present, is normalized to the DISPLAY-oriented frame - what
the user actually saw in the preview - not the raw decoded frame. This
matters because ffmpeg autorotates on decode by default (reading the
container's rotation metadata, e.g. a portrait phone clip stored
sideways with a `tkhd` rotation flag) and so does the browser's
`<video>` element; as long as neither side is told to skip that step,
"the frame the user cropped against" and "the frame ffmpeg crops" are
the same frame. `hearth/videogate.py` calls this out explicitly in a
comment on the crop-filter construction inside `transcode_video` - never
pass `-noautorotate` here - and an earlier commit in this slice built a
genuine rotated-fixture test (real MP4 box surgery on a `tkhd` rotation
matrix, not a hand-set flag) specifically to catch a regression on this
exact point, because it's the classic mismatch bug in any
client-crops/server-cuts design. `validate_video_edit`
(`hearth/videogate.py`) re-derives and clamps everything server-side
rather than trusting the client's arithmetic: duration must be `(0,
15]`, `poster_t` inside `[0, duration]`, crop bounds inside `[0,1]` with
a `1e-4` slack for client float drift and a 0.1 minimum on each axis - a
bad shape anywhere is a clean 400, never a silent clamp that posts
something other than what the user chose.

**Degraded mode is a two-rung ladder, not a single fallback.** The
editor assumes a `<video>` element that can decode the picked file, but
two increasingly worse things can go wrong, and each has its own honest
floor:
- **Metadata reads, frames don't** (a codec the container header
  understands but the engine can't paint - most often an unusual codec
  in a given WebView2/Chromium build). `buildThumbs()` fails, `degraded`
  flips true: the filmstrip is dropped, the aspect chips are disabled
  (`ve-disabled` - you can't position a crop you can't see), and trim
  becomes slider-only against the numeric time readout. The cover
  marker still works (it only needs a time, not a frame) and defaults
  to the window start. This is still a real, node-executed edit - just
  blind on the crop axis.
- **Metadata itself doesn't read** (the engine can't open the file at
  all). The `error` event fires before `loadedmetadata` ever does; the
  editor gives up entirely and calls back `{action: "raw"}` - today's
  pre-editor behavior: post the file unedited, and let the node's own
  `transcode_video` (no `edit` argument) validate and reject exactly as
  it always has (>15s source, not-a-video). Client capability never
  gates what the node can do; it only gates how much help the UI can
  give choosing parameters.

Both rungs, plus the "everything works" path, funnel into the same
`onClose({action, edit})` contract on both call sites (the wall
composer's video picker and its re-open-to-edit click, and the Story
"+" tile) - `action` is one of `"done"` / `"raw"` / `"cancel"`, so a
caller never has to special-case which rung produced the callback.

**The `-preset slow` rider.** Both the edited and un-edited paths now
encode at `-preset slow` instead of the prior `-preset veryfast` -
roughly 10-15% better quality-per-byte at the same `-crf 28`/bitrate
cap, for a few extra seconds on an encode that's already short (<=15s,
<=720p). This makes the no-edit path no longer byte-identical to
before, which is fine: the existing tests for that path assert behavior
(codec, cap, rejects), not exact output bytes, so nothing needed
touching to keep them honest.

**`codec` field, and the ladder it's for.** Every video post/story
record now carries `"codec": "h264"` (`node.compose_post` /
`compose_story`, `hearth/messages.py`'s `make_post`/`make_story`),
additive like `author_avatar` before it - an old client simply doesn't
read the field and behaves as before, no compatibility event for the
field itself. It exists because H.264 is not meant to be the only stop:
the recorded ladder is H.264 now (universal decode floor, including
whatever iOS client eventually ships), AV1 once chunked/streamed blob
transfer lands (by then the decode floor across active clients is
knowable), AV2 whenever real hardware decode silicon exists (not yet,
~2028+ by current estimate). Stamping `codec` now means a future
mixed-codec period - some posts H.264, some AV1 - can be told apart
per-artifact without guessing from bytes, and the switch itself is a
`min_core_for_web`-gated event like the 0.3.11 photo-gate change, not a
protocol break.

**Story raw-upload cap, and the asymmetry it fixes.** `/api/story` used
to check every upload - photo or video - against the 50 MB image cap
(`MAX_IMAGE_UPLOAD`), which made no sense for the trimmer's actual use
case: the whole point of an in-app trim is picking a long, large source
and cutting it down, not hand-pre-trimming a small file before upload.
Non-image media on `/api/story` now gets the same 100 MB ceiling
`/api/post`'s video field already had (`MAX_VIDEO_UPLOAD`,
`hearth/messages.py`), checked before the gate ever runs (a >100 MB
source is a clean 413, not a slow rejection after transcoding starts).
Telling image from video apart now goes through one shared sniff,
`is_image_bytes` (moved to `hearth/imagegate.py` so both `api.py` and
`node.py` call the identical function, instead of `node.py` owning the
only copy and the story endpoint having no way to call it) - the story
endpoint's cap check and the node's `compose_story` branch can no
longer disagree about what they're looking at. The gate's own output
ceiling (`MAX_VIDEO_BYTES`, 5 MB transcoded) and the 15s content cap are
unchanged by any of this - only the raw upload headroom moved.

Timing sanity (spec requirement, run once at the end of this slice): a
60-second 1080p `testsrc` source cut to a 15s window (`start=20,
duration=15, poster_t=5`) transcoded in under a second on this machine,
against the gate's 60s subprocess timeout - the cut happens via
`-ss`/`-t` before `-i`, so ffmpeg never decodes the parts of a long
source outside the window, leaving ample headroom even for sources far
larger than this test used. `_TIMEOUT` stays at 60; no change was
needed.

## Profile-load speed: render honesty, sync ordering, thumbnails, AVIF

Opening a new friend's profile used to take 1-2 minutes (August's own
report, 2026-07-18). Spec:
`docs/superpowers/specs/2026-07-18-profile-load-speed-design.md`, built
on the `profile-load` branch as one combined slice, four parts, no
protocol break in any of them except one additive field.

**Three causes, measured in the code, not guessed.**
1. **Budget/cadence.** `BLOB_GIVE_BUDGET` (`sync.py:49`) is `MAX_FRAME -
   1 MB` = ~15 MB of base64-encoded blob payload per sync round; after
   the ~4/3 base64 expansion that's ~11 MB of raw blob bytes per round -
   the number the spec cites. Onion peers only run that round every
   `ONION_SYNC_INTERVAL` (`messages.py:63`) = 45 seconds. A photo-heavy
   backlog bigger than one round's budget necessarily costs multiple
   45-second waits.
2. **Give order.** The pre-slice give loop walked the peer's wanted
   hashes in whatever order they arrived - content-addressed hashes, so
   effectively random relative to size - with no size-based ordering at
   all. One 9 MB video sitting anywhere in that order could fill an
   entire round's budget while twenty avatar/thumbnail-sized blobs waited
   behind it.
3. **Sharpest.** The profile page never self-healed. A not-yet-synced
   image had no `onerror` handler at all, so it rendered as the
   browser's broken-image glyph; the WS "changed" tick's `refresh()`
   re-rendered the journal/feed but explicitly skipped a currently-open
   profile view. A wall that started broken stayed broken until the
   visitor navigated away and back - a full manual reload was the only
   thing that actually re-fetched anything.

**Four-layer fix** (spec Parts 1-4; each independently shippable, and
none of them touches the wire beyond one additive field):
- **Render honesty (Part 1).** Every post/deck/journal `<img>` now goes
  through `blobImg()` (`hearth/web/app.js`): its `onerror` swaps the
  element for a `.img-pending` shimmer div sized to the same cell,
  instead of a broken glyph, ever. `refresh()` (the WS "changed"
  handler) now also re-renders the CURRENTLY OPEN profile - guarded off
  while `ARRANGING` (a re-render would tear the drag surface out from
  under a live pointer) and while `#block-settings` is open (it holds a
  live reference to its block element); both states are transient, so
  the next "changed" tick after either ends heals the wall. The deck
  viewer (`renderDeck`) deliberately does NOT call `blobImg()` - its
  `<img>` is one persistent element reused across flips by every
  prev/next closure, so `blobImg`'s `replaceWith` would tear it out from
  under them; it gets a narrower `onerror`/`onload` pair that just
  toggles the `.img-pending` class on that same element instead.
- **Sync ordering (Part 2).** The give loop (`sync.py:632-639`) now
  sorts the peer's wanted hashes ascending by locally-held size
  (`store.blob_sizes()`, one `SELECT hash, LENGTH(data) ... WHERE hash
  IN (...)`) before filling `BLOB_GIVE_BUDGET`; ties keep hash order for
  determinism, and hashes we don't hold sort last and fall out at the
  pre-existing `get_blob() is None` check, same as before. Node-side
  only, no wire change - an old peer talking to a new one simply
  receives small blobs first.
- **Thumbnails (Part 3).** `photo_thumb()` (`hearth/imagegate.py`)
  downscales an already-gated photo to <=640px long edge and re-encodes
  AVIF quality 50. `compose_post` calls it once per photo (and once on
  the video poster for a video post) right after
  `transcode_photo`/`transcode_video`, storing each thumb through the
  same `encrypt_blob`+`put_blob` path as its parent blob and appending
  its hash - or `None` on a `photo_thumb` `ValueError` (GIFs,
  undecodable bytes) - to a new index-aligned `thumbs` list riding the
  post payload (`make_post`, additive: `validate_payload` treats a
  missing `thumbs` as absent, and when present requires a list the same
  length as `blobs`). Wall/deck/lightbox rendering prefers the thumb
  hash permanently at tile size; only the lightbox steps up to the full
  hash.
- **AVIF for the full-res blob (Part 4).** `transcode_photo`'s quality
  ladder is now AVIF 70/60/50/40 (was JPEG 85/75/65/55); the old
  PNG-stays-PNG exemption is gone, so a screenshot posted as PNG now
  goes through the same ladder as everything else. `transcode()` gained
  a `fmt` param (`"png"` default, `"avif"` opt-in) so story stills and
  video posters can switch format without touching avatars/banners,
  which stay on the PNG-only call, untouched.

**Crypto note, and the spec correction it produced.** A thumb is
encrypted with the exact same per-post content key as its parent blob
(`compose_post` calls `encrypt_blob(key, photo_thumb(gated))` with the
identical `key` used for the post's own photo blobs, then `put_blob`s it
like any other blob), and `Store.referenced_blobs()` folds `thumbs` into
the same reference set `blobs`/`poster` already contribute - a thumb
syncs and gets GC'd exactly on its parent post's lifecycle, no separate
crypto or lifecycle path to keep in sync. The spec as written said
`node.post_blob(mid, h)` "must reject hashes not referenced by that
post, as today" - that is not actually how `post_blob` (`node.py:1925-
1933`) has ever worked: it fetches whatever blob is at hash `h` from the
store and decrypts it under the CALLING post's own content key
(`self._content_key(msg)`), with no check anywhere that `h` appears in
that post's `blobs`/`thumbs`/`poster` list. There never was a reference
list to check. What actually refuses a mismatched hash is
`decrypt_blob`'s AEAD auth-tag check (ChaCha20-Poly1305, `dmcrypt.py`)
failing under the wrong per-post key and returning `None` - every post's
content key is independently generated, so a hash belonging to a
different post's blob decrypts to nothing under this post's key.
`tests/test_profile_video.py::test_post_thumbs_aligned_and_served` pins
the corrected claim directly: it fetches another post's own blob hash
through THIS post's `msg_id` and asserts `None`, with a comment reading
"the per-post content key fails AEAD auth (the crypto IS the guard)" -
the actual (and stronger) mechanism, documented instead of a reference
check that was never implemented.

**Two review-caught fixes worth a permanent record** (both from the same
review pass, commit `edeb67a`):
- `referenced_blobs()`'s thumbs guard originally read `for t in
  (p.get("thumbs") or [])` - `or []` only substitutes on a FALSY value,
  so a hostile or malformed `KIND_DM` payload (DMs are never
  thumbs-validated the way posts are) carrying a truthy non-list
  `"thumbs": 1` would iterate an int and raise `TypeError`, taking down
  every sync round AND `gc_blobs()` with it - a one-message denial-of-
  service against any node that ingested it. Fixed by
  `isinstance(thumbs, list)`-checking the CONTAINER itself before
  iterating its elements, closing exactly the class of gap the original
  guard existed to prevent. Pinned by a new store-level test
  constructing that exact malformed DM payload.
- `hearth/api.py`'s `_sniff()` mis-served AVIF blobs before this fix:
  AVIF/AVIS are ISOBMFF containers, so they carry the same `ftyp` box at
  the same byte offset as MP4 - `_sniff` was labeling every
  `ftyp`-boxed blob `video/mp4` regardless of brand. It now reads the
  4-byte brand code right after the box (`data[8:12]`) and returns
  `image/avif` for `avif`/`avis` before falling through to the MP4 case
  - otherwise every post-photo/thumb/story-still/video-poster AVIF blob
  this slice introduces would have been served to the browser mislabeled
  as a video.

**Compatibility: no gating event, and why that's not a shortcut.** Blobs
are opaque, hash-addressed bytes to both `store` and `sync` - neither
ever inspects or interprets blob content, only length and hash.
Decoding an AVIF blob happens entirely client-side, in whichever
Chromium the viewer's browser/WebView2 embeds; that decoder already
exists today (desktop Chromium and WebView2 both decode AVIF natively)
and the future mobile floor (iOS 16+, Android 12+) covers it too.
Compare this to the two prior slices that DID need a gating event:
0.3.11's blob-cap raise (an old-cap peer flatly refuses a bigger blob
until it updates) and the `wrap_grant` record kind (an old peer's ingest
gate rejects an unknown `kind` outright). This slice adds neither a
bigger cap nor a new record kind - `thumbs` is an additive, ignorable
list field (same pattern as `poster`/`codec` before it), and the blob
bytes underneath are just bytes. The only thing that actually needs the
new core is CREATING an AVIF blob in the first place (encoding is
server/node-side, via the updated `transcode_photo`/`transcode`); an
old-core peer can already sync, store, and serve any hash a new-core
peer hands it, whether or not it can produce one itself.
`min_core_for_web` is untouched by this release.

**Measured numbers (transfer-size sanity check, the spec's honest-math
claim).** No real camera photos exist in this sandbox, so the check
gates 5 synthetic images approximating a representative posting mix -
four photo-like images (smoothed multi-octave noise plus fine grain, the
spread-spectrum shape of a real photograph, at four phone/camera-
realistic resolutions from 1200x1600 up to 4032x3024) plus one flat-
color/sharp-edge PNG "screenshot" (the case the spec calls out as
AVIF's biggest single win now that PNG-stays-PNG is retired) - through
both the CURRENT `transcode_photo` (AVIF)/`photo_thumb` pipeline and a
byte-for-byte reconstruction of the PRE-CHANGE JPEG-ladder
`transcode_photo` (copied verbatim from this slice's parent commit).
Results:
- Total full-blob bytes, OLD JPEG ladder: 1,967,929 B (1921.8 KB)
- Total full-blob bytes, NEW AVIF ladder: 1,073,291 B (1048.1 KB) - a
  **45.5% reduction**, inside the spec's ~40-50% estimate
- Total thumb bytes across the same 5 photos: 59,239 B (57.9 KB),
  averaging **11.6 KB/thumb** - comfortably under the spec's <=25 KB
  "typical" ceiling

The screenshot case alone: 10.2 KB old (PNG-stays-PNG kept it lossless)
vs 3.3 KB new (AVIF ladder) - the single largest relative win in the
set, matching the spec's called-out case exactly. These numbers
substantiate the spec's math; they are a sanity check on synthetic
content, not a controlled photographic benchmark, and are stated as
such.

Full suite green (963 passed, 8 env-gated skips - `TOR_E2E` plus seven
`UI_E2E` live smokes, this slice's `test_ui_smoke_profile_load.py`
included - confirmed clean across 2 consecutive runs). The three
`UI_E2E`-gated smokes this slice touches
(`test_ui_smoke_profile_load.py`, `test_ui_smoke_albums.py`,
`test_ui_smoke_video_editor.py`) - 4 passed, confirmed clean across 2
consecutive runs.

## Reactions, comments, and story replies (responses)

Journal posts get a fixed six-reaction bar and a flat comment thread;
stories get the same six reactions plus a reply, delivered as an
ordinary DM. Built on the `responses` branch per spec
`docs/superpowers/specs/2026-07-18-reactions-comments-design.md`
(amended during the build; see that file for the authoritative wire
shapes - this section is the durable "why," not a restatement of the
protocol).

**Why author-relay (the ingest-gate constraint).** `ingest_message`
refuses messages from unknown identities (`store.py`) - the same gate
that blocks unfriend-resurrection, load-bearing and deliberately not
weakened for this slice. A post's audience is the author's friends, not
the commenter's friends, and those two friend sets are not the same
people: a commenter's `KIND_RESPONSE` cannot sync directly to the rest
of the audience, because most of that audience has never verified the
commenter's identity and would refuse it outright at ingest. The chosen
shape routes every response through the author's own node instead -
`store.py`'s routing gate treats `KIND_RESPONSE`/`KIND_RESPONSES` exactly
like `KIND_WRAP_GRANT`'s plain wrap-set branch (responder -> author's
devices only, no grant-union widening, ever). The author's node folds
every response it receives into one re-signed `KIND_RESPONSES` record
(`node.process_responses`/`_rebuild_responses_record`, mirroring
`set_album`'s latest-wins-by-fresh-publish idiom exactly, just keyed on
`target` instead of `album_id`) and republishes it wrapped to the post's
own audience. One consistent comment section for every viewer,
verifiable authorship, and moderation for free, because the author's
node relaying a section IS the author's comment section. Honest cost,
stated plainly: fan-out waits for the author's node to be online (a
home node makes this invisible in practice) - one extra hop of latency
that a direct-broadcast design wouldn't have paid, in exchange for never
handing a commenter's raw message to strangers who can't verify it.

**Two-tier disclosure - and the near-miss that shaped it.** Naive
author-relay would hand the post's entire audience the commenter's real
identity key, including strangers who aren't the commenter's friends at
all - graph discovery through a side door, rejected outright. Every
response carries a per-post `alias_seed` (deliberately unlinkable
across posts) that strangers render as a neutral name + tinted avatar.
**Reviewer fix (whole-branch review):** the original implementation
drew `alias_seed` fresh via `os.urandom(16).hex()` on every
`compose_response` call - per RESPONSE, not per POST - so two comments
from the same responder on the same post could render as two different
aliases, contradicting the "same stranger reads as the same alias
within one post's thread" promise above. `identity.py`'s
`DeviceKeys.derive_alias_seed` now makes it deterministic instead:
HKDF-SHA256 over this device's raw Ed25519 signing key bytes
(`device_priv` - device-local, never transmitted, stable for the
device's lifetime, chosen over the also-device-local `enc_priv`
specifically because `enc_priv` rotates every `ENC_ROTATION_PERIOD` and
would have changed the alias mid-thread) with a domain-separated info
string (`hearth/alias-seed/v1`) produces a dedicated subkey, and
*that* subkey - never `device_priv` itself - is the HMAC-SHA256 key
over `target_msg_id`, truncated to the same 32 hex chars the wire
format already used. Same post -> same HMAC input -> same seed;
different post -> different input -> unlinkable seed; no one but this
device can compute it, since only this device holds `device_priv`.
**Accepted trade-off, stated honestly:** this is device-level
stability, not identity-level - the same identity commenting from two
of their own enrolled devices reads as two different aliases on the
same post. Real identity rides alongside as a `mutual_box`: sealed
per-recipient slots for the commenter's OWN friends' devices, opened
only by them. **The near-miss:** the obvious, structurally cheapest way
to build that box would have been the wrap mechanism already used
everywhere else in this codebase - DMs, posts, `enckey` announcements,
`wrap_grant` - one labeled entry per recipient device
(`{device_pub: wrapped_key}`). Reusing it here would have put every one
of the commenter's own friends' `device_pub`s in the clear on an entry
the ENTIRE post audience receives - disclosing the commenter's friend
graph to strangers, the identical hole this whole design exists to
close, just aimed at the commenter's graph instead of the author's.
Rejected for that reason; `dmcrypt.py`'s `seal_slots`/`try_open_slots`
build genuinely anonymous slots instead - an ephemeral X25519 key per
slot, HKDF into a per-slot key (`_derive_slot_kek`, domain-separated
from the AEAD's own `MUTUAL_BOX_AAD` by an intentionally-distinct
`-kek`-suffixed HKDF info string, so the two mechanisms can never be
mistaken for coupled), then ChaCha20-Poly1305 - with **no recipient
identifier anywhere in a slot**. Recipients trial-open every slot with
their own `enc_priv`; the one sealed to them decrypts, everyone else's
(including dummy slots) fails AEAD auth and looks identical from the
outside. Slot counts are padded to fixed buckets (8/16/32/64, dummy
slots filled with random bytes) so the count only weakly buckets, never
measures, the commenter's friend-device count - stated as an honest
residual disclosure, not hidden. **The anonymity property was
implementation-only until a reviewer pinned it structurally**: dummy-
slot ciphertext length has to exactly match real-slot ciphertext length
or the two are trivially distinguishable by size alone; `seal_slots`
now computes `ct_len` once from a real slot (or a same-shape estimate
when there are zero real recipients) and pads every dummy to it, proven
by boundary tests at the 8/64 bucket edges. Shuffling the slot order is
cosmetic hardening on top (breaks a "first N are real" positional
tell), not the anonymity mechanism itself.

The **author always sees the real identity** - the response is
addressed to them, and moderation requires knowing who wrote what. A
**settings toggle** ("show my name to people who don't know me",
default OFF, persisted through the same `store.get_meta`/`set_meta`
mechanism `close_behavior` already uses) flips a response to public: the
plaintext `responder`/`device_pub` ride openly on the entry and the
mutual box is omitted entirely, no per-post override in v1.

**Verification split, stated honestly.** Every response is signed by
the responder over a canonical payload (`identity.py`'s raw sign/verify
pair, `sign_raw`/`_sig_ok` - arbitrary bytes, no envelope/seq bump, the
same helpers Tasks 1-8 all reused unchanged) - so once a viewer has a
real `device_pub` to check it against, authorship is cryptographic, not
trusted. Mutuals get there two ways: the author resolves every entry
directly (routing hands the author literally every raw
`KIND_RESPONSE`, already cert-verified at ingest); a public entry's
`identity`/`device_pub` are read straight off the entry; a private
entry's `mutual_box` opens for whichever friend it was sealed to. Either
way, `_device_bound` (`node.py`) then checks the claimed `device_pub`
against `store.load_views` - this identity's actually-enrolled devices,
built automatically the moment any signed message from them is ever
ingested - because a sig check alone proves nothing: a forger controls
both sides of it, minting a fresh keypair and signing with the matching
private half passes verification while proving nothing about whose key
it is. Strangers who can't resolve any of that see only an
author-attested alias entry and trust the author's relay - the same
trust they already place in the author not having doctored the post
text itself. **The ratified boundary** (adjudicated in review, not a
new behavior): `_device_bound` is permissive when this viewer has no
view data at all for the claimed identity, returning `True` rather than
`False`, because that absence just as often means "this viewer has
simply never exchanged a message with them" as it means forgery - and
that permissiveness is exactly what lets genuine public engagement reach
strangers at all. The consequence, stated plainly: **a hostile author
can fabricate public identities, or inflate reaction counts, for a
viewer who has never talked to the claimed identity** (nothing available
to this viewer can refute it - same author-level trust as the tally
itself, which is rebuilt from the author's own record). What a hostile
author **cannot** do is impersonate an identity the viewer actually
knows: that case hits the `device_pub in views` branch instead, and the
sig check already guarantees whoever signed the entry really holds that
key.

**The fold's hostile-input hygiene.** Every decrypted field is
validated before it is ever republished or rendered, by two distinct
gates for two distinct paths. The fold/write path -
`_rebuild_responses_record`, which builds the author's own
audience-facing record from raw `KIND_RESPONSE` messages - decodes and
validates each one through `_response_event` (a malformed or hostile
raw response fails closed to `None` there and is skipped, never
allowed to raise out of the loop and abort every other post's rebuild
that round). The render path - `_post_responses_view`, which reads
back the entries already inside a (possibly hostile-author-crafted)
decrypted record body - repeats the same discipline independently via
`_valid_response_entry`, rather than trusting that the record was
actually built by the honest fold. Neither gate trusts the other: a
modified author client could skip `_rebuild_responses_record` entirely
and hand-craft a record straight into the encrypted body, which is
exactly the case `_valid_response_entry` exists to catch on the way
back out. A reviewer-caught Critical here: `decrypt_body`'s return
type is `Optional[dict]` as a hint, not an enforcement - `json.loads`
happily returns whatever JSON root a hostile or buggy author's plaintext
actually contains, and calling `.get("entries")` on a non-dict body used
to raise an uncaught `AttributeError` that took down the entire
`feed()`/`posts_by()` call for every OTHER post's row along with it, not
just the bad one. Fixed with an `isinstance(body, dict)` guard before
any dict access, matching every other fail-closed branch in the method.
Mutual-box payloads are bounded too (`MUTUAL_BOX_CT_HEX_MAX`, checked on
decode - `ingest_message`'s `validate_payload` is envelope-only and
never sees inside the encrypted body - with roughly 6.5x headroom over
a real 624-hex slot), and
comments are capped at `MAX_COMMENT` (500 characters) the same way every
other user-authored field in this codebase is length-bounded - a
hostile 2MB comment body or a response whose encrypted payload decrypts
to a list/string/int instead of the expected object both fail closed
rather than reaching a renderer or a republish.

**Monotonic per-instance `created_at`.** Windows' `time.time()` has
roughly 15.6ms granularity, and two `compose_response` calls fired back
to back - two comments, or a comment immediately followed by its own
retract - can land inside the same tick with an identical `created_at`.
Retraction and moderation both key an entry by `(responder,
created_at)` within `_rebuild_responses_record`'s fold, so a same-tick
collision meant retracting one comment could silently remove a
*different* comment from the same responder that happened to land on
the same timestamp - a real bug the controller reproduced, not a
theoretical one. Fixed with a strictly-increasing per-instance clock:
`compose_response` bumps `created_at` a microsecond past
`self._last_response_ts` whenever the raw clock reading isn't already
past it. Deliberately not persisted across a restart (`_last_response_
ts` resets to `0.0` in `__init__`) - a same-tick collision surviving a
process restart isn't a realistic shape, since the restart itself takes
far longer than one clock tick, so that residual gap is accepted rather
than adding on-disk state to close it.

**`removed_responses` PRAGMA+ALTER migration precedent.** The
moderation-tombstone table needed a new column (`rkind`, to tell a
reaction tombstone's per-responder cutoff semantics apart from a
comment tombstone's exact-match semantics) after some installs had
already created the table under its original shape. `CREATE TABLE IF
NOT EXISTS` is a no-op against an existing table - it does not add
columns - so `Store.__init__` now follows it with a `PRAGMA
table_info(removed_responses)` check and an `ALTER TABLE ... ADD
COLUMN` only when the column is actually missing, defaulting existing
rows to `'comment'`. A reviewer caught the sharp edge this default
creates: a *migrated* legacy tombstone that was actually a reaction
removal now carries the wrong `rkind`, and the reaction fold's cutoff
logic never consulted the exact-match set at all - so on an upgraded
install, that old tombstone was a silent no-op until the fold was
changed to check `removed_exact` before the cutoff, for both kinds, not
just comments.

**Stories-as-DMs: simplicity by not building a new mechanism.** Phase B
(story reactions/replies) is deliberately not the responses protocol at
all - no new kind, no relay, no aliasing, no mutual box. A story
reaction or reply is a plain DM to the story owner, with one additive
field, `story_ref: {story_id, media_hash}`, riding the existing DM
envelope (`make_dm`/`compose_dm`, `_valid_story_ref` in `messages.py`
shape-validates it at ingest and nothing more). This is the cheapest
correct answer available: the DM pipe already has confidentiality,
delivery, and a UI: it needed one field, not a second protocol surface.
**The honest correlation caveat**, tightened during the build after an
initial overclaim in the spec's own draft: `story_ref` rides the
envelope in the clear, the same disclosure class as a DM's own
`to`/`wraps` metadata, never inside the encrypted body - so a third
party mutually connected to both the reactor and the story owner can
correlate which story prompted a given DM (and in practice likely
already holds that story's blob anyway, via ordinary story gossip).
Only the correlation is exposed; the reply's own text/photos stay
inside the encrypted DM body exactly as any other DM would. `story_ref`
is shape-validated only, the same compliant-client precedent as
`KIND_DELETE.target` - never resolved against a real story the named
target actually posted.

**Honest limits, stated plainly (mirrors the spec's own "Honest
limits" section):**
- Comment/reaction fan-out requires the author's own node to be online
  to fold and republish - a laptop-only author's comment section updates
  only when they next come online, the same shape as wall wrap-grants.
- Retraction and moderation are compliant-client behavior, not DRM: a
  modified client can keep a comment it already received even after its
  author retracts it, or the post's author moderates it away - the same
  honesty stance deletion has always taken.
- Mutual-box slot buckets (8/16/32/64) weakly disclose a friend-device-
  count range, never an exact count or any recipient identity.
- A hostile author can fabricate public identities or inflate reaction
  counts for strangers (the ratified `_device_bound` boundary above);
  they cannot impersonate an identity a viewer actually knows.

**Verification.** Full suite green (1029 passed, 9 env-gated skips -
`TOR_E2E` plus eight `UI_E2E` live smokes - confirmed by direct
execution). All five `UI_E2E`-gated live smokes
(`test_ui_smoke_responses.py`, `test_ui_smoke_profile_load.py`,
`test_ui_smoke_albums.py`, `test_ui_smoke_video_editor.py`) - 5 passed;
`test_ui_smoke_responses.py`'s own three-node leg exercises the true
mutual-box pipeline in a real browser (Cleo, a third node who is a
mutual friend of the commenter but not the viewer, structurally cannot
be author-shortcut into resolving as an alias) alongside the story-reply
DM landing with its context chip.
