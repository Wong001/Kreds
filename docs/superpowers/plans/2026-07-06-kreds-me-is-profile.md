# Kreds "Me tab = your profile" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Me tab open your profile page directly, with a self-only Friends + Devices strip, retiring the card-summary view.

**Architecture:** Pure client change. The `#view-me` card view is removed; its Friends/Devices/ceremony panels move into a `#profile-side` region inside `#view-profile`. The Me nav/tab routes to `openProfile(self)`; the strip + two-column layout show only when `p.mine`; the Back button hides for self.

**Tech Stack:** vanilla-JS client, pytest asset tests, `node --check`. No backend change.

**Spec:** `docs/superpowers/specs/2026-07-06-kreds-me-is-profile-design.md`

## Global Constraints

- Branch: `kreds-me-is-profile` off `main` (already created + checked out — do NOT re-branch).
- Test runner: `timeout 150 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3`; full suite green at every commit (TOR_E2E skip expected). `node --check hearth/web/app.js` clean.
- No backend/API/protocol change. `renderProfilePage`, the composer, Wall/Journal, and the cogwheel editor overlay stay as they are.
- Friends + Devices strip renders ONLY when `p.mine`; others' profiles show no strip. Back button hidden when `p.mine`.
- Honesty guard unchanged: no receipts popover.
- Preserve behavior of the moved panels: friend rows → `openProfile`; device revoke → `/api/device/revoke`; Add-friend ceremony unchanged.

---

### Task 1: Me tab → profile page + self-only Friends/Devices strip

**Files:**
- Modify: `hearth/web/index.html` (remove `#view-me`; add `#profile-side` into `#view-profile`; make the profile view a two-column wrapper)
- Modify: `hearth/web/app.js` (`setView`, Me nav/tab handlers, `openProfile`, `renderProfilePage`, `renderMe`→strip, `restoreView`)
- Modify: `hearth/web/style.css` (two-column self-profile layout; strip)
- Test: `tests/test_web_assets.py` (extend)

**Interfaces:**
- Consumes: `openProfile`, `renderProfilePage`, `renderMe` (friend/device/ceremony rendering), `STATE.identity_pub`.
- Produces: Me tab renders `#view-profile` (self); `#profile-side` holds Friends+Devices, shown only for `p.mine`.

- [ ] **Step 1: Failing asset tests** — append to `tests/test_web_assets.py`:

```python
def test_me_tab_opens_profile_with_self_strip():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    # the card-summary Me view is gone; profile view gains a self-only side strip
    assert 'id="view-me"' not in html
    assert 'id="profile-side"' in html
    # Friends + Devices live in the profile side strip now
    assert 'id="friends"' in html and 'id="devices"' in html
    # Me nav + mobile Me tab route to the profile (openProfile), not a card view
    assert 'nav-me' in js and 'openProfile' in js
    # Back is gated on p.mine (hidden on your own profile)
    assert 'profile-back' in js
    # setView no longer carries a "me" view
    assert '"journal", "messages", "profile"' in js or "'journal', 'messages', 'profile'" in js
```

- [ ] **Step 2: Run — expect failure** — `pytest tests/test_web_assets.py::test_me_tab_opens_profile_with_self_strip -q` → FAIL.

- [ ] **Step 3: index.html — remove `#view-me`, add `#profile-side`, two-column profile**

Delete the entire `<div id="view-me" class="hidden"> … </div>` block (lines ~88-113).

Restructure `#view-profile` so the profile card and a self-only side strip sit in a two-column wrapper. Keep the topbar (Back + cog) above. Replace the current `#view-profile` opening structure so it reads:

```html
    <div id="view-profile" class="hidden">
      <div class="profile-topbar">
        <button class="profile-back" id="profile-back">&larr; Back</button>
        <button class="profile-cog hidden" id="profile-cog" aria-label="Edit profile" title="Edit profile">
          <!-- (existing cog SVG, unchanged) -->
        </button>
      </div>
      <div class="profile-layout" id="profile-layout">
        <div class="profile-page" id="profile-page">
          <!-- (existing banner/head/bio/meta/actions/body — UNCHANGED) -->
        </div>
        <aside class="profile-side hidden" id="profile-side">
          <div class="panel">
            <h2>Friends</h2>
            <div id="friends"></div>
            <div id="ceremony"></div>
          </div>
          <div class="panel">
            <h2>Devices</h2>
            <div id="devices"></div>
          </div>
        </aside>
      </div>
    </div>
```

Keep the existing `#profile-page` inner markup (banner, `#profile-avatar`, `#profile-name-view`, `#profile-hash`, `#profile-bio`, `#profile-meta`, `#profile-actions`, `#profile-body` with wall-compose/wall/journal) exactly as-is inside the new `#profile-page` div. Preserve the existing `#profile-cog` SVG.

- [ ] **Step 4: app.js — routing + strip + Back**

`setView` — drop `"me"` from the view list:

```javascript
function setView(which) {
  for (const v of ["journal", "messages", "profile"])
    document.getElementById("view-" + v).classList.toggle("hidden", v !== which);
  document.querySelectorAll(".navlinks button").forEach(b =>
    b.classList.toggle("active", b.dataset.view === which));
  if (which !== "profile") localStorage.setItem("hearth_view", which);
  if (which === "messages") { loadConversations(); if (!CURRENT_DM) dmPlaceholder(); }
}
```

