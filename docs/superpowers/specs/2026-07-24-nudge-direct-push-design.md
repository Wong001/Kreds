# Nudge Channel — Direct-Push on Write (Model B) — Design

> **PARKED 2026-07-24 (saved for a rainy day).** After the measure-first
> spike, this was judged **largely redundant**: the phone already pushes its
> own composes immediately (on-compose), hearth nodes already deliver to a
> reachable phone within ~45s (gossip loop), and the phone was measured
> reachable even through Doze. So push-on-write's only real add is
> ~45s→immediate for hearth-authored content, which shrinks to nothing as the
> network goes phone-majority. Revisit ONLY if a phone-majority network ever
> needs sub-45s delivery of hearth-authored content. The one useful piece —
> **dead-onion liveness** (Part C) — was salvaged as a small standalone fix.
> The real pains it did NOT address (onion publish-warmup, desktop↔laptop
> direct-sync, per-device grant coverage) are handled by other work.

Date: 2026-07-24. The next arc after friend-peering. Inbound content
latency today is *poll-bound*: the phone only learns of new content on its
next sweep that reaches the peer (foreground ~30-45s, backgrounded up to the
adaptive-backoff cap). This arc makes delivery *sender-driven*: when a node
commits content entitled to a reachable peer, it dials that peer and delivers
it immediately, instead of waiting to be polled. Best-effort, with the
adaptive poll retained as a self-adjusting safety net.

## Context

Decided with August, and gated on a measurement spike (2026-07-24) that
de-risked the arc:

- **Reachability is not the bottleneck.** Probing the phone's live onion
  (`i7el4xnl…`) over Tor: after a one-time warmup it is reliably reachable
  sub-second, and it **survived a ~15-min screen-off Doze window** (5/6
  probes reachable, first-after-idle immediately sub-second, one ordinary
  Tor timeout). So a friend *can* reach a pocketed phone quickly — push will
  actually land. The 5-6 min latency observed in the on-device friend-peering
  test was almost certainly the **one-time onion-publish warmup** right after
  a reinstall (descriptor propagation + first-circuit build), not a
  persistent cold/Doze problem.
- **A lot of "push" already exists.** The phone already pushes on-compose
  (friend-peering arc: a local compose triggers an immediate sync of all
  peers, fanning entitled pending to each). hearth nodes already dial every
  reachable peer every ~45s via the gossip loop. So this arc's *new* value is
  narrower than "add push": (1) push-on-write for **hearth** nodes (45s →
  immediate), (2) letting the receiver's poll back off harder because pushes
  cover the common case (battery), and (3) **dead-onion liveness** so a
  pusher hits the reachable onion fast instead of wasting ~40s on a dead one.
- **This is the first production hearth (Python) change in a while** — recent
  slices were all Android-side; the push-on-write sender lives in hearth.

Builds directly on friend-peering (peer table, `mergePeerAddress`, the
reachable onion from arc-2, the serve/ingest path) — that branch must land
first.

## Part A — Sender push-on-write (the main change; hearth node)

1. **Trigger.** When a node commits a local write — the content-producing
   entry points in `node.py` (post/journal, DM, response/react/comment/
   retract, profile, profile_layout, wrap_grant) — it schedules a push to
   each peer that is (a) *entitled* to that content and (b) has a known
   *reachable* onion address. Entitlement reuses the existing give-side gate
   (`store.messages_not_in` semantics, `store.py:702-748`): a peer is a push
   target for a write only if that write would pass the gate for that peer's
   identity. No new entitlement logic — the same predicate that governs a
   normal serve governs who gets pushed to.
2. **Direct-push, not a separate nudge frame.** A push *is* a normal sync
   session initiated by the sender (`node._dial` → `_sync_session` →
   `_session`, `sync.py`). The sender already paid for the Tor circuit by
   dialing, so it runs the ordinary bidirectional exchange and delivers its
   new content on that circuit. The receiver AUTHs the sender and applies the
   same ingest/entitlement gates as any inbound sync (friend-peering serve
   path) — **no new protocol, no new trust surface.**
3. **Debounce + throttle (anti-storm).** Coalesce a burst of writes into one
   push per peer within a short window (`PUSH_DEBOUNCE_MS ≈ 1500`, tuneable) —
   low enough to keep latency near-instant, high enough to batch a
   multi-write action (e.g. a post + its layout + its grant land as one
   push). Enforce a per-peer minimum gap by reusing the existing onion
   throttle (`ONION_SYNC_INTERVAL ≈ 45s`, `messages.py:67`): a peer that was
   just synced (by push *or* by the periodic gossip loop) is not re-dialed
   inside the window — the write is already covered, or waits for the next
   eligible moment. Push and the gossip loop share the one throttle map so
   they never double-dial.
4. **Best-effort.** A push to an unreachable/failing peer is not retried
   inline; it fails silently and the peer's own poll (or the next gossip
   round) is the safety net. A failed dial feeds Part C's liveness tracking.
5. **Own writes only.** A node pushes content *it* commits, not content it
   merely relayed from another node (no push-on-relay / multi-hop real-time —
   consistent with the endpoint-not-relay stance; deferred).

## Part B — Receiver side (the phone; mostly already done)

6. **Receiving is already built.** A friend dialing the phone runs the
   existing serve path (`GossipServer` → `respondHandshake` → `KotlinSync.
   serve`), which AUTHs and ingests the pushed content through the
   friend-peering gates. No new receive code.
