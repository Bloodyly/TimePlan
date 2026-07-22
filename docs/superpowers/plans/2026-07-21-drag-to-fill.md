# Drag-to-Fill mit Pfeil Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Long-Press auf eine befüllte Zelle erlaubt es, per Ziehen nach rechts weitere leere Tage in derselben Zeile zu markieren (mit live mitwachsendem Pfeil) und beim Loslassen zu befüllen — WebUI mit echter Server-Persistenz, Android nur im Demo-Modus.

**Architecture:** WebUI bekommt einen neuen, schmalen Server-Endpoint (`POST /web/cells/{origin_cell_id}/fill`), der nur leere Zielzellen in derselben Zeile/Woche befüllt, plus ein neues eigenständiges JS-Modul für Geste + SVG-Pfeil-Overlay. Android bekommt eine reine, isolierte Kotlin-Klasse für die Bereichs-Berechnung (analog `WeekSwipeGesture`) plus eine dünne Verkabelung im bestehenden Demo-Modus (Overlay-View + `DemoApi.putEntry`-Wiederverwendung). Beide Plattformen respektieren dieselbe Inhalts-Logik: Monteur-Freitext-Ursprung → Pfeil-Platzhalter, Azubi-Status-Ursprung → echter Status, Azubi-Monteurzuordnung-Ursprung → Text-Pfeil-Platzhalter.

**Tech Stack:** FastAPI/Pydantic (Server), Vanilla JS + SVG (WebUI), Kotlin/Android Views (Android, kein Compose).

## Global Constraints

- Auslöser: Long-Press **nur auf einer bereits befüllten Zelle**. Leere Zellen: unverändertes Verhalten (normales Antippen).
- Richtung: ausschließlich nach rechts, in derselben Zeile, begrenzt auf die letzte sichtbare Tagesspalte.
- Beim Loslassen: **nur leere** Zielzellen im markierten Bereich werden befüllt. Zellen mit vorhandenem Eintrag bleiben unverändert, keine Rückfrage.
- Inhalts-Logik (gilt identisch auf beiden Plattformen):
  - Monteur-Freitext-Ursprung → Zielzellen bekommen `"→"`.
  - Azubi-Status-Ursprung (Schule/Krank/Urlaub) → Zielzellen bekommen den echten Status-Text.
  - Azubi-Monteurzuordnung-Ursprung → Zielzellen bekommen `"----->"`.
- Android: **nur im Demo-Modus** (kein echter Schreibzugriff zum Server vorhanden — Out of Scope, siehe Spec).
- WebUI hat kein JavaScript-Test-Framework — die Geste selbst wird manuell verifiziert, nicht automatisiert getestet.
- Alle bestehenden Tests müssen nach jedem Task weiterhin grün sein: `cd server && .venv/bin/python -m pytest -q` (Baseline vor Task 1: 75 passed) und `cd android && ./gradlew testDebugUnitTest assembleDebug` (Baseline: BUILD SUCCESSFUL).

---

### Task 1: Server — Fill-Endpoint

**Files:**
- Modify: `server/app/web/routes.py`
- Test: `server/tests/test_web_cells.py`

**Interfaces:**
- Produces: `POST /web/cells/{origin_cell_id}/fill` mit JSON-Body `{"target_cell_ids": [...]}`, Antwort `{"filled": [...]}` (Liste der tatsächlich befüllten Cell-IDs). Admin-only. Wird in Task 2 von `dragfill.js` per `fetch()` aufgerufen.

- [ ] **Step 1: Failing Tests schreiben**

Füge in `server/tests/test_web_cells.py` (am Ende der Datei) hinzu:

```python
def test_fill_cells_fills_only_empty_targets(admin, device_headers):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "monteur")  # Do 2026-07-30
    admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Baustelle A"})

    # Zielzellen (alle nach dem Ursprung Do 30.07.): Fr (leer), Sa (bereits belegt), So (leer)
    worker_id = cell_id.split("_")[1]
    fr = f"2026-W31_{worker_id}_2026-07-31"
    sa = f"2026-W31_{worker_id}_2026-08-01"
    so = f"2026-W31_{worker_id}_2026-08-02"
    admin.post(f"/web/cells/{sa}/entries", data={"text": "Schon belegt"})

    r = admin.post(f"/web/cells/{cell_id}/fill",
                   json={"target_cell_ids": [fr, sa, so]})
    assert r.status_code == 200
    assert sorted(r.json()["filled"]) == sorted([fr, so])

    api = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    by_cell = {e["cell_id"]: e["content"]["text"] for e in api["entries"]}
    assert by_cell[fr] == "→"
    assert by_cell[so] == "→"
    assert by_cell[sa] == "Schon belegt"  # unverändert


def test_fill_cells_azubi_status_replicates_real_value(admin, device_headers):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "azubi")  # Do 2026-07-30
    admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Krank"})
    worker_id = cell_id.split("_")[1]
    target = f"2026-W31_{worker_id}_2026-07-31"  # Fr, nach dem Ursprung

    r = admin.post(f"/web/cells/{cell_id}/fill", json={"target_cell_ids": [target]})
    assert r.status_code == 200
    assert r.json()["filled"] == [target]

    api = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    entry = next(e for e in api["entries"] if e["cell_id"] == target)
    assert entry["content"]["text"] == "Krank"


def test_fill_cells_azubi_assignment_uses_text_arrow(admin, device_headers):
    conn = admin.app.state.db
    from app.repos import workers
    monteur = workers.create_worker(conn, "144", "Albrecht", "monteur")
    _, cell_id = _cell(conn, "azubi")  # Do 2026-07-30
    admin.post(f"/web/cells/{cell_id}/entries", data={"text": "144 Albrecht"})
    worker_id = cell_id.split("_")[1]
    target = f"2026-W31_{worker_id}_2026-07-31"  # Fr, nach dem Ursprung

    r = admin.post(f"/web/cells/{cell_id}/fill", json={"target_cell_ids": [target]})
    assert r.status_code == 200

    api = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    entry = next(e for e in api["entries"] if e["cell_id"] == target)
    assert entry["content"]["text"] == "----->"


def test_fill_cells_rejects_empty_origin(admin):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "monteur")
    r = admin.post(f"/web/cells/{cell_id}/fill", json={"target_cell_ids": []})
    assert r.status_code == 422


def test_fill_cells_ignores_targets_in_different_worker_row(admin, device_headers):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "monteur")
    admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Baustelle A"})
    other, _ = _cell(conn, "monteur")
    other_target = f"2026-W31_{other['id']}_2026-07-28"

    r = admin.post(f"/web/cells/{cell_id}/fill",
                   json={"target_cell_ids": [other_target]})
    assert r.status_code == 200
    assert r.json()["filled"] == []

    api = admin.get("/api/v1/weeks/2026-W31", headers=device_headers).json()
    assert all(e["cell_id"] != other_target for e in api["entries"])


def test_fill_cells_ignores_targets_left_of_origin(admin, device_headers):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "monteur")  # cell_id ist Do 2026-07-30 (siehe _cell-Helper)
    admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Baustelle A"})
    worker_id = cell_id.split("_")[1]
    earlier = f"2026-W31_{worker_id}_2026-07-27"  # Mo, liegt vor dem Ursprung

    r = admin.post(f"/web/cells/{cell_id}/fill",
                   json={"target_cell_ids": [earlier]})
    assert r.status_code == 200
    assert r.json()["filled"] == []


def test_fill_cells_requires_login(client):
    r = client.post("/web/cells/2026-W31_w-x_2026-07-28/fill",
                    json={"target_cell_ids": []}, follow_redirects=False)
    assert r.status_code == 303
```

