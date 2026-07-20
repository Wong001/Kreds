"""maintain_received_dm_grants: re-wrap RECEIVED DMs' content keys to OWN
satellite devices via RECIPIENT-signed wrap_grants -- so the phone can read
old friend DMs composed before its enc key existed. maintain_wrap_grants
and maintain_own_device_grants are NOT touched. Trust: a recipient-signed
grant is only ever honored for the recipient's own devices; this file
proves the mint side never wraps to anything else."""
from hearth.node import HearthNode
from hearth.identity import DeviceKeys, DeviceView
from hearth.dmcrypt import unwrap_key, decrypt_body
from hearth.messages import KIND_WRAP_GRANT, make_enckey


def _enroll_second_own_device(node):
    """A satellite device: fresh device keypair + enc key, enrolled by the
    node's identity, its enc key published into the node's store the same
    way an ingested KIND_ENCKEY would land there. (Mirrors
    tests/test_own_device_grants.py._enroll_second_own_device.)"""
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
    test_own_device_grants._befriend_with_enckeys)."""
    a.store.add_identity(b.identity_pub)
    b.store.add_identity(a.identity_pub)
    a.ensure_enckey()
    b.ensure_enckey()
    for src, dst in ((a, b), (b, a)):
        for m in src.store.messages_not_in({}, {src.identity_pub},
                                           dst.identity_pub):
            dst.store.ingest_message(m)


def _deliver(src, dst):
    """Push everything src holds and dst is entitled to into dst's store --
    the real sync ingestion path (messages_not_in + ingest_message)."""
    for m in src.store.messages_not_in({}, {src.identity_pub},
                                       dst.identity_pub):
        dst.store.ingest_message(m)


def test_backfills_old_received_dm_to_new_own_device(tmp_path):
    # friend node DMs me BEFORE my phone enc key exists; after the phone's
    # enckey ingests, the sweep mints a RECIPIENT-signed grant; the phone
    # can unwrap_key + decrypt_body the old DM with its own enc_priv.
    node = HearthNode.create(tmp_path / "n", "Me", "desk")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    _befriend_with_enckeys(node, freja)
    # freja DMs me while only my desk enc key exists (no phone yet)
    dm_id = freja.compose_dm(node.identity_pub, "gammel besked")
    _deliver(freja, node)
    # desk holds+decrypts it (precondition); the DM does NOT wrap the phone
    rmsg = node.store.get_message(dm_id)
    key, aad = node._content_key(rmsg)
    assert key is not None, "desk cannot recover the received DM key"

    dev = _enroll_second_own_device(node)
    assert dev.device_pub not in rmsg.payload.get("wraps", {})
    node.maintain_received_dm_grants()

    grants = node.store.wrap_grants(dm_id, node.identity_pub)
    assert dev.device_pub in grants, "no grant minted for the new own device"
    # the phone uses ITS enc_priv on the GRANT's wrap:
    gwrap = {dev.device_pub: grants[dev.device_pub]}
    phone_key = unwrap_key(gwrap, dev.device_pub, dev.enc_priv, aad)
    assert phone_key == key
    body = decrypt_body(phone_key, rmsg.payload["body_nonce"],
                        rmsg.payload["body_ct"], aad)
    assert body["text"] == "gammel besked"

    # idempotent: a second sweep mints no new wrap-grant message
    before = len(node.store.messages_by_author(node.identity_pub))
    node.maintain_received_dm_grants()
    after = len(node.store.messages_by_author(node.identity_pub))
    assert before == after, "sweep re-minted a duplicate grant"


def test_never_wraps_to_non_own_devices(tmp_path):
    # after the sweep, EVERY minted grant's wraps keyset is a subset of
    # my own enrolled device_pubs -- even with friend enckeys present.
    node = HearthNode.create(tmp_path / "n", "Me", "desk")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    _befriend_with_enckeys(node, freja)
    freja.compose_dm(node.identity_pub, "hej")
    _deliver(freja, node)
    dev = _enroll_second_own_device(node)
    node.maintain_received_dm_grants()

    own_devs = set(node.store.enckeys(node.identity_pub))
    friend_devs = set(node.store.enckeys(freja.identity_pub))
    assert friend_devs, "friend enckeys must be present in the store"
    minted = [m for m in node.store.messages_by_author(node.identity_pub)
              if m.payload.get("kind") == KIND_WRAP_GRANT]
    assert minted, "the sweep minted nothing (test would prove nothing)"
    for g in minted:
        wraps = set(g.payload.get("wraps", {}))
        assert wraps <= own_devs, ("grant wraps a non-own device", wraps)
        assert not (wraps & friend_devs), ("grant reached a friend", wraps)
    # and the actual satellite is among the covered devices
    assert dev.device_pub in own_devs


def test_never_targets_own_authored_or_non_dm(tmp_path):
    # own posts, own DMs, friend WALL posts: the sweep mints nothing for
    # them (own content is maintain_own_device_grants' job; posts are
    # author-signed territory). A received DM is present as a positive
    # control so a bare no-op cannot pass this test.
    node = HearthNode.create(tmp_path / "n", "Me", "desk")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    _befriend_with_enckeys(node, freja)
    rdm = freja.compose_dm(node.identity_pub, "modtaget")   # positive control
    _deliver(freja, node)
    wmid = node.compose_post("mit indlaeg")                 # own post
    odm = node.compose_dm(freja.identity_pub, "min besked")  # own DM
    fmid = freja.compose_post("frejas vaeg", scope="kreds",  # friend wall post
                              placement="profile")
    _deliver(freja, node)
    # node holds+decrypts freja's wall post (positive precondition)
    assert node._content_key(node.store.get_message(fmid))[0] is not None

    dev = _enroll_second_own_device(node)
    node.maintain_received_dm_grants()

    # positive control: the received DM IS granted to the phone
    assert dev.device_pub in node.store.wrap_grants(rdm, node.identity_pub)
    # own-authored + friend-authored content: this sweep mints nothing
    assert node.store.wrap_grants(wmid, node.identity_pub) == {}, "own post"
    assert node.store.wrap_grants(odm, node.identity_pub) == {}, "own DM"
    assert node.store.wrap_grants(fmid, node.identity_pub) == {}, "friend wall"


def test_locked_or_revoked_mints_nothing(tmp_path):
    # biting variant per B.2: on a fully-populated node (received DM + an
    # enrolled target device, i.e. the sweep WOULD mint), flipping revoked
    # or locked must make it mint nothing and never raise. Drop either flag
    # from the guard and the phone grant gets minted -> these fail.
    for flag in ("revoked", "locked"):
        node = HearthNode.create(tmp_path / flag, "Me", "desk")
        freja = HearthNode.create(tmp_path / (flag + "f"), "Freja", "fp")
        _befriend_with_enckeys(node, freja)
        dm_id = freja.compose_dm(node.identity_pub, "hemmelig")
        _deliver(freja, node)
        _enroll_second_own_device(node)
        assert node.store.enckeys(node.identity_pub)   # a target exists
        setattr(node, flag, True)
        node.maintain_received_dm_grants()             # must not raise
        assert node.store.wrap_grants(dm_id, node.identity_pub) == {}, flag


def test_three_sweep_fixpoint(tmp_path):
    """All three sweeps + the prune, in production order, over the whole
    keyspace: a kreds WALL post (friend + own satellites), OWN history (own
    DM), and an OLD RECEIVED DM (own satellites only). After round 1 the
    grant set must be STABLE (no churn), and after EVERY round the friend
    device AND both satellites must still unwrap+decrypt whatever they are
    entitled to. Extends test_own_device_grants' dual-sweep fixpoint to the
    third sweep; (own, friend-dm-id) shares its prune key with no other
    minter, so no carry-forward is involved."""
    node = HearthNode.create(tmp_path / "n", "Me", "desk")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    # own kreds WALL post composed BEFORE befriending: freja's coverage
    # exists only as a maintain_wrap_grants grant (shared-keyspace case).
    wmid = node.compose_post("min mur", scope="kreds", placement="profile")
    _befriend_with_enckeys(node, freja)
    # RECEIVED DM from freja (own satellites only)
    rdm = freja.compose_dm(node.identity_pub, "modtaget")
    _deliver(freja, node)
    # OWN DM history to freja (own satellites via maintain_own_device_grants)
    odm = node.compose_dm(freja.identity_pub, "min besked")
    # two satellites, enrolled AFTER all content exists
    phone1 = _enroll_second_own_device(node)
    phone2 = _enroll_second_own_device(node)

    def run_round():
        # mirrors hearth/sync.py:_gossip_round order for this keyspace
        node.maintain_wrap_grants()
        node.maintain_own_device_grants()
        node.maintain_received_dm_grants()
        node.store.prune_superseded_enckeys()
        node.store.prune_superseded_wrap_grants()

    def grant_ids():
        return {m.msg_id for m in
                node.store.messages_by_author(node.identity_pub)
                if m.payload.get("kind") == KIND_WRAP_GRANT}

    def decrypts(mid, expect, readers):
        msg = node.store.get_message(mid)
        key, aad = node._content_key(msg)
        assert key is not None, mid
        grants = node.store.wrap_grants(mid, node.identity_pub)
        for dpub, epriv in readers:
            assert dpub in grants, (mid, dpub)
            k = unwrap_key({dpub: grants[dpub]}, dpub, epriv, aad)
            assert k == key, (mid, dpub)
            body = decrypt_body(k, msg.payload["body_nonce"],
                                msg.payload["body_ct"], aad)
            assert body["text"] == expect, (mid, dpub)

    sats = [(phone1.device_pub, phone1.enc_priv),
            (phone2.device_pub, phone2.enc_priv)]
    freja_reader = [(freja.device.device_pub, freja.device.enc_priv)]

    def assert_all_decrypt():
        # wall post: friend + both satellites (shared keyspace)
        decrypts(wmid, "min mur", freja_reader + sats)
        # own DM history: both satellites (freja is inline-wrapped)
        decrypts(odm, "min besked", sats)
        # received DM: both satellites (recipient-signed grants)
        decrypts(rdm, "modtaget", sats)

    run_round()
    assert_all_decrypt()
    ids = grant_ids()
    assert ids, "round 1 minted no grant at all"
    for _ in range(3):
        run_round()
        assert grant_ids() == ids, "grant set churned after fixpoint"
        assert_all_decrypt()
