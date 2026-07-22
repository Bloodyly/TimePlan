# Samstag/Sonntag anzeigen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Zwei zentral am Server gespeicherte Schalter ("Samstag anzeigen", "Sonntag anzeigen") blenden die jeweilige Tages-Spalte im Wochenraster aus — identisch in WebUI und Android-App, ohne Datenverlust.

**Architecture:** Eine neue Single-Row-Tabelle `app_settings` speichert die zwei Booleans. Eine neue Funktion `weeks.visible_week_dates(conn, week_id)` ist der einzige Ort, an dem die Filterung passiert — sowohl das WebUI-Wochenraster als auch die `GET /api/v1/weeks/{week_id}`-Antwort (die die Android-App ohnehin bei jedem Wochenwechsel abruft) rufen diese eine Funktion auf. Beide Clients rendern einfach so viele Tages-Spalten wie sie bekommen; es gibt keine Filterlogik in Templates oder Kotlin-Code. Eine neue WebUI-Seite `/settings` lässt den Admin die Schalter umstellen und broadcastet die Änderung über den bestehenden WebSocket-Hub, damit offene WebUI-Sessions sofort neu laden.

**Tech Stack:** FastAPI, SQLite, Jinja2, htmx, Kotlin/Robolectric (bestehender Stack, keine neue Abhängigkeit).

## Global Constraints

- Filterung ist rein visuell: Einträge an ausgeblendeten Tagen bleiben in der Datenbank unangetastet und tauchen bei Wiedereinschalten sofort wieder auf.
- `weeks.parse_cell_id` bleibt unverändert und validiert weiterhin gegen die volle (ungefilterte) `week_dates(week_id)` — ein Cell-ID für einen ausgeblendeten Tag muss weiterhin als gültig erkannt werden, nur eben nicht mehr gerendert.
- Kein neuer API-Endpoint für die Android-App — sie liest die (ggf. gekürzte) `dates`-Liste aus der bestehenden `GET /api/v1/weeks/{week_id}`-Antwort.
- Kein Live-Push der Einstellung zur Android-App (kein WebSocket-Client dort) — Out of Scope laut Spec.
- Android-Demo-Modus (`DemoApi`) bleibt unverändert und zeigt weiterhin immer 7 Tage — Out of Scope laut Spec.
- Wochentags-Beschriftungen (WebUI `day_labels`, Android Header) werden aus dem tatsächlichen Datum abgeleitet (`date.weekday()` / `date.dayOfWeek`), nicht aus der Listenposition — sonst wird bei einer Lücke (z.B. nur Samstag ausgeblendet) der Sonntag fälschlich als "Sa" beschriftet.
- Alle bestehenden Tests müssen nach jedem Task weiterhin grün sein: `cd server && .venv/bin/python -m pytest -q` (Baseline vor Task 1: 58 passed).

---

### Task 1: `app_settings`-Tabelle + Repo

**Files:**
- Modify: `server/app/db.py` (SCHEMA-Konstante)
- Create: `server/app/repos/app_settings.py`
- Test: `server/tests/test_app_settings_repo.py`

**Interfaces:**
- Produces: `get_display_settings(conn) -> dict` mit Keys `"show_saturday": bool`, `"show_sunday": bool`. `update_display_settings(conn, show_saturday: bool, show_sunday: bool, actor=("web","web-admin")) -> dict` (gleiche Rückgabeform). Beide werden in Task 2 (server-seitige Filterung) und Task 3 (WebUI-Settings-Seite) importiert via `from ..repos import app_settings as app_settings_repo`.

- [ ] **Step 1: Failing Test schreiben**

Erstelle `server/tests/test_app_settings_repo.py`:

