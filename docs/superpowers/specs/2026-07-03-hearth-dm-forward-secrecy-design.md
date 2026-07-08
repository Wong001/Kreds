# Hearth DM Forward Secrecy — Design ("hearth-dm v0.2, slow ratchet")

**Date:** 2026-07-03
**Status:** Approved (design discussion, this session)
**Basis:** hearth-dm v0.1 (spec `2026-07-02-hearth-dm-design.md`, shipped on `main`); concept doc v0.4 ("ratchet is the named follow-up"); ROADMAP "No forward secrecy in DM v0.1"
**Branch:** `dm-forward-secrecy` off `main`
**Workstream:** 2 of 4 in the session ordering recorded in `2026-07-03-deletion-hardening-design.md`

---

## Approach decision (locked this session)

Three approaches were weighed; **A was chosen**:

- **A — slow ratchet via enckey rotation + local key store (CHOSEN).** Devices
  rotate their static X25519 enckey on a period, publish via the existing
  latest-wins `enckey` message, and permanently delete retired private keys
  after a grace window. Zero envelope/protocol change.
- **B — per-device-pair symmetric hash ratchet (REJECTED).** Fine-grained on
  paper, but home-node mailbox delivery lags days; skipped-key caches must
  live as long as delivery can lag, so real FS granularity collapses to
  delivery latency — approximately A's window, at far higher complexity
  (per-device-pair chain state, bootstrap, out-of-order handling).
- **C — full Double Ratchet with prekeys (REJECTED).** FS + strong PCS, but
  4 sessions per friendship, prekey publication, session resets on
  enrollment/revocation, own-device fan-out copies — designed for
  server-ordered delivery this gossip mesh does not have. Wrong-sized for
  the architecture, not merely for the schedule.

**Constants:** `ROTATION_PERIOD = 24h`, `GRACE = 7 days`. Module-level
constants, overridable in tests. The grace clock starts at **retirement**
(the moment a new key replaces the old one), not at key creation — an
offline device does not lose its current key by being away; it rotates on
return and its mailbox backlog still decrypts inside the grace window.

## Threat model (goes verbatim in spirit into README/ROADMAP)

**Protected after this change:**
- A leaked/exfiltrated `keys.json` (or any future compromise of a device's
  static keys) can no longer decrypt DM envelopes captured in transit or
  retained in stores that are older than the rotation+grace window.
- Coarse post-compromise security: an attacker who had a device's keys but
  loses access stops being able to read new DMs after the next rotation
  (new envelopes wrap to keys the attacker never saw).

**NOT protected — stated plainly, never overclaimed:**
- A thief holding an unlocked device reads whatever the app can display.
  No ratchet changes this; the mitigation is app-lock / OS-keystore gating
  (out of scope, named follow-up) and DM expiry (already shipped).
- Theft of the entire node directory (storage key in `keys.json` + `dm_keys`
  in the db together) reads cached history — exactly as v0.1 did.
- Metadata (who talks to whom, when) — that is the Tor workstream (3 of 4).

The existing README/ROADMAP phrasing "no forward secrecy — a stolen unlocked
device can read DM history" conflates the transport-FS property (fixed here)
with the device-custody property (app-lock, later). This workstream corrects
the framing wherever it appears.

## Design

### 1. Key rotation (the ratchet)

- `DeviceKeys` gains `retired_enc`: a list of `{enc_priv, enc_pub,
  retired_at}` entries, persisted in `keys.json` (`from_json` defaults keep
  old files loadable; existing fields `enc_priv`/`enc_pub` remain the
  current keypair).
- `DeviceKeys.rotate_enc(now)` moves the current keypair into `retired_enc`
  with `retired_at = now`, generates a fresh pair via the existing
  `_gen_x25519_pair()`, and prunes retired entries with
  `now - retired_at > GRACE` (permanent deletion — this deletion IS the
  forward secrecy).
- `HearthNode` rotation trigger: at startup and once per gossip-loop round,
  if the latest self enckey message is older than `ROTATION_PERIOD`, call
  `rotate_enc()`, persist `keys.json`, and publish a fresh `enckey` message
  (existing kind, existing `ensure_enckey`-style publish). No new message
  kinds, no envelope format change, no sync protocol change.
