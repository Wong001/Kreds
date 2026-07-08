# Kreds Unfriend + Signed-Notice Self-Deletion — Design

**Date:** 2026-07-06
**Status:** Approved (design discussion, this session)
**Basis:** shipped scoped-posts model, deletion-hardening slice (tombstone / delete-before-content guard), device revocation protocol. Builds on the gossip/entitlement model in `hearth/sync.py`.
**Branch:** `kreds-unfriend` off `main`
**Product context:** "Slice B" of the post-reskin work. Internal package stays `hearth`; product name is Kreds. The feature the user asked for: unfriending removes the person, removes the conversation, kills future messaging, and makes the content you shared with them self-delete on their device.

---

## Why

Today there is no way to unfriend. The user wants: removing someone takes them out of your kreds / circle / messages, ends the ability to message either way, and — the hard part in a P2P system — makes the posts/photos/DMs you shared with them **delete themselves on their device**.

The design realizes the "self-deleting content" via a **retention rule driven by an explicit signed notice**, not by clawing content back or by inferring removal from connection failures.

## Decisions locked this session

1. **Explicit signed notice is the trigger — never inference.** Content self-deletion on the recipient's side fires only on receipt of an authenticated `defriend` notice from the remover. We do NOT trigger on refusal patterns / unreachability (that would wrongly delete content and mark false "lost connections" on flaky networks). Fail-safe: if the notice never arrives, the recipient's copy is not deleted (under-delete rather than wrongly delete).
2. **Direct-only, persistent, private delivery.** The notice is delivered only to the removed person's own node, retried over a ~14-day window until acknowledged, then dropped. No mesh relay — third parties never learn of the unfriend.
3. **Recipient behavior on the notice:** purge everything the remover authored, remove them from `identities` (resurrection-safe), and show them as an inert **"no longer connected"** marker (not silently vanished, not still-active).
4. **No permanent block/ban.** There is no public lookup in Kreds, so an unfriended person can only reconnect by sharing their code again through the friend-add ceremony — a natural soft block. A separate ban mechanism is unnecessary.
5. **Honesty preserved.** An honest Kreds app deletes on receipt of the notice; a modified client or a screenshot can still keep a copy, and an unreachable device may retain content. The UI states this.

## Architecture overview

