"""Tests for post placement ("journal" default | "profile"): feed() is
journal-only, posts_by() filters by placement, and profile_view() returns
wall (profile) + journal instead of a single posts list. Reuses the shipped
scoped-posts machinery unchanged -- placement is authored content, not part
of the audience/AAD.

Local helpers match the two idioms already in the suite: the synchronous
befriend_with_enckeys() from tests/test_node_scoped_posts.py for
same-process assertions, and the real SyncService started()/sync_with()
idiom from tests/test_scoped_posts_e2e.py for the over-the-wire scoped-
encryption assertion (no `two_friends`/`single_node` pytest fixtures exist
in this repo -- the brief's placeholders are replaced with these)."""
import asyncio

import pytest

from hearth.node import HearthNode
from hearth.sync import SyncService


def befriend_with_enckeys(a, b):
    a.store.add_identity(b.identity_pub); b.store.add_identity(a.identity_pub)
    a.ensure_enckey(); b.ensure_enckey()
    for src, dst in ((a, b), (b, a)):
        for m in src.store.messages_not_in({}, {src.identity_pub}, dst.identity_pub):
            dst.store.ingest_message(m)


async def started(node):
    svc = SyncService(node)
    port = await svc.start("127.0.0.1", 0)
    node.store.set_meta("gossip_addr", f"127.0.0.1:{port}")
    return svc, f"127.0.0.1:{port}"


def test_profile_post_on_wall_not_feed(tmp_path):
    a = HearthNode.create(tmp_path / "a", "Wong", "wong-phone")
    b = HearthNode.create(tmp_path / "b", "Freja", "freja-phone")
    befriend_with_enckeys(a, b)                      # mutual friends, enckeys exchanged
    jid = a.compose_post("journal hi", scope="kreds", placement="journal")
    pid = a.compose_post("profile hi", scope="kreds", placement="profile")
    feed_ids = {r["msg_id"] for r in a.feed()}
    assert jid in feed_ids and pid not in feed_ids          # feed = journal only
    wall_ids = {r["msg_id"] for r in a.posts_by(a.identity_pub, "profile")}
    jrnl_ids = {r["msg_id"] for r in a.posts_by(a.identity_pub, "journal")}
    assert pid in wall_ids and jid not in wall_ids
    assert jid in jrnl_ids and pid not in jrnl_ids


def test_profile_post_still_scoped_encrypted(tmp_path):
    # a has friend b in 'kreds' only (default ring, never promoted to inner);
    # an inner-scoped profile post is invisible to b.
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Wong", "wong-phone")
        b = HearthNode.create(tmp_path / "b", "Freja", "freja-phone")
        a.store.add_identity(b.identity_pub); b.store.add_identity(a.identity_pub)
        a.ensure_enckey(); b.ensure_enckey()
        sa, aa = await started(a); sb, ba = await started(b)
        await sa.sync_with(ba)                             # exchange enckeys
        pid = a.compose_post("inner-only wall", scope="inner", placement="profile")
        # sync a->b, then b's view of a's wall must not contain pid (can't decrypt)
        await sa.sync_with(ba)
        assert pid not in {r["msg_id"] for r in b.posts_by(a.identity_pub, "profile")}
        for s in (sa, sb):
            await s.stop()
    asyncio.run(scenario())


def test_placement_validation():
    from hearth.messages import validate_payload, KIND_POST
    base = {"kind": KIND_POST, "scope": "kreds", "created_at": 1.0,
            "body_nonce": "0"*24, "body_ct": "ab", "wraps": {}, "blobs": []}
    ok, _ = validate_payload({**base, "placement": "profile"}); assert ok
    ok, _ = validate_payload({**base, "placement": "journal"}); assert ok
    ok, why = validate_payload({**base, "placement": "wat"}); assert not ok
    ok, _ = validate_payload(base); assert ok            # missing => journal, valid


def test_compose_post_rejects_bad_placement(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    with pytest.raises(ValueError):
        n.compose_post("x", placement="wat")
