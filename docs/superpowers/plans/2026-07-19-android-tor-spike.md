# Android Tor-Dial Spike Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove on the Moto G20 that our Kotlin/JNI Tor manager bootstraps GP tor-android, SOCKS-dials the real desktop home node's `.onion`, and completes a real HELLO/AUTH handshake — stopping at AUTH, no content sync.

**Architecture:** Three layers, each gated on the desk before anything runs on the phone: (1) a TypeScript wire layer (`wire.ts`/`handshake.ts`) ported byte-for-byte from `hearth/identity.py` + `hearth/transport.py` + `hearth/sync.py` and proven against committed cross-language vectors AND a live loopback session with the real Python node; (2) a Kotlin/JNI TorManager wrapping GP `tor-android` behind `bootstrap()/socksPort()/dial()/suspend()`; (3) a one-button Expo screen. The phone's device cert is minted by a desktop dev helper (`enroll_other`) and hand-carried via `adb push` — pairing crypto real, pairing transport stubbed.

**Tech Stack:** Expo dev build (TypeScript, `create-expo-app@latest`), Expo local native module (Kotlin + NDK r26 JNI shim + CMake), GP `info.guardianproject:tor-android` (C-tor, arm64-v8a), `@noble/curves` for Ed25519, `vitest` + `tsx` for desk-side TS, pytest + repo venv for Python gates.

**Spec:** `docs/superpowers/specs/2026-07-19-android-tor-spike-design.md`

## Global Constraints

