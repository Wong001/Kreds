"""Kreds Windows desktop shell: launch the node in a background thread and show
its web UI in a frameless pywebview window with our own chrome. Phase 1 (runs
from source); PyInstaller packaging is Phase 2. `import webview` is LAZY so this
module imports without the GUI dep (tests).

Frozen (packaged .exe) vs non-frozen (running from source) differ in three
ways, all gated on paths.is_frozen(): the node runs over Tor with the bundled
tor.exe (vs plain TCP for fast dev), the web UI is served from a writable,
auto-updatable copy seeded from the bundled assets, and startup is hardened
against a silent hang/blank-window (Tor bootstrap is slow)."""
import asyncio
import os
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.request
from pathlib import Path

from . import paths
from .runner import run_serve

def default_data_dir() -> Path:
    base = os.environ.get("APPDATA") or str(Path.home())
    return Path(base) / "Kreds"

def _free_port() -> int:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port

def _wait_http(url: str, timeout: float = 60.0, stop_check=None) -> bool:
    """Poll url until it answers 200 or timeout elapses. If stop_check is
    given, it is polled every iteration too and an immediate True return
    from it aborts the wait right away (returning False) -- lets a caller
    fail fast (e.g. the node thread already died) instead of always
    burning the full timeout (task review MINOR)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if stop_check is not None and stop_check():
            return False
        try:
            with urllib.request.urlopen(url, timeout=1) as r:
                if r.status == 200:
                    return True
        except Exception:
            pass
        time.sleep(0.15)
    return False

def _launcher_exe_path() -> Path | None:
    """The top-level launcher `Kreds.exe` (Task 3, packaging/launcher.py),
    computed from THIS (payload) process's own frozen executable location:

        install_root/versions/<version>/Kreds.exe   <- this process (sys.executable)
        install_root/Kreds.exe                        <- the launcher, 2 dirs up

    (packaging/build.ps1 lays the shipped tree out this way.) Returns None
    when not frozen (a dev/source run has no launcher to restart into --
    there is nothing built at that layout) or when the computed launcher
    path doesn't actually exist (unexpected layout / launcher missing) --
    either way `Api.restart()` degrades to a plain quit() rather than
    failing."""
    if not paths.is_frozen():
        return None
    exe = Path(sys.executable).resolve()
    versions_dir = exe.parent.parent            # install_root/versions
    if versions_dir.name != "versions":
        return None
    launcher = versions_dir.parent / "Kreds.exe"     # install_root/Kreds.exe
    return launcher if launcher.exists() else None


class Api:
    """window.pywebview.api bridge for the custom chrome."""
    def __init__(self, holder: dict):
        self._holder = holder          # {"loop": asyncio loop, "ev": shutdown Event}
        self.window = None
        self._maximized = False
        self._tray = None              # pystray.Icon, set by launch()
        self._tray_thread = None       # its daemon thread, set by launch()
        self._data_dir = None          # set by launch(); one-time balloon flag lives here

    def is_desktop(self):
        return True

    def get_startup_status(self):
        """Polled by the loading page (and app.js's onboarding poll):
        {"stage": ..., "pct": ...} mirroring runner's status callback via
        holder["startup"]. A recorded node-thread error overrides everything
        as stage "failed" - the loading page renders it in place (no
        separate error window)."""
        if "error" in self._holder:
            return {"stage": "failed",
                    "error": str(self._holder["error"])
                    + "\n\nSee app.log in the Kreds data folder."}
        return dict(self._holder.get("startup")
                    or {"stage": "starting", "pct": None})

    def minimize(self):
        if self.window: self.window.minimize()

    def show_window(self):
        """Tray 'Open Kreds' / double-click: restore a hidden window.
        pywebview marshals show/hide/restore onto the GUI thread, so
        calling from the pystray thread is safe (verify against the
        installed pywebview at implementation time and update this note
        if that ever changes)."""
        if not self.window: return
        try:
            self.window.show()
            if not self._maximized:
                self.window.restore()   # un-minimize if needed; a MAXIMIZED
                                        # window must not be forced Normal
                                        # (would clobber the layout and desync
                                        # the titlebar maximize toggle)
        except Exception:
            pass                        # window mid-teardown; nothing to do

    def hide_to_tray(self):
        """Close with close_behavior=keep: hide fully (no taskbar entry).
        A hidden window with no living tray has no way back, so fall back
        to a plain taskbar-minimize when the tray thread is dead - never
        strand the user (spec edge case)."""
        if not self.window: return
        alive = self._tray_thread is not None and self._tray_thread.is_alive()
        if not alive:
            self.minimize()
            return
        self.window.hide()
        self._notify_first_hide()

    def _notify_first_hide(self):
        """One-time balloon per data dir. The flag is shell-owned state in
        the data dir (like instance.lock), deliberately not node meta.
        Balloon failures never break the hide - pystray notify support
        varies by platform/backend."""
        try:
            flag = self._data_dir / TRAY_NOTIFIED_FLAG
            if flag.exists(): return
            # Written BEFORE notify() deliberately: a one-off balloon failure
            # must permanently consume the one-time balloon, never retry a
            # persistently failing balloon on every subsequent hide.
            flag.write_text("1")
            if self._tray is not None:
                self._tray.notify("Kreds keeps running in the background. "
                                  "Click the tray icon to open it again.", "Kreds")
        except Exception:
            pass

    def toggle_maximize(self):
        # pywebview's maximize API varies by version; verify against the installed
        # version and use .maximize()/.restore() if present, else toggle_fullscreen().
        if not self.window: return
        if self._maximized: self.window.restore()
        else: self.window.maximize()
        self._maximized = not self._maximized

    # create_window's min_size; WinForms MinimumSize is the native
    # backstop, this clamp just keeps the JS grip from flooding the
    # bridge with sub-minimum calls.
    MIN_W, MIN_H = 900, 600

    def resize_to(self, w, h):
        """Chrome corner grip (frameless windows have no native resize
        borders - resizable=True is inert without an OS frame, the
        regression Josh reported). window.resize marshals onto the GUI
        thread (pywebview winforms Invoke), same safety class as
        load_url."""
        if not self.window or self._maximized:
            return                      # maximized: native corner no-op
        try:
            self.window.resize(max(int(w), self.MIN_W),
                               max(int(h), self.MIN_H))
        except Exception:
            pass                        # mid-teardown; nothing to resize

    def quit(self):
        loop = self._holder.get("loop"); ev = self._holder.get("ev")
        if loop is not None and ev is not None:
            try:
                loop.call_soon_threadsafe(ev.set)  # ask the node to shut down cleanly
            except RuntimeError:
                pass                                # loop already closed (node died/raced us)
        if self._tray is not None:
            try:
                self._tray.stop()       # safe from any thread incl. the tray's own menu
                                        # callback: pystray 0.19.x _win32 stop() is a
                                        # non-blocking PostMessage (verified from source)
            except Exception:
                pass                    # a wedged tray must never block shutdown
        if self.window:
            try:
                self.window.destroy()   # ALWAYS attempted, even if the signal above
                                        # failed -- otherwise a dead node loop leaves the
                                        # frameless (no OS close button) window unclosable.
            except Exception:
                pass    # quit() is reachable from the tray thread too: destroy can
                        # raise on a not-yet-shown window (pre-webview.start race) or
                        # a double-quit (tray Quit racing titlebar close) - a raising
                        # destroy must never leave shutdown half-done or spill an
                        # unraisable out of a tray callback.

    def restart(self):
        """The shell's "restart to finish" action, offered after a staged
        core update (hearth.update.stage_core / POST /api/update/apply's
        restart_required=True): spawn the stable top-level launcher
        (packaging/launcher.py's Kreds.exe) DETACHED -- so it outlives this
        process -- then quit this instance exactly like quit() does. The
        detached launcher applies the staged update (re-verifying it --
        hearth.coreupdate.apply_staged_core) on its fresh start and runs
        whatever version ends up current.

        Best-effort: if there's no launcher to spawn (dev/source run, or an
        unexpected install layout), this just quits -- it never blocks or
        raises out of what is, either way, a user-requested restart/quit."""
        launcher = _launcher_exe_path()
        if launcher is not None:
            try:
                creationflags = (getattr(subprocess, "DETACHED_PROCESS", 0)
                                 | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0))
                # Tell the freshly-launched instance this is a restart, so it
                # WAITS for our lock (we're about to release it in quit())
                # instead of failing with "Kreds is already running".
                env = {**os.environ, "KREDS_RESTARTING": "1"}
                subprocess.Popen([str(launcher)], creationflags=creationflags,
                                 close_fds=True, cwd=str(launcher.parent), env=env)
            except OSError:
                pass        # couldn't spawn the launcher -- still quit below rather than hang
        self.quit()


# --------------------------------------------------------------------------
# Single-instance guard: an exclusive OS lock on data_dir/instance.lock.
# msvcrt.locking is per-handle (even within one process, a second open()'s
# handle can't re-acquire a byte another handle already holds), so this also
# refuses a second launch() call in the SAME process, which is what makes it
# unit-testable without spawning a second real process.
# --------------------------------------------------------------------------

def _lock_path(data_dir: Path) -> Path:
    return data_dir / "instance.lock"

def acquire_single_instance(data_dir: Path, wait_seconds: float = 0):
    """Returns an open file handle holding the exclusive lock if this is the
    only running instance for data_dir, else None (another instance already
    holds it).

    wait_seconds > 0 retries for that long before giving up. On a RESTART the
    prior instance is still tearing down and hasn't released the lock yet, so
    the freshly-launched one must wait for it rather than fail with a spurious
    "Kreds is already running" (which is what happened on the first core-update
    restart). A normal second launch passes 0 and fails fast."""
    import msvcrt
    data_dir.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + wait_seconds
    while True:
        f = open(_lock_path(data_dir), "a+b")
        try:
            msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
            return f
        except OSError:
            f.close()
            if time.monotonic() >= deadline:
                return None
            time.sleep(0.25)

def release_single_instance(lock) -> None:
    if lock is None:
        return
    import msvcrt
    try:
        lock.seek(0)
        msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
    except OSError:
        pass
    lock.close()

def _notify_already_running() -> None:
    """A second launch on an already-running data dir gets a small notice,
    not a second (broken, port-colliding) node -- MessageBoxW rather than a
    full webview window since there is nothing else to show."""
    try:
        import ctypes
        # Copy covers the startup phase too: a second click during a slow
        # Tor bootstrap is most likely a double-launch, not a hidden
        # instance.
        ctypes.windll.user32.MessageBoxW(
            0, "Kreds is already running or still starting up. If it just "
            "launched, give it a moment - the window appears when it is "
            "ready. Otherwise check the system tray.",
            "Kreds", 0x40)  # MB_ICONINFORMATION
    except Exception:
        pass


# --------------------------------------------------------------------------
# System tray (spec 2026-07-08-kreds-tray-icon): always present while the
# app runs. pystray loading is LAZY (inside _create_tray only), mirroring
# launch()'s lazy `import webview` - the test suite never needs GUI deps.
# --------------------------------------------------------------------------

TRAY_NOTIFIED_FLAG = "tray_notified"

def _tray_icon_image():
    """packaging/kreds.ico (bundled when frozen, repo-resolved from
    source); a missing file gets a Pillow-drawn 3-arc placeholder so a
    dev checkout never crashes the shell over an icon."""
    from PIL import Image
    p = paths.tray_icon_path()
    if p.is_file():
        return Image.open(p)
    from PIL import ImageDraw
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.arc((6, 6, 58, 58), 300, 240, fill=(200, 16, 46, 255), width=7)
    d.ellipse((26, 26, 38, 38), fill=(23, 25, 27, 255))
    return img

def _create_tray(api):
    """Build (not run) the tray icon: Open Kreds (default item -> a
    double-click triggers it) + Quit Kreds."""
    import pystray
    menu = pystray.Menu(
        pystray.MenuItem("Open Kreds", lambda: api.show_window(), default=True),
        pystray.MenuItem("Quit Kreds", lambda: api.quit()),
    )
    return pystray.Icon("Kreds", _tray_icon_image(), "Kreds", menu)


# --------------------------------------------------------------------------
# Freeze-harden: bound the node-thread startup instead of ever spinning /
# hanging on a blank window. On failure, write the traceback to
# data_dir/app.log so a packaged run leaves evidence instead of vanishing.
# --------------------------------------------------------------------------

def _prepare_web_dir(data_dir: Path) -> Path:
    """Seed the writable, auto-updatable web dir from the bundled assets
    before the node starts serving it."""
    web = data_dir / "web"
    paths.seed_web_dir(web)
    return web

def _tor_enabled() -> bool:
    """Tor ON when packaged (frozen); plain TCP when running from source, so
    `hearth app` from a dev checkout stays fast (no Tor bootstrap)."""
    return paths.is_frozen()

def _log_error(data_dir: Path, message: str) -> None:
    """Freeze-harden: a startup failure leaves evidence in data_dir/app.log
    instead of the packaged app just vanishing. `message` is expected to
    already carry any traceback text the caller captured."""
    try:
        data_dir.mkdir(parents=True, exist_ok=True)
        with open(data_dir / "app.log", "a", encoding="utf-8") as f:
            f.write(time.strftime("[%Y-%m-%d %H:%M:%S] ") + message + "\n")
    except Exception:
        pass

def _start_node(data_dir: Path, port: int, web_dir: Path, use_tor: bool):
    """Start the node in a background thread. Returns (thread, holder); the
    caller waits on holder via _await_node_ready. Any exception out of
    run_serve is captured into holder["error"] instead of dying silently in
    the background thread."""
    holder: dict = {}
    holder["startup"] = {"stage": "starting", "pct": None}

    def _status(stage, pct=None):
        # Whole-dict replacement (not mutation): the GUI thread reads this
        # concurrently via Api.get_startup_status.
        holder["startup"] = {"stage": stage, "pct": pct}

    def _node_thread():
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        ev = asyncio.Event()
        holder["loop"] = loop
        holder["ev"] = ev
        try:
            # Tor's ADD_ONION maps the published onion address to a FIXED
            # port; gossip_port=0 ("any free port") makes ADD_ONION reject
            # Port=0 outright, or -- worse -- publish an onion pointing at
            # a port nothing is listening on. Non-tor keeps 0: sync.start's
            # own ephemeral-port pick is used consistently in that branch
            # (task review CRITICAL; mirrors cli.py's `serve --tor` guard).
            gossip_port = _free_port() if use_tor else 0
            loop.run_until_complete(run_serve(
                data_dir, gossip_port, port, tor=use_tor, shutdown=ev,
                web_dir=web_dir, status=_status))
        except BaseException:
            holder["error"] = traceback.format_exc()
        finally:
            loop.close()

    t = threading.Thread(target=_node_thread, daemon=True)
    t.start()
    while "loop" not in holder and t.is_alive():     # wait until the loop/ev exist
        time.sleep(0.02)
    return t, holder

def _await_node_ready(t: threading.Thread, holder: dict, port: int,
                      timeout: float = 60.0) -> bool:
    """Bounded wait for the node to answer -- never spins forever. False if
    the thread never even got its loop up, if the HTTP check times out, or
    if the thread has since died."""
    if "loop" not in holder:
        return False
    stop_check = lambda: not t.is_alive() or holder.get("error") is not None
    if not _wait_http(f"http://127.0.0.1:{port}/api/bootstrap", timeout=timeout,
                      stop_check=stop_check):
        return False
    return t.is_alive()

def _signal_shutdown(holder: dict) -> None:
    loop = holder.get("loop"); ev = holder.get("ev")
    if loop is not None and ev is not None:
        try:
            loop.call_soon_threadsafe(ev.set)
        except RuntimeError:
            pass          # loop already closed (fast node shutdown won the exit race) --
                          # otherwise this re-raises out of launch() as an unhandled
                          # traceback + nonzero exit on an otherwise-clean quit

def _drain_node_thread(t, data_dir: Path) -> None:
    """Bounded wait for the node thread's shutdown (sync teardown + tor
    stop). On timeout, leave evidence -- the orphaned tor will hold the
    fixed ports for ~15s and the NEXT launch's spawn-retry window is what
    absorbs it (tor.py _SPAWN_RETRY_WINDOW)."""
    t.join(timeout=SHUTDOWN_DRAIN_TIMEOUT)
    if t.is_alive():
        _log_error(data_dir,
                   "shutdown drain timed out; a tor orphan may linger ~15s")

def _show_error_window(webview_module, message: str) -> None:
    """A minimal error page instead of a frozen blank window."""
    html = ("<html><body style='font-family:sans-serif;padding:2em'>"
            "<h2>Kreds failed to start</h2><p>" + message + "</p>"
            "<p>See app.log in the Kreds data folder for details.</p>"
            "</body></html>")
    webview_module.create_window("Kreds", html=html, width=480, height=260)
    webview_module.start()


# Window-first launch (launch loading states, 0.3.11): the window opens
# immediately on _LOADING_HTML; _watch_ready navigates it to the app when
# the node answers, or flips the page to its failed state via
# holder["error"] (which Api.get_startup_status reports as stage "failed").

# The tor ready-wait must exceed tor.py's own retry budget (2 x 90s
# bootstrap attempts + 5s gap) plus AV-scan overhead on freshly written
# binaries -- was 120, which a legitimately slow post-update cold bootstrap
# overran, landing in the "always fails after an update" pattern.
# 0.3.12: the spawn-retry window (30s window + 2x5s gaps + 2x5s bounded
# reap waits alongside the unchanged 2x90s bootstrap budget = 230s worst
# case) consumed most of 240's headroom. Raised to 300 to restore the
# >=60s AV-scan margin over the full worst-case retry budget.
READY_TIMEOUT_TOR = 300.0
READY_TIMEOUT_PLAIN = 25.0

# Exit drain (0.3.12): quit destroys the window instantly, but the process
# must stay alive until the node thread's finally has actually stopped tor
# (TorProcess.stop() is internally bounded at ~16s worst case) -- otherwise
# the daemon thread dies mid-stop and tor is orphaned for ~15s, colliding
# with the next launch's fixed ports (the update-restart failure, 0.3.12).
# Still bounded: a wedged node thread must not turn quit into a zombie.
SHUTDOWN_DRAIN_TIMEOUT = 30.0
# A restarting instance must out-wait the exiting instance's drain.
# Equality with SHUTDOWN_DRAIN_TIMEOUT is safe because the restarting
# instance's 30s clock starts only after the launcher's self-extract +
# staged-core verify + payload boot -- several seconds after the old
# instance began draining -- and that head start is the actual margin.
RESTART_LOCK_WAIT = 30.0

_LOADING_HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><style>
  html, body { height: 100%; margin: 0; }
  body { font-family: 'Segoe UI', sans-serif; background: #17191b;
         color: #e8e6e3; display: flex; flex-direction: column; }
  .titlebar { height: 36px; flex: none; display: flex; align-items: stretch; }
  .titlebar .pywebview-drag-region { flex: 1; }
  .titlebar button { width: 46px; border: 0; background: transparent;
          color: #9a9691; font-size: 14px; cursor: default; }
  .titlebar button:hover { background: #c8102e; color: #fff; }
  main { flex: 1; display: flex; flex-direction: column;
         align-items: center; justify-content: center; gap: 18px; }
  h1 { font-size: 28px; font-weight: 600; letter-spacing: 1px; margin: 0; }
  #stage { color: #9a9691; font-size: 14px; min-height: 20px;
           max-width: 80%; text-align: center; white-space: pre-wrap; }
  #barbox { width: 260px; height: 4px; background: #2a2d30;
            border-radius: 2px; overflow: hidden; }
  #bar { width: 8%; height: 100%; background: #c8102e; border-radius: 2px;
         transition: width .4s; }
  #barbox.pulse #bar { animation: pulse 1.6s ease-in-out infinite; }
  @keyframes pulse { 0% {margin-left:0%} 50% {margin-left:92%} 100% {margin-left:0%} }
  .failed #barbox { display: none; }
</style></head><body>
<div class="titlebar"><span class="pywebview-drag-region"></span>
<button onclick="doQuit()" title="Close">&#10005;</button></div>
<main>
  <h1>Kreds</h1>
  <div id="barbox" class="pulse"><div id="bar"></div></div>
  <div id="stage">Starting Kreds...</div>
</main>
<script>
  function doQuit() {
    try { window.pywebview.api.quit(); } catch (e) { /* bridge not up yet */ }
  }
  var COPY = { "starting": "Starting Kreds...",
               "tor-binary": "Starting Kreds...",
               "tor-waiting": "Waiting for a previous Kreds to finish closing...",
               "onion-publish": "Publishing your address...",
               "serving": "Almost there...",
               "ready": "Almost there..." };
  var timer = setInterval(poll, 500);
  async function poll() {
    // the js_api bridge is injected asynchronously after page load --
    // just keep trying until it exists
    if (!window.pywebview || !window.pywebview.api) return;
    var s;
    try { s = await window.pywebview.api.get_startup_status(); }
    catch (e) { return; }
    if (!s) return;
    var stageEl = document.getElementById("stage");
    var barbox = document.getElementById("barbox");
    var bar = document.getElementById("bar");
    if (s.stage === "failed") {
      clearInterval(timer);
      document.body.classList.add("failed");
      stageEl.textContent = "Kreds failed to start.\\n\\n" + (s.error || "");
      return;
    }
    if (s.stage === "tor-bootstrap") {
      var pct = (s.pct == null) ? 0 : s.pct;
      stageEl.textContent = "Connecting to Tor - " + pct + "%";
      barbox.classList.remove("pulse");
      bar.style.width = Math.max(pct, 4) + "%";
    } else {
      stageEl.textContent = COPY[s.stage] || "Starting Kreds...";
      barbox.classList.add("pulse");
    }
  }
</script>
</body></html>"""