```python
from app.repos import app_settings


def test_default_settings_are_both_visible(client):
    conn = client.app.state.db
    settings = app_settings.get_display_settings(conn)
    assert settings == {"show_saturday": True, "show_sunday": True}


def test_update_display_settings_persists(client):
    conn = client.app.state.db
    updated = app_settings.update_display_settings(conn, False, True)
    assert updated == {"show_saturday": False, "show_sunday": True}
    assert app_settings.get_display_settings(conn) == {"show_saturday": False, "show_sunday": True}

    updated_again = app_settings.update_display_settings(conn, True, False)
    assert updated_again == {"show_saturday": True, "show_sunday": False}
```

Hinweis: die `client`-Fixture (aus `conftest.py`) baut über `create_app()` eine frische Test-DB inklusive `init_db()` auf — die neue Tabelle und ihre Default-Zeile müssen also über die normale Schema-Initialisierung entstehen, nicht über einen separaten Migrations-Schritt.

- [ ] **Step 2: Test laufen lassen, sicherstellen dass er fehlschlägt**

Run: `cd /home/admin/Projekte/TimePlan/server && .venv/bin/python -m pytest tests/test_app_settings_repo.py -v`
Expected: FAIL mit `ModuleNotFoundError: No module named 'app.repos.app_settings'` (Modul existiert noch nicht)

- [ ] **Step 3: Tabelle + Seed-Zeile in db.py ergänzen**

In `server/app/db.py` steht aktuell am Ende der `SCHEMA`-Konstante:

```python
CREATE TABLE IF NOT EXISTS audit_log(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts TEXT NOT NULL,
  actor_type TEXT NOT NULL,
  actor_id TEXT NOT NULL,
  action TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  detail TEXT
);
"""
```

Ändere das zu:

```python
CREATE TABLE IF NOT EXISTS audit_log(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts TEXT NOT NULL,
  actor_type TEXT NOT NULL,
  actor_id TEXT NOT NULL,
  action TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  detail TEXT
);
CREATE TABLE IF NOT EXISTS app_settings(
  id INTEGER PRIMARY KEY CHECK(id = 1),
  show_saturday INTEGER NOT NULL DEFAULT 1,
  show_sunday INTEGER NOT NULL DEFAULT 1,
  revision INTEGER NOT NULL
);
INSERT OR IGNORE INTO app_settings(id, show_saturday, show_sunday, revision)
  VALUES (1, 1, 1, 0);
"""
```

(Die `CHECK(id = 1)` erzwingt genau eine Zeile; `INSERT OR IGNORE` sorgt dafür, dass ein bereits vorhandener, ggf. schon geänderter Datensatz bei jedem `init_db()`-Aufruf unangetastet bleibt.)

- [ ] **Step 4: Repo-Modul erstellen**

Erstelle `server/app/repos/app_settings.py`:

```python
from .. import db


def get_display_settings(conn) -> dict:
    row = conn.execute(
        "SELECT show_saturday, show_sunday FROM app_settings WHERE id=1"
    ).fetchone()
    return {"show_saturday": bool(row["show_saturday"]),
            "show_sunday": bool(row["show_sunday"])}


def update_display_settings(conn, show_saturday: bool, show_sunday: bool,
                            actor=("web", "web-admin")) -> dict:
    with db.tx(conn):
        revision = db.record_change(conn, "app_settings", "1", "updated")
        conn.execute(
            "UPDATE app_settings SET show_saturday=?, show_sunday=?, revision=?"
            " WHERE id=1",
            (int(show_saturday), int(show_sunday), revision),
        )
        db.audit(conn, actor[0], actor[1], "updated", "app_settings", "1", None)
    return get_display_settings(conn)
```

- [ ] **Step 5: Test laufen lassen, sicherstellen dass er besteht**

Run: `.venv/bin/python -m pytest tests/test_app_settings_repo.py -v`
Expected: beide Tests PASS

- [ ] **Step 6: Volle Suite laufen lassen**

Run: `.venv/bin/python -m pytest -q`
Expected: alle Tests grün (58 vorher + 2 neu = 60 passed)

