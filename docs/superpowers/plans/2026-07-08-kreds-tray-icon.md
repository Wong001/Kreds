# System Tray Icon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Always-present system tray icon for the desktop shell: close-with-"keep" hides the window fully, tray restores it, Quit menu shuts down cleanly, one-time balloon on first hide.

**Architecture:** Per the approved spec (`docs/superpowers/specs/2026-07-08-kreds-tray-icon-design.md`). `pystray` (lazy-imported, mirroring the lazy `import webview`) runs the icon on a daemon thread; `Api` gains `hide_to_tray()`/`show_window()`; the web close handler calls `hide_to_tray` with a `minimize` fallback for older shells; the tray stops inside `Api.quit()` so every exit path removes it.

**Tech Stack:** Python (hearth/desktop.py, hearth/paths.py), pystray + Pillow, PyInstaller spec datas, vanilla JS (hearth/web/app.js), pytest.

## Global Constraints

- `import pystray` may appear ONLY inside `_create_tray` (lazy) — `import hearth.desktop` must work without pystray installed.
- A dead tray thread must never strand a hidden window: `hide_to_tray()` falls back to `minimize()` when the tray thread is not alive.
- Balloon/tray failures are always swallowed — they must never break hide or block shutdown.
- The one-time balloon flag is a file named `tray_notified` in the data dir (shell-owned state, like `instance.lock`).
- No AI co-author trailers on commits (project policy since 2026-07-08).
- Repo `C:\Users\Wong\Desktop\Hearth`, venv `.venv`. Run tests from the repo root with `.venv\Scripts\python.exe -m pytest ...`.

---

### Task 1: Icon path resolution + dependency + spec datas

**Files:**
- Modify: `hearth/paths.py` (append function)
- Modify: `requirements.txt` (add `pystray` after `pywebview`)
- Modify: `packaging/kreds.spec` (datas entry, after the tor.exe append around line 37)
- Test: `tests/test_paths.py`

**Interfaces:**
- Produces: `paths.tray_icon_path() -> Path` — `<resource_dir>/packaging/kreds.ico` frozen, `<repo>/packaging/kreds.ico` from source. Task 2's `_tray_icon_image()` consumes it.

- [ ] **Step 1: Write the failing test** — append to `tests/test_paths.py`:

```python
def test_tray_icon_path_source_and_frozen(monkeypatch, tmp_path):
    # Spec 2026-07-08-kreds-tray-icon: the tray reuses packaging/kreds.ico,
    # bundled under <resource_dir>/packaging/ when frozen (kreds.spec datas
    # must match), resolved from the repo from source.
    import sys
    from pathlib import Path as _P
    from hearth import paths
    p = paths.tray_icon_path()
    assert p.name == "kreds.ico" and p.parent.name == "packaging"
    assert p.is_file()                     # the repo really carries it
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "_MEIPASS", str(tmp_path), raising=False)
    assert paths.tray_icon_path() == _P(str(tmp_path)) / "packaging" / "kreds.ico"


def test_pystray_declared_and_bundled():
    root = Path(__file__).resolve().parents[1]
    assert "pystray" in (root / "requirements.txt").read_text()
    spec = (root / "packaging" / "kreds.spec").read_text()
    assert '"packaging"' in spec           # kreds.ico datas destination
```

(`tests/test_paths.py` already imports `Path` from `pathlib` at the top — check, and add the import only if absent.)

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_paths.py -q`
Expected: FAIL (`tray_icon_path` missing)

- [ ] **Step 3: Implement.**

Append to `hearth/paths.py`:

```python
def tray_icon_path() -> Path:
    """packaging/kreds.ico for the system tray - bundled at
    <resource_dir>/packaging/kreds.ico when frozen (kreds.spec datas must
    match), the repo's packaging/ dir from source. Callers handle a
    missing file (dev fallback draws a placeholder)."""
    if is_frozen():
        return resource_dir() / "packaging" / "kreds.ico"
    return Path(__file__).parent.parent / "packaging" / "kreds.ico"
