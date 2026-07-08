# Loop Visual System + Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply Loop's "quiet canvas, loud people" visual system to every screen (name swap to Loop, ring-o SVG wordmark, achromatic-chrome light theme) and ship the customizable profile feature (accent, avatar shape/size/placement, banner, bio) with a Pillow image transcode gate.

**Architecture:** The profile is an extension of the existing `profile` message kind (structured data — gossips/validates like everything else, no executable content). Image uploads pass a server-side Pillow re-encode gate before storage so viewers only ever render bytes Loop produced. The visual system is CSS + markup + one reusable inline SVG wordmark; no behavior changes to feed/messages/sync. Spec: `docs/superpowers/specs/2026-07-03-loop-visual-and-profiles-design.md`.

**Tech Stack:** Existing Hearth stack (Python 3.12, FastAPI, sqlite3, vanilla JS) + Pillow (already installed via `qrcode[pil]`).

## Global Constraints

- Run as `.\.venv\Scripts\python.exe ...` from `C:\Users\Wong\Desktop\Hearth`, branch `hearth-vertical-slice`.
- No new dependency — Pillow is already present (`from PIL import Image`).
- ASCII only in console prints (cp1252).
- Working display name is **Loop**; the package/module names stay `hearth` (do NOT rename modules/imports). Only user-facing copy and the wordmark change.
- Achromatic-chrome rule: the visual system's chrome uses ONLY these tokens — `--paper #F6F7F9`, `--card #FFFFFF`, `--ink #17191E`, `--ink-2 #3B3F47`, `--muted #6B7079`, `--faint #969BA3`, `--line #E3E6EB`, `--line-2 #EEF0F3`, `--fill #EDEFF2`. Accent color appears ONLY inside profile/message contexts via a scoped `--me` custom property.
- Accent palette (exact 10, curated): `#2743D6` cobalt, `#C0563B` clay, `#3E7C55` moss, `#8A5CD0` violet, `#17191E` ink, `#1F8A8A` teal, `#C79A2E` amber, `#C0567E` rose, `#4A5568` slate, `#7A4E8A` plum. Any stored accent MUST validate as one of these (strict membership) in v1.
- Avatar enums: `avatar_shape` in {circle, squircle, square, triangle}; `avatar_size` in {s, m, l}; `avatar_align` in {left, center, right}. Bio <= 240 chars. Missing new fields default to circle / m / left / null banner / accent `#2743D6`.
- Image gate (avatar/banner): open with Pillow, take first frame only (strip animation), resize down to max (avatar 512px, banner 1500px wide), re-encode to PNG, store re-encoded bytes as a blob. 5 MB pre-gate cap unchanged.
- Full suite must stay green; the one existing test that changes (`test_api.py::test_profile_update`, and its `test_profile_missing_key` sibling if present) is updated in lockstep in the task that changes the endpoint. Require ZERO failures at each task's full-suite step.
- Commit after every task, trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: Extend the profile message model

**Files:**
- Modify: `hearth/messages.py`
- Test: `tests/test_profile_model.py`

**Interfaces:**
- Consumes: existing `make_profile`, `validate_payload`, `_is_hexn` in `hearth.messages`.
- Produces (in `hearth.messages`):
  - `MAX_BIO = 240`
  - `ACCENTS` — the exact 10-hex tuple (lowercase) from Global Constraints.
  - `AVATAR_SHAPES = ("circle","squircle","square","triangle")`, `AVATAR_SIZES = ("s","m","l")`, `AVATAR_ALIGNS = ("left","center","right")`
  - `make_profile(device, name, bio="", accent="#2743d6", avatar=None, avatar_shape="circle", avatar_size="m", avatar_align="left", banner=None, now=None) -> SignedMessage` — payload `{"kind":"profile","name","bio","accent","avatar","avatar_shape","avatar_size","avatar_align","banner","created_at"}`. `avatar`/`banner` are blob-hash hex strings or None.
  - `validate_payload` profile branch enforces: name 1..MAX_NAME; bio str <= MAX_BIO; accent in ACCENTS; avatar/banner each None or 64-hex; shape/size/align in their enums. Missing bio/accent/avatar/shape/size/align/banner default (bio "", accent "#2743d6", avatar None, circle, m, left, banner None) so old name-only profiles still validate.

- [ ] **Step 1: Write the failing tests**

`tests/test_profile_model.py`:
```python
from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import (
    ACCENTS, make_profile, validate_payload,
)


def device():
    d = DeviceKeys.create("phone")
    IdentityCeremony().enroll_first_device(d)
    return d


def test_rich_profile_valid_and_signed():
    d = device()
    m = make_profile(d, "Wong", bio="Designer i Kbh",
                     accent="#2743d6", avatar="ab" * 32,
                     avatar_shape="squircle", avatar_size="l",
                     avatar_align="center", banner="cd" * 32, now=5.0)
    assert m.payload["name"] == "Wong" and m.payload["bio"] == "Designer i Kbh"
    assert m.payload["accent"] == "#2743d6"
    assert m.payload["avatar"] == "ab" * 32
    assert m.payload["avatar_shape"] == "squircle"
    assert validate_payload(m.payload) == (True, "ok")
    assert m.verify_device_signature()


def test_name_only_profile_still_valid():
    # An old-style profile with only name+created_at must still pass.
    assert validate_payload({"kind": "profile", "name": "Wong",
                             "created_at": 1.0}) == (True, "ok")


def test_defaults_when_fields_absent():
    d = device()
    m = make_profile(d, "Wong")
    p = m.payload
    assert (p["bio"], p["accent"], p["avatar"]) == ("", "#2743d6", None)
    assert (p["avatar_shape"], p["avatar_size"], p["avatar_align"]) == \
        ("circle", "m", "left")
    assert p["banner"] is None


def test_invalid_profiles_rejected():
    ok = lambda p: validate_payload(p)[0]
    base = {"kind": "profile", "name": "Wong", "created_at": 1.0}
    assert not ok({**base, "accent": "#ffffff"})        # not in palette
    assert not ok({**base, "accent": "cobalt"})         # not hex
    assert not ok({**base, "bio": "x" * 241})
    assert not ok({**base, "avatar_shape": "hexagon"})
    assert not ok({**base, "avatar_size": "xl"})
    assert not ok({**base, "avatar_align": "top"})
    assert not ok({**base, "avatar": "zz"})             # bad hash
    assert not ok({**base, "banner": "zz"})
    assert not ok({**base, "name": ""})
    assert ACCENTS[0] == "#2743d6"                      # palette order pinned


def test_accent_membership_case_insensitive_not_required():
    # We store/validate lowercase hex; uppercase is rejected in v1 (strict).
    assert validate_payload({"kind": "profile", "name": "W",
                             "accent": "#2743D6", "created_at": 1.0})[0] \
        is False
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_profile_model.py -q`
Expected: FAIL — `make_profile` has no `bio` kwarg / `ACCENTS` import error.

- [ ] **Step 3: Modify `hearth/messages.py`**

Add constants after `MAX_NAME = 80`:
```python
MAX_BIO = 240
ACCENTS = ("#2743d6", "#c0563b", "#3e7c55", "#8a5cd0", "#17191e",
           "#1f8a8a", "#c79a2e", "#c0567e", "#4a5568", "#7a4e8a")
AVATAR_SHAPES = ("circle", "squircle", "square", "triangle")
AVATAR_SIZES = ("s", "m", "l")
AVATAR_ALIGNS = ("left", "center", "right")
```

