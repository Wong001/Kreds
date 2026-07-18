# Reactions + Comments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Friend reactions (fixed six) and a comment section on journal posts via author-relay with private-by-default engagement (per-post alias + anonymous mutual-box), and story reactions/replies delivered as DMs with story context.

**Architecture:** Spec: `docs/superpowers/specs/2026-07-18-reactions-comments-design.md`. Responders send `KIND_RESPONSE` wrapped to the author's devices only (existing wrap-set routing). The author's node sweeps ingested responses after each sync round (the `prune_superseded_*` hook precedent, sync.py:273) and republishes a latest-wins `KIND_RESPONSES` record per post, re-encrypted to the post's audience, carrying responder-signed entries. Identity disclosure is two-tier: alias for strangers, real identity sealed in anonymous bucket-padded slots only the responder's own friends can open. Stories: a plain DM with an additive `story_ref`.

**Tech Stack:** Python 3.12 (cryptography lib X25519/ChaCha20-Poly1305/HKDF — no new dependency), vanilla JS `hearth/web/app.js`, pytest (+ Playwright behind `UI_E2E=1`).

## Global Constraints

- Run tests with `.venv\Scripts\python.exe -m pytest ...`; ASCII in Python strings; commit style `type(scope): summary`, NO AI trailers.
- Reaction tokens exactly `("heart", "laugh", "wow", "sad", "up", "fire")` + `"clear"`; rendered ❤️ 😂 😮 😢 👍 🔥 client-side. No free emoji.
- `MAX_COMMENT = 500` (chars, server-enforced + client-mirrored).
- Validation is fail-closed with container type-guards on every new field (the referenced_blobs TypeError lesson — a hostile payload must never brick sync/sweep).
- Private-by-default: `public` False unless the settings toggle (`public_engagement`, default off) is on at compose time. The author ALWAYS learns the responder's real identity. Mutual-box slots carry NO recipient identifiers; slot counts pad to buckets (8, 16, 32, 64).
- Aliases: fresh random seed per (responder, post); stable within the post; never reused across posts.
- Comments flat, append-ordered; reactions latest-wins per (responder, post); retraction honored automatically by the author's node (compliant-client, stated honestly).
- Journal-placement posts only in v1 (wall comments = named follow-up).
- Release ships with `min_core_for_web` bumped (recorded for the release checklist; NOT set in this branch).
- Old cores refuse the new kinds harmlessly (wrap_grant precedent) — verify, don't assume.

---

### Task 1: crypto — anonymous sealed slots (`dmcrypt.py`)

**Files:**
- Modify: `hearth/dmcrypt.py` (append)
- Test: `tests/test_dmcrypt.py` (append; read its local style first)

**Interfaces:**
- Produces: `seal_slots(payload: bytes, enc_pubs: list[str]) -> list[dict]` — each slot `{"eph_pub": hex, "nonce": hex, "ct": hex}`, padded with dummy slots to the next bucket of `_SLOT_BUCKETS = (8, 16, 32, 64)` (payloads larger than 64 real recipients raise ValueError); slot order shuffled. `try_open_slots(slots, enc_priv_hex: str) -> bytes | None` — trial-decrypts, returns the payload or None. AAD constant `MUTUAL_BOX_AAD = b"hearth/mutual-box/v1"`.
- Consumes: existing `_derive_kek` HKDF pattern (new info string — do NOT reuse the dm-wrap info).

- [ ] **Step 1: Write the failing tests**

```python
from hearth.dmcrypt import (gen_enc_keypair, seal_slots, try_open_slots)


def test_sealed_slots_friend_opens_stranger_cannot():
    fpriv, fpub = gen_enc_keypair()
    spriv, spub = gen_enc_keypair()          # stranger keys, never sealed to
    slots = seal_slots(b"identity-payload", [fpub])
    assert try_open_slots(slots, fpriv) == b"identity-payload"
    assert try_open_slots(slots, spriv) is None


def test_sealed_slots_bucket_padding_and_anonymity():
    pubs = [gen_enc_keypair()[1] for _ in range(3)]
    slots = seal_slots(b"x", pubs)
    assert len(slots) == 8                    # 3 real -> 8-bucket
    # no recipient identifiers anywhere in a slot
    assert all(set(s.keys()) == {"eph_pub", "nonce", "ct"} for s in slots)
    pubs17 = [gen_enc_keypair()[1] for _ in range(17)]
    assert len(seal_slots(b"x", pubs17)) == 32
    import pytest
    with pytest.raises(ValueError):
        seal_slots(b"x", [gen_enc_keypair()[1] for _ in range(65)])


def test_sealed_slots_dummy_slots_indistinguishable_shape():
    slots = seal_slots(b"payload", [gen_enc_keypair()[1]])
    # every slot (real or dummy) has hex fields of plausible length;
    # a dummy must not be identifiable by shape alone
    lens = {(len(s["eph_pub"]), len(s["nonce"])) for s in slots}
    assert lens == {(64, 24)}


def test_sealed_slots_empty_recipients():
    # a responder with zero friends still produces a full dummy bucket
    slots = seal_slots(b"x", [])
    assert len(slots) == 8
    priv, _ = gen_enc_keypair()
    assert try_open_slots(slots, priv) is None
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests\test_dmcrypt.py -q`
Expected: ImportError on `seal_slots`; existing tests pass.

