# WebUI Azubi-Picker als Popup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Azubi-Zellen im WebUI öffnen ihren Auswahl-Picker (Status/Monteur-Zuordnung) in einem nativen `<dialog>`-Popup statt inline in der Tabellenzelle, damit die Tabellenzeile immer kompakt bleibt.

**Architecture:** Ein leeres `<dialog id="cell-dialog">` lebt einmalig in `week.html`. Azubi-`<td>`s laden den Picker per htmx in `#cell-dialog-body` und öffnen den Dialog. Die Picker-Formulare posten weiterhin direkt an die reale Tabellenzelle; bei Erfolg lenkt die Antwort sich selbst per `HX-Retarget`/`HX-Reswap`-Response-Headern auf die reale Zelle um (statt in den Dialog zurückzuswappen) und jedes Formular schließt den Dialog daraufhin per kleinem `hx-on::after-request`-Check. Bei einem Validierungsfehler bleiben diese Header weg — die Fehlermeldung erscheint dann im Dialog selbst, der offen bleibt. Monteur-Zellen bleiben vollständig unverändert (weiterhin Inline-Textfeld direkt in der `<td>`).

**Tech Stack:** FastAPI, Jinja2, htmx (bereits eingebunden, keine neue Abhängigkeit), natives HTML `<dialog>`-Element.

## Global Constraints

- Keine Änderungen am Monteur-Zellen-Verhalten (Freitext-Editor bleibt exakt wie heute, inline in der `<td>`).
- Keine Backend-Endpoint-URLs oder Formularfeldnamen ändern sich (`POST /web/cells/{cell_id}/entries`, Feld `text` bleibt identisch) — nur Response-Header und Template-Struktur ändern sich.
- Schließen des Dialogs: "Abbrechen"-Button und native ESC-Taste. Kein Schließen per Klick auf den Backdrop (bewusst nicht implementiert, YAGNI laut Spec).
- Sofort speichern + schließen bei Auswahl (kein separater Bestätigen-Schritt) — bestätigt vom Nutzer.
- Dialog-Styling nutzt die bestehende Papier-Palette (`--paper-surface`, `--ink`, `--radius` aus `style.css`).
- Alle bestehenden 51 Tests in `server/tests/` müssen nach jedem Task weiterhin grün sein (`cd server && .venv/bin/python -m pytest -q`).

---

### Task 1: Dialog-Shell in week.html + CSS-Grundgerüst

**Files:**
- Modify: `server/app/templates/week.html`
- Modify: `server/app/static/style.css`
- Test: `server/tests/test_web_week.py`

**Interfaces:**
- Produces: Ein DOM-Element `<dialog id="cell-dialog">` mit innerem Container `<div id="cell-dialog-body">`, vorhanden auf jeder `/week/{week_id}`-Seite. Spätere Tasks swappen Inhalt in `#cell-dialog-body` und rufen `document.getElementById('cell-dialog').showModal()` / `.close()` auf.

- [ ] **Step 1: Failing Test schreiben**

Füge am Ende von `server/tests/test_web_week.py` hinzu:

```python
def test_week_page_has_cell_dialog_shell(admin):
    r = admin.get("/week/2026-W31")
    assert r.status_code == 200
    assert '<dialog id="cell-dialog">' in r.text
    assert 'id="cell-dialog-body"' in r.text
```

- [ ] **Step 2: Test laufen lassen, sicherstellen dass er fehlschlägt**

Run: `cd /home/admin/Projekte/TimePlan/server && .venv/bin/python -m pytest tests/test_web_week.py::test_week_page_has_cell_dialog_shell -v`
Expected: FAIL (kein `<dialog id="cell-dialog">` im Response)

- [ ] **Step 3: Dialog-Markup in week.html ergänzen**

In `server/app/templates/week.html` steht aktuell am Ende:

```html
</table>
<script src="/static/live.js" defer></script>
{% endblock %}
```

Ändere das zu:

```html
</table>
<dialog id="cell-dialog">
  <div id="cell-dialog-body"></div>
</dialog>
<script src="/static/live.js" defer></script>
{% endblock %}
```

- [ ] **Step 4: CSS für den Dialog ergänzen**

Füge am Ende von `server/app/static/style.css` (nach dem bestehenden `.flash-error`-Block, letzte Zeile der Datei) hinzu:

