# Auto-Update Check + In-App Banner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The app auto-checks the update feed (launch + every ~6h) and shows a dismissible in-app banner offering a one-click apply; plus the bundled follow-up: drain the useless literal-self peer row from the outage fix. Version 0.3.15.

**Architecture:** A slow update-check tick piggybacks the existing gossip loop and calls the UNCHANGED `update.check()` off-thread; the result lands on `node.update_status` and `node.notify()` pushes it over the existing `/ws` channel, so the client re-fetches `/api/state` (which now carries `update_status`) and renders the banner. The banner's button reuses the existing `POST /api/update/apply`. No change to the signed-manifest mechanism.

**Tech Stack:** Python 3.12 / asyncio (node + gossip loop), vanilla JS + CSS (banner), pytest.

**Spec:** `docs/superpowers/specs/2026-07-16-auto-update-nudge-design.md` (approved).

## Global Constraints

- Work on branch `kreds-fixes-0.3.15` (create from main at start).
- Suite green before every commit: `.venv\Scripts\python.exe -m pytest -q` (baseline at dispatch: 915 passed, 6 skipped).
- NO AI/Co-Authored-By commit trailers; ASCII-only console prints (cp1252).
- Notify + one-click ONLY — nothing auto-APPLIES or restarts without a click.
- The update mechanism (`update.check`/`apply_web`/`stage_core`, signature verification, `/api/update/*`) is UNCHANGED — this slice adds only the auto-check trigger and the banner.
- Exact values: first check `UPDATE_CHECK_STARTUP_DELAY = 60.0`s after startup, then `UPDATE_CHECK_INTERVAL = 6 * 3600`s. `update.check()` NEVER raises (returns None on any failure — network, bad signature, replay) — an unverifiable manifest must never surface a banner.
- Version bump 0.3.15 lockstep (`hearth/__init__.py` + `hearth/web/VERSION`) rides Task 3.

## Verified codebase facts

