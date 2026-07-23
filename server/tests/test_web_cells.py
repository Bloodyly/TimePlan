import pytest

from app.repos import entries, workers


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


def test_edit_fragment_azubi_has_no_free_text_only_status_and_monteur_options(admin):
    conn = admin.app.state.db
    workers.create_worker(conn, "144", "Albrecht", "monteur")
    _, cell_id = _cell(conn, "azubi")
    r = admin.get(f"/web/cells/{cell_id}/edit")
    assert "<textarea" not in r.text
    assert "Schule" in r.text
    assert "Krank" in r.text
    assert "Urlaub" in r.text
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

    r2 = admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Geändert"})
    assert r2.status_code == 200
    api2 = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    assert len(api2["entries"]) == 1
    assert api2["entries"][0]["content"]["text"] == "Geändert"

    r3 = admin.post(f"/web/cells/{cell_id}/entries", data={"text": ""})
    assert r3.status_code == 200
    api3 = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    assert api3["entries"] == []


def test_monteur_autosave_updates_in_place_then_deletes_on_empty(admin, device_headers):
    conn = admin.app.state.db
    _, cell_id = _cell(conn)

    admin.post(f"/web/cells/{cell_id}/entries", data={"text": "A"})
    api = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    text_entries = [e for e in api["entries"] if e["type"] == "text"]
    assert len(text_entries) == 1
    assert text_entries[0]["content"]["text"] == "A"

    admin.post(f"/web/cells/{cell_id}/entries", data={"text": "B"})
    api2 = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    text_entries2 = [e for e in api2["entries"] if e["type"] == "text"]
    assert len(text_entries2) == 1
    assert text_entries2[0]["content"]["text"] == "B"

    admin.post(f"/web/cells/{cell_id}/entries", data={"text": ""})
    api3 = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    text_entries3 = [e for e in api3["entries"] if e["type"] == "text"]
    assert text_entries3 == []


def test_monteur_text_autosave_does_not_touch_drawing_entry(admin, device_headers):
    conn = admin.app.state.db
    settings = admin.app.state.settings
    _, cell_id = _cell(conn)

    drawing_content = {
        "canvas_width": 100, "canvas_height": 50,
        "strokes": [{"color": "#000000", "base_width": 2.0,
                     "points": [{"x": 1, "y": 1, "pressure": 0.5, "time": 0}]}],
    }
    drawing = entries.create_entry(conn, cell_id, "drawing", drawing_content,
                                   "tablet", "tablet-01", settings)

    admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Notiz"})
    admin.post(f"/web/cells/{cell_id}/entries", data={"text": ""})

    api = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    drawing_entries = [e for e in api["entries"] if e["type"] == "drawing"]
    assert len(drawing_entries) == 1
    assert drawing_entries[0]["id"] == drawing["id"]
    assert drawing_entries[0]["content"] == drawing_content
    text_entries = [e for e in api["entries"] if e["type"] == "text"]
    assert text_entries == []


def test_azubi_picker_selects_status(admin, device_headers):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "azubi")
    r = admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Krank"})
    assert r.status_code == 200
    assert "Krank" in r.text
    api = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    assert len(api["entries"]) == 1
    assert api["entries"][0]["content"]["text"] == "Krank"


def test_azubi_picker_selects_monteur(admin):
    conn = admin.app.state.db
    workers.create_worker(conn, "144", "Albrecht", "monteur")
    _, cell_id = _cell(conn, "azubi")
    r = admin.post(f"/web/cells/{cell_id}/entries", data={"text": "144 Albrecht"})
    assert r.status_code == 200
    assert "144 Albrecht" in r.text


def test_azubi_picker_rejects_arbitrary_text(admin, device_headers):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "azubi")
    r = admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Beliebiger Text"})
    assert r.status_code == 200
    assert "Ungültige Auswahl" in r.text
    api = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    assert api["entries"] == []


def test_azubi_picker_replaces_existing_entry_instead_of_duplicating(admin, device_headers):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "azubi")
    admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Krank"})
    admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Urlaub"})
    api = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    assert len(api["entries"]) == 1
    assert api["entries"][0]["content"]["text"] == "Urlaub"


def test_azubi_picker_clear_deletes_entry(admin, device_headers):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "azubi")
    admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Schule"})
    r = admin.post(f"/web/cells/{cell_id}/entries", data={"text": ""})
    assert r.status_code == 200
    api = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    assert api["entries"] == []


def test_azubi_picker_consolidates_preexisting_duplicate_entries(admin, device_headers):
    # Simulates a cell that already holds more than one entry (e.g. written
    # by the device API, which does not enforce azubi single-entry semantics)
    # to guard against the picker only ever touching the first one.
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "azubi")
    settings = admin.app.state.settings
    entries.create_entry(conn, cell_id, "text", {"text": "Alt 1"}, "tablet", "tab-1", settings)
    entries.create_entry(conn, cell_id, "text", {"text": "Alt 2"}, "tablet", "tab-1", settings)

    r = admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Krank"})
    assert r.status_code == 200
    assert r.text.count("Krank") == 1
    api = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    assert len(api["entries"]) == 1
    assert api["entries"][0]["content"]["text"] == "Krank"

    r2 = admin.post(f"/web/cells/{cell_id}/entries", data={"text": ""})
    assert r2.status_code == 200
    api2 = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    assert api2["entries"] == []


