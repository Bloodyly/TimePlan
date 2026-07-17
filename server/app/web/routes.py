import collections
import pathlib

from fastapi import APIRouter, Form, HTTPException, Request
from fastapi.responses import RedirectResponse
from fastapi.templating import Jinja2Templates

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
