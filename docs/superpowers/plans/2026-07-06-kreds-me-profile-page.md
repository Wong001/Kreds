# Kreds Me-View Reorg + Full Profile Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the compact profile modal with a full `#view-profile` page that renders the uploaded banner + avatar shape/size/placement, and reorganize the Me view (friends + Devices into a smaller right column, heading → "Friends").

**Architecture:** Front-end-only change to the three web files; reuses the existing API (`/api/profile`, `/api/ring`, `/api/state`, `/api/blob`, `/api/post-blob`). `openProfile` switches from opening a modal to navigating a new profile view; the modal markup/CSS/code is removed. No protocol/store/API change.

**Tech Stack:** vanilla JS (no framework/bundler), CSS with Kreds design tokens, pytest asset tests, `node --check`.

**Spec:** `docs/superpowers/specs/2026-07-06-kreds-me-profile-page-design.md`

## Global Constraints

- Branch: `kreds-me-profile` off `main`. One workstream; nothing unrelated.
- Test runner (timeout-guarded — false-green history): `timeout 120 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3`. Full suite green at every task commit.
- `node --check hearth/web/app.js` clean at every commit touching it.
- ASCII only in Python console prints; UI copy may use non-ASCII (matches existing).
- **Honesty guards unchanged:** NO receipts "who holds this post" popover; profile posts render only what the viewer can decrypt (the server already returns only decryptable posts in `p.posts`).
- **Only** the Me-view friend-list heading changes to "Friends"; the circle, chips, and "Kreds" post scope keep their names.
- The compact profile modal (`#profile-modal` / `.modalback` / `.pmodal` / `.pm*` / dead `.pmpost`) is REMOVED — no dead markup/CSS/JS left.
- Self-edit is unified on the profile page: the Me-view "Edit profile" navigates to `openProfile(self)`; the old inline `#me-editor-slot` is removed.
- The profile page body below the head/actions is a clear container (`#profile-body`) so the later block-profile slice extends it rather than restructures.
- Reuse the shipped `buildEntry(post)` (journal entry renderer), `profileEditor(p)`, `identityColor(fp)`, `openThread`, `refresh`, `setView`.

---

### Task 1: Full profile page (`#view-profile`), retire the modal

**Files:**
- Modify: `hearth/web/index.html` (add `#view-profile`; remove `#profile-modal`)
- Modify: `hearth/web/app.js` (rewrite `openProfile`/`openProfileFallback`; add `profile` to `setView` + Back nav; remove modal close code)
- Modify: `hearth/web/style.css` (profile-page + avatar shape/size/placement CSS; remove `.modalback`/`.pmodal`/`.pm*` CSS)
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: `/api/profile/{id}` (returns name, bio, accent, avatar, avatar_shape, avatar_size, avatar_align, banner, identity_pub, mine, ring, since, posts), `/api/ring`, `identityColor`, `buildEntry`, `profileEditor`, `openThread`, `refresh`.
- Produces: `openProfile(identityPub)` renders the profile PAGE and makes it the active view; `setView` accepts `"profile"`; `PRIOR_VIEW` tracks where Back returns; `#profile-body` container for the later block slice.

- [ ] **Step 1: Create the branch**

```powershell
git checkout main; git pull; git checkout -b kreds-me-profile
```

- [ ] **Step 2: Write failing asset tests**

Append to `tests/test_web_assets.py`:

```python
def test_profile_is_a_page_not_a_modal():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert 'id="view-profile"' in html          # profile is a view/page
    assert 'id="profile-body"' in html          # block-slice foundation container
    # the compact modal is gone entirely
    assert 'id="profile-modal"' not in html
    assert ".modalback" not in css and ".pmodal" not in css
    assert "closeProfile" not in js             # modal close code removed


def test_profile_page_honors_banner_and_avatar_shape():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    # banner uses the uploaded image when present (references p.banner as a src)
    assert "p.banner" in js
    # avatar shape/size/placement are applied and styled
    assert "avatar_shape" in js and "avatar_size" in js and "avatar_align" in js
    assert "squircle" in css and "triangle" in css   # shape classes styled


def test_setview_supports_profile_and_back():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert '"profile"' in js                    # setView knows the profile view
    assert "PRIOR_VIEW" in js                    # Back returns to prior view
```

