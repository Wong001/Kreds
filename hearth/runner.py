"""Wire one node's daemon: gossip listener + loop + localhost HTTP."""
from __future__ import annotations

import asyncio
import json
from pathlib import Path

import uvicorn

from .api import build_app
from .node import HearthNode
from .sync import SyncService
from .transport import TorTransport
from .tor import ONION_VIRTUAL_PORT, TorProcess, ensure_tor_binary, publish_onion


async def run_node(data_dir, gossip_port: int, http_port: int,
                   interval: float = 3.0, tor: bool = False,
                   tor_process: "TorProcess | None" = None,
                   publish: bool = True,
                   shutdown: "asyncio.Event | None" = None,
                   web_dir=None,
                   status=None):
    # Display-only startup progress for the desktop shell. Stage names are
    # a contract with desktop.py's loading page and app.js: starting,
    # tor-binary, tor-bootstrap, tor-waiting, onion-publish, serving, ready,
    # failed.
    status = status or (lambda stage, pct=None: None)
    status("starting")
    node = HearthNode(data_dir)
    node.web_dir = web_dir
    own_tor = None
    sync = None
    try:
        if tor:
            if tor_process is None:
                status("tor-binary")
                exe = ensure_tor_binary()
                own_tor = TorProcess(exe, node.data_dir / "tordata")
                status("tor-bootstrap", 0)
                # own_tor.start blocks ~90-185s; race it against a shutdown
                # request so quitting DURING bootstrap doesn't leave the
                # daemon thread wedged here and tor.exe orphaned (its fixed
                # socks/control ports would then collide with the next
                # launch). If shutdown wins, cancel the start and return --
                # the finally still stops own_tor (cancelling start leaves
                # its _proc set, so stop() can terminate it).
                start_task = asyncio.ensure_future(own_tor.start(
                    status=lambda pct: status("tor-bootstrap", pct),
                    waiting=lambda: status("tor-waiting")))
                waiters = {start_task}
                sd_task = (asyncio.ensure_future(shutdown.wait())
                           if shutdown is not None else None)
                if sd_task is not None:
                    waiters.add(sd_task)
                done, _pending = await asyncio.wait(
                    waiters, return_when=asyncio.FIRST_COMPLETED)
                if sd_task is not None and sd_task in done:  # quit mid-bootstrap
                    start_task.cancel()
                    await asyncio.gather(start_task, return_exceptions=True)
                    return
                if sd_task is not None:
                    sd_task.cancel()
                    try:
                        await sd_task
                    except asyncio.CancelledError:
                        pass
                await start_task               # re-raise a failed bootstrap
                tor_process = own_tor          # is stopped by the finally
            sync = SyncService(node,
                               transport=TorTransport(tor_process.socks_port))
            # Bind the FIXED gossip_port: the onion service maps to it, so the
            # published address must point at the port we actually listen on.
            await sync.start("127.0.0.1", gossip_port)
            if publish:
                status("onion-publish")
                service_id, blob = await publish_onion(
                    tor_process.control_port, tor_process.cookie_path,
                    ONION_VIRTUAL_PORT, gossip_port, node.onion_key)
                if blob and blob != node.onion_key:
                    node.save_onion_key(blob)
                node.store.set_meta(
                    "gossip_addr",
                    f"{service_id}.onion:{ONION_VIRTUAL_PORT}")
            # else: the caller already published (detached) and set gossip_addr
        else:
            sync = SyncService(node)
            port = await sync.start("127.0.0.1", gossip_port)
            node.store.set_meta("gossip_addr", f"127.0.0.1:{port}")
        status("serving")
        # bound graceful shutdown: the /ws handler parks in await q.get() and
        # never self-exits, so an unbounded shutdown hangs quit and orphans
        # tor (0.3.14).
        server = uvicorn.Server(uvicorn.Config(
            build_app(node, web_dir=web_dir), host="127.0.0.1", port=http_port,
            log_level="warning", timeout_graceful_shutdown=3))
        watcher = None
        if shutdown is not None:
            async def _watch():
                await shutdown.wait()
                server.should_exit = True
            watcher = asyncio.create_task(_watch())
        loop_task = asyncio.create_task(sync.gossip_loop(interval))
        try:
            # Display-only: the port binds inside serve(), so the desktop
            # shell's HTTP poll (_await_node_ready) stays the authoritative
            # readiness check.
            status("ready")
            await server.serve()
        finally:
            loop_task.cancel()
            try:
                await loop_task
            except BaseException:
                pass
            if watcher:
                watcher.cancel()
                try:
                    await watcher
                except BaseException:
                    pass
    finally:
        if sync is not None:
            # Bounded (final review, Finding 2): sync.stop() awaits
            # Server.wait_closed(), which on 3.12 blocks until in-flight
            # connection handlers finish. _session's post-hello reads are
            # unbounded, so a peer that stalls mid-session parks the
            # inbound handler forever -- an unbounded sync.stop() here
            # would then hang and own_tor.stop() below would never run,
            # orphaning tor (the exact symptom 0.3.14 fixes via the
            # neighbouring uvicorn-shutdown bound). A stalled in-flight
            # session must not block tor's graceful stop.
            try:
                await asyncio.wait_for(sync.stop(), timeout=5.0)
            except Exception:
                pass
        if own_tor is not None:
            await own_tor.stop()


