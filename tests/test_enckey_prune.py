"""Wart 2 (spec 2026-07-09-protocol-warts): daily rotation accumulates one
KIND_ENCKEY row per device per day forever; nothing pruned them. The prune
keeps exactly the latest per (identity, device) by the same (created_at,
seq) tie-break enckey_records uses, and TOMBSTONES the rest (reason
'superseded') - a bare DELETE would be re-fetched from peers forever."""
from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import make_enckey
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


def _tombstone_reason(s, msg_id):
    row = s._db.execute(
        "SELECT reason FROM tombstones WHERE msg_id=?", (msg_id,)).fetchone()
    return row[0] if row else None


def test_prune_keeps_exactly_latest_per_device(tmp_path):
    wong = person("wong-phone")
    node = DeviceKeys.create("wong-node")
    node.install(wong.enroll_other(node.device_pub, node.name),
                 wong.to_json()["identity_priv"])
    s = store_with(tmp_path, wong.identity_pub)
    # 3 daily rotations for device A (wong), 2 for device B (node)
    msgs_wong = [make_enckey(wong, now=t) for t in (1.0, 2.0, 3.0)]
    msgs_node = [make_enckey(node, now=t) for t in (1.0, 2.0)]
    for m in msgs_wong + msgs_node:
        assert s.ingest_message(m).accepted
    before = s.enckeys(wong.identity_pub)

    pruned = s.prune_superseded_enckeys()

    assert pruned == 3
    assert s.enckeys(wong.identity_pub) == before          # resolution unchanged
    # the latest row per device is still held
    assert s.message_kind(msgs_wong[-1].msg_id) == "enckey"
    assert s.message_kind(msgs_node[-1].msg_id) == "enckey"
    # every superseded row is gone and tombstoned as 'superseded'
    for m in msgs_wong[:-1] + msgs_node[:-1]:
        assert s.message_kind(m.msg_id) is None
        assert s.is_tombstoned(m.msg_id)
        assert _tombstone_reason(s, m.msg_id) == "superseded"


def test_prune_tiebreak_same_created_at_higher_seq_wins(tmp_path):
    wong = person("wong-phone")
    s = store_with(tmp_path, wong.identity_pub)
    older = make_enckey(wong, now=5.0)     # seq N
    newer = make_enckey(wong, now=5.0)     # same created_at, seq N+1
    assert newer.seq > older.seq
    s.ingest_message(older)
    s.ingest_message(newer)

    pruned = s.prune_superseded_enckeys()

    assert pruned == 1
    assert s.message_kind(newer.msg_id) == "enckey"
    assert s.message_kind(older.msg_id) is None
    assert s.is_tombstoned(older.msg_id)
    assert _tombstone_reason(s, older.msg_id) == "superseded"


def test_pruned_row_refused_reingest(tmp_path):
    wong = person("wong-phone")
    s = store_with(tmp_path, wong.identity_pub)
    old = make_enckey(wong, now=1.0)
    new = make_enckey(wong, now=2.0)
    s.ingest_message(old)
    s.ingest_message(new)
    s.prune_superseded_enckeys()

    # a stale peer that never learned about the prune pushes the old row
    # back over gossip -- the anti-resurrection (tombstone) gate refuses it
    r = s.ingest_message(old)

    assert (r.accepted, r.reason) == (False, "tombstoned")


def test_prune_is_idempotent_and_noop_on_single_keys(tmp_path):
    wong = person("wong-phone")
    node = DeviceKeys.create("wong-node")
    node.install(wong.enroll_other(node.device_pub, node.name),
                 wong.to_json()["identity_priv"])
    s = store_with(tmp_path, wong.identity_pub)
    s.ingest_message(make_enckey(wong, now=1.0))
    s.ingest_message(make_enckey(wong, now=2.0))
    single = make_enckey(node, now=1.0)         # node has exactly one enckey
    s.ingest_message(single)

    first = s.prune_superseded_enckeys()
    assert first == 1                            # only wong's older row

    second = s.prune_superseded_enckeys()
    assert second == 0                            # nothing left to prune

    # node's single key was never touched by either pass
    assert s.message_kind(single.msg_id) == "enckey"
    assert not s.is_tombstoned(single.msg_id)
