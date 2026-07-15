# Profile Feedback Batch (0.3.12) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Six profile fixes from August's annotated screenshot: first-fit post placement (revert of push-down creation), banner crop position, Delete-everywhere into the block settings modal, smaller ringless bottom-right scope tag, a self-only Settings page replacing the profile side panels, and a topbar "+" friend-add popover.

**Architecture:** Server work is two surgical node/protocol changes (a `_first_fit_place` sibling to `_push_place`, and an additive `banner_pos` field on KIND_PROFILE — no protocol bump). Everything else is client re-homing in `hearth/web/`: existing render functions keep their container IDs and move houses.

**Tech Stack:** Python 3.12 (FastAPI node), vanilla JS/CSS client, pytest.

**Spec:** `docs/superpowers/specs/2026-07-15-profile-feedback-batch-design.md` — authoritative on any ambiguity.

## Global Constraints

- Windows + PowerShell. Run tests from the repo root with `.venv\Scripts\python.exe -m pytest <paths> -q` (the venv python directly; no activation needed).
- Console is cp1252: ASCII only in Python source/prints (no `°`, `≈`, `→`).
- Git commits: conventional lowercase prefixes (`fix:`, `feat:`, `test:`) matching `git log`. **NO Co-Authored-By / AI trailers** — August's standing rule; the README discloses AI involvement instead.
- `banner_pos` is additive-optional on KIND_PROFILE: integer 0–100, default 50. Old validators ignore unknown fields; never bump a protocol version for it.
- Drag/resize/nudge placement keeps `_push_place` push-on-collision exactly as-is. Only creation and album-ungroup placement change.
- The full suite currently passes with a handful of environment skips. After your task, targeted files must pass; a failure in an unrelated file = stop and report, don't "fix" drive-by.
- Client asset tests live in `tests/test_web_assets.py` and assert on the literal text of `hearth/web/app.js` / `index.html` / `style.css` via the `_js_fn_body` / `_css_rule` helpers at the top of that file.

---

### Task 1: First-fit creation placement (server)

New posts fill the first open gap instead of pinning at (0,0) and pushing the wall down. Album ungroup follows the same rule, newest member first. Nothing already placed ever moves on create/ungroup.

**Files:**
- Modify: `hearth/node.py` (helper near `_push_place` ~line 761; `create_post` auto-place block ~lines 579–617; ungroup branch ~lines 950–976)
- Test: `tests/test_push_placement.py`

**Interfaces:**
- Consumes: `_push_place(pins, msg_id, geom)` (untouched), `MAX_LAYOUT`, `WALL_COLS` from `hearth.messages`.
- Produces: `HearthNode._first_fit_place(pins: dict, msg_id: str, span: dict) -> dict` — `span` is `{"w": int, "h": int}` (no x/y); returns a NEW pins dict including `msg_id` at the first open `(y, x)` spot; raises `ValueError("wall is full")` if no top-left corner with `y <= MAX_LAYOUT` fits. No other entry is ever mutated.

- [ ] **Step 1: Write the failing tests**

In `tests/test_push_placement.py`, REPLACE `test_create_auto_places_at_top_dense` with:

```python
def test_create_fills_first_open_slot_nothing_moves(tmp_path):
    # Spec 2026-07-15 (profile feedback batch): creation is first-fit -
    # the push-down-on-post behavior of 2026-07-14 was August's own
    # called-out mistake and is reverted. Nothing moves on post.
    n = _node(tmp_path)
    a = _post(n, "a")                       # empty wall: (0,0)
    assert _pins(n)[a] == {"x": 0, "y": 0, "w": 4, "h": 1}
    b = _post(n, "b")                       # row 0 taken: first open slot is row 1
    assert _pins(n)[b] == {"x": 0, "y": 1, "w": 4, "h": 1}
    assert _pins(n)[a] == {"x": 0, "y": 0, "w": 4, "h": 1}   # never moved
```

APPEND these new tests after `test_create_with_span_fields_and_dense_beside` (which stays as-is — its expectations already match first-fit):

```python
def test_create_uses_open_gap_between_blocks(tmp_path):
    n = _node(tmp_path)
    a = _post(n, "a")
    b = _post(n, "b")
    n.set_block_pin(a, 0, 0, 4, 1)
    n.set_block_pin(b, 0, 3, 4, 1)          # rows 1-2 left open in the middle
    c = _post(n, "c")                       # 4x1 fits in the gap at (0,1)
    p = _pins(n)
    assert p[c] == {"x": 0, "y": 1, "w": 4, "h": 1}
    assert p[a] == {"x": 0, "y": 0, "w": 4, "h": 1}
    assert p[b] == {"x": 0, "y": 3, "w": 4, "h": 1}


def test_create_skips_too_small_gap(tmp_path):
    n = _node(tmp_path)
    a = n.compose_post("a", scope="kreds", placement="profile",
                       span_w=3, span_h=1)   # row 0 keeps a 1-wide hole at x=3
    b = n.compose_post("b", scope="kreds", placement="profile",
                       span_w=2, span_h=1)   # 2 wide: can't use the 1-wide hole
    p = _pins(n)
    assert p[a] == {"x": 0, "y": 0, "w": 3, "h": 1}
    assert p[b] == {"x": 0, "y": 1, "w": 2, "h": 1}


def test_create_lands_beside_when_it_fits(tmp_path):
    n = _node(tmp_path)
    a = n.compose_post("a", scope="kreds", placement="profile",
                       span_w=2, span_h=2)
    b = n.compose_post("b", scope="kreds", placement="profile",
                       span_w=2, span_h=2)   # fits beside a in row 0
    p = _pins(n)
    assert p[a] == {"x": 0, "y": 0, "w": 2, "h": 2}
    assert p[b] == {"x": 2, "y": 0, "w": 2, "h": 2}
```

REPLACE `test_ungroup_top_inserts_members` with:

```python
def test_ungroup_first_fits_members_newest_first(tmp_path):
    # Ungroup follows creation's first-fit rule (spec 2026-07-15):
    # members are restored newest-first so the newest claims the highest
    # open slot, and nothing already placed ever moves.
    n = _node(tmp_path)
    p1 = n.compose_post("one", scope="kreds", placement="profile",
                        photos=[png_bytes(8, 8)])            # (0,0) 2x2
    p2 = n.compose_post("two", scope="kreds", placement="profile",
                        photos=[png_bytes(8, 8)])            # (2,0) 2x2
    solo = _post(n, "solo")                                  # (0,2) 4x1
    aid = n.set_album([p1, p2])       # both pinned -> album lands unplaced
    before_solo = _pins(n)[solo]
    n.set_album([], album_id=aid)     # ungroup
    p = _pins(n)
    assert p[solo] == before_solo                            # never moved
    assert p[p2] == {"x": 0, "y": 0, "w": 2, "h": 2}         # newest, highest slot
    assert p[p1] == {"x": 2, "y": 0, "w": 2, "h": 2}         # older, next open spot
    assert aid not in p                                      # album pin gone
```