7. **Inbound push resets the backoff.** When a serve session ingests new
   content, treat it as a backoff "event" (same class as new-content-pulled)
   so the adaptive poll fades correctly: pushes keep the phone current →
   polls find nothing new → the interval backs off toward the cap → rare
   cheap polls; a push-miss (phone was unreachable) → the next poll finds new
   content → interval snaps back → catches up. The poll is **not removed**;
   it self-adjusts to near-zero while push is healthy and reasserts when push
   has gaps.
8. **Stay reachable.** Keep the onion published (arc-2 `publishOnion`,
   already wired into the foreground service). The measurement confirmed the
   onion survives Doze; no keep-warm machinery is needed for the common case.
9. **Inbound rate-limit.** Add a light per-peer inbound throttle on the serve
   side so a peer cannot spam full sync sessions at the phone (a peer that
   just synced is refused a fresh full session inside a short window — it has
   nothing new that fast anyway). Prevents a push-storm or a malicious
   dial-flood from draining the phone.

## Part C — Dead-onion liveness (so push hits the reachable onion fast)

10. **The gap.** An identity can have several device onions in the peer table
    (multi-device — different hosts coexist by design, `mergePeerAddress`).
    Dead ones (retired/reinstalled devices, e.g. the measured `mmifjiow…`)
    are never pruned: the gossip loop re-dials them every ~45s and eats a
    ~40s timeout each, and a pusher trying a dead address first delays the
    push to the live device. hearth has **no** dial-failure tracking today
    (the `disconnected` table is for defriended identities, not liveness).
11. **Track dial outcomes per address.** Record, per peer address, a
    last-success time and a consecutive-failure count (in-memory is
    acceptable to start; a small persisted table is a refinement). A
    successful sync (either direction) clears the failure count and stamps
    last-success.
12. **Prefer live, skip dead.** When dialing/pushing an identity that has
    multiple addresses, order by liveness (recently-succeeded first). After
    `MAX_DIAL_FAILURES` consecutive failures (or no success within a long
    window), stop dialing that address — either evict it (`store.remove_
    peer`) or hard-deprioritize it. **Eviction is safe**: a live device
    re-advertises its onion in every HAVE (`addr` field), so a wrongly-evicted
    live device is re-learned on its next contact via `mergePeerAddress`. A
    device that is merely off (not dead) simply gets re-added when it returns.
13. **Applies to both platforms.** The dialer ordering/pruning lives wherever
    peers are dialed: hearth's gossip loop + push path (Python), and the
    phone's `SyncRunner` peer-loop (Kotlin, friend-peering). Both should
    prefer the live address and skip a proven-dead one.

## Testing / gates

- **hearth pytest:** a local write schedules a push to entitled reachable
  peers; a **non-entitled** peer is NOT pushed to (reuse the give-side gate —
  a DM does not push to non-recipient friends, a ring not to non-author);
  rapid writes coalesce to one push per peer (debounce); an addressless/
  proven-dead peer is skipped; push shares the throttle map with the gossip
  loop (no double-dial). Dead-onion: N consecutive failures → address
  skipped/evicted; a subsequent HAVE re-advertisement re-adds it.
- **Loopback (real wire):** node A commits a write → A dials listening node B
  and B receives it *without B polling* (assert timing/ordering); a
  non-entitled node C dialed-or-not gets nothing; A with a dead + a live
  address for B's identity reaches B via the live one and stops hammering the
  dead one. Reuse the friend-peering loopback harness.
- **Kotlin JVM:** the inbound-push-resets-backoff seam (pure, like the
  existing `pulledNewContent`); the inbound serve rate-limit decision (pure);
  the `SyncRunner` dead-onion ordering/skip (pure helper, like
  `acceptPeerIdentity`/`shouldThrottle`).
- **On-device DoD (August drives):** a friend's node posts → the phone
  receives it **near-real-time without polling** (the push path — verify by
  timing vs. the poll interval); the adaptive poll observably backs off while
  push is healthy and reasserts after a push-miss; a retired device's dead
  onion stops being dialed (no more 40s timeouts against it). Precondition
  check: confirm the phone is push-reachable (the measured steady-state), not
  in a fresh-publish warmup.

## Honest limits

- **Best-effort, not guaranteed.** Push lands only when the receiver is
  reachable; the adaptive poll remains the safety net for unreachable windows.
- **Deep-Doze untested.** ~15-min light Doze survival is proven; hours-long
  deep Doze (phone in a drawer overnight) is untested and may degrade
  reachability — a longer measurement is a follow-up, not a blocker.
- **One-time publish warmup remains.** Right after a (re)start the onion is
  slow to reach for the first few minutes while its descriptor propagates;
  this arc does not pre-warm it (a small startup pre-warm is a possible
  follow-up). Not a steady-state issue.
- **Own-writes only.** No push-on-relay / multi-hop real-time — a node only
  pushes what it authored, not what it forwarded.
- **Fan-out is N dials per write** (N = entitled reachable peers), bounded by
  debounce + the shared throttle; heavier batching/coalescing across peers is
  a later refinement.
- **Dead-onion liveness starts in-memory** (resets on restart — acceptable;
  a restart re-learns liveness quickly); a persisted liveness table is a
  refinement.

## Out of scope (later arcs / tickets)

Push-on-relay / multi-hop real-time delivery; a held-connection fast-path
(the "hybrid C" option — revisit only if per-write dials prove too costly);
onion pre-warm on startup; deep-Doze reachability hardening; a persisted
dead-onion liveness table; battery/charging-aware push suppression; the
carried friend-peering + arc-2 tickets (per-message revoked-device seq gate,
blob-GC, gossip_addr literal getter). The wall/journal *rendering* parity
inconsistency is a separate UI slice, not part of this networking arc.
