"""run_node/run_serve startup stage callback (launch loading states, 0.3.11):
`status(stage, pct=None)` threads through so the desktop shell can show live
progress. Display-only - the default no-op must leave every existing caller
untouched. Stage names are a contract shared with desktop.py and app.js:
starting, tor-binary, tor-bootstrap, tor-waiting, onion-publish, serving,
ready, failed.
"""
import asyncio
import socket

from hearth import runner
from hearth.node import HearthNode


def _free_port():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); p = s.getsockname()[1]; s.close(); return p


def test_non_tor_status_sequence(tmp_path):
    node_dir = tmp_path / "n"
    HearthNode.create(node_dir, "Test Person", "test-device")
    events = []

    def status(stage, pct=None):
        events.append(stage)

    async def scenario():
        shutdown = asyncio.Event()
        task = asyncio.create_task(runner.run_node(
            node_dir, gossip_port=0, http_port=_free_port(), tor=False,
            shutdown=shutdown, status=status))
        for _ in range(200):
            if "ready" in events:
                break
            await asyncio.sleep(0.05)
        shutdown.set()
        await asyncio.wait_for(task, timeout=10)

    asyncio.run(scenario())
    assert events[0] == "starting"
    assert "serving" in events and "ready" in events
    assert events.index("serving") < events.index("ready")
    assert "tor-binary" not in events   # non-tor path skips the tor stages
    assert "tor-bootstrap" not in events


class _FakeTorProcess:
    """Stands in for TorProcess in run_node's tor path: no real tor, but the
    same surface run_node touches (start/stop, socks/control ports, cookie)."""
    def __init__(self, exe, data_dir):
        self.socks_port = 19050
        self.control_port = 19051
        self.cookie_path = data_dir / "control_auth_cookie"

    async def start(self, bootstrap_timeout: float = 90.0, status=None,
                    waiting=None):
        if status is not None:
            status(50)
            status(100)

    async def stop(self):
        pass


def test_tor_status_sequence(tmp_path, monkeypatch):
    node_dir = tmp_path / "n"
    HearthNode.create(node_dir, "Test Person", "test-device")
    events = []

    def status(stage, pct=None):
        events.append((stage, pct))

    async def fake_publish(control_port, cookie_path, virtual_port, target_port, key_blob):
        return "fakesvcid", None

    async def scenario():
        monkeypatch.setattr(runner, "ensure_tor_binary", lambda: "tor")
        monkeypatch.setattr(runner, "TorProcess", _FakeTorProcess)
        monkeypatch.setattr(runner, "publish_onion", fake_publish)
        shutdown = asyncio.Event()
        task = asyncio.create_task(runner.run_node(
            node_dir, gossip_port=0, http_port=_free_port(), tor=True,
            shutdown=shutdown, status=status))
        for _ in range(200):
            if ("ready", None) in events:
                break
            await asyncio.sleep(0.05)
        shutdown.set()
        await asyncio.wait_for(task, timeout=10)

    asyncio.run(scenario())
    stages = [e[0] for e in events]
    # full tor-path contract, in order
    expected_order = ["starting", "tor-binary", "tor-bootstrap",
                      "onion-publish", "serving", "ready"]
    positions = [stages.index(s) for s in expected_order]
    assert positions == sorted(positions), stages
    # bootstrap percent flows through: the 0 pre-start marker plus the
    # fake tor's 50 and 100
    boot = [pct for stage, pct in events if stage == "tor-bootstrap"]
    assert boot == [0, 50, 100]


def test_run_node_quit_during_tor_bootstrap_returns_and_stops_tor(
        tmp_path, monkeypatch):
    # Quitting DURING the (long) tor bootstrap must not wedge the node
    # thread: run_node races own_tor.start against shutdown, returns
    # promptly, and the finally still stops own_tor (so tor.exe isn't
    # orphaned holding its fixed ports).
    node_dir = tmp_path / "n"
    HearthNode.create(node_dir, "Test Person", "test-device")

    class _BlockingTor:
        stopped = []

        def __init__(self, exe, data_dir):
            self.socks_port = 19050
            self.control_port = 19051
            self.cookie_path = data_dir / "control_auth_cookie"

        async def start(self, bootstrap_timeout: float = 90.0, status=None,
                        waiting=None):
            if status is not None:
                status(10)
            await asyncio.Event().wait()          # never bootstraps

        async def stop(self):
            _BlockingTor.stopped.append(True)

    async def scenario():
        monkeypatch.setattr(runner, "ensure_tor_binary", lambda: "tor")
        monkeypatch.setattr(runner, "TorProcess", _BlockingTor)
        shutdown = asyncio.Event()
        task = asyncio.create_task(runner.run_node(
            node_dir, gossip_port=12345, http_port=_free_port(), tor=True,
            shutdown=shutdown, status=lambda *a, **k: None))
        await asyncio.sleep(0.2)                   # reach the blocking start
        shutdown.set()
        await asyncio.wait_for(task, timeout=5)    # returns promptly

    asyncio.run(scenario())
    assert _BlockingTor.stopped                    # own_tor was stopped


