"""API-level tests for placement on /api/post and wall/journal on
/api/profile. Mirrors the client(tmp_path) idiom from test_api_profile.py
(no `client_self` fixture exists in the repo yet -- the brief's placeholder
is defined here as a real pytest fixture, single node, no friends needed
since we only inspect our own feed/profile)."""
import pytest
from fastapi.testclient import TestClient

from hearth.api import build_app
from hearth.node import HearthNode


@pytest.fixture
def client_self(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    return TestClient(build_app(node)), node


def test_api_post_placement_and_profile_split(client_self):
    c, node = client_self
    c.post("/api/post", data={"text": "j", "scope": "kreds", "placement": "journal"})
    c.post("/api/post", data={"text": "p", "scope": "kreds", "placement": "profile"})
    feed = c.get("/api/feed").json()
    assert any(r["text"] == "j" for r in feed)
    assert all(r["text"] != "p" for r in feed)                 # profile post not in feed
    prof = c.get(f"/api/profile/{node.identity_pub}").json()
    assert any(r["text"] == "p" for r in prof["wall"])
    assert any(r["text"] == "j" for r in prof["journal"])
    assert all(r["text"] != "j" for r in prof["wall"])


def test_api_post_defaults_to_journal(client_self):
    c, node = client_self
    c.post("/api/post", data={"text": "d", "scope": "kreds"})   # no placement
    assert any(r["text"] == "d" for r in c.get("/api/feed").json())
