# Android B.2d-3 — Stories Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The phone shows a strip of unexpired stories; tapping an author cycles their photo/video stories (with captions) in a fullscreen viewer; received story-reply DMs show a chip — all rendered from PLAINTEXT story blobs (no decryption), with the `missingBlobs` gap closed so story media actually downloads.

**Architecture:** Pure phone-side, no hearth change. Stories are plaintext `KIND_STORY` (no content key), so the render path skips `decryptBlob` — but a story photo still routes through the isolated `ImageDecodeService` (friend-authored AVIF, same RCE surface). Store gains `activeStories` + a widened `missingBlobs`; the module gets plaintext `getStoryImage`/`getStoryVideoUrl` (the latter via a STORY route on the existing MediaServer resolver) + `getStories`; App.tsx gets the strip/viewer + a story-reply chip.

**Tech Stack:** Kotlin (`SqliteSyncStore`/`InMemorySyncStore`, `KotlinImageDecode`, `MediaServer` from B.2d-2, `TorManagerModule`), `expo-video` (already added B.2d-2), React Native (App.tsx).

**Spec:** `docs/superpowers/specs/2026-07-20-android-b2d3-stories-design.md`

## Global Constraints

- **Commit messages: NO AI/Co-Authored-By trailers.** Style `feat(b2d3):` / `fix(b2d3):` lowercase.
- **NO hearth change.** Phone-side only. `MediaServer.kt` (B.2d-2 security boundary), `KotlinImageDecode`, `KotlinBlobCrypt`, `DecryptPass`, the isolated `ImageDecodeService` are consumed, not modified (the MediaServer's injected resolver gains a story branch — that's in `TorManagerModule`, not `MediaServer.kt`).
- **Stories are PLAINTEXT** — the story render path NEVER calls `decryptBlob` and NEVER looks up `blobKeys`. `getStoryImage`/`getStoryVideoUrl` are DISTINCT from the post `getBlobImage`/`getVideoUrl` (which decrypt). A story blob must never hit the decrypt path; a post blob must never hit the raw story path.
- **Isolated AVIF decode STILL applies to story photos** — `getStoryImage` calls `KotlinImageDecode.toRenderable` (which routes AVIF to the isolated `ImageDecodeService`), just without the preceding `decryptBlob`. A friend-authored story AVIF is attacker-influenceable; it must NOT decode in-process.
- **`media` field-shape trap:** for a POST, `payload["media"]` is the `"photo"/"video"` DISCRIMINATOR; for a STORY it's the blob HASH. The `missingBlobs` widening must extract `media` as a hash ONLY for `kind=="story"` rows — never for post/dm (would add "photo"/"video" as bogus hashes).
- **24h TTL:** stories filter `expires_at > now` at read; an expired story may still sit in the store (desktop GCs) — the phone just excludes it.
- **App package** `eu.kreds.torspike`; the G20 is API 30. Expo v57: TS/module-surface changes follow `android_tor_spike/app/AGENTS.md`.
- **Env:** dot-source `android_tor_spike/tools/env.ps1`; gradle from `android_tor_spike\app\android`; generous timeouts (600000 ms). Python `.venv\Scripts\python.exe`. August drives on-device (G20 ZY32DLZQ2N); Claude runs desk gates + adb.
- **Pinned (2026-07-20):** `make_story` payload `{kind:"story", media_kind:"photo"|"video", media:<hex64>, poster:<hex64|null>, caption, created_at, expires_at}` (messages.py:159-169). `StoredMsg(msgId, kind, identityPub, payload: Map)` (SyncStore.kt:14). SqliteSyncStore.missingBlobs: `SELECT msg_json ... WHERE kind IN ('post','dm')`, extracts `blobs`(list)/`poster`(str)/`thumbs`(list) (SqliteSyncStore.kt:212+); InMemory mirrors (SyncStore.kt:166). `getBlob(hash): ByteArray?` serves raw. `KotlinImageDecode.toRenderable(bytes): Pair<mime,bytes>?`. `MediaServer(resolve: (msgId,hash)->ByteArray?)`; the module's resolver + `ensureMediaServer` + `getVideoUrl` are in TorManagerModule (B.2d-2). `profileNames(): Map<identityPub,String>` (B.2c). `Base64.encodeToString(bytes, Base64.NO_WRAP)`.