- **Commit messages: NO AI/Co-Authored-By trailers** (August's standing rule; README discloses AI involvement instead). Style: `feat(spike): ...` / `docs(spike): ...` lowercase, ASCII hyphens.
- **Never touch the real `%APPDATA%\Kreds` profile from tests or generators.** Only `tools/mint_phone_fixture.py` reads it, run manually by a human, with the desktop app closed.
- **Committed fixtures use throwaway deterministic keys only.** The repo is public. The real phone fixture (`android_tor_spike/spike_phone_fixture.json`) is gitignored and never committed.
- **Do not modify `hearth/` production code.** If a spike task appears to need it, STOP and surface to August.
- **Protocol constants, copied verbatim:** `PROTOCOL = "hearth/v0.2"`, `MAX_FRAME = 16 * 1024 * 1024`, onion dial port `9997` (`ONION_VIRTUAL_PORT`), nonce = 16 random bytes hex (32 chars).
- **Desk gates (Tasks 2–5) must pass before any on-phone work (Tasks 7–10).**
- **Python console output ASCII-only** (Windows cp1252 console).
- **Python runs use the repo venv:** `.venv\Scripts\python.exe`. Node is on PATH (v24). JDK/Android env vars are User-level but NOT inherited by already-open shells — dot-source `android_tor_spike/tools/env.ps1` (created in Task 1) at the start of every PowerShell session that touches Android tooling.
- **Phone:** Moto G20, serial `ZY32DLZQ2N`, arm64-v8a, API 30. Build arm64-v8a only.
- **App package id:** `eu.kreds.torspike`.

## File Structure

```
android_tor_spike/
  NOTES.md                          Task 7: tor-android inspection + JNI-path decision record
  ON_DEVICE_CHECKLIST.md            Task 10: August's run checklist
  SPIKE_REPORT.md                   Task 11: outcome, findings, un-enroll answer
  spike_phone_fixture.json          (gitignored) real-node fixture, minted locally
  fixtures/
    wire_vectors.json               committed cross-language vectors (throwaway keys)
  tools/
    env.ps1                         session env for JDK/SDK/NDK/adb
    make_wire_vectors.py            deterministic vector generator
    mint.py                         mint_fixture(node) library (shared by tests + CLI)
    mint_phone_fixture.py           dev-only CLI against the real profile
  tests/
    conftest.py                     puts tools/ on sys.path
    test_wire_vectors.py            Task 2 gate: vectors current + self-verify
    test_ts_roundtrip.py            Task 4 gate: TS output verified by real hearth code
    test_handshake_desk.py          Task 5 gate: TS handshake vs real node over loopback TCP
  app/                              Expo app (create-expo-app, TypeScript)
    app.json  package.json  tsconfig.json
    App.tsx                         Task 9: one-button proof screen
    src/
      wire.ts                       Task 3: canonical + frames + Ed25519
      handshake.ts                  Task 5: HELLO/AUTH/acceptance-probe
      __tests__/wire.test.ts        Task 3: vitest against wire_vectors.json
    tools/
      node_stream.ts                Task 5: node:net Stream adapter (desk only)
      roundtrip_cli.ts              Task 4: TS side of the roundtrip gate
      handshake_cli.ts              Task 5: desk handshake runner
    modules/tor-manager/            Task 8: Expo local module
      expo-module.config.json
      index.ts                      TS binding + TorStream (implements Stream)
      android/build.gradle
      android/src/main/java/expo/modules/tormanager/TorManagerModule.kt
      android/src/main/java/expo/modules/tormanager/TorRunner.kt
      android/src/main/java/expo/modules/tormanager/ControlPort.kt
      android/src/main/java/expo/modules/tormanager/Socks.kt
      android/src/main/cpp/CMakeLists.txt
      android/src/main/cpp/tor_jni.c
    android/                        prebuild output, COMMITTED (dev build needs it)
```

## The wire contract (verified against source, 2026-07-19)

Everything the TS port must reproduce, with the exact Python it mirrors:

- **canonical** (`hearth/identity.py:41`): `json.dumps(obj, sort_keys=True, separators=(",", ":")).encode()`. Implications for the port: keys sorted by **code point** (not UTF-16 code units), `ensure_ascii=True` escaping (`\uXXXX` lowercase, astral chars as surrogate-pair escapes), compact separators, Python float `repr` for floats (shortest round-trip; integral floats render `"X.0"`).
- **frames** (`hearth/transport.py:17-30`): 4-byte big-endian length prefix + JSON bytes; `MAX_FRAME` 16 MiB. Write side is NOT canonical (no sort) and the reader is key-order-agnostic — the TS writer may reuse canonical serialization (sorted keys are harmless).
- **auth body** (`hearth/sync.py:64`): `canonical({"type": "gossip-auth", "protocol": PROTOCOL, "nonce": nonce_hex})` — strings only, no floats.
- **cert body** (`hearth/identity.py:95`): `canonical({"type": "enrollment", "protocol": PROTOCOL, "identity_pub": ..., "device_pub": ..., "device_name": ..., "enrolled_at": <float>})` — contains a float; JSON.parse cannot distinguish `1234.0` from `1234`, so the TS side wraps schema-known float fields in a `PyFloat` marker.
- **session, phone = initiator** (`hearth/sync.py:421-504`; `_swap` at 411: initiator writes then reads):
  1. phone writes HELLO `{"t":"hello","cert":<cert dict>,"nonce":<32-hex>}`
  2. phone reads node HELLO (same shape); verifies node cert (identity-signed), checks same `identity_pub` as fixture
  3. phone writes AUTH `{"t":"auth","sig":sign_device(auth_body(node_nonce))}`
  4. phone reads node AUTH; verifies sig over `auth_body(my_nonce)` with node cert's `device_pub`
  5. **acceptance probe:** accept/refuse is only observable AFTER the AUTH swap — the node either writes `{"t":"refused"}` or proceeds to REVOCATIONS (where, as responder, it reads before writing). So the phone writes `{"t":"revocations","revs":[]}`, reads one frame: `refused` → REFUSED; `revocations` → ACCEPTED (discard contents, hang up). The spike never reaches DEFRIENDS/HAVE/MESSAGES/BLOBS. The node logs one broken session at its DEFRIENDS read — expected spike noise.
- **own-identity admission** (`hearth/sync.py:472-483` + `hearth/store.py:149`): `is_known(peer_identity)` is true for the node's own identity (`identities` row with `is_self=1` from `HearthNode.create`), and the revoked-device check is skipped for own-identity peers. **Therefore an `enroll_other` cert that was never published to the node's store authenticates.** Task 5 proves this empirically (the desk node's store never sees the phone device); Task 11 records it as the answer to the spec's open un-enroll question — the fixture is a pure local artifact, deleting it is the cleanup, `revoke_device` optional belt-and-braces.
- **fixture minting**: `node.device.enroll_other(device_pub_hex, "spike-phone")` (`hearth/identity.py:396`), onion address = `node.store.get_meta("gossip_addr")` = `"<sid>.onion:9997"` (`hearth/runner.py:109-111`). Real profile default dir `%APPDATA%\Kreds` (`hearth/desktop.py:25`); if `applock.json` exists the node boots locked and needs `node.unlock(credential)`.

---

### Task 1: NDK install + Expo dev-build scaffold

**Files:**
- Create: `android_tor_spike/tools/env.ps1`
- Create: `android_tor_spike/app/` (via `create-expo-app` + `expo prebuild`, committed including `app/android/`)
- Modify: `.gitignore` (repo root — add spike-local artifacts)

**Interfaces:**
- Produces: a building Expo Android app at `android_tor_spike/app` (package `eu.kreds.torspike`), NDK `26.3.11579264` installed, `env.ps1` used by every later Android task.

- [ ] **Step 1: Create the spike dir and `env.ps1`**

`android_tor_spike/tools/env.ps1`:

```powershell
# Session env for the Android spike toolchain. The JDK/SDK were installed
# with User-level env vars (2026-07-19); a shell opened before that -- or
# a Claude tool session -- does not inherit them. Dot-source this first:
#   . .\android_tor_spike\tools\env.ps1
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
$env:ANDROID_HOME = [Environment]::GetEnvironmentVariable('ANDROID_HOME', 'User')
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;" +
            "$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"
```

(Expected values: `JAVA_HOME = C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot`, `ANDROID_HOME = C:\Users\Wong\AppData\Local\Android\Sdk` — verified present in User env 2026-07-19.)

- [ ] **Step 2: Install the NDK**

```powershell
. .\android_tor_spike\tools\env.ps1
sdkmanager --install "ndk;26.3.11579264"
```

Verify: `Test-Path "$env:ANDROID_HOME\ndk\26.3.11579264\source.properties"` → `True`.

- [ ] **Step 3: Scaffold the Expo app**

```powershell
cd android_tor_spike
npx create-expo-app@latest app --template blank-typescript
```

Then edit `app/app.json`: inside `"expo"` set `"name": "KredsTorSpike"`, `"slug": "kreds-tor-spike"`, and add:

```json
"android": { "package": "eu.kreds.torspike" }
```

- [ ] **Step 4: Prebuild the android project and pin the ABI**

```powershell
cd app
npx expo prebuild --platform android
```

In the generated `app/android/app/build.gradle`, inside `defaultConfig { ... }` add:

```gradle
        ndk { abiFilters "arm64-v8a" }
```

Check `app/.gitignore`: if it contains an `/android` (or `android/`) line, delete that line — the prebuild output is committed (dev build, no CNG regeneration).

- [ ] **Step 5: Verify the debug build compiles**

```powershell
. ..\..\android_tor_spike\tools\env.ps1   # if a fresh shell
cd android
.\gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL` (first run downloads Gradle + deps; allow ~5-10 min). If `JAVA_HOME` errors appear, the env.ps1 dot-source was skipped.

- [ ] **Step 6: Gitignore spike-local artifacts**

Append to the repo-root `.gitignore`:

```
android_tor_spike/spike_phone_fixture.json
android_tor_spike/logcat.txt
```

- [ ] **Step 7: Commit**

```powershell
git add .gitignore android_tor_spike
git commit -m "feat(spike): android tor spike scaffold - NDK r26 + expo dev-build app skeleton (eu.kreds.torspike, arm64-v8a)"
```

---

### Task 2: Cross-language wire vectors (Python generator + committed fixture)

**Files:**
- Create: `android_tor_spike/tools/make_wire_vectors.py`
- Create: `android_tor_spike/fixtures/wire_vectors.json` (generated, committed)
- Create: `android_tor_spike/tests/conftest.py`
- Test: `android_tor_spike/tests/test_wire_vectors.py`

**Interfaces:**
- Consumes: `hearth.identity.canonical/PROTOCOL/priv_from_hex/pub_hex/EnrollmentCert`, `hearth.sync._auth_body`, `hearth.transport` framing shape.
- Produces: `fixtures/wire_vectors.json` with top-level keys `canonical_cases` (list of `{name, obj, bytes_hex}` where floats in `obj` appear as `{"__pyfloat__": <n>}` markers), `auth_cases` (`{device_priv, device_pub, nonce, body_hex, sig}`), `cert_cases` (`{cert, body_hex|null, valid}`), `frame_cases` (`{obj, frame_hex}`); and `build_vectors() -> dict` importable by tests. Task 3's TS test consumes the JSON file.

- [ ] **Step 1: Write the failing test**

`android_tor_spike/tests/conftest.py`:

```python
import sys
from pathlib import Path

# Make android_tor_spike/tools importable and ensure the repo root (for
# `import hearth`) is on the path regardless of pytest rootdir.
_SPIKE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(_SPIKE / "tools"))
sys.path.insert(0, str(_SPIKE.parent))
```

`android_tor_spike/tests/test_wire_vectors.py`:

```python
"""Gate: the committed wire vectors are current, deterministic, and
self-verify against the real hearth implementation."""
import json
from pathlib import Path

from hearth.identity import EnrollmentCert, pub_from_hex
from hearth.sync import _auth_body

from make_wire_vectors import FIXTURE_PATH, build_vectors


def test_committed_vectors_are_current():
    committed = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
    assert committed == build_vectors(), (
        "fixtures/wire_vectors.json is stale -- rerun "
        "tools/make_wire_vectors.py and commit")


def test_generator_is_deterministic():
    assert build_vectors() == build_vectors()


def test_auth_vectors_verify_with_hearth_code():
    for case in build_vectors()["auth_cases"]:
        body = _auth_body(case["nonce"])
        assert body.hex() == case["body_hex"]
        pub_from_hex(case["device_pub"]).verify(
            bytes.fromhex(case["sig"]), body)   # raises on mismatch


def test_cert_vectors_verify_with_hearth_code():
    for case in build_vectors()["cert_cases"]:
        cert = EnrollmentCert.from_dict(case["cert"])
        assert cert.verify() is case["valid"]
        if case["body_hex"] is not None:
            assert cert.body().hex() == case["body_hex"]
```

- [ ] **Step 2: Run the test to verify it fails**

```powershell
.venv\Scripts\python.exe -m pytest android_tor_spike\tests\test_wire_vectors.py -v
```

Expected: FAIL (collection error: `No module named 'make_wire_vectors'`).

- [ ] **Step 3: Write the generator**

`android_tor_spike/tools/make_wire_vectors.py`:

```python
"""Deterministic cross-language wire vectors (THROWAWAY keys only -- this
file's output is committed to the public repo). ASCII-only output.

The TS side (app/src/__tests__/wire.test.ts) must reproduce every
bytes_hex from the marker-decoded obj, verify every signature, and parse
every frame. Floats are represented as {"__pyfloat__": n} markers because
JSON cannot round-trip 1234.0 vs 1234 through JavaScript."""
import json
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))  # repo root

from hearth.identity import (EnrollmentCert, PROTOCOL, canonical,
                             priv_from_hex, pub_hex)
from hearth.sync import _auth_body

FIXTURE_PATH = Path(__file__).resolve().parents[1] / "fixtures" / "wire_vectors.json"

IDENTITY_PRIV = priv_from_hex("11" * 32)
DEVICE_PRIV = priv_from_hex("22" * 32)
IDENTITY_PUB = pub_hex(IDENTITY_PRIV.public_key())
DEVICE_PUB = pub_hex(DEVICE_PRIV.public_key())
ENROLLED_AT = 1752900000.123456        # typical time.time(): fractional
ENROLLED_AT_INTEGRAL = 1752900000.0    # the nasty case: renders "...0.0"
NONCES = ["aa" * 16, "0123456789abcdef0123456789abcdef"]


def _mark_floats(obj):
    """Replace every float with a {"__pyfloat__": f} marker, recursively."""
    if isinstance(obj, float):
        return {"__pyfloat__": obj}
    if isinstance(obj, dict):
        return {k: _mark_floats(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_mark_floats(v) for v in obj]
    return obj


def _make_cert(name: str, enrolled_at: float) -> EnrollmentCert:
    body = canonical({
        "type": "enrollment", "protocol": PROTOCOL,
        "identity_pub": IDENTITY_PUB, "device_pub": DEVICE_PUB,
        "device_name": name, "enrolled_at": enrolled_at,
    })
    return EnrollmentCert(IDENTITY_PUB, DEVICE_PUB, name, enrolled_at,
                          IDENTITY_PRIV.sign(body).hex())


def build_vectors() -> dict:
    canonical_objs = [
        ("sorted_keys", {"b": 1, "a": "x"}),
        ("nested", {"outer": {"z": [1, 2, {"k": "v"}], "a": True, "n": None}}),
        ("escapes", {"s": "line\nquote\"back\\slash\ttab"}),
        ("ensure_ascii", {"name": "Åse ☕ \U0001d11e"}),
        ("astral_key_sort", {"￿": 1, "\U00010000": 2}),
        ("float_fractional", {"t": ENROLLED_AT}),
        ("float_integral", {"t": ENROLLED_AT_INTEGRAL}),
        ("empty", {}),
    ]
    canonical_cases = [
        {"name": name, "obj": _mark_floats(obj),
         "bytes_hex": canonical(obj).hex()}
        for name, obj in canonical_objs
    ]

    auth_cases = []
    for nonce in NONCES:
        body = _auth_body(nonce)
        auth_cases.append({
            "device_priv": "22" * 32, "device_pub": DEVICE_PUB,
            "nonce": nonce, "body_hex": body.hex(),
            "sig": DEVICE_PRIV.sign(body).hex(),
        })

    cert_frac = _make_cert("vec-device", ENROLLED_AT)
    cert_int = _make_cert("vec-device", ENROLLED_AT_INTEGRAL)
    tampered = dict(cert_frac.to_dict(), device_name="evil")
    cert_cases = [
        {"cert": cert_frac.to_dict(), "body_hex": cert_frac.body().hex(),
         "valid": True},
        {"cert": cert_int.to_dict(), "body_hex": cert_int.body().hex(),
         "valid": True},
        {"cert": tampered, "body_hex": None, "valid": False},
    ]

    frame_cases = []
    for obj in [{"t": "hello", "nonce": "00ff"},
                {"t": "auth", "sig": "ab" * 64},
                {"t": "refused"}]:
        data = json.dumps(obj, separators=(",", ":")).encode()
        frame_cases.append({
            "obj": obj, "frame_hex": (struct.pack(">I", len(data)) + data).hex(),
        })

    return {
        "canonical_cases": canonical_cases,
        "auth_cases": auth_cases,
        "cert_cases": cert_cases,
        "frame_cases": frame_cases,
    }


def main():
    # NO sort_keys here: the canonical_cases' obj payloads are DELIBERATELY
    # unsorted (the TS canonicalizer must prove it sorts them itself), and a
    # recursive sort_keys would pre-sort them in the committed file.
    # build_vectors() uses fixed dict literals, so insertion order is
    # already deterministic. (Amended per Task 2 review finding.)
    FIXTURE_PATH.parent.mkdir(parents=True, exist_ok=True)
    FIXTURE_PATH.write_text(
        json.dumps(build_vectors(), indent=2) + "\n",
        encoding="utf-8")
    print("wrote", FIXTURE_PATH)


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Generate the fixture and run the tests**

```powershell
.venv\Scripts\python.exe android_tor_spike\tools\make_wire_vectors.py
.venv\Scripts\python.exe -m pytest android_tor_spike\tests\test_wire_vectors.py -v
```

Expected: `wrote ...wire_vectors.json`, then 4 passed.

- [ ] **Step 5: Commit**

```powershell
git add android_tor_spike/tools/make_wire_vectors.py android_tor_spike/fixtures/wire_vectors.json android_tor_spike/tests
git commit -m "feat(spike): cross-language wire vectors - canonical/auth/cert/frame fixtures from the real hearth impl (throwaway keys)"
```

---

### Task 3: `wire.ts` — canonical + frames + Ed25519, proven against the vectors

**Files:**
- Create: `android_tor_spike/app/src/wire.ts`
- Test: `android_tor_spike/app/src/__tests__/wire.test.ts`
- Modify: `android_tor_spike/app/package.json` (deps + test script)

**Interfaces:**
- Consumes: `fixtures/wire_vectors.json` (Task 2 schema, incl. `{"__pyfloat__": n}` markers).
- Produces (exact exports later tasks use):
  - `PROTOCOL: string`, `MAX_FRAME: number`
  - `class PyFloat { constructor(value: number); readonly value: number }`
  - `canonical(obj: Record<string, unknown>): Uint8Array`
  - `dumps(v: unknown): string` (the serializer canonical uses; also used for frame payloads and `roundtrip_cli`)
  - `interface Stream { read(n: number): Promise<Uint8Array>; write(b: Uint8Array): Promise<void>; close(): void }` — `read` returns exactly `n` bytes or throws
  - `readFrame(s: Stream): Promise<any>`, `writeFrame(s: Stream, obj: unknown): Promise<void>`
  - `toHex(b: Uint8Array): string`, `fromHex(h: string): Uint8Array`
  - `signRaw(devicePrivHex: string, data: Uint8Array): string`, `verifyRaw(pubHex: string, sigHex: string, data: Uint8Array): boolean`
  - `interface CertDict { identity_pub: string; device_pub: string; device_name: string; enrolled_at: number; signature: string }`
  - `authBody(nonceHex: string): Uint8Array`, `certBody(c: CertDict): Uint8Array`, `verifyCert(c: CertDict): boolean`

- [ ] **Step 1: Install desk-side dependencies**

```powershell
cd android_tor_spike\app
npm install @noble/curves base64-js
npm install --save-dev vitest tsx
```

Add to `app/package.json` `"scripts"`: `"test": "vitest run"`.

- [ ] **Step 2: Write the failing test**

`android_tor_spike/app/src/__tests__/wire.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import {
  authBody, canonical, certBody, fromHex, PyFloat, readFrame, Stream,
  signRaw, toHex, verifyCert, verifyRaw, writeFrame,
} from "../wire";

const vectors = JSON.parse(readFileSync(
  new URL("../../../fixtures/wire_vectors.json", import.meta.url), "utf8"));

/** Turn {"__pyfloat__": n} markers back into PyFloat instances. */
function revive(v: any): any {
  if (Array.isArray(v)) return v.map(revive);
  if (v && typeof v === "object") {
    const keys = Object.keys(v);
    if (keys.length === 1 && keys[0] === "__pyfloat__") return new PyFloat(v.__pyfloat__);
    return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, revive(x)]));
  }
  return v;
}

