"""Server-side image gate: re-encode uploads to known-good static bytes.

Every avatar/banner an uploader sends is opened, reduced to a single
static frame, downsized, and re-encoded to PNG before storage, so every
viewer only ever renders bytes WE produced -- closing the decoder-exploit
and decompression-bomb surface for image inputs (spec: transcode gate).
Animated inputs are flattened to their first frame; animated avatars are a
deferred feature (video/animation pipeline). This holds for images
uploaded through this node; over gossip a modified peer can still
reference raw image bytes behind a hash, so the viewer's browser image
decoder is still exposed to peer-supplied bytes (served with nosniff,
never as HTML). Post/DM photos go through transcode_photo below, with
one deliberate exception: animated GIFs pass through raw so animation
survives -- for that one format the local decoder-exploit surface is
accepted, unchanged from the pre-gate behavior."""
from __future__ import annotations

import io

from PIL import Image, ImageOps, UnidentifiedImageError

from .messages import MAX_BLOB_BYTES

AVATAR_MAX = 512
BANNER_MAX = 1500


def transcode(data: bytes, max_dim: int) -> bytes:
    try:
        img = Image.open(io.BytesIO(data))
        img.load()                       # force decode (raises on truncated)
    except (UnidentifiedImageError, OSError, ValueError,
            Image.DecompressionBombError):
        raise ValueError("not an image")
    # First frame only (strips animation).
    if getattr(img, "is_animated", False):
        img.seek(0)
    # Flatten to RGB on white (drops alpha / palette / exotic modes).
    if img.mode != "RGB":
        bg = Image.new("RGB", img.size, (255, 255, 255))
        rgb = img.convert("RGBA")
        bg.paste(rgb, mask=rgb.split()[-1])
        img = bg
    # Downscale only (never upscale), aspect preserved.
    if max(img.size) > max_dim:
        img.thumbnail((max_dim, max_dim), Image.LANCZOS)
    out = io.BytesIO()
    img.save(out, format="PNG")
    return out.getvalue()


PHOTO_MAX = 2560                       # long edge for post/DM photos
_PHOTO_QUALITIES = (85, 75, 65, 55)    # JPEG ladder before dimensions halve
PHOTO_CAP = MAX_BLOB_BYTES - 64        # encrypt_blob adds nonce+tag (28 B);
                                       # 64 B margin keeps ciphertext under
                                       # the store's MAX_BLOB_BYTES check


def transcode_photo(data: bytes, cap: int = PHOTO_CAP) -> bytes:
    """Post/DM photo gate: orientation baked in, metadata (EXIF/GPS)
    dropped, downscaled to PHOTO_MAX, recompressed until the result fits
    `cap` (so the encrypted blob fits MAX_BLOB_BYTES). PNG input stays
    PNG when the lossless re-encode fits (screenshots stay crisp);
    everything else lands as JPEG. Animated GIFs pass through raw --
    animation preserved, cannot be compressed here, over-cap refused."""
    if data[:4] == b"GIF8":
        if len(data) > cap:
            raise ValueError("animated GIF exceeds the 10 MB cap - "
                             "animations can't be compressed")
        return data
    try:
        img = Image.open(io.BytesIO(data))
        img.load()                       # force decode (raises on truncated)
    except (UnidentifiedImageError, OSError, ValueError,
            Image.DecompressionBombError):
        raise ValueError("not an image")
    src_png = img.format == "PNG"        # .format is lost after transpose
    if getattr(img, "is_animated", False):
        img.seek(0)                      # first frame (animated webp etc.)
    img = ImageOps.exif_transpose(img)   # bake orientation BEFORE exif drops
    if max(img.size) > PHOTO_MAX:
        img.thumbnail((PHOTO_MAX, PHOTO_MAX), Image.LANCZOS)
    if src_png:
        out = io.BytesIO()
        img.save(out, format="PNG")      # re-encode: no source chunks carried
        if out.tell() <= cap:
            return out.getvalue()
    rgb = img
    if rgb.mode != "RGB":
        bg = Image.new("RGB", rgb.size, (255, 255, 255))
        rgba = rgb.convert("RGBA")
        bg.paste(rgba, mask=rgba.split()[-1])
        rgb = bg
    while True:
        for q in _PHOTO_QUALITIES:
            out = io.BytesIO()
            rgb.save(out, format="JPEG", quality=q, optimize=True)
            if out.tell() <= cap:
                return out.getvalue()
        w, h = rgb.size
        if max(w, h) <= 64:              # unreachable for any real photo;
            raise ValueError("image cannot fit the blob cap")
        rgb = rgb.resize((max(1, w // 2), max(1, h // 2)), Image.LANCZOS)
