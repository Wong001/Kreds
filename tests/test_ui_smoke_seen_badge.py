"""UI_E2E=1-gated live browser smoke for the 0.3.10 batch: seen-state
dot clearing and the unread DM badge, driven through the real web client
in headless Chromium against TWO real nodes syncing over real sockets.

Why a live smoke and not just the static asserts in test_web_assets.py:
the week of 2026-07-10's only two critical UI bugs were caught by live
smokes, never by static text asserts (ROADMAP, "permanent behavioral
test suite"). This file is written as a permanent gated test - the
promotion candidate that note asks for.

Harness: Playwright's sync API owns the main thread, so each node's
asyncio stack (SyncService + uvicorn/FastAPI app) runs on a loop in a
background thread; syncs are triggered explicitly via
run_coroutine_threadsafe (no gossip loop - deterministic).

Not covered here (covered elsewhere): the profile-visit clearing path
(static assert in test_web_assets.py + August's checklist - driving it
here would race the journal observer's own 700ms dwell on the same
post); sticky-scroll feel (August's checklist - it's a feel, not a DOM
predicate).
"""
import asyncio
import os
import socket
import threading

import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("UI_E2E") != "1",
    reason="live browser smoke; set UI_E2E=1 (needs playwright chromium)")

import uvicorn

from hearth.api import build_app
from hearth.node import HearthNode
from hearth.sync import SyncService


def _free_port() -> int:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class LiveNode:
    """SyncService + HTTP app on a background-thread asyncio loop."""

    def __init__(self, dir_, name, device):
        self.node = HearthNode.create(dir_, name, device)
        # A brand-new node starts onboarding_done=false (hearth/bootstrap.py),
        # which makes the client show the one-time onboarding wizard on top
        # of #app (see app.js boot(): NEEDS_WIZARD hides #app until the
        # wizard finishes). This smoke drives the app directly, not the
        # wizard, so mark onboarding done up front - same harness precedent
        # as tests/test_bootstrap.py's "a paired device is already set up".
        self.node.store.set_meta("onboarding_done", "1")
        self.loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self.loop.run_forever, daemon=True)
        self.thread.start()

    def start(self):
        async def _start():
            self.sync = SyncService(self.node)
            gport = await self.sync.start("127.0.0.1", 0)
            self.node.store.set_meta("gossip_addr", f"127.0.0.1:{gport}")
            self.gossip_addr = f"127.0.0.1:{gport}"
            self.http_port = _free_port()
            cfg = uvicorn.Config(build_app(self.node), host="127.0.0.1",
                                 port=self.http_port, log_level="warning")
            self.server = uvicorn.Server(cfg)
            self.serve_task = asyncio.ensure_future(self.server.serve())
        self._run(_start(), timeout=20)

    def sync_with(self, other):
        self._run(self.sync.sync_with(other.gossip_addr), timeout=30)

    def stop(self):
        # Must survive a partially-failed start() (reviewer finding: an
        # AttributeError here would leak the OTHER node's thread/socket
        # via the shared finally).
        async def _stop():
            server = getattr(self, "server", None)
            if server is not None:
                server.should_exit = True
            sync = getattr(self, "sync", None)
            if sync is not None:
                await sync.stop()
        try:
            self._run(_stop(), timeout=10)
        finally:
            self.loop.call_soon_threadsafe(self.loop.stop)
            self.thread.join(timeout=5)

    def _run(self, coro, timeout):
        return asyncio.run_coroutine_threadsafe(coro, self.loop).result(timeout)


def befriend(a: LiveNode, b: LiveNode):
    a.node.store.add_identity(b.node.identity_pub)
    b.node.store.add_identity(a.node.identity_pub)
    a.node.ensure_enckey()
    b.node.ensure_enckey()


def test_seen_dot_and_dm_badge_live(tmp_path):
    from playwright.sync_api import sync_playwright

    a = LiveNode(tmp_path / "a", "Anna", "anna-pc")
    b = LiveNode(tmp_path / "b", "Bo", "bo-pc")
    try:
        befriend(a, b)
        a.start()
        b.start()
        a.sync_with(b)   # exchange enckeys/profiles

        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            page = browser.new_page(viewport={"width": 1280, "height": 900})
            errors = []
            page.on("pageerror", lambda e: errors.append(str(e)))
            page.goto(f"http://127.0.0.1:{a.http_port}/")
            page.wait_for_selector(".fchip")   # app booted, chipbar rendered

            # -- 1: B posts; Anna's chip grows a fresh dot ---------------
            b.node.compose_post("hej fra Bo", scope="kreds")
            b.sync_with(a)
            # WS "changed" triggers refresh(); the dot appears...
            page.wait_for_selector(".fchip .fdot", timeout=10000)
            # ...and the post is at the top of the feed, on screen: after
            # the 700ms dwell + 200ms debounce it clears WITHOUT reload.
            page.wait_for_selector(".fchip .fdot", state="detached",
                                   timeout=5000)

            # -- 2: dot honesty while the journal is hidden --------------
            # Switch to Messages (journal hidden -> observer can't mark),
            # B posts again: the dot must appear AND persist.
            page.click("#nav-messages")
            b.node.compose_post("nummer to", scope="kreds")
            b.sync_with(a)
            page.wait_for_selector(".fchip .fdot", state="attached",
                                   timeout=10000)
            page.wait_for_timeout(1500)   # > dwell+debounce
            assert page.locator(".fchip .fdot").count() == 1, \
                "dot must NOT clear while the journal is hidden"

            # -- 3: unread DM badge appears ------------------------------
            b.node.compose_dm(a.node.identity_pub, "privat hej")
            b.sync_with(a)
            page.wait_for_selector("#nav-msg-badge:not(.hidden)",
                                   timeout=10000)
            assert page.locator("#nav-msg-badge").inner_text() == "1"
            assert page.locator(".conv.unread").count() == 1

            # -- 4: opening the thread clears the badge ------------------
            page.click(".conv.unread")
            # state="attached": .hidden is `display:none !important` in
            # this app's CSS (hearth/web/style.css), so the badge can never
            # satisfy Playwright's default state="visible" once it carries
            # that class - same reasoning as the state="attached" wait a
            # few lines up for the dot persisting behind the hidden
            # journal. Confirmed via a live run: the element DOES pick up
            # class="navbadge hidden" right on schedule; only the
            # wait's default state was wrong, not the feature.
            page.wait_for_selector("#nav-msg-badge.hidden", state="attached",
                                   timeout=5000)
            assert page.locator(".conv.unread").count() == 0

            # -- 5: replying keeps it clear (and the reply round-trips) --
            page.fill("#dm-text", "hej selv")
            page.click("#dm-compose button[type=submit]")
            page.wait_for_timeout(1000)
            assert page.locator("#nav-msg-badge.hidden").count() == 1

            # -- 6: back on the journal, post 2 now on screen -> clears --
            page.click("#nav-journal")
            page.wait_for_selector(".fchip .fdot", state="detached",
                                   timeout=5000)

            assert not errors, f"console pageerrors: {errors}"
            browser.close()
    finally:
        try:
            a.stop()
        finally:
            b.stop()
