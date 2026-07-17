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
