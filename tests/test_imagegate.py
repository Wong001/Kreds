import io

import pytest
from PIL import Image

from hearth.imagegate import AVATAR_MAX, transcode


def png_bytes(w, h, color=(200, 80, 80)):
    buf = io.BytesIO()
    Image.new("RGB", (w, h), color).save(buf, format="PNG")
    return buf.getvalue()


def animated_gif_bytes():
    buf = io.BytesIO()
    frames = [Image.new("P", (40, 40), i) for i in (1, 2, 3)]
    frames[0].save(buf, format="GIF", save_all=True,
                   append_images=frames[1:], duration=100, loop=0)
    return buf.getvalue()


def test_reencodes_to_png_and_downsizes():
    out = transcode(png_bytes(2000, 1000), AVATAR_MAX)
    img = Image.open(io.BytesIO(out))
    assert img.format == "PNG"
    assert max(img.size) <= AVATAR_MAX
    # aspect preserved (2:1)
    assert abs(img.size[0] / img.size[1] - 2.0) < 0.05


def test_small_image_not_upscaled():
    out = transcode(png_bytes(64, 64), AVATAR_MAX)
    assert Image.open(io.BytesIO(out)).size == (64, 64)


def test_animation_reduced_to_single_frame():
    out = transcode(animated_gif_bytes(), AVATAR_MAX)
    img = Image.open(io.BytesIO(out))
    assert img.format == "PNG"
    assert getattr(img, "n_frames", 1) == 1     # PNG static, one frame


def test_non_image_rejected():
    with pytest.raises(ValueError):
        transcode(b"this is not an image", AVATAR_MAX)
    with pytest.raises(ValueError):
        transcode(b"", AVATAR_MAX)


def test_reencode_strips_injected_metadata():
    from PIL import PngImagePlugin
    buf = io.BytesIO()
    info = PngImagePlugin.PngInfo()
    info.add_text("evil", "x" * 500)
    Image.new("RGB", (64, 64), (10, 20, 30)).save(buf, format="PNG",
                                                   pnginfo=info)
    src = buf.getvalue()
    assert b"evil" in src                    # injected metadata present in input
    out = transcode(src, AVATAR_MAX)
    assert b"evil" not in out                # re-encode stripped it


def test_decompression_bomb_rejected_as_valueerror():
    # A tiny PNG whose IHDR claims enormous dimensions trips Pillow's bomb
    # guard; the gate must surface it as the contracted ValueError, not a
    # raw DecompressionBombError.
    import struct, zlib
    sig = b"\x89PNG\r\n\x1a\n"
    ihdr_data = struct.pack(">IIBBBBB", 50000, 50000, 8, 2, 0, 0, 0)
    def chunk(typ, data):
        return (struct.pack(">I", len(data)) + typ + data
                + struct.pack(">I", zlib.crc32(typ + data) & 0xffffffff))
    bomb = sig + chunk(b"IHDR", ihdr_data) + chunk(b"IEND", b"")
    with pytest.raises(ValueError):
        transcode(bomb, AVATAR_MAX)