Unfriend is **not symmetric in code** but **is symmetric in effect**: each side runs the same two mechanisms independently —
- **Initiator side:** emit + durably deliver a signed `defriend` notice, and tear down the relationship locally.
- **Recipient side:** on an authenticated notice, run the retention rule (purge author's content + mark "no longer connected").

Most of "remove them / stop messaging / they can't reach me" falls out of removing the identity from `identities`, because the existing protocol already gates on `is_known`:
- `sync.py:162` refuses connections from unknown identities.
- `sync.py:240` only shares content for identities both peers know (entitlement).
- `node.compose_dm` (`node.py:476`) refuses a non-known recipient.
- `node.profile_view` (`node.py:215`) returns nothing for unknown identities.
- content ingest must drop messages authored by unknown identities (verify/enforce — see Component 4).

## Components

### 1. The `defriend` notice (new signed record)
- A signed record: the remover's device signs `{kind: defriend, author_identity, target_identity, created_at}`. Follows the existing signed-record pattern (like `RevocationCert` / `make_revocation`): a dedicated maker + a dataclass with `to_dict`/`from_dict` and a signature verified against the author's device cert.
- It is **targeted** (names `target_identity`) and delivered only to that target (Component 3). It is authenticated: the recipient verifies the signature against the author's known device key.
- Persisted by the remover in a delivery outbox until acknowledged/expired (Component 2); persisted by the recipient (like a tombstone) so the retention effect is durable and idempotent (a re-received notice is a no-op).

### 2. Initiator: `unfriend(identity)` + delivery outbox
`node.unfriend(target_identity)` does, atomically:
- Creates the signed `defriend` notice.
- **Local teardown:** removes `target` from `identities`; deletes the DM conversation with them, their cached posts, their profile, their ring records, their enckeys, and their peer address from the local store. They immediately disappear from the initiator's kreds/circle/messages; the initiator can no longer compose to them; their node is refused on connect.
- Writes a **`defriend_outbox`** record: `{target_identity, address, notice, created_at, expires_at (created_at + 14d), delivered}`.

A background delivery task (runs on the existing gossip cadence, or a dedicated pass):
- For each undelivered, unexpired outbox record, connects **directly** to `target`'s address (the initiator connecting *out* is allowed — the target still knows the initiator, so the target authenticates and accepts the connection), performs the authenticated handshake, and delivers the notice.
- Delivery is a request→ack round **within one session**: A connects, sends the notice frame, and the target verifies + persists the notice and returns an ack **before** it applies its own purge/teardown (Component 3). A marks `delivered=true` on that ack and drops the record. Ordering the ack before the target's self-removal avoids the trap where the target, having removed A from `identities`, would refuse A's retry.
- Cleanup on retry/expiry: if a later delivery attempt for a still-pending record is **refused** by the target (the target already processed the notice and no longer knows A), A treats the record as delivered and drops it — this is cleanup only, never a deletion trigger. If `expires_at` passes with neither an ack nor such a refusal, A drops the record (gives up — safe under-delete).
- The delivery connection is **notice-only**: it does not send the initiator's other content and does not accept the target's content (the target is no longer a known identity; ingest drops their content).

Note on ordering: the initiator removes the target from `identities` immediately, so the outbox is a standalone delivery obligation keyed by stored `address` + `notice` — it does not depend on the target being a known identity.

### 3. Recipient: retention rule (dormant protocol)
On receiving a `defriend` notice, the recipient:
- Verifies the signature against the author's known device cert **and** that `target_identity` is the recipient's own identity. An invalid or non-self-targeted notice is ignored.
- If valid and the author is currently known: runs the purge —
  - Delete all content authored by the author: their posts, their photos/blobs (blobs referenced only by the author's deleted content), their DMs, their profile, their ring records.
  - Remove the author from `identities` (this is what makes it **resurrection-safe**: a mutual friend relaying the author's content over the mesh is rejected because the author is no longer a known identity; mirrors the delete-before-content guard's intent).
  - Add a **display-only** `disconnected` marker row (`identity_pub`, `name`) so the UI can show the author as "no longer connected." This marker carries no content and does not make the author a known identity — it is purely cosmetic.
- Persist the notice (idempotent): a re-received notice for an already-removed author is a no-op.
- The recipient stops attempting to sync with the author (nothing to sync; they're not a known identity).

### 4. Enforcement / verification (mostly existing)
- **Verify content ingest drops unknown-author messages.** After teardown/purge the author is not a known identity; confirm (and enforce if missing) that the message-ingest path rejects/does not store content whose author is not in `identities`, so purged content cannot be re-added over the mesh.
- `compose_dm`, `profile_view`, gossip entitlement, and connection refusal already gate on `is_known` — confirmed present; add tests.

### 5. Re-friending
- Adding a removed person again (the normal friend-add ceremony → `add_identity`) clears their `disconnected` marker. From then on it is a fresh friendship; previously-purged content is not restored (deletion is one-way).

### 6. UI
- **Initiator:** an **Unfriend** action on the person's profile page (beside Message / Move-between-rings), behind a confirm dialog carrying the honest copy (below). After confirming, the person is gone from the initiator's kreds/circle/messages.
- **Recipient:** the person renders as **"no longer connected"** in the kreds list / on their profile page — Message disabled, an inert label — until the recipient removes or re-adds them.
- **Honest copy (confirm dialog):** "Remove {name} from your kreds? They leave your circle and messages, and you both stop exchanging. Their Kreds app is sent a signed removal notice and deletes what you shared as soon as it receives it — we keep trying privately for up to 14 days. An honest app deletes on receipt, but a modified client or a screenshot can still keep a copy, and if their device is never reachable their copy may remain. You can re-add them later if they share their code again."

## Testing

Two-node (initiator A + recipient R) integration, plus a third mutual node C for the resurrection test:
- **Happy path:** A `unfriend(R)` → A's local teardown correct (R gone from A's friends/circle/messages; A's copy of R's content + conversation deleted; outbox record written). Deliver the notice → R purges everything A authored, removes A from `identities`, adds the `disconnected` marker, persists the notice → A's outbox marks delivered and stops.
- **Resurrection guard:** C is friends with both and holds A's post. After R purges, C must NOT be able to re-deliver A's content to R (R no longer knows A → ingest rejects). Assert the purged content stays gone.
- **No false trigger:** a mere failed/refused connection or A going offline does NOT purge anything on R (we never trigger on refusal). Only the signed notice triggers.
- **Notice authenticity:** a `defriend` notice with a bad signature, or one not targeting R, is ignored.
- **Undelivered/offline:** R offline for the window → A retries, no purge occurs (nothing delivered); at `expires_at` A drops the outbox record; no crash, no wrong deletion.
- **Idempotent:** re-delivering the notice after R already removed A is a no-op.
- **Symmetry:** R `unfriend(A)` independently deletes R's content on A's side by the same path.
- **Gates:** after removal, `compose_dm(removed)` raises, `profile_view(removed)` returns None, gossip refuses the removed identity.
- **Re-friend:** re-adding clears the `disconnected` marker.
- **UI asset test:** the profile page has an Unfriend affordance; the honest copy string is present; the recipient "no longer connected" state exists; no receipts popover reintroduced.

ASCII-only Python console prints (cp1252). All existing tests stay green plus the new ones. Verify the full suite under an explicit timeout (false-green history).

## Out of scope (named)

- Permanent block/ban beyond re-add (no public lookup makes it unnecessary — Decision 4).
- Any broadcast or third-party-visible signal of the unfriend (delivery is direct-only, private).
- Mesh-relayed notice delivery.
- Changes to deletion of your own content beyond the local teardown.
- Retroactive deletion of content shared before this feature ships is still covered (the retention rule keys on authorship, not on a per-message flag), but re-syncing history from before the identity model existed is not in scope.

## Success criteria

- `unfriend(identity)` removes the person locally (kreds/circle/messages/compose all reflect it) and emits a signed notice that is durably, privately delivered to their node.
- On receiving the authenticated notice, the removed-from person's honest node deletes everything the remover authored, cannot resurrect it via the mesh, and shows the remover as "no longer connected."
- No deletion or "disconnected" state ever fires from connection failure/refusal — only from a valid signed notice.
- Honesty copy is shown and accurate; re-adding is possible via the ceremony.
- All existing tests green plus the new integration/enforcement/UI tests.
