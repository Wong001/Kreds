"""Easier friend-add, end to end (Task 4): two real loopback SyncService
nodes exercise the full auto-add path A `create_invite` -> B
`add_friend_via_invite` -> both mutual friends, exactly as Josh's real
2-machine Tor run will drive it. This is the first real payload the core
auto-update (swap-on-restart) ships, so it also pins down the two things a
regression here would silently break: (1) that normal gossip `sync_with`
between the newly-added friends still works after the `_on_conn`
first-frame refactor (Task 2) that dispatches the pre-friend `friend-add`
frame ahead of the ordinary hello/AUTH session, and (2) that an expired
invite is refused by A's own finalize_invite (the ONLY authenticator on
that frame) rather than silently adding anyone."""
import asyncio

from hearth.node import HearthNode
from hearth.sync import SyncService


async def _serve(node):
    svc = SyncService(node)
    port = await svc.start("127.0.0.1", 0)
    node.store.set_meta("gossip_addr", f"127.0.0.1:{port}")
    return svc, port


def test_easier_add_then_gossip_still_works(tmp_path):
    """A shares one code, B pastes it -- both become friends without B
    ever handing anything back to A -- and a plain gossip round between
    them afterward still carries content, proving the friend-add frame's
    _on_conn dispatch didn't disturb the ordinary hello/AUTH session
    path."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "A", "a-dev")
        b = HearthNode.create(tmp_path / "b", "B", "b-dev")
        svc_a, _ = await _serve(a)
        svc_b, port_b = await _serve(b)
        try:
            invite = a.create_invite()                      # A shares one code
            result = await b.add_friend_via_invite(invite)   # B pastes it, nothing sent back by hand
            assert result["status"] == "connected"
            assert result["friend"]
            assert b.store.is_known(a.identity_pub)          # B added A
            assert a.store.is_known(b.identity_pub)          # A added B automatically

            # Normal gossip still works post-add: a plain sync_with (the
            # ordinary hello/AUTH session, dispatched by _on_conn only
            # when the first frame ISN'T "friend-add") carries content
            # both ways over the exact same socket the friend-add frame
            # used moments earlier.
            addr_b = f"127.0.0.1:{port_b}"
            b.ensure_enckey()                       # B announces its enc key
            ok = await svc_a.sync_with(addr_b)       # round 1: A picks up B's enckey
            assert ok
            mid = a.compose_post("hello over gossip after auto-add")  # needs B's enckey to wrap for B
            ok = await svc_a.sync_with(addr_b)       # round 2: A's post reaches B
            assert ok
            texts = [p["text"] for p in b.feed()]
            assert "hello over gossip after auto-add" in texts
        finally:
            await svc_a.stop()
            await svc_b.stop()
    asyncio.run(scenario())


def test_expired_invite_auto_delivery_refused_falls_back_to_manual(tmp_path):
    """An expired invite's response, auto-delivered, must be refused by A
    (finalize_invite's own expiry check, its only authenticator for this
    pre-friend frame) -- B's add_friend_via_invite gets {"status":
    "manual"} back, and nobody is added on A's side."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "A", "a-dev")
        b = HearthNode.create(tmp_path / "b", "B", "b-dev")
        svc_a, _ = await _serve(a)
        svc_b, _ = await _serve(b)
        try:
            invite = a.create_invite(ttl_seconds=-1)   # already expired on arrival
            result = await b.add_friend_via_invite(invite)
            assert result["status"] == "manual"
            assert "response" in result
            assert a.store.is_known(b.identity_pub) is False   # nobody added on A
            assert b.store.is_known(a.identity_pub) is False
        finally:
            await svc_a.stop()
            await svc_b.stop()
    asyncio.run(scenario())
