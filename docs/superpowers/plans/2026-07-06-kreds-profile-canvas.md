# Kreds Profile Block Canvas (Slice 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the profile into a block canvas — profile posts render as typed blocks (text; photo: one big, several as a gallery), the composer gains photo upload, and the Journal moves to a compact right-column rail (desktop) / in-page disclosure (mobile) with the self-only Friends/Devices below it.

**Architecture:** Front-end only. Blocks = the existing `placement=profile` posts (already per-recipient encrypted, photo-capable). Type is inferred at render from content. No protocol/store change.

**Tech Stack:** vanilla-JS client, pytest asset tests, `node --check`. No backend change.

**Spec:** `docs/superpowers/specs/2026-07-06-kreds-profile-canvas-design.md`

## Global Constraints

- Branch: `kreds-profile-canvas` off `main` (already created + checked out — do NOT re-branch).
- Test runner: `timeout 150 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3`; full suite green each commit (TOR_E2E skip expected). `node --check hearth/web/app.js` clean.
- NO backend/API/protocol/store change. Profile posts already accept photos + `placement=profile`; `/api/profile` already returns `wall` + `journal` (decrypt-filtered).
- Block type inferred from content: text-only → text block; has photos → photo block (1 photo big, several = gallery). No stored `block_type`, no arrangement record this slice (Slices 2–3).
- Blocks render newest-first (by `created_at`). Drag-to-arrange is Slice 2.
- Friends/Devices remain self-only (`p.mine`); visitors see the canvas + Journal rail only, never Friends/Devices. Honesty guard: no receipts popover. Deletion routes through the existing `deleteEverywhere` + profile re-render.
- Inner/Kreds scope selector on the profile composer stays (default `kreds`).

---

### Task 1: Profile composer photos + block-canvas renderer

**Files:**
- Modify: `hearth/web/app.js` (`profilePostComposer` photo attach; new `renderBlock`; wall render uses it)
- Modify: `hearth/web/style.css` (block canvas: text block, big photo, gallery grid)
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: `/api/post` (accepts `photos[]` + `placement=profile` already), `/api/post-blob/{msg_id}/{h}`, `p.wall` rows (with `text`, `blobs`, `created_at`, `mine`, `msg_id`), `deleteEverywhere`, `openProfile`, `CURRENT_PROFILE`.
- Produces: `renderBlock(p)` → a canvas block element; profile Wall renders via `renderBlock`; composer sends photos.

- [ ] **Step 1: Failing asset tests** — append to `tests/test_web_assets.py`:

```python
def test_profile_composer_has_photos_and_block_canvas():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    # composer attaches photos to the profile post
    assert "profilePostComposer" in js
    assert 'fd.append("photos"' in js or "fd.append('photos'" in js
    # a dedicated block renderer exists and the wall uses it (not buildEntry)
    assert "function renderBlock" in js
    # photo block distinguishes one (big) vs several (gallery)
    assert "block-photo" in css and "block-gallery" in css
```

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: `profilePostComposer` — add a photo picker**

In `profilePostComposer()` (app.js ~687), add a hidden file input + a label button in the composer bar, and append its files on submit. Mirror the journal composer (`index.html` `#post-photos` + the `/api/post` submit at app.js ~1144). Concretely, add before the Post button:

```javascript
  const photoLabel = el("label", "keep");
  photoLabel.textContent = "Photo";
  const photoInput = document.createElement("input");
  photoInput.type = "file"; photoInput.accept = "image/*"; photoInput.multiple = true;
  photoInput.hidden = true;
  photoLabel.append(photoInput);
  bar.append(photoLabel);
```

And in the form's submit handler, after appending text/scope/placement:

```javascript
    for (const f of photoInput.files) fd.append("photos", f);
```

Clear `photoInput.value = ""` alongside the text reset on success. (Keep the existing `placement=profile`, scope, and `!r.ok` alert handling.)

- [ ] **Step 4: `renderBlock` + wall uses it**

Add a canvas block renderer (distinct from the compact `buildEntry`):