```

In `requirements.txt`, after the `pywebview` line add:

```
pystray
```

In `packaging/kreds.spec`, directly after the `datas.append((tor_exe, "tor"))` block, add:

```python
# Tray icon at runtime (hearth/paths.py's tray_icon_path expects
# resource_dir()/packaging/kreds.ico when frozen).
datas.append((os.path.join(PACKAGING_DIR, "kreds.ico"), "packaging"))
```

Then install the new dependency: `.venv\Scripts\pip.exe install pystray`

- [ ] **Step 4: Run tests**

Run: `.venv\Scripts\python.exe -m pytest tests/test_paths.py -q`
Expected: all PASS

- [ ] **Step 5: Commit**

```bash
git add hearth/paths.py requirements.txt packaging/kreds.spec tests/test_paths.py
git commit -m "feat(tray): icon path resolution (frozen + source), pystray dependency, spec datas"
```

---

### Task 2: Tray lifecycle + Api methods (hearth/desktop.py)

**Files:**
- Modify: `hearth/desktop.py` (Api class ~line 78; `_notify_already_running` ~line 185; new tray section after it; `launch()` ~line 330)
- Test: `tests/test_desktop.py`

**Interfaces:**
- Consumes: `paths.tray_icon_path()` (Task 1).
- Produces: `desktop.TRAY_NOTIFIED_FLAG = "tray_notified"`, `_tray_icon_image()`, `_create_tray(api)`, `Api.hide_to_tray()`, `Api.show_window()`, `Api._notify_first_hide()`; `Api` instances carry `._tray`, `._tray_thread`, `._data_dir` (None until `launch()` sets them). Task 3's JS calls `hide_to_tray` via the pywebview bridge.

- [ ] **Step 1: Write the failing tests** — append to `tests/test_desktop.py`:

```python
# ---- system tray (spec 2026-07-08-kreds-tray-icon) ----

class _FakeWindow:
    def __init__(self):
        self.hidden = self.shown = self.restored = False
        self.minimized = self.destroyed = False
    def hide(self): self.hidden = True
    def show(self): self.shown = True
    def restore(self): self.restored = True
    def minimize(self): self.minimized = True
    def destroy(self): self.destroyed = True


class _FakeTray:
    def __init__(self, raise_on_stop=False, raise_on_notify=False):
        self.stopped = False
        self.notes = []
        self._rs, self._rn = raise_on_stop, raise_on_notify
    def stop(self):
        if self._rs: raise RuntimeError("wedged tray")
        self.stopped = True
    def notify(self, message, title=None):
        if self._rn: raise RuntimeError("no balloon support")
        self.notes.append(message)


class _FakeThread:
    def __init__(self, alive): self._alive = alive
    def is_alive(self): return self._alive


def _tray_api(tmp_path, tray=None, thread_alive=True):
    from hearth import desktop
    api = desktop.Api({"loop": None, "ev": None})
    api.window = _FakeWindow()
    api._data_dir = tmp_path
    api._tray = tray
    api._tray_thread = _FakeThread(thread_alive) if tray is not None else None
    return api


def test_hide_to_tray_hides_and_balloons_exactly_once(tmp_path):
    from hearth import desktop
    api = _tray_api(tmp_path, tray=_FakeTray())
    api.hide_to_tray()
    assert api.window.hidden and not api.window.minimized
    assert api._tray.notes == [
        "Kreds keeps running in the background. Click the tray icon to open it again."]
    assert (tmp_path / desktop.TRAY_NOTIFIED_FLAG).exists()
    api.hide_to_tray()
    assert len(api._tray.notes) == 1        # flag file makes it one-time


def test_hide_to_tray_falls_back_to_minimize_when_tray_dead(tmp_path):
    # A hidden window with no living tray has NO way back - never strand
    # the user (spec edge case).
    api = _tray_api(tmp_path, tray=_FakeTray(), thread_alive=False)
    api.hide_to_tray()
    assert api.window.minimized and not api.window.hidden