/** In-memory Stream over a fixed byte buffer, capturing writes. */
class MemoryStream implements Stream {
  written: number[] = [];
  private pos = 0;
  constructor(private data: Uint8Array = new Uint8Array(0)) {}
  async read(n: number): Promise<Uint8Array> {
    if (this.pos + n > this.data.length) throw new Error("EOF");
    const out = this.data.slice(this.pos, this.pos + n);
    this.pos += n;
    return out;
  }
  async write(b: Uint8Array): Promise<void> { this.written.push(...b); }
  close(): void {}
}

describe("canonical", () => {
  for (const c of vectors.canonical_cases) {
    it(c.name, () => {
      expect(toHex(canonical(revive(c.obj)))).toBe(c.bytes_hex);
    });
  }
  it("rejects bare non-integer numbers", () => {
    expect(() => canonical({ t: 1.5 })).toThrow(/PyFloat/);
  });
});

describe("auth", () => {
  for (const [i, c] of vectors.auth_cases.entries()) {
    it(`vector ${i}`, () => {
      const body = authBody(c.nonce);
      expect(toHex(body)).toBe(c.body_hex);
      expect(signRaw(c.device_priv, body)).toBe(c.sig);   // RFC 8032 is deterministic
      expect(verifyRaw(c.device_pub, c.sig, body)).toBe(true);
      expect(verifyRaw(c.device_pub, c.sig, authBody("00".repeat(16)))).toBe(false);
    });
  }
});

describe("cert", () => {
  for (const [i, c] of vectors.cert_cases.entries()) {
    it(`vector ${i} (valid=${c.valid})`, () => {
      if (c.body_hex !== null) expect(toHex(certBody(c.cert))).toBe(c.body_hex);
      expect(verifyCert(c.cert)).toBe(c.valid);
    });
  }
});

describe("frames", () => {
  for (const [i, c] of vectors.frame_cases.entries()) {
    it(`reads python frame ${i}`, async () => {
      expect(await readFrame(new MemoryStream(fromHex(c.frame_hex)))).toEqual(c.obj);
    });
    it(`self round-trips frame ${i}`, async () => {
      const w = new MemoryStream();
      await writeFrame(w, c.obj);
      expect(await readFrame(new MemoryStream(new Uint8Array(w.written)))).toEqual(c.obj);
    });
  }
});
```

- [ ] **Step 3: Run the test to verify it fails**

```powershell
npm test
```

Expected: FAIL — cannot resolve `../wire`.

- [ ] **Step 4: Write `wire.ts`**

`android_tor_spike/app/src/wire.ts`:

```ts
// Byte-for-byte port of hearth/identity.py canonical(), hearth/transport.py
// frames, and the device-key sign/verify that HELLO/AUTH uses. The
// authoritative cross-language contract is fixtures/wire_vectors.json,
// generated from the real Python implementation.
import { ed25519 } from "@noble/curves/ed25519";

export const PROTOCOL = "hearth/v0.2";
export const MAX_FRAME = 16 * 1024 * 1024;

/** Marks a number that Python's JSON treats as float (e.g. enrolled_at).
 * JSON.parse collapses 1234.0 to 1234; Python does not. Schema-known
 * float fields must be wrapped so serialization renders Python-style. */
export class PyFloat {
  constructor(public readonly value: number) {}
}

export function toHex(b: Uint8Array): string {
  let s = "";
  for (const x of b) s += x.toString(16).padStart(2, "0");
  return s;
}

export function fromHex(h: string): Uint8Array {
  if (h.length % 2 !== 0) throw new Error("odd-length hex");
  const out = new Uint8Array(h.length / 2);
  for (let i = 0; i < out.length; i++) {
    const v = parseInt(h.slice(2 * i, 2 * i + 2), 16);
    if (Number.isNaN(v)) throw new Error("bad hex");
    out[i] = v;
  }
  return out;
}

function pyFloatRepr(n: number): string {
  if (!Number.isFinite(n)) throw new Error("non-finite float unsupported");
  const s = String(n);
  // Python repr() and JS String() both emit the shortest round-trip
  // decimal, so they agree wherever both use plain fixed notation. The
  // notations diverge at extreme magnitudes (Python: 1e+16 / 1e-05; JS:
  // 10000000000000000 / 0.00001). No spike value is near either edge --
  // refuse loudly rather than guess.
  if (/[eE]/.test(s) || Math.abs(n) >= 1e16 || (n !== 0 && Math.abs(n) < 1e-4)) {
    throw new Error(`float out of spike-supported range: ${n}`);
  }
  return Number.isInteger(n) ? `${s}.0` : s;
}

function escapeString(s: string): string {
  // Python json.dumps default ensure_ascii=True: escape every UTF-16
  // unit above 0x7E (astral chars become surrogate-pair escapes, exactly
  // like CPython), lowercase 4-digit \uXXXX, C shorthands for controls.
  let out = '"';
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    const ch = s[i];
    if (ch === '"') out += '\\"';
    else if (ch === "\\") out += "\\\\";
    else if (c === 0x08) out += "\\b";
    else if (c === 0x09) out += "\\t";
    else if (c === 0x0a) out += "\\n";
    else if (c === 0x0c) out += "\\f";
    else if (c === 0x0d) out += "\\r";
    else if (c < 0x20 || c > 0x7e) out += "\\u" + c.toString(16).padStart(4, "0");
    else out += ch;
  }
  return out + '"';
}

function codePointCompare(a: string, b: string): number {
  // Python sorts str by code point; JS default sort compares UTF-16 code
  // units. They disagree when an astral key meets a U+E000..U+FFFF key.
  const A = Array.from(a), B = Array.from(b);
  const n = Math.min(A.length, B.length);
  for (let i = 0; i < n; i++) {
    const d = A[i].codePointAt(0)! - B[i].codePointAt(0)!;
    if (d !== 0) return d;
  }
  return A.length - B.length;
}

export function dumps(v: unknown): string {
  if (v === null || v === undefined) return "null";
  if (v === true) return "true";
  if (v === false) return "false";
  if (typeof v === "string") return escapeString(v);
  if (v instanceof PyFloat) return pyFloatRepr(v.value);
  if (typeof v === "number") {
    if (!Number.isSafeInteger(v)) {
      throw new Error(
        `bare non-integer number in serialization: ${v} -- wrap schema-known floats in PyFloat`);
    }
    return String(v);
  }
  if (Array.isArray(v)) return "[" + v.map(dumps).join(",") + "]";
  if (typeof v === "object") {
    const o = v as Record<string, unknown>;
    const keys = Object.keys(o).sort(codePointCompare);
    return "{" + keys.map((k) => escapeString(k) + ":" + dumps(o[k])).join(",") + "}";
  }
  throw new Error(`unsupported type in serialization: ${typeof v}`);
}

/** hearth.identity.canonical: json.dumps(obj, sort_keys=True,
 *  separators=(",", ":")).encode() */
