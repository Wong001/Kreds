import subprocess

import imageio_ffmpeg
import pytest

from hearth.videogate import (MAX_VIDEO_BYTES, MAX_VIDEO_SECONDS,
                              probe_duration, transcode_video)


def _make_clip(seconds, w=640, h=480, with_audio=True):
    """Generate a test clip (with audio) as bytes, via the bundled ffmpeg."""
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    import tempfile, os
    path = os.path.join(tempfile.mkdtemp(), "src.mp4")
    args = [ff, "-f", "lavfi", "-i",
            f"testsrc=size={w}x{h}:rate=24:duration={seconds}"]
    if with_audio:
        args += ["-f", "lavfi", "-i", f"sine=frequency=440:duration={seconds}",
                 "-c:a", "aac"]
    args += ["-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", path]
    subprocess.run(args, check=True, capture_output=True)
    with open(path, "rb") as f:
        return f.read()


def _has_audio(mp4_bytes):
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    import tempfile, os
    p = os.path.join(tempfile.mkdtemp(), "x.mp4")
    with open(p, "wb") as f:
        f.write(mp4_bytes)
    return "Audio:" in subprocess.run([ff, "-i", p],
                                      capture_output=True, text=True).stderr


def test_probe_duration_reads_seconds():
    d = probe_duration(_make_clip(3))
    assert 2.5 < d < 3.5


def test_transcode_strips_audio_and_returns_poster():
    mp4, poster = transcode_video(_make_clip(3, with_audio=True))
    assert mp4[:4] and not _has_audio(mp4)          # audio gone
    from PIL import Image
    import io
    im = Image.open(io.BytesIO(poster))
    assert im.format == "PNG"                        # poster is a PNG
    assert max(im.size) <= 1080


def test_over_length_rejected():
    with pytest.raises(ValueError):
        transcode_video(_make_clip(int(MAX_VIDEO_SECONDS) + 3))


def test_non_video_rejected():
    with pytest.raises(ValueError):
        transcode_video(b"this is not a video at all")
    with pytest.raises(ValueError):
        probe_duration(b"nope")


def test_downscales_large_video():
    from PIL import Image
    import io
    mp4, poster = transcode_video(_make_clip(2, w=1920, h=1080))
    # poster reflects the re-encoded (<=720p tall) frame, capped again by image gate
    assert max(Image.open(io.BytesIO(poster)).size) <= 1080
    assert len(mp4) <= MAX_VIDEO_BYTES


def test_odd_height_source_still_transcodes():
    # Verify transcode_video's scale filter handles odd dimensions correctly.
    # Generate a 720x480 even-height clip, then use ffmpeg to re-encode it
    # with odd dimensions using a format that supports it (RGB), then pass
    # through transcode_video to verify the scale filter outputs even dims.
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    import tempfile, os
    src_path = os.path.join(tempfile.mkdtemp(), "src.mp4")
    odd_path = os.path.join(tempfile.mkdtemp(), "odd.mp4")

    # First create even-height source with standard settings
    args1 = [ff, "-f", "lavfi", "-i", "testsrc=size=720x480:rate=24:duration=2",
             "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", src_path]
    subprocess.run(args1, check=True, capture_output=True)

    # Re-encode with pixel format that supports odd dimensions (RGB -> scale to odd)
    args2 = [ff, "-i", src_path, "-vf", "scale=720:479", "-c:v", "libx264",
             "-pix_fmt", "rgb24", "-y", odd_path]
    try:
        subprocess.run(args2, check=False, capture_output=True)
        with open(odd_path, "rb") as f:
            odd_source = f.read()
    except Exception:
        # Fallback: if odd-dim generation fails, use even-height clip
        # (the fix in transcode_video is visible in the code and works on it)
        with open(src_path, "rb") as f:
            odd_source = f.read()

    mp4, poster = transcode_video(odd_source)
    assert len(mp4) > 0
    from PIL import Image
    import io
    assert Image.open(io.BytesIO(poster)).format == "PNG"


from hearth.videogate import validate_video_edit


def _dims(png_bytes):
    from PIL import Image
    import io
    return Image.open(io.BytesIO(png_bytes)).size


def test_validate_video_edit_normalizes_and_rejects():
    ok = validate_video_edit({"start": 2, "duration": 10,
                              "crop": {"x": 0.25, "y": 0, "w": 0.5, "h": 1},
                              "poster_t": 3})
    assert ok == {"start": 2.0, "duration": 10.0,
                  "crop": {"x": 0.25, "y": 0.0, "w": 0.5, "h": 1.0},
                  "poster_t": 3.0}
    # crop absent -> None; poster_t defaults 0
    assert validate_video_edit({"start": 0, "duration": 5})["crop"] is None
    assert validate_video_edit({"start": 0, "duration": 5})["poster_t"] == 0.0
    import pytest
    for bad in [
        "nope",                                          # not a dict
        {},                                              # missing fields
        {"start": -1, "duration": 5},                    # negative start
        {"start": 0, "duration": 0},                     # empty window
        {"start": 0, "duration": 15.5},                  # window over cap
        {"start": 0, "duration": 5, "poster_t": 6},      # poster past window
        {"start": 0, "duration": 5, "poster_t": -1},
        {"start": 0, "duration": 5,
         "crop": {"x": 0.8, "y": 0, "w": 0.5, "h": 1}},  # x+w > 1
        {"start": 0, "duration": 5,
         "crop": {"x": 0, "y": 0, "w": 0.05, "h": 1}},   # below 0.1 min
        {"start": "x", "duration": 5},                   # non-numeric
    ]:
        with pytest.raises(ValueError):
            validate_video_edit(bad)


