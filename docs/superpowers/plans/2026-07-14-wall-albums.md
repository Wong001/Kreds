# Wall Albums (Collage Slice C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Multi-photo wall blocks become swipeable stacked decks (no photo ever hidden), and albums become growable: a mutable, signed, latest-wins `album` record groups immutable photo posts, with Add-photos and Ungroup on the owner's blocks.

**Architecture:** New `KIND_ALBUM` record (the `profile_layout` idiom): `{album_id (hex64), members: [post msg_ids, ordered]}` — latest-wins per `(identity, album_id)`; members stay ordinary per-recipient-encrypted profile posts, the record carries opaque ids only. `profile_view` folds member posts into one album pseudo-block (`msg_id` = `album_id`, so pins/spans/drag/modal work unchanged) whose `photos` list is the concatenation of decryptable members' photos. The client renders any multi-photo block — album or plain multi-photo post — as a `.block-deck` (the composer preview's stacked look, wall edition) with arrows/swipe and a generalized lightbox. `set_album(members=[])` un-groups.

**Tech Stack:** Python (existing record/store/API idioms), vanilla JS, pytest, gated Playwright smoke.

**Spec:** `docs/superpowers/specs/2026-07-13-wall-collage-redesign-design.md` §5. Slices A (pins/tray/endpoints) and B (composer preview) are complete on the branch.

## Global Constraints

