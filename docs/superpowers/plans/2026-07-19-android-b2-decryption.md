# Android B.2 — Decryption + Readable History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The phone generates + publishes an X25519 enc key, the desktop backfills author-signed wrap-grants for the phone's existing own-authored content (a new isolated hearth sweep), and the phone decrypts and shows readable text from its real 253-message history — proven on the desk (vector + hearth + real-node gates) then on the G20.

**Architecture:** A Kotlin port of hearth's dmcrypt primitives (`unwrapKey`/`decryptBody` + matched AAD, vector-gated); a new isolated `maintain_own_device_grants` sweep in `hearth/node.py` (leaving the friend-facing `maintain_wrap_grants` untouched); the phone persists an enc key, pushes an `enckey` message (the transport's first write), pulls the backfill grants, and runs a decrypt pass over its SQLite store; a minimal readable-text feed.

**Tech Stack:** Kotlin + BouncyCastle (X25519, ChaCha20-Poly1305, HKDF-SHA256 — all present), the existing `KotlinWire`/`KotlinSync`/`SqliteSyncStore` (Brick B.1), Python `hearth/` (the one production change), gradle JVM tests + pytest + the extended real-node loopback gate, React Native feed UI.

**Spec:** `docs/superpowers/specs/2026-07-19-android-b2-decryption-design.md`
**Decomposition notes:** `docs/superpowers/specs/2026-07-19-android-b2-decryption-decomposition.md`

## Global Constraints

- **Commit messages: NO AI/Co-Authored-By trailers.** Style `feat(b2): ...` / `fix(b2): ...` / `docs(b2): ...` lowercase.
- **The friend-facing `maintain_wrap_grants` is NEVER modified.** The backfill is a NEW, isolated `maintain_own_device_grants`. Other `hearth/` changes are limited to: the new method + its two call sites (`api.py`, `sync.py`, right after `maintain_wrap_grants()`). `wire.ts`, `handshake.ts`, `wire_vectors.json`, the Brick A/B.1 Kotlin files (except additive consumption) stay untouched.
- **Own-authored content ONLY** — never friends' content (B.2c). **Minimal readable-text feed only** (no media/threads/reactions — B.2d). **Foreground-triggered.** **No composing/posting from the phone** (the only push is the `enckey`).
- **Exact crypto (from `hearth/dmcrypt.py` — byte-for-byte):**
  - `_derive_kek(shared)` = HKDF-SHA256, length 32, salt=None, `info=b"hearth/dm-wrap/v1"`.
  - wrap = `{eph_pub, nonce, wrapped_key}`; unwrap = X25519(enc_priv, eph_pub) → kek → ChaCha20-Poly1305 decrypt(nonce, wrapped_key, aad).
  - `decrypt_body(key, nonce, ct, aad)` = ChaCha20-Poly1305 decrypt(nonce, ct, aad) → JSON.
  - AAD (canonical JSON via `KotlinWire.canonical`, created_at as `PyFloat`): post `{"type":"post-aad","protocol":"hearth/v0.2","from":author,"scope":scope,"created_at":ca}`; dm `{"type":"dm-aad","protocol":"hearth/v0.2","from":sender,"to":to,"created_at":ca}`.
  - `enckey` payload: `{"kind":"enckey","enc_pub":<hex>,"created_at":<float>}` (device-signed, `make_enckey`).
  - `wrap_grant` payload: `{"kind":"wrap_grant","target":<msg_id>,"wraps":{device_pub:wrap},"created_at":<float>}` (author-signed, `make_wrap_grant`).
- **App package id** `eu.kreds.torspike`; tor-android 0.4.9.6; NDK 26.3.11579264; arm64-v8a; compileSdk 36.
- **Env:** dot-source `android_tor_spike/tools/env.ps1` in every PowerShell session touching gradle/adb; Python gates use `.venv\Scripts\python.exe`; `cd` persists; generous timeouts (up to 600000 ms).

## File Structure

