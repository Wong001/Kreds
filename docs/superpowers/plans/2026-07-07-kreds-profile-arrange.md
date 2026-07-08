# Kreds Profile Canvas Slice 2 (Arrange + Fixes) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an accessible Arrange mode to reorder profile blocks (persisted in a signed, latest-wins layout record that friends see), and fold in two fixes: viewers see the owner's accent/banner/avatar (not the identity hue), and each block shows its Inner/Kreds audience with a scope note.

**Architecture:** New `KIND_PROFILE_LAYOUT` signed record (latest-wins per author, like the profile record) holding an ordered list of block `msg_id`s; `profile_view` returns the `wall` in that order (unlisted blocks newest-first on top). Front-end Arrange mode (Up/Down, publish once on Done) + render fixes.

**Tech Stack:** Python 3.12, sqlite3, FastAPI, pytest; vanilla-JS client; `node --check`.

**Spec:** `docs/superpowers/specs/2026-07-07-kreds-profile-arrange-design.md`

## Global Constraints

- Branch: `kreds-profile-arrange` off `main` (already created + checked out — do NOT re-branch).
- Test runner: `timeout 150 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3`; full suite green each commit (TOR_E2E skip expected; a rare pre-existing intermittent flake may show "1 failed" ~1-in-5 — re-run 2-3x to confirm green, don't chase). `node --check hearth/web/app.js` clean. ASCII-only Python prints.
- The layout record orders only block `msg_id`s the viewer can already see — NO new confidentiality surface. A non-recipient must NOT gain access to a block by it being named in a layout. Undecryptable/unknown ids in the order are skipped at render.
- Layout is latest-wins per author (max `(created_at, seq, device_pub)`, mirroring `Store.profile`).
- Blocks are immutable signed posts; editing = delete + repost (delete already exists). No versioned edit.
- Fix A: profile page uses the profile's own `accent`/`banner`/`avatar` for ALL viewers; `identityColor` stays in feed/chips/circle only.
- Fix B: audience badge shows on YOUR OWN blocks (`p.mine`); others' profiles show no badge. Honesty guard: no receipts popover.
- Arrange mode is self-only; publishes the layout once on "Done" (not per move).

---

### Task 1: `KIND_PROFILE_LAYOUT` record + ordered wall + API

**Files:**
- Modify: `hearth/messages.py` (kind + `make_profile_layout` + validation)
- Modify: `hearth/store.py` (`profile_layout` latest-wins query)
- Modify: `hearth/node.py` (`set_profile_layout`; order the wall in `profile_view`)
- Modify: `hearth/api.py` (`POST /api/profile-layout`)
- Test: `tests/test_profile_layout.py` (new)

**Interfaces:**
- Produces: `make_profile_layout(device, order: list[str], now=None) -> SignedMessage`; `Store.profile_layout(identity_pub) -> list[str]`; `Node.set_profile_layout(order: list[str]) -> str`; `profile_view` returns `wall` in display order; `POST /api/profile-layout {order}`.

- [ ] **Step 1: Branch exists — skip branch creation; start at Step 2.**

- [ ] **Step 2: Failing tests** — `tests/test_profile_layout.py` (match the node/friend/sync + befriend idiom from `tests/test_profile_posts.py`):

```python
def test_layout_orders_wall(single_node):
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    b = n.compose_post("B", scope="kreds", placement="profile")
    c = n.compose_post("C", scope="kreds", placement="profile")
    # default: newest-first (C, B, A)
    assert [p["text"] for p in n.profile_view(n.identity_pub)["wall"]] == ["C","B","A"]
    n.set_profile_layout([a, c, b])            # explicit order A, C, B
    assert [p["text"] for p in n.profile_view(n.identity_pub)["wall"]] == ["A","C","B"]

def test_unlisted_block_prepended_newest_first(single_node):
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    b = n.compose_post("B", scope="kreds", placement="profile")
    n.set_profile_layout([a, b])               # A, B arranged
    c = n.compose_post("C", scope="kreds", placement="profile")   # new, unlisted
    # fresh post surfaces on top, then the arranged order
    assert [p["text"] for p in n.profile_view(n.identity_pub)["wall"]] == ["C","A","B"]

def test_unknown_id_in_layout_skipped(single_node):
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    n.set_profile_layout(["f"*64, a])          # unknown id skipped, no error
    assert [p["text"] for p in n.profile_view(n.identity_pub)["wall"]] == ["A"]

def test_layout_latest_wins(single_node):
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    b = n.compose_post("B", scope="kreds", placement="profile")
    n.set_profile_layout([a, b]); n.set_profile_layout([b, a])
    assert [p["text"] for p in n.profile_view(n.identity_pub)["wall"]] == ["B","A"]

def test_layout_validation():
    from hearth.messages import validate_payload, KIND_PROFILE_LAYOUT
    ok,_ = validate_payload({"kind": KIND_PROFILE_LAYOUT, "created_at":1.0, "order":["a"*64]}); assert ok
    ok,_ = validate_payload({"kind": KIND_PROFILE_LAYOUT, "created_at":1.0, "order":["nothex"]}); assert not ok
    ok,_ = validate_payload({"kind": KIND_PROFILE_LAYOUT, "created_at":1.0, "order":"x"}); assert not ok
```

Match the real `single_node`/harness idiom from existing tests (replace placeholders).

- [ ] **Step 3: Run — expect failure.**

- [ ] **Step 4: messages.py — kind + maker + validation**

```python
KIND_PROFILE_LAYOUT = "profile_layout"
MAX_LAYOUT = 500

def make_profile_layout(device, order, now=None):
    return device.sign_message({
        "kind": KIND_PROFILE_LAYOUT, "order": list(order), "created_at": _now(now),
    })
```

In `validate_payload`, add a `KIND_PROFILE_LAYOUT` branch:

```python
    if kind == KIND_PROFILE_LAYOUT:
        order = p.get("order")
        if not isinstance(order, list) or len(order) > MAX_LAYOUT:
            return False, "bad layout order"
        if not all(_is_hex64(x) for x in order):
            return False, "bad layout id"
        return True, "ok"
```

(Place alongside the other kind branches; ensure `KIND_PROFILE_LAYOUT` is imported where kinds are used.)

- [ ] **Step 5: store.py — `profile_layout` latest-wins**

Mirror `Store.profile` (max `(created_at, seq, device_pub)`):

```python
    def profile_layout(self, identity_pub: str) -> List[str]:
        with self._lock:
            best, best_key = None, None
            for seq, dpub, mj in self._db.execute(
                    "SELECT seq, device_pub, msg_json FROM messages"
                    " WHERE kind=? AND identity_pub=?",
                    (KIND_PROFILE_LAYOUT, identity_pub)):
                p = json.loads(mj)["payload"]
                key = (p["created_at"], seq, dpub)
                if best is None or key > best_key:
                    best, best_key = p.get("order", []), key
            return best or []
```

Import `KIND_PROFILE_LAYOUT` in store.py's kinds import.

- [ ] **Step 6: node.py — publish + order the wall**

```python
    def set_profile_layout(self, order: List[str]) -> str:
        return self._publish(make_profile_layout(self.device, order))
```

In `profile_view`, order the wall by the layout (the current `wall = self.posts_by(identity_pub, "profile")` is newest-first):

```python
        wall = self.posts_by(identity_pub, "profile")
        layout = self.store.profile_layout(identity_pub)
        pos = {mid: i for i, mid in enumerate(layout)}
        listed = [p for p in wall if p["msg_id"] in pos]
        listed.sort(key=lambda p: pos[p["msg_id"]])
        unlisted = [p for p in wall if p["msg_id"] not in pos]   # already newest-first
        ordered_wall = unlisted + listed
```

and return `"wall": ordered_wall` (instead of `self.posts_by(identity_pub, "profile")`). Keep `journal` as-is. Import `make_profile_layout`.

- [ ] **Step 7: api.py — endpoint**

```python
    @app.post("/api/profile-layout")
    async def profile_layout(body: dict = Body(...)):
        _400(lambda: node.set_profile_layout(body["order"]))
        return {"ok": True}
```

- [ ] **Step 8: Run tests + full suite** — update any test asserting the old wall order. All pass.

- [ ] **Step 9: Commit**

```powershell
git add hearth/messages.py hearth/store.py hearth/node.py hearth/api.py tests/test_profile_layout.py
git commit -m "feat: profile-layout record (latest-wins) orders the block canvas; /api/profile-layout"
```

---

### Task 2: Arrange mode + accent/scope fixes (front-end)

**Files:**
- Modify: `hearth/web/app.js` (Arrange toggle; Up/Down in `renderBlock`; publish on Done; Fix A in `renderProfilePage`; Fix B badge + composer note)
- Modify: `hearth/web/index.html` (Arrange/Done control in the profile topbar)
- Modify: `hearth/web/style.css` (arrange controls, scope badge)
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: `/api/profile-layout` (Task 1), `p.wall` (now display-ordered), `p.accent`/`p.banner`/`p.avatar`, `p.scope`/`p.mine`, `renderBlock`, `renderProfilePage`, `deleteEverywhere`, `openProfile`, `CURRENT_PROFILE`.
- Produces: `ARRANGING` flag + Arrange/Done toggle; blocks carry `data-msg-id`; Done POSTs the order.

- [ ] **Step 1: Failing asset tests** — append to `tests/test_web_assets.py`:

```python
def test_arrange_mode_and_fixes():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    html = (WEB / "index.html").read_text(encoding="utf-8")
    # arrange mode + layout POST
    assert "profile-arrange" in html or "profile-arrange" in js
    assert "/api/profile-layout" in js
    # Fix A: profile page uses the owner's accent, not identityColor, for the page color
    assert "p.accent" in js
    # Fix B: per-block scope badge + composer scope note
    assert "block-scope" in js or "block-scope" in (WEB / "style.css").read_text(encoding="utf-8")
```

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: Fix A — `renderProfilePage` color from the owner's accent**

Change the color line (app.js ~572) so the profile page uses the profile's own accent for every viewer (banner/avatar already prefer `p.banner`/`p.avatar`):

```javascript
  const color = p.accent || identityColor(p.identity_pub);   // owner's chosen accent for all viewers
```

(Leave `identityColor` usage in feed/chips/circle untouched.)

- [ ] **Step 4: Fix B — scope badge on own blocks + composer note**

In `renderBlock`, when `p.mine`, prepend a small audience badge:

```javascript
  if (p.mine) {
    const badge = el("span", "block-scope", p.scope === "inner" ? "Inner" : "Kreds");
    block.append(badge);
  }
```

In `profilePostComposer`, add a one-line note near the keeps selector:

```javascript
  form.append(el("div", "composer-note",
    "Inner posts reach only your Inner kreds. Moving someone into a ring reveals only future posts."));
```

- [ ] **Step 5: Arrange mode**

Add module state `let ARRANGING = false;`. Add an **Arrange/Done** button to the profile topbar (index.html, self-only, next to the cog) — `id="profile-arrange"`; `renderProfilePage` shows it only when `p.mine` and its label reflects `ARRANGING` ("Arrange" / "Done"). Its handler:

```javascript
document.getElementById("profile-arrange").onclick = async () => {
  if (ARRANGING) {
    // publish the current DOM order, then leave arrange mode
    const order = [...document.getElementById("profile-wall").children]
      .map(b => b.dataset.msgId).filter(Boolean);
    ARRANGING = false;
    await fetch("/api/profile-layout", {method: "POST",
      headers: {"Content-Type": "application/json"}, body: JSON.stringify({order})});
    await refresh();
    if (CURRENT_PROFILE) openProfile(CURRENT_PROFILE);
  } else {
    ARRANGING = true;
    if (CURRENT_PROFILE) openProfile(CURRENT_PROFILE);   // re-render with controls
  }
};
```

In `renderBlock`, set `block.dataset.msgId = p.msg_id;` always, and when `ARRANGING && p.mine` add Up/Down controls:

```javascript
  if (ARRANGING && p.mine) {
    const up = el("button", "arr", "↑"); up.setAttribute("aria-label", "Move up");
    up.onclick = () => { const prev = block.previousElementSibling;
      if (prev) block.parentNode.insertBefore(block, prev); };
    const down = el("button", "arr", "↓"); down.setAttribute("aria-label", "Move down");
    down.onclick = () => { const next = block.nextElementSibling;
      if (next) block.parentNode.insertBefore(next, block); };
    block.append(up, down);
  }
```

Update the label of `#profile-arrange` in `renderProfilePage` (self only; hidden for others): `arrangeBtn.textContent = ARRANGING ? "Done" : "Arrange"; arrangeBtn.classList.toggle("hidden", !p.mine);`. When leaving a profile / opening another, reset `ARRANGING = false` at the top of `openProfile`.

- [ ] **Step 6: style.css — arrange controls + badge**

```css
.block-scope { display: inline-block; font-family: var(--mono); font-size: 10px;
  color: var(--ink-2); border: 1px solid var(--line-2); border-radius: 99px;
  padding: 1px 8px; margin-top: 6px; }
.block .arr { border: 1px solid var(--line-2); background: var(--surface);
  width: 30px; height: 28px; border-radius: 8px; margin: 6px 4px 0 0; }
.composer-note { font-size: 11px; color: var(--ink-2); margin-top: 6px; }
```

- [ ] **Step 7: Run asset tests + node --check + full suite** — all pass.

- [ ] **Step 8: Commit**

```powershell
git add hearth/web/app.js hearth/web/index.html hearth/web/style.css tests/test_web_assets.py
git commit -m "feat: profile Arrange mode (Up/Down); viewers see owner accent/banner/avatar; per-block scope badge + composer note"
```

---

### Task 3: Integration smoke + docs

**Files:**
- Test: `tests/test_profile_arrange_integration.py` (new)
- Modify: `README.md`, `ROADMAP.md`

- [ ] **Step 1: Two-node integration test** (mirror `tests/test_curated_profile_integration.py`): A + B mutual friends. A posts 3 kreds profile blocks; `set_profile_layout` to a chosen order; sync; assert B's `profile_view(A)["wall"]` is in A's chosen order (for blocks B can decrypt). A posts a 4th block; assert it appears at the top of B's view (unlisted → newest-first) ahead of the arranged three. Terminates fast; under timeout.

- [ ] **Step 2: Full suite + JS check** — all pass; `node --check hearth/web/app.js` clean. (Re-run to clear the known flake if it appears.)

- [ ] **Step 3: Playwright/HTTP smoke (record)** — isolated node + friend on FREE ports (not demo ports). Confirm: Arrange toggle (self only) shows Up/Down; reordering + Done persists and a friend sees the new order; a fresh post lands on top until arranged; a viewer sees the owner's accent (not identity hue) + uploaded banner/avatar; own blocks show an Inner/Kreds badge; the composer note is present; reorder is keyboard-operable; delete still works. Playwright installed; else HTTP + DOM assertions, say so.

- [ ] **Step 4: README + ROADMAP** — document Slice 2: Arrange mode (Up/Down, layout record), viewers see owner's accent/banner/avatar, per-block scope badge + note; note Slice 3 (drag, video, split columns, grids, versioned edit) upcoming. Increment on the profile-canvas feature.

- [ ] **Step 5: Commit**

```powershell
git add tests/test_profile_arrange_integration.py README.md ROADMAP.md
git commit -m "test+docs: profile arrange integration (friend sees owner's order) + ship notes"
```

---

## Completion

After Task 3: whole-branch review (superpowers:requesting-code-review) — focus: the layout record adds NO confidentiality surface (a non-recipient can't see a block via the order; unknown/undecryptable ids skipped); latest-wins ordering correct; unlisted-newest-first prepend correct; Fix A (viewers see owner accent/banner/avatar; identityColor still used in feed/chips/circle); Fix B badge self-only; Arrange mode accessible + self-only + publishes once on Done; delete still works; no shipped behavior broken; honesty guard intact. Then superpowers:finishing-a-development-branch — merge to `main`, push. Next: Slice 3 (drag, video, split columns, grids) or Windows packaging.
