import pathlib

from fastapi import APIRouter, Form, HTTPException, Request
from fastapi.responses import RedirectResponse
from fastapi.templating import Jinja2Templates

from .. import weeks as weeklib

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
