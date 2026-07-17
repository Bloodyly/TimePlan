# Wochenplaner für Monteure – Umsetzungsplan

## 1. Zielbild

Entwickelt wird eine native Android-App für ein **Samsung Galaxy Tab Active3**, die eine Wochenübersicht für ungefähr 15 Monteure darstellt.

Die Hauptansicht besteht aus:

- 7 Spalten für Montag bis Sonntag
- etwa 15 Zeilen für Monteure
- grafischen Einträgen, die auf dem Tablet mit dem S Pen erstellt werden
- textbasierten Hinweisen, die über ein Webinterface eingetragen werden
- Navigation zwischen vergangenen, aktuellen und zukünftigen Wochen
- Live-Synchronisation mit einem vorhandenen Python-Server-Stack

Das Tablet übernimmt nur Darstellung, Eingabe, lokalen Zwischenspeicher und Synchronisation. PDF-Erstellung, Archivierung, Namensverwaltung und dauerhafte Datenspeicherung liegen auf dem Server.

## 2. Rahmenbedingungen

### Tablet

- Samsung Galaxy Tab Active3
- Android 10 auf dem Gerät
- App kompatibel ab Android 7, API-Level 24
- S-Pen-Eingabe
- Dauerbetrieb ohne Akku im No-Battery-Modus
- feste Spannungsversorgung
- Einbau in ein 3D-gedrucktes Gehäuse
- überwiegend stationärer Betrieb im internen, physisch getrennten Firmennetz

### Netzwerk und Sicherheit

Da das System in einem getrennten internen Netz betrieben und an definierte Python-Endpunkte angebunden wird, ist eine zusätzliche Transportverschlüsselung nicht Bestandteil des MVP.

Trotzdem sollten folgende Punkte umgesetzt werden:

- feste Gerätekennung pro Tablet
- API-Token oder vergleichbare einfache Authentifizierung
- Eingabevalidierung auf dem Server
- keine frei beschreibbaren Dateipfade aus Clientdaten
- Größenlimits für Bild- und JSON-Uploads
- revisionsbasierte Konflikterkennung
- Protokollierung von Änderungen

HTTP und WebSocket ohne TLS sind unter diesen Bedingungen möglich:

```text
http://planer-server:8000/api/...
ws://planer-server:8000/ws/...
```

Die Serveradressen sollten nicht hart im Code stehen, sondern in einer lokalen Gerätekonfiguration oder Build-Konfiguration hinterlegt werden.

## 3. Funktionsumfang

### 3.1 Tablet-App

- aktuelle Woche beim Start anzeigen
- vorherige und nächste Woche öffnen
- Sprung zur aktuellen Woche
- Namen der Monteure vom Server laden
- Wochenraster mit 7 Tagen und etwa 15 Monteuren anzeigen
- Textmeldungen der Verwaltung in Zellen darstellen
- vorhandene Handschrift als verkleinerte Vorschau darstellen
- Zelle antippen und Zeichenansicht öffnen
- ausschließlich S-Pen oder optional S-Pen und Finger akzeptieren
- Zeichnung löschen, rückgängig machen und speichern
- Eintrag sofort lokal sichern
- Einträge im Hintergrund synchronisieren
- Änderungen vom Server live empfangen
- Verbindungs- und Synchronisationsstatus anzeigen
- bei Verbindungsunterbrechung offline weiterarbeiten
- nach Wiederverbindung automatisch nachsynchronisieren
- optionaler Kioskmodus und automatischer App-Start

### 3.2 Webinterface

- Monteure anlegen, bearbeiten, deaktivieren und sortieren
- Wochen öffnen und wechseln
- Textmeldungen pro Zelle anlegen und bearbeiten
- Handschrift vom Tablet anzeigen
- Änderungsverlauf anzeigen
- aktuelle Synchronisationszustände einsehen
- PDF manuell erzeugen
- archivierte Wochen und PDFs abrufen
- Wochen bei Bedarf sperren oder erneut öffnen

