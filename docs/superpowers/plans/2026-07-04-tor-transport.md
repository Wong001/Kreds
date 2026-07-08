# Tor Transport Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run Loop gossip over Tor onion services — each node reachable at a stable `.onion`, dialing peers through a hand-rolled asyncio SOCKS5 dialer, with Tor invisible to the user (managed binary, background process).

**Architecture:** A new `hearth/socks.py` (SOCKS5 CONNECT dialer) and `hearth/tor.py` (binary resolution + `TorProcess` + `publish_onion` over the control port). `TorTransport` in `hearth/transport.py` dials `.onion` via SOCKS and plain TCP otherwise (dual-stack), satisfying the existing `connect/serve` seam with zero sync-protocol change. The gossip loop learns a per-peer cadence so onion peers sync every ~45s. `runner.run_node(tor=True)` and `demo --tor` wire it end-to-end.

**Tech Stack:** Python 3.12 (`.venv`), asyncio, `urllib`/`hashlib`/`tarfile` (stdlib, for the pinned download), Tor Expert Bundle (windows-x86_64). No `stem` in product code.

**Spec:** `docs/superpowers/specs/2026-07-04-hearth-tor-transport-design.md`
**Spike (proven code + measurements):** `docs/spikes/2026-07-03-tor-feasibility-spike.md`

## Global Constraints

- Branch: `tor-transport` off `main`. One workstream; nothing unrelated.
- Test runner: `.venv\Scripts\python.exe -m pytest tests -q`. The DEFAULT suite (194 tests today) must stay green, offline, and fast — real-Tor tests are gated behind the `TOR_E2E` env var and skipped by default.
- ASCII only in console prints (cp1252).
- Zero sync-protocol change: no edits to the frame protocol or `_session`; `TorTransport` plugs into `SyncService(node, transport=...)`.
- Constants (module-level, overridable in tests): `ONION_SYNC_INTERVAL = 45.0`; `TOR_VERSION` + `TOR_SHA256` + `TOR_URL` pinned in `hearth/tor.py` (implementer fills the current version and its checksum from the torproject checksums file at implementation time — spike used 15.0.17).
- Binary resolution order (never reorder): bundled-with-app -> cached in app data -> pinned download. Hash mismatch on download = hard refusal (raise, never run an unverified binary).
- `.onion` addresses route via SOCKS ATYP=domain (Tor recognizes the suffix); never resolve `.onion` locally.
- Privacy guardrail: in Tor mode a node's own `gossip_addr` is its onion address only; the have-frame peer exchange must not propagate a non-onion address for a peer that has a known onion address.
- `onion_key` is impersonation-grade secret: persisted in the node data dir, never logged, never gossiped, wiped by `enter_revoked_state`.
- SOCKS reads are all timeout-wrapped (the spike found and fixed a hang on an untimed CONNECT-reply read); dial failure surfaces as `OSError` so `SyncService.sync_with`'s existing `except OSError` handles it.

---

### Task 1: SOCKS5 dialer (`hearth/socks.py`)

**Files:**
- Create: `hearth/socks.py`
- Test: `tests/test_socks.py`

**Interfaces:**
- Produces: `async socks_connect(socks_host: str, socks_port: int, dest_host: str, dest_port: int, timeout: float = 30.0) -> tuple[asyncio.StreamReader, asyncio.StreamWriter]` — completes a SOCKS5 no-auth CONNECT to `dest_host:dest_port` (ATYP=domain) through the SOCKS proxy; raises `OSError` (or a subclass) on negotiation/CONNECT failure, truncation, or timeout.

- [ ] **Step 1: Create the branch**

```powershell
git checkout main; git pull; git checkout -b tor-transport
```

- [ ] **Step 2: Write the failing tests**

Create `tests/test_socks.py`. Tests run a fake in-process SOCKS5 server (no Tor, no network) so they belong in the default suite:

```python
import asyncio
import struct

import pytest

from hearth.socks import socks_connect


async def _fake_socks_server(reply_rep=0x00, truncate=False, hang=False):
    """A minimal SOCKS5 server: negotiate no-auth, read CONNECT, reply.
    Returns (host, port, server, seen) where seen collects the dest asked
    for. reply_rep sets the CONNECT reply code; truncate sends a short
    reply; hang never replies to CONNECT (to exercise the timeout)."""
    seen = {}

    async def handle(reader, writer):
        greeting = await reader.readexactly(2)          # ver, nmethods
        await reader.readexactly(greeting[1])           # methods
        writer.write(b"\x05\x00")                       # no-auth chosen
        await writer.drain()
        head = await reader.readexactly(4)              # ver, cmd, rsv, atyp
        dlen = (await reader.readexactly(1))[0]
        host = await reader.readexactly(dlen)
        port = struct.unpack(">H", await reader.readexactly(2))[0]
        seen["dest"] = (host.decode(), port)
        if hang:
            await asyncio.sleep(30)
            return
        if truncate:
            writer.write(b"\x05")                        # short reply
            await writer.drain()
            return
        # ver, rep, rsv, atyp=domain, len 0, no bound addr, port 0
        writer.write(b"\x05" + bytes([reply_rep]) + b"\x00\x03\x00\x00\x00")
        await writer.drain()

    server = await asyncio.start_server(handle, "127.0.0.1", 0)
    host, port = server.sockets[0].getsockname()[:2]
    return host, port, server, seen


def test_socks_connect_success_sends_domain_atyp():
    async def scenario():
        host, port, server, seen = await _fake_socks_server()
        reader, writer = await socks_connect(host, port,
                                             "abc.onion", 15001)
        assert seen["dest"] == ("abc.onion", 15001)     # domain ATYP, not resolved
        writer.close()
        server.close()
        await server.wait_closed()
    asyncio.run(scenario())


def test_socks_connect_refusal_raises_oserror():
    async def scenario():
        host, port, server, _ = await _fake_socks_server(reply_rep=0x05)
        with pytest.raises(OSError):
            await socks_connect(host, port, "abc.onion", 15001)
        server.close()
        await server.wait_closed()
    asyncio.run(scenario())


def test_socks_connect_truncated_reply_raises_oserror():
    async def scenario():
        host, port, server, _ = await _fake_socks_server(truncate=True)
        with pytest.raises(OSError):
            await socks_connect(host, port, "abc.onion", 15001)
        server.close()
        await server.wait_closed()
    asyncio.run(scenario())


def test_socks_connect_timeout_raises_oserror():
    async def scenario():
        host, port, server, _ = await _fake_socks_server(hang=True)
        with pytest.raises(OSError):
            await socks_connect(host, port, "abc.onion", 15001,
                                timeout=0.3)
        server.close()
        await server.wait_closed()
    asyncio.run(scenario())
```

