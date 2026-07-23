# Friend Peering + Dynamic Cadence (Arc 3) — proof record + DoD

**The phone gets a peer table and dials friends directly.** Arc 2
(`brick-phone-onion`, merged) made the phone reachable (a stable onion
answered by the arc-1 gossip server) and safe (revocation/defriend parity)
— but the phone still dialed only its home node (`SyncRunner`'s single dial
of `fx.onion_addr`) and had no peer table: it knew friend *identities* (from
pairing's `friends` list + home-node gossip `known`) but no *addresses* to
dial them, and the pairing package's `peers` list plus every HAVE frame's
`peers`/`addr` fields were parsed then dropped. This arc flips those drops
into a peer table, loops the dialer over it with friend-acceptance (not just
own-sibling), and replaces the fixed 15-minute background timer with event
triggers + adaptive backoff.

Branch: `brick-friend-peering` (base `1b40d9c` off main). HEAD: `ff822cc`.
Spec: `docs/superpowers/specs/2026-07-23-friend-peering-dynamic-cadence-design.md`.
Plan: ledger section `PLAN: 2026-07-23 friend-peering-dynamic-cadence` in
`.superpowers/sdd/progress.md`. 8 tasks, 11 commits `1b40d9c..ff822cc`. This
is Task 8, the final task: desk-gate sweep, this report, and a PAUSE — the
on-device DoD, RELEASE install, and merge are all August's call.

## Desk gates (all GREEN — this session, against HEAD `ff822cc`)

Commands run from `android_tor_spike/app/android` (gradle),
`android_tor_spike/app` (tsc/vitest), and the repo root (pytest), against a
clean working tree (`git status` clean at session start; no code changes
made this session — desk-gate verification + report only).

| Gate | Command | Result |
|------|---------|--------|
| Full Kotlin JVM suite (`tor-manager`) | `./gradlew :tor-manager:testDebugUnitTest --rerun-tasks` | **BUILD SUCCESSFUL** in 35s, 62/62 tasks executed (forced fresh, no cached UP-TO-DATE results) |
| XML result count | summed `tests`/`skipped`/`failures`/`errors` across all `TEST-*.xml` under `.../tor-manager/android/build/test-results/testDebugUnitTest/` | **437 tests, 0 skipped, 0 failures, 0 errors**, 40 result files — exact match to the ledger's expected final count |
| Full hearth pytest | `.venv/Scripts/python.exe -m pytest -q` (repo root) | **1105 passed, 9 skipped**, 4 warnings, 97.64s — exact match to expected |
| `npx tsc --noEmit` | from `android_tor_spike/app` | **14 errors, all pre-existing** `@types/node` gaps in `src/__tests__/wire.test.ts`, `test/web-readonly-seam.test.ts`, `tools/handshake_cli.ts`, `tools/node_stream.ts`, `tools/roundtrip_cli.ts` — identical file/line set to arc-2's own baseline (`BRICK_ONION_REPORT.md`). **0 new.** (Note: an arc-3 ledger entry for Task 6 quotes "13 pre-existing" — this session's own count, and arc-2's report, both independently land on 14 against the same file set; treated as a bookkeeping variance, not a regression, since no new file/location appears.) |
| `npx vitest run` (full) | from `android_tor_spike/app` | **29/29** (2 test files), 259ms — verified live this session |
| `:app:assembleRelease` | `./gradlew :app:assembleRelease` | **BUILD SUCCESSFUL** in 21s, apk at `app/build/outputs/apk/release/app-release.apk` (63,831,789 bytes), 365 tasks (33 executed, 332 up-to-date) |

No new tsc errors, no vitest regressions, no JVM regressions, no pytest
regressions. JVM suite grew across the branch from arc-2's final 372 to
437 (+65 across Tasks 1-7), matching this session's independent fresh
re-run exactly.

## What shipped (per the ledger)

**Task 1 — peer table** (`135f967`, `SyncStore.kt`+`SqliteSyncStore.kt`).
`Peer(address, identityPub?)` with `addPeer`/`listPeers`/`removePeer`/
`addressFor`, byte-checked vs hearth `store.py:39-40`/`217-239`: address-
keyed `INSERT OR REPLACE` (one row per address, newest identity wins),
non-destructive SQLite (`peers` table `CREATE IF NOT EXISTS` in both
`onCreate`/`onOpen`, `DB_VERSION` frozen, `onUpgrade` excludes it — same
precedent as `meta`/`revoked_devices`/`keys`). 377/377.

**Task 2 — `mergePeerAddress`** (`250d037`, `SyncStore.kt`). Line-for-line
port of hearth `_merge_peer_address` (`sync.py:93-131`): onion-preferred
(a non-onion address is stored only if no onion is already known for that
identity — never let plain TCP shadow a Tor peer, tested both directions);
onion eviction is host-keyed (same-host different-port rows are stale
duplicates and get evicted; different onion hosts for one identity coexist,
covering multiple devices). 383/383.

**Task 3 — address learning, transitive** (`e7a43f9` + fix `78bb33d`,
`KotlinPairing.kt`+`KotlinSync.kt`). Two drops flipped to keeps: pairing
install now merges the package's `peers[]` per-entry gated on `is_known`
(runs after the friend-identity loop so no legitimate address is lost) and
seeds the home node as a peer row; the HAVE phase (both `run` and `serve`)
now merges the peer's own advertised `addr` and merges each relayed `peers[]`
entry whose identity is already known — this is the transitive relay
mechanism: a friend's address propagates across the friend graph via
whichever node already knows both identities, typically the home node. A
review-caught ordering bug was fixed in `78bb33d`: serve's merge originally
ran *before* the same-frame `known[]` widening, so introducing a new
identity and relaying its address in the same HAVE frame silently dropped
the address — moved to run after widening (matches hearth `sync.py:768-781`
and the `run` side), mutation-verified. The phone still sends `peers: []`
in its own HAVE (endpoint, not relay — asserted). 396/396.

**Task 4 — `SyncRunner` peer-loop + friend acceptance** (`51847d9` +
security fix `e0092e4` + addr-attribution fix `56a6a65`, `SyncRunner.kt`+
`KotlinSync.kt`). `SyncRunner` now loops `listPeers()` instead of a single
home-node dial: `acceptPeerIdentity` gates each dial on known-identity +
correct-address-slot, `shouldSkipOwnOnion` skips the phone's own onion,
`shouldThrottle`/`ONION_SYNC_INTERVAL_MS=45s` throttles onion dials, and a
refused/failed peer does not abort the round. Two fix waves:

- **HIGH friend-injection** (`e0092e4`): `run()`'s `known[]` widening was
  unconditional, so a known friend's HAVE frame (`known:[attacker],
  peers:[{attacker,addr}]`) would widen `known` and then pass the
  `is_known` gate on the *same frame's* relay — planting a dialable
  stranger. Fixed by gating widening on `peerIdentity == ownIdentity` (own
  sibling devices only, matching `serve()` and `sync.py:768-772`).
  Mutation-verified: the regression test asserts absence on both axes
  (`knownIdentities` not-contains and `addressFor` null), and fails under
  the old code.
- **MED-HIGH delivery gap**, same commit: `pendingOutbound` was read and
  cleared *per peer*, so a compose reached only the first peer dialed
  before being deleted. Fixed to capture the pending list once before the
  peer loop, fan it to every peer's first dial, and clear once after the
  loop, gated on any peer succeeding — a fully-failed round no longer
  silently drops the outbound write.
- **Addr-attribution fix** (`56a6a65`, reviewer-overturned finding): `run()`'s
  HAVE direct-address merge hardcoded `mergePeerAddress(ownIdentity, peerAddr)`.
  Since `peers` is address-keyed, this overwrote the friend's own peer row
  with the *phone's own* identity — breaking `addressFor(friend)` and
  causing every future dial of that row to fail the wrong-address-slot
  guard until a third node re-healed it. This directly undermines the
  home-node-outage resilience goal the arc exists to deliver. Fixed to
  attribute the address to `peerIdentity` (matching `serve()` and
  `sync.py:776`); mutation-verified.

418/418.

**Task 5 — defriend removes the peer row** (`a57fb6e`, `DefriendNotice.kt`).
`applyDefriendNotice` now also removes every peer row for the defriended
identity, after the existing 4-gate order and `purgeAuthoredBy`/
`removeIdentity` — mirrors hearth `remove_peer_identity` (`store.py:1060-1067`,
call site `node.py:1769`). Closes arc 2's explicit deferral (there was no
peer table to clean up before this arc). 421/421.

**Task 6 — adaptive-cadence rework** (`d11afc9` + critical fix `979355b`,
`AdaptiveBackoff.kt` [new]+`TorNodeService.kt`+`LocalApi.kt`+`WebShell.tsx`+
`index.ts`). Kills the fixed `SYNC_INTERVAL_MS = 900_000` (15-min) timer.
`AdaptiveBackoff(baseMs, maxMs)` is a pure JVM unit: a sweep that pulls
nothing new doubles the interval toward a ~1 hr cap; a sweep that pulls new
content, an event trigger, or active app use resets to a ~10 min base. A
self-rescheduling chain (`sweepAndReschedule`) replaces
`scheduleAtFixedRate`. Event triggers wired end-to-end: on-compose
(`LocalApi` compose/DM/react/comment/retract calls fire `beatNow()` on
success), on-resume (`WebShell`'s `AppState` listener fires `beatNow()` on
foreground). **Critical fix in `979355b`**: the "pulled new content" signal
was originally `store.stats()`'s cumulative row count (`sum > 0`), which is
true on essentially every sync because the phone's own identity is always
seeded — meaning the interval never actually backed off and the "adaptive"
feature behaved exactly like the old fixed timer. Fixed to a real delta:
`pulledNewContent(prevTotal, newTotal, ran, ok)` compares against the
previous sweep's total, gated on the sweep actually running and succeeding.
Also fixed in the same commit: the reschedule chain could die if the
interval computation threw (split into independently-guarded steps), and
`AdaptiveBackoff.current`'s `@Volatile` was insufficient for the
reset-vs-`nextInterval` read-modify-write race (moved to `AtomicLong`
`updateAndGet`). 435/435 JVM, tsc 0-new, vitest 29/29. The scheduler/
on-compose/on-resume *wiring* is on-device-DoD-only (no Robolectric in this
module — established project precedent); the backoff math and the
pulled-new delta logic are JVM-tested.

**Task 7 — real-wire loopback gate** (`ff822cc`, `sync_loopback_node.py`+
`SyncPeerLoopbackTest.kt` [new]+`SyncServeLoopbackTest.kt`). See "The
loopback proof" below. 437/437 JVM, pytest 1105/9skip (no regression).

## The loopback proof

`SyncPeerLoopbackTest.kt` introduces a new harness scenario,
`_run_peer_friend`: a served hearth node signs a cert under its **own**
identity while its onion address maps to a **separate friend identity**
(the first such split in the codebase — every prior loopback gate used
either the home node's own identity or a plain stranger). This exercises
the friend-acceptance branch of `KotlinSync.run` (`peerIdentity != own`)
specifically, not the own-sibling widening branch, and does real Ed25519/
X25519 key convergence throughout — no stubs.

**Test 1 — phone dials a friend, attributes the address correctly.** The
phone (with the friend's address pre-seeded in its peer table) dials out,
completes mutual AUTH against the friend identity, pulls a genuinely-wrapped
entitled post (checked by message-ID membership in the store plus a real
decrypt), pushes its own enckey (cross-checked against the friend node's
ingest), and — the key regression check — the friend's advertised address
in HAVE gets attributed to the *friend's* identity, not the phone's own
(`addressFor(friendIdentityPub) == friendAddr` and `addressFor(own) ==
null`), directly proving Task 4's addr-attribution fix at the wire: the old
bug would have flipped both assertions.

**The over-pull negative, and why it's falsifiable.** The first design for
"content the phone must NOT pull" used a sibling-device-wrapped post — the
reviewer traced this against hearth's own `messages_not_in` routing
(`store.py:702-748`) and found it would be a **false pass**: POST/WRAP/
RESPONSE routing gates on `peer_devices ∩ wr`, where `peer_devices` is
*every* device of an identity, so that record would route to the phone
regardless of whether the friend-acceptance entitlement logic worked at
all. Switched to an inner-ring `set_ring` record instead, which hearth
gates unconditionally at the identity level (`store.py:723-724`:
`if kind==KIND_RING and peer_identity!=ipub: continue` — no fanout
loophole) and which clears the coarser `entitled = known ∩ peerKnown`
filter first, so it is stopped *specifically* by the ring gate, not by an
unrelated exclusion. Mutation-verified: disabling `store.py:723` turns the
test red at the assertion that checks the ring record is absent; reverting
restores green.

**Test 2 — a friend dials in, and address-advertisement actually gets
exercised.** `friendDialingInAdvertisesAddress` closes a real gap:
`SyncServeLoopbackTest` had never set `gossip_addr` on a dialing node before
this task, meaning the serve-side address-merge branch
(`KotlinSync.kt:735-739`) was wire-dead in every prior test even though it
was JVM-unit-tested in isolation. The new `gossipAddr` test-harness param is
additive and backward-compatible on both sides.

437/437 JVM, pytest 1105/9skip. One deferred item, flagged not silent: no
real-wire negative for a stranger squatting an address slot (covered today
only at the unit level, in `SyncRunnerTest`) — a local, pre-existing Kotlin
scope decision, not new to this task.

## On-device DoD (August drives — this is a PAUSE point, not verified above)

**Prerequisite — install.** Force-stop the app, install the RELEASE apk
(`app/build/outputs/apk/release/app-release.apk`, built this session against
HEAD `ff822cc`) — not the debug build. Expect the Play Protect "send app for
a safety check?" dialog on first install after a rebuild; dismiss it same as
every prior brick (screencap-verify before tapping, since coordinates can
drift between OS builds).

**The headline capability — desktop-offline resilience:**

- [ ] With the **desktop (home node) powered off / Tor disconnected**, the
      phone still exchanges content with a friend: it dials the friend
      *directly*, using an address it learned earlier (via pairing install
      or a prior HAVE relay through the home node) and that persisted in
      its peer table — not a fresh lookup through the now-offline desktop.
- [ ] A friend's node dialing the phone's onion (arc 2's reachability)
      delivers content to the phone **without the phone polling** — i.e.
      inbound-only traffic still lands.

**The cadence rework, observed:**

- [ ] An **idle** phone's background sweeps visibly space out over time
      (roughly 10 min → doubling toward a ~1 hr cap) when nothing new is
      arriving.
- [ ] **Composing** something (post/DM/response/react/retract) triggers an
      **immediate** sync push, not a wait for the next scheduled sweep.
- [ ] **Reopening the app** (from background/closed) triggers a sync on
      resume.

**Regressions — confirm nothing broke:**

- [ ] Home-node sync still works with the desktop back online (the original
      single-peer path, now just one row in the peer table).
- [ ] First-Load pairing still completes end-to-end (fresh QR pair).
- [ ] Revoke-wipe still fires (revoke the phone's device from the desktop;
      confirm the phone wipes to First-Load).

Do not treat any of the above as passed until actually run on-device — the
desk gates above prove the code is correct and green in isolation; they do
not prove the scheduler wiring, the real Tor dial to a friend, or the
inbound-without-polling path, none of which have Robolectric coverage in
this module (established project precedent, not new to this task).

## Honest limits

Reproduced verbatim from the design spec's "Honest limits" section
(`docs/superpowers/specs/2026-07-23-friend-peering-dynamic-cadence-design.md`):

> - The phone is a peering ENDPOINT: it dials friends whose addresses it
>   learned (home-node relay while online, or pairing) and answers friends who
>   dial in. It does NOT yet relay OTHER nodes' addresses onward (multi-hop
>   relay FROM the phone) — deferred.
> - Bootstrapping addresses in a PURE phone-only mesh with NO ever-online node
>   is the store-and-forward problem — ARC 4. Arc 3 gives the dialing
>   capability; a phone learns a friend's address via some node that knows it
>   (typically the home node), so a brand-new friend added while every relay
>   is offline won't have an address until a relay comes online.
> - Friend-add (befriending someone NEW) still happens on a desktop; the phone
>   inherits the identity + picks up the address via relay. No phone-side
>   friend-add ceremony.
> - The adaptive backoff is in-memory (resets on service restart) and is a
>   stepping-stone; the full nudge (held connection + push) replaces the
>   periodic sweep later and is the battery-optimal endpoint.
> - Battery/charging-aware refinements (sync freely when charging, harder
>   backoff on low battery) are a deliberate follow-up, not this arc.

## Follow-up tickets (all carried or new, non-blocking)

**From this arc:**
- **Multi-hop relay FROM the phone** (spec "Out of scope"). The phone sends
  `peers: []` in its own HAVE by design — it does not yet relay its friend
  graph's addresses onward to a third party. Deferred, per the endpoint-not-
  relay decision.
- **Arc 4 — store-and-forward / pure-phone-mesh bootstrapping** (spec "Out
  of scope"). A brand-new friend added while every relay (typically the
  home node) is offline has no address to learn until a relay comes back
  online. Untouched by this arc.
- **The nudge channel** (spec "Out of scope" + "Honest limits"). Folds onto
  this next: once reachability + peering exist, a held connection + push
  frames replaces the periodic adaptive sweep — the battery-optimal
  endpoint the current backoff is a deliberate stepping-stone toward.
- **Battery/charging-aware cadence** (spec "Out of scope" + "Honest
  limits"). Sync freely when charging, harder backoff on low battery — a
  deliberate follow-up, not built this arc.
- **No real-wire stranger-squats-address-slot negative** (Task 7, new,
  flagged not silent). Covered today only at the JVM-unit level
  (`SyncRunnerTest`); a local, pre-existing Kotlin-side scope decision, not
  a regression introduced by this task.
- **Phone-side friend-add ceremony** (spec "Out of scope"). Not built;
  friend-add remains desktop-only per the "Honest limits" note above.

**Carried from arc 2 (`brick-phone-onion`, `BRICK_ONION_REPORT.md` /
`.superpowers/sdd/progress.md`):**
- **No ongoing per-message revoked-device seq gate.** Retro-drop (at
  ingest time) and the AUTH-time refusal cover the direct cases, but there
  is no ongoing per-message check that would catch a revoked device's
  message arriving via a third-party relay after the revocation
  (`Verifier.verify_message`-equivalent gap). Cross-arc, unresolved.
- **Blob-GC gap after purge.** `purgeAuthoredBy` tombstones messages but
  the phone has no refcounting concept for blobs, so a defriended author's
  blobs are not reclaimed. Pre-existing, unresolved.
- **Literal `gossip_addr`/`onion_key` value is not independently readable
  by any current tooling.** Non-debuggable release build blocks `run-as`;
  `publishOnion()` is silent-on-success by design; no RN-bridge method or
  in-app UI surface exposes it. A small follow-up (a debug-only getter, or
  a one-line success log gated behind a build flag) would close this gap
  for future on-device verification sessions without requiring a live
  desktop dial every time.

**Carried from the live-sync arc (`brick-post-regrants`, Task 2 review):**
- **Desktop never consumes B.2c recipient DM re-grants.** `_content_key`'s
  `KIND_DM` branch has no grant step — only the phone's `DecryptPass` can
  read old friend DMs today via the recipient-signed re-grant path; a
  second desktop device cannot. This is the exact bug class the
  `recipient-post-regrants` slice fixed for posts, left open for DMs.
  Extending the `KIND_DM` branch as a mirror image of the post fix is its
  own follow-up slice/ticket, flagged as the TOP follow-up in that arc's
  whole-branch review.

## After the run

On a pass, whether this merges to public main is August's call, same as
every prior brick. PAUSE here for human review per the task brief. This
closes arc 3 (friend peering + dynamic cadence) at the desk level — the
live behavioral DoD items (desktop-offline friend exchange, inbound-without-
polling delivery, the observed cadence spacing/reset behavior, and the
home-node/First-Load/revoke-wipe regressions) remain August's to run
whenever he chooses. The honest state of each is "the underlying mechanism
is proven at the real wire or in a JVM unit; the live field action on the
phone's real identity and real Tor network has not yet been run," not
"passed."
