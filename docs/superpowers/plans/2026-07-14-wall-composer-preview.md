# Wall Composer Preview (Collage Slice B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The wall composer previews the post before it exists — attached media renders as a live preview card (photo / stacked deck / video first-frame) sized by a row of size chips at true canvas proportions — and the dead `Auto` layout dropdown disappears along with the last client caller of `/api/block-grid`.

**Architecture:** Front-end only. `profilePostComposer()` (hearth/web/app.js) is rewritten in place: a `.compose-preview` card driven by object URLs, a `.size-chips` row (1x1 / 2x2 / 4x2 / 4x3, default 2x2 for media), and the submit path seeds the chosen size via the existing `POST /api/block-span` right after `/api/post` — the block lands in the Unplaced tray at that size. No server, store, or protocol change.

**Tech Stack:** Vanilla JS (object URLs, no library), CSS, pytest asset tests, Playwright gated smoke.

**Spec:** `docs/superpowers/specs/2026-07-13-wall-collage-redesign-design.md` §4 (Composer). Slice A (pins, tray, `/api/block-span`) is complete on the branch.

## Global Constraints

- Branch: `kreds-quickwins-0.3.10` (the parked bundle branch). No version bump.
- After every app.js edit: `node --check hearth/web/app.js` clean.
- Python: `.venv\Scripts\python.exe -m pytest ...`; ASCII-only output (cp1252 console); chip labels are ASCII `1x1` etc.
- NO AI attribution trailers on commits. No new dependencies.
- Spec §4 exacts: chips `1x1 / 2x2 / 4x2 / 4x3`; default `2x2` for media, text-only posts show no chips and send no seed (server default 4x1 applies); video previews via local object URL (the server gate still transcodes/rejects on post — the preview must not imply otherwise); the dropdown, the five layouts, and the compose-time grid-seed call are REMOVED.
- Object URLs must be revoked (`URL.revokeObjectURL`) whenever replaced or cleared — a media-heavy session must not leak blobs.
- The keeps buttons (Inner/Kreds), composer note, and text input behavior stay byte-identical.
- After this slice, `/api/block-grid` has ZERO client callers (Slice A removed the modal's; this removes the composer's). The server endpoint stays (wire compat) — do not touch api.py.

---

### Task 1: Composer rewrite — preview card, size chips, dropdown removal, span seed

**Files:**
- Modify: `hearth/web/app.js:1621-1720` (`profilePostComposer`, full-body rewrite)
- Modify: `hearth/web/style.css` (delete `.layout-pick` rule; add preview/chips rules near the `.profile-composer` rule)
- Test: `tests/test_web_assets.py` (append)

