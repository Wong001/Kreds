import asyncio, ctypes, os, time
from pathlib import Path

import pytest

import hearth.desktop as desktop          # must import WITHOUT pywebview being needed

def test_default_data_dir_uses_appdata(monkeypatch, tmp_path):
    monkeypatch.setenv("APPDATA", str(tmp_path))
    assert desktop.default_data_dir() == Path(tmp_path) / "Kreds"

def test_free_port_is_usable():
    p = desktop._free_port()
    assert isinstance(p, int) and 1024 < p < 65536

def test_api_is_desktop():
    assert desktop.Api({}).is_desktop() is True

def test_api_quit_sets_shutdown_event():
    # quit() must signal the node loop's shutdown event (thread-safe) + destroy the window
    loop = asyncio.new_event_loop()
    ev = asyncio.Event()
    holder = {"loop": loop, "ev": ev}
    api = desktop.Api(holder)
    class W:
        destroyed = False
        def destroy(self): self.destroyed = True
    api.window = W()
    api.quit()
    loop.run_until_complete(asyncio.wait_for(ev.wait(), timeout=2))   # event got set
    assert api.window.destroyed
    loop.close()

def test_launcher_exe_path_none_when_not_frozen(monkeypatch):
    monkeypatch.setattr(desktop.paths, "is_frozen", lambda: False)
    assert desktop._launcher_exe_path() is None


def test_launcher_exe_path_found_when_frozen_at_expected_layout(tmp_path, monkeypatch):
    install_root = tmp_path / "Kreds"
    payload_dir = install_root / "versions" / "0.2.0"
    payload_dir.mkdir(parents=True)
    payload_exe = payload_dir / "Kreds.exe"
    payload_exe.write_bytes(b"payload")
    launcher_exe = install_root / "Kreds.exe"
    launcher_exe.write_bytes(b"launcher")

    monkeypatch.setattr(desktop.paths, "is_frozen", lambda: True)
    monkeypatch.setattr(desktop.sys, "executable", str(payload_exe))
    assert desktop._launcher_exe_path() == launcher_exe


def test_launcher_exe_path_none_when_launcher_missing(tmp_path, monkeypatch):
    install_root = tmp_path / "Kreds"
    payload_dir = install_root / "versions" / "0.2.0"
    payload_dir.mkdir(parents=True)
    payload_exe = payload_dir / "Kreds.exe"
    payload_exe.write_bytes(b"payload")
    # no Kreds.exe written directly under install_root

    monkeypatch.setattr(desktop.paths, "is_frozen", lambda: True)
    monkeypatch.setattr(desktop.sys, "executable", str(payload_exe))
    assert desktop._launcher_exe_path() is None


def test_launcher_exe_path_none_when_layout_unexpected(tmp_path, monkeypatch):
    # e.g. a dev-mode venv python.exe, nowhere near a versions/<ver>/ layout
    fake_exe = tmp_path / ".venv" / "Scripts" / "python.exe"
    fake_exe.parent.mkdir(parents=True)
    fake_exe.write_bytes(b"x")
    monkeypatch.setattr(desktop.paths, "is_frozen", lambda: True)
    monkeypatch.setattr(desktop.sys, "executable", str(fake_exe))
    assert desktop._launcher_exe_path() is None


def test_api_restart_not_frozen_just_quits(monkeypatch):
    # No launcher to spawn in dev/source mode -- restart() must still
    # cleanly quit (signal shutdown + destroy window), and must NOT try to
    # spawn anything.
    monkeypatch.setattr(desktop, "_launcher_exe_path", lambda: None)
    popen_calls = []
    monkeypatch.setattr(desktop.subprocess, "Popen", lambda *a, **k: popen_calls.append((a, k)))

    loop = asyncio.new_event_loop()
    ev = asyncio.Event()
    holder = {"loop": loop, "ev": ev}
    api = desktop.Api(holder)
    class W:
        destroyed = False
        def destroy(self): self.destroyed = True
    api.window = W()

    api.restart()

    assert popen_calls == []
    loop.run_until_complete(asyncio.wait_for(ev.wait(), timeout=2))
    assert api.window.destroyed
    loop.close()


def test_api_restart_frozen_spawns_launcher_detached_then_quits(tmp_path, monkeypatch):
    launcher_exe = tmp_path / "Kreds.exe"
    launcher_exe.write_bytes(b"launcher")
    monkeypatch.setattr(desktop, "_launcher_exe_path", lambda: launcher_exe)
    popen_calls = []
    monkeypatch.setattr(desktop.subprocess, "Popen",
                        lambda *a, **k: popen_calls.append((a, k)))

    loop = asyncio.new_event_loop()
    ev = asyncio.Event()
    holder = {"loop": loop, "ev": ev}
    api = desktop.Api(holder)
    class W:
        destroyed = False
        def destroy(self): self.destroyed = True
    api.window = W()

    api.restart()

    assert len(popen_calls) == 1
    args, kwargs = popen_calls[0]
    assert args[0] == [str(launcher_exe)]
    assert kwargs["creationflags"] != 0    # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
    loop.run_until_complete(asyncio.wait_for(ev.wait(), timeout=2))   # still quits
    assert api.window.destroyed
    loop.close()