### 3.3 Server

- zentrale Datenhaltung
- definierte REST-Endpunkte
- WebSocket-Kanal für Live-Aktualisierungen
- Speicherung der Zeichnungsdaten
- Erzeugung von Vorschaubildern
- HTML- oder Template-basierte PDF-Erstellung
- automatischer Wochenabschluss
- Ablage auf dem NAS
- Wiederholungslogik bei nicht verfügbarem NAS
- revisionsbasierte Synchronisation
- einfaches Audit-Log

## 4. Systemarchitektur

```text
┌──────────────────────────────┐
│ Galaxy Tab Active3           │
│                              │
│ Kotlin Android-App           │
│ ├─ Wochenraster              │
│ ├─ S-Pen-Zeichenfläche       │
│ ├─ Room-Datenbank            │
│ ├─ Bild-Cache                │
│ ├─ Sync Queue                │
│ ├─ REST-Client               │
│ └─ WebSocket-Client          │
└───────────────┬──────────────┘
                │ HTTP / WebSocket
                ▼
┌──────────────────────────────┐
│ Python-Server-Stack          │
│                              │
│ ├─ vorhandene API-Endpunkte  │
│ ├─ WebSocket-Endpunkt        │
│ ├─ Webinterface              │
│ ├─ Datenbank                 │
│ ├─ Dateiablage               │
│ ├─ PDF-Generator             │
│ └─ Archivierungsjob          │
└───────────────┬──────────────┘
                │
                ▼
          NAS / Archiv
```

## 5. Empfohlener Android-Stack

- Kotlin
- klassische Android Views oder ein vorsichtig eingesetztes Jetpack Compose
- `minSdk 24`
- Room für lokale Speicherung
- OkHttp für REST und WebSocket
- Kotlin Coroutines
- WorkManager für zuverlässige Upload-Wiederholungen
- eigene `View` für die Zeichenfläche
- RecyclerView oder benutzerdefiniertes Grid für das Wochenraster

Für maximale Kompatibilität und gute Kontrolle über Stift-Events ist bei Android 7 eine klassische View-basierte Oberfläche besonders robust.

## 6. Datenmodell

### Worker

```json
{
  "id": "worker-7",
  "name": "Max Mustermann",
  "position": 7,
  "active": true,
  "revision": 3
}
```

### Week

```json
{
  "id": "2026-W31",
  "year": 2026,
  "week_number": 31,
  "start_date": "2026-07-27",
  "end_date": "2026-08-02",
  "status": "OPEN",
  "revision": 42
}
```

### Cell

Eine Zelle repräsentiert die Kombination aus Monteur und Datum.

```json
{
  "id": "2026-W31_worker-7_2026-07-30",
  "week_id": "2026-W31",
  "worker_id": "worker-7",
  "date": "2026-07-30",
  "revision": 14
}
```

### Entry

```json
{
  "id": "entry-113",
  "cell_id": "2026-W31_worker-7_2026-07-30",
  "type": "drawing",
  "author_type": "tablet",
  "author_id": "tablet-01",
  "content": {
    "stroke_file": "drawings/entry-113.json",
    "preview_file": "previews/entry-113.webp"
  },
  "created_at": "2026-07-30T08:45:00",
  "updated_at": "2026-07-30T08:45:00",
  "revision": 1
}
```

Text vom Webinterface:

```json
{
  "id": "entry-127",
  "cell_id": "2026-W31_worker-7_2026-07-30",
  "type": "text",
  "author_type": "web",
  "author_id": "verwaltung-1",
  "content": {
    "text": "Material bei Lager 2 abholen"
  },
  "created_at": "2026-07-30T07:30:00",
  "updated_at": "2026-07-30T07:30:00",
  "revision": 1
}
```

## 7. Speicherung der Handschrift

Jede Zeichnung wird in zwei Formen gespeichert.

### Vektordaten