In `test_wall_full_compose_orphans_post_unplaced`, replace only the docstring's first sentence with: `"""The creation raise site (spec 2026-07-15 first-fit): the post is ALREADY published when auto-place runs, so a wall with no open cell at or above the row cap gives the caller a ValueError (400, no msg_id) while the post EXISTS orphaned-unplaced - no pin, no span - degrading honestly to the legacy flow-below rendering until /api/wall-autoplace adopts it."""` — the body is unchanged (the tiled stack leaves no gap, so first-fit scans past `MAX_LAYOUT` and raises; `auto_place_unplaced` still push-raises too).

- [ ] **Step 2: Run to verify the new tests fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_push_placement.py -q`
Expected: the replaced/new tests FAIL (current code pushes: e.g. `b` lands at y=0 and `a` moves to y=1); the untouched push tests still pass.

- [ ] **Step 3: Implement `_first_fit_place`**

In `hearth/node.py`, directly AFTER `_push_place` (after its closing `return final`, ~line 787), add:

```python
    def _first_fit_place(self, pins: dict, msg_id: str, span: dict) -> dict:
        """Creation/ungroup placement rule (spec 2026-07-15, reverting
        2026-07-14's top-insert-push for creation): scan cells in (y, x)
        order and settle the block at the first open spot its w x h
        footprint fits inside the canvas - gaps in the middle get used,
        and NOTHING already placed ever moves. With no fitting gap the
        scan lands just below the lowest block. Content-deterministic
        like _push_place; the published record carries the RESULT."""
        def overlaps(a, b):
            return (a["x"] < b["x"] + b["w"] and b["x"] < a["x"] + a["w"]
                    and a["y"] < b["y"] + b["h"] and b["y"] < a["y"] + a["h"])
        others = [g for k, g in pins.items() if k != msg_id]
        w, h = span["w"], span["h"]
        for y in range(MAX_LAYOUT + 1):        # same top-row cap as set_block_pin
            for x in range(WALL_COLS - w + 1):
                geom = {"x": x, "y": y, "w": w, "h": h}
                if not any(overlaps(geom, g) for g in others):
                    return {**{k: dict(v) for k, v in pins.items()
                               if k != msg_id},
                            msg_id: geom}
        raise ValueError("wall is full")
```

- [ ] **Step 4: Switch creation auto-place to first-fit**

In `create_post` (`hearth/node.py` ~line 579), the `if placement == "profile" and auto_place:` block: replace the FIRST comment paragraph (the 5 lines starting `# Creation auto-places at the top, dense (spec 2026-07-14): a` down to `# separate span-seed call is gone. August 2026-07-14.`) with:

```python
            # Creation is FIRST-FIT (spec 2026-07-15, August's revert of
            # the 2026-07-14 top-insert-push): the new post takes the
            # first open slot its composer-chosen (or default) size fits
            # - gaps get used, nothing already placed ever moves. The
            # separate span-seed call stays gone.
```

Keep the `auto_place=False` paragraph and the orphan-honesty paragraph verbatim (still accurate — the raise now comes from first-fit finding no open cell at or above the row cap, or the count cap below). Then replace the placement line:

```python
            pins = self._push_place(cur["pins"], mid, {"x": 0, "y": 0, **span})
```

with:

```python
            pins = self._first_fit_place(cur["pins"], mid, span)
```

(`span`, `cur`, `spans.pop`, the `len(pins) > MAX_LAYOUT` check, and the publish stay exactly as they are.)

- [ ] **Step 5: Switch ungroup to first-fit, newest first**

In `set_album`'s ungroup branch (~line 950): in the comment ending `# top-insert each restored member oldest first so the newest\n# ends on top - same push rule, no limbo.` replace those two lines with:

```python
            # first-fit each restored member NEWEST first so the newest
            # claims the highest open slot (spec 2026-07-15) - nothing
            # already placed moves, no limbo.
```

and change the loop head and placement call:

```python
                for mid in sorted(prior_members, key=_created_at, reverse=True):
                    span = spans.pop(mid, None)
                    if span is None:
                        msg = self.store.get_message(mid)
                        pl = msg.payload if msg else {}
                        has_media = (bool(pl.get("blobs"))
                                    or pl.get("media") == "video")
                        span = {"w": 2, "h": 2} if has_media else {"w": 4, "h": 1}
                    pins = self._first_fit_place(pins, mid, span)
```

(Only `reverse=True` and the last line change; the span-default body is as today.)

- [ ] **Step 6: Run the placement suites**

Run: `.venv\Scripts\python.exe -m pytest tests/test_push_placement.py tests/test_block_pins.py tests/test_api_pins.py tests/test_profile_pins_integration.py tests/test_albums_node.py tests/test_ui_smoke_collage.py tests/test_ui_smoke_albums.py -q`
Expected: ALL PASS. If a smoke test asserts old push-on-post choreography, update its expectation to first-fit (post lands in first open slot; wall never shifts) — do not weaken assertions.

- [ ] **Step 7: Commit**

```powershell
git add hearth/node.py tests/test_push_placement.py
git commit -m "fix(wall): creation + ungroup placement is first-fit - new posts take the first open slot, nothing moves; drag keeps push-on-collision (August revert, spec 2026-07-15)"
```

(Include any smoke-test files you had to update in the `git add`.)

---

### Task 2: `banner_pos` record field + server plumbing

**Files:**
- Modify: `hearth/messages.py` (`make_profile` ~line 96; KIND_PROFILE validation ~line 249)
- Modify: `hearth/node.py` (`set_profile` ~line 687; `profile_view` fallback rec ~line 1078)
- Modify: `hearth/store.py` (profile fold ~line 499)
- Modify: `hearth/api.py` (`POST /api/profile` ~line 465)
- Test: `tests/test_profile_model.py`, `tests/test_node_profile.py`, `tests/test_api_profile.py`

**Interfaces:**
- Produces: KIND_PROFILE payload key `banner_pos` (int 0–100, absent treated as 50 everywhere); `make_profile(..., banner=None, banner_pos: int = 50, now=None)`; `node.set_profile(..., banner_bytes=None, banner_pos=None)` — `None` keeps the stored value (default 50); `store.profile()` and `profile_view()` dicts gain `"banner_pos"`; `POST /api/profile` accepts optional form field `banner_pos`.
- Consumed by: Task 3 (client render + editor).

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_profile_model.py` (it defines `device()` at the top — reuse it):

```python
def test_banner_pos_roundtrip_and_default():
    # banner_pos (spec 2026-07-15): vertical background-position percent,
    # 0 = top of the image, 100 = bottom, default 50 (center). Additive -
    # old validators ignore unknown fields, so no protocol bump.
    d = device()
    m = make_profile(d, "Wong", banner="cd" * 32, banner_pos=10)
    assert m.payload["banner_pos"] == 10
    assert validate_payload(m.payload) == (True, "ok")
    assert make_profile(d, "Wong").payload["banner_pos"] == 50   # default


