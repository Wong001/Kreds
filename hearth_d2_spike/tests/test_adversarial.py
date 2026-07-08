"""
Adversarial cases for D2 — the tests whose FAILURE modes matter most.

Two of these are deliberately structured to demonstrate a vulnerability
window rather than assert everything is fine:
  * backdating by a thief vs. a NAIVE client (no seq tracking)
  * the gossip-lag window (friend who hasn't heard the revocation yet)
Those document design requirements, not bugs in the spike.
"""

import sys, os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from hearth_identity import (
    Device, IdentityCeremony, PeerVerifier,
)


def wong_and_freja(track_seqs: bool = True):
    ceremony = IdentityCeremony()
    phone = Device("wong-phone")
    node = Device("wong-homenode")
    ceremony.enroll_first_device(phone)
    phone.enroll_new_device(node)
    freja = PeerVerifier("freja", track_seqs=track_seqs)
    nonce = freja.fresh_nonce()
    assert freja.add_friend_via_qr(phone.make_qr(nonce), nonce)
    return ceremony, phone, node, freja


# ---------------------------------------------------------------------------
# Forged credentials
# ---------------------------------------------------------------------------

def test_device_cert_signed_by_wrong_identity_is_rejected():
    _, _, _, freja = wong_and_freja()

    # Mallory generates her own identity and enrolls her own device, then
    # presents its (validly signed, wrong-identity) messages as Wong's.
    mal_ceremony = IdentityCeremony()
    mal_dev = Device("mallory-phone")
    mal_ceremony.enroll_first_device(mal_dev)
    msg = mal_dev.sign_message({"kind": "post", "text": "hi it's wong trust me"})
    assert freja.verify_message(msg) is False
    assert "not my friend's identity" in freja.log[-1]


def test_tampered_cert_fails_verification():
    from dataclasses import replace
    _, phone, _, freja = wong_and_freja()
    mal_dev = Device("mallory-phone")
    # Mallory grafts her device pub into Wong's real cert (signature now stale).
    forged = replace(phone.cert, device_pub=mal_dev.device_pub)
    assert forged.verify() is False


def test_revocation_forged_by_non_identity_key_is_rejected():
    _, phone, _, freja = wong_and_freja()
    mal_ceremony = IdentityCeremony()
    mal_dev = Device("mallory-phone")
    mal_ceremony.enroll_first_device(mal_dev)
    # Mallory tries to revoke Wong's phone out from under him.
    fake_rev = mal_dev.revoke_device(phone.device_pub, 0)
    assert freja.process_revocation(fake_rev) is False
    # And Wong's phone still works for Freja.
    assert freja.verify_message(phone.sign_message({"kind": "post", "text": "still here"}))


def test_impostor_qr_with_different_identity_rejected_for_existing_friend():
    _, phone, _, freja = wong_and_freja()  # freja already has Wong's identity
    mal_ceremony = IdentityCeremony()
    mal_dev = Device("mallory-phone-claiming-to-be-wong")
    mal_ceremony.enroll_first_device(mal_dev)
    nonce = freja.fresh_nonce()
    qr = mal_dev.make_qr(nonce)
    # Freja's client is re-scanning "Wong" (e.g. re-pair flow) — identity
    # pub doesn't match the stored one -> reject.
    assert freja.add_friend_via_qr(qr, nonce) is False
    assert "identity mismatch" in freja.log[-1]


# ---------------------------------------------------------------------------
# Theft + revocation ordering (the predicted ambush)
# ---------------------------------------------------------------------------

def test_stolen_device_post_revocation_signatures_rejected():
    _, phone, node, freja = wong_and_freja()

    # Some legitimate history first.
    for i in range(3):
        assert freja.verify_message(phone.sign_message({"kind": "post", "n": i}))

    # Phone stolen at seq 3. Wong revokes from the home node.
    rev = node.revoke_device(phone.device_pub, last_valid_seq=3)
    assert freja.process_revocation(rev)

    # Thief keeps signing; seq counter naturally moves past 3.
    stolen = phone  # thief holds the live device
    loot = stolen.sign_message({"kind": "post", "text": "send crypto"})
    assert loot.seq == 4
    assert freja.verify_message(loot) is False
    assert "device revoked" in freja.log[-1]


