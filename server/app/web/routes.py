import collections
import pathlib

from fastapi import APIRouter, Form, HTTPException, Request
from fastapi.responses import RedirectResponse
from fastapi.templating import Jinja2Templates

from .. import db
from .. import weeks as weeklib
from ..repos import entries as entries_repo
from ..repos import workers as workers_repo

TEMPLATES_DIR = pathlib.Path(__file__).resolve().parents[1] / "templates"
templates = Jinja2Templates(directory=str(TEMPLATES_DIR))

web_router = APIRouter()


def require_admin(request: Request) -> None:
    if not request.session.get("admin"):
        raise HTTPException(status_code=303, headers={"Location": "/login"})


@web_router.get("/login")
def login_form(request: Request):
    return templates.TemplateResponse(request, "login.html", {"error": None})


@web_router.post("/login")
def login(request: Request, password: str = Form(...)):
    if password != request.app.state.settings.admin_password:
        return templates.TemplateResponse(request, "login.html",
                                          {"error": "Passwort falsch"})
    request.session["admin"] = True
    return RedirectResponse("/", status_code=303)


@web_router.get("/logout")
def logout(request: Request):
    request.session.clear()
    return RedirectResponse("/login", status_code=303)


@web_router.get("/")
def index(request: Request):
    require_admin(request)
    return RedirectResponse(f"/week/{weeklib.current_week_id()}", status_code=303)


WEEKDAY_NAMES = ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"]


def _grid_context(request: Request, week_id: str) -> dict:
    conn = request.app.state.db
    week = weeklib.get_or_create_week(conn, week_id)
    dates = weeklib.week_dates(week_id)
    all_workers = [w for w in workers_repo.list_workers(conn) if w["active"]]
    by_cell = collections.defaultdict(list)
    for entry in entries_repo.entries_for_week(conn, week_id):
        by_cell[entry["cell_id"]].append(entry)
    return {
        "week": week,
        "week_id": week_id,
        "dates": dates,
        "day_labels": [f"{WEEKDAY_NAMES[i]} {d.strftime('%d.%m.')}"
                       for i, d in enumerate(dates)],
        "monteure": [w for w in all_workers if w["category"] == "monteur"],
        "azubis": [w for w in all_workers if w["category"] == "azubi"],
        "by_cell": by_cell,
        "make_cell_id": weeklib.make_cell_id,
        "prev_week": weeklib.adjacent_week_id(week_id, -1),
        "next_week": weeklib.adjacent_week_id(week_id, 1),
        "current_week": weeklib.current_week_id(),
    }


@web_router.get("/week/{week_id}")
def week_page(week_id: str, request: Request):
    require_admin(request)
    try:
        context = _grid_context(request, week_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="unbekannte Woche")
    return templates.TemplateResponse(request, "week.html", context)


def _cell_context(request: Request, cell_id: str) -> dict:
    conn = request.app.state.db
    week_id, worker_id, _ = weeklib.parse_cell_id(cell_id)
    worker = workers_repo.get_worker(conn, worker_id)
    if worker is None:
        raise ValueError(worker_id)
    cell_entries = [e for e in entries_repo.entries_for_week(conn, week_id)
                    if e["cell_id"] == cell_id]
    return {"worker": worker, "cell_id": cell_id, "cell_entries": cell_entries,
            "monteure": [w for w in workers_repo.list_workers(conn)
                         if w["active"] and w["category"] == "monteur"]}


@web_router.get("/web/cells/{cell_id}")
def cell_fragment(cell_id: str, request: Request):
    require_admin(request)
    try:
        context = _cell_context(request, cell_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="unbekannte Zelle")
    return templates.TemplateResponse(request, "partials/cell.html", context)


def _render_cell(request: Request, cell_id: str, template="partials/cell.html",
                 error: str | None = None):
    context = _cell_context(request, cell_id)
    context["error"] = error
    return templates.TemplateResponse(request, template, context)


@web_router.get("/web/cells/{cell_id}/edit")
def cell_edit(cell_id: str, request: Request):
    require_admin(request)
    try:
        return _render_cell(request, cell_id, "partials/cell_edit.html")
    except ValueError:
        raise HTTPException(status_code=404, detail="unbekannte Zelle")


async def _broadcast_cell(request: Request, cell_id: str) -> None:
    await request.app.state.hub.broadcast({
        "event": "cell.updated", "cell_id": cell_id,
        "revision": db.latest_revision(request.app.state.db)})


