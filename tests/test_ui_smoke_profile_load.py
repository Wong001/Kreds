"""UI_E2E=1-gated live smoke for the profile-load slice (spec 2026-07-18):
Anna posts wall photos -> Bo syncs -> Bo's view of Anna's wall renders
tile <img>s pointing at the THUMB hashes (small AVIF), and opening the
lightbox loads the FULL hash. Also: a post whose blobs haven't arrived
renders .img-pending, not a broken glyph (forced by deleting the blob
bytes from Bo's store before rendering).
"""
import os

import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("UI_E2E") != "1",
    reason="live browser smoke; set UI_E2E=1 (needs playwright chromium)")

from tests.test_ui_smoke_seen_badge import LiveNode, befriend


def _pngs(tmp_path, n):
    from PIL import Image
    paths = []
    for i in range(n):
        p = tmp_path / f"p{i}.png"
        img = Image.new("RGB", (1600, 1200))
        px = img.load()
        for x in range(0, 1600, 4):
            for y in range(0, 1200, 4):
                px[x, y] = (x % 256, (x + i * 40) % 256, y % 256)
        img.save(p, "PNG")
        paths.append(str(p))
    return paths


def test_friend_wall_thumb_first_and_pending(tmp_path):
    from playwright.sync_api import sync_playwright

    a = LiveNode(tmp_path / "a", "Anna", "anna-pc")
    b = LiveNode(tmp_path / "b", "Bo", "bo-pc")
    try:
        befriend(a, b)
        a.start(); b.start()
        a.sync_with(b)                        # enckeys first (collage lesson)
        mid = a.node.compose_post("wall pics", "kreds",
                                  photos=[open(p, "rb").read()
                                          for p in _pngs(tmp_path, 2)],
                                  placement="profile")
        a.sync_with(b)                        # records + blobs across

        row = [p for p in b.node.posts_by(a.node.identity_pub,
                                          placement="profile")
               if p["msg_id"] == mid][0]
        assert row["thumbs"] and all(row["thumbs"])

        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            page = browser.new_page(viewport={"width": 1280, "height": 900})
            errors = []
            page.on("pageerror", lambda e: errors.append(str(e)))
            page.goto(f"http://127.0.0.1:{b.http_port}/")
            page.wait_for_selector(".fchip")
            page.click("#nav-me")
            page.click("#profile-cog")
            page.wait_for_selector("#friends .friend")
            page.click(".friend:has-text('Anna')")
            page.wait_for_selector("#profile-wall .block-deck img")

            src = page.locator("#profile-wall .block-deck img").first \
                .get_attribute("src")
            assert row["thumbs"][0] in src        # tile renders the THUMB
            # lightbox opens the FULL hash
            page.click("#profile-wall .block-deck img")
            page.wait_for_selector("#lightbox-img")
            lsrc = page.locator("#lightbox-img").get_attribute("src")
            assert row["blobs"][0] in lsrc and row["thumbs"][0] not in lsrc
            page.keyboard.press("Escape")

            # pending placeholder: nuke the blob bytes on Bo's store and
            # re-render - the tile must show .img-pending, never a glyph
            for h in row["thumbs"] + row["blobs"]:
                b.node.store._db.execute("DELETE FROM blobs WHERE hash=?",
                                         (h,))
            b.node.store._db.commit()
            page.reload()
            # NOT ".fchip" here: the earlier #nav-me click persisted
            # localStorage hearth_view="me" (openMe(), app.js), so
            # restoreView() lands this reload straight on Bo's OWN
            # profile (view "profile", #view-journal - and its chipbar -
            # stays hidden) rather than the journal. #nav-me is
            # persistent chrome outside the view-* divs, so it's a
            # reliable "app booted" signal across any restored view
            # (same lesson documented in test_ui_smoke_collage.py /
            # the text-block-styling smoke's own reload comment).
            page.wait_for_selector("#nav-me")
            page.click("#nav-me")
            page.click("#profile-cog")
            page.wait_for_selector("#friends .friend")
            page.click(".friend:has-text('Anna')")
            page.wait_for_selector("#profile-wall .img-pending",
                                   timeout=8000)

            assert not errors, f"console pageerrors: {errors}"
            browser.close()
    finally:
        try:
            a.stop()
        finally:
            b.stop()
