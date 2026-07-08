# Three protocol warts — hardening design

Date: 2026-07-09
Status: approved (August, in-session; design discussed and accepted incl.
the tombstone-as-supersession mechanic)

Pre-real-users hardening of three long-tracked backlog items. No wire
format change — all three are ingest/lifecycle policy; `hearth/v0.2`
stands. Code facts below verified against the tree on 2026-07-09
(Explore-agent map; file:line refs are from that pass).

## Wart 1 — delete tags can delete delete tags (divergence)

**Today:** nothing prevents a `KIND_DELETE` targeting another delete row
at creation (`node.delete_post`, node.py:686), validation
(messages.py:223 — format-only), or ingest (store.py:271 — the
authorization lookup has no kind filter). A meta-delete tombstones the
target tag, halting that tag's propagation: nodes that already applied
the tag have the content gone; nodes that never saw it keep the content
forever. Permanent, unrepairable divergence.

**Rule: delete tags are immune to deletion.** There is no legitimate use:
tombstones are permanent (no undelete), so deleting a delete tag can only
censor its propagation.

1. **Creation** (`node.delete_post`): if the target row is held and its
   `kind == KIND_DELETE`, raise `ValueError("cannot delete a delete
   tag")` (surfaces as 400 via the existing `/api/delete` error mapping).
2. **Ingest, target held** (store.py ~271-280): before the author check,
   if the target row's kind is `delete`, return
   `IngestResult(False, "delete tag cannot target a delete tag", mid)`.
3. **Ingest, target arrives later** (store.py ~281-294): the
   delete-on-arrival guard (`idx_delete_guard` lookup) applies ONLY when
   the arriving message's own kind is not `KIND_DELETE` — an arriving
   delete tag is never tombstoned-on-arrival by a lurking meta-delete.
4. **Hygiene:** where a meta-delete row is already held and its target is
   later determinable as a delete row (case 3 fires), tombstone the
   held meta-delete with reason `invalid` (tombstone, not row-DELETE —
   see Wart 2's re-fetch rationale). Pre-fix divergence is not repaired
   (impossible); no released users ever exercised this path.

## Wart 2 — superseded enckey rows accumulate forever

**Today:** daily rotation (`maintain_enckey`, node.py:1086) publishes one
`KIND_ENCKEY` message per device per day; nothing ever removes
superseded rows (verified: no `DELETE FROM messages` for enckeys; the
expiry sweep never touches them — enckeys carry no `expires_at`). A year
of use = ~365 rows/device, replicated into every friend's store and
offered on every have-exchange.

**Rule: keep exactly the latest enckey per `(identity_pub, device_pub)`
— by the existing `(created_at, seq)` tie-break — and tombstone the
rest, reason `superseded`.**

- **Tombstone, never bare-DELETE:** a bare row deletion makes the next
  summary diff treat the row as missing, and peers re-send it forever.
  Tombstoning reuses the existing anti-resurrection machinery
  (`is_tombstoned` rejects re-ingest); each node stops holding AND stops
  offering the row, so superseded enckeys evaporate network-wide as
  nodes prune independently. This deliberately widens the tombstones
  table's meaning from "user deleted" to "lifecycle-retired" (reasons:
  `deleted` | `expired` (existing sweep) | `superseded` | `invalid`) —
  accepted by August 2026-07-09.
- **Safety:** nothing reads superseded enckey rows. Senders wrap to the
  latest they hold (`store.enckeys` already resolves latest-wins);
  recipients decrypt via retired PRIVATE keys held client-side
  (`identity.py` retired_enc, 7-day grace) which this never touches.
  Forward secrecy is unaffected (it lives in `prune_retired`, not in
  public announcement rows).
- **Where:** new `Store.prune_superseded_enckeys()` run from the gossip
  round beside `sweep_expired` (sync.py ~205). Revoked devices'
  enckeys: already excluded from `enckey_records`; prune treats them
  like any other device (keep-latest, drop rest) — no special case.

## Wart 3 — permanently-undecryptable DMs retried every gossip round

**Today:** `cache_message_keys()` (node.py:1180, called every gossip
round from sync.py:207) iterates `uncached_message_ids` and re-attempts
envelope unwraps; a permanently-undecryptable message (wrapped only to
keys pruned past grace, or to a different device's keys) stays in that
set forever — unbounded per-round crypto busywork that grows with
history.

**Rule: a local negative cache.** New table
`undecryptable(msg_id TEXT PRIMARY KEY, since REAL NOT NULL)`:

- During the `cache_message_keys` sweep ONLY: when `_content_key`
  returns no key for an uncached message, record it; the
  `uncached_message_ids` query excludes recorded ids.
- **Never record while locked** (a locked node fails to decrypt
  everything — recording then would mass-poison the cache): the sweep
  guards on `self.locked` and additionally on key material being
  present, mirroring `maintain_enckey`'s guard.
- **Clear the whole table on `unlock()`** (belt-and-braces — unlock
  restores key material that may make entries decryptable).
- **Remove an entry** whenever a content key for that msg_id is cached
  (`cache_message_key` / `_replace_message_key` paths) and when the row
  is tombstoned (`_tombstone` already clears `dm_keys`; clear
  `undecryptable` alongside).
- Local-only: never synced, never in have-exchange, no protocol change.
- The per-view decryption in `dm_thread`/`conversations` is untouched —
  views stay correct-first; only the background sweep consults the
  cache.

## Out of scope (recorded for later sessions)

- UI distinction of "locked/temporary" vs "permanently gone"
  undecryptables (ties into the single-device backup-story session).
- Repairing pre-fix tag-on-tag divergence.
- Any enckey protocol change (epoch keys etc.).

## Testing

Per-wart unit tests in the existing suites (test_store_ingest.py,
test_store_dm.py / a new test_enckey_prune.py, test_node_dm.py) plus:

- Tag-on-tag: creation refusal; ingest refusal (target held); arriving
  delete immune to a lurking meta-delete (the divergence scenario:
  A deletes post, meta-delete lurks at C before the real tag arrives —
  post ends deleted at every node); hygiene tombstone of the meta-delete.
- Enckey prune: rotation accumulates → prune keeps exactly latest per
  device (tie-break pinned), tombstones rest; a pruned row offered by a
  stale peer is refused re-ingest; `enckeys()` resolution unchanged
  before/after prune; two-node sync round after prune carries content
  both ways (regression: the friend-add first-frame-peek precedent).
- Negative cache: undecryptable recorded once, skipped next sweep;
  locked node records nothing; unlock clears; caching a key removes the
  entry; tombstone removes the entry.
- Full-suite green; `node --check` on client JS untouched (no JS in this
  slice).

## Rollout

Ships as core-only 0.3.7 material (no web change) — bundled with
whatever else lands before the next publish; sign/publish held for
August as usual.
