# TimePlan Server-Kern + WebUI (Meilenstein 1+2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deploybarer Python-Container-Server (FastAPI + SQLite) mit REST-API, WebSocket, Sync-Mechanik und server-gerenderter WebUI (Jinja2 + htmx) für den Monteur-Wochenplaner — als Portainer-Git-Stack.

**Architecture:** Ein Container, ein Prozess: FastAPI liefert `/api/v1/*` (Tablet-REST), `/ws/v1` (Live-Events) und die WebUI. SQLite mit globalem Revisions-ChangeLog ist die einzige Wahrheit; jede Mutation erzeugt eine Revision und einen WebSocket-Broadcast. Spec: `docs/superpowers/specs/2026-07-17-timeplan-design.md`.

**Tech Stack:** Python 3.12, FastAPI, Uvicorn, sqlite3 (stdlib), Jinja2, htmx (vendored), pytest + httpx.

## Global Constraints

- Public-Repo: **keine Secrets im Repo** — alles über Env-Variablen (`TIMEPLAN_*`)
- Max. **15 aktive Monteure**, **10 aktive Azubis**; Azubi-Zellen: **nur Text-Entries**
- Strokes-/Entry-JSON ≤ **2 MB** (`TIMEPLAN_MAX_ENTRY_BYTES`, Default 2097152)
- Worker-Anzeige: `"{number} {name}"` (MA-Nr. + Name)
- `cell_id` = `"{week_id}_{worker_id}_{date}"`, `week_id` = `"YYYY-Wnn"` (ISO-Woche)
- Kein TLS (internes Netz); Tablet-Auth: `Authorization: Bearer <token>` aus `TIMEPLAN_DEVICE_TOKENS`
- Gesperrte/archivierte Wochen: keine Entry-Mutationen (HTTP 423), WebUI kann Wochen wieder öffnen
- Alle Serverpfade unter `server/`; Tests laufen mit `cd server && python -m pytest`

---

### Task 1: API-Vertrag dokumentieren

**Files:**
- Create: `docs/api-contract.md`

**Interfaces:**
- Produces: den verbindlichen Vertrag, den alle folgenden Tasks (und später die Android-App) implementieren. Bei Abweichungen im Code gilt: Vertrag zuerst ändern.

- [ ] **Step 1: Vertragsdokument schreiben**

Erzeuge `docs/api-contract.md` mit exakt diesem Inhalt:

````markdown
# TimePlan API-Vertrag v1

Basis-URL: `http://<server>:8000`. Kein TLS (internes Netz).
Alle Zeiten UTC, ISO-8601. Alle IDs sind Strings.

## Authentifizierung

- Tablet/API: `Authorization: Bearer <token>`. Tokens kommen aus der
  Env-Variable `TIMEPLAN_DEVICE_TOKENS` (`geraet-id:token,geraet-id2:token2`).
  Fehlend/falsch → `401`.
- WebUI: Session-Cookie nach Login (`TIMEPLAN_ADMIN_PASSWORD`).
- `GET /api/v1/status` ist ohne Auth erreichbar (Health-Check).

## Kernbegriffe

- `week_id`: ISO-Woche, z. B. `2026-W31`
- `worker_id`: `w-<8 hex>`; Kategorie `monteur` (max. 15 aktiv, Zeichnung+Text)
  oder `azubi` (max. 10 aktiv, nur Text)
- `cell_id`: `{week_id}_{worker_id}_{YYYY-MM-DD}` — Datum muss in der Woche liegen
- `revision`: global monoton steigende Zahl über alle Mutationen (ChangeLog)

## Endpunkte

### GET /api/v1/status  (ohne Auth)
`200 {"status": "ok", "revision": <int>}`

### GET /api/v1/workers
`200 {"workers": [{"id","number","name","category","position","active","revision"}]}`
Sortiert nach `category` (monteur zuerst), dann `position`. Enthält auch inaktive.

### GET /api/v1/weeks/{week_id}
Legt die Woche bei Erstzugriff implizit an (`OPEN`).
`200 {"week": {"id","status","revision"}, "dates": ["YYYY-MM-DD" ×7],
      "entries": [<Entry>]}`
`422` bei ungültiger week_id.

Entry-Objekt:
```json
{"id": "e-…", "cell_id": "…", "type": "text|drawing",
 "author_type": "tablet|web", "author_id": "…",
 "content": {…}, "conflict_of": null,
 "created_at": "…", "updated_at": "…", "revision": 7}
```
`content` bei `text`: `{"text": "…"}` (1–2000 Zeichen).
`content` bei `drawing`: `{"canvas_width": int, "canvas_height": int,
"strokes": [{"color": "#RRGGBB", "base_width": float,
"points": [{"x","y","pressure","time"}]}]}`.

### POST /api/v1/entries
Body: `{"cell_id", "type", "content"}` → `201 {"entry": <Entry>}`
Fehler: `422` Validierung (auch: drawing in Azubi-Zelle, unbekannter Worker),
`413` content > `TIMEPLAN_MAX_ENTRY_BYTES`, `423` Woche LOCKED/ARCHIVED.

### PUT /api/v1/entries/{entry_id}
Body: `{"content", "base_revision": <int>}` → `200 {"entry": <Entry>}`
`409` wenn `base_revision` ≠ aktuelle Entry-Revision. Der Server speichert den
eingereichten Stand als Konfliktkopie (`conflict_of` = Original-ID) und
antwortet `{"detail": {"error": "revision_conflict", "conflict_entry_id": "…",
"current_revision": <int>}}`. `404` unbekannte ID, `413`/`423` wie oben.

### DELETE /api/v1/entries/{entry_id}
`204`. `404` unbekannt, `423` Woche gesperrt.

### GET /api/v1/sync?since={revision}
Aufholen nach Offline-Phase. Kompaktiert: pro Entität nur die letzte Änderung.
`200 {"latest_revision": <int>, "changes":
      [{"revision", "entity_type": "worker|week|entry", "entity_id", "action":
        "created|updated|deleted"}]}`
Client lädt danach betroffene Entitäten per REST nach.

### WS /ws/v1
Query: `?device_id=<id>&token=<token>` (Tablet) oder `?client=web` (Browser,
ohne Token — Events enthalten keine Inhalte, nur IDs/Revisionen).
Server → Client Events:
```json
{"event": "cell.updated", "cell_id": "…", "revision": 12}
{"event": "workers.updated", "revision": 13}
{"event": "week.updated", "week_id": "…", "revision": 14}
```
Client → Server: beliebige Texte werden ignoriert (Keepalive erlaubt).

## Fehlerformat

FastAPI-Standard: `{"detail": …}` (String oder Objekt, s. o.).

## Env-Variablen (Portainer-Stack)

| Variable | Pflicht | Bedeutung |
|---|---|---|
| `TIMEPLAN_SECRET_KEY` | ja | Session-Signierung WebUI |
| `TIMEPLAN_ADMIN_PASSWORD` | ja | WebUI-Login |
| `TIMEPLAN_DEVICE_TOKENS` | nein | `id:token,…` für Tablets |
| `TIMEPLAN_DB` | nein | Default `/data/timeplan.db` (Container) |
| `TIMEPLAN_MAX_ENTRY_BYTES` | nein | Default `2097152` |
````

- [ ] **Step 2: Commit**

```bash
git add docs/api-contract.md
git commit -m "docs: API-Vertrag v1"
```

---

### Task 2: Server-Grundgerüst + Status-Endpunkt

**Files:**
- Create: `server/requirements.txt`, `server/requirements-dev.txt`, `server/app/__init__.py`, `server/app/config.py`, `server/app/main.py`, `server/tests/conftest.py`, `server/tests/test_status.py`, `server/pytest.ini`, `.gitignore`

**Interfaces:**
- Produces: `create_app() -> FastAPI` (Factory, liest Env bei Aufruf), `load_settings() -> Settings` mit Feldern `db_path, secret_key, admin_password, device_tokens: dict[str,str], max_entry_bytes, max_monteure=15, max_azubis=10`; pytest-Fixture `client` (frische DB in tmp_path, Device-Token `tablet-01:testtoken`, Admin-Passwort `pw`).

- [ ] **Step 1: Projektdateien anlegen**

`server/requirements.txt`:
```text
fastapi>=0.111
uvicorn[standard]>=0.30
jinja2>=3.1
python-multipart>=0.0.9
itsdangerous>=2.2
```

`server/requirements-dev.txt`:
```text
-r requirements.txt
pytest>=8
httpx>=0.27
```

`server/pytest.ini`:
```ini
[pytest]
testpaths = tests
```

`.gitignore` (Repo-Root):
```text
__pycache__/
*.pyc
.venv/
server/data/
*.db
*.db-wal
*.db-shm
```

`server/app/__init__.py`: leere Datei.

Dann: `cd server && python -m venv .venv && .venv/bin/pip install -r requirements-dev.txt`
(Alle folgenden `pytest`/`python`-Aufrufe nutzen `server/.venv/bin/…` bzw. eine aktivierte venv.)

