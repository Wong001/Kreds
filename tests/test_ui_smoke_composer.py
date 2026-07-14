"""UI_E2E=1-gated live smoke for the composer preview (collage Slice B):
attach photos -> deck preview + count badge + chips; chip changes the
size that rides straight onto /api/post; text-only posts send no chips;
the dropdown is gone. Rewritten for dynamic placement (spec 2026-07-14):
a post lands PINNED at the top the instant it's composed - there is no
span-seed roundtrip and no unplaced limbo to assert against, so this
smoke reads the pin directly. Reuses the LiveNode harness (import, not
copy)."""
import os

import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("UI_E2E") != "1",
    reason="live browser smoke; set UI_E2E=1 (needs playwright chromium)")

from tests.test_ui_smoke_seen_badge import LiveNode


def _pngs(tmp_path, n):
    from PIL import Image
    paths = []
    for i in range(n):
        p = tmp_path / f"pic{i}.png"
        Image.new("RGB", (64, 64), (200, 30 * i, 60)).save(p, "PNG")
        paths.append(str(p))
    return paths


def test_composer_preview_and_span_seed(tmp_path):
    from playwright.sync_api import sync_playwright

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

            # no dropdown anywhere, ever
            assert page.locator(".profile-composer select").count() == 0

            # 3 photos -> deck preview with count badge, chips visible,
            # 2x2 active by default
            page.set_input_files(
                '.profile-composer input[accept="image/*"]',
                _pngs(tmp_path, 3))
            page.wait_for_selector(".preview-deck")
            assert page.locator(".deck-count").inner_text() == "3"
            assert page.locator(
                '.size-chip.active[data-span="2x2"]').count() == 1

            # regression guard: the deck's ::before/::after edges must
            # actually paint (z-index stacking-context fix) - a pixel probe
            # in the padding strip just outside the sized card, since a DOM
            # assert can't see z-index paint order.
            box = page.locator(".compose-preview").bounding_box()
            clip = {"x": box["x"] - 8, "y": box["y"] - 8,
                    "width": box["width"] + 16, "height": box["height"] + 16}
            shot = tmp_path / "deck.png"
            page.screenshot(path=str(shot), clip=clip)
            from PIL import Image
            im = Image.open(shot).convert("RGB")
            bg = im.getpixel((1, 1))            # composer background corner
            strip = [im.getpixel((im.width - 3, y))
                     for y in range(10, im.height - 10, 4)]
            assert any(
                sum(abs(a - b) for a, b in zip(px, bg)) > 30 for px in strip
            ), "deck edge does not paint outside the card"

            # pick 1x1, preview shrinks to ~one cell
            page.click('.size-chip[data-span="1x1"]')
            cell = page.evaluate(
                "parseFloat(getComputedStyle(document.documentElement)"
                ".getPropertyValue('--cell'))")
            w = page.locator(".compose-preview").bounding_box()["width"]
            assert abs(w - cell) < 8, f"preview {w} vs cell {cell}"

            # post -> the chosen 1x1 rides straight onto /api/post as w/h
            # (spec 2026-07-14: the separate span-seed call is gone) and
            # lands PINNED at the top dense, push-place applied - it's the
            # only block on the wall, so it lands exactly at (0,0).
            # (scoped: the journal composer's hidden .postbtn is the first
            # DOM match, and page.click waits on the first match forever)
            page.click(".profile-composer .postbtn")
            page.wait_for_selector("#profile-wall .block", timeout=8000)
            msg_id = page.locator("#profile-wall .block").first \
                .get_attribute("data-msg-id")
            lay = a.node.store.profile_layout(a.node.identity_pub)
            assert lay["pins"][msg_id] == {"x": 0, "y": 0, "w": 1, "h": 1}
            assert msg_id not in lay["spans"]   # geometry lives in the pin now

            # text-only post: no chips shown, none sent - the server's 4x1
            # text default applies, pinned at the top, pushing the photo
            # block straight down (never sideways, never orphaned).
            assert page.locator(".size-chips").is_hidden()
            page.fill(".profile-composer input[type=text]", "bare tekst")
            page.click(".profile-composer .postbtn")
            page.wait_for_function(
                "document.querySelectorAll('#profile-wall .block').length === 2",
                timeout=8000)
            text_id = page.locator("#profile-wall .block").first \
                .get_attribute("data-msg-id")   # newest post renders first
            lay = a.node.store.profile_layout(a.node.identity_pub)
            assert lay["pins"][text_id] == {"x": 0, "y": 0, "w": 4, "h": 1}
            assert lay["pins"][msg_id]["x"] == 0
            assert lay["pins"][msg_id]["y"] > 0
            assert len(lay["pins"]) == 2

            assert not errors, f"console pageerrors: {errors}"
            browser.close()
    finally:
        a.stop()
