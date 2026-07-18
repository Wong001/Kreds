from fastapi.testclient import TestClient

from hearth.api import build_app
from hearth.node import HearthNode
from tests.test_imagegate import animated_gif_bytes


def client(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    return TestClient(build_app(node)), node


def test_state_and_index(tmp_path):
    c, node = client(tmp_path)
    assert c.get("/").status_code == 200
    s = c.get("/api/state").json()
    assert s["identity_pub"] == node.identity_pub
    assert s["profile_name"] == "Wong"
    assert s["device_name"] == "wong-phone"
    assert s["friends"] == []


def test_post_feed_blob_delete_cycle(tmp_path):
    c, _ = client(tmp_path)
    gif = animated_gif_bytes()              # byte-identity is the point below
    r = c.post("/api/post",
               data={"text": "hello", "scope": "kreds", "expires_seconds": ""},
               files=[("photos", ("p.gif", gif, "image/gif"))])
    assert r.status_code == 200
    mid = r.json()["msg_id"]
    feed = c.get("/api/feed").json()
    assert feed[0]["text"] == "hello" and feed[0]["mine"] is True
    assert feed[0]["scope"] == "kreds"
    blob = c.get(f"/api/post-blob/{mid}/{feed[0]['blobs'][0]}")
    assert blob.status_code == 200
    assert blob.content == gif
    assert c.post("/api/delete", json={"msg_id": mid}).status_code == 200
    assert c.get("/api/feed").json() == []


def test_expiring_post_via_api(tmp_path):
    c, node = client(tmp_path)
    c.post("/api/post", data={"text": "brief", "expires_seconds": "3600"})
    feed = c.get("/api/feed").json()
    assert feed[0]["expires_at"] is not None


def test_profile_update(tmp_path):
    c, _ = client(tmp_path)
    c.post("/api/profile", data={"name": "Wong II"})
    assert c.get("/api/state").json()["profile_name"] == "Wong II"


def test_unknown_blob_404(tmp_path):
    c, _ = client(tmp_path)
    assert c.get("/api/blob/" + "ab" * 32).status_code == 404


def test_blob_endpoints_are_cached_immutable(tmp_path):
    """MINOR (whole-branch review): content-addressed bytes never change
    under the same hash - cache them, or the heal loop's per-tick profile
    re-render refetches every visible tile for nothing."""
    c, _ = client(tmp_path)
    gif = animated_gif_bytes()
    r = c.post("/api/post",
               data={"text": "", "scope": "kreds", "expires_seconds": ""},
               files=[("photos", ("p.gif", gif, "image/gif"))])
    mid = r.json()["msg_id"]
    h = c.get("/api/feed").json()[0]["blobs"][0]
    for resp in (c.get(f"/api/post-blob/{mid}/{h}"), c.get(f"/api/blob/{h}")):
        assert resp.headers["cache-control"] == \
            "private, max-age=31536000, immutable"
        assert resp.headers["x-content-type-options"] == "nosniff"


def test_ws_notified_on_post(tmp_path):
    c, _ = client(tmp_path)
    with c.websocket_connect("/ws") as ws:
        c.post("/api/post", data={"text": "ping", "expires_seconds": ""})
        assert ws.receive_text() == "changed"


def test_oversized_photo_rejected_413(tmp_path):
    from hearth.messages import MAX_IMAGE_UPLOAD
    c, _ = client(tmp_path)
    big = b"\x89PNG" + b"\x00" * MAX_IMAGE_UPLOAD   # cap + 4 bytes
    r = c.post("/api/post",
               data={"text": "too big", "expires_seconds": ""},
               files=[("photos", ("big.png", big, "image/png"))])
    assert r.status_code == 413
    assert c.get("/api/feed").json() == []        # nothing was stored


def test_big_photo_upload_now_accepted(tmp_path):
    from tests.test_imagegate import noise_jpeg_bytes
    c, _ = client(tmp_path)
    big = noise_jpeg_bytes(4000, 3000)
    assert len(big) > 5 * 1024 * 1024
    r = c.post("/api/post", data={"text": "big", "scope": "kreds",
                                  "expires_seconds": ""},
               files=[("photos", ("p.jpg", big, "image/jpeg"))])
    assert r.status_code == 200


def test_image_over_upload_cap_413(tmp_path):
    from hearth.messages import MAX_IMAGE_UPLOAD
    c, _ = client(tmp_path)
    r = c.post("/api/post", data={"text": "too big", "scope": "kreds",
                                  "expires_seconds": ""},
               files=[("photos", ("p.jpg",
                       b"\xff\xd8" + b"x" * MAX_IMAGE_UPLOAD,
                       "image/jpeg"))])
    assert r.status_code == 413
    assert "50 MB" in r.text


def test_missing_body_keys_return_400(tmp_path):
    c, _ = client(tmp_path)
    assert c.post("/api/delete", json={}).status_code == 400
    assert c.post("/api/profile", data={}).status_code == 422
    assert c.post("/api/device/revoke", json={}).status_code == 400
    assert c.post("/api/ring", json={}).status_code == 400


def test_respond_endpoints_and_settings_toggle(tmp_path):
    c, node = client(tmp_path)
    pid = node.compose_post("own post", "kreds")
    r = c.post("/api/react", json={"msg_id": pid, "token": "fire"})
    assert r.status_code == 200
    r = c.post("/api/comment", json={"msg_id": pid, "text": "selvsvar"})
    assert r.status_code == 200
    node.process_responses()
    row = [p for p in node.feed() if p["msg_id"] == pid][0]
    assert row["responses"]["reactions"] == {"fire": 1}
    r = c.post("/api/comment", json={"msg_id": pid, "text": "x" * 501})
    assert r.status_code == 400
    s = c.get("/api/settings").json()
    assert s["public_engagement"] is False
    r = c.post("/api/settings", json={"public_engagement": True})
    assert r.status_code == 200
    assert c.get("/api/settings").json()["public_engagement"] is True


def test_retract_and_response_remove_endpoints(tmp_path):
    c, node = client(tmp_path)
    pid = node.compose_post("own post", "kreds")
    c.post("/api/comment", json={"msg_id": pid, "text": "first"})
    node.process_responses()
    row = [p for p in node.feed() if p["msg_id"] == pid][0]
    created_at = row["responses"]["comments"][0]["created_at"]
    r = c.post("/api/retract", json={"msg_id": pid, "created_at": created_at})
    assert r.status_code == 200
    node.process_responses()
    row = [p for p in node.feed() if p["msg_id"] == pid][0]
    assert row["responses"]["comments"] == []

    # response-remove: 400 when the target post isn't this node's own
    other = HearthNode.create(tmp_path / "other", "Other", "other-dev")
    other_pid = other.compose_post("someone else's post", "kreds")
    for m in other.store.messages_not_in({}, {other.identity_pub},
                                         node.identity_pub):
        node.store.ingest_message(m)
    r = c.post("/api/response-remove",
               json={"msg_id": other_pid, "responder": node.identity_pub,
                     "created_at": created_at})
    assert r.status_code == 400

    # moderating a real own-post entry succeeds
    c.post("/api/comment", json={"msg_id": pid, "text": "second"})
    node.process_responses()
    row = [p for p in node.feed() if p["msg_id"] == pid][0]
    created_at2 = row["responses"]["comments"][0]["created_at"]
    r = c.post("/api/response-remove",
               json={"msg_id": pid, "responder": node.identity_pub,
                     "created_at": created_at2})
    assert r.status_code == 200
    row = [p for p in node.feed() if p["msg_id"] == pid][0]
    assert row["responses"]["comments"] == []


def test_settings_close_behavior_still_independent_of_public_engagement(tmp_path):
    c, _ = client(tmp_path)
    assert c.post("/api/settings",
                  json={"close_behavior": "keep"}).status_code == 200
    s = c.get("/api/settings").json()
    assert s["close_behavior"] == "keep" and s["public_engagement"] is False
    assert c.post("/api/settings",
                  json={"close_behavior": "bad"}).status_code == 400


def test_service_worker_served_at_root(tmp_path):
    # Must be served at the app ROOT: a worker registered from under
    # /static/ can only ever control /static/*, not the whole app.
    c, _ = client(tmp_path)
    r = c.get("/sw.js")
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("application/javascript")
    from hearth.api import WEB_DIR
    # Normalize newlines: the HTTP body carries the file's raw bytes (CRLF on
    # a Windows checkout with autocrlf), while read_text() universal-newline-
    # normalizes to LF. Compare content, not line-ending encoding.
    served = r.text.replace("\r\n", "\n")
    on_disk = (WEB_DIR / "sw.js").read_text(encoding="utf-8").replace("\r\n", "\n")
    assert served == on_disk
    assert "kreds-shell" in served and "self.addEventListener" in served
