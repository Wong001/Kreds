# Kotlin Gossip Server (Arc 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The phone accepts an authenticated inbound connection on loopback and completes a full sync round as the RESPONDER, serving each peer exactly its entitled content — proven by a real hearth node dialing the phone.

**Architecture:** Port the responder half of hearth `_session` (sync.py:579-825) to Kotlin — same frames the phone already speaks as initiator, in read-then-write order, plus the give-side entitlement filter (`messages_not_in`, store.py:702). An accept loop in the foreground `TorNodeService` binds `127.0.0.1` (onion = arc 2). Spec: `docs/superpowers/specs/2026-07-23-kotlin-gossip-server-design.md`.

**Tech Stack:** Kotlin (tor-manager module, JVM tests), the existing `Stream`/`KotlinWire`/`KotlinHandshake`/`KotlinSync`/`SqliteSyncStore` surface, the `sync_loopback_node.py` real-node harness.

## Global Constraints

- **Branch:** `brick-gossip-server`. Commit prefix `feat/fix/docs/test(gossip)`, lowercase. **NO AI / Co-Authored-By / "Generated with" trailers.**
- **Parity (verbatim from hearth):** the responder is byte-identical to hearth `_session` (sync.py:579-825) in reverse I/O order (responder reads-then-writes each phase where the initiator writes-then-reads — this is `_swap`'s role difference; KotlinHandshake.kt:76 already documents "responder side: read-then-write"). Phase order: HELLO → AUTH → REVOCATIONS → DEFRIENDS → HAVE → MESSAGES → BLOBS.
- **Give-side entitlement (`messages_not_in`, store.py:702-750) — port EXACTLY:** entitled = `{i in knownIdentities if i in peer_known}`; per-kind gates — DM served only if `peer_identity in (author, recipient)`; RING only if `peer_identity == author`; POST/WRAP_GRANT/RESPONSE/RESPONSES served to non-authors only if `peer_devices ∩ wrap-set` (POST additionally unions the author-keyed grant devices `grant_devs[(author, mid)]`); then the seen-delta (`summary_has`) drops what the peer already has. An OVER-SERVE is a privacy breach — this is the load-bearing invariant.
- **Stranger refusal:** unknown identity / failed AUTH → `{"t":"refused"}` then close, byte-parity with hearth (sync.py:630-641). The AUTHENTICATED peer identity (never a frame claim) scopes the give side.
- **Bind `127.0.0.1` only** (no external exposure until arc 2). Coordinate store access with `SyncRunner`'s process-wide `ReentrantLock` (SyncRunner.kt:55).
- **Reuse (do NOT re-port):** `Stream` (Stream.kt:10), `KotlinWire.writeFrameBytes`/frame read, `KotlinWire.verifyCert`/`verifyRaw`/`signRaw`, the fixture dual-reader, `store.summary()` (== hearth `all_summaries`, SqliteSyncStore.kt:151), `store.knownIdentities/deviceViews/getBlob/missingBlobs/ingestMessage`, the existing ingest verify gates (receive side unchanged).

**Test commands** (git-bash; `JAVA_HOME=/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot`; from `android_tor_spike/app/android`): `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.<Class>"`; XML glob `../modules/tor-manager/android/build/test-results/testDebugUnitTest/*.xml` (baseline: run first, expect ~289). Release + G20 per the gotchas (force-stop, Play Protect). Loopback tests spawn `.venv` python.

---

## File Structure

- `SqliteSyncStore.kt` + `SyncStore.kt` (+ `InMemorySyncStore`) (modify) — `messagesNotIn(summaries, entitled, peerIdentity): List<SignedMessage>` + any give-side helpers (`blobSizes`, grant-devs scan).
- `KotlinHandshake.kt` (modify) — a responder auth path (`respondHandshake`).
- `KotlinSync.kt` (modify) — a responder session path (`serve`).
- `GossipServer.kt` (create) — accept loop + per-conn dispatch + lifecycle.
- `TorNodeService.kt` (modify) — start/stop the server.
- `Stream.kt` (modify only if needed) — construct a `Stream` from an accepted `java.net.Socket`.
- Tests: `SqliteSyncStoreTest`/`SyncStoreTest` (give-side matrix), `KotlinHandshakeTest` (responder), `KotlinSyncTest` (responder), `GossipServerTest`, `SyncServeLoopbackTest` (new); `android_tor_spike/BRICK_GOSSIP_REPORT.md`.

---

## Task 1: Give-side entitlement filter (`messagesNotIn`)

**Files:**
- Modify: `SqliteSyncStore.kt`, `SyncStore.kt` (interface + `InMemorySyncStore`)
- Test: `SyncStoreTest.kt`

**Interfaces:**
- Produces: `SyncStore.messagesNotIn(summaries: Map<Pair<String,String>, SeenSet>, entitled: Set<String>, peerIdentity: String): List<SignedMessage>` — the exact `messages_not_in` port (store.py:702-750). Also `SyncStore.blobSizes(hashes: List<String>): Map<String,Long>` if not present (for smallest-first blob give). Read how `summary()`/`deviceViews`/`SeenSet.summary_has` (SeenSet.kt) already work and reuse; the grant-devs scan mirrors the SQL at store.py:711-715 (all KIND_WRAP_GRANT, keyed `(identity_pub, target_id) -> wraps.keys`).

**Design (port store.py:702-750 EXACTLY):** for each stored message ordered by seq: skip if `author !in entitled`; DM → skip unless `peerIdentity in (author, recipient)`; RING → skip unless `peerIdentity == author`; POST → wrap-set = `payload.wraps.keys ∪ grantDevs[(author, mid)]`, skip if `peerIdentity != author && (peerDevices ∩ wrapSet).isEmpty()`; WRAP_GRANT/RESPONSE/RESPONSES → wrap-set = `payload.wraps.keys`, same non-author gate (no grant-union); then `dev = summaries[(author, devicePub)]`; skip if `dev != null && dev.summaryHas(seq)`; else include. `peerDevices = deviceViews(peerIdentity)`.

- [ ] **Step 1: Failing tests** — the entitlement matrix + OVER-SERVE NEGATIVE (mirror how SyncStoreTest seeds messages; read it first):

```kotlin
    @Test fun messagesNotInServesEntitledAndNeverOverServes() {
        // seed: an own POST (kreds), an inner-ring RING record, a friend
        // POST wrapped to the peer's device, a DM to a third party, and a
        // POST NOT wrapped to the peer.
        // peer = a kreds friend (own device set known via deviceViews).
        val out = store.messagesNotIn(emptyMap(), entitled, peerId).map { it.msgId() }
        assertTrue(kredsFriendPostId in out)          // entitled -> served
        assertFalse(innerRingRecordId in out)         // RING author-private
        assertFalse(dmToThirdPartyId in out)          // DM never relays
        assertFalse(postNotWrappedToPeerId in out)    // wrap-set gate
    }
    @Test fun messagesNotInDropsWhatPeerAlreadyHas() {
        // summaries claims the peer has seq N of author A's device -> that
        // message is excluded; seq N+1 still served.
    }
    @Test fun messagesNotInPostUnionsAuthorKeyedGrantDevices() {
        // a friend POST with no inline wrap for the peer but an
        // author-signed wrap_grant naming the peer device -> served;
        // a recipient(non-author)-signed grant naming the peer -> NOT
        // (grant_devs is author-keyed).
    }
```

- [ ] **Step 2: Run → fail.** [ ] **Step 3: Implement** (both store impls). [ ] **Step 4: Run → pass; full module suite.** [ ] **Step 5: Commit** — `feat(gossip): messagesNotIn give-side entitlement filter`.

---

## Task 2: Handshake responder

**Files:**
- Modify: `KotlinHandshake.kt`
- Test: `KotlinHandshakeTest.kt`

**Interfaces:**
- Produces: `KotlinHandshake.respondHandshake(stream: Stream, fixture: Fixture, isKnown: (identityPub: String) -> Boolean, rnd: () -> String = ::randomHex16): HandshakeResult` — the RESPONDER path: read peer HELLO first (verify peer `KotlinWire.verifyCert`; `isKnown(peerIdentity)` else write `{"t":"refused"}` + return `Failed("auth","refused")`), write own HELLO, then mutual AUTH in responder order — verify the peer's device-key signature over OUR nonce, sign THEIR nonce with `fixture.device_priv`. On success return the peer's `CertDict` (identity+device) for the session to scope on. Mirror `runOverStream` (KotlinHandshake.kt:88+) but with read/write order swapped and the added `isKnown` refusal gate (hearth refuses unknown peers at AUTH, sync.py:630-641).

- [ ] **Step 1: Failing tests** — against a scripted `Stream` pair (reuse the fake-Stream pattern in the test file / KotlinPairingTest's ScriptedStream): a known peer completes AUTH → returns its cert; an UNKNOWN peer → `{"t":"refused"}` written + Failed; a peer whose device sig doesn't verify → Failed (no refused-vs-bad-sig oracle beyond hearth's own). Build valid signed certs with real Ed25519 (KotlinPairingTest's fixtures show the idiom).
- [ ] **Step 2: Run → fail.** [ ] **Step 3: Implement.** [ ] **Step 4: Run → pass; full module suite.** [ ] **Step 5: Commit** — `feat(gossip): handshake responder (verify peer, prove self, refuse stranger)`.

---

## Task 3: Sync responder (content phases)

**Files:**
- Modify: `KotlinSync.kt`
- Test: `KotlinSyncTest.kt`

**Interfaces:**
- Consumes: `messagesNotIn`/`blobSizes` (T1); the authenticated `peerCert` from T2.
- Produces: `KotlinSync.serve(stream: Stream, store: SyncStore, fixture: Fixture, peerCert: KotlinWire.CertDict): SyncResult` — the RESPONDER content phases, read-then-write each, mirroring `_session` (sync.py:643-825):
  - REVOCATIONS: read peer revs (ingest via existing path), write own `list_revocations` (phone has none today → empty, but wire the read/ingest of theirs).
  - DEFRIENDS: apply-then-ack per hearth (sync.py:673-721); phone side minimal-but-correct (read+apply notices targeting us, ack).
  - HAVE: read peer HAVE (`known`/`summary`/`addr`/`peers`), write own `{summary: store.summary(), known: knownIdentities(), peers: [], addr: <loopback>}`. Own-device trust: if `peerIdentity == ownIdentity`, adopt peer's `known` (add_identity) — port sync.py:768-772. (Peer-address merge is arc 3; phone has no peer table — read `peers`/`addr` but DROP, documenting it, exactly as pairing install dropped peers.)
  - MESSAGES: `entitled = {i in knownIdentities if i in peerKnown}`; `toSend = messagesNotIn(peerSummary, entitled, peerIdentity)`; write them; read peer's + `ingestMessage` each (existing gates).
  - BLOBS: read peer `blob_want`; give smallest-first within `BLOB_GIVE_BUDGET` (sync.py:794-815) via `blobSizes`+`getBlob`; write; read peer's blobs, verify `blobHash==h && size<=cap`, `putBlob`.
- Do NOT re-run AUTH here (T2 already authenticated). The initiator `run` stays byte-unchanged (regression).

- [ ] **Step 1: Failing tests** — scripted `Stream` driving the responder: seed a store where the peer is a kreds friend with a known device; assert the MESSAGES the responder writes == the entitled delta (and EXCLUDE an inner-ring / non-entitled message — over-serve negative at the phase level); assert an offered message is ingested; assert a wanted blob is served smallest-first and an offered blob is stored; own-device peer adopts `known`. Read KotlinSyncTest's existing initiator-side scripted tests and mirror.
- [ ] **Step 2: Run → fail.** [ ] **Step 3: Implement.** [ ] **Step 4: Run → pass; full module suite; confirm initiator `run` tests unchanged.** [ ] **Step 5: Commit** — `feat(gossip): sync responder content phases (entitled serve + ingest)`.

---

## Task 4: `GossipServer` accept loop + lifecycle

**Files:**
- Create: `GossipServer.kt`
- Modify: `TorNodeService.kt`, `Stream.kt` (only if a Socket→Stream constructor is needed — read Stream.kt first)
- Test: `GossipServerTest.kt`

**Interfaces:**
- Produces: `GossipServer(store, fixtureProvider, lock: ReentrantLock, port: Int)` with `start()` / `stop()`. `start()` opens a `ServerSocket(port, backlog, InetAddress.getLoopbackAddress())`, accepts in a loop on a background thread, wraps each accepted `java.net.Socket` in a `Stream`, and — under `lock` — runs `KotlinHandshake.respondHandshake` (isKnown = `store.knownIdentities().contains`) then, on success, `KotlinSync.serve`. Bounded worker pool (small fixed, e.g. 2-4); handler always closes its socket in `finally`; one bad connection never kills the accept loop (per-conn try/catch). `stop()` closes the ServerSocket + drains the pool.
- Lock coordination: acquire the SAME `ReentrantLock` `SyncRunner` uses (thread it in, or expose it) so an inbound serve and an outbound sync are mutually exclusive — document why (shared SQLite writer).

- [ ] **Step 1: Failing tests** — start the server on an ephemeral loopback port; a plain in-process client socket connects and drives a full handshake+serve against a seeded store → completes; a second connection while one is mid-serve is handled (serialized by the lock, not deadlocked); `stop()` closes cleanly; a garbage first frame closes that conn without killing the loop. (This is a real-socket JVM test — loopback sockets work in plain JVM; mirror any existing socket-level test or use `Socket("127.0.0.1", port)`.)
- [ ] **Step 2: Run → fail.** [ ] **Step 3: Implement** + wire `start()/stop()` into `TorNodeService` (after Tor+store up / on teardown; share the lock). [ ] **Step 4: Run → pass; full module suite.** [ ] **Step 5: Commit** — `feat(gossip): GossipServer accept loop + TorNodeService lifecycle`.

---

## Task 5: Loopback fidelity gate — a real hearth node DIALS the phone

**Files:**
- Modify: `android_tor_spike/tools/sync_loopback_node.py` (a `serve` scenario)
- Create: `SyncServeLoopbackTest.kt`

- [ ] **Step 1: Scenario** — this INVERTS the harness direction: the Kotlin `GossipServer` listens on a loopback port; a REAL hearth node DIALS it and runs its real initiator `_sync_session`. The scenario boots a node whose identity is (a) the phone's own sibling, (b) a friend, or (c) a stranger (sub-scenarios), seeds content, and dials the port the test passes it (print `{"event":"serve_ready"}` / drive `node.sync_with("127.0.0.1:<port>")`). Emit what the node pulled + pushed.
- [ ] **Step 2: Kotlin test** — start `GossipServer` on an ephemeral port over a seeded phone store; spawn the harness node pointed at that port; assert: (a) own-sibling → pulls the phone's own content + the phone ingests the node's; (b) friend → receives ONLY entitled content — assert a seeded inner-ring / non-entitled message is NOT among what the node received (the OVER-SERVE NEGATIVE at the real-wire level); (c) stranger → refused at AUTH, node's sync fails cleanly, nothing exchanged. Fail-closed; a divergence = REAL parity bug, report BLOCKED.
- [ ] **Step 3: Run; Step 4: full JVM suite + full hearth pytest (harness imports hearth); Step 5: Commit** — `test(gossip): loopback gate -- a real hearth node dials the phone and syncs`.

---

## Task 6: On-device DoD + report + PAUSE

**Files:**
- Create: `android_tor_spike/BRICK_GOSSIP_REPORT.md`

- [ ] **Step 1: Desk-gate sweep** — full JVM (XML count), tsc 0-new, vitest, `:app:assembleRelease`. Record.
- [ ] **Step 2: Install RELEASE on the G20** (force-stop; Play Protect).
- [ ] **Step 3: On-device DoD (August drives; HONESTLY THIN for arc 1)** — confirm the `GossipServer` starts inside `TorNodeService` on the G20 (logcat shows the accept loop bound to loopback) and accepts a loopback dial completing a round — e.g. an `adb`-forwarded local client, or an in-app debug probe, drives one handshake+serve. This proves the accept loop runs on real Android under the foreground service. REAL Tor reachability is arc 2 — the report states this plainly; do NOT overclaim.
- [ ] **Step 4: BRICK_GOSSIP_REPORT.md** — desk gates; the parity proof (T5: real node dials the phone, own/friend/stranger, over-serve negative); honest boundary (loopback-only until arc 2; answers-but-doesn't-reach-out; store-and-forward untouched; nudge folds on next); follow-up tickets (arc 2 onion, arc 3 peering, arc 4 availability, the nudge channel).
- [ ] **Step 5: Commit + PAUSE** — `docs(gossip): on-device proof record + DoD`. Merge is August's call.

---

## Self-Review

**1. Spec coverage:** give-side filter → T1; handshake responder → T2; sync responder → T3; accept loop + lifecycle → T4; real-node-dials-phone parity gate incl over-serve negative + stranger refusal → T5; DoD/report → T6. Loopback-only bind, lock coordination, honest-limits → covered across T4/T6. All spec sections mapped.
**2. Placeholder scan:** T1's grant-devs scan and T3's phase list are read-and-mirror steps anchored to exact hearth line refs (store.py:702-750, sync.py:643-825). T4's Socket→Stream is a read-Stream.kt-first conditional. No bare TODOs.
**3. Type consistency:** `messagesNotIn(summaries, entitled, peerIdentity): List<SignedMessage>` consistent T1/T3/T5. `respondHandshake(...): HandshakeResult` returning `peerCert` (T2) consumed by `serve(..., peerCert)` (T3). `GossipServer(store, fixtureProvider, lock, port)` T4 used in T5. Phase order + refusal shape identical T2/T3/T5.

**Implementer notes:** the give-side filter is the security landmine — an over-serve leaks private content; port `messages_not_in` byte-exactly and let the over-serve negatives (T1 unit + T5 real-wire) prove it. The initiator `run`/`runOverStream` paths must stay byte-unchanged (regression — the phone still dials out identically). This arc ANSWERS only; no new outbound peers. Loopback bind is deliberate, not a stopgap to "fix" by binding wider.