- [ ] **Step 3: Implement (append to `hearth/dmcrypt.py`)**

```python
MUTUAL_BOX_AAD = b"hearth/mutual-box/v1"
_SLOT_BUCKETS = (8, 16, 32, 64)


def _derive_slot_kek(shared: bytes) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=None,
                info=b"hearth/mutual-box/v1").derive(shared)


def seal_slots(payload: bytes, enc_pubs) -> list:
    """Anonymous per-recipient slots (spec 2026-07-18, engagement
    privacy): each real slot is an ephemeral-X25519 + ChaCha20-Poly1305
    box with NO recipient identifier - recipients trial-open. Padded
    with byte-random dummy slots to a fixed bucket so the slot count
    only buckets, never measures, the sender's friend-device count.
    Order shuffled so real slots aren't prefix-positioned."""
    import random
    real = []
    for enc_pub in enc_pubs:
        try:
            peer = X25519PublicKey.from_public_bytes(bytes.fromhex(enc_pub))
        except ValueError:
            continue                      # skip bad keys, like wrap_key
        eph = X25519PrivateKey.generate()
        kek = _derive_slot_kek(eph.exchange(peer))
        nonce = os.urandom(12)
        real.append({"eph_pub": eph.public_key().public_bytes_raw().hex(),
                     "nonce": nonce.hex(),
                     "ct": ChaCha20Poly1305(kek).encrypt(
                         nonce, payload, MUTUAL_BOX_AAD).hex()})
    bucket = next((b for b in _SLOT_BUCKETS if b >= len(real)), None)
    if bucket is None:
        raise ValueError("too many recipients for a mutual box")
    ct_len = len(real[0]["ct"]) if real else (len(payload) + 16) * 2
    while len(real) < bucket:
        real.append({"eph_pub": os.urandom(32).hex(),
                     "nonce": os.urandom(12).hex(),
                     "ct": os.urandom(ct_len // 2).hex()})
    random.shuffle(real)
    return real


def try_open_slots(slots, enc_priv_hex: str):
    """Trial-open every slot; the one sealed to this device decrypts,
    all others (other recipients' and dummies) fail AEAD auth."""
    try:
        priv = X25519PrivateKey.from_private_bytes(
            bytes.fromhex(enc_priv_hex))
    except ValueError:
        return None
    for s in slots:
        if not isinstance(s, dict):
            continue
        try:
            shared = priv.exchange(X25519PublicKey.from_public_bytes(
                bytes.fromhex(s["eph_pub"])))
            kek = _derive_slot_kek(shared)
            return ChaCha20Poly1305(kek).decrypt(
                bytes.fromhex(s["nonce"]), bytes.fromhex(s["ct"]),
                MUTUAL_BOX_AAD)
        except (InvalidTag, ValueError, KeyError, TypeError):
            continue
    return None
```

- [ ] **Step 4: Run** `.venv\Scripts\python.exe -m pytest tests\test_dmcrypt.py -q` — ALL pass. Note: `test_sealed_slots_dummy_slots_indistinguishable_shape` requires all real cts equal length for one payload (true — same payload) and dummies matching (the `ct_len` mirror). If a bucket has zero real slots the dummy ct length falls back to payload-derived length — deterministic.

- [ ] **Step 5: Commit** `git add hearth/dmcrypt.py tests/test_dmcrypt.py` / `git commit -m "feat(dmcrypt): anonymous bucket-padded sealed slots for engagement privacy"`

---

### Task 2: messages — `KIND_RESPONSE` / `KIND_RESPONSES` + validation

**Files:**
- Modify: `hearth/messages.py` (kind constants near the existing KIND_* block; builders near make_post; validation branches in validate_payload)
- Test: `tests/test_messages.py` (append)

**Interfaces:**
- Produces: `KIND_RESPONSE = "response"`, `KIND_RESPONSES = "responses"`, `REACTION_TOKENS = ("heart", "laugh", "wow", "sad", "up", "fire")`, `MAX_COMMENT = 500`;
  `make_response(device, target, body_nonce, body_ct, wraps, created_at=None) -> SignedMessage` (payload `{kind, target, body_nonce, body_ct, wraps, created_at}` — rkind/body/alias/public/mutual_box live INSIDE the encrypted body, invisible to relays);
  `make_responses(device, target, body_nonce, body_ct, wraps, expires_at=None, created_at=None) -> SignedMessage`;
  `response_aad(responder_identity, target, created_at) -> bytes` and `responses_aad(author_identity, target, created_at) -> bytes` in `hearth/dmcrypt.py` (mirroring `dm_aad`/`post_aad`).
- Consumes: nothing new beyond existing signing.

- [ ] **Step 1: Write the failing tests**