Hinweis: Die bestehende `_cell(conn, category)`-Hilfsfunktion in dieser Datei erzeugt Zellen für `2026-W31` am `2026-07-30` (Donnerstag) — siehe die bereits vorhandene Definition am Dateianfang. Die obigen Tests nutzen das für die Datumsberechnung; alle Zielzellen liegen bewusst nach diesem Datum (Fr/Sa/So), da der Endpoint Ziele vor oder am Ursprungsdatum ablehnt.

- [ ] **Step 2: Tests laufen lassen, sicherstellen dass sie fehlschlagen**

Run: `cd /home/admin/Projekte/TimePlan/server && .venv/bin/python -m pytest tests/test_web_cells.py -k fill_cells -v`
Expected: alle neuen Tests FAILEN (404, Route existiert noch nicht)

- [ ] **Step 3: Endpoint implementieren**

`server/app/web/routes.py` beginnt aktuell mit:

```python
import collections
import pathlib
from urllib.parse import quote

from fastapi import APIRouter, Form, HTTPException, Request
from fastapi.responses import RedirectResponse
from fastapi.templating import Jinja2Templates

from .. import db
from .. import weeks as weeklib
from ..repos import app_settings as app_settings_repo
from ..repos import entries as entries_repo
from ..repos import workers as workers_repo
```

Ändere das zu:

```python
import collections
import pathlib
from datetime import date
from urllib.parse import quote

from fastapi import APIRouter, Form, HTTPException, Request
from fastapi.responses import RedirectResponse
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel

from .. import db
from .. import weeks as weeklib
from ..repos import app_settings as app_settings_repo
from ..repos import entries as entries_repo
from ..repos import workers as workers_repo
```

Füge nach der bestehenden `AZUBI_STATUS_CLASS`-Konstante (kurz nach `web_router = APIRouter()`) zwei neue Konstanten hinzu:

```python
DRAG_FILL_TEXT_ARROW = "→"
DRAG_FILL_ASSIGNMENT_ARROW = "----->"
```

Füge am Ende der Datei (nach `web_delete_entry`, vor der `/workers`-Route-Gruppe) hinzu:

```python
class FillCellsIn(BaseModel):
    target_cell_ids: list[str]


@web_router.post("/web/cells/{origin_cell_id}/fill")
async def web_fill_cells(origin_cell_id: str, payload: FillCellsIn, request: Request):
    require_admin(request)
    state = request.app.state
    conn = state.db

    try:
        origin_week_id, origin_worker_id, origin_date_iso = weeklib.parse_cell_id(origin_cell_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="unbekannte Ursprungszelle")

    origin_worker = workers_repo.get_worker(conn, origin_worker_id)
    if origin_worker is None:
        raise HTTPException(status_code=404, detail="unbekannter Mitarbeiter")

    origin_entries = _existing_cell_entries(conn, origin_week_id, origin_cell_id)
    if not origin_entries:
        raise HTTPException(status_code=422, detail="Ursprungszelle ist leer")
    origin_text = origin_entries[0]["content"]["text"]
    origin_date = date.fromisoformat(origin_date_iso)

    if origin_worker["category"] == "azubi" and origin_text in AZUBI_STATUS_CLASS:
        fill_text = origin_text
    elif origin_worker["category"] == "azubi":
        fill_text = DRAG_FILL_ASSIGNMENT_ARROW
    else:
        fill_text = DRAG_FILL_TEXT_ARROW

    filled: list[str] = []
    for target_cell_id in payload.target_cell_ids:
        try:
            target_week_id, target_worker_id, target_date_iso = weeklib.parse_cell_id(target_cell_id)
        except ValueError:
            continue
        if target_week_id != origin_week_id or target_worker_id != origin_worker_id:
            continue
        if date.fromisoformat(target_date_iso) <= origin_date:
            continue
        if _existing_cell_entries(conn, target_week_id, target_cell_id):
            continue
        entries_repo.create_entry(conn, target_cell_id, "text", {"text": fill_text},
                                  "web", "web-admin", state.settings)
        filled.append(target_cell_id)

    if filled:
        await request.app.state.hub.broadcast({
            "event": "week.updated",
            "revision": db.latest_revision(conn)})

    return {"filled": filled}
```

- [ ] **Step 4: Tests laufen lassen, sicherstellen dass sie bestehen**

Run: `.venv/bin/python -m pytest tests/test_web_cells.py -k fill_cells -v`
Expected: alle 7 neuen Tests PASS

