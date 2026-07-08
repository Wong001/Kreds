from fastapi.testclient import TestClient

from hearth.api import build_app
from hearth.node import HearthNode

PNG = b"\x89PNG\r\n\x1a\nfake"


def pair_friends(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    wong.store.add_identity(freja.identity_pub)
    freja.store.add_identity(wong.identity_pub)
    wong.ensure_enckey(); freja.ensure_enckey()
    for src, dst in ((wong, freja), (freja, wong)):
        for m in src.store.messages_not_in({}, {src.identity_pub},
                                           dst.identity_pub):
            dst.store.ingest_message(m)
    return wong, freja


def test_dm_send_and_thread_over_api(tmp_path):
    wong, freja = pair_friends(tmp_path)
    cw = TestClient(build_app(wong))
    r = cw.post("/api/dm", data={"to": freja.identity_pub,
                                 "text": "hej", "expires_seconds": ""},
                files=[("photos", ("p.png", PNG, "image/png"))])
    assert r.status_code == 200
    mid = r.json()["msg_id"]
    # carry to freja and read via her API
    for m in wong.store.messages_not_in({}, {wong.identity_pub},
                                        freja.identity_pub):
        freja.store.ingest_message(m)
    cf = TestClient(build_app(freja))
    thread = cf.get(f"/api/dm/{wong.identity_pub}").json()
    assert thread[0]["text"] == "hej" and thread[0]["from_me"] is False
    # hand-carry the ciphertext blob too (sync's BLOBS phase does this in
    # real life; messages_not_in/ingest_message above only carries the
    # signed message, not referenced blob bytes -- see test_node_dm.py)
    freja.store.put_blob(wong.store.get_blob(thread[0]["blobs"][0]))
    blob = cf.get(f"/api/dm-blob/{mid}/{thread[0]['blobs'][0]}")
    assert blob.status_code == 200 and blob.content == PNG
    convs = cf.get("/api/conversations").json()
    assert convs[0]["identity_pub"] == wong.identity_pub


def test_dm_to_stranger_is_400(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    c = TestClient(build_app(wong))
    r = c.post("/api/dm", data={"to": "bb" * 32, "text": "hi",
                                "expires_seconds": ""})
    assert r.status_code == 400


def test_qr_endpoint_returns_png(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    c = TestClient(build_app(wong))
    r = c.get("/api/qr", params={"text": "hearth-invite-code-xyz"})
    assert r.status_code == 200
    assert r.headers["content-type"] == "image/png"
    assert r.content[:8] == b"\x89PNG\r\n\x1a\n"


def test_revoked_node_gates_api_and_reports_state(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    wong.enter_revoked_state()
    c = TestClient(build_app(wong))
    assert c.get("/api/state").json()["revoked"] is True
    assert c.get("/api/feed").status_code == 410
    assert c.get("/").status_code == 200
