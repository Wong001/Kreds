# Kreds Profile Canvas Slice 3b (Configurable Photo Grids) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a photo block use one of five layouts (auto / 2-col / 3-col / hero / masonry), chosen at compose time and re-stylable in place afterward — persisted in the existing latest-wins layout record so friends see it.

**Architecture:** Extend `KIND_PROFILE_LAYOUT` from `order` to also carry a per-block `grids` map (`{msg_id: layout}`); `set_block_grid` updates it (preserving order), reorder preserves grids; `profile_view` annotates each wall block with its resolved grid; the client renders the five layouts. No new record type, no versioning protocol.

**Tech Stack:** Python 3.12, sqlite3, FastAPI, pytest; vanilla-JS client; `node --check`.

**Spec:** `docs/superpowers/specs/2026-07-07-kreds-profile-grids-design.md`

## Global Constraints

- Branch: `kreds-profile-grids` off `main` (already created + checked out — do NOT re-branch).
- Quality over shortcuts (user principle). Test runner: `timeout 150 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3`; full suite green each commit (TOR_E2E skip; the two prior flakes are fixed — keep them fixed). `node --check hearth/web/app.js` clean. ASCII-only Python prints.
- Grid enum EXACTLY: `("auto","cols2","cols3","hero","masonry")`; missing/`auto` = default (current render). Reorder must NEVER drop grids; setting a grid must NEVER change order.
- Grid lives ONLY in the layout record (mutable), not the post — that's what makes it re-stylable. Confidentiality unchanged: grid is a style enum on already-disclosed opaque `msg_id`s; an unknown id in `grids` is simply not rendered (no throw), like `order`.
- Self-only: `set_block_grid`/`set_profile_layout` sign the local identity (no target param); the grid pickers render only for `p.mine`.
- Honesty guard: no receipts popover.

---

### Task 1: `grids` in the layout record + `set_block_grid` + annotate wall

**Files:**
- Modify: `hearth/messages.py` (`make_profile_layout` grids param + `GRID_LAYOUTS` + validation)
- Modify: `hearth/store.py` (`profile_layout` returns `{order, grids}`)
- Modify: `hearth/node.py` (`set_profile_layout` preserves grids; `set_block_grid`; `profile_view` annotates `grid`)
- Modify: `hearth/api.py` (`POST /api/block-grid`)
- Test: `tests/test_profile_grids.py` (new); update `tests/test_profile_layout.py` for the new `profile_layout` return shape

**Interfaces:**
- Produces: `GRID_LAYOUTS`; `make_profile_layout(device, order, grids=None, now=None)`; `Store.profile_layout(id) -> {"order": [...], "grids": {...}}`; `Node.set_block_grid(msg_id, grid) -> str`; `profile_view` wall rows gain `"grid"`; `POST /api/block-grid {msg_id, grid}`.

- [ ] **Step 1: Branch exists — skip; start at Step 2.**

- [ ] **Step 2: Failing tests** — `tests/test_profile_grids.py` (mirror the `single_node` fixture in `tests/test_profile_layout.py`):

```python
def test_set_block_grid_annotates_and_preserves_order(single_node):
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    b = n.compose_post("B", scope="kreds", placement="profile")
    n.set_profile_layout([a, b])
    n.set_block_grid(a, "cols3")
    wall = {p["msg_id"]: p for p in n.profile_view(n.identity_pub)["wall"]}
    assert wall[a]["grid"] == "cols3" and wall[b]["grid"] == "auto"   # default
    assert [p["msg_id"] for p in n.profile_view(n.identity_pub)["wall"]] == [a, b]  # order kept

def test_reorder_preserves_grids(single_node):
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    b = n.compose_post("B", scope="kreds", placement="profile")
    n.set_block_grid(a, "hero")
    n.set_profile_layout([b, a])                       # reorder
    wall = {p["msg_id"]: p for p in n.profile_view(n.identity_pub)["wall"]}
    assert wall[a]["grid"] == "hero"                   # grid survived the reorder

def test_set_block_grid_auto_clears(single_node):
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    n.set_block_grid(a, "masonry"); n.set_block_grid(a, "auto")
    assert n.store.profile_layout(n.identity_pub)["grids"] == {}

def test_set_block_grid_rejects_bad(single_node):
    import pytest
    a = single_node.compose_post("A", scope="kreds", placement="profile")
    with pytest.raises(ValueError): single_node.set_block_grid(a, "wat")
    with pytest.raises(ValueError): single_node.set_block_grid("nothex", "cols2")

def test_layout_grids_validation():
    from hearth.messages import validate_payload, KIND_PROFILE_LAYOUT
    base = {"kind": KIND_PROFILE_LAYOUT, "created_at": 1.0, "order": []}
    ok,_ = validate_payload({**base, "grids": {"a"*64: "cols2"}}); assert ok
    ok,_ = validate_payload({**base}); assert ok                     # missing grids ok
    ok,_ = validate_payload({**base, "grids": {"a"*64: "wat"}}); assert not ok
    ok,_ = validate_payload({**base, "grids": {"nothex": "cols2"}}); assert not ok
    ok,_ = validate_payload({**base, "grids": ["x"]}); assert not ok  # not a dict

def test_api_block_grid(tmp_path):
    from fastapi.testclient import TestClient
    from hearth.api import build_app
    from hearth.node import HearthNode
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    c = TestClient(build_app(node))
    a = node.compose_post("A", scope="kreds", placement="profile")
    assert c.post("/api/block-grid", json={"msg_id": a, "grid": "cols3"}).status_code == 200
    assert c.post("/api/block-grid", json={"msg_id": a, "grid": "wat"}).status_code == 400
```