- [ ] **Step 5: Volle Suite laufen lassen**

Run: `.venv/bin/python -m pytest -q`
Expected: alle Tests grün (75 vorher + 7 neu = 82 passed)

- [ ] **Step 6: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add server/app/web/routes.py server/tests/test_web_cells.py
git commit -m "server: Fill-Endpoint für Drag-to-Fill (nur leere Zielzellen, Pfeil-Platzhalter)"
```

---

### Task 2: WebUI — Long-Press-Drag-Geste + Pfeil-Overlay

**Files:**
- Create: `server/app/static/dragfill.js`
- Modify: `server/app/templates/week.html`
- Modify: `server/app/static/style.css`
- Modify: `server/app/static/swipe.js`
- Test: `server/tests/test_web_week.py`

**Interfaces:**
- Consumes: `POST /web/cells/{origin_cell_id}/fill` aus Task 1.
- Produces: keine neuen Server-Interfaces — reiner Client-Task.

- [ ] **Step 1: Failing Tests schreiben**

Füge in `server/tests/test_web_week.py` (am Ende der Datei) hinzu:

```python
def test_week_page_includes_dragfill_script(admin):
    r = admin.get("/week/2026-W31")
    assert r.status_code == 200
    assert '<script src="/static/dragfill.js" defer></script>' in r.text


def test_week_page_has_dragfill_arrow_overlay(admin):
    r = admin.get("/week/2026-W31")
    assert 'id="dragfill-arrow-overlay"' in r.text
    assert 'id="dragfill-arrow-line"' in r.text
    assert 'id="dragfill-arrow-head"' in r.text
```

- [ ] **Step 2: Tests laufen lassen, sicherstellen dass sie fehlschlagen**

Run: `cd /home/admin/Projekte/TimePlan/server && .venv/bin/python -m pytest tests/test_web_week.py -k dragfill -v`
Expected: beide FAILEN

- [ ] **Step 3: Overlay-Markup + Script-Einbindung in week.html**

In `server/app/templates/week.html` steht:

```html
<dialog id="cell-dialog">
  <div id="cell-dialog-body"
       hx-on::after-swap="if (!this.closest('dialog').open) this.closest('dialog').showModal()"></div>
</dialog>
<script src="/static/live.js" defer></script>
<script src="/static/swipe.js" defer></script>
{% endblock %}
```

Ändere das zu:

```html
<dialog id="cell-dialog">
  <div id="cell-dialog-body"
       hx-on::after-swap="if (!this.closest('dialog').open) this.closest('dialog').showModal()"></div>
</dialog>
<svg id="dragfill-arrow-overlay">
  <line id="dragfill-arrow-line"></line>
  <polygon id="dragfill-arrow-head"></polygon>
</svg>
<script src="/static/live.js" defer></script>
<script src="/static/swipe.js" defer></script>
<script src="/static/dragfill.js" defer></script>
{% endblock %}
```

- [ ] **Step 4: CSS für Overlay + markierte Zellen**

Füge am Ende von `server/app/static/style.css` hinzu:

```css