export function canonical(obj: Record<string, unknown>): Uint8Array {
  // dumps() output is pure ASCII (ensure_ascii), so per-char codes are bytes.
  const s = dumps(obj);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

export interface Stream {
  /** Return exactly n bytes or throw. */
  read(n: number): Promise<Uint8Array>;
  write(b: Uint8Array): Promise<void>;
  close(): void;
}

/** hearth.transport.write_frame: 4-byte big-endian length + JSON bytes.
 *  The Python reader is key-order-agnostic, so reusing the sorted
 *  serializer here is safe -- and it keeps PyFloat handling in one place. */
export async function writeFrame(s: Stream, obj: unknown): Promise<void> {
  const payload = canonical(obj as Record<string, unknown>);
  if (payload.length > MAX_FRAME) throw new Error("frame too large");
  const out = new Uint8Array(4 + payload.length);
  new DataView(out.buffer).setUint32(0, payload.length, false);
  out.set(payload, 4);
  await s.write(out);
}

export async function readFrame(s: Stream): Promise<any> {
  const header = await s.read(4);
  const n = new DataView(header.buffer, header.byteOffset, 4).getUint32(0, false);
  if (n > MAX_FRAME) throw new Error("frame too large");
  const body = await s.read(n);
  let text = "";
  // TextDecoder is unavailable on RN Hermes; frames are ASCII-safe on the
  // read path only after JSON.parse -- decode UTF-8 manually via escape
  // trick is overkill: the node always sends ensure_ascii JSON (Python
  // json.dumps default), so bytes are pure ASCII.
  for (const b of body) {
    if (b > 0x7e) throw new Error("non-ascii frame byte (unexpected: python sends ensure_ascii)");
    text += String.fromCharCode(b);
  }
  return JSON.parse(text);
}

export function signRaw(devicePrivHex: string, data: Uint8Array): string {
  return toHex(ed25519.sign(data, fromHex(devicePrivHex)));
}

export function verifyRaw(pubHex: string, sigHex: string, data: Uint8Array): boolean {
  try {
    return ed25519.verify(fromHex(sigHex), data, fromHex(pubHex));
  } catch {
    return false;
  }
}

export interface CertDict {
  identity_pub: string;
  device_pub: string;
  device_name: string;
  enrolled_at: number;
  signature: string;
}

/** hearth.sync._auth_body */
export function authBody(nonceHex: string): Uint8Array {
  return canonical({ type: "gossip-auth", protocol: PROTOCOL, nonce: nonceHex });
}

/** hearth.identity.EnrollmentCert.body -- enrolled_at is float-typed. */
export function certBody(c: CertDict): Uint8Array {
  return canonical({
    type: "enrollment", protocol: PROTOCOL,
    identity_pub: c.identity_pub, device_pub: c.device_pub,
    device_name: c.device_name, enrolled_at: new PyFloat(c.enrolled_at),
  });
}

/** hearth.identity.EnrollmentCert.verify */
export function verifyCert(c: CertDict): boolean {
  return verifyRaw(c.identity_pub, c.signature, certBody(c));
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```powershell
npm test
```

Expected: all vector-derived tests PASS. If a `canonical` case fails, the port has drifted from Python — fix `wire.ts`, never the vectors.

- [ ] **Step 6: Commit**

```powershell
git add android_tor_spike/app/src android_tor_spike/app/package.json android_tor_spike/app/package-lock.json
git commit -m "feat(spike): wire.ts - canonical/frames/ed25519 port, green against the python-generated vectors"
```

---

### Task 4: TS→Python roundtrip gate (`roundtrip_cli.ts` + `mint.py`)

**Files:**
- Create: `android_tor_spike/tools/mint.py`
- Create: `android_tor_spike/app/tools/roundtrip_cli.ts`
- Test: `android_tor_spike/tests/test_ts_roundtrip.py`

**Interfaces:**
- Consumes: `wire.ts` exports (`authBody`, `signRaw`, `verifyCert`, `dumps`, `PyFloat`, `toHex`, `CertDict`).
- Produces:
  - `mint.mint_fixture(node, device_name="spike-phone") -> dict` with keys `device_priv`, `device_pub`, `cert` (EnrollmentCert dict), `onion_addr` (may be `None` on a fresh test node) — used by Tasks 5 and 6.
  - `roundtrip_cli.ts <in.json> <out.json>`: input `{cert, device_priv, nonce}`; output `{auth_body_hex, sig, cert_verifies, cert_json}` where `cert_json` is the TS re-serialization of the cert (with `enrolled_at` as `PyFloat`).

- [ ] **Step 1: Write `mint.py`**

`android_tor_spike/tools/mint.py`:

```python
"""Mint a phone device fixture: a REAL identity-signed EnrollmentCert for
a freshly generated device keypair. Pairing crypto real, transport
stubbed (the fixture travels by adb push, not the pairing ceremony)."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))  # repo root

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from hearth.identity import priv_hex, pub_hex


def mint_fixture(node, device_name: str = "spike-phone") -> dict:
    priv = Ed25519PrivateKey.generate()
    device_pub = pub_hex(priv.public_key())
    cert = node.device.enroll_other(device_pub, device_name)
    return {
        "device_priv": priv_hex(priv),
        "device_pub": device_pub,
        "cert": cert.to_dict(),
        "onion_addr": node.store.get_meta("gossip_addr"),
    }
```

- [ ] **Step 2: Write the failing test**

`android_tor_spike/tests/test_ts_roundtrip.py`:

```python
"""Gate: bytes produced by the TS wire layer verify under the REAL hearth
implementation (the reverse direction of the committed vectors)."""
import json
import os
import shutil
import subprocess
from pathlib import Path

from hearth.identity import EnrollmentCert, pub_from_hex
from hearth.node import HearthNode
from hearth.sync import _auth_body

import mint

APP_DIR = Path(__file__).resolve().parents[1] / "app"


def _npx() -> str:
    npx = shutil.which("npx") or shutil.which("npx.cmd")
    assert npx, "npx not on PATH"
    return npx


def test_ts_output_verifies_in_python(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Desk", "desk")
    fx = mint.mint_fixture(node)
    nonce = os.urandom(16).hex()
    inp, outp = tmp_path / "in.json", tmp_path / "out.json"
    inp.write_text(json.dumps(
        {"cert": fx["cert"], "device_priv": fx["device_priv"], "nonce": nonce}))

    r = subprocess.run(
        [_npx(), "tsx", "tools/roundtrip_cli.ts", str(inp), str(outp)],
        cwd=APP_DIR, capture_output=True, text=True, timeout=120)
    assert r.returncode == 0, r.stderr

    out = json.loads(outp.read_text())
    # TS verified the python-minted cert
    assert out["cert_verifies"] is True
    # TS canonical auth body == python's, and the TS signature verifies
    # under the real hearth device key
    assert bytes.fromhex(out["auth_body_hex"]) == _auth_body(nonce)
    pub_from_hex(fx["device_pub"]).verify(
        bytes.fromhex(out["sig"]), _auth_body(nonce))   # raises on mismatch
    # the TS re-serialized cert still verifies after a python json.loads
    # round trip -- this is exactly the HELLO path the node will exercise
    assert EnrollmentCert.from_dict(json.loads(out["cert_json"])).verify()
```

- [ ] **Step 3: Run the test to verify it fails**

```powershell
.venv\Scripts\python.exe -m pytest android_tor_spike\tests\test_ts_roundtrip.py -v
```

Expected: FAIL — `tools/roundtrip_cli.ts` does not exist (nonzero returncode, stderr shows tsx resolve error).

- [ ] **Step 4: Write `roundtrip_cli.ts`**

`android_tor_spike/app/tools/roundtrip_cli.ts`:

```ts
// Desk-only: consumes {cert, device_priv, nonce}, emits the TS-computed
// auth body/signature and a TS re-serialization of the cert, for the
// python side of the roundtrip gate to verify with real hearth code.
import { readFileSync, writeFileSync } from "node:fs";
import {
  authBody, CertDict, dumps, PyFloat, signRaw, toHex, verifyCert,
} from "../src/wire";

const [inPath, outPath] = process.argv.slice(2);
const inp = JSON.parse(readFileSync(inPath, "utf8")) as {
  cert: CertDict; device_priv: string; nonce: string;
};

const body = authBody(inp.nonce);
const certJson = dumps({ ...inp.cert, enrolled_at: new PyFloat(inp.cert.enrolled_at) });

writeFileSync(outPath, JSON.stringify({
  auth_body_hex: toHex(body),
  sig: signRaw(inp.device_priv, body),
  cert_verifies: verifyCert(inp.cert),
  cert_json: certJson,
}));
```

- [ ] **Step 5: Run the test to verify it passes**

```powershell
.venv\Scripts\python.exe -m pytest android_tor_spike\tests\test_ts_roundtrip.py -v
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add android_tor_spike/tools/mint.py android_tor_spike/app/tools/roundtrip_cli.ts android_tor_spike/tests/test_ts_roundtrip.py
git commit -m "feat(spike): ts-to-python roundtrip gate - TS auth sigs and cert reserialization verify under real hearth code"
```

---

### Task 5: `handshake.ts` + desk gate against the real node over loopback TCP

**Files:**
- Create: `android_tor_spike/app/src/handshake.ts`
- Create: `android_tor_spike/app/tools/node_stream.ts`
- Create: `android_tor_spike/app/tools/handshake_cli.ts`
- Test: `android_tor_spike/tests/test_handshake_desk.py`

**Interfaces:**
- Consumes: `wire.ts` exports; `mint.mint_fixture` (Task 4); `hearth.sync.SyncService(node)` / `await sync.start("127.0.0.1", 0) -> port` / `await sync.stop()`.
- Produces:
  - `interface Fixture { device_priv: string; device_pub: string; cert: CertDict; onion_addr: string }`
  - `type HandshakeResult = { status: "accepted" } | { status: "refused" } | { status: "failed"; stage: string; reason: string }`
  - `handshake(stream: Stream, fixture: Fixture, randomHex16: () => string): Promise<HandshakeResult>`
  - `handshake_cli.ts <fixture.json> [host:port]` printing exactly `RESULT accepted` / `RESULT refused` / `RESULT failed <stage>: <reason>`; exit code 0 only for accepted.
  - `connectTcp(host: string, port: number): Promise<Stream>` (desk only, `node:net`).

- [ ] **Step 1: Write the failing test**

`android_tor_spike/tests/test_handshake_desk.py`:

```python
"""Gate: the TS handshake completes real HELLO/AUTH against the REAL
python node (SyncService._session) over loopback TCP -- isolating any
future on-phone failure to the Tor/Android layers.

Also settles the spec's open un-enroll question: the phone cert below is
NEVER published to the node's store, and AUTH still succeeds via the
own-identity path (sync.py:472 is_known(own identity) is true; :479
skips the revocation check for own-identity peers). The fixture is a
pure local artifact; deleting it is the cleanup."""
import asyncio
import json
import shutil
import time
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from hearth.identity import EnrollmentCert, PROTOCOL, canonical, priv_hex, pub_hex
from hearth.node import HearthNode
from hearth.sync import SyncService

import mint

APP_DIR = Path(__file__).resolve().parents[1] / "app"


def _npx() -> str:
    npx = shutil.which("npx") or shutil.which("npx.cmd")
    assert npx, "npx not on PATH"
    return npx


async def _run_cli(fixture_path: Path) -> str:
    proc = await asyncio.create_subprocess_exec(
        _npx(), "tsx", "tools/handshake_cli.ts", str(fixture_path),
        cwd=APP_DIR, stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE)
    out, err = await asyncio.wait_for(proc.communicate(), timeout=120)
    return out.decode(errors="replace") + err.decode(errors="replace")


def test_real_device_cert_is_accepted(tmp_path):
    async def main():
        node = HearthNode.create(tmp_path / "n", "Desk", "desk")
        sync = SyncService(node)
        port = await sync.start("127.0.0.1", 0)
        try:
            fx = mint.mint_fixture(node)
            fx["onion_addr"] = f"127.0.0.1:{port}"
            p = tmp_path / "fixture.json"
            p.write_text(json.dumps(fx))
            output = await _run_cli(p)
            assert "RESULT accepted" in output, output
        finally:
            await sync.stop()
    asyncio.run(main())


def test_foreign_identity_cert_is_refused(tmp_path):
    async def main():
        node = HearthNode.create(tmp_path / "n", "Desk", "desk")
        sync = SyncService(node)
        port = await sync.start("127.0.0.1", 0)
        try:
            # a cryptographically VALID cert from an identity the node has
            # never heard of: passes cert verify + AUTH, then is refused
            # at the is_known gate -- proving the acceptance probe can
            # tell refusal from success.
            fid = Ed25519PrivateKey.generate()
            dev = Ed25519PrivateKey.generate()
            fpub, dpub = pub_hex(fid.public_key()), pub_hex(dev.public_key())
            ts = time.time()
            body = canonical({
                "type": "enrollment", "protocol": PROTOCOL,
                "identity_pub": fpub, "device_pub": dpub,
                "device_name": "stranger", "enrolled_at": ts})
            cert = EnrollmentCert(fpub, dpub, "stranger", ts,
                                  fid.sign(body).hex())
            fx = {"device_priv": priv_hex(dev), "device_pub": dpub,
                  "cert": cert.to_dict(),
                  "onion_addr": f"127.0.0.1:{port}"}
            p = tmp_path / "fixture.json"
            p.write_text(json.dumps(fx))
            output = await _run_cli(p)
            assert "RESULT refused" in output, output
        finally:
            await sync.stop()
    asyncio.run(main())
```

- [ ] **Step 2: Run the test to verify it fails**

```powershell
.venv\Scripts\python.exe -m pytest android_tor_spike\tests\test_handshake_desk.py -v
```

Expected: FAIL — tsx cannot resolve `tools/handshake_cli.ts`.

- [ ] **Step 3: Write `handshake.ts`**

`android_tor_spike/app/src/handshake.ts`:

```ts
// Mirror of hearth/sync.py _session, phone = initiator, stopping at the
// earliest unambiguous accept/refuse signal after AUTH. Runs identically
// over a node:net socket (desk gate) and TorManager's SOCKS stream (phone).
import {
  authBody, CertDict, PyFloat, readFrame, signRaw, Stream, verifyCert,
  verifyRaw, writeFrame,
} from "./wire";

export interface Fixture {
  device_priv: string;
  device_pub: string;
  cert: CertDict;
  onion_addr: string;
}

export type HandshakeResult =
  | { status: "accepted" }
  | { status: "refused" }
  | { status: "failed"; stage: string; reason: string };

function failed(stage: string, reason: string): HandshakeResult {
  return { status: "failed", stage, reason };
}

export async function handshake(
  stream: Stream,
  fixture: Fixture,
  randomHex16: () => string,   // 16 random bytes as 32 hex chars
): Promise<HandshakeResult> {
  try {
    // -- HELLO (initiator writes first; node's _on_conn reads it, then
    //    _session sends the node's own hello) --
    const myNonce = randomHex16();
    await writeFrame(stream, {
      t: "hello",
      // enrolled_at is float-typed in python; PyFloat keeps 1234.0 as
      // "1234.0" through our serializer (see wire.ts)
      cert: { ...fixture.cert, enrolled_at: new PyFloat(fixture.cert.enrolled_at) },
      nonce: myNonce,
    });
    const peerHello = await readFrame(stream);
    if (peerHello.t !== "hello") return failed("hello", `unexpected frame t=${peerHello.t}`);
    const peerCert = peerHello.cert as CertDict;
    if (!verifyCert(peerCert)) return failed("hello", "node cert failed verification");
    if (peerCert.identity_pub !== fixture.cert.identity_pub) {
      return failed("hello", "node identity differs from fixture identity");
    }

    // -- AUTH (mutual device-key proof, sync.py:451-458) --
    await writeFrame(stream, {
      t: "auth",
      sig: signRaw(fixture.device_priv, authBody(peerHello.nonce)),
    });
    const peerAuth = await readFrame(stream);
    if (peerAuth.t !== "auth") return failed("auth", `unexpected frame t=${peerAuth.t}`);
    if (!verifyRaw(peerCert.device_pub, peerAuth.sig, authBody(myNonce))) {
      return failed("auth", "node failed device-key proof");
    }

    // -- acceptance probe --
    // The node reveals accept/refuse only AFTER the AUTH swap: it either
    // writes {"t":"refused"} (sync.py:472-483) or proceeds to the
    // REVOCATIONS phase, where as responder it reads our frame before
    // writing its own (_swap, sync.py:411). So: send an empty
    // revocations frame, read one frame, hang up. We discard the node's
    // revocation list and never reach DEFRIENDS/HAVE/MESSAGES/BLOBS --
    // the node will log one broken session at its DEFRIENDS read, which
    // is expected spike noise.
    await writeFrame(stream, { t: "revocations", revs: [] });
    const verdict = await readFrame(stream);
    if (verdict.t === "refused") return { status: "refused" };
    if (verdict.t === "revocations") return { status: "accepted" };
    return failed("probe", `unexpected frame t=${verdict.t}`);
  } catch (e) {
    return failed("io", String(e));
  } finally {
    stream.close();
  }
}

export function splitAddr(addr: string): [string, number] {
  const i = addr.lastIndexOf(":");
  if (i < 0) throw new Error(`address has no port: ${addr}`);
  return [addr.slice(0, i), Number(addr.slice(i + 1))];
}
```

- [ ] **Step 4: Write the desk TCP stream + CLI**

`android_tor_spike/app/tools/node_stream.ts`:

```ts
// Desk-only Stream over node:net -- the phone uses TorStream instead.
// Contract: read(n) resolves with exactly n bytes as soon as they exist,
// rejects on EOF/error; write resolves on flush; close destroys.
import * as net from "node:net";
import { Stream } from "../src/wire";

export function connectTcp(host: string, port: number): Promise<Stream> {
  return new Promise((resolve, reject) => {
    const sock = net.createConnection({ host, port });
    let buffer = Buffer.alloc(0);
    let ended = false;
    let failure: Error | null = null;
    let waiting: { n: number; ok: (b: Uint8Array) => void; err: (e: Error) => void } | null = null;

    // Single delivery path: called on every state change (data/end/error
    // and at the start of each read).
    const pump = () => {
      if (!waiting) return;
      const w = waiting;
      if (buffer.length >= w.n) {
        waiting = null;
        const out = new Uint8Array(buffer.subarray(0, w.n));
        buffer = buffer.subarray(w.n);
        w.ok(out);
      } else if (failure) {
        waiting = null;
        w.err(failure);
      } else if (ended) {
        waiting = null;
        w.err(new Error(`EOF: wanted ${w.n} bytes, have ${buffer.length}`));
      }
    };

    sock.on("data", (c) => { buffer = Buffer.concat([buffer, c]); pump(); });
    sock.on("end", () => { ended = true; pump(); });
    sock.on("error", (e) => { failure = e; pump(); reject(e); });
    sock.on("connect", () => resolve({
      read(n: number): Promise<Uint8Array> {
        return new Promise((ok, err) => {
          if (waiting) { err(new Error("concurrent read")); return; }
          waiting = { n, ok, err };
          pump();
        });
      },
      write(b: Uint8Array): Promise<void> {
        return new Promise((ok, err) =>
          sock.write(Buffer.from(b), (e) => (e ? err(e) : ok())));
      },
      close(): void { sock.destroy(); },
    }));
  });
}
```

(The double-settle on a pre-connect error is harmless — `reject` after `resolve` is a no-op and vice versa. The handshake protocol is strictly sequential, so the single-`waiting` guard never trips in practice.)

`android_tor_spike/app/tools/handshake_cli.ts`:

```ts
// Desk handshake runner: RESULT accepted|refused|failed <stage>: <reason>
import { readFileSync } from "node:fs";
import { randomBytes } from "node:crypto";
import { Fixture, handshake, splitAddr } from "../src/handshake";
import { connectTcp } from "./node_stream";

const [fixturePath, addrOverride] = process.argv.slice(2);
const fixture = JSON.parse(readFileSync(fixturePath, "utf8")) as Fixture;
const [host, port] = splitAddr(addrOverride ?? fixture.onion_addr);

const stream = await connectTcp(host, port);
const result = await handshake(stream, fixture, () => randomBytes(16).toString("hex"));
if (result.status === "failed") {
  console.log(`RESULT failed ${result.stage}: ${result.reason}`);
} else {
  console.log(`RESULT ${result.status}`);
}
process.exit(result.status === "accepted" ? 0 : 1);
```

- [ ] **Step 5: Run the desk gate**

```powershell
.venv\Scripts\python.exe -m pytest android_tor_spike\tests\test_handshake_desk.py -v
```

Expected: both tests PASS — the TS client completes real HELLO/AUTH against the real node, and a valid-but-foreign cert reads as `refused`. This empirically settles the cert-publish question (accepted case: phone device never entered the node's store).

- [ ] **Step 6: Run the full spike test suite + TS tests together**

```powershell
.venv\Scripts\python.exe -m pytest android_tor_spike\tests -v
cd android_tor_spike\app; npm test; cd ..\..
```

Expected: all green. **This is the DESK GATE — do not proceed to Tasks 7+ until it holds.**

- [ ] **Step 7: Commit**

```powershell
git add android_tor_spike/app/src/handshake.ts android_tor_spike/app/tools android_tor_spike/tests/test_handshake_desk.py
git commit -m "feat(spike): handshake.ts green against the real node over loopback - HELLO/AUTH + acceptance probe; unpublished own-identity cert accepted, foreign cert refused"
```

---

### Task 6: `mint_phone_fixture.py` — the real-node dev helper

**Files:**
- Create: `android_tor_spike/tools/mint_phone_fixture.py`
- Test: `android_tor_spike/tests/test_mint_cli.py`

**Interfaces:**
- Consumes: `mint.mint_fixture` (Task 4); `HearthNode(data_dir)`, `node.unlock(credential)`, `node.applock_enabled`.
- Produces: CLI writing the Task 5 `Fixture` JSON shape to `android_tor_spike/spike_phone_fixture.json` (gitignored). Task 10 runs it against the real profile.

- [ ] **Step 1: Write the failing test**

`android_tor_spike/tests/test_mint_cli.py`:

```python
"""The CLI is exercised against a THROWAWAY node profile only -- never
the real %APPDATA%/Kreds (Global Constraints)."""
import json
import subprocess
import sys
from pathlib import Path

from hearth.identity import EnrollmentCert
from hearth.node import HearthNode

CLI = Path(__file__).resolve().parents[1] / "tools" / "mint_phone_fixture.py"


def test_cli_mints_valid_fixture(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Desk", "desk")
    node.store.set_meta("gossip_addr", "abcdefexample.onion:9997")
    del node   # release the sqlite handle before the CLI reopens it

    out = tmp_path / "fixture.json"
    r = subprocess.run(
        [sys.executable, str(CLI), "--data-dir", str(tmp_path / "n"),
         "--out", str(out)],
        capture_output=True, text=True, timeout=60)
    assert r.returncode == 0, r.stderr

    fx = json.loads(out.read_text())
    assert fx["onion_addr"] == "abcdefexample.onion:9997"
    cert = EnrollmentCert.from_dict(fx["cert"])
    assert cert.verify()
    assert cert.device_pub == fx["device_pub"]
    assert cert.device_name == "spike-phone"


def test_cli_refuses_without_onion_addr(tmp_path):
    node = HearthNode.create(tmp_path / "n", "Desk", "desk")
    del node
    r = subprocess.run(
        [sys.executable, str(CLI), "--data-dir", str(tmp_path / "n"),
         "--out", str(tmp_path / "fixture.json")],
        capture_output=True, text=True, timeout=60)
    assert r.returncode != 0
    assert "no gossip_addr" in (r.stdout + r.stderr)
```

- [ ] **Step 2: Run the test to verify it fails**

```powershell
.venv\Scripts\python.exe -m pytest android_tor_spike\tests\test_mint_cli.py -v
```

Expected: FAIL — CLI file does not exist.

- [ ] **Step 3: Write the CLI**

`android_tor_spike/tools/mint_phone_fixture.py`:

```python
"""Dev-only helper: mint the spike phone's {device_priv, cert, onion}
fixture from the REAL desktop node profile. NOT shipped in any client.

Run with the desktop Kreds app CLOSED (single sqlite writer), from the
repo root:

    .venv\\Scripts\\python.exe android_tor_spike\\tools\\mint_phone_fixture.py

Safety properties (spec 2026-07-19): the identity private key never
leaves this machine -- the phone receives only a fresh device keypair +
an identity-SIGNED cert. The cert is never published to the node's
store; AUTH succeeds via the own-identity path regardless (proven by
tests/test_handshake_desk.py). Cleanup = delete the fixture file from
the phone and this repo. node.revoke_device(device_pub) remains
available as belt-and-braces."""
import argparse
import getpass
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))  # repo root

from hearth.node import HearthNode

from mint import mint_fixture

DEFAULT_DATA_DIR = Path(os.environ.get("APPDATA", str(Path.home()))) / "Kreds"
DEFAULT_OUT = Path(__file__).resolve().parents[1] / "spike_phone_fixture.json"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--data-dir", type=Path, default=DEFAULT_DATA_DIR)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--device-name", default="spike-phone")
    args = ap.parse_args()

    node = HearthNode(args.data_dir)
    if node.applock_enabled:
        node.unlock(getpass.getpass("app-lock credential: "))
    fx = mint_fixture(node, args.device_name)
    if not fx["onion_addr"] or ".onion" not in fx["onion_addr"]:
        print("ERROR: no gossip_addr onion in this profile -- run the "
              "desktop app once with Tor so the onion is published, "
              "then retry (got: %r)" % (fx["onion_addr"],))
        return 1

    args.out.write_text(json.dumps(fx, indent=2))
    print("wrote", args.out)
    print("device_pub:", fx["device_pub"])
    print("onion:", fx["onion_addr"])
    print("REMINDER: this file holds the phone's device private key. "
          "It is gitignored; do not commit or share it.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

Note: `test_cli_refuses_without_onion_addr` covers a fresh node whose `gossip_addr` is unset (`get_meta` returns None) — the error message must contain the exact string `no gossip_addr`.

- [ ] **Step 4: Run the tests to verify they pass**

```powershell
.venv\Scripts\python.exe -m pytest android_tor_spike\tests\test_mint_cli.py -v
```

Expected: both PASS.

- [ ] **Step 5: Commit**

```powershell
git add android_tor_spike/tools/mint_phone_fixture.py android_tor_spike/tests/test_mint_cli.py
git commit -m "feat(spike): mint_phone_fixture CLI - real-profile device cert + onion, applock-aware, output gitignored"
```

---

### Task 7: Vendor GP tor-android + verify the JNI surface

**Files:**
- Modify: `android_tor_spike/app/android/app/build.gradle` (dependency)
- Create: `android_tor_spike/NOTES.md` (decision record)

**Interfaces:**
- Consumes: Maven Central `info.guardianproject:tor-android` (exact version discovered in Step 1 — GP shipped releases through May 2026; expect a 0.4.8.x-series artifact).
- Produces: a resolving gradle dependency + a written GO/NO-GO on the primary JNI path (dlopen `libtor.so`, call `tor_api.h` entry points: `tor_main_configuration_new`, `tor_main_configuration_set_command_line`, `tor_run_main`, `tor_main_configuration_free`) that Task 8's `tor_jni.c` depends on.

- [ ] **Step 1: Discover the current artifact version**

```powershell
curl.exe -s "https://search.maven.org/solrsearch/select?q=g:info.guardianproject+AND+a:tor-android&rows=5&wt=json"
```

Record the `latestVersion` in `NOTES.md`. If Maven Central search is unreachable, check https://repo1.maven.org/maven2/info/guardianproject/tor-android/ directly.

- [ ] **Step 2: Add the dependency**

In `android_tor_spike/app/android/app/build.gradle`, inside `dependencies { ... }`:

```gradle
    implementation "info.guardianproject:tor-android:<VERSION FROM STEP 1>"
```

Then verify it resolves and the app still builds:

```powershell
. .\android_tor_spike\tools\env.ps1
cd android_tor_spike\app\android
.\gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Inspect the AAR's native library**

```powershell
# find the AAR in the gradle cache
Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\info.guardianproject\tor-android" -Recurse -Filter *.aar
# extract to scratch and list the arm64 lib's exported symbols
Expand-Archive <aar path> -DestinationPath $env:TEMP\tor-aar
& "$env:ANDROID_HOME\ndk\26.3.11579264\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-nm.exe" -D --defined-only "$env:TEMP\tor-aar\jni\arm64-v8a\libtor.so" | Select-String "tor_"
```

Confirm and record in `NOTES.md`:
1. `jni/arm64-v8a/libtor.so` exists (arm64 supported).
2. The four `tor_api.h` symbols are exported: `tor_main_configuration_new`, `tor_main_configuration_set_command_line`, `tor_run_main`, `tor_main_configuration_free`.
3. The AAR's `AndroidManifest.xml` minSdk is ≤ 30.
4. W^X: tor ships as `libtor.so` inside the APK's native-lib area (read-only, JNI-loaded) — no `exec()` from writable storage, confirming the spec's packaging assumption.

- [ ] **Step 4: Decision checkpoint**

- If all four symbols are present → record **GO: primary path (own JNI shim onto tor_api)** in `NOTES.md` and proceed to Task 8 as written.
- If they are NOT present (symbols hidden, or the JNI surface is only the `org.torproject.jni.TorService` class) → **STOP. Surface to August** with the actual symbol list; Task 8 must be amended to wrap `TorService` (bind the service, watch its status broadcasts, same four-method TorManager interface — the spec explicitly allows this: "Manager-internal, interface unchanged either way"). Do not improvise the amendment inside this task.

`android_tor_spike/NOTES.md` skeleton (fill every `<...>` with findings — no placeholders may survive the task):

```markdown
# tor-android vendoring notes (Task 7, 2026-07-XX)

- artifact: info.guardianproject:tor-android:<version>
- arm64-v8a libtor.so: <present/absent>, <size>
- tor_api symbols (llvm-nm -D): <paste the four lines or "ABSENT">
- minSdk: <n>
- W^X packaging: <confirmed libtor.so in native-lib dir / deviation found>
- DECISION: <GO primary JNI path / STOPPED - TorService fallback needed>
```

- [ ] **Step 5: Commit**

```powershell
git add android_tor_spike/app/android/app/build.gradle android_tor_spike/NOTES.md
git commit -m "feat(spike): vendor guardianproject tor-android, confirm arm64 libtor.so exports the tor_api surface"
```

---

### Task 8: TorManager — Expo local module (Kotlin + JNI shim)

**Files:**
- Create: `android_tor_spike/app/modules/tor-manager/expo-module.config.json`
- Create: `android_tor_spike/app/modules/tor-manager/index.ts`
- Create: `android_tor_spike/app/modules/tor-manager/android/build.gradle`
- Create: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/TorManagerModule.kt`
- Create: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/TorRunner.kt`
- Create: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/ControlPort.kt`
- Create: `android_tor_spike/app/modules/tor-manager/android/src/main/java/expo/modules/tormanager/Socks.kt`
- Create: `android_tor_spike/app/modules/tor-manager/android/src/main/cpp/CMakeLists.txt`
- Create: `android_tor_spike/app/modules/tor-manager/android/src/main/cpp/tor_jni.c`

**Interfaces:**
- Consumes: Task 7's confirmed `tor_api` symbols; `Stream` from `wire.ts`.
- Produces (the narrow manager interface from the spec — `index.ts` exports):
  - `bootstrap(): Promise<number>` (resolves with the SOCKS port at 100%)
  - `onProgress(cb: (p: number) => void): () => void` (unsubscribe fn)
  - `socksPort(): number`
  - `dial(host: string, port: number): Promise<TorStream>` — `class TorStream implements Stream`
  - `suspendTor(): Promise<void>`
  - `fixtureDir: string` (the app's external files dir — the `adb push` target)

- [ ] **Step 1: Scaffold the local module**

```powershell
cd android_tor_spike\app
npx create-expo-module@latest --local tor-manager
```

Accept defaults. Delete the generated iOS folder (`modules/tor-manager/ios/`) and the generated example/view files; set `modules/tor-manager/expo-module.config.json` to:

```json
{
  "platforms": ["android"],
  "android": {
    "modules": ["expo.modules.tormanager.TorManagerModule"]
  }
}
```

Rename the generated Kotlin package dirs to `expo/modules/tormanager/`.

- [ ] **Step 2: Write the native build config**

`modules/tor-manager/android/build.gradle` — keep the generated `apply`/`android` boilerplate from create-expo-module, and add inside `android { ... }`:

```gradle
  namespace = "expo.modules.tormanager"
  ndkVersion "26.3.11579264"
  defaultConfig {
    minSdkVersion 24
    ndk { abiFilters "arm64-v8a" }
  }
  externalNativeBuild {
    cmake { path "src/main/cpp/CMakeLists.txt" }
  }
```

and in `dependencies { ... }` (tor-android must also be on the module's compile path so `libtor.so` ships; keep the app-level dep from Task 7 too — gradle dedupes):

```gradle
  implementation "info.guardianproject:tor-android:<VERSION from NOTES.md>"
```

`modules/tor-manager/android/src/main/cpp/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22)
project(torjni C)
add_library(torjni SHARED tor_jni.c)
```

- [ ] **Step 3: Write the JNI shim**

`modules/tor-manager/android/src/main/cpp/tor_jni.c`:

```c
/* Thin JNI shim onto GP libtor.so's tor_api entry points (verified
 * exported in Task 7 / NOTES.md). dlopen at call time resolves within
 * the APK's classloader namespace, so this shim has no link-time
 * dependency on libtor.so. Returns tor's exit code, or:
 *   -100 dlopen failed   -101 symbol missing   -102 set_command_line failed */
#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>

typedef struct tor_main_configuration_t tor_main_configuration_t;

JNIEXPORT jint JNICALL
Java_expo_modules_tormanager_TorRunner_nativeRunTor(JNIEnv *env, jobject thiz,
                                                    jobjectArray jargs) {
    void *h = dlopen("libtor.so", RTLD_NOW);
    if (!h) return -100;
    tor_main_configuration_t *(*cfg_new)(void) =
        (tor_main_configuration_t *(*)(void))dlsym(h, "tor_main_configuration_new");
    int (*cfg_set)(tor_main_configuration_t *, int, char **) =
        (int (*)(tor_main_configuration_t *, int, char **))
            dlsym(h, "tor_main_configuration_set_command_line");
    int (*run_main)(const tor_main_configuration_t *) =
        (int (*)(const tor_main_configuration_t *))dlsym(h, "tor_run_main");
    void (*cfg_free)(tor_main_configuration_t *) =
        (void (*)(tor_main_configuration_t *))dlsym(h, "tor_main_configuration_free");
    if (!cfg_new || !cfg_set || !run_main || !cfg_free) return -101;

    int argc = (*env)->GetArrayLength(env, jargs);
    char **argv = (char **)calloc((size_t)argc, sizeof(char *));
    for (int i = 0; i < argc; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, jargs, i);
        const char *c = (*env)->GetStringUTFChars(env, js, NULL);
        argv[i] = strdup(c);
        (*env)->ReleaseStringUTFChars(env, js, c);
        (*env)->DeleteLocalRef(env, js);
    }
    tor_main_configuration_t *cfg = cfg_new();
    int rc = -102;
    if (cfg_set(cfg, argc, argv) == 0) rc = run_main(cfg);
    cfg_free(cfg);
    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    return rc;
}
```

`modules/tor-manager/android/src/main/java/expo/modules/tormanager/TorRunner.kt`:

```kotlin
package expo.modules.tormanager

object TorRunner {
    init {
        System.loadLibrary("torjni")
    }

    /** Blocks until tor exits -- run on a dedicated thread. Returns tor's
     *  exit code, or a negative torjni error (see tor_jni.c). */
    external fun nativeRunTor(args: Array<String>): Int
}
```

- [ ] **Step 4: Write the Kotlin support classes**

`modules/tor-manager/android/src/main/java/expo/modules/tormanager/Socks.kt`:

```kotlin
package expo.modules.tormanager

import java.io.EOFException
import java.io.InputStream
import java.net.Socket

/** InputStream.readNBytes is API 33+; the G20 is API 30. */
fun InputStream.readExact(n: Int): ByteArray {
    val buf = ByteArray(n)
    var off = 0
    while (off < n) {
        val r = read(buf, off, n - off)
        if (r < 0) throw EOFException("stream closed at $off/$n")
        off += r
    }
    return buf
}

/** SOCKS5 (RFC 1928) no-auth CONNECT via the local tor SOCKS port.
 *  ATYP=3 (domain name) so tor resolves the .onion itself. */
fun socksDial(socksPort: Int, host: String, port: Int): Socket {
    val s = Socket("127.0.0.1", socksPort)
    try {
        s.soTimeout = 120_000   // onion circuit build can take tens of seconds
        val out = s.getOutputStream()
        val inp = s.getInputStream()
        out.write(byteArrayOf(5, 1, 0)); out.flush()
        val method = inp.readExact(2)
        require(method[0].toInt() == 5 && method[1].toInt() == 0) { "socks method refused" }
        val hb = host.toByteArray(Charsets.US_ASCII)
        require(hb.size in 1..255) { "host too long for socks" }
        out.write(byteArrayOf(5, 1, 0, 3, hb.size.toByte()) + hb +
                  byteArrayOf(((port shr 8) and 0xff).toByte(), (port and 0xff).toByte()))
        out.flush()
        val rep = inp.readExact(4)
        require(rep[1].toInt() == 0) { "socks connect failed, REP=${rep[1]}" }
        when (rep[3].toInt()) {          // drain BND.ADDR + BND.PORT
            1 -> inp.readExact(4 + 2)
            3 -> { val l = inp.readExact(1)[0].toInt() and 0xff; inp.readExact(l + 2) }
            4 -> inp.readExact(16 + 2)
            else -> throw IllegalStateException("bad socks ATYP ${rep[3]}")
        }
        return s
    } catch (e: Exception) {
        s.close()
        throw e
    }
}
```

`modules/tor-manager/android/src/main/java/expo/modules/tormanager/ControlPort.kt`:

```kotlin
package expo.modules.tormanager

import java.io.File
import java.net.Socket

/** Minimal tor control-port client: cookie AUTHENTICATE + one GETINFO.
 *  Mirrors the desktop's control-port bootstrap watching (hearth/tor.py),
 *  which is why the spike watches the control port rather than stdout. */
class ControlPort(private val port: Int, private val cookieFile: File) {

    /** Current bootstrap percentage, or null while the control port /
     *  cookie are not up yet. */
    fun bootstrapProgress(): Int? = try {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5_000
            val out = s.getOutputStream()
            val reader = s.getInputStream().bufferedReader()
            val cookie = cookieFile.readBytes().joinToString("") { "%02x".format(it) }
            out.write("AUTHENTICATE $cookie\r\n".toByteArray()); out.flush()
            if (!(reader.readLine() ?: "").startsWith("250")) return null
            out.write("GETINFO status/bootstrap-phase\r\n".toByteArray()); out.flush()
            var progress: Int? = null
            while (true) {
                val line = reader.readLine() ?: break
                Regex("PROGRESS=(\\d+)").find(line)?.let {
                    progress = it.groupValues[1].toInt()
                }
                if (line.startsWith("250 ")) break
            }
            progress
        }
    } catch (_: Exception) {
        null
    }

    fun signalShutdown() = try {
        Socket("127.0.0.1", port).use { s ->
            val cookie = cookieFile.readBytes().joinToString("") { "%02x".format(it) }
            s.getOutputStream().apply {
                write("AUTHENTICATE $cookie\r\nSIGNAL SHUTDOWN\r\n".toByteArray())
                flush()
            }
        }
    } catch (_: Exception) { }
}
```

- [ ] **Step 5: Write the module**

`modules/tor-manager/android/src/main/java/expo/modules/tormanager/TorManagerModule.kt`:

```kotlin
package expo.modules.tormanager