def test_api_restart_spawn_failure_still_quits(tmp_path, monkeypatch):
    # A failure to spawn the launcher (e.g. permissions) must not prevent
    # the quit half of restart() from still happening.
    launcher_exe = tmp_path / "Kreds.exe"
    launcher_exe.write_bytes(b"launcher")
    monkeypatch.setattr(desktop, "_launcher_exe_path", lambda: launcher_exe)
    def boom(*a, **k): raise OSError("simulated spawn failure")
    monkeypatch.setattr(desktop.subprocess, "Popen", boom)

    loop = asyncio.new_event_loop()
    ev = asyncio.Event()
    holder = {"loop": loop, "ev": ev}
    api = desktop.Api(holder)
    class W:
        destroyed = False
        def destroy(self): self.destroyed = True
    api.window = W()

    api.restart()                 # must not raise

    loop.run_until_complete(asyncio.wait_for(ev.wait(), timeout=2))
    assert api.window.destroyed
    loop.close()


def test_api_quit_with_closed_loop_still_destroys_window():
    # If the node loop already closed (fast node shutdown won the exit race, or
    # the node thread died), loop.call_soon_threadsafe raises RuntimeError("Event
    # loop is closed"). quit() must swallow that and still destroy the frameless
    # (no OS close button) window -- otherwise the window becomes unclosable
    # (whole-branch review, IMPORTANT #1).
    loop = asyncio.new_event_loop()
    ev = asyncio.Event()
    loop.close()
    holder = {"loop": loop, "ev": ev}
    api = desktop.Api(holder)
    class W:
        destroyed = False
        def destroy(self): self.destroyed = True
    api.window = W()
    api.quit()                        # must not raise
    assert api.window.destroyed


# --------------------------------------------------------------------------
# Task 1: web seeding, Tor-on-when-frozen, single-instance, freeze-harden.
# None of these tests open a real webview window.
# --------------------------------------------------------------------------

def test_prepare_web_dir_seeds_from_bundle(tmp_path, monkeypatch):
    calls = []
    monkeypatch.setattr(desktop.paths, "seed_web_dir", lambda dst: calls.append(dst))
    web = desktop._prepare_web_dir(tmp_path)
    assert web == tmp_path / "web"
    assert calls == [tmp_path / "web"]

def test_tor_enabled_mirrors_is_frozen(monkeypatch):
    monkeypatch.setattr(desktop.paths, "is_frozen", lambda: True)
    assert desktop._tor_enabled() is True
    monkeypatch.setattr(desktop.paths, "is_frozen", lambda: False)
    assert desktop._tor_enabled() is False


def test_acquire_single_instance_blocks_second_caller_same_data_dir(tmp_path):
    lock1 = desktop.acquire_single_instance(tmp_path)
    assert lock1 is not None
    lock2 = desktop.acquire_single_instance(tmp_path)          # already held -> refused
    assert lock2 is None
    desktop.release_single_instance(lock1)
    lock3 = desktop.acquire_single_instance(tmp_path)          # released -> available again
    assert lock3 is not None
    desktop.release_single_instance(lock3)


def test_acquire_single_instance_wait_retries_then_gives_up(tmp_path):
    # A held lock with a short wait_seconds retries, then returns None (no hang).
    import time
    lock1 = desktop.acquire_single_instance(tmp_path)
    assert lock1 is not None
    t0 = time.monotonic()
    lock2 = desktop.acquire_single_instance(tmp_path, wait_seconds=0.6)
    elapsed = time.monotonic() - t0
    assert lock2 is None
    assert elapsed >= 0.5                       # it actually waited, didn't fail instantly
    desktop.release_single_instance(lock1)


def test_acquire_single_instance_wait_succeeds_when_freed_during_wait(tmp_path):
    # If the holder releases during the wait window, the waiter acquires it.
    import threading, time
    lock1 = desktop.acquire_single_instance(tmp_path)
    assert lock1 is not None
    threading.Timer(0.4, lambda: desktop.release_single_instance(lock1)).start()
    lock2 = desktop.acquire_single_instance(tmp_path, wait_seconds=5)
    assert lock2 is not None                    # waited out the holder, then got it
    desktop.release_single_instance(lock2)

