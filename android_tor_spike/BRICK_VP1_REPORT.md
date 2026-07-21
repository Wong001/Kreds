# Visual Parity — Slice 1 (WebView shell) — on-device report

**The first slice of the shared-UI visual-parity arc (Option B).** The phone
now renders hearth's **real desktop web UI** (`hearth/web`) in a full-screen
WebView, served entirely from a native 127.0.0.1 loopback server backed by a
**read-only `/api/*` shim** over the phone's own SQLite store (decrypt-on-read).
This slice covers the **journal (feed) view**: text posts, photos, video, the
stories strip, and responses (reactions + comments) — all via the desktop UI,
read-only. Messages and the Me/profile view are later slices.

Branch: `brick-vp1-webview-shell` (base `b340de3` off main).
Spec: `docs/superpowers/specs/2026-07-20-android-visual-parity-webview-shell-design.md`.
Plan: `docs/superpowers/plans/2026-07-20-android-visual-parity-webview-shell-slice1.md`.

## What it does

- **`LocalWebServer`** — a loopback-only (127.0.0.1) HTTP server (evolves the
  MediaServer pattern): cookie/query **session-token gate before any store
  access**, strict **CSP** on the shell, HTTP range support, fail-closed.
- **Bundled assets** — `hearth/web` (index.html/app.js/style.css/fonts/icons)
  is **Gradle-copied into the APK at build time** (single source of truth — the
  desktop UI, never duplicated into the RN tree), served from `assets/www/`.
- **`LocalApi`** — read-only `/api/bootstrap`, `/api/applock`, `/api/state`,
  `/api/feed`, `/api/stories`, `/api/blob/{h}`, `/api/post-blob/{msg_id}/{h}`,
  marshaled from `DecryptPass`/`SqliteSyncStore` into hearth's **exact
  snake_case JSON shapes** so `app.js` runs unchanged. Decrypt-on-read: bytes
  in-memory, never to disk. `/api/post-blob` gated to posts; `/api/stories`
  filtered to known identities; expired posts dropped — all matching hearth.
- **`WebShell.tsx`** — full-screen `react-native-webview` pointed at the
  loopback URL (cookies on for the `/api` session, nav gated to loopback),
  owns engine bootstrap (Tor + node + an initial sync on launch).
- **`body.readonly` seam** — the one small shared-UI change: `app.js` toggles
  `body.readonly` from `/api/state.readonly`; `style.css` hides the write
  affordances (composer, comment input, reaction-add picker, delete, story-add,
  self-profile doors) while keeping read content (count chips, comments) — the
  seam a future outbound slice flips off. **Desktop is unaffected** (its
  `/api/state` never sets `readonly`).
- **NDK r26.3 → r27.1** — RN 0.86's Fabric codegen (`std::format`) needs r27;
  react-native-webview is the first native View to hit it. The vendored
  `libtor.so` is a **prebuilt** binary (Tor AAR — not compiled by our NDK), so
  only the JNI bridge + RN native recompile; the Tor engine binary is unchanged.

## Desk gates (all GREEN — Claude, pre-August)

| Gate | Result |
|------|--------|
| `:tor-manager:testDebugUnitTest` (JVM, incl LocalWebServerTest + LocalApiTest golden shapes) | 142 tests, 0 fail |
| `npx vitest run` (incl the read-only-seam guard) | 24/24 |
| `npx tsc --noEmit` | 0 new (only pre-existing `tools/` node-type errors) |
| `assembleRelease` (under NDK r27.1) | BUILD SUCCESSFUL |
| Per-task reviews (server / assets+wiring / webview / feed / stories+blob / seam) | all APPROVED (3 review-caught fixes: responder conditional, post-blob kind gate + stories filter, comment-x seam) |
| Whole-branch review (opus) | **READY WITH FIXES** → expired-post filter applied → clean merge |
| All 13 commits (bodies + subjects) | trailer-clean, lowercase `feat/fix/docs(vp1)` |

**Security, desk-proven:** token gate precedes every provider on every route,
loopback bind unconditional, strict CSP the shell doesn't defeat, zero
decrypted bytes/keys to disk, entitlement gates (post-blob→posts, stories→known)
match hearth.

---

## On-device DoD — G20 (August drives)

