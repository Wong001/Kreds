from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import make_post
from hearth.store import Store


def wong(tmp_path):
    phone = DeviceKeys.create("wong-phone")
    IdentityCeremony().enroll_first_device(phone)
    s = Store(tmp_path / "w.db")
    s.add_identity(phone.identity_pub, is_self=True)
    return s, phone


def _post(phone, scope, wraps, created_at=100.0):
    return make_post(phone, scope, body_nonce="ab" * 12, body_ct="deadbeef",
                     wraps=wraps, blob_refs=[], created_at=created_at)


# Routing resolves a peer's devices via load_views(peer_identity), so the
# audience test builds real enrolled devices (their real device_pubs are the
# wraps keys) rather than synthetic hex — modelled on tests/test_sync_dm.py.
def test_post_routing_by_wrapset(tmp_path):
    from hearth.node import HearthNode
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mikkel = HearthNode.create(tmp_path / "m", "Mikkel", "mikkel-phone")
    for a in (freja, mikkel):
        wong.store.add_identity(a.identity_pub)
        wong.store.load_views(a.identity_pub)  # ensure views table has them
    # store freja's + mikkel's device views so the router can resolve them
    from hearth.identity import DeviceView
    wong.store.save_views(freja.identity_pub,
        {freja.device.device_pub: DeviceView(cert=freja.device.cert)})
    wong.store.save_views(mikkel.identity_pub,
        {mikkel.device.device_pub: DeviceView(cert=mikkel.device.cert)})
    # inner post wrapped to freja's device only
    m = make_post(wong.device, "inner", body_nonce="ab" * 12,
                  body_ct="de", wraps={freja.device.device_pub: {
                      "eph_pub": "22" * 32, "nonce": "33" * 12,
                      "wrapped_key": "deadbeef"}}, created_at=100.0)
    wong.store.ingest_message(m)
    ent = {wong.identity_pub}
    # offered to freja (in wraps) and to wong (author); NOT to mikkel
    to_f = wong.store.messages_not_in({}, ent, freja.identity_pub)
    to_m = wong.store.messages_not_in({}, ent, mikkel.identity_pub)
    to_w = wong.store.messages_not_in({}, ent, wong.identity_pub)
    assert any(x.msg_id == m.msg_id for x in to_f)
    assert all(x.msg_id != m.msg_id for x in to_m)
    assert any(x.msg_id == m.msg_id for x in to_w)


def test_post_messages_accessor(tmp_path):
    s, phone = wong(tmp_path)
    a = _post(phone, "kreds", {}, created_at=1.0)
    b = _post(phone, "kreds", {}, created_at=2.0)
    s.ingest_message(a); s.ingest_message(b)
    ids = [m.msg_id for m in s.post_messages()]
    assert ids == [b.msg_id, a.msg_id]                   # newest first


def test_uncached_message_ids_includes_own_posts(tmp_path):
    s, phone = wong(tmp_path)
    p = _post(phone, "kreds", {}, created_at=1.0)      # authored by self
    s.ingest_message(p)
    assert p.msg_id in s.uncached_message_ids(phone.identity_pub)
    s.cache_message_key(p.msg_id, "cafe01")
    assert p.msg_id not in s.uncached_message_ids(phone.identity_pub)