import android.util.Base64
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private const val SOCKS_PORT = 39050
private const val CONTROL_PORT = 39051
private const val BOOTSTRAP_TIMEOUT_MS = 300_000L

class TorManagerModule : Module() {
    private var torThread: Thread? = null
    private val conns = ConcurrentHashMap<Int, Socket>()
    private val nextConn = AtomicInteger(1)

    // tor state (guards/consensus) stays app-internal; the LOG goes to the
    // external files dir so `adb pull` reaches it without run-as.
    private fun dataDir(): File =
        File(appContext.reactContext!!.filesDir, "tordata").apply { mkdirs() }
    private fun externalDir(): File =
        appContext.reactContext!!.getExternalFilesDir(null)!!

    override fun definition() = ModuleDefinition {
        Name("TorManager")

        Constants(
            "fixtureDir" to (appContext.reactContext?.getExternalFilesDir(null)?.absolutePath ?: "")
        )

        Events("torProgress")

        AsyncFunction("bootstrap") { promise: Promise ->
            if (torThread?.isAlive == true) {
                promise.resolve(SOCKS_PORT)
                return@AsyncFunction
            }
            val dir = dataDir()
            val logFile = File(externalDir(), "tor.log")
            val args = arrayOf(
                "tor",
                "--SocksPort", "127.0.0.1:$SOCKS_PORT",
                "--ControlPort", "127.0.0.1:$CONTROL_PORT",
                "--CookieAuthentication", "1",
                "--DataDirectory", dir.absolutePath,
                "--Log", "notice file ${logFile.absolutePath}",
            )
            torThread = thread(name = "tor-main") { TorRunner.nativeRunTor(args) }
            thread(name = "tor-bootstrap-watch") {
                val ctl = ControlPort(CONTROL_PORT, File(dir, "control_auth_cookie"))
                val deadline = System.currentTimeMillis() + BOOTSTRAP_TIMEOUT_MS
                var last = -1
                while (System.currentTimeMillis() < deadline) {
                    if (torThread?.isAlive != true) {
                        promise.reject("TOR_DIED", "tor thread exited during bootstrap (see tor.log)", null)
                        return@thread
                    }
                    val p = ctl.bootstrapProgress()
                    if (p != null && p != last) {
                        last = p
                        sendEvent("torProgress", mapOf("progress" to p))
                    }
                    if (p == 100) {
                        promise.resolve(SOCKS_PORT)
                        return@thread
                    }
                    Thread.sleep(1000)
                }
                promise.reject("TOR_TIMEOUT", "bootstrap did not reach 100% in 300s", null)
            }
        }

        Function("socksPort") { SOCKS_PORT }

        AsyncFunction("dial") { host: String, port: Int ->
            val s = socksDial(SOCKS_PORT, host, port)
            val id = nextConn.getAndIncrement()
            conns[id] = s
            id
        }

        AsyncFunction("send") { id: Int, b64: String ->
            val s = conns[id] ?: throw IllegalArgumentException("no conn $id")
            s.getOutputStream().apply {
                write(Base64.decode(b64, Base64.NO_WRAP))
                flush()
            }
        }

        AsyncFunction("recv") { id: Int, n: Int ->
            val s = conns[id] ?: throw IllegalArgumentException("no conn $id")
            Base64.encodeToString(s.getInputStream().readExact(n), Base64.NO_WRAP)
        }

        Function("closeConn") { id: Int ->
            conns.remove(id)?.close()
        }

        AsyncFunction("suspendTor") {
            ControlPort(CONTROL_PORT, File(dataDir(), "control_auth_cookie")).signalShutdown()
            torThread?.join(10_000)
            conns.values.forEach { runCatching { it.close() } }
            conns.clear()
        }
    }
}
```

- [ ] **Step 6: Write the TS binding**

`modules/tor-manager/index.ts`:

```ts
// The narrow manager interface the whole client will depend on (spec):
// bootstrap / socksPort / dial / suspend, plus the fixtureDir constant.
import { requireNativeModule } from "expo-modules-core";
import { fromByteArray, toByteArray } from "base64-js";
import type { Stream } from "../../src/wire";

