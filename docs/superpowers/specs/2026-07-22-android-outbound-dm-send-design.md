# Android Outbound — DM Send (text + photo + story-reply) — Design

Date: 2026-07-22. Third outbound/write slice for the Android WebView client
(after outbound-1 compose-post, PR #14, and outbound-2 responses, PR #15).
Approved by August 2026-07-22 (scope: full parity cut — text + photo +
story-reply in one slice).

## Goal

Send DMs from the phone: text, photo attachments, and story replies
(the story-reply chip in the story viewer sends a DM with `story_ref`,
matching desktop 0.3.17 behavior). A phone-composed KIND_DM is
byte-identical to a desktop-composed one; the recipient's desktop decrypts
it with no special casing. Read side needs no new work — conversations,
dm-thread, and `/api/dm-blob` already render received DMs on the phone.

## Model (mirror of hearth `node.compose_dm`, node.py:2308-2345)

- Validations, in hearth's order with hearth's exact error strings:
  `to == own identity` → `"cannot DM yourself"`; recipient not known →
  `"recipient is not a friend"`; present-but-malformed story_ref →
  `"bad story_ref"` (see shape guard below). All are caught
  IllegalArgumentException → 400 at the route, mirroring hearth's
  `_400(lambda: ...)`.
- Recipient set = `_dm_device_pubs(to)` (node.py:2308-2315):
  `theirs = store.enckeys(to)` — REQUIRED non-empty, error
  `"no encryption keys known for recipient yet"`; `mine =
  store.enckeys(own)` plus `[fx.device_pub -> own enc_pub]`; merged
  `{**theirs, **mine}` (self-readable so the sent bubble renders on every
  own device).
- `created_at = now` (single value, no drift); `expires_at = created_at +
  expires_seconds` when provided, else null — HONORED (this exact field
  was outbound-1's final-review privacy catch; it gets a dedicated
  loopback assertion this time).
- AAD = `dm_aad(own, to, created_at)` = `canonical({"type":"dm-aad",
  "protocol":"hearth/v0.2","from":own,"to":to,"created_at":PyFloat})`.
  Already ported: `KotlinDmcrypt.dmAad` (read side, B.2) — REUSE, do not
  re-derive.
- Fresh 32-byte content key. Photos: each gated through `PhotoPrep`
  (decode → downscale → JPEG ladder; EXIF stripped structurally — the
  Android analog of hearth's `transcode_photo` gate; undecodable/oversize
  → 400 before any store write), then `KotlinBlobCrypt.encryptBlob(key,
  jpeg)` and stored; `refs` = ciphertext hashes.
- Encrypted body = `{"text": text, "blobs": refs}` via
  `KotlinDmcrypt.encryptBody(key, body, aad)`; `wraps =
  KotlinDmcrypt.wrapKey(key, pubs, aad)` (wrap AAD == body AAD).
- Envelope = `make_dm` (messages.py:134-156): `{kind:"dm", to,
  body_nonce, body_ct, wraps, blobs: refs, created_at, expires_at,
  story_ref}` — text lives ONLY inside the encrypted body; blob refs ride
  both (envelope refs are ciphertext hashes, same disclosure class as
  posts); `story_ref` rides PLAINTEXT in the envelope by design (named
  disclosure, see Honest limits).
- Device-sign → `store.ingestMessage` → `store.addPendingOutbound(msgId,
  toDict)` (pushes on next sync via the outbound-1 queue) → warm the sent
  key so the thread renders without waiting for a decrypt pass (hearth
  `_cache_message_key`; Android analog: the existing in-memory
  `dmKeysCache` — in-memory only, decrypt-on-read preserved).

### story_ref shape guard (mirror `_valid_story_ref`, messages.py:241+)

Only a PRESENT value is checked (absent/None always fine): must be a
dict; `story_id` = non-empty string (opaque message id — NOT required to
be hex64); `media_hash` = a real content-addressed blob hash (hex64);
extra keys ride through unrejected (forward-compatible, fail-closed only
on the two fields that matter). The phone never resolves story_ref
against a real story (compliant-client precedent — shape-validated only).

## Components

1. **`ComposeDm.kt`** (create) — the orchestrator above. Third sibling of
   `Compose` (posts) and `ComposeResponse`; same Result(msgId, wireDict)
   shape, same fixture/device-sign/enqueue tail. Deliberately NOT a
   generalization of `Compose` — hearth keeps compose_post/compose_dm/
   compose_response separate, so the sibling shape IS the parity shape
   (DRY pass = ticket for after the outbound family completes).
2. **`POST /api/dm`** in `LocalApi.kt` — multipart via the existing
   `Multipart.parse` (desktop contract, api.py:666-681): fields `to`,
   `text` (default ""), `expires_seconds` (blank → null; else parsed as
   Double — org.json is not involved, this arrives as a form STRING;
   parse with toDoubleOrNull, invalid → 400 — DELIBERATE minor
   divergence: desktop's bare `float(...)` at api.py:677 sits outside
   the `_400` lambda so garbage would 500 there; app.js only ever sends
   "" or a number, so the stricter fail-closed 400 is unreachable in
   practice and preferred), `story_ref` (JSON string →
   parsed dict or null; parse failure → 400 mirroring hearth
   `_parse_json_field`), `photos` (repeated file parts). Response
   `{"msg_id": mid}` exactly like desktop (app.js reads r.ok only today,
   but the shape must match the desktop server's). Route added before the
   GET guard, reusing sharedStore/fixtureOrNull/EncKeys.getOrCreate;
   mandatory PhotoPrep on every file part (no bypass). NOTE: `/api/dm`
   never collides with the existing `/api/dm-blob/` prefix dispatch —
   LocalApi.kt:64 already documents the "-" vs "/" distinction.
3. **Seam reveal** — remove `#dm-compose` from the `body.readonly` hide
   list in `hearth/web/style.css`; update the block comment; update the
   vitest guard (`web-readonly-seam.test.ts`): `#dm-compose` no longer
   hidden, `#profile-wall-compose`/`#profile-arrange`/story-add/etc.
   still hidden. Grep check: `#dm-compose` and the story-reply UI must
   not share classes/ids with any surface that stays hidden.
4. **Story-reply chip goes live for free** — shared app.js already POSTs
   `/api/dm` with `story_ref` + reaction glyphs from the story viewer
   (app.js:3207-3221). Verify (don't assume) the phone's story viewer
   uses that exact code path and that the reply UI isn't seam-hidden by
   a separate selector.
5. **Sent-DM read-back: verify, no new code expected** — own sent DM
   decrypts via the self-wrap through the existing conversations pass
   (loadDms). The slice VERIFIES the sent bubble renders (unit +
   on-device) rather than assuming; if the pass misses own fresh
   composes, the fix is the dmKeysCache warm in component 1, not a new
   read path.

## Testing / parity gates

- **JVM unit tests**: ComposeDm through proven inverses — own-wrap
  unwrapKey/decryptBody recovers `{text, blobs}`; an INDEPENDENT
  keypair standing in for the recipient device opens the recipient wrap
  (non-tautological); envelope field set exact (incl expires_at null vs
  value, story_ref null vs dict); validation rejections use hearth's
  exact error strings; blob refs = ciphertext hashes decryptable by the
  content key. Route-level pyStr-class parsing traps (form-string
  expires_seconds) unit-tested on the LocalApi companion where testable.
- **Loopback fidelity gate** (extend `sync_loopback_node.py` +
  `SyncDmLoopbackTest.kt`): seed the node with the phone's own identity
  AND a FRIEND identity + enc keys (self-DM is rejected, so the DM
  targets the friend). Phone composes: (1) text DM, (2) photo DM,
  (3) story-reply DM with story_ref, (4) expiring DM. Push via the
  pending queue. Node, using REAL hearth crypto as the RECIPIENT
  (friend's enc_priv, dmcrypt.unwrap_key/decrypt_body with dm_aad
  recomputed from the envelope's own fields, decrypt_blob for the
  photo): asserts text bytes, photo AEAD-verified decrypt, story_ref
  passes hearth's `_valid_story_ref`/ingest validate_payload, and
  `expires_at == created_at + expires_seconds` exactly, PLUS hearth's
  own expiry semantics treat the message as expiring (assert via
  hearth's real expiry-visibility mechanism, not a reimplementation —
  the plan pins which store/node call exposes it). Assertions are
  fail-closed; a fidelity miss = BLOCKED, never weakened.
- **vitest**: seam guard updated (component 3).
- **On-device DoD (August drives; G20, RELEASE apk, force-stop first;
  watch for the Play Protect consent dialog)**: (a) DM composer visible
  in a thread; (b) text DM arrives on desktop, correct thread + sender;
  (c) photo DM arrives, image renders both ends, EXIF stripped;
  (d) story reply from the phone's story viewer lands as a DM on
  desktop with the story context chip; (e) an expiring DM disappears
  after its TTL on both ends; (f) sent bubbles render on the phone
  immediately; (g) regression: profile wall compose/Arrange/story-add
  stay hidden.

## Out of scope

Group DMs (hearth is 1:1), DM deletion/unsend, read receipts, typing
indicators, story posting (own slice), profile editing slices, friend
management, `/api/response-remove` (carried ticket).

## Honest limits

- `story_ref` is plaintext envelope metadata by hearth design: a mutual
  of both parties can tell WHICH story a DM was about (documented
  correlation caveat, messages.py:139-150). The phone changes nothing
  about this disclosure class.
- `to` is plaintext on the envelope (hearth wire design; same on
  desktop) — DM existence/recipient is store-visible metadata; only
  content is encrypted.
- Expiry is cooperative (compliant clients honor expires_at; same trust
  model as desktop).
- The recipient must already have published enckeys the phone has
  synced ("no encryption keys known for recipient yet" otherwise) —
  same failure mode as desktop.