- [ ] **Step 2: Failing Test schreiben**

`server/tests/conftest.py`:
```python
import pathlib
import sys

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("TIMEPLAN_DB", str(tmp_path / "test.db"))
    monkeypatch.setenv("TIMEPLAN_DEVICE_TOKENS", "tablet-01:testtoken")
    monkeypatch.setenv("TIMEPLAN_ADMIN_PASSWORD", "pw")
    monkeypatch.setenv("TIMEPLAN_SECRET_KEY", "test-secret")
    from fastapi.testclient import TestClient

    from app.main import create_app

    with TestClient(create_app()) as c:
        yield c


@pytest.fixture()
def device_headers():
    return {"Authorization": "Bearer testtoken"}
```

`server/tests/test_status.py`:
```python
def test_status_ok(client):
    r = client.get("/api/v1/status")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok"
    assert body["revision"] == 0
```

- [ ] **Step 3: Test läuft rot**

Run: `cd server && python -m pytest tests/test_status.py -v`
Expected: FAIL (`ModuleNotFoundError: app.main` bzw. app existiert nicht)

- [ ] **Step 4: Implementierung**

`server/app/config.py`:
```python
import os
from dataclasses import dataclass


def _parse_device_tokens(raw: str) -> dict[str, str]:
    tokens: dict[str, str] = {}
    for pair in raw.split(","):
        device_id, _, token = pair.strip().partition(":")
        if device_id and token:
            tokens[device_id] = token
    return tokens


@dataclass(frozen=True)
class Settings:
    db_path: str
    secret_key: str
    admin_password: str
    device_tokens: dict
    max_entry_bytes: int
    max_monteure: int = 15
    max_azubis: int = 10


def load_settings() -> Settings:
    return Settings(
        db_path=os.environ.get("TIMEPLAN_DB", "./data/timeplan.db"),
        secret_key=os.environ.get("TIMEPLAN_SECRET_KEY", "dev-secret-change-me"),
        admin_password=os.environ.get("TIMEPLAN_ADMIN_PASSWORD", "admin"),
        device_tokens=_parse_device_tokens(os.environ.get("TIMEPLAN_DEVICE_TOKENS", "")),
        max_entry_bytes=int(os.environ.get("TIMEPLAN_MAX_ENTRY_BYTES", "2097152")),
    )
```

`server/app/main.py`:
```python
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
```
(Die harte `0` wird in Task 3 durch `db.latest_revision` ersetzt.)

- [ ] **Step 5: Test läuft grün**

Run: `cd server && python -m pytest -v`
Expected: PASS (1 passed)

- [ ] **Step 6: Commit**

```bash
git add .gitignore server/
git commit -m "feat(server): FastAPI-Grundgerüst mit Status-Endpunkt"
```

---

### Task 3: SQLite-Schema, ChangeLog & Audit

**Files:**
- Create: `server/app/db.py`, `server/tests/test_db.py`
- Modify: `server/app/main.py`

**Interfaces:**
- Consumes: `Settings.db_path` aus Task 2
- Produces: `db.connect(path) -> sqlite3.Connection` (Row-Factory, WAL, `check_same_thread=False`), `db.init_db(conn)` (idempotent), `db.tx(conn)` (Contextmanager: prozessweiter Write-Lock + Commit/Rollback), `db.record_change(conn, entity_type, entity_id, action) -> int` (neue globale Revision), `db.latest_revision(conn) -> int`, `db.audit(conn, actor_type, actor_id, action, entity_type, entity_id, detail=None)`. `app.state.db` hält die eine Connection; `/api/v1/status` liefert echte Revision.

- [ ] **Step 1: Failing Tests schreiben**

`server/tests/test_db.py`:
```python
import sqlite3

from app import db


def make_conn():
    conn = db.connect(":memory:")
    db.init_db(conn)
    return conn


def test_init_db_idempotent():
    conn = make_conn()
    db.init_db(conn)  # zweiter Aufruf darf nicht crashen
    assert db.latest_revision(conn) == 0


def test_record_change_increments_revision():
    conn = make_conn()
    with db.tx(conn):
        r1 = db.record_change(conn, "worker", "w-1", "created")
        r2 = db.record_change(conn, "worker", "w-1", "updated")
    assert (r1, r2) == (1, 2)
    assert db.latest_revision(conn) == 2


def test_tx_rolls_back_on_error():
    conn = make_conn()
    try:
        with db.tx(conn):
            db.record_change(conn, "worker", "w-1", "created")
            raise RuntimeError("boom")
    except RuntimeError:
        pass
    assert db.latest_revision(conn) == 0


def test_audit_row_written():
    conn = make_conn()
    with db.tx(conn):
        db.audit(conn, "web", "web-admin", "created", "entry", "e-1", "hallo")
    row = conn.execute("SELECT * FROM audit_log").fetchone()
    assert row["actor_id"] == "web-admin"
    assert row["detail"] == "hallo"
```

- [ ] **Step 2: Rot laufen lassen**

Run: `cd server && python -m pytest tests/test_db.py -v`
Expected: FAIL (`No module named 'app.db'`)

- [ ] **Step 3: Implementierung**

`server/app/db.py`:
```python
import sqlite3
import threading
from contextlib import contextmanager

_write_lock = threading.Lock()

SCHEMA = """
CREATE TABLE IF NOT EXISTS workers(
  id TEXT PRIMARY KEY,
  number TEXT NOT NULL,
  name TEXT NOT NULL,
  category TEXT NOT NULL CHECK(category IN ('monteur','azubi')),
  position INTEGER NOT NULL,
  active INTEGER NOT NULL DEFAULT 1,
  revision INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS weeks(
  id TEXT PRIMARY KEY,
  status TEXT NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','LOCKED','ARCHIVED')),
  revision INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS entries(
  id TEXT PRIMARY KEY,
  cell_id TEXT NOT NULL,
  type TEXT NOT NULL CHECK(type IN ('text','drawing')),
  author_type TEXT NOT NULL CHECK(author_type IN ('tablet','web')),
  author_id TEXT NOT NULL,
  content TEXT NOT NULL,
  conflict_of TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  revision INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_entries_cell ON entries(cell_id);
CREATE TABLE IF NOT EXISTS devices(
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  last_seen TEXT
);
CREATE TABLE IF NOT EXISTS changelog(
  revision INTEGER PRIMARY KEY AUTOINCREMENT,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  action TEXT NOT NULL,
  ts TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS audit_log(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts TEXT NOT NULL,
  actor_type TEXT NOT NULL,
  actor_id TEXT NOT NULL,
  action TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  detail TEXT
);
"""


def connect(path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(path, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def init_db(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA)
    conn.commit()


@contextmanager
def tx(conn: sqlite3.Connection):
    with _write_lock:
        try:
            yield conn
            conn.commit()
        except BaseException:
            conn.rollback()
            raise


def record_change(conn, entity_type: str, entity_id: str, action: str) -> int:
    cur = conn.execute(
        "INSERT INTO changelog(entity_type, entity_id, action, ts)"
        " VALUES(?,?,?,datetime('now'))",
        (entity_type, entity_id, action),
    )
    return cur.lastrowid


def latest_revision(conn) -> int:
    row = conn.execute("SELECT COALESCE(MAX(revision),0) AS r FROM changelog").fetchone()
    return row["r"]


def audit(conn, actor_type, actor_id, action, entity_type, entity_id, detail=None):
    conn.execute(
        "INSERT INTO audit_log(ts, actor_type, actor_id, action, entity_type,"
        " entity_id, detail) VALUES(datetime('now'),?,?,?,?,?,?)",
        (actor_type, actor_id, action, entity_type, entity_id, detail),
    )
```

`server/app/main.py` komplett ersetzen durch:
```python
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request

from . import db
from .config import load_settings


def create_app() -> FastAPI:
    settings = load_settings()
    if settings.db_path != ":memory:":
        os.makedirs(os.path.dirname(os.path.abspath(settings.db_path)), exist_ok=True)
    conn = db.connect(settings.db_path)
    db.init_db(conn)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        yield
        conn.close()

    app = FastAPI(title="TimePlan", lifespan=lifespan)
    app.state.settings = settings
    app.state.db = conn

    @app.get("/api/v1/status")
    def status(request: Request):
        return {"status": "ok", "revision": db.latest_revision(request.app.state.db)}

    return app
```

- [ ] **Step 4: Grün laufen lassen**

Run: `cd server && python -m pytest -v`
Expected: PASS (alle Tests, inkl. test_status)

- [ ] **Step 5: Commit**

```bash
git add server/app/db.py server/app/main.py server/tests/test_db.py
git commit -m "feat(server): SQLite-Schema mit ChangeLog-Revisionen und Audit-Log"
```

---

### Task 4: Worker-Repository mit 15/10-Limits + Seed

**Files:**
- Create: `server/app/repos/__init__.py`, `server/app/repos/workers.py`, `server/app/seed.py`, `server/tests/test_workers_repo.py`

