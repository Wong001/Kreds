"""Localhost HTTP + WebSocket API for one Hearth node."""
from __future__ import annotations

import asyncio
import io
import time
import zipfile
from pathlib import Path
from typing import List, Optional

import qrcode
from fastapi import (Body, FastAPI, File, HTTPException, Form, Request,
                     UploadFile, WebSocket, WebSocketDisconnect)
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles

from . import applock, invitecodec, update
from .messages import (_is_hex_color, AVATAR_ALIGNS, AVATAR_SHAPES,
                       AVATAR_SIZES, MAX_BIO, MAX_BLOB_BYTES,
                       MAX_VIDEO_UPLOAD)
from .node import HearthNode

WEB_DIR = Path(__file__).parent / "web"

_MAGIC = [(b"\x89PNG", "image/png"), (b"\xff\xd8", "image/jpeg"),
          (b"GIF8", "image/gif"), (b"RIFF", "image/webp")]

# Locked-guard allowlist (Kreds App-lock): exact /api/* paths reachable
# while node.locked -- everything else under /api/* is 423 until unlock.
# Exact-path, not a prefix match: /api/applock/settings etc. are NOT
# allowlisted just because they start with "/api/applock". Deliberately
# does NOT include /api/activity: a locked node has no "activity" to
# extend, and the escalating throttle on /api/unlock is the only thing
# that should work while locked.
_APPLOCK_ALLOWLIST = {"/api/unlock", "/api/applock", "/api/bootstrap",
                      "/api/settings"}   # close_behavior (non-secret): the desktop
# titlebar's close handler must read it while locked, so a locked "keep running"
# node isn't silently quit on close (whole-branch review).


def _sniff(data: bytes) -> str:
    for magic, mime in _MAGIC:
        if data.startswith(magic):
            return mime
    if data[4:8] == b"ftyp":
        return "video/mp4"
    return "application/octet-stream"


def _installed_web_version(wd: Path) -> str:
    """The version of the web assets currently being served (the VERSION file
    inside the served web dir). Falls back to the core version if absent."""
    try:
        return (wd / "VERSION").read_text().strip()
    except OSError:
        return update.CORE_VERSION


