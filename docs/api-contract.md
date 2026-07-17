# TimePlan API-Vertrag v1

Basis-URL: `http://<server>:8000`. Kein TLS (internes Netz).
Alle Zeiten UTC, ISO-8601. Alle IDs sind Strings.

## Authentifizierung

- Tablet/API: `Authorization: Bearer <token>`. Tokens kommen aus der
  Env-Variable `TIMEPLAN_DEVICE_TOKENS` (`geraet-id:token,geraet-id2:token2`).
  Fehlend/falsch → `401`.
- WebUI: Session-Cookie nach Login (`TIMEPLAN_ADMIN_PASSWORD`).
- `GET /api/v1/status` ist ohne Auth erreichbar (Health-Check).

## Kernbegriffe

- `week_id`: ISO-Woche, z. B. `2026-W31`
- `worker_id`: `w-<8 hex>`; Kategorie `monteur` (max. 15 aktiv, Zeichnung+Text)
  oder `azubi` (max. 10 aktiv, nur Text)
- `cell_id`: `{week_id}_{worker_id}_{YYYY-MM-DD}` — Datum muss in der Woche liegen
- `revision`: global monoton steigende Zahl über alle Mutationen (ChangeLog)

## Endpunkte

### GET /api/v1/status  (ohne Auth)
`200 {"status": "ok", "revision": <int>}`

### GET /api/v1/workers
`200 {"workers": [{"id","number","name","category","position","active","revision"}]}`
Sortiert nach `category` (monteur zuerst), dann `position`. Enthält auch inaktive.

### GET /api/v1/weeks/{week_id}
Legt die Woche bei Erstzugriff implizit an (`OPEN`).
`200 {"week": {"id","status","revision"}, "dates": ["YYYY-MM-DD" ×7],
      "entries": [<Entry>]}`
`422` bei ungültiger week_id.

Entry-Objekt:
```json
{"id": "e-…", "cell_id": "…", "type": "text|drawing",
 "author_type": "tablet|web", "author_id": "…",
 "content": {…}, "conflict_of": null,
 "created_at": "…", "updated_at": "…", "revision": 7}
```
`content` bei `text`: `{"text": "…"}` (1–2000 Zeichen).
`content` bei `drawing`: `{"canvas_width": int, "canvas_height": int,
"strokes": [{"color": "#RRGGBB", "base_width": float,
"points": [{"x","y","pressure","time"}]}]}`.

### POST /api/v1/entries
Body: `{"cell_id", "type", "content"}` → `201 {"entry": <Entry>}`
Fehler: `422` Validierung (auch: drawing in Azubi-Zelle, unbekannter Worker),
`413` content > `TIMEPLAN_MAX_ENTRY_BYTES`, `423` Woche LOCKED/ARCHIVED.

### PUT /api/v1/entries/{entry_id}
Body: `{"content", "base_revision": <int>}` → `200 {"entry": <Entry>}`
`409` wenn `base_revision` ≠ aktuelle Entry-Revision. Der Server speichert den
eingereichten Stand als Konfliktkopie (`conflict_of` = Original-ID) und
antwortet `{"detail": {"error": "revision_conflict", "conflict_entry_id": "…",
"current_revision": <int>}}`. `404` unbekannte ID, `413`/`423` wie oben.

### DELETE /api/v1/entries/{entry_id}
`204`. `404` unbekannt, `423` Woche gesperrt.

### GET /api/v1/sync?since={revision}
Aufholen nach Offline-Phase. Kompaktiert: pro Entität nur die letzte Änderung.
`200 {"latest_revision": <int>, "changes":
      [{"revision", "entity_type": "worker|week|entry", "entity_id", "action":
        "created|updated|deleted"}]}`
Client lädt danach betroffene Entitäten per REST nach.

### WS /ws/v1
Query: `?device_id=<id>&token=<token>` (Tablet) oder `?client=web` (Browser,
ohne Token — Events enthalten keine Inhalte, nur IDs/Revisionen).
Server → Client Events:
```json
{"event": "cell.updated", "cell_id": "…", "revision": 12}
{"event": "workers.updated", "revision": 13}
{"event": "week.updated", "week_id": "…", "revision": 14}
```
Client → Server: beliebige Texte werden ignoriert (Keepalive erlaubt).

## Fehlerformat

FastAPI-Standard: `{"detail": …}` (String oder Objekt, s. o.).

## Env-Variablen (Portainer-Stack)

| Variable | Pflicht | Bedeutung |
|---|---|---|
| `TIMEPLAN_SECRET_KEY` | ja | Session-Signierung WebUI |
| `TIMEPLAN_ADMIN_PASSWORD` | ja | WebUI-Login |
| `TIMEPLAN_DEVICE_TOKENS` | nein | `id:token,…` für Tablets |
| `TIMEPLAN_DB` | nein | Default `/data/timeplan.db` (Container) |
| `TIMEPLAN_MAX_ENTRY_BYTES` | nein | Default `2097152` |
