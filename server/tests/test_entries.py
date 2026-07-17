import pytest

from app import db
from app.repos import workers


@pytest.fixture()
def setup(client, device_headers):
    conn = client.app.state.db
    monteur = workers.create_worker(conn, "144", "Monteur", "monteur")
    azubi = workers.create_worker(conn, "501", "Azubi", "azubi")
    def cell(worker, date="2026-07-30"):
        return f"2026-W31_{worker['id']}_{date}"
    return client, device_headers, conn, monteur, azubi, cell


def test_create_text_entry(setup):
    client, hdr, conn, monteur, azubi, cell = setup
    r = client.post("/api/v1/entries", headers=hdr, json={
        "cell_id": cell(monteur), "type": "text", "content": {"text": "Baustelle A"}})
    assert r.status_code == 201
    entry = r.json()["entry"]
    assert entry["author_type"] == "tablet"
    assert entry["author_id"] == "tablet-01"
    week = client.get("/api/v1/weeks/2026-W31", headers=hdr).json()
    assert [e["id"] for e in week["entries"]] == [entry["id"]]


def test_drawing_in_azubi_cell_rejected(setup):
    client, hdr, conn, monteur, azubi, cell = setup
    r = client.post("/api/v1/entries", headers=hdr, json={
        "cell_id": cell(azubi), "type": "drawing",
        "content": {"canvas_width": 100, "canvas_height": 50, "strokes": [
            {"color": "#000000", "base_width": 2.0,
             "points": [{"x": 1, "y": 1, "pressure": 0.5, "time": 0}]}]}})
    assert r.status_code == 422


def test_oversize_content_rejected(setup):
    client, hdr, conn, monteur, azubi, cell = setup
    r = client.post("/api/v1/entries", headers=hdr, json={
        "cell_id": cell(monteur), "type": "text",
        "content": {"text": "x" * 3000}})
    assert r.status_code == 422  # Textlimit 2000 Zeichen


def test_update_with_stale_revision_conflicts(setup):
    client, hdr, conn, monteur, azubi, cell = setup
    entry = client.post("/api/v1/entries", headers=hdr, json={
        "cell_id": cell(monteur), "type": "text",
        "content": {"text": "v1"}}).json()["entry"]
    ok = client.put(f"/api/v1/entries/{entry['id']}", headers=hdr, json={
        "content": {"text": "v2"}, "base_revision": entry["revision"]})
    assert ok.status_code == 200
    stale = client.put(f"/api/v1/entries/{entry['id']}", headers=hdr, json={
        "content": {"text": "v3"}, "base_revision": entry["revision"]})
    assert stale.status_code == 409
    detail = stale.json()["detail"]
    conflict = client.app.state.db.execute(
        "SELECT * FROM entries WHERE id=?", (detail["conflict_entry_id"],)
    ).fetchone()
    assert conflict["conflict_of"] == entry["id"]


def test_locked_week_blocks_mutation(setup):
    client, hdr, conn, monteur, azubi, cell = setup
    from app import weeks as weeklib
    weeklib.get_or_create_week(conn, "2026-W31")
    weeklib.set_week_status(conn, "2026-W31", "LOCKED")
    r = client.post("/api/v1/entries", headers=hdr, json={
        "cell_id": cell(monteur), "type": "text", "content": {"text": "zu spät"}})
    assert r.status_code == 423


def test_delete_entry(setup):
    client, hdr, conn, monteur, azubi, cell = setup
    entry = client.post("/api/v1/entries", headers=hdr, json={
        "cell_id": cell(monteur), "type": "text",
        "content": {"text": "weg"}}).json()["entry"]
    assert client.delete(f"/api/v1/entries/{entry['id']}", headers=hdr).status_code == 204
    assert client.delete(f"/api/v1/entries/{entry['id']}", headers=hdr).status_code == 404