```css

/* Cell edit dialog (Azubi-Picker) */
dialog#cell-dialog {
  padding: 1.25rem; border: none; border-radius: 10px;
  background: var(--paper-surface); color: var(--ink);
  max-width: 20rem; width: 90vw;
}
dialog#cell-dialog::backdrop { background: rgba(32, 26, 16, 0.45); }
dialog#cell-dialog .error { margin-bottom: .75rem; }
```

- [ ] **Step 5: Test laufen lassen, sicherstellen dass er besteht**

Run: `.venv/bin/python -m pytest tests/test_web_week.py::test_week_page_has_cell_dialog_shell -v`
Expected: PASS

- [ ] **Step 6: Volle Suite laufen lassen**

Run: `.venv/bin/python -m pytest -q`
Expected: alle Tests grün (50 vorher + 1 neu = 51 passed)

- [ ] **Step 7: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add server/app/templates/week.html server/app/static/style.css server/tests/test_web_week.py
git commit -m "webui: Dialog-Shell für Azubi-Picker-Popup ergänzt"
```

---

### Task 2: Azubi-Zelle öffnet den Dialog statt sich selbst zu ersetzen

**Files:**
- Modify: `server/app/templates/partials/cell.html`
- Test: `server/tests/test_web_cells.py`

**Interfaces:**
- Consumes: `#cell-dialog-body` und `#cell-dialog` aus Task 1 (müssen im umgebenden `week.html` existieren, damit das Ziel des htmx-Requests real ist).
- Produces: Azubi-`<td>`s haben `hx-target="#cell-dialog-body"` `hx-swap="innerHTML"` und rufen nach dem Swap `showModal()` auf. Monteur-`<td>`s bleiben bei `hx-target="this"` `hx-swap="outerHTML"`. Spätere Tasks (Task 3) liefern für Azubi-Zellen dann tatsächlich validen Inhalt für `#cell-dialog-body` — bis Task 3 fertig ist, öffnet der Dialog zwar, zeigt aber noch die alte `<td>`-verpackte Auswahl (visuell nicht final, das ist erwartet und wird in Task 3 behoben).

- [ ] **Step 1: Failing Tests schreiben**

Füge am Ende von `server/tests/test_web_cells.py` hinzu:

```python
def test_azubi_cell_targets_dialog_for_edit(admin):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "azubi")
    r = admin.get(f"/web/cells/{cell_id}")
    assert r.status_code == 200
    assert 'hx-target="#cell-dialog-body"' in r.text
    assert "showModal" in r.text


def test_monteur_cell_still_targets_itself_for_edit(admin):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "monteur")
    r = admin.get(f"/web/cells/{cell_id}")
    assert r.status_code == 200
    assert 'hx-target="this"' in r.text
    assert "cell-dialog-body" not in r.text
```

- [ ] **Step 2: Tests laufen lassen, sicherstellen dass sie fehlschlagen**

Run: `.venv/bin/python -m pytest tests/test_web_cells.py::test_azubi_cell_targets_dialog_for_edit tests/test_web_cells.py::test_monteur_cell_still_targets_itself_for_edit -v`
Expected: `test_azubi_cell_targets_dialog_for_edit` FAILS (aktuell `hx-target="this"` für alle Kategorien), `test_monteur_cell_still_targets_itself_for_edit` PASSES bereits (Monteur-Verhalten ist ja unverändert) — das ist ok, Ziel dieses Schritts ist nur zu bestätigen dass der Azubi-Test tatsächlich die neue Anforderung prüft.

- [ ] **Step 3: cell.html anpassen**

Aktueller Inhalt von `server/app/templates/partials/cell.html`:

```html
<td class="cell" id="cell-{{ cell_id }}"
    hx-get="/web/cells/{{ cell_id }}/edit" hx-trigger="click"
    hx-target="this" hx-swap="outerHTML">
  {% for entry in cell_entries %}
    {% if entry.type == "text" %}
      {% if worker.category == "azubi" and entry.content.text in azubi_status_class %}
        <div class="chip status-{{ azubi_status_class[entry.content.text] }}">{{ entry.content.text }}</div>
      {% elif worker.category == "azubi" %}
        <div class="chip chip-assigned">{{ entry.content.text }}</div>
      {% else %}
        <div class="entry-text">{{ entry.content.text }}</div>
      {% endif %}
    {% else %}
      <div class="entry-drawing">&#9998; Zeichnung</div>
    {% endif %}
  {% endfor %}
</td>
```

