# Kreds Image Lightbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Click/tap a photo in a profile photo block to open it fullscreen and swipe/arrow through that block's photos.

**Architecture:** A self-contained JS-created `#lightbox` overlay mirroring the existing `#story-viewer` (created on open, removed on close). `renderBlock`'s photo `<img>`s get a click opener (gated `!ARRANGING`). Within-block navigation over `p.blobs`, clamped; fit-to-screen, no zoom. Front-end only — blobs already serve decrypted via `/api/post-blob`.

**Tech Stack:** vanilla JS (no bundler/deps), CSS in Kreds tokens; `node --check`; pytest asset tests.

**Spec:** `docs/superpowers/specs/2026-07-07-kreds-image-lightbox-design.md`

## Global Constraints

- Branch: `kreds-image-lightbox` off `main` (already created + checked out — do NOT re-branch).
- Quality over shortcuts. `node --check hearth/web/app.js` clean; full suite green (`timeout 150 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3`); TOR_E2E skip; flakes stay fixed.
- Behavioral verification (click-open, swipe, arrows, Esc/backdrop, clamp, focus return, not-in-Arrange, single-photo-no-arrows) is done by the USER (per the testing-workflow split) — automated tests assert presence/wiring, not pixels. Do NOT run Playwright/manual UI smoke.
- Scope = profile photo blocks only. Excluded: video blocks, Arrange mode (`!ARRANGING` gate), zoom, home-feed photos, banner/avatar, cross-block swipe, any backend/crypto change.
- Honesty guard: no receipts.

---

### Task 1: Lightbox overlay + trigger + CSS

**Files:**
- Modify: `hearth/web/app.js` (`openLightbox` controller; photo-`<img>` click opener in `renderBlock`)
- Modify: `hearth/web/style.css` (`#lightbox` + controls)
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: `p.msg_id`, `p.blobs`, `ARRANGING`, `el()` (signature `el(tag, className, text)`), `/api/post-blob/{msg_id}/{hash}`.
- Produces: `openLightbox(msgId, blobs, index)`.

- [ ] **Step 1: Failing asset test** — append to `tests/test_web_assets.py`:

```python
def test_image_lightbox():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert "openLightbox" in js                       # controller
    assert 'id = "lightbox"' in js or 'ov.id = "lightbox"' in js
    assert "!ARRANGING" in js                          # gated to normal view
    assert "ArrowLeft" in js and "ArrowRight" in js    # keyboard nav
    assert "zoom-in" in js                             # click affordance on photos
    assert "#lightbox" in css and "object-fit: contain" in css
```

- [ ] **Step 2: Run — expect failure.**

- [ ] **Step 3: `openLightbox` controller** — add near `openStoryViewer` (app.js), mirroring its create-on-open / remove-on-close pattern:

```javascript
// Fullscreen photo lightbox: click a photo in a profile photo block to enlarge
// it and swipe/arrow through THAT block's photos (p.blobs). Fit-to-screen, no
// zoom; clamped at the ends; view-only (own + others). Mirrors #story-viewer.
function openLightbox(msgId, blobs, index) {
  if (!blobs || !blobs.length) return;
  let i = Math.max(0, Math.min(index, blobs.length - 1));
  const opener = document.activeElement;              // restore focus on close
  const ov = el("div"); ov.id = "lightbox";
  ov.setAttribute("role", "dialog");
  ov.setAttribute("aria-modal", "true");
  ov.setAttribute("aria-label", "Photo");
  const img = document.createElement("img"); img.id = "lightbox-img"; img.alt = "";
  const prev = el("button", "lb-nav lb-prev", "‹"); prev.type = "button";
  prev.setAttribute("aria-label", "Previous photo");
  const next = el("button", "lb-nav lb-next", "›"); next.type = "button";
  next.setAttribute("aria-label", "Next photo");
  const count = el("div", "lb-count"); count.id = "lightbox-count";
  const x = el("button", "lb-close", "×"); x.type = "button";
  x.setAttribute("aria-label", "Close");
  ov.append(img, prev, next, count, x);

  function render() {
    img.src = "/api/post-blob/" + msgId + "/" + blobs[i];
    count.textContent = (i + 1) + " / " + blobs.length;
    const multi = blobs.length > 1;
    prev.style.display = next.style.display = count.style.display = multi ? "" : "none";
    prev.disabled = i === 0;
    next.disabled = i === blobs.length - 1;
  }
  function go(d) { i = Math.max(0, Math.min(i + d, blobs.length - 1)); render(); }
  function close() {
    document.removeEventListener("keydown", onKey);
    ov.remove(); document.body.classList.remove("lb-open");
    if (opener && opener.focus) opener.focus();
  }
  function onKey(e) {
    if (e.key === "Escape") close();
    else if (e.key === "ArrowLeft") go(-1);
    else if (e.key === "ArrowRight") go(1);
  }
  prev.onclick = () => go(-1);
  next.onclick = () => go(1);
  x.onclick = close;
  ov.addEventListener("click", (e) => { if (e.target === ov) close(); });   // backdrop
  let sx = null;                                                            // touch swipe
  ov.addEventListener("pointerdown", (e) => { sx = e.clientX; });
  ov.addEventListener("pointerup", (e) => {
    if (sx == null) return;
    const dx = e.clientX - sx; sx = null;
    if (Math.abs(dx) > 40) go(dx < 0 ? 1 : -1);
  });
  document.addEventListener("keydown", onKey);
  document.body.append(ov); document.body.classList.add("lb-open");
  render(); x.focus();
}
```

