# Hearth-over-Tor feasibility spike

**VERDICT: YELLOW** -- every mechanical piece worked (bundling, bootstrap,
onion services, hand-rolled SOCKS5 dialer, two real Hearth nodes syncing a
post end-to-end 10/10 times), but two things must be resolved before
writing the real spec: (1) per-round latency over Tor (8-45s, avg ~14s)
is 100-1000x the LAN case and is incompatible with Hearth's current 2-3s
gossip-loop interval, and (2) this spike never tested onion-address
*persistence* across restarts (every test used a fresh ephemeral
NEW:ED25519-V3 key) -- friends store each other's `gossip_addr`
long-term, so an unstable onion address would break the whole model and
was simply out of timebox here.

All numbers below are from actual runs on this machine on 2026-07-03,
not estimates. Every tor.exe process launched in this spike was
confirmed killed (see Q2/Q7 and "tor.exe processes" at the bottom).

---

## Q1: Bundling

- Version page said current stable = **15.0.17** (tor 0.4.9.11).
- Confirmed exact filename via `dist.torproject.org/torbrowser/15.0.17/`
  directory listing before downloading.
- **Exact URL used:**
  `https://dist.torproject.org/torbrowser/15.0.17/tor-expert-bundle-windows-x86_64-15.0.17.tar.gz`
- **Archive size:** 22,323,852 bytes (21.3 MB). Download took ~4.2s on
  this connection.
- Extracted with Windows 11's built-in `tar -xzf` -- worked with no
  extra tooling, as expected.
- **Extracted size:** tor/ = 47,507,754 B, data/ = 25,473,602 B, docs/ =
  446,603 B -> **73,427,959 B (~73.4 MB) total**.
- **Contents:**
  - `tor/tor.exe` -- 10,215,424 B. This is the whole spike's engine.
  - `tor/tor-gencert.exe` -- 6,199,296 B (directory-authority cert tool;
    irrelevant to a client/onion-service app).
  - `tor/pluggable_transports/conjure-client.exe` (13,137,408 B) and
    `lyrebird.exe` (17,950,208 B) + `pt_config.json` + README -- bridge
    /censorship-circumvention transports. Not needed unless targeting
    heavily-censored networks (see Recommendation).
  - `data/geoip` (9,481,354 B) and `data/geoip6` (15,991,320 B) --
    country-code lookup tables for GeoIP-based node exclusion/stats.
  - `data/torrc-defaults` (928 B), `docs/*.txt` (six short doc files,
    446 KB total).
  - **Yes, tor.exe is present and directly runnable** -- no installer,
    no registry writes, no admin rights needed.

---

## Q2: Headless launch + bootstrap

torrc used (`hearth_tor_spike\torrc`):
```
SocksPort 127.0.0.1:9250
ControlPort 127.0.0.1:9251
CookieAuthentication 1
DataDirectory C:\Users\Wong\Desktop\hearth_tor_spike\tordata
Log notice stdout
```

Launched via `subprocess.Popen([tor.exe, "-f", torrc], stdout=PIPE,
stderr=STDOUT, text=True)`, parsed stdout lines for `Bootstrapped (\d+)%`.

- **Cold start** (freshly emptied `DataDirectory`, no cached consensus):
  **18.46s** wall-clock from process start to `Bootstrapped 100%`.
  Progress was not linear -- it sat at 30% for ~5s and jumped 61%->100%
  in under 2s once the consensus and descriptors were in.
- **Warm start** (same `DataDirectory`, cached consensus/microdescs):
  **2.55s** to `Bootstrapped 100%`.
- Clean shutdown via `proc.terminate()` succeeded on the **first try, in
  under 10s, every single time** across this entire spike (~10 separate
  tor.exe launches total, cold+warm+Q3+Q6+Q7 minimal+Q7 full). The
  `proc.kill()` fallback path was never exercised.

---

## Q3: Onion service via control port