- [ ] **Step 3: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_socks.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'hearth.socks'`.

- [ ] **Step 4: Implement**

Create `hearth/socks.py` (hardened from the spike's proven dialer — `asyncio.TimeoutError` is a subclass of `OSError` in 3.12, and negotiation failures are raised as `ConnectionError`, also an `OSError`, so the whole failure surface is `OSError`):

```python
"""Asyncio SOCKS5 CONNECT dialer (no auth, ATYP=domain).

This is the seam TorTransport dials through: `.onion` names are sent as
domain-type destinations so Tor routes them through the hidden-service
protocol instead of resolving/exiting to the clearnet. Every read is
timeout-wrapped; all failures surface as OSError so the sync layer's
existing `except OSError` treats a bad dial as 'peer offline'."""
from __future__ import annotations

import asyncio
import struct


async def socks_connect(socks_host: str, socks_port: int,
                        dest_host: str, dest_port: int,
                        timeout: float = 30.0):
    return await asyncio.wait_for(
        _connect(socks_host, socks_port, dest_host, dest_port),
        timeout=timeout)


async def _connect(socks_host, socks_port, dest_host, dest_port):
    reader, writer = await asyncio.open_connection(socks_host, socks_port)
    try:
        writer.write(b"\x05\x01\x00")                   # greeting: no-auth
        await writer.drain()
        resp = await reader.readexactly(2)
        if resp[0] != 0x05 or resp[1] != 0x00:
            raise ConnectionError(f"SOCKS5 negotiation failed: {resp!r}")

        host_b = dest_host.encode("ascii")
        writer.write(b"\x05\x01\x00\x03" + bytes([len(host_b)]) + host_b
                     + struct.pack(">H", dest_port))    # CONNECT, ATYP=domain
        await writer.drain()

        head = await reader.readexactly(4)              # ver, rep, rsv, atyp
        rep, atyp = head[1], head[3]
        if atyp == 0x01:
            await reader.readexactly(4 + 2)
        elif atyp == 0x03:
            await reader.readexactly((await reader.readexactly(1))[0] + 2)
        elif atyp == 0x04:
            await reader.readexactly(16 + 2)
        if rep != 0x00:
            raise ConnectionError(f"SOCKS5 CONNECT failed, REP=0x{rep:02x}")
        return reader, writer
    except BaseException:
        writer.close()
        raise
```

- [ ] **Step 5: Run tests, then full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_socks.py -v` — Expected: 4 PASS.
Run: `.venv\Scripts\python.exe -m pytest tests -q` — Expected: ALL PASS.

- [ ] **Step 6: Commit**

```powershell
git add hearth/socks.py tests/test_socks.py
git commit -m "feat: asyncio SOCKS5 CONNECT dialer for Tor"
```

---

### Task 2: Tor binary resolution (`hearth/tor.py` — part 1)

**Files:**
- Create: `hearth/tor.py`
- Test: `tests/test_tor_binary.py`

**Interfaces:**
- Produces:
  - `TOR_VERSION: str`, `TOR_SHA256: str`, `TOR_URL: str` (pinned constants)
  - `tor_app_dir() -> Path` — `%LOCALAPPDATA%\Loop\tor\<version>\` (honors `LOOP_TOR_DIR` env override for tests)
  - `ensure_tor_binary(bundled_dir: Optional[Path] = None, download: bool = True) -> Path` — resolution order bundled -> cached -> download; verifies SHA256 on a freshly downloaded archive and refuses (`raise RuntimeError`) on mismatch; returns the path to `tor.exe`.
  - `verify_sha256(path: Path, expected_hex: str) -> bool`

- [ ] **Step 1: Write the failing tests**

Create `tests/test_tor_binary.py` (no network — exercises cached/bundled/mismatch paths with fixture files; the real download path is not unit-tested, only its hash gate):

```python
import hashlib

import pytest

from hearth import tor


def _write(p, data=b"fake-tor-exe"):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(data)
    return p


def test_cached_binary_short_circuits(tmp_path, monkeypatch):
    monkeypatch.setenv("LOOP_TOR_DIR", str(tmp_path))
    exe = _write(tor.tor_app_dir() / "tor" / "tor.exe")
    # download=False proves no network is attempted when cache is present
    assert tor.ensure_tor_binary(download=False) == exe


def test_bundled_wins_over_cache(tmp_path, monkeypatch):
    monkeypatch.setenv("LOOP_TOR_DIR", str(tmp_path))
    _write(tor.tor_app_dir() / "tor" / "tor.exe", b"cached")
    bundled = tmp_path / "bundle"
    bexe = _write(bundled / "tor" / "tor.exe", b"bundled")
    assert tor.ensure_tor_binary(bundled_dir=bundled, download=False) == bexe


def test_missing_binary_without_download_raises(tmp_path, monkeypatch):
    monkeypatch.setenv("LOOP_TOR_DIR", str(tmp_path))
    with pytest.raises(RuntimeError):
        tor.ensure_tor_binary(download=False)


def test_verify_sha256_matches_and_mismatches(tmp_path):
    p = tmp_path / "f"
    p.write_bytes(b"payload")
    good = hashlib.sha256(b"payload").hexdigest()
    assert tor.verify_sha256(p, good) is True
    assert tor.verify_sha256(p, "00" * 32) is False
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_tor_binary.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'hearth.tor'`.

- [ ] **Step 3: Implement**

Create `hearth/tor.py` with the binary-resolution half (the process half is Task 3, appended to this same file):

```python
"""Tor management for Loop: resolve/launch the binary and publish onion
services. The user never 'downloads Tor' as an action -- the app resolves
tor.exe (bundled with the app, else cached in app data, else a pinned,
hash-verified first-run download) and runs it headless on loopback.

Windows-x86_64 only for now (stated in README). No stem dependency: the
control protocol is spoken directly (Task 3)."""
from __future__ import annotations

import hashlib
import os
import tarfile
import urllib.request
from pathlib import Path
from typing import Optional

# Pinned Tor Expert Bundle (windows-x86_64). Update together; a mismatch
# is a hard refusal, never a silent run of an unverified binary.
TOR_VERSION = "15.0.17"
TOR_URL = ("https://dist.torproject.org/torbrowser/" + TOR_VERSION +
           "/tor-expert-bundle-windows-x86_64-" + TOR_VERSION + ".tar.gz")
