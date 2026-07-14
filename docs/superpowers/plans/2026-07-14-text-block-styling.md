# Text Block Styling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wall text blocks become stylable in place — alignment (both axes), size steps, Sans/Display font, bold/italic, theme-safe color — via a `texts` map on the layout record and a "Text" group in the block-settings modal.

**Architecture:** The photo-grid idiom, seventh verse: a `texts` map (`{msg_id: {h,v,size,font,weight,style,color}}`) on `KIND_PROFILE_LAYOUT`, enum-validated, defaults omitted, carried forward by every layout write including `set_album`'s reconciliation. `profile_view` annotates text blocks with a fully-defaulted `text_style`; the client maps it to flex alignment + classes + a color variable.

**Tech Stack:** Existing record/store/API idioms; vanilla JS/CSS; pytest; the gated smokes.

**Spec:** `docs/superpowers/specs/2026-07-14-text-block-styling-design.md` — read it first; its enums are the contract.

## Global Constraints

- Branch `kreds-quickwins-0.3.10`; no version bump; NO AI trailers; no new dependencies; ASCII-only; `node --check hearth/web/app.js` clean after every app.js edit; `.venv\Scripts\python.exe -m pytest ...`.
- Enums verbatim from the spec: `h` left/center/right; `v` top/middle/bottom; `size` auto/s/m/l/xl; `font` sans/disp; `weight` normal/bold; `style` normal/italic; `color` default/accent/one-of-`ACCENTS`. Defaults (first value of each) are OMITTED from the stored map (`set_block_text` drops them; an all-default style removes the entry — the grids-"auto" pattern).
- `set_block_text` refuses a non-text block (payload has blobs or `media == "video"`) and an album_id (albums have no text) — ValueError → 400.
- Arbitrary hex is REJECTED (structured-options rule); `accent` resolves viewer-side to the AUTHOR's accent.
- Carry-forward: EVERY `make_profile_layout` call site gains `texts=...` — `set_profile_layout`, `set_block_grid`, `set_block_size`, `set_block_pin`, `unpin_block`, `set_block_span`, and `set_album`'s reconciliation publish. A reorder/pin/group must never drop a text style (test pins this).

---

### Task 1: Server — `texts` map end to end

**Files:**
- Modify: `hearth/messages.py` (constants + `make_profile_layout` + `KIND_PROFILE_LAYOUT` validation), `hearth/store.py` (`profile_layout` resolver + empty default), `hearth/node.py` (carry-forward everywhere + `set_block_text` + `profile_view` annotation), `hearth/api.py` (`POST /api/block-text` after `/api/album`)
- Test: `tests/test_text_style.py` (create); append one styled-sync assertion to `tests/test_albums_integration.py` OR a sibling test in that file

**Interfaces:**
- Produces: `TEXT_STYLE_ENUMS` in messages.py — `{"h": ("left","center","right"), "v": ("top","middle","bottom"), "size": ("auto","s","m","l","xl"), "font": ("sans","disp"), "weight": ("normal","bold"), "style": ("normal","italic")}` (color validated separately: `"default"`, `"accent"`, or membership in `ACCENTS`); `node.set_block_text(msg_id, **style)` accepting exactly those keys; `profile_view` text blocks (no blobs, media != video, not album) gain `"text_style"`: a dict with ALL seven keys present, defaults filled. Task 2 renders from `text_style` verbatim.

