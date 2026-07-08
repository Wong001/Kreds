import asyncio

import pytest

from hearth.transport import MAX_FRAME, TcpTransport, read_frame, write_frame


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