```python
def test_response_kinds_validate():
    from hearth.messages import (KIND_RESPONSE, KIND_RESPONSES,
                                 validate_payload)
    base = {"kind": KIND_RESPONSE, "target": "m" * 16,
            "body_nonce": "ab" * 12, "body_ct": "de",
            "wraps": {}, "created_at": 1.0}
    assert validate_payload(base)[0]
    for k, bad in [("target", 7), ("target", ""), ("body_nonce", "zz"),
                   ("body_ct", ""), ("wraps", "junk")]:
        p = dict(base); p[k] = bad
        assert not validate_payload(p)[0], k
    rec = {"kind": KIND_RESPONSES, "target": "m" * 16,
           "body_nonce": "ab" * 12, "body_ct": "de", "wraps": {},
           "expires_at": None, "created_at": 1.0}
    assert validate_payload(rec)[0]
    rec["expires_at"] = "soon"
    assert not validate_payload(rec)[0]


def test_reaction_tokens_frozen():
    from hearth.messages import REACTION_TOKENS, MAX_COMMENT
    assert REACTION_TOKENS == ("heart", "laugh", "wow", "sad", "up", "fire")
    assert MAX_COMMENT == 500
```

- [ ] **Step 2: Run to verify failure** — ImportError first.

- [ ] **Step 3: Implement.** Constants next to the other KIND_*; builders:

```python
def make_response(device: DeviceKeys, target: str, body_nonce: str,
                  body_ct: str, wraps: dict,
                  created_at: Optional[float] = None) -> SignedMessage:
    return device.sign_message({
        "kind": KIND_RESPONSE, "target": target,
        "body_nonce": body_nonce, "body_ct": body_ct, "wraps": wraps,
        "created_at": _now(created_at),
    })


def make_responses(device: DeviceKeys, target: str, body_nonce: str,
                   body_ct: str, wraps: dict,
                   expires_at: Optional[float] = None,
                   created_at: Optional[float] = None) -> SignedMessage:
    return device.sign_message({
        "kind": KIND_RESPONSES, "target": target,
        "body_nonce": body_nonce, "body_ct": body_ct, "wraps": wraps,
        "expires_at": expires_at, "created_at": _now(created_at),
    })
```

Validation branches (append before the final fallthrough in validate_payload; reuse `_is_hexn`, `_valid_wraps`, the body_ct hex check copied from the POST branch):

```python
    if kind in (KIND_RESPONSE, KIND_RESPONSES):
        target = p.get("target")
        if not isinstance(target, str) or not target:
            return False, "bad target"
        if not _is_hexn(p.get("body_nonce"), 24):
            return False, "bad body_nonce"
        ct = p.get("body_ct")
        if (not isinstance(ct, str) or not ct
                or any(c not in "0123456789abcdef" for c in ct)):
            return False, "bad body_ct"
        if not _valid_wraps(p.get("wraps")):
            return False, "bad wraps"
        if kind == KIND_RESPONSES:
            exp = p.get("expires_at")
            if exp is not None and not isinstance(exp, (int, float)):
                return False, "bad expires_at"
        return True, "ok"
```

AADs in `hearth/dmcrypt.py`:

```python
def response_aad(responder_identity: str, target: str,
                 created_at: float) -> bytes:
    return canonical({"type": "response-aad", "protocol": PROTOCOL,
                      "from": responder_identity, "target": target,
                      "created_at": created_at})


def responses_aad(author_identity: str, target: str,
                  created_at: float) -> bytes:
    return canonical({"type": "responses-aad", "protocol": PROTOCOL,
                      "from": author_identity, "target": target,
                      "created_at": created_at})
```

- [ ] **Step 4: Run** `tests\test_messages.py` + `tests\test_store_ingest.py` (ingest must accept the new validated kinds) — all green. If store ingest has a KIND allowlist that rejects unknown kinds, add the two kinds where existing kinds register (find the pattern — likely validate_payload IS the gate; verify and report).

- [ ] **Step 5: Commit** `feat(messages): KIND_RESPONSE/KIND_RESPONSES kinds, builders, fail-closed validation`

---

### Task 3: node — `compose_response` (responder side)

**Files:**
- Modify: `hearth/node.py` (new method near compose_dm; import seal_slots/response_aad), `hearth/settings.py` or wherever `close_behavior` persists (find the settings store used by /api/settings — extend with `public_engagement: bool = False`)
- Test: `tests/test_responses.py` (new file)

**Interfaces:**
- Consumes: Task 1 `seal_slots`; Task 2 kinds/builders/aads; existing `store.enckeys(identity)`, `new_content_key`, `encrypt_body`, `wrap_key`, `canonical`.
- Produces: `node.compose_response(target_msg_id: str, rkind: str, body: str) -> str` — validates rkind in ("comment","reaction","retract"), comment length <= MAX_COMMENT, reaction token in REACTION_TOKENS + "clear", target exists locally, is KIND_POST with `placement == "journal"`; **plaintext response body** (encrypted): `{"rkind", "body", "alias_seed": <32-hex random>, "public": <settings.public_engagement>, "responder": <identity_pub>, "responder_sig": <sig over canonical({target, rkind, body, created_at, responder})>, "mutual_box": <slots|None (None when public)>, "created_at"}`; wrapped to the TARGET AUTHOR's device enc pubs (`store.enckeys(author)`; author == self allowed — see below). Mutual-box payload: `canonical({"identity": identity_pub, "sig": responder_sig})`. `settings.public_engagement` read at compose time.
- Own-post responses: compose_response still emits the KIND_RESPONSE (wrapped to self) — Task 4's sweep folds it; one code path, no special case.

