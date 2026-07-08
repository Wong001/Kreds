"""Two-node integration test for per-block bento sizes (the `sizes` map
inside KIND_PROFILE_LAYOUT, Kreds profile bento Phase A). Mirrors
tests/test_profile_grids_integration.py: real SyncService, the started()
helper, store-level befriend(). tests/test_profile_sizes.py already proves
size annotation + reorder/grid-preserves-size at the single-node/API level;
this file proves the sizes map itself survives encrypt -> sync -> decrypt,
that a friend sees the chosen sizes after sync, and that reordering the
wall + restyling a block's photo grid (separate publishes) do not drop an
already-set size on the friend's side."""
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


def test_friend_sees_bento_sizes_and_they_survive_reorder_and_grid_change(tmp_path):
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Anna", "anna-phone")
        b = HearthNode.create(tmp_path / "b", "Bo", "bo-phone")
        befriend(a, b)
        for n in (a, b):
            n.ensure_enckey()
        sa, aa = await started(a)
        sb, ba = await started(b)
        await sa.sync_with(ba)                     # exchange enckeys

        one = a.compose_post("one", scope="kreds", placement="profile")
        two = a.compose_post("two", scope="kreds", placement="profile",
                             photos=[b"\x89PNG one", b"\x89PNG two"])
        three = a.compose_post("three", scope="kreds", placement="profile")
        a.set_block_size(one, "wide")
        a.set_block_size(two, "small")
        a.set_block_size(three, "full")
        await sa.sync_with(ba)

        view = b.profile_view(a.identity_pub)
        wall = {p["msg_id"]: p for p in view["wall"]}
        assert wall[one]["size"] == "wide"
        assert wall[two]["size"] == "small"
        assert wall[three]["size"] == "full"        # explicit full, same as default

        a.set_profile_layout([three, one, two])     # reorder A's wall
        a.set_block_grid(two, "cols3")               # change one block's grid
        await sa.sync_with(ba)

        view2 = b.profile_view(a.identity_pub)
        assert [p["msg_id"] for p in view2["wall"]] == [three, one, two]
        wall2 = {p["msg_id"]: p for p in view2["wall"]}
        assert wall2[one]["size"] == "wide"          # sizes survived the reorder...
        assert wall2[two]["size"] == "small"         # ...and the grid change...
        assert wall2[three]["size"] == "full"
        assert wall2[two]["grid"] == "cols3"         # ...and the grid landed too

        for s in (sa, sb):
            await s.stop()
    asyncio.run(scenario())
