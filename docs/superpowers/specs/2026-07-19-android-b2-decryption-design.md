# Android B.2 (decryption + readable history) — design, 2026-07-19

Fourth slice of the Kreds Android client, after the Tor-dial spike (PR #1),
Brick A (PR #2), and Brick B.1 (PR #3). B.1 gives the phone its own-identity
content ENCRYPTED in a native SQLite store (proven on the G20: 253 msgs / 19
blobs / 3 friends). B.2 makes that content READABLE. Decomposition notes:
`docs/superpowers/specs/2026-07-19-android-b2-decryption-decomposition.md`.

## Scope (August, 2026-07-19): enc-key + backfill + decrypt existing history

This slice folds B.2a (enc-key provisioning + Kotlin decrypt path) and B.2b
(own-device backfill of existing content) into one shippable outcome:
**readable text from your real existing history on the phone.** Friends'
content (B.2c) and a rich media/thread feed (B.2d) are later. This is the
FIRST slice to modify production `hearth/` — so the hearth change is
deliberately isolated and heavily tested.

## What this slice proves (and what it does not)

**Proves:** the phone generates + publishes an X25519 enc key, the desktop
re-wraps the phone's existing own-authored content to that key (a new
isolated hearth sweep), and the phone decrypts and displays readable text
from its real 253-message history — end to end, proven on the desk (vector +
real-node gates) before the G20.

**Does NOT (deferred):** friends' content (needs the friend-authorization
nuance — B.2c); rich rendering (media via decrypted blobs, threads,
reactions — B.2d); composing/posting from the phone (only the `enckey` is
pushed); running in the background (Brick C); OS-keystore protection of the
enc key at rest (out of scope, as it is desktop-side).

## Crypto (exact, from hearth/dmcrypt.py — the phone must match byte-for-byte)

- **enc key:** X25519 keypair (`enc_priv`/`enc_pub`).
- **content key:** per-message ChaCha20-Poly1305 key.
- **wrap** (`wraps[device_pub]`): `{eph_pub, nonce, wrapped_key}`. Unwrap:
  X25519(enc_priv, eph_pub) → `_derive_kek` → ChaCha20-Poly1305 decrypt
  `wrapped_key`.
- **`_derive_kek(shared)`** = HKDF-SHA256, length 32, salt=None,
  `info=b"hearth/dm-wrap/v1"`.
- **body decrypt:** `decrypt_body(content_key, body_nonce, body_ct, aad)` =
  ChaCha20-Poly1305 decrypt → JSON body.
- **AAD** (canonical JSON — the phone builds it via `KotlinWire.canonical`,
  which already handles the `created_at` float): posts
  `{"type":"post-aad","protocol":"hearth/v0.2","from":author,"scope":scope,"created_at":ca}`;
  DMs `{"type":"dm-aad","protocol":"hearth/v0.2","from":sender,"to":to,"created_at":ca}`.
- **enckey message:** `make_enckey` = device-signed `{"kind":"enckey","enc_pub":...,"created_at":...}`.
- **wrap_grant:** author-signed message re-wrapping a content key to a
  device — the existing kind the phone already pulls/stores in B.1.

## Components

### 1. Phone enc-key provisioning (Kotlin + a small transport change)

- Generate an X25519 enc keypair once (BouncyCastle `X25519PrivateKeyParameters`),
  persist `enc_priv`/`enc_pub` in the phone's SQLite (a `keys` table / a new
  column; plaintext at rest, matching the desktop posture).
- Compose a device-signed `enckey` message (reuse `SignedMessage`/device
  key: `{"kind":"enckey","enc_pub":...,"created_at":...}`) — the payload
  signed exactly as `make_enckey`.
