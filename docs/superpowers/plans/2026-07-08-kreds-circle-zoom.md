# Circle Overlay Zoom/Pan Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The expanded circle overlay fills the screen, scales its ring radii with friend count, and supports anchored wheel/pinch zoom + drag pan + fit-reset.

**Architecture:** viewBox-as-camera per the approved spec (`docs/superpowers/specs/2026-07-08-kreds-circle-zoom-design.md`). `buildCircle()` emits world-coordinate geometry (ring radius grows with occupancy); a `circleCamera` object rewrites the `<svg>` `viewBox` for zoom/pan; labels toggle by on-screen node pitch. No DOM transforms, no new dependencies.

**Tech Stack:** vanilla JS (hearth/web/app.js), CSS (hearth/web/style.css), static pytest assertions (tests/test_web_assets.py), Playwright live smoke.

## Global Constraints

- Constants (spec): `CIRCLE_SPACING = 64` world units, ring gap `>= 78`, world margin `60`, max zoom `fitW / 8`, tap-vs-drag threshold `6` px, label threshold: on-screen node pitch `>= 56` px.
- Pointer Events only — `pointerdown/move/up/cancel` + `lostpointercapture`; never native HTML5 DnD; `touch-action: none` on the overlay SVG.
- The compact rail minimap call of `buildCircle` must behave byte-identically (no `scaleWithCount`).
- `prefers-reduced-motion: reduce` disables the label fade transition.
- No AI co-author trailers on commits (project policy since 2026-07-08).
- All work in `C:\Users\Wong\Desktop\Hearth`, venv at `.venv` (`.venv\Scripts\Activate.ps1`).

---

### Task 1: World scaling in buildCircle

**Files:**
- Modify: `hearth/web/app.js` (buildCircle, ~line 732; openCircleOverlay, ~line 837)
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Produces: `buildCircle(svg, kreds, opts)` honoring new `opts.scaleWithCount: true` — recomputes `innerR`, `outerR`, `size` from ring occupancy; always sets `svg.dataset.worldSize = size`. Constants `CIRCLE_SPACING`, `CIRCLE_RING_GAP`, `CIRCLE_MARGIN`, helper `ringRadius(count, baseR)`. Task 3's camera reads `svg.dataset.worldSize`.

- [ ] **Step 1: Write the failing test** — append to `tests/test_web_assets.py`:

```python
def test_circle_world_scales_with_count():
    # Spec 2026-07-08-kreds-circle-zoom: the overlay circle gets BIGGER
    # with friend count, never denser - ring radius derives from occupancy
    # (constant node spacing), and the world size is published on the svg
    # for the camera. The rail's call must NOT opt in.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "CIRCLE_SPACING = 64" in js
    assert "CIRCLE_RING_GAP = 78" in js
    assert "CIRCLE_MARGIN = 60" in js
    assert "function ringRadius(" in js
    assert "scaleWithCount" in js
    assert "dataset.worldSize" in js
    # overlay call opts in; rail call does not
    overlay_call = js.split("function openCircleOverlay")[1][:400]
    assert "scaleWithCount: true" in overlay_call
    rail_fn = js.split("function renderCircleRail")[1][:900]
    assert "scaleWithCount" not in rail_fn
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py::test_circle_world_scales_with_count -q`
Expected: FAIL (`CIRCLE_SPACING = 64` not found)

- [ ] **Step 3: Implement.** In `hearth/web/app.js`, directly above `function buildCircle(...)` (after the `placeRing` helper), add:

```js
// World-scaling constants (spec 2026-07-08-kreds-circle-zoom): the circle
// grows with friend count instead of packing nodes tighter. SPACING is the
// world-unit distance between adjacent node centers on a ring; RING_GAP the
// minimum inner->outer separation; MARGIN clears ring/name labels at the rim.
const CIRCLE_SPACING = 64;
const CIRCLE_RING_GAP = 78;
const CIRCLE_MARGIN = 60;

// Radius that gives `count` nodes CIRCLE_SPACING of arc each, never below
// the ring's cosmetic base radius (small circles keep today's proportions).
function ringRadius(count, baseR) {
  return Math.max(baseR, (count * CIRCLE_SPACING) / (2 * Math.PI));
}
```

