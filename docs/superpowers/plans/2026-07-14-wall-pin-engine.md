# Wall Pin Engine (Collage Slice A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Wall becomes a free-pin collage: a 4-column canvas of square-ish cells where every block holds explicit `{x, y, w, h}` coordinates, new posts wait unplaced, holes are honest, and Arrange mode gains drag-to-pin, corner resize, and a full keyboard path.

**Architecture:** The mutable `profile_layout` record gains two maps — `pins` (placed geometry) and `spans` (size of unplaced blocks); the old `order`/`grids`/`sizes` maps are carried on the wire but no longer shape rendering. `profile_view` annotates every wall block with `pin`/`span` (legacy `sizes` mapped to default spans). The client renders pinned blocks into a native CSS grid at explicit coordinates (`--cell` measured in JS, since CSS cannot express "row height = column width" for spanning rows), unplaced blocks newest-first below (visitors) or in a tray (owner, Arrange mode). Every arrange action POSTs immediately; "Done" just exits.

**Tech Stack:** Python (FastAPI node, existing patterns), vanilla JS + CSS grid (no library), pytest, Playwright (gated smoke).

**Spec:** `docs/superpowers/specs/2026-07-13-wall-collage-redesign-design.md` — Slice A section. Read it first.

## Global Constraints

- Branch: work lands on `kreds-quickwins-0.3.10` (the parked bundle branch). No version bump in this slice — the bundle is versioned at release.
- Python: `.venv\Scripts\python.exe -m pytest ...`; ASCII-only output (cp1252 console).
- After every app.js edit: `node --check hearth/web/app.js` clean.
- NO AI attribution trailers on commits. No new dependencies.
- Geometry bounds (spec §1): `1 <= w <= 4`, `1 <= h <= 8` (`MAX_BLOCK_H`), `0 <= x`, `x + w <= 4` (`WALL_COLS`), `0 <= y <= MAX_LAYOUT`.
- No wire-protocol bump; `validate_payload` stays shape-only (no cross-pin overlap check server-side — the client prevents overlap; CSS tolerates garbage-in by stacking).
- Metadata honesty comment required where pins are read client-side: pins are plaintext geometry over opaque ids — same existence-disclosure class as the order list.
- Legacy default spans (spec §2): small→1×1, wide→2×2, full→4×2 for media / 4×1 for text-only.
- Transitional states inside the bundle are fine: the composer's grid dropdown stays until Slice B (its `grids` writes become render-inert after Task 5); multi-photo blocks render as a cropped 2-col gallery until Slice C's decks.

---

### Task 1: `pins`/`spans` on the layout record (messages.py)

**Files:**
- Modify: `hearth/messages.py` — constants (~line 18), `make_profile_layout` (~line 129), `validate_payload`'s `KIND_PROFILE_LAYOUT` branch (~line 272)
- Test: `tests/test_messages_pins.py` (create)

**Interfaces:**
- Produces: `WALL_COLS = 4`, `MAX_BLOCK_H = 8` constants; `make_profile_layout(device, order, grids=None, sizes=None, pins=None, spans=None, now=None)` (extends the existing signature — check it in the file and keep existing params exactly as they are, adding `pins`/`spans` keyword args); payload gains `"pins": dict(pins or {})`, `"spans": dict(spans or {})`. Validation: pins values are `{x, y, w, h}` ints within Global Constraints bounds; spans values are `{w, h}`. Task 2's node methods and store resolver rely on these exact field names.

- [ ] **Step 1: Write the failing test**

Create `tests/test_messages_pins.py`:

```python
"""Validation for the collage pins/spans maps on KIND_PROFILE_LAYOUT
(spec 2026-07-13 wall collage redesign, Slice A)."""
from hearth.identity import DeviceKeys
from hearth.messages import make_profile_layout, validate_payload

MID = "ab" * 32   # a well-formed 64-hex msg_id


def _mk(**kw):
    dev = DeviceKeys.generate("Anna", "anna-pc")
    return make_profile_layout(dev, [], **kw)


def _valid(msg):
    ok, why = validate_payload(msg.payload)
    return ok, why


def test_pins_and_spans_roundtrip_valid():
    m = _mk(pins={MID: {"x": 0, "y": 2, "w": 4, "h": 3}},
            spans={"cd" * 32: {"w": 2, "h": 2}})
    ok, why = _valid(m)
    assert ok, why
    assert m.payload["pins"][MID] == {"x": 0, "y": 2, "w": 4, "h": 3}
    assert m.payload["spans"]["cd" * 32] == {"w": 2, "h": 2}


def test_layout_without_pins_still_valid():
    # Backward compat: every existing record has no pins/spans fields.
    ok, why = _valid(_mk())
    assert ok, why


def test_pin_out_of_bounds_rejected():
    for bad in (
        {"x": 3, "y": 0, "w": 2, "h": 1},    # x+w > 4
        {"x": -1, "y": 0, "w": 1, "h": 1},   # x < 0
        {"x": 0, "y": -1, "w": 1, "h": 1},   # y < 0
        {"x": 0, "y": 0, "w": 0, "h": 1},    # w < 1
        {"x": 0, "y": 0, "w": 5, "h": 1},    # w > 4
        {"x": 0, "y": 0, "w": 1, "h": 9},    # h > MAX_BLOCK_H
        {"x": 0, "y": 0, "w": 1},            # missing h
        {"x": 0, "y": 0, "w": 1, "h": True}, # bool is not a size
        {"x": 0.5, "y": 0, "w": 1, "h": 1},  # float is not a cell
    ):
        m = _mk(pins={MID: bad})
        ok, _ = _valid(m)
        assert not ok, f"pin {bad} must be rejected"


def test_span_bad_shapes_rejected():
    for bad in ({"w": 5, "h": 1}, {"w": 1}, {"w": 1, "h": 0},
                {"x": 0, "y": 0, "w": 1, "h": 1}):   # spans carry NO x/y
        m = _mk(spans={MID: bad})
        ok, _ = _valid(m)
        assert not ok, f"span {bad} must be rejected"


def test_pin_key_must_be_hex64():
    m = _mk(pins={"zz": {"x": 0, "y": 0, "w": 1, "h": 1}})
    ok, _ = _valid(m)
    assert not ok
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_messages_pins.py -v`
Expected: FAIL — `make_profile_layout() got an unexpected keyword argument 'pins'`

- [ ] **Step 3: Implement**

In `hearth/messages.py`:

(a) Next to `MAX_LAYOUT`/`SIZE_LAYOUTS` (~line 18), add:

```python
WALL_COLS = 4      # collage canvas width in cells (spec 2026-07-13)
MAX_BLOCK_H = 8    # tallest block in row-units
```

(b) Extend `make_profile_layout` — add `pins=None, spans=None` keyword args (before `now` if present, matching the existing signature style) and two payload fields alongside `grids`/`sizes`:

```python
        "pins": dict(pins or {}), "spans": dict(spans or {}),
```

(c) In `validate_payload`'s `KIND_PROFILE_LAYOUT` branch, after the `sizes` loop and before `return True, "ok"`:

```python
        # Collage geometry (Slice A). Shape-only, like every other record
        # check here: overlapping pins are the CLIENT's job to prevent -
        # a hostile record just renders as stacked blocks, no crash.
        def _ok_geom(v, need_xy):
            keys = {"x", "y", "w", "h"} if need_xy else {"w", "h"}
            if not isinstance(v, dict) or set(v) != keys:
                return False
            if not all(isinstance(v[k], int) and not isinstance(v[k], bool)
                       for k in keys):
                return False
            if not (1 <= v["w"] <= WALL_COLS and 1 <= v["h"] <= MAX_BLOCK_H):
                return False
            if need_xy and not (0 <= v["x"] and v["x"] + v["w"] <= WALL_COLS
                                and 0 <= v["y"] <= MAX_LAYOUT):
                return False
            return True

        pins = p.get("pins", {})
        if not isinstance(pins, dict) or len(pins) > MAX_LAYOUT:
            return False, "bad layout pins"
        for k, v in pins.items():
            if not _is_hex64(k) or not _ok_geom(v, True):
                return False, "bad layout pin"
        spans = p.get("spans", {})
        if not isinstance(spans, dict) or len(spans) > MAX_LAYOUT:
            return False, "bad layout spans"
        for k, v in spans.items():
            if not _is_hex64(k) or not _ok_geom(v, False):
                return False, "bad layout span"
```

- [ ] **Step 4: Run tests**

