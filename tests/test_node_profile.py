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


def test_banner_pos_saved_and_carried_forward(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    n.set_profile("Wong", banner_bytes=png(1200, 400), banner_pos=20)
    p = n.store.profile(n.identity_pub)
    assert p["banner_pos"] == 20 and p["banner"] is not None
    n.set_profile("Wong Two")                # no banner_pos: keep stored
    p = n.store.profile(n.identity_pub)
    assert p["banner_pos"] == 20 and p["name"] == "Wong Two"
    assert n.profile_view(n.identity_pub)["banner_pos"] == 20


def test_banner_pos_default_and_bad_value(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    n.set_profile("Wong")
    assert n.store.profile(n.identity_pub)["banner_pos"] == 50
    assert n.profile_view(n.identity_pub)["banner_pos"] == 50
    with pytest.raises(ValueError):
        n.set_profile("Wong", banner_pos=101)


def test_feed_rows_carry_author_avatar(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    node.compose_post("before avatar", scope="kreds")
    node.set_profile("Wong", avatar_bytes=png())
    node.compose_post("after avatar", scope="kreds")
    rows = node.feed()
    avatar = node.store.profile(node.identity_pub)["avatar"]
    # enrichment is by AUTHOR's current profile, not post age: both rows
    # carry the avatar
    assert [r["author_avatar"] for r in rows] == [avatar, avatar]
    # journal surface on the profile rides posts_by and inherits it
    journal = node.profile_view(node.identity_pub)["journal"]
    assert journal[0]["author_avatar"] == avatar


def test_feed_rows_avatarless_author_is_none(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    node.compose_post("no avatar yet", scope="kreds")
    assert node.feed()[0]["author_avatar"] is None


def test_profile_avatars_latest_wins(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    assert node.store.profile_avatars() == {}          # no profile yet
    node.set_profile("Wong", avatar_bytes=png(color=(1, 2, 3)))
    first = node.store.profile_avatars()[node.identity_pub]
    node.set_profile("Wong", avatar_bytes=png(color=(4, 5, 6)))
    second = node.store.profile_avatars()[node.identity_pub]
    assert first != second                             # latest wins
    assert second == node.store.profile(node.identity_pub)["avatar"]
