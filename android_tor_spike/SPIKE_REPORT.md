# Android Tor-dial spike report

**Status: DESK-COMPLETE, on-device run PENDING.** Everything provable at
the desk is proven and reviewed; the one remaining step is the human-driven
G20 run (see `ON_DEVICE_CHECKLIST.md`). The two sections marked
**[PENDING RUN]** are filled in after that run.

Spec: `docs/superpowers/specs/2026-07-19-android-tor-spike-design.md`
Plan: `docs/superpowers/plans/2026-07-19-android-tor-spike.md`

## Verdict

The spike set out to prove two Android-novel things:

1. **Our Kotlin/JNI Tor manager bootstraps GP tor-android in-process and
   exposes a working SOCKS proxy.** Built and packaging-proven: the
   `TorManager` Expo module wraps GP `tor-android:0.4.9.6` behind the
   narrow `bootstrap()/socksPort()/dial()/suspend()` interface, with a JNI
   shim (`libtorjni.so`) that dlopen's `libtor.so` and calls the `tor_api`
   entry points. Both native libraries package into the arm64-v8a APK.
   Runtime bootstrap on hardware is **[PENDING RUN]**.

2. **The phone SOCKS-dials the real node's .onion and completes a real
   HELLO/AUTH handshake.** The handshake logic (`handshake.ts` +
   `wire.ts`) is proven byte-for-byte against the real Python node at the
   desk (see below); running it over an actual Tor circuit from the phone
   is **[PENDING RUN]**.

The riskiest, most novel loop is therefore de-risked as far as the desk
allows: the crypto/wire port is proven end-to-end against the real node,
and the native Tor layer compiles and packages correctly. What remains is
a single integration run on hardware.

## What was proven at the desk (reviewed, green)

- **Wire port is byte-exact.** `wire.ts` (canonical JSON, length-prefixed
  frames, Ed25519 sign/verify) reproduces every vector generated from the
  real `hearth/identity.py` + `hearth/transport.py` + `hearth/sync.py`
  (vitest 20/20), including the nasty cases: `ensure_ascii` escaping,
  astral-plane code-point key sort, and Python integral-float rendering
  (`1234.0` via a `PyFloat` marker).
- **TS -> Python direction verified.** TS-produced auth signatures and a
  TS-re-serialized cert verify under the real hearth crypto after a Python
  `json.loads` round trip (`test_ts_roundtrip.py`).
- **Full HELLO/AUTH against the real node.** `test_handshake_desk.py` runs
  the TS handshake against a real `SyncService` listener over loopback TCP
  and gets `RESULT accepted`; a cryptographically-valid *foreign*-identity
  cert gets `RESULT refused`. The acceptance probe distinguishes the two.
- Desk suite: **spike pytest 9/9, wire vitest 20/20.**
- **Release APK installs on the G20.** The controller-side
  `adb install -r app-release.apk` returned `Success` on ZY32DLZQ2N, so the
  arm64-v8a ABI, `minSdk=24`, and debug-keystore signing are all correct on
  real hardware -- August's install step is de-risked; only the Connect tap
  remains.

## Un-enroll answer (the spec's open question, RESOLVED)

The spec left open whether the phone's `enroll_other` cert must be
*published* to the node's store for AUTH to succeed. **It does not.**
`test_handshake_desk.py`'s accepted case mints the cert and never registers
the phone device in the node's store, yet AUTH succeeds via the
own-identity path (`hearth/sync.py:472` `is_known(own identity)` is true;
`:479` skips the revocation check for own-identity peers). So the fixture
is a **pure local artifact**: removing the spike phone is deleting the two
fixture copies (phone + desk), with nothing to revoke in the node's store.
`node.revoke_device(device_pub)` remains available as belt-and-braces. This
run will confirm the same holds over the wire from the real device.

## tor-android findings (Task 7, NOTES.md)

