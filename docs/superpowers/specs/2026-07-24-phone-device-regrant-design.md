# Phone Device Re-Grant — Bidirectional Own-Device Mesh — Design

Date: 2026-07-24. Slice 1 of the "my devices sync content between themselves"
work (slice 2 = the identity-content-key redesign, a separate later design).
Makes the phone a **full re-grant participant** so content flows between a
user's own devices in BOTH directions, not just desktop→phone.

## Context

Content in hearth is encrypted with a per-message content key that is
**wrapped per recipient DEVICE** (to each device's X25519 enc key). A device
can only read a message whose content key was wrapped/granted to *its* key.
To cover a device that was not in the wrap set at compose time (a late
satellite, or a device the author didn't know), hearth runs **own-device
re-grant sweeps**: a device that CAN decrypt a message mints a `wrap_grant`
sealing that message's content key to its **sibling** (same-identity) devices'
enc keys.

**The gap (confirmed 2026-07-24):** those sweeps run **only on the desktop**
(hearth Python — `maintain_own_device_grants` node.py:2158,
`maintain_received_dm_grants` :2239, `maintain_received_post_grants` :2310, all
called each maintenance cycle at api.py:97-101 / sync.py:269-273). The **phone
never mints grants** — its Kotlin only *reads* them (DecryptPass) and wraps its
*own* composes at compose time. So the mesh is one-directional:

- desktop → phone works (desktop re-grants what it decrypts to the phone), but
- **phone → desktop does NOT.** Content only the phone can decrypt — e.g. a
  friend's wall post author-granted to the phone but not the desktop (because
  the friend lacks the desktop's enc key) — is **stuck on the phone forever.**

This produced the observed wall/journal split (phone shows a friend's wall the
desktop can't). The fix is to give the phone the *mint* side.

**Why this and not an identity-content-key (slice 2):** the identity-key
redesign would retire this whole per-device coverage problem structurally, but
it is a major rewrite of the security-critical crypto core + a changed
revocation flow — its own careful design. This slice is the correct,
contained fix *within* the current per-device model, and it earns its place
regardless of slice 2: it is device-to-device **store-and-forward among your
own devices** — the phone catches a friend's post while the desktop is
offline, and when the desktop later comes online (friend now offline) the
phone hands it over AND mints the grant so the desktop can read it, with the
friend nowhere in sight.

## What to build — port the own-device re-grant sweeps to the phone (Kotlin)

Mirror the three hearth sweeps BYTE-FAITHFULLY into the phone, minting
`wrap_grant` messages that the phone signs with its own device key and pushes
on the next sync. The phone already holds every primitive needed:
`KotlinDmcrypt.wrapKey` (seal a content key to device enc_pubs), device
signing (it composes), `deviceViews`/sibling enc_pubs (synced from home), and
DecryptPass already *accepts* these grants (B.2/B.2c) — this slice supplies the
missing *mint* side.

1. **`maintain_received_post_grants` (primary — the wall/journal fix).**
   Recipient-signed. For each friend post the phone CAN decrypt, for each
   sibling device missing coverage, mint a `wrap_grant` (author = phone's
   identity, signed by the phone's device, target = the post's msg_id) sealing
   the post's content key to that sibling's enc key. Covers BOTH placements
   (wall + journal) — "a satellite can read a wall/journal post the phone
   already decrypted." Mirror node.py:2310-2389 exactly.
2. **`maintain_received_dm_grants`.** Same, for received friend DMs (mirror
   node.py:2239-2308; the phone already ACCEPTS these — B.2c — now it mints
   them for its siblings).
3. **`maintain_own_device_grants`.** Author-signed. Re-wrap the phone's
   OWN-authored content (all placements + DMs) to sibling devices missing
   coverage (mirror node.py:2158-2237). Lower urgency (the phone's own composes
   already wrap to current siblings; this covers a sibling added *later*
   reading the phone's history) but include it for a complete mesh.

## The sharp edges (get these exactly right)

- **Prune invariant / carry-forward (THE B.2 lesson — highest risk).** Each
  sweep's minted grant must be **full-coverage** and **carry forward the
  latest existing grant's entries**, not a need-only delta. hearth's store
  prunes to one surviving grant per (author, target) key; a need-only mint
  makes the two devices' sweeps ping-pong grants forever (the exact bug the
  B.2 Task-2 review caught). The Kotlin mint must reproduce hearth's
  full-coverage + carry-forward shape and share the same prune keyspace.
- **CURRENT enc key.** Seal to each sibling's *current* published enc_pub
  (mirror the sweeps' enc_pub freshness check) — a stale enc_pub yields an
  un-openable grant.
- **Own-devices-only, author-scoped.** The phone may seal ONLY to its own
  identity's sibling devices — never widen a friend's audience. Recipient-
  signed grants are honored only for the recipient's own devices (the existing
  DecryptPass entitlement rule, B.2c) — minting must match that boundary so a
  minted grant is exactly as trusted as one from the desktop.
- **Locked/revoked guard.** Minting signs; a locked or revoked phone must skip
  the sweeps entirely (mirror the hearth guard `if self.revoked or
  self.locked ...`) — no half-mint, no crash.
- **Idempotent + bounded.** Re-running the sweep with no new coverage mints
  nothing (fixpoint); grants ride the normal outbound sync (no new push
  channel).

## When it runs

After each sync ingests content — mirror hearth's placement (api.py:97-101 /
sync.py:269-273: the maintain_* sweeps run right after a gossip round). In the
phone: after `SyncRunner`/foreground sync stores newly-pulled content, run the
re-grant sweeps; the minted `wrap_grant`s enter the outbound set and push to
siblings on the next sync (or immediately via the existing on-compose/sync
path). Runs in the background service too (it is JS-free Kotlin, like the sync
transport) so a backgrounded phone still re-grants.

## Testing / gates

- **Kotlin JVM (vector-gated):** the minted `wrap_grant` is byte-identical to
  hearth's for the same inputs (extend the dmcrypt/message vectors — a grant
  minted by the Kotlin sweep must match one hearth would mint: same canonical
  body, same wrap shape, same signature-verifiable form). The prune-invariant
  **fixpoint**: running the sweep twice, then a hearth sweep, then the Kotlin
  sweep again mints nothing new (no ping-pong) — the B.2 fixpoint test ported.
  Own-devices-only: the sweep never seals to a friend's device. Locked-guard:
  a locked store mints nothing.
- **Loopback (real wire):** seed the phone with a friend post it can decrypt
  but a sibling device cannot; run the phone's sweep; a real hearth sibling
  node syncs from the phone and now DECRYPTS the post via the phone-minted
  grant (proves the mint is honored end-to-end by real hearth). Negative: a
  non-sibling (friend) node gets no new decryptability.
- **hearth pytest:** unaffected (no hearth change — the phone mirrors; confirm
  green). The phone-minted grants must survive hearth's own prune (a
  loopback/vector check that hearth's `store` keeps, not drops, a
  well-formed phone grant).
