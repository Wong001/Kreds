# Android First-Load + Phone↔Desktop Pairing Ceremony — Design

Date: 2026-07-22. Slice 1 of the onboarding arc (decided with August: link-first,
create-later; full pairing — identity_priv ships; QR + typed-code fallback;
approach A — reuse hearth's pairing triplet over a new Tor wire frame).

## Goal

A fresh Android install greets the user with a first-load screen: **Link to
your node** (live) or **Start a new profile** (coming-soon stub, arc 2).
Linking runs a real user ceremony — scan a QR (or type a code) shown by the
desktop's new "Add device" screen — and ends with the phone enrolled as a
full device of the identity (device cert + identity_priv custody), synced
and dropped into the normal web UI. This replaces the adb-pushed dev
fixture and closes the desktop's own documented gap (its first-run screen
references a "Settings → add device" UI that doesn't exist; pairing
acceptance currently requires the CLI).

## The model (decided)

- **Full pairing:** the phone receives hearth's standard pairing package —
  `{cert, identity_priv, friends, peers, my_addr}` (node.py:1966-1987) —
  exactly what a second desktop gets. The phone becomes an equal device:
  future arcs (create-on-phone, phone-enrolls-desktop "vice versa") build
  on this custody. NOTE: nothing in the phone's compose paths changes —
  all writes remain device-signed; identity_priv enters custody only.
