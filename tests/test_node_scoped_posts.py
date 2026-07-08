import pytest

from hearth.node import HearthNode


def befriend_with_enckeys(a, b):
    a.store.add_identity(b.identity_pub); b.store.add_identity(a.identity_pub)
    a.ensure_enckey(); b.ensure_enckey()
    for src, dst in ((a, b), (b, a)):
        for m in src.store.messages_not_in({}, {src.identity_pub}, dst.identity_pub):
            dst.store.ingest_message(m)


def test_kreds_post_roundtrip_author_and_recipient(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    mid = wong.compose_post("hej alle", scope="kreds")
    assert [p["text"] for p in wong.feed()] == ["hej alle"]      # author reads
    for m in wong.store.messages_not_in({}, {wong.identity_pub}, freja.identity_pub):
        freja.store.ingest_message(m)
    assert [p["text"] for p in freja.feed()] == ["hej alle"]     # recipient reads
    assert freja.feed()[0]["scope"] == "kreds"


def test_inner_post_excludes_non_inner_friend(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mikkel = HearthNode.create(tmp_path / "m", "Mikkel", "mikkel-phone")
    befriend_with_enckeys(wong, freja); befriend_with_enckeys(wong, mikkel)
    wong.set_ring(freja.identity_pub, "inner")            # Freja inner; Mikkel not
    mid = wong.compose_post("kun inner", scope="inner")
    m = wong.store.get_message(mid)
    wraps = m.payload["wraps"]
    assert freja.device.device_pub in wraps               # Freja wrapped
    assert mikkel.device.device_pub not in wraps          # Mikkel excluded
    assert wong.device.device_pub in wraps                # author's own device


def test_set_ring_rejects_self(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    with pytest.raises(ValueError):
        wong.set_ring(wong.identity_pub, "inner")


def test_ring_move_rekeys_future_only(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    wong.set_ring(freja.identity_pub, "inner")
    a = wong.store.get_message(wong.compose_post("inner 1", scope="inner"))
    wong.set_ring(freja.identity_pub, "kreds")            # demote
    b = wong.store.get_message(wong.compose_post("inner 2", scope="inner"))
    assert freja.device.device_pub in a.payload["wraps"]  # old post: still hers
    assert freja.device.device_pub not in b.payload["wraps"]  # new: excluded


def test_feed_hides_undecryptable(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mikkel = HearthNode.create(tmp_path / "m", "Mikkel", "mikkel-phone")
    befriend_with_enckeys(wong, freja); befriend_with_enckeys(wong, mikkel)
    wong.set_ring(freja.identity_pub, "inner")
    mid = wong.compose_post("kun inner", scope="inner")
    m = wong.store.get_message(mid)
    mikkel.store.add_identity(wong.identity_pub)
    mikkel.store.ingest_message(m)                        # mikkel holds ciphertext
    assert mikkel.feed() == []                            # but cannot read it


def test_profile_view_shows_only_decryptable_posts(tmp_path):
    # profile_view must go through the decrypting node.posts_by, not the
    # retired plaintext store path. A recipient sees a friend's post in their
    # journal; a friend holding only ciphertext they can't read sees an empty
    # journal list (not a KeyError crash). Default placement is "journal",
    # so this post shows up under "journal", not "wall".
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mikkel = HearthNode.create(tmp_path / "m", "Mikkel", "mikkel-phone")
    befriend_with_enckeys(wong, freja)
    befriend_with_enckeys(wong, mikkel)      # mikkel gets Wong's profile too
    wong.set_ring(freja.identity_pub, "inner")
    mid = wong.compose_post("kun inner", scope="inner")
    # author's own profile shows the post
    assert [p["text"] for p in wong.profile_view(wong.identity_pub)["journal"]] \
        == ["kun inner"]
    # Freja (inner recipient) sees it on Wong's profile
    for m in wong.store.messages_not_in({}, {wong.identity_pub}, freja.identity_pub):
        freja.store.ingest_message(m)
    assert [p["text"] for p in freja.profile_view(wong.identity_pub)["journal"]] \
        == ["kun inner"]
    # Mikkel holds Wong's profile but is NOT inner; force the inner-post
    # ciphertext into his store -> profile renders no posts, does not crash
    mikkel.store.ingest_message(wong.store.get_message(mid))
    assert mikkel.profile_view(wong.identity_pub)["journal"] == []