```json
{
  "canvas_width": 1200,
  "canvas_height": 500,
  "strokes": [
    {
      "color": "#1F2937",
      "base_width": 4.0,
      "points": [
        {"x": 103.2, "y": 51.8, "pressure": 0.42, "time": 0},
        {"x": 106.7, "y": 53.1, "pressure": 0.47, "time": 12}
      ]
    }
  ]
}
```

Vorteile:

- verlustfreie Skalierung
- spätere Bearbeitung
- Undo und Redo
- hochauflösende PDF-Ausgabe
- serverseitige Neuberechnung von Vorschaubildern

### Vorschaubild

Zusätzlich wird ein kompaktes WebP- oder PNG-Bild erzeugt.

Empfehlung:

- Rastervorschau: maximal 400 × 180 Pixel
- transparente oder weiße Fläche
- WebP für kleine Dateien
- Original-Vektordaten dauerhaft auf dem Server speichern

## 8. Synchronisationsprinzip

Die lokale Datenbank ist die unmittelbare Datenquelle der Tablet-Oberfläche.

```text
Server → lokale Datenbank → Benutzeroberfläche
```

Bei einer Eingabe:

```text
1. Zeichnung lokal speichern
2. Zellansicht sofort aktualisieren
3. ausstehende Operation in Sync Queue schreiben
4. Upload im Hintergrund starten
5. Serverbestätigung und neue Revision speichern
```

### Start-Synchronisation

1. Serverstatus und globale Revision abrufen
2. Stammdaten der Monteure synchronisieren
3. aktuelle und benachbarte Wochen laden
4. fehlende Vorschaubilder nachladen
5. WebSocket-Verbindung öffnen
6. ausstehende lokale Uploads senden

### Live-Update

Der WebSocket transportiert nur kleine Ereignisse:

```json
{
  "event": "cell.updated",
  "cell_id": "2026-W31_worker-7_2026-07-30",
  "revision": 15
}
```

Daraufhin lädt das Tablet die betroffene Zelle über REST neu.

### Offline-Modus

- alle Änderungen zuerst lokal speichern
- ausstehende Operationen dauerhaft in Room halten
- exponentielle Wiederholung bei Fehlern
- keine Benutzereingabe wegen Netzwerkfehlern verlieren
- Offline-Symbol in der Kopfleiste
- unsynchronisierte Zellen markieren

## 9. Beispiel-Endpunkte

Die konkreten Pfade werden an den vorhandenen Python-Stack angepasst.

```text
GET    /api/v1/status
GET    /api/v1/workers
GET    /api/v1/weeks/{week_id}
GET    /api/v1/cells/{cell_id}
GET    /api/v1/sync?since_revision=4510

POST   /api/v1/entries
PUT    /api/v1/entries/{entry_id}
DELETE /api/v1/entries/{entry_id}

POST   /api/v1/uploads/drawing
GET    /api/v1/media/{file_id}

POST   /api/v1/weeks/{week_id}/pdf
POST   /api/v1/weeks/{week_id}/archive

WS     /ws/v1/tablets/{device_id}
```

## 10. Konfliktstrategie

Verwaltungstexte und Tablet-Handschrift werden als getrennte Einträge gespeichert. Dadurch überschreiben sie sich nicht gegenseitig.

Für Änderungen desselben Eintrags:

- Client sendet `base_revision`
- Server vergleicht die Revision
- bei Übereinstimmung: Änderung übernehmen
- bei Abweichung: `409 Conflict`
- beide Versionen vorerst erhalten
- Konflikt im Webinterface sichtbar machen

Im normalen Betrieb sollten neue Einträge bevorzugt angehängt statt bestehende Inhalte überschrieben werden.

## 11. PDF- und NAS-Archivierung

### Ablauf

1. Woche erreicht den definierten Abschlusszeitpunkt
2. Server markiert sie als `LOCKED`
3. druckoptimierte HTML-Ansicht wird erzeugt
4. Vektordaten oder hochauflösende Bilder werden eingebettet
5. PDF wird serverseitig erstellt
6. Datei wird auf das NAS übertragen
7. Dateigröße und Prüfsumme werden kontrolliert
8. Woche wird als `ARCHIVED` markiert

