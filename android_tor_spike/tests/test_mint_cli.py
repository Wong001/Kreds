"""The CLI is exercised against a THROWAWAY node profile only -- never
the real %APPDATA%/Kreds (Global Constraints)."""
import json
import subprocess
import sys
from pathlib import Path

from hearth.identity import EnrollmentCert
from hearth.node import HearthNode

CLI = Path(__file__).resolve().parents[1] / "tools" / "mint_phone_fixture.py"


def test_cli_mints_valid_fixture(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Desk", "desk")
    node.store.set_meta("gossip_addr", "abcdefexample.onion:9997")
    del node   # release the sqlite handle before the CLI reopens it

    out = tmp_path / "fixture.json"
    r = subprocess.run(
        [sys.executable, str(CLI), "--data-dir", str(tmp_path / "n"),
         "--out", str(out)],
        capture_output=True, text=True, timeout=60)
    assert r.returncode == 0, r.stderr

    fx = json.loads(out.read_text())
    assert fx["onion_addr"] == "abcdefexample.onion:9997"
    cert = EnrollmentCert.from_dict(fx["cert"])
    assert cert.verify()
    assert cert.device_pub == fx["device_pub"]
    assert cert.device_name == "spike-phone"


def test_cli_refuses_without_onion_addr(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Desk", "desk")
    del node
    r = subprocess.run(
        [sys.executable, str(CLI), "--data-dir", str(tmp_path / "n"),
         "--out", str(tmp_path / "fixture.json")],
        capture_output=True, text=True, timeout=60)
    assert r.returncode != 0
    assert "no gossip_addr" in (r.stdout + r.stderr)
