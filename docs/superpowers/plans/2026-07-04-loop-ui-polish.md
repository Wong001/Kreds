# Loop UI Polish + Dark Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Loop's Feed/Messages/Profile look modern and finished, add a light/dark theme toggle, and widen accent customization (16 swatches + custom hex).

**Architecture:** Mostly CSS/markup/JS polish in `hearth/web/`, plus one small backend change (accent validation relaxes from a fixed palette to any valid `#rrggbb` hex). Dark mode is a CSS-variable swap keyed on `:root[data-theme]`; nothing structural changes because every screen already reads `--paper/--card/--ink/--line/--me`. Spec: `docs/superpowers/specs/2026-07-04-loop-ui-polish-design.md`.

**Tech Stack:** Existing Loop stack (Python 3.12, FastAPI, vanilla JS/CSS, Pillow). No new dependency.

## Global Constraints

- Run as `.\.venv\Scripts\python.exe ...` from `C:\Users\Wong\Desktop\Hearth`, branch `main`. Remote `origin` = github.com/Wong001/Loop.git.
- ASCII only in console/demo prints (cp1252).
- Preserve every element id/behavior the app depends on; this is look, not logic (except the accent-validation change). Feed/messages/profiles/stories/DMs/sync behavior unchanged.
- Dark theme = a neutral cool scale keyed on `:root[data-theme="dark"]`: `--paper #15171c`, `--card #1d2026`, `--ink #e7e9ee`, `--ink-2 #b6bac2`, `--muted #868b95`, `--faint #5c616b`, `--line #2b2f37`, `--line-2 #23262d`, `--fill #262a32`. Light stays the default `:root`. Theme persists in `localStorage["loop_theme"]`; default follows `prefers-color-scheme`.
- Accent = any lowercase `#rrggbb` (validate `^#[0-9a-f]{6}$`); the 16-swatch palette is suggestions, not enforced. Client always lowercases before sending.
- No view/seen metrics anywhere (thesis, unchanged).
- Full suite stays green; each visual task adds structural assertions to `tests/test_web_assets.py` and a demo smoke check. Require ZERO failures at each task's full-suite step.
- Commit after every task, trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: Accent validation → any valid hex

**Files:**
- Modify: `hearth/messages.py`, `hearth/api.py`
- Modify (tests in lockstep): `tests/test_profile_model.py`, `tests/test_api_profile.py`
- Test: (above)

**Interfaces:**
- Produces: `hearth.messages._is_hex_color(s) -> bool` (`^#[0-9a-f]{6}$`); the profile-branch accent check uses it; `hearth.api` profile POST uses it (import it). `ACCENTS` stays exported (suggested swatches, no longer enforced).

- [ ] **Step 1: Update the failing tests first**

In `tests/test_profile_model.py`, `test_invalid_profiles_rejected`: the palette-membership assumptions change. Replace the two accent lines:
```python
    assert not ok({**base, "accent": "#ffffff"})        # not in palette
    assert not ok({**base, "accent": "cobalt"})         # not hex
```
with:
```python
    assert ok({**base, "accent": "#ffffff"})            # any valid hex now ok
    assert ok({**base, "accent": "#abc123"})            # custom hex ok
    assert not ok({**base, "accent": "cobalt"})         # not hex -> rejected
    assert not ok({**base, "accent": "#fff"})           # short hex rejected
    assert not ok({**base, "accent": "#GG1234"})        # non-hex chars rejected
```
Keep `test_accent_membership_case_insensitive_not_required` (uppercase `#2743D6` still rejected — we validate strict lowercase). Its name is now slightly off but the assertion (uppercase rejected) is still correct; leave it.

In `tests/test_api_profile.py`, `test_post_profile_bad_accent_400`: change the invalid value from `#ffffff` (now valid) to a non-hex:
```python
    r = c.post("/api/profile", data={"name": "Wong", "accent": "not-a-color"})
    assert r.status_code == 400
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_profile_model.py tests/test_api_profile.py -q`
Expected: FAIL — `#ffffff` currently rejected (asserted ok now); `not-a-color` path still 400 but the `#ffffff`/`#abc123` ok-asserts fail against current palette-membership logic.