- [ ] **Step 1: Write the failing tests** (`tests/test_responses.py`, new file — build two befriended nodes the way tests/test_three_nodes.py does; read it first for the ceremony helpers):

```python
def test_compose_response_routes_to_author_only(tmp_path):
    a, b = _befriended_pair(tmp_path)        # helper per test_three_nodes idiom
    pid = a.compose_post("hello journal", "kreds")
    _sync(a, b)                              # b holds the post
    rid = b.compose_response(pid, "reaction", "heart")
    msg = b.store.get_message(rid)
    from hearth.messages import KIND_RESPONSE
    assert msg.payload["kind"] == KIND_RESPONSE
    # wrapped to A's devices (+ possibly B's own) - never to a third party
    a_devs = set(a.store.enckeys(a.identity_pub))
    assert set(msg.payload["wraps"]) <= a_devs | set(
        b.store.enckeys(b.identity_pub))
    assert a_devs & set(msg.payload["wraps"])


def test_compose_response_validation(tmp_path):
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    import pytest
    with pytest.raises(ValueError):
        b.compose_response(pid, "reaction", "thumbsdown")   # not a token
    with pytest.raises(ValueError):
        b.compose_response(pid, "comment", "x" * 501)       # over cap
    with pytest.raises(ValueError):
        b.compose_response("nope" * 8, "comment", "hi")     # unknown target
    with pytest.raises(ValueError):
        b.compose_response(pid, "sneer", "hi")              # bad rkind


def test_response_body_privacy_fields(tmp_path):
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    rid = b.compose_response(pid, "comment", "hej")
    # decrypt as the AUTHOR would: content key from wraps
    body = _decrypt_response_as(a, b.store.get_message(rid))
    assert body["rkind"] == "comment" and body["body"] == "hej"
    assert body["public"] is False                     # default OFF
    assert len(body["alias_seed"]) == 32
    assert isinstance(body["mutual_box"], list) and len(body["mutual_box"]) >= 8
    # sig verifies over the canonical form
    from hearth.identity import verify_over_canonical   # find the real
    # verification helper in identity.py - adapt this call to it
```

(NOTE to implementer: `_befriended_pair`, `_sync`, `_decrypt_response_as` are file-local helpers YOU write using the idioms of tests/test_three_nodes.py (ceremony + sync_with) and node's own `_content_key` for decryption. The signature-verification call must use identity.py's real API — read it; do not invent one. Assertions stay as written.)

- [ ] **Step 2: Run to verify failure** — AttributeError `compose_response`.

- [ ] **Step 3: Implement** in `hearth/node.py`:

```python
    def compose_response(self, target_msg_id: str, rkind: str,
                         body: str) -> str:
        """Respond to a journal post (spec 2026-07-18): comment, reaction,
        or retract - encrypted to the AUTHOR's devices only (the author
        relays to the audience via the responses record). Engagement is
        private by default: real identity rides only in the anonymous
        mutual box unless the public_engagement setting is on."""
        if rkind not in ("comment", "reaction", "retract"):
            raise ValueError("bad response kind")
        if rkind == "comment" and not (0 < len(body) <= MAX_COMMENT):
            raise ValueError("comment must be 1-500 characters")
        if rkind == "reaction" and body not in REACTION_TOKENS + ("clear",):
            raise ValueError("unknown reaction")
        msg = self.store.get_message(target_msg_id)
        if msg is None or msg.payload.get("kind") != KIND_POST \
                or msg.payload.get("placement", "journal") != "journal":
            raise ValueError("not a journal post")
        author = msg.cert.identity_pub
        author_devs = self.store.enckeys(author)
        if author == self.identity_pub:
            author_devs[self.device.device_pub] = self.device.enc_pub
        if not author_devs:
            raise ValueError("no reachable devices for the author")
        created_at = time.time()
        public = bool(self.settings.get("public_engagement", False))
        sig_payload = canonical({"target": target_msg_id, "rkind": rkind,
                                 "body": body, "created_at": created_at,
                                 "responder": self.identity_pub})
        responder_sig = self.device.sign_raw(sig_payload)   # find identity.py's
        # raw-sign helper; if only sign_message exists, sign a dict via
        # canonical the same way sign_message does internally - REPORT the
        # exact mechanism you used
        box = None
        if not public:
            friend_pubs = []
            for ident in self.store.known_identities():
                if ident == self.identity_pub:
                    continue
                friend_pubs.extend(self.store.enckeys(ident).values())
            box = seal_slots(canonical({"identity": self.identity_pub,
                                        "sig": responder_sig}), friend_pubs)
        key = new_content_key()
        aad = response_aad(self.identity_pub, target_msg_id, created_at)
        nonce, ct = encrypt_body(key, {
            "rkind": rkind, "body": body,
            "alias_seed": os.urandom(16).hex(), "public": public,
            "responder": self.identity_pub, "responder_sig": responder_sig,
            "mutual_box": box, "created_at": created_at}, aad)
        wraps = wrap_key(key, author_devs, aad)
        mine = self.store.enckeys(self.identity_pub)
        mine[self.device.device_pub] = self.device.enc_pub
        wraps.update(wrap_key(key, mine, aad))    # self-readable (retract UI)
        mid = self._publish(make_response(self.device, target_msg_id,
                                          nonce, ct, wraps,
                                          created_at=created_at))
        self._cache_message_key(mid, key)
        return mid
```

