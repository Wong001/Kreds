# Recipient-Side Post Re-Grants (Multi-Device Relay) — desk proof record

**A friend's old post, decrypted only on the desktop, is now readable on
every one of the recipient's own devices without the friend coming back
online.** `maintain_received_post_grants` (hearth) mirrors B.2c's
`maintain_received_dm_grants` for `KIND_POST`: any own device that has
already recovered a friend post's content key re-wraps that key to this
identity's OTHER enrolled devices via a RECIPIENT-signed `wrap_grant`.
Both consumers — hearth's own `_content_key` (desktop/second-desktop read
path) and the phone's `DecryptPass.resolveWrap` (Kotlin) — now trust that
grant shape under matching, parity-checked conditions. This is Slice A of
the live-sync/revocation arc (A -> B nudge channel -> C revocation wipe).

Branch: `brick-post-regrants` (off main `478077a`, via spec commit
`e3d3df9`). HEAD: `1506e3a`.
Spec: `docs/superpowers/specs/2026-07-23-recipient-post-regrants-design.md`.
Plan: ledger section `PLAN: 2026-07-23 recipient-post-regrants` in
`.superpowers/sdd/progress.md`.

4 code tasks (Task 1 sweep, Task 2 hearth consumer trust rule, Task 3
Kotlin `resolveWrap` extension, Task 4 loopback parity gate), all APPROVED
at review with zero blocking findings — Task 4 passed first attempt with
zero findings at all. Global constraints held throughout:
`feat/fix/docs/test(regrants)` lowercase, no AI/Co-Authored-By trailers;
full-coverage mints preserving the prune-safety invariant
(`store.py:480-496`); targets always `store.enckeys(own) minus self` —
never a friend's device; the three sibling sweeps
(`maintain_wrap_grants`, `maintain_own_device_grants`,
`maintain_received_dm_grants`) left byte-identical; additive compatibility
(old clients' author-only rule simply ignores the new grant shape).

This is the DESK portion of Task 5 only. **Per an explicit sequencing
directive, no APK was installed on the G20 this session** — see "On-device
DoD" below.

## Desk gates (all GREEN — this session, against HEAD `1506e3a`)

Commands run from a clean working tree (`git status` clean, HEAD =
`1506e3a` at session start).

| Gate | Command | Result |
|------|---------|--------|
| Full hearth pytest | `.venv/Scripts/python.exe -m pytest -q` (repo root) | **1063 passed, 9 skipped**, 4 warnings, 82.51s — matches expected baseline exactly |
| Full JVM suite (`tor-manager`) | `JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot" ./gradlew :tor-manager:testDebugUnitTest --rerun-tasks` (from `android_tor_spike/app/android`) | **BUILD SUCCESSFUL**, 62/62 tasks executed |
| XML result count | glob over `../modules/tor-manager/android/build/test-results/testDebugUnitTest/*.xml` | **253 tests, 0 failures, 0 errors**, 28 result files — matches expected 253/0 |
| `npx tsc --noEmit` | from `android_tor_spike/app` | **14 errors, all pre-existing `@types/node`** in `src/__tests__/wire.test.ts`, `test/web-readonly-seam.test.ts`, `tools/handshake_cli.ts`, `tools/node_stream.ts`, `tools/roundtrip_cli.ts` — same file set/count as the outbound family baseline. **0 new.** |
| `npx vitest run` (full) | from `android_tor_spike/app` | **29/29** (2 test files) — this branch predates the pairing slice's seam addition, so the file/test count is unchanged from the pre-pairing baseline (no new seam test present here to verify against) |
| `:app:assembleRelease` | from `android_tor_spike/app/android` | **BUILD SUCCESSFUL**, apk at `app/build/outputs/apk/release/app-release.apk` (55,454,542 bytes), 365 tasks (65 executed, 300 up-to-date). **Build-only proof — NOT installed on any device this session** (see sequencing directive below). |

No new tsc errors, no vitest regressions, no JVM regressions, no hearth
regressions. JVM suite composition across the slice's 4 tasks: Task 1
added `test_received_post_grants.py`'s sweep-level tests (hearth-side,
counted in the pytest total, not JVM); Task 3 extended
`DecryptPassTest.kt`; Task 4 added `SyncRegrantLoopbackTest.kt` (2 tests:
happy path + negative control) to the JVM total, which the review's fresh
re-run already confirmed at 251->253.

