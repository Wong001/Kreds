import time

from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import (
    KIND_POST, KIND_RING, blob_hash, is_expired, make_delete, make_post,
    make_profile, make_response, make_responses, make_ring, validate_payload,
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


def test_response_kinds_validate():
    from hearth.messages import (KIND_RESPONSE, KIND_RESPONSES,
                                 validate_payload)
    base = {"kind": KIND_RESPONSE, "target": "m" * 16,
            "body_nonce": "ab" * 12, "body_ct": "de",
            "wraps": {}, "created_at": 1.0}
    assert validate_payload(base)[0]
    for k, bad in [("target", 7), ("target", ""), ("body_nonce", "zz"),
                   ("body_ct", ""), ("wraps", "junk")]:
        p = dict(base); p[k] = bad
        assert not validate_payload(p)[0], k
    rec = {"kind": KIND_RESPONSES, "target": "m" * 16,
           "body_nonce": "ab" * 12, "body_ct": "de", "wraps": {},
           "expires_at": None, "created_at": 1.0}
    assert validate_payload(rec)[0]
    rec["expires_at"] = "soon"
    assert not validate_payload(rec)[0]


def test_reaction_tokens_frozen():
    from hearth.messages import REACTION_TOKENS, MAX_COMMENT
    assert REACTION_TOKENS == ("heart", "laugh", "wow", "sad", "up", "fire")
    assert MAX_COMMENT == 500


def test_dm_story_ref_validation():
    # Task 7 (stories as DMs): story_ref is an additive, optional envelope
    # field on KIND_DM - absent/None fine; present, it must be a dict with
    # a non-empty string story_id and a hex64 media_hash. Anything else
    # rejects the whole DM payload (fail-closed, same idiom as _valid_wraps).
    base = {"kind": "dm", "to": "bb" * 32, "body_nonce": "0" * 24,
            "body_ct": "ab", "wraps": {}, "created_at": 1.0,
            "expires_at": None}
    assert validate_payload(base)[0]                      # no story_ref: fine
    base["story_ref"] = {"story_id": "s" * 16, "media_hash": "a" * 64}
    assert validate_payload(base)[0]
    for bad in (7, "x", {"story_id": ""}, {"story_id": "s", "media_hash": 9},
                {"story_id": "s"}):
        base["story_ref"] = bad
        assert not validate_payload(base)[0], bad
    base["story_ref"] = None
    assert validate_payload(base)[0]


def test_make_response_and_make_responses_shape():
    d = _dev()
    from hearth.messages import KIND_RESPONSE, KIND_RESPONSES, validate_payload
    m = make_response(d, "m" * 16, body_nonce="ab" * 12, body_ct="de",
                      wraps={}, created_at=10.0)
    assert m.payload == {"kind": KIND_RESPONSE, "target": "m" * 16,
                         "body_nonce": "ab" * 12, "body_ct": "de",
                         "wraps": {}, "created_at": 10.0}
    assert validate_payload(m.payload)[0]
    rm = make_responses(d, "m" * 16, body_nonce="ab" * 12, body_ct="de",
                        wraps={}, expires_at=20.0, created_at=10.0)
    assert rm.payload == {"kind": KIND_RESPONSES, "target": "m" * 16,
                          "body_nonce": "ab" * 12, "body_ct": "de",
                          "wraps": {}, "expires_at": 20.0, "created_at": 10.0}
    assert validate_payload(rm.payload)[0]
