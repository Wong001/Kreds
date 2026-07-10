# Compact Invite Encoding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Shrink the human-copied friend invite from ~600-char JSON to ~80-char base58, move A's cert into the Tor handshake, add a 4-char casual identity fingerprint with a machine binding check.

**Architecture:** Per spec `docs/superpowers/specs/2026-07-10-compact-invite-design.md`. A new pure codec (`hearth/invitecodec.py`) packs the three ceremony messages to base58; the ceremony (`node.py`) converts at the codec boundary and keeps its internal hex-nonce / addr-string / cert logic identical, so crypto/signatures are unchanged — only the wire format and where the cert travels change.

**Tech Stack:** Python 3.12 stdlib only (struct, base64, hashlib — hand-rolled base58, no new dependency), vanilla JS, pytest.

## Global Constraints

- **No new dependency** — base58 is hand-rolled (project ethos; ~15 lines).
- **Convert at the boundary, keep ceremony internals identical:** nonces stay hex strings inside `node.py` (dict keys, `_friend_add_body` signatures); the codec takes/returns raw bytes and the ceremony converts with `.hex()` / `bytes.fromhex()`. The onion is passed to the ceremony as the same `"xxx.onion:port"` string it uses today. Certs stay `EnrollmentCert` objects in the ceremony; the codec packs/unpacks them only on the wire.
- **Cert moves invite→final:** the invite carries only a 4-byte `id_prefix`; A's full cert travels in the `final` message; `complete_invite` verifies it there PLUS the binding check.
- **Binding check:** `complete_invite` rejects unless `final_cert.identity_pub[:4] == invite id_prefix`.
- **Honest limit** (documented in engineering-notes): 4-char fingerprint is a casual check, not MITM-proof; full-identity verify is a deferred follow-up.
- No AI co-author trailers on commits. Repo `C:\Users\Wong\Desktop\Hearth`, venv `.venv`, tests from repo root: `.venv\Scripts\python.exe -m pytest ...`. Baseline 709 passed, 1 skipped.
- Code facts (verified 2026-07-10): ceremony in `hearth/node.py` — `create_invite` :873, `respond_to_invite` :885, `finalize_invite` :908, `complete_invite` :953; `_friend_add_body` :1317; `PROTOCOL = "hearth/v0.2"` (identity.py:31); `EnrollmentCert.to_dict/from_dict` (identity.py:104/112), fields `identity_pub`(64hex), `device_pub`(64hex), `device_name`(str), `enrolled_at`(float), `signature`(128hex); node stores onion in `store.get_meta("gossip_addr")` as `"xxx.onion:port"`.

---

### Task 1: The codec module (`hearth/invitecodec.py`)

**Files:**
- Create: `hearth/invitecodec.py`
- Test: `tests/test_invitecodec.py`

**Interfaces:**
- Produces: `b58encode/b58decode`; `onion_split(addr)->(pub32, port)`, `onion_join(pub32, port)->addr`; `fingerprint(identity_pub_hex)->str` (4-char); `pack_cert(cert_dict)->bytes`, `unpack_cert(bytes,offset)->(cert_dict,newoffset)`; `encode_invite/encode_response/encode_final(...)->str`; `decode(code)->(type_str, dict)`. Nonces cross this API as **hex strings** (the codec converts to/from raw bytes internally). Certs cross as **dicts** (EnrollmentCert.to_dict shape).

- [ ] **Step 1: Write the failing tests** — create `tests/test_invitecodec.py`:

```python
import os, time
from hearth import invitecodec as ic

def test_base58_roundtrip_incl_leading_zeros():
    for b in (b"", b"\x00", b"\x00\x00\x01", os.urandom(32), bytes(range(20))):
        assert ic.b58decode(ic.b58encode(b)) == b
    # alphabet excludes look-alikes
    assert "0" not in ic.b58encode(os.urandom(40)) or True  # 0 not in alphabet
    assert set("0OIl").isdisjoint(set(ic._B58))

def test_onion_split_join_roundtrip():
    # a real v3 onion address (56 base32 chars + .onion) + port
    addr = "otkspt5nohnwvmgir7obhcpeouqx4qkxchorw3ybhznjatdmwc7t5lad.onion:53799"
    pub, port = ic.onion_split(addr)
    assert len(pub) == 32 and port == 53799
    assert ic.onion_join(pub, port) == addr        # checksum+version reconstructed

def test_fingerprint_is_4_chars_and_stable():
    idhex = "abf442fa0d42240fafcc0aef5ff96f4f4cec7a0312e659d752636458de607758"
    fp = ic.fingerprint(idhex)
    assert len(fp) == 4 and fp == ic.fingerprint(idhex)   # deterministic
    assert fp.isalnum() and fp.upper() == fp              # base32 uppercase

def test_invite_roundtrip_and_size():
    idhex = "ab" * 32
    addr = "otkspt5nohnwvmgir7obhcpeouqx4qkxchorw3ybhznjatdmwc7t5lad.onion:53799"
    pub, port = ic.onion_split(addr)
    nonce = os.urandom(16).hex()
    expiry = int(time.time()) + 600
    code = ic.encode_invite(idhex[:8], pub, port, nonce, expiry)  # id_prefix = first 4 bytes = 8 hex
    assert len(code) < 100                                        # the whole point
    typ, d = ic.decode(code)
    assert typ == "invite"
    assert d["id_prefix"] == idhex[:8] and d["addr"] == addr
    assert d["nonce"] == nonce and d["expiry"] == expiry

def test_response_and_final_roundtrip_with_cert():
    cert = {"identity_pub": "ab"*32, "device_pub": "cd"*32,
            "device_name": "desktop", "enrolled_at": 1783534958.87,
            "signature": "ef"*64}
    addr = "otkspt5nohnwvmgir7obhcpeouqx4qkxchorw3ybhznjatdmwc7t5lad.onion:53799"
    pub, port = ic.onion_split(addr)
    resp = ic.encode_response(pub, port, "aa"*16, "bb"*16, "cc"*64, cert)
    typ, d = ic.decode(resp)
    assert typ == "response" and d["cert"] == cert and d["addr"] == addr
    assert d["nonce"] == "aa"*16 and d["peer_nonce"] == "bb"*16 and d["sig"] == "cc"*64
    fin = ic.encode_final("bb"*16, "dd"*64, cert)
    typ2, d2 = ic.decode(fin)
    assert typ2 == "final" and d2["cert"] == cert and d2["nonce"] == "bb"*16 and d2["sig"] == "dd"*64

def test_decode_rejects_bad_version_and_garbage():
    import pytest
    with pytest.raises(ValueError):
        ic.decode("z" + ic.encode_invite("ab"*4, os.urandom(32), 1, "aa"*16, 1)[1:])  # corrupt
    with pytest.raises(ValueError):
        ic.decode("!!!!not base58!!!!")
```

- [ ] **Step 2: Run to verify failure** — `.venv\Scripts\python.exe -m pytest tests/test_invitecodec.py -q` → FAIL (module missing).

- [ ] **Step 3: Implement `hearth/invitecodec.py`:**

