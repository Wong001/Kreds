# Sharded mailbox guardians — design sketch (pre-brainstorm seed)

Date: 2026-07-09
Status: **sketch only** — captured from a live design session (August +
Claude, night of the 0.3.6 release) for a future concentrated
brainstorm → spec → plan cycle. Nothing here is committed design.

## The problem (stated precisely)

Two friends with no common friends who are never online simultaneously
never exchange anything: posts need a direct sync or a mutual friend
inside the post's audience; DMs need a direct sync or the recipient's own
always-on device (home node). The unsolved case is exactly: **two
single-device users, zero overlap, zero mutuals.**

The constraint is topological, not cryptographic. While B is offline the
bytes must live somewhere, and there are only three somewheres: the
sender's hardware (retry — status quo), the recipient's own other
hardware (home node — already works today), or a third party. Any third
party irreducibly learns *that it holds something, deposited at some
time*. No protocol removes that; designs only choose who the third party
is and shrink what else they learn.

## Decisions already made (2026-07-09 session)

1. **Honest delivery state, soon** — "reached 3 of 6 friends' devices" /
   "waiting: their devices are offline, we keep trying" (rides the
   receipts/delivery-acks roadmap item). Converts silent failure into
   honest failure. Zero new trust.
2. **Pathology detection, cheap** — a node locally knows when delivery to
   one friend has failed for N days; surface it and recommend an
   always-on device. Local knowledge only, zero privacy cost.
3. **Home node as the recommended setup** (site guide: old PC / Pi /
   household box). The architecture's designed answer; work is packaging,
   not protocol.
4. **Sharded guardians** (this sketch) — the eventual gap-filler for
   laptop-only circles.
5. **Central/federated postbox: never as a default.** A standing
   intermediary makes Kreds provider-shaped again — undermines both the
   breach-surface story and the "no provider to order" policy argument.

## The sharded-guardian kernel

Origin: August's "crypto tumbler" idea (fragment + wash + no single
holder can reconstruct), redirected from a public pool to the social
graph. The pool part fails for known reasons (tiny anonymity sets;
Sybil-able open membership; the pool operator is a provider). The
*splitting* part is the keeper:

- **B designates guardians**: several of B's own friends' usually-on
  nodes (say n = 5). Being a guardian is opt-in and visible to the
  guardian (they know they hold B's shares — they're B's friend).
- **Envelope**: the message (a post row or a sealed DM) is wrapped in an
  opaque, fixed-size-padded outer envelope. For DMs this envelope also
  seals the blob hashes that are cleartext today — the *implementation*
  half of the no-relay invariant; the *metadata* half (holder learns B
  receives something) is the named, opt-in trade.
- **Split**: Shamir k-of-n over the envelope (working default: **3-of-5**).
  Each share is individually meaningless noise. No single guardian holds
  ciphertext at all; reconstructing even the *ciphertext* requires k
  colluding guardians — who are B's own chosen friends.
- **Deposit**: sender A (possibly a stranger to the guardian) deposits
  one share per guardian over Tor, presenting a **single-use deposit
  ticket** signed by B naming A (or a pseudonymous capability). Guardian
  stores without learning who A is; refuses without a valid ticket
  (closed membership — the Sybil surface a tumbler has and this doesn't).
- **Pickup**: B fetches shares with opaque pickup tokens whenever B comes
  online; k shares reconstruct; envelope opens on B's device as normal.
- **Symmetric trick — couriers**: the same envelope pointed the other
  way: A hands shares to A's own always-on friends, who retry B (or B's
  guardians) on A's behalf. Delivery then needs any overlap between
  {A + A's couriers} and {B + B's guardians} — with one usually-on node
  on either side, eventual delivery approaches certainty.

## What each party learns (name it, per house style)

- A guardian: "I hold shares for B; deposits arrive at these times, this
  volume" — NOT contents, NOT the sender, NOT (below k colluders) even
  the ciphertext. Same disclosure class as the have-frame, smaller.
- k colluding guardians: the ciphertext + its timing — still not the
  plaintext (E2EE underneath is untouched).
- The sender: B's guardian-set contact points (see open question 3).
- Nobody else: transport is onion-to-onion throughout.

## Open questions for the real brainstorm

1. **Guardian-set discovery**: how does A learn B's guardians + get
   tickets without a metadata leak? (Candidate: a signed "delivery
   capability bundle" exchanged inside the friendship itself, updated
   like enckeys — latest-wins record.)
2. **Churn/re-sharding**: guardian unfriended/revoked/offline-forever;
   rotation; what happens to undelivered shares (expiry: envelopes die
   after N days like unfriend notices' 14-day window?).
3. **Scope order**: posts-only first (audience metadata already exists)
   vs DMs too (requires the envelope redesign + explicitly relaxing the
   no-relay invariant for the opt-in guardian path only).
4. **Padding/uniformity**: fixed share size; big media (blobs) probably
   excluded or chunked — maybe guardian delivery is for the message row
   and blob fetch stays direct/lazy.
5. **Storage etiquette**: per-friend caps on guardian nodes, eviction,
   what a guardian's owner sees in their UI (they should know they ARE a
   guardian and how much they hold — honesty applies to guardians too).
6. **k/n defaults and UX**: who picks guardians (explicit? suggested from
   always-on friends?), sane defaults, what happens below k available.
7. **Interaction with unfriend/deletion**: delete tags must chase queued
   envelopes; unfriending a guardian re-shards; unfriending the SENDER
   while envelopes are queued.
8. **Cover traffic**: probably no (cost/benefit at household scale), but
   decide deliberately.

## Sequencing (agreed)

Honest delivery state → home-node recommended setup (site guide) →
sharded guardians (this design, own brainstorm→spec→plan cycle) →
central postbox: never.