## File Structure

```
android .../tormanager/
  SyncStore.kt / SqliteSyncStore.kt   Task 1: StoredStory + activeStories(now); missingBlobs widened for story
  TorManagerModule.kt                 Task 2: getStoryImage/getStoryVideoUrl (plaintext) + STORY resolver branch + getStories
  index.ts                            Task 2: getStories/getStoryImage/getStoryVideoUrl + StoryItem
android .../src/test/.../
  SyncStoreTest.kt (or new StoryStoreTest.kt)  Task 1: activeStories + missingBlobs story
android_tor_spike/app/App.tsx         Task 3: story strip + viewer + story-reply-DM chip
android_tor_spike/BRICK_B2D3_REPORT.md   Task 4
```

---

### Task 1: Store — `activeStories` + `missingBlobs` story fix

**Files:**
- Modify: `SyncStore.kt` (interface + `InMemorySyncStore`), `SqliteSyncStore.kt`
- Test: `android/src/test/java/expo/modules/tormanager/SyncStoreTest.kt` (extend; or a new `StoryStoreTest.kt`)

**Interfaces:**
- Produces:
```kotlin
data class StoredStory(
    val msgId: String, val author: String, val mediaKind: String,
    val media: String, val poster: String?, val caption: String, val createdAt: Double)
fun activeStories(nowSeconds: Double): List<StoredStory>   // unexpired KIND_STORY, newest-first
```
  on `SyncStore` + both impls; and `missingBlobs()` widened (both impls).

- [ ] **Step 1: Failing tests** (`SyncStoreTest.kt`, InMemory): 
  - `activeStories`: ingest two `story` messages (one `expires_at = now+1000`, one `expires_at = now-10`) + a `post` → `activeStories(now)` returns ONLY the unexpired story, with its `mediaKind`/`media`/`poster`/`caption`/`author`; the post is absent.
  - `missingBlobs` story: ingest a `story` (media=`"aa"*32`, poster=`"bb"*32`) with neither blob held → `missingBlobs()` includes both `aa*32` and `bb*32`; and (regression) a `post` with a `blobs` entry still appears. A `post` with `media="video"` must NOT put `"video"` in missingBlobs (the field-shape trap).
  Build the story messages with a helper mirroring how the existing tests build signed messages (a `KIND_STORY` payload; it needs no wraps/body).
