"""Tests for KIND_PROFILE_LAYOUT: a signed, latest-wins record (mirrors the
profile record) holding an ordered list of block msg_ids that arranges the
profile wall. `single_node` is defined here as a local pytest fixture (no
such fixture exists in the repo); layout ordering is single-author render
logic. The API endpoint's input handling is covered in
test_api_profile_layout below."""
import pytest

from fastapi.testclient import TestClient

from hearth.api import build_app
from hearth.node import HearthNode


@pytest.fixture
def single_node(tmp_path):
    return HearthNode.create(tmp_path / "n", "Wong", "wong-phone")


def test_layout_orders_wall(single_node, monkeypatch):
    """Regression: composing 3 posts back-to-back can land on the same
    time.time() value (fast hardware / coarse clock granularity), which
    made the created_at DESC ordering ties non-deterministic and flaked
    this test ~1-in-5..30 runs. Monkeypatch time.time() (as read by
    hearth.node, which calls it once per compose_post to stamp created_at)
    so each call returns a strictly increasing value -- A, B, C get
    unambiguous distinct timestamps and "newest-first" is deterministic,
    without weakening the assertion below."""
    clock = iter(1_700_000_000.0 + i * 0.01 for i in range(10_000))
    monkeypatch.setattr("hearth.node.time.time", lambda: next(clock))
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    b = n.compose_post("B", scope="kreds", placement="profile")
    c = n.compose_post("C", scope="kreds", placement="profile")
    # default: newest-first (C, B, A)
    assert [p["text"] for p in n.profile_view(n.identity_pub)["wall"]] == ["C", "B", "A"]
    n.set_profile_layout([a, c, b])            # explicit order A, C, B
    assert [p["text"] for p in n.profile_view(n.identity_pub)["wall"]] == ["A", "C", "B"]


def test_unlisted_block_prepended_newest_first(single_node):
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    b = n.compose_post("B", scope="kreds", placement="profile")
    n.set_profile_layout([a, b])               # A, B arranged
    c = n.compose_post("C", scope="kreds", placement="profile")   # new, unlisted
    # fresh post surfaces on top, then the arranged order
    assert [p["text"] for p in n.profile_view(n.identity_pub)["wall"]] == ["C", "A", "B"]


def test_unknown_id_in_layout_skipped(single_node):
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    n.set_profile_layout(["f" * 64, a])        # unknown id skipped, no error
    assert [p["text"] for p in n.profile_view(n.identity_pub)["wall"]] == ["A"]


def test_layout_latest_wins(single_node):
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    b = n.compose_post("B", scope="kreds", placement="profile")
    n.set_profile_layout([a, b])
    n.set_profile_layout([b, a])
    assert [p["text"] for p in n.profile_view(n.identity_pub)["wall"]] == ["B", "A"]


def test_layout_validation():
    from hearth.messages import validate_payload, KIND_PROFILE_LAYOUT
    ok, _ = validate_payload(
        {"kind": KIND_PROFILE_LAYOUT, "created_at": 1.0, "order": ["a" * 64]})
    assert ok
    ok, _ = validate_payload(
        {"kind": KIND_PROFILE_LAYOUT, "created_at": 1.0, "order": ["nothex"]})
    assert not ok
    ok, _ = validate_payload(
        {"kind": KIND_PROFILE_LAYOUT, "created_at": 1.0, "order": "x"})
    assert not ok


def test_api_profile_layout_bad_input_is_400(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    c = TestClient(build_app(node))
    a = node.compose_post("A", scope="kreds", placement="profile")
    assert c.post("/api/profile-layout", json={"order": [a]}).status_code == 200
    # bad orders must be a caught 400, not an unhandled 500
    assert c.post("/api/profile-layout", json={"order": "notalist"}).status_code == 400
    assert c.post("/api/profile-layout", json={"order": ["nothex"]}).status_code == 400
    assert c.post("/api/profile-layout",
                  json={"order": ["a" * 64] * 501}).status_code == 400
