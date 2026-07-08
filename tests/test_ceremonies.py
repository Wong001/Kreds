import json

import pytest

from hearth.node import HearthNode


def test_friend_ceremony_full_flow(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    wong.store.set_meta("gossip_addr", "127.0.0.1:7101")
    freja.store.set_meta("gossip_addr", "127.0.0.1:7103")

    invite = wong.create_invite()
    response = freja.respond_to_invite(invite)
    assert freja.store.is_known(wong.identity_pub) is False  # not yet
    final = wong.finalize_invite(response)
    assert wong.store.is_known(freja.identity_pub)           # A added
    freja.complete_invite(final)
    assert freja.store.is_known(wong.identity_pub)           # B added
    # both learned each other's gossip address
    assert any(p["address"] == "127.0.0.1:7103"
               for p in wong.store.list_peers())
    assert any(p["address"] == "127.0.0.1:7101"
               for p in freja.store.list_peers())


def test_tampered_final_rejected(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mallory = HearthNode.create(tmp_path / "m", "Mallory", "mal-phone")

    invite = wong.create_invite()
    response = freja.respond_to_invite(invite)
    wong.finalize_invite(response)
    # Mallory intercepts and substitutes her own signature.
    forged = json.dumps({"t": "hearth-final",
                         "nonce": json.loads(response)["peer_nonce"],
                         "sig": "ab" * 64})
    with pytest.raises(ValueError):
        freja.complete_invite(forged)
    assert freja.store.is_known(wong.identity_pub) is False


def test_replayed_invite_nonce_rejected(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    invite = wong.create_invite()
    response = freja.respond_to_invite(invite)
    wong.finalize_invite(response)
    with pytest.raises(ValueError):                 # nonce consumed
        wong.finalize_invite(response)


def test_pairing_full_flow(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    wong.store.set_meta("gossip_addr", "127.0.0.1:7101")
    # existing friendship so the package carries it
    invite = wong.create_invite()
    freja.complete_invite(wong.finalize_invite(
        freja.respond_to_invite(invite)))

    req = HearthNode.pair_request(tmp_path / "h", "wong-homenode")
    pkg = wong.accept_pairing(req)
    home = HearthNode.pair_install(tmp_path / "h", pkg)

    assert home.identity_pub == wong.identity_pub    # same person
    assert home.device.cert.verify()
    assert home.device.device_pub != wong.device.device_pub
    assert freja.identity_pub in home.store.known_identities()
    assert any(p["address"] == "127.0.0.1:7101"
               for p in home.store.list_peers())
    # wong's own view now includes the home node
    names = {d["name"] for d in wong.devices()}
    assert "wong-homenode" in names


def test_api_ceremony_endpoints(tmp_path):
    from fastapi.testclient import TestClient
    from hearth.api import build_app
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    cw, cf = TestClient(build_app(wong)), TestClient(build_app(freja))

    invite = cw.post("/api/friend/invite").json()["payload"]
    response = cf.post("/api/friend/respond",
                       json={"payload": invite}).json()["payload"]
    final = cw.post("/api/friend/finalize",
                    json={"payload": response}).json()["payload"]
    assert cf.post("/api/friend/complete",
                   json={"payload": final}).status_code == 200
    assert len(cw.get("/api/state").json()["friends"]) == 1
    assert len(cf.get("/api/state").json()["friends"]) == 1
    # garbage payload -> 400, not 500
    assert cf.post("/api/friend/respond",
                   json={"payload": "not json"}).status_code == 400


def test_missing_key_payload_returns_400_not_500(tmp_path):
    from fastapi.testclient import TestClient
    from hearth.api import build_app
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    c = TestClient(build_app(n))
    r = c.post("/api/friend/respond", json={"payload": "{}"})
    assert r.status_code == 400


def test_failed_final_does_not_consume_pending_response(tmp_path):
    import json as _json
    import pytest
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    invite = wong.create_invite()
    response = freja.respond_to_invite(invite)
    final = wong.finalize_invite(response)
    peer_nonce = _json.loads(response)["peer_nonce"]
    forged = _json.dumps({"t": "hearth-final", "nonce": peer_nonce,
                          "sig": "ab" * 64})
    with pytest.raises(ValueError):
        freja.complete_invite(forged)       # rejected...
    freja.complete_invite(final)            # ...but the real final still works
    assert freja.store.is_known(wong.identity_pub)
