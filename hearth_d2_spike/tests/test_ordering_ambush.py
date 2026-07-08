"""
Finding: strict monotonic sequence acceptance — the very mechanism that
blocks backdating thieves — REJECTS legitimate messages that arrive out
of order. Gossip networks deliver out of order routinely.

This is the second real ambush of the spike: the anti-replay defense and
the transport's delivery guarantees are in direct tension. The spike's
'seq must exceed max seen' rule is too strict for production; the real
client needs a seen-seq SET (or sliding window), not a high-water mark.
"""

import sys, os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from hearth_identity import Device, IdentityCeremony, PeerVerifier


def test_out_of_order_delivery_drops_a_legitimate_message():
    ceremony = IdentityCeremony()
    phone = Device("wong-phone")
    ceremony.enroll_first_device(phone)

    freja = PeerVerifier("freja", track_seqs=True)
    nonce = freja.fresh_nonce()
    assert freja.add_friend_via_qr(phone.make_qr(nonce), nonce)

    msg1 = phone.sign_message({"kind": "post", "n": 1})   # seq 1
    msg2 = phone.sign_message({"kind": "post", "n": 2})   # seq 2

    # Gossip delivers msg2 first (perfectly normal in a P2P mesh)...
    assert freja.verify_message(msg2) is True
    # ...and the earlier, entirely legitimate msg1 is now rejected.
    assert freja.verify_message(msg1) is False            # <-- the tension
    assert "replay/backdate" in freja.log[-1]