- [ ] **Step 3: Run — expect failure.**

- [ ] **Step 4: messages.py**

```python
GRID_LAYOUTS = ("auto", "cols2", "cols3", "hero", "masonry")

def make_profile_layout(device: DeviceKeys, order: Sequence[str],
                        grids: Optional[dict] = None,
                        now: Optional[float] = None) -> SignedMessage:
    return device.sign_message({
        "kind": KIND_PROFILE_LAYOUT, "order": list(order),
        "grids": dict(grids or {}), "created_at": _now(now),
    })
```

In `validate_payload`'s `KIND_PROFILE_LAYOUT` branch, after the order checks:

```python
        grids = p.get("grids", {})
        if not isinstance(grids, dict) or len(grids) > MAX_LAYOUT:
            return False, "bad layout grids"
        for k, v in grids.items():
            if not _is_hex64(k) or v not in GRID_LAYOUTS:
                return False, "bad layout grid"
        return True, "ok"
```

- [ ] **Step 5: store.py — `profile_layout` returns `{order, grids}`**

```python
    def profile_layout(self, identity_pub: str) -> dict:
        """Latest-wins {order: [...], grids: {msg_id: layout}} for this
        author's wall. Empty when never arranged."""
        with self._lock:
            best, best_key = None, None
            for seq, dpub, mj in self._db.execute(
                    "SELECT seq, device_pub, msg_json FROM messages"
                    " WHERE kind=? AND identity_pub=?",
                    (KIND_PROFILE_LAYOUT, identity_pub)):
                p = json.loads(mj)["payload"]
                key = (p["created_at"], seq, dpub)
                if best is None or key > best_key:
                    best = {"order": p.get("order", []), "grids": p.get("grids", {})}
                    best_key = key
            return best or {"order": [], "grids": {}}
```

- [ ] **Step 6: node.py**

`set_profile_layout` — preserve grids:

```python
    def set_profile_layout(self, order: List[str]) -> str:
        if not isinstance(order, list) or len(order) > MAX_LAYOUT:
            raise ValueError("bad layout order")
        if not all(isinstance(x, str) and len(x) == 64
                   and all(c in "0123456789abcdef" for c in x) for x in order):
            raise ValueError("bad layout id")
        grids = self.store.profile_layout(self.identity_pub)["grids"]
        return self._publish(make_profile_layout(self.device, order, grids=grids))
```

`set_block_grid` (new):

```python
    def set_block_grid(self, msg_id: str, grid: str) -> str:
        if grid not in GRID_LAYOUTS:
            raise ValueError("bad grid")
        if not (isinstance(msg_id, str) and len(msg_id) == 64
                and all(c in "0123456789abcdef" for c in msg_id)):
            raise ValueError("bad msg_id")
        cur = self.store.profile_layout(self.identity_pub)
        grids = dict(cur["grids"])
        if grid == "auto":
            grids.pop(msg_id, None)          # keep the map small
        else:
            grids[msg_id] = grid
        return self._publish(make_profile_layout(self.device, cur["order"], grids=grids))
```

`profile_view` — use `layout["order"]` and annotate each wall block:

```python
        wall = self.posts_by(identity_pub, "profile")
        layout = self.store.profile_layout(identity_pub)
        order, grids = layout["order"], layout["grids"]
        pos = {mid: i for i, mid in enumerate(order)}
        listed = [p for p in wall if p["msg_id"] in pos]
        listed.sort(key=lambda p: pos[p["msg_id"]])
        unlisted = [p for p in wall if p["msg_id"] not in pos]
        ordered_wall = unlisted + listed
        for p in ordered_wall:
            p["grid"] = grids.get(p["msg_id"], "auto")
```

Import `GRID_LAYOUTS` + `make_profile_layout` (already imported) in node.py.

- [ ] **Step 7: api.py — `POST /api/block-grid`** (mirror `/api/profile-layout`):

```python
    @app.post("/api/block-grid")
    async def block_grid(body: dict = Body(...)):
        _400(lambda: node.set_block_grid(body["msg_id"], body["grid"]))
        return {"ok": True}
```

- [ ] **Step 8: Update Slice-2 callers/tests for the new `profile_layout` shape** — grep `profile_layout(` in `hearth/` and `tests/`; any test asserting a bare list must use `["order"]`. Run `tests/test_profile_grids.py` + `tests/test_profile_layout.py` green, then full suite (3x if the flake area is touched).

- [ ] **Step 9: Commit**

```powershell
git add hearth/messages.py hearth/store.py hearth/node.py hearth/api.py tests/test_profile_grids.py tests/test_profile_layout.py
git commit -m "feat: per-block grid in the profile-layout record (5 layouts); set_block_grid + /api/block-grid; wall annotated with grid"
```

---

### Task 2: Grid pickers + five render layouts (front-end)

**Files:**
- Modify: `hearth/web/app.js` (`renderBlock` grid render + arrange-mode per-block grid picker; composer grid picker)
- Modify: `hearth/web/style.css` (five layouts)
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: `p.grid` on wall blocks; `/api/block-grid`; `/api/post` (returns `msg_id`); `ARRANGING`, `p.mine`, `openProfile`, `CURRENT_PROFILE`.
- Produces: five layout renders; a composer grid picker (multi-photo); a per-block grid selector in Arrange mode.

- [ ] **Step 1: Failing asset tests** — append to `tests/test_web_assets.py`:

```python
def test_photo_grid_layouts():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert "p.grid" in js                                    # render reads the grid
    assert "/api/block-grid" in js                           # picker persists it
    for k in ("block-grid-2", "block-grid-3", "block-hero", "block-masonry"):
        assert k in css                                      # five layouts styled
```

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: `renderBlock` — apply `p.grid`** (photo blocks only; single photo stays big). Replace the current media render:

```javascript
  if (p.blobs && p.blobs.length) {
    const grid = p.grid || "auto";
    let cls = "block-gallery";                    // auto multi-photo default
    if (p.blobs.length === 1) cls = "block-photo"; // single always big
    else if (grid === "cols2") cls = "block-grid-2";
    else if (grid === "cols3") cls = "block-grid-3";
    else if (grid === "hero") cls = "block-hero";
    else if (grid === "masonry") cls = "block-masonry";
    const media = el("div", cls);
    for (const h of p.blobs) {
      const img = document.createElement("img");
      img.src = "/api/post-blob/" + p.msg_id + "/" + h;
      img.alt = "";
      media.append(img);
    }
    block.append(media);
  }
```

- [ ] **Step 4: Arrange-mode per-block grid picker** — in `renderBlock`'s `ARRANGING && p.mine` branch, for multi-photo blocks add a `<select>` (keyboard-accessible, `aria-label="Photo layout"`) with the five options, value = `p.grid || "auto"`; on change `POST /api/block-grid {msg_id, grid}` then `await refresh(); openProfile(CURRENT_PROFILE)`:

```javascript
    if (p.blobs && p.blobs.length > 1) {
      const sel = document.createElement("select");
      sel.className = "grid-pick"; sel.setAttribute("aria-label", "Photo layout");
      for (const [v, label] of [["auto","Auto"],["cols2","2 columns"],
          ["cols3","3 columns"],["hero","Hero"],["masonry","Masonry"]]) {
        const o = document.createElement("option"); o.value = v; o.textContent = label;
        if ((p.grid || "auto") === v) o.selected = true; sel.append(o);
      }
      sel.onchange = async () => {
        await fetch("/api/block-grid", {method: "POST",
          headers: {"Content-Type": "application/json"},
          body: JSON.stringify({msg_id: p.msg_id, grid: sel.value})});
        await refresh(); if (CURRENT_PROFILE) openProfile(CURRENT_PROFILE);
      };
      block.append(sel);
    }
```