- [ ] **Step 7: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add server/app/db.py server/app/repos/app_settings.py server/tests/test_app_settings_repo.py
git commit -m "server: app_settings-Tabelle + Repo für Sa/So-Sichtbarkeit"
```

---

### Task 2: `visible_week_dates` + korrekte Wochentags-Beschriftung (WebUI-Raster + API)

**Files:**
- Modify: `server/app/weeks.py`
- Modify: `server/app/web/routes.py` (`_grid_context`)
- Modify: `server/app/api/routes.py` (`get_week`)
- Modify: `server/app/templates/week.html` (dynamisches `colspan`)
- Test: `server/tests/test_weeks.py`, `server/tests/test_web_week.py`

**Interfaces:**
- Consumes: `app_settings.get_display_settings(conn)` aus Task 1.
- Produces: `weeks.visible_week_dates(conn, week_id: str) -> list[datetime.date]` — gefilterte Teilmenge von `week_dates(week_id)`, Reihenfolge und `datetime.date`-Typ unverändert, nur Sa/So ggf. entfernt. Wird von `_grid_context` und `get_week` konsumiert.

- [ ] **Step 1: Failing Tests schreiben**

`server/tests/test_weeks.py` beginnt aktuell mit:

```python
import datetime

import pytest

from app import weeks
```

Ändere das zu:

```python
import datetime

import pytest

from app import weeks
from app.repos import app_settings
```

Füge dann (nach `test_cell_id_roundtrip`, vor `test_get_week_endpoint`) drei neue Tests hinzu:

```python
def test_visible_week_dates_both_shown(client):
    conn = client.app.state.db
    dates = weeks.visible_week_dates(conn, "2026-W31")
    assert len(dates) == 7
    assert dates[-1] == datetime.date(2026, 8, 2)  # So


def test_visible_week_dates_saturday_hidden(client):
    conn = client.app.state.db
    app_settings.update_display_settings(conn, False, True)
    dates = weeks.visible_week_dates(conn, "2026-W31")
    assert datetime.date(2026, 8, 1) not in dates  # Sa fehlt
    assert datetime.date(2026, 8, 2) in dates       # So bleibt
    assert len(dates) == 6


def test_visible_week_dates_both_hidden(client):
    conn = client.app.state.db
    app_settings.update_display_settings(conn, False, False)
    dates = weeks.visible_week_dates(conn, "2026-W31")
    assert len(dates) == 5
    assert dates[-1] == datetime.date(2026, 7, 31)  # Fr
```

(`client` ist die bestehende Fixture aus `conftest.py`, die eine echte `TestClient`/App-Instanz mit initialisierter DB liefert — hier nur als Quelle für `client.app.state.db` genutzt, kein HTTP-Request nötig.)

Der bestehende Test `test_get_week_endpoint` bleibt unverändert — er erwartet weiterhin `len(dates) == 7`, was für den unveränderten Default (beide Tage sichtbar) korrekt bleibt.

Füge außerdem in `server/tests/test_web_week.py` hinzu:

```python
def test_week_page_hides_saturday_when_disabled(admin):
    from app.repos import app_settings
    app_settings.update_display_settings(admin.app.state.db, False, True)
    r = admin.get("/week/2026-W31")
    assert r.status_code == 200
    assert "Sa 01.08." not in r.text
    assert "So 02.08." in r.text


def test_week_page_labels_sunday_correctly_when_saturday_hidden(admin):
    # Regression guard: Sonntag darf nicht als "Sa" beschriftet werden, nur
    # weil er an Sa's alter Listenposition landet.
    from app.repos import app_settings
    app_settings.update_display_settings(admin.app.state.db, False, True)
    r = admin.get("/week/2026-W31")
    assert "So 02.08." in r.text
    assert "Sa 02.08." not in r.text