**Interfaces:**
- Consumes: `db.tx`, `db.record_change`, `db.audit` (Task 3)
- Produces: `workers.LimitExceeded(Exception)`; `workers.list_workers(conn) -> list[dict]` (monteure zuerst, dann position; inkl. inaktive); `workers.get_worker(conn, worker_id) -> dict | None`; `workers.create_worker(conn, number, name, category, actor=("web","web-admin"), max_monteure=15, max_azubis=10) -> dict`; `workers.update_worker(conn, worker_id, *, number=None, name=None, position=None, active=None, actor=("web","web-admin"), max_monteure=15, max_azubis=10) -> dict`. Worker-Dict-Keys: `id, number, name, category, position, active(bool), revision`. IDs: `"w-" + uuid4().hex[:8]`.

- [ ] **Step 1: Failing Tests schreiben**

`server/tests/test_workers_repo.py`:
```python
import pytest

from app import db
from app.repos import workers


def make_conn():
    conn = db.connect(":memory:")
    db.init_db(conn)
    return conn


def test_create_and_list_sorted():
    conn = make_conn()
    workers.create_worker(conn, "501", "Azubi Eins", "azubi")
    m = workers.create_worker(conn, "144", "Monteur Eins", "monteur")
    assert m["id"].startswith("w-")
    assert m["revision"] == 2
    lst = workers.list_workers(conn)
    assert [w["category"] for w in lst] == ["monteur", "azubi"]


def test_monteur_limit_enforced():
    conn = make_conn()
    for i in range(15):
        workers.create_worker(conn, str(100 + i), f"M {i}", "monteur")
    with pytest.raises(workers.LimitExceeded):
        workers.create_worker(conn, "999", "Zuviel", "monteur")


def test_azubi_limit_enforced_on_reactivation():
    conn = make_conn()
    created = [workers.create_worker(conn, str(500 + i), f"A {i}", "azubi") for i in range(10)]
    workers.update_worker(conn, created[0]["id"], active=False)
    extra = workers.create_worker(conn, "599", "Nachrücker", "azubi")
    with pytest.raises(workers.LimitExceeded):
        workers.update_worker(conn, created[0]["id"], active=True)
    assert extra["active"] is True


def test_update_bumps_revision():
    conn = make_conn()
    w = workers.create_worker(conn, "144", "Alt", "monteur")
    w2 = workers.update_worker(conn, w["id"], name="Neu")
    assert w2["name"] == "Neu"
    assert w2["revision"] > w["revision"]
```

- [ ] **Step 2: Rot laufen lassen**

Run: `cd server && python -m pytest tests/test_workers_repo.py -v`
Expected: FAIL (`No module named 'app.repos'`)

- [ ] **Step 3: Implementierung**

`server/app/repos/__init__.py`: leer.

`server/app/repos/workers.py`:
```python
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
```

`server/app/seed.py` (nur Entwicklung/Demo, fiktive Namen; läuft nur bei leerer Tabelle):
```python
"""Testdaten: python -m app.seed  (nutzt TIMEPLAN_DB)."""
from . import db
from .config import load_settings
from .repos import workers

MONTEURE = [
    ("101", "Albrecht"), ("104", "Bergmann"), ("112", "Conrad"),
    ("118", "Dietrich"), ("125", "Ebert"), ("131", "Falk"),
    ("136", "Grimm"), ("140", "Hartmann"), ("147", "Ilgner"),
    ("152", "Jansen"), ("158", "Kaiser"), ("163", "Lindner"),
    ("169", "Martens"), ("174", "Nowak"), ("180", "Ostermann"),
]
AZUBIS = [
    ("501", "Petersen"), ("502", "Quandt"), ("503", "Richter"),
    ("504", "Sommer"), ("505", "Thiel"), ("506", "Ulrich"), ("507", "Vogel"),
]


def main() -> None:
    settings = load_settings()
    conn = db.connect(settings.db_path)
    db.init_db(conn)
    if conn.execute("SELECT COUNT(*) AS n FROM workers").fetchone()["n"]:
        print("workers vorhanden – kein Seed")
        return
    for number, name in MONTEURE:
        workers.create_worker(conn, number, name, "monteur")
    for number, name in AZUBIS:
        workers.create_worker(conn, number, name, "azubi")
    print(f"Seed ok: {len(MONTEURE)} Monteure, {len(AZUBIS)} Azubis")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Grün laufen lassen**

Run: `cd server && python -m pytest -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/app/repos/ server/app/seed.py server/tests/test_workers_repo.py
git commit -m "feat(server): Worker-Repository mit 15/10-Limits und Seed-Daten"
```

---

### Task 5: Geräte-Auth + GET /api/v1/workers

**Files:**
- Create: `server/app/api/__init__.py`, `server/app/api/routes.py`, `server/tests/test_auth.py`
- Modify: `server/app/main.py`

**Interfaces:**
- Consumes: `workers.list_workers` (Task 4), `Settings.device_tokens` (Task 2)
- Produces: FastAPI-Dependency `require_device(request: Request) -> str` (gibt `device_id` zurück, wirft `HTTPException(401)`, pflegt `devices.last_seen` per Upsert); `router` (APIRouter, prefix `/api/v1`) mit `GET /workers`; `main.create_app` bindet den Router ein. Alle späteren API-Tasks hängen ihre Endpunkte an diesen `router`.

- [ ] **Step 1: Failing Tests schreiben**

`server/tests/test_auth.py`:
```python
def test_workers_requires_token(client):
    assert client.get("/api/v1/workers").status_code == 401
    r = client.get("/api/v1/workers", headers={"Authorization": "Bearer falsch"})
    assert r.status_code == 401


def test_workers_with_token(client, device_headers):
    r = client.get("/api/v1/workers", headers=device_headers)
    assert r.status_code == 200
    assert r.json() == {"workers": []}


def test_last_seen_updated(client, device_headers):
    client.get("/api/v1/workers", headers=device_headers)
    row = client.app.state.db.execute(
        "SELECT * FROM devices WHERE id='tablet-01'"
    ).fetchone()
    assert row is not None
    assert row["last_seen"] is not None
```

- [ ] **Step 2: Rot laufen lassen**

Run: `cd server && python -m pytest tests/test_auth.py -v`
Expected: FAIL (404 statt 401 — Route existiert nicht)

- [ ] **Step 3: Implementierung**

`server/app/api/__init__.py`: leer.

`server/app/api/routes.py`:
```python
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
```

In `server/app/main.py` nach `app.state.db = conn` einfügen:
```python
    from .api.routes import router as api_router
    app.include_router(api_router)
```

- [ ] **Step 4: Grün laufen lassen**

Run: `cd server && python -m pytest -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/app/api/ server/app/main.py server/tests/test_auth.py
git commit -m "feat(server): Bearer-Geräte-Auth und Workers-Endpunkt"
```

---

### Task 6: Wochenlogik + GET /api/v1/weeks/{week_id}

**Files:**
- Create: `server/app/weeks.py`, `server/tests/test_weeks.py`
- Modify: `server/app/api/routes.py`

**Interfaces:**
- Consumes: `db.tx`, `db.record_change`; `require_device`, `router` (Task 5)
- Produces: `weeks.parse_week_id(week_id) -> (year, week)` (ValueError bei Müll), `weeks.week_dates(week_id) -> list[datetime.date]` (Mo–So), `weeks.current_week_id(today=None) -> str`, `weeks.adjacent_week_id(week_id, delta) -> str`, `weeks.make_cell_id(week_id, worker_id, date) -> str`, `weeks.parse_cell_id(cell_id) -> (week_id, worker_id, date_iso)` (ValueError, prüft Datum-in-Woche), `weeks.get_or_create_week(conn, week_id) -> dict` (`{id,status,revision}`), `weeks.set_week_status(conn, week_id, status, actor) -> dict`. API: `GET /weeks/{week_id}` → `{"week","dates","entries"}` (entries ab Task 7 gefüllt, hier `[]`).

- [ ] **Step 1: Failing Tests schreiben**

`server/tests/test_weeks.py`:
```python
import datetime

import pytest

from app import weeks


def test_week_dates_iso():
    dates = weeks.week_dates("2026-W31")
    assert dates[0] == datetime.date(2026, 7, 27)
    assert dates[6] == datetime.date(2026, 8, 2)


def test_adjacent_over_year_boundary():
    assert weeks.adjacent_week_id("2026-W01", -1) == "2025-W52"
    assert weeks.adjacent_week_id("2025-W52", 1) == "2026-W01"


def test_parse_week_id_invalid():
    with pytest.raises(ValueError):
        weeks.parse_week_id("2026-31")
    with pytest.raises(ValueError):
        weeks.parse_week_id("2026-W60")


def test_cell_id_roundtrip():
    cid = weeks.make_cell_id("2026-W31", "w-abc12345", datetime.date(2026, 7, 30))
    assert cid == "2026-W31_w-abc12345_2026-07-30"
    assert weeks.parse_cell_id(cid) == ("2026-W31", "w-abc12345", "2026-07-30")
    with pytest.raises(ValueError):
        weeks.parse_cell_id("2026-W31_w-abc12345_2026-09-01")  # Datum nicht in KW31


