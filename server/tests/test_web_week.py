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


def test_week_page_hides_conflict_copies(admin):
    conn = admin.app.state.db
    settings = admin.app.state.settings
    m = workers.create_worker(conn, "144", "Albrecht", "monteur")
    cell_id = f"2026-W31_{m['id']}_2026-07-30"
    entry = entries_repo.create_entry(
        conn, cell_id, "text", {"text": "Original"}, "web", "web-admin", settings)
    with pytest.raises(entries_repo.ConflictError):
        entries_repo.update_entry(
            conn, entry["id"], {"text": "Konflikt-Version"},
            entry["revision"] - 1, "web", "web-admin", settings)
    r = admin.get("/week/2026-W31")
    assert r.status_code == 200
    assert r.text.count("Original") == 1
    assert "Konflikt-Version" not in r.text


def test_cell_fragment_hides_conflict_copies(admin):
    conn = admin.app.state.db
    settings = admin.app.state.settings
    m = workers.create_worker(conn, "144", "Albrecht", "monteur")
    cell_id = f"2026-W31_{m['id']}_2026-07-30"
    entry = entries_repo.create_entry(
        conn, cell_id, "text", {"text": "Original"}, "web", "web-admin", settings)
    with pytest.raises(entries_repo.ConflictError):
        entries_repo.update_entry(
            conn, entry["id"], {"text": "Konflikt-Version"},
            entry["revision"] - 1, "web", "web-admin", settings)
    r = admin.get(f"/web/cells/{cell_id}")
    assert r.status_code == 200
    assert r.text.count("Original") == 1
    assert "Konflikt-Version" not in r.text


def test_week_page_requires_login(client):
    assert client.get("/week/2026-W31", follow_redirects=False).status_code == 303