Replace `make_profile` with:
```python
def make_profile(device: DeviceKeys, name: str, bio: str = "",
                 accent: str = "#2743d6", avatar: Optional[str] = None,
                 avatar_shape: str = "circle", avatar_size: str = "m",
                 avatar_align: str = "left", banner: Optional[str] = None,
                 now: Optional[float] = None) -> SignedMessage:
    return device.sign_message({
        "kind": KIND_PROFILE, "name": name, "bio": bio, "accent": accent,
        "avatar": avatar, "avatar_shape": avatar_shape,
        "avatar_size": avatar_size, "avatar_align": avatar_align,
        "banner": banner, "created_at": _now(now),
    })
```

Replace the `KIND_PROFILE` branch of `validate_payload` with:
```python
    if kind == KIND_PROFILE:
        name = p.get("name")
        if not isinstance(name, str) or not (1 <= len(name) <= MAX_NAME):
            return False, "bad name"
        bio = p.get("bio", "")
        if not isinstance(bio, str) or len(bio) > MAX_BIO:
            return False, "bad bio"
        if p.get("accent", "#2743d6") not in ACCENTS:
            return False, "bad accent"
        for field in ("avatar", "banner"):
            v = p.get(field)
            if v is not None and not _is_hex64(v):
                return False, f"bad {field}"
        if p.get("avatar_shape", "circle") not in AVATAR_SHAPES:
            return False, "bad avatar_shape"
        if p.get("avatar_size", "m") not in AVATAR_SIZES:
            return False, "bad avatar_size"
        if p.get("avatar_align", "left") not in AVATAR_ALIGNS:
            return False, "bad avatar_align"
        return True, "ok"
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_profile_model.py tests/test_messages.py -q`
Expected: all pass (5 new + existing messages tests).

- [ ] **Step 5: Run the full suite**

Run: `.\.venv\Scripts\python.exe -m pytest tests -q`
Expected: zero failures (the richer `make_profile` is backward-compatible; `set_profile(name)` callers still work via defaults).

- [ ] **Step 6: Commit**

```bash
git add hearth/messages.py tests/test_profile_model.py
git commit -m "feat: extend profile message - bio, accent, avatar shape/size/placement, banner"
```

---

### Task 2: Image transcode gate

**Files:**
- Create: `hearth/imagegate.py`
- Test: `tests/test_imagegate.py`

**Interfaces:**
- Produces (in `hearth.imagegate`):
  - `AVATAR_MAX = 512`, `BANNER_MAX = 1500`
  - `transcode(data: bytes, max_dim: int) -> bytes` — open with Pillow; raise `ValueError("not an image")` if it won't parse; take frame 0 (strip animation); if either dimension > max_dim, resize down preserving aspect (thumbnail); convert to RGB (drop alpha onto white) or keep RGB; re-encode to PNG; return the PNG bytes.

- [ ] **Step 1: Write the failing tests**

`tests/test_imagegate.py`:
```python
import io

import pytest
from PIL import Image

from hearth.imagegate import AVATAR_MAX, transcode


def png_bytes(w, h, color=(200, 80, 80)):
    buf = io.BytesIO()
    Image.new("RGB", (w, h), color).save(buf, format="PNG")
    return buf.getvalue()


def animated_gif_bytes():
    buf = io.BytesIO()
    frames = [Image.new("P", (40, 40), i) for i in (1, 2, 3)]
    frames[0].save(buf, format="GIF", save_all=True,
                   append_images=frames[1:], duration=100, loop=0)
    return buf.getvalue()


def test_reencodes_to_png_and_downsizes():
    out = transcode(png_bytes(2000, 1000), AVATAR_MAX)
    img = Image.open(io.BytesIO(out))
    assert img.format == "PNG"
    assert max(img.size) <= AVATAR_MAX
    # aspect preserved (2:1)
    assert abs(img.size[0] / img.size[1] - 2.0) < 0.05


def test_small_image_not_upscaled():
    out = transcode(png_bytes(64, 64), AVATAR_MAX)
    assert Image.open(io.BytesIO(out)).size == (64, 64)


def test_animation_reduced_to_single_frame():
    out = transcode(animated_gif_bytes(), AVATAR_MAX)
    img = Image.open(io.BytesIO(out))
    assert img.format == "PNG"
    assert getattr(img, "n_frames", 1) == 1     # PNG static, one frame


def test_non_image_rejected():
    with pytest.raises(ValueError):
        transcode(b"this is not an image", AVATAR_MAX)
    with pytest.raises(ValueError):
        transcode(b"", AVATAR_MAX)


def test_output_bytes_differ_from_input():
    src = png_bytes(300, 300)
    assert transcode(src, AVATAR_MAX) != src     # always re-encoded
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_imagegate.py -q`
Expected: FAIL — `No module named 'hearth.imagegate'`.

- [ ] **Step 3: Implement `hearth/imagegate.py`**

```python
"""Server-side image gate: re-encode uploads to known-good static bytes.

Every avatar/banner an uploader sends is opened, reduced to a single
static frame, downsized, and re-encoded to PNG before storage, so every
viewer only ever renders bytes WE produced -- closing the decoder-exploit
and decompression-bomb surface for image inputs (spec: transcode gate).
Animated inputs are flattened to their first frame; animated avatars are a
deferred feature (video/animation pipeline)."""
from __future__ import annotations

import io

from PIL import Image, UnidentifiedImageError

AVATAR_MAX = 512
BANNER_MAX = 1500


def transcode(data: bytes, max_dim: int) -> bytes:
    try:
        img = Image.open(io.BytesIO(data))
        img.load()                       # force decode (raises on truncated)
    except (UnidentifiedImageError, OSError, ValueError):
        raise ValueError("not an image")
    # First frame only (strips animation).
    if getattr(img, "is_animated", False):
        img.seek(0)
    # Flatten to RGB on white (drops alpha / palette / exotic modes).
    if img.mode != "RGB":
        bg = Image.new("RGB", img.size, (255, 255, 255))
        rgb = img.convert("RGBA")
        bg.paste(rgb, mask=rgb.split()[-1])
        img = bg
    # Downscale only (never upscale), aspect preserved.
    if max(img.size) > max_dim:
        img.thumbnail((max_dim, max_dim), Image.LANCZOS)
    out = io.BytesIO()
    img.save(out, format="PNG")
    return out.getvalue()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_imagegate.py -q`
Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
git add hearth/imagegate.py tests/test_imagegate.py
git commit -m "feat: Pillow image transcode gate for avatar/banner uploads"
```

---

### Task 3: Store — full profile record + per-identity posts

**Files:**
- Modify: `hearth/store.py`
- Test: `tests/test_store_profile.py`

**Interfaces:**
- Consumes: `KIND_PROFILE`, `KIND_POST` (existing store imports).
- Produces (methods on `Store`):
  - `profiles()` — UNCHANGED signature/return (`{identity: name}`), so feed/state/conversations keep working. (It already reads `p["name"]`, which still exists.)
  - `profile(identity_pub: str) -> dict | None` — the latest full profile record for one identity: `{name, bio, accent, avatar, avatar_shape, avatar_size, avatar_align, banner}` with defaults filled for missing fields (bio "", accent "#2743d6", avatar None, circle, m, left, banner None). None if that identity has published no profile.
  - `posts_by(identity_pub: str, now=None) -> list[dict]` — that identity's own non-expired POSTs, newest-first, same row shape as `feed()` rows (msg_id, identity_pub, device_pub, device_name, author_name, text, blobs, created_at, expires_at).

- [ ] **Step 1: Write the failing tests**

`tests/test_store_profile.py`:
```python
from hearth.identity import DeviceKeys, IdentityCeremony
from hearth.messages import make_post, make_profile
from hearth.store import Store


