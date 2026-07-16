"""tor.exe must be launched with CREATE_NO_WINDOW on Windows so no blank
console window appears next to the packaged app (and can't be closed to kill
tor -> freeze). Regression test for the andre-PC freeze."""
import asyncio
import subprocess
import sys

import hearth.tor as tor


class _FakeProc:
    def __init__(self):
        self.stdout = self
        self._lines = [b"Bootstrapped 100% (done)\n"]

    async def readline(self):
        return self._lines.pop(0) if self._lines else b""

    def terminate(self):
        pass

    async def wait(self):
        return 0


class _FailThenEofProc:
    """A tor that exits before bootstrap (stdout hits EOF immediately)."""
    def __init__(self):
        self.stdout = self
        self.returncode = None

    async def readline(self):
        self.returncode = 0
        return b""                      # EOF -> "tor exited before bootstrap"

    def terminate(self):
        pass

    async def wait(self):
        return 0


def test_tor_retries_once_on_bootstrap_failure(monkeypatch, tmp_path):
    calls = {"n": 0}

    async def fake_exec(*args, **kwargs):
        calls["n"] += 1
        return _FailThenEofProc() if calls["n"] == 1 else _FakeProc()

    async def scenario():
        monkeypatch.setattr(tor.asyncio, "create_subprocess_exec", fake_exec)
        tp = tor.TorProcess(exe="tor", data_dir=tmp_path)
        await asyncio.wait_for(tp.start(bootstrap_timeout=5), timeout=8)

    asyncio.run(scenario())
    assert calls["n"] == 2              # first failed, retried once, then bootstrapped


def test_tor_launched_with_no_window_flag(monkeypatch, tmp_path):
    captured = {}

    async def fake_exec(*args, **kwargs):
        captured.update(kwargs)
        return _FakeProc()

    async def scenario():
        monkeypatch.setattr(tor.asyncio, "create_subprocess_exec", fake_exec)
        tp = tor.TorProcess(exe="tor", data_dir=tmp_path)
        # start() reads until the bootstrap line; any post-bootstrap control
        # work is irrelevant -- creationflags is captured at the launch call.
        try:
            await asyncio.wait_for(tp.start(bootstrap_timeout=5), timeout=5)
        except Exception:
            pass

    asyncio.run(scenario())
    expected = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
    assert captured.get("creationflags") == expected


class _PctFakeProc:
    """Feeds scripted 'Bootstrapped NN%' lines through start()'s parsing."""
    def __init__(self, lines):
        self.stdout = self
        self._lines = list(lines)

    async def readline(self):
        return self._lines.pop(0) if self._lines else b""

    def terminate(self):
        pass

    async def wait(self):
        return 0


def test_bootstrap_percent_reported(monkeypatch, tmp_path):
    lines = [b"May 1 [notice] Bootstrapped 10% (conn): Connecting\n",
             b"May 1 [notice] Bootstrapped 45% (loading): Loading\n",
             b"May 1 [notice] Bootstrapped 100% (done): Done\n"]
    seen = []

    async def fake_exec(*args, **kwargs):
        return _PctFakeProc(lines)

    async def scenario():
        monkeypatch.setattr(tor.asyncio, "create_subprocess_exec", fake_exec)
        tp = tor.TorProcess(exe="tor", data_dir=tmp_path)
        await asyncio.wait_for(
            tp.start(bootstrap_timeout=5.0, status=seen.append), timeout=8)

    asyncio.run(scenario())
    assert seen == [10, 45, 100]


def test_bootstrap_status_exception_does_not_kill_startup(monkeypatch, tmp_path):
    # A broken progress callback must never abort tor startup.
    lines = [b"May 1 [notice] Bootstrapped 10% (conn): Connecting\n",
             b"May 1 [notice] Bootstrapped 100% (done): Done\n"]

    def bad_status(pct):
        raise ValueError("boom")

    async def fake_exec(*args, **kwargs):
        return _PctFakeProc(lines)

    async def scenario():
        monkeypatch.setattr(tor.asyncio, "create_subprocess_exec", fake_exec)
        tp = tor.TorProcess(exe="tor", data_dir=tmp_path)
        await asyncio.wait_for(
            tp.start(bootstrap_timeout=5.0, status=bad_status), timeout=8)

    asyncio.run(scenario())               # must not raise