TOR_SHA256 = "REPLACE_WITH_PINNED_CHECKSUM"   # from torproject checksums file


def tor_app_dir() -> Path:
    override = os.environ.get("LOOP_TOR_DIR")
    base = Path(override) if override else (
        Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "Loop")
    return base / "tor" / TOR_VERSION


def verify_sha256(path: Path, expected_hex: str) -> bool:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest().lower() == expected_hex.lower()


def _tor_exe_in(root: Path) -> Optional[Path]:
    cand = root / "tor" / "tor.exe"
    return cand if cand.exists() else None


def ensure_tor_binary(bundled_dir: Optional[Path] = None,
                      download: bool = True) -> Path:
    # (a) bundled with the app
    if bundled_dir is not None:
        exe = _tor_exe_in(bundled_dir)
        if exe is not None:
            return exe
    # (b) cached in app data
    app = tor_app_dir()
    exe = _tor_exe_in(app)
    if exe is not None:
        return exe
    # (c) pinned, hash-verified download
    if not download:
        raise RuntimeError("tor.exe not found (no bundle, no cache)")
    app.mkdir(parents=True, exist_ok=True)
    archive = app / "tor-expert-bundle.tar.gz"
    urllib.request.urlretrieve(TOR_URL, archive)
    if not verify_sha256(archive, TOR_SHA256):
        archive.unlink(missing_ok=True)
        raise RuntimeError("tor bundle SHA256 mismatch -- refusing to run")
    with tarfile.open(archive, "r:gz") as tf:
        tf.extractall(app)
    archive.unlink(missing_ok=True)
    exe = _tor_exe_in(app)
    if exe is None:
        raise RuntimeError("tor.exe not present after extraction")
    return exe
```

- [ ] **Step 4: Run tests, then full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_tor_binary.py -v` — Expected: 4 PASS.
Run: `.venv\Scripts\python.exe -m pytest tests -q` — Expected: ALL PASS.

- [ ] **Step 5: Pin the real checksum**

Replace `TOR_SHA256 = "REPLACE_WITH_PINNED_CHECKSUM"` with the actual SHA256 of the pinned archive. Fetch it without committing the binary:

```powershell
$u = "https://dist.torproject.org/torbrowser/15.0.17/tor-expert-bundle-windows-x86_64-15.0.17.tar.gz"
$tmp = "$env:TEMP\teb.tar.gz"; Invoke-WebRequest $u -OutFile $tmp
(Get-FileHash $tmp -Algorithm SHA256).Hash.ToLower()
Remove-Item $tmp
```

Paste the printed hash into `TOR_SHA256`. (If the network blocks the download, leave the placeholder, note it in the task report, and flag for the final review — the download path stays untested but the resolution/mismatch logic is covered.)

- [ ] **Step 6: Commit**

```powershell
git add hearth/tor.py tests/test_tor_binary.py
git commit -m "feat: Tor binary resolution - bundled/cached/pinned-download"
```

---

### Task 3: Tor process + onion publication (`hearth/tor.py` — part 2)

**Files:**
- Modify: `hearth/tor.py` (append `TorProcess` + control-port client)
- Test: `tests/test_tor_control.py`

**Interfaces:**
- Consumes: `ensure_tor_binary` (Task 2), `socks_connect` (Task 1, for reachability polling).
- Produces:
  - `class TorProcess`: `async start()` (launch tor with a generated torrc, await "Bootstrapped 100%"), `async stop()` (terminate with grace then kill), attributes `socks_port: int`, `control_port: int`, `cookie_path: Path`.
  - `async publish_onion(control_port: int, cookie_path: Path, local_port: int, key_blob: Optional[str] = None) -> tuple[str, str]` — returns `(service_id, key_blob)`; `NEW:ED25519-V3` when `key_blob` is None, else `ED25519-V3:<blob>`.
  - `_parse_control_reply` helper (pure, unit-tested).

- [ ] **Step 1: Write the failing tests**

Create `tests/test_tor_control.py`. The control-reply parser is pure and unit-tested here; the live `TorProcess`/`publish_onion` are exercised by the `TOR_E2E` integration test in Task 6 (they need a real tor.exe). This keeps the default suite offline:

```python
from hearth.tor import _parse_control_reply


def test_parse_add_onion_reply_extracts_service_id_and_key():
    # Shape of a real ADD_ONION 250 reply (multi-line, dot-terminated)
    lines = [
        "250-ServiceID=6kmaeg5eq7ivhgndfvj7dgoewiok4ujn3e2mly5kb5kglsjhx4kyhyad",
        "250-PrivateKey=ED25519-V3:aGVsbG8gd29ybGQgZmFrZSBrZXkgYmxvYg==",
        "250 OK",
    ]
    fields = _parse_control_reply(lines)
    assert fields["ServiceID"] == \
        "6kmaeg5eq7ivhgndfvj7dgoewiok4ujn3e2mly5kb5kglsjhx4kyhyad"
    assert fields["PrivateKey"] == \
        "ED25519-V3:aGVsbG8gd29ybGQgZmFrZSBrZXkgYmxvYg=="


def test_parse_control_reply_ignores_non_kv_lines():
    assert _parse_control_reply(["250 OK"]) == {}
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_tor_control.py -v`
Expected: FAIL — `ImportError: cannot import name '_parse_control_reply'`.

- [ ] **Step 3: Implement**

Append to `hearth/tor.py` (add `import asyncio`, `import subprocess`, `import re` to the imports; `from .socks import socks_connect`):

