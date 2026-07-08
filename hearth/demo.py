"""Four-node demo cast, pre-wired: wong-phone + wong-homenode +
freja-phone + freja-homenode on fixed local ports. ASCII-only prints
(cp1252 console)."""
from __future__ import annotations

import asyncio
from pathlib import Path

from .node import HearthNode
from .runner import run_node
from .sync import SyncService
from .tor import TorProcess, ensure_tor_binary, publish_onion

CAST = [
    ("run/wong-phone", 7101, 7201),
    ("run/wong-homenode", 7102, 7202),
    ("run/freja-phone", 7103, 7203),
    ("run/freja-homenode", 7104, 7204),
]


async def build_cast():
    if Path(CAST[0][0]).exists() and (Path(CAST[0][0]) / "keys.json").exists():
        return False
    wong = HearthNode.create("run/wong-phone", "Wong", "wong-phone")
    wong.store.set_meta("gossip_addr", "127.0.0.1:7101")
    req = HearthNode.pair_request("run/wong-homenode", "wong-homenode")
    whome = HearthNode.pair_install("run/wong-homenode",
                                    wong.accept_pairing(req))
    whome.store.set_meta("gossip_addr", "127.0.0.1:7102")
    whome.store.add_peer("127.0.0.1:7101", wong.identity_pub)

    freja = HearthNode.create("run/freja-phone", "Freja", "freja-phone")
    freja.store.set_meta("gossip_addr", "127.0.0.1:7103")
    req2 = HearthNode.pair_request("run/freja-homenode", "freja-homenode")
    fhome = HearthNode.pair_install("run/freja-homenode",
                                    freja.accept_pairing(req2))
    fhome.store.set_meta("gossip_addr", "127.0.0.1:7104")
    fhome.store.add_peer("127.0.0.1:7103", freja.identity_pub)

    final = wong.finalize_invite(
        freja.respond_to_invite(wong.create_invite()))
    freja.complete_invite(final)
    # Freja's home node learns Wong via her friend list on next gossip; seed
    # the peer address so it can catch up.
    fhome.store.add_peer("127.0.0.1:7101", wong.identity_pub)
    fhome.store.add_identity(wong.identity_pub)
    whome.store.add_peer("127.0.0.1:7103", freja.identity_pub)

    wong.set_profile("Wong", bio="Designer i Kobenhavn.", accent="#2743d6",
                     avatar_shape="squircle", avatar_align="center")
    freja.set_profile("Freja", bio="Keramiker og fotograf.", accent="#c0563b",
                      avatar_shape="circle", avatar_align="left")

    import io as _io
    from PIL import Image as _Image
    _buf = _io.BytesIO()
    _Image.new("RGB", (720, 1080), (192, 86, 59)).save(_buf, format="PNG")
    freja.compose_story(_buf.getvalue(), caption="aftenlys")

    for n in (wong, whome, freja, fhome):
        n.ensure_enckey()

    # Each node only knows its OWN enc key until a gossip round happens.
    # Run one now (same SyncService the daemons use) so Wong's store holds
    # Freja's enc key before we seed the DM below.
    wsync = SyncService(wong)
    await wsync.start("127.0.0.1", 0)
    fsync = SyncService(freja)
    fport = await fsync.start("127.0.0.1", 0)
    await wsync.sync_with(f"127.0.0.1:{fport}")
    await wsync.stop()
    await fsync.stop()

    freja.compose_post("Hej Wong! Fint at vaere her.", scope="kreds")
    wong.compose_post("Velkommen til Kreds, Freja.", scope="kreds")
    # NOTE: only wong<->freja exchange enckeys here (the sync round above),
    # so the seeded posts' and DM's wraps cover only their phones; the home
    # nodes relay the ciphertext but cannot decrypt these particular seeded
    # messages. The e2e test exercises the full home-node-decrypts path
    # separately.
    wong.compose_dm(freja.identity_pub, "Og en privat en til dig :)")
    for n in (wong, whome, freja, fhome):
        n.close()
    return True


async def _seed_onions(shared):
    """Publish a DETACHED onion per cast node (one per DEVICE, mapped to its
    fixed gossip port) and rewrite each node's build-time 127.0.0.1 peer rows
    to the matching device's .onion. Keyed by the old TCP address -- which is
    per-device -- rather than identity_pub, so a person's phone and home node
    (which SHARE an identity) get distinct onion routes instead of collapsing
    onto one. Without this rewrite the cast would gossip over plain TCP and
    never exercise Tor."""
    addr_map = {}                       # "127.0.0.1:<gp>" -> "<sid>.onion:<gp>"
    for data_dir, gp, _ in CAST:
        n = HearthNode(data_dir)
        sid, blob = await publish_onion(shared.control_port,
                                        shared.cookie_path, gp, n.onion_key)
        if blob and blob != n.onion_key:
            n.save_onion_key(blob)
        onion = f"{sid}.onion:{gp}"
        addr_map[f"127.0.0.1:{gp}"] = onion
        n.store.set_meta("gossip_addr", onion)
        n.close()
    for data_dir, _, _ in CAST:
        n = HearthNode(data_dir)
        for peer in n.store.list_peers():
            old = peer["address"]
            if old in addr_map:
                n.store.remove_peer(old)
                n.store.add_peer(addr_map[old], peer["identity_pub"])
        n.close()


async def demo(tor: bool = False):
    fresh = await build_cast()
    print("Kreds demo - four nodes on this machine"
          + (" (over Tor)" if tor else ""))
    print("  (cast was %s)" % ("just created" if fresh else "reused"))
    print()
    for label, hp in (("Wong's phone", 7201), ("Wong's home node", 7202),
                      ("Freja's phone", 7203), ("Freja's home node", 7204)):
        print("  %-18s http://127.0.0.1:%d" % (label, hp))
    print()
    if tor:
        print("Tor mode: first sync between nodes can take ~a minute while")
        print("onion services publish. Data lives in run/.")
        exe = ensure_tor_binary()
        shared = TorProcess(exe, Path("run/tordata"))
        try:
            await shared.start()           # inside try: a failed/slow start
                                           # must not orphan tor.exe (it locks
                                           # the persistent run/tordata dir)
            await _seed_onions(shared)
            # publish=False: _seed_onions already published (detached) and set
            # each node's gossip_addr; run_node just binds the matching port.
            await asyncio.gather(*(run_node(d, gp, hp, interval=2.0,
                                            tor=True, tor_process=shared,
                                            publish=False)
                                   for d, gp, hp in CAST))
        finally:
            await shared.stop()
    else:
        print("Post something - it appears on the other side within seconds.")
        print("Ctrl+C stops all four nodes. Data lives in run/.")
        await asyncio.gather(*(run_node(d, gp, hp, interval=2.0)
                               for d, gp, hp in CAST))
