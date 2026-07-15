# Profile Polish Batch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Three August-reported 0.3.12 polish items: topbar +/cog spacing, journal-rail ring clipping, and profile pictures rendering in journal entries (journal page + profile rail) with the identity ring kept and the letter circle as fallback.

**Architecture:** Server enriches post rows with `author_avatar` (new latest-wins `store.profile_avatars()` map, fetched once per feed/posts_by call — the stories-strip precedent); `buildEntry` renders the blob image inside the existing `.eavatar` circle when present. Spacing and clipping are two CSS declarations.

**Tech Stack:** Python 3.12/pytest (store/node/api), vanilla JS + CSS content pins (test_web_assets.py).

**Spec:** `docs/superpowers/specs/2026-07-15-profile-polish-batch-design.md` (approved).

## Global Constraints

- Work on branch `kreds-fixes-0.3.13` (created from main; never commit to main directly).
- Suite green before every commit: `.venv\Scripts\python.exe -m pytest -q` (baseline at dispatch: 895 passed, 6 skipped).
- NO AI/Co-Authored-By commit trailers; ASCII-only console prints (cp1252).
- `author_avatar` is ADDITIVE: absent avatar → key absent from the map → row field is None → client letter fallback. No `min_core_for_web` gate.
- The `--ring` and background color assignment in `buildEntry` stays UNCONDITIONAL (ring over photos, per August).
- Version bump 0.3.13 lockstep (`hearth/__init__.py` + `hearth/web/VERSION`) rides Task 2.

## Verified codebase facts

- `store.profiles()` (store.py:472-482) is the shape to mirror: latest-wins by `(created_at, seq, device_pub)` over `KIND_PROFILE` rows.
- `_decrypt_post_row(msg, names, now)` at node.py:1208-1229; callers: `feed()` (node.py:1231-1238, fetches `names = self.store.profiles()`) and `posts_by()` (node.py:~1241, same idiom). `profile_view` journal/wall ride `posts_by`.
- `buildEntry` at app.js:284-300: `.eavatar` is a `button` whose text is the initial; `avatar.style.background = color` + `--ring` property already set unconditionally.
- Blob-image idiom: `img.src = "/api/blob/" + hash` (app.js:1773, 2179).
- CSS: `.profile-addfriend` rule exists (grep `profile-addfriend` in style.css); `.profile-side #profile-journal-rail { max-height: 60vh; overflow-y: auto; }` at style.css:364; `.eavatar` block at style.css:316-321.
- Test conventions: store/node tests build via `HearthNode.create`; API via `TestClient(build_app(node))` with PNG helper `Image.new(...).save(buf, "PNG")` (tests/test_api_profile.py:12); web pins via `_js_fn_body`/`_css_rule` in tests/test_web_assets.py.

---

### Task 1: Server enrichment — `author_avatar` on post rows

**Files:**
- Modify: `hearth/store.py` (new `profile_avatars` next to `profiles`, ~483), `hearth/node.py` (`_decrypt_post_row` 1208-1229, `feed` 1231-1238, `posts_by` ~1241)
- Test: `tests/test_node_profile.py` (append), `tests/test_api_profile.py` (append)

