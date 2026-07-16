import asyncio

from hearth.node import HearthNode
from hearth.sync import SyncService, _is_onion, _merge_peer_address


def test_is_onion_detects_suffix():
    assert _is_onion("abc.onion:15001") is True
    assert _is_onion("127.0.0.1:7101") is False


def test_onion_peer_skipped_until_interval(tmp_path, monkeypatch):
    # A single gossip round with a fake clock: an onion peer dialed once,
    # then skipped on the next immediate round; a tcp peer dialed both.
    from hearth import messages
    monkeypatch.setattr(messages, "ONION_SYNC_INTERVAL", 45.0)
    node = HearthNode.create(tmp_path / "n", "Wong", "phone")
    node.store.add_peer("abc.onion:15001", node.identity_pub)
    node.store.add_peer("127.0.0.1:7999", node.identity_pub)
    svc = SyncService(node)
    dialed = []

    async def fake_sync_with(addr):
        dialed.append(addr)
        return False
    svc.sync_with = fake_sync_with

    async def scenario():
        clock = [1000.0]
        # one loop body iteration at t=1000, then again at t=1010 (<45s)
        await svc._gossip_round(now=lambda: clock[0])
        clock[0] = 1010.0
        await svc._gossip_round(now=lambda: clock[0])
    asyncio.run(scenario())

    # tcp peer dialed both rounds; onion peer only the first
    assert dialed.count("127.0.0.1:7999") == 2
    assert dialed.count("abc.onion:15001") == 1


def test_guardrail_tcp_addr_never_shadows_known_onion(tmp_path):
    # Once an onion address is known for an identity, a later non-onion
    # address for the SAME identity must not be stored (whether it arrives
    # as the peer's self-announced addr or in the gossiped peer list).
    node = HearthNode.create(tmp_path / "n", "Wong", "phone")
    friend = "aa" * 32
    node.store.add_identity(friend)
    _merge_peer_address(node.store, friend, "xyz.onion:7000")
    _merge_peer_address(node.store, friend, "203.0.113.5:7000")  # TCP later
    addrs = {p["address"] for p in node.store.list_peers()
             if p["identity_pub"] == friend}
    assert addrs == {"xyz.onion:7000"}                # TCP did not shadow it


def test_guardrail_tcp_kept_when_no_onion_known(tmp_path):
    # With no onion address known, a TCP address is stored normally.
    node = HearthNode.create(tmp_path / "n", "Wong", "phone")
    friend = "bb" * 32
    node.store.add_identity(friend)
    _merge_peer_address(node.store, friend, "203.0.113.5:7000")
    addrs = {p["address"] for p in node.store.list_peers()
             if p["identity_pub"] == friend}
    assert addrs == {"203.0.113.5:7000"}


def test_guardrail_onion_always_stored(tmp_path):
    # An onion address is stored even when a TCP address is already known,
    # and learning the onion evicts the now-shadowed TCP row so gossip
    # never dials or propagates the stale clearnet address again.
    node = HearthNode.create(tmp_path / "n", "Wong", "phone")
    friend = "cc" * 32
    node.store.add_identity(friend)
    _merge_peer_address(node.store, friend, "203.0.113.5:7000")
    _merge_peer_address(node.store, friend, "xyz.onion:7000")
    addrs = {p["address"] for p in node.store.list_peers()
             if p["identity_pub"] == friend}
    assert addrs == {"xyz.onion:7000"}   # TCP row evicted, only onion remains


def test_stale_same_host_onion_port_evicted_on_merge(tmp_path):
    # Final review, Finding 1: TorTransport.connect (0.3.14) normalizes
    # every .onion dial to the fixed ONION_VIRTUAL_PORT, so a stale
    # pre-0.3.14 row (same host, old port) now dials the SAME live
    # service as the fresh row -- redundant full gossip sessions every
    # round, a stale self-row syncing with itself over Tor, and the
    # stale row propagating family-wide via HAVE/pairing. Merging the
    # fresh-port row must drain the stale same-host row, not duplicate it.
    node = HearthNode.create(tmp_path / "n", "Wong", "phone")
    friend = "dd" * 32
    node.store.add_identity(friend)
    _merge_peer_address(node.store, friend, "host.onion:1117")  # stale pre-0.3.14 port
    _merge_peer_address(node.store, friend, "host.onion:9997")  # fresh normalized port
    addrs = {p["address"] for p in node.store.list_peers()
             if p["identity_pub"] == friend}
    assert addrs == {"host.onion:9997"}   # stale-port row drained, no duplicate


def test_different_onion_host_same_identity_both_kept(tmp_path):
    # Host-keyed, not identity-keyed: one identity can legitimately own
    # multiple DIFFERENT onion hosts across devices (e.g. phone + desktop).
    # Those must both survive -- only a same-host, different-port row is a
    # stale duplicate of the same node.
    node = HearthNode.create(tmp_path / "n", "Wong", "phone")
    friend = "ee" * 32
    node.store.add_identity(friend)
    _merge_peer_address(node.store, friend, "hosta.onion:9997")
    _merge_peer_address(node.store, friend, "hostb.onion:9997")
    addrs = {p["address"] for p in node.store.list_peers()
             if p["identity_pub"] == friend}
    assert addrs == {"hosta.onion:9997", "hostb.onion:9997"}   # both preserved