Run: `.venv\Scripts\python.exe -m pytest tests/test_messages_pins.py tests/test_messages.py -v`
Expected: all PASS (existing layout tests must not break — pins/spans are additive with `{}` defaults).

- [ ] **Step 5: Commit**

```bash
git add hearth/messages.py tests/test_messages_pins.py
git commit -m "feat(collage): profile_layout record carries pins {x,y,w,h} and spans {w,h} - shape-validated additive maps, no protocol change; WALL_COLS=4, MAX_BLOCK_H=8"
```

---

### Task 2: Store resolver + node methods + profile_view annotation

**Files:**
- Modify: `hearth/store.py:444-459` (`profile_layout` resolver)
- Modify: `hearth/node.py:603-650` (carry-forward in `set_profile_layout`/`set_block_grid`/`set_block_size`; new methods after them) and `hearth/node.py:652-688` (`profile_view`)
- Test: `tests/test_block_pins.py` (create)

**Interfaces:**
- Consumes: Task 1's `make_profile_layout(..., pins=, spans=)`, `WALL_COLS`, `MAX_BLOCK_H`.
- Produces: `store.profile_layout(...)` dict gains `"pins"` and `"spans"` keys (empty dicts when absent). `node.set_block_pin(msg_id, x, y, w, h)`, `node.unpin_block(msg_id)`, `node.set_block_span(msg_id, w, h)` — all raise `ValueError` on bad input (400 at the API), all republish carrying every other map forward. `profile_view` wall entries gain `"pin"` (`{x,y,w,h}` or `None`) and `"span"` (`{w,h}`, always present), and the wall list is plain newest-first (the `order` map no longer shapes it). Tasks 3-7 rely on these names exactly.

- [ ] **Step 1: Write the failing test**

Create `tests/test_block_pins.py`:

```python
"""Node-level pin/span/unpin + profile_view annotation (collage Slice A)."""
import pytest

from hearth.node import HearthNode


def _node(tmp_path):
    return HearthNode.create(tmp_path / "n", "Anna", "anna-pc")


def test_pin_roundtrip_and_carry_forward(tmp_path):
    n = _node(tmp_path)
    a = n.compose_post("first", scope="kreds", placement="profile")
    b = n.compose_post("second", scope="kreds", placement="profile")
    n.set_block_pin(a, 0, 0, 2, 2)
    n.set_block_span(b, 4, 1)
    lay = n.store.profile_layout(n.identity_pub)
    assert lay["pins"][a] == {"x": 0, "y": 0, "w": 2, "h": 2}
    assert lay["spans"][b] == {"w": 4, "h": 1}
    # a further unrelated layout write must not drop either map
    n.set_block_grid(b, "cols2")
    lay = n.store.profile_layout(n.identity_pub)
    assert lay["pins"][a] == {"x": 0, "y": 0, "w": 2, "h": 2}
    assert lay["spans"][b] == {"w": 4, "h": 1}


def test_pin_moves_geometry_out_of_spans(tmp_path):
    n = _node(tmp_path)
    a = n.compose_post("x", scope="kreds", placement="profile")
    n.set_block_span(a, 2, 2)
    n.set_block_pin(a, 1, 3, 2, 2)
    lay = n.store.profile_layout(n.identity_pub)
    assert a in lay["pins"] and a not in lay["spans"]


def test_unpin_keeps_size_as_span(tmp_path):
    n = _node(tmp_path)
    a = n.compose_post("x", scope="kreds", placement="profile")
    n.set_block_pin(a, 0, 0, 4, 3)
    n.unpin_block(a)
    lay = n.store.profile_layout(n.identity_pub)
    assert a not in lay["pins"]
    assert lay["spans"][a] == {"w": 4, "h": 3}


def test_bad_pin_rejected(tmp_path):
    n = _node(tmp_path)
    a = n.compose_post("x", scope="kreds", placement="profile")
    for bad in ((3, 0, 2, 1), (-1, 0, 1, 1), (0, -1, 1, 1), (0, 0, 0, 1),
                (0, 0, 1, 9)):
        with pytest.raises(ValueError):
            n.set_block_pin(a, *bad)
    with pytest.raises(ValueError):
        n.set_block_span(a, 5, 1)
    with pytest.raises(ValueError):
        n.set_block_pin("zz", 0, 0, 1, 1)


def test_span_on_pinned_block_rejected(tmp_path):
    # A pinned block's geometry lives in its pin; the modal routes pinned
    # resizes through set_block_pin. Refusing here keeps one source of truth.
    n = _node(tmp_path)
    a = n.compose_post("x", scope="kreds", placement="profile")
    n.set_block_pin(a, 0, 0, 1, 1)
    with pytest.raises(ValueError):
        n.set_block_span(a, 2, 2)


def test_profile_view_annotates_pin_and_span(tmp_path):
    n = _node(tmp_path)
    a = n.compose_post("pinned", scope="kreds", placement="profile")
    b = n.compose_post("spanned", scope="kreds", placement="profile")
    c = n.compose_post("legacy text", scope="kreds", placement="profile")
    n.set_block_pin(a, 2, 0, 2, 2)
    n.set_block_span(b, 1, 1)
    view = n.profile_view(n.identity_pub)
    by_id = {p["msg_id"]: p for p in view["wall"]}
    assert by_id[a]["pin"] == {"x": 2, "y": 0, "w": 2, "h": 2}
    assert by_id[a]["span"] == {"w": 2, "h": 2}       # pin implies its span
    assert by_id[b]["pin"] is None
    assert by_id[b]["span"] == {"w": 1, "h": 1}
    assert by_id[c]["pin"] is None
    assert by_id[c]["span"] == {"w": 4, "h": 1}       # legacy default, text


def test_profile_view_legacy_sizes_map_to_default_spans(tmp_path):
    n = _node(tmp_path)
    t = n.compose_post("text", scope="kreds", placement="profile")
    ph = n.compose_post("pic", scope="kreds", placement="profile",
                        photos=[b"\x89PNG fake"])
    n.set_block_size(t, "small")     # legacy Phase-A size
    view = n.profile_view(n.identity_pub)
    by_id = {p["msg_id"]: p for p in view["wall"]}
    assert by_id[t]["span"] == {"w": 1, "h": 1}       # small -> 1x1
    assert by_id[ph]["span"] == {"w": 4, "h": 2}      # full default, media


def test_wall_is_newest_first_regardless_of_order_map(tmp_path):
    n = _node(tmp_path)
    a = n.compose_post("older", scope="kreds", placement="profile")
    b = n.compose_post("newer", scope="kreds", placement="profile")
    n.set_profile_layout([a, b])     # legacy order says a first
    view = n.profile_view(n.identity_pub)
    ids = [p["msg_id"] for p in view["wall"]]
    assert ids.index(b) < ids.index(a)   # geometry rules; order map is inert
```

Note: check `compose_post`'s actual photo kwarg name in `hearth/node.py` before writing the `ph` line (it may be `photos=` or a blobs list) — use the same call shape `tests/test_api_profile_posts.py` or `tests/test_profile_grids_integration.py` uses. If a fake PNG is rejected by the image gate at compose level, reuse the tiny valid PNG helper those tests use.

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_block_pins.py -v`
Expected: FAIL — `AttributeError: 'HearthNode' object has no attribute 'set_block_pin'`

- [ ] **Step 3: Implement**

(a) `hearth/store.py` `profile_layout` — add to the `best` dict and the empty default:

```python
                    best = {"order": p.get("order", []),
                            "grids": p.get("grids", {}),
                            "sizes": p.get("sizes", {}),
                            "pins": p.get("pins", {}),
                            "spans": p.get("spans", {})}
