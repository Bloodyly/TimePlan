import pytest

from app.repos import app_settings


@pytest.fixture()
def admin(client):
    client.post("/login", data={"password": "pw"})
    return client


def test_settings_page_requires_login(client):
    assert client.get("/settings", follow_redirects=False).status_code == 303


def test_settings_update_requires_login(client):
    r = client.post("/settings", data={"show_saturday": "1"}, follow_redirects=False)
    assert r.status_code == 303
    assert r.headers["location"] == "/login"
    assert app_settings.get_display_settings(client.app.state.db) == {
        "show_saturday": True, "show_sunday": True}


def test_settings_page_shows_current_values(admin):
    r = admin.get("/settings")
    assert r.status_code == 200
    assert "Samstag anzeigen" in r.text
    assert "Sonntag anzeigen" in r.text


def test_settings_update_persists_and_redirects(admin):
    r = admin.post("/settings", data={"show_saturday": "1"}, follow_redirects=False)
    assert r.status_code == 303
    assert r.headers["location"] == "/settings"
    assert app_settings.get_display_settings(admin.app.state.db) == {
        "show_saturday": True, "show_sunday": False}


def test_settings_update_both_unchecked_disables_both(admin):
    admin.post("/settings", data={})
    assert app_settings.get_display_settings(admin.app.state.db) == {
        "show_saturday": False, "show_sunday": False}
