import json

from hearth.node import HearthNode


def test_onion_key_persists_and_loads(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "phone")
    assert n.onion_key is None
    n.save_onion_key("ED25519-V3:fakeblob")
    assert (tmp_path / "n" / "onion_key").read_text() == "ED25519-V3:fakeblob"
    n.close()
    n2 = HearthNode(tmp_path / "n")
    assert n2.onion_key == "ED25519-V3:fakeblob"


def test_onion_key_wiped_on_revocation(tmp_path):
    d = tmp_path / "n"
    n = HearthNode.create(d, "Wong", "phone")
    n.save_onion_key("ED25519-V3:fakeblob")
    n.enter_revoked_state()
    assert n.onion_key is None
    assert not (d / "onion_key").exists()


def test_remove_peer(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "phone")
    n.store.add_peer("127.0.0.1:7101", "aa" * 32)
    n.store.add_peer("abc.onion:7101", "aa" * 32)
    n.store.remove_peer("127.0.0.1:7101")
    addrs = [p["address"] for p in n.store.list_peers()]
    assert addrs == ["abc.onion:7101"]