### Dateistruktur

```text
/Monteure/
  /2026/
    /KW31/
      2026-KW31-Wochenplan.pdf
      2026-KW31-Daten.json
      /zeichnungen/
```

### Fehlerfall

Kann das NAS nicht erreicht werden:

- PDF lokal auf dem Server zwischenspeichern
- Woche nicht als vollständig archiviert markieren
- Wiederholungsjob ausführen
- Fehler im Webinterface anzeigen

## 12. UI-Varianten

### Variante A – klassische Tabelle

Merkmale:

- maximale Informationsdichte
- Namen dauerhaft links sichtbar
- sieben feste Tagesspalten
- sehr nah an einem Papier-Wochenplan
- gut für erfahrene Nutzer

Risiko:

- auf 8 Zoll können Zellen klein werden
- Einträge sollten in einer vergrößerten Detailansicht bearbeitet werden

Datei: `ui_variante_a_klassische_tabelle.png`

### Variante B – moderner Kartenstil

Merkmale:

- deutlichere Zellgrenzen
- Namen mit Initialen oder Avatar
- bessere optische Trennung der Einträge
- freundlichere Touch-Bedienung

Risiko:

- geringere Informationsdichte
- etwas mehr Scrollbedarf bei langen Namen oder vielen Einträgen

Datei: `ui_variante_b_kartenstil.png`

### Variante C – reduzierter Zeitstrahl

Merkmale:

- besonders klare Tagesköpfe
- Fokus auf schnelle Übersicht
- reduzierte visuelle Ablenkung
- gute Basis für horizontale Wochennavigation

Risiko:

- bei kleinen Displays muss die Schrift sehr sorgfältig gewählt werden
- weniger Platz für mehrere Einträge pro Zelle

Datei: `ui_variante_c_zeitstrahl.png`

### Empfehlung

Für den ersten funktionalen Prototyp sollte Variante A umgesetzt werden. Anschließend können aus Variante B die stärkeren Zellabgrenzungen und aus Variante C die klareren Tagesköpfe übernommen werden.

## 13. Bedienablauf Tablet

### Handschrift eintragen

1. Benutzer tippt auf eine Zelle
2. Zeichenansicht öffnet sich nahezu vollflächig
3. vorhandene Handschrift wird geladen
4. Benutzer schreibt mit S Pen
5. Benutzer tippt auf Speichern
6. Vektordaten werden lokal gespeichert
7. Vorschaubild wird erzeugt
8. Zellvorschau wird sofort aktualisiert
9. Upload wird im Hintergrund gestartet

### Verwaltungshinweis empfangen

1. Verwaltung trägt Text im Webinterface ein
2. Server speichert den Eintrag
3. Server sendet WebSocket-Ereignis
4. Tablet lädt die betroffene Zelle
5. Text erscheint ohne vollständiges Neuladen der Woche

## 14. Entwicklungsphasen

### Phase 1 – Projektgrundlage

- Android-Projekt anlegen
- `minSdk 24` festlegen
- Navigationsstruktur erstellen
- Konfigurationsseite für Serveradresse und Gerätekennung
- Testdaten für 15 Monteure
- statisches Wochenraster

**Ergebnis:** Navigierbarer lokaler Prototyp.

### Phase 2 – Serveranbindung

- vorhandene Python-Endpunkte dokumentieren
- DTOs und API-Client erstellen
- Monteure laden
- Wochen und Zellen laden
- Fehlerzustände anzeigen
- WebSocket-Grundverbindung

**Ergebnis:** Serverdaten werden auf dem Tablet dargestellt.

### Phase 3 – Webinterface-Einträge

- Text-Einträge serverseitig
- Textdarstellung im Raster
- Live-Ereignisse
- selektives Aktualisieren einzelner Zellen
- Änderungsverlauf

