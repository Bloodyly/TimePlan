# TimePlan

Wochenplaner für Monteure: Android-Tablet mit S-Pen-Handschrift +
Python-Server mit WebUI. Details: `docs/superpowers/specs/`,
API: `docs/api-contract.md`.

## Server lokal starten

```bash
cd server
python -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
TIMEPLAN_DB=./data/dev.db .venv/bin/python -m app.seed
TIMEPLAN_DB=./data/dev.db .venv/bin/uvicorn app.main:create_app --factory --port 8000
```

WebUI: `http://localhost:8000` (Passwort default `admin`).
Tests: `cd server && python -m pytest`.

## Deployment über Portainer (Git-Stack)

1. Portainer → Stacks → **Add stack** → *Repository*
2. Repository-URL dieses Repos, Compose path `docker-compose.yml`
3. Environment variables setzen:
   - `TIMEPLAN_SECRET_KEY` – langer Zufallswert
   - `TIMEPLAN_ADMIN_PASSWORD` – WebUI-Login
   - `TIMEPLAN_DEVICE_TOKENS` – z. B. `tablet-01:<zufallstoken>`
4. Deploy. Optional GitOps-Polling für Auto-Redeploy aktivieren.

Secrets stehen **nie** im Repo – nur im Portainer-Stack.