def test_hide_to_tray_without_tray_at_all_minimizes(tmp_path):
    api = _tray_api(tmp_path, tray=None)
    api.hide_to_tray()
    assert api.window.minimized and not api.window.hidden


def test_balloon_failure_never_breaks_the_hide(tmp_path):
    api = _tray_api(tmp_path, tray=_FakeTray(raise_on_notify=True))
    api.hide_to_tray()                      # must not raise
    assert api.window.hidden


def test_quit_stops_tray_best_effort_and_always_destroys(tmp_path):
    api = _tray_api(tmp_path, tray=_FakeTray(raise_on_stop=True))
    api.quit()                              # wedged tray must not block quit
    assert api.window.destroyed
    api2 = _tray_api(tmp_path, tray=_FakeTray())
    api2.quit()
    assert api2._tray.stopped and api2.window.destroyed


def test_show_window_shows_and_restores(tmp_path):
    api = _tray_api(tmp_path, tray=_FakeTray())
    api.show_window()
    assert api.window.shown and api.window.restored


def test_pystray_import_is_lazy():
    # import hearth.desktop must never require pystray (mirror of the lazy
    # `import webview` rule): the only `import pystray` sits inside
    # _create_tray's body.
    import inspect
    from hearth import desktop
    src = inspect.getsource(desktop)
    assert src.count("import pystray") == 1
    # the import line must be indented (function-local), never at column 0
    for line in src.splitlines():
        if "import pystray" in line:
            assert line.startswith((" ", "\t")), "pystray import must be function-local"


def test_already_running_notice_mentions_tray():
    import inspect
    from hearth import desktop
    src = inspect.getsource(desktop._notify_already_running)
    assert "system tray" in src
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_desktop.py -q`
Expected: new tests FAIL (`hide_to_tray` missing), pre-existing 34 still pass

- [ ] **Step 3: Implement in `hearth/desktop.py`.**

(a) In `Api.__init__`, after `self._maximized = False`, add:

```python
        self._tray = None              # pystray.Icon, set by launch()
        self._tray_thread = None       # its daemon thread, set by launch()
        self._data_dir = None          # set by launch(); one-time balloon flag lives here
```

(b) After `Api.minimize()`, add:

```python
    def show_window(self):
        """Tray 'Open Kreds' / double-click: restore a hidden window.
        pywebview marshals show/hide/restore onto the GUI thread, so
        calling from the pystray thread is safe (verify against the
        installed pywebview at implementation time and update this note
        if that ever changes)."""
        if not self.window: return
        try:
            self.window.show()
            self.window.restore()
        except Exception:
            pass                        # window mid-teardown; nothing to do

    def hide_to_tray(self):
        """Close with close_behavior=keep: hide fully (no taskbar entry).
        A hidden window with no living tray has no way back, so fall back
        to a plain taskbar-minimize when the tray thread is dead - never
        strand the user (spec edge case)."""
        if not self.window: return
        alive = self._tray_thread is not None and self._tray_thread.is_alive()
        if not alive:
            self.minimize()
            return
        self.window.hide()
        self._notify_first_hide()

    def _notify_first_hide(self):
        """One-time balloon per data dir. The flag is shell-owned state in
        the data dir (like instance.lock), deliberately not node meta.
        Balloon failures never break the hide - pystray notify support
        varies by platform/backend."""
        try:
            flag = self._data_dir / TRAY_NOTIFIED_FLAG
            if flag.exists(): return
            flag.write_text("1")
            if self._tray is not None:
                self._tray.notify("Kreds keeps running in the background. "
                                  "Click the tray icon to open it again.", "Kreds")
        except Exception:
            pass
```

(c) In `Api.quit()`, BEFORE the `if self.window: self.window.destroy()` line, add:

```python
        if self._tray is not None:
            try:
                self._tray.stop()
            except Exception:
                pass                    # a wedged tray must never block shutdown
```

(d) Change `_notify_already_running`'s message string from
`"Kreds is already running."` to:

```python
            "Kreds is already running. It may be in the background - "
            "check your system tray.",
