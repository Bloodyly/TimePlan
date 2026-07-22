# Stift-/Handeingabe (S-Pen) für Monteur-Zellen — Design

## Problem

Der ursprüngliche Umsetzungsplan (`WOCHENPLANER_UMSETZUNGSPLAN.md`) sieht als
Kernstück vor, dass Monteure ihre Einträge direkt auf dem Tablet mit dem S-Pen
handschriftlich in die Wochenzelle zeichnen — getrennt von den textbasierten
Verwaltungshinweisen, die über das Webinterface eingetragen werden. Dieser
Teil ("Phase 4 – Stifteingabe" des Originalplans) wurde bisher nicht gebaut:
Server-seitig existieren Datenmodell und API dafür bereits vollständig
(Entry-Typ `drawing`, Vektor-JSON-Schema, REST-Endpunkte, Azubi-Sperre), aber
es gibt weder eine Zeichenfläche auf Android noch eine echte Darstellung der
Vektordaten — beide Seiten zeigen aktuell nur einen Platzhalter
("✎ Zeichnung").

## Ziel

Eine Monteur-Zelle im echten (nicht-Demo) Android-Modus lässt sich antippen,
um eine nahezu vollflächige Zeichenfläche zu öffnen. Dort kann mit S-Pen oder
Finger gezeichnet werden (Striche, Undo/Redo, Löschen). Beim Speichern wird
die Zeichnung als Vektordaten über die bestehende REST-API auf dem Server
persistiert. Sowohl die Android-Zellenliste als auch das WebUI-Wochenraster
zeigen die Zeichnung anschließend als kleine Live-Vorschau, gerendert direkt
aus den Vektordaten.

## Bestand (keine Änderung nötig)

- DB-Schema: `entries.type CHECK(type IN ('text','drawing'))`,
  `author_type CHECK(... IN ('tablet','web'))`
- `entries_repo._validate_content` validiert `drawing`-Content bereits:
  `{"canvas_width": int, "canvas_height": int, "strokes": [{"color",
  "base_width", "points": [{"x","y","pressure","time"}]}]}`
- `entries_repo.create_entry` sperrt `drawing` für Azubi-Zellen
  (`"azubi cells accept text entries only"`)
- Vollständige REST-API: `POST/PUT/DELETE /api/v1/entries`,
  Device-Token-Auth (`require_device`), revisionsbasierte
  Konflikterkennung (`409` mit Konfliktkopie), WebSocket-Broadcast
  (`cell.updated`), `/api/v1/sync` für Aufholen nach Offline-Phase
- Android `Entry`-Modell dekodiert `type`/`content` bereits generisch
  (`content: JsonElement`); `WeekBundle`/`TimePlanApi.getWeek` liefert
  `drawing`-Einträge schon mit, sie werden nur noch nicht speziell
  gerendert

## Interaktionsmodell — Android

- **Auslöser:** Antippen einer Monteur-Zelle im echten (nicht-Demo) Modus
  öffnet die Zeichenfläche. Aktuell sind Zellen im echten Modus nicht
  antippbar — das ist neu. Azubi-Zellen bleiben unverändert (kein
  Zeichnen möglich, nur Status-Picker).
- **Zeichenfläche:** neue `DrawingView` (eigene, isolierte Komponente,
  analog zu den bisherigen Gesten-Klassen dieses Projekts). Nimmt Touch-
  Events von S-Pen **und** Finger entgegen (keine Palm-Rejection nötig,
  da Finger explizit erwünscht ist), zeichnet Striche live, hält sie als
  Liste von `Stroke{color, baseWidth, points:[{x,y,pressure,time}]}` —
  exakt das serverseitig erwartete Vektorschema.
- **Werkzeuge:** Löschen (alles), Undo/Redo (pro Strich, nur für die
  aktuelle Sitzung im Speicher), Speichern, Abbrechen. Stiftfarbe und
  -stärke sind fest (ein Schwarzton, eine Stärke) — kein Auswahl-UI.
- **Laden bestehender Handschrift:** existiert für die Zelle bereits ein
  `drawing`-Eintrag, werden dessen Striche beim Öffnen geladen und sind
  weiterbearbeitbar (analog zum bisherigen `showMonteurEditor`-Muster:
  vorhandener Inhalt wird vorbelegt).