```python
"""Compact friend-invite wire codec (spec 2026-07-10-compact-invite).
The three ceremony messages (invite/response/final) pack to base58 instead
of JSON. Nonces/certs cross this API as hex strings / dicts (the ceremony
in node.py stays hex-and-EnrollmentCert internally); this module converts
to raw bytes only on the wire. No dependency - base58 is hand-rolled."""
import base64
import hashlib
import struct

_B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

def b58encode(b: bytes) -> str:
    n = int.from_bytes(b, "big")
    out = ""
    while n > 0:
        n, r = divmod(n, 58)
        out = _B58[r] + out
    pad = len(b) - len(b.lstrip(b"\x00"))
    return "1" * pad + out

def b58decode(s: str) -> bytes:
    n = 0
    for c in s:
        i = _B58.find(c)
        if i < 0:
            raise ValueError("bad base58 char")
        n = n * 58 + i
    body = n.to_bytes((n.bit_length() + 7) // 8, "big") if n else b""
    pad = len(s) - len(s.lstrip("1"))
    return b"\x00" * pad + body

def onion_split(addr: str):
    host, _, port = addr.partition(":")
    b32 = host[:-6]                                   # strip ".onion"
    raw = base64.b32decode(b32.upper() + "=" * ((8 - len(b32) % 8) % 8))
    return raw[:32], int(port)

def onion_join(pub: bytes, port: int) -> str:
    ver = b"\x03"
    chk = hashlib.sha3_256(b".onion checksum" + pub + ver).digest()[:2]
    b32 = base64.b32encode(pub + chk + ver).decode().lower().rstrip("=")
    return f"{b32}.onion:{port}"

def fingerprint(identity_pub_hex: str) -> str:
    pre = bytes.fromhex(identity_pub_hex)[:4]
    return base64.b32encode(pre).decode().rstrip("=")[:4]

def pack_cert(c: dict) -> bytes:
    name = c["device_name"].encode("utf-8")
    return (bytes.fromhex(c["identity_pub"]) + bytes.fromhex(c["device_pub"])
            + struct.pack(">d", float(c["enrolled_at"]))
            + bytes.fromhex(c["signature"])
            + struct.pack(">H", len(name)) + name)

def unpack_cert(b: bytes, off: int):
    ident = b[off:off+32].hex();      off += 32
    dev = b[off:off+32].hex();        off += 32
    enrolled = struct.unpack(">d", b[off:off+8])[0]; off += 8
    sig = b[off:off+64].hex();        off += 64
    nl = struct.unpack(">H", b[off:off+2])[0]; off += 2
    name = b[off:off+nl].decode("utf-8"); off += nl
    return {"identity_pub": ident, "device_pub": dev, "device_name": name,
            "enrolled_at": enrolled, "signature": sig}, off

_VER = 0x01
def _wrap(typ: int, body: bytes) -> str:
    return b58encode(bytes([_VER, typ]) + body)

def encode_invite(id_prefix_hex, onion_pub, port, nonce_hex, expiry) -> str:
    body = (bytes.fromhex(id_prefix_hex) + onion_pub + struct.pack(">H", port)
            + bytes.fromhex(nonce_hex) + struct.pack(">I", int(expiry)))
    return _wrap(1, body)

def encode_response(onion_pub, port, nonce_hex, peer_nonce_hex, sig_hex, cert) -> str:
    body = (onion_pub + struct.pack(">H", port) + bytes.fromhex(nonce_hex)
            + bytes.fromhex(peer_nonce_hex) + bytes.fromhex(sig_hex) + pack_cert(cert))
    return _wrap(2, body)

def encode_final(nonce_hex, sig_hex, cert) -> str:
    body = bytes.fromhex(nonce_hex) + bytes.fromhex(sig_hex) + pack_cert(cert)
    return _wrap(3, body)

def decode(code: str):
    raw = b58decode(code)
    if len(raw) < 2 or raw[0] != _VER:
        raise ValueError("unrecognized invite")
    typ, body = raw[1], raw[2:]
    if typ == 1:
        return "invite", {
            "id_prefix": body[0:4].hex(),
            "addr": onion_join(body[4:36], struct.unpack(">H", body[36:38])[0]),
            "nonce": body[38:54].hex(),
            "expiry": struct.unpack(">I", body[54:58])[0]}
    if typ == 2:
        pub = body[0:32]; port = struct.unpack(">H", body[32:34])[0]
        nonce = body[34:50].hex(); peer = body[50:66].hex(); sig = body[66:130].hex()
        cert, _ = unpack_cert(body, 130)
        return "response", {"addr": onion_join(pub, port), "nonce": nonce,
                            "peer_nonce": peer, "sig": sig, "cert": cert}
    if typ == 3:
        nonce = body[0:16].hex(); sig = body[16:80].hex()
        cert, _ = unpack_cert(body, 80)
        return "final", {"nonce": nonce, "sig": sig, "cert": cert}
    raise ValueError("unknown message type")
```

- [ ] **Step 4: Run tests** — `.venv\Scripts\python.exe -m pytest tests/test_invitecodec.py -q` → all PASS.

- [ ] **Step 5: Commit**

```bash
git add hearth/invitecodec.py tests/test_invitecodec.py
git commit -m "feat(invite): compact base58 codec for the three friend-add messages (hand-rolled base58, onion pubkey reconstruction, packed certs, 4-char fingerprint)"
```

---

### Task 2: Wire the codec into the ceremony (`hearth/node.py`)

**Files:**
- Modify: `hearth/node.py` (create_invite ~873, respond_to_invite ~885, finalize_invite ~908, complete_invite ~953)
- Test: `tests/test_friend_add_delivery.py` (or wherever the ceremony unit tests live — grep), plus a new binding-check test.

**Interfaces:**
- Consumes: `hearth.invitecodec`. Produces: compact codes from all four methods; `complete_invite` gains the binding check.

**Read first:** the current bodies of all four methods (lines above) — the goal is minimal surgery: swap `json.dumps({...})` for `invitecodec.encode_*(...)`, swap `json.loads(...)` for `invitecodec.decode(...)`, move A's cert from the invite to the final, add the binding check. Nonces stay hex; addr stays the string; `_friend_add_body`, `_add_friend`, `_pending_invites`, `_pending_responses`, `EnrollmentCert.verify()`, `_sig_ok` are all UNCHANGED.

