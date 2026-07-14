"""Tests for KIND_PROFILE_LAYOUT: a signed, latest-wins record (mirrors the
profile record) holding an ordered list of block msg_ids. `single_node` is
defined here as a local pytest fixture (no such fixture exists in the
repo). The API endpoint's input handling is covered in
test_api_profile_layout below.

Retired (spec 2026-07-13, collage Slice A): `order` no longer arranges the
profile wall -- profile_view renders newest-first always, geometry-ruled
by pins/spans instead (proven single-node in tests/test_block_pins.py).
`order` still rides the wire for back-compat, so the tests below that
used to prove view-ordering now prove (a) the wall really is newest-first
regardless of `order`, and (b) the order record itself is still
latest-wins at the store layer."""
import pytest

from fastapi.testclient import TestClient

from hearth.api import build_app
from hearth.node import HearthNode


@pytest.fixture
def single_node(tmp_path):
    return HearthNode.create(tmp_path / "n", "Wong", "wong-phone")


def test_wall_stays_newest_first_despite_explicit_order(single_node, monkeypatch):
    """Regression: composing 3 posts back-to-back can land on the same
    time.time() value (fast hardware / coarse clock granularity), which
    made the created_at DESC ordering ties non-deterministic and flaked
    this test ~1-in-5..30 runs. Monkeypatch time.time() (as read by
    hearth.node, which calls it once per compose_post to stamp created_at)
    so each call returns a strictly increasing value -- A, B, C get
    unambiguous distinct timestamps and "newest-first" is deterministic,
    without weakening the assertion below.

    Stale-docstring correction (final Slice A review): post_messages now
    tie-breaks a created_at collision by rowid DESC (arrival order, see
    hearth/store.py) -- the monkeypatched clock here is belt-and-braces,
    not load-bearing for determinism anymore.

    Retired (spec 2026-07-13): explicit order used to reshuffle the wall;
    now it's wire-compat only (see module docstring) -- the wall is
    newest-first before AND after set_profile_layout."""
    clock = iter(1_700_000_000.0 + i * 0.01 for i in range(10_000))
    monkeypatch.setattr("hearth.node.time.time", lambda: next(clock))
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    b = n.compose_post("B", scope="kreds", placement="profile")
    c = n.compose_post("C", scope="kreds", placement="profile")
    # default: newest-first (C, B, A)
    assert [p["text"] for p in n.profile_view(n.identity_pub)["wall"]] == ["C", "B", "A"]
    n.set_profile_layout([a, c, b])            # explicit order -- inert for rendering
    assert [p["text"] for p in n.profile_view(n.identity_pub)["wall"]] == ["C", "B", "A"]
    assert n.store.profile_layout(n.identity_pub)["order"] == [a, c, b]  # record still carries it


def test_wall_stays_newest_first_with_or_without_a_layout_record(single_node, monkeypatch):
    """Retired (spec 2026-07-13): a fresh post used to be prepended ahead
    of an "arranged" set; now every post renders newest-first regardless
    of whether a layout record exists at all.

    Same monkeypatched clock as the sibling test above: back-to-back
    composes can share a time.time() value. Stale-docstring correction
    (final Slice A review): post_messages now tie-breaks a created_at
    collision by rowid DESC (see hearth/store.py), so the strict
    ["C","B","A"] ordering no longer strictly needs distinct timestamps to
    be deterministic -- the monkeypatched clock is kept here as
    belt-and-braces, not because the tie-break is missing."""
    clock = iter(1_700_000_000.0 + i * 0.01 for i in range(10_000))
    monkeypatch.setattr("hearth.node.time.time", lambda: next(clock))
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    b = n.compose_post("B", scope="kreds", placement="profile")
    n.set_profile_layout([a, b])               # wire-compat only, doesn't arrange
    c = n.compose_post("C", scope="kreds", placement="profile")   # newest
    assert [p["text"] for p in n.profile_view(n.identity_pub)["wall"]] == ["C", "B", "A"]


def test_unknown_id_in_layout_skipped(single_node):
    # A syntactically-valid but nonexistent id in `order` must not error --
    # true before the retirement (it was skipped when shaping the wall) and
    # still true now (order isn't read by profile_view at all any more).
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    n.set_profile_layout(["f" * 64, a])        # no error
    assert [p["text"] for p in n.profile_view(n.identity_pub)["wall"]] == ["A"]


def test_layout_latest_wins(single_node):
    """Retired for view-shaping (order no longer changes wall order --
    see test_wall_stays_newest_first_with_or_without_a_layout_record).
    This proves latest-wins still holds at the record layer
    (store.profile_layout), the wire-compat guarantee that survives."""
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    b = n.compose_post("B", scope="kreds", placement="profile")
    n.set_profile_layout([a, b])
    n.set_profile_layout([b, a])
    assert n.store.profile_layout(n.identity_pub)["order"] == [b, a]


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
