# Kreds circle overlay: fill the screen, zoom, and pan — design

Date: 2026-07-08
Status: approved (August, in-session)

## Problem

The expanded circle overlay (`#circle-overlay`) renders the radial map at a
fixed 440-unit viewBox capped at `min(76%, 470px)` CSS width. On a desktop
screen the map, its avatar nodes, and its text are tiny. And the layout
cannot scale: both rings are single circles with node positions spread
evenly by angle, so at high friend counts (hundreds) nodes overlap into an
unreadable band — at 400 friends the outer ring offers ~2.7 units of arc
per 42-unit-wide node.

## Goals

- The overlay map fills the available screen instead of a 470px box.
- Node and text sizes stay readable regardless of how many friends exist;
  the circle gets *bigger*, never *denser*.
- Zoom (mouse wheel, pinch) and pan (pointer drag — mouse, touch, pen)
  to navigate a circle too large to read all at once.
- Opens at fit-to-screen: the whole circle is always the first view.
- Same treatment on desktop and mobile (pinch-to-zoom, drag-to-pan).

## Non-goals

- No layout redesign beyond radius scaling: still exactly two rings
  (inner kreds / kreds). Community circles and multiple friend-group
  circles are a separate, later design (August, 2026-07-08) — this pass
  only avoids hard-coding assumptions that would block it.
- No inertia/momentum physics, no minimap-within-the-map.
- The compact rail minimap (`renderCircleRail`) is unchanged.
- The home-feed and profile views are untouched.

## Design

Approach: **viewBox-as-camera**. `buildCircle()` keeps emitting plain SVG
geometry in world coordinates; zoom/pan only rewrite the `<svg>`'s
`viewBox`. No transforms on drawn nodes, so hover/focus styling, click
handling, and keyboard behavior continue to work unmodified.

### World geometry (buildCircle)

- `buildCircle` overlay call keeps its current *node* sizes (youR 24,
  nodeR 21, current label/text sizes) — these are world units and no
  longer need to fit a fixed 440-box.
- Ring radii scale with occupancy instead of being fixed:
  `ringR(count) = max(baseR, count * SPACING / (2 * PI))` where `SPACING`
  keeps adjacent nodes comfortably separated (node diameter 42 + gap;
  SPACING = 64 world units). `baseR` = today's radii (inner 92, outer 170)
  so small circles look exactly like today, only bigger on screen.
- If the scaled inner radius approaches the outer, the outer grows to
  keep a minimum ring gap (`outerR >= innerR + 78`).
- World size (`size` passed to the viewBox math) derives from the outer
  radius: `worldSize = 2 * (outerR + MARGIN)` with MARGIN covering the
  ring label and node labels (60 world units).
- The function stays the single geometry source shared with the rail; the
  rail's call keeps passing its current fixed opts (its 200-box behavior
  is unchanged — radius scaling applies only when the caller passes
  `scaleWithCount: true`, which only the overlay does).

### Camera

A small camera module in `app.js` (`circleCamera`), state
`{x, y, w}` (viewBox origin + width; height mirrors width — the viewport
is square, see CSS):

- **Fit (home):** `{0, 0, worldSize}`. Applied on open and on
  double-click / double-tap (two taps within 300ms).
- **Zoom:** wheel (`wheel` event, `preventDefault`) and pinch (two active
  pointers; scale = distance ratio). Zoom is anchored: the world point
  under the cursor / pinch midpoint stays fixed. Clamped to
  `[fit, fit/8]` viewBox width (8x max magnification).
- **Pan:** single-pointer drag on the SVG background (not on a node —
  a drag starting on a node still pans if it moves past the same ~6px
  tap-vs-drag threshold the profile canvas uses, so node clicks stay
  clicks). Clamped so at least 20% of the world stays on-screen in each
  axis (cannot fling the circle away).
- Pointer Events only (`pointerdown/move/up/cancel` +
  `lostpointercapture`, the lesson from the profile drag work), with
  `touch-action: none` on the overlay SVG while it is open.
- No animation frames needed for correctness; viewBox writes happen on
  the event. (An rAF batch is a possible later polish, not in scope.)

