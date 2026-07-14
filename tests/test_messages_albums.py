"""KIND_ALBUM validation (collage Slice C): a mutable grouping record
over immutable photo posts - opaque ids only, latest-wins per
(identity, album_id)."""
from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import KIND_ALBUM, MAX_LAYOUT, make_album, validate_payload

AID = "ab" * 32
MID = "cd" * 32


def _mk(members, album_id=AID, dev=None):
    if dev is None:
        dev = DeviceKeys.create("Anna")
        IdentityCeremony().enroll_first_device(dev)
    return make_album(dev, album_id, members)


def test_album_roundtrip_valid():
    m = _mk([MID, "ef" * 32])
    ok, why = validate_payload(m.payload)
    assert ok, why
    assert m.payload["kind"] == KIND_ALBUM
    assert m.payload["album_id"] == AID
    assert m.payload["members"] == [MID, "ef" * 32]


def test_empty_members_valid_ungroup():
    ok, why = validate_payload(_mk([]).payload)
    assert ok, why


def test_bad_albums_rejected():
    ok, _ = validate_payload(_mk([MID], album_id="zz").payload)
    assert not ok
    ok, _ = validate_payload(_mk(["nothex"]).payload)
    assert not ok
    ok, _ = validate_payload(_mk([MID, MID]).payload)          # duplicate member
    assert not ok
    ok, _ = validate_payload(_mk([MID] + ["%064x" % i for i in range(MAX_LAYOUT)]).payload)
    assert not ok
