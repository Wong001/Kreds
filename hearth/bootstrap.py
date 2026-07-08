"""Node-less first-run server: serves the web client + create/pair endpoints
until an identity is enrolled, then run_serve hands off to the full node app."""
from pathlib import Path
from fastapi import FastAPI, Body, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from .api import WEB_DIR
from .node import HearthNode

def build_bootstrap_app(data_dir, on_ready, web_dir=None) -> FastAPI:
    data_dir = Path(data_dir)
    wd = web_dir or WEB_DIR
    app = FastAPI(title="Hearth bootstrap")
    app.mount("/static", StaticFiles(directory=wd), name="static")

    # Whole-branch review, MINOR #3: on_ready() tells run_serve to flip
    # server.should_exit and hand off to the full node app, but uvicorn
    # takes a beat (observed ~0.1-0.5s) to actually stop accepting
    # requests. A second create/pair-request/pair-install landing in that
    # gap would overwrite the just-enrolled keys.json. One flag, set
    # alongside the first successful on_ready(), checked at the top of
    # every mutation handler.
    done = False

    @app.get("/")
    async def index():
        return FileResponse(wd / "index.html")

    @app.get("/api/bootstrap")
    async def status():
        return {"initialized": False}

    @app.post("/api/bootstrap/create")
    async def create(body: dict = Body(...)):
        nonlocal done
        if done:
            raise HTTPException(409, "bootstrap already completed")
        name = (body.get("name") or "").strip()
        device = (body.get("device") or "this-device").strip() or "this-device"
        if not name:
            raise HTTPException(400, "name required")
        node = HearthNode.create(data_dir, name, device)
        node.close()                     # release hearth.db before run_node re-opens it
        done = True
        on_ready()
        return {"ok": True}

    @app.post("/api/bootstrap/pair-request")
    async def pair_request(body: dict = Body(...)):
        if done:
            raise HTTPException(409, "bootstrap already completed")
        device = (body.get("device") or "this-device").strip() or "this-device"
        return {"request": HearthNode.pair_request(data_dir, device)}

    @app.post("/api/bootstrap/pair-install")
    async def pair_install(body: dict = Body(...)):
        nonlocal done
        if done:
            raise HTTPException(409, "bootstrap already completed")
        package = body.get("package") or ""
        try:
            node = HearthNode.pair_install(data_dir, package)
        except Exception as e:
            raise HTTPException(400, str(e))
        node.store.set_meta("onboarding_done", "1")   # a paired device is already set up
        node.close()
        done = True
        on_ready()
        return {"ok": True}

    return app
