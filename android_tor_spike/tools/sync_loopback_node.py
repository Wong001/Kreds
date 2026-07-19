"""Desk loopback: seed a node with own-identity messages + blobs, mint the
phone fixture, serve SyncService on 127.0.0.1, print a JSON handshake line
({port, fixture, expect}), then serve until killed. Driven by
SyncLoopbackTest.kt (BB-5, the desk loopback gate).

Task 6 (B.2) extension: after EVERY connection's session fully completes,
node.maintain_own_device_grants() runs and a `{"event": "maintained"}` line
is printed -- see main() below. This is what lets a second connection prove
it pulls wrap_grants minted from a first connection's pushed enckey,
deterministically (the test blocks on the signal line rather than racing
connection timing).

`expect` is computed from the node's actual store state -- never
hardcoded -- mirroring exactly what store.messages_not_in() /
missingBlobs-equivalent logic the phone will receive during the real
sync session (hearth/sync.py:626-628 HAVE/MESSAGES phase): the phone
(own-identity peer) is entitled to every own-identity message, and to
every blob hash referenced (blobs/poster/thumbs) by those messages that
the node actually holds.

Task 4 (B.2c) extension: an OPT-IN second scenario ("two_node", selected
by an optional second CLI arg -- default "solo" reproduces every byte of
the ORIGINAL single-node behavior above, untouched) adds a second, real
in-process HearthNode ("Freja") befriended with the desk node, so the
desk loopback gate can prove the phone reads FRIEND content (wall posts,
an old DM backfilled via a recipient-signed grant, and a new DM that
inline-wraps the phone directly) end-to-end over the real wire, not just
its own-identity history. See _seed_friend_and_befriend/_run_phase3
below for the scenario and SyncLoopbackTest.kt's
phoneReadsFriendContentEndToEnd for the assertions.

Node-to-node content movement between the desk and friend nodes is DIRECT
IN-PROCESS DELIVERY (store.messages_not_in + store.ingest_message, both
directions, bounded rounds with an assert -- see _deliver/
_gossip_until_converged) rather than a second real network sync: this
script is gating the PHONE<->desk-node wire protocol, which is the only
leg that needs to be real; hearth/demo.py's real Tor/TCP gossip between
two independent people's devices is heavier prior art aimed at a
different question (proving the transport itself), and
tests/test_own_device_grants.py's / tests/test_received_dm_grants.py's
own _befriend_with_enckeys/_deliver helpers already establish this exact
direct-delivery pattern is sufficient and deterministic for moving
content between two HearthNode instances in one process."""
import asyncio
import io
import json
import sys
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))  # repo root
from hearth.node import HearthNode
from hearth.sync import SyncService

sys.path.insert(0, str(Path(__file__).resolve().parent))  # this dir (mint.py)
from mint import mint_fixture   # reused from the spike


def _tiny_png() -> bytes:
    """A minimal real (decodable) image -- compose_post's photo gate
    (transcode_photo) opens and re-encodes it, so raw junk bytes would
    raise ValueError("not an image"). An 8x8 solid square is the
    smallest thing that round-trips through the gate + thumbnailer."""
    img = Image.new("RGB", (8, 8), (200, 30, 30))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


def _compute_expect(node) -> dict:
    """Mirrors hearth/sync.py's own-identity HAVE/MESSAGES/BLOBS phases
    exactly (see _session, sync.py:602-663): a fresh phone fixture's
    SyncStore starts knowing only the node's own identity_pub, so
    entitled == {node.identity_pub} and peer_identity == node.identity_pub
    -- messages_not_in({}, {node.identity_pub}, node.identity_pub) is
    exactly the message set the node will hand the phone."""
    to_send = node.store.messages_not_in(
        {}, {node.identity_pub}, node.identity_pub)
    refs = set()
    for m in to_send:
        p = m.payload
        refs |= {b for b in (p.get("blobs") or []) if isinstance(b, str)}
        poster = p.get("poster")
        if isinstance(poster, str) and poster:
            refs.add(poster)
        refs |= {t for t in (p.get("thumbs") or []) if isinstance(t, str) and t}
    expect_blobs = len([h for h in refs if node.store.has_blob(h)])
    return {
        "messages": len(to_send),
        "blobs": expect_blobs,
        # Fresh node, no friends: known_identities() is just the own
        # identity. Computed (not hardcoded 1) so this stays correct if
        # the seeding below ever grows friends -- which the "two_node"
        # scenario (Task 4, B.2c) now does; _compute_expect itself is
        # unaffected either way since `entitled` above is always pinned
        # to {node.identity_pub} regardless of who else node knows.
        "identities": len(node.store.known_identities()),
    }