```
hearth/node.py                          Task 2: + maintain_own_device_grants (new method)
hearth/api.py, hearth/sync.py           Task 2: + one call each after maintain_wrap_grants()
tests/test_own_device_grants.py         Task 2: the hearth pytest (heaviest coverage)
android_tor_spike/tools/make_dmcrypt_vectors.py   Task 1: dmcrypt vector generator
android_tor_spike/fixtures/dmcrypt_vectors.json   Task 1: committed vectors
android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/
  KotlinDmcrypt.kt        Task 1: unwrapKey/decryptBody/postAad/dmAad (BouncyCastle)
  EncKeys.kt             Task 3: X25519 keygen + persistence (SQLite)
  KotlinSync.kt          Task 4: MESSAGES phase pushes outbound (enckey)
  DecryptPass.kt         Task 5: decrypt own messages via wraps/wrap_grants
  SqliteSyncStore.kt     Task 3/5: + enc-key persistence + decrypted-text access
  TorManagerModule.kt    Task 7: + publishEncKey/getFeed
  index.ts               Task 7: + the feed/enc-key surface
android_tor_spike/app/modules/tor-manager/android/src/test/java/.../
  KotlinDmcryptTest.kt   Task 1: vector gate
  SyncLoopbackTest.kt    Task 6: extended — asserts the phone DECRYPTS
android_tor_spike/tools/sync_loopback_node.py   Task 6: + publish enc key + run catch-up
android_tor_spike/app/App.tsx           Task 8: readable-text feed
android_tor_spike/BRICK_B2_REPORT.md    Task 9
```

---

### Task 1: `KotlinDmcrypt` port + committed dmcrypt vectors

The phone's decrypt primitives, gated against vectors from real hearth. AAD fidelity is the load-bearing risk — this task pins it.

**Files:**
- Create: `android_tor_spike/tools/make_dmcrypt_vectors.py`
- Create: `android_tor_spike/fixtures/dmcrypt_vectors.json` (generated, committed)
- Create: `android/src/main/java/expo/modules/tormanager/KotlinDmcrypt.kt`
- Test: `android/src/test/java/expo/modules/tormanager/KotlinDmcryptTest.kt`
- Modify: `android/build.gradle` (copy `dmcrypt_vectors.json` into test resources, like the wire/message vectors)

**Interfaces:**
- Consumes: `KotlinWire` (canonical/PyFloat/fromHex/toHex), BouncyCastle.
- Produces `object KotlinDmcrypt`:
  - `fun postAad(author: String, scope: String, createdAt: Double): ByteArray`
  - `fun dmAad(sender: String, to: String, createdAt: Double): ByteArray`
  - `fun unwrapKey(wrap: Map<String, Any?>, encPrivHex: String, aad: ByteArray): ByteArray?` (wrap has `eph_pub`/`nonce`/`wrapped_key`)
  - `fun decryptBody(contentKey: ByteArray, bodyNonceHex: String, bodyCtHex: String, aad: ByteArray): Map<String, Any?>?`

