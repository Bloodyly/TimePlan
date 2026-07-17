from app.repos import workers


def test_sync_compacts_changes(client, device_headers):
    conn = client.app.state.db
    w = workers.create_worker(conn, "144", "Alt", "monteur")      # rev 1
    workers.update_worker(conn, w["id"], name="Neu")              # rev 2
    workers.create_worker(conn, "501", "Azubi", "azubi")          # rev 3
    r = client.get("/api/v1/sync?since=0", headers=device_headers)
    assert r.status_code == 200
    body = r.json()
    assert body["latest_revision"] == 3
    assert len(body["changes"]) == 2  # w kompaktiert auf letzte Änderung
    first = body["changes"][0]
    assert first["entity_id"] == w["id"]
    assert first["revision"] == 2
    assert first["action"] == "updated"


def test_sync_since_filters(client, device_headers):
    conn = client.app.state.db
    workers.create_worker(conn, "144", "M", "monteur")  # rev 1
    r = client.get("/api/v1/sync?since=1", headers=device_headers)
    assert r.json() == {"latest_revision": 1, "changes": []}