def _enrolled(data_dir) -> bool:
    """True once keys.json holds an ENROLLED identity (cert non-null) --
    not merely once keys.json exists. pair_request writes an unenrolled
    keys.json mid-pairing (device keys with no cert yet), so existence
    alone would skip the bootstrap phase before pairing has completed."""
    kp = Path(data_dir) / "keys.json"
    if not kp.exists():
        return False
    try:
        return json.loads(kp.read_text()).get("cert") is not None
    except Exception:
        return False


async def run_serve(data_dir, gossip_port: int, http_port: int,
                    interval: float = 3.0, tor: bool = False,
                    shutdown: "asyncio.Event | None" = None,
                    web_dir=None,
                    status=None):
    """Two-phase `hearth serve`: if no identity is enrolled yet, run the
    node-less bootstrap app (create/pair endpoints) on http_port until a
    create or pair-install completes, then shut it down and hand off to
    the full node app on the same port. If already enrolled, skip
    straight to run_node."""
    if not _enrolled(data_dir):
        if status is not None:
            status("starting")   # bootstrap phase; run_node re-emits the
                                 # full sequence at handoff
        from .bootstrap import build_bootstrap_app
        ready = asyncio.Event()
        app = build_bootstrap_app(data_dir, ready.set, web_dir=web_dir)
        server = uvicorn.Server(uvicorn.Config(
            app, host="127.0.0.1", port=http_port, log_level="warning",
            timeout_graceful_shutdown=3))
        task = asyncio.create_task(server.serve())
        ready_task = asyncio.create_task(ready.wait())
        # Whole-branch review, MINOR #6: awaiting ONLY ready.wait() hangs
        # forever if server.serve() returns early (e.g. http_port already
        # in use) without ever calling on_ready. Wait on both and fail
        # loud instead of hanging if the server exits first.
        waiters = {ready_task, task}
        sd_task = asyncio.create_task(shutdown.wait()) if shutdown is not None else None
        if sd_task:
            waiters.add(sd_task)
        done, pending = await asyncio.wait(
            waiters, return_when=asyncio.FIRST_COMPLETED)
        if sd_task and sd_task in done:   # user quit during first-run
            server.should_exit = True
            ready_task.cancel()
            try:
                await ready_task
            except asyncio.CancelledError:
                pass
            await asyncio.gather(task, return_exceptions=True)
            return
        if task in done:
            ready_task.cancel()
            try:
                await ready_task
            except asyncio.CancelledError:
                pass
            if sd_task:
                sd_task.cancel()
                try:
                    await sd_task
                except asyncio.CancelledError:
                    pass
            raise RuntimeError(
                "bootstrap server exited before an identity was created")
        if sd_task:
            sd_task.cancel()
            try:
                await sd_task
            except asyncio.CancelledError:
                pass
        server.should_exit = True
        await task                        # graceful shutdown frees the port
    await run_node(data_dir, gossip_port, http_port, interval, tor=tor,
                   shutdown=shutdown, web_dir=web_dir, status=status)
