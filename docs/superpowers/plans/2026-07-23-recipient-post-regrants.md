# Recipient-Side Post Re-Grants Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A friend-authored post readable by any of the recipient's devices becomes readable on ALL of them without the author online — hearth grows a fourth grant sweep (`maintain_received_post_grants`) and both consumers (hearth `_content_key`, phone `DecryptPass.resolveWrap`) learn the matching recipient-signed trust rule.

**Architecture:** Mirror B.2c's `maintain_received_dm_grants` (node.py:2239+) for friend-authored KIND_POST: recipient-signed, full-coverage wrap_grants to own enrolled devices, minted at boot + sync tick. Consumer rule (byte-identical on desktop + phone): a post grant is trusted when author-signed (unchanged) OR own-identity-signed AND the post's author ∈ known identities. Spec: `docs/superpowers/specs/2026-07-23-recipient-post-regrants-design.md`.

**Tech Stack:** Python (hearth, pytest), Kotlin (DecryptPass, JVM tests), sync_loopback_node.py harness.

## Global Constraints

- **Branch:** `brick-post-regrants`. Commit prefix `feat/fix/docs/test(regrants)`, lowercase. **NO AI / Co-Authored-By / "Generated with" trailers.**
- **Trust rule (verbatim, both consumers):** post grant trusted iff signed by the post's AUTHOR (existing rule, unchanged) OR (signed by OWN identity AND post author ∈ known identities minus self AND the wrap consumed targets this device). DM/response rules untouched.
- **Sweep invariants (same as siblings — read node.py:2047-2259 + store.py:480-496 BEFORE writing a line):** full-coverage mints (prune safety depends on it); targets = `store.enckeys(own)` minus self ONLY (never a friend device); no cross-sweep stripping (received posts have author ≠ self so `maintain_wrap_grants`/`maintain_own_device_grants` never iterate them — verify, don't assume, incl. the wall-post keyspace note at node.py:2123-2132); mint only when this node can actually recover the content key.
- **Scope:** KIND_POST any placement, friend-authored, decryptable-here. Stories/responses/profile untouched; `maintain_received_dm_grants` untouched.
- **Compatibility additive:** no schema change; old clients ignore the new grants (author-only rule rejects them silently).
- **Parity:** hearth consumer and Kotlin consumer enforce the identical predicate; the loopback gate proves the phone end against a real node's real sweep.

**Test commands:** hearth: `.venv/Scripts/python.exe -m pytest -q` from repo root (baseline on this branch: run first and record — main-derived, expect ~1098-2=1096-area; pin actual). Kotlin (from `android_tor_spike/app/android`, `JAVA_HOME=/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot`): `./gradlew :tor-manager:testDebugUnitTest`; XML glob `../modules/tor-manager/android/build/test-results/testDebugUnitTest/*.xml` (baseline: run first — main-derived, expect 246-area; pin actual). Release APK + G20 install per the established gotchas (force-stop, Play Protect).

---

## File Structure

- `hearth/node.py` (modify) — `maintain_received_post_grants` + `_content_key` post-branch trust extension.
- `hearth/api.py` (modify) — boot-sweep call (api.py:97-100 block).
- `hearth/sync.py` (modify) — tick-sweep call (sync.py:249-250,276 block).
- `tests/test_received_post_grants.py` (create) — sweep + consumer + prune + offline-author scenario.
- `.../tormanager/DecryptPass.kt` (modify) — `resolveWrap` post rule.
- `.../test/.../DecryptPassTest.kt` (modify) — entitlement matrix.
- `android_tor_spike/tools/sync_loopback_node.py` (modify) — `regrants` scenario; `.../test/.../SyncRegrantLoopbackTest.kt` (create).
- `android_tor_spike/BRICK_REGRANTS_REPORT.md` (create, Task 5).

---

## Task 1: hearth `maintain_received_post_grants` sweep

**Files:**
- Modify: `hearth/node.py` (new method beside `maintain_received_dm_grants`, node.py:2239+), `hearth/api.py:97-100`, `hearth/sync.py:249-250,276`
- Test: `tests/test_received_post_grants.py` (create; find how existing sweep tests are structured — grep tests/ for `maintain_received_dm_grants` and mirror that harness)

**Interfaces:**
- Produces: `HearthNode.maintain_received_post_grants(now=None)` — same signature family as siblings. For each stored KIND_POST with `msg.cert.identity_pub ∈ store.known_identities()` minus self, where `self._content_key(msg)`-style recovery succeeds (reuse the node's existing key-recovery machinery — read how `maintain_received_dm_grants` recovers the DM key and mirror; do NOT hand-roll a second unwrap path): mint via the same `make_wrap_grant` the siblings use, wraps = full coverage of `store.enckeys(self.identity_pub)` minus this device? NO — check the sibling: DM sweep derives targets "solely from the own identity's enckeys" (node.py:2253) — match its exact minus-self/current-device semantics. Re-mint condition mirrors the sibling's staleness check (enc-rotation coverage), NOT unconditional every tick.

- [ ] **Step 1: Failing tests** (author-offline is the point):

```python
def test_new_own_device_reads_friend_post_after_sweep(tmp_path):
    # A (friend/author) and B (recipient) sync a post while both live.
    # A goes OFFLINE (simply never called again). B enrolls/rotates a
    # fresh enc key A never saw. B.maintain_received_post_grants().
    # Assert: the fresh key unwraps the post's content key via the
    # recipient-signed grant (drive B's real read path, not a hand rolled
    # unwrap). Mirror the two-node scaffolding of the received-dm tests.

def test_sweep_skips_undecryptable_and_nonfriend_posts(tmp_path):
    # a post B holds but cannot decrypt (no wrap for any B device) -> no
    # grant minted; a post from an identity NOT in known_identities -> no
    # grant minted.

def test_full_coverage_and_prune_safety(tmp_path):
    # two sweeps across an enc rotation: latest grant covers ALL current
    # own devices; store.prune_superseded_wrap_grants leaves the latest;
    # the pruned state still lets every current device unwrap.

def test_sibling_sweeps_untouched(tmp_path):
    # run all four sweeps together; assert own-authored + dm grant sets
    # are byte-identical to a run without the new sweep (no cross-sweep
    # stripping / keyspace collision).
```

- [ ] **Step 2: Run → fail.** [ ] **Step 3: Implement** (sweep + the two wiring calls beside the siblings). [ ] **Step 4: Run → pass; FULL hearth suite zero regressions (record baseline first).** [ ] **Step 5: Commit** — `feat(regrants): maintain_received_post_grants sweep (recipient-signed, full-coverage)`.

---

## Task 2: hearth consumer trust rule (`_content_key` post branch)

**Files:**
- Modify: `hearth/node.py` (`_content_key`, node.py:2813+ — read the FULL function first: how it resolves wraps for each kind today, and exactly how B.2c's DM recipient-branch consults `store.wrap_grants(msg_id, self.identity_pub)` gated on `p["to"] == self.identity_pub`)
- Test: `tests/test_received_post_grants.py` (extend)

**Interfaces:** the KIND_POST branch additionally unions `store.wrap_grants(msg.msg_id, self.identity_pub)` IFF `msg.cert.identity_pub in store.known_identities()` and ≠ self — author-signed grants keep precedence semantics identical to the DM branch's union order (read which side wins there and match). Routing note: verify `messages_not_in`/grant routing needs NO change (grants route by their wraps' device_pubs, all own devices here — state the verification in the test file's docstring, mirroring how B.2c handled the same question; if routing DOES need a change, that's a spec gap — stop and report).

- [ ] **Step 1: Failing tests:**

```python
def test_content_key_honors_recipient_grant_for_friend_post(tmp_path):
    # B's SECOND desktop device (no inline wrap, no author grant for its
    # key) decrypts a friend post via B-signed grant through the real
    # read path (feed/cache_message_keys), not _content_key called bare.

def test_content_key_rejects_third_identity_grant(tmp_path):
    # C (a third identity) mints a recipient-SHAPED grant for A's post
    # targeting B's device -> B must NOT decrypt via it.

def test_content_key_rejects_own_grant_for_nonfriend_post(tmp_path):
    # B somehow holds a post from stranger S plus a B-signed grant for it
    # (compromised-device model) -> read path must NOT decrypt it.
```

- [ ] **Step 2: Run → fail.** [ ] **Step 3: Implement.** [ ] **Step 4: Run → pass; full suite.** [ ] **Step 5: Commit** — `feat(regrants): _content_key trusts recipient-signed grants for friend posts`.

---

## Task 3: phone consumer (`DecryptPass.resolveWrap`)

**Files:**
- Modify: `.../tormanager/DecryptPass.kt` (`resolveWrap`, DecryptPass.kt:469-500 area — read the whole function + its trust doc block; the new branch mirrors the DM recipient-branch structurally)
- Test: `.../test/.../DecryptPassTest.kt`

**Interfaces:** posts: existing author-signed lookup unchanged; ADD — when no author-signed wrap resolves, consult grants authored by `ownIdentityPub` IFF `m.identityPub in store.knownIdentities()` and ≠ own; same newest-wins ordering the DM branch uses; update the file-top trust-model doc (DecryptPass.kt:7-30) to describe the new rule with the same precision as the B.2c text.

- [ ] **Step 1: Failing tests** (extend the existing entitlement matrix — read how DecryptPassTest builds friend posts + grants):

```kotlin
@Test fun friendPostDecryptsViaOwnRecipientSignedGrant() { /* friend post,
    no wrap for phone key, own-identity-signed grant wrapping to phone key
    -> renders; assert text+blob key recovered */ }
@Test fun thirdIdentityRecipientShapedGrantSkipped() { /* same but grant
    signed by identity C -> post absent from feed */ }
@Test fun ownGrantForNonFriendAuthorSkipped() { /* author NOT in
    knownIdentities + own-signed grant -> absent */ }
@Test fun authorSignedPathByteUnchanged() { /* regression: existing
    author-grant fixture still renders identically */ }
```

- [ ] **Step 2: Run → fail.** [ ] **Step 3: Implement.** [ ] **Step 4: Run → pass; full module suite.** [ ] **Step 5: Commit** — `feat(regrants): resolveWrap trusts own recipient-signed grants for friend posts`.

---

## Task 4: loopback gate

**Files:**
- Modify: `android_tor_spike/tools/sync_loopback_node.py` (`regrants` scenario)
- Create: `.../test/.../SyncRegrantLoopbackTest.kt`

- [ ] **Step 1: Scenario** — home node B + friend author A seeded EXACTLY as the field case: A composes a post (with photo) wrapped only to keys B held THEN; the phone's enc key is enrolled AFTER (fresh EncKeys — the phone's normal path); A's node is then gone (only B runs). B runs the REAL `maintain_received_post_grants`. Emit `{"event":"regrants_ready"}` plus a `no_sweep` sub-scenario that skips the sweep (negative control).
- [ ] **Step 2: Kotlin test** — phone (fresh enc key) syncs from B, DecryptPass renders the friend post: assert text + photo blob decrypts (decrypt-on-read path). `no_sweep` control: same fixture WITHOUT the sweep → post NOT rendered (proves the recipient grant, not another path, unlocked it). Fail-closed; never weaken.
- [ ] **Step 3: Run; Step 4: full JVM + full pytest; Step 5: Commit** — `test(regrants): loopback gate -- offline-author friend post renders via recipient re-grant`.

---

## Task 5: On-device DoD + report + PAUSE

- [ ] **Step 1: Desk-gate sweep** (full pytest, full JVM, tsc 0-new, vitest, assembleRelease) — record.
- [ ] **Step 2: Install RELEASE on the G20** (force-stop; Play Protect). NOTE: coordinate with the pairing-slice state — the phone may be freshly re-paired or mid-DoD; the report must state which build/branch the apk came from (this branch does NOT include the pairing slice; if August's phone is now paired via brick-first-load's build, flag that installing this branch's apk removes the FirstLoad screen — recommend running this DoD on whichever branch state August's phone is in, or PAUSE and let August sequence the two slices' merges first. Surface this to the coordinator BEFORE installing; do not silently downgrade the phone.)
- [ ] **Step 3: DoD (August drives)** — the field scenario: laptop offline; desktop (this branch) runs its sweep; phone syncs; the laptop account's old posts render (text + photos). Regression: own posts + DMs unchanged.
- [ ] **Step 4: BRICK_REGRANTS_REPORT.md** — gates, gate proof, DoD, honest limits (spec verbatim: one-decryptor-online requirement, readability-not-trust, store growth class), tickets.
- [ ] **Step 5: Commit + PAUSE** — `docs(regrants): on-device proof record + DoD`. Merge is August's call.

---

## Self-Review

**1. Spec coverage:** sweep → T1; hearth consumer + routing verification → T2; phone consumer → T3; parity gate incl negative control → T4; DoD/report → T5. Compatibility is additive (no task needed). ✓
**2. Placeholder scan:** T1's key-recovery and re-mint staleness mechanics are read-and-mirror steps anchored to the named sibling (node.py:2239+ + 2047+); T2's routing check has an explicit stop-and-report branch. No TBDs.
**3. Type consistency:** sweep name `maintain_received_post_grants` consistent T1/T2-docstring/T4-scenario; trust predicate identical wording T2/T3; scenario events `regrants_ready`/`no_sweep` consistent T4.

**Implementer notes:** the prune-safety invariant is the landmine (store.py:480-496 — full-coverage or data loss); read ALL THREE sibling sweeps before writing. The Kotlin rule must not weaken the author-signed path — additive branch only. The APK-install sequencing caveat in T5 Step 2 is real: surface it, don't guess.
