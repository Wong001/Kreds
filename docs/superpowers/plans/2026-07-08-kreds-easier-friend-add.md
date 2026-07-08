# Kreds Easier Friend-Add Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make friend-add "A shares one code, B pastes it, done" — B auto-delivers its half of the ceremony to A over Tor — with an expiring, single-use, single-active invite code and a copy-paste fallback when the other side is offline.

**Architecture:** Keep the existing 4-message friend ceremony crypto (`create_invite`/`respond_to_invite`/`finalize_invite`/`complete_invite`, nonces + signatures) **unchanged**. Add (1) a 10-minute expiry + single-active semantics to the invite, and (2) a new pre-friend `friend-add` handshake frame on the existing `SyncService` Tor listener (dispatched before the friend-auth session, authenticated by the invite nonce/sig) so B can deliver its response to A and get the final back automatically. Offline → today's copy-paste flow.

**Tech Stack:** Python 3.12, asyncio, the existing `hearth/sync.py` `SyncService` + `hearth/transport.py` framing, `cryptography` (unchanged); pytest. `node --check` for web.

**Spec:** `docs/superpowers/specs/2026-07-08-kreds-easier-friend-add-design.md`

## Global Constraints

- Branch: `kreds-easier-friend-add` off `main` (already created + checked out — do NOT re-branch).
- **The friend-ceremony crypto is UNCHANGED** — every nonce + signature check in `respond_to_invite`/`finalize_invite`/`complete_invite` stays; we only add expiry and change the *transport* of steps 2-4. Do not weaken authentication.
- **Version bump `hearth/__init__.py __version__` `0.2.0` -> `0.3.0`** (Task 4) — ships as the first real core auto-update test.
- The pre-friend `friend-add` frame is authenticated ONLY by the invite nonce + signatures inside `finalize_invite` (a random `.onion` with no valid pending nonce is refused); rate-limit inbound friend-add frames.
- Invite is single-use (nonce consumed on success), single-active (a new `create_invite` drops any prior unused invite), 10-minute TTL (`time.time()` wall-clock is fine at this scale); no background sweeper — purge lazily on use + on the next `create_invite`.
- Friend endpoints stay behind the app-lock guard (NOT in the locked allowlist). Web notes via `textContent` (no `innerHTML` of user data). ASCII-only Python. Full suite + `node --check` green each commit.
- Claude tests crypto+protocol on the in-process/loopback two-node harness (no real Tor); August verifies the real 2-machine-over-Tor flow.

---

### Task 1: Expiring, single-use, single-active invite

**Files:**
- Modify: `hearth/node.py` (`create_invite`, `respond_to_invite`, `finalize_invite`, `complete_invite`), `hearth/api.py` (`/api/friend/invite`)
- Test: `tests/test_invite_expiry.py`

**Interfaces:**
- Produces: `create_invite(ttl_seconds=600) -> str` (invite JSON now includes `expires_at`); `finalize_invite`/`complete_invite` reject expired.

- [ ] **Step 1: Failing tests** — `tests/test_invite_expiry.py`:

```python
import json, time
import pytest
from hearth.node import HearthNode

def _pair(tmp_path):
    a = HearthNode.create(tmp_path / "a", "A", "a-dev")
    b = HearthNode.create(tmp_path / "b", "B", "b-dev")
    a.store.set_meta("gossip_addr", "127.0.0.1:7101")
    b.store.set_meta("gossip_addr", "127.0.0.1:7103")
    return a, b

def test_invite_carries_expiry(tmp_path):
    a, _ = _pair(tmp_path)
    inv = json.loads(a.create_invite(ttl_seconds=600))
    assert inv["expires_at"] > time.time()

def test_expired_invite_rejected_at_finalize(tmp_path):
    a, b = _pair(tmp_path)
    inv = a.create_invite(ttl_seconds=0)          # already expired
    resp = b.respond_to_invite(inv)
    with pytest.raises(ValueError):
        a.finalize_invite(resp)
    assert a.store.is_known(b.identity_pub) is False

def test_single_active_new_code_kills_old(tmp_path):
    a, b = _pair(tmp_path)
    inv1 = a.create_invite()
    a.create_invite()                              # a new code invalidates inv1
    resp = b.respond_to_invite(inv1)
    with pytest.raises(ValueError):
        a.finalize_invite(resp)

def test_valid_invite_still_completes(tmp_path):
    a, b = _pair(tmp_path)
    inv = a.create_invite()
    a.finalize_invite(b.respond_to_invite(inv))
    assert a.store.is_known(b.identity_pub)
```

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: `hearth/node.py`.** `create_invite` (currently stores `_pending_invites[nonce] = True`):

