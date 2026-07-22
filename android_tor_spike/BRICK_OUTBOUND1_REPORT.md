# Outbound Slice 1 — Compose Text+Photo Journal Post — on-device report

**The first slice where the phone WRITES.** The Kreds Android client can now
**compose and publish a text + photo journal post** (`kreds` scope) — composed,
encrypted, wrapped, and device-signed **natively in Kotlin** (no Python node),
stored locally so it appears in the phone's own feed instantly, and pushed on
the next sync so it reaches the desktop web feed and friends. This flips the
first part of the read-only seam OFF (the journal composer) and adds the first
non-GET route.

Branch: `brick-outbound-post` (base `8ba1b92` off main).
Spec: `docs/superpowers/specs/2026-07-22-android-outbound-compose-post-design.md`.
Plan: `docs/superpowers/plans/2026-07-22-android-outbound-compose-post.md`.

## What it does

- **Native compose crypto (the inverse of B.2's decrypt):** `Compose` generates
  a content key, ChaCha20-Poly1305-encrypts the body (`{text, blobs}`) and each
  photo blob, X25519-wraps the key to **every recipient device** (own + all
  friends' enc-keyed devices — `enckeys()` accessor), builds a `make_post`-shaped
  KIND_POST, and **device-signs** it. All byte-exact to hearth (post AAD, HKDF
  `hearth/dm-wrap/v1`, blob AAD `hearth/dm-blob/v1`).
- **`POST /api/post`** (first non-GET route): parses the composer's
  `multipart/form-data` (UTF-8 text fields — Danish ø/æ/å + emoji survive),
  runs each photo through `PhotoPrep` (**EXIF-strip** via Bitmap re-encode +
  downscale to ≤2560px + JPEG), composes, stores.
- **Push:** a durable **pending-outbound queue** — `Compose` enqueues each post;
  `SyncRunner` drains it into the sync's `outbound` and clears on success. The
  BLOBS phase now **sends** the photo (was: never sent). The queue stores the
  message in **canonical wire form** so re-send is byte-exact.
- **Seam:** only the journal `.composer` is revealed; the profile-wall composer
  (`#profile-wall-compose`), comments, reactions, and DM composer **stay hidden**.
- Recipient wrapping is at **compose time** (journal posts are not re-wrapped to
  friends by the desktop sweep — this matches desktop journal behavior exactly).

## Desk gates (all GREEN — Claude, pre-August)

| Gate | Result |
|------|--------|
| `:tor-manager:testDebugUnitTest` (full JVM suite) | **201 tests, 0 failures, 0 errors** |
| Crypto round-trips (`KotlinDmcryptTest` wrapKey/encryptBody, `KotlinBlobCryptTest` encryptBlob) | encrypt→decrypt-with-proven-inverse ✓ |
| **Loopback fidelity gate** (`SyncComposeLoopbackTest`) | a **REAL hearth node decrypts** the phone-composed post's **text + friend-wrap + photo blob** (real `dmcrypt`, AEAD-verified) ✓ |
| `MsgJsonTest` (canonical serialize fidelity, incl. small-magnitude regression) | 3/3 ✓ |
| `npx vitest run test/web-readonly-seam.test.ts` | **7/7** (journal composer revealed, everything else still hidden) |
| `npx tsc --noEmit` | 14 errors, **all pre-existing** `@types/node` in `test/`+`tools/` — 0 new |
| `:app:assembleRelease` (NDK r27.1; `copyHearthWeb` synced the seam edit) | BUILD SUCCESSFUL, installed on G20 |
| Per-task reviews + fix waves | all APPROVED (2 review-driven fix waves: Compose blob coverage; pending-queue canonical fidelity + coverage + onOpen migration) |
| All commits | trailer-clean, lowercase `feat/fix/docs(outbound)` |

**Crypto fidelity is desk-proven against a real node:** the loopback gate is not
a self-test — a genuine hearth `HearthNode` subprocess ingests the phone's
pushed message + blob over the real wire protocol and decrypts both with its own
device key. If the compose crypto were a byte off, the node would fail the AEAD
tag / signature and the gate would fail, not pass.

---

## On-device DoD — G20 (August drives the compose)

**Field lessons (do these first):**
- Desktop node on **`serve --tor`**, unlocked. Have **at least one friend**
  whose enc-key has synced to the phone (so the post can be friend-wrapped).
- Install the **RELEASE** apk (already installed). Debug → "Unable to load script".
- The photo picker: the first time you tap the composer's photo/attach control,
  Android's file chooser opens via the WebView — **grant** gallery/camera
  permission if prompted. (If the picker never opens, that's the WebView
  file-chooser integration gap — tell me.)

### Steps

1. Open the app → land in the **Journal**. The **composer is now visible** (it
   was hidden in slices 1–3).
2. Type some text — **include a Danish character (ø/æ/å) or an emoji** to
   confirm UTF-8 — and **attach a photo**. Submit.
3. Let the phone **sync** (it syncs on open + every 15 min; reopen or wait for a
   cycle to push).

### Checklist — tick each

- [ ] The journal **composer is visible** and accepts text + a photo.
- [ ] After submit, the post appears in the **phone's own Journal immediately**
      (text + photo), before any sync.
- [ ] **Danish/emoji text renders correctly** (not mojibake).
- [ ] After a sync, the post appears on the **desktop web feed** — same text,
      same photo.
- [ ] The post appears in a **friend's feed** (friend-wrapped at compose).
- [ ] The delivered **photo has no EXIF/GPS** (re-encoded) and displays.
- [ ] **Regression:** reactions, comments, the DM composer, and profile Arrange
      are **still hidden** (only the journal composer is enabled).
- [ ] No token/CSP errors in `adb logcat` while composing.

### Verdict (August to fill)

> _(pass / partial / fail + notes — what posted, whether it reached the desktop + friend, photo + EXIF, any surprises)_

## After the run

On a **pass**, this is the first outbound slice — whether it merges to public
main is **your call**. Then the next write slices (reactions/comments, DM-send,
photos-in-profile, `inner` scope).

## Honest boundary

- **Journal, `kreds` scope, text + photo only.** `inner` scope, reactions,
  comments, DM-send, story compose, profile edit, deletes, albums are each a
  later slice (all still hidden by the seam).
- **JPEG, not AVIF** (the phone has no AVIF encoder; JPEG displays identically
  everywhere). **No server-side thumbnails** (full-image fallback, as B.2d).
- A journal post reaches a friend only if the phone held that friend's enc-key at
  compose time — the desktop does **not** re-wrap journal posts to friends (by
  design: "a journal is a moment in time"). Same as desktop behavior. `sync on
  open + every 15 min` means the phone is usually current.
- **No revocation modeling** — the phone can't exclude a friend's *revoked*
  device from the wrap set (it processes no revocations). Pre-existing gap.

## Follow-up tickets (non-blocking)

- **Revocation-aware `enckeys()`** — needs the phone to process revocations.
- **`inner` scope** — needs KIND_RING processing (same gap as profile ring/since).
- **PhotoPrep large-image OOM** — no `Bitmap.recycle()`/`inSampleSize`; a very
  large source photo decodes full-res before downscale. Watch on-device with a
  big camera photo.
- **WebView file-chooser** — camera-capture UX + permission flow; verify on-device.
- **Multipart hardening** — size caps + malformed-part fuzz; header 50-cap can
  break mid-block (pre-existing).
- **Push-side `BLOB_GIVE_BUDGET`** — the phone's give-side has no frame-size cap
  like hearth's (low-risk for a single-post push).
- **Pending-queue clear-on-Ok** — clears on sync success; a summary-driven push
  would be more self-healing (matches hearth) — future hardening.
- **`onUpgrade` is destructive** — dormant (DB_VERSION frozen at 1, `onOpen`
  ensures tables, `onDowngrade` no-op guard added); any future real schema
  change needs a non-destructive migration, not a version bump against that body.
- **Robolectric** — `SqliteSyncStore` SQL (incl. the new `pending_outbound` +
  `MsgJson` on the SQLite path) is JVM-covered only via `InMemoryStore` + the
  extracted pure `MsgJson`; a Robolectric pass is the standing hardening ticket.
