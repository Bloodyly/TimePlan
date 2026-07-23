import collections
import pathlib
from datetime import date
from urllib.parse import quote

from fastapi import APIRouter, Form, HTTPException, Request
from fastapi.responses import RedirectResponse
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel

from .. import db
from .. import weeks as weeklib
from ..repos import app_settings as app_settings_repo
from ..repos import entries as entries_repo
from ..repos import workers as workers_repo

TEMPLATES_DIR = pathlib.Path(__file__).resolve().parents[1] / "templates"
templates = Jinja2Templates(directory=str(TEMPLATES_DIR))


def _drawing_viewbox(content: dict, padding_fraction: float = 0.1) -> str:
    points = [p for stroke in content.get("strokes", []) for p in stroke.get("points", [])]
    if not points:
        width = content.get("canvas_width", 1)
        height = content.get("canvas_height", 1)
        return f"0 0 {width} {height}"
    xs = [p["x"] for p in points]
    ys = [p["y"] for p in points]
    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)
    width = max(max_x - min_x, 1)
    height = max(max_y - min_y, 1)
    pad_x = width * padding_fraction
    pad_y = height * padding_fraction
    return f"{min_x - pad_x} {min_y - pad_y} {width + 2 * pad_x} {height + 2 * pad_y}"


templates.env.filters["drawing_viewbox"] = _drawing_viewbox

web_router = APIRouter()

# Single source of truth for the fixed Azubi status vocabulary (label -> CSS
# chip modifier). Azubi cells accept exactly one of these labels or an active
# monteur's "{number} {name}", never free text - enforced in web_create_entry.
AZUBI_STATUS_CLASS = {"Schule": "school", "Krank": "sick", "Urlaub": "vacation"}

