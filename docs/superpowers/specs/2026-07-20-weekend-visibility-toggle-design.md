# Samstag/Sonntag anzeigen — Design

## Problem

Das Wochenraster zeigt immer alle 7 Tage (Mo-So), auch wenn eine Firma am
Wochenende grundsätzlich nicht plant. Das kostet Platz für die eigentlich
relevanten Tage Montag-Freitag, sowohl im WebUI als auch in der Android-App.

## Ziel

Zwei Schalter ("Samstag anzeigen", "Sonntag anzeigen") im WebUI, zentral am
Server gespeichert. Sind sie deaktiviert, verschwindet die jeweilige
Tages-Spalte aus dem Wochenraster — in WebUI *und* Android-App gleichermaßen,
da beide dieselbe Server-Einstellung konsumieren.

## Persistenz

Neue Tabelle `app_settings` (genau eine Zeile, keine mehrbenutzerfähige
Konfiguration nötig, da es nur eine Admin-Anmeldung gibt):

```sql
CREATE TABLE IF NOT EXISTS app_settings(
  id INTEGER PRIMARY KEY CHECK(id = 1),
  show_saturday INTEGER NOT NULL DEFAULT 1,
  show_sunday INTEGER NOT NULL DEFAULT 1,
  revision INTEGER NOT NULL
);
```

Getrennt vom bestehenden `config.Settings`-Dataclass (der aus
Umgebungsvariablen beim Prozessstart geladen wird und zur Laufzeit
unveränderlich ist) — `app_settings` ist laufzeit-editierbar über die
WebUI und damit ein eigener, neuer Mechanismus.

Ein neues Repo-Modul `app/repos/app_settings.py` kapselt Lesen/Schreiben
(`get_display_settings(conn) -> DisplaySettings`,
`update_display_settings(conn, show_saturday, show_sunday) -> DisplaySettings`),
analog zu den bestehenden Repos (`workers.py`, `entries.py`).

## Zentraler Filter-Mechanismus

Die eigentliche Filterung passiert **ausschließlich auf dem Server**, an
einer einzigen Stelle: einer neuen Funktion `weeks.visible_week_dates(conn, week_id)`
(in `app/weeks.py`, direkt neben dem bestehenden `week_dates(week_id)`), die:

1. `week_dates(week_id)` aufruft (liefert alle 7 `date`-Objekte, Mo-So,
   unverändert),
2. `app_settings.get_display_settings(conn)` liest,
3. eine gefilterte Liste `[(weekday_index, date), ...]` zurückgibt, in der
   Samstag (Index 5) fehlt, wenn `show_saturday=False`, und Sonntag
   (Index 6) fehlt, wenn `show_sunday=False` — unabhängig voneinander,
   alle vier Kombinationen sind möglich.

Der `weekday_index` wird mitgeliefert (nicht aus der Listenposition
abgeleitet), damit Wochentags-Beschriftungen ("Mo".."So") auch dann korrekt
bleiben, wenn z.B. nur Samstag fehlt und Sonntag an Listenposition 5 (statt
6) landet — sonst würde Sonntag fälschlich als "Sa" beschriftet.

Sowohl `web/routes.py` (`_grid_context`, für `day_labels` und die
Tabellen-Spalten) als auch `api/routes.py` (`get_week`, für das
`dates`-Feld der JSON-Antwort) rufen diese eine Funktion auf, statt die
Filterlogik zu duplizieren. Die zurückgelieferten `entries` bleiben davon
unberührt — Einträge an ausgeblendeten Tagen werden weder gelöscht noch
aus der Antwort entfernt, sie landen im WebUI einfach in keiner sichtbaren
Spalte und werden von der Android-App nicht für eine (nicht vorhandene)
Spalte konsumiert. Wird die Einstellung später wieder aktiviert, sind alle
vorherigen Einträge sofort wieder sichtbar.

## WebUI

