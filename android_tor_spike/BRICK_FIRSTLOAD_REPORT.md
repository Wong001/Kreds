# Onboarding Arc Slice 1 — First-Load Screen + Phone<->Desktop Pairing Ceremony — on-device proof record + DoD

**A fresh phone can now link itself to a desktop node by scanning a QR
code.** The Kreds Android client's first-load screen offers **Link to
your node** (live) or **Start a new profile** (coming-soon stub, arc 2).
Linking runs a real user ceremony: the desktop's new Add-device panel
mints a one-time pairing code and shows it as a QR + copyable link +
countdown; the phone scans it (or types the code), dials the desktop
over Tor, and the desktop operator sees "`<device>` wants to link" with
an Accept/Deny choice. On Accept, the phone receives hearth's standard
**full pairing package** (`cert`, `identity_priv`, `friends`, `peers`,
`my_addr` — node.py:1966-1987, unchanged) and drops straight into the
normal web UI, with history filling in over subsequent syncs. This
replaces the adb-pushed dev fixture as the intended onboarding path and
closes the desktop's own documented gap (its first-run screen used to
point at a "Settings -> add device" UI that didn't exist).

Branch: `brick-first-load` (base `04a3ceb` off main). HEAD: `d3c7996`.
Spec: `docs/superpowers/specs/2026-07-22-android-first-load-pairing-design.md`.
Plan: ledger section `PLAN: 2026-07-22 android-first-load-pairing` in
`.superpowers/sdd/progress.md`.