Ersetze die komplette Datei durch:

```html
{% if worker.category == "azubi" %}
<td class="cell" id="cell-{{ cell_id }}"
    hx-get="/web/cells/{{ cell_id }}/edit" hx-trigger="click"
    hx-target="#cell-dialog-body" hx-swap="innerHTML"
    hx-on::after-swap="document.getElementById('cell-dialog').showModal()">
{% else %}
<td class="cell" id="cell-{{ cell_id }}"
    hx-get="/web/cells/{{ cell_id }}/edit" hx-trigger="click"
    hx-target="this" hx-swap="outerHTML">
{% endif %}
  {% for entry in cell_entries %}
    {% if entry.type == "text" %}
      {% if worker.category == "azubi" and entry.content.text in azubi_status_class %}
        <div class="chip status-{{ azubi_status_class[entry.content.text] }}">{{ entry.content.text }}</div>
      {% elif worker.category == "azubi" %}
        <div class="chip chip-assigned">{{ entry.content.text }}</div>
      {% else %}
        <div class="entry-text">{{ entry.content.text }}</div>
      {% endif %}
    {% else %}
      <div class="entry-drawing">&#9998; Zeichnung</div>
    {% endif %}
  {% endfor %}
</td>
```

(Einzige Änderung: die öffnende `<td ...>`-Zeile ist jetzt nach `worker.category` verzweigt. Die Schleife über `cell_entries` bleibt exakt gleich.)

- [ ] **Step 4: Tests laufen lassen, sicherstellen dass sie bestehen**

Run: `.venv/bin/python -m pytest tests/test_web_cells.py::test_azubi_cell_targets_dialog_for_edit tests/test_web_cells.py::test_monteur_cell_still_targets_itself_for_edit -v`
Expected: beide PASS

- [ ] **Step 5: Volle Suite laufen lassen**

Run: `.venv/bin/python -m pytest -q`
Expected: alle Tests grün (51 vorher + 2 neu = 53 passed)

- [ ] **Step 6: Commit**

```bash
git add server/app/templates/partials/cell.html server/tests/test_web_cells.py
git commit -m "webui: Azubi-Zellen öffnen den Cell-Dialog statt sich selbst zu ersetzen"
```

---

### Task 3: Picker-Inhalt im Dialog + Speichern schließt automatisch

**Files:**
- Modify: `server/app/templates/partials/cell_edit.html`
- Modify: `server/app/web/routes.py:145-190` (Funktion `web_create_entry`)
- Test: `server/tests/test_web_cells.py`

**Interfaces:**
- Consumes: `#cell-dialog-body`/`#cell-dialog` (Task 1), Azubi-`<td>`-Trigger aus Task 2.
- Produces: GET `/web/cells/{cell_id}/edit` liefert für Azubi-Zellen ein `<div>`-Fragment (kein `<td>` mehr) mit Picker-Buttons und optionaler Fehlermeldung — gültig für ein `innerHTML`-Swap in `#cell-dialog-body`. POST `/web/cells/{cell_id}/entries` liefert bei Erfolg (Azubi) zusätzlich die Response-Header `HX-Retarget: #cell-{cell_id}` und `HX-Reswap: outerHTML`, wodurch htmx die Antwort (die reale, aktualisierte `cell.html`-`<td>`) automatisch in die echte Tabellenzelle statt in den Dialog swapt. Bei einem Validierungsfehler bleiben diese Header weg.

- [ ] **Step 1: Failing Tests schreiben**

Füge am Ende von `server/tests/test_web_cells.py` hinzu:

```python
def test_azubi_edit_fragment_is_not_wrapped_in_td(admin):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "azubi")
    r = admin.get(f"/web/cells/{cell_id}/edit")
    assert r.status_code == 200
    assert "<td" not in r.text
    assert "cell-dialog" in r.text


def test_monteur_edit_fragment_still_wrapped_in_td(admin):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "monteur")
    r = admin.get(f"/web/cells/{cell_id}/edit")
    assert r.status_code == 200
    assert '<td class="cell editing"' in r.text


def test_azubi_picker_success_retargets_to_real_cell(admin):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "azubi")
    r = admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Krank"})
    assert r.status_code == 200
    assert r.headers["HX-Retarget"] == f"#cell-{cell_id}"
    assert r.headers["HX-Reswap"] == "outerHTML"


def test_azubi_picker_error_does_not_retarget(admin):
    conn = admin.app.state.db
    _, cell_id = _cell(conn, "azubi")
    r = admin.post(f"/web/cells/{cell_id}/entries", data={"text": "Unsinn"})
    assert r.status_code == 200
    assert "HX-Retarget" not in r.headers
    assert "Ungültige Auswahl" in r.text
```

