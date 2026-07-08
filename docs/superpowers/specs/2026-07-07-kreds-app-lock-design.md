# Kreds App-Lock (Windows desktop) — Design

**Date:** 2026-07-07
**Status:** Approved (design discussion, this session)
**Basis:** self-hosted node holds `keys.json` = `DeviceKeys.to_json()` (secret fields: `identity_priv`, `enc_priv`, `retired_enc[].enc_priv`, `storage_key`; non-secret: device_pub, enc_pub, cert, retired enc_pubs). `storage_key` seals cached content-keys at rest (`dmcrypt.seal_content_key`/`open_content_key`). Node loads keys at `HearthNode.__init__` (`node.py:27`). Crypto lib = `cryptography` (Ed25519/X25519/HKDF/ChaCha20-Poly1305 already used) — ships **Scrypt** (KDF), so no new dependency. Revocation already clears in-memory private material (`enter_revoked_state`) — App-lock reuses that shape, reversibly.
**Branch:** `kreds-app-lock` off `main`
**Product context:** the named device-custody follow-up from the forward-secrecy work; Windows-desktop-focused. Internal package stays `hearth`.

---

## Why

A thief with an *unlocked* device (or one left idle/asleep) reads whatever the app shows, and a stolen `keys.json` is plaintext today. App-lock closes both: a credential gates the running app (idle/sleep/manual lock, keys dropped from memory) **and** encrypts the key material at rest, so a stolen file is useless without the device + credential. "Not a UI-only gate" — the node literally cannot decrypt/sign while locked.

## Threat model (honest, drives the design)

There is **no server** — key material lives only on the user's device. Attack paths: local device access (walk-up/idle/asleep/stolen-while-off), a stolen `keys.json` file, or remote malware. App-lock's design: **the entropy lives in the device, not the credential**, so a short convenient credential is still strong at rest.

