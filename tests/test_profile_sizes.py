"""Tests for per-block bento sizes on the profile wall (Kreds profile bento,
Task 1). Extends KIND_PROFILE_LAYOUT (tests/test_profile_layout.py,
tests/test_profile_grids.py) with a `sizes` map `{msg_id: "small"|"wide"|
"full"}` alongside `order` and `grids`: a photo block's bento width is
re-stylable because it lives in this mutable record, not the immutable
post. `single_node` mirrors the fixture in tests/test_profile_grids.py."""
import pytest

from fastapi.testclient import TestClient

from hearth.api import build_app
from hearth.node import HearthNode


@pytest.fixture
def single_node(tmp_path):
    return HearthNode.create(tmp_path / "n", "Wong", "wong-phone")


def test_set_block_size_annotates_and_preserves_order_and_grids(single_node):
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    b = n.compose_post("B", scope="kreds", placement="profile")
    n.set_profile_layout([a, b]); n.set_block_grid(a, "cols3")
    n.set_block_size(a, "wide")
    wall = {p["msg_id"]: p for p in n.profile_view(n.identity_pub)["wall"]}
    assert wall[a]["size"] == "wide" and wall[b]["size"] == "full"   # default full
    assert wall[a]["grid"] == "cols3"                                # grid preserved
    assert [p["msg_id"] for p in n.profile_view(n.identity_pub)["wall"]] == [a, b]  # order kept


def test_reorder_and_grid_preserve_sizes(single_node):
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    b = n.compose_post("B", scope="kreds", placement="profile")
    n.set_block_size(a, "small")
    n.set_profile_layout([b, a])                       # reorder
    n.set_block_grid(a, "hero")                        # grid change
    wall = {p["msg_id"]: p for p in n.profile_view(n.identity_pub)["wall"]}
    assert wall[a]["size"] == "small"                  # size survived both


def test_set_block_size_full_clears(single_node):
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    n.set_block_size(a, "wide"); n.set_block_size(a, "full")
    assert n.store.profile_layout(n.identity_pub)["sizes"] == {}   # default clears entry


def test_set_block_size_rejects_bad(single_node):
    a = single_node.compose_post("A", scope="kreds", placement="profile")
    with pytest.raises(ValueError): single_node.set_block_size(a, "huge")
    with pytest.raises(ValueError): single_node.set_block_size("nothex", "wide")


def test_layout_sizes_validation():
    from hearth.messages import validate_payload, KIND_PROFILE_LAYOUT
    base = {"kind": KIND_PROFILE_LAYOUT, "created_at": 1.0, "order": []}
    ok,_ = validate_payload({**base, "sizes": {"a"*64: "wide"}}); assert ok
    ok,_ = validate_payload({**base}); assert ok                       # missing sizes ok
    ok,_ = validate_payload({**base, "sizes": {"a"*64: "huge"}}); assert not ok
    ok,_ = validate_payload({**base, "sizes": {"nothex": "wide"}}); assert not ok
    ok,_ = validate_payload({**base, "sizes": ["x"]}); assert not ok    # not a dict


def test_api_block_size(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    c = TestClient(build_app(node))
    a = node.compose_post("A", scope="kreds", placement="profile")
    assert c.post("/api/block-size", json={"msg_id": a, "size": "wide"}).status_code == 200
    assert c.post("/api/block-size", json={"msg_id": a, "size": "huge"}).status_code == 400
