from app.repos import workers


def test_ws_receives_cell_update(client, device_headers):
    conn = client.app.state.db
    monteur = workers.create_worker(conn, "144", "M", "monteur")
    cell_id = f"2026-W31_{monteur['id']}_2026-07-30"
    with client.websocket_connect("/ws/v1?client=web") as ws:
        r = client.post("/api/v1/entries", headers=device_headers, json={
            "cell_id": cell_id, "type": "text", "content": {"text": "hi"}})
        assert r.status_code == 201
        event = ws.receive_json()
        assert event["event"] == "cell.updated"
        assert event["cell_id"] == cell_id
        assert event["revision"] == r.json()["entry"]["revision"]


def test_ws_rejects_bad_token(client):
    import pytest
    from starlette.websockets import WebSocketDisconnect
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect("/ws/v1?device_id=x&token=falsch") as ws:
            ws.receive_json()
