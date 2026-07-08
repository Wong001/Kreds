# Kreds Profile Bento Canvas Phase A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the profile wall into a bento grid — each block picks a width (Small 1-col / Wide 2-col / Full 3-col), blocks auto-pack in a CSS 3-column grid, and per-block settings (size, photo-grid, move up/down) live in a modal opened by tapping a block in Arrange mode.

**Architecture:** Extend the existing latest-wins `KIND_PROFILE_LAYOUT` record (already holds `order` + `grids`) with a per-block `sizes` map. Render `#profile-wall` as `display:grid; grid-template-columns:repeat(3,1fr); grid-auto-flow:row dense`, blocks span by size. Replace the inline drag-handle / Up-Down / grid-`<select>` on each block with: whole-block drag to reorder (tap vs drag by movement threshold) + a per-block settings modal. No hand-rolled packing engine (CSS grid packs); free x/y + corner-drag + pinch are Phase B.

**Tech Stack:** Python 3.12, sqlite3, FastAPI, pytest; vanilla-JS client (no bundler/deps); `node --check`.

**Spec:** `docs/superpowers/specs/2026-07-07-kreds-profile-bento-design.md`

## Global Constraints

- Branch: `kreds-profile-bento` off `main` (already created + checked out — do NOT re-branch).
- Quality over shortcuts (user principle). Test runner: `timeout 180 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3`; full suite green each commit (TOR_E2E skip; the two prior flakes are fixed — keep them fixed). `node --check hearth/web/app.js` clean. ASCII-only Python prints.
- Size enum EXACTLY: `("small","wide","full")`. New/absent block = `full` (default; canvas looks like today's stack until shrunk). Reorder must NEVER drop sizes/grids; set_block_size must NEVER change order/grids (all read the current record + republish the full record — the discipline already used for `grids`).
- Size lives ONLY in the layout record (mutable), not the post. Confidentiality unchanged: size is a style enum on already-disclosed opaque `msg_id`s; an unknown id in `sizes` is inert (no throw), like `order`/`grids`.
- Self-only: `set_block_size`/`set_profile_layout` sign the local identity (no target); the settings modal + drag render only for `p.mine` in Arrange mode. Visitors and non-arrange views are read-only.
- Honesty guard: no receipts popover.
- Phase B (OUT of scope): free x/y placement, corner-drag resize, two-finger pinch, height/row spans, true masonry.

---

### Task 1: `sizes` in the layout record + `set_block_size` + annotate

**Files:**
- Modify: `hearth/messages.py` (`make_profile_layout` sizes param + `SIZE_LAYOUTS` + validation)
- Modify: `hearth/store.py` (`profile_layout` returns `{order, grids, sizes}`)
- Modify: `hearth/node.py` (`set_profile_layout`/`set_block_grid` preserve sizes; `set_block_size`; `profile_view` annotates `size`)
- Modify: `hearth/api.py` (`POST /api/block-size`)
- Test: `tests/test_profile_sizes.py` (new)

**Interfaces:**
- Produces: `SIZE_LAYOUTS`; `make_profile_layout(device, order, grids=None, sizes=None, now=None)`; `Store.profile_layout(id) -> {"order":[...],"grids":{...},"sizes":{...}}`; `Node.set_block_size(msg_id, size) -> str`; `profile_view` wall rows gain `"size"`; `POST /api/block-size {msg_id, size}`.

- [ ] **Step 1: Branch exists — skip; start at Step 2.**

- [ ] **Step 2: Failing tests** — `tests/test_profile_sizes.py` (mirror the `single_node` fixture in `tests/test_profile_grids.py`):

```python
def test_set_block_size_annotates_and_preserves_order_and_grids(single_node):
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    b = n.compose_post("B", scope="kreds", placement="profile")
    n.set_profile_layout([a, b]); n.set_block_grid(a, "cols3")
    n.set_block_size(a, "wide")
    wall = {p["msg_id"]: p for p in n.profile_view(n.identity_pub)["wall"]}
    assert wall[a]["size"] == "wide" and wall[b]["size"] == "full"   # default full
    assert wall[a]["grid"] == "cols3"                                # grid preserved
    assert [p["msg_id"] for p in n.profile_view(n.identity_pub)["wall"]] == [a, b]  # order kept

def test_reorder_and_grid_preserve_sizes(single_node):
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    b = n.compose_post("B", scope="kreds", placement="profile")
    n.set_block_size(a, "small")
    n.set_profile_layout([b, a])                       # reorder
    n.set_block_grid(a, "hero")                        # grid change
    wall = {p["msg_id"]: p for p in n.profile_view(n.identity_pub)["wall"]}
    assert wall[a]["size"] == "small"                  # size survived both

def test_set_block_size_full_clears(single_node):
    n = single_node
    a = n.compose_post("A", scope="kreds", placement="profile")
    n.set_block_size(a, "wide"); n.set_block_size(a, "full")
    assert n.store.profile_layout(n.identity_pub)["sizes"] == {}   # default clears entry

def test_set_block_size_rejects_bad(single_node):
    import pytest
    a = single_node.compose_post("A", scope="kreds", placement="profile")
    with pytest.raises(ValueError): single_node.set_block_size(a, "huge")
    with pytest.raises(ValueError): single_node.set_block_size("nothex", "wide")

def test_layout_sizes_validation():
    from hearth.messages import validate_payload, KIND_PROFILE_LAYOUT
    base = {"kind": KIND_PROFILE_LAYOUT, "created_at": 1.0, "order": []}
    ok,_ = validate_payload({**base, "sizes": {"a"*64: "wide"}}); assert ok
    ok,_ = validate_payload({**base}); assert ok                       # missing sizes ok
    ok,_ = validate_payload({**base, "sizes": {"a"*64: "huge"}}); assert not ok
    ok,_ = validate_payload({**base, "sizes": {"nothex": "wide"}}); assert not ok
    ok,_ = validate_payload({**base, "sizes": ["x"]}); assert not ok    # not a dict

def test_api_block_size(tmp_path):
    from fastapi.testclient import TestClient
    from hearth.api import build_app
    from hearth.node import HearthNode
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    c = TestClient(build_app(node))
    a = node.compose_post("A", scope="kreds", placement="profile")
    assert c.post("/api/block-size", json={"msg_id": a, "size": "wide"}).status_code == 200
    assert c.post("/api/block-size", json={"msg_id": a, "size": "huge"}).status_code == 400
```

- [ ] **Step 3: Run — expect failure.**

- [ ] **Step 4: messages.py** — add the enum + extend make/validate (mirror the existing `grids` handling exactly):

```python
SIZE_LAYOUTS = ("small", "wide", "full")

def make_profile_layout(device: DeviceKeys, order: Sequence[str],
                        grids: Optional[dict] = None, sizes: Optional[dict] = None,
                        now: Optional[float] = None) -> SignedMessage:
    return device.sign_message({
        "kind": KIND_PROFILE_LAYOUT, "order": list(order),
        "grids": dict(grids or {}), "sizes": dict(sizes or {}),
        "created_at": _now(now),
    })
```

In `validate_payload`'s `KIND_PROFILE_LAYOUT` branch, after the existing `grids` validation and before `return True, "ok"`:

```python
        sizes = p.get("sizes", {})
        if not isinstance(sizes, dict) or len(sizes) > MAX_LAYOUT:
            return False, "bad layout sizes"
        for k, v in sizes.items():
            if not _is_hex64(k) or v not in SIZE_LAYOUTS:
                return False, "bad layout size"
```

- [ ] **Step 5: store.py — `profile_layout` returns `sizes` too**

```python
                if best is None or key > best_key:
                    best = {"order": p.get("order", []),
                            "grids": p.get("grids", {}),
                            "sizes": p.get("sizes", {})}
                    best_key = key
        return best or {"order": [], "grids": {}, "sizes": {}}
```

- [ ] **Step 6: node.py**

`set_profile_layout` and `set_block_grid` must now carry `sizes` through. In `set_profile_layout`, republish with both preserved:

```python
        cur = self.store.profile_layout(self.identity_pub)
        return self._publish(make_profile_layout(self.device, order,
                                                 grids=cur["grids"], sizes=cur["sizes"]))
```

In `set_block_grid`, the republish line becomes (preserve order + sizes):

```python
        return self._publish(make_profile_layout(self.device, cur["order"],
                                                 grids=grids, sizes=cur["sizes"]))
```

New `set_block_size` (mirror `set_block_grid`):

```python
    def set_block_size(self, msg_id: str, size: str) -> str:
        if size not in SIZE_LAYOUTS:
            raise ValueError("bad size")
        if not (isinstance(msg_id, str) and len(msg_id) == 64
                and all(c in "0123456789abcdef" for c in msg_id)):
            raise ValueError("bad msg_id")
        cur = self.store.profile_layout(self.identity_pub)
        sizes = dict(cur["sizes"])
        if size == "full":
            sizes.pop(msg_id, None)          # default -> keep the map small
        else:
            sizes[msg_id] = size
        if len(sizes) > MAX_LAYOUT:          # pre-check -> 400, not a 500 from _publish
            raise ValueError("too many sized blocks")
        return self._publish(make_profile_layout(self.device, cur["order"],
                                                 grids=cur["grids"], sizes=sizes))
```

Import `SIZE_LAYOUTS` from `.messages`. In `profile_view`, alongside the existing `grid` annotation, add:

```python
        sizes = layout["sizes"]
        for p in ordered_wall:
            p["grid"] = grids.get(p["msg_id"], "auto")
            p["size"] = sizes.get(p["msg_id"], "full")
```

(Merge with the existing grid-annotation loop — one loop, both fields.)

- [ ] **Step 7: api.py — `POST /api/block-size`** (mirror `/api/block-grid`):

```python
    @app.post("/api/block-size")
    async def block_size(body: dict = Body(...)):
        _400(lambda: node.set_block_size(body["msg_id"], body["size"]))
        return {"ok": True}
```

- [ ] **Step 8: Run `tests/test_profile_sizes.py` + `tests/test_profile_grids.py` (regression on the shared record) green, then full suite. Commit.**

```powershell
git add hearth/messages.py hearth/store.py hearth/node.py hearth/api.py tests/test_profile_sizes.py
git commit -m "feat: per-block size in the profile-layout record (small/wide/full); set_block_size + /api/block-size; wall annotated with size"
```

---

### Task 2: Bento grid render (front-end)

**Files:**
- Modify: `hearth/web/app.js` (`renderBlock` adds the size span class)
- Modify: `hearth/web/style.css` (`#profile-wall` grid + size spans + mobile)
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: `p.size` on wall blocks.
- Produces: `#profile-wall` 3-col grid; `.block.size-small/.size-wide/.size-full` spans.

- [ ] **Step 1: Failing asset test** — append to `tests/test_web_assets.py`:

```python
def test_bento_grid_render():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert "p.size" in js                                  # render reads the size
    assert "size-" in js                                   # span class applied
    assert "grid-auto-flow" in css                         # bento packing
    for k in ("size-small", "size-wide", "size-full"):
        assert k in css
```

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: `renderBlock` — apply the size span class.** Right after `block.dataset.msgId = p.msg_id;`:

```javascript
  block.classList.add("size-" + (p.size || "full"));   // bento width span
```

- [ ] **Step 4: style.css — the bento grid.** Replace the current `#profile-wall` rule (find it; it's a vertical block container) with:

```css
#profile-wall { display: grid; grid-template-columns: repeat(3, 1fr);
  grid-auto-flow: row dense; gap: 12px; align-items: start; }
.block { min-width: 0; }                       /* let grid items shrink, no overflow */
.block.size-small { grid-column: span 1; }
.block.size-wide  { grid-column: span 2; }
.block.size-full  { grid-column: span 3; }
@media (max-width: 560px) {
  #profile-wall { grid-template-columns: repeat(2, 1fr); }
  .block.size-full, .block.size-wide { grid-column: span 2; }  /* clamp to 2 cols */
  .block.size-small { grid-column: span 1; }
}
```

Ensure media fills its cell without overflow: confirm `.block img`, `.block video`, and the photo-grid containers use `width:100%` / `max-width:100%` (they do from 3b/3c — verify `.block-photo img`, `.block-gallery`, `.block-video video`). If a `.block` had a fixed max-width from the old stack layout, remove it so the grid controls width.

- [ ] **Step 5: Run asset tests + `node --check` + full suite. Commit.**

```powershell
git add hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat: bento grid render - profile wall is a 3-col CSS grid, blocks span by size (2-col on mobile)"
```

---

### Task 3: Per-block settings modal + tap-vs-drag (front-end)

**Files:**
- Modify: `hearth/web/index.html` (`#block-settings` dialog markup)
- Modify: `hearth/web/app.js` (`openBlockSettings`; block tap-vs-drag; remove inline handle/Up-Down/grid-`<select>`)
- Modify: `hearth/web/style.css` (`#block-settings` card)
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: `p.size`/`p.grid`/`p.mine`/`ARRANGING`; `startBlockDrag(block, ev)`; `updateArrowStates`; `/api/block-size`, `/api/block-grid`; `openProfile`, `CURRENT_PROFILE`, `refresh`.
- Produces: `openBlockSettings(p, block)`; a settings dialog with size + grid + move controls; whole-block drag.

- [ ] **Step 1: Failing asset test** — append to `tests/test_web_assets.py`:

```python
def test_block_settings_modal():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert 'id="block-settings"' in html                 # modal markup
    assert "openBlockSettings" in js                     # opener
    assert "/api/block-size" in js                       # size action
    assert "drag-handle" not in js                       # inline 3-line handle removed
    assert 'className = "grid-pick"' not in js and "grid-pick" not in js  # inline select removed
```

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: index.html — add the dialog** (after the `#circle-overlay` block, mirroring its role/close pattern):

```html
  <div class="hidden" id="block-settings" role="dialog" aria-modal="true" aria-label="Block settings">
    <div class="block-settings-card">
      <button class="closeoverlay" id="block-settings-close" aria-label="Close">&times;</button>
      <h3 class="block-settings-title">Block</h3>
      <div id="block-settings-body"></div>
    </div>
  </div>
```

- [ ] **Step 4: app.js — `openBlockSettings` + close wiring.** Add near the other profile helpers:

```javascript
function closeBlockSettings() {
  document.getElementById("block-settings").classList.add("hidden");
}
// Build a labelled button group; onPick(value) fires on click.
function settingsGroup(label, options, current, onPick) {
  const wrap = el("div", "settings-group");
  wrap.append(el("div", "settings-label", label));
  const row = el("div", "settings-row");
  for (const [v, text] of options) {
    const b = el("button", "settings-opt" + (v === current ? " on" : ""), text);
    b.type = "button";
    b.onclick = () => onPick(v);
    row.append(b);
  }
  wrap.append(row); return wrap;
}
async function postJSON(url, body) {
  const r = await fetch(url, {method: "POST",
    headers: {"Content-Type": "application/json"}, body: JSON.stringify(body)});
  if (!r.ok) alert("Couldn't save: " + await r.text());
  return r.ok;
}
function openBlockSettings(p, block) {
  const body = document.getElementById("block-settings-body");
  body.textContent = "";
  // Size (all blocks)
  body.append(settingsGroup("Size",
    [["small","Small"],["wide","Wide"],["full","Full"]], p.size || "full",
    async (v) => {
      if (await postJSON("/api/block-size", {msg_id: p.msg_id, size: v})) {
        p.size = v;
        block.classList.remove("size-small","size-wide","size-full");
        block.classList.add("size-" + v);            // apply in place (keep pending order)
        openBlockSettings(p, block);                 // refresh the highlighted option
      }
    }));
  // Photo grid (multi-photo blocks only)
  if (p.media !== "video" && p.blobs && p.blobs.length > 1) {
    body.append(settingsGroup("Photo layout",
      [["auto","Auto"],["cols2","2 cols"],["cols3","3 cols"],["hero","Hero"],["masonry","Masonry"]],
      p.grid || "auto",
      async (v) => {
        if (await postJSON("/api/block-grid", {msg_id: p.msg_id, grid: v})) {
          p.grid = v;
          const media = block.querySelector(".block-photo,.block-gallery,"
            + ".block-grid-2,.block-grid-3,.block-hero,.block-masonry");
          if (media) media.className = photoGridClass(p.grid, p.blobs.length);
          openBlockSettings(p, block);
        }
      }));
  }
  // Move (keyboard/touch-accessible reorder; DOM-only until Done, like Up/Down was)
  const move = el("div", "settings-group");
  move.append(el("div", "settings-label", "Move"));
  const row = el("div", "settings-row");
  const up = el("button", "settings-opt", "Up"); up.type = "button";
  up.onclick = () => { const prev = block.previousElementSibling;
    if (prev) { block.parentNode.insertBefore(block, prev); updateArrowStates(); } };
  const down = el("button", "settings-opt", "Down"); down.type = "button";
  down.onclick = () => { const next = block.nextElementSibling;
    if (next) { block.parentNode.insertBefore(next, block); updateArrowStates(); } };
  row.append(up, down); move.append(row); body.append(move);
  document.getElementById("block-settings").classList.remove("hidden");
}
document.getElementById("block-settings-close").onclick = closeBlockSettings;
document.getElementById("block-settings").addEventListener("click", (ev) => {
  if (ev.target.id === "block-settings") closeBlockSettings();   // backdrop
});
document.addEventListener("keydown", (ev) => {
  if (ev.key === "Escape") closeBlockSettings();
});
```

- [ ] **Step 5: app.js — replace the inline arrange controls with whole-block tap-vs-drag.** In `renderBlock`, delete the entire `if (ARRANGING && p.mine) { ... }` block that adds the handle, Up/Down, and grid `<select>` (lines that create `drag-handle`, `arr-up`, `arr-down`, and the `grid-pick` select). Replace with:

```javascript
  if (ARRANGING && p.mine) {
    block.classList.add("arranging");
    block.style.touchAction = "none";
    // A small drag reorders; a tap (sub-threshold release) opens settings.
    block.addEventListener("pointerdown", (ev) => {
      if ((ev.button != null && ev.button !== 0) || !ev.isPrimary) return;
      if (ev.target.closest("button, a, select, video")) return;   // let controls work
      const sx = ev.clientX, sy = ev.clientY;
      let handed = false;
      const move = (e) => {
        if (!handed && Math.hypot(e.clientX - sx, e.clientY - sy) > 6) {
          handed = true; teardown(); startBlockDrag(block, ev);    // hand off to the drag controller
        }
      };
      const up = () => { teardown(); if (!handed) openBlockSettings(p, block); };
      const teardown = () => {
        window.removeEventListener("pointermove", move);
        window.removeEventListener("pointerup", up);
        window.removeEventListener("pointercancel", up);
      };
      window.addEventListener("pointermove", move);
      window.addEventListener("pointerup", up);
      window.addEventListener("pointercancel", up);
    });
  }
```

Verify `startBlockDrag(block, ev)` still works when called from this handler after ~6px of movement (it calls `ev.preventDefault()` + `setPointerCapture(ev.pointerId)` and installs its own move/up/cancel/lostpointercapture listeners — the pointer is still active, so capture succeeds). If capture on the original `ev` proves unreliable mid-gesture, fold the threshold INTO `startBlockDrag` instead (defer adding `.dragging` + the reorder listeners until movement exceeds 6px; on sub-threshold release call an `onTap` callback) — either way the outcome is: tap → modal, drag → reorder. Keep the existing `lostpointercapture` safety net.

- [ ] **Step 6: style.css — settings card + arrange affordance.** Add:

```css
#block-settings { position: absolute; inset: 0; z-index: 30; display: grid; place-items: center;
  background: rgba(0,0,0,.45); backdrop-filter: blur(3px); }
#block-settings.hidden { display: none; }
.block-settings-card { position: relative; background: var(--surface); border: 1px solid var(--line-2);
  border-radius: 16px; padding: 22px 22px 18px; min-width: 260px; max-width: 340px; }
.block-settings-title { margin: 0 0 12px; font-size: 15px; }
.settings-group { margin-bottom: 14px; }
.settings-label { font-size: 12px; color: var(--ink-2); margin-bottom: 6px; text-transform: uppercase; letter-spacing: .04em; }
.settings-row { display: flex; flex-wrap: wrap; gap: 8px; }
.settings-opt { border: 1px solid var(--line-2); background: var(--surface); color: var(--ink-1);
  border-radius: 10px; padding: 7px 12px; cursor: pointer; font-size: 13px; }
.settings-opt.on { border-color: var(--accent); color: var(--accent); }
.block.arranging { cursor: grab; outline: 1px dashed var(--line-2); outline-offset: 2px; }
```

(Use the real token names present in style.css — `--surface`, `--line-2`, `--ink-1`, `--ink-2`, `--accent`; grep to confirm exact names and adjust.)

- [ ] **Step 7: Run asset tests + `node --check` + full suite. Commit.**

```powershell
git add hearth/web/index.html hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat: per-block settings modal (size/photo-grid/move) opened by tapping a block in Arrange; whole-block drag replaces the 3-line handle + inline controls"
```

---

### Task 4: Integration smoke + docs

**Files:**
- Test: `tests/test_profile_bento_integration.py` (new)
- Modify: `README.md`, `ROADMAP.md`

- [ ] **Step 1: Two-node integration test** (mirror `tests/test_profile_grids_integration.py`): A posts three profile blocks; `set_block_size` them to `wide`/`small`/`full`; sync A→B; assert B's `profile_view(A)["wall"]` carries the matching `size` per block. Reorder A's wall + change a grid; assert sizes survive on B's side. Terminates fast; under timeout.

- [ ] **Step 2: Full suite + `node --check`** — all pass (run twice to confirm the fixed flakes stay green).

- [ ] **Step 3: Playwright/HTTP smoke (record)** — isolated node + a friend on FREE ports (not demo). In Arrange mode: tap a block → the settings modal opens; set Small/Wide/Full → the block's column span changes and the wall repacks; a small drag on a block reorders it (modal does NOT open); Done persists; a friend sees the bento at the chosen sizes after sync; the 3-line handle + inline Up/Down/grid-select are gone; a multi-photo block's grid is set from the modal; mobile viewport packs into 2 columns. Record concrete observations. Playwright installed; else assert DOM (`.size-wide` etc. + `#block-settings` shown) + persisted size via `/api/profile`, and say so.

- [ ] **Step 4: README + ROADMAP** — document the bento canvas Phase A (3 width sizes, CSS-grid pack, tap-a-block settings modal consolidating size/photo-grid/move, whole-block drag replacing the handle). State Phase B (free x/y placement, corner-drag, two-finger pinch, height spans, masonry) is the deferred next step. Increment on the profile-canvas feature.

- [ ] **Step 5: Commit**

```powershell
git add tests/test_profile_bento_integration.py README.md ROADMAP.md
git commit -m "test+docs: profile bento canvas Phase A integration + ship notes; Phase B (free placement/pinch) deferred"
```

---

## Completion

After Task 4: whole-branch review (superpowers:requesting-code-review) — focus: size lives only in the (mutable) layout record; reorder + set_block_grid preserve sizes AND set_block_size preserves order + grids (all republish the full latest-wins record from one winner); `profile_view` annotates size (default full; unknown ids inert); the bento grid renders + clamps to 2 cols on mobile with no overflow; the settings modal is self+arrange-only and keyboard-accessible (Esc/backdrop close); **tap opens settings, a drag reorders** (the handoff to `startBlockDrag` works, no stuck-drag regression, `lostpointercapture` net intact); the inline handle/Up-Down/grid-`<select>` are gone with their functions preserved in drag + modal; `/api/block-size` validates (400 on bad); no confidentiality regression; the `profile_layout` three-field record didn't break Slice-2/3b/3c callers/tests; honesty guard; no shipped behavior broken (photo/video/text blocks, grids, feed, DMs). Then superpowers:finishing-a-development-branch — merge to `main`, push. Next: Phase B, or Windows packaging.
