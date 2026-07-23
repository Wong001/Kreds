# Kotlin Gossip Server — the Phone's Answering Side (Arc 1) — Design

Date: 2026-07-23. Arc 1 of the phone-as-full-node effort (decided with
August: pause the nudge/liveness slice; build the phone's server first,
since the phone today can dial but cannot listen — every networking path
is initiator-only). Full-entitlement serve surface (friends included).

## Context: the phone can talk but not listen

Verified in the codebase: `KotlinSync.run` is explicitly INITIATOR-only
(KotlinSync.kt:63), `KotlinHandshake.runOverStream` is "phone = initiator"
(KotlinHandshake.kt:65), and `TorEngine` only opens a SOCKS port to dial
out (TorEngine.kt:45-82) — no `ServerSocket`, no accept path, no onion
service. The always-on Python hearth node on the user's desktop answers
the phone's calls. This arc gives the phone the RESPONDER half of hearth's
`_session` so it can answer a dial and serve/receive a full sync round.

## Goal

The phone accepts an authenticated inbound connection on loopback and
completes a full sync round as the RESPONDER — serving each peer exactly
the content that peer is entitled to (rings/scopes), and ingesting what
the peer offers — byte-parity with hearth's `_session`. Proven by a REAL
hearth node dialing the phone's Kotlin server over loopback.

## Scope boundary (what this arc is and is NOT)

- IS: the responder/answer side of the sync protocol + the give-side
  entitlement filter, on a `127.0.0.1`-bound `ServerSocket`.
- NOT reachable over Tor — the phone onion service is arc 2. Arc 1 is
  loopback-proven now, production-exercisable after arc 2 (the same
  desk-proven-then-wired pattern as the outbound-crypto slices).
- NOT friend PEERING — arc 1 only ANSWERS; the phone still dials only its
  home node for outbound. A friend peer table + the phone dialing friends
  directly is arc 3.
- NOT store-and-forward (the both-offline problem) — arc 4.
- The paused nudge/liveness channel folds on top of this server as an
  immediate follow-on (a held connection + push frames), not part of arc 1.

## Architecture

Mirror hearth's `_session` responder (hearth/sync.py:579-825) in Kotlin,
answering the same frames the phone already speaks as initiator. An accept
loop runs inside the existing foreground `TorNodeService` (Brick A), bound
to `127.0.0.1` only. Each accepted socket is wrapped in the existing
`Stream` abstraction so the handshake/sync code stays transport-agnostic
(the same seam the onion will slot into at arc 2). Give-side entitlement
(hearth `messages_not_in`, store.py:702; `all_summaries`, store.py:199) is
ported into the phone's store so serving is correctly scoped.

## Components

1. **`GossipServer.kt`** (create) — `ServerSocket` accept loop +
   per-connection handler, mirroring `_on_conn`'s dispatch (sync.py:174).
   Arc 1 has ONE branch: the normal authenticated session. friend-add /
   pair-request are desktop-only, out of scope. Binds `127.0.0.1:<port>`
   (a fixed/known local port or store-recorded), accepts, wraps each
   socket in `Stream`, dispatches to the responder on a worker thread.
   Bounded concurrency (a small accept pool; reject/queue beyond it).
2. **Handshake responder** (extend `KotlinHandshake`) — the AUTH answerer:
   read peer HELLO, send own HELLO, verify peer cert (`KotlinWire.verifyCert`),
   verify the peer's device-key signature over our nonce, prove our own
   device key over theirs. Unknown identity / failed AUTH → `{"t":"refused"}`
   then close — byte-parity with hearth's stranger refusal (sync.py:630-641).
   Returns the authenticated peer identity for the session to scope on.
3. **Sync responder** (extend `KotlinSync`) — the content phases, answering:
   REVOCATIONS (exchange, apply received) → DEFRIENDS (apply-then-ack) →
   HAVE (send our `all_summaries` + known identities + our loopback addr;
   receive the peer's summaries → compute what to give) → MESSAGES (serve
   the entitled delta via the ported give-side filter; ingest the peer's
   via the EXISTING verify/ingest path) → BLOBS (serve wanted blobs
   smallest-first within `BLOB_GIVE_BUDGET`, sync.py:31-50; ingest theirs).
   The receive side reuses the phone's existing ingest gates (cert verify,
   entitlement) unchanged.