**Interfaces:**
- Consumes: `el()`, `SCOPE_ICON`, `STATE`, `openProfile`, `--cell` (set by `measureWallCell` whenever the profile page renders — the composer only exists on that page), `POST /api/block-span` `{msg_id, w, h}` (Slice A).
- Produces: `.compose-preview` card, `.preview-deck` + `.deck-count` (the stacked-photos affordance Slice C's wall decks will echo), `.size-chips` buttons with `data-span="WxH"` and `aria-pressed`. Task 2's smoke drives these selectors verbatim.

- [ ] **Step 1: Write the failing web-asset test**

Append to `tests/test_web_assets.py`:

```python
def test_composer_preview_wired():
    # Collage Slice B: the wall composer previews attached media (photo /
    # stacked deck / video first-frame) sized by chips at true canvas
    # proportions; the dead Auto dropdown and the last /api/block-grid
    # client caller are gone. Live behavior pinned by
    # tests/test_ui_smoke_composer.py (UI_E2E=1).
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    body = _js_fn_body(js, "profilePostComposer")
    for needle in ("compose-preview", "size-chips", "createObjectURL",
                   "revokeObjectURL", "/api/block-span", "preview-deck",
                   "deck-count", "aria-pressed"):
        assert needle in body, needle
    assert '"2x2"' in body                     # media default chip
    assert "layout-pick" not in js             # dropdown fully retired
    assert "Masonry" not in js and "cols3" not in js
    assert "/api/block-grid" not in js         # zero client callers left
    _css_rule(css, ".compose-preview")
    assert ".preview-deck" in css and ".deck-count" in css
    assert "layout-pick" not in css
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py::test_composer_preview_wired -v`
Expected: FAIL on `compose-preview`.

- [ ] **Step 3: Rewrite `profilePostComposer`**

Replace the function body (keep the signature and the parts noted). The text input, keeps buttons, and composer note (lines 1622-1645) stay EXACTLY as they are. Everything from the `photoLabel` declaration through the end of `form.onsubmit` is replaced with:

```js
  const photoLabel = el("label", "keep");
  photoLabel.textContent = "Photo";
  const photoInput = document.createElement("input");
  photoInput.type = "file"; photoInput.accept = "image/*"; photoInput.multiple = true;
  photoInput.className = "visually-hidden";   // keyboard-reachable (not display:none)
  photoLabel.append(photoInput);
  bar.append(photoLabel);
  const videoLabel = el("label", "keep");
  videoLabel.textContent = "Video";
  const videoInput = document.createElement("input");
  videoInput.type = "file"; videoInput.accept = "video/*";
  videoInput.className = "visually-hidden";
  videoLabel.append(videoInput);
  bar.append(videoLabel);

  // -- live preview (spec 2026-07-13 section 4): the post as it will
  // render - one photo big, several as a stacked deck (the same
  // affordance Slice C's wall decks use), video as its first frame via a
  // local object URL. Honest boundary: the video preview is the RAW
  // file's frame - the server gate still transcodes and can still reject
  // on post; previewing is not acceptance.
  const preview = el("div", "compose-preview");
  preview.hidden = true;
  form.insertBefore(preview, bar);

  // -- size chips: the block's starting w x h, previewed at true canvas
  // proportions via --cell (measureWallCell keeps it fresh on the
  // profile page, where this composer exclusively lives). Media defaults
  // to 2x2; text-only posts show no chips and send no seed (the server's
  // 4x1 text default applies).
  const chips = el("div", "size-chips");
  chips.setAttribute("role", "group");
  chips.setAttribute("aria-label", "Block size");
  chips.hidden = true;
  let span = {w: 2, h: 2};
  const chipBtns = [];
  for (const [w, h] of [[1, 1], [2, 2], [4, 2], [4, 3]]) {
    const c = el("button", "size-chip", w + "x" + h);
    c.type = "button";
    c.dataset.span = w + "x" + h;
    c.setAttribute("aria-pressed", String(w === 2 && h === 2));
    if (w === 2 && h === 2) c.classList.add("active");   // "2x2" default
    c.onclick = () => {
      span = {w, h};
      for (const x of chipBtns) {
        x.classList.toggle("active", x === c);
        x.setAttribute("aria-pressed", String(x === c));
      }
      sizePreview();
    };
    chipBtns.push(c);
    chips.append(c);
  }
  form.insertBefore(chips, bar);

  let objectUrls = [];
  const dropUrls = () => {
    for (const u of objectUrls) URL.revokeObjectURL(u);
    objectUrls = [];
  };
  const sizePreview = () => {
    // True proportions: the same cell math the canvas renders with.
    preview.style.width = "calc(var(--cell, 120px) * " + span.w
      + " + " + (12 * (span.w - 1)) + "px)";
    preview.style.height = "calc(var(--cell, 120px) * " + span.h
      + " + " + (12 * (span.h - 1)) + "px)";
  };
  const clearPreview = () => {
    dropUrls();
    preview.replaceChildren();
    preview.hidden = true;
    chips.hidden = true;
  };
  const showPreview = () => {
    dropUrls();
    preview.replaceChildren();
    const files = [...photoInput.files];
    const videoFile = videoInput.files[0];
    if (videoFile) {
      const v = document.createElement("video");
      v.muted = true; v.playsInline = true; v.preload = "metadata";
      const u = URL.createObjectURL(videoFile);
      objectUrls.push(u);
      v.src = u;
      preview.append(v, el("div", "preview-note",
        videoFile.name + " - will be trimmed to the story rules on post"));
    } else if (files.length) {
      const wrap = el("div", files.length > 1 ? "preview-deck" : "preview-photo");
      const img = document.createElement("img");
      const u = URL.createObjectURL(files[0]);
      objectUrls.push(u);
      img.src = u; img.alt = "";
      wrap.append(img);
      if (files.length > 1)
        wrap.append(el("span", "deck-count", String(files.length)));
      preview.append(wrap);
    } else { clearPreview(); return; }
    preview.hidden = false;
    chips.hidden = false;
    sizePreview();
  };
  photoInput.onchange = () => {
    if (photoInput.files.length) videoInput.value = "";   // one medium, photos win
    showPreview();
  };
  videoInput.onchange = () => {
    if (videoInput.files.length) photoInput.value = "";   // one medium, video wins
    showPreview();
  };

  const btn = el("button", "postbtn", "Post to profile"); btn.type = "submit";
  bar.append(btn);
  form.append(bar);

  form.onsubmit = async (ev) => {
    ev.preventDefault();
    const fd = new FormData();
    fd.append("text", input.value);
    fd.append("scope", scope);
    fd.append("placement", "profile");
    const videoFile = videoInput.files[0];
    const hasMedia = !!videoFile || photoInput.files.length > 0;
    if (videoFile) fd.append("video", videoFile);
    else for (const f of photoInput.files) fd.append("photos", f);
    const r = await fetch("/api/post", {method: "POST", body: fd});
    if (!r.ok) { alert("Post failed: " + await r.text()); return; }
    const { msg_id } = await r.json();
    // Seed the chosen size so the block waits in the Unplaced tray at the
    // proportions the preview showed. Text-only posts skip the seed - the
    // server's 4x1 text default already matches what was previewed (none).
    if (hasMedia) {
      const sr = await fetch("/api/block-span", {method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({msg_id, w: span.w, h: span.h})});
      if (!sr.ok) alert("Posted, but couldn't set the size: " + await sr.text());
    }
    input.value = "";
    photoInput.value = "";
    videoInput.value = "";
    clearPreview();
    span = {w: 2, h: 2};
    for (const x of chipBtns) {
      const isDefault = x.dataset.span === "2x2";
      x.classList.toggle("active", isDefault);
      x.setAttribute("aria-pressed", String(isDefault));
    }
    openProfile(STATE.identity_pub);                // re-render the wall
  };
  return form;
```

Deleted with this rewrite: `gridSelect` and its options loop, the `videoCue` span (the preview supersedes it), the `/api/block-grid` seed call.

- [ ] **Step 4: style.css**

Delete the `.layout-pick { margin-top: 6px; }` rule (last consumer gone). Near the `.profile-composer` rule, add:

```css
/* Composer live preview (Slice B): the post at its chosen w x h, using
   the canvas's own --cell so proportions are true. The deck is the same
   stacked-photos affordance Slice C's wall decks render. */
.compose-preview { margin: 10px 0 4px; border-radius: 12px; overflow: visible;
  position: relative; max-width: 100%; }
.compose-preview img, .compose-preview video { width: 100%; height: 100%;
  object-fit: cover; border-radius: 12px; display: block; }
.preview-photo, .preview-deck { width: 100%; height: 100%; position: relative; }
.preview-deck::before, .preview-deck::after { content: ""; position: absolute;
  inset: 0; border-radius: 12px; background: var(--line-2);
  border: 1px solid var(--line); z-index: -1; }
.preview-deck::before { transform: rotate(2.5deg) translate(4px, 2px); }
.preview-deck::after { transform: rotate(-2deg) translate(-3px, 4px); }
.deck-count { position: absolute; top: 8px; right: 8px; min-width: 22px;
  height: 22px; padding: 0 6px; border-radius: 99px; background: var(--ink);
  color: var(--paper); font-size: 12px; font-weight: 650; display: grid;
  place-items: center; }
.preview-note { font-size: 11.5px; color: var(--ink-2); margin-top: 6px; }
.size-chips { display: flex; gap: 6px; margin: 8px 0 2px; }
.size-chip { border: 1px solid var(--line-2); background: transparent;
  color: var(--ink-2); font-size: 12px; font-weight: 550; padding: 4px 10px;
  border-radius: 99px; transition: .15s; }
.size-chip:hover { color: var(--ink); border-color: var(--ink-2); }
.size-chip.active { color: var(--me); border-color: var(--me); }
```

- [ ] **Step 5: Verify**

Run: `node --check hearth/web/app.js` → clean.
Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -v` → all pass. Older asset tests asserting the dropdown/`layout-pick`/`gridSelect` get updated in place with the retirement note ("retired by collage Slice B, spec 2026-07-13 §4"); anything unrelated failing is a real break — stop.

- [ ] **Step 6: Commit**

```bash
git add hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat(collage): composer previews the post before it exists - photo/deck/video preview card at true --cell proportions, size chips (2x2 media default) seed /api/block-span into the tray; the Auto dropdown and the last /api/block-grid client caller are gone"
```

---

### Task 2: Live smoke + ROADMAP increment + full suite

**Files:**
- Create: `tests/test_ui_smoke_composer.py`
- Modify: `ROADMAP.md` (feature-12 collage increment paragraph — one added sentence block)

**Interfaces:**
- Consumes: Task 1's selectors verbatim; the `LiveNode` harness from `tests/test_ui_smoke_seen_badge.py` (import); a REAL valid PNG for uploads — the image gate transcodes via Pillow, so generate one with Pillow in-test (Pillow is a runtime dependency already), e.g. `Image.new("RGB", (64, 64), c).save(path, "PNG")`.

- [ ] **Step 1: Write the smoke**

Create `tests/test_ui_smoke_composer.py`:

```python
"""UI_E2E=1-gated live smoke for the composer preview (collage Slice B):
attach photos -> deck preview + count badge + chips; chip changes the
seeded span; text-only posts seed nothing; the dropdown is gone.
Reuses the LiveNode harness (import, not copy)."""
import os

import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("UI_E2E") != "1",
    reason="live browser smoke; set UI_E2E=1 (needs playwright chromium)")

