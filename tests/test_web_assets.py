import re
from pathlib import Path

WEB = Path(__file__).resolve().parents[1] / "hearth" / "web"


def _css_rule(css, selector):
    """Pull a single top-level CSS rule's body by its exact selector text
    (e.g. "#block-settings"), across however many lines it spans."""
    m = re.search(re.escape(selector) + r"\s*\{([^}]*)\}", css)
    assert m, f"no CSS rule found for {selector!r}"
    return m.group(1)


def _js_fn_body(js, name):
    """Extract a JS function's body (text between its outer braces) by
    name, via brace counting - robust regardless of what function follows
    it in the file (unlike a '\\nfunction '-style split, which silently
    breaks if the next declaration's style ever changes)."""
    m = re.search(r"(?:async\s+)?function\s+" + re.escape(name) + r"\s*\([^)]*\)\s*\{", js)
    assert m, f"no function {name!r} found"
    depth = 1
    i = m.end()
    while depth > 0:
        if js[i] == "{": depth += 1
        elif js[i] == "}": depth -= 1
        i += 1
    return js[m.end():i - 1]


def test_title_and_no_loop():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert "<title>Kreds</title>" in html
    assert "Loop" not in html                       # no user-facing Loop
    assert 'class="mark"' in html                    # 3-arc mark present


def test_no_cdn_fonts_selfhosted():
    css = (WEB / "style.css").read_text(encoding="utf-8")
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert "fonts.googleapis.com" not in css and "fonts.googleapis.com" not in html
    assert "fonts.gstatic.com" not in css and "fonts.gstatic.com" not in html
    assert "@font-face" in css


def test_pwa_wired_in_html():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert 'rel="manifest"' in html
    assert "apple-touch-icon" in html
    assert 'name="theme-color"' in html


def test_service_worker_registered():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "serviceWorker" in js and "sw.js" in js
    # Root-scope requirement: a worker served under /static/ can only ever
    # control /static/*. It must be registered at the app root (see the
    # dedicated `GET /sw.js` route in hearth/api.py), never under /static/.
    assert "/static/sw.js" not in js


def test_round_icon_buttons_reset_form_padding():
    # Root cause of the 0.3.x "tiny sun/moon, off-center cogwheel" bug: the
    # base form-control rule (`textarea, input, select, button { padding:
    # 8px 11px }`) also pads the fixed-size round icon buttons. A 30x30
    # .themebtn then keeps only a 6px-wide content box, so its 20px svg
    # flex-shrinks into a sliver; .profile-cog's 18px svg overflows a
    # 10px-wide grid content box toward the right/bottom (grid content
    # alignment defaults to start) and gets clipped by the border-radius.
    # The svg display:block rule alone never fixed this - the padding must
    # be reset on every fixed-size round icon button.
    css = (WEB / "style.css").read_text(encoding="utf-8")
    for selector in (".themebtn", ".profile-cog", ".block-settings-btn"):
        rule = _css_rule(css, selector)
        assert re.search(r"padding:\s*0\b", rule), \
            f"{selector} must reset the base form-control padding (padding: 0)"


def test_journal_shell_and_keeps():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert 'id="journal"' in html and 'id="chipbar"' in html
    assert "That's everything" in js                 # end-state copy
    # keeps selector offers inner + kreds (scope sent to /api/post)
    assert "inner" in js and "kreds" in js


def test_no_receipts_popover():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    # honesty guard: the "who holds this post" popover is NOT shipped
    assert "Who holds this post" not in html
    assert "Who holds this post" not in js
    assert ".receipts" not in css


def test_identity_color_and_localstorage_unread():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "identityColor" in js                     # deterministic color fn
    assert "localStorage" in js                      # unread watermark stopgap
    # the watermark is a per-device stopgap, never a server-provided field:
    # helpers are keyed off localStorage only, and there's exactly one
    # identityColor implementation (single source of the color derivation).
    assert "lastOpened" in js and "markOpenedNow" in js
    assert js.count("function identityColor(") == 1


def test_shipped_features_preserved():
    # This is a full rewrite of the client; these are the honesty-relevant
    # and previously-shipped behaviors the rewrite must not silently drop.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert "deleteEverywhere" in js                          # DM/post deletion
    assert "A modified app or a screenshot can still have kept a copy." in js
    assert "openThread" in js and "loadConversations" in js  # DM chat
    assert "renderStories" in js and "openStoryViewer" in js  # stories
    assert "ceremonyUI" in js and "Add friend" in js         # friend-add ceremony
    assert "profileEditor" in js                             # profile editor
    assert 'id="revoked-banner"' in html and "logged out of Kreds" in html


def test_view_containers_present():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert 'id="view-journal"' in html
    assert 'id="view-messages"' in html and 'id="view-profile"' in html
    assert 'id="theme-toggle"' in html
    assert 'id="circle-rail"' in html                # Task 5 fills this in


def test_profile_page_and_ring_move():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert 'id="view-profile"' in html              # profile is a page, not a modal
    assert "openProfile" in js
    assert "/api/ring" in js                        # move between rings
    assert "since" in js                            # ring status renders since


def test_profile_is_a_page_not_a_modal():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert 'id="view-profile"' in html          # profile is a view/page
    assert 'id="profile-body"' in html          # block-slice foundation container
    # the compact modal is gone entirely
    assert 'id="profile-modal"' not in html
    assert ".modalback" not in css and ".pmodal" not in css
    assert "closeProfile" not in js             # modal close code removed


def test_profile_page_honors_banner_and_avatar_shape():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    # banner uses the uploaded image when present (references p.banner as a src)
    assert "p.banner" in js
    # avatar shape/size/placement are applied and styled
    assert "avatar_shape" in js and "avatar_size" in js and "avatar_align" in js
    assert "squircle" in css and "triangle" in css   # shape classes styled


def test_setview_supports_profile_and_back():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert '"profile"' in js                    # setView knows the profile view
    assert "PRIOR_VIEW" in js                    # Back returns to prior view


def test_circle_rail_and_overlay():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert 'id="circle-rail"' in html
    assert "overlay" in html.lower()                # expandable radial overlay
    assert "buildCircle" in js or "renderCircle" in js   # SVG built from /api/kreds
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert ".ringguide" in css                      # radial ring guides (ported)


def test_mobile_tabbar_present():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert "Circle" in html and "Journal" in html   # mobile tab labels


def test_me_view_friends_label_and_right_column():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    # friends section relabeled "Friends" in the profile self-strip (note:
    # the circle rail legitimately still says "Your kreds", so only assert
    # the positive)
    assert ">Friends<" in html
    # the card-summary Me view's own two-column grid + inline editor slot
    # are gone; the self-only strip lives on the profile layout instead
    assert 'id="profile-side"' in html and "has-side" in js
    assert 'id="me-editor-slot"' not in html
    # Me now opens the profile page directly (openMe -> openProfile)
    assert "openMe" in js


def test_unfriend_ui_and_honest_copy():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "/api/unfriend" in js
    assert "no longer connected" in js.lower()
    # the honest copy is present (a distinctive phrase from it)
    assert "we keep trying privately for up to 14 days" in js.lower()
    assert "a screenshot can still keep a copy" in js.lower()


def test_profile_two_sections_and_cogwheel():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    # two distinct sections (journal now lives in the right-column rail)
    assert 'id="profile-wall"' in html and 'id="profile-journal-rail"' in html
    # cogwheel now opens the Settings page (spec 2026-07-15), not an overlay
    assert "profile-cog" in html or "profile-cog" in js
    assert "openSettings" in js
    # renders wall + journal splits from the profile payload
    assert "p.wall" in js and "p.journal" in js
    # profile-post composer posts with placement=profile
    assert 'placement' in js and 'profile' in js


def test_profile_post_composer_scope_selector():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    # the wall composer sends a scope (inner/kreds) + placement=profile
    assert "/api/post" in js


def test_me_tab_opens_profile_with_self_strip():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    # the card-summary Me view is gone; profile view gains a self-only side strip
    assert 'id="view-me"' not in html
    assert 'id="profile-side"' in html
    # Friends + Devices live in the profile side strip now
    assert 'id="friends"' in html and 'id="devices"' in html
    # Me nav + mobile Me tab route to the profile (openProfile), not a card view
    assert 'nav-me' in js and 'openProfile' in js
    # Back is gated on p.mine (hidden on your own profile)
    assert 'profile-back' in js
    # setView no longer carries a "me" view
    assert '"journal", "messages", "profile"' in js or "'journal', 'messages', 'profile'" in js


def test_profile_composer_has_photos_and_block_canvas():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    # composer attaches photos to the profile post
    assert "profilePostComposer" in js
    assert 'fd.append("photos"' in js or "fd.append('photos'" in js
    # a dedicated block renderer exists and the wall uses it (not buildEntry)
    assert "function renderBlock" in js
    # photo block distinguishes one (big) vs several (swipeable deck) -
    # the cropped .block-gallery is retired by collage Slice C,
    # spec 2026-07-13 SS5 (see test_wall_deck_wired)
    assert "block-photo" in css and "block-deck" in css


def test_arrange_mode_and_fixes():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    html = (WEB / "index.html").read_text(encoding="utf-8")
    # arrange mode toggle exists
    assert "profile-arrange" in html or "profile-arrange" in js
    # Retired by Task 6 (collage drag-to-pin): Done used to publish the DOM
    # order via /api/profile-layout; every pin/resize/nudge now persists
    # itself (/api/block-pin, /api/block-unpin - see test_drag_to_pin_wired),
    # so toggleArrange's Done branch has nothing left to POST.
    assert "function toggleArrange" in js
    assert "/api/profile-layout" not in js
    # Fix A: profile page uses the owner's accent (falling back to identityColor),
    # not the viewer's identity-hue, for the page color
    assert "p.accent || identityColor" in js
    # Fix B: per-block scope badge + composer scope note
    assert "block-scope" in js or "block-scope" in (WEB / "style.css").read_text(encoding="utf-8")