/* Drag-to-Fill: Pfeil-Overlay + markierte Zielzellen */
#dragfill-arrow-overlay {
  position: fixed; top: 0; left: 0; width: 100vw; height: 100vh;
  pointer-events: none; z-index: 50; display: none;
}
#dragfill-arrow-line { stroke: var(--primary); stroke-width: 3; }
#dragfill-arrow-head { fill: var(--primary); }
.grid td.cell.dragfill-marked { background: var(--primary-surface); }
```

- [ ] **Step 5: swipe.js um Geste-Sperre erweitern**

In `server/app/static/swipe.js` steht:

```js
  document.addEventListener("touchend", function (e) {
    if (!tracking) return;
    tracking = false;
```

Ändere das zu:

```js
  document.addEventListener("touchend", function (e) {
    if (window.__timeplanGestureLock) { tracking = false; return; }
    if (!tracking) return;
    tracking = false;
```

(Verhindert, dass ein Long-Press-Drag zum Befüllen versehentlich als Wochenwechsel-Wisch gewertet wird — `dragfill.js` setzt dieses Flag, sobald eine Drag-Fill-Geste tatsächlich armiert wurde.)

- [ ] **Step 6: dragfill.js erstellen**

Erstelle `server/app/static/dragfill.js`:

```js
(function () {
  var LONG_PRESS_MS = 450;
  var MOVE_SLOP = 10;

  var timer = null;
  var armed = false;
  var originCell = null;
  var originCellId = null;
  var rowCells = [];
  var originIndex = -1;
  var startX = 0, startY = 0;

  function cellHasContent(td) {
    return td.textContent.trim().length > 0;
  }

  function updateArrow(x1, y, x2) {
    var line = document.getElementById("dragfill-arrow-line");
    var head = document.getElementById("dragfill-arrow-head");
    line.setAttribute("x1", x1); line.setAttribute("y1", y);
    line.setAttribute("x2", x2); line.setAttribute("y2", y);
    var headSize = 10;
    var hx = x2 - headSize;
    head.setAttribute("points",
      x2 + "," + y + " " + hx + "," + (y - headSize / 2) + " " + hx + "," + (y + headSize / 2));
  }

  function showOverlay() { document.getElementById("dragfill-arrow-overlay").style.display = "block"; }
  function hideOverlay() { document.getElementById("dragfill-arrow-overlay").style.display = "none"; }

  function clearMarks() {
    rowCells.forEach(function (td) { td.classList.remove("dragfill-marked"); });
  }

  function reset() {
    if (timer) { clearTimeout(timer); timer = null; }
    if (armed) { clearMarks(); hideOverlay(); window.__timeplanGestureLock = false; }
    armed = false;
    originCell = null;
    rowCells = [];
    originIndex = -1;
  }

  function arm() {
    if (!originCell) return;
    armed = true;
    window.__timeplanGestureLock = true;
    originCellId = originCell.id.replace(/^cell-/, "");
    rowCells = Array.prototype.slice.call(originCell.parentElement.querySelectorAll("td.cell"));
    originIndex = rowCells.indexOf(originCell);
    var rect = originCell.getBoundingClientRect();
    var originX = rect.left + rect.width / 2;
    var originY = rect.top + rect.height / 2;
    showOverlay();
    updateArrow(originX, originY, originX);
  }

  document.addEventListener("touchstart", function (e) {
    reset();
    var td = e.target.closest("td.cell");
    if (!td || !cellHasContent(td) || e.touches.length !== 1) return;
    var touch = e.touches[0];
    startX = touch.clientX;
    startY = touch.clientY;
    originCell = td;
    timer = setTimeout(arm, LONG_PRESS_MS);
  }, { passive: true });

  document.addEventListener("touchmove", function (e) {
    if (timer && !armed) {
      var touch = e.touches[0];
      if (Math.abs(touch.clientX - startX) > MOVE_SLOP || Math.abs(touch.clientY - startY) > MOVE_SLOP) {
        clearTimeout(timer);
        timer = null;
      }
      return;
    }
    if (!armed) return;
    var touch = e.touches[0];
    var rect = originCell.getBoundingClientRect();
    var originX = rect.left + rect.width / 2;
    var originY = rect.top + rect.height / 2;
    var clampedX = Math.max(touch.clientX, originX);
    updateArrow(originX, originY, clampedX);

    for (var i = originIndex + 1; i < rowCells.length; i++) {
      var cellRect = rowCells[i].getBoundingClientRect();
      var cellCenterX = cellRect.left + cellRect.width / 2;
      if (clampedX >= cellCenterX) {
        rowCells[i].classList.add("dragfill-marked");
      } else {
        rowCells[i].classList.remove("dragfill-marked");
      }
    }
  }, { passive: true });

  document.addEventListener("touchend", function () {
    if (!armed) { reset(); return; }
    var targetIds = [];
    for (var i = originIndex + 1; i < rowCells.length; i++) {
      if (rowCells[i].classList.contains("dragfill-marked")) {
        targetIds.push(rowCells[i].id.replace(/^cell-/, ""));
      }
    }
    var originIdForRequest = originCellId;
    reset();
    if (targetIds.length === 0) return;
    fetch("/web/cells/" + originIdForRequest + "/fill", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ target_cell_ids: targetIds })
    }).then(function (r) { return r.json(); }).then(function (data) {
      (data.filled || []).forEach(function (cellId) {
        var el = document.getElementById("cell-" + cellId);
        if (el) htmx.ajax("GET", "/web/cells/" + cellId, { target: el, swap: "outerHTML" });
      });
    });
  }, { passive: true });

  document.addEventListener("touchcancel", reset, { passive: true });
})();
```

- [ ] **Step 7: Tests laufen lassen, sicherstellen dass sie bestehen**

Run: `.venv/bin/python -m pytest tests/test_web_week.py -k dragfill -v`
Expected: beide PASS

- [ ] **Step 8: Volle Suite laufen lassen**

Run: `.venv/bin/python -m pytest -q`
Expected: alle Tests grün (82 vorher + 2 neu = 84 passed)

- [ ] **Step 9: Commit**

```bash
git add server/app/static/dragfill.js server/app/templates/week.html server/app/static/style.css server/app/static/swipe.js server/tests/test_web_week.py
git commit -m "webui: Long-Press-Drag-to-Fill mit live mitwachsendem Pfeil-Overlay"
```

---

### Task 3: Android — isolierte Bereichs-Berechnung (`DragFillRange`)

**Files:**
- Create: `android/app/src/main/java/de/fs/timeplan/week/DragFillRange.kt`
- Test: `android/app/src/test/java/de/fs/timeplan/week/DragFillRangeTest.kt`

**Interfaces:**
- Produces: `object DragFillRange { fun coveredIndices(cellCenters: List<Float>, originIndex: Int, currentX: Float): List<Int> }` — reine Kotlin-Logik, keine Android-Imports, mit JUnit testbar. Wird in Task 4 von `DragFillController` konsumiert.

- [ ] **Step 1: Failing Test schreiben**

Erstelle `android/app/src/test/java/de/fs/timeplan/week/DragFillRangeTest.kt`:

```kotlin
package de.fs.timeplan.week

import org.junit.Assert.assertEquals
import org.junit.Test

class DragFillRangeTest {

    private val centers = listOf(100f, 200f, 300f)

    @Test
    fun `no movement covers nothing`() {
        assertEquals(emptyList<Int>(), DragFillRange.coveredIndices(centers, 0, 100f))
    }

    @Test
    fun `crossing one cell center covers it`() {
        assertEquals(listOf(1), DragFillRange.coveredIndices(centers, 0, 250f))
    }

    @Test
    fun `crossing past the last cell covers all remaining`() {
        assertEquals(listOf(1, 2), DragFillRange.coveredIndices(centers, 0, 350f))
    }

    @Test
    fun `origin in the middle only considers cells after it`() {
        assertEquals(listOf(2), DragFillRange.coveredIndices(centers, 1, 350f))
    }

    @Test
    fun `retracting before a center uncoveres it again`() {
        assertEquals(emptyList<Int>(), DragFillRange.coveredIndices(centers, 0, 150f))
    }
}
```

- [ ] **Step 2: Test laufen lassen, sicherstellen dass er fehlschlägt**

Run: `cd /home/admin/Projekte/TimePlan/android && JAVA_HOME="$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)" ./gradlew testDebugUnitTest --tests "de.fs.timeplan.week.DragFillRangeTest" --console=plain`
Expected: FAIL — `DragFillRange` existiert noch nicht (Compile-Fehler)

- [ ] **Step 3: DragFillRange.kt implementieren**

Erstelle `android/app/src/main/java/de/fs/timeplan/week/DragFillRange.kt`:

```kotlin
package de.fs.timeplan.week

object DragFillRange {
    fun coveredIndices(cellCenters: List<Float>, originIndex: Int, currentX: Float): List<Int> {
        val result = mutableListOf<Int>()
        for (i in originIndex + 1 until cellCenters.size) {
            if (currentX >= cellCenters[i]) {
                result.add(i)
            } else {
                break
            }
        }
        return result
    }
}
```

- [ ] **Step 4: Test laufen lassen, sicherstellen dass er besteht**

Run: `JAVA_HOME="$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)" ./gradlew testDebugUnitTest --tests "de.fs.timeplan.week.DragFillRangeTest" --console=plain`
Expected: PASS, alle 5 Tests grün

- [ ] **Step 5: Volle Android-Suite + Build laufen lassen**

Run: `JAVA_HOME="$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)" ./gradlew testDebugUnitTest assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL, alle Tests grün

- [ ] **Step 6: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add android/app/src/main/java/de/fs/timeplan/week/DragFillRange.kt android/app/src/test/java/de/fs/timeplan/week/DragFillRangeTest.kt
git commit -m "android: isolierte Bereichs-Berechnung für Drag-to-Fill (DragFillRange)"
```

---

### Task 4: Android — Demo-Modus-Verkabelung (Overlay, Touch-Handling, Verkabelung)

**Files:**
- Create: `android/app/src/main/java/de/fs/timeplan/grid/DragFillOverlay.kt`
- Create: `android/app/src/main/java/de/fs/timeplan/grid/DragFillController.kt`
- Modify: `android/app/src/main/res/layout/activity_week.xml`
- Modify: `android/app/src/main/java/de/fs/timeplan/grid/WeekGridAdapter.kt`
- Modify: `android/app/src/main/java/de/fs/timeplan/WeekActivity.kt`
- Test: `android/app/src/test/java/de/fs/timeplan/week/WeekActivityTest.kt`

**Interfaces:**
- Consumes: `DragFillRange.coveredIndices(...)` aus Task 3.
- Produces: nichts für spätere Tasks — letzter Task des Plans.

**Hintergrund — warum eine `FrameLayout`-Umstrukturierung nötig ist:** Der Pfeil muss oberhalb der `RecyclerView` gezeichnet werden können, unabhängig vom Zell-Recycling. Eine `LinearLayout` (wie `SwipeInterceptLayout`) kann Kinder nicht überlappend stapeln — daher wird die `RecyclerView` in eine neue `FrameLayout` gepackt, die zusätzlich zwei einfache Overlay-Views enthält (ein schmales `View` als Pfeilschaft, ein `TextView` mit "▶" als Pfeilspitze). Kein Custom-Canvas-`onDraw()` nötig.

**Hintergrund — warum Touch-Handling pro Zelle und nicht per `GestureDetector`:** Die bestehende Zell-Erstellung in `WorkerRowViewHolder.bind()` erzeugt einfache `TextView`s ohne Gesten-Infrastruktur. Ein `Handler.postDelayed`-Timer (dasselbe Muster wie in `dragfill.js`) ist einfacher als ein `GestureDetector` pro recycelter Zelle. Wichtig: `setOnTouchListener` konsumiert bei `return true` den Touch-Stream und verhindert damit den normalen Klick — deshalb ruft der Handler bei einem kurzen Tap (nicht armiert) manuell `view.performClick()` auf, um das bestehende Antippen-Verhalten exakt zu erhalten.

- [ ] **Step 1: Failing Test schreiben**

Füge in `android/app/src/test/java/de/fs/timeplan/week/WeekActivityTest.kt` einen neuen Test hinzu (nach dem bestehenden `swipe gesture is disabled while a swipe animation is in flight`-Test):

```kotlin
    @Test
    fun `drag-fill commit only writes empty target cells with the arrow placeholder`() {
        val controller = Robolectric.buildActivity(WeekActivity::class.java)
        controller.setup()
        val activity = controller.get()

        val workers = (DemoApi.getWorkers() as ApiResult.Success).data.workers
        val monteur = workers.first { it.isMonteur }
        val weekId = WeekId.currentWeekId()
        val dates = WeekId.weekDates(weekId).map { it.toString() }

        val originCellId = WeekId.makeCellId(weekId, monteur.id, dates[0])
        val alreadyFilledCellId = WeekId.makeCellId(weekId, monteur.id, dates[1])
        val emptyCellId = WeekId.makeCellId(weekId, monteur.id, dates[2])
        DemoApi.putEntry(originCellId, "Baustelle A")
        DemoApi.putEntry(alreadyFilledCellId, "Schon belegt")

        val method = WeekActivity::class.java.getDeclaredMethod(
            "onDragFillCommit", String::class.java, Int::class.java, List::class.java
        )
        method.isAccessible = true
        method.invoke(activity, monteur.id, 0, listOf(1, 2))

        assertEquals("Schon belegt", DemoApi.textFor(alreadyFilledCellId))
        assertEquals("→", DemoApi.textFor(emptyCellId))
    }
```

- [ ] **Step 2: Test laufen lassen, sicherstellen dass er fehlschlägt**

Run: `cd /home/admin/Projekte/TimePlan/android && JAVA_HOME="$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)" ./gradlew testDebugUnitTest --tests "de.fs.timeplan.week.WeekActivityTest" --console=plain`
Expected: FAIL — `NoSuchMethodException: onDragFillCommit` (Methode existiert noch nicht)

- [ ] **Step 3: activity_week.xml umstrukturieren**

In `android/app/src/main/res/layout/activity_week.xml` steht:

```xml
        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/weekRecyclerView"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:background="@color/paper_bg"
            android:clipToPadding="false"
            android:paddingBottom="16dp" />

    </de.fs.timeplan.grid.SwipeInterceptLayout>
```

Ersetze das durch:

```xml
        <FrameLayout
            android:id="@+id/weekGridOverlayContainer"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1">

            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/weekRecyclerView"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:background="@color/paper_bg"
                android:clipToPadding="false"
                android:paddingBottom="16dp" />

            <View
                android:id="@+id/dragFillArrowShaft"
                android:layout_width="0dp"
                android:layout_height="3dp"
                android:layout_gravity="top|start"
                android:background="@color/primary"
                android:visibility="invisible" />

            <TextView
                android:id="@+id/dragFillArrowHead"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_gravity="top|start"
                android:text="▶"
                android:textColor="@color/primary"
                android:textSize="16sp"
                android:visibility="invisible" />

        </FrameLayout>

    </de.fs.timeplan.grid.SwipeInterceptLayout>
```

- [ ] **Step 4: DragFillOverlay.kt erstellen**

Erstelle `android/app/src/main/java/de/fs/timeplan/grid/DragFillOverlay.kt`:

```kotlin
package de.fs.timeplan.grid

import android.view.View
import android.widget.TextView

class DragFillOverlay(
    private val container: View,
    private val shaft: View,
    private val head: TextView
) {
    fun show(originX: Float, centerY: Float) {
        update(originX, centerY, originX)
        shaft.visibility = View.VISIBLE
        head.visibility = View.VISIBLE
    }

    fun update(originX: Float, centerY: Float, currentX: Float) {
        val offset = IntArray(2)
        container.getLocationOnScreen(offset)
        val localOriginX = originX - offset[0]
        val localY = centerY - offset[1]
        val localCurrentX = currentX - offset[0]

        shaft.translationX = localOriginX
        shaft.translationY = localY - shaft.layoutParams.height / 2f
        shaft.layoutParams = shaft.layoutParams.apply {
            width = maxOf((localCurrentX - localOriginX).toInt(), 0)
        }
        shaft.requestLayout()

        head.translationX = localCurrentX - head.width / 2f
        head.translationY = localY - head.height / 2f
    }

    fun hide() {
        shaft.visibility = View.INVISIBLE
        head.visibility = View.INVISIBLE
    }
}
```

- [ ] **Step 5: DragFillController.kt erstellen**

Erstelle `android/app/src/main/java/de/fs/timeplan/grid/DragFillController.kt`:

```kotlin
package de.fs.timeplan.grid

import android.view.View
import de.fs.timeplan.week.DragFillRange

class DragFillController(
    private val overlay: DragFillOverlay,
    private val onCommit: (workerId: String, originIndex: Int, targetIndices: List<Int>) -> Unit
) {
    private var armed = false
    private var workerId: String? = null
    private var originIndex = -1
    private var cellCenters: List<Float> = emptyList()
    private var rowCenterY = 0f

    fun arm(workerId: String, originIndex: Int, cellViews: List<View>) {
        armed = true
        this.workerId = workerId
        this.originIndex = originIndex
        cellCenters = cellViews.map { v ->
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            loc[0] + v.width / 2f
        }
        val loc = IntArray(2)
        cellViews[originIndex].getLocationOnScreen(loc)
        rowCenterY = loc[1] + cellViews[originIndex].height / 2f
        overlay.show(cellCenters[originIndex], rowCenterY)
    }

    fun progress(rawX: Float) {
        if (!armed) return
        val clampedX = maxOf(rawX, cellCenters[originIndex])
        overlay.update(cellCenters[originIndex], rowCenterY, clampedX)
    }

    fun release(rawX: Float) {
        if (!armed) return
        val clampedX = maxOf(rawX, cellCenters[originIndex])
        val targets = DragFillRange.coveredIndices(cellCenters, originIndex, clampedX)
        val id = workerId!!
        val origin = originIndex
        reset()
        if (targets.isNotEmpty()) onCommit(id, origin, targets)
    }

    fun cancel() = reset()

    private fun reset() {
        armed = false
        workerId = null
        originIndex = -1
        overlay.hide()
    }
}
```

- [ ] **Step 6: WeekGridAdapter.kt erweitern**

In `android/app/src/main/java/de/fs/timeplan/grid/WeekGridAdapter.kt` steht:

```kotlin
class WeekGridAdapter(private var rows: List<WeekRow> = emptyList()) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var onCellClick: ((workerId: String, dateIndex: Int) -> Unit)? = null
```

Ändere das zu:

```kotlin
class WeekGridAdapter(private var rows: List<WeekRow> = emptyList()) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var onCellClick: ((workerId: String, dateIndex: Int) -> Unit)? = null
    var dragFillController: DragFillController? = null
```

Es folgt:

```kotlin
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is WeekRow.Monteur -> (holder as WorkerRowViewHolder)
                .bind(row.workerId, row.displayName, row.cellTexts, compact = false, onCellClick)
            is WeekRow.Azubi -> (holder as WorkerRowViewHolder)
                .bind(row.workerId, row.displayName, row.cellTexts, compact = true, onCellClick)
            WeekRow.Separator -> Unit
        }
    }