def person(name):
    d = DeviceKeys.create(name)
    IdentityCeremony().enroll_first_device(d)
    return d


def store_with(tmp_path, *identities):
    s = Store(tmp_path / "s.db")
    for i, ident in enumerate(identities):
        s.add_identity(ident, is_self=(i == 0))
    return s


def test_profile_full_record_latest_wins(tmp_path):
    wong = person("wong-phone")
    s = store_with(tmp_path, wong.identity_pub)
    s.ingest_message(make_profile(wong, "Wong", now=1.0))
    s.ingest_message(make_profile(wong, "Wong", bio="hej", accent="#c0563b",
                                  avatar="ab" * 32, avatar_shape="squircle",
                                  avatar_size="l", avatar_align="center",
                                  banner="cd" * 32, now=2.0))
    p = s.profile(wong.identity_pub)
    assert p["name"] == "Wong" and p["bio"] == "hej"
    assert p["accent"] == "#c0563b" and p["avatar"] == "ab" * 32
    assert p["avatar_shape"] == "squircle" and p["avatar_align"] == "center"
    assert p["banner"] == "cd" * 32
    # names-only view still works for feed/state
    assert s.profiles()[wong.identity_pub] == "Wong"


def test_profile_defaults_for_name_only(tmp_path):
    wong = person("wong-phone")
    s = store_with(tmp_path, wong.identity_pub)
    s.ingest_message(make_profile(wong, "Wong"))
    p = s.profile(wong.identity_pub)
    assert (p["bio"], p["accent"], p["avatar"]) == ("", "#2743d6", None)
    assert p["avatar_shape"] == "circle" and p["avatar_size"] == "m"


def test_profile_none_when_absent(tmp_path):
    s = store_with(tmp_path, "aa" * 32)
    assert s.profile("aa" * 32) is None


def test_posts_by_identity_only(tmp_path):
    wong, freja = person("wong-phone"), person("freja-phone")
    s = store_with(tmp_path, wong.identity_pub, freja.identity_pub)
    s.ingest_message(make_post(wong, "wong one", now=10.0))
    s.ingest_message(make_post(wong, "wong two", now=20.0))
    s.ingest_message(make_post(freja, "freja one", now=15.0))
    texts = [r["text"] for r in s.posts_by(wong.identity_pub)]
    assert texts == ["wong two", "wong one"]      # newest-first, wong only
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_store_profile.py -q`
Expected: FAIL — `Store` has no `profile` / `posts_by`.

- [ ] **Step 3: Append methods to `Store` in `hearth/store.py`**

```python
    def profile(self, identity_pub: str) -> Optional[dict]:
        with self._lock:
            best = None
            for (mj,) in self._db.execute(
                    "SELECT msg_json FROM messages WHERE kind=?"
                    " AND identity_pub=?", (KIND_PROFILE, identity_pub)):
                p = json.loads(mj)["payload"]
                if best is None or p["created_at"] > best["created_at"]:
                    best = p
            if best is None:
                return None
            return {
                "name": best.get("name", identity_pub[:8]),
                "bio": best.get("bio", ""),
                "accent": best.get("accent", "#2743d6"),
                "avatar": best.get("avatar"),
                "avatar_shape": best.get("avatar_shape", "circle"),
                "avatar_size": best.get("avatar_size", "m"),
                "avatar_align": best.get("avatar_align", "left"),
                "banner": best.get("banner"),
            }

    def posts_by(self, identity_pub: str,
                 now: Optional[float] = None) -> List[dict]:
        now = now if now is not None else time.time()
        with self._lock:
            names = self.profiles()
            out = []
            for mid, ipub, dpub, mj in self._db.execute(
                    "SELECT msg_id, identity_pub, device_pub, msg_json"
                    " FROM messages WHERE kind=? AND identity_pub=?"
                    " ORDER BY created_at DESC", (KIND_POST, identity_pub)):
                m = json.loads(mj)
                p = m["payload"]
                if (p.get("expires_at") is not None
                        and p["expires_at"] <= now):
                    continue
                out.append({
                    "msg_id": mid, "identity_pub": ipub, "device_pub": dpub,
                    "device_name": m["cert"]["device_name"],
                    "author_name": names.get(ipub, ipub[:8]),
                    "text": p["text"], "blobs": p["blobs"],
                    "created_at": p["created_at"],
                    "expires_at": p.get("expires_at"),
                })
            return out
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_store_profile.py tests/test_store_ingest.py -q`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add hearth/store.py tests/test_store_profile.py
git commit -m "feat: store full profile record + per-identity posts query"
```

---

### Task 4: Node — set rich profile + read profile view

**Files:**
- Modify: `hearth/node.py`
- Test: `tests/test_node_profile.py`