Then change the head of `buildCircle` from:

```js
function buildCircle(svg, kreds, opts) {
  const {size, innerR, outerR, youR, nodeR, big} = opts;
  svg.setAttribute("viewBox", "0 0 " + size + " " + size);
```

to:

```js
function buildCircle(svg, kreds, opts) {
  let {size, innerR, outerR, youR, nodeR, big} = opts;
  if (opts.scaleWithCount) {
    const nInner = kreds.filter(k => k.ring === "inner").length;
    innerR = ringRadius(nInner, innerR);
    outerR = Math.max(ringRadius(kreds.length - nInner, outerR),
                      innerR + CIRCLE_RING_GAP);
    size = 2 * (outerR + CIRCLE_MARGIN);
  }
  svg.dataset.worldSize = size;
  svg.setAttribute("viewBox", "0 0 " + size + " " + size);
  // ... (ring guides, ring labels, you-node - unchanged) ...
  const inner = kreds.filter(k => k.ring === "inner");
  const outer = kreds.filter(k => k.ring !== "inner");
  // Actual minimum node pitch (world units of arc between adjacent nodes on
  // the fullest ring). updateCircleLabels compares THIS, not the SPACING
  // floor: a small circle's real pitch is far above the floor, so its labels
  // stay visible at fit even on phone-sized viewports (whole-branch review,
  // Important #1).
  const pitches = [];
  if (inner.length) pitches.push(2 * Math.PI * innerR / inner.length);
  if (outer.length) pitches.push(2 * Math.PI * outerR / outer.length);
  svg.dataset.nodePitch = pitches.length ? Math.min(...pitches) : CIRCLE_SPACING;
```

And in `openCircleOverlay()` change the call to:

```js
  buildCircle(svg, KREDS, {size: 440, innerR: 92, outerR: 170, youR: 24,
                           nodeR: 21, big: true, scaleWithCount: true});
```

- [ ] **Step 4: Run tests**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q`
Expected: all PASS

- [ ] **Step 5: Commit**

```bash
git add hearth/web/app.js tests/test_web_assets.py
git commit -m "feat(circle): overlay ring radii scale with friend count (constant node spacing), world size published for the camera"
```

---

### Task 2: Screen-filling viewport, label CSS, Fit button, hint copy

**Files:**
- Modify: `hearth/web/style.css` (~lines 211-212, 219-229)
- Modify: `hearth/web/index.html` (~lines 192-198, the `#circle-overlay` block)
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Produces: `#circle-fit` button (Task 3 wires it), `svg.labels-off` CSS contract (Task 4 toggles the class).

- [ ] **Step 1: Write the failing test** — append to `tests/test_web_assets.py`:

```python
def test_circle_overlay_fills_screen_and_label_css():
    css = (WEB / "style.css").read_text(encoding="utf-8")
    html = (WEB / "index.html").read_text(encoding="utf-8")
    # the fixed 470px cap is gone; vmin-square viewport instead
    assert "min(76%, 470px)" not in css
    assert "94vmin" in css
    assert "touch-action: none" in _css_rule(css, ".bigmapwrap svg")
    # label visibility contract + reduced-motion opt-out
    assert ".labels-off" in css
    assert re.search(r"prefers-reduced-motion[^}]*\.nlabel", css, re.S)
    # Fit reset is a real, labeled <button>; hint teaches the gestures
    assert re.search(r'<button[^>]*id="circle-fit"', html)
    assert "pinch to zoom" in html and "drag to move" in html
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py::test_circle_overlay_fills_screen_and_label_css -q`
Expected: FAIL (`min(76%, 470px)` still present)

- [ ] **Step 3: Implement CSS.** In `hearth/web/style.css` replace:

```css
.bigmapwrap { text-align: center; }
.bigmapwrap svg { width: min(76%, 470px); height: auto; }
```

with:

```css
.bigmapwrap { text-align: center; width: min(94vmin, 94%); }
/* The svg is a square viewport (viewBox is square); touch-action:none so
   the camera's pointer gestures never fight page scroll while open. */
.bigmapwrap svg { width: 100%; height: auto; display: block; touch-action: none; }
/* Camera-driven label visibility (spec: labels hide when on-screen node
   pitch < 56px). Opacity so the fade can transition; pointer-events off so
   hidden labels never eat a node tap. */
.bigmapwrap svg .nlabel, .bigmapwrap svg .ringlabel {
  transition: opacity .18s; pointer-events: none; }
.bigmapwrap svg.labels-off .nlabel, .bigmapwrap svg.labels-off .ringlabel {
  opacity: 0; }
@media (prefers-reduced-motion: reduce) {
  .bigmapwrap svg .nlabel, .bigmapwrap svg .ringlabel { transition: none; }
}
.fitbtn { position: absolute; top: 16px; left: 18px; border: 1px solid var(--line-2);
  background: var(--surface); color: var(--ink-2); border-radius: 999px;
  padding: 6px 14px; font-size: 12.5px; font-weight: 600; cursor: pointer; }
.fitbtn:hover { color: var(--ink); border-color: var(--ink-2); }
/* Whole-branch review fix: narrow screens drop the gesture clause from the
   hint (directly after the existing .overlayhint rule). */
@media (max-width: 560px) { .overlayhint .hint-ext { display: none; } }
```

- [ ] **Step 4: Implement markup.** In `hearth/web/index.html` change the overlay block to:

```html
  <div class="overlay" id="circle-overlay" role="dialog" aria-label="Your kreds, expanded">
    <button class="closeoverlay" id="close-overlay" aria-label="Close">&times;</button>
    <button class="fitbtn" id="circle-fit" aria-label="Zoom to fit">Fit</button>
    <div class="bigmapwrap">
      <svg id="circle-overlay-svg" viewBox="0 0 440 440"></svg>
      <div class="overlayhint">Click a person to open their profile<span class="hint-ext"> &middot; scroll or pinch to zoom &middot; drag to move &middot; double-click to reset</span> &middot; Esc to close</div>
    </div>
  </div>
```

Whole-branch review fix: on narrow screens the hint's gesture clause is
noise (no wheel/pinch/drag context on a small screen), so it's wrapped in
`<span class="hint-ext">` and hidden below 560px (see the CSS block below).

- [ ] **Step 5: Run tests**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q`
Expected: all PASS

- [ ] **Step 6: Commit**

```bash
git add hearth/web/style.css hearth/web/index.html tests/test_web_assets.py
git commit -m "feat(circle): overlay claims 94vmin, label-visibility CSS contract, Fit button + gesture hint copy"
```

---

### Task 3: Camera core — fit, anchored wheel zoom, reset paths

**Files:**
- Modify: `hearth/web/app.js` (below `closeCircleOverlay`, ~line 844)
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: `svg.dataset.worldSize` (Task 1), `#circle-fit` (Task 2).
- Produces: `circleCamera` object — `init(svg)`, `fit()`, `apply()`, `clamp()`, `zoomAt(clientX, clientY, factor)`, `panBy(dxPx, dyPx)`, `ensureVisible(nodeG)`, fields `{svg, x, y, w, fitW}`. `updateCircleLabels()` stub called from `apply()` (Task 4 fills it). Task 4's gestures call `panBy`/`zoomAt`/`fit`.

- [ ] **Step 1: Write the failing test** — append to `tests/test_web_assets.py`:

```python
def test_circle_camera_core():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "const circleCamera" in js
    cam = js.split("const circleCamera")[1].split("\nfunction wireCircleGestures")[0] \
        if "wireCircleGestures" in js else js.split("const circleCamera")[1][:4000]
    # zoom clamp: never past fit, never tighter than fit/8 (8x magnification)
    assert "fitW / 8" in cam
    # pan clamp: at least 20% of the world stays on-screen per axis
    assert "0.2" in cam and "0.8" in cam
    # anchored zoom + fit entry points
    assert "zoomAt(" in js and ".fit()" in js
    # wheel wired non-passively (preventDefault must work), dblclick + Fit reset
    assert '"wheel"' in js and "passive: false" in js
    assert '"dblclick"' in js
    assert '"circle-fit"' in js
    # opening the overlay initializes the camera at fit
    overlay_fn = js.split("function openCircleOverlay")[1][:600]
    assert "circleCamera" in overlay_fn
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py::test_circle_camera_core -q`
Expected: FAIL (`const circleCamera` not found)

