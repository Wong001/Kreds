import io

from fastapi.testclient import TestClient
from PIL import Image

from hearth.api import build_app
from hearth.node import HearthNode


def png(w=300, h=300):
    buf = io.BytesIO()
    Image.new("RGB", (w, h), (40, 90, 200)).save(buf, format="PNG")
    return buf.getvalue()


def client(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    return TestClient(build_app(node)), node


def test_get_own_profile(tmp_path):
    c, node = client(tmp_path)
    r = c.get(f"/api/profile/{node.identity_pub}")
    assert r.status_code == 200
    body = r.json()
    assert body["name"] == "Wong" and body["mine"] is True
    assert body["accent"] == "#2743d6" and "wall" in body and "journal" in body


def test_get_unknown_profile_404(tmp_path):
    c, _ = client(tmp_path)
    assert c.get("/api/profile/" + "bb" * 32).status_code == 404


def test_post_rich_profile_with_avatar(tmp_path):
    c, node = client(tmp_path)
    r = c.post("/api/profile",
               data={"name": "Wong", "bio": "Designer", "accent": "#c0563b",
                     "avatar_shape": "squircle", "avatar_size": "l",
                     "avatar_align": "center"},
               files=[("avatar", ("a.png", png(2000, 2000), "image/png"))])
    assert r.status_code == 200
    prof = c.get(f"/api/profile/{node.identity_pub}").json()
    assert prof["bio"] == "Designer" and prof["accent"] == "#c0563b"
    assert prof["avatar_shape"] == "squircle"
    blob = c.get("/api/blob/" + prof["avatar"])
    assert blob.status_code == 200
    assert max(Image.open(io.BytesIO(blob.content)).size) <= 512


def test_post_profile_bad_accent_400(tmp_path):
    c, _ = client(tmp_path)
    r = c.post("/api/profile", data={"name": "Wong", "accent": "not-a-color"})
    assert r.status_code == 400


def test_post_profile_bad_enum_400(tmp_path):
    c, _ = client(tmp_path)
    r = c.post("/api/profile",
               data={"name": "Wong", "avatar_shape": "hexagon"})
    assert r.status_code == 400


def test_post_profile_bad_image_400(tmp_path):
    c, _ = client(tmp_path)
    r = c.post("/api/profile", data={"name": "Wong"},
               files=[("avatar", ("a.png", b"not an image", "image/png"))])
    assert r.status_code == 400


def test_state_includes_accent(tmp_path):
    from fastapi.testclient import TestClient
    from hearth.api import build_app
    from hearth.node import HearthNode
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    c = TestClient(build_app(n))
    assert c.get("/api/state").json()["accent"] == "#2743d6"
