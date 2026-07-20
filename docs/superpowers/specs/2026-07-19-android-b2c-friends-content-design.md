# Android B.2c (friends' content readable) — design, 2026-07-19

Fifth slice of the Kreds Android client, after the Tor-dial spike (PR #1),
Brick A (PR #2), Brick B.1 (PR #3), and B.2 (decryption + readable own
history — PROVEN on the G20 2026-07-19, branch `brick-b2-decryption`,
`android_tor_spike/BRICK_B2_REPORT.md`). B.2 deliberately rendered
own-authored content only; B.2c makes FRIENDS' content readable.

## Scope (August, 2026-07-19): everything, including old received DMs

Chosen over the no-hearth-change alternative (walls + new DMs only):
**complete friend history**, which requires one further production `hearth/`
change — a recipient-signed re-grant of received DMs to own devices.

What already works with NO change (verified against `store.py:702-748`
during brainstorming):

- The phone ALREADY RECEIVES friends' wall posts, friend DMs addressed to
  us, and friend-authored wrap_grants naming our devices — encrypted. The
  serving gate passes any message wrapped/granted to any of the peer
  identity's devices, and the phone authenticates as the own identity.
- Friends' STOCK nodes (no update, no min_core bump) mint wrap_grants for
  their ENTIRE wall history to the phone automatically, once the phone's
  published enckey gossips to them via the home node. Friend journal
  content never leaves their node (by design; unchanged).
- NEW friend DMs inline-wrap to all of our enc-keyed devices at compose
  time — including the phone, now that its enckey is published.

The ONE gap: OLD received DMs (composed before the phone's enckey existed)
are wrapped only to the devices we had then. No stock mechanism re-wraps a
friend-AUTHORED DM to our new device. Our desktop holds those content keys
(it decrypts those DMs today) — it re-grants them to our own phone.

## Trust-model extension (the core of the slice)

A wrap_grant for a DM is honored when signed by the DM's **author**
(existing rule) OR by the DM's **recipient** — and a recipient-signed grant
is honored ONLY for devices enrolled to that same recipient identity.

- Safety: the recipient already holds the plaintext. Re-wrapping the
  content key to the recipient's own enrolled device (valid identity-signed
  cert — the same thing AUTH already requires) discloses nothing to anyone
  new. Identical premise to B.2's own-device backfill.
- Posts are untouched: author-signed only, exactly as today.
- The new rule is CONSUMED only on the phone (`DecryptPass`). The desktop
  never reads DM grants (`_content_key` consults grants for `KIND_POST`
  only) — desktop decrypt behavior is unchanged.
- No friend-side impact: grants route only to the devices named in their
  wraps (`messages_not_in`'s existing `KIND_WRAP_GRANT` gate), and a
  recipient-signed grant names only own devices — it never reaches a friend.
  No min_core bump.

## Components

### 1. Hearth: `maintain_received_dm_grants` (the production change)

A THIRD isolated sweep in `hearth/node.py`, beside `maintain_own_device_grants`
(B.2) and the untouched friend-facing `maintain_wrap_grants`. Called from
the same two call sites (`api.py`, `sync.py`), immediately after
`maintain_own_device_grants()`. Same guard: revoked/locked/unenrolled skip
entirely (minting signs).

For each received DM the node holds AND can key (author != self,
recipient == self, `_content_key`-equivalent DM key available): if some own
satellite device (own enckeys minus self) lacks coverage, mint ONE
recipient-signed full-coverage grant wrapping the DM's content key to ALL
own satellite devices, with the `enc_pub` annotation. Latest-grant coverage
check reuses B.2's `_latest_own_wrap_grants` machinery (the prune's exact
`(created_at, seq)` tie-break).

Prune-keyspace analysis (why no cross-sweep ping-pong is possible here):
`prune_superseded_wrap_grants` keys on `(grant author identity, target)`.
These grants key as `(me, friend-dm-id)` — disjoint from author-signed
grants for the same DM `(friend, dm-id)` and from `maintain_own_device_grants`'
targets (own-authored messages). Full-coverage mints + the latest-check
still apply (self-consistency across own multi-satellite), and the fixpoint
test proves it empirically.

### 2. Phone: DecryptPass entitlement rule (replacing the own-author filter)

B.2's `ownIdentityPub` filter (commit `ceaf1a6`) relaxes into:

- **Posts:** decryptable via inline wraps or AUTHOR-signed grants
  (`wrapGrantsFor(msgId, authorIdentityPub)` — the existing author scoping,
  now with friend authors allowed through).
- **DMs:** decryptable via inline wraps, AUTHOR-signed grants, or
  RECIPIENT-signed grants where the grant's verified signing identity ==
  the OWN identity AND the own identity is the DM's recipient.
- Everything else remains fail-closed skip-never-crash. Friend journal
  content never arrives (server gate), so no phone-side placement filter is
  needed; anything undecryptable simply doesn't render.

### 3. Phone: feed display

- Feed items gain the author's display name (the phone already stores the
  friend list with names from B.1; own items may show "me"/own name).