```python
def _parse_control_reply(lines) -> dict:
    """Pull KEY=VALUE fields out of a Tor control 250 reply block.
    Lines look like '250-ServiceID=...' / '250 OK'."""
    out = {}
    for line in lines:
        body = line[4:] if len(line) > 4 and line[3] in "-+ " else line
        if "=" in body:
            k, v = body.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def _gen_torrc(data_dir: Path, socks_port: int, control_port: int) -> Path:
    data_dir.mkdir(parents=True, exist_ok=True)
    torrc = data_dir / "torrc"
    torrc.write_text(
        f"SocksPort 127.0.0.1:{socks_port}\n"
        f"ControlPort 127.0.0.1:{control_port}\n"
        "CookieAuthentication 1\n"
        f"DataDirectory {data_dir}\n"
        "Log notice stdout\n")
    return torrc


class TorProcess:
    """One headless tor.exe on loopback. Shared per machine/demo cast."""

    def __init__(self, exe: Path, data_dir: Path,
                 socks_port: int = 9250, control_port: int = 9251):
        self.exe = exe
        self.data_dir = Path(data_dir)
        self.socks_port = socks_port
        self.control_port = control_port
        self.cookie_path = self.data_dir / "control_auth_cookie"
        self._proc = None

    async def start(self, bootstrap_timeout: float = 90.0):
        torrc = _gen_torrc(self.data_dir, self.socks_port,
                           self.control_port)
        self._proc = await asyncio.create_subprocess_exec(
            str(self.exe), "-f", str(torrc),
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT)

        async def _await_bootstrap():
            while True:
                raw = await self._proc.stdout.readline()
                if not raw:
                    raise RuntimeError("tor exited before bootstrap")
                if b"Bootstrapped 100%" in raw:
                    return
        await asyncio.wait_for(_await_bootstrap(), timeout=bootstrap_timeout)

    async def stop(self):
        if self._proc is None:
            return
        self._proc.terminate()
        try:
            await asyncio.wait_for(self._proc.wait(), timeout=10.0)
        except asyncio.TimeoutError:
            self._proc.kill()
            await self._proc.wait()
        self._proc = None


async def _control_command(control_port: int, cookie_path: Path,
                           command: str) -> list:
    reader, writer = await asyncio.open_connection("127.0.0.1", control_port)
    try:
        cookie_hex = cookie_path.read_bytes().hex()
        writer.write(f"AUTHENTICATE {cookie_hex}\r\n".encode())
        await writer.drain()
        auth = await reader.readline()
        if not auth.startswith(b"250"):
            raise RuntimeError(f"tor control auth failed: {auth!r}")
        writer.write((command + "\r\n").encode())
        await writer.drain()
        lines = []
        while True:
            raw = await reader.readline()
            if not raw:
                break
            text = raw.decode(errors="replace").rstrip("\r\n")
            lines.append(text)
            if text.startswith("250 ") or text[:3] in ("451", "512", "550"):
                break
        return lines
    finally:
        writer.close()


async def publish_onion(control_port: int, cookie_path: Path,
                        local_port: int, key_blob=None):
    key_spec = key_blob if key_blob else "NEW:ED25519-V3"
    cmd = (f"ADD_ONION {key_spec} "
           f"Port={local_port},127.0.0.1:{local_port}")
    fields = _parse_control_reply(
        await _control_command(control_port, cookie_path, cmd))
    service_id = fields.get("ServiceID")
    if service_id is None:
        raise RuntimeError("ADD_ONION returned no ServiceID")
    # On a NEW key Tor returns PrivateKey=ED25519-V3:<blob>; on republish
    # from a saved blob it echoes nothing, so keep what we sent.
    returned = fields.get("PrivateKey")
    return service_id, (returned if returned else key_blob)
```

- [ ] **Step 4: Run tests, then full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_tor_control.py -v` — Expected: 2 PASS.
Run: `.venv\Scripts\python.exe -m pytest tests -q` — Expected: ALL PASS.

- [ ] **Step 5: Commit**

```powershell
git add hearth/tor.py tests/test_tor_control.py
git commit -m "feat: headless TorProcess + control-port onion publication"
```

---

### Task 4: TorTransport (`hearth/transport.py`)

**Files:**
- Modify: `hearth/transport.py` (append `TorTransport`; keep `TcpTransport` unchanged)
- Test: `tests/test_tor_transport.py`

**Interfaces:**
- Consumes: `socks_connect` (Task 1).
- Produces: `class TorTransport` with `socks_port: int`; `async connect(address)` — SOCKS5 dial when the host part ends with `.onion`, else `asyncio.open_connection` (dual-stack); `async serve(host, port, handler)` — identical to `TcpTransport.serve` (local listen; the onion service maps onto it). Same duck-typed interface `SyncService` already consumes.

- [ ] **Step 1: Write the failing tests**

Create `tests/test_tor_transport.py` (fake SOCKS server from Task 1's pattern; no Tor):

```python
import asyncio
import struct

from hearth.transport import TorTransport


async def _fake_socks(seen):
    async def handle(reader, writer):
        g = await reader.readexactly(2)
        await reader.readexactly(g[1])
        writer.write(b"\x05\x00"); await writer.drain()
        await reader.readexactly(4)
        dlen = (await reader.readexactly(1))[0]
        host = await reader.readexactly(dlen)
        port = struct.unpack(">H", await reader.readexactly(2))[0]
        seen.append((host.decode(), port))
        writer.write(b"\x05\x00\x00\x03\x00\x00\x00"); await writer.drain()
    server = await asyncio.start_server(handle, "127.0.0.1", 0)
    return server, server.sockets[0].getsockname()[1]


def test_onion_address_dials_via_socks():
    async def scenario():
        seen = []
        server, sport = await _fake_socks(seen)
        t = TorTransport(socks_port=sport)
        reader, writer = await t.connect("abc.onion:15001")
        assert seen == [("abc.onion", 15001)]
        writer.close(); server.close(); await server.wait_closed()
    asyncio.run(scenario())


def test_plain_address_dials_direct_tcp():
    async def scenario():
        # a plain local echo listener; TorTransport must NOT use SOCKS
        async def echo(r, w):
            w.write(await r.readexactly(3)); await w.drain(); w.close()
        srv = await asyncio.start_server(echo, "127.0.0.1", 0)
        port = srv.sockets[0].getsockname()[1]
        t = TorTransport(socks_port=1)          # bogus SOCKS: must be unused
        reader, writer = await t.connect(f"127.0.0.1:{port}")
        writer.write(b"hey"); await writer.drain()
        assert await reader.readexactly(3) == b"hey"
        writer.close(); srv.close(); await srv.wait_closed()
    asyncio.run(scenario())


def test_serve_is_plain_local_listen():
    async def scenario():
        t = TorTransport(socks_port=1)
        hits = []
        async def handler(r, w): hits.append(1); w.close()
        server = await t.serve("127.0.0.1", 0, handler)
        port = server.sockets[0].getsockname()[1]
        r, w = await asyncio.open_connection("127.0.0.1", port)
        await asyncio.sleep(0.05)
        w.close(); server.close(); await server.wait_closed()
        assert hits == [1]
    asyncio.run(scenario())
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_tor_transport.py -v`
Expected: FAIL — `ImportError: cannot import name 'TorTransport'`.

- [ ] **Step 3: Implement**

Append to `hearth/transport.py` (add `from .socks import socks_connect` at the top):

```python
class TorTransport:
    """Dual-stack transport: dial `.onion` peers through Tor's SOCKS proxy,
    plain TCP otherwise. serve() is an ordinary local listener -- the node's
    onion service (published separately) maps onto it. Same connect/serve
    seam as TcpTransport, so SyncService is unchanged."""

    def __init__(self, socks_port: int):
        self.socks_port = socks_port

    async def connect(self, address: str):
        host, port = address.rsplit(":", 1)
        if host.endswith(".onion"):
            return await socks_connect("127.0.0.1", self.socks_port,
                                       host, int(port))
        return await asyncio.open_connection(host, int(port))

    async def serve(self, host: str, port: int, handler):
        return await asyncio.start_server(handler, host, port)
