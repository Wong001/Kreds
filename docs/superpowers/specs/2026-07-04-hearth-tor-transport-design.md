# Hearth Tor Transport — Design ("Loop over onions v1")

**Date:** 2026-07-04
**Status:** Approved (design discussion + spike, this session)
**Basis:** Concept doc v0.4 D1 (home node as onion service; standing requirement: metadata-blind transport); ROADMAP "Path to shippable — Tor"; feasibility spike `docs/spikes/2026-07-03-tor-feasibility-spike.md` (copied from `hearth_tor_spike/SPIKE_REPORT.md`)
**Branch:** `tor-transport` off `main`
**Workstream:** 3 of 5 in the session ordering (1 deletion DONE, 2 forward secrecy DONE, 3 Tor, 4 app-lock, 5 unfriend)

---

## Spike findings this design stands on (measured, not assumed)

- Tor Expert Bundle windows-x86_64 v15.0.17: 21.3 MB download, tor.exe 10.2 MB,
  ~77 MB RAM running, zero firewall prompts (loopback-only), clean
  `terminate()` shutdown 10/10.
- Bootstrap: 18.46 s cold, 2.55 s warm (cached consensus).
- Onion service publish to reachable: 10-47 s; four services on one Tor
  process work without interference (ADD_ONION accepted in 0.08 s).
- Real Hearth gossip over onions: 12/12 sessions succeeded through a
  hand-rolled asyncio SOCKS5 dialer; dial latency 8-43 s, avg 14.2 s.
- **Onion identity is stable:** ADD_ONION with a saved ED25519-V3 key blob
  republishes a byte-identical ServiceID on a fresh tor process with a fresh
  DataDirectory (~20 s to reachable). The key blob is impersonation-grade
  secret material.

## Decisions (locked this session)

1. **Cadence: slow interval now, persistent connections later.** Onion peers
   sync on `ONION_SYNC_INTERVAL = 45.0` s; ip:port peers keep the existing
   3 s loop. One-hop message latency lands around a minute — acceptable and
   stated. Persistent connections (dial once, keep the socket, repeat
   sessions) are the NAMED follow-up optimization, not built now.
