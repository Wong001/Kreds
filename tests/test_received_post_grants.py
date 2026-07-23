"""maintain_received_post_grants: re-wrap FRIEND-AUTHORED posts' content
keys to OWN satellite devices via RECIPIENT-signed wrap_grants -- so a
phone that never saw the friend online can still read a post the desk
already decrypted. Mirrors B.2c's maintain_received_dm_grants (node.py:
2190+) for KIND_POST instead of KIND_DM. maintain_wrap_grants (friend
audience, author-signed) and maintain_own_device_grants (own-authored) are
NOT touched -- a received post has author != self, so neither of those
sweeps ever iterates it.

The tests above this docstring's original scope (Task 1, the sweep) assert
at the GRANT level (store.wrap_grants(msg_id, own_identity) + a direct
unwrap_key call using the satellite's enc_priv), exactly like
test_received_dm_grants.py's own tests do for the DM sweep -- the grants
they mint were inert at read time until Task 2.

Task 2 (below): _content_key's KIND_POST branch now ALSO unions
store.wrap_grants(msg_id, self.identity_pub) when the post's author is a
known identity other than self (node.py:2956+) -- so these grants are no
longer inert. These tests drive the REAL consumer read path
(cache_message_keys/feed) on a genuine SECOND HearthNode for the SAME
identity (own device, own store, own device/enc_privs -- see
_second_device_node/_deliver_to_own_device below), not _content_key called
bare and not a hand-rolled unwrap_key -- the point is proving the actual
satellite can read its own feed.

Routing verification (required by the Task 2 brief): messages_not_in
(store.py:702-...) needs NO change. Its KIND_WRAP_GRANT gate (store.py:
730-733) is signer-and-target-agnostic already -- `wr = wraps of THIS
grant message; route if peer_identity == the grant's own signer OR a
peer device is named in wr` -- it never distinguishes "author-shaped" from
"recipient-shaped". maintain_received_post_grants' own targets rule
(node.py:2298-2300, mirrored from maintain_received_dm_grants) wraps
ONLY this identity's own enrolled devices, so `wr` for a recipient-signed
post grant is always a subset of the signer's own devices -- exactly the
guarantee maintain_received_dm_grants' docstring already states for DMs
("these grants route ONLY to the devices named in their wraps... so one
can never reach a friend", node.py:2196-2198). Same mechanism, same
conclusion, for posts: nothing to change. (test_new_own_device_reads_
friend_post_after_sweep above already exercises this routing gate
implicitly via _deliver's real store.messages_not_in call for the
grant's own delivery leg; this file's Task 2 tests exercise it again for
the POST leg via _deliver_to_own_device.)"""
import json
from pathlib import Path

from hearth.node import HearthNode
from hearth.identity import DeviceKeys, DeviceView
from hearth.dmcrypt import unwrap_key, wrap_key
from hearth.messages import KIND_WRAP_GRANT, make_enckey, make_wrap_grant


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


def _second_device_node(owner, data_dir, device_name):
    """A genuine second HearthNode for owner's SAME identity (own device,
    own store, own device/enc_privs -- not a bare DeviceKeys satellite the
    way _enroll_second_own_device above builds one). Store-level pairing,
    mirrors tests/test_sync_session.py's test_own_devices_adopt_friend_list
    (the enrollment ceremony itself is out of scope here, same as that
    precedent). Needed so Task 2's tests can drive the REAL consumer read
    path (_content_key/feed/cache_message_keys) AS the satellite device
    itself, not node._content_key called on the PRIMARY device with a
    borrowed DeviceKeys wrap the way Task 1's sweep-level tests above do."""
    data_dir = Path(data_dir)
    data_dir.mkdir(parents=True, exist_ok=True)
    dev = DeviceKeys.create(device_name)
    dev.install(owner.device.enroll_other(dev.device_pub, dev.name),
               owner.device.to_json()["identity_priv"])
    (data_dir / "keys.json").write_text(json.dumps(dev.to_json()))
    node2 = HearthNode(data_dir)
    node2.store.add_identity(node2.identity_pub, is_self=True)
    node2.store.save_views(node2.identity_pub,
                           {dev.device_pub: DeviceView(cert=dev.cert)})
    return node2