Neue Seite `GET/POST /settings` (admin-only wie `/workers`), neuer
Menüpunkt "Einstellungen" in der Topbar (`base.html`, neben "Monteure").
Zwei Checkboxen, "Speichern"-Button. Nutzt dieselbe Papier-CSS-Palette wie
der Rest des WebUI (keine neue Optik nötig, bestehende Formular-/Button-
Klassen reichen).

Nach dem Speichern: Broadcast eines `settings.updated`-Events über den
bestehenden WebSocket-Hub (`request.app.state.hub.broadcast(...)`),
analog zum bestehenden `workers.updated`-Event. `static/live.js` behandelt
`settings.updated` genauso wie `workers.updated` bereits behandelt wird
(`location.reload()`), sodass alle offenen WebUI-Sessions die neue
Spaltenzahl sofort sehen.

Das Wochenraster-Template (`week.html`) baut seine `<th>`/`<td>`-Spalten
aus der (nun ggf. gekürzten) `dates`/`day_labels`-Liste — die
`colspan` der Azubi-Trennzeile muss dynamisch werden
(`colspan="{{ 1 + dates|length }}"` statt hartcodiert `8`), sonst
verrutscht die Trennzeile bei 5 oder 6 sichtbaren Tagen.

## Android-App

Die App konsumiert weiterhin `GET /api/v1/weeks/{week_id}` wie bisher —
kein neuer Endpoint. Das `dates`-Feld der Antwort ist jetzt ggf. kürzer
als 7 Einträge; `WeekBundle` und das Wochenraster-Rendering (aktuell an
fixer Spaltenzahl orientiert) werden auf variable Spaltenzahl umgestellt.

**Kein Live-Push zur App:** Es existiert aktuell kein WebSocket-Client in
der Android-App (nur im WebUI). Die App übernimmt eine geänderte
Einstellung daher beim nächsten Datenabruf (Wochenwechsel, App-Neustart),
nicht in Echtzeit. Das ist kein neuer Sonderfall, sondern entspricht dem
bestehenden Verhalten der App bei jeder anderen Server-seitigen Änderung
(z.B. neuer Monteur).

**Demo-Modus bleibt unverändert:** Der Offline-Demo-Modus (`DemoApi`,
Meilenstein 3/4) spricht nicht mit dem echten Server und zeigt daher
weiterhin immer alle 7 Tage. Das ist eine bewusste, akzeptierte Lücke —
der Demo-Modus dient nur der Design-Iteration ohne Server, nicht der
Abbildung aller Produktiv-Einstellungen.

## Testing

**Server (pytest):**
- Persistenz: `app_settings`-Repo liest/schreibt korrekt, Default beim
  ersten Zugriff ist `show_saturday=True, show_sunday=True`.
- `weeks.visible_week_dates`: alle vier Kombinationen (beide an, nur Sa
  aus, nur So aus, beide aus) liefern die richtige Teilmenge mit korrektem
  `weekday_index` pro Datum.
- WebUI-Wochenraster (`/week/{id}`): korrekte Spaltenzahl und
  -beschriftung für alle vier Kombinationen, `colspan` der Trennzeile
  stimmt, vorhandene Einträge an ausgeblendeten Tagen bleiben in der DB
  und tauchen bei Wiedereinschalten korrekt wieder auf.
- API (`/api/v1/weeks/{id}`): `dates`-Feld korrekt gekürzt, `entries`
  unverändert vollständig.
- `/settings`-Seite: Rendering, Speichern, Broadcast-Event wird ausgelöst,
  admin-only (redirect ohne Login).

**Android:** Wochenraster-Rendering mit 5, 6 und 7 Spalten (Unit-/
Robolectric-Tests analog zu den bestehenden `WeekActivityTest`-Mustern).

## Out of Scope

- Live-Push der Einstellung zur Android-App (kein WebSocket-Client dort).
- Demo-Modus-Unterstützung für diese Einstellung.
- Warnung/Bestätigung beim Ausblenden eines Tages mit vorhandenen Daten
  (bewusst weggelassen, siehe Datenverhalten oben).
- Backdrop-Klick oder sonstige UI-Interaktionen außerhalb der zwei
  Checkboxen auf der neuen Einstellungsseite.
