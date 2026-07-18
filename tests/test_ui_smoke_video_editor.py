"""UI_E2E=1-gated live smoke for the video editor (spec 2026-07-18):
pick a 30s clip in the wall composer -> the editor opens -> drag the
right handle to a ~10s window -> pick 1:1 -> drag the cover marker ->
Done -> Post -> the SYNCED artifact (fetched decrypted via
/api/post-blob) is ~10s and square, proving client params executed
node-side. Story leg: same clip through the story "+" -> Done with
defaults -> stored plaintext story blob is <=15s.
"""
import os
import subprocess

import imageio_ffmpeg
import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("UI_E2E") != "1",
    reason="live browser smoke; set UI_E2E=1 (needs playwright chromium)")

from hearth.videogate import probe_duration
from tests.test_ui_smoke_seen_badge import LiveNode


def _clip(tmp_path, seconds=30):
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    p = str(tmp_path / "src.mp4")
    subprocess.run([ff, "-f", "lavfi", "-i",
                    f"testsrc=size=640x480:rate=24:duration={seconds}",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", p],
                   check=True, capture_output=True)
    return p


def _drag(page, el_handle, to_x, to_y):
    box = el_handle.bounding_box()
    page.mouse.move(box["x"] + box["width"] / 2,
                    box["y"] + box["height"] / 2)
    page.mouse.down()
    page.mouse.move(to_x, to_y, steps=10)
    page.mouse.up()


def test_editor_trim_crop_cover_end_to_end(tmp_path):
    from playwright.sync_api import sync_playwright
    from PIL import Image
    import io
    import urllib.request

    a = LiveNode(tmp_path / "a", "Anna", "anna-pc")
    try:
        a.start()
        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            page = browser.new_page(viewport={"width": 1280, "height": 900})
            errors = []
            page.on("pageerror", lambda e: errors.append(str(e)))
            page.goto(f"http://127.0.0.1:{a.http_port}/")
            page.wait_for_selector(".fchip")
            page.click('.navlinks button[data-view="me"]')
            page.wait_for_selector(".profile-composer")

            # -- wall leg -----------------------------------------------
            # videoInput is a hidden (visually-hidden, not display:none)
            # file input inside .profile-composer; accept="video/*"
            # verified against hearth/web/app.js's profilePostComposer().
            page.set_input_files(
                '.profile-composer input[accept="video/*"]',
                _clip(tmp_path))
            page.wait_for_selector("#video-editor .ve-thumb", timeout=15000)

            strip = page.locator(".ve-strip").bounding_box()
            # right handle sits at 15s of 30s (= mid-strip, VE_MAX_WINDOW
            # default window); drag to 10/30
            _drag(page, page.locator(".ve-h-right"),
                  strip["x"] + strip["width"] * (10 / 30),
                  strip["y"] + strip["height"] / 2)
            page.click('.ve-chip:has-text("1:1")')
            _drag(page, page.locator(".ve-cover"),
                  strip["x"] + strip["width"] * (5 / 30),
                  strip["y"] + 4)
            page.click(".ve-done")
            page.wait_for_selector("#video-editor", state="detached")
            page.click(".profile-composer .postbtn")
            # wall re-render is post-fetch, not awaited by the caller (fire
            # and forget in profilePostComposer's onsubmit) - .block-video
            # is the class the wall renderer (app.js's block builder, ~554)
            # actually adds for media==="video" blocks.
            page.wait_for_selector("#profile-wall .block-video", timeout=30000)

            rows = a.node.posts_by(a.node.identity_pub, placement="profile")
            row = [p for p in rows if p["media"] == "video"][0]
            assert row["codec"] == "h264"
            base = f"http://127.0.0.1:{a.http_port}"
            mp4 = urllib.request.urlopen(
                f"{base}/api/post-blob/{row['msg_id']}/{row['blobs'][0]}"
            ).read()
            d = probe_duration(mp4)
            assert 6.0 < d < 13.0        # drag precision, not exactness
            poster = urllib.request.urlopen(
                f"{base}/api/post-blob/{row['msg_id']}/{row['poster']}"
            ).read()
            w, h = Image.open(io.BytesIO(poster)).size
            assert abs(w - h) <= 2       # 1:1 crop landed

            # -- story leg (defaults: 15s window of the 30s source) -----
            # Stories are re-homed into the journal view (app.js
            # renderStories(): #stories lives under #view-journal
            # .journal); nav-journal was already the boot view, so
            # #stories exists and is visible again as soon as we switch
            # back to it.
            page.click("#nav-journal")
            page.wait_for_selector("#stories .story-ring.add")
            page.set_input_files(
                '#stories input[type="file"]', _clip(tmp_path))
            page.wait_for_selector("#video-editor", timeout=15000)
            # Wait for the editor's own metadata load (first thumbnail) so
            # Done's default (start=0, end=min(dur, VE_MAX_WINDOW)) is
            # actually populated - clicking Done before loadedmetadata
            # fires would send a degenerate {start:0, duration:0} edit and
            # the story post would 400 (empty window), never landing a
            # second story tile below. Same wait the wall leg already
            # needs for the identical reason.
            page.wait_for_selector("#video-editor .ve-thumb", timeout=15000)
            page.click(".ve-done")
            page.wait_for_selector("#video-editor", state="detached")
            page.wait_for_function(
                "document.querySelectorAll('#stories .story-tile').length >= 2",
                timeout=30000)
            item = [i for g in a.node.stories_view() for i in g["items"]][0]
            smp4 = a.node.store.get_blob(item["media"])
            assert probe_duration(smp4) <= 15.5

            assert not errors, f"console pageerrors: {errors}"
            browser.close()
    finally:
        a.stop()