`self.settings` — find how /api/settings persists `close_behavior` (api.py:185-190 → likely a node-side settings dict/file); extend the same store with `public_engagement` (bool, default False) and expose read here. REPORT the mechanism found.

- [ ] **Step 4: Run** `tests\test_responses.py` — green; plus `tests\test_three_nodes.py` (no regression).

- [ ] **Step 5: Commit** `feat(node): compose_response - author-addressed, private-by-default with anonymous mutual box`

---

### Task 4: node — author sweep + responses record

**Files:**
- Modify: `hearth/node.py` (sweep + rebuild + moderation), `hearth/sync.py:273-274` region (add the sweep call beside the prune calls)
- Test: `tests/test_responses.py` (append)

**Interfaces:**
- Consumes: Task 3's response bodies; `make_responses`, `responses_aad`; `_scope_device_pubs(scope)` (post's ring at republish time).
- Produces: `node.process_responses() -> int` (count of rebuilt records; idempotent; called after each sync round AND at the end of compose_response when the target author is self); `node.remove_response(post_id, responder_identity, created_at) -> str` (moderation: drop that entry, republish); records: latest-wins per (author, target) via the store's existing latest-wins machinery for keyed records (albums precedent — find `set_album`/album ingest keying and mirror it for KIND_RESPONSES keyed on `target`; REPORT the mechanism).
- Record body (encrypted to post audience): `{"entries": [{"rkind", "body", "created_at", "alias_seed", "public", "identity" (only when public), "responder_sig", "mutual_box"}]}` — reactions folded latest-wins per responder BEFORE publishing (the record never carries a responder's stale reactions), retracted entries dropped, comments append-ordered by created_at.

- [ ] **Step 1: Write the failing tests** (append to tests/test_responses.py):

```python
def test_author_sweep_republishes_record(tmp_path):
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    b.compose_response(pid, "comment", "fin kommentar")
    b.compose_response(pid, "reaction", "fire")
    _sync(a, b)                    # response reaches A; A's sweep runs in-sync-hook
    a.process_responses()          # idempotent double-call must be safe
    rec = _responses_record(a, pid)          # helper: find KIND_RESPONSES by target
    body = _decrypt_record_as(a, rec)
    kinds = sorted(e["rkind"] for e in body["entries"])
    assert kinds == ["comment", "reaction"]
    assert all("identity" not in e for e in body["entries"])   # private default


def test_reaction_latest_wins_and_clear(tmp_path):
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    b.compose_response(pid, "reaction", "heart")
    _sync(a, b); a.process_responses()
    b.compose_response(pid, "reaction", "laugh")
    _sync(a, b); a.process_responses()
    body = _decrypt_record_as(a, _responses_record(a, pid))
    reactions = [e for e in body["entries"] if e["rkind"] == "reaction"]
    assert len(reactions) == 1 and reactions[0]["body"] == "laugh"
    b.compose_response(pid, "reaction", "clear")
    _sync(a, b); a.process_responses()
    body = _decrypt_record_as(a, _responses_record(a, pid))
    assert not [e for e in body["entries"] if e["rkind"] == "reaction"]


def test_retract_and_moderation(tmp_path):
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    b.compose_response(pid, "comment", "first")
    b.compose_response(pid, "comment", "second")
    _sync(a, b); a.process_responses()
    body = _decrypt_record_as(a, _responses_record(a, pid))
    first = [e for e in body["entries"] if e["body"] == "first"][0]
    # responder retracts their own first comment
    b.compose_response(pid, "retract", str(first["created_at"]))
    _sync(a, b); a.process_responses()
    body = _decrypt_record_as(a, _responses_record(a, pid))
    assert [e["body"] for e in body["entries"]] == ["second"]
    # author moderates the second away
    a.remove_response(pid, b.identity_pub, [e for e in body["entries"]][0]["created_at"])
    body = _decrypt_record_as(a, _responses_record(a, pid))
    assert body["entries"] == []


def test_post_delete_tombstones_record(tmp_path):
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    b.compose_response(pid, "comment", "hej")
    _sync(a, b); a.process_responses()
    rec = _responses_record(a, pid)
    a.delete_post(pid)
    assert _responses_record(a, pid) is None or \
        a.store.get_message(rec.msg_id) is None
```

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement.** `process_responses()`: scan stored KIND_RESPONSE messages whose `target` is an own KIND_POST (journal placement); decrypt each via `_content_key`; group by target; fold (reactions latest-wins per `responder`, retracts remove the matching (responder, created_at-as-float-string ref) entry, moderation tombstone list consulted — a node-local `removed_responses` store table `{target, responder, created_at}` written by `remove_response`); skip undecryptable/junk rows fail-closed (a hostile response must never break the sweep — wrap the per-message fold in try/except with a logged skip); compare the folded entry list against the currently-published record's decrypted entries — republish only on change (idempotence). Publish via `make_responses` with body encrypted to `_scope_device_pubs(post.scope)` + self, `expires_at` copied from the post, and the record REPLACES the prior one via the same latest-wins keying albums use (mirror `set_album`'s mechanism for keying on `target`; REPORT it). `remove_response` writes the tombstone row + calls `process_responses()`. Hook: in `hearth/sync.py` beside `prune_superseded_wrap_grants()` add `self.node.process_responses()` (guarded try/except like its neighbors if they are guarded — match the local idiom); also call at the end of `compose_response` when `author == self.identity_pub`. Post-delete cascade: in `delete_post`, also publish a delete tag for the post's responses record if one exists.

