"""Window-first launch (launch loading states, 0.3.11): the webview window
opens immediately on a loading page that polls Api.get_startup_status();
a watcher thread navigates to the app on readiness or flips the page to the
failed state via holder["error"]. Logic-level tests only - no real webview.
"""
import time
from pathlib import Path

import hearth.desktop as desktop


class _FakeThread:
    def __init__(self, alive=True): self._alive = alive
    def is_alive(self): return self._alive


class _FakeWindow:
    def __init__(self, raise_on_load=False):
        self.loaded = []
        self._raise = raise_on_load

    def load_url(self, url):
        if self._raise:
            raise RuntimeError("window already destroyed")
        self.loaded.append(url)


def test_api_get_startup_status_reads_holder():
    holder = {"startup": {"stage": "tor-bootstrap", "pct": 45}}
    api = desktop.Api(holder)
    assert api.get_startup_status() == {"stage": "tor-bootstrap", "pct": 45}


def test_api_get_startup_status_defaults_before_thread_writes():
    api = desktop.Api({})
    assert api.get_startup_status() == {"stage": "starting", "pct": None}


def test_api_get_startup_status_failure_state():
    holder = {"startup": {"stage": "tor-bootstrap", "pct": 45},
              "error": "RuntimeError: tor failed"}
    api = desktop.Api(holder)
    s = api.get_startup_status()
    assert s["stage"] == "failed"
    assert "tor failed" in s["error"]
    assert "app.log" in s["error"]      # points the user at the evidence


def test_ready_wait_timeout_constants():
    # conscious-edit pin per spec: the tor ready-wait must exceed tor.py's
    # own 2x90s + 5s retry budget or a slow-but-successful cold bootstrap
    # gets declared dead mid-bootstrap (the post-update always-fails bug).
    # Value moved 240 -> 300 in 0.3.12 when the spawn-retry window landed
    # and ate most of 240's AV-overhead headroom.
    assert desktop.READY_TIMEOUT_TOR == 300.0
    assert desktop.READY_TIMEOUT_PLAIN == 25.0


def test_start_node_seeds_startup_and_threads_status(tmp_path, monkeypatch):
    async def fake_run_serve(data_dir, gossip_port, http_port, tor=False,
                             shutdown=None, web_dir=None, status=None):
        status("serving")
        status("tor-bootstrap", 45)
    monkeypatch.setattr(desktop, "run_serve", fake_run_serve)
    t, holder = desktop._start_node(tmp_path, 5557, tmp_path / "web", False)
    t.join(timeout=5)
    # the last write wins; the seed value existed before the thread ran
    assert holder["startup"] == {"stage": "tor-bootstrap", "pct": 45}


def test_watch_ready_success_navigates_window(tmp_path, monkeypatch):
    monkeypatch.setattr(desktop, "_await_node_ready",
                        lambda t, holder, port, timeout: True)
    w = _FakeWindow()
    desktop._watch_ready(_FakeThread(True), {}, 4321, w, tmp_path, False)
    assert w.loaded == ["http://127.0.0.1:4321"]


def test_watch_ready_failure_logs_and_sets_holder_error(tmp_path, monkeypatch):
    monkeypatch.setattr(desktop, "_await_node_ready",
                        lambda t, holder, port, timeout: False)
    holder = {"error": "kaboom traceback"}
    w = _FakeWindow()
    desktop._watch_ready(_FakeThread(False), holder, 4321, w, tmp_path, True)
    log = (tmp_path / "app.log").read_text(encoding="utf-8")
    assert "node thread died" in log            # reason string preserved
    assert "kaboom traceback" in log            # detail appended
    assert w.loaded == []                       # never navigated to the app


def test_watch_ready_failure_sets_error_when_unset(tmp_path, monkeypatch):
    # Timeout with no exception recorded (node alive but never answered):
    # the loading page only flips to failed via holder["error"], so the
    # watcher must set it.
    monkeypatch.setattr(desktop, "_await_node_ready",
                        lambda t, holder, port, timeout: False)
    holder = {}
    desktop._watch_ready(_FakeThread(True), holder, 4321, _FakeWindow(),
                         tmp_path, True)
    assert "did not answer" in holder["error"]
    log = (tmp_path / "app.log").read_text(encoding="utf-8")
    assert "did not answer" in log


def test_watch_ready_timeout_tracks_tor_mode(tmp_path, monkeypatch):
    seen = {}
    def fake_await(t, holder, port, timeout):
        seen["timeout"] = timeout
        return True
    monkeypatch.setattr(desktop, "_await_node_ready", fake_await)
    desktop._watch_ready(_FakeThread(True), {}, 4321, _FakeWindow(),
                         tmp_path, True)
    assert seen["timeout"] == desktop.READY_TIMEOUT_TOR
    desktop._watch_ready(_FakeThread(True), {}, 4321, _FakeWindow(),
                         tmp_path, False)
    assert seen["timeout"] == desktop.READY_TIMEOUT_PLAIN


