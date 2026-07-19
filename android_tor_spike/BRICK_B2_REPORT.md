# Brick B.2 report — decryption + readable history

**Status: DESK-COMPLETE. ON-DEVICE RUN PENDING (human-driven, August at the
G20).** B.1 proved the phone pulls its own-identity content ENCRYPTED (253
msgs / 19 blobs / 3 friends, on hardware). B.2 makes that content READABLE:
the phone generates + publishes an X25519 enc key, the desktop's new
`maintain_own_device_grants` sweep backfills wrap_grants for the phone's
existing own history, and the phone decrypts + displays it. All of this is
proven at the desk (vector gates, hearth pytest, an extended real-node
loopback gate). The G20 run itself is **[PENDING RUN]**.

Spec: `docs/superpowers/specs/2026-07-19-android-b2-decryption-design.md`
Plan: `docs/superpowers/plans/2026-07-19-android-b2-decryption.md`
Branch: `brick-b2-decryption`, base `f7e7571`, **13 commits**
(`f7e7571..ceaf1a6` = HEAD). NOT merged — awaiting this run + August's merge
decision.

## What Brick B.2 builds

- **`KotlinDmcrypt`** — port of `hearth/dmcrypt.py`'s unwrap/decrypt path
  (X25519 shared secret -> HKDF `_derive_kek` -> ChaCha20-Poly1305 unwrap of
  the content key -> ChaCha20-Poly1305 body decrypt), AAD built via the
  already-proven `KotlinWire.canonical`.
