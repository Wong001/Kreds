"""Phone/desktop pairing over Tor -- the pre-auth `hearth-pair-request`
wire frame (Task 2, spec 2026-07-22-android-first-load-pairing-design).

A second citizen of the SAME `_on_conn` peek-dispatch precedent
`test_friend_add_delivery.py` exercises for the `friend-add` frame
(sync.py:172-194) -- this file follows that harness's own shape (a real
loopback `SyncService`, `HearthNode.create` fixtures, raw socket I/O via
`hearth.transport`) but lives in its own file, mirroring Task 1's own
per-feature convention (`test_pairing_codes.py`, `test_pair_api.py`
alongside the pre-existing `test_invitecodec.py`) rather than folding an
unrelated ceremony into the friend-add file.

No Python pairing CLIENT exists yet (that's Task 4/6's Kotlin ceremony) --
so these tests build the wire frame directly and speak raw length-
prefixed frames (`hearth.transport.read_frame`/`write_frame`) against a
real listening `SyncService`, exactly the shape Task 4/6's phone client
will send."""
import asyncio
import json
import time

import httpx
import pytest

from hearth.api import build_app
from hearth.node import HearthNode
from hearth.pairingcodes import TTL_SECONDS
from hearth.sync import SyncService
from hearth.transport import read_frame, write_frame


async def _serve(node):
    svc = SyncService(node)
    port = await svc.start("127.0.0.1", 0)
    node.store.set_meta("gossip_addr", f"127.0.0.1:{port}")
    return svc, port


async def _send_pair_frame(port, frame, timeout=5.0):
    reader, writer = await asyncio.open_connection("127.0.0.1", port)
    try:
        await write_frame(writer, frame)
        return await asyncio.wait_for(read_frame(reader), timeout=timeout)
    finally:
        writer.close()
        try:
            await writer.wait_closed()
        except Exception:
            pass


async def _wait_for_pending(node, timeout=5.0):
    start = time.monotonic()
    while node.pending_pair is None:
        if time.monotonic() - start > timeout:
            raise TimeoutError("pending_pair never appeared")
        await asyncio.sleep(0.01)


async def _api_accept(app, device_pub, accept):
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://t") as c:
        r = await c.post("/api/pair/accept",
                         json={"device_pub": device_pub, "accept": accept})
        assert r.status_code == 200


# -- happy path ---------------------------------------------------------

