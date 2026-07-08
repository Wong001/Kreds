# Deletion Hardening + Honesty Pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make protocol-enforced deletion actually hold under out-of-order gossip (all message kinds, not just posts), and state the honest deletion boundary where users and readers see it.

**Architecture:** One surgical change in `Store.ingest_message` (generalize the existing tombstone-on-arrival guard from posts to every non-delete kind, keeping the author-match authorization) plus a covering SQL index. UI gets a single shared confirm helper that every delete affordance routes through. README/ROADMAP get the approved D3 wording. No transport, crypto, or API changes.

**Tech Stack:** Python 3.12 (`.venv`), sqlite3, pytest, vanilla JS (`hearth/web/app.js`).

**Spec:** `docs/superpowers/specs/2026-07-03-deletion-hardening-design.md`

## Global Constraints

- Branch: `deletion-hardening` off `main`. One workstream; nothing unrelated.
- Test runner: `.venv\Scripts\python.exe -m pytest tests -q` (Windows PowerShell). Full suite must be green at every commit.
- ASCII only in any console prints (cp1252); ASCII-style hyphens in README (matches its existing style).
- Confirm copy, verbatim from spec: `Delete for everyone? This removes it from your friends' devices running Loop. A modified app or a screenshot can still have kept a copy.`
- D3 formulation for docs, verbatim from spec: "Deletion is structural against strangers — they never had it — and automatic among friends running compliant clients."
- Delete button label stays exactly `delete everywhere` (spec decision — the confirm carries the boundary).
- Rejected approach (do NOT implement): pre-tombstoning a delete tag's absent target. It skips the author check and becomes a censorship vector. The guard must always match `identity_pub`.

---

### Task 1: Generalize the delete-before-content guard in the store

**Files:**
- Modify: `hearth/store.py` (schema block at top; the `elif kind == KIND_POST:` branch inside `ingest_message`, currently around line 238)
- Test: `tests/test_store_ingest.py` (append tests; extend one import line)

**Interfaces:**
- Consumes: existing `Store.ingest_message(msg) -> IngestResult`, `make_dm/make_story/make_profile/make_delete/make_post` from `hearth.messages`, the `wong(tmp_path)` helper already defined at the top of `tests/test_store_ingest.py`.
- Produces: `ingest_message` now returns `IngestResult(True, "deleted on arrival", mid, deleted_target=mid)` for ANY non-delete kind whose delete tag (same author) is already stored. Task 2's integration tests rely on this behavior; no signature changes.

- [ ] **Step 1: Create the branch**

```powershell
git checkout main; git pull; git checkout -b deletion-hardening
```

- [ ] **Step 2: Write the failing unit tests**

In `tests/test_store_ingest.py`, replace the import line

```python
from hearth.messages import blob_hash, make_delete, make_post, make_profile
```

with

```python
from hearth.messages import (
    blob_hash, make_delete, make_dm, make_post, make_profile, make_story,
)
```

and append at the end of the file:

