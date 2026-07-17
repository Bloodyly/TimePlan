def test_workers_requires_token(client):
    assert client.get("/api/v1/workers").status_code == 401
    r = client.get("/api/v1/workers", headers={"Authorization": "Bearer falsch"})
    assert r.status_code == 401


def test_workers_with_token(client, device_headers):
    r = client.get("/api/v1/workers", headers=device_headers)
    assert r.status_code == 200
    assert r.json() == {"workers": []}


def test_last_seen_updated(client, device_headers):
    client.get("/api/v1/workers", headers=device_headers)
    row = client.app.state.db.execute(
        "SELECT * FROM devices WHERE id='tablet-01'"
    ).fetchone()
    assert row is not None
    assert row["last_seen"] is not None
