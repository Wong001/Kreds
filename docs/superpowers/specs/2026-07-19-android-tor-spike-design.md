# Android Tor-dial spike (design, 2026-07-19)

First step of the Android client (platform-order decided 2026-07-19:
Android-first — buildable/testable entirely from Windows, no macOS gate).
Spike-first, like the D2 device-enrollment spike (18/18): prove the
single riskiest, most novel loop end-to-end on real hardware before
committing to the full client port. If the phone says "connected to home
node over Tor," the satellite model's network foundation is proven on
Android and everything after is ordinary porting on a known-good base.

## What this spike proves (and what it does not)

**Proves the two Android-novel things:**
1. Our own Kotlin/JNI Tor manager bootstraps GP `tor-android` (C-tor)
   in-process and exposes a working SOCKS proxy on the phone.
2. The phone SOCKS-dials the desktop home node's `.onion` and completes
   a REAL cryptographic HELLO/AUTH handshake — meaning the Ed25519
   signing, canonical-JSON, and length-prefixed framing all port
   correctly from Python to Kotlin/TypeScript (the second-riskiest
   piece; a byte-for-byte mismatch fails here, loudly, in isolation).

**Does NOT prove (deferred to the real client — normal work, not novel
risk):** the pairing/enrollment UI, identity-key provisioning
(`DeviceKeys.install`), content sync (HAVE/MESSAGES/BLOBS), the
background/foreground Tor lifecycle, notifications, any real app UI.

## Decisions (August, 2026-07-19)

- **Depth:** Tor-dial + real crypto handshake, with STUBBED TRUST — the
  phone authenticates with a genuine device cert but we hand-carry it
  rather than run the pairing ceremony. Every novel thing is real; only
  the (already-D2-validated) pairing transport is stubbed.
- **Home node:** the REAL desktop node (0.3.18), real identity — not a
  throwaway. De-risked below; and it forces us to solve un-enroll now,
  which the real client needs anyway.
- **Engine:** GP `tor-android` C-tor via JNI, behind a narrow
  swap-friendly manager interface. NOT Arti (pre-1.0, self-`exit(1)` on
  obsolete consensus, non-default onion-client — bad fit for a
  slow-update mobile channel), NOT a Tor reimplementation (roll-your-own
  anonymity antipattern). Settled — see mobile-architecture memory.

## Why the real node is safe (verified against the code)

The Kreds handshake (`hearth/sync.py` `_session`) is:
HELLO `{t, cert, nonce}` (each side sends its enrollment cert + a
nonce) → AUTH `{t, sig}` (each signs the OTHER's nonce with its DEVICE
key) → the node continues only for a known/own identity, else
`{"t":"refused"}`.

Three properties make pointing the phone at the real node low-stakes:

1. **The identity private key never leaves the desktop.** The desktop
   (which holds it) calls `node.enroll_other(phone_device_pub,
   "spike-phone")` → an `EnrollmentCert` signed by the identity. The
   phone generates its OWN device keypair and receives only
   `{device_priv, cert}` — a cryptographically genuine device cert
   (identity-signed), whether or not the node ever registers it as a
   real device (see the publish question under un-enroll). The identity
   key stays put regardless.
2. **AUTH uses only the DEVICE key** — not the identity key, not the
   per-message content-decryption keys. The phone can prove "I am a
   device of this identity" and nothing more.
3. **The spike stops at AUTH success** — it never advances to
   HAVE/MESSAGES/BLOBS, so no real content ever flows to the phone even
   though it authenticated. (The phone is an "own-identity peer," which
   `_session` exempts from stranger-refusal — see node.py; that
   exemption is exactly why a real device cert is what makes AUTH
   succeed rather than a fabricated key.)

**Un-enroll (the "awkward later" August flagged — resolved):** the
existing `node.revoke_device(phone_device_pub)` is the removal path — it
kills that device_pub network-wide and wipes it, the same
device-revocation flow the product already ships. Removing the spike
phone is therefore a first-class operation, not a cleanup hack — and
one the real client will exercise anyway. Open question to answer during
build (not blocking): whether the own-identity exemption lets AUTH
succeed from a cert that was never published to the node's store, in
which case the spike cert is a pure local fixture with literally nothing
to revoke; if publishing IS required, `revoke_device` is the cleanup.

## Components (all new, all small, all ours)