```python
def _friend(store, name):
    dev = DeviceKeys.create(name)
    IdentityCeremony().enroll_first_device(dev)
    store.add_identity(dev.identity_pub)
    return dev


def test_delete_tag_arriving_before_dm(tmp_path):
    s, phone, _ = wong(tmp_path)
    friend = _friend(s, "freja-phone")
    ref = s.put_blob(b"encrypted-photo-ct")
    dm = make_dm(phone, friend.identity_pub, body_nonce="ab" * 12,
                 body_ct="deadbeef", wraps={}, created_at=100.0,
                 blob_refs=[ref])
    tag = make_delete(phone, dm.msg_id)
    assert s.ingest_message(tag).accepted            # tag first
    r = s.ingest_message(dm)                         # content second
    assert r.accepted and r.reason == "deleted on arrival"
    assert r.deleted_target == dm.msg_id
    assert s.get_message(dm.msg_id) is None
    assert s.is_tombstoned(dm.msg_id)
    assert not s.has_blob(ref)                       # blob GC'd
    r2 = s.ingest_message(dm)                        # gossip echo
    assert (r2.accepted, r2.reason) == (False, "tombstoned")


def test_delete_tag_arriving_before_story(tmp_path):
    s, phone, _ = wong(tmp_path)
    media = s.put_blob(b"story-media-bytes")
    story = make_story(phone, "photo", media)
    tag = make_delete(phone, story.msg_id)
    assert s.ingest_message(tag).accepted
    r = s.ingest_message(story)
    assert r.accepted and r.reason == "deleted on arrival"
    assert s.get_message(story.msg_id) is None
    assert s.is_tombstoned(story.msg_id)
    assert not s.has_blob(media)


def test_delete_tag_arriving_before_profile(tmp_path):
    s, phone, _ = wong(tmp_path)
    prof = make_profile(phone, "Wong")
    tag = make_delete(phone, prof.msg_id)
    assert s.ingest_message(tag).accepted
    r = s.ingest_message(prof)
    assert r.accepted and r.reason == "deleted on arrival"
    assert phone.identity_pub not in s.profiles()


def test_foreign_delete_tag_does_not_censor_unarrived_message(tmp_path):
    # Mallory (a friend) saw the post elsewhere, knows its msg_id, and
    # sends a preemptive delete tag hoping to censor it on nodes that do
    # not have it yet. The author-match in the guard must defeat this.
    s, phone, _ = wong(tmp_path)
    mal = _friend(s, "mallory")
    post = make_post(phone, "mine, not yet arrived")
    assert s.ingest_message(make_delete(mal, post.msg_id)).accepted
    r = s.ingest_message(post)
    assert (r.accepted, r.reason) == (True, "ok")
    assert [p["text"] for p in s.feed()] == ["mine, not yet arrived"]
```

- [ ] **Step 3: Run the new tests, verify they fail the right way**

Run: `.venv\Scripts\python.exe -m pytest tests/test_store_ingest.py -v`

Expected: `test_delete_tag_arriving_before_dm`, `..._before_story`, `..._before_profile` FAIL (reason comes back `"ok"` instead of `"deleted on arrival"` — the bug). `test_foreign_delete_tag_does_not_censor_unarrived_message` already PASSES on current code (it pins the authorization property so the fix cannot regress it). All pre-existing tests PASS, including `test_delete_tag_arriving_before_post`.

- [ ] **Step 4: Implement the fix**

In `hearth/store.py`, inside `ingest_message`, change the post-only guard

```python
            elif kind == KIND_POST:
                # A delete tag for this post may have gossiped in first.
                if self._db.execute(
                        "SELECT 1 FROM messages WHERE kind=? AND target_id=?"
                        " AND identity_pub=?",
                        (KIND_DELETE, mid, identity)).fetchone():
                    self._tombstone(mid, "deleted")
                    self._db.commit()
                    self.gc_blobs()
                    return IngestResult(True, "deleted on arrival", mid,
                                        deleted_target=mid)
```

to run for every non-delete kind:

```python
            else:
                # A delete tag for this message may have gossiped in first
                # (out-of-order delivery hits every kind, not just posts).
                # The identity_pub match keeps delete authorization intact:
                # only the author's own tag can kill content on arrival.
                if self._db.execute(
                        "SELECT 1 FROM messages WHERE kind=? AND target_id=?"
                        " AND identity_pub=?",
                        (KIND_DELETE, mid, identity)).fetchone():
                    self._tombstone(mid, "deleted")
                    self._db.commit()
                    self.gc_blobs()
                    return IngestResult(True, "deleted on arrival", mid,
                                        deleted_target=mid)
```

(The only code change on this block is `elif kind == KIND_POST:` becoming `else:` plus the comment. `KIND_POST` remains imported/used elsewhere in the file.)

In the `_SCHEMA` string at the top of `hearth/store.py`, after the `tombstones` table statement, add the covering index for the guard query (IF NOT EXISTS makes it retrofit existing DBs on open):

```sql
CREATE INDEX IF NOT EXISTS idx_delete_guard
  ON messages(kind, target_id, identity_pub);
```

- [ ] **Step 5: Run the unit tests, verify all pass**

Run: `.venv\Scripts\python.exe -m pytest tests/test_store_ingest.py -v`
Expected: ALL PASS.

- [ ] **Step 6: Run the full suite**

Run: `.venv\Scripts\python.exe -m pytest tests -q`
Expected: ALL PASS (no existing behavior depends on post-only narrowing).

- [ ] **Step 7: Commit**

```powershell
git add hearth/store.py tests/test_store_ingest.py
git commit -m "fix: delete-before-content race - tombstone-on-arrival for all kinds"
```

---