## Gate proof

### T4 — the loopback fidelity gate (the headline proof)

`SyncRegrantLoopbackTest.kt`
(`android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/SyncRegrantLoopbackTest.kt`)
starts a **real hearth node subprocess**
(`android_tor_spike/tools/sync_loopback_node.py`'s `_run_regrants`
scenario) and reproduces the spec's Goal paragraph exactly, structurally
proving offline-author ordering rather than assuming it:

- **Structurally-proven offline-author ordering.** B ("Desk", the
  phone's own identity) and A ("Freja", a second real `HearthNode`,
  the friend) befriend and exchange enc keys BEFORE A ever composes, so
  A's `compose_post` automatic friend-wrap can only ever target B's
  THEN-current primary-device key — the phone's own device does not
  exist anywhere yet, not even as a cert. A composes one kreds journal
  post with a photo, is gossiped into B's store, and then goes OFFLINE
  permanently — never touched again for the rest of the scenario. The
  phone's own enc key is published fresh AFTER, over the real wire,
  strictly after A's last action — an ordering A could not possibly have
  seen, proven by construction rather than by timestamp comparison.
- **No-inline-wrap precondition (non-tautological).** Before asserting
  the post renders, the test asserts the post's `wraps` payload does NOT
  cover the phone's fresh device key, AND that no author-signed grant
  covers it either — ruling out both legitimate alternative paths so a
  pass genuinely isolates the recipient-signed backfill as the only
  candidate that could unlock it.
- **`no_sweep` negative control.** A second scenario
  (`regrants_no_sweep`) runs the identical fixture and connections but
  the harness never starts the periodic gossip loop the sweep call lives
  inside — "never calling it" is the cleanest possible suppression, no
  monkeypatch needed. The test re-asserts all three preconditions (no
  inline wrap, no author grant, no recipient grant — since the sweep
  that would mint one never ran) and then asserts the post does **NOT**
  render (`assertNull`), proving the happy-path render in the sibling
  test is attributable to the sweep and nothing else.
- **AEAD photo.** The happy path additionally recovers the post's
  content key through the recipient-signed grant and AEAD-decrypts its
  photo blob via `KotlinBlobCrypt.decryptBlob`, proving decrypt-on-read
  composes correctly with the new grant path end to end, including the
  independent check that the covering grant is signed by the recipient's
  OWN identity specifically (never the friend/author).

The gate passed first attempt — zero production changes needed.

### T1 — prune-safety, two-device scenario

`test_full_coverage_and_prune_safety`
(`tests/test_received_post_grants.py:211-277`) is the landmine test: two
sweep rounds across an enc-key rotation, with a SECOND own device enrolled
in between round 1 and round 2, so round 2's mint must be a FULL-COVERAGE
mint of every current own device (phone1 at its rotated key AND phone2,
freshly enrolled) — not merely a heal of the one stale entry. After
`store.prune_superseded_wrap_grants()` removes round 1's superseded grant,
the test asserts the surviving (round-2-only) grant alone still lets BOTH
current devices `unwrap_key` the true content key — the exact invariant
`store.py:480-496` requires (prune is safe ONLY because mints are
full-coverage). `test_sibling_sweeps_untouched`
(`tests/test_received_post_grants.py:280-334`) is the companion carry-
forward check: running all four sweeps together produces byte-identical
grant rows for the three siblings' own targets (own kreds-wall post,
received DM, own DM) versus a round that ran the siblings alone — the new
sweep neither re-mints nor strips anything it doesn't own. Keyspace
non-collision was verified (per Task 1 review) against all `make_wrap_grant`
call sites: each of the four sweeps signs as a distinct
`(identity_pub, target)` prune key, so `maintain_received_post_grants`'s
`(own, received-post-id)` row is exclusively its own.

### T2 — mutation-tested entitlement gate