```

- [ ] **Step 2: Tests laufen lassen, sicherstellen dass sie fehlschlagen**

Run: `.venv/bin/python -m pytest tests/test_weeks.py tests/test_web_week.py -v`
Expected: die drei neuen `test_visible_week_dates_*`-Tests und die zwei neuen `test_week_page_*`-Tests FAILEN (`visible_week_dates` existiert noch nicht bzw. das Raster zeigt weiterhin immer alle 7 Tage)

- [ ] **Step 3: `visible_week_dates` in weeks.py ergänzen**

`server/app/weeks.py` beginnt aktuell mit:

```python
import datetime
import re

from . import db

WEEK_RE = re.compile(r"^(\d{4})-W(\d{2})$")
```

Ändere das zu:

```python
import datetime
import re

from . import db
from .repos import app_settings as app_settings_repo

WEEK_RE = re.compile(r"^(\d{4})-W(\d{2})$")
```

Füge direkt nach der bestehenden `week_dates`-Funktion (nach Zeile `return [datetime.date.fromisocalendar(year, week, d) for d in range(1, 8)]`) eine neue Funktion ein:

```python
def visible_week_dates(conn, week_id: str) -> list[datetime.date]:
    settings = app_settings_repo.get_display_settings(conn)
    return [d for d in week_dates(week_id)
            if d.weekday() < 5
            or (d.weekday() == 5 and settings["show_saturday"])
            or (d.weekday() == 6 and settings["show_sunday"])]
```

- [ ] **Step 4: `_grid_context` in web/routes.py umstellen**

In `server/app/web/routes.py` steht in `_grid_context`:

```python
    week = weeklib.get_or_create_week(conn, week_id)
    dates = weeklib.week_dates(week_id)
    all_workers = [w for w in workers_repo.list_workers(conn) if w["active"]]
```

Ändere das zu:

```python
    week = weeklib.get_or_create_week(conn, week_id)
    dates = weeklib.visible_week_dates(conn, week_id)
    all_workers = [w for w in workers_repo.list_workers(conn) if w["active"]]
```

Wenige Zeilen weiter steht:

```python
        "day_labels": [f"{WEEKDAY_NAMES[i]} {d.strftime('%d.%m.')}"
                       for i, d in enumerate(dates)],
```

Ändere das zu (Beschriftung aus dem tatsächlichen Wochentag des Datums, nicht aus der Listenposition):

```python
        "day_labels": [f"{WEEKDAY_NAMES[d.weekday()]} {d.strftime('%d.%m.')}"
                       for d in dates],
```

- [ ] **Step 5: `get_week` in api/routes.py umstellen**

In `server/app/api/routes.py` steht in `get_week`:

```python
        week = weeklib.get_or_create_week(conn, week_id)
        dates = [d.isoformat() for d in weeklib.week_dates(week_id)]
```

Ändere das zu:

```python
        week = weeklib.get_or_create_week(conn, week_id)
        dates = [d.isoformat() for d in weeklib.visible_week_dates(conn, week_id)]
```

- [ ] **Step 6: `colspan` in week.html dynamisch machen**

In `server/app/templates/week.html` steht:

```html
    <tr class="separator"><td colspan="8">Mitarb. / Azubis</td></tr>
```

Ändere das zu:

```html
    <tr class="separator"><td colspan="{{ dates|length + 1 }}">Mitarb. / Azubis</td></tr>
