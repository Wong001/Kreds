from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import make_post, make_profile
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


def _post(device, created_at=None):
    """A store-level post row: undecryptable-by-design (wraps={}) is fine
    here since these tests assert on GC/rows, never on content."""
    return make_post(device, "kreds", body_nonce="ab" * 12,
                     body_ct="deadbeef", wraps={}, created_at=created_at)


def test_profile_full_record_latest_wins(tmp_path):
    wong = person("wong-phone")
    s = store_with(tmp_path, wong.identity_pub)
    s.ingest_message(make_profile(wong, "Wong", now=1.0))
    s.ingest_message(make_profile(wong, "Wong", bio="hej", accent="#c0563b",
                                  avatar="ab" * 32, avatar_shape="squircle",
                                  avatar_size="l", avatar_align="center",
                                  banner="cd" * 32, now=2.0))
    p = s.profile(wong.identity_pub)
    assert p["name"] == "Wong" and p["bio"] == "hej"
    assert p["accent"] == "#c0563b" and p["avatar"] == "ab" * 32
    assert p["avatar_shape"] == "squircle" and p["avatar_align"] == "center"
    assert p["banner"] == "cd" * 32
    # names-only view still works for feed/state
    assert s.profiles()[wong.identity_pub] == "Wong"


def test_profile_defaults_for_name_only(tmp_path):
    wong = person("wong-phone")
    s = store_with(tmp_path, wong.identity_pub)
    s.ingest_message(make_profile(wong, "Wong"))
    p = s.profile(wong.identity_pub)
    assert (p["bio"], p["accent"], p["avatar"]) == ("", "#2743d6", None)
    assert p["avatar_shape"] == "circle" and p["avatar_size"] == "m"


def test_profile_none_when_absent(tmp_path):
    s = store_with(tmp_path, "aa" * 32)
    assert s.profile("aa" * 32) is None


def test_profile_latest_wins_breaks_created_at_ties_by_seq(tmp_path):
    # Two updates with an IDENTICAL created_at (timer-granularity collision)
    # must deterministically resolve to the later-published one via seq.
    wong = person("wong-phone")
    s = store_with(tmp_path, wong.identity_pub)
    s.ingest_message(make_profile(wong, "First", bio="old", now=100.0))
    s.ingest_message(make_profile(wong, "Second", bio="new", now=100.0))
    p = s.profile(wong.identity_pub)
    assert p["name"] == "Second" and p["bio"] == "new"
    assert s.profiles()[wong.identity_pub] == "Second"


def test_profile_avatar_referenced_and_gc_safe(tmp_path):
    wong = person("wong-phone")
    s = store_with(tmp_path, wong.identity_pub)
    h = s.put_blob(b"\x89PNG-fake-avatar")
    s.ingest_message(make_profile(wong, "Wong", avatar=h))
    assert h in s.referenced_blobs()
    s.ingest_message(_post(wong))
    s.gc_blobs()                                   # must NOT delete the avatar
    assert s.get_blob(h) is not None


def test_profile_avatar_reported_missing_when_absent(tmp_path):
    wong = person("wong-phone")
    s = store_with(tmp_path, wong.identity_pub)
    s.ingest_message(make_profile(wong, "Wong", avatar="ab" * 32))
    assert "ab" * 32 in s.missing_blobs()          # gossip will now fetch it


def test_posts_by_identity_only(tmp_path):
    # store.posts_by() is retired (decryption is node's job now -- see
    # node.posts_by / test_node_scoped_posts.py); at the store level the
    # equivalent row-level guarantee is post_messages(identity_pub):
    # newest-first, filtered to one author.
    wong, freja = person("wong-phone"), person("freja-phone")
    s = store_with(tmp_path, wong.identity_pub, freja.identity_pub)
    wong_one = _post(wong, created_at=10.0)
    wong_two = _post(wong, created_at=20.0)
    freja_one = _post(freja, created_at=15.0)
    s.ingest_message(wong_one)
    s.ingest_message(wong_two)
    s.ingest_message(freja_one)
    ids = [m.msg_id for m in s.post_messages(wong.identity_pub)]
    assert ids == [wong_two.msg_id, wong_one.msg_id]   # newest-first, wong only
