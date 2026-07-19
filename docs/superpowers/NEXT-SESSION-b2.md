# Next-session continuation prompt — execute the B.2 plan

Paste the block below into a fresh session to resume exactly here.

---

Resuming the Kreds Android client. Continue by executing the B.2 (decryption
+ readable history) implementation plan subagent-driven, the same way the
prior three slices were run. The mobile-architecture memory has the full
context; read it plus the plan/spec before starting.

**State at handoff (2026-07-19, deep night):** three slices are MERGED to
public main (github.com/Wong001/Kreds) and PROVEN on the Moto G20 (serial
ZY32DLZQ2N, arm64-v8a, API 30) — the Tor-dial spike (PR #1), Brick A
(persistent background node surviving Doze/process-death, PR #2), and Brick
B.1 (native Kotlin content-sync transport; on the G20 it pulled 253 msgs / 19
blobs / 3 friends of own-identity content over Tor into a phone SQLite store,
stored ENCRYPTED, PR #3). B.2's spec + plan are committed on main; the B.2
build is NOT started.

**Do this:**
1. Read: `docs/superpowers/plans/2026-07-19-android-b2-decryption.md` (the
   plan), `docs/superpowers/specs/2026-07-19-android-b2-decryption-design.md`
   (spec), and `...-b2-decryption-decomposition.md` (why backfill-included).
2. Execute the plan subagent-driven (superpowers:subagent-driven-development):
   fresh subagent per task, spec+quality review after each, fix loops, a
   whole-branch review at the end. Start a branch off main first (do NOT
   build on main). 9 tasks; Task 1 (KotlinDmcrypt port) is the safe
   self-contained start.

**What B.2's first slice delivers:** the phone generates + publishes an
X25519 enc key (a device-signed `enckey` message, pushed via a NEW write in
the KotlinSync MESSAGES phase — B.1 was read-only), the desktop backfills
author-signed wrap-grants for the phone's existing own-authored content, and
the phone decrypts + shows readable text from its real 253-message history in
a minimal feed. Own-authored content only (friends = B.2c; rich media/thread
UI = B.2d).

**Highest-stakes item — weight the review here:** Task 2 adds
`maintain_own_device_grants` to `hearth/node.py` — the FIRST change to
production `hearth/` on the live desktop. It re-wraps OWN content to OWN
devices via author-signed wrap_grants. NON-NEGOTIABLE: the friend-facing
`maintain_wrap_grants` is NEVER modified (the new sweep is isolated,
additive; wired only at api.py:~98 + sync.py:~249 after the existing call).
Its two negative tests (friends' content NOT granted; a revoked/locked node
mints nothing) are the security core and MUST be filled with real
friend-post setup — do not ship them stubbed. Confirm the exact hearth store
accessors during build (enckey ingest/record, get_message, the own-DM
iterator, DeviceView import).

**Other load-bearing gotchas (in the plan, don't rediscover the hard way):**
- AAD byte-fidelity is the sharp edge: a byte off vs hearth → every decrypt
  returns null → empty feed (uniform, diagnosable). AAD is built via
  `KotlinWire.canonical` (created_at as PyFloat); vector-gated (Task 1).
- Prefer BouncyCastle's `ChaCha20Poly1305` AEAD over javax Cipher (avoids a
  minSdk-28 gate; identical on JVM + Android). X25519 ECDH via BouncyCastle
  `X25519Agreement`. HKDF-SHA256 info = `hearth/dm-wrap/v1`.
- The desk loopback gate (Task 6, extends the B.1 gate) is the spine: seed
  own posts on a real node, publish the phone enc key, run the catch-up,
  sync, assert the phone DECRYPTS — before the phone.
- Two-sync on-device flow: sync 1 publishes the enckey; the desktop mints
  grants on its next maintenance; sync 2 pulls them → decrypt → feed.

**Constraints:** NO AI/Co-Authored-By commit trailers (August's standing
rule); style `feat(b2):`/`fix(b2):` lowercase. tor-android 0.4.9.6, NDK
26.3.11579264, arm64-v8a, compileSdk 36. Dot-source
`android_tor_spike/tools/env.ps1` every PowerShell session (JDK/SDK vars are
User-scope, not inherited by tool shells); Python gates use
`.venv\Scripts\python.exe`. August drives the on-device runs (Task 9); Claude
runs the desk gates + adb. Testing-workflow-division + voice-rule memories
apply. Merge is August's call after the on-device run (he did "1+2" — merge +
PR — for the last three).
