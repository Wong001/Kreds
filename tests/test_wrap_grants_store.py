"""Store-level wrap-grant behavior: ingest bookkeeping (target_id,
negative-cache clearing, tombstone interactions), the author-filtered
accessor, and grant GC on delete/expiry."""
import time

from hearth.messages import make_post, make_wrap_grant, make_delete
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
