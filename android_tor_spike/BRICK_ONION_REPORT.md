# Phone Onion Reachability + Revocation Parity (Arc 2) — proof record + DoD

**The phone is now a genuinely reachable node.** Arc 1 (`brick-gossip-server`,
merged) gave the phone the RESPONDER half of `_session` but bound it to
`127.0.0.1` only — no friend or desktop could dial in. This arc closes both
halves of what that left open: (1) the phone now publishes a Tor v3 onion
service forwarding to its own `GossipServer`, proven reachable by a real
desktop Tor client over a real circuit; (2) the phone now has a revocation
and defriend model at all — the arc-1 whole-branch review's named BLOCKING
precondition for exposing the server past loopback, because an unrevoked/
undefriended check on a server a stranger can now dial is a real breach, not
a latent one.

Branch: `brick-phone-onion` (base `988e1ed` off main). HEAD: `55889b1`.
Spec: `docs/superpowers/specs/2026-07-23-phone-onion-reachability-design.md`.
Plan: ledger section `PLAN: 2026-07-23 phone-onion-reachability` in
`.superpowers/sdd/progress.md`.

9 tasks. Global constraints held throughout: `feat/fix/docs/test(onion)`
lowercase, no AI/Co-Authored-By trailers; revocation ingest and defriend
apply are byte-parity ports of hearth's own `store.ingest_revocation`
(`store.py:410-430`) and `node.apply_defriend_notice`
(`node.py:1746-1780`, the phone's peer-table-free SUBSET); the post-AUTH
revoked-device refusal and the mid-session defriend re-check mirror
`sync.py:637-641` / `sync.py:741-758` exactly; `ONION_VIRTUAL_PORT = 9997`
fixed forever (mirrors `tor.py:41` — re-picking this port once deadlocked
every node, per the 0.3.14 outage); self-revoke is a FULL WIPE
(`enterRevokedState`, the `node.py:3144` analog), destructive and
irreversible by design, folding in slice C's revocation trigger. This is
Task 9, the final task: desk-gate sweep, RELEASE install on the G20, an
honestly-scoped on-device consolidation probe, this report, and a PAUSE —
merge is August's call.

## Desk gates (all GREEN — this session, against HEAD `55889b1`)

Commands run from `android_tor_spike/app/android` (gradle),
`android_tor_spike/app` (tsc/vitest), and the repo root (pytest), against a
clean working tree (`git status` clean at session start; no code changes
made this session — desk-gate verification + install + on-device probe
only).

| Gate | Command | Result |
|------|---------|--------|
| Full JVM suite (`tor-manager`) | `JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot" ./gradlew :tor-manager:testDebugUnitTest --rerun-tasks` | **BUILD SUCCESSFUL** in 27s, 62/62 tasks executed (forced fresh, no cached UP-TO-DATE results) |
| XML result count | glob `android_tor_spike/app/modules/tor-manager/android/build/test-results/testDebugUnitTest/*.xml`, summed `tests`/`skipped`/`failures`/`errors` attrs | **372 tests, 0 skipped, 0 failures, 0 errors**, 38 result files — exact match to expected 372/0 |
| Full hearth pytest | `.venv/Scripts/python.exe -m pytest -q` (repo root) | **1105 passed, 9 skipped**, 4 warnings, 82.51s — exact match to expected |
| `npx tsc --noEmit` | from `android_tor_spike/app` | **14 errors, all pre-existing** `@types/node` (`src/__tests__/wire.test.ts`, `test/web-readonly-seam.test.ts`, `tools/handshake_cli.ts`, `tools/node_stream.ts`, `tools/roundtrip_cli.ts` — same file set/count as every prior brick's baseline, unaffected by Task 6's `FirstLoad.tsx` change). **0 new.** |
| `npx vitest run` (full) | from `android_tor_spike/app` | **29/29** (2 test files), 230ms — current baseline, verified live this session |
| `:app:assembleRelease` | `JAVA_HOME=... ./gradlew :app:assembleRelease` | **BUILD SUCCESSFUL** in 17s, apk at `app/build/outputs/apk/release/app-release.apk` (63,831,497 bytes), 365 tasks (36 executed, 329 up-to-date) |

No new tsc errors, no vitest regressions, no JVM regressions, no pytest
regressions. JVM suite grew across the branch from arc-1's final 305 to
372 (+67 across Tasks 1-8), matching this session's independent fresh
re-run exactly. Task 8's own final gate line (`372/372 JVM, pytest
1105/9skip`) is exactly reproduced here.

## The headline — ONION REACHABILITY PROVEN, architecture validated

Task 1 is the arc's crux result. First pass was a genuine escalation: 15
desktop Tor dials of the phone's freshly-published `.onion:9997` over ~12
minutes all came back `rep=0x01` (general failure) against a REAL listener
(arc-1's `GossipServer`) behind the target port — meaning the failure was
genuine onion-unreachability, not a nothing-listening artifact, and pointed
at tor-android hidden-service-hosting difficulty (a known-hard mobile
problem). This was escalated to August as an architectural fork before any
further investment.

Root-caused via 3 on-device diagnostic passes (all instrumentation
reverted afterward): the onion was doing everything right — descriptor
upload, intro-circuit maintenance, `INTRODUCE2`, rendezvous handshake
("Active rendezvous point") — but `handle_hs_exit_conn` retried 3x then
abandoned the connect to the app's own local listener. Cause: arc-1's
`GossipServer` bound `getLoopbackAddress()`, which resolved to pure IPv6
`::1` on the G20, while `addOnion` targets IPv4 `127.0.0.1:<port>` — no
IPv4 listener existed for Tor's exit connection to reach. **Fix (commit
`4883990`):** `getLoopbackAddress()` → `getByName("127.0.0.1")`
(`::ffff:127.0.0.1`, dual-stack IPv4-reachable). Re-run: **3/3 desktop Tor
dials of the phone's `.onion:9997` SUCCEEDED** (`rep=0x00`, "Exit connection
established", no retries) — the first real Tor reachability of the whole
phone effort. **Architecture validated: onion-on-tor-android works; the
reachable-phone model (arcs 2-3) is viable.**

This report does **not** re-run that desktop dial — Task 1 already proved
it, and re-proving real Tor reachability was explicitly out of scope for
this task (see the on-device section below for what this session confirms
instead: that the *production* publish path, not Task 1's temporary spike
hook, is the one now live).

## The security gate — revocation/defriend parity (Tasks 2-6, proven at the real wire by Task 8)

The phone had never processed a revocation or defriend on any path before
this arc — `knownIdentities` was add-only, no per-device revoked flag
existed, and `SelfRevoked` was detected but took no action. Latent while the
phone only dialed out to a desktop that enforced everything; would have
gone live the moment the onion made the phone reachable. Ported hearth's
model line-for-line as a faithful peer-table-free SUBSET:

- **Task 2** (`4883990..747a324`): store primitives —
  `removeIdentity`/`purgeAuthoredBy` (mirroring `store.py:162`/`store.py:1036`),
  a `revokedDevices` set + retro-drop (seq boundary matches hearth's
  `store.py:421` exactly), `getMeta`/`setMeta`. Both `SqliteSyncStore` and
  `InMemorySyncStore` byte-checked consistent. 317/317.
- **Task 3** (`747a324..5694cdf`): `RevocationCert` verify at BYTE-PARITY vs
  `identity.py:120-149`; `ingestRevocation` wired into both `run`/`serve`
  REVOCATIONS phases. A review-caught comment error was fixed mid-task: own
  identity IS seeded and known (not "never known" as originally commented),
  so a genuine self-revocation runs the full ingest+markRevoked+retro-drop
  path before returning `SelfRevoked` — matches hearth's `is_self=1`.
  334/334.
- **Task 4** (`5694cdf..5fea0d7`): `DefriendNotice` verify at BYTE-PARITY vs
  `identity.py:159-168`; the 4-gate apply order EXACT vs
  `node.py:1746-1780` (`target==own` → self-author-guard → `verify()` →
  `isKnown()` → `purgeAuthoredBy`+`removeIdentity`), no mutation before all
  4 gates pass. 350/350.
- **Task 5** (`5fea0d7..d7a7a43`): post-AUTH revoked-device refusal in
  `respondHandshake`, same wire slot as stranger refusal (mirrors
  `sync.py:637-641`); mid-session re-check after DEFRIENDS before HAVE
  (mirrors `sync.py:741-758`) — a revoked/unfriended peer is served
  NOTHING further, fail-fast. A fail-open default (`isRevoked` defaulting
  `{false}`) was found and fixed to a required parameter, matching the
  existing `isKnown` convention. 355/355.
- **Task 6** (`d7a7a43..a80c924`): self-revoke = full wipe
  (`PairingStore.wipe` + `SqliteSyncStore.wipe` + service stop + a
  `"revoked"` RN event → `FirstLoad.tsx` re-checks `hasIdentity()` → false).
  **CRITICAL REMOTE-WIPE VULNERABILITY found and fixed (commit `a80c924`):**
  `SelfRevoked` had been firing on a RAW `device_pub` match, ignoring
  `ingestRevocation`'s own verify result — meaning ANY authenticated friend
  could forge a throwaway-key revocation cert naming the victim's
  `device_pub` and trigger an irreversible remote wipe of a phone that
  never authorized it. The arc-1 raw-match pattern had been "weaponized" by
  Task 6 turning detection into destructive action. **Fix:** gate on
  `rev.identity_pub==own && rev.verify() && device_pub==own` — only the
  device's own `identity_priv` can authorize wiping it, stricter than
  hearth's own `is_known`+device-match. 5 negative tests were mutation-
  verified independently by the reviewer to fail under the old raw-match
  logic (throwaway-key forgery + known-friend-signs-naming-our-device both
  closed); the genuine-own-identity-signed positive case still fires. No
  residual bypass — this is the sole `SelfRevoked` gate on both sync paths.
  366/366.

**Task 8 — the real-wire proof.** `SyncRevokeLoopbackTest.kt` +
`SyncServeLoopbackTest.kt` extend arc-1's real-hearth-node-dials-the-phone
loopback gate with two scenarios:

- **Revoked-device refused**: a real hearth node whose device the phone has
  `markRevoked`'d dials in → `respondHandshake` refuses post-AUTH
  (`PeerRefused`). Reviewer mutation-verified (disabling the gate makes
  this fail).
- **Defriend-stops-serving**: caught TWO false passes before landing
  correctly. First, disabling the phone's own mid-session re-check still
  passed — because the test node's own `unfriend()` call tore down its own
  session locally, masking whether the phone's gate did anything. Fixed by
  signing/queuing the notice directly (`device.make_defriend`+
  `add_outbox`) so the node keeps trying to HAVE regardless. Second, the
  entitled-content marker used in the test depended on `deviceViews`, which
  `purgeAuthoredBy` destroys regardless of whether the gate works — so the
  test would pass even with the gate broken. Fixed (commit `55889b1`) with
  a plaintext audience-gate-free marker. **Now genuinely mutation-verified:
  disabling the mid-session re-check reproduces the over-serve (test
  FAILS, node receives the marker); gate on, test passes.**

372/372 JVM, pytest 1105/9skip — real hearth classes and real signing
throughout, fail-closed by design.

## On-device — G20, HONESTLY

**Install.** Device G20, serial `ZY32DLZQ2N`, package `eu.kreds.torspike`,
RELEASE apk built against HEAD `55889b1`.

```
adb shell am force-stop eu.kreds.torspike   -> (no output, success)
adb install -r -d app-release.apk           -> "Performing Streamed Install" / "Success"
```

Same Play Protect gotcha as every prior sideload cycle:
`dumpsys window | grep mCurrentFocus` showed `PlayProtectDialogsActivity` in
focus mid-install; `adb exec-out screencap -p` confirmed the "Vil du sende
appen til sikkerhedstjek?" prompt naming `KredsTorSpike`, dismissed with a
tap on "Send ikke" (358, 1163) — screencapped and visually verified before
tapping, not assumed from memory. Confirmed via `adb shell dumpsys package
eu.kreds.torspike`: `firstInstallTime=2026-07-19 05:20:15` (unchanged,
predates this session), `lastUpdateTime=2026-07-23 20:25:50` (this
session's install) — the device's existing real pairing survived the
reinstall.

**Launch + the monkey-tombstone false positive (again).**
`adb shell monkey -p eu.kreds.torspike -c android.intent.category.LAUNCHER
1` reported 17+ "New tombstone found" lines and a "First NativeCrash" line
both times this session (initial launch and the later restart). As
documented in `BRICK_GOSSIP_REPORT.md`, this is `monkey` reporting every
pre-existing tombstone as "new" on its first invocation per session, not a
real crash: `adb shell ls -la /data/tombstones/` (with `MSYS_NO_PATHCONV=1`
to stop Git Bash mangling the absolute path) shows every listed tombstone
dated 2023-01 through 2023-08 (factory-era) except one from 2026-07-19
(four days before this session, prior brick work) — **none dated today.**
The app process was confirmed alive both times (`ps -A | grep kreds` shows
the process plus an Android pre-fork sibling; `dumpsys window | grep
mCurrentFocus` shows `MainActivity` in focus; `dumpsys activity services`
shows `TorNodeService` `isForeground=true`).

**What was checked, and what it does and does not prove.**

Per the task framing: Task 1 already proved real Tor reachability with a
temporary spike hook; this probe's job is to confirm the *production*
`publishOnion()` path (wired in Task 7, gated behind the Task 2-6 security
work) actually fires on a real boot, not to re-prove reachability itself.
`publishOnion()` is deliberately best-effort and silent-on-success by
design (`TorNodeService.kt:262-275` — the ONLY log call on this path is
`Log.w(TAG, "onion publish failed...")` in the `catch` block; confirmed by
direct source inspection, zero other `Log.*` calls exist in
`TorNodeService.kt`, matching the same no-logging convention arc-1's report
already found in `GossipServer.kt`/`LocalWebServer.kt`). There is also no
RN-bridge method or in-app UI surface exposing `gossip_addr`/`onion_key`
(checked every `Function`/`AsyncFunction` in `TorManagerModule.kt` and
grepped all `.ts`/`.tsx` for `gossip_addr`/`onion` — none). And `run-as`
remains blocked on this non-debuggable release build (`run-as: package not
debuggable: eu.kreds.torspike` — the exact same constraint Task 1's own
report hit trying to read Tor's HS log), so the `sync_store.db` `meta`
table holding the actual persisted `gossip_addr`/`onion_key` strings is
**not independently readable via adb**. This is a real, structural
evidence gap, stated plainly rather than glossed over.

What IS independently confirmable without that access, gathered across
**two full boots** (the initial launch, and a second force-stop + relaunch
cycle to probe restart behavior):

1. **`TorNodeService` runs foreground both times** (`isForeground=true`,
   `startForegroundCount=1`, `foregroundNoti` on channel `kreds-node`) — the
   precondition for `startNode()`'s whole bootstrap chain to have run at
   all.
2. **Tor bootstrap completed both times**, evidenced indirectly but solidly:
   the foreground notification's tracked sync count advanced both times
   (`"last sync 20:26 FAIL (skipped (in progress)) - 1 syncs"` on first
   launch, `"last sync 20:32 FAIL (skipped (in progress)) - 1 syncs"` after
   the restart) — this text is only ever written by `syncCycle()`
   (`TorNodeService.kt:284-309`), which is only ever scheduled from
   `TorEngine.bootstrap`'s `onDone` callback. **"FAIL (skipped (in
   progress))" is a documented, benign outcome** (`TorNodeService.kt:290-296`
   comment: `SyncRunner.ran == false` is "a benign mutex contention... not a
   real failure," rendered neutral rather than red in the app's own UI) —
   the shared `SyncRunner.syncLock` was held by something else at that exact
   moment. Given arc 2 wires `GossipServer`'s accept path onto this SAME
   shared lock (first noted in `BRICK_GOSSIP_REPORT.md`), this is
   circumstantially consistent with an inbound connection racing the
   outbound cycle for the lock in that window, though the specific cause
   could not be independently confirmed.
3. **`publishOnion()` is queued and runs BEFORE the first sync**, by source
   ordering: `TorNodeService.kt:171-172` submits `publishOnion()` then
   `syncCycle()` to the SAME single-threaded scheduler, FIFO. Since
   `syncCycle()` demonstrably ran (point 2), `publishOnion()` demonstrably
   ran first.
4. **No "onion publish failed" ever appeared in logcat**, either boot
   (`adb logcat -d | grep -iE "onion|gossip|tornodeservice|controlport"` —
   zero matches both times) — the only failure signal this code path can
   produce never fired.
5. **`GossipServer`'s listener is alive and re-establishes fresh each
   boot**, confirmed via the same graceful-close fingerprint technique
   `BRICK_GOSSIP_REPORT.md` used: `/proc/net/tcp`+`/proc/net/tcp6` showed
   exactly 4 LISTEN sockets under this app's uid both times — the 2 fixed
   Tor ports (`127.0.0.1:39050`/`39051`, matching `TorEngine.kt`'s own
   `SOCKS_PORT`/`CONTROL_PORT` constants exactly) plus 2 ephemeral
   `::ffff:127.0.0.1`-bound ports (ephemeral by design — `GossipServer` and
   `LocalWebServer` both mint a fresh port every boot; **only the onion
   KEY, not the bound port, is meant to be stable**). Notably, **neither
   ephemeral port showed as bare `::1`** this session, unlike arc-1's
   finding — consistent with the Task-1 `getByName("127.0.0.1")` fix now
   also applying to `GossipServer`'s own bind (same class, same fix).
   `adb forward` + a raw garbage-frame probe (`\x00\x00\x00\x04PING`,
   removed after each probe) distinguished the two: one port returned clean
   EOF (`recv() == b''`, the exact `gracefulClose()` fingerprint — parse
   failure → `shutdownOutput()` → drain → `close()`) on both boots (port
   33317 first boot, port 40745 after restart — the port number itself
   changing between boots is the expected/correct behavior, not a
   discrepancy); the other timed out on the same garbage frame (consistent
   with `LocalWebServer`'s HTTP semantics waiting for a real request line).
   Re-checked immediately after each probe: the port was still `LISTEN` —
   the accept loop survived the malformed connection both times, matching
   arc-1's documented "one bad connection never kills the loop" guarantee.
6. **One notable, unexplained data point**: before any probing this
   session, the first-boot `GossipServer` port already showed exactly ONE
   prior `TIME_WAIT` connection in `/proc/net/tcp6`, from a remote ephemeral
   port neither `adb forward` nor this session's own tooling created. A
   plausible (not confirmed) explanation is Tor's own hidden-service
   reachability self-test — a documented Tor behavior of dialing its own
   freshly-published onion through the same edge/exit-connection path Task
   1's fix repaired — but this could not be independently attributed with
   certainty from adb-only evidence, and is reported as a candidate
   explanation, not a proven one.

**Stated plainly: this is real, reproducible, two-boot behavioral evidence
that the PRODUCTION `publishOnion()` path executes without error on every
launch — strictly stronger than "the code compiled and passed a unit test"
— but it is NOT a literal readout of the persisted `gossip_addr`/
`onion_key` string, and therefore does NOT independently confirm the
DoD's specific claim that the SAME onion identity persists byte-for-byte
across a restart.** That narrower claim remains open, blocked by the same
non-debuggable-release-build constraint Task 1's report already flagged for
HS-log access, and is properly closed by either a real live desktop dial
against two consecutive boots (August, or a future session with deeper
device access) or a small follow-up (a debug getter / an on-success log
line) rather than by anything adb-only tooling can extract from this build
today.

## On-device DoD (from the task-9 brief's Step 3)

| Item | Status |
|------|--------|
| (a) phone publishes a STABLE `.onion` (same across app restarts) | **PARTIAL — behaviorally confirmed, not literally confirmed.** Two consecutive boots both show: bootstrap completes, `publishOnion()` demonstrably runs before the first sync with zero logged failures, `GossipServer`'s own listener re-establishes and passes the graceful-close fingerprint. The literal `gossip_addr`/`onion_key` STRING comparison across boots could not be read (non-debuggable release build blocks `run-as`; no success log or RN-exposed getter exists). PENDING a literal string-level confirmation — August's to close, or a small logging/getter follow-up. |
| (b) desktop learns the phone's HAVE and dials it over Tor; sync completes, content flows desktop→phone without polling | **PENDING (August drives).** Task 1 already proved a desktop→phone onion dial reaches `GossipServer` (3/3, `rep=0x00`) using a temporary spike hook, not the production HAVE-driven path. Task 7's production wiring (`gossip_addr` sent in HAVE instead of `""`) is JVM-tested and reviewer-traced line-by-line, but a live end-to-end round — desktop learning the REAL onion via a REAL sync's HAVE, then dialing back — was explicitly out of scope for this session (the brief: "Do NOT re-run the full desktop-dial"). |
| (c) revoke the phone's device from the desktop → phone WIPES to First-Load on next sync | **PENDING (August drives) — but the gate itself is proven at the real wire.** `enterRevokedState` (Task 6) is JVM-tested (366/366, including the CRITICAL forged-revocation fix, 5 independently mutation-verified negative tests) and `SyncRevokeLoopbackTest.kt` (Task 8) proves a real hearth node's revocation cert triggers `PeerRefused` post-AUTH at the real wire. A live revoke from the desktop against the phone's actual paired identity, observed wiping the physical device to First-Load, was not performed this session (touching the device's real synced identity for a destructive, irreversible action is explicitly August's call). |
| (d) a defriend → the phone stops serving that identity | **PENDING (August drives) — but the gate itself is proven at the real wire, mutation-verified.** Task 8's `defriendedIdentityNotServedMidSession` scenario is the strongest form of this proof available today: a real hearth node signs+queues a defriend notice, the phone applies it, and the node is served NOTHING further on the next round — verified by deliberately disabling the phone's mid-session re-check and confirming the over-serve reproduces (test fails), then confirming it passes with the gate live. A live defriend from the desktop against the phone's real friend graph was not performed this session, same reasoning as (c). |
| (e) regression: loopback + outbound sync + First-Load pairing still work | **PROVEN at the code/loopback level; PARTIALLY proven on-device.** All prior loopback interlock suites (arc-1's `SyncLoopbackTest`/`SyncDmLoopbackTest`/`GossipServerTest`/`SyncPairLoopbackTest`/etc.) are part of this session's 372/372 JVM green run — zero regressions. On-device, outbound `syncCycle()` genuinely ran on both boots this session (evidenced by the notification's sync-count/timestamp advancing), though both attempts logged the benign "skipped (in progress)" outcome rather than a completed `OK` sync — consistent with mutex contention, not a failure, per the code's own documented semantics. First-Load pairing itself was not exercised this session (the device's existing real pairing was deliberately left untouched, per the same "don't touch the real synced identity" convention as (c)/(d)). |

## Honest boundary

Reproduced verbatim from the design spec's "Honest limits" section
(`docs/superpowers/specs/2026-07-23-phone-onion-reachability-design.md`):

> - Arc 2 proves reachability with THE DESKTOP (own sibling device) dialing the
>   phone. FRIENDS dialing the phone directly is arc 3 — own-device onion
>   addresses don't propagate to friends via gossip today (a friend peer table
>   + address advertisement to friends is arc 3).
> - The both-offline store-and-forward problem is untouched (arc 4).
> - The nudge/liveness channel folds onto this next: once the desktop can dial
>   the phone, a held connection + push is the small remaining step.
> - Self-revoke wipe is irreversible on the phone by design (re-pair to rejoin);
>   the identity survives on the desktop. The separate failed-unlock panic-wipe
>   (August's other slice-C idea, tied to at-rest App-lock) stays a future
>   feature — not this arc.
> - Onion-service viability on tor-android is proven by Task 1 or the arc is
>   BLOCKED there (report honestly; the revocation parity work is still valuable
>   for arc 3 regardless).

On that last bullet: Task 1 resolved the conditional — onion-service
viability on tor-android IS proven (not blocked), after the IPv4-bind root
cause was found and fixed.

## Follow-up tickets (all carried, non-blocking)

- **No ongoing per-message seq gate for a revoked device's newly-relayed
  messages** (Task 3, carried). `Verifier.verify_message`-equivalent gap:
  retro-drop (at ingest time) and the AUTH-time refusal (Task 5) cover the
  direct cases, but there is no ongoing per-message check that would catch
  a revoked device's message arriving via a THIRD party relay after the
  revocation. Cross-arc, unresolved by this task.
- **Blob-GC gap after purge** (Task 2, carried). `purgeAuthoredBy` tombstones
  messages but the phone has no refcounting concept for blobs, so a
  defriended author's blobs are not reclaimed. Pre-existing, unresolved.
- **SQLite give-side path is JVM-untestable** (Task 2, carried; module-wide,
  pre-existing). No Robolectric in this module means `SqliteSyncStore`'s
  wiring (including this arc's new `meta`/`revokedDevices` tables) can't be
  exercised in the JVM suite directly — correctness is by inspection plus
  the on-device install genuinely running `SqliteSyncStore` (confirmed
  again this session: `TorNodeService`/`GossipServer` both live on real
  Android), but no on-device test this session drove a real authenticated
  serve through it (see the DoD table's (c)/(d) rows) — the column-index
  wiring remains hand-verified, not run-verified on-device.
- **Literal `gossip_addr`/`onion_key` value is not independently readable
  by any current tooling** (new, this task). Non-debuggable release build
  blocks `run-as`; `publishOnion()` is silent-on-success by design; no
  RN-bridge method or in-app UI surface exposes it. A small follow-up (a
  debug-only getter, or a one-line success log gated behind a build flag)
  would close this gap for future on-device verification sessions without
  requiring a live desktop dial every time.
- **The "skipped (in progress)" first-sync outcome on both boots this
  session** (new observation, likely benign, not confirmed). Both boots'
  very first outbound sync attempt hit the documented benign mutex-
  contention path rather than completing. Plausibly explained by
  `GossipServer`'s accept path (now sharing `SyncRunner.syncLock`)
  contending for the lock in that same window — worth a closer look if a
  future session has time, but not blocking.
- **Silent accept-loop death** (Task 4/arc-1, carried). No log line exists
  anywhere to show the accept thread dying abnormally — matches this
  module's no-logging convention throughout, reconfirmed again this session
  (zero `Log.*` calls found in `TorNodeService.kt` outside the one failure
  path).
- **Worker-pool unbounded queue** (Task 4/arc-1, carried). `POOL_SIZE=4`
  has an unbounded internal task queue; now more relevant than arc-1's
  report judged it, since the loopback-only bind that made it "low risk
  pre-arc-2" is exactly what this arc removes. A queue depth cap is
  undone work worth prioritizing given the server is now reachable from
  outside the device.

**Forward-looking (next arcs, from the spec's own "Out of scope"):**

- **Arc 3 — friend peering + address propagation to friends.** The phone's
  own onion address does not yet propagate to friends via gossip; a friend
  peer table and phone-initiated friend dials are unbuilt.
- **Arc 4 — store-and-forward / the both-offline problem.** Untouched.
- **The nudge/liveness channel.** Folds onto this server next per the spec
  — a held connection + push frames, now that the desktop can dial in.
- **App-lock + failed-unlock panic-wipe.** A separate future feature from
  this arc's self-revoke wipe (August's other slice-C idea).

## Run gotchas (new + carried)

- **`MSYS_NO_PATHCONV=1` is required before any `adb shell` command with an
  absolute Unix-style path argument** (e.g. `/data/tombstones/`,
  `/proc/net/tcp6`) when running under Git Bash on Windows — otherwise Git
  Bash's automatic path-conversion mangles the argument into a bogus
  Windows path (observed this session: `/data/tombstones/` became
  `Files/Git/data/tombstones/` and failed).
- **Distinguishing `GossipServer`'s ephemeral loopback port from
  `LocalWebServer`'s across a restart**: both mint fresh ports every boot,
  so port NUMBER is never a stable identifier — re-run the graceful-close
  garbage-frame probe (`adb forward` + raw socket send + expect clean EOF)
  fresh each time rather than assuming the port from a prior boot still
  applies.
- **The Play Protect scan-consent dialog + monkey-tombstone false positive
  are both still exactly as documented in `BRICK_GOSSIP_REPORT.md`** —
  screencap-verify the dialog before tapping (coordinates can drift), and
  cross-check `/data/tombstones/` actual file dates before trusting
  `monkey`'s own "New tombstone"/"NativeCrash" self-report on its first
  invocation per session.
- **`run-as` is blocked on this build, confirmed again**: `run-as: package
  not debuggable: eu.kreds.torspike` — same constraint Task 1's own report
  hit trying to read the Tor HS log; still true for the `sync_store.db`
  meta table this session.

## After the run

On a pass, whether this merges to public main is August's call, same as
every prior brick. PAUSE here for human review per the task brief. This
closes arc 2 (phone-as-full-node's reachability + the security gate) at the
desk/production-path-consolidation level; the live behavioral DoD items
((b) desktop dial-back via the real HAVE path, (c) live revoke-wipes-phone,
(d) live defriend-stops-serving, and a literal restart-stability read of
`gossip_addr`) remain August's to run whenever he chooses — the honest
state of each is "the underlying gate is proven at the real wire; the live
field action against the phone's real identity has not yet been run,"
not "passed."
