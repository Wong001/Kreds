# Android Visual Parity — WebView Shell (Slice 3: Me/profile + 4-column wall) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The phone's Me/profile view renders read-only — the profile header/banner/accent, the 4-column WALL (post-blocks with pin/span/text_style plus folded album pseudo-blocks), and the profile journal rail — by extending the slice-1/2 `LocalApi` with a read-only profile route marshaled from the phone's native store into hearth's EXACT `profile_view` snake_case JSON, plus closing the friend-profile write-affordance seam gap.

**Architecture:** Slices 1-2 already serve `hearth/web` from a loopback server behind a WebView, backed by read-only `/api` routes over the native SQLite store (decrypt-on-read). The `#view-profile` DOM, `renderProfilePage`/`renderWall`/`renderBlock` JS, and the `repeat(4,1fr)` wall grid CSS already exist byte-identical in the bundle — slice 3 is 100% a marshaling task in `LocalApi.kt` (+ three new plaintext `SyncStore` accessors), plus a two-file shared-UI edit to hide a friend profile's Move/Unfriend write buttons. Wall posts flow through the existing `DecryptPass.run`; the profile record, layout, and album groupings are PLAINTEXT reads (no `DecryptPass`).

**Tech Stack:** Kotlin (Expo native module `tor-manager`, JUnit4 + org.json JVM tests), the shared `hearth/web` bundle (HTML/CSS re-synced into the APK by the existing Gradle `copyHearthWeb` task), vitest (read-only-seam static guard). No new RN/native wiring — the slice-1 `LocalWebServer`, `WebShell`, and asset-copy pipeline are reused unchanged.

## Global Constraints

- Commit messages `feat(vp3): …` / `fix(vp3): …`, lowercase. **NO AI / Co-Authored-By / "Generated with" trailers on ANY commit.**
- Decrypt-on-read: NO decrypted bytes or keys are ever written to disk. The server decrypts on demand, in-memory, and streams; caches live only in memory.
- Exact hearth JSON shapes: every route's response must byte/shape-match `hearth/api.py`/`node.py` (SNAKE_CASE: `identity_pub`, `avatar_shape`, `banner_pos`, `scope_newest`, `text_style`, …) so `hearth/web/app.js` runs unchanged. Golden-shape tests are the guard.
- **THE LOAD-BEARING LESSON (from slices 1-2):** `app.js`'s `j()` throws on any non-2xx, and `refresh()` awaits a FIXED set of routes EVERY tick (`/api/state`, `/api/feed`, `/api/kreds`, `/api/stories`, `/api/conversations`) — all ALREADY implemented in slices 1-2. The NEW profile route is DIFFERENT: `openProfile` wraps it in try/catch (`app.js:2078-2082`), degrading to `fallbackProfile` on a 404. So the profile route adds NO new blanking risk — but a 404 or bad data renders an empty wall via the fallback, so it must return GOOD data, and its 404 condition must reproduce hearth exactly (see Pinned decision 3). Do NOT build a fallback JSON body in Kotlin — return null → the server 404s → app.js's fallback takes over.
- Shared `hearth/web` changes are re-synced into the APK byte-for-byte by the existing Gradle `copyHearthWeb` task; a `hearth/web` edit + a rebuild covers both the desktop and the phone copy. Only touch `hearth/web` for the Task-4 seam gap.
- **KDoc trap:** never write a literal endpoint glob (a slash + `api` + slash + star) inside a Kotlin `/** */` block comment — it triggers a nested-comment compile break (bit prior slices). In doc comments name routes in prose ("the profile route") or use `//` line comments for anything path-shaped.

**Test command reference** (Windows: use `.\gradlew.bat`; git-bash: `./gradlew`). For the Kotlin JVM tests set `JAVA_HOME=/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot` and `ANDROID_HOME=~/AppData/Local/Android/Sdk`:
- Kotlin JVM tests: from `android_tor_spike/app/android` → `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.<Class>"`
- vitest: from `android_tor_spike/app` → `npx vitest run test/web-readonly-seam.test.ts`
- tsc: from `android_tor_spike/app` → `npx tsc --noEmit` (0-new; pre-existing `tools/` node-type errors exist)
- Release APK: from `android_tor_spike/app/android` → `./gradlew :app:assembleRelease` (NDK r27.1 pinned; apk at `app/build/outputs/apk/release/app-release.apk`)

## Pinned design decisions (do NOT re-decide)

1. **The EXACT profile route shape (reference §1).** Top-level `{name, bio, accent, avatar, avatar_shape, avatar_size, avatar_align, banner, banner_pos}` (from the profile record) PLUS `{identity_pub, mine, ring, since, wall, journal}`. NOT nested under a `"profile"` key. `mine = (identity == own)` (on the top level AND on every wall/album block). `wall` = a list (newest-first) of blocks; `journal` = a list of feedRow-shape rows.
   - **Post wall-block** = the SAME `feedRow(...)` shape (`msg_id, identity_pub, author_name, author_avatar, text, blobs, scope, created_at, expires_at, mine, placement, media, poster, codec, thumbs, responses`) PLUS three added fields: `pin` (`{x,y,w,h}` or null), `span` (`{w,h}`, always present), and `text_style` (`{h,v,size,font,weight,style,color}`, ONLY on pure-text blocks: `blobs empty && media != "video"`). Wall rows emit `responses: null` (renderBlock never reads responses).
   - **Album pseudo-block** (reduced, different shape): `{album:true, msg_id:<album_id>, mine:bool, photos:[{m,h,t}…], count:int, created_at:float, scope_newest:"inner"|"kreds", pin:{…}|null, span:{w,h}}`. NOT the post fields.
