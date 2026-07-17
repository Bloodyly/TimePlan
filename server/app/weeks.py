import datetime
import re

from . import db

WEEK_RE = re.compile(r"^(\d{4})-W(\d{2})$")


def parse_week_id(week_id: str) -> tuple[int, int]:
    m = WEEK_RE.match(week_id)
    if not m:
        raise ValueError(f"invalid week id: {week_id}")
    year, week = int(m.group(1)), int(m.group(2))
    datetime.date.fromisocalendar(year, week, 1)  # wirft ValueError bei W00/W60 …
    return year, week


def week_dates(week_id: str) -> list[datetime.date]:
    year, week = parse_week_id(week_id)
    return [datetime.date.fromisocalendar(year, week, d) for d in range(1, 8)]


def current_week_id(today: datetime.date | None = None) -> str:
    today = today or datetime.date.today()
    iso = today.isocalendar()
    return f"{iso[0]}-W{iso[1]:02d}"


def adjacent_week_id(week_id: str, delta: int) -> str:
    monday = week_dates(week_id)[0] + datetime.timedelta(weeks=delta)
    iso = monday.isocalendar()
    return f"{iso[0]}-W{iso[1]:02d}"


def make_cell_id(week_id: str, worker_id: str, date: datetime.date) -> str:
    return f"{week_id}_{worker_id}_{date.isoformat()}"


def parse_cell_id(cell_id: str) -> tuple[str, str, str]:
    parts = cell_id.split("_")
    if len(parts) != 3:
        raise ValueError(f"invalid cell id: {cell_id}")
    week_id, worker_id, date_iso = parts
    date = datetime.date.fromisoformat(date_iso)
    if date not in week_dates(week_id):
        raise ValueError(f"date {date_iso} not in {week_id}")
    return week_id, worker_id, date_iso


def get_or_create_week(conn, week_id: str) -> dict:
    parse_week_id(week_id)
    row = conn.execute("SELECT * FROM weeks WHERE id=?", (week_id,)).fetchone()
    if row is None:
        with db.tx(conn):
            revision = db.record_change(conn, "week", week_id, "created")
            conn.execute(
                "INSERT INTO weeks(id, status, revision) VALUES(?,?,?)",
                (week_id, "OPEN", revision),
            )
        row = conn.execute("SELECT * FROM weeks WHERE id=?", (week_id,)).fetchone()
    return dict(row)


def set_week_status(conn, week_id: str, status: str, actor=("web", "web-admin")) -> dict:
    if status not in ("OPEN", "LOCKED", "ARCHIVED"):
        raise ValueError(status)
    get_or_create_week(conn, week_id)
    with db.tx(conn):
        revision = db.record_change(conn, "week", week_id, "updated")
        conn.execute("UPDATE weeks SET status=?, revision=? WHERE id=?",
                     (status, revision, week_id))
        db.audit(conn, actor[0], actor[1], f"status:{status}", "week", week_id, None)
    return dict(conn.execute("SELECT * FROM weeks WHERE id=?", (week_id,)).fetchone())
