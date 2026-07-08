# Kreds Unfriend + Signed-Notice Self-Deletion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add unfriending: remove a person + your conversation locally, emit a signed `defriend` notice delivered privately/direct-only to their node, and have the recipient's honest node self-delete everything the remover authored (resurrection-safe) and show them as "no longer connected".

**Architecture:** A new identity-signed `DefriendNotice` record (mirrors `RevocationCert`). The remover tears down locally and queues the notice in a `defriend_outbox`; a delivery task connects directly to the target and hands over the notice, which the target verifies and applies (purge author's content + remove from `identities` + add a display-only `disconnected` marker). Deletion fires ONLY on the authenticated notice — never on refusal/unreachability.

**Tech Stack:** Python 3.12, sqlite3, asyncio, FastAPI; vanilla-JS web client; pytest. Windows/PowerShell, `.venv`.

**Spec:** `docs/superpowers/specs/2026-07-06-kreds-unfriend-design.md`

## Global Constraints

- Branch: `kreds-unfriend` off `main`. One workstream.
- Test runner (timeout-guarded — false-green history): `timeout 150 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3`. Full suite green at every task commit (a TOR_E2E skip is expected).
- ASCII only in Python console prints (cp1252); UI copy may use non-ASCII.
- Deletion of a remover's content on the recipient fires ONLY on a verified `DefriendNotice` — NEVER on connection refusal, unreachability, or any inference.
- Delivery is **direct-only** to the target and **private** — no mesh relay, no third-party-visible signal.
- Resurrection-safe: after purge the remover is not in `identities`; `store.ingest_message` already returns `(False, "unknown identity")` for unknown authors (`store.py:213`) — do not weaken this.
- Honest copy (confirm dialog), verbatim: "Remove {name} from your kreds? They leave your circle and messages, and you both stop exchanging. Their Kreds app is sent a signed removal notice and deletes what you shared as soon as it receives it - we keep trying privately for up to 14 days. An honest app deletes on receipt, but a modified client or a screenshot can still keep a copy, and if their device is never reachable their copy may remain. You can re-add them later if they share their code again."
- No block/ban mechanism (no public lookup; re-friend via the ceremony is the soft block).
- Retraction delivery window: `DEFRIEND_TTL = 14 * 86400` seconds.
- Follow the existing signed-record pattern (`RevocationCert` in `hearth/identity.py`): frozen dataclass, `body()`/`verify()`/`to_dict()`/`from_dict()`, identity-key signature via `_sig_ok`.

---

### Task 1: `DefriendNotice` record + store primitives

**Files:**
- Modify: `hearth/identity.py` (add `DefriendNotice` dataclass + an `Identity.make_defriend` maker near `RevocationCert`/`make_revocation`)
- Modify: `hearth/store.py` (schema: `defriend_outbox` + `disconnected` tables; methods)
- Test: `tests/test_unfriend_store.py` (new)

**Interfaces:**
- Produces:
  - `DefriendNotice(author_identity: str, target_identity: str, created_at: float, signature: str)` with `.body()->bytes`, `.verify()->bool`, `.to_dict()`, `DefriendNotice.from_dict(d)`.
  - `Identity.make_defriend(target_identity: str, now: float|None=None) -> DefriendNotice`.
  - `Store.purge_authored_by(identity_pub: str) -> int` — delete all messages where `identity_pub` is the author, then `gc_blobs()`; returns count.
  - `Store.unfriend_teardown(self_identity: str, other: str)` — remove `other` from `identities`; delete messages authored by `other`; delete the DM conversation (messages of kind `dm` where author is `other` OR `recipient` is `other`); delete ring records authored by self about `other`; forget `other`'s enckeys/profile rows (covered by purge_authored_by for their profile/enckey messages); remove `other`'s peer address; `gc_blobs()`.
  - `Store.remove_identity(identity_pub: str)` — `DELETE FROM identities WHERE identity_pub=?` (helper used by both teardown and recipient apply).
  - `Store.add_disconnected(identity_pub: str, name: str)`, `Store.list_disconnected() -> List[dict]` (`{identity_pub, name}`), `Store.remove_disconnected(identity_pub: str)`.
  - `Store.add_outbox(notice: DefriendNotice, address: str, expires_at: float)`, `Store.list_outbox() -> List[dict]` (`{target_identity, address, notice(dict), created_at, expires_at}`), `Store.drop_outbox(target_identity: str)`.

- [ ] **Step 1: Branch already exists** — the controller created `kreds-unfriend`. Do not create/switch branches. Start at Step 2.

- [ ] **Step 2: Write failing tests for the record**

Create `tests/test_unfriend_store.py`:

```python
import time
from hearth.identity import Identity, DefriendNotice
from hearth.store import Store


def _id():
    return Identity.create("dev")   # mirror how other tests build an Identity


def test_defriend_notice_signs_and_verifies():
    a = _id()
    n = a.make_defriend("b" * 64, now=1000.0)
    assert n.author_identity == a.identity_pub
    assert n.target_identity == "b" * 64
    assert n.verify() is True
    # tamper -> fails
    bad = DefriendNotice(n.author_identity, n.target_identity, n.created_at,
                         "00" + n.signature[2:])
    assert bad.verify() is False


def test_defriend_notice_roundtrip():
    a = _id()
    n = a.make_defriend("c" * 64, now=5.0)
    assert DefriendNotice.from_dict(n.to_dict()) == n
```

Check how existing tests construct an `Identity`/`Store` (read `tests/test_identity.py` / `tests/conftest.py`) and match that idiom; adjust `_id()` accordingly.

- [ ] **Step 3: Run — expect failure** — `.venv\Scripts\python.exe -m pytest tests/test_unfriend_store.py -q` → FAIL (`DefriendNotice` undefined).

- [ ] **Step 4: Implement `DefriendNotice` + `make_defriend`**

In `hearth/identity.py`, after `RevocationCert`, add (mirroring it — identity-key signed):

```python
@dataclass(frozen=True)
class DefriendNotice:
    author_identity: str
    target_identity: str
    created_at: float
    signature: str

    def body(self) -> bytes:
        return canonical({
            "type": "defriend", "protocol": PROTOCOL,
            "author_identity": self.author_identity,
            "target_identity": self.target_identity,
            "created_at": self.created_at,
        })

    def verify(self) -> bool:
        return _sig_ok(self.author_identity, self.signature, self.body())

    def to_dict(self) -> dict:
        return {"author_identity": self.author_identity,
                "target_identity": self.target_identity,
                "created_at": self.created_at, "signature": self.signature}

    @staticmethod
    def from_dict(d: dict) -> "DefriendNotice":
        return DefriendNotice(d["author_identity"], d["target_identity"],
                              d["created_at"], d["signature"])
```

Add the maker on the `Identity` class, next to `make_revocation` (use the same identity-private-key signing helper `make_revocation` uses — read `_make_revocation`/`make_revocation` at `identity.py:281,325` and mirror the signing call):

```python
    def make_defriend(self, target_identity: str,
                      now: Optional[float] = None) -> "DefriendNotice":
        ts = now if now is not None else time.time()
        body = canonical({
            "type": "defriend", "protocol": PROTOCOL,
            "author_identity": self.identity_pub,
            "target_identity": target_identity, "created_at": ts,
        })
        sig = self._sign_identity(body)   # same call make_revocation uses
        return DefriendNotice(self.identity_pub, target_identity, ts, sig)
```

Match the actual identity-signing method name used by `make_revocation` (it may be an inline `sign`/`_sign` — copy that exact call).

- [ ] **Step 5: Run — record tests pass** — `.venv\Scripts\python.exe -m pytest tests/test_unfriend_store.py -q` → the two record tests PASS.

- [ ] **Step 6: Write failing tests for store primitives**

Append to `tests/test_unfriend_store.py` (match how other store tests create a `Store` + ingest messages; read `tests/test_store.py`):

```python
def test_purge_authored_by_removes_only_that_author(tmp_path):
    # Build a store where identities A (self) and B are known, B has a post.
    # (Use the project's helpers to enroll + ingest a post from B.)
    st = ...  # per test_store.py idiom; B known, B authored a post + profile
    assert st.messages_by_author("bpub") != []      # helper or direct query
    n = st.purge_authored_by("bpub")
    assert n >= 1
    assert st.messages_by_author("bpub") == []


def test_disconnected_marker_roundtrip(tmp_path):
    st = ...  # fresh store
    st.add_disconnected("b" * 64, "Bob")
    assert st.list_disconnected() == [{"identity_pub": "b" * 64, "name": "Bob"}]
    st.remove_disconnected("b" * 64)
    assert st.list_disconnected() == []


def test_outbox_roundtrip(tmp_path):
    a = _id(); st = ...  # fresh store, a is self
    n = a.make_defriend("d" * 64, now=1.0)
    st.add_outbox(n, "1.2.3.4:9000", expires_at=100.0)
    rows = st.list_outbox()
    assert rows and rows[0]["target_identity"] == "d" * 64
    assert rows[0]["address"] == "1.2.3.4:9000"
    st.drop_outbox("d" * 64)
    assert st.list_outbox() == []
```

For the purge test you may add a tiny read helper `messages_by_author(identity_pub)` (returns rows) if none exists, or query `messages` directly in the test.

- [ ] **Step 7: Run — expect failure** — FAIL (methods/tables undefined).

- [ ] **Step 8: Implement the store schema + methods**

In `hearth/store.py` `_SCHEMA`, add:

```sql
CREATE TABLE IF NOT EXISTS defriend_outbox(
  target_identity TEXT PRIMARY KEY, address TEXT NOT NULL,
  notice_json TEXT NOT NULL, created_at REAL NOT NULL,
  expires_at REAL NOT NULL);
CREATE TABLE IF NOT EXISTS disconnected(
  identity_pub TEXT PRIMARY KEY, name TEXT NOT NULL);
```

Add methods (all under `with self._lock:` + `commit()`, mirroring existing ones):

```python
    def purge_authored_by(self, identity_pub: str) -> int:
        with self._lock:
            cur = self._db.execute(
                "DELETE FROM messages WHERE identity_pub=?", (identity_pub,))
            self._db.commit()
        self.gc_blobs()
        return cur.rowcount

    def remove_identity(self, identity_pub: str):
        with self._lock:
            self._db.execute("DELETE FROM identities WHERE identity_pub=?",
                             (identity_pub,))
            self._db.commit()

    def unfriend_teardown(self, self_identity: str, other: str):
        with self._lock:
            # remove the identity + their authored content + the DM thread +
            # ring records I authored about them + their peer address
            self._db.execute("DELETE FROM identities WHERE identity_pub=?",
                             (other,))
            self._db.execute("DELETE FROM messages WHERE identity_pub=?",
                             (other,))
            self._db.execute(
                "DELETE FROM messages WHERE kind='dm' AND recipient=?",
                (other,))
            self._db.execute(
                "DELETE FROM messages WHERE kind='ring' AND target_id=? "
                "AND identity_pub=?", (other, self_identity))
            self._db.execute("DELETE FROM peers WHERE identity_pub=?", (other,))
            self._db.commit()
        self.gc_blobs()

    def add_disconnected(self, identity_pub: str, name: str):
        with self._lock:
            self._db.execute(
                "INSERT OR REPLACE INTO disconnected VALUES(?,?)",
                (identity_pub, name))
            self._db.commit()

    def list_disconnected(self):
        with self._lock:
            return [{"identity_pub": r[0], "name": r[1]} for r in
                    self._db.execute(
                        "SELECT identity_pub, name FROM disconnected")]

    def remove_disconnected(self, identity_pub: str):
        with self._lock:
            self._db.execute("DELETE FROM disconnected WHERE identity_pub=?",
                             (identity_pub,))
            self._db.commit()

    def add_outbox(self, notice, address: str, expires_at: float):
        with self._lock:
            self._db.execute(
                "INSERT OR REPLACE INTO defriend_outbox VALUES(?,?,?,?,?)",
                (notice.target_identity, address, json.dumps(notice.to_dict()),
                 notice.created_at, expires_at))
            self._db.commit()

    def list_outbox(self):
        with self._lock:
            return [{"target_identity": r[0], "address": r[1],
                     "notice": json.loads(r[2]), "created_at": r[3],
                     "expires_at": r[4]} for r in self._db.execute(
                        "SELECT target_identity, address, notice_json,"
                        " created_at, expires_at FROM defriend_outbox")]

    def drop_outbox(self, target_identity: str):
        with self._lock:
            self._db.execute(
                "DELETE FROM defriend_outbox WHERE target_identity=?",
                (target_identity,))
            self._db.commit()
```

Note: `ring` records store the member in `target_id` — confirm by reading how `KIND_RING` is stored in `ingest_message` (the `target` column mapping). If ring uses a different column for `member`, adjust the teardown query to match; add a `messages_by_author` read helper if the purge test needs it.

- [ ] **Step 9: Run — store tests pass** — `pytest tests/test_unfriend_store.py -q` → PASS.

- [ ] **Step 10: Commit**

```powershell
git add hearth/identity.py hearth/store.py tests/test_unfriend_store.py
git commit -m "feat: DefriendNotice record + unfriend store primitives (teardown, purge, outbox, disconnected)"
```

---

### Task 2: Node — `unfriend()` + apply-notice (retention rule)

**Files:**
- Modify: `hearth/node.py` (add `unfriend`, `apply_defriend_notice`)
- Test: `tests/test_unfriend_node.py` (new)

**Interfaces:**
- Consumes: Task 1's `Identity.make_defriend`, `Store.unfriend_teardown`, `Store.add_outbox`, `Store.purge_authored_by`, `Store.remove_identity`, `Store.add_disconnected`, `DEFRIEND_TTL`.
- Produces:
  - `Node.unfriend(identity_pub: str) -> None` — teardown + queue the notice in the outbox (address from the peer/enckey/profile the node holds for them; if no address known, still tear down and queue with an empty address so it simply never delivers — safe).
  - `Node.apply_defriend_notice(notice: DefriendNotice) -> bool` — verify signature + that `target_identity == self.identity_pub` + that `author_identity` is currently known; if valid: `purge_authored_by(author)`, `remove_identity(author)`, `add_disconnected(author, name)`; return True if applied, False if ignored (bad sig / not for me / unknown author / already applied).

- [ ] **Step 1: Add `DEFRIEND_TTL`** in `hearth/messages.py` near `STORY_TTL`: `DEFRIEND_TTL = 14 * 86400`.

- [ ] **Step 2: Write failing node tests**

Create `tests/test_unfriend_node.py` (match how existing node tests build a `Node` — read `tests/test_node.py`/`conftest.py`):

```python
def test_apply_defriend_notice_purges_and_marks(two_friend_nodes):
    a, b = two_friend_nodes            # a and b are mutual friends, synced
    # b has content from a (a post) synced to b:
    assert b.store.messages_by_author(a.identity_pub)
    notice = a.identity.make_defriend(b.identity_pub)   # a removes b -> notice to b
    # b applies a's notice about... wait: a removes b => notice.target=b, author=a
    applied = b.apply_defriend_notice(notice)
    assert applied is True
    assert b.store.messages_by_author(a.identity_pub) == []   # a's content gone
    assert not b.store.is_known(a.identity_pub)               # removed
    assert any(d["identity_pub"] == a.identity_pub
               for d in b.store.list_disconnected())          # marker set


def test_apply_ignores_bad_or_misdirected_notice(two_friend_nodes):
    a, b = two_friend_nodes
    from hearth.identity import DefriendNotice
    good = a.identity.make_defriend(b.identity_pub)
    forged = DefriendNotice(a.identity_pub, b.identity_pub, good.created_at,
                            "00" + good.signature[2:])
    assert b.apply_defriend_notice(forged) is False          # bad signature
    not_for_b = a.identity.make_defriend("f" * 64)
    assert b.apply_defriend_notice(not_for_b) is False       # not targeting b
    assert b.store.is_known(a.identity_pub)                   # untouched


def test_unfriend_tears_down_and_queues(two_friend_nodes):
    a, b = two_friend_nodes
    a.unfriend(b.identity_pub)
    assert not a.store.is_known(b.identity_pub)               # b gone from a
    assert a.store.messages_by_author(b.identity_pub) == []   # b's content gone
    ob = a.store.list_outbox()
    assert ob and ob[0]["target_identity"] == b.identity_pub  # notice queued
```

Add a `two_friend_nodes` fixture (or reuse an existing multi-node fixture) that enrolls two nodes as mutual friends and syncs one post each. Add `messages_by_author` if not added in Task 1.

- [ ] **Step 3: Run — expect failure.**

- [ ] **Step 4: Implement `unfriend` + `apply_defriend_notice`** in `hearth/node.py`:

```python
    def unfriend(self, identity_pub: str) -> None:
        if identity_pub == self.identity_pub:
            raise ValueError("cannot unfriend yourself")
        notice = self.identity.make_defriend(identity_pub)
        addr = self.store.address_for(identity_pub) or ""   # best-known address
        self.store.unfriend_teardown(self.identity_pub, identity_pub)
        self.store.add_outbox(notice, addr,
                              expires_at=notice.created_at + DEFRIEND_TTL)
        self.notify()

    def apply_defriend_notice(self, notice) -> bool:
        if notice.target_identity != self.identity_pub:
            return False
        if not notice.verify():
            return False
        author = notice.author_identity
        if not self.store.is_known(author):
            return False                       # unknown / already applied
        name = self.store.profiles().get(author, author[:8])
        self.store.purge_authored_by(author)
        self.store.remove_identity(author)
        self.store.add_disconnected(author, name)
        self.notify()
        return True
```

Add `Store.address_for(identity_pub)` if absent (look up the peer address for an identity; return None if unknown) — reuse existing peer lookup if one exists.

- [ ] **Step 5: Run — node tests pass.**

- [ ] **Step 6: Commit**

```powershell
git add hearth/node.py hearth/messages.py tests/test_unfriend_node.py
git commit -m "feat: node unfriend() teardown+queue and apply_defriend_notice retention rule"
```

---

### Task 3: Delivery — notice exchange in the session + delivery pass

**Files:**
- Modify: `hearth/sync.py` (add a `defriends` phase that carries a notice targeted at the peer; apply received notices)
- Modify: `hearth/node.py` (a delivery pass over the outbox; drop on delivered/expiry/refused)
- Test: `tests/test_unfriend_delivery.py` (new)

**Interfaces:**
- Consumes: `Store.list_outbox`, `Store.drop_outbox`, `Node.apply_defriend_notice`, the existing session handshake in `sync.py`.
- Produces: `Node.deliver_defriends(now=None)` — for each outbox record: if `now >= expires_at` drop it; else open a direct session to `address` and deliver the notice; on ack (peer applied/holds it) drop it; on `refused` drop it (cleanup only, never a deletion trigger).

- [ ] **Step 1: Write a failing two-node delivery test**

Create `tests/test_unfriend_delivery.py`:

```python
async def test_delivery_applies_on_recipient_then_drops(running_pair):
    a, b = running_pair               # two live nodes, mutual friends, synced
    assert b.store.messages_by_author(a.identity_pub)
    a.unfriend(b.identity_pub)        # queues a notice targeting b
    await a.deliver_defriends()       # direct delivery to b
    assert b.store.messages_by_author(a.identity_pub) == []   # b purged a
    assert not b.store.is_known(a.identity_pub)
    assert any(d["identity_pub"] == a.identity_pub
               for d in b.store.list_disconnected())
    assert a.store.list_outbox() == []                        # acked -> dropped


async def test_delivery_drops_on_expiry_without_purging(running_pair):
    a, b = running_pair
    a.unfriend(b.identity_pub)
    # force expiry
    for _ in a.store.list_outbox():
        pass
    await a.deliver_defriends(now=a.store.list_outbox()[0]["expires_at"] + 1)
    assert a.store.list_outbox() == []          # given up
    # b never got a notice in this test path -> still knows a (no false purge)
    # (in this test b is unreachable/paused; assert no purge occurred)
```

Match the project's live-node test harness (read `tests/test_sync.py`/`tests/test_tor_e2e.py` for how a `running_pair` of nodes is stood up and driven). Keep it offline/loopback.

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: Add the `defriends` phase to the session** in `hearth/sync.py`

After the REVOCATIONS phase (which already swaps signed certs, `sync.py:176`), add a symmetric `defriends` swap. The initiator populates it with any outbox notices whose `target_identity == peer_identity`; both sides apply received notices that target them:

```python
        # -- DEFRIENDS (targeted, direct-only removal notices) --
        out = [r["notice"] for r in store.list_outbox()
               if r["target_identity"] == peer_identity]
        peer_df = await self._swap(reader, writer, initiator,
                                   {"t": "defriends", "notices": out})
        for nd in peer_df.get("notices", []):
            notice = DefriendNotice.from_dict(nd)
            if self.node.apply_defriend_notice(notice):
                changed = True
        # ack: tell the initiator we hold/handled notices targeting the peer
        # (the peer drops its outbox entry for us on seeing this)
        if peer_df.get("notices"):
            # we (as target) just applied; nothing more to send
            pass
```

The ack: the simplest reliable ack is that the delivery session completes without `refused`; `deliver_defriends` treats a completed session (peer received the `defriends` frame) as delivered. Because `apply_defriend_notice` is idempotent and the recipient removes the author, re-delivery is unnecessary. Import `DefriendNotice` in `sync.py`. Ensure this phase runs BEFORE the recipient would remove the initiator (order within the single session), matching the spec's ack-before-teardown note — here the initiator learns delivery succeeded by the swap completing.

- [ ] **Step 4: Implement `deliver_defriends`** in `hearth/node.py`

```python
    async def deliver_defriends(self, now=None):
        now = now if now is not None else time.time()
        for rec in self.store.list_outbox():
            if now >= rec["expires_at"] or not rec["address"]:
                self.store.drop_outbox(rec["target_identity"])
                continue
            try:
                await self._sync_once(rec["address"])   # reuse the session dialer
                # session completed (notice delivered in the DEFRIENDS phase)
                self.store.drop_outbox(rec["target_identity"])
            except Exception:
                # refused / unreachable: keep for a later attempt unless expired;
                # a 'refused' means the target already removed us -> also cleanup
                pass
```

Use the node's existing outbound-session entry point (read how the gossip loop dials a peer — reuse that exact call instead of `_sync_once` if it differs). Wire `deliver_defriends` into the periodic gossip loop so it runs on cadence. Refusal handling: if the dialer distinguishes a `refused` close, drop the record (cleanup); otherwise rely on expiry.

- [ ] **Step 5: Run — delivery tests pass.**

- [ ] **Step 6: Run full suite** — `timeout 150 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3` → all pass.

- [ ] **Step 7: Commit**

```powershell
git add hearth/sync.py hearth/node.py tests/test_unfriend_delivery.py
git commit -m "feat: direct-only defriend-notice delivery (session phase + outbox delivery pass)"
```

---

### Task 4: API + web UI

**Files:**
- Modify: `hearth/api.py` (add `POST /api/unfriend`; include `disconnected` in state/kreds)
- Modify: `hearth/web/app.js` (Unfriend button + confirm on the profile page; render "no longer connected")
- Modify: `hearth/web/index.html`, `hearth/web/style.css` (button + disconnected state styling)
- Test: `tests/test_api_unfriend.py` (new); extend `tests/test_web_assets.py`

**Interfaces:**
- Consumes: `Node.unfriend`, `Store.list_disconnected`.
- Produces: `POST /api/unfriend` JSON `{identity_pub}` → calls `node.unfriend`; `/api/kreds` (or `/api/state`) exposes `disconnected: [{identity_pub, name}]` for the UI.

- [ ] **Step 1: Failing API test** in `tests/test_api_unfriend.py` (mirror `tests/test_api_kreds.py` harness):

```python
def test_unfriend_removes_and_queues(client_with_friend):
    c, node, friend = client_with_friend
    r = c.post("/api/unfriend", json={"identity_pub": friend})
    assert r.status_code == 200
    assert not node.store.is_known(friend)
    assert node.store.list_outbox()            # notice queued
    # friend no longer in /api/kreds
    assert all(k["identity_pub"] != friend
               for k in c.get("/api/kreds").json())
```

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: Implement `POST /api/unfriend`** in `hearth/api.py` (mirror `/api/ring`):

```python
    @app.post("/api/unfriend")
    async def unfriend(body: dict = Body(...)):
        node.unfriend(body["identity_pub"])
        return {"ok": True}
```

Add `disconnected` to the state or kreds payload: include `node.store.list_disconnected()` under a `disconnected` key wherever the client needs it (add to `/api/state`).

- [ ] **Step 4: Run — API test passes.**

- [ ] **Step 5: Failing web-asset test** — append to `tests/test_web_assets.py`:

```python
def test_unfriend_ui_and_honest_copy():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "/api/unfriend" in js
    assert "no longer connected" in js.lower()
    # the honest copy is present (a distinctive phrase from it)
    assert "we keep trying privately for up to 14 days" in js.lower()
    assert "a screenshot can still keep a copy" in js.lower()
```

- [ ] **Step 6: Implement the UI** in `hearth/web/app.js` (+ index.html/style.css):
  - On the profile page actions (non-self), add an **Unfriend** button. Its handler `confirm(...)` with the verbatim honest copy (Global Constraints), and on OK `POST /api/unfriend {identity_pub}` then navigate Back + `refresh()`.
  - Render a person present in `STATE.disconnected` as **"no longer connected"** (an inert label; disable/hide Message) in the kreds list and on their profile page.
  - Keep the confirm copy exactly as in Global Constraints.

- [ ] **Step 7: Run web-asset + API tests, `node --check hearth/web/app.js`, full suite.**

- [ ] **Step 8: Commit**

```powershell
git add hearth/api.py hearth/web/app.js hearth/web/index.html hearth/web/style.css tests/test_api_unfriend.py tests/test_web_assets.py
git commit -m "feat: unfriend API + profile-page Unfriend action, honest copy, no-longer-connected state"
```

---

### Task 5: Integration — resurrection guard, false-trigger, re-add, docs

**Files:**
- Test: `tests/test_unfriend_integration.py` (new)
- Modify: `README.md`, `ROADMAP.md`

**Interfaces:** Consumes everything above.

- [ ] **Step 1: Resurrection + false-trigger + re-add integration tests**

Create `tests/test_unfriend_integration.py` (three live nodes A, B, C where useful):

```python
async def test_purged_content_not_resurrected_via_mutual_friend(triple):
    a, b, c = triple                  # all mutual friends, a's post synced to b and c
    a.unfriend(b.identity_pub)
    await a.deliver_defriends()       # b purges a's content, removes a
    # c still holds a's post and syncs with b; b must NOT re-accept a's content
    await b_syncs_with_c(b, c)
    assert b.store.messages_by_author(a.identity_pub) == []


async def test_refusal_alone_never_purges(running_pair):
    a, b = running_pair
    # a removes b from identities WITHOUT delivering a notice (simulate a cut),
    # then b tries to sync a and is refused:
    a.store.remove_identity(b.identity_pub)
    await b_tries_to_sync_a_and_is_refused(b, a)
    # b must NOT have purged a's content (no notice was ever received):
    assert b.store.messages_by_author(a.identity_pub)   # still there
    assert b.store.is_known(a.identity_pub)


def test_refriend_clears_disconnected(node_with_disconnected):
    b, a_pub, a_name = node_with_disconnected   # b has a as 'no longer connected'
    b.store.add_identity(a_pub)                 # re-friend (ceremony -> add_identity)
    b.store.remove_disconnected(a_pub)
    assert all(d["identity_pub"] != a_pub for d in b.store.list_disconnected())
```

Wire re-friend so the ceremony/`add_identity` path clears the `disconnected` marker (either call `remove_disconnected` inside the friend-add flow in `node.py`, or in the API friend-add handler). Add that call and a test that the ceremony clears it.

- [ ] **Step 2: Run — expect failures, then implement the re-friend marker clear** (add `remove_disconnected` to the friend-add path). Re-run → pass.

- [ ] **Step 3: Full suite** — `timeout 150 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3` → all pass.

- [ ] **Step 4: Playwright/manual smoke (record outcome)** — stand up two isolated nodes on free ports (NOT demo ports), make them friends, unfriend from the UI, confirm: the person leaves the remover's kreds/circle/messages; after delivery the recipient shows "no longer connected" and the remover's posts are gone from the recipient. If Playwright unavailable, drive it via the two-node integration harness and say so.

- [ ] **Step 5: README + ROADMAP** — document unfriend: signed-notice-driven, direct-only/private delivery, recipient self-deletes the remover's content (honest limits), "no longer connected", no block/ban (re-friend via ceremony). Mark the slice shipped.

- [ ] **Step 6: Commit**

```powershell
git add tests/test_unfriend_integration.py README.md ROADMAP.md hearth/node.py
git commit -m "test+docs: unfriend resurrection guard, no-false-trigger, re-add; ship notes"
```

---

## Completion

After Task 5: whole-branch review (superpowers:requesting-code-review) focused on — deletion fires ONLY on a verified notice (no refusal-inference anywhere); resurrection impossible after purge (unknown-author ingest guard intact); delivery is direct-only/private (no mesh/broadcast); ack-before-teardown ordering and refused=cleanup-not-trigger; teardown removes the right rows without collateral (own content, mutual friends' content untouched); honest copy verbatim; symmetry; no shipped behavior broken. Then superpowers:finishing-a-development-branch — merge `kreds-unfriend` to `main`, push. Next: Windows packaging.
