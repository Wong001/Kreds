"""Two-node integration test for the `sizes` map inside
KIND_PROFILE_LAYOUT (Kreds profile bento Phase A). Mirrors
tests/test_profile_grids_integration.py: real SyncService, the started()
helper, store-level befriend().

Retired (spec 2026-07-13, collage Slice A): profile_view no longer
annotates wall entries with `p["size"]`/`p["grid"]` -- legacy sizes now
map to default spans instead (proven single-node in
tests/test_block_pins.py). Bento sizes ride the wire for back-compat
only. This file now asserts the record itself (sizes, then reorder +
grid change) survives encrypt -> sync -> decrypt to a friend's store via
store.profile_layout(...), keeping the wire-compat guarantee pinned
without asserting retired view annotations."""
import asyncio

from hearth.node import HearthNode
from hearth.sync import SyncService
from tests.test_imagegate import png_bytes


def befriend(a, b):
    a.store.add_identity(b.identity_pub)
    b.store.add_identity(a.identity_pub)


async def started(node):
    svc = SyncService(node)
    port = await svc.start("127.0.0.1", 0)
    node.store.set_meta("gossip_addr", f"127.0.0.1:{port}")
    return svc, f"127.0.0.1:{port}"


def test_friend_gets_bento_size_records_surviving_reorder_and_grid_change(tmp_path):
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
                             photos=[png_bytes(8, 8, (200, 80, 80)),
                                    png_bytes(8, 8, (80, 200, 80))])
        three = a.compose_post("three", scope="kreds", placement="profile")
        a.set_block_size(one, "wide")
        a.set_block_size(two, "small")
        a.set_block_size(three, "full")
        await sa.sync_with(ba)

        sizes = b.store.profile_layout(a.identity_pub)["sizes"]
        assert sizes[one] == "wide"
        assert sizes[two] == "small"
        assert three not in sizes                   # explicit full clears the entry

        a.set_profile_layout([three, one, two])     # reorder A's wall (wire-compat only)
        a.set_block_grid(two, "cols3")               # change one block's grid
        await sa.sync_with(ba)

        layout2 = b.store.profile_layout(a.identity_pub)
        assert layout2["order"] == [three, one, two]
        assert layout2["sizes"][one] == "wide"       # sizes survived the reorder...
        assert layout2["sizes"][two] == "small"      # ...and the grid change...
        assert three not in layout2["sizes"]
        assert layout2["grids"][two] == "cols3"      # ...and the grid landed too

        for s in (sa, sb):
            await s.stop()
    asyncio.run(scenario())