const native = requireNativeModule("TorManager");

export const fixtureDir: string = native.fixtureDir;

export function onProgress(cb: (p: number) => void): () => void {
  const sub = native.addListener("torProgress", (e: { progress: number }) => cb(e.progress));
  return () => sub.remove();
}

export function bootstrap(): Promise<number> {
  return native.bootstrap();
}

export function socksPort(): number {
  return native.socksPort();
}

export class TorStream implements Stream {
  constructor(private id: number) {}
  async read(n: number): Promise<Uint8Array> {
    return toByteArray(await native.recv(this.id, n));
  }
  async write(b: Uint8Array): Promise<void> {
    await native.send(this.id, fromByteArray(b));
  }
  close(): void {
    native.closeConn(this.id);
  }
}

export async function dial(host: string, port: number): Promise<TorStream> {
  return new TorStream(await native.dial(host, port));
}

export function suspendTor(): Promise<void> {
  return native.suspendTor();
}
```

(If the installed Expo SDK's `requireNativeModule` return type lacks `addListener` directly, use `new EventEmitter(native)` from `expo-modules-core` — same subscription shape. Adapt to whichever the installed SDK version exposes; the exported `onProgress` signature must not change.)

- [ ] **Step 7: Verify the full build compiles**

```powershell
. .\android_tor_spike\tools\env.ps1
cd android_tor_spike\app\android
.\gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`, with `:tor-manager:externalNativeBuildDebug` (CMake/torjni) and Kotlin compilation in the log. Tor itself cannot run on the desk — the on-device run (Task 10) exercises it; this gate is compile + packaging only. Optionally confirm packaging:

```powershell
# both libtor.so and libtorjni.so must be inside the APK, arm64 only
& "$env:JAVA_HOME\bin\jar.exe" -tf app\build\outputs\apk\debug\app-debug.apk | Select-String "lib/"
```

- [ ] **Step 8: Commit**

```powershell
git add android_tor_spike/app/modules android_tor_spike/app/android android_tor_spike/app/package.json android_tor_spike/app/package-lock.json
git commit -m "feat(spike): TorManager expo module - JNI shim onto libtor.so tor_api, control-port bootstrap watch, socks5 dial, byte bridge"
```

---

### Task 9: One-button proof screen (`App.tsx`)

**Files:**
- Modify: `android_tor_spike/app/App.tsx` (replace template content)
- Modify: `android_tor_spike/app/android/app/build.gradle` (release variant usable without metro)

**Interfaces:**
- Consumes: `handshake`/`splitAddr`/`Fixture` (Task 5), `bootstrap`/`dial`/`onProgress`/`fixtureDir` (Task 8), `expo-file-system`, `expo-crypto`.
- Produces: the installable spike APK; UI strings Task 10's checklist references verbatim: stage line (`tor bootstrap N%`, `dialing home node`, `handshake`) and result line (`CONNECTED to home node over Tor` / `REFUSED by node` / `FAILED at <stage>: <reason>` / `ERROR: <e>`).

- [ ] **Step 1: Install runtime deps**

```powershell
cd android_tor_spike\app
npx expo install expo-file-system expo-crypto
```

- [ ] **Step 2: Write `App.tsx`**

```tsx
import React, { useCallback, useState } from "react";
import { Button, SafeAreaView, StyleSheet, Text } from "react-native";
import * as FileSystem from "expo-file-system";
import * as Crypto from "expo-crypto";
import { bootstrap, dial, fixtureDir, onProgress } from "./modules/tor-manager";
import { Fixture, handshake, splitAddr } from "./src/handshake";

