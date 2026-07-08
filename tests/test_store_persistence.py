import pytest

from hearth.identity import (
    DeviceKeys, DeviceView, IdentityCeremony, RevocationCert, SeenSet,
)
from hearth.store import Store


def make_store(tmp_path, name="a.db"):
    return Store(tmp_path / name)


def test_identities_and_meta(tmp_path):
    s = make_store(tmp_path)
    s.add_identity("aa" * 32, is_self=True)
    s.add_identity("bb" * 32)
    s.add_identity("bb" * 32)                    # idempotent
    assert s.self_identity() == "aa" * 32
    assert set(s.known_identities()) == {"aa" * 32, "bb" * 32}
    assert s.is_known("bb" * 32) and not s.is_known("cc" * 32)
    s.set_meta("gossip_addr", "127.0.0.1:7101")
    assert s.get_meta("gossip_addr") == "127.0.0.1:7101"
    assert s.get_meta("nope") is None


def test_device_views_persist_across_reopen(tmp_path):
    phone = DeviceKeys.create("phone")
    IdentityCeremony().enroll_first_device(phone)
    ident = phone.identity_pub
    rev = phone.make_revocation("dd" * 32, 5)

    s = make_store(tmp_path)
    s.add_identity(ident, is_self=True)
    seen = SeenSet()
    for i in (1, 2, 7):
        seen.add(i)
    views = {phone.device_pub: DeviceView(cert=phone.cert, seen=seen),
             "dd" * 32: DeviceView(cert=None, revocation=rev)}
    s.save_views(ident, views)

    s2 = make_store(tmp_path)                    # reopen same file
    loaded = s2.load_views(ident)
    assert loaded[phone.device_pub].cert == phone.cert
    assert loaded[phone.device_pub].seen.has(7)
    assert not loaded[phone.device_pub].seen.has(3)
    assert loaded["dd" * 32].revocation == rev
    assert [r for r in s2.list_revocations()] == [rev]
    summaries = s2.all_summaries()
    assert summaries[ident][phone.device_pub] == {"contiguous": 2,
                                                  "sparse": [7]}


def test_peers(tmp_path):
    s = make_store(tmp_path)
    s.add_peer("127.0.0.1:7102", "aa" * 32)
    s.add_peer("127.0.0.1:7102", "aa" * 32)      # idempotent
    s.add_peer("127.0.0.1:7103")
    addrs = {p["address"] for p in s.list_peers()}
    assert addrs == {"127.0.0.1:7102", "127.0.0.1:7103"}


def test_blobs_roundtrip_and_size_cap(tmp_path):
    from hearth.messages import MAX_BLOB_BYTES, blob_hash
    s = make_store(tmp_path)
    data = b"photo-bytes"
    h = s.put_blob(data)
    assert h == blob_hash(data)
    assert s.get_blob(h) == data and s.has_blob(h)
    assert s.get_blob("ee" * 32) is None
    with pytest.raises(ValueError):
        s.put_blob(b"x" * (MAX_BLOB_BYTES + 1))
