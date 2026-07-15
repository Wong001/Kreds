"""Two-node album integration over real sync sockets: an album syncs,
GROWS across the wire, an Inner-scoped member stays out of a Kreds-only
viewer's deck AND stays suppressed standalone, ungroup restores."""
import asyncio

from hearth.messages import ACCENTS
from hearth.node import HearthNode
from hearth.sync import SyncService
from tests.test_imagegate import png_bytes

# A real (tiny) PNG -- compose_post now runs photos through the photo gate
# (transcode_photo), which rejects non-image bytes.
PNG = png_bytes(8, 8)


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


def test_text_style_survives_sync(tmp_path):
    """A styled wall text block (spec 2026-07-14) reaches a friend's
    profile_view with the same text_style - the texts map rides the same
    profile_layout record as pins/spans/albums, so this is the album
    suite's sibling: real sync sockets, no shortcuts."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Anna", "anna-pc")
        b = HearthNode.create(tmp_path / "b", "Bo", "bo-pc")
        befriend(a, b)
        for n in (a, b):
            n.ensure_enckey()
        sa, aa = await started(a)
        sb, ba = await started(b)
        await sa.sync_with(ba)

        t = a.compose_post("styled", scope="kreds", placement="profile")
        a.set_block_text(t, h="center", size="xl", color=ACCENTS[0])
        await sa.sync_with(ba)

        view = b.profile_view(a.identity_pub)
        blk = next(p for p in view["wall"] if p["msg_id"] == t)
        assert blk["text_style"] == {"h": "center", "v": "top", "size": "xl",
                                     "font": "sans", "weight": "normal",
                                     "style": "normal", "color": ACCENTS[0]}

        for s in (sa, sb):
            await s.stop()
    asyncio.run(scenario())


def test_delete_everywhere_album_member_shrinks_deck_over_sync(tmp_path):
    """A delete-everywhere on one album member shrinks the deck to the
    remaining decryptable members - on B's synced view AND on A's own
    (the album-folding loop's existing "p is None -> continue" branch,
    same path as an undecryptable/conflicted member, handles a deleted
    member with no special-casing needed)."""
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Anna", "anna-pc")
        b = HearthNode.create(tmp_path / "b", "Bo", "bo-pc")
        befriend(a, b)
        for n in (a, b):
            n.ensure_enckey()
        sa, aa = await started(a)
        sb, ba = await started(b)
        await sa.sync_with(ba)

        p1 = a.compose_post("uno", scope="kreds", placement="profile", photos=[PNG])
        p2 = a.compose_post("dos", scope="kreds", placement="profile", photos=[PNG])
        a.set_album([p1, p2])
        await sa.sync_with(ba)

        a.delete_post(p1)
        await sa.sync_with(ba)

        view_b = b.profile_view(a.identity_pub)
        alb_b = next(p for p in view_b["wall"] if p.get("album"))
        assert [ph["m"] for ph in alb_b["photos"]] == [p2]
        assert alb_b["count"] == 1
        ids_b = [p["msg_id"] for p in view_b["wall"]]
        assert p1 not in ids_b                       # deleted, not resurrected standalone

        view_a = a.profile_view(a.identity_pub)
        alb_a = next(p for p in view_a["wall"] if p.get("album"))
        assert [ph["m"] for ph in alb_a["photos"]] == [p2]
        assert alb_a["count"] == 1
        ids_a = [p["msg_id"] for p in view_a["wall"]]
        assert p1 not in ids_a

        for s in (sa, sb):
            await s.stop()
    asyncio.run(scenario())
