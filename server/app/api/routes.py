import secrets

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel

from .. import db
from .. import weeks as weeklib
from ..repos import entries as entries_repo
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


@router.get("/weeks/{week_id}")
def get_week(week_id: str, request: Request):
    require_device(request)
    conn = request.app.state.db
    try:
        week = weeklib.get_or_create_week(conn, week_id)
        dates = [d.isoformat() for d in weeklib.week_dates(week_id)]
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc))
    return {"week": week, "dates": dates,
            "entries": entries_repo.entries_for_week(conn, week_id)}


class EntryIn(BaseModel):
    cell_id: str
    type: str
    content: dict


class EntryUpdateIn(BaseModel):
    content: dict
    base_revision: int


def _map_entry_error(exc: Exception) -> HTTPException:
    if isinstance(exc, entries_repo.NotFound):
        return HTTPException(status_code=404, detail="entry not found")
    if isinstance(exc, entries_repo.PayloadTooLarge):
        return HTTPException(status_code=413, detail=str(exc))
    if isinstance(exc, entries_repo.WeekLocked):
        return HTTPException(status_code=423, detail=f"week locked: {exc}")
    if isinstance(exc, entries_repo.ConflictError):
        return HTTPException(status_code=409, detail={
            "error": "revision_conflict",
            "conflict_entry_id": exc.conflict_entry["id"],
            "current_revision": exc.current_revision,
        })
    return HTTPException(status_code=422, detail=str(exc))


@router.post("/entries", status_code=201)
async def create_entry(payload: EntryIn, request: Request):
    device_id = require_device(request)
    state = request.app.state
    try:
        entry = entries_repo.create_entry(
            state.db, payload.cell_id, payload.type, payload.content,
            "tablet", device_id, state.settings)
    except Exception as exc:
        raise _map_entry_error(exc)
    return {"entry": entry}


@router.put("/entries/{entry_id}")
async def update_entry(entry_id: str, payload: EntryUpdateIn, request: Request):
    device_id = require_device(request)
    state = request.app.state
    try:
        entry = entries_repo.update_entry(
            state.db, entry_id, payload.content, payload.base_revision,
            "tablet", device_id, state.settings)
    except Exception as exc:
        raise _map_entry_error(exc)
    return {"entry": entry}


@router.delete("/entries/{entry_id}", status_code=204)
async def delete_entry(entry_id: str, request: Request):
    device_id = require_device(request)
    try:
        entries_repo.delete_entry(request.app.state.db, entry_id, "tablet", device_id)
    except Exception as exc:
        raise _map_entry_error(exc)


@router.get("/sync")
def sync(request: Request, since: int = 0):
    require_device(request)
    conn = request.app.state.db
    rows = conn.execute(
        "SELECT MAX(revision) AS revision, entity_type, entity_id,"
        " (SELECT action FROM changelog c2 WHERE c2.entity_type=c1.entity_type"
        "   AND c2.entity_id=c1.entity_id ORDER BY revision DESC LIMIT 1) AS action"
        " FROM changelog c1 WHERE revision > ?"
        " GROUP BY entity_type, entity_id ORDER BY revision",
        (since,),
    ).fetchall()
    return {
        "latest_revision": db.latest_revision(conn),
        "changes": [dict(r) for r in rows],
    }
