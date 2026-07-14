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


def test_member_claimed_by_two_albums_resolves_deterministically(tmp_path):
    # A member claimed by TWO album records must fold into the
    # lexically-smallest album_id on EVERY device: dict order out of
    # store.albums() is SQL scan order (per-device ingest history), so
    # first-wins by insertion order would let synced devices disagree.
    # Insertion order here deliberately OPPOSES lexical order ("ff" first).
    n = _node(tmp_path)
    a = _photo_post(n)
    n.set_album([a], album_id="ff" * 32)
    n.set_album([a], album_id="00" * 32)
    view = n.profile_view(n.identity_pub)
    albs = [p for p in view["wall"] if p.get("album")]
    assert len(albs) == 1                          # loser album: zero photos, absent
    assert albs[0]["msg_id"] == "00" * 32          # lexically smallest wins
    assert [ph["m"] for ph in albs[0]["photos"]] == [a]
    assert a not in [p["msg_id"] for p in view["wall"]]   # never standalone


def test_ungroup_restores_standalone(tmp_path):
    n = _node(tmp_path)
    a = _photo_post(n)
    aid = n.set_album([a])
    n.set_album([], album_id=aid)
    view = n.profile_view(n.identity_pub)
    ids = [p["msg_id"] for p in view["wall"]]
    assert a in ids
    assert not any(p.get("album") for p in view["wall"])


# --- album/pin-map interplay (review finding: albums and the pin map now
# talk to each other in set_album) ---

def test_mint_from_pinned_single_photo_inherits_pin(tmp_path):
    n = _node(tmp_path)
    a = _photo_post(n)
    n.set_block_pin(a, 1, 2, 2, 2)
    aid = n.set_album([a])
    layout = n.store.profile_layout(n.identity_pub)
    assert layout["pins"].get(aid) == {"x": 1, "y": 2, "w": 2, "h": 2}
    assert a not in layout["pins"]                 # never independently placed
    assert layout["spans"][a] == {"w": 2, "h": 2}  # kept size, unplaced


def test_group_two_pinned_posts_leaves_album_unplaced(tmp_path):
    n = _node(tmp_path)
    a = _photo_post(n)
    b = _photo_post(n)
    n.set_block_pin(a, 0, 0, 1, 1)
    n.set_block_pin(b, 2, 0, 1, 1)
    aid = n.set_album([a, b])
    layout = n.store.profile_layout(n.identity_pub)
    assert aid not in layout["pins"]                # no deterministic winner
    assert a not in layout["pins"] and b not in layout["pins"]
    assert layout["spans"][a] == {"w": 1, "h": 1}
    assert layout["spans"][b] == {"w": 1, "h": 1}


def test_ungroup_after_pin_inheritance_top_inserts(tmp_path):
    n = _node(tmp_path)
    a = _photo_post(n)
    n.set_block_pin(a, 1, 2, 2, 2)
    aid = n.set_album([a])
    n.set_album([], album_id=aid)                  # ungroup
    view = n.profile_view(n.identity_pub)
    member = next(p for p in view["wall"] if p["msg_id"] == a)
    # dynamic placement (spec 2026-07-14): ungroup top-inserts the
    # restored member via the push rule now, instead of leaving it
    # unplaced - no more limbo.
    assert member["pin"] == {"x": 0, "y": 0, "w": 2, "h": 2}
    assert member["span"] == {"w": 2, "h": 2}
