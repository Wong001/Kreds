"""UI_E2E=1-gated live browser smoke for the collage pin engine (Slice A):
two real nodes, real sync, headless Chromium. Reuses the LiveNode harness
from test_ui_smoke_seen_badge (import, not copy).

Bo-side profile-open note: the brief's draft used a chip click + "text=Anna"
click to open Anna's profile from Bo's browser. That doesn't exist as a real
affordance here - .fchip only sets the journal filter (renderChipbar's
onclick), it never calls openProfile; and Anna's only post in this test is
placement="profile" (posts_by(..., "profile")), so it never lands in Bo's
FEED/journal for a by-name entry click either. The real openProfile path for
a known friend with no journal entry to click is the self profile's Friends
panel (#friends .friend, populated by renderMeStrip() from STATE.friends -
see app.js's openMe()/renderMeStrip()): click #nav-me to open Bo's own
profile (selfonly panels reveal), then click the .friend row for Anna.
"""
import os

import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("UI_E2E") != "1",
    reason="live browser smoke; set UI_E2E=1 (needs playwright chromium)")

from tests.test_ui_smoke_seen_badge import LiveNode, befriend


def test_pin_drag_resize_and_synced_view(tmp_path):
    from playwright.sync_api import sync_playwright

    a = LiveNode(tmp_path / "a", "Anna", "anna-pc")
    b = LiveNode(tmp_path / "b", "Bo", "bo-pc")
    try:
        befriend(a, b)
        a.start(); b.start()
        a.sync_with(b)
        mid = a.node.compose_post("min blok", scope="kreds",
                                  placement="profile")

        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            page = browser.new_page(viewport={"width": 1280, "height": 900})
            errors = []
            page.on("pageerror", lambda e: errors.append(str(e)))
            page.goto(f"http://127.0.0.1:{a.http_port}/")
            page.wait_for_selector(".fchip")

            # own profile -> Arrange: the new block waits in the tray
            page.click('.navlinks button[data-view="me"]')
            page.wait_for_selector("#profile-arrange")
            page.click("#profile-arrange")
            page.wait_for_selector("#profile-tray .block")

            # keyboard path: place on canvas via the modal
            page.click("#profile-tray .block .block-settings-btn")
            page.click("text=Place on canvas")
            page.wait_for_selector("#profile-wall .block")

            # modal preset resize: 2x2. openBlockSettings's reopenAfterAction
            # (app.js) keeps the modal open on the block after EVERY action
            # (focus-return, #5a-e) - "Place on canvas" already left it open
            # on the now-pinned block, so no extra gear click here.
            page.click('[data-sel="size-2x2"]')
            page.wait_for_timeout(400)
            page.keyboard.press("Escape")

            # nudge Right moves x by one cell (verify via the API)
            page.click("#profile-wall .block .block-settings-btn")
            page.click('[data-sel="nudge-Right"]')
            page.wait_for_timeout(400)
            page.keyboard.press("Escape")
            lay = a.node.store.profile_layout(a.node.identity_pub)
            assert lay["pins"][mid]["x"] == 1
            assert (lay["pins"][mid]["w"], lay["pins"][mid]["h"]) == (2, 2)

            # pointer drag: pick the block up and drop it at column 0, a
            # couple of rows down; verify the pin moved. This step caught
            # the tap-handoff lostpointercapture bug: the block's own
            # capture release bubbled into the wall's dropped-terminator
            # safety net and ended every drag before its first move,
            # unpinning instead of moving (fixed in 948ab3c by scoping
            # onLost to the wall's own capture + pointerId).
            blk = page.locator("#profile-wall .block").first
            box = blk.bounding_box()
            wall = page.locator("#profile-wall").bounding_box()
            page.mouse.move(box["x"] + box["width"] / 2,
                            box["y"] + box["height"] / 2)
            page.mouse.down()
            page.mouse.move(wall["x"] + 40, wall["y"] + wall["height"] - 20,
                            steps=12)
            page.mouse.up()
            page.wait_for_timeout(600)
            lay = a.node.store.profile_layout(a.node.identity_pub)
            assert lay["pins"][mid]["x"] == 0
            assert lay["pins"][mid]["y"] > 0

            # persistence + honest geometry on the friend's synced view
            a.sync_with(b)
            page2 = browser.new_page(viewport={"width": 1280, "height": 900})
            page2.goto(f"http://127.0.0.1:{b.http_port}/")
            page2.wait_for_selector(".fchip")
            # Open Anna's profile via Bo's own Friends panel (see module
            # docstring: the chip/journal-entry path the brief drafted isn't
            # a real openProfile call site for a profile-only post).
            page2.click("#nav-me")
            page2.wait_for_selector("#friends .friend")
            page2.click(".friend:has-text('Anna')")
            page2.wait_for_selector("#profile-wall .block")
            col = page2.locator("#profile-wall .block").first \
                .evaluate("b => b.style.gridColumn")
            assert col.startswith("1 / span 2")   # x=0, w=2 on Bo's side
            assert page2.locator("#profile-arrange").is_hidden()

            assert not errors, f"console pageerrors: {errors}"
            browser.close()
    finally:
        try:
            a.stop()
        finally:
            b.stop()
