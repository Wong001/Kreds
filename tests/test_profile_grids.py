"""Tests for per-block grid layouts on the profile wall (Slice 3b, Task 1).

Extends KIND_PROFILE_LAYOUT (tests/test_profile_layout.py) with a `grids`
map `{msg_id: layout}` alongside `order`: a photo block's grid style is
re-stylable because it lives in this mutable record, not the immutable
post. `single_node` mirrors the fixture in tests/test_profile_layout.py.

Retired (spec 2026-07-13, collage Slice A): profile_view no longer
annotates wall entries with p["grid"], and `order` no longer shapes the
wall (see tests/test_profile_layout.py, tests/test_block_pins.py). The
two tests that used to read those annotations off profile_view now read
the grids/order maps straight off store.profile_layout(...) instead,
proving the record-carriage guarantee (wire back-compat) without
asserting retired view behavior."""
import pytest

from fastapi.testclient import TestClient

from hearth.api import build_app
from hearth.node import HearthNode


@pytest.fixture
def single_node(tmp_path):
    return HearthNode.create(tmp_path / "n", "Wong", "wong-phone")


def test_set_block_grid_record_preserves_order(single_node):
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    b = n.compose_post("B", scope="kreds", placement="profile")
    n.set_profile_layout([a, b])
    n.set_block_grid(a, "cols3")
    layout = n.store.profile_layout(n.identity_pub)
    assert layout["grids"].get(a) == "cols3" and b not in layout["grids"]  # default
    assert layout["order"] == [a, b]                    # order kept in the record


def test_reorder_preserves_grids_record(single_node):
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    b = n.compose_post("B", scope="kreds", placement="profile")
    n.set_block_grid(a, "hero")
    n.set_profile_layout([b, a])                       # reorder (wire-compat only)
    layout = n.store.profile_layout(n.identity_pub)
    assert layout["grids"][a] == "hero"                 # grid survived the reorder
    assert layout["order"] == [b, a]


def test_set_block_grid_auto_clears(single_node):
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    n.set_block_grid(a, "masonry"); n.set_block_grid(a, "auto")
    assert n.store.profile_layout(n.identity_pub)["grids"] == {}


def test_set_block_grid_rejects_bad(single_node):
    a = single_node.compose_post("A", scope="kreds", placement="profile")
    with pytest.raises(ValueError): single_node.set_block_grid(a, "wat")
    with pytest.raises(ValueError): single_node.set_block_grid("nothex", "cols2")


def test_layout_grids_validation():
    from hearth.messages import validate_payload, KIND_PROFILE_LAYOUT
    base = {"kind": KIND_PROFILE_LAYOUT, "created_at": 1.0, "order": []}
    ok,_ = validate_payload({**base, "grids": {"a"*64: "cols2"}}); assert ok
    ok,_ = validate_payload({**base}); assert ok                     # missing grids ok
    ok,_ = validate_payload({**base, "grids": {"a"*64: "wat"}}); assert not ok
    ok,_ = validate_payload({**base, "grids": {"nothex": "cols2"}}); assert not ok
    ok,_ = validate_payload({**base, "grids": ["x"]}); assert not ok  # not a dict


def test_api_block_grid(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    c = TestClient(build_app(node))
    a = node.compose_post("A", scope="kreds", placement="profile")
    assert c.post("/api/block-grid", json={"msg_id": a, "grid": "cols3"}).status_code == 200
    assert c.post("/api/block-grid", json={"msg_id": a, "grid": "wat"}).status_code == 400