- [ ] **Step 3: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q`
Expected: FAIL (modal still present, no profile view).

- [ ] **Step 4: index.html — add the profile view, remove the modal**

Remove the entire `<div class="modalback" id="profile-modal">…</div>` block. Add a `#view-profile` view alongside the other views (inside `.appmain`, hidden by default):

```html
    <div id="view-profile" class="hidden">
      <button class="profile-back" id="profile-back">&larr; Back</button>
      <div class="profile-page" id="profile-page">
        <div class="profile-banner" id="profile-banner"></div>
        <div class="profile-head" id="profile-head">
          <div class="profile-avatar" id="profile-avatar"></div>
          <div class="profile-id">
            <div class="profile-name" id="profile-name-view"></div>
            <div class="profile-hash" id="profile-hash"></div>
          </div>
        </div>
        <div class="profile-bio" id="profile-bio"></div>
        <div class="profile-meta" id="profile-meta"></div>
        <div class="profile-actions" id="profile-actions"></div>
        <div class="profile-body" id="profile-body">
          <h4 class="profile-posts-h">Posts</h4>
          <div id="profile-posts"></div>
        </div>
      </div>
    </div>
```

(Use a distinct id `#profile-name-view` — `#profile-name` is already the nav whoami element.)

- [ ] **Step 5: style.css — profile-page + avatar shape/size/placement; remove modal CSS**

Remove all `.modalback`, `.pmodal`, `.pmbanner`, `.pmclose`, `.pmhead`, `.pmavatar`, `.pmname`, `.pmhash`, `.pmbio`, `.pmring`, `.pmacts`, `.pmbtn`, `.pmrecent`, `.pmpost` rules. Add profile-page CSS using only Kreds tokens:

```css
/* ---------- profile page ---------- */
#view-profile { max-width: 720px; margin: 0 auto; padding: 18px 24px 40px; }
.profile-back { border: 1px solid var(--line-2); background: var(--surface);
  color: var(--ink); font-size: 12.5px; padding: 6px 12px; border-radius: 99px;
  margin-bottom: 14px; }
.profile-page { background: var(--surface); border: 1px solid var(--line-2);
  border-radius: 16px; overflow: hidden; box-shadow: var(--shadow); }
.profile-banner { height: 120px; background: var(--pcolor, var(--red));
  background-size: cover; background-position: center; }
.profile-head { display: flex; align-items: flex-end; gap: 14px; padding: 0 22px;
  margin-top: -34px; }
.profile-head.align-center { justify-content: center; text-align: center; }
.profile-head.align-right { justify-content: flex-end; }
.profile-avatar { display: grid; place-items: center; color: #fff;
  font-family: var(--disp); font-weight: 700; background: var(--pcolor, var(--red));
  border: 4px solid var(--surface); overflow: hidden; }
.profile-avatar img { width: 100%; height: 100%; object-fit: cover; }
/* shapes */
.profile-avatar.circle { border-radius: 50%; }
.profile-avatar.squircle { border-radius: 28%; }
.profile-avatar.square { border-radius: 8%; }
.profile-avatar.triangle { clip-path: polygon(50% 0, 100% 100%, 0 100%); border-radius: 0; }
/* sizes */
.profile-avatar.s { width: 52px; height: 52px; font-size: 20px; }
.profile-avatar.m { width: 68px; height: 68px; font-size: 26px; }
.profile-avatar.l { width: 92px; height: 92px; font-size: 34px; }
.profile-name { font-family: var(--disp); font-weight: 700; font-size: 22px; }
.profile-hash { font-family: var(--mono); font-size: 10.5px; color: var(--ink-2); }
.profile-bio { padding: 10px 22px 0; font-size: 14px; color: var(--ink-2); }
.profile-meta { padding: 8px 22px 0; }
.profile-ring { display: inline-flex; align-items: center; gap: 6px;
  font-family: var(--mono); font-size: 10.5px; color: var(--ink-2);
  border: 1px dashed var(--line-2); border-radius: 99px; padding: 4px 11px; }
.profile-actions { display: flex; gap: 8px; padding: 12px 22px 0; }
.profile-body { padding: 16px 22px 22px; }
.profile-posts-h { font-family: var(--mono); font-size: 10px; letter-spacing: .13em;
  text-transform: uppercase; color: var(--ink-2); font-weight: 500;
  margin: 6px 0 8px; border-top: 1px solid var(--line); padding-top: 12px; }
```

