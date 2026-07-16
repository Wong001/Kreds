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
    peer_identity = "12" * 32              # a peer, not self -- see the
    node.store.add_peer("abc.onion:15001", peer_identity)  # self-skip guard
    node.store.add_peer("127.0.0.1:7999", peer_identity)   # in _gossip_round
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


def test_self_onion_row_drained_sibling_and_friend_preserved(tmp_path):
    # 0.3.14 outage residual: the node's own onion service got stored as a
    # peer row under a now-dead port; post-fix it dialed itself over Tor
    # every cycle. Host-keyed, NOT identity-keyed (review finding on the
    # first attempt at this fix): a paired sibling device (e.g. phone +
    # home node) SHARES one identity_pub -- pair_install deliberately
    # seeds the sibling's own address as a peer under that shared identity
    # so the two sync (home-node catch-up, see test_three_nodes.py).
    # Keying the drain on identity_pub would delete that legitimate
    # sibling row too. An onion host is unique per device, so only a row
    # pointing at OUR OWN onion host is actually a self-row.
    #
    # This test fails against an identity-keyed _drain_self_peers: it
    # would drop the sibling row along with the real stale self row.
    from hearth.node import HearthNode
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    n.store.set_meta("gossip_addr", "ownhost.onion:9997")     # current publish
    n.store.add_peer("ownhost.onion:1117", n.identity_pub)    # stale self row (dead port)
    n.store.add_peer("sibling.onion:9997", n.identity_pub)    # paired home node
    n.store.add_peer("friend.onion:9997", "ff" * 32)          # a real friend
    from hearth.runner import _drain_self_peers
    _drain_self_peers(n)
    addrs = [p["address"] for p in n.store.list_peers()]
    assert "ownhost.onion:1117" not in addrs        # stale self row drained
    assert "sibling.onion:9997" in addrs            # sibling preserved
    assert "friend.onion:9997" in addrs             # real friend kept


def test_gossip_round_skips_own_onion_dials_sibling_and_friend(tmp_path):
    # Belt-and-suspenders (sync.py _gossip_round): even if a row pointing
    # at our own onion host reappears (e.g. echoed back via a peer's HAVE
    # merge), the gossip loop's dial loop must skip it -- keyed on onion
    # HOST, not identity, for the same reason as the drain above: a paired
    # sibling device shares our identity_pub but runs a DIFFERENT onion,
    # and must still be dialed for home-node sync to work.
    #
    # This test fails against an identity-keyed self-skip guard: it would
    # also skip dialing the sibling row.
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    node.store.set_meta("gossip_addr", "ownhost.onion:9997")
    node.store.add_peer("ownhost.onion:1117", node.identity_pub)  # stale self row
    node.store.add_peer("sibling.onion:9997", node.identity_pub)  # paired home node
    node.store.add_peer("friend.onion:9997", "ff" * 32)           # a real friend
    svc = SyncService(node)
    dialed = []

    async def fake_sync_with(addr):
        dialed.append(addr)
        return False
    svc.sync_with = fake_sync_with

    asyncio.run(svc._gossip_round())

    assert "ownhost.onion:1117" not in dialed       # our own onion never dialed
    assert "sibling.onion:9997" in dialed           # sibling still dialed
    assert "friend.onion:9997" in dialed            # real friend still dialed