def test_journal_rail_and_self_only_friends():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    # journal rail lives in the right column; the main area no longer holds it
    assert 'id="profile-journal-rail"' in html
    # a journal disclosure control for mobile exists
    assert 'profile-journal-toggle' in html or 'profile-journal-toggle' in js
    # right column shows for everyone (journal), friends/devices gated to mine
    assert 'renderMeStrip' in js


def test_photo_grid_layouts():
    # Retired by the collage redesign, spec 2026-07-13: the Slice-3b
    # cols2/cols3/hero/masonry photo-grid picker no longer drives rendering,
    # and renderBlock/renderWall never read p.grid (see
    # test_collage_canvas_wired). photoGridClass's own big-vs-gallery split
    # is itself retired by collage Slice C, spec 2026-07-13 SS5 - multi-photo
    # blocks are decks now (see test_wall_deck_wired). The composer's own
    # compose-time preselect (gridSelect / /api/block-grid) is retired by
    # collage Slice B, spec 2026-07-13 SS4 (see test_composer_preview_wired):
    # the live preview + size chips replace it, and /api/block-grid now has
    # zero client callers.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert "p.grid" not in js
    assert "/api/block-grid" not in js                       # last client caller retired (Slice B)
    for k in ("block-grid-2", "block-grid-3", "block-hero", "block-masonry"):
        assert k not in css                                  # five-layout styling retired


def test_pointer_dnd_present():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    # hand-rolled pointer-events drag (NOT native HTML5 DnD)
    assert "setPointerCapture" in js
    assert "pointermove" in js and "pointerup" in js and "pointercancel" in js
    assert 'draggable="true"' not in js and "dragstart" not in js   # not native DnD
    # Task 3: the standalone drag-handle button + inline Up/Down arrows are
    # gone entirely (whole-block tap-vs-drag replaced them - see
    # test_block_settings_modal) - the drag itself (startBlockDrag) and its
    # touch-action guard remain, just no longer keyed to those old classes.
    assert "drag-handle" not in js and "drag-handle" not in css
    assert "arr-up" not in js and "arr-down" not in js
    assert "startBlockDrag" in js
    assert "touch-action" in css   # kept on .block.arranging now, not .drag-handle
    # no new dependency (single app.js script)
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert html.count("<script") == 1


def test_video_block_render_and_composer():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert 'p.media === "video"' in js or "p.media==='video'" in js
    assert "createElement(\"video\")" in js or "createElement('video')" in js
    assert "autoplay" not in js.split("renderBlock")[1][:1200]   # no autoplay in the block render
    assert 'accept="video/*"' in js or "accept='video/*'" in js  # composer video picker
    assert "block-video" in css


def test_bento_grid_render():
    # Retired by the collage redesign, spec 2026-07-13: Phase-A's p.size /
    # size-small|wide|full bento span classes are gone from both render and
    # CSS - renderBlock now reads p.pin/p.span onto inline grid-column/
    # grid-row (see test_collage_canvas_wired); #profile-wall-flow still
    # packs unplaced blocks with grid-auto-flow, just not via size-*
    # classes (the tray it once shared this rule with is retired, spec
    # 2026-07-14 dynamic placement).
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert "p.size" not in js
    assert "grid-auto-flow" in css                         # unplaced flow packing
    for k in ("size-small", "size-wide", "size-full"):
        assert k not in css
        assert k not in js


def test_block_settings_modal():
    # Retired by the collage redesign, spec 2026-07-13: the modal's old
    # Phase-A Size (/api/block-size) and Slice-3b Photo-layout groups are
    # gone. Task 7 then retired the reorder-era Move Up/Down pair too and
    # rebuilt the modal around pin/span geometry (Size presets, Nudge,
    # Send to tray, Place on canvas). Dynamic placement (spec 2026-07-14)
    # then retired Send to tray / Place on canvas themselves - see
    # test_block_settings_modal_collage_groups.
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert 'id="block-settings"' in html                 # modal markup
    assert "openBlockSettings" in js                     # opener
    assert "/api/block-size" not in js                   # size action retired
    assert "drag-handle" not in js                       # inline 3-line handle removed
    assert 'className = "grid-pick"' not in js and "grid-pick" not in js  # inline select removed


def test_block_settings_keyboard_affordance():
    # a11y regression fix: the modal was tap-only (pointerdown), leaving no
    # keyboard path to open it. A focusable button must open it too.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert "block-settings-btn" in js and "block-settings-btn" in css
    # it's a real <button> that wires straight into the opener
    assert 'el("button", "block-settings-btn"' in js
    # opener arg added for focus-return (review fix #5) - gear remembers itself
    assert "cog.onclick = () => openBlockSettings(p, block, cog);" in js
    # not a reversion to the old cluttered handle/up-down/select controls
    assert "drag-handle" not in js and "grid-pick" not in js


def test_whole_branch_review_fixes():
    # Whole-branch review (Kreds profile bento canvas, Phase A) - guards
    # against each finding regressing silently.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")

    # IMPORTANT #1: both self-only overlays are viewport-fixed, not
    # absolute-inside-#app (which grows with content while the body
    # scrolls, stranding a "centered" card thousands of px away on a tall
    # wall). #story-viewer already used fixed; #block-settings must match it.
    assert "position: fixed" in _css_rule(css, "#block-settings")

    # IMPORTANT #2: pointercancel is an aborted gesture, not a tap - it
    # must have its own handler that only tears down, never one shared
    # with pointerup's open-the-modal branch.
    assert 'window.addEventListener("pointercancel", cancel)' in js
    assert 'window.addEventListener("pointercancel", up)' not in js

    # IMPORTANT #3: native image-drag / text-selection can't hijack a
    # whole-block mouse drag.
    assert "img.draggable = false" in js               # (a) photo <img>s aren't natively draggable
    arranging_rule = _css_rule(css, ".block.arranging")
    assert "user-select: none" in arranging_rule        # (b) no text-selection smear
    assert "-webkit-user-drag: none" in arranging_rule
    # (c) preventDefault happens synchronously in the block's own
    # pointerdown handler - ordered after the closest() bail (so controls
    # still work) but before the tap/drag bookkeeping starts, not only
    # (too late) inside startBlockDrag's own preventDefault.
    handler_start = js.index('block.addEventListener("pointerdown"')
    # Task 6 (albums) added "label" to the bail list so .block-add's file
    # input stays clickable in Arrange - the ordering guard still holds.
    closest_idx = js.index('closest("button, a, select, video, label")', handler_start)
    prevent_idx = js.index("ev.preventDefault()", handler_start)
    move_def_idx = js.index("const move = (e) =>", handler_start)
    assert closest_idx < prevent_idx < move_def_idx

    # IMPORTANT #4: the drag controller's wall-level listeners filter by
    # pointerId, so a second concurrent touch on the wall can't hijack an
    # in-progress drag. onLost is exempt (per the fix spec) so it's not
    # counted here. Task 6 rewrote startBlockDrag for cell-targeting (the
    # reorder-era onUp/onCancel bodies this used to pin are gone), but the
    # pointerId-filter discipline on every handler still holds - just in the
    # new handlers' own idiom (onMove's early-return, onUp/onCancel's guard).
    drag_fn = _js_fn_body(js, "startBlockDrag")
    assert "if (e.pointerId !== ev.pointerId) return;" in drag_fn        # onMove
    assert "const onUp = (e) => { if (e.pointerId === ev.pointerId) finish(true); };" in drag_fn
    assert "const onCancel = (e) => { if (e.pointerId === ev.pointerId) finish(false); };" in drag_fn

    # IMPORTANT #5: the modal manages focus - remembers its opener, moves
    # focus in on open, traps Tab, and returns focus to the opener on close.
    assert "BLOCK_SETTINGS_OPENER" in js
    assert "opener.focus()" in js
    assert 'if (ev.key !== "Tab") return;' in js        # Tab-trap keydown handler present
    assert "target.focus()" in js                       # focus moved in on open/rebuild

    # MINOR #6 (if implemented, not just deferred-with-a-comment): tap-phase
    # pointer capture, released before the drag handoff takes the wall's.
    assert "block.setPointerCapture(ev.pointerId)" in js
    assert "block.releasePointerCapture(ev.pointerId)" in js


def test_image_lightbox():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert "openLightbox" in js                       # controller
    assert 'id = "lightbox"' in js or 'ov.id = "lightbox"' in js
    assert "!ARRANGING" in js                          # gated to normal view
    assert "ArrowLeft" in js and "ArrowRight" in js    # keyboard nav
    assert "zoom-in" in js                             # click affordance on photos
    assert "#lightbox" in css and "object-fit: contain" in css


# ---------------------------------------------------------------------
# App-lock client (Task 4): lock screen (PIN/passphrase) + settings.
# Node is the source of truth (GET /api/applock); the client only
# reflects it and never persists the credential itself.
# ---------------------------------------------------------------------

def test_applock_lock_screen_markup():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert 'id="lock-screen"' in html
    assert re.search(r'<form[^>]*id="lock-form"', html)   # real <form> - Enter submits
    assert 'id="lock-cred"' in html
    assert 'id="lock-keypad"' in html               # numeric PIN keypad, per cred_type
    assert 'id="lock-submit"' in html
    assert 'id="lock-error"' in html


