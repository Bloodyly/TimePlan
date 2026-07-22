# Wochen-Wischnavigation mit Animation — Design

## Problem

Zwischen Wochen wechselt man aktuell nur über kleine "Vorherige"/"Nächste"-
Links (WebUI) bzw. Buttons (Android). Auf einem Tablet ist eine
Wisch-Geste die natürlichere Bedienung, besonders bei häufigem
Vor-/Zurückblättern.

## Ziel

Eine überwiegend horizontale Wisch-Geste auf der Wochenraster-Seite löst
denselben Wochenwechsel aus wie die bestehenden Buttons/Links, mit einer
Gleit-Übergangsanimation. Sowohl im WebUI als auch in der Android-App.

## Interaktionsmodell

- **Auslöser, kein Live-Fingerfolgen:** Eine Wischbewegung über eine
  Mindestdistanz und -geschwindigkeit löst den Wechsel aus (identisch zur
  bestehenden Prev/Next-Logik). Die Ansicht folgt dem Finger nicht 1:1
  während der Bewegung — das hält die Umsetzung auf beiden Plattformen
  deutlich einfacher als echtes interaktives Paging.
- **Richtung:** Wischen nach links = nächste Woche, nach rechts =
  vorherige Woche (übliche Kalender-/Foto-App-Konvention).
- **Achsen-Erkennung:** Nur überwiegend horizontale Bewegungen zählen als
  Wisch-Geste. Vertikales Scrollen durch die Monteur-/Azubi-Liste wird
  nicht durch die Geste beeinträchtigt.
- **Geltungsbereich:** Nur die Wochenraster-Seite (WebUI `/week/{id}`,
  Android `WeekActivity`). Nicht auf Monteure- oder
  Einstellungen-Seiten.
- **Bestehende Navigation bleibt:** "Vorherige"/"Nächste"-Buttons/Links
  werden nicht entfernt, die Geste ist rein additiv.
- **Dialog-Konflikt:** Ist der Azubi-Picker-Dialog (WebUI) geöffnet, wird
  die Wisch-Erkennung deaktiviert, damit kein versehentlicher
  Wochenwechsel während einer Auswahl passiert.
- **Fehlerfall:** Schlägt der durch Wischen ausgelöste Wochenwechsel fehl
  (z.B. Server nicht erreichbar), greift der bereits bestehende
  Fehlerpfad (Android: `WeekLoadResult.Failure` → Fehlermeldung statt
  Raster; WebUI: normale Fehlerseite bei fehlgeschlagener Navigation) —
  keine Sonderbehandlung nötig.

## WebUI: Übergangsanimation

Jede Woche ist im WebUI eine eigene Server-Seite (`/week/{id}`), kein
SPA-Routing. Für den Gleit-Übergang wird die native **View Transitions
API** des Browsers genutzt (Cross-Document-Variante, seit neueren
Chrome-Versionen verfügbar — passend zu unserem Zielbrowser, Chrome auf
den Tablets):

- Ein `<meta name="view-transition" content="same-origin">`-Tag in
  `base.html` aktiviert Cross-Document View Transitions für
  Navigationen innerhalb der App.
- Ein kleines neues Skript (`static/swipe.js`) erkennt die Wisch-Geste
  (Touch-Events: `touchstart`/`touchmove`/`touchend`, Achsen- und
  Schwellwert-Prüfung wie oben beschrieben) auf der Wochenraster-Seite
  und navigiert bei erkannter Geste per `location.href` zur
  Vorherige/Nächste-URL (dieselben URLs, die die bestehenden Links schon
  verwenden).
- CSS (`::view-transition-old`/`::view-transition-new`) definiert die
  Slide-Richtung passend zur Wisch-Richtung.
- **Kein Fallback-Code nötig:** Browser ohne Unterstützung ignorieren
  das Meta-Tag und navigieren normal (unanimiert) — die Kernfunktion
  (Wischen wechselt die Woche) funktioniert unabhängig von
  Animations-Unterstützung.

## Android: Übergangsanimation

- Eine neue, von Android unabhängige Klasse (reine Kotlin-Logik, keine
  View/Context-Abhängigkeiten) kapselt die Gesten-Mathematik: gegeben
  Start- und End-Koordinaten sowie Zeitdauer einer Bewegung, entscheidet
  sie, ob es sich um eine gültige horizontale Wisch-Geste handelt und in
  welche Richtung. Das macht die Erkennungslogik unabhängig von
  Android-Test-Infrastruktur (Robolectric) mit normalen Kotlin-Unit-Tests
  testbar.
- `WeekActivity` registriert einen Touch-Listener auf dem
  Wochenraster-Container, der Touch-Events an diese Klasse weiterreicht.
  Bei erkannter Geste: die aktuelle Ansicht (Kopfzeile + Liste) gleitet
  per Standard-View-Animation (`ViewPropertyAnimator`, keine neue
  Abhängigkeit) in Wischrichtung raus, `currentWeekId` wird auf die
  Nachbarwoche gesetzt und `loadCurrentWeek()` (bereits vorhanden)
  aufgerufen, die neue Ansicht gleitet von der Gegenseite rein.
- Kein Umbau auf ViewPager2 — die Geste ist ein dünner Zusatzlayer über
  der bestehenden Activity-Struktur.

## Testing

**Server:** Keine Änderungen, keine neuen Tests — die Geste löst nur die
bereits bestehenden, bereits getesteten `/week/{id}`-Navigationen aus.

**Android:** Die isolierte Gesten-Erkennungsklasse wird mit
Standard-Kotlin-Unit-Tests abgedeckt (verschiedene Kombinationen aus
Distanz/Richtung/Dauer, inkl. Grenzfälle wie "zu kurz", "zu langsam",
"überwiegend vertikal"). Die Animation/Glue-Code in `WeekActivity` bleibt
wie bisher nur indirekt über bestehende Robolectric-Tests abgedeckt.

**WebUI:** Kein JavaScript-Test-Framework in diesem Projekt (bewusste
Entscheidung, passend zur bestehenden "kein Build-Tooling"-Architektur).
Die Wisch-Erkennung wird manuell live im Emulator/Tablet verifiziert
(wie bereits beim Azubi-Picker-Dialog geschehen), nicht automatisiert.
Das ist eine akzeptierte Grenze.

## Out of Scope

- Echtes interaktives Fingerfolgen (Live-Drag mit Zurückfedern).
- Wisch-Navigation auf der Monteure- oder Einstellungen-Seite.
- ViewPager2-Umbau der Android-App.
- Automatisierte Browser-Tests für die Wisch-Geste im WebUI.