def test_acquire_single_instance_independent_across_data_dirs(tmp_path):
    a = tmp_path / "a"; b = tmp_path / "b"
    lock_a = desktop.acquire_single_instance(a)
    lock_b = desktop.acquire_single_instance(b)
    assert lock_a is not None and lock_b is not None
    desktop.release_single_instance(lock_a)
    desktop.release_single_instance(lock_b)

def test_release_single_instance_none_is_noop():
    desktop.release_single_instance(None)     # must not raise

def test_notify_already_running_calls_messagebox_not_a_real_gui(monkeypatch):
    # MessageBoxW itself is monkeypatched out -- a real popup would block
    # an automated test run waiting for a click.
    calls = []
    monkeypatch.setattr(ctypes.windll.user32, "MessageBoxW",
                        lambda *a: calls.append(a))
    desktop._notify_already_running()
    assert len(calls) == 1

def test_launch_exits_zero_when_already_running(tmp_path, monkeypatch):
    monkeypatch.setattr(desktop, "acquire_single_instance", lambda d, **k: None)
    notified = []
    monkeypatch.setattr(desktop, "_notify_already_running", lambda: notified.append(True))
    with pytest.raises(SystemExit) as exc:
        desktop.launch(tmp_path)
    assert exc.value.code == 0
    assert notified == [True]


def test_start_node_passes_tor_and_web_dir_through(tmp_path, monkeypatch):
    captured = {}
    async def fake_run_serve(data_dir, gossip_port, http_port, tor=False,
                             shutdown=None, web_dir=None):
        captured.update(data_dir=data_dir, gossip_port=gossip_port,
                        http_port=http_port, tor=tor, web_dir=web_dir,
                        shutdown=shutdown)
    monkeypatch.setattr(desktop, "run_serve", fake_run_serve)
    web = tmp_path / "web"
    t, holder = desktop._start_node(tmp_path, 5555, web, True)
    t.join(timeout=5)
    assert captured["data_dir"] == tmp_path
    # Tor ON: gossip_port must be a real, non-zero port (task review
    # CRITICAL). ADD_ONION maps the published onion address to this port
    # literally, so 0 ("any free port") makes ADD_ONION reject Port=0, or
    # publishes an onion address pointing at a port nothing listens on.
    assert captured["gossip_port"] != 0
    assert captured["http_port"] == 5555
    assert captured["tor"] is True                 # Tor ON was threaded through
    assert captured["web_dir"] == web               # writable web dir was threaded through
    assert isinstance(captured["shutdown"], asyncio.Event)
    assert "loop" in holder and "ev" in holder

def test_start_node_tor_off_keeps_gossip_port_zero(tmp_path, monkeypatch):
    # Non-tor (dev/demo/serve) must stay unchanged: sync.start's own
    # ephemeral-port pick is used consistently in that branch, so
    # gossip_port=0 ("any free port") is correct and must NOT change.
    captured = {}
    async def fake_run_serve(data_dir, gossip_port, http_port, tor=False,
                             shutdown=None, web_dir=None):
        captured["gossip_port"] = gossip_port
    monkeypatch.setattr(desktop, "run_serve", fake_run_serve)
    t, holder = desktop._start_node(tmp_path, 5556, tmp_path / "web", False)
    t.join(timeout=5)
    assert captured["gossip_port"] == 0

def test_start_node_captures_exception_into_holder(tmp_path, monkeypatch):
    async def boom(*a, **k):
        raise RuntimeError("kaboom")
    monkeypatch.setattr(desktop, "run_serve", boom)
    t, holder = desktop._start_node(tmp_path, 5556, tmp_path / "web", False)
    t.join(timeout=5)
    assert not t.is_alive()
    assert "kaboom" in holder.get("error", "")


class _FakeThread:
    def __init__(self, alive=True): self._alive = alive
    def is_alive(self): return self._alive

def test_await_node_ready_false_when_loop_never_started():
    # bounded, doesn't touch _wait_http at all -- no hang
    assert desktop._await_node_ready(_FakeThread(True), {}, 9999, timeout=0.1) is False

def test_await_node_ready_false_when_http_never_answers(monkeypatch):
    monkeypatch.setattr(desktop, "_wait_http", lambda url, timeout, stop_check=None: False)
    holder = {"loop": object(), "ev": object()}
    assert desktop._await_node_ready(_FakeThread(True), holder, 9999, timeout=0.1) is False

def test_await_node_ready_false_when_thread_died_after_answering(monkeypatch):
    monkeypatch.setattr(desktop, "_wait_http", lambda url, timeout, stop_check=None: True)
    holder = {"loop": object(), "ev": object()}
    assert desktop._await_node_ready(_FakeThread(False), holder, 9999, timeout=0.1) is False