def test_applock_boot_check_and_unlock_wired():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "getApplockStatus" in js
    assert "/api/applock" in js                     # on-load status check
    assert "/api/unlock" in js
    assert "renderLockScreen" in js
    assert "hideLockScreen" in js
    assert "cred.focus()" in js                      # focus the field on show


def test_applock_423_gate_on_any_fetch():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    # a wrapped fetch (or equivalent central check) reacts to ANY 423, not
    # just the boot-time status call - so an autolock mid-session is caught.
    assert "window.fetch" in js
    assert "423" in js


def test_applock_throttle_countdown():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "throttle_wait" in js
    assert "applyThrottle" in js
    assert "lock-submit" in js                       # disabled while throttled


def test_applock_settings_section_present():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert 'id="sec-applock"' in html                # Settings page section (spec 2026-07-15)
    assert 'id="applock-settings"' in html
    assert "renderApplockSettings" in js
    assert "/api/applock/setup" in js
    assert "/api/applock/settings" in js
    assert "/api/applock/change" in js
    assert "/api/applock/disable" in js
    assert "applock-lock-now" in js


def test_applock_idle_select_and_sleep_checkbox():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "IDLE_OPTIONS" in js
    assert "idle_minutes" in js
    assert "lock_on_sleep" in js
    assert "applock-lock-on-sleep" in js


def test_applock_client_hooks_visibility_and_heartbeat():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert 'addEventListener("visibilitychange"' in js
    assert 'document.visibilityState === "hidden"' in js
    assert "startApplockHeartbeat" in js
    assert "setInterval" in js


def test_applock_hides_app_and_tabbar_while_locked():
    # position:fixed alone doesn't remove #app's controls from the Tab
    # order / AT tree - the overlay must actually hide #app (+ the mobile
    # tabbar, which lives outside #app) so nothing behind it is reachable.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    lock_render = js.split("function renderLockScreen(")[1].split("\nfunction ")[0]
    assert 'document.getElementById("app").classList.add("hidden")' in lock_render
    assert ".tabbar-mobile" in lock_render
    lock_hide = js.split("function hideLockScreen(")[1].split("\nfunction ")[0]
    assert 'document.getElementById("app").classList.remove("hidden")' in lock_hide
    assert ".tabbar-mobile" in lock_hide


# ---------------------------------------------------------------------
# First-run onboarding (Task 2): a node with no enrolled identity yet
# (GET /api/bootstrap -> initialized:false) shows Create/Connect instead
# of the normal app. Node is the source of truth for /api/bootstrap; the
# client only branches on it.
# ---------------------------------------------------------------------

def test_first_run_onboarding():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert 'id="first-run"' in html
    assert "renderFirstRun" in js
    assert "/api/bootstrap/create" in js and "/api/bootstrap/pair-request" in js
    assert "/api/bootstrap/pair-install" in js


# ---------------------------------------------------------------------
# One-time onboarding wizard + logo animation + lock-screen restyle
# (Task 3): shown once right after a fresh node's create/pair, gated by
# NEEDS_WIZARD (Task 2 sets it in boot() from GET /api/bootstrap's
# onboarding_done). Two steps - App-lock (genuinely skippable) and an
# honest iPhone-in-development note - then POST /api/onboarding-done so
# it never shows again.
# ---------------------------------------------------------------------

def test_onboarding_wizard_and_logo_anim():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert "renderOnboardingWizard" in js and "/api/onboarding-done" in js
    assert "Skip" in js                                   # app-lock is skippable
    assert "prefers-reduced-motion" in css                # animation honors reduced motion
    assert "@keyframes" in css                            # logo breathing/rotation


def test_onboarding_wizard_markup_present():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert 'id="onboarding-wizard"' in html
    wiz_html = html.split('id="onboarding-wizard"')[1][:900]
    assert 'class="mark' in wiz_html                      # shares the logo treatment
    assert 'id="wiz-step-applock"' in wiz_html and 'id="wiz-step-phone"' in wiz_html


def test_onboarding_wizard_wired_into_bootdata():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    bootdata = _js_fn_body(js, "bootData")
    assert "NEEDS_WIZARD" in bootdata and "renderOnboardingWizard()" in bootdata


def test_onboarding_wizard_finish_marks_done_and_clears_flag():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    finish = _js_fn_body(js, "finishOnboardingWizard")
    assert "/api/onboarding-done" in finish
    assert "NEEDS_WIZARD = false" in finish
    assert '"onboarding-wizard"' in finish                # dismisses the overlay


def test_onboarding_wizard_applock_step_skippable_and_reuses_setup():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    wiz = _js_fn_body(js, "renderOnboardingWizard")
    assert "Protect this device with a PIN or passphrase" in wiz
    assert "/api/applock/setup" in wiz                     # reuses the same setup endpoint
    assert "Skip" in wiz
    # Skip's own handler must not call setup - it just advances (genuinely
    # skippable, not "skip = silently enable with a default").
    skip_onclick = wiz[wiz.index('el("button", "", "Skip")'):]
    skip_onclick = skip_onclick[:skip_onclick.index(";", skip_onclick.index("onclick"))]
    assert "/api/applock/setup" not in skip_onclick
    assert "showWizardStep(1)" in skip_onclick


def test_onboarding_wizard_phone_step_honest_copy():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    wiz = _js_fn_body(js, "renderOnboardingWizard")
    assert "Kreds for iPhone is in development" in wiz
    assert "pair your phone to this node when it ships" in wiz
    assert "pair any device anytime from Settings" in wiz
    assert "Continue" in wiz


def test_onboarding_wizard_esc_skips_to_done_not_silent_dismiss():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    keydown = _js_fn_body(js, "onboardingWizardKeydown")
    assert '"Escape"' in keydown
    assert "finishOnboardingWizard()" in keydown


def test_onboarding_wizard_focuses_first_control():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert ".focus()" in _js_fn_body(js, "showWizardStep")


def test_onboarding_wizard_hides_app_and_tabbar_no_flash():
    # Mirrors the applock lock-screen's own hide-#app pattern (position:fixed
    # alone doesn't remove #app's controls from the Tab order/AT tree) - and
    # boot() must pre-hide #app itself so bootData() populating it doesn't
    # flash the real app before the wizard ever shows.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    render = _js_fn_body(js, "renderOnboardingWizard")
    assert 'document.getElementById("app").classList.add("hidden")' in render
    assert ".tabbar-mobile" in render
    boot = _js_fn_body(js, "boot")
    assert "NEEDS_WIZARD" in boot
    assert 'document.getElementById("app").classList.add("hidden")' in boot


def test_logo_breathing_animation_scoped_and_reduced_motion_aware():
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert "@keyframes" in css
    # explicitly gated by a reduced-motion query (defense in depth on top of
    # the existing blanket kill-switch)
    assert re.search(r"prefers-reduced-motion:\s*(no-preference|reduce)", css)
    assert "mark-anim" in css                              # scoped modifier, not the persistent nav mark


def test_first_run_option_cards_have_hover_transition():
    css = (WEB / "style.css").read_text(encoding="utf-8")
    fr_option_rule = _css_rule(css, ".fr-option")
    assert "transition" in fr_option_rule
    assert ".fr-option:hover" in css


def test_lock_screen_keypad_structure_unchanged():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    # the existing PIN keypad + passphrase field structure is untouched -
    # only the surrounding chrome was restyled (per brief Step 5)
    assert 'id="lock-keypad"' in html
    for digit in range(10):
        assert f'data-digit="{digit}"' in html
    assert 'id="lock-clear"' in html and 'id="lock-backspace"' in html
    assert 'id="lock-cred"' in html and 'id="lock-submit"' in html and 'id="lock-error"' in html


def test_lock_screen_shares_first_run_logo_treatment():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    lock_screen_html = html.split('id="lock-screen"')[1].split('id="lock-form"')[0]
    assert 'class="mark' in lock_screen_html
    assert "mark-anim" in lock_screen_html
    assert "kreds" in lock_screen_html.lower()             # shares the first-run wordmark
    css = (WEB / "style.css").read_text(encoding="utf-8")
    # same background treatment as #first-run (var(--paper), fixed overlay)
    fr_rule = _css_rule(css, "#first-run")
    lock_rule = _css_rule(css, "#lock-screen")
    assert "background: var(--paper)" in fr_rule
    assert "background: var(--paper)" in lock_rule


# ---------------------------------------------------------------------
# Whole-branch review (Kreds desktop onboarding): guards against each
# finding regressing silently.
# ---------------------------------------------------------------------

def test_boot_checks_response_status_before_parsing_bootstrap_json():
    # IMPORTANT #1: a revoked device's GET /api/bootstrap gets a 410 from
    # revoked_gate (hearth/api.py doesn't allowlist /api/bootstrap for a
    # revoked device - only /api/state is exempt), body
    # {"detail": "device revoked"} - no "initialized" key. Parsing that
    # unconditionally makes !b.initialized true -> wrongly renders
    # first-run over a revoked device. boot() must check r.ok first and
    # fall through to the lock/state path (which shows the revoked banner)
    # on any non-2xx, exactly like its existing network-error catch.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    boot = _js_fn_body(js, "boot")
    assert "fetch(\"/api/bootstrap\")" in boot
    assert "r.ok" in boot
    assert "initialized: true" in boot and "onboarding_done: true" in boot


def test_lock_screen_hides_onboarding_wizard_and_drops_its_keydown():
    # IMPORTANT #2(a): the wizard shares #app's z-index and lives outside
    # #app, so hiding #app/tabbar alone leaves the wizard painted on top of
    # the lock screen with dead 423 controls if App-lock got enabled at
    # wizard Step A and the node then autolocks before the wizard finishes.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    lock_render = _js_fn_body(js, "renderLockScreen")
    assert 'document.getElementById("onboarding-wizard").classList.add("hidden")' in lock_render
    assert 'document.removeEventListener("keydown", onboardingWizardKeydown)' in lock_render


