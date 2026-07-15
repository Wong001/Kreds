"""One gossip session: hello/auth -> revocations -> have -> messages ->
blobs. Revocations always travel before content (v0.3 binding finding).
Strangers are refused at AUTH: they never receive anything - the
structural half of the thesis, enforced at the socket layer."""
from __future__ import annotations

import asyncio
import base64
import os
import time

from .identity import (
    DefriendNotice, PROTOCOL, EnrollmentCert, RevocationCert, SignedMessage,
    canonical, _sig_ok,
)
from .messages import MAX_BLOB_BYTES, ONION_SYNC_INTERVAL, blob_hash
from .transport import MAX_FRAME, TcpTransport, read_frame, write_frame

# Bound on the pre-auth friend-add handshake reads (whole-branch review,
# Fix 2): _on_conn's shared first-frame read and deliver_friend_add's reply
# read have no other authenticator gating them (friend-add is deliberately
# pre-friend-auth), so an open-but-silent peer would otherwise park a
# coroutine forever and hang the awaited /api/friend/add request. 30s is
# generous enough that a normal LAN/clearnet gossip hello (which shares the
# same first-frame read in _on_conn) is never at risk, while staying
# Tor-tolerant (see TorTransport's 60s onion-dial allowance for comparison).
FRIEND_ADD_TIMEOUT = 30

# Bound on one round's BLOBS-phase give dict (whole-branch review, Finding
# 1): the give dict used to collect EVERY blob a peer wants into a single
# JSON frame, so an ordinary want-set (e.g. one big GIF + one photo posted
# while a peer was offline, now normal at the 10 MB blob cap) could exceed
# MAX_FRAME (transport.py, 16 MB) once base64-inflated (4/3x) -- write_frame
# would raise "frame too large", killing the session at the blob phase, and
# since store.missing_blobs() is stable the SAME oversized frame would be
# rebuilt every round: blob sync with that peer wedged permanently. Budget
# the give dict to this many encoded bytes per round instead; whatever
# doesn't fit is simply left for the next sync round -- store.missing_blobs()
# is recomputed fresh every session, and the receive loop below only ever
# processes whatever keys ARE present in the peer's reply, so a partial give
# is tolerated for free (nothing asserts the reply is complete; unfulfilled
# hashes just stay "missing" and get re-requested next round).
#
# A single MAX_BLOB_BYTES blob (10 MB) can never overflow this budget on its
# own: base64 inflates it to ~13.3 MB, comfortably under both MAX_FRAME and
# this budget -- so "one blob alone doesn't fit" is unreachable and is not
# given any special-case handling here.
BLOB_GIVE_BUDGET = MAX_FRAME - 1024 * 1024

# Bound on peer dial timeout: plain TCP needs a short backstop (LAN
# responsiveness expectation) while clearnet/cellular can be slow but
# must not stall the whole gossip round. open_connection has no builtin
# timeout, so one dead peer would hang indefinitely; this bounds it.
TCP_DIAL_TIMEOUT = 20.0

# Bound on onion peer dial timeout: TorTransport's own 60s budget for the
# full SOCKS negotiation + connection. This backstop sits above that so
# the transport's error (if it occurs) surfaces first, and we fall back to
# offline-peer handling.
ONION_DIAL_TIMEOUT = 75.0


def _auth_body(nonce_hex: str) -> bytes:
    # Domain-separated: never sign raw peer-supplied bytes.
    return canonical({"type": "gossip-auth", "protocol": PROTOCOL,
                      "nonce": nonce_hex})


def _is_onion(address: str) -> bool:
    host = address.rsplit(":", 1)[0]
    return host.endswith(".onion")


class PeerRefused(Exception):
    """Raised when the AUTHENTICATED peer itself wrote {"t": "refused"}
    during this session (as opposed to a generic I/O failure, or the
    local side being the one that refused). Carries the peer_identity
    proven by the AUTH device-key challenge earlier in this same
    session, so a caller (Node.deliver_defriends, via _sync_session) can
    tell "the exact node I dialed just told me it doesn't know me
    anymore" apart from "some unrelated failure happened" -- binding the
    former to the record's target is what makes refusal-cleanup
    (whole-branch review, Fix 3) safe: a refusal from a DIFFERENT authenticated
    identity than the record's target must never be treated the same
    way."""
    def __init__(self, peer_identity):
        super().__init__("refused by peer")
        self.peer_identity = peer_identity


