# DM Forward Secrecy (Slow Ratchet) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Windowed forward secrecy for DMs — daily enckey rotation with permanent deletion of retired keys after a grace window, plus a local encrypted content-key cache so message history survives key deletion.

**Architecture:** Zero protocol change. Devices rotate their static X25519 enckey and publish it through the existing latest-wins `enckey` message kind; decryption tries the current key then retained retired keys; retired private keys are deleted after grace (that deletion IS the forward secrecy). A new `dm_keys` table caches each DM's content key encrypted under a per-device storage key so history stays displayable. Rotation/prune runs from the existing gossip loop.

**Tech Stack:** Python 3.12 (`.venv`), `cryptography` (X25519 + ChaCha20-Poly1305, already a dependency), sqlite3, pytest.

**Spec:** `docs/superpowers/specs/2026-07-03-hearth-dm-forward-secrecy-design.md`

## Global Constraints

- Branch: `dm-forward-secrecy` off `main`. One workstream; nothing unrelated.
- Constants, verbatim from spec: `ENC_ROTATION_PERIOD = 24 * 3600.0` (24h), `ENC_GRACE = 7 * 24 * 3600.0` (7 days). Module-level in `hearth/identity.py`, overridable in tests via explicit `now` arguments (no monkeypatching of time needed).
- **Grace clock starts at retirement** (when a new key replaces the old), never at creation.
- **Zero envelope/protocol change:** no new message kinds, no new payload fields, no `validate_payload` changes, no sync changes beyond one call in the gossip loop. A v0.1 peer must still interoperate.
- Honesty rule (docs): never describe this as per-message forward secrecy; always "windowed". Never claim protection against a thief holding an unlocked device.
- Test runner: `.venv\Scripts\python.exe -m pytest tests -q`. Full suite green at every commit (169 tests exist at branch point).
- ASCII only in console output (cp1252). Existing `run/` demo casts stay compatible (new keys.json fields default via `.get`; new table is `IF NOT EXISTS`) — no demo changes.

---

### Task 1: DeviceKeys — retired keys, storage key, rotate/prune

**Files:**
- Modify: `hearth/identity.py` (constants near top; `DeviceKeys.__init__` at ~182; `to_json`/`from_json` at ~250-270; new methods after `sign_raw`)
- Test: `tests/test_key_rotation.py` (new file)

**Interfaces:**
- Consumes: existing `_gen_x25519_pair()` in `identity.py`.
- Produces (later tasks rely on these exact names):
  - `ENC_ROTATION_PERIOD: float`, `ENC_GRACE: float` (module constants)
  - `DeviceKeys.retired_enc: list[dict]` — entries `{"enc_priv": str, "enc_pub": str, "retired_at": float}`
  - `DeviceKeys.storage_key: Optional[str]` — 64-hex (32 bytes), auto-generated when absent
  - `DeviceKeys.rotate_enc(now: Optional[float] = None) -> None`
  - `DeviceKeys.prune_retired(now: Optional[float] = None) -> bool` (True if anything was deleted)
  - `DeviceKeys.enc_privs() -> List[str]` (current first, then retired)

- [ ] **Step 1: Create the branch**

```powershell
git checkout main; git pull; git checkout -b dm-forward-secrecy
```

- [ ] **Step 2: Write the failing tests**

Create `tests/test_key_rotation.py`:

```python
from hearth.identity import DeviceKeys, ENC_GRACE


def test_rotate_enc_retires_current_and_generates_fresh():
    d = DeviceKeys.create("phone")
    old_priv, old_pub = d.enc_priv, d.enc_pub
    d.rotate_enc(now=1000.0)
    assert (d.enc_priv, d.enc_pub) != (old_priv, old_pub)
    assert d.retired_enc == [{"enc_priv": old_priv, "enc_pub": old_pub,
                              "retired_at": 1000.0}]


def test_prune_retired_deletes_only_past_grace():
    d = DeviceKeys.create("phone")
    d.rotate_enc(now=0.0)                    # retired at t=0
    d.rotate_enc(now=ENC_GRACE)              # retired at t=GRACE
    # t=0 entry is exactly at the boundary (age == GRACE, kept)
    assert len(d.retired_enc) == 2
    assert d.prune_retired(now=ENC_GRACE + 1.0) is True
    assert [r["retired_at"] for r in d.retired_enc] == [ENC_GRACE]
    assert d.prune_retired(now=ENC_GRACE + 1.0) is False   # idempotent


def test_enc_privs_current_first_then_retired():
    d = DeviceKeys.create("phone")
    first = d.enc_priv
    d.rotate_enc(now=1.0)
    second = d.enc_priv
    d.rotate_enc(now=2.0)
    assert d.enc_privs() == [d.enc_priv, first, second]


def test_keys_json_roundtrip_and_legacy_load():
    d = DeviceKeys.create("phone")
    d.rotate_enc(now=5.0)
    j = d.to_json()
    d2 = DeviceKeys.from_json(j)
    assert d2.retired_enc == d.retired_enc
    assert d2.storage_key == d.storage_key
    # legacy keys.json (v0.1, no rotation fields) still loads and gains
    # fresh defaults
    legacy = {k: v for k, v in j.items()
              if k not in ("retired_enc", "storage_key")}
    d3 = DeviceKeys.from_json(legacy)
    assert d3.retired_enc == []
    assert isinstance(d3.storage_key, str) and len(d3.storage_key) == 64
```

