"""UI_E2E=1-gated live browser smoke for the collage Slice C albums
feature: post 3 photos via the composer -> a swipeable .block-deck badge
"1/3" -> arrow-flip to "3/3" -> a tap (not in Arrange) opens the lightbox
at "3 / 3", Escape closes it -> the '+' Add-photos control grows the deck
in place to "/4" and mints a 2-member album -> Arrange -> gear ->
Ungroup restores two standalone blocks and empties the album. Reuses the
LiveNode harness from test_ui_smoke_seen_badge (import, not copy); the
composer/collage smokes (test_ui_smoke_composer.py,
test_ui_smoke_collage.py) are this file's concrete templates for the
scoped-postbtn lesson, the Pillow PNG helper, and the deck pixel-probe
shape.
"""
import os

import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("UI_E2E") != "1",
    reason="live browser smoke; set UI_E2E=1 (needs playwright chromium)")

from hearth.messages import ACCENTS
from tests.test_ui_smoke_seen_badge import LiveNode, befriend


def _pngs(tmp_path, n, start=0):
    from PIL import Image
    paths = []
    for i in range(start, start + n):
        p = tmp_path / f"pic{i}.png"
        Image.new("RGB", (64, 64), (30 * i, 200, 80)).save(p, "PNG")
        paths.append(str(p))
    return paths


def test_album_deck_flip_lightbox_grow_ungroup(tmp_path):
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

            # own profile -> post 3 photos via the composer (scoped
            # .profile-composer .postbtn - the B2 lesson: the journal
            # composer's own hidden .postbtn is the first unscoped DOM
            # match and a plain page.click on it waits forever).
            page.click('.navlinks button[data-view="me"]')
            page.wait_for_selector(".profile-composer")
            page.set_input_files(
                '.profile-composer input[accept="image/*"]',
                _pngs(tmp_path, 3))
            page.wait_for_selector(".preview-deck")
            page.click(".profile-composer .postbtn")
            page.wait_for_selector(
                "#profile-wall-flow .block-deck, #profile-tray .block-deck",
                timeout=8000)

            deck = page.locator(".block-deck").first
            assert deck.locator(".deck-count").inner_text() == "1/3"

            # -- pixel probe, right after the deck first renders, before
            # any interaction (regression guard mirroring the composer
            # smoke's already-shipped probe for the identical stacked-card
            # CSS: the ::before/::after peek-out edges should paint just
            # outside the card, in the padding strip a DOM assert can't
            # see z-index paint order for). --------------------------------
            blk_box = page.locator(".block").first.bounding_box()
            clip = {"x": blk_box["x"] - 8, "y": blk_box["y"] - 8,
                    "width": blk_box["width"] + 16,
                    "height": blk_box["height"] + 16}
            shot = tmp_path / "deck_probe.png"
            page.screenshot(path=str(shot), clip=clip)
            from PIL import Image
            im = Image.open(shot).convert("RGB")
            bg = im.getpixel((1, 1))
            strip = [im.getpixel((im.width - 3, y))
                     for y in range(10, im.height - 10, 4)]
            peeks = any(
                sum(abs(x - y) for x, y in zip(px, bg)) > 30 for px in strip
            )
            # Bug found by this exact probe in Slice C Task 7 (see
            # .superpowers/sdd/task-7-report.md) and fixed in the
            # deck-fix follow-up: unlike the composer's .compose-preview
            # (overflow: visible), the wall's ancestor .block carried a
            # pre-existing overflow:hidden (hearth/web/style.css, added
            # generically for photo/video corner-clipping, predates Slice
            # C) that clipped .block-deck's ::before/::after peek-out
            # edges entirely, so the "several photos stacked" look never
            # actually painted on the live wall. Fix: renderBlock (app.js)
            # tags a deck block with a deterministic "has-deck" class, and
            # .block.has-deck { overflow: visible } (style.css) opts it out
            # of the cell-crop clip. Hard assert, not a diagnostic print -
            # a weakened assert here is exactly what let this regression
            # class through undetected in Tasks 5/6.
            assert peeks, (
                "deck stacked edge does NOT paint outside .block - "
                "ancestor overflow:hidden clips it")

            # arrow-flip to the last photo
            deck.locator(".deck-next").click()
            deck.locator(".deck-next").click()
            assert deck.locator(".deck-count").inner_text() == "3/3"

            # tap the photo (NOT in Arrange) -> lightbox opens at 3 / 3
            deck.locator("img").click()
            page.wait_for_selector("#lightbox")
            assert page.locator("#lightbox-count").inner_text() == "3 / 3"
            page.keyboard.press("Escape")
            page.wait_for_selector("#lightbox", state="detached")

            # '+' Add photos: one more PNG grows the deck in place and
            # mints an album wrapping [original post, new post]
            page.set_input_files(
                ".block-add input", _pngs(tmp_path, 1, start=3))
            page.wait_for_function(
                "document.querySelector('.deck-count') && "
                "document.querySelector('.deck-count').innerText"
                ".endsWith('/4')",
                timeout=8000)
            assert page.locator(".deck-count").inner_text() == "1/4"

            albums = a.node.store.albums(a.node.identity_pub)
            assert len(albums) == 1
            album_id, members = next(iter(albums.items()))
            assert len(members) == 2   # original post + newly added post

            # Arrange -> gear on the deck -> Ungroup
            page.click("#profile-arrange")
            page.wait_for_selector("#profile-tray .block")
            page.click("#profile-tray .block .block-settings-btn")
            page.click("text=Ungroup")
            # the album pseudo-block (keyed on msg_id == album_id) must be
            # gone from the DOM once the re-render lands - more precise
            # than waiting on ".block-deck" alone, which the pre-ungroup
            # 4-photo album deck already satisfies.
            page.wait_for_selector(f'[data-msg-id="{album_id}"]',
                                   state="detached", timeout=8000)

            # two standalone blocks reappear, both unplaced (still in
            # Arrange -> the tray): the 3-photo deck and the 1-photo block
            assert page.locator("#profile-tray .block").count() == 2
            assert page.locator(".block-deck").count() == 1
            assert page.locator(
                ".block-deck .deck-count").inner_text() == "1/3"
            assert page.locator(".block-photo").count() == 1

            albums_after = a.node.store.albums(a.node.identity_pub)
            assert albums_after.get(album_id) == []

            assert not errors, f"console pageerrors: {errors}"
            browser.close()
    finally:
        a.stop()