```

Ändere das zu:

```kotlin
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is WeekRow.Monteur -> (holder as WorkerRowViewHolder)
                .bind(row.workerId, row.displayName, row.cellTexts, compact = false, onCellClick, dragFillController)
            is WeekRow.Azubi -> (holder as WorkerRowViewHolder)
                .bind(row.workerId, row.displayName, row.cellTexts, compact = true, onCellClick, dragFillController)
            WeekRow.Separator -> Unit
        }
    }
```

Der gesamte `WorkerRowViewHolder`-Block lautet aktuell:

```kotlin
    class WorkerRowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val nameView: TextView = view.findViewById(R.id.rowWorkerName)
        private val cellsContainer: LinearLayout = view.findViewById(R.id.rowCellsContainer)

        fun bind(
            workerId: String,
            name: String,
            cellTexts: List<String?>,
            compact: Boolean,
            onCellClick: ((String, Int) -> Unit)?
        ) {
            nameView.text = name
            cellsContainer.removeAllViews()
            val context = cellsContainer.context
            val minHeightPx = dp(context, if (compact) 40 else 64)
            val marginPx = dp(context, 3)

            cellTexts.forEachIndexed { index, text ->
                val status = if (compact) AzubiStatus.from(text) else null
                val hasText = !text.isNullOrBlank()

                val cell = TextView(context).apply {
                    this.text = text.orEmpty()
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(marginPx, marginPx, marginPx, marginPx)
                    }
                    minHeight = minHeightPx
                    maxLines = if (compact) 1 else 4
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    textSize = if (compact) 13f else 14f
                    setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))

                    when {
                        status != null -> {
                            gravity = Gravity.CENTER
                            setBackgroundResource(status.chipBackground)
                            setTextColor(ContextCompat.getColor(context, R.color.on_status))
                        }
                        compact && hasText -> {
                            gravity = Gravity.CENTER
                            setBackgroundResource(R.drawable.bg_chip_assigned)
                            setTextColor(ContextCompat.getColor(context, R.color.ink))
                        }
                        hasText -> {
                            gravity = Gravity.TOP or Gravity.START
                            setBackgroundResource(R.drawable.bg_cell_filled)
                            setTextColor(ContextCompat.getColor(context, R.color.ink))
                        }
                        else -> {
                            gravity = if (compact) Gravity.CENTER else Gravity.TOP or Gravity.START
                            setBackgroundResource(R.drawable.bg_cell_empty)
                            setTextColor(ContextCompat.getColor(context, R.color.ink_faint))
                        }
                    }

                    if (onCellClick != null) {
                        isClickable = true
                        foreground = resolveSelectableForeground(context)
                        setOnClickListener { onCellClick(workerId, index) }
                    }
                }
                cellsContainer.addView(cell)
            }
        }

        private fun dp(context: android.content.Context, value: Int): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

        private fun resolveSelectableForeground(context: android.content.Context): android.graphics.drawable.Drawable? {
            val typedValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
            return ContextCompat.getDrawable(context, typedValue.resourceId)
        }
    }