- [ ] **Step 1: Write the failing test** — `tests/test_text_style.py` (node construction per `tests/test_block_pins.py`; keep tests in that file's style):

```python
"""texts map on the layout record: validation, carry-forward, annotation."""
import pytest

from hearth.messages import ACCENTS
from hearth.node import HearthNode


def _node(tmp_path):
    return HearthNode.create(tmp_path / "n", "Anna", "anna-pc")


def _text_post(n, txt="ord"):
    return n.compose_post(txt, scope="kreds", placement="profile")


def test_set_and_annotate(tmp_path):
    n = _node(tmp_path)
    t = _text_post(n)
    n.set_block_text(t, h="center", v="middle", size="xl", font="disp",
                     weight="bold", style="italic", color=ACCENTS[0])
    view = n.profile_view(n.identity_pub)
    blk = next(p for p in view["wall"] if p["msg_id"] == t)
    assert blk["text_style"] == {"h": "center", "v": "middle", "size": "xl",
                                 "font": "disp", "weight": "bold",
                                 "style": "italic", "color": ACCENTS[0]}


def test_defaults_omitted_and_fully_annotated(tmp_path):
    n = _node(tmp_path)
    t = _text_post(n)
    n.set_block_text(t, h="center")               # one non-default field
    lay = n.store.profile_layout(n.identity_pub)
    assert lay["texts"][t] == {"h": "center"}     # defaults dropped
    n.set_block_text(t, h="left")                 # back to all-default
    lay = n.store.profile_layout(n.identity_pub)
    assert t not in lay["texts"]                  # entry removed entirely
    view = n.profile_view(n.identity_pub)
    blk = next(p for p in view["wall"] if p["msg_id"] == t)
    assert blk["text_style"] == {"h": "left", "v": "top", "size": "auto",
                                 "font": "sans", "weight": "normal",
                                 "style": "normal", "color": "default"}


def test_validation(tmp_path):
    n = _node(tmp_path)
    t = _text_post(n)
    ph = n.compose_post("pic", scope="kreds", placement="profile",
                        photos=[b"\x89PNG fake"])
    with pytest.raises(ValueError):
        n.set_block_text(t, h="diagonal")
    with pytest.raises(ValueError):
        n.set_block_text(t, color="#123456")      # arbitrary hex rejected
    with pytest.raises(ValueError):
        n.set_block_text(ph, h="center")          # not a text block
    with pytest.raises(ValueError):
        n.set_block_text("zz", h="center")
    aid = None
    # album refusal: build a real album, then try to style it
    p1 = n.compose_post("a", scope="kreds", placement="profile",
                        photos=[b"\x89PNG fake"])
    aid = n.set_album([p1])
    with pytest.raises(ValueError):
        n.set_block_text(aid, h="center")


def test_carry_forward_everywhere(tmp_path):
    n = _node(tmp_path)
    t = _text_post(n)
    ph = n.compose_post("pic", scope="kreds", placement="profile",
                        photos=[b"\x89PNG fake"])
    n.set_block_text(t, size="xl")
    n.set_block_pin(t, 0, 0, 2, 1)
    n.set_block_span(ph, 1, 1)
    n.set_block_size(ph, "small")                 # legacy writer
    n.set_profile_layout([t, ph])
    n.unpin_block(t)
    aid = n.set_album([ph])                       # reconciliation publish
    lay = n.store.profile_layout(n.identity_pub)
    assert lay["texts"][t] == {"size": "xl"}      # survived all seven writes
```

(`color="accent"` resolution is Task 2's client concern; the server stores the token. Adapt the photo-post call to the real kwarg as previous tasks did.)

- [ ] **Step 2: RED** — `AttributeError: set_block_text`.
- [ ] **Step 3: Implement.** messages.py: `TEXT_STYLE_ENUMS` constant; `make_profile_layout` gains `texts=None` → payload `"texts": dict(texts or {})`; validation branch after `spans`: dict, cap MAX_LAYOUT, keys hex64, values dicts whose keys ⊆ the seven fields, each enum-checked (`color`: `"default"`/`"accent"`/`in ACCENTS`), reject empty-dict values. store.py: resolver + empty default gain `"texts"`. node.py: every `make_profile_layout` call gains `texts=cur["texts"]` (grep them ALL — including `set_album`'s reconciliation); add:

```python
    def set_block_text(self, msg_id: str, **style) -> str:
        """Style a wall TEXT block in place (spec 2026-07-14): the texts
        map is presentation, not content - same idiom as the retired
        grids map. Defaults are dropped so the map stays minimal; an
        all-default style removes the entry."""
        self._check_block_id(msg_id)
        msg = self.store.get_message(msg_id)
        if msg is None or msg.cert.identity_pub != self.identity_pub:
            raise ValueError("not your block")
        pl = msg.payload
        if pl.get("kind") != KIND_POST or pl.get("placement") != "profile" \
                or pl.get("blobs") or pl.get("media") == "video":
            raise ValueError("text styling applies to text blocks only")
        cleaned = {}
        for k, v in style.items():
            if k == "color":
                if v not in ("default", "accent") and v not in ACCENTS:
                    raise ValueError("bad text color")
                if v != "default":
                    cleaned[k] = v
                continue
            enum = TEXT_STYLE_ENUMS.get(k)
            if enum is None or v not in enum:
                raise ValueError("bad text style")
            if v != enum[0]:                      # enum[0] is the default
                cleaned[k] = v
        cur = self.store.profile_layout(self.identity_pub)
        texts = dict(cur["texts"])
        if cleaned:
            texts[msg_id] = cleaned
        else:
            texts.pop(msg_id, None)
        if len(texts) > MAX_LAYOUT:
            raise ValueError("too many styled blocks")
        return self._publish(make_profile_layout(
            self.device, cur["order"], grids=cur["grids"],
            sizes=cur["sizes"], pins=cur["pins"], spans=cur["spans"],
            texts=texts))
```

(NOTE: `set_block_text` REPLACES the block's whole style each call — the modal always posts the complete current selection, Task 2 honors that.) `profile_view`: annotate text blocks (`not p.get("blobs") and p.get("media") != "video"`, plain posts only — album pseudo-blocks skipped) with `text_style` = defaults overlaid by `texts.get(msg_id, {})`. api.py: `POST /api/block-text` — `_400(lambda: node.set_block_text(body["msg_id"], **{k: v for k, v in body.items() if k != "msg_id"}))`.

- [ ] **Step 4: GREEN** targeted + append the sync assertion (author styles a text block center/xl/accent-token; after `sync_with`, the friend's `profile_view` carries the same `text_style`) to the albums integration file as its own test; full suite once.
- [ ] **Step 5: Commit** — `feat(text): texts map on the layout record - h/v align, size steps, sans/disp, bold/italic, token color (default/accent/ACCENTS; arbitrary hex refused); set_block_text + /api/block-text; carried forward by every layout write incl. album reconciliation`

---

### Task 2: Client — rendering + the modal Text group

**Files:**
- Modify: `hearth/web/app.js` (`renderBlock` text branch; `openBlockSettings` Text group), `hearth/web/style.css`
- Test: `tests/test_web_assets.py` (append); extend `tests/test_ui_smoke_albums.py` (or a sibling smoke test in that file) with the styled-text leg

**Interfaces:**
- Consumes: `p.text_style` (all seven keys, Task 1), `p.accent` on the profile payload (the author's accent — `openProfile`'s `p` is in scope where blocks render; pass what's needed), `postJSON`, `reopenAfterAction`, `ACCENTS` values duplicated client-side as `TEXT_COLORS` (the ten hexes — copy them verbatim from messages.py with a comment naming the source of truth).

- [ ] **Step 1: Failing asset test:**

```python
def test_text_styling_wired():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    rb = _js_fn_body(js, "renderBlock")
    assert "text_style" in rb and "applyTextStyle" in rb
    ats = _js_fn_body(js, "applyTextStyle")
    for needle in ("justify-content", "align-items", "--text-color",
                   "text-font-disp", "text-size-", "text-bold",
                   "text-italic"):
        assert needle in ats or needle in css, needle
    modal = _js_fn_body(js, "openBlockSettings")
    assert '"/api/block-text"' in modal and "TEXT_COLORS" in modal
    assert ".block-text-wrap" in css
```

- [ ] **Step 2: RED.**
- [ ] **Step 3: Implement.**
  - `applyTextStyle(block, wrap, ts, authorAccent)` helper: `wrap` is a new `.block-text-wrap` flex container around `.block-text-body` (`display:flex; height:100%`); map `h`→`justify-content` (flex-start/center/flex-end) + `text-align`, `v`→`align-items`; size `auto` keeps the existing `text-w*` class path, `s/m/l/xl`→`text-size-s/m/l/xl` classes (12.5/15/19/26px, the current scale); `font disp`→`text-font-disp` (`font-family: var(--disp)`); `weight bold`→`text-bold`; `style italic`→`text-italic`; color: `default`→nothing, `accent`→`wrap.style.setProperty("--text-color", authorAccent || "")`, hex→set the var to it; CSS `.block-text-wrap { color: var(--text-color, inherit); }`.
  - renderBlock's text branch wraps the body and calls the helper (author accent: thread the profile's accent to renderBlock the same way `p.mine` flows — check how renderWall gets `p`; the profile object's `accent` is available at the renderWall call site, pass it through or read a module-level CURRENT_PROFILE_ACCENT set by renderProfilePage — pick the existing idiom, state which).
  - Modal: for text blocks (`p.text_style` present), a "Text" group — two segmented rows (H: Left/Center/Right; V: Top/Middle/Bottom), size chips (Auto/S/M/L/XL), Sans/Display toggle, B/I toggle buttons, color row (Default, Accent, ten `TEXT_COLORS` swatch buttons with `aria-label`s). Selection state initialized from `p.text_style`; every change POSTs the COMPLETE current selection to `/api/block-text` (`{msg_id, h, v, size, font, weight, style, color}` — the server drops defaults) then `reopenAfterAction` with the control's `data-sel`. All real `<button>`s.
  - CSS: the classes above + swatch styling (reuse the profile editor's accent-swatch look if one exists — check; else small round buttons).
- [ ] **Step 4:** `node --check`; asset suite green (update any pinned-literal stragglers with the usual note).
- [ ] **Step 5: Smoke leg** (extend the albums smoke file as its own test function, same gating): post a text-only wall block → Arrange → gear → set Center/Middle/XL/Display/Bold/first-swatch → assert the block's computed `justify-content === "center"`, `align-items === "center"`, font-size 26px, `font-family` contains "Bricolage", `font-weight` ≥ 600, `color` matches the swatch hex (rgb-compare); reload → persists; friend leg optional but preferred (styled view after sync). 3 consecutive greens; suite once.
- [ ] **Step 6: Commit** — `feat(text): wall text blocks style in place - both-axis alignment, size steps, Display font, bold/italic, theme-safe token colors; Text group in the block settings modal, complete-selection posts`
- [ ] **Step 7: ROADMAP** — one increment sentence appended to the collage paragraph (styling shipped, token-color honesty: monochrome = theme ink, arbitrary hex refused per structured-options rule) + commit with the smoke:
`test(text): styled-text smoke leg + roadmap increment`

---

## Self-review

1. **Spec coverage:** every enum/behavior in the spec maps to Task 1 (record/validation/carry-forward/annotation/API) or Task 2 (rendering/modal/CSS/smoke); dark-mode monochrome → `default` = theme ink (no override); accent = author-resolved (Task 2 threading note); non-text/album refusal → Task 1 validation + test.
2. **Placeholders:** two read-the-idiom notes (photo kwarg; accent-threading idiom choice) point at named existing code.
3. **Type consistency:** `text_style` seven keys identical in Task 1 annotation, Task 2 helper/modal/smoke; endpoint `/api/block-text` verbatim; `TEXT_COLORS` = `ACCENTS` duplication is commented as such.