```

- [ ] **Step 7: Tests laufen lassen, sicherstellen dass sie bestehen**

Run: `.venv/bin/python -m pytest tests/test_weeks.py tests/test_web_week.py -v`
Expected: alle Tests in beiden Dateien PASS, inklusive der neuen und aller bestehenden (insbesondere `test_get_week_endpoint`, das weiterhin `len(dates) == 7` erwartet — Default ist unverändert beide sichtbar)

- [ ] **Step 8: Volle Suite laufen lassen**

Run: `.venv/bin/python -m pytest -q`
Expected: alle Tests grün (60 vorher + 5 neu = 65 passed)

- [ ] **Step 9: Commit**

```bash
git add server/app/weeks.py server/app/web/routes.py server/app/api/routes.py server/app/templates/week.html server/tests/test_weeks.py server/tests/test_web_week.py
git commit -m "server: visible_week_dates filtert Sa/So in WebUI-Raster und API"
```

---

### Task 3: WebUI-Einstellungsseite `/settings`

**Files:**
- Modify: `server/app/web/routes.py` (neue Routen)
- Create: `server/app/templates/settings.html`
- Modify: `server/app/templates/base.html` (Nav-Link)
- Modify: `server/app/static/live.js` (`settings.updated`-Event)
- Test: `server/tests/test_web_settings.py`

**Interfaces:**
- Consumes: `app_settings.get_display_settings`/`update_display_settings` aus Task 1.
- Produces: `GET /settings` (admin-only, rendert aktuelle Schalterstellung), `POST /settings` (admin-only, persistiert, broadcastet `settings.updated`, redirect zu `/settings`). Keine neuen Interfaces für spätere Tasks — dieser Task ist das Ende der Server-Kette.

- [ ] **Step 1: Failing Tests schreiben**

Erstelle `server/tests/test_web_settings.py`:

```python
import pytest

from app.repos import app_settings


@pytest.fixture()
def admin(client):
    client.post("/login", data={"password": "pw"})
    return client


def test_settings_page_requires_login(client):
    assert client.get("/settings", follow_redirects=False).status_code == 303


def test_settings_page_shows_current_values(admin):
    r = admin.get("/settings")
    assert r.status_code == 200
    assert "Samstag anzeigen" in r.text
    assert "Sonntag anzeigen" in r.text


def test_settings_update_persists_and_redirects(admin):
    r = admin.post("/settings", data={"show_saturday": "1"}, follow_redirects=False)
    assert r.status_code == 303
    assert r.headers["location"] == "/settings"
    assert app_settings.get_display_settings(admin.app.state.db) == {
        "show_saturday": True, "show_sunday": False}


def test_settings_update_both_unchecked_disables_both(admin):
    admin.post("/settings", data={})
    assert app_settings.get_display_settings(admin.app.state.db) == {
        "show_saturday": False, "show_sunday": False}
```

(Checkbox-Semantik wie beim bestehenden `/workers`-Formular: ein nicht abgesendetes Feld bedeutet "nicht angehakt", vgl. `test_deactivate_worker` in `test_web_workers.py`.)

- [ ] **Step 2: Tests laufen lassen, sicherstellen dass sie fehlschlagen**

Run: `.venv/bin/python -m pytest tests/test_web_settings.py -v`
Expected: FAIL mit 404 (Route existiert noch nicht)

- [ ] **Step 3: Routen in web/routes.py ergänzen**

Der Import-Block am Anfang von `server/app/web/routes.py` lautet aktuell:

```python
from .. import db
from .. import weeks as weeklib
from ..repos import entries as entries_repo
from ..repos import workers as workers_repo
```

Ändere das zu:

```python
from .. import db
from .. import weeks as weeklib
from ..repos import app_settings as app_settings_repo
from ..repos import entries as entries_repo
from ..repos import workers as workers_repo
```

Füge am Ende der Datei (nach der bestehenden `workers_update`-Funktion) hinzu:

```python
@web_router.get("/settings")
def settings_page(request: Request):
    require_admin(request)
    settings = app_settings_repo.get_display_settings(request.app.state.db)
    return templates.TemplateResponse(request, "settings.html", {"settings": settings})


@web_router.post("/settings")
async def settings_update(request: Request, show_saturday: str = Form(None),
                          show_sunday: str = Form(None)):
    require_admin(request)
    app_settings_repo.update_display_settings(
        request.app.state.db, show_saturday == "1", show_sunday == "1")
    await request.app.state.hub.broadcast({
        "event": "settings.updated",
        "revision": db.latest_revision(request.app.state.db)})
    return RedirectResponse("/settings", status_code=303)