**Interfaces:**
- Consumes: `transcode`, `AVATAR_MAX`, `BANNER_MAX` (Task 2); `make_profile` (Task 1); store `profile`/`posts_by` (Task 3).
- Produces (on `HearthNode`):
  - `set_profile(name, bio="", accent="#2743d6", avatar_bytes=None, avatar_shape="circle", avatar_size="m", avatar_align="left", banner_bytes=None) -> str` — REPLACES the current name-only `set_profile`. If `avatar_bytes`/`banner_bytes` given, each is `transcode`d (avatar with AVATAR_MAX, banner with BANNER_MAX) then `put_blob`'d; the resulting hash goes in the profile message. When bytes are None, the corresponding hash is carried forward from the current stored profile (so saving the name doesn't wipe the avatar). Raises ValueError on a bad image (propagated from transcode) or off-enum/off-palette value (caught by make_profile's payload being validated at ingest — but validate here too for a clean error).
  - `profile_view(identity_pub) -> dict | None` — `{**store.profile(identity), "identity_pub", "mine": bool, "posts": posts_by(identity)}`. None if unknown identity or no profile AND not self. For self with no profile yet, synthesize a default record from the display name so the page always renders for the owner.

- [ ] **Step 1: Write the failing tests**

`tests/test_node_profile.py`:
```python
import io

import pytest
from PIL import Image

from hearth.node import HearthNode


def png(w=300, h=300, color=(40, 90, 200)):
    buf = io.BytesIO()
    Image.new("RGB", (w, h), color).save(buf, format="PNG")
    return buf.getvalue()


def test_set_rich_profile_transcodes_and_stores(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    n.set_profile("Wong", bio="Designer", accent="#c0563b",
                  avatar_bytes=png(2000, 2000), avatar_shape="squircle",
                  avatar_size="l", avatar_align="center")
    p = n.store.profile(n.identity_pub)
    assert p["bio"] == "Designer" and p["accent"] == "#c0563b"
    assert p["avatar_shape"] == "squircle"
    # avatar stored as a re-encoded blob, downsized to <=512
    blob = n.store.get_blob(p["avatar"])
    assert blob is not None
    assert max(Image.open(io.BytesIO(blob)).size) <= 512


def test_saving_name_keeps_existing_avatar(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    n.set_profile("Wong", avatar_bytes=png())
    first = n.store.profile(n.identity_pub)["avatar"]
    assert first is not None
    n.set_profile("Wong Two", bio="new bio")     # no new avatar bytes
    p = n.store.profile(n.identity_pub)
    assert p["name"] == "Wong Two" and p["avatar"] == first  # carried forward


def test_bad_image_rejected(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    with pytest.raises(ValueError):
        n.set_profile("Wong", avatar_bytes=b"not an image")


def test_profile_view_self_and_friend(tmp_path):
    wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
    freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
    wong.store.add_identity(freja.identity_pub)
    # friend publishes a profile; carry it to wong
    freja.set_profile("Freja", bio="Keramiker", accent="#3e7c55")
    from hearth.messages import make_profile
    for m in freja.store.messages_not_in({}, {freja.identity_pub},
                                         wong.identity_pub):
        wong.store.ingest_message(m)
    fv = wong.profile_view(freja.identity_pub)
    assert fv["name"] == "Freja" and fv["mine"] is False
    assert fv["bio"] == "Keramiker"
    mv = wong.profile_view(wong.identity_pub)
    assert mv["mine"] is True and "posts" in mv


def test_profile_view_includes_own_posts(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    n.compose_post("hello world")
    v = n.profile_view(n.identity_pub)
    assert [p["text"] for p in v["posts"]] == ["hello world"]
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_node_profile.py -q`
Expected: FAIL — `set_profile` signature / `profile_view` missing.

- [ ] **Step 3: Modify `hearth/node.py`**

Extend imports:
```python
from .imagegate import AVATAR_MAX, BANNER_MAX, transcode
```
(and ensure `make_profile` is already imported from `.messages` — it is).

Replace `set_profile`:
```python
    def set_profile(self, name: str, bio: str = "",
                    accent: str = "#2743d6", avatar_bytes=None,
                    avatar_shape: str = "circle", avatar_size: str = "m",
                    avatar_align: str = "left", banner_bytes=None) -> str:
        current = self.store.profile(self.identity_pub) or {}
        avatar = current.get("avatar")
        banner = current.get("banner")
        if avatar_bytes is not None:
            avatar = self.store.put_blob(transcode(avatar_bytes, AVATAR_MAX))
        if banner_bytes is not None:
            banner = self.store.put_blob(transcode(banner_bytes, BANNER_MAX))
        return self._publish(make_profile(
            self.device, name, bio=bio, accent=accent, avatar=avatar,
            avatar_shape=avatar_shape, avatar_size=avatar_size,
            avatar_align=avatar_align, banner=banner))
```

Append:
```python
    def profile_view(self, identity_pub: str):
        if identity_pub != self.identity_pub \
                and not self.store.is_known(identity_pub):
            return None
        rec = self.store.profile(identity_pub)
        if rec is None:
            if identity_pub != self.identity_pub:
                return None
            rec = {"name": self.store.profiles().get(
                       identity_pub, identity_pub[:8]),
                   "bio": "", "accent": "#2743d6", "avatar": None,
                   "avatar_shape": "circle", "avatar_size": "m",
                   "avatar_align": "left", "banner": None}
        return {**rec, "identity_pub": identity_pub,
                "mine": identity_pub == self.identity_pub,
                "posts": self.store.posts_by(identity_pub)}
```

Note: `_publish` runs the payload through `ingest_message` -> `validate_payload`, so an off-palette accent or bad enum raises "own message rejected" (RuntimeError). To give the API a clean 400, the API task validates enums/accent before calling; this task leaves node as-is (its callers pass valid values; tests above use valid values).

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_node_profile.py tests/test_node.py tests/test_node_dm.py -q`
Expected: all pass. (The existing `test_node.py` calls `set_profile(person_name)` positionally in `create()` — still valid, name-only.)

- [ ] **Step 5: Run the full suite**

Run: `.\.venv\Scripts\python.exe -m pytest tests -q`
Expected: zero failures.

- [ ] **Step 6: Commit**

```bash
git add hearth/node.py tests/test_node_profile.py
git commit -m "feat: node rich set_profile (transcode+carry-forward) and profile_view"
```

---

### Task 5: API — profile read route + rich profile POST

**Files:**
- Modify: `hearth/api.py`
- Modify: `tests/test_api.py` (update the two profile tests)
- Test: `tests/test_api_profile.py`

**Interfaces:**
- Consumes: node `profile_view`, `set_profile` (Task 4); `ACCENTS`, `AVATAR_SHAPES`, `AVATAR_SIZES`, `AVATAR_ALIGNS`, `MAX_BIO` (Task 1).
- Produces (routes in `build_app`, before the websocket route):
  - `GET /api/profile/{identity_pub}` -> `node.profile_view(identity_pub)`; 404 if it returns None.
  - `POST /api/profile` — REPLACES the current JSON name-only handler. Multipart form: `name` (str, required), `bio` (str, default ""), `accent` (str, default "#2743d6"), `avatar_shape`/`avatar_size`/`avatar_align` (str, defaults circle/m/left), optional `avatar` file, optional `banner` file. Validates accent in ACCENTS, shape/size/align in their enums, bio <= MAX_BIO -> else 400 via `_400`. Reads any uploaded files (413 if over MAX_BLOB_BYTES pre-gate), passes bytes to `node.set_profile` (which transcodes); a bad image raises ValueError -> 400 via `_400`. Returns `{"ok": true}`.

- [ ] **Step 1: Write the failing tests + update existing**

Create `tests/test_api_profile.py`:
```python
import io

from fastapi.testclient import TestClient
from PIL import Image

from hearth.api import build_app
from hearth.node import HearthNode


def png(w=300, h=300):
    buf = io.BytesIO()
    Image.new("RGB", (w, h), (40, 90, 200)).save(buf, format="PNG")
    return buf.getvalue()


def client(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Wong", "wong-phone")
    return TestClient(build_app(node)), node


def test_get_own_profile(tmp_path):
    c, node = client(tmp_path)
    r = c.get(f"/api/profile/{node.identity_pub}")
    assert r.status_code == 200
    body = r.json()
    assert body["name"] == "Wong" and body["mine"] is True
    assert body["accent"] == "#2743d6" and "posts" in body


def test_get_unknown_profile_404(tmp_path):
    c, _ = client(tmp_path)
    assert c.get("/api/profile/" + "bb" * 32).status_code == 404


def test_post_rich_profile_with_avatar(tmp_path):
    c, node = client(tmp_path)
    r = c.post("/api/profile",
               data={"name": "Wong", "bio": "Designer", "accent": "#c0563b",
                     "avatar_shape": "squircle", "avatar_size": "l",
                     "avatar_align": "center"},
               files=[("avatar", ("a.png", png(2000, 2000), "image/png"))])
    assert r.status_code == 200
    prof = c.get(f"/api/profile/{node.identity_pub}").json()
    assert prof["bio"] == "Designer" and prof["accent"] == "#c0563b"
    assert prof["avatar_shape"] == "squircle"
    blob = c.get("/api/blob/" + prof["avatar"])
    assert blob.status_code == 200
    assert max(Image.open(io.BytesIO(blob.content)).size) <= 512


def test_post_profile_bad_accent_400(tmp_path):
    c, _ = client(tmp_path)
    r = c.post("/api/profile", data={"name": "Wong", "accent": "#ffffff"})
    assert r.status_code == 400


def test_post_profile_bad_enum_400(tmp_path):
    c, _ = client(tmp_path)
    r = c.post("/api/profile",
               data={"name": "Wong", "avatar_shape": "hexagon"})
    assert r.status_code == 400


def test_post_profile_bad_image_400(tmp_path):
    c, _ = client(tmp_path)
    r = c.post("/api/profile", data={"name": "Wong"},
               files=[("avatar", ("a.png", b"not an image", "image/png"))])
    assert r.status_code == 400
```

In `tests/test_api.py`, update the two profile tests to the multipart form:
- `test_profile_update`: change
  `c.post("/api/profile", json={"name": "Wong II"})` to
  `c.post("/api/profile", data={"name": "Wong II"})`
- `test_missing_body_keys_return_400` (if it references `/api/profile`): the profile line becomes `c.post("/api/profile", data={}).status_code == 400` (missing required `name` -> FastAPI 422; adjust the assertion to `in (400, 422)` for the profile endpoint only, OR drop the profile line from that test since `/api/delete` and `/api/device/revoke` already cover the 400 path). Prefer: change the profile assertion to accept 422 (`assert c.post("/api/profile", data={}).status_code == 422`), since `name` is now a required Form field.

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_api_profile.py -q`
Expected: FAIL — no `/api/profile/{id}` route / POST still JSON-only.

- [ ] **Step 3: Modify `hearth/api.py`**

Add imports at the top:
```python
from .messages import (ACCENTS, AVATAR_ALIGNS, AVATAR_SHAPES, AVATAR_SIZES,
                       MAX_BIO, MAX_BLOB_BYTES)
```
(Keep the existing `MAX_BLOB_BYTES` import if already present; do not duplicate — merge into one import line.)

Replace the existing `@app.post("/api/profile")` handler (the JSON name-only one) with the multipart version, and add the GET route just before it:
```python
    @app.get("/api/profile/{identity_pub}")
    async def get_profile(identity_pub: str):
        view = node.profile_view(identity_pub)
        if view is None:
            raise HTTPException(404, "no such profile")
        return view

    @app.post("/api/profile")
    async def profile(name: str = Form(...), bio: str = Form(""),
                      accent: str = Form("#2743d6"),
                      avatar_shape: str = Form("circle"),
                      avatar_size: str = Form("m"),
                      avatar_align: str = Form("left"),
                      avatar: UploadFile = File(default=None),
                      banner: UploadFile = File(default=None)):
        if accent not in ACCENTS:
            raise HTTPException(400, "accent not in palette")
        if (avatar_shape not in AVATAR_SHAPES
                or avatar_size not in AVATAR_SIZES
                or avatar_align not in AVATAR_ALIGNS):
            raise HTTPException(400, "bad avatar option")
        if len(bio) > MAX_BIO:
            raise HTTPException(400, "bio too long")
        av_bytes = bn_bytes = None
        for up, setter in ((avatar, "av"), (banner, "bn")):
            if up is not None:
                data = await up.read()
                if len(data) > MAX_BLOB_BYTES:
                    raise HTTPException(413, "image exceeds 5 MB cap")
                if setter == "av":
                    av_bytes = data
                else:
                    bn_bytes = data
        _400(lambda: node.set_profile(
            name, bio=bio, accent=accent, avatar_bytes=av_bytes,
            avatar_shape=avatar_shape, avatar_size=avatar_size,
            avatar_align=avatar_align, banner_bytes=bn_bytes))
        return {"ok": True}
```
(`_400`, `Form`, `File`, `UploadFile`, `HTTPException` are already imported/defined in the file — verify and reuse; `_400` was relocated earlier so it is in scope here.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_api_profile.py tests/test_api.py tests/test_api_dm.py tests/test_ceremonies.py -q`
Expected: all pass.

- [ ] **Step 5: Run the full suite**

Run: `.\.venv\Scripts\python.exe -m pytest tests -q`
Expected: zero failures.

- [ ] **Step 6: Commit**

```bash
git add hearth/api.py tests/test_api_profile.py tests/test_api.py
git commit -m "feat: profile read route + rich multipart profile POST with image gate"
```

---

### Task 6: Visual system — Loop wordmark, achromatic-chrome CSS, name swap

**Files:**
- Modify: `hearth/web/index.html`, `hearth/web/style.css`
- Create: (none — wordmark is inline SVG in index.html + a JS helper for dynamic spots)
- Test: `tests/test_web_assets.py`

**Interfaces:**
- Produces: the light "quiet canvas" theme applied to all existing screens; the ring-o SVG wordmark in the header; "Hearth" -> "Loop" in all user-facing copy. No JS behavior change (IDs preserved).

This is a visual task; its automated test only guards the invariants that matter (name swapped, achromatic tokens present, no leftover dark-theme values, wordmark present). The look itself is verified by the Task 7 smoke check.

- [ ] **Step 1: Write the failing test**

`tests/test_web_assets.py`:
```python
from pathlib import Path

WEB = Path(__file__).resolve().parents[1] / "hearth" / "web"


def test_name_swapped_to_loop():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert "<title>Loop</title>" in html
    assert "revoked. It is logged out of Loop." in html
    # no stray user-facing "Hearth" left in the shell
    assert "Hearth" not in html


def test_achromatic_tokens_present_and_dark_theme_gone():
    css = (WEB / "style.css").read_text(encoding="utf-8")
    for token in ("--paper", "--ink", "--line", "--card", "--muted"):
        assert token in css
    assert "#f6f7f9" in css.lower()          # paper
    # the old dark background must be gone
    assert "#16181d" not in css.lower()


def test_wordmark_svg_present():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert 'class="wordmark"' in html
    assert "<svg" in html                    # the ring-o mark is inline SVG
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -q`
Expected: FAIL — title still "Hearth", dark tokens present.

- [ ] **Step 3: Rewrite `hearth/web/index.html`**

Full file (preserves every id used by app.js; swaps brand + wordmark + copy; adds a `#view-profile` container and a "Me" profile entry point):
```html
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Loop</title>
<link rel="stylesheet" href="/static/style.css">
</head>
<body>
<header>
  <div class="brand">
    <span class="wordmark" title="Loop">
      <svg viewBox="0 0 132 44" height="26" aria-label="Loop"
           xmlns="http://www.w3.org/2000/svg">
        <text x="0" y="34" class="wm-l">L</text>
        <circle cx="52" cy="26" r="12" class="wm-ring"/>
        <circle cx="80" cy="26" r="12" class="wm-ring"/>
        <text x="96" y="34" class="wm-l">p</text>
      </svg>
    </span>
  </div>
  <nav class="tabs">
    <button id="tab-feed" class="tab active">Feed</button>
    <button id="tab-messages" class="tab">Messages</button>
    <button id="tab-me" class="tab">Me</button>
  </nav>
  <div class="who"><span id="profile-name"></span>
    <span id="device-name" class="dim"></span></div>
</header>
<div id="view-feed">
<main>
  <section class="col feed-col">
    <form id="compose">
      <textarea id="text" rows="3"
        placeholder="Say something to your people..."></textarea>
      <div class="compose-row">
        <input type="file" id="photos" accept="image/*" multiple>
        <select id="expiry">
          <option value="">keeps</option>
          <option value="3600">1 hour</option>
          <option value="86400">1 day</option>
          <option value="604800">7 days</option>
        </select>
        <button type="submit">Post</button>
      </div>
    </form>
    <div id="feed"></div>
  </section>
  <aside class="col side-col">
    <div class="panel">
      <h2>Me</h2>
      <div class="row"><input id="name-input" placeholder="Display name">
        <button id="name-save">Save</button></div>
      <div class="dim tiny" id="identity-pub"></div>
    </div>
    <div class="panel">
      <h2>Friends</h2>
      <div id="friends"></div>
      <div id="ceremony"></div>
    </div>
    <div class="panel">
      <h2>Devices</h2>
      <div id="devices"></div>
    </div>
  </aside>
</main>
</div>
<div id="view-messages" class="hidden">
  <main class="dm-layout">
    <aside class="col conv-col"><h2>Conversations</h2>
      <div id="conversations"></div></aside>
    <section class="col thread-col">
      <div id="thread-title" class="dim">Pick a conversation</div>
      <div id="thread"></div>
      <form id="dm-compose" class="hidden">
        <textarea id="dm-text" rows="2"
          placeholder="Encrypted message..."></textarea>
        <div class="compose-row">
          <input type="file" id="dm-photos" accept="image/*" multiple>
          <button type="submit">Send</button>
        </div>
      </form>
    </section>
  </main>
</div>
<div id="view-profile" class="hidden"></div>
<div id="revoked-banner" class="hidden">
  This device has been revoked. It is logged out of Loop.
</div>
<script src="/static/app.js"></script>
</body>
</html>
```

- [ ] **Step 4: Rewrite `hearth/web/style.css`**

Full file (light achromatic chrome; `--me` accent scoped to profile/DM contexts). Every selector used by existing markup keeps working; the palette is the Loop system:
```css
:root {
  --paper: #f6f7f9; --card: #ffffff; --ink: #17191e; --ink-2: #3b3f47;
  --muted: #6b7079; --faint: #969ba3; --line: #e3e6eb; --line-2: #eef0f3;
  --fill: #edeff2; --me: #2743d6;
  --sans: system-ui, -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  --mono: ui-monospace, "SF Mono", "Cascadia Code", Consolas, monospace;
}
* { box-sizing: border-box; margin: 0; }
body { background: var(--paper); color: var(--ink); font-family: var(--sans);
  font-size: 15px; line-height: 1.5; -webkit-font-smoothing: antialiased; }
header { display: flex; align-items: center; gap: 22px;
  padding: 12px 22px; border-bottom: 1px solid var(--line);
  background: var(--card); position: sticky; top: 0; z-index: 10; }
.brand { display: flex; align-items: center; }
.wordmark svg { display: block; }
.wm-l { font-family: var(--sans); font-weight: 800; font-size: 40px;
  fill: var(--ink); letter-spacing: -0.03em; }
.wm-ring { fill: none; stroke: var(--ink); stroke-width: 4.5; }
.tabs { display: inline-flex; gap: 6px; }
.tab { background: none; border: 1px solid transparent; border-radius: 999px;
  padding: 6px 14px; font: inherit; font-weight: 600; color: var(--faint);
  cursor: pointer; }
.tab:hover { color: var(--ink-2); }
.tab.active { color: var(--ink); border-color: var(--line); background: var(--fill); }
.who { margin-left: auto; font-size: 13px; color: var(--ink-2); }
.dim { color: var(--muted); } .tiny { font-size: 11px; word-break: break-all; }
.hidden { display: none !important; }
main { display: grid; grid-template-columns: minmax(0,1fr) 320px; gap: 18px;
  max-width: 1000px; margin: 22px auto; padding: 0 18px; }
.dm-layout { grid-template-columns: 240px minmax(0,1fr); }
.col { min-width: 0; }
.panel, #compose { background: var(--card); border: 1px solid var(--line);
  border-radius: 14px; padding: 16px; margin-bottom: 16px; }
h2 { font-size: 11px; text-transform: uppercase; letter-spacing: 0.1em;
  color: var(--faint); margin-bottom: 12px; font-weight: 600; }
textarea, input, select, button { font: inherit; color: var(--ink);
  background: var(--card); border: 1px solid var(--line); border-radius: 8px;
  padding: 8px 11px; }
textarea { width: 100%; resize: vertical; }
.compose-row { display: flex; gap: 8px; margin-top: 8px; align-items: center; }
.compose-row input[type=file] { flex: 1; border: none; background: none; padding-left: 0; }
button { cursor: pointer; font-weight: 600; }
button:hover { border-color: var(--ink-2); }
#compose button[type=submit], #dm-compose button[type=submit] {
  background: var(--ink); color: var(--card); border-color: var(--ink); }
.post { background: var(--card); border: 1px solid var(--line);
  border-radius: 14px; padding: 14px 16px; margin-bottom: 12px; }
.post .meta { display: flex; gap: 8px; align-items: baseline; margin-bottom: 6px; }
.post .author { font-weight: 650; color: var(--ink); }
.post img { max-width: 100%; border-radius: 10px; margin-top: 8px; display: block; }
.post .actions { margin-top: 8px; }
.post .actions button { color: var(--muted); font-size: 12px; border-color: var(--line-2); }
.row { display: flex; gap: 8px; } .row input { flex: 1; }
.friend, .device { padding: 7px 0; border-bottom: 1px solid var(--line-2);
  display: flex; justify-content: space-between; align-items: center; }
.friend { cursor: pointer; } .friend:hover .name-link { color: var(--ink); }
.revoked { text-decoration: line-through; color: var(--faint); }
.hint { font-size: 12px; color: var(--muted); margin-top: 8px; }
.chip { display: inline-flex; align-items: center; gap: 5px; font-size: 11px;
  font-weight: 600; color: var(--muted); background: var(--fill);
  border-radius: 999px; padding: 3px 9px; }
/* conversations + thread */
.conv { padding: 9px 10px; border-radius: 10px; cursor: pointer; }
.conv:hover { background: var(--fill); }
.conv .name { font-weight: 650; }
.bubble { background: var(--fill); border: 1px solid var(--line-2);
  border-radius: 12px; padding: 8px 12px; margin: 6px 0; max-width: 82%; }
.bubble.me { margin-left: auto; background: color-mix(in srgb, var(--me) 12%, #fff);
  border-color: color-mix(in srgb, var(--me) 30%, var(--line)); }
.bubble img { max-width: 100%; border-radius: 8px; margin-top: 6px; }
.bubble .undec { font-style: italic; color: var(--muted); }
#ceremony textarea { margin-top: 8px; font-size: 11px; }
#ceremony button { margin-top: 6px; margin-right: 6px; font-size: 12px; }
#qr-img { display: block; margin: 8px 0; background: #fff; padding: 8px;
  border: 1px solid var(--line); border-radius: 10px; max-width: 190px; }
#revoked-banner { max-width: 560px; margin: 80px auto; padding: 40px;
  text-align: center; color: var(--ink); border: 1px solid var(--line);
  background: var(--card); border-radius: 14px; font-size: 17px; }
/* profile view (Task 7 fills #view-profile) */
.prof-wrap { max-width: 640px; margin: 0 auto; padding: 0 18px 40px; --me: #2743d6; }
.prof-banner { height: 150px; border-radius: 0 0 16px 16px; margin-top: 0;
  background: linear-gradient(135deg, var(--me), color-mix(in srgb, var(--me) 55%, #fff)); }
.prof-banner img { width: 100%; height: 150px; object-fit: cover; border-radius: 0 0 16px 16px; }
.prof-head { padding: 0 4px; position: relative; }
.pfp { background: var(--me); color: #fff; display: grid; place-items: center;
  font-weight: 800; border: 4px solid var(--paper); overflow: hidden; }
.pfp img { width: 100%; height: 100%; object-fit: cover; }
.pfp.circle { border-radius: 50%; } .pfp.squircle { border-radius: 24px; }
.pfp.square { border-radius: 6px; }
.pfp.triangle { clip-path: polygon(50% 0, 100% 100%, 0 100%); border: none; }
.pfp.s { width: 64px; height: 64px; font-size: 26px; margin-top: -32px; }
.pfp.m { width: 88px; height: 88px; font-size: 34px; margin-top: -44px; }
.pfp.l { width: 116px; height: 116px; font-size: 44px; margin-top: -58px; }
.align-left { text-align: left; } .align-center { text-align: center; }
.align-right { text-align: right; }
.align-center .pfp { margin-left: auto; margin-right: auto; }
.align-right .pfp { margin-left: auto; }
.prof-name { font-size: 22px; font-weight: 800; letter-spacing: -0.02em; margin-top: 10px; }
.prof-handle { font-family: var(--mono); font-size: 11.5px; color: var(--faint); }
.prof-bio { color: var(--ink-2); margin-top: 8px; max-width: 46ch; }
.align-center .prof-bio { margin-left: auto; margin-right: auto; }
.prof-actions { margin-top: 14px; display: flex; gap: 8px; }
.align-center .prof-actions { justify-content: center; }
.btn-accent { background: var(--me); color: #fff; border-color: var(--me); }
.prof-posts { margin-top: 22px; }
.editor { background: var(--card); border: 1px solid var(--line);
  border-radius: 14px; padding: 16px; margin-top: 18px; }
.editor .lbl { font-size: 10.5px; font-weight: 600; letter-spacing: 0.1em;
  text-transform: uppercase; color: var(--faint); margin: 14px 0 6px; }
.swatches, .shapes, .aligns { display: flex; gap: 8px; flex-wrap: wrap; }
.sw { width: 28px; height: 28px; border-radius: 50%; border: 2px solid transparent;
  cursor: pointer; } .sw.on { border-color: var(--ink); }
.opt { padding: 6px 12px; border: 1px solid var(--line); border-radius: 8px;
  cursor: pointer; font-size: 13px; background: var(--card); }
.opt.on { border-color: var(--ink); background: var(--fill); font-weight: 650; }
@media (max-width: 720px) { main { grid-template-columns: 1fr; }
  .dm-layout { grid-template-columns: 1fr; } }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `.\.venv\Scripts\python.exe -m pytest tests/test_web_assets.py tests/test_api.py -q`
Expected: pass. (API tests serve index.html/static and still pass.)

- [ ] **Step 6: Commit**

```bash
git add hearth/web/index.html hearth/web/style.css tests/test_web_assets.py
git commit -m "feat: Loop wordmark + achromatic-chrome light theme across all screens"
```

---

### Task 7: Profile page + editor (JS), Me tab, demo copy, smoke check

**Files:**
- Modify: `hearth/web/app.js`, `hearth/demo.py`, `README.md`
- Test: (manual smoke — no unit test; JS behavior)

**Interfaces:**
- Consumes: `GET /api/profile/{id}`, `POST /api/profile` (Task 5); `#view-profile`, `#tab-me`, the `.prof-*`/`.editor` styles (Task 6).
- Produces: clicking "Me" or a friend/author opens their profile in `#view-profile`; own profile shows an inline editor (accent swatches, avatar upload + shape/size/placement, banner upload, bio, name) that POSTs multipart and refreshes. Feed author names and friend rows become clickable to open profiles.

- [ ] **Step 1: Extend `hearth/web/app.js`**

Add a `showView("profile")` branch and a profile renderer. Append after the existing `restoreView()`/tab wiring:
```javascript
document.getElementById("tab-me").onclick = () => openProfile(STATE.identity_pub);

function setView(which) {
  for (const v of ["feed", "messages", "profile"])
    document.getElementById("view-" + v).classList.toggle("hidden", v !== which);
  for (const t of ["feed", "messages", "me"])
    document.getElementById("tab-" + t).classList.toggle("active", t === which
      || (which === "profile" && t === "me"));
}
// route feed/messages tabs through setView too
document.getElementById("tab-feed").onclick = () => { setView("feed"); };
document.getElementById("tab-messages").onclick = () => {
  setView("messages"); loadConversations(); };

async function openProfile(identity) {
  setView("profile");
  const p = await j("/api/profile/" + identity);
  const root = document.getElementById("view-profile");
  root.replaceChildren();
  const wrap = el("div", "prof-wrap");
  wrap.style.setProperty("--me", p.accent);
  // banner
  const banner = el("div", "prof-banner");
  if (p.banner) { const bi = document.createElement("img");
    bi.src = "/api/blob/" + p.banner; banner.replaceChildren(bi); }
  // head
  const head = el("div", "prof-head align-" + p.avatar_align);
  const pfp = el("div", "pfp " + p.avatar_shape + " " + p.avatar_size);
  if (p.avatar) { const ai = document.createElement("img");
    ai.src = "/api/blob/" + p.avatar; pfp.append(ai); }
  else pfp.textContent = (p.name || "?").slice(0, 1).toUpperCase();
  head.append(pfp,
    el("div", "prof-name", p.name),
    el("div", "prof-handle", "identity " + identity.slice(0, 12) + "…"));
  if (p.bio) head.append(el("div", "prof-bio", p.bio));
  const actions = el("div", "prof-actions");
  if (!p.mine) { const b = el("button", "btn-accent", "Message");
    b.onclick = () => { setView("messages"); loadConversations();
      openThread(identity, p.name); }; actions.append(b); }
  head.append(actions);
  wrap.append(banner, head);
  if (p.mine) wrap.append(profileEditor(p));
  // posts
  const posts = el("div", "prof-posts");
  renderPostsInto(posts, p.posts, p.mine);
  wrap.append(posts);
  root.append(wrap);
}

const ACCENTS = ["#2743d6","#c0563b","#3e7c55","#8a5cd0","#17191e",
  "#1f8a8a","#c79a2e","#c0567e","#4a5568","#7a4e8a"];

function profileEditor(p) {
  const box = el("div", "editor");
  box.append(el("h2", "", "Edit your profile"));
  const name = document.createElement("input"); name.value = p.name;
  const bio = document.createElement("textarea"); bio.rows = 2; bio.value = p.bio || "";
  bio.placeholder = "Short bio";
  box.append(el("div","lbl","Name"), name, el("div","lbl","Bio"), bio);
  // accent
  box.append(el("div","lbl","Your color"));
  const sws = el("div","swatches"); let accent = p.accent;
  for (const c of ACCENTS) { const s = el("div","sw" + (c===accent?" on":""));
    s.style.background = c; s.onclick = () => { accent = c;
      [...sws.children].forEach(x=>x.classList.remove("on")); s.classList.add("on");
      box.closest(".prof-wrap").style.setProperty("--me", c); }; sws.append(s); }
  box.append(sws);
  // shape / size / align
  const mk = (label, opts, cur) => { box.append(el("div","lbl",label));
    const row = el("div","shapes"); const st = {v: cur};
    for (const o of opts) { const b = el("div","opt"+(o===cur?" on":""), o);
      b.onclick = () => { st.v = o; [...row.children].forEach(x=>x.classList.remove("on"));
        b.classList.add("on"); }; row.append(b); } box.append(row); return st; };
  const shape = mk("Picture shape", ["circle","squircle","square","triangle"], p.avatar_shape);
  const size  = mk("Picture size", ["s","m","l"], p.avatar_size);
  const align = mk("Placement", ["left","center","right"], p.avatar_align);
  // uploads
  box.append(el("div","lbl","Picture"));
  const av = document.createElement("input"); av.type="file"; av.accept="image/*"; box.append(av);
  box.append(el("div","lbl","Banner"));
  const bn = document.createElement("input"); bn.type="file"; bn.accept="image/*"; box.append(bn);
  const save = el("button","btn-accent","Save profile"); save.style.marginTop="14px";
  save.onclick = async () => {
    const fd = new FormData();
    fd.append("name", name.value); fd.append("bio", bio.value);
    fd.append("accent", accent); fd.append("avatar_shape", shape.v);
    fd.append("avatar_size", size.v); fd.append("avatar_align", align.v);
    if (av.files[0]) fd.append("avatar", av.files[0]);
    if (bn.files[0]) fd.append("banner", bn.files[0]);
    const r = await fetch("/api/profile", {method:"POST", body:fd});
    if (r.ok) openProfile(STATE.identity_pub);
    else alert("Save failed: " + await r.text());
  };
  box.append(save);
  return box;
}

function renderPostsInto(root, posts, mine) {
  root.replaceChildren();
  for (const p of posts) {
    const post = el("div", "post");
    const meta = el("div", "meta");
    meta.append(el("span","author",p.author_name),
      el("span","dim",new Date(p.created_at*1000).toLocaleString()));
    post.append(meta, el("div","",p.text));
    for (const h of p.blobs) { const img=document.createElement("img");
      img.src="/api/blob/"+h; post.append(img); }
    root.append(post);
  }
}
```

Then make feed author names and friend rows open profiles. In `renderFeed`, change the author span to a clickable link:
```javascript
    const author = el("span", "author", p.author_name);
    author.style.cursor = "pointer";
    author.onclick = () => openProfile(p.identity_pub);
    meta.append(author, /* ...the rest unchanged... */);
```
In `renderState`'s friends loop, make each friend row open the profile:
```javascript
    row.style.cursor = "pointer";
    row.onclick = () => openProfile(f.identity_pub);
```
And update `refresh()`'s revoked copy string if it references "Hearth" (it does not — no change), and ensure `restoreView()` still only handles feed/messages (profile is not persisted; fine).

- [ ] **Step 2: Update `hearth/demo.py` seed copy + `README.md`**

In `hearth/demo.py`, seed a richer profile so the demo shows the feature. After the cast is built and before closing nodes, replace the two `set_profile`-less flow by adding accents (ASCII only, no image bytes needed):
```python
    wong.set_profile("Wong", bio="Designer i Kobenhavn.", accent="#2743d6",
                     avatar_shape="squircle", avatar_align="center")
    freja.set_profile("Freja", bio="Keramiker og fotograf.", accent="#c0563b",
                      avatar_shape="circle", avatar_align="left")
```
(Place these right after the friend-ceremony completes, before `ensure_enckey()`/close. They publish updated profiles that gossip with the rest.)

Update the printed banner text and any "Hearth" occurrences in demo prints to "Loop". In `README.md`, change the top-of-file product name usages from Hearth to Loop where they are the product's display name (leave the `hearth` package/path references intact), and add one line to the run section: "Open the Me tab to customize your profile — accent, picture shape and placement, banner, bio."

- [ ] **Step 3: Full suite**

Run: `.\.venv\Scripts\python.exe -m pytest tests -q`
Expected: zero failures (JS/demo/README changes don't touch Python behavior; web-asset test still green).

- [ ] **Step 4: Demo smoke check (headless, then visual)**

Run the demo in the background: `.\.venv\Scripts\python.exe -m hearth demo`, wait ~10s. Headless checks with Python/httpx:
- `GET http://127.0.0.1:7201/api/profile/<wong-identity>` returns `accent == "#2743d6"`, `avatar_shape == "squircle"`, and a non-empty `posts` list. (Get Wong's identity from `GET /api/state`.)
- `GET http://127.0.0.1:7203/api/state` has `"revoked": false`.
Then kill the demo process(es) and delete `run/` (`Remove-Item -Recurse -Force run`).

Report the visual URLs for the user to open (7201 Wong phone, 7203 Freja phone): Feed shows the Loop wordmark + light theme; the Me tab opens the profile with editor; clicking a friend/author opens their profile.

- [ ] **Step 5: Commit**

```bash
git add hearth/web/app.js hearth/demo.py README.md
git commit -m "feat: profile page + editor, Me tab, profile entry points, Loop demo copy"
```

---

## Plan self-review notes

- **Spec coverage:** name swap + wordmark + achromatic theme (T6); profile message model with enums/palette/bio (T1); Pillow transcode gate incl. animation-strip + downscale + re-encode (T2); store full record + posts-by (T3); node rich set_profile w/ carry-forward + profile_view (T4); API read route + multipart POST w/ 400/413 (T5); profile page + editor + entry points + demo seed (T7). Dark mode / animated avatars / custom-hex / stories / personas / calls explicitly out of scope per spec.
- **Backward-compat flagged:** `make_profile`/`set_profile` stay call-compatible with the existing name-only callers via defaults (T1/T4); `store.profiles()` unchanged so feed/state/conversations keep working (T3); the two `test_api.py` profile tests are updated in lockstep (T5). `create()`'s `set_profile(person_name)` positional call remains valid.
- **Type consistency:** accent stored/validated lowercase; `ACCENTS` identical tuple in messages.py (T1), api.py import (T5), app.js `ACCENTS` array (T7) — all the same 10 lowercase hexes. `profile_view` keys match what app.js reads (name, bio, accent, avatar, avatar_shape, avatar_size, avatar_align, banner, identity_pub, mine, posts).
- **Known risks:** (1) FastAPI `File(default=None)` yields `None` when no file part is sent (verified pattern already used by DM photos). (2) `test_missing_body_keys_return_400` — the profile line must accept 422 now that `name` is a required Form field; T5 step 1 calls this out. (3) The wordmark SVG uses `<text>` with the system font — renders consistently since it's the same stack; ring stroke width tuned in CSS. (4) `color-mix` is used in CSS for DM bubble + banner tints — supported in current browsers; if the user's browser is old it degrades to the fallback background (still legible). (5) Triangle avatar uses clip-path and drops the border by design (border on a clip-path is not visible).
