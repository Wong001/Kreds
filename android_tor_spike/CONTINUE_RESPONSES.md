# Continuation prompt — execute the Android responses (reactions+comments) plan

Paste the block below into a fresh session (working dir `C:\Users\Wong\Desktop\Hearth\android_tor_spike\app`). It is self-contained — the fresh session has none of the prior conversation's context.

---

Execute the implementation plan `docs/superpowers/plans/2026-07-22-android-outbound-responses.md` using the **superpowers:subagent-driven-development** skill (fresh implementer subagent per task → per-task review [spec + quality] → fix loop → whole-branch review at the end → PAUSE for the on-device task).

**What this builds:** the second OUTBOUND/write slice for the Kreds Android WebView client — composing **reactions + comments (KIND_RESPONSE)** on journal posts natively in Kotlin, plus read-side de-anon. Spec: `docs/superpowers/specs/2026-07-22-android-outbound-responses-design.md`.

**Branch/base:** you are on `brick-outbound-responses` (off public main `7a82efc`). It currently holds only the spec (`040bf1d`) + plan (`85db7d1`) commits — Task 1's base is `85db7d1`. The prior outbound-1 slice (compose text+photo journal post) is already merged to main (PR #14) and is the crypto you REUSE.

**The model (get this right):** responses are NOT naively "private" — they are **name-to-those-who-know-you, alias-to-strangers**. If A comments on friend B's post, a mutual friend C (who knows A) sees A's real name; a stranger sees an alias. This is the `mutual_box` (seal_slots) mechanism — A seals its identity into slots readable only by A's OWN friends. **Parity invariant:** a phone-composed response is byte-identical to a desktop-composed one; who de-anons is the responder's friend graph, device-independent — proven by the Task 8 loopback gate + the seal vector round-trip.

**Global constraints (also in the plan's Global Constraints section — use those verbatim):**
- Commits `feat/fix/docs(responses)` lowercase, **NO AI / Co-Authored-By / "Generated with" trailers** (this is a hard standing rule from August — the repo README discloses AI use instead).
- Byte-exact crypto: `MUTUAL_BOX_AAD="hearth/mutual-box/v1"`, slot-kek HKDF info `"hearth/mutual-box-kek/v1"` (DISTINCT from wrap-kek `"hearth/dm-wrap/v1"`), `_SLOT_BUCKETS=(8,16,32,64)`, `deriveAliasSeed` keys on `device_priv` (raw Ed25519, NOT enc_priv), sealed payload `canonical({identity,device_pub,sig})`, mutual-box audience = MY friends (never the author's). A byte off = a friend on desktop can't open a phone-sealed box.
- Reuse (don't re-port): `KotlinDmcrypt.wrapKey/encryptBody`, `KotlinWire.signRaw/canonical/PyFloat`, the outbound-1 pending-outbound queue (`store.addPendingOutbound(msgId, wireDict)`), `store.enckeys(identity)`, the B.2d-4 `KotlinResponses` alias derivation + `DecryptPass.responsesPass`.
- Private-by-default (public_engagement toggle deferred); journal posts only; decrypt-on-read; device-signs only; the seam still hides every non-response write.

**Test env / commands:**
- `JAVA_HOME=/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot`, `ANDROID_HOME=~/AppData/Local/Android/Sdk`.
- Kotlin JVM (from `android_tor_spike/app/android`): `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.<Class>"`; full module: drop `--tests`.
- vitest (from `android_tor_spike/app`): `npx vitest run test/web-readonly-seam.test.ts`.
- Release APK: `./gradlew :app:assembleRelease` (NDK r27.1; apk at `app/build/outputs/apk/release/app-release.apk`).
- The loopback-gate tests (Task 8) spawn a REAL hearth node via `.venv/Scripts/python.exe` over loopback — the `.venv` must exist (existing `SyncLoopbackTest`/`SyncComposeLoopbackTest` already rely on it).
- G20 test phone = serial `ZY32DLZQ2N` (moto g20, package `eu.kreds.torspike`). Install the **RELEASE** apk (debug → "Unable to load script"); **force-stop first** (`adb shell am force-stop eu.kreds.torspike`) or the reinstall hangs on the running app; `adb install -r -d`.

**Ledger:** track progress in `.superpowers/sdd/progress.md` (append a new `=== PLAN: 2026-07-22 android-outbound-responses ===` section; one line per completed task with the commit range + review verdict). Task-brief / review-package scripts live under the subagent-driven-development skill dir (`~/.claude/plugins/cache/claude-plugins-official/superpowers/6.1.1/skills/subagent-driven-development/scripts/`).

**Standing working rules (from memory):** August drives the on-device behavioral run (Task 9), though Claude CAN build+install+adb-drive the G20 itself when the device is attached (screencap + logcat + tap). Claude does the automated suite + crypto/networking/sync testing. Quality over shortcuts — flag the cost, take the quality path. Merge is August's call (do NOT auto-merge; PAUSE at Task 9 and offer the PR). Never draft August's community-facing/outbound text.

**Model-selection for dispatches:** implementers on complete-code tasks → cheap tier; crypto/integration tasks (KotlinSeal T1, ComposeResponse T4, read de-anon T6, loopback gate T8) → standard tier; reviewers scaled to diff risk (weight the seal-crypto + compose-fidelity heavily); the final whole-branch review → the most capable tier. Always specify the model explicitly.

Start by reading the plan, noting the Global Constraints, creating a todo per task, then dispatch Task 1 (`KotlinSeal`). The on-device slices in this session's history are the pattern: per-task review caught real bugs each time; keep the discipline.

---

(For continuity, the two prior write slices are on main: outbound-1 = compose text+photo journal post [PR #14, merge 7a82efc]; the read-only visual-parity arc [journal/messages/profile, PRs #11-13] preceded it. `BRICK_OUTBOUND1_REPORT.md` documents the outbound-1 crypto this slice reuses + its follow-up tickets.)