- **Ceremony trust:** the QR/code = `{desktop onion address, one-time
  pairing code}`. The code is a bearer authorization: single-use, short
  TTL (10 min), minted by the desktop's Add-device screen. The Tor onion
  channel provides transport encryption + server authenticity (the onion
  address IS the server's key). A **desktop confirm step** ("<device name>
  wants to link — Accept / Deny") gates `accept_pairing`, so a
  photographed QR alone cannot silently enroll (the attacker's request
  surfaces on the desktop screen for a human decision).

## Ceremony protocol (hearth shapes reused verbatim)

1. **Desktop, Add-device screen** (new UI in the me-strip Devices section,
   app.js:3346-3363 area): `POST /api/pair/begin` → node mints
   `pairing_code` (random, base58-short), stores `{code_hash, expires_at}`
   (single-use, 10-min TTL, in-memory on the node object), returns the
   combined **link string** = invitecodec-style base58 of
   `{gossip_addr, code}` (reuse `hearth/invitecodec.py` encoding
   mechanics). UI renders it as QR (existing `/api/qr`) + the same string
   as typeable text. Screen polls `GET /api/pair/pending` for an arriving
   request and offers Accept/Deny.
2. **Phone, first-load screen:** user scans the QR (expo camera/barcode —
   new dependency, camera permission added to the manifest) or types the
   code. Phone decodes `{onion, code}`, generates its device keys (the
   Kotlin analog of `pair_request`, node.py:1956-1964: fresh Ed25519
   device keypair + device_name; default name e.g. "phone" + model,
   user-editable on the screen), dials the onion over Tor (existing
   TorEngine.dial), and sends a new wire frame:
   `{"t":"hearth-pair-request", "protocol":..., "device_pub":...,
   "device_name":..., "code":...}` — the standard pair-request shape
   (node.py:1961-1964) plus the code.
3. **Desktop node, wire handler** (hearth prod change, sync server): on a
   pair frame — verify code (constant-time hash compare, unexpired,
   unused; else reject-close), hold the request as *pending*, surface it
   to `GET /api/pair/pending`. On the human's Accept:
   `node.accept_pairing(request_json)` (node.py:1966-1987, UNCHANGED — it
   already enrolls, saves views, notifies) and the resulting package
   returns over the SAME held connection; code marked used. On Deny (or
   TTL): error frame, nothing enrolled. The held connection has a hard
   server-side bound (the code's remaining TTL, max 10 min) after which
   it closes with the expired error; the phone shows a "waiting for your
   desktop to accept…" state with its own matching timeout + retry.
4. **Phone, install** (Kotlin `pair_install` analog, node.py:1990-2010
   semantics on the phone's store): verify `t=="hearth-pair-package"`;
   `DeviceKeys.install` analog — persist device keys + cert +
   identity_priv; `store.addIdentity(identity, is_self)`, save own device
   view, add friends as known identities, add peers + `my_addr` as sync
   peers. Then jump to WebShell; background sync drains history. The
   success screen says honestly: "linked — your history will fill in over
   the next syncs" (old-device grant sweeps + round-trip,
   maintain_own_device_grants, are what make history decryptable — same
   drip-in behavior as the vp3 blob drain).

## Storage (phone)

- New canonical location: **app-internal storage** (Context.filesDir)
  `pairing.json` = `{device_priv, device_pub, cert, identity_priv,
  onion_addr}` — internal, not the external adb-visible dir.
- `fixtureOrNull()` reads: internal first, then the legacy external
  `spike_phone_fixture.json` (the G20 keeps working; no migration step).
  identity_priv is NOT part of `KotlinHandshake.Fixture` (nothing consumes
  it yet) — it is stored, never loaded into the fixture object, until the
  arc that needs it. App-lock-style sealing of identity_priv on the phone
  = explicit follow-up ticket, not this slice.

## First-load screen (RN)

- New native bridge: `hasIdentity(): Boolean` (fixture readable from
  either location). `index.ts` gate: no identity → `FirstLoad` component;
  else `WebShell` (unchanged).
- FirstLoad: Kreds wordmark, two buttons — **Link to your node** →
  scanner/code entry → progress states (dialing / waiting for accept /
  installing / linked) with distinct, retryable error states (bad code,
  expired, denied, unreachable); **Start a new profile** → coming-soon
  note (arc 2). Visual bar: minimal-but-clean; the real visual-parity pass
  stays its own future slice (standing decision).

## Desktop changes (hearth prod — the slice's second half)

- `POST /api/pair/begin`, `GET /api/pair/pending`, `POST
  /api/pair/accept` (accept/deny verdict on a pending request; wraps the
  existing `node.accept_pairing`) — UI-facing.
- Sync-server wire handling for the pair-request frame (code-gated,
  pre-auth: a pairing peer has no device cert yet, so the frame must be
  accepted BEFORE the normal authenticated handshake path — scoped
  strictly: unauthenticated connections can ONLY submit a pair frame,
  everything else still requires AUTH).
- Add-device UI in the Devices section + fix the first-run "Connect"
  screen's stale instruction text (it currently points at a UI that now
  actually exists).
- Desktop↔desktop copy-paste pairing keeps working unchanged (bootstrap
  endpoints untouched); the new wire path is additive.

## Honest limits

- **identity_priv on the phone**: a stolen/compromised phone now holds the
  identity root key. Device revocation (existing) removes network trust
  but cannot un-know an exfiltrated key. Mitigations: ceremony transport
  is onion-encrypted end-to-end; at-rest sealing = ticketed follow-up
  (App-lock analog on phone); the paper seed remains desktop-side.
- **The QR is a 10-minute bearer secret** for *requesting* enrollment; the
  desktop Accept step is the human backstop. A user who both leaks the QR
  and blind-accepts an unexpected device name defeats it — the Accept
  dialog therefore shows the device name prominently.
- **History is not instant** after linking (grant-sweep round-trip);
  stated in the success UI, not hidden.
- Un-linking = existing desktop device revocation; no phone-side "log
  out" in this slice (ticket).
- Camera permission is requested only when the user taps Link (not at
  install).

## Testing / gates

- **hearth (pytest):** pairing-code mint/TTL/single-use; wire pair frame
  happy path + wrong/expired/reused code + deny; unauthenticated
  connections restricted to pair frames only; existing pairing tests stay
  green (desktop↔desktop unchanged).
- **Kotlin JVM:** pair-request frame shape (byte-parity vs
  node.py:1961-1964 + code field); package install semantics (store
  state: identity, views, friends, peers, my_addr precedence); fixture
  dual-location read (internal-first, legacy fallback); link-string
  decode (base58 → onion+code, malformed → typed-friendly error).
- **Loopback pairing gate (the parity proof):** a REAL hearth node with
  the wire handler; the Kotlin client runs the FULL ceremony over
  loopback (request → accept → package → install), then performs a real
  authenticated SYNC with its new device cert and pulls seeded content —
  proving the enrolled device actually works, not just that bytes moved.
  Deny + expired-code paths asserted as clean failures.
- **On-device DoD (August drives):** fresh install (or cleared storage) →
  first-load screen appears; desktop Add-device shows QR; scan → accept
  on desktop → phone lands in web UI; history fills after a sync round;
  typed-code fallback works; deny path shows the right error; legacy G20
  fixture still boots straight to WebShell; camera permission prompts
  only on Link.

## Out of scope (arc 2+ / tickets)

Create-new-profile on phone (button stubs to coming-soon); phone-enrolls-
desktop ("vice versa"); at-rest sealing of identity_priv on the phone —
August's direction (2026-07-22) for that later arc: consider an opt-in
panic-wipe ("nuke profile after N failed unlock attempts") or similar
alongside an App-lock analog — its own design conversation;
phone-side unlink/log-out; iOS; visual-parity polish of the first-load
screen; multi-QR/NFC channels.
