# Kreds Scoped Encrypted Posts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every post a per-recipient encrypted message addressed to a ring (Inner or Kreds), replacing plaintext posts — so "scope is physics" and the feed/profile only ever show posts this device can decrypt.

**Architecture:** A post becomes a DM-shaped envelope addressed to a *set* of devices. Reuse the existing `dmcrypt` seal/wrap primitives (already generic over key+AAD). Ring membership is the author's private, own-device-only signed state. Routing self-describes via the `wraps` set. Posts inherit the shipped windowed forward secrecy and the (generalized) local message-key cache.

**Tech Stack:** Python 3.12 (`.venv`), asyncio, sqlite3, `cryptography` (X25519 + ChaCha20-Poly1305), pytest, vanilla JS.

**Spec:** `docs/superpowers/specs/2026-07-06-kreds-scoped-posts-design.md`

## Global Constraints

- Branch: `kreds-scoped-posts` off `main`. One workstream; nothing unrelated.
- Test runner: `.venv\Scripts\python.exe -m pytest tests -q -p no:cacheprovider`. Verify the full suite under a timeout and read the real tail — this repo has had false-green reports: `timeout 120 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3`.
- ASCII only in console prints (cp1252).
- Two scopes only: `"inner"` and `"kreds"`. Built as named audiences so a future `"open"` scope type slots in later. No `"open"` in this slice.
- **Every post is scoped + encrypted; plaintext posts are retired.** `make_post`'s old `(device, text, blob_refs, ...)` signature is replaced. Every caller (demo, tests) is updated in the task that changes the signature — the suite must be green at each task's commit.
- Reuse, don't duplicate, `hearth/dmcrypt.py` primitives: `new_content_key`, `encrypt_body`, `decrypt_body`, `wrap_key(key, device_enc_pubs, aad)`, `unwrap_key(wraps, device_pub, enc_priv, aad)`, `encrypt_blob`, `decrypt_blob`, `seal_content_key`, `open_content_key`.
- `ring` records route **own-device-only** — never offered to a friend's node.
- Posts encrypted at rest; decryption happens at the **node** layer (which holds keys), never in `store` (which holds only ciphertext) — mirror the DM read path (`store.dm_thread` returns raw `SignedMessage`s; `node.dm_thread` decrypts).
- Honest boundary (already in spec, no code needed): a recipient can enumerate a post's audience from `wraps`; non-members receive nothing (routing).

---

### Task 1: Post + ring message kinds, envelope AAD, validation

**Files:**
- Modify: `hearth/messages.py` (add `KIND_RING`; replace `make_post`; add `make_ring`; `validate_payload` post + ring branches)
- Modify: `hearth/dmcrypt.py` (add `post_aad`)
- Test: `tests/test_messages.py`, `tests/test_dmcrypt.py`

**Interfaces:**
- Produces:
  - `KIND_RING = "ring"`; `RINGS = ("inner", "kreds")`
  - `dmcrypt.post_aad(author_identity: str, scope: str, created_at: float) -> bytes`
  - `make_post(device, scope, body_nonce, body_ct, wraps, blob_refs=(), created_at=..., expires_at=None) -> SignedMessage` (payload `{kind:"post", scope, body_nonce, body_ct, wraps, blobs, created_at, expires_at}`)
  - `make_ring(device, member, ring, now=None) -> SignedMessage` (payload `{kind:"ring", member, ring, created_at}`)
  - `validate_payload` accepts the new post shape and ring; rejects a post missing `scope`/`wraps`.

- [ ] **Step 1: Create the branch**

```powershell
git checkout main; git pull; git checkout -b kreds-scoped-posts
```

- [ ] **Step 2: Write failing tests**

In `tests/test_dmcrypt.py`, extend the `hearth.dmcrypt` import with `post_aad` and append:

```python
def test_post_aad_binds_author_scope_time():
    a = post_aad("aa" * 32, "inner", 100.0)
    assert a != post_aad("aa" * 32, "kreds", 100.0)      # scope bound
    assert a != post_aad("bb" * 32, "inner", 100.0)      # author bound
    assert a != post_aad("aa" * 32, "inner", 101.0)      # time bound
    assert isinstance(a, (bytes, bytearray))
```

In `tests/test_messages.py` (create if absent; check the file first), add:

```python
from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import (KIND_POST, KIND_RING, make_post, make_ring,
                             validate_payload)


def _dev():
    d = DeviceKeys.create("phone")
    IdentityCeremony().enroll_first_device(d)
    return d


def test_make_post_scoped_encrypted_shape():
    d = _dev()
    m = make_post(d, "kreds", body_nonce="ab" * 12, body_ct="deadbeef",
                  wraps={}, blob_refs=[], created_at=100.0)
    p = m.payload
    assert p["kind"] == KIND_POST and p["scope"] == "kreds"
    assert p["body_nonce"] == "ab" * 12 and p["body_ct"] == "deadbeef"
    assert p["wraps"] == {} and p["blobs"] == []
    ok, why = validate_payload(p)
    assert ok, why


def test_post_rejects_bad_scope_and_missing_fields():
    d = _dev()
    ok, _ = validate_payload({"kind": "post", "scope": "open",
                              "body_nonce": "ab" * 12, "body_ct": "de",
                              "wraps": {}, "blobs": [], "created_at": 1.0})
    assert not ok                                        # "open" not allowed
    ok, _ = validate_payload({"kind": "post", "scope": "kreds",
                              "created_at": 1.0})
    assert not ok                                        # missing envelope


def test_make_ring_and_validate():
    d = _dev()
    m = make_ring(d, "cc" * 32, "inner", now=5.0)
    assert m.payload == {"kind": KIND_RING, "member": "cc" * 32,
                         "ring": "inner", "created_at": 5.0}
    assert validate_payload(m.payload)[0]
    ok, _ = validate_payload({"kind": "ring", "member": "cc" * 32,
                              "ring": "outer", "created_at": 5.0})
    assert not ok                                        # bad ring
```