```

(and the function's final `return best or {...}` empty-default dict gains `"pins": {}, "spans": {}` — read the end of the function for its exact shape).

(b) `hearth/node.py` — update the three existing republishers to carry the new maps (each `make_profile_layout(...)` call gains `pins=cur["pins"], spans=cur["spans"]`; `set_profile_layout` reads `cur` already). Then add, after `set_block_size`:

```python
    def _check_block_id(self, msg_id):
        if not (isinstance(msg_id, str) and len(msg_id) == 64
                and all(c in "0123456789abcdef" for c in msg_id)):
            raise ValueError("bad msg_id")

    def set_block_pin(self, msg_id: str, x: int, y: int, w: int, h: int) -> str:
        """Place (or move/resize) a block at explicit cell coordinates.
        Shape-checked here for a clean 400; overlap is the client's job."""
        self._check_block_id(msg_id)
        for v in (x, y, w, h):
            if not isinstance(v, int) or isinstance(v, bool):
                raise ValueError("bad pin geometry")
        if not (1 <= w <= WALL_COLS and 1 <= h <= MAX_BLOCK_H
                and 0 <= x and x + w <= WALL_COLS and 0 <= y <= MAX_LAYOUT):
            raise ValueError("bad pin geometry")
        cur = self.store.profile_layout(self.identity_pub)
        pins = dict(cur["pins"])
        pins[msg_id] = {"x": x, "y": y, "w": w, "h": h}
        spans = dict(cur["spans"])
        spans.pop(msg_id, None)          # geometry lives in the pin now
        if len(pins) > MAX_LAYOUT:
            raise ValueError("too many pinned blocks")
        return self._publish(make_profile_layout(
            self.device, cur["order"], grids=cur["grids"],
            sizes=cur["sizes"], pins=pins, spans=spans))

    def unpin_block(self, msg_id: str) -> str:
        """Send a block back to the unplaced tray, keeping its size."""
        self._check_block_id(msg_id)
        cur = self.store.profile_layout(self.identity_pub)
        pins = dict(cur["pins"])
        geom = pins.pop(msg_id, None)
        spans = dict(cur["spans"])
        if geom is not None:
            spans[msg_id] = {"w": geom["w"], "h": geom["h"]}
        return self._publish(make_profile_layout(
            self.device, cur["order"], grids=cur["grids"],
            sizes=cur["sizes"], pins=pins, spans=spans))

    def set_block_span(self, msg_id: str, w: int, h: int) -> str:
        """Size an UNPLACED block. A pinned block's geometry lives in its
        pin (set_block_pin) - one source of truth, so this refuses."""
        self._check_block_id(msg_id)
        if not (isinstance(w, int) and isinstance(h, int)
                and not isinstance(w, bool) and not isinstance(h, bool)
                and 1 <= w <= WALL_COLS and 1 <= h <= MAX_BLOCK_H):
            raise ValueError("bad span")
        cur = self.store.profile_layout(self.identity_pub)
        if msg_id in cur["pins"]:
            raise ValueError("block is pinned - move/resize via block-pin")
        spans = dict(cur["spans"])
        spans[msg_id] = {"w": w, "h": h}
        if len(spans) > MAX_LAYOUT:
            raise ValueError("too many sized blocks")
        return self._publish(make_profile_layout(
            self.device, cur["order"], grids=cur["grids"],
            sizes=cur["sizes"], pins=cur["pins"], spans=spans))
```

Import `WALL_COLS`, `MAX_BLOCK_H` from `hearth.messages` at the top of node.py (extend the existing import line).

(c) `profile_view` — replace the order/grids/sizes block (node.py:667-683) with:

```python
        # The collage is geometry-ruled (spec 2026-07-13): pins say where a
        # block sits; unpinned blocks flow newest-first (posts_by's order).
        # The legacy order/grids maps ride the wire untouched but no longer
        # shape rendering; legacy sizes map to default spans so a
        # never-arranged wall still has sane geometry.
        wall = self.posts_by(identity_pub, "profile")   # newest-first
        layout = self.store.profile_layout(identity_pub)
        pins, spans, sizes = layout["pins"], layout["spans"], layout["sizes"]

        def _default_span(p):
            size = sizes.get(p["msg_id"], "full")
            if size == "small":
                return {"w": 1, "h": 1}
            if size == "wide":
                return {"w": 2, "h": 2}
            has_media = bool(p.get("blobs")) or p.get("media") == "video"
            return {"w": 4, "h": 2} if has_media else {"w": 4, "h": 1}

        for p in wall:
            pin = pins.get(p["msg_id"])
            p["pin"] = pin
            if pin is not None:
                p["span"] = {"w": pin["w"], "h": pin["h"]}
            else:
                p["span"] = spans.get(p["msg_id"]) or _default_span(p)
```

and change the returned `"wall": ordered_wall` to `"wall": wall` (the `pos`/`listed`/`unlisted` lines are deleted; `p["grid"]`/`p["size"]` annotations are deleted too — the client stops reading them in Task 5, same bundle).

- [ ] **Step 4: Run tests**

Run: `.venv\Scripts\python.exe -m pytest tests/test_block_pins.py tests/test_api_profile_posts.py tests/test_profile_arrange_integration.py tests/test_profile_grids_integration.py tests/test_profile_bento_integration.py -v`
Expected: `tests/test_block_pins.py` PASSES. The three older integration tests and any API test asserting `p["grid"]`/`p["size"]`/order-shaping WILL FAIL — that is expected spec retirement, not collateral. Update those tests in place: where they asserted `view["wall"]` order equals the arranged order, assert newest-first; where they asserted `p["grid"]`/`p["size"]`, assert the layout record still CARRIES the maps (`store.profile_layout(...)["grids"][mid] == ...`) so the wire-compat guarantee stays pinned, and drop the view-annotation asserts. Keep each test's sync mechanics untouched — they still prove record propagation. State exactly this rationale in the updated docstrings.

- [ ] **Step 5: Run the full suite**

Run: `.venv\Scripts\python.exe -m pytest -q`
Expected: all pass + 2 skips. Fix any straggler that asserted the retired annotations (same rationale as above); anything ELSE failing means a real break — stop and investigate.

- [ ] **Step 6: Commit**

```bash
git add hearth/store.py hearth/node.py tests/test_block_pins.py tests/test_profile_arrange_integration.py tests/test_profile_grids_integration.py tests/test_profile_bento_integration.py tests/test_api_profile_posts.py
git commit -m "feat(collage): set_block_pin/unpin_block/set_block_span + profile_view pin/span annotation - wall is newest-first, geometry-ruled; legacy sizes map to default spans; order/grids ride the wire inert"
```

---

### Task 3: API endpoints

**Files:**
- Modify: `hearth/api.py:377-389` (after the existing layout endpoints)
- Test: `tests/test_api_pins.py` (create)

**Interfaces:**
- Consumes: Task 2's node methods.
- Produces: `POST /api/block-pin` `{msg_id, x, y, w, h}`, `POST /api/block-unpin` `{msg_id}`, `POST /api/block-span` `{msg_id, w, h}` — 200 `{"ok": true}` / 400 on `ValueError`, mirroring `/api/block-size`. Tasks 5-8 call these paths verbatim.

- [ ] **Step 1: Write the failing test**

Create `tests/test_api_pins.py` (mirror the client/fixture setup of `tests/test_api_profile_posts.py` — read its top and reuse its app/client construction exactly; the snippet below assumes a `client(tmp_path)`-style helper named as in that file):

```python
"""API surface for collage pins (Slice A): block-pin / block-unpin /
block-span, mirroring /api/block-size's contract."""


def test_pin_endpoints_roundtrip(client_fixture):
    c, node = client_fixture
    r = c.post("/api/post", data={"text": "blok", "scope": "kreds",
                                  "placement": "profile"})
    mid = r.json()["msg_id"]
    assert c.post("/api/block-pin", json={"msg_id": mid, "x": 1, "y": 0,
                                          "w": 2, "h": 2}).status_code == 200
    prof = c.get("/api/profile/" + node.identity_pub).json()
    blk = next(p for p in prof["wall"] if p["msg_id"] == mid)
    assert blk["pin"] == {"x": 1, "y": 0, "w": 2, "h": 2}
    assert c.post("/api/block-unpin", json={"msg_id": mid}).status_code == 200
    prof = c.get("/api/profile/" + node.identity_pub).json()
    blk = next(p for p in prof["wall"] if p["msg_id"] == mid)
    assert blk["pin"] is None and blk["span"] == {"w": 2, "h": 2}
    assert c.post("/api/block-span", json={"msg_id": mid, "w": 1,
                                           "h": 1}).status_code == 200


def test_pin_endpoint_400s(client_fixture):
    c, node = client_fixture
    r = c.post("/api/post", data={"text": "blok", "scope": "kreds",
                                  "placement": "profile"})
    mid = r.json()["msg_id"]
    assert c.post("/api/block-pin", json={"msg_id": mid, "x": 3, "y": 0,
                                          "w": 2, "h": 1}).status_code == 400
    assert c.post("/api/block-span", json={"msg_id": "zz", "w": 1,
                                           "h": 1}).status_code == 400
    # pinned block refuses span (one source of truth)
    assert c.post("/api/block-pin", json={"msg_id": mid, "x": 0, "y": 0,
                                          "w": 1, "h": 1}).status_code == 200
    assert c.post("/api/block-span", json={"msg_id": mid, "w": 2,
                                           "h": 2}).status_code == 400
