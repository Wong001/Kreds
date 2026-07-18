import inspect
import re

from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import make_delete, make_dm, make_enckey
from hearth.node import HearthNode
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


def test_caching_a_key_removes_the_entry(tmp_path):
    s, phone = wong(tmp_path)
    freja = friend_of(s)
    d1 = dm_to(phone, freja.identity_pub)
    assert s.ingest_message(d1).accepted
    s.mark_undecryptable(d1.msg_id)
    assert s.undecryptable_ids() == {d1.msg_id}
    s.cache_message_key(d1.msg_id, "cafe01")
    assert s.undecryptable_ids() == set()


def test_replacing_a_key_removes_the_entry(tmp_path):
    s, phone = wong(tmp_path)
    freja = friend_of(s)
    d1 = dm_to(phone, freja.identity_pub)
    assert s.ingest_message(d1).accepted
    s.mark_undecryptable(d1.msg_id)
    s.replace_message_key(d1.msg_id, "beef02")
    assert s.undecryptable_ids() == set()


def test_tombstone_removes_the_entry(tmp_path):
    s, phone = wong(tmp_path)
    freja = friend_of(s)
    d1 = dm_to(phone, freja.identity_pub)
    assert s.ingest_message(d1).accepted
    s.mark_undecryptable(d1.msg_id)
    assert s.ingest_message(make_delete(phone, d1.msg_id)).accepted
    assert s.undecryptable_ids() == set()


def test_uncached_message_ids_excludes_marked_undecryptable(tmp_path):
    s, phone = wong(tmp_path)
    freja = friend_of(s)
    d1 = dm_to(phone, freja.identity_pub)
    assert s.ingest_message(d1).accepted
    s.mark_undecryptable(d1.msg_id)
    assert s.uncached_message_ids(phone.identity_pub) == []


def test_clear_undecryptable_with_no_id_clears_everything(tmp_path):
    s, phone = wong(tmp_path)
    s.mark_undecryptable("aa" * 32)
    s.mark_undecryptable("bb" * 32)
    s.clear_undecryptable()
    assert s.undecryptable_ids() == set()


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


def test_wipe_all_clears_undecryptable(tmp_path):
    """Whole-branch review Fix 1: wipe_all must clear the undecryptable
    table, leaving no metadata remnant."""
    s, phone = wong(tmp_path)
    freja = friend_of(s)
    d1 = dm_to(phone, freja.identity_pub)
    assert s.ingest_message(d1).accepted
    s.mark_undecryptable(d1.msg_id)
    assert s.undecryptable_ids() == {d1.msg_id}

    s.wipe_all()

    assert s.undecryptable_ids() == set()


def test_unfriend_teardown_clears_undecryptable_for_purged_ids(tmp_path):
    """Whole-branch review Fix 3: unfriend_teardown must also delete
    undecryptable rows for messages about to be purged, mirroring its
    dm_keys deletion pattern."""
    from hearth.messages import make_post
    phone = DeviceKeys.create("phone")
    IdentityCeremony().enroll_first_device(phone)
    freja = DeviceKeys.create("freja-phone")
    IdentityCeremony().enroll_first_device(freja)
    s = Store(tmp_path / "teardown_undec.db")
    s.add_identity(phone.identity_pub, is_self=True)
    s.add_identity(freja.identity_pub)
    # A message authored by freja that will be marked undecryptable
    post = make_post(freja, "kreds", body_nonce="ab" * 12, body_ct="deadbeef",
                     wraps={})
    assert s.ingest_message(post).accepted
    s.mark_undecryptable(post.msg_id)
    assert s.undecryptable_ids() == {post.msg_id}

    s.unfriend_teardown(phone.identity_pub, freja.identity_pub)

    assert s.undecryptable_ids() == set()


def test_uncached_message_ids_kinds_all_have_content_key_branches():
    """Reviewer guard-test (whole-branch review, MINOR): store.py and
    node.py both carry hand-written comments warning that
    uncached_message_ids' kind IN-clause and node._content_key's kind
    dispatch are "two lists [that] must be extended together" -- a kind
    added to one without the other either starves that kind's caching
    forever (missing from _content_key's dispatch, falls into its
    defensive `else: return None, None`, then cache_message_keys()
    permanently marks it undecryptable) or never gets swept at all
    (missing from the IN-clause). This pins that invariant at the
    source level so a future edit to either list trips a test instead
    of silently drifting: extract the literal kind tuple out of
    Store.uncached_message_ids' SQL call, then assert every one of
    those kind names appears in an `== KIND_X` comparison somewhere in
    HearthNode._content_key's source."""
    store_src = inspect.getsource(Store.uncached_message_ids)
    m = re.search(r'\(\s*((?:KIND_\w+\s*,?\s*)+)\)\):', store_src)
    assert m, "could not find the kind IN(...) params tuple in source"
    kinds = [k.strip() for k in m.group(1).split(",") if k.strip()]
    assert kinds == ["KIND_DM", "KIND_POST", "KIND_RESPONSE",
                     "KIND_RESPONSES"]   # sanity: parsed the right tuple

    content_key_src = inspect.getsource(HearthNode._content_key)
    for kind_name in kinds:
        assert re.search(r"kind\s*==\s*" + re.escape(kind_name),
                         content_key_src), (
            f"{kind_name} is in uncached_message_ids' IN-clause but has "
            "no matching branch in _content_key -- the two lists must "
            "move together")