- [ ] **Step 3: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_messages.py tests/test_dmcrypt.py -q`
Expected: FAIL — `ImportError` on `post_aad`/`make_ring` and the new post signature.

- [ ] **Step 4: Implement**

In `hearth/dmcrypt.py`, next to `dm_aad`:

```python
def post_aad(author_identity: str, scope: str, created_at: float) -> bytes:
    return canonical({"type": "post-aad", "protocol": PROTOCOL,
                      "from": author_identity, "scope": scope,
                      "created_at": created_at})
```

In `hearth/messages.py`: add `KIND_RING = "ring"` and `RINGS = ("inner", "kreds")` near the other kind constants. Replace the existing `make_post` with:

```python
def make_post(device: DeviceKeys, scope: str, body_nonce: str,
              body_ct: str, wraps: dict, blob_refs: Sequence[str] = (),
              created_at: Optional[float] = None,
              expires_at: Optional[float] = None) -> SignedMessage:
    return device.sign_message({
        "kind": KIND_POST, "scope": scope, "body_nonce": body_nonce,
        "body_ct": body_ct, "wraps": wraps, "blobs": list(blob_refs),
        "created_at": _now(created_at), "expires_at": expires_at,
    })


def make_ring(device: DeviceKeys, member: str, ring: str,
              now: Optional[float] = None) -> SignedMessage:
    return device.sign_message({
        "kind": KIND_RING, "member": member, "ring": ring,
        "created_at": _now(now),
    })
```

In `validate_payload`, **replace** the `if kind == KIND_POST:` branch with a DM-shaped one, and add a ring branch. The post branch reuses the same wrap validation the DM branch already has — factor it into a module helper `_valid_wraps(wraps)` and call it from both:

```python
def _valid_wraps(wraps) -> bool:
    if not isinstance(wraps, dict):
        return False
    for dpub, w in wraps.items():
        if not _is_hex64(dpub) or not isinstance(w, dict):
            return False
        if not _is_hex64(w.get("eph_pub")):
            return False
        if not _is_hexn(w.get("nonce"), 24):
            return False
        wk = w.get("wrapped_key")
        if (not isinstance(wk, str) or not wk
                or any(c not in "0123456789abcdef" for c in wk)):
            return False
    return True
```

Post branch:

```python
    if kind == KIND_POST:
        if p.get("scope") not in ("inner", "kreds"):
            return False, "bad scope"
        if not _is_hexn(p.get("body_nonce"), 24):
            return False, "bad body_nonce"
        ct = p.get("body_ct")
        if (not isinstance(ct, str) or not ct
                or any(c not in "0123456789abcdef" for c in ct)):
            return False, "bad body_ct"
        if not _valid_wraps(p.get("wraps")):
            return False, "bad wraps"
        blobs = p.get("blobs", [])
        if not isinstance(blobs, list) or not all(_is_hex64(b) for b in blobs):
            return False, "bad blobs"
        exp = p.get("expires_at")
        if exp is not None and not isinstance(exp, (int, float)):
            return False, "bad expires_at"
        return True, "ok"
```

Ring branch (add before the final `return False, "unknown kind"`):

```python
    if kind == KIND_RING:
        if not _is_hex64(p.get("member")):
            return False, "bad member"
        if p.get("ring") not in ("inner", "kreds"):
            return False, "bad ring"
        return True, "ok"
```

Update the DM branch to call `_valid_wraps(p.get("wraps"))` instead of its inline loop (DRY; behavior identical).

- [ ] **Step 5: Run the new tests + full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_messages.py tests/test_dmcrypt.py -q` — Expected: PASS.
The full suite will now FAIL in other files (callers of the old `make_post`). That is expected and fixed in the tasks that own those layers. Run it and note which files break (they should be only post-authoring tests): `timeout 120 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -20`. Do NOT fix them here — they belong to Tasks 5/8. Commit this task with the messages/dmcrypt tests green.

- [ ] **Step 6: Commit**

```powershell
git add hearth/messages.py hearth/dmcrypt.py tests/test_messages.py tests/test_dmcrypt.py
git commit -m "feat: scoped-post + ring message kinds, post envelope AAD, validation"
```

---

### Task 2: Ring records — store resolution + own-device routing

**Files:**
- Modify: `hearth/store.py` (`KIND_RING` import; `rings()` resolver; `messages_not_in` own-device rule for ring)
- Test: `tests/test_store_rings.py` (new)

**Interfaces:**
- Consumes: Task 1 `make_ring`, `KIND_RING`.
- Produces: `Store.rings(identity_pub: str) -> Dict[str, str]` — member_identity -> "inner"|"kreds", latest-wins by `(created_at, seq)`, absent members omitted (caller treats absent as "kreds"). `messages_not_in` offers a `kind="ring"` row only to a peer whose identity == the row's author.

- [ ] **Step 1: Write failing tests**

Create `tests/test_store_rings.py`:

```python
from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import make_ring
from hearth.store import Store


def wong(tmp_path):
    phone = DeviceKeys.create("wong-phone")
    IdentityCeremony().enroll_first_device(phone)
    s = Store(tmp_path / "w.db")
    s.add_identity(phone.identity_pub, is_self=True)
    return s, phone


def test_rings_latest_wins(tmp_path):
    s, phone = wong(tmp_path)
    m = "cc" * 32
    s.add_identity(m)
    assert s.ingest_message(make_ring(phone, m, "inner", now=1.0)).accepted
    assert s.rings(phone.identity_pub) == {m: "inner"}
    assert s.ingest_message(make_ring(phone, m, "kreds", now=2.0)).accepted
    assert s.rings(phone.identity_pub) == {m: "kreds"}      # latest wins


def test_ring_records_route_own_device_only(tmp_path):
    s, phone = wong(tmp_path)
    m = "cc" * 32
    s.add_identity(m)
    s.ingest_message(make_ring(phone, m, "inner", now=1.0))
    ident = phone.identity_pub
    summaries = {}
    # a friend (not me) is offered NOTHING of kind ring
    friend = "dd" * 32
    s.add_identity(friend)
    to_friend = s.messages_not_in(summaries, entitled={ident}, peer_identity=friend)
    assert all(msg.payload.get("kind") != "ring" for msg in to_friend)
    # my own other device IS offered the ring record
    to_self = s.messages_not_in(summaries, entitled={ident}, peer_identity=ident)
    assert any(msg.payload.get("kind") == "ring" for msg in to_self)
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_store_rings.py -q`
Expected: FAIL — `AttributeError: 'Store' object has no attribute 'rings'`.

- [ ] **Step 3: Implement**

In `hearth/store.py`, add `KIND_RING` to the messages import. Add `rings()` near `enckey_records`:

```python
    def rings(self, identity_pub: str) -> Dict[str, str]:
        """member_identity -> 'inner'|'kreds' for this author, latest-wins
        by (created_at, seq). Absent members default to 'kreds' at the
        caller. Author-private; never disclosed to friends (see routing)."""
        with self._lock:
            best: Dict[str, tuple] = {}
            for seq, mj in self._db.execute(
                    "SELECT seq, msg_json FROM messages"
                    " WHERE kind=? AND identity_pub=?",
                    (KIND_RING, identity_pub)):
                p = json.loads(mj)["payload"]
                rank = (p["created_at"], seq)
                member = p["member"]
                if member not in best or rank > best[member][0]:
                    best[member] = (rank, p["ring"])
            return {m: r for m, (rank, r) in best.items()}
```

In `messages_not_in`, add the own-device-only rule for ring rows (right after the existing DM rule):

```python
                if kind == KIND_RING and peer_identity != ipub:
                    continue          # ring records are author-private
```

- [ ] **Step 4: Run tests + full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_store_rings.py -q` — Expected: PASS.
Run: `timeout 120 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3` — still failing only in post-authoring test files (Tasks 5/8); ring/routing tests pass.

- [ ] **Step 5: Commit**

```powershell
git add hearth/store.py tests/test_store_rings.py
git commit -m "feat: ring records - author-private latest-wins + own-device routing"
```

---

### Task 3: Scoped-post routing + raw post-message accessors in store

**Files:**
- Modify: `hearth/store.py` (`messages_not_in` post rule; `post_messages()`)
- Test: `tests/test_store_scoped_posts.py` (new)

**Interfaces:**
- Consumes: Task 1 `make_post`.
- Produces:
  - `messages_not_in` offers a `kind="post"` row to a peer iff `peer_identity == author` OR the peer owns a device present in the post's `wraps`.
  - `Store.post_messages(identity_pub: Optional[str] = None) -> List[SignedMessage]` — raw post `SignedMessage`s newest-first (all authors, or one author), for the node to decrypt. Excludes tombstoned rows (they are already deleted from `messages`).

- [ ] **Step 1: Write failing tests**

Create `tests/test_store_scoped_posts.py`:

```python
from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import make_post
from hearth.store import Store


def wong(tmp_path):
    phone = DeviceKeys.create("wong-phone")
    IdentityCeremony().enroll_first_device(phone)
    s = Store(tmp_path / "w.db")
    s.add_identity(phone.identity_pub, is_self=True)
    return s, phone


def _post(phone, scope, wraps, created_at=100.0):
    return make_post(phone, scope, body_nonce="ab" * 12, body_ct="deadbeef",
                     wraps=wraps, blob_refs=[], created_at=created_at)


# Routing resolves a peer's devices via load_views(peer_identity), so the
# audience test builds real enrolled devices (their real device_pubs are the
# wraps keys) rather than synthetic hex — modelled on tests/test_sync_dm.py.
def test_post_routing_by_wrapset(tmp_path):
    from hearth.node import HearthNode
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mikkel = HearthNode.create(tmp_path / "m", "Mikkel", "mikkel-phone")
    for a in (freja, mikkel):
        wong.store.add_identity(a.identity_pub)
        wong.store.load_views(a.identity_pub)  # ensure views table has them
    # store freja's + mikkel's device views so the router can resolve them
    from hearth.identity import DeviceView
    wong.store.save_views(freja.identity_pub,
        {freja.device.device_pub: DeviceView(cert=freja.device.cert)})
    wong.store.save_views(mikkel.identity_pub,
        {mikkel.device.device_pub: DeviceView(cert=mikkel.device.cert)})
    # inner post wrapped to freja's device only
    m = make_post(wong.device, "inner", body_nonce="ab" * 12,
                  body_ct="de", wraps={freja.device.device_pub: {
                      "eph_pub": "22" * 32, "nonce": "33" * 12,
                      "wrapped_key": "deadbeef"}}, created_at=100.0)
    wong.store.ingest_message(m)
    ent = {wong.identity_pub}
    # offered to freja (in wraps) and to wong (author); NOT to mikkel
    to_f = wong.store.messages_not_in({}, ent, freja.identity_pub)
    to_m = wong.store.messages_not_in({}, ent, mikkel.identity_pub)
    to_w = wong.store.messages_not_in({}, ent, wong.identity_pub)
    assert any(x.msg_id == m.msg_id for x in to_f)
    assert all(x.msg_id != m.msg_id for x in to_m)
    assert any(x.msg_id == m.msg_id for x in to_w)