- **Version pinned: 0.4.9.6.** The newest release, 0.4.9.11, and 0.4.9.6.2+
  demand `minCompileSdk=37`, but the Expo project is `compileSdk=36` (API
  37 is not installed and bumping the whole app to a preview SDK for one
  AAR is the wrong blast radius). 0.4.9.5 has `minCompileSdk=1` but its POM
  hard-pins `kotlin-stdlib:2.3.0`, which the project's Kotlin 2.1.20
  compiler cannot read. **0.4.9.6** is the sweet spot: `minCompileSdk=36`
  (exact), `kotlin-stdlib:2.2.10` (compatible), all four `tor_api` symbols
  exported on arm64-v8a `libtor.so`, multi-ABI.
- **W^X confirmed.** `libtor.so` ships in the read-only native-lib dir
  (`extractNativeLibs=false`, mmap'd from the APK) and is run via
  `tor_run_main` through JNI -- no `exec()` from writable storage, so
  Android API 29+ W^X is satisfied. `minSdk=24` (<= the G20's API 30).
- **Bootstrap watched via the control port** (cookie AUTHENTICATE + GETINFO
  status/bootstrap-phase), mirroring the desktop -- not stdout parsing.

## Kotlin/JNI surface as built vs. the spec's interface

Built exactly to `bootstrap()/socksPort()/dial()/suspend()`, plus a
`fixtureDir` constant (the adb-push target) and a byte bridge
(`send`/`recv`/`closeConn`, base64 NO_WRAP) behind the `TorStream`
implementing wire.ts's `Stream`. `suspend()` is implemented as a full
control-port `SIGNAL SHUTDOWN` (sufficient for the spike's narrow
interface). Bootstrap failures now surface the JNI exit code
(`dlopen failed` / `symbol missing` / `set_command_line failed`) in the
`TOR_DIED` reject -- important because a dlopen failure exits before
`tor.log` is written, so that reason string is the only diagnostic.

## What the real client inherits vs. rebuilds

**Inherits as-is:** `wire.ts` and `handshake.ts` (the entire crypto/wire
layer, desk-proven), the committed cross-language vectors (a permanent
regression gate for any future port change), and the `TorManager` module
(the narrow, swap-friendly Tor interface the whole client depends on).

**Rebuilt for the real client (normal work, not novel risk):** the fixture
transport -> the real pairing/enrollment ceremony (D2 already validated the
crypto); the one-button screen -> real app UI + navigation; identity-key
provisioning (`DeviceKeys.install`); content sync (HAVE/MESSAGES/BLOBS);
and the Tor background/foreground lifecycle.

## Two plan bugs found during implementation (both amended)

- **Task 2:** the vector writer's recursive `sort_keys` pre-sorted the
  deliberately-unsorted canonical test payloads, which would have let a
  non-sorting TS canonicalizer pass by coincidence. Fixed; all vector
  hex values unchanged.
- **Task 5:** the handshake had (a) a HELLO-stage identity check that
  blocked the node's refused path from ever being observed -- moved the
  home-identity pin to the accepted branch (a friend's node also knows our
  identity and must not read as the home node); and (b) a probe
  write-before-read ordering that raced the refusing node's close (the
  Windows loopback RST purged the buffered `refused` frame) -- reordered to
  a single up-front verdict read with a grace window.

## Honest unknowns that remain (for the real client, not the spike)

- Tor **background/foreground lifecycle** on Android (Doze, process death,
  re-bootstrap on resume) -- deliberately out of spike scope.
- **Multi-ABI** builds (armeabi-v7a, x86_64 for emulators) -- the spike is
  arm64-v8a only for the G20.
- **Battery / bootstrap latency** on a real mobile network vs. loopback.
- The accepted-path handshake currently costs a fixed ~1.5s grace window
  (the probe's unsolicited-refusal wait); fine for a one-shot proof, worth
  restructuring for the real client's connection path.

## [PENDING RUN] On-device result

_Filled in after the G20 run (ON_DEVICE_CHECKLIST.md):_
- Did the phone reach **CONNECTED to home node over Tor**? (the two proof
  bullets, each explicitly confirmed/denied)
- Any layer that failed, and its stage/result line.

## [PENDING RUN] Timings observed

_Filled in after the run:_ first (cold) bootstrap, warm bootstrap, onion
dial, handshake round trip.
