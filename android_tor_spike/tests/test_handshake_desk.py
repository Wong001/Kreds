"""Gate: the TS handshake completes real HELLO/AUTH against the REAL
python node (SyncService._session) over loopback TCP -- isolating any
future on-phone failure to the Tor/Android layers.

Also settles the spec's open un-enroll question: the phone cert below is
NEVER published to the node's store, and AUTH still succeeds via the
own-identity path (sync.py:472 is_known(own identity) is true; :479
skips the revocation check for own-identity peers). The fixture is a
pure local artifact; deleting it is the cleanup."""
import asyncio
import json
import shutil
import time
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from hearth.identity import EnrollmentCert, PROTOCOL, canonical, priv_hex, pub_hex
from hearth.node import HearthNode
from hearth.sync import SyncService

import mint

APP_DIR = Path(__file__).resolve().parents[1] / "app"


def _npx() -> str:
    npx = shutil.which("npx") or shutil.which("npx.cmd")
    assert npx, "npx not on PATH"
    return npx


async def _run_cli(fixture_path: Path) -> str:
    proc = await asyncio.create_subprocess_exec(
        _npx(), "tsx", "tools/handshake_cli.ts", str(fixture_path),
        cwd=APP_DIR, stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE)
    out, err = await asyncio.wait_for(proc.communicate(), timeout=120)
    return out.decode(errors="replace") + err.decode(errors="replace")


def test_real_device_cert_is_accepted(tmp_path):
    async def main():
        node = HearthNode.create(tmp_path / "n", "Desk", "desk")
        sync = SyncService(node)
        port = await sync.start("127.0.0.1", 0)
        try:
            fx = mint.mint_fixture(node)
            fx["onion_addr"] = f"127.0.0.1:{port}"
            p = tmp_path / "fixture.json"
            p.write_text(json.dumps(fx))
            output = await _run_cli(p)
            assert "RESULT accepted" in output, output
        finally:
            await sync.stop()
    asyncio.run(main())


def test_foreign_identity_cert_is_refused(tmp_path):
    async def main():
        node = HearthNode.create(tmp_path / "n", "Desk", "desk")
        sync = SyncService(node)
        port = await sync.start("127.0.0.1", 0)
        try:
            # a cryptographically VALID cert from an identity the node has
            # never heard of: passes cert verify + AUTH, then is refused
            # at the is_known gate -- proving the acceptance probe can
            # tell refusal from success.
            fid = Ed25519PrivateKey.generate()
            dev = Ed25519PrivateKey.generate()
            fpub, dpub = pub_hex(fid.public_key()), pub_hex(dev.public_key())
            ts = time.time()
            body = canonical({
                "type": "enrollment", "protocol": PROTOCOL,
                "identity_pub": fpub, "device_pub": dpub,
                "device_name": "stranger", "enrolled_at": ts})
            cert = EnrollmentCert(fpub, dpub, "stranger", ts,
                                  fid.sign(body).hex())
            fx = {"device_priv": priv_hex(dev), "device_pub": dpub,
                  "cert": cert.to_dict(),
                  "onion_addr": f"127.0.0.1:{port}"}
            p = tmp_path / "fixture.json"
            p.write_text(json.dumps(fx))
            output = await _run_cli(p)
            assert "RESULT refused" in output, output
        finally:
            await sync.stop()
    asyncio.run(main())
