# Android First-Load + Pairing Ceremony Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A fresh Android install shows a first-load screen; "Link to your node" runs a real QR/code pairing ceremony over Tor against the desktop's new Add-device screen, ending with the phone enrolled (full pairing package incl identity_priv) and synced.

**Architecture:** Hearth grows a pairing-code service + three `/api/pair/*` endpoints + a PRE-AUTH `pair-request` wire frame using the EXISTING first-frame-peek dispatch precedent (`_on_conn` already routes `friend-add` frames pre-AUTH with rate limiting, hearth/sync.py:172-188 + 315-339 — the pair frame is a second citizen of that exact pattern). The phone gets a Kotlin ceremony client (dial → pair-request+code → await package → install), internal-storage identity, and an RN FirstLoad gate with expo-camera QR scanning. Spec: `docs/superpowers/specs/2026-07-22-android-first-load-pairing-design.md`.

**Tech Stack:** Python (hearth, pytest), Kotlin (tor-manager module, JVM tests), React Native/Expo (FirstLoad screen, expo-camera), base58 invitecodec.

## Global Constraints

- **Branch:** `brick-first-load`. Commit prefix `feat/fix/docs/test(pairing)`, lowercase. **NO AI / Co-Authored-By / "Generated with" trailers.**
- **Ceremony (verbatim from spec):** link string = base58 of `{gossip_addr, code}` (invitecodec mechanics); code single-use, **TTL 600 s**, stored hashed, constant-time compare; wire frame = the standard pair-request shape (node.py:1961-1964) + `"code"`; desktop human Accept/Deny gates `node.accept_pairing` (node.py:1966-1987, UNCHANGED); package returns over the SAME held connection (server-side bound = code's remaining TTL); phone install mirrors `pair_install` semantics (node.py:1990-2010) onto the Kotlin store.
- **Pre-auth scoping (security):** an unauthenticated connection may submit ONLY a pair frame (or the existing friend-add frame); everything else still requires AUTH. Mirror `_handle_friend_add`'s rate-limit discipline (~20/min).
- **Phone storage:** canonical = app-internal `Context.filesDir/pairing.json` `{device_priv, device_pub, cert, identity_priv, onion_addr}`; `fixtureOrNull()` reads internal FIRST then legacy external `spike_phone_fixture.json` (G20 keeps working). identity_priv is stored but NEVER loaded into `KotlinHandshake.Fixture`.
- **Desktop↔desktop copy-paste pairing (bootstrap endpoints + CLI) unchanged.** All phone writes stay device-signed.
- **expo-camera:** permission via the library's config plugin in app.json — NEVER a manual AndroidManifest edit (the B2D2 lesson: manual manifest edits have no config plugin and `expo prebuild --clean` silently drops them). Camera requested only when the user taps Link.
- **UI copy:** first-load buttons "Link to your node" / "Start a new profile" (coming-soon note); success copy states history fills in over the next syncs.

**Test commands:** hearth pytest (repo root): `.venv/Scripts/python.exe -m pytest tests/ -q` (find the pairing tests' actual home first — grep `pair_request` under tests/). Kotlin JVM (from `android_tor_spike/app/android`, `JAVA_HOME=/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot`): `./gradlew :tor-manager:testDebugUnitTest`; XML count glob `../modules/tor-manager/android/build/test-results/testDebugUnitTest/*.xml`. tsc/vitest from `android_tor_spike/app`. Release: `./gradlew :app:assembleRelease`; G20 install force-stop-first, watch Play Protect dialog.

---

## File Structure

- `hearth/pairingcodes.py` (create) — code mint/verify/consume, TTL, hashing (pure, testable).
- `hearth/invitecodec.py` (modify) — `encode_pair(addr, code)` / `decode` gains a pair type.
- `hearth/api.py` (modify) — `POST /api/pair/begin`, `GET /api/pair/pending`, `POST /api/pair/accept`.
- `hearth/sync.py` (modify) — pre-auth `pair-request` frame branch in the first-frame peek + held-connection pending registry.
- `hearth/node.py` (modify) — thin glue only (pending-pairing state on the node object); `accept_pairing` untouched.
- `hearth/web/app.js` + `index.html` (modify) — Add-device UI in the Devices section; first-run "Connect" text fix.
- Kotlin (create): `KotlinPairing.kt` (link decode + frame build + install semantics), `PairingStore.kt` (internal pairing.json); (modify): `LocalApi.kt`/`TorManagerModule.kt`/`TorNodeService.kt` fixture dual-read + `hasIdentity` bridge + ceremony bridge method.
- RN (create): `app/FirstLoad.tsx`; (modify): `app/index.ts` gate, `app.json` (expo-camera plugin), `package.json`.
- Tests: `tests/test_pairing_codes.py` + wire/API additions in hearth's existing test homes; `KotlinPairingTest.kt`; `SyncPairLoopbackTest.kt`; report `android_tor_spike/BRICK_FIRSTLOAD_REPORT.md`.

---

## Task 1: hearth pairing-code service + `/api/pair/*` endpoints + link codec

**Files:**
- Create: `hearth/pairingcodes.py`, `tests/test_pairing_codes.py` (adjust to hearth's real test dir — grep where existing node/api tests live FIRST)
- Modify: `hearth/invitecodec.py`, `hearth/api.py`, `hearth/node.py` (pending-pairing state only)

**Interfaces:**
- Produces: `PairingCodes` class — `mint(now) -> str` (random base58 code, stores sha256 hash + `expires_at = now+600`, replaces any prior un-used code: ONE active code at a time), `verify_and_consume(code, now) -> bool` (constant-time `hmac.compare_digest` on the hash; False if expired/used/none; consuming is atomic — a second call with the same code returns False). Node glue: `node.pairing = PairingCodes()` instance + `node.pending_pair: Optional[dict]` + `node.pending_pair_event: asyncio.Event` (sync.py consumes in Task 2).
- Produces: `invitecodec.encode_pair(addr: str, code: str) -> str` and `decode(...)` returning a `("pair", addr, code)`-shaped result for pair strings (read `encode_invite`/`decode` at invitecodec.py:132-160 and follow the exact same base58/prefix mechanics with a new type tag).
- Produces API: `POST /api/pair/begin` → `{"link": <pair string>, "expires_at": ...}`; `GET /api/pair/pending` → `{"pending": null}` or `{"pending": {"device_name":..., "device_pub":...}}`; `POST /api/pair/accept` body `{"device_pub":..., "accept": true|false}` → on true runs `node.accept_pairing(request_json)` and hands the package to the waiting wire connection (Task 2 wiring), on false rejects it. 400s via the existing `_400` idiom.

- [ ] **Step 1: Write failing tests** — `PairingCodes`: mint→verify True once→False after (consumed); expired (now+601) False; wrong code False; second mint invalidates the first; constant-time compare used (assert `hmac.compare_digest` referenced — read how hearth tests assert such things or drop to behavioral). Codec: `decode(encode_pair("abc.onion:9997","XyZ"))` round-trips; malformed string → the codec's established error behavior (read decode's current malformed handling and pin it). API: begin returns a decodable link; pending empty → null; accept with no pending → 400. Mirror hearth's existing API-test fixtures (grep tests for `/api/friend/invite` style tests and copy their harness pattern).
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Implement** (`pairingcodes.py` pure stdlib: `secrets`, `hashlib`, `hmac`; codec addition; api endpoints; node glue fields).
- [ ] **Step 4: Run → pass.** Full hearth pytest suite — ZERO regressions.
- [ ] **Step 5: Commit** — `feat(pairing): pairing-code service + /api/pair endpoints + pair link codec`.

---

## Task 2: hearth pre-auth `pair-request` wire frame (held-connection accept)

**Files:**
- Modify: `hearth/sync.py` (the `_on_conn` first-frame peek block, sync.py:172-188, + a `_handle_pair_request` sibling of `_handle_friend_add`, sync.py:315-365 area)
- Test: hearth's existing sync/wire test home (grep tests for `_handle_friend_add`/friend-add wire tests and extend the same harness)

**Interfaces:**
- Consumes: `node.pairing.verify_and_consume`, `node.pending_pair`/`pending_pair_event` (Task 1), `node.accept_pairing` (existing, unchanged).
- Produces (wire contract, Task 4/6 consume): client sends frame `{"t":"hearth-pair-request","protocol":PROTOCOL,"device_pub":...,"device_name":...,"code":...}`; server replies EXACTLY ONE of: `{"t":"hearth-pair-package", ...}` (the untouched accept_pairing output), `{"t":"pair-denied"}`, `{"t":"pair-expired"}` — then closes. Timing: the frame's `code` is verified IMMEDIATELY (bad/expired/reused → `pair-expired`, close, no pending surfaced); on success the request parks as `node.pending_pair` and the coroutine awaits `pending_pair_event` with `asyncio.wait_for(timeout = remaining TTL)`; `/api/pair/accept` (Task 1) sets a verdict field + fires the event; timeout → `pair-denied`... no: timeout → `pair-expired`; explicit deny → `pair-denied`.
- **Scoping invariant (test it):** the pre-auth peek accepts ONLY `friend-add` (existing) and `hearth-pair-request` frames from unauthenticated peers; any other first frame follows the existing AUTH-or-refused path exactly as today. Rate-limit the pair branch with the same throttle mechanism `_handle_friend_add` uses (read sync.py:325-335).

- [ ] **Step 1: Failing tests** — happy path (mint code via node.pairing, client coro sends frame, api-accept fires, client receives package with `identity_priv` present and cert.device_pub == the request's); wrong code → `pair-expired` + nothing pending; reuse after success → `pair-expired`; deny → `pair-denied` + device NOT in views; a second pair frame while one is pending → `pair-expired` (single active ceremony); non-pair non-friend-add first frame from a stranger still refused per the existing tests (extend, don't weaken).
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run → pass.** Full hearth suite (the 925-test desktop suite must stay green).
- [ ] **Step 5: Commit** — `feat(pairing): pre-auth pair-request wire frame with held-connection accept`.

---

## Task 3: Kotlin — link decode, internal PairingStore, package install, fixture dual-read

**Files:**
- Create: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/KotlinPairing.kt`, `.../PairingStore.kt`
- Modify: `LocalApi.kt` + `TorManagerModule.kt` + `TorNodeService.kt` (every `fixtureOrNull()`/`fixture()` read site — grep them — goes through one shared dual-location reader)
- Test: `.../test/.../KotlinPairingTest.kt`

**Interfaces:**
- Produces: `KotlinPairing.decodeLink(link: String): Pair<String,String>?` (onion addr, code; null on malformed — byte-parity with Task 1's `decode`); `KotlinPairing.buildRequestFrame(devicePub, deviceName, code): Map<String,Any?>` (the Task 2 wire shape, PROTOCOL constant reused from KotlinWire/KotlinHandshake — grep where PROTOCOL lives); `KotlinPairing.installPackage(store: SyncStore, pkgJson: String, devicePriv: String, devicePub: String, deviceName: String): PairingStore.Identity` — mirrors pair_install semantics (node.py:1990-2010): validate `t=="hearth-pair-package"`, addIdentity(self), save own device view w/ cert, addIdentity per `friends`, addPeer per `peers` + `my_addr`; returns the persisted identity record. `PairingStore.save(ctx, Identity(device_priv, device_pub, cert, identity_priv, onion_addr))` → `filesDir/pairing.json` (atomic write: temp+rename); `PairingStore.load(ctx): Identity?`; `PairingStore.hasIdentity(ctx): Boolean` (internal OR legacy external present).
- The shared fixture reader: internal `pairing.json` first (mapped to `KotlinHandshake.Fixture(device_priv, device_pub, cert, onion_addr)` — identity_priv NEVER copied into Fixture), else legacy external file exactly as today.

- [ ] **Step 1: Failing tests** — decodeLink round-trip against a REAL Task-1-encoded string (generate one via `.venv/Scripts/python.exe -c "from hearth.invitecodec import encode_pair; print(encode_pair('abc...xyz.onion:9997','TESTCODE123'))"` and pin it as a vector — cross-language parity, the seal_vector lesson); malformed → null. buildRequestFrame shape (all 5 keys, exact `t`). installPackage: feed a hearth-shaped package dict (mirror node.py:1982 fields), assert store state — own identity is_self, device view saved w/ cert, friends known, peers + my_addr added (read SyncStore's peer API first; if the phone store has no peer table, the onion_addr in PairingStore.Identity IS the peer record — implement whichever the store supports and document); `t` wrong → IllegalArgumentException. PairingStore save/load round-trip incl identity_priv; hasIdentity false→true; Fixture mapping excludes identity_priv (assert the Fixture object has no such field — compile-time; assert loaded fixture equals expected 4 fields).
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Implement** (PairingStore uses plain java.io atomic write; dual-read wired into the ONE shared reader all call sites use — refactor the three duplicated read sites onto it).
- [ ] **Step 4: Run → pass.** Full module suite.
- [ ] **Step 5: Commit** — `feat(pairing): link decode + internal pairing store + package install + fixture dual-read`.

---

## Task 4: Kotlin ceremony client + native bridge

**Files:**
- Modify: `KotlinPairing.kt` (add `runCeremony`), `TorManagerModule.kt` (bridge: `hasIdentity(): Boolean` constant/function + `pairWithNode(link: String, deviceName: String): Promise` async function emitting progress events)
- Test: `KotlinPairingTest.kt` (frame/dispatch pieces; the live path is Task 6's gate)

**Interfaces:**
- Produces: `KotlinPairing.runCeremony(dial: (host: String, port: Int) -> Stream, link: String, deviceName: String, timeoutMs: Long): CeremonyResult` — sealed result: `Linked(identity: PairingStore.Identity)`, `Denied`, `Expired`, `Unreachable(reason)`, `BadLink`. Internally: decodeLink → generate Ed25519 device keypair (mirror how mint.py/DeviceKeys.create shapes keys — grep how KotlinHandshake/fixtures represent device_priv hex) → dial → write pair-request frame → read ONE reply frame (bound by timeoutMs) → package: installPackage + PairingStore.save (onion_addr = the dialed addr) → Linked; `pair-denied`→Denied; `pair-expired`→Expired. Frame I/O reuses the module's existing frame read/write used by KotlinSync (grep writeFrame/readFrame usage in SyncRunner/KotlinSync — same length-prefix mechanics, do NOT hand-roll).
- Bridge: `pairWithNode` runs runCeremony off the main thread with `TorEngine.dial`, resolves `{status: "linked"|"denied"|"expired"|"unreachable"|"bad_link"}`; `hasIdentity` exposes `PairingStore.hasIdentity`.

- [ ] **Step 1: Failing tests** — runCeremony against a FAKE in-process Stream pair (the module's tests already fake Streams for handshake tests — grep KotlinHandshakeTest for the pattern): scripted server returns a valid package → Linked + store/pairing.json state; returns pair-denied → Denied; returns pair-expired → Expired; garbage reply → Unreachable; malformed link → BadLink without dialing (dial lambda asserts not called).
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run → pass.** Full module suite.
- [ ] **Step 5: Commit** — `feat(pairing): ceremony client + hasIdentity/pairWithNode bridge`.

---

## Task 5: Desktop Add-device UI + first-run text fix

**Files:**
- Modify: `hearth/web/app.js` (Devices section, app.js:3346-3363 area; first-run connect text app.js:3472-3473), `hearth/web/index.html` (if the section needs static scaffolding — follow how existing me-strip panels are built)

**Interfaces:** Consumes Task 1's `/api/pair/begin`, `/api/pair/pending`, `/api/pair/accept`, and the existing `/api/qr?text=` renderer (grep its exact query contract in api.py:691-696 + an existing app.js caller).

- [ ] **Step 1: Read** the Devices section renderer + one existing modal/panel flow (the friend-add manual ceremony, app.js:4392-4444, is the closest precedent) and report the seam.
- [ ] **Step 2: Implement** — an "Add device" button in Devices → panel: calls pair/begin, shows the QR image + the link string in a copyable input + a countdown from expires_at; polls pair/pending every ~2 s while open; on pending shows "**<device_name>** wants to link" + Accept / Deny buttons → pair/accept; success/deny states; closing the panel stops polling. Fix the first-run connect instructions (app.js:3472-3473) to name the now-real flow ("open Kreds on your other device → Devices → Add device"). Match app.js's existing vanilla-JS style exactly — no frameworks, same helper idioms.
- [ ] **Step 3: Gate** — `node --check hearth/web/app.js`; hearth pytest still green (endpoints exercised in Task 1's tests; the UI itself is on-device/manual per this codebase's convention — the desktop web has no JS test runner; state this in the report).
- [ ] **Step 4: Commit** — `feat(pairing): desktop add-device screen + first-run connect text fix`.

---

## Task 6: Loopback ceremony gate — real node, full ceremony, then a real authenticated sync

**Files:**
- Modify: `android_tor_spike/tools/sync_loopback_node.py` (add a `pairing` scenario)
- Create: `.../test/.../SyncPairLoopbackTest.kt`

- [ ] **Step 1: Extend the harness** — scenario boots a REAL hearth node (existing harness pattern) with the Task 2 wire handler active, seeds one journal post + one friend, mints a pairing code via `node.pairing.mint`, prints `{"event":"pair_ready","link":encode_pair(addr, code)}`. An auto-accept driver: when `node.pending_pair` appears, call the REAL `/api/pair/accept` path (or the node-level accept glue the API calls — the same function, not a shortcut) with accept=true; ALSO support a `deny` sub-scenario (accept=false) and an `expired` one (mint, then let the Kotlin side present a WRONG code). Emit `{"event":"paired","device_pub":...}` after accept with the enrolled device_pub from views.
- [ ] **Step 2: Kotlin test** — `KotlinPairing.runCeremony` with a REAL socket dial to the loopback node using the harness's link: assert `Linked`; assert `pairing.json` persisted with identity_priv == the node's real identity_priv (readable from the harness fixture output — the ULTIMATE full-pairing assertion); THEN run the standard sync (SyncRunner/KotlinSync path the other loopback tests use) with the NEW fixture — assert it authenticates (the node enrolled the cert for real) and pulls the seeded post + friend. Deny scenario → `Denied`, nothing persisted, subsequent sync with the unenrolled keys REFUSED by the node (negative auth proof). Wrong code → `Expired` before any pending appears.
- [ ] **Step 3: Run** (`--tests "expo.modules.tormanager.SyncPairLoopbackTest"`); a failure = REAL ceremony bug → BLOCKED, never weaken.
- [ ] **Step 4: Full JVM suite + full hearth pytest.**
- [ ] **Step 5: Commit** — `test(pairing): loopback gate -- full ceremony against a real node, then a real authenticated sync`.

---

## Task 7: RN FirstLoad screen + camera + gate

**Files:**
- Create: `android_tor_spike/app/FirstLoad.tsx`
- Modify: `android_tor_spike/app/index.ts` (gate), `app.json` (expo-camera plugin + permission copy), `package.json` (expo-camera)

- [ ] **Step 1: Install** `npx expo install expo-camera` (version matched to the project's Expo SDK); add its config plugin to app.json (NEVER manual manifest edits — the B2D2 prebuild lesson; verify after `assembleRelease` that CAMERA permission landed in the merged manifest via aapt or the built manifest).
- [ ] **Step 2: Implement FirstLoad.tsx** — on mount call the `hasIdentity` bridge: true → render `WebShell` (import unchanged); false → the two-button screen ("Link to your node" / "Start a new profile" → inline coming-soon note). Link flow: request camera permission ON TAP → `CameraView` barcode scan (or "type the code instead" → TextInput) → call `pairWithNode(link, deviceName)` with a device-name field defaulting to the device model (`expo-device` or the RN Platform constants — whichever the project already has; if neither, a plain "phone" default + editable input, no new dep) → progress states (dialing… / waiting for your desktop to accept… / installing…) → on `linked`: success note "Linked — your history will fill in over the next syncs" → mount WebShell; on denied/expired/unreachable/bad_link: distinct retryable messages. index.ts registers a root component that renders FirstLoad (which decides). Keep styling minimal-clean (the visual-parity pass is its own future slice — standing decision).
- [ ] **Step 3: Gates** — `npx tsc --noEmit` (0 new vs the 14 known @types/node); full vitest (unchanged, 29/29); `./gradlew :app:assembleRelease` builds with the camera plugin; verify merged-manifest CAMERA permission + that the LEGACY G20 fixture path still boots straight to WebShell (hasIdentity true via external file — JVM-tested in Task 3; here just confirm the release build).
- [ ] **Step 4: Commit** — `feat(pairing): first-load screen with qr link ceremony + coming-soon create stub`.

---

## Task 8: On-device DoD + report + PAUSE

**Files:**
- Create: `android_tor_spike/BRICK_FIRSTLOAD_REPORT.md`

- [ ] **Step 1: Desk-gate sweep** — full JVM (XML count), full hearth pytest, tsc (0 new), vitest, assembleRelease, merged-manifest CAMERA check. Record outputs.
- [ ] **Step 2: Install RELEASE on the G20** (force-stop; Play Protect gotcha). NOTE: do NOT clear the G20's app storage — its legacy fixture is the regression case for the dual-read.
- [ ] **Step 3: On-device DoD (August drives)** — (a) G20 with legacy fixture boots straight to the web UI (no first-load screen); (b) fresh state (second device, or August clears storage AFTER (a) passes): first-load screen appears; (c) desktop → Devices → Add device shows QR + code + countdown; (d) scan with the phone → desktop shows "<device> wants to link" → Accept → phone lands in web UI; (e) after a sync round, history appears; (f) typed-code fallback works; (g) Deny shows the denied message and the phone can retry; (h) camera permission prompts only on Link tap; (i) desktop↔desktop copy-paste pairing still works (regression, CLI or bootstrap screen).
- [ ] **Step 4: Write the report** — desk gates, ceremony proof (Task 6: real node enrolled the phone AND the new device synced authenticated; deny/expired negative proofs), DoD checklist PENDING, gotchas, honest boundary (spec's Honest limits verbatim incl the identity_priv custody note + August's ticketed panic-wipe direction), follow-up tickets.
- [ ] **Step 5: Commit + PAUSE** — `docs(pairing): on-device proof record + DoD`. Merge is August's call.

---

## Self-Review

**1. Spec coverage:** code service/endpoints/link codec → T1; pre-auth wire frame + held accept + scoping → T2; phone storage/install/dual-read → T3; ceremony client + bridge → T4; Add-device UI + first-run text → T5; loopback proof (incl full-pairing identity_priv assertion + post-enroll authenticated sync + deny/expired) → T6; FirstLoad/QR/camera/coming-soon → T7; DoD/report/PAUSE → T8. Honest-limits copy → T8 report. All spec sections mapped.
**2. Placeholder scan:** T1/T2 test homes and T5's section renderer are read-first-and-follow-precedent steps anchored to exact files/lines (invitecodec.py:132-160, sync.py:172-188/315-365, app.js:3346-3363/4392-4444) — deliberate, not TBD. T3's peer-API branch states both permitted outcomes explicitly. No bare TODOs.
**3. Type consistency:** `encode_pair(addr, code)`/`decode` (T1) ↔ `decodeLink` vector test (T3) ↔ harness `pair_ready` link (T6). Wire frame + reply `t` values identical in T2 (produces), T4 (client), T6 (gate). `PairingStore.Identity(device_priv, device_pub, cert, identity_priv, onion_addr)` consistent T3/T4/T6. Bridge names `hasIdentity`/`pairWithNode` consistent T4/T7. `CeremonyResult` variants consistent T4/T6/T7 (status strings lowercase).

**Implementer notes:** `accept_pairing` and desktop↔desktop pairing are UNTOUCHED — additive only. The pre-auth peek has a live precedent (`friend-add`) — extend it, don't invent a parallel listener. identity_priv never enters `KotlinHandshake.Fixture`. The B2D2 prebuild hazard governs ALL manifest needs: config plugins only.