def test_finish_onboarding_wizard_does_not_unhide_app_under_lock():
    # IMPORTANT #2(b): finishOnboardingWizard (Esc/Continue) must not
    # unconditionally unhide #app/tabbar - if the lock screen is currently
    # showing (autolock fired while the wizard was open), doing so would
    # restore gated content to the Tab/AT tree behind the lock overlay.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    finish = _js_fn_body(js, "finishOnboardingWizard")
    assert '"lock-screen"' in finish
    guard_idx = finish.index('classList.contains("hidden")')
    unhide_idx = finish.index('document.getElementById("app").classList.remove("hidden")')
    assert guard_idx < unhide_idx                # the unhide is inside the guarded branch


def test_applock_credential_never_persisted():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    # the credential must never be written to persistent client storage
    for m in re.finditer(r'(?:localStorage|sessionStorage)\.setItem\(([^)]*)\)', js):
        args = m.group(1).lower()
        assert "cred" not in args and "pin" not in args and "passphrase" not in args


# ---------------------------------------------------------------------
# Desktop custom chrome (Kreds Windows app shell, Task 3): frameless
# title bar + traffic-light controls (minimize/maximize/close) + a
# close_behavior setting (wizard step + Settings toggle). Everything here
# is gated on window.pywebview -- a plain browser (dev/demo) must see
# none of it.
# ---------------------------------------------------------------------

def test_desktop_custom_chrome():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert "window.pywebview" in js                       # desktop detection
    assert "pywebview-drag-region" in html or "pywebview-drag-region" in js  # drag region
    assert ".titlebar" in css                             # custom chrome styled
    assert "close_behavior" in js and "/api/settings" in js


def test_titlebar_markup_buttons_not_draggable():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert 'id="titlebar"' in html
    titlebar_html = html.split('id="titlebar"')[1][:1200]   # comfortably covers the whole block
    assert "pywebview-drag-region" in titlebar_html
    assert 'id="titlebar-min"' in titlebar_html
    assert 'id="titlebar-max"' in titlebar_html
    assert 'id="titlebar-close"' in titlebar_html
    # the three control buttons must NOT carry the drag class themselves,
    # only the separate title/logo span does -- otherwise clicks on them
    # would be swallowed by pywebview's window-drag handling instead of
    # registering as clicks. Check each <button ...> tag's own class attr.
    buttons = re.findall(r'<button\b[^>]*>', titlebar_html)
    assert len(buttons) == 3
    for btn in buttons:
        assert "pywebview-drag-region" not in btn


def test_titlebar_hidden_by_default_and_desktop_gated_in_js():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    titlebar_html = html.split('<div class="titlebar')[1][:40]
    assert "hidden" in titlebar_html          # hidden by default (plain browser)
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "pywebviewready" in js             # wired on pywebview's ready event
    assert '.classList.add("desktop")' in js  # reveals the bar / offsets content


def test_close_reads_live_pref_each_time():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    chrome_fn = _js_fn_body(js, "wireDesktopChrome")
    assert "/api/settings" in chrome_fn
    assert '"keep"' in chrome_fn
    assert "api.minimize()" in chrome_fn and "api.quit()" in chrome_fn


def test_wizard_close_behavior_step_desktop_only():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    wiz = _js_fn_body(js, "renderOnboardingWizard")
    assert "window.pywebview" in wiz
    assert "Keep running in the background" in wiz
    assert "/api/settings" in wiz


def test_settings_toggle_desktop_only_in_me_area():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert 'id="sec-desktop"' in html
    assert 'id="desktop-settings"' in html
    assert "renderDesktopSettings" in js
    render = _js_fn_body(js, "renderDesktopSettings")
    assert "window.pywebview" in render
    assert "/api/settings" in render


# ---------------------------------------------------------------------
# Signed in-app updates (Task 3): a self-only Updates panel in the
# profile side strip (mirrors App-lock/Desktop) -- "Check for updates"
# hits GET /api/update/check, and an Apply button (shown only once an
# update is available) hits POST /api/update/apply, reloading on a web
# hot-swap or telling the user to restart on a staged core update.
# ---------------------------------------------------------------------

def test_updates_panel_markup_present():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert 'id="sec-updates"' in html
    assert 'id="update-settings"' in html
    assert "Updates" in html.split('id="sec-updates"')[1][:120]


def test_updates_ui_wired_into_app():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "renderUpdateSettings" in js
    assert "/api/update/check" in js
    assert "/api/update/apply" in js
    render = _js_fn_body(js, "renderUpdateSettings")
    assert "/api/update/check" in render
    # Task 2 (0.3.15): the apply fetch itself moved into the shared
    # applyUpdateNow helper (reused by the update banner) - the panel just
    # calls it, rather than duplicating the fetch+decision inline.
    assert "applyUpdateNow" in render


def test_updates_ui_wired_into_me_strip():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    me_strip = _js_fn_body(js, "renderMeStrip")
    assert "renderUpdateSettings" in me_strip


def test_updates_ui_apply_reload_vs_restart_paths():
    # Task 2 fix (review findings, 0.3.15): applyUpdateNow owns only the
    # unambiguous web reload path - it returns the parsed apply result and
    # leaves out.restart_required to each caller, since the banner
    # ("Restart to update") and the Settings panel ("Apply update") make
    # different promises to the user about when a restart happens.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    helper = _js_fn_body(js, "applyUpdateNow")
    # web hot-swap -> reload, still owned by the shared helper
    assert "location.reload()" in helper
    assert "out.reload" in helper
    # restart_required is returned by the helper, not actioned by it
    assert "restart_required" in helper
    assert "return out" in helper

    # Banner: "Restart to update" is a promise to restart on this click -
    # it restarts immediately once the apply stages.
    banner = _js_fn_body(js, "renderUpdateBanner")
    assert "applyUpdateNow" in banner
    assert "restart_required" in banner
    assert "api.restart" in banner

    # Settings: "Apply update" is not a promise to restart - it must not
    # restart silently. It renders a user-clicked "Restart now" button
    # instead (the pre-fix UX), reusing the same api.restart idiom.
    settings = _js_fn_body(js, "renderUpdateSettings")
    assert "applyUpdateNow" in settings
    assert "restart_required" in settings
    assert "Restart now" in settings          # restored user-clicked affordance
    assert "restart Kreds" in settings        # plain-browser text fallback


def test_updates_ui_check_button_keyboard_accessible():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    render = _js_fn_body(js, "renderUpdateSettings")
    # real <button> elements (Tab/Enter/Space reachable), not div/span
    # click-handler-only controls
    assert 'el("button"' in render


def test_updates_ui_checks_response_ok_before_parsing():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    render = _js_fn_body(js, "renderUpdateSettings")
    assert "r.ok" in render


# ---------------------------------------------------------------------
# Easier friend-add (Task 3): a two-mode Add-friend panel is now the
# default -- Share my code (POST /api/friend/invite, live expiry
# countdown, Regenerate) and Enter a code (POST /api/friend/add,
# connected/manual). The original 4-box copy-paste ceremony
# (respond/finalize/complete, server-side unchanged) moves under a
# "manual fallback" disclosure rather than being deleted.
# ---------------------------------------------------------------------

def test_friend_add_entry_point_and_tabs_present():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    # ceremonyUI stays the Settings>Friends entry point (bootData calls
    # it); the tab panel itself now comes from buildFriendAdd so the
    # topbar "+" popover hosts the identical flow (spec 2026-07-15).
    ceremony = _js_fn_body(js, "ceremonyUI")
    assert "buildFriendAdd" in ceremony
    panel = _js_fn_body(js, "buildFriendAdd")
    assert "friendadd-tab" in panel
    assert "Share my code" in panel and "Enter a code" in panel
    assert "buildShareTab" in panel and "buildEnterTab" in panel


def test_friend_add_manual_fallback_still_reachable():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    ceremony = _js_fn_body(js, "buildFriendAdd")
    assert "buildManualCeremony" in ceremony
    assert "manual code exchange" in ceremony
    # the manual builder still drives the SAME four endpoints as before --
    # this is the fallback the "manual" response from /api/friend/add
    # walks the user through by hand.
    manual = _js_fn_body(js, "buildManualCeremony")
    for step_url in ("/api/friend/invite", "/api/friend/respond",
                      "/api/friend/finalize", "/api/friend/complete"):
        assert step_url in manual


def test_friend_add_share_tab_code_copy_and_countdown():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    share = _js_fn_body(js, "buildShareTab")
    assert "/api/friend/invite" in share
    assert "Copy code" in share
    assert "wireCopyButton" in share
    # live countdown driven by setInterval, computed from expires_at,
    # updates via textContent (not innerHTML)
    assert "setInterval" in share
    assert "r.expires_at" in share
    assert 'countdown.textContent = "expires in "' in share


def test_friend_add_share_tab_expiry_and_regenerate():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    share = _js_fn_body(js, "buildShareTab")
    assert '"Code expired"' in share
    assert "Regenerate" in share
    # Regenerate re-POSTs the exact same getCode flow used for the first code
    assert "regenBtn.onclick = getCode" in share


def test_friend_add_enter_tab_connected_and_manual_paths():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    enter = _js_fn_body(js, "buildEnterTab")
    assert "/api/friend/add" in enter
    assert '"connected"' in enter
    assert "You're now friends with " in enter
    assert "refresh()" in enter
    assert "They seem offline" in enter
    assert "r.response" in enter
    assert "Copy code" in enter and "wireCopyButton" in enter