### Task 2: Adverse-order convergence integration tests (real sockets)

**Files:**
- Create: `tests/test_deletion_convergence.py`

**Interfaces:**
- Consumes: `HearthNode.create(dir, name, device_name)`, `HearthNode(dir)`, `node.compose_dm(to_identity, text) -> msg_id`, `node.delete_post(msg_id)` (generic: publishes a delete tag for any own msg_id), `node.dm_thread(identity)`, `node.ensure_enckey()`, `store.get_message/is_tombstoned/has_blob/put_blob/ingest_message`, `SyncService`, `make_story` — all existing. Task 1's generalized guard.
- Produces: nothing consumed later; these are the spec's system-level proof.

- [ ] **Step 1: Write the integration tests**

Create `tests/test_deletion_convergence.py`. The adverse-order shape both tests force: one peer holds live content and misses the delete tag (went offline in between); a second peer receives ONLY the tag (the author already tombstoned the content, so it can no longer serve it); the tag-holder then syncs with the content-holder — the fixed guard must kill the late-arriving content on ingest, and the tag must tombstone it on the content-holder, converging every store to deleted.

```python
"""Adverse-order deletion convergence over real gossip sockets.

Proves the spec's system-level claim: a node that receives a delete tag
BEFORE the content it targets (possible whenever tag and content travel
by different paths) converges to deleted, for DMs and stories, on every
compliant node. Fails on pre-fix stores, where the late content
resurrects permanently.
"""
import asyncio
import json as _json

from hearth.identity import DeviceKeys, DeviceView
from hearth.messages import make_story
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


def pair_home_node(owner, hd, device_name):
    hd.mkdir()
    home_dev = DeviceKeys.create(device_name)
    home_dev.install(
        owner.device.enroll_other(home_dev.device_pub, home_dev.name),
        owner.device.to_json()["identity_priv"])
    (hd / "keys.json").write_text(_json.dumps(home_dev.to_json()))
    home = HearthNode(hd)
    home.store.add_identity(home.identity_pub, is_self=True)
    home.store.save_views(
        home.identity_pub,
        {home_dev.device_pub: DeviceView(cert=home_dev.cert)})
    return home


def test_dm_delete_tag_beats_late_content(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "fp", "Freja", "freja-phone")
        home = pair_home_node(freja, tmp_path / "fh", "freja-homenode")
        befriend(wong, freja)
        home.store.add_identity(wong.identity_pub)
        for n in (wong, freja, home):
            n.ensure_enckey()
        sw, wa = await started(wong)
        sf, fa = await started(freja)
        sh, ha = await started(home)
        # everyone learns everyone's enckeys
        await sw.sync_with(fa); await sw.sync_with(ha)
        await sf.sync_with(ha)
        # DM lands on freja-home only; freja-phone stays out of the loop
        mid = wong.compose_dm(freja.identity_pub, "fortryd mig")
        await sw.sync_with(ha)
        assert home.store.get_message(mid) is not None
        # freja-home goes offline BEFORE the delete
        await sh.stop()
        wong.delete_post(mid)          # tombstones immediately on wong
        # freja-phone can now only ever hear the tag from wong
        await sw.sync_with(fa)
        assert freja.store.get_message(mid) is None
        # tag with absent target must NOT pre-tombstone (authorization
        # is only checkable when the content arrives)
        assert not freja.store.is_tombstoned(mid)
        # freja-home returns holding the live DM; phone must not resurrect
        sh2, ha2 = await started(home)
        await sf.sync_with(ha2)
        assert freja.dm_thread(wong.identity_pub) == []
        assert freja.store.is_tombstoned(mid)
        # and the phone's tag killed it on the home node too
        assert home.store.get_message(mid) is None
        assert home.store.is_tombstoned(mid)
        for s in (sw, sf, sh2):
            await s.stop()
    asyncio.run(scenario())


def test_story_delete_tag_beats_late_content(tmp_path):
    async def scenario():
        wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
        freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
        mads = HearthNode.create(tmp_path / "m", "Mads", "mads-phone")
        befriend(wong, freja); befriend(wong, mads); befriend(freja, mads)
        sw, wa = await started(wong)
        sf, fa = await started(freja)
        sm, ma = await started(mads)
        # wong authors a story at store level (sidesteps the Pillow/ffmpeg
        # transcode gates; gossip treats it identically)
        media = wong.store.put_blob(b"story-media-bytes")
        story = make_story(wong.device, "photo", media)
        assert wong.store.ingest_message(story).accepted
        # freja receives story + blob, then goes offline
        await sf.sync_with(wa)
        assert freja.store.get_message(story.msg_id) is not None
        assert freja.store.has_blob(media)
        await sf.stop()
        # wong deletes; mads hears only the tag; then wong goes offline
        wong.delete_post(story.msg_id)
        await sm.sync_with(wa)
        assert mads.store.get_message(story.msg_id) is None
        await sw.stop()
        # freja returns holding live content; mads must not resurrect it,
        # and mads' tag must kill it on freja
        sf2, fa2 = await started(freja)
        await sm.sync_with(fa2)
        assert mads.store.get_message(story.msg_id) is None
        assert mads.store.is_tombstoned(story.msg_id)
        assert not mads.store.has_blob(media)
        assert freja.store.is_tombstoned(story.msg_id)
        assert not freja.store.has_blob(media)
        for s in (sf2, sm):
            await s.stop()
    asyncio.run(scenario())
```