- Fix the "3 friends" stat label: exclude the own identity from the count
  (store has own + 2 friends; August has 2 friends).
- Still the minimal text feed: kind + author + text + timestamp. Media,
  threads, reactions, stories remain B.2d.

### 4. Desk gate: two-node loopback

Extend the loopback harness to TWO real python nodes — home + a simulated
friend node (the field friends are only intermittently active, so the gate
cannot rely on them):

1. friend node befriends the home identity; posts wall content; sends a DM
   to the home identity BEFORE the phone fixture's enckey exists (the
   old-DM case); syncs with the home node.
2. phone publishes its enckey (sync 1, over the wire as in B.2's gate);
   home node gossips it to the friend node; friend node's STOCK
   `maintain_wrap_grants` mints wall grants naming the phone; home node
   runs `maintain_received_dm_grants` for the old DM; friend sends a NEW
   DM (inline-wraps to the phone).
3. phone sync 2 pulls everything; `DecryptPass` must yield: the friend's
   wall history, the NEW friend DM, and the OLD friend DM (via the
   recipient-signed backfill) — texts asserted exactly.

## Data flow (the old-DM backfill leg)

```
friend node ──DM (pre-phone wraps)──> home node   (phone can't read it)
phone ──enckey──> home node
home: maintain_received_dm_grants -> recipient-signed wrap_grant
      {target: friend-dm, wraps: {phone: wrap}}   (routes only to phone)
phone sync -> pulls DM + recipient grant -> unwrap (signer==own identity,
      own is recipient) -> decrypt -> feed shows friend's DM text + name
```

## Testing

- **Hearth pytest (heaviest coverage, the production change):** positive
  end-to-end (phone unwraps + decrypts an old received DM via the
  recipient-signed grant); REQUIRED security negatives: the sweep never
  grants to a non-own device; never targets own-authored or non-DM
  content; locked/revoked mint nothing (biting assertions per B.2's
  pattern); idempotency/fixpoint across ALL THREE sweeps + prune over
  multiple rounds (extending B.2's fixpoint test).
- **Phone JVM tests:** entitlement rule — friend post via author grant
  decrypts; received DM via recipient-signed grant decrypts; REQUIRED
  negative: a recipient-signed grant whose signer is NOT the DM's
  recipient (hostile friend "re-granting" someone else's DM) is rejected;
  own content still renders (B.2 regression).
- **Two-node desk loopback gate** (above) — the end-to-end proof before
  hardware, incl. the stock-friend-sweep leg.
- **On-device:** old friend DMs readable on the G20 — provable with just
  the desktop (no friends online needed). Friend wall posts render
  whenever the real friends' nodes next sync (best-effort field leg,
  already proven at the desk gate).

## Definition of done

The G20's feed shows readable friend content with author names: at minimum
the old received DMs (desktop-only dependency), and friend wall history
once real friend nodes sync. Desk-proven first via the two-node gate.

## Out of scope (named)

Rich rendering — media/blobs, threads, reactions, stories (B.2d);
composing/posting from the phone; background sync (Brick C); any change to
`maintain_wrap_grants`; friend-side hearth changes of any kind; OS-keystore
enc-key protection.

## Risks / honest unknowns (resolve during build)

- **DM content-key access at sweep time** — the desktop decrypts received
  DMs today, but confirm the exact key-recovery path the sweep should use
  (`_content_key` handles posts; DMs may need the DM-specific accessor).
  Pin against `node.py` during Task-brief writing.
- **Two-node gate plumbing** — the loopback harness has only ever run one
  node; two nodes gossiping over loopback + a phone session is new harness
  work (the four-node demo cast in `hearth/demo.py` is prior art).
- **Field leg timing** — friends' nodes are only partly/intermittently
  active (August, 2026-07-19); the wall-history leg on real hardware may
  land days after merge. The desk gate carries the proof burden.
- **Stacked branch** — B.2 (`brick-b2-decryption`) is not yet merged; B.2c
  stacks on it. Merge order is August's call (merge B.2 first is cleaner).
