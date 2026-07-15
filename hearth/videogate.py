"""Video transcode gate: re-encode Story videos to known-good muted MP4.

Mirrors the image gate: decode the upload and re-encode via a bundled
ffmpeg so viewers only ever render bytes WE produced. Author-side gate
(a modified peer can still put raw bytes behind a hash, as post photos do,
served nosniff and never as HTML). Reject over-length/over-size instead of
trimming, for predictability. Audio is stripped in v1 (muted stories)."""
from __future__ import annotations

import os
import re
import subprocess
import tempfile

import imageio_ffmpeg

from .imagegate import transcode as image_transcode

MAX_VIDEO_SECONDS = 15.0
VIDEO_MAX_DIM = 720
STORY_IMAGE_MAX = 1080
# Video's own long-standing transcoded-output bound. Deliberately does NOT
# ride MAX_BLOB_BYTES (whole-branch review, Finding 2): when 0.3.11 raised
# the protocol blob cap 5->10 MB to give the photo gate's compressed output
# room, the video path was spec'd as untouched -- this constant keeps the
# video transcode gate's output ceiling at its original 5 MB regardless of
# where MAX_BLOB_BYTES goes.
MAX_VIDEO_BYTES = 5 * 1024 * 1024
_TIMEOUT = 60

_DUR = re.compile(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)")


def _ff() -> str:
    return imageio_ffmpeg.get_ffmpeg_exe()


def _run(args):
    try:
        return subprocess.run([_ff(), "-nostdin", *args],
                              capture_output=True, text=False,
                              timeout=_TIMEOUT)
    except subprocess.TimeoutExpired:
        raise ValueError("video processing timed out")


def probe_duration(data: bytes) -> float:
    with tempfile.TemporaryDirectory() as d:
        p = os.path.join(d, "in")
        with open(p, "wb") as f:
            f.write(data)
        try:
            stderr = subprocess.run([_ff(), "-nostdin", "-protocol_whitelist",
                                     "file", "-i", p],
                                    capture_output=True, text=True,
                                    timeout=_TIMEOUT).stderr
        except subprocess.TimeoutExpired:
            raise ValueError("video processing timed out")
    m = _DUR.search(stderr)
    if not m:
        raise ValueError("not a video")
    h, mm, ss = m.groups()
    return int(h) * 3600 + int(mm) * 60 + float(ss)


def transcode_video(data: bytes) -> tuple[bytes, bytes]:
    if probe_duration(data) > MAX_VIDEO_SECONDS:
        raise ValueError("video longer than 15 seconds")
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "in")
        out = os.path.join(d, "out.mp4")
        frame = os.path.join(d, "poster.png")
        with open(src, "wb") as f:
            f.write(data)
        # Downscale to <=720 tall keeping aspect (even dims), strip audio,
        # H.264 MP4. The comma inside min() must be escaped for ffmpeg's
        # filter parser (raw string), else it reads as a filter separator.
        # Height is forced even (trunc(ih/2)*2) to satisfy yuv420p/libx264.
        vf = f"scale=w=-2:h=min({VIDEO_MAX_DIM}\\,trunc(ih/2)*2)"
        enc = _run(["-protocol_whitelist", "file", "-i", src, "-vf", vf,
                    "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                    "-crf", "28", "-maxrate", "2500k", "-bufsize", "5000k",
                    "-preset", "veryfast", "-movflags",
                    "+faststart", "-y", out])
        if enc.returncode != 0 or not os.path.exists(out):
            raise ValueError("could not transcode video")
        with open(out, "rb") as f:
            mp4 = f.read()
        if len(mp4) > MAX_VIDEO_BYTES:
            raise ValueError("transcoded video exceeds 5 MB")
        pf = _run(["-protocol_whitelist", "file", "-i", out, "-frames:v", "1",
                   "-f", "image2", "-y", frame])
        if pf.returncode != 0 or not os.path.exists(frame):
            raise ValueError("could not extract poster")
        with open(frame, "rb") as f:
            poster_data = f.read()
        poster = image_transcode(poster_data, STORY_IMAGE_MAX)
    return mp4, poster
