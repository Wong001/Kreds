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


def test_locked_or_revoked_mints_nothing(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Me", "desk")
    mid = node.compose_post("p")
    # enroll a device BEFORE revoking so there would be a target to grant to
    _enroll_second_own_device(node)
    node.enter_revoked_state()          # destroys keys + wipes the store
    # revoked -> no-op, no exception (the sweep signs, so it must skip)
    node.maintain_own_device_grants()
    assert node.store.wrap_grants(mid, node.identity_pub) == {}