- [ ] **Step 3: Implement.** In `hearth/web/app.js`, after `closeCircleOverlay()` and before the existing `circle-overlay-svg` click listener, add:

```js
// ---------------------------------------------------------------------
// Circle camera (spec 2026-07-08-kreds-circle-zoom): zoom/pan by rewriting
// the overlay svg's viewBox - the drawn nodes never carry transforms, so
// click/keyboard/hover behavior is untouched. State is the viewBox origin
// (x, y) and width w (the viewport is square, height mirrors width).
// ---------------------------------------------------------------------
function updateCircleLabels() {}   // filled in by the gesture/labels pass

const circleCamera = {
  svg: null, x: 0, y: 0, w: 440, fitW: 440,
  init(svg) { this.svg = svg; },
  fit() {
    this.fitW = +this.svg.dataset.worldSize || 440;
    this.x = 0; this.y = 0; this.w = this.fitW;
    this.apply();
  },
  apply() {
    this.svg.setAttribute("viewBox",
      this.x + " " + this.y + " " + this.w + " " + this.w);
    updateCircleLabels();
  },
  clamp() {
    // zoom: between fit (whole world) and 8x magnification
    this.w = Math.min(this.fitW, Math.max(this.fitW / 8, this.w));
    // pan: keep >= 20% of the viewport showing world on each axis
    const lo = -0.8 * this.w, hi = this.fitW - 0.2 * this.w;
    this.x = Math.min(hi, Math.max(lo, this.x));
    this.y = Math.min(hi, Math.max(lo, this.y));
  },
  // Zoom by `factor` keeping the world point under (clientX, clientY) fixed.
  zoomAt(clientX, clientY, factor) {
    const r = this.svg.getBoundingClientRect();
    if (!r.width) return;
    const fx = (clientX - r.left) / r.width, fy = (clientY - r.top) / r.height;
    const wx = this.x + fx * this.w, wy = this.y + fy * this.w;
    this.w /= factor;
    this.clamp();
    this.x = wx - fx * this.w;
    this.y = wy - fy * this.w;
    this.clamp();
    this.apply();
  },
  panBy(dxPx, dyPx) {
    const r = this.svg.getBoundingClientRect();
    if (!r.width) return;
    this.x -= (dxPx / r.width) * this.w;
    this.y -= (dyPx / r.height) * this.w;
    this.clamp();
    this.apply();
  },
  // Pan (no zoom change) so a focused node sits inside a 10% margin.
  ensureVisible(nodeG) {
    const c = nodeG.querySelector("circle");
    if (!c) return;
    const nx = +c.getAttribute("cx"), ny = +c.getAttribute("cy");
    const pad = this.w * 0.1;
    if (nx < this.x + pad) this.x = nx - pad;
    if (nx > this.x + this.w - pad) this.x = nx - this.w + pad;
    if (ny < this.y + pad) this.y = ny - pad;
    if (ny > this.y + this.w - pad) this.y = ny - this.w + pad;
    this.clamp();
    this.apply();
  },
};

{
  const svg = document.getElementById("circle-overlay-svg");
  circleCamera.init(svg);
  svg.addEventListener("wheel", (ev) => {
    ev.preventDefault();
    circleCamera.zoomAt(ev.clientX, ev.clientY, ev.deltaY < 0 ? 1.15 : 1 / 1.15);
  }, {passive: false});
  svg.addEventListener("dblclick", () => circleCamera.fit());
  document.getElementById("circle-fit").onclick = () => circleCamera.fit();
  svg.addEventListener("focusin", (ev) => {
    const node = ev.target.closest("[data-open]");
    if (node) circleCamera.ensureVisible(node);
  });
}
```

And in `openCircleOverlay()`, after the `classList.add("open")` line, add:

```js
  circleCamera.fit();
```

