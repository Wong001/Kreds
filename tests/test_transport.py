import asyncio

import pytest

from hearth.tor import ONION_VIRTUAL_PORT
from hearth.transport import (MAX_FRAME, TcpTransport, TorTransport,
                              read_frame, write_frame)


def test_frame_roundtrip_over_real_socket():
    async def scenario():
        t = TcpTransport()

        async def echo(reader, writer):
            frame = await read_frame(reader)
            await write_frame(writer, {"echo": frame})
            writer.close()

        server = await t.serve("127.0.0.1", 0, echo)
        port = server.sockets[0].getsockname()[1]
        reader, writer = await t.connect(f"127.0.0.1:{port}")
        await write_frame(writer, {"t": "hello", "n": 42})
        reply = await read_frame(reader)
        writer.close()
        server.close()
        await server.wait_closed()
        return reply

    assert asyncio.run(scenario()) == {"echo": {"t": "hello", "n": 42}}


def test_oversized_frame_rejected():
    async def scenario():
        class W:                       # capture-only fake writer
            def __init__(self):
                self.data = b""
            def write(self, b):
                self.data += b
            async def drain(self):
                pass

        with pytest.raises(ValueError):
            await write_frame(W(), {"x": "a" * (MAX_FRAME + 1)})

    asyncio.run(scenario())


def test_onion_dial_normalizes_to_fixed_port(monkeypatch):
    # A stale stored port in an .onion address must be ignored -- the dial
    # always targets ONION_VIRTUAL_PORT, so peers deadlocked on old random
    # ports recover once both ends are on 0.3.14 (no data migration).
    seen = {}

    async def fake_socks(socks_host, socks_port, host, port, timeout=30.0):
        seen["host"] = host
        seen["port"] = port
        return (object(), object())            # (reader, writer) placeholder

    monkeypatch.setattr("hearth.transport.socks_connect", fake_socks)
    t = TorTransport(socks_port=9050)

    async def scenario():
        await t.connect("abcdefghij.onion:1117")   # stale port 1117

    asyncio.run(scenario())
    assert seen["host"] == "abcdefghij.onion"
    assert seen["port"] == ONION_VIRTUAL_PORT       # normalized, not 1117


def test_tcp_dial_keeps_its_real_port(monkeypatch):
    seen = {}

    async def fake_open(host, port):
        seen["host"] = host
        seen["port"] = port
        return (object(), object())

    monkeypatch.setattr("hearth.transport.asyncio.open_connection", fake_open)
    t = TorTransport(socks_port=9050)

    async def scenario():
        await t.connect("127.0.0.1:22299")          # dev TCP, unchanged
    asyncio.run(scenario())
    assert seen == {"host": "127.0.0.1", "port": 22299}