- [ ] **Step 4: Run** `tests\test_responses.py -q` green; full suite once — exact tail reported.

- [ ] **Step 5: Commit** `feat(node): author responses sweep - latest-wins record, retraction, moderation, delete cascade`

---

### Task 5: view + API — responses in feed rows, respond endpoints, settings toggle

**Files:**
- Modify: `hearth/node.py` (feed row extension + `open_response_identity` helper), `hearth/api.py` (endpoints + settings field)
- Test: `tests/test_responses.py` + `tests/test_api.py` (append)

**Interfaces:**
- Consumes: Tasks 1-4.
- Produces: feed rows (`feed()` / `posts_by`) gain `"responses": {"reactions": {token: count}, "my_reaction": token|None, "comments": [{"name", "avatar" (hash|None), "alias" (bool), "mine" (bool), "body", "created_at"}], "can_moderate": bool}` — assembled per-viewer: decrypt the post's KIND_RESPONSES record (viewer is in the audience), for each entry try `try_open_slots(entry["mutual_box"], device.enc_priv)` → identity → verify `responder_sig` over the canonical form → resolve name/avatar from local profiles; `public` entries use the carried identity the same way; unopened → alias name derived client-side from `alias_seed` (row carries the seed). Author's own rows: identities resolved from the raw KIND_RESPONSE originals (their node holds them).
- API: `POST /api/react {msg_id, token}` (token or "clear"), `POST /api/comment {msg_id, text}`, `POST /api/retract {msg_id, created_at}`, `POST /api/response-remove {msg_id, responder, created_at}` (moderation; 400 if not own post) — all `_400`-wrapped over compose_response/remove_response. `/api/settings` GET/POST gains `public_engagement` (bool) beside close_behavior, same persistence.

- [ ] **Step 1: failing tests** — append (API tests in tests/test_api.py using its TestClient fixture idiom):

```python
def test_feed_rows_carry_responses(tmp_path):
    a, b = _befriended_pair(tmp_path)
    pid = a.compose_post("post", "kreds")
    _sync(a, b)
    b.compose_response(pid, "reaction", "heart")
    b.compose_response(pid, "comment", "hej fra B")
    _sync(a, b); a.process_responses(); _sync(a, b)
    row_a = [p for p in a.feed() if p["msg_id"] == pid][0]
    assert row_a["responses"]["reactions"] == {"heart": 1}
    assert row_a["responses"]["comments"][0]["name"]      # author sees real B
    assert row_a["responses"]["can_moderate"] is True
    row_b = [p for p in b.feed() if p["msg_id"] == pid][0]
    assert row_b["responses"]["my_reaction"] == "heart"
    assert row_b["responses"]["comments"][0]["mine"] is True


def test_stranger_sees_alias_mutual_sees_name(tmp_path):
    # A authors; B and C are A's friends but NOT each other's
    a, b, c = _triangle_no_bc(tmp_path)      # helper per test_three_nodes idiom
    pid = a.compose_post("post", "kreds")
    _sync(a, b); _sync(a, c)
    b.compose_response(pid, "comment", "privat hilsen")
    _sync(a, b); a.process_responses(); _sync(a, c)
    row_c = [p for p in c.feed() if p["msg_id"] == pid][0]
    entry = row_c["responses"]["comments"][0]
    assert entry["alias"] is True and "name" not in entry or entry.get("name") is None
    # now with B<->C friends, C resolves the real name
    _befriend(b, c); _sync(b, c)
    b2 = b.compose_response(pid, "comment", "nu venner")
    _sync(a, b); a.process_responses(); _sync(a, c)
    row_c = [p for p in c.feed() if p["msg_id"] == pid][0]
    named = [e for e in row_c["responses"]["comments"] if e["body"] == "nu venner"]
    assert named and named[0]["alias"] is False and named[0]["name"]
```

(NOTE: the second test's B-comments-again step is needed because B's first mutual_box was sealed to B's friends AT COMMENT TIME — C wasn't one. That earlier comment legitimately STAYS aliased for C: pin that too with `assert [e for e in row_c["responses"]["comments"] if e["body"] == "privat hilsen"][0]["alias"] is True`.)

API test:

```python
def test_respond_endpoints_and_settings_toggle(tmp_path):
    c, node = client(tmp_path)
    pid = node.compose_post("own post", "kreds")
    r = c.post("/api/react", json={"msg_id": pid, "token": "fire"})
    assert r.status_code == 200
    r = c.post("/api/comment", json={"msg_id": pid, "text": "selvsvar"})
    assert r.status_code == 200
    node.process_responses()
    row = [p for p in node.feed() if p["msg_id"] == pid][0]
    assert row["responses"]["reactions"] == {"fire": 1}
    r = c.post("/api/comment", json={"msg_id": pid, "text": "x" * 501})
    assert r.status_code == 400
    s = c.get("/api/settings").json()
    assert s["public_engagement"] is False
    r = c.post("/api/settings", json={"public_engagement": True})
    assert r.status_code == 200
    assert c.get("/api/settings").json()["public_engagement"] is True
```

- [ ] **Step 2: verify failure.** **Step 3: implement** per the Interfaces block (row assembly in a `_post_responses_view(msg, viewer_ctx)` helper near `_decrypt_post_row`; alias display text is NOT built server-side — the row carries `alias_seed` and the client renders "Quiet Fox"-style names from it; sig verification uses the same identity.py mechanism Task 3 reported). **Step 4: run** the two files + full suite tail. **Step 5: commit** `feat(api): respond endpoints, responses in feed rows, public_engagement toggle`

---

### Task 6: web UI — reaction bar + comment section + settings toggle

**Files:**
- Modify: `hearth/web/app.js` (buildEntry + alias-name generator + settings section), `hearth/web/style.css`
- Test: `tests/test_web_assets.py` (append)

**Interfaces:**
- Consumes: feed rows' `responses` shape from Task 5; endpoints `/api/react`, `/api/comment`, `/api/retract`, `/api/response-remove`; `/api/settings.public_engagement`.
- Produces: `REACTION_GLYPHS = {heart: "❤️", laugh: "😂", wow: "😮", sad: "😢", up: "👍", fire: "🔥"}`; `aliasName(seed)` (deterministic adjective+animal from seed, small word lists, e.g. "Quiet Fox"); `aliasColor(seed)` reusing `identityColor`'s derivation shape on the seed.

- [ ] **Step 1: failing asset test:**

```python
def test_reactions_comments_ui_wired():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    be = _js_fn_body(js, "buildEntry")
    for needle in ("reaction-bar", "/api/react", "comments-toggle",
                   "/api/comment", "aliasName"):
        assert needle in be or needle in js, needle
    assert "REACTION_GLYPHS" in js and "aliasName" in js
    for sel in (".reaction-bar", ".rx", ".rx.on", ".comments",
                ".comment-alias"):
        assert sel in css, sel
    # settings toggle present and wired to the API field
    assert "public_engagement" in js
    # moderation affordance only on own posts
    assert "response-remove" in js
```

- [ ] **Step 2-5:** implement per spec's UI section: reaction bar under each entry (six `.rx` buttons: glyph + count when > 0, `.on` for own; click → POST /api/react with token or "clear" when clicking the active one; optimistic update then refresh); "Comments (n)" toggle expanding `.comments` (flat list: avatar-or-alias-tint circle, name-or-aliasName, body, time; own comments get a retract "×"; on own POSTS every comment gets a moderation remove "×" → /api/response-remove); composer (input + send → /api/comment, maxlength 500); the profile journal rail shows the collapsed count only (no bar/section — read-only, per spec). Settings page: a toggle row "Show my name on comments to people who don't know me" wired to public_engagement (follow the close_behavior toggle's existing markup/idiom). Alias entries: `aliasName(seed)` + tinted default avatar via `aliasColor(seed)`. Verify with `node --check` + full asset suite; commit `feat(web): reaction bar, comment section, engagement-privacy toggle`.

---

### Task 7: stories — replies/reactions as DMs with story context

**Files:**
- Modify: `hearth/messages.py` (make_dm gains `story_ref=None`; DM validation accepts optional `{story_id, media_hash}` with type guards), `hearth/node.py` (compose_dm passes it through), `hearth/api.py` (/api/dm accepts `story_ref` JSON field), `hearth/web/app.js` (story viewer: reaction row + reply input; openThread: story-context chip), `hearth/web/style.css`
- Test: `tests/test_node_story.py`, `tests/test_api.py`, `tests/test_web_assets.py` (append each)

**Interfaces:**
- Consumes: existing DM machinery end-to-end; REACTION_GLYPHS from Task 6.
- Produces: DM payloads may carry `"story_ref": {"story_id": str, "media_hash": str}` (additive; old cores ignore unknown fields inside... NO — DM payload fields are validated; verify the DM branch tolerates the extra key on OLD validation (it does — unknown keys ride through, the wrap_grant/codec precedent) and add explicit type-guarded validation on NEW cores: absent/None fine; if present, both fields non-empty str, hash hex64). DM view rows carry `story_ref` through `dm_thread`. A reaction-reply is a DM whose text is the reaction GLYPH (no new field — the glyph IS the message; the story_ref is what renders the context).

- [ ] **Step 1: failing tests:**

