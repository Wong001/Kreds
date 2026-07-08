# Protocol Warts Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close three tracked protocol warts before third-party nodes exist: delete tags become immune to deletion (kills a permanent-divergence path), superseded enckey rows get tombstone-pruned (kills unbounded growth), permanently-undecryptable DMs get a local negative cache (kills per-round crypto busywork).

**Architecture:** Per the approved spec `docs/superpowers/specs/2026-07-09-protocol-warts-design.md`. All ingest/lifecycle policy — no wire change, `hearth/v0.2` stands. All Python (store.py, node.py, sync.py); no JS.

**Tech Stack:** Python 3.12, sqlite, pytest.

## Global Constraints

- Tombstone, never bare-`DELETE FROM messages` — a deleted row gets re-fetched from peers forever; a tombstoned one is refused re-ingest and stops being offered. New tombstone reasons: `superseded` (enckey prune), `invalid` (inert meta-deletes). Existing reasons `deleted`/`expired` unchanged.
- The negative cache records ONLY during the background sweep, NEVER while the node is locked or `device.storage_key` is None (a locked node fails to decrypt everything — recording would mass-poison the cache).
- No AI co-author trailers on commits (project policy).
- Repo `C:\Users\Wong\Desktop\Hearth`, venv `.venv`; run tests from repo root with `.venv\Scripts\python.exe -m pytest ...`. Suite baseline: 689 passed, 1 skipped.
- Code facts referenced below were verified 2026-07-09: delete ingest at store.py:241-306 (`_tombstone` 233-237, authorization lookup 271-278 with NO kind filter, delete-on-arrival guard 281-294 in the non-delete `else` branch), enckey resolution `enckey_records` store.py:428-447 with `(created_at, seq)` tie-break, rotation `maintain_enckey` node.py:1086-1109, sweep call sites sync.py:194-207, `cache_message_keys` node.py:1180-1189, `uncached_message_ids` in store.py ~495-537, `unlock()` node.py:225-266, messages schema store.py:31-42.

---

### Task 1: Delete tags immune to deletion

**Files:**
- Modify: `hearth/store.py` (ingest_message ~241-306; add helper)
- Modify: `hearth/node.py` (delete_post ~686)
- Test: `tests/test_store_ingest.py`, `tests/test_deletion_convergence.py`

**Interfaces:**
- Produces: `Store.message_kind(msg_id) -> str | None` (used by node.delete_post). New ingest refusal string: `"delete tag cannot target a delete tag"`. New tombstone reason: `"invalid"`.

- [ ] **Step 1: Write the failing tests** — append to `tests/test_store_ingest.py` (reuse the file's existing helpers for building identities/messages — read its imports and fixtures first and mirror the adjacent delete tests' construction style exactly):

```python
def test_delete_tag_cannot_target_a_delete_tag_when_held(store_and_identity):
    # Wart 1 (spec 2026-07-09-protocol-warts): a meta-delete that lands
    # AFTER the tag it targets must be refused - pre-fix it tombstoned the
    # tag, halting its propagation and permanently diverging the network.
    store, dev = store_and_identity
    post = _ingest_post(store, dev)                    # helper per file style
    tag = make_delete(dev, post)
    assert store.ingest_message(tag).accepted
    meta = make_delete(dev, tag.msg_id)
    res = store.ingest_message(meta)
    assert not res.accepted and "cannot target a delete tag" in res.reason
    # the original tag row is still held and still offered (not tombstoned)
    assert store.message_kind(tag.msg_id) == "delete"


def test_arriving_delete_tag_is_immune_to_a_lurking_meta_delete(store_and_identity):
    # Pin the (already-correct) behavior: the delete-on-arrival guard sits
    # in the non-delete branch, so a meta-delete held BEFORE its target tag
    # arrives never tombstones-on-arrival the tag itself...
    store, dev = store_and_identity
    post = _ingest_post(store, dev)
    tag = make_delete(dev, post)
    meta = make_delete(dev, tag.msg_id)
    assert store.ingest_message(meta).accepted        # lurks (target unknown)
    res = store.ingest_message(tag)
    assert res.accepted and res.reason != "deleted on arrival"
    # ...the post is gone (the real tag applied) and the tag row survives
    assert store.message_kind(tag.msg_id) == "delete"
    # ...and the now-provably-invalid meta-delete was tombstoned as hygiene
    assert store.message_kind(meta.msg_id) is None
    assert store.is_tombstoned(meta.msg_id)


def test_delete_post_refuses_a_held_delete_target(store_and_identity):
    store, dev = store_and_identity
    # node-level creation guard - build a minimal node per the file's
    # conventions or, if node construction is heavyweight here, place this
    # test in tests/test_node.py beside test_compose_feed_delete_with_photo.
```