- [ ] **Step 4: Run tests**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q`
Expected: all PASS

- [ ] **Step 5: Commit**

```bash
git add hearth/web/app.js tests/test_web_assets.py
git commit -m "feat(circle): viewBox camera - fit-on-open, anchored wheel zoom, dblclick/Fit reset, focus auto-pan"
```

---

### Task 4: Pointer gestures (pan, pinch, tap threshold) + label threshold

**Files:**
- Modify: `hearth/web/app.js` (the camera block from Task 3 and the existing svg click listener ~line 846)
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: `circleCamera.panBy/zoomAt/fit` (Task 3), `.labels-off` CSS (Task 2), `CIRCLE_SPACING` (Task 1).
- Produces: `wireCircleGestures(svg)`; real `updateCircleLabels()` (replaces Task 3's stub); module flag `CIRCLE_DRAGGED` consumed by the svg click handler.

- [ ] **Step 1: Write the failing test** — append to `tests/test_web_assets.py`:

```python
def test_circle_gestures_and_labels():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "function wireCircleGestures" in js
    g = js.split("function wireCircleGestures")[1].split("\nconst circleCamera")[0] \
        if js.index("function wireCircleGestures") < js.index("const circleCamera") \
        else js.split("function wireCircleGestures")[1][:5000]
    # pointer-events discipline (profile-drag lessons): cancel + capture-loss
    # both tear down; per-pointer bookkeeping for pinch; 6px tap threshold
    for token in ("pointerdown", "pointermove", "pointerup",
                  "pointercancel", "lostpointercapture",
                  "setPointerCapture", "Math.hypot"):
        assert token in g, token
    assert "> 6" in g
    # drag must not fire the node click that follows pointerup
    assert "CIRCLE_DRAGGED" in js
    click_handler = js.split('document.getElementById("circle-overlay-svg").addEventListener("click"')[1][:400]
    assert "CIRCLE_DRAGGED" in click_handler
    # double-tap reset for touch
    assert "300" in g
    # label threshold: on-screen node pitch vs 56px, toggling labels-off
    assert "labels-off" in js and "56" in js
    labels_fn = js.split("function updateCircleLabels")[-1][:600]
    assert "CIRCLE_SPACING" in labels_fn and "classList.toggle" in labels_fn
    # no native DnD anywhere in the gesture code
    assert "dragstart" not in g and 'draggable="true"' not in g
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py::test_circle_gestures_and_labels -q`
Expected: FAIL (`wireCircleGestures` not found)

- [ ] **Step 3: Implement.** In `hearth/web/app.js`:

(a) Replace the Task 3 stub `function updateCircleLabels() {}` with:

```js
// Labels show only when nodes are far enough apart on SCREEN to carry
// them (spec: node pitch >= 56 css px). Pitch comes from dataset.nodePitch,
// the ACTUAL minimum world arc between adjacent nodes published by
// buildCircle - not the SPACING floor (a small circle's real pitch is far
// above that floor, so its labels stay visible on small viewports too).
function updateCircleLabels() {
  const svg = circleCamera.svg;
  if (!svg) return;
  const r = svg.getBoundingClientRect();
  if (!r.width) return;
  const pitchWorld = +svg.dataset.nodePitch || CIRCLE_SPACING;
  const pitchPx = pitchWorld * (r.width / circleCamera.w);
  svg.classList.toggle("labels-off", pitchPx < 56);
}
```

(Whole-branch review fix: the pitch used to be the SPACING floor, which
undersells a small circle's real, wider spacing on small viewports; it now
reads the actual pitch buildCircle publishes.)

(b) Add above the `{ const svg = ... }` wiring block:

```js
// One-shot flag: a pan/pinch gesture ends in a browser-generated click on
// whatever is under the pointer - swallow exactly that one click so a drag
// that started on a person node doesn't ALSO open their profile.
let CIRCLE_DRAGGED = false;