- [ ] **Step 1: Write `make_dmcrypt_vectors.py`** (deterministic where possible; ephemeral randomness in wrap means the generator captures a real wrap+key and the Kotlin side UNWRAPS it — the assertion is round-trip, not byte-equality of the wrap)
```python
"""dmcrypt vectors from real hearth (THROWAWAY keys). The Kotlin side must
UNWRAP the committed wrap to the committed content key, and DECRYPT the
committed body_ct to the committed plaintext -- using the committed aad.
ASCII-only output."""
import json, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from hearth.dmcrypt import (new_content_key, encrypt_body, wrap_key,
                            post_aad, dm_aad, _gen_x25519_pair)
FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "dmcrypt_vectors.json"


def build():
    cases = []
    # a POST body wrapped to a device with a fresh enc keypair
    enc_priv, enc_pub = _gen_x25519_pair()
    author = "11" * 32
    created_at = 1752900000.123456
    aad = post_aad(author, "kreds", created_at)
    key = new_content_key()
    body = {"kind": "post", "text": "hello from the desk", "blobs": []}
    nonce_hex, ct_hex = encrypt_body(key, body, aad)
    wraps = wrap_key(key, {"dev1": enc_pub}, aad)
    cases.append({
        "kind": "post", "author": author, "scope": "kreds",
        "created_at": created_at, "enc_priv": enc_priv,
        "wrap": wraps["dev1"], "body_nonce": nonce_hex, "body_ct": ct_hex,
        "content_key": key.hex(), "plaintext": body,
    })
    # a DM body
    enc_priv2, enc_pub2 = _gen_x25519_pair()
    to = "22" * 32
    aad2 = dm_aad(author, to, created_at)
    key2 = new_content_key()
    body2 = {"kind": "dm", "text": "secret dm", "to": to}
    n2, c2 = encrypt_body(key2, body2, aad2)
    w2 = wrap_key(key2, {"dev2": enc_pub2}, aad2)
    cases.append({
        "kind": "dm", "author": author, "to": to, "created_at": created_at,
        "enc_priv": enc_priv2, "wrap": w2["dev2"], "body_nonce": n2,
        "body_ct": c2, "content_key": key2.hex(), "plaintext": body2,
    })
    return {"cases": cases}


def main():
    FIXTURE.write_text(json.dumps(build(), indent=2) + "\n", encoding="utf-8")
    print("wrote", FIXTURE)


if __name__ == "__main__":
    main()
```
> **Implementer:** confirm `_gen_x25519_pair` exists in `hearth/dmcrypt.py` (it does — used for enc keys) returning `(priv_hex, pub_hex)`; if the name differs, use `hearth.identity._gen_x25519_pair` (seen in identity.py). Run: `.venv\Scripts\python.exe android_tor_spike\tools\make_dmcrypt_vectors.py`.

- [ ] **Step 2: gradle copy** — add a `copyDmcryptVectors` task (mirror `copyMessageVectors`, 4-levels-up path) copying `dmcrypt_vectors.json` into `src/test/resources`; `dependsOn` on `preBuild` + `Test`; gitignore the test-resource copy.

- [ ] **Step 3: Write the failing test**
`android/src/test/java/expo/modules/tormanager/KotlinDmcryptTest.kt`:
```kotlin
package expo.modules.tormanager

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class KotlinDmcryptTest {
    private fun cases(): JSONArray {
        val t = javaClass.classLoader!!.getResourceAsStream("dmcrypt_vectors.json")!!
            .readBytes().toString(Charsets.UTF_8)
        return JSONObject(t).getJSONArray("cases")
    }
    private fun map(o: JSONObject): Map<String, Any?> =
        o.keys().asSequence().associateWith { o.get(it) }

    @Test fun unwrapAndDecrypt() {
        val cs = cases()
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            val aad = if (c.getString("kind") == "post")
                KotlinDmcrypt.postAad(c.getString("author"), c.getString("scope"), c.getDouble("created_at"))
            else
                KotlinDmcrypt.dmAad(c.getString("author"), c.getString("to"), c.getDouble("created_at"))
            val key = KotlinDmcrypt.unwrapKey(map(c.getJSONObject("wrap")), c.getString("enc_priv"), aad)
            assertNotNull("case $i unwrap", key)
            assertArrayEquals("case $i key", KotlinWire.fromHex(c.getString("content_key")), key)
            val body = KotlinDmcrypt.decryptBody(key!!, c.getString("body_nonce"), c.getString("body_ct"), aad)
            assertNotNull("case $i decrypt", body)
            assertEquals(c.getJSONObject("plaintext").getString("text"), body!!["text"])
        }
    }
}
```

- [ ] **Step 4: Run — expect FAIL** (unresolved KotlinDmcrypt).

