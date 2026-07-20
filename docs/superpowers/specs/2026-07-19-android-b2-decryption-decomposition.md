# Android B.2 (decryption + rendering) — decomposition notes (2026-07-19)

Scoping captured during brainstorming, before the full B.2 design. B.1
(merged, PR #3) gives the phone 253 own-identity ENCRYPTED messages + 19
blobs + friend list in a native SQLite store. B.2 makes content readable.

## The crypto path (from hearth/dmcrypt.py)

To read a message the phone: finds its wrap in `wraps[phone_device_pub]`
→ `unwrap_key(wraps, phone_device_pub, enc_priv, aad)` (X25519 ECDH →
HKDF-derived KEK → ChaCha20-Poly1305 decrypt) → content key →
`decrypt_body(content_key, body_nonce, body_ct, aad)` (ChaCha20-Poly1305 +
JSON) → plaintext body dict. Enc keys are X25519; content keys are
per-message ChaCha20-Poly1305.

## The gating facts

- **Enc-key provisioning is the gate.** The phone must generate an X25519
  enc keypair and PUBLISH it (a device-signed `enckey` message — `kind:
  KIND_ENCKEY, enc_pub`). Publishing means the phone must PUSH a message
  (a change from B.1's read-only pull). Once published,
  `_scope_device_pubs` wraps NEW own content to the phone automatically —
  no hearth change for new content.
- **The existing 253 are NOT wrapped to the phone** (composed before its
  enc key existed). Reading them needs BACKFILL: re-wrapping old content to
  the phone's enc key.
- **Backfill is net-new hearth work.** `maintain_wrap_grants` re-wraps only
  FRIENDS' devices on kreds-PROFILE (wall) posts, and deliberately leaves
  journal/inner alone and excludes own identity. An own-device backfill
  (re-wrap YOUR content to YOUR new device) does not exist yet.

## Decision (August, 2026-07-19): first slice INCLUDES backfill

The first B.2 slice must deliver readable existing history (the 253), not
just new content. This is bigger and, for the first time in this effort,
touches production `hearth/` crypto.

## Design principle (own-device backfill)

**Your own devices should see all your own content** — journal + wall +
inner + DMs — because it is YOUR content on YOUR device. This is broader
than friends ever get (friends deliberately do not receive your journal
history). The backfill re-wraps every own message's content key to a
newly-published own-device enc_pub.

## The two substantial, novel pieces

1. **Kotlin dmcrypt port** — `unwrap_key` + `decrypt_body` (+ the HKDF KEK
   derivation), via BouncyCastle (X25519, ChaCha20-Poly1305, HKDF all
   present), vector-gated against real hearth like the Ed25519 port.
2. **Own-device backfill in hearth** (production-crypto change) — on seeing
   a new own-device enc key, re-wrap existing own content to it via
   author-signed wrap_grants. Highest-stakes code in the effort: a bug
   could mis-wrap/leak content or disturb the live desktop. Needs the D2 /
   multi-device security reasoning and heavy review.

## Proposed decomposition (for the full B.2 design)

- **B.2a — enc-key provisioning + Kotlin decrypt path + decrypt content
  wrapped to the phone.** Prerequisite for everything; buildable WITHOUT a
  hearth change (proves the decrypt path on NEW content that auto-wraps to
  the phone via `_scope_device_pubs`). The risk-reducer.
- **B.2b — own-device backfill** (the hearth change) + decrypt the existing
  253, reusing B.2a's proven decrypt path.
- **B.2c — friends' content** (largely falls out once the phone is a
  wrapped recipient).
- **B.2d — rich feed UI** (photos via decrypted blobs, threads, kinds).

The August decision folds B.2a+B.2b into the first shippable slice
(readable history). B.2a remains the natural first BUILD step within it
(the decrypt path must work before backfill can be verified).

## Status

Decomposition captured. Full B.2 design (architecture / the hearth backfill
approach / the Kotlin port / testing) still to be written — recommended
with fresh care given it is the first production-`hearth/` crypto change.