function wireCircleGestures(svg) {
  const pts = new Map();       // pointerId -> {x, y, sx, sy} (current + start)
  let moved = false;           // past the 6px tap threshold this gesture?
  let multi = false;           // did this gesture ever have two pointers?
  let pinchDist = 0;
  let lastTap = 0;             // for double-tap reset (touch)

  // Capture is DEFERRED until the gesture is definitely a pan/pinch: with
  // capture taken at pointerdown, the browser retargets the eventual click
  // to the svg itself, so a plain tap's click never reaches the person node
  // (found by the Task 5 live smoke - taps were silently dead). Capturing
  // only after the 6px threshold (or on a second pointer) leaves clean taps
  // uncaptured -> their click flows to the node as before.
  const capture = () => {
    for (const id of pts.keys()) {
      try { svg.setPointerCapture(id); } catch (e) { /* pointer already gone */ }
    }
  };

  const gone = (ev) => {
    // pointerup AND lostpointercapture both fire for a captured pointer -
    // process each pointer's release exactly once, or the second pass
    // re-runs the size-0 branch and clobbers CIRCLE_DRAGGED back to false
    // before the browser's click event consumes it (task review, Critical).
    if (!pts.has(ev.pointerId)) return;
    pts.delete(ev.pointerId);
    // dropping to exactly 2 must also re-seed (a 3rd finger lifting mid-pinch
    // left a stale pair distance)
    if (pts.size <= 2) pinchDist = 0;
    if (pts.size === 0) { CIRCLE_DRAGGED = moved; moved = false; multi = false; }
  };

  svg.addEventListener("pointerdown", (ev) => {
    // A pinch generates no click, so a stale CIRCLE_DRAGGED=true from the
    // previous gesture would swallow this gesture's tap - clear it at the
    // start of every fresh gesture (task review, Important).
    if (pts.size === 0) CIRCLE_DRAGGED = false;
    pts.set(ev.pointerId, {x: ev.clientX, y: ev.clientY,
                           sx: ev.clientX, sy: ev.clientY});
    if (pts.size === 2) {
      multi = true;
      capture();               // a second pointer = definitely a pinch, never a tap
      const [a, b] = [...pts.values()];
      pinchDist = Math.hypot(a.x - b.x, a.y - b.y);
    }
  });

  svg.addEventListener("pointermove", (ev) => {
    const p = pts.get(ev.pointerId);
    if (!p) return;
    // A swallowed right-button pointerup (context-menu flows on some
    // platforms) can leave a stale entry - a mouse moving with no buttons
    // held is not a gesture, tear it down.
    if (ev.pointerType === "mouse" && !ev.buttons) { gone(ev); return; }
    const prevX = p.x, prevY = p.y;
    p.x = ev.clientX; p.y = ev.clientY;
    if (!moved && Math.hypot(p.x - p.sx, p.y - p.sy) > 6) {
      moved = true;
      capture();               // definitely a pan now, never a tap
    }
    if (!moved) return;
    if (pts.size === 1) {
      circleCamera.panBy(p.x - prevX, p.y - prevY);
    } else if (pts.size === 2) {
      const [a, b] = [...pts.values()];
      const dist = Math.hypot(a.x - b.x, a.y - b.y);
      if (pinchDist > 0 && dist > 0) {
        circleCamera.zoomAt((a.x + b.x) / 2, (a.y + b.y) / 2, dist / pinchDist);
      }
      pinchDist = dist;
    }
  });

  svg.addEventListener("pointerup", (ev) => {
    // double-tap (touch) resets to fit - two clean taps within 300ms
    if (!moved && !multi && ev.pointerType === "touch" && pts.size === 1) {
      const now = performance.now();
      if (now - lastTap < 300) { circleCamera.fit(); lastTap = 0; }
      else lastTap = now;
    }
    gone(ev);
  });
  svg.addEventListener("pointercancel", gone);
  svg.addEventListener("lostpointercapture", gone);
  // Sub-threshold releases can land off-svg without capture (a down at the
  // very edge that exits before crossing 6px) - window-level teardown covers
  // them; gone()'s pts.has guard makes stray pointerups elsewhere a no-op.
  window.addEventListener("pointerup", gone);
  window.addEventListener("pointercancel", gone);
}
```

(Whole-branch review fixes backported into the block above: `gone()` now
re-seeds `pinchDist` on dropping to exactly 2 pointers, not just below 2 -
a 3rd finger lifting mid-pinch used to leave a stale pair distance; and
`pointermove` tears down via `gone()` when a mouse pointer moves with no
buttons held, covering a swallowed right-button pointerup on platforms
where a context-menu flow eats it.)

(c) Inside the existing `{ const svg = ... }` wiring block, add one line after `circleCamera.init(svg);`:

```js
  wireCircleGestures(svg);
