from hearth.dmcrypt import (dm_aad, encrypt_body, new_content_key, post_aad,
                            wrap_key)
from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import make_dm, make_enckey, make_post
from hearth.store import Store


def person(name):
    d = DeviceKeys.create(name)
    IdentityCeremony().enroll_first_device(d)
    return d


def dm(sender, to_identity, text, extra_pubs=None, now=100.0):
    key = new_content_key()
    aad = dm_aad(sender.identity_pub, to_identity, now)
    nonce, ct = encrypt_body(key, {"text": text, "blobs": []}, aad)
    pubs = {sender.device_pub: sender.enc_pub}
    pubs.update(extra_pubs or {})
    return make_dm(sender, to_identity, nonce, ct,
                   wrap_key(key, pubs, aad), created_at=now)


def post(sender, pubs=None, scope="kreds", now=100.0):
    """A post with real (schema-valid) wraps for whichever devices are
    passed -- lets store-level tests exercise wrap-set routing without a
    full HearthNode. wraps={} (the default) is fine for tests that only
    need a post row to exist, since the store never decrypts."""
    key = new_content_key()
    aad = post_aad(sender.identity_pub, scope, now)
    nonce, ct = encrypt_body(key, {"text": "x", "blobs": []}, aad)
    return make_post(sender, scope, nonce, ct, wrap_key(key, pubs or {}, aad),
                     created_at=now)


def store_with(tmp_path, *identities):
    s = Store(tmp_path / "s.db")
    for i, ident in enumerate(identities):
        s.add_identity(ident, is_self=(i == 0))
    return s


def test_enckeys_latest_wins_and_revoked_excluded(tmp_path):
    wong = person("wong-phone")
    node = DeviceKeys.create("wong-node")
    node.install(wong.enroll_other(node.device_pub, node.name),
                 wong.to_json()["identity_priv"])
    s = store_with(tmp_path, wong.identity_pub)
    s.ingest_message(make_enckey(wong, now=1.0))
    s.ingest_message(make_enckey(node, now=1.0))
    keys = s.enckeys(wong.identity_pub)
    assert keys == {wong.device_pub: wong.enc_pub,
                    node.device_pub: node.enc_pub}
    # device rotates its enc key: latest wins
    old_pub = wong.enc_pub
    wong.enc_priv, wong.enc_pub = None, None
    from hearth.identity import _gen_x25519_pair
    wong.enc_priv, wong.enc_pub = _gen_x25519_pair()
    s.ingest_message(make_enckey(wong, now=2.0))
    assert s.enckeys(wong.identity_pub)[wong.device_pub] == wong.enc_pub != old_pub
    # revoked device drops out of the directory
    s.ingest_revocation(node.make_revocation(node.device_pub, 99))
    assert node.device_pub not in s.enckeys(wong.identity_pub)


def test_dm_rows_get_recipient_and_thread_query(tmp_path):
    wong, freja = person("wong-phone"), person("freja-phone")
    s = store_with(tmp_path, wong.identity_pub, freja.identity_pub)
    s.ingest_message(dm(wong, freja.identity_pub, "hej", now=10.0))
    s.ingest_message(dm(freja, wong.identity_pub, "hej selv", now=20.0))
    s.ingest_message(post(wong))
    thread = s.dm_thread(wong.identity_pub, freja.identity_pub)
    assert [m.payload["kind"] for m in thread] == ["dm", "dm"]
    assert [m.payload["created_at"] for m in thread] == [10.0, 20.0]
    assert s.dm_conversations(wong.identity_pub) == [freja.identity_pub]
    assert s.get_message(thread[0].msg_id).msg_id == thread[0].msg_id
    assert s.get_message("ab" * 32) is None


def test_dm_entitlement_in_messages_not_in(tmp_path):
    wong, freja, mads = (person("wong-phone"), person("freja-phone"),
                         person("mads-phone"))
    s = store_with(tmp_path, wong.identity_pub, freja.identity_pub,
                   mads.identity_pub)
    # post routing resolves a peer's devices via load_views(peer_identity),
    # so the store must have learned their device certs first -- an enckey
    # publication (something every real node does early) is the realistic
    # way that happens.
    s.ingest_message(make_enckey(freja))
    s.ingest_message(make_enckey(mads))
    d = dm(wong, freja.identity_pub, "privat")
    # "kreds" post wrapped for both friends, so the wrap-set routing test
    # below actually exercises fan-out to more than just the author.
    p = post(wong, pubs={freja.device_pub: freja.enc_pub,
                         mads.device_pub: mads.enc_pub})
    s.ingest_message(d)
    s.ingest_message(p)
    entitled = {wong.identity_pub, freja.identity_pub, mads.identity_pub}
    # freja (recipient) gets both; mads (mutual friend) gets ONLY the post
    freja_gets = {m.msg_id for m in
                  s.messages_not_in({}, entitled, freja.identity_pub)}
    mads_gets = {m.msg_id for m in
                 s.messages_not_in({}, entitled, mads.identity_pub)}
    assert d.msg_id in freja_gets and p.msg_id in freja_gets
    assert d.msg_id not in mads_gets and p.msg_id in mads_gets
    # the author's own devices also carry the DM
    wong_gets = {m.msg_id for m in
                 s.messages_not_in({}, entitled, wong.identity_pub)}
    assert d.msg_id in wong_gets