(The third test lives wherever node-level tests already construct a real `HearthNode` — follow `tests/test_node.py`'s pattern: create node, post, `delete_post(post_id)` once, then `pytest.raises(ValueError, match="cannot delete a delete tag")` on `delete_post(tag_id)`.)

And append to `tests/test_deletion_convergence.py` (mirroring its real-sync two/three-node style): a divergence regression — A posts; the post syncs to B and C; A deletes the post; the tag syncs everywhere; a meta-delete (crafted via `make_delete(dev_a, tag_id)` and injected via direct `ingest_message` at C, since `delete_post` now refuses to create one) is refused at C; after further sync rounds all three nodes agree: post gone, tag held (or tombstoned only by expiry semantics), no node resurrects the post.

- [ ] **Step 2: Run to verify failures**

Run: `.venv\Scripts\python.exe -m pytest tests/test_store_ingest.py tests/test_deletion_convergence.py -q`
Expected: new tests FAIL (`message_kind` missing / meta-delete currently accepted); pre-existing pass.

- [ ] **Step 3: Implement.**

(a) `hearth/store.py` — add near the other small readers:

```python
    def message_kind(self, msg_id: str):
        """Kind of a held message row, or None if not held (tombstoned or
        never seen). Used by the delete-creation guard and tests."""
        row = self._db.execute(
            "SELECT kind FROM messages WHERE msg_id=?", (msg_id,)).fetchone()
        return row[0] if row else None
```

(b) `hearth/store.py` `ingest_message`, the `kind == KIND_DELETE` branch: change the authorization lookup to also select `kind`, and refuse a delete-kind target BEFORE the author check:

```python
            row = self._db.execute(
                "SELECT identity_pub, kind FROM messages WHERE msg_id=?",
                (target,)).fetchone()
            if row is not None:
                if row[1] == KIND_DELETE:
                    # Wart 1: delete tags are immune to deletion. Tombstones
                    # are permanent (no undelete), so a delete-of-a-delete can
                    # only halt the tag's propagation -> permanent divergence.
                    return IngestResult(False,
                                        "delete tag cannot target a delete tag", mid)
                if row[0] != identity:
                    return IngestResult(False, "delete not authorized", mid)
                self._tombstone(target, "deleted")
                deleted_target = target
```

(c) Same branch, hygiene for the lurking case — after the arriving delete's own row is inserted (the common insert at ~296-301), add (guarded on `kind == KIND_DELETE`):

```python
        if kind == KIND_DELETE:
            # Hygiene: any held meta-delete targeting THIS tag is now
            # provably invalid - tombstone it (reason 'invalid') so it stops
            # gossiping. Tombstone, not DELETE: a bare row-delete would be
            # re-fetched from peers on the next summary diff.
            for (bad,) in self._db.execute(
                    "SELECT msg_id FROM messages WHERE kind=? AND target_id=?",
                    (KIND_DELETE, mid)).fetchall():
                self._tombstone(bad, "invalid")
```

(place it so it runs inside the same commit as the insert; reuse the existing commit at the end of ingest).

(d) `hearth/node.py` `delete_post` — add the creation guard before `make_delete`:

```python
        if self.store.message_kind(target_msg_id) == KIND_DELETE:
            raise ValueError("cannot delete a delete tag")
```

(`KIND_DELETE` import: node.py already imports from `.messages` — extend that import if absent.) Confirm `/api/delete`'s existing error mapping turns ValueError into a 400 (grep api.py for the `_400` pattern used by other endpoints; if delete has no mapping, add the same try/except used by `/api/post`).

- [ ] **Step 4: Run tests**

Run: `.venv\Scripts\python.exe -m pytest tests/test_store_ingest.py tests/test_deletion_convergence.py tests/test_node.py tests/test_api_delete.py -q` (drop test_api_delete.py if it doesn't exist; grep tests/ for the delete API test location)
Expected: all PASS

- [ ] **Step 5: Commit**

```bash
git add hearth/store.py hearth/node.py tests/
git commit -m "fix(protocol): delete tags are immune to deletion - refuse at creation and ingest, tombstone lurking meta-deletes as invalid (closes the tag-on-tag permanent-divergence path)"
```

---

### Task 2: Tombstone-prune superseded enckeys

**Files:**
- Modify: `hearth/store.py` (new method beside `sweep_expired` ~330)
- Modify: `hearth/sync.py` (gossip round, beside the sweep at ~205)
- Test: new `tests/test_enckey_prune.py`

**Interfaces:**
- Consumes: existing `_tombstone`, `enckey_records`' `(created_at, seq)` tie-break semantics.
- Produces: `Store.prune_superseded_enckeys() -> int` (count pruned).

- [ ] **Step 1: Write the failing tests** — create `tests/test_enckey_prune.py` (mirror `tests/test_store_dm.py`'s store/device fixtures for constructing enckey messages via `make_enckey`):

```python
"""Wart 2 (spec 2026-07-09-protocol-warts): daily rotation accumulates one
KIND_ENCKEY row per device per day forever; nothing pruned them. The prune
keeps exactly the latest per (identity, device) by the same (created_at,
seq) tie-break enckey_records uses, and TOMBSTONES the rest (reason
'superseded') - a bare DELETE would be re-fetched from peers forever."""


def test_prune_keeps_exactly_latest_per_device(...):
    # ingest 3 rotations for device A (t=1,2,3) and 2 for device B (t=1,2)
    # prune -> returns 3; enckeys() resolution identical before/after;
    # latest rows still held; superseded rows tombstoned w/ reason
    # 'superseded' and no longer held.


def test_prune_tiebreak_same_created_at_higher_seq_wins(...):
    # two enckeys same created_at, seq 5 and 6 -> seq 6 survives.


def test_pruned_row_refused_reingest(...):
    # ingest_message of a pruned (tombstoned) enckey row -> not accepted
    # (the anti-resurrection gate), so a stale peer can't push it back.


def test_prune_is_idempotent_and_noop_on_single_keys(...):
    # second prune returns 0; a device with one enckey is untouched.
```

Write these as REAL tests (full bodies) following the fixture style you find in `tests/test_store_dm.py` — the sketch above defines required behavior, not literal text. Also append to `tests/test_gossip_loop.py` (mirror its loop test): after a gossip round, superseded enckeys are pruned (the sync.py wiring).

- [ ] **Step 2: Run to verify failure** — `.venv\Scripts\python.exe -m pytest tests/test_enckey_prune.py -q` → FAIL (method missing).

- [ ] **Step 3: Implement.**

(a) `hearth/store.py`, beside `sweep_expired`:

```python
    def prune_superseded_enckeys(self) -> int:
        """Tombstone (reason 'superseded') every enckey row that is not the
        latest for its (identity_pub, device_pub), by the same
        (created_at, seq) tie-break enckey_records resolves with. Rotation
        is daily (maintain_enckey), so without this the table grows one row
        per device per day forever, replicated to every friend. Tombstone,
        never DELETE: a bare row-delete reads as "missing" to the next
        summary diff and peers re-send it forever; a tombstone stops both
        the holding and the offering, so superseded rows evaporate
        network-wide as each node prunes independently. Safe: nothing reads
        superseded rows (senders wrap to latest; recipients decrypt with
        retired PRIVATE keys, client-side, untouched here)."""
        rows = self._db.execute(
            "SELECT msg_id, identity_pub, device_pub, created_at, seq "
            "FROM messages WHERE kind=?", (KIND_ENCKEY,)).fetchall()
        latest = {}
        for mid, ident, dpub, created, seq in rows:
            cur = latest.get((ident, dpub))
            if cur is None or (created, seq) > cur[1]:
                latest[(ident, dpub)] = (mid, (created, seq))
        keep = {v[0] for v in latest.values()}
        pruned = 0
        for mid, *_rest in rows:
            if mid not in keep:
                self._tombstone(mid, "superseded")
                pruned += 1
        if pruned:
            self._db.commit()
        return pruned
```

(b) `hearth/sync.py`, in `_gossip_round` directly after the `sweep_expired` call (~205-206):

```python
            self.node.store.prune_superseded_enckeys()
```

(no `notify()` — enckey rows have no UI surface).

- [ ] **Step 4: Run** — `.venv\Scripts\python.exe -m pytest tests/test_enckey_prune.py tests/test_store_dm.py tests/test_gossip_loop.py tests/test_dm_rotation_e2e.py -q` → all PASS (the rotation e2e is the canary that grace-window decryption still works with pruning active in the loop).

- [ ] **Step 5: Commit**

```bash
git add hearth/store.py hearth/sync.py tests/test_enckey_prune.py tests/test_gossip_loop.py
git commit -m "fix(protocol): tombstone-prune superseded enckey rows each gossip round (unbounded daily-rotation growth; tombstone so peers stop re-sending)"
```

---

### Task 3: Negative cache for undecryptable messages

**Files:**
- Modify: `hearth/store.py` (schema `_SCHEMA`; `_tombstone`; `uncached_message_ids`; `cache_message_key`/`replace_message_key`; new mark/clear methods)
- Modify: `hearth/node.py` (`cache_message_keys` ~1180; `unlock` ~225)
- Test: `tests/test_node_dm.py`, `tests/test_store_dm_keys.py`

**Interfaces:**
- Produces: table `undecryptable(msg_id TEXT PRIMARY KEY, since REAL NOT NULL)`; `Store.mark_undecryptable(msg_id, now=None)`, `Store.clear_undecryptable(msg_id=None)` (None = clear all), `Store.undecryptable_ids() -> set` (test convenience).

- [ ] **Step 1: Write the failing tests** — following `tests/test_node_dm.py`'s two-device fixtures (sender wraps to a key the reader never had = permanently undecryptable):

```python
def test_sweep_negative_caches_undecryptable_and_skips_next_round(...):
    # DM wrapped to an alien device key arrives; first cache_message_keys()
    # marks it; uncached_message_ids no longer yields it; a counting spy on
    # _content_key (monkeypatch) proves the second sweep does 0 attempts.


def test_locked_node_records_nothing(...):
    # lock the node (or construct locked_from_json per test_applock_node
    # fixtures); run cache_message_keys(); undecryptable_ids() is empty.


def test_unlock_clears_negative_cache(...):
    # mark an id, unlock() -> undecryptable_ids() empty (belt-and-braces).


def test_caching_a_key_removes_the_entry(...):
    # mark an id, then cache_message_key(msg_id, ...) -> entry gone.


def test_tombstone_removes_the_entry(...):
    # mark an id, _tombstone via a delete tag -> entry gone.


def test_dm_thread_view_still_attempts_decryption(...):
    # a marked id still shows in dm_thread with undecryptable=True (views
    # are correct-first; only the background sweep consults the cache) -
    # and if key material later ALLOWS decryption (retired-key case),
    # dm_thread still decrypts it despite the mark.
```

Real bodies per the file's conventions; the behavior contract above is binding.

- [ ] **Step 2: Run to verify failure** — targeted files → new tests FAIL.

- [ ] **Step 3: Implement.**

(a) `_SCHEMA` (store.py:22-51): add

```sql
CREATE TABLE IF NOT EXISTS undecryptable(
  msg_id TEXT PRIMARY KEY, since REAL NOT NULL);
```

(b) Store methods beside the dm_keys group:

```python
    def mark_undecryptable(self, msg_id: str, now=None) -> None:
        """Wart 3: background-sweep negative cache. LOCAL ONLY - never
        synced. Only the gossip-round sweep writes here (and only while the
        node is unlocked - see node.cache_message_keys); per-view reads in
        dm_thread stay correct-first and ignore this table."""
        self._db.execute(
            "INSERT OR IGNORE INTO undecryptable VALUES(?,?)",
            (msg_id, time.time() if now is None else now))
        self._db.commit()

    def clear_undecryptable(self, msg_id=None) -> None:
        if msg_id is None:
            self._db.execute("DELETE FROM undecryptable")
        else:
            self._db.execute("DELETE FROM undecryptable WHERE msg_id=?", (msg_id,))
        self._db.commit()

    def undecryptable_ids(self) -> set:
        return {r[0] for r in self._db.execute(
            "SELECT msg_id FROM undecryptable")}
```

(check `import time` exists in store.py; add if absent).

(c) `uncached_message_ids` (~495-537): extend its WHERE with
`AND msg_id NOT IN (SELECT msg_id FROM undecryptable)` (read the actual query first and splice minimally).

(d) `_tombstone` (233-237): add `self._db.execute("DELETE FROM undecryptable WHERE msg_id=?", (msg_id,))` beside the dm_keys delete.

(e) `cache_message_key` and `replace_message_key`: after inserting the sealed key, `self._db.execute("DELETE FROM undecryptable WHERE msg_id=?", (msg_id,))` inside the same commit.

(f) `node.cache_message_keys` (~1180-1189): guard + mark. Preserve the existing loop body; shape:

```python
    def cache_message_keys(self) -> None:
        # Locked (or storage key absent): EVERYTHING fails to decrypt -
        # recording now would mass-poison the negative cache (spec, Wart 3).
        if self.locked or self.device.storage_key is None:
            return
        for mid in self.store.uncached_message_ids(self.identity_pub):
            ...existing fetch + self._content_key(msg)...
            if key is None:
                self.store.mark_undecryptable(mid)
```

(g) `node.unlock` (~225-266): after key material is restored, `self.store.clear_undecryptable()` with a one-line comment (restored keys may make old failures decryptable).

- [ ] **Step 4: Run the FULL suite** — `.venv\Scripts\python.exe -m pytest -q` → expect ~700 passed (689 baseline + new), 1 skipped. The applock integration test (`test_applock_integration.py`) is the canary for the unlock hook.

- [ ] **Step 5: Commit**

```bash
git add hearth/store.py hearth/node.py tests/
git commit -m "fix(protocol): negative-cache permanently-undecryptable messages - gossip sweep stops retrying them every round; never recorded while locked, cleared on unlock/key-cache/tombstone"
```

---

### Task 4: Docs + final suite

**Files:**
- Modify: `docs/engineering-notes.md` ("Deletion" section; "Encrypted messages" section)

- [ ] **Step 1:** In the Deletion section, add one honest paragraph: delete tags are immune to deletion (refused at creation and ingest; a lurking meta-delete is tombstoned as invalid when its target proves to be a tag) — closing a pre-users divergence path where deleting a delete tag halted its propagation. In the Encrypted messages section, add two sentences: superseded daily-rotation enckey announcements are tombstone-pruned each sweep (unbounded growth fix; retired PRIVATE keys and the 7-day grace are untouched), and permanently-undecryptable envelopes are negative-cached by the background sweep only (views still attempt decryption every time; the cache clears on unlock or when a key materializes).
- [ ] **Step 2:** Full suite: `.venv\Scripts\python.exe -m pytest -q` → all PASS. 
- [ ] **Step 3: Commit**

```bash
git add docs/engineering-notes.md
git commit -m "docs: deletion immunity, enckey pruning, and the undecryptable negative cache in engineering notes"
```

---

## Self-review (done at write time)

- **Spec coverage:** Wart 1 rules 1/2/4 are code (T1 steps 3a-d), rule 3 is a pinning test (T1 step 1, second test — the scout's map showed the guard already exempts arriving deletes); Wart 2 incl. tie-break, tombstone rationale, gossip wiring, rotation-e2e canary (T2); Wart 3 incl. both safety rules, all three clear paths, view-untouched contract (T3); docs (T4). Out-of-scope items match spec.
- **Placeholders:** T2/T3 test steps give behavior contracts + named conventions to mirror rather than verbatim bodies (fixtures differ per file; the implementer must read the fixture style first) — deliberate, bounded by explicit binding contracts, not TBDs.
- **Type consistency:** `message_kind` (T1a) used in T1d and T1 tests; `mark/clear/undecryptable_ids` names consistent across T3 store/node/tests; tombstone reasons `invalid`/`superseded` consistent with spec.
