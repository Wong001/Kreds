# Android B.2c — Friends' Content Readable Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The phone's feed shows readable FRIEND content with author names — friend wall history (via friends' stock grant sweeps), new friend DMs (inline-wrapped), and OLD received DMs via a new recipient-signed re-grant sweep on the home node — proven by a two-node desk gate, then on the G20.

**Architecture:** One new isolated hearth sweep (`maintain_received_dm_grants`, recipient-signed grants, own devices only) + the phone's DecryptPass own-author filter relaxed into an entitlement rule (author-signed grants for posts; author- OR recipient-signed for DMs) + author display names from stored plaintext profile payloads + a two-node loopback gate.

**Tech Stack:** Python `hearth/` (one production change), Kotlin (DecryptPass/SqliteSyncStore/module), RN (App.tsx), the existing loopback harness grown to two nodes.

**Spec:** `docs/superpowers/specs/2026-07-19-android-b2c-friends-content-design.md`

## Global Constraints

- **Commit messages: NO AI/Co-Authored-By trailers.** Style `feat(b2c): ...` / `fix(b2c): ...` / `docs(b2c): ...` lowercase.
- **`maintain_wrap_grants` AND `maintain_own_device_grants` are NEVER modified.** The new sweep is a third, isolated method. `hearth/` changes are limited to: the new method (+ small helper if needed) in `node.py` + one call each in `api.py`/`sync.py` immediately after `maintain_own_device_grants()`.
- **Recipient-signed grants wrap ONLY to devices enrolled to the recipient identity** (own enckeys minus self) — never to any other identity's device. This is the security core.
- **Trust rule (consumer side, phone):** a grant is honored for a POST only when signed by the post's author; for a DM when signed by the DM's author OR by the DM's recipient (== the phone's own identity). Nothing else.
- Posts' trust rule unchanged; friend journal never arrives (server gate, untouched); minimal text feed only (media/threads/reactions = B.2d); foreground-triggered; no composing from the phone.
- **Branch:** `brick-b2c-friends-content` off `brick-b2-decryption` (B.2 not yet merged; stacked by August's proceed decision).
- **Env:** dot-source `android_tor_spike/tools/env.ps1` every PowerShell session touching gradle/adb; Python gates use `.venv\Scripts\python.exe`; generous timeouts (up to 600000 ms).
- Pinned signatures (verified 2026-07-19): `make_wrap_grant(device, target_msg_id, wraps, now=None)` signs with WHOEVER's device mints (consumer enforces trust, messages.py:193-202); `_content_key(msg)` handles KIND_DM via `dm_aad(msg.cert.identity_pub, p["to"], p["created_at"])` and returns `(None, None)`-style for unkeyable kinds (node.py:2727+); KIND_PROFILE payloads are PLAINTEXT (`name` field, messages.py:104-112); phone `store.addIdentity(String)` stores bare identity-pub strings (KotlinSync.kt:182-183).

## File Structure

```
hearth/node.py                    Task 1: + maintain_received_dm_grants
hearth/api.py, hearth/sync.py     Task 1: + one call each after maintain_own_device_grants()
tests/test_received_dm_grants.py  Task 1: the hearth pytest (heaviest coverage)
android .../tormanager/
  SyncStore.kt, SqliteSyncStore.kt  Task 2: wrapGrantsFor accepts a signer SET; Task 3: profileNames()
  DecryptPass.kt                    Task 2: entitlement rule; Task 3: author names on Decrypted
  TorManagerModule.kt               Task 3: feed items carry author; friends stat excludes self
  index.ts                          Task 3: FeedItem.author
android .../src/test/.../DecryptPassTest.kt   Task 2/3: entitlement + name tests
android_tor_spike/tools/sync_loopback_node.py Task 4: two-node harness
android .../src/test/.../SyncLoopbackTest.kt  Task 4: the two-node gate test
android_tor_spike/app/App.tsx     Task 3: author shown per feed row
android_tor_spike/BRICK_B2C_REPORT.md  Task 5
```

---

### Task 1: Hearth `maintain_received_dm_grants` (the production change) + pytest

**Files:**
- Modify: `hearth/node.py` (new method beside `maintain_own_device_grants`)
- Modify: `hearth/api.py` + `hearth/sync.py` (one call each, right after `maintain_own_device_grants()`)
- Test: `tests/test_received_dm_grants.py`

**Interfaces:**
- Consumes: `store.enckeys(identity)`, `self._content_key(msg)` (handles KIND_DM), `wrap_key`, `make_wrap_grant`, `self._publish`, `self._latest_own_wrap_grants()` (B.2's helper — grants minted by this identity, keyed by target, prune tie-break), the store's DM iterator (implementer: find the real one — grep `KIND_DM` in `store.py`; a received DM is `kind==KIND_DM and identity_pub != self.identity_pub and payload["to"] == self.identity_pub`).
- Produces: `HearthNode.maintain_received_dm_grants(now=None)`.

- [ ] **Step 1: Write the failing tests** — `tests/test_received_dm_grants.py`, mirroring `tests/test_own_device_grants.py`'s structure and helpers (READ that file first; reuse its `_enroll_second_own_device`-style helper and its two-node/DM setup patterns; the tests below are the required behaviors — adapt constructor/accessor names to what that file actually uses):

```python
"""maintain_received_dm_grants: re-wrap RECEIVED DMs' content keys to OWN
satellite devices via RECIPIENT-signed wrap_grants -- so the phone can read
old friend DMs composed before its enc key existed. maintain_wrap_grants
and maintain_own_device_grants are NOT touched. Trust: a recipient-signed
grant is only ever honored for the recipient's own devices; this file
proves the mint side never wraps to anything else."""

def test_backfills_old_received_dm_to_new_own_device(tmp_path):
    # friend node DMs me BEFORE my phone enc key exists; after the phone's
    # enckey ingests, the sweep mints a RECIPIENT-signed grant; the phone
    # can unwrap_key + decrypt_body the old DM with its own enc_priv.
    ...

def test_never_wraps_to_non_own_devices(tmp_path):
    # after the sweep, EVERY minted grant's wraps keyset is a subset of
    # my own enrolled satellite device_pubs -- even with friend enckeys
    # present in the store.
    ...

def test_never_targets_own_authored_or_non_dm(tmp_path):
    # own posts, own DMs, friend WALL posts: the sweep mints nothing for
    # them (own content is maintain_own_device_grants' job; posts are
    # author-signed territory).
    ...

def test_locked_or_revoked_mints_nothing(tmp_path):
    # biting variant per B.2: build a node that WOULD mint, flip each
    # guard flag, assert nothing minted and no exception.
    ...

def test_three_sweep_fixpoint(tmp_path):
    # run maintain_wrap_grants + maintain_own_device_grants +
    # maintain_received_dm_grants + prune_superseded_wrap_grants in
    # production order for >=3 rounds over: a wall post w/ friend grants,
    # own history, an old received DM, 2 satellites. Assert: grant set
    # stable after round 1, and phone-side decryptability holds for all
    # three content classes after every round. (Extend/copy B.2's
    # fixpoint test in tests/test_own_device_grants.py.)
    ...
```
> **Implementer:** these five tests are REQUIRED and must be real (no `...` remains). Build the friend-DM ingestion exactly the way existing DM tests do (grep `KIND_DM` in `tests/`).

- [ ] **Step 2: Run — expect FAIL** (`.venv\Scripts\python.exe -m pytest tests\test_received_dm_grants.py -v` → AttributeError: no `maintain_received_dm_grants`).

- [ ] **Step 3: Implement the sweep in `hearth/node.py`** (immediately after `maintain_own_device_grants`; mirror its final B.2 shape — latest-grant coverage + enc_pub annotation + full coverage; NO carry-forward needed: `(me, friend-dm-id)` shares its prune key with no other minter):

```python
    def maintain_received_dm_grants(self, now: Optional[float] = None):
        """Re-wrap RECEIVED DMs' content keys to this identity's OWN other
        devices via RECIPIENT-signed wrap_grants -- the phone can then read
        old friend DMs composed before its enc key existed. The recipient
        already holds the plaintext; wrapping to its own enrolled device
        (identity-signed cert, the same thing AUTH requires) discloses
        nothing new. Grants route only to the devices named in their wraps,
        so these never reach a friend. Distinct from maintain_wrap_grants
        (friends' wall coverage) and maintain_own_device_grants (own-
        authored content) -- both untouched. Guard identical: minting
        signs, so locked/revoked/unenrolled skip entirely."""
        if self.revoked or self.locked or self.device.identity_pub is None:
            return
        own = self.store.enckeys(self.identity_pub)
        targets = {d: e for d, e in own.items() if d != self.device.device_pub}
        if not targets:
            return
        latest = self._latest_own_wrap_grants()
        for msg in self._received_dms():
            grant = latest.get(msg.msg_id, {})
            wrapped = set(msg.payload.get("wraps", {}))
            missing = False
            for dpub, enc_pub in targets.items():
                if dpub in wrapped:
                    continue
                g = grant.get(dpub)
                if g is None or g.get("enc_pub", enc_pub) != enc_pub:
                    missing = True
            if not missing:
                continue
            key, aad = self._content_key(msg)
            if key is None:
                continue        # unkeyable: never crash the sweep
            wraps = wrap_key(key, targets, aad)
            for dpub in wraps:
                wraps[dpub]["enc_pub"] = targets[dpub]
            self._publish(make_wrap_grant(self.device, msg.msg_id, wraps,
                                          now=now))

    def _received_dms(self):
        """Friend-authored DMs addressed to this identity. Confirm the real
        iterator against store.py (kind==KIND_DM, identity_pub != own,
        payload['to'] == own)."""
        ...
```
> **Implementer:** confirm `_latest_own_wrap_grants()`'s exact name/shape from B.2's code in `node.py` (it maps target -> the latest own-minted grant's per-device entries with the prune's `(created_at, seq)` tie-break — recipient-signed grants ARE own-minted, so it already covers them). Fill `_received_dms` with the real store accessor. `_content_key` already builds the DM aad and recovers the key for received DMs (the desktop is in their wraps).

- [ ] **Step 4: Wire the two call sites** — `hearth/api.py` and `hearth/sync.py`, one line each, immediately after `node.maintain_own_device_grants()` / `self.node.maintain_own_device_grants()`: add `.maintain_received_dm_grants()`.

- [ ] **Step 5: Run → PASS**, then the full suite once: `.venv\Scripts\python.exe -m pytest -q` (production code — zero regressions accepted; B.2 baseline was 1051 passed / 9 skipped, plus B.2c's new tests).

- [ ] **Step 6: Commit**
```powershell
git add hearth/node.py hearth/api.py hearth/sync.py tests/test_received_dm_grants.py
git commit -m "feat(b2c): maintain_received_dm_grants - recipient-signed backfill of received DMs to own devices, isolated third sweep"
```

---

### Task 2: Phone entitlement rule (DecryptPass + store signer-set)

**Files:**
- Modify: `SyncStore.kt` + `SqliteSyncStore.kt` (`wrapGrantsFor` takes a signer set)
- Modify: `DecryptPass.kt` (own-author filter → entitlement rule)
- Test: `DecryptPassTest.kt`

**Interfaces:**
- Consumes: B.2's `DecryptPass.run(store, phoneDevicePub, encPrivHex, ownIdentityPub)`, `StoredMsg(msgId, kind, payload, identityPub)`, `wrapGrantsFor(msgId, authorIdentityPub)`.
- Produces: `wrapGrantsFor(msgId: String, acceptedSigners: Set<String>): List<Map<String, Any?>>` (both impls; SQLite: `identity_pub IN (...)`); `DecryptPass.run` signature UNCHANGED, but per-message entitlement inside:

```kotlin
// posts: grants trusted from the author only (unchanged rule, friend
// authors now allowed). DMs: author OR the recipient (own identity), and
// recipient-trust applies only when WE are the DM's recipient -- a
// hostile "recipient-signed" grant from anyone else stays untrusted.
val to = m.payload["to"] as? String
val accepted: Set<String> = when {
    m.kind == "post" -> setOf(m.identityPub)
    m.kind == "dm" && to == ownIdentityPub -> setOf(m.identityPub, ownIdentityPub)
    else -> setOf(m.identityPub)
}
val grants = store.wrapGrantsFor(m.msgId, accepted)
```
The B.2 `if (m.identityPub != ownIdentityPub) continue` own-author skip is REMOVED (that was B.2's scope fence; this slice replaces it with the rule above).

- [ ] **Step 1: Write the failing JVM tests** (in `DecryptPassTest.kt`, reusing its fixture-material helpers): (a) friend-authored post + author-signed grant to the phone → decrypts; (b) friend-authored DM to own identity, phone key ONLY via a grant signed by OWN identity (recipient) → decrypts; (c) REQUIRED negative: same DM but the "recipient-style" grant signed by a THIRD identity (neither author nor recipient) → skipped; (d) B.2 regression: own post + own-sent DM still decrypt; (e) newest-first ordering intact.
- [ ] **Step 2: Run — expect FAIL** (signature/entitlement not implemented).
- [ ] **Step 3: Implement** (store signature change + both impls + the entitlement block above; update all existing call sites/tests to pass sets).
- [ ] **Step 4: Full module JVM suite + `assembleDebug` → green. Commit** `feat(b2c): DecryptPass entitlement rule - friend posts via author grants, received DMs via recipient-signed grants`

---

### Task 3: Author names + friends-stat fix (store → module → UI)

**Files:**
- Modify: `SyncStore.kt`/`SqliteSyncStore.kt` (`profileNames(): Map<String, String>` — latest KIND_PROFILE payload `name` per identity, plaintext, latest by `(created_at, seq)`)
- Modify: `DecryptPass.kt` (`Decrypted` gains `author: String` — the resolved display name)
- Modify: `TorManagerModule.kt` (feed maps carry `author`; friends stat excludes the own identity)
- Modify: `index.ts` (`FeedItem.author`), `App.tsx` (render author; row = `author · kind · time` + text)
- Test: `DecryptPassTest.kt` name-resolution cases

**Interfaces:**
- Produces: `Decrypted(msgId, kind, author, text, createdAt)`; name resolution: profile name if stored, else `"friend-" + identityPub.take(8)`; own identity → own profile name or `"me"`.

- [ ] **Step 1:** failing tests: profile-name resolution (friend with a stored profile message → name; without → prefix fallback; own → "me"/own name; latest profile wins).
- [ ] **Step 2:** implement store accessor + DecryptPass wiring + module marshal + `index.ts`/`App.tsx`; friends stat: count identities minus self (confirm where the current count originates — `getSyncStats` or the HAVE `known` ingestion — and exclude `fixture.cert.identity_pub`).
- [ ] **Step 3:** module suite + `assembleDebug` + `tsc --noEmit` A/B + vitest → green. Commit `feat(b2c): author names on the feed + friends stat excludes self`

---

### Task 4: Two-node desk loopback gate

**Files:**
- Modify: `android_tor_spike/tools/sync_loopback_node.py` (a second, FRIEND node in-process; `hearth/demo.py`'s multi-node cast is the prior art for befriending + gossip wiring over loopback TCP)
- Modify: `SyncLoopbackTest.kt` (new test `phoneReadsFriendContentEndToEnd`)

Scenario the script must build (all before the phone connects, except where noted):
1. home node (the existing seeded one) + friend node, befriended both ways; friend posts wall content ("friend wall post one/two"); friend sends home a DM ("old dm from friend") — at this point NO phone enc key exists anywhere.
2. nodes gossip until both hold each other's content (bounded rounds + assert, not sleep).
3. phone sync 1 (over the wire, as in B.2's gate): pushes enckey. Script then: gossips the enckey to the friend node; friend node runs its STOCK `maintain_wrap_grants` (mints wall grants naming the phone); home runs `maintain_own_device_grants` + `maintain_received_dm_grants`; friend sends a NEW DM ("new dm from friend") which now inline-wraps to the phone; final gossip; emit the `maintained` signal line (B.2's handshake pattern).
4. phone sync 2 pulls; test runs DecryptPass and asserts ALL of: both friend wall texts, "old dm from friend" (recipient-grant leg — assert its key came via a grant signed by the OWN identity, mirroring B.2's independent grant-path check), "new dm from friend", own B.2 content still present, and author names resolve (friend node's profile name).

- [ ] Extend script + test; RED-control: comment out the `maintain_received_dm_grants` call → the old-DM assertion must fail while the rest pass; re-enable → green. GATE: full module suite (all loopback tests) green. Commit `feat(b2c): two-node desk gate - phone reads friend walls, new DMs, and recipient-backfilled old DMs`

---

### Task 5: On-device run + report

**Files:**
- Create: `android_tor_spike/BRICK_B2C_REPORT.md`

- [ ] Build both APKs, install on the G20 (`adb -s ZY32DLZQ2N install -r ...`; Play Protect may need August's tap). Report mirrors `BRICK_B2_REPORT.md`: desk-gate summary, run steps (desktop via `python -m hearth serve --dir %APPDATA%\Kreds --http-port P --gossip-port P --tor`, UNLOCK via web UI first — both B.2 field lessons), verify: old friend DMs readable with names (desktop-only dependency), friend walls best-effort (field friends intermittent), own content regression. **PAUSE — August drives.** Fill verdict. Commit `docs(b2c): on-device friend-content run + report`

---

## Self-Review (performed at write time)

**Spec coverage:** trust extension → Task 1 (mint) + Task 2 (consume); phone entitlement → Task 2; names + friends label → Task 3; two-node gate incl. stock-friend-sweep leg → Task 4; on-device → Task 5. No-friend-side-change: nothing in any task touches friend-node behavior beyond running stock code in the gate.
**Type consistency:** `wrapGrantsFor(msgId, acceptedSigners: Set<String>)` (Tasks 2/4), `Decrypted(msgId, kind, author, text, createdAt)` (Tasks 3/4), `maintain_received_dm_grants(now=None)` (Tasks 1/4).
**Judgment calls flagged:** `_received_dms` store iterator + `_latest_own_wrap_grants` exact name (Task 1 implementer-confirms, per B.2's successful pattern); friends-stat origin (Task 3); befriend/gossip plumbing mirrors `demo.py` (Task 4). The five Task-1 tests and Task-2's hostile-signer negative are REQUIRED, never stubs.