DRAG_FILL_TEXT_ARROW = "→"
DRAG_FILL_ASSIGNMENT_ARROW = "----->"


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
    dates = weeklib.visible_week_dates(conn, week_id)
    all_workers = [w for w in workers_repo.list_workers(conn) if w["active"]]
    by_cell = collections.defaultdict(list)
    for entry in entries_repo.entries_for_week(conn, week_id):
        if entry["conflict_of"] is None:
            by_cell[entry["cell_id"]].append(entry)
    return {
        "week": week,
        "week_id": week_id,
        "dates": dates,
        "day_labels": [f"{WEEKDAY_NAMES[d.weekday()]} {d.strftime('%d.%m.')}"
                       for d in dates],
        "monteure": [w for w in all_workers if w["category"] == "monteur"],
        "azubis": [w for w in all_workers if w["category"] == "azubi"],
        "by_cell": by_cell,
        "make_cell_id": weeklib.make_cell_id,
        "prev_week": weeklib.adjacent_week_id(week_id, -1),
        "next_week": weeklib.adjacent_week_id(week_id, 1),
        "current_week": weeklib.current_week_id(),
        "azubi_status_class": AZUBI_STATUS_CLASS,
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
    cell_entries = _existing_cell_entries(conn, week_id, cell_id)
    existing_text = next(
        (e["content"]["text"] for e in cell_entries if e["type"] == "text"), "")
    return {"worker": worker, "cell_id": cell_id, "cell_entries": cell_entries,
            "existing_text": existing_text,
            "monteure": [w for w in workers_repo.list_workers(conn)
                         if w["active"] and w["category"] == "monteur"],
            "azubi_status_class": AZUBI_STATUS_CLASS}


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


def _existing_cell_entries(conn, week_id: str, cell_id: str) -> list[dict]:
    return [e for e in entries_repo.entries_for_week(conn, week_id)
            if e["cell_id"] == cell_id and e["conflict_of"] is None]


@web_router.post("/web/cells/{cell_id}/entries")
async def web_create_entry(cell_id: str, request: Request, text: str = Form("")):
    require_admin(request)
    state = request.app.state
    conn = state.db
    value = text.strip()

    try:
        week_id, worker_id, _ = weeklib.parse_cell_id(cell_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="unbekannte Zelle")
    worker = workers_repo.get_worker(conn, worker_id)

    if worker is not None and worker["category"] == "azubi":
        allowed = set(AZUBI_STATUS_CLASS) | {
            f"{m['number']} {m['name']}" for m in workers_repo.list_workers(conn)
            if m["category"] == "monteur" and m["active"]
        }
        if value and value not in allowed:
            return _render_cell(request, cell_id, "partials/cell_edit.html",
                                error="Ungültige Auswahl")
        existing = _existing_cell_entries(conn, week_id, cell_id)
        try:
            if not value:
                for e in existing:
                    entries_repo.delete_entry(conn, e["id"], "web", "web-admin")
            elif existing:
                entries_repo.update_entry(conn, existing[0]["id"], {"text": value},
                                          existing[0]["revision"], "web", "web-admin",
                                          state.settings)
                for e in existing[1:]:
                    entries_repo.delete_entry(conn, e["id"], "web", "web-admin")
            else:
                entries_repo.create_entry(conn, cell_id, "text", {"text": value},
                                          "web", "web-admin", state.settings)
        except entries_repo.ConflictError:
            return _render_cell(request, cell_id, "partials/cell_edit.html",
                                error="Konflikt: Eintrag wurde parallel geändert")
        except entries_repo.WeekLocked:
            return _render_cell(request, cell_id, "partials/cell_edit.html",
                                error="Woche ist gesperrt")
        except Exception as exc:
            return _render_cell(request, cell_id, "partials/cell_edit.html",
                                error=str(exc))
        await _broadcast_cell(request, cell_id)
        response = _render_cell(request, cell_id)
        response.headers["HX-Retarget"] = f"#cell-{cell_id}"
        response.headers["HX-Reswap"] = "outerHTML"
        return response

    existing = [e for e in _existing_cell_entries(conn, week_id, cell_id) if e["type"] == "text"]
    try:
        if not value:
            for e in existing:
                entries_repo.delete_entry(conn, e["id"], "web", "web-admin")
        elif existing:
            entries_repo.update_entry(conn, existing[0]["id"], {"text": value},
                                      existing[0]["revision"], "web", "web-admin",
                                      state.settings)
            for e in existing[1:]:
                entries_repo.delete_entry(conn, e["id"], "web", "web-admin")
        else:
            entries_repo.create_entry(conn, cell_id, "text", {"text": value},
                                      "web", "web-admin", state.settings)
    except entries_repo.ConflictError:
        response = _render_cell(request, cell_id, "partials/cell_edit.html",
                                error="Konflikt: Eintrag wurde parallel geändert")
        response.headers["HX-Retarget"] = f"#cell-{cell_id}"
        response.headers["HX-Reswap"] = "outerHTML"
        return response
    except entries_repo.WeekLocked:
        response = _render_cell(request, cell_id, "partials/cell_edit.html",
                                error="Woche ist gesperrt")
        response.headers["HX-Retarget"] = f"#cell-{cell_id}"
        response.headers["HX-Reswap"] = "outerHTML"
        return response
    except Exception as exc:
        response = _render_cell(request, cell_id, "partials/cell_edit.html", error=str(exc))
        response.headers["HX-Retarget"] = f"#cell-{cell_id}"
        response.headers["HX-Reswap"] = "outerHTML"
        return response
    await _broadcast_cell(request, cell_id)
    return _render_cell(request, cell_id)


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


class FillCellsIn(BaseModel):
    target_cell_ids: list[str]


@web_router.post("/web/cells/{origin_cell_id}/fill")
async def web_fill_cells(origin_cell_id: str, payload: FillCellsIn, request: Request):
    require_admin(request)
    state = request.app.state
    conn = state.db

    try:
        origin_week_id, origin_worker_id, origin_date_iso = weeklib.parse_cell_id(origin_cell_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="unbekannte Ursprungszelle")

    origin_worker = workers_repo.get_worker(conn, origin_worker_id)
    if origin_worker is None:
        raise HTTPException(status_code=404, detail="unbekannter Mitarbeiter")

    origin_entries = [e for e in _existing_cell_entries(conn, origin_week_id, origin_cell_id)
                      if e["type"] == "text"]
    if not origin_entries:
        raise HTTPException(status_code=422, detail="Ursprungszelle ist leer")
    origin_text = origin_entries[0]["content"]["text"]
    origin_date = date.fromisoformat(origin_date_iso)

    if origin_worker["category"] == "azubi" and origin_text in AZUBI_STATUS_CLASS:
        fill_text = origin_text
    elif origin_worker["category"] == "azubi":
        fill_text = DRAG_FILL_ASSIGNMENT_ARROW
    else:
        fill_text = DRAG_FILL_TEXT_ARROW

    filled: list[str] = []
    for target_cell_id in payload.target_cell_ids:
        try:
            target_week_id, target_worker_id, target_date_iso = weeklib.parse_cell_id(target_cell_id)
        except ValueError:
            continue
        if target_week_id != origin_week_id or target_worker_id != origin_worker_id:
            continue
        if date.fromisoformat(target_date_iso) <= origin_date:
            continue
        if _existing_cell_entries(conn, target_week_id, target_cell_id):
            continue
        try:
            entries_repo.create_entry(conn, target_cell_id, "text", {"text": fill_text},
                                      "web", "web-admin", state.settings)
        except (entries_repo.ConflictError, entries_repo.WeekLocked):
            continue
        filled.append(target_cell_id)

    for cell_id in filled:
        await _broadcast_cell(request, cell_id)

    return {"filled": filled}


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
    if error is None:
        await request.app.state.hub.broadcast({
            "event": "workers.updated",
            "revision": db.latest_revision(request.app.state.db)})
        return RedirectResponse("/workers", status_code=303)
    return RedirectResponse(f"/workers?error={quote(error)}", status_code=303)


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


@web_router.get("/settings")
def settings_page(request: Request):
    require_admin(request)
    settings = app_settings_repo.get_display_settings(request.app.state.db)
    return templates.TemplateResponse(request, "settings.html", {"settings": settings})


@web_router.post("/settings")
async def settings_update(request: Request, show_saturday: str = Form(None),
                          show_sunday: str = Form(None)):
    require_admin(request)
    app_settings_repo.update_display_settings(
        request.app.state.db, show_saturday == "1", show_sunday == "1")
    await request.app.state.hub.broadcast({
        "event": "settings.updated",
        "revision": db.latest_revision(request.app.state.db)})
    return RedirectResponse("/settings", status_code=303)
