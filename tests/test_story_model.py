from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import STORY_TTL, make_story, validate_payload


def device():
    d = DeviceKeys.create("phone")
    IdentityCeremony().enroll_first_device(d)
    return d


def test_photo_story_valid():
    d = device()
    m = make_story(d, "photo", "ab" * 32, caption="hej", now=100.0)
    assert m.payload["media_kind"] == "photo"
    assert m.payload["poster"] is None
    assert m.payload["expires_at"] == 100.0 + STORY_TTL
    assert validate_payload(m.payload) == (True, "ok")
    assert m.verify_device_signature()


def test_video_story_valid():
    d = device()
    m = make_story(d, "video", "ab" * 32, poster="cd" * 32, caption="", now=1.0)
    assert m.payload["poster"] == "cd" * 32
    assert validate_payload(m.payload) == (True, "ok")


def test_invalid_stories_rejected():
    ok = lambda p: validate_payload(p)[0]
    base = {"kind": "story", "media_kind": "photo", "media": "ab" * 32,
            "poster": None, "caption": "", "created_at": 1.0,
            "expires_at": 2.0}
    assert ok(base)
    assert not ok({**base, "media_kind": "gif"})
    assert not ok({**base, "media": "zz"})
    assert not ok({**base, "poster": "zz"})
    assert not ok({**base, "caption": "x" * 201})
    assert not ok({**base, "media": None})
    assert not ok({**base, "expires_at": None})        # stories MUST expire
    assert not ok({k: v for k, v in base.items() if k != "expires_at"})
    assert not ok({**base, "created_at": 1.0,
                   "expires_at": 1.0 + 86400 + 10})       # ttl too long
    assert ok({**base, "created_at": 1.0, "expires_at": 1.0 + 86400})  # exact boundary ok
