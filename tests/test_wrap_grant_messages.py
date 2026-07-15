"""wrap_grant record kind (spec 2026-07-15-wall-wrap-grants): an
author-signed bundle of extra sealed content-key wraps for an existing
post. Shape-only validation here, like every other kind."""
from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import (KIND_WRAP_GRANT, make_wrap_grant,
                             validate_payload)


def _device():
    d = DeviceKeys.create("test-dev")
    IdentityCeremony().enroll_first_device(d)
    return d


def _wrap_entry(enc_pub=None):
    w = {"eph_pub": "ab" * 32, "nonce": "cd" * 12, "wrapped_key": "ef" * 48}
    if enc_pub is not None:
        w["enc_pub"] = enc_pub
    return w


def test_make_wrap_grant_shape_and_validation():
    d = _device()
    msg = make_wrap_grant(d, "aa" * 32, {"bb" * 32: _wrap_entry()})
    p = msg.payload
    assert p["kind"] == KIND_WRAP_GRANT
    assert p["target"] == "aa" * 32
    ok, why = validate_payload(p)
    assert ok, why


def test_wrap_grant_enc_pub_annotation_allowed():
    d = _device()
    msg = make_wrap_grant(d, "aa" * 32,
                          {"bb" * 32: _wrap_entry(enc_pub="11" * 32)})
    ok, why = validate_payload(msg.payload)
    assert ok, why


def test_wrap_grant_bad_shapes_refused():
    base = {"kind": KIND_WRAP_GRANT, "created_at": 1.0}
    good_wraps = {"bb" * 32: _wrap_entry()}
    assert not validate_payload({**base, "target": "zz",
                                 "wraps": good_wraps})[0]      # bad target
    assert not validate_payload({**base, "target": "aa" * 32,
                                 "wraps": {}})[0]              # empty wraps
    assert not validate_payload({**base, "target": "aa" * 32,
                                 "wraps": {"bb" * 32: {}}})[0]  # bad entry
    bad_enc = {"bb" * 32: _wrap_entry(enc_pub="not-hex")}
    assert not validate_payload({**base, "target": "aa" * 32,
                                 "wraps": bad_enc})[0]         # bad enc_pub


def test_unknown_kind_still_refused():
    # the fallthrough old peers rely on must survive this branch addition
    ok, why = validate_payload({"kind": "nonsense", "created_at": 1.0})
    assert not ok and why == "unknown kind"
