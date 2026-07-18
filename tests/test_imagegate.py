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


import os

from hearth.imagegate import PHOTO_MAX, PHOTO_CAP, transcode_photo


def noise_jpeg_bytes(w, h, quality=95):
    # Random noise compresses terribly - the cheapest way to make a
    # genuinely multi-megabyte JPEG without a fixture file.
    img = Image.frombytes("RGB", (w, h), os.urandom(w * h * 3))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=quality)
    return buf.getvalue()


def test_big_photo_compressed_under_cap_and_downscaled():
    src = noise_jpeg_bytes(4000, 3000)
    assert len(src) > 5 * 1024 * 1024        # premise: bigger than the OLD cap
    out = transcode_photo(src)
    assert len(out) <= PHOTO_CAP
    img = Image.open(io.BytesIO(out))
    assert img.format == "AVIF"              # superseded pin: was JPEG pre-AVIF ladder
    assert max(img.size) <= PHOTO_MAX
    assert abs(img.size[0] / img.size[1] - 4 / 3) < 0.05   # aspect kept


def test_orientation_baked_in_and_exif_stripped():
    img = Image.new("RGB", (100, 50), (10, 200, 30))
    exif = Image.Exif()
    exif[274] = 6                             # Orientation: rotate 90 CW
    buf = io.BytesIO()
    img.save(buf, format="JPEG", exif=exif)
    out = transcode_photo(buf.getvalue())
    outimg = Image.open(io.BytesIO(out))
    assert outimg.size == (50, 100)           # transpose applied to pixels
    assert dict(outimg.getexif()) == {}       # no metadata carried (incl. GPS)


def test_png_screenshot_becomes_avif():
    # Superseded pin: PNG-stays-PNG rule retired (spec 2026-07-18 Part 4) --
    # screenshots now land as AVIF too, same as every other photo input.
    out = transcode_photo(png_bytes(800, 600))
    assert Image.open(io.BytesIO(out)).format == "AVIF"


def test_never_upscales():
    out = transcode_photo(png_bytes(64, 64))
    assert Image.open(io.BytesIO(out)).size == (64, 64)


def test_animated_gif_passes_through_byte_identical():
    src = animated_gif_bytes()
    assert transcode_photo(src) == src


def test_oversized_gif_rejected_honestly():
    src = animated_gif_bytes()
    with pytest.raises(ValueError, match="animations can't be compressed"):
        transcode_photo(src, cap=len(src) - 1)


def test_photo_non_image_rejected():
    with pytest.raises(ValueError, match="not an image"):
        transcode_photo(b"definitely not pixels" * 100)


def test_dimension_ladder_when_quality_floor_is_not_enough():
    # A tiny artificial cap forces the gate past q40 into halving the
    # dimensions - output must still be a valid image under the cap.
    out = transcode_photo(noise_jpeg_bytes(800, 600), cap=15_000)
    assert len(out) <= 15_000
    img = Image.open(io.BytesIO(out))
    assert img.format == "AVIF"               # superseded pin: was JPEG
    assert max(img.size) <= 400               # at least one halving happened


from hearth.imagegate import (photo_thumb, transcode, transcode_photo,
                              THUMB_MAX, PHOTO_MAX)


def _photo_bytes(w=3000, h=2000, fmt="JPEG"):
    buf = io.BytesIO()
    img = Image.new("RGB", (w, h))
    # gradient, so lossy encoders have real work (a flat color compresses
    # to nothing and hides size regressions)
    px = img.load()
    for x in range(0, w, 4):
        for y in range(0, h, 4):
            px[x, y] = (x % 256, y % 256, (x + y) % 256)
    img.save(buf, format=fmt)
    return buf.getvalue()


def _fmt(data):
    return Image.open(io.BytesIO(data)).format


def test_photo_gate_emits_avif_now():
    out = transcode_photo(_photo_bytes())
    assert _fmt(out) == "AVIF"
    assert max(Image.open(io.BytesIO(out)).size) <= PHOTO_MAX


def test_png_input_becomes_avif_too():
    # PNG-stays-PNG retired (spec Part 4): screenshots gain the most.
    out = transcode_photo(_photo_bytes(800, 600, fmt="PNG"))
    assert _fmt(out) == "AVIF"


def test_gif_passthrough_unchanged():
    buf = io.BytesIO()
    frames = [Image.new("P", (60, 60), c) for c in (0, 100)]
    frames[0].save(buf, format="GIF", save_all=True,
                   append_images=frames[1:])
    raw = buf.getvalue()
    assert transcode_photo(raw) == raw


def test_photo_thumb_small_avif():
    gated = transcode_photo(_photo_bytes())
    th = photo_thumb(gated)
    assert _fmt(th) == "AVIF"
    assert max(Image.open(io.BytesIO(th)).size) <= THUMB_MAX
    assert len(th) < 100 * 1024          # generous ceiling; typical ~25KB


def test_photo_thumb_rejects_gif_and_junk():
    import pytest
    buf = io.BytesIO()
    Image.new("P", (60, 60)).save(buf, format="GIF")
    with pytest.raises(ValueError):
        photo_thumb(buf.getvalue())      # GIF: no thumb (animation on tiles)
    with pytest.raises(ValueError):
        photo_thumb(b"not an image")


def test_transcode_fmt_param():
    src = _photo_bytes(900, 900, fmt="PNG")
    assert _fmt(transcode(src, 512)) == "PNG"            # default unchanged
    assert _fmt(transcode(src, 512, fmt="avif")) == "AVIF"