def test_get_week_endpoint(client, device_headers):
    r = client.get("/api/v1/weeks/2026-W31", headers=device_headers)
    assert r.status_code == 200
    body = r.json()
    assert body["week"] == {"id": "2026-W31", "status": "OPEN",
                            "revision": body["week"]["revision"]}
    assert len(body["dates"]) == 7
    assert body["entries"] == []
    assert client.get("/api/v1/weeks/quatsch", headers=device_headers).status_code == 422
```

- [ ] **Step 2: Rot laufen lassen**

Run: `cd server && python -m pytest tests/test_weeks.py -v`
Expected: FAIL (`No module named 'app.weeks'`)

- [ ] **Step 3: Implementierung**

`server/app/weeks.py`:
```python
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
```

In `server/app/api/routes.py` ergänzen (Import oben: `from .. import weeks as weeklib`):
```python
@router.get("/weeks/{week_id}")
def get_week(week_id: str, request: Request):
    require_device(request)
    conn = request.app.state.db
    try:
        week = weeklib.get_or_create_week(conn, week_id)
        dates = [d.isoformat() for d in weeklib.week_dates(week_id)]
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc))
    return {"week": week, "dates": dates, "entries": []}
```
(`"entries": []` wird in Task 7 durch echte Daten ersetzt.)

- [ ] **Step 4: Grün laufen lassen**

Run: `cd server && python -m pytest -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/app/weeks.py server/app/api/routes.py server/tests/test_weeks.py
git commit -m "feat(server): ISO-Wochenlogik, Zell-IDs und Weeks-Endpunkt"
```

---

### Task 7: Entries-Repository + CRUD-API mit Konflikt- und Sperrlogik

**Files:**
- Create: `server/app/repos/entries.py`, `server/tests/test_entries.py`
- Modify: `server/app/api/routes.py`

**Interfaces:**
- Consumes: Task 3–6 (`db.*`, `workers.get_worker`, `weeklib.parse_cell_id`, `weeklib.get_or_create_week`, `require_device`)
- Produces: Exceptions `entries.ValidationError`, `entries.PayloadTooLarge`, `entries.WeekLocked`, `entries.ConflictError` (Attribut `conflict_entry: dict`, `current_revision: int`), `entries.NotFound`; Funktionen `entries.create_entry(conn, cell_id, type, content, author_type, author_id, settings) -> dict`, `entries.update_entry(conn, entry_id, content, base_revision, author_type, author_id, settings) -> dict`, `entries.delete_entry(conn, entry_id, author_type, author_id) -> None`, `entries.entries_for_week(conn, week_id) -> list[dict]`, `entries.get_entry(conn, entry_id) -> dict | None`. Entry-Dict wie im API-Vertrag (`content` als dict geparst). REST: `POST /entries` (201), `PUT /entries/{id}` (200/409), `DELETE /entries/{id}` (204), `GET /weeks/{id}` liefert jetzt echte Entries.

- [ ] **Step 1: Failing Tests schreiben**

`server/tests/test_entries.py`:
```python
import pytest

from app import db
from app.repos import workers


@pytest.fixture()
def setup(client, device_headers):
    conn = client.app.state.db
    monteur = workers.create_worker(conn, "144", "Monteur", "monteur")
    azubi = workers.create_worker(conn, "501", "Azubi", "azubi")
    def cell(worker, date="2026-07-30"):
        return f"2026-W31_{worker['id']}_{date}"
    return client, device_headers, conn, monteur, azubi, cell


def test_create_text_entry(setup):
    client, hdr, conn, monteur, azubi, cell = setup
    r = client.post("/api/v1/entries", headers=hdr, json={
        "cell_id": cell(monteur), "type": "text", "content": {"text": "Baustelle A"}})
    assert r.status_code == 201
    entry = r.json()["entry"]
    assert entry["author_type"] == "tablet"
    assert entry["author_id"] == "tablet-01"
    week = client.get("/api/v1/weeks/2026-W31", headers=hdr).json()
    assert [e["id"] for e in week["entries"]] == [entry["id"]]


def test_drawing_in_azubi_cell_rejected(setup):
    client, hdr, conn, monteur, azubi, cell = setup
    r = client.post("/api/v1/entries", headers=hdr, json={
        "cell_id": cell(azubi), "type": "drawing",
        "content": {"canvas_width": 100, "canvas_height": 50, "strokes": [
            {"color": "#000000", "base_width": 2.0,
             "points": [{"x": 1, "y": 1, "pressure": 0.5, "time": 0}]}]}})
    assert r.status_code == 422


def test_oversize_content_rejected(setup):
    client, hdr, conn, monteur, azubi, cell = setup
    r = client.post("/api/v1/entries", headers=hdr, json={
        "cell_id": cell(monteur), "type": "text",
        "content": {"text": "x" * 3000}})
    assert r.status_code == 422  # Textlimit 2000 Zeichen


def test_update_with_stale_revision_conflicts(setup):
    client, hdr, conn, monteur, azubi, cell = setup
    entry = client.post("/api/v1/entries", headers=hdr, json={
        "cell_id": cell(monteur), "type": "text",
        "content": {"text": "v1"}}).json()["entry"]
    ok = client.put(f"/api/v1/entries/{entry['id']}", headers=hdr, json={
        "content": {"text": "v2"}, "base_revision": entry["revision"]})
    assert ok.status_code == 200
    stale = client.put(f"/api/v1/entries/{entry['id']}", headers=hdr, json={
        "content": {"text": "v3"}, "base_revision": entry["revision"]})
    assert stale.status_code == 409
    detail = stale.json()["detail"]
    conflict = client.app.state.db.execute(
        "SELECT * FROM entries WHERE id=?", (detail["conflict_entry_id"],)
    ).fetchone()
    assert conflict["conflict_of"] == entry["id"]


def test_locked_week_blocks_mutation(setup):
    client, hdr, conn, monteur, azubi, cell = setup
    from app import weeks as weeklib
    weeklib.get_or_create_week(conn, "2026-W31")
    weeklib.set_week_status(conn, "2026-W31", "LOCKED")
    r = client.post("/api/v1/entries", headers=hdr, json={
        "cell_id": cell(monteur), "type": "text", "content": {"text": "zu spät"}})
    assert r.status_code == 423


def test_delete_entry(setup):
    client, hdr, conn, monteur, azubi, cell = setup
    entry = client.post("/api/v1/entries", headers=hdr, json={
        "cell_id": cell(monteur), "type": "text",
        "content": {"text": "weg"}}).json()["entry"]
    assert client.delete(f"/api/v1/entries/{entry['id']}", headers=hdr).status_code == 204
    assert client.delete(f"/api/v1/entries/{entry['id']}", headers=hdr).status_code == 404
```

- [ ] **Step 2: Rot laufen lassen**

Run: `cd server && python -m pytest tests/test_entries.py -v`
Expected: FAIL (404/405 — Endpunkte fehlen)

- [ ] **Step 3: Repository implementieren**

`server/app/repos/entries.py`:
```python
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
```

Hinweis: In `entries_for_week` ist das LIKE-Muster `f"{week_id}_%"` gewollt — `_` matcht in SQLite LIKE zwar jedes Zeichen, aber da alle `cell_id`s der Woche mit `{week_id}_` beginnen und `week_id` fixe Länge hat, ist das Ergebnis korrekt. Wer es exakt will: `WHERE substr(cell_id,1,length(?))=?` — im Plan bleibt LIKE.

- [ ] **Step 4: API-Endpunkte ergänzen**

In `server/app/api/routes.py`: Imports erweitern und Endpunkte anfügen. Neuer Kopf:
```python
import secrets

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel

from .. import db
from .. import weeks as weeklib
from ..repos import entries as entries_repo
from ..repos import workers
```

Pydantic-Modelle + Fehler-Mapping + Endpunkte anfügen:
```python
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
```

In `get_week` die Zeile `return {"week": week, "dates": dates, "entries": []}` ersetzen durch:
```python
    return {"week": week, "dates": dates,
            "entries": entries_repo.entries_for_week(conn, week_id)}
```

- [ ] **Step 5: Grün laufen lassen**

Run: `cd server && python -m pytest -v`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add server/app/repos/entries.py server/app/api/routes.py server/tests/test_entries.py
git commit -m "feat(server): Entries-CRUD mit Revisionskonflikten und Wochensperre"
```

---

### Task 8: Sync-Endpunkt

**Files:**
- Create: `server/tests/test_sync.py`
- Modify: `server/app/api/routes.py`

**Interfaces:**
- Consumes: `changelog`-Tabelle (Task 3), `require_device` (Task 5)
- Produces: `GET /api/v1/sync?since=N` → `{"latest_revision", "changes": [...]}`, kompaktiert pro `(entity_type, entity_id)` auf die letzte Änderung, aufsteigend nach `revision`.

