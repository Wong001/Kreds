# Wall Wrap-Grants Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** "A wall is a wall" — kreds-scope wall (profile-placement) posts become visible to CURRENT friends, not friends-at-post-time, via a new author-signed `wrap_grant` record; journal and inner-scope posts keep at-post-time audiences exactly as shipped.

**Architecture:** A new record kind `KIND_WRAP_GRANT` (`{kind, target: <post msg_id>, wraps, created_at}`) carries extra sealed content-key wraps for an existing post. An author-side sweep (`maintain_wrap_grants`, same hooks as `maintain_enckey`) mints grants for any current friend's device not yet covered. The store's routing gate, negative cache, and the node's `_content_key` all union grant wraps with payload wraps — **only for grants signed by the post's own author** (a hostile friend's grant naming someone else's post must neither widen routing nor feed key resolution).

**Tech Stack:** Python 3.12, sqlite3 (existing store schema — grants reuse the `target_id` column deletes already use), ChaCha20Poly1305/X25519 via existing `dmcrypt` helpers, pytest.

**Spec:** `docs/superpowers/specs/2026-07-15-wall-wrap-grants-design.md` (approved; PROTOCOL CHANGE: new record kind).

## Global Constraints

- Suite green after every task: `.venv\Scripts\python.exe -m pytest -q` (baseline at dispatch: 841 passed, 6 skipped).
- NO AI/Co-Authored-By commit trailers; ASCII-only console prints (cp1252).
- Grants cover ONLY the author's own posts with `placement == "profile"` AND `scope == "kreds"`. Journal posts and inner-scope posts (including inner wall posts) are untouched — several existing tests pin this and MUST stay green unmodified where noted.
- Do NOT touch `dmcrypt.PROTOCOL` (it binds every existing AAD; the "protocol change" is the new record kind, nothing else).
- Grant wraps are sealed with the TARGET POST's AAD: `post_aad(author_identity, post.scope, post.created_at)` — the recipient recomputes it from the post row it holds.
- Mixed-version: old (≤0.3.10) peers refuse `wrap_grant` at ingest with `"unknown kind"` (verified: `messages.validate_payload` falls through cleanly; `sync._session`'s GIVE loop only checks `res.accepted`). The refused seq never enters their seen-set, so grants are re-offered to old peers every round — bounded, accepted for the single-release window (same acceptance shape as 0.3.11's 5→10 MB blob raise). Documented in Task 6, not "fixed".

## Verified codebase facts (planning-time, so implementers don't re-derive)

