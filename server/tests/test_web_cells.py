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
