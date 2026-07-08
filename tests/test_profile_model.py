from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import (
    ACCENTS, make_profile, validate_payload,
)


def device():
    d = DeviceKeys.create("phone")
    IdentityCeremony().enroll_first_device(d)
    return d


def test_rich_profile_valid_and_signed():
    d = device()
    m = make_profile(d, "Wong", bio="Designer i Kbh",
                     accent="#2743d6", avatar="ab" * 32,
                     avatar_shape="squircle", avatar_size="l",
                     avatar_align="center", banner="cd" * 32, now=5.0)
    assert m.payload["name"] == "Wong" and m.payload["bio"] == "Designer i Kbh"
    assert m.payload["accent"] == "#2743d6"
    assert m.payload["avatar"] == "ab" * 32
    assert m.payload["avatar_shape"] == "squircle"
    assert validate_payload(m.payload) == (True, "ok")
    assert m.verify_device_signature()


def test_name_only_profile_still_valid():
    # An old-style profile with only name+created_at must still pass.
    assert validate_payload({"kind": "profile", "name": "Wong",
                             "created_at": 1.0}) == (True, "ok")


def test_defaults_when_fields_absent():
    d = device()
    m = make_profile(d, "Wong")
    p = m.payload
    assert (p["bio"], p["accent"], p["avatar"]) == ("", "#2743d6", None)
    assert (p["avatar_shape"], p["avatar_size"], p["avatar_align"]) == \
        ("circle", "m", "left")
    assert p["banner"] is None


def test_invalid_profiles_rejected():
    ok = lambda p: validate_payload(p)[0]
    base = {"kind": "profile", "name": "Wong", "created_at": 1.0}
    assert ok({**base, "accent": "#ffffff"})            # any valid hex now ok
    assert ok({**base, "accent": "#abc123"})            # custom hex ok
    assert not ok({**base, "accent": "cobalt"})         # not hex -> rejected
    assert not ok({**base, "accent": "#fff"})           # short hex rejected
    assert not ok({**base, "accent": "#GG1234"})        # non-hex chars rejected
    assert not ok({**base, "bio": "x" * 241})
    assert not ok({**base, "avatar_shape": "hexagon"})
    assert not ok({**base, "avatar_size": "xl"})
    assert not ok({**base, "avatar_align": "top"})
    assert not ok({**base, "avatar": "zz"})             # bad hash
    assert not ok({**base, "banner": "zz"})
    assert not ok({**base, "name": ""})
    assert ACCENTS[0] == "#2743d6"                      # palette order pinned


def test_accent_membership_case_insensitive_not_required():
    # We store/validate lowercase hex; uppercase is rejected in v1 (strict).
    assert validate_payload({"kind": "profile", "name": "W",
                             "accent": "#2743D6", "created_at": 1.0})[0] \
        is False
