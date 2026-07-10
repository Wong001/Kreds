# Compact invite encoding — design

Date: 2026-07-10
Status: approved direction (August, in-session, after the first two-machine
Tor test). Spec for review before writing-plans.

## Problem

`node.create_invite()` emits ~600 chars of JSON (full cert + onion + nonce
+ expiry + field names, all hex). In the first real two-machine Tor test it
nearly killed the friend-add: hand-typing across two clipboard-isolated PCs
took 6 minutes and didn't finish (pivoted to a USB stick). The invite is
the one human-copied artifact in the ceremony (response + final
auto-deliver over Tor); shrinking it is the fix.

## Constraint (honest, stated up front)

A 4-10 digit self-contained code is information-theoretically impossible:
the onion address alone is 256 bits, the nonce 128, and random keys don't
compress. Sub-100-char codes require a rendezvous point (a provider — the
thing Kreds architecturally avoids). So the target is **~80 characters of
base58, displayed truncated** — copy-paste, not hand-type. The real
low-friction path is share-over-an-existing-trusted-chat (one tap each) and
QR later; hand-typing between two side-by-side PCs is the rare edge case.

## Design

### 1. Move the cert out of the invite (the big win)

The invite's biggest chunk is the full `EnrollmentCert` (~130 bytes:
identity_pub + device_pub + device_name + enrolled_at + signature). It does
NOT need to be in the human-copied code:

- The invite travels over a channel the user already trusts. A cert in the
  invite never defended against a compromised channel anyway — an attacker
  who can rewrite the code can swap the whole thing (cert included), and B,
  with no prior knowledge of A's identity, cannot tell. What proves "the
  node I reached is the one who made this invite" is the **nonce** (only A
  and whoever A shared it with know it), not the cert. The cert is a label.
- So A's cert moves from the **invite** into the **final** message (A→B,
  auto-delivered over Tor). B verifies A's cert + A's signature at
  `complete_invite` exactly as strongly as before — just later in the flow.

Ceremony changes (crypto/nonces/signatures unchanged, only where the cert
travels):

- `create_invite`: emit compact invite WITHOUT cert; carry a 4-byte
  identity fingerprint instead (see §3).
- `respond_to_invite`: parse compact invite; no cert to verify here now
  (there's nothing B sends A that needs A's cert). Store `{onion, port,
  nonce, my_nonce, id_prefix, expiry}`; respond as today (B's cert + sig
  over A's nonce) but in compact form.
- `finalize_invite`: unchanged auth; the returned **final** now ALSO
  carries A's full cert.
- `complete_invite`: verify A's cert (from final) + A's sig (as today) AND
  the new **binding check** (§3).

### 2. Compact binary codec (`hearth/invitecodec.py`)

One codec for all three ceremony messages (invite / response / final), so
the manual fallback benefits too. Copyable form = base58 of a packed
binary blob (base58 = Bitcoin alphabet, omits look-alikes 0/O/I/l).

Common header: `version:u8 (0x01) | type:u8 (invite=1, response=2,
final=3)`.

Onion is stored as its 32-byte ed25519 pubkey and reconstructed to the full
`<56-base32>.onion` on decode (v3 rule: `base32(pubkey || checksum ||
0x03)`, `checksum = sha3_256(b".onion checksum" + pubkey + b"\x03")[:2]`).
`gossip_addr` ("xxx.onion:port") → strip ".onion", base32-decode 56 chars →
first 32 bytes = pubkey; port kept as u16.

**INVITE (type 1) body** (58 bytes → ~82 base58 chars total):
```
id_prefix:   4   bytes  (identity_pub[:4])
onion_pub:   32  bytes
port:        2   bytes  (u16 big-endian)
nonce:       16  bytes
expiry:      4   bytes  (u32 unix seconds; a 10-min TTL fits)
```

**RESPONSE (type 2) body**: `onion_pub(32) | port(2) | echoed_nonce(16) |
peer_nonce(16) | sig(64) | cert(...)` — B's cert + B's signature over A's
nonce + B's own onion/port + B's peer_nonce (mirrors today's response
fields).

**FINAL (type 3) body**: `echoed_peer_nonce(16) | sig(64) | cert(...)` —
now carries A's full cert (the moved-out piece).

**Wire cert (in response/final)** — packed, not JSON:
```
identity_pub: 32 bytes
device_pub:   32 bytes
enrolled_at:  8  bytes  (f64 or u64 ms)
sig:          64 bytes
name_len:     1  byte
device_name:  name_len bytes (utf-8; cosmetic, shown in UI)
```

Codec API:
- `encode_invite(id_prefix, onion_pub, port, nonce, expiry) -> str`
- `encode_response(...) -> str`, `encode_final(...) -> str`
- `decode(code) -> (type, dict)` — validates version, raises
  `ValueError("unrecognized invite")` on bad version / malformed base58.
- No JSON fallback: invites are ephemeral (10-min), no old codes exist to
  migrate; the version byte future-proofs the format.

### 3. Identity fingerprint (casual verification)

- Carried in the invite: `id_prefix = identity_pub[:4]` (4 bytes).
- **Display fingerprint** (human): `FP = base32_crockford(id_prefix)[:4]`
  → a 4-char uppercase string like `K7QX`. Deterministic from `id_prefix`,
  so A (from its own identity) and B (from the carried prefix) compute the
  same string.
- **UI:** A's invite card shows `kreds·invite·K7QX…<last4>` (full code
  behind a Copy button). On paste, B's app shows *"Connecting to someone
  whose ID starts with K7QX"* — the user glances at A's card and confirms.