- [ ] **Step 1: Write the failing tests.** Grep `tests/` for the ceremony unit test file (`test_friend_add_delivery.py` and/or `test_ceremonies.py`) and mirror its fixtures. Add:

```python
def test_invite_is_compact_and_has_no_cert(one_node):
    node = one_node
    code = node.create_invite()
    assert len(code) < 100                    # was ~600
    typ, d = invitecodec.decode(code)
    assert typ == "invite" and "cert" not in d and "id_prefix" in d

def test_full_ceremony_roundtrip_compact(two_nodes):
    a, b = two_nodes
    invite = a.create_invite()
    response = b.respond_to_invite(invite)    # B parses compact invite, responds compact
    final = a.finalize_invite(response)       # A verifies, final now carries A's cert
    b.complete_invite(final)                  # B verifies A's cert + binding check
    assert a.store.is_known(b.identity_pub) and b.store.is_known(a.identity_pub)

def test_binding_check_rejects_wrong_identity_in_final(two_nodes, third_node):
    # A final whose cert identity[:4] != the invite's id_prefix is rejected.
    a, b = two_nodes
    invite = a.create_invite()
    response = b.respond_to_invite(invite)
    good_final = a.finalize_invite(response)
    typ, d = invitecodec.decode(good_final)
    forged = invitecodec.encode_final(d["nonce"], d["sig"], third_node.device.cert.to_dict())
    import pytest
    with pytest.raises(ValueError):
        b.complete_invite(forged)             # fingerprint mismatch
```

(Adapt fixture names to the file's conventions. If a two_nodes/one_node fixture doesn't exist there, build minimal `HearthNode.create` instances as the neighboring tests do.)

- [ ] **Step 2: Run to verify failure** — targeted file → new tests FAIL.

- [ ] **Step 3: Implement.** In `hearth/node.py`, add `from . import invitecodec` (top), then:

`create_invite` — replace the `json.dumps({...})` return with:
```python
        pub, port = invitecodec.onion_split(self.store.get_meta("gossip_addr"))
        return invitecodec.encode_invite(
            self.device.cert.identity_pub[:8], pub, port, nonce, expiry)
```
(`nonce`/`expiry` unchanged above; `identity_pub[:8]` = first 4 bytes as 8 hex chars = `id_prefix`.)

`respond_to_invite` — replace the JSON parse + the `inv[...]` accesses:
```python
        try:
            typ, inv = invitecodec.decode(invite_json)
        except ValueError:
            raise ValueError("not an invite")
        if typ != "invite":
            raise ValueError("not an invite")
        # no cert to verify here anymore (it arrives in the final); keep the
        # id_prefix so complete_invite can bind it.
        my_nonce = os.urandom(16).hex()
        self._pending_responses = {k: v for k, v in self._pending_responses.items()
                                   if v[3] > time.time()}          # purge expired
        # store id_prefix alongside (addr, my_nonce, expiry) for the binding check
        self._pending_responses[my_nonce] = (
            inv["id_prefix"], inv["addr"], time.time() + 600, inv["nonce"])
        pub, port = invitecodec.onion_split(self.store.get_meta("gossip_addr"))
        return invitecodec.encode_response(
            pub, port, inv["nonce"], my_nonce,
            self.device.sign_raw(_friend_add_body(inv["nonce"])),
            self.device.cert.to_dict())
```
NOTE the `_pending_responses` tuple shape changed (now 4 fields incl. id_prefix; the expiry moved to index 2, nonce index 3) — update `complete_invite`'s unpacking to match, and any other reader (grep `_pending_responses`).

`finalize_invite` — its response parse and the final it returns:
```python
        try:
            typ, resp = invitecodec.decode(response_json)
        except ValueError:
            raise ValueError("no matching invite")
        if typ != "response":
            raise ValueError("no matching invite")
        nonce = resp["nonce"]
        exp = self._pending_invites.get(nonce)
        if exp is None:
            raise ValueError("no matching invite")
        if time.time() >= exp:
            del self._pending_invites[nonce]; raise ValueError("invite expired")
        cert = EnrollmentCert.from_dict(resp["cert"])
        if not cert.verify() or not _sig_ok(cert.device_pub, resp["sig"],
                                            _friend_add_body(nonce)):
            raise ValueError("invalid response signature")
        sig = self.device.sign_raw(_friend_add_body(resp["peer_nonce"]))
        self._add_friend(cert, resp["addr"])
        del self._pending_invites[nonce]
        # final now carries A's cert (moved out of the invite)
        return invitecodec.encode_final(resp["peer_nonce"], sig, self.device.cert.to_dict())
```
(The sign-before-mutate / consume-nonce-after ordering and its long comment MUST be preserved — keep the existing comment block; only the parse and the return line change.)

