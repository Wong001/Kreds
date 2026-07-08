"""Real-Tor end-to-end. Skipped unless TOR_E2E=1 (needs network + tor.exe,
~1 minute). Ports the spike's money test into the suite."""
import asyncio
import os

import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("TOR_E2E") != "1",
    reason="set TOR_E2E=1 to run real-Tor integration (slow, network)")


def test_two_nodes_gossip_over_onions(tmp_path):
    from hearth.node import HearthNode
    from hearth.sync import SyncService
    from hearth.transport import TorTransport
    from hearth.tor import TorProcess, ensure_tor_binary, publish_onion

    async def scenario():
        exe = ensure_tor_binary()
        tor = TorProcess(exe, tmp_path / "tordata")
        sw = sf = None
        try:
            await tor.start()              # inside try: a failed/slow start
                                           # is still torn down by the finally
            wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
            freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
            wong.store.add_identity(freja.identity_pub)
            freja.store.add_identity(wong.identity_pub)
            sw = SyncService(wong, transport=TorTransport(tor.socks_port))
            sf = SyncService(freja, transport=TorTransport(tor.socks_port))
            wp = await sw.start("127.0.0.1", 0)
            fp = await sf.start("127.0.0.1", 0)
            wid, wblob = await publish_onion(tor.control_port,
                                             tor.cookie_path, wp, None)
            fid, fblob = await publish_onion(tor.control_port,
                                             tor.cookie_path, fp, None)
            wong.save_onion_key(wblob); freja.save_onion_key(fblob)
            faddr = f"{fid}.onion:{fp}"
            wong.compose_post("hej over tor")
            # onion may take up to ~a minute to become reachable
            for _ in range(30):
                if await sw.sync_with(faddr):
                    if [p["text"] for p in freja.feed()] == ["hej over tor"]:
                        break
                await asyncio.sleep(3)
            else:
                raise AssertionError("post did not arrive over onion")
        finally:
            if sw is not None:
                await sw.stop()
            if sf is not None:
                await sf.stop()
            await tor.stop()
    asyncio.run(scenario())