def _deliver_to_own_device(src, dst):
    """Sync src -> dst as PRODUCTION own-device pairing sync does
    (sync.py:613-618 adopts the peer's known-identity set; sync.py:628-629
    computes `entitled` from that FULL known-identity set) -- unlike
    _deliver above (scoped to just {src.identity_pub}, the friend-to-
    friend case: a peer only ever gets what the SENDER itself authored),
    an own device inherits everything the primary already holds, including
    friends' content. dst must already share src's identity_pub (see
    _second_device_node)."""
    assert dst.identity_pub == src.identity_pub, "own-device sync only"
    for ident in src.store.known_identities():
        if not dst.store.is_known(ident):
            dst.store.add_identity(ident)
    entitled = set(src.store.known_identities())
    for m in src.store.messages_not_in({}, entitled, dst.identity_pub):
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


# -- Task 2: consumer trust rule (_content_key's KIND_POST branch) ---------


def test_content_key_honors_recipient_grant_for_friend_post(tmp_path):
    # B's SECOND desktop device (no inline wrap, no author grant for its
    # key) decrypts a friend post via a B-signed grant -- through the REAL
    # read path (cache_message_keys + feed), not _content_key called bare.
    node = HearthNode.create(tmp_path / "n", "Me", "desk")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    _befriend_with_enckeys(node, freja)
    post_id = freja.compose_post("kun paa desk foerst", scope="kreds")
    _deliver(freja, node)
    rmsg = node.store.get_message(post_id)
    key, aad = node._content_key(rmsg)
    assert key is not None, "desk cannot recover the received post key"

    phone2 = _second_device_node(node, tmp_path / "p2", "b-phone2")
    phone2.ensure_enckey()
    _deliver(phone2, node)                # phone2's own enckey reaches desk
    node.maintain_received_post_grants()  # desk mints the recipient-signed grant

    _deliver_to_own_device(node, phone2)  # phone2 adopts desk's post + grant

    # precondition: neither an inline wrap NOR an author-signed grant
    # covers phone2's key -- ONLY the recipient-signed grant can unlock it.
    rmsg2 = phone2.store.get_message(post_id)
    assert phone2.device.device_pub not in rmsg2.payload.get("wraps", {})
    assert phone2.device.device_pub not in \
        phone2.store.wrap_grants(post_id, freja.identity_pub)
    assert phone2.device.device_pub in \
        phone2.store.wrap_grants(post_id, phone2.identity_pub)

    phone2.cache_message_keys()           # THE REAL READ PATH
    feed = phone2.feed()
    match = next((p for p in feed if p["msg_id"] == post_id), None)
    assert match is not None, "recipient-signed grant did not unlock the post"
    assert match["text"] == "kun paa desk foerst"