`complete_invite` — parse the final, verify cert + sig + binding check:
```python
        try:
            typ, fin = invitecodec.decode(final_json)
        except ValueError:
            raise ValueError("no matching response")
        if typ != "final":
            raise ValueError("no matching response")
        entry = self._pending_responses.get(fin["nonce"])
        if entry is None:
            raise ValueError("no matching response")
        id_prefix, addr, exp, _a_nonce = entry
        if time.time() >= exp:
            del self._pending_responses[fin["nonce"]]; raise ValueError("response expired")
        cert = EnrollmentCert.from_dict(fin["cert"])
        # binding check: the identity that completed must match the invite's
        # fingerprint (spec 2026-07-10-compact-invite; forces full
        # substitution, makes the human 4-char check meaningful).
        if cert.identity_pub[:8] != id_prefix:
            raise ValueError("identity does not match invite fingerprint")
        if not cert.verify() or not _sig_ok(cert.device_pub, fin["sig"],
                                            _friend_add_body(fin["nonce"])):
            raise ValueError("invalid final signature")
        del self._pending_responses[fin["nonce"]]
        self._add_friend(cert, addr)
```

(Grep every other reader of `_pending_responses` and `respond_to_invite`/`complete_invite` — e.g. `add_friend_via_invite` at ~971 — and update tuple-unpacking to the new 4-field shape. `add_friend_via_invite` calls respond_to_invite then complete_invite; confirm it passes the compact strings through unchanged.)

