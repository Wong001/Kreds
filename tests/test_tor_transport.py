import asyncio
import struct

from hearth.tor import ONION_VIRTUAL_PORT
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
        # 15001 is a stale/arbitrary stored port; the dial must normalize to
        # the fixed ONION_VIRTUAL_PORT regardless (0.3.14 dial normalization).
        reader, writer = await t.connect("abc.onion:15001")
        assert seen == [("abc.onion", ONION_VIRTUAL_PORT)]
        writer.close(); server.close()
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
        writer.close(); srv.close()
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
        w.close(); server.close()
        assert hits == [1]
    asyncio.run(scenario())