```

- [ ] **Step 4: Run tests, then full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_tor_transport.py -v` — Expected: 3 PASS.
Run: `.venv\Scripts\python.exe -m pytest tests -q` — Expected: ALL PASS.

- [ ] **Step 5: Commit**

```powershell
git add hearth/transport.py tests/test_tor_transport.py
git commit -m "feat: TorTransport - dual-stack onion-via-SOCKS dialer"
```

---

### Task 5: Per-peer gossip cadence + privacy guardrail

**Files:**
- Modify: `hearth/sync.py` (`gossip_loop` scheduling; import `ONION_SYNC_INTERVAL`)
- Modify: `hearth/messages.py` (add `ONION_SYNC_INTERVAL = 45.0` near the other module constants — it is a transport-cadence constant, colocated with the shared constants module the codebase already imports from)
- Modify: `hearth/node.py` (`_onion_addr(a)` helper + guardrail in the have-frame peer-merge is in `sync.py`; see below)
- Test: `tests/test_gossip_cadence.py`

Note on placement: `ONION_SYNC_INTERVAL` lives in `hearth/messages.py` (already the constants home, imported widely) to avoid a new import cycle between `sync.py` and `tor.py`.

**Interfaces:**
- Consumes: `ONION_SYNC_INTERVAL`.
- Produces:
  - `SyncService.gossip_loop` schedules `.onion` peers every `ONION_SYNC_INTERVAL`, others every round, using an injected clock for tests: `gossip_loop(interval=3.0, now=None)` where `now` defaults to `time.monotonic`.
  - `_is_onion(address: str) -> bool` module helper in `sync.py`.
  - Guardrail: in the have-frame peer merge (`_session`), when adding a peer address, do not overwrite/duplicate a known onion address for that identity with a non-onion one, and skip storing a non-onion address for an identity we already hold an onion address for.

- [ ] **Step 1: Write the failing tests**

Create `tests/test_gossip_cadence.py`:

```python
import asyncio

from hearth.node import HearthNode
from hearth.sync import SyncService, _is_onion


def test_is_onion_detects_suffix():
    assert _is_onion("abc.onion:15001") is True
    assert _is_onion("127.0.0.1:7101") is False


def test_onion_peer_skipped_until_interval(tmp_path, monkeypatch):
    # A single gossip round with a fake clock: an onion peer dialed once,
    # then skipped on the next immediate round; a tcp peer dialed both.
    from hearth import messages
    monkeypatch.setattr(messages, "ONION_SYNC_INTERVAL", 45.0)
    node = HearthNode.create(tmp_path / "n", "Wong", "phone")
    node.store.add_peer("abc.onion:15001", node.identity_pub)
    node.store.add_peer("127.0.0.1:7999", node.identity_pub)
    svc = SyncService(node)
    dialed = []

    async def fake_sync_with(addr):
        dialed.append(addr)
        return False
    svc.sync_with = fake_sync_with

    async def scenario():
        clock = [1000.0]
        # one loop body iteration at t=1000, then again at t=1010 (<45s)
        await svc._gossip_round(now=lambda: clock[0])
        clock[0] = 1010.0
        await svc._gossip_round(now=lambda: clock[0])
    asyncio.run(scenario())

    # tcp peer dialed both rounds; onion peer only the first
    assert dialed.count("127.0.0.1:7999") == 2
    assert dialed.count("abc.onion:15001") == 1
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_gossip_cadence.py -v`
Expected: FAIL — `ImportError` on `_is_onion` / `AttributeError` on `_gossip_round`.

- [ ] **Step 3: Implement**

(a) In `hearth/messages.py`, add near the other constants (e.g. after `MAX_BLOB_BYTES`):

```python
ONION_SYNC_INTERVAL = 45.0   # seconds; onion peers sync slower than TCP
```

(b) In `hearth/sync.py`: add `import time`, `from .messages import ..., ONION_SYNC_INTERVAL` (extend the existing messages import), and refactor the loop to a per-peer-scheduled round with an injectable clock:

```python
def _is_onion(address: str) -> bool:
    host = address.rsplit(":", 1)[0]
    return host.endswith(".onion")


class SyncService:
    def __init__(self, node, transport=None):
        self.node = node
        self.transport = transport or TcpTransport()
        self._server = None
        self._last_onion_sync = {}          # address -> monotonic ts

    # ... start/stop/sync_with unchanged ...

    async def _gossip_round(self, now=None):
        now = now or time.monotonic
        self.node.maintain_enckey()
        for peer in self.node.store.list_peers():
            addr = peer["address"]
            if _is_onion(addr):
                t = now()
                last = self._last_onion_sync.get(addr, 0.0)
                if t - last < ONION_SYNC_INTERVAL:
                    continue
                self._last_onion_sync[addr] = t
            await self.sync_with(addr)
        if self.node.store.sweep_expired():
            self.node.notify()
        self.node.cache_dm_keys()

    async def gossip_loop(self, interval: float = 3.0, now=None):
        while True:
            try:
                await self._gossip_round(now=now)
            except Exception:
                pass                # never let one bad round kill gossip
            await asyncio.sleep(interval)
```

(c) Privacy guardrail in `_session`'s have-frame peer merge. Locate the existing peer-add block:

```python
        for p in peer_have.get("peers", []):
            if (p.get("identity_pub") and store.is_known(p["identity_pub"])
                    and p["address"] != my_addr):
                store.add_peer(p["address"], p["identity_pub"])
```

and gate it so a non-onion address is not stored for an identity we already reach via onion, and an onion address always supersedes a stored non-onion one:

```python
        for p in peer_have.get("peers", []):
            ident, addr = p.get("identity_pub"), p.get("address")
            if not (ident and store.is_known(ident) and addr != my_addr):
                continue
            known = [pe["address"] for pe in store.list_peers()
                     if pe.get("identity_pub") == ident]
            has_onion = any(_is_onion(a) for a in known)
            if _is_onion(addr):
                store.add_peer(addr, ident)          # onion always welcome
            elif not has_onion:
                store.add_peer(addr, ident)          # tcp only if no onion known
```