- [ ] **Step 3: Modify `hearth/messages.py`**

Add the helper near `_is_hexn`:
```python
def _is_hex_color(s) -> bool:
    return (isinstance(s, str) and len(s) == 7 and s[0] == "#"
            and all(c in "0123456789abcdef" for c in s[1:]))
```
In `validate_payload`'s `KIND_PROFILE` branch, replace:
```python
        if p.get("accent", "#2743d6") not in ACCENTS:
            return False, "bad accent"
```
with:
```python
        if not _is_hex_color(p.get("accent", "#2743d6")):
            return False, "bad accent"
```

- [ ] **Step 4: Modify `hearth/api.py`**

Add `_is_hex_color` to the `from .messages import (...)` line. Replace the accent guard in the profile POST handler:
```python
        if accent not in ACCENTS:
            raise HTTPException(400, "accent not in palette")
```
with:
```python
        if not _is_hex_color(accent):
            raise HTTPException(400, "bad accent color")
```
(If `ACCENTS` is now unused in api.py, drop it from the import to avoid a lint nit.)

- [ ] **Step 5: Run tests to verify they pass, then the full suite**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_profile_model.py tests/test_api_profile.py -q`
Expected: pass.
Run: `.\.venv\Scripts\python.exe -m pytest tests -q`
Expected: zero failures.

- [ ] **Step 6: Commit**

```bash
git add hearth/messages.py hearth/api.py tests/test_profile_model.py tests/test_api_profile.py
git commit -m "feat: accept any valid hex accent (palette becomes suggestions)"
```

---

### Task 2: Dark mode — neutral tokens + header toggle + persistence

**Files:**
- Modify: `hearth/web/style.css`, `hearth/web/index.html`, `hearth/web/app.js`
- Test: `tests/test_web_assets.py` (structural assertions)

**Interfaces:**
- Produces: a theme toggle in the header (`#theme-toggle`), `applyTheme()`/`toggleTheme()` in app.js, and `:root[data-theme="dark"]` tokens in style.css.

- [ ] **Step 1: Add structural assertions (they fail first)**

Append to `tests/test_web_assets.py`:
```python
def test_dark_theme_tokens_present():
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert '[data-theme="dark"]' in css
    assert "#15171c" in css.lower()          # dark paper token

def test_theme_toggle_present():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert 'id="theme-toggle"' in html
```

- [ ] **Step 2: Run to verify fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q`
Expected: FAIL — no dark tokens / toggle yet.

- [ ] **Step 3: Add the dark token block to `hearth/web/style.css`**

Immediately after the existing `:root { ... }` block, add:
```css
:root[data-theme="dark"] {
  --paper: #15171c; --card: #1d2026; --ink: #e7e9ee; --ink-2: #b6bac2;
  --muted: #868b95; --faint: #5c616b; --line: #2b2f37; --line-2: #23262d;
  --fill: #262a32;
}
html { color-scheme: light dark; }
```
Audit hardcoded light-only colors so dark reads correctly:
- Any `background: #fff` / `color: #fff` on chrome (NOT the story viewer, which stays black) → use `var(--card)` / `var(--ink)` or, for solid buttons, keep white text on the accent (`.btn.solid`, `#compose button[type=submit]` etc. use `color: var(--card)` in light which is white; in dark `--card` is dark, so buttons on a colored accent need explicit white). Change accent/ink solid-button text to a fixed readable value: set `#compose button[type=submit], #dm-compose button[type=submit] { background: var(--ink); color: var(--paper); }` — in light that's dark-on-... no. Simplest robust rule: solid action buttons use `background: var(--ink); color: var(--paper);` so they invert cleanly per theme (dark ink text on light paper button in dark theme; dark button with light text in light theme). Verify each solid button uses this pair.
- `.bubble.me` currently mixes `--me` with `#fff`; change the mix base to `var(--card)` so it tints correctly in both themes: `background: color-mix(in srgb, var(--me) 16%, var(--card));`.
- `#qr-img` keeps `background:#fff` (QR must stay black-on-white to scan) — leave it.