1. **Tor manager — Kotlin/JNI** (`TorManager.kt` + JNI glue), the narrow
   interface the whole client will depend on:
   `bootstrap(onProgress)`, `socksPort(): Int`, `dial(onion, port):
   Socket`, `suspend()`. Wraps GP `tor-android`: loads `libtor.so`,
   runs `tor_run_main` on a dedicated thread, watches bootstrap via the
   control port, exposes the SOCKS port. This is the auditable layer we
   own; the C-tor binary underneath is unchanged and unmodified.
2. **Wire layer — TypeScript** (`wire.ts`): length-prefixed frame
   read/write, Ed25519 `sign_raw`, canonical-JSON — ported to match
   `hearth/identity.py` + `hearth/transport.py` BYTE-FOR-BYTE. Verified
   with cross-language test vectors (a Python-produced canonical-JSON +
   signature that the TS side must reproduce/verify identically) BEFORE
   the on-phone run, so a mismatch is caught on the desk, not blamed on
   Tor.
3. **Handshake — TypeScript** (`handshake.ts`): HELLO/AUTH exactly per
   `sync.py` `_session`, over the SOCKS-dialed stream from the manager.
   Stops at "peer AUTH verified + our AUTH accepted."
4. **Minimal RN screen** (`App.tsx`): one "Connect" button, a bootstrap
   progress line, a result line. No real UI, no navigation.
5. **Desktop dev helper:** a small dev-only script that calls
   `enroll_other` to mint the phone's `{device_priv, cert}` fixture and
   the node's current `.onion` address, written to a JSON file that
   `adb push` delivers to the phone. This stubs the pairing TRANSPORT
   while keeping the pairing CRYPTO real. NOT shipped in the client.

## Data flow (the whole spike in one line)

desktop `enroll_other` → fixture JSON (device cert + onion) → `adb push`
to phone → phone taps Connect → `TorManager.bootstrap()` → `dial(onion,
9997)` → `handshake` sends HELLO(cert,nonce) / verifies node HELLO /
sends AUTH(sig over node nonce) / verifies node AUTH over our nonce →
"connected."

(`ONION_VIRTUAL_PORT = 9997`, fixed since the 0.3.14 outage fix — the
phone dials that port on the node's onion.)

## Toolchain additions this spike needs

- **Android NDK** (for the JNI glue) — `sdkmanager "ndk;<r26+>"`; not
  yet installed (SDK/platform-tools/JDK 17 already are, 2026-07-19).
- **Expo dev build + config plugin** — custom native code means a dev
  build, NOT Expo Go. Node 24 already present.
- **GP `tor-android`** — vendored as a dependency
  (`info.guardianproject:tor-android`, arm64-v8a for the G20 first;
  verify the current release coordinate during build — GP shipped
  through May 2026).

## Testing

- **Cross-language wire vectors (desk, pre-phone):** a committed fixture
  of Python-canonical-JSON + Ed25519 signatures; a TS test that
  reproduces the canonical bytes and verifies the signatures. This is
  the gate that isolates a port bug from a Tor bug.
- **On-device (August + the G20):** the real end-to-end run — plug in,
  push fixture, tap Connect, observe "connected." Failure at any stage
  is diagnosable because the layers are separable (bootstrap progress
  line vs. dial vs. handshake result).
- **Manager unit-shape:** the interface is exercised by the on-device
  run; no attempt to unit-test Tor itself (delegated engine).

## Risks / honest unknowns (resolve during build, none blocking)

- **W^X / `libtor.so` hosting:** Android API 29+ blocks `exec()` from
  writable storage; GP ships tor as `libtor.so` in the read-only
  native-lib dir and runs it via JNI (`tor_run_main`), sidestepping the
  exec path entirely. Confirm current GP packaging still does this.
- **arm64-v8a only for the spike:** the G20 is arm64-v8a; multi-ABI
  (armeabi-v7a, x86_64 for emulators) is a real-client concern, not a
  spike one.
- **Control-port vs. stdout bootstrap detection:** desktop parses the
  control port; confirm the tor-android build exposes it or whether
  bootstrap is watched via a callback. Manager-internal, interface
  unchanged either way.
- **Cert-publish requirement for own-identity AUTH:** see un-enroll
  note above — decides whether cleanup is `revoke_device` or nothing.

## Out of scope (named, so it doesn't creep in)

Pairing/enrollment UI; identity-key provisioning; content sync; Tor
background/foreground lifecycle; notifications (APNs is iOS-only anyway);
multi-ABI builds; the iOS client (Tor.framework, the Mac burst); any
real app UI beyond the one-button proof screen.