def test_post_messages_accessor(tmp_path):
    s, phone = wong(tmp_path)
    a = _post(phone, "kreds", {}, created_at=1.0)
    b = _post(phone, "kreds", {}, created_at=2.0)
    s.ingest_message(a); s.ingest_message(b)
    ids = [m.msg_id for m in s.post_messages()]
    assert ids == [b.msg_id, a.msg_id]                   # newest first
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_store_scoped_posts.py -q`
Expected: FAIL — `post_messages` missing; routing not yet wrap-aware.

- [ ] **Step 3: Implement**

In `messages_not_in`, compute the peer's own device set once at the top of the method (after `out = []`):

```python
            peer_devices = set(self.load_views(peer_identity).keys())
```

Then add the post rule alongside the DM/ring rules (parse `wraps` from the row's payload):

```python
                if kind == KIND_POST:
                    wr = set(json.loads(mj)["payload"].get("wraps", {}))
                    if peer_identity != ipub and not (peer_devices & wr):
                        continue      # only the wrap-set audience + author
```

Add `post_messages`:

```python
    def post_messages(self, identity_pub: Optional[str] = None):
        with self._lock:
            if identity_pub is None:
                rows = self._db.execute(
                    "SELECT msg_json FROM messages WHERE kind=?"
                    " ORDER BY created_at DESC", (KIND_POST,))
            else:
                rows = self._db.execute(
                    "SELECT msg_json FROM messages WHERE kind=?"
                    " AND identity_pub=? ORDER BY created_at DESC",
                    (KIND_POST, identity_pub))
            return [SignedMessage.from_dict(json.loads(mj)) for (mj,) in rows]
```

(`SignedMessage` is already imported in store.py.)

- [ ] **Step 4: Run tests + full suite tail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_store_scoped_posts.py -q` — Expected: PASS.
Run: `timeout 120 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3` — still red only in post-authoring test files (fixed Tasks 5/8).

- [ ] **Step 5: Commit**

```powershell
git add hearth/store.py tests/test_store_scoped_posts.py
git commit -m "feat: scoped-post wrap-set routing + raw post_messages accessor"
```

---

### Task 4: Generalize the message-key cache to cover posts

**Files:**
- Modify: `hearth/store.py` (rename DM-scoped cache accessors to message-scoped; `uncached_message_ids` covers posts)
- Modify: `hearth/node.py` (rename call sites)
- Test: `tests/test_store_dm_keys.py` (update names), `tests/test_store_scoped_posts.py` (add coverage)

**Interfaces:**
- Consumes: Task 3 `post_messages`.
- Produces:
  - `Store.cache_message_key`, `cached_message_key`, `replace_message_key` (renamed from `*_dm_key`; msg_id-keyed, kind-agnostic).
  - `Store.uncached_message_ids(self_identity: str) -> List[str]` (renamed from `uncached_dm_ids`) — DM rows where self is a party **plus** post rows authored by self or whose `wraps` contains one of self's devices, with no cached key.

- [ ] **Step 1: Update the DM-key tests to the new names, add a post case**

In `tests/test_store_dm_keys.py`, rename `cache_dm_key`→`cache_message_key`, `cached_dm_key`→`cached_message_key`, `uncached_dm_ids`→`uncached_message_ids` throughout. In `tests/test_store_scoped_posts.py` append:

```python
def test_uncached_message_ids_includes_own_posts(tmp_path):
    s, phone = wong(tmp_path)
    p = _post(phone, "kreds", {}, created_at=1.0)      # authored by self
    s.ingest_message(p)
    assert p.msg_id in s.uncached_message_ids(phone.identity_pub)
    s.cache_message_key(p.msg_id, "cafe01")
    assert p.msg_id not in s.uncached_message_ids(phone.identity_pub)
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_store_dm_keys.py tests/test_store_scoped_posts.py -q`
Expected: FAIL — renamed methods don't exist yet.

- [ ] **Step 3: Implement the renames + post coverage**

In `hearth/store.py`, rename the three cache methods (`cache_dm_key`→`cache_message_key`, `cached_dm_key`→`cached_message_key`, `replace_dm_key`→`replace_message_key`) — bodies unchanged. Replace `uncached_dm_ids` with `uncached_message_ids` covering both kinds:

```python
    def uncached_message_ids(self, self_identity: str) -> List[str]:
        with self._lock:
            out = []
            for mid, kind, ipub, rcpt, mj in self._db.execute(
                    "SELECT msg_id, kind, identity_pub, recipient, msg_json"
                    " FROM messages WHERE kind IN (?,?)"
                    " AND msg_id NOT IN (SELECT msg_id FROM dm_keys)",
                    (KIND_DM, KIND_POST)):
                if kind == KIND_DM:
                    if self_identity in (ipub, rcpt):
                        out.append(mid)
                else:                                    # post
                    if ipub == self_identity:
                        out.append(mid); continue
                    my_devs = set(self.load_views(self_identity).keys())
                    if my_devs & set(json.loads(mj)["payload"].get("wraps", {})):
                        out.append(mid)
            return out
```

