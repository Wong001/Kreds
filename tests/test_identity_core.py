import pytest

from hearth.identity import (
    DeviceKeys, IdentityCeremony, EnrollmentCert, SignedMessage, canonical,
)


def make_person():
    ceremony = IdentityCeremony()
    phone = DeviceKeys.create("phone")
    ceremony.enroll_first_device(phone)
    return ceremony, phone


def test_seed_recovery_is_deterministic():
    ceremony, _ = make_person()
    recovered = IdentityCeremony.recover(ceremony.paper_seed())
    assert recovered.identity_pub == ceremony.identity_pub


def test_protocol_bump_breaks_cross_version_signatures():
    """Whole-branch review, Fix 9: the DEFRIENDS phase is a new mandatory
    wire phase, so PROTOCOL was bumped. PROTOCOL is domain-separation
    baked into every signed body via canonical() (enrollment certs, AUTH
    challenges, messages, revocations, defriend notices) -- that's the
    mechanism that makes a mismatched-version peer's signatures simply
    fail verification (refuse cleanly at AUTH/HELLO) instead of the two
    sides misaligning frames deeper in the session. Pin the mechanism
    directly rather than re-deriving it via a fragile two-node session
    that would need to mutate the shared PROTOCOL global mid-test."""
    from hearth.identity import PROTOCOL
    assert PROTOCOL == "hearth/v0.2"          # this session's bumped value
    current = canonical({"type": "gossip-auth", "protocol": PROTOCOL,
                         "nonce": "abc"})
    older = canonical({"type": "gossip-auth", "protocol": "hearth/v0.1",
                       "nonce": "abc"})
    assert current != older                   # different signed bytes


def test_enrollment_cert_verifies_and_roundtrips():
    _, phone = make_person()
    assert phone.cert.verify()
    clone = EnrollmentCert.from_dict(phone.cert.to_dict())
    assert clone.verify() and clone == phone.cert


def test_tampered_cert_fails():
    from dataclasses import replace
    _, phone = make_person()
    other = DeviceKeys.create("evil")
    forged = replace(phone.cert, device_pub=other.device_pub)
    assert forged.verify() is False


def test_device_enrolls_another_device():
    _, phone = make_person()
    node = DeviceKeys.create("homenode")
    cert = phone.enroll_other(node.device_pub, node.name)
    node.install(cert, phone.to_json()["identity_priv"])
    assert node.cert.verify()
    assert node.identity_pub == phone.identity_pub


def test_sign_message_increments_seq_and_verifies():
    _, phone = make_person()
    m1 = phone.sign_message({"kind": "post", "text": "a"})
    m2 = phone.sign_message({"kind": "post", "text": "b"})
    assert (m1.seq, m2.seq) == (1, 2)
    assert m1.verify_device_signature() and m2.verify_device_signature()
    assert m1.msg_id != m2.msg_id
    assert SignedMessage.from_dict(m1.to_dict()).msg_id == m1.msg_id


def test_devicekeys_json_roundtrip_preserves_seq():
    _, phone = make_person()
    phone.sign_message({"kind": "post", "text": "a"})
    restored = DeviceKeys.from_json(phone.to_json())
    m = restored.sign_message({"kind": "post", "text": "b"})
    assert m.seq == 2 and m.verify_device_signature()


def test_revocation_cert_verifies():
    _, phone = make_person()
    rev = phone.make_revocation("ab" * 32, 7)
    assert rev.verify() and rev.last_valid_seq == 7


def test_install_rejects_mismatched_identity_key():
    ceremony_a, phone_a = make_person()
    _, phone_b = make_person()          # different identity
    node = DeviceKeys.create("node")
    cert = phone_a.enroll_other(node.device_pub, node.name)
    with pytest.raises(ValueError):
        node.install(cert, phone_b.to_json()["identity_priv"])