```

Adapt the fixture name/shape to the file you mirrored — the assertions are the contract.

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_api_pins.py -v`
Expected: FAIL with 404 on `/api/block-pin`.

- [ ] **Step 3: Implement**

In `hearth/api.py`, directly after the `/api/block-size` route:

```python
    @app.post("/api/block-pin")
    async def block_pin(body: dict = Body(...)):
        _400(lambda: node.set_block_pin(body["msg_id"], body["x"],
                                        body["y"], body["w"], body["h"]))
        return {"ok": True}

    @app.post("/api/block-unpin")
    async def block_unpin(body: dict = Body(...)):
        _400(lambda: node.unpin_block(body["msg_id"]))
        return {"ok": True}

    @app.post("/api/block-span")
    async def block_span(body: dict = Body(...)):
        _400(lambda: node.set_block_span(body["msg_id"], body["w"], body["h"]))
        return {"ok": True}
```

- [ ] **Step 4: Run tests**

Run: `.venv\Scripts\python.exe -m pytest tests/test_api_pins.py tests/test_api_profile_posts.py -v`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add hearth/api.py tests/test_api_pins.py
git commit -m "feat(collage): POST /api/block-pin /block-unpin /block-span - 400s mirror /api/block-size"
```

---

### Task 4: Two-node integration — pins survive sync, legacy walls flow

**Files:**
- Test: `tests/test_profile_pins_integration.py` (create)

**Interfaces:**
- Consumes: everything above, over real sync sockets. Mirrors `tests/test_profile_arrange_integration.py`'s harness (`befriend`, `started`, `sync_with` — read that file and reuse its helpers verbatim).

- [ ] **Step 1: Write the test**

Create `tests/test_profile_pins_integration.py`:

```python
"""Two-node integration for collage pins (Slice A), over real sync
sockets - mirrors tests/test_profile_arrange_integration.py's harness.
Proves: A's pin geometry reaches B latest-wins; an unpinned block flows
with its span; a never-arranged (legacy) wall renders newest-first."""
import asyncio

from hearth.node import HearthNode
from hearth.sync import SyncService


def befriend(a, b):
    a.store.add_identity(b.identity_pub)
    b.store.add_identity(a.identity_pub)


async def started(node):
    svc = SyncService(node)
    port = await svc.start("127.0.0.1", 0)
    node.store.set_meta("gossip_addr", f"127.0.0.1:{port}")
    return svc, f"127.0.0.1:{port}"


def test_pins_survive_sync_and_legacy_flows(tmp_path):
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Anna", "anna-pc")
        b = HearthNode.create(tmp_path / "b", "Bo", "bo-pc")
        befriend(a, b)
        for n in (a, b):
            n.ensure_enckey()
        sa, aa = await started(a)
        sb, ba = await started(b)
        await sa.sync_with(ba)

        p1 = a.compose_post("pinned", scope="kreds", placement="profile")
        p2 = a.compose_post("tray", scope="kreds", placement="profile")
        a.set_block_pin(p1, 0, 1, 4, 3)
        a.set_block_span(p2, 1, 1)
        await sa.sync_with(ba)

        view = b.profile_view(a.identity_pub)
        by_id = {p["msg_id"]: p for p in view["wall"]}
        assert by_id[p1]["pin"] == {"x": 0, "y": 1, "w": 4, "h": 3}
        assert by_id[p2]["pin"] is None
        assert by_id[p2]["span"] == {"w": 1, "h": 1}

        # A moves the block; latest-wins geometry reaches B.
        a.set_block_pin(p1, 0, 0, 2, 2)
        await sa.sync_with(ba)
        view = b.profile_view(a.identity_pub)
        by_id = {p["msg_id"]: p for p in view["wall"]}
        assert by_id[p1]["pin"] == {"x": 0, "y": 0, "w": 2, "h": 2}

        # Legacy wall: Bo never arranged anything - newest-first flow with
        # default spans on Anna's side after a real sync.
        q1 = b.compose_post("bo older", scope="kreds", placement="profile")
        q2 = b.compose_post("bo newer", scope="kreds", placement="profile")
        await sb.sync_with(aa)
        view = a.profile_view(b.identity_pub)
        ids = [p["msg_id"] for p in view["wall"]]
        assert ids.index(q2) < ids.index(q1)
        assert all(p["pin"] is None for p in view["wall"])
        assert all(p["span"] == {"w": 4, "h": 1} for p in view["wall"])

        for s in (sa, sb):
            await s.stop()
    asyncio.run(scenario())
```

- [ ] **Step 2: Run it**

Run: `.venv\Scripts\python.exe -m pytest tests/test_profile_pins_integration.py -v`
Expected: PASS (Tasks 1-3 shipped the behavior; this pins it over the wire). If it fails, the bug is real — investigate, don't loosen.

- [ ] **Step 3: Commit**

```bash
git add tests/test_profile_pins_integration.py
git commit -m "test(collage): two-node integration - pin geometry syncs latest-wins, unpinned spans travel, legacy walls flow newest-first"
```

---

### Task 5: Client canvas rendering (pinned grid + holes + flow + tray shell)

**Files:**
- Modify: `hearth/web/index.html:133-137` (the `#profile-body` section)
- Modify: `hearth/web/app.js` — `renderProfilePage`'s wall section (~line 1374), `renderBlock` (~line 361), `photoGridClass` (~line 350); new `measureWallCell` helper
- Modify: `hearth/web/style.css:429-467` (the wall/block section)
- Test: `tests/test_web_assets.py` (append)

