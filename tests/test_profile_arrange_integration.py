"""Two-node integration test for profile-wall arrangement (KIND_PROFILE_LAYOUT),
proven over real sync sockets. Mirrors tests/test_curated_profile_integration.py:
real SyncService, started() helper, store-level befriend(). test_profile_layout.py
already proves layout ordering at the single-node/API level; this file proves the
layout record itself survives encrypt -> sync -> decrypt and that a friend's view
of the wall reflects the author's chosen order, plus that a fresh unlisted post
still surfaces on top after sync."""
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


def test_friend_sees_arranged_wall_order_over_sync(tmp_path):
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
        a.set_profile_layout([two, three, one])     # A's chosen order
        await sa.sync_with(ba)

        view = b.profile_view(a.identity_pub)
        wall_texts = [p["text"] for p in view["wall"]]
        assert wall_texts == ["two", "three", "one"]

        # A posts a 4th block after arranging; it's unlisted in the layout
        # record, so it must surface at the TOP of B's view (newest-first
        # for unlisted blocks), ahead of the three arranged ones.
        a.compose_post("four", scope="kreds", placement="profile")
        await sa.sync_with(ba)

        view2 = b.profile_view(a.identity_pub)
        wall_texts2 = [p["text"] for p in view2["wall"]]
        assert wall_texts2 == ["four", "two", "three", "one"]

        for s in (sa, sb):
            await s.stop()
    asyncio.run(scenario())