- [ ] **Step 6: app.js — rewrite openProfile as a page; setView + Back; drop modal code**

Add `let PRIOR_VIEW = "journal";` near the other module state. Extend `setView` to handle `profile` (add `"profile"` to the toggled view list) — change its view loop to include profile:

```javascript
function setView(which) {
  for (const v of ["journal", "messages", "me", "profile"])
    document.getElementById("view-" + v).classList.toggle("hidden", v !== which);
  document.querySelectorAll(".navlinks button").forEach(b =>
    b.classList.toggle("active", b.dataset.view === which));
  if (which !== "profile") localStorage.setItem("hearth_view", which);
  if (which === "messages") { loadConversations(); if (!CURRENT_DM) dmPlaceholder(); }
}
```

Replace `closeProfile`/`openProfile`/`openProfileFallback` with the page versions:

```javascript
function currentView() {
  for (const v of ["journal", "messages", "me", "profile"])
    if (!document.getElementById("view-" + v).classList.contains("hidden")) return v;
  return "journal";
}

async function openProfile(identityPub) {
  const from = currentView();
  if (from !== "profile") PRIOR_VIEW = from;   // where Back returns
  let p;
  try {
    p = await j("/api/profile/" + identityPub);
  } catch (e) {
    renderProfilePage(fallbackProfile(identityPub));
    setView("profile");
    return;
  }
  renderProfilePage(p);
  setView("profile");
}

function fallbackProfile(identityPub) {
  const known = (STATE && STATE.friends || []).find(f => f.identity_pub === identityPub)
    || KREDS.find(k => k.identity_pub === identityPub);
  return {identity_pub: identityPub, name: (known && known.name) || identityPub.slice(0, 8),
          bio: "", mine: false, avatar: null, banner: null,
          avatar_shape: "circle", avatar_size: "m", avatar_align: "left",
          ring: null, since: null, posts: [], unavailable: true};
}

function renderProfilePage(p) {
  const color = (p.mine && STATE && STATE.accent) ? STATE.accent
    : identityColor(p.identity_pub);
  const page = document.getElementById("profile-page");
  page.style.setProperty("--pcolor", color);

  const banner = document.getElementById("profile-banner");
  banner.style.backgroundImage = p.banner ? `url(/api/blob/${p.banner})` : "";

  const head = document.getElementById("profile-head");
  head.className = "profile-head align-" + (p.avatar_align || "left");
  const av = document.getElementById("profile-avatar");
  av.className = "profile-avatar " + (p.avatar_shape || "circle") + " " + (p.avatar_size || "m");
  av.style.background = color;
  av.replaceChildren();
  if (p.avatar) { const img = document.createElement("img");
    img.src = "/api/blob/" + p.avatar; av.append(img); }
  else av.append(document.createTextNode((p.name || "?").slice(0, 1).toUpperCase()));

  document.getElementById("profile-name-view").textContent = p.name;
  document.getElementById("profile-hash").textContent =
    "identity " + p.identity_pub.slice(0, 8) + "…";
  document.getElementById("profile-bio").textContent = p.bio || "";

  const meta = document.getElementById("profile-meta");
  const acts = document.getElementById("profile-actions");
  meta.replaceChildren(); acts.replaceChildren();

  if (p.unavailable) {
    meta.append(el("div", "hint", "Profile unavailable yet."));
    const msg = el("button", "btn-accent", "Message");
    msg.onclick = () => { goView("messages"); openThread(p.identity_pub, p.name); };
    acts.append(msg);
  } else if (p.mine) {
    acts.append(profileEditor(p));               // self: edit in place
  } else {
    const ringLabel = p.ring === "inner" ? "Inner kreds" : "Kreds";
    const since = p.since ? new Date(p.since * 1000)
      .toLocaleDateString(undefined, {month: "long", year: "numeric"}) : "";
    const ring = el("div", "profile-ring", ringLabel + (since ? " · since " + since : ""));
    meta.append(ring);
    const msg = el("button", "btn-accent", "Message");
    msg.onclick = () => { goView("messages"); openThread(p.identity_pub, p.name); };
    const move = el("button", "", p.ring === "inner" ? "Move to kreds" : "Move to inner kreds");
    move.onclick = async () => {
      const next = p.ring === "inner" ? "kreds" : "inner";
      await j("/api/ring", {method: "POST", headers: {"Content-Type": "application/json"},
        body: JSON.stringify({identity_pub: p.identity_pub, ring: next})});
      await refresh();
      openProfile(p.identity_pub);
    };
    acts.append(msg, move);
  }

  const posts = document.getElementById("profile-posts");
  posts.replaceChildren();
  if (!p.posts.length) posts.append(el("div", "hint", "No posts you can see here yet."));
  for (const post of p.posts) posts.append(buildEntry(post));
}
```