```

Ersetze den kompletten Block durch:

```kotlin
    class WorkerRowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val nameView: TextView = view.findViewById(R.id.rowWorkerName)
        private val cellsContainer: LinearLayout = view.findViewById(R.id.rowCellsContainer)

        fun bind(
            workerId: String,
            name: String,
            cellTexts: List<String?>,
            compact: Boolean,
            onCellClick: ((String, Int) -> Unit)?,
            dragFillController: DragFillController?
        ) {
            nameView.text = name
            cellsContainer.removeAllViews()
            val context = cellsContainer.context
            val minHeightPx = dp(context, if (compact) 40 else 64)
            val marginPx = dp(context, 3)

            val cells = cellTexts.mapIndexed { index, text ->
                val status = if (compact) AzubiStatus.from(text) else null
                val hasText = !text.isNullOrBlank()

                TextView(context).apply {
                    this.text = text.orEmpty()
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(marginPx, marginPx, marginPx, marginPx)
                    }
                    minHeight = minHeightPx
                    maxLines = if (compact) 1 else 4
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    textSize = if (compact) 13f else 14f
                    setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))

                    when {
                        status != null -> {
                            gravity = Gravity.CENTER
                            setBackgroundResource(status.chipBackground)
                            setTextColor(ContextCompat.getColor(context, R.color.on_status))
                        }
                        compact && hasText -> {
                            gravity = Gravity.CENTER
                            setBackgroundResource(R.drawable.bg_chip_assigned)
                            setTextColor(ContextCompat.getColor(context, R.color.ink))
                        }
                        hasText -> {
                            gravity = Gravity.TOP or Gravity.START
                            setBackgroundResource(R.drawable.bg_cell_filled)
                            setTextColor(ContextCompat.getColor(context, R.color.ink))
                        }
                        else -> {
                            gravity = if (compact) Gravity.CENTER else Gravity.TOP or Gravity.START
                            setBackgroundResource(R.drawable.bg_cell_empty)
                            setTextColor(ContextCompat.getColor(context, R.color.ink_faint))
                        }
                    }

                    if (onCellClick != null) {
                        isClickable = true
                        foreground = resolveSelectableForeground(context)
                        setOnClickListener { onCellClick(workerId, index) }
                    }
                }
            }
            cells.forEach { cellsContainer.addView(it) }

            if (onCellClick != null && dragFillController != null) {
                cells.forEachIndexed { index, cellView ->
                    attachDragFillTouchHandling(cellView, cells, index, workerId, dragFillController)
                }
            }
        }

        private fun attachDragFillTouchHandling(
            cellView: TextView,
            rowCells: List<TextView>,
            index: Int,
            workerId: String,
            controller: DragFillController
        ) {
            val longPressMs = 450L
            val slopPx = dp(cellView.context, 8)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var armRunnable: Runnable? = null
            var armed = false
            var startX = 0f
            var startY = 0f

            cellView.setOnTouchListener { view, event ->
                if (cellView.text.isNullOrBlank()) return@setOnTouchListener false
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        armed = false
                        startX = event.rawX
                        startY = event.rawY
                        val runnable = Runnable {
                            armed = true
                            (view.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                            controller.arm(workerId, index, rowCells)
                        }
                        armRunnable = runnable
                        handler.postDelayed(runnable, longPressMs)
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (!armed) {
                            if (kotlin.math.abs(event.rawX - startX) > slopPx ||
                                kotlin.math.abs(event.rawY - startY) > slopPx) {
                                armRunnable?.let { handler.removeCallbacks(it) }
                            }
                            return@setOnTouchListener true
                        }
                        controller.progress(event.rawX)
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        armRunnable?.let { handler.removeCallbacks(it) }
                        if (armed) {
                            controller.release(event.rawX)
                        } else {
                            view.performClick()
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        armRunnable?.let { handler.removeCallbacks(it) }
                        if (armed) controller.cancel()
                        true
                    }
                    else -> false
                }
            }
        }

        private fun dp(context: android.content.Context, value: Int): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

        private fun resolveSelectableForeground(context: android.content.Context): android.graphics.drawable.Drawable? {
            val typedValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
            return ContextCompat.getDrawable(context, typedValue.resourceId)
        }
    }
