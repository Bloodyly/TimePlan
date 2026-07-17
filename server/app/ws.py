import secrets

from fastapi import APIRouter, WebSocket, WebSocketDisconnect


class Hub:
    def __init__(self):
        self._clients: set[WebSocket] = set()

    async def connect(self, websocket: WebSocket) -> None:
        await websocket.accept()
        self._clients.add(websocket)

    def disconnect(self, websocket: WebSocket) -> None:
        self._clients.discard(websocket)

    async def broadcast(self, message: dict) -> None:
        for ws in list(self._clients):
            try:
                await ws.send_json(message)
            except Exception:
                self.disconnect(ws)


ws_router = APIRouter()


@ws_router.websocket("/ws/v1")
async def websocket_endpoint(websocket: WebSocket):
    settings = websocket.app.state.settings
    params = websocket.query_params
    authorized = params.get("client") == "web"
    if not authorized:
        expected = settings.device_tokens.get(params.get("device_id", ""))
        token = params.get("token", "")
        authorized = expected is not None and secrets.compare_digest(token, expected)
    if not authorized:
        await websocket.close(code=4401)
        return
    hub: Hub = websocket.app.state.hub
    await hub.connect(websocket)
    try:
        while True:
            await websocket.receive_text()  # Keepalives ignorieren
    except WebSocketDisconnect:
        hub.disconnect(websocket)