- [ ] **Step 4: Run tests, then full suite**

Run: `.venv\Scripts\python.exe -m pytest tests/test_gossip_cadence.py tests/test_gossip_loop.py tests/test_sync_dm.py -v` — Expected: ALL PASS (the existing loop/sync tests still pass — `_gossip_round` preserves their behavior for TCP peers).
Run: `.venv\Scripts\python.exe -m pytest tests -q` — Expected: ALL PASS.

- [ ] **Step 5: Commit**

```powershell
git add hearth/sync.py hearth/messages.py tests/test_gossip_cadence.py
git commit -m "feat: per-peer onion sync cadence + onion-preferred peer guardrail"
```

---

### Task 6: Node onion identity + runner/demo wiring + TOR_E2E integration test

**Files:**
- Modify: `hearth/node.py` (`onion_key` load/persist/wipe)
- Modify: `hearth/store.py` (`remove_peer`)
- Modify: `hearth/runner.py` (`run_node(..., tor=False)`)
- Modify: `hearth/cli.py` (`run --tor`, `demo --tor`)
- Modify: `hearth/demo.py` (`--tor` cast over onions)
- Test: `tests/test_node_onion.py` (default suite), `tests/test_tor_e2e.py` (TOR_E2E-gated)

**Interfaces:**
- Consumes: `TorProcess`, `publish_onion`, `ensure_tor_binary` (Tasks 2-3); `TorTransport` (Task 4); `_is_onion` (Task 5).
- Produces:
  - `HearthNode.onion_key` (str|None), loaded from / saved to the data dir file `onion_key`; `HearthNode.save_onion_key(blob)`; wiped in `enter_revoked_state`.
  - `Store.remove_peer(address: str) -> None` — delete a peer row by address.
  - `run_node(data_dir, gossip_port, http_port, interval=3.0, tor=False, tor_process=None)` — when `tor`, ensures binary + shared `TorProcess`, publishes the node's onion (reusing saved `onion_key`), sets onion `gossip_addr`, uses `TorTransport`.

- [ ] **Step 1: Write the failing default-suite test**

Create `tests/test_node_onion.py`:

```python
import json

from hearth.node import HearthNode


def test_onion_key_persists_and_loads(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "phone")
    assert n.onion_key is None
    n.save_onion_key("ED25519-V3:fakeblob")
    assert (tmp_path / "n" / "onion_key").read_text() == "ED25519-V3:fakeblob"
    n.close()
    n2 = HearthNode(tmp_path / "n")
    assert n2.onion_key == "ED25519-V3:fakeblob"


def test_onion_key_wiped_on_revocation(tmp_path):
    d = tmp_path / "n"
    n = HearthNode.create(d, "Wong", "phone")
    n.save_onion_key("ED25519-V3:fakeblob")
    n.enter_revoked_state()
    assert n.onion_key is None
    assert not (d / "onion_key").exists()
```

- [ ] **Step 2: Run to verify failure**

Run: `.venv\Scripts\python.exe -m pytest tests/test_node_onion.py -v`
Expected: FAIL — `AttributeError: 'HearthNode' object has no attribute 'onion_key'`.

- [ ] **Step 2b: Add and test `Store.remove_peer`**

Append to `tests/test_node_onion.py`:

```python
def test_remove_peer(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Wong", "phone")
    n.store.add_peer("127.0.0.1:7101", "aa" * 32)
    n.store.add_peer("abc.onion:7101", "aa" * 32)
    n.store.remove_peer("127.0.0.1:7101")
    addrs = [p["address"] for p in n.store.list_peers()]
    assert addrs == ["abc.onion:7101"]
```

In `hearth/store.py`, next to `add_peer`:

```python
    def remove_peer(self, address: str):
        with self._lock:
            self._db.execute("DELETE FROM peers WHERE address=?", (address,))
            self._db.commit()
```

- [ ] **Step 3: Implement node onion identity**

In `hearth/node.py`:

(a) In `__init__`, after the store is set up, load the key:

```python
        okp = self.data_dir / "onion_key"
        self.onion_key = okp.read_text() if okp.exists() else None
```

(b) Add the setter:

```python
    def save_onion_key(self, blob: str):
        self.onion_key = blob
        (self.data_dir / "onion_key").write_text(blob)
```

(c) In `enter_revoked_state`, after the existing key wipes, wipe the onion identity:

```python
        self.onion_key = None
        okp = self.data_dir / "onion_key"
        if okp.exists():
            okp.unlink()
```

- [ ] **Step 4: Run the node test, then implement runner/cli/demo wiring**

Run: `.venv\Scripts\python.exe -m pytest tests/test_node_onion.py -v` — Expected: 2 PASS.

Then wire the daemon. In `hearth/runner.py`:

```python
"""Wire one node's daemon: gossip listener + loop + localhost HTTP."""
from __future__ import annotations

import asyncio

import uvicorn

from .api import build_app
from .node import HearthNode
from .sync import SyncService
from .transport import TorTransport
from .tor import TorProcess, ensure_tor_binary, publish_onion


async def run_node(data_dir, gossip_port: int, http_port: int,
                   interval: float = 3.0, tor: bool = False,
                   tor_process: "TorProcess | None" = None):
    node = HearthNode(data_dir)
    own_tor = None
    if tor:
        if tor_process is None:
            exe = ensure_tor_binary()
            own_tor = TorProcess(exe, node.data_dir / "tordata")
            await own_tor.start()
            tor_process = own_tor
        sync = SyncService(node, transport=TorTransport(tor_process.socks_port))
        listen_port = await sync.start("127.0.0.1", 0)
        service_id, blob = await publish_onion(
            tor_process.control_port, tor_process.cookie_path,
            listen_port, node.onion_key)
        if blob and blob != node.onion_key:
            node.save_onion_key(blob)
        node.store.set_meta("gossip_addr", f"{service_id}.onion:{listen_port}")
    else:
        sync = SyncService(node)
        port = await sync.start("127.0.0.1", gossip_port)
        node.store.set_meta("gossip_addr", f"127.0.0.1:{port}")
    server = uvicorn.Server(uvicorn.Config(
        build_app(node), host="127.0.0.1", port=http_port,
        log_level="warning"))
    loop_task = asyncio.create_task(sync.gossip_loop(interval))
    try:
        await server.serve()
    finally:
        loop_task.cancel()
        try:
            await loop_task
        except BaseException:
            pass
        await sync.stop()
        if own_tor is not None:
            await own_tor.stop()
```

