# Fullscreen fill + window resize — design

Date: 2026-07-15
Status: approved (August, 2026-07-15, in session; item 2 = fixed ~1720px
cap over edge-to-edge; item 3 confirmed "rebuild the resize we lost going
frameless")
Slice: rides branch kreds-fixes-0.3.13, ships with the profile polish
batch in 0.3.13

## Problems (August + Josh, live on 0.3.12)

1. **Messages dead space in fullscreen:** `.dm-shell { height: min(640px,
   70vh) }` (style.css:756) hard-caps the chat; on a tall window most of
   the view below is empty. The thread + conversation columns should run
   to the bottom, composer at the foot.
2. **Narrow app box on wide screens:** `.app { max-width: 1220px }`
   (style.css:132) leaves large dead margins. Wanted: the shell fills
   more of the screen, inner readable columns stay centered at their own
   widths.
3. **No drag-resize (Josh):** the window is frameless (own chrome); a
   frameless WinForms window has NO native resize borders, so
   `resizable=True` is inert — the app is effectively locked to its
   initial 1100x760 or maximized. This was an unnoticed regression when
   the shell went frameless. pywebview's `Window.resize(w, h)` is
   thread-safe (marshals via Invoke, same class as `load_url`), so the
   chrome can rebuild resizing itself.

## Decision (August, 2026-07-15)

1. Chat fills the viewport height, with a sane minimum for small windows.
2. `.app` cap rises to **1720px** (fixed cap, NOT `100vw - margin` —
   ultrawide monitors would stretch the nav absurdly far from content).
   Inner view caps (journal column, `.profile-layout` 720/1040,
   `#view-settings` 640) are deliberately untouched; Messages is the view
   that consumes the new width.
3. A **bottom-right corner drag grip** in the app chrome drives
   `Api.resize_to(w, h)` → `window.resize`. Corner only for now; edges
   later if the corner feels good.

## Design

### 1. Chat height (CSS only)

`.dm-shell` height becomes viewport-derived:
`height: max(420px, calc(100vh - VAR))` where VAR is the measured
vertical offset (nav + `.app` margins + view padding + composer clearance)
— MEASURED live in a rendered browser (0.3.4 lesson), not guessed; the
implementer records the number and the measurement in the plan/test
report. The 720px-width mobile media query keeps its current stacked
behavior; `70vh`/`640px` caps die.

### 2. App width (CSS only)

`.app { max-width: 1220px }` -> `1720px`. Nothing else moves — inner
views keep their own centering caps, so long-line readability is
preserved. Verify Messages actually consumes the width (the `.dm-shell`
grid's `minmax(0,1fr)` column should absorb it).

### 3. Corner resize grip (chrome JS + Api bridge)

- `Api.resize_to(self, w, h)`: clamps to the existing minimum (900x600,
  the `min_size` passed at create_window), ignores the call while
  `self._maximized` (native behavior: a maximized window doesn't corner-
  resize), then `self.window.resize(int(w), int(h))` wrapped in
  try/except like the other window calls (mid-teardown safe).
- Chrome: a fixed-position grip element in the bottom-right corner
  (`cursor: nwse-resize`, subtle diagonal-lines affordance, ~16px hit
  area), rendered ONLY when the pywebview bridge exists (desktop app;
  hidden in a plain browser where the OS window manager already handles
  resize).
- Drag: pointerdown captures start `screenX/Y` + start size
  (`window.innerWidth/innerHeight` — frameless, so client area == window);
  pointermove computes `start + delta`, throttled to one
  `api.resize_to` per animation frame (unthrottled pointermoves would
  flood the Invoke bridge); pointerup/pointercancel release capture.
  DPI note: JS `screenX` and pywebview's logical-pixel resize both live
  in CSS pixel space on WebView2 — the implementer VERIFIES this on the
  real shell (drag tracks the cursor ~1:1) and records the observation;
  if scaling skews it, divide deltas by `window.devicePixelRatio` and
  record which form shipped.
- WinForms `MinimumSize` (set from `min_size`) is the backstop clamp;
  the JS clamp just avoids flooding the bridge with sub-minimum calls.

### Mixed-version / testability

All web + shell (`desktop.py` Api); no protocol or core-API surface, no
update gating. The grip's drag behavior on the REAL shell is August's
manual smoke (Playwright drives a browser, where the grip is hidden by
design); `Api.resize_to` gets unit tests (clamp, maximized-ignore,
teardown-safe), and the grip's markup/JS get content pins + a
browser-mode "hidden without bridge" live check.

## Out of scope

- Edge grips / all-8-direction resize (corner first, per August).
- Window position persistence across launches (separate wish if wanted).
- Any inner-view width changes beyond the shell cap.