- **On-device DoD (August drives) — the headline:** with the DESKTOP OFFLINE,
  the phone receives a friend's wall post (author-granted to the phone). Bring
  the desktop online with the FRIEND offline; the desktop syncs from the phone
  and now **renders the friend's wall** — content the friend never granted the
  desktop directly, delivered + made-decryptable by the phone. Plus: the
  wall/journal split from 2026-07-24 resolves (both devices converge on the
  same friend content as grants propagate).

## Honest limits / out of scope

- Does NOT change the per-device wrap model — that is slice 2 (identity content
  key). This makes the existing model's mesh bidirectional; it does not remove
  the underlying O(messages × devices) grant proliferation (slice 2's job).
- Still depends on **enc-key propagation**: the phone can only seal to a
  sibling whose enc_pub it holds (it does, from home sync). The separate
  "desktop's enc key never reached the laptop" propagation issue (desktop
  doesn't sync directly with the laptop) is a distinct thread and not fixed
  here.
- Journal posts that NO current device ever decrypted (wrapped only to a
  now-dead device) remain unrecoverable — no device can re-grant what it can't
  read. (Slice 2 or a policy change on journal re-granting would address the
  residue.)
- No new sync/push channel; grants ride existing outbound.
- Battery/charging-aware sweep scheduling is a later refinement.