```python
def create_invite(self, ttl_seconds=600) -> str:
    self._pending_invites = {}          # single active: drop any prior unused invite
    nonce = os.urandom(16).hex()
    expiry = time.time() + ttl_seconds
    self._pending_invites[nonce] = expiry
    return json.dumps({
        "t": "hearth-invite", "protocol": PROTOCOL,
        "cert": self.device.cert.to_dict(),
        "addr": self.store.get_meta("gossip_addr"),
        "nonce": nonce, "expires_at": expiry,
    })
```

`finalize_invite` — replace the `nonce not in self._pending_invites` guard with an expiry-aware one:

```python
    nonce = resp.get("nonce")
    exp = self._pending_invites.get(nonce)
    if resp.get("t") != "hearth-response" or exp is None:
        raise ValueError("no matching invite")
    if time.time() >= exp:
        del self._pending_invites[nonce]
        raise ValueError("invite expired")
    # ... (existing cert.verify() + _sig_ok signature check UNCHANGED) ...
    del self._pending_invites[nonce]
    self._add_friend(cert, resp.get("addr"))
    # ... (existing final-frame return UNCHANGED) ...
```

`respond_to_invite` — store an expiry with the pending response + purge expired ones (so B's half doesn't linger); the tuple becomes `(cert, addr, expiry)`:

```python
    my_nonce = os.urandom(16).hex()
    self._pending_responses = {k: v for k, v in self._pending_responses.items()
                               if v[2] > time.time()}          # purge expired
    self._pending_responses[my_nonce] = (cert, inv.get("addr"), time.time() + 600)
    # ... (existing response-frame return UNCHANGED) ...
```

`complete_invite` — unpack the 3-tuple + reject expired:

```python
    entry = self._pending_responses.get(fin.get("nonce"))
    if fin.get("t") != "hearth-final" or entry is None:
        raise ValueError("no matching response")
    cert, addr, exp = entry
    if time.time() >= exp:
        del self._pending_responses[fin["nonce"]]
        raise ValueError("response expired")
    # ... (existing _sig_ok signature check UNCHANGED) ...
    del self._pending_responses[fin["nonce"]]
    self._add_friend(cert, addr)
```

- [ ] **Step 4: `hearth/api.py`** `/api/friend/invite` — surface `expires_at`:

```python
    @app.post("/api/friend/invite")
    async def friend_invite():
        payload = node.create_invite()
        return {"payload": payload, "expires_at": json.loads(payload)["expires_at"]}
```

