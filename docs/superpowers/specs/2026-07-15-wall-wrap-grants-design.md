# Wall wrap-grants — "a wall is a wall" — design

Date: 2026-07-15
Status: approved (August, 2026-07-15). PROTOCOL CHANGE (new record kind).
Slice: 3 of 3 in the 0.3.11 fixes bundle (biggest; built last)

## Problem

A new friend cannot see wall posts made before the friendship (Josh case,
2026-07-15). Investigation confirmed this is the shipped design working
as specced: posts wrap their content key to ring members AT POST TIME
(`node.py:470-487`), the wrap set IS the sync audience
(`store.py:503-506` refuses to even offer the row to non-wrapped peers),
and "ring moves re-key future only" is documented and test-pinned. The
"invisible blocks" Josh sees are pure geometry: the plaintext layout
record syncs to everyone, so pins for posts he can't hold leave exact
ghost holes that move on rearrange (accepted-disclosure per the collage
spec; not a ciphertext leak).

Secondary transient with the same mechanism: a post composed after
friend-add but before the new friend's enckey record syncs skips them
silently and permanently (`node.py:472-473` best-effort).

## Decision (August, 2026-07-15)

"A wall is a wall": **kreds-scope PROFILE (wall) posts become visible to
current friends, not friends-at-post-time.** Specifically:

- Grants cover the author's own posts with `placement == "profile"` and
  `scope == "kreds"` only.
- **Journal posts are untouched**: they keep expiry timers and
  at-post-time inner/kreds audience (a journal is a moment in time).
- **Inner-scope posts stay future-only** everywhere, including inner wall
  posts (they remain honest holes for non-inner viewers). The shipped
  "ring moves reveal only future posts" rule and its UI copy survive for
  inner.
- Honest tradeoff on record: a grant cannot be un-sent (beyond the
  existing unfriend purge protocol); once a friend holds ciphertext +
  key, that's permanent, same as any delivered post.

## Design

### 1. New record kind: `KIND_WRAP_GRANT`

Author-signed message: `{kind, target: <post msg_id>, wraps: {device_pub:
sealed_key, ...}, created_at}`. Grants are additive and deduplicable
(multiple grants for one target union). Mutable-record precedent: album
records (latest-wins style); grants are simpler — append-only union.

### 2. Maintenance sweep (author side)

Same shape/hooks as `maintain_enckey` (called from `api.py:72` and
`sync.py:215`): for each current friend, for each own kreds-scope
profile post (non-expired, non-tombstoned): if the friend's current
non-revoked enckey devices are not covered by payload wraps ∪ existing
grants → recover the content key (author always can: sealed-key cache
`_cache_message_key`/`dm_keys`, else unwrap own payload wrap) → mint a
grant wrapping to the missing devices. Multi-device authors: any device
may mint; races produce redundant grants, which union harmlessly. The
sweep also heals the enckey-not-yet-synced transient (a recent post
missing a current friend gets granted on the next sweep).

### 3. Sync + decrypt integration (the load-bearing seams)

- **Routing gate** `store.py:503-506`: a post is offered to a peer if the
  peer's devices appear in payload wraps **∪ grant wraps** for that
  target. Grant records themselves route to the devices they name ∪ the
  author's devices.
- **Key resolution** `_content_key` (`node.py:1588-1613`): unwrap from
  payload wraps, else from any held grant targeting the message.
- **Negative cache un-poisoning** (subtle, from the investigation):
  `uncached_message_ids` (`store.py:656-661`) must union grant wraps, and
  grant ingest must `clear_undecryptable(target)` (`store.py:627-634`) —
  otherwise a post row arriving before its grant is permanently
  negative-cached on the recipient.
- **Blob follow-through**: none needed — once the row lands,
  `missing_blobs` drives blob sync and blob serving is not per-post gated
  (`sync.py:572-590`).
- **Deletes/expiry**: tombstoned targets never get grants; delete
  tombstones suppress/GC grants for their target (mirror
  `store.py:588-589`'s refusal); expired posts are skipped by the sweep.
- **Enckey rotation**: grants wrap to current enc_pubs; the sweep re-mints
  against the latest enckey if a grant's wraps went stale (unlike DMs,
  retroactive re-mint is possible — the author still has the key).

### 4. Ghost holes

No compaction shipped. Grants make kreds blocks real for friends; inner
wall blocks legitimately remain holes for non-inner viewers (existing
accepted disclosure, unchanged). The proper closure (per-scope layout
records) stays on the deferred list from the collage spec.

### 5. Mixed-version / protocol compatibility — MUST VERIFY IN PLANNING

- How does a ≤0.3.11-minus-one node ingest an unknown kind? (Verify
  `store.ingest_message` tolerance for unknown kinds BEFORE build; if
  unknown kinds are refused or crash, gate grant gossip on peer version
  or accept partial rollout consequences explicitly.)
- Old peers ignore grants → they still refuse to OFFER old posts to the
  new friend (their routing gate knows only payload wraps). The new
  friend still receives the posts from the AUTHOR's own (updated) node —
  acceptable: the author is the source of truth for their wall; relayed
  availability from mutual friends improves as they update. Document in
  ROADMAP + release notes.
- UI copy: composer note (`app.js:1889`) and any "future posts only"
  copy must be re-worded for the kreds-wall case (August words the final
  user-facing copy).

### 6. Tests to update (behavior change is intended)

`test_node_scoped_posts.py:46` (`test_ring_move_rekeys_future_only`) —
splits into inner (unchanged, still future-only) and kreds-wall
(new: back catalog opens). `test_scoped_posts_e2e.py:70`,
`test_node_scoped_posts.py:58` (feed-hides-undecryptable stays true for
non-granted cases). New end-to-end: two real nodes, wall posts, THEN
friend-add → sweep → posts and blobs arrive and decrypt; journal posts
from before friendship stay invisible; inner wall post stays invisible;
delete-then-sweep mints no grant; row-before-grant ordering decrypts
after grant arrival (negative-cache regression).

## Out of scope

- Re-keying inner scope on ring joins (explicitly declined).
- Per-scope layout records (deferred; ghost-hole closure for inner).
- Unfriend retro-revocation of grants (impossible; two-tier honesty
  stands).
