# Visual Parity — Slice 3 (Me/profile + 4-column wall) — on-device report

**Third slice of the shared-UI visual-parity arc.** The phone's **Me / profile
view** now renders hearth's real desktop profile page — the header (banner +
avatar + bio + accent), the **4-column bento wall** (pinned + flowing
post-blocks with pin/span/text_style, plus folded multi-photo album decks), and
the profile journal rail — read-only, served by extending the slice-1/2
`LocalApi` with one read-only `/api/profile/{identity_pub}` route over the
phone's native store (decrypt-on-read). Reachable via the existing **Me** tab.
No composing / arranging / writing — hidden by the read-only seam.

Branch: `brick-vp3-profile` (base `069ff0b` off main).
Plan: `docs/superpowers/plans/2026-07-21-android-visual-parity-profile-slice3.md`.

## What it does

- **`/api/profile/{identity_pub}`** — the profile view in hearth's EXACT
  `profile_view` shape: `{name, bio, accent, avatar, avatar_shape, avatar_size,
  avatar_align, banner, banner_pos, identity_pub, mine, ring, since, wall,
  journal}`. The nine display fields are selected BY NAME from the stored
  KIND_PROFILE record (so incidental `kind`/`created_at` keys never leak), each
  with hearth's default fallback. **Guarded, not load-bearing:** unlike
  `/api/feed`/`/api/kreds`/`/api/conversations` (awaited every refresh tick, a
  404 there aborts the whole app), `/api/profile` is wrapped in app.js's
  `openProfile` try/catch → `fallbackProfile`, so its null→404 is the DESIGNED
  degrade path.
- **The 4-column wall** — post-blocks (feedRow shape + pin/span/text_style) and
  folded album pseudo-blocks, assembled by a faithful Kotlin port of hearth's
  `profile_view` / `_fold_album_members` / `_default_span` (verified line-by-line
  against `node.py`). Members of an album are folded out of the loose list;
  video members skipped; empty albums dropped; `created_at`/`scope_newest` taken
  from the newest member; the list re-sorted newest-first. The existing 4-column
  CSS (`#profile-wall{grid-template-columns:repeat(4,1fr)}`) is unchanged — the
  wall scales cells, it never reflows to fewer columns, by design.
- **The profile journal rail** — this identity's `journal`-placement posts in
  the feedRow shape WITH responses (collapsed reaction/comment counts), via the
  same `DecryptPass.responsesPass` the journal feed uses.
- **Three plaintext store accessors** — `profileRecord`/`profileLayout`/`albums`
  read the plaintext KIND_PROFILE / PROFILE_LAYOUT / ALBUM messages, latest-wins
  by `(created_at, seq)` (albums per-album-id), mirroring `profileNames()`'s
  seq-column SQL. No DecryptPass (these payloads are plaintext).
- **Read-only seam gap closed** — the friend-profile **Move** (`ring-move`) and
  **Unfriend** (`#profile-actions .btn-danger`, scoped so the Settings
  "Disable App-lock" `.btn-danger` survives) buttons are now hidden under
  `body.readonly`. The **Message** button stays reachable (it leads to the
  already-read-only Messages view).
- `DecryptPass.kt` untouched; blobs served by the EXISTING `/api/post-blob` /
  `/api/blob` routes (this slice adds no blob route). Decrypt-on-read: `keysCache`
  is in-memory, mirroring `feed()`; no decrypted bytes/keys to disk.

## Desk gates (all GREEN — Claude, pre-August)

| Gate | Result |
|------|--------|
| `:tor-manager:testDebugUnitTest` (full JVM suite, incl. new `SyncStoreTest` accessor tests + `LocalApiTest` wall-assembly/`profileJson` tests) | **185 tests, 0 failures, 0 errors** |
| `npx vitest run test/web-readonly-seam.test.ts` (extended read-only-seam guard) | **6/6** |
| `npx tsc --noEmit` | 14 errors, **all pre-existing** `@types/node` gaps in `test/`+`tools/` files — **0 in RN app source, 0 new** |
| `:app:assembleRelease` (NDK r27.1; `copyHearthWeb` re-syncs the app.js/style.css edits into `assets/www/`) | see on-device section |
| Per-task reviews (accessors / wall builders / profile route / seam) | all **APPROVED** (Task 4 zero issues) |
| Whole-branch review (opus) | **READY TO MERGE** — zero Critical/Important; 5 Minor → follow-up tickets |
| All commits (subjects + bodies) | trailer-clean, lowercase `feat/docs(vp3)` |

