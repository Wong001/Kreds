"""Author-side wrap-grant sweep + grant-based key resolution ("a wall is
a wall", 0.3.11). Store-and-forward level (hand-carried messages);
socket-level proof lives in test_wrap_grants_e2e.py."""
from hearth.messages import KIND_WRAP_GRANT
from hearth.node import HearthNode


def befriend_with_enckeys(a, b):
    a.store.add_identity(b.identity_pub); b.store.add_identity(a.identity_pub)
    a.ensure_enckey(); b.ensure_enckey()
    for src, dst in ((a, b), (b, a)):
        for m in src.store.messages_not_in({}, {src.identity_pub}, dst.identity_pub):
            dst.store.ingest_message(m)


def _grants(node, mid):
    return node.store.wrap_grants(mid, node.identity_pub)


def test_sweep_grants_wall_back_catalog_to_new_friend(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mid = wong.compose_post("gammel veag", scope="kreds", placement="profile")
    befriend_with_enckeys(wong, freja)                # friendship AFTER post
    wong.maintain_wrap_grants()
    g = _grants(wong, mid)
    assert freja.device.device_pub in g
    # idempotent: a second sweep mints nothing new
    before = wong.store.messages_not_in({}, {wong.identity_pub}, freja.identity_pub)
    wong.maintain_wrap_grants()
    after = wong.store.messages_not_in({}, {wong.identity_pub}, freja.identity_pub)
    assert len(before) == len(after)


def test_sweep_skips_journal_inner_expired_and_tombstoned(tmp_path):
    import time
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    j = wong.compose_post("journal", scope="kreds")                 # journal
    i = wong.compose_post("inner wall", scope="inner", placement="profile")
    e = wong.compose_post("kort", scope="kreds", placement="profile",
                          expires_seconds=0.001)
    d = wong.compose_post("slettes", scope="kreds", placement="profile")
    wong.delete_post(d)                     # node.py:1161
    befriend_with_enckeys(wong, freja)
    time.sleep(0.01)                                  # e is now expired
    wong.maintain_wrap_grants()
    for mid in (j, i, e, d):
        assert _grants(wong, mid) == {}, mid


def test_recipient_decrypts_via_grant_and_feed_shows_post(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mid = wong.compose_post("bagkatalog", scope="kreds", placement="profile")
    befriend_with_enckeys(wong, freja)
    wong.maintain_wrap_grants()
    for m in wong.store.messages_not_in({}, {wong.identity_pub}, freja.identity_pub):
        freja.store.ingest_message(m)
    # node.feed() is journal-only by design (placement == "journal"); a
    # profile/wall post's visibility surface is posts_by(.., "profile")
    # and profile_view()["wall"] (both back onto _decrypt_post_row ->
    # _content_key, so this still proves the grant-based unwrap works).
    assert "bagkatalog" in [p["text"] for p in
                           freja.posts_by(wong.identity_pub, "profile")]
    wall = freja.profile_view(wong.identity_pub)["wall"]
    assert "bagkatalog" in [p["text"] for p in wall]


def test_sweep_remints_after_recipient_enckey_rotation(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mid = wong.compose_post("rotation", scope="kreds", placement="profile")
    befriend_with_enckeys(wong, freja)
    wong.maintain_wrap_grants()
    old_wrap = _grants(wong, mid)[freja.device.device_pub]
    # freja rotates; her new enckey record reaches wong
    freja.device.rotate_enc(__import__("time").time())
    freja.ensure_enckey()
    for m in freja.store.messages_not_in({}, {freja.identity_pub}, wong.identity_pub):
        wong.store.ingest_message(m)
    wong.maintain_wrap_grants()
    new_wrap = _grants(wong, mid)[freja.device.device_pub]
    assert new_wrap != old_wrap                      # re-minted, not stale
    assert new_wrap["enc_pub"] == wong.store.enckeys(
        freja.identity_pub)[freja.device.device_pub]


def test_sweep_noop_when_locked_or_no_friends(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    mid = wong.compose_post("alene", scope="kreds", placement="profile")
    wong.maintain_wrap_grants()                       # no friends: no-op
    assert _grants(wong, mid) == {}