def _watch_ready(t: threading.Thread, holder: dict, port: int, window,
                 data_dir: Path, use_tor: bool) -> None:
    """Runs on a daemon thread beside the already-open loading window:
    bounded wait for the node, then navigate the window to the app. On
    failure, log exactly as the old pre-window _handle_start_failure did
    and make sure holder["error"] is set -- the loading page's own polling
    renders the failed state in place (no separate error window)."""
    timeout = READY_TIMEOUT_TOR if use_tor else READY_TIMEOUT_PLAIN
    if _await_node_ready(t, holder, port, timeout=timeout):
        try:
            # Thread-safe: pywebview's winforms backend marshals load_url
            # onto the GUI thread (BrowserView.load_url -> self.Invoke).
            window.load_url(f"http://127.0.0.1:{port}")
        except Exception as e:
            # window destroyed while we waited (user quit during startup) --
            # the process is on its way down. Still log (not a bare pass) so a
            # navigation failure that ISN'T a quit leaves evidence in app.log.
            _log_error(data_dir,
                       "loading-page navigation failed: " + repr(e))
        return
    reason = "node thread died before startup" if not t.is_alive() \
        else "node did not answer within the startup timeout"
    detail = holder.get("error", "")
    _log_error(data_dir, reason + ("\n" + detail if detail else ""))
    if "error" not in holder:
        holder["error"] = reason    # flips the loading page to failed
    # Past the (300s) tor-exceeding timeout the node is not legitimately
    # mid-bootstrap; a "failed" screen over a live, syncing node is
    # dishonest, so tear it down (restores the pre-window-first semantics).
    _signal_shutdown(holder)