In `hearth/cli.py`, add `--tor` to the `run` parser and pass it through, and add `--tor` to `demo`:

```python
    sp.add_argument("--tor", action="store_true",
                    help="run this node as a Tor onion service")
```
```python
        asyncio.run(run_node(args.dir, args.gossip_port, args.http_port,
                             args.interval, tor=args.tor))
```
```python
    dp = sub.add_parser("demo", help="run the four-node demo cast")
    dp.add_argument("--tor", action="store_true",
                    help="run the cast over Tor onion services")
```
```python
    elif args.cmd == "demo":
        from .demo import demo
        try:
            asyncio.run(demo(tor=args.tor))
        except KeyboardInterrupt:
            print("demo stopped")
```

In `hearth/demo.py`, thread `tor` through. The wrinkle: `build_cast` seeds peer tables with `127.0.0.1:71xx` addresses, which `TorTransport` would dial over plain TCP — so the cast must have its peer addresses rewritten to onions before the daemons run, or the demo silently bypasses Tor. Do a two-phase startup: publish an onion per cast node first (each mapped to its fixed gossip port, keyed by the node's identity), rewrite every node's peer table from `identity -> onion`, then run the daemons (which reuse the saved `onion_key`, so `run_node`'s own publish returns the same service_id and the seeded addresses match).

Add `from .tor import TorProcess, ensure_tor_binary, publish_onion` and `from .sync import _is_onion` to the imports, and:

```python
async def _seed_onions(shared):
    """Publish an onion per cast node (mapped to its fixed gossip port),
    persist each node's onion_key, and rewrite every peer table so peers
    are addressed by .onion instead of the build-time 127.0.0.1 seeds.
    Without this the cast would gossip over plain TCP and never touch Tor."""
    ident_to_onion = {}
    for data_dir, gp, _ in CAST:
        n = HearthNode(data_dir)
        sid, blob = await publish_onion(shared.control_port,
                                        shared.cookie_path, gp, n.onion_key)
        if blob and blob != n.onion_key:
            n.save_onion_key(blob)
        ident_to_onion[n.identity_pub] = f"{sid}.onion:{gp}"
        n.store.set_meta("gossip_addr", ident_to_onion[n.identity_pub])
        n.close()
    for data_dir, _, _ in CAST:
        n = HearthNode(data_dir)
        for peer in n.store.list_peers():
            ident = peer.get("identity_pub")
            if ident in ident_to_onion:
                # replace the build-time TCP seed with the onion address,
                # or the fast TCP cadence would carry the gossip and Tor
                # would never actually be exercised
                if not _is_onion(peer["address"]):
                    n.store.remove_peer(peer["address"])
                n.store.add_peer(ident_to_onion[ident], ident)
        n.close()


async def demo(tor: bool = False):
    fresh = await build_cast()
    print("Loop demo - four nodes on this machine"
          + (" (over Tor)" if tor else ""))
    print("  (cast was %s)" % ("just created" if fresh else "reused"))
    print()
    for label, hp in (("Wong's phone", 7201), ("Wong's home node", 7202),
                      ("Freja's phone", 7203), ("Freja's home node", 7204)):
        print("  %-18s http://127.0.0.1:%d" % (label, hp))
    print()
    if tor:
        print("Tor mode: first sync between nodes can take ~a minute while")
        print("onion services publish. Data lives in run/.")
        exe = ensure_tor_binary()
        shared = TorProcess(exe, Path("run/tordata"))
        await shared.start()
        try:
            await _seed_onions(shared)
            await asyncio.gather(*(run_node(d, gp, hp, interval=2.0,
                                            tor=True, tor_process=shared)
                                   for d, gp, hp in CAST))
        finally:
            await shared.stop()
    else:
        print("Post something - it appears on the other side within seconds.")
        print("Ctrl+C stops all four nodes. Data lives in run/.")
        await asyncio.gather(*(run_node(d, gp, hp, interval=2.0)
                               for d, gp, hp in CAST))
```

Guardrail interaction: after `_seed_onions` replaces the TCP rows with onion rows, the Task 5 guardrail keeps a peer's re-gossiped TCP address from re-entering the table for an identity already reachable by onion — so the cast stays on onions for the whole run.

`remove_peer` does not exist yet — add it in this task (see Step 3b). It's a two-line store method with its own test.

- [ ] **Step 5: Verify no-Tor path unaffected + node check**

Run: `.venv\Scripts\python.exe -m pytest tests -q` — Expected: ALL PASS (default suite untouched by Tor; demo import still valid).
Run: `.venv\Scripts\python.exe -c "import hearth.runner, hearth.demo, hearth.cli"` — Expected: no error.

- [ ] **Step 6: Write the TOR_E2E integration test**

Create `tests/test_tor_e2e.py` (skipped unless `TOR_E2E=1` — it launches a real tor and takes ~a minute):

```python
"""Real-Tor end-to-end. Skipped unless TOR_E2E=1 (needs network + tor.exe,
~1 minute). Ports the spike's money test into the suite."""
import asyncio
import os

import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("TOR_E2E") != "1",
    reason="set TOR_E2E=1 to run real-Tor integration (slow, network)")


def test_two_nodes_gossip_over_onions(tmp_path):
    from hearth.node import HearthNode
    from hearth.sync import SyncService
    from hearth.transport import TorTransport
    from hearth.tor import TorProcess, ensure_tor_binary, publish_onion

    async def scenario():
        exe = ensure_tor_binary()
        tor = TorProcess(exe, tmp_path / "tordata")
        await tor.start()
        try:
            wong = HearthNode.create(tmp_path / "w", "Wong", "wong-phone")
            freja = HearthNode.create(tmp_path / "f", "Freja", "freja-phone")
            wong.store.add_identity(freja.identity_pub)
            freja.store.add_identity(wong.identity_pub)
            sw = SyncService(wong, transport=TorTransport(tor.socks_port))
            sf = SyncService(freja, transport=TorTransport(tor.socks_port))
            wp = await sw.start("127.0.0.1", 0)
            fp = await sf.start("127.0.0.1", 0)
            wid, wblob = await publish_onion(tor.control_port,
                                             tor.cookie_path, wp, None)
            fid, fblob = await publish_onion(tor.control_port,
                                             tor.cookie_path, fp, None)
            wong.save_onion_key(wblob); freja.save_onion_key(fblob)
            faddr = f"{fid}.onion:{fp}"
            wong.compose_post("hej over tor")
            # onion may take up to ~a minute to become reachable
            for _ in range(30):
                if await sw.sync_with(faddr):
                    if [p["text"] for p in freja.feed()] == ["hej over tor"]:
                        break
                await asyncio.sleep(3)
            else:
                raise AssertionError("post did not arrive over onion")
            await sw.stop(); await sf.stop()
        finally:
            await tor.stop()
    asyncio.run(scenario())
```

- [ ] **Step 7: Run the E2E test on this machine (manual gate)**

```powershell
$env:TOR_E2E=1; .venv\Scripts\python.exe -m pytest tests/test_tor_e2e.py -v; Remove-Item Env:\TOR_E2E
```

Expected: PASS (may take ~1 minute). Record the wall-clock and outcome in the task report. If the network blocks Tor, record the evidence and mark the E2E as environment-blocked (the default suite still gates the merge).

- [ ] **Step 8: Full default suite**

Run: `.venv\Scripts\python.exe -m pytest tests -q` — Expected: ALL PASS (E2E skipped without the env var).

- [ ] **Step 9: Commit**

```powershell
git add hearth/node.py hearth/store.py hearth/runner.py hearth/cli.py hearth/demo.py tests/test_node_onion.py tests/test_tor_e2e.py
git commit -m "feat: node onion identity + run/demo --tor wiring + TOR_E2E test"
```

---

### Task 7: Manual demo smoke + honest docs

**Files:**
- Modify: `README.md` (honest limits; new Tor section)
- Modify: `ROADMAP.md` (honest-status transport bullet; shipped list; Path-to-shippable Tor entry; follow-ups)

**Interfaces:** docs + one recorded manual run. Wording below is binding (never claim more than transport encryption + endpoint auth + metadata-blind routing; state the plaintext demo default, download size, latency).

- [ ] **Step 1: Manual demo smoke (record in the task report)**

```powershell
Remove-Item -Recurse -Force run -ErrorAction SilentlyContinue
.venv\Scripts\python.exe -m hearth demo --tor
```

Open two of the printed `http://127.0.0.1:720x` UIs, post from one, confirm it arrives on the other over onions (allow ~a minute for first sync), and confirm a DM. Ctrl+C, confirm no `tor.exe` remains (`tasklist | findstr tor`). Record outcome + timing in the report. (If the network blocks Tor, record the evidence; the default suite still gates merge.)

- [ ] **Step 2: README — replace the honest-deviations transport bullet**

Replace:

```markdown
- Transport is signed but PLAINTEXT TCP on localhost. Mandatory before
  any real network: transport encryption. Not wired: Tor (the transport
  interface is shaped for it), encryption at rest, OS-keystore key
  protection, notifications.
```

with:

```markdown
- The default demo transport is signed but PLAINTEXT TCP on localhost.
  Tor is now wired (see below) and is the real-network transport; the
  localhost demo stays plaintext for speed. Not wired: encryption at rest,
  OS-keystore key protection (app-lock is the named follow-up),
  notifications.

## Running over Tor

    .venv\Scripts\python.exe -m hearth demo --tor

Each node becomes reachable at a stable `.onion` address and dials peers
through Tor. The user never installs Tor: Loop resolves `tor.exe`
(bundled with the app, else cached, else a pinned, hash-verified first-run
download of ~21MB) and runs it headless on loopback.

What Tor mode gives, honestly: the transport is encrypted and
endpoint-authenticated to the onion service, and routing is metadata-blind
(no relay of ours ever sees who talks to whom) - on top of the existing
device-key handshake. Costs, stated plainly: first run downloads the Tor
bundle unless it is packaged with the app; onion services take 10-47s to
publish on start; a first sync between two nodes can take about a minute
(steady-state syncs are faster but still seconds, so onion peers sync on a
slower interval than the localhost demo). Windows-x86_64 only for now.
```

- [ ] **Step 3: ROADMAP — four edits**

(a) Honest-status transport bullet — replace:

```markdown
- **Transport is plaintext TCP on localhost.** The system was *built Tor-shaped* (onion-service home node in the design) but Tor is **not yet wired** — see "Path to shippable."
```

with:

```markdown
- **Transport: Tor is wired; plaintext TCP is the demo default.** Nodes run as onion services (`demo --tor`) with encrypted, endpoint-authenticated, metadata-blind transport; the localhost demo stays plaintext TCP for speed. Onion identity is stable across restarts; first sync can take ~a minute (onion peers sync on a slower interval).
```

(b) `## Shipped (7 features)` -> `## Shipped (8 features)`, append after item 7:

```markdown
8. **Tor transport v1** — nodes reachable as stable `.onion` services, dialing peers through a hand-rolled asyncio SOCKS5 dialer; Tor is invisible (managed binary: bundled-with-app → cached → pinned hash-verified download, headless on loopback); dual-stack onion-preferred with a privacy guardrail (never gossip a non-onion address for a peer with a known onion); 45s onion sync cadence. Real-Tor path proven by a `TOR_E2E=1`-gated test + spike (`docs/spikes/2026-07-03-tor-feasibility-spike.md`). Windows-x86_64 only for now.
```

(c) "Path to shippable" Tor bullet — replace:

```markdown
- **Tor** — the natural next infra step. Swap the TCP transport for a SOCKS dialer, run an onion service per node (especially the home node), bundle Tor on Windows. No redesign — the system was built for it.
```

with:

```markdown
- **Tor** — DONE (shipped feature 8). Persistent connections over Tor (dial once, keep the socket) is the named latency follow-up; macOS/Linux tor binaries and desktop-app packaging (bundle tor.exe so even the first-run download disappears) are the remaining pieces.
```

(d) Add to the "Big lifts (post-Tor)" or an appropriate near-term slot — desktop packaging:

```markdown
- **Desktop app packaging** — ship Loop as an installer/app that bundles `tor.exe` in the package (the Tor manager already resolves a bundled binary first, so this is packaging, not code). Removes the first-run download.
```

- [ ] **Step 4: Full suite**

Run: `.venv\Scripts\python.exe -m pytest tests -q` — Expected: ALL PASS.

- [ ] **Step 5: Commit**

```powershell
git add README.md ROADMAP.md
git commit -m "docs: Tor transport - honest limits, run-over-Tor section, roadmap"
```

---

## Completion

After Task 7: whole-branch review (superpowers:requesting-code-review) on the most capable model — the reviewer should pay special attention to the control-protocol client, process cleanup (no orphaned tor.exe), the privacy guardrail's correctness against the have-frame merge, and the `TOR_SHA256` pin being real (not the placeholder). Then superpowers:finishing-a-development-branch — merge `tor-transport` to `main`, push to origin. Workstream 4 (app-lock) starts after this merge.