**Shape fidelity + read-only integrity, desk-proven:** the profile top-level +
wall + album fold byte-match hearth's `profile_view` (verified against
`node.py`/`store.py`/`messages.py`); the load-bearing refresh chain is untouched
(no new always-awaited endpoint); every write affordance is hidden by the seam
and every write route is an unrouted safe-404 (`handle()` rejects non-GET, so
app.js's fire-and-forget `POST /api/wall-autoplace` no-ops); decrypt-on-read,
zero disk writes.

---

## On-device DoD — G20 (August drives)

**Field lessons (do these first):**
- Desktop node on **`serve --tor`**, unlocked. A bare `hearth app` has Tor OFF
  and the sync EOFs; a **locked** node refuses sync as a bare EOF.
- Install the **RELEASE** apk (debug → "Unable to load script").
- Play-Protect "install anyway" may need a physical tap.

**Set up a composed profile first (so the wall has something to show):** from the
desktop, arrange your own profile into a page — **pin at least one block, leave
at least one block flowing, include at least one photo block, at least one
pure-text block, and a folded album (2+ photos grouped)**. Also have **at least
one friend whose profile has synced** to the phone. Then let the phone sync
(background or reopen).

### Checklist — tick each

- [ ] Tapping **Me** opens the own profile: **banner + avatar + bio + accent**
      render; no crash / no blank page.
- [ ] The **4-column wall** renders: pinned blocks sit where arranged, flowing
      blocks fill the grid, photos load (via `/api/post-blob`), pure-text blocks
      show their `text_style`, and a multi-photo album renders as **ONE folded
      deck** (not scattered single photos).
- [ ] The **profile journal rail** renders this identity's journal posts (with
      any collapsed reaction-count line).
- [ ] The own profile is **read-only**: no composer, no **Arrange** button, no
      Settings **cog**, no add-friend **"+"**.
- [ ] Tapping a **friend** opens THEIR profile: header + wall render; the
      **Message** button is present; there is **NO Unfriend** button and **NO
      "Move to inner/kreds"** button (both hidden by the seam); tapping around
      produces **NO unhandled-rejection JS error** in `adb logcat`.
- [ ] **Regression:** the Journal feed (slice 1) and the Messages view (slice 2)
      still render — the profile route did not disturb the always-awaited
      `refresh()` chain.
- [ ] **No token/CSP errors** in `adb logcat` while browsing the profile + wall.

### Verdict (August to fill)

> _(pass / partial / fail + notes — what rendered, any surprises)_

## After the run

On a **pass**, whether this slice merges to public main is **August's call**
(mirrors the B.2-era "merge is August's call" pattern). Then slice 4 (mobile
polish / theme) or the composing/outbound arc.

## Honest boundary

- **Read-only Me/profile only.** Header/banner/accent + the 4-column wall + the
  journal rail render; composing, arranging, and all writes are hidden and out
  of scope.
- **The wall never reflows** — it scales cells within the fixed 4 columns, by
  design (matches the desktop reference; no wall CSS was changed).
- **Friend `ring`/`since` are the reused default** (`"kreds"` / `0`), NOT real
  KIND_RING membership — see ticket T3. Own profile is exact.

## Follow-up tickets (Minor — none blocking merge; from the whole-branch review)

- **T1 — `device_pub` tie-break.** `profileRecord`/`profileLayout`/`albums` (and
  the pre-existing merged `profileNames`) break `(created_at, seq)` ties WITHOUT
  hearth's 3rd key `device_pub` (`store.py:556/587/613`). Only bites an EXACT
  `(created_at, seq)` collision across two devices of one identity
  (practically-unreachable: `time.time()` microsecond match + independent
  per-device seq counters aligning). Fix = add the 3rd key across all **four**
  accessors together (so they stay consistent with `profileNames`). Fold in the
  T-name one-liner below.
- **T1b (fold into T1) — `name` fallback.** `profileJson` defaults a missing
  `name` to `""`; hearth (`store.py:563`) falls back to `identity_pub[:8]`.
  Dead in practice (`make_profile` mandates `name`; the own-no-record path
  passes a resolved name). One-liner: `record["name"] ?: identityPub.take(8)`.
- **T2 — spans/pins empty-map robustness.** `wallBlockJson`/`wallJson` use
  presence (`!= null`) where hearth uses Python truthiness (`or`), so a malformed
  empty-map `{}` span would be emitted as a keyless span object instead of
  falling back to the default. Unreachable via `make_profile_layout`
  (author-validated `{w,h}` values); cosmetic-only if a hostile layout ever
  reached it. Fix = `isNotEmpty()` guard.
- **T3 — friend-profile ring/since parity.** The phone doesn't process KIND_RING,
  so a friend's `ring`/`since` are the reused `kreds()`-route default; a friend
  actually in your **inner** ring renders as "Kreds" and the "· since …" line
  never shows. Real values need new `rings()`/`ring_since()` SyncStore accessors
  + the circle view (a later slice; the hardcode is now marked deliberate in
  `profile()`). Own profile unaffected.
- **wall-autoplace (never-arranged posts).** `POST /api/wall-autoplace` is not
  implemented (fire-and-forget, safe 404); a never-arranged wall renders via the
  newest-first flow grid — a legitimate designed-for state — but auto-pin-to-top
  is unbuilt.
- **STATE.disconnected always empty.** `/api/state` hardcodes an empty
  `disconnected` list, so app.js's `fallbackProfile` "no longer connected" branch
  is unreachable on the phone; an unfriended-on-desktop identity isn't
  distinguishable from an unknown one yet.
- **SQLite accessor coverage.** `profileRecord`/`profileLayout`/`albums` are
  JVM-tested only against `InMemorySyncStore` (SyncStoreTest is InMemory-only,
  matching every prior accessor); a Robolectric pass over the real
  `SqliteSyncStore` SQL is a later hardening ticket.
- **Profile-header + comment-author avatars.** Still deferred (no avatar-blob
  accessor threaded through the wall/rail; the wall's own photos work via
  `/api/post-blob`).
