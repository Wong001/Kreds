"""API surface for collage pins (Slice A): block-pin / block-unpin /
block-span, mirroring /api/block-size's contract."""
import pytest
from fastapi.testclient import TestClient

from hearth.api import build_app
from hearth.node import HearthNode


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