- [ ] **Step 1: Failing Tests schreiben**

`server/tests/test_sync.py`:
```python
from app.repos import workers


def test_sync_compacts_changes(client, device_headers):
    conn = client.app.state.db
    w = workers.create_worker(conn, "144", "Alt", "monteur")      # rev 1
    workers.update_worker(conn, w["id"], name="Neu")              # rev 2
    workers.create_worker(conn, "501", "Azubi", "azubi")          # rev 3
    r = client.get("/api/v1/sync?since=0", headers=device_headers)
    assert r.status_code == 200
    body = r.json()
    assert body["latest_revision"] == 3
    assert len(body["changes"]) == 2  # w kompaktiert auf letzte Änderung
    first = body["changes"][0]
    assert first["entity_id"] == w["id"]
    assert first["revision"] == 2
    assert first["action"] == "updated"


def test_sync_since_filters(client, device_headers):
    conn = client.app.state.db
    workers.create_worker(conn, "144", "M", "monteur")  # rev 1
    r = client.get("/api/v1/sync?since=1", headers=device_headers)
    assert r.json() == {"latest_revision": 1, "changes": []}
```

- [ ] **Step 2: Rot laufen lassen**

Run: `cd server && python -m pytest tests/test_sync.py -v`
Expected: FAIL (404)

- [ ] **Step 3: Implementierung**

In `server/app/api/routes.py` anfügen:
```python
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
```

- [ ] **Step 4: Grün laufen lassen**

Run: `cd server && python -m pytest -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/app/api/routes.py server/tests/test_sync.py
git commit -m "feat(server): Sync-Endpunkt mit kompaktierter Änderungsliste"
```

---

### Task 9: WebSocket-Hub + Broadcasts bei Mutationen

**Files:**
- Create: `server/app/ws.py`, `server/tests/test_ws.py`
- Modify: `server/app/api/routes.py`, `server/app/main.py`

**Interfaces:**
- Consumes: Auth-Logik aus Task 5 (Token-Vergleich), Entry-Endpunkte aus Task 7
- Produces: `ws.Hub` mit `async connect(websocket)`, `disconnect(websocket)`, `async broadcast(message: dict)`; `app.state.hub: Hub`; `ws_router` mit `WS /ws/v1` (`?device_id=&token=` oder `?client=web`, sonst Close 4401); alle Entry-Mutationen senden `{"event":"cell.updated","cell_id","revision"}`. Spätere Tasks (12, 13) rufen `await hub.broadcast(...)` ebenfalls auf.

- [ ] **Step 1: Failing Test schreiben**

`server/tests/test_ws.py`:
```python
from app.repos import workers


def test_ws_receives_cell_update(client, device_headers):
    conn = client.app.state.db
    monteur = workers.create_worker(conn, "144", "M", "monteur")
    cell_id = f"2026-W31_{monteur['id']}_2026-07-30"
    with client.websocket_connect("/ws/v1?client=web") as ws:
        r = client.post("/api/v1/entries", headers=device_headers, json={
            "cell_id": cell_id, "type": "text", "content": {"text": "hi"}})
        assert r.status_code == 201
        event = ws.receive_json()
        assert event["event"] == "cell.updated"
        assert event["cell_id"] == cell_id
        assert event["revision"] == r.json()["entry"]["revision"]


def test_ws_rejects_bad_token(client):
    import pytest
    from starlette.websockets import WebSocketDisconnect
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect("/ws/v1?device_id=x&token=falsch") as ws:
            ws.receive_json()
```

- [ ] **Step 2: Rot laufen lassen**

Run: `cd server && python -m pytest tests/test_ws.py -v`
Expected: FAIL (Route existiert nicht)

- [ ] **Step 3: Implementierung**

`server/app/ws.py`:
```python
import secrets

from fastapi import APIRouter, WebSocket, WebSocketDisconnect


class Hub:
    def __init__(self):
        self._clients: set[WebSocket] = set()

    async def connect(self, websocket: WebSocket) -> None:
        await websocket.accept()
        self._clients.add(websocket)

    def disconnect(self, websocket: WebSocket) -> None:
        self._clients.discard(websocket)

    async def broadcast(self, message: dict) -> None:
        for ws in list(self._clients):
            try:
                await ws.send_json(message)
            except Exception:
                self.disconnect(ws)


ws_router = APIRouter()


@ws_router.websocket("/ws/v1")
async def websocket_endpoint(websocket: WebSocket):
    settings = websocket.app.state.settings
    params = websocket.query_params
    authorized = params.get("client") == "web"
    if not authorized:
        expected = settings.device_tokens.get(params.get("device_id", ""))
        token = params.get("token", "")
        authorized = expected is not None and secrets.compare_digest(token, expected)
    if not authorized:
        await websocket.close(code=4401)
        return
    hub: Hub = websocket.app.state.hub
    await hub.connect(websocket)
    try:
        while True:
            await websocket.receive_text()  # Keepalives ignorieren
    except WebSocketDisconnect:
        hub.disconnect(websocket)
```

In `server/app/main.py`: nach `app.state.db = conn` einfügen bzw. Router-Block erweitern:
```python
    from .ws import Hub, ws_router
    app.state.hub = Hub()
    app.include_router(ws_router)
```

In `server/app/api/routes.py` in den drei Entry-Endpunkten nach erfolgreicher Mutation vor dem `return` jeweils broadcasten:

`create_entry`:
```python
    await state.hub.broadcast({"event": "cell.updated", "cell_id": entry["cell_id"],
                               "revision": entry["revision"]})
```
`update_entry`: identisch (nach dem try/except).
`delete_entry` (hier gibt es kein Entry-Objekt mehr; `cell_id` vor dem Löschen holen):
```python
@router.delete("/entries/{entry_id}", status_code=204)
async def delete_entry(entry_id: str, request: Request):
    device_id = require_device(request)
    state = request.app.state
    existing = entries_repo.get_entry(state.db, entry_id)
    try:
        entries_repo.delete_entry(state.db, entry_id, "tablet", device_id)
    except Exception as exc:
        raise _map_entry_error(exc)
    await state.hub.broadcast({"event": "cell.updated",
                               "cell_id": existing["cell_id"],
                               "revision": db.latest_revision(state.db)})
```

- [ ] **Step 4: Grün laufen lassen**

Run: `cd server && python -m pytest -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/app/ws.py server/app/main.py server/app/api/routes.py server/tests/test_ws.py
git commit -m "feat(server): WebSocket-Hub mit cell.updated-Broadcasts"
```

---

### Task 10: WebUI-Login, Base-Layout & Statics

**Files:**
- Create: `server/app/web/__init__.py`, `server/app/web/routes.py`, `server/app/templates/base.html`, `server/app/templates/login.html`, `server/app/static/style.css`, `server/app/static/htmx.min.js` (vendored), `server/tests/test_web_auth.py`
- Modify: `server/app/main.py`

**Interfaces:**
- Consumes: `Settings.admin_password`, `Settings.secret_key` (Task 2)
- Produces: `web_router` (kein Prefix); `templates: Jinja2Templates` (Verzeichnis `app/templates`); Dependency `require_admin(request)` (wirft `HTTPException(303, headers={"Location": "/login"})` wenn keine Session); Routen `GET/POST /login`, `GET /logout`, `GET /` (Redirect auf `/week/{current_week_id()}` — Week-Seite folgt in Task 11); `SessionMiddleware` + `/static`-Mount in `main.py`. Templates erben von `base.html` (Block `content`; Nav mit Links `/`, `/workers`, `/logout`).

- [ ] **Step 1: htmx vendoren**

Run: `curl -fsSL -o server/app/static/htmx.min.js https://unpkg.com/htmx.org@1.9.12/dist/htmx.min.js`
Expected: Datei ~48 KB vorhanden (`ls -la server/app/static/`)

- [ ] **Step 2: Failing Tests schreiben**

`server/tests/test_web_auth.py`:
```python
def test_root_redirects_to_login_without_session(client):
    r = client.get("/", follow_redirects=False)
    assert r.status_code == 303
    assert r.headers["location"] == "/login"


def test_login_wrong_password(client):
    r = client.post("/login", data={"password": "falsch"})
    assert r.status_code == 200
    assert "Passwort falsch" in r.text


def test_login_logout_roundtrip(client):
    r = client.post("/login", data={"password": "pw"}, follow_redirects=False)
    assert r.status_code == 303
    r2 = client.get("/", follow_redirects=False)
    assert r2.status_code == 303
    assert r2.headers["location"].startswith("/week/")
    client.get("/logout")
    assert client.get("/", follow_redirects=False).headers["location"] == "/login"
```

- [ ] **Step 3: Rot laufen lassen**

Run: `cd server && python -m pytest tests/test_web_auth.py -v`
Expected: FAIL (404)

- [ ] **Step 4: Implementierung**

`server/app/web/__init__.py`: leer.

`server/app/web/routes.py`:
```python
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
```