(The table stays named `dm_keys` — renaming the table is churn for no behavioral gain; the *methods* are what read as message-scoped. Note this in the method docstring.)

In `hearth/node.py`, rename call sites: `store.cache_dm_key`→`store.cache_message_key`, `store.cached_dm_key`→`store.cached_message_key`, `store.replace_dm_key`→`store.replace_message_key`, `store.uncached_dm_ids`→`store.uncached_message_ids`. (Node method renames come in Task 5.)

- [ ] **Step 4: Run tests + suite tail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_store_dm_keys.py tests/test_store_scoped_posts.py -q` — Expected: PASS.
Run the DM tests to confirm the node call-site renames didn't break them: `.venv\Scripts\python.exe -m pytest tests/test_node_dm.py -q` — the DM path should still pass (node internals still call the cache; only names changed). If `test_node_dm.py` references the old store names directly, update them.
Run: `timeout 120 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3`.

- [ ] **Step 5: Commit**

```powershell
git add hearth/store.py hearth/node.py tests/test_store_dm_keys.py tests/test_store_scoped_posts.py
git commit -m "refactor: generalize DM key cache to message-key cache (covers posts)"
```

---

### Task 5: Node — compose scoped posts, decrypt feed/profile, set_ring

**Files:**
- Modify: `hearth/node.py` (`compose_post`; `_content_key` generalization; `feed`/`posts_by` decrypt; `set_ring`; `cache_message_keys`; revocation wipe unaffected)
- Test: `tests/test_node_scoped_posts.py` (new); update `tests/test_node_dm.py` for the `_content_key` rename

**Interfaces:**
- Consumes: Task 1 `make_post`/`make_ring`/`post_aad`; Task 2 `rings`; Task 3 `post_messages`; Task 4 message-key cache.
- Produces:
  - `HearthNode.compose_post(text, scope="kreds", photos=(), expires_seconds=None) -> str`
  - `HearthNode.set_ring(member_identity, ring) -> str` (publishes a `ring` record; `ring` in {inner,kreds})
  - `HearthNode.feed() -> List[dict]` and `HearthNode.posts_by(identity_pub) -> List[dict]` return only decryptable posts, each `{msg_id, identity_pub, author_name, text, blobs, scope, created_at, expires_at, mine}`.
  - `_content_key(msg)` (renamed from `_dm_content_key`) works for both `dm` and `post` (AAD by kind); `cache_message_keys()` (renamed from `cache_dm_keys`) sweeps both.

- [ ] **Step 1: Write failing tests**

Create `tests/test_node_scoped_posts.py`:

```python
from hearth.node import HearthNode


def befriend_with_enckeys(a, b):
    a.store.add_identity(b.identity_pub); b.store.add_identity(a.identity_pub)
    a.ensure_enckey(); b.ensure_enckey()
    for src, dst in ((a, b), (b, a)):
        for m in src.store.messages_not_in({}, {src.identity_pub}, dst.identity_pub):
            dst.store.ingest_message(m)


