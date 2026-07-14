"""One placement rule: anchor at target, push only overlapped blocks
straight down, deterministically (spec 2026-07-14 dynamic placement)."""
import pytest

from hearth.node import HearthNode


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
                        photos=[b"\x89PNG fake"])
    p2 = n.compose_post("two", scope="kreds", placement="profile",
                        photos=[b"\x89PNG fake"])
    solo = _post(n, "solo")                  # occupies the top
    aid = n.set_album([p1, p2])
    n.set_album([], album_id=aid)            # ungroup
    p = _pins(n)
    assert p[p2]["y"] == 0                   # newest restored member on top
    assert p[p1]["y"] >= p[p2]["y"]          # older below or beside
    assert solo in p                          # pushed, still pinned
    assert aid not in p                       # album pin gone


def test_auto_place_unplaced_single_publish(tmp_path):
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