def _merge_peer_address(store, ident, addr):
    """Onion-preferred peer store: an onion address is always kept; a
    non-onion address is stored only if we hold no onion address for that
    identity yet. Never let a plain-TCP address shadow a known onion one --
    that would route a Tor peer's gossip over the clearnet."""
    if _is_onion(addr):
        # Evict any non-onion rows for this identity: otherwise the
        # gossip loop keeps dialing the stale clearnet address every
        # round, and the have-frame (built from list_peers()) would
        # keep propagating a non-onion address for a peer with a known
        # onion -- exactly what this guardrail exists to prevent.
        for pe in store.list_peers():
            if pe.get("identity_pub") == ident and not _is_onion(pe["address"]):
                store.remove_peer(pe["address"])
        store.add_peer(addr, ident)
        return
    known = [pe["address"] for pe in store.list_peers()
             if pe.get("identity_pub") == ident]
    if not any(_is_onion(a) for a in known):
        store.add_peer(addr, ident)


class SyncService:
    def __init__(self, node, transport=None):
        self.node = node
        self.transport = transport or TcpTransport()
        self._server = None
        self._last_onion_sync = {}          # address -> monotonic ts
        # Node.deliver_defriends reuses this exact outbound-session
        # dialer (the same one sync_with wraps for the gossip loop
        # below) instead of hand-rolling a second connection path. It
        # needs the AUTHENTICATED peer_identity (whole-branch review,
        # Fix 1 -- never trust an ack without knowing who actually
        # proved it) and the DEFRIENDS-phase applied_by_peer list (to
        # tell a real application-level ack apart from a session that
        # merely completed without raising), so it gets the
        # (bool, peer_identity, list) tuple straight from _sync_session
        # rather than sync_with's bool-only wrapper.
        node._dial = self._sync_session
        # Wire the pre-friend friend-add auto-delivery dialer (Node.
        # add_friend_via_invite uses this the same way deliver_defriends
        # uses node._dial above): B's side of the handshake, dialing A
        # directly over this same transport instead of requiring manual
        # copy-paste of the response.
        node._friend_dial = self.deliver_friend_add
        self._friend_add_times = []          # inbound rate-limit window

    async def start(self, host: str, port: int) -> int:
        self._server = await self.transport.serve(host, port, self._on_conn)
        return self._server.sockets[0].getsockname()[1]

    async def stop(self):
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()
            self._server = None

    async def _on_conn(self, reader, writer):
        if self.node.revoked:
            writer.close()
            return
        try:
            # Peek the first frame to dispatch: a `friend-add` frame is the
            # pre-friend handshake (authenticated solely by node.
            # finalize_invite, never by the friend-auth below); anything
            # else is a normal gossip/AUTH session, and peer_hello=first
            # hands _session the frame already read here so it doesn't
            # block trying to read it a second time.
            #
            # Bounded (whole-branch review, Fix 2): this read is shared by
            # BOTH the friend-add path and the normal gossip/AUTH session
            # (peer_hello=first below), and neither path authenticates a
            # peer before this point -- a connection that opens and never
            # sends would otherwise park this coroutine forever. 30s is
            # generous so a real hello (gossip or friend-add) always
            # completes well inside it; asyncio.TimeoutError falls through
            # to the except Exception below, which already drops the
            # connection via the existing finally.
            first = await asyncio.wait_for(read_frame(reader),
                                           timeout=FRIEND_ADD_TIMEOUT)
            if first.get("t") == "friend-add":
                await self._handle_friend_add(first, writer)
            else:
                await self._session(reader, writer, initiator=False,
                                    peer_hello=first)
        except Exception:
            pass                        # malformed peer: drop connection
        finally:
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass

    async def sync_with(self, address: str) -> bool:
        ok, _, _ = await self._sync_session(address)
        return ok

    async def _sync_session(self, address: str):
        """Same outbound session sync_with runs, but also returns the
        AUTHENTICATED peer_identity (the identity actually proven by the
        AUTH device-key challenge this session, regardless of what
        address we dialed or what any frame *claims*) and the DEFRIENDS-
        phase applied_by_peer list: the author_identity values the peer
        told us (in its own frame, during the phase) it actually applied
        this round. sync_with() stays bool-only -- every other caller
        (gossip loop, tests) only ever needs pass/fail; only Node.
        deliver_defriends needs the peer_identity + ack detail, via
        node._dial (wired to this method in __init__ above), to bind an
        outbox record's disposition to the peer it actually talked to
        rather than to whichever address happened to be on file
        (whole-branch review, Fix 1: a poisoned peers-table row must not
        let a different node's session count as the target's)."""
        if self.node.revoked:
            return False, None, []
        try:
            timeout = ONION_DIAL_TIMEOUT if _is_onion(address) else TCP_DIAL_TIMEOUT
            reader, writer = await asyncio.wait_for(
                self.transport.connect(address), timeout=timeout)
        except (OSError, asyncio.TimeoutError):
            return False, None, []      # peer offline/unreachable: next round
        try:
            peer_identity, applied = await self._session(
                reader, writer, initiator=True)
            return True, peer_identity, (applied or [])
        except PeerRefused as e:
            return False, e.peer_identity, []
        except Exception:
            return False, None, []
        finally:
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass

    async def _gossip_round(self, interval: float = 3.0, now=None):
        now = now or time.monotonic
        self.node.maintain_enckey()
        for peer in self.node.store.list_peers():
            addr = peer["address"]
            if _is_onion(addr):
                t = now()
                last = self._last_onion_sync.get(addr, 0.0)
                if t - last < ONION_SYNC_INTERVAL:
                    continue
                self._last_onion_sync[addr] = t
            await self.sync_with(addr)
        await self.node.deliver_defriends()
        if self.node.store.sweep_expired():
            self.node.notify()
        self.node.store.prune_superseded_enckeys()
        self.node.cache_message_keys()

    async def gossip_loop(self, interval: float = 3.0, now=None):
        while True:
            try:
                await self._gossip_round(interval=interval, now=now)
            except Exception:
                pass                    # never let one bad round kill gossip
            # Auto-lock tick brackets ONLY the sleep: baseline before,
            # check after, so the gap can't include the round's dial time
            # (the 0.3.11 misfire: one offline peer > 33s round -> lock).
            self.node.stamp_autolock_tick()
            await asyncio.sleep(interval)
            self.node.maybe_autolock(interval)

    # -- pre-friend friend-add handshake (auto-delivery over Tor) ---------
    #
    # A separate frame type from the friend-auth `_session` above: this is
    # the pre-friend bootstrap channel (B has A's invite but is not yet a
    # known friend of A's), so it is deliberately NOT gated by is_known.
    # The ONLY authenticator is node.finalize_invite itself -- it requires
    # the frame's nonce to match a live, non-expired pending invite AND a
    # valid signature over it (Task 1's expiry-aware finalize_invite). A
    # stranger with a random/expired/forged nonce gets {"t": "refused"}
    # and nothing is added; see _handle_friend_add below. Do not add any
    # other admission check here -- that would either bypass finalize_
    # invite's own validation or reintroduce a friend-auth requirement
    # that defeats the entire point of a PRE-friend channel.

    def _friend_add_allowed(self) -> bool:
        """Inbound rate-limit: cap finalize_invite attempts a stranger can
        throw at this listener to ~20/min, so the friend-add frame (which
        bypasses friend-auth by design) can't be used to spam-guess/DoS
        finalize_invite."""
        now = time.time()
        self._friend_add_times = [t for t in self._friend_add_times if now - t < 60]
        if len(self._friend_add_times) >= 20:     # cap inbound handshake attempts / min
            return False
        self._friend_add_times.append(now)
        return True

    async def _handle_friend_add(self, frame, writer):
        """A's side: a pre-friend delivered B's response. finalize_invite is
        the ONLY authenticator (nonce must match a live pending invite +
        valid sig) -- a stranger with no valid nonce is refused.

        App-lock guard (task review CRITICAL): mirrors _on_conn's revoked
        check above. Without this, a locked A would still run finalize_
        invite, which mutates the store (_add_friend) BEFORE it reaches
        the sign_raw call that raises on a locked device -- silently
        bypassing the app-lock gate that every HTTP friend endpoint
        respects (423 while locked) and leaving a broken asymmetric state
        (A added B, nonce consumed, A never sent the final). Checked
        first, before the rate limiter, so a locked node never spends
        rate-limit budget on attempts it was always going to refuse."""
        if self.node.locked:
            await write_frame(writer, {"t": "refused"})
            return
        if not self.node._pending_invites:
            # Cheaper rate-limit (whole-branch review, Fix 1): with no live
            # pending invite at all, finalize_invite has no possible
            # matching nonce to protect -- there is no finalize work to
            # do, so refuse immediately WITHOUT touching the rate limiter.
            # Otherwise an address-knowing attacker who never even
            # received an invite could throw friend-add frames at this
            # listener and exhaust the shared 20/60s inbound budget,
            # suppressing a legitimate concurrent auto-connect from a real
            # invitee. Checked before the rate limiter for exactly that
            # reason; the rate limiter below still guards the case where a
            # live invite genuinely exists and is worth protecting.
            await write_frame(writer, {"t": "refused"})
            return
        if not self._friend_add_allowed():
            await write_frame(writer, {"t": "refused"})
            return
        try:
            final = self.node.finalize_invite(frame.get("payload", ""))
        except (ValueError, KeyError, TypeError, RuntimeError):
            # RuntimeError: sign_raw's "locked" -- a locked-race between
            # the check above and finalize_invite running (defense in
            # depth; finalize_invite itself now signs before mutating, so
            # even this race leaves the store untouched -- see node.py).
            await write_frame(writer, {"t": "refused"})
            return
        await write_frame(writer, {"t": "friend-final", "payload": final})

    async def deliver_friend_add(self, address: str, response_json: str):
        """B's side: dial A, send the response, return A's final (or None)
        if A is unreachable / refuses / replies with anything else. Node.
        add_friend_via_invite falls back to manual copy-paste on None."""
        try:
            reader, writer = await self.transport.connect(address)
        except OSError:
            return None
        try:
            await write_frame(writer, {"t": "friend-add", "payload": response_json})
            # Bounded (whole-branch review, Fix 2): A may accept the
            # connection and read our frame but never reply (hung peer,
            # crashed mid-handshake, hostile stall) -- without a timeout
            # this read hangs forever and, with it, the awaited
            # add_friend_via_invite -> /api/friend/add request. Treat a
            # timeout the same as "unreachable": return None so the
            # caller falls back to the manual copy-paste path.
            reply = await asyncio.wait_for(read_frame(reader),
                                           timeout=FRIEND_ADD_TIMEOUT)
            return reply.get("payload") if reply.get("t") == "friend-final" else None
        except Exception:
            return None
        finally:
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass

    async def _swap(self, reader, writer, initiator: bool,
                    frame: dict) -> dict:
        """Send our frame and receive the peer's, initiator first."""
        if initiator:
            await write_frame(writer, frame)
            return await read_frame(reader)
        reply = await read_frame(reader)
        await write_frame(writer, frame)
        return reply

    async def _session(self, reader, writer, initiator: bool,
                       peer_hello=None):
        node, store = self.node, self.node.store
        changed = False
        # Populated by the DEFRIENDS phase below: the author_identity
        # values the peer told us it actually applied this round.
        # Meaningful only when we're the initiator (see that phase's
        # comment) -- _sync_session hands this back to Node.
        # deliver_defriends as the real application-level ack.
        applied_by_peer = []

        # -- HELLO --
        my_nonce = os.urandom(16).hex()
        hello = {"t": "hello", "cert": node.device.cert.to_dict(),
                 "nonce": my_nonce}
        if peer_hello is None:
            peer_hello = await self._swap(reader, writer, initiator, hello)
        else:
            # _on_conn already read the peer's first frame (to dispatch
            # friend-add vs. a normal session) -- just send ours. Only
            # reachable with initiator=False (_on_conn is always the
            # responder side), so this mirrors _swap's own responder
            # branch (read-then-write) minus the read it already did.
            await write_frame(writer, hello)
        if peer_hello.get("t") != "hello":
            raise ValueError("bad hello")
        peer_cert = EnrollmentCert.from_dict(peer_hello["cert"])
        if not peer_cert.verify():
            raise ValueError("bad peer cert")

        # -- AUTH (mutual device-key proof) --
        auth = {"t": "auth",
                "sig": node.device.sign_raw(_auth_body(peer_hello["nonce"]))}
        peer_auth = await self._swap(reader, writer, initiator, auth)
        if (peer_auth.get("t") != "auth"
                or not _sig_ok(peer_cert.device_pub, peer_auth["sig"],
                               _auth_body(my_nonce))):
            raise ValueError("peer failed device-key proof")
        peer_identity = peer_cert.identity_pub
        # A pending outbox notice earns a narrow admission even though
        # `is_known` no longer holds: this is exactly the peer we (or
        # they) unfriended and need to hand a direct-only defriend notice
        # to. The session is cut short right after DEFRIENDS below --
        # they still get nothing else (no HAVE/MESSAGES/BLOBS).
        # Only a NON-expired outbox notice earns this admission
        # (whole-branch review, Fix 8): a stale record that deliver_
        # defriends simply hasn't gotten around to pruning yet must not
        # keep admitting a stranger indefinitely past the 14-day window.
        outbox_for_peer = [r["notice"] for r in store.list_outbox()
                          if r["target_identity"] == peer_identity
                          and r["expires_at"] > time.time()]
        if not store.is_known(peer_identity) and not outbox_for_peer:
            await write_frame(writer, {"t": "refused"})
            raise ValueError("unknown identity refused")
        # Own-identity peers are exempt: this is the very channel a device
        # learns of its own revocation over (self-logout below), so a
        # sibling device must never be refused here for being the target
        # of a revocation it doesn't yet know about.
        if peer_identity != node.identity_pub:
            peer_view = store.load_views(peer_identity).get(peer_cert.device_pub)
            if peer_view is not None and peer_view.revocation is not None:
                await write_frame(writer, {"t": "refused"})
                raise ValueError("revoked device refused")

        # -- REVOCATIONS (always before content) --
        # A peer admitted ONLY via the pending-outbox carve-out above (we
        # do not otherwise know them -- this is exactly the person we're
        # unfriending or being unfriended by) must not be handed our full
        # revocation set: that discloses the revocation state of every
        # OTHER identity we know to someone who is no longer entitled to
        # any of our metadata (whole-branch review, Fix 2). A normal known-friend
        # session is unaffected. We still ingest THEIRS below regardless
        # -- revocation certs are self-authenticating and separately
        # gated by is_known(rev.identity_pub) at ingest.
        outbox_only = not store.is_known(peer_identity)
        revs = {"t": "revocations",
                "revs": ([] if outbox_only else
                         [r.to_dict() for r in store.list_revocations()])}
        peer_revs = await self._swap(reader, writer, initiator, revs)
        if peer_revs.get("t") == "refused":
            # The AUTHENTICATED peer itself refused us -- see PeerRefused's
            # docstring for why this must carry peer_identity rather than
            # collapsing into a generic failure.
            raise PeerRefused(peer_identity)
        self_revoked = False
        for rd in peer_revs.get("revs", []):
            rev = RevocationCert.from_dict(rd)
            if store.is_known(rev.identity_pub):
                res = store.ingest_revocation(rev)
                if res.accepted and res.retro_dropped:
                    changed = True
                if rev.device_pub == self.node.device.device_pub:
                    self_revoked = True

        # -- DEFRIENDS (targeted, direct-only removal notices) --
        # Only notices whose target IS this peer are ever sent here -- a
        # notice never rides along on a session with an unrelated third
        # party, which is what keeps delivery direct-only instead of
        # gossiped/broadcast.
        #
        # This phase is NOT a plain _swap: the recipient must apply an
        # incoming notice and report which author_identity values it
        # actually applied in the SAME frame it sends back, so the
        # sender can tell a real application-level ack apart from a
        # session that merely completed without raising (a tampered
        # notice that fails notice.verify(), or any other decline, must
        # NOT look like delivery -- see Node.deliver_defriends). That
        # needs apply-before-write on the applying side:
        #   - responder: read the peer's frame, apply what targets me,
        #     THEN write my own frame (my notices + my applied list).
        #   - initiator: write my frame first (nothing to apply yet),
        #     THEN read the peer's frame, apply what targets me from
        #     it, and record their applied list for MY sent notices.
        # That is exactly _swap's own I/O order per role -- the apply
        # step is just folded between the responder's read and write --
        # so neither side ever blocks on a frame the other was never
        # going to send.
        my_defriends = {"t": "defriends", "notices": outbox_for_peer}
        if initiator:
            await write_frame(writer, my_defriends)
            peer_df = await read_frame(reader)
            applied_by_peer = peer_df.get("applied", [])
            for nd in peer_df.get("notices", []):
                notice = DefriendNotice.from_dict(nd)
                if self.node.apply_defriend_notice(notice):
                    changed = True
        else:
            peer_df = await read_frame(reader)
            applied_here = []
            for nd in peer_df.get("notices", []):
                notice = DefriendNotice.from_dict(nd)
                if self.node.apply_defriend_notice(notice):
                    changed = True
                    # Belt-and-braces (whole-branch review, Fix 1): only ack a
                    # notice whose author_identity is the peer we just
                    # authenticated in THIS session. A notice's signature
                    # already makes it self-authenticating regardless of
                    # who relayed it, but the ack is meaningful only to
                    # its true author -- never credit it to whoever
                    # happened to carry it over this connection.
                    if notice.author_identity == peer_identity:
                        applied_here.append(notice.author_identity)
            await write_frame(writer, {**my_defriends, "applied": applied_here})

        # Both ends of a revocation must stop at the exact same protocol
        # point, or one side blocks reading a frame the other never sends.
        # self_revoked (I was just told I'm revoked) and the peer-revoked
        # check below (I already know the device I'm talking to is revoked)
        # are the two local views of the SAME fact within a sibling pair --
        # and because REVOCATIONS is a full mutual exchange, they surface
        # in lockstep during this same round. So neither side proceeds to
        # HAVE/MESSAGES/BLOBS once either view fires; the peer has already
        # received our revocations (sent above), which is all it's owed.
        if self_revoked:
            if changed:
                self.node.notify()
            self.node.enter_revoked_state()
            raise ValueError("this device was revoked; logging out")

        # A sibling we know to be revoked has now received our revocations
        # (REVOCATIONS phase above). Do not serve it any content or
        # metadata: end the session here.
        peer_dev_view = store.load_views(peer_identity).get(
            peer_cert.device_pub)
        if peer_dev_view is not None and peer_dev_view.revocation is not None:
            if changed:
                self.node.notify()
            return peer_identity, applied_by_peer

        # Either we just delivered our own notice to a peer we'd already
        # forgotten (the AUTH-phase admission above), or we just applied
        # a notice that made us forget THEM (apply_defriend_notice drops
        # the author). Either way, once `peer_identity` is no longer
        # known the session ends here -- same cutoff shape as the
        # revoked-sibling case above, and for the same reason: a former
        # friend gets the notice exchange and nothing else.
        if not store.is_known(peer_identity):
            if changed:
                self.node.notify()
            return peer_identity, applied_by_peer

        # -- HAVE (summaries, known identities, peer addresses) --
        have = {"t": "have", "summary": store.all_summaries(),
                "known": store.known_identities(),
                "peers": store.list_peers(),
                "addr": store.get_meta("gossip_addr")}
        peer_have = await self._swap(reader, writer, initiator, have)
        peer_known = set(peer_have.get("known", []))

        # Own-device trust: my other devices replicate my friend list.
        if peer_identity == node.identity_pub:
            for ident in peer_known:
                if not store.is_known(ident):
                    store.add_identity(ident)
                    changed = True
        my_addr = store.get_meta("gossip_addr")
        if peer_have.get("addr") and peer_have["addr"] != my_addr:
            _merge_peer_address(store, peer_identity, peer_have["addr"])
        for p in peer_have.get("peers", []):
            ident, addr = p.get("identity_pub"), p.get("address")
            if not (ident and store.is_known(ident) and addr != my_addr):
                continue
            _merge_peer_address(store, ident, addr)

        # -- MESSAGES (peer gets only identities it already knows) --
        entitled = {i for i in store.known_identities() if i in peer_known}
        to_send = store.messages_not_in(peer_have.get("summary", {}),
                                        entitled, peer_identity)
        msgs = {"t": "messages", "msgs": [m.to_dict() for m in to_send]}
        peer_msgs = await self._swap(reader, writer, initiator, msgs)
        for md in peer_msgs.get("msgs", []):
            res = store.ingest_message(SignedMessage.from_dict(md))
            if res.accepted:
                changed = True

        # -- BLOBS --
        want = {"t": "blob_want", "hashes": sorted(store.missing_blobs())}
        peer_want = await self._swap(reader, writer, initiator, want)
        give = {}
        give_size = 0
        for h in peer_want.get("hashes", []):
            data = store.get_blob(h)
            if data is None:
                continue
            b64 = base64.b64encode(data).decode()
            if give and give_size + len(b64) > BLOB_GIVE_BUDGET:
                break            # leave the rest for the next sync round
            give[h] = b64
            give_size += len(b64)
        blobs = {"t": "blobs", "blobs": give}
        peer_blobs = await self._swap(reader, writer, initiator, blobs)
        for h, b64 in peer_blobs.get("blobs", {}).items():
            data = base64.b64decode(b64)
            if len(data) <= MAX_BLOB_BYTES and blob_hash(data) == h:
                store.put_blob(data)
                changed = True

        if changed:
            node.notify()
        return peer_identity, applied_by_peer