```

(e) After `_notify_already_running`, add the tray section:

```python
# --------------------------------------------------------------------------
# System tray (spec 2026-07-08-kreds-tray-icon): always present while the
# app runs. `import pystray` is LAZY (inside _create_tray only), mirroring
# launch()'s lazy `import webview` - the test suite never needs GUI deps.
# --------------------------------------------------------------------------

TRAY_NOTIFIED_FLAG = "tray_notified"

def _tray_icon_image():
    """packaging/kreds.ico (bundled when frozen, repo-resolved from
    source); a missing file gets a Pillow-drawn 3-arc placeholder so a
    dev checkout never crashes the shell over an icon."""
    from PIL import Image
    p = paths.tray_icon_path()
    if p.is_file():
        return Image.open(p)
    from PIL import ImageDraw
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.arc((6, 6, 58, 58), 300, 240, fill=(200, 16, 46, 255), width=7)
    d.ellipse((26, 26, 38, 38), fill=(23, 25, 27, 255))
    return img

def _create_tray(api):
    """Build (not run) the tray icon: Open Kreds (default item -> a
    double-click triggers it) + Quit Kreds."""
    import pystray
    menu = pystray.Menu(
        pystray.MenuItem("Open Kreds", lambda: api.show_window(), default=True),
        pystray.MenuItem("Quit Kreds", lambda: api.quit()),
    )
    return pystray.Icon("Kreds", _tray_icon_image(), "Kreds", menu)
```

(f) In `launch()`, directly after `api.window = window`, add:

```python
        api._data_dir = data_dir
        # Tray failures degrade, never block: without a tray the app still
        # runs and hide_to_tray falls back to minimize.
        try:
            tray = _create_tray(api)
            t_tray = threading.Thread(target=tray.run, name="kreds-tray", daemon=True)
            t_tray.start()
            api._tray = tray
            api._tray_thread = t_tray
        except Exception as e:
            _log_error(data_dir, "tray unavailable: " + repr(e))
```

- [ ] **Step 4: Run tests**

Run: `.venv\Scripts\python.exe -m pytest tests/test_desktop.py tests/test_paths.py -q`
Expected: all PASS

- [ ] **Step 5: Commit**

```bash
git add hearth/desktop.py tests/test_desktop.py
git commit -m "feat(tray): always-present tray icon - hide-to-tray on keep-close with dead-tray fallback, one-time balloon, Open/Quit menu, tray stop on every exit path"
```

---

### Task 3: Web client close handler + copy

**Files:**
- Modify: `hearth/web/app.js` (`wireDesktopChrome` close handler ~line 2851; Settings label ~line 2138 area; wizard step copy ~line 3008 area)
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Consumes: `Api.hide_to_tray` (Task 2) via `window.pywebview.api`.

- [ ] **Step 1: Write the failing test** — append to `tests/test_web_assets.py`:

```python
def test_desktop_keep_close_hides_to_tray_with_fallback():
    # Spec 2026-07-08-kreds-tray-icon: close with "keep" hides to the tray;
    # an OLDER frozen shell (web payload ahead of core - the skew the
    # updater allows) lacks hide_to_tray, so the handler falls back to
    # minimize. The titlebar minimize button itself stays minimize.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    chrome = _js_fn_body(js, "wireDesktopChrome")
    assert "hide_to_tray" in chrome
    assert "api.minimize()" in chrome
    # user-facing copy names the tray (wizard step + Settings label)
    assert js.count("in the system tray") >= 2
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py::test_desktop_keep_close_hides_to_tray_with_fallback -q`
Expected: FAIL

- [ ] **Step 3: Implement in `hearth/web/app.js`.**

(a) In `wireDesktopChrome`, change:

```js
    if (closeBehavior === "keep") api.minimize();
    else api.quit();
```

to:

```js
    if (closeBehavior === "keep") {
      // Newer shells hide to the system tray; an older frozen shell (web
      // payload ahead of core - allowed update skew) has no hide_to_tray,
      // so degrade to the old taskbar-minimize.
      if (api.hide_to_tray) api.hide_to_tray();
      else api.minimize();
    } else api.quit();