- [ ] **Step 2: Tests laufen lassen, sicherstellen dass sie fehlschlagen**

Run: `.venv/bin/python -m pytest tests/test_web_cells.py::test_azubi_edit_fragment_is_not_wrapped_in_td tests/test_web_cells.py::test_monteur_edit_fragment_still_wrapped_in_td tests/test_web_cells.py::test_azubi_picker_success_retargets_to_real_cell tests/test_web_cells.py::test_azubi_picker_error_does_not_retarget -v`
Expected: `test_azubi_edit_fragment_is_not_wrapped_in_td` FAILS (aktuell noch `<td>`-verpackt), `test_azubi_picker_success_retargets_to_real_cell` FAILS (Header existieren noch nicht), `test_monteur_edit_fragment_still_wrapped_in_td` und `test_azubi_picker_error_does_not_retarget` PASSEN bereits (bestehendes Verhalten) — das ist ok.

- [ ] **Step 3: cell_edit.html umbauen**

Aktueller Inhalt von `server/app/templates/partials/cell_edit.html`:

```html
<td class="cell editing" id="cell-{{ cell_id }}">
  {% if error %}<p class="error">{{ error }}</p>{% endif %}

  {% if worker.category == "azubi" %}
    <div class="picker">
      <p class="picker-label">Status</p>
      {% for label, css_class in azubi_status_class.items() %}
      <form hx-post="/web/cells/{{ cell_id }}/entries" hx-target="#cell-{{ cell_id }}" hx-swap="outerHTML">
        <input type="hidden" name="text" value="{{ label }}">
        <button type="submit" class="chip-btn status-{{ css_class }}">{{ label }}</button>
      </form>
      {% endfor %}

      <p class="picker-label">Monteur zuordnen</p>
      {% for m in monteure %}
      <form hx-post="/web/cells/{{ cell_id }}/entries" hx-target="#cell-{{ cell_id }}" hx-swap="outerHTML">
        <input type="hidden" name="text" value="{{ m.number }} {{ m.name }}">
        <button type="submit" class="chip-btn chip-assigned">{{ m.number }} {{ m.name }}</button>
      </form>
      {% endfor %}

      <form hx-post="/web/cells/{{ cell_id }}/entries" hx-target="#cell-{{ cell_id }}" hx-swap="outerHTML">
        <input type="hidden" name="text" value="">
        <button type="submit" class="chip-btn chip-clear">Leeren</button>
      </form>
    </div>
    <button type="button" class="btn-text" hx-get="/web/cells/{{ cell_id }}"
            hx-target="#cell-{{ cell_id }}" hx-swap="outerHTML">Abbrechen</button>
  {% else %}
    {% for entry in cell_entries if entry.type == "text" %}
    <form hx-post="/web/entries/{{ entry.id }}" hx-target="#cell-{{ cell_id }}"
          hx-swap="outerHTML">
      <input type="hidden" name="base_revision" value="{{ entry.revision }}">
      <textarea name="text" rows="3">{{ entry.content.text }}</textarea>
      <div class="cell-actions">
        <button type="submit" class="btn-primary">Speichern</button>
        <button type="button" class="btn-text" hx-post="/web/entries/{{ entry.id }}/delete"
                hx-target="#cell-{{ cell_id }}" hx-swap="outerHTML">Löschen</button>
      </div>
    </form>
    {% endfor %}
    {% for entry in cell_entries if entry.type == "drawing" %}
    <div class="entry-drawing">&#9998; Zeichnung (nur am Tablet bearbeitbar)
      <button type="button" class="btn-text" hx-post="/web/entries/{{ entry.id }}/delete"
              hx-target="#cell-{{ cell_id }}" hx-swap="outerHTML">Löschen</button>
    </div>
    {% endfor %}
    <form hx-post="/web/cells/{{ cell_id }}/entries" hx-target="#cell-{{ cell_id }}"
          hx-swap="outerHTML">
      <textarea name="text" rows="3" placeholder="Neuer Hinweis"></textarea>
      <div class="cell-actions">
        <button type="submit" class="btn-primary">Hinzufügen</button>
        <button type="button" class="btn-text" hx-get="/web/cells/{{ cell_id }}"
                hx-target="#cell-{{ cell_id }}" hx-swap="outerHTML">Abbrechen</button>
      </div>
    </form>
  {% endif %}
</td>
```

