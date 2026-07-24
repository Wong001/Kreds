# Friend Peering + Dynamic Cadence (Arc 3) — Design

Date: 2026-07-23. Arc 3 of phone-as-full-node. The phone gets a peer table
and dials friends DIRECTLY (and friends dial the phone's arc-2 onion), so a
phone-only user or one whose desktop is offline still exchanges with
friends. Bundled with a cadence overhaul (decided with August: the fixed
15-min timer is replaced by event triggers + adaptive backoff).

## Context

Arc 2 (merged) made the phone reachable (a stable onion, answered by the
arc-1 gossip server) and safe (revocation/defriend parity). But the phone
still DIALS only its home node (`SyncRunner` single-dial of `fx.onion_addr`)
and has NO peer table — it knows friend IDENTITIES (from pairing's `friends`
list + home-node gossip `known`) but no ADDRESSES to dial them. The pairing
package's `peers` list and every HAVE frame's `peers`/`addr` fields are
PARSED but DROPPED (the arc-1/2 decision). This arc flips those drops into a
peer table and loops the dialer over it.

Key enabling mechanism (hearth, already built): a HAVE frame gossips a
node's ENTIRE peer table (`sync.py:763`), and each receiver merges any entry
for an identity it ALREADY KNOWS (`is_known` gate, `sync.py:777-781`). So
friend addresses propagate TRANSITIVELY across the friend graph — the phone
inherits friend identities and just needs the address half, which its home
node relays. No phone-side friend-add ceremony is needed.

## Part A — Friend peering (the phone dials friends)

1. **Peer table** on the phone's `SyncStore`/`SqliteSyncStore`/InMemory —
   `addPeer(address, identityPub)`, `listPeers(): List<Peer(address, identityPub)>`,
   `removePeer(address)`, `addressFor(identityPub): String?` — mirroring
   hearth `store.py:39-40, 217-239` (address-keyed PRIMARY KEY, identity_pub
   nullable). SQLite: a `peers` table via the non-destructive CREATE-IF-NOT-
   EXISTS onOpen pattern (established: keys/pending_outbound/meta tables;
   DB_VERSION unchanged).
2. **`mergePeerAddress(store, identity, addr)`** — port hearth
   `_merge_peer_address` (sync.py:93-131) BYTE-FAITHFULLY: onion-preferred
   (an onion addr always kept; a non-onion addr stored only if no onion
   known for that identity — never let plain-TCP shadow a Tor peer);
   onion eviction is HOST-keyed (same-host different-port rows are stale
   duplicates → evict; different onion hosts for one identity coexist =
   multiple devices). Reuse the `_is_onion` predicate shape.
3. **Learn addresses — flip two drops to keeps:**
   - Pairing install: `KotlinPairing.installPackage` currently drops the
     package's `peers` list (KotlinPairing.kt:255) — instead loop
     `mergePeerAddress(store, p.identity_pub, p.address)` per entry (guard:
     `is_known` + not our own onion, mirroring the desktop's install).
   - HAVE phase (both `run` + `serve`): the peer's `addr` (their own onion)
     and `peers` list (relayed addresses) are parsed then dropped
     (KotlinSync.kt HAVE). Instead: `mergePeerAddress` the peer's `addr`
     for the peer's identity (if `is_known` + not own), and each `peers`
     entry whose identity we already know (the transitive relay). Mirror
     sync.py:773-781.
4. **`SyncRunner` loops over `listPeers()`** instead of the single
   `fx.onion_addr` dial (SyncRunner.kt:174). Mirror hearth's peer-loop
   (sync.py:282-293): skip our own onion (`onion_host(addr) == own`); the
   home node is just another peer row (seeded from `fx.onion_addr` at
   install / kept in the table). Each dial is the existing mutually-
   authenticated `KotlinHandshake.runOverStream` + `KotlinSync.run`; a peer
   that refuses us (we were defriended) → PeerRefused, handled gracefully
   (don't hammer; keep the row, or drop per hearth — mirror hearth's
   handling). The give-side entitlement (arc 1) applies when serving; the
   ingest gates apply when pulling. No new trust surface — N authenticated
   friends instead of 1 home node.
5. **Endpoint, not relay:** the phone sends `peers: []` in its own HAVE (it
   does NOT disclose its friend graph's addresses onward — the multi-hop
   relay-FROM-phone role is deferred, per the arc-2 honest-limits note). It
   KEEPS advertising its OWN onion in HAVE `addr` (arc 2) so a friend
   dialing/being-dialed learns to reach the phone.
6. **Close arc 2's deferral:** `applyDefriendNotice` (arc 2) skipped peer-
   table cleanup (no table then). Now it also `removePeer` for the
   defriended identity (mirror hearth `remove_peer_identity`, node.py:1770)
   so the phone stops dialing an ex-friend.

## Part B — Dynamic cadence (replaces the fixed 15-min timer)

The phone's background sync scheduler (Brick C's fixed `SYNC_INTERVAL_MS =
900_000`) is replaced by:
- **Event triggers → immediate sync:** (a) ON COMPOSE — any local write
  (post/DM/response/react/retract) triggers a sync now (push the pending-
  outbound to peers), rather than waiting for the next cycle; (b) ON APP
  OPEN/RESUME — sync when the app foregrounds after being closed/backgrounded
  (a cold-start version half-exists in WebShell; extend to resume).
- **Adaptive-backoff periodic sweep** (the fallback): base interval ~10 min
  (`BASE_SYNC_MS`); a sweep that ingests NOTHING NEW from any peer backs the
  interval off (×2 each idle sweep) toward a ~1 hr cap (`MAX_SYNC_MS`); a
  sweep that DOES pull new content, OR any event trigger, OR active app use,
  snaps it back to base. Idle phone → rare cheap sweeps (battery); receiving
  phone → responsive. This is the pragmatic stepping-stone to the nudge
  (which later replaces the periodic sweep with a pushed signal).
- The scheduler must survive Doze/process-death as the foreground service
  already does (Brick A/C); the backoff state is in-memory (resets to base
  on service restart — acceptable; a restart is an event-ish moment).

## Testing / gates

- **JVM:** peer-table CRUD + `mergePeerAddress` (onion-preferred, host-keyed
  eviction, non-onion-doesn't-shadow — the exact hearth cases); the pairing-
  install + HAVE address-learning (seed a package/HAVE with friend addrs,
  assert the peer table gains them, `is_known`-gated); `SyncRunner` peer-loop
  (dials each listed peer, skips own onion — testable with a fake dialer);
  `applyDefriendNotice` now removePeers; the ADAPTIVE-BACKOFF math (a pure
  scheduler unit: idle sweeps double toward the cap, new-content/event resets
  to base — the interval computation is JVM-testable in isolation); the
  event-trigger wiring (compose → sync requested) where a seam exists.
- **Loopback gate:** the phone (with a friend's address in its peer table)
  DIALS a real hearth friend node → mutual AUTH + exchange (the new
  capability, real wire); and a real hearth friend node DIALS the phone's
  gossip server → sync (arc-2 reachability, friend-not-home-node). Assert
  content flows both directions, entitlement respected (a friend gets only
  entitled content — reuse the arc-1/arc-2 over-serve negative).
- **On-device DoD (August drives) — the headline:** with the DESKTOP
  (home node) OFFLINE, the phone still exchanges with a friend (the phone
  dials the friend directly using a learned+persisted address) — the
  phone-only/desktop-offline resilience proven. Plus: the adaptive cadence
  observed (an idle phone's sweeps space out; a compose triggers an
  immediate push; reopening the app syncs). And: a friend's node dialing
  the phone's onion delivers content without the phone polling.

## Honest limits

- The phone is a peering ENDPOINT: it dials friends whose addresses it
  learned (home-node relay while online, or pairing) and answers friends who
  dial in. It does NOT yet relay OTHER nodes' addresses onward (multi-hop
  relay FROM the phone) — deferred.
- Bootstrapping addresses in a PURE phone-only mesh with NO ever-online node
  is the store-and-forward problem — ARC 4. Arc 3 gives the dialing
  capability; a phone learns a friend's address via some node that knows it
  (typically the home node), so a brand-new friend added while every relay
  is offline won't have an address until a relay comes online.
- Friend-add (befriending someone NEW) still happens on a desktop; the phone
  inherits the identity + picks up the address via relay. No phone-side
  friend-add ceremony.
- The adaptive backoff is in-memory (resets on service restart) and is a
  stepping-stone; the full nudge (held connection + push) replaces the
  periodic sweep later and is the battery-optimal endpoint.
- Battery/charging-aware refinements (sync freely when charging, harder
  backoff on low battery) are a deliberate follow-up, not this arc.

## Out of scope (later arcs / tickets)

Multi-hop relay FROM the phone (send its `peers` list); store-and-forward /
pure-phone-mesh bootstrapping (arc 4); the nudge channel (folds on next);
phone-side friend-add ceremony; battery/charging-aware cadence refinements;
the carried arc-2 tickets (per-message revoked-device seq gate, blob-GC,
gossip_addr literal getter).