4. **Give-side entitlement in `SqliteSyncStore`** (modify) — port
   `messages_not_in(summaries, entitled, peer_identity)` (store.py:702) +
   whatever `all_summaries`/ring/scope helpers it needs. This is the
   security-critical new store capability: the rule that decides what each
   peer may pull. Must match hearth's filtering exactly (rings: inner vs
   kreds; scopes; own vs friend vs stranger) — an over-serve is a privacy
   breach.
5. **Lifecycle wiring in `TorNodeService`** (modify) — start the accept
   loop once Tor + store are up; stop it on service teardown. Coordinate
   store access with `SyncRunner`'s existing process-wide `ReentrantLock`
   (SyncRunner.kt:55) so an inbound serve and an outbound sync never run
   over each other / corrupt a transaction.

## Data flow

peer dials phone loopback port → `GossipServer` accepts → handshake
responder HELLO/AUTH (verify peer, prove self, refuse strangers) →
REVOCATIONS/DEFRIENDS → HAVE (phone sends its summaries, learns the peer's)
→ phone serves the entitled message delta + ingests the peer's → blobs both
ways within budget → close. Symmetric to the phone's existing outbound
sync, answering instead of asking.

## Security

- Mutual AUTH refuses strangers (parity with hearth); the authenticated
  peer identity — not anything a frame claims — scopes the give side.
- The give-side filter must NEVER over-serve: a kreds-scope friend gets
  kreds content only, never inner-ring or non-entitled messages; a
  stranger never authenticates; an own sibling gets own-device content.
  This is the load-bearing invariant and the primary negative test.
- Receive/ingest keeps the existing verify gates (no new trust surface on
  the inbound direction).
- No `identity_priv` involved. Loopback-only bind = zero external exposure
  until arc 2 deliberately publishes the onion.
- Concurrency: the server handler and `SyncRunner` share the process-wide
  lock; the store's own transaction discipline holds under concurrent
  accept + outbound sync.

## Testing / gates

- **JVM units:** handshake responder (verify peer, prove self, refuse
  stranger — the refusal asserted precisely, not a generic catch); the
  give-side entitlement matrix — own-device / kreds-friend / inner-friend
  / stranger, with an explicit OVER-SERVE NEGATIVE (kreds friend must not
  receive inner-ring or non-entitled content); the responder content
  phases against a scripted `Stream` (mirror the existing initiator-side
  fake-Stream tests).
- **Loopback fidelity gate (the parity proof):** a REAL hearth node
  (existing `sync_loopback_node.py` harness) DIALS the phone's Kotlin
  `GossipServer` over loopback and runs its real initiator `_sync_session`
  → completes a full round: (a) an own-sibling dialer pulls the phone's
  own content + pushes; (b) a friend dialer receives ONLY its entitled
  content (assert a seeded inner-ring / non-entitled message is NOT
  delivered — the over-serve negative at the real-wire level); (c) a
  stranger is refused at AUTH. Fail-closed; a divergence is a REAL parity
  bug → BLOCKED, never weakened. This inverts every prior loopback gate
  (which had the phone dial the node); here the node dials the phone.
- **On-device DoD (honest, thin for arc 1):** the server starts inside
  `TorNodeService` on the G20 and accepts a loopback dial (a local test
  client, e.g. `adb`-driven or an in-app probe) completing a round —
  proving the accept loop runs on real Android under the foreground
  service. Real Tor reachability is arc 2; the report states this plainly.

## Honest limits

- Loopback-only until arc 2; no friend can actually reach the phone yet.
- Arc 1 does not add outbound peers (the phone still dials only its home
  node); it teaches the phone to answer, not to reach out to friends.
- The both-offline availability problem is untouched (arc 4).
- Serving correctness rests on the ported `messages_not_in` matching
  hearth exactly; the loopback gate's real-node initiator + the over-serve
  negative are what prove it.

## Out of scope (later arcs / tickets)

Phone onion service (arc 2); friend peer table + phone-dials-friends
directly (arc 3); store-and-forward / relay strategy (arc 4); the nudge/
liveness channel (folds onto this server next); friend-add / pairing
accept branches on the phone (desktop-only today, revisit if ever needed).