8 tasks, all APPROVED at review (Tasks 1, 4, 6 clean first pass; Tasks 2,
3, 5, 7 approved after in-branch fix waves — see the ledger for each
task's finding list). Global constraints held throughout:
`feat/fix/docs/test(pairing)` lowercase, no AI/Co-Authored-By trailers;
pairing-code TTL 600s single-use, sha256-hashed, `hmac.compare_digest`
constant-time verify; the pre-auth wire frame rides the *existing*
friend-add first-frame-peek precedent (`sync.py:172-188/315-365`), not a
new listener; `accept_pairing` and desktop<->desktop copy-paste pairing
are UNTOUCHED — additive only; phone storage is internal `pairing.json`
with legacy-external dual-read (the G20 fixture keeps working, no
migration step); `identity_priv` is stored but never enters
`KotlinHandshake.Fixture`; `expo-camera` was added via config plugin only
(the B2D2 prebuild hazard governs all manifest needs); the gate is a real
loopback ceremony (request -> accept -> package -> install) followed by a
genuine authenticated sync, plus deny/expired negatives.

This is Task 8, the final task: desk-gate sweep, RELEASE install on the
G20 (legacy fixture storage preserved, not cleared), this report, and a
PAUSE for August to drive the on-device DoD. **No on-device behavioral
checks were run beyond confirming the install succeeded** — the DoD
checklist below is entirely PENDING, August's to drive.

## Desk gates (all GREEN — this session, against HEAD `d3c7996`)

Commands run from `android_tor_spike/app` (tsc/vitest),
`android_tor_spike/app/android` (gradle), and the repo root (pytest),
against a clean working tree (`git status` clean at session start and
throughout — no code changes made this session, desk-gate + install only).

| Gate | Command | Result |
|------|---------|--------|
| Full JVM suite (`tor-manager`) | `JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot" ./gradlew :tor-manager:testDebugUnitTest --rerun-tasks` | **BUILD SUCCESSFUL** in 24s, 62/62 tasks executed |
| XML result count | `python -c "..."` glob over `../modules/tor-manager/android/build/test-results/testDebugUnitTest/*.xml`, summed `tests`/`failures`/`errors` attrs | **282 tests, 0 failures, 0 errors**, 29 result files |
| Full hearth pytest | `.venv/Scripts/python.exe -m pytest -q` (repo root) | **1096 passed, 9 skipped**, 4 warnings, 90.98s |
| `npx tsc --noEmit` | from `android_tor_spike/app` | **14 errors, all pre-existing** `@types/node` (`src/__tests__/wire.test.ts`, `test/web-readonly-seam.test.ts`, `tools/handshake_cli.ts`, `tools/node_stream.ts`, `tools/roundtrip_cli.ts` — same file set/count as the outbound-1/outbound-2/DM baseline). **0 new.** |
| `npx vitest run` (full) | from `android_tor_spike/app` | **29/29** (2 test files) |
| `:app:assembleRelease` | `./gradlew :app:assembleRelease` | **BUILD SUCCESSFUL** in 4s, apk at `app/build/outputs/apk/release/app-release.apk` (63,814,661 bytes), 365 tasks (8 executed, 357 up-to-date — tree unchanged since Task 7's build) |
| Manifest permission check | `aapt dump permissions app-release.apk` (build-tools 36.0.0) | **CAMERA present** (`android.permission.CAMERA`); **RECORD_AUDIO absent** from the full permission list |
| Native libs present | unzip listing of the apk | `lib/arm64-v8a/libtor.so` and `lib/arm64-v8a/libtorjni.so` both present |
| `copyHearthWeb` seam sync | unzipped `assets/www/style.css` directly from the built apk | Confirmed: `body.readonly #add-device` **is** in the hide block (Task 5's fix); `body.readonly #profile-wall-compose` **is** still in the hide block; `#dm-compose` (revealed in the DM slice) does **not** appear anywhere in the hide block — only as ordinary visible rules (`#dm-compose button[type=submit] {...}`, `.dm-compose-bar {...}`) |

No new tsc errors, no vitest regressions, no JVM regressions, no pytest
regressions. JVM suite count (282) matches Task 6/7's reported total —
this session added no new tests (desk-gate re-verification only).

## Ceremony proof (Task 6 — the parity proof of the whole slice)

`SyncPairLoopbackTest.kt`
(`android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/SyncPairLoopbackTest.kt`)
is the first place the two halves of this slice actually meet:
`KotlinPairing.runCeremony` (Tasks 3-4) dialing a **real socket against a
real hearth node subprocess** (`sync_loopback_node.py`'s new `pairing`/
`pairing_deny` scenarios), whose pre-auth `hearth-pair-request` wire
handler is Task 2's actual, unmodified, production
`_handle_pair_request` — the same listener every other `*LoopbackTest.kt`
file in this module already syncs real content against. Three tests, each
spawning its own fresh node process:

- **`happyPathLinksPersistsAndSyncsRealContent`** — the full positive
  path. `runCeremony` dials the real node; the node's real
  `_drive_pair_accept` auto-accepts, driving the *exact* node-level
  operations `hearth/api.py`'s `POST /api/pair/accept` executes
  (`node.accept_pairing`, never a shortcut). Asserts `Linked`, then the
  **ultimate full-pairing assertion**: the persisted `identity_priv`
  equals the node's own real `identity_priv` **full-hex**, byte for byte
  — not a stand-in value, the actual root key the ceremony was supposed
  to hand over (`assertEquals(node.identityPriv,
  identity.identity_priv)`). Cross-checked independently by the node
  process reading its own `store.load_views` back (`_drive_pair_accept`)
  and confirming the enrolled `device_pub` matches, and by
  `PairingStore.load(dir)` round-tripping the exact same identity from
  disk. `accept_pairing`'s own `friends` list rode along inside the
  package itself (both the node's own identity and the friend's identity
  are present in the phone's store before any sync runs). **Then a fresh
  connection** (not the ceremony connection) builds a
  `KotlinHandshake.Fixture` from the just-persisted identity and runs the
  same `authOnlyOverStream` + `KotlinSync.run` path every other loopback
  gate in this module uses — proving the cert `accept_pairing` minted is
  not merely well-formed but genuinely **accepted by the node's own AUTH
  admission gate**, and the resulting sync pulls the seeded journal post
  (`store.allMessages().any { it.kind == "post" && ... }`). This is the
  **post-enroll authenticated sync** proof: the enrolled device is a
  genuinely working device of the identity, not a cert that merely looks
  valid.
- **`denyReturnsDeniedNothingPersistedAndUnenrolledSyncRefused`** — the
  deny negative. The node's driver always denies; asserts
  `CeremonyResult.Denied` with `PairingStore.load(dir)` returning null
  (nothing persisted) and the store's known-identities empty (untouched).
  The harder half is the **negative auth proof**: a device that was never
  handed `identity_priv` (by construction — `Denied` carries no key
  material) cannot mint a cert the node will recognize as this identity's;
  the only cert it could self-sign names some other identity it minted
  itself, which the node's real `is_known` admission gate
  (`hearth/sync.py` `_session`:629-631) correctly refuses — driven through
  the same `authOnlyOverStream` + `KotlinSync.run` chain the accepted case
  uses, not a different mechanism. **The Windows RST manifestation note**
  (verified, not assumed, in this run): hearth's own responder writes a
  clean `{"t":"refused"}` frame and closes immediately, confirmed directly
  against this exact scenario with a bare `hearth.transport` asyncio
  client — but by the time the server decides to refuse, the Kotlin
  client's own already-sent "revocations" frame (the wire protocol's
  initiator-writes-first phase) is still sitting unread in the server's
  socket receive buffer, so the server never reaches its own graceful
  close path; unread inbound data at close is the textbook trigger for a
  hard RST instead of a FIN, and empirically (4/4 reruns, deterministic)
  that is exactly what `java.net.Socket` surfaces on this platform: the
  reply frame's bytes never make it through `readExactSync`, which throws
  `SocketException`, folded into `Failed("io", ...)` rather than the
  idealized `Failed("revocations","refused")`. Both outcomes are accepted
  as the one "refused" signal this transport can produce — this is the
  same platform quirk `KotlinHandshake.kt`'s own doc comment had already
  flagged as unverified; this test is what verifies it, for real, on this
  platform. An `Ok`/successful sync is the only outcome that would
  actually fail the assertion.
- **`wrongCodeIsExpiredBeforeAnyPendingAndRealCodeSurvives`** — presents
  the same dialable address with a code that can never match. Asserts
  `CeremonyResult.Expired`, nothing persisted. "Nothing parked" is proven
  concretely rather than merely asserted in isolation: a real ceremony
  against the *original correct* link, run immediately afterward,
  succeeding completely (`Linked`) is only possible if hearth's
  single-active-ceremony admission check never saw a stray pending
  ceremony to collide with, **and** the real code survived the wrong-code
  guess untouched (`pairingcodes.py`'s `verify_and_consume`: "a wrong
  attempt leaves the active code untouched").

Reviewer re-ran this gate 3x fresh at Task 6 review time (3/3 each run);
JVM suite 282/282, pytest 1096/9skip — the same totals this session's
independent re-run reproduced.

## On-device DoD — G20 (August drives)

**Status: PASSED — confirmed by August 2026-07-23** ("the pairing worked
well on the phone"). Run sequence as executed: (a) legacy-fixture boot
verified before reset; revoke pressed on the old spike-phone device
(REVOKED=YES confirmed in the desktop store; the phone kept local data
and lost sync, the documented current-design behavior — self-wipe is the
revocation arc's slice C); `pm clear`; fresh first-load screen; QR
ceremony against the branch-served desktop node (Add-device panel);
paired successfully. Items beyond the core flow were not individually
reported; no issues raised. UX notes from the run, ticketed: the
first-load screen wants a visual pass (later — the cross-platform
visual-parity slice), and the SAME first-run UI should be REUSED on
desktop so phone and desktop onboarding look and act alike (August
2026-07-23). Checklist preserved as run documentation:

- [ ] (a) G20 with legacy fixture boots straight to the web UI (no
      first-load screen)
- [ ] (b) fresh state (second device, or August clears storage AFTER (a)
      passes): first-load screen appears
- [ ] (c) desktop -> Devices -> Add device shows QR + code + countdown
- [ ] (d) scan with the phone -> desktop shows "\<device\> wants to
      link" -> Accept -> phone lands in web UI
- [ ] (e) after a sync round, history appears
- [ ] (f) typed-code fallback works
- [ ] (g) Deny shows the denied message and the phone can retry
- [ ] (h) camera permission prompts only on Link tap
- [ ] (i) desktop<->desktop copy-paste pairing still works (regression,
      CLI or bootstrap screen)

**CRITICAL for (a)/(b):** the G20's app storage was deliberately **not
cleared** this session — its legacy external fixture is the regression
case DoD item (a) exists to test (the device should boot straight past
the first-load screen). August should run (a) first on this G20 install,
and only then clear storage (or use a second device) to exercise (b)
through (i).

## Install confirmation

Device: G20, serial `ZY32DLZQ2N`, package `eu.kreds.torspike`, RELEASE
apk only (built against HEAD `d3c7996`, `assembleRelease` mostly
up-to-date — no source changes since Task 7's build).

```
adb shell am force-stop eu.kreds.torspike   -> (no output, success)
adb install -r -d app-release.apk           -> "Performing Streamed Install" / "Success"
```

Same Play Protect gotcha as every prior sideload cycle on this device:
`adb shell dumpsys window | grep mCurrentFocus` showed
`com.android.vending/com.google.android.finsky.protectdialogs.activity.PlayProtectDialogsActivity`
in focus mid-install. `adb exec-out screencap -p` confirmed the "Vil du
sende appen til sikkerhedstjek?" (Danish: "do you want to send the app
for a security check?") prompt for `KredsTorSpike`, dismissed via a tap
on "Send ikke" (Don't send — the privacy-preserving choice for an
unreleased, pre-IP-filing sideloaded APK), after which focus returned to
the launcher and the backgrounded install call's output showed `Success`
immediately.

Confirmed via `adb shell dumpsys package eu.kreds.torspike`:
`firstInstallTime=2026-07-19 05:20:15` (unchanged, predates this
session), `lastUpdateTime=2026-07-23 00:41:04` (this session's install) —
proving the reinstall updated the APK in place **without** wiping app
storage, so the legacy external fixture DoD item (a) needs is still
present on the device.

No behavioral checks were run beyond confirming the install succeeded —
the on-device DoD checklist above is entirely August's to drive.

## Run gotchas

- **A Play Protect scan-consent dialog can silently block `adb install`
  with no error and no timeout** (now a repeated, well-documented
  occurrence across every outbound/DM/pairing sideload cycle on this
  device). If a reinstall hangs with no output after "Performing Streamed
  Install," check `adb shell dumpsys window | grep mCurrentFocus` for
  `PlayProtectDialogsActivity` before assuming a transfer stall — the app
  may already be installed and waiting on the post-install scan-consent
  prompt. `adb shell input tap` on the dialog's "don't send" option
  (`Send ikke`) unblocks it; screenshot via `adb exec-out screencap -p`
  first to find the exact coordinates — they can shift with device
  language/resolution/layout.
- **RELEASE-apk-only + force-stop-first are still the field rules**
  carried from every prior brick: the debug apk fails to load the JS
  bundle on this device config, and skipping `am force-stop` before
  `adb install -r` can hang the install.
- **Do not clear the G20's app storage before DoD item (a) runs.** This
  install intentionally preserved it — clearing storage first destroys
  the regression fixture DoD (a) is designed to exercise (the legacy
  external `spike_phone_fixture.json` path booting straight past the
  first-load screen). Clear storage only *after* (a) passes, to then
  exercise (b) onward, or use a second device for the fresh-state path.
- **Prebuild hazard, for future devs touching this module (carried from
  Task 7):** `expo prebuild --clean` regenerates the `android/` tree from
  scratch and will silently drop any manifest/build.gradle customization
  that isn't backed by a config plugin — this bit Task 7 directly (the
  tor-android AAR reference, arm64 ABI pins, network-security-config, and
  the CAMERA permission line all got dropped on a clean prebuild and had
  to be restored verbatim). `CAMERA` for this slice was added via a
  proper Expo config plugin specifically to survive this; any other
  manifest-level need in this module should follow the same pattern, not
  a hand-edit of the generated tree. See "Follow-up tickets" below for
  the ticket to generalize this into small local config plugins for the
  *other* three still-hand-held customizations.

## Honest boundary

Reproduced verbatim from the design spec's "Honest limits" section
(`docs/superpowers/specs/2026-07-22-android-first-load-pairing-design.md`):

> - **identity_priv on the phone**: a stolen/compromised phone now holds
>   the identity root key. Device revocation (existing) removes network
>   trust but cannot un-know an exfiltrated key. Mitigations: ceremony
>   transport is onion-encrypted end-to-end; at-rest sealing = ticketed
>   follow-up (App-lock analog on phone); the paper seed remains
>   desktop-side.
> - **The QR is a 10-minute bearer secret** for *requesting* enrollment;
>   the desktop Accept step is the human backstop. A user who both leaks
>   the QR and blind-accepts an unexpected device name defeats it — the
>   Accept dialog therefore shows the device name prominently.
> - **History is not instant** after linking (grant-sweep round-trip);
>   stated in the success UI, not hidden.
> - Un-linking = existing desktop device revocation; no phone-side "log
>   out" in this slice (ticket).
> - Camera permission is requested only when the user taps Link (not at
>   install).

**The panic-wipe ticket** (spec "Out of scope," August's direction,
2026-07-22): at-rest sealing of `identity_priv` on the phone is explicitly
deferred to a later arc. August's direction for that arc: consider an
opt-in panic-wipe ("nuke profile after N failed unlock attempts") or
similar, alongside an App-lock analog — its own design conversation, not
started by this slice.

## Follow-up tickets (non-blocking, carried from the ledger)

- **Desktop paste-request tab has no home in the new Add-device panel**
  (Task 5). The new panel covers the QR/code/countdown flow only;
  desktop<->desktop copy-paste pairing (the pre-existing bootstrap flow)
  is untouched and still works, but has no UI presence inside the new
  panel. Candidate: extend the panel with Share/Enter tabs mirroring the
  existing `buildFriendAdd` pattern. DoD item (i) exercises the
  *pre-existing* flow as a regression check, not the new panel — this
  ticket is about giving it a home in the new UI, not about it being
  broken.
- **RANKED #2 (operationally, per the final whole-branch review):**
  **`/api/pair/accept`'s exception path never fires the pending-request
  event** (Task 2). If `accept_pairing` itself raises, the held
  connection has no signal and looks dead until the code's TTL expires
  rather than failing fast with a clear error.
- **Rate-limiter window logic is duplicated** (Task 2). The pairing
  frame's rate limit reuses the same sliding-window algorithm as the
  existing friend-add rate limiter, but as a separate, hand-duplicated
  budget rather than a shared implementation. Candidate for a DRY pass
  if a third consumer ever appears.
- **Frame I/O is now a third private copy** (Task 4).
  `KotlinHandshake`/`KotlinSync`/`KotlinPairing` each carry their own
  length-prefix frame read/write implementation (byte-identical by
  construction, verified, but not shared code). Future DRY candidate, not
  a correctness gap.
- **RANKED #1 (operationally, per the final whole-branch review) — do
  before the next Expo-touching slice:** **Prebuild config plugins for
  the remaining hand-held customizations** (Task 7). Only the CAMERA
  permission got a proper Expo config plugin this slice; the tor-android
  AAR reference, arm64 ABI pins, and the network-security-config remain
  hand-restored-on-prebuild customizations (verified byte-identical
  after Task 7's clean prebuild, but still fragile to any *future*
  prebuild by someone who doesn't know to re-diff). A future `expo
  prebuild --clean` silently breaks Tor + video + camera all at once if
  this isn't done first. Candidate: small local config plugins following
  the `withHearthWebAssets` pattern already used elsewhere in this
  module.
- **`PairingStore`'s atomic write has no fallback if `ATOMIC_MOVE` isn't
  supported** (Task 3). The NIO atomic-move re-pair-overwrite path is
  reasoned to be safe on the target filesystems, but there's no fallback
  branch if a filesystem doesn't support `ATOMIC_MOVE` — a safe failure
  mode (the write would throw rather than silently corrupt), not a
  silent-data-loss risk, but not handled gracefully either.
- **At-rest sealing of `identity_priv` on the phone, incl. the panic-wipe
  direction** (spec "Out of scope," see "Honest boundary" above).
  Explicitly deferred to a later arc — August's direction is an opt-in
  panic-wipe alongside an App-lock analog, its own design conversation.

**Final whole-branch review (this session) added five more findings,
none blocking merge** (existing tickets above are unchanged and keep
their original numbering/order; the two RANKED tags above were added by
this same review pass):

- **Desktop panel's pending state is terminal.** The Add-device panel
  polls `/api/pair/pending` while waiting, but once a pending ceremony
  resolves and `pending` goes back to `null`, the panel doesn't
  distinguish "never happened" from "just resolved" -- there's no
  UI transition to an expired/resolved view, so a human who missed the
  Accept/Deny moment has no discoverable way to recover except manually
  restarting Add-device. Candidate: keep polling and flip to an expired
  view when `pending` goes null without a prior local Accept/Deny click.
- **Ghost-device enroll on accept-after-phone-gone.** If the desktop
  operator clicks Accept after the phone has already dropped the
  connection (crashed, lost Tor, walked away), `node.accept_pairing`
  still runs and enrolls a device that will never come back to claim its
  package -- an enrolled-but-unreachable "ghost" device sitting in the
  identity's device list. This is inherent to the protocol's shape (the
  accept decision and the connection's liveness are checked
  independently, not atomically) rather than a bug in this slice;
  existing device revocation is the cleanup path. Recorded here as a
  report line, not a fix.
- **Client protocol field is silently rebuilt server-side.** The pairing
  package's protocol-version field isn't trusted from the phone as sent
  -- the server rebuilds it from its own constant rather than validating
  the client's. A version-skewed phone can therefore complete pairing
  successfully and only fail later, at AUTH, rather than getting a clear
  version-mismatch error at pairing time. Not urgent today (single
  protocol version in the field), but check this the next time `PROTOCOL`
  bumps -- that's when the deferred failure becomes reachable.
- **Portless type-4 link surfaces as "unreachable" not "bad_link".**
  A pairing link missing its port (a type-4 link malformed in that
  specific way) is reported to the human as a generic "unreachable"
  connection failure rather than a more specific "bad_link" parse error.
  Cosmetic -- the ceremony still correctly fails closed, just with a
  less precise message.
- The two RANKED tags above (prebuild config plugins = #1, do before the
  next Expo-touching slice; `/api/pair/accept`'s exception-path event gap
  = #2) are this review's operational priority call across the whole
  ticket list, not new findings.

**Final review verdict:** READY TO MERGE pending DoD, zero blocking
findings.

Additional minor/cosmetic items carried in the ledger but not blocking
and not itemized above: Task 4's unrecognized-reply `t` string is
unsanitized in logs (cosmetic, no key/cert material involved — verified);
Task 6's `awaitEvent` duplication across loopback test files (justified
per-file, not shared) and `_drive_pair_accept`'s omission of an
inapplicable `device_pub` guard (comment nit); Task 7's report omitted
mentioning `ndkVersion` churn (cosmetic, traced to an identical resolved
value) and an unmount `setTimeout` no-op (inert under React 18).

## After the run

On a pass, whether this merges to public main is August's call, same as
every prior brick. PAUSE here for human review per the task brief. This
closes slice 1 of the onboarding arc (link-first; create-on-phone and
phone-enrolls-desktop "vice versa" are arc 2+, per the spec's "Out of
scope" section).
