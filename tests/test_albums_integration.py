"""Two-node album integration over real sync sockets: an album syncs,
GROWS across the wire, an Inner-scoped member stays out of a Kreds-only
viewer's deck AND stays suppressed standalone, ungroup restores."""
import asyncio

from hearth.node import HearthNode
from hearth.sync import SyncService

# Fake PNG magic bytes -- compose_post never decodes photo bytes (unlike
# compose_story's transcode gate), so any bytes work; reuses the exact
# literal test_albums_node.py's photo test passes to `photos=`.
PNG = b"\x89PNG fake"


def befriend(a, b):
    a.store.add_identity(b.identity_pub)
    b.store.add_identity(a.identity_pub)


async def started(node):
    svc = SyncService(node)
    port = await svc.start("127.0.0.1", 0)
    node.store.set_meta("gossip_addr", f"127.0.0.1:{port}")
    return svc, f"127.0.0.1:{port}"


def test_album_grows_and_scopes_over_sync(tmp_path):
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Anna", "anna-pc")
        b = HearthNode.create(tmp_path / "b", "Bo", "bo-pc")
        befriend(a, b)
        for n in (a, b):
            n.ensure_enckey()
        sa, aa = await started(a)
        sb, ba = await started(b)
        await sa.sync_with(ba)

        p1 = a.compose_post("en", scope="kreds", placement="profile", photos=[PNG])
        p2 = a.compose_post("hemmelig", scope="inner", placement="profile", photos=[PNG])
        aid = a.set_album([p1, p2])
        await sa.sync_with(ba)

        view = b.profile_view(a.identity_pub)
        alb = next(p for p in view["wall"] if p.get("album"))
        assert [ph["m"] for ph in alb["photos"]] == [p1]     # inner member absent
        assert alb["count"] == 1
        ids = [p["msg_id"] for p in view["wall"]]
        assert p1 not in ids and p2 not in ids               # suppressed standalone

        # grow: a new photo post + republished record reach B
        p3 = a.compose_post("tre", scope="kreds", placement="profile", photos=[PNG])
        a.set_album([p1, p2, p3], album_id=aid)
        await sa.sync_with(ba)
        view = b.profile_view(a.identity_pub)
        alb = next(p for p in view["wall"] if p.get("album"))
        assert [ph["m"] for ph in alb["photos"]] == [p1, p3]

        # ungroup restores standalone on BOTH sides
        a.set_album([], album_id=aid)
        await sa.sync_with(ba)
        view = b.profile_view(a.identity_pub)
        assert not any(p.get("album") for p in view["wall"])
        ids = [p["msg_id"] for p in view["wall"]]
        assert p1 in ids and p3 in ids and p2 not in ids     # p2 inner: still invisible

        for s in (sa, sb):
            await s.stop()
    asyncio.run(scenario())