- **Protects:** the idle/asleep/walked-up-on running app (keys dropped from RAM, node refuses to serve); a stolen `keys.json` (encrypted; useless without the Windows-sealed device secret, then scrypt-slow even with it).
- **Does NOT protect:** malware running *as the user while unlocked* (reads what the app displays — same boundary as today's forward-secrecy docs); nothing defends a screenshot. OS-suspend detection is heuristic (wall-clock jump) until a packaged desktop wrapper can hook true suspend events.

## Decisions locked this session

- **Credential = user's choice of PIN or passphrase** (both are just an input string → same KDF). Pattern is a deferred later add (another input widget, no crypto change).
- **At-rest key binds credential + a Windows-sealed device secret.** A short PIN is safe at rest because the *device* carries the entropy.
- **Node boots locked** when App-lock is on; unlock is throttled; **no auto-wipe** (keys = identity).
- Auto-lock is **node-tracked** (reliable): idle timeout (Off/5/10/15 min), lock-on-sleep, manual lock.
- Per-device, local settings — never gossiped.

## Components

### 1. `hearth/applock.py` — crypto core (the security-critical unit; Claude tests hard)
- **DPAPI (ctypes, no dep):** `dpapi_seal(data: bytes) -> bytes` / `dpapi_unseal(blob: bytes) -> bytes` via `crypt32.CryptProtectData`/`CryptUnprotectData` (per-user scope, default; optional static app entropy). Non-Windows fallback for dev/tests: a `0600` keyfile under the data dir, with a logged warning that at-rest binding is weaker off-Windows (the product target is Windows).
- **Key derivation:** `pw = Scrypt(salt=salt, length=32, n=2**15, r=8, p=1).derive(credential.encode())`; `master = HKDF(SHA256, length=32, salt=device_secret, info=b"hearth-applock-v1").derive(pw)`. Master needs BOTH the credential and the (unsealed) 32-byte device secret. KDF params stored in the record for future-proofing.
- **`enable(secrets: dict, credential: str, cred_type: str) -> record`:** gen `device_secret = os.urandom(32)`, `salt = os.urandom(16)`; DPAPI-seal the device secret; derive master; `ct = ChaCha20Poly1305(master).encrypt(nonce, json(secrets), aad=b"hearth-applock")`; return the plaintext-safe record (below).
- **`unlock(record, credential) -> dict`:** unseal device secret → derive master → ChaCha20Poly1305 decrypt; a wrong credential → `InvalidTag` → raise `BadCredential` (the AEAD tag IS the verifier — no separate hash). 
- **`change_credential(record, old, new)`**, **`disable(record, credential) -> secrets`**.
- The **record** (`applock.json`, plaintext, non-secret): `{version, cred_type, kdf:{name:"scrypt",n,r,p,salt_hex}, sealed_device_secret_b64, nonce_hex, ct_hex, settings:{idle_minutes,lock_on_sleep}}`. Readable while locked (for boot + settings).

### 2. Node lock-state + boot-locked (`node.py`, `identity.py`)
- **keys.json split when App-lock is ON:** `keys.json` keeps only non-secret fields + `"applock": true`; the secret bundle (`identity_priv`, `enc_priv`, `retired_enc` privs, `storage_key`) lives ONLY inside `applock.json`'s ciphertext. Plaintext secrets no longer exist on disk.
- **Boot:** `HearthNode.__init__` — if `applock.json` present → load keys.json (non-secret) into a `DeviceKeys` with private fields `None`, set `self.locked = True`, hold no master key. The demo (no `applock.json`) is unchanged.
- **`node.unlock(credential)`:** `applock.unlock` → inject `identity_priv`/`enc_priv`/`retired_enc` privs/`storage_key` into the in-memory `DeviceKeys`; keep the **master key in memory** (so secret-field changes can be re-persisted); `self.locked = False`; reset `last_activity`. Wrong credential raises (→ throttled 401/423).
- **`node.lock()`:** drop the private material from `DeviceKeys` (identity_priv, enc_priv, retired privs, storage_key → None) + drop the master key; `self.locked = True`. Reversible (no `revoked` flag, no wipe).
- **Persisting secret changes while unlocked:** forward-secrecy enc-key rotation mutates `retired_enc`/`enc_priv` and saves keys. When App-lock is on + unlocked, that save **re-encrypts the secret bundle** into `applock.json` using the in-memory master (a `_save_keys` that routes secrets to applock when enabled, non-secrets to keys.json). Locking mid-session never loses a rotation (it was persisted on rotation).

### 3. API + throttle + auto-lock (`api.py`, `node.py`)
- **Locked guard:** while `node.locked`, every `/api/*` returns **423 Locked** EXCEPT `POST /api/unlock`, `GET /api/applock` (status), and static assets. (A small dependency/guard, not per-route edits.)
- **Endpoints:** `POST /api/unlock {credential}` (throttled); `POST /api/lock`; `GET /api/applock` → `{enabled, locked, cred_type, settings, throttle_wait}`; `POST /api/applock/setup {credential, cred_type}` (enable — requires unlocked/keys present); `POST /api/applock/settings {idle_minutes, lock_on_sleep}`; `POST /api/applock/change {old, new}`; `POST /api/applock/disable {credential}`.
- **Throttle:** in-memory failed-attempt counter + escalating next-allowed time (e.g. ≥3 fails → 5s, then 30s, then 5 min), returned as `throttle_wait`. In-memory is sufficient: offline file brute-force is already blocked by the DPAPI secret; this only slows the online prompt.
- **Auto-lock (node-side):** `last_activity` updated on any authenticated API call; a periodic check (fold into the existing gossip loop or a small timer) locks when `now - last_activity > idle_minutes` (idle) OR when the loop observes a **wall-clock jump** larger than its interval + a margin and `lock_on_sleep` is on (process was suspended → lock on resume). `idle_minutes = 0` / "Off" disables the idle timer.

### 4. Client (`web/app.js`, `index.html`, `style.css`) — August tests the UX
- **Lock screen:** on load and on any `423`, `GET /api/applock`; if `locked`, render a full-screen credential entry (PIN keypad or passphrase field per `cred_type`) gating the entire app; on success (`POST /api/unlock`) load normally; show `throttle_wait` countdown on failure.
- **Settings section** (in the profile/Me settings area): enable App-lock (choose PIN or passphrase + confirm), idle-timeout select (Off/5/10/15), lock-on-sleep toggle, change credential, disable (requires current credential), and a **"Lock now"** button.
- **Client-side belt-and-suspenders:** on `visibilitychange`→hidden or a detected wall-clock gap, proactively re-check `/api/applock` (the node is the source of truth; the client just reflects/nudges).

## Testing

Claude-owned (crypto/security — test hard):
- `applock.py`: enable→unlock round-trips; **wrong credential fails** (`BadCredential`); tampered ct fails; the sealed device secret is required (unlock fails if the sealed blob is corrupted/absent); DPAPI seal/unseal round-trips on Windows; the fallback path works off-Windows; KDF params round-trip; `change`/`disable` correct.
- Node: with App-lock on, **`keys.json` on disk contains NO plaintext secrets** (assert the secret fields are absent/None); a **locked node cannot decrypt a post or sign** (a content/compose call raises or the private material is None); `unlock` restores full function; `lock()` re-drops it; an enc-key rotation while unlocked re-persists into `applock.json` and survives a lock→unlock cycle; boot with `applock.json` starts locked.
- API: locked node returns 423 for content endpoints, 200 for unlock/status; wrong-credential unlock throttles (increasing `throttle_wait`); setup/disable/change flow; the demo (no applock) is unaffected — full suite stays green.

August-owned (UX — hand a checklist): lock screen appears + unlocks; PIN and passphrase both work; idle timeout locks after the set minutes; lid-close/sleep locks on resume; "Lock now"; change/disable; settings persist per-device.

## Out of scope (named)

- Pattern credential (later input widget, no crypto change); biometric (mobile/OS keystore); true OS-suspend hooks (needs the packaged desktop wrapper — heuristic wall-clock detection for now); syncing lock settings across devices; multi-user on one node; auto-wipe on failed attempts (deliberately rejected).

## Success criteria

- App-lock can be enabled with a PIN or passphrase; the node then boots locked, refuses all content APIs (423) until unlocked, and `keys.json` holds no plaintext secrets; unlocking restores full function; a stolen `applock.json`+`keys.json` cannot be decrypted without the Windows-sealed device secret, and even with it faces scrypt + the credential; idle/sleep/manual auto-lock work; no auto-wipe; the non-App-lock demo path is unchanged; crypto tests + full suite green.
