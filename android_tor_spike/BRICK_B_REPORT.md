# Brick B.1 report — content-sync transport

**Status: DESK-COMPLETE, on-device run PENDING.** All 8 code tasks are
implemented and reviewed; the desk loopback gate proves the whole sync port
against a real node. The remaining step is the human-driven G20 run.

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

## [PENDING RUN] Verdict + counts

_Filled after the G20 run:_
- Did "Sync now" succeed (`synced: N msgs, M blobs, K friends`)?
- Do the counts match your desktop's own-identity content (roughly)?
- Any failure stage/reason.

## Follow-ups (carried from reviews)

- Thumbs empty-string guard (a hearth-consistency nit in missingBlobs).
- The full `ingest_message` acceptance policy beyond signature/dedup/seq/
  is_known (deferred to B.2/hardening).
- B.2: enc-key publish + wrap-grants + decryption + friends' content + feed UI.