- [ ] **Step 5: Composer grid picker (multi-photo)** — in `profilePostComposer`, add a layout `<select>` (same 5 options, default auto) shown when 2+ photos are chosen (toggle its visibility on the photo input's `change`). On submit, after the `/api/post` succeeds and returns `{msg_id}`, if the pick != "auto" `POST /api/block-grid {msg_id, grid}` before the re-render. (Read the current composer submit handler; the `/api/post` response JSON has `msg_id`.)

- [ ] **Step 6: style.css — five layouts**

```css
.block-grid-2 { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; }
.block-grid-3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
.block-grid-2 img, .block-grid-3 img { width: 100%; aspect-ratio: 1/1; object-fit: cover; border-radius: 10px; }
.block-hero img:first-child { width: 100%; border-radius: 12px; margin-bottom: 8px; }
.block-hero { display: flex; flex-wrap: wrap; gap: 8px; }
.block-hero img:not(:first-child) { flex: 1 1 0; min-width: 80px; aspect-ratio: 1/1; object-fit: cover; border-radius: 8px; }
.block-masonry { column-count: 3; column-gap: 8px; }
.block-masonry img { width: 100%; margin-bottom: 8px; border-radius: 10px; break-inside: avoid; }
.grid-pick { margin-top: 6px; }
@media (max-width: 560px) { .block-grid-3, .block-masonry { column-count: 2; grid-template-columns: repeat(2,1fr); } }
```

(`.block-hero img:first-child` must be full-width — ensure the `flex` container still lets the first image span 100%; if flex fights it, wrap the first image or use `flex-basis:100%` on it.)

- [ ] **Step 7: Run asset tests + node --check + full suite** — all pass.

- [ ] **Step 8: Commit**

```powershell
git add hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat: five photo-grid layouts (auto/2col/3col/hero/masonry); composer + arrange-mode grid pickers"
```

---

### Task 3: Integration smoke + docs

**Files:**
- Test: `tests/test_profile_grids_integration.py` (new)
- Modify: `README.md`, `ROADMAP.md`

- [ ] **Step 1: Two-node integration test** (mirror `tests/test_profile_arrange_integration.py`): A posts a multi-photo profile block; `set_block_grid` to `cols3`; sync; assert B's `profile_view(A)["wall"]` block carries `grid == "cols3"` (for the block B can decrypt). Restyle to `hero`; sync; B sees `hero`. Reorder A's wall; assert the grid survives on B's side. Terminates fast; under timeout.

- [ ] **Step 2: Full suite + node --check** — all pass (run 3x to confirm the fixed flakes stay green). `node --check hearth/web/app.js` clean.

- [ ] **Step 3: Playwright/HTTP smoke (record)** — isolated node + friend, FREE ports (not demo). Post a multi-photo block; in Arrange mode change its layout across auto/2col/3col/hero/masonry → each renders distinctly; Done/persist; a friend sees the chosen layout after sync; a single-photo block stays big; the composer grid picker sets the initial layout; reorder keeps the grid. Record observations. Playwright installed; else HTTP + DOM assertions, say so.

- [ ] **Step 4: README + ROADMAP** — document 3b (five configurable photo-grid layouts, re-stylable in place via the layout record's per-block `grids` map, composer + arrange-mode pickers, friends see it after sync). Note 3c (video) is the last tractable sub-slice; split-columns + versioned-edit still deferred. Increment on the profile-canvas feature.

- [ ] **Step 5: Commit**

```powershell
git add tests/test_profile_grids_integration.py README.md ROADMAP.md
git commit -m "test+docs: configurable photo grids (3b) integration + ship notes"
```

---

## Completion

After Task 3: whole-branch review (superpowers:requesting-code-review) — focus: grid lives only in the (mutable) layout record, re-stylable; reorder preserves grids AND set_block_grid preserves order (both republish the full record); `profile_view` annotates grid (default auto; unknown ids inert); five layouts render correctly incl. single-photo-stays-big; grid pickers self-only; `/api/block-grid` validates (400 on bad); no confidentiality regression (style enum on opaque ids); the `profile_layout` return-shape change didn't break Slice-2 callers/tests; honesty guard intact; no shipped behavior broken. Then superpowers:finishing-a-development-branch — merge to `main`, push. Next: 3c (video blocks) — the last tractable sub-slice.