def test_dm_conversations_ignores_non_party_rows(tmp_path):
    wong, freja, mads, alice = (person("wong-phone"), person("freja-phone"),
                                person("mads-phone"), person("alice-phone"))
    s = store_with(tmp_path, wong.identity_pub, freja.identity_pub,
                   mads.identity_pub, alice.identity_pub)
    s.ingest_message(dm(wong, freja.identity_pub, "hej"))
    # DMs between others, neither involving wong, somehow present locally
    s.ingest_message(dm(freja, mads.identity_pub, "not wong's business"))
    s.ingest_message(dm(mads, alice.identity_pub, "also not wong's"))
    # wong's conversation list must contain only freja, never mads or alice
    assert s.dm_conversations(wong.identity_pub) == [freja.identity_pub]


def test_wipe_all(tmp_path):
    wong = person("wong-phone")
    s = store_with(tmp_path, wong.identity_pub)
    s.ingest_message(post(wong))
    s.put_blob(b"data")
    s.add_peer("127.0.0.1:1", wong.identity_pub)
    s.wipe_all()
    assert s.known_identities() == []
    assert s.post_messages() == [] and s.list_peers() == []
    assert s.get_meta("gossip_addr") is None            # meta wiped too


def test_dm_thread_same_second_tie_orders_by_local_arrival(tmp_path):
    """created_at is wall-clock time.time(): on this machine consecutive
    calls return the IDENTICAL float ~99.997% of the time (measured), so
    two DMs landing in the same compose burst routinely tie exactly.
    'ORDER BY created_at ASC' alone has no tie-break, so which message
    dm_thread puts last (and node.conversations()' last_from_me, and the
    web client's chat-bubble order) becomes a function of unrelated
    storage/scan order rather than of the messages themselves.

    Store.profile()'s own (created_at, seq, device_pub) idiom is NOT
    enough on its own here, verified against real HearthNode/Store
    plumbing (9/30 real runs still failed with only that tie-break, 0/60
    once rowid was added): seq is a PER-DEVICE publish counter, and two
    peers who've each signed the same number of prior messages before
    their first DM to each other -- exactly this repo's
    befriend_with_enckeys (one enckey publish each) followed by
    compose_dm -- land on the SAME seq value too. Once seq also ties,
    (seq, device_pub) alone decays to an arbitrary lexicographic coin
    flip on a random key, unrelated to which message this store actually
    learned about most recently. The tie
    must ultimately fall back to THIS store's own local arrival order
    (SQLite rowid, monotonic per insert) -- the only signal that answers
    'which message did I see/compose last', which is what last_from_me
    needs. Proven here for BOTH insertion orders so the result can't be
    an accident of whichever device_pub happens to sort higher."""
    wong, freja = person("wong-phone"), person("freja-phone")
    m_a = dm(wong, freja.identity_pub, "hej", now=100.0)          # seq=1
    m_b = dm(freja, wong.identity_pub, "hej selv", now=100.0)     # seq=1 too
    assert m_a.payload["created_at"] == m_b.payload["created_at"]
    assert m_a.seq == m_b.seq                    # the seq tie-break alone
                                                  # cannot disambiguate

    for label, insertion in (("a_then_b", (m_a, m_b)),
                             ("b_then_a", (m_b, m_a))):
        s = Store(tmp_path / f"{label}.db")
        s.add_identity(wong.identity_pub, is_self=True)
        s.add_identity(freja.identity_pub)
        for m in insertion:
            s.ingest_message(m)
        thread = s.dm_thread(wong.identity_pub, freja.identity_pub)
        # whichever arrived (was inserted) into THIS store last must be
        # reported last -- regardless of the arbitrary device_pub values
        assert thread[-1].msg_id == insertion[-1].msg_id, label
