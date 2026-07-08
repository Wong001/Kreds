"""Gossip transport: length-prefixed JSON frames over TCP.

Deliberately tiny surface so a Tor SOCKS dialer can replace TcpTransport
without touching the sync protocol (spec: D1 standing requirement)."""
from __future__ import annotations

import asyncio
import json
import struct

from .socks import socks_connect

MAX_FRAME = 16 * 1024 * 1024


async def write_frame(writer, obj: dict):
    data = json.dumps(obj, separators=(",", ":")).encode()
    if len(data) > MAX_FRAME:
        raise ValueError("frame too large")
    writer.write(struct.pack(">I", len(data)) + data)
    await writer.drain()


async def read_frame(reader) -> dict:
    header = await reader.readexactly(4)
    (n,) = struct.unpack(">I", header)
    if n > MAX_FRAME:
        raise ValueError("frame too large")
    return json.loads(await reader.readexactly(n))


class TcpTransport:
    async def connect(self, address: str):
        host, port = address.rsplit(":", 1)
        return await asyncio.open_connection(host, int(port))

    async def serve(self, host: str, port: int, handler):
        return await asyncio.start_server(handler, host, port)


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
            # Spike measured onion dials up to 43s; socks_connect's 30s
            # default would spuriously fail tail-latency first dials.
            return await socks_connect("127.0.0.1", self.socks_port,
                                       host, int(port), timeout=60.0)
        return await asyncio.open_connection(host, int(port))

    async def serve(self, host: str, port: int, handler):
        return await asyncio.start_server(handler, host, port)
