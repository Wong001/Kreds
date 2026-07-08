import io

import pytest
from PIL import Image

from hearth.node import HearthNode


def png(w=300, h=300, color=(40, 90, 200)):
    buf = io.BytesIO()
    Image.new("RGB", (w, h), color).save(buf, format="PNG")
    return buf.getvalue()


def test_set_rich_profile_transcodes_and_stores(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    n.set_profile("Wong", bio="Designer", accent="#c0563b",
                  avatar_bytes=png(2000, 2000), avatar_shape="squircle",
                  avatar_size="l", avatar_align="center")
    p = n.store.profile(n.identity_pub)
    assert p["bio"] == "Designer" and p["accent"] == "#c0563b"
    assert p["avatar_shape"] == "squircle"
    # avatar stored as a re-encoded blob, downsized to <=512
    blob = n.store.get_blob(p["avatar"])
    assert blob is not None
    assert max(Image.open(io.BytesIO(blob)).size) <= 512


def test_saving_name_keeps_existing_avatar(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    n.set_profile("Wong", avatar_bytes=png())
    first = n.store.profile(n.identity_pub)["avatar"]
    assert first is not None
    n.set_profile("Wong Two", bio="new bio")     # no new avatar bytes
    p = n.store.profile(n.identity_pub)
    assert p["name"] == "Wong Two" and p["avatar"] == first  # carried forward


def test_bad_image_rejected(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    with pytest.raises(ValueError):
        n.set_profile("Wong", avatar_bytes=b"not an image")


def test_profile_view_self_and_friend(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    wong.store.add_identity(freja.identity_pub)
    # friend publishes a profile; carry it to wong
    freja.set_profile("Freja", bio="Keramiker", accent="#3e7c55")
    from hearth.messages import make_profile
    for m in freja.store.messages_not_in({}, {freja.identity_pub},
                                         wong.identity_pub):
        wong.store.ingest_message(m)
    fv = wong.profile_view(freja.identity_pub)
    assert fv["name"] == "Freja" and fv["mine"] is False
    assert fv["bio"] == "Keramiker"
    mv = wong.profile_view(wong.identity_pub)
    assert mv["mine"] is True and "wall" in mv and "journal" in mv


def test_profile_view_includes_own_posts(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    n.compose_post("hello world")                 # default placement: journal
    v = n.profile_view(n.identity_pub)
    assert [p["text"] for p in v["journal"]] == ["hello world"]
    assert v["wall"] == []
