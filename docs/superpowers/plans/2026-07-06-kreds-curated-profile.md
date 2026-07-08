# Kreds Curated Profile (Wall) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add profile posts (a `placement` field on posts), render the self profile page as others see it with a two-section profile (curated Wall + a separate scrollable Journal), and move editing behind a subtle cogwheel overlay.

**Architecture:** One protocol addition — `placement` (`"journal"` default | `"profile"`) on `KIND_POST`, reusing the entire shipped scoped-posts encryption/scope/routing. `feed()` returns journal-placement posts only; profiles render the author's profile posts (Wall) and journal posts (Journal section) as two distinct areas. Front-end restructures `renderProfilePage` and moves the editor into a cogwheel-triggered overlay.

**Tech Stack:** Python 3.12, sqlite3, FastAPI, pytest; vanilla-JS client; `node --check`. Windows/PowerShell, `.venv`.

**Spec:** `docs/superpowers/specs/2026-07-06-kreds-curated-profile-design.md`

## Global Constraints

- Branch: `kreds-curated-profile` off `main`. One workstream.
- Test runner (timeout-guarded — false-green history): `timeout 150 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3`. Full suite green at every task commit (a TOR_E2E skip is expected).
- `node --check hearth/web/app.js` clean at every commit touching it. ASCII-only Python prints.
- `placement` values are exactly `"journal"` and `"profile"`; a missing `placement` is treated as `"journal"` (backward-compat with pre-existing posts).
- Profile posts reuse the scoped-posts machinery UNCHANGED — per-recipient encrypted, Inner/Kreds scope selector (default `kreds`), same wraps/AAD/routing. `placement` is authored content, NOT part of the audience/AAD.
- Journal posts appear only in the home feed + a profile's Journal section; profile posts appear only on the Wall. Neither bleeds into the other; a journal post never auto-appears on the Wall.
- Honesty guards unchanged: Wall/Journal on another person's profile show only posts the viewer can decrypt (server-side filtered); NO receipts "who holds this post" popover.
- Deletion of a post is unchanged (the shared `deleteEverywhere`/`/api/delete` path).

---

### Task 1: `placement` on posts (backend model)

**Files:**
- Modify: `hearth/messages.py` (`make_post` + `validate_payload`)
- Modify: `hearth/node.py` (`compose_post`, `_decrypt_post_row`, `feed`, `posts_by`, `profile_view`)
- Test: `tests/test_profile_posts.py` (new)

**Interfaces:**
- Produces:
  - `make_post(device, scope, body_nonce, body_ct, wraps, blob_refs=(), created_at=None, expires_at=None, placement="journal")` — payload gains `"placement"`.
  - `Node.compose_post(text, scope="kreds", photos=(), expires_seconds=None, placement="journal") -> str` — validates `placement in ("journal","profile")`.
  - `Node.feed() -> list` — journal-placement rows only.
  - `Node.posts_by(identity_pub, placement=None) -> list` — filtered by placement when given.
  - `Node.profile_view(id)` — returns `wall` (profile posts) + `journal` (journal posts) instead of `posts`.
  - decrypted post rows include `"placement"`.

- [ ] **Step 1: Branch already exists** — the controller created `kreds-curated-profile`. Skip branch creation; start at Step 2.

- [ ] **Step 2: Write failing tests**

Create `tests/test_profile_posts.py` (match how `tests/test_scoped_posts_e2e.py` builds nodes + friends + composes/syncs posts):

```python
def test_profile_post_on_wall_not_feed(two_friends):
    a, b = two_friends                      # mutual friends, enckeys exchanged
    jid = a.compose_post("journal hi", scope="kreds", placement="journal")
    pid = a.compose_post("profile hi", scope="kreds", placement="profile")
    feed_ids = {r["msg_id"] for r in a.feed()}
    assert jid in feed_ids and pid not in feed_ids          # feed = journal only
    wall_ids = {r["msg_id"] for r in a.posts_by(a.identity_pub, "profile")}
    jrnl_ids = {r["msg_id"] for r in a.posts_by(a.identity_pub, "journal")}
    assert pid in wall_ids and jid not in wall_ids
    assert jid in jrnl_ids and pid not in jrnl_ids


def test_profile_post_still_scoped_encrypted(two_friends_one_inner):
    # a has friend b in 'kreds' only; an inner-scoped profile post is invisible to b
    a, b = two_friends_one_inner
    pid = a.compose_post("inner-only wall", scope="inner", placement="profile")
    # sync a->b, then b's view of a's wall must not contain pid (can't decrypt)
    sync(a, b)
    assert pid not in {r["msg_id"] for r in b.posts_by(a.identity_pub, "profile")}


def test_placement_validation():
    from hearth.messages import validate_payload, KIND_POST
    base = {"kind": KIND_POST, "scope": "kreds", "created_at": 1.0,
            "body_nonce": "0"*24, "body_ct": "ab", "wraps": {}, "blobs": []}
    ok, _ = validate_payload({**base, "placement": "profile"}); assert ok
    ok, _ = validate_payload({**base, "placement": "journal"}); assert ok
    ok, why = validate_payload({**base, "placement": "wat"}); assert not ok
    ok, _ = validate_payload(base); assert ok            # missing => journal, valid


def test_compose_post_rejects_bad_placement(single_node):
    import pytest
    with pytest.raises(ValueError):
        single_node.compose_post("x", placement="wat")
```