- Branch `kreds-quickwins-0.3.10`; no version bump; NO AI trailers; no new dependencies; ASCII-only output; `node --check hearth/web/app.js` clean after every app.js edit; `.venv\Scripts\python.exe -m pytest ...`.
- Spec §5 exacts: photo-only members (video posts rejected); the member list is opaque-id plaintext metadata (same honesty class as pins — comment where read); a viewer's deck contains ONLY members they can decrypt; a fully-undecryptable album renders nothing (honest hole/absence); membership suppresses standalone rendering; deleting the album record (empty `members`) un-groups — members reappear standalone, unplaced.
- Album pseudo-blocks carry `"msg_id": album_id` so every Slice-A layout path (pins, spans, drag, modal, `WALL_PINS`) works untouched — album_id is deliberately the same 64-hex width as a msg_id (spec §5).
- Deck CSS MUST carry the Slice-B stacking-context lesson: the stacked-edge `::before/::after` need `z-index: 0` on their host or they paint behind the page background (pixel-probed bug class; the composer smoke's pixel probe is the precedent).
- v1 UI honest limit (goes in ROADMAP, Task 7): deleting a single photo from an album = Ungroup first, then delete the standalone post — delete-everywhere propagation itself is unchanged; only the one-tap UI path is deferred.
- Small uniformity extension of spec §5 (deliberate, record here): the self-only "Add photos" affordance appears on EVERY own photo block (single-photo and multi-photo posts too, not just album blocks) — growing a post-deck mints an album wrapping `[that post, new post]` exactly as the spec describes for multi-photo posts; a single photo growing the same way is the same mechanism and omitting it would be an arbitrary hole.

---

### Task 1: `KIND_ALBUM` record (messages.py)

**Files:**
- Modify: `hearth/messages.py` (kind constant near `KIND_PROFILE_LAYOUT` ~line 17; `make_album` near `make_profile_layout` ~line 125; validation branch near the `KIND_PROFILE_LAYOUT` branch ~line 272)
- Test: `tests/test_messages_albums.py` (create)

**Interfaces:**
- Produces: `KIND_ALBUM = "album"`; `make_album(device, album_id, members, now=None)` → payload `{"kind": KIND_ALBUM, "album_id": ..., "members": list(members), "created_at": ...}` (mirror `make_profile_layout`'s construction/signing exactly); validation: `album_id` hex64, `members` a list of hex64 with `len <= MAX_LAYOUT`, duplicates rejected, empty list VALID (ungroup).

- [ ] **Step 1: Write the failing test**

Create `tests/test_messages_albums.py` (device construction: copy the exact `DeviceKeys`/enroll pattern from the top of `tests/test_messages_pins.py` — it is the current idiom):

```python
"""KIND_ALBUM validation (collage Slice C): a mutable grouping record
over immutable photo posts - opaque ids only, latest-wins per
(identity, album_id)."""
from hearth.messages import KIND_ALBUM, MAX_LAYOUT, make_album, validate_payload

AID = "ab" * 32
MID = "cd" * 32


def _mk(members, album_id=AID, dev=None):
    # build `dev` exactly as tests/test_messages_pins.py does
    from tests.test_messages_pins import _mk as _pins_mk  # reuse device setup if factored; else inline the same pattern
    raise NotImplementedError  # replace with the real device construction per the file you mirrored


def test_album_roundtrip_valid():
    m = _mk([MID, "ef" * 32])
    ok, why = validate_payload(m.payload)
    assert ok, why
    assert m.payload["kind"] == KIND_ALBUM
    assert m.payload["album_id"] == AID
    assert m.payload["members"] == [MID, "ef" * 32]


def test_empty_members_valid_ungroup():
    ok, why = validate_payload(_mk([]).payload)
    assert ok, why


def test_bad_albums_rejected():
    ok, _ = validate_payload(_mk([MID], album_id="zz").payload)
    assert not ok
    ok, _ = validate_payload(_mk(["nothex"]).payload)
    assert not ok
    ok, _ = validate_payload(_mk([MID, MID]).payload)          # duplicate member
    assert not ok
    ok, _ = validate_payload(_mk([MID] + ["%064x" % i for i in range(MAX_LAYOUT)]).payload)
    assert not ok
```

(The `_mk` helper stub above is a deliberate read-the-idiom instruction: replace it with the real construction copied from `tests/test_messages_pins.py` — do not import a private helper across test files if that file didn't export one; inline the same few lines.)

- [ ] **Step 2: RED**

Run: `.venv\Scripts\python.exe -m pytest tests/test_messages_albums.py -v`
Expected: FAIL — `ImportError: cannot import name 'KIND_ALBUM'`.

- [ ] **Step 3: Implement**

In `hearth/messages.py`: add `KIND_ALBUM = "album"` next to `KIND_PROFILE_LAYOUT`; add `make_album` next to `make_profile_layout`, building `{"kind": KIND_ALBUM, "album_id": album_id, "members": list(members), "created_at": _now(now)}` through the same signing helper `make_profile_layout` uses; add the validation branch after `KIND_PROFILE_LAYOUT`'s:

```python
    if kind == KIND_ALBUM:
        # A mutable grouping over immutable photo posts. Opaque ids only -
        # the member list is plaintext metadata (same existence-disclosure
        # class as the layout order/pins); content stays per-post encrypted.
        if not _is_hex64(p.get("album_id")):
            return False, "bad album id"
        members = p.get("members")
        if not isinstance(members, list) or len(members) > MAX_LAYOUT:
            return False, "bad album members"
        if not all(_is_hex64(x) for x in members):
            return False, "bad album member"
        if len(set(members)) != len(members):
            return False, "duplicate album member"
        return True, "ok"
```

Also confirm (read, don't assume) that record kinds without `recipient` ride the sync entitlement paths generically the way `KIND_PROFILE_LAYOUT` does (`store.messages_not_in`'s kind handling) — if `KIND_PROFILE_LAYOUT` is special-cased anywhere by name (grep for it in `hearth/store.py` and `hearth/sync.py`), `KIND_ALBUM` needs the same treatment; report what you find in your task report either way.

- [ ] **Step 4: GREEN** — the new file + `tests/test_messages.py` + `tests/test_messages_pins.py` all pass.

- [ ] **Step 5: Commit**

```bash
git add hearth/messages.py tests/test_messages_albums.py
git commit -m "feat(albums): KIND_ALBUM record - {album_id, members[]} opaque-id grouping, empty members = ungroup, latest-wins class"
```

---

### Task 2: Store resolver

**Files:**
- Modify: `hearth/store.py` (new `albums` method next to `profile_layout` ~line 444)
- Test: `tests/test_store_albums.py` (create)

**Interfaces:**
- Consumes: `KIND_ALBUM` (Task 1).
- Produces: `store.albums(identity_pub) -> dict[str, list[str]]` — `{album_id: members}` resolved latest-wins per album_id with the `(created_at, seq, device_pub)` key `profile_layout` uses; empty-members records INCLUDED in the dict (node decides what empty means). Task 3 consumes this exact shape.

- [ ] **Step 1: Write the failing test**

Create `tests/test_store_albums.py` (node/store construction: mirror `tests/test_block_pins.py`'s `HearthNode.create` pattern; publish album records through a Task-3-independent path — build them with `make_album(node.device, ...)` and `node.store.ingest_message` or the node's `_publish` if accessible; mirror how `tests/test_store_dm.py` crafts and ingests records):

```python
"""Latest-wins album resolution per (identity, album_id)."""
from hearth.messages import make_album
from hearth.node import HearthNode

AID1 = "aa" * 32
AID2 = "bb" * 32
M1, M2, M3 = "11" * 32, "22" * 32, "33" * 32


def test_albums_latest_wins_per_id(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Anna", "anna-pc")
    n.store.ingest_message(make_album(n.device, AID1, [M1], now=100.0))
    n.store.ingest_message(make_album(n.device, AID2, [M2], now=101.0))
    n.store.ingest_message(make_album(n.device, AID1, [M1, M3], now=102.0))
    albums = n.store.albums(n.identity_pub)
    assert albums[AID1] == [M1, M3]        # newest record wins for AID1
    assert albums[AID2] == [M2]


def test_empty_members_resolves_empty(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Anna", "anna-pc")
    n.store.ingest_message(make_album(n.device, AID1, [M1], now=100.0))
    n.store.ingest_message(make_album(n.device, AID1, [], now=101.0))
    assert n.store.albums(n.identity_pub)[AID1] == []
```

(If `ingest_message` refuses own-device records in this shape, use whatever internal publish path `tests/test_store_dm.py`-class tests use — the assertion contract is the resolver's output.)

- [ ] **Step 2: RED** — `AttributeError: 'Store' object has no attribute 'albums'`.

- [ ] **Step 3: Implement**

In `hearth/store.py`, next to `profile_layout` (mirror its query/locking/tie-break exactly, but resolve PER album_id):

```python
    def albums(self, identity_pub: str) -> dict:
        """Latest-wins {album_id: members} for this author (collage Slice
        C). Same (created_at, seq, device_pub) tie-break as profile_layout;
        resolved per album_id. Empty members lists are returned as-is -
        the node layer treats them as ungrouped."""
        with self._lock:
            best: dict = {}
            best_key: dict = {}
            for seq, dpub, mj in self._db.execute(
                    "SELECT seq, device_pub, msg_json FROM messages"
                    " WHERE kind=? AND identity_pub=?",
                    (KIND_ALBUM, identity_pub)):
                p = json.loads(mj)["payload"]
                aid = p["album_id"]
                key = (p["created_at"], seq, dpub)
                if aid not in best or key > best_key[aid]:
                    best[aid] = list(p.get("members", []))
                    best_key[aid] = key
            return best
```

(import `KIND_ALBUM` alongside the existing kind imports.)

- [ ] **Step 4: GREEN**, then `.venv\Scripts\python.exe -m pytest tests/test_store_albums.py tests/test_store_dm.py -v`.

- [ ] **Step 5: Commit**

```bash
git add hearth/store.py tests/test_store_albums.py
git commit -m "feat(albums): Store.albums latest-wins resolver per (identity, album_id) - profile_layout idiom"
```

---

### Task 3: `set_album` + `/api/album` + profile_view folding

**Files:**
- Modify: `hearth/node.py` (new `set_album` near `set_block_span`; `profile_view` fold after the pin/span annotation)
- Modify: `hearth/api.py` (new route after `/api/block-span`)
- Test: `tests/test_albums_node.py` (create), `tests/test_api_pins.py` (append one album API test — same fixture)

**Interfaces:**
- Consumes: Tasks 1-2; `secrets.token_hex(32)` for minting.
- Produces: `node.set_album(members, album_id=None) -> str` (returns the album_id; mints when None; `ValueError` → 400 on: non-list, >MAX_LAYOUT, non-hex64 ids, duplicates, a member that isn't THIS identity's own profile-placement photo post (missing message, foreign author, `placement != "profile"`, `media == "video"`), or empty members WITHOUT an album_id). `POST /api/album` `{members, album_id?}` → `{"ok": true, "album_id": ...}`. `profile_view` album folding: member posts removed from `wall`; per album with ≥1 decryptable photo, one pseudo-entry appended containing `"album": True`, `"msg_id": album_id`, `"mine"`, `"photos": [{"m": member_msg_id, "h": blob_hash}, ...]` (member order, each member's blobs in order), `"count": len(photos)`, `"created_at"`: newest member's, `"pin"`/`"span"` from the layout maps keyed by album_id (span default `{"w": 2, "h": 2}` — a deck is a compact object, not a banner), `"scope_newest"`: the newest member's scope (the Add-photos default). Wall ordering: pseudo-entries participate in the newest-first flow by their `created_at`.

- [ ] **Step 1: Write the failing test**

Create `tests/test_albums_node.py`:

```python
"""set_album validation + profile_view folding (collage Slice C)."""
import pytest

from hearth.node import HearthNode

PNG = None  # replace with the tiny-valid-PNG helper/bytes the photo tests use
            # (find it where tests/test_block_pins.py's photo test got it)


def _node(tmp_path):
    return HearthNode.create(tmp_path / "n", "Anna", "anna-pc")


def _photo_post(n, text="p"):
    return n.compose_post(text, scope="kreds", placement="profile",
                          photos=[PNG])   # adapt kwarg to the real signature


def test_set_album_mints_and_validates(tmp_path):
    n = _node(tmp_path)
    a = _photo_post(n)
    b = _photo_post(n)
    aid = n.set_album([a, b])
    assert len(aid) == 64
    assert n.store.albums(n.identity_pub)[aid] == [a, b]
    aid2 = n.set_album([b, a], album_id=aid)      # reorder in place
    assert aid2 == aid
    assert n.store.albums(n.identity_pub)[aid] == [b, a]


def test_set_album_rejects_bad_members(tmp_path):
    n = _node(tmp_path)
    a = _photo_post(n)
    t = n.compose_post("text only", scope="kreds", placement="profile")
    j = n.compose_post("journal", scope="kreds", placement="journal")
    with pytest.raises(ValueError):
        n.set_album([a, a])                        # duplicate
    with pytest.raises(ValueError):
        n.set_album(["zz"])                        # malformed id
    with pytest.raises(ValueError):
        n.set_album([a, "ff" * 32])                # unknown post
    with pytest.raises(ValueError):
        n.set_album([j])                           # journal placement
    with pytest.raises(ValueError):
        n.set_album([t])                           # no photos
    with pytest.raises(ValueError):
        n.set_album([], album_id=None)             # empty needs an id (ungroup only)


def test_profile_view_folds_members_into_album(tmp_path):
    n = _node(tmp_path)
    a = _photo_post(n, "one")
    b = _photo_post(n, "two")
    solo = _photo_post(n, "solo")
    aid = n.set_album([a, b])
    view = n.profile_view(n.identity_pub)
    ids = [p["msg_id"] for p in view["wall"]]
    assert a not in ids and b not in ids           # suppressed standalone
    assert solo in ids
    alb = next(p for p in view["wall"] if p.get("album"))
    assert alb["msg_id"] == aid
    assert [ph["m"] for ph in alb["photos"]] == [a, b]
    assert alb["count"] == 2
    assert alb["span"] == {"w": 2, "h": 2}
    assert alb["mine"] is True
    assert alb["scope_newest"] == "kreds"


def test_ungroup_restores_standalone(tmp_path):
    n = _node(tmp_path)
    a = _photo_post(n)
    aid = n.set_album([a])
    n.set_album([], album_id=aid)
    view = n.profile_view(n.identity_pub)
    ids = [p["msg_id"] for p in view["wall"]]
    assert a in ids
    assert not any(p.get("album") for p in view["wall"])
```

Append to `tests/test_api_pins.py` (same fixture as its existing tests; PNG upload via the multipart pattern `tests/test_api_profile_posts.py` uses for photos):

```python
def test_album_api_roundtrip(client_fixture):
    c, node = client_fixture
    # post two photo posts through the API (mirror test_api_profile_posts'
    # photo upload), then group, then ungroup
    ...  # adapt: two posts -> mids m1, m2
    r = c.post("/api/album", json={"members": [m1, m2]})
    assert r.status_code == 200
    aid = r.json()["album_id"]
    prof = c.get("/api/profile/" + node.identity_pub).json()
    alb = next(p for p in prof["wall"] if p.get("album"))
    assert alb["msg_id"] == aid and alb["count"] == 2
    assert c.post("/api/album", json={"members": ["zz"]}).status_code == 400
    assert c.post("/api/album",
                  json={"members": [], "album_id": aid}).status_code == 200
```

(The `...` is a read-the-fixture instruction: replicate the exact photo-upload calls that file already makes; the assertions are the contract.)

- [ ] **Step 2: RED** — `AttributeError: set_album`.

- [ ] **Step 3: Implement node.set_album** (after `set_block_span`; import `secrets`, `KIND_ALBUM`, `make_album`):

```python
    def set_album(self, members, album_id: str | None = None) -> str:
        """Group own profile photo posts into a growable album (collage
        Slice C). Members must be THIS identity's own profile-placement
        photo posts; empty members un-groups an EXISTING album. The record
        carries opaque ids only - content stays per-post encrypted."""
        if not isinstance(members, list) or len(members) > MAX_LAYOUT:
            raise ValueError("bad album members")
        if len(set(members)) != len(members):
            raise ValueError("duplicate album member")
        if not members and album_id is None:
            raise ValueError("empty album needs an album_id (ungroup)")
        for mid in members:
            self._check_block_id(mid)
            msg = self.store.get_message(mid)
            if msg is None or msg.cert.identity_pub != self.identity_pub:
                raise ValueError("album member must be your own post")
            pl = msg.payload
            if pl.get("kind") != KIND_POST or pl.get("placement") != "profile":
                raise ValueError("album member must be a profile post")
            if pl.get("media") == "video" or not pl.get("blobs"):
                raise ValueError("album members are photo posts")
        if album_id is None:
            album_id = secrets.token_hex(32)
        else:
            self._check_block_id(album_id)
        return self._publish(make_album(self.device, album_id, members)) and album_id
```

(NOTE: `_publish` returns the msg_id — do NOT use `and`; write it as two statements: `self._publish(...)` then `return album_id`. The snippet above flags the trap deliberately; implement the two-statement form.)

- [ ] **Step 4: Implement the profile_view fold** — after the existing pin/span annotation loop, before the return:

```python
        # Album folding (Slice C): members render inside their album's
        # deck, never standalone; the album pseudo-block borrows the
        # msg_id slot (album_id, same 64-hex width) so every layout path
        # (pins/spans/drag/modal) treats it like any block. A viewer's
        # deck holds only members THEY decrypted; an album with none is
        # simply absent (honest hole). Empty members = ungrouped.
        albums = self.store.albums(identity_pub)
        by_id = {p["msg_id"]: p for p in wall}
        member_of = {}
        for aid, mids in albums.items():
            for mid in mids:
                member_of.setdefault(mid, aid)     # first album wins a conflict
        folded = [p for p in wall if p["msg_id"] not in member_of]
        for aid, mids in albums.items():
            photos, newest, scope_newest = [], None, "kreds"
            for mid in mids:
                p = by_id.get(mid)
                if p is None or member_of.get(mid) != aid:
                    continue                        # undecryptable/unknown/conflicted
                for h in p.get("blobs") or []:
                    photos.append({"m": mid, "h": h})
                if newest is None or p["created_at"] > newest:
                    newest = p["created_at"]
                    scope_newest = p.get("scope", "kreds")
            if not photos:
                continue
            pin = pins.get(aid)
            folded.append({
                "album": True, "msg_id": aid,
                "mine": identity_pub == self.identity_pub,
                "photos": photos, "count": len(photos),
                "created_at": newest, "scope_newest": scope_newest,
                "pin": pin,
                "span": ({"w": pin["w"], "h": pin["h"]} if pin
                         else spans.get(aid) or {"w": 2, "h": 2}),
            })
        folded.sort(key=lambda p: p["created_at"], reverse=True)
        wall = folded
```

(`pins`/`spans` are already in scope from the Slice-A annotation code; keep the final `"wall": wall` return. The full-wall re-sort keeps newest-first semantics with pseudo-entries mixed in — pinned blocks ignore list order anyway.)

- [ ] **Step 5: API route** (after `/api/block-span`):

```python
    @app.post("/api/album")
    async def album(body: dict = Body(...)):
        aid = _400(lambda: node.set_album(body["members"],
                                          body.get("album_id")))
        return {"ok": True, "album_id": aid}
```

- [ ] **Step 6: GREEN** — the two new/extended test files + `tests/test_block_pins.py` + `tests/test_api_profile_posts.py`; then the full suite once (expect all pass + 4 skips; anything failing outside album scope = real break, stop).

- [ ] **Step 7: Commit**

```bash
git add hearth/node.py hearth/api.py tests/test_albums_node.py tests/test_api_pins.py
git commit -m "feat(albums): set_album (mint/reorder/grow/ungroup, own-photo-post validation) + POST /api/album + profile_view folds members into a deck pseudo-block keyed by album_id"
```

---

### Task 4: Two-node integration

**Files:**
- Test: `tests/test_albums_integration.py` (create)

**Interfaces:** consumes everything above over real sockets; harness = the `befriend`/`started` helpers pattern of `tests/test_profile_pins_integration.py` (copy them inline as that file does).

- [ ] **Step 1: Write the test**

```python
"""Two-node album integration over real sync sockets: an album syncs,
GROWS across the wire, an Inner-scoped member stays out of a Kreds-only
viewer's deck AND stays suppressed standalone, ungroup restores."""
import asyncio

from hearth.node import HearthNode
from hearth.sync import SyncService

PNG = None   # the same tiny-valid-PNG the other photo tests use


def befriend(a, b):
    a.store.add_identity(b.identity_pub)
    b.store.add_identity(a.identity_pub)


async def started(node):
    svc = SyncService(node)
    port = await svc.start("127.0.0.1", 0)
    node.store.set_meta("gossip_addr", f"127.0.0.1:{port}")
    return svc, f"127.0.0.1:{port}"


def test_album_grows_and_scopes_over_sync(tmp_path):
    async def scenario():
        a = HearthNode.create(tmp_path / "a", "Anna", "anna-pc")
        b = HearthNode.create(tmp_path / "b", "Bo", "bo-pc")
        befriend(a, b)
        for n in (a, b):
            n.ensure_enckey()
        sa, aa = await started(a)
        sb, ba = await started(b)
        await sa.sync_with(ba)

        p1 = a.compose_post("en", scope="kreds", placement="profile", photos=[PNG])
        p2 = a.compose_post("hemmelig", scope="inner", placement="profile", photos=[PNG])
        aid = a.set_album([p1, p2])
        await sa.sync_with(ba)

        view = b.profile_view(a.identity_pub)
        alb = next(p for p in view["wall"] if p.get("album"))
        assert [ph["m"] for ph in alb["photos"]] == [p1]     # inner member absent
        assert alb["count"] == 1
        ids = [p["msg_id"] for p in view["wall"]]
        assert p1 not in ids and p2 not in ids               # suppressed standalone

        # grow: a new photo post + republished record reach B
        p3 = a.compose_post("tre", scope="kreds", placement="profile", photos=[PNG])
        a.set_album([p1, p2, p3], album_id=aid)
        await sa.sync_with(ba)
        view = b.profile_view(a.identity_pub)
        alb = next(p for p in view["wall"] if p.get("album"))
        assert [ph["m"] for ph in alb["photos"]] == [p1, p3]

        # ungroup restores standalone on BOTH sides
        a.set_album([], album_id=aid)
        await sa.sync_with(ba)
        view = b.profile_view(a.identity_pub)
        assert not any(p.get("album") for p in view["wall"])
        ids = [p["msg_id"] for p in view["wall"]]
        assert p1 in ids and p3 in ids and p2 not in ids     # p2 inner: still invisible

        for s in (sa, sb):
            await s.stop()
    asyncio.run(scenario())
```

- [ ] **Step 2: Run** — expected PASS (Tasks 1-3 shipped it; a failure is real — this is also where any sync-entitlement gap for the new kind shows up. Investigate, don't loosen).
- [ ] **Step 3: Commit** — `test(albums): two-node integration - album syncs, grows, scopes honestly, ungroups`

---

### Task 5: Deck rendering + generalized lightbox (client)

**Files:**
- Modify: `hearth/web/app.js` — `renderBlock` (photo branch ~line 374), `photoGridClass` (delete, ~line 350), `openLightbox` (~line 1965) + its call sites
- Modify: `hearth/web/style.css` (deck rules; delete `.block-gallery` rules)
- Test: `tests/test_web_assets.py` (append)

**Interfaces:**
- Consumes: wall entries — plain posts (`p.blobs`, `p.msg_id`) and album pseudo-blocks (`p.album`, `p.photos: [{m, h}]`, `p.count`, `p.mine`, `p.scope_newest`).
- Produces: `blockPhotoItems(p)` → uniform `[{m, h}]` for BOTH shapes (single/multi-photo post → its own msg_id per blob; album → `p.photos`); `renderDeck(p, items)` inside renderBlock; `openLightbox(items, index, opener)` NEW signature (items `[{m, h}]`). `.block-deck` / `.deck-nav` / `.deck-count` (badge class shared with the composer). Task 6 adds the owner controls onto this markup; Task 7's smoke drives it.

- [ ] **Step 1: Failing asset test**

```python
def test_wall_deck_wired():
    # Slice C: any multi-photo block - album or plain post - is a
    # swipeable stacked deck; the transitional cropped gallery is gone.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert "photoGridClass" not in js
    assert "block-gallery" not in js and "block-gallery" not in css
    rb = _js_fn_body(js, "renderBlock")
    assert "blockPhotoItems" in rb and "block-deck" in rb
    items = _js_fn_body(js, "blockPhotoItems")
    assert "photos" in items and "blobs" in items
    lb = _js_fn_body(js, "openLightbox")
    assert "items[i].m" in lb or "items[i].h" in lb
    deck_rule = _css_rule(css, ".block-deck")
    assert "z-index: 0" in deck_rule       # the Slice-B stacking lesson
    assert ".block-deck::before" in css
```

- [ ] **Step 2: RED** on `photoGridClass not in js`.

- [ ] **Step 3: Implement**

(a) Delete `photoGridClass`. Add:

```js
// Uniform photo list for a block: a plain post contributes its own
// msg_id per blob; an album pseudo-block (server-folded, Slice C) already
// carries cross-post {m, h} pairs. Either way the deck and lightbox see
// one shape.
function blockPhotoItems(p) {
  if (p.album) return p.photos;
  return (p.blobs || []).map(h => ({m: p.msg_id, h}));
}
```

(b) In `renderBlock`, replace the photo branch (the old gallery/`photoGridClass` code) with a deck for 2+ photos, single photo unchanged (`.block-photo`):

```js
  } else if (p.album || (p.blobs && p.blobs.length)) {
    const items = blockPhotoItems(p);
    if (items.length === 1) {
      const media = el("div", "block-photo");
      const img = document.createElement("img");
      img.src = "/api/post-blob/" + items[0].m + "/" + items[0].h;
      img.alt = ""; img.draggable = false; img.style.cursor = "zoom-in";
      img.tabIndex = -1;
      img.onclick = () => { if (!ARRANGING) openLightbox(items, 0, img); };
      media.append(img);
      block.append(media);
    } else if (items.length) {
      block.append(renderDeck(p, items));
    }
  }
```

(c) Add `renderDeck` above `renderBlock`:

```js
// Stacked deck (Slice C): the top photo fills the cells; stacked edges +
// a count badge say "there's more"; arrows/swipe flip through, and a tap
// (outside Arrange) opens the lightbox at the current photo. No photo is
// ever hidden - the 3rd, 5th, 12th are one swipe away at any size.
function renderDeck(p, items) {
  const deck = el("div", "block-deck");
  let i = 0;
  const img = document.createElement("img");
  img.alt = ""; img.draggable = false; img.style.cursor = "zoom-in";
  img.tabIndex = -1;
  const badge = el("span", "deck-count");
  const prev = el("button", "deck-nav deck-prev");
  prev.type = "button"; prev.textContent = "‹";
  prev.setAttribute("aria-label", "Previous photo");
  const next = el("button", "deck-nav deck-next");
  next.type = "button"; next.textContent = "›";
  next.setAttribute("aria-label", "Next photo");
  const show = () => {
    img.src = "/api/post-blob/" + items[i].m + "/" + items[i].h;
    badge.textContent = (i + 1) + "/" + items.length;
    prev.disabled = i === 0;
    next.disabled = i === items.length - 1;
  };
  prev.onclick = (e) => { e.stopPropagation(); if (i > 0) { i--; show(); } };
  next.onclick = (e) => { e.stopPropagation(); if (i < items.length - 1) { i++; show(); } };
  img.onclick = () => { if (!ARRANGING) openLightbox(items, i, img); };
  // touch swipe, same 40px threshold as the lightbox; passive pointer
  // tracking only - Arrange mode's drag takes pointerdown before us via
  // the block handler, so gate on !ARRANGING.
  let sx = null;
  deck.addEventListener("pointerdown", (e) => { if (!ARRANGING) sx = e.clientX; });
  deck.addEventListener("pointerup", (e) => {
    if (sx == null) return;
    const dx = e.clientX - sx; sx = null;
    if (Math.abs(dx) > 40) {
      if (dx < 0 && i < items.length - 1) { i++; show(); }
      else if (dx > 0 && i > 0) { i--; show(); }
    }
  });
  deck.append(img, badge, prev, next);
  show();
  return deck;
}
```

(d) `openLightbox` new signature `openLightbox(items, index, opener)`: inside, replace `msgId`/`blobs` usage with `items` — `img.src = "/api/post-blob/" + items[i].m + "/" + items[i].h;`, length checks on `items.length`. Update every call site (grep `openLightbox(`): the single-photo branch above already passes items; any other caller gets the same `[{m, h}]` mapping inline.

(e) style.css — delete `.block-gallery` rules; add (note the stacking-context lesson):

```css
/* Wall deck (Slice C): the composer preview's stacked look, wall
   edition. z-index: 0 establishes a stacking context so the ::before/
   ::after edges (z-index -1) paint above the page background - the
   Slice-B pixel-probed lesson, do not remove. */
.block-deck { position: relative; width: 100%; height: 100%; z-index: 0; }
.block-deck img { width: 100%; height: 100%; object-fit: cover;
  border-radius: 12px; display: block; }
.block-deck::before, .block-deck::after { content: ""; position: absolute;
  inset: 0; border-radius: 12px; background: var(--line-2);
  border: 1px solid var(--line); z-index: -1; }
.block-deck::before { transform: rotate(2deg) translate(4px, 2px); }
.block-deck::after { transform: rotate(-1.6deg) translate(-3px, 3px); }
.block-deck .deck-count { position: absolute; top: 8px; right: 8px;
  z-index: 2; }
.deck-nav { position: absolute; top: 50%; transform: translateY(-50%);
  z-index: 2; width: 28px; height: 28px; border-radius: 50%;
  border: none; background: color-mix(in srgb, var(--ink) 55%, transparent);
  color: var(--paper); font-size: 18px; line-height: 1; cursor: pointer; }
.deck-nav:disabled { opacity: .25; cursor: default; }
.deck-prev { left: 8px; }
.deck-next { right: 8px; }
```

(`.deck-count`'s base styling from Slice B already exists — the absolute positioning here layers on it; verify it composes, adjust locally if the composer variant's position rule conflicts.)

- [ ] **Step 4: Verify** — `node --check`; full asset file green (legacy gallery/grid asserts updated in place with "retired by collage Slice C, spec 2026-07-13 §5").

- [ ] **Step 5: Commit** — `feat(albums): multi-photo blocks are swipeable stacked decks (album or plain post, one shape) - gallery retired, lightbox generalized to cross-post items, stacked edges carry the stacking-context lesson`

---

### Task 6: Owner controls — Add photos + Ungroup (client)

**Files:**
- Modify: `hearth/web/app.js` — `renderBlock` (self-only controls), `openBlockSettings` (Ungroup for album blocks)
- Modify: `hearth/web/style.css` (small `.block-add` styling)
- Test: `tests/test_web_assets.py` (append)

**Interfaces:**
- Consumes: `p.album`, `p.msg_id` (= album_id), `p.photos`, `p.scope_newest`, `p.mine`, `blockPhotoItems`; `POST /api/post` (multipart) + `POST /api/album`.
- Produces: `addPhotosToBlock(p, files)` — posts ONE new photo post (scope = `p.scope_newest` for albums, `p.scope` for plain posts) then `POST /api/album` with members = existing members + new msg_id (album) or `[p.msg_id, new]` (plain post → mints, spec §5); `.block-add` self-only button + hidden file input on every own photo block; "Ungroup" button in the settings modal for album blocks (`POST /api/album {album_id: p.msg_id, members: []}`), and NO "Delete everywhere" on album pseudo-blocks (ungroup first — the v1 honest limit).

- [ ] **Step 1: Failing asset test**

```python
def test_album_owner_controls_wired():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    body = _js_fn_body(js, "addPhotosToBlock")
    assert '"/api/album"' in body and "scope_newest" in body
    rb = _js_fn_body(js, "renderBlock")
    assert "block-add" in rb
    assert "p.album" in rb                       # album blocks skip the del button
    modal = _js_fn_body(js, "openBlockSettings")
    assert "Ungroup" in modal
```

- [ ] **Step 2: RED**. 

- [ ] **Step 3: Implement**

(a) `addPhotosToBlock` near `renderDeck`:

```js
// Add photos to an own photo block (Slice C): posts ONE new immutable
// photo post at the block's scope, then republishes the album record
// with it appended - growing a plain post-deck mints the album that
// wraps it (spec 2026-07-13 section 5). Post immutability untouched.
async function addPhotosToBlock(p, files) {
  if (!files || !files.length) return;
  const scope = p.album ? (p.scope_newest || "kreds") : (p.scope || "kreds");
  const fd = new FormData();
  fd.append("text", "");
  fd.append("scope", scope);
  fd.append("placement", "profile");
  for (const f of files) fd.append("photos", f);
  const r = await fetch("/api/post", {method: "POST", body: fd});
  if (!r.ok) { alert("Couldn't add: " + await r.text()); return; }
  const { msg_id } = await r.json();
  const members = p.album
    ? [...new Set(p.photos.map(ph => ph.m))].concat(msg_id)
    : [p.msg_id, msg_id];
  const ar = await fetch("/api/album", {method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify(p.album
      ? {album_id: p.msg_id, members}
      : {members})});
  if (!ar.ok) alert("Added the photos, but couldn't grow the album: " + await ar.text());
  if (CURRENT_PROFILE) openProfile(CURRENT_PROFILE);
}
```

(b) In `renderBlock`'s `p.mine` section: album blocks get the scope badge from `scope_newest` SKIPPED (mixed scopes — no badge, one-line comment) and NO "Delete everywhere" (`if (!p.album)` around the existing del button, comment: ungroup first, v1 honest limit); every own PHOTO block (album or plain, i.e. `blockPhotoItems(p).length > 0`) gains:

```js
    const add = el("label", "block-add");
    add.title = "Add photos";
    add.textContent = "+";
    const addInput = document.createElement("input");
    addInput.type = "file"; addInput.accept = "image/*"; addInput.multiple = true;
    addInput.className = "visually-hidden";
    addInput.onchange = () => addPhotosToBlock(p, [...addInput.files]);
    add.append(addInput);
    block.append(add);
```

(c) In `openBlockSettings`, for `p.album` blocks add an "Ungroup" button after "Send to tray"/"Place on canvas" (own `settings-opt` button; `postJSON("/api/album", {album_id: p.msg_id, members: []})` then `reopenAfterAction(null)`); a one-line comment: members reappear standalone and unplaced.

(d) CSS:

```css
.block-add { position: absolute; bottom: 6px; left: 50%; transform: translateX(-50%);
  z-index: 2; width: 26px; height: 26px; border-radius: 50%; display: grid;
  place-items: center; background: color-mix(in srgb, var(--ink) 55%, transparent);
  color: var(--paper); font-size: 17px; cursor: pointer; }
```

- [ ] **Step 4: Verify** — `node --check`; asset file green.
- [ ] **Step 5: Commit** — `feat(albums): own photo blocks grow in place - Add photos posts at the block's scope and (re)mints the album record; Ungroup in the settings modal; album blocks drop delete-everywhere (ungroup first, v1 honest limit)`

---

### Task 7: Live smoke + ROADMAP + suite

**Files:**
- Create: `tests/test_ui_smoke_albums.py`
- Modify: `ROADMAP.md`

- [ ] **Step 1: Smoke** (import `LiveNode`; PNGs via Pillow like the composer smoke): own profile → post 3 photos via the composer (scoped `.profile-composer .postbtn` — the B2 lesson) → the wall/tray block is a `.block-deck` with badge "1/3" → arrow-click twice → badge "3/3" → click the photo (NOT in arrange) → lightbox opens at 3/3 and Escape closes → `+` Add photos with 1 more PNG → deck badge shows "/4" after re-render and `node.store.albums(...)` shows a 2-member album (original post + new post) → Arrange → gear on the deck → "Ungroup" → two standalone blocks reappear (the 3-photo deck and the 1-photo block), `albums` resolves empty for that id → pixel probe: the deck's stacked edge paints outside the card (reuse the composer smoke's probe shape) → friend-view leg optional: a second `LiveNode` synced once, `profile_view` shows the folded deck. Zero pageerrors. 3 consecutive greens; gate check skips.
- [ ] **Step 2: Full suite twice** — all pass + 5 env-gated skips (TOR_E2E + four UI_E2E), consistent. `node --check` clean.
- [ ] **Step 3: ROADMAP** — replace the Slice B increment's closing "Slice C..." sentence with the Slice C increment paragraph: albums shipped (mutable KIND_ALBUM grouping record over immutable per-recipient-encrypted photo posts, latest-wins per (identity, album_id); decks everywhere a block has 2+ photos — album or plain post — top photo filling the cells, stacked edges, count badge, arrows/swipe/lightbox, no photo ever hidden; Add photos grows in place at the block's scope, growing a plain post mints its album; Ungroup restores standalone; a viewer's deck holds only members they decrypt, fully-undecryptable albums leave honest holes; member list is opaque-id metadata, same class as pins). Name the v1 honest limits: deleting a single album photo = ungroup first; albums are photo-only; the collage redesign (spec 2026-07-13) is COMPLETE — Bento Phase B leftovers (free-form beyond this, split-column, edit-in-place) remain the deferred tail.
- [ ] **Step 4: Commit** — `test(albums): live smoke (deck flip, lightbox, add-photos mints/grows, ungroup, edge pixel probe); roadmap - collage redesign complete`

---

## Self-review (done at write time)

1. **Spec §5 coverage:** record/idiom → T1-2; own-post/photo-only validation + minting + folding + suppression + honest-hole + scope-filtered decks → T3 (+T4 over the wire); growing + plain-post-mints + ungroup-restores → T3/T4/T6; deck rendering + swipe + lightbox + count badge → T5; member-list honesty comment → T1 validation + T3 fold comment; pin-by-album_id → T3 (`msg_id` slot) + Global Constraints; add-photos scope default = newest member → T3 `scope_newest` + T6; v1 delete-limit + photo-only → ROADMAP (T7) + Global Constraints.
2. **Placeholder scan:** three deliberate read-the-idiom stubs (T1 `_mk` device construction, T3 PNG helper + API photo-upload lines, T4 PNG) — each points at a named existing file to copy from; everything else is complete code.
3. **Type consistency:** `set_album(members, album_id=None) -> str` across T3/T4/T6-via-API; pseudo-block fields (`album`, `msg_id`=album_id, `photos [{m,h}]`, `count`, `scope_newest`, `pin`, `span`) identical in T3 fold, T5 `blockPhotoItems`/`renderDeck`, T6 controls; `openLightbox(items, index, opener)` consistent T5 both branches; endpoints `/api/album` verbatim T3/T6/T7.
