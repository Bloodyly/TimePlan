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
