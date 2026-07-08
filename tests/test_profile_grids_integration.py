"""Two-node integration test for per-block grid styling (the `grids` map
inside KIND_PROFILE_LAYOUT, Slice 3b), proven over real sync sockets.
Mirrors tests/test_profile_arrange_integration.py: real SyncService,
started() helper, store-level befriend(). tests/test_profile_grids.py
already proves grid annotation + reorder-preserves-grids at the
single-node/API level; this file proves the grids map itself survives
encrypt -> sync -> decrypt, that restyling republishes and a friend sees
the new style after a further sync, and that reordering the wall (a
separate publish) does not drop an already-set grid on the friend's
side."""
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


def test_friend_sees_restyled_grid_and_it_survives_reorder(tmp_path):
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
                               photos=[b"\x89PNG one", b"\x89PNG two"])
        other = a.compose_post("solo", scope="kreds", placement="profile")
        a.set_block_grid(multi, "cols3")
        await sa.sync_with(ba)

        view = b.profile_view(a.identity_pub)
        wall = {p["msg_id"]: p for p in view["wall"]}
        assert wall[multi]["grid"] == "cols3"       # B can decrypt it, sees the style

        a.set_block_grid(multi, "hero")             # restyle in place
        await sa.sync_with(ba)
        view2 = b.profile_view(a.identity_pub)
        assert {p["msg_id"]: p for p in view2["wall"]}[multi]["grid"] == "hero"

        a.set_profile_layout([other, multi])        # reorder A's wall
        await sa.sync_with(ba)
        view3 = b.profile_view(a.identity_pub)
        assert [p["msg_id"] for p in view3["wall"]] == [other, multi]
        wall3 = {p["msg_id"]: p for p in view3["wall"]}
        assert wall3[multi]["grid"] == "hero"        # grid survived the reorder, on B's side

        for s in (sa, sb):
            await s.stop()
    asyncio.run(scenario())