Wire Back once at load (near other one-time handlers):

```javascript
document.getElementById("profile-back").onclick = () => goView(PRIOR_VIEW);
```

Delete the old modal `openProfile`/`openProfileFallback`/`closeProfile` and the revoked-branch `closeProfile()`/`#profile-modal` hide lines in `refresh` (the modal no longer exists; the profile view is inside `#app`, so hiding `#app` covers it — remove those two lines).

- [ ] **Step 7: Run asset tests + node --check + full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q` — Expected: PASS.
Run: `node --check hearth/web/app.js` — Expected: clean.
Run: `timeout 120 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3` — Expected: all pass (update any test asserting modal markup).

- [ ] **Step 8: Commit**

```powershell
git add hearth/web/index.html hearth/web/style.css hearth/web/app.js tests/test_web_assets.py
git commit -m "feat: full profile page (banner + avatar shape/size/placement), retire modal"
```

---

### Task 2: Me-view reorg + "Friends" label

**Files:**
- Modify: `hearth/web/index.html` (`#view-me` structure)
- Modify: `hearth/web/style.css` (Me two-column layout)
- Modify: `hearth/web/app.js` (`renderMe`; Edit-profile → `openProfile(self)`; remove `#me-editor-slot` usage)
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: Task 1's `openProfile` (page). `renderMe` fills `#me-card`, `#friends`, `#devices`, `#ceremony`.
- Produces: Me view with a main column (Me card + Edit-profile) and a smaller right column (Friends + Devices).

- [ ] **Step 1: Append failing tests**

```python
def test_me_view_friends_label_and_right_column():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    # friends section relabeled "Friends" in the Me view (note: the circle
    # rail legitimately still says "Your kreds", so only assert the positive)
    assert ">Friends<" in html
    # a two-column Me layout hook + the inline editor slot is gone
    assert "me-grid" in html
    assert 'id="me-editor-slot"' not in html
    # Edit-profile is still wired (now navigates to the self profile page)
    assert "edit-profile" in js
```

- [ ] **Step 2: Run to verify failure** — `pytest tests/test_web_assets.py::test_me_view_friends_label_and_right_column -q` → FAIL.

- [ ] **Step 3: index.html — restructure `#view-me`**

Replace the `#view-me` block with a two-column grid (main + smaller right), Friends heading, no `#me-editor-slot`:

