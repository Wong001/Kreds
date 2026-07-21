# Android Visual Parity — WebView Shell (Slice 2: Messages) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The phone's Messages tab renders read-only — the desktop app's conversation list plus a DM thread (own DMs right/accent, received left, "cannot decrypt" placeholders, DM photos) — by extending the slice-1 `LocalApi` with read-only conversations, dm-thread, and dm-blob routes marshaled from the native store into hearth's EXACT snake_case JSON, adding a Messages entry to the mobile tab bar, and hiding the DM composer via the read-only seam.

**Architecture:** Slice 1 already serves `hearth/web` from a loopback server behind a WebView, backed by read-only `/api` routes over the native SQLite store (decrypt-on-read). Slice 2 adds three read-only routes to the SAME `LocalApi.kt`: the conversations list, the per-partner DM thread, and per-DM blob bytes. The DM message list is built from the RAW `store.allMessages()` DMs joined against `DecryptPass.run`'s decrypted map, so hearth's undecryptable-placeholder rows and message counts are reproduced exactly. Two one-line shared-UI edits (a mobile Messages tab button; the composer added to the read-only seam) make the view reachable and read-only on a phone.

**Tech Stack:** Kotlin (Expo native module `tor-manager`, JUnit4 + org.json JVM tests), the shared `hearth/web` bundle (HTML/CSS re-synced into the APK by the existing Gradle `copyHearthWeb` task), vitest (read-only-seam static guard). No new RN/native wiring — the slice-1 `LocalWebServer`, `WebShell`, and asset-copy pipeline are reused unchanged.

## Global Constraints

- Commit messages `feat(vp2): …` / `fix(vp2): …`, lowercase. **NO AI / Co-Authored-By / "Generated with" trailers on ANY commit.**
- Decrypt-on-read: NO decrypted bytes or keys are ever written to disk. The server decrypts on demand, in-memory, and streams; caches live only in memory.
- Exact hearth JSON shapes: every route's response must byte/shape-match `hearth/api.py` (SNAKE_CASE: `msg_id`, `identity_pub`, `last_from_me`, `from_me`, `story_ref`, …) so `hearth/web/app.js` runs unchanged. Golden-shape tests are the guard.
- **THE LOAD-BEARING LESSON (from slice 1):** `app.js`'s `j()` throws on any non-2xx, and `refresh()` awaits a FIXED set of routes EVERY tick — `/api/state`, `/api/feed`, `/api/kreds` (slice 1) THEN the conversations route (`app.js:4526`, awaited via `loadConversations()` on **every** tick, NOT just when Messages is open — a 404 there aborts the whole boot/refresh chain, so `restoreView()`/`connectWs()`/heartbeats never run). So the conversations route MUST return 2xx (even an empty `[]`). The dm-thread route is awaited on thread-open and on every tick while a thread is open — it too MUST return 2xx. The dm-blob route is a plain `<img src>` (a 404 shows a broken image, does not throw), so it need not be stubbed but IS implemented this slice.
- Shared `hearth/web` changes are re-synced into the APK byte-for-byte by the existing Gradle `copyHearthWeb` task; a `hearth/web` edit + a rebuild covers both the desktop and the phone copy. Only touch `hearth/web` for the two shared-UI changes in Task 4.
- **KDoc trap:** never write a literal endpoint glob (a slash + `api` + slash + star) inside a Kotlin `/** */` block comment — it triggers a nested-comment compile break (bit slice 1). In doc comments name routes in prose ("the conversations route") or use `//` line comments for anything path-shaped.

**Test command reference** (Windows: use `.\gradlew.bat`; git-bash: `./gradlew`). For the Kotlin JVM tests set `JAVA_HOME=/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot` and `ANDROID_HOME=~/AppData/Local/Android/Sdk`:
- Kotlin JVM tests: from `android_tor_spike/app/android` → `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.<Class>"`
- vitest: from `android_tor_spike/app` → `npx vitest run test/web-readonly-seam.test.ts`
- tsc: from `android_tor_spike/app` → `npx tsc --noEmit` (0-new; pre-existing `tools/` node-type errors exist)
- Release APK: from `android_tor_spike/app/android` → `./gradlew :app:assembleRelease` (NDK r27.1 pinned in slice 1; apk at `app/build/outputs/apk/release/app-release.apk`)

## Pinned design decisions (do NOT re-decide)

