from fastapi.testclient import TestClient

from hearth.api import build_app
from hearth.node import HearthNode

PNG = b"\x89PNG\r\n\x1a\nfakepixels"


def client(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    return TestClient(build_app(node)), node


def befriend_with_enckeys(a, b):
    a.store.add_identity(b.identity_pub); b.store.add_identity(a.identity_pub)
    a.ensure_enckey(); b.ensure_enckey()
    for src, dst in ((a, b), (b, a)):
        for m in src.store.messages_not_in({}, {src.identity_pub}, dst.identity_pub):
            dst.store.ingest_message(m)


def test_post_with_explicit_scope_shows_in_feed(tmp_path):
    c, node = client(tmp_path)
    r = c.post("/api/post", data={"text": "hi", "scope": "inner"})
    assert r.status_code == 200
    feed = c.get("/api/feed").json()
    assert feed[0]["text"] == "hi" and feed[0]["scope"] == "inner"


def test_post_with_no_scope_defaults_to_kreds(tmp_path):
    c, node = client(tmp_path)
    r = c.post("/api/post", data={"text": "hi"})
    assert r.status_code == 200
    feed = c.get("/api/feed").json()
    assert feed[0]["text"] == "hi" and feed[0]["scope"] == "kreds"


def test_ring_endpoint_moves_friend_and_future_inner_post_wraps_reflect_it(tmp_path):
    c, wong = client(tmp_path)
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    # before the ring move, freja is kreds by default -> excluded from inner
    before = c.post("/api/post", data={"text": "before", "scope": "inner"})
    before_wraps = wong.store.get_message(before.json()["msg_id"]).payload["wraps"]
    assert freja.device.device_pub not in before_wraps

    r = c.post("/api/ring", json={"identity_pub": freja.identity_pub,
                                  "ring": "inner"})
    assert r.status_code == 200
    assert r.json()["ok"] is True

    after = c.post("/api/post", data={"text": "after", "scope": "inner"})
    after_wraps = wong.store.get_message(after.json()["msg_id"]).payload["wraps"]
    assert freja.device.device_pub in after_wraps


def test_ring_endpoint_rejects_unknown_identity(tmp_path):
    c, node = client(tmp_path)
    r = c.post("/api/ring", json={"identity_pub": "bb" * 32, "ring": "inner"})
    assert r.status_code == 400


def test_post_blob_decrypts_photo(tmp_path):
    c, node = client(tmp_path)
    r = c.post("/api/post", data={"text": "pic", "scope": "kreds"},
               files=[("photos", ("p.png", PNG, "image/png"))])
    assert r.status_code == 200
    mid = r.json()["msg_id"]
    feed = c.get("/api/feed").json()
    h = feed[0]["blobs"][0]
    blob = c.get(f"/api/post-blob/{mid}/{h}")
    assert blob.status_code == 200
    assert blob.content == PNG
    # the raw store blob is ciphertext, not the plaintext PNG
    raw = c.get(f"/api/blob/{h}")
    assert raw.content != PNG


def test_post_blob_unknown_returns_404(tmp_path):
    c, node = client(tmp_path)
    r = c.post("/api/post", data={"text": "hi", "scope": "kreds"})
    mid = r.json()["msg_id"]
    assert c.get(f"/api/post-blob/{mid}/" + "ab" * 32).status_code == 404