def test_watch_ready_survives_destroyed_window(tmp_path, monkeypatch):
    # The user can close the window while the watcher is still waiting;
    # load_url on the destroyed window must not spill an exception out of
    # the watcher thread -- but the failure is now logged, not swallowed.
    monkeypatch.setattr(desktop, "_await_node_ready",
                        lambda t, holder, port, timeout: True)
    desktop._watch_ready(_FakeThread(True), {}, 4321,
                         _FakeWindow(raise_on_load=True), tmp_path, False)
    log = (tmp_path / "app.log").read_text(encoding="utf-8")
    assert "navigation failed" in log


def test_watch_ready_failure_signals_shutdown(tmp_path, monkeypatch):
    # Past the ready timeout the node is not legitimately mid-bootstrap; a
    # "failed" screen over a live node is dishonest, so the watcher tears
    # the node down (restores the pre-window-first semantics).
    import asyncio
    monkeypatch.setattr(desktop, "_await_node_ready",
                        lambda t, holder, port, timeout: False)
    loop = asyncio.new_event_loop()
    ev = asyncio.Event()
    holder = {"loop": loop, "ev": ev}
    desktop._watch_ready(_FakeThread(True), holder, 4321, _FakeWindow(),
                         tmp_path, True)
    loop.run_until_complete(asyncio.wait_for(ev.wait(), timeout=2))  # got set
    loop.close()


def test_loading_html_polls_status_and_maps_stages():
    # Content pins: the loading page must poll the bridge and carry the
    # stage copy contract (ASCII only - cp1252 console, packaged app).
    html = desktop._LOADING_HTML
    assert "get_startup_status" in html
    assert "Connecting to Tor" in html
    assert "Starting Kreds" in html
    # (the "See app.log" pointer rides in the failed-state error payload --
    # pinned on Api.get_startup_status above -- not in the page itself)
    assert "failed" in html
    assert html.isascii()
    assert "Waiting for a previous Kreds to finish closing..." in html
    assert "tor-waiting" in html


def test_notify_already_running_copy_covers_startup_window(monkeypatch):
    # The single-instance notice must not claim "already running" flatly:
    # during the (now long) loading phase a second click is most likely a
    # double-launch while the first is still starting.
    captured = {}
    class FakeUser32:
        def MessageBoxW(self, h, text, title, flags):
            captured["text"] = text
    class FakeWindll:
        user32 = FakeUser32()
    import ctypes
    monkeypatch.setattr(ctypes, "windll", FakeWindll(), raising=False)
    desktop._notify_already_running()
    assert "still starting up" in captured["text"]


def test_shutdown_drain_constants():
    # Exit drain (0.3.12): the join must out-wait TorProcess.stop()'s own
    # internal worst case (~16s: 5s SIGNAL SHUTDOWN grace + 10s terminate
    # wait + kill); the restart lock-wait must out-wait the drain.
    assert desktop.SHUTDOWN_DRAIN_TIMEOUT == 30.0
    assert desktop.RESTART_LOCK_WAIT == 30.0
    assert desktop.RESTART_LOCK_WAIT >= desktop.SHUTDOWN_DRAIN_TIMEOUT


def test_spawn_window_plus_bootstrap_budget_fits_ready_wait():
    # C's spawn window + the unchanged 2x bootstrap budget must fit the
    # shell's ready-wait WITH the margin the 0.3.11 fix established for
    # AV-scan overhead on fresh binaries (the reason 120 originally
    # failed). Deterministic worst case: full spawn window + the gap
    # after the first timeout attempt + two bootstrap timeouts + two
    # bounded reap waits.
    from hearth.tor import TorProcess
    REAP_WAIT = 5.0                      # tor.py start()'s except path
    worst = (TorProcess._SPAWN_RETRY_WINDOW + TorProcess._SPAWN_RETRY_GAP
             + 2 * 90.0 + 2 * REAP_WAIT)
    assert worst == 225.0                # conscious-edit pin (30+5+180+10)
    assert desktop.READY_TIMEOUT_TOR - worst >= 60.0   # AV-overhead margin


def test_drain_timeout_leaves_evidence(tmp_path, monkeypatch):
    # A node thread still alive after the drain window must log the orphan
    # warning to app.log. Drive the same code launch() runs post-join.
    class _StuckThread:
        def join(self, timeout=None): pass
        def is_alive(self): return True
    desktop._drain_node_thread(_StuckThread(), tmp_path)
    log = (tmp_path / "app.log").read_text(encoding="utf-8")
    assert "shutdown drain timed out" in log


def test_drain_clean_exit_logs_nothing(tmp_path):
    class _DoneThread:
        def join(self, timeout=None): pass
        def is_alive(self): return False
    desktop._drain_node_thread(_DoneThread(), tmp_path)
    assert not (tmp_path / "app.log").exists()