Me nav + mobile Me tab route to the self profile (replace the `goView("me")` handlers):

```javascript
document.getElementById("nav-me").onclick = () => openMe();
```

And in the mobile tab handler, special-case `me`:

```javascript
document.querySelectorAll(".tabbar-mobile button").forEach(
  b => b.onclick = () => (b.dataset.tab === "me" ? openMe() : goView(b.dataset.tab)));
```

Add `openMe()`:

```javascript
function openMe() {
  closeEditOverlay();
  localStorage.setItem("hearth_view", "me");   // remembered across reloads
  if (STATE) openProfile(STATE.identity_pub);
}
```

In `openProfile`, when `p.mine`, also highlight the mobile Me tab + record the remembered view (the desktop Me nav active already happens for `p.mine`). After `setView("profile")` in the `p.mine` block:

```javascript
  if (p.mine) {
    const meTab = document.querySelector('.navlinks button[data-view="me"]');
    if (meTab) meTab.classList.add("active");
    document.querySelectorAll(".tabbar-mobile button").forEach(b =>
      b.classList.toggle("active", b.dataset.tab === "me"));
  }
```

`renderProfilePage` — show the strip + hide Back for self; hide strip + show Back for others. Near where the header/cog are set:

```javascript
  document.getElementById("profile-back").classList.toggle("hidden", !!p.mine);
  const side = document.getElementById("profile-side");
  side.classList.toggle("hidden", !p.mine);
  document.getElementById("profile-layout").classList.toggle("has-side", !!p.mine);
  if (p.mine) renderMeStrip();     // fills #friends / #devices / #ceremony
```

Rename `renderMe` → `renderMeStrip` and drop the `#me-card` population (the profile header already shows name/avatar); keep the friends-list + devices + ceremony logic exactly. Update its one call site in `refresh()` to `renderMeStrip()`, and guard it so it only runs when those elements exist / on self (calling it always is fine — the elements are in the DOM, just hidden when not self; but only re-render when relevant). Confirm `ceremonyUI()` still wires `#ceremony` (unchanged).

`restoreView` — a remembered `"me"` opens the self profile:

```javascript
  } else if (v === "me") {
    openMe();
  }
```

(Also drop any `goView("me")`/`setView("me")` references.)

- [ ] **Step 5: style.css — two-column self layout + strip**

```css
.profile-layout { max-width: 720px; margin: 0 auto; }
.profile-layout.has-side { max-width: 1040px; display: grid;
  grid-template-columns: 1fr 300px; gap: 20px; align-items: start; }
.profile-side .panel { margin-bottom: 16px; }
@media (max-width: 820px) { .profile-layout.has-side { grid-template-columns: 1fr; } }
```

(The existing `#view-profile` centering/padding stays; `.profile-page` keeps its own styles. Adjust the `#view-profile` wrapper padding if needed so the two-column layout isn't clipped. Reuse existing `.panel`/`.friend`/`.device` styles.)

- [ ] **Step 6: Run asset tests + node --check + full suite** — update any existing test that referenced `#view-me`, a `"me"` entry in `setView`, or `renderMe`. All pass; `node --check hearth/web/app.js` clean.

- [ ] **Step 7: Commit**

```powershell
git add hearth/web/index.html hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat: Me tab opens your profile directly; self-only Friends+Devices strip; card view retired"
```

---

### Task 2: Integration smoke + docs

**Files:**
- Modify: `README.md`, `ROADMAP.md`
- Test: manual/Playwright smoke

- [ ] **Step 1: Full suite + JS check** — `timeout 150 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3` all pass; `node --check hearth/web/app.js` clean. Grep confirms no stale `#view-me` / `renderMe(`/`setView("me")` references: `grep -rn "view-me\|renderMe\b\|\"me\"" hearth/web/app.js`.

- [ ] **Step 2: Playwright/HTTP smoke (record outcome)** — stand up an isolated node + a friend on free ports (NOT demo ports). Confirm: clicking Me opens your profile immediately (header + Wall + Journal) WITH a Friends + Devices strip; a friend's profile shows NO strip and a Back button; Add-friend and device-revoke work from the strip; the cogwheel opens/saves the editor; the mobile Me tab behaves the same. If Playwright is available use it; else HTTP + DOM assertions, and say so.

- [ ] **Step 3: README + ROADMAP** — note the Me tab now opens your profile directly with a self-only Friends/Devices strip; others see only the profile. A short line under the curated-profile section; ROADMAP note (this is a small IA refinement on feature 12 — record it, no new feature number needed, or bump if you prefer).

- [ ] **Step 4: Commit**

```powershell
git add README.md ROADMAP.md
git commit -m "docs: Me tab opens your profile (self-only Friends/Devices strip)"
```

---

## Completion

After Task 2: whole-branch review (superpowers:requesting-code-review) — focus: the Friends/Devices strip renders ONLY for `p.mine` (never leaks to a visitor's view of your profile); Add-friend/revoke/ceremony still work from the relocated strip; Back hidden for self / shown for others; Me nav + mobile tab + reload all land on the profile; no dead `#view-me`/`renderMe` references; honesty guard intact; no shipped behavior broken. Then superpowers:finishing-a-development-branch — merge `kreds-me-is-profile` to `main`, push. Next: block-layout profile builder, or Windows packaging.