```python
def test_dm_story_ref_round_trip(tmp_path):
    a, b = _befriended_pair(tmp_path)
    sid = a.compose_story(_png_bytes(), "min story")
    _sync(a, b)
    item = [i for g in b.node.stories_view() for i in g["items"]
            if i["msg_id"] == sid][0] if hasattr(b, "node") else \
        [i for g in b.stories_view() for i in g["items"]][0]
    did = b.compose_dm(a.identity_pub, "\U0001F525", story_ref={
        "story_id": sid, "media_hash": item["media"]})
    _sync(a, b)
    thread = a.dm_thread(b.identity_pub)
    assert thread[-1]["story_ref"]["story_id"] == sid


def test_dm_story_ref_validation():
    from hearth.messages import validate_payload
    base = _valid_dm_payload()               # file's helper or build inline
    base["story_ref"] = {"story_id": "s" * 16, "media_hash": "a" * 64}
    assert validate_payload(base)[0]
    for bad in (7, "x", {"story_id": ""}, {"story_id": "s", "media_hash": 9},
                {"story_id": "s"}):
        base["story_ref"] = bad
        assert not validate_payload(base)[0], bad
    base["story_ref"] = None
    assert validate_payload(base)[0]
```

Asset test: story viewer contains a `.sv-react` row using REACTION_GLYPHS + a `.sv-reply` input POSTing /api/dm with story_ref; `openThread` renders `.story-chip` when `m.story_ref` (thumbnail `<img src="/api/blob/" + media_hash>` with `onerror` → "Story expired" text chip — the blob is gone after TTL; that IS the expiry mechanism, no date math).

- [ ] **Step 2-5:** implement (compose_dm signature `story_ref=None` additive; api /api/dm Form field `story_ref: str = Form("")` JSON-parsed via the `_parse_video_edit`-style helper — generalize or mirror it; story viewer UI: reaction row sends the glyph as DM text + story_ref and flashes a "sent" toast then advances; reply input likewise; thread chip above bubble text). Verify: named test files + `node --check` + asset suite + full suite tail. Commit `feat(stories): replies and reactions land as DMs with story context`.

---

### Task 8: integration + compat + live smoke

**Files:**
- Create: `tests/test_ui_smoke_responses.py`
- Modify: `tests/test_responses.py` (old-core refusal test)

**Interfaces:** consumes everything.

- [ ] **Step 1: old-core refusal test** (append to tests/test_responses.py): build a KIND_RESPONSE SignedMessage, monkeypatch-remove the new kinds from a receiving store's validation (simulate old core by calling `validate_payload` from a copy with the branch absent — simplest honest simulation: assert `ingest_message` of a response whose kind string is mangled to `"response-future"` is refused with "unknown/invalid", AND assert the store's re-offer behavior matches wrap_grant's precedent by checking `messages_not_in` still offers refused-kind messages on the next round — read how wrap_grant's mixed-version test does this, tests/ has one; mirror it exactly and REPORT the file).

- [ ] **Step 2: live smoke** (UI_E2E, LiveNode pair): A posts; B reacts 🔥 + comments via the real UI (`.rx` click, comment composer); assert A's journal shows count + comment with B's real name (mutuals); B's own entry shows "(you)"/mine styling; story leg: B opens A's story, taps a reaction → A's thread shows the glyph DM with `.story-chip`. Console pageerrors empty. Selector-verify against live DOM; assertions fixed.

- [ ] **Step 3: run** both new files + the full UI_E2E set (responses + profile_load + albums + video_editor) — all green. **Step 4: commit** `test(responses): three-node alias/mutual integration, old-core refusal, live smoke`

---

### Task 9: verification + docs + ROADMAP

- [ ] Full suite + all smokes; exact tails.
- [ ] engineering-notes section: the ingest-gate constraint → author-relay choice; the two-tier disclosure design with the labeled-wraps near-miss (why slots are anonymous); bucket padding; verification split (mutuals cryptographic, strangers author-attested); honest limits (author-online fan-out, compliant-client retraction/moderation, slot-bucket disclosure); stories-as-DMs simplicity argument.
- [ ] ROADMAP: mark the reactions/comments entry built-on-branch; add the cross-reference on the communities/personas entry ("engagement aliasing (responses spec 2026-07-18) is the seed mechanism — personas must extend it, not fork it"); RELEASE CHECKLIST note: `min_core_for_web` bump required when this ships.
- [ ] Commit `docs: responses slice engineering notes + ROADMAP status/cross-refs`

---

## Self-review notes (done at plan time)

- **Spec coverage:** sealed slots (T1), kinds/validation (T2), responder path + settings (T3), sweep/record/moderation/retract/cascade (T4), viewer assembly + endpoints + toggle (T5), journal UI (T6), stories (T7), integration/compat/smoke (T8), docs (T9). Alias rendering client-side (T5 note + T6); author-always-sees via raw originals (T5); slot-count buckets (T1).
- **Known intentional deviations from the spec's record sketch:** entry `identity` field present only when public (spec matches); "name (author-attested)" — carried for public entries only (mutual viewers resolve names locally; spec updated language already).
- **Type consistency:** `compose_response(target, rkind, body)` used identically in T3-T8; row `responses` shape defined once in T5 and consumed verbatim in T6/T8; `story_ref {story_id, media_hash}` consistent T7/T8.
- **Open mechanisms the implementer must find and REPORT (not invent):** identity.py's raw-sign/verify helpers (T3/T5), the album latest-wins keying (T4), the settings persistence (T3/T5), the wrap_grant mixed-version test to mirror (T8).