- [ ] **Step 2: Run the new tests, verify they pass with the fix**

Run: `.venv\Scripts\python.exe -m pytest tests/test_deletion_convergence.py -v`
Expected: 2 PASS.

- [ ] **Step 3: Prove they fail without the fix (spec success criterion)**

Temporarily restore main's `store.py`, run, then restore the branch version:

```powershell
git checkout main -- hearth/store.py
.venv\Scripts\python.exe -m pytest tests/test_deletion_convergence.py -q
```

Expected: 2 FAILED (the deleted DM/story resurrects on the tag-holding node).

```powershell
git checkout HEAD -- hearth/store.py
.venv\Scripts\python.exe -m pytest tests/test_deletion_convergence.py -q
```

Expected: 2 passed. Working tree clean again (`git status` shows no changes to `hearth/store.py`).

- [ ] **Step 4: Run the full suite**

Run: `.venv\Scripts\python.exe -m pytest tests -q`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```powershell
git add tests/test_deletion_convergence.py
git commit -m "test: adverse-order deletion convergence over real gossip"
```

---

### Task 3: Honest delete confirm in the UI

**Files:**
- Modify: `hearth/web/app.js` (helper near the top after `el()`; the two delete-button call sites, currently around lines 97-108 and 538-549)
- Test: `tests/test_web_assets.py` (append one test)

**Interfaces:**
- Consumes: existing `j(url, opts)` fetch helper in `app.js`.
- Produces: `deleteEverywhere(msgId) -> Promise<boolean>` — THE single deletion entry point in the UI; any future delete affordance (DMs, stories) must call it.

- [ ] **Step 1: Write the failing asset test**

Append to `tests/test_web_assets.py`:

```python
def test_delete_confirm_copy_present():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    # honest-boundary copy, verbatim from the deletion-hardening spec
    assert ("A modified app or a screenshot can still have kept a copy."
            in js)
    # one shared helper: definition + two call sites, and no bare
    # /api/delete POST outside it
    assert js.count("deleteEverywhere(") == 3
    assert js.count('"/api/delete"') == 1
```

- [ ] **Step 2: Run it, verify it fails**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py::test_delete_confirm_copy_present -v`
Expected: FAIL (copy string absent).

- [ ] **Step 3: Implement the helper and rewire both call sites**

In `hearth/web/app.js`, directly after the `el()` function definition, add:

```js
const DELETE_CONFIRM =
  "Delete for everyone? This removes it from your friends' devices " +
  "running Loop. A modified app or a screenshot can still have kept a copy.";

// Every deletion affordance must route through this helper so the honest
// boundary is always shown (spec: 2026-07-03 deletion hardening). When
// DMs/stories grow delete buttons, they call this too.
async function deleteEverywhere(msgId) {
  if (!confirm(DELETE_CONFIRM)) return false;
  await j("/api/delete", {method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({msg_id: msgId})});
  return true;
}
```

Feed call site — replace:

```js
      const btn = el("button", "", "delete everywhere");
      btn.onclick = async () => {
        await j("/api/delete", {method: "POST",
          headers: {"Content-Type": "application/json"},
          body: JSON.stringify({msg_id: p.msg_id})});
        refresh();
      };
