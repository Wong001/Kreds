"""Node-level unfriend: Node.unfriend() tears down + queues a signed
DefriendNotice; Node.apply_defriend_notice() is the receiving side's
retention rule (verify sig + target + known-author, then purge/remove/mark).
Fixture mirrors the befriend()-then-direct-ingest idiom used in
tests/test_scoped_posts_e2e.py rather than standing up real SyncService
sockets, since these tests only need store-level content to exist, not
decrypted feed rows."""
import pytest

from hearth.identity import DefriendNotice
from hearth.node import HearthNode


def _befriend(a, b):
    a.store.add_identity(b.identity_pub)
    b.store.add_identity(a.identity_pub)


@pytest.fixture
def two_friend_nodes(tmp_path):
    a = HearthNode.create(tmp_path / "a", "Alice", "alice-phone")
    b = HearthNode.create(tmp_path / "b", "Bob", "bob-phone")
    _befriend(a, b)
    # one post each, synced by direct ingest (store-level stand-in for sync)
    mid_a = a.compose_post("hello from a")
    b.store.ingest_message(a.store.get_message(mid_a))
    mid_b = b.compose_post("hello from b")
    a.store.ingest_message(b.store.get_message(mid_b))
    return a, b


def test_apply_defriend_notice_purges_and_marks(two_friend_nodes):
    a, b = two_friend_nodes            # a and b are mutual friends, synced
    # b has content from a (a post) synced to b:
    assert b.store.messages_by_author(a.identity_pub)
    b.store.add_peer("1.2.3.4:9000", a.identity_pub)     # Fix 4 setup
    assert b.store.load_views(a.identity_pub)            # Fix 5 setup (non-empty)
    notice = a.device.make_defriend(b.identity_pub)   # a removes b -> notice to b
    applied = b.apply_defriend_notice(notice)
    assert applied is True
    assert b.store.messages_by_author(a.identity_pub) == []   # a's content gone
    assert not b.store.is_known(a.identity_pub)                # removed
    assert any(d["identity_pub"] == a.identity_pub
               for d in b.store.list_disconnected())           # marker set
    # Fix 4: b must forget a's peer address, or the gossip loop keeps
    # dialing a forever (IP disclosure to a former friend).
    assert b.store.address_for(a.identity_pub) is None
    # Fix 5: b must forget a's device_views (enrollment certs / seen-
    # state / revocations) too -- a stale row could linger and even make
    # a later session wrongly refuse a's device as "revoked".
    assert b.store.load_views(a.identity_pub) == {}


def test_apply_ignores_bad_or_misdirected_notice(two_friend_nodes):
    a, b = two_friend_nodes
    good = a.device.make_defriend(b.identity_pub)
    forged = DefriendNotice(a.identity_pub, b.identity_pub, good.created_at,
                            "00" + good.signature[2:])
    assert b.apply_defriend_notice(forged) is False          # bad signature
    not_for_b = a.device.make_defriend("f" * 64)
    assert b.apply_defriend_notice(not_for_b) is False       # not targeting b
    assert b.store.is_known(a.identity_pub)                   # untouched


def test_apply_defriend_notice_idempotent(two_friend_nodes):
    a, b = two_friend_nodes
    notice = a.device.make_defriend(b.identity_pub)
    assert b.apply_defriend_notice(notice) is True
    assert b.apply_defriend_notice(notice) is False           # already applied


def test_unfriend_tears_down_and_queues(two_friend_nodes):
    a, b = two_friend_nodes
    a.unfriend(b.identity_pub)
    assert not a.store.is_known(b.identity_pub)               # b gone from a
    assert a.store.messages_by_author(b.identity_pub) == []   # b's content gone
    ob = a.store.list_outbox()
    assert ob and ob[0]["target_identity"] == b.identity_pub  # notice queued


def test_unfriend_rejects_self(tmp_path):
    a = HearthNode.create(tmp_path / "a", "Alice", "alice-phone")
    with pytest.raises(ValueError):
        a.unfriend(a.identity_pub)


def test_apply_defriend_notice_rejects_self_authored(tmp_path):
    """Fix 7: a genuinely self-signed notice that both targets AND claims
    to be authored by me must never be allowed to purge my own identity.
    Without this guard, is_known(self) is trivially true (a node is
    always its own known identity), so this would otherwise wipe all of
    a node's own content."""
    a = HearthNode.create(tmp_path / "a", "Alice", "alice-phone")
    mid = a.compose_post("hello world")
    notice = a.device.make_defriend(a.identity_pub)   # genuinely self-signed
    assert notice.verify() is True
    assert a.apply_defriend_notice(notice) is False
    assert a.store.get_message(mid) is not None       # nothing purged
    assert a.store.is_known(a.identity_pub)            # still self-known