- `store._lock` is an `RLock` — store methods may call other store methods.
- `store._tombstone(mid, reason)` deletes the message row and inserts into `tombstones`; tombstoned rows are never offered again (`ingest_message` also refuses tombstoned msg_ids).
- `messages` table columns: `msg_id, identity_pub, device_pub, seq, kind, target_id, recipient, msg_json, created_at, expires_at`. `target_id` currently populated only for `KIND_DELETE` (store.py:277-278).
- `dmcrypt.unwrap_key(wraps, device_pub, priv_hex, aad)` reads only `eph_pub`/`nonce`/`wrapped_key` per entry — extra fields (our `enc_pub` annotation) are ignored. `messages._valid_wraps` likewise checks required fields only and tolerates extras.
- `node._content_key(msg)` resolution order today: local sealed cache → payload-wrap unwrap (current then retired enc privs). Returns `(key_or_None, aad)`.
- `node._publish(msg)` ingests own message, saves keys, notifies; raises on rejection.
- Hooks: `node.maintain_enckey()` is called at `api.py:72` (app build) and `sync.py:229` (`_gossip_round`, every round).
- `store.post_messages(identity)` returns non-tombstoned `SignedMessage`s of `KIND_POST` for that author (newest first).
- `store.enckeys(identity)` → `{device_pub: enc_pub}` for non-revoked devices, latest-wins.
- `store.known_identities()` includes self; "current friends" = `known_identities() - {self}` (defriend purge removes identities, so this IS current).
- `is_expired(payload, now)` in `messages.py`.
- Test conventions: store/node-level tests build nodes with `HearthNode.create(...)` and hand-carry messages via `store.messages_not_in({}, {author}, peer)` → `peer.store.ingest_message(m)` (see `tests/test_node_scoped_posts.py`'s `befriend_with_enckeys`); e2e over real sockets uses `SyncService` + `started()`/`befriend()` helpers (see `tests/test_scoped_posts_e2e.py`).

---

### Task 1: Record kind + validation (`hearth/messages.py`)

**Files:**
- Modify: `hearth/messages.py` (constants block ~line 18, a `make_wrap_grant` next to `make_album` ~line 160, a validate branch before the final `return False, "unknown kind"` ~line 389)
- Test: `tests/test_wrap_grant_messages.py` (create)

**Interfaces:**
- Produces: `KIND_WRAP_GRANT = "wrap_grant"`; `make_wrap_grant(device, target_msg_id: str, wraps: dict, now=None) -> SignedMessage` signing payload `{"kind": KIND_WRAP_GRANT, "target": target_msg_id, "wraps": wraps, "created_at": ...}`; `validate_payload` accepts it (target hex64, wraps pass `_valid_wraps`, wraps non-empty, optional per-entry `enc_pub` hex64 if present).

- [ ] **Step 1: Write the failing tests**

```python
"""wrap_grant record kind (spec 2026-07-15-wall-wrap-grants): an
author-signed bundle of extra sealed content-key wraps for an existing
post. Shape-only validation here, like every other kind."""
from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import (KIND_WRAP_GRANT, make_wrap_grant,
                             validate_payload)


def _device():
    d = DeviceKeys.create("test-dev")
    IdentityCeremony().enroll_first_device(d)
    return d


def _wrap_entry(enc_pub=None):
    w = {"eph_pub": "ab" * 32, "nonce": "cd" * 12, "wrapped_key": "ef" * 48}
    if enc_pub is not None:
        w["enc_pub"] = enc_pub
    return w


def test_make_wrap_grant_shape_and_validation():
    d = _device()
    msg = make_wrap_grant(d, "aa" * 32, {"bb" * 32: _wrap_entry()})
    p = msg.payload
    assert p["kind"] == KIND_WRAP_GRANT
    assert p["target"] == "aa" * 32
    ok, why = validate_payload(p)
    assert ok, why


def test_wrap_grant_enc_pub_annotation_allowed():
    d = _device()
    msg = make_wrap_grant(d, "aa" * 32,
                          {"bb" * 32: _wrap_entry(enc_pub="11" * 32)})
    ok, why = validate_payload(msg.payload)
    assert ok, why


def test_wrap_grant_bad_shapes_refused():
    base = {"kind": KIND_WRAP_GRANT, "created_at": 1.0}
    good_wraps = {"bb" * 32: _wrap_entry()}
    assert not validate_payload({**base, "target": "zz",
                                 "wraps": good_wraps})[0]      # bad target
    assert not validate_payload({**base, "target": "aa" * 32,
                                 "wraps": {}})[0]              # empty wraps
    assert not validate_payload({**base, "target": "aa" * 32,
                                 "wraps": {"bb" * 32: {}}})[0]  # bad entry
    bad_enc = {"bb" * 32: _wrap_entry(enc_pub="not-hex")}
    assert not validate_payload({**base, "target": "aa" * 32,
                                 "wraps": bad_enc})[0]         # bad enc_pub


def test_unknown_kind_still_refused():
    # the fallthrough old peers rely on must survive this branch addition
    ok, why = validate_payload({"kind": "nonsense", "created_at": 1.0})
    assert not ok and why == "unknown kind"
```

(If `DeviceKeys.create`/`IdentityCeremony` names differ from the existing message-kind tests, copy the device-construction idiom from `tests/test_messages.py` instead — the assertion content stays the same.)

- [ ] **Step 2: Run to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_wrap_grant_messages.py -q`
Expected: FAIL — `ImportError: cannot import name 'KIND_WRAP_GRANT'`

- [ ] **Step 3: Implement**

In the constants block (after `KIND_ALBUM`):

```python
KIND_WRAP_GRANT = "wrap_grant"
```

Next to `make_album`:

```python
def make_wrap_grant(device: DeviceKeys, target_msg_id: str, wraps: dict,
                    now: Optional[float] = None) -> SignedMessage:
    """Extra sealed content-key wraps for an EXISTING post ("a wall is a
    wall", spec 2026-07-15): additive, deduplicable — multiple grants for
    one target union at the reader. Only meaningful when signed by the
    target post's own author; consumers enforce that, not this shape."""
    return device.sign_message({
        "kind": KIND_WRAP_GRANT, "target": target_msg_id, "wraps": wraps,
        "created_at": _now(now),
    })
```

In `validate_payload`, immediately before the final `return False, "unknown kind"`:

```python
    if kind == KIND_WRAP_GRANT:
        if not _is_hex64(p.get("target")):
            return False, "bad target"
        wraps = p.get("wraps")
        if not _valid_wraps(wraps) or not wraps:
            return False, "bad wraps"
        # optional per-entry annotation: which enc_pub the wrap was sealed
        # to, so the author-side sweep can detect stale wraps after the
        # recipient rotates (unwrap_key ignores the extra field)
        for w in wraps.values():
            ep = w.get("enc_pub")
            if ep is not None and not _is_hex64(ep):
                return False, "bad enc_pub"
        return True, "ok"
```

- [ ] **Step 4: Run the new tests, then the full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_wrap_grant_messages.py -q` then `.venv\Scripts\python.exe -m pytest -q`
Expected: all pass (the branch is additive; the unknown-kind fallthrough is pinned by the new test).

- [ ] **Step 5: Commit**

```bash
git add hearth/messages.py tests/test_wrap_grant_messages.py
git commit -m "feat(protocol): wrap_grant record kind - author-signed extra key wraps for an existing post ('a wall is a wall', 0.3.11)"
```

---

### Task 2: Store ingest, accessor, and grant GC (`hearth/store.py`)

**Files:**
- Modify: `hearth/store.py` — `ingest_message` (~253-336), `sweep_expired` (~360-371), new `wrap_grants` accessor near `enckey_records` (~515)
- Test: `tests/test_wrap_grants_store.py` (create)

**Interfaces:**
- Consumes: `KIND_WRAP_GRANT`, `make_wrap_grant` from Task 1 (import `KIND_WRAP_GRANT` in store.py alongside the existing KIND_ imports).
- Produces: `store.wrap_grants(target_msg_id: str, author_identity: str) -> dict` — `{device_pub: wrap_entry}` unioned over every grant BY THAT AUTHOR for that target, later mints overriding earlier per device. Ingest behavior: grants populate `target_id`; a grant whose target is already tombstoned is tombstoned on arrival (reason `"invalid"`, accepted result — consumes the seq so peers stop re-offering); accepted grants clear the target's negative-cache row. `KIND_DELETE` ingest tombstones held grants for the deleted target; `sweep_expired` tombstones grants of expired targets.

- [ ] **Step 1: Write the failing tests**

```python
"""Store-level wrap-grant behavior: ingest bookkeeping (target_id,
negative-cache clearing, tombstone interactions), the author-filtered
accessor, and grant GC on delete/expiry."""
import time

from hearth.messages import make_post, make_wrap_grant, make_delete
from hearth.node import HearthNode


def _wrap_entry():
    return {"eph_pub": "ab" * 32, "nonce": "cd" * 12,
            "wrapped_key": "ef" * 48}


def befriend_with_enckeys(a, b):
    a.store.add_identity(b.identity_pub); b.store.add_identity(a.identity_pub)
    a.ensure_enckey(); b.ensure_enckey()
    for src, dst in ((a, b), (b, a)):
        for m in src.store.messages_not_in({}, {src.identity_pub}, dst.identity_pub):
            dst.store.ingest_message(m)


def _wall_post(node, text="wall"):
    return node.compose_post(text, scope="kreds", placement="profile")


def test_wrap_grants_accessor_unions_and_filters_author(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    mid = _wall_post(wong)
    g1 = make_wrap_grant(wong.device, mid, {"11" * 32: _wrap_entry()})
    g2 = make_wrap_grant(wong.device, mid, {"22" * 32: _wrap_entry()})
    assert wong.store.ingest_message(g1).accepted
    assert wong.store.ingest_message(g2).accepted
    got = wong.store.wrap_grants(mid, wong.identity_pub)
    assert set(got) == {"11" * 32, "22" * 32}          # grants union
    # a DIFFERENT identity's grant for the same target is never returned
    # for the author query (author-filter is the security boundary)
    gf = make_wrap_grant(freja.device, mid, {"33" * 32: _wrap_entry()})
    assert wong.store.ingest_message(gf).accepted       # shape-valid, held
    assert "33" * 32 not in wong.store.wrap_grants(mid, wong.identity_pub)


def test_grant_ingest_clears_negative_cache_for_target(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    mid = _wall_post(wong)
    freja.store.mark_undecryptable(mid)                 # poisoned earlier
    g = make_wrap_grant(wong.device, mid, {"11" * 32: _wrap_entry()})
    assert freja.store.ingest_message(g).accepted
    assert mid not in freja.store.undecryptable_ids()   # un-poisoned


def test_grant_for_tombstoned_target_dies_on_arrival(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    mid = _wall_post(wong)
    g = make_wrap_grant(wong.device, mid, {"11" * 32: _wrap_entry()})
    d = make_delete(wong.device, mid)
    # order: delete lands at freja BEFORE the grant does
    for m in wong.store.messages_not_in({}, {wong.identity_pub}, freja.identity_pub):
        freja.store.ingest_message(m)
    assert freja.store.ingest_message(d).accepted
    res = freja.store.ingest_message(g)
    assert res.accepted                                 # seq consumed...
    assert freja.store.wrap_grants(mid, wong.identity_pub) == {}   # ...but dead
    assert freja.store.is_tombstoned(g.msg_id)          # stops re-offering


def test_delete_ingest_gcs_held_grants(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    mid = _wall_post(wong)
    g = make_wrap_grant(wong.device, mid, {"11" * 32: _wrap_entry()})
    for m in wong.store.messages_not_in({}, {wong.identity_pub}, freja.identity_pub):
        freja.store.ingest_message(m)
    assert freja.store.ingest_message(g).accepted
    assert freja.store.wrap_grants(mid, wong.identity_pub) != {}
    freja.store.ingest_message(make_delete(wong.device, mid))
    assert freja.store.wrap_grants(mid, wong.identity_pub) == {}
    assert freja.store.is_tombstoned(g.msg_id)


def test_sweep_expired_gcs_grants_of_expired_targets(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    mid = wong.compose_post("kort", scope="kreds", placement="profile",
                            expires_seconds=1)
    g = make_wrap_grant(wong.device, mid, {"11" * 32: _wrap_entry()})
    assert wong.store.ingest_message(g).accepted
    wong.store.sweep_expired(now=time.time() + 5)
    assert wong.store.wrap_grants(mid, wong.identity_pub) == {}
    assert wong.store.is_tombstoned(g.msg_id)
```

- [ ] **Step 2: Run to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_wrap_grants_store.py -q`
Expected: FAIL — `AttributeError: 'Store' object has no attribute 'wrap_grants'` (first test) and assertion failures on the GC tests.

- [ ] **Step 3: Implement**

Import: add `KIND_WRAP_GRANT` to store.py's existing `from .messages import ...` line.

In `ingest_message`, change the `target` population (store.py:277-278) to:

```python
            target = (msg.payload.get("target")
                      if kind in (KIND_DELETE, KIND_WRAP_GRANT) else None)
```

Immediately after the existing `if kind == KIND_DELETE:` block's tombstone work (i.e., after `deleted_target = target` inside it), extend the delete branch to GC held grants — inside the `if kind == KIND_DELETE:` block, after `deleted_target = target` is set (and also when the target row was absent — key off `target`, not `deleted_target`):

```python
                # "A wall is a wall" GC: a deleted post's grants must stop
                # gossiping too (mirror of the meta-delete hygiene below).
                # Keyed on the delete's target regardless of whether the
                # post row was present -- grants can be held even when the
                # post row never arrived or died earlier.
                for (g,) in self._db.execute(
                        "SELECT msg_id FROM messages WHERE kind=?"
                        " AND target_id=?",
                        (KIND_WRAP_GRANT, target)).fetchall():
                    self._tombstone(g, "invalid")
```

Before the `INSERT INTO messages` (after the delete-tag-gossiped-first block), add the grant-specific arrival checks:

```python
            if kind == KIND_WRAP_GRANT and self.is_tombstoned(target):
                # Target already deleted/expired. Views were saved above,
                # so the seq is consumed; tombstone the grant so peers
                # stop offering it (a bare refusal would leave a summary
                # gap and re-offers forever).
                self._tombstone(mid, "invalid")
                self._db.commit()
                return IngestResult(True, "grant for tombstoned target", mid)
```

After the `INSERT INTO messages` (next to the `if kind == KIND_DELETE:` meta-delete hygiene), un-poison the negative cache:

```python
            if kind == KIND_WRAP_GRANT:
                # Un-poison: the post row may have arrived first, failed to
                # decrypt, and been negative-cached -- this grant is exactly
                # the missing key material, so let the next sweep retry.
                self._db.execute("DELETE FROM undecryptable WHERE msg_id=?",
                                 (target,))
```

In `sweep_expired`, extend the tombstone loop:

```python
            for mid in swept:
                self._tombstone(mid, "expired")
                for (g,) in self._db.execute(
                        "SELECT msg_id FROM messages WHERE kind=?"
                        " AND target_id=?", (KIND_WRAP_GRANT, mid)).fetchall():
                    self._tombstone(g, "expired")
```

New accessor, next to `enckey_records`:

```python
    def wrap_grants(self, target_msg_id: str,
                    author_identity: str) -> dict:
        """device_pub -> wrap entry, unioned over every wrap-grant BY THAT
        AUTHOR for this target; later mints override earlier per device
        (a re-mint after enc-key rotation supersedes the stale wrap).
        Author-filtered on purpose: a grant is only honored when signed by
        the post's own author -- anyone else's 'grant' must neither widen
        routing nor feed key resolution (see messages_not_in and
        node._content_key)."""
        with self._lock:
            out: dict = {}
            for (mj,) in self._db.execute(
                    "SELECT msg_json FROM messages WHERE kind=?"
                    " AND target_id=? AND identity_pub=?"
                    " ORDER BY created_at ASC, seq ASC",
                    (KIND_WRAP_GRANT, target_msg_id, author_identity)):
                out.update(json.loads(mj)["payload"].get("wraps", {}))
            return out
```

- [ ] **Step 4: Run new tests + full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_wrap_grants_store.py -q` then `.venv\Scripts\python.exe -m pytest -q`
Expected: all pass. If an existing delete/expiry test asserts an exact tombstone count, check whether grants legitimately changed it (fix the test only if the new tombstones are the intended behavior change).

- [ ] **Step 5: Commit**

```bash
git add hearth/store.py tests/test_wrap_grants_store.py
git commit -m "feat(store): wrap_grant ingest + author-filtered accessor + GC - grants die with their target (delete, expiry, tombstone-first ordering), negative cache un-poisons on grant arrival"
```

---

### Task 3: Routing gate + negative-cache candidates (`hearth/store.py`)

**Files:**
- Modify: `hearth/store.py` — `messages_not_in` (~489-511), `uncached_message_ids` (~641-662)
- Test: `tests/test_wrap_grants_store.py` (append)

**Interfaces:**
- Consumes: `store.wrap_grants` from Task 2.
- Produces: `messages_not_in` offers a post to a peer when the peer's devices appear in payload wraps ∪ the AUTHOR's grant wraps for it; offers a grant row to `peer_identity == author` or peers whose devices the grant names. `uncached_message_ids` includes posts decryptable only via a grant.

- [ ] **Step 1: Write the failing tests** (append to `tests/test_wrap_grants_store.py`)

```python
def test_routing_gate_offers_granted_post(tmp_path):
    # Freja was NOT wrapped at post time; a grant naming her device must
    # open the offer gate for the post AND for the grant itself.
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mid = _wall_post(wong)                          # composed pre-friendship
    befriend_with_enckeys(wong, freja)
    offered = {m.msg_id for m in wong.store.messages_not_in(
        {}, {wong.identity_pub}, freja.identity_pub)}
    assert mid not in offered                       # not wrapped, no grant
    g = make_wrap_grant(wong.device, mid,
                        {freja.device.device_pub: _wrap_entry()})
    assert wong.store.ingest_message(g).accepted
    offered = {m.msg_id for m in wong.store.messages_not_in(
        {}, {wong.identity_pub}, freja.identity_pub)}
    assert mid in offered                           # grant opened the gate
    assert g.msg_id in offered                      # grant routes to named device


def test_routing_gate_ignores_non_author_grants(tmp_path):
    # A grant signed by someone OTHER than the post's author must not
    # widen the post's audience (hostile-friend containment).
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mikkel = HearthNode.create(tmp_path / "m", "Mikkel", "mikkel-phone")
    befriend_with_enckeys(wong, freja); befriend_with_enckeys(wong, mikkel)
    befriend_with_enckeys(freja, mikkel)
    wong.set_ring(freja.identity_pub, "inner")
    mid = wong.compose_post("kun inner", scope="inner", placement="profile")
    # Freja (holds the post) maliciously "grants" it to Mikkel
    g = make_wrap_grant(freja.device, mid,
                        {mikkel.device.device_pub: _wrap_entry()})
    for m in wong.store.messages_not_in({}, {wong.identity_pub}, freja.identity_pub):
        freja.store.ingest_message(m)
    assert freja.store.ingest_message(g).accepted
    offered = {m.msg_id for m in freja.store.messages_not_in(
        {}, {wong.identity_pub, freja.identity_pub}, mikkel.identity_pub)}
    assert mid not in offered              # her grant is inert for routing


def test_uncached_ids_include_grant_wrapped_posts(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mid = _wall_post(wong)
    befriend_with_enckeys(wong, freja)
    # freja holds the row but is not in payload wraps
    post = wong.store.get_message(mid)
    freja.store.ingest_message(post)
    assert mid not in freja.store.uncached_message_ids(freja.identity_pub)
    g = make_wrap_grant(wong.device, mid,
                        {freja.device.device_pub: _wrap_entry()})
    freja.store.ingest_message(g)
    assert mid in freja.store.uncached_message_ids(freja.identity_pub)
```

- [ ] **Step 2: Run to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_wrap_grants_store.py -q`
Expected: the three new tests FAIL (post not offered / mid absent from uncached ids); Task 2's tests still pass.

- [ ] **Step 3: Implement**

`messages_not_in`: add `msg_id` to the SELECT, pre-build an author-filtered grant index, and extend the per-kind gates:

```python
    def messages_not_in(self, summaries: dict, entitled: Set[str],
                        peer_identity: str) -> List[SignedMessage]:
        with self._lock:
            out = []
            peer_devices = set(self.load_views(peer_identity).keys())
            # (author, target) -> devices named by the AUTHOR's own grants.
            # Author-keyed so a hostile friend's grant for someone else's
            # post can never widen that post's audience.
            grant_devs: Dict[tuple, set] = {}
            for gipub, gtarget, gmj in self._db.execute(
                    "SELECT identity_pub, target_id, msg_json FROM messages"
                    " WHERE kind=?", (KIND_WRAP_GRANT,)):
                grant_devs.setdefault((gipub, gtarget), set()).update(
                    json.loads(gmj)["payload"].get("wraps", {}))
            for mid, ipub, dpub, seq, kind, rcpt, mj in self._db.execute(
                    "SELECT msg_id, identity_pub, device_pub, seq, kind,"
                    " recipient, msg_json FROM messages ORDER BY seq ASC"):
                if ipub not in entitled:
                    continue
                if kind == KIND_DM and peer_identity not in (ipub, rcpt):
                    continue          # DMs never relay through friends
                if kind == KIND_RING and peer_identity != ipub:
                    continue          # ring records are author-private
                if kind == KIND_POST:
                    wr = set(json.loads(mj)["payload"].get("wraps", {}))
                    wr |= grant_devs.get((ipub, mid), set())
                    if peer_identity != ipub and not (peer_devices & wr):
                        continue      # wrap-set union grant audience + author
                if kind == KIND_WRAP_GRANT:
                    wr = set(json.loads(mj)["payload"].get("wraps", {}))
                    if peer_identity != ipub and not (peer_devices & wr):
                        continue      # grants route to named devices + author
                dev = summaries.get(ipub, {}).get(dpub)
                if dev is not None and SeenSet.summary_has(dev, seq):
                    continue
                out.append(SignedMessage.from_dict(json.loads(mj)))
            return out
```

(`Dict` is already imported in store.py. Keep the whole method body as shown — the change is: `msg_id` added to the SELECT, the `grant_devs` pre-pass, the `wr |= ...` line, and the `KIND_WRAP_GRANT` gate.)

`uncached_message_ids`: in the post branch, union grant wraps (author-filtered via `wrap_grants` — the RLock is reentrant):

```python
                else:                                    # post
                    if ipub == self_identity:
                        out.append(mid); continue
                    my_devs = set(self.load_views(self_identity).keys())
                    wrapped = set(json.loads(mj)["payload"].get("wraps", {}))
                    if not (my_devs & wrapped):
                        # not in the at-post-time audience -- but a wall
                        # wrap-grant from the author may cover us
                        wrapped |= set(self.wrap_grants(mid, ipub))
                    if my_devs & wrapped:
                        out.append(mid)
```

- [ ] **Step 4: Run new tests + full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_wrap_grants_store.py -q` then `.venv\Scripts\python.exe -m pytest -q`
Expected: all pass. `tests/test_scoped_posts_e2e.py` and `tests/test_sync_dm.py` MUST be untouched and green (inner/DM routing unchanged).

- [ ] **Step 5: Commit**

```bash
git add hearth/store.py tests/test_wrap_grants_store.py
git commit -m "feat(store): routing gate + negative-cache candidates honor author-signed wrap grants - grant audience unions payload wraps; non-author grants stay inert"
```

---

### Task 4: Node key resolution + author sweep + hooks (`hearth/node.py`, `hearth/sync.py`, `hearth/api.py`)

**Files:**
- Modify: `hearth/node.py` — `_content_key` (~1639-1664), new `maintain_wrap_grants` next to `maintain_enckey` (~1571); add `make_wrap_grant` and `KIND_WRAP_GRANT` to the existing `from .messages import ...`
- Modify: `hearth/sync.py:229` (`_gossip_round`), `hearth/api.py:72`
- Test: `tests/test_wrap_grants_node.py` (create)

**Interfaces:**
- Consumes: `make_wrap_grant` (Task 1), `store.wrap_grants` (Task 2).
- Produces: `node.maintain_wrap_grants(now=None)` — author-side sweep minting grants for uncovered current-friend devices on own kreds wall posts; `_content_key` resolves keys from author grants as a third step. Hook sites call it right after `maintain_enckey()`.

- [ ] **Step 1: Write the failing tests**

```python
"""Author-side wrap-grant sweep + grant-based key resolution ("a wall is
a wall", 0.3.11). Store-and-forward level (hand-carried messages);
socket-level proof lives in test_wrap_grants_e2e.py."""
from hearth.messages import KIND_WRAP_GRANT
from hearth.node import HearthNode


def befriend_with_enckeys(a, b):
    a.store.add_identity(b.identity_pub); b.store.add_identity(a.identity_pub)
    a.ensure_enckey(); b.ensure_enckey()
    for src, dst in ((a, b), (b, a)):
        for m in src.store.messages_not_in({}, {src.identity_pub}, dst.identity_pub):
            dst.store.ingest_message(m)


def _grants(node, mid):
    return node.store.wrap_grants(mid, node.identity_pub)


def test_sweep_grants_wall_back_catalog_to_new_friend(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mid = wong.compose_post("gammel veag", scope="kreds", placement="profile")
    befriend_with_enckeys(wong, freja)                # friendship AFTER post
    wong.maintain_wrap_grants()
    g = _grants(wong, mid)
    assert freja.device.device_pub in g
    # idempotent: a second sweep mints nothing new
    before = wong.store.messages_not_in({}, {wong.identity_pub}, freja.identity_pub)
    wong.maintain_wrap_grants()
    after = wong.store.messages_not_in({}, {wong.identity_pub}, freja.identity_pub)
    assert len(before) == len(after)


def test_sweep_skips_journal_inner_expired_and_tombstoned(tmp_path):
    import time
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    j = wong.compose_post("journal", scope="kreds")                 # journal
    i = wong.compose_post("inner wall", scope="inner", placement="profile")
    e = wong.compose_post("kort", scope="kreds", placement="profile",
                          expires_seconds=0.001)
    d = wong.compose_post("slettes", scope="kreds", placement="profile")
    wong.delete_post(d)                     # node.py:1161
    befriend_with_enckeys(wong, freja)
    time.sleep(0.01)                                  # e is now expired
    wong.maintain_wrap_grants()
    for mid in (j, i, e, d):
        assert _grants(wong, mid) == {}, mid


def test_recipient_decrypts_via_grant_and_feed_shows_post(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mid = wong.compose_post("bagkatalog", scope="kreds", placement="profile")
    befriend_with_enckeys(wong, freja)
    wong.maintain_wrap_grants()
    for m in wong.store.messages_not_in({}, {wong.identity_pub}, freja.identity_pub):
        freja.store.ingest_message(m)
    assert "bagkatalog" in [p["text"] for p in freja.feed()]
    wall = freja.profile_view(wong.identity_pub)["wall"]
    assert "bagkatalog" in [p["text"] for p in wall]


def test_sweep_remints_after_recipient_enckey_rotation(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mid = wong.compose_post("rotation", scope="kreds", placement="profile")
    befriend_with_enckeys(wong, freja)
    wong.maintain_wrap_grants()
    old_wrap = _grants(wong, mid)[freja.device.device_pub]
    # freja rotates; her new enckey record reaches wong
    freja.device.rotate_enc(__import__("time").time())
    freja.ensure_enckey()
    for m in freja.store.messages_not_in({}, {freja.identity_pub}, wong.identity_pub):
        wong.store.ingest_message(m)
    wong.maintain_wrap_grants()
    new_wrap = _grants(wong, mid)[freja.device.device_pub]
    assert new_wrap != old_wrap                      # re-minted, not stale
    assert new_wrap["enc_pub"] == wong.store.enckeys(
        freja.identity_pub)[freja.device.device_pub]


def test_sweep_noop_when_locked_or_no_friends(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    mid = wong.compose_post("alene", scope="kreds", placement="profile")
    wong.maintain_wrap_grants()                       # no friends: no-op
    assert _grants(wong, mid) == {}
```

(Verified at planning time: `node.delete_post(target_msg_id)` exists at node.py:1161; `profile_view` returns a `"wall"` key of decrypted post dicts at node.py:1155-1158.)

- [ ] **Step 2: Run to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_wrap_grants_node.py -q`
Expected: FAIL — `AttributeError: 'HearthNode' object has no attribute 'maintain_wrap_grants'`.

- [ ] **Step 3: Implement**

`node.py` — extend the messages import with `KIND_WRAP_GRANT, make_wrap_grant` (and `is_expired` if not already imported).

New method directly after `maintain_enckey`:

```python
    def maintain_wrap_grants(self, now: Optional[float] = None):
        """'A wall is a wall' (0.3.11): kreds-scope PROFILE posts are for
        CURRENT friends, not friends-at-post-time. For each own kreds wall
        post, mint an author-signed wrap_grant covering any current
        friend device the payload wraps + existing grants miss. Also heals
        the enckey-not-yet-synced transient (a post composed right after
        friend-add gets granted on the next sweep). Journal and inner are
        deliberately untouched -- a journal is a moment in time, and inner
        stays future-only (spec 2026-07-15-wall-wrap-grants).

        Guard mirrors maintain_enckey: minting signs, so locked/revoked/
        unenrolled skip entirely. Multi-device races just union."""
        if self.revoked or self.locked or self.device.identity_pub is None:
            return
        now = now if now is not None else time.time()
        friends = [i for i in self.store.known_identities()
                   if i != self.identity_pub]
        if not friends:
            return
        for msg in self.store.post_messages(self.identity_pub):
            p = msg.payload
            if (p.get("placement", "journal") != "profile"
                    or p.get("scope") != "kreds" or is_expired(p, now)):
                continue
            wrapped = set(p.get("wraps", {}))
            granted = self.store.wrap_grants(msg.msg_id, self.identity_pub)
            missing = {}
            for friend in friends:
                for dpub, enc_pub in self.store.enckeys(friend).items():
                    if dpub in wrapped:
                        continue
                    g = granted.get(dpub)
                    # covered only if the grant's wrap was sealed to the
                    # device's CURRENT enc key (enc_pub annotation from
                    # mint time; a pre-annotation grant counts as covered)
                    if g is not None and g.get("enc_pub", enc_pub) == enc_pub:
                        continue
                    missing[dpub] = enc_pub
            if not missing:
                continue
            key, aad = self._content_key(msg)
            if key is None:
                continue    # own post yet unrecoverable: never mint garbage
            wraps = wrap_key(key, missing, aad)
            for dpub in wraps:
                wraps[dpub]["enc_pub"] = missing[dpub]
            if wraps:
                self._publish(make_wrap_grant(self.device, msg.msg_id,
                                              wraps, now=now))
```

`_content_key` — third resolution step, between the envelope-unwrap loop and the final `return None, aad`:

```python
        # 3) wall wrap-grant unwrap (kreds back-catalog): only grants
        # signed by the POST'S author count -- store.wrap_grants filters
        # on author, so a hostile friend's grant naming someone else's
        # post is inert here (and in routing).
        if kind == KIND_POST:
            grants = self.store.wrap_grants(msg.msg_id,
                                            msg.cert.identity_pub)
            if grants:
                for priv in self.device.enc_privs():
                    key = unwrap_key(grants, self.device.device_pub, priv,
                                     aad)
                    if key is not None:
                        if stale_row:
                            self._replace_message_key(msg.msg_id, key)
                        else:
                            self._cache_message_key(msg.msg_id, key)
                        return key, aad
        return None, aad
```

`sync.py` `_gossip_round` — directly after `self.node.maintain_enckey()`:

```python
        self.node.maintain_wrap_grants()
```

`api.py` — directly after `node.maintain_enckey()` (line 72):

```python
    node.maintain_wrap_grants()
```

- [ ] **Step 4: Run new tests + full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_wrap_grants_node.py -q` then `.venv\Scripts\python.exe -m pytest -q`
Expected: all pass. Watch specifically: `tests/test_node_scoped_posts.py::test_feed_hides_undecryptable` and `::test_profile_view_shows_only_decryptable_posts` must stay green — they use inner posts, which the sweep never touches.

- [ ] **Step 5: Commit**

```bash
git add hearth/node.py hearth/sync.py hearth/api.py tests/test_wrap_grants_node.py
git commit -m "feat(node): wall wrap-grant sweep + grant-based key resolution - kreds wall back-catalog opens to current friends; journal + inner untouched; re-mints on recipient enckey rotation"
```

---

### Task 5: End-to-end over real sockets + legacy test split

**Files:**
- Create: `tests/test_wrap_grants_e2e.py`
- Modify: `tests/test_node_scoped_posts.py` — extend `test_ring_move_rekeys_future_only` area with a kreds-wall counterpart (the inner test itself stays byte-identical)
- Verify-untouched: `tests/test_scoped_posts_e2e.py` (inner/journal behavior — must stay green UNMODIFIED)

**Interfaces:**
- Consumes: everything from Tasks 1-4. Test helpers copied from `tests/test_scoped_posts_e2e.py` (`befriend`, `started`).

- [ ] **Step 1: Write the tests** (they should PASS if Tasks 1-4 are correct — this task is the spec's acceptance gate; a failure here is a Task 1-4 bug, fix it there)

```python
"""'A wall is a wall' end-to-end over real sockets (spec 2026-07-15):
wall posts made BEFORE a friendship reach the new friend after the
author's sweep; journal and inner posts from before the friendship stay
invisible; the row-before-grant ordering un-poisons the negative cache."""
import asyncio

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


def test_wall_back_catalog_opens_to_new_friend_over_sync(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        josh = HearthNode.create(tmp_path / "j", "Josh", "josh-phone")
        wong.ensure_enckey()
        # pre-friendship back catalog: wall (with a real photo blob, for
        # the blob follow-through assertion) + journal + inner wall.
        # PNG fixture idiom shared with test_imagegate.py.
        import io
        from PIL import Image
        buf = io.BytesIO()
        Image.new("RGB", (64, 64), (30, 80, 180)).save(buf, format="PNG")
        png = buf.getvalue()
        wall = wong.compose_post("bagkatalog", scope="kreds",
                                 placement="profile", photos=[png])
        journal = wong.compose_post("dagbog", scope="kreds")
        inner_wall = wong.compose_post("hemmelig", scope="inner",
                                       placement="profile")
        befriend(wong, josh)
        josh.ensure_enckey()
        sw, wa = await started(wong)
        sj, ja = await started(josh)
        await sw.sync_with(ja)              # enckeys cross
        await sj.sync_with(wa)
        wong.maintain_wrap_grants()         # the sweep (gossip-loop hook)
        await sw.sync_with(ja)              # grant + post row flow
        await sw.sync_with(ja)              # second round: blob want/give
        # NOTE (plan amended after Task 4): feed() is journal-only
        # (node.py:1196), wall posts render via posts_by/profile_view.
        wall_texts = [p["text"] for p in
                      josh.posts_by(wong.identity_pub, "profile")]
        assert "bagkatalog" in wall_texts
        assert josh.store.get_message(wall) is not None
        assert josh.store.missing_blobs() == set()          # photo followed
        assert josh.store.get_message(journal) is None      # journal: never
        assert josh.store.get_message(inner_wall) is None   # inner: never
        for s in (sw, sj):
            await s.stop()
    asyncio.run(scenario())


def test_row_before_grant_ordering_unpoisons_negative_cache(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        wong.ensure_enckey()
        mid = wong.compose_post("foerst raekken", scope="kreds",
                                placement="profile")
        befriend(wong, freja)
        freja.ensure_enckey()
        # force the poisoned ordering: the post ROW lands at freja first
        # (hand-carried, as a relaying mutual friend would), decryption
        # fails, the sweep negative-caches it
        post = wong.store.get_message(mid)
        # simulate: freja got the row via a mutual friend before any grant
        # (messages_not_in wouldn't offer it -- force-ingest is the point)
        freja.store.ingest_message(post)
        freja.cache_message_keys()
        assert mid in freja.store.undecryptable_ids()       # poisoned
        # now the author's sweep mints the grant and it syncs over
        sw, wa = await started(wong)
        sf, fa = await started(freja)
        await sf.sync_with(wa)              # enckeys cross
        wong.maintain_wrap_grants()
        await sw.sync_with(fa)              # grant arrives
        assert mid not in freja.store.undecryptable_ids()   # un-poisoned
        freja.cache_message_keys()          # next gossip-round sweep
        assert "foerst raekken" in [p["text"] for p in
                                    freja.posts_by(wong.identity_pub,
                                                   "profile")]
        for s in (sw, sf):
            await s.stop()
    asyncio.run(scenario())


def test_delete_then_sweep_mints_no_grant(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        wong.ensure_enckey()
        mid = wong.compose_post("fortrydes", scope="kreds",
                                placement="profile")
        # author deletes BEFORE any friendship exists
        from hearth.messages import make_delete
        wong.store.ingest_message(make_delete(wong.device, mid))
        befriend(wong, freja)
        freja.ensure_enckey()
        sw, wa = await started(wong)
        sf, fa = await started(freja)
        await sf.sync_with(wa)
        wong.maintain_wrap_grants()
        assert wong.store.wrap_grants(mid, wong.identity_pub) == {}
        await sw.sync_with(fa)
        assert freja.store.get_message(mid) is None
        for s in (sw, sf):
            await s.stop()
    asyncio.run(scenario())
```

(Blob follow-through rationale: blob serving is not per-post gated — once the granted row lands, `missing_blobs` drives blob sync on the next round, hence the second `sync_with` before the `missing_blobs() == set()` assertion.)

Legacy split, in `tests/test_node_scoped_posts.py` — add BELOW the untouched `test_ring_move_rekeys_future_only`:

```python
def test_kreds_wall_back_catalog_opens_but_inner_stays_closed(tmp_path):
    # "A wall is a wall" (0.3.11): the kreds-wall HALF of the old
    # future-only rule is gone -- the back catalog opens via wrap grants.
    # The inner half survives verbatim above (test_ring_move_rekeys_
    # future_only): this pair of tests IS the spec's two-scope split.
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    wall = wong.compose_post("foer venskab", scope="kreds",
                             placement="profile")
    inner_wall = wong.compose_post("indre foer", scope="inner",
                                   placement="profile")
    befriend_with_enckeys(wong, freja)
    wong.maintain_wrap_grants()
    for m in wong.store.messages_not_in({}, {wong.identity_pub},
                                        freja.identity_pub):
        freja.store.ingest_message(m)
    texts = [p["text"] for p in
             freja.posts_by(wong.identity_pub, "profile")]
    assert "foer venskab" in texts          # kreds wall: opened
    assert "indre foer" not in texts        # inner wall: honest hole
    assert freja.store.get_message(inner_wall) is None
```

- [ ] **Step 2: Run the new files**

Run: `.venv\Scripts\python.exe -m pytest tests/test_wrap_grants_e2e.py tests/test_node_scoped_posts.py -q`
Expected: PASS. Any failure is a Task 1-4 defect — fix it there (surgically), never by weakening these assertions.

- [ ] **Step 3: Run the full suite**

Run: `.venv\Scripts\python.exe -m pytest -q`
Expected: green. Confirm `tests/test_scoped_posts_e2e.py` passed WITHOUT modification (git diff must not touch it).

- [ ] **Step 4: Commit**

```bash
git add tests/test_wrap_grants_e2e.py tests/test_node_scoped_posts.py
git commit -m "test(grants): e2e acceptance - back catalog opens over real sockets, journal/inner stay closed, row-before-grant un-poisons, delete-then-sweep mints nothing"
```

---

### Task 6: UI copy + mixed-version documentation

**Files:**
- Modify: `hearth/web/app.js:1889` (composer note; grep app.js for any other "future posts" copy and update those too)
- Modify: `ROADMAP.md` (mixed-version caveat item, same style as item 19's blob-size window note)
- Test: `tests/test_web_assets.py` (append a content pin)

**Interfaces:** none produced; consumes the shipped behavior from Tasks 1-5.

- [ ] **Step 1: Write the failing content pin** (append to `tests/test_web_assets.py`)

```python
def test_composer_note_reflects_wall_wrap_grants():
    # "A wall is a wall" (0.3.11): the blanket "reveals only future
    # posts" claim is now wrong for the kreds wall -- the note must
    # scope the future-only rule to Inner and say kreds wall posts are
    # for current friends. DRAFT copy pinned here; August owns final
    # wording (update the pin together with the string).
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "Moving someone into a ring reveals only future posts." not in js
    assert "Inner posts reach only your Inner kreds" in js
```

- [ ] **Step 2: Run to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py::test_composer_note_reflects_wall_wrap_grants -q`
Expected: FAIL on the first assertion (old copy still present).

- [ ] **Step 3: Implement**

`app.js:1889` — replace the composer-note string with the DRAFT (flag for August's rewording in the task report; do not invent additional UI):

```javascript
  form.append(el("div", "composer-note",
    "Inner posts reach only your Inner kreds, and moving someone into Inner reveals only future Inner posts. Kreds wall posts are visible to all your current kreds."));
```

Then `grep -n "future posts" hearth/web/app.js` — if other copies of the old claim exist (ring-move dialogs, settings), update each the same way and extend the content pin accordingly.

`ROADMAP.md` — add a mixed-version item next to the existing 0.3.11 blob-size window item (match its style/numbering; content to convey):

- `wrap_grant` (0.3.11) is a new record kind. ≤0.3.10 peers refuse it at ingest ("unknown kind") — harmless, but (a) the refused seq leaves a summary gap, so updated peers re-offer each grant to old peers every round until they update (bounded chatter, single-release window, same acceptance as the 10 MB blob raise), and (b) old MUTUAL friends won't relay back-catalog posts to a new friend (their routing gate knows only payload wraps) — the new friend still gets the wall from the author's own node; relayed availability improves as mutuals update.

- [ ] **Step 4: Run the pin + full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q` then `.venv\Scripts\python.exe -m pytest -q`
Expected: green.

- [ ] **Step 5: Commit**

```bash
git add hearth/web/app.js tests/test_web_assets.py ROADMAP.md
git commit -m "docs+copy(grants): composer note scopes future-only to Inner (draft copy, August words final); ROADMAP mixed-version window for the wrap_grant kind"
```

---

## Self-Review Notes (planning time)

- **Spec coverage:** §1 record kind → T1. §2 sweep → T4. §3 routing gate → T3; key resolution → T4; negative-cache un-poisoning (both halves: `uncached_message_ids` union → T3, `clear_undecryptable` on grant ingest → T2); blob follow-through → verified none needed (noted in T5's optional blob assertion); deletes/expiry → T2; enckey rotation re-mint → T4 (via the `enc_pub` annotation, validated in T1). §4 ghost holes → no code (kreds holes close as a consequence; inner holes stay — pinned in T5). §5 mixed-version → verified at planning time (clean "unknown kind" refusal; GIVE loop tolerant), documented in T6; UI copy → T6. §6 tests → T5 + per-task tests.
- **Beyond-spec but load-bearing:** the author-filter on grants at every consumer (accessor, routing index, `_content_key`) — without it a hostile friend's grant widens someone else's post audience. Tested in T2 (accessor) and T3 (routing).
- **Deliberate deviations to flag for August:** (1) grant wraps carry an `enc_pub` annotation so staleness is detectable — the spec asked for re-mint-on-rotation and this is the minimal mechanism; validated but optional in the shape, so pre-annotation grants stay valid. (2) A grant arriving for a tombstoned target is ACCEPTED-then-tombstoned rather than refused, mirroring "deleted on arrival", so old rows don't re-offer forever.
- **Type consistency:** `wrap_grants(target, author) -> {device_pub: entry}` used identically in T2/T3/T4; stage of `make_wrap_grant(device, target, wraps, now=None)` consistent T1/T4.
- **Softness resolved at planning time:** `node.delete_post` (node.py:1161), `profile_view`'s `"wall"` key (node.py:1155-1158), and the PIL PNG fixture idiom (`tests/test_imagegate.py`) were all verified against the codebase — no lookups left for the implementer.