def test_happy_path_package_delivered_over_held_connection(tmp_path):
    """Mint a code, send the wire frame, the human accepts (via the REAL
    /api/pair/accept route) while the connection is held open, and the
    client receives the untouched accept_pairing package back over that
    SAME connection."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "A", "a-dev")
        svc_a, port = await _serve(a)
        app = build_app(a)
        try:
            code = a.pairing.mint(time.time())
            req = json.loads(HearthNode.pair_request(tmp_path / "phone", "phone-1"))
            frame = {**req, "code": code}

            async def accept_when_pending():
                await _wait_for_pending(a)
                assert a.pending_pair["device_pub"] == req["device_pub"]
                await _api_accept(app, req["device_pub"], True)

            reply, _ = await asyncio.gather(
                _send_pair_frame(port, frame), accept_when_pending())

            assert reply["t"] == "hearth-pair-package"
            assert reply["identity_priv"]
            assert reply["cert"]["device_pub"] == req["device_pub"]
            assert a.pending_pair is None
            assert req["device_pub"] in a.store.load_views(a.identity_pub)
        finally:
            await svc_a.stop()
    asyncio.run(scenario())


def test_deny_pair_denied_device_not_enrolled(tmp_path):
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "A", "a-dev")
        svc_a, port = await _serve(a)
        app = build_app(a)
        try:
            code = a.pairing.mint(time.time())
            req = json.loads(HearthNode.pair_request(tmp_path / "phone", "phone-1"))
            frame = {**req, "code": code}

            async def deny_when_pending():
                await _wait_for_pending(a)
                await _api_accept(app, req["device_pub"], False)

            reply, _ = await asyncio.gather(
                _send_pair_frame(port, frame), deny_when_pending())

            assert reply == {"t": "pair-denied"}
            assert a.pending_pair is None
            assert req["device_pub"] not in a.store.load_views(a.identity_pub)
        finally:
            await svc_a.stop()
    asyncio.run(scenario())


# -- bad / reused code ----------------------------------------------------

def test_wrong_code_pair_expired_nothing_pending(tmp_path):
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "A", "a-dev")
        svc_a, port = await _serve(a)
        try:
            a.pairing.mint(time.time())         # a real code is live...
            req = json.loads(HearthNode.pair_request(tmp_path / "phone", "phone-1"))
            frame = {**req, "code": "TOTALLYWRONG"}   # ...but this isn't it
            reply = await _send_pair_frame(port, frame)
            assert reply == {"t": "pair-expired"}
            assert a.pending_pair is None
        finally:
            await svc_a.stop()
    asyncio.run(scenario())


def test_reused_code_after_consume_pair_expired(tmp_path):
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "A", "a-dev")
        svc_a, port = await _serve(a)
        app = build_app(a)
        try:
            code = a.pairing.mint(time.time())
            req1 = json.loads(HearthNode.pair_request(tmp_path / "phone1", "phone-1"))
            frame1 = {**req1, "code": code}

            async def deny_when_pending():
                await _wait_for_pending(a)
                await _api_accept(app, req1["device_pub"], False)

            reply1, _ = await asyncio.gather(
                _send_pair_frame(port, frame1), deny_when_pending())
            assert reply1 == {"t": "pair-denied"}

            # Same code again (a replayed frame, or a retried client that
            # didn't realize it already succeeded/failed once) -- already
            # consumed, so it must fail exactly like a wrong code.
            req2 = json.loads(HearthNode.pair_request(tmp_path / "phone2", "phone-2"))
            frame2 = {**req2, "code": code}
            reply2 = await _send_pair_frame(port, frame2)
            assert reply2 == {"t": "pair-expired"}
            assert a.pending_pair is None
        finally:
            await svc_a.stop()
    asyncio.run(scenario())


# -- single active ceremony ------------------------------------------------

def test_second_concurrent_pair_frame_expired_single_ceremony(tmp_path):
    """A second, otherwise-VALID pair frame arriving while an earlier
    request is still parked (awaiting the human) must not clobber it --
    single active ceremony. The first request stays live and resolvable
    normally afterward."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "A", "a-dev")
        svc_a, port = await _serve(a)
        try:
            code1 = a.pairing.mint(time.time())
            req1 = json.loads(HearthNode.pair_request(tmp_path / "p1", "phone-1"))
            frame1 = {**req1, "code": code1}

            first_task = asyncio.create_task(
                _send_pair_frame(port, frame1, timeout=10.0))
            await _wait_for_pending(a)

            code2 = a.pairing.mint(time.time())     # fresh, genuinely valid
            req2 = json.loads(HearthNode.pair_request(tmp_path / "p2", "phone-2"))
            frame2 = {**req2, "code": code2}
            reply2 = await _send_pair_frame(port, frame2)
            assert reply2 == {"t": "pair-expired"}

            # The first ceremony is untouched -- still exactly what was
            # parked, still resolvable.
            assert a.pending_pair is not None
            assert a.pending_pair["device_pub"] == req1["device_pub"]
            a.pending_pair["verdict"] = False
            a.pending_pair_event.set()
            reply1 = await first_task
            assert reply1 == {"t": "pair-denied"}
            assert a.pending_pair is None

            # code2 was rejected on the "already pending" check BEFORE
            # verify_and_consume ever ran on it -- it must still be live
            # and redeemable now that the first ceremony has resolved,
            # not silently burned by the rejected attempt.
            assert a.pairing.verify_and_consume(code2, time.time()) is True
        finally:
            await svc_a.stop()
    asyncio.run(scenario())


# -- timeout ---------------------------------------------------------------