Match the real fixtures/helpers from `tests/test_scoped_posts_e2e.py` (node build, `sync`, inner-ring setup); replace `two_friends`/`sync`/etc. with the actual idiom.

- [ ] **Step 3: Run — expect failure** (`placement` kwarg unknown / feed returns profile post).

- [ ] **Step 4: `make_post` + `validate_payload`** in `hearth/messages.py`

`make_post` — add the param + payload field:

```python
def make_post(device: DeviceKeys, scope: str, body_nonce: str,
              body_ct: str, wraps: dict, blob_refs: Sequence[str] = (),
              created_at: Optional[float] = None,
              expires_at: Optional[float] = None,
              placement: str = "journal") -> SignedMessage:
    return device.sign_message({
        "kind": KIND_POST, "scope": scope, "body_nonce": body_nonce,
        "body_ct": body_ct, "wraps": wraps, "blobs": list(blob_refs),
        "created_at": _now(created_at), "expires_at": expires_at,
        "placement": placement,
    })
```

`validate_payload`, in the `KIND_POST` block (after the scope check at line 153):

```python
        if p.get("placement", "journal") not in ("journal", "profile"):
            return False, "bad placement"
```

- [ ] **Step 5: `node.py` — compose, decrypt row, feed, posts_by, profile_view**

`compose_post` (add param + validation + pass through):

```python
    def compose_post(self, text: str, scope: str = "kreds",
                     photos=(), expires_seconds=None,
                     placement: str = "journal") -> str:
        if scope not in ("inner", "kreds"):
            raise ValueError("scope must be inner or kreds")
        if placement not in ("journal", "profile"):
            raise ValueError("placement must be journal or profile")
        pubs = self._scope_device_pubs(scope)
        created_at = time.time()
        expires_at = (created_at + expires_seconds
                      if expires_seconds is not None else None)
        aad = post_aad(self.identity_pub, scope, created_at)
        key = new_content_key()
        refs = [self.store.put_blob(encrypt_blob(key, p)) for p in photos]
        nonce, ct = encrypt_body(key, {"text": text, "blobs": refs}, aad)
        wraps = wrap_key(key, pubs, aad)
        mid = self._publish(make_post(self.device, scope, nonce, ct, wraps,
                                      refs, created_at, expires_at,
                                      placement=placement))
        self._cache_message_key(mid, key)
        return mid
```

`_decrypt_post_row` — add `placement` to the returned dict (in the `return {...}` at ~line 258):

```python
            "placement": p.get("placement", "journal"),
```

`feed` — journal only:

```python
    def feed(self) -> List[dict]:
        now = time.time()
        names = self.store.profiles()
        out = []
        for msg in self.store.post_messages():
            row = self._decrypt_post_row(msg, names, now)
            if row is not None and row["placement"] == "journal":
                out.append(row)
        return out
```

`posts_by` — optional placement filter:

```python
    def posts_by(self, identity_pub: str, placement=None) -> List[dict]:
        now = time.time()
        names = self.store.profiles()
        out = []
        for msg in self.store.post_messages(identity_pub):
            row = self._decrypt_post_row(msg, names, now)
            if row is not None and (placement is None
                                    or row["placement"] == placement):
                out.append(row)
        return out
```

`profile_view` — return `wall` + `journal` instead of `posts` (the `return {**rec, ...}` at ~line 239):

```python
        return {**rec, "identity_pub": identity_pub,
                "mine": identity_pub == self.identity_pub,
                "ring": ring, "since": since,
                "wall": self.posts_by(identity_pub, "profile"),
                "journal": self.posts_by(identity_pub, "journal")}
```

- [ ] **Step 6: Run tests — pass; then full suite** — fix any existing test that asserted `feed()`/`posts_by` returned both placements or referenced `profile_view()["posts"]` (update to `wall`/`journal`).

