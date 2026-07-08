from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import make_delete, make_dm, make_enckey
from hearth.store import Store


def wong(tmp_path):
    phone = DeviceKeys.create("wong-phone")
    IdentityCeremony().enroll_first_device(phone)
    s = Store(tmp_path / "w.db")
    s.add_identity(phone.identity_pub, is_self=True)
    return s, phone


def friend_of(s):
    dev = DeviceKeys.create("freja-phone")
    IdentityCeremony().enroll_first_device(dev)
    s.add_identity(dev.identity_pub)
    return dev


def dm_to(phone, to_identity):
    return make_dm(phone, to_identity, body_nonce="ab" * 12,
                   body_ct="deadbeef", wraps={}, created_at=100.0)


def test_message_key_cache_roundtrip(tmp_path):
    s, phone = wong(tmp_path)
    s.cache_message_key("aa" * 32, "cafe01")
    assert s.cached_message_key("aa" * 32) == "cafe01"
    assert s.cached_message_key("bb" * 32) is None


def test_uncached_message_ids_lists_only_uncached_self_dms(tmp_path):
    s, phone = wong(tmp_path)
    freja = friend_of(s)
    d1 = dm_to(phone, freja.identity_pub)
    d2 = dm_to(phone, freja.identity_pub)
    assert s.ingest_message(d1).accepted
    assert s.ingest_message(d2).accepted
    assert set(s.uncached_message_ids(phone.identity_pub)) \
        == {d1.msg_id, d2.msg_id}
    s.cache_message_key(d1.msg_id, "cafe01")
    assert s.uncached_message_ids(phone.identity_pub) == [d2.msg_id]


def test_deleted_dm_drops_cached_key(tmp_path):
    s, phone = wong(tmp_path)
    freja = friend_of(s)
    d1 = dm_to(phone, freja.identity_pub)
    assert s.ingest_message(d1).accepted
    s.cache_message_key(d1.msg_id, "cafe01")
    assert s.ingest_message(make_delete(phone, d1.msg_id)).accepted
    assert s.cached_message_key(d1.msg_id) is None


def test_expired_dm_drops_cached_key(tmp_path):
    s, phone = wong(tmp_path)
    freja = friend_of(s)
    d = make_dm(phone, freja.identity_pub, body_nonce="ab" * 12,
                body_ct="deadbeef", wraps={}, created_at=100.0,
                expires_at=200.0)
    assert s.ingest_message(d).accepted
    s.cache_message_key(d.msg_id, "cafe01")
    assert s.sweep_expired(now=201.0) == [d.msg_id]
    assert s.cached_message_key(d.msg_id) is None


def test_cache_message_key_refuses_tombstoned_msg_id(tmp_path):
    s, phone = wong(tmp_path)
    freja = friend_of(s)
    d1 = dm_to(phone, freja.identity_pub)
    assert s.ingest_message(d1).accepted
    assert s.ingest_message(make_delete(phone, d1.msg_id)).accepted
    s.cache_message_key(d1.msg_id, "cafe01")   # delete landed first: refused
    assert s.cached_message_key(d1.msg_id) is None


def test_replace_message_key_refuses_tombstoned_msg_id(tmp_path):
    s, phone = wong(tmp_path)
    freja = friend_of(s)
    d1 = dm_to(phone, freja.identity_pub)
    assert s.ingest_message(d1).accepted
    s.cache_message_key(d1.msg_id, "cafe01")
    assert s.ingest_message(make_delete(phone, d1.msg_id)).accepted
    s.replace_message_key(d1.msg_id, "beef02")  # delete landed first: refused
    assert s.cached_message_key(d1.msg_id) is None


def test_enckeys_tiebreak_same_created_at_higher_seq_wins(tmp_path):
    s, phone = wong(tmp_path)
    e1 = make_enckey(phone, now=100.0)
    phone.rotate_enc(now=100.0)
    e2 = make_enckey(phone, now=100.0)       # same created_at, higher seq
    assert s.ingest_message(e1).accepted
    assert s.ingest_message(e2).accepted
    assert s.enckeys(phone.identity_pub)[phone.device_pub] == phone.enc_pub
    assert s.enckey_records(phone.identity_pub)[phone.device_pub] \
        == (100.0, phone.enc_pub)
    # out-of-order arrival gives the same answer
    s2 = Store(tmp_path / "w2.db")
    s2.add_identity(phone.identity_pub, is_self=True)
    assert s2.ingest_message(e2).accepted
    assert s2.ingest_message(e1).accepted
    assert s2.enckeys(phone.identity_pub)[phone.device_pub] \
        == phone.enc_pub
