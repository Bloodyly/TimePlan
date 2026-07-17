# TimePlan – Design-Spezifikation

Datum: 2026-07-17
Status: vom Auftraggeber abgenommen
Grundlage: `WOCHENPLANER_UMSETZUNGSPLAN.md` (Fachkonzept) + Architektur-Entscheidungen vom 2026-07-17

## 1. Zusammenfassung

TimePlan ist ein Wochenplaner für ca. 15 Monteure, bestehend aus:

1. einer **Kotlin-Android-App** für ein fest verbautes Samsung Galaxy Tab Active3
   (Android 10, App ab minSdk 24), mit S-Pen-Handschrifteingabe direkt in
   Wochenraster-Zellen,
2. einem **Python-Container-Server** (FastAPI), der REST-API, WebSocket und eine
   server-gerenderte WebUI für Verwaltung und Synchronisation bereitstellt,
3. einer **PDF-/NAS-Archivierung** abgeschlossener Wochen.

Der Server existiert noch nicht und wird komplett neu gebaut. Beide Teilsysteme
werden parallel/verschränkt entwickelt; der gemeinsame API-Vertrag
(`docs/api-contract.md`) ist die eine Quelle der Wahrheit.

## 2. Getroffene Entscheidungen

| Thema | Entscheidung |
|---|---|
| Server-Stand | Es existiert nichts – kompletter Neubau |
| Vorgehen | Parallel/verschränkt, API-Vertrag zuerst |
| Deployment | Public-Git-Repo → Portainer-Stack (Git-Import), nur im internen Netz |
| Datenbank | SQLite in Docker-Volume |
| WebUI | Server-gerendert: FastAPI + Jinja2 + htmx, kein Node-Build |
| Tablets | Mehrgerätefähig ausgelegt (Geräteregistrierung + Token) |
| Handschrift | Nach dem Speichern wieder bearbeitbar (Vektordaten) |
| Tablet-UI | Variante A – klassische Tabelle (`ui_variante_a_klassische_tabelle.png`) |
| Repo-Layout | Monorepo: `/server`, `/android`, `/docs`, `docker-compose.yml` im Root |
| Android-UI-Toolkit | Klassische Views (RecyclerView-Raster, eigene Canvas-View) |
| PDF-Engine | WeasyPrint (HTML/CSS-Template, Handschrift als SVG eingebettet) |

## 3. Architektur & Deployment

### Repo-Struktur (Monorepo, public)

```text
TimePlan/
├── docker-compose.yml        ← Portainer-Stack-Einstieg
├── server/
│   ├── Dockerfile
│   └── app/                  ← FastAPI-Anwendung (API + WS + WebUI)
├── android/                  ← Kotlin-Projekt (Gradle)
└── docs/
    ├── api-contract.md       ← gemeinsamer API-Vertrag
    └── superpowers/specs/    ← Design-Dokumente
```

### Server-Container

Ein einziger Container: FastAPI + Uvicorn, SQLite, WeasyPrint. REST, WebSocket
und WebUI laufen im selben Prozess.

Volumes:

- `timeplan-data` → SQLite-Datei, Medien (Stroke-JSON, WebP-Vorschauen, PDFs)
- `timeplan-archive` → NAS-Mount (CIFS/NFS, in Portainer konfiguriert)

### Deployment-Fluss

Push ins öffentliche Git-Repo → Portainer deployed den Stack aus dem Repo
(optional mit Auto-Redeploy/Polling). **Keine Secrets im Repo** – Gerätetokens,
WebUI-Passwort und NAS-Zugang kommen ausschließlich aus
Portainer-Environment-Variablen.

### Sicherheit (internes, physisch getrenntes Netz)

- HTTP/WS ohne TLS (bewusste Entscheidung fürs abgeschottete Netz)
- pro Tablet feste Gerätekennung + Token (`Authorization: Bearer …`)
- WebUI: Session-Login mit Passwort aus Env-Variable, kein Benutzerkonzept im MVP
- Eingabevalidierung serverseitig, keine clientgesteuerten Dateipfade
- Größenlimits: Strokes-JSON ≤ 2 MB, generelle Request-Limits
- revisionsbasierte Konflikterkennung, Audit-Log

