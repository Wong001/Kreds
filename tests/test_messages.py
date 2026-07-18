import time

from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import (
    KIND_POST, KIND_RING, blob_hash, is_expired, make_delete, make_post,
    make_profile, make_ring, validate_payload,
)


def device():
    d = DeviceKeys.create("phone")
    IdentityCeremony().enroll_first_device(d)
    return d


def test_profile_payload_valid():
    d = device()
    assert validate_payload(make_profile(d, "Wong").payload) == (True, "ok")


def test_invalid_payloads_rejected():
    ok = lambda p: validate_payload(p)[0]
    assert not ok({"kind": "post", "created_at": 1.0})            # no text
    assert not ok({"kind": "post", "text": "x" * 4001,
                   "blobs": [], "created_at": 1.0})               # too long
    assert not ok({"kind": "post", "text": "x", "blobs": ["zz"],
                   "created_at": 1.0})                            # bad ref
    assert not ok({"kind": "profile", "name": "", "created_at": 1.0})
    assert not ok({"kind": "delete", "target": "nope", "created_at": 1.0})
    assert not ok({"kind": "wat", "created_at": 1.0})
    assert not ok({"kind": "post", "text": "x", "blobs": []})     # no ts


def test_expiry():
    now = time.time()
    p = {"kind": "post", "text": "x", "blobs": [],
         "created_at": now, "expires_at": now - 1}
    assert is_expired(p) is True
    p["expires_at"] = now + 3600
    assert is_expired(p) is False
    p["expires_at"] = None
    assert is_expired(p) is False


def _dev():
    d = DeviceKeys.create("phone")
    IdentityCeremony().enroll_first_device(d)
    return d


def test_make_post_scoped_encrypted_shape():
    d = _dev()
    m = make_post(d, "kreds", body_nonce="ab" * 12, body_ct="deadbeef",
                  wraps={}, blob_refs=[], created_at=100.0)
    p = m.payload
    assert p["kind"] == KIND_POST and p["scope"] == "kreds"
    assert p["body_nonce"] == "ab" * 12 and p["body_ct"] == "deadbeef"
    assert p["wraps"] == {} and p["blobs"] == []
    ok, why = validate_payload(p)
    assert ok, why


def test_post_rejects_bad_scope_and_missing_fields():
    d = _dev()
    ok, _ = validate_payload({"kind": "post", "scope": "open",
                              "body_nonce": "ab" * 12, "body_ct": "de",
                              "wraps": {}, "blobs": [], "created_at": 1.0})
    assert not ok                                        # "open" not allowed
    ok, _ = validate_payload({"kind": "post", "scope": "kreds",
                              "created_at": 1.0})
    assert not ok                                        # missing envelope


def test_make_ring_and_validate():
    d = _dev()
    m = make_ring(d, "cc" * 32, "inner", now=5.0)
    assert m.payload == {"kind": KIND_RING, "member": "cc" * 32,
                         "ring": "inner", "created_at": 5.0}
    assert validate_payload(m.payload)[0]
    ok, _ = validate_payload({"kind": "ring", "member": "cc" * 32,
                              "ring": "outer", "created_at": 5.0})
    assert not ok                                        # bad ring


def test_post_thumbs_validation():
    base = {"kind": KIND_POST, "scope": "kreds", "created_at": 1.0,
            "body_nonce": "ab" * 12, "body_ct": "de", "wraps": {},
            "blobs": ["a" * 64, "b" * 64]}
    base["thumbs"] = ["c" * 64, None]     # aligned, null entry ok
    assert validate_payload(base)[0]
    base["thumbs"] = ["c" * 64]           # length mismatch
    assert not validate_payload(base)[0]
    base["thumbs"] = "junk"               # not a list
    assert not validate_payload(base)[0]
    base["thumbs"] = ["zz", None]         # non-hex entry
    assert not validate_payload(base)[0]
    del base["thumbs"]                    # absent = old record, fine
    assert validate_payload(base)[0]
