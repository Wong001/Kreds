# Friend Peering + Dynamic Cadence (Arc 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The phone gains a peer table and dials friends directly (learning their onion addresses via the home-node relay), and its fixed 15-min sync timer is replaced by event triggers + adaptive backoff — so a phone whose desktop is offline still exchanges with friends, on a battery-smart schedule.

**Architecture:** Port hearth's peer table + `_merge_peer_address` + peer-loop into the phone (Part A, Tasks 1-5), then replace the fixed scheduler with adaptive backoff + on-compose/on-open triggers (Part B, Task 6). The phone is a peering ENDPOINT (dials friends, answers friends, does NOT relay others' addresses onward). Spec: `docs/superpowers/specs/2026-07-23-friend-peering-dynamic-cadence-design.md`.

**Tech Stack:** Kotlin (tor-manager module, JVM tests), the arc-1/2 GossipServer/KotlinSync/KotlinHandshake/SyncRunner/TorNodeService, sync_loopback_node.py, RN WebShell.

## Global Constraints

- **Branch:** `brick-friend-peering`. Commit prefix `feat/fix/docs/test(peering)`, lowercase. **NO AI / Co-Authored-By / "Generated with" trailers.**
- **Parity (verbatim from hearth):** peer table = `peers(address TEXT PRIMARY KEY, identity_pub TEXT)` (store.py:39-40), `add_peer`/`list_peers`/`remove_peer`/`address_for` (store.py:217-239); `_merge_peer_address` (sync.py:93-131 — onion-preferred: an onion addr always kept, a non-onion addr stored only if no onion known for that identity; onion eviction HOST-keyed: same-host different-port evicted, different onion hosts coexist); peer-loop (sync.py:282-293 — skip our OWN onion `onion_host(addr)==own`, onion-throttle `ONION_SYNC_INTERVAL`); HAVE relay merge (sync.py:773-781 — merge peer's `addr` + each `peers` entry whose identity is `is_known`); defriend removePeer (node.py:1770 `remove_peer_identity`).
- **Security (the peer-loop's critical change):** the phone now dials FRIENDS, not only its own home node. `runTransport` currently pins `peerCert.identity_pub == fx.cert.identity_pub` (own). For a friend dial it must accept a KNOWN identity (`peerCert.identity_pub in knownIdentities`) AND, when the peer row carries an expected `identity_pub`, require it matches (a wrong/hostile address must never sync us into a stranger — refuse). Strangers still refused. Entitlement give-side (arc 1) scopes serving by the authenticated friend; ingest gates unchanged.
- **Endpoint not relay:** the phone sends `peers: []` in its own HAVE (does NOT disclose its friend graph's addresses onward). It KEEPS advertising its own onion in HAVE `addr` (arc 2).
- **Cadence:** replace `SYNC_INTERVAL_MS = 900_000` fixed timer with: event triggers (on-compose → sync now; on-app-open/resume → sync now) + adaptive backoff (`BASE_SYNC_MS ~10min`, ×2 per idle sweep → `MAX_SYNC_MS ~1hr` cap, reset to base on new content / event / active use). In-memory backoff state (resets on service restart — acceptable).
- **Non-destructive SQLite** (peers table via CREATE-IF-NOT-EXISTS onOpen, DB_VERSION unchanged — the keys/pending_outbound/meta precedent).
- **Reuse:** arc-1/2 GossipServer/serve/respondHandshake, KotlinHandshake.authOnlyOverStream, KotlinSync.run, the store dual-read, applyDefriendNotice.

**Test commands** (git-bash; `JAVA_HOME=/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot`; from `android_tor_spike/app/android`): `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.<Class>"`; XML glob `../modules/tor-manager/android/build/test-results/testDebugUnitTest/*.xml` (baseline: run first). Release + G20 per gotchas. Loopback tests spawn `.venv` python.

---

## File Structure

- `SyncStore.kt`/`SqliteSyncStore.kt`/`InMemorySyncStore` (modify) — peer table + `mergePeerAddress`.
- `KotlinPairing.kt` (modify) — seed home node + friends' addresses at install.
- `KotlinSync.kt` (modify) — HAVE-phase address learning (run + serve).
- `SyncRunner.kt` (modify) — loop over peers; per-peer identity acceptance.
- `TorNodeService.kt` + `TorManagerModule.kt` (modify) — adaptive scheduler + event triggers; `Compose*`/`LocalApi` (modify) — on-compose sync request; `WebShell.tsx`/`FirstLoad.tsx` (modify) — on-resume sync.
- Tests: per-file JVM; `SyncPeerLoopbackTest.kt` (new); `android_tor_spike/BRICK_PEERING_REPORT.md`.

---

## Task 1: Peer table (`addPeer`/`listPeers`/`removePeer`/`addressFor`)

**Files:** Modify `SyncStore.kt` (interface + InMemory), `SqliteSyncStore.kt`; Test `SyncStoreTest.kt`

**Interfaces:** `data class Peer(val address: String, val identityPub: String?)`; `addPeer(address, identityPub)` (INSERT OR REPLACE, address PK — mirror store.py:217); `listPeers(): List<Peer>` (store.py:223); `removePeer(address)` (store.py:229); `addressFor(identityPub): String?` (store.py:234, first match). SQLite `peers(address TEXT PRIMARY KEY, identity_pub TEXT)` via non-destructive onOpen CREATE-IF-NOT-EXISTS.

- [ ] **Step 1: Failing tests** — addPeer→listPeers round-trip; INSERT OR REPLACE (same address, new identity → one row); removePeer; addressFor returns the address for an identity, null when absent; non-destructive (a peer survives a store reopen — dir-based InMemory can't reopen, so pin the SQLite schema-add pattern by inspection + the meta/keys precedent). Read SyncStoreTest seeding idioms.
- [ ] **Step 2-4: Run→fail→implement both impls→pass; full suite.** [ ] **Step 5: Commit** — `feat(peering): peer table (addPeer/listPeers/removePeer/addressFor)`.

---

## Task 2: `mergePeerAddress` (onion-preferred, host-keyed eviction)

**Files:** Modify `SyncStore.kt` (a store extension or helper, like ingestRevocation), `SqliteSyncStore.kt` if needed; Test `SyncStoreTest.kt`

**Interfaces:** `mergePeerAddress(store, identity: String, addr: String)` porting sync.py:93-131 EXACTLY. `isOnion(addr)` predicate (mirror hearth `_is_onion` — a `.onion:port` host). If `isOnion(addr)`: evict this identity's non-onion rows AND same-host-different-port onion rows, then `addPeer(addr, identity)`. Else (non-onion): add only if this identity has no onion row yet.

- [ ] **Step 1: Failing tests** (the exact hearth cases): a new onion addr for an identity is stored; a non-onion addr is stored only if no onion known (a non-onion does NOT shadow/replace a known onion — assert the onion survives + the non-onion is dropped); a same-host different-port onion evicts the stale-port row (one row, the new); two DIFFERENT onion hosts for one identity coexist (both rows — multi-device); merging the identical onion twice is idempotent.
- [ ] **Step 2-4: Run→fail→implement→pass; full suite.** [ ] **Step 5: Commit** — `feat(peering): mergePeerAddress (onion-preferred, host-keyed eviction)`.

---

## Task 3: Address learning — pairing install + HAVE relay

**Files:** Modify `KotlinPairing.kt` (installPackage), `KotlinSync.kt` (run + serve HAVE); Test `KotlinPairingTest.kt`, `KotlinSyncTest.kt`

**Interfaces:**
- `KotlinPairing.installPackage`: currently drops the package's `peers` list (KotlinPairing.kt:255) — instead, for each `{identity_pub, address}` entry, `mergePeerAddress(store, identity_pub, address)` GATED on `store.knownIdentities().contains(identity_pub)` (a friend we know) AND `identity_pub != own`/not our own onion. ALSO seed the HOME NODE: add `my_addr` (= the returned Identity.onion_addr) as a peer for the OWN identity (`mergePeerAddress(store, ownIdentity, my_addr)`) so the peer-loop (Task 4) dials the home node like any peer.
- `KotlinSync` HAVE (both run + serve): the peer's `addr` (its own onion) → `mergePeerAddress(store, peerCert.identity_pub, addr)` if is_known + addr non-empty + not our own onion (mirror sync.py:774-776); each `peers[]` entry `{identity_pub, address}` whose `identity_pub` is `is_known` → `mergePeerAddress` (the transitive relay, mirror sync.py:777-781). Preserve HAVE phase I/O order + the phone still SENDS `peers: []` (endpoint).

- [ ] **Step 1: Failing tests** — install with a package `peers` list containing a KNOWN friend's addr → peer table gains it; an UNKNOWN identity's addr in the package → NOT added (is_known gate); home node seeded (my_addr as a peer for own identity). HAVE: a peer's HAVE carrying `addr` (its onion) + a `peers` entry for a known friend → both merged; an unknown identity's relayed addr → dropped. Scripted Stream for the HAVE cases.
- [ ] **Step 2-4: Run→fail→implement→pass; full suite; HAVE I/O order preserved.** [ ] **Step 5: Commit** — `feat(peering): learn friend addresses at pairing install + HAVE relay`.

---

## Task 4: `SyncRunner` peer-loop (the phone dials friends)

**Files:** Modify `SyncRunner.kt`; Test `SyncRunnerTest.kt` (or the seam that's JVM-reachable)

**Interfaces:** `runSync` loops over `store.listPeers()` (instead of the single `fx.onion_addr` dial); for each peer, `runTransport(ctx, fx, peer, onProgress)`. `runTransport` gains a `peer: Peer` param: dial `peer.address` (was `fx.onion_addr`); after `authOnlyOverStream`, the identity acceptance CHANGES (the security-critical bit): accept if `peerCert.identity_pub in store.knownIdentities()` (friend or own — was `== fx.cert.identity_pub`); AND if `peer.identityPub != null`, require `peerCert.identity_pub == peer.identityPub` (the address must be who we expected — a wrong/hostile onion is refused). Skip our OWN onion (`onionHost(peer.address) == onionHost(gossip_addr)`, mirror sync.py:282-286). Onion-throttle per `ONION_SYNC_INTERVAL` (an in-memory `lastOnionSync` map, mirror sync.py:287-292 — avoid re-dialing the same onion within the window in one burst). A peer that refuses us (PeerRefused / auth-refused) → record + continue to the next peer (don't abort the whole cycle; keep the row — hearth keeps dialing; a genuinely-gone friend is pruned by defriend, Task 5). Aggregate the per-peer outcomes (the drain loop stays per-peer). The SelfRevoked check + `enterRevokedState` stays the choke point (fires if ANY peer's sync reveals our own revocation).

- [ ] **Step 1: Failing tests** — with 2 peers in the table (home node + a friend), runSync dials BOTH (a fake/injected dialer records the addresses dialed); own-onion peer is skipped; a peer whose AUTH'd identity is NOT known → that peer's sync fails (refused) but the loop continues to the next; a peer-row with an expected identity that the AUTH'd cert doesn't match → refused (wrong-address guard); onion-throttle skips a same-onion re-dial within the window. (Use whatever dial-injection seam SyncRunnerTest has; if runTransport isn't unit-testable [real TorEngine], extract the peer-loop + acceptance logic into a testable helper and unit-test that; the live dial is Task 7/8.)
- [ ] **Step 2-4: Run→fail→implement→pass; full suite; the single-home-node case still works (regression — home node is now a peer row).** [ ] **Step 5: Commit** — `feat(peering): SyncRunner dials all peers (known-friend acceptance, own-onion skip, onion throttle)`.

---

## Task 5: Defriend removes the peer row

**Files:** Modify `KotlinSync.kt`/wherever `applyDefriendNotice` lives (arc 2); Test the defriend test + a peer-removal assertion

**Interfaces:** `applyDefriendNotice` (arc 2: purgeAuthoredBy + removeIdentity) now ALSO `store.removePeer` for the defriended author's address(es) — mirror hearth `remove_peer_identity` (node.py:1770). So after a defriend, the phone stops dialing that ex-friend (their peer row is gone). Find the defriended identity's peer rows via `listPeers().filter { it.identityPub == author }` → removePeer each.

- [ ] **Step 1: Failing tests** — seed a peer for a friend, apply a valid defriend of that friend → the peer row is removed (listPeers no longer contains it) + the existing defriend effects (removeIdentity, purgeAuthoredBy) still hold; a defriend of an identity with no peer row → no error. Cross with Task 4: after defriend, the peer-loop no longer dials them.
- [ ] **Step 2-4: Run→fail→implement→pass; full suite.** [ ] **Step 5: Commit** — `feat(peering): defriend removes the ex-friend's peer row`.

---

## Task 6: Adaptive-backoff scheduler + event triggers

**Files:** Modify `TorNodeService.kt` (scheduler), `TorManagerModule.kt` (triggers/bridge), `LocalApi.kt`/the compose routes (on-compose), `WebShell.tsx`/`index.ts` (on-resume); Test a pure `SyncScheduler`/backoff-math unit

**Interfaces:**
- Extract the interval math into a pure testable unit: `AdaptiveBackoff(baseMs, maxMs)` with `nextInterval(lastSweepPulledNew: Boolean): Long` — reset to base when `lastSweepPulledNew` (or on `reset()` for an event), else `min(current*2, maxMs)`. JVM-testable.
- `TorNodeService`: replace `scheduleAtFixedRate(syncCycle, 900_000, 900_000)` with a self-rescheduling task: run a sweep, compute the next interval from whether the sweep pulled new content (SyncOutcome across peers), `schedule(next)`. On any event trigger (compose/open), `reset()` to base + run a sweep now.
- Event triggers: (a) ON COMPOSE — the compose routes (post/DM/response/react) request an immediate sync after enqueuing (a `TorNodeService.requestSyncNow()` / broadcast that resets backoff + kicks a sweep); (b) ON APP OPEN/RESUME — WebShell already syncs on mount; add a resume listener (AppState 'active') → requestSyncNow. Wire the bridge.
- Keep the process-wide sync lock + Doze survival (Brick A/C foreground service).

- [ ] **Step 1: Failing tests** — `AdaptiveBackoff`: base on first; idle sweeps double toward the cap (10→20→40→…→cap, stays at cap); a sweep that pulled new → reset to base; `reset()` (event) → base. (The scheduler-wiring + the on-compose/on-resume triggers are on-device/partial — test the backoff MATH in isolation; note the wiring is DoD-verified.)
- [ ] **Step 2-4: Run→fail→implement→pass; full suite; tsc 0-new (RN resume listener); vitest.** [ ] **Step 5: Commit** — `feat(peering): adaptive-backoff scheduler + on-compose/on-resume sync triggers`.

---

## Task 7: Loopback gate — phone dials a friend + a friend dials the phone

**Files:** Modify `sync_loopback_node.py`; Create `SyncPeerLoopbackTest.kt`

- [ ] **Step 1: Scenario** — (1) PHONE-DIALS-FRIEND: seed the phone store with a FRIEND identity + the friend node's loopback address in the peer table; a real hearth FRIEND node listens; the phone's SyncRunner peer-loop dials it → mutual AUTH + exchange; assert content flows (the friend serves the phone entitled content, the phone pushes its own) and entitlement is respected (an inner-ring/non-entitled item the friend holds but the phone isn't entitled to is NOT delivered — reuse the over-serve negative). (2) FRIEND-DIALS-PHONE: a real hearth friend node DIALS the phone's GossipServer (arc-2 reachability, but the dialer is a FRIEND not the home node) → the phone serves it entitled content; a stranger is refused.
- [ ] **Step 2: Kotlin test** — mirror the arc-1/2 loopback idioms; assert both directions + the negatives. Fail-closed; a divergence (phone dials a stranger, over-serves, or won't dial a known friend) = BLOCKED, never weaken.
- [ ] **Step 3-4: Run; full JVM + full hearth pytest; Step 5: Commit** — `test(peering): loopback gate -- phone dials a friend + a friend dials the phone`.

---

## Task 8: On-device DoD + report + PAUSE

**Files:** Create `android_tor_spike/BRICK_PEERING_REPORT.md`

- [ ] **Step 1: Desk-gate sweep** — full JVM, pytest, tsc 0-new, vitest, assembleRelease. Record.
- [ ] **Step 2: Install RELEASE on the G20** (force-stop; Play Protect).
- [ ] **Step 3: On-device DoD (August drives) — the headline:** with the DESKTOP (home node) OFFLINE, the phone still exchanges with a friend (the phone dials the friend directly using a learned+persisted onion address); a friend's node dialing the phone's onion delivers content without the phone polling; the ADAPTIVE cadence observed (an idle phone's sweeps space out; composing triggers an immediate push; reopening the app syncs). Plus regression: home-node sync + First-Load pairing + revoke-wipe still work.
- [ ] **Step 4: BRICK_PEERING_REPORT.md** — desk gates; the loopback proof (phone-dials-friend + friend-dials-phone, over-serve negative); the cadence rework; honest boundary (spec Honest limits VERBATIM: endpoint-not-relay, pure-phone-mesh bootstrapping = arc 4, friend-add still desktop, in-memory backoff, nudge folds next, battery-aware = follow-up); follow-up tickets (multi-hop relay-from-phone, arc 4 store-and-forward, the nudge, battery-aware cadence, + carried arc-2 tickets).
- [ ] **Step 5: Commit + PAUSE** — `docs(peering): friend-peering + cadence proof record + DoD`. Merge is August's call.

---

## Self-Review

**1. Spec coverage:** peer table → T1; mergePeerAddress → T2; address learning (install + HAVE relay) → T3; peer-loop + friend acceptance → T4; defriend removePeer → T5; adaptive cadence + triggers → T6; loopback (both directions + entitlement) → T7; DoD → T8. Endpoint-not-relay (send peers:[]) → T3. Home-node-as-peer-row → T3/T4. All spec sections mapped.
**2. Placeholder scan:** T4's testability note (extract the peer-loop/acceptance helper if runTransport isn't unit-testable) is an explicit fallback, not a TBD; the live dial is T7/T8. T6's scheduler wiring is on-device-partial with the backoff MATH JVM-tested — honest, matches the module's Robolectric constraint. No bare TODOs.
**3. Type consistency:** `Peer(address, identityPub?)` + `addPeer/listPeers/removePeer/addressFor` (T1) consumed T2/T3/T4/T5. `mergePeerAddress(store, identity, addr)` (T2) consumed T3. `runTransport(ctx, fx, peer, onProgress)` (T4) — the peer param threads the loop. `AdaptiveBackoff(baseMs, maxMs).nextInterval/reset` (T6). Consistent.

**Implementer notes:** the peer-loop's friend-acceptance is the security change — the phone now trusts KNOWN friends (not just own home identity), so refuse strangers + honor the expected-identity row guard; the loopback gate (T7) proves it at the real wire. Endpoint-not-relay: the phone SENDS `peers: []` (don't leak the friend graph's addresses). The home node becomes a peer row (seeded at install) — verify the single-home-node sync still works after the loop change. Cadence: the fixed 15-min timer is GONE, replaced by adaptive backoff + event triggers.