def test_azubi_cell_targets_dialog_for_edit(admin):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "azubi")
    r = admin.get(f"/web/cells/{cell_id}")
    assert r.status_code == 200
    assert 'hx-target="#cell-dialog-body"' in r.text
    assert 'hx-swap="innerHTML"' in r.text


def test_monteur_cell_still_targets_itself_for_edit(admin):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "monteur")
    r = admin.get(f"/web/cells/{cell_id}")
    assert r.status_code == 200
    assert 'hx-target="this"' in r.text
    assert "cell-dialog-body" not in r.text


def test_azubi_edit_fragment_is_not_wrapped_in_td(admin):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "azubi")
    r = admin.get(f"/web/cells/{cell_id}/edit")
    assert r.status_code == 200
    assert "<td" not in r.text
    assert "cell-dialog" in r.text


def test_monteur_edit_fragment_still_wrapped_in_td(admin):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "monteur")
    r = admin.get(f"/web/cells/{cell_id}/edit")
    assert r.status_code == 200
    assert '<td class="cell editing"' in r.text


def test_azubi_picker_success_retargets_to_real_cell(admin):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "azubi")
    r = admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Krank"})
    assert r.status_code == 200
    assert r.headers["HX-Retarget"] == f"#cell-{cell_id}"
    assert r.headers["HX-Reswap"] == "outerHTML"


def test_azubi_picker_error_does_not_retarget(admin):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "azubi")
    r = admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Unsinn"})
    assert r.status_code == 200
    assert "HX-Retarget" not in r.headers
    assert "Ungültige Auswahl" in r.text


def test_azubi_picker_conflict_does_not_retarget(admin, monkeypatch):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "azubi")
    admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Schule"})

    def raise_conflict(*args, **kwargs):
        raise entries.ConflictError({"id": "e-fake"}, 1)

    monkeypatch.setattr(entries, "update_entry", raise_conflict)
    r = admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Krank"})
    assert r.status_code == 200
    assert "HX-Retarget" not in r.headers
    assert "Konflikt" in r.text


def test_fill_cells_fills_only_empty_targets(admin, device_headers):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "monteur")  # Do 2026-07-30
    admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Baustelle A"})

    # Zielzellen (alle nach dem Ursprung Do 30.07.): Fr (leer), Sa (bereits belegt), So (leer)
    worker_id = cell_id.split("_")[1]
    fr = f"2026-W31_{worker_id}_2026-07-31"
    sa = f"2026-W31_{worker_id}_2026-08-01"
    so = f"2026-W31_{worker_id}_2026-08-02"
    admin.post(f"/web/cells/{sa}/entries", data={"text": "Schon belegt"})

    r = admin.post(f"/web/cells/{cell_id}/fill",
                   json={"target_cell_ids": [fr, sa, so]})
    assert r.status_code == 200
    assert sorted(r.json()["filled"]) == sorted([fr, so])

    api = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    by_cell = {e["cell_id"]: e["content"]["text"] for e in api["entries"]}
    assert by_cell[fr] == "→"
    assert by_cell[so] == "→"
    assert by_cell[sa] == "Schon belegt"  # unverändert


def test_fill_cells_skips_targets_that_raise_conflict(admin, monkeypatch, device_headers):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "monteur")  # Do 2026-07-30
    admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Baustelle A"})
    worker_id = cell_id.split("_")[1]
    fr = f"2026-W31_{worker_id}_2026-07-31"

    def raise_conflict(*args, **kwargs):
        raise entries.ConflictError({"id": "e-fake"}, 1)

    monkeypatch.setattr(entries, "create_entry", raise_conflict)
    r = admin.post(f"/web/cells/{cell_id}/fill", json={"target_cell_ids": [fr]})
    assert r.status_code == 200
    assert r.json()["filled"] == []


def test_fill_cells_azubi_status_replicates_real_value(admin, device_headers):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "azubi")  # Do 2026-07-30
    admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Krank"})
    worker_id = cell_id.split("_")[1]
    target = f"2026-W31_{worker_id}_2026-07-31"  # Fr, nach dem Ursprung

    r = admin.post(f"/web/cells/{cell_id}/fill", json={"target_cell_ids": [target]})
    assert r.status_code == 200
    assert r.json()["filled"] == [target]

    api = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    entry = next(e for e in api["entries"] if e["cell_id"] == target)
    assert entry["content"]["text"] == "Krank"


def test_fill_cells_azubi_assignment_uses_text_arrow(admin, device_headers):
    conn = admin.app.state.db
    from app.repos import workers
    monteur = workers.create_worker(conn, "144", "Albrecht", "monteur")
    _, cell_id = _cell(conn, "azubi")  # Do 2026-07-30
    admin.post(f"/web/cells/{cell_id}/entries", data={"text": "144 Albrecht"})
    worker_id = cell_id.split("_")[1]
    target = f"2026-W31_{worker_id}_2026-07-31"  # Fr, nach dem Ursprung

    r = admin.post(f"/web/cells/{cell_id}/fill", json={"target_cell_ids": [target]})
    assert r.status_code == 200

    api = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    entry = next(e for e in api["entries"] if e["cell_id"] == target)
    assert entry["content"]["text"] == "----->"