`_content_key`'s `KIND_POST` branch (`hearth/node.py:2966-2999`) unions
`store.wrap_grants(msg_id, self.identity_pub)` into the author-signed
grant set, but ONLY when the post's author is a known identity other than
self — the entitlement gate the reviewer mutation-tested by disabling it
and confirming `test_content_key_rejects_own_grant_for_nonfriend_post`
then genuinely failed (a real discriminator, not a tautological check
that would pass either way). Three tests exercise it:
`test_content_key_honors_recipient_grant_for_friend_post` drives the
positive case through the REAL read path (`cache_message_keys` + `feed`)
on a genuine second `HearthNode` sharing the recipient's identity — not
`_content_key` called bare. `test_content_key_rejects_third_identity_grant`
constructs a validly-signed, recipient-SHAPED grant from a THIRD identity
that would successfully unwrap the true content key if trusted — proving
rejection is a trust decision, not an accident of wrong key material.
`test_content_key_rejects_own_grant_for_nonfriend_post` does the mirror
case: an own-signed grant for a stranger's post (the compromised-device
threat model — some other on-device compromise minted it, since the
legitimate sweep would have refused a non-friend author) is independently
rejected by the read path, defense in depth. Routing was verified to need
no change: `messages_not_in`'s `KIND_WRAP_GRANT` gate (`store.py:730-733`)
is already signer-and-target-agnostic, and the sweep's targets rule
(own enckeys only) guarantees a recipient-signed post grant's wraps are
always a subset of the signer's own devices — it can never widen routing
to a friend.

### T3 — collision-order adversarial test

`DecryptPassTest.authorSignedGrantWinsOverOwnRecipientGrantOnCollision`
(`android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/DecryptPassTest.kt:538-585`)
constructs BOTH an author-signed grant (older `created_at`, the REAL wrap)
and an own-recipient-signed grant (deliberately NEWER `created_at`,
carrying a syntactically-valid but semantically BOGUS wrap that would
fail to decrypt if ever tried) covering the same device, and asserts the
post still decrypts — proving author-signed coverage wins
unconditionally, not merely "whichever grant is chronologically newest."
This is a deliberate structural deviation from the DM branch's shape: a
naive port combining both signers into one newest-wins fold (the DM
branch's own combined-signer-set shape) would have let the newer,
bogus recipient grant silently shadow the real author grant and the post
would vanish from the feed. `resolveWrap`'s posts branch instead performs
two separate single-signer lookups — author first, applied unconditionally
if it covers the device; only on a miss does it fall back to the
own-recipient-signed lookup, gated on the author being a known identity
other than the phone's own — byte-identical in effect to hearth's own
`{**recipient_grants, **grants}` merge order (author-signed dict applied
last, so it wins on any same-device key collision). Parity was walked
against `node.py:2966-2999` over all three input classes, including the
asymmetric case where only the recipient-signed grant (not the
author-signed one) covers the device.

## On-device DoD — DEFERRED-PENDING-MERGE-SEQUENCING

**Status: DEFERRED. No APK was installed on the G20 this session, by
explicit direction, and Step 3 (the field DoD itself) did not run.**

**Exact reason:** the G20 currently runs the `brick-first-load` branch's
build (the `FirstLoad` screen + internal `PairingStore`) and may be
holding a freshly re-paired identity in internal `pairing.json`. This
branch (`brick-post-regrants`) predates that code entirely — it forked
from main before the pairing slice existed. Installing this branch's
`assembleRelease` APK over the phone's current install would remove the
FirstLoad/PairingStore code paths the phone's present install depends on,
orphaning whatever identity is currently paired there. That risk is not
worth taking to run a DoD checklist that can just as easily wait.
Sequencing the two branches' merges (pairing slice first, then this
slice, then one combined build) is explicitly August's call, not
something to be resolved by silently downgrading the phone.

**Checklist for when the combined build runs (August drives):**

- [ ] Laptop (the friend/author account) is OFFLINE for the whole
      scenario.
- [ ] Desktop (this slice, on whatever device holds August's own
      identity) runs its normal sync/sweep tick — `maintain_received_post_grants`
      mints recipient-signed grants for the laptop account's old posts.
- [ ] Phone syncs against the desktop (or against any node that has
      already run the sweep).
- [ ] The laptop account's OLD posts (composed before the phone's own
      enc key existed) render on the phone — both text and photos.
- [ ] Regression: the phone's OWN posts render unaffected.
- [ ] Regression: DMs (read via B.2c's existing recipient-signed DM
      re-grants) render unaffected — this slice touches `KIND_POST` only,
      never the DM consumer path.

## Honest limits