`server/app/templates/base.html`:
```html
<!doctype html>
<html lang="de">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>TimePlan</title>
  <link rel="stylesheet" href="/static/style.css">
  <script src="/static/htmx.min.js" defer></script>
</head>
<body>
  <header class="topbar">
    <strong>TimePlan</strong>
    <nav>
      <a href="/">Wochenplan</a>
      <a href="/workers">Monteure</a>
      <a href="/logout">Abmelden</a>
    </nav>
  </header>
  <main>{% block content %}{% endblock %}</main>
</body>
</html>
```

`server/app/templates/login.html`:
```html
{% extends "base.html" %}
{% block content %}
<form method="post" action="/login" class="login">
  <h1>Anmeldung</h1>
  {% if error %}<p class="error">{{ error }}</p>{% endif %}
  <input type="password" name="password" placeholder="Passwort" autofocus>
  <button type="submit">Anmelden</button>
</form>
{% endblock %}
```

`server/app/static/style.css`:
```css
* { box-sizing: border-box; }
body { margin: 0; font-family: system-ui, sans-serif; color: #1f2937; }
.topbar { display: flex; justify-content: space-between; align-items: center;
  padding: .5rem 1rem; background: #1f2937; color: #fff; }
.topbar a { color: #cbd5e1; margin-left: 1rem; text-decoration: none; }
main { padding: 1rem; }
.error { color: #b91c1c; }
.login { max-width: 20rem; margin: 4rem auto; display: grid; gap: .5rem; }
table.grid { border-collapse: collapse; width: 100%; table-layout: fixed; }
.grid th, .grid td { border: 1px solid #d1d5db; padding: .25rem; vertical-align: top;
  font-size: .85rem; overflow: hidden; }
.grid th.worker { width: 9rem; text-align: left; background: #f3f4f6; }
.grid thead th { background: #e5e7eb; }
.grid td.cell { min-height: 3.5rem; height: 3.5rem; cursor: pointer; }
.grid tr.azubi td.cell { height: 1.6rem; min-height: 1.6rem; white-space: nowrap; }
.grid tr.separator td { background: #e5e7eb; font-weight: 600; }
.entry.text { color: #1d4ed8; }
.entry.text.tablet { color: #111827; }
.entry.drawing { color: #6b7280; font-style: italic; }
.weeknav { display: flex; gap: 1rem; align-items: center; margin-bottom: .75rem; }
.cell form { display: grid; gap: .25rem; }
.cell textarea, .cell input[type=text], .cell select { width: 100%; font: inherit; }
.workers-page table { border-collapse: collapse; }
.workers-page td, .workers-page th { border: 1px solid #d1d5db; padding: .3rem; }
.flash-error { background: #fee2e2; color: #b91c1c; padding: .5rem; margin-bottom: .5rem; }
```

In `server/app/main.py` erweitern — Imports oben:
```python
import pathlib

from starlette.middleware.sessions import SessionMiddleware
from starlette.staticfiles import StaticFiles
```
Nach den bestehenden `include_router`-Zeilen:
```python
    from .web.routes import web_router
    app.include_router(web_router)
    app.add_middleware(SessionMiddleware, secret_key=settings.secret_key)
    static_dir = pathlib.Path(__file__).resolve().parent / "static"
    app.mount("/static", StaticFiles(directory=str(static_dir)), name="static")
```

- [ ] **Step 5: Grün laufen lassen**

Run: `cd server && python -m pytest -v`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add server/app/web/ server/app/templates/ server/app/static/ server/app/main.py server/tests/test_web_auth.py
git commit -m "feat(webui): Session-Login, Base-Layout und Statics"
```

---

### Task 11: WebUI-Wochenraster (lesend)

**Files:**
- Create: `server/app/templates/week.html`, `server/app/templates/partials/cell.html`, `server/tests/test_web_week.py`
- Modify: `server/app/web/routes.py`

**Interfaces:**
- Consumes: `weeklib.*` (Task 6), `entries_repo.entries_for_week` (Task 7), `workers.list_workers` (Task 4), `require_admin`/`templates` (Task 10)
- Produces: `GET /week/{week_id}` (volle Seite) und `GET /web/cells/{cell_id}` (Zell-Fragment). Template-Kontext von `partials/cell.html`: `worker` (dict), `cell_id` (str), `cell_entries` (list[Entry-dict]). Zellen tragen `id="cell-{{ cell_id }}"` und `hx-get="/web/cells/{{ cell_id }}/edit"` (Edit-Route folgt Task 12). Hilfsfunktion `_grid_context(request, week_id) -> dict` für Task 12/14 wiederverwendbar.

- [ ] **Step 1: Failing Tests schreiben**

`server/tests/test_web_week.py`:
```python
import pytest

from app.repos import entries as entries_repo
from app.repos import workers


@pytest.fixture()
def admin(client):
    client.post("/login", data={"password": "pw"})
    return client


def test_week_page_shows_grid(admin):
    conn = admin.app.state.db
    m = workers.create_worker(conn, "144", "Albrecht", "monteur")
    workers.create_worker(conn, "501", "Petersen", "azubi")
    entries_repo.create_entry(
        conn, f"2026-W31_{m['id']}_2026-07-30", "text", {"text": "Baustelle X"},
        "web", "web-admin", admin.app.state.settings)
    r = admin.get("/week/2026-W31")
    assert r.status_code == 200
    assert "144 Albrecht" in r.text
    assert "501 Petersen" in r.text
    assert "Mitarb. / Azubis" in r.text
    assert "Baustelle X" in r.text
    assert f'id="cell-2026-W31_{m["id"]}_2026-07-27"' in r.text


def test_cell_fragment(admin):
    conn = admin.app.state.db
    m = workers.create_worker(conn, "144", "Albrecht", "monteur")
    cell_id = f"2026-W31_{m['id']}_2026-07-30"
    r = admin.get(f"/web/cells/{cell_id}")
    assert r.status_code == 200
    assert f'id="cell-{cell_id}"' in r.text


def test_week_page_requires_login(client):
    assert client.get("/week/2026-W31", follow_redirects=False).status_code == 303
```

- [ ] **Step 2: Rot laufen lassen**

Run: `cd server && python -m pytest tests/test_web_week.py -v`
Expected: FAIL (404)

- [ ] **Step 3: Implementierung**

In `server/app/web/routes.py` Imports ergänzen:
```python
import collections

from ..repos import entries as entries_repo
from ..repos import workers as workers_repo
```

Routen/Hilfen anfügen:
```python
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
```

`server/app/templates/week.html`:
```html
{% extends "base.html" %}
{% block content %}
<div class="weeknav">
  <a href="/week/{{ prev_week }}">&larr; Vorherige</a>
  <strong>{{ week_id }}</strong>
  <span class="status">[{{ week.status }}]</span>
  <a href="/week/{{ next_week }}">Nächste &rarr;</a>
  <a href="/week/{{ current_week }}">Heute</a>
</div>
<table class="grid" data-week="{{ week_id }}">
  <thead>
    <tr>
      <th class="worker">Monteur</th>
      {% for label in day_labels %}<th>{{ label }}</th>{% endfor %}
    </tr>
  </thead>
  <tbody>
    {% for worker in monteure %}
    <tr>
      <th class="worker">{{ worker.number }} {{ worker.name }}</th>
      {% for date in dates %}
        {% with cell_id = make_cell_id(week_id, worker.id, date),
                cell_entries = by_cell[make_cell_id(week_id, worker.id, date)] %}
          {% include "partials/cell.html" %}
        {% endwith %}
      {% endfor %}
    </tr>
    {% endfor %}
    <tr class="separator"><td colspan="8">Mitarb. / Azubis</td></tr>
    {% for worker in azubis %}
    <tr class="azubi">
      <th class="worker">{{ worker.number }} {{ worker.name }}</th>
      {% for date in dates %}
        {% with cell_id = make_cell_id(week_id, worker.id, date),
                cell_entries = by_cell[make_cell_id(week_id, worker.id, date)] %}
          {% include "partials/cell.html" %}
        {% endwith %}
      {% endfor %}
    </tr>
    {% endfor %}
  </tbody>
</table>
{% endblock %}
```

`server/app/templates/partials/cell.html`:
```html
<td class="cell" id="cell-{{ cell_id }}"
    hx-get="/web/cells/{{ cell_id }}/edit" hx-trigger="click"
    hx-target="this" hx-swap="outerHTML">
  {% for entry in cell_entries %}
    {% if entry.type == "text" %}
      <div class="entry text {{ entry.author_type }}">{{ entry.content.text }}</div>
    {% else %}
      <div class="entry drawing">&#9998; Zeichnung</div>
    {% endif %}
  {% endfor %}
