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
            await asyncio.sleep(30)      # hold open: client must hit timeout,
            return                       # not EOF (cancelled at loop exit)
        if truncate:
            writer.write(b"\x05")                        # short reply -> EOF
            await writer.drain()
        else:
            # ver, rep, rsv, atyp=domain, len 0, no bound addr, port 0
            writer.write(b"\x05" + bytes([reply_rep])
                         + b"\x00\x03\x00\x00\x00")
            await writer.drain()
        writer.close()                   # close server side; no lingering conn

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
    asyncio.run(scenario())


def test_socks_connect_refusal_raises_oserror():
    async def scenario():
        host, port, server, _ = await _fake_socks_server(reply_rep=0x05)
        with pytest.raises(OSError):
            await socks_connect(host, port, "abc.onion", 15001)
        server.close()
    asyncio.run(scenario())


def test_socks_connect_truncated_reply_raises_oserror():
    async def scenario():
        host, port, server, _ = await _fake_socks_server(truncate=True)
        with pytest.raises(OSError):
            await socks_connect(host, port, "abc.onion", 15001)
        server.close()
    asyncio.run(scenario())


def test_socks_connect_timeout_raises_oserror():
    async def scenario():
        host, port, server, _ = await _fake_socks_server(hang=True)
        with pytest.raises(OSError):
            await socks_connect(host, port, "abc.onion", 15001,
                                timeout=0.3)
        server.close()
    asyncio.run(scenario())
