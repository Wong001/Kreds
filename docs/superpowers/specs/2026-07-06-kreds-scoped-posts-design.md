# Kreds Scoped Encrypted Posts — Design ("Inner / Kreds rings")

**Date:** 2026-07-06
**Status:** Approved (design discussion, this session)
**Basis:** Loop→Kreds redesign brief + `kreds_design_v3.html` (this session); shipped DM per-recipient encryption (`hearth/dmcrypt.py`, spec `2026-07-02-hearth-dm-design.md`) and windowed forward secrecy (spec `2026-07-03-hearth-dm-forward-secrecy-design.md`); ROADMAP open decision "Entitlement ≠ confidentiality — per-recipient encryption is the real boundary."
**Branch:** `kreds-scoped-posts` off `main`
**Product context:** First build slice of the Kreds redesign. Internal package stays `hearth`; product name is Kreds.

---

## Why this exists

Today posts are `KIND_POST` signed **plaintext**, gossiped to every entitled friend (fan-out control, not confidentiality — the ROADMAP's stated open boundary). The Kreds redesign's honesty thesis ("scope is physics, not policy" — receipts show which devices actually hold a post) is only true if posts are **encrypted to a named audience**. This slice brings the DM per-recipient encryption model to posts and introduces the ring (scope) membership that defines each audience.

## Decisions locked this session

1. **Two scopes now: Inner and Kreds.** Inner = a hand-picked subset of your friends; Kreds = all your friends. The model is built as **N named audiences** so a future distinct **Open** scope (public/pseudonymous — deferred, has unresolved content-control questions) slots in later as a new scope *type* with no retrofit.
2. **Every post is scoped + encrypted.** Plaintext `KIND_POST` is retired. Pre-release; demo casts are disposable (`run/` deleted for a fresh cast).
3. **Rejected approaches:** per-ring group key (rotation/removal complexity; a removed member keeps the old key; worse demotion honesty) and broadcast-encrypt-to-all (non-members hold undecryptable ciphertext — breaks the "never receive it" receipts claim).
4. **Profiles and stories stay whole-kreds signed** (not part of this slice). Read-state is decided separately (synced across own devices) as its own later slice.

## Approach: generalize the DM envelope to a recipient set

Factor the reviewed DM seal/wrap crypto into a shared "sealed envelope to N devices" primitive; a scoped post is a DM envelope addressed to many identities instead of one.

### Ring membership (author-private, own-device-synced)

- Each friend carries a ring level in the author's own classification: `inner` or `kreds` (default `kreds`). "Inner kreds" = friends marked `inner`; the **Kreds scope** targets all friends; the **Inner scope** targets `inner` friends only.
- Membership is the **author's private data**, via a new signed record kind **`ring`**: payload `{kind:"ring", member: identity_pub, ring: "inner"|"kreds", created_at}`. Latest-wins per member. A friend defaults to `kreds` until an `inner` record names them.
- **`ring` records route own-device-only** — `messages_not_in` offers a `kind="ring"` row only to a peer whose identity equals the author's (the same restriction the DM rule applies, keyed on author-identity rather than a `to` field). Inner-circle membership is more sensitive than the already-disclosed friend list and must **never** reach a friend's node. Because only your own devices ever hold these records, they are signed-plaintext (no encryption needed — no other identity receives them).
- Recipients never learn your ring structure as a whole — but note an honest disclosure: because `wraps` is part of the post payload, a recipient **can enumerate the device-set a given post was encrypted to** (its audience for that post). For a Kreds post that is your friend graph (already disclosed today via the have-frame `known` set). For an Inner post it reveals **co-membership of that inner audience** to its members (the people in an inner post can see which other devices it went to). This is stated plainly, not hidden; blinding the per-device wrap identifiers is a candidate follow-up, the same class as the already-logged "hash-based have-exchange." Non-members still receive nothing at all (routing), so the disclosure is only ever to people already in the audience.

### The post envelope

A scoped post is a `SignedMessage`, `kind="post"`, payload mirroring a DM:
- `scope`: `"inner"` | `"kreds"`
- `body_nonce`, `body_ct`: canonical-JSON body `{text, blobs:[hash…]}` sealed once with a random content key (ChaCha20-Poly1305)
- `wraps`: `{device_pub: {eph_pub, nonce, wrapped_key}}` — the content key wrapped (X25519→HKDF→ChaCha20-Poly1305) to every non-revoked device of every scope member **and** the author's own devices
- `blobs`: attachment hashes (encrypted blobs, as DM photos are), also in cleartext payload for GC/sync visibility exactly as DMs do
- `created_at`, optional `expires_at`

AAD binds author identity + `scope` + `created_at` (prevents cross-context ciphertext transplant). Crypto reuses/refactors `hearth/dmcrypt.py`; the wrap/seal primitive is shared, not duplicated.

### Self-describing routing (the key insight)

**The `wraps` set IS the audience.** A node offers a scoped post to a peer iff the peer's identity owns a device present in `wraps` (or the peer is the author). Consequences:
- Non-members' devices **never receive** the post — makes the receipts claim true, not aspirational; generalizes the DM routing rule (`messages_not_in` currently restricts DMs to `{author, to}`) to "restrict to identities present in `wraps`".
- **No relaying node needs the author's private ring membership** — the envelope carries its own audience. Any holder (author, member, member's home node) offers it only to identities in the wrap-set.
- Home-node mailbox store-and-forward works exactly as for DMs (a member's home node is one of the wrapped devices).

### Ring moves = future-only (two-tier honesty)

Moving a friend between rings changes only which devices **future** posts wrap to. Posts they already hold stay held (cannot be unsent). This is the shipped two-tier deletion honesty applied to demotion, and it is automatic because each post wraps to membership **at compose time**. UI copy must state it (deferred to the reskin; the invariant is here).

### Forward secrecy — inherited for free

Posts wrap to the same per-device X25519 enc-keys DMs use, so scoped posts automatically inherit the shipped **windowed forward secrecy** (daily enc-key rotation + 7-day grace). Posts therefore need the same **local content-key cache** so authored/received history survives enc-key rotation: the shipped `dm_keys` table + `seal_content_key`/`open_content_key` generalize from "DM content keys" to "message content keys" (rename/broaden, one cache for both kinds). Tombstone hygiene (delete/expiry/retro-drop drop the cached key) applies unchanged.

## The profile consequence (design invariant, recorded)

Because all posts are now encrypted to a scope, **there is no universal public post wall.** The single guarantee this slice delivers, at the data layer:

> The journal feed and any post-listing view render **only posts this device can decrypt** — an undecryptable post never leaks into any view.

- The **profile card** (name, bio, avatar, banner, ring status) stays whole-kreds signed and visible to all your friends — unchanged.
- **The profile is being reconceived as a curated space, separate from the feed** (decided this session): a deliberately arranged wall — photos, visual setup — that you *place* there, distinct in content type from chronological feed posts, with its own visibility model. This is its **own upcoming slice** ("curated profile / block-based profile," sharpening the ROADMAP's parked customizable-profile item). This spec therefore does **not** define the profile as a scope-filtered mirror of the feed; it commits only to the data-layer invariant above. The profile's content model and display are that slice's decisions.
- If any feed posts are surfaced on a profile before the curated-profile slice ships, they obey the invariant (only what the viewer can decrypt, with an honest empty state).

## Storage / API / model changes (this slice)

- **Store:** posts stored as encrypted rows (as DMs are); a `ring` records table / derivation (latest-wins per member); feed/profile-posts queries return only rows this device can decrypt (via the generalized message-key path); scoped routing in `messages_not_in` extended to honor the wrap-set for `kind="post"`.
- **Message-key cache:** generalize the DM `dm_keys` cache to cover posts (shared table + helpers).
- **Node/API:** `compose_post(text, scope, photos=…)` (scope required); `feed()`/profile-posts decrypt-and-filter; `set_ring(member_identity, ring)` to move a friend between rings; enckey directory reused to find recipients' devices.
- **UI (minimal this slice):** wire compose to a scope choice and show a scope label; full v3 visual treatment (keeps selector, scope pill, receipts, move-between-rings on the profile modal) is the reskin slice.

## Out of scope (named)

- v3 visual reskin (next slice).
- Receipts / delivery-acks (needs this + ack propagation).
- Read-state (decided: synced across own devices; own slice).
- Public/pseudonymous **Open** scope (deferred; content-control questions unresolved).
- Profiles + stories encryption (stay whole-kreds signed).
- Migration of old plaintext posts (none — casts are disposable pre-release).

## Testing

Unit:
- Seal-to-N-devices round-trip; a non-member device cannot open the envelope; author's own devices can always open.
- `ring` records: latest-wins per member; default `kreds`; Inner scope resolves to `inner`-marked members only.
- Demotion: after moving a friend out of `inner`, a new inner post's `wraps` excludes their devices, but a pre-move inner post they hold still opens.
- Feed/profile queries never return a row this device can't decrypt.

Integration (real sockets, existing conventions):
- **Structural assertion:** an inner post reaches inner members' devices (including a member's home node via mailbox) and a non-inner friend's node **never receives the row at all** (mirrors the DM mutual-observer test).
- A kreds post reaches every friend's devices; a non-friend never does.
- Ring move re-keys future only: friend demoted between two inner posts holds the first, never receives the second.

All existing tests stay green (except deliberate changes to the retired-plaintext-post path, which are updated to the scoped model).

## Success criteria

- Posting to Inner encrypts to exactly the inner members + own devices; a non-inner friend's node never holds the row (proven by a real-socket test).
- Feed and profiles render only decryptable posts; no undecryptable ciphertext leaks into any view.
- Ring demotion affects future posts only, proven by test.
- Scoped posts inherit windowed FS and survive enc-key rotation via the shared message-key cache.