def test_friend_add_notes_use_textcontent_not_innerhtml():
    # XSS-safe pattern check (matches the rest of the client): server-
    # controlled/user-pasted values (friend name, response code, error
    # messages) must land via textContent/.value, never innerHTML, across
    # the code+status handling helpers. (ceremonyUI's own innerHTML use is
    # the pre-existing static "+<span>Add friend</span>" icon markup, same
    # established pattern as the Send/Attach button icons elsewhere.)
    js = (WEB / "app.js").read_text(encoding="utf-8")
    block_start = js.index("function wireCopyButton")
    block_end = js.index("function ceremonyUI")
    block = js[block_start:block_end]
    assert "innerHTML" not in block
    assert "buildShareTab" in block and "buildEnterTab" in block \
        and "buildManualCeremony" in block   # sanity: didn't slice past the intended functions


def test_friend_add_keyboard_accessible():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    enter = _js_fn_body(js, "buildEnterTab")
    # real <label for=...> tied to the textarea's id
    assert 'label.htmlFor = "friendadd-enter-code"' in enter
    assert 'ta.id = "friendadd-enter-code"' in enter
    # Enter submits (Shift+Enter still allowed to insert a newline)
    assert '"keydown"' in enter
    assert 'ev.key === "Enter" && !ev.shiftKey' in enter
    assert "requestSubmit" in enter
    # focus management: opening a tab focuses its first meaningful control
    ceremony = _js_fn_body(js, "ceremonyUI")
    assert "friendaddFocus" in ceremony


def test_friend_add_css_present():
    css = (WEB / "style.css").read_text(encoding="utf-8")
    for sel in (".friendadd-tabs", ".friendadd-tab", ".friendadd-body",
                ".friendadd-code", ".friendadd-countdown", ".ceremony-manual"):
        assert sel in css


def test_circle_world_scales_with_count():
    # Spec 2026-07-08-kreds-circle-zoom: the overlay circle gets BIGGER
    # with friend count, never denser - ring radius derives from occupancy
    # (constant node spacing), and the world size is published on the svg
    # for the camera. The rail's call must NOT opt in.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "CIRCLE_SPACING = 64" in js
    assert "CIRCLE_RING_GAP = 78" in js
    assert "CIRCLE_MARGIN = 60" in js
    assert "function ringRadius(" in js
    assert "scaleWithCount" in js
    assert "dataset.worldSize" in js
    # overlay call opts in; rail call does not
    overlay_call = js.split("function openCircleOverlay")[1][:400]
    assert "scaleWithCount: true" in overlay_call
    rail_fn = js.split("function renderCircleRail")[1][:900]
    assert "scaleWithCount" not in rail_fn


def test_circle_overlay_fills_screen_and_label_css():
    css = (WEB / "style.css").read_text(encoding="utf-8")
    html = (WEB / "index.html").read_text(encoding="utf-8")
    # the fixed 470px cap is gone; vmin-square viewport instead
    assert "min(76%, 470px)" not in css
    assert "94vmin" in css
    assert "touch-action: none" in _css_rule(css, ".bigmapwrap svg")
    # label visibility contract + reduced-motion opt-out
    assert ".labels-off" in css
    assert re.search(r"prefers-reduced-motion[^}]*\.nlabel", css, re.S)
    # Fit reset is a real, labeled <button>; hint teaches the gestures
    assert re.search(r'<button[^>]*id="circle-fit"', html)
    assert "pinch to zoom" in html and "drag to move" in html


def test_circle_camera_core():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "const circleCamera" in js
    cam = js.split("const circleCamera")[1].split("\nfunction wireCircleGestures")[0] \
        if "wireCircleGestures" in js else js.split("const circleCamera")[1][:4000]
    # zoom clamp: never past fit, never tighter than fit/8 (8x magnification)
    assert "fitW / 8" in cam
    # pan clamp: at least 20% of the world stays on-screen per axis
    assert "0.2" in cam and "0.8" in cam
    # anchored zoom + fit entry points
    assert "zoomAt(" in js and ".fit()" in js
    # wheel wired non-passively (preventDefault must work), dblclick + Fit reset
    assert '"wheel"' in js and "passive: false" in js
    assert '"dblclick"' in js
    assert '"circle-fit"' in js
    # opening the overlay initializes the camera at fit
    overlay_fn = js.split("function openCircleOverlay")[1][:600]
    assert "circleCamera" in overlay_fn


def test_circle_gestures_and_labels():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "function wireCircleGestures" in js
    g = js.split("function wireCircleGestures")[1].split("\nconst circleCamera")[0] \
        if js.index("function wireCircleGestures") < js.index("const circleCamera") \
        else js.split("function wireCircleGestures")[1][:5000]
    # pointer-events discipline (profile-drag lessons): cancel + capture-loss
    # both tear down; per-pointer bookkeeping for pinch; 6px tap threshold
    for token in ("pointerdown", "pointermove", "pointerup",
                  "pointercancel", "lostpointercapture",
                  "setPointerCapture", "Math.hypot"):
        assert token in g, token
    assert "> 6" in g
    # review fixes: gone() idempotent (pointerup + lostpointercapture both
    # fire); stale pinch flag cleared at gesture start; pinch never counts
    # toward double-tap
    assert "if (!pts.has(ev.pointerId)) return;" in g
    assert "if (pts.size === 0) CIRCLE_DRAGGED = false;" in g
    assert "!multi" in g
    # tap path must stay uncaptured: capture is deferred to pan/pinch
    # (capture at pointerdown retargets the click to the svg - dead taps,
    # found by live smoke)
    assert "const capture = ()" in g
    down_block = g.split('addEventListener("pointerdown"')[1]
    down_block = down_block[:down_block.index('addEventListener("pointermove"')]
    assert "setPointerCapture" not in down_block
    assert 'window.addEventListener("pointerup", gone)' in g
    # drag must not fire the node click that follows pointerup
    assert "CIRCLE_DRAGGED" in js
    click_handler = js.split('document.getElementById("circle-overlay-svg").addEventListener("click"')[1][:400]
    assert "CIRCLE_DRAGGED" in click_handler
    # double-tap reset for touch
    assert "300" in g
    # label threshold: on-screen node pitch vs 56px, toggling labels-off
    assert "labels-off" in js and "56" in js
    labels_fn = js.split("function updateCircleLabels")[-1][:600]
    assert "CIRCLE_SPACING" in labels_fn and "classList.toggle" in labels_fn
    # no native DnD anywhere in the gesture code
    assert "dragstart" not in g and 'draggable="true"' not in g
    # labels compare the ACTUAL node pitch published by buildCircle, not the
    # SPACING floor (small circles keep labels at fit on small screens)
    assert "dataset.nodePitch" in js
    # a11y: focusing an off-camera node pans it into view
    assert '"focusin"' in js and "ensureVisible" in js
    # narrow screens shorten the gesture hint
    html = (WEB / "index.html").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert "hint-ext" in html and ".overlayhint .hint-ext" in css


def test_desktop_keep_close_hides_to_tray_with_fallback():
    # Spec 2026-07-08-kreds-tray-icon: close with "keep" hides to the tray;
    # an OLDER frozen shell (web payload ahead of core - the skew the
    # updater allows) lacks hide_to_tray, so the handler falls back to
    # minimize. The titlebar minimize button itself stays minimize.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    chrome = _js_fn_body(js, "wireDesktopChrome")
    # pin the GUARDED call structure inside the keep-branch itself - a
    # whole-function substring would pass on the comment text alone, or on
    # the unrelated titlebar minimize-button line (task review, Important)
    keep_branch = chrome[chrome.index('if (closeBehavior === "keep")'):]
    keep_branch = keep_branch[:keep_branch.index("api.quit()")]
    assert "if (api.hide_to_tray) api.hide_to_tray();" in keep_branch
    assert "else api.minimize();" in keep_branch
    # user-facing copy names the tray (wizard step + Settings label)
    assert js.count("in the system tray") >= 2


def test_invite_display_is_truncated_and_copies_full():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    # a short-display helper renders kreds·invite·<FP>…<suffix>; Copy copies raw
    assert "kreds·invite·" in js
    assert "shortInvite" in js
    # the enter path names the fingerprint the user should confirm
    assert "starts with" in js
    # copy uses the full code, not the truncated display (grep the copy wiring)
    share = _js_fn_body(js, "buildShareTab")
    assert "shortInvite" in share


def test_journal_composer_photo_button_not_a_scope_and_post_fails_loud():
    # Bug (first two-machine test, 2026-07-10): the Photo button is
    # <label class="keep"> (for pill styling), so the scope handler
    # (#composer .keep) caught it, moved .active onto Photo and set scope to
    # undefined -> server 400 -> the post silently vanished (inputs cleared
    # with NO error, looking like an undeletable orphan; nothing was actually
    # created). Scope logic must target .keep[data-scope] so the Photo label
    # is excluded, AND the submit must check response.ok and keep the user's
    # text/photo on failure (matching the Wall composer's pattern).
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "#composer .keep[data-scope]" in js
    submit = js.split('document.getElementById("composer").onsubmit')[1].split("\n};")[0]
    assert "r.ok" in submit and "Post failed" in submit
    # the input-clearing must come AFTER the ok-guard (a failed post keeps text)
    assert submit.index("Post failed") < submit.index('"post-text").value = ""')