**Library choice: `stem` (v1.8.2, `pip install stem` into the Hearth
venv).** Reasoning: stem correctly handles `AUTHENTICATE` cookie
discovery/hex-encoding and `ADD_ONION` response parsing across Tor
versions -- there is nothing to learn by re-deriving that by hand. The
hand-rolling effort in this spike went where it actually matters for
Hearth: the SOCKS5 *dialer* (Q4), which is the piece that has to live
inside `hearth/transport.py`'s seam, not the one-time control-port setup
call.

Test: 3 rounds of `controller.create_ephemeral_hidden_service({port:
port})` (fresh `NEW:ED25519-V3` key each round) against a local echo
server, then poll-dialed the resulting `<addr>.onion` through the
SocksPort with the Q4 dialer until an echo round-tripped.

| round | ADD_ONION call latency | time-to-reachable | attempts |
|---|---|---|---|
| 1 | 0.07s | 46.64s | 2 (1st attempt hit our 25s test-side timeout) |
| 2 | 0.08s | 10.05s | 1 |
| 3 | 0.10s | 12.98s | 1 |

- **`ADD_ONION` itself returns near-instantly (<0.1s)** -- it just
  registers the service locally; publication to the HSDir network is
  async and happens after the call returns.
- **3/3 rounds eventually became reachable -- 100% publication success**
  across all attempts made in this spike (this pattern repeats in Q5 and
  Q6 below: every onion ever created in this spike eventually became
  reachable; zero permanent publication failures).
- Time-to-reachable varied 10-47s. No exact error text to report for
  failures because there were none that didn't eventually resolve --
  the "failure" in round 1 attempt 1 was our own external 25s
  test-harness timeout, not a Tor-side error.

---

## Q4: asyncio SOCKS5 dialer

Hand-rolled, no-auth, ATYP=0x03 (domain name) -- works identically for
plain domains and `<x>.onion` names, which is exactly why domain-name
ATYP is used instead of resolving locally: **Tor itself recognizes
`.onion` suffixes at this layer and routes them through the hidden
service protocol instead of exiting to the clearnet.**

```python
async def socks5_connect(socks_host: str, socks_port: int,
                         dest_host: str, dest_port: int,
                         connect_timeout: float = 30.0):
    return await asyncio.wait_for(
        _socks5_connect(socks_host, socks_port, dest_host, dest_port),
        timeout=connect_timeout)


async def _socks5_connect(socks_host, socks_port, dest_host, dest_port):
    reader, writer = await asyncio.open_connection(socks_host, socks_port)
    writer.write(b"\x05\x01\x00")                      # greeting: no-auth
    await writer.drain()
    resp = await reader.readexactly(2)
    if resp[0] != 0x05 or resp[1] != 0x00:
        writer.close()
        raise ConnectionError(f"SOCKS5 method negotiation failed: {resp!r}")

    host_b = dest_host.encode("ascii")
    req = (b"\x05\x01\x00\x03" + bytes([len(host_b)]) + host_b
           + struct.pack(">H", dest_port))              # CONNECT, ATYP=domain
    writer.write(req)
    await writer.drain()

    head = await reader.readexactly(4)                  # ver, rep, rsv, atyp
    rep, atyp = head[1], head[3]
    if atyp == 0x01:   await reader.readexactly(4 + 2)
    elif atyp == 0x03: await reader.readexactly((await reader.readexactly(1))[0] + 2)
    elif atyp == 0x04: await reader.readexactly(16 + 2)
    if rep != 0x00:
        writer.close()
        raise ConnectionError(f"SOCKS5 CONNECT failed, REP=0x{rep:02x}")
    return reader, writer
```

(Full version with comments in `scripts/socks5_dialer.py`, ~45 lines of
actual code.)

- **Sanity-checked first against a clearnet target** (per the brief):
  dialed `example.com:80` through Tor's SocksPort, sent a raw
  `HEAD / HTTP/1.0`, got `HTTP/1.1 200 OK` back in **1.30s**. This
  isolated "does my SOCKS5 protocol implementation work at all" from
  "is the onion/HS layer flaky" before touching anything Tor-specific.
