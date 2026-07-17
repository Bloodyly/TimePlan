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