- [ ] **Step 3: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_key_rotation.py -v`
Expected: FAIL — `ImportError: cannot import name 'ENC_GRACE'`.

- [ ] **Step 4: Implement**

In `hearth/identity.py`:

(a) Ensure `import os` is present in the import block (add it if absent).

(b) Add module constants directly above the `class DeviceKeys:` definition:

```python
ENC_ROTATION_PERIOD = 24 * 3600.0    # rotate the DM enc key daily
ENC_GRACE = 7 * 24 * 3600.0          # retired enc keys die after 7 days
```

(c) Extend `DeviceKeys.__init__` — add two trailing parameters and body lines (existing parameters unchanged):

```python
    def __init__(self, name: str, device_priv: Ed25519PrivateKey,
                 cert: Optional[EnrollmentCert] = None,
                 identity_priv: Optional[Ed25519PrivateKey] = None,
                 seq: int = 0,
                 enc_priv: Optional[str] = None,
                 enc_pub: Optional[str] = None,
                 retired_enc: Optional[list] = None,
                 storage_key: Optional[str] = None):
```

and at the end of the body (after `self.enc_pub = enc_pub`):

```python
        self.retired_enc = list(retired_enc or [])
        if storage_key is None:
            storage_key = os.urandom(32).hex()
        self.storage_key = storage_key
```

(d) Add methods after `sign_raw`:

```python
    def rotate_enc(self, now: Optional[float] = None):
        """Retire the current enc keypair and generate a fresh one.
        The grace clock starts NOW (retirement), not at key creation."""
        now = now if now is not None else time.time()
        self.retired_enc.append({
            "enc_priv": self.enc_priv, "enc_pub": self.enc_pub,
            "retired_at": now,
        })
        self.enc_priv, self.enc_pub = _gen_x25519_pair()
        self.prune_retired(now)

    def prune_retired(self, now: Optional[float] = None) -> bool:
        """Permanently delete retired keys past grace. This deletion IS
        the forward secrecy — never widen the retention window."""
        now = now if now is not None else time.time()
        keep = [r for r in self.retired_enc
                if now - r["retired_at"] <= ENC_GRACE]
        changed = len(keep) != len(self.retired_enc)
        self.retired_enc = keep
        return changed

    def enc_privs(self) -> list:
        """Decryption candidates: current key first, then retired."""
        out = []
        if self.enc_priv is not None:
            out.append(self.enc_priv)
        out.extend(r["enc_priv"] for r in self.retired_enc
                   if r.get("enc_priv"))
        return out
```

(e) In `to_json`, add two entries to the returned dict:

```python
            "retired_enc": self.retired_enc,
            "storage_key": self.storage_key,
```

(f) In `from_json`, add two trailing arguments to the `DeviceKeys(...)` call:

```python
            d.get("retired_enc"), d.get("storage_key"),
```

- [ ] **Step 5: Run the new tests, then the full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_key_rotation.py -v` — Expected: 4 PASS.
Run: `.venv\Scripts\python.exe -m pytest tests -q` — Expected: ALL PASS.

- [ ] **Step 6: Commit**

```powershell
git add hearth/identity.py tests/test_key_rotation.py
git commit -m "feat: enc-key rotation with grace-window deletion in DeviceKeys"
```

---

### Task 2: dmcrypt — seal/open content keys under the storage key

**Files:**
- Modify: `hearth/dmcrypt.py` (new AAD constant + two functions at the end)
- Test: `tests/test_dmcrypt.py` (append)

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `KEYCACHE_AAD: bytes`
  - `seal_content_key(storage_key_hex: str, msg_id: str, key: bytes) -> str` (hex: 12-byte nonce || ciphertext)
  - `open_content_key(storage_key_hex: str, msg_id: str, sealed_hex: str) -> Optional[bytes]` (None on any failure)

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_dmcrypt.py` (add `import os` to its imports if absent, and extend the `hearth.dmcrypt` import list with `seal_content_key, open_content_key`):

```python
def test_content_key_seal_open_roundtrip():
    sk = os.urandom(32).hex()
    key = os.urandom(32)
    sealed = seal_content_key(sk, "aa" * 32, key)
    assert open_content_key(sk, "aa" * 32, sealed) == key


def test_content_key_seal_binds_msg_id():
    sk = os.urandom(32).hex()
    sealed = seal_content_key(sk, "aa" * 32, os.urandom(32))
    assert open_content_key(sk, "bb" * 32, sealed) is None


