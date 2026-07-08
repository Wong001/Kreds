from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import make_ring
from hearth.store import Store


def wong(tmp_path):
    phone = DeviceKeys.create("wong-phone")
    IdentityCeremony().enroll_first_device(phone)
    s = Store(tmp_path / "w.db")
    s.add_identity(phone.identity_pub, is_self=True)
    return s, phone


def test_rings_latest_wins(tmp_path):
    s, phone = wong(tmp_path)
    m = "cc" * 32
    s.add_identity(m)
    assert s.ingest_message(make_ring(phone, m, "inner", now=1.0)).accepted
    assert s.rings(phone.identity_pub) == {m: "inner"}
    assert s.ingest_message(make_ring(phone, m, "kreds", now=2.0)).accepted
    assert s.rings(phone.identity_pub) == {m: "kreds"}      # latest wins


def test_rings_device_pub_tiebreak_is_deterministic(tmp_path):
    # Two ring records for the SAME member, authored from two DIFFERENT
    # devices of the same identity, with an IDENTICAL (created_at, seq)
    # collision (each device's own seq counter starts at 1, and a timer
    # can tie on created_at). Without a device_pub tie-break (mirroring
    # profiles()/profile()) the resolved ring depends on ingest order --
    # see test_profile_latest_wins_breaks_created_at_ties_by_seq for the
    # analogous profile case.
    phone = DeviceKeys.create("wong-phone")
    IdentityCeremony().enroll_first_device(phone)
    laptop = DeviceKeys.create("wong-laptop")
    laptop.install(
        phone.enroll_other(laptop.device_pub, laptop.name),
        phone.to_json()["identity_priv"])
    m = "cc" * 32
    from_phone = make_ring(phone, m, "inner", now=100.0)
    from_laptop = make_ring(laptop, m, "kreds", now=100.0)
    assert (from_phone.payload["created_at"], from_phone.seq) == \
        (from_laptop.payload["created_at"], from_laptop.seq)   # true tie

    s1 = Store(tmp_path / "s1.db")
    s1.add_identity(phone.identity_pub, is_self=True)
    s1.add_identity(m)
    assert s1.ingest_message(from_phone).accepted
    assert s1.ingest_message(from_laptop).accepted

    s2 = Store(tmp_path / "s2.db")
    s2.add_identity(phone.identity_pub, is_self=True)
    s2.add_identity(m)
    assert s2.ingest_message(from_laptop).accepted
    assert s2.ingest_message(from_phone).accepted

    r1 = s1.rings(phone.identity_pub)
    r2 = s2.rings(phone.identity_pub)
    assert r1 == r2                       # order-independent
    expected = "inner" if phone.device_pub > laptop.device_pub else "kreds"
    assert r1 == {m: expected}


def test_ring_records_route_own_device_only(tmp_path):
    s, phone = wong(tmp_path)
    m = "cc" * 32
    s.add_identity(m)
    s.ingest_message(make_ring(phone, m, "inner", now=1.0))
    ident = phone.identity_pub
    summaries = {}
    # a friend (not me) is offered NOTHING of kind ring
    friend = "dd" * 32
    s.add_identity(friend)
    to_friend = s.messages_not_in(summaries, entitled={ident}, peer_identity=friend)
    assert all(msg.payload.get("kind") != "ring" for msg in to_friend)
    # my own other device IS offered the ring record
    to_self = s.messages_not_in(summaries, entitled={ident}, peer_identity=ident)
    assert any(msg.payload.get("kind") == "ring" for msg in to_self)
