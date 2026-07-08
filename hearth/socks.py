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
    except asyncio.IncompleteReadError as e:
        # A truncated/closed reply is EOFError, not OSError; the sync layer
        # only treats OSError as 'peer offline', so normalize it.
        writer.close()
        raise ConnectionError("SOCKS5 reply truncated") from e
    except BaseException:
        writer.close()
        raise