def test_content_key_wrong_storage_key_or_garbage_rejected():
    sk = os.urandom(32).hex()
    sealed = seal_content_key(sk, "aa" * 32, os.urandom(32))
    assert open_content_key(os.urandom(32).hex(), "aa" * 32,
                            sealed) is None
    assert open_content_key(sk, "aa" * 32, "00ff") is None
    assert open_content_key(sk, "aa" * 32, "zz-not-hex") is None
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_dmcrypt.py -v`
Expected: the three new tests FAIL with ImportError; existing tests PASS.

- [ ] **Step 3: Implement**

Append to `hearth/dmcrypt.py`:

```python
KEYCACHE_AAD = b"hearth/dm-keycache/v1/"


def seal_content_key(storage_key_hex: str, msg_id: str,
                     key: bytes) -> str:
    """Encrypt a DM content key for the local dm_keys cache. AAD binds
    the msg_id so a cached key cannot be transplanted between rows."""
    nonce = os.urandom(12)
    ct = ChaCha20Poly1305(bytes.fromhex(storage_key_hex)).encrypt(
        nonce, key, KEYCACHE_AAD + msg_id.encode())
    return (nonce + ct).hex()


def open_content_key(storage_key_hex: str, msg_id: str,
                     sealed_hex: str) -> Optional[bytes]:
    try:
        data = bytes.fromhex(sealed_hex)
        if len(data) < 13:
            return None
        return ChaCha20Poly1305(bytes.fromhex(storage_key_hex)).decrypt(
            data[:12], data[12:], KEYCACHE_AAD + msg_id.encode())
    except (InvalidTag, ValueError):
        return None
```

- [ ] **Step 4: Run tests, then full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_dmcrypt.py -v` — Expected: ALL PASS.
Run: `.venv\Scripts\python.exe -m pytest tests -q` — Expected: ALL PASS.

- [ ] **Step 5: Commit**

```powershell
git add hearth/dmcrypt.py tests/test_dmcrypt.py
git commit -m "feat: seal/open DM content keys under a device storage key"
```

---

### Task 3: Store — dm_keys table, cache queries, tombstone hygiene, enckey tiebreak

**Files:**
- Modify: `hearth/store.py` (`_SCHEMA`; `_tombstone` at ~191; `wipe_all` table list; `enckeys` at ~410 becomes a wrapper over new `enckey_records`)
- Test: `tests/test_store_dm_keys.py` (new file)

**Interfaces:**
- Consumes: Task 1's `DeviceKeys.rotate_enc` (test only).
- Produces:
  - `Store.cache_dm_key(msg_id: str, sealed_hex: str) -> None`
  - `Store.cached_dm_key(msg_id: str) -> Optional[str]`
  - `Store.uncached_dm_ids(self_identity: str) -> List[str]`
  - `Store.enckey_records(identity_pub: str) -> Dict[str, Tuple[float, str]]` — device_pub -> (created_at, enc_pub), latest-wins by `(created_at, seq)`
  - `Store.enckeys(...)` behavior unchanged in shape (device_pub -> enc_pub), now tie-broken by seq
  - `_tombstone` also deletes the msg_id's `dm_keys` row (delete tags, expiry sweep, and retro-drop all route through it)

- [ ] **Step 1: Write the failing tests**

Create `tests/test_store_dm_keys.py`:

