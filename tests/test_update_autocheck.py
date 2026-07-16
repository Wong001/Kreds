"""Auto-update check tick (0.3.15): the node periodically runs the
UNCHANGED update.check() off the gossip loop and stores a small status the
banner renders. Best-effort - check() never raises; a None result means
no banner."""
import asyncio

from hearth import node as node_mod
from hearth.node import HearthNode


def _node(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    n.web_dir = None
    return n


def test_maybe_check_update_sets_core_status(tmp_path, monkeypatch):
    n = _node(tmp_path)
    # A truly first-ever call always seeds _update_started_at to its OWN
    # now_monotonic (elapsed 0 < the 60s delay), so it never itself performs
    # a check regardless of the value passed - see
    # test_maybe_check_update_respects_startup_delay_and_interval, where the
    # first (bootstrap) call is the one that establishes the baseline and a
    # LATER call is the one that clears the gate. Seed the baseline directly
    # here to simulate "the loop already ticked once, well in the past" so
    # this single real call can exercise the actual check() -> status path.
    n._update_started_at = 0.0
    monkeypatch.setattr(node_mod.update, "check", lambda web_dir=None: {
        "version": "0.3.16", "web_available": True, "core_available": True})
    notified = []
    monkeypatch.setattr(n, "notify", lambda: notified.append(1))
    asyncio.run(n.maybe_check_update(10_000.0))
    # core takes precedence when both are available (web is gated behind it)
    assert n.update_status == {"available": True, "kind": "core",
                               "version": "0.3.16"}
    assert notified                      # status change pushed to the UI


def test_maybe_check_update_web_only(tmp_path, monkeypatch):
    n = _node(tmp_path)
    n._update_started_at = 0.0    # see comment in test_..._sets_core_status
    monkeypatch.setattr(node_mod.update, "check", lambda web_dir=None: {
        "version": "0.3.16", "web_available": True, "core_available": False})
    asyncio.run(n.maybe_check_update(10_000.0))
    assert n.update_status["kind"] == "web"
    assert n.update_status["available"] is True


def test_maybe_check_update_none_leaves_unavailable(tmp_path, monkeypatch):
    n = _node(tmp_path)
    n._update_started_at = 0.0    # see comment in test_..._sets_core_status -
                                   # without this the single call below would
                                   # gate out before ever reaching check(),
                                   # passing vacuously rather than exercising
                                   # the None -> unavailable branch.
    monkeypatch.setattr(node_mod.update, "check", lambda web_dir=None: None)
    notified = []
    monkeypatch.setattr(n, "notify", lambda: notified.append(1))
    asyncio.run(n.maybe_check_update(10_000.0))
    assert n.update_status["available"] is False
    assert not notified                  # no change -> no push


def test_maybe_check_update_respects_startup_delay_and_interval(tmp_path, monkeypatch):
    n = _node(tmp_path)
    calls = {"n": 0}
    def fake_check(web_dir=None):
        calls["n"] += 1
        return None
    monkeypatch.setattr(node_mod.update, "check", fake_check)
    asyncio.run(n.maybe_check_update(10.0))          # < 60s startup delay
    assert calls["n"] == 0                            # too early
    asyncio.run(n.maybe_check_update(70.0))          # past 60s -> first check
    assert calls["n"] == 1
    asyncio.run(n.maybe_check_update(80.0))          # < 6h since last
    assert calls["n"] == 1                            # gated
    asyncio.run(n.maybe_check_update(70.0 + 6 * 3600 + 1))
    assert calls["n"] == 2                            # interval elapsed
