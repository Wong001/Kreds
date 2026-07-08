"""The forward-secrecy story over real gossip sockets.

Proves the spec's success criteria: an envelope wrapped to a rotated key
still decrypts inside grace (retired key), history survives pruning via
the local key cache, rotation propagates to the sender's wraps, and --
THE FS assertion -- a keys.json-only leak after pruning cannot decrypt a
pre-rotation envelope."""
import asyncio
import json

from hearth.dmcrypt import dm_aad, unwrap_key
from hearth.identity import DeviceKeys
from hearth.node import HearthNode
from hearth.sync import SyncService


def befriend(a, b):
    a.store.add_identity(b.identity_pub)
    b.store.add_identity(a.identity_pub)


async def started(node):
    svc = SyncService(node)
    port = await svc.start("127.0.0.1", 0)
    node.store.set_meta("gossip_addr", f"127.0.0.1:{port}")
    return svc, f"127.0.0.1:{port}"


def test_rotation_grace_cache_and_leak_story(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        befriend(wong, freja)
        wong.ensure_enckey()
        freja.ensure_enckey()
        sw, wa = await started(wong)
        sf, fa = await started(freja)
        await sw.sync_with(fa)                     # exchange enckeys
        gen1 = freja.device.enc_pub
        # DM 1 lands before any rotation
        mid1 = wong.compose_dm(freja.identity_pub, "foer rotation")
        await sw.sync_with(fa)
        # freja rotates BEFORE reading: envelope must open via the
        # retired key (grace), and the content key gets cached
        freja.device.rotate_enc()
        freja.ensure_enckey()                      # publish the new key
        assert freja.dm_thread(wong.identity_pub)[0]["text"] \
            == "foer rotation"
        assert freja.store.cached_message_key(mid1) is not None
        # rotation propagates: wong learns the NEW key over gossip
        before = wong.store.enckeys(freja.identity_pub)[
            freja.device.device_pub]
        await sw.sync_with(fa)
        after = wong.store.enckeys(freja.identity_pub)[
            freja.device.device_pub]
        assert before != after and after == freja.device.enc_pub
        # DM 2 wraps to the new key and reads fine
        mid2 = wong.compose_dm(freja.identity_pub, "efter rotation")
        await sw.sync_with(fa)
        texts = [t["text"] for t in freja.dm_thread(wong.identity_pub)]
        assert texts == ["foer rotation", "efter rotation"]
        # far-future maintenance: prunes gen-1 for good AND (same call, by
        # design) rotates again, since the rotation period has long elapsed
        gen2 = freja.device.enc_pub
        freja.maintain_enckey(now=4e9)
        retired_pubs = [r["enc_pub"] for r in freja.device.retired_enc]
        assert gen1 not in retired_pubs          # pruned for good
        assert gen2 in retired_pubs              # second rotation retired gen-2
        assert freja.device.enc_pub not in (gen1, gen2)   # fresh gen-3 current
        # history STILL displays -- the local key cache carries it
        texts = [t["text"] for t in freja.dm_thread(wong.identity_pub)]
        assert texts == ["foer rotation", "efter rotation"]
        # THE FS ASSERTION: keys.json alone (post-prune) cannot decrypt
        # the pre-rotation envelope
        leaked = DeviceKeys.from_json(json.loads(
            (tmp_path / "f" / "keys.json").read_text()))
        env = wong.store.get_message(mid1)         # captured envelope
        p = env.payload
        aad = dm_aad(env.cert.identity_pub, p["to"], p["created_at"])
        assert leaked.enc_privs()                  # keys exist, yet:
        assert all(unwrap_key(p["wraps"], leaked.device_pub, priv, aad)
                   is None for priv in leaked.enc_privs())
        for s in (sw, sf):
            await s.stop()
    asyncio.run(scenario())
