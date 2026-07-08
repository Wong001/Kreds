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
            self.window.restore()
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
        ctypes.windll.user32.MessageBoxW(
            0, "Kreds is already running. It may be in the background - "
            "check your system tray.", "Kreds", 0x40)  # MB_ICONINFORMATION
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
                web_dir=web_dir))
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

def _show_error_window(webview_module, message: str) -> None:
    """A minimal error page instead of a frozen blank window."""
    html = ("<html><body style='font-family:sans-serif;padding:2em'>"
            "<h2>Kreds failed to start</h2><p>" + message + "</p>"
            "<p>See app.log in the Kreds data folder for details.</p>"
            "</body></html>")
    webview_module.create_window("Kreds", html=html, width=480, height=260)
    webview_module.start()

def _handle_start_failure(data_dir: Path, t: threading.Thread, holder: dict) -> None:
    reason = "node thread died before startup" if not t.is_alive() \
        else "node did not answer within the startup timeout"
    detail = holder.get("error", "")
    _log_error(data_dir, reason + ("\n" + detail if detail else ""))
    _signal_shutdown(holder)
    import webview                                   # LAZY (GUI dep)
    _show_error_window(webview, "The Kreds node failed to start.")


def launch(data_dir=None):
    data_dir = Path(data_dir) if data_dir else default_data_dir()
    data_dir.mkdir(parents=True, exist_ok=True)

    # On a restart the prior instance is still releasing its lock, so wait for
    # it; a normal launch fails fast. Consume the flag so it's one-shot.
    _restart_wait = 15.0 if os.environ.pop("KREDS_RESTARTING", None) else 0.0
    lock = acquire_single_instance(data_dir, wait_seconds=_restart_wait)
    if lock is None:
        _notify_already_running()
        sys.exit(0)

    try:
        web = _prepare_web_dir(data_dir)
        port = _free_port()
        use_tor = _tor_enabled()

        t, holder = _start_node(data_dir, port, web, use_tor)
        # Tor bootstrap is slow (TorProcess.start() allows ~90s) -- the
        # ready-wait must exceed that or a legitimately slow-but-successful
        # cold bootstrap gets declared "failed to start" mid-bootstrap
        # (task review IMPORTANT). Dev's fast plain-TCP start stays quick.
        timeout = 120.0 if use_tor else 25.0
        if not _await_node_ready(t, holder, port, timeout=timeout):
            _handle_start_failure(data_dir, t, holder)
            return

        import webview                              # LAZY (GUI dep not needed for import)
        api = Api(holder)
        window = webview.create_window(
            "Kreds", url=f"http://127.0.0.1:{port}", frameless=True, js_api=api,
            width=1100, height=760, min_size=(900, 600))
        api.window = window
        api._data_dir = data_dir
        # Tray failures degrade, never block: without a tray the app still
        # runs and hide_to_tray falls back to minimize.
        try:
            tray = _create_tray(api)
            t_tray = threading.Thread(target=tray.run, name="kreds-tray", daemon=True)
            t_tray.start()
            api._tray = tray
            api._tray_thread = t_tray
        except Exception as e:
            _log_error(data_dir, "tray unavailable: " + repr(e))
        # DevTools opt-in: set KREDS_DEVTOOLS=1 to enable right-click -> Inspect
        # in the WebView2 window (for diagnosing rendering). Off by default.
        webview.start(debug=os.environ.get("KREDS_DEVTOOLS") == "1")
        # window gone -> ensure the node is asked to stop, then join
        _signal_shutdown(holder)
        t.join(timeout=8)
    finally:
        release_single_instance(lock)