```javascript
// Profile canvas block: a profile post rendered by inferred type - text, or
// photo (one big, several as a gallery). Distinct from the compact journal
// buildEntry(). Delete-everywhere (self) routes through the shared helper.
function renderBlock(p) {
  const block = el("article", "block");
  if (p.blobs && p.blobs.length) {
    const media = el("div", p.blobs.length === 1 ? "block-photo" : "block-gallery");
    for (const h of p.blobs) {
      const img = document.createElement("img");
      img.src = "/api/post-blob/" + p.msg_id + "/" + h;
      media.append(img);
    }
    block.append(media);
  } else {
    block.classList.add("block-text");
  }
  if (p.text) block.append(el("p", "block-text-body", p.text));
  if (p.mine) {
    const del = el("button", "pact del", "Delete everywhere");
    del.onclick = async () => {
      if (!await deleteEverywhere(p.msg_id)) return;
      await refresh();
      if (currentView() === "profile" && CURRENT_PROFILE) openProfile(CURRENT_PROFILE);
    };
    block.append(del);
  }
  return block;
}
```

Change the Wall render loop in `renderProfilePage` (app.js ~658-661) from `buildEntry` to `renderBlock`, and update the empty-state copy:

```javascript
  const wall = document.getElementById("profile-wall");
  wall.replaceChildren();
  if (!p.wall.length) wall.append(el("div", "hint",
    p.mine ? "Your profile is a blank canvas - post something above." : "Nothing here yet."));
  for (const post of p.wall) wall.append(renderBlock(post));
```

(Leave the `#profile-journal` render as-is for now — Task 2 relocates it.)

- [ ] **Step 5: style.css — block canvas**

Add token-based styles:

```css
.block { margin: 0 0 20px; }
.block-photo img { width: 100%; border-radius: 12px; display: block; }
.block-gallery { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; }
.block-gallery img { width: 100%; aspect-ratio: 1/1; object-fit: cover; border-radius: 10px; }
.block-text-body { font-size: 15px; line-height: 1.5; white-space: pre-wrap; }
.block .del { margin-top: 8px; }
```

- [ ] **Step 6: Run asset tests + node --check + full suite** — all pass.

- [ ] **Step 7: Commit**

```powershell
git add hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat: profile composer photo upload + block-canvas render (text/photo blocks)"
```

---

### Task 2: Journal → right-column rail; self Friends/Devices below; mobile disclosure

**Files:**
- Modify: `hearth/web/index.html` (move `#profile-journal` into the right column; restructure `#profile-side`)
- Modify: `hearth/web/app.js` (`renderProfilePage`: right column always shown with the journal rail; Friends/Devices gated to `p.mine`; mobile journal disclosure)
- Modify: `hearth/web/style.css` (rail + always-on layout + mobile disclosure)
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: `p.journal` (decrypt-filtered), `renderMeStrip` (friends/devices/ceremony), `buildEntry` (compact journal entries).
- Produces: right column shows a Journal rail for every profile; Friends/Devices only for self; the inline main-area Journal section is gone.

- [ ] **Step 1: Failing asset tests** — append:

```python
def test_journal_rail_and_self_only_friends():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    # journal rail lives in the right column; the main area no longer holds it
    assert 'id="profile-journal-rail"' in html
    # a journal disclosure control for mobile exists
    assert 'profile-journal-toggle' in html or 'profile-journal-toggle' in js
    # right column shows for everyone (journal), friends/devices gated to mine
    assert 'renderMeStrip' in js
```

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: index.html — restructure the right column**

Move `#profile-journal` out of the main `#profile-body` into `#profile-side`, renamed to a rail, with a mobile disclosure button; keep Friends/Devices below it. The `#profile-side` aside becomes (always rendered — the `hidden` class is removed; JS toggles the friends/devices sub-panels):

```html
        <aside class="profile-side" id="profile-side">
          <div class="panel journal-rail">
            <button class="journal-toggle" id="profile-journal-toggle" aria-expanded="true">Journal</button>
            <div id="profile-journal-rail"></div>
          </div>
          <div class="panel selfonly" id="profile-friends-panel">
            <h2>Friends</h2>
            <div id="friends"></div>
            <div id="ceremony"></div>
          </div>
          <div class="panel selfonly" id="profile-devices-panel">
            <h2>Devices</h2>
            <div id="devices"></div>
          </div>
        </aside>
```

Remove the old `#profile-journal` block from `#profile-body` (the main area now holds only compose + `#profile-wall`). Keep `#profile-wall-compose` + `#profile-wall`.

- [ ] **Step 4: app.js — render the rail; gate friends/devices; mobile toggle**

In `renderProfilePage`, replace the side-strip visibility logic (app.js ~605-609) and the journal render (~663-666):

