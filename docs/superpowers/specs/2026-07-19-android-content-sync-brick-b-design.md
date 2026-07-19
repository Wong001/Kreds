# Android content sync — Brick B.1 (design, 2026-07-19)

Third slice of the Kreds Android client, after the Tor-dial spike (PR #1)
and Brick A (persistent background node, PR #2). Brick B ports the
post-AUTH content sync so the phone pulls its real feed data from the home
node over Tor. This spec covers **B.1: the native-Kotlin sync transport**
(pull + verify + store); decryption and rendering are B.2.

## Where this sits (Brick B decomposition)

The chosen architecture splits content sync into a background-capable
transport and a foreground-only decrypt/render layer (they have different
runtime needs — see the Brick A precedent: nothing runs in the background
without being native Kotlin, because there is no JS runtime after a
`START_STICKY` restart). Brick B therefore decomposes:

- **B.1 (THIS SPEC) — Kotlin sync transport.** Extend the proven handshake
  past AUTH through HAVE / MESSAGES / BLOBS, verify each `SignedMessage`,
  and store the still-encrypted messages + blobs + friend list in a
  phone-side SQLite store. Native Kotlin so Brick C can later run it in the
  background. Reuses `KotlinWire`/`KotlinHandshake`.
- **B.2 (later) — enc-key provisioning + decryption + friends' content.**
  The phone publishes an X25519 enc key and receives wrap-grants (which is
  what routes friends' encrypted content to it), then decrypts (content
  keys) and renders the feed. Enc-key work and decryption belong together —
  the same key that decrypts is the one content is wrapped to.
- **Brick C (later) — swap the heartbeat for this sync** inside the
  background service cadence.

## What B.1 proves (and what it does not)

**Proves:** the phone, after the proven AUTH, completes the real sync
protocol (REVOCATIONS → DEFRIENDS → HAVE → MESSAGES → BLOBS) against the
home node over Tor, verifies every `SignedMessage` signature, and durably
stores the pulled encrypted messages, media blobs, and friend list — the
whole transport + storage spine, natively, ready for Brick C to background.

**Does NOT (deferred):** decryption / making content human-readable (B.2);
enc-key publish + wrap-grants, hence **friends'** encrypted content (B.2 —
B.1 pulls the own-identity content set); rendering a feed UI (B.2); pushing
the phone's own composed content (the phone composes nothing yet — read-only
pull); running in the background (Brick C).

## The own-identity content scope (honest framing)