from tests.test_ui_smoke_seen_badge import LiveNode


def _pngs(tmp_path, n):
    from PIL import Image
    paths = []
    for i in range(n):
        p = tmp_path / f"pic{i}.png"
        Image.new("RGB", (64, 64), (200, 30 * i, 60)).save(p, "PNG")
        paths.append(str(p))
    return paths


def test_composer_preview_and_span_seed(tmp_path):
    from playwright.sync_api import sync_playwright

    a = LiveNode(tmp_path / "a", "Anna", "anna-pc")
    try:
        a.start()
        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            page = browser.new_page(viewport={"width": 1280, "height": 900})
            errors = []
            page.on("pageerror", lambda e: errors.append(str(e)))
            page.goto(f"http://127.0.0.1:{a.http_port}/")
            page.wait_for_selector(".fchip")
            page.click('.navlinks button[data-view="me"]')
            page.wait_for_selector(".profile-composer")

            # no dropdown anywhere, ever
            assert page.locator(".profile-composer select").count() == 0

            # 3 photos -> deck preview with count badge, chips visible,
            # 2x2 active by default
            page.set_input_files(
                '.profile-composer input[accept="image/*"]',
                _pngs(tmp_path, 3))
            page.wait_for_selector(".preview-deck")
            assert page.locator(".deck-count").inner_text() == "3"
            assert page.locator(
                '.size-chip.active[data-span="2x2"]').count() == 1

            # pick 1x1, preview shrinks to ~one cell
            page.click('.size-chip[data-span="1x1"]')
            cell = page.evaluate(
                "parseFloat(getComputedStyle(document.documentElement)"
                ".getPropertyValue('--cell'))")
            w = page.locator(".compose-preview").bounding_box()["width"]
            assert abs(w - cell) < 8, f"preview {w} vs cell {cell}"

            # post -> span seeded 1x1, block lands unpinned
            page.click(".postbtn")
            page.wait_for_selector("#profile-wall-flow .block, #profile-tray .block",
                                   timeout=8000)
            lay = a.node.store.profile_layout(a.node.identity_pub)
            assert list(lay["spans"].values()) == [{"w": 1, "h": 1}]
            assert lay["pins"] == {}

            # text-only post: no chips, no extra span entries
            assert page.locator(".size-chips").is_hidden()
            page.fill(".profile-composer input[type=text]", "bare tekst")
            page.click(".postbtn")
            page.wait_for_timeout(800)
            lay = a.node.store.profile_layout(a.node.identity_pub)
            assert len(lay["spans"]) == 1        # still only the photo post

            assert not errors, f"console pageerrors: {errors}"
            browser.close()
    finally:
        a.stop()