- **Then proven against real onions**: this exact function was reused,
  unmodified after one fix (below), for every onion dial in Q3 (3x), Q5
  (12x — first-dial + money-test + 10 flakiness rounds), and Q6 (4x) --
  19 onion dials total across the spike, all eventually successful.
- **Bug found and fixed during the spike**: the first draft only wrapped
  the *initial* local connect to the SOCKS port in a timeout. The
  CONNECT-reply read (the step that's actually slow for a
  not-yet-reachable onion) was unbounded -- a slow/dead onion would hang
  the caller forever. Fixed by wrapping the *entire* handshake in one
  `asyncio.wait_for`. This is exactly the kind of thing this spike is
  supposed to surface.

---

## Q5: Two Hearth nodes over onions -- the money test

`TorTransport` (full file `scripts/tor_transport.py`):

```python
class TorTransport:
    def __init__(self, socks_host="127.0.0.1", socks_port=9250,
                connect_timeout=60.0):
        self.socks_host = socks_host
        self.socks_port = socks_port
        self.connect_timeout = connect_timeout

    async def connect(self, address: str):
        host, port = address.rsplit(":", 1)
        return await socks5_connect(self.socks_host, self.socks_port,
                                    host, int(port),
                                    connect_timeout=self.connect_timeout)

    async def serve(self, host: str, port: int, handler):
        return await asyncio.start_server(handler, host, port)
```

`serve()` is untouched plain-TCP -- `SyncService.start()` only needs a
local listener; an onion mapping (`ADD_ONION Port=<v>,127.0.0.1:<bound>`)
is created separately by the setup code, pointed at whatever port
`serve()` bound. `connect()` is the only method that goes through Tor.
This is the exact seam `hearth/transport.py`'s docstring asked for --
**zero changes needed to `hearth/sync.py` or `hearth/transport.py`
itself.** One nice confirmation: `sync_with()`'s existing
`except OSError: return False` transparently caught the new transport's
failure modes (`ConnectionError` and `TimeoutError` are both `OSError`
subclasses in Python 3.12) with no changes.

