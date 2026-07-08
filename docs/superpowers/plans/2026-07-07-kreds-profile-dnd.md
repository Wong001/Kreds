# Kreds Profile Canvas Slice 3a (Drag-and-Drop) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add hand-rolled Pointer-Events drag-and-drop to reorder profile blocks in Arrange mode (mouse + touch, live reorder, edge auto-scroll, cancel-restores), keeping Up/Down as the keyboard path and publishing via the existing Slice-2 layout record.

**Architecture:** Front-end only. A drag handle per block (Arrange mode, self) + a small drag controller using Pointer Events. Live reorder = `insertBefore` by neighbor midpoints (the Trello/Sortable feel); no library; no backend change. "Done" reads the DOM order and POSTs to `/api/profile-layout` exactly as Slice 2.

**Tech Stack:** vanilla-JS (Pointer Events), CSS; pytest asset tests; `node --check`.

**Spec:** `docs/superpowers/specs/2026-07-07-kreds-profile-dnd-design.md`

## Global Constraints

- Branch: `kreds-profile-dnd` off `main` (already created + checked out — do NOT re-branch).
- Quality over shortcuts (stated user principle): hand-rolled Pointer Events, NOT native HTML5 DnD; unified mouse+touch+pen; no dependency; sleek.
- Test runner: `timeout 150 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3`; full suite green each commit (TOR_E2E skip; rare pre-existing flake ~1-in-5 — re-run to confirm). `node --check hearth/web/app.js` clean.
- NO backend/protocol/store change. Reuses the Slice-2 `KIND_PROFILE_LAYOUT` record + `POST /api/profile-layout`; "Done" publish flow (with its `r.ok` failure handling) is unchanged.
- Drag is active ONLY in the existing self-only Arrange mode (`ARRANGING && p.mine`); a visitor can never drag (server-side `set_profile_layout` signs the local identity — unchanged).
- Up/Down buttons + `updateArrowStates()` stay exactly as they are (keyboard/screen-reader path).
- `touch-action: none` on the drag handle so a touch-drag doesn't scroll the page. Respect `prefers-reduced-motion` (skip settle animation). No leaked listeners; `pointercancel` restores the pre-drag order.

---

### Task 1: Pointer-Events drag controller + handle + styles

