# WebUI: drawing viewBox bounding-box + text/drawing overlap

Status: DONE

## Scope

Repo root: `/home/admin/Projekte/TimePlan` (not a git repo at the `server/`
level — the git root is one level up). Branch: `main`. No Android/Kotlin
files were touched; a parallel task was concurrently modifying those, and
they were left untouched and unstaged in this commit.

## Change 1 — SVG viewBox zooms into drawn content bounding box

`server/app/web/routes.py`: added `_drawing_viewbox(content, padding_fraction=0.1)`
right after `templates = Jinja2Templates(...)`, and registered it as the
Jinja filter `drawing_viewbox` via `templates.env.filters["drawing_viewbox"] = _drawing_viewbox`.

Logic: flattens all points across all strokes; if none, falls back to
`"0 0 {canvas_width} {canvas_height}"`. Otherwise takes min/max x and y,
floors raw width/height at 1 (`max(max_x - min_x, 1)`), computes 10% padding
of the raw (pre-floor... actually post-floor, since floor is applied first)
width/height on each side, and returns `"minX minY width height"`.

`server/app/templates/partials/cell.html`: changed the drawing `<svg>`'s
`viewBox` attribute from `"0 0 {{ entry.content.canvas_width }} {{ entry.content.canvas_height }}"`
to `"{{ entry.content | drawing_viewbox }}"`.

## Change 2 — text overlaps drawing (text on top) in the WebUI grid

`server/app/static/style.css`:
- `.grid td.cell` gained `position: relative;` (anchor for the absolutely
  positioned drawing).
- `.entry-text` gained `position: relative; z-index: 1;` plus a translucent
  paper-colored backing (`background: rgba(249, 241, 227, 0.88);`,
  `display: inline-block; border-radius: 3px; padding: 0 .15rem;`) so text
  stays legible over a drawing beneath it.
- `.entry-drawing` changed from a block-flow 34px-tall strip
  (`display:block; width:100%; height:34px; margin-top:2px;`) to an
  absolutely positioned full-cell background layer:
  `position: absolute; inset: .4rem; width: auto; height: auto; z-index: 0;
  pointer-events: none;`. `inset: .4rem` matches the cell's own `.4rem`
  padding so the drawing fills the same content box as the text.
  `pointer-events: none` keeps the `<td>`'s `hx-get`/click handler working.
- `.chip` gained `position: relative; z-index: 1;` (defensive — Azubi cells
  can't actually hold drawings server-side, but keeps the rule consistent
  with `.entry-text`).

No Jinja template restructuring was needed or done — the loop order in
`cell.html` is unchanged; CSS alone produces the overlap.

## Tests

File: `server/tests/test_web_week.py`.

Added `from app.web.routes import _drawing_viewbox` import.

### New unit tests for `_drawing_viewbox`

1. `test_drawing_viewbox_single_stroke_two_points` — points (0,0) and
   (10,20).
   - width = max(10-0, 1) = 10, height = max(20-0, 1) = 20
   - pad_x = 10 * 0.1 = 1.0, pad_y = 20 * 0.1 = 2.0
   - minX = 0 - 1.0 = -1.0, minY = 0 - 2.0 = -2.0
   - w = 10 + 2*1.0 = 12.0, h = 20 + 2*2.0 = 24.0
   - expected: `"-1.0 -2.0 12.0 24.0"`

2. `test_drawing_viewbox_spans_multiple_strokes` — stroke A has point
   (0,0), stroke B has point (100,50) — verifies the bbox spans across
   strokes, not just the first one.
   - width = max(100-0,1) = 100, height = max(50-0,1) = 50
   - pad_x = 10.0, pad_y = 5.0
   - minX = -10.0, minY = -5.0, w = 120.0, h = 60.0
   - expected: `"-10.0 -5.0 120.0 60.0"`

3. `test_drawing_viewbox_falls_back_to_canvas_size_when_no_strokes` —
   `strokes: []`, `canvas_width: 300, canvas_height: 120` →
   expected: `"0 0 300 120"`.