```

- [ ] **Step 7: WeekActivity.kt verkabeln**

In `android/app/src/main/java/de/fs/timeplan/WeekActivity.kt` steht am Anfang der Klasse:

```kotlin
    private lateinit var weekContentContainer: de.fs.timeplan.grid.SwipeInterceptLayout
    private val adapter = WeekGridAdapter()
```

Ändere das zu:

```kotlin
    private lateinit var weekContentContainer: de.fs.timeplan.grid.SwipeInterceptLayout
    private lateinit var dragFillOverlay: de.fs.timeplan.grid.DragFillOverlay
    private lateinit var dragFillController: de.fs.timeplan.grid.DragFillController
    private val adapter = WeekGridAdapter()
```

In `onCreate` steht:

```kotlin
        recyclerView = findViewById(R.id.weekRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
```

Ändere das zu:

```kotlin
        recyclerView = findViewById(R.id.weekRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        val dragFillContainer = findViewById<android.widget.FrameLayout>(R.id.weekGridOverlayContainer)
        val dragFillShaft = findViewById<View>(R.id.dragFillArrowShaft)
        val dragFillHead = findViewById<TextView>(R.id.dragFillArrowHead)
        dragFillOverlay = de.fs.timeplan.grid.DragFillOverlay(dragFillContainer, dragFillShaft, dragFillHead)
        dragFillController = de.fs.timeplan.grid.DragFillController(dragFillOverlay, ::onDragFillCommit)
```

In `onResume` steht:

```kotlin
        adapter.onCellClick = if (isDemoMode) {
            { workerId, dateIndex -> onDemoCellClick(workerId, dateIndex) }
        } else {
            null
        }
        loadCurrentWeek()
    }
```

Ändere das zu:

```kotlin
        adapter.onCellClick = if (isDemoMode) {
            { workerId, dateIndex -> onDemoCellClick(workerId, dateIndex) }
        } else {
            null
        }
        adapter.dragFillController = if (isDemoMode) dragFillController else null
        loadCurrentWeek()
    }