@web_router.post("/web/cells/{cell_id}/entries")
async def web_create_entry(cell_id: str, request: Request,
                           text: str = Form(""), monteur: str = Form("")):
    require_admin(request)
    state = request.app.state
    value = text.strip() or monteur.strip()
    if not value:
        return _render_cell(request, cell_id, "partials/cell_edit.html",
                            error="Text fehlt")
    try:
        entries_repo.create_entry(state.db, cell_id, "text", {"text": value},
                                  "web", "web-admin", state.settings)
    except Exception as exc:
        return _render_cell(request, cell_id, "partials/cell_edit.html",
                            error=str(exc))
    await _broadcast_cell(request, cell_id)
    return _render_cell(request, cell_id)


@web_router.post("/web/entries/{entry_id}")
async def web_update_entry(entry_id: str, request: Request,
                           text: str = Form(...), base_revision: int = Form(...)):
    require_admin(request)
    state = request.app.state
    existing = entries_repo.get_entry(state.db, entry_id)
    if existing is None:
        raise HTTPException(status_code=404, detail="unbekannter Eintrag")
    try:
        entries_repo.update_entry(state.db, entry_id, {"text": text.strip()},
                                  base_revision, "web", "web-admin", state.settings)
    except entries_repo.ConflictError:
        return _render_cell(request, existing["cell_id"],
                            "partials/cell_edit.html",
                            error="Konflikt: Eintrag wurde parallel geändert")
    except Exception as exc:
        return _render_cell(request, existing["cell_id"],
                            "partials/cell_edit.html", error=str(exc))
    await _broadcast_cell(request, existing["cell_id"])
    return _render_cell(request, existing["cell_id"])


@web_router.post("/web/entries/{entry_id}/delete")
async def web_delete_entry(entry_id: str, request: Request):
    require_admin(request)
    state = request.app.state
    existing = entries_repo.get_entry(state.db, entry_id)
    if existing is None:
        raise HTTPException(status_code=404, detail="unbekannter Eintrag")
    try:
        entries_repo.delete_entry(state.db, entry_id, "web", "web-admin")
    except Exception as exc:
        return _render_cell(request, existing["cell_id"],
                            "partials/cell_edit.html", error=str(exc))
    await _broadcast_cell(request, existing["cell_id"])
    return _render_cell(request, existing["cell_id"])


@web_router.get("/workers")
def workers_page(request: Request, error: str | None = None):
    require_admin(request)
    all_workers = workers_repo.list_workers(request.app.state.db)
    return templates.TemplateResponse(request, "workers.html", {
        "monteure": [w for w in all_workers if w["category"] == "monteur"],
        "azubis": [w for w in all_workers if w["category"] == "azubi"],
        "error": error,
    })


async def _workers_redirect(request: Request, error: str | None = None):
    await request.app.state.hub.broadcast({
        "event": "workers.updated",
        "revision": db.latest_revision(request.app.state.db)})
    url = "/workers" if not error else f"/workers?error={error}"
    return RedirectResponse(url, status_code=303)


@web_router.post("/workers")
async def workers_create(request: Request, number: str = Form(...),
                         name: str = Form(...), category: str = Form(...)):
    require_admin(request)
    settings = request.app.state.settings
    try:
        workers_repo.create_worker(request.app.state.db, number.strip(),
                                   name.strip(), category,
                                   max_monteure=settings.max_monteure,
                                   max_azubis=settings.max_azubis)
    except workers_repo.LimitExceeded as exc:
        return await _workers_redirect(request, str(exc))
    return await _workers_redirect(request)


@web_router.post("/workers/{worker_id}")
async def workers_update(worker_id: str, request: Request,
                         number: str = Form(...), name: str = Form(...),
                         position: int = Form(...), active: str = Form(None)):
    require_admin(request)
    settings = request.app.state.settings
    try:
        workers_repo.update_worker(request.app.state.db, worker_id,
                                   number=number.strip(), name=name.strip(),
                                   position=position, active=active == "1",
                                   max_monteure=settings.max_monteure,
                                   max_azubis=settings.max_azubis)
    except workers_repo.LimitExceeded as exc:
        return await _workers_redirect(request, str(exc))
    except KeyError:
        raise HTTPException(status_code=404, detail="unbekannter Worker")
    return await _workers_redirect(request)
