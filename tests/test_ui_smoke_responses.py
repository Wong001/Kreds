"""UI_E2E=1-gated live browser smoke for the reactions/comments feature
(spec 2026-07-18): B reacts and comments on A's journal post through the
REAL UI (.rx click, comment composer submit) -> A's journal shows the
updated count and the comment with B's real name; B's own view of that
same comment shows "mine" styling (retract affordance, not moderation);
a THIRD node C (a mutual friend of both A and B, but neither author nor
responder) sees B's real name too - via the mutual_box trial-open +
sig-verify pipeline, not the author's raw-response shortcut (see
"Reviewer fix" below). Story leg: A posts a story, B opens the viewer
and taps a reaction glyph -> A's DM thread (opened through the real UI)
shows the glyph message with a .story-chip.

Harness: LiveNode/befriend imported from test_ui_smoke_seen_badge.py (not
copied) - THREE browser pages, one per node's own http_port, mirroring
test_ui_smoke_albums.py's test_text_block_styling_via_modal_persists_and_
syncs two-page pattern (extended to a trio here): /api/react,
/api/comment, /api/dm each act on whichever node's own HTTP server
receives the request, so B's engagement actions must be driven through
B's OWN page, not A's.

Reviewer fix (2026-07-18): the first version of this file only checked
the real-name assertion on Anna's (the AUTHOR's) page. Per node.py's
_post_responses_view docstring, the author resolves EVERY comment's
identity directly from a raw KIND_RESPONSE row it holds - a shortcut
that never touches the mutual_box/sig-verify/_device_bound pipeline a
mutual FRIEND (not the author, not the responder) actually needs, and
KIND_RESPONSE rows never route to a third party at all (routing sends
them only to the post's author + the responder's own devices - see
test_responses.py's test_response_never_leaks_to_a_third_friend_via_
routing) - so Cleo's store can never hold Bo's raw response no matter
who she's friends with, and MUST resolve his name via the mutual box.
Cleo is befriended with BOTH Anna (to see the post/record at all - kreds
entitlement) and Bo (real friend-sync completed BEFORE Bo's comment is
composed, since the mutual box's audience is a snapshot of the
responder's friends AT COMPOSE TIME - test_responses.py's own
test_stranger_sees_alias_mutual_sees_name pins the same "sealed to
friends at that time" rule for the alias-stays-aliased case; this is
its live, resolved-name counterpart).

Found and fixed during this task: buildEntry used to gate the ENTIRE
reaction bar/comment toggle behind `if (p.responses)`, which is null
until a KIND_RESPONSES record exists - itself requiring a PRIOR response
- so nobody could ever be first to react/comment (live-reproduced, see
.superpowers/sdd/task-8-report.md). Fixed on this branch (bf6d6db):
renderResponses() normalizes a null p.responses to EMPTY_RESPONSES_SHAPE,
so a fresh post's bar/toggle render (six zero-count buttons, "Comment").

process_responses() is NOT invoked by LiveNode.sync_with (that calls
SyncService.sync_with directly, not the periodic _gossip_round that
wraps it with the author sweep) - so after B's UI action lands a raw
KIND_RESPONSE in B's own store, this file bridges with the same
_sync(b, a); a.process_responses(); _sync(a, b) idiom tests/test_
responses.py already uses at the node level (translated to LiveNode's
real bidirectional sync_with), before either page's next WS-driven
refresh() can show the resolved record. This bridging is deliberately
NOT a UI action - it stands in for the ~3s gossip loop's own
process_responses() call, exactly like every other smoke's direct
node.compose_post()/sync_with() calls stand in for background gossip.
"""
import io
import os

import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("UI_E2E") != "1",
    reason="live browser smoke; set UI_E2E=1 (needs playwright chromium)")

from tests.test_ui_smoke_seen_badge import LiveNode, befriend

FIRE = "\U0001F525"   # REACTION_GLYPHS.fire ("\U0001F525" == the same
                      # emoji app.js maps the "fire" token to) - written
                      # as an escape, not a literal, to avoid any source-
                      # encoding surprises in this file.


def _tiny_png_bytes():
    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", (48, 48), (200, 90, 40)).save(buf, "PNG")
    return buf.getvalue()


