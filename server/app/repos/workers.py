import uuid

from .. import db


class LimitExceeded(Exception):
    pass


def _row_to_dict(row) -> dict:
    d = dict(row)
    d["active"] = bool(d["active"])
    return d


def list_workers(conn) -> list[dict]:
    rows = conn.execute(
        "SELECT * FROM workers ORDER BY CASE category WHEN 'monteur' THEN 0"
        " ELSE 1 END, position"
    ).fetchall()
    return [_row_to_dict(r) for r in rows]


def get_worker(conn, worker_id: str):
    row = conn.execute("SELECT * FROM workers WHERE id=?", (worker_id,)).fetchone()
    return _row_to_dict(row) if row else None


def _active_count(conn, category: str) -> int:
    return conn.execute(
        "SELECT COUNT(*) AS n FROM workers WHERE category=? AND active=1", (category,)
    ).fetchone()["n"]


def _check_limit(conn, category: str, max_monteure: int, max_azubis: int) -> None:
    limit = max_monteure if category == "monteur" else max_azubis
    if _active_count(conn, category) >= limit:
        raise LimitExceeded(f"Maximal {limit} aktive {category}-Einträge erlaubt")


def create_worker(conn, number: str, name: str, category: str,
                  actor=("web", "web-admin"), max_monteure: int = 15,
                  max_azubis: int = 10) -> dict:
    if category not in ("monteur", "azubi"):
        raise ValueError(f"invalid category: {category}")
    with db.tx(conn):
        _check_limit(conn, category, max_monteure, max_azubis)
        worker_id = "w-" + uuid.uuid4().hex[:8]
        pos_row = conn.execute(
            "SELECT COALESCE(MAX(position),0)+1 AS p FROM workers WHERE category=?",
            (category,),
        ).fetchone()
        revision = db.record_change(conn, "worker", worker_id, "created")
        conn.execute(
            "INSERT INTO workers(id, number, name, category, position, active,"
            " revision) VALUES(?,?,?,?,?,1,?)",
            (worker_id, number, name, category, pos_row["p"], revision),
        )
        db.audit(conn, actor[0], actor[1], "created", "worker", worker_id,
                 f"{number} {name} ({category})")
    return get_worker(conn, worker_id)


def update_worker(conn, worker_id: str, *, number=None, name=None, position=None,
                  active=None, actor=("web", "web-admin"), max_monteure: int = 15,
                  max_azubis: int = 10) -> dict:
    with db.tx(conn):
        current = get_worker(conn, worker_id)
        if current is None:
            raise KeyError(worker_id)
        if active is True and not current["active"]:
            _check_limit(conn, current["category"], max_monteure, max_azubis)
        fields = {
            "number": number if number is not None else current["number"],
            "name": name if name is not None else current["name"],
            "position": position if position is not None else current["position"],
            "active": int(active if active is not None else current["active"]),
        }
        revision = db.record_change(conn, "worker", worker_id, "updated")
        conn.execute(
            "UPDATE workers SET number=?, name=?, position=?, active=?, revision=?"
            " WHERE id=?",
            (fields["number"], fields["name"], fields["position"],
             fields["active"], revision, worker_id),
        )
        db.audit(conn, actor[0], actor[1], "updated", "worker", worker_id, None)
    return get_worker(conn, worker_id)