```

(d) Change the existing overlay-svg click handler from:

```js
document.getElementById("circle-overlay-svg").addEventListener("click", (ev) => {
  const node = ev.target.closest("[data-open]");
  if (node) openProfile(node.getAttribute("data-open"));
});
```

to:

```js
document.getElementById("circle-overlay-svg").addEventListener("click", (ev) => {
  if (CIRCLE_DRAGGED) { CIRCLE_DRAGGED = false; return; }   // that "click" was a drag ending
  const node = ev.target.closest("[data-open]");
  if (node) openProfile(node.getAttribute("data-open"));
});
```

- [ ] **Step 4: Run the full suite**

Run: `.venv\Scripts\python.exe -m pytest -q`
Expected: all PASS (672+)

- [ ] **Step 5: Commit**

```bash
git add hearth/web/app.js tests/test_web_assets.py
git commit -m "feat(circle): pointer pan/pinch with 6px tap threshold and drag-click suppression, double-tap reset, label pitch threshold"
```

---

### Task 5: Live Playwright smoke + docs

**Files:**
- Create: (scratchpad) `circle_smoke.py` — run, not committed
- Modify: `docs/engineering-notes.md` (circle/overlay description)

**Interfaces:**
- Consumes: everything above, `python -m hearth demo` (fresh `run/`), `POST /api/onboarding-done` to node 7201 (clears the wizard — established pattern from the 0.3.4 icon investigation).

- [ ] **Step 1: Write the smoke script** (scratchpad; adapt paths):

```python
import json, time, urllib.request
from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:7201"
for _ in range(60):
    try:
        d = json.load(urllib.request.urlopen(BASE + "/api/bootstrap", timeout=2))
        if d.get("initialized"): break
    except Exception: pass
    time.sleep(2)
urllib.request.urlopen(urllib.request.Request(BASE + "/api/onboarding-done", method="POST"), timeout=5)

FAKE_KREDS = json.dumps([
    {"identity_pub": f"{i:064x}", "name": f"Friend {i}",
     "ring": "inner" if i % 5 == 0 else "kreds", "since": 1750000000}
    for i in range(400)])

with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": 1280, "height": 800})
    pg.goto(BASE, wait_until="networkidle")
    checks = []
    def ck(name, ok): checks.append((name, ok)); print(("PASS " if ok else "FAIL ") + name)

    # -- real data (2-friend-or-fewer) path: overlay opens at fit, big
    pg.evaluate("openCircleOverlay()")
    box = pg.evaluate("document.querySelector('#circle-overlay-svg').getBoundingClientRect().width")
    ck("svg claims >= 90% of vmin", box >= 0.9 * 800 * 0.94)
    vb0 = pg.evaluate("document.getElementById('circle-overlay-svg').getAttribute('viewBox')")

    # wheel zoom anchored at a corner: viewBox narrows, origin moves toward corner
    pg.mouse.move(300, 300)
    pg.mouse.wheel(0, -400)
    vb1 = pg.evaluate("document.getElementById('circle-overlay-svg').getAttribute('viewBox')")
    ck("wheel zoom narrows viewBox", float(vb1.split()[2]) < float(vb0.split()[2]))

    # drag pans
    pg.mouse.move(640, 400); pg.mouse.down(); pg.mouse.move(740, 460, steps=5); pg.mouse.up()
    vb2 = pg.evaluate("document.getElementById('circle-overlay-svg').getAttribute('viewBox')")
    ck("drag pans viewBox origin", vb2.split()[:2] != vb1.split()[:2])

    # double-click resets to fit
    pg.mouse.dblclick(640, 400)
    vb3 = pg.evaluate("document.getElementById('circle-overlay-svg').getAttribute('viewBox')")
    ck("dblclick resets to fit", vb3 == vb0)

    # Fit button resets after another zoom
    pg.mouse.wheel(0, -400); pg.click("#circle-fit")
    ck("Fit button resets", pg.evaluate("document.getElementById('circle-overlay-svg').getAttribute('viewBox')") == vb0)

    # node click still opens a profile (sub-threshold tap) - only if a friend exists
    has_node = pg.evaluate("!!document.querySelector('#circle-overlay-svg [data-open]')")
    if has_node:
        pg.click("#circle-overlay-svg [data-open]")
        ck("node click opens profile", pg.evaluate("!document.getElementById('view-profile').classList.contains('hidden')"))
        pg.evaluate("openCircleOverlay()")

    # -- synthetic 400-friend world: labels off at fit, on when zoomed
    pg.evaluate(f"KREDS.length = 0; KREDS.push(...{FAKE_KREDS}); openCircleOverlay()")
    ck("labels off at 400-friend fit",
       pg.evaluate("document.getElementById('circle-overlay-svg').classList.contains('labels-off')"))
    world = pg.evaluate("+document.getElementById('circle-overlay-svg').dataset.worldSize")
    ck("world grew for 400 friends", world > 2000)
    for _ in range(14): pg.mouse.move(640, 400); pg.mouse.wheel(0, -400)
    ck("labels on when zoomed in",
       not pg.evaluate("document.getElementById('circle-overlay-svg').classList.contains('labels-off')"))

    # CDP pinch zoom (touch)
    cdp = pg.context.new_cdp_session(pg)
    pg.click("#circle-fit")
    vbf = pg.evaluate("document.getElementById('circle-overlay-svg').getAttribute('viewBox')")
    def touch(tp, pts): cdp.send("Input.dispatchTouchEvent", {"type": tp, "touchPoints": pts})
    touch("touchStart", [{"x": 600, "y": 400, "id": 1}, {"x": 680, "y": 400, "id": 2}])
    for i in range(1, 6):
        touch("touchMove", [{"x": 600 - i * 20, "y": 400, "id": 1}, {"x": 680 + i * 20, "y": 400, "id": 2}])
    touch("touchEnd", [])
    vbp = pg.evaluate("document.getElementById('circle-overlay-svg').getAttribute('viewBox')")
    ck("CDP pinch zooms", float(vbp.split()[2]) < float(vbf.split()[2]))

    # Esc closes cleanly
    pg.keyboard.press("Escape")
    ck("Esc closes overlay", pg.evaluate("!document.getElementById('circle-overlay').classList.contains('open')"))

    b.close()
    failed = [n for n, ok in checks if not ok]
    print(f"\n{len(checks) - len(failed)}/{len(checks)} checks passed")
    raise SystemExit(1 if failed else 0)