def test_seen_state_observer_wired():
    # The 2026-07-13 seen-state fix: a post genuinely on screen (or a
    # profile visit) clears the person's new-post dot - not just the
    # chip click. Static wiring asserts; the live behavior is pinned by
    # tests/test_ui_smoke_seen_badge.py (UI_E2E=1).
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "IntersectionObserver" in js
    assert "SEEN_DWELL_MS" in js and "SEEN_RATIO" in js
    rj = _js_fn_body(js, "renderJournal")
    assert "journalSeenObserver" in rj          # entries observed on render
    assert "disconnect" in rj                   # re-attached, never leaked
    bump = _js_fn_body(js, "bumpOpenedTo")
    assert "lastOpened" in bump                 # never moves backwards
    prof = _js_fn_body(js, "openProfile")
    assert "markOpenedNow" in prof              # profile visit clears the dot
    be = _js_fn_body(js, "buildEntry")
    assert "dataset.created" in be              # observer needs the post time


def test_dm_unread_badge_wired():
    # Unread badge (live-test follow-up): count of conversations whose
    # last message is from the other side and newer than the per-device
    # kreds_dm_opened watermark. Desktop nav only (mobile has no Messages
    # tab - a named follow-up, not silently included).
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert 'id="nav-msg-badge"' in html
    assert "kreds_dm_opened:" in js               # own prefix, not kreds_opened
    unread = _js_fn_body(js, "convUnread")
    assert "last_from_me" in unread
    assert "over-badge" in unread                 # skew degrade documented
    badge = _js_fn_body(js, "renderDmBadge")
    assert "hidden" in badge                      # hidden at zero
    assert "measureNavHeight" in badge            # badge toggle can resize .appnav
    assert "aria-label" in badge                  # count is exposed to AT, not static
    thread = _js_fn_body(js, "openThread")
    assert "markDmOpenedNow" in thread            # opening clears
    assert "document.hidden" in thread            # never mark read behind a hidden window
    assert "await j(\"/api/conversations\")" not in _js_fn_body(js, "openThread")
    mark = _js_fn_body(js, "markDmOpenedNow")
    assert "floor" in mark                        # clamped against sender clock skew
    _css_rule(css, ".navbadge")                   # style exists
    # the old double-fetch is gone: exactly ONE fetch site remains
    assert js.count('j("/api/conversations")') == 1


def test_sticky_journal_header():
    # Sticky nav + chips/composer (live-test follow-up). The load-bearing
    # detail: .app must be overflow:clip, NOT hidden - a hidden ancestor
    # creates a scroll container and silently kills position:sticky
    # against the page scroll.
    css = (WEB / "style.css").read_text(encoding="utf-8")
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    app_rule = _css_rule(css, ".app")
    assert "overflow: clip" in app_rule
    assert "overflow: hidden" not in app_rule
    nav_rule = _css_rule(css, ".appnav")
    assert "position: sticky" in nav_rule
    assert "--chrome-h" in nav_rule               # desktop titlebar offset
    assert 'id="journal-sticky"' in html
    assert ".journal-sticky" in css
    assert "--nav-h" in js and "offsetHeight" in js   # measured, not hardcoded


def test_collage_canvas_wired():
    # Slice A pin engine: 4-col canvas with measured square-ish cells,
    # pinned blocks at explicit coordinates, unplaced flow below (the tray
    # died with dynamic placement, spec 2026-07-14), legacy size-*/grid-*
    # rendering retired.
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert 'id="profile-wall-flow"' in html
    assert 'id="profile-tray"' not in html
    wall_rule = _css_rule(css, "#profile-wall")
    assert "repeat(4, 1fr)" in wall_rule
    assert "var(--cell" in wall_rule
    assert "measureWallCell" in js and "clientWidth" in js
    rw = _js_fn_body(js, "renderWall")
    assert "pin" in rw
    # Migration (spec 2026-07-14): own profile, any unplaced block ->
    # one /api/wall-autoplace call, re-render only when placed > 0.
    assert "/api/wall-autoplace" in rw
    assert "placed" in rw
    # The trigger is silent (not click-initiated), so the resolution must
    # check the user is still on this profile's view before re-rendering -
    # openProfile would otherwise setView("profile") and yank a
    # navigated-away user back.
    assert "currentView()" in rw
    rb = _js_fn_body(js, "renderBlock")
    assert "gridColumn" in rb and "gridRow" in rb
    assert "size-full" not in js          # Phase-A width classes retired
    assert "existence-disclosure" in js or "opaque ids" in js  # honesty note


def test_drag_to_pin_wired():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    drag = _js_fn_body(js, "startBlockDrag")
    assert "cellFromPoint" in drag and "pin-ghost" in drag
    assert "/api/block-pin" in drag
    assert "insertBefore" not in drag        # reorder semantics are gone
    # Dynamic placement (spec 2026-07-14): the tray/unpin drop zone and the
    # client's overlap veto are both retired - a drop pushes server-side
    # instead of being refused, and dragging off-canvas is a snap-back
    # no-op (no more /api/block-unpin from a drag gesture).
    assert "tray-target" not in drag
    assert "/api/block-unpin" not in drag
    assert "pinFree" not in drag
    assert "block-resize" in js              # corner handle exists
    _css_rule(css, ".pin-ghost")
    assert ".pin-ghost.invalid" in css
    done = _js_fn_body(js, "toggleArrange") if "function toggleArrange" in js \
        else _js_fn_body(js, "renderProfilePage")
    assert '"/api/profile-layout"' not in done   # Done no longer posts order


def test_tray_retired():
    # Dynamic placement (spec 2026-07-14): the Unplaced tray - its markup,
    # CSS, and every JS branch (renderWall's tray/flow split, the drag
    # gesture's tray hit-test, the modal's Send to tray) - is gone
    # entirely. Blocks are always on the canvas: unplaced ones flow below
    # until a drag places them or /api/wall-autoplace migrates them.
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert "profile-tray" not in html
    assert "profile-tray" not in js
    assert "profile-tray" not in css
    assert "tray-target" not in css
    assert "Send to tray" not in js
    assert "Place on canvas" not in js
    # The client's overlap veto (pinFree/WALL_PINS) is retired along with
    # its only remaining caller (firstFreeSpot, "Place on canvas"'s
    # keyboard path) - the server pushes on collision instead.
    assert "pinFree" not in js
    assert "WALL_PINS" not in js
    assert "firstFreeSpot" not in js


def test_nudge_and_preset_push_aware():
    # Dynamic placement (spec 2026-07-14): nudge buttons disable only at
    # canvas edges (no more overlap veto); the size preset keeps its
    # x-clamp but its "No room" alert is gone - both just POST
    # /api/block-pin and let the server push whatever's in the way.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    body = _js_fn_body(js, "openBlockSettings")
    assert "No room" not in body
    assert "Math.min(p.pin.x, 4 - w)" in body    # x-clamp survives
    nudge_block = body[body.index('el("div", "settings-label", "Move")'):]
    assert "g.x < 0 || g.x + g.w > 4 || g.y < 0" in nudge_block


def test_block_settings_modal_collage_groups():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    body = _js_fn_body(js, "openBlockSettings")
    for needle in ("/api/block-span", "/api/block-pin"):
        assert needle in body, needle
    assert "previousElementSibling" not in body   # Up/Down reorder retired
    # Dynamic placement (spec 2026-07-14): Send to tray / Place on canvas
    # are retired (blocks are always on the canvas now); firstFreeSpot lost
    # its only caller with Place on canvas and is gone too.
    assert "Send to tray" not in body
    assert "Place on canvas" not in body
    assert "/api/block-unpin" not in body
    assert "firstFreeSpot" not in js
    assert "pinFree" not in js
    assert "WALL_PINS" not in js


def test_composer_preview_wired():
    # Collage Slice B: the wall composer previews attached media (photo /
    # stacked deck / video first-frame) sized by chips at true canvas
    # proportions; the dead Auto dropdown and the last /api/block-grid
    # client caller are gone. Live behavior pinned by
    # tests/test_ui_smoke_composer.py (UI_E2E=1).
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    body = _js_fn_body(js, "profilePostComposer")
    for needle in ("compose-preview", "size-chips", "createObjectURL",
                   "revokeObjectURL", "preview-deck",
                   "deck-count", "aria-pressed"):
        assert needle in body, needle
    assert '"2x2"' in body                     # media default chip
    assert "layout-pick" not in js             # dropdown fully retired
    assert "Masonry" not in js and "cols3" not in js
    assert "/api/block-grid" not in js         # zero client callers left
    # Dynamic placement (spec 2026-07-14): the size chips now ride w/h
    # fields straight on /api/post - the separate span-seed call is gone.
    assert 'fd.append("w"' in body
    assert 'fd.append("h"' in body
    assert "/api/block-span" not in body
    _css_rule(css, ".compose-preview")
    assert ".preview-deck" in css and ".deck-count" in css
    assert "layout-pick" not in css


def test_wall_deck_wired():
    # Slice C: any multi-photo block - album or plain post - is a
    # swipeable stacked deck; the transitional cropped gallery is gone.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert "photoGridClass" not in js
    assert "block-gallery" not in js and "block-gallery" not in css
    rb = _js_fn_body(js, "renderBlock")
    assert "blockPhotoItems" in rb and "block-deck" in rb
    items = _js_fn_body(js, "blockPhotoItems")
    assert "photos" in items and "blobs" in items
    lb = _js_fn_body(js, "openLightbox")
    assert "items[i].m" in lb or "items[i].h" in lb
    # a mouse swipe's trailing click must not also open the lightbox
    # (pointerup precedes click - review-traced fix)
    rd = _js_fn_body(js, "renderDeck")
    assert "swiped" in rd
    # Sketch 2026-07-18: the arrow pills + "n/N" badge left the wall deck.
    # Invisible tap zones (real buttons - keyboard path) flip photos;
    # bottom-center dots track position. The composer preview's own
    # .deck-count photo-count badge stays - only the wall usage is gone.
    assert "deck-tap-prev" in rd and "deck-tap-next" in rd
    assert "deck-dots" in rd and "deck-count" not in rd
    assert "deck-nav" not in js
    assert ".deck-tap" in css and ".deck-dot" in css
    assert ".deck-nav" not in css and ".block-deck .deck-count" not in css
    # both flavors of deck chrome step aside in Arrange: zones would eat
    # the drag surface (buttons bail out of the block's pointerdown), and
    # the dots' bottom-center spot belongs to the "+" pill there.
    assert ".block.arranging .deck-tap" in css
    assert ".block.arranging .deck-dots" in css
    deck_rule = _css_rule(css, ".block-deck")
    assert "z-index: 0" in deck_rule       # the Slice-B stacking lesson
    assert ".block-deck::before" in css
    # Task 7's bug: .block's cell-crop overflow:hidden clipped the deck's
    # peek-out edges. Fix: a deterministic has-deck class opts the block
    # out of that clip (no :has() dependency).
    assert "has-deck" in _js_fn_body(js, "renderBlock")
    assert ".block.has-deck" in css