def _deliver(src, dst) -> bool:
    """One-directional content transfer: every message `src` has authored
    that `dst` does not yet hold, via the real store.messages_not_in +
    store.ingest_message pair -- the same two calls a real sync session
    drives, just without the socket in between. Mirrors
    tests/test_received_dm_grants.py's _deliver helper. Returns whether
    anything new was actually accepted (dedup-aware: re-delivering
    already-held messages is a safe no-op, not "changed")."""
    changed = False
    for m in src.store.messages_not_in({}, {src.identity_pub}, dst.identity_pub):
        res = dst.store.ingest_message(m)
        changed = changed or res.accepted
    return changed


def _gossip_until_converged(a, b, max_rounds=4) -> int:
    """Bounded-rounds direct-in-process gossip standing in for real
    network sync between the desk and friend nodes (see module docstring
    for why direct delivery was chosen over a second real transport).
    Delivers both directions every round and asserts convergence (a round
    that moves nothing) within max_rounds, rather than assuming any fixed
    round count or sleeping -- a regression that stopped content from
    ever settling would fail loudly here, not as a silent short read
    downstream. Returns the round index convergence was detected at."""
    for i in range(max_rounds):
        moved_ab = _deliver(a, b)
        moved_ba = _deliver(b, a)
        if not (moved_ab or moved_ba):
            return i
    raise RuntimeError(
        "direct in-process gossip between the desk and friend nodes did "
        f"not converge within {max_rounds} rounds")


def _seed_friend_and_befriend(node, friend_dir) -> "HearthNode":
    """Task 4 (B.2c) scenario steps 1-2: a second real HearthNode
    ("Freja"), befriended with `node` both ways via the same lightweight
    store.add_identity + ensure_enckey + direct-delivery pattern already
    proven by tests/test_own_device_grants.py's and
    tests/test_received_dm_grants.py's _befriend_with_enckeys helper --
    not the full invite/pairing ceremony (hearth/demo.py's heavier prior
    art for befriending two independent people): this gate exercises
    content sync, not pairing, so the already-proven lighter pattern is
    enough. Delivering each side's ensure_enckey message is what TOFU-
    registers a DeviceView for the OTHER identity's device
    (identity.py's Verifier.verify_message) -- load-bearing for
    messages_not_in's KIND_POST/KIND_WRAP_GRANT peer-device gating, not
    merely cosmetic; skip it and cross-identity content silently stops
    relaying.

    Composes friend's wall posts + the OLD DM here, before this
    function's own final gossip round -- i.e. all before the phone ever
    connects (scenario steps 1-2), and before the phone's device or enc
    key exist anywhere: HearthNode.create's own set_profile("Freja") call
    is what publishes the friend's profile (delivered to `node` by the
    same final gossip round), satisfying the "friend has a real,
    delivered profile" requirement the author-name-resolution assertion
    needs."""
    friend = HearthNode.create(friend_dir, "Freja", "freja-desk")
    node.store.add_identity(friend.identity_pub)
    friend.store.add_identity(node.identity_pub)
    node.ensure_enckey()
    friend.ensure_enckey()
    _gossip_until_converged(node, friend)   # cross-registers device views

    friend.compose_post("friend wall post one", scope="kreds",
                        placement="profile")
    friend.compose_post("friend wall post two", scope="kreds",
                        placement="profile")
    friend.compose_dm(node.identity_pub, "old dm from friend")
    _gossip_until_converged(node, friend)
    return friend


