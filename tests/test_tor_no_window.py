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
