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
