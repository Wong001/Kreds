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
the node actually holds."""
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
        # the seeding below ever grows friends.
        "identities": len(node.store.known_identities()),
    }


async def main(data_dir):
    node = HearthNode.create(Path(data_dir) / "n", "Desk", "desk")
    # Seed own-identity content: two text posts + one post with a photo
    # (-> a photo blob + its thumbnail blob, both referenced from the
    # post's payload).
    node.compose_post("hello from desk")
    node.compose_post("second post, still text")
    node.compose_post("with pic", photos=[_tiny_png()])

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
    orig_on_conn = sync._on_conn

    async def _on_conn_then_maintain(reader, writer):
        await orig_on_conn(reader, writer)
        node.maintain_own_device_grants()
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
    asyncio.run(main(sys.argv[1]))