def test_banner_pos_validation_bounds():
    ok = lambda p: validate_payload(p)[0]
    base = {"kind": "profile", "name": "Wong", "created_at": 1.0}
    assert ok(base)                                  # absent -> default 50
    assert ok({**base, "banner_pos": 0})
    assert ok({**base, "banner_pos": 100})
    assert not ok({**base, "banner_pos": -1})
    assert not ok({**base, "banner_pos": 101})
    assert not ok({**base, "banner_pos": 50.5})      # int only
    assert not ok({**base, "banner_pos": True})      # bool is not an int here
    assert not ok({**base, "banner_pos": "50"})
```

Append to `tests/test_node_profile.py` (reuse its `png()` helper):

```python
def test_banner_pos_saved_and_carried_forward(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    n.set_profile("Wong", banner_bytes=png(1200, 400), banner_pos=20)
    p = n.store.profile(n.identity_pub)
    assert p["banner_pos"] == 20 and p["banner"] is not None
    n.set_profile("Wong Two")                # no banner_pos: keep stored
    p = n.store.profile(n.identity_pub)
    assert p["banner_pos"] == 20 and p["name"] == "Wong Two"
    assert n.profile_view(n.identity_pub)["banner_pos"] == 20


def test_banner_pos_default_and_bad_value(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    n.set_profile("Wong")
    assert n.store.profile(n.identity_pub)["banner_pos"] == 50
    assert n.profile_view(n.identity_pub)["banner_pos"] == 50
    with pytest.raises(ValueError):
        n.set_profile("Wong", banner_pos=101)
```

Append to `tests/test_api_profile.py`:

```python
def test_post_profile_banner_pos_roundtrip(tmp_path):
    c, node = client(tmp_path)
    r = c.post("/api/profile", data={"name": "Wong", "banner_pos": "15"},
               files=[("banner", ("b.png", png(1200, 400), "image/png"))])
    assert r.status_code == 200
    prof = c.get(f"/api/profile/{node.identity_pub}").json()
    assert prof["banner_pos"] == 15


def test_post_profile_banner_pos_bad_400_and_absent_default(tmp_path):
    c, node = client(tmp_path)
    assert c.post("/api/profile",
                  data={"name": "Wong", "banner_pos": "150"}).status_code == 400
    assert c.post("/api/profile", data={"name": "Wong"}).status_code == 200
    assert c.get(f"/api/profile/{node.identity_pub}").json()["banner_pos"] == 50
```

- [ ] **Step 2: Run to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_profile_model.py tests/test_node_profile.py tests/test_api_profile.py -q`
Expected: new tests FAIL (`banner_pos` TypeError / KeyError); existing ones pass.

- [ ] **Step 3: Implement — messages.py**

`make_profile` signature: add `banner_pos: int = 50` between `banner` and `now`; payload dict: add `"banner_pos": banner_pos,` right after `"banner": banner,`.

Validation, in the `if kind == KIND_PROFILE:` block after the `avatar_align` check (~line 267):

```python
        bp = p.get("banner_pos", 50)
        if not isinstance(bp, int) or isinstance(bp, bool) or not 0 <= bp <= 100:
            return False, "bad banner_pos"
```

- [ ] **Step 4: Implement — store.py, node.py, api.py**

`store.py` profile fold (~line 507): after `"banner": best.get("banner"),` add `"banner_pos": best.get("banner_pos", 50),`.

`node.py set_profile`: signature gains `banner_pos=None` after `banner_bytes=None`. In the body, after the banner transcode block:

```python
        if banner_pos is None:                       # editor didn't touch the crop
            banner_pos = current.get("banner_pos", 50)
        if not isinstance(banner_pos, int) or isinstance(banner_pos, bool) \
                or not 0 <= banner_pos <= 100:       # pre-check -> 400, not a 500 from _publish
            raise ValueError("bad banner_pos")
```

and pass `banner_pos=banner_pos` into the `make_profile(...)` call.

`node.py profile_view` fallback rec (~line 1082): add `"banner_pos": 50` after `"banner": None`.

`api.py POST /api/profile`: add parameter `banner_pos: Optional[int] = Form(default=None),` before the `avatar` UploadFile param (add `Optional` to the `typing` import if the file doesn't already import it), and pass `banner_pos=banner_pos` into the `node.set_profile(...)` lambda (node-side validation makes a bad value a 400 via `_400`).

- [ ] **Step 5: Run to verify green**

Run: `.venv\Scripts\python.exe -m pytest tests/test_profile_model.py tests/test_node_profile.py tests/test_api_profile.py tests/test_store_profile.py tests/test_messages.py -q`
Expected: ALL PASS.

- [ ] **Step 6: Commit**

```powershell
git add hearth/messages.py hearth/store.py hearth/node.py hearth/api.py tests/test_profile_model.py tests/test_node_profile.py tests/test_api_profile.py
git commit -m "feat(profile): banner_pos crop field - additive 0-100 background-position-y percent, default 50; carried forward on save like the banner itself"
```

---

### Task 3: Banner crop — client render + editor drag control

**Files:**
- Modify: `hearth/web/app.js` (`renderProfilePage` ~line 1749; `profileEditor` ~line 2953; `fallbackProfile` ~line 1673)
- Modify: `hearth/web/style.css` (near `.profile-banner`, ~line 390)
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: `p.banner_pos` from `GET /api/profile/...` (Task 2); posts form field `banner_pos` on save.
- Produces: `.banner-crop` / `.banner-crop-preview` editor control; `#profile-banner` gets an inline `background-position`.

- [ ] **Step 1: Write the failing asset test**

Append to `tests/test_web_assets.py`:

```python
def test_banner_crop_control_wired():
    # Banner crop (spec 2026-07-15): drag the editor preview up/down (or
    # use the paired range slider - the keyboard path); the profile
    # banner renders background-position from banner_pos.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    editor = _js_fn_body(js, "profileEditor")
    assert "banner_pos" in editor and "banner-crop" in editor
    assert 'type = "range"' in editor or 'type="range"' in editor
    assert "pointerdown" in editor                   # drag path
    page = _js_fn_body(js, "renderProfilePage")
    assert "backgroundPosition" in page and "banner_pos" in page
    rule = _css_rule(css, ".banner-crop-preview")
    assert "cover" in rule and "ns-resize" in rule
```

- [ ] **Step 2: Run to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py::test_banner_crop_control_wired -q`
Expected: FAIL (no `banner_pos` in profileEditor).

- [ ] **Step 3: Implement — render + fallback**

`renderProfilePage` (~line 1750), after the `banner.style.backgroundImage` line, add:

```js
  // banner_pos (spec 2026-07-15): vertical crop percent, 0 = top of the
  // image, 100 = bottom; 50 = the old hardcoded center.
  banner.style.backgroundPosition =
    "center " + (Number.isInteger(p.banner_pos) ? p.banner_pos : 50) + "%";
```

`fallbackProfile` (~lines 1679 and 1686): in BOTH object literals add `banner_pos: 50,` right after `banner: null,`.

- [ ] **Step 4: Implement — editor control**

In `profileEditor` (~line 2955), replace the two Banner lines:

```js
  box.append(el("div","lbl","Banner"));
  const bn = document.createElement("input"); bn.type="file"; bn.accept="image/*"; box.append(bn);
```

with:

```js
  box.append(el("div","lbl","Banner"));
  const bn = document.createElement("input"); bn.type="file"; bn.accept="image/*"; box.append(bn);
  // Banner crop (spec 2026-07-15): drag the preview up/down to pick which
  // band of the image the banner strip shows; the range slider is the
  // same value's keyboard path. banner_pos = background-position-y
  // percent (0 top, 100 bottom). Shown only once a banner exists.
  let bannerPos = Number.isInteger(p.banner_pos) ? p.banner_pos : 50;
  const crop = el("div", "banner-crop hidden");
  const preview = el("div", "banner-crop-preview");
  preview.setAttribute("aria-hidden", "true");   // the slider is the accessible control
  const slider = document.createElement("input");
  slider.type = "range"; slider.min = "0"; slider.max = "100";
  slider.value = String(bannerPos);
  slider.setAttribute("aria-label", "Banner crop position");
  const applyPos = (v) => {
    bannerPos = Math.max(0, Math.min(100, Math.round(v)));
    slider.value = String(bannerPos);
    preview.style.backgroundPosition = "center " + bannerPos + "%";
  };
  slider.oninput = () => applyPos(Number(slider.value));
  preview.addEventListener("pointerdown", (ev) => {
    ev.preventDefault();                       // no text-selection smear
    const start = bannerPos, sy = ev.clientY;
    const rect = preview.getBoundingClientRect();
    try { preview.setPointerCapture(ev.pointerId); } catch (e) { /* not fatal */ }
    const move = (e) => {
      if (e.pointerId !== ev.pointerId) return;
      // dragging the image DOWN reveals more of its top = smaller percent
      applyPos(start - ((e.clientY - sy) / rect.height) * 100);
    };
    const up = (e) => {
      if (e.pointerId !== ev.pointerId) return;
      preview.removeEventListener("pointermove", move);
      preview.removeEventListener("pointerup", up);
    };
    preview.addEventListener("pointermove", move);
    preview.addEventListener("pointerup", up);
  });
  const showCrop = (url) => {
    preview.style.backgroundImage = `url(${url})`;
    preview.style.backgroundPosition = "center " + bannerPos + "%";
    crop.classList.remove("hidden");
  };
  if (p.banner) showCrop("/api/blob/" + p.banner);
  bn.onchange = () => { if (bn.files[0]) showCrop(URL.createObjectURL(bn.files[0])); };
  crop.append(preview, slider);
  box.append(crop);
```

In the same function's `save.onclick`, after `fd.append("avatar_align", align.v);` add:

```js
    fd.append("banner_pos", String(bannerPos));
```

- [ ] **Step 5: Implement — CSS**

In `hearth/web/style.css`, directly after the `.profile-banner` rule (~line 391), add:

```css
/* Banner crop (spec 2026-07-15): the editor's drag-up/down preview -
   same 120px strip as .profile-banner so what you frame is what ships. */
.banner-crop { margin-top: 8px; }
.banner-crop-preview { height: 120px; border-radius: 10px; cursor: ns-resize;
  background-size: cover; background-position: center 50%;
  border: 1px solid var(--line-2); touch-action: none; }
.banner-crop input[type="range"] { width: 100%; margin-top: 6px; padding: 0; }
```

- [ ] **Step 6: Run to verify green**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q`
Expected: ALL PASS (including the new test).

- [ ] **Step 7: Commit**

```powershell
git add hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat(profile): banner crop control - drag the editor preview (slider = keyboard path), profile renders banner_pos; dog heads stay attached"
```

---

### Task 4: "Delete everywhere" into the block settings modal; gear available outside Arrange

**Files:**
- Modify: `hearth/web/app.js` (`renderBlock` ~lines 554–622; `openBlockSettings` after the album/Ungroup group ~line 1059)
- Modify: `hearth/web/style.css` (AFTER the existing `.block-settings-btn` rules, ~line 601 — see note in Step 4)
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: `deleteEverywhere(msgId)` (app.js:74, confirm + POST), `closeBlockSettings()`, `currentView()`, `CURRENT_PROFILE`.
- Produces: a `Delete` settings-group in the modal; `.block-settings-btn` present on every own block (hover-revealed outside Arrange); journal `buildEntry` keeps its inline delete untouched.

- [ ] **Step 1: Write the failing asset tests**

Append to `tests/test_web_assets.py`:

```python
def test_delete_everywhere_rehomed_into_block_settings():
    # Spec 2026-07-15: the always-visible per-block delete button leaves
    # the wall face; the settings modal owns it now. Journal entries keep
    # their inline delete; albums keep the ungroup-first rule.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    rb = _js_fn_body(js, "renderBlock")
    assert "Delete everywhere" not in rb
    assert "block-settings-btn" in rb              # gear on every own block
    obs = _js_fn_body(js, "openBlockSettings")
    assert "Delete everywhere" in obs and "deleteEverywhere" in obs
    assert "Delete everywhere" in _js_fn_body(js, "buildEntry")


def test_block_gear_hover_revealed_outside_arrange():
    css = (WEB / "style.css").read_text(encoding="utf-8")
    rule = _css_rule(css, ".block .block-settings-btn")
    assert "opacity: 0" in rule
    assert ".block:hover .block-settings-btn" in css
    assert "pointer: coarse" in css                # touch always shows it
```

- [ ] **Step 2: Run to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest "tests/test_web_assets.py::test_delete_everywhere_rehomed_into_block_settings" "tests/test_web_assets.py::test_block_gear_hover_revealed_outside_arrange" -q`
Expected: both FAIL.

- [ ] **Step 3: Implement — renderBlock**

(a) DELETE the whole inline-delete block (~lines 561–572):

```js
    // No one-tap "Delete everywhere" on an album: ungroup first, then
    // delete the standalone post (v1 honest limit - see Ungroup in the
    // settings modal).
    if (!p.album) {
      const del = el("button", "pact del", "Delete everywhere");
      del.onclick = async () => {
        if (!await deleteEverywhere(p.msg_id)) return;
        await refresh();
        if (currentView() === "profile" && CURRENT_PROFILE) openProfile(CURRENT_PROFILE);
      };
      block.append(del);
    }
```

(b) In its place (still inside `if (p.mine) {`, after the scope-badge block), add the gear — and DELETE the identical `const cog = ...` block from inside `if (ARRANGING && p.mine)` (~lines 607–612) so it exists exactly once:

```js
    // Gear (spec 2026-07-15): the settings modal's entry point, now on
    // every own block OUTSIDE Arrange too - with the inline delete gone,
    // the modal is the only delete path, so it can't stay Arrange-gated.
    // CSS hover-reveals it on fine pointers; coarse pointers always see it.
    const cog = el("button", "block-settings-btn");
    cog.innerHTML = ICONS.cog;
    cog.type = "button";
    cog.setAttribute("aria-label", "Block settings");
    cog.onclick = () => openBlockSettings(p, block, cog);
    block.append(cog);
```

Note: `if (ARRANGING && p.mine)` keeps everything else (arranging class, tabIndex, resize handle, drag pointerdown). The drag handler's `closest("button, ...")` guard already lets the gear click through.

- [ ] **Step 4: Implement — modal Delete group + CSS**

In `openBlockSettings`, after the `if (p.album) { ... body.append(ungroup); }` block (~line 1059) and BEFORE `document.getElementById("block-settings").classList.remove("hidden");`, add:

```js
  // Delete (spec 2026-07-15): re-homed from the block face. Albums keep
  // the ungroup-first rule - no one-tap delete on a folded deck.
  if (!p.album) {
    const grp = el("div", "settings-group");
    grp.append(el("div", "settings-label", "Delete"));
    const del = el("button", "settings-opt settings-del", "Delete everywhere");
    del.type = "button";
    del.onclick = async () => {
      if (!await deleteEverywhere(p.msg_id)) return;
      closeBlockSettings();                       // the block is gone
      await refresh();
      if (currentView() === "profile" && CURRENT_PROFILE) openProfile(CURRENT_PROFILE);
    };
    grp.append(del);
    body.append(grp);
  }
```

In `style.css`, add AFTER the existing `.block-settings-btn:hover` rule (~line 601). Placement matters: `test_round_icon_buttons_reset_form_padding` greps the FIRST `.block-settings-btn {`-shaped match for `padding: 0`, and `.block .block-settings-btn {` would match that pattern — these new rules must come later in the file than the original:

```css
/* Gear outside Arrange (spec 2026-07-15): hover-revealed on fine
   pointers so the wall stays clean; always visible where hover doesn't
   exist; Arrange keeps it permanent. */
.block .block-settings-btn { opacity: 0; transition: opacity .15s; }
.block:hover .block-settings-btn, .block:focus-within .block-settings-btn,
.block.arranging .block-settings-btn { opacity: 1; }
@media (pointer: coarse) { .block .block-settings-btn { opacity: 1; } }
.settings-del { color: var(--red); }
.settings-del:hover { background: var(--red-soft); }
```

Also DELETE the now-dead positional rule `.block .del { position: absolute; bottom: 6px; left: 6px; right: auto; z-index: 2; }` (~line 570) and its two comment lines — the wall no longer renders a `.pact del` (journal's `.pact.del` styling at ~line 340 stays).

- [ ] **Step 5: Run the asset tests**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py tests/test_ui_smoke_collage.py tests/test_ui_smoke_albums.py tests/test_ui_smoke_seen_badge.py -q`
Expected: ALL PASS. If a smoke test drove the inline delete button, reroute it through the gear -> modal path (same end state asserted).

- [ ] **Step 6: Commit**

```powershell
git add hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "fix(wall): Delete everywhere moves into the block settings modal; gear now hover-revealed on every own block outside Arrange"
```

(Add smoke files if touched.)

---

### Task 5: Scope tag — smaller, ringless, bottom-right, hidden while arranging

**Files:**
- Modify: `hearth/web/style.css` (rules at ~lines 567 and 577–581)
- Test: `tests/test_web_assets.py`

**Interfaces:** none new — markup (`.block-scope`, self-only, skipped on albums) is untouched; this is CSS-only.

- [ ] **Step 1: Write the failing asset test**

Append to `tests/test_web_assets.py`:

```python
def test_scope_tag_small_ringless_bottom_right():
    # Spec 2026-07-15: the Inner/Kreds badge sheds its pill ring, shrinks,
    # and moves to the block's bottom-right (freed by the delete button's
    # departure). Arrange hides it - the resize handle owns that corner.
    css = (WEB / "style.css").read_text(encoding="utf-8")
    base = _css_rule(css, ".block-scope")          # base rule must come FIRST in the file
    assert "9px" in base and "border" not in base and "padding" not in base
    pos = _css_rule(css, ".block .block-scope")
    assert "bottom" in pos and "right" in pos
    assert "top" not in pos and "left" not in pos
    hide = _css_rule(css, ".block.arranging .block-scope")
    assert "display: none" in hide
```

- [ ] **Step 2: Run to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py::test_scope_tag_small_ringless_bottom_right -q`
Expected: FAIL.

- [ ] **Step 3: Implement**

In `style.css`, DELETE both existing rules and their comments:

- `~line 566–567`: the `/* self-only overlays... */` comment + `.block .block-scope { position: absolute; top: 6px; left: 6px; z-index: 2; }`
- `~lines 577–581`: the `/* Fix B: per-block audience badge... */` comment + `.block-scope { display: inline-block; font-family: var(--mono); font-size: 10px; color: var(--ink-2); border: 1px solid var(--line-2); border-radius: 99px; padding: 1px 8px; margin-top: 6px; }`

In their place (where the old base rule was, keeping the BASE rule textually FIRST — the asset helper's regex for `.block-scope` matches the first occurrence, which must not be the positional `.block .block-scope` rule), add:

```css
/* Per-block audience badge (self-only). Spec 2026-07-15: small, ringless,
   dimmed - a whisper, not a chip. */
.block-scope { display: inline-block; font-family: var(--mono); font-size: 9px;
  color: var(--ink-2); opacity: .75; }
/* bottom-right (the inline delete's departure freed the bottom edge);
   hidden while arranging - Task 6's .block-resize corner handle owns
   bottom-right there. */
.block .block-scope { position: absolute; bottom: 6px; right: 6px; z-index: 2; }
.block.arranging .block-scope { display: none; }
```

- [ ] **Step 4: Run to verify green**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```powershell
git add hearth/web/style.css tests/test_web_assets.py
git commit -m "fix(wall): scope tag - 9px ringless whisper at bottom-right, hidden in Arrange (resize handle owns that corner)"
```

---

### Task 6: Settings page — re-home the self-only side panels

**Files:**
- Modify: `hearth/web/index.html` (profile side panels ~lines 148–179; cog ~line 117; idstrip ~line 189; edit overlay ~lines 220–222; new `#view-settings` after `#view-profile`)
- Modify: `hearth/web/app.js` (`setView` ~3444; `renderProfilePage` ~1770–1786; `openProfileEditor`/`closeEditOverlay` ~1850–1860; `profileEditor` save ~2966; `goView` ~3455; `openMe` ~3467; new `openSettings`/`wireSettingsSections`)
- Modify: `hearth/web/style.css` (settings-page rules; delete `#profile-edit-overlay` rules)
- Test: `tests/test_web_assets.py` (new tests + updates to `test_profile_two_sections_and_cogwheel` ~215, `test_whole_branch_review_fixes` ~395, `test_applock_settings_section_present` ~498, `test_settings_toggle_desktop_only_in_me_area` ~802, `test_updates_panel_markup_present` ~821)

**Interfaces:**
- Consumes: `profileEditor(p)` (Task 3's version), `renderMeStrip()`, `ceremonyUI()` (boot-called, builds into `#ceremony`), `fallbackProfile`, `setView`, `openMe`, `j()`.
- Produces: `#view-settings` with `<details class="panel settings-section">` sections `sec-editprofile`, `sec-friends` (hosts `#friends` + `#ceremony`), `sec-devices` (`#devices`), `sec-applock` (`#applock-settings`), `sec-desktop` (`#desktop-settings`, keeps class `desktop-only-panel`), `sec-updates` (`#update-settings`); `async function openSettings()`; localStorage keys `kreds_settings_open_<sectionId>`. `#profile-edit-overlay`, `openProfileEditor`, `closeEditOverlay` are GONE (Task 7 must not reference them).

- [ ] **Step 1: Write the failing asset tests**

Append to `tests/test_web_assets.py`:

```python
def test_settings_view_markup_and_rehomed_panels():
    # Spec 2026-07-15: the self-only side panels move to a Settings page
    # (cog opens it); the profile right column keeps ONLY the journal
    # rail, so own and friends' profiles finally align.
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert 'id="view-settings"' in html
    for sec in ("sec-editprofile", "sec-friends", "sec-devices",
                "sec-applock", "sec-desktop", "sec-updates"):
        assert f'id="{sec}"' in html
    settings = html.split('id="view-settings"')[1].split('id="idstrip')[0]
    for inner in ("settings-editprofile", 'id="friends"', 'id="ceremony"',
                  'id="devices"', "applock-settings", "desktop-settings",
                  "update-settings"):
        assert inner in settings
    assert "desktop-only-panel" in settings          # browser never sees Desktop
    side = html.split('id="profile-side"')[1].split("</aside>")[0]
    assert "journal-rail" in side
    assert "applock-settings" not in side and 'id="friends"' not in side
    assert 'id="profile-edit-overlay"' not in html   # overlay is dead
    assert "manage in Settings" in html


def test_settings_page_wiring_and_collapse_memory():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    body = _js_fn_body(js, "openSettings")
    assert "settings-editprofile" in body and "profileEditor" in body
    assert "renderMeStrip" in body and 'setView("settings")' in body
    assert "kreds_settings_open_" in js              # collapse state remembered
    assert "openProfileEditor" not in js and "closeEditOverlay" not in js
```

- [ ] **Step 2: Update the five existing asset tests**

In `tests/test_web_assets.py`:

(a) `test_profile_two_sections_and_cogwheel` (~line 213): replace the two cog/overlay lines with:

```python
    # cogwheel now opens the Settings page (spec 2026-07-15), not an overlay
    assert "profile-cog" in html or "profile-cog" in js
    assert "openSettings" in js
```

(b) `test_whole_branch_review_fixes` (~line 395): delete the line `assert "position: fixed" in _css_rule(css, "#profile-edit-overlay")` and above it update the comment's "these two must match it" to "#block-settings must match it" (the edit overlay died with the Settings page, spec 2026-07-15).

(c) `test_applock_settings_section_present` (~lines 501–503): replace the two `profile-applock-panel` asserts with:

```python
    assert 'id="sec-applock"' in html                # Settings page section (spec 2026-07-15)
```

(d) `test_settings_toggle_desktop_only_in_me_area` (~line 805): replace `assert 'id="profile-desktop-panel"' in html` with `assert 'id="sec-desktop"' in html`.

(e) `test_updates_panel_markup_present` (~lines 823–829): replace the body with:

```python
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert 'id="sec-updates"' in html
    assert 'id="update-settings"' in html
    assert "Updates" in html.split('id="sec-updates"')[1][:120]
```

- [ ] **Step 3: Run to verify the new tests fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q`
Expected: the two new tests FAIL; the five edited ones now FAIL too (markup not moved yet). Everything else passes.

- [ ] **Step 4: Implement — index.html**

(a) Cog button (~line 117): change `aria-label="Edit profile" title="Edit profile"` to `aria-label="Settings" title="Settings"`.

(b) Inside `#profile-side` (~lines 148–179): DELETE the five self-only panels (`#profile-friends-panel`, `#profile-devices-panel`, `#profile-applock-panel`, `#profile-desktop-panel` with its comment, `#profile-updates-panel` with its comment). Keep only the journal-rail panel.

(c) After `#view-profile`'s closing `</div>` (~line 182), add:

```html
    <!-- Settings page (spec 2026-07-15): the self-only panels, re-homed
         off the profile side column (which keeps only the journal rail).
         Reached via the profile cog; each section collapses independently
         and remembers its state locally (app.js wireSettingsSections). -->
    <div id="view-settings" class="hidden">
      <div class="profile-topbar">
        <button class="profile-back" id="settings-back">&larr; Back</button>
        <h2 class="settings-title">Settings</h2>
      </div>
      <div class="settings-page">
        <details class="panel settings-section" id="sec-editprofile" open>
          <summary>Edit profile</summary>
          <div id="settings-editprofile"></div>
        </details>
        <details class="panel settings-section" id="sec-friends" open>
          <summary>Friends</summary>
          <div id="friends"></div>
          <div id="ceremony"></div>
        </details>
        <details class="panel settings-section" id="sec-devices" open>
          <summary>Devices</summary>
          <div id="devices"></div>
        </details>
        <details class="panel settings-section" id="sec-applock" open>
          <summary>App-lock</summary>
          <div id="applock-settings"></div>
        </details>
        <!-- Desktop close-behavior: selfonly by reachability (Settings is
             cog-gated) AND desktop-only - .desktop-only-panel stays
             display:none unless body.desktop (wireDesktopChrome). -->
        <details class="panel settings-section desktop-only-panel" id="sec-desktop" open>
          <summary>Desktop</summary>
          <div id="desktop-settings"></div>
        </details>
        <!-- Signed in-app updates: manual "Check for updates" only -
             GET /api/update/check, then POST /api/update/apply. -->
        <details class="panel settings-section" id="sec-updates" open>
          <summary>Updates</summary>
          <div id="update-settings"></div>
        </details>
      </div>
    </div>
```

(d) idstrip (~line 189): change `&middot; manage in Me` to `&middot; manage in Settings`.

(e) DELETE the `#profile-edit-overlay` div and its comment (~lines 217–222).

- [ ] **Step 5: Implement — app.js**

(a) `setView` (~line 3446): change the loop list to `["journal", "messages", "profile", "settings"]` and the remember-guard to `if (which !== "profile" && which !== "settings") localStorage.setItem("hearth_view", which);` (settings, like profile, is not a restore target).

(b) `renderProfilePage`: change the cog wiring (~line 1772) to `cog.onclick = p.mine ? () => openSettings() : null;`. DELETE the self-only panel toggling + strip fill (~lines 1784–1786):

```js
  document.querySelectorAll("#profile-side .selfonly").forEach(el2 =>
    el2.classList.toggle("hidden", !p.mine));
  if (p.mine) renderMeStrip();     // fills #friends / #devices / #ceremony
```

and update the comment above (~1780-1781) to: `// Right column: ONLY the Journal rail now, on every profile (spec 2026-07-15) - the self-only panels live on the Settings page.`

(c) REPLACE `openProfileEditor`, `closeEditOverlay`, and the overlay click listener (~lines 1847–1860) with:

```js
// Settings page (spec 2026-07-15): the self-only side panels + the
// profile editor, re-homed - the cog opens this instead of the old edit
// overlay. Like the profile page, content refreshes on entry, not on
// every WS tick. Section collapse is a local UI preference
// (localStorage), deliberately not synced state.
async function openSettings() {
  let p;
  try {
    p = await j("/api/profile/" + STATE.identity_pub);
  } catch (e) {
    p = fallbackProfile(STATE.identity_pub);
  }
  document.getElementById("settings-editprofile")
    .replaceChildren(profileEditor(p));
  renderMeStrip();   // fills #friends / #devices / App-lock / Desktop / Updates
  setView("settings");
}
document.getElementById("settings-back").onclick = () => openMe();
function wireSettingsSections() {
  document.querySelectorAll("#view-settings .settings-section").forEach(d => {
    const key = "kreds_settings_open_" + d.id;
    const saved = localStorage.getItem(key);
    if (saved !== null) d.open = saved === "1";
    d.addEventListener("toggle", () =>
      localStorage.setItem(key, d.open ? "1" : "0"));
  });
}
wireSettingsSections();
```

(d) `profileEditor` save handler (~lines 2966–2973): replace the ok-branch body

```js
      await refresh();
      // The editor now lives in the cogwheel overlay, not inline on the
      // page; re-render the profile underneath so the saved name/bio/
      // avatar/banner show immediately, then close the overlay.
      openProfile(STATE.identity_pub);
      closeEditOverlay();
```

with:

```js
      await refresh();
      // The editor lives on the Settings page now (spec 2026-07-15):
      // re-enter it so the saved values (and a fresh banner blob ref)
      // show immediately, staying on Settings.
      openSettings();
```

(e) Remove the two remaining `closeEditOverlay();` call sites: in `goView` (~line 3455) and `openMe` (~line 3467) — delete the lines (and `goView`'s trailing comment).

(f) `renderMeStrip`'s header comment block (~lines 2260–2264): update "now a self-only side strip on the profile page" to "now sections on the self-only Settings page (spec 2026-07-15)".

- [ ] **Step 6: Implement — style.css**

DELETE the `#profile-edit-overlay` rules (grep `#profile-edit-overlay` — the fixed-position rule near the `#block-settings` block, plus any `.profile-editcard` sizing that references the overlay; keep `.editor` form styles, `profileEditor` still uses them). Add, near the `#view-settings`-era styles (after the `.profile-side` rules ~line 365):

```css
/* Settings page (spec 2026-07-15): re-homed self-only panels. */
#view-settings { max-width: 640px; margin: 0 auto; }
#view-settings .settings-title { font-family: var(--disp); font-size: 20px; margin: 0; }
.settings-page .settings-section { margin-bottom: 16px; }
.settings-section > summary { cursor: pointer; font-weight: 600; font-size: 15px; }
.settings-section[open] > summary { margin-bottom: 10px; }
```

- [ ] **Step 7: Run the asset + applock/update suites**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py tests/test_applock_api.py tests/test_update_api.py tests/test_friend_add_api.py tests/test_pwa_assets.py -q`
Expected: ALL PASS. If `test_applock_api.py`/`test_update_api.py`/`test_friend_add_api.py` assert old panel markup, update those assertions to the `sec-*` sections (same pattern as Step 2's edits) — endpoints and render functions are untouched, so only markup-location assertions can break.

- [ ] **Step 8: Commit**

```powershell
git add hearth/web/index.html hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat(settings): self-only panels + profile editor move to a Settings page (cog opens it) - collapsible remembered sections; profile right column keeps only the journal rail"
```

(Add other test files if touched in Step 7.)

---

### Task 7: Topbar "+" friend-add popover

**Files:**
- Modify: `hearth/web/index.html` (profile topbar ~line 116; new overlay before `#block-settings`)
- Modify: `hearth/web/app.js` (`ceremonyUI` ~line 3236 refactor; `renderProfilePage` topbar wiring; global Escape handler ~line 1074)
- Modify: `hearth/web/style.css` (near `.profile-cog` ~line 387; near `#block-settings` ~line 646)
- Test: `tests/test_web_assets.py` (new test + updates to `test_friend_add_entry_point_and_tabs_present` ~882 and `test_friend_add_manual_fallback_still_reachable` ~893)

**Interfaces:**
- Consumes: Task 6's layout (`#ceremony` lives in `sec-friends`; `openProfileEditor`/`closeEditOverlay` no longer exist). `ICONS.plus`, `el()`.
- Produces: `function buildFriendAdd(panel)` — builds the Share-my-code/Enter-a-code tabs + manual fallback into `panel` and sets `panel.friendaddFocus()`; defined BETWEEN `buildManualCeremony` and `ceremonyUI` (the XSS asset test slices `wireCopyButton`→`ceremonyUI` and asserts no `innerHTML` there — `buildFriendAdd` has none). `#profile-addfriend` topbar button; `#friendadd-overlay` dialog; `openFriendAdd()`/`closeFriendAdd()`.

- [ ] **Step 1: Write the failing asset test + update the two existing ones**

Append to `tests/test_web_assets.py`:

```python
def test_topbar_addfriend_popover():
    # Spec 2026-07-15: a small self-only "+" next to Arrange/cog opens the
    # add-friend flow as a dialog - no trip to Settings to add someone.
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert 'id="profile-addfriend"' in html
    assert 'id="friendadd-overlay"' in html and 'id="friendadd-close"' in html
    assert "buildFriendAdd" in _js_fn_body(js, "openFriendAdd")
    assert "profile-addfriend" in _js_fn_body(js, "renderProfilePage")
    assert "position: fixed" in _css_rule(css, "#friendadd-overlay")
    assert "closeFriendAdd" in js
```

Update `test_friend_add_entry_point_and_tabs_present` (~lines 882–890) — replace the body after the docstring-comment with:

```python
    js = (WEB / "app.js").read_text(encoding="utf-8")
    # ceremonyUI stays the Settings>Friends entry point (bootData calls
    # it); the tab panel itself now comes from buildFriendAdd so the
    # topbar "+" popover hosts the identical flow (spec 2026-07-15).
    ceremony = _js_fn_body(js, "ceremonyUI")
    assert "buildFriendAdd" in ceremony
    panel = _js_fn_body(js, "buildFriendAdd")
    assert "friendadd-tab" in panel
    assert "Share my code" in panel and "Enter a code" in panel
    assert "buildShareTab" in panel and "buildEnterTab" in panel
```

Update `test_friend_add_manual_fallback_still_reachable` (~lines 893–897): change both `_js_fn_body(js, "ceremonyUI")` reads to `_js_fn_body(js, "buildFriendAdd")` (the four-endpoint `buildManualCeremony` asserts below stay).

- [ ] **Step 2: Run to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q -k "friend_add or addfriend"`
Expected: the three tests FAIL.

- [ ] **Step 3: Implement — refactor ceremonyUI**

In `app.js`, split `ceremonyUI` (~line 3236). Directly BEFORE `function ceremonyUI()`, add `buildFriendAdd` containing everything from `const tabs = el("div", "friendadd-tabs");` down to `panel.append(tabs, shareTab, enterTab, fallbackToggle, manualPanel);` — moved verbatim, wrapped as:

```js
// The Share-my-code / Enter-a-code panel, home-agnostic (spec
// 2026-07-15): ceremonyUI hosts it in Settings > Friends behind the
// "Add friend" toggle; openFriendAdd hosts the same panel in the topbar
// "+" dialog. Sets panel.friendaddFocus() for whichever host opens it.
function buildFriendAdd(panel) {
  const tabs = el("div", "friendadd-tabs");
  /* ... the moved code, verbatim, through buildShareTab/buildEnterTab,
     showShare/showEnter, aria-selected wiring, fallbackToggle and
     manualPanel ... */
  panel.append(tabs, shareTab, enterTab, fallbackToggle, manualPanel);
  panel.friendaddFocus = () => activeTab.friendaddFocus();
}
```

and shrink `ceremonyUI` to:

```js
function ceremonyUI() {
  const root = document.getElementById("ceremony");
  root.replaceChildren();
  const toggle = el("button", "btn-accent", "Add friend");
  toggle.innerHTML = ICONS.plus + "<span>Add friend</span>";
  const panel = el("div", "ceremony-panel hidden");
  buildFriendAdd(panel);
  toggle.onclick = () => {
    panel.classList.toggle("hidden");
    if (!panel.classList.contains("hidden")) panel.friendaddFocus();
  };
  root.append(toggle, panel);
}
```

(The old `ceremonyUI` created `toggle`/`panel` first, then the tabs — the only behavior change is `activeTab.friendaddFocus()` reached via `panel.friendaddFocus`.)

- [ ] **Step 4: Implement — markup, wiring, CSS**

(a) `index.html` topbar (~line 116), between the Arrange button and the cog:

```html
        <button class="profile-addfriend hidden" id="profile-addfriend"
          aria-label="Add friend" title="Add friend">+</button>
```

(b) `index.html`, before the `#block-settings` dialog markup:

```html
  <!-- Friend-add popover (spec 2026-07-15): the topbar "+" opens the same
       Share-my-code / Enter-a-code flow Settings > Friends hosts, as a
       dialog - add a friend without leaving the profile. Mirrors
       #block-settings' role/close pattern. -->
  <div class="hidden" id="friendadd-overlay" role="dialog" aria-modal="true" aria-label="Add friend">
    <div class="block-settings-card">
      <button class="closeoverlay" id="friendadd-close" aria-label="Close">&times;</button>
      <h3 class="block-settings-title">Add friend</h3>
      <div id="friendadd-body"></div>
    </div>
  </div>
```

(c) `app.js`, after the `ceremonyUI` function:

```js
// Topbar "+" (spec 2026-07-15): quick add-friend without leaving the
// profile. Rebuilt fresh per open so a prior invite's countdown state
// never leaks into a new session.
function openFriendAdd() {
  const body = document.getElementById("friendadd-body");
  body.replaceChildren();
  const panel = el("div", "ceremony-panel");
  buildFriendAdd(panel);
  body.append(panel);
  document.getElementById("friendadd-overlay").classList.remove("hidden");
  panel.friendaddFocus();
}
function closeFriendAdd() {
  document.getElementById("friendadd-overlay").classList.add("hidden");
}
document.getElementById("profile-addfriend").onclick = openFriendAdd;
document.getElementById("friendadd-close").onclick = closeFriendAdd;
document.getElementById("friendadd-overlay").addEventListener("click", (ev) => {
  if (ev.target.id === "friendadd-overlay") closeFriendAdd();
});
```

(d) `app.js` `renderProfilePage`, next to the cog/arrange toggles (~line 1778):

```js
  document.getElementById("profile-addfriend").classList.toggle("hidden", !p.mine);
```

(e) `app.js` global Escape handler (~line 1074): change its body to `{ if (ev.key === "Escape") { closeBlockSettings(); closeFriendAdd(); } }`.

(f) `style.css`, after `.profile-cog:hover` (~line 387):

```css
.profile-addfriend { border-radius: 50%; width: 34px; height: 34px; padding: 0;
  display: grid; place-items: center; font-size: 20px; line-height: 1;
  color: var(--ink-2); flex: none; transition: .15s; }
.profile-addfriend:hover { color: var(--ink); border-color: var(--ink-2); }
```

and after the `.settings-opt.on` rule (~line 658):

```css
/* Friend-add popover (spec 2026-07-15): same viewport-fixed dialog
   treatment as #block-settings (whole-branch review IMPORTANT #1). */
#friendadd-overlay { position: fixed; inset: 0; z-index: 30; display: grid;
  place-items: center; background: rgba(0,0,0,.45); backdrop-filter: blur(3px); }
#friendadd-overlay.hidden { display: none; }
#friendadd-overlay .block-settings-card { max-width: 420px; width: min(92vw, 420px); }
```

- [ ] **Step 5: Run asset + friend-add suites, then the FULL suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py tests/test_friend_add_api.py -q`
Expected: ALL PASS (including the XSS slice test — `buildFriendAdd` sits inside the `wireCopyButton`→`ceremonyUI` block and contains no `innerHTML`).

Then the whole suite: `.venv\Scripts\python.exe -m pytest -q`
Expected: everything passes with the same skip count as `git stash`-free main; any unrelated failure = stop and report.

- [ ] **Step 6: Commit**

```powershell
git add hearth/web/index.html hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat(profile): topbar + quick-add - the share/enter friend flow opens as a dialog on the profile; panel extracted as buildFriendAdd, shared with Settings > Friends"
```

---

## After all tasks: August's behavioral checklist

Hand this list to August (Claude does not run UI smoke by hand — testing-workflow division):

1. Post to the wall with gaps present — the post fills the first open slot; nothing shifts. Drag-onto-occupied still pushes.
2. Banner: drag the editor preview (and tab to the slider) — crop sticks after save, survives an unrelated name edit, renders the same on a friend's device.
3. Delete a wall post via the gear (outside Arrange and inside Arrange). Journal delete unchanged. Albums: no delete, Ungroup first.
4. Scope tag: bottom-right, small, no ring; gone while arranging.
5. Settings page: cog opens it; collapse a couple of sections, reload — states remembered; Desktop section only in the desktop app; profile right column shows just the journal rail on both own and friends' profiles.
6. Topbar "+": full add-friend round-trip with a real friend from the popover; Esc/backdrop closes it.
