"""Pre-friend friend-add handshake over Tor + auto-delivery (Task 2).

B pastes A's invite; B's node dials A directly over the existing gossip
listener (a `friend-add` frame, dispatched in _on_conn before the normal
hello/AUTH session) and delivers its response. A's ONLY authenticator for
that frame is node.finalize_invite -- nonce must match a live, non-expired
pending invite plus a valid signature. No friend-auth (is_known) applies to
this frame; that is by design (it's the pre-friend bootstrap channel), so
these tests specifically pin down that a bogus/expired nonce is refused and
adds nobody, and that the ordinary gossip/auth session is unaffected by the
_on_conn refactor (covered in the existing gossip/ceremony/dm/three-node
suites, run alongside this file per the task brief)."""
import asyncio
import time

import hearth.sync as sync_mod
from hearth import invitecodec
from hearth.node import HearthNode
from hearth.sync import SyncService
from hearth.transport import read_frame


async def _serve(node):
    svc = SyncService(node)
    port = await svc.start("127.0.0.1", 0)
    node.store.set_meta("gossip_addr", f"127.0.0.1:{port}")
    return svc, port


def test_auto_delivery_makes_both_friends(tmp_path):
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "A", "a-dev")
        b = HearthNode.create(tmp_path / "b", "B", "b-dev")
        svc_a, _ = await _serve(a)
        svc_b, _ = await _serve(b)
        try:
            invite = a.create_invite()                  # A shares one code
            result = await b.add_friend_via_invite(invite)   # B pastes it
            assert result["status"] == "connected"
            assert result["friend"]
            assert b.store.is_known(a.identity_pub)      # B added A
            assert a.store.is_known(b.identity_pub)      # A added B automatically
        finally:
            await svc_a.stop()
            await svc_b.stop()
    asyncio.run(scenario())


def test_offline_falls_back_to_manual(tmp_path):
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "A", "a-dev")
        b = HearthNode.create(tmp_path / "b", "B", "b-dev")
        a.store.set_meta("gossip_addr", "127.0.0.1:59999")   # nobody listening
        svc_b, _ = await _serve(b)
        try:
            result = await b.add_friend_via_invite(a.create_invite())
            assert result["status"] == "manual"
            assert "response" in result
            # the manual fallback path (today's flow) still completes the add:
            final = a.finalize_invite(result["response"])
            b.complete_invite(final)
            assert a.store.is_known(b.identity_pub) and b.store.is_known(a.identity_pub)
        finally:
            await svc_b.stop()
    asyncio.run(scenario())


def test_friend_add_frame_without_valid_nonce_refused(tmp_path):
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "A", "a-dev")
        b = HearthNode.create(tmp_path / "b", "B", "b-dev")
        svc_a, _ = await _serve(a)
        svc_b = SyncService(b)            # B's dialer (no listener needed)
        try:
            # A valid-looking response, but A has no matching pending invite ->
            # finalize_invite refuses -> None returned, nobody added.
            bogus = b.respond_to_invite(a.create_invite())
            a._pending_invites = {}                        # A no longer holds the nonce
            final = await svc_b.deliver_friend_add(
                a.store.get_meta("gossip_addr"), bogus)
            assert final is None
            assert a.store.is_known(b.identity_pub) is False
        finally:
            await svc_a.stop()
    asyncio.run(scenario())


def test_add_friend_via_invite_still_raises_on_bad_invite(tmp_path):
    """respond_to_invite's own validation (unchanged from Task 1) still
    gates add_friend_via_invite -- a garbage invite never reaches the
    friend-add dial at all."""
    async def scenario():
        b = HearthNode.create(tmp_path / "b", "B", "b-dev")
        try:
            await b.add_friend_via_invite("not json")
            assert False, "expected ValueError"
        except ValueError:
            pass
    asyncio.run(scenario())


def test_locked_node_refuses_inbound_friend_add_frame(tmp_path):
    """A locked A must never let an inbound friend-add frame add a friend
    to its store -- app-lock is a hard gate on ALL mutation, not just the
    HTTP surface. Mirrors _on_conn's revoked check: _handle_friend_add
    needs its own locked check because finalize_invite mutates (_add_
    friend) BEFORE it reaches the sign_raw call that would otherwise be
    the only thing noticing the lock."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "A", "a-dev")
        b = HearthNode.create(tmp_path / "b", "B", "b-dev")
        invite = a.create_invite()          # A issues the invite while unlocked
        a.enable_applock("1234", "pin")
        a.lock()
        svc_a, _ = await _serve(a)
        svc_b = SyncService(b)
        try:
            resp = b.respond_to_invite(invite)
            final = await svc_b.deliver_friend_add(
                a.store.get_meta("gossip_addr"), resp)
            assert final is None                          # refused, not finalized
            assert a.store.is_known(b.identity_pub) is False
            assert b.store.is_known(a.identity_pub) is False
        finally:
            await svc_a.stop()
    asyncio.run(scenario())


def test_finalize_invite_locked_mutates_nothing(tmp_path):
    """Defense-in-depth (belt-and-braces even though the socket-level
    locked guard above already prevents reaching this): finalize_invite
    itself must fail safe if it is ever called while locked -- signing
    happens BEFORE the pending invite is deleted and before _add_friend
    runs, so a RuntimeError from sign_raw leaves the invite still pending
    and no friend added (instead of today's order, which deletes the
    invite and adds the friend and only THEN raises on the sign)."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "A", "a-dev")
        b = HearthNode.create(tmp_path / "b", "B", "b-dev")
        invite = a.create_invite()
        resp = b.respond_to_invite(invite)
        a.enable_applock("1234", "pin")
        a.lock()
        try:
            a.finalize_invite(resp)
            assert False, "expected RuntimeError (locked)"
        except RuntimeError:
            pass
        assert a.store.is_known(b.identity_pub) is False
        # the invite is still pending -- nothing was consumed by the failed attempt
        assert resp
        _, resp_d = invitecodec.decode(resp)
        nonce = resp_d["nonce"]
        assert nonce in a._pending_invites
    asyncio.run(scenario())


