"""hearth.desktop integration (Task 4): the two helpers desktop.launch() itself
uses to build a real config - default_data_dir() and _free_port() - compose
into an actually-runnable run_serve invocation, and the SAME shutdown-event
lifecycle guarantee tests/test_runner.py::test_run_serve_stops_on_shutdown_event
already proves (starts on a free port, answers, a shutdown event stops it
cleanly, the task returns - no hang) holds through that composition, not just
a hand-built tmp_path/port pair. This does NOT create a pywebview window - a
real GUI can't be driven headless; the window itself is August's to verify."""
import asyncio

import httpx

from hearth import desktop
from hearth.runner import run_serve


def test_default_data_dir_and_free_port_compose_into_a_runnable_config(
        tmp_path, monkeypatch):
    monkeypatch.setenv("APPDATA", str(tmp_path))
    data_dir = desktop.default_data_dir()          # exactly what launch() computes
    assert data_dir == tmp_path / "Kreds"
    data_dir.mkdir(parents=True, exist_ok=True)     # launch() does this before run_serve
    port = desktop._free_port()                    # exactly what launch() picks

    async def scenario():
        shutdown = asyncio.Event()
        task = asyncio.create_task(
            run_serve(data_dir, 0, port, shutdown=shutdown))
        async with httpx.AsyncClient() as c:
            for _ in range(60):
                try:
                    r = await c.get(f"http://127.0.0.1:{port}/api/bootstrap")
                    if r.status_code == 200:
                        break
                except Exception:
                    pass
                await asyncio.sleep(0.1)
            else:
                raise AssertionError("node never answered on the desktop-picked port")
        shutdown.set()                               # Api.quit()'s effect, via the event
        await asyncio.wait_for(task, timeout=10)      # must return, not hang
    asyncio.run(scenario())
