"""Two-node integration test for the profile-wall layout record
(KIND_PROFILE_LAYOUT), proven over real sync sockets. Mirrors
tests/test_curated_profile_integration.py: real SyncService, started()
helper, store-level befriend().

Retired (spec 2026-07-13, collage Slice A): the wall is now geometry-ruled
by pins/spans, always rendered newest-first -- the `order` map no longer
shapes profile_view's wall, so this file no longer asserts view ordering.
It still proves what matters at the sync layer: `set_profile_layout`'s
record (order map included, for wire back-compat) propagates encrypt ->
sync -> decrypt to a friend's store, and a friend's wall is newest-first
regardless of that record, same as the single-node proof in
tests/test_block_pins.py::test_wall_is_newest_first_regardless_of_order_map."""
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


def test_friend_gets_order_record_but_wall_stays_newest_first(tmp_path):
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
        two = a.compose_post("two", scope="kreds", placement="profile")
        three = a.compose_post("three", scope="kreds", placement="profile")
        a.set_profile_layout([two, three, one])     # A's chosen order (wire-compat only)
        await sa.sync_with(ba)

        # The order record itself propagated to B's store...
        assert b.store.profile_layout(a.identity_pub)["order"] == [two, three, one]
        # ...but it no longer shapes B's view of the wall: newest-first always.
        view = b.profile_view(a.identity_pub)
        wall_texts = [p["text"] for p in view["wall"]]
        assert wall_texts == ["three", "two", "one"]

        # A posts a 4th block after "arranging"; newest-first means it's on
        # top regardless of the (inert) layout record.
        a.compose_post("four", scope="kreds", placement="profile")
        await sa.sync_with(ba)

        view2 = b.profile_view(a.identity_pub)
        wall_texts2 = [p["text"] for p in view2["wall"]]
        assert wall_texts2 == ["four", "three", "two", "one"]

        for s in (sa, sb):
            await s.stop()
    asyncio.run(scenario())
