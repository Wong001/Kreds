# Recipient-Side Post Re-Grants (Multi-Device Relay) — Design

Date: 2026-07-23. Slice A of the live-sync/revocation arc (decided with
August: A → B nudge channel → C revocation wipe). Fixes the field-observed
gap: a friend's (e.g. the laptop account's) old journal posts are visible on
August's desktop but undecryptable on his phone until the FRIEND comes back
online — the desktop holds the plaintext yet cannot share readability with
its own sibling device today.

## Goal

A friend-authored post readable by ANY of the recipient's devices becomes
readable on ALL of the recipient's devices, without the author being
online. Rights are granted to the IDENTITY (August's framing); encryption
stays per-device — the recipient's own devices fan readability out among
themselves, exactly as B.2c already does for received DMs.

## Why not identity-key wrapping (decided)

Wrapping content keys to the long-lived identity key was considered and
rejected (agreed 2026-07-23): under full pairing every device holds
identity_priv, so a REVOKED device could decrypt all future content
forever — revocation would stop protecting anything. Per-device enc keys +
recipient-side re-grants deliver the same user-visible outcome ("my
identity was granted this; all my devices can read it") without that
regression.

## Mechanism (mirror of B.2c's `maintain_received_dm_grants`, node.py:2239+)

### hearth: `maintain_received_post_grants` (new sweep, node.py)

- For every stored KIND_POST whose author is a KNOWN identity ≠ self and
  whose content key THIS node can recover (inline wrap for one of its own
  devices, or an existing author-signed wrap_grant): mint a
  RECIPIENT-signed `wrap_grant` targeting the post, wrapping the content
  key to this identity's OWN enrolled devices' current enc_pubs
  (`store.enckeys(own)` minus self — never a friend's device; same
  targets-derivation rule, and the same disclosure argument: the recipient
  already holds the plaintext, re-wrapping to its own identity-signed-cert
  devices discloses nothing new).
- FULL-COVERAGE mints + the same carry-forward/prune-safety contract the
  sibling sweeps obey (`store.prune_superseded_wrap_grants` is safe ONLY
  because mints are full-coverage — store.py:480-496; the new sweep must
  uphold the identical invariant; read both sibling sweeps' docstrings
  before writing a line).
- Distinct from and non-overlapping with `maintain_wrap_grants`
  (author-side, friends' coverage) and `maintain_own_device_grants`
  (own-authored): a received post has author ≠ self, so those sweeps never
  iterate it. `maintain_received_dm_grants` stays DM-only and untouched.
- Runs where the siblings run: node-app boot (api.py:97-100) and every
  gossip/sync tick (sync.py:249-250,276).
- Scope: KIND_POST, any placement (journal AND kreds wall), friend-
  authored, decryptable-here. Stories are plaintext (n/a); DMs covered by
  B.2c; responses have their own mechanism (KIND_RESPONSES rebuild).
  Blobs need nothing extra — once the content key unwraps, the existing
  decrypt-on-read blob path works.

### hearth: consumer trust rule (desktop read path)

The desktop's own grant-trust predicate must accept a recipient-signed
grant for a friend's post under the SAME conditions the phone will (below)
— find hearth's existing grant-acceptance rule for posts (the code path
that today accepts author-signed grants when decrypting; the plan pins the
exact function) and extend it symmetrically. Phone and desktop MUST
enforce byte-identical trust rules (parity invariant).

### Phone: `DecryptPass.resolveWrap` entitlement extension (Kotlin)

Current rule (B.2c, DecryptPass.kt:469-482 doc): posts trust grants
signed by the post's AUTHOR only; DMs trust author OR own-identity when
`payload.to == own`. New post rule — a grant is ALSO trusted when ALL of:
1. the grant is signed by OUR OWN identity (`grant author == ownIdentityPub`
   — only own devices hold identity_priv);
2. the post's author is in `store.knownIdentities()` minus self (we
   regard them as a friend — the entitlement analog of the DM rule's
   `payload.to == own` guard: our own compromised-device threat model
   must not let an own-signed grant unlock content whose author we never
   befriended);
3. the wrap consumed targets THIS device (as always).
The DM branch's hostile-grant reasoning (DecryptPass.kt:472-475) applies
verbatim — replicate its guard style, don't weaken the author-signed path.

## Compatibility

- Additive: old clients simply ignore recipient-signed post grants (their
  author-only rule rejects them; nothing breaks). New grants gossip like
  any wrap_grant, routed only to the own devices named in their wraps.
- No schema change (wrap_grant shape unchanged; only who signs + for what
  kind). No min_core bump strictly required; release notes should mention
  the multi-device readability improvement.

## Testing / parity gates

- **hearth pytest (the author-offline scenario is the point):** two nodes
  A (author/friend) + B (recipient) sync a post; A goes OFFLINE; B enrolls
  a new device key (rotate/enroll so B has an enc key A never saw); B's
  sweep mints; assert the new key can unwrap via the recipient-signed
  grant. RED tests: a grant signed by a THIRD identity in recipient
  shape → rejected; a recipient-signed grant for a NON-friend author's
  post → rejected by the consumer rule; prune invariant (superseded
  recipient grants prune safely, latest full-coverage survives); sibling
  sweeps' outputs untouched (no cross-sweep stripping — the B.2c
  carry-forward class of bug).
- **Kotlin JVM:** DecryptPassTest entitlement matrix extension — friend
  post + own-recipient-signed grant → decrypts; third-identity-signed →
  skipped; own-signed grant for a NON-friend author's post → skipped;
  author-signed path byte-unchanged (regression).
- **Loopback gate:** home node seeded with a friend-authored post it can
  decrypt but whose wraps/grants do NOT cover the phone's enc key; node
  runs the real sweep; phone (fresh enc key, author absent — only the
  home node running) syncs and RENDERS the post (and its photo via
  decrypt-on-read). Negative: without the sweep the same fixture must NOT
  render (proving the grant, not some other path, unlocked it).
- **On-device DoD (August drives):** the actual field scenario — with the
  laptop still offline, after the desktop's sweep + a phone sync, the
  laptop account's old posts render on the phone.

## Honest limits

- A post becomes multi-device-readable only once AT LEAST ONE recipient
  device that can already decrypt it comes online to mint (for August:
  the desktop, which is usually on). If every recipient device post-dates
  the content and the author is gone forever, it stays unreadable —
  inherent to end-to-end encryption, unchanged.
- Re-grants replicate READABILITY, not trust: revoked devices are excluded
  by the targets rule (enckeys of enrolled devices only), and rotation
  hygiene is preserved (grants re-mint per rotation like the siblings).
- Store growth: one grant row per friend post per rotation period, pruned
  by the existing superseded-grant prune — same growth class the sibling
  sweeps already manage.

## Out of scope

Slice B (nudge channel / event-driven sync), slice C (signed-revocation
wipe on the phone), author-side changes of any kind, profile/story/
response grant mechanics, identity-key wrapping (rejected above).