**Files:**
- Modify: `hearth/web/app.js` (drag handle in `renderBlock`'s arrange branch; `startBlockDrag` controller)
- Modify: `hearth/web/style.css` (handle, dragging/lifted style, `touch-action`, reduced-motion)
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: `renderBlock` arrange branch (app.js:284), `#profile-wall`, `updateArrowStates`, `ARRANGING`, `p.mine`.
- Produces: a `.drag-handle` per block in arrange mode; `startBlockDrag(block, ev)` drives the drag via Pointer Events; blocks stay `data-msg-id` so "Done" reads order unchanged.

- [ ] **Step 1: Failing asset tests** — append to `tests/test_web_assets.py`:

```python
def test_pointer_dnd_present():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    # hand-rolled pointer-events drag (NOT native HTML5 DnD)
    assert "setPointerCapture" in js
    assert "pointermove" in js and "pointerup" in js and "pointercancel" in js
    assert 'draggable="true"' not in js and "dragstart" not in js   # not native DnD
    # a drag handle + touch-action guard + Up/Down retained
    assert "drag-handle" in js or "drag-handle" in css
    assert "touch-action" in css
    assert "arr-up" in js and "arr-down" in js          # keyboard path kept
    # no new dependency (single app.js script)
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert html.count("<script") == 1
```

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: Add the drag handle** in `renderBlock`'s arrange branch (app.js:284, alongside Up/Down):

```javascript
  if (ARRANGING && p.mine) {
    const handle = el("button", "drag-handle", "≡");   // ≡ grip
    handle.type = "button";
    handle.setAttribute("aria-label", "Drag to reorder");
    handle.addEventListener("pointerdown", (ev) => startBlockDrag(block, ev));
    block.append(handle);
    const up = el("button", "arr arr-up", "↑"); up.type = "button";
    up.setAttribute("aria-label", "Move up");
    up.onclick = () => { const prev = block.previousElementSibling;
      if (prev) { block.parentNode.insertBefore(block, prev); updateArrowStates(); } };
    const down = el("button", "arr arr-down", "↓"); down.type = "button";
    down.setAttribute("aria-label", "Move down");
    down.onclick = () => { const next = block.nextElementSibling;
      if (next) { block.parentNode.insertBefore(next, block); updateArrowStates(); } };
    block.append(up, down);
  }
```

- [ ] **Step 4: The drag controller** — add near `renderBlock`:

```javascript
// Hand-rolled Pointer-Events block drag (mouse + touch + pen) - the sleek,
// dependency-free path (native HTML5 DnD has no touch + an uncustomizable
// ghost). Live reorder by neighbor midpoints; Up/Down remain the keyboard
// path; "Done" reads the resulting DOM order and publishes as in Slice 2.
function startBlockDrag(block, ev) {
  if (ev.button != null && ev.button !== 0) return;   // primary pointer only
  ev.preventDefault();
  const wall = document.getElementById("profile-wall");
  const before = [...wall.children];                  // snapshot for cancel-restore
  block.classList.add("dragging");
  block.setPointerCapture(ev.pointerId);

  const afterElement = (y) => {
    const els = [...wall.querySelectorAll(".block:not(.dragging)")];
    let best = null, bestOffset = -Infinity;
    for (const child of els) {
      const box = child.getBoundingClientRect();
      const offset = y - (box.top + box.height / 2);
      if (offset < 0 && offset > bestOffset) { bestOffset = offset; best = child; }
    }
    return best;   // element to insert BEFORE, or null => append at end
  };

  const onMove = (e) => {
    const after = afterElement(e.clientY);
    if (after == null) wall.appendChild(block);
    else if (after !== block.nextSibling) wall.insertBefore(block, after);
    // edge auto-scroll so a long canvas stays reorderable
    const M = 70;
    if (e.clientY < M) window.scrollBy(0, -12);
    else if (e.clientY > window.innerHeight - M) window.scrollBy(0, 12);
  };
  const finish = () => {
    block.removeEventListener("pointermove", onMove);
    block.removeEventListener("pointerup", onUp);
    block.removeEventListener("pointercancel", onCancel);
    block.classList.remove("dragging");
    updateArrowStates();
  };
  const onUp = () => finish();
  const onCancel = () => {                             // restore pre-drag order
    before.forEach((el2) => wall.appendChild(el2));
    finish();
  };
  block.addEventListener("pointermove", onMove);
  block.addEventListener("pointerup", onUp);
  block.addEventListener("pointercancel", onCancel);
}
```

(Listeners are bound to `block` and captured via `setPointerCapture`, so moves route to it even if the pointer outpaces the element; all three are removed in `finish` — no leaks.)

- [ ] **Step 5: style.css — handle, lifted style, touch-action, reduced-motion**

```css
.drag-handle { cursor: grab; touch-action: none; border: none; background: none;
  color: var(--ink-2); font-size: 18px; line-height: 1; padding: 2px 8px;
  margin-top: 6px; }
.drag-handle:active { cursor: grabbing; }
.block.dragging { opacity: .85; box-shadow: var(--shadow);
  border-radius: 12px; transition: box-shadow .12s; }
@media (prefers-reduced-motion: reduce) { .block.dragging { transition: none; } }
```

- [ ] **Step 6: Run asset tests + node --check + full suite** — all pass.

- [ ] **Step 7: Commit**

```powershell
git add hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat: pointer-events drag-and-drop reorder for profile blocks (mouse+touch, live reorder, edge autoscroll); Up/Down kept for keyboard"
```

---

### Task 2: Integration smoke + docs

**Files:**
- Modify: `README.md`, `ROADMAP.md`
- Test: manual/Playwright smoke

- [ ] **Step 1: Full suite + JS check** — all pass; `node --check hearth/web/app.js` clean. Grep confirms no native-DnD leftovers: `grep -n "draggable=\|dragstart\|ondrop" hearth/web/app.js` → none.

- [ ] **Step 2: Playwright smoke (record)** — isolated node + a friend on FREE ports (NOT demo ports 7101-7104/7201-7204). In Arrange mode: (a) mouse-drag a block (Playwright `mouse.move`/`down`/`up` or `dragTo`) to a new position → blocks live-reorder → release lands it; (b) "Done" → reload → order persists; a friend's synced `profile_view` matches; (c) an emulated **touch** drag (pointer/touchscreen) reorders too; (d) **keyboard** Up/Down still reorders; (e) a drag near the bottom auto-scrolls a long canvas; (f) `pointercancel` (e.g. Escape/blur mid-drag) restores order. Record concrete observations. If Playwright can't drive pointer drag reliably, note it and assert the DOM-order/publish path via a scripted pointer-event dispatch, and say so.

- [ ] **Step 3: README + ROADMAP** — document 3a: pointer-events drag-and-drop reorder (mouse+touch, hand-rolled, no library; Up/Down retained for keyboard; persists via the Slice-2 layout record). Note the remaining tractable sub-slices (3b configurable grids, 3c video) + the deferred heavy two (split-columns, versioned-edit). Increment on the profile-canvas feature.

- [ ] **Step 4: Commit**

```powershell
git add README.md ROADMAP.md
git commit -m "docs: profile block drag-and-drop (3a) shipped; remaining canvas slices noted"
```

---

## Completion

After Task 2: whole-branch review (superpowers:requesting-code-review) — focus: hand-rolled Pointer-Events (no native DnD, no dependency); drag self-only in Arrange mode (no visitor reorder; server-side still signs local identity); Up/Down keyboard path intact; "Done" publishes the DOM order unchanged (Slice-2 flow + failure handling); no leaked pointer listeners; `pointercancel` restores order; `touch-action`/reduced-motion honored; no backend change; honesty guard intact; no shipped behavior broken. Then superpowers:finishing-a-development-branch — merge to `main`, push. Next: 3b (configurable photo-grid layouts), then 3c (video blocks).