def test_add_friend_via_invite_handles_malformed_final(tmp_path, monkeypatch):
    """If A's peer returns a bogus/garbage friend-final payload (protocol
    error, tampered relay, buggy peer), add_friend_via_invite must not
    raise -- it should fall back to the manual-copy-paste result (B still
    has its own response to hand over by hand) rather than blowing up the
    caller."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "A", "a-dev")
        b = HearthNode.create(tmp_path / "b", "B", "b-dev")
        svc_a, _ = await _serve(a)

        # Stand in for a peer/relay that answers with garbage instead of a
        # valid friend-final -- deliver_friend_add's real network path
        # already returns None on any non-friend-final reply; this drives
        # the "peer returned SOMETHING that looks final-shaped but fails
        # complete_invite" branch specifically.
        async def _bad_dial(address, response_json):
            return "not a valid friend-final payload"
        b._friend_dial = _bad_dial

        try:
            result = await b.add_friend_via_invite(a.create_invite())
            assert result["status"] == "manual"
            assert "response" in result
            assert a.store.is_known(b.identity_pub) is False
            assert b.store.is_known(a.identity_pub) is False
        finally:
            await svc_a.stop()
    asyncio.run(scenario())


def test_inbound_friend_add_rate_limited(tmp_path):
    """A stranger hammering the friend-add frame gets refused past the cap
    instead of burning unlimited finalize_invite attempts. A live pending
    invite must be present for this: Fix 1 below carves out the no-pending
    case to refuse WITHOUT spending rate-limit budget at all, so this test
    exercises the has-pending case the rate limiter still has to guard."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "A", "a-dev")
        svc_a, _ = await _serve(a)
        a.create_invite()                  # a live pending invite to protect
        b = HearthNode.create(tmp_path / "b", "B", "b-dev")
        svc_b = SyncService(b)
        try:
            addr = a.store.get_meta("gossip_addr")
            # Exhaust the cap with bogus payloads (finalize_invite refuses
            # each one anyway -- wrong nonce -- but they still consume
            # rate-limit budget because a live invite exists to protect).
            results = []
            for _ in range(25):
                r = await svc_b.deliver_friend_add(addr, "not json")
                results.append(r)
            assert all(r is None for r in results)
            assert not svc_a._friend_add_allowed()
        finally:
            await svc_a.stop()
    asyncio.run(scenario())


def test_friend_add_with_no_pending_invite_refused_without_spending_rate_limit(tmp_path):
    """Fix 1 (cheaper rate-limit): a node holding NO live pending invite at
    all must refuse a friend-add frame immediately WITHOUT spending any
    rate-limit budget. Otherwise an address-knowing attacker who never
    received a real invite could hammer this listener and exhaust the
    shared 20/60s inbound budget, suppressing a legitimate concurrent
    auto-connect from someone who actually holds a fresh invite."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "A", "a-dev")
        svc_a, _ = await _serve(a)
        assert not a._pending_invites          # no invite was ever created
        b = HearthNode.create(tmp_path / "b", "B", "b-dev")
        svc_b = SyncService(b)
        try:
            addr = a.store.get_meta("gossip_addr")
            for _ in range(25):
                r = await svc_b.deliver_friend_add(addr, "not json")
                assert r is None
            # None of those 25 attempts touched the rate-limit window --
            # the budget is still fully available for a real invitee.
            assert svc_a._friend_add_times == []
            assert svc_a._friend_add_allowed()
        finally:
            await svc_a.stop()
    asyncio.run(scenario())


def test_deliver_friend_add_times_out_when_peer_never_replies(tmp_path, monkeypatch):
    """Fix 2 (bounded handshake reads): A accepts the connection and reads
    B's frame but never replies (hung peer, crashed mid-handshake, hostile
    stall). deliver_friend_add must return None within the timeout instead
    of hanging forever -- and, with it, the awaited add_friend_via_invite
    -> /api/friend/add request. FRIEND_ADD_TIMEOUT is monkeypatched down so
    the test stays fast."""
    monkeypatch.setattr(sync_mod, "FRIEND_ADD_TIMEOUT", 0.5)

    async def scenario():
        release = asyncio.Event()          # holds the connection open on purpose

        async def _silent_handler(reader, writer):
            try:
                await read_frame(reader)    # drain B's frame...
            except Exception:
                pass
            await release.wait()            # ...and never reply
            writer.close()

        server = await asyncio.start_server(_silent_handler, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        b = HearthNode.create(tmp_path / "b", "B", "b-dev")
        svc_b = SyncService(b)
        try:
            start = time.monotonic()
            result = await svc_b.deliver_friend_add(
                f"127.0.0.1:{port}", "irrelevant")
            elapsed = time.monotonic() - start
            assert result is None
            assert elapsed < 5      # bounded by the 0.5s timeout, not hanging
        finally:
            release.set()
            server.close()
            await server.wait_closed()
    asyncio.run(scenario())
