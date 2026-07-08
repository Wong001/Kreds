import pytest
from fastapi.testclient import TestClient

from hearth.api import build_app
from hearth.messages import make_profile
from hearth.node import HearthNode


def _friend(host, name, tmp_path, sub):
    f = HearthNode.create(tmp_path / sub, name, name.lower() + "-phone")
    host.store.add_identity(f.identity_pub)
    # give the host a profile name for the friend
    host.store.ingest_message(make_profile(f.device, name))
    return f


@pytest.fixture
def client_with_friend(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = _friend(wong, "Freja", tmp_path, "f")
    client = TestClient(build_app(wong))
    return client, wong, freja.identity_pub


def test_unfriend_removes_and_queues(client_with_friend):
    c, node, friend = client_with_friend
    r = c.post("/api/unfriend", json={"identity_pub": friend})
    assert r.status_code == 200
    assert not node.store.is_known(friend)
    assert node.store.list_outbox()            # notice queued
    # friend no longer in /api/kreds
    assert all(k["identity_pub"] != friend
               for k in c.get("/api/kreds").json())


def test_unfriend_rejects_self(client_with_friend):
    c, node, _friend_pub = client_with_friend
    r = c.post("/api/unfriend", json={"identity_pub": node.identity_pub})
    assert r.status_code == 400


def test_state_exposes_disconnected(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = _friend(wong, "Freja", tmp_path, "f")
    # freja unfriends wong -> wong receives + applies the notice, marking
    # freja disconnected on wong's side
    notice = freja.device.make_defriend(wong.identity_pub)
    assert wong.apply_defriend_notice(notice) is True

    client = TestClient(build_app(wong))
    state = client.get("/api/state").json()
    assert any(d["identity_pub"] == freja.identity_pub and d["name"] == "Freja"
               for d in state["disconnected"])