- [ ] **Step 4: Add the toggle to `hearth/web/index.html`**

In the header, before the `.who` div, add:
```html
  <button id="theme-toggle" class="icon-btn" title="Toggle theme"
    aria-label="Toggle light/dark theme"></button>
```
(The sun/moon icon is injected by app.js so it can swap with the theme.)

- [ ] **Step 5: Add theme logic to `hearth/web/app.js`**

Near the top (after the `el`/`j` helpers), add:
```javascript
const SUN = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" '
  + 'stroke="currentColor" stroke-width="2" stroke-linecap="round">'
  + '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2'
  + 'M5 5l1.5 1.5M17.5 17.5L19 19M19 5l-1.5 1.5M6.5 17.5L5 19"/></svg>';
const MOON = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" '
  + 'stroke="currentColor" stroke-width="2" stroke-linecap="round" '
  + 'stroke-linejoin="round"><path d="M21 12.8A9 9 0 1 1 11.2 3 '
  + 'a7 7 0 0 0 9.8 9.8z"/></svg>';

function applyTheme(t) {
  document.documentElement.dataset.theme = t;
  const btn = document.getElementById("theme-toggle");
  if (btn) btn.innerHTML = (t === "dark") ? SUN : MOON;
}
function currentTheme() {
  return localStorage.getItem("loop_theme")
    || (window.matchMedia("(prefers-color-scheme: dark)").matches
        ? "dark" : "light");
}
function toggleTheme() {
  const next = (document.documentElement.dataset.theme === "dark")
    ? "light" : "dark";
  localStorage.setItem("loop_theme", next);
  applyTheme(next);
}
```
Wire it: after the toggle exists in the DOM (at the tail where other handlers are set), add:
```javascript
applyTheme(currentTheme());
document.getElementById("theme-toggle").onclick = toggleTheme;
```
Place `applyTheme(currentTheme())` as early as practical (right after the helpers or at the tail before `refresh()`); the button-icon set is guarded by `if (btn)`.

- [ ] **Step 6: Add `.icon-btn` styling to `hearth/web/style.css`**

```css
.icon-btn { display: inline-grid; place-items: center; width: 34px;
  height: 34px; border-radius: 9px; border: 1px solid var(--line);
  background: var(--card); color: var(--ink-2); cursor: pointer; padding: 0; }
.icon-btn:hover { color: var(--ink); border-color: var(--ink-2); }
.icon-btn svg { display: block; }
```

- [ ] **Step 7: Run tests + demo smoke**

Run: `.\.venv\Scripts\python.exe -m pytest tests -q`
Expected: zero failures (structural asserts pass).
Demo smoke: `.\.venv\Scripts\python.exe -m hearth demo` (background, ~8s); open http://127.0.0.1:7201, click the header toggle → whole UI flips to neutral dark, reload keeps it. Kill demo, delete `run/`.

- [ ] **Step 8: Commit**

```bash
git add hearth/web/style.css hearth/web/index.html hearth/web/app.js tests/test_web_assets.py
git commit -m "feat: neutral dark mode with persisted header toggle"
```

---

### Task 3: Styled controls, inline icons, focus-visible

**Files:**
- Modify: `hearth/web/index.html`, `hearth/web/style.css`, `hearth/web/app.js`
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Produces: an `ICONS` object in app.js (`ICONS.attach/send/plus/camera` inline SVG strings, `currentColor`-tinted); `.file-btn` styled labels wrapping file inputs; a styled `select`; a global `:focus-visible` ring. Existing ids (`photos`, `dm-photos`, compose/dm submit) preserved.

- [ ] **Step 1: Structural assertions (fail first)**

