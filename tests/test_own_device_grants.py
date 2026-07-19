"""maintain_own_device_grants: re-wrap OWN-authored content to a new OWN
device's enc key, via author-signed wrap_grants -- so a satellite device
(device-key only, no identity key) can decrypt the author's existing
history. maintain_wrap_grants (friends) is NOT involved."""
from hearth.node import HearthNode
from hearth.identity import DeviceKeys, DeviceView
from hearth.dmcrypt import unwrap_key, decrypt_body
from hearth.messages import KIND_WRAP_GRANT, make_enckey


def _enroll_second_own_device(node):
    """A satellite device: fresh device keypair + enc key, enrolled by the
    node's identity, its enc key published into the node's store the same
    way an ingested KIND_ENCKEY would land there.

    (The enckey record is what the sweep reads; the device holds its own
    enc_priv for decryption. Whether the real phone additionally holds the
    identity key is irrelevant to this record -- the provisioning path is a
    separate task -- so here the device is enrolled fully just to MINT the
    signed enckey message the store expects.)"""
    dev = DeviceKeys.create("phone")
    cert = node.device.enroll_other(dev.device_pub, "phone")
    dev.install(cert, node.device.to_json()["identity_priv"])
    node.store.save_views(node.identity_pub,
                          {dev.device_pub: DeviceView(cert=cert)})
    res = node.store.ingest_message(make_enckey(dev))
    assert res.accepted, res.reason
    assert dev.device_pub in node.store.enckeys(node.identity_pub)
    return dev


def _befriend_with_enckeys(a, b):
    """Mutual friendship + exchanged enckeys (mirrors
    test_wrap_grants_node.befriend_with_enckeys)."""
    a.store.add_identity(b.identity_pub)
    b.store.add_identity(a.identity_pub)
    a.ensure_enckey()
    b.ensure_enckey()
    for src, dst in ((a, b), (b, a)):
        for m in src.store.messages_not_in({}, {src.identity_pub},
                                           dst.identity_pub):
            dst.store.ingest_message(m)


