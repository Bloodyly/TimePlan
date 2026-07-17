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
