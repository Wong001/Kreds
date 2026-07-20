# Brick B.2c report — friends' content readable

**Status: PROVEN ON HARDWARE (G20, 2026-07-20).** All 4 implementation tasks
are done and reviewed; the release APK is installed on the G20 and the run
passed: the feed shows readable friend content with author names and
timestamps. All four verification points passed (see Verdict).

Spec: `docs/superpowers/specs/2026-07-19-android-b2c-friends-content-design.md`
Plan: `docs/superpowers/plans/2026-07-19-android-b2c-friends-content.md`
Branch: `brick-b2c-friends-content`, base `79eb9fa`, **6 commits**
(`79eb9fa..40c815f` = HEAD). STACKED on unmerged `brick-b2-decryption`. NOT
merged — merge is August's decision, after both bricks together.

## What Brick B.2c builds

B.2 made the phone's OWN content readable. B.2c extends that to content the
phone is entitled to as a friend/recipient:

- **`maintain_received_dm_grants`** (new, isolated third hearth sweep in
  `hearth/node.py`) — recipient-signed backfill: re-wraps a received DM's
  content key to the RECEIVING identity's own devices, signed by the
  recipient, never touching `maintain_wrap_grants` (friend-authored-content
  sweep) or `maintain_own_device_grants` (B.2's own-content sweep), both of
  which remain byte-for-byte unmodified across all 6 commits.
- **Entitlement `DecryptPass`** — friend wall posts decrypt via existing
  author-signed wrap_grants (stock `maintain_wrap_grants`, unmodified);
  received DMs decrypt via the new recipient-signed grants; own content
  keeps working exactly as B.2 left it. Four independent barriers make the
  rule watertight: signer-set filtering at the store layer, kind-gated set
  construction, an unforgeable own-identity signer check, and AAD binding.
- **Author names on the feed** — `profileNames` (latest-wins by
  `(created_at, seq)`, plaintext-only, no pubkey leak beyond an 8-char
  prefix) renders a name instead of a bare pubkey; friends stat now
  excludes the phone's own identity from the count (was inflating by 1).
- **Two-node desk gate** — a real second `HearthNode` (a stock, unmodified
  "friend" satellite) plus the branch desktop, proving all 6 questions:
  before-enckey sequencing, the STOCK friend sweep minting real
  author-signed grants (no branch code on the friend side), old-DM
  recipient-signed backfill, new-DM inline delivery, bounded gossip timing,
  and clean RED-control isolation for both new legs.

## Desk gates (green)

- **hearth pytest:** full suite **1056/9-skip**, including **5 new**
  required tests for `maintain_received_dm_grants` with a three-sweep
  fixpoint test (all three sweeps — friend, own-device, received-DM —
  converging in production order, both prunes included). Independently
  re-run by the whole-branch reviewer, same count.
- **JVM (`:tor-manager:testDebugUnitTest`):** **51/51**, including the
  two-node gate (Task 4) proving: the friend wall leg via a genuinely stock
  friend-side sweep (no branch code run on the friend node), the old-DM leg
  via recipient-signed backfill independent of `DecryptPass`'s own
  resolution order, and the new-DM leg via inline delivery.
- **Final whole-branch review: READY TO MERGE, first slice with no fix
  wave.** Trust contract verified independently at mint/serve/consume;
  hearth-side isolation confirmed exact across all 6 commits (only
  `maintain_received_dm_grants` is new production code; the two prior
  sweeps are untouched).

## The trust-model extension (two sentences)

B.2c adds recipient-signed re-grants: when the phone receives a DM, the
sending desktop node re-wraps that DM's content key to the phone's OWN
devices only, signed by the RECEIVING identity (never the sender, never any
third party) — so a grant is honored strictly when the phone's identity is
that DM's actual recipient, closing off any path for a mutual friend or
bystander node to mint a grant on someone else's behalf.

## On-device run (the checklist August drives)

### a. Preconditions

- The G20 already carries the B.1 (253 msgs/19 blobs/3 friends) and B.2
  (own-content readable) proven state from prior runs.
- **The B.2 field lessons still apply and are load-bearing here too:**
  1. A source-run desktop MUST use
     `python -m hearth serve --dir %APPDATA%\Kreds --http-port <p> --gossip-port <p> --tor`
     — plain `hearth app` from source runs WITHOUT Tor (`_tor_enabled()` is
     packaged-only) and the phone's syncs will time out against a stale
     descriptor.
  2. A headless `serve` node starts LOCKED (keys are applock-encrypted at
     rest) and refuses sync sessions by design — the refusal frame gets
     purged by Windows RST, so the phone just sees a bare EOF
     (`stream closed at 0/4`). **Unlock via the web UI
     (`http://127.0.0.1:<http-port>`) before the phone syncs.**
  3. Check for stray duplicate `hearth app`/`serve` processes fighting over
     the data dir if anything misbehaves — repeated launch attempts left
     duplicates during the B.2 run.
- The desktop node running for this test must be on THIS branch
  (`brick-b2c-friends-content`, or a merge of it) — the new
  `maintain_received_dm_grants` sweep does not exist on plain `main` or on
  `brick-b2-decryption` alone. Confirm the running process's checked-out
  commit before starting the phone steps.
- The B.2 grants and the phone's enc key already exist from the prior
  on-device run, so in principle ONE sync could surface the new content —
  but do TWO to be safe (see flow below), since the new recipient-DM sweep
  needs its own one-time backfill pass to run at least once against this
  phone's identity.
- For the friend-wall leg: this needs a real friend's node to sync with the
  desktop at some point during or after this session — best-effort, since
  field friends' nodes are intermittent. Don't block the rest of the
  verification on it.

### b. Install

Both APKs are built and fresh (see below). Installing on the G20 hit the
same Play Protect dialog B.2's Task 8 hit:

1. On the G20, dismiss the pending Google Play Protect "send app for a
   security scan?" dialog (any of the three buttons proceeds).
2. From a fresh shell, run both installs:
   ```
   adb -s ZY32DLZQ2N install -r android_tor_spike\app\android\app\build\outputs\apk\debug\app-debug.apk
   adb -s ZY32DLZQ2N install -r android_tor_spike\app\android\app\build\outputs\apk\release\app-release.apk
   ```
3. If the dialog reappears for either install, dismiss it again and re-run
   that command.

### c. The two-sync flow

1. Open the app.
2. Tap **Sync now** (sync #1).
3. Wait roughly 15 seconds for the desktop's maintenance sweeps to run,
   including the new recipient-DM sweep's one-time backfill burst.
4. Tap **Sync now** again (sync #2) to pull whatever was minted.
5. If a real friend's node happens to sync with the desktop around this
   window, a third sync later can pick up their wall posts too — best
   effort, not required for the core verification.

### d. What to verify (the DoD checks)

- **Old friend DMs now readable, WITH author names** — this is the key new
  leg, and it works with just the desktop online (no friend node needed):
  received DMs from friends, previously stuck as ciphertext, should now
  show plaintext plus the sender's display name.
- **Friend wall posts** whenever a real friend's node next syncs with the
  desktop — best-effort, field friends are intermittent, don't treat
  absence as a failure by itself.
- **Own content regression** — everything B.2 proved (own posts/DMs
  readable, own-author-only, no friend content leaking into that path)
  still holds; this slice must not have disturbed it.
- **Friends stat now shows the true count** — 2, not 3 (B.1's stat
  included the phone's own identity in the count; B.2c's Task 3 fix
  excludes self).
- **Cold-start behavior unchanged** from B.2's baseline (force-kill +
  reopen shows the empty-state hint, not a silent blank, modulo the known
  B.2 anomaly ticket about the foreground service keeping the feed cache
  warm across swipe-kills).

### e. What to capture for the verdict

- Feed item counts before and after each sync (especially sync #2).
- Which specific friend DMs became readable, and whether the text matches
  the desktop's actual content (spot-check, not just "some text appeared").
- Whether author names render correctly for those DMs, and for any friend
  wall posts that come through.
- The friends stat value (expect 2).
- Any anomalies: timing between syncs, missing items, unexpected content
  appearing (e.g. anything NOT from a friend or NOT one's own), error
  strings, empty-state edge cases, or a recurrence of the known B.2
  cold-start/feed-squeeze anomaly.

## Build + install status (this session)

Both APKs built clean from a fresh `env.ps1` dot-source:

```
. .\android_tor_spike\tools\env.ps1
cd android_tor_spike\app\android
.\gradlew assembleDebug assembleRelease --console=plain
...
BUILD SUCCESSFUL in 14s
502 actionable tasks: 40 executed, 462 up-to-date
```

- `app/build/outputs/apk/debug/app-debug.apk` (65,433,300 bytes)
- `app/build/outputs/apk/release/app-release.apk` (38,446,673 bytes)

Install was attempted (`adb -s ZY32DLZQ2N install -r ...debug\app-debug.apk`)
and hung exactly like B.2's Task 8 run. Confirmed without driving the
device: `adb shell dumpsys activity activities` showed
`mResumedActivity: com.android.vending/...PlayProtectDialogsActivity` — the
same "send app for a security scan?" prompt. Per this task's scope (do not
tap the device), the stuck `adb.exe` client process was killed rather than
left hanging; the release install was not attempted this session (it would
hit the same dialog). Device confirmed left in a stable, unmodified state:
`adb shell dumpsys package eu.kreds.torspike` shows `lastUpdateTime`
unchanged before and after the attempt (the previously-installed B.2 build
is untouched and still functional).

**Retry commands for the human-driven session** — dismiss the dialog once
(any of the three buttons), then from a fresh shell:
```
adb -s ZY32DLZQ2N install -r android_tor_spike\app\android\app\build\outputs\apk\debug\app-debug.apk
adb -s ZY32DLZQ2N install -r android_tor_spike\app\android\app\build\outputs\apk\release\app-release.apk
```
If the dialog reappears for the second install, dismiss again and re-run
that one command.

## Field finding from the run (2026-07-20)

- **"Unable to load script" on first open** — the DEBUG APK had landed last
  on the device (it expects a Metro dev server and embeds no JS bundle).
  Fixed by installing the RELEASE APK (`adb -s ZY32DLZQ2N install -r
  android_tor_spike\app\android\app\build\outputs\apk\release\app-release.apk`),
  which embeds the bundle; the re-signed same-package install did NOT
  re-trigger Play Protect this time. Run steps below now say install the
  RELEASE apk (not debug) for an on-device run.

## Verdict

**PROVEN.**

- Feed shows readable friend content with author NAMES and TIMESTAMPS
  (August, on the G20). All four verification points passed:
  1. Old friend DMs readable via the recipient-signed backfill, with the
     correct friend name on each (the key new leg — desktop-only dependency).
  2. Friends stat shows the true count (2, not the pre-fix phantom 3).
  3. Friend wall posts render (best-effort field leg — confirmed present).
  4. Own content intact — no B.2 regression.
- Desk node ran this branch's hearth via `serve --tor`, unlocked via the web
  UI (both B.2 field lessons carried in cleanly). Two-sync flow as designed.
- Anomalies: only the debug-vs-release "Unable to load script" above,
  resolved by installing the release APK. No decrypt or entitlement issues.
- Overall: **PROVEN** — B.2c's definition of done met on hardware.

## Known deferred items / follow-up tickets

From the whole-branch final review (`79eb9fa..40c815f`, verdict READY TO
MERGE, no fix wave) plus carried-forward per-task minors:

- **B.2d ticket (Important): display-name spoofing hardening.** Within the
  friend set, nothing currently disambiguates a name collision or reserves
  "me"/similar strings from being claimed by a friend's profile — a friend
  could set a display name that reads as the owner's own content or as
  another friend, purely a presentation-layer confusion (no crypto forgery
  is possible; the entitlement/signature chain is unaffected). Needs
  collision disambiguation and a reserved-"me" rule.
- **Post-merge small-test commit** (deferred, not blocking): an entitlement
  else-branch negative test, a new-DM no-covering-grant mirror assert (the
  new-DM leg currently has no negative control for "no covering grant
  exists" — safe by construction today but ordering-fragile), and a
  `_run_phase3` docstring fix (the asymmetric final-deliver behavior is
  correct but under-commented).
- **Received-DM iterator/rebuild cost as DM history grows** — Task 1's
  `maintain_received_dm_grants` carries the same per-round
  `dm_conversations`/`dm_thread` rebuild cost as its sibling sweep (nested
  per-peer query); fine at current scale, worth revisiting if DM history
  grows substantially.
- **The standing Robolectric/instrumented coverage ticket** — still open,
  now covers B.2 Tasks 3/4/5/7 plus B.2c Tasks 2/3 (friendsCount's 0-case
  and SqliteSyncStore/module coverage generally); needed before Brick C
  backgrounds this sync.
- **One-time recipient-grant burst note** — the first sweep run against a
  live identity that already has friends with existing DM history will
  produce a one-time backfill burst of recipient-signed grants (bounded,
  and is the intended feature, same shape as B.2's own-device backfill
  burst); worth knowing before this runs against real accounts so it isn't
  mistaken for a bug.
