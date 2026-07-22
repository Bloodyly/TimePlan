# WebUI: Azubi-Auswahl als Popup — Design

## Problem

Im WebUI klappt die Azubi-Auswahl (Status Schule/Krank/Urlaub oder Monteur-Zuordnung)
aktuell inline in der Tabellenzelle auf (`partials/cell_edit.html`). Der Picker enthält
mehrere Buttons untereinander und vergrößert dadurch die Tabellenzeile stark, was das
Wochenraster unübersichtlich macht. Monteur-Zellen sind davon nicht betroffen (dort bleibt
das kompakte Freitextfeld).

## Ziel

Bei Azubi-Zellen öffnet ein Klick stattdessen ein Popup (natives HTML `<dialog>`) mit
demselben Picker-Inhalt. Die Tabellenzeile bleibt dabei immer kompakt. Eine Auswahl im
Popup speichert sofort (wie bisher beim Klick auf einen Picker-Button) und schließt das
Popup automatisch.

## Architektur

- **Ein globales Dialog-Element**: `week.html` bekommt ein leeres
  `<dialog id="cell-dialog"><div id="cell-dialog-body"></div></dialog>`, einmalig
  außerhalb der Tabelle platziert.
- **Öffnen**: Nur Azubi-`<td>`s in `partials/cell.html` ändern ihr `hx-target` von
  `this` (outerHTML-Swap der Zelle selbst) auf `#cell-dialog-body` (innerHTML-Swap) und
  bekommen ein `hx-on::after-swap`-Attribut, das `document.getElementById('cell-dialog').showModal()`
  aufruft. Monteur-`<td>`s bleiben exakt wie heute (Ziel: sich selbst, outerHTML).
- **Speichern**: Die Picker-Formulare in `partials/cell_edit.html` (Azubi-Zweig) posten
  weiterhin direkt an die reale Tabellenzelle (`hx-target="#cell-{{ cell_id }}"
  hx-swap="outerHTML"`) — dieser Teil des bestehenden Verhaltens ändert sich nicht,
  die Zelle im Hintergrund aktualisiert sich also unabhängig vom Dialog. Zusätzlich
  bekommt jedes Picker-Formular `hx-on::after-request="document.getElementById('cell-dialog').close()"`,
  damit der Dialog nach jeder erfolgreichen Auswahl (Status, Monteur-Zuordnung, Leeren)
  automatisch schließt.
- **Abbrechen/Schließen**: Ein "Abbrechen"-Button ruft `.close()` auf dem Dialog auf
  (kein Server-Request nötig, da nichts geändert wurde). Die native ESC-Taste schließt
  `<dialog>` bereits automatisch (Browser-Standardverhalten von `showModal()`).
  Schließen per Klick auf den abgedunkelten Backdrop wird **nicht** implementiert
  (YAGNI — ESC + Abbrechen-Button reichen für v1).
- **Backend**: Keine Änderungen an `web/routes.py` — die Endpoints, Formularfelder und
  Antwort-Fragmente bleiben identisch. Nur die umgebende Template-Struktur (wo der
  Picker-HTML-Block landet) ändert sich.

## Styling

Der Dialog bekommt die bestehende Papier-Palette aus `style.css`
(`--paper-surface`-Hintergrund, `--ink`-Text, `--radius`-Ecken) sowie einen abgedunkelten
`::backdrop`. Größe: kompakte Breite (z.B. `max-width: 20rem`), zentriert (native
`<dialog>`-Zentrierung).

## Testing

Die bestehenden Tests in `tests/test_web_cells.py` prüfen HTML-Fragmentinhalt und
Endpoint-Verträge (welche Buttons/Labels im Response-Text vorkommen, welche Einträge
in der DB landen) — diese bleiben gültig, da sich an den Endpoints nichts ändert.
Ergänzend: ein Test, der sicherstellt, dass die Azubi-Edit-Fragment-Antwort weiterhin
alle Picker-Optionen enthält (bereits durch bestehenden Test abgedeckt) und dass die
Monteur-Edit-Fragment-Antwort **kein** `hx-on::after-request`-Dialog-Attribut enthält
(neuer Test, stellt sicher dass Monteur-Zellen unberührt bleiben).

Manuelle Verifikation zusätzlich per Emulator-Browser (Klick öffnet Popup, Auswahl
speichert + schließt, Abbrechen schließt ohne zu speichern, ESC schließt).

## Out of Scope

- Backdrop-Klick zum Schließen.
- Änderungen am Monteur-Zellen-Verhalten.
- Android-App (dieses Feature ist WebUI-exklusiv, wie vom Nutzer festgelegt).