Ersetze die komplette Datei durch:

```html
{% if worker.category == "azubi" %}
  {% if error %}<p class="error">{{ error }}</p>{% endif %}
  <div class="picker">
    <p class="picker-label">Status</p>
    {% for label, css_class in azubi_status_class.items() %}
    <form hx-post="/web/cells/{{ cell_id }}/entries" hx-target="#cell-dialog-body" hx-swap="innerHTML"
          hx-on::after-request="if(event.detail.xhr.getResponseHeader('HX-Retarget')) document.getElementById('cell-dialog').close()">
      <input type="hidden" name="text" value="{{ label }}">
      <button type="submit" class="chip-btn status-{{ css_class }}">{{ label }}</button>
    </form>
    {% endfor %}

    <p class="picker-label">Monteur zuordnen</p>
    {% for m in monteure %}
    <form hx-post="/web/cells/{{ cell_id }}/entries" hx-target="#cell-dialog-body" hx-swap="innerHTML"
          hx-on::after-request="if(event.detail.xhr.getResponseHeader('HX-Retarget')) document.getElementById('cell-dialog').close()">
      <input type="hidden" name="text" value="{{ m.number }} {{ m.name }}">
      <button type="submit" class="chip-btn chip-assigned">{{ m.number }} {{ m.name }}</button>
    </form>
    {% endfor %}

    <form hx-post="/web/cells/{{ cell_id }}/entries" hx-target="#cell-dialog-body" hx-swap="innerHTML"
          hx-on::after-request="if(event.detail.xhr.getResponseHeader('HX-Retarget')) document.getElementById('cell-dialog').close()">
      <input type="hidden" name="text" value="">
      <button type="submit" class="chip-btn chip-clear">Leeren</button>
    </form>
  </div>
  <button type="button" class="btn-text" onclick="document.getElementById('cell-dialog').close()">Abbrechen</button>
{% else %}
<td class="cell editing" id="cell-{{ cell_id }}">
  {% if error %}<p class="error">{{ error }}</p>{% endif %}
  {% for entry in cell_entries if entry.type == "text" %}
  <form hx-post="/web/entries/{{ entry.id }}" hx-target="#cell-{{ cell_id }}"
        hx-swap="outerHTML">
    <input type="hidden" name="base_revision" value="{{ entry.revision }}">
    <textarea name="text" rows="3">{{ entry.content.text }}</textarea>
    <div class="cell-actions">
      <button type="submit" class="btn-primary">Speichern</button>
      <button type="button" class="btn-text" hx-post="/web/entries/{{ entry.id }}/delete"
              hx-target="#cell-{{ cell_id }}" hx-swap="outerHTML">Löschen</button>
    </div>
  </form>
  {% endfor %}
  {% for entry in cell_entries if entry.type == "drawing" %}
  <div class="entry-drawing">&#9998; Zeichnung (nur am Tablet bearbeitbar)
    <button type="button" class="btn-text" hx-post="/web/entries/{{ entry.id }}/delete"
            hx-target="#cell-{{ cell_id }}" hx-swap="outerHTML">Löschen</button>
  </div>
  {% endfor %}
  <form hx-post="/web/cells/{{ cell_id }}/entries" hx-target="#cell-{{ cell_id }}"
        hx-swap="outerHTML">
    <textarea name="text" rows="3" placeholder="Neuer Hinweis"></textarea>
    <div class="cell-actions">
      <button type="submit" class="btn-primary">Hinzufügen</button>
      <button type="button" class="btn-text" hx-get="/web/cells/{{ cell_id }}"
              hx-target="#cell-{{ cell_id }}" hx-swap="outerHTML">Abbrechen</button>
    </div>
  </form>
</td>
{% endif %}
```

