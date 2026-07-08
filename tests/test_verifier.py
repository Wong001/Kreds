from dataclasses import replace

from hearth.identity import (
    DeviceKeys, DeviceView, IdentityCeremony, SeenSet, SignedMessage,
    Verifier, canonical,
)


def wong_with_verifier():
    ceremony = IdentityCeremony()
    phone = DeviceKeys.create("wong-phone")
    node = DeviceKeys.create("wong-homenode")
    ceremony.enroll_first_device(phone)
    node.install(phone.enroll_other(node.device_pub, node.name),
                 phone.to_json()["identity_priv"])
    views = {}
    v = Verifier(ceremony.identity_pub, views)
    return ceremony, phone, node, v, views


def backdated(device, payload, seq):
    """Test-only forge: sign with an arbitrary (reused) seq, as a thief
    with a live stolen device would."""
    from hearth.identity import PROTOCOL
    body = canonical({
        "type": "message", "protocol": PROTOCOL,
        "identity_pub": device.cert.identity_pub,
        "device_pub": device.cert.device_pub,
        "seq": seq, "payload": payload,
    })
    return SignedMessage(device.cert, seq, payload,
                         device._device_priv.sign(body).hex())


def test_valid_message_accepted_and_device_autolearned():
    _, phone, _, v, views = wong_with_verifier()
    ok, reason = v.verify_message(phone.sign_message({"kind": "post"}))
    assert (ok, reason) == (True, "ok")
    assert phone.device_pub in views


def test_wrong_identity_rejected():
    _, _, _, v, _ = wong_with_verifier()
    mal = DeviceKeys.create("mallory")
    IdentityCeremony().enroll_first_device(mal)
    ok, reason = v.verify_message(mal.sign_message({"kind": "post"}))
    assert (ok, reason) == (False, "wrong identity")


def test_grafted_cert_rejected():
    _, phone, _, v, _ = wong_with_verifier()
    msg = phone.sign_message({"kind": "post"})
    mal = DeviceKeys.create("mallory")
    forged = SignedMessage(replace(msg.cert, device_pub=mal.device_pub),
                           msg.seq, msg.payload, msg.signature)
    ok, reason = v.verify_message(forged)
    assert (ok, reason) == (False, "cert invalid")


def test_out_of_order_delivery_now_legal():
    """Ambush 2 resolved: the spike's failing case must pass in product."""
    _, phone, _, v, _ = wong_with_verifier()
    m1 = phone.sign_message({"n": 1})
    m2 = phone.sign_message({"n": 2})
    assert v.verify_message(m2)[0] is True    # later arrives first
    assert v.verify_message(m1)[0] is True    # earlier still accepted
    assert v.verify_message(m1) == (False, "seq reuse")  # replay rejected


def test_post_revocation_seq_rejected():
    _, phone, node, v, _ = wong_with_verifier()
    for _ in range(3):
        assert v.verify_message(phone.sign_message({"kind": "post"}))[0]
    ok, _ = v.process_revocation(node.make_revocation(phone.device_pub, 3))
    assert ok
    loot = phone.sign_message({"kind": "post", "text": "send crypto"})
    assert v.verify_message(loot) == (False, "device revoked")


def test_backdating_thief_blocked_by_seen_set():
    """Ambush 1: reuse below last_valid_seq is caught by the seen-set."""
    _, phone, node, v, _ = wong_with_verifier()
    for _ in range(3):
        assert v.verify_message(phone.sign_message({"kind": "post"}))[0]
    assert v.process_revocation(node.make_revocation(phone.device_pub, 3))[0]
    assert v.verify_message(backdated(phone, {"kind": "post"}, 2)) == \
        (False, "seq reuse")


def test_backdated_but_unseen_seq_below_bound_accepted():
    """A legitimately-late message (seq <= last_valid, never seen) is OK:
    seen-set + revocation bound compose exactly as the spike report says."""
    _, phone, node, v, _ = wong_with_verifier()
    m1 = phone.sign_message({"n": 1})
    m2 = phone.sign_message({"n": 2})
    assert v.verify_message(m2)[0] is True          # m1 delayed in gossip
    assert v.process_revocation(node.make_revocation(phone.device_pub, 2))[0]
    assert v.verify_message(m1)[0] is True          # late but legit


def test_revocation_before_cert_kills_device_on_arrival():
    _, phone, node, v, _ = wong_with_verifier()
    tablet = DeviceKeys.create("wong-tablet")
    tablet.install(phone.enroll_other(tablet.device_pub, tablet.name),
                   phone.to_json()["identity_priv"])
    assert v.process_revocation(node.make_revocation(tablet.device_pub, 0))[0]
    loot = tablet.sign_message({"kind": "post"})
    assert v.verify_message(loot) == (False, "device revoked")


def test_forged_revocation_rejected():
    _, phone, _, v, _ = wong_with_verifier()
    mal = DeviceKeys.create("mallory")
    IdentityCeremony().enroll_first_device(mal)
    fake = mal.make_revocation(phone.device_pub, 0)
    assert v.process_revocation(fake) == (False, "wrong identity")
    assert v.verify_message(phone.sign_message({"kind": "post"}))[0] is True


def test_tampered_device_signature_rejected():
    _, phone, _, v, _ = wong_with_verifier()
    good = phone.sign_message({"kind": "post", "text": "real"})
    tampered = SignedMessage(good.cert, good.seq,
                             {"kind": "post", "text": "forged"},
                             good.signature)
    assert v.verify_message(tampered) == (False, "bad device signature")
