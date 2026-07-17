import json
import uuid

from .. import db, weeks
from . import workers as workers_repo

MAX_TEXT_CHARS = 2000


class ValidationError(Exception):
    pass


class PayloadTooLarge(Exception):
    pass


class WeekLocked(Exception):
    pass


class NotFound(Exception):
    pass


class ConflictError(Exception):
    def __init__(self, conflict_entry: dict, current_revision: int):
        self.conflict_entry = conflict_entry
        self.current_revision = current_revision
        super().__init__("revision conflict")


def _row_to_dict(row) -> dict:
    d = dict(row)
    d["content"] = json.loads(d["content"])
    return d


def get_entry(conn, entry_id: str):
    row = conn.execute("SELECT * FROM entries WHERE id=?", (entry_id,)).fetchone()
    return _row_to_dict(row) if row else None


def entries_for_week(conn, week_id: str) -> list[dict]:
    rows = conn.execute(
        "SELECT * FROM entries WHERE cell_id LIKE ? ORDER BY created_at, id",
        (f"{week_id}_%",),
    ).fetchall()
    return [_row_to_dict(r) for r in rows]


def _validate_content(entry_type: str, content, max_bytes: int) -> str:
    if entry_type == "text":
        if not isinstance(content, dict) or not isinstance(content.get("text"), str) \
                or not (1 <= len(content["text"]) <= MAX_TEXT_CHARS):
            raise ValidationError("text content must be {'text': 1..2000 chars}")
    elif entry_type == "drawing":
        if not isinstance(content, dict) \
                or not isinstance(content.get("canvas_width"), int) \
                or not isinstance(content.get("canvas_height"), int) \
                or not isinstance(content.get("strokes"), list) \
                or not content["strokes"]:
            raise ValidationError("drawing content needs canvas size and strokes")
    else:
        raise ValidationError(f"invalid type: {entry_type}")
    raw = json.dumps(content, ensure_ascii=False)
    if len(raw.encode()) > max_bytes:
        raise PayloadTooLarge(f"content exceeds {max_bytes} bytes")
    return raw


def _check_week_open(conn, week_id: str) -> None:
    week = weeks.get_or_create_week(conn, week_id)
    if week["status"] != "OPEN":
        raise WeekLocked(week_id)


def create_entry(conn, cell_id: str, entry_type: str, content, author_type: str,
                 author_id: str, settings) -> dict:
    try:
        week_id, worker_id, _date = weeks.parse_cell_id(cell_id)
    except ValueError as exc:
        raise ValidationError(str(exc))
    worker = workers_repo.get_worker(conn, worker_id)
    if worker is None:
        raise ValidationError(f"unknown worker: {worker_id}")
    if worker["category"] == "azubi" and entry_type != "text":
        raise ValidationError("azubi cells accept text entries only")
    raw = _validate_content(entry_type, content, settings.max_entry_bytes)
    _check_week_open(conn, week_id)
    entry_id = "e-" + uuid.uuid4().hex[:12]
    with db.tx(conn):
        revision = db.record_change(conn, "entry", entry_id, "created")
        conn.execute(
            "INSERT INTO entries(id, cell_id, type, author_type, author_id,"
            " content, conflict_of, created_at, updated_at, revision)"
            " VALUES(?,?,?,?,?,?,NULL,datetime('now'),datetime('now'),?)",
            (entry_id, cell_id, entry_type, author_type, author_id, raw, revision),
        )
        db.audit(conn, author_type, author_id, "created", "entry", entry_id, cell_id)
    return get_entry(conn, entry_id)


def update_entry(conn, entry_id: str, content, base_revision: int, author_type: str,
                 author_id: str, settings) -> dict:
    current = get_entry(conn, entry_id)
    if current is None:
        raise NotFound(entry_id)
    week_id, _, _ = weeks.parse_cell_id(current["cell_id"])
    raw = _validate_content(current["type"], content, settings.max_entry_bytes)
    _check_week_open(conn, week_id)
    if base_revision != current["revision"]:
        conflict_id = "e-" + uuid.uuid4().hex[:12]
        with db.tx(conn):
            revision = db.record_change(conn, "entry", conflict_id, "created")
            conn.execute(
                "INSERT INTO entries(id, cell_id, type, author_type, author_id,"
                " content, conflict_of, created_at, updated_at, revision)"
                " VALUES(?,?,?,?,?,?,?,datetime('now'),datetime('now'),?)",
                (conflict_id, current["cell_id"], current["type"], author_type,
                 author_id, raw, entry_id, revision),
            )
            db.audit(conn, author_type, author_id, "conflict", "entry",
                     conflict_id, f"conflict_of={entry_id}")
        raise ConflictError(get_entry(conn, conflict_id), current["revision"])
    with db.tx(conn):
        revision = db.record_change(conn, "entry", entry_id, "updated")
        conn.execute(
            "UPDATE entries SET content=?, updated_at=datetime('now'), revision=?"
            " WHERE id=?",
            (raw, revision, entry_id),
        )
        db.audit(conn, author_type, author_id, "updated", "entry", entry_id,
                 current["cell_id"])
    return get_entry(conn, entry_id)


def delete_entry(conn, entry_id: str, author_type: str, author_id: str) -> None:
    current = get_entry(conn, entry_id)
    if current is None:
        raise NotFound(entry_id)
    week_id, _, _ = weeks.parse_cell_id(current["cell_id"])
    _check_week_open(conn, week_id)
    with db.tx(conn):
        db.record_change(conn, "entry", entry_id, "deleted")
        conn.execute("DELETE FROM entries WHERE id=?", (entry_id,))
        db.audit(conn, author_type, author_id, "deleted", "entry", entry_id,
                 current["cell_id"])