## 4. Datenmodell (SQLite)

- **Worker** – `id, name, position, active, revision`
- **Week** – `id ("2026-W31"), status (OPEN | LOCKED | ARCHIVED), revision`;
  Wochen entstehen lazy beim ersten Zugriff
- **Entry** – `id, cell_id, type (text | drawing), author_type (tablet | web),
  author_id, content (JSON), created_at, updated_at, revision`
  - `cell_id` ist ein berechneter Schlüssel `"{week_id}_{worker_id}_{date}"`,
    keine eigene Cell-Tabelle
  - `content` bei `text`: `{"text": …}`;
    bei `drawing`: Strokes-JSON (Canvas-Maße, Strokes mit Punkten
    `x, y, pressure, time`, Farbe, Basisbreite) wie im Fachkonzept §7
- **Device** – `id, name, token_hash, last_seen` (mehrgerätefähig)
- **ChangeLog** – global monoton steigende Revisionsnummer pro Mutation;
  Motor der Synchronisation (`GET /sync?since=…`)
- **AuditLog** – wer hat wann was geändert (Änderungsverlauf in der WebUI)

### Zeichnungen: Vereinfachung gegenüber dem Fachkonzept

Das Tablet lädt **nur Vektordaten** hoch (inline im Entry). Vorschau-WebP für
WebUI und PDF erzeugt der **Server** aus den Vektordaten (max. 400×180 px).
Das Tablet rendert seine Zellvorschau lokal aus den eigenen Vektordaten.
Der separate Bild-Upload aus dem Fachkonzept entfällt.

## 5. API-Vertrag (Auszug; vollständig in `docs/api-contract.md`)

```text
GET    /api/v1/status                  Health + aktuelle globale Revision
GET    /api/v1/workers
GET    /api/v1/weeks/{week_id}         Woche + alle Entries als Bundle
GET    /api/v1/sync?since={revision}   kompakte Änderungsliste zum Aufholen
POST   /api/v1/entries                 mit base_revision
PUT    /api/v1/entries/{id}            409 bei Revisionskonflikt
DELETE /api/v1/entries/{id}
GET    /api/v1/media/previews/{entry_id}.webp
WS     /ws/v1                          {"event":"cell.updated","cell_id":…,"revision":…}
```

## 6. Synchronisation

- Room-DB ist die unmittelbare Wahrheit fürs Tablet-UI
  (`Server → Room → UI`).
- Eingaben: sofort lokal speichern → Sync-Queue → Upload im Hintergrund via
  WorkManager mit exponentiellem Retry. Keine Eingabe geht bei Netzausfall
  verloren.
