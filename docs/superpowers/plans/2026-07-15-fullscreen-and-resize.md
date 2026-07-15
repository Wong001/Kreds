# Fullscreen Fill + Window Resize Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Messages fills the viewport height, the app shell widens to a 1720px cap (inner columns stay centered), and the frameless window regains drag-resizing via a bottom-right chrome grip driving `Api.resize_to`.

**Architecture:** Items 1-2 are CSS on `.dm-shell` and `.app` with live-measured offsets. Item 3 is a new `Api.resize_to(w, h)` bridge method (clamp → maximized-ignore → `window.resize`, teardown-safe) plus a desktop-only chrome grip whose pointer-drag throttles one bridge call per animation frame.

**Tech Stack:** CSS, vanilla JS pointer events, pywebview `Window.resize` (verified thread-safe: winforms marshals via Invoke), pytest + Playwright live geometry.

**Spec:** `docs/superpowers/specs/2026-07-15-fullscreen-and-resize-design.md` (approved).

## Global Constraints

- Branch `kreds-fixes-0.3.13` (already exists — this batch rides it; base for this plan's diff is the branch HEAD `8169956`).
- Suite green before every commit: `.venv\Scripts\python.exe -m pytest -q` (baseline at dispatch: 901 passed, 6 skipped).
- NO AI/Co-Authored-By commit trailers; ASCII-only console prints.
- Exact values: `.app` cap `1720px`; `.dm-shell` minimum `420px`; grip hit area `16px`; resize floor = the existing `min_size` `(900, 600)`.
- The grip renders ONLY when the pywebview bridge exists — a plain browser must never show it.
- Live rendered-geometry verification (0.3.4 lesson) is REQUIRED for the CSS task; the real-shell drag is August's manual smoke (record that explicitly as NOT verified here).
- No version bump (branch is already 0.3.13).

## Verified codebase facts

- `.dm-shell` at style.css:755-756 (`height: min(640px, 70vh)`); mobile stack at style.css:790 (`@media (max-width: 720px)`).
- `.app` at style.css:132 (`max-width: 1220px; margin: 22px auto; min-height: calc(100vh - 44px)`).
- `Api` class in hearth/desktop.py: `self.window` set by launch(); `self._maximized` tracked by `toggle_maximize` (desktop.py:155-161); existing window calls wrap in try/except for mid-teardown (`show_window` pattern, desktop.py:107-122).
- pywebview 6.2.1 `Window.resize(width, height, fix_point=...)` is `@_shown_call` and the winforms backend marshals via `self.Invoke` — callable from the JS bridge thread, same safety class as the `load_url` we already rely on.
- WinForms `MinimumSize` is set from `min_size=(900, 600)` at window creation (platforms/winforms.py:210-212) — the native backstop clamp.
- `buildEntry`-era test conventions: content pins via `_js_fn_body`/`_css_rule` in tests/test_web_assets.py; Api unit tests with fake windows in tests/test_desktop.py (`_FakeWindow` idiom) and tests/test_desktop_status.py.
- app.js exposes the bridge presence as `window.pywebview` checks (see `pollForFullApp`'s optional chaining); index.html carries the chrome (titlebar at index.html:20).

---

### Task 1: CSS — chat fills the viewport, shell widens to 1720

**Files:**
- Modify: `hearth/web/style.css` (`.dm-shell` 755-756, `.app` 132)
- Test: `tests/test_web_assets.py` (append)

**Interfaces:** none produced; pure CSS.

- [ ] **Step 1: Write the failing content pins**

```python
def test_chat_fills_viewport_and_shell_widens():
    # Fullscreen fill (0.3.13, August): the chat shell derives its height
    # from the viewport instead of the old min(640px, 70vh) cap, and the
    # app box caps at 1720px (fixed cap, NOT 100vw - ultrawides would
    # stretch the nav away from content).
    css = (WEB / "style.css").read_text(encoding="utf-8")
    shell = _css_rule(css, ".dm-shell")
    assert "min(640px" not in shell and "70vh" not in shell
    assert "100vh" in shell and "max(420px" in shell
    assert "max-width: 1720px" in _css_rule(css, ".app")
```

- [ ] **Step 2: Run to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py::test_chat_fills_viewport_and_shell_widens -q`
Expected: FAIL (old cap present, 1220px cap).

- [ ] **Step 3: Implement — with the offset MEASURED, not guessed**

First measure: `.venv\Scripts\python.exe -m hearth demo` (fresh `run/`; POST `/api/onboarding-done` to node 7201 if the wizard blocks), Playwright against `127.0.0.1:7201`, open Messages, evaluate `document.querySelector(".dm-shell").getBoundingClientRect().top` plus the app's bottom margin/padding clearance below the shell. The height formula is `max(420px, calc(100vh - <top + bottom clearance>px))` with the measured integer substituted and a comment naming what it sums (nav + .app margin + view padding + bottom clearance = NNNpx, measured 2026-07-15). Then:

- `.dm-shell` line becomes (preserving everything else in the rule):
  `height: max(420px, calc(100vh - NNNpx));`
- `.app` line: `max-width: 1720px`.

- [ ] **Step 4: Pins green, live geometry verification, full suite**

Run the pin, then verify RENDERED geometry in the same Playwright session at two viewport sizes (1280x800 and 2400x1300): the `.dm-shell` bottom edge fills to just above the `.idstrip` footer, which must stay fully inside the viewport (AMENDED after the first run: the offset folds in the idstrip's height — it is the always-visible identity fingerprint strip, and the original 106px offset pushed it below the fold); the composer row (`.thread-col` bottom child) is inside the viewport; at 2400 wide the `.app` box is 1720px wide and centered; the journal view keeps its pre-widen readable width (AMENDED after the first run caught it inheriting the shell cap and stretching 952->1452px: the journal container gains its own max-width restoring ~952px entries, with the chipbar/dayheads centering alongside). Record numbers + screenshots in the report. Then full suite.

- [ ] **Step 5: Commit**

```bash
git add hearth/web/style.css tests/test_web_assets.py
git commit -m "fix(ui): chat fills the viewport (min 420px) and the app shell caps at 1720px - inner readable columns stay centered (August)"
```

---

### Task 2: Corner resize grip — `Api.resize_to` + chrome

**Files:**
- Modify: `hearth/desktop.py` (`Api`, next to `toggle_maximize`), `hearth/web/index.html` (grip element after the titlebar block), `hearth/web/app.js` (grip wiring near the other `window.pywebview` chrome hooks), `hearth/web/style.css` (grip styling)
- Test: `tests/test_desktop_status.py` (append Api tests), `tests/test_web_assets.py` (append pins)

**Interfaces:**
- Produces: `Api.resize_to(w, h)` — floors at (900, 600), no-op while `self._maximized` or `self.window is None`, `self.window.resize(int(w), int(h))` in try/except.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_desktop_status.py`:

```python
class _ResizableFakeWindow:
    def __init__(self):
        self.sizes = []

    def resize(self, w, h):
        self.sizes.append((w, h))


def test_api_resize_to_drives_window():
    api = desktop.Api({})
    api.window = _ResizableFakeWindow()
    api.resize_to(1300, 900)
    assert api.window.sizes == [(1300, 900)]


def test_api_resize_to_clamps_to_min_size():
    api = desktop.Api({})
    api.window = _ResizableFakeWindow()
    api.resize_to(200, 5000)
    assert api.window.sizes == [(900, 5000)]
    api.resize_to(5000, 100)
    assert api.window.sizes[-1] == (5000, 600)


def test_api_resize_to_ignored_while_maximized_or_windowless():
    api = desktop.Api({})
    api.resize_to(1300, 900)                 # no window: no crash
    api.window = _ResizableFakeWindow()
    api._maximized = True
    api.resize_to(1300, 900)                 # maximized: native no-op
    assert api.window.sizes == []


def test_api_resize_to_survives_destroyed_window():
    class _DeadWindow:
        def resize(self, w, h):
            raise RuntimeError("window destroyed")
    api = desktop.Api({})
    api.window = _DeadWindow()
    api.resize_to(1300, 900)                 # must not raise
```

Append to `tests/test_web_assets.py`:

```python
def test_resize_grip_desktop_only_and_wired():
    # Frameless regression rebuilt (Josh, 0.3.13): a chrome corner grip
    # drives Api.resize_to; a plain browser never shows it (the OS frame
    # already resizes there).
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert 'id="win-resize"' in html
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "resize_to" in js
    body = _js_fn_body(js, "initResizeGrip")
    assert "requestAnimationFrame" in body        # bridge-flood throttle
    assert "setPointerCapture" in body
    css = (WEB / "style.css").read_text(encoding="utf-8")
    rule = _css_rule(css, "#win-resize")
    assert "nwse-resize" in rule
    assert "display: none" in rule                # hidden until JS reveals
```

- [ ] **Step 2: Run to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_desktop_status.py tests/test_web_assets.py -q`
Expected: new tests FAIL (`no attribute 'resize_to'`, missing markup/rules).

- [ ] **Step 3: Implement**

`hearth/desktop.py`, in `Api` next to `toggle_maximize` (constants mirror the create_window `min_size=(900, 600)` — name them):

```python
    # create_window's min_size; WinForms MinimumSize is the native
    # backstop, this clamp just keeps the JS grip from flooding the
    # bridge with sub-minimum calls.
    MIN_W, MIN_H = 900, 600

    def resize_to(self, w, h):
        """Chrome corner grip (frameless windows have no native resize
        borders - resizable=True is inert without an OS frame, the
        regression Josh reported). window.resize marshals onto the GUI
        thread (pywebview winforms Invoke), same safety class as
        load_url."""
        if not self.window or self._maximized:
            return                      # maximized: native corner no-op
        try:
            self.window.resize(max(int(w), self.MIN_W),
                               max(int(h), self.MIN_H))
        except Exception:
            pass                        # mid-teardown; nothing to resize
```

`hearth/web/index.html` — after the titlebar block (index.html:~20-30), inside the body chrome:

```html
  <div id="win-resize" aria-hidden="true" title="Resize"></div>
```

`hearth/web/style.css`:

```css
/* Corner resize grip (0.3.13): frameless window = no native resize
   borders, so the chrome rebuilds corner drag itself. Hidden by default;
   app.js reveals it only when the pywebview bridge exists (a plain
   browser's OS frame already resizes). */
#win-resize { display: none; position: fixed; right: 0; bottom: 0;
  width: 16px; height: 16px; cursor: nwse-resize; z-index: 60;
  background: linear-gradient(135deg, transparent 0 50%,
    var(--line-2) 50% 60%, transparent 60% 70%,
    var(--line-2) 70% 80%, transparent 80%); }
#win-resize.on { display: block; }
```

`hearth/web/app.js` — a top-level `initResizeGrip()` called from the same startup path that wires the other chrome buttons (find where `is_desktop`/titlebar handlers are bound and call it there):

```javascript
// Corner resize grip: rebuilds the drag-resize a frameless window loses
// (no OS borders). Desktop only - the grip stays hidden in a browser.
// One bridge call per animation frame: unthrottled pointermoves flood
// the pywebview Invoke marshal.
function initResizeGrip() {
  const grip = document.getElementById("win-resize");
  if (!grip || !window.pywebview || !window.pywebview.api) return;
  grip.classList.add("on");
  let startX = 0, startY = 0, startW = 0, startH = 0, raf = 0;
  let nextW = 0, nextH = 0;
  const push = () => {
    raf = 0;
    window.pywebview.api.resize_to(nextW, nextH);
  };
  grip.addEventListener("pointerdown", (e) => {
    startX = e.screenX; startY = e.screenY;
    startW = window.innerWidth; startH = window.innerHeight;
    grip.setPointerCapture(e.pointerId);
    e.preventDefault();
  });
  grip.addEventListener("pointermove", (e) => {
    if (!grip.hasPointerCapture || !grip.hasPointerCapture(e.pointerId)) return;
    nextW = startW + (e.screenX - startX);
    nextH = startH + (e.screenY - startY);
    if (!raf) raf = requestAnimationFrame(push);
  });
  const drop = (e) => {
    if (grip.hasPointerCapture && grip.hasPointerCapture(e.pointerId))
      grip.releasePointerCapture(e.pointerId);
    if (raf) { cancelAnimationFrame(raf); raf = 0; }
  };
  grip.addEventListener("pointerup", drop);
  grip.addEventListener("pointercancel", drop);
}
```

Call `initResizeGrip()` where the chrome initializes. If the bridge injects after DOM ready (it can - see pollForFullApp's retry note), guard with the same retry idiom used there or bind on the `pywebviewready` event if the codebase already listens for it — follow whatever the existing chrome code does for `is_desktop` detection.

- [ ] **Step 4: Tests green, live browser check, full suite**

Run both test files, then the full suite. Live check (Playwright, browser mode): the grip element exists but is NOT visible (`display: none` — no bridge in a browser); no console errors on load. Real-shell drag verification is AUGUST'S manual smoke — state that explicitly in the report as not verified here, with the DPI observation item from the spec called out for him (drag should track the cursor 1:1; if it lags proportionally on a scaled display, the deltas need /devicePixelRatio — report it rather than guess).

- [ ] **Step 5: Commit**

```bash
git add hearth/desktop.py hearth/web/index.html hearth/web/app.js hearth/web/style.css tests/test_desktop_status.py tests/test_web_assets.py
git commit -m "feat(shell): corner drag-resize rebuilt for the frameless window - chrome grip drives Api.resize_to (rAF-throttled, min 900x600, maximized no-op); desktop-only, hidden in browsers (Josh)"
```

---

## Self-Review Notes (planning time)

- Spec coverage: item 1 → T1 (measured offset, 420 floor, mobile query untouched); item 2 → T1 (1720 cap, inner caps untouched + verified live); item 3 → T2 (Api clamp/maximized/teardown semantics all pinned by unit tests; grip desktop-only pinned; rAF throttle pinned; DPI check delegated to August's smoke explicitly).
- Type consistency: `resize_to(w, h)` name identical across Api, JS, and pins; `MIN_W/MIN_H` mirror create_window's min_size.
- The one deliberately-unverifiable-here behavior (real drag on the real shell) is named as such in both the spec and T2 Step 4 — honest gap, August's checklist item, not silent.
