"""Server-side image gate: re-encode uploads to known-good static bytes.

Every avatar/banner an uploader sends is opened, reduced to a single
static frame, downsized, and re-encoded to PNG before storage, so every
viewer only ever renders bytes WE produced -- closing the decoder-exploit
and decompression-bomb surface for image inputs (spec: transcode gate).
Animated inputs are flattened to their first frame; animated avatars are a
deferred feature (video/animation pipeline). This holds for images
uploaded through this node; over gossip a modified peer can still
reference raw image bytes behind a hash, exactly as post photos do, so
the viewer's browser image decoder is still exposed to peer-supplied
bytes (served with nosniff, never as HTML)."""
from __future__ import annotations

import io

from PIL import Image, UnidentifiedImageError

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