- [ ] **Step 4: Wire the trigger in `renderBlock`.** In the photo branch (`else if (p.blobs && p.blobs.length)`), the current media loop is `for (const h of p.blobs) { ... }`. Change it to index-aware and add the click opener:

```javascript
    const media = el("div", photoGridClass(p.grid || "auto", p.blobs.length));
    p.blobs.forEach((h, idx) => {
      const img = document.createElement("img");
      img.src = "/api/post-blob/" + p.msg_id + "/" + h;
      img.alt = "";
      img.style.cursor = "zoom-in";
      img.onclick = () => { if (!ARRANGING) openLightbox(p.msg_id, p.blobs, idx); };
      media.append(img);
    });
    block.append(media);
```

(In Arrange mode the block's pointerdown opens the settings modal and `!ARRANGING` keeps the lightbox from firing; the video branch is unchanged.)

- [ ] **Step 5: CSS** — append to `hearth/web/style.css`:

```css
#lightbox { position: fixed; inset: 0; z-index: 110; background: rgba(0,0,0,.92);
  display: grid; place-items: center; }
#lightbox-img { max-width: 100%; max-height: 100vh; object-fit: contain; }
.lb-nav { position: absolute; top: 50%; transform: translateY(-50%); border: none;
  background: rgba(0,0,0,.4); color: #fff; font-size: 30px; width: 48px; height: 64px;
  border-radius: 10px; cursor: pointer; }
.lb-prev { left: 12px; } .lb-next { right: 12px; }
.lb-nav:disabled { opacity: .3; cursor: default; }
.lb-close { position: absolute; top: 16px; right: 16px; background: none; border: none;
  color: #fff; font-size: 30px; line-height: 1; cursor: pointer; }
.lb-count { position: absolute; bottom: 20px; left: 0; right: 0; text-align: center;
  color: #fff; font-size: 14px; }
```

- [ ] **Step 6: Run asset tests + `node --check` + full suite. Commit.**

```powershell
git add hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat: image lightbox - click a profile photo to enlarge + swipe/arrow through the block's photos (fit-to-screen, no zoom)"
```

---

### Task 2: Docs

**Files:**
- Modify: `README.md`, `ROADMAP.md`

- [ ] **Step 1: README + ROADMAP** — document the image lightbox (click/tap a profile photo → fullscreen; ←/→, on-screen arrows, and touch swipe move through that block's photos, clamped; Esc/✕/backdrop close; view-only, own + others; not in Arrange, not video; no zoom). Note the deferred follow-ups: zoom/pan, home-feed photo lightbox. Increment on the profile-canvas feature area.

- [ ] **Step 2: Commit**

```powershell
git add README.md ROADMAP.md
git commit -m "docs: image lightbox ship notes (zoom + feed lightbox deferred)"
```

---

## Completion

After Task 2: whole-branch review (superpowers:requesting-code-review) — focus: lightbox is view-only + front-end-only (no backend/crypto touched); gated `!ARRANGING` (never fires in Arrange, where a tap opens the settings modal); within-block clamp (no wrap, single-photo hides arrows); keyboard (arrows/Esc) + swipe + backdrop close all work; the `keydown` listener is removed on close (no leak); focus returns to the opener; video blocks unaffected; no XSS (img.src is a blob path, no innerHTML of user data); no shipped behavior broken (bento tap/drag, grids, feed, DMs). Then superpowers:finishing-a-development-branch — merge to `main`, push. Behavioral verification is the USER's (hand a test checklist on merge). Next: Phase B, or Windows packaging.
