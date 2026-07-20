# Android B.2d-4 — Responses (reactions + comments) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The phone's feed shows reactions (counts) + comments under posts, decrypted from the aggregated `KIND_RESPONSES` record, with public commenters attributed to a real friend ONLY when their `responder_sig` verifies AND the signing device is enrolled to that identity, and private commenters shown as their client-derived alias.

**Architecture:** View-only, no hearth change, no composing, no blobs. Port one AAD (`responsesAad`); decrypt the latest `KIND_RESPONSES` per post via the existing content-key/`resolveWrap`/`unwrapKey`/`decryptBody` path; validate + verify each entry (`responder_sig` via `KotlinWire.verifyRaw` + device-binding from the phone's `messages` table); resolve to name or alias; render. `seal_slots` de-anonymization deferred (private → alias, never a wrong identity).

**Tech Stack:** Kotlin (`KotlinDmcrypt`/`KotlinWire`/`DecryptPass`/`SqliteSyncStore`/`TorManagerModule`), React Native (App.tsx), Python `hearth` (vector generator tool only).

**Spec:** `docs/superpowers/specs/2026-07-20-android-b2d4-responses-design.md`

## Global Constraints

- **Commit messages: NO AI/Co-Authored-By trailers.** Style `feat(b2d4):` / `fix(b2d4):` lowercase.
- **NO hearth change.** `hearth/` touched only by the vector-generator tool (imports read-only). `KotlinWire`/`KotlinDmcrypt` (except the additive `responsesAad`), `MediaServer`, `KotlinImageDecode` consumed, not modified.
- **View-only:** nothing composes/reacts/comments/retracts/moderates from the phone. The phone decrypts only `KIND_RESPONSES` (the aggregated record) — never raw `KIND_RESPONSE`.
- **Security core (NEVER weaken):** a `public` entry is attributed to `identity` ONLY when BOTH `KotlinWire.verifyRaw(device_pub, responder_sig, responseSigPayload(...))` is true AND device-binding holds (`device_pub` ∈ the identity's enrolled devices, or the phone has NO view data for that identity → permissive, mirroring hearth `_device_bound`). Sig-alone is forgeable — device-binding is mandatory. A verify/bind failure → alias (do NOT attribute).
- **Private (or verify-failed) entries → client alias** from `alias_seed`; `seal_slots`/`mutual_box` DEFERRED (validate the mutual_box shape, never open it).
- **Fail-closed:** un-decryptable record, invalid entry, malformed field → dropped/empty, never a crash or a wrong attribution.
- **Exact crypto (byte-pinned from hearth):**
  - `responses_aad(author, target, created_at)` = `canonical({"type":"responses-aad","protocol":PROTOCOL,"from":author,"target":target,"created_at":ca})` (dmcrypt.py:58-62).
  - `_response_sig_payload` = `canonical({"target":target,"rkind":rkind,"body":body,"created_at":created_at,"responder":responder})` (node.py:1390-1397), signed with the responder's device key, verified via Ed25519.
  - `_sig_ok(pub, sig, body)` = Ed25519 verify (identity.py:79-84) = `KotlinWire.verifyRaw(pub, sig, body)` (KotlinWire.kt:149).
  - `_device_bound(id, dev)` (node.py:1399+): `views = load_views(id); if not views: return True; return dev in views`.
  - Entry (`_valid_response_entry`, node.py:93-131): `{rkind∈comment/reaction, body(comment 0<len<=MAX_COMMENT / reaction ∈ REACTION_TOKENS), created_at(num), alias_seed(hex32), public(bool), responder_sig(hex128), mutual_box(valid shape)}` + `identity(hex64)`/`device_pub(hex64)` iff public. `REACTION_TOKENS = ("heart","laugh","wow","sad","up","fire")`; `MAX_COMMENT = 500`.
  - Alias (app.js:134-155): `ALIAS_ADJECTIVES = ["Quiet","Bright","Gentle","Bold","Calm","Swift","Kind","Curious","Merry","Steady","Lucky","Sunny","Brave","Soft","Sharp","Wandering"]`; `ALIAS_ANIMALS = ["Fox","Otter","Heron","Wren","Lynx","Hare","Finch","Badger","Seal","Crane","Moth","Robin","Deer","Owl","Sparrow","Marten"]`; `aliasName(seed) = ADJ[hex(seed[0:2])%16] + " " + ANIMAL[hex(seed[2:4])%16]`; `aliasColor(seed)` hue = `hex(seed[0:6])%360` (HSL).
- **App package** `eu.kreds.torspike`; the G20 is API 30. Expo v57: TS/module-surface changes follow `android_tor_spike/app/AGENTS.md`.
- **Env:** dot-source `android_tor_spike/tools/env.ps1`; gradle from `android_tor_spike\app\android`; timeouts 600000 ms. Python `.venv\Scripts\python.exe`. August drives on-device (G20 ZY32DLZQ2N); Claude runs desk gates + adb.
- **Pinned interfaces:** `KotlinDmcrypt.postAad/dmAad` (the pattern to mirror), `KotlinWire.canonical(map): ByteArray`, `KotlinWire.PyFloat`, `KotlinWire.verifyRaw(pubHex, sigHex, data): Boolean` (KotlinWire.kt:149), `KotlinWire.PROTOCOL`. `DecryptPass.run(...)` + `resolveWrap`/`unwrapKey`/`decryptBody` (B.2). `StoredMsg(msgId, kind, identityPub, payload)`; `store.allMessages()`; `store.profileNames()`. `KotlinDmcrypt.decryptBody` returns org.json-typed values.

## File Structure

```
android .../tormanager/
  KotlinDmcrypt.kt        Task 1: + responsesAad
  KotlinResponses.kt      Task 2: validate/verify/alias/resolve/aggregate (new)
  SyncStore.kt / SqliteSyncStore.kt   Task 2: deviceViews(identity)
  DecryptPass.kt          Task 3: responses pass -> per-post Responses
  TorManagerModule.kt     Task 3: getFeed items gain `responses`
  index.ts                Task 3: FeedItem.responses type
android tools/
  make_dmcrypt_vectors.py + fixtures/dmcrypt_vectors.json  Task 1: a real KIND_RESPONSES vector
android .../src/test/.../
  KotlinDmcryptTest.kt    Task 1: responsesAad + record round-trip
  KotlinResponsesTest.kt  Task 2: sig-verify + device-bind + alias + validation
android_tor_spike/app/App.tsx   Task 4: reaction summary + comment list under posts
android_tor_spike/BRICK_B2D4_REPORT.md   Task 5
```

---

### Task 1: `responsesAad` port + a real `KIND_RESPONSES` vector

**Files:**
- Modify: `android/src/main/java/expo/modules/tormanager/KotlinDmcrypt.kt`
- Modify: `android_tor_spike/tools/make_dmcrypt_vectors.py` → regenerate `android_tor_spike/fixtures/dmcrypt_vectors.json`
- Test: `android/src/test/java/expo/modules/tormanager/KotlinDmcryptTest.kt`

**Interfaces:**
- Produces: `KotlinDmcrypt.responsesAad(author: String, target: String, createdAt: Double): ByteArray`.

- [ ] **Step 1: Add a `KIND_RESPONSES` vector** — extend `make_dmcrypt_vectors.py`'s `build()` to produce a real aggregated record by driving hearth: create an author node, `compose_post`, a responder node that `compose_response`s a PUBLIC comment (so the entry carries `identity`/`device_pub`/`responder_sig`), ingest into the author, run the author's `process_responses`/rebuild to get the `KIND_RESPONSES` record; a second responder makes a PRIVATE reaction. Emit a `"kind":"responses"` case: `{author, target, created_at, enc_priv (a scope recipient's), body_nonce, body_ct, content_key, entries:[...expected...], public_responder_device, public_responder_identity, public_responder_sig}`. (Confirm the exact hearth API: `compose_response(target, rkind, body, public=...)`, `process_responses`/`_rebuild_responses_record` — grep node.py; use the real names.)
> **Implementer:** this is the fiddliest step — driving hearth to emit a genuine aggregated record. Keep THROWAWAY keys, ASCII-only. If `process_responses` needs a specific trigger, mirror how `tests/` exercise it (grep tests for `compose_response`/`process_responses`). Run: `.venv\Scripts\python.exe android_tor_spike\tools\make_dmcrypt_vectors.py`; confirm the `"responses"` case is in the fixture. NOTE (bit twice before): `make_dmcrypt_vectors.py` is non-deterministic (ephemeral keys) — regenerating re-churns all cases; that's the known cosmetic-diff nuisance, acceptable.

- [ ] **Step 2: Failing test** — in `KotlinDmcryptTest.kt`, add: load the `"responses"` case; `KotlinDmcrypt.responsesAad(author, target, created_at)` → unwrap the content key from the record's wraps with `enc_priv` (the existing `unwrapKey` path) → `decryptBody(content_key-nonce/ct, responsesAad)` → assert the body's `entries` array matches the expected entries (at least: the public entry's `body` text + the private entry's `body` token).
- [ ] **Step 3: Run — expect FAIL** (`responsesAad` unresolved).
- [ ] **Step 4: Implement `responsesAad`** in `KotlinDmcrypt.kt` beside `postAad`/`dmAad`:
```kotlin
    fun responsesAad(author: String, target: String, createdAt: Double): ByteArray =
        KotlinWire.canonical(mapOf(
            "type" to "responses-aad", "protocol" to KotlinWire.PROTOCOL,
            "from" to author, "target" to target,
            "created_at" to KotlinWire.PyFloat(createdAt)))
```
- [ ] **Step 5: Run — PASS.** Ensure the gradle vector-copy task still copies the regenerated fixture. Full module suite + assembleDebug green. Commit `feat(b2d4): KotlinDmcrypt.responsesAad + a real KIND_RESPONSES decrypt vector`

---

### Task 2: `KotlinResponses` — validate / verify / alias / resolve + `deviceViews`

The security core. All pure/JVM-testable.

**Files:**
- Create: `android/src/main/java/expo/modules/tormanager/KotlinResponses.kt`
- Modify: `SyncStore.kt` (interface + InMemory) + `SqliteSyncStore.kt` — `deviceViews(identity): Set<String>`
- Test: `android/src/test/java/expo/modules/tormanager/KotlinResponsesTest.kt`

**Interfaces:**
- Produces:
```kotlin
object KotlinResponses {
    data class Comment(val body: String, val display: String, val aliasColor: Int?, val createdAt: Double)
    data class Responses(val reactions: Map<String, Int>, val comments: List<Comment>)

    fun validEntry(e: Map<String, Any?>): Boolean
    fun aliasName(seed: String): String
    fun aliasColor(seed: String): Int            // HSL hue 0..359
    fun responseSigPayload(target: String, rkind: String, body: String, createdAt: Double, responder: String): ByteArray
    // resolve one entry to a display name (verified identity) or an alias.
    fun resolveDisplay(e: Map<String, Any?>, target: String,
                       profileNames: Map<String, String>,
                       deviceBound: (identity: String, devicePub: String) -> Boolean): Pair<String, Int?>
    // aggregate a validated+resolved entry list into a per-post view.
    fun aggregate(entries: List<Map<String, Any?>>, target: String,
                  profileNames: Map<String, String>,
                  deviceBound: (String, String) -> Boolean): Responses
}
```
- `SyncStore.deviceViews(identity: String): Set<String>` (InMemory: distinct device_pubs of stored messages for that identity; SQLite: `SELECT DISTINCT device_pub FROM messages WHERE identity_pub=?`).

- [ ] **Step 1: Failing tests** `KotlinResponsesTest.kt` — use Task 1's `"responses"` vector for the real public entry (valid sig + its device/identity):
  - `aliasName`/`aliasColor` match `app.js` for a couple of committed seeds (e.g. assert the exact adjective+animal + hue for a known 6-hex seed).
  - `validEntry`: a good comment + a good reaction pass; a bad rkind, an oversized (>500) comment, a reaction body not in REACTION_TOKENS, a non-hex64 responder_sig, a public entry missing identity/device_pub → all fail (dropped).
  - **Security core:** the vector's PUBLIC entry with its VALID sig + a `deviceBound` that returns true → `resolveDisplay` returns the profile name (or `friend-<8>`); the SAME entry with a corrupted `responder_sig` → returns the ALIAS; the valid entry but `deviceBound` returns FALSE → returns the ALIAS (sig-alone insufficient).
  - `aggregate`: a mix (2 reactions same token, 1 different, 1 comment) → `reactions` tally correct, `comments` list correct.
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement `KotlinResponses.kt`** — the wordlists + funcs per Global Constraints; `resolveDisplay`: if `public==true` AND `verifyRaw(device_pub, responder_sig, responseSigPayload(target, rkind, body, createdAt, identity))` AND `deviceBound(identity, device_pub)` → `profileNames[identity] ?: "friend-"+identity.take(8)` (aliasColor null); else → `aliasName(alias_seed)` + `aliasColor(alias_seed)`. `aggregate`: filter `validEntry`, tally reactions by `body` for rkind=="reaction", build `Comment` for rkind=="comment" (display+color via `resolveDisplay`). Junk-guard every field read (org.json types — mirror DecryptPass's stringList idiom). Implement `deviceViews` both store impls.
> **Implementer:** `deviceBound` in production is `{ id, dev -> val v = store.deviceViews(id); v.isEmpty() || dev in v }` (mirrors hearth `_device_bound`: empty views → permissive true). Confirm `verifyRaw`'s exact arg order/hex handling against `KotlinWire.kt:149` and that the sig payload's `created_at` uses `PyFloat` inside `canonical` (byte-exact with hearth's `_response_sig_payload`).
- [ ] **Step 4: Run — PASS.** Full module suite + assembleDebug green. Commit `feat(b2d4): KotlinResponses - entry validation + responder_sig+device-bind verification + alias + aggregation; store deviceViews`

---

### Task 3: Responses decrypt pass + feed `responses`

**Files:**
- Modify: `DecryptPass.kt` (a responses pass), `TorManagerModule.kt` (getFeed marshal), `index.ts`

**Interfaces:**
- Consumes: `KotlinResponses` (Task 2), `KotlinDmcrypt.responsesAad`, `resolveWrap`/`unwrapKey`/`decryptBody`, `store.allMessages()`/`profileNames()`/`deviceViews()`.
- Produces: a `Map<String, KotlinResponses.Responses>` keyed by target post msgId, computed in the decrypt pass; feed items gain `responses` (or omit/empty). `index.ts`: `FeedItem.responses?: {reactions: Record<string, number>, comments: {body, display, color, createdAt}[]}`.

- [ ] **Step 1** — add to the decrypt pass (DecryptPass.run or a sibling `responsesPass`): over `allMessages()`, select `kind=="responses"`, group by `payload["target"]`, keep the LATEST per `(cert.identity_pub, target)` by `(created_at, seq)` (the prune tie-break, mirroring `wrapGrantsFor`'s latest logic). For each: build `responsesAad(author=identityPub, target, created_at)`, `resolveWrap`→`unwrapKey`→`decryptBody` (fail-closed skip on null), read `body["entries"]` (a list), `KotlinResponses.aggregate(entries, target, profileNames, deviceBound)`. Return `target -> Responses`. Wire `deviceBound = { id, dev -> store.deviceViews(id).let { it.isEmpty() || dev in it } }`.
- [ ] **Step 2** — `TorManagerModule`: capture the `target->Responses` map from the same decrypt pass into a `@Volatile responsesByPost` (feedCache lifetime), and `getFeed` per-item adds `"responses" to responsesByPost[msgId]?.let { mapOf("reactions" to it.reactions, "comments" to it.comments.map{ c -> mapOf("body" to c.body,"display" to c.display,"color" to c.aliasColor,"createdAt" to c.createdAt)}) }` (or null/omit). `index.ts`: `FeedItem.responses`.
- [ ] **Step 3** — a JVM test: a feed post + its latest `KIND_RESPONSES` (from the vector) → the feed item's `responses` has the right reactions + comments; a post with an older + newer responses record uses the newer (latest-wins). Full suite + assembleDebug + tsc A/B green. Commit `feat(b2d4): responses decrypt pass (latest KIND_RESPONSES per post) + feed carries reactions/comments`

---

### Task 4: App.tsx — reaction summary + comment list

**Files:**
- Modify: `android_tor_spike/app/App.tsx`

- [ ] **Step 1** — Follow `AGENTS.md`. Under each post feed row, if `item.responses` present: a reaction summary line (each `token` + its count, e.g. `heart 3  fire 1`) and a comment list (each: `display` rendered in its `color` HSL hue when present — `hsl(<color>,60%,45%)` — else the default text color, followed by the `body` text). Posts with no responses render nothing extra. Minimal dev-dashboard aesthetic — no reacting/commenting, no avatars.
- [ ] **Step 2** — tsc A/B clean, vitest green; build BOTH APKs; install the RELEASE apk on the G20. Play-Protect/device-absent → report, defer to Task 5. Commit `feat(b2d4): render reaction counts + comments under posts`

---

### Task 5: On-device run + report

**Files:**
- Create: `android_tor_spike/BRICK_B2D4_REPORT.md`

- [ ] Report + run steps (mirror `BRICK_B2D3_REPORT.md`; carry the field lessons: desktop `serve --tor`, UNLOCK via the web UI, RELEASE apk). Verify: from the desktop, add REACTIONS + a COMMENT to one of your own posts (and, if a friend is reachable, have them react/comment on a shared post); sync on the phone; the post shows the reaction counts + the comment(s) under it — public commenters by name, private as alias. Confirm a fabricated/forged case isn't mis-attributed is a desk-gate concern (already proven); on-device just confirms real responses render. Own posts/photos/video/stories/feed unchanged (regression). NOTE the honest boundary: only responses aggregated AFTER the phone's enc key published decrypt (older posts may show no responses). **PAUSE — human-driven.** Fill the verdict. Commit `docs(b2d4): on-device responses run + report`.

---

## Self-Review (performed at write time)

**Spec coverage:** responsesAad + KIND_RESPONSES decrypt → Task 1; entry validate + responder_sig+device-bind verify + alias + aggregate → Task 2 (the security core); latest-per-post decrypt pass + feed responses → Task 3; render → Task 4; on-device → Task 5. View-only, no-composing, no-hearth-change, seal_slots-deferred, fail-closed, device-binding-mandatory all honored.

**Type consistency:** `responsesAad(author,target,createdAt)` (Tasks 1/3); `KotlinResponses.{validEntry, aliasName, aliasColor, responseSigPayload, resolveDisplay, aggregate}` + `Responses(reactions, comments)` + `Comment(body, display, aliasColor, createdAt)` (Tasks 2/3); `deviceViews(identity): Set<String>` (Tasks 2/3); `FeedItem.responses` (Tasks 3/4).

**Judgment calls flagged:** the Task-1 vector generator driving hearth's `compose_response`/`process_responses` is the fiddly bit (implementer confirms the exact API + how tests trigger the fold); `deviceBound` = empty-views-permissive per hearth `_device_bound` (do NOT weaken to sig-only); `verifyRaw` arg order + the sig payload's PyFloat `created_at` are byte-exactness points (Task 2 confirms). The render + on-device are device-proven (Tasks 4/5), same coverage boundary as prior slices.
