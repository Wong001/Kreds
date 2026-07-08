"""hearth.runner.run_serve: the two-phase `hearth serve` glue itself (the
bootstrap<->full-app handoff over real HTTP is covered live by
tests/test_onboarding_integration.py). This file covers run_serve's own
control flow with fakes/monkeypatches - fast, no live server needed.

Whole-branch review, MINOR #6: `await ready.wait()` alone hangs forever if
the bootstrap uvicorn.Server.serve() returns early (e.g. http_port already
in use) without ever calling on_ready. run_serve must instead wait on BOTH
the ready signal and the server task, and fail loud if the server exits
first."""
import asyncio
import socket

import httpx
import pytest

from hearth import runner
from hearth.runner import run_serve


def _free_port():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); p = s.getsockname()[1]; s.close(); return p


def test_run_serve_stops_on_shutdown_event(tmp_path):
    async def scenario():
        port = _free_port()
        shutdown = asyncio.Event()
        task = asyncio.create_task(run_serve(tmp_path / "n", 0, port, shutdown=shutdown))
        # wait for the bootstrap server to answer
        async with httpx.AsyncClient() as c:
            for _ in range(60):
                try:
                    if (await c.get(f"http://127.0.0.1:{port}/api/bootstrap")).status_code == 200:
                        break
                except Exception:
                    pass
                await asyncio.sleep(0.1)
        shutdown.set()                              # request quit (still in bootstrap phase)
        await asyncio.wait_for(task, timeout=10)    # must return, not hang
    asyncio.run(scenario())


def test_run_serve_raises_if_bootstrap_server_exits_before_ready(tmp_path, monkeypatch):
    """Simulates a bind failure: the fake server's serve() returns
    immediately without the bootstrap app ever handling a create/
    pair-install, so on_ready() (ready.set()) never fires. Pre-fix, this
    would hang on `await ready.wait()` forever; post-fix it raises a clear
    RuntimeError instead."""
    class FakeServer:
        def __init__(self, config):
            self.config = config
            self.should_exit = False

        async def serve(self):
            return   # early exit, e.g. "address already in use"

    monkeypatch.setattr(runner.uvicorn, "Server", FakeServer)

    async def scenario():
        with pytest.raises(RuntimeError, match="exited before"):
            await runner.run_serve(tmp_path / "n", gossip_port=0, http_port=0)

    asyncio.run(asyncio.wait_for(scenario(), timeout=10))
