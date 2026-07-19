"""Dev-only helper: mint the spike phone's {device_priv, cert, onion}
fixture from the REAL desktop node profile. NOT shipped in any client.

Run with the desktop Kreds app CLOSED (single sqlite writer), from the
repo root:

    .venv\\Scripts\\python.exe android_tor_spike\\tools\\mint_phone_fixture.py

Safety properties (spec 2026-07-19): the identity private key never
leaves this machine -- the phone receives only a fresh device keypair +
an identity-SIGNED cert. The cert is never published to the node's
store; AUTH succeeds via the own-identity path regardless (proven by
tests/test_handshake_desk.py). Cleanup = delete the fixture file from
the phone and this repo. node.revoke_device(device_pub) remains
available as belt-and-braces."""
import argparse
import getpass
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))  # repo root

from hearth.node import HearthNode

from mint import mint_fixture

DEFAULT_DATA_DIR = Path(os.environ.get("APPDATA", str(Path.home()))) / "Kreds"
DEFAULT_OUT = Path(__file__).resolve().parents[1] / "spike_phone_fixture.json"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--data-dir", type=Path, default=DEFAULT_DATA_DIR)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--device-name", default="spike-phone")
    args = ap.parse_args()

    node = HearthNode(args.data_dir)
    if node.applock_enabled:
        node.unlock(getpass.getpass("app-lock credential: "))
    fx = mint_fixture(node, args.device_name)
    if not fx["onion_addr"] or ".onion" not in fx["onion_addr"]:
        print("ERROR: no gossip_addr onion in this profile -- run the "
              "desktop app once with Tor so the onion is published, "
              "then retry (got: %r)" % (fx["onion_addr"],))
        return 1

    args.out.write_text(json.dumps(fx, indent=2))
    print("wrote", args.out)
    print("device_pub:", fx["device_pub"])
    print("onion:", fx["onion_addr"])
    print("REMINDER: this file holds the phone's device private key. "
          "It is gitignored; do not commit or share it.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