def test_fill_cells_rejects_empty_origin(admin):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "monteur")
    r = admin.post(f"/web/cells/{cell_id}/fill", json={"target_cell_ids": []})
    assert r.status_code == 422


def test_fill_cells_rejects_drawing_only_origin(admin):
    # A cell that only holds a tablet drawing (no text) has nothing for
    # drag-fill to propagate - it must be treated the same as an empty
    # origin (422), not crash trying to read a "text" key that a drawing's
    # content dict doesn't have.
    conn = admin.app.state.db
    settings = admin.app.state.settings
    _, cell_id = _cell(conn, "monteur")
    drawing_content = {
        "canvas_width": 100, "canvas_height": 50,
        "strokes": [{"color": "#000000", "base_width": 2.0,
                     "points": [{"x": 1, "y": 1, "pressure": 0.5, "time": 0}]}],
    }
    entries.create_entry(conn, cell_id, "drawing", drawing_content,
                         "tablet", "tablet-01", settings)
    r = admin.post(f"/web/cells/{cell_id}/fill", json={"target_cell_ids": []})
    assert r.status_code == 422


def test_fill_cells_uses_text_entry_even_when_a_drawing_entry_is_also_present(admin, device_headers):
    # Monteur cells can independently hold both a web-authored text entry and
    # a tablet-authored drawing entry. Drag-fill must always pick the text
    # entry as its origin value regardless of which of the two entries was
    # created first (entries are otherwise ordered by created_at, so the
    # drawing could easily sort before the text).
    conn = admin.app.state.db
    settings = admin.app.state.settings
    _, cell_id = _cell(conn, "monteur")  # Do 2026-07-30
    drawing_content = {
        "canvas_width": 100, "canvas_height": 50,
        "strokes": [{"color": "#000000", "base_width": 2.0,
                     "points": [{"x": 1, "y": 1, "pressure": 0.5, "time": 0}]}],
    }
    entries.create_entry(conn, cell_id, "drawing", drawing_content,
                         "tablet", "tablet-01", settings)
    admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Baustelle A"})

    worker_id = cell_id.split("_")[1]
    target = f"2026-W31_{worker_id}_2026-07-31"  # Fr, nach dem Ursprung

    r = admin.post(f"/web/cells/{cell_id}/fill", json={"target_cell_ids": [target]})
    assert r.status_code == 200
    assert r.json()["filled"] == [target]

    api = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    entry = next(e for e in api["entries"] if e["cell_id"] == target)
    assert entry["content"]["text"] == "→"


def test_fill_cells_ignores_targets_in_different_worker_row(admin, device_headers):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "monteur")  # Do 2026-07-30
    admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Baustelle A"})
    other, _ = _cell(conn, "monteur")
    # Bewusst NACH dem Ursprungsdatum (Fr 31.07.), damit dieser Test wirklich
    # die Zeilen-/Wochen-Isolation prüft und nicht zufällig durch die
    # Datums-Prüfung durchfällt (die würde ein Ziel vor/am Ursprungsdatum
    # ohnehin schon überspringen, unabhängig vom Worker).
    other_target = f"2026-W31_{other['id']}_2026-07-31"

    r = admin.post(f"/web/cells/{cell_id}/fill",
                   json={"target_cell_ids": [other_target]})
    assert r.status_code == 200
    assert r.json()["filled"] == []

    api = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    assert all(e["cell_id"] != other_target for e in api["entries"])


def test_fill_cells_ignores_targets_in_different_week(admin, device_headers):
    conn = admin.app.state.db
    worker, cell_id = _cell(conn, "monteur")  # 2026-W31, Do 2026-07-30
    admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Baustelle A"})
    other_week_target = f"2026-W32_{worker['id']}_2026-08-06"  # gleicher Worker, andere Woche

    r = admin.post(f"/web/cells/{cell_id}/fill",
                   json={"target_cell_ids": [other_week_target]})
    assert r.status_code == 200
    assert r.json()["filled"] == []


def test_fill_cells_ignores_targets_left_of_origin(admin, device_headers):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "monteur")  # cell_id ist Do 2026-07-30 (siehe _cell-Helper)
    admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Baustelle A"})
    worker_id = cell_id.split("_")[1]
    earlier = f"2026-W31_{worker_id}_2026-07-27"  # Mo, liegt vor dem Ursprung

    r = admin.post(f"/web/cells/{cell_id}/fill",
                   json={"target_cell_ids": [earlier]})
    assert r.status_code == 200
    assert r.json()["filled"] == []


def test_fill_cells_requires_login(client):
    r = client.post("/web/cells/2026-W31_w-x_2026-07-28/fill",
                    json={"target_cell_ids": []}, follow_redirects=False)
    assert r.status_code == 303