**Interfaces:**
- Produces: `store.profile_avatars() -> Dict[str, str]` (identity_pub -> avatar blob hash; no-avatar authors absent); post rows from `feed()`/`posts_by()` (and therefore `profile_view`'s journal) carry `"author_avatar": <hash or None>`.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_node_profile.py` (follow its existing node-construction idiom):

```python
def test_feed_rows_carry_author_avatar(tmp_path):
    import io
    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", (64, 64), (200, 30, 60)).save(buf, format="PNG")
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    node.compose_post("before avatar", scope="kreds")
    node.set_profile("Wong", avatar_bytes=buf.getvalue())
    node.compose_post("after avatar", scope="kreds")
    rows = node.feed()
    avatar = node.store.profile(node.identity_pub)["avatar"]
    # enrichment is by AUTHOR's current profile, not post age: both rows
    # carry the avatar
    assert [r["author_avatar"] for r in rows] == [avatar, avatar]
    # journal surface on the profile rides posts_by and inherits it
    journal = node.profile_view(node.identity_pub)["journal"]
    assert journal[0]["author_avatar"] == avatar


def test_feed_rows_avatarless_author_is_none(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    node.compose_post("no avatar yet", scope="kreds")
    assert node.feed()[0]["author_avatar"] is None


def test_profile_avatars_latest_wins(tmp_path):
    import io
    from PIL import Image
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    assert node.store.profile_avatars() == {}          # no profile yet
    b1, b2 = io.BytesIO(), io.BytesIO()
    Image.new("RGB", (64, 64), (1, 2, 3)).save(b1, format="PNG")
    Image.new("RGB", (64, 64), (4, 5, 6)).save(b2, format="PNG")
    node.set_profile("Wong", avatar_bytes=b1.getvalue())
    first = node.store.profile_avatars()[node.identity_pub]
    node.set_profile("Wong", avatar_bytes=b2.getvalue())
    second = node.store.profile_avatars()[node.identity_pub]
    assert first != second                             # latest wins
    assert second == node.store.profile(node.identity_pub)["avatar"]
```

Append to `tests/test_api_profile.py` (reuse its `client(tmp_path)` helper and PNG helper):

```python
def test_state_feed_carries_author_avatar(tmp_path):
    c, node = client(tmp_path)
    node.set_profile("Wong", avatar_bytes=png())
    node.compose_post("med billede", scope="kreds")
    state = c.get("/api/state").json()
    row = state["feed"][0]
    assert row["author_avatar"] == node.store.profile(
        node.identity_pub)["avatar"]
```

(Adapt the `/api/state` field name to how that test file already reads the feed — if it uses a different endpoint for feed rows, pin whichever surface the web client actually consumes; check `app.js`'s `j("/api/state")` usage. If `set_profile`'s kwargs differ, copy the call shape from the file's existing profile tests.)

- [ ] **Step 2: Run to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_node_profile.py tests/test_api_profile.py -q`
Expected: new tests FAIL — `KeyError: 'author_avatar'` / `AttributeError: ... no attribute 'profile_avatars'`.

- [ ] **Step 3: Implement**

`store.py`, directly after `profiles()`:

```python
    def profile_avatars(self) -> Dict[str, str]:
        """identity_pub -> avatar blob hash from each author's latest
        profile record (same latest-wins tie-break as profiles()).
        Authors whose latest profile has no avatar are absent -- post-row
        enrichment reads .get() and falls back to the letter circle."""
        with self._lock:
            best: Dict[str, tuple] = {}
            for ipub, seq, dpub, mj in self._db.execute(
                    "SELECT identity_pub, seq, device_pub, msg_json"
                    " FROM messages WHERE kind=?", (KIND_PROFILE,)):
                p = json.loads(mj)["payload"]
                key = (p["created_at"], seq, dpub)
                if ipub not in best or key > best[ipub][0]:
                    best[ipub] = (key, p.get("avatar"))
            return {k: v[1] for k, v in best.items() if v[1]}
```

`node.py` — `_decrypt_post_row(self, msg, names, now, avatars)` (fourth positional param; both callers updated in this same commit so no default needed — but give it `avatars=None` + `avatars = avatars or {}` so any out-of-tree caller degrades to letter circles instead of crashing). Row gains:

```python
            "author_avatar": avatars.get(ipub),
```

`feed()` and `posts_by()` each add `avatars = self.store.profile_avatars()` beside their `names = self.store.profiles()` line and pass `avatars` to `_decrypt_post_row`.

- [ ] **Step 4: Run the two test files, then the full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_node_profile.py tests/test_api_profile.py -q` then `.venv\Scripts\python.exe -m pytest -q`
Expected: all pass (the field is additive; no existing row-shape test pins an exact key set — if one does, updating it is the intended change, say so in the report).

- [ ] **Step 5: Commit**

```bash
git add hearth/store.py hearth/node.py tests/test_node_profile.py tests/test_api_profile.py
git commit -m "feat(feed): post rows carry author_avatar (latest profile's blob hash) - stories-strip precedent, additive, letter-circle fallback stays"
```

---

### Task 2: Web — avatar rendering + spacing + rail padding + version bump

**Files:**
- Modify: `hearth/web/app.js` (`buildEntry` 284-300), `hearth/web/style.css` (`.profile-addfriend` rule, `.profile-side #profile-journal-rail` 364, `.eavatar` block 316-321), `hearth/__init__.py` + `hearth/web/VERSION` (0.3.12 → 0.3.13)
- Test: `tests/test_web_assets.py` (append)

**Interfaces:**
- Consumes: `p.author_avatar` from Task 1.

- [ ] **Step 1: Write the failing content pins** (append to `tests/test_web_assets.py`, its existing style)

```python
def test_entry_avatar_renders_profile_picture_with_ring_fallback():
    # Profile polish batch (0.3.13): buildEntry shows the author's
    # uploaded avatar inside the circle; the identity ring stays ON TOP
    # of photos and the colored letter circle stays the fallback
    # (August, 2026-07-15).
    js = (WEB / "app.js").read_text(encoding="utf-8")
    body = _js_fn_body(js, "buildEntry")
    assert "author_avatar" in body
    assert '"/api/blob/" + p.author_avatar' in body
    css = (WEB / "style.css").read_text(encoding="utf-8")
    rule = _css_rule(css, ".eavatar img")
    assert "object-fit: cover" in rule and "border-radius: 50%" in rule


def test_topbar_addfriend_spacing_and_rail_padding():
    css = (WEB / "style.css").read_text(encoding="utf-8")
    # + button carries the same 8px rhythm Arrange already has, so it no
    # longer sits flush against the cog (August, 2026-07-15)
    assert "margin-right: 8px" in _css_rule(css, ".profile-addfriend")
    # the rail scroll box pads inward so .eavatar::after's -4px identity
    # ring isn't clipped at the container edge
    assert "padding: 6px" in _css_rule(css, ".profile-side #profile-journal-rail")
```

(If `.profile-addfriend`'s rule shares a selector list with another button, `_css_rule` needs the exact selector text as written in the file — read it first and pin what's really there.)

- [ ] **Step 2: Run to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q`
Expected: both new pins FAIL (no `author_avatar` in buildEntry; no `.eavatar img` rule; missing margin/padding).

- [ ] **Step 3: Implement**

`app.js` `buildEntry` — replace the avatar construction (lines 291-293) with:

```javascript
  const avatar = el("button", "eavatar");
  if (p.author_avatar) {
    const im = document.createElement("img");
    im.src = "/api/blob/" + p.author_avatar;
    im.alt = "";
    avatar.append(im);
  } else {
    avatar.textContent = (p.author_name || "?").slice(0, 1).toUpperCase();
  }
  // ring + color stay unconditional: identity color remains visible over
  // photos, and IS the circle for the letter fallback (August 2026-07-15)
  avatar.style.background = color;
  avatar.style.setProperty("--ring", color);
```

`style.css`:
- Add to the `.eavatar` block's vicinity: `.eavatar img { width: 100%; height: 100%; border-radius: 50%; object-fit: cover; display: block; }` and `.eavatar { overflow: hidden; }`? NO — do NOT add `overflow: hidden` to `.eavatar` (it would clip the `::after` ring, recreating bug 2 in miniature); the img's own `border-radius: 50%` handles the rounding.
- `.profile-addfriend` rule gains `margin-right: 8px;`.
- `.profile-side #profile-journal-rail` becomes `{ max-height: 60vh; overflow-y: auto; padding: 6px; }`.

Version bump lockstep: `hearth/__init__.py` `__version__ = "0.3.13"`, `hearth/web/VERSION` → `0.3.13`.

- [ ] **Step 4: Run web pins, full suite, and the live look**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q` then `.venv\Scripts\python.exe -m pytest -q`
Expected: green. Then the geometry lesson (0.3.4) applies — verify RENDERED geometry, not code: run `.venv\Scripts\python.exe -m hearth demo`, open 127.0.0.1:7201 with Playwright (in .venv), set an avatar via the Settings editor, and assert: `.eavatar img` present in a journal entry and its `getBoundingClientRect()` is square inside the button; the rail's first `.eavatar`'s ring box (rect grown 4px) sits fully inside the rail's content box; `#profile-addfriend`'s right edge is >= 6px from `#profile-cog`'s left edge. Screenshot the three spots into the report. (If UI_E2E smoke conventions fit better, follow `tests/test_ui_smoke_*.py`'s harness instead of ad-hoc scripting.)

- [ ] **Step 5: Commit**

```bash
git add hearth/web/app.js hearth/web/style.css hearth/web/VERSION hearth/__init__.py tests/test_web_assets.py
git commit -m "fix(profile): journal avatars render the uploaded picture (ring kept, letter fallback), rail pads past the ring clip, + button spaced from the cog; version 0.3.13"
```

---

## Self-Review Notes (planning time)

- Spec coverage: fix 1 → T2 CSS; fix 2 → T2 CSS (with the overflow-hidden trap called out); fix 3 → T1 server + T2 client; ring-over-photo + fallback decision encoded in T2's code and pin; mixed-version (no gate) → nothing to do, noted in spec.
- Type consistency: `profile_avatars()` map consumed via `.get(ipub)` in T1; `p.author_avatar` in T2 matches T1's row key.
- The 0.3.4 lesson (measure rendered geometry, don't trust code inspection) is baked into T2 Step 4 — this batch is exactly the class of bug that lesson came from.