def test_await_node_ready_true(monkeypatch):
    monkeypatch.setattr(desktop, "_wait_http", lambda url, timeout, stop_check=None: True)
    holder = {"loop": object(), "ev": object()}
    assert desktop._await_node_ready(_FakeThread(True), holder, 9999, timeout=0.1) is True

def test_await_node_ready_passes_a_stop_check_that_reflects_thread_and_error(monkeypatch):
    # _await_node_ready must wire a stop_check into _wait_http that goes
    # true as soon as the node thread dies or holder["error"] is set --
    # this is what lets the real _wait_http loop bail out early instead of
    # always burning the full timeout (task review MINOR).
    captured = {}
    def fake_wait_http(url, timeout, stop_check=None):
        captured["stop_check"] = stop_check
        return False
    monkeypatch.setattr(desktop, "_wait_http", fake_wait_http)
    holder = {"loop": object(), "ev": object()}
    desktop._await_node_ready(_FakeThread(True), holder, 9999, timeout=0.1)
    assert captured["stop_check"]() is False        # thread alive, no error -> keep waiting
    holder["error"] = "boom"
    assert captured["stop_check"]() is True          # error set -> stop

def test_await_node_ready_returns_fast_on_early_error():
    # End-to-end (real _wait_http, not mocked): if the node thread has
    # already recorded an error (e.g. missing tor.exe) or died, the
    # ready-wait must bail out immediately instead of polling the full
    # timeout -- otherwise the user stares at a blank window for the whole
    # wait before the error surfaces (task review MINOR).
    holder = {"loop": object(), "ev": object(), "error": "boom"}
    start = time.time()
    result = desktop._await_node_ready(_FakeThread(False), holder, 9999, timeout=10.0)
    elapsed = time.time() - start
    assert result is False
    assert elapsed < 2.0    # fails fast, nowhere near the full 10s timeout

def test_start_node_and_await_ready_with_a_real_node(tmp_path):
    # Real (non-Tor) run_serve on a free port -- proves the freeze-harden
    # wait/ready wiring works end to end, not just against mocks.
    port = desktop._free_port()
    t, holder = desktop._start_node(tmp_path, port, None, False)
    try:
        assert desktop._await_node_ready(t, holder, port, timeout=20.0) is True
    finally:
        desktop._signal_shutdown(holder)
        t.join(timeout=8)
        assert not t.is_alive()


def test_log_error_writes_app_log(tmp_path):
    desktop._log_error(tmp_path, "boom")
    text = (tmp_path / "app.log").read_text(encoding="utf-8")
    assert "boom" in text

def test_handle_start_failure_logs_and_avoids_real_gui(tmp_path, monkeypatch):
    shown = []
    monkeypatch.setattr(desktop, "_show_error_window",
                        lambda wv, msg: shown.append(msg))
    holder = {"error": "kaboom traceback"}
    desktop._handle_start_failure(tmp_path, _FakeThread(False), holder)
    log = (tmp_path / "app.log").read_text(encoding="utf-8")
    assert "node thread died" in log
    assert "kaboom traceback" in log
    assert len(shown) == 1                       # error window path was taken, not a hang


def test_launch_frozen_tor_on_passes_nonzero_gossip_port_to_run_serve(tmp_path, monkeypatch):
    # End-to-end proof of the CRITICAL fix: a frozen (packaged) launch runs
    # the node over Tor, and the gossip port threaded all the way from
    # launch() -> _start_node() -> run_serve() must be real and non-zero --
    # otherwise ADD_ONION either rejects Port=0 or publishes an onion
    # address pointing at a port nothing listens on.
    monkeypatch.setattr(desktop.paths, "is_frozen", lambda: True)   # frozen -> tor on
    monkeypatch.setattr(desktop, "_prepare_web_dir", lambda d: tmp_path / "web")

    captured = {}
    async def fake_run_serve(data_dir, gossip_port, http_port, tor=False,
                             shutdown=None, web_dir=None):
        captured.update(gossip_port=gossip_port, tor=tor)
        await shutdown.wait()          # block like the real node until asked to stop
    monkeypatch.setattr(desktop, "run_serve", fake_run_serve)
    monkeypatch.setattr(desktop, "_await_node_ready",
                        lambda t, holder, port, timeout: True)

    class FakeWindow:
        def destroy(self): pass
    monkeypatch.setattr("webview.create_window", lambda *a, **k: FakeWindow())
    monkeypatch.setattr("webview.start", lambda **k: None)  # returns immediately, no real GUI (accepts debug=)

    desktop.launch(tmp_path)

    assert captured["tor"] is True
    assert captured["gossip_port"] != 0
