"""Gate: the committed wire vectors are current, deterministic, and
self-verify against the real hearth implementation."""
import json
from pathlib import Path

from hearth.identity import EnrollmentCert, pub_from_hex
from hearth.sync import _auth_body

from make_wire_vectors import FIXTURE_PATH, build_vectors


def test_committed_vectors_are_current():
    committed = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
    assert committed == build_vectors(), (
        "fixtures/wire_vectors.json is stale -- rerun "
        "tools/make_wire_vectors.py and commit")


def test_generator_is_deterministic():
    assert build_vectors() == build_vectors()


def test_auth_vectors_verify_with_hearth_code():
    for case in build_vectors()["auth_cases"]:
        body = _auth_body(case["nonce"])
        assert body.hex() == case["body_hex"]
        pub_from_hex(case["device_pub"]).verify(
            bytes.fromhex(case["sig"]), body)   # raises on mismatch


def test_cert_vectors_verify_with_hearth_code():
    for case in build_vectors()["cert_cases"]:
        cert = EnrollmentCert.from_dict(case["cert"])
        assert cert.verify() is case["valid"]
        if case["body_hex"] is not None:
            assert cert.body().hex() == case["body_hex"]
