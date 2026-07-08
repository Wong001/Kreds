from fastapi.testclient import TestClient

from hearth.api import build_app
from hearth.node import HearthNode


def _friend(host, name, tmp_path, sub):
    f = HearthNode.create(tmp_path / sub, name, name.lower() + "-phone")
    host.store.add_identity(f.identity_pub)
    # give the host a profile name for the friend
    from hearth.messages import make_profile
    host.store.ingest_message(make_profile(f.device, name))
    return f


def test_kreds_list_rings_and_since(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = _friend(wong, "Freja", tmp_path, "f")
    mikkel = _friend(wong, "Mikkel", tmp_path, "m")
    wong.set_ring(freja.identity_pub, "inner")
    rows = {r["identity_pub"]: r for r in wong.kreds_list()}
    assert rows[freja.identity_pub]["ring"] == "inner"
    assert rows[mikkel.identity_pub]["ring"] == "kreds"     # default
    assert rows[freja.identity_pub]["name"] == "Freja"
    assert isinstance(rows[freja.identity_pub]["since"], (int, float))
    assert wong.identity_pub not in rows                    # self excluded


def test_profile_view_has_ring_and_since(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = _friend(wong, "Freja", tmp_path, "f")
    wong.set_ring(freja.identity_pub, "inner")
    pv = wong.profile_view(freja.identity_pub)
    assert pv["ring"] == "inner"
    assert isinstance(pv["since"], (int, float))


def test_api_kreds_endpoint(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = _friend(wong, "Freja", tmp_path, "f")
    mikkel = _friend(wong, "Mikkel", tmp_path, "m")
    wong.set_ring(freja.identity_pub, "inner")

    client = TestClient(build_app(wong))
    resp = client.get("/api/kreds")
    assert resp.status_code == 200
    kreds_list = resp.json()
    assert isinstance(kreds_list, list)

    rows = {r["identity_pub"]: r for r in kreds_list}
    assert rows[freja.identity_pub]["ring"] == "inner"
    assert rows[freja.identity_pub]["name"] == "Freja"
    assert "since" in rows[freja.identity_pub]
    assert rows[mikkel.identity_pub]["ring"] == "kreds"
    assert len(rows) == 2  # self excluded