- **Push it:** the sync MESSAGES phase, which sent `{"t":"messages","msgs":[]}`
  in B.1, now sends the phone's outbound messages. For B.2 that is just the
  `enckey` until it is confirmed ingested (the node echoes it back / the
  phone stops re-sending once the node's HAVE shows it). This is the
  transport's first real WRITE; it reuses `KotlinSync`'s frame path.

### 2. Hearth own-device catch-up (the production change) — `maintain_own_device_grants`

A NEW, isolated periodic sweep in `hearth/node.py`, alongside the existing
maintenance (same guard as `maintain_wrap_grants`: skip if
revoked/locked/unenrolled — minting signs). **`maintain_wrap_grants`
(friend-facing) is left completely untouched.**

For each OWN enrolled device whose enc key the node knows (`store.enckeys(own_identity)`,
excluding this node's own device_pub) and each OWN-AUTHORED message the node
holds that is not already wrapped to that device:
- get the content key via the existing `_content_key(msg)` accessor (the
  node authored it, so it holds the key),
- `wrap_key(content_key, {device_pub: enc_pub}, aad)` (the exact primitive
  posts use), with the message's own aad,
- publish an author-signed `wrap_grant` for `(msg_id, device_pub)` (the
  existing message kind the phone pulls in B.1).

**Own-authored scope:** posts of ALL placements (journal + profile + inner)
and this identity's DMs — the full history, because it is all yours. Broader
than friends ever get (friends deliberately do not receive journal history);
this is the intended principle: *your own devices see all your own content.*

**Security reasoning:** the grant discloses your own content to your own
enrolled device (authorized by definition — a valid identity-signed device
cert, which AUTH already requires); only the phone (holding `enc_priv`) can
unwrap; it re-wraps ONLY own-authored content, never friends' (B.2c);
revocation-aware by the guard + a revoked device's grants stop. No new
disclosure surface beyond "your enrolled device reads your content" — the
satellite premise.

### 3. `KotlinDmcrypt` (the decrypt port) — new, vector-gated

Via BouncyCastle (X25519, ChaCha20-Poly1305, HKDF-SHA256):
- `unwrapKey(wrap: Map, encPrivHex: String, aad: ByteArray): ByteArray?` —
  X25519(enc_priv, wrap.eph_pub) → HKDF(info="hearth/dm-wrap/v1") → ChaCha20-
  Poly1305 decrypt wrap.wrapped_key with wrap.nonce + aad → content key.
  Null on auth failure.
- `decryptBody(contentKey: ByteArray, bodyNonce: String, bodyCt: String, aad: ByteArray): Map?`
  — ChaCha20-Poly1305 decrypt → JSON → Map. Null on failure.
- `postAad(author, scope, createdAt)` / `dmAad(sender, to, createdAt)` —
  built via `KotlinWire.canonical` (the created_at float via the existing
  PyFloat path), matching hearth byte-for-byte.

### 4. Decrypt integration + minimal render

- A phone-side decrypt pass over the SQLite store: for each own message,
  find the content-key source — `wraps[phone_device_pub]` OR a stored
  `wrap_grant` for `(msg_id, phone_device)` (the backfill path; wrap_grants
  are just messages `KotlinSync` already pulls/stores) — `unwrapKey` →
  `decryptBody` → plaintext body. Held for render (decrypt-on-read; not
  re-persisted as durable plaintext beyond a render cache).
- Minimal feed UI: the dashboard gains a list showing, per decrypted
  message, its kind + plaintext text (post body / DM text) + timestamp. No
  media/threads/reactions (B.2d).

## Data flow

```
Phone first run: gen X25519 enc key -> persist -> compose enckey (device-signed)
Sync now: ... MESSAGES phase now PUSHES the enckey ...
Desktop: ingest enckey -> store.enckeys(own) includes phone
         maintain_own_device_grants sweep -> for each own msg: wrap_key(content_key, phone_enc_pub)
              -> publish author-signed wrap_grant(msg_id, phone_device)
Next Sync now: phone pulls the wrap_grants (+ any new wrapped content)
Decrypt pass: per own msg -> wrap or wrap_grant -> unwrapKey -> decryptBody -> plaintext
Feed: render kind + text + timestamp
```

## The AAD fidelity risk (the sharp edge)

If the Kotlin `aad` diverges from hearth by a byte, EVERY decrypt returns
null and the feed is empty — a uniform, diagnosable failure (not partial
corruption). Mitigations: aad is built via `KotlinWire.canonical` (already
vector-proven, incl. the created_at float), pinned from `dmcrypt.py`, and
gated by committed dmcrypt vectors + the extended real-node loopback gate.

## Testing

- **Kotlin dmcrypt vector gate (JVM):** committed vectors from real hearth
  `dmcrypt` (a content key, a `wrap_key` output, a `decrypt_body` case, with
  the exact aad) — `unwrapKey`/`decryptBody` must reproduce/verify them.
  Pins the AAD + primitives before the phone.
- **Hearth catch-up test (pytest):** a seeded node + a second own device
  with an enc key → run `maintain_own_device_grants` → assert it mints
  `wrap_grant`s for every own-authored message and the second device can
  `unwrap_key`+`decrypt_body` each. Negatives: friends' content NOT granted;
  a revoked/locked node mints nothing. Heaviest coverage (production `hearth/`).
- **Extended desk loopback gate:** seed own posts, publish the phone's enc
  key, run the catch-up, sync, assert the phone DECRYPTS them (not just
  stores) — the end-to-end proof before hardware.
- **On-device:** phone publishes enc key, syncs, the feed shows readable
  text from the real 253.
- Existing gates stay green; `hearth/` changes are additive (the new sweep +
  its wiring into the maintenance loop), `maintain_wrap_grants`/`wire.ts`/
  `handshake.ts`/`wire_vectors.json` untouched.

## Definition of done

The phone publishes its enc key, the desktop's `maintain_own_device_grants`
backfills grants for the existing own history, and the phone's feed shows
readable plaintext from the real 253 messages — proven on the desk (vector +
hearth + loopback gates) then on the G20.

## Risks / honest unknowns (resolve during build)

- **AAD byte-fidelity** (above) — the load-bearing risk; vector-gated.
- **`_content_key(msg)` access for the catch-up** — confirm the node can get
  every own message's content key at sweep time (cache vs re-derive); some
  kinds (DMs, expired) may need care. Pin against `node.py`.
- **enckey publish confirmation** — how the phone knows the node ingested
  its enckey (stop re-pushing): via the node's next HAVE/summary or an echo.
  Simplest robust rule pinned during build.
- **Catch-up volume / cadence** — 253 grants is a burst; confirm the sweep
  is incremental and does not stall the node's maintenance loop.
- **Which own kinds are decryptable/renderable** — B.2 renders post/DM text;
  other own kinds (reactions, comments, stories, profile) may decrypt but
  render as a stub in this slice (B.2d does rich rendering).

## Out of scope (named)

Friends' content (B.2c); rich media/thread/reaction rendering (B.2d);
composing/posting from the phone; background sync (Brick C); OS-keystore
protection of the enc key; any change to the friend-facing
`maintain_wrap_grants`.
