# Android Outbound — Compose Text+Photo Journal Post (design)

**Date:** 2026-07-22
**Status:** design approved, spec for review
**Arc:** first OUTBOUND / write slice for the Kreds Android WebView client. The
read-only visual-parity arc (feed + messages + profile, PRs #11/#12/#13) is
complete and merged; this is the first slice that lets the phone WRITE.

## Goal

Let the phone **compose and publish a text + photo journal post** to the
`kreds` (everyone) scope, authored as the user's identity and signed by the
phone's enrolled device key, so the post appears immediately in the phone's own
feed and — after the next sync — on the desktop web feed and in friends' feeds.

This is the first slice to flip part of the `body.readonly` seam OFF and the
first to implement a non-GET `/api/*` route.

## Why this is the pivotal slice

Everything so far has been the **decrypt/unwrap** direction (B.2 ported
`unwrapKey` / `decryptBody` / `KotlinBlobCrypt` decrypt; the WebView shell reads
the native store). Composing a post requires the **inverse** direction — encrypt
+ wrap + author-sign a new content message natively in Kotlin, because the phone
has no Python node to do server-side crypto (on the desktop, app.js sends
plaintext and `node.py` does all the crypto). This slice builds that outbound
crypto pipeline once, reusing B.2's primitives inverted.

## The load-bearing hearth finding (drives the architecture)

`node.maintain_wrap_grants` re-wraps own content to *current* friends **only for
`kreds`-scope `profile` posts** — it **deliberately skips journal and inner**
("a journal is a moment in time", spec 2026-07-15-wall-wrap-grants). Therefore a
**journal** post is **not** backfilled to friends by the desktop. The
tempting shortcut "phone wraps to own devices only, let the desktop fan out to
friends" does **not** work for journal posts.

**Consequence:** the phone-as-author must wrap the post's content key to all
recipient devices **at compose time**. `node.maintain_own_device_grants` (built
in B.2) still backfills *own-device* coverage as belt-and-braces, but friends
depend on the phone's compose-time wrap. This matches desktop behavior exactly —
a desktop-composed journal post also reaches only friends whose enc-keys the
desktop held at compose time.

## Scope (this slice)

**In:**
- `POST /api/post` handler in `LocalApi` (text + photos + `scope`), the first
  non-GET route.
- Native compose pipeline: recipient resolution, content-key generation, body +
  blob encryption, X25519 key-wrapping, `make_post`-shaped payload, device-sign,
  local store insert.
- Photo pipeline: pick → EXIF-strip → downscale → JPEG encode → content-key
  blob-encrypt.
- Seam flip: enable **only** the journal `.composer`.
- The post is pushed on the existing sync MESSAGES phase (already supports
  outbound — B.2 pushed the enckey).

**Out (deferred to later slices, each its own spec):**
- `inner` scope (needs KIND_RING processing, not built — same gap as the
  profile ring/since ticket).
- Reactions, comments, DM-send, story compose, profile edit, deletes,
  friend-management writes.
- AVIF encoding (phone sends JPEG); server-side/tile thumbnails (full-image
  fallback already works, per B.2d).
- Albums (`/api/album`); profile-wall posts (`placement=profile`) — the handler
  may wrap generically, but only the journal composer is exposed this slice.

## Architecture

### Native compose pipeline (Kotlin)

New unit `Compose` orchestrates, on the loopback request thread (off the JS
runtime, like all `LocalApi` work):

1. **Resolve recipients** for `scope=kreds`: own enc-keyed devices + **all
   friends' enc-keyed devices**, via a new store accessor
   `enckeys(identityPub): Map<devicePub, encPub>` reading the KIND_ENCKEY
   messages already synced into the store (mirrors hearth `store.enckeys`).
   Friends = `knownIdentities()` minus self.
2. **Photos** (`PhotoPrep`, main process — these are the user's *own* bytes, so
   no isolated-decode sandbox is needed): decode each picked image → **strip
   EXIF** (mandatory — a raw camera JPEG leaks GPS) → downscale to a max
   dimension and to ≤ `MAX_BLOB_BYTES` (10 MiB) → **JPEG** encode. JPEG (not
   AVIF): the phone has no AVIF *encoder*; JPEG displays identically in every
   viewer (Chromium + the phone's magic-byte `KotlinImageDecode`), and hearth
   accepts device-authored blobs by content hash (imagegate runs only on the
   node's own upload path, never on ingest).
3. **Content key** (random) → ChaCha20-Poly1305 **encrypt the body**
   (`KotlinDmcrypt.encryptBody`, post AAD) and **encrypt each blob**
   (`KotlinBlobCrypt.encryptBlob`, blob AAD); store each ciphertext blob
   (`putBlob`, hash-addressed).
4. **Wrap** the content key to each recipient's enc_pub
   (`KotlinDmcrypt.wrapKey`, X25519-ECDH + HKDF + ChaCha20-Poly1305 — the
   inverse of B.2's `unwrapKey`), producing the `wraps` map (device_pub →
   wrapped key).
5. Build the **`make_post`-shaped** KIND_POST payload (exact hearth field set:
   `kind`, `text`, `blobs`, `thumbs`, `poster`, `media`, `scope`, `placement`,
   `created_at`, `body_ct`/`nonce`, `wraps`, …; confirm the precise shape from
   `messages.py:make_post` in the plan), **device-sign** it (KotlinWire
   canonical + Ed25519 sign — already exists from B.2's enckey compose).
6. **Store locally** (`putMessage` / `ingestMessage`) so the post shows in the
   phone's own feed on the next app.js refresh, before any sync.

### LocalApi route

`handle()` currently returns `null` for any non-GET (so writes no-op). Open it
for exactly `POST /api/post`: parse the multipart body (text, `scope`, photo
parts), call `Compose.post(...)`, return 200 on success / 4xx on failure. All
other non-GET requests keep no-op'ing.

### Seam flip

In `hearth/web/style.css`, remove **only `.composer`** from the `body.readonly`
selector list — this reveals the journal compose box. `#profile-wall-compose`,
`.comment-composer`, `.rx-open`/`.rx-picker`, `#dm-compose`, and the
profile-arrange controls **stay hidden**. Desktop is unaffected (never sets
`body.readonly`).

### Data flow

`compose UI → POST /api/post → Compose (encrypt+wrap+sign) → local store insert →
app.js refresh shows it on the phone → next sync MESSAGES-phase push → desktop +
friends ingest and (their sweeps / this wrap) can read it`.

## Fidelity — the sharp edge

The **post AAD must byte-match hearth** or the desktop and friends cannot
decrypt (a byte off → uniform, diagnosable "nobody can read it"). Encryption is
non-deterministic (random content key + nonce), so we cannot vector-match
ciphertext. The gate is a **round-trip loopback test**: the phone composes over
loopback → a **real seeded `HearthNode` decrypts** it → assert the plaintext
text + blob bytes match what was composed. Same gate style as B.2's two-sync
decrypt test. Unit vectors additionally pin `wrapKey`/`encryptBody`/
`encryptBlob` by decrypting their output with the already-proven inverse.

## Error handling

- **Friend enc-key not yet synced** → that friend is skipped (no wrap); logged,
  not fatal. Matches hearth's un-healed journal behavior; the phone syncs on
  mount + every 15 min, so this is a rare new-friend transient.
- **Compose failure** (encode/sign/store error) → 4xx; the composer surfaces the
  error; **nothing is stored** (no partial post).
- **Offline** (no desktop reachable) → the post is stored locally and pushed on
  the next successful sync — this is already how the sync queue works; the post
  is visible on the phone immediately regardless.

## Security

- The phone **device-signs**; it never touches the identity key.
- **EXIF strip is mandatory** on every photo (GPS/metadata leak otherwise).
- **Decrypt-on-read preserved:** the content key exists in memory only during
  compose; no plaintext body/key is persisted (only the ciphertext blob + the
  wrapped keys + the signed message are stored).
- Every other write path stays hidden by the seam and no-op'd by `handle()`'s
  non-GET guard (only `POST /api/post` opens).
- Device-authored KIND_POST acceptance: hearth's multi-device model authors
  content signed by device keys bound to the identity (the desktop is itself a
  device); the phone is another enrolled device. B.2 already proved
  device-signed messages (enckey) are accepted. **Confirm in the plan** there is
  no post-specific "author must be identity key" check.

## Testing

- **JVM units:** `wrapKey` / `encryptBody` / `encryptBlob` round-trip (encrypt
  then decrypt with B.2's proven inverse); `enckeys(identity)` accessor;
  `make_post` payload golden shape; multipart parse.
- **Loopback gate:** phone-compose → real `HearthNode` ingest + decrypt →
  plaintext + blob match (proves AAD fidelity + device-authored acceptance
  before the phone).
- **Web guard (vitest):** the read-only seam still hides everything except
  `.composer`.
- **On-device DoD (G20):** compose a **text + photo** journal post on the phone
  → it appears in the phone's own feed immediately → after a sync it appears on
  the **desktop web feed** and in a **friend's** feed, photo rendering and text
  intact; EXIF verified stripped on the delivered blob.

## Open items to resolve in the plan (not blocking this design)

- Exact `make_post` payload field set + AAD canonical bytes (from
  `messages.py`).
- `KotlinDmcrypt.wrapKey` / `encryptBody` HKDF `info` strings + AAD construction
  (mirror the ported decrypt side exactly).
- Multipart parsing in `LocalWebServer` (the server currently reads only the
  request line + headers; a `POST /api/post` body reader + `multipart/form-data`
  boundary parse are new — keep it minimal and well-bounded).
- Confirm the sync MESSAGES-phase outbound already carries arbitrary stored
  own-identity messages (B.2 pushed a single composed enckey; verify a stored
  KIND_POST flows the same push path).
