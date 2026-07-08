# Kreds Rename + v3 Reskin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite the node-served web client to the v3 design (journal-first, radial circle nav, people-as-profile-modals), rename Loop→Kreds, derive per-person identity colors, and make the client an installable PWA — reusing the API plus two read-only additions.

**Architecture:** Full rewrite of the three web files (`index.html`, `style.css`, `app.js`) porting markup + CSS from the authoritative mockup `docs/design/kreds_design_v3.html`, wired to live data/endpoints. Two read-only API additions. New static assets: self-hosted fonts, PWA manifest, icons, service worker. No protocol/crypto/store change.

**Tech Stack:** FastAPI (localhost), vanilla JS (no framework, no bundler), sqlite3 via the existing store, pytest for asset/API tests, `node --check` for JS syntax.

**Spec:** `docs/superpowers/specs/2026-07-06-kreds-rename-reskin-design.md`
**Authoritative visual source (in repo):** `docs/design/kreds_design_v3.html` — port its markup and CSS from here; it uses only design tokens. The plan supplies the live-data wiring, IDs the JS needs, and the honesty constraints.

## Global Constraints

- Branch: `kreds-reskin` off `main`. One workstream; nothing unrelated.
- Test runner (timeout-guarded — false-green history): `timeout 120 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3`. Full suite green at every task commit.
- JS syntax: `node --check hearth/web/app.js` (and `sw.js`) must be clean at every commit that touches them.
- ASCII only in Python console prints (cp1252). UI copy may use non-ASCII (Danish text, ×, ↗ etc.) since it's UTF-8 HTML/JS — matches the current client.
- Internal package stays `hearth`. Product name is **Kreds**. No user-facing "Loop" may remain (title, wordmark, copy, revoked banner).
- **Fonts self-hosted** as woff2 under `hearth/web/fonts/`; NO `fonts.googleapis.com` / `fonts.gstatic.com` links anywhere. `@font-face` in style.css.
- **Honesty guard 1:** NO receipts "who holds this post / N copies" popover markup or logic. The scope pill shows only the scope name (Inner/Kreds), which is real today. (The mockup HAS a `.receipts` popover — do NOT port it.)
- **Honesty guard 2:** unread dots / "N new since you last looked" are driven ONLY by a per-device `localStorage` "last opened" watermark. No server "unread" field exists or is consumed. Label nothing as synced.
- **Identity color** is derived in ONE place — a JS `identityColor(fingerprint)` in app.js. Not returned by any API.
- Reuse existing endpoints unchanged: `/api/state`, `/api/feed`, `/api/post` (scope form), `/api/delete`, `/api/profile/{id}`, `/api/profile` (POST), `/api/ring`, `/api/conversations`, `/api/dm/{id}`, `/api/dm` (POST), `/api/dm-blob`, `/api/post-blob`, `/api/blob`, `/api/stories`, `/api/story`, `/api/qr`, `/api/device/revoke`, `/api/friend/*`, `/ws`.
- Preserve shipped behavior: DM chat, stories strip+viewer, profile editor, friend-add ceremony, revocation self-logout banner, theme toggle+persist. The reskin re-homes and re-styles these; it does not drop them.
- Front-end verifiability note: these tasks are verified by asset/DOM-string tests + `node --check` + a controller-run manual smoke before merge (a reskin's visual fidelity is not unit-testable). Tests assert presence/wiring/honesty-guards, not pixels.

---

### Task 1: Backend — `/api/kreds` + profile ring/since

**Files:**
- Modify: `hearth/node.py` (`kreds_list()`; `profile_view` gains `ring`+`since`)
- Modify: `hearth/api.py` (`GET /api/kreds`)
- Test: `tests/test_api_kreds.py` (new); extend `tests/test_node_profile.py` / `tests/test_api_profile.py` if present

**Interfaces:**
- Produces:
  - `HearthNode.kreds_list() -> List[dict]` — one row per friend (excluding self): `{identity_pub, name, ring, since}`. `ring` from `store.rings(self)` (default "kreds"); `since` = the ring record's `created_at` if the friend is ringed, else the identity's `added_at`.
  - `GET /api/kreds` → that list as JSON.
  - `profile_view(identity)` dict gains `ring` (this viewer's ring for that identity; "kreds" default; for self, "kreds") and `since` (as above; None for self).

- [ ] **Step 1: Create the branch**

```powershell
git checkout main; git pull; git checkout -b kreds-reskin
```

- [ ] **Step 2: Write failing tests**

Create `tests/test_api_kreds.py` (model the FastAPI TestClient fixture on `tests/test_api.py` — read it first):

```python
from hearth.node import HearthNode


def _friend(host, name, tmp_path, sub):
    f = HearthNode.create(tmp_path / sub, name, name.lower() + "-phone")
    host.store.add_identity(f.identity_pub)
    # give the host a profile name for the friend
    from hearth.messages import make_profile
    host.store.ingest_message(make_profile(f.device, name))
    return f


def test_kreds_list_rings_and_since(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = _friend(wong, "Freja", tmp_path, "f")
    mikkel = _friend(wong, "Mikkel", tmp_path, "m")
    wong.set_ring(freja.identity_pub, "inner")
    rows = {r["identity_pub"]: r for r in wong.kreds_list()}
    assert rows[freja.identity_pub]["ring"] == "inner"
    assert rows[mikkel.identity_pub]["ring"] == "kreds"     # default
    assert rows[freja.identity_pub]["name"] == "Freja"
    assert isinstance(rows[freja.identity_pub]["since"], (int, float))
    assert wong.identity_pub not in rows                    # self excluded


def test_profile_view_has_ring_and_since(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = _friend(wong, "Freja", tmp_path, "f")
    wong.set_ring(freja.identity_pub, "inner")
    pv = wong.profile_view(freja.identity_pub)
    assert pv["ring"] == "inner"
    assert isinstance(pv["since"], (int, float))
```

Add a TestClient test in `tests/test_api_kreds.py` asserting `GET /api/kreds` returns 200 and a list with `ring`/`since`/`identity_pub` keys (mirror the client setup in `tests/test_api.py`).

- [ ] **Step 3: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_api_kreds.py -q`
Expected: FAIL — `kreds_list` missing / profile has no `ring`.

- [ ] **Step 4: Implement**

In `hearth/node.py`, add `kreds_list` and a shared since-helper, and extend `profile_view`:

```python
    def _ring_and_since(self, identity_pub: str):
        """(ring, since) for a friend from THIS node's ring records; ring
        defaults to 'kreds'. `since` = the ring record's created_at if ringed,
        else the friend's identity added_at (from the identities table)."""
        ring = self.store.rings(self.identity_pub).get(identity_pub, "kreds")
        since = self.store.ring_since(self.identity_pub, identity_pub)
        return ring, since

    def kreds_list(self):
        names = self.store.profiles()
        out = []
        for ident in self.store.known_identities():
            if ident == self.identity_pub:
                continue
            ring, since = self._ring_and_since(ident)
            out.append({"identity_pub": ident,
                        "name": names.get(ident, ident[:8]),
                        "ring": ring, "since": since})
        return out
```

Add `Store.ring_since(self_identity, member)` in `hearth/store.py`: return the `created_at` of the latest `ring` record for that member if one exists, else the member's `added_at` from `identities`, else None:

```python
    def ring_since(self, self_identity: str, member: str) -> Optional[float]:
        with self._lock:
            best = None
            for seq, mj in self._db.execute(
                    "SELECT seq, msg_json FROM messages"
                    " WHERE kind=? AND identity_pub=?",
                    (KIND_RING, self_identity)):
                p = json.loads(mj)["payload"]
                if p["member"] != member:
                    continue
                rank = (p["created_at"], seq)
                if best is None or rank > best[0]:
                    best = (rank, p["created_at"])
            if best is not None:
                return best[1]
            row = self._db.execute(
                "SELECT added_at FROM identities WHERE identity_pub=?",
                (member,)).fetchone()
            return row[0] if row else None
```

In `profile_view`, add `ring`/`since` to the returned dict (for self, ring="kreds", since=None):

```python
        ring, since = (("kreds", None) if identity_pub == self.identity_pub
                       else self._ring_and_since(identity_pub))
        return {**rec, "identity_pub": identity_pub,
                "mine": identity_pub == self.identity_pub,
                "ring": ring, "since": since,
                "posts": self.posts_by(identity_pub)}
```

In `hearth/api.py`, add:

```python
    @app.get("/api/kreds")
    async def kreds():
        return node.kreds_list()
```

- [ ] **Step 5: Run tests + full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_api_kreds.py -q` — Expected: PASS.
Run: `timeout 120 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3` — Expected: all pass.

- [ ] **Step 6: Commit**

```powershell
git add hearth/node.py hearth/store.py hearth/api.py tests/test_api_kreds.py
git commit -m "feat: /api/kreds listing + profile ring/since for the reskin"
```

---

### Task 2: PWA + brand assets — fonts, icons, manifest, service worker

**Files:**
- Create: `hearth/web/fonts/*.woff2` (Bricolage Grotesque, Instrument Sans, IBM Plex Mono — the weights the mockup uses)
- Create: `hearth/web/manifest.json`, `hearth/web/sw.js`, `hearth/web/icons/*` (mark-derived PNGs + maskable)
- Test: `tests/test_pwa_assets.py` (new)

**Interfaces:**
- Produces: self-hosted font files; `manifest.json` (name "Kreds", icons, standalone); `sw.js` (versioned shell cache, network-only for `/api/`+`/ws`); an icon set. Consumed by Task 3's `index.html`/`style.css`/`app.js`.

- [ ] **Step 1: Fetch the fonts**

Download woff2 for the three families/weights the mockup references (Bricolage Grotesque 400/600/700; Instrument Sans 400/500/600; IBM Plex Mono 400/500). Use the Google Fonts CSS API to get the woff2 URLs, then fetch the files into `hearth/web/fonts/` with descriptive names (e.g. `bricolage-700.woff2`). PowerShell:

```powershell
# Example for one file; repeat per family/weight. Get the woff2 URL from
# the css2 endpoint, then download the .woff2 itself (NOT the CSS).
$css = Invoke-WebRequest "https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400" -Headers @{ "User-Agent" = "Mozilla/5.0" }
# extract the https://fonts.gstatic.com/....woff2 URL from $css.Content, then:
Invoke-WebRequest "<gstatic-woff2-url>" -OutFile "hearth/web/fonts/plex-mono-400.woff2"
```

(If the network blocks this, note it in the report and fall back to a documented placeholder `@font-face` pointing at system fallbacks — but the goal is the real files; the no-CDN constraint is firm.)

- [ ] **Step 2: Write failing asset tests**

Create `tests/test_pwa_assets.py`:

```python
import json
from pathlib import Path

WEB = Path(__file__).resolve().parents[1] / "hearth" / "web"


def test_fonts_selfhosted_present():
    fonts = list((WEB / "fonts").glob("*.woff2"))
    assert len(fonts) >= 3          # at least the three families


def test_manifest_valid():
    m = json.loads((WEB / "manifest.json").read_text(encoding="utf-8"))
    assert m["name"] == "Kreds"
    assert m["display"] == "standalone"
    assert len(m["icons"]) >= 1


def test_service_worker_shell_only():
    sw = (WEB / "sw.js").read_text(encoding="utf-8")
    # network-only for API + ws; never caches data
    assert "/api/" in sw and "/ws" in sw
    assert "caches" in sw           # uses the Cache API for the shell


def test_icons_present():
    assert list((WEB / "icons").glob("*.png"))
```

- [ ] **Step 3: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_pwa_assets.py -q`
Expected: FAIL — assets missing.

- [ ] **Step 4: Create the assets**

`hearth/web/manifest.json`:

```json
{
  "name": "Kreds",
  "short_name": "Kreds",
  "start_url": "/",
  "display": "standalone",
  "background_color": "#F3F4F1",
  "theme_color": "#C8102E",
  "icons": [
    {"src": "/static/icons/icon-192.png", "sizes": "192x192", "type": "image/png"},
    {"src": "/static/icons/icon-512.png", "sizes": "512x512", "type": "image/png"},
    {"src": "/static/icons/icon-512-maskable.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable"}
  ]
}
```

Icons: render the 3-arc mark (from the mockup's `<svg class="mark">`) to PNG at 192 and 512 plus a maskable 512 (mark centered with padding on a `#F3F4F1` field). Generate with Pillow (already a dependency) by drawing three arcs + a center dot, or rasterize the SVG. A Pillow drawing script is acceptable — write it inline in a `scripts/` one-off or generate at implementation time and commit the PNGs. The mark: three 96° arcs with 24° gaps around a center dot, red (`#C8102E`) strokes, ~7% stroke width, dot radius ~9% — match the mockup's `viewBox="0 0 100 100"` path.

`hearth/web/sw.js` (shell-only cache; bump `CACHE` to invalidate):

```javascript
const CACHE = "kreds-shell-v1";
const SHELL = ["/", "/static/style.css", "/static/app.js",
               "/static/manifest.json"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)));
  self.skipWaiting();
});
self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys().then((ks) =>
    Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))));
  self.clients.claim();
});
self.addEventListener("fetch", (e) => {
  const u = new URL(e.request.url);
  // Never cache live node data: API + websocket + blobs are network-only.
  if (u.pathname.startsWith("/api/") || u.pathname.startsWith("/ws")) return;
  e.respondWith(caches.match(e.request).then((r) => r || fetch(e.request)));
});
```

- [ ] **Step 5: Run tests + full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_pwa_assets.py -q` — Expected: PASS.
Run: `node --check hearth/web/sw.js` — Expected: clean.
Run: `timeout 120 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3` — Expected: all pass.

- [ ] **Step 6: Commit**

```powershell
git add hearth/web/fonts hearth/web/icons hearth/web/manifest.json hearth/web/sw.js tests/test_pwa_assets.py
git commit -m "feat: self-hosted fonts + PWA manifest, icons, shell service worker"
```

---

### Task 3: Client rewrite A — shell, brand, tokens, theme, journal surface

**Files:**
- Rewrite: `hearth/web/index.html`, `hearth/web/style.css`, `hearth/web/app.js`
- Test: `tests/test_web_assets.py` (rewrite the assertions to the Kreds shell)

**Interfaces:**
- Consumes: Task 1 `/api/kreds`, `/api/feed`, `/api/state`, `/api/post`; Task 2 fonts/manifest/sw/icons.
- Produces: the v3 shell + journal surface with these stable IDs later tasks/tests rely on: `#journal` (feed container), `#chipbar`, `#composer` (form) with `#post-text` + a keeps control writing a hidden `scope`, `#circle-rail` (Task 5 fills it), `#view-journal`/`#view-messages`/`#view-me` containers, `#theme-toggle`, `#revoked-banner`. `identityColor(fp)` and `lastOpened()`/`markOpenedNow()` (localStorage watermark) helpers in app.js.

- [ ] **Step 1: Rewrite the asset tests to the Kreds shell**

Rewrite `tests/test_web_assets.py` so its assertions describe the new shell (keep the file; replace stale Loop assertions):

```python
from pathlib import Path
WEB = Path(__file__).resolve().parents[1] / "hearth" / "web"


def test_title_and_no_loop():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert "<title>Kreds</title>" in html
    assert "Loop" not in html                       # no user-facing Loop
    assert 'class="mark"' in html                   # 3-arc mark present


def test_no_cdn_fonts_selfhosted():
    css = (WEB / "style.css").read_text(encoding="utf-8")
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert "fonts.googleapis.com" not in css and "fonts.googleapis.com" not in html
    assert "fonts.gstatic.com" not in css and "fonts.gstatic.com" not in html
    assert "@font-face" in css


def test_pwa_wired_in_html():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert 'rel="manifest"' in html
    assert "apple-touch-icon" in html
    assert 'name="theme-color"' in html


def test_service_worker_registered():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "serviceWorker" in js and "sw.js" in js


def test_journal_shell_and_keeps():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert 'id="journal"' in html and 'id="chipbar"' in html
    assert "That's everything" in js                # end-state copy
    # keeps selector offers inner + kreds (scope sent to /api/post)
    assert "inner" in js and "kreds" in js


def test_no_receipts_popover():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    # honesty guard: the "who holds this post" popover is NOT shipped
    assert "Who holds this post" not in html and "Who holds this post" not in js_of()
    assert ".receipts" not in css


def js_of():
    return (WEB / "app.js").read_text(encoding="utf-8")


def test_identity_color_and_localstorage_unread():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "identityColor" in js                    # deterministic color fn
    assert "localStorage" in js                     # unread watermark stopgap
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q`
Expected: FAIL (old shell).

- [ ] **Step 3: Rewrite index.html**

Port the page chrome + desktop journal + composer + chip bar structure from `docs/design/kreds_design_v3.html` into `hearth/web/index.html`, with these adaptations: reference `/static/style.css`, `/static/app.js`, `/static/manifest.json`; add `<link rel="manifest">`, `<link rel="apple-touch-icon" href="/static/icons/icon-192.png">`, `<meta name="theme-color" content="#C8102E">`; keep the real container IDs from the Interfaces block. Include the three view containers (`#view-journal` visible, `#view-messages` + `#view-me` hidden), the `#revoked-banner` (text "...logged out of Kreds."), and the mobile tab bar (Circle/Journal/Me). Do NOT include the `.receipts` popover span. The composer's keeps control sets a hidden scope value (default `kreds`).

- [ ] **Step 4: Rewrite style.css**

Replace `hearth/web/style.css`: port the `:root`/`[data-theme="night"]` tokens and all base/chrome/journal/chip/composer/end-state CSS from the mockup verbatim (it uses only tokens); add `@font-face` blocks pointing at `/static/fonts/*.woff2`; map the mockup's `[data-theme="night"]` to the app's existing `[data-theme="dark"]` selector (the app toggles `dark`/`light` — use `[data-theme="dark"]` for the night tokens so the existing toggle works). Do NOT port `.receipts`.

- [ ] **Step 5: Rewrite app.js — bootstrap + journal surface**

Rewrite `hearth/web/app.js` with: the `j()`/`el()` helpers and `deleteEverywhere` (copy from current, retext "Kreds" not "Loop"); `identityColor(fp)` (hue = `parseInt(fp.slice(0,6),16) % 360`, fixed `s=55% l=45%` → `hsl(...)`); theme apply/toggle/persist (reuse current, key `kreds_theme`); WS connect→refresh; `refresh()` fetching `/api/state`+`/api/feed`+`/api/kreds`; the chip bar (Everyone/Inner kreds/per-person from `/api/kreds`, each chip colored via `identityColor`, unread dot when that author has posts newer than `lastOpened(author)`); the composer (keeps→scope, submit to `/api/post`); the day-grouped journal render (group `feed()` rows by local date, day headers, entries with avatar (identityColor)/name/time/scope-pill/text/photos via `/api/post-blob`, delete on own posts) + the "That's everything — your kreds is quiet." end state; `lastOpened`/`markOpenedNow` localStorage helpers (key `kreds_opened`); register the service worker (`navigator.serviceWorker.register('/static/sw.js', {scope:'/'})` guarded by feature check). Stub `openProfile(id)`, the circle rail, Messages, and Me to no-ops or minimal placeholders that Tasks 4-5 replace — but keep the DM + stories + ceremony + profile-editor functions from the current app.js present (re-homed) so nothing shipped is lost; they can live unstyled until Task 5.

Provide the identityColor + watermark helpers verbatim:

```javascript
function identityColor(fp) {
  const h = parseInt(fp.slice(0, 6), 16) % 360;
  return `hsl(${h} 55% 45%)`;
}
function lastOpened(key) {
  return Number(localStorage.getItem("kreds_opened:" + key) || 0);
}
function markOpenedNow(key) {
  localStorage.setItem("kreds_opened:" + key, String(Date.now() / 1000));
}
```

- [ ] **Step 6: Run asset tests + node --check + full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q` — Expected: PASS.
Run: `node --check hearth/web/app.js` — Expected: clean.
Run: `timeout 120 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3` — Expected: all pass.

- [ ] **Step 7: Commit**

```powershell
git add hearth/web/index.html hearth/web/style.css hearth/web/app.js tests/test_web_assets.py
git commit -m "feat: Kreds shell + journal-first surface + PWA wiring + identity colors"
```

---

### Task 4: Client B1 — profile modal (people as destinations)

**Files:**
- Modify: `hearth/web/app.js` (real `openProfile` as a modal), `hearth/web/index.html` (modal container), `hearth/web/style.css` (modal styles ported from mockup)
- Test: `tests/test_web_assets.py` (append modal assertions)

**Interfaces:**
- Consumes: `/api/profile/{id}` (now with `ring`+`since`), `/api/ring`, `identityColor`.
- Produces: `openProfile(identity)` opens the mockup's `.pmodal` (identity-color banner, name, hash, bio, ring status "Inner kreds · since <date>", Message, Move-between-rings, recent posts). Move-between-rings posts to `/api/ring` and refreshes. Clicking a person anywhere calls `openProfile`.

- [ ] **Step 1: Append failing tests** to `tests/test_web_assets.py`:

```python
def test_profile_modal_and_ring_move():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert 'id="profile-modal"' in html or "pmodal" in html
    assert "openProfile" in js
    assert "/api/ring" in js                        # move between rings
    assert "since" in js                            # ring status renders since
```

- [ ] **Step 2: Run to verify failure** — `pytest tests/test_web_assets.py::test_profile_modal_and_ring_move -q` → FAIL.

- [ ] **Step 3: Implement** — port `.modalback`/`.pmodal` markup+CSS from `docs/design/kreds_design_v3.html`. Implement `openProfile(identity)` fetching `/api/profile/{id}`, filling: banner (identityColor for others / own accent for self), avatar (identityColor bg + initial, or `/api/blob/{avatar}`), name, `identity <hash…>`, bio, ring-status line (`{Ring} kreds · since {date from since}`), Message button (→ open DM), Move button (label "Move to inner kreds"/"Move to kreds" per current ring; onclick `POST /api/ring {identity_pub, ring: toggled}` then refresh+reopen), and recent posts (reuse the post render; only decryptable ones come back). For self, show the Edit-profile editor (reuse the shipped `profileEditor`). Format `since` as a month-year (`new Date(since*1000).toLocaleDateString(undefined,{month:'long',year:'numeric'})`).

- [ ] **Step 4: Run + node --check + full suite** (same commands as Task 3 Step 6).

- [ ] **Step 5: Commit**

```powershell
git add hearth/web/app.js hearth/web/index.html hearth/web/style.css tests/test_web_assets.py
git commit -m "feat: profile modal - people as destinations, ring status + move-between-rings"
```

---

### Task 5: Client B2 — circle rail + radial overlay, Messages + Me re-home

**Files:**
- Modify: `hearth/web/app.js`, `hearth/web/index.html`, `hearth/web/style.css`
- Test: `tests/test_web_assets.py` (append circle assertions)

**Interfaces:**
- Consumes: `/api/kreds` (ring + identity for node placement/color), `openProfile`.
- Produces: the compact circle rail (small radial SVG built from `/api/kreds`, you at center, friends on inner/kreds rings, localStorage freshness dots, Expand control) and the full `.overlay` radial map (ring labels, nodes clickable → `openProfile`, Esc/close). Messages view re-homed + re-tokenized (existing DM chat), Me view re-homed. Mobile tab bar wired (Circle/Journal/Me).

- [ ] **Step 1: Append failing tests** to `tests/test_web_assets.py`:

```python
def test_circle_rail_and_overlay():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert 'id="circle-rail"' in html
    assert "overlay" in html.lower()                # expandable radial overlay
    assert "buildCircle" in js or "renderCircle" in js   # SVG built from /api/kreds
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert ".ringguide" in css                      # radial ring guides (ported)


def test_mobile_tabbar_present():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert "Circle" in html and "Journal" in html   # mobile tab labels
```

- [ ] **Step 2: Run to verify failure** → FAIL.

- [ ] **Step 3: Implement** — port `.rail`/`.minimap`/`.overlay`/`.node`/`.ringguide` markup+CSS from the mockup. Build the radial SVG in JS from `/api/kreds`: place inner-ring members on the inner radius, kreds-ring members on the outer, distribute by angle, color nodes with `identityColor`, draw a freshness dot when that friend has feed posts newer than `lastOpened(identity)`. Rail "Expand" and the minimap click open the `.overlay`; overlay nodes and rail nodes call `openProfile`. Re-home Messages (the existing `loadConversations`/`openThread`/DM compose, re-tokenized) and Me (profile/friends/devices) into their view containers; wire the desktop nav + mobile tab bar through a `setView` that toggles `#view-journal`/`#view-messages`/`#view-me`. Keep stories strip in the journal view.

- [ ] **Step 4: Run + node --check + full suite.**

- [ ] **Step 5: Commit**

```powershell
git add hearth/web/app.js hearth/web/index.html hearth/web/style.css tests/test_web_assets.py
git commit -m "feat: radial circle rail + overlay, Messages/Me re-homed, mobile tab bar"
```

---

### Task 6: Integration — full green, manual smoke, docs, Loop stragglers

**Files:**
- Modify: `README.md`, `ROADMAP.md`; sweep for any remaining "Loop"
- Test: whole suite

- [ ] **Step 1: Sweep for user-facing Loop stragglers**

```powershell
Select-String -Path hearth\web\*,hearth\*.py -Pattern "Loop" | Select-String -NotMatch "loop_|gossip_loop|event loop|for |while |asyncio"
```

Fix any user-facing "Loop" (UI strings, comments that say the product name). The `deleteEverywhere` copy and README must say Kreds. (Internal `hearth`/`loop_`-prefixed localStorage keys are fine, but prefer the new `kreds_` keys introduced this slice.)

- [ ] **Step 2: README + ROADMAP**

README: update the top-line name to Kreds; add a short "The app" note that the client is journal-first with the circle as navigation, installable as a PWA, served by your own node (never from kreds.eu). ROADMAP: mark the rename + v3 reskin shipped; note PWA-installable; the curated-profile, receipts, synced-read-state, Windows-packaging, and iOS-reachability items remain next.

- [ ] **Step 3: Full suite + JS checks**

Run: `timeout 150 .venv/Scripts/python.exe -m pytest tests -q -p no:cacheprovider 2>&1 | tail -3` — Expected: all pass.
Run: `node --check hearth/web/app.js` and `node --check hearth/web/sw.js` — clean.

- [ ] **Step 4: Manual smoke (controller runs; record outcome)**

Launch a single node and load the UI in a browser (or the /run skill). Confirm: journal renders day-grouped with the end state; the chip bar filters; composing to Inner vs Kreds works; the circle rail shows and expands to the overlay; clicking a person opens the profile modal with ring status; Move-between-rings works; theme toggles; the app reports installable (manifest + SW registered — check devtools Application tab). Record what was observed. (Stop any stray `hearth demo` processes holding ports first if needed.)

- [ ] **Step 5: Commit**

```powershell
git add README.md ROADMAP.md hearth/
git commit -m "docs: Kreds reskin shipped - journal-first PWA; sweep Loop stragglers"
```

---

## Completion

After Task 6: whole-branch review (superpowers:requesting-code-review) on the most capable model — reviewer focuses on the honesty guards (no receipts popover; unread is localStorage-only), the identity-color single-source derivation, PWA correctness (SW never caches `/api/`, scope covers `/`), no user-facing Loop, no external font host, and that no shipped feature (DM, stories, editor, ceremony, revocation banner) was dropped in the rewrite. Then superpowers:finishing-a-development-branch — merge `kreds-reskin` to `main`, push. Next slice: Windows packaging (wraps exactly this client).