def test_album_owner_controls_wired():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    body = _js_fn_body(js, "addPhotosToBlock")
    assert '"/api/album"' in body and "scope_newest" in body
    rb = _js_fn_body(js, "renderBlock")
    assert "block-add" in rb
    assert "p.album" in rb                       # album blocks skip the del button
    modal = _js_fn_body(js, "openBlockSettings")
    assert "Ungroup" in modal


def test_text_styling_wired():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    rb = _js_fn_body(js, "renderBlock")
    assert "text_style" in rb and "applyTextStyle" in rb
    ats = _js_fn_body(js, "applyTextStyle")
    for needle in ("justify-content", "align-items", "--text-color",
                   "text-font-disp", "text-size-", "text-bold",
                   "text-italic"):
        assert needle in ats or needle in css, needle
    modal = _js_fn_body(js, "openBlockSettings")
    assert '"/api/block-text"' in modal and "TEXT_COLORS" in modal
    assert ".block-text-wrap" in css
    # final-review Fix 1: the wrap is capped the same way as its body, or a
    # captioned deck's wrap escapes has-deck's overflow:visible as an
    # invisible click-stealer below the card (review-probed via
    # elementFromPoint).
    assert ".block.has-deck .block-text-wrap" in css
    # final-review Fix 2: belt-and-braces default so an unstyled body can't
    # flex-stretch a clipped line past the clamp's ellipsis if applyTextStyle
    # is ever skipped. Anchored to line-start (not _css_rule's plain substring
    # search) so it targets the base .block-text-wrap rule itself, not the
    # ".block.has-deck .block-text-wrap" override rule that also contains
    # ".block-text-wrap" as a substring and sits earlier in the file.
    wrap_rule = re.search(r"(?m)^\.block-text-wrap\s*\{([^}]*)\}", css)
    assert wrap_rule, "no base .block-text-wrap rule found"
    assert "align-items: flex-start" in wrap_rule.group(1)


def test_onboarding_poll_never_gives_up():
    # Launch loading states (0.3.11): the bootstrap->full-app handoff can
    # legitimately take minutes (cold Tor bootstrap), so pollForFullApp
    # must poll forever with backoff - the old 40-try cap ended in a
    # dead-end message telling the user to "leave this page open" while
    # nothing would ever arrive.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "You can leave this page open" not in js       # the dead-end lie
    body = _js_fn_body(js, "pollForFullApp")
    assert "while (true)" in body                          # polls forever
    assert "get_startup_status" in body       # desktop bridge stage surfaced
    assert "Connecting to Tor" in body
    assert "pywebview?.api?.get_startup_status?." in body  # optional-chained:
    # a plain browser (dev) has no bridge and must keep the text fallback
    assert "tor-waiting" in body
    assert "Waiting for a previous Kreds to finish closing..." in body


def test_composer_note_reflects_wall_wrap_grants():
    # "A wall is a wall" (0.3.11): the blanket "reveals only future
    # posts" claim is now wrong for the kreds wall -- the note must
    # scope the future-only rule to Inner and say kreds wall posts are
    # for current friends. DRAFT copy pinned here; August owns final
    # wording (update the pin together with the string).
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "Moving someone into a ring reveals only future posts." not in js
    assert "Inner posts reach only your Inner kreds" in js


def test_banner_crop_control_wired():
    # Banner crop (spec 2026-07-15): drag the editor preview up/down (or
    # use the paired range slider - the keyboard path); the profile
    # banner renders background-position from banner_pos.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    editor = _js_fn_body(js, "profileEditor")
    assert "banner_pos" in editor and "banner-crop" in editor
    assert 'type = "range"' in editor or 'type="range"' in editor
    assert "pointerdown" in editor                   # drag path
    assert "pointercancel" in editor                 # aborted gesture tears down tracking
    assert "revokeObjectURL" in editor               # picked-banner blob URL lifecycle
    page = _js_fn_body(js, "renderProfilePage")
    assert "backgroundPosition" in page and "banner_pos" in page
    rule = _css_rule(css, ".banner-crop-preview")
    assert "cover" in rule and "ns-resize" in rule


def test_delete_everywhere_rehomed_into_block_settings():
    # Spec 2026-07-15: the always-visible per-block delete button leaves
    # the wall face; the settings modal owns it now. Journal entries keep
    # their inline delete; albums keep the ungroup-first rule.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    rb = _js_fn_body(js, "renderBlock")
    assert "Delete everywhere" not in rb
    assert "block-settings-btn" in rb              # gear (Arrange-only since 2026-07-18)
    obs = _js_fn_body(js, "openBlockSettings")
    assert "Delete everywhere" in obs and "deleteEverywhere" in obs
    # Review fix: the doomed gear is dropped as opener BEFORE the close so
    # focus-restore can't re-arm a stale modal during the refresh round-trip.
    assert "BLOCK_SETTINGS_OPENER = null" in obs
    assert "Delete everywhere" in _js_fn_body(js, "buildEntry")


def test_block_chrome_arrange_only():
    # Sketch 2026-07-18: outside Arrange an own wall block is clean
    # viewing chrome - the gear and the "+" exist only inside the
    # ARRANGING && p.mine branch, and the 2026-07-15 hover-reveal CSS
    # went with the always-present gear. (Supersedes the "gear on every
    # own block outside Arrange" decision; the delete path survives via
    # Arrange's tap-to-open and the Arrange-mode gear, both reaching the
    # settings modal.)
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    rb = _js_fn_body(js, "renderBlock")
    arranging_idx = rb.index("if (ARRANGING && p.mine)")
    assert rb.index('el("button", "block-settings-btn"') > arranging_idx
    assert rb.index('el("label", "block-add"') > arranging_idx
    assert ".block:hover .block-settings-btn" not in css   # hover-reveal gone
    assert "opacity: 0" not in _css_rule(css, ".block-settings-btn")
    # Review fix: bare .settings-del ties with the later .settings-opt color
    # rule and loses the cascade - the compound selector wins on specificity
    # (same precedent as .pact.del).
    assert ".settings-opt.settings-del" in css


def test_scope_tag_small_ringless_bottom_right():
    # Spec 2026-07-15: the Inner/Kreds badge sheds its pill ring, shrinks,
    # and moves to the block's bottom-right (freed by the delete button's
    # departure). Arrange hides it - the resize handle owns that corner.
    css = (WEB / "style.css").read_text(encoding="utf-8")
    base = _css_rule(css, ".block-scope")          # base rule must come FIRST in the file
    assert "9px" in base and "border" not in base and "padding" not in base
    pos = _css_rule(css, ".block .block-scope")
    assert "bottom" in pos and "right" in pos
    assert "top" not in pos and "left" not in pos
    hide = _css_rule(css, ".block.arranging .block-scope")
    assert "display: none" in hide


def test_settings_view_markup_and_rehomed_panels():
    # Spec 2026-07-15: the self-only side panels move to a Settings page
    # (cog opens it); the profile right column keeps ONLY the journal
    # rail, so own and friends' profiles finally align.
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert 'id="view-settings"' in html
    for sec in ("sec-editprofile", "sec-friends", "sec-devices",
                "sec-applock", "sec-desktop", "sec-updates"):
        assert f'id="{sec}"' in html
    settings = html.split('id="view-settings"')[1].split('id="idstrip')[0]
    for inner in ("settings-editprofile", 'id="friends"', 'id="ceremony"',
                  'id="devices"', "applock-settings", "desktop-settings",
                  "update-settings"):
        assert inner in settings
    assert "desktop-only-panel" in settings          # browser never sees Desktop
    side = html.split('id="profile-side"')[1].split("</aside>")[0]
    assert "journal-rail" in side
    assert "applock-settings" not in side and 'id="friends"' not in side
    assert 'id="profile-edit-overlay"' not in html   # overlay is dead
    assert "manage in Settings" in html


def test_entry_avatar_renders_profile_picture_with_ring_fallback():
    # Profile polish batch (0.3.13): buildEntry shows the author's
    # uploaded avatar inside the circle; the identity ring stays ON TOP
    # of photos and the colored letter circle stays the fallback
    # (August, 2026-07-15).
    js = (WEB / "app.js").read_text(encoding="utf-8")
    body = _js_fn_body(js, "buildEntry")
    assert "author_avatar" in body
    assert '"/api/blob/" + p.author_avatar' in body
    # missing blob (row gossiped before the avatar) falls back to the
    # letter circle instead of an empty ring
    assert "im.onerror" in body
    css = (WEB / "style.css").read_text(encoding="utf-8")
    # button.eavatar img: specificity (0,1,2) outranks .entry img (0,1,1)
    # so the avatar sizing never depends on rule ORDER (final review)
    rule = _css_rule(css, "button.eavatar img")
    assert "object-fit: cover" in rule and "border-radius: 50%" in rule