def test_reactions_comments_and_story_reply_live(tmp_path):
    from playwright.sync_api import sync_playwright

    a = LiveNode(tmp_path / "a", "Anna", "anna-pc")
    b = LiveNode(tmp_path / "b", "Bo", "bo-pc")
    c = LiveNode(tmp_path / "c", "Cleo", "cleo-pc")
    try:
        befriend(a, b)
        befriend(a, c)
        befriend(b, c)
        a.start(); b.start(); c.start()
        # Initial sync exchanges enckeys/profiles before anything is
        # composed (test_ui_smoke_albums.py's own ordering note: composing
        # any earlier would seal a "kreds"-scope post for the author's own
        # device only, and Bo's name wouldn't have synced to Anna yet for
        # the author-always-real-name assertion below). Bo<->Cleo's real
        # sync must ALSO land before Bo's comment is composed below - the
        # mutual box's audience is a snapshot of the responder's friends
        # at compose time (module docstring), so friending Cleo to Bo
        # only afterward would leave THIS entry aliased for her forever,
        # same as test_responses.py's own alias-stays-aliased pin.
        a.sync_with(b)
        a.sync_with(c)
        b.sync_with(c)
        pid = a.node.compose_post("look at this", scope="kreds")
        b.sync_with(a)   # Bo's store holds Anna's post
        a.sync_with(c)   # Cleo's store holds Anna's post too

        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            page_a = browser.new_page(viewport={"width": 1280, "height": 900})
            page_b = browser.new_page(viewport={"width": 1280, "height": 900})
            page_c = browser.new_page(viewport={"width": 1280, "height": 900})
            errors = []
            page_a.on("pageerror", lambda e: errors.append(str(e)))
            page_b.on("pageerror", lambda e: errors.append(str(e)))
            page_c.on("pageerror", lambda e: errors.append(str(e)))

            page_a.goto(f"http://127.0.0.1:{a.http_port}/")
            page_a.wait_for_selector(".fchip")
            page_b.goto(f"http://127.0.0.1:{b.http_port}/")
            page_b.wait_for_selector(".fchip")
            page_c.goto(f"http://127.0.0.1:{c.http_port}/")
            page_c.wait_for_selector(".fchip")

            # -- fresh post already carries the T8-fixed empty-state bar --
            # Collapsed-picker edition (August demo feedback, 2026-07-18):
            # resting state is the .rx-open icon (no chips on a fresh
            # post); the six .rx live in a hidden .rx-picker until the
            # icon is clicked.
            page_b.wait_for_selector(".entry .rx-open")
            assert page_b.locator(".entry .rx-count-chip").count() == 0
            assert page_b.locator(
                ".entry .rx-picker:not(.hidden)").count() == 0
            assert page_b.locator(".entry .comments-toggle") \
                .inner_text() == "Comment"

            # -- Bo reacts fire, via the real UI: open the picker first ----
            page_b.locator(".entry .rx-open").first.click()
            page_b.wait_for_selector(".entry .rx-picker:not(.hidden)")
            assert page_b.locator(
                ".entry .rx-picker:not(.hidden) .rx").count() == 6
            page_b.locator('.entry [aria-label="fire"]').click()
            # picking collapses the picker in place
            page_b.wait_for_selector(
                ".entry .rx-picker:not(.hidden)", state="detached")
            # optimistic class flip lands immediately, then the POST +
            # refresh() round-trip settles it (Bo's own feed() call can't
            # show my_reaction=fire yet - that needs Anna's record synced
            # back, done below - so this only confirms the click fired
            # and the page survived its own refresh(), not the final state)
            page_b.wait_for_timeout(500)

            # -- Bo comments, via the real UI -------------------------------
            # The composer lives inside .comments, which starts collapsed
            # (the "hidden" class) until comments-toggle is clicked - the
            # composer exists in the DOM either way, so a plain .fill()
            # would time out on "element is not visible" without this.
            page_b.click(".entry .comments-toggle")
            page_b.wait_for_selector(".entry .comments:not(.hidden)")
            page_b.fill(".entry .comment-composer input", "hej fra Bo")
            page_b.click(".entry .comment-composer button[type=submit]")
            # the composer clears its own input before refresh() (T6 fix) -
            # wait on that as confirmation the submit round-tripped
            page_b.wait_for_function(
                "document.querySelector('.entry .comment-composer input')"
                ".value === ''", timeout=8000)

            # -- bridge the author-sweep the harness's sync_with doesn't
            # run on its own (see module docstring): raw response(s) reach
            # Anna, she folds+republishes the record, Bo gets it back.
            b.sync_with(a)
            n = a.node.process_responses()
            assert n == 1
            a.sync_with(b)

            # -- Anna's journal: count + comment with Bo's REAL name -----
            # Collapsed picker: the count shows as a resting chip now, not
            # the (hidden) picker button's aria-label.
            page_a.wait_for_selector(
                f'.entry .rx-count-chip:has-text("{FIRE} 1")',
                timeout=10000)
            page_a.wait_for_selector(
                '.entry .comments-toggle:has-text("Comments (1)")',
                timeout=10000)
            # expand the thread (same real-UI action Bo used) before
            # reading/clicking anything inside .comments - a collapsed
            # thread's contents are present in the DOM but not visible,
            # and the moderation button's own actionability check below
            # needs real visibility, not just DOM presence.
            page_a.click(".entry .comments-toggle")
            page_a.wait_for_selector(".entry .comments:not(.hidden)")
            comment_name_a = page_a.locator(".entry .comment-name").first
            assert comment_name_a.inner_text() == "Bo"
            assert "comment-alias" not in (
                comment_name_a.get_attribute("class") or "")
            assert page_a.locator(".entry .comment-body") \
                .inner_text() == "hej fra Bo"
            # author moderates (not retracts) anyone else's comment
            page_a.wait_for_selector(
                '.comment-x[aria-label="Remove comment"]', timeout=8000)

            # -- Bo's own view of the same comment: "mine" styling --------
            # (retract affordance, never moderation, for your own comment)
            page_b.wait_for_selector(
                '.comment-x[aria-label="Retract comment"]', timeout=10000)
            assert page_b.locator(
                '.comment-x[aria-label="Remove comment"]').count() == 0

            # -- Cleo's view (mutual friend, neither author nor responder):
            # the mutual_box trial-open + sig-verify + _device_bound
            # pipeline, not the author's raw-response shortcut. Cleo's
            # store never held a raw KIND_RESPONSE row at all (routing
            # never sends her one - see module docstring) - her only path
            # to a real name here is decrypting Anna's rebuilt record and
            # successfully opening the mutual box with her own enc key.
            a.sync_with(c)   # Cleo's store holds Anna's freshly rebuilt record
            page_c.wait_for_selector(
                f'.entry .rx-count-chip:has-text("{FIRE} 1")',
                timeout=10000)
            page_c.click(".entry .comments-toggle")
            page_c.wait_for_selector(".entry .comments:not(.hidden)")
            comment_name_c = page_c.locator(".entry .comment-name").first
            assert comment_name_c.inner_text() == "Bo"
            assert "comment-alias" not in (
                comment_name_c.get_attribute("class") or "")
            assert page_c.locator(".entry .comment-body") \
                .inner_text() == "hej fra Bo"
            # Cleo is a mutual friend of the responder, not the post's
            # author - she must never see the moderation affordance.
            assert page_c.locator(
                '.comment-x[aria-label="Remove comment"]').count() == 0

            # -- story leg: Anna posts a story, Bo reacts via the viewer --
            a.node.compose_story(_tiny_png_bytes(), caption="")
            a.sync_with(b)   # Bo's store holds Anna's story

            page_b.wait_for_function(
                "document.querySelectorAll('.story-tile').length >= 2",
                timeout=10000)
            page_b.locator('.story-tile:has-text("Anna") .story-ring').click()
            page_b.wait_for_selector("#story-viewer")
            page_b.click('.sv-react-btn[aria-label="React with fire"]')
            # sendReply's advanceOnSuccess=true calls next() only after the
            # /api/dm fetch resolves ok - with Anna's one story, idx runs
            # past the end and close() removes #story-viewer. Waiting on
            # that detach is confirmation the DM actually sent, not a
            # guess about timing.
            page_b.wait_for_selector("#story-viewer", state="detached",
                                     timeout=10000)

            b.sync_with(a)   # Anna's store holds Bo's story-reply DM

            # -- Anna opens the thread via the real UI: glyph + story-chip
            page_a.click("#nav-messages")
            page_a.wait_for_selector('.conv:has-text("Bo")', timeout=10000)
            page_a.click('.conv:has-text("Bo")')
            page_a.wait_for_selector("#thread .bubble .story-chip",
                                     timeout=10000)
            chip_bubble = page_a.locator(
                "#thread .bubble:has(.story-chip)").first
            assert FIRE in chip_bubble.inner_text()

            assert not errors, f"console pageerrors: {errors}"
            browser.close()
    finally:
        try:
            a.stop()
        finally:
            try:
                b.stop()
            finally:
                c.stop()
