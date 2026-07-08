# Hearth Vertical Slice v0.1 — Design ("three nodes on a desk")

**Date:** 2026-07-02
**Status:** Approved (design discussion, this session)
**Basis:** `hearth_concept_capture_v0_3.md` (D1/D2/D3 locked decisions) + `hearth_d2_spike/SPIKE_REPORT.md` (binding findings)
**Pace note:** May span multiple sessions; build order below is the sequence, not a schedule. One real constraint: the Fable 5 collaboration window closes **July 7, 2026** (end of free access) — aim to have the slice demonstrable before then.

---

## Scope decisions (locked this session)

1. **Vertical slice, local** — a real Hearth node daemon with persistent store, D2 identity (spike fixes implemented), D3 deletion tags, and friend-to-friend gossip over real TCP sockets. No Tor yet; transport is swappable by design.
2. **Client surface: local web UI** — each node serves a localhost page; feeds update live.
3. **Photos in** — content-addressed blobs, signed, synced, rendered, covered by deletion.
4. **Topology: 1 process = 1 device** — each running process is one enrolled device with its own store and ports. Demo cast: `wong-phone`, `wong-homenode`, `freja-phone`.

## Success criterion

Three node processes on one machine. Wong and Freja friend-add via the QR ceremony (copy-paste standing in for the camera). Posts with photos gossip between them over TCP. Deletion tags propagate and compliant nodes forget. A device revocation kills the stolen phone across the network, including retro-drop of already-accepted messages. Watched from two browser tabs. All signatures real Ed25519. The spike's two ambushes (seen-set, retro-drop) are production behavior.

---

## Package layout

```
hearth/                  ← product package (new code)
  identity.py            ← evolved from spike: seen-SET verifier, retro-drop, always-strict
  messages.py            ← wire objects: post, profile, deletion tag; canonical JSON; content-addressed IDs
  store.py               ← SQLite per node: messages, blobs, friends, devices, seen-sets, tombstones
  sync.py                ← gossip protocol over TCP; transport behind an interface (Tor slots in later)
  node.py                ← daemon: wires store + identity + sync, background gossip loop
  api.py                 ← localhost HTTP + WebSocket (FastAPI + uvicorn)
  web/                   ← single-page UI: index.html + app.js + style.css, no build toolchain
  cli.py                 ← `init`, `run`, `demo` (launches the three-node cast pre-wired)
tests/                   ← unit + integration
hearth_d2_spike/         ← frozen historical artifact; NOT imported by product code
```

Dependencies: `cryptography`, `fastapi`, `uvicorn` (standard extra for WebSocket), `pytest`. Python 3.12, existing `.venv`.

---

## Identity layer (`hearth/identity.py`)

Same object model as the spike — enrollment certs, revocation certs with `last_valid_seq`, per-device monotonic seq, canonical-JSON signing, QR payload with scanner-supplied nonce — with the three v0.3 binding findings implemented:

1. **Seen-set, not high-water mark** (Ambush 2). Verifier accepts any *unseen* seq, rejects reuse. Persisted per friend-device as `(lowest_contiguous_seq, sparse_set_above)`; compacts as gaps fill. Out-of-order gossip delivery is legal.
2. **Retro-drop** (gossip-lag window). On revocation ingest: re-evaluate accepted messages from that device; drop those with `seq > last_valid_seq` — content deleted, tombstoned against resurrection, UI notified.
3. **Always strict.** No `track_seqs=False` mode in the product verifier; the naive configuration exists only in the spike as attack documentation.

Backdating protection = seen-set (no reuse of delivered seqs) + revocation `last_valid_seq` (nothing new above N), per the spike report.

Hardening note carried from v0.3: identity-key-behind-OS-keystore is real-device work, out of scope for the slice; the identity key replica sits in the node's data dir, stated plainly.

## Data model (`hearth/messages.py`, `hearth/store.py`)

**Message envelope** — the spike's `SignedMessage` shape: enrollment cert + per-device seq + payload, device-signed, self-contained. **Message ID = SHA-256 of the canonical signed body** (content-addressed, forgery-evident).

**Payload types:**

| Type | Contents | Semantics |
|---|---|---|
| `post` | text, blob refs (list of SHA-256), optional `expires_at` | The feed unit |
| `profile` | display name | Latest-wins per identity |
| `delete` | target message ID | Valid only from the target's authoring identity; compliant node deletes content + unreferenced blobs, keeps tombstone, re-gossips the tag |

**Expiry** — second D3 mechanism: a node-local sweeper tombstones posts past `expires_at`; no tag involved.

**Blobs** — content-addressed by SHA-256, ≤ 5 MB each for the slice, referenced from posts by hash, garbage-collected when unreferenced.