function randomHex16(): string {
  return Array.from(Crypto.getRandomBytes(16))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export default function App() {
  const [stage, setStage] = useState("idle");
  const [result, setResult] = useState("");
  const [busy, setBusy] = useState(false);

  const connect = useCallback(async () => {
    setBusy(true);
    setResult("");
    try {
      setStage("reading fixture");
      const raw = await FileSystem.readAsStringAsync(
        `file://${fixtureDir}/spike_phone_fixture.json`);
      const fixture = JSON.parse(raw) as Fixture;

      setStage("tor bootstrap 0%");
      const off = onProgress((p) => setStage(`tor bootstrap ${p}%`));
      try {
        await bootstrap();
      } finally {
        off();
      }

      setStage("dialing home node");
      const [host, port] = splitAddr(fixture.onion_addr);
      const stream = await dial(host, port);

      setStage("handshake");
      const r = await handshake(stream, fixture, randomHex16);
      setStage("done");
      setResult(
        r.status === "accepted" ? "CONNECTED to home node over Tor"
        : r.status === "refused" ? "REFUSED by node"
        : `FAILED at ${r.stage}: ${r.reason}`);
    } catch (e) {
      setStage("done");
      setResult(`ERROR: ${String(e)}`);
    } finally {
      setBusy(false);
    }
  }, []);

  return (
    <SafeAreaView style={styles.container}>
      <Text style={styles.title}>Kreds Tor spike</Text>
      <Button title="Connect" onPress={connect} disabled={busy} />
      <Text style={styles.stage}>{stage}</Text>
      <Text style={styles.result}>{result}</Text>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, alignItems: "center", justifyContent: "center", gap: 16 },
  title: { fontSize: 20, fontWeight: "600" },
  stage: { fontSize: 16 },
  result: { fontSize: 16, fontWeight: "600", paddingHorizontal: 24, textAlign: "center" },
});
```

- [ ] **Step 3: Make the release variant self-contained**

The debug variant needs a metro server; August's run must not. The release variant bundles JS and (Expo prebuild default) signs with the debug keystore — verify in `app/android/app/build.gradle` that `buildTypes.release.signingConfig` is set (to `signingConfigs.debug` in the template); if absent, add:

```gradle
        release {
            signingConfig signingConfigs.debug
            shrinkResources false
            minifyEnabled false
        }
