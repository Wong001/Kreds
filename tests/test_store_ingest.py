import time

from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import (
    blob_hash, make_delete, make_dm, make_post, make_profile, make_story,
)
from hearth.store import Store


def wong(tmp_path, db="w.db"):
    phone = DeviceKeys.create("wong-phone")
    node = DeviceKeys.create("wong-homenode")
    IdentityCeremony().enroll_first_device(phone)
    node.install(phone.enroll_other(node.device_pub, node.name),
                 phone.to_json()["identity_priv"])
    s = Store(tmp_path / db)
    s.add_identity(phone.identity_pub, is_self=True)
    return s, phone, node


def _post(device, blob_refs=(), created_at=None, expires_at=None):
    """A store-level post row: undecryptable-by-design (wraps={}) is fine
    here -- these tests assert on rows/tombstones/GC, never on content, so
    the store never needs to decrypt anything."""
    return make_post(device, "kreds", body_nonce="ab" * 12,
                     body_ct="deadbeef", wraps={}, blob_refs=blob_refs,
                     created_at=created_at, expires_at=expires_at)


def test_ingest_accept_duplicate_unknown(tmp_path):
    s, phone, _ = wong(tmp_path)
    m = _post(phone)
    assert s.ingest_message(m).accepted is True
    assert s.ingest_message(m).reason == "duplicate"
    stranger = DeviceKeys.create("stranger")
    IdentityCeremony().enroll_first_device(stranger)
    r = s.ingest_message(_post(stranger))
    assert (r.accepted, r.reason) == (False, "unknown identity")


def test_feed_with_profile_names(tmp_path):
    s, phone, _ = wong(tmp_path)
    s.ingest_message(make_profile(phone, "Wong"))
    first = _post(phone, created_at=100.0)
    second = _post(phone, created_at=200.0)
    s.ingest_message(first)
    s.ingest_message(second)
    rows = s.post_messages()
    assert [m.msg_id for m in rows] == [second.msg_id, first.msg_id]
    assert s.profiles()[phone.identity_pub] == "Wong"


def test_delete_tag_removes_post_and_blocks_resurrection(tmp_path):
    s, phone, _ = wong(tmp_path)
    data = b"photo"
    ref = s.put_blob(data)
    post = _post(phone, blob_refs=[ref])
    s.ingest_message(post)
    r = s.ingest_message(make_delete(phone, post.msg_id))
    assert r.accepted and r.deleted_target == post.msg_id
    assert s.post_messages() == []
    assert s.is_tombstoned(post.msg_id)
    assert not s.has_blob(ref)                       # GC'd
    r2 = s.ingest_message(post)                      # gossip echo
    assert (r2.accepted, r2.reason) == (False, "tombstoned")


def test_delete_from_wrong_identity_rejected(tmp_path):
    s, phone, _ = wong(tmp_path)
    post = _post(phone)
    s.ingest_message(post)
    mal = DeviceKeys.create("mallory")
    IdentityCeremony().enroll_first_device(mal)
    s.add_identity(mal.identity_pub)                 # mallory IS a friend
    r = s.ingest_message(make_delete(mal, post.msg_id))
    assert (r.accepted, r.reason) == (False, "delete not authorized")
    assert len(s.post_messages()) == 1


def test_delete_tag_arriving_before_post(tmp_path):
    s, phone, _ = wong(tmp_path)
    ref = s.put_blob(b"photo-in-doomed-post")
    post = _post(phone, blob_refs=[ref])
    tag = make_delete(phone, post.msg_id)
    assert s.ingest_message(tag).accepted            # tag first
    r = s.ingest_message(post)                       # post second
    assert r.accepted and r.reason == "deleted on arrival"
    assert r.deleted_target == post.msg_id
    assert s.post_messages() == [] and s.is_tombstoned(post.msg_id)
    assert not s.has_blob(ref)                       # blob GC'd, no orphan


def test_revocation_retro_drop(tmp_path):
    s, phone, node = wong(tmp_path)
    keep = _post(phone)
    s.ingest_message(keep)
    loot1 = _post(phone)
    loot2 = _post(phone)
    s.ingest_message(loot1)
    s.ingest_message(loot2)
    rev = node.make_revocation(phone.device_pub, last_valid_seq=keep.seq)
    r = s.ingest_revocation(rev)
    assert r.accepted
    assert set(r.retro_dropped) == {loot1.msg_id, loot2.msg_id}
    assert [m.msg_id for m in s.post_messages()] == [keep.msg_id]
    assert s.ingest_message(loot1).reason == "tombstoned"


def test_sweep_expired(tmp_path):
    s, phone, _ = wong(tmp_path)
    now = time.time()
    gone = _post(phone, expires_at=now - 10)
    stays = _post(phone, expires_at=now + 3600)
    s.ingest_message(gone)
    s.ingest_message(stays)
    swept = s.sweep_expired(now)
    assert len(swept) == 1
    assert [m.msg_id for m in s.post_messages()] == [stays.msg_id]


