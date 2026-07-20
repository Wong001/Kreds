# Android B.2d-4 (responses: reactions + comments) — design, 2026-07-20

Tenth slice of the Kreds Android client, and the LAST of the richer-feed
(B.2d) arc, after photos (#6), video (#8), stories (#9). Renders reactions +
comments under posts. View-only, no hearth change, no composing.

## What responses are (verified against hearth)

Reactions and comments are the SAME mechanism: raw `KIND_RESPONSE` messages
(rkind ∈ "comment"/"reaction"/"retract" inside the encrypted body). The post
AUTHOR aggregates all raw responses targeting their post into ONE
`KIND_RESPONSES` record (latest-wins per (author, target)), body
`{entries:[...]}`, wrapped to the WHOLE post scope audience via the same
`wrap_key`/`_scope_device_pubs` the post itself uses (node.py:2739-2744). The
phone, as a wrapped scope recipient, decrypts `KIND_RESPONSES` exactly as it
decrypts a post — same `unwrap_key`/`decrypt_body`, a different AAD
(`responses_aad`, node.py:2821-2823).

**The phone only ever needs `KIND_RESPONSES`, never raw `KIND_RESPONSE`** (raw
responses route author-only; the aggregated record is the audience view —
verified). Responses carry NO media (no blob dependency —
messages.py:495-515, store referenced_blobs excludes them). `KIND_RESPONSES`
already syncs into the phone store today (kind-agnostic ingest, B.1).

## Scope (August, 2026-07-20)

View reactions + comments under posts. Public entries are attributed to a
real friend ONLY when their `responder_sig` verifies AND the signing device
is enrolled to the claimed identity (the security core). Private (or
verify-failed) entries render as a client-derived ALIAS. NO composing/
reacting/commenting from the phone. `seal_slots`/`try_open_slots`
(de-anonymizing a private mutual-friend commenter's real name) DEFERRED — a
strict, safe degrade (private → alias, never a wrong identity).

## The exact crypto (from hearth, byte-pinned)

- **`responses_aad(author_identity, target, created_at)`** (dmcrypt.py:58-62)
  = `canonical({"type":"responses-aad","protocol":PROTOCOL,"from":author_identity,"target":target,"created_at":ca})`
  — same 3-field pattern as post_aad/dm_aad; port to `KotlinDmcrypt` beside
  the existing `postAad`/`dmAad` (created_at via the proven PyFloat path).
- **Entry shape** (`_valid_response_entry`, node.py:93-131; build,
  node.py:2707-2722): `{rkind, body, created_at, alias_seed(hex32),
  public(bool), responder_sig(hex128), mutual_box, ...}` plus
  `identity(hex64)`/`device_pub(hex64)` ONLY when `public`. `rkind` ∈
  comment/reaction; comment body 0<len<=MAX_COMMENT; reaction body ∈
  REACTION_TOKENS ("heart","laugh","wow","sad","up","fire").
- **`responder_sig`** signs, with the responder's DEVICE key (Ed25519),
  `_response_sig_payload` (node.py:1390-1397) =
  `canonical({"target":target,"rkind":rkind,"body":body,"created_at":created_at,"responder":responder})`
  (5 fields). Verify via KotlinWire's Ed25519 (`verifyRaw`-equivalent, the
  same primitive B.1 uses for message signatures) against the entry's
  `device_pub`.
- **Device-binding** (`_device_bound`, node.py:1399+): the claimed
  `device_pub` must be an enrolled device of `identity` — because sig-alone is
  forgeable (an attacker mints a keypair, puts its public half in
  `device_pub`, signs with the private half — verifies, proves nothing). The
  phone's `messages` table already records `(identity_pub, device_pub)` for
  every validly-ingested message (the enrollment cert's device-binding
  signature is checked at ingest, SqliteSyncStore.kt:128-166), which IS
  hearth's `load_views` model — so device-binding is a `SELECT 1 FROM messages
  WHERE identity_pub=? AND device_pub=? LIMIT 1`-style check, no new plumbing.
  Port `_device_bound`'s exact permissive/disprove semantics (node.py:1399+):
  it returns true unless it can actively DISPROVE the binding.
- **Alias** (view side, app.js:142-155): `aliasName(seed)` =
  `ADJECTIVES[parseInt(seed[0:2],16)%16] + " " + ANIMALS[parseInt(seed[2:4],16)%16]`;
  `aliasColor(seed)` = HSL hue `parseInt(seed[0:6],16)%360`. Pure
  deterministic function of the entry's `alias_seed` — no crypto/keys. Port
  the two 16-entry word lists byte-identical.

## Components

### 1. `KotlinDmcrypt.responsesAad` (port)

One function beside `postAad`/`dmAad`, vector-gated.

### 2. `KotlinResponses` (new — the view logic)

Port of hearth's `_post_responses_view` (node.py:1446-1592) MINUS the
`mutual_box` branch:
- `validResponseEntry(entry: Map): Boolean` — `_valid_response_entry` port.
- `responseSigPayload(target, rkind, body, createdAt, responder): ByteArray`
  = `KotlinWire.canonical` of the 5 fields (created_at via PyFloat).
- `resolveEntry(entry, target, deviceBound: (identity, devicePub) -> Boolean, profileNames): ResolvedEntry`
  — if `public` AND `verifyRaw(device_pub, responder_sig, responseSigPayload(...))`
  AND `deviceBound(identity, device_pub)` → resolved to `profileNames[identity]
  ?: "friend-"+identity.take(8)`; else → alias (`aliasName(alias_seed)`,
  `aliasColor(alias_seed)`).
- `aliasName(seed)` / `aliasColor(seed)`.
- `Responses(reactions: Map<String,Int>, comments: List<Comment>)` where a
  `Comment(body, display, aliasColor: Int?, createdAt)`; reactions tally by
  `body` token over rkind=="reaction" entries; comments list rkind=="comment"
  entries.

### 3. Responses pass (in the decrypt pass)

For each stored `KIND_RESPONSES` message, key by its `target`; keep the
LATEST per `(author=cert.identity_pub, target)` (created_at,seq tie-break, the
prune's ordering). Decrypt via the existing `resolveWrap`→`unwrapKey`→
`decryptBody(responsesAad(author, target, createdAt))` path (the phone is
inline-wrapped when the record was aggregated after its enc key published;
older records fail-closed to "no responses"). Validate + resolve each entry →
a `Responses` per target post. Attach to the matching feed item.

### 4. Module + feed

`getFeed` items gain a `responses` field `{reactions: {token:count}, comments:
[{body, display, color, createdAt}]}` (or empty), computed in the same
in-memory decrypt pass, cached like `feedCache`. `index.ts` types it.

### 5. App.tsx

Under each post row: a reaction summary (each token + its count) and a comment
list (each: `display` in its `color` + the `body` text). Minimal
dev-dashboard aesthetic — no reacting/commenting, no avatars (name+color
only). Posts with no responses show nothing extra.

## Data flow

```
sync -> KIND_RESPONSES records stored (kind-agnostic, no blobs)
decrypt pass: per feed post -> latest KIND_RESPONSES targeting it
   -> resolveWrap + unwrapKey + decryptBody(responses_aad) -> entries
   -> per entry: validResponseEntry; public && sig-ok && device-bound -> name; else -> alias
   -> Responses{reactions:{token:count}, comments:[{body,display,color,createdAt}]}
feed item.responses -> App.tsx renders reaction summary + comment list under the post
```

## Testing

- **`responsesAad` vector gate (JVM):** extend `make_dmcrypt_vectors.py` with a
  real `KIND_RESPONSES` case (author, target, entries incl. one public-valid-
  sig + one private) — the phone decrypts it, `responsesAad` byte-matches, and
  the reaction tally + comment text are exact.
- **Signature verification (JVM, the security core):** a public entry with a
  VALID `responder_sig` on an enrolled device → attributed to the identity; the
  SAME entry with a forged/wrong sig → NOT attributed (alias); a valid sig on a
  device_pub NOT enrolled to the identity → NOT attributed (alias). Prove
  sig-alone is insufficient.
- **Alias derivation (JVM):** `aliasName`/`aliasColor` match `app.js` for
  committed seed vectors (guard the wordlists + the modulo math).
- **Entry validation (JVM):** malformed entries (bad rkind, oversized comment,
  bad-hex sig, reaction not in REACTION_TOKENS) fail-closed (dropped, no crash);
  latest-wins per (author,target).
- **On-device:** a friend's post with real reactions + comments shows the
  reaction summary + comments (public by name, private as alias) under it.

## Definition of done

The G20 feed shows reactions (counts) + comments under posts, with public
commenters attributed to real friends ONLY when their responder_sig +
device-binding verify, and private commenters shown as their alias. No
composing; seal_slots deferred; no hearth change. Desk-proven first (the
responses vector gate + the signature-verification gate + alias vectors).

## Risks / honest unknowns (resolve during build)

- **`_device_bound` exact semantics** — port node.py:1399+'s permissive/
  disprove logic faithfully against the phone's `messages` (identity_pub,
  device_pub) records; do NOT weaken to sig-only (hearth is explicit that
  sig-only is forgeable).
- **`_sig_ok` / the Ed25519 verify primitive** — confirm KotlinWire exposes a
  raw-Ed25519-verify over `(device_pub, sig, payload)` matching hearth's
  `sign_raw`/verify (B.1 already verifies message signatures — reuse that
  path). The signed bytes are the 5-field canonical, byte-exact.
- **Which records decrypt** — only `KIND_RESPONSES` aggregated AFTER the
  phone's enc key published are inline-wrapped to it; older ones won't decrypt
  (no responses shown for those posts). Acceptable, fail-closed. (No wrap_grant
  backfill exists for responses — maintain_wrap_grants is wall-posts only.)
- **Latest-wins selection** — a post's responses come from the LATEST
  `KIND_RESPONSES` its author published for that target; use the prune's
  (created_at, seq) tie-break, mirroring `wrapGrantsFor`'s latest logic.
- **`mutual_box` present but unused** — the entry carries it; the phone ignores
  it (seal_slots deferred). Confirm ignoring it never breaks validation
  (`_valid_response_entry` shape-checks it, so validate its shape but don't
  open it).

## Out of scope (named)

`seal_slots`/`try_open_slots` de-anonymization (private stays alias — a
follow-on); composing/reacting/commenting from the phone (the outbound slice);
moderation (remove/retract); comment avatars (name+color only); decrypting raw
`KIND_RESPONSE` submissions (the aggregated record is the view); any hearth
change.
