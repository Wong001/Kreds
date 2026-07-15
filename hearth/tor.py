"""Tor management for Kreds: resolve/launch the binary and publish onion
services. The user never 'downloads Tor' as an action -- the app resolves
tor.exe (bundled with the app, else cached in app data, else a pinned,
hash-verified first-run download) and runs it headless on loopback.

Windows-x86_64 only for now (stated in README). No stem dependency: the
control protocol is spoken directly (Task 3)."""
from __future__ import annotations

import asyncio
import hashlib
import os
import re
import subprocess
import sys
import tarfile
import time
import urllib.request
from pathlib import Path
from typing import Optional

# Pinned Tor Expert Bundle (windows-x86_64). Update together; a mismatch
# is a hard refusal, never a silent run of an unverified binary.
TOR_VERSION = "15.0.17"
TOR_URL = ("https://dist.torproject.org/torbrowser/" + TOR_VERSION +
           "/tor-expert-bundle-windows-x86_64-" + TOR_VERSION + ".tar.gz")
TOR_SHA256 = "5f91e9426bf641dfe539dc28029088c72bed0b1d8f1c79104a0f89273cb3ebe1"   # from torproject checksums file

# Per-socket-operation timeout for the first-run download. Without this,
# urlretrieve has no timeout at all -- a stalled/intercepted connection
# (captive portal, filtering network) hangs the first run forever.
TOR_DOWNLOAD_TIMEOUT = 60.0


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
    # (a) bundled with the app. bundled_dir=None defaults to the frozen
    # (packaged) app's bundled tor dir so a packaged run finds tor.exe
    # without callers having to know about paths.py; non-frozen (dev/tests)
    # keeps today's behavior of falling straight through to the cache/
    # download path below. Lazy import: paths.py is a leaf module but this
    # keeps tor.py importable standalone with zero import-order coupling.
    if bundled_dir is None:
        from . import paths
        if paths.is_frozen():
            bundled_dir = paths.bundled_tor_dir()
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
    with urllib.request.urlopen(TOR_URL, timeout=TOR_DOWNLOAD_TIMEOUT) as resp, \
            open(archive, "wb") as f:
        while True:
            chunk = resp.read(1 << 16)
            if not chunk:
                break
            f.write(chunk)
    if not verify_sha256(archive, TOR_SHA256):
        archive.unlink(missing_ok=True)
        raise RuntimeError("tor bundle SHA256 mismatch -- refusing to run")
    with tarfile.open(archive, "r:gz") as tf:
        tf.extractall(app, filter="data")
    archive.unlink(missing_ok=True)
    exe = _tor_exe_in(app)
    if exe is None:
        raise RuntimeError("tor.exe not present after extraction")
    return exe


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
        "Log notice stdout\n"
        f"__OwningControllerProcess {os.getpid()}\n")
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
        self._drain_task = None

    async def start(self, bootstrap_timeout: float = 90.0, status=None, waiting=None):
        torrc = _gen_torrc(self.data_dir, self.socks_port,
                           self.control_port)
        # CREATE_NO_WINDOW: tor.exe is a console program; when a windowed
        # (no-console) packaged app spawns it, Windows otherwise pops a blank
        # console window whose close button KILLS tor -> the node freezes
        # mid-setup. Hide it entirely. Windows-only flag; 0 (no-op) elsewhere.
        creationflags = (subprocess.CREATE_NO_WINDOW
                         if sys.platform == "win32" else 0)
        # Spawn-exit vs. bootstrap-timeout retries: tor can fail to bootstrap
        # in two ways. (1) Spawn-exit: tor dies before emitting a bootstrap
        # line (signature of a prior tor holding the ports). Retry within a
        # time window (_SPAWN_RETRY_WINDOW) to outlast the orphan's ~15s
        # self-reap. (2) Bootstrap timeout: tor alive but slow, same 2-attempt
        # budget as before. Call waiting() (no args, exceptions swallowed) each
        # time a spawn-exit retry is scheduled.
        _BOOTSTRAP_RE = re.compile(rb"Bootstrapped (\d+)%")
        async def _await_bootstrap():
            while True:
                raw = await self._proc.stdout.readline()
                if not raw:
                    raise RuntimeError("tor exited before bootstrap")
                m = _BOOTSTRAP_RE.search(raw)
                if m:
                    pct = int(m.group(1))
                    if status is not None:
                        try:
                            status(pct)
                        except Exception:
                            pass       # progress display must never kill tor startup
                    if pct >= 100:
                        return

        last_err = None
        attempts = 0
        timeout_attempts = 0
        first_spawn = time.monotonic()
        while True:
            attempts += 1
            self._proc = await asyncio.create_subprocess_exec(
                str(self.exe), "-f", str(torrc),
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.STDOUT,
                creationflags=creationflags)
            try:
                await asyncio.wait_for(_await_bootstrap(),
                                       timeout=bootstrap_timeout)
                break                                   # bootstrapped
            except (RuntimeError, asyncio.TimeoutError) as e:
                last_err = e
                try:                                    # reap the failed tor
                    if self._proc.returncode is None:
                        self._proc.terminate()
                        await asyncio.wait_for(self._proc.wait(), timeout=5.0)
                except Exception:
                    pass
                if isinstance(e, asyncio.TimeoutError):
                    # tor alive but slow: same 2-attempt budget as always
                    timeout_attempts += 1
                    if timeout_attempts >= 2:
                        raise RuntimeError(
                            f"tor failed to bootstrap after {attempts} "
                            f"attempts: {last_err}")
                else:
                    # spawn-exit: the orphan signature (see class attrs)
                    if time.monotonic() - first_spawn >= self._SPAWN_RETRY_WINDOW:
                        raise RuntimeError(
                            f"tor failed to bootstrap after {attempts} "
                            f"attempts: {last_err}")
                    if waiting is not None:
                        try:
                            waiting()
                        except Exception:
                            pass    # display must never kill tor startup
                await asyncio.sleep(self._SPAWN_RETRY_GAP)

        # Tor keeps logging (heartbeats, warnings) after bootstrap; the
        # torrc has "Log notice stdout" so if nobody keeps reading, the
        # ~64KB OS pipe buffer fills and tor blocks on its log write,
        # freezing the whole daemon. Drain to EOF in the background.
        async def _drain():
            try:
                while await self._proc.stdout.readline():
                    pass
            except Exception:
                pass
        self._drain_task = asyncio.create_task(_drain())

    # Spawn-exit retry (0.3.12): a tor that EXITS instantly at spawn is the
    # signature of a prior instance's orphaned tor still holding the fixed
    # socks/control ports + tordata lock. The orphan self-reaps within ~15s
    # of its owner's death (__OwningControllerProcess polling), so retry for
    # 2x that. Bootstrap TIMEOUTS are a different failure (tor alive but
    # slow) and keep their own 2-attempt budget.
    _SPAWN_RETRY_GAP = 5.0
    _SPAWN_RETRY_WINDOW = 30.0

    _SHUTDOWN_GRACE = 5.0

    async def stop(self):
        if self._drain_task is not None:
            self._drain_task.cancel()
        if self._proc is None:
            return
        # Graceful first: a hard TerminateProcess can leave tor's consensus
        # cache unclean, forcing the NEXT start into a cold bootstrap (the
        # post-update always-fails pattern, 0.3.11). SIGNAL SHUTDOWN lets
        # tor flush state; fall back to the old hard path if it's ignored.
        try:
            await _control_command(self.control_port, self.cookie_path,
                                   "SIGNAL SHUTDOWN")
            await asyncio.wait_for(self._proc.wait(),
                                   timeout=self._SHUTDOWN_GRACE)
            self._proc = None
            return
        except Exception:
            pass                        # control conn dead / ignored: hard path
        try:
            self._proc.terminate()
        except ProcessLookupError:
            pass
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
            # Tor control replies are multi-line: continuation lines use
            # '-'/'+' after the 3-digit status, the FINAL line uses a space.
            # Terminate on any final line (success 250 or any error code) so
            # an ADD_ONION failure surfaces instead of blocking readline().
            if len(text) >= 4 and text[3] == " ":
                break
        return lines
    finally:
        writer.close()


async def publish_onion(control_port: int, cookie_path: Path,
                        local_port: int, key_blob=None):
    key_spec = key_blob if key_blob else "NEW:ED25519-V3"
    # Flags=Detach: without it, Tor removes an ephemeral onion the moment the
    # control connection that created it closes -- and _control_command closes
    # per call, so a non-detached service would die the instant this returns.
    cmd = (f"ADD_ONION {key_spec} Flags=Detach "
           f"Port={local_port},127.0.0.1:{local_port}")
    reply_lines = await _control_command(control_port, cookie_path, cmd)
    fields = _parse_control_reply(reply_lines)
    service_id = fields.get("ServiceID")
    if service_id is None:
        raise RuntimeError(
            "ADD_ONION returned no ServiceID: " + " | ".join(reply_lines))
    # On a NEW key Tor returns PrivateKey=ED25519-V3:<blob>; on republish
    # from a saved blob it echoes nothing, so keep what we sent.
    returned = fields.get("PrivateKey")
    return service_id, (returned if returned else key_blob)
