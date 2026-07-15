"""Two-node integration test for the `grids` map inside
KIND_PROFILE_LAYOUT (Slice 3b), proven over real sync sockets. Mirrors
tests/test_profile_arrange_integration.py: real SyncService, started()
helper, store-level befriend().

Retired (spec 2026-07-13, collage Slice A): profile_view no longer
annotates wall entries with `p["grid"]` -- the client stops reading it
(Task 5, same bundle). Grid styling rides the wire for back-compat only.
This file now asserts the record itself (restyle, then reorder) survives
encrypt -> sync -> decrypt to a friend's store via
store.profile_layout(...)["grids"], keeping the wire-compat guarantee
pinned without asserting retired view annotations."""
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


def test_friend_gets_grid_record_and_it_survives_reorder(tmp_path):
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Anna", "anna-phone")
        b = HearthNode.create(tmp_path / "b", "Bo", "bo-phone")
        befriend(a, b)
        for n in (a, b):
            n.ensure_enckey()
        sa, aa = await started(a)
        sb, ba = await started(b)
        await sa.sync_with(ba)                     # exchange enckeys

        multi = a.compose_post("gallery", scope="kreds", placement="profile",
                               photos=[png_bytes(8, 8, (200, 80, 80)),
                                      png_bytes(8, 8, (80, 200, 80))])
        other = a.compose_post("solo", scope="kreds", placement="profile")
        a.set_block_grid(multi, "cols3")
        await sa.sync_with(ba)

        # B can decrypt it and the record carries the style (wire-compat).
        assert b.store.profile_layout(a.identity_pub)["grids"][multi] == "cols3"

        a.set_block_grid(multi, "hero")             # restyle in place
        await sa.sync_with(ba)
        assert b.store.profile_layout(a.identity_pub)["grids"][multi] == "hero"

        a.set_profile_layout([other, multi])        # reorder A's wall (wire-compat only)
        await sa.sync_with(ba)
        layout = b.store.profile_layout(a.identity_pub)
        assert layout["order"] == [other, multi]
        assert layout["grids"][multi] == "hero"      # grid survived the reorder, on B's side

        for s in (sa, sb):
            await s.stop()
    asyncio.run(scenario())
