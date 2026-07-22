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


def test_week_page_has_cell_dialog_shell(admin):
    r = admin.get("/week/2026-W31")
    assert r.status_code == 200
    assert '<dialog id="cell-dialog">' in r.text
    assert "showModal" in r.text
    assert 'id="cell-dialog-body"' in r.text


def test_week_page_hides_saturday_when_disabled(admin):
    from app.repos import app_settings
    app_settings.update_display_settings(admin.app.state.db, False, True)
    r = admin.get("/week/2026-W31")
    assert r.status_code == 200
    assert "Sa 01.08." not in r.text
    assert "So 02.08." in r.text


def test_week_page_labels_sunday_correctly_when_saturday_hidden(admin):
    # Regression guard: Sonntag darf nicht als "Sa" beschriftet werden, nur
    # weil er an Sa's alter Listenposition landet.
    from app.repos import app_settings
    app_settings.update_display_settings(admin.app.state.db, False, True)
    r = admin.get("/week/2026-W31")
    assert "So 02.08." in r.text
    assert "Sa 02.08." not in r.text


def test_week_page_includes_swipe_script(admin):
    r = admin.get("/week/2026-W31")
    assert r.status_code == 200
    assert '<script src="/static/swipe.js" defer></script>' in r.text


def test_week_page_nav_links_have_swipe_target_classes(admin):
    r = admin.get("/week/2026-W31")
    assert 'class="weeknav-prev"' in r.text
    assert 'class="weeknav-next"' in r.text


def test_base_layout_opts_into_view_transitions(admin):
    r = admin.get("/week/2026-W31")
    assert '<meta name="view-transition" content="same-origin">' in r.text


def test_week_page_includes_dragfill_script(admin):
    r = admin.get("/week/2026-W31")
    assert r.status_code == 200
    assert '<script src="/static/dragfill.js" defer></script>' in r.text


def test_week_page_has_dragfill_arrow_overlay(admin):
    r = admin.get("/week/2026-W31")
    assert 'id="dragfill-arrow-overlay"' in r.text
    assert 'id="dragfill-arrow-line"' in r.text
    assert 'id="dragfill-arrow-head"' in r.text


def test_week_page_renders_drawing_as_inline_svg(admin):
    conn = admin.app.state.db
    m = workers.create_worker(conn, "144", "Albrecht", "monteur")
    cell_id = f"2026-W31_{m['id']}_2026-07-30"
    entries_repo.create_entry(
        conn, cell_id, "drawing",
        {"canvas_width": 300, "canvas_height": 120,
         "strokes": [{"color": "#201A10", "base_width": 4.0,
                      "points": [{"x": 10.0, "y": 20.0, "pressure": 0.5, "time": 0},
                                 {"x": 15.0, "y": 22.0, "pressure": 0.6, "time": 12}]}]},
        "tablet", "tablet-01", admin.app.state.settings)
    r = admin.get("/week/2026-W31")
    assert r.status_code == 200
    assert '<svg class="entry-drawing"' in r.text
    assert 'viewBox="0 0 300 120"' in r.text
    assert "10.0,20.0" in r.text
    assert "Zeichnung" not in r.text


def test_week_page_stacks_text_and_drawing_in_the_same_cell(admin):
    conn = admin.app.state.db
    m = workers.create_worker(conn, "144", "Albrecht", "monteur")
    cell_id = f"2026-W31_{m['id']}_2026-07-30"
    entries_repo.create_entry(
        conn, cell_id, "text", {"text": "Hinweis"}, "web", "web-admin", admin.app.state.settings)
    entries_repo.create_entry(
        conn, cell_id, "drawing",
        {"canvas_width": 50, "canvas_height": 50,
         "strokes": [{"color": "#201A10", "base_width": 4.0,
                      "points": [{"x": 1.0, "y": 1.0, "pressure": 1.0, "time": 0}]}]},
        "tablet", "tablet-01", admin.app.state.settings)
    r = admin.get("/week/2026-W31")
    assert r.status_code == 200
    assert "Hinweis" in r.text
    assert '<svg class="entry-drawing"' in r.text