**Ergebnis:** Verwaltung kann Hinweise live an das Tablet senden.

### Phase 4 – Stifteingabe

- benutzerdefinierte Zeichenfläche
- Stylus-Erkennung
- Druckwerte
- Palm-Rejection
- Undo, Redo, Löschen
- Vektordatenmodell
- Vorschaubilder

**Ergebnis:** Handschrift kann lokal erstellt und angezeigt werden.

### Phase 5 – Upload und Offline-Betrieb

- Room-Datenbank
- Sync Queue
- Upload von Zeichnungsdaten
- Upload von Vorschaubildern
- Retry-Logik
- Offline-Indikator
- Revisionsverwaltung

**Ergebnis:** Eingaben gehen auch bei Netzausfall nicht verloren.

### Phase 6 – PDF und Archiv

- Drucktemplate
- serverseitiges Rendern
- manueller PDF-Export
- automatischer Wochenabschluss
- NAS-Ablage
- Fehler- und Wiederholungslogik

**Ergebnis:** Wochen werden zuverlässig archiviert.

### Phase 7 – Betriebsmodus

- automatischer App-Start
- Kioskmodus
- Bildschirm-Timeout definieren
- Recovery nach Server- oder Stromausfall
- Watchdog oder regelmäßiger Health Check
- Remote-Diagnose im Webinterface

**Ergebnis:** stabiler 24/7-Betrieb.

## 15. Testplan

### Tablet

- Stift- und Fingerunterscheidung
- lange Handschrifteingaben
- schnelle Folgeeingaben
- Wechsel der Woche während eines Uploads
- App-Neustart mit ausstehenden Einträgen
- Stromausfall während des Speicherns
- Neustart ohne Netzwerk
- Wiederverbindung nach mehreren Stunden
- Speicherverbrauch nach mehreren Wochen
- Displayhelligkeit und Lesbarkeit im Gehäuse

### Server

- doppelte Uploads
- veraltete Revisionen
- große oder ungültige Zeichnungsdateien
- unterbrochener Upload
- NAS nicht erreichbar
- erneute PDF-Erstellung
- parallele Änderungen aus Webinterface und Tablet
- Zeitzonenwechsel und Kalenderwochenwechsel

### Abnahmekriterien

- Eingaben erscheinen lokal ohne spürbare Verzögerung
- keine Eingabe geht bei WLAN-Ausfall verloren
- Text vom Webinterface erscheint im Normalfall innerhalb weniger Sekunden
- eine Woche kann vollständig als PDF erzeugt werden
- PDF enthält Text und Handschrift lesbar
- Archivierung wird bei Fehlern nicht fälschlich als erfolgreich markiert
- Tablet startet nach Stromunterbrechung selbstständig in die App

## 16. Offene Entscheidungen

Vor der Implementierung müssen noch festgelegt werden:

- exakte vorhandene Python-Endpunkte und Payloads
- verwendete Datenbank auf dem Server
- zulässige Anzahl von Einträgen pro Zelle
- ob Handschrift nach dem Speichern noch bearbeitet werden darf
- ob mehrere Tablets eingesetzt werden
- Zeitpunkt des Wochenabschlusses
- Verhalten bei nachträglichen Änderungen archivierter Wochen
- gewünschtes PDF-Format: A4 quer, A3 quer oder benutzerdefiniert
- genaue Displayausrichtung im 3D-Gehäuse
- automatische Bildschirmabschaltung außerhalb der Arbeitszeit

## 17. Empfohlener erster Meilenstein

Der erste produktive Meilenstein sollte bewusst klein bleiben:

1. Namen vom Server laden
2. aktuelle Woche anzeigen
3. zwischen Wochen wechseln
4. Text vom Webinterface live darstellen
5. Handschrift lokal erfassen
6. Handschrift hochladen und als Vorschau anzeigen
7. bei Netzunterbrechung lokal puffern

Erst danach sollten PDF-Archivierung, Audit-Log und erweiterte Administration ergänzt werden.