### Labels and readability

- Name labels (`.nlabel`) and ring labels (`.ringlabel`) get visibility
  driven by effective magnification `m = fitW / w`:
  below `m = LABEL_AT` (chosen so labels don't collide at the fit view of
  a large circle; computed as: show labels when on-screen node pitch
  >= 56 CSS px) the SVG root carries class `labels-off`; CSS fades
  labels via opacity transition (respecting `prefers-reduced-motion`:
  no transition, just show/hide).
- With few friends (today's realistic case) the fit view already passes
  the threshold, so labels are visible immediately — no behavior loss.
- Freshness dots and node initials are always visible.

### CSS

- `.bigmapwrap` / `.bigmapwrap svg`: the cap `width: min(76%, 470px)`
  becomes a square viewport `width/height: min(94vmin, 94%)` centered in
  the overlay, so the circle never clips on either axis and claims the
  screen on any aspect ratio.
- The hint line ("Click a person to open their profile · Esc to close")
  gains "· scroll or pinch to zoom · drag to move · double-click to
  reset" (shortened on narrow screens via existing media query pattern).
- `.themebtn`-style close button (`#circle-close`, existing) unchanged.

### Accessibility

- Nodes stay `tabIndex=0` + `role=button` + Enter/Space activation
  (unchanged code path). On focus of a node that is off-camera, the
  camera pans (no zoom change) to bring it on-screen — keyboard users
  can reach every friend without the pointer.
- Double-tap reset has a visible equivalent: a small "Fit" button in the
  overlay corner (real `<button>`, Tab-reachable), since a
  keyboard-only user cannot double-click a specific SVG point
  meaningfully.
- `prefers-reduced-motion`: label fade becomes instant; there is no
  other animation.

### Future-proofing (multi-circle, explicitly deferred)

The camera operates on whatever world `buildCircle` drew and reads
`worldSize` from a data attribute the builder sets — it has no knowledge
of ring count or meaning. A future multi-circle world only changes the
builder.

## Edge cases

- 0 friends: world is `2 * (baseOuterR + MARGIN)` — same as today's view,
  full-screen; camera still works (nothing to pan to, zoom harmless).
- Window resize / rotation while open: viewport is vmin-based CSS; the
  camera keeps its viewBox (SVG scales), fit remains correct because fit
  is world-derived, not pixel-derived. A resize while zoomed simply
  rescales on screen — acceptable, no listener needed.
- Wheel over a node: zooms (does not open the profile); only click/tap
  under the drag threshold opens.
- Pinch while a drag is in progress: second pointer upgrades the gesture
  from pan to pinch (standard two-pointer bookkeeping keyed by
  pointerId, as in the profile drag).
- Overlay closed mid-gesture (Esc): listeners are removed with the
  overlay's close path; `lostpointercapture` guarantees cleanup.

## Testing

Split per the established workflow:

- **Static/unit (pytest, test_web_assets.py style):** ring-radius scaling
  math present (`scaleWithCount`), camera module + clamps present, pointer
  events wired (`pointercancel`, `lostpointercapture`), `touch-action`
  rule present, no `dragstart`/native DnD, labels threshold class in CSS,
  hint copy updated, Fit button present and a real `<button>`.
- **Live (Playwright + CDP against a demo node, the DnD-smoke pattern):**
  overlay opens at fit with the SVG >= 90% of vmin; wheel at a corner
  zooms toward that corner (world point under cursor stays within
  tolerance); drag pans and is clamped; CDP touch pinch zooms; double-click
  resets to fit; a node click (sub-threshold) still opens the profile;
  Esc closes cleanly mid-gesture; with a synthetic 400-friend KREDS
  payload injected via the API-shaped fixture, the fit view shows the
  whole circle with labels off, and zooming past the threshold turns
  labels on.
- **August (by hand):** feel of wheel/pinch/drag on desktop + phone,
  label fade threshold sanity, "does it finally look like it owns the
  screen."

## Rollout

Web-only change (app.js, style.css, index.html hint text + Fit button).
Ships as 0.3.5 alongside whatever else lands before the next release; no
protocol, API, or packaging impact.
