"""Node-level pin/span/unpin + profile_view annotation (collage Slice A)."""
import pytest

from hearth.node import HearthNode


def _node(tmp_path):
    return HearthNode.create(tmp_path / "n", "Anna", "anna-pc")


def test_pin_roundtrip_and_carry_forward(tmp_path):
    n = _node(tmp_path)
    a = n.compose_post("first", scope="kreds", placement="profile")
    b = n.compose_post("second", scope="kreds", placement="profile")
    n.set_block_pin(a, 0, 0, 2, 2)
    n.set_block_span(b, 4, 1)
    lay = n.store.profile_layout(n.identity_pub)
    assert lay["pins"][a] == {"x": 0, "y": 0, "w": 2, "h": 2}
    assert lay["spans"][b] == {"w": 4, "h": 1}
    # a further unrelated layout write must not drop either map
    n.set_block_grid(b, "cols2")
    lay = n.store.profile_layout(n.identity_pub)
    assert lay["pins"][a] == {"x": 0, "y": 0, "w": 2, "h": 2}
    assert lay["spans"][b] == {"w": 4, "h": 1}


def test_pin_moves_geometry_out_of_spans(tmp_path):
    n = _node(tmp_path)
    a = n.compose_post("x", scope="kreds", placement="profile")
    n.set_block_span(a, 2, 2)
    n.set_block_pin(a, 1, 3, 2, 2)
    lay = n.store.profile_layout(n.identity_pub)
    assert a in lay["pins"] and a not in lay["spans"]


def test_unpin_keeps_size_as_span(tmp_path):
    n = _node(tmp_path)
    a = n.compose_post("x", scope="kreds", placement="profile")
    n.set_block_pin(a, 0, 0, 4, 3)
    n.unpin_block(a)
    lay = n.store.profile_layout(n.identity_pub)
    assert a not in lay["pins"]
    assert lay["spans"][a] == {"w": 4, "h": 3}


def test_bad_pin_rejected(tmp_path):
    n = _node(tmp_path)
    a = n.compose_post("x", scope="kreds", placement="profile")
    for bad in ((3, 0, 2, 1), (-1, 0, 1, 1), (0, -1, 1, 1), (0, 0, 0, 1),
                (0, 0, 1, 9)):
        with pytest.raises(ValueError):
            n.set_block_pin(a, *bad)
    with pytest.raises(ValueError):
        n.set_block_span(a, 5, 1)
    with pytest.raises(ValueError):
        n.set_block_pin("zz", 0, 0, 1, 1)


def test_span_on_pinned_block_rejected(tmp_path):
    # A pinned block's geometry lives in its pin; the modal routes pinned
    # resizes through set_block_pin. Refusing here keeps one source of truth.
    n = _node(tmp_path)
    a = n.compose_post("x", scope="kreds", placement="profile")
    n.set_block_pin(a, 0, 0, 1, 1)
    with pytest.raises(ValueError):
        n.set_block_span(a, 2, 2)


def test_profile_view_annotates_pin_and_span(tmp_path):
    n = _node(tmp_path)
    a = n.compose_post("pinned", scope="kreds", placement="profile")
    b = n.compose_post("spanned", scope="kreds", placement="profile")
    c = n.compose_post("legacy text", scope="kreds", placement="profile")
    n.set_block_pin(a, 2, 0, 2, 2)
    n.set_block_span(b, 1, 1)
    view = n.profile_view(n.identity_pub)
    by_id = {p["msg_id"]: p for p in view["wall"]}
    assert by_id[a]["pin"] == {"x": 2, "y": 0, "w": 2, "h": 2}
    assert by_id[a]["span"] == {"w": 2, "h": 2}       # pin implies its span
    assert by_id[b]["pin"] is None
    assert by_id[b]["span"] == {"w": 1, "h": 1}
    assert by_id[c]["pin"] is None
    assert by_id[c]["span"] == {"w": 4, "h": 1}       # legacy default, text


def test_profile_view_legacy_sizes_map_to_default_spans(tmp_path):
    n = _node(tmp_path)
    t = n.compose_post("text", scope="kreds", placement="profile")
    ph = n.compose_post("pic", scope="kreds", placement="profile",
                        photos=[b"\x89PNG fake"])
    n.set_block_size(t, "small")     # legacy Phase-A size
    view = n.profile_view(n.identity_pub)
    by_id = {p["msg_id"]: p for p in view["wall"]}
    assert by_id[t]["span"] == {"w": 1, "h": 1}       # small -> 1x1
    assert by_id[ph]["span"] == {"w": 4, "h": 2}      # full default, media


def test_wall_is_newest_first_regardless_of_order_map(tmp_path):
    n = _node(tmp_path)
    a = n.compose_post("older", scope="kreds", placement="profile")
    b = n.compose_post("newer", scope="kreds", placement="profile")
    n.set_profile_layout([a, b])     # legacy order says a first
    view = n.profile_view(n.identity_pub)
    ids = [p["msg_id"] for p in view["wall"]]
    assert ids.index(b) < ids.index(a)   # geometry rules; order map is inert