```html
    <div id="view-me" class="hidden">
      <div class="me-grid">
        <div class="me-main">
          <div class="panel">
            <h2>Me</h2>
            <div id="me-card" class="me-card"></div>
            <button id="edit-profile">Edit profile</button>
          </div>
        </div>
        <aside class="me-side">
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

- [ ] **Step 4: style.css — Me two-column layout**

```css
.me-grid { max-width: 1000px; margin: 0 auto; padding: 20px 24px;
  display: grid; grid-template-columns: 1fr 320px; gap: 20px; align-items: start; }
.me-side .panel { margin-bottom: 16px; }
@media (max-width: 760px) { .me-grid { grid-template-columns: 1fr; } }
```

(The existing `.panel`/`.me-card`/`.friend`/`.device` styles are reused.)

- [ ] **Step 5: app.js — Edit-profile → profile page; renderMe unchanged except slot**

`renderMe` already fills `#me-card`/`#friends`/`#devices` — it works against the new markup unchanged (same ids). Replace the Edit-profile handler (currently appends `profileEditor` into `#me-editor-slot`) with a navigation to the self profile page:

```javascript
document.getElementById("edit-profile").onclick = () => {
  if (STATE) openProfile(STATE.identity_pub);
};
```

Remove any remaining `#me-editor-slot` references and the `PROFILE_EDITOR` caching if it was only used for the inline slot (keep `profileEditor` itself — Task 1's self-profile page uses it).

- [ ] **Step 6: Run tests + node --check + full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q` — Expected: PASS.
Run: `node --check hearth/web/app.js` — clean.
Run: `timeout 120 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3` — all pass.

- [ ] **Step 7: Commit**

```powershell
git add hearth/web/index.html hearth/web/style.css hearth/web/app.js tests/test_web_assets.py
git commit -m "feat: Me view - Friends + Devices in a right column, Friends label, edit on profile page"
```

---

### Task 3: Integration — full green, Playwright smoke, docs

**Files:**
- Modify: `README.md` / `ROADMAP.md` (note profile-page + Me reorg; profile-page is the block-profile foundation)
- Test: whole suite + manual smoke

- [ ] **Step 1: Full suite + JS checks**

Run: `timeout 150 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3` — Expected: all pass (a TOR_E2E skip is fine).
Run: `node --check hearth/web/app.js` — clean.
Confirm no dead modal refs: `grep -rn "pmodal\|modalback\|profile-modal\|closeProfile\|me-editor-slot" hearth/web` — expect none.

- [ ] **Step 2: Playwright smoke (record outcome)**

Stand up one isolated node on a free port (e.g. 8401, NOT the demo ports) with a created identity + a second friend node synced (mirror the Task-6 reskin smoke harness). Drive headless and confirm:
- As self: open Edit profile → the profile page shows; set an avatar shape (e.g. squircle) + upload a banner image + Save; reopen the self profile page and confirm the banner image and squircle avatar render.
- View the friend's profile page: ring status shows; Move-between-rings toggles and the page updates; Back returns to the prior view.
- Me view shows the Me card in the main column and Friends + Devices in the right column; the "Friends" heading is present.
Record observations. If Playwright is unavailable, do an HTTP smoke (`/`, `/api/profile/{id}`) and say so.

- [ ] **Step 3: README + ROADMAP**

README: note the profile is a full page (banner + avatar customization) and the Me view groups Friends + Devices in a side column. ROADMAP: record this slice; note the profile page is the foundation for the block-based-profile slice (grids/columns/etc.), still upcoming; unfriend still upcoming.

- [ ] **Step 4: Commit**

```powershell
git add README.md ROADMAP.md
git commit -m "docs: Kreds profile page + Me reorg shipped; block-profile foundation noted"
```

---

## Completion

After Task 3: whole-branch review (superpowers:requesting-code-review) — reviewer focuses on the modal being fully removed (no dead code), banner-image + avatar shape/size/placement actually rendering, Back navigation returning to the right prior view, self-edit unified on the page, honesty guards intact (no receipts; decrypt-filtered posts), and no shipped behavior lost. Then superpowers:finishing-a-development-branch — merge `kreds-me-profile` to `main`, push. Next: Slice B (unfriend + messaging removal), then Windows packaging.