- **Phone enc-key provisioning** — X25519 keypair generated once, persisted
  in the phone's SQLite; a device-signed `enckey` message composed exactly
  as hearth's `make_enckey` and pushed as the sync MESSAGES phase's first
  real outbound WRITE (B.1's MESSAGES phase always sent empty).
- **`maintain_own_device_grants`** (new, isolated hearth sweep in
  `hearth/node.py`) — backfills wrap_grants re-wrapping the phone's own
  existing content to its published enc key. `maintain_wrap_grants` (the
  friend-facing sweep) is untouched — this is the first slice to modify
  production `hearth/`, and the change is deliberately isolated.
- **`DecryptPass`** — decrypts own posts/DMs via inline wraps or the
  backfilled wrap_grants, author-scoped to the phone's own verified signing
  identity (see final-review fix below), newest-first.
- **Feed UI** — minimal readable-text list on the existing dashboard
  (kind + timestamp + text), 3-way loading/empty/populated states with an
  explicit cold-start empty-state hint (not a silent blank).

## Desk gates (green)

- **dmcrypt vectors (Task 1):** `KotlinDmcrypt` unwrap/decrypt + AAD
  construction green against committed vectors generated from the real
  `hearth/dmcrypt.py`.
- **hearth pytest (Task 2):** `maintain_own_device_grants` — 6/6 new tests,
  full suite 1051/9-skip x2, including a dual-sweep+prune **fixpoint test**
  (2 satellites + a friend, proving convergence) and the two **REQUIRED
  security-negative tests**: friends' content is excluded from the backfill,
  and a revoked device's grants are skipped.
- **Extended desk loopback gate (Task 6):** a JVM test drives the REAL
  two-sync flow against a real seeded `HearthNode` over loopback TCP — the
  phone's enckey pushed over-the-wire (not script-injected), a
  maintained-signal handshake confirms the desktop actually ran the backfill
  sweep in between, and the grant path is proven independently of
  `DecryptPass` — i.e. this gate proves the phone genuinely **decrypts**
  real backfilled content, not just that the pieces compile together.
  35/35 x3.

## Plan deviations + final-review fix (for the merge decision)

Two deliberate deviations from the written plan, adopted during review, plus
one fix from the whole-branch final review:

1. **Full-coverage + carry-forward mint (Task 2, commit `9c82823`).** The
   plan's need-only mint sketch violated `store.py:490-496`'s
   full-coverage/prune invariant — it produced grant ping-pong between the
   own-device sweep and the friend sweep on wall posts under two-satellite
   churn. Fixed: a latest-grant coverage check (the prune's exact tie-break
   logic), full-coverage annotated mints, verbatim carry-forward of the
   latest grant's other entries, and the dual-sweep+prune fixpoint test
   above to prove it actually converges.
2. **Author-scoped `wrapGrantsFor` (Task 5, commit `20d7690`).** Scoped
   wrap-grant lookup to the verified signing identity, closing a
   hostile-mutual-friend grant-shadowing denial-of-render vector (a
   malicious mutual friend could otherwise shadow/withhold the render for
   content that wasn't theirs). 2 regression tests added.
3. **Final-review fix — `DecryptPass` own-author filter (commit
   `ceaf1a6`).** The whole-branch review found that, once the phone's enc
   key gossips and a friend-sweep-minted grant exists, friends' wall posts
   and friend DMs would have rendered in the feed — outside this slice's
   own-content-only scope. Fixed with an own-author filter and a biting
   regression test; verified closed by the reviewer. Whole-branch verdict:
   **Ready-to-merge (reviewer side) after this fix.** 40/40 + loopback 3/3.

## On-device run (the checklist August drives)

### a. Preconditions

- The G20 already has the B.1 build proven on it (253 msgs / 19 blobs / 3
  friends over Tor).
- **NOTE PROMINENTLY: the desktop's hearth process must be running THIS
  BRANCH's code for `maintain_own_device_grants` to exist.** It is a brand
  new sweep — a desktop running plain `main` will never backfill anything,
  and sync #2 (below) will silently show nothing new, indistinguishable
  from "nothing to backfill." Two ways to get there — August's call:
  1. Merge `brick-b2-decryption` to `main` first, then run the desktop's
     normal installed/main build; or
  2. Run the desktop node directly off a `brick-b2-decryption` checkout for
     this test, without merging yet.
  Either way, confirm the desktop process actually contains
  `maintain_own_device_grants` (e.g. check the running code's branch/commit)
  before starting the phone steps below.

### b. Install

The two builds from Task 8 are queued but blocked by a Play Protect dialog
(see `task-8-report.md`'s concern section):

1. On the G20, dismiss the pending Google Play Protect "send app for a
   security scan?" dialog (any of the three buttons proceeds).
2. From a fresh shell, run both installs:
   ```
   adb -s ZY32DLZQ2N install -r android_tor_spike\app\android\app\build\outputs\apk\debug\app-debug.apk
   adb -s ZY32DLZQ2N install -r android_tor_spike\app\android\app\build\outputs\apk\release\app-release.apk
   ```
3. If the dialog reappears for either install, dismiss it again and re-run
   that command (unconfirmed whether a third sideload of the same
   already-scanned signature re-triggers it — task-8-report.md flags this
   as possible).

### c. The two-sync flow

1. Open the app.
2. Tap **Sync now** (sync #1). This is the transport's first real WRITE:
   the phone composes + signs its `enckey` message and pushes it in the
   MESSAGES phase. The feed is likely still empty/unchanged after this —
   the desktop hasn't backfilled grants for this new enc key yet.
3. Wait for the desktop's next maintenance round (seconds, per its existing
   maintenance cadence) — `maintain_own_device_grants` runs and mints
   backfill wrap_grants for the phone's own existing history.
4. Tap **Sync now** again (sync #2) — this pulls the backfilled
   wrap_grants over the wire.
5. The feed should now show readable text from the real history (253 msgs
   at B.1 time; may have grown since then with normal desktop use).

### d. What to verify (the DoD checks)

- Feed is non-empty after sync #2.
- Texts are readable **and CORRECT** — spot-check several feed items
  against the desktop's actual content, not just "some text appears."
- Kinds and timestamps are sensible (post/dm, dates line up with known
  history).
- **Only OWN content renders** — no friend-authored items appear (this is
  the `ceaf1a6` fix; confirm it holds against real data, not just the
  regression test).
- Cold-start behavior: force-kill the app, reopen — the feed shows the "No
  decrypted items yet" empty-state hint (not a silent blank) until the next
  in-app sync, per the Task 7/8 cold-start contract.
- Sync stats line (messages/blobs/friends) still works — a B.1 regression
  check, confirming B.2's changes didn't disturb it.

### e. What to capture for the verdict

- Item counts (feed length after sync #2; how it compares to the 253-msg
  B.1 baseline).
- Spot-check results (which items were checked, whether text matched the
  desktop exactly).
- Any anomalies: timing between the two syncs, missing items, unexpected
  friend content, error strings, empty-state edge cases.

## Verdict

**[PENDING RUN]**

- Feed item count (after sync #2):
- Spot-check results:
- Anomalies:
- DoD checks (feed non-empty / correct / sensible kinds+timestamps /
  own-only / cold-start hint / sync stats regression):
- Overall: PROVEN / NOT PROVEN / PARTIAL —

## Known deferred items / follow-up tickets

From the whole-branch final-review triage (`f7e7571..1de3c08` + fix
`ceaf1a6`):

- **Important #2 (deferred, tracked fast-follow):** the enc-key publish
  marker is set on sync-Ok, not on confirmed node-acceptance — a rare,
  silent never-retry if the seq was burned but the node didn't actually
  ingest the enckey. Fix direction: store-confirmed publish via the echoed
  enckey.
- **Robolectric/instrumented coverage** for `SqliteSyncStore` + the module,
  consolidated into one ticket — needed before Brick C backgrounds this
  sync.
- **Fixpoint test's friend-rotation (Direction B) leg** — convergence is
  proven analytically in Task 2's review; an empirical rotation round is
  the remaining leg.
- **`EncKeyPrep` `toString` hardening** — the data class holds `encPriv`;
  `toString` could leak the key if ever logged (pre-existing pattern via
  `Pair`, low risk, cheap fix).
- **Merge-day production note:** if the live identity has a paired sibling
  device with an already-published enckey, the first sweep after merge is a
  one-time backfill burst (bounded, and is the intended feature); otherwise
  it's a no-op. Worth knowing before this runs against real accounts.