```

(b) Find the TWO user-facing "Keep running in the background" strings (the
Settings toggle label in `renderDesktopSettings` and the onboarding wizard
close-behavior step) and change both to:

```
Keep running in the background (in the system tray)
```

Do NOT touch `README`/docs occurrences in this task.

- [ ] **Step 4: Run the full suite**

Run: `.venv\Scripts\python.exe -m pytest -q`
Expected: all PASS (685+), 1 pre-existing skip

- [ ] **Step 5: Commit**

```bash
git add hearth/web/app.js tests/test_web_assets.py
git commit -m "feat(tray): keep-close hides to tray (minimize fallback for older shells), wizard + Settings copy names the tray"
```

---

### Task 4: Live shell sanity + build + release assembly (publish HELD)

**Files:**
- Modify: `docs/engineering-notes.md` ("Windows desktop app" section)
- No web/app code changes.

**Interfaces:**
- Consumes: everything above.

- [ ] **Step 1: Source-run tray sanity.** Run `hearth app` from source against a scratch data dir and confirm it comes up (the tray itself is GUI — August verifies visuals; this step only proves the wiring doesn't crash the shell):

Run (PowerShell): `$env:APPDATA = "<scratchpad>\tray-smoke"; .venv\Scripts\python.exe -m hearth app --dir "<scratchpad>\tray-smoke\Kreds"` in the background; poll the data dir's `app.log` stays absent for ~20s and a `python` process with a listening port exists; then kill it. A `tray unavailable` line in app.log = FAIL, investigate.

- [ ] **Step 2: Bump version + build.** Set `hearth/__init__.py` `__version__ = "0.3.6"`, `hearth/web/VERSION` = `0.3.6`, commit (`chore: bump 0.3.6 (system tray release)`), run `.\packaging\build.ps1` (fails early if Kreds is running — stop it first), confirm `dist\Kreds\versions\0.3.6` exists and `dist\Kreds\versions\0.3.6\_internal` contains a `pystray` directory (the bundling proof).

- [ ] **Step 3: Packaged smoke.** Run the existing scratchpad `smoke.ps1` (isolated APPDATA; polls `/api/bootstrap` → 200, kills cleanly). Expected: SMOKE PASS.

- [ ] **Step 4: Docs.** In `docs/engineering-notes.md`, "Windows desktop app" section, replace the "no system-tray icon yet" statements (three occurrences reference it) with a short paragraph: always-present tray icon (pystray, lazy import), keep-close hides fully with a dead-tray minimize fallback, one-time balloon, Open/Quit menu, tray removed on every exit path including restart-to-finish. Run the full suite once more.

- [ ] **Step 5: Assemble, HOLD publish.** `release-build --version 0.3.6 ... --notes "Kreds now lives in your system tray: closing with 'keep running' hides the window fully, the tray icon brings it back, and Quit lives in its menu."` — then STOP. Signing and publishing are August's call (key custody + he may want to bundle findings from tonight's two-laptop Tor test). Report ready-to-sign.

```bash
git add docs/engineering-notes.md
git commit -m "docs: system tray behavior in engineering notes"
git push origin main
```

---

## Self-review (done at write time)

- **Spec coverage:** always-in-tray (T2 launch wiring), hide-on-keep (T2+T3), dead-tray fallback (T2, tested), one-time balloon + flag file (T2, tested), Open/Quit menu with double-click default (T2), quit/restart removes icon (T2 quit path — `restart()` calls `quit()`), copy updates incl. already-running notice (T2d, T3b), lazy import (T2, tested), icon bundling (T1), packaged proof (T4).
- **Placeholders:** none — every step carries code, exact strings, or exact commands. (A drafting artifact in `test_pystray_import_is_lazy` was caught by this review and removed from the test text above.)
- **Type consistency:** `_tray`/`_tray_thread`/`_data_dir` set in `__init__` (None) and `launch()`; `TRAY_NOTIFIED_FLAG` consumed by both T2 code and tests; `hide_to_tray` name identical across desktop.py, app.js, and the static test.
