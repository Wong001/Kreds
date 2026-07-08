import time

from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import make_post, make_story
from hearth.store import Store


def person(name):
    d = DeviceKeys.create(name)
    IdentityCeremony().enroll_first_device(d)
    return d


def store_with(tmp_path, *identities):
    s = Store(tmp_path / "s.db")
    for i, ident in enumerate(identities):
        s.add_identity(ident, is_self=(i == 0))
    return s


def _post(device):
    """A store-level post row: undecryptable-by-design (wraps={}) is fine
    here -- this test only checks that GC leaves story blobs alone."""
    return make_post(device, "kreds", body_nonce="ab" * 12,
                     body_ct="deadbeef", wraps={})


def test_story_blobs_referenced_and_gc_safe(tmp_path):
    wong = person("wong-phone")
    s = store_with(tmp_path, wong.identity_pub)
    media = s.put_blob(b"video-bytes")
    poster = s.put_blob(b"poster-bytes")
    s.ingest_message(make_story(wong, "video", media, poster=poster))
    refs = s.referenced_blobs()
    assert media in refs and poster in refs
    s.ingest_message(_post(wong))
    s.gc_blobs()                                   # must NOT delete story blobs
    assert s.get_blob(media) is not None and s.get_blob(poster) is not None


def test_story_blob_reported_missing_when_absent(tmp_path):
    wong = person("wong-phone")
    s = store_with(tmp_path, wong.identity_pub)
    s.ingest_message(make_story(wong, "photo", "ab" * 32))
    assert "ab" * 32 in s.missing_blobs()          # gossip will fetch it


def test_active_stories_grouped_and_expiry(tmp_path):
    wong, freja = person("wong-phone"), person("freja-phone")
    s = store_with(tmp_path, wong.identity_pub, freja.identity_pub)
    now = time.time()
    s.ingest_message(make_story(wong, "photo", "a1" * 32, now=now - 10))
    s.ingest_message(make_story(wong, "photo", "a2" * 32, now=now - 5))
    s.ingest_message(make_story(freja, "photo", "b1" * 32, now=now - 8))
    groups = s.active_stories(now)
    by = {g["identity_pub"]: g for g in groups}
    assert [i["media"] for i in by[wong.identity_pub]["items"]] == \
        ["a1" * 32, "a2" * 32]                      # time-ascending within author
    assert len(by[freja.identity_pub]["items"]) == 1
    # an expired story (created 25h ago) is excluded
    s.ingest_message(make_story(wong, "photo", "c1" * 32,
                                now=now - 25 * 3600))
    medias = [i["media"] for g in s.active_stories(now)
              for i in g["items"]]
    assert "c1" * 32 not in medias