def test_messages_not_in_respects_entitlement_and_summaries(tmp_path):
    s, phone, _ = wong(tmp_path)
    m1 = _post(phone)
    m2 = _post(phone)
    s.ingest_message(m1)
    s.ingest_message(m2)
    ident = phone.identity_pub
    # peer has seen seq 1 only
    summaries = {ident: {phone.device_pub: {"contiguous": 1, "sparse": []}}}
    missing = s.messages_not_in(summaries, entitled={ident},
                                peer_identity=ident)
    assert [m.msg_id for m in missing] == [m2.msg_id]
    # not entitled -> nothing, regardless of summaries
    assert s.messages_not_in({}, entitled=set(),
                             peer_identity=ident) == []


def _friend(store, name):
    dev = DeviceKeys.create(name)
    IdentityCeremony().enroll_first_device(dev)
    store.add_identity(dev.identity_pub)
    return dev


def test_delete_tag_arriving_before_dm(tmp_path):
    s, phone, _ = wong(tmp_path)
    friend = _friend(s, "freja-phone")
    ref = s.put_blob(b"encrypted-photo-ct")
    dm = make_dm(phone, friend.identity_pub, body_nonce="ab" * 12,
                 body_ct="deadbeef", wraps={}, created_at=100.0,
                 blob_refs=[ref])
    tag = make_delete(phone, dm.msg_id)
    assert s.ingest_message(tag).accepted            # tag first
    r = s.ingest_message(dm)                         # content second
    assert r.accepted and r.reason == "deleted on arrival"
    assert r.deleted_target == dm.msg_id
    assert s.get_message(dm.msg_id) is None
    assert s.is_tombstoned(dm.msg_id)
    assert not s.has_blob(ref)                       # blob GC'd
    r2 = s.ingest_message(dm)                        # gossip echo
    assert (r2.accepted, r2.reason) == (False, "tombstoned")


def test_delete_tag_arriving_before_story(tmp_path):
    s, phone, _ = wong(tmp_path)
    media = s.put_blob(b"story-media-bytes")
    story = make_story(phone, "photo", media)
    tag = make_delete(phone, story.msg_id)
    assert s.ingest_message(tag).accepted
    r = s.ingest_message(story)
    assert r.accepted and r.reason == "deleted on arrival"
    assert s.get_message(story.msg_id) is None
    assert s.is_tombstoned(story.msg_id)
    assert not s.has_blob(media)


def test_delete_tag_arriving_before_profile(tmp_path):
    s, phone, _ = wong(tmp_path)
    prof = make_profile(phone, "Wong")
    tag = make_delete(phone, prof.msg_id)
    assert s.ingest_message(tag).accepted
    r = s.ingest_message(prof)
    assert r.accepted and r.reason == "deleted on arrival"
    assert phone.identity_pub not in s.profiles()


def test_foreign_delete_tag_does_not_censor_unarrived_message(tmp_path):
    # Mallory (a friend) saw the post elsewhere, knows its msg_id, and
    # sends a preemptive delete tag hoping to censor it on nodes that do
    # not have it yet. The author-match in the guard must defeat this.
    s, phone, _ = wong(tmp_path)
    mal = _friend(s, "mallory")
    post = _post(phone)
    assert s.ingest_message(make_delete(mal, post.msg_id)).accepted
    r = s.ingest_message(post)
    assert (r.accepted, r.reason) == (True, "ok")
    assert [m.msg_id for m in s.post_messages()] == [post.msg_id]


def test_delete_tag_cannot_target_a_delete_tag_when_held(tmp_path):
    # Wart 1 (spec 2026-07-09-protocol-warts): a meta-delete that lands
    # AFTER the tag it targets must be refused - pre-fix it tombstoned the
    # tag, halting its propagation and permanently diverging the network.
    s, phone, _ = wong(tmp_path)
    post = _post(phone)
    s.ingest_message(post)
    tag = make_delete(phone, post.msg_id)
    assert s.ingest_message(tag).accepted
    meta = make_delete(phone, tag.msg_id)
    res = s.ingest_message(meta)
    assert not res.accepted and "cannot target a delete tag" in res.reason
    # the original tag row is still held and still offered (not tombstoned)
    assert s.message_kind(tag.msg_id) == "delete"


def test_arriving_delete_tag_is_immune_to_a_lurking_meta_delete(tmp_path):
    # Pin the (already-correct) behavior: the delete-on-arrival guard sits
    # in the non-delete branch, so a meta-delete held BEFORE its target tag
    # arrives never tombstones-on-arrival the tag itself...
    s, phone, _ = wong(tmp_path)
    post = _post(phone)
    s.ingest_message(post)
    tag = make_delete(phone, post.msg_id)
    meta = make_delete(phone, tag.msg_id)
    assert s.ingest_message(meta).accepted           # lurks (target unknown)
    res = s.ingest_message(tag)
    assert res.accepted and res.reason != "deleted on arrival"
    # ...the post is gone (the real tag applied) and the tag row survives
    assert s.message_kind(tag.msg_id) == "delete"
    # ...and the now-provably-invalid meta-delete was tombstoned as hygiene
    assert s.message_kind(meta.msg_id) is None
    assert s.is_tombstoned(meta.msg_id)
