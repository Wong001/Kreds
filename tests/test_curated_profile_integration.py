"""Two-node integration test for the curated-profile wall/journal split,
proven over real sync sockets. Mirrors tests/test_scoped_posts_e2e.py: real
SyncService, started() helper, store-level befriend(). Those tests proved
scope (inner/kreds) survives encrypt -> sync -> decrypt; this file proves
placement (journal/profile) does too, plus its interaction with expiry --
not just the API/store-level coverage already in test_api_profile_posts.py
and test_node_scoped_posts.py."""
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


def test_curated_profile_wall_journal_separation_over_sync(tmp_path):
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Anna", "anna-phone")
        b = HearthNode.create(tmp_path / "b", "Bo", "bo-phone")
        befriend(a, b)
        for n in (a, b):
            n.ensure_enckey()
        sa, aa = await started(a)
        sb, ba = await started(b)
        await sa.sync_with(ba)                     # exchange enckeys

        a.compose_post("dagbog", scope="kreds", placement="journal")
        a.compose_post("til vaeggen", scope="kreds", placement="profile")
        await sa.sync_with(ba)

        feed_texts = [p["text"] for p in b.feed()]
        assert "dagbog" in feed_texts
        assert "til vaeggen" not in feed_texts          # profile post never in feed

        view = b.profile_view(a.identity_pub)
        wall_texts = [p["text"] for p in view["wall"]]
        journal_texts = [p["text"] for p in view["journal"]]
        assert "til vaeggen" in wall_texts
        assert "dagbog" not in wall_texts               # journal post never on wall
        assert "dagbog" in journal_texts
        assert "til vaeggen" not in journal_texts        # wall post never in journal

        # An expiring journal post: appears under journal before it expires,
        # and NEVER on the wall (placement, not expiry, gates the wall).
        a.compose_post("snart vaek", scope="kreds", placement="journal",
                       expires_seconds=60)
        await sa.sync_with(ba)

        view2 = b.profile_view(a.identity_pub)
        assert "snart vaek" in [p["text"] for p in view2["journal"]]
        assert "snart vaek" not in [p["text"] for p in view2["wall"]]
        assert "snart vaek" in [p["text"] for p in b.feed()]

        for s in (sa, sb):
            await s.stop()
    asyncio.run(scenario())