Wichtig: Der Azubi-Zweig hat jetzt **keinen** `<td>`-Wrapper mehr (er landet als `innerHTML` in `#cell-dialog-body`, einem `<div>` — ein `<td>` außerhalb eines `<table>`-Kontexts wäre ungültiges HTML und würde vom Browser-Parser verworfen/verschoben). Der Monteur-Zweig bleibt exakt wie vorher inklusive `<td class="cell editing">`-Wrapper.

- [ ] **Step 4: routes.py anpassen — Erfolgsfall bekommt Retarget-Header**

In `server/app/web/routes.py` steht in `web_create_entry` (im Azubi-Zweig, nach dem `try`/`except`-Block):

```python
        await _broadcast_cell(request, cell_id)
        return _render_cell(request, cell_id)

    if not value:
```

Ändere das zu:

```python
        await _broadcast_cell(request, cell_id)
        response = _render_cell(request, cell_id)
        response.headers["HX-Retarget"] = f"#cell-{cell_id}"
        response.headers["HX-Reswap"] = "outerHTML"
        return response

    if not value:
```

(Nur der Azubi-Erfolgspfad bekommt die zwei zusätzlichen Header. Der Monteur-Zweig darunter — `if not value: ... return _render_cell(...)` bis zum Ende der Funktion — bleibt komplett unverändert.)

- [ ] **Step 5: Tests laufen lassen, sicherstellen dass sie bestehen**

Run: `.venv/bin/python -m pytest tests/test_web_cells.py -v`
Expected: alle Tests in dieser Datei PASS, inklusive der 4 neuen aus Step 1 und aller bereits bestehenden (insbesondere `test_azubi_picker_selects_status`, `test_azubi_picker_selects_monteur`, `test_azubi_picker_rejects_arbitrary_text`, `test_azubi_picker_replaces_existing_entry_instead_of_duplicating`, `test_azubi_picker_clear_deletes_entry`, `test_azubi_picker_consolidates_preexisting_duplicate_entries` — deren Assertions prüfen nur Response-Text und DB-Zustand, nicht Header, daher unberührt von dieser Änderung).

- [ ] **Step 6: Volle Suite laufen lassen**

Run: `.venv/bin/python -m pytest -q`
Expected: alle Tests grün (53 vorher + 4 neu = 57 passed)

- [ ] **Step 7: Commit**

```bash
git add server/app/templates/partials/cell_edit.html server/app/web/routes.py server/tests/test_web_cells.py
git commit -m "webui: Azubi-Picker lebt im Dialog, Speichern schließt ihn automatisch"
```

---

## Nach Abschluss aller Tasks (manuelle Verifikation)

Automatisierte Tests prüfen HTML-Fragmentinhalt, Response-Header und DB-Zustand, aber **nicht** tatsächliches Browser-Verhalten (Dialog öffnet visuell, `showModal()`/`close()` laufen wirklich, ESC schließt). Nach Task 3 im Browser (Emulator oder echtes Tablet) verifizieren:

1. Klick auf eine Azubi-Zelle → Dialog öffnet sich mit Status-/Monteur-Buttons, Tabellenzeile bleibt kompakt.
2. Klick auf "Krank" (oder einen Monteur) → Dialog schließt sich sofort, Zelle in der Tabelle zeigt den neuen Chip.
3. Erneuter Klick auf dieselbe Zelle, dann "Abbrechen" → Dialog schließt ohne Änderung.
4. ESC-Taste bei offenem Dialog → schließt ohne Änderung.
5. Klick auf eine Monteur-Zelle → weiterhin Inline-Textfeld wie bisher, kein Dialog.

Falls Schritt 2 nicht wie erwartet schließt (z.B. weil `event.detail.xhr.getResponseHeader` in der verwendeten htmx-Version anders benannt ist), das während der manuellen Verifikation direkt im Browser via DevTools-Konsole nachvollziehen und den `hx-on::after-request`-Ausdruck in `cell_edit.html` entsprechend korrigieren — kein neuer Task nötig, da es sich um dieselbe Zeile aus Task 3 handelt.

## Danach

Feature-Branch erstellen (`feature/webui-azubi-picker-modal`), alle drei Tasks committen (bereits während der Tasks passiert), finale Whole-Branch-Review dispatchen, Findings beheben, mergen nach `main` — wie bei den vorherigen Phasen (`superpowers:finishing-a-development-branch`-Muster).