def test_kreds_post_roundtrip_author_and_recipient(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    mid = wong.compose_post("hej alle", scope="kreds")
    assert [p["text"] for p in wong.feed()] == ["hej alle"]      # author reads
    for m in wong.store.messages_not_in({}, {wong.identity_pub}, freja.identity_pub):
        freja.store.ingest_message(m)
    assert [p["text"] for p in freja.feed()] == ["hej alle"]     # recipient reads
    assert freja.feed()[0]["scope"] == "kreds"


def test_inner_post_excludes_non_inner_friend(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mikkel = HearthNode.create(tmp_path / "m", "Mikkel", "mikkel-phone")
    befriend_with_enckeys(wong, freja); befriend_with_enckeys(wong, mikkel)
    wong.set_ring(freja.identity_pub, "inner")            # Freja inner; Mikkel not
    mid = wong.compose_post("kun inner", scope="inner")
    m = wong.store.get_message(mid)
    wraps = m.payload["wraps"]
    assert freja.device.device_pub in wraps               # Freja wrapped
    assert mikkel.device.device_pub not in wraps          # Mikkel excluded
    assert wong.device.device_pub in wraps                # author's own device


def test_ring_move_rekeys_future_only(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    wong.set_ring(freja.identity_pub, "inner")
    a = wong.store.get_message(wong.compose_post("inner 1", scope="inner"))
    wong.set_ring(freja.identity_pub, "kreds")            # demote
    b = wong.store.get_message(wong.compose_post("inner 2", scope="inner"))
    assert freja.device.device_pub in a.payload["wraps"]  # old post: still hers
    assert freja.device.device_pub not in b.payload["wraps"]  # new: excluded


def test_feed_hides_undecryptable(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    mikkel = HearthNode.create(tmp_path / "m", "Mikkel", "mikkel-phone")
    befriend_with_enckeys(wong, freja); befriend_with_enckeys(wong, mikkel)
    wong.set_ring(freja.identity_pub, "inner")
    mid = wong.compose_post("kun inner", scope="inner")
    m = wong.store.get_message(mid)
    mikkel.store.add_identity(wong.identity_pub)
    mikkel.store.ingest_message(m)                        # mikkel holds ciphertext
    assert mikkel.feed() == []                            # but cannot read it
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_node_scoped_posts.py -q`
Expected: FAIL — `compose_post` signature/`set_ring` missing, `feed` returns plaintext-shaped rows.

- [ ] **Step 3: Implement**

In `hearth/node.py`: extend the `dmcrypt` import with `post_aad`; extend the `messages` import with `make_ring`. Add ring helpers + rewrite `compose_post`, generalize the content-key path, rewrite `feed`/`posts_by`.

Replace `compose_post`:

```python
    def _scope_device_pubs(self, scope: str) -> dict:
        """device_pub -> enc_pub for every recipient of a post in `scope`
        (ring members) plus this node's own devices. Members whose enc
        keys we do not yet hold are skipped (best-effort, like DMs)."""
        rings = self.store.rings(self.identity_pub)
        friends = [i for i in self.store.known_identities()
                   if i != self.identity_pub]
        if scope == "inner":
            members = [i for i in friends if rings.get(i) == "inner"]
        else:
            members = friends                       # kreds = all friends
        pubs = {}
        for ident in members:
            pubs.update(self.store.enckeys(ident))
        mine = self.store.enckeys(self.identity_pub)
        mine[self.device.device_pub] = self.device.enc_pub
        pubs.update(mine)
        return pubs

    def compose_post(self, text: str, scope: str = "kreds",
                     photos=(), expires_seconds=None) -> str:
        if scope not in ("inner", "kreds"):
            raise ValueError("scope must be inner or kreds")
        pubs = self._scope_device_pubs(scope)
        created_at = time.time()
        expires_at = (created_at + expires_seconds
                      if expires_seconds is not None else None)
        aad = post_aad(self.identity_pub, scope, created_at)
        key = new_content_key()
        refs = [self.store.put_blob(encrypt_blob(key, p)) for p in photos]
        nonce, ct = encrypt_body(key, {"text": text, "blobs": refs}, aad)
        wraps = wrap_key(key, pubs, aad)
        mid = self._publish(make_post(self.device, scope, nonce, ct, wraps,
                                      refs, created_at, expires_at))
        self._cache_message_key(mid, key)
        return mid

    def set_ring(self, member_identity: str, ring: str) -> str:
        if ring not in ("inner", "kreds"):
            raise ValueError("ring must be inner or kreds")
        if not self.store.is_known(member_identity):
            raise ValueError("not a friend")
        return self._publish(make_ring(self.device, member_identity, ring))
```

Rename `_dm_content_key`→`_content_key` and make the AAD kind-aware:

```python
    def _content_key(self, msg):
        p = msg.payload
        kind = p["kind"]
        if kind == KIND_DM:
            aad = dm_aad(msg.cert.identity_pub, p["to"], p["created_at"])
        else:                                        # post
            aad = post_aad(msg.cert.identity_pub, p["scope"], p["created_at"])
        sealed = self.store.cached_message_key(msg.msg_id)
        if sealed is not None and self.device.storage_key is not None:
            k = open_content_key(self.device.storage_key, msg.msg_id, sealed)
            if k is not None:
                return k, aad
        for priv in self.device.enc_privs():
            k = unwrap_key(p["wraps"], self.device.device_pub, priv, aad)
            if k is not None:
                self._cache_message_key(msg.msg_id, k)
                return k, aad
        return None, aad
```

Rename `_cache_dm_key`→`_cache_message_key`, `cache_dm_keys`→`cache_message_keys` (bodies unchanged except `uncached_dm_ids`→`uncached_message_ids`), and update `dm_thread`/`dm_blob` to call `_content_key`. (The DM tests from Task 4 already expect these renames.)

Rewrite `feed` and add `posts_by` decrypting via `store.post_messages` (import `KIND_DM` is already present; add a decrypt-and-assemble helper):

```python
    def _decrypt_post_row(self, msg, names, now):
        p = msg.payload
        if p.get("expires_at") is not None and p["expires_at"] <= now:
            return None
        key, aad = self._content_key(msg)
        if key is None:
            return None                              # not for this device
        body = decrypt_body(key, p["body_nonce"], p["body_ct"], aad)
        if body is None:
            return None
        ipub = msg.cert.identity_pub
        return {
            "msg_id": msg.msg_id, "identity_pub": ipub,
            "author_name": names.get(ipub, ipub[:8]),
            "text": body["text"], "blobs": body["blobs"],
            "scope": p["scope"], "created_at": p["created_at"],
            "expires_at": p.get("expires_at"),
            "mine": ipub == self.identity_pub,
        }

    def feed(self):
        now = time.time()
        names = self.store.profiles()
        out = []
        for msg in self.store.post_messages():
            row = self._decrypt_post_row(msg, names, now)
            if row is not None:
                out.append(row)
        return out

    def posts_by(self, identity_pub: str):
        now = time.time()
        names = self.store.profiles()
        out = []
        for msg in self.store.post_messages(identity_pub):
            row = self._decrypt_post_row(msg, names, now)
            if row is not None:
                out.append(row)
        return out
```

(Delete the old plaintext `feed` body. `decrypt_body` is imported already for DMs; confirm it's in the `dmcrypt` import line and add if missing.)

- [ ] **Step 4: Update the DM tests for the renames, then run**

In `tests/test_node_dm.py`, rename any references `_dm_content_key`→`_content_key`, `cache_dm_keys`→`cache_message_keys`, `_cache_dm_key`→`_cache_message_key`.
Run: `.venv\Scripts\python.exe -m pytest tests/test_node_scoped_posts.py tests/test_node_dm.py -q` — Expected: PASS.
Run: `timeout 120 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -6` — remaining failures should now be only `test_api.py`/`test_store_ingest.py`/`test_three_nodes.py`/`test_gossip_loop.py`/demo (Task 8 territory) that call the old `make_post`/`compose_post`.

- [ ] **Step 5: Commit**

```powershell
git add hearth/node.py tests/test_node_scoped_posts.py tests/test_node_dm.py
git commit -m "feat: node scoped-post compose/decrypt + set_ring + message-key rename"
```

---

### Task 6: API + minimal UI wiring

**Files:**
- Modify: `hearth/api.py` (`/api/post` takes `scope`; `/api/feed` already calls `node.feed()`; add `/api/ring`; add `/api/post-blob/{msg_id}/{h}`)
- Modify: `hearth/web/app.js` (scope selector on the composer; pass scope; render scope label; post photos via post-blob)
- Test: `tests/test_api_scoped_posts.py` (new); update `tests/test_api.py`

**Interfaces:**
- Consumes: Task 5 `compose_post(text, scope, ...)`, `set_ring`, `feed`, `dm_blob`-style decryption.
- Produces: `POST /api/post` form field `scope` (default `kreds`); `POST /api/ring` JSON `{identity_pub, ring}`; `GET /api/post-blob/{msg_id}/{h}` decrypts a post's photo server-side-locally (mirrors `/api/dm-blob`).

- [ ] **Step 1: Write failing tests**

Create `tests/test_api_scoped_posts.py` modelled on `tests/test_api.py`'s client setup (read that file first for the fixture). Assert: posting with `scope=inner` then `GET /api/feed` returns the post with `scope=="inner"`; `POST /api/ring` moves a friend and a subsequent inner post's stored wraps reflect it; `POST /api/post` with no scope defaults to `kreds`. In `tests/test_api.py`, update the existing post test to pass/expect `scope`.

```python
def test_post_with_scope_and_ring(client_and_node):    # adapt to test_api fixture
    c, node = client_and_node
    assert c.post("/api/post", data={"text": "hi", "scope": "kreds"}).status_code == 200
    feed = c.get("/api/feed").json()
    assert feed[0]["text"] == "hi" and feed[0]["scope"] == "kreds"
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_api_scoped_posts.py -q`
Expected: FAIL — `scope` not accepted / `/api/ring` missing.

- [ ] **Step 3: Implement**

In `hearth/api.py`, update `/api/post` to accept `scope` and add the endpoints:

```python
    @app.post("/api/post")
    async def post(text: str = Form(""), scope: str = Form("kreds"),
                   expires_seconds: str = Form(""),
                   photos: List[UploadFile] = File(default=[])):
        blobs = []
        for up in photos:
            data = await up.read()
            if len(data) > MAX_BLOB_BYTES:
                raise HTTPException(413, "photo exceeds 5 MB cap")
            blobs.append(data)
        expiry = float(expires_seconds) if expires_seconds.strip() else None
        mid = _400(lambda: node.compose_post(text, scope, blobs, expiry))
        return {"msg_id": mid}

    @app.post("/api/ring")
    async def ring(body: dict = Body(...)):
        _400(lambda: node.set_ring(body["identity_pub"], body["ring"]))
        return {"ok": True}

    @app.get("/api/post-blob/{msg_id}/{h}")
    async def post_blob(msg_id: str, h: str):
        data = node.post_blob(msg_id, h)
        if data is None:
            raise HTTPException(404, "not found")
        return Response(content=data, media_type="application/octet-stream")
```

Add `node.post_blob` in `hearth/node.py` (mirror `dm_blob`):

```python
    def post_blob(self, msg_id: str, h: str):
        msg = self.store.get_message(msg_id)
        if msg is None or msg.payload.get("kind") != KIND_POST:
            return None
        key, _ = self._content_key(msg)
        data = self.store.get_blob(h)
        if key is None or data is None:
            return None
        return decrypt_blob(key, data)
```

(Ensure `Response`, `Body` are imported in api.py — they are, used by other endpoints.)

In `hearth/web/app.js`: add a two-option scope selector (Inner / Kreds) to the composer, default Kreds; send `scope` in the `/api/post` form; render each feed entry's `scope` as a small label; point post images at `/api/post-blob/{msg_id}/{hash}` instead of `/api/blob/{hash}`. Keep it minimal — the full v3 visual treatment is the next slice. (Read the current composer + feed render in `app.js` and make the smallest change that wires scope through.)

- [ ] **Step 4: Run tests + `node --check` + suite tail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_api_scoped_posts.py tests/test_api.py -q` — Expected: PASS.
Run: `node --check hearth/web/app.js` — Expected: clean.
Run: `timeout 120 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -6`.

- [ ] **Step 5: Commit**

```powershell
git add hearth/api.py hearth/node.py hearth/web/app.js tests/test_api_scoped_posts.py tests/test_api.py
git commit -m "feat: scoped-post API (scope form, /api/ring, /api/post-blob) + minimal UI wiring"
```

---

### Task 7: Integration tests — structural scope assertions over real sockets

**Files:**
- Create: `tests/test_scoped_posts_e2e.py`
- Update: any remaining post-authoring integration tests broken by the signature change (`tests/test_three_nodes.py`, `tests/test_gossip_loop.py`, `tests/test_sync_session.py` if they compose posts) — update to `compose_post(text, scope=...)`.

**Interfaces:** consumes everything above; the structural proof mirrors the DM mutual-observer test.

- [ ] **Step 1: Write the integration tests**

Create `tests/test_scoped_posts_e2e.py`, modelled on `tests/test_sync_dm.py` (real `SyncService`, `started()` helper, `befriend`). Assert:

```python
# 1) Kreds post reaches every friend's node.
# 2) Inner post: an inner member's node receives + decrypts it; a
#    non-inner friend's node NEVER receives the row (get_message is None) --
#    the structural "never receive it" claim, like the DM mutual-observer test.
# 3) Ring move re-keys future only over real sync: friend demoted between
#    two inner posts holds the first, never receives the second.
```

Concretely (fill in with the `started()`/`befriend` helpers copied from `test_sync_dm.py`):

```python
def test_inner_post_never_reaches_non_inner_over_sync(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        mikkel = HearthNode.create(tmp_path / "m", "Mikkel", "mikkel-phone")
        befriend(wong, freja); befriend(wong, mikkel)
        for n in (wong, freja, mikkel):
            n.ensure_enckey()
        sw, wa = await started(wong); sf, fa = await started(freja)
        sm, ma = await started(mikkel)
        await sw.sync_with(fa); await sw.sync_with(ma)     # exchange enckeys
        await sf.sync_with(wa); await sm.sync_with(wa)
        wong.set_ring(freja.identity_pub, "inner")
        mid = wong.compose_post("kun inner", scope="inner")
        await sw.sync_with(fa); await sw.sync_with(ma)
        assert [p["text"] for p in freja.feed()] == ["kun inner"]   # inner sees
        assert mikkel.store.get_message(mid) is None                # never got row
        for s in (sw, sf, sm): await s.stop()
    asyncio.run(scenario())
```

- [ ] **Step 2: Run + fix stragglers**

Run: `.venv\Scripts\python.exe -m pytest tests/test_scoped_posts_e2e.py -q` — Expected: PASS.
Update any other integration test that composes posts to the new signature. Run: `timeout 120 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -6`.

- [ ] **Step 3: Commit**

```powershell
git add tests/
git commit -m "test: scoped-post structural assertions over real sockets"
```

---

### Task 8: Retire plaintext posts everywhere — demo, remaining tests, docs, full green

**Files:**
- Modify: `hearth/demo.py` (cast posts get scopes; ensure enckeys exchanged before the seeded posts so wraps cover recipients)
- Modify: `tests/test_store_ingest.py` and any other tests still calling old `make_post` (update to scoped `make_post`, or switch to `node.compose_post`)
- Modify: `README.md` (Kreds scopes note)

**Interfaces:** none produced; this task drives the whole suite green and updates the demo/docs.

- [ ] **Step 1: Sweep remaining old-signature callers**

Search and fix every remaining caller of the old `make_post(device, text, ...)`:

```powershell
Select-String -Path tests\*.py,hearth\*.py -Pattern "make_post\(" | Select-String -NotMatch "scope"
```

For store-level tests that only need a post row, use the scoped signature with `wraps={}` (a post nobody can decrypt is fine for pure-store tests that assert on rows, not decryption). For behavior tests, prefer `node.compose_post(text, scope=...)`.

- [ ] **Step 2: Update the demo cast**

In `hearth/demo.py`, the seeded posts become scoped. The cast already runs one enckey-exchange sync round before seeding the DM; ensure posts are seeded **after** that round so `compose_post`'s `wraps` cover Freja/Wong. Give the welcome posts `scope="kreds"`. Example (adapt to current demo structure):

```python
    freja.compose_post("Hej Wong! Fint at være her.", scope="kreds")
    wong.compose_post("Velkommen til Kreds, Freja.", scope="kreds")
```

- [ ] **Step 3: README note**

Add under a "Posts and scopes" heading: posts are now encrypted to a ring — **Inner** (a chosen subset) or **Kreds** (all your friends) — so a post exists only on its audience's devices; there is no plaintext post and no universal wall. State the honest audience-enumeration boundary in one line (recipients can see which devices a post went to).

- [ ] **Step 4: Full suite green under timeout**

Run: `timeout 180 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3` — Expected: all pass (a TOR_E2E skip is fine). If any post-authoring test remains red, fix it here.

- [ ] **Step 5: Demo smoke**

```powershell
Remove-Item -Recurse -Force run -ErrorAction SilentlyContinue
.venv\Scripts\python.exe -c "import hearth.demo"   # import clean
```

(Optionally run `python -m hearth demo` briefly to confirm the cast builds and the feed shows the seeded posts; Ctrl+C to stop.)

- [ ] **Step 6: Commit**

```powershell
git add hearth/demo.py hearth/ tests/ README.md
git commit -m "feat: retire plaintext posts - scoped cast, tests, docs; suite green"
```

---

## Completion

After Task 8: whole-branch review (superpowers:requesting-code-review) on the most capable model — reviewer should scrutinize the wrap-set routing (no non-member ever receives a row; author always does), the ring records' own-device-only routing (inner membership never leaks to a friend), the feed/profile decrypt-and-filter (no undecryptable ciphertext leaks into any view), forward-secrecy inheritance (posts survive enc-key rotation via the message-key cache), and that plaintext posts are fully gone. Then superpowers:finishing-a-development-branch — merge `kreds-scoped-posts` to `main`, push. The next Kreds slice is the rename + v3 visual reskin.