## Speichern-Fluss

`TimePlanApiClient` (bisher nur lesend: `getStatus`/`getWorkers`/`getWeek`)
bekommt zwei neue Methoden, `createEntry` (POST `/api/v1/entries`) und
`updateEntry` (PUT `/api/v1/entries/{id}`) — nutzt die bestehende
Device-Token-Auth unverändert.

Beim Speichern: existiert für die Zelle bereits ein `drawing`-Eintrag, wird
er per `updateEntry` mit dessen `base_revision` aktualisiert; sonst wird per
`createEntry` ein neuer Eintrag angelegt. Gleiches Muster wie die
bestehende Update-vs-Create-Logik für Azubi-Text-Einträge im WebUI.

**Fehlerbehandlung:** Kein Offline-Queue in diesem Schritt (bewusst
abgegrenzt, siehe unten). Schlägt der Upload fehl (Netzwerkfehler,
`409`-Konflikt, `423`-gesperrte Woche), bleibt die Zeichenfläche mit den
gezeichneten Strichen offen und zeigt eine Fehlermeldung — nichts geht
verloren, der Nutzer kann erneut speichern oder abbrechen. Nach
erfolgreichem Speichern schließt der Dialog und die Woche wird über den
bestehenden `loadCurrentWeek()`-Mechanismus aktualisiert.

## Vorschau-Darstellung

Kein separates Vorschaubild (kein WebP/PNG, keine Schema-/API-Erweiterung
nötig) — beide Seiten rendern die Vorschau live aus denselben Vektordaten:

- **WebUI** (`partials/cell.html`): der bestehende
  `{% for entry in cell_entries %}`-Loop bekommt einen neuen Zweig für
  `entry.type == "drawing"`, der ein kleines Inline-`<svg>` mit
  `<polyline>`-Elementen aus `content.strokes[].points` baut, skaliert
  auf die Zellengröße. Ersetzt den aktuellen Platzhalter "✎ Zeichnung".
- **Android:** Der Grid-Aufbau ist aktuell rein textbasiert
  (`WeekGridBuilder.build` liefert `WeekRow.cellTexts: List<String?>`,
  ein flacher, über alle Entries gejointer String pro Zelle). Das wird
  erweitert, damit eine Zelle sowohl Text **und** eine Zeichnung tragen
  kann (Anzeige beider gestapelt in derselben Zelle, wie bei mehreren
  Text-Entries bereits üblich). Neue kleine `DrawingThumbnailView`
  (dieselbe Render-Logik wie `DrawingView`, nur skaliert und rein
  lesend/nicht-interaktiv) zeigt die Zeichnung zusätzlich zum Text an.

## Testing

- Die reine Vektor-Logik (Undo/Redo-Stack, Punkte-Skalierung fürs
  Thumbnail) landet in isolierten, unit-testbaren Kotlin-Klassen ohne
  Android-Abhängigkeit — wie `WeekSwipeGesture`/`DragFillRange`.
- WebUI: die neue SVG-Darstellung wird über normale Jinja2-Render-Tests
  geprüft (Server-API/Schema sind bereits vollständig getestet,
  unverändert).
- Die eigentliche Stift-Interaktion (Touch-Events, Live-Zeichnen) ist wie
  bei den bisherigen Gesten-Features (Wisch-Navigation, Drag-to-Fill) nur
  manuell im Emulator verifizierbar (kein Android-UI-Test-Framework in
  diesem Projekt für Touch-Gesten — etablierte, akzeptierte Grenze).

## Out of Scope

- Offline-Queue / Room-Datenbank / automatische Wiederholung bei
  Netzwerkfehlern ("Phase 5 – Upload und Offline-Betrieb" im
  Original-Plan) — eigenes, größeres Teilprojekt.
- PDF-Export mit Handschrift.
- Azubi-Zellen (bleiben ausschließlich beim Status-Picker).
- Wählbare Stiftfarbe/-stärke.
- Separates gerendertes Vorschaubild (WebP/PNG) — nur Live-Vektor-Rendering.
- Demo-Modus-Unterstützung für Zeichnungen — dieses Feature zielt bewusst
  auf den echten Server-Modus, da es genau um echte Persistenz geht.