4. `test_drawing_viewbox_horizontal_line_has_positive_height` — points
   (0,5) and (20,5), constant y (perfectly horizontal line).
   - raw height = 20*0 = 0 → floored to `max(0, 1) = 1`
   - width = max(20-0,1) = 20, height = 1
   - pad_x = 2.0, pad_y = 0.1
   - minX = 0-2.0 = -2.0, minY = 5-0.1 = 4.9
   - w = 20+4.0 = 24.0, h = 1+0.2 = 1.2
   - expected: `"-2.0 4.9 24.0 1.2"`; asserts the parsed height field is
     `> 0` (not zero) in addition to the exact string, to make the "not
     zero" property explicit.

All four values were computed by hand per the formula and cross-checked by
running the actual `_drawing_viewbox` function standalone in a Python
one-liner before writing the assertions, to rule out float-formatting
surprises (e.g. `19.8` vs `19.799999999999997`) — none occurred; Python's
default float repr produced the clean decimal values shown above for all
cases exercised.

### Updated existing tests

`test_week_page_renders_drawing_as_inline_svg` — fixture points are
(10.0, 20.0) and (15.0, 22.0).
- width = max(15-10,1) = 5, height = max(22-20,1) = 2
- pad_x = 0.5, pad_y = 0.2
- minX = 10-0.5 = 9.5, minY = 20-0.2 = 19.8
- w = 5+1.0 = 6.0, h = 2+0.4 = 2.4
- Old assertion `'viewBox="0 0 300 120"'` replaced with
  `'viewBox="9.5 19.8 6.0 2.4"'`.

`test_week_page_stacks_text_and_drawing_in_the_same_cell` → renamed to
`test_week_page_shows_text_and_drawing_together_in_the_same_cell` (its old
name implied vertical stacking, which no longer describes the CSS-overlap
behavior). Assertions unchanged (`"Hinweis" in r.text` and
`'<svg class="entry-drawing"' in r.text`) — both elements are still present
in the HTML, just positioned differently via CSS; no browser-based overlap
assertion was added (out of scope per this test suite's established
manual-verification-only limitation for rendering behavior).

## Verification

Command:
```
cd /home/admin/Projekte/TimePlan/server && .venv/bin/python -m pytest -q
```

Output:
```
........................................................................ [ 78%]
....................                                                     [100%]
=============================== warnings summary ===============================
tests/test_app_settings_repo.py::test_default_settings_are_both_visible
  /home/admin/Projekte/TimePlan/server/.venv/lib/python3.14/site-packages/fastapi/testclient.py:1: StarletteDeprecationWarning: Using `httpx` with `starlette.testclient` is deprecated; install `httpx2` instead.
    from starlette.testclient import TestClient as TestClient  # noqa

-- Docs: https://docs.pytest.org/en/stable/how-to/capture-warnings.html
92 passed, 1 warning in 4.59s
```

92 passed, 0 failed.

## Commit

Staged only the four server-side files touched by this task (left the
concurrently-modified Android/Kotlin files from the parallel task
untouched and unstaged):

```
git add server/app/static/style.css server/app/templates/partials/cell.html \
        server/app/web/routes.py server/tests/test_web_week.py
```

Commit hash: `1873056`

Commit message:
```
feat: scale drawing SVG to content bounds, overlap text over drawings in WebUI grid

- Add _drawing_viewbox() route helper (registered as Jinja filter
  `drawing_viewbox`) that computes a padded bounding box around a
  drawing's actual points, so small doodles zoom to fill the cell
  instead of rendering tiny against the full tablet canvas size.
- Make .entry-drawing an absolutely positioned background layer and
  .entry-text/.chip relative+z-index+translucent backing so text
  overlaps drawings instead of stacking below them, staying legible.
- Add unit tests for _drawing_viewbox and update/rename the two
  existing drawing-cell tests in test_web_week.py to match.
```

## Concerns / notes

- None outstanding. The two prescribed CSS/Python code blocks were applied
  verbatim as specified in the task; no deviations were needed.
- Verifying the actual visual overlap (text rendering legibly on top of a
  drawing) requires a browser and is out of scope for this test suite, as
  noted in the task — this is an established, accepted limitation already
  covering the rest of this feature.