**Interfaces:**
- Consumes: `profile_view` blocks carrying `p.pin` / `p.span` (Task 2).
- Produces: `#profile-wall` (pinned canvas), `#profile-wall-flow` (unplaced, visitors + owner outside Arrange), `#profile-tray` (owner in Arrange — Task 6/7 populate its interactions); `renderWall(p)` helper; `WALL_PINS` global (`{msg_id: {x,y,w,h}}` for the rendered profile — Task 6's overlap tests read it); `measureWallCell()`; CSS custom property `--cell`. Blocks carry `.block-cells` + inline `grid-column`/`grid-row`.

- [ ] **Step 1: Write the failing web-asset test**

Append to `tests/test_web_assets.py`:

```python
def test_collage_canvas_wired():
    # Slice A pin engine: 4-col canvas with measured square-ish cells,
    # pinned blocks at explicit coordinates, unplaced flow + tray,
    # legacy size-*/grid-* rendering retired.
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert 'id="profile-wall-flow"' in html
    assert 'id="profile-tray"' in html
    wall_rule = _css_rule(css, "#profile-wall")
    assert "repeat(4, 1fr)" in wall_rule
    assert "var(--cell" in wall_rule
    assert "measureWallCell" in js and "clientWidth" in js
    rw = _js_fn_body(js, "renderWall")
    assert "pin" in rw and "profile-tray" in rw
    rb = _js_fn_body(js, "renderBlock")
    assert "gridColumn" in rb and "gridRow" in rb
    assert "size-full" not in js          # Phase-A width classes retired
    assert "existence-disclosure" in js or "opaque ids" in js  # honesty note
```

- [ ] **Step 2: Run to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py::test_collage_canvas_wired -v`
Expected: FAIL on `'id="profile-wall-flow"' in html`.

- [ ] **Step 3: index.html**

In `#profile-body` (line ~133), after `<div id="profile-wall"></div>` add:

```html
            <div id="profile-tray-wrap" class="hidden">
              <h4 class="profile-section-h">Unplaced</h4>
              <div id="profile-tray"></div>
            </div>
            <div id="profile-wall-flow"></div>
```

(the tray heading + container live in a wrapper so the "Unplaced" label vanishes with it outside Arrange mode).

- [ ] **Step 4: app.js**

(a) Replace `photoGridClass` with a two-way version (grids are dead; single vs many is all that's left until Slice C's decks):

```js
// Photo container class: one photo renders big, several as a cropped
// 2-col gallery (interim until Slice C's album decks). The Slice-3b grid
// layouts are retired - a synced record's grids map is deliberately
// ignored (spec 2026-07-13 decision 4).
function photoGridClass(count) {
  return count === 1 ? "block-photo" : "block-gallery";
}
```

(update `renderBlock`'s call site to `photoGridClass(p.blobs.length)`; delete the arrange-mode grid picker's use in `openBlockSettings` — Task 7 rebuilds that modal anyway, so for THIS task just delete the `photo layout` group code from `openBlockSettings` and its `photoGridClass` reference so `node --check` stays clean).

(b) In `renderBlock`, replace the `block.classList.add("size-" + ...)` line with:

```js
  // Collage geometry: a pinned block sits at explicit cells; an unplaced
  // one spans its chosen size and flows. Pins/spans are plaintext geometry
  // over opaque ids (existence-disclosure class, same as the old order
  // list - see the spec's metadata honesty note).
  block.classList.add("block-cells");
  const geom = p.pin || null;
  const span = p.span || {w: 4, h: 1};
  if (geom) {
    block.style.gridColumn = (geom.x + 1) + " / span " + geom.w;
    block.style.gridRow = (geom.y + 1) + " / span " + geom.h;
  } else {
    block.style.gridColumn = "span " + span.w;
    block.style.gridRow = "span " + span.h;
  }
  if (p.text && !(p.blobs && p.blobs.length))
    block.classList.add("text-w" + span.w);
```

(c) In `renderProfilePage`, replace the wall loop (lines ~1374-1378) with a call to the new helper, and add the helper + measurement near it:

```js
  renderWall(p);
```

```js
// The collage: pinned blocks at explicit coordinates on #profile-wall
// (holes stay empty - a block this viewer can't decrypt leaves its gap);
// unplaced blocks flow newest-first below (#profile-wall-flow), or sit in
// the Unplaced tray when the owner is arranging. WALL_PINS mirrors the
// rendered pins map for Task-6 overlap checks.
let WALL_PINS = {};
function renderWall(p) {
  const wall = document.getElementById("profile-wall");
  const flow = document.getElementById("profile-wall-flow");
  const tray = document.getElementById("profile-tray");
  const trayWrap = document.getElementById("profile-tray-wrap");
  wall.replaceChildren(); flow.replaceChildren(); tray.replaceChildren();
  WALL_PINS = {};
  const pinned = p.wall.filter(b => b.pin);
  const unpinned = p.wall.filter(b => !b.pin);
  for (const post of pinned) {
    WALL_PINS[post.msg_id] = post.pin;
    wall.append(renderBlock(post));
  }
  const trayMode = ARRANGING && p.mine;
  trayWrap.classList.toggle("hidden", !trayMode);
  for (const post of unpinned)
    (trayMode ? tray : flow).append(renderBlock(post));
  if (!p.wall.length) flow.append(el("div", "hint",
    p.mine ? "Your profile is a blank canvas - post something above." : "Nothing here yet."));
  measureWallCell();
}

// --cell drives grid-auto-rows: CSS can't express "row height = column
// width" for spanning rows, so measure it (same idiom as --nav-h).
function measureWallCell() {
  const wall = document.getElementById("profile-wall");
  if (!wall) return;
  const cell = Math.max(40, (wall.clientWidth - 3 * 12) / 4);
  document.documentElement.style.setProperty("--cell", cell.toFixed(2) + "px");
}
window.addEventListener("resize", measureWallCell);
```

(d) Delete the now-dead `#profile-wall`-targeting code in the old `renderProfilePage` wall block (the empty-hint lives in `renderWall` now).

- [ ] **Step 5: style.css**

Replace the wall/block section (lines ~429-467) with:

```css
/* Collage canvas (Slice A): 4 columns of square-ish cells. --cell is
   measured in JS (measureWallCell) - CSS cannot express "row height =
   column width" when blocks span rows. Pinned blocks carry inline
   grid-column/grid-row from their pin; empty cells are real (honest
   holes). #profile-wall-flow packs unplaced blocks newest-first;
   the collage SCALES on small screens (cells shrink), it never reflows -
   the user composed a page. */
#profile-wall { display: grid; grid-template-columns: repeat(4, 1fr);
  grid-auto-rows: var(--cell, 120px); gap: 12px; }
#profile-wall-flow { display: grid; grid-template-columns: repeat(4, 1fr);
  grid-auto-rows: var(--cell, 120px); grid-auto-flow: row dense;
  gap: 12px; margin-top: 12px; }
#profile-tray { display: grid; grid-template-columns: repeat(4, 1fr);
  grid-auto-rows: var(--cell, 120px); grid-auto-flow: row dense;
  gap: 12px; padding: 10px; border: 1px dashed var(--line-2);
  border-radius: 12px; background: var(--paper); }
#profile-wall-flow > .hint { grid-column: 1 / -1; }
.block { min-width: 0; position: relative; overflow: hidden;
  border-radius: 12px; }
/* media fills its cells, cropped (iOS-widget style) */
.block-photo, .block-photo img, .block-video, .block-video video {
  width: 100%; height: 100%; }
.block-photo img, .block-video video { object-fit: cover; display: block;
  border-radius: 12px; }
.block-gallery { display: grid; grid-template-columns: repeat(2, 1fr);
  gap: 8px; height: 100%; }
.block-gallery img { width: 100%; height: 100%; object-fit: cover;
  border-radius: 10px; }
/* text scales to its width-span: 4-wide is a headliner, 1x1 a note */
.block-text-body { font-size: 15px; line-height: 1.5; white-space: pre-wrap;
  overflow: hidden; }
.block.text-w1 .block-text-body { font-size: 12.5px; }
.block.text-w2 .block-text-body { font-size: 15px; }
.block.text-w3 .block-text-body { font-size: 19px; }
.block.text-w4 .block-text-body { font-size: 24px; font-weight: 650;
  font-family: var(--disp); line-height: 1.25; }
/* self-only overlays sit on the cell, not under it (fixed block heights) */
.block .block-scope { position: absolute; top: 6px; left: 6px; z-index: 2; }
.block .del { position: absolute; bottom: 6px; right: 6px; z-index: 2; }
```

Keep any `.block-scope`/`.del` base styling that exists elsewhere; delete the retired rules: `.block.size-small/-wide/-full`, `.block-grid-2/-3`, `.block-hero`, `.block-masonry`, the 560px clamp block, and `.layout-pick`'s arrange twin if orphaned (the composer's `.layout-pick` stays until Slice B).

- [ ] **Step 6: Verify**

Run: `node --check hearth/web/app.js`
Expected: clean.
Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -v`
Expected: the new test passes. Older asset tests referencing retired markup (`size-full`, grid classes, the arrange-mode grid picker) fail — update them in place with a one-line docstring note ("retired by the collage redesign, spec 2026-07-13"); anything unrelated failing is a real break.

- [ ] **Step 7: Commit**

```bash
git add hearth/web/index.html hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat(collage): 4-col pinned canvas with measured square cells, honest holes, unplaced flow + tray shell; Phase-A size classes and Slice-3b grid rendering retired"
```

---

### Task 6: Drag-to-pin + corner resize (Arrange mode)

**Files:**
- Modify: `hearth/web/app.js` — `startBlockDrag` (~line 481, full rewrite), the Arrange toggle's Done handler (~line 1405: it must stop POSTing `/api/profile-layout` and just exit + re-render), `renderBlock`'s arranging branch (~line 402: add the resize handle)
- Modify: `hearth/web/style.css` (ghost + handle styles)
- Test: `tests/test_web_assets.py` (append)

**Interfaces:**
- Consumes: `WALL_PINS`, `measureWallCell`/`--cell`, `/api/block-pin`, `/api/block-unpin` (Tasks 3, 5).
- Produces: `startBlockDrag(block, ev, p)` (note the new third arg — the post object, for span/pin data; update the pointerdown handoff call in `renderBlock` accordingly), `cellFromPoint`, `pinsOverlap`, `pinFree` helpers; `.pin-ghost` element with `.valid`/`.invalid` states; `.block-resize` handle. Task 7's modal reuses `pinsOverlap`/`pinFree`; Task 8's smoke drives all of it.

- [ ] **Step 1: Write the failing web-asset test**

Append to `tests/test_web_assets.py`:

```python
def test_drag_to_pin_wired():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    drag = _js_fn_body(js, "startBlockDrag")
    assert "cellFromPoint" in drag and "pin-ghost" in drag
    assert "/api/block-pin" in drag
    assert "/api/block-unpin" in drag        # dropping off-canvas unpins
    assert "insertBefore" not in drag        # reorder semantics are gone
    ov = _js_fn_body(js, "pinsOverlap")
    assert "w" in ov and "h" in ov
    assert "block-resize" in js              # corner handle exists
    _css_rule(css, ".pin-ghost")
    assert ".pin-ghost.invalid" in css
    done = _js_fn_body(js, "toggleArrange") if "function toggleArrange" in js \
        else _js_fn_body(js, "renderProfilePage")
    assert '"/api/profile-layout"' not in done   # Done no longer posts order
