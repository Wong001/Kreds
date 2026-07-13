"""Validation for the collage pins/spans maps on KIND_PROFILE_LAYOUT
(spec 2026-07-13 wall collage redesign, Slice A)."""
from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import make_profile_layout, validate_payload

MID = "ab" * 32   # a well-formed 64-hex msg_id


def _mk(**kw):
    dev = DeviceKeys.create("Anna")
    IdentityCeremony().enroll_first_device(dev)
    return make_profile_layout(dev, [], **kw)


def _valid(msg):
    ok, why = validate_payload(msg.payload)
    return ok, why


def test_pins_and_spans_roundtrip_valid():
    m = _mk(pins={MID: {"x": 0, "y": 2, "w": 4, "h": 3}},
            spans={"cd" * 32: {"w": 2, "h": 2}})
    ok, why = _valid(m)
    assert ok, why
    assert m.payload["pins"][MID] == {"x": 0, "y": 2, "w": 4, "h": 3}
    assert m.payload["spans"]["cd" * 32] == {"w": 2, "h": 2}


def test_layout_without_pins_still_valid():
    # Backward compat: every existing record has no pins/spans fields.
    ok, why = _valid(_mk())
    assert ok, why


def test_pin_out_of_bounds_rejected():
    for bad in (
        {"x": 3, "y": 0, "w": 2, "h": 1},    # x+w > 4
        {"x": -1, "y": 0, "w": 1, "h": 1},   # x < 0
        {"x": 0, "y": -1, "w": 1, "h": 1},   # y < 0
        {"x": 0, "y": 0, "w": 0, "h": 1},    # w < 1
        {"x": 0, "y": 0, "w": 5, "h": 1},    # w > 4
        {"x": 0, "y": 0, "w": 1, "h": 9},    # h > MAX_BLOCK_H
        {"x": 0, "y": 0, "w": 1},            # missing h
        {"x": 0, "y": 0, "w": 1, "h": True}, # bool is not a size
        {"x": 0.5, "y": 0, "w": 1, "h": 1},  # float is not a cell
    ):
        m = _mk(pins={MID: bad})
        ok, _ = _valid(m)
        assert not ok, f"pin {bad} must be rejected"


def test_span_bad_shapes_rejected():
    for bad in ({"w": 5, "h": 1}, {"w": 1}, {"w": 1, "h": 0},
                {"x": 0, "y": 0, "w": 1, "h": 1}):   # spans carry NO x/y
        m = _mk(spans={MID: bad})
        ok, _ = _valid(m)
        assert not ok, f"span {bad} must be rejected"


def test_pin_key_must_be_hex64():
    m = _mk(pins={"zz": {"x": 0, "y": 0, "w": 1, "h": 1}})
    ok, _ = _valid(m)
    assert not ok