```

- [ ] **Step 4: Template erstellen**

Erstelle `server/app/templates/settings.html`:

```html
{% extends "base.html" %}
{% block content %}
<div class="workers-page">
  <h1>Einstellungen</h1>
  <form method="post" action="/settings">
    <p>
      <label>
        <input type="checkbox" name="show_saturday" value="1"
               {% if settings.show_saturday %}checked{% endif %}>
        Samstag anzeigen
      </label>
    </p>
    <p>
      <label>
        <input type="checkbox" name="show_sunday" value="1"
               {% if settings.show_sunday %}checked{% endif %}>
        Sonntag anzeigen
      </label>
    </p>
    <button type="submit" class="btn-primary">Speichern</button>
  </form>
</div>
{% endblock %}
```

- [ ] **Step 5: Nav-Link in base.html ergänzen**

In `server/app/templates/base.html` steht:

```html
    <nav>
      <a href="/">Wochenplan</a>
      <a href="/workers">Monteure</a>
      <a href="/logout">Abmelden</a>
    </nav>
```

Ändere das zu:

```html
    <nav>
      <a href="/">Wochenplan</a>
      <a href="/workers">Monteure</a>
      <a href="/settings">Einstellungen</a>
      <a href="/logout">Abmelden</a>
    </nav>
```

- [ ] **Step 6: live.js um settings.updated erweitern**

In `server/app/static/live.js` steht:

```js
      } else if (ev.event === "workers.updated" || ev.event === "week.updated") {
        location.reload();
      }
```

Ändere das zu:

```js
      } else if (ev.event === "workers.updated" || ev.event === "week.updated"
                 || ev.event === "settings.updated") {
        location.reload();
      }
```

- [ ] **Step 7: Tests laufen lassen, sicherstellen dass sie bestehen**

Run: `.venv/bin/python -m pytest tests/test_web_settings.py -v`
Expected: alle 4 Tests PASS

- [ ] **Step 8: Volle Suite laufen lassen**

Run: `.venv/bin/python -m pytest -q`
Expected: alle Tests grün (65 vorher + 4 neu = 69 passed)

- [ ] **Step 9: Commit**

```bash
git add server/app/web/routes.py server/app/templates/settings.html server/app/templates/base.html server/app/static/live.js server/tests/test_web_settings.py
git commit -m "webui: Einstellungsseite für Sa/So-Sichtbarkeit"
```

---

### Task 4: Android — Wochentags-Beschriftung aus dem Datum statt der Listenposition

**Files:**
- Modify: `android/app/src/main/java/de/fs/timeplan/WeekActivity.kt`
- Modify: `android/app/src/test/java/de/fs/timeplan/week/WeekActivityTest.kt`
- Test: `android/app/src/test/java/de/fs/timeplan/grid/WeekGridBuilderTest.kt`

**Interfaces:**
- Consumes: nichts Neues — `dates: List<String>` kommt weiterhin über `WeekBundle`/`WeekPresenter` (unverändert, bereits variable-Länge-fähig, siehe unten). `WeekGridBuilder.build(weekId, dates, workers, entries)` und `WeekGridAdapter` sind bereits vollständig längen-agnostisch (iterieren generisch über `dates`/`cellTexts`) und werden in diesem Task **nicht verändert** — nur der Header-Beschriftungs-Bug in `WeekActivity.renderDayHeader` wird behoben.
- Produces: nichts für spätere Tasks — letzter Task des Plans.

**Hintergrund:** `WeekGridBuilder.build` und `WeekGridAdapter.WorkerRowViewHolder.bind` iterieren bereits generisch über `dates`/`cellTexts` beliebiger Länge (verifiziert: kein hartkodiertes `7` im Code) — sie brauchen keine Änderung. Nur `WeekActivity.renderDayHeader` leitet die Wochentags-Beschriftung ("Mo".."So") aktuell aus der **Listenposition** ab (`WEEKDAY_LABELS.getOrElse(index)`), nicht aus dem tatsächlichen Datum. Das ist harmlos, solange immer alle 7 Tage lückenlos ankommen — sobald der Server aber z.B. nur Samstag ausblendet (Sonntag bleibt), landet Sonntag an Listenposition 5 und würde fälschlich als "Sa" beschriftet.

- [ ] **Step 1: Failing Test schreiben**

Füge in `android/app/src/test/java/de/fs/timeplan/week/WeekActivityTest.kt` nach der bestehenden `clickCell`-Hilfsfunktion (vor der ersten `@Test fun \`tapping an azubi cell...\``) folgenden Test ein:

```kotlin
    @Test
    fun `day header labels reflect the actual weekday even with a gap`() {
        val controller = Robolectric.buildActivity(WeekActivity::class.java)
        controller.setup()
        val activity = controller.get()

        // Mo 27.07. .. Fr 31.07., dann So 02.08. (Sa 01.08. fehlt - simuliert
        // eine deaktivierte Samstag-Sichtbarkeit).
        val dates = listOf(
            "2026-07-27", "2026-07-28", "2026-07-29",
            "2026-07-30", "2026-07-31", "2026-08-02"
        )
        val method = WeekActivity::class.java.getDeclaredMethod(
            "renderDayHeader", List::class.java
        )
        method.isAccessible = true
        method.invoke(activity, dates)

        val headerContainer = activity.findViewById<android.widget.LinearLayout>(
            de.fs.timeplan.R.id.dayHeaderCells
        )
        val lastLabel = (headerContainer.getChildAt(5) as TextView).text.toString()
        assertTrue("expected label to start with 'So', was: $lastLabel",
                   lastLabel.startsWith("So"))
    }
```

- [ ] **Step 2: Test laufen lassen, sicherstellen dass er fehlschlägt**

Run: `cd /home/admin/Projekte/TimePlan/android && JAVA_HOME="$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)" ./gradlew testDebugUnitTest --tests "de.fs.timeplan.week.WeekActivityTest" --console=plain`
Expected: FAIL — Label an Position 5 ist `"Sa\n02.08."` statt `"So\n02.08."`

- [ ] **Step 3: `renderDayHeader` in WeekActivity.kt korrigieren**

In `android/app/src/main/java/de/fs/timeplan/WeekActivity.kt` steht:

```kotlin
    private fun renderDayHeader(dates: List<String>) {
        dayHeaderCells.removeAllViews()
        dates.forEachIndexed { index, dateIso ->
            val date = LocalDate.parse(dateIso)
            val label = "${WEEKDAY_LABELS.getOrElse(index) { "" }}\n${"%02d.%02d.".format(date.dayOfMonth, date.monthValue)}"
            val cell = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@WeekActivity, R.color.ink))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            dayHeaderCells.addView(cell)
        }
    }
```

Ändere das zu (Beschriftung aus `date.dayOfWeek` statt aus der Listenposition `index`;
`DayOfWeek.value` ist 1=Montag..7=Sonntag, `WEEKDAY_LABELS` ist 0=Mo..6=So, daher `- 1`):

```kotlin
    private fun renderDayHeader(dates: List<String>) {
        dayHeaderCells.removeAllViews()
        dates.forEach { dateIso ->
            val date = LocalDate.parse(dateIso)
            val weekdayLabel = WEEKDAY_LABELS[date.dayOfWeek.value - 1]
            val label = "$weekdayLabel\n${"%02d.%02d.".format(date.dayOfMonth, date.monthValue)}"
            val cell = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@WeekActivity, R.color.ink))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            dayHeaderCells.addView(cell)
        }
    }
```

- [ ] **Step 4: Test laufen lassen, sicherstellen dass er besteht**

Run: `JAVA_HOME="$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)" ./gradlew testDebugUnitTest --tests "de.fs.timeplan.week.WeekActivityTest" --console=plain`
Expected: PASS, alle Tests in `WeekActivityTest` grün

