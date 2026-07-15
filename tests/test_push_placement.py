"""One placement rule: anchor at target, push only overlapped blocks
straight down, deterministically (spec 2026-07-14 dynamic placement)."""
import os
import subprocess
import tempfile

import pytest

from hearth.node import HearthNode
from tests.test_imagegate import png_bytes


def clip(seconds=1):
    # copied from tests/test_profile_video.py (itself from
    # tests/test_node_story.py): synthetic mp4 via imageio_ffmpeg's
    # bundled ffmpeg, for composing a real video post.
    import imageio_ffmpeg
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    p = os.path.join(tempfile.mkdtemp(), "c.mp4")
    subprocess.run([ff, "-f", "lavfi", "-i",
        f"testsrc=size=480x360:rate=24:duration={seconds}",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", p],
        check=True, capture_output=True)
    return open(p, "rb").read()


def _node(tmp_path):
    return HearthNode.create(tmp_path / "n", "Anna", "anna-pc")


def _post(n, txt="p"):
    return n.compose_post(txt, scope="kreds", placement="profile")


def _pins(n):
    return n.store.profile_layout(n.identity_pub)["pins"]


def test_create_auto_places_at_top_dense(tmp_path):
    n = _node(tmp_path)
    a = _post(n, "a")                       # text default 4x1 at (0,0)
    assert _pins(n)[a] == {"x": 0, "y": 0, "w": 4, "h": 1}
    b = _post(n, "b")                       # pushes a down by 1
    assert _pins(n)[b] == {"x": 0, "y": 0, "w": 4, "h": 1}
    assert _pins(n)[a] == {"x": 0, "y": 1, "w": 4, "h": 1}


def test_create_with_span_fields_and_dense_beside(tmp_path):
    n = _node(tmp_path)
    a = n.compose_post("a", scope="kreds", placement="profile",
                       span_w=1, span_h=1)
    n.set_block_pin(a, 3, 0, 1, 1)          # park it top-right
    b = n.compose_post("b", scope="kreds", placement="profile",
                       span_w=2, span_h=2)  # lands (0,0); a NOT in the way
    assert _pins(n)[b] == {"x": 0, "y": 0, "w": 2, "h": 2}
    assert _pins(n)[a] == {"x": 3, "y": 0, "w": 1, "h": 1}   # never moved


def test_pin_onto_occupied_pushes_cascade(tmp_path):
    n = _node(tmp_path)
    a = _post(n, "a")                       # ends at... build explicit:
    b = _post(n, "b")
    c = _post(n, "c")
    n.set_block_pin(a, 0, 0, 4, 1)
    n.set_block_pin(b, 0, 1, 4, 1)
    n.set_block_pin(c, 0, 2, 4, 1)
    # drop c on top: a and b cascade down below it, in order
    n.set_block_pin(c, 0, 0, 4, 2)
    p = _pins(n)
    assert p[c] == {"x": 0, "y": 0, "w": 4, "h": 2}
    assert p[a] == {"x": 0, "y": 2, "w": 4, "h": 1}
    assert p[b] == {"x": 0, "y": 3, "w": 4, "h": 1}


def test_non_colliding_never_move(tmp_path):
    n = _node(tmp_path)
    a = _post(n, "a")
    b = _post(n, "b")
    n.set_block_pin(a, 0, 5, 2, 2)
    n.set_block_pin(b, 2, 0, 1, 1)          # nowhere near a
    assert _pins(n)[a] == {"x": 0, "y": 5, "w": 2, "h": 2}


def test_row_cap_400s(tmp_path):
    n = _node(tmp_path)
    a = _post(n, "a")
    with pytest.raises(ValueError):
        n.set_block_pin(a, 0, 501, 1, 1)    # beyond MAX_LAYOUT rows


def test_ungroup_top_inserts_members(tmp_path):
    n = _node(tmp_path)
    p1 = n.compose_post("one", scope="kreds", placement="profile",
                        photos=[png_bytes(8, 8)])
    p2 = n.compose_post("two", scope="kreds", placement="profile",
                        photos=[png_bytes(8, 8)])
    solo = _post(n, "solo")                  # occupies the top
    aid = n.set_album([p1, p2])
    n.set_album([], album_id=aid)            # ungroup
    p = _pins(n)
    assert p[p2]["y"] == 0                   # newest restored member on top
    assert p[p1]["y"] >= p[p2]["y"]          # older below or beside
    assert solo in p                          # pushed, still pinned
    assert aid not in p                       # album pin gone


def test_auto_place_unplaced_single_publish(tmp_path, monkeypatch):
    """Regression (root-caused 2026-07-15): candidates used to sort by
    created_at alone, so a same-second tie fell back to Python's stable
    sort preserving posts_by's build order - which is newest-first - and
    that INVERTED the newest-on-top contract (the older post ended on
    top). Forcing both composes onto the identical time.time() value
    (rather than hoping two back-to-back composes land in the same
    tick, which only happened ~80% of the time on this machine) pins the
    tie case deterministically on every machine, not just fast ones."""
    monkeypatch.setattr("hearth.node.time.time", lambda: 1_700_000_000.0)
    n = _node(tmp_path)
    a = _post(n, "a")
    b = _post(n, "b")
    # simulate legacy: strip their pins via a raw layout write
    cur = n.store.profile_layout(n.identity_pub)
    from hearth.messages import make_profile_layout
    n._publish(make_profile_layout(n.device, cur["order"],
                                   grids=cur["grids"], sizes=cur["sizes"],
                                   pins={}, spans={a: {"w": 4, "h": 1},
                                                   b: {"w": 4, "h": 1}},
                                   texts=cur["texts"]))
    count_before = len(n.store.albums(n.identity_pub))  # noqa - just touch
    placed = n.auto_place_unplaced()
    assert placed == 2
    p = _pins(n)
    assert p[b]["y"] == 0                    # newest on top
    assert p[a]["y"] == 1
    assert n.auto_place_unplaced() == 0      # idempotent, no extra publish


def test_autoplace_skips_shadowed_album(tmp_path):
    """Two own devices minting albums around the same post offline can
    leave two albums sharing a member; profile_view folds the shared
    member into the lexically-smallest album_id and never renders the
    other album at all. auto_place_unplaced must gate candidacy on that
    SAME fold - otherwise it can pin the shadowed album: a permanent
    invisible hole in the grid, unreachable by any UI (review finding).
    Mint order is deliberately the REVERSE of lexical order (larger id
    first) so the test can't pass by accident on publish/ingest order."""
    n = _node(tmp_path)
    p1 = n.compose_post("shared", scope="kreds", placement="profile",
                        photos=[png_bytes(8, 8)])
    big_id = "b" * 64
    small_id = "a" * 64
    n.set_album([p1], album_id=big_id)              # minted first, larger id
    n.set_album([p1], album_id=small_id)             # same sole member, smaller id
    # simulate legacy: strip all pins via a raw layout write (established
    # simulation, same idiom as test_auto_place_unplaced_single_publish).
    cur = n.store.profile_layout(n.identity_pub)
    from hearth.messages import make_profile_layout
    n._publish(make_profile_layout(n.device, cur["order"],
                                   grids=cur["grids"], sizes=cur["sizes"],
                                   pins={}, spans={}, texts=cur["texts"]))
    placed = n.auto_place_unplaced()
    p = _pins(n)
    assert placed == 1                    # only the winning album is a candidate
    assert small_id in p                  # lexically-smallest wins the fold
    assert big_id not in p                # shadowed album never gets pinned
    assert p1 not in p                    # the member itself is not standalone


def test_grow_pinned_album_leaves_wall_undisturbed(tmp_path):
    """Deck grow (smoke-caught fix): an album-bound photo post composed
    with auto_place=False is deck CONTENT, not a wall block - it must
    not push anything. After compose(auto_place=False) + set_album, the
    album's pin and every other pin are byte-identical to before, and
    the new member never appears in pins or spans."""
    n = _node(tmp_path)
    other = _post(n, "other")                       # a block elsewhere
    p1 = n.compose_post("one", scope="kreds", placement="profile",
                        photos=[png_bytes(8, 8)])
    aid = n.set_album([p1])                         # album inherits p1's pin
    n.set_block_pin(other, 0, 0, 4, 1)
    n.set_block_pin(aid, 0, 3, 2, 2)                # known geometry, off-top
    before = n.store.profile_layout(n.identity_pub)["pins"]
    new = n.compose_post("", scope="kreds", placement="profile",
                         photos=[png_bytes(8, 8)], auto_place=False)
    assert _pins(n) == before                       # compose alone: no touch
    n.set_album([p1, new], album_id=aid)            # grow
    lay = n.store.profile_layout(n.identity_pub)
    assert lay["pins"] == before                    # wall undisturbed
    assert lay["pins"][aid] == {"x": 0, "y": 3, "w": 2, "h": 2}
    assert lay["pins"][other] == {"x": 0, "y": 0, "w": 4, "h": 1}
    assert new not in lay["pins"] and new not in lay["spans"]


def test_video_post_auto_places_media_default(tmp_path):
    """A profile video post without composer w/h gets the media default
    2x2 auto-pin at the top (spec 2026-07-14), same as a photo post."""
    n = _node(tmp_path)
    mid = n.compose_post("clip", scope="kreds", placement="profile",
                         video=clip(1))
    assert _pins(n)[mid] == {"x": 0, "y": 0, "w": 2, "h": 2}


def test_wall_full_compose_orphans_post_unplaced(tmp_path):
    """The cascade raise site, from compose (spec 2026-07-14): the post
    is ALREADY published when auto-place runs, so a wall-full push gives
    the caller a ValueError (400, no msg_id) while the post EXISTS
    orphaned-unplaced - no pin, no span - degrading honestly to the
    legacy flow-below rendering until /api/wall-autoplace adopts it.
    The trigger is deliberately NOT 500-posts-scale: a contiguous pinned
    chain ending in one block AT y=MAX_LAYOUT means the very next
    overlapping auto-place cascades past the cap."""
    from hearth.messages import MAX_LAYOUT, make_profile_layout
    n = _node(tmp_path)
    # Synthetic full-height stack (raw layout write, same idiom as the
    # autoplace test above): full-width blocks tiling rows 0..499
    # contiguously, then one block AT the row cap. Any push of the chain
    # crosses MAX_LAYOUT.
    pins, y, i = {}, 0, 0
    while y + 8 <= 496:
        pins["%064x" % i] = {"x": 0, "y": y, "w": 4, "h": 8}
        y, i = y + 8, i + 1
    pins["%064x" % i] = {"x": 0, "y": 496, "w": 4, "h": 4}   # rows 496-499
    pins["ff" * 32] = {"x": 0, "y": MAX_LAYOUT, "w": 4, "h": 1}
    cur = n.store.profile_layout(n.identity_pub)
    n._publish(make_profile_layout(n.device, cur["order"],
                                   grids=cur["grids"], sizes=cur["sizes"],
                                   pins=pins, spans={}, texts=cur["texts"]))
    with pytest.raises(ValueError):
        n.compose_post("boom", scope="kreds", placement="profile")
    # The orphan-degrades contract: the post exists, unplaced on the wall.
    wall = n.posts_by(n.identity_pub, "profile")
    boom = next(p for p in wall if p["text"] == "boom")
    lay = n.store.profile_layout(n.identity_pub)
    assert boom["msg_id"] not in lay["pins"]
    assert boom["msg_id"] not in lay["spans"]
    assert lay["pins"] == pins                # layout untouched by the failure
    view_row = next(p for p in n.profile_view(n.identity_pub)["wall"]
                    if p["msg_id"] == boom["msg_id"])
    assert view_row["pin"] is None            # flow-below fallback renders it
    assert view_row["span"] == {"w": 4, "h": 1}
    # ...and wall-autoplace would adopt it later; here it must raise too
    # (the wall IS full), never silently drop the orphan.
    with pytest.raises(ValueError):
        n.auto_place_unplaced()