def launch(data_dir=None):
    data_dir = Path(data_dir) if data_dir else default_data_dir()
    data_dir.mkdir(parents=True, exist_ok=True)

    # On a restart the prior instance is still releasing its lock, so wait for
    # it; a normal launch fails fast. Consume the flag so it's one-shot.
    _restart_wait = RESTART_LOCK_WAIT if os.environ.pop("KREDS_RESTARTING", None) else 0.0
    lock = acquire_single_instance(data_dir, wait_seconds=_restart_wait)
    if lock is None:
        _notify_already_running()
        sys.exit(0)

    try:
        web = _prepare_web_dir(data_dir)
        port = _free_port()
        use_tor = _tor_enabled()

        t, holder = _start_node(data_dir, port, web, use_tor)

        # Window-first: open on the loading page immediately instead of
        # blocking here through a (minutes-long, cold-Tor) startup with no
        # window at all. _watch_ready does the bounded ready-wait beside it.
        import webview                              # LAZY (GUI dep not needed for import)
        api = Api(holder)
        window = webview.create_window(
            "Kreds", html=_LOADING_HTML, frameless=True, js_api=api,
            width=1100, height=760, min_size=(900, 600))
        api.window = window
        api._data_dir = data_dir
        threading.Thread(target=_watch_ready,
                         args=(t, holder, port, window, data_dir, use_tor),
                         name="kreds-ready-watch", daemon=True).start()
        # Tray failures degrade, never block: without a tray the app still
        # runs and hide_to_tray falls back to minimize.
        try:
            tray = _create_tray(api)
            def _run_tray():
                try:
                    tray.run()
                except Exception as e:
                    # In-thread death would otherwise vanish (pystray logs to a
                    # handlerless logger; a frozen app has no console). The app
                    # keeps working - hide_to_tray sees the dead thread and
                    # falls back to minimize.
                    _log_error(data_dir, "tray died: " + repr(e))
            t_tray = threading.Thread(target=_run_tray, name="kreds-tray", daemon=True)
            t_tray.start()
            api._tray = tray
            api._tray_thread = t_tray
        except Exception as e:
            _log_error(data_dir, "tray unavailable: " + repr(e))
        # DevTools opt-in: set KREDS_DEVTOOLS=1 to enable right-click -> Inspect
        # in the WebView2 window (for diagnosing rendering). Off by default.
        #
        # Persistent web storage (bug 2026-07-18): pywebview defaults to
        # private_mode=True - an ephemeral WebView2 profile that wiped EVERY
        # localStorage key on exit (theme choice, DM read watermarks,
        # journal/story seen state, restored view: the "dark mode resets" and
        # "read messages show as new" reports were one bug). The profile
        # lives inside data_dir so wiping Kreds data wipes it too, and the
        # single-instance lock already prevents concurrent profile use.
        webview.start(debug=os.environ.get("KREDS_DEVTOOLS") == "1",
                      private_mode=False,
                      storage_path=str(data_dir / "webview"))
        # window gone -> ensure the node is asked to stop, then drain fully
        _signal_shutdown(holder)
        _drain_node_thread(t, data_dir)
    finally:
        release_single_instance(lock)