2. **Three new `SyncStore` accessors** (interface + `SqliteSyncStore` + `InMemorySyncStore` + JVM tests), each latest-wins by `(created_at, seq)` reading the `seq` column directly (mirror `profileNames()`, NOT `allMessages()` which lacks `seq`), PLAINTEXT JSON reads (KIND_PROFILE/PROFILE_LAYOUT/ALBUM carry no body_ct/wraps — no DecryptPass):
   - `profileRecord(identityPub): Map<String,Any?>?` — the full latest KIND_PROFILE payload, or null if none.
   - `profileLayout(identityPub): ProfileLayout` = `{pins, spans, sizes, texts}` (skip order/grids — legacy/unused by rendering), latest-wins over kind='profile_layout', empty maps when none.
   - `albums(identityPub): Map<String, List<String>>` (album_id → member msgIds), latest-wins PER album_id (group then newest-per-key, like `wrapGrantsFor`'s fold).
3. **404 condition (reproduce hearth exactly, node.py:1262-1265):** `identity != own && !isKnown(identity)` → return null (→ 404 → app.js `fallbackProfile`). `isKnown(identity)` = `identity == own || identity in store.knownIdentities()`. When `identity == own` AND no `profileRecord` exists → use hearth's HARDCODED default: `{name: profileNames[own] ?: own[:8], bio:"", accent:"#2743d6", avatar:null, avatar_shape:"circle", avatar_size:"m", avatar_align:"left", banner:null, banner_pos:50}`. When `identity` is known-but-not-own AND has no `profileRecord` → return null (404), matching hearth.
4. **ring/since:** own → `ring="kreds", since=null`; others → `ring="kreds", since=0` (reuse the existing `kreds()`-route default — the phone doesn't process KIND_RING; flag the gap as a follow-up, same as `kreds()`).
5. **Wall assembly is a PURE, JVM-testable set of companion builders** reproducing hearth `profile_view` (reference §1): port `_default_span` and `_fold_album_members` and the album-fold loop verbatim; annotate each post-block with pin/span/text_style; then fold albums; sort the folded list created_at DESC.
6. **journal rail** = this identity's `placement=="journal"` posts, the `feedRow` shape WITH real responses (reuse `feedRow` + `responsesPass`, filtered to this identity's journal posts). No pin/span/text_style on rail rows.
7. **Wall posts come from `DecryptPass.run` (already warmed for /api/feed):** filter `d.identityPub == identity && d.placement == "profile"` (unexpired) for the wall; `d.identityPub == identity && (d.placement ?: "journal") == "journal"` (unexpired) for the rail. Warm `keysCache = postKeys(res.feed, res.keys)` (same as `feed()`). Blob serving reuses the existing `/api/blob` (avatars/banners plaintext) + `/api/post-blob` (wall photos/videos) — NO new blob route.
8. **Route:** `path.startsWith("/api/profile/")` → identity = segment after (same prefix-extraction idiom as the `/api/dm/` branch). No collision: hearth's POST profile write route isn't served, and `/api/profile-layout` etc. aren't GET-routed here (`"-"` ≠ `"/"` right after `profile`).
9. **Task 4 — close the friend-profile seam gap (reference §3):** the friend-profile `move` (ring-move) + `unfriend` buttons `await j(...)` UNGUARDED → tapping them throws an unhandled rejection (the writes aren't implemented → 404 → j() throws). The `move` button is `el("button", "", …)` with an EMPTY class (app.js:2274) — add `class="ring-move"` at that call site; then extend the `body.readonly` seam in style.css with `#profile-actions .ring-move` + `#profile-actions .btn-danger` (SCOPE `.btn-danger` to `#profile-actions` — do NOT blanket-hide it; `.btn-danger` is also the App-lock disable button, app.js:3795). Leave the `Message` (btn-accent) button reachable (it only navigates to the already-read-only Messages view). Extend the vitest read-only-seam guard.
10. **wall-autoplace + block-\* + arrange + ring/unfriend writes:** NOT implemented (POST, fire-and-forget or unreachable → safe 404). Do NOT stub them.

---

## File Structure

**Native (Kotlin), all under `android_tor_spike/app/modules/tor-manager/android/src/`:**
- `main/java/expo/modules/tormanager/SyncStore.kt` — MODIFY. New `ProfileLayout` data class + two `internal` layout-parse helpers; three new interface methods; three new `InMemorySyncStore` overrides.
- `main/java/expo/modules/tormanager/SqliteSyncStore.kt` — MODIFY. The three new accessors as real SQL (seq-column tie-break), mirroring `profileNames()`.
- `main/java/expo/modules/tormanager/LocalApi.kt` — MODIFY. Pure wall-assembly + profile builders in the companion; the profile instance route wired into `handle()`.
- `test/java/expo/modules/tormanager/SyncStoreTest.kt` — MODIFY. Task-1 accessor tests (latest-wins, per-album fold, plaintext reads, unknown-identity null/empty).
- `test/java/expo/modules/tormanager/LocalApiTest.kt` — MODIFY. Task-2 wall-assembly golden-shape tests; Task-3 `profileJson`/`defaultProfileRecord` golden-shape tests.

**Web (shared UI), under `hearth/web/`:**
- `app.js` — MODIFY (one call site, `app.js:2274`). `class="ring-move"` on the move button.
- `style.css` — MODIFY (two selectors). `#profile-actions .ring-move` + `#profile-actions .btn-danger` in the `body.readonly` seam.

**Test (vitest), under `android_tor_spike/app/`:**
- `test/web-readonly-seam.test.ts` — MODIFY. `#profile-actions .ring-move` + `#profile-actions .btn-danger` added to the hidden-selector contract.

**Docs, under `android_tor_spike/`:**
- `BRICK_VP3_REPORT.md` — NEW. On-device DoD + follow-ups.

---

## Task 1: three plaintext `SyncStore` accessors (profileRecord / profileLayout / albums)

**Files:**
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/SyncStore.kt`
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/SqliteSyncStore.kt`
- Test: `android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/SyncStoreTest.kt`

**Interfaces:**
- Consumes: `StoredMsg`, `SignedMessage` (`m.kind`, `m.cert.identity_pub`, `m.seq`, `m.payload`); the SQLite `messages` table (`identity_pub, seq, kind, msg_json` columns); `SqliteSyncStore.jsonToMap`.
- Produces:
  - `data class ProfileLayout(pins: Map<String, Map<String, Any?>>, spans: Map<String, Map<String, Any?>>, sizes: Map<String, String>, texts: Map<String, Map<String, Any?>>)` (top-level in SyncStore.kt).
  - `internal fun layoutSubMaps(v: Any?): Map<String, Map<String, Any?>>` and `internal fun layoutSizes(v: Any?): Map<String, String>` (top-level in SyncStore.kt).
  - `SyncStore.profileRecord(identityPub: String): Map<String, Any?>?`
  - `SyncStore.profileLayout(identityPub: String): ProfileLayout`
  - `SyncStore.albums(identityPub: String): Map<String, List<String>>`

- [ ] **Step 1: Confirm the KIND_PROFILE / PROFILE_LAYOUT / ALBUM payload field names, then write the failing tests**

Read `hearth/messages.py` `make_profile` (messages.py:104-115), `make_profile_layout` (messages.py:171-182), and `make_album` (messages.py:185-190) and CONFIRM the signed payload field names. The confirmed values (use verbatim if the read matches):
- profile: `kind, name, bio, accent, avatar, avatar_shape, avatar_size, avatar_align, banner, banner_pos, created_at`.
- profile_layout: `kind, order, grids, sizes, pins, spans, texts, created_at` (slice 3 reads only `pins/spans/sizes/texts`).
- album: `kind, album_id, members, created_at`.

Add to `SyncStoreTest.kt` (append inside `class SyncStoreTest`; reuse the existing `msg(seq, payload)` helper — every message signs as identity `idPub`, so all rows share one identity, which is enough for latest-wins + per-album fold + the unknown-identity negative):

```kotlin
    // -- vp3 slice 3 Task 1: profileRecord / profileLayout / albums (plaintext) --

    @Test fun profileRecordLatestWinsAndReadsPlaintextFields() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        s.ingestMessage(msg(1, mapOf(
            "kind" to "profile", "name" to "Old", "bio" to "b0", "accent" to "#111111",
            "avatar" to null, "avatar_shape" to "circle", "avatar_size" to "m",
            "avatar_align" to "left", "banner" to null, "banner_pos" to 50, "created_at" to 100.0)))
        s.ingestMessage(msg(2, mapOf(
            "kind" to "profile", "name" to "New", "bio" to "b1", "accent" to "#2743d6",
            "avatar" to "aa".repeat(32), "avatar_shape" to "squircle", "avatar_size" to "l",
            "avatar_align" to "center", "banner" to "bb".repeat(32), "banner_pos" to 30,
            "created_at" to 200.0)))
        val rec = s.profileRecord(idPub)!!
        assertEquals("New", rec["name"])                 // newer created_at wins
        assertEquals("b1", rec["bio"])
        assertEquals("squircle", rec["avatar_shape"])
        assertEquals("bb".repeat(32), rec["banner"])
        assertEquals(30, (rec["banner_pos"] as Number).toInt())
        assertNull("unknown identity -> null (drives hearth's 404)", s.profileRecord("ff".repeat(32)))
    }

    @Test fun profileRecordSameCreatedAtHigherSeqWins() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        s.ingestMessage(msg(1, mapOf("kind" to "profile", "name" to "A", "created_at" to 100.0)))
        s.ingestMessage(msg(2, mapOf("kind" to "profile", "name" to "B", "created_at" to 100.0)))
        assertEquals("B", s.profileRecord(idPub)!!["name"])   // seq tie-break
    }

    @Test fun profileLayoutLatestWinsWithPinsSpansSizesTexts() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        s.ingestMessage(msg(1, mapOf(
            "kind" to "profile_layout",
            "pins" to mapOf("m1" to mapOf("x" to 0, "y" to 0, "w" to 2, "h" to 2)),
            "spans" to mapOf("m2" to mapOf("w" to 1, "h" to 1)),
            "sizes" to mapOf("m3" to "wide"),
            "texts" to mapOf("m4" to mapOf("h" to "center", "size" to "l")),
            "order" to emptyList<String>(), "grids" to emptyMap<String, Any?>(),
            "created_at" to 100.0)))
        val layout = s.profileLayout(idPub)
        assertEquals(2, (layout.pins["m1"]?.get("w") as Number).toInt())
        assertEquals(1, (layout.spans["m2"]?.get("h") as Number).toInt())
        assertEquals("wide", layout.sizes["m3"])
        assertEquals("center", layout.texts["m4"]?.get("h"))
        val empty = s.profileLayout("ff".repeat(32))     // never null; empty maps
        assertTrue(empty.pins.isEmpty() && empty.spans.isEmpty() &&
            empty.sizes.isEmpty() && empty.texts.isEmpty())
    }

    @Test fun albumsLatestWinsPerAlbumId() {
        val s = InMemorySyncStore()
        s.addIdentity(idPub)
        s.ingestMessage(msg(1, mapOf("kind" to "album", "album_id" to "A",
            "members" to listOf("m1", "m2"), "created_at" to 100.0)))
        s.ingestMessage(msg(2, mapOf("kind" to "album", "album_id" to "A",
            "members" to listOf("m1", "m2", "m3"), "created_at" to 200.0)))   // newer A
        s.ingestMessage(msg(3, mapOf("kind" to "album", "album_id" to "B",
            "members" to listOf("m9"), "created_at" to 150.0)))
        val albums = s.albums(idPub)
        assertEquals(listOf("m1", "m2", "m3"), albums["A"])   // newest A wins, per-album
        assertEquals(listOf("m9"), albums["B"])               // B unaffected by A's re-publish
        assertTrue(s.albums("ff".repeat(32)).isEmpty())
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run (from `android_tor_spike/app/android`): `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.SyncStoreTest"`
Expected: FAIL / compile error — `profileRecord`, `profileLayout`, `albums`, and `ProfileLayout` are unresolved.

- [ ] **Step 3: Add `ProfileLayout` + the layout-parse helpers + the interface methods in `SyncStore.kt`**

In `SyncStore.kt`, just below the `StoredStory` data class (before `interface SyncStore`), add:

```kotlin
/** Latest-wins profile-layout view (vp3 slice 3): the subset of a
 *  KIND_PROFILE_LAYOUT payload the wall renderer actually consumes.
 *  order/grids are intentionally dropped (legacy, unused by rendering).
 *  Empty maps (not null) when the identity has never published a layout,
 *  so wall assembly can always index without a null guard. pins/spans/texts
 *  are msgId -> a sub-map (pins: {x,y,w,h}; spans: {w,h}; texts: a subset of
 *  {h,v,size,font,weight,style,color}); sizes is msgId -> "small"|"wide"|"full". */
data class ProfileLayout(
    val pins: Map<String, Map<String, Any?>>,
    val spans: Map<String, Map<String, Any?>>,
    val sizes: Map<String, String>,
    val texts: Map<String, Map<String, Any?>>)

/** Parse a layout payload's pins/spans/texts field (a JSON object of
 *  msgId -> sub-object) into a plain Kotlin map, dropping any entry whose
 *  key isn't a String or whose value isn't itself a map. Shared by both
 *  store impls (module-`internal` so SqliteSyncStore, a separate file, can
 *  use it); both feed it already-plain Kotlin maps (InMemory: native
 *  payloads; SQLite: jsonToMap output), so a plain `as?` cast is enough. */
@Suppress("UNCHECKED_CAST")
internal fun layoutSubMaps(v: Any?): Map<String, Map<String, Any?>> {
    val m = v as? Map<*, *> ?: return emptyMap()
    val out = linkedMapOf<String, Map<String, Any?>>()
    for ((k, sub) in m) {
        val key = k as? String ?: continue
        val subMap = sub as? Map<String, Any?> ?: continue
        out[key] = subMap
    }
    return out
}

/** Parse a layout payload's `sizes` field (msgId -> "small"|"wide"|"full")
 *  into a plain String map, dropping non-String keys/values. */
internal fun layoutSizes(v: Any?): Map<String, String> {
    val m = v as? Map<*, *> ?: return emptyMap()
    val out = linkedMapOf<String, String>()
    for ((k, sv) in m) {
        val key = k as? String ?: continue
        val value = sv as? String ?: continue
        out[key] = value
    }
    return out
}
```

Then, inside the `interface SyncStore { … }` block, add these three methods just after `fun activeStories(nowSeconds: Double): List<StoredStory>`:

```kotlin
    /** The latest (by created_at, then seq) KIND_PROFILE payload for
     *  `identityPub`, or null if none is stored. PLAINTEXT: hearth's
     *  make_profile signs a plain dict with no wraps/body_ct (messages.py:
     *  104-115), so this is a JSON read, no decrypt -- same provenance basis
     *  as profileNames (a message can only ever claim its own author's
     *  profile). The whole payload is returned (incl. kind/created_at); the
     *  caller selects the display fields it wants. A null return is what
     *  drives the profile route's 404 for an unknown/record-less identity. */
    fun profileRecord(identityPub: String): Map<String, Any?>?
    /** The latest (by created_at, then seq) KIND_PROFILE_LAYOUT for
     *  `identityPub`, reduced to the pins/spans/sizes/texts the wall renderer
     *  uses (order/grids dropped). Empty maps (never null) when never
     *  published. PLAINTEXT (make_profile_layout signs a plain dict). */
    fun profileLayout(identityPub: String): ProfileLayout
    /** album_id -> member msgIds for `identityPub`, latest-wins PER album_id
     *  (by created_at, then seq -- the same per-key newest fold wrapGrantsFor
     *  uses, keyed by album_id here). Empty map when none. PLAINTEXT
     *  (make_album signs a plain dict). */
    fun albums(identityPub: String): Map<String, List<String>>
```

- [ ] **Step 4: Add the three `InMemorySyncStore` overrides**

In `SyncStore.kt`, inside `class InMemorySyncStore`, add these three overrides just after the `activeStories` override:

```kotlin
    override fun profileRecord(identityPub: String): Map<String, Any?>? {
        data class Cand(val createdAt: Double, val seq: Int, val payload: Map<String, Any?>)
        var best: Cand? = null
        for (m in messages.values) {
            if (m.kind != "profile" || m.cert.identity_pub != identityPub) continue
            val createdAt = (m.payload["created_at"] as? Number)?.toDouble() ?: 0.0
            val cur = best
            if (cur == null || createdAt > cur.createdAt || (createdAt == cur.createdAt && m.seq > cur.seq))
                best = Cand(createdAt, m.seq, m.payload)
        }
        return best?.payload
    }

    override fun profileLayout(identityPub: String): ProfileLayout {
        data class Cand(val createdAt: Double, val seq: Int, val payload: Map<String, Any?>)
        var best: Cand? = null
        for (m in messages.values) {
            if (m.kind != "profile_layout" || m.cert.identity_pub != identityPub) continue
            val createdAt = (m.payload["created_at"] as? Number)?.toDouble() ?: 0.0
            val cur = best
            if (cur == null || createdAt > cur.createdAt || (createdAt == cur.createdAt && m.seq > cur.seq))
                best = Cand(createdAt, m.seq, m.payload)
        }
        val p = best?.payload ?: return ProfileLayout(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        return ProfileLayout(
            pins = layoutSubMaps(p["pins"]), spans = layoutSubMaps(p["spans"]),
            sizes = layoutSizes(p["sizes"]), texts = layoutSubMaps(p["texts"]))
    }

    override fun albums(identityPub: String): Map<String, List<String>> {
        data class Cand(val createdAt: Double, val seq: Int, val members: List<String>)
        val best = linkedMapOf<String, Cand>()
        for (m in messages.values) {
            if (m.kind != "album" || m.cert.identity_pub != identityPub) continue
            val albumId = m.payload["album_id"] as? String ?: continue
            val members = (m.payload["members"] as? List<*>)?.mapNotNull { it as? String } ?: continue
            val createdAt = (m.payload["created_at"] as? Number)?.toDouble() ?: 0.0
            val cur = best[albumId]
            if (cur == null || createdAt > cur.createdAt || (createdAt == cur.createdAt && m.seq > cur.seq))
                best[albumId] = Cand(createdAt, m.seq, members)
        }
        return best.mapValues { it.value.members }
    }
```

- [ ] **Step 5: Add the three `SqliteSyncStore` overrides (real SQL, seq-column tie-break)**

In `SqliteSyncStore.kt`, add these three overrides just after the `activeStories` override (end of the class). They mirror `profileNames()`'s SQL scan (read the `seq` column directly, decode `msg_json`'s `payload`, newest-wins) exactly:

```kotlin
    /** See the SyncStore interface doc: latest KIND_PROFILE payload by
     *  (created_at, seq). Reads msg_json + the real `seq` column the same way
     *  profileNames() above does; identity_pub is filtered in SQL. */
    override fun profileRecord(identityPub: String): Map<String, Any?>? {
        data class Cand(val createdAt: Double, val seq: Int, val payload: Map<String, Any?>)
        var best: Cand? = null
        readableDatabase.rawQuery(
            "SELECT seq, msg_json FROM messages WHERE kind = ? AND identity_pub = ?",
            arrayOf("profile", identityPub)
        ).use { c ->
            while (c.moveToNext()) {
                val seq = c.getInt(0)
                val payload = JSONObject(c.getString(1)).optJSONObject("payload") ?: continue
                val createdAt = payload.optDouble("created_at", 0.0)
                val cur = best
                if (cur == null || createdAt > cur.createdAt || (createdAt == cur.createdAt && seq > cur.seq))
                    best = Cand(createdAt, seq, jsonToMap(payload))
            }
        }
        return best?.payload
    }

    /** Latest KIND_PROFILE_LAYOUT by (created_at, seq), reduced to
     *  pins/spans/sizes/texts (order/grids dropped). Empty maps when none. */
    override fun profileLayout(identityPub: String): ProfileLayout {
        data class Cand(val createdAt: Double, val seq: Int, val payload: Map<String, Any?>)
        var best: Cand? = null
        readableDatabase.rawQuery(
            "SELECT seq, msg_json FROM messages WHERE kind = ? AND identity_pub = ?",
            arrayOf("profile_layout", identityPub)
        ).use { c ->
            while (c.moveToNext()) {
                val seq = c.getInt(0)
                val payload = JSONObject(c.getString(1)).optJSONObject("payload") ?: continue
                val createdAt = payload.optDouble("created_at", 0.0)
                val cur = best
                if (cur == null || createdAt > cur.createdAt || (createdAt == cur.createdAt && seq > cur.seq))
                    best = Cand(createdAt, seq, jsonToMap(payload))
            }
        }
        val p = best?.payload ?: return ProfileLayout(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        return ProfileLayout(
            pins = layoutSubMaps(p["pins"]), spans = layoutSubMaps(p["spans"]),
            sizes = layoutSizes(p["sizes"]), texts = layoutSubMaps(p["texts"]))
    }

    /** album_id -> members for `identityPub`, latest-wins PER album_id by
     *  (created_at, seq). Same per-key newest fold pattern as wrapGrantsFor,
     *  keyed by album_id. */
    override fun albums(identityPub: String): Map<String, List<String>> {
        data class Cand(val createdAt: Double, val seq: Int, val members: List<String>)
        val best = linkedMapOf<String, Cand>()
        readableDatabase.rawQuery(
            "SELECT seq, msg_json FROM messages WHERE kind = ? AND identity_pub = ?",
            arrayOf("album", identityPub)
        ).use { c ->
            while (c.moveToNext()) {
                val seq = c.getInt(0)
                val payload = JSONObject(c.getString(1)).optJSONObject("payload") ?: continue
                val albumId = payload.opt("album_id") as? String ?: continue
                val membersArr = payload.optJSONArray("members") ?: continue
                val members = (0 until membersArr.length()).mapNotNull { membersArr.opt(it) as? String }
                val createdAt = payload.optDouble("created_at", 0.0)
                val cur = best[albumId]
                if (cur == null || createdAt > cur.createdAt || (createdAt == cur.createdAt && seq > cur.seq))
                    best[albumId] = Cand(createdAt, seq, members)
            }
        }
        return best.mapValues { it.value.members }
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.SyncStoreTest"`
Expected: PASS (the four new accessor tests). Then run the whole suite `./gradlew :tor-manager:testDebugUnitTest` — the full existing JVM suite still green (the two new `internal` helpers + `ProfileLayout` compile; every other `SyncStore` impl is untouched). Note: SQLite overrides are JVM-untestable without Robolectric (SyncStoreTest is InMemory-only, matching how every prior accessor was JVM-covered); the SQL mirror is exercised on-device in Task 5.

- [ ] **Step 7: Commit**

```bash
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/SyncStore.kt \
        android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/SqliteSyncStore.kt \
        android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/SyncStoreTest.kt
git commit -m "feat(vp3): plaintext profile-record, layout, and album store accessors"
```

---

## Task 2: the pure wall-assembly + profile builders

**Files:**
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalApi.kt`
- Test: `android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/LocalApiTest.kt`

**Interfaces:**
- Consumes: `DecryptPass.Decrypted` (fields `msgId, kind, author, text, createdAt, blobs, thumbs, media, poster, storyRefMediaHash, identityPub, scope, expiresAt, placement, codec`); the existing companion `feedRow(d, ownIdentityPub, responses)`; `ProfileLayout` (Task 1).
- Produces (companion, all pure / JVM-testable):
  - `LocalApi.TEXT_STYLE_DEFAULTS: Map<String, String>`
  - `LocalApi.mapToJson(m: Map<String, Any?>): JSONObject`
  - `LocalApi.foldAlbumMembers(albums: Map<String, List<String>>): Map<String, String>`
  - `LocalApi.defaultSpan(d: DecryptPass.Decrypted, sizes: Map<String, String>): JSONObject`
  - `LocalApi.wallBlockJson(d: DecryptPass.Decrypted, ownIdentityPub: String, layout: ProfileLayout): JSONObject`
  - `LocalApi.albumBlockJson(albumId: String, mine: Boolean, photos: JSONArray, createdAt: Double, scopeNewest: String, pin: Map<String, Any?>?, span: JSONObject): JSONObject`
  - `LocalApi.wallJson(wall: List<DecryptPass.Decrypted>, layout: ProfileLayout, albums: Map<String, List<String>>, ownIdentityPub: String, mine: Boolean): JSONArray`

- [ ] **Step 1: Confirm the TEXT_STYLE_ENUMS defaults, then write the failing tests**

Read `hearth/messages.py:30-37` (`TEXT_STYLE_ENUMS`) and CONFIRM each tuple's FIRST value (the default). The confirmed values (use verbatim if the read matches): `h="left", v="top", size="auto", font="sans", weight="normal", style="normal"`; plus hearth's separately-added `color="default"` (node.py `profile_view` sets `text_style_defaults["color"] = "default"`). If a read differs, copy the read verbatim.

Add to `LocalApiTest.kt` (append inside `class LocalApiTest`). A helper builds a profile wall post:

```kotlin
    // -- vp3 slice 3 Task 2: pure wall-assembly builders --

    private fun wallPost(
        msgId: String, createdAt: Double, blobs: List<String> = emptyList(),
        thumbs: List<String?> = emptyList(), media: String = "photo", scope: String = "kreds",
    ) = DecryptPass.Decrypted(
        msgId = msgId, kind = "post", author = "Me", text = "t", createdAt = createdAt,
        blobs = blobs, thumbs = thumbs, media = media, poster = null, storyRefMediaHash = null,
        identityPub = "own", scope = scope, expiresAt = null, placement = "profile", codec = null)

    private val emptyLayout = ProfileLayout(emptyMap(), emptyMap(), emptyMap(), emptyMap())

    @Test fun defaultSpanBranches() {
        val text = wallPost("t", 1.0)                                      // no blobs, photo
        val photo = wallPost("p", 1.0, blobs = listOf("b"))
        val video = wallPost("v", 1.0, media = "video")                    // no blobs but video
        // full (default size) -> 4x1 for pure text, 4x2 for media / video
        assertSpan(1, 1, LocalApi.defaultSpan(text, mapOf("t" to "small")))
        assertSpan(2, 2, LocalApi.defaultSpan(text, mapOf("t" to "wide")))
        assertSpan(4, 1, LocalApi.defaultSpan(text, emptyMap()))           // full + no media
        assertSpan(4, 2, LocalApi.defaultSpan(photo, emptyMap()))          // full + blobs
        assertSpan(4, 2, LocalApi.defaultSpan(video, emptyMap()))          // full + video (has_media)
    }

    private fun assertSpan(w: Int, h: Int, o: JSONObject) {
        assertEquals(w, o.getInt("w")); assertEquals(h, o.getInt("h"))
    }

    @Test fun foldAlbumMembersSmallestAlbumIdWins() {
        // "m1" is in both album "A" and album "B"; sorted iteration hits "A"
        // first, setdefault keeps it -> "A" wins the conflict.
        val member = LocalApi.foldAlbumMembers(mapOf(
            "B" to listOf("m1", "m2"), "A" to listOf("m1", "m3")))
        assertEquals("A", member["m1"])
        assertEquals("B", member["m2"])
        assertEquals("A", member["m3"])
    }

    @Test fun wallBlockJsonPinnedMirrorsPinAndOmitsTextStyleForMedia() {
        val d = wallPost("p", 5.0, blobs = listOf("b"), thumbs = listOf<String?>("th"))
        val layout = ProfileLayout(
            pins = mapOf("p" to mapOf("x" to 1, "y" to 2, "w" to 3, "h" to 2)),
            spans = emptyMap(), sizes = emptyMap(), texts = emptyMap())
        val o = LocalApi.wallBlockJson(d, "own", layout)
        // base feedRow fields present + the three added fields
        assertEquals("p", o.getString("msg_id"))
        assertTrue(o.getBoolean("mine"))                       // identity == own
        assertTrue(o.isNull("responses"))                      // wall rows: responses null
        val pin = o.getJSONObject("pin")
        assertEquals(1, pin.getInt("x")); assertEquals(3, pin.getInt("w"))
        assertSpan(3, 2, o.getJSONObject("span"))              // span mirrors pin w/h
        assertFalse("media block has no text_style", o.has("text_style"))
    }

    @Test fun wallBlockJsonUnpinnedTextHasDefaultedTextStyleWithOverride() {
        val d = wallPost("t", 5.0)                              // pure text
        val layout = ProfileLayout(
            pins = emptyMap(), spans = emptyMap(), sizes = emptyMap(),
            texts = mapOf("t" to mapOf("h" to "center", "size" to "xl")))
        val o = LocalApi.wallBlockJson(d, "own", layout)
        assertTrue(o.isNull("pin"))                            // unpinned -> null
        assertSpan(4, 1, o.getJSONObject("span"))              // default span, full + no media
        val ts = o.getJSONObject("text_style")
        assertEquals("center", ts.getString("h"))              // override
        assertEquals("xl", ts.getString("size"))               // override
        assertEquals("top", ts.getString("v"))                 // default
        assertEquals("sans", ts.getString("font"))             // default
        assertEquals("default", ts.getString("color"))         // hearth's color default
    }

    @Test fun albumBlockJsonShape() {
        val photos = JSONArray().put(JSONObject().put("m", "m1").put("h", "h1").put("t", "t1"))
        val o = LocalApi.albumBlockJson(
            albumId = "A", mine = false, photos = photos, createdAt = 9.0,
            scopeNewest = "inner", pin = null, span = JSONObject().put("w", 2).put("h", 2))
        assertEquals(setOf("album", "msg_id", "mine", "photos", "count",
            "created_at", "scope_newest", "pin", "span"), o.keys().asSequence().toSet())
        assertTrue(o.getBoolean("album"))
        assertEquals("A", o.getString("msg_id"))
        assertFalse(o.getBoolean("mine"))
        assertEquals(1, o.getInt("count"))
        assertEquals("inner", o.getString("scope_newest"))
        assertTrue(o.isNull("pin"))
        assertSpan(2, 2, o.getJSONObject("span"))
        val ph = o.getJSONArray("photos").getJSONObject(0)
        assertEquals("m1", ph.getString("m")); assertEquals("t1", ph.getString("t"))
    }

    @Test fun wallJsonFoldsAlbumsRemovesMembersAndSortsDesc() {
        // p1 (loose, newest), a1+a2 (album A members), p0 (loose, oldest)
        val wall = listOf(
            wallPost("p1", 30.0, blobs = listOf("bp1")),
            wallPost("a1", 20.0, blobs = listOf("ba1"), thumbs = listOf<String?>("ta1"), scope = "inner"),
            wallPost("a2", 25.0, blobs = listOf("ba2"), thumbs = listOf<String?>(null)),
            wallPost("p0", 5.0))
        val albums = mapOf("A" to listOf("a1", "a2"))
        val arr = LocalApi.wallJson(wall, emptyLayout, albums, "own", mine = true)
        // 3 blocks: p1, album A, p0 -- a1/a2 folded away; sorted created_at DESC
        assertEquals(3, arr.length())
        assertEquals("p1", arr.getJSONObject(0).getString("msg_id"))     // 30
        val album = arr.getJSONObject(1)                                  // A: newest member 25
        assertTrue(album.getBoolean("album"))
        assertEquals("A", album.getString("msg_id"))
        assertTrue(album.getBoolean("mine"))
        assertEquals(2, album.getInt("count"))                           // ba1 + ba2
        assertEquals("kreds", album.getString("scope_newest"))           // a2 (25) is newest -> its scope
        assertSpan(2, 2, album.getJSONObject("span"))                    // album default span
        val ph0 = album.getJSONArray("photos").getJSONObject(0)
        assertEquals("a1", ph0.getString("m")); assertEquals("ta1", ph0.getString("t"))
        val ph1 = album.getJSONArray("photos").getJSONObject(1)
        assertEquals("a2", ph1.getString("m")); assertTrue(ph1.isNull("t"))   // null thumb
        assertEquals("p0", arr.getJSONObject(2).getString("msg_id"))     // 5
    }

    @Test fun wallJsonSkipsVideoMembersAndEmptyAlbums() {
        // album with only a video member yields NO photos -> album dropped;
        // the video member is still folded out of the loose list (member_of).
        val wall = listOf(wallPost("v1", 10.0, media = "video", blobs = listOf("bv")))
        val arr = LocalApi.wallJson(wall, emptyLayout, mapOf("A" to listOf("v1")), "own", mine = true)
        assertEquals(0, arr.length())
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.LocalApiTest"`
Expected: FAIL / compile error — `LocalApi.defaultSpan`, `foldAlbumMembers`, `wallBlockJson`, `albumBlockJson`, `wallJson` are unresolved.

- [ ] **Step 3: Add the wall-assembly builders to `LocalApi.kt`**

In `LocalApi.kt`, inside the `companion object { … }` (place just above `feedRow`, since `wallBlockJson` calls it), add:

```kotlin
        // vp3: text_style defaults = each TEXT_STYLE_ENUMS tuple's first value
        // (messages.py:30-37) plus color="default" (node.py profile_view sets
        // it separately). A pure-text wall block's text_style is these defaults
        // merged with the layout's per-block override (layout.texts[msgId]).
        val TEXT_STYLE_DEFAULTS = linkedMapOf(
            "h" to "left", "v" to "top", "size" to "auto",
            "font" to "sans", "weight" to "normal", "style" to "normal",
            "color" to "default")

        // vp3: a plain Kotlin map -> JSONObject (null values -> JSON null).
        fun mapToJson(m: Map<String, Any?>): JSONObject {
            val o = JSONObject()
            for ((k, v) in m) o.put(k, v ?: JSONObject.NULL)
            return o
        }

        // vp3: port of hearth _fold_album_members (node.py:1178-1195). For each
        // album_id in SORTED order, the first album to claim a member keeps it
        // (Python setdefault) -- so the smallest album_id wins a member that two
        // albums list. An explicit `!in` check (not Map.putIfAbsent, which is
        // API 24+) keeps this min-SDK-safe. Returns member msgId -> album_id.
        fun foldAlbumMembers(albums: Map<String, List<String>>): Map<String, String> {
            val memberOf = linkedMapOf<String, String>()
            for (aid in albums.keys.sorted())
                for (mid in albums[aid] ?: emptyList())
                    if (mid !in memberOf) memberOf[mid] = aid
            return memberOf
        }

        // vp3: port of hearth profile_view's _default_span (node.py:1272-1279).
        // size = sizes[msgId] or "full"; small -> 1x1; wide -> 2x2; full ->
        // 4x2 when the post has media (blobs non-empty OR media=="video"),
        // else 4x1.
        fun defaultSpan(d: DecryptPass.Decrypted, sizes: Map<String, String>): JSONObject {
            val size = sizes[d.msgId] ?: "full"
            if (size == "small") return JSONObject().put("w", 1).put("h", 1)
            if (size == "wide") return JSONObject().put("w", 2).put("h", 2)
            val hasMedia = d.blobs.isNotEmpty() || d.media == "video"
            return if (hasMedia) JSONObject().put("w", 4).put("h", 2)
                   else JSONObject().put("w", 4).put("h", 1)
        }

        // vp3: a post wall-block = the feedRow shape + pin/span/text_style
        // (node.py profile_view's per-post annotation loop). responses is null
        // on wall rows (renderBlock never reads it). pin = layout.pins[msgId]
        // or null; span = (pin ? {pin.w,pin.h} : layout.spans[msgId] ?:
        // defaultSpan); text_style only on pure-text blocks (no blobs, not
        // video), defaults merged with layout.texts[msgId].
        fun wallBlockJson(d: DecryptPass.Decrypted, ownIdentityPub: String, layout: ProfileLayout): JSONObject {
            val o = feedRow(d, ownIdentityPub, null)
            val pin = layout.pins[d.msgId]
            o.put("pin", if (pin != null) mapToJson(pin) else JSONObject.NULL)
            val span: JSONObject = when {
                pin != null -> JSONObject().put("w", pin["w"]).put("h", pin["h"])
                layout.spans[d.msgId] != null -> mapToJson(layout.spans[d.msgId]!!)
                else -> defaultSpan(d, layout.sizes)
            }
            o.put("span", span)
            if (d.blobs.isEmpty() && d.media != "video") {
                val ts = JSONObject()
                for ((k, v) in TEXT_STYLE_DEFAULTS) ts.put(k, v)
                layout.texts[d.msgId]?.let { for ((k, v) in it) ts.put(k, v ?: JSONObject.NULL) }
                o.put("text_style", ts)
            }
            return o
        }

        // vp3: an album pseudo-block (node.py profile_view's album-fold append)
        // -- a REDUCED shape, NOT the post fields. count = photos.length().
        fun albumBlockJson(
            albumId: String, mine: Boolean, photos: JSONArray, createdAt: Double,
            scopeNewest: String, pin: Map<String, Any?>?, span: JSONObject,
        ): JSONObject = JSONObject()
            .put("album", true)
            .put("msg_id", albumId)
            .put("mine", mine)
            .put("photos", photos)
            .put("count", photos.length())
            .put("created_at", createdAt)
            .put("scope_newest", scopeNewest)
            .put("pin", if (pin != null) mapToJson(pin) else JSONObject.NULL)
            .put("span", span)

        // vp3: the whole wall list, reproducing node.py profile_view's
        // annotate-then-album-fold. `wall` is this identity's placement==
        // "profile" posts, ALREADY newest-first. Posts whose msgId is an album
        // member are removed; each album with >=1 photo becomes an album-block
        // (photos = {m,h,t} per member's blobs, video members skipped;
        // created_at = newest member's; scope_newest = newest member's scope;
        // span defaults to 2x2). The folded list is re-sorted created_at DESC
        // (stable -> ties keep wall order).
        fun wallJson(
            wall: List<DecryptPass.Decrypted>, layout: ProfileLayout,
            albums: Map<String, List<String>>, ownIdentityPub: String, mine: Boolean,
        ): JSONArray {
            val byId = wall.associateBy { it.msgId }
            val memberOf = foldAlbumMembers(albums)
            val folded = mutableListOf<Pair<Double, JSONObject>>()
            for (d in wall)
                if (d.msgId !in memberOf)
                    folded.add(d.createdAt to wallBlockJson(d, ownIdentityPub, layout))
            for (aid in albums.keys.sorted()) {
                val photos = JSONArray()
                var newest: Double? = null
                var scopeNewest = "kreds"
                for (mid in albums[aid] ?: emptyList()) {
                    val p = byId[mid] ?: continue
                    if (memberOf[mid] != aid) continue
                    if (p.media == "video") continue
                    for ((i, h) in p.blobs.withIndex())
                        photos.put(JSONObject().put("m", mid).put("h", h)
                            .put("t", p.thumbs.getOrNull(i) ?: JSONObject.NULL))
                    val cur = newest
                    if (cur == null || p.createdAt > cur) {
                        newest = p.createdAt
                        scopeNewest = p.scope ?: "kreds"
                    }
                }
                if (photos.length() == 0) continue
                val at = newest ?: continue
                val pin = layout.pins[aid]
                val span: JSONObject = when {
                    pin != null -> JSONObject().put("w", pin["w"]).put("h", pin["h"])
                    layout.spans[aid] != null -> mapToJson(layout.spans[aid]!!)
                    else -> JSONObject().put("w", 2).put("h", 2)
                }
                folded.add(at to albumBlockJson(aid, mine, photos, at, scopeNewest, pin, span))
            }
            val out = JSONArray()
            for ((_, obj) in folded.sortedByDescending { it.first }) out.put(obj)
            return out
        }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.LocalApiTest"`
Expected: PASS (the seven new wall-assembly tests). Then `./gradlew :tor-manager:testDebugUnitTest` — full suite green.

- [ ] **Step 5: Commit**

```bash
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalApi.kt \
        android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/LocalApiTest.kt
git commit -m "feat(vp3): pure wall-assembly builders reproducing hearth profile_view"
```

---

## Task 3: the profile route (`profileJson` + instance route wired into handle)

**Files:**
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalApi.kt`
- Test: `android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/LocalApiTest.kt`

**Interfaces:**
- Consumes: Task-2 `wallJson`; the existing `feedRow`, `postKeys`, `notExpired`, `fixtureOrNull`, `notFound`, `json`; `DecryptPass.run(...).feed/.keys`; `DecryptPass.responsesPass(...)`; `EncKeys.getOrCreate(store)`; `SqliteSyncStore(ctx)` with `knownIdentities()`, `profileNames()`, `profileRecord()`, `profileLayout()`, `albums()` (Task 1); `ProfileLayout`.
- Produces (companion, pure): `LocalApi.defaultProfileRecord(name: String): Map<String, Any?>`; `LocalApi.profileJson(record: Map<String, Any?>, identityPub: String, mine: Boolean, ring: String, since: Any?, wall: JSONArray, journal: JSONArray): String`. Instance method `profile(identityPub: String): String?`; a new branch in `handle()`.

- [ ] **Step 1: Write the failing golden-shape tests**

Add to `LocalApiTest.kt`:

```kotlin
    // -- vp3 slice 3 Task 3: profileJson top-level shape + own-default record --

    @Test fun profileJsonTopLevelShapeOwn() {
        val rec = mapOf(
            "name" to "Me", "bio" to "hi", "accent" to "#2743d6", "avatar" to null,
            "avatar_shape" to "circle", "avatar_size" to "m", "avatar_align" to "left",
            "banner" to null, "banner_pos" to 50,
            "kind" to "profile", "created_at" to 1.0)                 // extra keys must NOT leak
        val wall = JSONArray().put(JSONObject().put("msg_id", "m1"))
        val o = JSONObject(LocalApi.profileJson(rec, "own", true, "kreds", null, wall, JSONArray()))
        assertEquals(setOf(
            "name", "bio", "accent", "avatar", "avatar_shape", "avatar_size",
            "avatar_align", "banner", "banner_pos", "identity_pub", "mine",
            "ring", "since", "wall", "journal"), o.keys().asSequence().toSet())
        assertEquals("Me", o.getString("name"))
        assertEquals("own", o.getString("identity_pub"))
        assertTrue(o.getBoolean("mine"))
        assertEquals("kreds", o.getString("ring"))
        assertTrue(o.isNull("since"))                                 // own -> null
        assertTrue(o.isNull("avatar"))
        assertEquals(50, o.getInt("banner_pos"))
        assertEquals(1, o.getJSONArray("wall").length())
    }

    @Test fun profileJsonSinceZeroForOtherAndDefaultRecord() {
        val rec = LocalApi.defaultProfileRecord("bob01234")
        val o = JSONObject(LocalApi.profileJson(rec, "bob", false, "kreds", 0, JSONArray(), JSONArray()))
        assertEquals("bob01234", o.getString("name"))
        assertFalse(o.getBoolean("mine"))
        assertEquals(0, o.getInt("since"))                            // other -> 0
        assertEquals("#2743d6", o.getString("accent"))
        assertEquals("circle", o.getString("avatar_shape"))
        assertEquals("m", o.getString("avatar_size"))
        assertEquals("left", o.getString("avatar_align"))
        assertTrue(o.isNull("avatar")); assertTrue(o.isNull("banner"))
        assertEquals(50, o.getInt("banner_pos"))
        assertEquals("", o.getString("bio"))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.LocalApiTest"`
Expected: FAIL / compile error — `LocalApi.defaultProfileRecord` and `LocalApi.profileJson` are unresolved.

- [ ] **Step 3: Add `defaultProfileRecord` + `profileJson` to the companion**

In `LocalApi.kt` companion (place near the other top-level builders, e.g. above `feedRow`), add:

```kotlin
        // vp3: hearth's hardcoded own-profile default (node.py:1266-1270) used
        // when identity == own and NO KIND_PROFILE record exists yet -- so the
        // own profile always renders something instead of 404ing. name =
        // profileNames[own] or own[:8] (the caller supplies the resolved name).
        fun defaultProfileRecord(name: String): Map<String, Any?> = linkedMapOf(
            "name" to name, "bio" to "", "accent" to "#2743d6", "avatar" to null,
            "avatar_shape" to "circle", "avatar_size" to "m", "avatar_align" to "left",
            "banner" to null, "banner_pos" to 50)

        // vp3: the profile route's top-level JSON (node.py profile_view's
        // return). The nine display fields come from `record` (selected BY
        // NAME so a record's incidental kind/created_at keys never leak into
        // the response), each with hearth's default as a fallback; then
        // identity_pub/mine/ring/since/wall/journal. `since` is null (own) or
        // an Int (others); pass JSONObject.NULL-safe via `since ?: NULL`.
        fun profileJson(
            record: Map<String, Any?>, identityPub: String, mine: Boolean,
            ring: String, since: Any?, wall: JSONArray, journal: JSONArray,
        ): String = JSONObject()
            .put("name", record["name"] ?: "")
            .put("bio", record["bio"] ?: "")
            .put("accent", record["accent"] ?: "#2743d6")
            .put("avatar", record["avatar"] ?: JSONObject.NULL)
            .put("avatar_shape", record["avatar_shape"] ?: "circle")
            .put("avatar_size", record["avatar_size"] ?: "m")
            .put("avatar_align", record["avatar_align"] ?: "left")
            .put("banner", record["banner"] ?: JSONObject.NULL)
            .put("banner_pos", record["banner_pos"] ?: 50)
            .put("identity_pub", identityPub)
            .put("mine", mine)
            .put("ring", ring)
            .put("since", since ?: JSONObject.NULL)
            .put("wall", wall)
            .put("journal", journal)
            .toString()
```

- [ ] **Step 4: Add the `profile()` instance method**

In `LocalApi.kt`, add this instance method next to `feed()` (it mirrors `feed()`'s decrypt-pass + cache-warm exactly, then filters by identity/placement and calls the Task-2 assembly). Use a `//` line comment for anything path-shaped — never a path glob inside a `/** */` block (KDoc trap):

```kotlin
    // vp3: the profile route body. Returns null in exactly hearth's 404 cases
    // (node.py:1262-1265): no fixture; identity is neither own nor known; or
    // identity is known-but-not-own with no stored profile record. A null
    // return -> the server 404s -> app.js's openProfile try/catch degrades to
    // fallbackProfile (it does NOT blank the page). When identity == own with
    // no record, hearth's hardcoded default is used instead of 404ing.
    private fun profile(identityPub: String): String? {
        val fx = fixtureOrNull() ?: return null
        val store = SqliteSyncStore(ctx)
        val own = fx.cert.identity_pub
        val isOwn = identityPub == own
        if (!isOwn && identityPub !in store.knownIdentities()) return null
        val names = store.profileNames()
        val record = store.profileRecord(identityPub)
            ?: (if (isOwn) defaultProfileRecord(names[own] ?: own.take(8)) else return null)
        val (priv, _) = EncKeys.getOrCreate(store)
        val res = DecryptPass.run(store, fx.device_pub, priv, own)
        keysCache = postKeys(res.feed, res.keys)                 // warm blob cache (same as feed())
        val responses = DecryptPass.responsesPass(store, fx.device_pub, priv, own)
        val now = System.currentTimeMillis() / 1000.0
        // res.feed is already newest-first; both filters preserve that order.
        val wallPosts = res.feed.filter {
            it.kind == "post" && it.identityPub == identityPub &&
                it.placement == "profile" && notExpired(it.expiresAt, now)
        }
        val railPosts = res.feed.filter {
            it.kind == "post" && it.identityPub == identityPub &&
                (it.placement ?: "journal") == "journal" && notExpired(it.expiresAt, now)
        }
        val wall = wallJson(wallPosts, store.profileLayout(identityPub), store.albums(identityPub), own, isOwn)
        val journal = JSONArray()
        for (d in railPosts) journal.put(feedRow(d, own, responses[d.msgId]))
        val since: Any? = if (isOwn) null else 0
        return profileJson(record, identityPub, isOwn, "kreds", since, wall, journal)
    }
```

- [ ] **Step 5: Wire the route into `handle()`**

In `handle()`'s `when { … }`, add a branch (place it after the `path == "/api/kreds"` line and before the `/api/dm-blob/` branch). The `profile()` null → 404 is what reproduces hearth's 404 (do NOT synthesize a fallback body):

```kotlin
            path.startsWith("/api/profile/") -> {
                val id = path.removePrefix("/api/profile/")
                if (id.isEmpty() || id.contains("/")) notFound()
                else profile(id)?.let { json(it) } ?: notFound()
            }
```

- [ ] **Step 6: Run the tests + full suite to verify they pass**

Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.LocalApiTest"`
Expected: PASS (the two new `profileJson`/`defaultProfileRecord` tests). Then `./gradlew :tor-manager:testDebugUnitTest` — full suite green. (The store-touching `profile()` route — the 404 is-known gate, own-default, DecryptPass wiring — needs a real store + WebView and is exercised on-device in Task 5, mirroring how slice 1 left `feed()`'s live decrypt to its on-device task.)

- [ ] **Step 7: Commit**

```bash
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalApi.kt \
        android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/LocalApiTest.kt
git commit -m "feat(vp3): serve read-only profile view in hearth profile_view shape"
```

---

## Task 4: close the friend-profile write-affordance seam gap

**Files:**
- Modify: `hearth/web/app.js` (one call site, `app.js:2274`)
- Modify: `hearth/web/style.css` (two selectors in the `body.readonly` block)
- Test: `android_tor_spike/app/test/web-readonly-seam.test.ts`

**Interfaces:**
- Consumes: the existing `body.readonly` seam (`style.css:1211-1223`), already driven by the profile route's `readonly:true` in `/api/state` (slice 1); the friend-profile `#profile-actions` buttons built in `renderProfilePage`'s `!p.mine` branch (`app.js:2272-2290`).
- Produces: `class="ring-move"` on the move button; `#profile-actions .ring-move` + `#profile-actions .btn-danger` hidden under `body.readonly`; an extended vitest guard.

- [ ] **Step 1: Confirm the friend-profile button call sites, then write the failing vitest guard**

Read `hearth/web/app.js:2272-2290` and CONFIRM: `move = el("button", "", …)` has an EMPTY class (app.js:2274), `unfriendBtn = el("button", "btn-danger", "Unfriend")` (app.js:2282), and both `onclick` handlers `await j(...)` UNGUARDED (a 404 throws). Also confirm `btn-danger` is shared with the App-lock disable button (`app.js:3795`, `el("button", "btn-danger", "Disable App-lock")`) — which is why the seam selector must be `#profile-actions`-scoped, not a blanket `.btn-danger`. If the call sites differ from this, STOP and reconcile before editing.

In `android_tor_spike/app/test/web-readonly-seam.test.ts`, add the two scoped selectors to `HIDDEN_SELECTORS` (the existing "hides every known write affordance" loop then covers them):

```ts
const HIDDEN_SELECTORS = [
  ".composer",
  ".comment-composer",
  ".rx-open",
  ".rx-picker",
  ".pact.del",
  ".settings-del",
  ".story-tile .story-ring.add",
  ".comment-x",
  "#profile-cog",
  "#profile-arrange",
  "#profile-addfriend",
  "#dm-compose",
  "#profile-actions .ring-move",   // vp3: friend-profile ring-move (POST write)
  "#profile-actions .btn-danger",  // vp3: friend-profile Unfriend (POST write), scoped
];
```

Then add a new `it(...)` inside the `describe("vp1 read-only seam", …)` block asserting the move button now carries the `ring-move` class in app.js (so the CSS selector has something to match):

```ts
  it("app.js gives the friend-profile move button a ring-move class (vp3)", () => {
    const js = web("app.js");
    expect(js).toMatch(/el\(\s*["']button["']\s*,\s*["']ring-move["']/);
  });
```

- [ ] **Step 2: Run the guard to verify it fails**

Run (from `android_tor_spike/app`): `npx vitest run test/web-readonly-seam.test.ts`
Expected: FAIL — the two `#profile-actions` selectors are not yet in the `body.readonly` block, and the move button has no `ring-move` class.

- [ ] **Step 3: Add the `ring-move` class at the move-button call site**

In `hearth/web/app.js`, change the move-button construction (currently `app.js:2274`) from an empty class to `ring-move`:

```javascript
    const move = el("button", "ring-move", p.ring === "inner" ? "Move to kreds" : "Move to inner kreds");
```

(Only the second argument changes, from `""` to `"ring-move"`. The `onclick` handler and label are untouched — this is purely a hook for the read-only seam.)

- [ ] **Step 4: Add the two scoped selectors to the `body.readonly` seam**

In `hearth/web/style.css`, extend the `body.readonly` selector list (currently ending `body.readonly #dm-compose {` at `style.css:1222`) with the two friend-profile write buttons, scoped to `#profile-actions`:

```css
body.readonly .composer,
body.readonly .comment-composer,
body.readonly .rx-open,
body.readonly .rx-picker,
body.readonly .pact.del,
body.readonly .settings-del,
body.readonly .comment-x,
body.readonly .story-tile .story-ring.add,
body.readonly #profile-cog,
body.readonly #profile-arrange,
body.readonly #profile-addfriend,
body.readonly #dm-compose,
body.readonly #profile-actions .ring-move,
body.readonly #profile-actions .btn-danger {
  display: none !important;
}
```

(`.btn-danger` is SCOPED to `#profile-actions` so the App-lock "Disable App-lock" button — the only other `.btn-danger` user, in the Settings panel — is NOT hidden. The `Message` btn-accent button is intentionally left reachable: it only navigates to the already-read-only Messages view. Also update the stale comment above the block, `style.css:1206-1208`, which says the friend-profile controls "aren't covered yet … that's a slice-3 follow-up" — they are covered now; change it to note vp3 closed the gap.)

- [ ] **Step 5: Run the guard to verify it passes**

Run (from `android_tor_spike/app`): `npx vitest run test/web-readonly-seam.test.ts`
Expected: PASS — all read-only-seam tests plus the new `ring-move`-class assertion.

- [ ] **Step 6: Confirm desktop is unaffected**

By inspection: a desktop node's `/api/state` never sets `readonly`, so `body.readonly` is never applied and the Move/Unfriend buttons render + function normally there; the `ring-move` class is inert without the `body.readonly` prefix. No desktop test change required.

- [ ] **Step 7: Commit**

```bash
git add hearth/web/app.js hearth/web/style.css android_tor_spike/app/test/web-readonly-seam.test.ts
git commit -m "feat(vp3): read-only seam hides friend-profile ring-move and unfriend"
```

---

## Task 5: on-device integration + report + PAUSE

**Files:**
- Create: `android_tor_spike/BRICK_VP3_REPORT.md`

**Interfaces:**
- Consumes: everything from Tasks 1-4 (built into one RELEASE apk; the slice-1 `copyHearthWeb` task re-syncs the `hearth/web` edits into the APK).
- Produces: the on-device proof record + follow-up tickets; a review PAUSE.

- [ ] **Step 1: Full desk-gate sweep**

Run and record outputs:
- `./gradlew :tor-manager:testDebugUnitTest` (from `android_tor_spike/app/android`, with `JAVA_HOME`/`ANDROID_HOME` set per Global Constraints) — expect the whole JVM suite green incl. the new `SyncStoreTest` accessor tests and the new `LocalApiTest` wall-assembly + `profileJson` tests.
- `npx tsc --noEmit` (from `android_tor_spike/app`) — expect 0 new errors (pre-existing `tools/` node-type errors may remain).
- `npx vitest run test/web-readonly-seam.test.ts` (from `android_tor_spike/app`) — expect green.

- [ ] **Step 2: Build + install the RELEASE apk**

From `android_tor_spike/app/android`: `./gradlew :app:assembleRelease`. Confirm `copyHearthWeb` ran (the app.js/style.css edits land in the APK's `assets/www/`). Install `app/build/outputs/apk/release/app-release.apk` on the G20 (`adb install -r …`). RELEASE, not debug (field lesson: a debug build yields "Unable to load script").

- [ ] **Step 3: Run the on-device DoD (human-driven)**

Preconditions (field lessons): the desktop peer must be reachable over Tor — run the desktop node with `serve --tor` (a bare `hearth app` has Tor OFF and the sync EOFs; a locked node refuses sync as a bare EOF). To exercise the wall, from the desktop arrange the own profile into a composed page: pin at least one block, leave at least one block flowing, include at least one photo block, at least one pure-text block, and a folded album (2+ photos grouped). Also have at least one friend whose profile has been synced. Let the phone sync (background or reopen).

DoD checklist — tick each:
- [ ] Tapping **Me** opens the own profile: banner + avatar + bio + accent render; no crash/blank.
- [ ] The 4-column wall renders: pinned blocks sit where arranged, flowing blocks fill the grid, photos load (via `/api/post-blob`), pure-text blocks show their text_style, and a multi-photo album renders as ONE folded deck (not scattered single photos).
- [ ] The profile journal rail renders this identity's journal posts (with any collapsed reaction-count line).
- [ ] The own profile is read-only: no composer, no Arrange button, no Settings cog, no add-friend "+".
- [ ] Tapping a friend opens THEIR profile: header + wall render; the **Message** button is present; there is NO Unfriend button and NO ring-move ("Move to inner/kreds") button (both hidden by the seam), and tapping around produces NO unhandled-rejection JS error in `adb logcat`.
- [ ] Regression: the journal feed and the Messages view still render (the profile route did not disturb the always-awaited refresh chain).
- [ ] No token/CSP errors in `adb logcat` while browsing the profile + wall.

- [ ] **Step 4: Write `BRICK_VP3_REPORT.md`**

Mirror `BRICK_VP2_REPORT.md`'s structure. Include: (a) a desk-gates table (each command + green/red); (b) the on-device DoD checklist above with pass/fail + notes; (c) run gotchas (RELEASE apk only; desktop `serve --tor`; a locked node refuses sync as bare EOF; arrange a composed own profile first — pinned + flowing + photo + text + album — and sync a friend's profile); (d) the honest boundary (read-only Me/profile only; header/banner/accent + the 4-column wall + the journal rail render; composing/arranging/writing are hidden and out of scope; the wall never reflows — it scales cells, by design; friend ring/since is the reused default, not real KIND_RING membership); (e) follow-up tickets:
  - **ring/since accuracy** — the phone doesn't process KIND_RING, so a friend's `ring`/`since` are the reused `kreds()`-route default (`"kreds"`/`0`); real ring membership + the circle view are a later slice (same pre-existing gap `kreds()` already documents).
  - **wall-autoplace (never-arranged posts)** — the `POST /api/wall-autoplace` migration is not implemented (fire-and-forget, safe 404); a never-arranged wall renders via the newest-first flow grid, a legitimate designed-for state, but auto-pin-to-top is unbuilt.
  - **STATE.disconnected always empty** — `/api/state` hardcodes an empty `disconnected` list, so app.js's `fallbackProfile` "no longer connected" branch is unreachable on the phone; an unfriended-on-desktop identity isn't distinguishable from an unknown one yet.
  - **SQLite accessor coverage** — `profileRecord`/`profileLayout`/`albums` are JVM-tested only against `InMemorySyncStore` (SyncStoreTest is InMemory-only); a Robolectric pass over the real `SqliteSyncStore` SQL is a later hardening ticket.
  - **profile-header + comment-author avatars** — still deferred (no avatar-blob accessor threaded through the wall/rail; the wall's own photos work via `/api/post-blob`).

- [ ] **Step 5: Commit + PAUSE**

```bash
git add android_tor_spike/BRICK_VP3_REPORT.md
git commit -m "docs(vp3): on-device proof record and follow-up tickets for slice 3"
```

Then PAUSE for human review. Do not proceed past this checkpoint without sign-off. Whether to merge the slice-3 branch is the human's call (mirrors the B.2-era "merge is August's call" pattern).

---

## Self-Review

**1. Spec coverage.** Every slice-3 scope item maps to a task:
- Profile route with the EXACT top-level shape (`{…rec, identity_pub, mine, ring, since, wall, journal}`, not nested) → Task 3 (`profileJson` + `profile()` + route).
- The 4-column wall: post-blocks with pin/span/text_style + folded album pseudo-blocks → Task 2 (`wallJson`/`wallBlockJson`/`albumBlockJson`/`defaultSpan`/`foldAlbumMembers`) fed by Task 3's identity+placement filter.
- The profile journal rail (feedRow shape WITH responses) → Task 3 (`railPosts` + `feedRow(d, own, responses[d.msgId])`).
- Three plaintext store accessors, latest-wins by (created_at, seq), per-album fold → Task 1.
- The 404 condition reproduced exactly (identity != own && !isKnown → null; known-but-recordless-not-own → null; own-recordless → hardcoded default) → Task 3 `profile()` + Pinned decision 3.
- ring/since default reuse → Task 3 (`"kreds"` / null-or-0) + follow-up ticket.
- Close the friend-profile write-affordance seam gap (ring-move class + scoped btn-danger, Message left reachable, vitest guard) → Task 4.
- Reuse existing blob routes (no new blob route) → Task 3 warms `keysCache = postKeys(...)`, blobs served by the existing `/api/post-blob`/`/api/blob`.
- On-device DoD + report + PAUSE → Task 5.
- NOT-in-slice-3 (wall-autoplace, block-\*, ring/unfriend writes, arrange, any wall CSS change) — correctly excluded; no task implements them; the write routes stay unrouted (safe 404).

**2. Placeholder scan.** No "TBD"/"similar to Task N"/"add error handling". The three implementation-time confirmations are written read→confirm→implement with the confirmed values inline as the fallback: the TEXT_STYLE_ENUMS defaults (Task 2 Step 1 — read messages.py:30-37, values quoted), the KIND_PROFILE/PROFILE_LAYOUT/ALBUM payload field names (Task 1 Step 1 — read make_profile/make_profile_layout/make_album, names quoted), and the friend-profile button call sites (Task 4 Step 1 — read app.js:2272-2290, shapes quoted). Every code step shows complete Kotlin/TS/CSS/JS. No path-shaped globs inside any `/** */` block — the `profile()` doc + `handle()` branch use `//` line comments / prose per the KDoc trap constraint.

**3. Type consistency.**
- `ProfileLayout(pins, spans, sizes, texts)` — defined top-level in SyncStore.kt (Task 1), returned by `profileLayout()` in both store impls, consumed by `wallBlockJson`/`wallJson` (Task 2) and `profile()` (Task 3) with identical field names.
- `layoutSubMaps(v)` / `layoutSizes(v)` — `internal` top-level in SyncStore.kt (Task 1), called by BOTH `InMemorySyncStore.profileLayout` and `SqliteSyncStore.profileLayout`.
- `profileRecord(identityPub): Map<String,Any?>?` / `profileLayout(identityPub): ProfileLayout` / `albums(identityPub): Map<String,List<String>>` — same signatures in the interface, both impls, the Task-1 tests, and `profile()` (Task 3).
- `feedRow(d, ownIdentityPub, responses)` — existing companion signature; `wallBlockJson` calls it with `responses = null`, the rail calls it with `responses[d.msgId]` (a `KotlinResponses.Responses?`), both matching.
- `wallJson(wall, layout, albums, ownIdentityPub, mine)` — defined Task 2, called with those exact args in `profile()` (Task 3).
- `defaultProfileRecord(name)` / `profileJson(record, identityPub, mine, ring, since, wall, journal)` — defined Task 3, constructed identically in the Task-3 golden tests and consumed by `profile()`.
- `DecryptPass.Decrypted` — UNMODIFIED (reads existing `msgId/kind/identityPub/placement/scope/blobs/thumbs/media/createdAt/expiresAt`); the Task-2 `wallPost` helper constructs it with the existing positional constructor + `identityPub`/`scope`/`placement` named args.
- `postKeys`, `notExpired`, `fixtureOrNull`, `notFound`, `json`, `DecryptPass.run(...).feed/.keys`, `DecryptPass.responsesPass`, `EncKeys.getOrCreate`, `SqliteSyncStore.knownIdentities()/profileNames()` — all reused from slices 1-2 with their established signatures.
- Route string — `/api/profile/` prefix, parsed with the same `removePrefix` + empty/`"/"` guard idiom as the `/api/dm/` branch; no collision with `/api/profile-layout` (never GET-routed) since `"-"` ≠ `"/"` after `profile`.

No inconsistencies found.
