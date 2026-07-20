# Android B.2d-1 (photos + sync progress) — design, 2026-07-20

Sixth slice of the Kreds Android client, after the Tor-dial spike (PR #1),
Brick A (#2), B.1 (#3), B.2 (#4), and B.2c (#5). B.2/B.2c made own + friends'
TEXT readable. This slice renders PHOTOS from the already-synced encrypted
blobs, and adds live SYNC PROGRESS feedback (the sync takes 1-2 min on the
G20 with no visible activity today).

"Rich feed" (B.2d) decomposes into independent rendering subsystems: photos
(this slice), video (B.2d-5, heavier — mp4 decode), threads/replies
(B.2d-2), reactions/comments (B.2d-3), stories (B.2d-4). Photos first: most
self-contained, highest visual payoff, NO hearth change.

## The load-bearing fact (verified against hearth)

Blobs are encrypted with the SAME per-message content key as the body:
`encrypt_blob(key, data)` where `key = new_content_key()` — the same key
`encrypt_body` uses (node.py:701-708). `decrypt_blob(key, data)` =
ChaCha20-Poly1305 over `data[12:]` with nonce `data[:12]` and a CONSTANT
`BLOB_AAD = b"hearth/dm-blob/v1"` (dmcrypt.py:124-135) — no per-message AAD,
simpler than body decrypt. The phone already syncs the encrypted blob bytes
(`blobs(hash, data)` table, hash-verified, B.1) and DecryptPass already
recovers each message's content key (B.2). So photos are pure phone-side:
decrypt the blob with the key we already have, render it.

## The format reality (verified against imagegate.py)

Post/DM photos are stored as **AVIF** — `transcode_photo` runs an AVIF
quality ladder (`img.save(format="AVIF")`, imagegate.py:69-118) and
`photo_thumb` produces AVIF thumbnails. Avatars/banners are PNG; animated
GIFs pass through raw. The desktop renders AVIF only because its UI is a
WebView (Chromium software-decodes it — imagegate.py:59-60). **The G20 is
API 30 (Android 11); native AVIF decode arrived only in API 31 (Android
12)**, so RN's `<Image>` cannot decode AVIF on the test device. This slice
therefore decodes AVIF to a bitmap on the phone (a maintained native
decoder) and re-encodes PNG for the data URI, so rendering stays native and
works down-level. Decision: native decode over a WebView (August, 2026-07-20)
— keeps the feed native and matches the "use their codec behind our own
boundary" pattern (as with tor-android).

## Scope

**In:** decrypt + render image blobs and their thumbnails in the feed —
covering AVIF (the real photo format, decoded on the phone), plus
JPEG/PNG/GIF/WebP passed through — for own AND friend posts/DMs (B.2c made
friend content readable); tap a thumbnail to view the full image
full-screen; a live sync-progress status line. **Posture:** decrypt-on-read
— no decrypted image bytes or content keys are ever written to disk; they
live only in the module's in-memory caches (same lifetime as the existing
`feedCache`, gone on process death).

**Out (named):** video playback + mp4 blobs (B.2d-5) — non-image blobs
render a "media not supported yet" placeholder; threads/replies (B.2d-2);
reactions/comments (B.2d-3); stories (B.2d-4); pinch-zoom / carousel
gestures; any disk image cache; any hearth change; the B.2c display-name
spoofing hardening (separate ticket).

## Components

### 1. `KotlinBlobCrypt` (new, vector-gated)

`object KotlinBlobCrypt { fun decryptBlob(contentKey: ByteArray, cipher: ByteArray): ByteArray? }`
— BouncyCastle ChaCha20-Poly1305 (same AEAD path as `KotlinDmcrypt`),
nonce = `cipher[0..12]`, ciphertext = `cipher[12..]`, aad =
`"hearth/dm-blob/v1"`. Returns null on length < 13 or auth failure (mirrors
`decrypt_blob`). Gated by a committed vector generated from real
`hearth.dmcrypt.encrypt_blob` (known key + known bytes -> phone decrypts to
the exact bytes), extending `android_tor_spike/tools/make_dmcrypt_vectors.py`
+ `dmcrypt_vectors.json`.

### 2. DecryptPass surfaces media refs

`DecryptPass.Decrypted` gains `blobs: List<String>` and
`thumbs: List<String?>` (from the decrypted payload's `blobs`/`thumbs`
lists; `thumbs` entries may be null, same-length as `blobs` when present).
DecryptPass already holds the content key while decrypting each message; it
returns, alongside the `Decrypted` list, a `Map<String, ByteArray>` of
`msgId -> contentKey` for messages that carry blobs, so the module can
decrypt those blobs lazily without re-running the unwrap. (Keys for
blob-less messages are not retained.)

### 3. `KotlinImageDecode` (new — the AVIF decode boundary)

`object KotlinImageDecode { fun toRenderable(bytes: ByteArray): Pair<String, ByteArray>? }`
— returns `(mime, bytes)` ready for a native `<Image>` data URI, or null if
the bytes are not a supported image (a video mp4 lands here). Format by
magic bytes: JPEG `FF D8`, PNG `89 50 4E 47`, GIF `47 49 46 38`, WebP
`RIFF`+`WEBP`, BMP `42 4D`, TIFF `49 49 2A 00`/`4D 4D 00 2A` (mirroring
`imagegate.is_image_bytes`) pass through unchanged with their mime. **AVIF**
(`bytes[4..8] == "ftyp"` and `bytes[8..12]` in `avif`/`avis`) is decoded to
a `Bitmap` via a maintained native AVIF decoder and re-encoded to PNG
(`Bitmap.compress(PNG)`), returned as `("image/png", pngBytes)`. Decoder:
a maintained AVIF library supporting API 24+ (e.g. `com.github.awxkee:avif-coder`);
the implementer confirms the exact lib, that its license is AGPL-compatible,
and that it decodes down to API 30 on the G20. The decode call is isolated
behind this one boundary so a swap is a backend change.

### 4. Module: `getBlobImage(msgId, hash)` + progress event

- The module caches the `msgId -> contentKey` map from DecryptPass in memory
  (feedCache lifetime; cleared on the next sync and never persisted).
- `getBlobImage(msgId: String, hash: String): String?` (async) — look up the
  cached key; if absent return null. Load the ciphertext from the `blobs`
  table (`getBlob(hash)`); if absent return null. `KotlinBlobCrypt.decryptBlob`;
  if null return null. `KotlinImageDecode.toRenderable`; if null (non-image,
  e.g. a video mp4) return null -> UI placeholder. Return a base64
  `data:<mime>;base64,...` URI (PNG for decoded AVIF, original mime
  otherwise).
- Feed items marshalled by `getFeed` gain `blobs`/`thumbs` arrays.

### 5. Sync progress

- `KotlinSync.run` gains `onProgress: (phase: String, count: Int) -> Unit`
  (default no-op, so existing callers/tests are unchanged), invoked at the
  phase transitions it already runs: `"connecting"`, `"handshake"`,
  `"messages"` (running count as each SignedMessage is ingested), `"blobs"`
  (running count), `"decrypting"`, `"done"`. Totals are unknown until the
  pull is underway (the HAVE/pull protocol doesn't front-load them), so
  counts are running, not out-of-N.
- The module forwards each as an `onSyncProgress` event
  `{phase, count}` (alongside the existing terminal `onSync`).
- No change to what sync does — observability only.

### 6. Feed UI (App.tsx)

- Each feed row renders its images as a small thumbnail row: for each blob,
  fetch `getBlobImage(msgId, thumbHash ?? blobHash)` lazily; render an
  `<Image>` from the returned data URI, or a "media not supported yet"
  placeholder chip when null (missing blob / decrypt fail / non-image).
- Tap a thumbnail -> a full-screen `Modal` renders the full image via
  `getBlobImage(msgId, blobHash)`; tap to close. No pinch-zoom in v1.
- A live status line under the Sync button driven by `onSyncProgress`:
  e.g. "Syncing... 120 messages, 8 blobs", resolving to the existing
  "synced: N msgs / N blobs / N friends" (or the error) on `onSync`.

## Data flow

```
sync -> DecryptPass: per message recover content key (as B.2) -> decrypt body
        -> also surface payload.blobs + payload.thumbs; return msgId->key for
        blob-carrying messages; module caches it (in-memory, feedCache life)
   ... during sync: KotlinSync.onProgress -> module onSyncProgress -> status line
feed row mounts -> per image hash: getBlobImage(msgId, thumbOrBlobHash)
        -> cached key + ciphertext blob -> KotlinBlobCrypt.decryptBlob
        -> KotlinImageDecode.toRenderable (AVIF -> PNG; others pass through)
        -> base64 data URI  (or null -> placeholder)
tap thumbnail -> Modal: getBlobImage(msgId, fullBlobHash) -> full image
```

## Testing

What is JVM-gateable vs on-device-only matters here — the AVIF decoder is a
native library (`.so`) that will NOT load in a plain JVM unit test, so the
decode itself is proven on-device, while everything around it is desk-gated.

- **Blob vector gate (JVM):** committed vector from real
  `hearth.dmcrypt.encrypt_blob` — `KotlinBlobCrypt.decryptBlob` reproduces
  the exact plaintext bytes. Pins the decrypt primitive before the phone.
- **`KotlinImageDecode` format-dispatch (JVM):** fixture byte headers ->
  assert the branch each takes (PNG/JPEG/GIF/WebP/BMP/TIFF -> pass-through
  with the right mime; AVIF header -> routed to the decode path; mp4 / junk
  -> null). The native AVIF decode CALL is stubbed/guarded here (no `.so` in
  the JVM); the real decode is the on-device proof below. Keep the
  format-detection logic separate from the decode call so this test covers
  the dispatch fully.
- **`getBlobImage` unit tests (JVM, InMemory store):** a pass-through image
  blob (real PNG bytes, real key) -> non-null `data:image/png` URI; a
  missing blob hash -> null; wrong key -> null; non-image bytes -> null.
- **Extended desk loopback gate:** seed a post carrying a REAL encrypted
  image blob; sync; assert the phone `decryptBlob`s it to the exact original
  bytes and `KotlinImageDecode`'s format-detection accepts it. (The decrypt
  + detection are the JVM-provable end-to-end legs; the AVIF pixel decode is
  device-only.)
- **Sync progress:** a JVM test that `KotlinSync.run` invokes `onProgress`
  with the expected phase sequence and a non-decreasing message/blob count
  against the loopback node.
- **On-device (the AVIF decode proof):** real AVIF photos render in the feed
  (own + friend), tapping opens full-screen, non-image posts show the
  placeholder, and the sync status line ticks up live during a real sync.
  A committed tiny AVIF fixture (generated via hearth's `transcode_photo`
  from a test image) is decoded + rendered on the G20 as the decode's
  ground-truth check.

## Definition of done

The G20 feed shows real photos (own + friends', AVIF decoded on the phone),
tapping opens the full image full-screen, unsupported media shows a
placeholder, and the sync shows live progress instead of 1-2 minutes of dead
air. Desk-proven where provable (blob vector gate, format-dispatch, loopback
decrypt+detect, progress unit test); the AVIF pixel decode is proven on the
G20 (native decoder, not JVM-loadable).

## Risks / honest unknowns (resolve during build)

- **Content-key lifetime in the module cache** — retaining `msgId->key` in
  memory extends a secret's lifetime vs a pure decrypt-on-read of text. It
  is no more sensitive than the decrypted text already in `feedCache`, is
  in-memory only, and is cleared on the next sync; deliberate and bounded.
  Confirm no path persists it.
- **Base64 data-URI size for full images** — a full-resolution image as a
  base64 string crosses the bridge on tap only (not in `getFeed`), one at a
  time; acceptable for the cap'd blob sizes (`MAX_BLOB_BYTES`). If a
  specific large image janks the bridge, note it for a follow-on (a
  content-provider path is the escalation, deferred).
- **Image/video discrimination** — by magic bytes in `KotlinImageDecode`,
  not a payload flag; a video post's mp4 blob fails the image check and
  shows the placeholder. The detection mirrors `imagegate.is_image_bytes`
  plus AVIF; confirm the signatures cover what the desktop actually stores
  (photos = AVIF, avatars/banners = PNG, animated GIF = raw).
- **AVIF decoder dependency** — the load-bearing new dependency. Confirm
  during build: (1) a maintained lib that decodes AVIF on API 30 (the G20);
  (2) license AGPL-compatible (the repo is AGPL-3.0); (3) it ships arm64-v8a
  `.so`s (the G20 ABI) and doesn't bloat the APK unreasonably; (4) it is
  isolated behind `KotlinImageDecode` so a later swap (or a WebView
  fallback) is a backend change. If no acceptable lib is found, escalate —
  do not silently fall back to shipping AVIF as placeholders.
- **Native decode not JVM-testable** — the AVIF decode is proven on-device,
  not at the desk. The format-dispatch and the decrypt legs ARE desk-gated;
  the pixel decode's ground truth is the committed AVIF fixture rendered on
  the G20. Accept this coverage boundary (same shape as the existing
  no-Robolectric SQLite gap).
- **Missing blobs** — a referenced hash the phone never synced -> null ->
  placeholder; never a crash. (Blob sync completeness is B.1's concern, not
  this slice's.)