- [ ] **Step 5: Regressionstest für variable Spaltenzahl in WeekGridBuilderTest ergänzen**

`android/app/src/test/java/de/fs/timeplan/grid/WeekGridBuilderTest.kt` hat aktuell zwei Tests
(`builds monteur rows, separator, and azubi rows...` und `ignores inactive workers`), beide
nutzen `Worker(id, number, name, category, position, active, revision)` und
`WeekRow.Monteur.cellTexts: List<String?>`. Füge einen dritten Test in dieselbe Klasse ein
(nach `ignores inactive workers`), der `WeekGridBuilder.build` mit einer 5-elementigen
`dates`-Liste (Mo-Fr, wie bei komplett ausgeblendetem Wochenende) aufruft und verifiziert,
dass die erzeugte `WeekRow.Monteur`-Zeile genau 5 Zellen hat:

```kotlin
    @Test
    fun `handles a 5-day week without saturday and sunday columns`() {
        val monteur = Worker("w-1", "144", "Albrecht", "monteur", 1, true, 1)
        val dates = listOf("2026-07-27", "2026-07-28", "2026-07-29", "2026-07-30", "2026-07-31")

        val rows = WeekGridBuilder.build("2026-W31", dates, listOf(monteur), emptyList())

        val monteurRow = rows[0] as WeekRow.Monteur
        assertEquals(5, monteurRow.cellTexts.size)
    }
```

- [ ] **Step 6: Test laufen lassen, sicherstellen dass er besteht**

Run: `JAVA_HOME="$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)" ./gradlew testDebugUnitTest --tests "de.fs.timeplan.grid.WeekGridBuilderTest" --console=plain`
Expected: PASS

- [ ] **Step 7: Volle Android-Suite + Build laufen lassen**

Run: `JAVA_HOME="$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)" ./gradlew testDebugUnitTest assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL, alle Tests grün

- [ ] **Step 8: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add android/app/src/main/java/de/fs/timeplan/WeekActivity.kt android/app/src/test/java/de/fs/timeplan/week/WeekActivityTest.kt android/app/src/test/java/de/fs/timeplan/grid/WeekGridBuilderTest.kt
git commit -m "android: Wochentags-Beschriftung aus dem Datum statt der Listenposition ableiten"
```

---

## Nach Abschluss aller Tasks (manuelle Verifikation)

Serverseitige Tests decken Persistenz, Filterlogik und Beschriftungs-Korrektheit vollständig ab. Manuell im Browser (Emulator o.ä.) zu prüfen, da UI-/Live-Sync-Verhalten nicht automatisiert getestet wird:

1. `/settings` aufrufen, beide Schalter deaktivieren, speichern → Wochenraster zeigt nur noch Mo-Fr, Trennzeile über den Azubis reicht über die volle (jetzt schmalere) Breite.
2. Bereits vorhandene Einträge an einem jetzt ausgeblendeten Tag (z.B. vorher testweise einen Samstag-Eintrag anlegen, dann Sa deaktivieren) bleiben in der DB — nach Wiedereinschalten sofort wieder sichtbar.
3. Zwei Browser-Tabs offen, in einem `/settings` ändern → der andere Tab (offene `/week/...`-Seite) lädt automatisch neu und zeigt die neue Spaltenzahl (Live-Sync via `settings.updated`).
4. Android-App (Emulator/Tablet) nach einer Server-seitigen Änderung neu laden (Wochenwechsel oder Neustart) → zeigt dieselbe Spaltenzahl wie das WebUI für dieselbe Einstellung.

## Danach

Feature-Branch erstellen (`feature/weekend-visibility-toggle`), alle vier Tasks committen (bereits während der Tasks passiert), finale Whole-Branch-Review dispatchen, Findings beheben, mergen nach `main` — wie bei den vorherigen Phasen (`superpowers:finishing-a-development-branch`-Muster).