def test_backfills_own_posts_to_new_device(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Me", "desk")
    mid = node.compose_post("my existing post")
    dev = _enroll_second_own_device(node)
    node.maintain_own_device_grants()
    grants = node.store.wrap_grants(mid, node.identity_pub)
    assert dev.device_pub in grants, "no grant minted for the new own device"
    # the new device can now unwrap + decrypt the post
    msg = node.store.get_message(mid)
    key, aad = node._content_key(msg)   # the node's own view (has the key)
    assert key is not None
    # the phone uses ITS enc_priv on the GRANT's wrap:
    gwrap = {dev.device_pub: grants[dev.device_pub]}
    phone_key = unwrap_key(gwrap, dev.device_pub, dev.enc_priv, aad)
    assert phone_key == key
    body = decrypt_body(phone_key, msg.payload["body_nonce"],
                        msg.payload["body_ct"], aad)
    assert body["text"] == "my existing post"

    # idempotent: a second sweep mints no new wrap-grant message
    before = len(node.store.messages_by_author(node.identity_pub))
    node.maintain_own_device_grants()
    after = len(node.store.messages_by_author(node.identity_pub))
    assert before == after, "sweep re-minted a duplicate grant"


def test_backfills_own_dm_to_new_device(tmp_path):
    # own DMs are re-wrapped too (own devices see all own content)
    node = HearthNode.create(tmp_path / "n", "Me", "desk")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    _befriend_with_enckeys(node, freja)
    dm_id = node.compose_dm(freja.identity_pub, "hemmelig besked")
    dev = _enroll_second_own_device(node)
    node.maintain_own_device_grants()
    grants = node.store.wrap_grants(dm_id, node.identity_pub)
    assert dev.device_pub in grants, "own DM not granted to the new device"
    msg = node.store.get_message(dm_id)
    key, aad = node._content_key(msg)
    assert key is not None
    gwrap = {dev.device_pub: grants[dev.device_pub]}
    phone_key = unwrap_key(gwrap, dev.device_pub, dev.enc_priv, aad)
    assert phone_key == key
    body = decrypt_body(phone_key, msg.payload["body_nonce"],
                        msg.payload["body_ct"], aad)
    assert body["text"] == "hemmelig besked"


def test_does_not_grant_friends_content(tmp_path):
    # a friend's post the node holds must NOT be granted to the own device
    # (own-authored only), even though the node CAN decrypt it (it is in
    # the friend's kreds scope). The security core: the sweep signs as this
    # identity, so an own-device grant naming a friend's post would forge
    # the friend's authorship of a re-wrap. It must never happen.
    node = HearthNode.create(tmp_path / "n", "Me", "desk")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    _befriend_with_enckeys(node, freja)
    # freja composes a kreds wall post AFTER friendship -> node is in scope
    fmid = freja.compose_post("frejas veag", scope="kreds",
                              placement="profile")
    for m in freja.store.messages_not_in({}, {freja.identity_pub},
                                         node.identity_pub):
        node.store.ingest_message(m)
    # node holds and can decrypt freja's post (positive precondition)
    assert node._content_key(node.store.get_message(fmid))[0] is not None
    # node's OWN post (positive control for "the sweep did run")
    wmid = node.compose_post("mit indlaeg")
    dev = _enroll_second_own_device(node)
    node.maintain_own_device_grants()
    # own post IS granted to the phone
    assert dev.device_pub in node.store.wrap_grants(wmid, node.identity_pub)
    # friend post is NOT granted by this identity at all
    assert node.store.wrap_grants(fmid, node.identity_pub) == {}


def test_revoked_mints_nothing_and_does_not_raise(tmp_path):
    # integration leg: the real revocation path (destroys keys + wipes the
    # store) must leave the sweep a safe no-op, never an exception.
    node = HearthNode.create(tmp_path / "n", "Me", "desk")
    mid = node.compose_post("p")
    _enroll_second_own_device(node)
    node.enter_revoked_state()
    node.maintain_own_device_grants()   # revoked -> returns early, no raise
    assert node.store.wrap_grants(mid, node.identity_pub) == {}


def test_guard_skips_when_revoked_or_locked(tmp_path):
    # guard legs that BITE: on a fully-populated node (own post + an enrolled
    # target device, i.e. the sweep WOULD mint), flipping revoked or locked
    # must make it mint nothing. Drop either flag from the guard and the
    # phone grant gets minted -> these assertions fail. (enter_revoked_state
    # wipes the store, so it can't test this -- hence the direct flag.)
    for flag in ("revoked", "locked"):
        node = HearthNode.create(tmp_path / flag, "Me", "desk")
        mid = node.compose_post("p")
        _enroll_second_own_device(node)
        assert node.store.enckeys(node.identity_pub)  # a target exists
        setattr(node, flag, True)
        node.maintain_own_device_grants()
        assert node.store.wrap_grants(mid, node.identity_pub) == {}, flag


def test_fixpoint_dual_sweep_prune_no_churn_and_both_decrypt(tmp_path):
    """The invariant the Critical was about: maintain_wrap_grants (friends)
    and maintain_own_device_grants (own satellites) share the (author,
    target) grant keyspace on a kreds wall post. Run BOTH sweeps in the
    production order, then both prunes, repeatedly. After round 1 the grant
    set must be STABLE (no churn), and after EVERY round the friend device
    AND both satellites must still unwrap+decrypt the post. Without the
    own-sweep's full-coverage carry-forward the two sweeps strip each
    other's coverage and this churns / drops decryptability every round."""
    node = HearthNode.create(tmp_path / "n", "Me", "desk")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    # own kreds WALL post composed BEFORE befriending: freja is NOT inline-
    # wrapped, so her coverage exists only as a maintain_wrap_grants grant --
    # exactly the shared-keyspace case.
    wmid = node.compose_post("min mur", scope="kreds", placement="profile")
    _befriend_with_enckeys(node, freja)
    phone1 = _enroll_second_own_device(node)
    phone2 = _enroll_second_own_device(node)

    def run_round():
        # mirrors hearth/sync.py:_gossip_round order for this keyspace
        node.maintain_wrap_grants()
        node.maintain_own_device_grants()
        node.store.prune_superseded_enckeys()
        node.store.prune_superseded_wrap_grants()

    def grant_ids():
        return {m.msg_id for m in
                node.store.messages_by_author(node.identity_pub)
                if m.payload.get("kind") == KIND_WRAP_GRANT}

    def assert_all_decrypt():
        msg = node.store.get_message(wmid)
        key, aad = node._content_key(msg)
        assert key is not None
        grants = node.store.wrap_grants(wmid, node.identity_pub)
        for dpub, epriv in ((freja.device.device_pub, freja.device.enc_priv),
                            (phone1.device_pub, phone1.enc_priv),
                            (phone2.device_pub, phone2.enc_priv)):
            assert dpub in grants, dpub
            k = unwrap_key({dpub: grants[dpub]}, dpub, epriv, aad)
            assert k == key, dpub
            body = decrypt_body(k, msg.payload["body_nonce"],
                                msg.payload["body_ct"], aad)
            assert body["text"] == "min mur", dpub

    run_round()
    assert_all_decrypt()
    ids = grant_ids()
    assert ids, "round 1 minted no grant at all"
    for _ in range(3):
        run_round()
        assert grant_ids() == ids, "grant set churned after fixpoint"
        assert_all_decrypt()
