from fastapi import FastAPI, Request

from .config import load_settings


def create_app() -> FastAPI:
    settings = load_settings()
    app = FastAPI(title="TimePlan")
    app.state.settings = settings

    @app.get("/api/v1/status")
    def status(request: Request):
        return {"status": "ok", "revision": 0}

    return app
