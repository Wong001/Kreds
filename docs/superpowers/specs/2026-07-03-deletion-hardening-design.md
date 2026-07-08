# Deletion Hardening + Honesty Pass — Design

**Date:** 2026-07-03
**Status:** Approved (design discussion, this session)
**Basis:** ROADMAP.md (Honest status / Open design decisions), `hearth_concept_capture_v0_4.md` (D3), vertical-slice store/sync as merged on `main`
**Branch:** `deletion-hardening` off `main`

---

## Session context: workstream ordering (decided 2026-07-03)

Four security/infra workstreams were scoped and ordered this session, each its own
spec -> plan -> branch -> review -> merge cycle, one at a time:

1. **Deletion hardening + honesty pass** (this spec) — small, contained.
2. **Forward secrecy for DMs** — ratchet on the hearth-dm v0.1 envelope; own spec.
3. **Tor transport** — SOCKS dialer + onion services + Windows bundling; own spec.
4. **Unfriend / relationship-level deletion** — own spec; deliberately last, may
   slip past the 2026-07-07 window. Chosen during the deletion scoping chat but
   recognized as feature-sized, not a hardening item.

Deletion acknowledgments ("deleted on 3 of 4 devices") were considered and
**declined** — protocol surface that risks implying stronger guarantees than
exist, in tension with the D3 marketing rule.

## The problem

D3 (concept doc) promises: deletion is structural against strangers,
protocol-enforced among friends running compliant clients. The code does not
fully keep the protocol-enforced half, and the UI says nothing honest about it.

**Bug — deleted content resurrects under out-of-order gossip.** Gossip delivery
order is not guaranteed (same class as the D2 spike's Ambush 2). When a
`kind="delete"` tag arrives **before** its target message, the tombstone-on-
arrival guard in `Store.ingest_message` runs only for `kind="post"`
(`store.py`, the `elif kind == KIND_POST` branch). A DM, story, or profile
message arriving after its own delete tag is ingested normally and displayed —
the deletion is silently lost on that node, permanently, even though every
participant is honest and compliant. Delete-for-both on DMs (hearth-dm v0.1)
and story deletion are both exposed to this.

**Gap — no honest signal at the point of deletion.** The only deletion
affordance in the UI is a bare "delete everywhere" button (two call sites in
`hearth/web/app.js`: feed and profile view). "Everywhere" overclaims: a
modified client or a screenshot can retain content. The approved D3
formulation exists in the concept doc and ROADMAP but nowhere a user sees it.

## Design

### 1. Correctness: generalize the delete-before-content guard

In `Store.ingest_message`, move the existing on-arrival check — "does a stored
`kind="delete"` message from this same identity have `target_id` equal to this
incoming msg_id?" — out of the `elif kind == KIND_POST` branch so it runs for
**every incoming non-delete kind** (post, dm, story, profile, enckey). On a
hit: tombstone the incoming msg_id (reason "deleted"), commit, `gc_blobs()`,
and return the existing "deleted on arrival" IngestResult shape, exactly as
the post path does today.

The query keeps the `identity_pub` match, which preserves the authorization
invariant (only the content's author can delete it) with **no trust placed in
the delete tag alone**.

**Rejected alternative — pre-tombstone on delete-tag arrival.** When a delete
tag arrives with its target absent, one could tombstone the target msg_id
immediately and let the generic `is_tombstoned` ingest check block the content
later. Rejected: with no stored target row there is nothing to authorize
against, so any friend who has *seen* your message (and thus knows its msg_id)
could emit a delete tag for it and censor it on nodes that have not yet
received it. The generalized on-arrival check defers judgment until the
content (and its author) is in hand.

### 2. Index the guard's query path

The on-arrival check now runs a `(kind, target_id, identity_pub)` lookup on
every ingested message. Add the SQL index for it (already on the ROADMAP
tech-debt backlog as "SQL indexes on message scans"). Only the index this
query needs — the rest of the backlog item stays in the backlog.

### 3. Honesty: UI copy + docs

- **UI:** replace the bare delete onclick with a shared confirm helper used by
  all deletion affordances (currently the two "delete everywhere" buttons).
  Confirm text (this is the copy, verbatim):
  *"Delete for everyone? This removes it from your friends' devices running
  Loop. A modified app or a screenshot can still have kept a copy."*
  Button label stays "delete everywhere" unchanged — the confirm carries the
  boundary. DMs and stories have no delete UI today; when they get one, it
  must use the same helper (noted in code comment).
- **README:** add a **Deletion** section quoting the approved D3 formulation
  verbatim: "Deletion is structural against strangers — they never had it —
  and automatic among friends running compliant clients." Plus one line on the
  mechanism (delete tags + tombstones + blob GC) and one on the boundary
  (compliant-client behavior, not DRM).
- **ROADMAP:** update the "Deletion is protocol-enforced among honest friends"
  honest-status bullet to note the out-of-order race is fixed and tested.

## Testing

Unit (store):
- For each of dm / story / profile: ingest delete tag first, then the target
  message -> target is tombstoned on arrival, not stored, not readable; its
  blobs are GC'd. Existing post case stays green.
- Authorization race: delete tag authored by identity B targeting a msg_id of
  identity A's not-yet-arrived message -> A's message ingests normally and
  survives; B's tag does not tombstone it.
- Idempotence: content arriving after both delete tag AND its own earlier
  tombstone (duplicate delivery) is still refused.

Integration (real sockets, per existing suite):
- Node offline during send+delete of a DM and a story receives both in
  adverse order across gossip rounds and converges to deleted on all nodes.

All existing tests stay green.

## Out of scope (stated)

- Deletion acknowledgments (declined, see above).
- Unfriend / relationship-level deletion (workstream 4, own spec).
- Ingest rollback-on-exception and the rest of the robustness/index backlog.
- Adding delete UI for DMs/stories (only the helper contract is laid down).
- Any transport or crypto change.

## Success criteria

- The adverse-order integration test fails on `main` today and passes on the
  branch (proves the bug and the fix).
- A user clicking delete sees the honest boundary before confirming.
- README/ROADMAP state deletion semantics using the D3 formulation.
