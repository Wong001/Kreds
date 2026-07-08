# Hearth Encrypted Messaging Slice — Design ("hearth-dm v0.1")

**Date:** 2026-07-02
**Status:** Approved (design discussion, this session; revocation self-logout added per user requirement)
**Basis:** Vertical slice v0.1 (branch `hearth-vertical-slice`, head 2d26e15) + `hearth_concept_capture_v0_3.md` (D1/D2/D3)
**Window note:** Free Fable 5 access ends 2026-07-07; messaging chosen as the protocol-deep work to land inside it.

---

## Scope decisions (locked this session)

1. **1:1 DMs only.** No groups, read receipts, or typing indicators.
2. **Routing: direct only + home-node mailbox.** A DM syncs ONLY between the two identities' own devices. Friends never relay DMs — not even ciphertext. Offline delivery via the recipient's home node (a first-class device, per D2 — the Briar Mailbox pattern). Stated cost: if ALL recipient devices are offline, the DM waits on the sender's devices.
3. **Crypto: per-device static X25519, no forward secrecy in v0.1** — stated plainly, ratchet is the named follow-up.
4. **Photos in DMs: encrypted blobs** — attachment bytes encrypted with the DM content key before entering the blob store; transit and third-party stores only ever hold ciphertext.
5. **D3 applies to DMs unchanged** — delete tags (delete-for-both on compliant clients) and expiry.
6. **Revocation self-logout (new requirement, this design):** a compliant device that learns of its own revocation wipes its identity-key replica and store, drops to unenrolled state, and shows "This device has been revoked." Honest limit, marketed per the D3 discipline: a stolen/modified client cannot be forced to wipe; the structural protection is that everything it signs post-revocation is rejected network-wide and it receives/decrypts nothing new.

## Success criterion

On the demo cast (which gains `freja-homenode`, ports 7104/7204, so the mailbox story is demonstrable on both sides): Wong DMs Freja (text + photo) from his phone while freja-phone is STOPPED; the DM lands on freja-homenode; Freja's phone comes back, picks the DM up from her home node, decrypts, displays. A mutual observer node proves it never received the DM. Delete-for-both removes it from all four stores. Revoking wong-phone locks that node out on next gossip (self-logout) and Freja's client rejects anything it signs afterward. All existing 69 tests stay green.

---

## Keys: separate per-device encryption keys

Signing (Ed25519) and encryption (X25519) keys are separate — good practice, and the `cryptography` package has no Ed25519→X25519 conversion (avoiding a PyNaCl dependency).

- `DeviceKeys` gains an X25519 keypair, generated at create/pair time, persisted in `keys.json`.
- New signed message kind **`enckey`**: payload `{kind, enc_pub, created_at}`, an ordinary device-signed `SignedMessage`. Latest-wins per device. Gossips to friends via existing sync (friends need your device enc keys to DM you). Published automatically at node startup if absent/stale.
- No changes to `EnrollmentCert` — zero protocol surgery on reviewed code.

## The DM envelope

A DM is a normal `SignedMessage` with `kind="dm"` (same seq/verifier/tombstone machinery). Payload:

- `to`: recipient identity pub (hex)
- `body_nonce`, `body_ct`: canonical-JSON body `{text, blobs: [hash...], }` encrypted once with a random 32-byte content key, ChaCha20-Poly1305
- `wraps`: `{device_pub: {eph_pub, nonce, wrapped_key}}` — content key wrapped for every **non-revoked** device of recipient AND sender (sender's other devices must read sent history), via X25519(eph, device_enc_pub) → HKDF-SHA256 → ChaCha20-Poly1305
- `created_at`, optional `expires_at`

AAD for both encryptions binds sender identity, recipient identity, and `created_at` (prevents cross-context ciphertext transplant). Crypto isolated in a new `hearth/dmcrypt.py`, unit-tested against tamper/wrong-device/forged-wrap cases.

**Known property (stated):** a device enrolled AFTER a DM was sent cannot read that DM (no history re-wrap) — inherent to E2E, matches Signal/WhatsApp.

## Routing: strict two-identity entitlement

One new sync rule in `messages_not_in`: a `kind="dm"` row is offered only when the authenticated peer identity ∈ {author identity, `to` identity}. Everything else (auth, revocations-first, have/want, blob transfer) is unchanged. DM attachment hashes travel in the cleartext DM payload (so blob GC and the existing sync BLOBS phase see them with no special-casing) AND inside the encrypted body (for display). This exposes only ciphertext-blob hashes, and only to the two conversing identities' devices — the routing rule above guarantees no third party ever holds a DM envelope. This safety depends on that invariant: it must hold if group DMs or third-party relay are ever added.

## Revocation self-logout

- On processing a revocation cert naming **this device's own device_pub** (arriving via gossip or local API), the node: wipes the identity private key and X25519 private key from `keys.json` (device keypair retained solely to keep the file loadable), closes and deletes `hearth.db`, and enters a terminal "revoked" state; all API routes except `GET /` + `/api/state` return 410; UI renders a full-screen "This device has been revoked" notice.
- Devices panel everywhere: revoked devices move to a collapsed "revoked" history row (no longer shown among active devices).
- New wraps exclude revoked devices (already implied by "non-revoked" above).
- Honest limit documented in README + concept-doc delta: compliant-client behavior only.

## API + UI

- `GET /api/conversations` — one row per counterpart identity: name, last message preview (decrypted locally), unread count (client-side, last-seen watermark in localStorage).
- `GET /api/dm/{identity_pub}` — decrypted thread with that identity, oldest-first.
- `POST /api/dm` — multipart like `/api/post`: `to`, `text`, `expires_seconds`, `photos`.
- Delete = existing `/api/delete` (tag gossips to both sides).
- UI: "Messages" toggle in the header; conversation list (friends with threads + friends without), thread view with compose box; live updates via the existing WebSocket "changed" push; DM photos render via a new `GET /api/dm-blob/{msg_id}/{hash}` that decrypts server-side-locally (the node process holds the keys; it only ever serves localhost).

## Storage

- `messages` table: new `recipient` column for DMs (mirrors the `target_id` pattern; existing rows null).
- Enckey directory: latest `enc_pub` per (identity, device), derived from stored enckey messages.
- Decryption on read (store holds ciphertext at rest for DM bodies — a small honesty bonus given encryption-at-rest is otherwise out of scope).

## Testing

- **Unit (dmcrypt):** seal/open round-trip; tampered body/wrap rejected; non-wrapped device cannot decrypt; AAD mismatch rejected; revoked device excluded from wraps.
- **Integration (real sockets, per today's suite):** offline mailbox story (send while recipient phone down, deliver via her home node); mutual-observer node NEVER receives the DM (the structural assertion); photo DM decrypts end-to-end, blob is ciphertext in every store; delete-for-both; expiry; self-logout on revocation (revoked node wipes and 410s, existing devices drop it from active lists).
- All existing 69 tests remain green.

## Out of scope (stated)

Forward secrecy / ratchet (named follow-up); group DMs; read receipts/typing; re-wrap of history for late-enrolled devices; push notifications; encryption at rest for non-DM content; Tor (unchanged).

## Demo cast change

Add `freja-homenode` (run/freja-homenode, gossip 7104, HTTP 7204), paired to Freja at cast build, so both sides demonstrate the mailbox story. Existing `run/` casts are incompatible (new keys/columns) — demo README notes: delete `run/` once after this lands.
