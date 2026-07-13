"""Two-node integration for collage pins (Slice A), over real sync
sockets - mirrors tests/test_profile_arrange_integration.py's harness.
Proves: A's pin geometry reaches B latest-wins; an unpinned block flows
with its span; a never-arranged (legacy) wall renders newest-first."""
import asyncio

from hearth.node import HearthNode
from hearth.sync import SyncService


def befriend(a, b):
    a.store.add_identity(b.identity_pub)
    b.store.add_identity(a.identity_pub)


async def started(node):
    svc = SyncService(node)
    port = await svc.start("127.0.0.1", 0)
    node.store.set_meta("gossip_addr", f"127.0.0.1:{port}")
    return svc, f"127.0.0.1:{port}"


def test_pins_survive_sync_and_legacy_flows(tmp_path):
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Anna", "anna-pc")
        b = HearthNode.create(tmp_path / "b", "Bo", "bo-pc")
        befriend(a, b)
        for n in (a, b):
            n.ensure_enckey()
        sa, aa = await started(a)
        sb, ba = await started(b)
        await sa.sync_with(ba)

        p1 = a.compose_post("pinned", scope="kreds", placement="profile")
        p2 = a.compose_post("tray", scope="kreds", placement="profile")
        a.set_block_pin(p1, 0, 1, 4, 3)
        a.set_block_span(p2, 1, 1)
        await sa.sync_with(ba)

        view = b.profile_view(a.identity_pub)
        by_id = {p["msg_id"]: p for p in view["wall"]}
        assert by_id[p1]["pin"] == {"x": 0, "y": 1, "w": 4, "h": 3}
        assert by_id[p2]["pin"] is None
        assert by_id[p2]["span"] == {"w": 1, "h": 1}

        # A moves the block; latest-wins geometry reaches B.
        a.set_block_pin(p1, 0, 0, 2, 2)
        await sa.sync_with(ba)
        view = b.profile_view(a.identity_pub)
        by_id = {p["msg_id"]: p for p in view["wall"]}
        assert by_id[p1]["pin"] == {"x": 0, "y": 0, "w": 2, "h": 2}

        # Legacy wall: Bo never arranged anything - newest-first flow with
        # default spans on Anna's side after a real sync.
        q1 = b.compose_post("bo older", scope="kreds", placement="profile")
        q2 = b.compose_post("bo newer", scope="kreds", placement="profile")
        await sb.sync_with(aa)
        view = a.profile_view(b.identity_pub)
        ids = [p["msg_id"] for p in view["wall"]]
        # deterministic even on a created_at tie: post_messages' rowid
        # DESC tie-break puts q2 (composed/arrived after q1) first.
        assert ids.index(q2) < ids.index(q1)
        assert all(p["pin"] is None for p in view["wall"])
        assert all(p["span"] == {"w": 4, "h": 1} for p in view["wall"])

        for s in (sa, sb):
            await s.stop()
    asyncio.run(scenario())
