# Seen-state fix + unread DM badge + sticky bars (0.3.10) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the never-clearing new-post dot, add an unread-DM count badge to the desktop nav, and pin the top bar + journal chips/composer while the feed scrolls — the 2026-07-10 live-test follow-up batch, shipping as core+web 0.3.10.

**Architecture:** All client work lives in `hearth/web/` (app.js / index.html / style.css) — localStorage-only seen-state, no new seen-data leaves the client. The single server change is one additive read-only field (`last_from_me`) on `node.conversations()`. No protocol, store, or crypto change.

**Tech Stack:** Vanilla JS (no framework, no new dependency), IntersectionObserver, CSS `position: sticky` + `overflow: clip`, FastAPI (existing), pytest, Playwright (dev-only, already installed).

**Spec:** `docs/superpowers/specs/2026-07-13-seen-state-badge-sticky-design.md` — read it before starting; it holds the approved semantics and the honest limits that must appear in code comments.

## Global Constraints

- Python: `.venv\Scripts\python.exe` (Windows venv). Run tests as `.venv\Scripts\python.exe -m pytest ...`.
- Console encoding is cp1252 — ASCII only in print statements and pytest output.
- Syntax-check client JS after every app.js edit: `node --check hearth/web/app.js`.
- NO AI attribution trailers on commits (August's rule; README discloses instead).
- No new runtime dependencies. Playwright is dev-only and already in the venv.
- localStorage honesty boundary: seen-state / unread watermarks are per-device, never synced, never sent to the server — state this in comments where the watermarks live.
- Comment style: comments explain constraints and honesty boundaries, matching the existing file voice (see `app.js:84-93`).
- Version bump (0.3.10 core + web) happens ONLY in Task 6, not per-task.
- The wire `PROTOCOL` (`hearth/v0.2`) is untouched. If any task seems to need a protocol change, STOP — that's a spec violation.

---

### Task 1: Server — `last_from_me` on conversations()

**Files:**
- Modify: `hearth/node.py:1234-1248` (the `conversations` method)
- Test: `tests/test_node_dm.py` (append one test)

**Interfaces:**
- Produces: `node.conversations()` entries gain `"last_from_me": bool | None` (None when the thread is empty). `/api/conversations` (hearth/api.py:514-516) returns `node.conversations()` verbatim, so the API picks this up with no route change. Task 3's client `convUnread(c)` reads `c.last_from_me`.

- [ ] **Step 1: Write the failing test**

Append to `tests/test_node_dm.py` (uses the file's existing `befriend_with_enckeys` helper at the top of the file):

```python
def test_conversations_last_from_me(tmp_path):
    # Direction of the newest message, per side: the unread badge (web
    # client) needs "did the other person write last", which last_at
    # alone cannot answer.
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    befriend_with_enckeys(wong, freja)
    wong.compose_dm(freja.identity_pub, "hej")
    assert wong.conversations()[0]["last_from_me"] is True
    # carry the DM to freja (same hand-carry as the roundtrip test above)
    for m in wong.store.messages_not_in({}, {wong.identity_pub},
                                        freja.identity_pub):
        freja.store.ingest_message(m)
    assert freja.conversations()[0]["last_from_me"] is False
    freja.compose_dm(wong.identity_pub, "hej selv")
    assert freja.conversations()[0]["last_from_me"] is True
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_node_dm.py::test_conversations_last_from_me -v`
Expected: FAIL with `KeyError: 'last_from_me'`

- [ ] **Step 3: Implement**

In `hearth/node.py`, `conversations()` — add one line to the dict (after `"last_text"`):

```python
            out.append({
                "identity_pub": other,
                "name": names.get(other, other[:8]),
                "last_text": last["text"] if last else None,
                "last_from_me": last["from_me"] if last else None,
                "last_at": last["created_at"] if last else None,
                "count": len(thread),
            })
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.venv\Scripts\python.exe -m pytest tests/test_node_dm.py -v`
Expected: all PASS (the new test plus the file's existing ones — the additive field must not break them).

- [ ] **Step 5: Commit**

```bash
git add hearth/node.py tests/test_node_dm.py
git commit -m "feat(dm): conversations() carries last_from_me - the unread badge needs message direction, which last_at alone cannot answer"
```

---

### Task 2: Seen-state fix (the dot finally clears)

**Files:**
- Modify: `hearth/web/app.js` — four spots: after `markOpenedNow` (~line 93), `buildEntry` (~line 209), `renderJournal` (~line 638), `openProfile` (~line 1107)
- Test: `tests/test_web_assets.py` (append)

**Interfaces:**
- Consumes: existing `lastOpened(key)` / `markOpenedNow(key)` (`app.js:88-93`), `renderChipbar()`, `renderCircleRail()`, `isFresh()`.
- Produces: `bumpOpenedTo(key, t)` and `scheduleFreshDotRerender()` — Task 3 does NOT use these (DMs get their own helpers); they are internal to the post-seen path.

- [ ] **Step 1: Write the failing web-asset test**

Append to `tests/test_web_assets.py` (uses the file's existing `WEB` path and `_js_fn_body` helper):

```python
def test_seen_state_observer_wired():
    # The 2026-07-13 seen-state fix: a post genuinely on screen (or a
    # profile visit) clears the person's new-post dot - not just the
    # chip click. Static wiring asserts; the live behavior is pinned by
    # tests/test_ui_smoke_seen_badge.py (UI_E2E=1).
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "IntersectionObserver" in js
    assert "SEEN_DWELL_MS" in js and "SEEN_RATIO" in js
    rj = _js_fn_body(js, "renderJournal")
    assert "journalSeenObserver" in rj          # entries observed on render
    assert "disconnect" in rj                   # re-attached, never leaked
    bump = _js_fn_body(js, "bumpOpenedTo")
    assert "lastOpened" in bump                 # never moves backwards
    prof = _js_fn_body(js, "openProfile")
    assert "markOpenedNow" in prof              # profile visit clears the dot
    be = _js_fn_body(js, "buildEntry")
    assert "dataset.created" in be              # observer needs the post time
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py::test_seen_state_observer_wired -v`
Expected: FAIL on `assert "IntersectionObserver" in js`

- [ ] **Step 3: Implement in app.js**

**(a)** Directly after `markOpenedNow` (line ~93), add:

```js
// Bump a person's watermark to a specific post's created_at - never to
// "now", never backwards. Seeing Tuesday's post must NOT mark Thursday's
// unseen post below the fold as read: isFresh() compares against the
// person's LATEST post, so a created_at watermark honestly keeps the dot.
function bumpOpenedTo(key, t) {
  if (!key || !(t > lastOpened(key))) return;
  localStorage.setItem("kreds_opened:" + key, String(t));
  scheduleFreshDotRerender();
}

// Debounced dot refresh: one scroll can mark several posts seen; collapse
// into one chipbar+rail re-render. Deliberately NEVER re-renders the
// journal itself - that would rebuild the entries mid-scroll and
// re-trigger the seen observer.
let FRESH_RERENDER_TIMER = null;
function scheduleFreshDotRerender() {
  clearTimeout(FRESH_RERENDER_TIMER);
  FRESH_RERENDER_TIMER = setTimeout(() => {
    renderChipbar();
    renderCircleRail();
  }, 200);
}
```

**(b)** In `buildEntry` (line ~209), directly after `article.dataset.author = p.identity_pub;`, add:

```js
  article.dataset.created = p.created_at;
```

**(c)** Directly above `renderJournal` (line ~638), add the observer:

```js
// -- seen-state observer: a journal entry that has genuinely been on
// screen (>= SEEN_RATIO visible for SEEN_DWELL_MS) marks its author's
// watermark at that post's created_at. The dwell means a fast fling past
// a post does not count as reading it. localStorage-only - same honesty
// boundary as lastOpened above; no seen-data ever leaves this device.
const SEEN_RATIO = 0.6;
const SEEN_DWELL_MS = 700;
const SEEN_TIMERS = new Map();   // entry element -> pending dwell timer
function clearSeenTimers() {
  for (const t of SEEN_TIMERS.values()) clearTimeout(t);
  SEEN_TIMERS.clear();
}
const journalSeenObserver = new IntersectionObserver((entries) => {
  for (const en of entries) {
    const node = en.target;
    if (en.isIntersecting && en.intersectionRatio >= SEEN_RATIO) {
      if (!SEEN_TIMERS.has(node)) SEEN_TIMERS.set(node, setTimeout(() => {
        SEEN_TIMERS.delete(node);
        journalSeenObserver.unobserve(node);
        bumpOpenedTo(node.dataset.author, Number(node.dataset.created));
      }, SEEN_DWELL_MS));
    } else {
      clearTimeout(SEEN_TIMERS.get(node));
      SEEN_TIMERS.delete(node);
    }
  }
}, {threshold: SEEN_RATIO});
```

**(d)** Replace `renderJournal` (line ~638) with:

```js
function renderJournal() {
  const root = document.getElementById("journal");
  // Entries are about to be rebuilt: drop observations AND pending dwell
  // timers (a timer surviving a re-render would mark a post the user may
  // have already scrolled away from).
  journalSeenObserver.disconnect();
  clearSeenTimers();
  root.replaceChildren();
  for (const day of groupByDay(filteredFeed())) {
    root.append(el("div", "dayhead", dayLabel(day.date)));
    for (const p of day.rows) {
      const entry = buildEntry(p);
      root.append(entry);
      if (!p.mine) journalSeenObserver.observe(entry);
    }
  }
  root.append(endState());
}
```

**(e)** In `openProfile` (line ~1107), directly after the `try/catch` that assigns `p` (i.e. after the `p = fallbackProfile(identityPub);` closing brace, before `renderProfilePage(p);`), add:

```js
  // Opening someone's profile is a deliberate visit: clear their new-post
  // dot outright (watermark to now - unlike the journal observer's
  // per-post bump, a visit means "caught up on this person").
  if (!p.mine) { markOpenedNow(identityPub); scheduleFreshDotRerender(); }
```

- [ ] **Step 4: Verify**

Run: `node --check hearth/web/app.js`
Expected: no output (clean).
Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -v`
Expected: all PASS (new test + existing ones — `renderJournal`/`buildEntry`/`openProfile` are asserted by older tests too; the edits must not break them).

- [ ] **Step 5: Commit**

```bash
git add hearth/web/app.js tests/test_web_assets.py
git commit -m "fix(seen): new-post dot clears when the post is actually read - IntersectionObserver (60% visible, 700ms dwell) bumps the watermark to that post's created_at; opening a profile clears outright; chip click unchanged. Was: only the chip click ever cleared it"
```

---

### Task 3: Unread DM badge

**Files:**
- Modify: `hearth/web/index.html:46` (the `#nav-messages` button)
- Modify: `hearth/web/app.js` — globals (top of file, next to the `FEED`/`KREDS` declarations), helpers (after Task 2's `scheduleFreshDotRerender`), `loadConversations` (~line 2624), `openThread` (~line 2657), the `dm-compose` submit handler (~line 2693), `setView` (~line 2757), `refresh()` (~line 2813-2826)
- Modify: `hearth/web/style.css` (badge + unread-row rules, after the `.conv .preview` rule ~line 585)
- Test: `tests/test_web_assets.py` (append)

**Interfaces:**
- Consumes: `c.last_from_me` from Task 1 (may be `undefined` under update skew — handle it), existing `j()` fetch helper, `el()` DOM helper, `STATE.friends`, `CURRENT_DM` / `CURRENT_DM_NAME` globals.
- Produces: `CONVS` global (array, latest `/api/conversations` result), `dmOpened(identity)`, `markDmOpenedNow(identity)`, `convUnread(c)`, `renderDmBadge()`, `renderConversations()` (render-only; `loadConversations()` becomes fetch-then-render). Task 5's smoke drives these through the real UI.

- [ ] **Step 1: Write the failing web-asset test**

Append to `tests/test_web_assets.py`:

```python
def test_dm_unread_badge_wired():
    # Unread badge (live-test follow-up): count of conversations whose
    # last message is from the other side and newer than the per-device
    # kreds_dm_opened watermark. Desktop nav only (mobile has no Messages
    # tab - a named follow-up, not silently included).
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert 'id="nav-msg-badge"' in html
    assert "kreds_dm_opened:" in js               # own prefix, not kreds_opened
    unread = _js_fn_body(js, "convUnread")
    assert "last_from_me" in unread
    assert "over-badge" in unread                 # skew degrade documented
    badge = _js_fn_body(js, "renderDmBadge")
    assert "hidden" in badge                      # hidden at zero
    thread = _js_fn_body(js, "openThread")
    assert "markDmOpenedNow" in thread            # opening clears
    assert "await j(\"/api/conversations\")" not in _js_fn_body(js, "openThread")
    _css_rule(css, ".navbadge")                   # style exists
    # the old double-fetch is gone: exactly ONE fetch site remains
    assert js.count('j("/api/conversations")') == 1
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py::test_dm_unread_badge_wired -v`
Expected: FAIL on `assert 'id="nav-msg-badge"' in html`

- [ ] **Step 3: index.html — badge span**

Line 46, change:

```html
      <button id="nav-messages" data-view="messages">Messages</button>
```

to:

```html
      <button id="nav-messages" data-view="messages">Messages<span
        class="navbadge hidden" id="nav-msg-badge" aria-label="Unread conversations"></span></button>
```

- [ ] **Step 4: app.js — global, helpers, refactor**

**(a)** Find the module-level declarations of `FEED` and `KREDS` (search `let FEED`) and add alongside them:

```js
let CONVS = [];   // latest /api/conversations - fetched every refresh() for the nav badge
```

**(b)** After Task 2's `scheduleFreshDotRerender`, add:

```js
// -- DM unread watermark: same per-device localStorage boundary as the
// post watermark above (never synced, never sent to the server), with
// its OWN prefix - a chip click marking posts read must not mark a DM
// thread read, and vice versa.
function dmOpened(identity) {
  return Number(localStorage.getItem("kreds_dm_opened:" + identity) || 0);
}
function markDmOpenedNow(identity) {
  localStorage.setItem("kreds_dm_opened:" + identity, String(Date.now() / 1000));
}

// A conversation is unread when the other side wrote last and it's newer
// than this device's watermark.
function convUnread(c) {
  if (!c.last_at) return false;
  if (c.last_from_me === true) return false;
  // Update-skew degrade (web payload ahead of core, allowed skew): an
  // older core omits last_from_me entirely - fall back to "any activity
  // since I opened", which can over-badge a thread my own OTHER device
  // answered last, but never silently under-badges.
  return c.last_at > dmOpened(c.identity_pub);
}

// Red count pill on the desktop Messages nav button: number of unread
// CONVERSATIONS (people, not messages), hidden at zero. Mobile has no
// Messages tab to badge - named follow-up, not silently included.
function renderDmBadge() {
  const badge = document.getElementById("nav-msg-badge");
  if (!badge) return;
  const n = CONVS.filter(convUnread).length;
  badge.textContent = String(n);
  badge.classList.toggle("hidden", n === 0);
}
```

**(c)** Replace `loadConversations` (line ~2624) with a fetch-then-render pair. `renderConversations` is today's body rendering from `CONVS` (copy, don't mutate — the friends-merge placeholders must not accumulate in the global), plus the unread row state:

```js
async function loadConversations() {
  CONVS = await j("/api/conversations");
  renderConversations();
  renderDmBadge();
}

function renderConversations() {
  const root = document.getElementById("conversations");
  root.replaceChildren();
  const convs = CONVS.slice();
  const friends = (STATE ? STATE.friends : []);
  const seen = new Set(convs.map(c => c.identity_pub));
  for (const f of friends)
    if (!seen.has(f.identity_pub))
      convs.push({identity_pub: f.identity_pub, name: f.name, last_text: null});
  if (!convs.length) {
    root.append(el("div", "hint", "No friends yet. Add someone to chat."));
    return;
  }
  for (const c of convs) {
    const row = el("div", "conv"
      + (c.identity_pub === CURRENT_DM ? " active" : "")
      + (convUnread(c) ? " unread" : ""));
    const av = el("div", "conv-avatar");
    av.textContent = (c.name || "?").slice(0, 1).toUpperCase();
    const txt = el("div");
    txt.append(el("div", "name", c.name),
               el("div", "preview", c.last_text || "no messages yet"));
    row.append(av, txt);
    if (convUnread(c)) row.append(el("span", "cdot"));
    row.onclick = () => openThread(c.identity_pub, c.name);
    root.append(row);
  }
}
```

**(d)** In `openThread` (line ~2657), replace the line

```js
  loadConversations();   // refresh the active highlight
```

with:

```js
  // Opening the thread is what "read" means for the badge: mark this
  // device's watermark, then re-render list + badge from the CONVS we
  // already hold (the old refetch here was the backlog's double-fetch).
  markDmOpenedNow(identity);
  renderConversations();
  renderDmBadge();
```

**(e)** In the `dm-compose` submit handler (line ~2693), after the successful send (locate the end of the handler where the input is cleared / after the POST succeeds), add:

```js
  // Replying obviously means the thread is read - matters on the skew
  // path (no last_from_me), where the fallback is time-based.
  if (CURRENT_DM) markDmOpenedNow(CURRENT_DM);
```

**(f)** In `refresh()` (line ~2813), replace the messages-view block

```js
  if (!document.getElementById("view-messages").classList.contains("hidden")) {
    loadConversations();
    // Keep the open conversation live: ...
    if (CURRENT_DM) openThread(CURRENT_DM, CURRENT_DM_NAME, true);
  }
```

with:

```js
  // Conversations are fetched EVERY cycle now (not just with Messages
  // open): the nav badge lives outside that view. One fetch feeds both
  // the badge and the list.
  await loadConversations();
  if (!document.getElementById("view-messages").classList.contains("hidden")) {
    // Keep the open conversation live: a WS "changed" (e.g. an incoming DM
    // arriving over gossip) re-renders the thread in place, preserving the
    // reader's scroll position unless they're already at the bottom.
    if (CURRENT_DM) openThread(CURRENT_DM, CURRENT_DM_NAME, true);
  }
```

**(g)** In `setView` (line ~2757), the `if (which === "messages") { loadConversations(); ... }` line: `loadConversations()` here is now redundant with refresh() but harmless and keeps an explicit nav click instantly fresh — replace it with `renderConversations()` ONLY if the asset test's single-fetch-site count fails; otherwise leave it. (The count in Step 1 counts `j("/api/conversations")` call sites — `loadConversations` is the one site; `setView` calls `loadConversations`, not `j(...)` directly, so it passes as-is. Leave it.)

- [ ] **Step 5: style.css — badge + unread row**

After the `.conv .preview` rule (~line 585), add:

```css
/* unread DM badge (nav) + unread conversation row. Count = unread
   conversations, hidden at zero (renderDmBadge). */
.navbadge { display: inline-grid; place-items: center; min-width: 17px; height: 17px;
  padding: 0 5px; margin-left: 7px; border-radius: 99px; background: var(--red);
  color: #fff; font-size: 10.5px; font-weight: 650; line-height: 1; vertical-align: 1px; }
.navlinks button.active .navbadge { background: var(--paper); color: var(--ink); }
.conv .cdot { width: 8px; height: 8px; border-radius: 50%; background: var(--red);
  margin-left: auto; flex-shrink: 0; }
.conv.unread .name { font-weight: 750; }
```

- [ ] **Step 6: Verify**

Run: `node --check hearth/web/app.js`
Expected: clean.
Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -v`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add hearth/web/index.html hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat(dm): unread badge on the Messages nav - count of conversations where the other side wrote last (kreds_dm_opened watermark, localStorage-only, skew-degrades without last_from_me); conversations fetched once per refresh, killing the double-fetch backlog item"
```

---

### Task 4: Sticky top bar + journal header group

**Files:**
- Modify: `hearth/web/index.html:58-84` (wrap chipbar + composer)
- Modify: `hearth/web/style.css` — `.app` (~line 133), `.appnav` (~line 137), `body.desktop` (~line 113), new `.journal-sticky` rule (after the `.chipbar` block ~line 258)
- Modify: `hearth/web/app.js` — nav-height measurement (near `wireDesktopChrome`, ~line 2862)
- Test: `tests/test_web_assets.py` (append)

**Interfaces:**
- Consumes: `body.desktop` class (set by `wireDesktopChrome` when `window.pywebview` exists), existing `.hidden` utility.
- Produces: CSS custom properties `--chrome-h` (40px under the desktop titlebar, unset in a browser) and `--nav-h` (runtime-measured). No JS API for other tasks.

- [ ] **Step 1: Write the failing web-asset test**

Append to `tests/test_web_assets.py`:

```python
def test_sticky_journal_header():
    # Sticky nav + chips/composer (live-test follow-up). The load-bearing
    # detail: .app must be overflow:clip, NOT hidden - a hidden ancestor
    # creates a scroll container and silently kills position:sticky
    # against the page scroll.
    css = (WEB / "style.css").read_text(encoding="utf-8")
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    app_rule = _css_rule(css, ".app")
    assert "overflow: clip" in app_rule
    assert "overflow: hidden" not in app_rule
    nav_rule = _css_rule(css, ".appnav")
    assert "position: sticky" in nav_rule
    assert "--chrome-h" in nav_rule               # desktop titlebar offset
    assert 'id="journal-sticky"' in html
    assert ".journal-sticky" in css
    assert "--nav-h" in js and "offsetHeight" in js   # measured, not hardcoded
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py::test_sticky_journal_header -v`
Expected: FAIL on `assert "overflow: clip" in app_rule`

- [ ] **Step 3: index.html — wrap chipbar + composer**

In `#view-journal`'s `<section class="journal">` (lines 58-84), wrap the existing `<div class="chipbar" ...>` and `<form class="composer" ...>` elements — both completely unchanged inside — in one new div:

```html
        <div class="journal-sticky" id="journal-sticky">
          <div class="chipbar" id="chipbar" role="group" aria-label="Filter journal"></div>

          <form class="composer" id="composer">
            ... (existing content, unchanged) ...
          </form>
        </div>
```

`#journal` (the entry list) stays OUTSIDE the wrapper.

- [ ] **Step 4: style.css**

**(a)** `.app` rule (~line 133): change `overflow: hidden;` to `overflow: clip;` and add the reason where it happens:

```css
/* overflow: clip (NOT hidden) - identical rounded-corner clipping, but
   hidden would make .app a scroll container and silently kill the
   position:sticky nav/journal-header below (sticky pins to the nearest
   scrollport; .app itself never scrolls - the page does). */
```

**(b)** `.appnav` rule (~line 137): add to the existing declarations:

```css
  position: sticky; top: var(--chrome-h, 0px); z-index: 15;
```

(z-index 15: below the circle overlay's 20 and every modal at 25+, above entries.)

**(c)** `body.desktop` rule (~line 113): add the custom property next to the existing `padding-top: 40px;`:

```css
body.desktop { padding-top: 40px; --chrome-h: 40px; }
```

**(d)** After the `.fchip .fdot` rule (~line 258), add:

```css
/* Sticky journal header: chips + composer pin as ONE wrapper under the
   nav (per-element sticky offsets would break when the chipbar wraps to
   a second row as friends grow). Negative margins/padding make it span
   .journal's 20px/24px padding so entries scroll fully under it, on the
   journal column's own --surface. --nav-h is measured at runtime in
   app.js (nav height depends on content), --chrome-h is the desktop
   titlebar (0 in a browser). <=760px - the journal's existing mobile
   breakpoint - keeps only the nav pinned: three stacked bars would eat
   a phone viewport. */
@media (min-width: 761px) {
  .journal-sticky { position: sticky;
    top: calc(var(--chrome-h, 0px) + var(--nav-h, 51px)); z-index: 14;
    background: var(--surface); border-bottom: 1px solid var(--line);
    margin: -14px -24px 14px; padding: 14px 24px 10px; }
}
```

*(Amended during execution — Task 4 review finding: the original -20px/20px pair
assumed the wrapper is `.journal`'s first child, but `renderStories()` inserts
the `#stories` strip above it at runtime, so -20px covered the story-name
labels. -14px/14px overlaps only the strip's own 14px empty bottom padding.)*

- [ ] **Step 5: app.js — measure the nav height**

Directly above `wireDesktopChrome` (~line 2862), add and call:

```js
// --nav-h feeds .journal-sticky's sticky offset (style.css): measured,
// not hardcoded - the nav's real height depends on font metrics and can
// change if it ever wraps.
function measureNavHeight() {
  const nav = document.querySelector(".appnav");
  if (nav) document.documentElement.style.setProperty(
    "--nav-h", nav.offsetHeight + "px");
}
window.addEventListener("resize", measureNavHeight);
measureNavHeight();
```

- [ ] **Step 6: Verify**

Run: `node --check hearth/web/app.js`
Expected: clean.
Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -v`
Expected: all PASS (including the older asset tests that assert chipbar/composer markup — they must still find both inside the wrapper).

- [ ] **Step 7: Commit**

```bash
git add hearth/web/index.html hearth/web/style.css hearth/web/app.js tests/test_web_assets.py
git commit -m "feat(ui): sticky nav + journal chips/composer - .app overflow:hidden was silently defeating position:sticky, now overflow:clip (same clipping, no scroll container); chips+composer pin as one wrapper below the measured nav height; <=760px pins the nav only"
```

---

### Task 5: Live browser smoke (UI_E2E-gated, two real nodes)

**Files:**
- Create: `tests/test_ui_smoke_seen_badge.py`

**Interfaces:**
- Consumes: everything Tasks 1-4 shipped, through the real UI only. `HearthNode`, `SyncService` (`tests/test_curated_profile_integration.py` pattern), `build_app` (hearth/api.py), `uvicorn`, `playwright.sync_api`.
- Produces: nothing downstream — this is the behavioral pin the roadmap's "permanent behavioral test suite" note asks for; written runnable and gated like `TOR_E2E`.

- [ ] **Step 1: Prerequisite check**

Run: `.venv\Scripts\python.exe -m playwright install chromium`
Expected: installs or reports already installed. (Dev-only; the gate below keeps CI/default runs unaffected.)

- [ ] **Step 2: Write the smoke**

Create `tests/test_ui_smoke_seen_badge.py`:

```python
"""UI_E2E=1-gated live browser smoke for the 0.3.10 batch: seen-state
dot clearing and the unread DM badge, driven through the real web client
in headless Chromium against TWO real nodes syncing over real sockets.

Why a live smoke and not just the static asserts in test_web_assets.py:
the week of 2026-07-10's only two critical UI bugs were caught by live
smokes, never by static text asserts (ROADMAP, "permanent behavioral
test suite"). This file is written as a permanent gated test - the
promotion candidate that note asks for.

Harness: Playwright's sync API owns the main thread, so each node's
asyncio stack (SyncService + uvicorn/FastAPI app) runs on a loop in a
background thread; syncs are triggered explicitly via
run_coroutine_threadsafe (no gossip loop - deterministic).

Not covered here (covered elsewhere): the profile-visit clearing path
(static assert in test_web_assets.py + August's checklist - driving it
here would race the journal observer's own 700ms dwell on the same
post); sticky-scroll feel (August's checklist - it's a feel, not a DOM
predicate).
"""
import asyncio
import os
import socket
import threading

import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("UI_E2E") != "1",
    reason="live browser smoke; set UI_E2E=1 (needs playwright chromium)")

import uvicorn

from hearth.api import build_app
from hearth.node import HearthNode
from hearth.sync import SyncService


def _free_port() -> int:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class LiveNode:
    """SyncService + HTTP app on a background-thread asyncio loop."""

    def __init__(self, dir_, name, device):
        self.node = HearthNode.create(dir_, name, device)
        self.loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self.loop.run_forever, daemon=True)
        self.thread.start()

    def start(self):
        async def _start():
            self.sync = SyncService(self.node)
            gport = await self.sync.start("127.0.0.1", 0)
            self.node.store.set_meta("gossip_addr", f"127.0.0.1:{gport}")
            self.gossip_addr = f"127.0.0.1:{gport}"
            self.http_port = _free_port()
            cfg = uvicorn.Config(build_app(self.node), host="127.0.0.1",
                                 port=self.http_port, log_level="warning")
            self.server = uvicorn.Server(cfg)
            self.serve_task = asyncio.ensure_future(self.server.serve())
        self._run(_start(), timeout=20)

    def sync_with(self, other):
        self._run(self.sync.sync_with(other.gossip_addr), timeout=30)

    def stop(self):
        async def _stop():
            self.server.should_exit = True
            await self.sync.stop()
        try:
            self._run(_stop(), timeout=10)
        finally:
            self.loop.call_soon_threadsafe(self.loop.stop)
            self.thread.join(timeout=5)

    def _run(self, coro, timeout):
        return asyncio.run_coroutine_threadsafe(coro, self.loop).result(timeout)


def befriend(a: LiveNode, b: LiveNode):
    a.node.store.add_identity(b.node.identity_pub)
    b.node.store.add_identity(a.node.identity_pub)
    a.node.ensure_enckey()
    b.node.ensure_enckey()


def test_seen_dot_and_dm_badge_live(tmp_path):
    from playwright.sync_api import sync_playwright

    a = LiveNode(tmp_path / "a", "Anna", "anna-pc")
    b = LiveNode(tmp_path / "b", "Bo", "bo-pc")
    try:
        befriend(a, b)
        a.start()
        b.start()
        a.sync_with(b)   # exchange enckeys/profiles

        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            page = browser.new_page(viewport={"width": 1280, "height": 900})
            errors = []
            page.on("pageerror", lambda e: errors.append(str(e)))
            page.goto(f"http://127.0.0.1:{a.http_port}/")
            page.wait_for_selector(".fchip")   # app booted, chipbar rendered

            # -- 1: B posts; Anna's chip grows a fresh dot ---------------
            b.node.compose_post("hej fra Bo", scope="kreds")
            b.sync_with(a)
            # WS "changed" triggers refresh(); the dot appears...
            page.wait_for_selector(".fchip .fdot", timeout=10000)
            # ...and the post is at the top of the feed, on screen: after
            # the 700ms dwell + 200ms debounce it clears WITHOUT reload.
            page.wait_for_selector(".fchip .fdot", state="detached",
                                   timeout=5000)

            # -- 2: dot honesty while the journal is hidden --------------
            # Switch to Messages (journal hidden -> observer can't mark),
            # B posts again: the dot must appear AND persist.
            page.click("#nav-messages")
            b.node.compose_post("nummer to", scope="kreds")
            b.sync_with(a)
            page.wait_for_selector(".fchip .fdot", state="attached",
                                   timeout=10000)
            page.wait_for_timeout(1500)   # > dwell+debounce
            assert page.locator(".fchip .fdot").count() == 1, \
                "dot must NOT clear while the journal is hidden"

            # -- 3: unread DM badge appears ------------------------------
            b.node.compose_dm(a.node.identity_pub, "privat hej")
            b.sync_with(a)
            page.wait_for_selector("#nav-msg-badge:not(.hidden)",
                                   timeout=10000)
            assert page.locator("#nav-msg-badge").inner_text() == "1"
            assert page.locator(".conv.unread").count() == 1

            # -- 4: opening the thread clears the badge ------------------
            page.click(".conv.unread")
            page.wait_for_selector("#nav-msg-badge.hidden", timeout=5000)
            assert page.locator(".conv.unread").count() == 0

            # -- 5: replying keeps it clear (and the reply round-trips) --
            page.fill("#dm-text", "hej selv")
            page.click("#dm-compose button[type=submit]")
            page.wait_for_timeout(1000)
            assert page.locator("#nav-msg-badge.hidden").count() == 1

            # -- 6: back on the journal, post 2 now on screen -> clears --
            page.click("#nav-journal")
            page.wait_for_selector(".fchip .fdot", state="detached",
                                   timeout=5000)

            assert not errors, f"console pageerrors: {errors}"
            browser.close()
    finally:
        a.stop()
        b.stop()
```

- [ ] **Step 3: Run the smoke**

Run (PowerShell): `$env:UI_E2E = "1"; .venv\Scripts\python.exe -m pytest tests/test_ui_smoke_seen_badge.py -v -s; Remove-Item Env:UI_E2E`
Expected: PASS. If any wait times out, debug the FEATURE first (this is the test catching a real wiring bug — that is its job), not the timeout value. Also confirm the gate: `.venv\Scripts\python.exe -m pytest tests/test_ui_smoke_seen_badge.py -v` (no env var) → 1 skipped.

- [ ] **Step 4: Commit**

```bash
git add tests/test_ui_smoke_seen_badge.py
git commit -m "test(ui): UI_E2E-gated live smoke for seen-dot clearing + DM badge - two real nodes, real sync sockets, headless Chromium; first permanent candidate for the behavioral suite the roadmap names"
```

---

### Task 6: Version bump, ROADMAP, full-suite verification

**Files:**
- Modify: `hearth/__init__.py:2` (`__version__`)
- Modify: `hearth/web/VERSION`
- Modify: `ROADMAP.md` ("Live-test findings" bullets + the backlog's double-fetch line)

**Interfaces:**
- Consumes: everything above, complete and committed.
- Produces: 0.3.10 ready for August's behavioral pass and the signed release pipeline (`RELEASE.md` — the actual build/sign/publish is August's manual step, NOT this plan's).

- [ ] **Step 1: Bump versions**

`hearth/__init__.py`: `__version__ = "0.3.10"`
`hearth/web/VERSION`: `0.3.10`
(Core change in Task 1 means this canNOT ship as a web-only hot-update — core and web move together, mirroring the 0.3.8 bump commit `8156fd4`.)

- [ ] **Step 2: Update ROADMAP.md**

In "Live-test findings (2026-07-10...)":
- The **Unread badge** bullet → `**Unread badge** — DONE (0.3.10): nav count of unread conversations (last message from the other side vs a kreds_dm_opened localStorage watermark; per-device, honest; skew-degrades on an old core). Mobile has no Messages tab to badge — named follow-up.`
- The **Sticky top bar + composer** bullet → `**Sticky top bar + composer on scroll** — DONE (0.3.10): .app overflow:hidden was silently defeating position:sticky (scroll-container rule); now overflow:clip + a single sticky chips+composer wrapper under the measured nav. <=760px pins the nav only.`
- Add a new bullet: `**Seen-state fix** — DONE (0.3.10, reported by August 2026-07-13): the new-post dot cleared ONLY on a chip click; now also when the post is genuinely on screen (IntersectionObserver, 60%/700ms dwell, watermark at that post's created_at — a newer below-fold post honestly keeps the dot) or on a profile visit. Live-pinned by tests/test_ui_smoke_seen_badge.py (UI_E2E=1).`

In "Polish & tech-debt backlog" → Data/perf: delete `dedupe the double /api/conversations fetch per refresh;` (shipped with the badge).

- [ ] **Step 3: Full suite, twice**

Run: `.venv\Scripts\python.exe -m pytest -q` (twice, back to back)
Expected: all pass + the 2 known env-gated skips (`TOR_E2E`, `UI_E2E`), consistent across both runs. Also: `node --check hearth/web/app.js` clean.

- [ ] **Step 4: Commit**

```bash
git add hearth/__init__.py hearth/web/VERSION ROADMAP.md
git commit -m "chore: bump 0.3.10 (seen-state fix + unread DM badge + sticky bars); roadmap live-test bullets marked DONE"
```

- [ ] **Step 5: Hand-off checklist for August (paste in chat at the end, not committed)**

- Sticky: scroll a long journal on desktop — nav + chips + composer pinned, entries slide under cleanly (no bleed-through above the composer); check both themes; check the desktop app (`hearth app`) — the pinned bars must sit BELOW the traffic-light titlebar, not under it; check <=760px width — only the nav pins.
- Seen dot: have a friend post over real Tor; dot appears on their chip + rail node; read the post in the feed → dot clears in ~1s without switching views; a post arriving while you're in Messages keeps its dot until you actually see it; the 700ms dwell feel (too eager? too lazy? it's `SEEN_DWELL_MS`, one constant).
- Badge: real DM over Tor → red count on Messages; open the thread → clears; reply → stays clear; two friends DMing → count reads 2, opening one drops to 1.
- Dark mode + a pale custom accent on the new pill/dot/unread styles.

---

## Self-review (done at write time)

1. **Spec coverage:** seen-state semantics (§1) → Task 2; `last_from_me` + badge + skew degrade + double-fetch removal (§2) → Tasks 1+3; overflow:clip + sticky wrapper + 760px mobile + z-order (§3) → Task 4; server test / DOM asserts / live smoke / checklist (§Testing) → Tasks 1/2/3/4 step-1 tests + Task 5 + Task 6 step 5; core+web 0.3.10 (§Release) → Task 6. Spec says "≤820px: only the nav sticks" — the journal's actual mobile breakpoint in style.css is 760px (820 is the profile layout's); Task 4 uses 760 to match the layout it modifies. Deliberate, noted here.
2. **Placeholder scan:** none — every step carries its code, command, or exact expected outcome.
3. **Type consistency:** `bumpOpenedTo(key, t)` / `scheduleFreshDotRerender()` defined in Task 2, used only there + openProfile; `CONVS` / `convUnread(c)` / `renderDmBadge()` / `renderConversations()` / `markDmOpenedNow(identity)` defined in Task 3 and referenced identically in its refactor sites and Task 5's selectors; `--chrome-h` / `--nav-h` defined and consumed only in Task 4's CSS/JS; `last_from_me` spelled identically in Task 1 (python), Task 3 (JS), and both test files.
