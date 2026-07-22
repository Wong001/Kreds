# Android Outbound — Compose Reactions + Comments (KIND_RESPONSE) — design

**Date:** 2026-07-22
**Status:** design approved, spec for review
**Arc:** second OUTBOUND / write slice. Journal-post composing shipped (PR #14).
This adds the crypto-heaviest write: composing reactions + comments, plus the
read-side de-anon so responses attribute correctly on the phone.

## Goal

Let the phone **react to and comment on journal posts** (own or friends'),
composed natively in Kotlin, AND **display responses with correct attribution**
— your real name to people who know you, an alias to strangers — matching
hearth exactly on every platform.

## The model (corrected during brainstorming)

Responses are NOT "private by default." They are **name-to-those-who-know-you,
alias-to-strangers**: if A comments on friend B's post, a mutual friend C (who
knows A) sees A's real name; someone who knows B and C but not A sees an alias.
This is the `mutual_box` (`seal_slots`) mechanism — A's identity is sealed into
anonymous slots readable only by **A's own friends**, who trial-open to de-anon;
strangers find no openable slot and fall back to the client alias.

## Parity invariant (load-bearing)

A phone-composed KIND_RESPONSE is **byte-identical** to a desktop-composed one.
**Who can de-anon a response is determined by the responder's friend graph
(sealed in the mutual_box), never by the composing device.** A response made on
the phone reads identically on desktop and (future) iOS/macOS — same people see
the name, same people see the alias. This is proven by the real-node loopback
gate: a genuine hearth `HearthNode` processes the phone's output identically to
its own.

## Scope

**In:**
- **Write** (`compose_response` ported): `POST /api/react {msg_id, token}`,
  `POST /api/comment {msg_id, text}`, and un-react / retract (reaction
  `token:"clear"`; comment/reaction removal). `rkind` ∈ (`reaction`, `comment`,
  `retract`) distinguishes them — one compose path, own-post included.
- The one new crypto: **`seal_slots` (seal) + `try_open_slots` (open)**, ported
  to Kotlin, vector-gated against hearth both directions.
- **Read de-anon:** integrate `try_open_slots` into the existing responses read
  path so the phone shows real names for responders it knows (and "you" on your
  own) instead of always an alias.
- Reveal the reaction picker + comment composer + remove affordances from the
  read-only seam.
- Reuse: `wrapKey`/`encryptBody`/`signRaw`/`responsesAad`/the pending-outbound
  queue (all from outbound-1 / B.2d-4).

**Out (deferred to later slices):**
- The `public_engagement` HTTP toggle (fully-public, name-to-everyone responses).
  Composing stays **private-by-default** — that default *is* the model above.
- Author moderation (an author removing *others'* comments) — only own
  react/comment/retract this slice.
- Responses on profile-wall posts (hearth `compose_response` requires
  `placement=="journal"`).
- DM-send, profile edit, `inner` scope (other write slices).

## Architecture

### Write — `ComposeResponse` (Kotlin, mirrors hearth `node.compose_response`)

`ComposeResponse.react(...)` / `.comment(...)` / `.retract(...)` (or one
`compose(target, rkind, body)`):
1. **Validate** (mirror hearth): `rkind` ∈ (reaction, comment, retract);
   reaction body ∈ `REACTION_TOKENS + ("clear",)`; comment 1–`MAX_COMMENT`(500)
   chars; target must be a stored **journal** KIND_POST.
2. **Author + recipients:** `author = target.cert.identity_pub`;
   `authorDevs = store.enckeys(author)` (+ this device if own post). Empty →
   error (no reachable author device).
3. **Monotonic `created_at`:** a strictly-increasing per-instance clock
   (`last <= now ? last + 1e-6 : now`) — retract/fold key entries on it, so a
   same-tick collision must not merge two of my responses.
4. **`responder_sig`** `= signRaw(device_priv, canonical({target, rkind, body,
   created_at, responder: own_identity}))` — binds the response to me
   (prevents an author forging my attribution).
5. **`mutual_box`** (private-by-default) `= sealSlots(sealPayload,
   enckeys(all MY friends))`. Audience is **my** friends, never the author's.
   The sealed payload is what a friend learns on open (the responder identity /
   the fields hearth seals). `null` only if `public_engagement` is on (deferred
   → always private this slice).
6. **Encrypt body** `{rkind, body, alias_seed, public:false, mutual_box,
   responder_sig, responder?}` with a fresh content key + `responsesAad`;
   **wrap the key to `authorDevs` only**; **device-sign** the `make_response`-
   shaped KIND_RESPONSE; **store + enqueue** (pending-outbound queue) → push.
   (Exact body field set + where `responder`/`responder_sig`/`alias_seed` live
   is pinned from `hearth/messages.py:make_response` + `node.compose_response`
   in the plan.)

The author's node aggregates the KIND_RESPONSE into the KIND_RESPONSES record
and relays to the audience — one path, own-post included (no special case).

### New crypto — `KotlinSeal` (mirrors `hearth/dmcrypt.py` `seal_slots`/`try_open_slots`)

- `sealSlots(payload: ByteArray, encPubs: List<String>): List<Map<String,String>>`
  — for each enc_pub: fresh X25519 ephemeral, `kek = _derive_slot_kek(eph·peer)`
  (HKDF — pin the info string from hearth), 12-byte nonce,
  `ct = ChaCha20Poly1305(kek).encrypt(nonce, payload, MUTUAL_BOX_AAD)`; slot =
  `{eph_pub, nonce, ct}` (all hex, **no recipient id**). Pad with byte-random
  dummy slots (same field sizes) to the smallest `_SLOT_BUCKETS` value ≥ real
  count; shuffle. Bad enc_pub skipped (like `wrapKey`).
- `tryOpenSlots(slots, encPrivHex): ByteArray?` — for each slot, derive the kek
  from my enc-priv + the slot's `eph_pub` and attempt the ChaCha20-Poly1305
  open with `MUTUAL_BOX_AAD`; first slot that authenticates → its payload; a
  dummy/other-recipient slot fails the AEAD tag → skipped; none → null.

`_SLOT_BUCKETS`, `MUTUAL_BOX_AAD`, and the `_derive_slot_kek` HKDF info are
copied verbatim from `hearth/dmcrypt.py` in the plan.

### Read de-anon

Integrate `tryOpenSlots` into the existing B.2d-4 responses read path
(`DecryptPass`/`KotlinResponses`): when attributing a **non-public** entry, if
`tryOpenSlots(entry.mutual_box, ownEncPriv)` returns a payload, resolve the
responder's real identity → their profile name (or "you" if it's own identity);
otherwise keep the current client-derived alias. Public entries keep the
existing `responder_sig`+device-bind name path.

### Routes + seam

- `LocalApi.handle`: `POST /api/react`, `POST /api/comment` (+ the removal route
  hearth's UI uses), **JSON** bodies (the composer POSTs JSON, not multipart —
  parse the already-read body as JSON). Return 200 `{"ok":true}` / 4xx on
  validation / 500 on compose error. All rejects before any store write.
- `style.css`: remove `.rx-open`, `.rx-picker`, `.comment-composer`,
  `.comment-x` from the `body.readonly` block (reveal the reaction picker +
  comment composer + remove). Everything else stays hidden.

### Store additions

- `getMessage(msgId): SignedMessage?` (or the fields needed: the target post's
  author identity + kind + placement) — to resolve the author + validate journal.
- Reuse `enckeys(identity)` for both `enckeys(author)` and each friend's enckeys
  (own-friends union for the mutual_box).

## Fidelity — the sharp edges

1. **`seal_slots` byte-fidelity:** the kek HKDF info + `MUTUAL_BOX_AAD` + the
   slot shape must byte-match hearth, or a friend on desktop can't open a
   phone-sealed box (and vice versa). Gate: vector round-trip **both ways**
   (Kotlin-seal → hearth-open, hearth-seal → Kotlin-open).
2. **`responsesAad` + the response body canonical form** must byte-match (as in
   B.2d-4) so the author decrypts + the `responder_sig` verifies.
3. **Monotonic `created_at`** — same-tick collisions must not merge/mis-retract.
4. **Bucket padding** — dummy slots must be byte-indistinguishable from real
   ones (so slot count only buckets, never measures, my friend count).

## Error handling

- Non-journal / unknown target → 4xx. No author enckey → 4xx (can't reach the
  author). Bad rkind/body → 4xx. Compose error → 5xx; nothing stored (reject
  before the store write / enqueue).
- Offline → stored + enqueued, pushed on the next sync (pending queue).
- A response whose mutual_box the phone can't open → alias (existing behavior);
  never a crash.

## Security

- Device-signs (never the identity key). Responder identity is sealed in the
  mutual_box, **not** plaintext (private). Mutual-box audience = **my** friends
  (the author can't shrink the anonymity set with their own graph). `responder_sig`
  binds the response to me. Decrypt-on-read preserved (content key + seal payload
  in memory only). The seam still hides every other write.

## Testing

- **JVM units:** `KotlinSeal` seal→open round-trip (recipient recovers, stranger
  gets null, dummies never false-open, bucket size correct); `ComposeResponse`
  validation + body/wrap shape; monotonic clock.
- **Vector gate:** `seal_slots`/`try_open_slots` against hearth-produced vectors
  **both directions** (proves cross-platform parity at the primitive level).
- **Loopback gate (extended):** a real hearth node ingests a phone-composed
  **react + comment**, decrypts (author-wrap), verifies `responder_sig`, and
  **opens the mutual_box as a friend** — proving byte-parity end-to-end.
- **Read de-anon:** a hearth-composed response with a mutual_box → the phone
  `tryOpenSlots` → shows the responder's real name; a stranger's → alias.
- **Web guard (vitest):** the reaction picker + comment composer are revealed;
  everything else stays hidden under `body.readonly`.
- **On-device DoD (G20):** react + comment on a friend's post (and your own)
  from the phone → appears on the desktop feed with correct attribution (your
  name to mutual friends); a friend's response shows their real name on your
  phone (not an alias); un-react/retract works; regression: DM composer +
  profile Arrange stay hidden.

## Open items to resolve in the plan (not blocking this design)

- Exact `make_response` body field set + `_response_sig_payload` canonical shape
  + what the sealed mutual_box payload contains (from messages.py + node.py).
- `_derive_slot_kek` HKDF info string, `MUTUAL_BOX_AAD`, `_SLOT_BUCKETS`,
  `REACTION_TOKENS`, `MAX_COMMENT` (verbatim from hearth).
- How the existing `KotlinResponses`/`DecryptPass` read path is threaded for the
  `tryOpenSlots` de-anon (minimal change to the B.2d-4 code).
- The exact removal route(s) the composer UI uses (`/api/response-remove` /
  `/api/retract`) and their JSON shapes.