def test_backdating_thief_DEFEATS_naive_client_without_seq_tracking():
    """THE AMBUSH, DEMONSTRATED: a client that does not track seen
    sequence numbers accepts a thief's backdated signature even after
    processing the revocation. This test PASSES by proving the attack
    works against the naive configuration."""
    _, phone, node, freja_naive = wong_and_freja(track_seqs=False)

    for i in range(3):
        assert freja_naive.verify_message(phone.sign_message({"kind": "post", "n": i}))

    rev = node.revoke_device(phone.device_pub, last_valid_seq=3)
    assert freja_naive.process_revocation(rev)

    # Thief reuses seq 2 — "this was signed before the theft, honest."
    backdated = phone.sign_message_with_seq(
        {"kind": "post", "text": "backdated loot"}, seq=2)
    assert freja_naive.verify_message(backdated) is True   # <-- the hole
    assert "naive client" in freja_naive.log[-1]


def test_backdating_thief_BLOCKED_by_seq_tracking_client():
    _, phone, node, freja = wong_and_freja(track_seqs=True)

    for i in range(3):
        assert freja.verify_message(phone.sign_message({"kind": "post", "n": i}))

    rev = node.revoke_device(phone.device_pub, last_valid_seq=3)
    assert freja.process_revocation(rev)

    backdated = phone.sign_message_with_seq(
        {"kind": "post", "text": "backdated loot"}, seq=2)
    assert freja.verify_message(backdated) is False
    assert "replay/backdate" in freja.log[-1]


def test_replayed_message_rejected_by_seq_tracking():
    _, phone, _, freja = wong_and_freja()
    msg = phone.sign_message({"kind": "post", "text": "once only"})
    assert freja.verify_message(msg) is True
    assert freja.verify_message(msg) is False   # exact replay
    assert "replay/backdate" in freja.log[-1]


def test_revocation_arriving_before_cert_still_kills_the_device():
    """Out-of-order gossip: Freja hears 'device X revoked' before ever
    seeing device X. When X's messages later arrive, they must be dead
    on arrival past last_valid_seq."""
    _, phone, node, freja = wong_and_freja()

    # Wong enrolls a tablet Freja has never seen, which is then stolen
    # at seq 0 before posting anything legitimate.
    tablet = Device("wong-tablet")
    phone.enroll_new_device(tablet)
    rev = node.revoke_device(tablet.device_pub, last_valid_seq=0)

    assert freja.process_revocation(rev)          # revocation first
    loot = tablet.sign_message({"kind": "post", "text": "tablet thief"})
    assert freja.verify_message(loot) is False    # cert arrives second, too late


# ---------------------------------------------------------------------------
# The gossip-lag window (honest limitation, demonstrated)
# ---------------------------------------------------------------------------

def test_gossip_lag_window_friend_who_missed_the_revocation_is_exposed():
    """A friend who has NOT yet received the revocation accepts the
    thief's messages. No signature scheme fixes this — revocation is
    information, and information travels at gossip speed. Documented as
    a design fact: the window equals revocation propagation time."""
    _, phone, node, freja = wong_and_freja()
    mads = PeerVerifier("mads")
    nonce = mads.fresh_nonce()
    assert mads.add_friend_via_qr(phone.make_qr(nonce), nonce)

    rev = node.revoke_device(phone.device_pub, last_valid_seq=phone.current_seq)
    assert freja.process_revocation(rev)   # freja heard it
    # mads did NOT hear it yet.

    loot = phone.sign_message({"kind": "post", "text": "thief speaking"})
    assert freja.verify_message(loot) is False   # protected
    assert mads.verify_message(loot) is True     # exposed until gossip arrives

    # Once the revocation reaches Mads, the SAME already-accepted message
    # would now be rejected — a real client must be able to retro-drop.
    assert mads.process_revocation(rev)
    loot2 = phone.sign_message({"kind": "post", "text": "more loot"})
    assert mads.verify_message(loot2) is False