1. **Routes + EXACT shapes (reference §1).**
   - Conversations route → a flat list `[{identity_pub, name, last_text(str|null), last_from_me(bool|null), last_at(float|null), count(int)}]`, sorted by `last_at` DESC (a null/empty `last_at` sorts as 0/last). Only partners WITH DM history (hearth's own behavior — `renderConversations` unions in `STATE.friends` client-side, so do NOT add friends server-side).
   - Dm-thread route → a flat list `[{msg_id, from_me(bool), created_at(float), expires_at(float|null), text(str|null), blobs(list[str]), undecryptable(bool), story_ref(dict|null)}]`, ordered `created_at` ASC. NOT wrapped in `{messages, partner}`.
   - Dm-blob route → raw decrypted bytes, `Content-Type` (sniff) + `X-Content-Type-Options: nosniff`, **NO Cache-Control** (hearth's dm_blob route sets none — do NOT reuse `mediaResponse()`, which hardcodes an immutable Cache-Control; use a dm-specific header map). Kind-gated to DM (a post's msgId must 404 here).
2. **Reproduce hearth's undecryptable placeholder rows.** hearth emits ONE thread entry per stored DM row for the pair, decryptable or not (undecryptable → `{undecryptable:true, text:null, blobs:[]}`), and `count`/`last_*` include them; but `DecryptPass.run()` DROPS undecryptable messages. So build the DM message list from the RAW `store.allMessages()` filtered to `kind=="dm"` (NOT from the Decrypted list), JOINED with a `msgId->Decrypted` map from `DecryptPass.run`: `undecryptable = (msgId not in decrypted map)`; `text`/`blobs` from the Decrypted entry when present, else `null`/`[]`. This keeps `count` + `last_text`/`last_from_me`/`last_at` correct and renders the "(cannot decrypt on this device)" bubble app.js expects.
3. **Slice 2 does NOT modify `DecryptPass.Decrypted` / `DecryptPass.kt` at all.** The DM extraction must iterate the RAW DMs (to keep undecryptable rows, which `DecryptPass.run` drops), so it derives everything it needs from the raw `StoredMsg`: partner + `from_me` from `StoredMsg.identityPub` and `payload["to"]`; `undecryptable` from whether the msgId is absent from the join map; `created_at`/`expires_at`/`story_ref` from the plaintext outer `payload`. Only `text`/`blobs` come from the `msgId->Decrypted` join map produced by `DecryptPass.run`. Since the partner is read from the raw `payload["to"]`, a `Decrypted.to` field would have NO consumer — YAGNI, so it is deliberately NOT added (unlike the vp1-additive `scope`/`codec`, which ARE consumed by `feedRow`; a speculative field on the shared, security-adjacent `DecryptPass` is not worth the churn).
4. **Partner derivation** (mirror hearth `dm_conversations`): for a DM, `sender = StoredMsg.identityPub`, `recipient = payload["to"]`; `partner = if (sender == own) recipient else sender`; `from_me = (sender == own)`. Own identity from the fixture (`fx.cert.identity_pub`, same as `feed()`/`state()`). A partner P's thread = all DMs whose `{sender, recipient}` is `{own, P}` either direction.
5. **Ordering:** `created_at` ASC. `allMessages()` has NO `ORDER BY`, returning SQLite natural/rowid scan order in practice. Use a Kotlin STABLE `sortedBy { createdAt }` so same-`created_at` ties preserve `allMessages()`' scan order (≈ hearth's rowid tiebreak). The exact tiebreak hearth uses (`store.py:988-1019`: `ORDER BY created_at ASC, rowid ASC`) is a FOLLOW-UP ticket — a proper port would surface `rowid` on `StoredMsg`; for slice 2 the stable-sort approximation is accepted and flagged.
6. **DM blob keys:** a `dmKeys(feed, keys)` companion mirroring `postKeys` but `filter { it.kind == "dm" }` (`Result.keys` is kind-agnostic by msgId). A `@Volatile dmKeysCache` warmed by the conversations/dm builder (which already runs `DecryptPass.run`), with a fresh-run fallback in `dmBlob` (mirror `postBlob`'s cache+fallback). The dm-blob response OMITS Cache-Control.
7. **Shared-UI change A — mobile Messages tab.** The stock `.tabbar-mobile` (`index.html:365-369`) has only Circle/Journal/Me — a phone has no way to reach Messages. Add `<button data-tab="messages">Messages</button>`. The existing handler (`app.js:4847-4848`, `b.dataset.tab === "me" ? openMe() : goView(b.dataset.tab)`) already routes `data-tab="messages"` → `goView("messages")` → `setView("messages")`; the CSS `.tabbar-mobile button { flex:1 }` (`style.css:828`) auto-sizes a 4th button. No app.js/css change needed beyond index.html — VERIFY the handler + `goView` route at implementation. (The unread badge `#nav-msg-badge` is `.navlinks`-scoped/desktop-only — a mobile unread badge is a follow-up, out of slice 2.)
8. **Shared-UI change B — hide the DM composer.** Add `#dm-compose` to the `body.readonly` selector list in `hearth/web/style.css` (the vp1 seam, `style.css:1211-1223`). The composer is a single `<form id="dm-compose" class="dm-compose-bar">` — hiding it removes the Photo/textarea/Send together, structurally distinct from `#thread`/`#conversations` (read content stays). Extend the vitest read-only-seam guard to assert `#dm-compose` is hidden.
9. **Accept stock mobile layout:** at ≤720px `.dm-shell` collapses to one column (conv list + thread stacked). Do NOT redesign it this slice — flag "mobile Messages single-pane-with-back UX" as the top follow-up.

---

## File Structure

**Native (Kotlin), all under `android_tor_spike/app/modules/tor-manager/android/src/`** (slice 2's Kotlin changes are confined to these two files — `DecryptPass.kt` is NOT touched):
- `main/java/expo/modules/tormanager/LocalApi.kt` — MODIFY. Pure DM grouping builders + `DmMsg`/`ConvRow` types + `dmKeys` in the companion; `dmKeysCache`, the conversations/dm-thread/dm-blob routes wired into `handle()`.
- `test/java/expo/modules/tormanager/LocalApiTest.kt` — MODIFY. Task-1 grouping tests, Task-2 golden-shape tests, Task-3 `dmKeys` test.

**Web (shared UI), under `hearth/web/`:**
- `index.html` — MODIFY (one line). Mobile Messages tab button.
- `style.css` — MODIFY (one selector). `#dm-compose` in the `body.readonly` seam.

**Test (vitest), under `android_tor_spike/app/`:**
- `test/web-readonly-seam.test.ts` — MODIFY. `#dm-compose` added to the hidden-selector contract; a Messages-tab-present assertion.

**Docs, under `android_tor_spike/`:**
- `BRICK_VP2_REPORT.md` — NEW. On-device DoD + follow-ups.

---

## Task 1: Kotlin DM grouping logic

**Files:**
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalApi.kt`
- Test: `android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/LocalApiTest.kt`

**Interfaces:**
- Consumes: `DecryptPass.Decrypted` (existing fields incl. `msgId`, `kind`, `text`, `blobs` — UNMODIFIED by slice 2); `StoredMsg(msgId: String, kind: String, identityPub: String, payload: Map<String, Any?>)`.
- Produces (all pure, JVM-testable):
  - `LocalApi.DmMsg(msgId: String, fromMe: Boolean, createdAt: Double, expiresAt: Double?, text: String?, blobs: List<String>, undecryptable: Boolean, storyRef: Map<*, *>?, partner: String)` (companion-nested data class).
  - `LocalApi.ConvRow(identityPub: String, name: String, lastText: String?, lastFromMe: Boolean?, lastAt: Double?, count: Int)` (companion-nested data class).
  - `LocalApi.extractDmMsgs(rawDms: List<StoredMsg>, decryptedById: Map<String, DecryptPass.Decrypted>, ownIdentityPub: String): List<DmMsg>`
  - `LocalApi.threadFor(all: List<DmMsg>, partner: String): List<DmMsg>`
  - `LocalApi.conversationsFrom(all: List<DmMsg>, names: Map<String, String>, ownIdentityPub: String): List<ConvRow>`

- [ ] **Step 1: Write the failing grouping tests**

Add to `LocalApiTest.kt` (append inside the existing `class LocalApiTest`). These build raw `StoredMsg` DMs and a `msgId->Decrypted` join map directly — no Android store needed.

```kotlin
    // ---- slice 2 (vp2) Task 1: pure DM grouping ----

    // A raw stored DM: sender=identityPub, payload carries plaintext outer
    // fields (to / created_at / expires_at / story_ref) present regardless of
    // whether THIS device can decrypt the body.
    private fun rawDm(
        msgId: String, sender: String, to: String, createdAt: Double,
        expiresAt: Double? = null, storyRef: Map<String, Any?>? = null,
    ): StoredMsg {
        val p = HashMap<String, Any?>()
        p["to"] = to; p["created_at"] = createdAt
        if (expiresAt != null) p["expires_at"] = expiresAt
        if (storyRef != null) p["story_ref"] = storyRef
        return StoredMsg(msgId, "dm", sender, p)
    }

    // A decrypted-join entry for a DM (only msgId/kind/text/blobs are read by
    // the grouping join; the rest are incidental constructor args).
    private fun decDm(msgId: String, text: String, blobs: List<String> = emptyList()) =
        DecryptPass.Decrypted(
            msgId = msgId, kind = "dm", author = "x", text = text, createdAt = 0.0,
            blobs = blobs, thumbs = emptyList(), media = "photo", poster = null,
            storyRefMediaHash = null)

    @Test fun extractDmMsgsDerivesPartnerAndFromMe() {
        val raw = listOf(
            rawDm("m1", sender = "own", to = "cara", createdAt = 10.0),   // I sent
            rawDm("m2", sender = "cara", to = "own", createdAt = 20.0))   // I received
        val dec = mapOf("m1" to decDm("m1", "hi"), "m2" to decDm("m2", "yo"))
        val out = LocalApi.extractDmMsgs(raw, dec, ownIdentityPub = "own")
        val m1 = out.first { it.msgId == "m1" }
        val m2 = out.first { it.msgId == "m2" }
        assertEquals("cara", m1.partner); assertTrue(m1.fromMe)
        assertEquals("cara", m2.partner); assertFalse(m2.fromMe)
    }

    @Test fun extractDmMsgsKeepsUndecryptableRow() {
        val raw = listOf(rawDm("m1", sender = "cara", to = "own", createdAt = 10.0))
        val out = LocalApi.extractDmMsgs(raw, emptyMap(), ownIdentityPub = "own")
        assertEquals(1, out.size)
        assertTrue(out[0].undecryptable)
        assertNull(out[0].text)
        assertTrue(out[0].blobs.isEmpty())
    }

    @Test fun extractDmMsgsOrdersByCreatedAtAscStable() {
        val raw = listOf(
            rawDm("late", sender = "own", to = "cara", createdAt = 30.0),
            rawDm("tieA", sender = "own", to = "cara", createdAt = 10.0),
            rawDm("tieB", sender = "cara", to = "own", createdAt = 10.0),
            rawDm("mid", sender = "cara", to = "own", createdAt = 20.0))
        val dec = raw.associate { it.msgId to decDm(it.msgId, "t") }
        val out = LocalApi.extractDmMsgs(raw, dec, ownIdentityPub = "own")
        // created_at asc; the two created_at==10 ties keep input (scan) order.
        assertEquals(listOf("tieA", "tieB", "mid", "late"), out.map { it.msgId })
    }

    @Test fun conversationsFromPicksNewestAsLastAndCountsUndecryptable() {
        val raw = listOf(
            rawDm("m1", sender = "own", to = "cara", createdAt = 10.0),
            rawDm("m2", sender = "cara", to = "own", createdAt = 30.0),   // newest, undecryptable
            rawDm("m3", sender = "cara", to = "own", createdAt = 20.0))
        val dec = mapOf("m1" to decDm("m1", "hi"), "m3" to decDm("m3", "middle"))
        val msgs = LocalApi.extractDmMsgs(raw, dec, ownIdentityPub = "own")
        val rows = LocalApi.conversationsFrom(msgs, mapOf("cara" to "Cara"), ownIdentityPub = "own")
        assertEquals(1, rows.size)
        val c = rows[0]
        assertEquals("cara", c.identityPub)
        assertEquals("Cara", c.name)
        assertEquals(3, c.count)                 // includes the undecryptable m2
        assertNull(c.lastText)                   // newest (m2) is undecryptable -> null text
        assertFalse(c.lastFromMe!!)              // m2 was received
        assertEquals(30.0, c.lastAt!!, 0.0)      // newest created_at
    }

    @Test fun conversationsFromSortsByLastAtDesc() {
        val raw = listOf(
            rawDm("a1", sender = "own", to = "alice", createdAt = 5.0),
            rawDm("b1", sender = "own", to = "bob", createdAt = 50.0))
        val dec = raw.associate { it.msgId to decDm(it.msgId, "t") }
        val msgs = LocalApi.extractDmMsgs(raw, dec, ownIdentityPub = "own")
        val rows = LocalApi.conversationsFrom(msgs, emptyMap(), ownIdentityPub = "own")
        assertEquals(listOf("bob", "alice"), rows.map { it.identityPub })  // bob newer -> first
        // no profile name -> first-8 fallback (identity strings here are short)
        assertEquals("bob", rows[0].name)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run (from `android_tor_spike/app/android`): `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.LocalApiTest"`
Expected: FAIL / compile error — `LocalApi.DmMsg`, `LocalApi.ConvRow`, `LocalApi.extractDmMsgs`, `LocalApi.threadFor`, `LocalApi.conversationsFrom` are unresolved.

- [ ] **Step 3: Add the pure DM grouping builders to `LocalApi.kt`**

In `LocalApi.kt`, inside the `companion object { … }` (place near the other pure builders, e.g. just above `postKeys`), add the two data classes and the three builders:

```kotlin
        // vp2 slice 2: one DM thread message, built by joining a RAW stored DM
        // (StoredMsg, which survives even when this device cannot decrypt it)
        // with the msgId->Decrypted map from DecryptPass.run. `undecryptable`
        // is true exactly when the raw DM had no Decrypted entry -- reproducing
        // hearth dm_thread's one-entry-per-stored-row-including-undecryptable
        // behavior. storyRef rides the plaintext outer payload, so it is
        // present even for an undecryptable row (hearth messages.py:139-150).
        data class DmMsg(
            val msgId: String, val fromMe: Boolean, val createdAt: Double, val expiresAt: Double?,
            val text: String?, val blobs: List<String>, val undecryptable: Boolean,
            val storyRef: Map<*, *>?, val partner: String)

        // vp2: one conversation-list summary row (hearth node.conversations()).
        data class ConvRow(
            val identityPub: String, val name: String, val lastText: String?,
            val lastFromMe: Boolean?, val lastAt: Double?, val count: Int)

        // vp2: build the flat DM message list from RAW DMs joined with the
        // decrypted map. Partner derivation mirrors hearth dm_conversations:
        // sender = StoredMsg.identityPub, recipient = payload["to"]; the partner
        // is whichever of {sender, recipient} is NOT own. A DM missing a
        // created_at or a `to` in its plaintext payload is malformed (a valid
        // signed DM always carries both) and is skipped. Stable sort by
        // created_at ASC: same-created_at ties keep allMessages()' scan order,
        // approximating hearth's `created_at ASC, rowid ASC` tiebreak (rowid is
        // not exposed on StoredMsg -- flagged as a follow-up, not fixed here).
        fun extractDmMsgs(
            rawDms: List<StoredMsg>,
            decryptedById: Map<String, DecryptPass.Decrypted>,
            ownIdentityPub: String,
        ): List<DmMsg> =
            rawDms.mapNotNull { m ->
                val createdAt = (m.payload["created_at"] as? Number)?.toDouble() ?: return@mapNotNull null
                val to = m.payload["to"] as? String ?: return@mapNotNull null
                val sender = m.identityPub
                val fromMe = sender == ownIdentityPub
                val partner = if (fromMe) to else sender
                val d = decryptedById[m.msgId]
                DmMsg(
                    msgId = m.msgId,
                    fromMe = fromMe,
                    createdAt = createdAt,
                    expiresAt = (m.payload["expires_at"] as? Number)?.toDouble(),
                    text = d?.text,
                    blobs = d?.blobs ?: emptyList(),
                    undecryptable = d == null,
                    storyRef = m.payload["story_ref"] as? Map<*, *>,
                    partner = partner)
            }.sortedBy { it.createdAt }

        // vp2: the ascending thread for one partner. extractDmMsgs already
        // collapsed both directions onto a single `partner` per message and
        // sorted ASC, so this is a stable filter preserving that order.
        fun threadFor(all: List<DmMsg>, partner: String): List<DmMsg> =
            all.filter { it.partner == partner }

        // vp2: conversation summary rows (hearth node.conversations()). Group by
        // partner (only partners WITH history appear -- friends are unioned in
        // client-side by renderConversations, NOT added here). `last` = the
        // newest message (the ASC list's last element); last_text/last_from_me/
        // last_at come from it, including when it is undecryptable (text null,
        // but from_me/created_at still populated -- matching hearth's unread
        // semantics). count includes undecryptable rows. Sorted by last_at DESC
        // (a null/empty last_at sorts as 0); the stable sort keeps
        // first-appearance order among equal last_at.
        fun conversationsFrom(
            all: List<DmMsg>, names: Map<String, String>, ownIdentityPub: String,
        ): List<ConvRow> {
            val byPartner = linkedMapOf<String, MutableList<DmMsg>>()
            for (m in all) byPartner.getOrPut(m.partner) { mutableListOf() }.add(m)
            return byPartner.map { (partner, msgs) ->
                val last = msgs.last()
                ConvRow(
                    identityPub = partner,
                    name = names[partner] ?: partner.take(8),
                    lastText = last.text,
                    lastFromMe = last.fromMe,
                    lastAt = last.createdAt,
                    count = msgs.size)
            }.sortedByDescending { it.lastAt ?: 0.0 }
        }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.LocalApiTest"`
Expected: PASS (the five new grouping tests). Then run the whole suite `./gradlew :tor-manager:testDebugUnitTest` — expect the full existing JVM suite still green (slice 2 does not touch `DecryptPass.kt`, so `DecryptPassTest` needs no change).

- [ ] **Step 5: Commit**

```bash
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalApi.kt \
        android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/LocalApiTest.kt
git commit -m "feat(vp2): pure dm grouping builders reproducing hearth conversations and thread"
```

---

## Task 2: conversations + dm-thread routes

**Files:**
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalApi.kt`
- Test: `android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/LocalApiTest.kt`

**Interfaces:**
- Consumes: Task-1 `DmMsg`/`ConvRow`, `extractDmMsgs`/`threadFor`/`conversationsFrom`; `DecryptPass.run(store, phoneDevicePub, encPrivHex, ownIdentityPub): Result` (with `Result.feed: List<Decrypted>`, `Result.keys: Map<String, ByteArray>`); `EncKeys.getOrCreate(store): Pair<String,String>`; `SqliteSyncStore(ctx)` with `allMessages(): List<StoredMsg>`, `profileNames(): Map<String, String>`; `fixtureOrNull()`.
- Produces (companion, pure): `LocalApi.conversationsJson(rows: List<ConvRow>): String`; `LocalApi.dmThreadJson(msgs: List<DmMsg>): String`. Instance methods `conversations(): String`, `dmThread(identityPub: String): String`, and a private `loadDms()` helper that warms `dmKeysCache`. Two new routes in `handle()`.

- [ ] **Step 1: Write the failing golden-shape tests**

Add to `LocalApiTest.kt`:

```kotlin
    // ---- slice 2 (vp2) Task 2: golden JSON shapes ----

    @Test fun conversationsJsonGoldenShape() {
        val rows = listOf(
            LocalApi.ConvRow("cara", "Cara", lastText = null, lastFromMe = false, lastAt = 30.0, count = 3),
            LocalApi.ConvRow("bob", "bob01234", lastText = "hey", lastFromMe = true, lastAt = 10.0, count = 1))
        val arr = org.json.JSONArray(LocalApi.conversationsJson(rows))
        assertEquals(2, arr.length())
        val c0 = arr.getJSONObject(0)
        assertEquals(setOf("identity_pub", "name", "last_text", "last_from_me", "last_at", "count"),
            c0.keys().asSequence().toSet())
        assertEquals("cara", c0.getString("identity_pub"))
        assertEquals("Cara", c0.getString("name"))
        assertTrue(c0.isNull("last_text"))            // undecryptable newest -> null
        assertFalse(c0.getBoolean("last_from_me"))
        assertEquals(30.0, c0.getDouble("last_at"), 0.0)
        assertEquals(3, c0.getInt("count"))
        val c1 = arr.getJSONObject(1)
        assertEquals("hey", c1.getString("last_text"))
        assertTrue(c1.getBoolean("last_from_me"))
    }

    @Test fun dmThreadJsonGoldenShape() {
        val msgs = listOf(
            LocalApi.DmMsg(
                msgId = "m1", fromMe = true, createdAt = 10.0, expiresAt = null,
                text = "hi", blobs = listOf("b1"), undecryptable = false,
                storyRef = null, partner = "cara"),
            LocalApi.DmMsg(
                msgId = "m2", fromMe = false, createdAt = 20.0, expiresAt = 99.0,
                text = null, blobs = emptyList(), undecryptable = true,
                storyRef = mapOf("story_id" to "s9", "media_hash" to "ab12"),
                partner = "cara"))
        val arr = org.json.JSONArray(LocalApi.dmThreadJson(msgs))
        assertEquals(2, arr.length())
        val a = arr.getJSONObject(0)
        assertEquals(setOf("msg_id", "from_me", "created_at", "expires_at", "text",
            "blobs", "undecryptable", "story_ref"), a.keys().asSequence().toSet())
        assertEquals("m1", a.getString("msg_id"))
        assertTrue(a.getBoolean("from_me"))
        assertEquals(10.0, a.getDouble("created_at"), 0.0)
        assertTrue(a.isNull("expires_at"))
        assertEquals("hi", a.getString("text"))
        assertEquals("b1", a.getJSONArray("blobs").getString(0))
        assertFalse(a.getBoolean("undecryptable"))
        assertTrue(a.isNull("story_ref"))
        // undecryptable row: text null, blobs empty array (not null), story_ref
        // passed through as a dict from the plaintext outer payload.
        val b = arr.getJSONObject(1)
        assertTrue(b.getBoolean("undecryptable"))
        assertTrue(b.isNull("text"))
        assertEquals(0, b.getJSONArray("blobs").length())
        assertEquals(99.0, b.getDouble("expires_at"), 0.0)
        val sr = b.getJSONObject("story_ref")
        assertEquals("s9", sr.getString("story_id"))
        assertEquals("ab12", sr.getString("media_hash"))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.LocalApiTest"`
Expected: FAIL / compile error — `LocalApi.conversationsJson` and `LocalApi.dmThreadJson` are unresolved.

- [ ] **Step 3: Confirm the story_ref passthrough shape, then write the pure JSON marshalers**

CONFIRM at implementation: hearth's dm-thread emits `msg.payload.get("story_ref")` — the RAW outer-payload dict (`{story_id, media_hash}`), passed straight through, and `app.js` reads only `m.story_ref.media_hash` (reference §1, §2). `SqliteSyncStore`'s `jsonToMap` hands back a Kotlin `Map` for that value, so `payload["story_ref"] as? Map<*, *>` is the right read (done in Task 1's `extractDmMsgs`) and marshaling it key-for-key reproduces hearth exactly. If a future hearth build nests non-string values under `story_ref`, the key-for-key copy below still passes them through unchanged.

In `LocalApi.kt` companion, add:

```kotlin
        // vp2: hearth node.conversations() row list. last_text/last_from_me/
        // last_at are null only for an empty thread -- which never occurs here
        // (a partner is listed only if it has >=1 message), but the null-coalesce
        // keeps the shape faithful to hearth's field types.
        fun conversationsJson(rows: List<ConvRow>): String {
            val arr = JSONArray()
            for (c in rows) arr.put(JSONObject()
                .put("identity_pub", c.identityPub)
                .put("name", c.name)
                .put("last_text", c.lastText ?: JSONObject.NULL)
                .put("last_from_me", (c.lastFromMe as Boolean?) ?: JSONObject.NULL)
                .put("last_at", c.lastAt ?: JSONObject.NULL)
                .put("count", c.count))
            return arr.toString()
        }

        // vp2: hearth node.dm_thread() flat list (NOT wrapped in {messages,
        // partner}). blobs is always an array ([] when undecryptable, never
        // null). story_ref passes the plaintext outer-payload dict through
        // key-for-key (or null), matching hearth's msg.payload.get("story_ref").
        fun dmThreadJson(msgs: List<DmMsg>): String {
            val arr = JSONArray()
            for (m in msgs) {
                val blobs = JSONArray(); m.blobs.forEach { blobs.put(it) }
                val storyRef: Any = m.storyRef?.let { sr ->
                    JSONObject().also { o -> for ((k, v) in sr) o.put(k.toString(), v ?: JSONObject.NULL) }
                } ?: JSONObject.NULL
                arr.put(JSONObject()
                    .put("msg_id", m.msgId)
                    .put("from_me", m.fromMe)
                    .put("created_at", m.createdAt)
                    .put("expires_at", m.expiresAt ?: JSONObject.NULL)
                    .put("text", m.text ?: JSONObject.NULL)
                    .put("blobs", blobs)
                    .put("undecryptable", m.undecryptable)
                    .put("story_ref", storyRef))
            }
            return arr.toString()
        }
```

- [ ] **Step 4: Add the `dmKeysCache` field and the instance methods, and wire the routes**

Just below the existing `@Volatile private var keysCache: Map<String, ByteArray> = emptyMap()` (LocalApi.kt:20), add:

```kotlin
    // vp2: msgId -> content key for DM blobs, warmed by loadDms() (the pass the
    // conversations/dm-thread routes already run) and reused by dmBlob() so a
    // DM image request does NOT re-run a full DecryptPass.run. Separate from
    // keysCache (posts) so the two blob routes stay kind-isolated. In-memory
    // only; never persisted (decrypt-on-read).
    @Volatile private var dmKeysCache: Map<String, ByteArray> = emptyMap()
```

Add the instance methods (place them near `feed()`):

```kotlin
    // vp2: one decrypt pass feeding BOTH the conversations and dm-thread routes.
    // Warms dmKeysCache. Returns null only when the fixture is missing (the
    // routes then serve an empty list, keeping the load-bearing 2xx contract:
    // app.js's j() throws on any non-2xx and refresh() awaits the conversations
    // route every tick). rawDms carries EVERY stored DM (incl. undecryptable);
    // decryptedById is the subset this device could decrypt.
    private data class DmLoad(val msgs: List<DmMsg>, val names: Map<String, String>, val own: String)

    private fun loadDms(): DmLoad? {
        val fx = fixtureOrNull() ?: return null
        val store = SqliteSyncStore(ctx)
        val (priv, _) = EncKeys.getOrCreate(store)
        val own = fx.cert.identity_pub
        val res = DecryptPass.run(store, fx.device_pub, priv, own)
        dmKeysCache = dmKeys(res.feed, res.keys)                 // warm DM blob-key cache
        val decryptedById = res.feed.filter { it.kind == "dm" }.associateBy { it.msgId }
        val rawDms = store.allMessages().filter { it.kind == "dm" }
        val msgs = extractDmMsgs(rawDms, decryptedById, own)
        return DmLoad(msgs, store.profileNames(), own)
    }

    private fun conversations(): String {
        val d = loadDms() ?: return "[]"
        return conversationsJson(conversationsFrom(d.msgs, d.names, d.own))
    }

    private fun dmThread(identityPub: String): String {
        val d = loadDms() ?: return "[]"
        return dmThreadJson(threadFor(d.msgs, identityPub))
    }
```

In `handle()`'s `when { … }`, add two branches (place them after `path == "/api/kreds" -> …` and before the blob branches). Use a `//` line comment, never a path-shaped `/** */` doc:

```kotlin
            path == "/api/conversations" -> json(conversations())
            path.startsWith("/api/dm/") -> {
                val id = path.removePrefix("/api/dm/")
                if (id.isEmpty() || id.contains("/")) notFound() else json(dmThread(id))
            }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.LocalApiTest"`
Expected: PASS (the two golden-shape tests). Then `./gradlew :tor-manager:testDebugUnitTest` — full suite green.

- [ ] **Step 6: Commit**

```bash
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalApi.kt \
        android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/LocalApiTest.kt
git commit -m "feat(vp2): serve conversations and dm-thread in hearth snake_case shapes"
```

---

## Task 3: dm-blob route

**Files:**
- Modify: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalApi.kt`
- Test: `android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/LocalApiTest.kt`

**Interfaces:**
- Consumes: Task-2 `dmKeysCache`; `DecryptPass.run(...).feed`/`.keys`; `EncKeys.getOrCreate(store)`; `SqliteSyncStore.getBlob(hash): ByteArray?`; `KotlinBlobCrypt.decryptBlob(contentKey: ByteArray, cipher: ByteArray): ByteArray?`; `LocalApi.sniff(...)`, `notFound()`, `fixtureOrNull()`.
- Produces (companion, pure): `LocalApi.dmKeys(feed: List<DecryptPass.Decrypted>, keys: Map<String, ByteArray>): Map<String, ByteArray>`. Instance methods `dmBlob(msgId: String, hash: String): HttpResponse`, `dmMediaResponse(bytes: ByteArray): HttpResponse`. One new route in `handle()`.

- [ ] **Step 1: Write the failing `dmKeys` test**

Add to `LocalApiTest.kt`:

```kotlin
    // ---- slice 2 (vp2) Task 3: dm blob key filtering (kind gate) ----

    @Test fun dmKeysExcludesPostKeys() {
        val feed = listOf(
            DecryptPass.Decrypted("post1", "post", "a", "t", 1.0, listOf("pb"), emptyList(),
                "photo", null, null),
            DecryptPass.Decrypted("dm1", "dm", "a", "t", 2.0, listOf("db"), emptyList(),
                "photo", null, null))
        val keys = mapOf("post1" to byteArrayOf(1), "dm1" to byteArrayOf(2))
        val out = LocalApi.dmKeys(feed, keys)
        // only the DM's key survives -- a post's msgId can never resolve a key
        // via the dm-blob route (the kind gate, mirroring hearth's dm_blob
        // `if msg.kind != KIND_DM: return None`).
        assertEquals(setOf("dm1"), out.keys)
        assertArrayEquals(byteArrayOf(2), out["dm1"])
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.LocalApiTest"`
Expected: FAIL / compile error — `LocalApi.dmKeys` is unresolved.

- [ ] **Step 3: Add `dmBlob` + `dmMediaResponse`, and wire the route**

**[AMENDED post-Task-2 review]: `dmKeys` and the `@Volatile dmKeysCache` field were ALREADY added in Task 2 (its `loadDms()` warms the cache, so they had to exist to compile). Do NOT re-add `dmKeys` or `dmKeysCache` — they already exist in `LocalApi.kt`. Verify they're present (the `dmKeys` companion below is shown only so you know its exact signature to consume; the `dmBlob` reader uses the existing `dmKeysCache`). Skip straight to adding `dmBlob`/`dmMediaResponse`/the route + the `dmKeysExcludesPostKeys` test.**

The already-present `dmKeys` (from Task 2, for reference — do NOT re-add):

```kotlin
        // vp2: hearth dm_blob (node.py:2946-2954) serves ONLY KIND_DM blobs,
        // even though the content-key machinery is shared with posts. Result.
        // keys is keyed by msgId across BOTH kinds -- this narrows it to
        // dm-authored keys only, so a post's msgId can never resolve a key via
        // the dm-blob route (the reverse of postKeys' post-only narrowing).
        fun dmKeys(feed: List<DecryptPass.Decrypted>, keys: Map<String, ByteArray>): Map<String, ByteArray> =
            feed.filter { it.kind == "dm" }.mapNotNull { d -> keys[d.msgId]?.let { d.msgId to it } }.toMap()
```

Add the instance methods (place next to `postBlob`):

```kotlin
    // vp2: content-key-decrypted DM blob bytes ONLY, streamed decrypt-on-read.
    // Mirrors postBlob's cache+fallback but keyed off dmKeysCache (DM-only), so
    // a post's msgId is never decryptable here (kind gate). The response OMITS
    // Cache-Control -- hearth's dm_blob route sets NONE (contrast post_blob/
    // blob, which set immutable) -- so it uses dmMediaResponse, not
    // mediaResponse (which hardcodes the immutable Cache-Control).
    private fun dmBlob(msgId: String, hash: String): HttpResponse {
        val store = SqliteSyncStore(ctx)
        var key = dmKeysCache[msgId]
        if (key == null) {
            val fx = fixtureOrNull() ?: return notFound()
            val (priv, _) = EncKeys.getOrCreate(store)
            val res = DecryptPass.run(store, fx.device_pub, priv, fx.cert.identity_pub)
            val dms = dmKeys(res.feed, res.keys)
            dmKeysCache = dms
            key = dms[msgId] ?: return notFound()
        }
        val cipher = store.getBlob(hash) ?: return notFound()
        val plain = KotlinBlobCrypt.decryptBlob(key, cipher) ?: return notFound()
        return dmMediaResponse(plain)
    }

    // vp2: DM blob response headers -- Content-Type (sniffed) + nosniff, and
    // deliberately NO Cache-Control (hearth dm_blob sets none; do not reuse
    // mediaResponse's immutable header for a DM).
    private fun dmMediaResponse(bytes: ByteArray) = HttpResponse(200, mapOf(
        "Content-Type" to sniff(bytes),
        "X-Content-Type-Options" to "nosniff"), bytes)
```

In `handle()`'s `when { … }`, add a branch parsed exactly like `/api/post-blob/` (a 2-segment `msg_id`/`h`). Put it before the `/api/dm/` branch from Task 2 so the longer prefix wins clearly:

```kotlin
            path.startsWith("/api/dm-blob/") -> {
                val seg = path.removePrefix("/api/dm-blob/").split("/")
                if (seg.size != 2 || seg[0].isEmpty() || seg[1].isEmpty()) notFound() else dmBlob(seg[0], seg[1])
            }
```

(`/api/dm-blob/…` does not start with `/api/dm/` — the char after `dm` is `-`, not `/` — so the two DM branches never collide; ordering is for readability.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :tor-manager:testDebugUnitTest --tests "expo.modules.tormanager.LocalApiTest"`
Expected: PASS (the `dmKeys` test). Then `./gradlew :tor-manager:testDebugUnitTest` — full suite green. (The decrypt + kind-gate-404 + no-Cache-Control paths need a real store + WebView and are exercised on-device in Task 5, mirroring how slice 1 left `postBlob`'s live decrypt to its on-device task.)

- [ ] **Step 5: Commit**

```bash
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/LocalApi.kt \
        android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/LocalApiTest.kt
git commit -m "feat(vp2): serve dm-blob media kind-gated with no cache-control"
```

---

## Task 4: shared-UI (mobile Messages tab + composer seam)

**Files:**
- Modify: `hearth/web/index.html` (one line)
- Modify: `hearth/web/style.css` (one selector)
- Test: `android_tor_spike/app/test/web-readonly-seam.test.ts`

**Interfaces:**
- Consumes: the existing tabbar handler (`app.js:4847-4848`) and `goView`/`setView` (`app.js:4707-4723`); the existing `body.readonly` seam (`style.css:1211-1223`), already driven by `/api/state`'s `readonly:true` (slice 1).
- Produces: a `data-tab="messages"` button in `.tabbar-mobile`; `#dm-compose` hidden under `body.readonly`; an extended vitest guard.

- [ ] **Step 1: Confirm the tabbar handler routes `data-tab="messages"`**

Read `hearth/web/app.js:4707-4723` (the `setView`/`goView` functions) and `app.js:4845-4848` (the nav + tabbar `onclick` wiring). CONFIRM: the tabbar handler is `b.dataset.tab === "me" ? openMe() : goView(b.dataset.tab)`, so a `data-tab="messages"` button calls `goView("messages")` → `setView("messages")` → `loadConversations()` + (no `CURRENT_DM`) `dmPlaceholder()`. No app.js change is needed. If the handler differs from this in the current bundle, STOP and reconcile (a bespoke handler branch for "messages" would be the fallback) before editing index.html.

- [ ] **Step 2: Write the failing vitest guard extension**

In `android_tor_spike/app/test/web-readonly-seam.test.ts`, add `"#dm-compose"` to the `HIDDEN_SELECTORS` array (so the existing "hides every known write affordance" loop covers it) and to `LOAD_BEARING_SELECTORS` (so the "pairs with display:none" test also covers it):

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
  "#dm-compose",            // vp2: the DM composer bar (Photo + textarea + Send)
];

const LOAD_BEARING_SELECTORS = [".composer", ".comment-composer", ".rx-open", ".comment-x", "#dm-compose"];
```

Then add a new `it(...)` inside the `describe("vp1 read-only seam", …)` block asserting the mobile Messages tab exists:

```ts
  it("index.html mobile tab bar has a Messages entry (vp2)", () => {
    const html = web("index.html");
    expect(html).toMatch(/<button[^>]*data-tab=["']messages["'][^>]*>/);
  });
```

- [ ] **Step 3: Run the guard to verify it fails**

Run (from `android_tor_spike/app`): `npx vitest run test/web-readonly-seam.test.ts`
Expected: FAIL — `#dm-compose` is not yet in the `body.readonly` block, and no `data-tab="messages"` button exists.

- [ ] **Step 4: Add the mobile Messages tab button**

In `hearth/web/index.html`, change the `.tabbar-mobile` block (currently `index.html:365-369`) to insert a Messages button between Journal and Me:

```html
<nav class="tabbar-mobile">
  <button data-tab="circle">Circle</button>
  <button data-tab="journal">Journal</button>
  <button data-tab="messages">Messages</button>
  <button data-tab="me">Me</button>
</nav>
```

(`style.css:828` `.tabbar-mobile button { flex: 1 }` auto-sizes the 4th button; no CSS change needed for the tab.)

- [ ] **Step 5: Add `#dm-compose` to the read-only seam**

In `hearth/web/style.css`, in the `body.readonly` selector list (currently ending `body.readonly #profile-addfriend {` at `style.css:1221`), add one selector before the closing `{`:

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
body.readonly #dm-compose {
  display: none !important;
}
```

(`#dm-compose` is the whole `<form>` — hiding it removes Photo/textarea/Send together, leaving `#thread` and `#conversations` fully readable.)

- [ ] **Step 6: Run the guard to verify it passes**

Run (from `android_tor_spike/app`): `npx vitest run test/web-readonly-seam.test.ts`
Expected: PASS — all read-only-seam tests plus the new Messages-tab assertion.

- [ ] **Step 7: Confirm desktop is unaffected**

By inspection: a desktop node's `/api/state` never sets `readonly`, so `body.readonly` is never applied and `#dm-compose` renders normally there; the extra tabbar button is inside `.tabbar-mobile`, which is `display:none` above 760px (`style.css:824`), so desktop chrome is unchanged. No desktop test change required.

- [ ] **Step 8: Commit**

```bash
git add hearth/web/index.html hearth/web/style.css android_tor_spike/app/test/web-readonly-seam.test.ts
git commit -m "feat(vp2): mobile messages tab and read-only seam hides the dm composer"
```

---

## Task 5: on-device integration + report + PAUSE

**Files:**
- Create: `android_tor_spike/BRICK_VP2_REPORT.md`

**Interfaces:**
- Consumes: everything from Tasks 1–4 (built into one RELEASE apk; the slice-1 `copyHearthWeb` task re-syncs the `hearth/web` edits into the APK).
- Produces: the on-device proof record + follow-up tickets; a review PAUSE.

- [ ] **Step 1: Full desk-gate sweep**

Run and record outputs:
- `./gradlew :tor-manager:testDebugUnitTest` (from `android_tor_spike/app/android`, with `JAVA_HOME`/`ANDROID_HOME` set per Global Constraints) — expect the whole JVM suite green incl. the new `LocalApiTest` grouping/golden/`dmKeys` tests.
- `npx tsc --noEmit` (from `android_tor_spike/app`) — expect 0 new errors (pre-existing `tools/` node-type errors may remain).
- `npx vitest run test/web-readonly-seam.test.ts` (from `android_tor_spike/app`) — expect green.

- [ ] **Step 2: Build + install the RELEASE apk**

From `android_tor_spike/app/android`: `./gradlew :app:assembleRelease`. Confirm `copyHearthWeb` ran (the index.html/style.css edits land in the APK's `assets/www/`). Install `app/build/outputs/apk/release/app-release.apk` on the G20 (`adb install -r …`). RELEASE, not debug (field lesson: a debug build yields "Unable to load script").

- [ ] **Step 3: Run the on-device DoD (human-driven)**

Preconditions (field lessons): the desktop peer must be reachable over Tor — run the desktop node with `serve --tor` (a bare `hearth app` has Tor OFF and the sync EOFs; a locked node refuses sync as a bare EOF). From the desktop, send the phone a DM thread with content: at least one message the desktop sends TO the phone's identity, at least one the phone authored back (so both bubble sides render), a DM photo, and — if reproducible — a DM the phone cannot decrypt (to show the placeholder). Let the phone sync (background or reopen).

DoD checklist — tick each:
- [ ] The mobile tab bar shows a Messages entry, and tapping it opens the Messages view (proves the new `data-tab="messages"` button routes through `goView`).
- [ ] The conversation list renders one row per partner with a DM history — name + last-message preview; no crash/blank (proves the conversations route returns 2xx and the whole refresh chain still completes).
- [ ] Opening a conversation renders the thread read-only: own DMs right-aligned/accent-tinted, received DMs left — bubbles in chronological (oldest-top) order.
- [ ] A DM photo renders (served via the dm-blob route; decrypt-on-read).
- [ ] If an undecryptable DM was reproduced: a "(cannot decrypt on this device)" placeholder bubble appears in-line, and the conversation's count/preview still reflect it.
- [ ] The DM composer is hidden: no Photo/textarea/Send bar under the thread (read content stays visible).
- [ ] No token/CSP errors in `adb logcat` while browsing conversations and a thread.

- [ ] **Step 4: Write `BRICK_VP2_REPORT.md`**

Mirror `BRICK_VP1_REPORT.md`'s structure. Include: (a) a desk-gates table (each command + green/red); (b) the on-device DoD checklist above with pass/fail + notes; (c) run gotchas (RELEASE apk only; desktop `serve --tor`; a locked node refuses sync as bare EOF; send fresh DM content first — both directions + a photo); (d) the honest boundary (read-only Messages only; conversation list + thread render; the composer is hidden and sending is out of scope; the ≤720px `.dm-shell` is the stock stacked two-column layout, NOT a single-pane-with-back redesign; no mobile unread badge); (e) follow-up tickets:
  - **Mobile Messages single-pane-with-back UX (top follow-up)** — the stock `.dm-shell` stacks the conversation list above the thread at ≤720px with no back navigation; design a proper phone list↔thread flow in a later slice.
  - **rowid tiebreak** — slice 2 orders the thread by a stable `sortedBy { createdAt }`, approximating hearth's `created_at ASC, rowid ASC`; a faithful port would surface `rowid` on `StoredMsg` (`SqliteSyncStore.allMessages()` has no `ORDER BY`) so same-`created_at` DMs tie-break by arrival exactly as hearth does.
  - **Mobile unread badge** — `#nav-msg-badge` is `.navlinks`-scoped/desktop-only; a mobile Messages-tab unread indicator is unbuilt.
  - **Sending DMs (outbound)** — the composer is hidden and the route surface is GET-only; a future outbound slice re-enables the composer and adds the write path.
  - **DM avatars** — the conversation list uses a text-initial only (hearth's own behavior); no avatar-blob accessor exists yet on the phone store.

- [ ] **Step 5: Commit + PAUSE**

```bash
git add android_tor_spike/BRICK_VP2_REPORT.md
git commit -m "docs(vp2): on-device proof record and follow-up tickets for slice 2"
```

Then PAUSE for human review before starting slice 3 (Me/wall + `/api/profile`). Do not proceed past this checkpoint without sign-off. Whether to merge the slice-2 branch is the human's call (mirrors the B.2-era "merge is August's call" pattern).

---

## Self-Review

**1. Spec coverage.** Every slice-2 scope item maps to a task:
- Conversations route + exact flat shape + last_at-desc sort + history-only partners → Task 1 (grouping/sort/last-selection) + Task 2 (JSON + route + 2xx contract).
- Dm-thread route + exact flat shape (not `{messages,partner}`) + created_at-asc order + undecryptable placeholder rows + count fidelity → Task 1 (raw-DM extraction join, undecryptable, ordering) + Task 2 (JSON + route).
- Dm-blob route + sniff Content-Type + nosniff + NO Cache-Control + DM kind-gate → Task 3 (`dmKeys` filter, `dmBlob`, `dmMediaResponse`, route).
- `DecryptPass.kt` untouched (pinned decision 3) — Task 1 derives the partner from the raw `StoredMsg.payload["to"]`, so no `Decrypted.to` field is added; no task modifies `DecryptPass`.
- Mobile Messages tab (pinned decision 7) → Task 4 Steps 1/4.
- Composer hidden via the read-only seam (pinned decision 8) → Task 4 Steps 5 + the vitest guard extension Step 2.
- Accept stock mobile layout, flag as follow-up (pinned decision 9) → Task 5 report follow-ups.
- On-device DoD + report + PAUSE → Task 5.
- NOT-in-slice-2 (sending DMs, `/api/profile`, the Me/wall view, a single-pane redesign) — correctly excluded; no task implements them; the composer route surface stays GET-only.

**2. Placeholder scan.** No "TBD"/"similar to Task N"/"add error handling". The three implementation-time confirmations are written read→confirm→implement with a fallback: the story_ref passthrough shape (Task 2 Step 3 — confirm hearth passes the raw dict, key-for-key copy is the fallback-safe port); the tabbar handler routing (Task 4 Step 1 — confirm `b.dataset.tab === "me" ? openMe() : goView(...)`, a bespoke "messages" branch is the fallback); `rowid` unavailability (Task 1 decision 5 + Task 5 follow-up — the stable-sort approximation is the accepted slice-2 behavior). Every code step shows complete Kotlin/TS/CSS/HTML — no path-shaped globs inside `/** */` blocks (endpoint references use prose or `//`).

**3. Type consistency.**
- `DecryptPass.Decrypted` — UNMODIFIED by slice 2; the DM extraction reads only its existing `msgId`/`kind`/`text`/`blobs` fields via the join map, and derives the partner from the raw `StoredMsg.payload["to"]`. No `Decrypted.to` field is added, so no `decryptOne`/`DecryptPassTest` change is entailed.
- `LocalApi.DmMsg(msgId, fromMe, createdAt, expiresAt, text, blobs, undecryptable, storyRef, partner)` — defined Task 1, constructed identically in Task 2's `dmThreadJsonGoldenShape` and consumed by `threadFor`/`dmThreadJson`.
- `LocalApi.ConvRow(identityPub, name, lastText, lastFromMe, lastAt, count)` — defined Task 1, constructed identically in Task 2's `conversationsJsonGoldenShape` and consumed by `conversationsFrom`/`conversationsJson`.
- `extractDmMsgs(rawDms, decryptedById, ownIdentityPub)` / `threadFor(all, partner)` / `conversationsFrom(all, names, ownIdentityPub)` — signatures match between Task 1 tests, Task 2's `loadDms()`, and the route methods.
- `dmKeys(feed, keys)` — defined Task 3, mirrors the existing `postKeys` signature exactly; `dmKeysCache` field (Task 2) is the cache both `loadDms()` (warm) and `dmBlob()` (fallback) write.
- `dmMediaResponse(bytes)` — distinct from `mediaResponse(bytes)` precisely by omitting Cache-Control (pinned decision 1/6).
- `HttpResponse(status, headers, body)`, `sniff(...)`, `notFound()`, `fixtureOrNull()`, `EncKeys.getOrCreate`, `DecryptPass.run(...).feed/.keys`, `SqliteSyncStore.allMessages()/profileNames()/getBlob()` — all reused from slice 1 with their established signatures.
- Route strings — `/api/conversations` (exact), `/api/dm/` prefix, `/api/dm-blob/` prefix — parsed with the same idioms slice 1 uses for `/api/post-blob/`/`/api/blob/`; the two DM prefixes do not collide.

No inconsistencies found.