Run: `timeout 150 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3` → all pass.

- [ ] **Step 7: Commit**

```powershell
git add hearth/messages.py hearth/node.py tests/test_profile_posts.py
git commit -m "feat: post placement (journal|profile); feed=journal-only, profile_view returns wall+journal"
```

---

### Task 2: API — placement on compose + wall/journal in profile

**Files:**
- Modify: `hearth/api.py` (`/api/post` gains `placement`; `/api/profile` already returns `profile_view` which now has wall/journal)
- Test: `tests/test_api_profile_posts.py` (new)

**Interfaces:**
- Consumes: Task 1's `compose_post(..., placement=...)`, `profile_view` (wall/journal).
- Produces: `POST /api/post` accepts `placement` form field (default `"journal"`); `GET /api/profile/{id}` returns `wall` + `journal`.

- [ ] **Step 1: Failing API test** in `tests/test_api_profile_posts.py` (mirror `tests/test_api_kreds.py`/`test_api_unfriend.py` harness):

```python
def test_api_post_placement_and_profile_split(client_self):
    c, node = client_self
    c.post("/api/post", data={"text": "j", "scope": "kreds", "placement": "journal"})
    c.post("/api/post", data={"text": "p", "scope": "kreds", "placement": "profile"})
    feed = c.get("/api/feed").json()
    assert any(r["text"] == "j" for r in feed)
    assert all(r["text"] != "p" for r in feed)                 # profile post not in feed
    prof = c.get(f"/api/profile/{node.identity_pub}").json()
    assert any(r["text"] == "p" for r in prof["wall"])
    assert any(r["text"] == "j" for r in prof["journal"])
    assert all(r["text"] != "j" for r in prof["wall"])


def test_api_post_defaults_to_journal(client_self):
    c, node = client_self
    c.post("/api/post", data={"text": "d", "scope": "kreds"})   # no placement
    assert any(r["text"] == "d" for r in c.get("/api/feed").json())
```

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: `/api/post` — add `placement`** in `hearth/api.py`:

```python
    @app.post("/api/post")
    async def post(text: str = Form(""), scope: str = Form("kreds"),
                   expires_seconds: str = Form(""),
                   placement: str = Form("journal"),
                   photos: List[UploadFile] = File(default=[])):
        blobs = []
        for up in photos:
            data = await up.read()
            if len(data) > MAX_BLOB_BYTES:
                raise HTTPException(413, "photo exceeds 5 MB cap")
            blobs.append(data)
        expiry = float(expires_seconds) if expires_seconds.strip() else None
        mid = _400(lambda: node.compose_post(text, scope, blobs, expiry,
                                             placement=placement))
        return {"msg_id": mid}
```

`/api/profile` needs no change (it returns `node.profile_view(...)`, now carrying wall/journal). Confirm the `fallbackProfile`/unavailable path in the client (Task 3) tolerates the renamed fields.

- [ ] **Step 4: Run tests — pass; full suite green.**

- [ ] **Step 5: Commit**

```powershell
git add hearth/api.py tests/test_api_profile_posts.py
git commit -m "feat: /api/post placement field; /api/profile returns wall+journal"
```

---

### Task 3: Profile page — render-as-others, two sections, cogwheel edit, wall composer

**Files:**
- Modify: `hearth/web/index.html` (profile view: banner/head + `#profile-wall` + `#profile-journal`; a cogwheel button; an editor overlay container; Me-card cogwheel)
- Modify: `hearth/web/app.js` (`renderProfilePage` restructure; editor overlay; wall composer; remove inline editor + prominent Edit button)
- Modify: `hearth/web/style.css` (cogwheel, overlay, wall/journal sections, composer)
- Test: `tests/test_web_assets.py` (extend)

**Interfaces:**
- Consumes: `/api/profile/{id}` (`wall`, `journal`, `mine`, banner/avatar fields), `/api/post` with `placement`, `buildEntry`, `profileEditor`, `identityColor`.
- Produces: self + others profiles render header + Wall + Journal; cogwheel (self) opens the editor overlay; a profile-post composer on the self Wall.

- [ ] **Step 1: Failing asset tests** — append to `tests/test_web_assets.py`:

```python
def test_profile_two_sections_and_cogwheel():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    # two distinct sections
    assert 'id="profile-wall"' in html and 'id="profile-journal"' in html
    # cogwheel edit + overlay, not an inline dump / prominent button
    assert "profile-cog" in html or "profile-cog" in js
    assert "profile-edit-overlay" in html or "profile-edit-overlay" in js
    # renders wall + journal splits from the profile payload
    assert "p.wall" in js and "p.journal" in js
    # profile-post composer posts with placement=profile
    assert 'placement' in js and 'profile' in js


def test_profile_post_composer_scope_selector():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    # the wall composer sends a scope (inner/kreds) + placement=profile
    assert "/api/post" in js
```

(Keep these DOM/string-level; visual correctness is covered by the smoke in Task 4.)

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: index.html — profile view structure**

In `#view-profile`, keep the banner/head/bio, add a self-only **cogwheel** button near the Back button, split `#profile-body` into a Wall region (with a self composer slot) and a Journal region, and add an editor-overlay container:

```html
    <div id="view-profile" class="hidden">
      <div class="profile-topbar">
        <button class="profile-back" id="profile-back">&larr; Back</button>
        <button class="profile-cog hidden" id="profile-cog" aria-label="Edit profile" title="Edit profile">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
          </svg>
        </button>
      </div>
      <div class="profile-page" id="profile-page">
        <div class="profile-banner" id="profile-banner"></div>
        <div class="profile-head" id="profile-head">
          <div class="profile-avatar" id="profile-avatar"></div>
          <div><div class="profile-name" id="profile-name-view"></div>
               <div class="profile-hash" id="profile-hash"></div></div>
        </div>
        <div class="profile-bio" id="profile-bio"></div>
        <div class="profile-meta" id="profile-meta"></div>
        <div class="profile-actions" id="profile-actions"></div>
        <div class="profile-body" id="profile-body">
          <div id="profile-wall-compose"></div>
          <h4 class="profile-section-h">Profile</h4>
          <div id="profile-wall"></div>
          <h4 class="profile-section-h">Journal</h4>
          <div id="profile-journal"></div>
        </div>
      </div>
    </div>
    <div class="modalback" id="profile-edit-overlay" hidden>
      <div class="editwrap" id="profile-edit-wrap"></div>
    </div>
```

- [ ] **Step 4: app.js — renderProfilePage restructure + overlay + composer**

Rewrite `renderProfilePage` so header/bio render the same for all (fixing the self render), the actions row holds only Message/Move/Unfriend (others) — NOT the editor — and the body renders Wall + Journal; wire the cogwheel to an overlay; add the self wall composer. Key structure (adapt to the exact current function):

```javascript
function renderProfilePage(p) {
  // ... header render (banner/avatar/name/hash/bio) UNCHANGED — applies to
  //     self too (p.mine no longer swaps in the editor here) ...
  const cog = document.getElementById("profile-cog");
  cog.classList.toggle("hidden", !p.mine);
  if (p.mine) cog.onclick = () => openProfileEditor(p);

  const meta = document.getElementById("profile-meta");
  const acts = document.getElementById("profile-actions");
  meta.replaceChildren(); acts.replaceChildren();
  if (p.disconnected) { meta.append(el("div","profile-ring disconnected","no longer connected")); }
  else if (p.unavailable) { /* Message only, as today */ }
  else if (!p.mine) { /* ring status + Message + Move + Unfriend, as today */ }
  // (no editor in the actions row anymore)

  // wall composer (self only)
  const compose = document.getElementById("profile-wall-compose");
  compose.replaceChildren();
  if (p.mine) compose.append(profilePostComposer());

  // Wall + Journal
  const wall = document.getElementById("profile-wall");
  wall.replaceChildren();
  if (!p.wall.length) wall.append(el("div","hint","Nothing on the wall yet."));
  for (const post of p.wall) wall.append(buildEntry(post));

  const jrnl = document.getElementById("profile-journal");
  jrnl.replaceChildren();
  if (!p.journal.length) jrnl.append(el("div","hint","No journal posts you can see."));
  for (const post of p.journal) jrnl.append(buildEntry(post));
}

function openProfileEditor(p) {
  const wrap = document.getElementById("profile-edit-wrap");
  wrap.replaceChildren(profileEditor(p));          // reuse existing editor
  document.getElementById("profile-edit-overlay").hidden = false;
}
function closeProfileEditor() {
  document.getElementById("profile-edit-overlay").hidden = true;
}

function profilePostComposer() {
  const form = el("form", "composer profile-composer");
  const input = document.createElement("input");
  input.type = "text"; input.id = "wall-text";
  input.placeholder = "Post to your profile…";
  let scope = "kreds";
  // reuse the journal composer's keeps buttons pattern (inner/kreds), default kreds
  // ... build two keep buttons that set `scope` ...
  const btn = el("button", "postbtn", "Post to profile"); btn.type = "submit";
  form.append(input /*, keeps */, btn);
  form.onsubmit = async (ev) => {
    ev.preventDefault();
    const fd = new FormData();
    fd.append("text", input.value);
    fd.append("scope", scope);
    fd.append("placement", "profile");
    await fetch("/api/post", {method: "POST", body: fd});
    input.value = "";
    openProfile(STATE.identity_pub);      // re-render the wall
  };
  return form;
}
```

