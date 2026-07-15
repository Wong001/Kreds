"""UI_E2E=1-gated live browser smoke for the collage pin engine (Slice A),
rewritten for dynamic placement (spec 2026-07-14): every wall post lands
PINNED at the top the instant it's composed - there is no tray, no
"Place on canvas" - so this smoke drives preset/nudge/drag legs directly
on the canvas, then adds a push leg (drag one pinned block onto another's
exact cell and confirm the occupant cascades straight down, never
sideways). Two real nodes, real sync, headless Chromium. Reuses the
LiveNode harness from test_ui_smoke_seen_badge (import, not copy).

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
        # Creation auto-places at the top, dense (spec 2026-07-14): a
        # text-only post with no w/h fields lands pinned at (0,0) with the
        # 4x1 text default - no tray, no unplaced limbo, nothing to place.
        lay = a.node.store.profile_layout(a.node.identity_pub)
        assert lay["pins"][mid] == {"x": 0, "y": 0, "w": 4, "h": 1}

        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            page = browser.new_page(viewport={"width": 1280, "height": 900})
            errors = []
            page.on("pageerror", lambda e: errors.append(str(e)))
            page.goto(f"http://127.0.0.1:{a.http_port}/")
            page.wait_for_selector(".fchip")

            # own profile -> the block is already pinned on the canvas,
            # no Arrange/modal needed to see it land there.
            page.click('.navlinks button[data-view="me"]')
            page.wait_for_selector("#profile-wall .block")

            # Final-review Fix 1 regression guard: navigating away from the
            # profile and back must not leave --cell squashed to the 40px
            # floor. openProfile used to call renderProfilePage (which
            # measures the wall) BEFORE setView("profile") unhid the view -
            # a display:none wall always reads clientWidth 0, clamping
            # --cell every time on the most common profile entry
            # (Journal/Messages -> profile).
            page.click("#nav-journal")
            page.wait_for_selector("#journal")
            page.click('.navlinks button[data-view="me"]')
            page.wait_for_selector("#profile-wall .block")
            cell = page.evaluate(
                "parseFloat(getComputedStyle(document.documentElement)"
                ".getPropertyValue('--cell'))")
            assert cell > 100, f"--cell squashed to {cell}px after journal->profile nav"

            # Enter Arrange for the modal-driven keyboard legs below.
            page.click("#profile-arrange")
            page.wait_for_selector("#profile-wall .block .block-settings-btn")

            # modal preset resize: 2x2.
            page.click("#profile-wall .block .block-settings-btn")
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

            # -- push leg (spec 2026-07-14 dynamic placement): pin A
            # exactly at (0,0), land a second block B elsewhere, then
            # DRAG B onto (0,0) - the server must push A straight down,
            # never sideways, and the ghost must never veto the drop (the
            # client's old overlap check is gone; the server settles it).
            # Node-side setup only pins A/B via the API (that call path is
            # covered elsewhere - test_push_placement.py); what's under
            # test here is the live drag COMMIT triggering the server push
            # end to end through the real UI gesture.
            a.node.set_block_pin(mid, 0, 0, 1, 1)
            bid = a.node.compose_post("push me", scope="kreds",
                                      placement="profile",
                                      span_w=1, span_h=1)
            # compose_post's own creation auto-place is first-fit (spec
            # 2026-07-15): with A pinned at (0,0) it would land B in the
            # next open cell WITHOUT touching A - move B to a free cell
            # explicitly anyway so the drag itself is what triggers the
            # push under test, not creation. Row 1 (not further down): the
            # drag needs both B's start and the wall's (0,0) drop target
            # visible in the SAME 900px viewport without scrolling - row 6
            # measured off-screen (y ~1523px) and made the whole gesture a
            # silent no-op, confirmed via an isolated bounding-box repro
            # before landing on row 1. Re-pin A last: it must land exactly
            # at (0,0) with nothing else in the way.
            a.node.set_block_pin(bid, 2, 1, 1, 1)
            a.node.set_block_pin(mid, 0, 0, 1, 1)

            page.reload()
            page.wait_for_selector("#profile-wall .block")
            page.click("#profile-arrange")
            page.wait_for_selector(f'[data-msg-id="{bid}"] .block-settings-btn')

            wall = page.locator("#profile-wall").bounding_box()
            blk_b = page.locator(f'[data-msg-id="{bid}"]')
            box = blk_b.bounding_box()
            page.mouse.move(box["x"] + box["width"] / 2,
                            box["y"] + box["height"] / 2)
            page.mouse.down()
            page.mouse.move(wall["x"] + 20, wall["y"] + 20, steps=10)
            page.mouse.up()
            page.wait_for_timeout(600)

            lay = a.node.store.profile_layout(a.node.identity_pub)
            assert lay["pins"][bid] == {"x": 0, "y": 0, "w": 1, "h": 1}
            assert lay["pins"][mid]["x"] == 0     # never sideways
            assert lay["pins"][mid]["y"] > 0      # pushed straight down

            assert not errors, f"console pageerrors: {errors}"
            browser.close()
    finally:
        try:
            a.stop()
        finally:
            b.stop()