def test_topbar_addfriend_spacing_and_rail_padding():
    css = (WEB / "style.css").read_text(encoding="utf-8")
    # + button carries the same 8px rhythm Arrange already has, so it no
    # longer sits flush against the cog (August, 2026-07-15)
    assert "margin-right: 8px" in _css_rule(css, ".profile-addfriend")
    # the rail scroll box pads inward so .eavatar::after's -4px identity
    # ring isn't clipped at the container edge
    assert "padding: 6px" in _css_rule(css, ".profile-side #profile-journal-rail")


def test_settings_page_wiring_and_collapse_memory():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    body = _js_fn_body(js, "openSettings")
    assert "settings-editprofile" in body and "profileEditor" in body
    assert "renderMeStrip" in body and 'setView("settings")' in body
    assert "kreds_settings_open_" in js              # collapse state remembered
    assert "openProfileEditor" not in js and "closeEditOverlay" not in js
    # Settings > Friends is the friend-list home now - currentView() must
    # report "settings" so a profile opened from there has PRIOR_VIEW set
    # correctly and Back returns to Settings, not Journal.
    assert '"settings"' in _js_fn_body(js, "currentView")


def test_topbar_addfriend_popover():
    # Spec 2026-07-15: a small self-only "+" next to Arrange/cog opens the
    # add-friend flow as a dialog - no trip to Settings to add someone.
    html = (WEB / "index.html").read_text(encoding="utf-8")
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert 'id="profile-addfriend"' in html
    assert 'id="friendadd-overlay"' in html and 'id="friendadd-close"' in html
    assert "buildFriendAdd" in _js_fn_body(js, "openFriendAdd")
    assert "profile-addfriend" in _js_fn_body(js, "renderProfilePage")
    assert "position: fixed" in _css_rule(css, "#friendadd-overlay")
    assert "closeFriendAdd" in js
    # Review fixes (block-settings parity): focus returns to the opener on
    # close, Tab is trapped inside the card while the dialog is open, and
    # the share tab's countdown interval self-clears once its node is
    # detached (popover closed / panel rebuilt mid-countdown).
    assert "FRIENDADD_OPENER" in js
    assert 'document.getElementById("friendadd-overlay").addEventListener("keydown"' in js
    assert "isConnected" in _js_fn_body(js, "buildShareTab")


# ---------------------------------------------------------------------
# Fullscreen fill (Task 1, 0.3.13): the chat shell derives its height
# from the viewport instead of the old min(640px, 70vh) cap, and the
# app box caps at 1720px (fixed cap, NOT 100vw - ultrawides would
# stretch the nav away from content).
# ---------------------------------------------------------------------

def test_chat_fills_viewport_and_shell_widens():
    css = (WEB / "style.css").read_text(encoding="utf-8")
    shell = _css_rule(css, ".dm-shell")
    assert "min(640px" not in shell and "70vh" not in shell
    assert "100vh" in shell and "max(420px" in shell
    assert "max-width: 1720px" in _css_rule(css, ".app")


def test_journal_keeps_readable_column_and_idstrip_stays_on_screen():
    # Review follow-up (0.3.13, owner decision): ONLY Messages consumes the
    # widened 1720px shell. The journal view keeps its pre-widen readable
    # column - #view-journal (the container wrapping chipbar + #journal +
    # circle rail) caps at the old 1220 app cap's inner width and centers,
    # so .entry rows render at their pre-change ~952px (measured at a 2400px
    # viewport). And the chat height offset folds in the identity strip so
    # the always-visible fingerprint footer is never pushed below the fold.
    css = (WEB / "style.css").read_text(encoding="utf-8")
    view = _css_rule(css, "#view-journal")
    assert "max-width: 1218px" in view
    assert "margin: 0 auto" in view
    # the chat offset accounts for the idstrip (144px total, not the
    # strip-blind 106px)
    shell = _css_rule(css, ".dm-shell")
    # the offset subtracts the desktop titlebar via --chrome-h (0 in a
    # browser) - measured 144px browser-side, +40px chrome in the shell
    assert "calc(100vh - 144px - var(--chrome-h, 0px))" in shell


# ---------------------------------------------------------------------
# Corner resize grip (Task 2, 0.3.13, Josh): frameless window silently
# kills native drag-resize (resizable=True is inert with no OS frame),
# so the chrome rebuilds it - a bottom-right grip drives Api.resize_to.
# ---------------------------------------------------------------------

def test_resize_grip_desktop_only_and_wired():
    # Frameless regression rebuilt (Josh, 0.3.13): a chrome corner grip
    # drives Api.resize_to; a plain browser never shows it (the OS frame
    # already resizes there).
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert 'id="win-resize"' in html
    js = (WEB / "app.js").read_text(encoding="utf-8")
    assert "resize_to" in js
    body = _js_fn_body(js, "initResizeGrip")
    assert "requestAnimationFrame" in body        # bridge-flood throttle
    assert "setPointerCapture" in body
    # double-wire guard: wireDesktopChrome legitimately runs twice (boot
    # check + pywebviewready) - without this, pointer listeners stack
    assert 'grip.classList.contains("on")' in body
    # drop FLUSHES the last pending frame instead of discarding it, so the
    # window settles exactly where the pointer released
    assert "cancelAnimationFrame(raf); push();" in body
    css = (WEB / "style.css").read_text(encoding="utf-8")
    rule = _css_rule(css, "#win-resize")
    assert "nwse-resize" in rule
    assert "display: none" in rule                # hidden until JS reveals


# ---------------------------------------------------------------------
# In-app update banner + shared apply helper (Task 2, 0.3.15): a
# dismissible top banner renders from STATE.update_status (Task 1) and
# applies via the existing POST /api/update/apply - notify + one-click
# only, never automatic. The fetch+decision is a shared applyUpdateNow
# helper reused by both the banner and the existing Settings > Updates
# panel, so the reload/restart logic isn't duplicated.
# ---------------------------------------------------------------------

def test_update_banner_present_and_wired():
    # Auto-update nudge (0.3.15): a dismissible top banner renders from
    # STATE.update_status and applies via the existing endpoint.
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert 'id="update-banner"' in html
    js = (WEB / "app.js").read_text(encoding="utf-8")
    body = _js_fn_body(js, "renderUpdateBanner")
    assert "update_status" in body
    assert "Restart to update" in body        # core copy
    assert "Update now" in body               # web copy
    assert "renderUpdateBanner" in _js_fn_body(js, "refresh")   # called each refresh
    # shared apply helper reused by banner + settings (no duplicated restart logic)
    assert "applyUpdateNow" in _js_fn_body(js, "renderUpdateBanner")
    assert "applyUpdateNow" in _js_fn_body(js, "renderUpdateSettings")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    assert "nwse" not in _css_rule(css, "#update-banner")   # it's a bar, sanity
    assert _css_rule(css, "#update-banner")                 # rule exists


def test_update_banner_reenables_action_button_when_apply_does_not_navigate():
    # Whole-branch review, Finding 2 (rides along with Finding 1): the
    # banner's action button set act.disabled = true on click but was only
    # ever re-enabled on the NEXT refresh() rebuild -- so an apply that
    # fails (400, or a network error) left a permanently dead button until
    # the poller happened to re-render. Once the apply call returns without
    # actually navigating the page away (no web reload, no core restart),
    # the button must be re-enabled so the user can retry.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    banner = _js_fn_body(js, "renderUpdateBanner")
    assert "act.disabled = true" in banner
    assert "act.disabled = false" in banner


def test_video_editor_wired():
    # Spec 2026-07-18: trim+crop+cover editor - client simulates, the
    # node executes. Core contract pins.
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    ve = _js_fn_body(js, "openVideoEditor")
    assert "VE_MAX_WINDOW" in js and "const VE_MAX_WINDOW = 15" in js
    for needle in ("ve-stage", "ve-strip", "ve-handle", "ve-window",
                   "createObjectURL", "revokeObjectURL",
                   'action: "done"', 'action: "raw"', 'action: "cancel"'):
        assert needle in ve, needle
    # the trim loop wraps playback inside the window
    assert "timeupdate" in ve
    assert "#video-editor" in css and ".ve-handle" in css


def test_video_editor_crop_and_cover_wired():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    css = (WEB / "style.css").read_text(encoding="utf-8")
    ve = _js_fn_body(js, "openVideoEditor")
    for needle in ('"orig"', '"1:1"', '"9:16"', '"16:9"',
                   "ve-cover", "wheel", "ve-chip"):
        assert needle in ve, needle
    # pinch: a two-pointer distance path exists
    assert "pointers.size === 2" in ve or "pointers.length === 2" in ve
    assert ".ve-cover" in css and ".ve-chip" in css
    # cover drag tears down on pointercancel too (45455ab pattern), same as
    # both trim handles - the specific listener, not a bare count (the body
    # already had 4 pointercancel occurrences via dragHandle before this fix)
    assert 'cover.addEventListener("pointercancel"' in ve


def test_video_editor_composer_integration():
    js = (WEB / "app.js").read_text(encoding="utf-8")
    composer = _js_fn_body(js, "profilePostComposer")
    assert "openVideoEditor" in composer
    assert 'fd.append("video_edit"' in composer
    # the false promise is dead: the note now reflects the edit
    assert "will be trimmed to the story rules on post" not in js
    # story composer routes video picks through the editor too
    assert js.count("openVideoEditor(") >= 3      # def + 2 call sites
    # "coming soon" promise retired (the editor exists now)
    assert "In-app trimming is coming soon" not in js
