"""Gate: bytes produced by the TS wire layer verify under the REAL hearth
implementation (the reverse direction of the committed vectors)."""
import json
import os
import shutil
import subprocess
from pathlib import Path

from hearth.identity import EnrollmentCert, pub_from_hex
from hearth.node import HearthNode
from hearth.sync import _auth_body

import mint

APP_DIR = Path(__file__).resolve().parents[1] / "app"


def _npx() -> str:
    npx = shutil.which("npx") or shutil.which("npx.cmd")
    assert npx, "npx not on PATH"
    return npx


def test_ts_output_verifies_in_python(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Desk", "desk")
    fx = mint.mint_fixture(node)
    nonce = os.urandom(16).hex()
    inp, outp = tmp_path / "in.json", tmp_path / "out.json"
    inp.write_text(json.dumps(
        {"cert": fx["cert"], "device_priv": fx["device_priv"], "nonce": nonce}))

    r = subprocess.run(
        [_npx(), "tsx", "tools/roundtrip_cli.ts", str(inp), str(outp)],
        cwd=APP_DIR, capture_output=True, text=True, timeout=120)
    assert r.returncode == 0, r.stderr

    out = json.loads(outp.read_text())
    # TS verified the python-minted cert
    assert out["cert_verifies"] is True
    # TS canonical auth body == python's, and the TS signature verifies
    # under the real hearth device key
    assert bytes.fromhex(out["auth_body_hex"]) == _auth_body(nonce)
    pub_from_hex(fx["device_pub"]).verify(
        bytes.fromhex(out["sig"]), _auth_body(nonce))   # raises on mismatch
    # the TS re-serialized cert still verifies after a python json.loads
    # round trip -- this is exactly the HELLO path the node will exercise
    assert EnrollmentCert.from_dict(json.loads(out["cert_json"])).verify()
