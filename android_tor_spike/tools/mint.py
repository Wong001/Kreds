"""Mint a phone device fixture: a REAL identity-signed EnrollmentCert for
a freshly generated device keypair. Pairing crypto real, transport
stubbed (the fixture travels by adb push, not the pairing ceremony)."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))  # repo root

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from hearth.identity import priv_hex, pub_hex


def mint_fixture(node, device_name: str = "spike-phone") -> dict:
    priv = Ed25519PrivateKey.generate()
    device_pub = pub_hex(priv.public_key())
    cert = node.device.enroll_other(device_pub, device_name)
    return {
        "device_priv": priv_hex(priv),
        "device_pub": device_pub,
        "cert": cert.to_dict(),
        "onion_addr": node.store.get_meta("gossip_addr"),
    }