**Store** — one SQLite file per node under `run/<node-name>/`. Tables: `identities` (self + friends), `devices` (certs, revocations, persisted seen-sets), `messages`, `blobs`, `tombstones`, `peers` (gossip addresses). Ingest is idempotent and transactional.

## Sync protocol (`hearth/sync.py`)

Length-prefixed JSON frames over TCP. Transport behind an interface — `connect(address) → framed stream` — so a Tor SOCKS dialer is a swap-in later (D1 standing requirement). Session shape, in order:

1. **HELLO/AUTH** — mutual device authentication: each side presents its cert chain and signs the peer's fresh nonce with its device key (the QR verification machinery, reused). Unknown identities are refused before any data flows — the structural anti-stranger property enforced at the socket layer.
2. **REVOCATIONS** — full exchange of revocation certs, always before content (v0.3: revocations are highest-priority gossip).
3. **HAVE/WANT** — per shared identity+device: compact seen-set summaries; each side computes what the peer is missing.
4. **MESSAGES** — missing messages stream across; every message runs the full acceptance pipeline on ingest (cert chain, device sig, revocation check, seen-set) — never trusted because a friend relayed it.
5. **BLOBS** — have/want by hash for blobs referenced by newly accepted posts. Base64-in-JSON for the slice (noted inefficiency, irrelevant on localhost).

**Gossip loop:** each node re-syncs with every known peer address every few seconds (configurable interval).

**Replication rule — friends-replicate-your-feed** (SSB model, concept-doc BORROW): your own devices replicate you fully; friends' posts are replicated among all their friends' devices. `freja-phone` can receive Wong's posts from `wong-homenode` when `wong-phone` is offline. This mesh property is what bounds the revocation gossip-lag window.

**Error handling:** malformed frame → log + drop connection (peer retries next gossip round); oversized blob → reject; failed auth → refuse session; all ingest idempotent so retries are safe.

## Node daemon + API + web UI (`hearth/node.py`, `hearth/api.py`, `hearth/web/`)

Each node: gossip TCP listener + localhost HTTP listener (FastAPI + uvicorn). WebSocket pushes store changes (new post, deletion, revocation, retro-drop) to the page live.

Single dark-themed page (concept doc: good dark default), four panels:

- **Feed** — merged own + friends' posts, photos rendered, expiry countdown, delete button on own posts.
- **Compose** — text + photo attach + expiry selector (1 h / 1 d / 7 d / never).
- **Friends** — the QR ceremony: "Show my code" renders the payload a real QR would carry (cert chain + device-signed scanner nonce + gossip address; onion address stands down to `127.0.0.1:port`). "Scan" is a paste box. Mutual, both directions.
- **Devices** — enrolled devices for this identity; "pair new device" (copy-paste standing in for the secure local channel — flagged in-UI, since the pairing package carries the identity-key replica); revoke button per device.

## Testing

- **Unit:** spike's adversarial suite ported against the production verifier — the out-of-order test now must PASS (Ambush 2 resolved). New: retro-drop; deletion-tag semantics (wrong-identity tag rejected; tombstone blocks resurrection); expiry sweep; seen-set persistence across restart; blob GC.
- **Integration:** two real nodes on ephemeral ports — friend-add, post propagation, photo transfer, deletion propagation, revocation + retro-drop, offline-device catch-up via the third node.
- **Demo:** `python -m hearth demo` launches the three-node cast pre-wired.

## Build order

Risk concentrates in the sync protocol; the order isolates it. Each stage is independently testable.

1. `messages.py` + `identity.py` (production verifier w/ seen-set + retro-drop) — spike suite ported and green.
2. `store.py` — persistence, tombstones, expiry sweeper, blob GC.
3. `node.py` core + `api.py` + `web/` on a **single node** — compose/feed/delete working locally.
4. `sync.py` — auth, revocations-first, have/want, messages, blobs; two-node integration tests.
5. Friend-add + device-pairing ceremonies end-to-end; three-node demo; polish.

## Out of scope (stated, not hidden)

Tor itself (interface-ready, not wired); transport encryption (frames signed but plaintext — mandatory before any real network, fine on localhost); encryption at rest; OS keystore/biometric gating of identity key; notifications (iOS fork remains open per concept doc); household multi-identity; profile customization beyond display name; performance work. Each is follow-up, none silently dropped.

## Deliberate deviations from "real Hearth" (all flagged in-product where visible)

- Copy-paste stands in for: camera QR scan (friend-add) and the secure local pairing channel (device enrollment).
- `127.0.0.1:port` stands in for the onion address in the QR payload.
- Plaintext TCP stands in for Tor circuits.