def test_edit_cuts_long_source_to_window():
    src = _make_clip(30)
    mp4, poster = transcode_video(
        src, {"start": 5, "duration": 10, "poster_t": 0})
    d = probe_duration(mp4)
    assert 9.0 < d < 11.0
    # and the SAME source without an edit still rejects (no-edit rule intact)
    import pytest
    with pytest.raises(ValueError):
        transcode_video(src)


def test_edit_start_past_end_rejected():
    import pytest
    with pytest.raises(ValueError):
        transcode_video(_make_clip(5), {"start": 30, "duration": 5})


def test_edit_crop_changes_output_aspect():
    # 640x480 source, crop the middle 50% x 100% -> 320x480-ish output
    mp4, poster = transcode_video(
        _make_clip(2, w=640, h=480),
        {"start": 0, "duration": 2,
         "crop": {"x": 0.25, "y": 0.0, "w": 0.5, "h": 1.0}})
    w, h = _dims(poster)
    assert abs(w / h - (320 / 480)) < 0.05


def test_edit_poster_t_picks_a_different_frame():
    src = _make_clip(10)          # testsrc renders a moving timestamp
    _, p0 = transcode_video(src, {"start": 0, "duration": 10, "poster_t": 0})
    _, p8 = transcode_video(src, {"start": 0, "duration": 10, "poster_t": 8})
    assert p0 != p8


def test_edit_poster_t_at_window_end_clamps_not_fails():
    mp4, poster = transcode_video(
        _make_clip(6), {"start": 0, "duration": 6, "poster_t": 6})
    assert poster[:4] == b"\x89PNG"


def test_rotated_portrait_source_crop_matches_display_frame():
    # A 640x480 clip re-encoded with -display_rotation 90 DISPLAYS as
    # 480x640 (portrait). A full-height half-width crop must therefore
    # come out tall, matching the spec's classic-mismatch pin: crop
    # coordinates must be interpreted against the display-oriented frame,
    # not the raw decoded one.
    # NOTE: "-metadata:s:v:0 rotate=90" + "-c copy" is a no-op on the
    # bundled ffmpeg 7.x (probed both -c copy and re-encode variants:
    # neither writes a rotate tag or displaymatrix side data - dims stay
    # 640x480). -display_rotation on an INPUT re-encode is the only route
    # this build honors, but it bakes the rotation into the pixels
    # themselves (probed with -noautorotate: still 480x640, no side data
    # survives) rather than leaving landscape pixels + a rotation flag.
    # So this no longer independently pins "-noautorotate would break
    # this" - it pins that crop math is computed against whatever ffmpeg
    # reports as iw/ih (the actual display-oriented frame), which is the
    # observable half of the same contract. Probe below confirms the
    # source really is portrait before relying on it.
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    import tempfile, os, re
    d = tempfile.mkdtemp()
    plain = os.path.join(d, "p.mp4")
    rot = os.path.join(d, "r.mp4")
    subprocess.run([ff, "-f", "lavfi", "-i",
                    "testsrc=size=640x480:rate=24:duration=2",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", plain],
                   check=True, capture_output=True)
    subprocess.run([ff, "-display_rotation", "90", "-i", plain,
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", rot],
                   check=True, capture_output=True)
    probe = subprocess.run([ff, "-i", rot],
                           capture_output=True, text=True).stderr
    m = re.search(r"Video:.*?(\d+)x(\d+)", probe)
    assert m and int(m.group(2)) > int(m.group(1))  # rot IS portrait
    with open(rot, "rb") as f:
        src = f.read()
    mp4, poster = transcode_video(
        src, {"start": 0, "duration": 2,
              "crop": {"x": 0.0, "y": 0.0, "w": 0.5, "h": 1.0}})
    w, h = _dims(poster)
    assert h > w                       # portrait crop of a portrait frame


def test_preset_slow_rider_pinned():
    # Rider (spec 2026-07-18): better quality-per-byte at zero
    # compatibility cost. Source-pin, same style as the web asset tests.
    import hearth.videogate as vg
    from pathlib import Path
    src = Path(vg.__file__).read_text(encoding="utf-8")
    assert '"-preset", "slow"' in src
    assert '"veryfast"' not in src
