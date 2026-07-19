# Android Content Sync — Brick B.1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The phone, after the proven AUTH, completes the real Kreds sync protocol (REVOCATIONS → DEFRIENDS → HAVE → MESSAGES → BLOBS) against the home node over Tor, verifies every `SignedMessage`, and durably stores the pulled own-identity encrypted messages + media blobs + friend list — natively (Kotlin), ready for Brick C to run in the background.

**Architecture:** Extend the proven `KotlinHandshake`/`KotlinWire` past AUTH with a new `KotlinSync` that runs the sync phases over a `Stream`, driven against a `SyncStore` interface (Android-free, so a JVM in-memory impl backs a real-node desk gate; a SQLite impl backs the phone). Foreground-triggered ("Sync now") for B.1; Brick C wires it into the background cadence. Decryption/render is B.2.

**Tech Stack:** Kotlin (extends the Brick A module), `KotlinWire` (canonical/frames/Ed25519, already vector-gated), Android SQLite (`SQLiteOpenHelper`), gradle JVM tests (JUnit) driving a Python subprocess node for the loopback gate, the existing `.venv` Python + `hearth/`.

**Spec:** `docs/superpowers/specs/2026-07-19-android-content-sync-brick-b-design.md`

## Global Constraints

- **Commit messages: NO AI/Co-Authored-By trailers.** Style `feat(brick-b): ...` / `fix(brick-b): ...` / `docs(brick-b): ...` lowercase.
- **Do not modify `hearth/` production code, `wire.ts`, `handshake.ts`, `fixtures/wire_vectors.json`, or the Brick A Kotlin files** (`TorEngine`/`KotlinWire`/`KotlinHandshake`/`HeartbeatStore`/`TorNodeService`) except the two explicitly-additive wiring points in Tasks 7-8 (`TorManagerModule.kt`, `index.ts`, `App.tsx`). Reuse `KotlinWire` for all crypto/canonical — no re-implemented Ed25519/JSON.
- **App package id** `eu.kreds.torspike`; tor-android `0.4.9.6`; NDK `26.3.11579264`; arm64-v8a only; compileSdk 36. Do not bump.
- **Exact wire shapes (verified against `hearth/`, do not deviate):**
  - `SignedMessage` dict: `{"cert": <EnrollmentCert dict>, "seq": <int>, "payload": <dict>, "signature": <hex>}`.
  - `SignedMessage` body (what's signed / hashed): `KotlinWire.canonical({"type":"message","protocol":"hearth/v0.2","identity_pub":<cert.identity_pub>,"device_pub":<cert.device_pub>,"seq":<seq>,"payload":<payload>})`. Signature verified with `cert.device_pub`.
  - `msg_id` = SHA-256(body).hex.
  - `blob_hash` = SHA-256(data).hex.
  - `seen` (per device): `{"contiguous": <int>, "sparse": <sorted int list>}`; membership: `1 <= seq <= contiguous || seq in sparse`; `add(seq)`: if `seq >= 1` and not present, add to sparse, then while `contiguous+1 in sparse` increment `contiguous` and drop it from sparse.
  - HAVE `summary`: `{identity_pub: {device_pub: <seen dict>}}`.
- **Sync phase frames (per `hearth/sync.py`), phone = initiator, `_swap` = write-then-read:** `revocations{revs:[]}`, `defriends{notices:[]}`, `have{summary,known,peers:[],addr:null}`, `messages{msgs:[]}`, `blob_want{hashes:[...]}`, `blobs{blobs:{}}`.
- **Own-identity content only** (no enc key → friends' content is B.2). **Read-only pull** (phone sends empty `msgs`/`blobs`). **Foreground-triggered** (background swap is Brick C). **No decryption** (B.2).
- **Env:** dot-source `android_tor_spike/tools/env.ps1` in every PowerShell session touching gradle/adb; `cd` persists (use absolute paths or re-`Set-Location`). Python gates use `.venv\Scripts\python.exe`. Generous timeouts (up to 600000 ms).

## File Structure

Under `android_tor_spike/app/modules/tor-manager/` unless noted.

```
android/src/main/java/expo/modules/tormanager/
  SeenSet.kt            Task 1: contiguous/sparse seen-seq set (mirrors hearth SeenSet)
  SignedMessageKt.kt    Task 2: parse/verify/msg_id (reuses KotlinWire)
  SyncStore.kt          Task 3: the SyncStore interface + InMemorySyncStore
  KotlinSync.kt         Task 4: the sync phases over a Stream + SyncStore
  SqliteSyncStore.kt    Task 6: Android SQLite impl of SyncStore
  TorManagerModule.kt   Task 7: + syncNow()/getSyncStats() + nodeSync event
android/src/test/java/expo/modules/tormanager/
  SeenSetTest.kt        Task 1
  SignedMessageTest.kt  Task 2 (gated by the committed message vector)
  SyncStoreTest.kt      Task 3
  SyncLoopbackTest.kt   Task 5: JVM test driving KotlinHandshake+KotlinSync vs a real Python node subprocess
index.ts                Task 7: + syncNow/getSyncStats/onSync/SyncStats
../../App.tsx           Task 8: + "Sync now" button + stats line
android_tor_spike/fixtures/
  message_vectors.json  Task 2: committed SignedMessage vectors (throwaway keys)
android_tor_spike/tools/
  make_message_vectors.py   Task 2: generator (real hearth SignedMessage)
  sync_loopback_node.py     Task 5: seed+serve a node for the loopback gate
android_tor_spike/BRICK_B_REPORT.md   Task 9
```

---

### Task 1: `SeenSet` (Kotlin) + JVM test

The per-device seen-sequence set, mirroring `hearth.identity.SeenSet`. This is the load-bearing fidelity primitive for the HAVE summary.

**Files:**
- Create: `android/src/main/java/expo/modules/tormanager/SeenSet.kt`
- Test: `android/src/test/java/expo/modules/tormanager/SeenSetTest.kt`

**Interfaces:**
- Produces `class SeenSet(contiguous: Int = 0, sparse: MutableSet<Int> = mutableSetOf())` with: `fun has(seq: Int): Boolean`, `fun add(seq: Int): Boolean`, `fun toJson(): Map<String, Any>` (`{"contiguous":Int,"sparse":List<Int> sorted}`), companion `fun fromJson(m: Map<String, Any?>): SeenSet`.

- [ ] **Step 1: Write the failing test**

`android/src/test/java/expo/modules/tormanager/SeenSetTest.kt`:
```kotlin
package expo.modules.tormanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeenSetTest {
    @Test fun addCompactsContiguous() {
        val s = SeenSet()
        assertTrue(s.add(2)); assertTrue(s.add(1))   // 1 then rolls up: 2 already sparse
        // after adding 2 (sparse={2}), adding 1 rolls contiguous to 2
        assertEquals(2, s.toJson()["contiguous"])
        assertEquals(emptyList<Int>(), s.toJson()["sparse"])
    }

    @Test fun hasAndDedup() {
        val s = SeenSet()
        assertTrue(s.add(1)); assertFalse(s.add(1))  // dedup
        assertTrue(s.has(1)); assertFalse(s.has(2))
        assertFalse(s.add(0))                        // seq < 1 rejected
    }

    @Test fun sparseAboveGap() {
        val s = SeenSet()
        s.add(1); s.add(3)                           // gap at 2
        assertEquals(1, s.toJson()["contiguous"])
        assertEquals(listOf(3), s.toJson()["sparse"])
        s.add(2)                                     // fills gap, rolls to 3
        assertEquals(3, s.toJson()["contiguous"])
        assertEquals(emptyList<Int>(), s.toJson()["sparse"])
    }

    @Test fun jsonRoundTrip() {
        val s = SeenSet(2, mutableSetOf(5, 7))
        val back = SeenSet.fromJson(s.toJson())
        assertEquals(2, back.toJson()["contiguous"])
        assertEquals(listOf(5, 7), back.toJson()["sparse"])
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (`. .\android_tor_spike\tools\env.ps1; cd android_tor_spike\app\android; .\gradlew :tor-manager:testDebugUnitTest`). Expected: unresolved `SeenSet`.

- [ ] **Step 3: Write `SeenSet.kt`**
```kotlin
package expo.modules.tormanager

/** Per-device accepted-sequence set, compactable — mirrors
 *  hearth.identity.SeenSet: seqs 1..contiguous are seen, plus a sparse set
 *  above. Accept any UNSEEN seq, reject reuse (D2 Ambush 2). */
class SeenSet(var contiguous: Int = 0, val sparse: MutableSet<Int> = mutableSetOf()) {
    fun has(seq: Int): Boolean = (seq in 1..contiguous) || seq in sparse

    fun add(seq: Int): Boolean {
        if (seq < 1 || has(seq)) return false
        sparse.add(seq)
        while ((contiguous + 1) in sparse) {
            contiguous += 1
            sparse.remove(contiguous)
        }
        return true
    }

    fun toJson(): Map<String, Any> = mapOf("contiguous" to contiguous, "sparse" to sparse.sorted())

    companion object {
        fun fromJson(m: Map<String, Any?>): SeenSet {
            val c = (m["contiguous"] as Number).toInt()
            @Suppress("UNCHECKED_CAST")
            val sp = (m["sparse"] as List<Number>).map { it.toInt() }.toMutableSet()
            return SeenSet(c, sp)
        }
    }
}
```

- [ ] **Step 4: Run — expect PASS.** Expected: 4 SeenSet tests pass (existing KotlinWire/HeartbeatStore tests stay green).

- [ ] **Step 5: Commit**
```powershell
cd C:\Users\Wong\Desktop\Hearth
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/SeenSet.kt android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/SeenSetTest.kt
git commit -m "feat(brick-b): SeenSet - per-device accepted-seq set (contiguous+sparse), mirrors hearth for the HAVE summary"
```

---

### Task 2: `SignedMessage` (Kotlin) + committed message vectors

Parse + verify + `msg_id`, gated against vectors generated from the real hearth `SignedMessage`.

**Files:**
- Create: `android_tor_spike/tools/make_message_vectors.py`
- Create: `android_tor_spike/fixtures/message_vectors.json` (generated, committed)
- Create: `android/src/main/java/expo/modules/tormanager/SignedMessageKt.kt`
- Test: `android/src/test/java/expo/modules/tormanager/SignedMessageTest.kt`
- Modify: `android/build.gradle` (extend `copyWireVectors` to also copy `message_vectors.json` into test resources)

**Interfaces:**
- Consumes: `KotlinWire` (canonical/PyFloat/verifyRaw/CertDict/toHex), the message-body shape from Global Constraints.
- Produces `data class SignedMessage(val cert: KotlinWire.CertDict, val seq: Int, val payload: Map<String, Any?>, val signature: String)` with `fun body(): ByteArray`, `fun msgId(): String`, `fun verifyDeviceSignature(): Boolean`, `val kind: String` (`payload["kind"] as? String ?: ""` — the payload's kind discriminator is `"kind"`, NOT `"type"`; `"type"` is the signed-envelope field. `KIND_POST="post"`, `KIND_DM="dm"` per hearth/messages.py), companion `fun fromDict(d: Map<String, Any?>): SignedMessage`.

- [ ] **Step 1: Write `make_message_vectors.py`** (deterministic, throwaway keys — output committed to the public repo)
```python
"""Deterministic SignedMessage vectors from the real hearth impl (THROWAWAY
keys). ASCII-only output. Gates the Kotlin SignedMessage port."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))  # repo root

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from hearth.identity import (EnrollmentCert, PROTOCOL, SignedMessage, canonical,
                             priv_from_hex, pub_hex, _make_enrollment)

FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "message_vectors.json"
IDP = priv_from_hex("11" * 32); DVP = priv_from_hex("22" * 32)
IDPUB = pub_hex(IDP.public_key()); DVPUB = pub_hex(DVP.public_key())
ENROLLED_AT = 1752900000.123456


def _cert() -> EnrollmentCert:
    return _make_enrollment(IDP, DVPUB, "vec-device", ENROLLED_AT)


def _msg(seq: int, payload: dict) -> SignedMessage:
    cert = _cert()
    body = canonical({"type": "message", "protocol": PROTOCOL,
                      "identity_pub": IDPUB, "device_pub": DVPUB,
                      "seq": seq, "payload": payload})
    return SignedMessage(cert, seq, payload, DVP.sign(body).hex())


def build() -> dict:
    cases = []
    # Payloads use the REAL field name "kind" (not "type"); "blobs"/"poster"/
    # "thumbs" are the cleartext blob-ref fields hearth.store.referenced_blobs
    # reads. Body_ct/scope minimal (BB-2 gates verify/msgId/kind, not the
    # encrypted-body semantics; the loopback gate proves against real node msgs).
    for seq, payload in [
        (1, {"kind": "post", "scope": "kreds", "body_ct": "aa", "blobs": []}),
        (2, {"kind": "post", "scope": "kreds", "body_ct": "bb", "blobs": ["aa" * 32], "thumbs": ["bb" * 32]}),
        (3, {"kind": "dm", "recipient": IDPUB, "body_ct": "cc", "poster": "cc" * 32}),
    ]:
        m = _msg(seq, payload)
        cases.append({"dict": m.to_dict(), "msg_id": m.msg_id, "kind": payload["kind"],
                      "body_hex": m.body().hex(), "valid": True})
    # a tampered message (payload changed after signing) -> invalid
    good = _msg(4, {"kind": "post", "scope": "kreds", "body_ct": "orig", "blobs": []})
    tampered = dict(good.to_dict(), payload={"kind": "post", "scope": "kreds", "body_ct": "EVIL", "blobs": []})
    cases.append({"dict": tampered, "msg_id": None, "kind": "post", "body_hex": None, "valid": False})
    return {"cases": cases}


def main():
    FIXTURE.write_text(json.dumps(build(), indent=2) + "\n", encoding="utf-8")
    print("wrote", FIXTURE)


if __name__ == "__main__":
    main()
```
Run it: `.venv\Scripts\python.exe android_tor_spike\tools\make_message_vectors.py`.

- [ ] **Step 2: Extend the gradle fixtures-copy** — in `android/build.gradle`, update the `copyWireVectors` task's `from`/`into` to also copy `message_vectors.json`:
```gradle
tasks.register('copyMessageVectors', Copy) {
  from "${projectDir}/../../../fixtures/message_vectors.json"
  into "${projectDir}/src/test/resources"
}
tasks.named('preBuild') { dependsOn 'copyMessageVectors' }
tasks.withType(Test).configureEach { dependsOn 'copyMessageVectors' }
```
Add `src/test/resources/message_vectors.json` to the module `.gitignore` (generated copy).

- [ ] **Step 3: Write the failing test**
`android/src/test/java/expo/modules/tormanager/SignedMessageTest.kt`:
```kotlin
package expo.modules.tormanager

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignedMessageTest {
    private fun cases(): JSONArray {
        val text = javaClass.classLoader!!.getResourceAsStream("message_vectors.json")!!
            .readBytes().toString(Charsets.UTF_8)
        return JSONObject(text).getJSONArray("cases")
    }

    // JSONObject -> Map<String,Any?> (payload is arbitrary JSON)
    private fun toMap(o: JSONObject): Map<String, Any?> =
        o.keys().asSequence().associateWith { unwrap(o.get(it)) }
    private fun unwrap(v: Any?): Any? = when (v) {
        is JSONObject -> toMap(v)
        is JSONArray -> (0 until v.length()).map { unwrap(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }

    @Test fun verifyAndMsgId() {
        val cs = cases()
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            val m = SignedMessageKt.fromDict(toMap(c.getJSONObject("dict")))
            assertEquals("case $i valid", c.getBoolean("valid"), m.verifyDeviceSignature())
            if (!c.isNull("msg_id")) {
                assertEquals(c.getString("body_hex"), KotlinWire.toHex(m.body()))
                assertEquals(c.getString("msg_id"), m.msgId())
            }
        }
    }
}
```

- [ ] **Step 4: Run — expect FAIL** (unresolved `SignedMessageKt`).

- [ ] **Step 5: Write `SignedMessageKt.kt`**
```kotlin
package expo.modules.tormanager

import java.security.MessageDigest

/** Kotlin port of hearth.identity.SignedMessage — parse/verify/msg_id.
 *  All crypto/canonical via KotlinWire; body matches the Python signer. */
data class SignedMessage(
    val cert: KotlinWire.CertDict,
    val seq: Int,
    val payload: Map<String, Any?>,
    val signature: String,
) {
    // The payload's kind discriminator is "kind" (KIND_POST="post",
    // KIND_DM="dm" per hearth/messages.py), NOT "type" -- "type" is the
    // signed ENVELOPE field ("message"). Reading "type" here returns "" for
    // every real message and silently breaks missingBlobs' kind filter.
    val kind: String get() = payload["kind"] as? String ?: ""

    fun body(): ByteArray = KotlinWire.canonical(mapOf(
        "type" to "message", "protocol" to KotlinWire.PROTOCOL,
        "identity_pub" to cert.identity_pub, "device_pub" to cert.device_pub,
        "seq" to seq, "payload" to payload,
    ))

    fun msgId(): String =
        KotlinWire.toHex(MessageDigest.getInstance("SHA-256").digest(body()))

    fun verifyDeviceSignature(): Boolean =
        KotlinWire.verifyRaw(cert.device_pub, signature, body())
}

object SignedMessageKt {
    @Suppress("UNCHECKED_CAST")
    fun fromDict(d: Map<String, Any?>): SignedMessage {
        val c = d["cert"] as Map<String, Any?>
        return SignedMessage(
            KotlinWire.CertDict(
                c["identity_pub"] as String, c["device_pub"] as String,
                c["device_name"] as String, (c["enrolled_at"] as Number).toDouble(),
                c["signature"] as String),
            (d["seq"] as Number).toInt(),
            d["payload"] as Map<String, Any?>,
            d["signature"] as String)
    }
}
```

> **Fidelity note:** `body()` serializes `payload` via `KotlinWire.canonical`, which sorts keys and renders numbers via the same rules as Python. The vector cases include nested payloads with lists/strings; a real payload with float fields would need `PyFloat` wrapping — none of the B.1 own-identity message payloads carry bare floats in the signed body beyond `enrolled_at` (inside the cert, already handled). If a vector case fails on a float, wrap that payload field in `PyFloat` and record it here.

- [ ] **Step 6: Run — expect PASS.** Then commit:
```powershell
cd C:\Users\Wong\Desktop\Hearth
git add android_tor_spike/tools/make_message_vectors.py android_tor_spike/fixtures/message_vectors.json android_tor_spike/app/modules/tor-manager/android/build.gradle android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/SignedMessageKt.kt android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/SignedMessageTest.kt android_tor_spike/app/modules/tor-manager/android/.gitignore
git commit -m "feat(brick-b): SignedMessage port (verify + msg_id) green against committed message vectors from real hearth"
```

---

### Task 3: `SyncStore` interface + `InMemorySyncStore` + JVM tests

**Files:**
- Create: `android/src/main/java/expo/modules/tormanager/SyncStore.kt`
- Test: `android/src/test/java/expo/modules/tormanager/SyncStoreTest.kt`

**Interfaces:**
- Consumes: `SignedMessage`/`SignedMessageKt`, `SeenSet`, `blob_hash` = SHA-256.
- Produces:
  - `interface SyncStore { fun summary(): Map<String, Map<String, Map<String, Any>>>; fun knownIdentities(): List<String>; fun addIdentity(id: String); fun ingestMessage(m: SignedMessage): Boolean; fun missingBlobs(): List<String>; fun putBlob(hash: String, data: ByteArray): Boolean; fun stats(): SyncStats }`
  - `data class SyncStats(val messages: Int, val blobs: Int, val identities: Int)`
  - `class InMemorySyncStore : SyncStore` — the reference impl. `ingestMessage`: reject if `!m.verifyDeviceSignature()`; dedup by `msgId`; store; update the per-`(identity_pub, device_pub)` `SeenSet.add(seq)`; return accepted. `summary()`: `{ipub: {dpub: seen.toJson()}}`. `missingBlobs()`: referenced blob hashes (from stored POST/DM payloads: `payload["blobs"]` list + `payload["poster"]` string + `payload["thumbs"]` list, junk-guarded to strings) minus stored hashes. `putBlob`: verify `SHA-256(data).hex == hash` before storing.

- [ ] **Step 1: Write the failing test**
`android/src/test/java/expo/modules/tormanager/SyncStoreTest.kt`:
```kotlin
package expo.modules.tormanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class SyncStoreTest {
    private val idp = "11".repeat(32); private val dvp = "22".repeat(32)
    private fun sha(b: ByteArray) = KotlinWire.toHex(MessageDigest.getInstance("SHA-256").digest(b))

    // Build a REAL signed message via the same primitives (device key = 0x22..).
    private fun msg(seq: Int, payload: Map<String, Any?>): SignedMessage {
        // sign with device priv 0x22.. so verifyDeviceSignature passes;
        // identity_pub/device_pub are the matching pubs.
        val devPriv = "22".repeat(32)
        val idPub = KotlinWire.toHex(org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(KotlinWire.fromHex("11".repeat(32)), 0).generatePublicKey().encoded)
        val dvPub = KotlinWire.toHex(org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(KotlinWire.fromHex(devPriv), 0).generatePublicKey().encoded)
        val cert = KotlinWire.CertDict(idPub, dvPub, "d", 1752900000.0, "00")
        val unsigned = SignedMessage(cert, seq, payload, "")
        return unsigned.copy(signature = KotlinWire.signRaw(devPriv, unsigned.body()))
    }

    @Test fun ingestDedupAndSummary() {
        val s = InMemorySyncStore()
        val m1 = msg(1, mapOf("type" to "post", "text" to "a", "blobs" to emptyList<String>()))
        assertTrue(s.ingestMessage(m1))
        assertFalse(s.ingestMessage(m1))              // dedup by msg_id
        val sum = s.summary().values.first().values.first()
        assertEquals(1, sum["contiguous"])
        assertEquals(1, s.stats().messages)
    }

    @Test fun rejectsBadSignature() {
        val s = InMemorySyncStore()
        val good = msg(1, mapOf("type" to "post", "text" to "a", "blobs" to emptyList<String>()))
        val forged = good.copy(payload = mapOf("type" to "post", "text" to "EVIL", "blobs" to emptyList<String>()))
        assertFalse(s.ingestMessage(forged))          // sig no longer matches body
        assertEquals(0, s.stats().messages)
    }

    @Test fun missingBlobsFromPayload() {
        val s = InMemorySyncStore()
        val h = "ab".repeat(32)
        s.ingestMessage(msg(1, mapOf("type" to "post", "text" to "p", "blobs" to listOf(h))))
        assertEquals(listOf(h), s.missingBlobs())
        val data = byteArrayOf(1, 2, 3)
        assertFalse(s.putBlob("00".repeat(32), data))  // wrong hash rejected
        assertTrue(s.putBlob(sha(data), data))
        // now h is still missing (we stored a different blob), sha(data) present
        assertEquals(listOf(h), s.missingBlobs())
        assertEquals(1, s.stats().blobs)
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (unresolved `InMemorySyncStore`).

- [ ] **Step 3: Write `SyncStore.kt`**
```kotlin
package expo.modules.tormanager

import java.security.MessageDigest

data class SyncStats(val messages: Int, val blobs: Int, val identities: Int)

interface SyncStore {
    fun summary(): Map<String, Map<String, Map<String, Any>>>
    fun knownIdentities(): List<String>
    fun addIdentity(id: String)
    fun ingestMessage(m: SignedMessage): Boolean
    fun missingBlobs(): List<String>
    fun putBlob(hash: String, data: ByteArray): Boolean
    fun stats(): SyncStats
}

/** Reference impl (JVM-testable, no Android). Also the shape the SQLite
 *  impl mirrors. */
class InMemorySyncStore : SyncStore {
    private val identities = linkedSetOf<String>()
    private val messages = linkedMapOf<String, SignedMessage>()     // msg_id -> msg
    private val seen = hashMapOf<Pair<String, String>, SeenSet>()   // (ipub,dpub) -> seen
    private val blobs = linkedMapOf<String, ByteArray>()            // hash -> data

    private fun sha(b: ByteArray) =
        KotlinWire.toHex(MessageDigest.getInstance("SHA-256").digest(b))

    override fun summary(): Map<String, Map<String, Map<String, Any>>> {
        val out = linkedMapOf<String, MutableMap<String, Map<String, Any>>>()
        for ((k, ss) in seen)
            out.getOrPut(k.first) { linkedMapOf() }[k.second] = ss.toJson()
        return out
    }

    override fun knownIdentities(): List<String> = identities.toList()
    override fun addIdentity(id: String) { identities.add(id) }

    override fun ingestMessage(m: SignedMessage): Boolean {
        // is_known gate (mirrors hearth Store.ingest_message's first check):
        // accept only from an identity we already know -- own identity is
        // seeded before sync, friends are added during HAVE. Do NOT
        // auto-register senders.
        if (m.cert.identity_pub !in identities) return false
        if (!m.verifyDeviceSignature()) return false
        val id = m.msgId()
        if (messages.containsKey(id)) return false            // already have this exact message
        // seq-reuse rejection -- SeenSet's whole purpose (D2 Ambush 2;
        // hearth Verifier.verify_message: `if not seen.add(seq): reject`).
        // A device reusing a seq with DIFFERENT content (different msg_id,
        // so past the dedup above) is rejected here.
        if (!seen.getOrPut(m.cert.identity_pub to m.cert.device_pub) { SeenSet() }.add(m.seq))
            return false
        messages[id] = m
        return true
    }

    /** Blob hashes referenced by stored POST/DM payloads, minus what we hold.
     *  Mirrors hearth.store.referenced_blobs for the KIND_POST/KIND_DM fields
     *  (blobs list + poster str + thumbs list), junk-guarded to strings. */
    override fun missingBlobs(): List<String> {
        val refs = linkedSetOf<String>()
        for (m in messages.values) {
            if (m.kind != "post" && m.kind != "dm") continue
            (m.payload["blobs"] as? List<*>)?.forEach { if (it is String) refs.add(it) }
            (m.payload["poster"] as? String)?.let { if (it.isNotEmpty()) refs.add(it) }
            (m.payload["thumbs"] as? List<*>)?.forEach { if (it is String) refs.add(it) }
        }
        return refs.filter { it !in blobs }
    }

    override fun putBlob(hash: String, data: ByteArray): Boolean {
        if (sha(data) != hash) return false
        blobs[hash] = data
        return true
    }

    override fun stats(): SyncStats = SyncStats(messages.size, blobs.size, identities.size)
}
```

> **Kind-string note:** the vectors/tests use lowercase payload `type` values (`"post"`, `"dm"`). Confirm against `hearth` the exact `KIND_POST`/`KIND_DM` payload `type` strings during implementation (grep `KIND_POST =`); if the real strings differ (e.g. capitalized), update the `kind`/`missingBlobs` comparisons AND the vectors to match — the vectors are generated from real hearth, so they are authoritative.

- [ ] **Step 4: Run — expect PASS.** Commit:
```powershell
cd C:\Users\Wong\Desktop\Hearth
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/SyncStore.kt android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/SyncStoreTest.kt
git commit -m "feat(brick-b): SyncStore interface + InMemorySyncStore - ingest/dedup/seen-summary/missingBlobs/putBlob, JVM-tested"
```

---

### Task 4: `KotlinSync` (the sync phases)

**Files:**
- Create: `android/src/main/java/expo/modules/tormanager/KotlinSync.kt`

**Interfaces:**
- Consumes: `KotlinWire` (readFrame/writeFrame via the `Stream`), `SyncStore`, `SignedMessageKt`. Assumes AUTH already completed on the `Stream`.
- Produces:
  - `sealed class SyncResult { data class Ok(val messages: Int, val blobs: Int, val identities: Int): SyncResult(); object SelfRevoked: SyncResult(); data class Failed(val stage: String, val reason: String): SyncResult() }`
  - `object KotlinSync { fun run(stream: Stream, store: SyncStore, ownDevicePub: String): SyncResult }`
- The frame read/write reuse the same length-prefixed helpers as `KotlinHandshake` — extract them if not already shared (KotlinHandshake has private `readFrame`/`writeFrame`; add equivalent here or make them `internal` in a shared spot). Keep them identical to `KotlinWire.writeFrameBytes` pairing.

- [ ] **Step 1: Write `KotlinSync.kt`**
```kotlin
package expo.modules.tormanager

import org.json.JSONArray
import org.json.JSONObject

sealed class SyncResult {
    data class Ok(val messages: Int, val blobs: Int, val identities: Int) : SyncResult()
    object SelfRevoked : SyncResult()
    data class Failed(val stage: String, val reason: String) : SyncResult()
}

/** Runs the post-AUTH sync phases (hearth/sync.py _session) as INITIATOR,
 *  over an already-authenticated Stream. Read-only pull: sends empty
 *  msgs/blobs; ingests the node's own-identity messages + blobs. */
object KotlinSync {

    private fun writeFrame(s: Stream, obj: Map<String, Any?>) =
        s.write(KotlinWire.writeFrameBytes(obj))   // NOTE: suspend/blocking bridged by caller's IO scope

    private fun readFrame(s: Stream): JSONObject {
        val header = s.readExactSync(4)
        val n = (((header[0].toLong() and 0xff) shl 24) or ((header[1].toLong() and 0xff) shl 16) or
                 ((header[2].toLong() and 0xff) shl 8) or (header[3].toLong() and 0xff))
        require(n <= KotlinWire.MAX_FRAME) { "frame too large" }
        val body = s.readExactSync(n.toInt())
        return JSONObject(String(body, Charsets.US_ASCII))
    }

    // JSONObject payload -> Map for SignedMessageKt
    private fun toMap(o: JSONObject): Map<String, Any?> =
        o.keys().asSequence().associateWith { unwrap(o.get(it)) }
    private fun unwrap(v: Any?): Any? = when (v) {
        is JSONObject -> toMap(v)
        is JSONArray -> (0 until v.length()).map { unwrap(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }

    fun run(stream: Stream, store: SyncStore, ownDevicePub: String): SyncResult {
        try {
            // -- REVOCATIONS -- (initiator writes then reads)
            writeFrame(stream, mapOf("t" to "revocations", "revs" to emptyList<Any>()))
            val revs = readFrame(stream)
            if (revs.optString("t") == "refused") return SyncResult.Failed("revocations", "refused")
            val revArr = revs.optJSONArray("revs") ?: JSONArray()
            for (i in 0 until revArr.length()) {
                val r = revArr.getJSONObject(i)
                if (r.optString("device_pub") == ownDevicePub) return SyncResult.SelfRevoked
            }

            // -- DEFRIENDS --
            writeFrame(stream, mapOf("t" to "defriends", "notices" to emptyList<Any>()))
            readFrame(stream)   // read node's, ignore (own-identity, B.1)

            // -- HAVE --
            writeFrame(stream, mapOf("t" to "have",
                "summary" to store.summary(), "known" to store.knownIdentities(),
                "peers" to emptyList<Any>(), "addr" to null))
            val have = readFrame(stream)
            val known = have.optJSONArray("known") ?: JSONArray()
            for (i in 0 until known.length()) store.addIdentity(known.getString(i))

            // -- MESSAGES -- (send empty, ingest node's)
            writeFrame(stream, mapOf("t" to "messages", "msgs" to emptyList<Any>()))
            val msgs = readFrame(stream)
            val msgArr = msgs.optJSONArray("msgs") ?: JSONArray()
            for (i in 0 until msgArr.length()) {
                val m = SignedMessageKt.fromDict(toMap(msgArr.getJSONObject(i)))
                store.ingestMessage(m)   // verifies + dedups internally
            }

            // -- BLOBS -- (want swap, then blobs swap)
            writeFrame(stream, mapOf("t" to "blob_want", "hashes" to store.missingBlobs()))
            readFrame(stream)   // node's want; we give nothing
            writeFrame(stream, mapOf("t" to "blobs", "blobs" to emptyMap<String, Any>()))
            val blobs = readFrame(stream)
            val given = blobs.optJSONObject("blobs") ?: JSONObject()
            for (h in given.keys()) {
                val data = android.util.Base64.decode(given.getString(h), android.util.Base64.NO_WRAP)
                store.putBlob(h, data)   // verifies hash internally
            }

            val st = store.stats()
            return SyncResult.Ok(st.messages, st.blobs, st.identities)
        } catch (e: Exception) {
            return SyncResult.Failed("io", e.toString())
        }
    }
}
```

> **Two implementer notes:**
> 1. `Stream` (from `wire.ts` on the TS side) is an async interface; on Kotlin the `KotlinHandshake` used blocking `TorEngine.recv`. `KotlinSync` needs the SAME blocking frame read/write `KotlinHandshake` uses. Reuse KotlinHandshake's exact `readFrame`/`writeFrame` mechanism — if they're private, refactor them into an `internal object Frames` both call, rather than duplicating. `readExactSync`/`s.write` above are placeholders for whatever KotlinHandshake already does over `TorEngine`; match it exactly (the desk gate proves the pairing).
> 2. `android.util.Base64` is an Android class — the desk JVM test (Task 5) runs on the JVM without Android. Use `java.util.Base64.getDecoder()` instead (JVM + Android both have it on API 26+), so `KotlinSync` stays Android-free. Replace the `android.util.Base64` line with `java.util.Base64.getDecoder().decode(given.getString(h))`.

- [ ] **Step 2: Build to verify it compiles** (`. .\android_tor_spike\tools\env.ps1; cd android_tor_spike\app\android; .\gradlew assembleDebug`). Expected: BUILD SUCCESSFUL. (Runtime proven by the desk gate Task 5 + on-device Task 9.)

- [ ] **Step 3: Commit**
```powershell
cd C:\Users\Wong\Desktop\Hearth
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/KotlinSync.kt
git commit -m "feat(brick-b): KotlinSync - post-AUTH sync phases (revocations/defriends/have/messages/blobs) over a Stream + SyncStore"
```

---

### Task 5: Desk loopback gate — JVM test vs a real Python node

The spine gate: a JVM test starts a seeded Python `SyncService` subprocess, then drives `KotlinHandshake` AUTH + `KotlinSync` against it over loopback TCP with an `InMemorySyncStore`, asserting the store ends holding the seeded messages/blobs/identities. Mirrors `test_handshake_desk.py`, extended through sync.

**Files:**
- Create: `android_tor_spike/tools/sync_loopback_node.py`
- Test: `android/src/test/java/expo/modules/tormanager/SyncLoopbackTest.kt`

**Interfaces:**
- Consumes: `TorEngine`? No — the desk gate must NOT need Tor. It needs a `Stream` over a plain TCP socket. Add a small JVM-only `Stream` over `java.net.Socket` in the TEST source (mirrors the desk `node_stream.ts`), so `KotlinHandshake`/`KotlinSync` run over loopback TCP without `TorEngine`.
- Produces: proof the full AUTH+sync completes against the real node.

- [ ] **Step 1: Write `sync_loopback_node.py`** — seed a node with own-identity messages + blobs, mint the phone fixture, serve `SyncService` on a free port, print a JSON line `{port, fixture, expect:{messages,blobs,identities}}` then serve until killed.
```python
"""Desk loopback: seed a node with own-identity messages + blobs, mint the
phone fixture, serve SyncService on 127.0.0.1, print a JSON handshake line,
then serve until killed. Driven by SyncLoopbackTest.kt."""
import asyncio, json, os, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from hearth.node import HearthNode
from hearth.sync import SyncService

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from mint import mint_fixture   # reused from the spike


async def main(data_dir):
    node = HearthNode.create(Path(data_dir) / "n", "Desk", "desk")
    # seed own-identity content: a couple of posts, one with a blob
    blob = os.urandom(64)
    from hearth.messages import blob_hash
    node.store.put_blob(blob)
    node.compose_post({"type": "post", "text": "hello from desk", "blobs": []})
    node.compose_post({"type": "post", "text": "with pic", "blobs": [blob_hash(blob)]})
    sync = SyncService(node)
    port = await sync.start("127.0.0.1", 0)
    fx = mint_fixture(node)
    fx["onion_addr"] = f"127.0.0.1:{port}"
    expect = {"messages": len(node.store.all_message_ids()) if hasattr(node.store, "all_message_ids") else 2,
              "blobs": 1, "identities": len(node.store.known_identities())}
    print(json.dumps({"port": port, "fixture": fx, "expect": expect}), flush=True)
    try:
        await asyncio.Event().wait()   # serve until killed
    finally:
        await sync.stop()


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1]))
```
> **Implementer:** verify the exact node API for composing an own post + counting messages against `hearth/node.py` (`compose_post` name/signature, how to count stored messages — e.g. `store` query). Adjust `expect.messages` to the true count the node will send the phone (own-identity posts). If `compose_post` needs different args, match it. The `expect` block is the assertion target; it MUST equal what the node actually serves.

- [ ] **Step 2: Write the JVM loopback test** (starts the python subprocess, drives AUTH+sync)
`android/src/test/java/expo/modules/tormanager/SyncLoopbackTest.kt`:
```kotlin
package expo.modules.tormanager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.nio.file.Files

class SyncLoopbackTest {
    // Minimal blocking Stream over a TCP socket (JVM-only, test-side).
    private class SocketStream(host: String, port: Int) : Stream {
        private val sock = Socket(host, port).apply { soTimeout = 30000 }
        private val inp = sock.getInputStream(); private val out = sock.getOutputStream()
        override fun readExactSync(n: Int): ByteArray {
            val b = ByteArray(n); var off = 0
            while (off < n) { val r = inp.read(b, off, n - off); if (r < 0) throw RuntimeException("EOF"); off += r }
            return b
        }
        override fun write(bytes: ByteArray) { out.write(bytes); out.flush() }
        override fun close() { sock.close() }
    }

    @Test fun syncsRealOwnIdentityContent() {
        val repo = java.io.File(System.getProperty("user.dir")).parentFile.parentFile.parentFile.parentFile // -> repo root; adjust in impl
        val venvPy = java.io.File(repo, ".venv/Scripts/python.exe")
        val script = java.io.File(repo, "android_tor_spike/tools/sync_loopback_node.py")
        val tmp = Files.createTempDirectory("syncgate").toFile()
        val proc = ProcessBuilder(venvPy.absolutePath, script.absolutePath, tmp.absolutePath)
            .redirectErrorStream(false).start()
        try {
            val line = BufferedReader(InputStreamReader(proc.inputStream)).readLine()
                ?: throw RuntimeException("no handshake line from node")
            val info = JSONObject(line)
            val fx = info.getJSONObject("fixture")
            val expect = info.getJSONObject("expect")
            val parts = fx.getString("onion_addr").split(":")
            val stream = SocketStream(parts[0], parts[1].toInt())
            // AUTH via KotlinHandshake over the raw Stream (see the refactor
            // note below), then KotlinSync over the same Stream.
            val fixture = KotlinHandshake.parseFixture(fx.toString())
            val hs = KotlinHandshake.runOverStream(stream, fixture)
            assertTrue("auth: $hs", hs is KotlinHandshake.HandshakeResult.Accepted)
            val store = InMemorySyncStore()
            store.addIdentity(fixture.cert.identity_pub)
            val res = KotlinSync.run(stream, store, fixture.device_pub)
            assertTrue("sync: $res", res is SyncResult.Ok)
            assertEquals(expect.getInt("messages"), store.stats().messages)
            assertEquals(expect.getInt("blobs"), store.stats().blobs)
        } finally {
            proc.destroy()
        }
    }
}
```
> **Required refactor (name it in the commit):** `KotlinHandshake.run` currently dials via `TorEngine` conn ids. Add `fun runOverStream(stream: Stream, fixture: Fixture, rnd:()->String = ::randomHex16): HandshakeResult` that runs HELLO/AUTH+probe directly over a `Stream` (the existing `run` becomes a thin wrapper that opens a `TorStream` and calls `runOverStream`). Likewise ensure `Stream` exposes the blocking `readExactSync(n)`/`write`/`close` used here (align its shape with what `KotlinHandshake`/`KotlinSync`/`TorStream` use — one `Stream` interface, blocking, in the module's Kotlin, NOT the TS `wire.ts` one). This unifies the desk (SocketStream) and phone (TorStream) paths behind one interface — the whole point of the SyncStore/Stream split.

- [ ] **Step 3: Run the desk gate**
```powershell
. .\android_tor_spike\tools\env.ps1; cd android_tor_spike\app\android; .\gradlew :tor-manager:testDebugUnitTest
```
Expected: `syncsRealOwnIdentityContent` PASSES — the Kotlin client completed real AUTH + sync against the real node and stored the seeded messages/blobs. If it fails at a phase, the `SyncResult.Failed(stage,...)` / assertion localizes it. Fix the Kotlin side; the node is authoritative.

- [ ] **Step 4: Commit**
```powershell
cd C:\Users\Wong\Desktop\Hearth
git add android_tor_spike/tools/sync_loopback_node.py android_tor_spike/app/modules/tor-manager/android/src/test/java/expo/modules/tormanager/SyncLoopbackTest.kt android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/KotlinHandshake.kt
git commit -m "feat(brick-b): desk loopback gate - Kotlin AUTH+sync completes against a real seeded node, stores its own-identity messages+blobs; KotlinHandshake.runOverStream"
```

---

### Task 6: `SqliteSyncStore` (Android impl)

**Files:**
- Create: `android/src/main/java/expo/modules/tormanager/SqliteSyncStore.kt`

**Interfaces:**
- Produces `class SqliteSyncStore(context: Context) : SyncStore` — the Android SQLite impl, semantics identical to `InMemorySyncStore` (proven by the shared `SyncStore` contract; the in-memory impl is the reference). Tables: `identities(identity_pub TEXT PRIMARY KEY)`, `messages(msg_id TEXT PRIMARY KEY, identity_pub TEXT, device_pub TEXT, seq INTEGER, kind TEXT, msg_json TEXT)`, `blobs(hash TEXT PRIMARY KEY, data BLOB)`. `summary()` rebuilds `SeenSet`s from the messages table (group by identity_pub, device_pub; `add` each seq); `missingBlobs()` scans `msg_json` payloads (POST/DM) for `blobs`/`poster`/`thumbs` refs.

- [ ] **Step 1: Write `SqliteSyncStore.kt`** — a `SQLiteOpenHelper` with the three tables; each `SyncStore` method backed by SQL. `ingestMessage`: verify sig, `INSERT OR IGNORE` by `msg_id` (returns changed), store `KotlinWire`-serialized `msg_json` (or the original dict JSON). `summary()`: `SELECT identity_pub,device_pub,seq FROM messages`, fold into `SeenSet`s. `missingBlobs()`: `SELECT msg_json FROM messages WHERE kind IN ('post','dm')`, extract refs, minus `SELECT hash FROM blobs`. `putBlob`: verify hash, `INSERT OR IGNORE`. (Full code: mirror `InMemorySyncStore` method-for-method over SQL; the shared `SyncStore` tests in Task 3 define the contract, and the on-device run exercises this impl.)

Because this is a mechanical SQL mirror of the reference impl, the implementer writes it to satisfy the identical `SyncStore` contract. Keep `msg_json` as the exact dict the node sent (so re-serialization for the next HAVE is faithful) — store the raw JSON string received, not a re-serialized form.

- [ ] **Step 2: Build to verify** (`.\gradlew assembleDebug`). Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**
```powershell
cd C:\Users\Wong\Desktop\Hearth
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/SqliteSyncStore.kt
git commit -m "feat(brick-b): SqliteSyncStore - Android SQLite impl of SyncStore (persisted messages/blobs/identities)"
```

---

### Task 7: Module `syncNow`/`getSyncStats` + `index.ts`

**Files:**
- Modify: `android/src/main/java/expo/modules/tormanager/TorManagerModule.kt`
- Modify: `index.ts`

**Interfaces:**
- Consumes: `TorEngine` (dial), `KotlinHandshake` (`runOverStream` or the existing conn-based run via a `TorStream`), `KotlinSync`, `SqliteSyncStore`, `KotlinHandshake.parseFixture`, the fixture at `TorEngine.externalDir()/spike_phone_fixture.json`.
- Produces (JS):
  - `syncNow(): void` (fire-and-forget; result via event) and `getSyncStats(): Promise<SyncStats>` where `SyncStats = {messages:number, blobs:number, identities:number}`
  - `onSync(cb: (r: {ok:boolean, messages:number, blobs:number, identities:number, reason?:string}) => void): () => void`

- [ ] **Step 1: Add to `TorManagerModule.kt`** an `AsyncFunction("syncNow")` (on `ioScope`) that: reads the fixture; ensures Tor is up (`TorEngine.isUp`, else bootstrap or fail); `TorEngine.dial(host, 9997)`; runs `KotlinHandshake` to Accepted; opens a `SqliteSyncStore(context)`, seeds `addIdentity(fixture.cert.identity_pub)`; `KotlinSync.run(...)`; sends a `nodeSync` event `{ok, messages, blobs, identities, reason?}`. Add `Function("getSyncStats")` returning `SqliteSyncStore(context).stats()` as a map. Add `"nodeSync"` to `Events(...)`. Reuse the existing `TorStream`/conn plumbing for the Handshake+Sync over Tor (the same path the heartbeat uses).

- [ ] **Step 2: Add to `index.ts`:**
```ts
export interface SyncStats { messages: number; blobs: number; identities: number }
export function syncNow(): void { native.syncNow(); }
export function getSyncStats(): Promise<SyncStats> { return native.getSyncStats(); }
export function onSync(cb: (r: { ok: boolean; messages: number; blobs: number; identities: number; reason?: string }) => void): () => void {
  const sub = native.addListener("nodeSync", (e: any) => cb(e));
  return () => sub.remove();
}
```
Add `nodeSync` to the module's `Events(...)`.

- [ ] **Step 3: Build** (`.\gradlew assembleDebug`). Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**
```powershell
cd C:\Users\Wong\Desktop\Hearth
git add android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/TorManagerModule.kt android_tor_spike/app/modules/tor-manager/index.ts
git commit -m "feat(brick-b): module syncNow/getSyncStats + nodeSync event - foreground-triggered content sync over Tor"
```

---

### Task 8: `App.tsx` — "Sync now" + stats

**Files:**
- Modify: `../../App.tsx`

- [ ] **Step 1: Add to `App.tsx`** a "Sync now" button (`onPress={syncNow}`), a stats line fed by `getSyncStats()` on mount + `onSync` events (`messages / blobs / friends`), and a last-sync result line (`ok` → `synced N msgs, M blobs` / `!ok` → `sync failed: reason`). Import `syncNow, getSyncStats, onSync, SyncStats` from `./modules/tor-manager`. Keep the existing node dashboard (Start/Stop/Beat-now/history) intact — add the sync controls as a new section.

- [ ] **Step 2: Build both variants + install** (`.\gradlew assembleDebug assembleRelease`, then `adb -s ZY32DLZQ2N install -r ...\app-release.apk`). Expected: BUILD SUCCESSFUL, Success.

- [ ] **Step 3: Commit**
```powershell
cd C:\Users\Wong\Desktop\Hearth
git add android_tor_spike/app/App.tsx
git commit -m "feat(brick-b): dashboard Sync now button + content-sync stats line"
```

---

### Task 9: On-device run + report

**Files:**
- Create: `android_tor_spike/BRICK_B_REPORT.md`

- [ ] **Step 1: Write `BRICK_B_REPORT.md`** — the run steps (desktop node online; fixture minted+pushed as in the spike; tap "Sync now"; observe the stats line show non-zero messages/blobs/friends) + the DoD (own-identity messages + blobs + friend list pulled and stored) + a `[PENDING RUN]` verdict/counts section for August's run.

- [ ] **Step 2: Hand August the run** (per testing-workflow-division): desktop Kreds online, fixture pushed, tap "Sync now", report the stats counts. **PAUSE — human-driven.** Then fill the report's verdict/counts.

- [ ] **Step 3: Commit**
```powershell
cd C:\Users\Wong\Desktop\Hearth
git add android_tor_spike/BRICK_B_REPORT.md
git commit -m "docs(brick-b): on-device sync run + report - own-identity messages/blobs/friends pulled over Tor"
```

---

## Self-Review (performed at write time)

**Spec coverage:** Kotlin sync transport → Tasks 4/5; SeenSet/summary → Task 1; SignedMessage verify+msg_id → Task 2; SyncStore (interface + in-memory + SQLite) → Tasks 3/6; the sync phases (REVOCATIONS/DEFRIENDS/HAVE/MESSAGES/BLOBS) → Task 4; desk loopback gate against the real node → Task 5; module + UI (syncNow/getSyncStats/stats) → Tasks 7/8; on-device → Task 9. Own-identity scope, read-only pull, foreground-trigger, no-decryption all respected (nothing decrypts; phone sends empty msgs/blobs).

**Type consistency:** `SignedMessage`/`SeenSet`/`SyncStore`/`SyncStats`/`SyncResult` names+shapes consistent across Tasks 1-8; `summary()` return type (`Map<String,Map<String,Map<String,Any>>>`) used identically in Task 3 (produce) and Task 4 (HAVE send); `SignedMessageKt.fromDict` signature matches its callers in Tasks 3/4/5; the `Stream` interface (blocking `readExactSync`/`write`/`close`) unified in Task 5's refactor note and used by KotlinSync/KotlinHandshake/SocketStream/TorStream.

**Known judgment calls (flagged, must confirm at implementation):**
- **Kind strings** (`"post"`/`"dm"`) — Task 3 note: confirm against `hearth`'s real `KIND_POST`/`KIND_DM` payload `type` values; the generated vectors are authoritative, update comparisons + vectors to match.
- **`Stream` unification refactor** (Task 5) — `KotlinHandshake.runOverStream` + one blocking `Stream` interface across desk (SocketStream) and phone (TorStream). This touches `KotlinHandshake.kt` (a Brick A file) — the ONE allowed exception, additive (`runOverStream` + the existing `run` delegates to it), flagged here and in Task 5's commit.
- **`sync_loopback_node.py` node API** (Task 5) — confirm `compose_post`/message-count/`put_blob` names against `hearth/node.py`; `expect` must equal what the node actually serves.
- **`java.util.Base64`** not `android.util.Base64` in `KotlinSync` (Task 4 note 2) so it stays JVM-testable.
- **Thin ingest vs. desktop policy** — B.1 verifies signature + dedups; it does NOT run the node's full `ingest_message` acceptance policy (per spec risks). Acceptable for own-identity content from our own node; note for B.2.
