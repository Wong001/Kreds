"""Store-level wrap-grant behavior: ingest bookkeeping (target_id,
negative-cache clearing, tombstone interactions), the author-filtered
accessor, and grant GC on delete/expiry."""
import time

from hearth.messages import (KIND_WRAP_GRANT, make_post, make_wrap_grant,
                             make_delete)
from hearth.node import HearthNode


def _wrap_entry():
    return {"eph_pub": "ab" * 32, "nonce": "cd" * 12,
            "wrapped_key": "ef" * 48}


def befriend_with_enckeys(a, b):
    a.store.add_identity(b.identity_pub); b.store.add_identity(a.identity_pub)
    a.ensure_enckey(); b.ensure_enckey()
    for src, dst in ((a, b), (b, a)):
        for m in src.store.messages_not_in({}, {src.identity_pub}, dst.identity_pub):
            dst.store.ingest_message(m)


def _wall_post(node, text="wall"):
    return node.compose_post(text, scope="kreds", placement="profile")


def test_wrap_grants_accessor_unions_and_filters_author(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    mid = _wall_post(wong)
    g1 = make_wrap_grant(wong.device, mid, {"11" * 32: _wrap_entry()})
    g2 = make_wrap_grant(wong.device, mid, {"22" * 32: _wrap_entry()})
    assert wong.store.ingest_message(g1).accepted
    assert wong.store.ingest_message(g2).accepted
    got = wong.store.wrap_grants(mid, wong.identity_pub)
    assert set(got) == {"11" * 32, "22" * 32}          # grants union
    # a DIFFERENT identity's grant for the same target is never returned
    # for the author query (author-filter is the security boundary)
    gf = make_wrap_grant(freja.device, mid, {"33" * 32: _wrap_entry()})
    assert wong.store.ingest_message(gf).accepted       # shape-valid, held
    assert "33" * 32 not in wong.store.wrap_grants(mid, wong.identity_pub)


def test_grant_ingest_clears_negative_cache_for_target(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    mid = _wall_post(wong)
    freja.store.mark_undecryptable(mid)                 # poisoned earlier
    g = make_wrap_grant(wong.device, mid, {"11" * 32: _wrap_entry()})
    assert freja.store.ingest_message(g).accepted
    assert mid not in freja.store.undecryptable_ids()   # un-poisoned


def test_grant_for_tombstoned_target_dies_on_arrival(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    mid = _wall_post(wong)
    g = make_wrap_grant(wong.device, mid, {"11" * 32: _wrap_entry()})
    d = make_delete(wong.device, mid)
    # order: delete lands at freja BEFORE the grant does
    for m in wong.store.messages_not_in({}, {wong.identity_pub}, freja.identity_pub):
        freja.store.ingest_message(m)
    assert freja.store.ingest_message(d).accepted
    res = freja.store.ingest_message(g)
    assert res.accepted                                 # seq consumed...
    assert freja.store.wrap_grants(mid, wong.identity_pub) == {}   # ...but dead
    assert freja.store.is_tombstoned(g.msg_id)          # stops re-offering


def test_delete_ingest_gcs_held_grants(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    mid = _wall_post(wong)
    g = make_wrap_grant(wong.device, mid, {"11" * 32: _wrap_entry()})
    for m in wong.store.messages_not_in({}, {wong.identity_pub}, freja.identity_pub):
        freja.store.ingest_message(m)
    assert freja.store.ingest_message(g).accepted
    assert freja.store.wrap_grants(mid, wong.identity_pub) != {}
    freja.store.ingest_message(make_delete(wong.device, mid))
    assert freja.store.wrap_grants(mid, wong.identity_pub) == {}
    assert freja.store.is_tombstoned(g.msg_id)


def test_sweep_expired_gcs_grants_of_expired_targets(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    mid = wong.compose_post("kort", scope="kreds", placement="profile",
                            expires_seconds=1)
    g = make_wrap_grant(wong.device, mid, {"11" * 32: _wrap_entry()})
    assert wong.store.ingest_message(g).accepted
    wong.store.sweep_expired(now=time.time() + 5)
    assert wong.store.wrap_grants(mid, wong.identity_pub) == {}
    assert wong.store.is_tombstoned(g.msg_id)


def test_routing_gate_offers_granted_post(tmp_path):
    # Freja was NOT wrapped at post time; a grant naming her device must
    # open the offer gate for the post AND for the grant itself.
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mid = _wall_post(wong)                          # composed pre-friendship
    befriend_with_enckeys(wong, freja)
    offered = {m.msg_id for m in wong.store.messages_not_in(
        {}, {wong.identity_pub}, freja.identity_pub)}
    assert mid not in offered                       # not wrapped, no grant
    g = make_wrap_grant(wong.device, mid,
                        {freja.device.device_pub: _wrap_entry()})
    assert wong.store.ingest_message(g).accepted
    offered = {m.msg_id for m in wong.store.messages_not_in(
        {}, {wong.identity_pub}, freja.identity_pub)}
    assert mid in offered                           # grant opened the gate
    assert g.msg_id in offered                      # grant routes to named device


def test_routing_gate_ignores_non_author_grants(tmp_path):
    # A grant signed by someone OTHER than the post's author must not
    # widen the post's audience (hostile-friend containment).
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mikkel = HearthNode.create(tmp_path / "m", "Mikkel", "mikkel-phone")
    befriend_with_enckeys(wong, freja); befriend_with_enckeys(wong, mikkel)
    befriend_with_enckeys(freja, mikkel)
    wong.set_ring(freja.identity_pub, "inner")
    mid = wong.compose_post("kun inner", scope="inner", placement="profile")
    # Freja (holds the post) maliciously "grants" it to Mikkel
    g = make_wrap_grant(freja.device, mid,
                        {mikkel.device.device_pub: _wrap_entry()})
    for m in wong.store.messages_not_in({}, {wong.identity_pub}, freja.identity_pub):
        freja.store.ingest_message(m)
    assert freja.store.ingest_message(g).accepted
    offered = {m.msg_id for m in freja.store.messages_not_in(
        {}, {wong.identity_pub, freja.identity_pub}, mikkel.identity_pub)}
    assert mid not in offered              # her grant is inert for routing


def test_prune_superseded_wrap_grants_keeps_newest(tmp_path):
    # Daily enc rotation re-mints one grant per granted post per day; the
    # prune must tombstone all but the latest per (author, target) so the
    # table doesn't grow forever (mirrors prune_superseded_enckeys).
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    mid = _wall_post(wong)
    g1 = make_wrap_grant(wong.device, mid, {"11" * 32: _wrap_entry()},
                         now=1000.0)
    g2 = make_wrap_grant(wong.device, mid, {"22" * 32: _wrap_entry()},
                         now=2000.0)
    g3 = make_wrap_grant(wong.device, mid, {"33" * 32: _wrap_entry()},
                         now=3000.0)
    for g in (g1, g2, g3):
        assert wong.store.ingest_message(g).accepted
    assert wong.store.prune_superseded_wrap_grants() == 2   # 3 rows -> 1
    assert wong.store.is_tombstoned(g1.msg_id)
    assert wong.store.is_tombstoned(g2.msg_id)
    assert wong.store.message_kind(g3.msg_id) == KIND_WRAP_GRANT   # survivor
    # the accessor now returns only the surviving (newest) grant's wraps
    assert set(wong.store.wrap_grants(mid, wong.identity_pub)) == {"33" * 32}
    assert wong.store.prune_superseded_wrap_grants() == 0   # idempotent


def test_prune_superseded_wrap_grants_independent_per_author_and_target(tmp_path):
    # Grouping is (identity_pub, target_id): different authors of the same
    # target, and different targets of one author, prune independently.
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    a = _wall_post(wong, "a")
    b = _wall_post(wong, "b")
    # target a, author wong: two rows, older superseded
    a1 = make_wrap_grant(wong.device, a, {"11" * 32: _wrap_entry()}, now=1.0)
    a2 = make_wrap_grant(wong.device, a, {"22" * 32: _wrap_entry()}, now=2.0)
    # target b, author wong: sole row, kept
    b1 = make_wrap_grant(wong.device, b, {"44" * 32: _wrap_entry()}, now=1.0)
    # target a, author freja: different (author,target) group -> kept
    af = make_wrap_grant(freja.device, a, {"33" * 32: _wrap_entry()}, now=1.0)
    for g in (a1, a2, b1, af):
        assert wong.store.ingest_message(g).accepted
    assert wong.store.prune_superseded_wrap_grants() == 1   # only a1 drops
    assert wong.store.is_tombstoned(a1.msg_id)
    for kept in (a2, b1, af):
        assert wong.store.message_kind(kept.msg_id) == KIND_WRAP_GRANT


def test_uncached_ids_include_grant_wrapped_posts(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mid = _wall_post(wong)
    befriend_with_enckeys(wong, freja)
    # freja holds the row but is not in payload wraps
    post = wong.store.get_message(mid)
    freja.store.ingest_message(post)
    assert mid not in freja.store.uncached_message_ids(freja.identity_pub)
    g = make_wrap_grant(wong.device, mid,
                        {freja.device.device_pub: _wrap_entry()})
    freja.store.ingest_message(g)
    assert mid in freja.store.uncached_message_ids(freja.identity_pub)