- `update.check(feed_url=FEED_URL, web_dir=None) -> dict | None` (update.py:123): returns None on nothing-new/failure/bad-sig; on success carries `web_available` / `core_available` bools. `web_dir` is needed (installed-web-version gate).
- `/api/update/check` and `/api/update/apply` (api.py:186-230) already run `update.check` via `asyncio.to_thread` (blocking network). Apply: core → `{staged, restart_required}`; web → `{applied, reload}`.
- `/api/state` (api.py:325-342) is the payload `refresh()` fetches (`STATE = await j("/api/state")`, app.js:3660); `connectWs()` (app.js:3691-3695) does `ws.onmessage = () => refresh()`. So `node.notify()` → /ws → refresh() → /api/state re-render.
- `build_app(node, web_dir=None)`: `wd = web_dir or WEB_DIR` (api.py:59-60). The node does NOT currently hold web_dir.
- `_gossip_round` (sync.py:227-245) runs `maintain_enckey`/`maintain_wrap_grants`, then the peer loop, then prunes + `cache_message_keys`. `now = now or time.monotonic`; onion-sync cadence uses a `self._last_onion_sync` monotonic-stamp gate — the pattern to copy.
- `renderUpdateSettings` (app.js:2881-2959) is the manual panel; its apply handler does reload (web) / `window.pywebview.api.restart` (core, desktop only) — reuse this restart logic, don't reinvent it.
- Self-peer follow-up: `store.remove_peer_identity(identity_pub)` (store.py:905-911) deletes all peer rows for an identity; the gossip peer loop dials `store.list_peers()` without excluding self. The stale self-row (August's `r7nyng:1117`) is a peer row whose `identity_pub == node.identity_pub`.
- Test conventions: node/store tests via `HearthNode.create`; api via `TestClient(build_app(node))`; gossip cadence tests in `tests/test_gossip_cadence.py`; web pins via `_js_fn_body`/`_css_rule` in `tests/test_web_assets.py`.

---

### Task 1: Node auto-check + `/api/state` exposure

**Files:**
- Modify: `hearth/node.py` (HearthNode `__init__` — add `update_status` + `web_dir`; new `maybe_check_update`), `hearth/runner.py` (set `node.web_dir` in both tor + non-tor branches, before the loop), `hearth/sync.py` (`_gossip_round` — call the tick on cadence; `SyncService.__init__` — the stamp), `hearth/api.py` (`/api/state` — add `update_status`)
- Test: `tests/test_update_autocheck.py` (create)

**Interfaces:**
- Produces: `node.update_status` = `{"available": bool, "kind": "web"|"core"|None, "version": str|None}` (init `{"available": False, "kind": None, "version": None}`); `node.web_dir` (Path|None); `async node.maybe_check_update(now_monotonic: float) -> None` (cadence-gated; runs `update.check` off-thread; updates `update_status` + `notify()` only on change). `/api/state` carries `"update_status": node.update_status`.

- [ ] **Step 1: Write the failing tests**

```python
"""Auto-update check tick (0.3.15): the node periodically runs the
UNCHANGED update.check() off the gossip loop and stores a small status the
banner renders. Best-effort - check() never raises; a None result means
no banner."""
import asyncio

from hearth import node as node_mod
from hearth.node import HearthNode


def _node(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    n.web_dir = None
    return n


def test_maybe_check_update_sets_core_status(tmp_path, monkeypatch):
    n = _node(tmp_path)
    monkeypatch.setattr(node_mod.update, "check", lambda web_dir=None: {
        "version": "0.3.16", "web_available": True, "core_available": True})
    notified = []
    monkeypatch.setattr(n, "notify", lambda: notified.append(1))
    asyncio.run(n.maybe_check_update(10_000.0))
    # core takes precedence when both are available (web is gated behind it)
    assert n.update_status == {"available": True, "kind": "core",
                               "version": "0.3.16"}
    assert notified                      # status change pushed to the UI


def test_maybe_check_update_web_only(tmp_path, monkeypatch):
    n = _node(tmp_path)
    monkeypatch.setattr(node_mod.update, "check", lambda web_dir=None: {
        "version": "0.3.16", "web_available": True, "core_available": False})
    asyncio.run(n.maybe_check_update(10_000.0))
    assert n.update_status["kind"] == "web"
    assert n.update_status["available"] is True


def test_maybe_check_update_none_leaves_unavailable(tmp_path, monkeypatch):
    n = _node(tmp_path)
    monkeypatch.setattr(node_mod.update, "check", lambda web_dir=None: None)
    notified = []
    monkeypatch.setattr(n, "notify", lambda: notified.append(1))
    asyncio.run(n.maybe_check_update(10_000.0))
    assert n.update_status["available"] is False
    assert not notified                  # no change -> no push


def test_maybe_check_update_respects_startup_delay_and_interval(tmp_path, monkeypatch):
    n = _node(tmp_path)
    calls = {"n": 0}
    def fake_check(web_dir=None):
        calls["n"] += 1
        return None
    monkeypatch.setattr(node_mod.update, "check", fake_check)
    asyncio.run(n.maybe_check_update(10.0))          # < 60s startup delay
    assert calls["n"] == 0                            # too early
    asyncio.run(n.maybe_check_update(70.0))          # past 60s -> first check
    assert calls["n"] == 1
    asyncio.run(n.maybe_check_update(80.0))          # < 6h since last
    assert calls["n"] == 1                            # gated
    asyncio.run(n.maybe_check_update(70.0 + 6 * 3600 + 1))
    assert calls["n"] == 2                            # interval elapsed
```

- [ ] **Step 2: Run to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_update_autocheck.py -q`
Expected: FAIL — `AttributeError: 'HearthNode' object has no attribute 'maybe_check_update'` / `update_status`.

- [ ] **Step 3: Implement**

`hearth/node.py` — import update (`from . import update` if not already), and in `HearthNode.__init__` (after other instance state):

```python
        self.web_dir = None       # set by runner; used by the auto-update check
        self.update_status = {"available": False, "kind": None,
                              "version": None}
        self._update_started_at = None   # monotonic of first tick
        self._update_last_check = None    # monotonic of last check()
```

New constants near the top of node.py (module level):

```python
UPDATE_CHECK_STARTUP_DELAY = 60.0
UPDATE_CHECK_INTERVAL = 6 * 3600
```

New method:

```python
    async def maybe_check_update(self, now_monotonic: float) -> None:
        """Cadence-gated auto-update check (0.3.15): first check
        UPDATE_CHECK_STARTUP_DELAY after the loop starts, then every
        UPDATE_CHECK_INTERVAL. Runs the UNCHANGED update.check() off the
        event loop; on a status CHANGE, notify() pushes it to the UI over
        /ws. Best-effort - check() never raises; None means no banner."""
        if self._update_started_at is None:
            self._update_started_at = now_monotonic
        if now_monotonic - self._update_started_at < UPDATE_CHECK_STARTUP_DELAY:
            return
        if (self._update_last_check is not None
                and now_monotonic - self._update_last_check < UPDATE_CHECK_INTERVAL):
            return
        self._update_last_check = now_monotonic
        info = await asyncio.to_thread(update.check, web_dir=self.web_dir)
        if info and info.get("core_available"):
            new = {"available": True, "kind": "core", "version": info["version"]}
        elif info and info.get("web_available"):
            new = {"available": True, "kind": "web", "version": info["version"]}
        else:
            new = {"available": False, "kind": None, "version": None}
        if new != self.update_status:
            self.update_status = new
            self.notify()
```

(`import asyncio` is already at node.py top; confirm.)

`hearth/runner.py` — set `node.web_dir` right after `node = HearthNode(data_dir)` (so both branches have it): `node.web_dir = web_dir`.

`hearth/sync.py` — `SyncService.__init__`: add `self._last_update_tick_started = False` is not needed; the node owns the cadence. In `_gossip_round`, after `self.node.maintain_wrap_grants()` add:

```python
        await self.node.maybe_check_update(now())
```

(`now` is `now or time.monotonic` already bound at the top of `_gossip_round`; `now()` returns the monotonic float the node method gates on.)

`hearth/api.py` — `/api/state` return dict: add `"update_status": node.update_status,`.

- [ ] **Step 4: Run the new tests + full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_update_autocheck.py -q` then `.venv\Scripts\python.exe -m pytest -q`
Expected: all pass. Existing gossip-loop/runner tests must stay green (the tick is best-effort and additive; a fake node in those tests without `maybe_check_update` would break — if any gossip test uses a stub node, add a no-op `maybe_check_update`; report it).

- [ ] **Step 5: Commit**

```bash
git add hearth/node.py hearth/runner.py hearth/sync.py hearth/api.py tests/test_update_autocheck.py
git commit -m "feat(update): auto-check the feed on the gossip loop (launch+6h), expose update_status on /api/state - mechanism unchanged, best-effort, notify-only"
```

---

### Task 2: The in-app banner + shared apply helper

**Files:**
- Modify: `hearth/web/index.html` (a banner element near the top of the app shell), `hearth/web/app.js` (render banner from `STATE.update_status` in `refresh()`; extract a shared `applyUpdateNow` used by the banner AND `renderUpdateSettings`), `hearth/web/style.css` (banner styling)
- Test: `tests/test_web_assets.py` (append pins)

**Interfaces:**
- Consumes: `STATE.update_status` from Task 1.
- Produces: `renderUpdateBanner()` called from `refresh()`; `applyUpdateNow(errEl)` shared apply helper (POST /api/update/apply → reload for web / restart for core).

- [ ] **Step 1: Write the failing content pins**

```python
def test_update_banner_present_and_wired():
    # Auto-update nudge (0.3.15): a dismissible top banner renders from
    # STATE.update_status and applies via the existing endpoint.
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert 'id="update-banner"' in html
    js = (WEB / "app.js").read_text(encoding="utf-8")
    body = _js_fn_body(js, "renderUpdateBanner")
    assert "update_status" in body
    assert "Restart to update" in body        # core copy
    assert "Update now" in body               # web copy
    assert "renderUpdateBanner" in _js_fn_body(js, "refresh")   # called each refresh
    # shared apply helper reused by banner + settings (no duplicated restart logic)
    assert "applyUpdateNow" in _js_fn_body(js, "renderUpdateBanner")
    assert "applyUpdateNow" in _js_fn_body(js, "renderUpdateSettings")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert "nwse" not in _css_rule(css, "#update-banner")   # it's a bar, sanity
    assert _css_rule(css, "#update-banner")                 # rule exists
```

(Adapt the `renderUpdateSettings` assertion if the extraction lands differently — the intent is: no duplicated apply/restart block; both call the same helper.)

- [ ] **Step 2: Run to verify they fail**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q`
Expected: FAIL — no `#update-banner`, no `renderUpdateBanner`/`applyUpdateNow`.

- [ ] **Step 3: Implement**

`hearth/web/index.html` — a banner element at the top of the app shell (inside `#app`, above the view container; place it as the first child so it spans the width):

```html
  <div id="update-banner" class="hidden" role="status"></div>
```

`hearth/web/app.js`:

1. Extract the apply flow currently inside `renderUpdateSettings`'s `applyBtn.onclick` into a top-level helper, and call it from both places:

```javascript
// Shared by the Settings panel and the auto-update banner: POST the
// existing apply endpoint, then reload (web) or restart (core, desktop
// only). errEl receives any failure text.
async function applyUpdateNow(errEl) {
  let out;
  try {
    const r = await fetch("/api/update/apply", {method: "POST"});
    if (!r.ok) { if (errEl) errEl.textContent = "Couldn't apply update: " + await r.text(); return; }
    out = await r.json();
  } catch (e) {
    if (errEl) errEl.textContent = "Couldn't apply update: " + e.message;
    return;
  }
  if (out.reload) { location.reload(); return; }
  if (out.restart_required) {
    const api = window.pywebview && window.pywebview.api;
    if (api && api.restart) { api.restart(); }
    else if (errEl) { errEl.textContent = "Downloaded - restart Kreds to finish."; }
  }
}
```

(Refactor `renderUpdateSettings`'s apply button to call `applyUpdateNow(applyErr)` in place of its inline fetch/reload/restart block — keep its "Restart now" button UX if you prefer, but the fetch+decision must be the shared helper. Preserve the panel's existing status text behavior.)

2. The banner renderer, called from `refresh()`:

```javascript
let UPDATE_BANNER_DISMISSED = false;   // session-only; returns next status push

function renderUpdateBanner() {
  const bar = document.getElementById("update-banner");
  if (!bar) return;
  const s = (STATE && STATE.update_status) || {available: false};
  if (!s.available || UPDATE_BANNER_DISMISSED) {
    bar.classList.add("hidden");
    bar.replaceChildren();
    return;
  }
  bar.replaceChildren();
  const msg = el("span", "ub-msg", "A Kreds update is ready");
  const act = el("button", "ub-act",
    s.kind === "core" ? "Restart to update" : "Update now");
  act.type = "button";
  const err = el("span", "ub-err", "");
  act.onclick = () => { act.disabled = true; applyUpdateNow(err); };
  const x = el("button", "ub-x", "✕");   // dismiss (returns next push)
  x.type = "button";
  x.setAttribute("aria-label", "Dismiss");
  x.onclick = () => { UPDATE_BANNER_DISMISSED = true; renderUpdateBanner(); };
  bar.append(msg, act, err, x);
  bar.classList.remove("hidden");
}
```

3. In `refresh()`, after `STATE = await j("/api/state");` reset the dismiss flag when a NEW version appears so a fresh update re-nudges even if a prior one was dismissed, then render:

```javascript
  // re-nudge on a genuinely new version even if a prior banner was dismissed
  if (STATE.update_status && STATE.update_status.available
      && STATE.update_status.version !== LAST_SEEN_UPDATE_VERSION) {
    UPDATE_BANNER_DISMISSED = false;
    LAST_SEEN_UPDATE_VERSION = STATE.update_status.version;
  }
  renderUpdateBanner();
```

(Declare `let LAST_SEEN_UPDATE_VERSION = null;` near the other module lets, e.g. by `UPDATE_BANNER_DISMISSED`.)

`hearth/web/style.css` — a slim top bar (theme tokens, matches the shell):

```css
#update-banner { display: flex; align-items: center; gap: 12px;
  padding: 8px 16px; background: var(--accent-soft, var(--surface));
  border-bottom: 1px solid var(--line); font-size: 13px; }
#update-banner.hidden { display: none; }
#update-banner .ub-msg { color: var(--ink); }
#update-banner .ub-act { margin-left: auto; border: 1px solid var(--line-2);
  background: var(--surface); color: var(--ink); border-radius: 99px;
  padding: 4px 12px; font-size: 12.5px; cursor: pointer; }
#update-banner .ub-act:hover { border-color: var(--ink-2); }
#update-banner .ub-err { color: var(--ink-2); font-size: 12px; }
#update-banner .ub-x { border: 0; background: transparent; color: var(--ink-2);
  font-size: 13px; cursor: pointer; padding: 2px 6px; }
```

(If `--accent-soft` isn't a defined token, use `var(--surface)` alone — check the token set in style.css and use what exists.)

- [ ] **Step 4: Run web pins, full suite, and a live look**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q` then `.venv\Scripts\python.exe -m pytest -q`
Expected: green. Then a rendered check (0.3.4 lesson): `python -m hearth demo`, open 127.0.0.1:7201, and in the browser console set `STATE.update_status = {available:true, kind:"web", version:"9.9.9"}; renderUpdateBanner()` — confirm the bar appears at the top, "Update now" + dismiss present, dismiss hides it; repeat with `kind:"core"` → "Restart to update". Screenshot into the report. (The real apply/restart is not exercised here — note it.)

- [ ] **Step 5: Commit**

```bash
git add hearth/web/index.html hearth/web/app.js hearth/web/style.css tests/test_web_assets.py
git commit -m "feat(web): in-app update banner from update_status - one-click apply (reused helper), dismiss-but-return, web/core copy; Settings panel shares the apply helper"
```

---

### Task 3: Drain the self-peer row (bundled follow-up) + version bump

**Files:**
- Modify: `hearth/runner.py` (drain self-peer rows at startup), `hearth/sync.py` (`_gossip_round` peer loop skips self-identity — belt and suspenders), `hearth/__init__.py` + `hearth/web/VERSION` (0.3.14 → 0.3.15)
- Test: `tests/test_gossip_cadence.py` or `tests/test_sync_*.py` (append — follow whichever covers the peer loop)

**Interfaces:**
- Consumes: `store.remove_peer_identity` (store.py:905), `node.identity_pub`.
- Produces: no self-identity peer row survives startup; the gossip peer loop never dials a self-identity peer.

- [ ] **Step 1: Write the failing tests**

```python
def test_self_peer_row_drained_and_not_dialed(tmp_path):
    # 0.3.14 outage residual: pairing left the node's OWN address stored as
    # a peer; post-fix it dialed itself over Tor every cycle. A node never
    # peers with itself - drain it on startup and never dial a self row.
    from hearth.node import HearthNode
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    n.store.add_peer("selfonion.onion:9997", n.identity_pub)   # stale self row
    n.store.add_peer("friend.onion:9997", "ff" * 32)           # a real peer
    from hearth.runner import _drain_self_peers
    _drain_self_peers(n)
    addrs = [p["address"] for p in n.store.list_peers()]
    assert "selfonion.onion:9997" not in addrs      # self drained
    assert "friend.onion:9997" in addrs             # real peer kept
```

(If a `_drain_self_peers` helper doesn't fit the codebase shape, inline the drain in run_node and test via `store.remove_peer_identity(n.identity_pub)` directly + assert the gossip-loop skip separately. The gossip-loop skip test: build a SyncService, add a self peer row, run one `_gossip_round` with a transport whose `connect` records dialed addresses, assert the self address was never dialed — follow `tests/test_scoped_posts_e2e.py`'s SyncService setup.)

- [ ] **Step 2: Run to verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_gossip_cadence.py -q` (or the file you put it in)
Expected: FAIL — `_drain_self_peers` undefined.

- [ ] **Step 3: Implement**

`hearth/runner.py` — a small helper + call it once at startup (after `node = HearthNode(data_dir)`, before the loop, in both branches):

```python
def _drain_self_peers(node):
    """A node never peers with itself. Pairing stored my_addr as a peer;
    the 0.3.14 outage residual left a stale self-onion row that post-fix
    dialed itself over Tor every cycle. Drop every self-identity peer row
    at startup so it stops (and stops circulating in HAVE)."""
    node.store.remove_peer_identity(node.identity_pub)
```

Call `_drain_self_peers(node)` in run_node right after `node = HearthNode(data_dir)`.

`hearth/sync.py` `_gossip_round` peer loop — skip self-identity rows (belt and suspenders, in case one reappears via HAVE from a peer echoing our address):

```python
        for peer in self.node.store.list_peers():
            if peer.get("identity_pub") == self.node.identity_pub:
                continue                 # never dial ourselves
            addr = peer["address"]
            ...
```

Version bump: `hearth/__init__.py` `__version__ = "0.3.15"`, `hearth/web/VERSION` → `0.3.15`.

- [ ] **Step 4: Run the new test + full suite**

Run: the file you added the test to, then `.venv\Scripts\python.exe -m pytest -q`
Expected: green. (Version-string pins, if any, updated — say so in the report.)

- [ ] **Step 5: Commit**

```bash
git add hearth/runner.py hearth/sync.py hearth/web/VERSION hearth/__init__.py tests/
git commit -m "fix(sync): drain self-identity peer rows at startup + never dial ourselves - kills the 0.3.14 self-sync-over-Tor residual; version 0.3.15"
```

---

## Self-Review Notes (planning time)

- Spec coverage: auto-check trigger + cadence + best-effort + status shape → T1; `update_status` on /api/state + /ws push (via notify) → T1; banner render/copy/dismiss-return + one-click apply reusing the endpoint → T2; shared apply helper (DRY vs the settings panel) → T2; mechanism-unchanged → nothing touches update.py/verification. Bundled self-peer drain (logged follow-up) → T3. Version bump → T3.
- Type consistency: `update_status` shape (`available`/`kind`/`version`) identical across T1 (node), /api/state, and T2 (`STATE.update_status`, `s.kind`, `s.version`); `applyUpdateNow(errEl)` used in both T2 sites; `maybe_check_update(now_monotonic)` matches the gossip-loop `now()` call.
- Placeholder scan: clean — code in every step; the draft banner copy is flagged as August's to reword.
- YAGNI: no auto-apply, no persisted dismiss, no "what's new" surface (all spec out-of-scope).
