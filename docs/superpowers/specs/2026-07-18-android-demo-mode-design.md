# TimePlan Android Demo-Modus – Design-Spezifikation

Datum: 2026-07-18
Status: vom Auftraggeber abgenommen
Grundlage: `docs/superpowers/specs/2026-07-17-timeplan-design.md` (Gesamtdesign),
`docs/superpowers/plans/2026-07-17-android-app-basis.md` (bereits umgesetzte
App-Basis, Meilenstein 3, auf `main` gemerged)

## 1. Zweck

Für die anstehende Design-/Layout-Überarbeitung der App (mit `impeccable`)
wird ein Demo-Modus benötigt, der auf einem beliebigen Testgerät ohne
laufenden TimePlan-Server funktioniert. Der Demo-Modus liefert realistische,
aber frei erfundene Daten und erlaubt lokale Eingaben, damit das Wochenraster
mit echtem Inhalt beurteilt werden kann.

## 2. Getroffene Entscheidungen

| Thema | Entscheidung |
|---|---|
| Azubi-Anzahl | 10 (nicht 12) — folgt der bestehenden, bereits serverseitig erzwungenen Grenze |
| Aktivierung | Automatisch, wenn keine Server-Konfiguration gespeichert ist (kein separater Schalter) |
| Rückkehr aus Demo | Button „Konfiguration löschen" in den Einstellungen (`ConfigRepository.clear()`) |
| Eingabeart | Text-Platzhalter-Dialog (kein Vorgriff auf die S-Pen-Zeichenfläche aus Meilenstein 5) |
| Persistenz der Demo-Eingaben | Nur für die laufende App-Sitzung (In-Memory), kein Neustart-Überleben |
| Sichtbarkeit | Dauerhaftes „DEMO"-Badge in der Kopfleiste |

## 3. Architektur

Der Demo-Modus fügt sich in die bestehende Schichtung aus Meilenstein 3 ein,
ohne sie zu verändern:

```text
WeekActivity
  ├─ config vorhanden?  → TimePlanApiClient (echt, Meilenstein 3)
  └─ config fehlt?      → DemoApi (neu, dieser Plan)
                              │
                         implementiert TimePlanApi
                              │
        WeekPresenter ──────────────► WeekGridBuilder ──► WeekGridAdapter
```

`WeekPresenter`, `WeekGridBuilder` und `WeekGridAdapter` kennen nur das
Interface `TimePlanApi` (bereits aus Meilenstein 3, Task 5) und bleiben
unverändert. `DemoApi` ist eine zweite, rein lokale Implementierung dieses
Interfaces — kein Sonderpfad in der Business-Logik.

## 4. DemoApi

- Implementiert `TimePlanApi` (`getStatus`, `getWorkers`, `getWeek`)
  vollständig lokal, ohne Netzwerkzugriff.
- Seed bei Konstruktion: **10 Monteure** (MA-Nr. 101–190, fiktive Nachnamen)
  und **10 Azubis** (MA-Nr. 501–510, fiktive Nachnamen) — beide Listen
  eindeutig als Demo-Daten erkennbar (z. B. Namen, die nicht mit dem
  Server-Seed aus `server/app/seed.py` kollidieren).
- Hält Entries sitzungsweit in einer veränderlichen Map `cell_id -> Entry`
  (nicht auf die „aktuelle" Woche beschränkt — Navigation zwischen Wochen
  behält Eingaben bei, wie beim echten Server auch).
- Neue Methode `fun putEntry(cellId: String, text: String)` (kein Teil des
  `TimePlanApi`-Interfaces, nur von der neuen Eingabe-UI direkt auf der
  konkreten `DemoApi`-Instanz aufgerufen) legt/ersetzt einen Text-Entry mit
  `author_type = "tablet"`, `author_id = "demo"`.

## 5. Eingabe-Interaktion (nur im Demo-Modus)

- Zellen werden klickbar: Tipp auf eine Monteur-Zelle öffnet einen
  einfachen Dialog mit Mehrzeilen-Textfeld (vorbelegt mit vorhandenem
  Text), Speichern ruft `DemoApi.putEntry(...)` auf und lädt die Woche neu.
- Tipp auf eine Azubi-Zelle öffnet denselben Dialog, zusätzlich mit einer
  Schnellauswahl der aktiven Monteure (Spinner/Liste) — Auswahl füllt das
  Textfeld mit `"{number} {name}"`, analog zur bereits gebauten
  WebUI-Azubi-Schnellauswahl (`server/app/templates/partials/cell_edit.html`).
- Im vernetzten (nicht-Demo) Modus bleibt das Verhalten unverändert
  (read-only, wie in Meilenstein 3 gebaut) — diese Spezifikation führt
  keine Schreibfunktion für den echten Server ein.

## 6. Sichtbarkeit & Rückkehr

- Ein Badge/Label „DEMO" wird dauerhaft in der Kopfleiste von
  `WeekActivity` angezeigt, solange keine Konfiguration vorliegt.
- `SettingsActivity` erhält einen zusätzlichen Button „Konfiguration
  löschen", der `ConfigRepository.clear()` aufruft und zur `WeekActivity`
  zurückkehrt; diese erkennt die fehlende Konfiguration und zeigt wieder
  den Demo-Modus.

## 7. Nicht Teil dieser Spezifikation

- Die S-Pen-Zeichenfläche selbst (weiterhin Meilenstein 5)
- Schreibzugriff für den echten, vernetzten Modus (POST/PUT `/entries` vom
  Tablet aus — kommt mit der Zeichenfläche)
- Persistenz der Demo-Eingaben über einen App-Neustart hinaus
- Der Stift-only-Schalter aus der Haupt-Design-Spec bleibt bestehen, hat in
  diesem Umfang aber keine Wirkung (Texteingabe ist werkzeugunabhängig)
