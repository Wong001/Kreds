# Dynamic Placement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One placement rule everywhere — place at the target, push only overlapped blocks straight down — with new posts auto-placed at the top and the Unplaced tray removed entirely.

**Architecture:** A deterministic `_push_place(pins, msg_id, geom)` helper in node.py; `set_block_pin` becomes push-aware (drag/presets/nudges/resize inherit); `compose_post(placement=profile)` auto-pins at (0,0) with optional `w`/`h` from `/api/post`; ungroup and a one-shot `/api/wall-autoplace` migration top-insert via the same helper. Client: tray UI and overlap vetoes removed; composer sends `w`/`h` with the post.

**Tech Stack:** existing idioms throughout; pytest; the gated smokes (both existing smoke files need their tray choreography rewritten).

**Spec:** `docs/superpowers/specs/2026-07-14-dynamic-placement-design.md` — read it first.

## Global Constraints

- Branch `kreds-quickwins-0.3.10`; no version bump; NO AI trailers; no new deps; ASCII-only; `node --check hearth/web/app.js` after every app.js edit; `.venv\Scripts\python.exe -m pytest ...`.
- Push semantics verbatim from the spec: anchor stays exactly at target; others processed in `(y, x, id)` order, each keeping `(x, w, h)` and settling below anything already settled that it overlaps; non-colliding blocks NEVER move; a result exceeding `MAX_LAYOUT` rows → ValueError → 400. The RECORD carries the result — peers never re-run the algorithm.
- Creation defaults when `/api/post` carries no `w`/`h`: media → `{w:2,h:2}`, text-only → `{w:4,h:1}` (the composer's chip defaults; keeps old-client skew sane).
- The tray, "Send to tray", drag-off-unpin, "Place on canvas", and the client overlap veto (`pinFree` as a drop/nudge/preset/resize gate) are REMOVED. `pinFree`/`WALL_PINS` may remain only if something still genuinely needs them (out-of-bounds is geometric, not occupancy — expect them to die; report either way). `/api/block-unpin` and `unpin_block` remain server-side, UI-callerless (wire compat; one-line comment).
- `/api/wall-autoplace`: owner-only concept but the route mutates own state like every other (locked-gate applies, not allowlisted); ONE layout publish for all unplaced own blocks, oldest processed first so newest ends on top.

---

### Task 1: Server — push placement end to end

**Files:**
- Modify: `hearth/node.py` (`_push_place` helper; `set_block_pin`; `compose_post` profile auto-place; `set_album` ungroup branch; new `auto_place_unplaced`), `hearth/api.py` (`/api/post` w/h fields; `POST /api/wall-autoplace`)
- Test: `tests/test_push_placement.py` (create); extend `tests/test_profile_pins_integration.py` with one pushed-layout-syncs test

**Interfaces:**
- Produces: `_push_place(pins: dict, msg_id: str, geom: dict) -> dict` (pure; raises ValueError on row-cap breach); `set_block_pin` unchanged signature, now pushes; `compose_post(..., span_w=None, span_h=None)` (only meaningful for placement=profile; validated 1<=w<=4, 1<=h<=8 when given); `auto_place_unplaced() -> int` (count placed; publishes at most ONE layout record, zero when nothing unplaced); `/api/post` accepts optional `w`/`h` form fields (ints); `POST /api/wall-autoplace` → `{"ok": true, "placed": n}`.

- [ ] **Step 1: failing tests** — `tests/test_push_placement.py` (node construction + photo fake per `tests/test_block_pins.py`):

```python
"""One placement rule: anchor at target, push only overlapped blocks
straight down, deterministically (spec 2026-07-14 dynamic placement)."""
import pytest

from hearth.node import HearthNode


def _node(tmp_path):
    return HearthNode.create(tmp_path / "n", "Anna", "anna-pc")


def _post(n, txt="p"):
    return n.compose_post(txt, scope="kreds", placement="profile")


def _pins(n):
    return n.store.profile_layout(n.identity_pub)["pins"]


def test_create_auto_places_at_top_dense(tmp_path):
    n = _node(tmp_path)
    a = _post(n, "a")                       # text default 4x1 at (0,0)
    assert _pins(n)[a] == {"x": 0, "y": 0, "w": 4, "h": 1}
    b = _post(n, "b")                       # pushes a down by 1
    assert _pins(n)[b] == {"x": 0, "y": 0, "w": 4, "h": 1}
    assert _pins(n)[a] == {"x": 0, "y": 1, "w": 4, "h": 1}


def test_create_with_span_fields_and_dense_beside(tmp_path):
    n = _node(tmp_path)
    a = n.compose_post("a", scope="kreds", placement="profile",
                       span_w=1, span_h=1)
    n.set_block_pin(a, 3, 0, 1, 1)          # park it top-right
    b = n.compose_post("b", scope="kreds", placement="profile",
                       span_w=2, span_h=2)  # lands (0,0); a NOT in the way
    assert _pins(n)[b] == {"x": 0, "y": 0, "w": 2, "h": 2}
    assert _pins(n)[a] == {"x": 3, "y": 0, "w": 1, "h": 1}   # never moved


def test_pin_onto_occupied_pushes_cascade(tmp_path):
    n = _node(tmp_path)
    a = _post(n, "a")                       # ends at... build explicit:
    b = _post(n, "b")
    c = _post(n, "c")
    n.set_block_pin(a, 0, 0, 4, 1)
    n.set_block_pin(b, 0, 1, 4, 1)
    n.set_block_pin(c, 0, 2, 4, 1)
    # drop c on top: a and b cascade down below it, in order
    n.set_block_pin(c, 0, 0, 4, 2)
    p = _pins(n)
    assert p[c] == {"x": 0, "y": 0, "w": 4, "h": 2}
    assert p[a] == {"x": 0, "y": 2, "w": 4, "h": 1}
    assert p[b] == {"x": 0, "y": 3, "w": 4, "h": 1}


def test_non_colliding_never_move(tmp_path):
    n = _node(tmp_path)
    a = _post(n, "a")
    b = _post(n, "b")
    n.set_block_pin(a, 0, 5, 2, 2)
    n.set_block_pin(b, 2, 0, 1, 1)          # nowhere near a
    assert _pins(n)[a] == {"x": 0, "y": 5, "w": 2, "h": 2}


def test_row_cap_400s(tmp_path):
    n = _node(tmp_path)
    a = _post(n, "a")
    with pytest.raises(ValueError):
        n.set_block_pin(a, 0, 501, 1, 1)    # beyond MAX_LAYOUT rows


def test_ungroup_top_inserts_members(tmp_path):
    n = _node(tmp_path)
    p1 = n.compose_post("one", scope="kreds", placement="profile",
                        photos=[b"\x89PNG fake"])
    p2 = n.compose_post("two", scope="kreds", placement="profile",
                        photos=[b"\x89PNG fake"])
    solo = _post(n, "solo")                  # occupies the top
    aid = n.set_album([p1, p2])
    n.set_album([], album_id=aid)            # ungroup
    p = _pins(n)
    assert p[p2]["y"] == 0                   # newest restored member on top
    assert p[p1]["y"] >= p[p2]["y"]          # older below or beside
    assert solo in p                          # pushed, still pinned
    assert aid not in p                       # album pin gone


def test_auto_place_unplaced_single_publish(tmp_path):
    n = _node(tmp_path)
    a = _post(n, "a")
    b = _post(n, "b")
    # simulate legacy: strip their pins via a raw layout write
    cur = n.store.profile_layout(n.identity_pub)
    from hearth.messages import make_profile_layout
    n._publish(make_profile_layout(n.device, cur["order"],
                                   grids=cur["grids"], sizes=cur["sizes"],
                                   pins={}, spans={a: {"w": 4, "h": 1},
                                                   b: {"w": 4, "h": 1}},
                                   texts=cur["texts"]))
    count_before = len(n.store.albums(n.identity_pub))  # noqa - just touch
    placed = n.auto_place_unplaced()
    assert placed == 2
    p = _pins(n)
    assert p[b]["y"] == 0                    # newest on top
    assert p[a]["y"] == 1
    assert n.auto_place_unplaced() == 0      # idempotent, no extra publish
```

Also extend `tests/test_profile_pins_integration.py`: A pins three stacked blocks, drops one on top (push), syncs; B's `profile_view` pins match A's exactly (the record carries the pushed RESULT).

- [ ] **Step 2: RED.**
- [ ] **Step 3: Implement.**

`_push_place` in node.py (above `set_block_pin`):

```python
    def _push_place(self, pins: dict, msg_id: str, geom: dict) -> dict:
        """One placement rule (spec 2026-07-14): the placed block anchors
        exactly at its target; every other pinned block, processed in
        (y, x, id) order, keeps its own (x, w, h) and settles just below
        anything already settled that it overlaps. Non-colliding blocks
        never move; chains cascade straight down, never sideways. The
        published record carries the RESULT, so every peer renders the
        same layout without re-running this."""
        def overlaps(a, b):
            return (a["x"] < b["x"] + b["w"] and b["x"] < a["x"] + a["w"]
                    and a["y"] < b["y"] + b["h"] and b["y"] < a["y"] + a["h"])
        rest = sorted(((k, dict(v)) for k, v in pins.items() if k != msg_id),
                      key=lambda kv: (kv[1]["y"], kv[1]["x"], kv[0]))
        final = {msg_id: dict(geom)}
        for oid, og in rest:
            g = og
            bumped = True
            while bumped:
                bumped = False
                for fg in final.values():
                    if overlaps(g, fg):
                        g["y"] = fg["y"] + fg["h"]
                        bumped = True
            if g["y"] > MAX_LAYOUT:
                raise ValueError("wall is full")
            final[oid] = g
        return final
```

`set_block_pin`: after the existing geometry validation, replace the `pins[msg_id] = ...` line with `pins = self._push_place(cur["pins"], msg_id, {"x": x, "y": y, "w": w, "h": h})` (keep the spans pop + MAX_LAYOUT count check + publish).

`compose_post`: add `span_w=None, span_h=None` params; when `placement == "profile"`, after the post publishes, compute the default (`{"w": 2, "h": 2}` if the post carries blobs or video else `{"w": 4, "h": 1}`), override with validated `span_w`/`span_h` when both given (ValueError on partial/bad values), then `cur = ...profile_layout(...)`; `pins = self._push_place(cur["pins"], msg_id, {"x": 0, "y": 0, **span})`; publish the layout carrying everything forward (spans: pop the msg_id). One comment: creation lives at the top, dense — August 2026-07-14.

`set_album` ungroup branch (empty members with album_id): after clearing the album's own pin/span entries (the C-residual cleanup — fold it in here since this branch is being edited: pop `pins[album_id]`/`spans[album_id]`), place each restored member — resolve the outgoing record's members (the store still has the pre-ungroup record at this point: read it BEFORE publishing the empty one), oldest first, each `pins = self._push_place(pins, mid, {"x": 0, "y": 0, **span_for(mid)})` (span from `spans` map or the media/text default) — all in the SAME single layout publish.

`auto_place_unplaced`:

```python
    def auto_place_unplaced(self) -> int:
        """One-shot migration (spec 2026-07-14): pin every unplaced own
        wall block at the top, oldest first so the newest ends on top.
        One layout publish; zero-publish no-op when nothing is unplaced."""
```
Body: walk own `posts_by(self.identity_pub, "profile")` + album pseudo-ids (albums with photos, via `store.albums` — an unpinned album counts) — collect ids not in `pins`; if none → return 0; sort oldest-first by created_at (albums: newest member's); fold each via `_push_place` at (0,0) with its span (spans map → media/text default → album 2x2); one publish; return the count.

api.py: `/api/post` gains `w: int = Form(default=None), h: int = Form(default=None)` passed as `span_w`/`span_h` (check the existing route's Form param style); `POST /api/wall-autoplace` → `{"ok": True, "placed": _400(lambda: node.auto_place_unplaced())}`.

- [ ] **Step 4: GREEN** targeted (new file + pins/albums/text/block tests + the integration extension) then FULL suite once. Expected churn: tests asserting the old free-space refusal or unplaced-after-compose behavior (e.g. `test_api_pins`' span roundtrip, `test_albums_node`'s unpinned assertions, text tests composing profile posts then reading spans) — update in place with the spec note ("dynamic placement, spec 2026-07-14"); the CONTRACTS to preserve while updating: carry-forward, scoping, determinism. Anything unrelated failing = stop.
- [ ] **Step 5: Commit** — `feat(placement): one rule everywhere - anchor at target, push only overlapped blocks straight down; new wall posts auto-place at the top dense (composer w/h ride /api/post); ungroup top-inserts; /api/wall-autoplace migrates legacy unplaced in one publish`

---

### Task 2: Client — tray removal + push-aware Arrange + composer

**Files:**
- Modify: `hearth/web/index.html` (remove `#profile-tray-wrap`), `hearth/web/app.js` (renderWall, drag/ghost, modal, composer submit), `hearth/web/style.css` (tray + tray-target rules out)
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: Task 1's `/api/post` w/h fields, `/api/wall-autoplace`.
- Produces: composer submit appends `fd.append("w", span.w)` / `"h"` when media... (text-only too — chips hidden means default `{2,2}`? NO: text-only has no chips; send nothing, server text default 4x1 applies; media: send the chip values) and DROPS the `/api/block-span` call; `renderWall` on own profile with any unpinned block fires `POST /api/wall-autoplace` ONCE then `openProfile` again (guard against loops: only when the response reports `placed > 0`; visitors keep flow-below); tray branch/markup/CSS gone; drag ghost invalid ONLY out-of-bounds (in-bounds always valid — the push happens server-side on drop); drag-off-canvas = snap-back no-op (unpin gesture dead); "Send to tray"/"Place on canvas" modal actions removed; nudges disabled only at canvas edges; preset x-clamp keeps, its "No room" alert dies; `pinFree`/`WALL_PINS` removed if callerless (report).

Steps: failing asset test (tray markup absent, `"/api/wall-autoplace"` in renderWall body, composer body has `fd.append("w"` and NOT `/api/block-span`, drag body has no `pinFree`) → RED → implement → `node --check` + asset suite green (update legacy asserts with the spec note) → commit:
`feat(placement): tray retired - new posts land placed, legacy walls self-migrate on the owner's next visit, drops push instead of refuse (ghost red = out-of-bounds only), send-to-tray/place-on-canvas die`

---

### Task 3: Smokes + ROADMAP + suite

**Files:** `tests/test_ui_smoke_collage.py`, `tests/test_ui_smoke_composer.py`, `tests/test_ui_smoke_albums.py`, `ROADMAP.md`

Rewrite the tray choreography: collage smoke — post lands PINNED at top (assert `pins` non-empty immediately, no tray selectors, no "Place on canvas"); add a push leg (pin A at (0,0), drop B onto (0,0) by drag, assert A moved below B via the API); composer smoke — after posting with the 1x1 chip, assert `pins[msg_id] == {x:0,y:0,w:1,h:1}` (spans no longer used for new posts); albums smoke — tray selectors out, ungroup leg asserts members PINNED with the restored-newest at y==0. All three: 3 consecutive greens each; gate checks; full suite TWICE (consistent; skips = TOR_E2E + the UI_E2E set). ROADMAP: rewrite the collage paragraph's tray/unplaced sentences (the model is now top-on-create push-on-collision; tray removed after real-use confusion — August 2026-07-14; flow-below remains only as the visitor fallback for unmigrated walls) + the increment sentence. Commit: `test(placement): smokes drive the push model; roadmap - dynamic placement replaces the tray`

---

## Self-review

1. **Spec coverage:** push algorithm/determinism/row-cap → T1 helper+tests; creation dense top + w/h on /api/post + defaults → T1; set_block_pin push (drag/preset/nudge/resize inherit) → T1+T2; tray death + veto removal + migration → T2; ungroup top-insert + album-pin cleanup residual → T1; smokes/ROADMAP → T3.
2. **Placeholders:** none — read-the-idiom notes point at named code (Form style, existing tests to update).
3. **Type consistency:** `span_w/span_h` (node) vs `w/h` (API form + composer) mapped explicitly; `_push_place` signature identical across all four consumers; `/api/wall-autoplace` verbatim in T1/T2/T3.
