import pytest

from hearth.node import HearthNode


def test_create_and_reload(tmp_path):
    d = tmp_path / "wong-phone"
    n = HearthNode.create(d, "Wong", "wong-phone")
    assert n.identity_pub and (d / "paper_seed.txt").exists()
    n2 = HearthNode(d)                      # reload from disk
    assert n2.identity_pub == n.identity_pub


def test_uninitialized_dir_raises(tmp_path):
    with pytest.raises(FileNotFoundError):
        HearthNode(tmp_path / "empty")


def test_compose_feed_delete_with_photo(tmp_path):
    from tests.test_imagegate import animated_gif_bytes
    n = HearthNode.create(tmp_path / "n", "Wong", "phone")
    photo = animated_gif_bytes()            # byte-identity is the point below
    mid = n.compose_post("hello hearth", photos=[photo])
    feed = n.feed()
    assert len(feed) == 1 and feed[0]["mine"] is True
    assert feed[0]["author_name"] == "Wong"
    ref = feed[0]["blobs"][0]
    assert n.store.get_blob(ref) != photo   # stored ciphertext, not plaintext
    assert n.post_blob(mid, ref) == photo   # decrypts to the original bytes
    n.delete_post(mid)
    assert n.feed() == []
    assert n.store.get_blob(ref) is None    # blob GC'd


def test_delete_post_refuses_a_held_delete_target(tmp_path):
    # Wart 1 (spec 2026-07-09-protocol-warts): delete tags are immune to
    # deletion -- refuse the creation before it ever hits the wire, rather
    # than relying only on the ingest-side guard.
    n = HearthNode.create(tmp_path / "n", "Wong", "phone")
    mid = n.compose_post("hello hearth")
    tag_id = n.delete_post(mid)
    with pytest.raises(ValueError, match="cannot delete a delete tag"):
        n.delete_post(tag_id)


def test_seq_survives_restart(tmp_path):
    d = tmp_path / "n"
    n = HearthNode.create(d, "Wong", "phone")
    n.compose_post("one")
    seq_before = n.device.seq
    n2 = HearthNode(d)                      # simulated process restart
    n2.compose_post("two")
    assert n2.device.seq == seq_before + 1
    assert len(n2.feed()) == 2              # no seq reuse rejection


def test_expiring_post(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "phone")
    n.compose_post("brief", expires_seconds=0.0)
    n.store.sweep_expired()
    assert n.feed() == []


def test_revoke_device(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "phone")
    from hearth.identity import DeviceKeys
    tablet = DeviceKeys.create("tablet")
    cert = n.device.enroll_other(tablet.device_pub, tablet.name)
    tablet.install(cert, n.device.to_json()["identity_priv"])
    # a post authored on the tablet, wrapped for n's own device so n can
    # actually read it back via feed() -- exercises the real crypto path
    # rather than a dummy undecryptable row.
    from hearth.dmcrypt import encrypt_body, new_content_key, post_aad, wrap_key
    from hearth.messages import make_post
    created_at = 100.0
    aad = post_aad(n.identity_pub, "kreds", created_at)
    key = new_content_key()
    nonce, ct = encrypt_body(key, {"text": "from tablet, before revoke",
                                   "blobs": []}, aad)
    wraps = wrap_key(key, {n.device.device_pub: n.device.enc_pub}, aad)
    seen_post = make_post(tablet, "kreds", nonce, ct, wraps,
                          created_at=created_at)
    n.store.ingest_message(seen_post)

    r = n.revoke_device(tablet.device_pub)
    assert r.accepted
    # The revoker vouches for what they saw: nothing local is dropped...
    assert r.retro_dropped == []
    assert [p["text"] for p in n.feed()] == ["from tablet, before revoke"]
    # ...but the device is dead for anything NEW.
    loot = make_post(tablet, "kreds", body_nonce="ab" * 12,
                     body_ct="deadbeef", wraps={})
    assert n.store.ingest_message(loot).reason == "device revoked"
    devices = {d["device_pub"]: d for d in n.devices()}
    assert devices[tablet.device_pub]["revoked"] is True
    assert devices[n.device.device_pub]["this_device"] is True


def test_compose_post_gates_photos(tmp_path):
    import io as _io
    from PIL import Image as _Image
    from tests.test_imagegate import noise_jpeg_bytes

    n = HearthNode.create(tmp_path / "n", "Wong", "phone")
    big = noise_jpeg_bytes(4000, 3000)
    assert len(big) > 5 * 1024 * 1024
    mid = n.compose_post("beach", photos=[big])
    feed = n.feed()
    ref = feed[0]["blobs"][0]
    plain = n.post_blob(mid, ref)
    assert plain != big                        # gate re-encoded
    img = _Image.open(_io.BytesIO(plain))
    assert img.format == "JPEG" and max(img.size) <= 2560


def test_compose_post_gif_survives_byte_identical(tmp_path):
    from tests.test_imagegate import animated_gif_bytes
    n = HearthNode.create(tmp_path / "n", "Wong", "phone")
    gif = animated_gif_bytes()
    mid = n.compose_post("loop", photos=[gif])
    ref = n.feed()[0]["blobs"][0]
    assert n.post_blob(mid, ref) == gif


def test_compose_post_rejects_junk_photo_bytes(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "phone")
    with pytest.raises(ValueError, match="not an image"):
        n.compose_post("x", photos=[b"not-pixels"])


def test_compose_dm_gates_photos(tmp_path):
    from tests.test_imagegate import noise_jpeg_bytes
    from tests.test_node_dm import befriend_with_enckeys

    a = HearthNode.create(tmp_path / "a", "Wong", "wong-phone")
    b = HearthNode.create(tmp_path / "b", "Freja", "freja-phone")
    befriend_with_enckeys(a, b)
    big = noise_jpeg_bytes(4000, 3000)
    mid = a.compose_dm(b.identity_pub, "pic", photos=[big])
    thread = a.dm_thread(b.identity_pub)
    ref = thread[-1]["blobs"][0]
    plain = a.dm_blob(mid, ref)
    assert plain is not None and plain != big and len(plain) < len(big)