```

- [ ] **Step 2: Run it** — `python -m hearth demo` in the background (delete `run/` first for a fresh cast), then run the script.
Expected: all checks PASS. Investigate any FAIL via systematic-debugging before touching code.

- [ ] **Step 3: Stop the demo, delete `run/`.**

- [ ] **Step 4: Update `docs/engineering-notes.md`.** In "The app" section, after the sentence describing the circle rail expanding into a radial map, add:

```markdown
The expanded circle fills the screen and is a camera, not a fixed
picture: ring radii grow with friend count (constant node spacing -
the circle gets bigger, never denser), the wheel or a two-finger pinch
zooms anchored under the pointer, dragging pans (same Pointer Events
plumbing as the profile canvas: pointercancel + lostpointercapture
teardown, 6px tap-vs-drag threshold so node clicks stay clicks), and
double-click/double-tap or the Fit button resets to the full view.
Name labels hide when nodes are tighter than ~56px on screen and fade
back in as you zoom (instant, no fade, under prefers-reduced-motion).
The compact rail minimap is unchanged.
```

- [ ] **Step 5: Full suite one more time, then commit**

Run: `.venv\Scripts\python.exe -m pytest -q`
Expected: all PASS

```bash
git add docs/engineering-notes.md
git commit -m "docs: circle overlay zoom/pan behavior in engineering notes"
git push origin main
```

---

## Self-review (done at write time)

- **Spec coverage:** fill-screen (T2), radius scaling (T1), wheel/pinch/drag (T3/T4), fit-on-open + double-click/tap + Fit button (T3/T4), label threshold + reduced-motion (T2/T4), focus auto-pan (T3), rail untouched (T1 test), edge cases exercised in T5 smoke. Camera reads `dataset.worldSize` — no ring-count assumptions (future multi-circle).
- **Placeholders:** none; every step carries code or an exact command.
- **Type consistency:** `circleCamera.{init,fit,apply,clamp,zoomAt,panBy,ensureVisible}` used identically in T3 wiring, T4 gestures, T5 smoke; `updateCircleLabels` stub (T3) replaced in T4(a); `CIRCLE_DRAGGED` produced in T4(b), consumed in T4(d).
