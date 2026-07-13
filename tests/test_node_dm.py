import json

import pytest

from hearth.node import HearthNode


def befriend_with_enckeys(a: HearthNode, b: HearthNode):
    a.store.add_identity(b.identity_pub)
    b.store.add_identity(a.identity_pub)
    a.ensure_enckey()
    b.ensure_enckey()
    # hand-carry the enckey messages (sync does this in real life)
    for src, dst in ((a, b), (b, a)):
        for m in src.store.messages_not_in({}, {src.identity_pub},
                                           dst.identity_pub):
            dst.store.ingest_message(m)


def test_ensure_enckey_publishes_once(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "phone")
    n.ensure_enckey()
    n.ensure_enckey()                                  # idempotent
    keys = n.store.enckeys(n.identity_pub)
    assert keys[n.device.device_pub] == n.device.enc_pub


def test_dm_roundtrip_with_photo(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    photo = b"\x89PNG-hemmeligt-billede"
    mid = wong.compose_dm(freja.identity_pub, "kun til dig", [photo])
    # the stored blob is ciphertext, not the photo
    ref = wong.store.get_message(mid)
    # carry the DM to freja
    for m in wong.store.messages_not_in({}, {wong.identity_pub},
                                        freja.identity_pub):
        freja.store.ingest_message(m)
    thread = freja.dm_thread(wong.identity_pub)
    assert len(thread) == 1 and thread[0]["text"] == "kun til dig"
    assert thread[0]["from_me"] is False
    blob_hash_ = thread[0]["blobs"][0]
    assert freja.store.get_blob(blob_hash_) != photo   # ciphertext at rest
    # hand-carry the ciphertext blob too (sync's BLOBS phase does this in
    # real life; messages_not_in/ingest_message above only carries the
    # signed message, not referenced blob bytes)
    freja.store.put_blob(wong.store.get_blob(blob_hash_))
    assert freja.dm_blob(thread[0]["msg_id"], blob_hash_) == photo
    # sender's own view decrypts too
    assert wong.dm_thread(freja.identity_pub)[0]["from_me"] is True
    assert wong.conversations()[0]["identity_pub"] == freja.identity_pub


def test_outsider_device_cannot_decrypt(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mads = HearthNode.create(tmp_path / "m", "Mads", "mads-phone")
    befriend_with_enckeys(wong, freja)
    mads.store.add_identity(wong.identity_pub)
    mads.store.add_identity(freja.identity_pub)
    mid = wong.compose_dm(freja.identity_pub, "privat")
    # even if the ciphertext somehow reached mads, he cannot read it
    msg = wong.store.get_message(mid)
    mads.store.ingest_message(msg)
    thread = mads.dm_thread(wong.identity_pub)
    assert thread == [] or all(t["undecryptable"] for t in thread)


def test_compose_dm_guards(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    with pytest.raises(ValueError):
        wong.compose_dm(freja.identity_pub, "x")       # not a friend
    wong.store.add_identity(freja.identity_pub)
    with pytest.raises(ValueError):
        wong.compose_dm(freja.identity_pub, "x")       # no enckeys yet
    with pytest.raises(ValueError):
        wong.compose_dm(wong.identity_pub, "x")        # DM to self


def test_enter_revoked_state_and_reload(tmp_path):
    d = tmp_path / "n"
    n = HearthNode.create(d, "Wong", "phone")
    n.compose_post("about to lose this device")
    n.enter_revoked_state()
    assert n.revoked and n.feed() == []
    raw = json.loads((d / "keys.json").read_text())
    assert raw["revoked"] is True
    assert raw["identity_priv"] is None and raw["enc_priv"] is None
    n2 = HearthNode(d)                                 # reload
    assert n2.revoked is True


def test_learning_own_revocation_at_load_triggers_logout(tmp_path):
    d = tmp_path / "n"
    n = HearthNode.create(d, "Wong", "phone")
    from hearth.identity import DeviceKeys, DeviceView
    other = DeviceKeys.create("wong-node")
    other.install(n.device.enroll_other(other.device_pub, other.name),
                  n.device.to_json()["identity_priv"])
    rev = other.make_revocation(n.device.device_pub, 99)
    n.store.ingest_revocation(rev)
    n.close()
    n2 = HearthNode(d)                                 # discovers it at load
    assert n2.revoked is True


def test_cannot_revoke_own_device(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "phone")
    with pytest.raises(ValueError):
        n.revoke_device(n.device.device_pub)


def test_dm_photo_survives_blob_gc(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    photo = b"\x89PNG-must-survive-gc"
    mid = wong.compose_dm(freja.identity_pub, "photo dm", [photo])
    thread = wong.dm_thread(freja.identity_pub)
    ref = thread[0]["blobs"][0]
    wong.store.gc_blobs()                      # an unrelated GC pass
    assert wong.store.get_blob(ref) is not None   # DM photo NOT collected
    assert wong.dm_blob(mid, ref) == photo        # still decryptable


def test_compose_caches_own_content_key(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    mid = wong.compose_dm(freja.identity_pub, "cached at send")
    assert wong.store.cached_message_key(mid) is not None


def test_recipient_caches_on_first_read_then_survives_key_loss(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    mid = wong.compose_dm(freja.identity_pub, "husk mig")
    for m in wong.store.messages_not_in({}, {wong.identity_pub},
                                        freja.identity_pub):
        freja.store.ingest_message(m)
    assert freja.store.cached_message_key(mid) is None
    assert freja.dm_thread(wong.identity_pub)[0]["text"] == "husk mig"
    assert freja.store.cached_message_key(mid) is not None      # cached on read
    # total envelope-key loss: history still displays via the cache
    freja.device.enc_priv = None
    freja.device.retired_enc = []
    assert freja.dm_thread(wong.identity_pub)[0]["text"] == "husk mig"


def test_decrypts_via_retired_key_after_rotation(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    mid = wong.compose_dm(freja.identity_pub, "foer rotation")
    for m in wong.store.messages_not_in({}, {wong.identity_pub},
                                        freja.identity_pub):
        freja.store.ingest_message(m)
    freja.device.rotate_enc(now=1000.0)     # BEFORE any read: no cache yet
    thread = freja.dm_thread(wong.identity_pub)
    assert thread[0]["text"] == "foer rotation"
    assert thread[0]["undecryptable"] is False


def test_cache_dm_keys_sweep_caches_without_a_read(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    mid = wong.compose_dm(freja.identity_pub, "sweep me")
    for m in wong.store.messages_not_in({}, {wong.identity_pub},
                                        freja.identity_pub):
        freja.store.ingest_message(m)
    assert freja.store.cached_message_key(mid) is None
    freja.cache_message_keys()
    assert freja.store.cached_message_key(mid) is not None


def _forge_alien_wrapped_dm(wong, freja, alien_device, text="alien wrap"):
    """A DM addressed to freja whose only wrap key belongs to a device she
    has never owned -- her enc_privs() will never open it, no matter how
    many times a sweep retries: permanently undecryptable."""
    from hearth.dmcrypt import dm_aad, encrypt_body, new_content_key, wrap_key
    from hearth.messages import make_dm
    created_at = 100.0
    aad = dm_aad(wong.identity_pub, freja.identity_pub, created_at)
    key = new_content_key()
    nonce, ct = encrypt_body(key, {"text": text, "blobs": []}, aad)
    wraps = wrap_key(key, {alien_device.device_pub: alien_device.enc_pub},
                     aad)
    return make_dm(wong.device, freja.identity_pub, nonce, ct, wraps,
                   created_at)


def test_sweep_negative_caches_undecryptable_and_skips_next_round(
        tmp_path, monkeypatch):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mads = HearthNode.create(tmp_path / "m", "Mads", "mads-phone")
    befriend_with_enckeys(wong, freja)
    alien = _forge_alien_wrapped_dm(wong, freja, mads.device)
    assert freja.store.ingest_message(alien).accepted
    mid = alien.msg_id
    assert mid in freja.store.uncached_message_ids(freja.identity_pub)

    freja.cache_message_keys()                       # first sweep: marks it
    assert freja.store.undecryptable_ids() == {mid}
    assert mid not in freja.store.uncached_message_ids(freja.identity_pub)

    calls = []
    real_content_key = freja._content_key
    def spy(msg):
        calls.append(msg.msg_id)
        return real_content_key(msg)
    monkeypatch.setattr(freja, "_content_key", spy)
    freja.cache_message_keys()                        # second sweep
    assert calls == []                                # zero attempts


def test_locked_node_records_nothing(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    wong.compose_dm(freja.identity_pub, "arrives before lock")
    for m in wong.store.messages_not_in({}, {wong.identity_pub},
                                        freja.identity_pub):
        freja.store.ingest_message(m)
    freja.enable_applock("1234", "pin")
    freja2 = HearthNode(freja.data_dir)               # reboot: boots locked
    assert freja2.locked is True
    freja2.cache_message_keys()          # locked: must record NOTHING
    assert freja2.store.undecryptable_ids() == set()


def test_unlock_clears_negative_cache(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "phone")
    n.enable_applock("1234", "pin")
    n.store.mark_undecryptable("aa" * 32)
    assert n.store.undecryptable_ids() == {"aa" * 32}
    n2 = HearthNode(n.data_dir)                       # reboot: locked
    n2.unlock("1234")
    assert n2.store.undecryptable_ids() == set()


def test_dm_thread_view_still_attempts_decryption(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mads = HearthNode.create(tmp_path / "m", "Mads", "mads-phone")
    befriend_with_enckeys(wong, freja)

    # A genuinely undecryptable DM, marked by the sweep: dm_thread still
    # attempts it fresh and reports undecryptable=True from the real
    # crypto result -- it does not shortcut on the negative cache.
    alien = _forge_alien_wrapped_dm(wong, freja, mads.device)
    assert freja.store.ingest_message(alien).accepted
    freja.store.mark_undecryptable(alien.msg_id)
    thread = freja.dm_thread(wong.identity_pub)
    marked = next(t for t in thread if t["msg_id"] == alien.msg_id)
    assert marked["undecryptable"] is True

    # A message the negative cache holds a STALE mark for (simulating a
    # sweep that ran before a retired key was available): dm_thread
    # ignores the table and decrypts it anyway via the retired key.
    mid = wong.compose_dm(freja.identity_pub, "husk stadig")
    for m in wong.store.messages_not_in({}, {wong.identity_pub},
                                        freja.identity_pub):
        freja.store.ingest_message(m)
    freja.device.rotate_enc(now=1000.0)     # BEFORE any read: no cache yet
    freja.store.mark_undecryptable(mid)     # stale/erroneous mark
    thread = freja.dm_thread(wong.identity_pub)
    fixed = next(t for t in thread if t["msg_id"] == mid)
    assert fixed["text"] == "husk stadig"
    assert fixed["undecryptable"] is False


def test_revoked_wipe_clears_rotation_material(tmp_path):
    d = tmp_path / "n"
    n = HearthNode.create(d, "Wong", "phone")
    n.device.rotate_enc(now=1.0)
    n.enter_revoked_state()
    raw = json.loads((d / "keys.json").read_text())
    assert raw["retired_enc"] == []
    assert raw["storage_key"] is None


def test_maintain_enckey_rotates_after_period_and_persists(tmp_path):
    from hearth.identity import ENC_ROTATION_PERIOD
    d = tmp_path / "n"
    n = HearthNode.create(d, "Wong", "phone")
    n.maintain_enckey(now=1000.0)                    # first publish
    pub1 = n.device.enc_pub
    n.maintain_enckey(now=1000.0 + ENC_ROTATION_PERIOD - 1)
    assert n.device.enc_pub == pub1                  # not yet
    n.maintain_enckey(now=1000.0 + ENC_ROTATION_PERIOD)
    assert n.device.enc_pub != pub1                  # rotated
    assert n.store.enckeys(n.identity_pub)[n.device.device_pub] \
        == n.device.enc_pub                          # new key published
    assert n.device.retired_enc[0]["enc_pub"] == pub1
    n.close()
    n2 = HearthNode(d)                               # rotation persisted
    assert n2.device.retired_enc[0]["enc_pub"] == pub1


def test_legacy_storage_key_persisted_on_load(tmp_path):
    d = tmp_path / "n"
    n = HearthNode.create(d, "Wong", "phone")
    n.close()
    raw = json.loads((d / "keys.json").read_text())
    del raw["storage_key"]
    del raw["retired_enc"]                  # simulate a legacy v0.1 file
    (d / "keys.json").write_text(json.dumps(raw))
    n2 = HearthNode(d)                      # should auto-generate + persist
    saved = json.loads((d / "keys.json").read_text())
    assert "storage_key" in saved
    assert isinstance(saved["storage_key"], str) \
        and len(saved["storage_key"]) == 64
    n2.close()
    n3 = HearthNode(d)                      # reload: same key, not a new one
    assert n3.device.storage_key == saved["storage_key"]


def test_heals_unopenable_cache_row_via_envelope_fallback(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    mid = wong.compose_dm(freja.identity_pub, "heal me")
    for m in wong.store.messages_not_in({}, {wong.identity_pub},
                                        freja.identity_pub):
        freja.store.ingest_message(m)
    freja.store.cache_message_key(mid, "00ff")   # garbage/corrupt sealed row
    thread = freja.dm_thread(wong.identity_pub)
    assert thread[0]["text"] == "heal me"
    healed = freja.store.cached_message_key(mid)
    assert healed is not None and healed != "00ff"
    # total envelope-key loss: history still displays via the healed cache
    freja.device.enc_priv = None
    freja.device.retired_enc = []
    assert freja.dm_thread(wong.identity_pub)[0]["text"] == "heal me"


def test_maintain_enckey_prunes_past_grace_and_persists(tmp_path):
    from hearth.identity import ENC_GRACE
    d = tmp_path / "n"
    n = HearthNode.create(d, "Wong", "phone")
    n.maintain_enckey(now=0.0)
    n.device.rotate_enc(now=0.0)
    n._save_keys()
    n.maintain_enckey(now=ENC_GRACE + 1.0)           # prunes (and rotates)
    assert all(r["retired_at"] > 0.0 for r in n.device.retired_enc)
    n.close()
    raw = json.loads((d / "keys.json").read_text())
    assert all(r["retired_at"] > 0.0 for r in raw["retired_enc"])


def test_conversations_last_from_me(tmp_path):
    # Direction of the newest message, per side: the unread badge (web
    # client) needs "did the other person write last", which last_at
    # alone cannot answer.
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    wong.compose_dm(freja.identity_pub, "hej")
    assert wong.conversations()[0]["last_from_me"] is True
    # carry the DM to freja (same hand-carry as the roundtrip test above)
    for m in wong.store.messages_not_in({}, {wong.identity_pub},
                                        freja.identity_pub):
        freja.store.ingest_message(m)
    assert freja.conversations()[0]["last_from_me"] is False
    freja.compose_dm(wong.identity_pub, "hej selv")
    assert freja.conversations()[0]["last_from_me"] is True