```

with:

```js
      const btn = el("button", "", "delete everywhere");
      btn.onclick = async () => {
        if (await deleteEverywhere(p.msg_id)) refresh();
      };
```

Profile call site — replace:

```js
      const btn = el("button", "", "delete everywhere");
      btn.onclick = async () => {
        await j("/api/delete", {method: "POST",
          headers: {"Content-Type": "application/json"},
          body: JSON.stringify({msg_id: p.msg_id})});
        openProfile(p.identity_pub);
      };
```

with:

```js
      const btn = el("button", "", "delete everywhere");
      btn.onclick = async () => {
        if (await deleteEverywhere(p.msg_id)) openProfile(p.identity_pub);
      };
```

- [ ] **Step 4: Run the asset tests, then the full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_web_assets.py -v`
Expected: ALL PASS.
Run: `.venv\Scripts\python.exe -m pytest tests -q`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```powershell
git add hearth/web/app.js tests/test_web_assets.py
git commit -m "feat: honest delete confirm - shared deleteEverywhere helper"
```

---

### Task 4: README + ROADMAP honesty updates

**Files:**
- Modify: `README.md` (new section after "Encrypted messages")
- Modify: `ROADMAP.md` (Honest status bullet; Shipped list; tech-debt backlog line)

**Interfaces:**
- Consumes: nothing from code; wording from the spec.
- Produces: docs only.

- [ ] **Step 1: Add the README Deletion section**

Append to `README.md` (after the "Encrypted messages" section, README's ASCII-hyphen style):

```markdown
## Deletion

Deletion is structural against strangers - they never had it - and
automatic among friends running compliant clients. Deleting sends a
signed delete tag that gossips to every device holding the content;
compliant clients tombstone the message, drop its media blobs, and
refuse re-ingest - in any arrival order (a delete tag arriving before
its content still wins; fixed and tested for all message kinds).

The boundary, stated honestly: this is compliant-client behavior, not
DRM. A modified client can keep what it already received, and a
screenshot survives everything. Only the author of a message can
delete it - a friend's delete tag for someone else's content is
rejected.
```

- [ ] **Step 2: Update ROADMAP.md**

(a) Honest-status bullet — replace:

```markdown
- **Deletion is protocol-enforced among honest friends, not DRM.** A malicious peer can retain what it received. This is stated honestly by design, not hidden.
```

with:

```markdown
- **Deletion is protocol-enforced among honest friends, not DRM.** A malicious peer can retain what it received. This is stated honestly by design, not hidden — and shown in the delete confirm. The out-of-order gossip race (a delete tag arriving before its content silently resurrected DMs/stories/profiles) is fixed and tested for all message kinds (2026-07-03).
```

(b) Shipped list — change the heading `## Shipped (5 features)` to `## Shipped (6 features)` and append after item 5:

```markdown
6. **Deletion hardening + honesty pass** — delete-before-content race fixed for every message kind (was posts-only; deleted DMs/stories/profiles could resurrect under out-of-order gossip), covering index, adverse-order convergence tests over real sockets; honest delete confirm in the UI; README/ROADMAP state the D3 boundary.
```

(c) Tech-debt backlog — replace:

```markdown
- **Data/perf:** add SQL indexes on message scans; dedupe the double `/api/conversations` fetch per refresh; share a post-row helper between `feed` and `posts_by`; `enckeys()` latest-wins tiebreak (same class as the profile fix, narrower).
```

with:

```markdown
- **Data/perf:** add SQL indexes on message scans (delete-guard index landed 2026-07-03; feed/thread scan indexes still open); dedupe the double `/api/conversations` fetch per refresh; share a post-row helper between `feed` and `posts_by`; `enckeys()` latest-wins tiebreak (same class as the profile fix, narrower).
```

- [ ] **Step 3: Run the full suite one last time**

Run: `.venv\Scripts\python.exe -m pytest tests -q`
Expected: ALL PASS.

- [ ] **Step 4: Commit**

```powershell
git add README.md ROADMAP.md
git commit -m "docs: deletion semantics - README section + ROADMAP honest status"
```

---

## Completion

After Task 4: request whole-branch review (superpowers:requesting-code-review), then superpowers:finishing-a-development-branch — merge `deletion-hardening` to `main` and push to `origin` (github.com/Wong001/Loop.git) once reviewed clean. The next workstream (DM forward secrecy) starts only after this one is merged.