def _hex_to_rgb(hexcolor):
    h = hexcolor.lstrip("#")
    return f"rgb({int(h[0:2], 16)}, {int(h[2:4], 16)}, {int(h[4:6], 16)})"


def _read_text_style(page, wrap_sel, body_sel):
    return page.evaluate(
        """([wrapSel, bodySel]) => {
            const wrap = document.querySelector(wrapSel);
            const body = document.querySelector(bodySel);
            const w = getComputedStyle(wrap);
            const b = getComputedStyle(body);
            return {justify: w.justifyContent, align: w.alignItems,
                    fontSize: b.fontSize, fontFamily: b.fontFamily,
                    fontWeight: b.fontWeight, color: b.color};
        }""", [wrap_sel, body_sel])


def test_text_block_styling_via_modal_persists_and_syncs(tmp_path):
    """UI_E2E=1-gated live smoke for the text-block-styling feature (spec
    2026-07-14): a plain text wall block, styled Center/Middle/XL/Display/
    Bold/first-swatch through the block-settings modal's Text group -
    computed-style asserts on .block-text-wrap (justify-content/
    align-items) and .block-text-body (font-size/font-family/font-weight/
    color), reload persistence, and a friend's synced view (the color
    option here is a fixed ACCENTS[0] swatch, not "accent" resolution -
    that path is Task 1's node-level coverage, not this live smoke's job).
    """
    from playwright.sync_api import sync_playwright

    a = LiveNode(tmp_path / "a", "Anna", "anna-pc")
    b = LiveNode(tmp_path / "b", "Bo", "bo-pc")
    try:
        befriend(a, b)
        a.start(); b.start()
        # An initial sync BEFORE composing (test_ui_smoke_collage.py's own
        # ordering) exchanges enckey records - _scope_device_pubs only
        # encrypts a "kreds"-scope post for identities whose enc key THIS
        # node already holds at compose time, so composing any earlier
        # would seal the post for Anna's own device only and the friend
        # leg below would sync a message Bo can never decrypt.
        a.sync_with(b)
        a.node.compose_post("Style me please", scope="kreds",
                            placement="profile")

        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            page = browser.new_page(viewport={"width": 1280, "height": 900})
            errors = []
            page.on("pageerror", lambda e: errors.append(str(e)))
            page.goto(f"http://127.0.0.1:{a.http_port}/")
            page.wait_for_selector(".fchip")

            # own profile -> Arrange: the new text block waits in the tray
            page.click('.navlinks button[data-view="me"]')
            page.wait_for_selector("#profile-arrange")
            page.click("#profile-arrange")
            page.wait_for_selector("#profile-tray .block .block-settings-btn")
            page.click("#profile-tray .block .block-settings-btn")
            page.wait_for_selector('[data-sel="text-h-center"]')

            # Center / Middle / XL / Display / Bold / first swatch - each
            # click POSTs the complete selection and reopenAfterAction
            # rebuilds the modal, so the NEXT selector is queried fresh;
            # waiting on the just-picked control's own .active/.on class
            # both settles the reopen and confirms the pick landed.
            for sel, cls in [("text-h-center", "active"),
                              ("text-v-middle", "active"),
                              ("text-size-xl", "active"),
                              ("text-font-disp", "active"),
                              ("text-weight", "active"),
                              ("text-color-0", "on")]:
                page.click(f'[data-sel="{sel}"]')
                page.wait_for_selector(f'[data-sel="{sel}"].{cls}', timeout=8000)

            page.keyboard.press("Escape")
            page.wait_for_selector("#block-settings", state="hidden")

            styles = _read_text_style(
                page, "#profile-tray .block-text-wrap",
                "#profile-tray .block-text-body")
            expected_color = _hex_to_rgb(ACCENTS[0])
            assert styles["justify"] == "center"
            assert styles["align"] == "center"
            assert styles["fontSize"] == "26px"
            assert "Bricolage" in styles["fontFamily"]
            assert int(float(styles["fontWeight"])) >= 600
            assert styles["color"] == expected_color

            # reload -> persists. openMe() persists "hearth_view"="me" in
            # localStorage (restoreView() reads it on boot), so the reload
            # lands back on the profile view automatically - no re-click,
            # and NOT ".fchip" (that lives in #view-journal, which stays
            # hidden the whole time; waiting on it here is a real flake,
            # not a feature bug - caught by an isolated repro during this
            # task, see task-2-report.md). ARRANGING itself resets on
            # reload, so the styled block is now in the ordinary flow.
            page.reload()
            page.wait_for_selector("#profile-wall-flow .block-text-wrap")
            styles_after_reload = _read_text_style(
                page, "#profile-wall-flow .block-text-wrap",
                "#profile-wall-flow .block-text-body")
            assert styles_after_reload == styles

            # friend leg: Bo syncs and sees the same styled render on
            # Anna's profile (Friends panel is the real openProfile path
            # for a friend with no journal entry - see module docstring
            # precedent in test_ui_smoke_collage.py).
            a.sync_with(b)
            page2 = browser.new_page(viewport={"width": 1280, "height": 900})
            page2.on("pageerror", lambda e: errors.append(str(e)))
            page2.goto(f"http://127.0.0.1:{b.http_port}/")
            page2.wait_for_selector(".fchip")
            page2.click("#nav-me")
            page2.wait_for_selector("#friends .friend")
            page2.click(".friend:has-text('Anna')")
            page2.wait_for_selector("#profile-wall-flow .block-text-wrap")
            friend_styles = _read_text_style(
                page2, "#profile-wall-flow .block-text-wrap",
                "#profile-wall-flow .block-text-body")
            assert friend_styles == styles

            assert not errors, f"console pageerrors: {errors}"
            browser.close()
    finally:
        try:
            a.stop()
        finally:
            b.stop()