Append to `tests/test_web_assets.py`:
```python
def test_focus_visible_and_file_btn_styles_present():
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert ":focus-visible" in css
    assert ".file-btn" in css

def test_no_raw_file_input_in_compose_markup():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    # file inputs are wrapped in a .file-btn label, not bare in a compose-row
    assert 'class="file-btn"' in html
```

- [ ] **Step 2: Run to verify fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q`
Expected: FAIL.

- [ ] **Step 3: Wrap the two markup file inputs in `hearth/web/index.html`**

In `#compose`, replace:
```html
        <input type="file" id="photos" accept="image/*" multiple>
```
with:
```html
        <label class="file-btn"><span class="fi-label">Photo</span>
          <input type="file" id="photos" accept="image/*" multiple hidden></label>
```
In `#dm-compose`, replace:
```html
          <input type="file" id="dm-photos" accept="image/*" multiple>
```
with:
```html
          <label class="file-btn"><span class="fi-label">Photo</span>
            <input type="file" id="dm-photos" accept="image/*" multiple hidden></label>
```
(App.js's existing `document.getElementById("photos")`/`("dm-photos")` still find the inputs; `hidden` only removes default chrome.)

- [ ] **Step 4: Add the icon set + inject icons in `hearth/web/app.js`**

Near the SUN/MOON consts, add:
```javascript
const ICONS = {
  attach: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" '
    + 'stroke="currentColor" stroke-width="2" stroke-linecap="round" '
    + 'stroke-linejoin="round"><path d="M21 8l-9.5 9.5a3.5 3.5 0 0 1-5-5'
    + 'L15 3.5a2.5 2.5 0 0 1 3.5 3.5L9 16"/></svg>',
  send: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" '
    + 'stroke="currentColor" stroke-width="2" stroke-linecap="round" '
    + 'stroke-linejoin="round"><path d="M22 2L11 13M22 2l-7 20-4-9-9-4z"/></svg>',
  plus: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" '
    + 'stroke="currentColor" stroke-width="2" stroke-linecap="round">'
    + '<path d="M12 5v14M5 12h14"/></svg>',
};
```
At the tail (after DOM is ready), prepend an attach icon into each `.fi-label`'s parent label and put a send icon in the submit buttons:
```javascript
document.querySelectorAll(".file-btn").forEach(l => {
  l.insertAdjacentHTML("afterbegin", ICONS.attach);
});
const composeBtn = document.querySelector("#compose button[type=submit]");
if (composeBtn) composeBtn.innerHTML = ICONS.send + "<span>Post</span>";
const dmBtn = document.querySelector("#dm-compose button[type=submit]");
if (dmBtn) dmBtn.innerHTML = ICONS.send + "<span>Send</span>";
```

- [ ] **Step 5: Styling in `hearth/web/style.css`**

Add:
```css
/* focus ring (kills the browser default orange) */
:focus { outline: none; }
:focus-visible { outline: 2px solid var(--me);
  outline-offset: 2px; border-radius: 8px; }
/* styled file button */
.file-btn { display: inline-flex; align-items: center; gap: 6px;
  padding: 8px 12px; border: 1px solid var(--line); border-radius: 8px;
  background: var(--card); color: var(--ink-2); font-weight: 600;
  font-size: 13px; cursor: pointer; }
.file-btn:hover { border-color: var(--ink-2); color: var(--ink); }
.file-btn svg { display: block; }
/* styled select */
select { appearance: none; -webkit-appearance: none; padding-right: 30px;
  background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%236b7079' stroke-width='2' stroke-linecap='round'><path d='M6 9l6 6 6-6'/></svg>");
  background-repeat: no-repeat; background-position: right 10px center; }
/* submit buttons with icon + label */
#compose button[type=submit], #dm-compose button[type=submit] {
  display: inline-flex; align-items: center; gap: 6px; }
```
Remove the now-dead `.compose-row input[type=file]` rule (the input is hidden inside a label).

- [ ] **Step 6: Run tests + demo smoke**

Run: `.\.venv\Scripts\python.exe -m pytest tests -q`
Expected: zero failures.
Demo smoke: open the feed — the attach control is a clean "Photo" pill with a paperclip; Post/Send show an arrow icon; tabbing shows a tidy focus ring, not the orange default; the expiry select has a custom caret. Kill demo, delete run/.

- [ ] **Step 7: Commit**

```bash
git add hearth/web/index.html hearth/web/style.css hearth/web/app.js tests/test_web_assets.py
git commit -m "feat: styled file buttons, inline SVG icons, focus-visible ring, styled select"
```

---

### Task 4: Messages rebuilt as a real chat

**Files:**
- Modify: `hearth/web/index.html`, `hearth/web/style.css`, `hearth/web/app.js`
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: existing `openThread`, `loadConversations`, `CURRENT_DM`/`CURRENT_DM_NAME`, `#conversations`, `#thread`, `#thread-title`, `#dm-compose`.
- Produces: a full-height chat layout with the compose bar pinned to the bottom of the thread column, conversation-list avatars, empty states. No behavior change to DM send/receive.

- [ ] **Step 1: Structural assertions (fail first)**

Append to `tests/test_web_assets.py`:
```python
def test_messages_chat_layout_present():
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert ".thread-col" in css and ".dm-compose-bar" in css
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "Pick a conversation" in js        # empty state copy
```

- [ ] **Step 2: Run to verify fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q`
Expected: FAIL.

- [ ] **Step 3: Restructure the messages markup in `hearth/web/index.html`**

Replace the whole `#view-messages` block with a fixed-height chat shell (compose wrapped in a `.dm-compose-bar` at the bottom of the thread column):
```html
<div id="view-messages" class="hidden">
  <div class="dm-shell">
    <aside class="conv-col"><h2>Conversations</h2>
      <div id="conversations"></div></aside>
    <section class="thread-col">
      <div id="thread-title" class="thread-title">Messages</div>
      <div id="thread" class="thread-scroll"></div>
      <form id="dm-compose" class="dm-compose-bar hidden">
        <label class="file-btn"><span class="fi-label">Photo</span>
          <input type="file" id="dm-photos" accept="image/*" multiple hidden></label>
        <textarea id="dm-text" rows="1"
          placeholder="Encrypted message..."></textarea>
        <button type="submit">Send</button>
      </form>
    </section>
  </div>
</div>
```
(Keeps every id app.js uses: `conversations`, `thread`, `thread-title`, `dm-compose`, `dm-photos`, `dm-text`. The Task-3 attach-icon injection and send-icon injection still apply since the classes/selectors are the same.)

- [ ] **Step 4: Chat layout CSS in `hearth/web/style.css`**

Replace the old `.dm-layout`, `.conv`, `.bubble` rules region with:
```css
.dm-shell { display: grid; grid-template-columns: 260px minmax(0,1fr);
  gap: 0; max-width: 1000px; margin: 0 auto;
  height: calc(100vh - 60px); border-bottom: 1px solid var(--line); }
.conv-col { border-right: 1px solid var(--line); padding: 16px 12px;
  overflow-y: auto; }
.thread-col { display: flex; flex-direction: column; min-height: 0; }
.thread-title { padding: 14px 18px; border-bottom: 1px solid var(--line);
  font-weight: 700; }
.thread-scroll { flex: 1; overflow-y: auto; padding: 16px 18px;
  display: flex; flex-direction: column; }
.dm-empty { margin: auto; text-align: center; color: var(--muted);
  font-size: 14px; }
.conv { display: flex; gap: 10px; align-items: center; padding: 9px 10px;
  border-radius: 10px; cursor: pointer; }
.conv:hover, .conv.active { background: var(--fill); }
.conv-avatar { width: 38px; height: 38px; border-radius: 50%; flex-shrink: 0;
  background: var(--me); color: #fff; display: grid; place-items: center;
  font-weight: 700; overflow: hidden; }
.conv-avatar img { width: 100%; height: 100%; object-fit: cover; }
.conv .name { font-weight: 650; font-size: 14px; }
.conv .preview { font-size: 12px; color: var(--muted);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 170px; }
.bubble { background: var(--fill); border: 1px solid var(--line-2);
  border-radius: 14px; padding: 8px 12px; margin: 4px 0; max-width: 74%;
  width: fit-content; }
.bubble.me { margin-left: auto;
  background: color-mix(in srgb, var(--me) 16%, var(--card));
  border-color: color-mix(in srgb, var(--me) 30%, var(--line)); }
.bubble img { max-width: 100%; border-radius: 8px; margin-top: 6px; }
.bubble .bt { font-size: 10px; color: var(--faint); margin-top: 3px; }
.bubble .undec { font-style: italic; color: var(--muted); }
.dm-compose-bar { display: flex; gap: 8px; align-items: center;
  padding: 12px 14px; border-top: 1px solid var(--line); background: var(--card); }
.dm-compose-bar textarea { flex: 1; resize: none; }
@media (max-width: 720px) { .dm-shell { grid-template-columns: 1fr; } }
```

- [ ] **Step 5: Update the chat rendering in `hearth/web/app.js`**

Rewrite `loadConversations` to draw avatar rows + active highlight + empty state:
```javascript
async function loadConversations() {
  const convs = await j("/api/conversations");
  const root = document.getElementById("conversations");
  root.replaceChildren();
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
    const row = el("div", "conv" + (c.identity_pub === CURRENT_DM ? " active" : ""));
    const av = el("div", "conv-avatar");
    av.textContent = (c.name || "?").slice(0, 1).toUpperCase();
    const txt = el("div");
    txt.append(el("div", "name", c.name),
               el("div", "preview", c.last_text || "no messages yet"));
    row.append(av, txt);
    row.onclick = () => openThread(c.identity_pub, c.name);
    root.append(row);
  }
}
```
In `openThread`, after building the thread, show an empty state when there are no messages, add per-bubble timestamps, and re-highlight the conversation list. Replace the bubble-render loop body so each bubble appends a `.bt` timestamp, and after the loop:
```javascript
  if (!msgs.length) {
    const e = el("div", "dm-empty", "No messages yet - say hi.");
    root.append(e);
  }
  loadConversations();   // refresh the active highlight
```
And where the thread is first shown with nothing selected (initial state), set `#thread` to an empty-state prompt. Add to `setView` (in the messages branch) or `restoreView`: if `!CURRENT_DM`, set `document.getElementById("thread").innerHTML = ''` and append `el("div","dm-empty","Pick a conversation to start.")`, and keep `#dm-compose` hidden. Concretely, add a helper and call it from the messages-tab handler:
```javascript
function dmPlaceholder() {
  const t = document.getElementById("thread");
  t.replaceChildren(el("div", "dm-empty", "Pick a conversation to start."));
  document.getElementById("dm-compose").classList.add("hidden");
  document.getElementById("thread-title").textContent = "Messages";
}
```
Call `dmPlaceholder()` at the end of the `tab-messages` click handler when `!CURRENT_DM`, and in `restoreView` if messages view but no stored `hearth_dm`.

- [ ] **Step 6: Run tests + demo smoke**

Run: `.\.venv\Scripts\python.exe -m pytest tests -q`
Expected: zero failures.
Demo smoke: open Messages — the thread fills the pane, compose sits at the bottom, "Pick a conversation to start" shows initially; open Freja → seeded DM bubbles render (own accent-tinted right, hers left) with timestamps and the bottom compose. Post a DM and see it appear. Kill demo, delete run/.

- [ ] **Step 7: Commit**

```bash
git add hearth/web/index.html hearth/web/style.css hearth/web/app.js tests/test_web_assets.py
git commit -m "feat: Messages rebuilt as a real chat - bubbles, bottom compose, empty states"
```

---

### Task 5: Empty states, friend-ceremony collapse, Me card, wider accent picker

**Files:**
- Modify: `hearth/web/app.js`, `hearth/web/index.html`, `hearth/web/style.css`
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: `ceremonyUI` (`#ceremony`), `renderFeed`/`#feed`, `renderState` (Me panel), `profileEditor` (`ACCENTS` array + swatches), `openProfile`.
- Produces: feed empty-state; ceremony collapsed behind an "Add friend" toggle; a richer "Me" sidebar card; an expanded 16-swatch palette + a custom-hex `<input type="color">` in the editor.

- [ ] **Step 1: Structural assertions (fail first)**

Append to `tests/test_web_assets.py`:
```python
def test_accent_picker_and_addfriend_present():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert 'type="color"' in js or "createElement(\"input\")" in js
    assert "Add friend" in js
    assert js.count("#") >= 16 or "ACCENTS = [" in js   # expanded palette
```

- [ ] **Step 2: Run to verify fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q`
Expected: FAIL (Add friend / color input not present yet).

- [ ] **Step 3: Expand the palette + add custom-hex in `hearth/web/app.js`**

Replace the existing `ACCENTS` array with 16 suggestions:
```javascript
const ACCENTS = ["#2743d6","#c0563b","#3e7c55","#8a5cd0","#17191e",
  "#1f8a8a","#c79a2e","#c0567e","#4a5568","#7a4e8a",
  "#2f80ed","#c0392b","#e2725b","#b52d8c","#1e6b5a","#6a4bb0"];
```
In `profileEditor`, after the swatch loop that builds `sws`, append a custom-color control that updates `accent` live:
```javascript
  const custom = document.createElement("input");
  custom.type = "color"; custom.value = /^#[0-9a-f]{6}$/.test(accent)
    ? accent : "#2743d6"; custom.className = "sw-custom";
  custom.oninput = () => { accent = custom.value.toLowerCase();
    [...sws.children].forEach(x => x.classList.remove("on"));
    box.closest(".prof-wrap").style.setProperty("--me", accent); };
  sws.append(custom);
```
(The save handler already sends `accent`; ensure it lowercases: where it does `fd.append("accent", accent)`, change to `fd.append("accent", accent.toLowerCase())`.)

- [ ] **Step 4: Collapse the friend ceremony in `hearth/web/app.js`**

In `ceremonyUI`, wrap the four step buttons + textarea in a container hidden by default, and add an "Add friend" toggle button that reveals it. At the start of `ceremonyUI`, after `root.replaceChildren()`:
```javascript
  const toggle = el("button", "btn-accent", "Add friend");
  const panel = el("div", "ceremony-panel hidden");
  toggle.onclick = () => panel.classList.toggle("hidden");
  root.append(toggle, panel);
```
Then append the textarea/buttons/status into `panel` instead of `root` (change the final `root.append(ta, mk(...), ..., status)` to `panel.append(ta, mk(...), ..., status)`).

- [ ] **Step 5: Feed empty state + Me card in `hearth/web/app.js`**

In `renderFeed`, at the top after `root.replaceChildren()`, if `!feed.length`:
```javascript
  if (!feed.length) {
    root.append(el("div", "empty-feed",
      "Nothing here yet - say something to your people, or add a friend."));
    return;
  }
```
In `renderState`, enrich the "Me" panel: it currently sets `#identity-pub` and (Task from profiles) has an `edit-profile` button. Add an avatar + name line at the top of the Me panel. In index.html the Me panel is:
```html
    <div class="panel">
      <h2>Me</h2>
      <button id="edit-profile">Edit profile</button>
      <div class="dim tiny" id="identity-pub"></div>
    </div>
```
Change it to include a header slot:
```html
    <div class="panel">
      <h2>Me</h2>
      <div id="me-card" class="me-card"></div>
      <button id="edit-profile">Edit profile</button>
      <div class="dim tiny" id="identity-pub"></div>
    </div>
```
In `renderState`, populate `#me-card` from `STATE` (profile_name + a small avatar initial; avatar image if the state exposes it — else initial):
```javascript
  const meCard = document.getElementById("me-card");
  if (meCard) {
    meCard.replaceChildren();
    const av = el("div", "me-av");
    av.textContent = (STATE.profile_name || "?").slice(0, 1).toUpperCase();
    meCard.append(av, el("div", "me-name", STATE.profile_name || ""));
  }
```

- [ ] **Step 6: Styling in `hearth/web/style.css`**

```css
.empty-feed { text-align: center; color: var(--muted); font-size: 14px;
  padding: 40px 20px; border: 1px dashed var(--line); border-radius: 14px;
  background: var(--card); }
.ceremony-panel { margin-top: 10px; }
#ceremony > .btn-accent { width: 100%; }
.me-card { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.me-av { width: 40px; height: 40px; border-radius: 50%; background: var(--me);
  color: #fff; display: grid; place-items: center; font-weight: 700; }
.me-name { font-weight: 700; }
.sw-custom { width: 28px; height: 28px; padding: 0; border: 1px solid var(--line);
  border-radius: 50%; background: none; cursor: pointer; overflow: hidden; }
.sw-custom::-webkit-color-swatch { border: none; border-radius: 50%; }
.sw-custom::-webkit-color-swatch-wrapper { padding: 0; }
```

- [ ] **Step 7: Run tests + demo smoke**

Run: `.\.venv\Scripts\python.exe -m pytest tests -q`
Expected: zero failures.
Demo smoke: feed with no posts shows the empty-feed card; the Friends panel shows a single "Add friend" button that expands the step flow; the Me panel shows an avatar + name; open Me -> Edit profile -> the accent row has 16 swatches plus a color picker, and choosing a custom color updates the profile accent live and saves. Kill demo, delete run/.

- [ ] **Step 8: Commit**

```bash
git add hearth/web/app.js hearth/web/index.html hearth/web/style.css tests/test_web_assets.py
git commit -m "feat: feed/chat empty states, Add-friend collapse, Me card, 16-swatch + custom-hex accent"
```

---

## Plan self-review notes

- **Spec coverage:** accent-hex validation + tests (T1); neutral dark tokens + toggle + persist + hardcoded-color audit (T2); styled file buttons + inline SVG icons + focus-visible + styled select (T3); Messages real-chat rebuild with bubbles/bottom-compose/empty-states (T4); feed empty state + friend-ceremony collapse + Me card + 16-swatch-plus-custom-hex picker (T5). Logo tweak and layout/font/density customization explicitly out of scope per spec.
- **Behavior preserved:** every element id app.js depends on is retained across the markup changes (compose `photos`, `dm-photos`, `dm-text`, `conversations`, `thread`, `thread-title`, `dm-compose`, `edit-profile`, `identity-pub`, `theme-toggle`). The only logic change is accent validation (T1), tested.
- **Type/name consistency:** `--me` accent, `ICONS.attach/send/plus`, `.file-btn`, `.dm-compose-bar`/`.thread-col`/`.conv-avatar`, `ACCENTS` (16 lowercase hex), `applyTheme/toggleTheme/currentTheme` used consistently across tasks.
- **Dark-mode audit (T2) is the main risk:** solid buttons and `.bubble.me` hardcoded `#fff` — T2 changes them to theme-aware values; the story viewer intentionally stays black; `#qr-img` intentionally stays white (scannability). The T2 demo smoke must be done in BOTH themes.
- **Visual tasks are gated by structural asserts + demo smoke, not pixel tests** — the user verifies final look in the live demo (both themes). Each task keeps the full suite green.
- **Known risk:** `test_web_assets.py`'s existing `test_no_stray_hearth`/token tests must still pass — T2 adds tokens (doesn't remove), T3-5 don't touch the wordmark or the "Loop" name, so those remain green.