- Decryption (`_dm_content_key` path): try the current `enc_priv`, then each
  retired `enc_priv` (bounded at ~GRACE/ROTATION_PERIOD ≈ 7-8 entries;
  X25519 ops are cheap). No hint field is added to wraps — zero protocol
  delta outweighs saving a few key-agreement attempts.
- `store.enckeys()` latest-wins tiebreak upgraded from bare `created_at` to
  `(created_at, seq)` — the existing backlog item, now in scope because
  rotation makes same-timestamp ties realistic.

### 2. Local DM key store (history survives key deletion)

- New table: `dm_keys(msg_id TEXT PRIMARY KEY, wrapped_key TEXT NOT NULL)` —
  the DM's 32-byte content key encrypted with ChaCha20-Poly1305 under a new
  per-device **storage key** (32 random bytes, generated at create/pair,
  persisted in `keys.json`, wiped by revocation self-logout like everything
  else there). AAD binds the msg_id (prevents cross-message key transplant).
- **Eager caching:** after any sync round that ingested changes, and once at
  startup, the node sweeps DMs involving self that have no `dm_keys` row,
  unwraps with current+retired keys, and caches. (A headless home node
  forwards envelopes regardless — caching only preserves that node's own
  display ability.)
- **Lazy fallback:** `dm_thread`/`dm_blob` try `dm_keys` first, fall back to
  envelope unwrap, and cache on success.
- **Deletion hygiene:** tombstoning a DM (delete tag, expiry sweep,
  retro-drop) also deletes its `dm_keys` row — a deleted DM leaves no usable
  key behind.

### 3. Honesty pass (docs)

- README "Encrypted messages" honest-limits paragraph rewritten: what the
  window-FS protects, what it does not (unlocked device, whole-dir theft),
  window sizes stated.
- ROADMAP: honest-status DM bullet updated (v0.1 → v0.2, framing corrected);
  shipped list gains the feature; "ratchet is the named follow-up" lines
  updated; app-lock/OS-keystore named as the follow-up for device custody.
- Concept doc: NOT edited by this workstream (it is August's document); a
  suggested one-line v0.4 changelog delta is included in the final summary
  instead.

## Testing

Unit:
- `rotate_enc`: current pair moves to retired with timestamp; fresh pair
  differs; pruning deletes only entries past GRACE; keys.json round-trips
  (old files without `retired_enc`/storage key still load).
- Unwrap with a retired key succeeds (envelope wrapped to gen-N key,
  decrypted after one rotation); fails after pruning (the FS property,
  asserted directly).
- `dm_keys`: cache round-trip; AAD mismatch (key cached under msg A cannot
  serve msg B); tombstone removes the row; storage-key absence degrades to
  envelope path.
- `enckeys()` tiebreak: same created_at, higher seq wins.

Integration (real sockets, existing suite conventions):
- Recipient offline across a sender-visible rotation: DM wrapped to the
  pre-rotation key still decrypts on the recipient inside grace (via
  retired key), and the content key gets cached.
- After grace pruning (test-scale constants), the envelope path fails but
  the thread still displays via `dm_keys` on the device that received it.
- Rotation propagates: after a gossip round, the sender wraps to the
  recipient's NEW key (assert the wrap set changes).
- The honest negative test: simulate a `keys.json`-only leak post-rotation
  (construct DeviceKeys from the current file with `dm_keys`/db withheld,
  retired keys pruned) and assert a pre-rotation envelope does NOT decrypt.

All existing 169 tests stay green.

## Out of scope (stated)

- Approaches B/C (rejected above, reasons recorded).
- App-lock / OS-keystore gating of key material (named follow-up).
- Group DMs, read receipts, re-wrap of history for late-enrolled devices
  (unchanged v0.1 boundaries).
- Tor / transport work (workstream 3).
- Encryption at rest for non-DM content.

## Success criteria

- The FS property is asserted by a test, not prose: pruned retired key +
  captured envelope = no decrypt; `keys.json`-only leak = no decrypt of
  pre-rotation traffic.
- History display survives rotation and pruning on devices that received
  the messages.
- Zero envelope/protocol change: a v0.1 peer still interoperates (its
  static key simply never rotates; wraps to it keep working).
- README/ROADMAP state the corrected two-property framing.
