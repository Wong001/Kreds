import time

from hearth.identity import DeviceKeys, IdentityCeremony, DefriendNotice
from hearth.messages import make_dm, make_post, make_profile, make_ring
from hearth.store import Store


def _id():
    """Mirror the enroll idiom used across tests/test_identity_core.py and
    tests/test_store_ingest.py: DeviceKeys.create + IdentityCeremony gives
    an enrolled device whose identity_pub / make_revocation-style makers
    work (the brief's 'Identity' is this codebase's DeviceKeys)."""
    dev = DeviceKeys.create("dev")
    IdentityCeremony().enroll_first_device(dev)
    return dev


def test_defriend_notice_signs_and_verifies():
    a = _id()
    n = a.make_defriend("b" * 64, now=1000.0)
    assert n.author_identity == a.identity_pub
    assert n.target_identity == "b" * 64
    assert n.verify() is True
    # tamper -> fails. XOR the first signature byte with 0xff rather than
    # overwriting it with a fixed "00": Ed25519 signatures are effectively
    # random bytes, so hardcoding "00" had a 1-in-256 chance of being a
    # no-op (original byte already 0x00), making this assertion flaky.
    # XOR-with-0xff is guaranteed to differ from the original byte.
    flipped = format(int(n.signature[:2], 16) ^ 0xFF, "02x")
    bad = DefriendNotice(n.author_identity, n.target_identity, n.created_at,
                         flipped + n.signature[2:])
    assert bad.verify() is False


def test_defriend_notice_roundtrip():
    a = _id()
    n = a.make_defriend("c" * 64, now=5.0)
    assert DefriendNotice.from_dict(n.to_dict()) == n


def test_purge_authored_by_removes_only_that_author(tmp_path):
    a = _id()
    b = _id()
    st = Store(tmp_path / "purge.db")
    st.add_identity(a.identity_pub, is_self=True)
    st.add_identity(b.identity_pub)
    st.ingest_message(make_profile(b, "Bob"))
    post = make_post(b, "kreds", body_nonce="ab" * 12, body_ct="deadbeef",
                     wraps={})
    st.ingest_message(post)
    a_post = make_post(a, "kreds", body_nonce="ef" * 12, body_ct="c0ffee",
                       wraps={})
    st.ingest_message(a_post)
    assert st.messages_by_author(b.identity_pub) != []
    n = st.purge_authored_by(b.identity_pub)
    assert n >= 1
    assert st.messages_by_author(b.identity_pub) == []
    # A's own content is untouched
    assert st.messages_by_author(a.identity_pub) != []


def test_purge_authored_by_removes_dm_keys(tmp_path):
    """Fix 6: purge_authored_by must also drop the dm_keys sealed-key row
    for every message it deletes, mirroring _tombstone's own cleanup --
    otherwise a purge leaves an orphaned cached content key behind."""
    a = _id()
    b = _id()
    st = Store(tmp_path / "purge_dmkeys.db")
    st.add_identity(a.identity_pub, is_self=True)
    st.add_identity(b.identity_pub)
    post = make_post(b, "kreds", body_nonce="ab" * 12, body_ct="deadbeef",
                     wraps={})
    st.ingest_message(post)
    st.cache_message_key(post.msg_id, "aa" * 16)
    assert st.cached_message_key(post.msg_id) is not None

    st.purge_authored_by(b.identity_pub)

    assert st.cached_message_key(post.msg_id) is None


def test_remove_peer_identity_and_device_views(tmp_path):
    """Fix 4 / 5 building blocks: dedicated helpers used by both
    unfriend_teardown and Node.apply_defriend_notice."""
    a = _id()
    st = Store(tmp_path / "removepeer.db")
    st.add_identity(a.identity_pub)
    st.ingest_message(make_profile(a, "Alice"))       # populates device_views
    st.add_peer("5.6.7.8:9000", a.identity_pub)
    assert st.address_for(a.identity_pub) == "5.6.7.8:9000"
    assert st.load_views(a.identity_pub)

    st.remove_peer_identity(a.identity_pub)
    assert st.address_for(a.identity_pub) is None

    st.remove_device_views(a.identity_pub)
    assert st.load_views(a.identity_pub) == {}


def test_disconnected_marker_roundtrip(tmp_path):
    st = Store(tmp_path / "disc.db")
    st.add_disconnected("b" * 64, "Bob")
    assert st.list_disconnected() == [{"identity_pub": "b" * 64, "name": "Bob"}]
    st.remove_disconnected("b" * 64)
    assert st.list_disconnected() == []