- [ ] **Step 5: Run tests + full suite** (the existing `tests/test_ceremonies.py` must still pass — it creates one invite per node + finalizes immediately, so expiry + single-active don't affect it). **Commit.**

```bash
git add hearth/node.py hearth/api.py tests/test_invite_expiry.py
git commit -m "feat: expiring single-use single-active friend invite (10-min TTL, new code kills old, finalize/complete reject expired)"
```

---

### Task 2: Pre-friend friend-add handshake over Tor + auto-delivery

**Files:**
- Modify: `hearth/sync.py` (`_on_conn`, `_session` add `peer_hello` param, add `_handle_friend_add` + `deliver_friend_add` + rate-limit, wire `node._friend_dial` in `__init__`), `hearth/node.py` (`__init__` `self._friend_dial = None`, add `add_friend_via_invite`)
- Test: `tests/test_friend_add_delivery.py`

**Interfaces:**
- Consumes: Task 1's expiry-aware `finalize_invite`/`respond_to_invite`/`complete_invite`.
- Produces: `SyncService.deliver_friend_add(address, response_json) -> str|None`; `node.add_friend_via_invite(invite_json) -> {"status": "connected"|"manual", ...}` (async); a `friend-add`/`friend-final`/`refused` frame protocol on the existing listener.

- [ ] **Step 1: Failing test** — `tests/test_friend_add_delivery.py` (two SyncService nodes on a loopback `TcpTransport`; mirror the existing gossip two-node test setup — inspect `tests/test_gossip_loop.py`/`test_node_onion.py` for the exact `SyncService(node, transport)` + `start()`/listen pattern and reuse it):

```python
import pytest
from hearth.node import HearthNode
from hearth.sync import SyncService
from hearth.transport import TcpTransport

async def _serve(node):
    svc = SyncService(node, TcpTransport())
    port = await svc.start("127.0.0.1", 0)          # match the real start() signature
    node.store.set_meta("gossip_addr", f"127.0.0.1:{port}")
    return svc, port

@pytest.mark.asyncio
async def test_auto_delivery_makes_both_friends(tmp_path):
    a = HearthNode.create(tmp_path / "a", "A", "a-dev")
    b = HearthNode.create(tmp_path / "b", "B", "b-dev")
    svc_a, _ = await _serve(a)
    svc_b, _ = await _serve(b)
    try:
        invite = a.create_invite()                  # A shares one code
        result = await b.add_friend_via_invite(invite)   # B pastes it
        assert result["status"] == "connected"
        assert b.store.is_known(a.identity_pub)      # B added A
        assert a.store.is_known(b.identity_pub)      # A added B automatically
    finally:
        await svc_a.stop(); await svc_b.stop()

@pytest.mark.asyncio
async def test_offline_falls_back_to_manual(tmp_path):
    a = HearthNode.create(tmp_path / "a", "A", "a-dev")
    b = HearthNode.create(tmp_path / "b", "B", "b-dev")
    a.store.set_meta("gossip_addr", "127.0.0.1:59999")   # nobody listening
    svc_b, _ = await _serve(b)
    try:
        result = await b.add_friend_via_invite(a.create_invite())
        assert result["status"] == "manual"
        # the manual fallback path (today's flow) still completes the add:
        final = a.finalize_invite(result["response"])
        b.complete_invite(final)
        assert a.store.is_known(b.identity_pub) and b.store.is_known(a.identity_pub)
    finally:
        await svc_b.stop()

@pytest.mark.asyncio
async def test_friend_add_frame_without_valid_nonce_refused(tmp_path):
    a = HearthNode.create(tmp_path / "a", "A", "a-dev")
    b = HearthNode.create(tmp_path / "b", "B", "b-dev")
    svc_a, _ = await _serve(a)
    svc_b = SyncService(b, TcpTransport())            # B's dialer (no listener needed)
    try:
        # A valid-looking response, but A has no matching pending invite ->
        # finalize_invite refuses -> None returned, nobody added.
        bogus = b.respond_to_invite(a.create_invite())
        a._pending_invites = {}                        # A no longer holds the nonce
        final = await svc_b.deliver_friend_add(a.store.get_meta("gossip_addr"), bogus)
        assert final is None
        assert a.store.is_known(b.identity_pub) is False
    finally:
        await svc_a.stop()
```

(If the harness's `start()`/listen signature differs, adapt these three tests to it — the assertions are the contract: connected→both friends; offline→manual+fallback completes; no-valid-nonce→refused+no add.)

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: `hearth/node.py`** — in `__init__` add `self._friend_dial = None` (near `self._dial = None`), and add the orchestrator:

```python
async def add_friend_via_invite(self, invite_json: str) -> dict:
    """B's side: build the response, deliver it to A over Tor, and complete
    the add automatically. Falls back to returning the response for manual
    copy-paste when A is unreachable."""
    resp = self.respond_to_invite(invite_json)      # validates A's invite (raises on bad)
    inv = json.loads(invite_json)
    addr = inv.get("addr")
    final = None
    if self._friend_dial and addr:
        final = await self._friend_dial(addr, resp)
    if final:
        self.complete_invite(final)                 # adds A (verifies A's sig)
        cert = EnrollmentCert.from_dict(inv["cert"])
        return {"status": "connected",
                "friend": self.store.friend_name(cert.identity_pub) or cert.identity_pub[:8]}
    return {"status": "manual", "response": resp}
```

(Use whatever the store's friend-name accessor is — check `store.py`; if none, return `cert.identity_pub[:8]`.)

- [ ] **Step 4: `hearth/sync.py`** — wire the dialer, add the handshake. In `SyncService.__init__` (where `_dial` is wired) add:

```python
        node._friend_dial = self.deliver_friend_add
        self._friend_add_times = []          # inbound rate-limit window
```

Refactor `_session` to accept an already-read responder hello:

```python
    async def _session(self, reader, writer, initiator: bool, peer_hello=None):
        ...
        hello = {"t": "hello", "cert": node.device.cert.to_dict(), "nonce": my_nonce}
        if peer_hello is None:
            peer_hello = await self._swap(reader, writer, initiator, hello)
        else:
            await write_frame(writer, hello)   # _on_conn already read the peer's first frame
        if peer_hello.get("t") != "hello":
            raise ValueError("bad hello")
        ...                                    # everything after UNCHANGED
```

Dispatch on the first inbound frame in `_on_conn`:

```python
    async def _on_conn(self, reader, writer):
        if self.node.revoked:
            writer.close(); return
        try:
            first = await read_frame(reader)
            if first.get("t") == "friend-add":
                await self._handle_friend_add(first, writer)
            else:
                await self._session(reader, writer, initiator=False, peer_hello=first)
        except Exception:
            pass
        finally:
            writer.close()
            try: await writer.wait_closed()
            except Exception: pass
```

Add the handler + dialer + rate-limit:

```python
    def _friend_add_allowed(self) -> bool:
        now = time.time()
        self._friend_add_times = [t for t in self._friend_add_times if now - t < 60]
        if len(self._friend_add_times) >= 20:     # cap inbound handshake attempts / min
            return False
        self._friend_add_times.append(now)
        return True

    async def _handle_friend_add(self, frame, writer):
        """A's side: a pre-friend delivered B's response. finalize_invite is
        the ONLY authenticator (nonce must match a live pending invite + valid
        sig) -- a stranger with no valid nonce is refused."""
        if not self._friend_add_allowed():
            await write_frame(writer, {"t": "refused"}); return
        try:
            final = self.node.finalize_invite(frame.get("payload", ""))
        except (ValueError, KeyError, TypeError):
            await write_frame(writer, {"t": "refused"}); return
        await write_frame(writer, {"t": "friend-final", "payload": final})

    async def deliver_friend_add(self, address: str, response_json: str):
        """B's side: dial A, send the response, return A's final (or None)."""
        try:
            reader, writer = await self.transport.connect(address)
        except OSError:
            return None
        try:
            await write_frame(writer, {"t": "friend-add", "payload": response_json})
            reply = await read_frame(reader)
            return reply.get("payload") if reply.get("t") == "friend-final" else None
        except Exception:
            return None
        finally:
            writer.close()
            try: await writer.wait_closed()
            except Exception: pass
```

- [ ] **Step 5: Run tests + full suite.** Critically, the existing gossip/sync tests must still pass — the `_on_conn` change reads the first frame then hands it to `_session` via `peer_hello`, so a normal sync hello behaves exactly as before (verify `tests/test_gossip_loop.py`, `test_ceremonies.py`, `test_dm_e2e.py` green). **Commit.**

```bash
git add hearth/sync.py hearth/node.py tests/test_friend_add_delivery.py
git commit -m "feat: pre-friend friend-add handshake over Tor - B auto-delivers its response, A finalizes automatically (nonce/sig authed, rate-limited); offline -> manual fallback"
```

---

### Task 3: API `/friend/add` + web UI (share+countdown / enter-code)

**Files:**
- Modify: `hearth/api.py` (`/api/friend/add`), `hearth/web/app.js`, `hearth/web/index.html`, `hearth/web/style.css`
- Test: `tests/test_friend_add_api.py`, extend `tests/test_web_assets.py`

- [ ] **Step 1: Failing test** — `tests/test_friend_add_api.py`: `POST /api/friend/add {payload: <A's invite>}` against a node whose `_friend_dial` is stubbed to return a valid final → `{status:"connected"}`; stubbed to return None → `{status:"manual", response}`; a malformed/expired payload → 400; and `/api/friend/add` returns 423 while app-locked (mirror an existing locked-guard test).

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: `hearth/api.py`:**

```python
    @app.post("/api/friend/add")
    async def friend_add(body: dict = Body(...)):
        try:
            return await node.add_friend_via_invite(body["payload"])
        except (ValueError, KeyError, TypeError) as e:
            raise HTTPException(400, str(e))
```

(Keep `/api/friend/respond|finalize|complete` for the manual fallback — unchanged.)

- [ ] **Step 4: Web UI** (`app.js`/`index.html`/`style.css`) — two-mode Add-friend panel, replacing the 4-box ceremony as the default (keep the manual boxes reachable as the fallback):
  - **Share tab:** button → `POST /api/friend/invite` → show `payload` (copy button) + a live **"expires in MM:SS"** countdown computed from `expires_at` (a `setInterval` updating `textContent`); at 0 → "Code expired" + a **Regenerate** button (re-POST). While this is open, the existing friends-changed WS event refreshes the friends list so A sees the new friend appear.
  - **Enter-code tab:** textarea for A's code → **Connect** → `POST /api/friend/add` → on `connected`: "You're now friends with <friend>" + refresh; on `manual`: show "They seem offline — send them this code:" + `response` (copy) as the fallback (A then pastes it in the manual box → finalize → gives the final back). Notes via `textContent`. Keyboard-accessible (labels, focus, Enter submits).

- [ ] **Step 5: `node --check hearth/web/app.js` + the web-asset test + full suite. Commit.**

```bash
git add hearth/api.py hearth/web/ tests/test_friend_add_api.py tests/test_web_assets.py
git commit -m "feat: friend-add UI - share code + live expiry countdown / enter code -> auto-connect over Tor, manual fallback; /api/friend/add"
```

---

### Task 4: Version bump 0.3.0 + integration + docs

**Files:**
- Modify: `hearth/__init__.py` (`__version__`), `hearth/web/VERSION`, `README.md`, `ROADMAP.md`
- Test: `tests/test_friend_add_integration.py`

- [ ] **Step 1: Integration test** — `tests/test_friend_add_integration.py`: two loopback SyncService nodes; assert the full easier-add (A `create_invite` → B `add_friend_via_invite` → both mutual friends, `connected`) AND that after adding, a normal gossip `sync_with` between them still works (the `_on_conn` first-frame refactor didn't break gossip); plus an expired-invite auto-delivery attempt → A refuses, B gets `manual`.

- [ ] **Step 2:** `hearth/__init__.py` → `__version__ = "0.3.0"`; `hearth/web/VERSION` → `0.3.0` (keeps the web-update version floor in step with core).

- [ ] **Step 3:** `README.md` + `ROADMAP.md` — document the new friend-add (share one code → auto-connect over Tor → both friends; expiring single-use single-active code; offline copy-paste fallback), and mark it shipped as 0.3.0. Note QR/camera is deferred to iOS.

- [ ] **Step 4: Full suite + `node --check` (twice). Commit.**

```bash
git add hearth/__init__.py hearth/web/VERSION README.md ROADMAP.md tests/test_friend_add_integration.py
git commit -m "test+docs: easier friend-add integration + bump 0.3.0 (first core auto-update payload)"
```

---

## Completion

After Task 4: whole-branch review (superpowers:requesting-code-review) — focus: **the friend-ceremony crypto is unchanged** (every nonce/sig check intact); the pre-friend `friend-add` frame is authenticated solely by `finalize_invite`'s nonce+sig (a random `.onion` / expired / bad-sig is refused, no friend added) and is rate-limited; the `_on_conn` first-frame refactor does not alter normal gossip/auth sessions (existing friends still sync); invite expiry + single-active + single-use hold; the offline fallback completes via the unchanged manual path; app-lock guards the friend endpoints; `0.3.0` bump + web VERSION. Then superpowers:finishing-a-development-branch — merge to `main`, push. Then: the 0.3.0 build (with the MOTW/installer packaging fix) → the real 2-machine Tor friend-add test with Josh, which also live-tests the core auto-update swap-on-restart.
