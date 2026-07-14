"""set_album validation + profile_view folding (collage Slice C)."""
import pytest

from hearth.node import HearthNode

# Fake PNG magic bytes -- compose_post never decodes photo bytes (unlike
# compose_story's transcode gate), so any bytes work; reuses the exact
# literal test_block_pins.py's photo test passes to `photos=`.
PNG = b"\x89PNG fake"


def _node(tmp_path):
    return HearthNode.create(tmp_path / "n", "Anna", "anna-pc")


def _photo_post(n, text="p"):
    return n.compose_post(text, scope="kreds", placement="profile",
                          photos=[PNG])


def test_set_album_mints_and_validates(tmp_path):
    n = _node(tmp_path)
    a = _photo_post(n)
    b = _photo_post(n)
    aid = n.set_album([a, b])
    assert len(aid) == 64
    assert n.store.albums(n.identity_pub)[aid] == [a, b]
    aid2 = n.set_album([b, a], album_id=aid)      # reorder in place
    assert aid2 == aid
    assert n.store.albums(n.identity_pub)[aid] == [b, a]


def test_set_album_rejects_bad_members(tmp_path):
    n = _node(tmp_path)
    a = _photo_post(n)
    t = n.compose_post("text only", scope="kreds", placement="profile")
    j = n.compose_post("journal", scope="kreds", placement="journal")
    with pytest.raises(ValueError):
        n.set_album([a, a])                        # duplicate
    with pytest.raises(ValueError):
        n.set_album(["zz"])                        # malformed id
    with pytest.raises(ValueError):
        n.set_album([a, "ff" * 32])                # unknown post
    with pytest.raises(ValueError):
        n.set_album([j])                           # journal placement
    with pytest.raises(ValueError):
        n.set_album([t])                           # no photos
    with pytest.raises(ValueError):
        n.set_album([], album_id=None)             # empty needs an id (ungroup only)


def test_profile_view_folds_members_into_album(tmp_path):
    n = _node(tmp_path)
    a = _photo_post(n, "one")
    b = _photo_post(n, "two")
    solo = _photo_post(n, "solo")
    aid = n.set_album([a, b])
    view = n.profile_view(n.identity_pub)
    ids = [p["msg_id"] for p in view["wall"]]
    assert a not in ids and b not in ids           # suppressed standalone
    assert solo in ids
    alb = next(p for p in view["wall"] if p.get("album"))
    assert alb["msg_id"] == aid
    assert [ph["m"] for ph in alb["photos"]] == [a, b]
    assert alb["count"] == 2
    assert alb["span"] == {"w": 2, "h": 2}
    assert alb["mine"] is True
    assert alb["scope_newest"] == "kreds"


def test_ungroup_restores_standalone(tmp_path):
    n = _node(tmp_path)
    a = _photo_post(n)
    aid = n.set_album([a])
    n.set_album([], album_id=aid)
    view = n.profile_view(n.identity_pub)
    ids = [p["msg_id"] for p in view["wall"]]
    assert a in ids
    assert not any(p.get("album") for p in view["wall"])