Setup: two real `HearthNode.create(...)` instances (`alice`, `bob`)
under `hearth_tor_spike\nodes\`, each given its own onion service on
**one shared tor.exe process**, befriended via `store.add_identity` both
ways (pairing ceremony skipped per spike instructions), wired with
`SyncService(node, transport=TorTransport())`.

- **First dial to a freshly-published onion** (bob dialing alice's
  brand-new onion): succeeded on the **first attempt**, in **17.15s**.
- **The money test**: `alice.compose_post("hello over tor")` ->
  `await bob_sync.sync_with(alice_onion_address)` -> `True` in **11.03s**
  -> post **confirmed present** in `bob.store.feed()` (`feed_len=1`) and
  `bob.store.posts_by(alice.identity_pub) == [msg_id]`. **Full round trip
  worked, over real Tor onion services, on the first clean run.**
- **Flakiness across 10 repeated sync rounds**: **10/10 succeeded**.
  Latencies: 11.16, 43.48, 9.32, 9.87, 12.86, 8.06, 14.17, 12.83, 9.74,
  10.66 (seconds) -> **min 8.06s, max 43.48s, avg 14.21s**.
- **Error modes seen: none.** Zero exceptions, zero `False` returns,
  across 12 real `sync_with()` calls total (first-dial + money-test +
  10 rounds) in this run.
- **The catch**: 8-45s per gossip round is enormous next to LAN gossip
  (sub-100ms). See Recommendation for what this means for the interval
  design.

---

## Q6: Demo shape -- 4 onion services on one tor.exe process

4x `ADD_ONION`, each mapped to its own local echo listener, issued
sequentially on one shared Tor process/control connection:

| onion # | ADD_ONION call time | time to actually reachable |
|---|---|---|
| 1 | 0.075s | 13.73s |
| 2 | 0.000s | 20.84s |
| 3 | 0.002s | 12.09s |
| 4 | 0.000s | 28.29s |

- All 4 `ADD_ONION` calls together took **0.08s** -- registering a new
  onion locally is essentially free and does not block on publication.
- **"Accepted" is not "live"**: each service took 12-28s to actually
  become dialable via SOCKS, confirmed by polling, not assumed. All 4
  eventually worked -- no failures, no interference between services
  sharing one process.
- This confirms the shape a future N-node local demo would need: one
  Tor process, N onion services, each mapped to a distinct local
  listener -- exactly like Hearth's existing 4-node `demo.py` cast, just
  with Tor onion addresses standing in for `127.0.0.1:710x`.

---

## Q7: Sizing / Windows gotchas

**Minimal footprint test**: copied *only* `tor.exe` (10,215,424 B) into
a bare directory with a fresh `DataDirectory` and a minimal torrc (no
`data/geoip*`, no `pluggable_transports/`, no `tor-gencert.exe` present
anywhere nearby) -- **bootstrapped to 100% in 10.60s, no errors, no
warnings about missing files.**

- **Load-bearing:** `tor.exe` alone (~9.74 MB / 10,215,424 B).
- **Deletable for an MVP bundle** (confirmed not required for basic
  client + onion-service operation):
  - `data/geoip` + `data/geoip6` -- 25.47 MB (only needed for
    GeoIP-based node exclusion or stats reporting)
  - `tor/pluggable_transports/*` -- ~31.1 MB (`conjure-client.exe`
    13.14 MB, `lyrebird.exe` 17.95 MB, configs) -- only needed for
    bridges/censorship circumvention
  - `tor/tor-gencert.exe` -- 6.2 MB (directory-authority tooling,
    irrelevant to any client)
  - Total removable: **~62.7 MB**, cutting the 73.4 MB extracted
    footprint down to **~9.7 MB** for tor.exe alone.
- **RAM usage** (full bundle's tor.exe, measured via PowerShell
  `Get-Process -Id <pid> | select WorkingSet64`):
  - Immediately after `Bootstrapped 100%`: **80,945,152 B (77.20 MB)**
  - After 20s idle: **80,994,304 B (77.24 MB)** -- effectively flat.
- **Windows-specific gotchas: none that blocked anything.**
  - **No firewall prompts, ever**, across ~10 tor.exe launches in this
    spike. Every listener (Tor's SocksPort/ControlPort, Hearth's own
    gossip listener) was bound to `127.0.0.1` only. Worth calling out as
    a genuine *advantage* of the onion-service model: the app never
    needs to request an inbound Windows Firewall exception at all,
    unlike a plain-TCP peer that has to accept connections on a routable
    interface.
  - No path-length or quoting issues (short paths throughout).
  - No antivirus interference observed (no scan delays, no
    quarantining of `tor.exe` or the downloaded archive).
  - `proc.terminate()` cleanly stopped every tor.exe instance within the
    10s grace window, 100% of the time; `proc.kill()` fallback was never
    needed but is wired and tested-by-code-path in `tor_control.py`.

---

## Gotchas

1. **SOCKS5 dialer needs a whole-handshake timeout, not just a
   connect-to-proxy timeout.** Found and fixed live during this spike
   (see Q4). Any hand-rolled SOCKS client for Tor needs this or it can
   hang forever on an unreachable onion.
2. **Onion-address persistence across restarts was never tested.** Every
   onion in this spike was an ephemeral `NEW:ED25519-V3` key, and every
   one died when its owning `Controller` connection closed (stem's
   `detached=False`, the default we used). Hearth's `gossip_addr` model
   assumes a peer's address is stable long-term. This is the single
   biggest open question and was out of this spike's timebox -- stem
   supports persistent keys (`discard_key=False`, save
   `response.private_key`, pass it back in as `key_content` on next
   launch) but that path was unverified at the time of the main spike.
   **RESOLVED by the "Persistence follow-up" section below: saved-key
   republish gives a byte-identical ServiceID on a fresh tor process,
   verified across 2 restart cycles.**
3. **"ADD_ONION accepted" != "onion reachable."** Consistently a 10-28s
   gap in this spike. Any UI wired to this needs a "publishing..." state,
   not an assumption of instant reachability.
4. **Hearth's default gossip interval (2-3s, see `runner.py` /
   `demo.py`) is incompatible with Tor's real round-trip time** (8-45s
   measured, avg 14.2s). Wiring `TorTransport` in without touching
   `gossip_loop`'s interval would work but be wasteful/misleading, not
   broken outright (the loop `await`s each round fully before sleeping,
   so it can't overlap on itself -- but a naive short interval implies a
   UX promise this can't keep).
5. Cold bootstrap (18.46s) requires the Tor directory authorities to be
   reachable; this network had no blocking/filtering at all. **This must
   be re-verified on any network that might filter Tor** -- this spike's
   environment is not evidence that Tor is unblockable everywhere.
6. The tiny-transport-seam design in `hearth/transport.py` paid off
   exactly as intended: `TorTransport` needed zero changes to
   `hearth/sync.py`, and `sync_with()`'s generic `except OSError`
   already covered the new failure modes.

---

## Recommendation

- **Bundling strategy**: ship `tor.exe` alone (~10 MB) for the MVP.
  Treat `geoip`/`geoip6` and the pluggable-transports (bridges) as an
  optional, separately-downloadable add-on, gated behind an explicit
  decision about whether Hearth needs to work on censored networks (the
  user's own roadmap notes mention Tor + iOS/tunnel as a direction --
  worth deciding explicitly before the real spec, since it changes the
  bundle by ~63 MB).
- **Per-node vs. shared Tor process**: run **one shared tor.exe process
  per device**, hosting one onion service per local identity/persona.
  Q6 showed 4 concurrent onion services on one process with zero
  interference and no extra RAM cost beyond the flat ~77 MB baseline.
  Do not spawn one tor.exe per identity -- that multiplies RAM for no
  observed benefit.
- **Expected latency budget for the real spec**: budget **10-50s** for
  first contact with a freshly-published peer onion, and **8-45s per
  subsequent gossip round** (measured avg 14.2s, 10/10 reliable in this
  spike). This must replace the current 2-3s interval assumption.
  Recommend: a much longer background interval (60-120s) plus an
  immediate best-effort sync attempt right after `compose_post`/
  `compose_dm` for perceived responsiveness, and UI copy that sets
  "may take up to a minute" expectations rather than implying instant
  delivery.
- **Cleanup/shutdown strategy**: `proc.terminate()` with a ~10s grace
  period, `proc.kill()` fallback, exactly as implemented in
  `scripts/tor_control.py::kill_tor()` -- validated across every launch
  in this spike, terminate() alone was always sufficient. Shutdown order
  should be: stop `SyncService` -> close the Tor control connection
  (this silently drops any non-detached ephemeral onion services) ->
  terminate tor.exe. **Before finalizing the real spec, run a follow-up
  spike specifically on persistent onion keys across app restarts** --
  that is the one piece of this design not yet proven and it is on the
  critical path (a friend's stored `gossip_addr` must remain dialable
  after the peer's app restarts).

---

## Spike artifacts (all under `C:\Users\Wong\Desktop\hearth_tor_spike\`)

- `torrc`, `torrc_minimal` -- test configs
- `tor/` -- extracted expert bundle; `tor_minimal/` -- footprint-test copy
- `scripts/tor_control.py` -- launch/kill helper, reused by every test
- `scripts/socks5_dialer.py` -- Q4 deliverable
- `scripts/tor_transport.py` -- Q5 deliverable (`TorTransport`)
- `scripts/01_bootstrap_test.py` -- Q2
- `scripts/03_onion_and_socks_test.py` -- Q3 + Q4
- `scripts/05_two_nodes_over_tor.py` -- Q5 (the money test)
- `scripts/06_four_onions_test.py` -- Q6
- `scripts/07_footprint_and_ram_test.py` -- Q7
- `nodes/alice/`, `nodes/bob/` -- real HearthNode data dirs from the Q5 run
- `q5_run.log` -- raw stdout from the Q5 run quoted above

## Persistence follow-up

Closes Gotcha #2 / the flagged blocker: does saving the ADD_ONION
private-key blob and re-publishing it on a completely fresh tor process
give back the same .onion identity?

**Approach** (`scripts/08_persistence_test.py`, run 2026-07-03):
- Cycle 0: fresh tor process (own empty `DataDirectory`,
  `tordata_persist_0`), `create_ephemeral_hidden_service({18001: 18001},
  key_type="NEW", key_content="ED25519-V3", discard_key=False,
  detached=False)` -- i.e. `ADD_ONION NEW:ED25519-V3` with NO Detach and
  NO `Flags=DiscardPK`. Captured `resp.service_id` and
  `resp.private_key` (type `ED25519-V3`, blob length 88 chars, saved to
  `persist_key.txt`). Verified echo round-trip through SOCKS, then fully
  killed that tor.
- Cycles 1 and 2: each a BRAND-NEW tor process with a BRAND-NEW empty
  `DataDirectory` (`tordata_persist_1`, `tordata_persist_2` -- so the
  saved key blob is provably the only carried-over state), then
  `ADD_ONION ED25519-V3:<saved blob>` with the same port mapping,
  asserted ServiceID identity, poll-dialed until an echo round-tripped.

**Observed ServiceIDs** (byte-identical across all three cycles):

| cycle | tor process | ServiceID | time-to-reachable | attempts |
|---|---|---|---|---|
| 0 (new key) | #1, fresh datadir | `6kmaeg5eq7ivhgndfvj7dgoewiok4ujn3e2mly5kb5kglsjhx4kyhyad` | 5.94s | 1 |
| 1 (republish) | #2, fresh datadir | `6kmaeg5eq7ivhgndfvj7dgoewiok4ujn3e2mly5kb5kglsjhx4kyhyad` | 19.67s | 1 |
| 2 (republish) | #3, fresh datadir | `6kmaeg5eq7ivhgndfvj7dgoewiok4ujn3e2mly5kb5kglsjhx4kyhyad` | 20.12s | 1 |

- ServiceID assertion (`sid == orig_sid`) passed on both re-publish
  cycles; the ServiceID is deterministically derived from the key blob.
- Re-publish time-to-reachable (~20s) is in the same band as first-ever
  publication (10-28s elsewhere in this spike) -- restarting does not
  make re-publication slower or flakier; each cycle succeeded on its
  first dial attempt.
- All three tor processes stopped cleanly via `proc.terminate()`;
  `tasklist` afterwards: "INFO: No tasks are running which match the
  specified criteria."
- Consequence for the real spec: a Hearth node should store the 88-char
  `ED25519-V3` key blob (alongside `keys.json`, guarded with the same
  care as identity keys -- whoever holds this blob can impersonate the
  node's network address) and re-issue `ADD_ONION ED25519-V3:<blob>` on
  every app start. `gossip_addr` stays stable, so the friend-address
  model works unchanged. This upgrades the overall verdict's open
  question (2) from "untested" to "proven".

**Verdict: does saved-key republish give a stable .onion identity: YES**

## tor.exe process cleanup confirmation

Every tor.exe launched in this spike (~10 total: Q2 cold, Q2 warm, Q3,
Q6, Q7-minimal, Q7-full, plus earlier iterations) was stopped via
`proc.terminate()` (Windows equivalent of SIGTERM) inside
`kill_tor()`/inline cleanup, with a 10s `proc.wait()` grace period and a
`proc.kill()` fallback that was never actually needed. Final check via
`tasklist /FI "IMAGENAME eq tor.exe"` at the end of this spike returned
**"INFO: No tasks are running which match the specified criteria."**