**Field lessons (do these first):**
- Run the desktop node on **`serve --tor`**, unlocked (a bare `hearth app` has
  Tor off → sync EOFs; a locked node refuses sync).
- Install the **RELEASE** apk, not debug (debug → "Unable to load script").
- Play-Protect / "install anyway" may need a physical tap on the phone.
- **NDK bump → re-validate the Tor path**: this build re-toolchained the JNI
  bridge (r27.1). The first thing to confirm is that the phone still
  **bootstraps Tor + syncs** at all — if sync works, the bridge is fine.

**Honest boundary:** slice 1 is the **journal view only**, **read-only**. The
**Messages** and **Me/profile** tabs will not render content yet (their `/api`
endpoints land in slices 2–3) — tapping them is expected to be empty/non-
functional. Avatars are deferred (feed shows names, no avatar images).

### Steps

1. Desktop node up on `serve --tor`, unlocked. Post some fresh content: a text
   post, a photo post, a video post, a story, and a reaction + comment on a post.
2. On the phone, install + launch this RELEASE apk. (It syncs on launch.)

### Checklist — tick each

- [ ] **Tor + sync still work** under the new NDK (the app reaches the node —
      content arrives). *(This is the NDK-bump revalidation.)*
- [ ] App opens straight into the Kreds **journal** (no onboarding/lock/setup
      screen) — served by the desktop web UI, NOT the old native dashboard.
- [ ] The feed shows the desktop look: the `kreds` chrome, the **date "space
      line"** separators, posts as divider-separated rows.
- [ ] A **text post** renders with correct author name + timestamp.
- [ ] A **photo** post renders (AVIF decodes in the WebView).
- [ ] A **video** post plays + seeks (range requests to `/api/post-blob`).
- [ ] The **stories strip** renders; opening a story shows its media.
- [ ] **Responses** render: reaction count chips + comments (aliased
      names/colors, matching desktop; a resolved commenter shows a real name).
- [ ] **Read-only**: no composer, no comment input, no reaction-add picker, no
      "Delete everywhere", no "Your story" add — while count chips + comments
      stay visible.
- [ ] No token/CSP/403 errors in `adb logcat` during a full scroll.

### Verdict (August to fill)

> _(pass / partial / fail + notes — what rendered, any surprises)_

## After the run

On a **pass**, this slice merges to public main (the first visual-parity slice).
Then slice 2 (**Messages**), slice 3 (**Me/profile + 4-col wall**), slice 4
(mobile polish/theme).

## Follow-up tickets (Minor, none blocking merge — from the whole-branch review)

- **`author_name` fallback** — the feed's `author_name` uses the native
  `"me"`/`"friend-"+id[:8]` resolver, where hearth uses the bare `id[:8]`. Only
  differs for unnamed (pre-profile-sync) identities; internally inconsistent
  with the comment-name parity fix. Align the feed `author_name` to bare-8.
- **Tor-bootstrap blocks the read-only UI** — `WebShell` awaits `bootstrap()`
  (Tor) before loading; the local server could render already-synced cached
  content without Tor. Consider loading the shell first, syncing in the
  background.
- **`LocalWebServer.writeResponse`** clobbers a non-200 status → 206 when a
  Range header is present (a 404 + Range → 206 with a "not found" body; user-
  visible result identical to a clean 404). Guard: apply range only when
  status == 200.
- **`LocalApi.state()`** on a fixture-read failure sets `own=""` → own identity
  appears in `friends` (degraded/unenrolled state only; feed is empty there).
- **`sniff`** treats an 8–11-byte `ftyp` buffer as octet-stream where hearth
  yields video/mp4 (unreachable — real media > 12 bytes).
- **Friend-profile write controls** (Unfriend, ring-move) not hidden by the
  read-only seam — deferred to slice 3 (those views need `/api/profile`).
- **`react-native-webview`** — the loopback-cleartext NSC + the Gradle
  asset-copy are the `expo prebuild --clean`-fragile edits; the NSC already
  existed (MediaServer), the copy task is codified in the `withHearthWebAssets`
  config plugin. Verify both survive a clean prebuild before relying on it.
- **`/ws`** — no websocket this slice; `app.js`'s `connectWs()` fails silently.
  A later slice may add live push.
