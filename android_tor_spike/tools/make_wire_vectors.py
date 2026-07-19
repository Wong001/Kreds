"""Deterministic cross-language wire vectors (THROWAWAY keys only -- this
file's output is committed to the public repo). ASCII-only output.

The TS side (app/src/__tests__/wire.test.ts) must reproduce every
bytes_hex from the marker-decoded obj, verify every signature, and parse
every frame. Floats are represented as {"__pyfloat__": n} markers because
JSON cannot round-trip 1234.0 vs 1234 through JavaScript."""
import json
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))  # repo root

from hearth.identity import (EnrollmentCert, PROTOCOL, canonical,
                             priv_from_hex, pub_hex)
from hearth.sync import _auth_body

FIXTURE_PATH = Path(__file__).resolve().parents[1] / "fixtures" / "wire_vectors.json"

IDENTITY_PRIV = priv_from_hex("11" * 32)
DEVICE_PRIV = priv_from_hex("22" * 32)
IDENTITY_PUB = pub_hex(IDENTITY_PRIV.public_key())
DEVICE_PUB = pub_hex(DEVICE_PRIV.public_key())
ENROLLED_AT = 1752900000.123456        # typical time.time(): fractional
ENROLLED_AT_INTEGRAL = 1752900000.0    # the nasty case: renders "...0.0"
NONCES = ["aa" * 16, "0123456789abcdef0123456789abcdef"]


def _mark_floats(obj):
    """Replace every float with a {"__pyfloat__": f} marker, recursively."""
    if isinstance(obj, float):
        return {"__pyfloat__": obj}
    if isinstance(obj, dict):
        return {k: _mark_floats(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_mark_floats(v) for v in obj]
    return obj


def _make_cert(name: str, enrolled_at: float) -> EnrollmentCert:
    body = canonical({
        "type": "enrollment", "protocol": PROTOCOL,
        "identity_pub": IDENTITY_PUB, "device_pub": DEVICE_PUB,
        "device_name": name, "enrolled_at": enrolled_at,
    })
    return EnrollmentCert(IDENTITY_PUB, DEVICE_PUB, name, enrolled_at,
                          IDENTITY_PRIV.sign(body).hex())


def build_vectors() -> dict:
    canonical_objs = [
        ("sorted_keys", {"b": 1, "a": "x"}),
        ("nested", {"outer": {"z": [1, 2, {"k": "v"}], "a": True, "n": None}}),
        ("escapes", {"s": "line\nquote\"back\\slash\ttab"}),
        ("ensure_ascii", {"name": "Åse ☕ \U0001d11e"}),
        ("astral_key_sort", {"￿": 1, "\U00010000": 2}),
        ("float_fractional", {"t": ENROLLED_AT}),
        ("float_integral", {"t": ENROLLED_AT_INTEGRAL}),
        ("empty", {}),
    ]
    canonical_cases = [
        {"name": name, "obj": _mark_floats(obj),
         "bytes_hex": canonical(obj).hex()}
        for name, obj in canonical_objs
    ]

    auth_cases = []
    for nonce in NONCES:
        body = _auth_body(nonce)
        auth_cases.append({
            "device_priv": "22" * 32, "device_pub": DEVICE_PUB,
            "nonce": nonce, "body_hex": body.hex(),
            "sig": DEVICE_PRIV.sign(body).hex(),
        })

    cert_frac = _make_cert("vec-device", ENROLLED_AT)
    cert_int = _make_cert("vec-device", ENROLLED_AT_INTEGRAL)
    tampered = dict(cert_frac.to_dict(), device_name="evil")
    cert_cases = [
        {"cert": cert_frac.to_dict(), "body_hex": cert_frac.body().hex(),
         "valid": True},
        {"cert": cert_int.to_dict(), "body_hex": cert_int.body().hex(),
         "valid": True},
        {"cert": tampered, "body_hex": None, "valid": False},
    ]

    frame_cases = []
    for obj in [{"t": "hello", "nonce": "00ff"},
                {"t": "auth", "sig": "ab" * 64},
                {"t": "refused"}]:
        data = json.dumps(obj, separators=(",", ":")).encode()
        frame_cases.append({
            "obj": obj, "frame_hex": (struct.pack(">I", len(data)) + data).hex(),
        })

    return {
        "canonical_cases": canonical_cases,
        "auth_cases": auth_cases,
        "cert_cases": cert_cases,
        "frame_cases": frame_cases,
    }


def main():
    FIXTURE_PATH.parent.mkdir(parents=True, exist_ok=True)
    FIXTURE_PATH.write_text(
        json.dumps(build_vectors(), indent=2, sort_keys=True) + "\n",
        encoding="utf-8")
    print("wrote", FIXTURE_PATH)


if __name__ == "__main__":
    main()
