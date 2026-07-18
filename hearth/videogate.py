"""Video transcode gate: re-encode Story/wall videos to known-good muted MP4.

Mirrors the image gate: decode the upload and re-encode via a bundled
ffmpeg so viewers only ever render bytes WE produced. Author-side gate
(a modified peer can still put raw bytes behind a hash, as post photos do,
served nosniff and never as HTML). With a video_edit (spec 2026-07-18)
the gate CUTS a <=15s window / crops / picks the poster frame; without
one, over-length is rejected exactly as before. Audio is stripped."""
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


def validate_video_edit(edit) -> dict:
    """Normalize a client video_edit (spec 2026-07-18). Raises ValueError.

    {"start": s>=0, "duration": 0<d<=15, "crop": {x,y,w,h} normalized
    to the DISPLAY-oriented frame (0..1, min 0.1, inside bounds) or
    None, "poster_t": 0<=t<=duration into the cut}.
    edit is intentionally unannotated: it accepts anything and raises
    ValueError itself when it isn't a dict (the type promise lives on
    transcode_video's signature, which is the public surface)."""
    if not isinstance(edit, dict):
        raise ValueError("bad video_edit")
    try:
        start = float(edit["start"])
        duration = float(edit["duration"])
        poster_t = float(edit.get("poster_t", 0.0))
    except (KeyError, TypeError, ValueError):
        raise ValueError("bad video_edit")
    if not (start >= 0.0 and 0.0 < duration <= MAX_VIDEO_SECONDS):
        raise ValueError("trim window must be within 15 seconds")
    if not (0.0 <= poster_t <= duration):
        raise ValueError("cover time is outside the trim window")
    crop = edit.get("crop")
    if crop is not None:
        try:
            x, y, w, h = (float(crop[k]) for k in ("x", "y", "w", "h"))
        except (KeyError, TypeError, ValueError):
            raise ValueError("bad crop")
        # 1e-4 slack: client floats may land at 1.0000001 after math
        if not (x >= 0.0 and y >= 0.0 and w >= 0.1 and h >= 0.1
                and x + w <= 1.0 + 1e-4 and y + h <= 1.0 + 1e-4):
            raise ValueError("bad crop")
        crop = {"x": x, "y": y, "w": min(w, 1.0 - x), "h": min(h, 1.0 - y)}
    return {"start": start, "duration": duration,
            "crop": crop, "poster_t": poster_t}


def transcode_video(data: bytes, edit: dict | None = None) -> tuple[bytes, bytes]:
    if edit is not None:
        edit = validate_video_edit(edit)
    src_dur = probe_duration(data)
    if edit is None and src_dur > MAX_VIDEO_SECONDS:
        raise ValueError("video longer than 15 seconds")
    if edit is not None and edit["start"] >= src_dur:
        raise ValueError("trim window starts past the end of the video")
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
        cut = []
        if edit is not None:
            # -ss/-t BEFORE -i: fast keyframe seek; output is
            # frame-accurate anyway because we re-encode.
            cut = ["-ss", f"{edit['start']:.3f}",
                   "-t", f"{edit['duration']:.3f}"]
            if edit["crop"] is not None:
                c = edit["crop"]
                # iw/ih are the DISPLAY-oriented dims (ffmpeg autorotates
                # on decode by default - do not pass -noautorotate; the
                # browser preview the user cropped against agrees).
                vf = (f"crop=iw*{c['w']:.6f}:ih*{c['h']:.6f}"
                      f":iw*{c['x']:.6f}:ih*{c['y']:.6f}," + vf)
        enc = _run(["-protocol_whitelist", "file", *cut, "-i", src,
                    "-vf", vf,
                    "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                    "-crf", "28", "-maxrate", "2500k", "-bufsize", "5000k",
                    "-preset", "slow", "-movflags",
                    "+faststart", "-y", out])
        if enc.returncode != 0 or not os.path.exists(out):
            raise ValueError("could not transcode video")
        with open(out, "rb") as f:
            mp4 = f.read()
        if len(mp4) > MAX_VIDEO_BYTES:
            raise ValueError("transcoded video exceeds 5 MB")
        poster_t = 0.0
        if edit is not None:
            # A start near the source end can yield a near-empty cut -
            # surface that as a trim error, not "not a video".
            try:
                out_dur = probe_duration(mp4)
            except ValueError:
                raise ValueError("trim window is outside the video")
            # clamp: the cut may be shorter than requested (source ended)
            poster_t = min(edit["poster_t"], max(0.0, out_dur - 0.1))
        seek = ["-ss", f"{poster_t:.3f}"] if poster_t > 0 else []
        pf = _run(["-protocol_whitelist", "file", *seek, "-i", out,
                   "-frames:v", "1", "-f", "image2", "-y", frame])
        if pf.returncode != 0 or not os.path.exists(frame):
            raise ValueError("could not extract poster")
        with open(frame, "rb") as f:
            poster_data = f.read()
        poster = image_transcode(poster_data, STORY_IMAGE_MAX, fmt="avif")
    return mp4, poster
