# Drag-to-Fill mit Pfeil — Design

## Problem

Ist ein Monteur oder Azubi mehrere Tage hintereinander an derselben Baustelle
bzw. hat denselben Status, muss das aktuell für jeden Tag einzeln eingetragen
werden. Das kostet Zeit bei einem sehr häufigen Anwendungsfall.

## Ziel

Long-Press auf eine bereits befüllte Zelle erlaubt es, per Ziehen nach rechts
weitere (noch leere) Tage in derselben Zeile schnell zu markieren und beim
Loslassen zu befüllen — visualisiert durch einen Pfeil, der beim Ziehen live
mitwächst.

## Interaktionsmodell

- **Auslöser:** Nur eine bereits **befüllte** Zelle kann per Long-Press eine
  Drag-Session starten. Leere Zellen verhalten sich exakt wie heute (Tippen
  öffnet den Editor/Picker, kein Long-Press-Sonderverhalten).
- **Richtung:** Ausschließlich nach rechts, in derselben Zeile, begrenzt auf
  die letzte aktuell sichtbare Tagesspalte (keine Wochengrenze-Überschreitung,
  respektiert automatisch die Sa/So-Sichtbarkeits-Einstellung, da ausgeblendete
  Spalten schlicht nicht existieren).
- **Live-Pfeil:** Startpunkt fix in der vertikalen Mitte der Ursprungszelle.
  Der Pfeilkopf folgt beim Ziehen nur der horizontalen Fingerposition
  (vertikale Bewegung wird ignoriert). Zurückziehen Richtung Ursprung
  verkleinert den markierten Bereich entsprechend; am Ursprung selbst ist der
  markierte Bereich leer (kein Effekt beim Loslassen).
- **Loslassen:** Nur Zellen im markierten Bereich, die zu diesem Zeitpunkt
  **keinen Eintrag** haben, werden befüllt. Zellen mit vorhandenem Eintrag
  bleiben unverändert — keine Rückfrage, kein Überschreiben.

## Was eingetragen wird

| Ursprungszelle | Inhalt der leeren Zielzellen |
|---|---|
| Monteur-Freitext | Einfacher Pfeil-Platzhalter (`→`), **nicht** der eigentliche Text |
| Azubi-Status (Schule/Krank/Urlaub) | Der echte Status, identisch zur manuellen Auswahl über den Picker |
| Azubi-Monteurzuordnung | Text-Pfeil-Platzhalter (`----->`), **nicht** der Name wiederholt |

Der Pfeil-Platzhalter ist reiner Anzeige-/Datenwert (wird wie jeder andere
Zelleneintrag als `text`-Entry gespeichert) — keine neue Entry-Art, keine
Schema-Änderung.

## Plattformen

- **WebUI:** Vollständige, echte serverseitige Umsetzung (siehe unten).
- **Android:** Nur im **Demo-Modus**. Begründung: Der Android-Client hat
  aktuell keinerlei echte Schreibzugriffe zum Server (`TimePlanApiClient`
  ist rein lesend, Zellen sind im echten Modus nicht antippbar) — jegliches
  interaktive Bearbeiten existiert bisher ausschließlich im Demo-Modus über
  `DemoApi`. Eine echte Schreib-API für Android aufzubauen wäre ein eigenes,
  deutlich größeres Vorhaben und ist explizit außerhalb dieses Features.

## Technische Umsetzung — WebUI

**Server:** Ein neuer Endpoint `POST /web/cells/{origin_cell_id}/fill` mit
einer Liste von Ziel-Cell-IDs im Body. Der Server liest den aktuellen
Eintragstext der Ursprungszelle selbst aus der DB (kein Vertrauen auf
Client-seitig mitgeschickten Text), berechnet daraus den zu schreibenden
Wert (Status/echter Text bei Azubi-Status, sonst der passende
Pfeil-Platzhalter) und schreibt ihn — unter Wiederverwendung der
bestehenden Create-Entry-Logik — nur in die Ziel-Zellen, die aktuell noch
keinen Eintrag haben. Die bestehende Azubi-Validierung (`AZUBI_STATUS_CLASS`
plus aktive Monteure) wird für diesen Endpoint um den fest codierten
Pfeil-Platzhalter als zulässigen Sonderwert erweitert — ausschließlich für
diesen serverseitig erzeugten Schreibpfad, nicht für die manuelle
Picker-Eingabe.

**Client:** Ein neues, in sich abgeschlossenes JS-Modul (`static/dragfill.js`)
übernimmt: Long-Press-Erkennung (Timer ab `touchstart` auf einer befüllten
Zelle), das Zeichnen/Aktualisieren des Pfeil-Overlays (einfache horizontale
SVG-Linie mit Pfeilspitze) während `touchmove`, die Berechnung des aktuell
markierten Zellbereichs, sowie das Absenden des Fill-Requests bei
`touchend`. Sobald der Long-Press-Timer feuert, ruft das Modul
`stopPropagation()` auf den weiteren Touch-Events auf — das verhindert
zuverlässig, dass die bestehende Wisch-Navigation (`swipe.js`, hört auf
`document`) denselben Drag fälschlich als Wochenwechsel-Geste interpretiert.

## Technische Umsetzung — Android (Demo-Modus)

Eine neue Gesten-Erkennung (Long-Press + horizontaler Drag, ähnliches
Muster wie die bereits vorhandene `WeekSwipeGesture`-Klasse, isolierte
Berechnungslogik + dünne Android-Glue-Schicht) auf den bestehenden
Zellen-Views. Sobald ein Long-Press auf einer befüllten Zelle erkannt wird,
ruft der Handler `requestDisallowInterceptTouchEvent(true)` auf dem
Eltern-`SwipeInterceptLayout` auf — das verhindert, dass die bestehende
Wochen-Wisch-Erkennung denselben Drag abfängt. Der Overlay-Pfeil wird über
eine einfache Canvas-Zeichnung realisiert. Beim Loslassen ruft die
Activity für jede noch leere Zielzelle die bereits vorhandene
`DemoApi.putEntry(cellId, value)`-Funktion auf (kein neuer Netzwerk-/
Schreibpfad nötig, reine Wiederverwendung des bestehenden Demo-Mechanismus).

## Testing

- **Server:** Neuer Endpoint — nur leere Zielzellen werden befüllt, belegte
  bleiben unangetastet; korrekter Pfeil-Platzhalter je nach Ursprungsart
  (Monteur-Text, Azubi-Status, Azubi-Monteurzuordnung); Begrenzung auf
  gültige/sichtbare Tage derselben Woche; admin-only.
- **Android:** Die isolierte Gesten-Erkennungsklasse (Range-Berechnung aus
  Start-/Aktuellposition, Richtungsbegrenzung nach rechts) mit
  Standard-Kotlin-Unit-Tests, analog zu `WeekSwipeGestureTest`. Der
  Demo-Fill-Vorgang selbst über Robolectric, analog zu den bestehenden
  `WeekActivityTest`-Mustern.
- **WebUI:** Kein JavaScript-Test-Framework in diesem Projekt (etablierte,
  akzeptierte Grenze) — die Geste wird wie bei den vorherigen Features
  manuell live im Emulator/Tablet verifiziert.

## Out of Scope

- Echte Schreib-API für Android (nicht-Demo-Modus).
- Rückfrage/Bestätigung beim Überschreiben — es wird schlicht nicht
  überschrieben.
- Ziehen nach links oder über die Wochengrenze hinaus.
- Live-Fingerfolgen der eigentlichen Zellen (nur der Pfeilkopf folgt live,
  die Zellen selbst ändern sich erst beim Loslassen).