```python
from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import make_delete, make_dm, make_enckey
from hearth.store import Store


def wong(tmp_path):
    phone = DeviceKeys.create("wong-phone")
    IdentityCeremony().enroll_first_device(phone)
    s = Store(tmp_path / "w.db")
    s.add_identity(phone.identity_pub, is_self=True)
    return s, phone


def friend_of(s):
    dev = DeviceKeys.create("freja-phone")
    IdentityCeremony().enroll_first_device(dev)
    s.add_identity(dev.identity_pub)
    return dev


def dm_to(phone, to_identity):
    return make_dm(phone, to_identity, body_nonce="ab" * 12,
                   body_ct="deadbeef", wraps={}, created_at=100.0)


def test_dm_key_cache_roundtrip(tmp_path):
    s, phone = wong(tmp_path)
    s.cache_dm_key("aa" * 32, "cafe01")
    assert s.cached_dm_key("aa" * 32) == "cafe01"
    assert s.cached_dm_key("bb" * 32) is None


def test_uncached_dm_ids_lists_only_uncached_self_dms(tmp_path):
    s, phone = wong(tmp_path)
    freja = friend_of(s)
    d1 = dm_to(phone, freja.identity_pub)
    d2 = dm_to(phone, freja.identity_pub)
    assert s.ingest_message(d1).accepted
    assert s.ingest_message(d2).accepted
    assert set(s.uncached_dm_ids(phone.identity_pub)) \
        == {d1.msg_id, d2.msg_id}
    s.cache_dm_key(d1.msg_id, "cafe01")
    assert s.uncached_dm_ids(phone.identity_pub) == [d2.msg_id]


def test_deleted_dm_drops_cached_key(tmp_path):
    s, phone = wong(tmp_path)
    freja = friend_of(s)
    d1 = dm_to(phone, freja.identity_pub)
    assert s.ingest_message(d1).accepted
    s.cache_dm_key(d1.msg_id, "cafe01")
    assert s.ingest_message(make_delete(phone, d1.msg_id)).accepted
    assert s.cached_dm_key(d1.msg_id) is None


def test_expired_dm_drops_cached_key(tmp_path):
    s, phone = wong(tmp_path)
    freja = friend_of(s)
    d = make_dm(phone, freja.identity_pub, body_nonce="ab" * 12,
                body_ct="deadbeef", wraps={}, created_at=100.0,
                expires_at=200.0)
    assert s.ingest_message(d).accepted
    s.cache_dm_key(d.msg_id, "cafe01")
    assert s.sweep_expired(now=201.0) == [d.msg_id]
    assert s.cached_dm_key(d.msg_id) is None


def test_enckeys_tiebreak_same_created_at_higher_seq_wins(tmp_path):
    s, phone = wong(tmp_path)
    e1 = make_enckey(phone, now=100.0)
    phone.rotate_enc(now=100.0)
    e2 = make_enckey(phone, now=100.0)       # same created_at, higher seq
    assert s.ingest_message(e1).accepted
    assert s.ingest_message(e2).accepted
    assert s.enckeys(phone.identity_pub)[phone.device_pub] == phone.enc_pub
    assert s.enckey_records(phone.identity_pub)[phone.device_pub] \
        == (100.0, phone.enc_pub)
    # out-of-order arrival gives the same answer
    s2 = Store(tmp_path / "w2.db")
    s2.add_identity(phone.identity_pub, is_self=True)
    assert s2.ingest_message(e2).accepted
    assert s2.ingest_message(e1).accepted
    assert s2.enckeys(phone.identity_pub)[phone.device_pub] \
        == phone.enc_pub
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_store_dm_keys.py -v`
Expected: FAIL — `AttributeError: 'Store' object has no attribute 'cache_dm_key'` (and enckey_records missing).

- [ ] **Step 3: Implement**

(a) In `_SCHEMA` (end of the string, after the `idx_delete_guard` index):

```sql
CREATE TABLE IF NOT EXISTS dm_keys(
  msg_id TEXT PRIMARY KEY, sealed_key TEXT NOT NULL);
```

(b) In `_tombstone`, after the `DELETE FROM messages` line:

```python
        self._db.execute("DELETE FROM dm_keys WHERE msg_id=?", (msg_id,))
```

(c) In `wipe_all`, add `"dm_keys"` to the table tuple.

