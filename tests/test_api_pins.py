"""API surface for collage pins (Slice A): block-pin / block-unpin /
block-span, mirroring /api/block-size's contract."""
import pytest
from fastapi.testclient import TestClient

from hearth.api import build_app
from hearth.node import HearthNode

# Same fake-PNG idiom as test_api_scoped_posts.py/test_api.py's photo-upload
# tests -- compose_post never decodes the bytes, only the magic prefix
# matters for /api/blob's sniffer.
PNG = b"\x89PNG\r\n\x1a\nfakepixels"


@pytest.fixture
def client_self(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    return TestClient(build_app(node)), node


def test_pin_endpoints_roundtrip(client_self):
    c, node = client_self
    r = c.post("/api/post", data={"text": "blok", "scope": "kreds",
                                  "placement": "profile"})
    mid = r.json()["msg_id"]
    assert c.post("/api/block-pin", json={"msg_id": mid, "x": 1, "y": 0,
                                          "w": 2, "h": 2}).status_code == 200
    prof = c.get("/api/profile/" + node.identity_pub).json()
    blk = next(p for p in prof["wall"] if p["msg_id"] == mid)
    assert blk["pin"] == {"x": 1, "y": 0, "w": 2, "h": 2}
    assert c.post("/api/block-unpin", json={"msg_id": mid}).status_code == 200
    prof = c.get("/api/profile/" + node.identity_pub).json()
    blk = next(p for p in prof["wall"] if p["msg_id"] == mid)
    assert blk["pin"] is None and blk["span"] == {"w": 2, "h": 2}
    assert c.post("/api/block-span", json={"msg_id": mid, "w": 1,
                                           "h": 1}).status_code == 200


def test_pin_endpoint_400s(client_self):
    c, node = client_self
    r = c.post("/api/post", data={"text": "blok", "scope": "kreds",
                                  "placement": "profile"})
    mid = r.json()["msg_id"]
    assert c.post("/api/block-pin", json={"msg_id": mid, "x": 3, "y": 0,
                                          "w": 2, "h": 1}).status_code == 400
    assert c.post("/api/block-span", json={"msg_id": "zz", "w": 1,
                                           "h": 1}).status_code == 400
    # pinned block refuses span (one source of truth)
    assert c.post("/api/block-pin", json={"msg_id": mid, "x": 0, "y": 0,
                                          "w": 1, "h": 1}).status_code == 200
    assert c.post("/api/block-span", json={"msg_id": mid, "w": 2,
                                           "h": 2}).status_code == 400


def test_post_place_0_skips_auto_place(client_self):
    # place=0 (the deck grow flow): the post exists but is NOT auto-pinned
    # - it is album-bound deck content, not a wall block (spec 2026-07-14).
    c, node = client_self
    r = c.post("/api/post", data={"text": "", "scope": "kreds",
                                  "placement": "profile", "place": "0"},
               files=[("photos", ("p.png", PNG, "image/png"))])
    assert r.status_code == 200
    mid = r.json()["msg_id"]
    lay = node.store.profile_layout(node.identity_pub)
    assert mid not in lay["pins"] and mid not in lay["spans"]


def test_album_api_roundtrip(client_self):
    c, node = client_self
    # two profile photo posts through the API (multipart pattern mirrors
    # test_api_scoped_posts.py's photo-upload tests), then group, then ungroup
    r1 = c.post("/api/post", data={"text": "one", "scope": "kreds",
                                   "placement": "profile"},
               files=[("photos", ("p1.png", PNG, "image/png"))])
    r2 = c.post("/api/post", data={"text": "two", "scope": "kreds",
                                   "placement": "profile"},
               files=[("photos", ("p2.png", PNG, "image/png"))])
    m1, m2 = r1.json()["msg_id"], r2.json()["msg_id"]
    r = c.post("/api/album", json={"members": [m1, m2]})
    assert r.status_code == 200
    aid = r.json()["album_id"]
    prof = c.get("/api/profile/" + node.identity_pub).json()
    alb = next(p for p in prof["wall"] if p.get("album"))
    assert alb["msg_id"] == aid and alb["count"] == 2
    assert c.post("/api/album", json={"members": ["zz"]}).status_code == 400
    assert c.post("/api/album",
                  json={"members": [], "album_id": aid}).status_code == 200