The existing `profileEditor` Save handler currently calls `openProfile(STATE.identity_pub)` — after this change it must also `closeProfileEditor()`; update it to close the overlay on success. Wire the overlay backdrop/Esc to `closeProfileEditor`. On the Me card, change the "Edit profile" button into a small cogwheel that calls `openProfile(STATE.identity_pub)` (which then shows the cog) — or a cog that directly opens the editor overlay; keep ONE self-edit entry (the cogwheel).

**Required consistency fix:** `fallbackProfile(identityPub)` currently returns `posts: []`. Since `renderProfilePage` now reads `p.wall`/`p.journal`, change `fallbackProfile` to return `wall: [], journal: []` (drop `posts`), so the disconnected/unavailable path doesn't throw on `p.wall.length`. Grep for any other reader of `profile_view`/`p.posts` on the client and update to `wall`/`journal`.

- [ ] **Step 5: style.css — cogwheel, overlay, sections, composer** — add token-based styles: `.profile-topbar` (flex, Back left / cog right), `.profile-cog` (small round icon button, `var(--ink-2)`), reuse the existing `.modalback` overlay style for `#profile-edit-overlay` (or a light variant), `.profile-section-h` (mono uppercase label like the old Posts header), `.profile-composer` (compact composer). Keep it consistent with existing profile-page tokens.

- [ ] **Step 6: Run asset tests + node --check + full suite** — all pass.

- [ ] **Step 7: Commit**

```powershell
git add hearth/web/index.html hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat: profile renders as others see it - Wall + Journal sections, cogwheel editor overlay, wall composer"
```

---

### Task 4: Integration — smoke, docs

**Files:**
- Test: `tests/test_curated_profile_integration.py` (new)
- Modify: `README.md`, `ROADMAP.md`

- [ ] **Step 1: Two-node integration test** (mirror `tests/test_scoped_posts_e2e.py`): A and B mutual friends. A posts one journal + one profile post; sync. Assert: B's `/api/feed`-equivalent (`b.feed()`) contains the journal post, not the profile post; B's `profile_view(A)` shows the profile post on `wall` and the journal post under `journal`; an expiring journal post (short expiry) appears under journal and never on wall. Terminates fast; run under timeout.

- [ ] **Step 2: Full suite** — `timeout 150 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3` → all pass. `node --check hearth/web/app.js` clean.

- [ ] **Step 3: Playwright/HTTP smoke (record outcome)** — stand up two isolated nodes on free ports (NOT demo ports 7101-7104/7201-7204). Confirm: your own profile page shows the rendered header (set an avatar+banner via the cogwheel editor → they render on the header, not just in the editor), a Wall, and a Journal section; posting via the wall composer lands on the Wall and NOT the home feed; a normal journal post lands in the feed and the profile's Journal section, not the Wall; the cogwheel opens/saves/closes the editor overlay. If Playwright unavailable, drive via HTTP + the integration harness and say so.

- [ ] **Step 4: README + ROADMAP** — document the curated profile: profile posts (`placement`), two-section profile (Wall + Journal), self-renders-as-others, cogwheel edit; note the block-layout builder is still the next profile slice. Mark this slice shipped.

- [ ] **Step 5: Commit**

```powershell
git add tests/test_curated_profile_integration.py README.md ROADMAP.md
git commit -m "test+docs: curated-profile integration (wall vs journal separation) + ship notes"
```

---

## Completion

After Task 4: whole-branch review (superpowers:requesting-code-review) focused on — journal/profile placement never bleeds (feed excludes profile posts; wall excludes journal posts; a journal post never auto-appears on the wall); profile posts stay per-recipient encrypted + scope-filtered (an inner profile post invisible to a kreds-only friend; no ciphertext leak in wall/journal); self profile renders header (banner/avatar) as others see it with no inline editor; cogwheel self-only; honesty guards intact (no receipts); backward-compat (missing placement = journal); no shipped behavior broken (home feed, DMs, unfriend, move-between-rings, deletion). Then superpowers:finishing-a-development-branch — merge `kreds-curated-profile` to `main`, push. Next: block-layout profile builder, then Windows packaging.