def test_timeout_pair_expired(tmp_path):
    """No accept/deny ever arrives -- the held connection is bounded by
    the code's OWN remaining TTL, not an unbounded wait. Minted with an
    almost-elapsed `now` so only a sliver of TTL remains, short-circuiting
    the wait without monkeypatching any internal constant."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "A", "a-dev")
        svc_a, port = await _serve(a)
        try:
            code = a.pairing.mint(time.time() - (TTL_SECONDS - 1.0))  # ~1s left
            req = json.loads(HearthNode.pair_request(tmp_path / "phone", "phone-1"))
            frame = {**req, "code": code}
            start = time.monotonic()
            reply = await _send_pair_frame(port, frame, timeout=8.0)
            elapsed = time.monotonic() - start
            assert reply == {"t": "pair-expired"}
            assert elapsed < 5.0            # bounded by the ~1s remaining TTL
            assert a.pending_pair is None
        finally:
            await svc_a.stop()
    asyncio.run(scenario())


# -- rate limit --------------------------------------------------------------

def test_pair_request_rate_limited(tmp_path):
    """Mirrors test_friend_add_delivery.py's
    test_inbound_friend_add_rate_limited -- its OWN budget (not friend-
    add's), same 20/60s cap. A live code must be minted for this: with no
    code active at all, the cheap pre-check below refuses without ever
    touching the rate limiter (see the next test) -- this one exercises
    the has-a-live-code case the rate limiter still has to guard, wrong-
    guessing against it 25 times."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "A", "a-dev")
        svc_a, port = await _serve(a)
        try:
            a.pairing.mint(time.time())     # a live code worth guessing at
            for _ in range(25):
                reply = await _send_pair_frame(
                    port, {"t": "hearth-pair-request", "code": "wrongguess"})
                assert reply == {"t": "pair-expired"}
            assert not svc_a._pair_request_allowed()
            # the friend-add budget is untouched by the pair flood
            assert svc_a._friend_add_allowed()
        finally:
            await svc_a.stop()
    asyncio.run(scenario())


def test_pair_request_with_no_live_code_refused_without_spending_rate_limit(tmp_path):
    """Fix (mirrors _handle_friend_add's Fix 1, test_friend_add_
    delivery.py's test_friend_add_with_no_pending_invite_refused_without_
    spending_rate_limit): with NO code minted at all, a flood must be
    refused immediately without spending any rate-limit budget -- an
    onion-address-knowing attacker who never saw a real code must not be
    able to exhaust the shared 20/60s budget and lock out a legitimate
    concurrent pairing attempt."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "A", "a-dev")
        svc_a, port = await _serve(a)
        try:
            assert a.pairing._hash is None      # no code was ever minted
            for _ in range(25):
                reply = await _send_pair_frame(
                    port, {"t": "hearth-pair-request", "code": "nope"})
                assert reply == {"t": "pair-expired"}
            assert svc_a._pair_request_times == []
            assert svc_a._pair_request_allowed()
        finally:
            await svc_a.stop()
    asyncio.run(scenario())


def test_malformed_frame_with_valid_code_pair_expired(tmp_path):
    """A genuinely live, correct code but a malformed device_pub/
    device_name (buggy or hostile client) -- refused, and the code is
    consumed in the process (an accepted, documented trade-off: burning a
    code on a malformed request grants the attacker nothing)."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "A", "a-dev")
        svc_a, port = await _serve(a)
        try:
            code = a.pairing.mint(time.time())
            frame = {"t": "hearth-pair-request", "protocol": "hearth/v0.2",
                     "device_pub": "not-hex", "device_name": "phone-1",
                     "code": code}
            reply = await _send_pair_frame(port, frame)
            assert reply == {"t": "pair-expired"}
            assert a.pending_pair is None
            # the code is burned even though the request was malformed
            assert a.pairing.verify_and_consume(code, time.time()) is False
        finally:
            await svc_a.stop()
    asyncio.run(scenario())


# -- scoping invariant -------------------------------------------------------

def test_stranger_non_pair_frame_still_refused(tmp_path):
    """The _on_conn peek dispatches ONLY `friend-add` and
    `hearth-pair-request` pre-auth; anything else -- a normal hello
    attempt from an unfriended stranger, or plain garbage -- falls
    through to the ordinary hello/AUTH session exactly as before Task 2,
    unaffected by the new pair branch. Proven directly at the wire level:
    the responder still writes its OWN hello unconditionally (today's
    behavior), then the session raises on the peer's bad hello and the
    connection drops with nothing further -- and node.pending_pair is
    never touched."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "A", "a-dev")
        svc_a, port = await _serve(a)
        try:
            reader, writer = await asyncio.open_connection("127.0.0.1", port)
            try:
                await write_frame(writer, {"t": "gibberish", "not": "real"})
                reply = await asyncio.wait_for(read_frame(reader), timeout=5.0)
                assert reply["t"] == "hello"     # unchanged existing behavior
                with pytest.raises((asyncio.IncompleteReadError, ConnectionError,
                                    OSError)):
                    await asyncio.wait_for(read_frame(reader), timeout=5.0)
            finally:
                writer.close()
                try:
                    await writer.wait_closed()
                except Exception:
                    pass
            assert a.pending_pair is None
        finally:
            await svc_a.stop()
    asyncio.run(scenario())
