# Phone Reachability (Arc 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The phone publishes a stable Tor onion so the desktop can dial it and sync — AND the phone safely processes revocations/defriends (the blocking precondition), including a full self-wipe when its own device is revoked.

**Architecture:** Two halves. Part A (Tasks 2-6) ports hearth's revocation/defriend model into the phone's Kotlin as a faithful subset — the security gate. Part B (Tasks 1,7) adds `ControlPort.addOnion` (port `publish_onion`, tor.py:325-345) + persistence + wiring so the phone is reachable. Task 1 (onion spike) runs FIRST to de-risk `ADD_ONION` on tor-android; the gate (A) lands before the onion is wired into always-on production (Task 7). Spec: `docs/superpowers/specs/2026-07-23-phone-onion-reachability-design.md`.

**Tech Stack:** Kotlin (tor-manager module, JVM tests), tor-android control port, the arc-1 GossipServer/KotlinSync/KotlinHandshake, `sync_loopback_node.py`, RN FirstLoad gate.

## Global Constraints

- **Branch:** `brick-phone-onion`. Commit prefix `feat/fix/docs/test(onion)`, lowercase. **NO AI / Co-Authored-By / "Generated with" trailers.**
- **Parity (verbatim from hearth):** revocation ingest mirrors `store.ingest_revocation` (store.py:410-430: verify cert against views, mark device revoked, retro-drop that device's messages `seq > last_valid_seq`); defriend apply mirrors `node.apply_defriend_notice` (node.py:1746-1780 SUBSET: `target==own` && `author!=own` && `verify()` && `isKnown(author)` → `purgeAuthoredBy` + `removeIdentity`); post-AUTH revoked-device refusal mirrors sync.py:637-641; mid-session re-check mirrors sync.py:741-758; `ADD_ONION NEW:ED25519-V3 Flags=Detach Port=9997,127.0.0.1:<target>` + `_parse_control_reply` (tor.py:122-131,325-345); `ONION_VIRTUAL_PORT=9997` FIXED.
- **Self-revoke = full wipe** (enterRevokedState, node.py:3144 analog): wipe `pairing.json` (incl device_priv + identity_priv), DB, caches; stop the service; event → RN First-Load. Irreversible by design (identity survives on the desktop). Idempotent + safe mid-sync.
- **The gate lands before reachability:** Part A (revocation parity) must be complete before Task 7 wires the onion into TorNodeService always-on. The whole slice merges together (August gates merge), so no partial reachability ships.
- **Reuse (do NOT re-port):** arc-1 `GossipServer`/`KotlinSync.run`/`serve`/`respondHandshake`; `KotlinWire.verify*`/`canonical`; `ControlPort`'s cookie-auth + reply-read pattern; `PairingStore`; `SyncStore` accessors. run/runOverStream stay byte-parity except the intentional revocation/defriend ingest + gossip_addr additions (which are additive to the phases, not a rewrite).

**Test commands** (git-bash; `JAVA_HOME=/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot`; from `android_tor_spike/app/android`): `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.<Class>"`; XML glob `../modules/tor-manager/android/build/test-results/testDebugUnitTest/*.xml` (baseline: run first). Release + G20 per gotchas. Loopback tests spawn `.venv` python.

---

## File Structure

- `ControlPort.kt` (modify) — `addOnion(virtualPort, targetPort, keyBlob?)` + a generic reply-parse.
- `SyncStore.kt`/`SqliteSyncStore.kt`/`InMemorySyncStore` (modify) — `removeIdentity`, `purgeAuthoredBy`, revoked-device set (`markRevoked`/`isRevokedDevice`), `getMeta`/`setMeta`.
- `KotlinWire.kt` or new `RevocationCert.kt`/`DefriendNotice.kt` (create) — the two cert/notice types + verify (port identity.py).
- `KotlinSync.kt` (modify) — ingest REVOCATIONS + apply DEFRIENDS on `run` AND `serve`; send real `gossip_addr` in HAVE; surface own-device-revoked.
- `KotlinHandshake.kt` (modify) — post-AUTH revoked-device refusal.
- `PairingStore.kt` (modify) — `wipe(ctx)`.
- `TorNodeService.kt` + `TorManagerModule.kt` (modify) — `enterRevokedState` wipe + `revoked` event; onion publish wiring after GossipServer.start.
- Tests: per-file JVM tests; extend `SyncServeLoopbackTest.kt`; `android_tor_spike/BRICK_ONION_REPORT.md`.

---

## Task 1: `ControlPort.addOnion` + onion spike (DE-RISK FIRST)

**Files:**
- Modify: `ControlPort.kt`
- Test: `ControlPortTest.kt` (create or extend)

**Interfaces:**
- Produces: `ControlPort.addOnion(virtualPort: Int, targetPort: Int, keyBlob: String? = null): Pair<String,String?>` returning `(serviceId, keyBlob)`. Authenticate (cookie, as `bootstrapProgress`/`signalShutdown` do), send `ADD_ONION ${keyBlob ?: "NEW:ED25519-V3"} Flags=Detach Port=$virtualPort,127.0.0.1:$targetPort`, read the multi-line reply, parse via a ported `parseControlReply(lines): Map<String,String>` (mirror tor.py:122-131 — strip the `250-`/`250 ` prefix, split `KEY=VALUE`). serviceId = `fields["ServiceID"]` (throw if absent); returned keyBlob = `fields["PrivateKey"] ?: keyBlob` (NEW returns `PrivateKey=ED25519-V3:<blob>`; republish echoes nothing → keep what we sent). `Flags=Detach` is REQUIRED (ControlPort is per-call; without it the onion dies on close).

- [ ] **Step 1: Failing tests** — against a scripted control-port socket (a local `ServerSocket` faking Tor's `250` replies; mirror how ControlPort's existing methods are tested or build a fake): a NEW reply (`250-ServiceID=abc\r\n250-PrivateKey=ED25519-V3:KEY\r\n250 OK\r\n`) → returns `("abc","ED25519-V3:KEY")`; a republish reply (no PrivateKey) with a passed keyBlob → returns `(serviceId, thePassedBlob)`; a reply with no ServiceID → throws; the command bytes written contain `Flags=Detach` and the correct `Port=9997,127.0.0.1:<target>`.
- [ ] **Step 2: Run → fail.** [ ] **Step 3: Implement.** [ ] **Step 4: Run → pass; full module suite.**
- [ ] **Step 5: ON-DEVICE SPIKE (de-risk — Claude adb-drives the G20 or August):** build+install; from an in-app probe or adb, call `addOnion(9997, <a test local port>)`; confirm Tor returns a serviceId (logcat/probe). Then the DESKTOP dials `<serviceId>.onion:9997` over Tor into a test listener (or, if wired enough, the GossipServer) and confirms a TCP connect. IF `ADD_ONION` fails on tor-android → report BLOCKED; the arc's reachability goal is blocked (revocation parity Tasks 2-6 still proceed, valuable for arc 3). Record the spike result.
- [ ] **Step 6: Commit** — `feat(onion): ControlPort.addOnion (ADD_ONION Flags=Detach) + reply parse`.

---

## Task 2: Store primitives — removeIdentity / purgeAuthoredBy / revoked-device set / meta

**Files:**
- Modify: `SyncStore.kt` (interface + `InMemorySyncStore`), `SqliteSyncStore.kt`
- Test: `SyncStoreTest.kt`

**Interfaces:**
- Produces: `removeIdentity(id)` (mirror store.py:162 — drop from known identities); `purgeAuthoredBy(id): Int` (mirror store.py:1036 — tombstone/delete all messages `identity_pub == id`, return count); `markRevoked(devicePub, lastValidSeq)` + `isRevokedDevice(devicePub): Boolean` + retro-drop of that device's messages `seq > lastValidSeq` (a `revoked_devices` table or a column; Sqlite; InMemory a map); `getMeta(k): String?` / `setMeta(k, v)` (a `meta` table, mirror hearth store meta).

- [ ] **Step 1: Failing tests** — removeIdentity drops from knownIdentities (and messagesNotIn no longer serves that author — cross with arc-1 filter); purgeAuthoredBy removes that author's messages (count); markRevoked → isRevokedDevice true + that device's `seq>lastValid` messages gone, `seq<=lastValid` kept; getMeta/setMeta round-trip + null-on-absent.
- [ ] **Step 2: Run → fail.** [ ] **Step 3: Implement both impls.** [ ] **Step 4: Run → pass; full module suite.** [ ] **Step 5: Commit** — `feat(onion): store primitives -- removeIdentity, purgeAuthoredBy, revoked-device set, meta`.

---

## Task 3: `RevocationCert` + ingest on both sync paths

**Files:**
- Create: `RevocationCert.kt` (+ maybe `DefriendNotice.kt` here or Task 4); Modify: `KotlinSync.kt`
- Test: `RevocationCertTest.kt`, `KotlinSyncTest.kt`

**Interfaces:**
- Produces: `RevocationCert` (fields per hearth identity.py: identity_pub, device_pub, last_valid_seq, created_at, signature) + `verify(): Boolean` (self-authenticating — signed by the identity key; port hearth's verify). `store.ingestRevocation(rev): Boolean` (mirror store.ingest_revocation store.py:410-430: `isKnown(identity)` gate, `verify()`, `markRevoked(device, lastValidSeq)` [Task 2], retro-drop). `KotlinSync` REVOCATIONS phase on BOTH `run` and `serve`: read peer revs → for each, ingestRevocation; write own (empty — phone authors none). If an ingested rev names OUR OWN device_pub → return/signal `SelfRevoked` (the wipe wires in Task 6; here just detect + surface, matching the existing `SelfRevoked` enum).

- [ ] **Step 1: Failing tests** — a valid RevocationCert for a known friend device → ingested, isRevokedDevice true, that device's future-seq messages dropped; an unknown-identity rev → rejected; a rev naming OUR OWN device → surfaces SelfRevoked; a tampered-sig rev → rejected. Build real Ed25519-signed certs (KotlinPairingTest fixtures idiom). Wire into serve/run and assert via a scripted Stream that a peer's REVOCATIONS frame is ingested.
- [ ] **Step 2: Run → fail.** [ ] **Step 3: Implement.** [ ] **Step 4: Run → pass; full suite; run/serve REVOCATIONS phase still interlocks (regression).** [ ] **Step 5: Commit** — `feat(onion): RevocationCert + ingest on both sync paths`.

---

## Task 4: `DefriendNotice` + apply on both sync paths

**Files:**
- Create/Modify: `DefriendNotice.kt`, `KotlinSync.kt`
- Test: `DefriendNoticeTest.kt`, `KotlinSyncTest.kt`

**Interfaces:**
- Produces: `DefriendNotice` (target_identity, author_identity, created_at, signature) + `verify()`. `store`/node-level `applyDefriendNotice(notice, ownIdentity): Boolean` (mirror node.py:1746-1780 SUBSET): `target==own` && `author!=own` (self-author guard) && `verify()` && `isKnown(author)` → `purgeAuthoredBy(author)` + `removeIdentity(author)` → true; else false (idempotent — re-delivered notice hits `!isKnown` → false). `KotlinSync` DEFRIENDS phase on BOTH run+serve: read peer notices → applyDefriendNotice each; ack per hearth's apply-then-ack shape (mirror what run does today for the ack, minimal-but-correct).

- [ ] **Step 1: Failing tests** — a valid defriend targeting us from a known author → author removed from knownIdentities (isKnown false), their messages purged; re-delivered → false (idempotent); a notice NOT targeting us → false, no removal; a self-authored notice → false (guard); bad sig → false. Wire into serve/run; assert a peer's DEFRIENDS frame removes the identity.
- [ ] **Step 2: Run → fail.** [ ] **Step 3: Implement.** [ ] **Step 4: Run → pass; full suite; DEFRIENDS phase interlock (regression).** [ ] **Step 5: Commit** — `feat(onion): DefriendNotice + apply (removeIdentity) on both sync paths`.

---

## Task 5: Post-AUTH revoked-device refusal + mid-session re-check

**Files:**
- Modify: `KotlinHandshake.kt` (respondHandshake), `KotlinSync.kt` (serve)
- Test: `KotlinHandshakeTest.kt`, `KotlinSyncTest.kt`

**Interfaces:** `respondHandshake` — after AUTH succeeds, for a non-self peer, if `store.isRevokedDevice(peerCert.device_pub)` → write `{"t":"refused"}` (same wire slot as the stranger refusal) + Failed (mirror sync.py:637-641). Thread the store/an `isRevoked` lambda into respondHandshake (like `isKnown`). `serve` mid-session re-check (mirror sync.py:741-758): after DEFRIENDS, if `!isKnown(peerIdentity)` (a defriend was just applied this session) or the peer device became revoked → end the session before HAVE/MESSAGES (serve nothing). This closes the arc-1 blocking finding.

- [ ] **Step 1: Failing tests** — a known peer whose device is in the revoked set → respondHandshake refuses post-AUTH (same position as stranger, tested position-sensitively); serve() after a mid-session defriend of the peer → no MESSAGES served (the peer is no longer known). Reuse the scripted-Stream + real-cert idioms.
- [ ] **Step 2: Run → fail.** [ ] **Step 3: Implement.** [ ] **Step 4: Run → pass; full suite.** [ ] **Step 5: Commit** — `feat(onion): post-AUTH revoked-device refusal + mid-session defriend re-check`.

---

## Task 6: Self-revoke → full wipe to First-Load

**Files:**
- Modify: `PairingStore.kt` (`wipe`), `TorNodeService.kt` (`enterRevokedState`), `TorManagerModule.kt` (`revoked` event), `KotlinSync.kt` (trigger on SelfRevoked)
- Test: `PairingStoreTest.kt`, `TorNodeServiceTest.kt` (if a JVM seam exists; else the wipe logic tested at the PairingStore/store level + on-device)

**Interfaces:** `PairingStore.wipe(ctx)` — delete `pairing.json` (incl device_priv + identity_priv) atomically; `enterRevokedState` (TorNodeService, node.py:3144 analog): `PairingStore.wipe` + delete/clear the SQLite store file + blob caches, stop the foreground service, `sendEvent("revoked", ...)`. RN: the FirstLoad gate listens for `revoked` → re-checks `hasIdentity()` (now false via wiped pairing.json) → shows First-Load (mirror how pairProgress is consumed). Trigger: `KotlinSync` (run or serve) detecting an ingested revocation of OUR OWN device (Task 3's SelfRevoked) → `TorNodeService.enterRevokedState`. Idempotent (a second SelfRevoked after wipe is a no-op — no fixture left).

- [ ] **Step 1: Failing tests** — `PairingStore.wipe` removes pairing.json and hasIdentity→false (JVM, dir-based); an ingested own-device revocation drives the wipe path (test at whatever seam is JVM-reachable — the SelfRevoked→wipe wiring; the service-stop + event are on-device). Assert idempotency.
- [ ] **Step 2: Run → fail.** [ ] **Step 3: Implement.** [ ] **Step 4: Run → pass; full suite.** [ ] **Step 5: Commit** — `feat(onion): self-revoke wipes pairing.json + store to First-Load`.

---

## Task 7: Onion key persistence + TorNodeService wiring + advertise gossip_addr

**Files:**
- Modify: `TorNodeService.kt`, `KotlinSync.kt` (HAVE addr)
- Test: `KotlinSyncTest.kt` (gossip_addr in HAVE), on-device for the publish itself

**Interfaces:** In `TorNodeService`, after Tor bootstrap AND `GossipServer.start()` (boundPort known): `savedBlob = store.getMeta("onion_key")`; `(serviceId, blob) = ControlPort.addOnion(9997, boundPort, savedBlob)`; `store.setMeta("onion_key", blob)`; `store.setMeta("gossip_addr", "$serviceId.onion:9997")`. Re-issue every restart (fresh boundPort, same saved key → same `.onion`). `KotlinSync.run`+`serve` HAVE: send `store.getMeta("gossip_addr") ?: ""` as `addr` (arc 1 sent `""`), so the desktop's `_merge_peer_address` learns the phone's onion and can dial back.

- [ ] **Step 1: Failing test** — serve/run HAVE now carries the stored gossip_addr (seed meta, assert the HAVE frame's `addr` field == the stored `.onion`); the null/absent case still sends `""` (arc-1 parity). The addOnion wiring itself is on-device (Task 9).
- [ ] **Step 2: Run → fail.** [ ] **Step 3: Implement.** [ ] **Step 4: Run → pass; full suite.** [ ] **Step 5: Commit** — `feat(onion): persist onion key + publish on start + advertise gossip_addr in HAVE`.

---

## Task 8: Loopback gate — revoke + defriend at the real wire

**Files:**
- Modify: `sync_loopback_node.py`, `SyncServeLoopbackTest.kt`
- (extend the arc-1 serve scenario)

- [ ] **Step 1: Scenario** — (a) a real hearth node that has REVOKED the phone's peer device (the node ingests/holds a revocation for a device the phone will present, OR the node sends the phone a revocation for a FRIEND then dials — pick the shape that proves the phone's gate) dials the phone → for the revoked-friend case: after the phone ingests the node's REVOCATIONS naming a friend device, the phone must not serve that device's content; for the phone-device-revoked-by-node case: the node refuses/the phone wipes. (b) a node carrying a DEFRIEND notice targeting the phone's identity → the phone applies it → on the next round serves that identity NOTHING. Emit what was served/refused.
- [ ] **Step 2: Kotlin test** — start GossipServer over a seeded phone store; drive the node; assert the revoked device's content is not served + the defriended identity gets nothing (the gate at the real wire, the over-serve-STOPS negative). Fail-closed; BLOCKED on divergence.
- [ ] **Step 3: Run; Step 4: full JVM + pytest; Step 5: Commit** — `test(onion): loopback gate -- revoked device refused + defriended identity not served`.

---

## Task 9: On-device DoD + report + PAUSE

**Files:** Create `android_tor_spike/BRICK_ONION_REPORT.md`

- [ ] **Step 1: Desk-gate sweep** — full JVM, pytest, tsc 0-new, vitest, assembleRelease. Record.
- [ ] **Step 2: Install RELEASE on the G20** (force-stop; Play Protect).
- [ ] **Step 3: On-device DoD (August drives; Claude can adb-assist the reachability probe)** — (a) phone publishes a STABLE `.onion` (same across app restarts — check gossip_addr meta persists); (b) desktop learns it (the phone's HAVE) and DIALS the phone over Tor → sync completes, content flows desktop→phone WITHOUT the phone polling (the first real reachability); (c) revoke the phone's device from the desktop → the phone WIPES to First-Load on its next sync; (d) a defriend → the phone stops serving that identity; (e) regression: loopback + outbound sync + First-Load pairing still work.
- [ ] **Step 4: BRICK_ONION_REPORT.md** — desk gates; the spike result (ADD_ONION on tor-android); the revocation gate proof (loopback + on-device); honest boundary (spec Honest limits VERBATIM: desktop-reaches-phone proven, FRIENDS-reach-phone = arc 3, store-and-forward = arc 4, nudge folds next, self-wipe irreversible, failed-unlock panic-wipe separate); follow-up tickets (arc 3 friend peering, arc 4 availability, the nudge, + any carried arc-1 tickets now resolved [revocation gate] vs still open).
- [ ] **Step 5: Commit + PAUSE** — `docs(onion): reachability + revocation-parity proof record + DoD`. Merge is August's call.

---

## Self-Review

**1. Spec coverage:** addOnion+spike → T1; store primitives → T2; revocation ingest → T3; defriend apply → T4; revoked refusal + mid-session recheck → T5; self-revoke wipe → T6; onion persist+wire+advertise → T7; real-wire gate → T8; DoD → T9. Part A (gate) = T2-T6 lands before T7 wires the onion into production. Self-revoke-full-wipe (slice C fold) → T6. All spec sections mapped.
**2. Placeholder scan:** T8's scenario shape is a read-and-pick anchored to the exact hearth refs (sync.py:637-641/741-758); T6's service-level wipe is JVM-tested where a seam exists + on-device otherwise (honest, matches the module's Robolectric constraint). No bare TODOs.
**3. Type consistency:** `addOnion(virtualPort,targetPort,keyBlob?):Pair<String,String?>` T1→T7. `removeIdentity`/`purgeAuthoredBy`/`markRevoked`/`isRevokedDevice`/`getMeta`/`setMeta` (T2) consumed T3/T4/T5/T7. `ingestRevocation`/`applyDefriendNotice` (T3/T4) consumed by serve/run + T5's re-check. `enterRevokedState`/`PairingStore.wipe` (T6) triggered by T3's SelfRevoked. `gossip_addr` meta (T2) written T7, advertised in HAVE T7, learned by the desktop.

**Implementer notes:** the security gate (Part A) is the point — an over-serve to a defriended/revoked peer once reachable is a real breach; port hearth EXACTLY and let the loopback gate (T8) prove it at the real wire. Self-revoke wipe is DESTRUCTIVE and irreversible — test idempotency + that it fully clears identity_priv. The onion spike (T1) gates the reachability half: BLOCKED-if-impossible is an acceptable honest outcome, and Part A still ships value for arc 3.