2. **Bundling: the user never "downloads Tor".** The tor manager resolves
   the binary in this order: **(a) bundled with the app** (future packaged
   desktop app drops tor.exe into the bundle — zero code change), **(b)
   already cached** in app data, **(c) pinned first-run download** from
   dist.torproject.org (exact version + SHA256 pinned in code; spike used
   15.0.17 — implementer pins the current version and its checksum from the
   torproject checksums file at implementation time), extracted to
   `%LOCALAPPDATA%\Loop\tor\<version>\`. Hash mismatch = hard refusal.
   Desktop app packaging itself is a separate roadmap item (added there).
3. **Reachability: dual-stack, onion-preferred.** Peer addresses may be
   `host:port` or `<id>.onion:port`. Dial .onion via SOCKS5 whenever that is
   the known address; plain TCP only for explicitly-local peers (demo/LAN).
   **Privacy guardrail:** in Tor mode a node publishes ONLY its onion
   address as `gossip_addr`, and the have-frame peer exchange never
   propagates a non-onion address for a peer for which an onion address is
   known.
4. **Process model: one Tor per machine, shared.** Nodes each publish their
   own onion service on the shared process (spike-proven 4-on-1). Per-node
   Tor isolation is not security-relevant on a single machine.

## Components

### 1. `hearth/socks.py` — asyncio SOCKS5 dialer

Minimal SOCKS5 CONNECT (no auth, ATYP=domain for .onion), hardened from the
spike version: timeouts on every read (the spike found and fixed a hang on an
untimed CONNECT-reply read), errors surfaced as `OSError` so the existing
peer-offline handling in `SyncService.sync_with` works unchanged.
`socks_connect(socks_host, socks_port, dest_host, dest_port, timeout) ->
(reader, writer)`.

### 2. `hearth/tor.py` — tor manager

- `ensure_tor_binary() -> Path`: resolution order bundled -> cached ->
  pinned download (URL + SHA256 constants; refuses on mismatch; extracts
  only what tor needs, not the full 73 MB bundle).
- `TorProcess`: launch with generated torrc (SocksPort/ControlPort on
  loopback with auto or configured ports, CookieAuthentication 1, private
  DataDirectory under app data), await "Bootstrapped 100%" with timeout,
  `stop()` = terminate with grace then kill. One instance shared per
  machine/demo cast.
- `publish_onion(tor, local_port, key_blob=None) -> (service_id, key_blob)`:
  control-port AUTHENTICATE (cookie) + ADD_ONION ED25519-V3; NEW key on
  first run, saved blob thereafter. Raw control protocol over asyncio (no
  stem dependency in product code — the spike validated the protocol; stem
  stays spike-only).

### 3. Onion identity per node

- Key blob persists as `onion_key` in the node's data dir. Treated as key
  material: never logged, never gossiped, **wiped by revocation
  self-logout** (`enter_revoked_state` deletes it; a revoked device's onion
  address dies with it).
- `gossip_addr` in Tor mode = `<service_id>.onion:<virtual port>`.

### 4. `hearth/transport.py` — TorTransport

- `TorTransport(socks_port)`: `connect(address)` -> SOCKS5 dial when the
  host ends with `.onion`, `asyncio.open_connection` otherwise (dual-stack);
  `serve(host, port, handler)` unchanged local listen (the onion service
  maps onto it). Same tiny seam; `SyncService` takes it via the existing
  `transport=` parameter. Zero sync-protocol change.

### 5. Cadence in the gossip loop

Per-peer scheduling: track last-sync-attempt per address; dial `.onion`
peers when `ONION_SYNC_INTERVAL` has elapsed, others every round (3 s loop
unchanged). Constants module-level, overridable in tests.

### 6. Runner + demo

- `run_node(..., tor=False)`: when True — ensure binary, start/attach shared
  TorProcess, publish onion from the node's saved key, set onion
  `gossip_addr`, use TorTransport.
- `python -m hearth demo --tor`: the 4-node cast over onions on one Tor
  process. Plain `demo` stays fast localhost TCP (stated in README). Demo
  prints the four onion addresses and expected latency honestly (first sync
  can take ~a minute).

### 7. Honest docs

- README: new Tor section — what Tor mode gives (transport encryption +
  endpoint authentication to the onion service + metadata-blind routing, on
  top of existing device-key AUTH), what it costs (~21 MB first-run
  download unless bundled, ~1-minute message latency, 10-47 s service
  publish on start), and that plain `demo` remains plaintext localhost.
- ROADMAP: honest-status "Transport is plaintext TCP" bullet rewritten
  (plaintext is now the demo default, not the ceiling); "Tor" moves from
  Path-to-shippable to shipped; persistent connections + desktop app
  packaging (bundle tor.exe) added as named follow-ups.

## Testing

Unit (no network, default suite):
- SOCKS5 dialer against an in-process fake SOCKS server: success path,
  refusal (reply != 0x00), truncated reply, timeout -> OSError.
- `ensure_tor_binary`: cached hit short-circuits; hash mismatch on a local
  fixture file refuses; bundled path wins over cache.
- Onion key: persist/load round-trip; `enter_revoked_state` deletes it.
- TorTransport.connect routing: `.onion` -> SOCKS path, `host:port` -> TCP
  (fake SOCKS server observes the dial).
- Cadence: onion peer skipped until interval elapses, TCP peer dialed every
  round (injected clock).

Integration (real Tor, network — gated behind `TOR_E2E=1`, skipped by
default so the standard suite stays fast and offline):
- Port of the spike's money test: two HearthNodes, real tor process, onion
  publish, gossip a post and a DM both ways over onions.
- Stable identity: stop tor, relaunch, republish from saved key, same
  ServiceID, sync still works.

Manual smoke (recorded in plan, run once before merge): `demo --tor` on this
machine — post + DM traverse all four nodes over onions.

All existing 194 default-suite tests stay green.

## Out of scope (stated)

- Persistent connections over Tor (named follow-up).
- Pluggable transports / bridges (censorship circumvention).
- Tor for the localhost HTTP UI (stays loopback-only).
- Desktop app packaging (own roadmap item; this design is bundle-ready via
  the binary resolution order).
- Mobile clients / iOS notification fork (concept doc, unchanged).
- macOS/Linux tor binaries (resolution order + manager are OS-agnostic in
  shape; only the windows-x86_64 pin ships now — stated in README).

## Success criteria

- `demo --tor`: all four nodes reachable over onions; a post and a DM
  propagate end-to-end; second run reuses the same four onion addresses
  (persistence).
- Default test suite runs offline, green, unchanged count + new unit tests.
- `TOR_E2E=1` suite passes on this machine.
- README/ROADMAP state the new honest boundary (metadata-blind transport in
  Tor mode; plaintext demo default; latency and download costs).