```

(Selector adjustments to real DOM are fine; assertions are the contract. On timeout, diagnose the feature first.)

- [ ] **Step 2: Run it live**

Run (PowerShell): `$env:UI_E2E = "1"; .venv\Scripts\python.exe -m pytest tests/test_ui_smoke_composer.py -v -s; Remove-Item Env:UI_E2E`
Expected: PASS, 3 consecutive runs. Gate check without the env var: 1 skipped.

- [ ] **Step 3: Full suite twice**

Run: `.venv\Scripts\python.exe -m pytest -q` (twice)
Expected: all pass + 4 env-gated skips (TOR_E2E + three UI_E2E smokes), consistent both runs.

- [ ] **Step 4: ROADMAP**

In the feature-12 collage Slice A increment paragraph's end (before its final "Slices B..." sentence — rewrite that closing sentence), record:

```
**Increment - collage Slice B (composer preview):** attaching media transforms the wall composer - a live preview card shows the post as it will render (one photo big, several as a stacked deck with a count badge - the same affordance Slice C's wall decks adopt, video as its raw first frame with an honest will-be-transcoded note), sized by 1x1/2x2/4x2/4x3 chips at true --cell proportions (2x2 media default); posting seeds the chosen size via /api/block-span so the block waits in the Unplaced tray exactly as previewed; text-only posts show no chips and seed nothing. The Auto dropdown, its five dead layouts, and the last /api/block-grid client caller are gone (the endpoint stays for wire compat). Slice C (albums: swipeable growable decks) completes the redesign - spec 2026-07-13.
```

- [ ] **Step 5: Commit**

```bash
git add tests/test_ui_smoke_composer.py ROADMAP.md
git commit -m "test(collage): live smoke for the composer preview (deck badge, chip-sized preview, span seed, no dropdown); roadmap Slice B increment"
```

---

## Self-review (done at write time)

1. **Spec coverage (§4):** preview card photo/deck/video → Task 1 Step 3; size chips + true proportions + 2x2 media default → Task 1 (chips + `sizePreview`); tray landing at chosen size → span seed on submit; dropdown/layouts/grid-seed removal → Task 1 (asserted zero `/api/block-grid` callers); honest video-preview boundary → `preview-note` copy + comment; text-only no-chips/no-seed → `hasMedia` gate + smoke assertion.
2. **Placeholder scan:** none — full code everywhere; the smoke's "selector adjustments fine" note points at real DOM discovery, not unwritten design.
3. **Type consistency:** `span {w,h}` consistent between chips, `sizePreview`, and the `/api/block-span` body; selectors (`.compose-preview`, `.preview-deck`, `.deck-count`, `.size-chip[data-span]`) identical across Task 1 code, Task 1 asset test, and Task 2 smoke.
