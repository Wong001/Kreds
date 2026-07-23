"""maintain_received_post_grants: re-wrap FRIEND-AUTHORED posts' content
keys to OWN satellite devices via RECIPIENT-signed wrap_grants -- so a
phone that never saw the friend online can still read a post the desk
already decrypted. Mirrors B.2c's maintain_received_dm_grants (node.py:
2190+) for KIND_POST instead of KIND_DM. maintain_wrap_grants (friend
audience, author-signed) and maintain_own_device_grants (own-authored) are
NOT touched -- a received post has author != self, so neither of those
sweeps ever iterates it.

Consumer trust (letting a satellite ACTUALLY read via this grant through
node._content_key) is Task 2, not this file: _content_key's KIND_POST
branch today only honors grants signed by the post's OWN AUTHOR
(store.wrap_grants(msg.msg_id, msg.cert.identity_pub) -- see node.py:2871).
So these tests assert at the GRANT level (store.wrap_grants(msg_id,
own_identity) + a direct unwrap_key call using the satellite's enc_priv),
exactly like test_received_dm_grants.py's own tests do for the DM sweep --
not through a live second node's real feed/read path."""
from hearth.node import HearthNode
from hearth.identity import DeviceKeys, DeviceView
from hearth.dmcrypt import unwrap_key
from hearth.messages import KIND_WRAP_GRANT, make_enckey