```

Füge eine neue Methode hinzu (z.B. direkt nach `onDemoCellClick`):

```kotlin
    private fun onDragFillCommit(workerId: String, originIndex: Int, targetIndices: List<Int>) {
        val workers = (DemoApi.getWorkers() as? ApiResult.Success)?.data?.workers.orEmpty()
        val worker = workers.firstOrNull { it.id == workerId } ?: return
        val originDate = currentDates.getOrNull(originIndex) ?: return
        val originCellId = WeekId.makeCellId(currentWeekId, workerId, originDate)
        val originText = DemoApi.textFor(originCellId) ?: return

        val fillValue = when {
            worker.isAzubi && AzubiStatus.from(originText) != null -> originText
            worker.isAzubi -> "----->"
            else -> "→"
        }

        var changed = false
        for (targetIndex in targetIndices) {
            val targetDate = currentDates.getOrNull(targetIndex) ?: continue
            val targetCellId = WeekId.makeCellId(currentWeekId, workerId, targetDate)
            if (DemoApi.textFor(targetCellId) != null) continue
            DemoApi.putEntry(targetCellId, fillValue)
            changed = true
        }
        if (changed) loadCurrentWeek()
    }
```

(`import de.fs.timeplan.grid.WeekGridAdapter` ist in dieser Datei bereits vorhanden — kein neuer Import nötig. `DragFillOverlay`/`DragFillController` werden oben bewusst mit vollem Package-Pfad referenziert statt per Import, passend zum bereits bestehenden Stil dieser Datei für `de.fs.timeplan.grid.SwipeInterceptLayout`.)

- [ ] **Step 8: Test laufen lassen, sicherstellen dass er besteht**

Run: `cd /home/admin/Projekte/TimePlan/android && JAVA_HOME="$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)" ./gradlew testDebugUnitTest --tests "de.fs.timeplan.week.WeekActivityTest" --console=plain`
Expected: PASS, alle Tests in dieser Datei grün (inkl. des neuen)

- [ ] **Step 9: Volle Android-Suite + Build laufen lassen**

Run: `JAVA_HOME="$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)" ./gradlew testDebugUnitTest assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL, alle Tests grün

- [ ] **Step 10: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add android/app/src/main/java/de/fs/timeplan/grid/DragFillOverlay.kt android/app/src/main/java/de/fs/timeplan/grid/DragFillController.kt android/app/src/main/res/layout/activity_week.xml android/app/src/main/java/de/fs/timeplan/grid/WeekGridAdapter.kt android/app/src/main/java/de/fs/timeplan/WeekActivity.kt android/app/src/test/java/de/fs/timeplan/week/WeekActivityTest.kt
git commit -m "android: Drag-to-Fill im Demo-Modus verkabelt (Overlay, Touch-Handling, DemoApi-Wiederverwendung)"
```

---

## Nach Abschluss aller Tasks (manuelle Verifikation)

Automatisierte Tests decken die Server-Logik, die Android-Bereichsberechnung und
Markup-Auslieferung ab, aber nicht das tatsächliche Zieh-Gefühl. Manuell zu prüfen:

1. **WebUI:** Long-Press auf eine befüllte Monteur-Zelle, nach rechts ziehen —
   Pfeil wächst live mit, leere Zellen im Bereich werden markiert (Hintergrund).
   Loslassen → markierte leere Zellen zeigen "→", bereits belegte Zellen bleiben
   unverändert. Dasselbe für eine Azubi-Status-Zelle (echter Status übernommen)
   und eine Azubi-Monteurzuordnung-Zelle ("----->" statt Namenswiederholung).
   Kurzes Antippen einer befüllten Zelle öffnet weiterhin normal den Editor.
   Normales vertikales Scrollen bleibt unbeeinträchtigt. Ein Long-Press-Drag
   löst keinen Wochenwechsel aus (swipe.js-Sperre).
2. **Android (Demo-Modus):** Dieselben drei Szenarien (Monteur-Text, Azubi-
   Status, Azubi-Zuordnung) im Emulator/Tablet-Demo-Modus. Kurzes Antippen
   einer befüllten Zelle öffnet weiterhin den Demo-Editor-Dialog. Normales
   vertikales Scrollen und die bestehende Wochen-Wischnavigation bleiben
   unbeeinträchtigt (Koordination über `requestDisallowInterceptTouchEvent`).

## Danach

Feature-Branch erstellen (`feature/drag-to-fill`), alle vier Tasks committen
(bereits während der Tasks passiert), finale Whole-Branch-Review dispatchen,
Findings beheben, mergen nach `main` — wie bei den vorherigen Phasen
(`superpowers:finishing-a-development-branch`-Muster).