class _WaitingFakeTorProcess(_FakeTorProcess):
    async def start(self, bootstrap_timeout: float = 90.0, status=None,
                    waiting=None):
        if waiting is not None:
            waiting()                       # one spawn-exit retry happened
        await super().start(bootstrap_timeout, status)


def test_tor_waiting_stage_surfaces(tmp_path, monkeypatch):
    node_dir = tmp_path / "n"
    HearthNode.create(node_dir, "Test Person", "test-device")
    events = []

    def status(stage, pct=None):
        events.append(stage)

    async def fake_publish(control_port, cookie_path, virtual_port, target_port, key_blob):
        return "fakesvcid", None

    async def scenario():
        monkeypatch.setattr(runner, "ensure_tor_binary", lambda: "tor")
        monkeypatch.setattr(runner, "TorProcess", _WaitingFakeTorProcess)
        monkeypatch.setattr(runner, "publish_onion", fake_publish)
        shutdown = asyncio.Event()
        task = asyncio.create_task(runner.run_node(
            node_dir, gossip_port=0, http_port=_free_port(), tor=True,
            shutdown=shutdown, status=status))
        for _ in range(200):
            if "ready" in events:
                break
            await asyncio.sleep(0.05)
        shutdown.set()
        await asyncio.wait_for(task, timeout=10)

    asyncio.run(scenario())
    assert "tor-waiting" in events
    assert events.index("tor-waiting") < events.index("ready")


def test_sync_stop_hang_does_not_orphan_tor(tmp_path, monkeypatch):
    # Final review, Finding 2: sync.stop() awaits Server.wait_closed(),
    # which on 3.12 blocks until in-flight connection handlers finish.
    # _session's post-hello reads are unbounded, so a peer that stalls
    # mid-session parks the inbound handler forever -- sync.stop() hangs,
    # own_tor.stop() never runs, and tor is orphaned (the exact symptom
    # 0.3.14 claims to fix). run_node's finally must bound sync.stop() so
    # a stalled in-flight session can never prevent tor's graceful stop.
    node_dir = tmp_path / "n"
    HearthNode.create(node_dir, "Test Person", "test-device")
    events = []

    def status(stage, pct=None):
        events.append(stage)

    async def fake_publish(control_port, cookie_path, virtual_port, target_port, key_blob):
        return "fakesvcid", None

    async def hanging_stop(self):
        await asyncio.Event().wait()   # never returns: simulates a parked
                                       # in-flight session handler

    class _TrackingTorProcess(_FakeTorProcess):
        stopped = []

        async def stop(self):
            _TrackingTorProcess.stopped.append(True)
            await super().stop()

    async def scenario():
        monkeypatch.setattr(runner, "ensure_tor_binary", lambda: "tor")
        monkeypatch.setattr(runner, "TorProcess", _TrackingTorProcess)
        monkeypatch.setattr(runner, "publish_onion", fake_publish)
        monkeypatch.setattr(runner.SyncService, "stop", hanging_stop)
        shutdown = asyncio.Event()
        task = asyncio.create_task(runner.run_node(
            node_dir, gossip_port=0, http_port=_free_port(), tor=True,
            shutdown=shutdown, status=status))
        for _ in range(200):
            if "ready" in events:
                break
            await asyncio.sleep(0.05)
        shutdown.set()
        await asyncio.wait_for(task, timeout=10)   # must return, not hang

    asyncio.run(scenario())
    assert _TrackingTorProcess.stopped   # own_tor.stop() still ran


def test_run_serve_forwards_status_to_run_node(tmp_path, monkeypatch):
    """Enrolled dir: run_serve skips the bootstrap phase and must hand the
    status callback straight to run_node."""
    node_dir = tmp_path / "n"
    HearthNode.create(node_dir, "Test Person", "test-device")
    seen = {}

    async def fake_run_node(*args, **kwargs):
        seen["status"] = kwargs.get("status")

    def status(stage, pct=None):
        pass

    async def scenario():
        monkeypatch.setattr(runner, "run_node", fake_run_node)
        await runner.run_serve(node_dir, gossip_port=0, http_port=0,
                               status=status)

    asyncio.run(asyncio.wait_for(scenario(), timeout=10))
    assert seen["status"] is status