def _enroll_second_own_device(node):
    """A satellite device: fresh device keypair + enc key, enrolled by the
    node's identity, its enc key published into the node's store the same
    way an ingested KIND_ENCKEY would land there. (Mirrors
    tests/test_received_dm_grants.py._enroll_second_own_device.)"""
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
    test_received_dm_grants._befriend_with_enckeys)."""
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


def test_new_own_device_reads_friend_post_after_sweep(tmp_path):
    # A (friend/author) and B (recipient) sync a post while both live. A
    # goes OFFLINE (simply never called again). B enrolls a fresh own
    # device A never saw. B.maintain_received_post_grants() must mint a
    # RECIPIENT-signed grant the new device can unwrap the post's content
    # key with -- driving the real key-recovery path (node._content_key
    # on the ALREADY-entitled desk device), not a hand-rolled unwrap.
    node = HearthNode.create(tmp_path / "n", "Me", "desk")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    _befriend_with_enckeys(node, freja)
    post_id = freja.compose_post("gammelt indlaeg", scope="kreds")
    _deliver(freja, node)
    # desk holds+decrypts it (precondition); the post does NOT wrap the
    # not-yet-enrolled phone
    rmsg = node.store.get_message(post_id)
    key, aad = node._content_key(rmsg)
    assert key is not None, "desk cannot recover the received post key"

    dev = _enroll_second_own_device(node)
    assert dev.device_pub not in rmsg.payload.get("wraps", {})
    # freja (the author) is now permanently offline: only node.* is called
    # from here on.
    node.maintain_received_post_grants()

    grants = node.store.wrap_grants(post_id, node.identity_pub)
    assert dev.device_pub in grants, "no grant minted for the new own device"
    # the phone uses ITS enc_priv on the GRANT's wrap:
    gwrap = {dev.device_pub: grants[dev.device_pub]}
    phone_key = unwrap_key(gwrap, dev.device_pub, dev.enc_priv, aad)
    assert phone_key == key

    # idempotent: a second sweep mints no new wrap-grant message
    before = len(node.store.messages_by_author(node.identity_pub))
    node.maintain_received_post_grants()
    after = len(node.store.messages_by_author(node.identity_pub))
    assert before == after, "sweep re-minted a duplicate grant"


def test_sweep_skips_undecryptable_and_nonfriend_posts(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Me", "desk")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    casper = HearthNode.create(tmp_path / "c", "Casper", "casper-phone")

    # -- part 1: a post node HOLDS but cannot decrypt (no wrap for any of
    # its own devices). This cannot arise via real sync (messages_not_in
    # gates KIND_POST routing on wrap/grant entitlement -- an unentitled
    # peer is never sent the row at all), so it is forced in directly via
    # ingest_message the way test_wrap_grants_e2e.py simulates a stale/
    # unreachable grant: a defense-in-depth case, proving the sweep never
    # crashes or mints garbage for a post it genuinely cannot open.
    freja.ensure_enckey()
    undecryptable_id = freja.compose_post("hemmelig", scope="kreds")
    node.store.add_identity(freja.identity_pub)
    freja.store.add_identity(node.identity_pub)
    node.ensure_enckey()
    forced = freja.store.get_message(undecryptable_id)
    res = node.store.ingest_message(forced)
    assert res.accepted, res.reason
    assert node._content_key(forced)[0] is None, \
        "precondition: node must NOT be able to decrypt this post"

    # -- part 2: a post from an identity NOT in known_identities (posted
    # while still friends, then unfriended -- the post row survives, the
    # friendship doesn't).
    _befriend_with_enckeys(node, casper)
    nonfriend_id = casper.compose_post("frajs indlaeg", scope="kreds")
    _deliver(casper, node)
    assert node._content_key(node.store.get_message(nonfriend_id))[0] \
        is not None, "precondition: node can decrypt while still friends"
    node.store.remove_identity(casper.identity_pub)
    assert casper.identity_pub not in node.store.known_identities()

    _enroll_second_own_device(node)
    node.maintain_received_post_grants()

    assert node.store.wrap_grants(undecryptable_id, node.identity_pub) == {}, \
        "undecryptable post must not be granted"
    assert node.store.wrap_grants(nonfriend_id, node.identity_pub) == {}, \
        "non-friend-authored post must not be granted"


def test_full_coverage_and_prune_safety(tmp_path):
    # two sweeps across an enc rotation (+ a second device enrolled in
    # between, so round 2 must be FULL coverage of every current own
    # device, not just a heal of the one stale entry): the latest grant
    # alone covers every CURRENT own device; store.prune_superseded_
    # wrap_grants leaves only that latest grant; the pruned state still
    # lets every current device unwrap.
    node = HearthNode.create(tmp_path / "n", "Me", "desk")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    _befriend_with_enckeys(node, freja)
    post_id = freja.compose_post("roterende indlaeg", scope="kreds")
    _deliver(freja, node)
    rmsg = node.store.get_message(post_id)
    key, aad = node._content_key(rmsg)
    assert key is not None

    phone1 = _enroll_second_own_device(node)
    node.maintain_received_post_grants()          # round 1
    grants1 = node.store.wrap_grants(post_id, node.identity_pub)
    assert phone1.device_pub in grants1
    old_enc_pub = grants1[phone1.device_pub]["enc_pub"]
    round1_ids = {m.msg_id for m in
                 node.store.messages_by_author(node.identity_pub)
                 if m.payload.get("kind") == KIND_WRAP_GRANT
                 and m.payload.get("target") == post_id}
    assert len(round1_ids) == 1

    # rotate phone1's enc key (its stored enc_pub changes) AND enroll a
    # second satellite -- both must be covered by round 2's mint.
    phone1.rotate_enc()
    res = node.store.ingest_message(make_enckey(phone1))
    assert res.accepted, res.reason
    assert node.store.enckeys(node.identity_pub)[phone1.device_pub] \
        != old_enc_pub
    phone2 = _enroll_second_own_device(node)

    node.maintain_received_post_grants()          # round 2: re-mint
    round2_ids = {m.msg_id for m in
                 node.store.messages_by_author(node.identity_pub)
                 if m.payload.get("kind") == KIND_WRAP_GRANT
                 and m.payload.get("target") == post_id}
    assert len(round2_ids) == 2, "round 2 must mint a fresh grant"
    assert round1_ids < round2_ids

    # the LATEST grant alone (what the prune keeps) covers BOTH current
    # devices at their CURRENT enc keys.
    latest = node._latest_own_wrap_grants()[post_id]
    current = node.store.enckeys(node.identity_pub)
    for dpub in (phone1.device_pub, phone2.device_pub):
        assert dpub in latest, dpub
        assert latest[dpub]["enc_pub"] == current[dpub], dpub

    pruned = node.store.prune_superseded_wrap_grants()
    assert pruned >= 1
    surviving = {m.msg_id for m in
                node.store.messages_by_author(node.identity_pub)
                if m.payload.get("kind") == KIND_WRAP_GRANT
                and m.payload.get("target") == post_id
                and not node.store.is_tombstoned(m.msg_id)}
    assert surviving == (round2_ids - round1_ids)

    # every current device unwraps against the PRUNED state.
    post_prune_grants = node.store.wrap_grants(post_id, node.identity_pub)
    for dev in (phone1, phone2):
        gwrap = {dev.device_pub: post_prune_grants[dev.device_pub]}
        unwrapped = unwrap_key(gwrap, dev.device_pub, dev.enc_priv, aad)
        assert unwrapped == key, dev.device_pub


def test_sibling_sweeps_untouched(tmp_path):
    # run all four sweeps together; the three siblings' grant rows for
    # own-authored + received-DM targets must be byte-identical (same
    # msg_id, same payload) to a round that ran the siblings ALONE --
    # i.e. adding maintain_received_post_grants to the round must cause
    # NO re-mint and NO stripping of sibling-owned grants (the B.2c
    # carry-forward class of bug). The new sweep's own target (a received
    # post) is a positive control proving the round actually did
    # something.
    node = HearthNode.create(tmp_path / "n", "Me", "desk")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    # own kreds WALL post composed BEFORE befriending: freja's coverage
    # exists only via maintain_wrap_grants -- the shared-(author,target)-
    # keyspace case with maintain_own_device_grants' carry-forward.
    wmid = node.compose_post("min mur", scope="kreds", placement="profile")
    _befriend_with_enckeys(node, freja)
    rdm = freja.compose_dm(node.identity_pub, "modtaget")   # received DM
    _deliver(freja, node)
    odm = node.compose_dm(freja.identity_pub, "min besked")  # own DM
    rpost = freja.compose_post("frejas indlaeg", scope="kreds")  # received post
    _deliver(freja, node)
    phone1 = _enroll_second_own_device(node)
    phone2 = _enroll_second_own_device(node)

    def siblings_round():
        node.maintain_wrap_grants()
        node.maintain_own_device_grants()
        node.maintain_received_dm_grants()

    def grant_snapshot(target):
        return sorted(
            (m.msg_id, m.payload)
            for m in node.store.messages_by_author(node.identity_pub)
            if m.payload.get("kind") == KIND_WRAP_GRANT
            and m.payload.get("target") == target)

    siblings_round()
    node.store.prune_superseded_enckeys()
    node.store.prune_superseded_wrap_grants()
    before = {t: grant_snapshot(t) for t in (wmid, odm, rdm)}
    assert node.store.wrap_grants(rpost, node.identity_pub) == {}, \
        "positive control: the new sweep must not have run yet"

    siblings_round()
    node.maintain_received_post_grants()          # the new sweep, in the mix
    node.store.prune_superseded_enckeys()
    node.store.prune_superseded_wrap_grants()
    after = {t: grant_snapshot(t) for t in (wmid, odm, rdm)}

    assert before == after, \
        "sibling grant rows changed when the new sweep joined the round"
    # positive control: the new sweep DID mint for its own target
    rpost_grants = node.store.wrap_grants(rpost, node.identity_pub)
    for dev in (phone1, phone2):
        assert dev.device_pub in rpost_grants