- **Binding check** (machine, `complete_invite`): reject unless
  `final.cert.identity_pub[:4] == invite.id_prefix`. This forces any
  attacker into full substitution (they cannot redirect B while claiming
  A's fingerprint without grinding a 32-bit match) and makes the human
  check meaningful.

**Honest limit (documented in engineering-notes):** a 4-char fingerprint
is a *casual* integrity check — it catches wrong-person, accidental
cross-wires, and unsophisticated tampering. It does NOT stop a determined
attacker who both intercepts the trusted channel AND grinds a look-alike
identity key matching those 4 chars in real time (feasible in seconds on a
GPU). Real MITM proof needs comparing a long fingerprint or in-person key
exchange — the deferred follow-up below.

### 4. Follow-up (deferred, own task): full-identity verification

An option in the friend UI to view/compare the FULL identity fingerprint
(Signal-safety-number style — the complete `base32(identity_pub)`), for
users who want real MITM proof rather than the casual 4-char check. Both
sides display it; users compare in person or over a high-assurance channel.
Not in this slice.

### 5. Display helper (`hearth/web/app.js`)

- `shortInvite(code)` → `"kreds·invite·" + FP + "…" + code.slice(-4)` for
  the card; the Copy button copies the raw `code`.
- Enter-code path: after a successful parse (client shows nothing it can't
  verify), surface the "Connecting to someone whose ID starts with FP"
  line before/at connect.

## Scope / non-goals

- No rendezvous, no short-code-via-lookup (that's a provider; separate
  design if ever).
- No QR in this slice (separate near-term item; this makes the payload QR
  ready by shrinking it).
- The full-identity verification screen is the deferred follow-up (§4).

## Testing

- **Codec unit tests** (`tests/test_invitecodec.py`): round-trip each
  message type (encode→decode identity); onion pubkey→address→pubkey
  reconstruction matches the real gossip_addr; version-byte rejection;
  malformed base58 rejection; a real invite is < 100 chars.
- **Ceremony tests** (extend `tests/test_friend_add_*`): cert-out-of-invite
  path — full auto friend-add still lands both sides as mutual friends;
  the binding check rejects a final whose identity fingerprint ≠ the
  invite's id_prefix; expired/replayed invites still refused; the manual
  fallback round-trips all three compact messages.
- **Integration** (`tests/test_friend_add_integration.py`): the existing
  loopback two-node auto-add still passes end to end with the compact
  format, and a plain gossip round afterward still carries content
  (the first-frame-peek regression guard).
- Static/web: `shortInvite` present; the "ID starts with" copy present;
  Copy button copies the full code (not the truncated display).

## Rollout

Core + web change, 0.3.x material. Friend-add wire format changes (invite
/ response / final) — since real installs are only August's machines and
both upgrade together, and invites are ephemeral, no migration path is
needed; the version byte guards future format changes. Bundle with the
composer-scope bugfix + unread badge before the next signed publish.