def test_content_key_rejects_third_identity_grant(tmp_path):
    # C (a third identity) mints a recipient-SHAPED grant for A's post,
    # targeting B's device -- B must NOT decrypt via it. The grant is a
    # REAL, validly-signed wrap_grant that WOULD unwrap to the true key if
    # trusted (same key/aad node already holds) -- proving rejection is a
    # trust decision, not merely "wrong key material".
    node = HearthNode.create(tmp_path / "n", "Me", "desk")           # B
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")  # A
    casper = HearthNode.create(tmp_path / "c", "Casper", "casper-phone")  # C
    _befriend_with_enckeys(node, freja)
    post_id = freja.compose_post("kun til B", scope="kreds")
    _deliver(freja, node)
    rmsg = node.store.get_message(post_id)
    key, aad = node._content_key(rmsg)
    assert key is not None, "precondition: desk can decrypt via inline wrap"

    # phone2 gets the post itself but deliberately NEVER gets a legitimate
    # grant (node.maintain_received_post_grants() is never called here) --
    # the hostile grant below must be the ONLY candidate in play, or a
    # pass would prove nothing.
    phone2 = _second_device_node(node, tmp_path / "p2", "b-phone2")
    phone2.ensure_enckey()
    phone2.store.add_identity(freja.identity_pub)
    phone2.store.add_identity(casper.identity_pub)
    _deliver(freja, phone2)               # the post itself, no grant

    # precondition: no inline wrap, no author-signed grant, no legitimate
    # recipient-signed grant covers phone2's key at all.
    rmsg2 = phone2.store.get_message(post_id)
    assert phone2.device.device_pub not in rmsg2.payload.get("wraps", {})
    assert phone2.store.wrap_grants(post_id, freja.identity_pub) == {}
    assert phone2.store.wrap_grants(post_id, phone2.identity_pub) == {}

    # C crafts a recipient-shaped grant naming phone2's device -- forced in
    # directly (real sync would never route this: messages_not_in's
    # KIND_WRAP_GRANT gate only offers a grant to its own signer or to a
    # peer whose device it names, and phone2 is not casper's own device;
    # same "forced" idiom test_sweep_skips_undecryptable_and_nonfriend_
    # posts uses to reach an otherwise-unroutable state directly).
    hostile_wraps = wrap_key(key, {phone2.device.device_pub:
                                   phone2.device.enc_pub}, aad)
    hostile = make_wrap_grant(casper.device, post_id, hostile_wraps)
    assert phone2.store.ingest_message(hostile).accepted, \
        "precondition: the hostile grant is shape-valid and held"
    assert phone2.device.device_pub in \
        phone2.store.wrap_grants(post_id, casper.identity_pub), \
        "precondition: the hostile grant really would unwrap the true key"

    phone2.cache_message_keys()           # THE REAL READ PATH
    feed = phone2.feed()
    assert post_id not in {p["msg_id"] for p in feed}, \
        "a third identity's recipient-shaped grant must never be trusted"


def test_content_key_rejects_own_grant_for_nonfriend_post(tmp_path):
    # B somehow holds a post from stranger S plus a B-signed grant for it
    # (compromised-device model: some other on-device compromise minted
    # this, not the legitimate sweep -- maintain_received_post_grants
    # would refuse a non-friend author, per test_sweep_skips_
    # undecryptable_and_nonfriend_posts above) -- the read path must
    # refuse it independently, defense in depth. The grant wraps the
    # TRUE content key (S can always read its own post via its self-wrap)
    # so, as in test_content_key_rejects_third_identity_grant, rejection
    # proves a trust decision, not merely "wrong key material".
    node = HearthNode.create(tmp_path / "n", "Me", "desk")
    stranger = HearthNode.create(tmp_path / "s", "Stranger", "s-phone")
    stranger.ensure_enckey()
    post_id = stranger.compose_post("fremmed indlaeg", scope="kreds")
    forced = stranger.store.get_message(post_id)
    real_key, aad = stranger._content_key(stranger.store.get_message(post_id))
    assert real_key is not None, "precondition: S can read its own post"
    # Force the row into node's store the way test_sweep_skips_
    # undecryptable_and_nonfriend_posts' Part 1 does -- this can never
    # arise via real sync (messages_not_in gates KIND_POST on the peer's
    # own entitlement): B briefly "knows" S only long enough to satisfy
    # ingest_message's is_known gate, then forgets -- modeling "never
    # actually friends" (no inline wrap for node's device either way).
    node.store.add_identity(stranger.identity_pub)
    assert node.store.ingest_message(forced).accepted
    node.store.remove_identity(stranger.identity_pub)
    assert stranger.identity_pub not in node.store.known_identities()
    assert node.device.device_pub not in forced.payload.get("wraps", {})
    assert node._content_key(forced)[0] is None, \
        "precondition: no legitimate path can decrypt this post"

    # B's OWN device signs a recipient-shaped grant for it anyway.
    own_wraps = wrap_key(real_key, {node.device.device_pub:
                                    node.device.enc_pub}, aad)
    own_grant = make_wrap_grant(node.device, post_id, own_wraps)
    assert node.store.ingest_message(own_grant).accepted
    assert node.device.device_pub in \
        node.store.wrap_grants(post_id, node.identity_pub), \
        "precondition: the own-signed grant really would unwrap the true key"

    node.cache_message_keys()             # THE REAL READ PATH
    feed = node.feed()
    assert post_id not in {p["msg_id"] for p in feed}, \
        "an own-signed grant for a non-friend author's post must never" \
        " be trusted"
