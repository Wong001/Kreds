"""Live two-phase `hearth serve` integration: the bootstrap app (unenrolled,
create/pair endpoints) runs first, then hands off to the full node app on
the SAME port, proven over real HTTP against a real running server --
not `TestClient`, which builds each app in isolation and never exercises
the handoff itself.

tests/test_bootstrap.py already covers each app's endpoints in isolation
(build_bootstrap_app's status/create/pair-request, and build_app's
bootstrap/onboarding-done pair) via TestClient. What none of those cover
is hearth.runner.run_serve gluing the two together: the bootstrap
uvicorn.Server gracefully stopping and freeing http_port, then run_node's
own uvicorn.Server binding that SAME port -- the exact sequence that would
deadlock on Windows if HearthNode.create's sqlite handle weren't closed
before run_node re-opens hearth.db (see hearth/bootstrap.py's
`node.close()` after create/pair-install).

No pytest-asyncio in this project (see requirements.txt) -- mirrors
tests/test_tor_e2e.py's plain `asyncio.run(scenario())` pattern instead.
Uses httpx.AsyncClient against the live server (httpx is already a
dependency: FastAPI's own TestClient is a thin wrapper around it)."""
import asyncio
import socket
import time

import httpx

from hearth.runner import run_serve


def _free_port() -> int:
    """An OS-assigned free port (not the 7101-7204 demo range) -- bind to
    port 0, read back what the OS picked, release it immediately so
    run_serve's uvicorn.Server can bind it a moment later."""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


async def _poll(client: httpx.AsyncClient, url: str, predicate, timeout: float):
    """Poll url until predicate(json) is true. Tolerates connection errors
    (the port may be transiently unbound during the bootstrap->full-app
    handoff, or not yet bound at process start)."""
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            r = await client.get(url)
            if r.status_code == 200:
                last = r.json()
                if predicate(last):
                    return last
        except httpx.TransportError:
            pass
        await asyncio.sleep(0.2)
    raise AssertionError(f"timed out polling {url}; last response={last}")


def test_two_phase_serve_handoff_live(tmp_path):
    """Fresh empty data dir -> run_serve boots the bootstrap phase ->
    create -> polls through the handoff -> the full app answers on the
    SAME port, unenrolled state is gone, onboarding_done starts false and
    flips true through the real endpoint."""
    t0 = time.monotonic()
    http_port = _free_port()
    base = f"http://127.0.0.1:{http_port}"
    node_dir = tmp_path / "n"

    async def scenario():
        serve_task = asyncio.create_task(
            run_serve(node_dir, gossip_port=0, http_port=http_port))
        try:
            async with httpx.AsyncClient(timeout=5.0) as client:
                # -- phase 1: bootstrap app is up, nothing enrolled yet --
                b1 = await _poll(
                    client, f"{base}/api/bootstrap",
                    lambda j: j.get("initialized") is False, timeout=15)
                assert b1 == {"initialized": False}

                # -- create the node; bootstrap fires on_ready and starts
                # tearing itself down to hand off ------------------------
                r = await client.post(
                    f"{base}/api/bootstrap/create",
                    json={"name": "Wong", "device": "desktop"})
                assert r.status_code == 200
                assert r.json() == {"ok": True}

                # -- phase 2: poll through the handoff gap (bootstrap
                # uvicorn's graceful shutdown freeing the port, then
                # run_node's sync.start + its own uvicorn startup) until
                # the FULL app answers on the SAME port. Generous timeout:
                # this is the slowest step in the whole flow. -----------
                b2 = await _poll(
                    client, f"{base}/api/bootstrap",
                    lambda j: j.get("initialized") is True, timeout=45)
                assert b2 == {"initialized": True, "onboarding_done": False}

                # -- a real full-app route works (not just /api/bootstrap)
                r_state = await client.get(f"{base}/api/state")
                assert r_state.status_code == 200
                assert r_state.json()["device_name"] == "desktop"

                # -- onboarding-done flips the meta flag through the real
                # endpoint, visible back through /api/bootstrap ----------
                r_done = await client.post(f"{base}/api/onboarding-done")
                assert r_done.status_code == 200
                assert r_done.json() == {"ok": True}

                b3 = (await client.get(f"{base}/api/bootstrap")).json()
                assert b3 == {"initialized": True, "onboarding_done": True}
        finally:
            # Clean teardown: run_serve/run_node have no external shutdown
            # signal exposed to a caller in-process, so cancellation is the
            # documented way to stop them (mirrors run_node's own
            # loop_task.cancel() in its finally block). run_node's
            # try/finally (sync.stop(), own_tor.stop()) still executes as
            # the CancelledError propagates -- only uvicorn's OWN graceful
            # shutdown (socket close) is skipped on this path, same as any
            # process getting SIGKILLed; harmless for a short-lived test
            # process about to move on to the next test.
            serve_task.cancel()
            try:
                await serve_task
            except BaseException:
                pass

    asyncio.run(asyncio.wait_for(scenario(), timeout=75))

    elapsed = time.monotonic() - t0
    print(f"test_two_phase_serve_handoff_live: TERMINATED in {elapsed:.1f}s")
    assert elapsed < 75, "must terminate well inside the wait_for ceiling"