- [ ] **Step 4: Run tests** — `.venv\Scripts\python.exe -m pytest tests/test_friend_add_delivery.py tests/test_friend_add_integration.py tests/test_ceremonies.py tests/test_invitecodec.py -q` (drop any file that doesn't exist; grep first). Then FULL suite. Expect baseline + new, no regressions.

- [ ] **Step 5: Commit**

```bash
git add hearth/node.py tests/
git commit -m "feat(invite): friend-add ceremony uses the compact codec - cert moves invite->final, complete_invite binds the completing identity to the invite fingerprint"
```

---

### Task 3: Web display + verification copy (`hearth/web/app.js`, `style.css`)

**Files:**
- Modify: `hearth/web/app.js` (the Share tab `buildShareTab` and Enter tab `buildEnterTab` — grep), `hearth/web/style.css` (invite code display)
- Test: `tests/test_web_assets.py`

**Interfaces:** Consumes the compact code string from `/api/friend/invite` and `/api/friend/add`. Produces the truncated display + "ID starts with" copy.

- [ ] **Step 1: Write the failing test** — append to `tests/test_web_assets.py`:

```python
def test_invite_display_is_truncated_and_copies_full():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    # a short-display helper renders kreds·invite·<FP>…<suffix>; Copy copies raw
    assert "kreds·invite·" in js or "kreds·invite·" in js
    assert "shortInvite" in js
    # the enter path names the fingerprint the user should confirm
    assert "starts with" in js
    # copy uses the full code, not the truncated display (grep the copy wiring)
    share = _js_fn_body(js, "buildShareTab")
    assert "shortInvite" in share
```

- [ ] **Step 2: Run to verify failure** — FAIL.

- [ ] **Step 3: Implement.** Read `buildShareTab`/`buildEnterTab` first. Add near the invite helpers in app.js:

```js
// Display the ~80-char invite compactly: kreds·invite·<FP>…<last4>. The
// Copy button always copies the RAW code, never this truncated form.
function inviteFingerprint(code) {
  // FP is the first 4 chars after the "kreds" scheme in the decoded form;
  // simplest: the server returns it alongside the code (preferred) - if the
  // invite API returns {code, fp}, use r.fp; else derive client-side.
  return code.fp || "";
}
function shortInvite(code) {
  const raw = typeof code === "string" ? code : code.code;
  const fp = typeof code === "object" ? code.fp : "";
  return "kreds·invite·" + (fp || raw.slice(0, 4)) + "…" + raw.slice(-4);
}
```

Cleanest: have the invite API (`/api/friend/invite`, in `hearth/api.py`) return `{code, fp}` where `fp = invitecodec.fingerprint(node identity)`, and the add API (`/api/friend/add`) return the peer `fp` on a connected/pending result so the Enter tab can show "Connecting to someone whose ID starts with <fp>". Wire:
- Share tab: render `shortInvite({code, fp})` as the visible chip; the Copy button copies `code` (raw). Keep the existing `wireCopyButton` but ensure it copies `r.code`, not the display.
- Enter tab: after posting the code, show a line `"Connecting to someone whose ID starts with " + r.fp` (textContent, XSS-safe) before/at the connected/manual result.

(Server side: `/api/friend/invite` and `/api/friend/add` add the `fp` field. `fp` for the invite = `invitecodec.fingerprint(self.device.cert.identity_pub)`. For `/api/friend/add`, decode the pasted invite to get its `id_prefix`, then `fp = invitecodec.fingerprint(id_prefix + "0"*56)` — or add an `invitecodec.fp_from_prefix(id_prefix_hex)` helper so the display derives from the carried prefix. Add that helper in Task 1 if cleaner; note it here as a dependency.)

- [ ] **Step 4: Run FULL suite** — expect green.

- [ ] **Step 5: Commit**

```bash
git add hearth/web/app.js hearth/web/style.css hearth/api.py tests/test_web_assets.py
git commit -m "feat(invite): truncated kreds·invite·FP…code display + 'ID starts with' verification line; Copy copies the full code"
```

---

### Task 4: Docs, live smoke, full suite

**Files:**
- Modify: `docs/engineering-notes.md` ("Adding a friend" section)

- [ ] **Step 1: Docs.** In "Adding a friend", update the code description: the invite is now a compact ~80-char base58 code (`kreds·invite·…`), the enrollment cert travels in the Tor handshake not the code, and a 4-char identity fingerprint lets the recipient confirm "whose ID starts with X". State the honest limit plainly: the 4-char fingerprint is a casual integrity check (catches accidents/unsophisticated tampering) — NOT proof against a determined attacker who intercepts the trusted channel and grinds a look-alike key; real MITM proof needs comparing the full identity (a named follow-up).

- [ ] **Step 2: Live smoke** (the codec is crypto-adjacent; prove the round-trip on a real demo). Delete `run/`, start `python -m hearth demo`, then a scratchpad script: node 7201 `create_invite()` via `/api/friend/invite`, feed it to node 7203 via `/api/friend/add`, assert both become mutual friends and a subsequent post syncs. Confirm the returned code is < 100 chars. Stop demo, delete `run/`. (If a `test_friend_add_integration.py` already covers loopback auto-add, this smoke can be a lighter confirmation that the API surface returns the compact code + fp.)

- [ ] **Step 3: Full suite** — `.venv\Scripts\python.exe -m pytest -q` → all pass, 1 skipped.

- [ ] **Step 4: Commit**

```bash
git add docs/engineering-notes.md
git commit -m "docs: compact invite + the honest limit of the 4-char fingerprint in engineering notes"
```

---

## Self-review (done at write time)

- **Spec coverage:** ~80-char base58 (T1), cert invite→final (T2), 4-char fingerprint + binding check (T1 fingerprint / T2 check), truncated display + verification copy (T3), all-three-messages compacted (T1), honest limit documented (T4), full-identity verify recorded as deferred (spec §4 / ROADMAP). Onion pubkey reconstruction, hand-rolled base58, version byte all in T1.
- **Boundary discipline:** nonces stay hex, addr stays string, certs stay EnrollmentCert in the ceremony — codec converts only on the wire (T2 constraint), minimizing crypto-path risk.
- **Placeholders:** T1 is complete code; T2/T3 give the exact new pieces + explicit "grep and read current bodies" for the surgical edits (the ceremony has load-bearing ordering comments that must be preserved — called out).
- **Type consistency:** `_pending_responses` tuple shape change (now 4 fields incl. id_prefix) flagged with "update every reader"; `id_prefix` is 8 hex chars (4 bytes) consistently in create_invite, decode, and the binding check.
- **Risk note for the implementer:** the `_pending_responses` shape change and `add_friend_via_invite`'s pass-through are the two places a careless edit breaks the auto-flow — the integration test (T2 step 4) is the guard.
