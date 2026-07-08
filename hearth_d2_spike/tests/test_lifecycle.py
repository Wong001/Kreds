"""
D2 lifecycle stories, exactly as locked in hearth_concept_capture_v0_2.

Each test is one narrative from the concept doc, written so its name and
body read as the story it proves.
"""

import sys, os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from hearth_identity import Device, IdentityCeremony, PeerVerifier


def make_wong():
    """Standard fixture: Wong creates identity, enrolls phone + home node."""
    ceremony = IdentityCeremony()
    phone = Device("wong-phone")
    node = Device("wong-homenode")
    ceremony.enroll_first_device(phone)
    phone.enroll_new_device(node)          # phone brings the node online
    return ceremony, phone, node


# ---------------------------------------------------------------------------
# Story 1: create identity -> enroll phone + home node
# ---------------------------------------------------------------------------

def test_identity_creation_and_dual_enrollment():
    ceremony, phone, node = make_wong()
    assert phone.cert.verify()
    assert node.cert.verify()
    assert phone.cert.identity_pub == node.cert.identity_pub == ceremony.identity_pub
    assert phone.cert.device_pub != node.cert.device_pub  # distinct device keys


# ---------------------------------------------------------------------------
# Story 2: in-person QR friend-add, verified fully offline
# ---------------------------------------------------------------------------

def test_qr_friend_add_is_offline_and_needs_no_home_node():
    _, wong_phone, wong_node = make_wong()

    # Freja scans Wong. Her client supplies a fresh nonce; Wong's PHONE
    # signs it with its own enrolled key. The home node is not consulted —
    # to prove that, we burn it down first.
    wong_node.destroy()

    freja = PeerVerifier("freja")
    nonce = freja.fresh_nonce()
    qr = wong_phone.make_qr(nonce)
    assert freja.add_friend_via_qr(qr, nonce) is True
    assert freja.friend_identity_pub == wong_phone.cert.identity_pub

    # And Wong's posts now verify against that stored identity.
    post = wong_phone.sign_message({"kind": "post", "text": "hej fra Wong"})
    assert freja.verify_message(post) is True


def test_photographed_qr_replayed_at_a_later_meeting_is_rejected():
    _, wong_phone, _ = make_wong()

    # Attacker photographs the QR Wong showed Freja...
    freja = PeerVerifier("freja")
    freja_nonce = freja.fresh_nonce()
    old_qr = wong_phone.make_qr(freja_nonce)
    assert freja.add_friend_via_qr(old_qr, freja_nonce)

    # ...and later shows the same QR to Mads, claiming to be Wong.
    mads = PeerVerifier("mads")
    mads_nonce = mads.fresh_nonce()          # different fresh nonce
    assert mads.add_friend_via_qr(old_qr, mads_nonce) is False
    assert "nonce mismatch" in mads.log[-1]


# ---------------------------------------------------------------------------
# Story 3: phone dies -> walk to your desk -> enroll replacement
# ---------------------------------------------------------------------------

def test_phone_death_replacement_enrolled_from_home_node():
    _, phone, node = make_wong()

    # Freja is already Wong's friend via the ORIGINAL phone.
    freja = PeerVerifier("freja")
    nonce = freja.fresh_nonce()
    assert freja.add_friend_via_qr(phone.make_qr(nonce), nonce)

    # Phone drowns. Wong walks to his desk.
    last_seq_of_dead_phone = phone.current_seq
    phone.destroy()

    new_phone = Device("wong-phone-2")
    node.enroll_new_device(new_phone)
    revocation = node.revoke_device(phone.device_pub, last_seq_of_dead_phone)

    # Freja hears the revocation and then a post from the new phone.
    assert freja.process_revocation(revocation)
    post = new_phone.sign_message({"kind": "post", "text": "new phone, same me"})
    assert freja.verify_message(post) is True

    # Identity intact: same identity pub throughout.
    assert new_phone.cert.identity_pub == freja.friend_identity_pub


# ---------------------------------------------------------------------------
# Story 4: home node dies -> phone re-enrolls a new one
# ---------------------------------------------------------------------------

def test_home_node_death_replacement_enrolled_from_phone():
    _, phone, node = make_wong()
    node_last_seq = node.current_seq
    node.destroy()  # disk failure / reformat / fire in the office corner

    new_node = Device("wong-homenode-2")
    phone.enroll_new_device(new_node)
    rev = phone.revoke_device(node.device_pub, node_last_seq)

    freja = PeerVerifier("freja")
    nonce = freja.fresh_nonce()
    assert freja.add_friend_via_qr(phone.make_qr(nonce), nonce)
    assert freja.process_revocation(rev)

    # The new node can act as a full identity holder (e.g. sync frames).
    frame = new_node.sign_message({"kind": "sync", "n": 1})
    assert freja.verify_message(frame) is True


# ---------------------------------------------------------------------------
# Story 5: total loss -> paper seed recovery
# ---------------------------------------------------------------------------

def test_total_device_loss_recovered_from_paper_seed():
    ceremony, phone, node = make_wong()
    paper = ceremony.paper_seed()

    # Freja knew Wong before the disaster.
    freja = PeerVerifier("freja")
    nonce = freja.fresh_nonce()
    assert freja.add_friend_via_qr(phone.make_qr(nonce), nonce)
    phone_seq, node_seq = phone.current_seq, node.current_seq
    phone_pub, node_pub = phone.device_pub, node.device_pub

    # House fire with the phone inside: everything gone.
    phone.destroy()
    node.destroy()

    # Recovery: seed from the drawer -> same identity key -> fresh devices.
    recovered = IdentityCeremony.recover(paper)
    assert recovered.identity_pub == ceremony.identity_pub  # deterministic

    new_phone = Device("wong-phone-3")
    recovered.enroll_first_device(new_phone)
    rev_phone = new_phone.revoke_device(phone_pub, phone_seq)
    rev_node = new_phone.revoke_device(node_pub, node_seq)

    # Freja accepts revocations + new device without re-meeting Wong,
    # because everything is signed by the identity key she already trusts.
    assert freja.process_revocation(rev_phone)
    assert freja.process_revocation(rev_node)
    post = new_phone.sign_message({"kind": "post", "text": "rising from ashes"})
    assert freja.verify_message(post) is True


def test_wrong_seed_recovers_a_different_identity():
    ceremony, *_ = make_wong()
    impostor = IdentityCeremony()  # random seed
    assert impostor.identity_pub != ceremony.identity_pub