def _run_phase3(node, friend) -> None:
    """Task 4 (B.2c) scenario step 3's second half -- reactive to the
    phone's real enckey push, which has ALREADY landed in node.store by
    the time this runs (the just-finished connection's real MESSAGES
    phase ingested it generically; no script-side injection). Runs
    exactly once per node process -- see main()'s guard -- the first
    time any connection completes.

    Order mirrors hearth/sync.py's _gossip_round for this keyspace
    (maintain_wrap_grants; maintain_own_device_grants;
    maintain_received_dm_grants), split across the two nodes: friend's
    STOCK, untouched maintain_wrap_grants mints the wall grants naming
    the phone (it now knows the phone's enc key, delivered below);
    node's maintain_received_dm_grants (already called once,
    unconditionally, in main()'s wrapper before this runs -- see
    maintain_own_device_grants there) backfills the OLD dm to the phone
    via a RECIPIENT-signed grant. RED-CONTROL: comment out ONLY the
    maintain_received_dm_grants call below to reproduce the required
    RED state (task-4-brief.md) -- the old-DM assertion must then fail
    while the wall + new-DM assertions still pass."""
    _deliver(node, friend)               # phone's enckey -> friend
    friend.maintain_wrap_grants()        # STOCK, untouched: mints wall
                                         # grants naming the phone
    node.maintain_received_dm_grants()   # backfills the OLD dm to the
                                         # phone via a recipient-signed grant
    friend.compose_dm(node.identity_pub, "new dm from friend")  # phone's
                                         # enc key is known now -> inline
    _deliver(friend, node)               # bring it all back to node


async def main(data_dir, scenario="solo"):
    node = HearthNode.create(Path(data_dir) / "n", "Desk", "desk")
    # Seed own-identity content: two text posts + one post with a photo
    # (-> a photo blob + its thumbnail blob, both referenced from the
    # post's payload).
    node.compose_post("hello from desk")
    node.compose_post("second post, still text")
    node.compose_post("with pic", photos=[_tiny_png()])

    # Task 4 (B.2c): opt-in only -- "solo" (the default, and every call
    # site that predates this task) never constructs a friend node at
    # all, so authProbeConnectionIsAccepted / syncsRealOwnIdentityContent
    # / phoneDecryptsRealBackfilledContent see byte-identical behavior to
    # before this task.
    friend = None
    if scenario == "two_node":
        friend = _seed_friend_and_befriend(node, Path(data_dir) / "f")

    sync = SyncService(node)

    # Task 6 (B.2): the desk decrypt gate's two-sync flow needs
    # node.maintain_own_device_grants() (Task 2) to run AFTER the phone's
    # first sync pushes its device-signed enckey message (via KotlinSync's
    # `outbound`, ingested generically by hearth/sync.py's MESSAGES phase --
    # untouched, no script-side injection needed) and BEFORE the phone's
    # second sync pulls the resulting wrap_grants. In production this sweep
    # rides the periodic gossip loop (hearth/sync.py _gossip_round,
    # SyncService.gossip_loop) alongside maintain_enckey/maintain_wrap_grants
    # -- this desk gate never starts that loop, so it is called directly
    # here instead (spec: "for the loopback script call it directly").
    #
    # Wrapping _on_conn (rather than editing hearth/sync.py, which is
    # untouchable production code) means the real, unmodified _on_conn/
    # _session logic runs exactly as production does; this only appends the
    # sweep once a connection's session has FULLY finished (the connection's
    # writer already closed). The signal line printed afterward lets the
    # test BLOCK on the sweep actually having run, rather than racing the
    # timing of opening a second connection against this coroutine's
    # completion on the node's event loop -- deterministic handoff, not a
    # timing assumption.
    #
    # Task 4 (B.2c) extension: when a friend node exists (scenario
    # "two_node"), the FIRST connection to fully complete also runs
    # _run_phase3 (see its own docstring) -- gated by phase3_done so it
    # fires exactly once, not on every connection (the new test's own
    # second sync must not re-trigger friend.compose_dm a second time).
    orig_on_conn = sync._on_conn
    phase3_done = False

    async def _on_conn_then_maintain(reader, writer):
        nonlocal phase3_done
        await orig_on_conn(reader, writer)
        node.maintain_own_device_grants()
        if friend is not None and not phase3_done:
            phase3_done = True
            _run_phase3(node, friend)
        print(json.dumps({"event": "maintained"}), flush=True)

    sync._on_conn = _on_conn_then_maintain

    port = await sync.start("127.0.0.1", 0)
    try:
        fx = mint_fixture(node)
        fx["onion_addr"] = f"127.0.0.1:{port}"
        expect = _compute_expect(node)
        print(json.dumps({"port": port, "fixture": fx, "expect": expect}),
              flush=True)
        await asyncio.Event().wait()   # serve until killed
    finally:
        await sync.stop()


if __name__ == "__main__":
    _scenario = sys.argv[2] if len(sys.argv) > 2 else "solo"
    asyncio.run(main(sys.argv[1], _scenario))