class _StopFakeProc:
    """For TorProcess.stop() tests: simulates SIGNAL SHUTDOWN either being
    honored (proc exits promptly) or ignored (wait() hangs past the grace
    period until terminate() unblocks it)."""
    def __init__(self, exits_after_signal):
        self.terminated = False
        self._done = asyncio.Event()
        if exits_after_signal:
            self._done.set()

    def terminate(self):
        self.terminated = True
        self._done.set()

    async def wait(self):
        await self._done.wait()
        return 0


def test_stop_sends_shutdown_signal_before_terminate(monkeypatch, tmp_path):
    calls = []

    async def fake_control(control_port, cookie_path, command):
        calls.append(command)
        return []

    tp = tor.TorProcess(exe="tor", data_dir=tmp_path)
    fake_proc = _StopFakeProc(exits_after_signal=True)
    tp._proc = fake_proc

    async def scenario():
        monkeypatch.setattr(tor, "_control_command", fake_control)
        await tp.stop()

    asyncio.run(scenario())
    assert calls == ["SIGNAL SHUTDOWN"]
    assert not fake_proc.terminated       # graceful path won; no hard kill
    assert tp._proc is None               # cleaned up after stop


def test_stop_falls_back_to_terminate_when_signal_ignored(monkeypatch, tmp_path):
    async def fake_control(control_port, cookie_path, command):
        return []                          # tor accepts the connection but
                                            # never actually shuts down

    tp = tor.TorProcess(exe="tor", data_dir=tmp_path)
    tp._SHUTDOWN_GRACE = 0.05              # patched short so the test is fast
    fake_proc = _StopFakeProc(exits_after_signal=False)
    tp._proc = fake_proc

    async def scenario():
        monkeypatch.setattr(tor, "_control_command", fake_control)
        await tp.stop()

    asyncio.run(scenario())
    assert fake_proc.terminated            # fallback fired
    assert tp._proc is None


def test_stop_falls_back_when_control_command_raises(monkeypatch, tmp_path):
    # No control connection at all (e.g. tor already dead) must still reach
    # the hard-terminate fallback instead of raising out of stop().
    async def fake_control(control_port, cookie_path, command):
        raise ConnectionRefusedError("no control port")

    tp = tor.TorProcess(exe="tor", data_dir=tmp_path)
    tp._SHUTDOWN_GRACE = 0.05
    fake_proc = _StopFakeProc(exits_after_signal=False)
    tp._proc = fake_proc

    async def scenario():
        monkeypatch.setattr(tor, "_control_command", fake_control)
        await tp.stop()

    asyncio.run(scenario())
    assert fake_proc.terminated
    assert tp._proc is None


class _ExitNTimesThenGood:
    """Factory state for create_subprocess_exec fakes: the first `n` procs
    exit before bootstrap (stdout EOF immediately), later ones bootstrap."""
    def __init__(self, n):
        self.n = n
        self.calls = 0

    async def __call__(self, *args, **kwargs):
        self.calls += 1
        return _FailThenEofProc() if self.calls <= self.n else _FakeProc()


def test_spawn_exit_retries_past_two_attempts_within_window(monkeypatch, tmp_path):
    # The orphan case: 4 instant-exits, then success -- must NOT give up
    # at 2 attempts like the old flat loop did.
    fake = _ExitNTimesThenGood(4)
    waits = []

    async def scenario():
        monkeypatch.setattr(tor.asyncio, "create_subprocess_exec", fake)
        tp = tor.TorProcess(exe="tor", data_dir=tmp_path)
        tp._SPAWN_RETRY_GAP = 0.01          # fast test; window stays ample
        await asyncio.wait_for(
            tp.start(bootstrap_timeout=5, waiting=lambda: waits.append(None)), timeout=8)

    asyncio.run(scenario())
    assert fake.calls == 5                  # 4 failures + the success
    assert len(waits) == 4                  # waiting() per scheduled retry
    # waits entries are None (waiting takes no args); presence is the signal