```

- [ ] **Step 4: Build both variants and install release on the G20**

```powershell
. .\android_tor_spike\tools\env.ps1
cd android_tor_spike\app\android
.\gradlew assembleDebug assembleRelease
adb -s ZY32DLZQ2N install -r app\build\outputs\apk\release\app-release.apk
```

Expected: `BUILD SUCCESSFUL`, then `Success` from adb. (Install proves packaging/ABI/minSdk on real hardware; tapping Connect is Task 10.)

- [ ] **Step 5: Commit**

```powershell
git add android_tor_spike/app/App.tsx android_tor_spike/app/android android_tor_spike/app/package.json android_tor_spike/app/package-lock.json android_tor_spike/app/app.json
git commit -m "feat(spike): one-button proof screen - fixture load, bootstrap progress, dial, handshake verdict"
```

---

### Task 10: On-device run (August + the G20)

**Files:**
- Create: `android_tor_spike/ON_DEVICE_CHECKLIST.md`

**Interfaces:**
- Consumes: everything above; the real desktop node (0.3.18) running with Tor; `mint_phone_fixture.py` (Task 6).
- Produces: the spike verdict + `logcat.txt` / `tor.log` diagnostics on failure.

- [ ] **Step 1: Write the checklist** (per the testing-workflow division: August drives the phone; the checklist is the handoff)

`android_tor_spike/ON_DEVICE_CHECKLIST.md`:

```markdown
# Spike on-device run (G20, serial ZY32DLZQ2N)

Prep (desk, once):
1. Desktop Kreds app CLOSED. From the repo root:
   `.venv\Scripts\python.exe android_tor_spike\tools\mint_phone_fixture.py`
   (asks for the app-lock credential if you have one; writes
   android_tor_spike\spike_phone_fixture.json)
2. Start the desktop Kreds app and wait until it is fully online
   (Tor connected -- the node must be reachable at its onion).

Phone (USB, from the repo root):
3. `. .\android_tor_spike\tools\env.ps1`
4. `adb -s ZY32DLZQ2N install -r android_tor_spike\app\android\app\build\outputs\apk\release\app-release.apk`
5. Open "KredsTorSpike" on the phone once, then close nothing -- just
   background it (first open creates the app's files dir).
6. `adb -s ZY32DLZQ2N push android_tor_spike\spike_phone_fixture.json /sdcard/Android/data/eu.kreds.torspike/files/`
7. In the app, tap **Connect**.

Expected: "tor bootstrap N%" climbing (first run can take 1-3 min),
then "dialing home node" (tens of seconds -- onion circuit), then
"handshake", then result line **CONNECTED to home node over Tor**.

If it fails, note the LAST stage line + the result line -- that pair
localizes the layer:

| Last stage / result                | Layer that failed        | Grab this                |
|------------------------------------|--------------------------|--------------------------|
| ERROR ... fixture                  | push path / step 5-6     | rerun steps 5-6          |
| tor bootstrap stuck / TOR_TIMEOUT  | tor-android / JNI        | tor.log (below), logcat  |
| TOR_DIED                           | JNI shim / libtor        | logcat + tor.log         |
| ERROR ... dialing / socks          | SOCKS dial / node onion  | is the desktop online?   |
| FAILED at hello/auth/probe         | wire port (unlikely --   | exact result line        |
|                                    | desk-gated) or node side |                          |
| REFUSED by node                    | cert/identity mismatch   | result line + fixture    |

Diagnostics:
- `adb -s ZY32DLZQ2N logcat -d > android_tor_spike\logcat.txt`
- `adb -s ZY32DLZQ2N pull /sdcard/Android/data/eu.kreds.torspike/files/tor.log android_tor_spike\`

Cleanup (whenever the spike phone should stop being able to auth):
- Phone: uninstall the app OR
  `adb -s ZY32DLZQ2N shell rm /sdcard/Android/data/eu.kreds.torspike/files/spike_phone_fixture.json`
- Desk: delete android_tor_spike\spike_phone_fixture.json
- The cert was never published to the node's store (proven in
  tests/test_handshake_desk.py), so there is nothing to revoke;
  revoke_device(device_pub from the fixture) remains available as
  belt-and-braces.
```

- [ ] **Step 2: Commit the checklist, then hand off**

```powershell
git add android_tor_spike/ON_DEVICE_CHECKLIST.md
git commit -m "docs(spike): on-device run checklist for the G20"
```

Hand the checklist to August. **PAUSE — the run itself is human-driven.** Collect: the result line, and on failure the stage/result pair + `logcat.txt` + `tor.log`.

- [ ] **Step 3: If the run fails** — use superpowers:systematic-debugging with the layer table above; the desk gates (Tasks 2–5) mean a `FAILED at hello/auth/probe` implicates the transport bridging (base64/recv framing) before the wire port. Fix surgically, rebuild, rerun.

---

### Task 11: Spike report

**Files:**
- Create: `android_tor_spike/SPIKE_REPORT.md`

**Interfaces:**
- Consumes: Task 10's verdict; Task 5's cert-publish finding; Task 7's NOTES.md.

- [ ] **Step 1: Write the report** (follow `hearth_d2_spike/SPIKE_REPORT.md` for tone/structure). Must contain, with actual observed values — no placeholders:

- Verdict: did the phone complete HELLO/AUTH over Tor against the real node? (the two spec-proof bullets, each explicitly confirmed/denied)
- Timings observed: first bootstrap, warm bootstrap, onion dial, handshake round trip
- **Un-enroll answer (spec open question):** the enroll_other cert authenticates WITHOUT ever being published to the node's store (own-identity path, `sync.py:472/479`, proven on desk by `test_handshake_desk.py` and on-device by the run itself) → the fixture is a pure local artifact; cleanup = delete the two fixture copies; `revoke_device` optional.
- tor-android findings (from NOTES.md): version used, W^X confirmation, control-port availability
- Kotlin/JNI surface as built vs. spec's `bootstrap()/socksPort()/dial()/suspend()` — deviations listed
- What the real client inherits as-is (wire.ts, handshake.ts, TorManager, vectors) vs. what gets rebuilt (fixture transport → real pairing, screen → real UI, Tor lifecycle)
- Honest unknowns that remain (background lifecycle, multi-ABI, battery)

- [ ] **Step 2: Commit**

```powershell
git add android_tor_spike/SPIKE_REPORT.md
git commit -m "docs(spike): android tor-dial spike report - verdict, timings, un-enroll answer, client-inheritance notes"
```

---

## Self-Review (performed at write time)

**Spec coverage:** Tor manager → Task 8; wire layer + vectors-before-phone → Tasks 2–4; handshake → Task 5; RN screen → Task 9; desktop dev helper → Task 6; NDK/Expo/tor-android toolchain → Tasks 1, 7; on-device run → Task 10; un-enroll open question → answered statically in "wire contract", proven in Task 5, recorded in Task 11; W^X + control-port risks → Task 7 Steps 3–4; arm64-only → Tasks 1/8. Out-of-scope list respected (no pairing UI, no content sync, no lifecycle work).

**Known judgment calls (flagged, not hidden):**
- The "stops at AUTH" spec line is implemented as the acceptance probe (one empty-revocations frame + one read) because refusal/acceptance is only observable there; no revocation data is applied, and DEFRIENDS/HAVE/MESSAGES/BLOBS are never reached. Documented in handshake.ts and the wire-contract section.
- `suspend()` is implemented as full stop (SIGNAL SHUTDOWN) — sufficient for the spike's narrow interface; noted for the report.
- Python float formatting is handled schema-aware (`PyFloat` on `enrolled_at` only), with out-of-range floats throwing loudly rather than guessing; the integral-float case is vector-pinned.
- `readFrame` in wire.ts rejects non-ASCII frame bytes instead of decoding UTF-8 (RN Hermes lacks TextDecoder; Python's `json.dumps` default `ensure_ascii=True` means the node never sends any) — loud failure over silent mojibake.

**Type consistency:** `Stream`/`CertDict`/`Fixture`/`HandshakeResult`/`PyFloat` names and shapes match across Tasks 3/4/5/8/9; `mint_fixture` dict keys match the `Fixture` interface; CLI output strings (`RESULT accepted|refused|failed`) match the Task 5 tests; App.tsx result strings match the Task 10 checklist table; module event name `torProgress` and constant `fixtureDir` match between Kotlin and index.ts; NDK version string `26.3.11579264` consistent across Tasks 1/7/8.