```

(If the Arrange toggle is an inline `onclick` rather than a named function, locate it by its current `/api/profile-layout` POST — line ~1405 — name it `toggleArrange` as part of this task so the assert has a stable target.)

- [ ] **Step 2: Run to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py::test_drag_to_pin_wired -v`
Expected: FAIL on `cellFromPoint`.

- [ ] **Step 3: Implement the geometry helpers + drag rewrite**

(a) Above `startBlockDrag`, add:

```js
// ---- collage geometry (Arrange mode) --------------------------------
const WALL_GAP = 12;
function wallMetrics() {
  const wall = document.getElementById("profile-wall");
  const r = wall.getBoundingClientRect();
  const cell = (r.width - 3 * WALL_GAP) / 4;
  return {wall, r, cell};
}
// Top-left cell for a w-wide block whose pointer is at (px, py); y is
// unbounded downward (the canvas grows), x clamps into the 4 columns.
function cellFromPoint(px, py, w) {
  const {r, cell} = wallMetrics();
  const step = cell + WALL_GAP;
  const x = Math.max(0, Math.min(4 - w, Math.round((px - r.left) / step)));
  const y = Math.max(0, Math.round((py - r.top) / step));
  return {x, y};
}
function pinsOverlap(a, b) {
  return a.x < b.x + b.w && b.x < a.x + a.w
      && a.y < b.y + b.h && b.y < a.y + a.h;
}
function pinFree(geom, exceptId) {
  for (const [id, pin] of Object.entries(WALL_PINS))
    if (id !== exceptId && pinsOverlap(geom, pin)) return false;
  return true;
}
function ghostAt(geom, ok) {
  let g = document.getElementById("pin-ghost");
  if (!g) {
    g = el("div", "pin-ghost"); g.id = "pin-ghost";
    document.getElementById("profile-wall").append(g);
  }
  g.style.gridColumn = (geom.x + 1) + " / span " + geom.w;
  g.style.gridRow = (geom.y + 1) + " / span " + geom.h;
  g.classList.toggle("invalid", !ok);
  return g;
}
function clearGhost() {
  const g = document.getElementById("pin-ghost");
  if (g) g.remove();
}
```

(b) Rewrite `startBlockDrag` completely (delete the reorder version — snapshot/`afterElement`/`insertBefore` all go). Keep the hard-won mechanics its comments document: capture on the WALL not the block, pointerId filtering, idempotent finish via `lostpointercapture`, edge auto-scroll, cancel-restores:

```js
// Hand-rolled pointer drag (mouse + touch + pen), collage edition: the
// gesture no longer reorders siblings - it carries the block's w x h
// footprint to a cell target. The ghost previews the drop (green = free,
// red = overlap/out of bounds); release on valid pins via /api/block-pin;
// release over the tray/off-canvas unpins; invalid or cancelled drops
// change nothing (no auto-push - blocks never displace each other, spec
// 2026-07-13 section 3). Capture stays on the WALL (a reparented captured
// element loses capture in Chromium - found live in Slice 3a); finish()
// is idempotent and also wired to lostpointercapture (the autoscroll
// dropped-event fix from Slice 3a still applies).
function startBlockDrag(block, ev, p) {
  if ((ev.button != null && ev.button !== 0) || !ev.isPrimary) return;
  ev.preventDefault();
  const wall = document.getElementById("profile-wall");
  const wasPinned = !!p.pin;
  const w = p.span.w, h = p.span.h;
  try { wall.setPointerCapture(ev.pointerId); } catch (e) { return; }
  block.classList.add("dragging");
  let target = null, ok = false;

  const onMove = (e) => {
    if (e.pointerId !== ev.pointerId) return;
    const {r} = wallMetrics();
    const over = e.clientX >= r.left && e.clientX <= r.right
              && e.clientY >= r.top - 40;   // small grace above row 0
    if (!over) { clearGhost(); target = null; ok = false; }
    else {
      const c = cellFromPoint(e.clientX, e.clientY, w);
      target = {x: c.x, y: c.y, w, h};
      ok = pinFree(target, p.msg_id);
      ghostAt(target, ok);
    }
    const M = 70;
    if (e.clientY < M) window.scrollBy(0, -12);
    else if (e.clientY > window.innerHeight - M) window.scrollBy(0, 12);
  };
  let done = false;
  const finish = async (commit) => {
    if (done) return;
    done = true;
    wall.removeEventListener("pointermove", onMove);
    wall.removeEventListener("pointerup", onUp);
    wall.removeEventListener("pointercancel", onCancel);
    wall.removeEventListener("lostpointercapture", onLost);
    try { wall.releasePointerCapture(ev.pointerId); } catch (e) { /* released */ }
    block.classList.remove("dragging");
    clearGhost();
    if (commit && target && ok) {
      await postJSON("/api/block-pin", {msg_id: p.msg_id, ...target});
    } else if (commit && !target && wasPinned) {
      // dragged clean off the canvas: back to the tray
      await postJSON("/api/block-unpin", {msg_id: p.msg_id});
    } else if (!commit || !target) {
      return void (CURRENT_PROFILE && openProfile(CURRENT_PROFILE));
    }
    if (CURRENT_PROFILE) openProfile(CURRENT_PROFILE);
  };
  const onUp = (e) => { if (e.pointerId === ev.pointerId) finish(true); };
  const onCancel = (e) => { if (e.pointerId === ev.pointerId) finish(false); };
  const onLost = () => finish(true);   // dropped-terminator safety net
  wall.addEventListener("pointermove", onMove);
  wall.addEventListener("pointerup", onUp);
  wall.addEventListener("pointercancel", onCancel);
  wall.addEventListener("lostpointercapture", onLost);
}
```

`postJSON` — if no such helper exists yet, add one next to `j()`: 

```js
async function postJSON(url, body) {
  const r = await fetch(url, {method: "POST",
    headers: {"Content-Type": "application/json"}, body: JSON.stringify(body)});
  if (!r.ok) alert("Couldn't save: " + await r.text());
  return r.ok;
}
```

(check first — Slice 3b-era code may already have an equivalent; reuse it if so.)

(c) In `renderBlock`'s arranging branch, update the handoff `startBlockDrag(block, ev)` → `startBlockDrag(block, ev, p)`, and after the cog button add the resize handle (pinned blocks only):

```js
    if (p.pin) {
      const rz = el("div", "block-resize");
      rz.setAttribute("aria-hidden", "true");   // modal presets are the a11y path
      rz.addEventListener("pointerdown", (e) => {
        if ((e.button != null && e.button !== 0) || !e.isPrimary) return;
        e.preventDefault(); e.stopPropagation();   // never starts a move-drag
        startBlockResize(block, e, p);
      });
      block.append(rz);
    }
```

(d) Add `startBlockResize` after `startBlockDrag` (same lifecycle discipline):

```js
// Corner resize in cell steps: width/height derive from pointer distance
// past the block's top-left cell; clamped to the canvas and validated
// against neighbors like a move. Same capture/finish rules as the drag.
function startBlockResize(block, ev, p) {
  const wall = document.getElementById("profile-wall");
  try { wall.setPointerCapture(ev.pointerId); } catch (e) { return; }
  block.classList.add("dragging");
  const pin = p.pin;
  let target = null, ok = false;
  const onMove = (e) => {
    if (e.pointerId !== ev.pointerId) return;
    const {r, cell} = wallMetrics();
    const step = cell + WALL_GAP;
    const w = Math.max(1, Math.min(4 - pin.x,
      Math.round((e.clientX - (r.left + pin.x * step)) / step)));
    const h = Math.max(1, Math.min(8,
      Math.round((e.clientY - (r.top + pin.y * step)) / step)));
    target = {x: pin.x, y: pin.y, w, h};
    ok = pinFree(target, p.msg_id);
    ghostAt(target, ok);
  };
  let done = false;
  const finish = async (commit) => {
    if (done) return;
    done = true;
    wall.removeEventListener("pointermove", onMove);
    wall.removeEventListener("pointerup", onUp);
    wall.removeEventListener("pointercancel", onCancel);
    wall.removeEventListener("lostpointercapture", onLost);
    try { wall.releasePointerCapture(ev.pointerId); } catch (e) { /* released */ }
    block.classList.remove("dragging");
    clearGhost();
    if (commit && target && ok)
      await postJSON("/api/block-pin", {msg_id: p.msg_id, ...target});
    if (CURRENT_PROFILE) openProfile(CURRENT_PROFILE);
  };
  const onUp = (e) => { if (e.pointerId === ev.pointerId) finish(true); };
  const onCancel = (e) => { if (e.pointerId === ev.pointerId) finish(false); };
  const onLost = () => finish(true);
  wall.addEventListener("pointermove", onMove);
  wall.addEventListener("pointerup", onUp);
  wall.addEventListener("pointercancel", onCancel);
  wall.addEventListener("lostpointercapture", onLost);
}
```