Reproduced verbatim from the design spec's "Honest limits" section
(`docs/superpowers/specs/2026-07-23-recipient-post-regrants-design.md`):

> - A post becomes multi-device-readable only once AT LEAST ONE recipient
>   device that can already decrypt it comes online to mint (for August:
>   the desktop, which is usually on). If every recipient device post-dates
>   the content and the author is gone forever, it stays unreadable —
>   inherent to end-to-end encryption, unchanged.
> - Re-grants replicate READABILITY, not trust: revoked devices are excluded
>   by the targets rule (enckeys of enrolled devices only), and rotation
>   hygiene is preserved (grants re-mint per rotation like the siblings).
> - Store growth: one grant row per friend post per rotation period, pruned
>   by the existing superseded-grant prune — same growth class the sibling
>   sweeps already manage.

## Follow-up tickets (non-blocking, carried from the ledger)

- **Desktop never consumes B.2c recipient DM re-grants (the big one,
  empirically proven).** `_content_key`'s `KIND_DM` branch has no grant
  step at all — only `KIND_POST` got the recipient-signed union this
  slice. A SECOND DESKTOP device cannot read old friend DMs today; only
  the phone's Kotlin `DecryptPass` (B.2c) can. This is the exact bug
  class this slice fixes for posts, still open for DMs. The Task 2
  reviewer probed and empirically confirmed this gap (not a theoretical
  concern). Candidate: extend `_content_key`'s `KIND_DM` branch with the
  mirror-image union this slice added for `KIND_POST` — own follow-up
  slice/ticket, not part of this one.
- **4-sweep boilerplate duplication.** The four sweeps
  (`maintain_wrap_grants`, `maintain_own_device_grants`,
  `maintain_received_dm_grants`, `maintain_received_post_grants`) share
  near-identical structure (guard, targets derivation, full-coverage mint
  loop). Flagged as a deliberate auditability pattern (each sweep is
  independently readable/reviewable in isolation), not an accident — a
  shared-helper refactor is a candidate, not a requirement.
- **No inline expiry filter on received post grants.** Matches 2 of the
  3 sibling sweeps already; self-healing via the existing
  `sweep_expired` pass rather than a per-sweep check. Consistent with
  precedent, not a gap unique to this slice.
- **`store.wrap_grants` docstring staleness (cosmetic).**
  `hearth/store.py:775-781`'s docstring still reads "Author-filtered on
  purpose: a grant is only honored when signed by the post's own
  author" — true of the SQL-level union itself, but the CALLER
  (`_content_key`'s `KIND_POST` branch) now also calls it a second time
  with `self.identity_pub` as the author filter and unions the result.
  The function's own behavior is unchanged and correct; only the
  docstring's framing reads as if `wrap_grants` alone still gates trust,
  when the two-call-then-union pattern in the caller is what actually
  does. One-line documentation fix, no behavior change.
- **Asymmetric case (b) untested directly (Task 3).** `resolveWrap`'s
  posts branch was parity-verified by code-reading against all three
  input classes hearth's `{**recipient, **author}` merge can produce,
  including the asymmetric case where ONLY the recipient-signed grant
  (not the author-signed one) covers the device — but no dedicated
  `DecryptPassTest` case exercises that specific asymmetric shape in
  isolation (the collision test above exercises the "both exist" case;
  the happy-path loopback gate exercises "only recipient" but at the
  integration level, not a focused unit case). Test-strengthening
  candidate, not a known behavior gap.
- **Own-post-unreachable case has no dedicated test (Task 3).**
  `resolveWrap`'s recipient-grant fallback is gated on
  `m.identityPub != ownIdentityPub`, so an own-authored post can never
  reach the recipient-grant branch by construction (own-device coverage
  for those is `maintain_own_device_grants`' job, already covered by the
  author-signed branch above it) — verified structurally by reading the
  gate, not exercised by a dedicated test that constructs an own post
  with a hypothetical own-recipient-shaped grant and asserts the
  author-branch already satisfies it. Structural, not a known gap.

## After the run

Whole-branch review and merge decision are August's call, same as every
prior slice in this arc. This report covers the DESK gate sweep and gate
proof only — Step 3's field DoD is DEFERRED (see above) until the
pairing-slice and this slice's merges are sequenced into one combined
build. PAUSE here for human review per the task brief.
