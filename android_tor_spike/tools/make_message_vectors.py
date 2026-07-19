"""Deterministic SignedMessage vectors from the real hearth impl (THROWAWAY
keys). ASCII-only output. Gates the Kotlin SignedMessage port."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))  # repo root

from hearth.identity import (EnrollmentCert, PROTOCOL, SignedMessage, canonical,
                             priv_from_hex, pub_hex, _make_enrollment)

FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "message_vectors.json"
IDP = priv_from_hex("11" * 32); DVP = priv_from_hex("22" * 32)
IDPUB = pub_hex(IDP.public_key()); DVPUB = pub_hex(DVP.public_key())
ENROLLED_AT = 1752900000.123456


def _cert() -> EnrollmentCert:
    return _make_enrollment(IDP, DVPUB, "vec-device", ENROLLED_AT)


def _msg(seq: int, payload: dict) -> SignedMessage:
    cert = _cert()
    body = canonical({"type": "message", "protocol": PROTOCOL,
                      "identity_pub": IDPUB, "device_pub": DVPUB,
                      "seq": seq, "payload": payload})
    return SignedMessage(cert, seq, payload, DVP.sign(body).hex())


def build() -> dict:
    cases = []
    # Payloads use the REAL field name "kind" (not "type"); "blobs"/"poster"/
    # "thumbs" are the cleartext blob-ref fields hearth reads.
    for seq, payload in [
        (1, {"kind": "post", "scope": "kreds", "body_ct": "aa", "blobs": []}),
        (2, {"kind": "post", "scope": "kreds", "body_ct": "bb", "blobs": ["aa" * 32], "thumbs": ["bb" * 32]}),
        (3, {"kind": "dm", "recipient": IDPUB, "body_ct": "cc", "poster": "cc" * 32}),
    ]:
        m = _msg(seq, payload)
        cases.append({"dict": m.to_dict(), "msg_id": m.msg_id, "kind": payload["kind"],
                      "body_hex": m.body().hex(), "valid": True})
    good = _msg(4, {"kind": "post", "scope": "kreds", "body_ct": "orig", "blobs": []})
    tampered = dict(good.to_dict(), payload={"kind": "post", "scope": "kreds", "body_ct": "EVIL", "blobs": []})
    cases.append({"dict": tampered, "msg_id": None, "kind": "post", "body_hex": None, "valid": False})
    return {"cases": cases}


def main():
    FIXTURE.write_text(json.dumps(build(), indent=2) + "\n", encoding="utf-8")
    print("wrote", FIXTURE)


if __name__ == "__main__":
    main()