</td>
```

Hinweis: Jinja2 erlaubt `{% with a = x, b = y %}` mit Komma-Liste; `by_cell` ist ein defaultdict, fehlende Zellen ergeben `[]`.

- [ ] **Step 4: Grün laufen lassen**

Run: `cd server && python -m pytest -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/app/templates/ server/app/web/routes.py server/tests/test_web_week.py
git commit -m "feat(webui): Wochenraster mit Monteur- und Azubi-Block"
```

---

### Task 12: WebUI-Zellbearbeitung (htmx) inkl. Azubi-Schnellauswahl

**Files:**
- Create: `server/app/templates/partials/cell_edit.html`, `server/tests/test_web_cells.py`
- Modify: `server/app/web/routes.py`

**Interfaces:**
- Consumes: `_cell_context`, `templates` (Task 11), `entries_repo.*` (Task 7), `hub.broadcast` (Task 9)
- Produces: `GET /web/cells/{cell_id}/edit` (Edit-Fragment), `POST /web/cells/{cell_id}/entries` (Formfeld `text`; bei Azubi zusätzlich optionales Feld `monteur` — gewählter Monteur-Name wird als Text übernommen, wenn `text` leer), `POST /web/entries/{entry_id}` (Felder `text`, `base_revision`), `POST /web/entries/{entry_id}/delete`. Alle geben das Zell-Fragment (`partials/cell.html`) zurück und broadcasten `cell.updated`.

- [ ] **Step 1: Failing Tests schreiben**

`server/tests/test_web_cells.py`:
```python
import pytest

from app.repos import workers


@pytest.fixture()
def admin(client):
    client.post("/login", data={"password": "pw"})
    return client


def _cell(conn, category="monteur"):
    w = workers.create_worker(conn, "144" if category == "monteur" else "501",
                              "Test", category)
    return w, f"2026-W31_{w['id']}_2026-07-30"


def test_edit_fragment_monteur_has_textarea(admin):
    _, cell_id = _cell(admin.app.state.db)
    r = admin.get(f"/web/cells/{cell_id}/edit")
    assert r.status_code == 200
    assert "<textarea" in r.text


def test_edit_fragment_azubi_has_monteur_select(admin):
    conn = admin.app.state.db
    workers.create_worker(conn, "144", "Albrecht", "monteur")
    _, cell_id = _cell(conn, "azubi")
    r = admin.get(f"/web/cells/{cell_id}/edit")
    assert "<select" in r.text
    assert "144 Albrecht" in r.text


def test_create_update_delete_entry_via_web(admin, device_headers):
    conn = admin.app.state.db
    _, cell_id = _cell(conn)
    r = admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Neu"})
    assert r.status_code == 200
    assert "Neu" in r.text
    api = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    entry = api["entries"][0]
    assert entry["author_type"] == "web"
    r2 = admin.post(f"/web/entries/{entry['id']}",
                    data={"text": "Geändert", "base_revision": entry["revision"]})
    assert "Geändert" in r2.text
    r3 = admin.post(f"/web/entries/{entry['id']}/delete")
    assert r3.status_code == 200
    assert "Geändert" not in r3.text


def test_azubi_quickpick_creates_text(admin):
    conn = admin.app.state.db
    workers.create_worker(conn, "144", "Albrecht", "monteur")
    _, cell_id = _cell(conn, "azubi")
    r = admin.post(f"/web/cells/{cell_id}/entries",
                   data={"text": "", "monteur": "144 Albrecht"})
    assert r.status_code == 200
    assert "144 Albrecht" in r.text
```

- [ ] **Step 2: Rot laufen lassen**

Run: `cd server && python -m pytest tests/test_web_cells.py -v`
Expected: FAIL (404)

- [ ] **Step 3: Implementierung**

`server/app/templates/partials/cell_edit.html`:
```html
<td class="cell editing" id="cell-{{ cell_id }}">
  {% if error %}<p class="error">{{ error }}</p>{% endif %}
  {% for entry in cell_entries if entry.type == "text" %}
  <form hx-post="/web/entries/{{ entry.id }}" hx-target="#cell-{{ cell_id }}"
        hx-swap="outerHTML">
    <input type="hidden" name="base_revision" value="{{ entry.revision }}">
    <textarea name="text" rows="2">{{ entry.content.text }}</textarea>
    <button type="submit">Speichern</button>
    <button type="button" hx-post="/web/entries/{{ entry.id }}/delete"
            hx-target="#cell-{{ cell_id }}" hx-swap="outerHTML">Löschen</button>
  </form>
  {% endfor %}
  {% for entry in cell_entries if entry.type == "drawing" %}
  <div class="entry drawing">&#9998; Zeichnung (nur am Tablet bearbeitbar)
    <button type="button" hx-post="/web/entries/{{ entry.id }}/delete"
            hx-target="#cell-{{ cell_id }}" hx-swap="outerHTML">Löschen</button>
  </div>
  {% endfor %}
  <form hx-post="/web/cells/{{ cell_id }}/entries" hx-target="#cell-{{ cell_id }}"
        hx-swap="outerHTML">
    {% if worker.category == "azubi" %}
    <select name="monteur">
      <option value="">— Monteur wählen —</option>
      {% for m in monteure %}
      <option value="{{ m.number }} {{ m.name }}">{{ m.number }} {{ m.name }}</option>
      {% endfor %}
    </select>
    <input type="text" name="text" placeholder="oder freier Text">
    {% else %}
    <textarea name="text" rows="2" placeholder="Neuer Hinweis"></textarea>
    {% endif %}
    <button type="submit">Hinzufügen</button>
    <button type="button" hx-get="/web/cells/{{ cell_id }}"
            hx-target="#cell-{{ cell_id }}" hx-swap="outerHTML">Abbrechen</button>
  </form>
</td>
```

In `server/app/web/routes.py` anfügen (Import oben ergänzen: `from fastapi import Form` ist schon da; zusätzlich `from .. import db`):
```python
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
```

- [ ] **Step 4: Grün laufen lassen**

Run: `cd server && python -m pytest -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/app/templates/partials/cell_edit.html server/app/web/routes.py server/tests/test_web_cells.py
git commit -m "feat(webui): Inline-Zellbearbeitung mit Azubi-Schnellauswahl"
```

---

### Task 13: WebUI-Monteurverwaltung

**Files:**
- Create: `server/app/templates/workers.html`, `server/tests/test_web_workers.py`
- Modify: `server/app/web/routes.py`

**Interfaces:**
- Consumes: `workers_repo.create_worker/update_worker/list_workers` (Task 4), `hub.broadcast` (Task 9), `templates`/`require_admin` (Task 10)
- Produces: `GET /workers` (Seite), `POST /workers` (Felder `number, name, category`), `POST /workers/{worker_id}` (Felder `number, name, position, active` als Checkbox). Beide POSTs redirecten auf `/workers` (303); Fehler (LimitExceeded) via Query-Param `?error=…`. Broadcast `workers.updated` nach jeder Mutation.

- [ ] **Step 1: Failing Tests schreiben**

`server/tests/test_web_workers.py`:
```python
import pytest

from app.repos import workers


@pytest.fixture()
def admin(client):
    client.post("/login", data={"password": "pw"})
    return client


def test_workers_page_lists_workers(admin):
    workers.create_worker(admin.app.state.db, "144", "Albrecht", "monteur")
    r = admin.get("/workers")
    assert r.status_code == 200
    assert "144" in r.text and "Albrecht" in r.text


def test_add_worker_via_form(admin):
    r = admin.post("/workers", data={"number": "150", "name": "Neu",
                                     "category": "monteur"},
                   follow_redirects=False)
    assert r.status_code == 303
    assert "Neu" in admin.get("/workers").text


def test_limit_shows_error(admin):
    conn = admin.app.state.db
    for i in range(15):
        workers.create_worker(conn, str(100 + i), f"M{i}", "monteur")
    r = admin.post("/workers", data={"number": "999", "name": "Zuviel",
                                     "category": "monteur"},
                   follow_redirects=False)
    assert r.status_code == 303
    followed = admin.get(r.headers["location"])
    assert "Maximal 15" in followed.text


def test_deactivate_worker(admin):
    w = workers.create_worker(admin.app.state.db, "144", "Albrecht", "monteur")
    admin.post(f"/workers/{w['id']}", data={"number": "144", "name": "Albrecht",
                                            "position": "1"})  # ohne active-Feld
    assert workers.get_worker(admin.app.state.db, w["id"])["active"] is False
