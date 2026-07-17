import secrets

from fastapi import APIRouter, HTTPException, Request

from .. import db
from ..repos import workers

router = APIRouter(prefix="/api/v1")


def require_device(request: Request) -> str:
    auth = request.headers.get("authorization", "")
    scheme, _, token = auth.partition(" ")
    if scheme.lower() == "bearer":
        for device_id, expected in request.app.state.settings.device_tokens.items():
            if secrets.compare_digest(token, expected):
                conn = request.app.state.db
                with db.tx(conn):
                    conn.execute(
                        "INSERT INTO devices(id, name, last_seen)"
                        " VALUES(?,?,datetime('now')) ON CONFLICT(id) DO UPDATE"
                        " SET last_seen=datetime('now')",
                        (device_id, device_id),
                    )
                return device_id
    raise HTTPException(status_code=401, detail="invalid device token")


@router.get("/workers")
def get_workers(request: Request):
    require_device(request)
    return {"workers": workers.list_workers(request.app.state.db)}