def build_app(node: HearthNode, web_dir: Path | None = None) -> FastAPI:
    wd = web_dir or WEB_DIR
    app = FastAPI(title="Hearth node")
    app.mount("/static", StaticFiles(directory=wd), name="static")

    # Serializes /api/update/apply so two concurrent calls can't both pass
    # the "is there an update" check and race apply_web's .web-new/.web-bak
    # swap or stage_core's pending-core.zip write at once (whole-branch
    # review, IMPORTANT #3). apply_web/stage_core are synchronous, blocking
    # I/O called from an async def -- fine now that _fetch is timeout-
    # bounded, but moving them to a threadpool is a Phase-2b refinement.
    update_lock = asyncio.Lock()

    node.maintain_enckey()

    @app.middleware("http")
    async def revoked_gate(request: Request, call_next):
        path = request.url.path
        if node.revoked and path.startswith("/api/") and path != "/api/state":
            return JSONResponse(status_code=410,
                                content={"detail": "device revoked"})
        return await call_next(request)

    @app.middleware("http")
    async def locked_gate(request: Request, call_next):
        """423 for every /api/* route while node.locked, except the
        allowlist (unlock + status) and non-/api/ static assets. Keyed on
        request.url.path so no individual route needed to change.

        Does NOT touch last_activity. The earlier approach touched here on
        every allowed request except a denylist of tagged/background
        paths -- but background WS-driven refresh() fetches and media
        <img>/<video> GETs (/api/blob/, /api/post-blob/, /api/dm-blob/)
        can't be tagged or excluded that way, so an abandoned tab with an
        active friend graph never idled out (whole-branch review,
        IMPORTANT #5, redone). last_activity is now touched ONLY by
        POST /api/activity below -- an explicit, genuine-input signal from
        the client, never inferred from traffic the app generates on its
        own."""
        path = request.url.path
        # /api/settings is allowlisted for the GET only (the close handler must
        # read close_behavior while locked); a POST while locked -- e.g. someone
        # flipping keep->quit at a locked machine -- is still gated like any
        # other write (whole-branch review, MINOR #2).
        if node.locked and path.startswith("/api/") and (
                path not in _APPLOCK_ALLOWLIST
                or (path == "/api/settings" and request.method != "GET")):
            return JSONResponse(status_code=423, content={"detail": "locked"})
        return await call_next(request)

    @app.middleware("http")
    async def shell_no_cache(request: Request, call_next):
        """The web shell (index / app.js / style.css / sw.js) is hot-swapped
        on disk by the updater. With no cache directive the webview applies
        HEURISTIC caching and serves the old shell without ever revalidating
        -> an applied web update isn't shown (the exact 0.3.1 bug). no-cache =
        "always revalidate": the ETag makes it a cheap 304 when unchanged, and
        a full 200 the moment a swap changes the file, so updates show at once."""
        resp = await call_next(request)
        path = request.url.path
        if path == "/" or path == "/sw.js" or path.startswith("/static/"):
            resp.headers["Cache-Control"] = "no-cache"
        return resp

    def _400(fn, *args):
        try:
            return fn(*args)
        except (ValueError, KeyError, TypeError) as e:
            raise HTTPException(400, str(e))

    @app.get("/")
    async def index():
        return FileResponse(wd / "index.html")

    @app.get("/sw.js")
    async def service_worker():
        # Served at the app ROOT, not /static/sw.js: a worker registered
        # from under /static/ can only ever control /static/*. Root scope
        # lets it control the whole app (see app.js's registration call).
        return FileResponse(wd / "sw.js",
                            media_type="application/javascript")

    # -- Bootstrap status (mirrors hearth.bootstrap's node-less endpoint so
    # the web client can probe /api/bootstrap the same way regardless of
    # which phase of `hearth serve` it happens to be talking to) ----------

    @app.get("/api/bootstrap")
    async def bootstrap_status():
        return {"initialized": True,
                "onboarding_done": node.store.get_meta("onboarding_done") == "1"}

    @app.post("/api/onboarding-done")
    async def onboarding_done():
        node.store.set_meta("onboarding_done", "1")
        return {"ok": True}

    # -- Desktop app settings (Kreds Windows shell, Task 3) --------------
    # Currently a single value: what closing the frameless window does.
    # "quit" (stop the node + exit, the default) or "keep" (minimize to
    # the taskbar; the node keeps syncing). Set at onboarding and
    # changeable later in Settings -- see hearth/desktop.py's Api.quit/
    # minimize and app.js's titlebar close handler, which reads this on
    # every close (not cached) so a Settings change takes effect without
    # a reload.

    @app.get("/api/settings")
    async def get_settings():
        return {"close_behavior": node.store.get_meta("close_behavior") or "quit"}

    @app.post("/api/settings")
    async def set_settings(body: dict = Body(...)):
        cb = body.get("close_behavior")
        if cb not in ("quit", "keep"):
            raise HTTPException(400, "bad close_behavior")
        node.store.set_meta("close_behavior", cb)
        return {"ok": True}

    # -- Signed in-app updates (Kreds auto-update, Task 3) ---------------
    # update.check() never raises to ITS caller, but the try/except here is
    # belt-and-braces against a future regression there -- either way a
    # broken feed must read as "no update", never a 500 in the client.
    # apply targets `wd` (the SAME dir build_app just resolved to serve),
    # not WEB_DIR, so a hot-swap actually lands in the directory this app
    # is serving from (dev/source mode: the repo web dir; packaged mode:
    # the writable copy build_app was pointed at).

    @app.get("/api/update/check")
    async def update_check():
        try:
            # Run the blocking fetch/verify off the event loop so a slow or
            # hung feed can't freeze the whole node (whole-branch review #3).
            info = await asyncio.to_thread(update.check, web_dir=wd)
        except Exception:
            return {"available": False, "error": "check failed",
                    "current": update.CORE_VERSION,
                    "web_version": _installed_web_version(wd)}
        web_ver = _installed_web_version(wd)
        if not info:
            return {"available": False, "current": update.CORE_VERSION,
                    "web_version": web_ver}
        return {"available": True, "current": update.CORE_VERSION,
                "web_version": web_ver,
                "version": info["version"], "notes": info.get("notes", ""),
                "web": info["web_available"], "core": info["core_available"]}

    @app.post("/api/update/apply")
    async def update_apply():
        # The lock serializes concurrent applies within this one event loop;
        # to_thread keeps the blocking fetch/swap off the loop so the lock can
        # actually do that (and a slow feed can't freeze the node). The second
        # of two racing applies re-checks INSIDE the lock, sees the just-bumped
        # web VERSION, and gets "no update" -- no double-swap (review #2/#3).
        async with update_lock:
            info = await asyncio.to_thread(update.check, web_dir=wd)
            if not info:
                raise HTTPException(400, "no update available")
            try:
                # Core takes precedence: a combined release (new core + web,
                # where the web bundle needs that new core) must stage the
                # core instead of dead-ending in apply_web's min-core gate.
                # A web update that needs a newer core is only ever offered
                # as a core/restart update; a web-only update hot-applies
                # (whole-branch review, IMPORTANT #2).
                if info["core_available"]:
                    await asyncio.to_thread(
                        update.stage_core, info, node.data_dir / "update-staging")
                    return {"staged": "core", "restart_required": True}
                elif info["web_available"]:
                    await asyncio.to_thread(update.apply_web, info, wd)
                    return {"applied": "web", "reload": True}
                else:
                    raise HTTPException(400, "no update available")
            except (update.BadUpdate, KeyError, TypeError, OSError,
                    ValueError, zipfile.BadZipFile) as e:
                raise HTTPException(400, str(e))

    # -- App-lock (Kreds security slice) --------------------------------

    @app.get("/api/applock")
    async def get_applock_status():
        status = node.applock_status()
        status["throttle_wait"] = node.throttle_wait()
        return status

    @app.post("/api/unlock")
    async def applock_unlock(body: dict = Body(...)):
        now = time.time()
        wait = node.throttle_wait(now)
        if wait > 0:
            return JSONResponse(status_code=401, content={
                "detail": "throttled", "throttle_wait": wait})
        try:
            node.unlock(body["credential"])
        except applock.BadCredential:
            node._throttle_fail(now)
            return JSONResponse(status_code=401, content={
                "detail": "bad credential",
                "throttle_wait": node.throttle_wait()})
        except (RuntimeError, KeyError) as e:
            raise HTTPException(400, str(e))
        node._throttle_reset()
        return {"ok": True}

    @app.post("/api/lock")
    async def applock_lock():
        try:
            node.lock()
        except RuntimeError as e:
            raise HTTPException(400, str(e))
        return {"ok": True}

    @app.post("/api/activity")
    async def activity():
        """The ONLY route that touches last_activity (whole-branch review,
        IMPORTANT #5, redone) -- an explicit "the user did something real"
        signal from app.js's startActivityPing(), which only calls this
        when genuine pointerdown/keydown input has been seen since the
        last ping. Deliberately not in _APPLOCK_ALLOWLIST: a locked node
        has no activity to extend, so this 423s while locked, same as any
        other content route -- app.js ignores that 423 (fire-and-forget).
        A normal, allowed route on a non-applock node too: harmless there,
        since last_activity is simply never read (maybe_autolock is a
        no-op unless applock_enabled)."""
        node._touch()
        return {"ok": True}

    @app.post("/api/applock/setup")
    async def applock_setup(body: dict = Body(...)):
        try:
            if node.applock_enabled:
                raise ValueError("app-lock already enabled")
            node.enable_applock(body["credential"], body.get("cred_type", "pin"))
        except (RuntimeError, ValueError, KeyError) as e:
            raise HTTPException(400, str(e))
        return {"ok": True}

    @app.post("/api/applock/settings")
    async def applock_settings(body: dict = Body(...)):
        try:
            node.update_applock_settings(int(body["idle_minutes"]),
                                         bool(body["lock_on_sleep"]))
        except (RuntimeError, ValueError, KeyError, TypeError) as e:
            raise HTTPException(400, str(e))
        return {"ok": True}

    @app.post("/api/applock/change")
    async def applock_change(body: dict = Body(...)):
        try:
            node.change_applock_credential(body["old"], body["new"])
        except applock.BadCredential:
            raise HTTPException(401, "bad credential")
        except (RuntimeError, ValueError, KeyError) as e:
            raise HTTPException(400, str(e))
        return {"ok": True}

    @app.post("/api/applock/disable")
    async def applock_disable(body: dict = Body(...)):
        try:
            node.disable_applock(body["credential"])
        except applock.BadCredential:
            raise HTTPException(401, "bad credential")
        except (RuntimeError, ValueError, KeyError) as e:
            raise HTTPException(400, str(e))
        return {"ok": True}

    @app.get("/api/state")
    async def state():
        names = node.store.profiles()
        return {
            "identity_pub": node.identity_pub,
            "device_pub": node.device.device_pub,
            "device_name": node.device.name,
            "profile_name": names.get(node.identity_pub, ""),
            "devices": node.devices(),
            "friends": [{"identity_pub": i, "name": names.get(i, i[:8])}
                        for i in node.store.known_identities()
                        if i != node.identity_pub],
            "peers": node.store.list_peers(),
            "disconnected": node.store.list_disconnected(),
            "revoked": node.revoked,
            "accent": (node.store.profile(node.identity_pub) or {}).get(
                "accent", "#2743d6"),
        }

    @app.get("/api/feed")
    async def feed():
        return node.feed()

    @app.post("/api/post")
    async def post(text: str = Form(""), scope: str = Form("kreds"),
                   expires_seconds: str = Form(""),
                   placement: str = Form("journal"),
                   photos: List[UploadFile] = File(default=[]),
                   video: UploadFile = File(default=None),
                   w: Optional[int] = Form(default=None),
                   h: Optional[int] = Form(default=None),
                   place: str = Form("1")):
        # place="0" skips the profile auto-place (spec 2026-07-14): the
        # deck grow flow's album-bound photo must not disturb the wall.
        expiry = float(expires_seconds) if expires_seconds.strip() else None
        auto_place = place != "0"
        if video is not None:
            vbytes = await video.read()
            if len(vbytes) > MAX_VIDEO_UPLOAD:
                raise HTTPException(413, "video exceeds upload cap")
            mid = _400(lambda: node.compose_post(text, scope, (), expiry,
                                                 placement=placement, video=vbytes,
                                                 span_w=w, span_h=h,
                                                 auto_place=auto_place))
            return {"msg_id": mid}
        blobs = []
        for up in photos:
            data = await up.read()
            if len(data) > MAX_BLOB_BYTES:
                raise HTTPException(413, "photo exceeds 5 MB cap")
            blobs.append(data)
        mid = _400(lambda: node.compose_post(text, scope, blobs, expiry,
                                             placement=placement,
                                             span_w=w, span_h=h,
                                             auto_place=auto_place))
        return {"msg_id": mid}

    @app.post("/api/ring")
    async def ring(body: dict = Body(...)):
        _400(lambda: node.set_ring(body["identity_pub"], body["ring"]))
        return {"ok": True}

    @app.post("/api/profile-layout")
    async def profile_layout(body: dict = Body(...)):
        _400(lambda: node.set_profile_layout(body["order"]))
        return {"ok": True}

    @app.post("/api/block-grid")
    async def block_grid(body: dict = Body(...)):
        _400(lambda: node.set_block_grid(body["msg_id"], body["grid"]))
        return {"ok": True}

    @app.post("/api/block-size")
    async def block_size(body: dict = Body(...)):
        _400(lambda: node.set_block_size(body["msg_id"], body["size"]))
        return {"ok": True}

    @app.post("/api/block-pin")
    async def block_pin(body: dict = Body(...)):
        _400(lambda: node.set_block_pin(body["msg_id"], body["x"],
                                        body["y"], body["w"], body["h"]))
        return {"ok": True}

    @app.post("/api/block-unpin")
    async def block_unpin(body: dict = Body(...)):
        # wire-compat: no UI caller since dynamic placement retired the tray.
        _400(lambda: node.unpin_block(body["msg_id"]))
        return {"ok": True}

    @app.post("/api/block-span")
    async def block_span(body: dict = Body(...)):
        _400(lambda: node.set_block_span(body["msg_id"], body["w"], body["h"]))
        return {"ok": True}

    @app.post("/api/block-text")
    async def block_text(body: dict = Body(...)):
        _400(lambda: node.set_block_text(
            body["msg_id"],
            **{k: v for k, v in body.items() if k != "msg_id"}))
        return {"ok": True}

    @app.post("/api/album")
    async def album(body: dict = Body(...)):
        aid = _400(lambda: node.set_album(body["members"],
                                          body.get("album_id")))
        return {"ok": True, "album_id": aid}

    @app.post("/api/wall-autoplace")
    async def wall_autoplace():
        """Migration (spec 2026-07-14): a client calls this once on
        opening its OWN profile if any own wall block is unplaced. A
        single layout write pins every one of them at the top, newest on
        top, push rule applied."""
        return {"ok": True, "placed": _400(lambda: node.auto_place_unplaced())}

    @app.post("/api/unfriend")
    async def unfriend(body: dict = Body(...)):
        _400(lambda: node.unfriend(body["identity_pub"]))
        return {"ok": True}

    @app.get("/api/post-blob/{msg_id}/{h}")
    async def post_blob(msg_id: str, h: str):
        data = node.post_blob(msg_id, h)
        if data is None:
            raise HTTPException(404, "not found")
        return Response(content=data, media_type=_sniff(data),
                        headers={"X-Content-Type-Options": "nosniff"})

    @app.post("/api/delete")
    async def delete(body: dict = Body(...)):
        _400(lambda: node.delete_post(body["msg_id"]))
        return {"ok": True}

    @app.get("/api/profile/{identity_pub}")
    async def get_profile(identity_pub: str):
        view = node.profile_view(identity_pub)
        if view is None:
            raise HTTPException(404, "no such profile")
        return view

    @app.post("/api/profile")
    async def profile(name: str = Form(...), bio: str = Form(""),
                      accent: str = Form("#2743d6"),
                      avatar_shape: str = Form("circle"),
                      avatar_size: str = Form("m"),
                      avatar_align: str = Form("left"),
                      avatar: UploadFile = File(default=None),
                      banner: UploadFile = File(default=None)):
        if not _is_hex_color(accent):
            raise HTTPException(400, "bad accent color")
        if (avatar_shape not in AVATAR_SHAPES
                or avatar_size not in AVATAR_SIZES
                or avatar_align not in AVATAR_ALIGNS):
            raise HTTPException(400, "bad avatar option")
        if len(bio) > MAX_BIO:
            raise HTTPException(400, "bio too long")
        av_bytes = bn_bytes = None
        for up, setter in ((avatar, "av"), (banner, "bn")):
            if up is not None:
                data = await up.read()
                if len(data) > MAX_BLOB_BYTES:
                    raise HTTPException(413, "image exceeds 5 MB cap")
                if setter == "av":
                    av_bytes = data
                else:
                    bn_bytes = data
        _400(lambda: node.set_profile(
            name, bio=bio, accent=accent, avatar_bytes=av_bytes,
            avatar_shape=avatar_shape, avatar_size=avatar_size,
            avatar_align=avatar_align, banner_bytes=bn_bytes))
        return {"ok": True}

    @app.post("/api/device/revoke")
    async def revoke(body: dict = Body(...)):
        result = _400(lambda: node.revoke_device(body["device_pub"]))
        return {"ok": result.accepted, "retro_dropped": result.retro_dropped}

    @app.get("/api/blob/{h}")
    async def blob(h: str):
        data = node.store.get_blob(h)
        if data is None:
            raise HTTPException(404, "unknown blob")
        return Response(content=data, media_type=_sniff(data),
                        headers={"X-Content-Type-Options": "nosniff"})

    @app.post("/api/friend/invite")
    async def friend_invite():
        payload = node.create_invite()
        # create_invite now returns a compact base58 code (spec
        # 2026-07-10-compact-invite), not JSON -- decode it to read back
        # the expiry the client's countdown UI needs.
        _, d = invitecodec.decode(payload)
        # fp: this node's own 4-char fingerprint, so the Share tab can show
        # a short "kreds.invite.<FP>...<code tail>" chip instead of the raw
        # ~80-char code (task 3, compact-invite web display).
        return {"payload": payload, "expires_at": d["expiry"],
                "fp": invitecodec.fingerprint(node.identity_pub)}

    @app.post("/api/friend/respond")
    async def friend_respond(body: dict = Body(...)):
        return {"payload": _400(lambda: node.respond_to_invite(
            body["payload"]))}

    @app.post("/api/friend/finalize")
    async def friend_finalize(body: dict = Body(...)):
        return {"payload": _400(lambda: node.finalize_invite(
            body["payload"]))}

    @app.post("/api/friend/complete")
    async def friend_complete(body: dict = Body(...)):
        _400(lambda: node.complete_invite(body["payload"]))
        return {"ok": True}

    # Easier friend-add (Task 3): B pastes A's invite code, this tries to
    # auto-connect over Tor via node.add_friend_via_invite (Task 2) and
    # falls back to {"status": "manual", "response": ...} when A is
    # unreachable -- the client then walks that response through the SAME
    # /api/friend/finalize + /api/friend/complete pair above, by hand.
    @app.post("/api/friend/add")
    async def friend_add(body: dict = Body(...)):
        try:
            result = await node.add_friend_via_invite(body["payload"])
            # fp: the PEER's 4-char fingerprint, derived from the pasted
            # invite's own id_prefix (not the connected friend's full
            # identity_pub, which the "manual" branch doesn't have yet) --
            # the Enter tab shows "Connecting to someone whose ID starts
            # with <fp>" so the human check stays meaningful either way.
            _, inv = invitecodec.decode(body["payload"])
            result["fp"] = invitecodec.fp_from_prefix(inv["id_prefix"])
            return result
        except (ValueError, KeyError, TypeError) as e:
            raise HTTPException(400, str(e))

    @app.post("/api/pair/accept")
    async def pair_accept(body: dict = Body(...)):
        return {"payload": _400(lambda: node.accept_pairing(
            body["payload"]))}

    @app.get("/api/conversations")
    async def conversations():
        return node.conversations()

    @app.get("/api/dm/{identity_pub}")
    async def dm_thread(identity_pub: str):
        return node.dm_thread(identity_pub)

    @app.post("/api/dm")
    async def dm(to: str = Form(...), text: str = Form(""),
                 expires_seconds: str = Form(""),
                 photos: List[UploadFile] = File(default=[])):
        blobs = []
        for up in photos:
            data = await up.read()
            if len(data) > MAX_BLOB_BYTES:
                raise HTTPException(413, "photo exceeds 5 MB cap")
            blobs.append(data)
        expiry = float(expires_seconds) if expires_seconds.strip() else None
        mid = _400(lambda: node.compose_dm(to, text, blobs, expiry))
        return {"msg_id": mid}

    @app.get("/api/dm-blob/{msg_id}/{h}")
    async def dm_blob(msg_id: str, h: str):
        data = node.dm_blob(msg_id, h)
        if data is None:
            raise HTTPException(404, "unavailable")
        return Response(content=data, media_type=_sniff(data),
                        headers={"X-Content-Type-Options": "nosniff"})

    @app.get("/api/qr")
    async def qr(text: str):
        img = qrcode.make(text)
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        return Response(content=buf.getvalue(), media_type="image/png")

    @app.get("/api/stories")
    async def stories():
        return node.stories_view()

    @app.post("/api/story")
    async def story(media: UploadFile = File(...), caption: str = Form("")):
        data = await media.read()
        if len(data) > MAX_BLOB_BYTES:
            raise HTTPException(413, "media exceeds 5 MB cap")
        mid = _400(lambda: node.compose_story(data, caption))
        return {"msg_id": mid}

    @app.get("/api/kreds")
    async def kreds():
        return node.kreds_list()

    @app.websocket("/ws")
    async def ws(sock: WebSocket):
        await sock.accept()
        q: asyncio.Queue = asyncio.Queue()
        node.subscribers.add(q)
        try:
            while True:
                await sock.send_text(await q.get())
        except WebSocketDisconnect:
            pass
        finally:
            node.subscribers.discard(q)

    return app