(d) In the DM-support section, replace the body of `enckeys` and add the new methods (Tuple is already imported in the file's typing imports; add it if not):

```python
    def enckey_records(self, identity_pub: str) -> Dict[str, tuple]:
        """device_pub -> (created_at, enc_pub) for non-revoked devices,
        latest-wins by (created_at, seq) — rotation makes same-timestamp
        ties realistic, so bare created_at is not enough."""
        with self._lock:
            revoked = {dpub for dpub, v in
                       self.load_views(identity_pub).items()
                       if v.revocation is not None}
            best: Dict[str, tuple] = {}
            for dpub, seq, mj in self._db.execute(
                    "SELECT device_pub, seq, msg_json FROM messages"
                    " WHERE kind=? AND identity_pub=?",
                    (KIND_ENCKEY, identity_pub)):
                if dpub in revoked:
                    continue
                p = json.loads(mj)["payload"]
                rank = (p["created_at"], seq)
                if dpub not in best or rank > best[dpub][0]:
                    best[dpub] = (rank, p["enc_pub"])
            return {d: (rank[0], pub) for d, (rank, pub) in best.items()}

    def enckeys(self, identity_pub: str) -> Dict[str, str]:
        return {d: pub for d, (_, pub) in
                self.enckey_records(identity_pub).items()}

    def cache_dm_key(self, msg_id: str, sealed_hex: str):
        with self._lock:
            self._db.execute("INSERT OR IGNORE INTO dm_keys VALUES(?,?)",
                             (msg_id, sealed_hex))
            self._db.commit()

    def cached_dm_key(self, msg_id: str) -> Optional[str]:
        with self._lock:
            row = self._db.execute(
                "SELECT sealed_key FROM dm_keys WHERE msg_id=?",
                (msg_id,)).fetchone()
            return row[0] if row else None

    def uncached_dm_ids(self, self_identity: str) -> List[str]:
        with self._lock:
            return [mid for (mid,) in self._db.execute(
                "SELECT msg_id FROM messages WHERE kind=?"
                " AND (identity_pub=? OR recipient=?)"
                " AND msg_id NOT IN (SELECT msg_id FROM dm_keys)",
                (KIND_DM, self_identity, self_identity))]
```

(The old `enckeys` body is deleted; its behavior is preserved through `enckey_records`.)

- [ ] **Step 4: Run tests, then full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_store_dm_keys.py -v` — Expected: 5 PASS.
Run: `.venv\Scripts\python.exe -m pytest tests -q` — Expected: ALL PASS.

- [ ] **Step 5: Commit**

```powershell
git add hearth/store.py tests/test_store_dm_keys.py
git commit -m "feat: dm_keys content-key cache + (created_at, seq) enckey tiebreak"
```

---

### Task 4: Node — cached-first decryption, retired-key fallback, sweep, wipe

**Files:**
- Modify: `hearth/node.py` (imports; `_dm_content_key` at ~361; `compose_dm` at ~343; new `_cache_dm_key` + `cache_dm_keys`; `enter_revoked_state` at ~415)
- Test: `tests/test_node_dm.py` (append)

**Interfaces:**
- Consumes: Task 1 `enc_privs()/rotate_enc()/storage_key`; Task 2 `seal_content_key/open_content_key`; Task 3 `cache_dm_key/cached_dm_key/uncached_dm_ids`.
- Produces:
  - `HearthNode._cache_dm_key(msg_id: str, key: bytes) -> None` (no-op without storage key)
  - `HearthNode._dm_content_key(msg) -> (Optional[bytes], bytes)` — cache first, then current+retired unwrap (caching on success); same signature as today
  - `HearthNode.cache_dm_keys() -> None` — eager sweep over uncached self-DMs
  - `compose_dm` caches its own content key immediately
  - `enter_revoked_state` clears `retired_enc` and `storage_key`

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_node_dm.py`:

```python
def test_compose_caches_own_content_key(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    mid = wong.compose_dm(freja.identity_pub, "cached at send")
    assert wong.store.cached_dm_key(mid) is not None


def test_recipient_caches_on_first_read_then_survives_key_loss(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    mid = wong.compose_dm(freja.identity_pub, "husk mig")
    for m in wong.store.messages_not_in({}, {wong.identity_pub},
                                        freja.identity_pub):
        freja.store.ingest_message(m)
    assert freja.store.cached_dm_key(mid) is None
    assert freja.dm_thread(wong.identity_pub)[0]["text"] == "husk mig"
    assert freja.store.cached_dm_key(mid) is not None      # cached on read
    # total envelope-key loss: history still displays via the cache
    freja.device.enc_priv = None
    freja.device.retired_enc = []
    assert freja.dm_thread(wong.identity_pub)[0]["text"] == "husk mig"


def test_decrypts_via_retired_key_after_rotation(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    mid = wong.compose_dm(freja.identity_pub, "foer rotation")
    for m in wong.store.messages_not_in({}, {wong.identity_pub},
                                        freja.identity_pub):
        freja.store.ingest_message(m)
    freja.device.rotate_enc(now=1000.0)     # BEFORE any read: no cache yet
    thread = freja.dm_thread(wong.identity_pub)
    assert thread[0]["text"] == "foer rotation"
    assert thread[0]["undecryptable"] is False


def test_cache_dm_keys_sweep_caches_without_a_read(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    mid = wong.compose_dm(freja.identity_pub, "sweep me")
    for m in wong.store.messages_not_in({}, {wong.identity_pub},
                                        freja.identity_pub):
        freja.store.ingest_message(m)
    assert freja.store.cached_dm_key(mid) is None
    freja.cache_dm_keys()
    assert freja.store.cached_dm_key(mid) is not None


def test_revoked_wipe_clears_rotation_material(tmp_path):
    d = tmp_path / "n"
    n = HearthNode.create(d, "Wong", "phone")
    n.device.rotate_enc(now=1.0)
    n.enter_revoked_state()
    raw = json.loads((d / "keys.json").read_text())
    assert raw["retired_enc"] == []
    assert raw["storage_key"] is None
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_node_dm.py -v`
Expected: the 5 new tests FAIL (`cached_dm_key` returns None after compose; `cache_dm_keys` missing; wipe leaves fields), existing tests PASS.

- [ ] **Step 3: Implement**

(a) In `hearth/node.py` imports, extend the `dmcrypt` import with `open_content_key, seal_content_key`.

(b) Add the cache helper and rewrite `_dm_content_key` (replacing the current body):

```python
    def _cache_dm_key(self, msg_id: str, key: bytes):
        if self.device.storage_key is None:
            return
        if self.store.cached_dm_key(msg_id) is None:
            self.store.cache_dm_key(msg_id, seal_content_key(
                self.device.storage_key, msg_id, key))

    def _dm_content_key(self, msg):
        p = msg.payload
        aad = dm_aad(msg.cert.identity_pub, p["to"], p["created_at"])
        # 1) local cache: survives enc-key rotation and grace deletion
        sealed = self.store.cached_dm_key(msg.msg_id)
        if sealed is not None and self.device.storage_key is not None:
            key = open_content_key(self.device.storage_key, msg.msg_id,
                                   sealed)
            if key is not None:
                return key, aad
        # 2) envelope unwrap: current key first, then retired (grace)
        for priv in self.device.enc_privs():
            key = unwrap_key(p["wraps"], self.device.device_pub, priv, aad)
            if key is not None:
                self._cache_dm_key(msg.msg_id, key)
                return key, aad
        return None, aad

    def cache_dm_keys(self):
        """Eagerly cache content keys for DMs that arrived via sync, so
        history survives rotation even on nodes that never display."""
        if (self.revoked or self.device.identity_pub is None
                or self.device.storage_key is None):
            return
        for mid in self.store.uncached_dm_ids(self.identity_pub):
            msg = self.store.get_message(mid)
            if msg is not None:
                self._dm_content_key(msg)
```

(c) In `compose_dm`, capture the msg_id and cache the key (replace the final `return self._publish(...)` line):

```python
        mid = self._publish(make_dm(self.device, to_identity, nonce, ct,
                                    wraps, created_at, refs, expires_at))
        self._cache_dm_key(mid, key)
        return mid
```

(d) In `enter_revoked_state`, after `self.device.enc_priv = None`:

```python
        self.device.retired_enc = []
        self.device.storage_key = None
```

- [ ] **Step 4: Run tests, then full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_node_dm.py -v` — Expected: ALL PASS.
Run: `.venv\Scripts\python.exe -m pytest tests -q` — Expected: ALL PASS.

- [ ] **Step 5: Commit**

```powershell
git add hearth/node.py tests/test_node_dm.py
git commit -m "feat: cached-first DM decryption with retired-key fallback"
```

---

### Task 5: Rotation maintenance + wiring (gossip loop, API startup)

**Files:**
- Modify: `hearth/node.py` (new `maintain_enckey` next to `ensure_enckey`; import `ENC_ROTATION_PERIOD` from `.identity`)
- Modify: `hearth/api.py:38` (`node.ensure_enckey()` -> `node.maintain_enckey()`)
- Modify: `hearth/sync.py` `gossip_loop` (~75)
- Test: `tests/test_node_dm.py` (append), `tests/test_gossip_loop.py` (append)

**Interfaces:**
- Consumes: Task 1 `rotate_enc/prune_retired`; Task 3 `enckey_records`; Task 4 `cache_dm_keys`.
- Produces: `HearthNode.maintain_enckey(now: Optional[float] = None) -> None` — publish-if-missing/mismatched, rotate-if-older-than-period, prune-and-persist. `ensure_enckey` is left unchanged (existing callers/tests keep working).

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_node_dm.py`:

```python
def test_maintain_enckey_rotates_after_period_and_persists(tmp_path):
    from hearth.identity import ENC_ROTATION_PERIOD
    d = tmp_path / "n"
    n = HearthNode.create(d, "Wong", "phone")
    n.maintain_enckey(now=1000.0)                    # first publish
    pub1 = n.device.enc_pub
    n.maintain_enckey(now=1000.0 + ENC_ROTATION_PERIOD - 1)
    assert n.device.enc_pub == pub1                  # not yet
    n.maintain_enckey(now=1000.0 + ENC_ROTATION_PERIOD)
    assert n.device.enc_pub != pub1                  # rotated
    assert n.store.enckeys(n.identity_pub)[n.device.device_pub] \
        == n.device.enc_pub                          # new key published
    assert n.device.retired_enc[0]["enc_pub"] == pub1
    n.close()
    n2 = HearthNode(d)                               # rotation persisted
    assert n2.device.retired_enc[0]["enc_pub"] == pub1


def test_maintain_enckey_prunes_past_grace_and_persists(tmp_path):
    from hearth.identity import ENC_GRACE
    d = tmp_path / "n"
    n = HearthNode.create(d, "Wong", "phone")
    n.maintain_enckey(now=0.0)
    n.device.rotate_enc(now=0.0)
    n._save_keys()
    n.maintain_enckey(now=ENC_GRACE + 1.0)           # prunes (and rotates)
    assert all(r["retired_at"] > 0.0 for r in n.device.retired_enc)
    n.close()
    raw = json.loads((d / "keys.json").read_text())
    assert all(r["retired_at"] > 0.0 for r in raw["retired_enc"])
```

Append to `tests/test_gossip_loop.py`:

```python
def test_gossip_loop_publishes_enckey_and_caches_dm_keys(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        wong.store.add_identity(freja.identity_pub)
        freja.store.add_identity(wong.identity_pub)
        freja.ensure_enckey()                  # wong's key comes from loop
        sw, sf = SyncService(wong), SyncService(freja)
        wp = await sw.start("127.0.0.1", 0)
        fp = await sf.start("127.0.0.1", 0)
        wong.store.set_meta("gossip_addr", f"127.0.0.1:{wp}")
        freja.store.set_meta("gossip_addr", f"127.0.0.1:{fp}")
        wong.store.add_peer(f"127.0.0.1:{fp}", freja.identity_pub)
        loop_task = asyncio.create_task(sw.gossip_loop(interval=0.05))
        mid = None
        for _ in range(100):                   # up to ~5s
            await asyncio.sleep(0.05)
            if mid is None:
                if freja.store.enckeys(wong.identity_pub):
                    # loop published wong's enckey and gossiped it over;
                    # now freja can DM wong
                    mid = freja.compose_dm(wong.identity_pub, "til wong")
                continue
            if wong.store.cached_dm_key(mid) is not None:
                break                          # loop swept + cached it
        else:
            raise AssertionError("loop did not publish/cache in time")
        loop_task.cancel()
        await sw.stop()
        await sf.stop()
    asyncio.run(scenario())
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_node_dm.py tests/test_gossip_loop.py -v`
Expected: new tests FAIL (`maintain_enckey` missing; loop never publishes/caches). Existing PASS.

- [ ] **Step 3: Implement**

(a) In `hearth/node.py`, extend the `.identity` import with `ENC_ROTATION_PERIOD`, and add below `ensure_enckey`:

```python
    def maintain_enckey(self, now: Optional[float] = None):
        """Periodic key hygiene (startup + gossip loop): publish the enc
        key if missing/stale, rotate it past ENC_ROTATION_PERIOD, prune
        retired keys past grace. Deletion of retired keys is the forward
        secrecy; publication rides the existing enckey message kind."""
        if self.revoked or self.device.identity_pub is None:
            return
        now = now if now is not None else time.time()
        if self.device.prune_retired(now):
            self._save_keys()
        rec = self.store.enckey_records(self.identity_pub).get(
            self.device.device_pub)
        if rec is None or rec[1] != self.device.enc_pub:
            self._publish(make_enckey(self.device, now=now))
        elif now - rec[0] >= ENC_ROTATION_PERIOD:
            self.device.rotate_enc(now)
            self._publish(make_enckey(self.device, now=now))
```

(`_publish` already persists `keys.json`, so the rotation itself is durable.)

(b) In `hearth/api.py` line 38, change `node.ensure_enckey()` to `node.maintain_enckey()`.

(c) In `hearth/sync.py` `gossip_loop`, add key maintenance before the peers loop and the cache sweep after the expiry sweep:

```python
    async def gossip_loop(self, interval: float = 3.0):
        while True:
            try:
                self.node.maintain_enckey()
                for peer in self.node.store.list_peers():
                    await self.sync_with(peer["address"])
                if self.node.store.sweep_expired():
                    self.node.notify()
                self.node.cache_dm_keys()
            except Exception:
                pass                # never let one bad round kill gossip
            await asyncio.sleep(interval)
```

- [ ] **Step 4: Run tests, then full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_node_dm.py tests/test_gossip_loop.py -v` — Expected: ALL PASS.
Run: `.venv\Scripts\python.exe -m pytest tests -q` — Expected: ALL PASS.

- [ ] **Step 5: Commit**

```powershell
git add hearth/node.py hearth/api.py hearth/sync.py tests/test_node_dm.py tests/test_gossip_loop.py
git commit -m "feat: enckey rotation maintenance wired into gossip loop + API startup"
```

---

### Task 6: End-to-end rotation story over real sockets (the FS proof)

**Files:**
- Create: `tests/test_dm_rotation_e2e.py`

**Interfaces:**
- Consumes: everything above; `unwrap_key`, `dm_aad` from `hearth.dmcrypt`; `DeviceKeys` from `hearth.identity`.
- Produces: nothing; this is the spec's success-criterion test.

- [ ] **Step 1: Write the test**

Create `tests/test_dm_rotation_e2e.py`:

```python
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
        # DM 1 lands before any rotation
        mid1 = wong.compose_dm(freja.identity_pub, "foer rotation")
        await sw.sync_with(fa)
        # freja rotates BEFORE reading: envelope must open via the
        # retired key (grace), and the content key gets cached
        freja.device.rotate_enc()
        freja.ensure_enckey()                      # publish the new key
        assert freja.dm_thread(wong.identity_pub)[0]["text"] \
            == "foer rotation"
        assert freja.store.cached_dm_key(mid1) is not None
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
        # far-future maintenance: gen-1 retired key is pruned for good
        freja.maintain_enckey(now=4e9)
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
```

- [ ] **Step 2: Run it**

Run: `.venv\Scripts\python.exe -m pytest tests/test_dm_rotation_e2e.py -v`
Expected: PASS (all machinery landed in Tasks 1-5).

- [ ] **Step 3: Prove the FS assertion has teeth**

Temporarily neuter pruning and confirm the leak test catches it:

```powershell
# In hearth/identity.py, temporarily change prune_retired's keep-line to
# keep everything:   keep = list(self.retired_enc)
.venv\Scripts\python.exe -m pytest tests/test_dm_rotation_e2e.py -q
```

Expected: FAIL at the `unwrap_key(...) is None` assertion (the leaked retired key decrypts DM 1). Revert the temporary change (`git checkout -- hearth/identity.py` if needed — Task 1's committed version is correct), re-run: PASS. State both outputs in the report.

- [ ] **Step 4: Full suite**

Run: `.venv\Scripts\python.exe -m pytest tests -q` — Expected: ALL PASS.

- [ ] **Step 5: Commit**

```powershell
git add tests/test_dm_rotation_e2e.py
git commit -m "test: end-to-end rotation, grace, cache, and keys.json-leak FS proof"
```

---

### Task 7: Docs — honest windowed-FS framing (README + ROADMAP)

**Files:**
- Modify: `README.md` (the "Honest limits (v0.1)" paragraph in "Encrypted messages")
- Modify: `ROADMAP.md` (honest-status bullet; shipped list; open-design-decisions bullet; backlog line)

**Interfaces:** docs only; wording below is binding (honesty rule: "windowed", never per-message; never claim unlocked-device protection).

- [ ] **Step 1: README**

Replace the paragraph that starts `Honest limits (v0.1): NO forward secrecy` (through `...named follow-up.`) with:

```markdown
Honest limits (v0.2): DM forward secrecy is WINDOWED, not per-message.
Each device rotates its encryption key daily and permanently deletes
retired keys after 7 days, so a leaked keys.json or stolen backup
cannot decrypt DM ciphertexts older than that window - and an attacker
who loses access stops reading new DMs after the next rotation. What
this does NOT protect, stated plainly: a thief holding an unlocked
device reads whatever the app can display (revoke the device; app-lock
/ OS-keystore gating is the named follow-up), and stealing the whole
node directory (device keys + database together) reads cached history
exactly as before. Revoking a device logs it out on compliant clients
(wipes its keys and store) and structurally cuts it off from anything
new; a modified client cannot be forced to wipe.
```

- [ ] **Step 2: ROADMAP**

(a) Replace the honest-status bullet:

```markdown
- **DMs have no forward secrecy (v0.1).** A stolen *unlocked* device can read DM history until revoked. A ratchet is the named follow-up.
```

with:

```markdown
- **DM forward secrecy is windowed (v0.2).** Devices rotate encryption keys daily and permanently delete retired keys after 7 days: a leaked `keys.json`/backup cannot decrypt DM envelopes older than the window, and an attacker who loses access stops reading new DMs after the next rotation. Still true and stated: a thief with an *unlocked* device reads what the app displays (app-lock/OS-keystore gating is the named follow-up), and whole-node-directory theft reads cached history.
```

(b) Change `## Shipped (6 features)` to `## Shipped (7 features)` and append after item 6:

```markdown
7. **DM forward secrecy v0.2 (slow ratchet)** — daily enckey rotation riding the existing latest-wins `enckey` messages (zero protocol change), 7-day grace then permanent key deletion, per-device encrypted `dm_keys` content-key cache so history survives rotation; the FS property is proven by test (a `keys.json`-only leak cannot decrypt pre-rotation envelopes). Double Ratchet / per-pair chains rejected: mailbox latency collapses their granularity to the window anyway (see spec).
```

(c) In "Open design decisions", replace:

```markdown
- **No forward secrecy in DM v0.1** — ratchet is the named follow-up. Cleartext ciphertext-blob-hashes in DM payloads are safe *only* under the no-relay/no-group-DM routing invariant.
```

with:

```markdown
- **DM forward secrecy is windowed (v0.2)** — rotation 24h, grace 7d; finer-grained ratchets rejected for mailbox-latency reasons (spec 2026-07-03). App-lock / OS-keystore gating of key material is the named follow-up for device custody. Cleartext ciphertext-blob-hashes in DM payloads are safe *only* under the no-relay/no-group-DM routing invariant.
```

(d) In the "Data/perf" backlog bullet, append before the final period:

```
; prune superseded enckey messages (rotation accumulates one per device per day)
```

- [ ] **Step 3: Full suite**

Run: `.venv\Scripts\python.exe -m pytest tests -q` — Expected: ALL PASS (docs-only change).

- [ ] **Step 4: Commit**

```powershell
git add README.md ROADMAP.md
git commit -m "docs: windowed forward secrecy - honest two-property framing"
```

---

## Completion

After Task 7: whole-branch review (superpowers:requesting-code-review), then superpowers:finishing-a-development-branch — merge `dm-forward-secrecy` to `main`, push to origin. Include in the final summary to August a suggested concept-doc v0.4 changelog delta (his document; not edited by this branch). Workstream 3 (Tor) starts only after this merge.