def test_outbox_roundtrip(tmp_path):
    a = _id()
    st = Store(tmp_path / "outbox.db")
    st.add_identity(a.identity_pub, is_self=True)
    n = a.make_defriend("d" * 64, now=1.0)
    st.add_outbox(n, "1.2.3.4:9000", expires_at=100.0)
    rows = st.list_outbox()
    assert rows and rows[0]["target_identity"] == "d" * 64
    assert rows[0]["address"] == "1.2.3.4:9000"
    assert rows[0]["next_attempt_at"] == 0          # Fix 3: fresh default
    st.drop_outbox("d" * 64)
    assert st.list_outbox() == []


def test_outbox_retry_backoff_roundtrip(tmp_path):
    """Fix 3: set_outbox_retry updates only next_attempt_at, in place."""
    a = _id()
    st = Store(tmp_path / "outbox_retry.db")
    st.add_identity(a.identity_pub, is_self=True)
    n = a.make_defriend("d" * 64, now=1.0)
    st.add_outbox(n, "1.2.3.4:9000", expires_at=100.0)

    st.set_outbox_retry("d" * 64, 42.0)

    rows = st.list_outbox()
    assert rows[0]["next_attempt_at"] == 42.0
    assert rows[0]["address"] == "1.2.3.4:9000"      # untouched
    assert rows[0]["expires_at"] == 100.0            # untouched


def test_unfriend_teardown_removes_identity_content_dm_and_ring(tmp_path):
    a = _id()
    b = _id()
    st = Store(tmp_path / "teardown.db")
    st.add_identity(a.identity_pub, is_self=True)
    st.add_identity(b.identity_pub)
    st.ingest_message(make_profile(b, "Bob"))
    post = make_post(b, "kreds", body_nonce="ab" * 12, body_ct="deadbeef",
                     wraps={})
    st.ingest_message(post)
    st.cache_message_key(post.msg_id, "aa" * 16)
    dm = make_dm(a, b.identity_pub, body_nonce="cd" * 12, body_ct="beadfeed",
                wraps={}, created_at=1.0)
    st.ingest_message(dm)
    st.cache_message_key(dm.msg_id, "bb" * 16)
    st.ingest_message(make_ring(a, b.identity_pub, "inner", now=1.0))
    st.add_peer("1.2.3.4:9000", b.identity_pub)
    assert st.address_for(b.identity_pub) == "1.2.3.4:9000"
    assert st.load_views(b.identity_pub)                   # non-empty
    assert st.cached_message_key(post.msg_id) is not None
    assert st.cached_message_key(dm.msg_id) is not None

    st.unfriend_teardown(a.identity_pub, b.identity_pub)

    assert not st.is_known(b.identity_pub)
    assert st.messages_by_author(b.identity_pub) == []
    assert st.dm_thread(a.identity_pub, b.identity_pub) == []
    assert st.rings(a.identity_pub) == {}
    assert st.address_for(b.identity_pub) is None
    # Fix 5: device_views (enrollment certs / seen-state / revocations)
    # for the removed identity are gone too.
    assert st.load_views(b.identity_pub) == {}
    # Fix 6: dm_keys rows for every message just deleted -- both what b
    # authored (post) and what a sent b (dm) -- are gone, no orphans.
    assert st.cached_message_key(post.msg_id) is None
    assert st.cached_message_key(dm.msg_id) is None


def test_remove_identity_deletes_row_only(tmp_path):
    a = _id()
    st = Store(tmp_path / "removeid.db")
    st.add_identity(a.identity_pub, is_self=True)
    assert st.is_known(a.identity_pub)
    st.remove_identity(a.identity_pub)
    assert not st.is_known(a.identity_pub)


def test_address_for_unknown_returns_none(tmp_path):
    st = Store(tmp_path / "addrnone.db")
    assert st.address_for("e" * 64) is None


def test_store_migrates_pre_existing_outbox_table_without_next_attempt_at(
        tmp_path):
    """Fix 3: a DB file created before the next_attempt_at retry-backoff
    column existed must be upgraded in place via the guarded ALTER TABLE
    on open, not crash and not lose existing schema."""
    import sqlite3
    path = tmp_path / "legacy.db"
    raw = sqlite3.connect(str(path))
    raw.execute("""
        CREATE TABLE defriend_outbox(
          target_identity TEXT PRIMARY KEY, address TEXT NOT NULL,
          notice_json TEXT NOT NULL, created_at REAL NOT NULL,
          expires_at REAL NOT NULL)
    """)
    raw.commit()
    raw.close()

    st = Store(path)          # must not raise: guarded ALTER adds the column
    a = _id()
    st.add_identity(a.identity_pub, is_self=True)
    n = a.make_defriend("e" * 64, now=1.0)
    st.add_outbox(n, "1.2.3.4:9000", expires_at=100.0)
    rows = st.list_outbox()
    assert rows and rows[0]["next_attempt_at"] == 0

    # Re-opening an already-migrated DB (this test's own file, second
    # connect) must also not raise -- the ALTER's "duplicate column" case.
    st.close()
    st2 = Store(path)
    assert st2.list_outbox()[0]["target_identity"] == "e" * 64