The phone's fixture is a *device* cert (Ed25519, for AUTH) with no X25519
enc key. The node's `messages_not_in` routes content by wrap-set:
**own-identity** messages (the phone shares the desktop's identity) flow
freely (`peer_identity == ipub` skips the wrap gate; DMs where the identity
is sender/recipient qualify), but **friends'** posts/DMs/responses route
only to a device in their wrap-set, which requires the phone to have
published an enc key and received wrap-grants. That is B.2 work (the enc key
is also what decrypts). So B.1's pull is the **own-identity content set**
(your posts, your DMs, your friend list, and their referenced blobs) — which
exercises the full transport + storage path — and friends'-content
entitlement arrives with B.2.

## Decisions (August, 2026-07-19)

- **Split:** native-Kotlin sync transport (background-capable, extends
  `KotlinHandshake`/`KotlinWire`); decryption + rendering is a separable
  foreground layer (B.2).
- **Full pull:** HAVE + MESSAGES + **BLOBS** in this slice (store the
  complete encrypted feed data incl. media, so B.2's render has everything).
- **Foreground-triggered:** a "Sync now" action runs one sync; the
  background-cadence swap is Brick C.
- **Storage:** SQLite (matches the desktop's durable store-encrypted model).
- **Testability:** `KotlinSync` depends on a `SyncStore` *interface* + a
  `Stream`, staying Android-free, so a JVM in-memory impl backs a desk
  loopback test against the real Python node (as `KotlinWire` is
  JVM-testable).

## Components

- **`KotlinSync.kt`** (new). Entry: `run(stream, store, fixture): SyncResult`
  — assumes the caller already completed `KotlinHandshake` AUTH on `stream`
  (or composes with it). As initiator, runs each phase via the same
  length-prefixed frames (`KotlinWire`):
  - **REVOCATIONS** (`_swap`): send `{"t":"revocations","revs":[]}`; read
    node's; if any revocation names the phone's own `device_pub`, return
    `SyncResult.SelfRevoked` (self-logout). Other identities' revocations are
    not ingested in B.1.
  - **DEFRIENDS** (`_swap`, initiator writes first): send
    `{"t":"defriends","notices":[]}`; read node's; ignore.
  - **HAVE** (`_swap`): send `{"t":"have","summary": store.summary(),
    "known": store.knownIdentities(), "peers": [], "addr": null}`; read
    node's HAVE; for each identity in its `known`, `store.addIdentity(...)`
    (own-device trust replicates the friend list); ignore its `peers`/`addr`.
  - **MESSAGES** (`_swap`): send `{"t":"messages","msgs":[]}`; read node's;
    for each, build `SignedMessage`, verify, `store.ingestMessage(...)`.
  - **BLOBS**: `_swap` `{"t":"blob_want","hashes": store.missingBlobs()}`
    (read node's want, give nothing); then `_swap`
    `{"t":"blobs","blobs":{}}`, read node's blobs, hash-verify each,
    `store.putBlob(...)`.
  - Returns `SyncResult.Ok(messages, blobs, identities)` (counts) or
    `SyncResult.Failed(stage, reason)`.
- **`SignedMessageKt.kt`** (new). `parse(dict)` + `verify()`: body =
  `KotlinWire.canonical({"type":"message","protocol":PROTOCOL,
  "identity_pub","device_pub","seq","payload"})`, verified against
  `device_pub`. `msgId()` for dedup (mirror the desktop's msg_id derivation).
- **`SyncStore` (interface)** + **`SqliteSyncStore.kt`** (Android impl) +
  an in-memory test impl. Interface:
  - `summary(): Map<String, Map<String, SeenJson>>`
  - `knownIdentities(): List<String>` ; `addIdentity(id: String)`
  - `ingestMessage(msg: SignedMessage): Boolean` (verified + deduped; returns
    accepted)
  - `missingBlobs(): List<String>` ; `putBlob(hash: String, data: ByteArray)`
    (hash-verified)
  - `stats(): Triple<Int,Int,Int>` (messages, blobs, identities)
  - SQLite tables: `identities(identity_pub)`,
    `messages(msg_id PK, identity_pub, device_pub, seq, kind, msg_json)`,
    `blobs(hash PK, data)`.
- **Module + UI.** `TorManagerModule` gains `syncNow()` (dials, runs
  `KotlinHandshake` then `KotlinSync` on the IO scope) and `getSyncStats()`;
  a `nodeSync` event carries the result. `App.tsx` gains a "Sync now" button
  and a stats line (messages / blobs / friends).

## Data flow

```
Sync now ─► TorEngine.dial(onion,9997) ─► KotlinHandshake AUTH (accepted)
        ─► KotlinSync: REVOCATIONS ─► DEFRIENDS ─► HAVE (learn friends)
              ─► MESSAGES (verify + ingest) ─► BLOBS (hash-verify + store)
        ─► SqliteSyncStore ─► stats ─► nodeSync event ─► App.tsx counts
```

## The `seen` summary — the one real fidelity risk

HAVE's `summary` is `{identity_pub: {device_pub: seen_json}}`, where
`seen_json` is the desktop's per-device **seen-sequence set** (the D2-spike
anti-replay structure — a prunable set of accepted seqs, NOT a high-water
mark). The phone must produce the exact shape `store.messages_not_in`
consumes. Getting it slightly wrong is mostly wasteful (the node resends
already-held messages — redundant, not corrupting), but the target is exact.
Mitigations: (1) B.1 starts from an empty summary (`{}` → node sends
everything, correct by construction) and rebuilds `seen` from ingested
messages; (2) the exact `seen_json` shape is pinned from `hearth/store.py`
during planning; (3) a committed cross-language `seen` fixture gates the
Kotlin format.

## Testing

- **Desk loopback gate (the spine, mirrors `test_handshake_desk.py`):** a
  Python node seeded with real own-identity messages + blobs; a JVM test
  drives `KotlinSync` (with the in-memory `SyncStore`) over loopback TCP
  through all sync phases against the real node; asserts the store ends
  holding exactly the expected messages/blobs/identities. Proves the port
  end-to-end on the desk before the phone.
- **JVM store unit tests:** dedup, the `seen` summary format (vs a committed
  fixture), `missingBlobs`, hash-verified `putBlob`.
- **`SignedMessage` verify** gated against a committed message vector
  (extends the existing `wire_vectors.json` approach).
- **On-device:** "Sync now" against the real home node → the stats line
  shows real counts (messages / blobs / friends).
- The existing gates stay green (Kotlin JVM 7/7, spike vitest 20/20, pytest
  9/9); `hearth/`, `wire.ts`, `handshake.ts`, `wire_vectors.json` untouched.

## Definition of done

The phone runs "Sync now" against the real home node, completes the sync
protocol over Tor, verifies and stores the own-identity encrypted messages +
media blobs + friend list, and the app shows non-zero counts — proven first
on the desk by the loopback gate, then on the G20.

## Risks / honest unknowns (resolve during build)

- **`seen_json` exact shape** (above) — the load-bearing fidelity point;
  pin it from `store.py` and fixture-gate it.
- **`msg_id` derivation** — dedup needs the phone to compute the same
  `msg_id` the desktop uses; mirror it from `hearth/identity.py`/`store.py`.
- **`SignedMessage` verify vs. entitlement** — B.1 verifies signatures and
  stores; it does NOT re-run the node's full `ingest_message` acceptance
  policy (revocation-aware seq checks, kind gates). For own-identity content
  from our own trusted node this is acceptable for a transport slice;
  note where the phone's ingest is thinner than the desktop's and defer the
  full policy to B.2/hardening.
- **Blob volume** — a real feed's media could be large; B.1 stores whatever
  the node gives within its existing `BLOB_GIVE_BUDGET` per round (multiple
  syncs drain it). Watch storage growth on-device.
- **SQLite on the Kotlin/Android side** — new infrastructure; the
  `SyncStore` interface keeps the protocol logic testable off-device.

## Out of scope (named, so it does not creep in)

Decryption / rendering / feed UI (B.2); enc-key publish + wrap-grants +
friends' content (B.2); pushing the phone's own composed content; the
background-cadence swap (Brick C); the full desktop `ingest_message`
acceptance policy; multi-round blob-budget tuning.