def test_spawn_exit_gives_up_after_window(monkeypatch, tmp_path):
    fake = _ExitNTimesThenGood(10 ** 6)     # never succeeds

    async def scenario():
        monkeypatch.setattr(tor.asyncio, "create_subprocess_exec", fake)
        tp = tor.TorProcess(exe="tor", data_dir=tmp_path)
        tp._SPAWN_RETRY_GAP = 0.01
        tp._SPAWN_RETRY_WINDOW = 0.05
        await tp.start(bootstrap_timeout=5)

    import pytest
    with pytest.raises(RuntimeError, match="tor failed to bootstrap"):
        asyncio.run(scenario())
    assert fake.calls >= 2                  # it did keep retrying first


def test_waiting_callback_exception_never_kills_startup(monkeypatch, tmp_path):
    fake = _ExitNTimesThenGood(1)

    def bad_waiting():
        raise ValueError("boom")

    async def scenario():
        monkeypatch.setattr(tor.asyncio, "create_subprocess_exec", fake)
        tp = tor.TorProcess(exe="tor", data_dir=tmp_path)
        tp._SPAWN_RETRY_GAP = 0.01
        await asyncio.wait_for(
            tp.start(bootstrap_timeout=5, waiting=bad_waiting), timeout=8)

    asyncio.run(scenario())                 # must not raise
    assert fake.calls == 2


class _HangForeverProc:
    """Never emits a bootstrap line and never EOFs: forces the TIMEOUT
    failure kind (asyncio.TimeoutError), not the spawn-exit kind."""
    def __init__(self):
        self.stdout = self
        self.returncode = None

    async def readline(self):
        await asyncio.sleep(3600)

    def terminate(self):
        self.returncode = 1

    async def wait(self):
        return 1


def test_bootstrap_timeout_budget_still_two_attempts(monkeypatch, tmp_path):
    calls = {"n": 0}

    async def fake_exec(*args, **kwargs):
        calls["n"] += 1
        return _HangForeverProc()

    async def scenario():
        monkeypatch.setattr(tor.asyncio, "create_subprocess_exec", fake_exec)
        tp = tor.TorProcess(exe="tor", data_dir=tmp_path)
        tp._SPAWN_RETRY_GAP = 0.01
        await tp.start(bootstrap_timeout=0.05)

    import pytest
    with pytest.raises(RuntimeError, match="tor failed to bootstrap"):
        asyncio.run(scenario())
    assert calls["n"] == 2                  # timeout budget unchanged


def test_publish_onion_uses_fixed_virtual_port(monkeypatch, tmp_path):
    # Bug 1 (0.3.14): the onion VIRTUAL port is fixed so a node's dialable
    # address never churns across restarts; the TARGET is the ephemeral
    # local bind port (they now differ).
    import asyncio
    captured = {}

    async def fake_control(control_port, cookie_path, command):
        captured["cmd"] = command
        return ["250-ServiceID=abcdef", "250 OK"]

    monkeypatch.setattr(tor, "_control_command", fake_control)

    async def scenario():
        sid, blob = await tor.publish_onion(
            9051, tmp_path / "cookie", tor.ONION_VIRTUAL_PORT, 54321,
            key_blob="KEY")
        return sid

    sid = asyncio.run(scenario())
    assert sid == "abcdef"
    assert tor.ONION_VIRTUAL_PORT == 9997
    # virtual (public) 9997 -> target (local bind) 54321
    assert "Port=9997,127.0.0.1:54321" in captured["cmd"]