```javascript
  // Right column: the Journal rail shows on EVERY profile; Friends/Devices
  // panels are self-only. The Back button still hides on your own profile.
  document.getElementById("profile-back").classList.toggle("hidden", !!p.mine);
  document.getElementById("profile-layout").classList.add("has-side");   // always two-col on desktop
  document.querySelectorAll("#profile-side .selfonly").forEach(el2 =>
    el2.classList.toggle("hidden", !p.mine));
  if (p.mine) renderMeStrip();

  const rail = document.getElementById("profile-journal-rail");
  rail.replaceChildren();
  if (!p.journal.length) rail.append(el("div", "hint", "No journal posts here."));
  for (const post of p.journal) rail.append(buildEntry(post));
```

Remove the old `#profile-journal` render block. Wire the mobile Journal disclosure once at load (toggles a class that shows/hides the rail body on narrow screens):

```javascript
document.getElementById("profile-journal-toggle").onclick = () => {
  const rail = document.getElementById("profile-journal-rail");
  const btn = document.getElementById("profile-journal-toggle");
  const open = rail.classList.toggle("open");
  btn.setAttribute("aria-expanded", String(open));
};
```

- [ ] **Step 5: style.css — rail, always-on layout, mobile disclosure**

```css
.journal-rail .journal-toggle { display: none; }   /* desktop: rail always open */
.journal-rail #profile-journal-rail { display: block; }
.profile-side .journal-rail { max-height: 60vh; overflow-y: auto; }
@media (max-width: 820px) {
  /* mobile: collapse the rail behind the Journal button */
  .journal-rail .journal-toggle { display: block; width: 100%; }
  .journal-rail #profile-journal-rail { display: none; }
  .journal-rail #profile-journal-rail.open { display: block; }
}
```

(Confirm `.has-side` two-column grid from the prior slice still applies; the right column now always shows.)

- [ ] **Step 6: Run asset tests + node --check + full suite** — update any test referencing the old `#profile-journal` main-area section. All pass.

- [ ] **Step 7: Commit**

```powershell
git add hearth/web/index.html hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat: Journal moves to a right-column rail (mobile disclosure); Friends/Devices self-only below it"
```

---

### Task 3: Integration smoke + docs

**Files:**
- Modify: `README.md`, `ROADMAP.md`
- Test: manual/Playwright smoke

- [ ] **Step 1: Full suite + JS check** — `timeout 150 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3` all pass; `node --check hearth/web/app.js` clean. Grep confirms no stale main-area `#profile-journal` render (only `#profile-journal-rail`).

- [ ] **Step 2: Playwright/HTTP smoke (record outcome)** — Playwright is installed. Isolated node + a friend on FREE ports (NOT demo ports). Confirm: attach a photo in the profile composer → it posts and renders as a **photo block** (one photo big; add several → gallery grid) on the canvas, and is NOT in the home feed; a text profile post → text block; a journal post → appears in the **Journal rail** (not the canvas) + the home feed; on your own profile the right column shows the rail + Friends + Devices; a friend's profile shows the canvas + rail only (no Friends/Devices) + a Back button; on a narrow viewport the Journal collapses behind the button and expands on tap. Record observations. If Playwright can't run, HTTP + DOM assertions, and say so.

- [ ] **Step 3: README + ROADMAP** — document the profile canvas (Slice 1): profile posts render as typed blocks (text/photo, big vs gallery), photo upload in the profile composer, Journal moved to a rail/disclosure with self-only Friends/Devices; note Slices 2 (arrange) + 3 (video, split columns, grids) still upcoming. Record as an increment on the curated-profile feature (no new number needed, or bump if preferred).

- [ ] **Step 4: Commit**

```powershell
git add README.md ROADMAP.md
git commit -m "docs: profile block canvas Slice 1 (typed blocks, photos, journal rail)"
```

---

## Completion

After Task 3: whole-branch review (superpowers:requesting-code-review) — focus: photo profile posts render as blocks and never leak into the home feed; a journal post never renders on the canvas; the Journal rail + Friends/Devices are correct (rail for everyone, Friends/Devices self-only — no leak to visitors); decrypt-filtering intact (only decryptable wall/journal shown); block delete re-renders the canvas; no backend change; honesty guard intact; no shipped behavior broken (home feed, DMs, cogwheel, unfriend, Me tab). Then superpowers:finishing-a-development-branch — merge `kreds-profile-canvas` to `main`, push. Next: Slice 2 (arrangement editor), then Slice 3 (video/split/grids), or Windows packaging.
