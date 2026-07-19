# Brick B.1 report — content-sync transport

**Status: PROVEN ON HARDWARE (2026-07-19, G20).** "Sync now" against the
real home node over Tor pulled **253 messages, 19 blobs, 3 identities**
(own-identity content), each `SignedMessage` verified and each blob
hash-checked before storing in the phone's SQLite store. The desk loopback
gate had already proved the port end-to-end against a real node; the G20 run
confirms it on real content — including the SQLite `serialize→missingBlobs`
org.json path (the one seam the desk gate, which used the in-memory store,
could not cover): 19 blobs pulled cleanly.

Spec: `docs/superpowers/specs/2026-07-19-android-content-sync-brick-b-design.md`
Plan: `docs/superpowers/plans/2026-07-19-android-content-sync-brick-b.md`

## What Brick B.1 builds

The native Kotlin content-sync transport: after the proven AUTH, the phone
runs the real sync protocol (REVOCATIONS → DEFRIENDS → HAVE → MESSAGES →
BLOBS) against the home node over Tor, verifies every `SignedMessage`, and
durably stores the pulled own-identity encrypted messages, media blobs, and
friend list in a phone-side SQLite store. Decryption and rendering are B.2.

- **`SeenSet`** — per-device accepted-seq set (contiguous+sparse), mirrors hearth.
- **`SignedMessage`** — verify + `msg_id`, vector-gated against real hearth.
- **`SyncStore` + `InMemorySyncStore`** — reference store (ingest with
  is_known + signature + dedup + seq-reuse gates, HAVE summary, missingBlobs,
  hash-verified putBlob), JVM-tested.
- **`KotlinSync`** — the sync phases over a `Stream`.
- **`SqliteSyncStore`** — Android SQLite persistence (same contract).
- **Module `syncNow`/`getSyncStats` + a dashboard "Sync now" button.**

## Desk gates (green)

- **Desk loopback gate (the spine):** a JVM test drives the full Kotlin
  AUTH+sync against a REAL seeded `HearthNode`/`SyncService` over loopback
  TCP and asserts the store holds the node-computed expected content
  (messages/blobs/identities) — proving the port end-to-end before the phone.
- Kotlin JVM suite green (SeenSet/SignedMessage/SyncStore + the loopback gate
  + the prior Brick A tests). `hearth/`, `wire.ts`, `handshake.ts`,
  `wire_vectors.json` untouched.
- The desk gate caught two real bugs before the phone: a REVOCATIONS
  double-send desync (the handshake's acceptance probe *is* the sync
  revocations swap — fixed by `authOnlyOverStream`), and a BigDecimal
  `created_at` float that broke message signature re-verification (fixed via
  the proven `pyFloatRepr` path).

## Own-identity scope (honest framing)

B.1 pulls the **own-identity** content set (your own posts, DMs, friend list,
and their blobs) — which fully exercises the transport + storage spine.
Friends' encrypted content requires the phone to publish an enc key and
receive wrap-grants, which lands with decryption in B.2 (the same key that
decrypts is the one content is wrapped to). Content stays **encrypted** in
B.1; making it readable is B.2.

## On-device run (the app is already installed on the G20 from BB-8)

Prereq: the fixture (`spike_phone_fixture.json`) is in the app's files dir
(from the Brick A run; re-mint + push per `ON_DEVICE_CHECKLIST.md` if it was
removed). Your real desktop node holds your real own-identity content, so
"Sync now" pulls your actual posts/DMs/blobs.

1. Desktop Kreds online over Tor.
2. On the phone: open the app, **Start node** (bootstraps Tor — wait for a
   beat / the notification to show it's up).
3. Tap **Sync now**.
4. Read the result line + stats: on success, `synced: N msgs, M blobs, K
   friends`, and the stats line shows the totals. On failure, the reason
   localizes the layer (`tor not up` → Start node first; `auth: ...` →
   fixture/identity; `<stage>: <reason>` → the sync phase).

The content is stored encrypted (no rendering yet — that's B.2); the proof
is non-zero counts matching your desktop's own-identity content.

## On-device result (2026-07-19, G20)

**`synced: 253 msgs, 19 blobs, 3 friends`** — success on the first tap.
- 253 own-identity `SignedMessage`s verified (device-signature) and stored;
  plausible for months of daily Kreds use (every kind the node hands an
  own-identity device: posts, DMs, reactions, comments, enckeys, wrap-grants).
- 19 media blobs pulled, hash-verified, stored — confirms the SQLite blob
  path (the desk gate's one blind spot) works on-device.
- 3 identities (own + friend list, learned via HAVE own-device trust).
- Content is stored ENCRYPTED (decryption + rendering = B.2). The proof here
  is the transport: real content pulled + verified + persisted over Tor.

## Follow-ups (carried from reviews; whole-branch review = ready-to-merge, tracking only)

- **Acceptance-policy hardening (B.2):** `ingestMessage` gates on is_known +
  device-signature + dedup + seq (seq<1 + reuse), but NOT the enrollment
  `cert.verify()`, identity-match, `validate_payload`, tombstone, or
  per-device revocation retro-drop that hearth's `verify_message` applies.
  Non-exploitable for B.1 (own-identity pull over an authenticated,
  home-identity-pinned channel; the node applied the full policy before
  sending; it only ever accepts more, never wrongly rejects) — but a real
  fidelity gap to close in B.2.
- Thumbs empty-string guard (a hearth-consistency nit in missingBlobs;
  identical in InMemory + SQLite, so no desk-vs-phone divergence).
- `SyncResult.Ok` reports store TOTALS not this-round deltas (the "synced: N
  msgs" line shows cumulative counts on a second sync — cosmetic).
- `getSyncStats` blocking/unqueued + `SqliteSyncStore` helpers never closed
  (potential SQLITE_BUSY if a stats read overlaps a sync write; hygiene
  follow-up).
- **On the run, eyeball blob counts vs desktop** — SQLite's
  `serialize→missingBlobs` org.json round-trip is the one sync seam the desk
  gate (which uses InMemory) couldn't cover.
- B.2: enc-key publish + wrap-grants + decryption + friends' content + feed UI.