- [ ] **Step 5: Write `KotlinDmcrypt.kt`**
```kotlin
package expo.modules.tormanager

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.digests.SHA256Digest
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec

/** Kotlin port of hearth.dmcrypt's reader half (unwrap + body decrypt),
 *  byte-matched to hearth via dmcrypt_vectors.json. AAD via KotlinWire. */
object KotlinDmcrypt {

    fun postAad(author: String, scope: String, createdAt: Double): ByteArray =
        KotlinWire.canonical(mapOf(
            "type" to "post-aad", "protocol" to KotlinWire.PROTOCOL,
            "from" to author, "scope" to scope,
            "created_at" to KotlinWire.PyFloat(createdAt)))

    fun dmAad(sender: String, to: String, createdAt: Double): ByteArray =
        KotlinWire.canonical(mapOf(
            "type" to "dm-aad", "protocol" to KotlinWire.PROTOCOL,
            "from" to sender, "to" to to,
            "created_at" to KotlinWire.PyFloat(createdAt)))

    private fun deriveKek(shared: ByteArray): ByteArray {
        val out = ByteArray(32)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(shared, null, "hearth/dm-wrap/v1".toByteArray()))
        hkdf.generateBytes(out, 0, 32)
        return out
    }

    /** ChaCha20-Poly1305 decrypt (12-byte nonce), returns null on auth failure. */
    private fun chachaOpen(key: ByteArray, nonce: ByteArray, ct: ByteArray, aad: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(aad)
        cipher.doFinal(ct)
    } catch (e: Exception) { null }

    fun unwrapKey(wrap: Map<String, Any?>, encPrivHex: String, aad: ByteArray): ByteArray? {
        return try {
            val ephPub = KotlinWire.fromHex(wrap["eph_pub"] as String)
            val priv = X25519PrivateKeyParameters(KotlinWire.fromHex(encPrivHex), 0)
            val shared = ByteArray(32)
            X25519Agreement().apply { init(priv) }.calculateAgreement(
                X25519PublicKeyParameters(ephPub, 0), shared, 0)
            val kek = deriveKek(shared)
            chachaOpen(kek, KotlinWire.fromHex(wrap["nonce"] as String),
                KotlinWire.fromHex(wrap["wrapped_key"] as String), aad)
        } catch (e: Exception) { null }
    }

    fun decryptBody(contentKey: ByteArray, bodyNonceHex: String, bodyCtHex: String, aad: ByteArray): Map<String, Any?>? {
        val plain = chachaOpen(contentKey, KotlinWire.fromHex(bodyNonceHex), KotlinWire.fromHex(bodyCtHex), aad)
            ?: return null
        val o = JSONObject(String(plain, Charsets.UTF_8))
        return o.keys().asSequence().associateWith { o.get(it) }
    }
}
```
> **Implementer notes:** (1) `Cipher.getInstance("ChaCha20-Poly1305")` requires API 28+ (the G20 is API 30 — fine; and it's a JVM class for the desk gate — the test JVM has it). If the JVM test can't find the provider, add BouncyCastle as a Security provider in the test, or use BouncyCastle's `ChaCha20Poly1305` AEAD directly (BouncyCastle is already a dep) — prefer the BouncyCastle AEAD (`org.bouncycastle.crypto.modes.ChaCha20Poly1305`) so both JVM and Android behave identically and no minSdk-28 gate is introduced. Adapt `chachaOpen` to BouncyCastle's AEAD if the javax path is fragile. (2) X25519 raw-key ECDH via BouncyCastle's `X25519Agreement` matches `cryptography`'s `X25519PrivateKey.exchange`.

- [ ] **Step 6: Run — expect PASS.** Commit:
```powershell
cd C:\Users\Wong\Desktop\Hearth
git add android_tor_spike/tools/make_dmcrypt_vectors.py android_tor_spike/fixtures/dmcrypt_vectors.json android_tor_spike/app/modules/tor-manager/android/build.gradle android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/KotlinDmcrypt.kt android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/KotlinDmcryptTest.kt android_tor_spike/app/modules/tor-manager/android/.gitignore
git commit -m "feat(b2): KotlinDmcrypt port (unwrapKey/decryptBody + AAD) green against committed dmcrypt vectors from real hearth"
```

---

### Task 2: Hearth `maintain_own_device_grants` (the production change) + pytest

The isolated own-device backfill. **`maintain_wrap_grants` is NOT touched.** Heaviest review + test coverage.

**Files:**
- Modify: `hearth/node.py` (add the method)
- Modify: `hearth/api.py:98` + `hearth/sync.py:249` (one call each, after `maintain_wrap_grants()`)
- Test: `tests/test_own_device_grants.py`

**Interfaces:**
- Consumes: `store.enckeys(identity)`, `store.post_messages(identity)`, the DM iterator (confirm: `store.dm_messages`/equivalent), `self._content_key(msg)`, `store.wrap_grants(msg_id, identity)`, `wrap_key`, `make_wrap_grant`, `self._publish`.
- Produces: `HearthNode.maintain_own_device_grants(now=None)`.

- [ ] **Step 1: Write the failing test**
`tests/test_own_device_grants.py`:
```python
"""maintain_own_device_grants: re-wrap OWN-authored content to a new OWN
device's enc key, via author-signed wrap_grants -- so a satellite device
(device-key only, no identity key) can decrypt the author's existing
history. maintain_wrap_grants (friends) is NOT involved."""
from hearth.node import HearthNode
from hearth.identity import DeviceKeys
from hearth.dmcrypt import unwrap_key, decrypt_body
from hearth.messages import KIND_WRAP_GRANT


def _enroll_second_own_device(node):
    """A satellite device: fresh device keypair + enc key, enrolled by the
    node's identity, enc key published into the node's store."""
    dev = DeviceKeys.create("phone")
    cert = node.device.enroll_other(dev.device_pub, "phone")
    node.store.save_views(node.identity_pub, {
        **node.store.load_views(node.identity_pub),
        dev.device_pub: __import__("hearth.identity", fromlist=["DeviceView"]).DeviceView(cert=cert)})
    # publish the phone's enc key into the node's store (as an ingested enckey would)
    node.store.record_enckey(node.identity_pub, dev.device_pub, dev.enc_pub, created_at=1.0)  # confirm the real setter
    return dev


def test_backfills_own_posts_to_new_device(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Me", "desk")
    mid = node.compose_post("my existing post")
    dev = _enroll_second_own_device(node)
    node.maintain_own_device_grants()
    grants = node.store.wrap_grants(mid, node.identity_pub)
    assert dev.device_pub in grants, "no grant minted for the new own device"
    # the new device can now unwrap + decrypt the post
    msg = node.store.get_message(mid)   # confirm accessor
    key, aad = node._content_key(msg)   # the node's own view (has the key)
    # the phone uses ITS enc_priv on the GRANT's wrap:
    gwrap = {dev.device_pub: grants[dev.device_pub]}
    phone_key = unwrap_key(gwrap, dev.device_pub, dev.enc_priv, aad)
    assert phone_key == key
    body = decrypt_body(phone_key, msg.payload["body_nonce"], msg.payload["body_ct"], aad)
    assert body["text"] == "my existing post"


def test_does_not_grant_friends_content(tmp_path):
    # a friend's post the node holds must NOT be granted to the own device
    # (own-authored only). Construct/ingest a friend post, run the sweep,
    # assert no grant for it. (Build per the real friend-post ingest path.)
    ...


def test_locked_or_revoked_mints_nothing(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Me", "desk")
    node.compose_post("p")
    node.enter_revoked_state()
    node.maintain_own_device_grants()   # revoked -> no-op, no exception
    # (assert no grants; the sweep returns early)
```
> **Implementer:** confirm the exact store setters/getters (`record_enckey` or how an enckey ingests → `store.enckeys`; `get_message`; the DM iterator; `DeviceView` import). Fill the two stubbed negative tests with the real friend-post construction (mirror an existing test's friend-post setup) — they are REQUIRED (the friends-excluded + revoked-skips guarantees are the security core). Do not leave `...`.

- [ ] **Step 2: Run — expect FAIL** (no `maintain_own_device_grants`).

- [ ] **Step 3: Add `maintain_own_device_grants` to `hearth/node.py`** (mirror `maintain_wrap_grants`' shape + guard; own devices + own content only)
```python
    def maintain_own_device_grants(self, now: Optional[float] = None):
        """Re-wrap this identity's OWN-authored content to its OWN other
        devices' enc keys (a satellite phone), via author-signed
        wrap_grants -- so a device-key-only device can decrypt the author's
        existing history. Distinct from maintain_wrap_grants (friends,
        kreds-wall only), which is left untouched. Own devices see ALL own
        content (journal + wall + inner + DMs): it is yours, on your device.

        Guard mirrors maintain_wrap_grants: minting signs, so
        locked/revoked/unenrolled skip entirely."""
        if self.revoked or self.locked or self.device.identity_pub is None:
            return
        own = self.store.enckeys(self.identity_pub)
        targets = {d: e for d, e in own.items() if d != self.device.device_pub}
        if not targets:
            return
        for msg in self._own_authored_messages():
            wrapped = set(msg.payload.get("wraps", {}))
            granted = self.store.wrap_grants(msg.msg_id, self.identity_pub)
            need = {d: e for d, e in targets.items()
                    if d not in wrapped and d not in granted}
            if not need:
                continue
            key, aad = self._content_key(msg)
            if key is None:                 # unhandled kind: skip, never crash
                continue
            wraps = wrap_key(key, need, aad)
            self._publish(make_wrap_grant(self.device, msg.msg_id, wraps))

    def _own_authored_messages(self):
        """Own posts (all placements) + own DMs -- the content the catch-up
        re-wraps. Confirm the exact iterators against store.py."""
        return list(self.store.post_messages(self.identity_pub)) + \
               list(self.store.dm_messages(self.identity_pub))   # confirm name
```
> **Implementer:** confirm `store.post_messages(identity)` returns own posts and find the own-DM iterator (grep `def dm_` / `WHERE kind=?` with KIND_DM in store.py). `_content_key` returns `(key, aad)` and returns `(None, None)` for kinds it can't key — the `if key is None: continue` is load-bearing (never crash the sweep). Ensure `make_wrap_grant`/`wrap_key`/`_publish` imports/refs are already available in node.py (they are used by maintain_wrap_grants).

- [ ] **Step 4: Wire the two call sites** — in `hearth/api.py` right after `node.maintain_wrap_grants()` (line ~98) add `node.maintain_own_device_grants()`; same in `hearth/sync.py` after `self.node.maintain_wrap_grants()` (line ~249): `self.node.maintain_own_device_grants()`.

- [ ] **Step 5: Run the pytest → PASS** (`.venv\Scripts\python.exe -m pytest tests\test_own_device_grants.py -v`). Then run a slice of the existing suite to confirm no regression in the wrap/sync area: `.venv\Scripts\python.exe -m pytest tests\test_wrap_grants.py tests\test_three_nodes.py -q` (or the nearest existing wrap/sync tests — confirm names).

- [ ] **Step 6: Commit**
```powershell
cd C:\Users\Wong\Desktop\Hearth
git add hearth/node.py hearth/api.py hearth/sync.py tests/test_own_device_grants.py
git commit -m "feat(b2): maintain_own_device_grants - backfill own content to own devices (satellite), isolated from maintain_wrap_grants"
```

---

### Task 3: Phone enc-key generation + persistence

**Files:**
- Create: `android/src/main/java/expo/modules/tormanager/EncKeys.kt`
- Modify: `android/src/main/java/expo/modules/tormanager/SqliteSyncStore.kt` (a `keys` table + get/set enc key)

**Interfaces:**
- Produces: `object EncKeys { fun getOrCreate(store: SyncStore): Pair<String,String> }` returning `(encPrivHex, encPubHex)` — generates an X25519 keypair (BouncyCastle `X25519PrivateKeyParameters(SecureRandom)`) once, persists via the store, returns it thereafter. `SyncStore` gains `fun getEncKey(): Pair<String,String>?` + `fun setEncKey(priv: String, pub: String)` (both InMemory + SQLite; SQLite: a `keys(k TEXT PRIMARY KEY, v TEXT)` table storing `enc_priv`/`enc_pub`).

- [ ] **Step 1** — add `getEncKey`/`setEncKey` to the `SyncStore` interface + both impls (InMemory: a field; SQLite: a `keys` table). JVM-test the InMemory round-trip + add a SqliteSyncStore method (contract mirror).
- [ ] **Step 2** — write `EncKeys.getOrCreate`: if `store.getEncKey()` present return it; else generate X25519 (`val p = X25519PrivateKeyParameters(SecureRandom()); val priv = toHex(p.encoded); val pub = toHex(p.generatePublicKey().encoded)`), `store.setEncKey(priv, pub)`, return.
- [ ] **Step 3** — build (`assembleDebug`) + the InMemory enc-key JVM test green.
- [ ] **Step 4: Commit** `feat(b2): phone X25519 enc key generation + SQLite persistence`

---

### Task 4: Push the `enckey` (KotlinSync MESSAGES phase sends outbound)

**Files:**
- Modify: `android/src/main/java/expo/modules/tormanager/KotlinSync.kt`

**Interfaces:**
- `KotlinSync.run` gains an `outbound: List<Map<String,Any?>>` param (the phone's messages to push; for B.2 = `[enckeyMessageDict]` or empty). The MESSAGES phase sends `{"t":"messages","msgs": outbound}` instead of always empty. Everything else unchanged (still ingests the node's msgs).

- [ ] **Step 1** — change the MESSAGES `writeFrame` to send `outbound` (default empty list, so existing callers/the heartbeat are unaffected). The enckey dict = a `SignedMessage.toDict()`-shaped map: `{"cert": <phone cert dict>, "seq": <next seq>, "payload": {"kind":"enckey","enc_pub":encPub,"created_at":<now>}, "signature": <device-signed>}`. Building the signed enckey (device sign over the canonical message body) reuses `KotlinWire.signRaw` + the `SignedMessage.body()` shape — add a small `composeEncKey(fixture, encPub, seq, createdAt): Map` helper (in KotlinSync or a new file), signing exactly as hearth's `make_enckey`/`sign_message`.
> **created_at + seq:** `created_at` = current time (the phone needs a clock — pass it in from the module, since `Date.now()` semantics differ; the module supplies `System.currentTimeMillis()/1000.0`). `seq` = the phone device's next message seq — the phone must track its own outbound seq (persist in the store, start at 1). Confirm hearth accepts the phone's enckey with a fresh seq (it's a new device; its seq starts at 1).
- [ ] **Step 2** — build; the desk gate (Task 6) proves the push end-to-end. Commit `feat(b2): KotlinSync pushes outbound messages in the MESSAGES phase (enckey publish)`

---

### Task 5: Decrypt pass over the store

**Files:**
- Create: `android/src/main/java/expo/modules/tormanager/DecryptPass.kt`
- Modify: `SqliteSyncStore.kt` (accessors: all own messages + all wrap_grants)

**Interfaces:**
- Produces `object DecryptPass { data class Decrypted(val msgId: String, val kind: String, val text: String, val createdAt: Double); fun run(store: SyncStore, phoneDevicePub: String, encPrivHex: String): List<Decrypted> }` — for each stored own POST/DM message: build its aad (postAad/dmAad from the payload's from/scope-or-to/created_at), find the content-key source — `payload.wraps[phoneDevicePub]` OR a stored `wrap_grant` with `target==msgId` whose `wraps[phoneDevicePub]` exists — `KotlinDmcrypt.unwrapKey` → `decryptBody` → pull `body["text"]`. Skip (don't crash) anything that fails.
- `SyncStore` gains `fun allMessages(): List<StoredMsg>` (msgId, kind, payload map) and `fun wrapGrantsFor(msgId): List<Map>` (the wraps dicts from wrap_grant messages targeting msgId).

- [ ] Write DecryptPass + the store accessors; a JVM test using InMemory: ingest a real wrapped post (built with hearth-parity in the test) + assert DecryptPass returns the plaintext text; a case decrypted via a wrap_grant (not the inline wraps). Build. Commit `feat(b2): DecryptPass - decrypt own posts/DMs via inline wraps or backfilled wrap_grants`

---

### Task 6: Extend the desk loopback gate — assert the phone DECRYPTS

**Files:**
- Modify: `android_tor_spike/tools/sync_loopback_node.py` (publish the phone's enc key into the node + run `maintain_own_device_grants`)
- Modify: `android/src/test/java/expo/modules/tormanager/SyncLoopbackTest.kt` (push enckey, sync twice, run DecryptPass, assert plaintext)

- [ ] Extend the seed script: after seeding own posts, accept the phone's enc_pub (the test passes it, OR the script mints the phone fixture WITH an enc key and the test uses it), record it into the node's store, call `node.maintain_own_device_grants()`, so the grants exist to be pulled. Extend the test: sync (pulls messages + grants), run `DecryptPass.run(store, phoneDevicePub, encPriv)`, assert the decrypted texts match the seeded post bodies. GATE: `:tor-manager:testDebugUnitTest` green — the phone decrypted real node content end-to-end. Commit `feat(b2): desk loopback gate proves the phone decrypts real backfilled content`

---

### Task 7: Module surface — publish enc key + expose the feed

**Files:**
- Modify: `TorManagerModule.kt` (+ `publishEncKey` folded into `syncNow`; `getFeed`), `index.ts`

- [ ] `syncNow` now: `EncKeys.getOrCreate(store)` → build the enckey outbound (if not yet confirmed published) → `KotlinSync.run(stream, store, devicePub, outbound=[enckey])` → after sync, run `DecryptPass` → cache the decrypted feed. Add `getFeed(): Promise<Decrypted[]>` returning the decrypted list; add `onSync` to also carry a feed-updated signal. `index.ts`: `getFeed`, `FeedItem` type. Build. Commit `feat(b2): module publishes enc key in syncNow + getFeed (decrypted readable history)`

---

### Task 8: Feed UI

**Files:**
- Modify: `android_tor_spike/app/App.tsx`

- [ ] Add a "Feed" section below the sync stats: a FlatList of `getFeed()` items (kind + text + timestamp), refreshed on mount + after each sync. Keep the existing dashboard. Build both APKs + install on the G20. Commit `feat(b2): readable-text feed on the dashboard`

---

### Task 9: On-device run + report

**Files:**
- Create: `android_tor_spike/BRICK_B2_REPORT.md`

- [ ] Report + run steps (desktop node online; phone Sync now → publishes enc key; desktop backfills grants on its next maintenance; phone Sync now again → pulls grants → decrypts → feed shows readable text from the real 253). **PAUSE — human-driven** (two syncs: one to publish the enc key, one to pull the grants the desktop minted in between). Fill the verdict. Commit `docs(b2): on-device readable-feed run + report`.

---

## Self-Review (performed at write time)

**Spec coverage:** KotlinDmcrypt port + AAD → Task 1; the hearth own-device backfill → Task 2 (isolated; maintain_wrap_grants untouched); enc-key gen+persist → Task 3; the enckey push (transport's first write) → Task 4; decrypt pass (wraps + wrap_grants) → Task 5; extended real-node gate proving decryption → Task 6; module + feed → Tasks 7-8; on-device → Task 9. Own-content-only, minimal-text-feed, foreground-trigger, no-posting all respected.

**Type consistency:** `KotlinDmcrypt` (postAad/dmAad/unwrapKey/decryptBody), `SyncStore` enc-key + message/grant accessors, `DecryptPass.Decrypted`, the `outbound` param on `KotlinSync.run` — names used consistently across Tasks 1/3/4/5/7. AAD built via `KotlinWire.canonical` + PyFloat consistently.

**Known judgment calls (flagged — confirm at implementation):**
- **The hearth store accessors** (`record_enckey`/enckey ingest, `get_message`, the own-DM iterator, `DeviceView` import, `dm_messages` name) — Task 2 notes: confirm each against `store.py`/`node.py`; the two negative tests (friends-excluded, revoked-skips) are REQUIRED, not optional.
- **ChaCha20-Poly1305 provider** — Task 1 note: prefer BouncyCastle's AEAD (`org.bouncycastle.crypto.modes.ChaCha20Poly1305`) over `javax Cipher` to avoid a minSdk-28 gate and keep JVM/Android identical.
- **Phone outbound seq/clock** — Task 4: the phone tracks its own message seq (persist, start at 1) and gets `created_at` from the module (`System.currentTimeMillis()/1000.0`); confirm hearth accepts a new device's enckey at seq 1.
- **Two-sync flow** — publishing the enckey (sync 1) and pulling the grants the desktop mints on its next maintenance (sync 2) are separate round-trips; the report + on-device steps make this explicit.
- **`maintain_wrap_grants` is NEVER edited** — the friend path stays exactly as-is; the backfill is additive only.