- WebSocket transportiert nur Ereignisse („Zelle X hat Revision N"); die Daten
  werden per REST nachgeladen.
- Nach Offline-Phase: `GET /sync?since=letzteBekannteRevision` holt alle
  verpassten Änderungen in einem Rutsch.
- Konflikt: Client sendet `base_revision`; bei Abweichung antwortet der Server
  `409`, beide Versionen bleiben erhalten, der Konflikt wird in der WebUI zur
  Auflösung angezeigt. Text- und Zeichnungs-Entries sind getrennte Objekte und
  überschreiben sich nie gegenseitig.
- Offline-Indikator in der Kopfleiste, unsynchronisierte Zellen markiert.

## 7. WebUI (Jinja2 + htmx)

- **Wochenansicht** – Raster Monteure × 7 Tage (wie Tablet-Variante A).
  Textzellen inline bearbeitbar (htmx-Formulare), Handschrift als
  Server-Vorschaubild. Live-Aktualisierung: WebSocket-Listener lädt betroffene
  Zellen per htmx nach.
- **Monteurverwaltung** – anlegen, umbenennen, deaktivieren, sortieren.
- **Verwaltung** – Wochenstatus sperren/öffnen, PDF manuell erzeugen,
  Archiv-Liste mit Download, Änderungsverlauf, Gerätestatus (letzter Sync pro
  Tablet), offene Konflikte auflösen.
- **Login** – einfache Session, Passwort aus Env-Variable.

## 8. Android-App (Kotlin, minSdk 24, klassische Views)

Module innerhalb einer App:

- `ui` – Wochenraster (RecyclerView, Variante A), eigene `DrawingCanvasView`
- `data` – Room: Entries, Workers, SyncQueue
- `net` – OkHttp REST-Client + WebSocket
- `sync` – WorkManager-Jobs, Revisionslogik

Verhalten:

- Start in aktueller Woche; Navigation vor/zurück + „Heute"-Sprung
- Zellen-Tipp öffnet nahezu vollflächige Zeichenansicht; vorhandene Strokes
  werden geladen und sind bearbeitbar
- S-Pen mit Druckstärke; Palm-Rejection über `TOOL_TYPE_STYLUS`, Fingereingabe
  optional zuschaltbar; Undo/Redo/Löschen
- Einstellungsseite: Server-URL, Gerätename, Token – nichts hartkodiert
- Kiosk-Betrieb (letzte Phase): Autostart via `BOOT_COMPLETED`,
  Lock-Task-/Screen-Pinning

## 9. PDF & Archivierung

- WeasyPrint rendert ein druckoptimiertes HTML/CSS-Template; Handschrift wird
  als SVG aus den Vektordaten eingebettet (verlustfrei skalierbar).
- Manueller Export aus der WebUI + automatischer Wochenabschluss.
- Ablage auf NAS-Volume in der Struktur
  `/Monteure/{Jahr}/KW{NN}/…` (PDF + Daten-JSON + Zeichnungen),
  mit Prüfsummen-Kontrolle.
- NAS nicht erreichbar → PDF bleibt im Data-Volume, Woche bleibt `LOCKED`
  (nicht `ARCHIVED`), Retry-Job, Fehleranzeige in der WebUI.

## 10. Festgelegte Defaults (per Env-Variable änderbar)

- PDF-Format: **A4 quer**
- Automatischer Wochenabschluss: **Sonntag 23:59, zwei Wochen nach Wochenende**
- Archivierte Wochen: am Tablet **read-only**, in der WebUI wieder zu öffnen
- Pro Zelle: **max. 1 Zeichnungs-Entry pro Gerät**, Text-Entries mehrfach

## 11. Meilensteine (verschränkt, jeder endet testbar)

1. **Vertrag + Server-Kern** – `docs/api-contract.md`; FastAPI mit
   Workers/Weeks/Entries/Sync in SQLite; Seed-Daten; docker-compose →
   als Portainer-Stack deploybar
2. **WebUI-Basis** – Wochenraster lesen/Texte schreiben, Monteurverwaltung, Login
3. **App-Basis** – Projekt-Setup, Konfigseite, Wochenraster mit Serverdaten,
   Wochennavigation
4. **Live-Sync** – WebSocket beidseitig: Text aus der WebUI erscheint binnen
   Sekunden auf dem Tablet
5. **Stifteingabe** – Zeichenfläche lokal (Room), Zellvorschau
6. **Offline & Upload** – Sync-Queue, Retry, Konfliktbehandlung,
   Server-Vorschaubilder
7. **PDF & Archiv** – WeasyPrint-Template, manueller Export, automatischer
   Wochenabschluss, NAS-Retry
8. **Betrieb** – Kiosk, Autostart, Watchdog, Gerätestatus in der WebUI

## 12. Tests & Abnahme

Testplan und Abnahmekriterien aus dem Fachkonzept §15 gelten unverändert;
zusätzlich pro Meilenstein:

- Server: pytest gegen die API (inkl. Revisions-/Konfliktfälle, doppelte
  Uploads, ungültige/übergroße Payloads)
- WebUI: Smoke-Tests der zentralen Flows über Template-Rendering-Tests
- Android: Unit-Tests für Sync-Queue/Revisionslogik; Instrumented Tests fürs
  Raster; manuelle S-Pen-Tests auf dem Zielgerät
- Ende-zu-Ende je Meilenstein 4+: WebUI-Eingabe ↔ Tablet-Anzeige gegen den im
  Portainer deployten Stack