- [ ] **Step 2: Run — expect FAIL** (`activeStories` unresolved; missingBlobs test fails to include story media).
- [ ] **Step 3: Implement `activeStories`** — both impls scan stored messages for `kind=="story"` with `payload["expires_at"] as Number > nowSeconds`, map to `StoredStory` (`author`=the stored identity_pub, `mediaKind`=`payload["media_kind"]`, `media`=`payload["media"]`, `poster`=`payload["poster"] as? String`, `caption`=`payload["caption"] as? String ?: ""`, `createdAt`=`payload["created_at"]`), sorted newest-first by `createdAt`. (InMemory: iterate the messages map. SQLite: `SELECT msg_json FROM messages WHERE kind='story'` then filter/parse, like `wrapGrantsFor`/`allMessages` do.)
- [ ] **Step 4: Implement the `missingBlobs` widening** — SqliteSyncStore: change the query to `SELECT kind, msg_json FROM messages WHERE kind IN ('post','dm','story')`; keep the existing `blobs`/`thumbs`/`poster` extraction (the `poster` branch now also catches story posters); ADD, guarded on `kind == "story"`, `(payload.opt("media") as? String)?.let { if (it.isNotEmpty()) refs.add(it) }`. Do NOT extract `media` for post/dm (it's the discriminator). InMemory: same shape over the SignedMessage payloads.
> **Implementer:** the `media`-only-for-story guard is load-bearing — a post's `media` is `"photo"/"video"`, not a hash. The `poster` extraction is already generic (any kind), so adding `'story'` to the IN-clause makes story posters flow through it automatically; only `media` needs the story guard.
- [ ] **Step 5: Run — PASS.** Full module JVM suite + `assembleDebug` green. Commit `feat(b2d3): store activeStories + missingBlobs fetches story media/poster`

---

### Task 2: Module — plaintext story render paths + `getStories`

**Files:**
- Modify: `TorManagerModule.kt`, `index.ts`

**Interfaces:**
- Consumes: `activeStories`/`getBlob`/`profileNames` (Task 1 + existing), `KotlinImageDecode.toRenderable`, `MediaServer`/`ensureMediaServer` (B.2d-2), `Base64`, `ioScope`.
- Produces: `getStoryImage(hash): Promise<string|null>`, `getStoryVideoUrl(hash): Promise<string|null>`, `getStories(): Promise<StoryItem[]>`; `StoryItem = {msgId, author, authorName, mediaKind, media, poster, caption, createdAt}`.

- [ ] **Step 1: STORY resolver branch** — the B.2d-2 `MediaServer` resolver (in `ensureMediaServer`) currently does `blobKeys[msgId]?.let { key -> getBlob(hash)?.let { decryptBlob(key,it) } }`. Add an explicit story branch: `if (msgId == STORY_MARKER) SqliteSyncStore(ctx).getBlob(hash) else <the existing decrypt path>`, where `private const val STORY_MARKER = "story"` (a reserved msgId a real message never has — msgIds are hex64). A post hash under the marker would serve unplayable ciphertext (no plaintext leak); a story hash is plaintext-by-design.
- [ ] **Step 2: `getStoryImage`** — `AsyncFunction("getStoryImage") { hash: String -> ... }.runOnQueue(ioScope)`: `val bytes = SqliteSyncStore(ctx).getBlob(hash) ?: return null`; `val (mime, out) = KotlinImageDecode.toRenderable(bytes) ?: return null`; `"data:$mime;base64," + Base64.encodeToString(out, Base64.NO_WRAP)`. NO `blobKeys`, NO `decryptBlob` — plaintext. (Reuses the isolated AVIF decode inside `toRenderable`.)
- [ ] **Step 3: `getStoryVideoUrl`** — `AsyncFunction("getStoryVideoUrl") { hash: String -> ensureMediaServer(ctx)?.urlFor(STORY_MARKER, hash) }.runOnQueue(ioScope)` (returns null if the server can't start / module destroyed, mirroring `getVideoUrl`).
- [ ] **Step 4: `getStories`** — `AsyncFunction("getStories") { -> val names = store.profileNames(); store.activeStories(now).map { mapOf("msgId" to it.msgId, "author" to it.author, "authorName" to (names[it.author] ?: "friend-"+it.author.take(8)), "mediaKind" to it.mediaKind, "media" to it.media, "poster" to it.poster, "caption" to it.caption, "createdAt" to it.createdAt) } }.runOnQueue(ioScope)`. `now` = `System.currentTimeMillis()/1000.0`.
- [ ] **Step 5: `index.ts`** — export the three functions + `StoryItem` type. Follow `android_tor_spike/app/AGENTS.md` (Expo v57). Build + tsc A/B (no new errors). Commit `feat(b2d3): module getStories + plaintext getStoryImage/getStoryVideoUrl (story MediaServer route)`

---

### Task 3: App.tsx — story strip + viewer + story-reply chip

**Files:**
- Modify: `android_tor_spike/app/App.tsx`

- [ ] **Step 1** — Follow `AGENTS.md`. Add a story strip above the feed: on mount + `onSync`, call `getStories()`; render a horizontal row of author chips (one per author with >=1 unexpired story — group the StoryItem[] by `author`, show `authorName` + count). Empty → the strip hides (no empty box).
- [ ] **Step 2** — Tap an author chip → open the existing fullscreen `Modal` in "story mode": hold that author's stories (sorted), an index starting at 0; render the current story — `mediaKind === "photo"` → `<Image>` from `getStoryImage(media)`; `mediaKind === "video"` → the `expo-video` player from `getStoryVideoUrl(media)` (release on close, as B.2d-2); show `caption` below. Tap advances the index; past the last story (or back) closes. A `getStoryImage`/`getStoryVideoUrl` returning null → a "media unavailable" placeholder (fail-closed, e.g. not-yet-downloaded).
- [ ] **Step 3** — Story-reply chip: a feed DM whose `payload.story_ref` is present (a `{story_id, media_hash}` object) renders a small "↩ replied to your story" chip + a thumbnail via `getStoryImage(story_ref.media_hash)` on that DM row. (The DM already renders from B.2c; the chip is additive, reads the plaintext `story_ref`.) If `getStoryImage` returns null (media not held), show the chip text without the thumbnail.
- [ ] **Step 4** — tsc A/B clean, vitest green; build BOTH APKs; install the RELEASE apk on the G20 (`adb -s ZY32DLZQ2N install -r ...\release\app-release.apk`). Play-Protect/device-absent → report, defer install to Task 4. Commit `feat(b2d3): story strip + fullscreen viewer + story-reply-DM chip`

---

### Task 4: On-device run + report

**Files:**
- Create: `android_tor_spike/BRICK_B2D3_REPORT.md`

- [ ] Report + run steps (mirror `BRICK_B2D2_REPORT.md`, carrying the field lessons: desktop via `python -m hearth serve --dir %APPDATA%\Kreds --http-port P --gossip-port P --tor`, UNLOCK via the web UI first, install the RELEASE apk; and the B.2d-2 prebuild-drops-the-NSC hazard still applies to video stories). Verify: post a FRESH story from the desktop (24h TTL — post right before the run), sync on the phone, the story appears in the strip under its author; tapping shows the photo (isolated-decoded) or plays the video + caption; an expired/old story is absent; story media actually downloaded (the missingBlobs fix — check the story renders, not a placeholder); a received story-reply DM shows the chip. Own posts/photos/video/feed unchanged (regression). **PAUSE — human-driven.** Fill the verdict. Commit `docs(b2d3): on-device stories run + report`.

---

## Self-Review (performed at write time)

**Spec coverage:** plaintext render (no decrypt, isolated AVIF still) → Task 2 (`getStoryImage` via `toRenderable`, no `decryptBlob`); `missingBlobs` story fix + `activeStories` (TTL) → Task 1; story video raw route → Task 2 (STORY_MARKER resolver branch, MediaServer unmodified); strip + viewer → Task 3; story-reply-DM chip → Task 3; on-device → Task 4. No-composing, no-hearth-change, isolated-decode-preserved, plaintext-vs-decrypt-split all honored.

**Type consistency:** `StoredStory`/`activeStories(now)` (Task 1) → `getStories`/`StoryItem` (Task 2) → App.tsx (Task 3); `getStoryImage(hash)`/`getStoryVideoUrl(hash)` + `STORY_MARKER="story"` (Task 2/3); `missingBlobs` widened both impls (Task 1).

**Judgment calls flagged:** the `media`-only-for-story guard in `missingBlobs` (the field-shape trap) is called out explicitly in Task 1 Step 4; the STORY_MARKER resolver routing keeps `MediaServer.kt` untouched (routing lives in the module resolver); the plaintext render paths are separate functions from the decrypting post paths (no crossover). The strip/viewer + chip are device-proven (Task 4), same coverage boundary as prior UI slices.