(e) The Arrange toggle (~line 1405): name it `toggleArrange` if anonymous, delete the Done-branch's `/api/profile-layout` POST + error alert entirely — every pin/resize/nudge already persisted itself — so Done is now:

```js
    ARRANGING = false;
    if (CURRENT_PROFILE) await openProfile(CURRENT_PROFILE);
```

(f) style.css additions:

```css
/* Arrange-mode collage affordances */
.pin-ghost { border: 2px dashed var(--red); border-radius: 12px;
  background: color-mix(in srgb, var(--red) 12%, transparent);
  pointer-events: none; z-index: 3; }
.pin-ghost.invalid { border-color: var(--ink-2); border-style: dotted;
  background: color-mix(in srgb, var(--ink-2) 14%, transparent); }
.block.dragging { opacity: .45; }
.block-resize { position: absolute; right: 2px; bottom: 2px; width: 18px;
  height: 18px; cursor: nwse-resize; z-index: 3; border-right: 3px solid
  var(--ink-2); border-bottom: 3px solid var(--ink-2);
  border-bottom-right-radius: 8px; touch-action: none; }
```

(Note `.block .del` from Task 5 sits bottom-right too — move the delete button to bottom-LEFT in Task 5's CSS if you land here and they collide: `left: 6px; right: auto;`. Do it in this task if Task 5 shipped it bottom-right.)

- [ ] **Step 4: Verify**

Run: `node --check hearth/web/app.js`
Expected: clean.
Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -v`
Expected: all pass (older drag asserts from Slice 3a-era tests that assert `insertBefore`-reorder or Done-POST behavior get updated in place with the retirement note, same rationale as Task 5).

- [ ] **Step 5: Commit**

```bash
git add hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat(collage): drag-to-pin with cell-snapped valid/invalid ghost (no auto-push), drag-off unpins, corner resize in cell steps; Done stops posting order - every action persists itself"
```

---

### Task 7: Settings modal — presets, nudges, place/tray (the keyboard path)

**Files:**
- Modify: `hearth/web/app.js` — `openBlockSettings` (~line 500-593; replace the Move Up/Down group and any leftover grid-picker code with the new groups)
- Test: `tests/test_web_assets.py` (append)

**Interfaces:**
- Consumes: `pinFree`, `WALL_PINS`, the three endpoints, `p.pin`/`p.span`.
- Produces: modal groups — Size presets (1×1, 2×1, 2×2, 4×2, 4×3), Nudge (pinned only: ←→↑↓ one cell), Place on canvas (unpinned: first free spot scan), Send to tray (pinned). All actions POST immediately and re-render via the modal's existing rebuild pattern. Task 8's smoke drives the preset + nudge paths by keyboard.

- [ ] **Step 1: Write the failing web-asset test**

Append to `tests/test_web_assets.py`:

```python
def test_block_settings_modal_collage_groups():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    body = _js_fn_body(js, "openBlockSettings")
    for needle in ("firstFreeSpot", "/api/block-unpin", "/api/block-span",
                   "/api/block-pin", "Send to tray", "Place on canvas"):
        assert needle in body, needle
    assert "previousElementSibling" not in body   # Up/Down reorder retired
    ff = _js_fn_body(js, "firstFreeSpot")
    assert "pinFree" in ff
```

- [ ] **Step 2: Run to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py::test_block_settings_modal_collage_groups -v`
Expected: FAIL on `firstFreeSpot`.

- [ ] **Step 3: Implement**

(a) Add the scan helper next to the geometry helpers from Task 6:

```js
// First free {x, y} that fits a w x h block: scan rows top-down, columns
// left-to-right - the keyboard path's "Place on canvas".
function firstFreeSpot(w, h, exceptId) {
  for (let y = 0; y <= 500; y++)
    for (let x = 0; x + w <= 4; x++)
      if (pinFree({x, y, w, h}, exceptId)) return {x, y};
  return null;   // unreachable in practice (canvas grows downward)
}
```

(b) In `openBlockSettings`, delete the Move Up/Down group (lines ~560-584) and any remaining grid-select group, and build in their place (keep the modal's existing `settings-group`/`settings-row`/`refresh-after-action` idioms — actions POST, then re-render the profile and reopen the modal with `focusSel` exactly like the size buttons did in Phase A):

```js
  // Size presets - the keyboard path for what corner-drag does by pointer.
  const sizes = el("div", "settings-group");
  sizes.append(el("div", "settings-label", "Size"));
  const srow = el("div", "settings-row");
  for (const [w, h] of [[1, 1], [2, 1], [2, 2], [4, 2], [4, 3]]) {
    const btn = el("button", "settings-opt", w + "x" + h);
    btn.type = "button";
    btn.dataset.sel = "size-" + w + "x" + h;
    if (p.pin && p.pin.w === w && p.pin.h === h) btn.classList.add("active");
    if (!p.pin && p.span && p.span.w === w && p.span.h === h) btn.classList.add("active");
    btn.onclick = async () => {
      if (p.pin) {
        const x = Math.min(p.pin.x, 4 - w);
        const g = {x, y: p.pin.y, w, h};
        if (!pinFree(g, p.msg_id)) { alert("No room there - move it first."); return; }
        await postJSON("/api/block-pin", {msg_id: p.msg_id, ...g});
      } else {
        await postJSON("/api/block-span", {msg_id: p.msg_id, w, h});
      }
      await reopenAfterAction('[data-sel="size-' + w + 'x' + h + '"]');
    };
    srow.append(btn);
  }
  sizes.append(srow); body.append(sizes);

  if (p.pin) {
    // Nudge - one cell per press, refused (disabled feel) when blocked.
    const move = el("div", "settings-group");
    move.append(el("div", "settings-label", "Move"));
    const mrow = el("div", "settings-row");
    for (const [label, dx, dy] of [["Left", -1, 0], ["Right", 1, 0],
                                   ["Up", 0, -1], ["Down", 0, 1]]) {
      const btn = el("button", "settings-opt", label);
      btn.type = "button";
      btn.dataset.sel = "nudge-" + label;
      const g = {x: p.pin.x + dx, y: p.pin.y + dy, w: p.pin.w, h: p.pin.h};
      btn.disabled = g.x < 0 || g.x + g.w > 4 || g.y < 0
                     || !pinFree(g, p.msg_id);
      btn.onclick = async () => {
        await postJSON("/api/block-pin", {msg_id: p.msg_id, ...g});
        await reopenAfterAction('[data-sel="nudge-' + label + '"]');
      };
      mrow.append(btn);
    }
    move.append(mrow); body.append(move);

    const tray = el("button", "settings-opt", "Send to tray");
    tray.type = "button";
    tray.onclick = async () => {
      await postJSON("/api/block-unpin", {msg_id: p.msg_id});
      await reopenAfterAction(null);   // block left the canvas; focus close btn
    };
    body.append(tray);
  } else {
    const place = el("button", "settings-opt", "Place on canvas");
    place.type = "button";
    place.onclick = async () => {
      const spot = firstFreeSpot(p.span.w, p.span.h, p.msg_id);
      if (!spot) { alert("No free spot fits this block."); return; }
      await postJSON("/api/block-pin",
        {msg_id: p.msg_id, ...spot, w: p.span.w, h: p.span.h});
      await reopenAfterAction(null);
    };
    body.append(place);
  }
```

`reopenAfterAction(focusSel)` — implement once near `openBlockSettings`, following the exact refresh pattern the Phase-A size buttons used (re-fetch the profile, re-render, find the block's element by `data-msg-id`, reopen the modal with the given `focusSel`, falling back to closing the modal if the block is no longer on the canvas/tray). Read the existing Phase-A "apply immediately + refresh the modal" code in this same function and factor it — do not invent a new pattern.

- [ ] **Step 4: Verify**

Run: `node --check hearth/web/app.js` → clean.
Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -v` → all pass (Phase-A modal asserts updated in place with the retirement note).

- [ ] **Step 5: Commit**

```bash
git add hearth/web/app.js tests/test_web_assets.py
git commit -m "feat(collage): block settings modal gains size presets, one-cell nudges, place-on-canvas and send-to-tray - the full keyboard path for the pin engine; Up/Down reorder retired"
```

---

### Task 8: Live smoke + full suite + roadmap increment note

**Files:**
- Create: `tests/test_ui_smoke_collage.py`
- Modify: `ROADMAP.md` (feature-12 increment note)

**Interfaces:**
- Consumes: everything above through the real UI; the `LiveNode` harness from `tests/test_ui_smoke_seen_badge.py` (import it — do not copy it).

- [ ] **Step 1: Write the smoke**

Create `tests/test_ui_smoke_collage.py`:

```python
"""UI_E2E=1-gated live smoke for the collage pin engine (Slice A): two
real nodes, real sync, headless Chromium. Reuses the LiveNode harness
from test_ui_smoke_seen_badge (import, not copy)."""
import os

import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("UI_E2E") != "1",
    reason="live browser smoke; set UI_E2E=1 (needs playwright chromium)")

from tests.test_ui_smoke_seen_badge import LiveNode, befriend


def test_pin_drag_resize_and_synced_view(tmp_path):
    from playwright.sync_api import sync_playwright

    a = LiveNode(tmp_path / "a", "Anna", "anna-pc")
    b = LiveNode(tmp_path / "b", "Bo", "bo-pc")
    try:
        befriend(a, b)
        a.start(); b.start()
        a.sync_with(b)
        mid = a.node.compose_post("min blok", scope="kreds",
                                  placement="profile")

        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            page = browser.new_page(viewport={"width": 1280, "height": 900})
            errors = []
            page.on("pageerror", lambda e: errors.append(str(e)))
            page.goto(f"http://127.0.0.1:{a.http_port}/")
            page.wait_for_selector(".fchip")

            # own profile -> Arrange: the new block waits in the tray
            page.click('.navlinks button[data-view="me"]')
            page.wait_for_selector("#profile-arrange")
            page.click("#profile-arrange")
            page.wait_for_selector("#profile-tray .block")

            # keyboard path: place on canvas via the modal
            page.click("#profile-tray .block .block-settings-btn")
            page.click("text=Place on canvas")
            page.wait_for_selector("#profile-wall .block")

            # modal preset resize: 2x2
            page.click("#profile-wall .block .block-settings-btn")
            page.click('[data-sel="size-2x2"]')
            page.wait_for_timeout(400)
            page.keyboard.press("Escape")

            # nudge Right moves x by one cell (verify via the API)
            page.click("#profile-wall .block .block-settings-btn")
            page.click('[data-sel="nudge-Right"]')
            page.wait_for_timeout(400)
            page.keyboard.press("Escape")
            lay = a.node.store.profile_layout(a.node.identity_pub)
            assert lay["pins"][mid]["x"] == 1
            assert (lay["pins"][mid]["w"], lay["pins"][mid]["h"]) == (2, 2)

            # pointer drag: pick the block up and drop it at column 0, a
            # couple of rows down; verify the pin moved
            blk = page.locator("#profile-wall .block").first
            box = blk.bounding_box()
            wall = page.locator("#profile-wall").bounding_box()
            page.mouse.move(box["x"] + box["width"] / 2,
                            box["y"] + box["height"] / 2)
            page.mouse.down()
            page.mouse.move(wall["x"] + 40, wall["y"] + wall["height"] - 20,
                            steps=12)
            page.mouse.up()
            page.wait_for_timeout(600)
            lay = a.node.store.profile_layout(a.node.identity_pub)
            assert lay["pins"][mid]["x"] == 0
            assert lay["pins"][mid]["y"] > 0

            # persistence + honest geometry on the friend's synced view
            a.sync_with(b)
            page2 = browser.new_page(viewport={"width": 1280, "height": 900})
            page2.goto(f"http://127.0.0.1:{b.http_port}/")
            page2.wait_for_selector(".fchip")
            page2.click(".fchip:has-text('Anna')")
            page2.click("text=Anna")   # open the profile from the chip/entry
            page2.wait_for_selector("#profile-wall .block")
            col = page2.locator("#profile-wall .block").first \
                .evaluate("b => b.style.gridColumn")
            assert col.startswith("1 / span 2")   # x=0, w=2 on Bo's side
            assert page2.locator("#profile-arrange").is_hidden()

            assert not errors, f"console pageerrors: {errors}"
            browser.close()
    finally:
        try:
            a.stop()
        finally:
            b.stop()
```

(The Bo-side profile-open clicks may need adjusting to the real chip/entry affordances — drive whatever `openProfile` path exists; the assertions are the contract. Debug the feature first on any timeout, per the standing smoke discipline.)

- [ ] **Step 2: Run it live**

Run (PowerShell): `$env:UI_E2E = "1"; .venv\Scripts\python.exe -m pytest tests/test_ui_smoke_collage.py -v -s; Remove-Item Env:UI_E2E`
Expected: PASS. Then gate check without the env var: 1 skipped.

- [ ] **Step 3: Full suite twice**

Run: `.venv\Scripts\python.exe -m pytest -q` (twice)
Expected: all pass + 3 env-gated skips (TOR_E2E + the two UI_E2E smokes), consistent. `node --check hearth/web/app.js` clean.

- [ ] **Step 4: ROADMAP increment note**

In `ROADMAP.md`, at the end of the feature-12 entry (after the lightbox increment), add:

```
**Increment - collage Slice A (pin engine):** the Wall is now a free-pin collage - a 4-column canvas of square-ish cells (measured `--cell`, scales on mobile rather than reflowing) where every block holds an explicit `{x,y,w,h}` pin in a new `pins` map on the same latest-wins layout record (`spans` sizes unplaced blocks; the Phase-A `sizes` and Slice-3b `grids` maps ride the wire inert - legacy walls map to default spans and flow newest-first). Holes are honest: an undecryptable block leaves its gap. Arrange mode: drag-to-pin with a cell-snapped valid/invalid ghost (no auto-push, deliberately), drag-off-canvas unpins to an Unplaced tray, corner resize in cell steps, and a full keyboard path (size presets / one-cell nudges / place / send-to-tray in the settings modal). Every action persists immediately; Done just exits. New: `POST /api/block-pin`/`/api/block-unpin`/`/api/block-span`. Proven by a two-node integration test over real sockets (pins sync latest-wins) and a UI_E2E live smoke (keyboard place/resize/nudge, real pointer drag, friend's synced geometry). Slices B (composer preview) and C (albums) complete the redesign - spec 2026-07-13.
```

- [ ] **Step 5: Commit**

```bash
git add tests/test_ui_smoke_collage.py ROADMAP.md
git commit -m "test(collage): live two-node smoke for the pin engine (keyboard place/resize/nudge + real drag + synced geometry); roadmap increment note"
```

---

## Self-review (done at write time)

1. **Spec coverage (Slice A section):** `pins`/`spans` record → Task 1; carry-forward + node methods + legacy defaults + newest-first → Task 2; endpoints → Task 3; sync proof → Task 4; canvas/holes/tray/flow/scaling + retirement of sizes/grids rendering → Task 5; drag-to-pin/no-push/unpin-by-drag/corner resize/Done-just-exits → Task 6; modal keyboard path → Task 7; smoke + roadmap → Task 8. Metadata-honesty comment → Task 5(b). Transitional composer state → Global Constraints.
2. **Placeholder scan:** two deliberate read-the-file-first instructions remain (Task 2 Step 1's photo-kwarg check; Task 7's `reopenAfterAction` factoring from the existing Phase-A pattern) — these point at exact existing code to mirror, not at unwritten design; everything else carries full code.
3. **Type consistency:** `set_block_pin(msg_id, x, y, w, h)` / `unpin_block(msg_id)` / `set_block_span(msg_id, w, h)` consistent across Tasks 2/3/8; `p.pin` (`{x,y,w,h}`|null) and `p.span` (`{w,h}`) across Tasks 2/5/6/7; `WALL_PINS`/`pinFree`/`cellFromPoint`/`firstFreeSpot` across 5/6/7; endpoint paths verbatim across 3/6/7/8; `startBlockDrag(block, ev, p)` third arg updated at its only call site (Task 6c).