```

- [ ] **Step 2: Rot laufen lassen**

Run: `cd server && python -m pytest tests/test_web_workers.py -v`
Expected: FAIL (404)

- [ ] **Step 3: Implementierung**

`server/app/templates/workers.html`:
```html
{% extends "base.html" %}
{% block content %}
<div class="workers-page">
  <h1>Monteure &amp; Azubis</h1>
  {% if error %}<div class="flash-error">{{ error }}</div>{% endif %}
  {% for group, title in [(monteure, "Monteure (max. 15 aktiv)"),
                          (azubis, "Azubis (max. 10 aktiv)")] %}
  <h2>{{ title }}</h2>
  <table>
    <tr><th>MA-Nr.</th><th>Name</th><th>Pos.</th><th>Aktiv</th><th></th></tr>
    {% for w in group %}
    <tr>
      <form method="post" action="/workers/{{ w.id }}">
        <td><input name="number" value="{{ w.number }}" size="5"></td>
        <td><input name="name" value="{{ w.name }}"></td>
        <td><input name="position" value="{{ w.position }}" size="3"></td>
        <td><input type="checkbox" name="active" value="1"
                   {% if w.active %}checked{% endif %}></td>
        <td><button type="submit">Speichern</button></td>
      </form>
    </tr>
    {% endfor %}
  </table>
  {% endfor %}
  <h2>Neu anlegen</h2>
  <form method="post" action="/workers">
    <input name="number" placeholder="MA-Nr." size="5" required>
    <input name="name" placeholder="Name" required>
    <select name="category">
      <option value="monteur">Monteur</option>
      <option value="azubi">Azubi</option>
    </select>
    <button type="submit">Anlegen</button>
  </form>
</div>
{% endblock %}
```

In `server/app/web/routes.py` anfügen:
```python
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
```

Hinweis: HTML-Formulare in Tabellenzeilen (`<form>` innerhalb `<tr>`) sind formal unsauber, funktionieren aber in allen Browsern; wer es strikt will, nutzt das `form`-Attribut. Für das MVP genügt die einfache Variante.

- [ ] **Step 4: Grün laufen lassen**

Run: `cd server && python -m pytest -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/app/templates/workers.html server/app/web/routes.py server/tests/test_web_workers.py
git commit -m "feat(webui): Monteurverwaltung mit 15/10-Limits"
```

---

### Task 14: Live-Aktualisierung im Browser

**Files:**
- Create: `server/app/static/live.js`, `server/tests/test_live_assets.py`
- Modify: `server/app/templates/week.html`

**Interfaces:**
- Consumes: WS-Endpunkt `?client=web` (Task 9), Zell-Fragment-Route (Task 11), htmx (Task 10)
- Produces: Wochenseite verbindet sich per WebSocket; `cell.updated` lädt die betroffene Zelle nach (außer sie wird gerade bearbeitet), `workers.updated`/`week.updated` laden die Seite neu. Auto-Reconnect alle 3 s.

- [ ] **Step 1: Failing Test schreiben**

`server/tests/test_live_assets.py`:
```python
import pytest

from app.repos import workers


@pytest.fixture()
def admin(client):
    client.post("/login", data={"password": "pw"})
    return client


def test_live_js_served(client):
    r = client.get("/static/live.js")
    assert r.status_code == 200
    assert "cell.updated" in r.text


def test_week_page_includes_live_js(admin):
    workers.create_worker(admin.app.state.db, "144", "M", "monteur")
    assert "/static/live.js" in admin.get("/week/2026-W31").text
```

- [ ] **Step 2: Rot laufen lassen**

Run: `cd server && python -m pytest tests/test_live_assets.py -v`
Expected: FAIL (404)

- [ ] **Step 3: Implementierung**

`server/app/static/live.js`:
```javascript
(function () {
  function connect() {
    var proto = location.protocol === "https:" ? "wss" : "ws";
    var ws = new WebSocket(proto + "://" + location.host + "/ws/v1?client=web");
    ws.onmessage = function (msg) {
      var ev = JSON.parse(msg.data);
      if (ev.event === "cell.updated") {
        var el = document.getElementById("cell-" + ev.cell_id);
        if (el && !el.classList.contains("editing")) {
          htmx.ajax("GET", "/web/cells/" + ev.cell_id,
                    { target: el, swap: "outerHTML" });
        }
      } else if (ev.event === "workers.updated" || ev.event === "week.updated") {
        location.reload();
      }
    };
    ws.onclose = function () { setTimeout(connect, 3000); };
  }
  if (document.querySelector("table.grid")) { connect(); }
})();
```

In `server/app/templates/week.html` direkt vor `{% endblock %}` einfügen:
```html
<script src="/static/live.js" defer></script>
```

- [ ] **Step 4: Grün laufen lassen**

Run: `cd server && python -m pytest -v`
Expected: PASS

- [ ] **Step 5: Manueller Ende-zu-Ende-Check (zwei Browserfenster)**

```bash
cd server && TIMEPLAN_DB=./data/dev.db python -m app.seed \
  && TIMEPLAN_DB=./data/dev.db .venv/bin/uvicorn app.main:create_app --factory --port 8000
```
Browser: zweimal `http://localhost:8000` öffnen, anmelden (Passwort `admin`), in Fenster 1 einen Zelltext speichern → Fenster 2 zeigt ihn ohne Reload innerhalb ~1 s. Ergebnis im Commit-Text vermerken.

- [ ] **Step 6: Commit**

```bash
git add server/app/static/live.js server/app/templates/week.html server/tests/test_live_assets.py
git commit -m "feat(webui): Live-Zellaktualisierung über WebSocket"
```

---

### Task 15: Container, Compose & Portainer-Deployment

**Files:**
- Create: `server/Dockerfile`, `server/.dockerignore`, `docker-compose.yml`, `README.md`

**Interfaces:**
- Consumes: gesamte App (Task 2–14), Env-Variablen aus dem API-Vertrag (Task 1)
- Produces: `docker compose up` startet den Stack; Portainer kann das Repo als Git-Stack deployen. Volume `timeplan-data` unter `/data`.

- [ ] **Step 1: Docker-Dateien schreiben**

`server/Dockerfile`:
```dockerfile
FROM python:3.12-slim
WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
ENV TIMEPLAN_DB=/data/timeplan.db
EXPOSE 8000
CMD ["uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000"]
```

`server/.dockerignore`:
```text
.venv
tests
__pycache__
data
```

`docker-compose.yml` (Repo-Root):
```yaml
services:
  timeplan:
    build: ./server
    ports:
      - "8000:8000"
    environment:
      TIMEPLAN_SECRET_KEY: ${TIMEPLAN_SECRET_KEY:?TIMEPLAN_SECRET_KEY setzen}
      TIMEPLAN_ADMIN_PASSWORD: ${TIMEPLAN_ADMIN_PASSWORD:?TIMEPLAN_ADMIN_PASSWORD setzen}
      TIMEPLAN_DEVICE_TOKENS: ${TIMEPLAN_DEVICE_TOKENS:-}
    volumes:
      - timeplan-data:/data
    restart: unless-stopped
volumes:
  timeplan-data:
```

- [ ] **Step 2: README schreiben**

`README.md` (Repo-Root):
````markdown
# TimePlan

Wochenplaner für Monteure: Android-Tablet mit S-Pen-Handschrift +
Python-Server mit WebUI. Details: `docs/superpowers/specs/`,
API: `docs/api-contract.md`.

## Server lokal starten

```bash
cd server
python -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
TIMEPLAN_DB=./data/dev.db .venv/bin/python -m app.seed
TIMEPLAN_DB=./data/dev.db .venv/bin/uvicorn app.main:create_app --factory --port 8000
```

WebUI: `http://localhost:8000` (Passwort default `admin`).
Tests: `cd server && python -m pytest`.

## Deployment über Portainer (Git-Stack)

1. Portainer → Stacks → **Add stack** → *Repository*
2. Repository-URL dieses Repos, Compose path `docker-compose.yml`
3. Environment variables setzen:
   - `TIMEPLAN_SECRET_KEY` – langer Zufallswert
   - `TIMEPLAN_ADMIN_PASSWORD` – WebUI-Login
   - `TIMEPLAN_DEVICE_TOKENS` – z. B. `tablet-01:<zufallstoken>`
4. Deploy. Optional GitOps-Polling für Auto-Redeploy aktivieren.

Secrets stehen **nie** im Repo – nur im Portainer-Stack.
````

- [ ] **Step 3: Build & Smoke-Test**

```bash
docker build -t timeplan-server ./server
docker run --rm -d -p 8001:8000 \
  -e TIMEPLAN_SECRET_KEY=smoke -e TIMEPLAN_ADMIN_PASSWORD=smoke \
  --name timeplan-smoke timeplan-server
sleep 2 && curl -s http://localhost:8001/api/v1/status
docker rm -f timeplan-smoke
```
Expected: `{"status":"ok","revision":0}`

- [ ] **Step 4: Kompletter Testlauf**

Run: `cd server && python -m pytest -v`
Expected: PASS (alle Tests)

- [ ] **Step 5: Commit**

```bash
git add server/Dockerfile server/.dockerignore docker-compose.yml README.md
git commit -m "feat: Docker-Image und Portainer-Stack-Deployment"
```

---

## Nach diesem Plan

Meilenstein 1+2 sind damit vollständig. Es folgen (je ein eigener Plan, nach Review des laufenden Stacks):

1. **Android App-Basis** (M3): Projekt-Setup, Konfigseite, Wochenraster mit Serverdaten
2. **Live-Sync + Stifteingabe** (M4+M5)
3. **Offline & Upload** (M6) — inkl. Server-Vorschaubilder + Konflikt-UI in der WebUI
4. **PDF & Archiv** (M7), **Betrieb/Kiosk** (M8)
