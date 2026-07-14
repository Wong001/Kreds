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
        # dynamic placement (spec 2026-07-14): creation auto-pins now, so
        # reaching an unplaced/spanned block goes through an explicit unpin.
        a.unpin_block(p2)
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

        # Legacy wall: dynamic placement (spec 2026-07-14) means
        # compose_post always auto-pins now, so a truly never-arranged
        # (pins={}, spans={}) wall can no longer arise through the node
        # API - simulate it directly, the way a pre-dynamic-placement
        # wall actually looks on the wire, then prove the newest-first
        # default-span fallback still renders on Anna's side after sync.
        q1 = b.compose_post("bo older", scope="kreds", placement="profile")
        q2 = b.compose_post("bo newer", scope="kreds", placement="profile")
        from hearth.messages import make_profile_layout
        b_cur = b.store.profile_layout(b.identity_pub)
        b._publish(make_profile_layout(
            b.device, b_cur["order"], grids=b_cur["grids"],
            sizes=b_cur["sizes"], pins={}, spans={}, texts=b_cur["texts"]))
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


def test_pushed_layout_syncs_identically(tmp_path):
    """Dynamic placement (spec 2026-07-14): A pins three stacked blocks,
    then drops one on top of the stack - the server-side push cascades the
    other two down. The record carries the pushed RESULT, so B's synced
    profile_view pins match A's own layout exactly, with no re-run of the
    placement algorithm on B's side."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Anna", "anna-pc")
        b = HearthNode.create(tmp_path / "b", "Bo", "bo-pc")
        befriend(a, b)
        for n in (a, b):
            n.ensure_enckey()
        sa, aa = await started(a)
        sb, ba = await started(b)
        await sa.sync_with(ba)

        x = a.compose_post("x", scope="kreds", placement="profile")
        y = a.compose_post("y", scope="kreds", placement="profile")
        z = a.compose_post("z", scope="kreds", placement="profile")
        a.set_block_pin(x, 0, 0, 4, 1)
        a.set_block_pin(y, 0, 1, 4, 1)
        a.set_block_pin(z, 0, 2, 4, 1)
        a.set_block_pin(z, 0, 0, 4, 2)     # drop z on top: x, y cascade down
        await sa.sync_with(ba)

        view = b.profile_view(a.identity_pub)
        by_id = {p["msg_id"]: p for p in view["wall"]}
        a_pins = a.store.profile_layout(a.identity_pub)["pins"]
        for mid in (x, y, z):
            assert by_id[mid]["pin"] == a_pins[mid]

        for s in (sa, sb):
            await s.stop()
    asyncio.run(scenario())
