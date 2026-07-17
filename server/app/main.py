import os
import pathlib
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from starlette.middleware.sessions import SessionMiddleware
from starlette.staticfiles import StaticFiles

from . import db
from .config import load_settings


def create_app() -> FastAPI:
    settings = load_settings()
    if settings.db_path != ":memory:":
        os.makedirs(os.path.dirname(os.path.abspath(settings.db_path)), exist_ok=True)
    conn = db.connect(settings.db_path)
    db.init_db(conn)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        yield
        conn.close()

    app = FastAPI(title="TimePlan", lifespan=lifespan)
    app.state.settings = settings
    app.state.db = conn

    from .api.routes import router as api_router
    app.include_router(api_router)

    from .ws import Hub, ws_router
    app.state.hub = Hub()
    app.include_router(ws_router)

    from .web.routes import web_router
    app.include_router(web_router)
    app.add_middleware(SessionMiddleware, secret_key=settings.secret_key)
    static_dir = pathlib.Path(__file__).resolve().parent / "static"
    app.mount("/static", StaticFiles(directory=str(static_dir)), name="static")

    @app.get("/api/v1/status")
    def status(request: Request):
        return {"status": "ok", "revision": db.latest_revision(request.app.state.db)}

    return app
