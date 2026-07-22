# Wochen-Wischnavigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eine horizontale Wisch-Geste auf der Wochenraster-Seite löst denselben Wochenwechsel aus wie die bestehenden Vorherige/Nächste-Buttons, mit Gleit-Übergangsanimation — in WebUI und Android-App.

**Architecture:** Rein client-seitig, keine Server-Änderungen. WebUI: ein neues `static/swipe.js` erkennt die Geste per Touch-Events und navigiert zur bestehenden Prev/Next-URL; die native Cross-Document View Transitions API übernimmt die Animation. Android: eine reine, Android-freie Kotlin-Klasse (`WeekSwipeGesture`) kapselt die Gesten-Erkennung und ist per JUnit unit-testbar; ein custom `SwipeInterceptLayout` (ViewGroup-Subklasse) löst das Problem, dass eine horizontale Wisch-Geste zuverlässig erkannt werden muss, obwohl darunter eine vertikal scrollende `RecyclerView` liegt — Standard-Android-Technik via `onInterceptTouchEvent`.

**Tech Stack:** Vanilla JS (kein neues Framework), htmx bereits vorhanden; Kotlin/Android Views (kein Compose), JUnit + Robolectric (bestehender Stack).

## Global Constraints

- Auslöser-basiert, kein Live-Fingerfolgen: die Ansicht folgt dem Finger nicht während der Bewegung, erst nach Erkennung der Geste läuft die Animation.
- Wischen nach links = nächste Woche, wischen nach rechts = vorherige Woche.
- Nur überwiegend horizontale Bewegungen zählen als Geste; vertikales Scrollen darf nicht beeinträchtigt werden.
- Nur die Wochenraster-Seite bekommt die Geste (WebUI `/week/{id}`, Android `WeekActivity`) — nicht Monteure/Einstellungen.
- Bestehende Vorherige/Nächste-Buttons/Links bleiben unverändert bestehen, die Geste ist additiv.
- Ist ein Zell-Dialog offen (WebUI: `#cell-dialog`: Android: der Azubi-Picker- oder Monteur-Editor-Dialog), wird die Wisch-Erkennung deaktiviert.
- Schlägt der durch Wischen ausgelöste Wochenwechsel fehl, greift der jeweils bereits bestehende Fehlerpfad — keine neue Fehlerbehandlung.
- Keine Server-Änderungen, keine neuen Server-Tests nötig.
- WebUI hat kein JavaScript-Test-Framework — `swipe.js`s Geste-Logik wird nicht automatisiert getestet; nur serverseitig wird per pytest geprüft, dass die neue Datei/das Meta-Tag/die Link-Klassen korrekt ausgeliefert werden.
- Alle bestehenden Tests müssen nach jedem Task weiterhin grün sein: `cd server && .venv/bin/python -m pytest -q` (Baseline vor Task 1: 72 passed) und `cd android && ./gradlew testDebugUnitTest assembleDebug` (Baseline: BUILD SUCCESSFUL, alle Tests grün).

---

### Task 1: WebUI — Wisch-Erkennung + View-Transitions-Animation

**Files:**
- Create: `server/app/static/swipe.js`
- Modify: `server/app/templates/base.html`
- Modify: `server/app/templates/week.html`
- Modify: `server/app/static/style.css`
- Test: `server/tests/test_web_week.py`

**Interfaces:**
- Produces: Keine Server-seitigen Funktionen — dieser Task ist rein Markup/Static-Assets. Spätere Tasks (Android) hängen nicht von diesem Task ab; beide Plattform-Tasks sind unabhängig voneinander.

- [ ] **Step 1: Failing Tests schreiben**

Füge in `server/tests/test_web_week.py` (am Ende der Datei) hinzu:

```python
def test_week_page_includes_swipe_script(admin):
    r = admin.get("/week/2026-W31")
    assert r.status_code == 200
    assert '<script src="/static/swipe.js" defer></script>' in r.text


def test_week_page_nav_links_have_swipe_target_classes(admin):
    r = admin.get("/week/2026-W31")
    assert 'class="weeknav-prev"' in r.text
    assert 'class="weeknav-next"' in r.text


def test_base_layout_opts_into_view_transitions(admin):
    r = admin.get("/week/2026-W31")
    assert '<meta name="view-transition" content="same-origin">' in r.text
```

- [ ] **Step 2: Tests laufen lassen, sicherstellen dass sie fehlschlagen**

Run: `cd /home/admin/Projekte/TimePlan/server && .venv/bin/python -m pytest tests/test_web_week.py::test_week_page_includes_swipe_script tests/test_web_week.py::test_week_page_nav_links_have_swipe_target_classes tests/test_web_week.py::test_base_layout_opts_into_view_transitions -v`
Expected: alle drei FAILEN (Skript/Klassen/Meta-Tag existieren noch nicht)

- [ ] **Step 3: Meta-Tag + Direction-Relay-Script in base.html ergänzen**

Aktueller Inhalt von `server/app/templates/base.html`:

```html
<!doctype html>
<html lang="de">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>TimePlan</title>
  <link rel="stylesheet" href="/static/style.css">
  <script src="/static/htmx.min.js" defer></script>
</head>
<body>
  <header class="topbar">
    <strong>TimePlan</strong>
    <nav>
      <a href="/">Wochenplan</a>
      <a href="/workers">Monteure</a>
      <a href="/settings">Einstellungen</a>
      <a href="/logout">Abmelden</a>
    </nav>
  </header>
  <main>{% block content %}{% endblock %}</main>
</body>
</html>
```

Ersetze die komplette Datei durch:

```html
<!doctype html>
<html lang="de">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="view-transition" content="same-origin">
  <title>TimePlan</title>
  <script>
    (function () {
      var dir = sessionStorage.getItem("timeplan-swipe-dir");
      if (dir) {
        sessionStorage.removeItem("timeplan-swipe-dir");
        document.documentElement.classList.add("swipe-" + dir);
      }
    })();
  </script>
  <link rel="stylesheet" href="/static/style.css">
  <script src="/static/htmx.min.js" defer></script>
</head>
<body>
  <header class="topbar">
    <strong>TimePlan</strong>
    <nav>
      <a href="/">Wochenplan</a>
      <a href="/workers">Monteure</a>
      <a href="/settings">Einstellungen</a>
      <a href="/logout">Abmelden</a>
    </nav>
  </header>
  <main>{% block content %}{% endblock %}</main>
</body>
</html>
```

(Das kleine Inline-Skript liest synchron, noch vor dem ersten Render, ob die
vorherige Seite eine Wisch-Richtung hinterlassen hat, setzt dann sofort eine
`swipe-next`/`swipe-prev`-Klasse auf `<html>` und löscht den Eintrag wieder —
so wirkt die Klasse nur für genau die eine Navigation, die durch Wischen
ausgelöst wurde, nicht für normale Klicks auf die Vorherige/Nächste-Links.)

- [ ] **Step 4: Klassen an den Vorherige/Nächste-Links + swipe.js einbinden in week.html**

Aktueller Inhalt von `server/app/templates/week.html` (Ausschnitt):

```html
<div class="weeknav">
  <a href="/week/{{ prev_week }}">&larr; Vorherige</a>
  <strong>{{ week_id }}</strong>
  <span class="status">[{{ week.status }}]</span>
  <a href="/week/{{ next_week }}">Nächste &rarr;</a>
  <a href="/week/{{ current_week }}">Heute</a>
</div>
```

Ändere das zu:

```html
<div class="weeknav">
  <a href="/week/{{ prev_week }}" class="weeknav-prev">&larr; Vorherige</a>
  <strong>{{ week_id }}</strong>
  <span class="status">[{{ week.status }}]</span>
  <a href="/week/{{ next_week }}" class="weeknav-next">Nächste &rarr;</a>
  <a href="/week/{{ current_week }}">Heute</a>
</div>
```

Am Ende derselben Datei steht:

```html
<script src="/static/live.js" defer></script>
{% endblock %}
```

Ändere das zu:

```html
<script src="/static/live.js" defer></script>
<script src="/static/swipe.js" defer></script>
{% endblock %}
```

- [ ] **Step 5: swipe.js erstellen**

Erstelle `server/app/static/swipe.js`:

```js
(function () {
  var MIN_DISTANCE = 60;
  var MAX_DURATION = 600;
  var MIN_RATIO = 1.5;

  function detectSwipeDirection(dx, dy, durationMs) {
    if (durationMs > MAX_DURATION) return null;
    if (Math.abs(dx) < MIN_DISTANCE) return null;
    if (Math.abs(dx) <= Math.abs(dy) * MIN_RATIO) return null;
    return dx < 0 ? "next" : "prev";
  }

  function isDialogOpen() {
    var dialog = document.getElementById("cell-dialog");
    return !!(dialog && dialog.open);
  }

  function navigate(direction) {
    var link = document.querySelector(
      direction === "next" ? ".weeknav-next" : ".weeknav-prev"
    );
    if (!link) return;
    document.documentElement.classList.add("swipe-" + direction);
    sessionStorage.setItem("timeplan-swipe-dir", direction);
    location.href = link.href;
  }

  var startX = 0, startY = 0, startTime = 0, tracking = false;

  document.addEventListener("touchstart", function (e) {
    if (isDialogOpen() || e.touches.length !== 1) { tracking = false; return; }
    tracking = true;
    startX = e.touches[0].clientX;
    startY = e.touches[0].clientY;
    startTime = Date.now();
  }, { passive: true });

  document.addEventListener("touchend", function (e) {
    if (!tracking) return;
    tracking = false;
    if (isDialogOpen()) return;
    var touch = e.changedTouches[0];
    var dx = touch.clientX - startX;
    var dy = touch.clientY - startY;
    var duration = Date.now() - startTime;
    var direction = detectSwipeDirection(dx, dy, duration);
    if (direction) navigate(direction);
  }, { passive: true });
})();
```

- [ ] **Step 6: Übergangsanimation in style.css ergänzen**

Füge am Ende von `server/app/static/style.css` hinzu:

```css

/* Wochen-Wischnavigation: gerichteter Übergang via View Transitions API */
::view-transition-old(root),
::view-transition-new(root) {
  animation-duration: .25s;
  animation-timing-function: ease-out;
}
html.swipe-next::view-transition-old(root) { animation-name: timeplan-slide-out-left; }
html.swipe-next::view-transition-new(root) { animation-name: timeplan-slide-in-right; }
html.swipe-prev::view-transition-old(root) { animation-name: timeplan-slide-out-right; }
html.swipe-prev::view-transition-new(root) { animation-name: timeplan-slide-in-left; }

@keyframes timeplan-slide-out-left { to { transform: translateX(-100%); } }
@keyframes timeplan-slide-in-right { from { transform: translateX(100%); } to { transform: translateX(0); } }
@keyframes timeplan-slide-out-right { to { transform: translateX(100%); } }
@keyframes timeplan-slide-in-left { from { transform: translateX(-100%); } to { transform: translateX(0); } }
```

- [ ] **Step 7: Tests laufen lassen, sicherstellen dass sie bestehen**

Run: `.venv/bin/python -m pytest tests/test_web_week.py -v`
Expected: alle Tests in dieser Datei PASS, inklusive der 3 neuen

- [ ] **Step 8: Volle Suite laufen lassen**

Run: `.venv/bin/python -m pytest -q`
Expected: alle Tests grün (72 vorher + 3 neu = 75 passed)

- [ ] **Step 9: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add server/app/static/swipe.js server/app/templates/base.html server/app/templates/week.html server/app/static/style.css server/tests/test_web_week.py
git commit -m "webui: Wisch-Navigation zwischen Wochen mit View-Transitions-Animation"
```

---

### Task 2: Android — isolierte Gesten-Erkennung (`WeekSwipeGesture`)

**Files:**
- Create: `android/app/src/main/java/de/fs/timeplan/week/WeekSwipeGesture.kt`
- Test: `android/app/src/test/java/de/fs/timeplan/week/WeekSwipeGestureTest.kt`

**Interfaces:**
- Produces: `enum class SwipeDirection { PREV, NEXT }` und
  `object WeekSwipeGesture { fun detect(dx: Float, dy: Float, durationMs: Long, minDistancePx: Float): SwipeDirection? }`.
  Reine Kotlin-Logik, keine Android-Imports — direkt mit `org.junit.Test` testbar,
  kein Robolectric nötig. Wird in Task 3 von `SwipeInterceptLayout` konsumiert.

- [ ] **Step 1: Failing Test schreiben**

Erstelle `android/app/src/test/java/de/fs/timeplan/week/WeekSwipeGestureTest.kt`:

```kotlin
package de.fs.timeplan.week

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeekSwipeGestureTest {

    @Test
    fun `leftward swipe past threshold is NEXT`() {
        assertEquals(SwipeDirection.NEXT, WeekSwipeGesture.detect(-150f, 10f, 200L, 100f))
    }

    @Test
    fun `rightward swipe past threshold is PREV`() {
        assertEquals(SwipeDirection.PREV, WeekSwipeGesture.detect(150f, 10f, 200L, 100f))
    }

    @Test
    fun `too short a distance is ignored`() {
        assertNull(WeekSwipeGesture.detect(-50f, 10f, 200L, 100f))
    }

    @Test
    fun `too slow a movement is ignored`() {
        assertNull(WeekSwipeGesture.detect(-150f, 10f, 800L, 100f))
    }

    @Test
    fun `movement that is not dominantly horizontal is ignored`() {
        assertNull(WeekSwipeGesture.detect(-150f, 120f, 200L, 100f))
    }

    @Test
    fun `pure vertical scroll is ignored`() {
        assertNull(WeekSwipeGesture.detect(0f, 200f, 200L, 100f))
    }
}
```

- [ ] **Step 2: Test laufen lassen, sicherstellen dass er fehlschlägt**

Run: `cd /home/admin/Projekte/TimePlan/android && JAVA_HOME="$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)" ./gradlew testDebugUnitTest --tests "de.fs.timeplan.week.WeekSwipeGestureTest" --console=plain`
Expected: FAIL — `WeekSwipeGesture` bzw. `SwipeDirection` existieren noch nicht (Compile-Fehler)

- [ ] **Step 3: WeekSwipeGesture.kt implementieren**

Erstelle `android/app/src/main/java/de/fs/timeplan/week/WeekSwipeGesture.kt`:

```kotlin
package de.fs.timeplan.week

import kotlin.math.abs

enum class SwipeDirection { PREV, NEXT }

object WeekSwipeGesture {
    private const val MAX_DURATION_MS = 600L
    private const val MIN_RATIO = 1.5f

    fun detect(dx: Float, dy: Float, durationMs: Long, minDistancePx: Float): SwipeDirection? {
        if (durationMs > MAX_DURATION_MS) return null
        if (abs(dx) < minDistancePx) return null
        if (abs(dx) <= abs(dy) * MIN_RATIO) return null
        return if (dx < 0) SwipeDirection.NEXT else SwipeDirection.PREV
    }
}
```

- [ ] **Step 4: Test laufen lassen, sicherstellen dass er besteht**

Run: `JAVA_HOME="$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)" ./gradlew testDebugUnitTest --tests "de.fs.timeplan.week.WeekSwipeGestureTest" --console=plain`
Expected: PASS, alle 6 Tests grün

- [ ] **Step 5: Volle Android-Suite + Build laufen lassen**

Run: `JAVA_HOME="$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)" ./gradlew testDebugUnitTest assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL, alle Tests grün

- [ ] **Step 6: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add android/app/src/main/java/de/fs/timeplan/week/WeekSwipeGesture.kt android/app/src/test/java/de/fs/timeplan/week/WeekSwipeGestureTest.kt
git commit -m "android: isolierte Wisch-Gesten-Erkennung (WeekSwipeGesture)"
```

---

### Task 3: Android — `SwipeInterceptLayout` + Verkabelung in WeekActivity

**Files:**
- Create: `android/app/src/main/java/de/fs/timeplan/grid/SwipeInterceptLayout.kt`
- Modify: `android/app/src/main/res/layout/activity_week.xml`
- Modify: `android/app/src/main/java/de/fs/timeplan/WeekActivity.kt`
- Test: `android/app/src/test/java/de/fs/timeplan/week/WeekActivityTest.kt`

**Interfaces:**
- Consumes: `WeekSwipeGesture.detect(...)`/`SwipeDirection` aus Task 2.
- Produces: nichts für spätere Tasks — letzter Task des Plans.

**Hintergrund — warum ein custom ViewGroup nötig ist:** Ein einfacher
`setOnTouchListener` auf dem Container würde die meisten echten Wisch-Gesten
verpassen, weil der Finger fast immer über der `RecyclerView` startet (sie
füllt den größten Teil des Bildschirms) — Android leitet Touch-Events zuerst
an das tiefste Kind weiter, und `RecyclerView` beansprucht sie sofort für
ihr eigenes (vertikales) Scrollen. Die Standard-Android-Lösung dafür ist
`ViewGroup.onInterceptTouchEvent`: der Elternteil beobachtet die ersten
Bewegungen, und sobald sie eindeutig horizontal sind, "stiehlt" er den
Touch-Stream (die `RecyclerView` bekommt dann ein `ACTION_CANCEL` und
scrollt nicht weiter) und wertet die Geste selbst zu Ende aus. Bei einer
überwiegend vertikalen Bewegung greift der Elternteil nie ein, die
`RecyclerView` scrollt ganz normal weiter.

- [ ] **Step 1: Failing Test schreiben**

Füge in `android/app/src/test/java/de/fs/timeplan/week/WeekActivityTest.kt` einen neuen Test hinzu (nach der bestehenden `day header labels reflect the actual weekday even with a gap`-Testfunktion):

```kotlin
    @Test
    fun `swipe gesture is disabled while a cell dialog is showing`() {
        val controller = Robolectric.buildActivity(WeekActivity::class.java)
        controller.setup()
        val activity = controller.get()

        val workers = (DemoApi.getWorkers() as ApiResult.Success).data.workers
        val monteur = workers.first { it.isMonteur }
        val weekId = WeekId.currentWeekId()
        val dates = WeekId.weekDates(weekId).map { it.toString() }
        clickCell(activity, monteur.id, 0, dates)

        // Ein Dialog ist jetzt offen (siehe bestehendes Testmuster oben);
        // isDialogShowing muss dadurch true geworden sein.
        val field = WeekActivity::class.java.getDeclaredField("isDialogShowing")
        field.isAccessible = true
        assertTrue(field.getBoolean(activity))
    }
```

- [ ] **Step 2: Test laufen lassen, sicherstellen dass er fehlschlägt**

Run: `cd /home/admin/Projekte/TimePlan/android && JAVA_HOME="$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)" ./gradlew testDebugUnitTest --tests "de.fs.timeplan.week.WeekActivityTest" --console=plain`
Expected: FAIL — `NoSuchFieldException: isDialogShowing` (Feld existiert noch nicht)

- [ ] **Step 3: SwipeInterceptLayout.kt erstellen**

Erstelle `android/app/src/main/java/de/fs/timeplan/grid/SwipeInterceptLayout.kt`:

```kotlin
package de.fs.timeplan.grid

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import de.fs.timeplan.week.SwipeDirection
import de.fs.timeplan.week.WeekSwipeGesture
import kotlin.math.abs

class SwipeInterceptLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onSwipe: ((SwipeDirection) -> Unit)? = null
    var isGestureEnabled: () -> Boolean = { true }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minDistancePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 60f, resources.displayMetrics
    )
    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L
    private var intercepting = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isGestureEnabled()) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                startTime = System.currentTimeMillis()
                intercepting = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - startX
                val dy = ev.y - startY
                if (!intercepting && abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f) {
                    intercepting = true
                }
            }
        }
        return intercepting
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - startX
                val dy = ev.y - startY
                val duration = System.currentTimeMillis() - startTime
                WeekSwipeGesture.detect(dx, dy, duration, minDistancePx)?.let { onSwipe?.invoke(it) }
                intercepting = false
            }
            MotionEvent.ACTION_CANCEL -> intercepting = false
        }
        return true
    }
}
```

- [ ] **Step 4: activity_week.xml umstrukturieren**

Aktueller Inhalt von `android/app/src/main/res/layout/activity_week.xml` (der Teil ab dem Tages-Kopfzeilen-Block bis zum Ende, der obere Button-Balken bleibt unverändert):

```xml
    <LinearLayout
        android:id="@+id/dayHeaderRow"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:background="@drawable/bg_header_row"
        android:paddingTop="10dp"
        android:paddingBottom="10dp">

        <TextView
            android:layout_width="140dp"
            android:layout_height="wrap_content"
            android:paddingStart="10dp"
            android:paddingEnd="8dp"
            android:text="@string/column_header_worker"
            android:textColor="@color/ink_muted"
            android:textSize="12sp"
            android:textStyle="bold"
            android:letterSpacing="0.04" />

        <LinearLayout
            android:id="@+id/dayHeaderCells"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:orientation="horizontal" />

    </LinearLayout>

    <TextView
        android:id="@+id/errorLabel"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="24dp"
        android:gravity="center"
        android:textColor="@color/ink_muted"
        android:textSize="16sp"
        android:visibility="gone" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/weekRecyclerView"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:background="@color/paper_bg"
        android:clipToPadding="false"
        android:paddingBottom="16dp" />

</LinearLayout>
```

Ersetze diesen Teil durch (dayHeaderRow, errorLabel und die RecyclerView wandern
unverändert in einen neuen `SwipeInterceptLayout`-Wrapper, der den verbleibenden
Platz unter dem oberen Button-Balken füllt):

```xml
    <de.fs.timeplan.grid.SwipeInterceptLayout
        android:id="@+id/weekContentContainer"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:orientation="vertical">

        <LinearLayout
            android:id="@+id/dayHeaderRow"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:background="@drawable/bg_header_row"
            android:paddingTop="10dp"
            android:paddingBottom="10dp">

            <TextView
                android:layout_width="140dp"
                android:layout_height="wrap_content"
                android:paddingStart="10dp"
                android:paddingEnd="8dp"
                android:text="@string/column_header_worker"
                android:textColor="@color/ink_muted"
                android:textSize="12sp"
                android:textStyle="bold"
                android:letterSpacing="0.04" />

            <LinearLayout
                android:id="@+id/dayHeaderCells"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:orientation="horizontal" />

        </LinearLayout>

        <TextView
            android:id="@+id/errorLabel"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:padding="24dp"
            android:gravity="center"
            android:textColor="@color/ink_muted"
            android:textSize="16sp"
            android:visibility="gone" />

        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/weekRecyclerView"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:background="@color/paper_bg"
            android:clipToPadding="false"
            android:paddingBottom="16dp" />

    </de.fs.timeplan.grid.SwipeInterceptLayout>

</LinearLayout>
```

- [ ] **Step 5: WeekActivity.kt verkabeln**

In `android/app/src/main/java/de/fs/timeplan/WeekActivity.kt` steht am Anfang der Klasse:

```kotlin
class WeekActivity : AppCompatActivity() {

    private lateinit var configRepository: ConfigRepository
    private lateinit var weekLabel: TextView
    private lateinit var demoBadge: TextView
    private lateinit var errorLabel: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var dayHeaderCells: LinearLayout
    private val adapter = WeekGridAdapter()
    private var currentWeekId: String = WeekId.currentWeekId()
    private var isDemoMode: Boolean = false
    private var currentDates: List<String> = emptyList()
```

Ändere das zu:

```kotlin
class WeekActivity : AppCompatActivity() {

    private lateinit var configRepository: ConfigRepository
    private lateinit var weekLabel: TextView
    private lateinit var demoBadge: TextView
    private lateinit var errorLabel: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var dayHeaderCells: LinearLayout
    private lateinit var weekContentContainer: de.fs.timeplan.grid.SwipeInterceptLayout
    private val adapter = WeekGridAdapter()
    private var currentWeekId: String = WeekId.currentWeekId()
    private var isDemoMode: Boolean = false
    private var currentDates: List<String> = emptyList()
    private var isDialogShowing: Boolean = false
```

In `onCreate` steht:

```kotlin
        weekLabel = findViewById(R.id.weekLabel)
        demoBadge = findViewById(R.id.demoBadge)
        errorLabel = findViewById(R.id.errorLabel)
        dayHeaderCells = findViewById(R.id.dayHeaderCells)
        recyclerView = findViewById(R.id.weekRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
```

Ändere das zu:

```kotlin
        weekLabel = findViewById(R.id.weekLabel)
        demoBadge = findViewById(R.id.demoBadge)
        errorLabel = findViewById(R.id.errorLabel)
        dayHeaderCells = findViewById(R.id.dayHeaderCells)
        weekContentContainer = findViewById(R.id.weekContentContainer)
        weekContentContainer.isGestureEnabled = { !isDialogShowing }
        weekContentContainer.onSwipe = { direction -> onSwipeDetected(direction) }
        recyclerView = findViewById(R.id.weekRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
```

Füge eine neue Methode hinzu (z.B. direkt nach `renderDayHeader`):

```kotlin
    private fun onSwipeDetected(direction: de.fs.timeplan.week.SwipeDirection) {
        val width = weekContentContainer.width.toFloat()
        val outTranslation = if (direction == de.fs.timeplan.week.SwipeDirection.NEXT) -width else width
        weekContentContainer.animate()
            .translationX(outTranslation)
            .setDuration(200)
            .withEndAction {
                weekContentContainer.translationX = -outTranslation
                currentWeekId = WeekId.adjacentWeekId(
                    currentWeekId,
                    if (direction == de.fs.timeplan.week.SwipeDirection.NEXT) 1 else -1
                )
                loadCurrentWeek()
                weekContentContainer.animate().translationX(0f).setDuration(200).start()
            }
            .start()
    }
```

In `showMonteurEditor` steht:

```kotlin
    private fun showMonteurEditor(worker: Worker, cellId: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_cell_edit, null)
        val textField = dialogView.findViewById<EditText>(R.id.fieldEntryText)
        textField.setText(DemoApi.textFor(cellId).orEmpty())

        MaterialAlertDialogBuilder(this)
            .setBackground(ContextCompat.getDrawable(this, R.drawable.bg_dialog_paper))
            .setTitle(worker.displayName)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                DemoApi.putEntry(cellId, textField.text.toString())
                loadCurrentWeek()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
```

Ändere das zu:

```kotlin
    private fun showMonteurEditor(worker: Worker, cellId: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_cell_edit, null)
        val textField = dialogView.findViewById<EditText>(R.id.fieldEntryText)
        textField.setText(DemoApi.textFor(cellId).orEmpty())

        isDialogShowing = true
        val dialog = MaterialAlertDialogBuilder(this)
            .setBackground(ContextCompat.getDrawable(this, R.drawable.bg_dialog_paper))
            .setTitle(worker.displayName)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                DemoApi.putEntry(cellId, textField.text.toString())
                loadCurrentWeek()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.setOnDismissListener { isDialogShowing = false }
    }
```

In `showAzubiPicker` steht:

```kotlin
        val dialog = MaterialAlertDialogBuilder(this)
            .setBackground(ContextCompat.getDrawable(this, R.drawable.bg_dialog_paper))
            .setTitle(worker.displayName)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun apply(value: String) {
```

Ändere das zu:

```kotlin
        isDialogShowing = true
        val dialog = MaterialAlertDialogBuilder(this)
            .setBackground(ContextCompat.getDrawable(this, R.drawable.bg_dialog_paper))
            .setTitle(worker.displayName)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnDismissListener { isDialogShowing = false }

        fun apply(value: String) {
```

- [ ] **Step 6: Test laufen lassen, sicherstellen dass er besteht**

Run: `JAVA_HOME="$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)" ./gradlew testDebugUnitTest --tests "de.fs.timeplan.week.WeekActivityTest" --console=plain`
Expected: PASS, alle Tests in dieser Datei grün (inkl. des neuen)

- [ ] **Step 7: Volle Android-Suite + Build laufen lassen**

Run: `JAVA_HOME="$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)" ./gradlew testDebugUnitTest assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL, alle Tests grün

- [ ] **Step 8: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add android/app/src/main/java/de/fs/timeplan/grid/SwipeInterceptLayout.kt android/app/src/main/res/layout/activity_week.xml android/app/src/main/java/de/fs/timeplan/WeekActivity.kt android/app/src/test/java/de/fs/timeplan/week/WeekActivityTest.kt
git commit -m "android: SwipeInterceptLayout + Wisch-Navigation in WeekActivity verkabelt"
```

---

## Nach Abschluss aller Tasks (manuelle Verifikation)

Automatisierte Tests decken die Gesten-Mathematik (Android) und die
Markup-Auslieferung (WebUI) ab, aber nicht das tatsächliche Wisch-Gefühl im
Browser/auf dem Tablet. Manuell zu prüfen:

1. **WebUI (Emulator/Tablet-Browser):** Auf der Wochenraster-Seite nach
   links wischen → nächste Woche lädt, Übergang gleitet sichtbar. Nach
   rechts wischen → vorherige Woche. Vertikales Scrollen durch die
   Monteur-Liste funktioniert weiterhin normal, löst keinen Wochenwechsel
   aus. Bei offenem Azubi-Picker-Dialog löst Wischen über dem Dialog
   keinen Wochenwechsel aus.
   **Bekanntes Risiko:** Die *Richtung* der Slide-Animation hängt von
   Details der Cross-Document-View-Transitions-Implementierung im
   jeweiligen Chrome ab, die nicht in allen Versionen identisch
   spezifiziert ist. Funktioniert die Kernnavigation (Wischen wechselt
   die Woche), aber die Animation läuft nur als einfaches
   Raus-/Reinblenden statt gerichtet zu gleiten, ist das laut Spec
   ausdrücklich akzeptabel ("kein Fallback-Code nötig") — kein Blocker,
   kein separater Task nötig, nur bei der Verifikation vermerken.
2. **Android (Emulator/Tablet):** Auf der Wochenraster-Seite nach links/
   rechts wischen → Wochenwechsel mit Rutsch-Animation. Normales
   Hoch-/Runterscrollen der Monteur-Liste bleibt unverändert nutzbar.
   Bei offenem Zell-Dialog (Demo-Modus) löst Wischen keinen Wochenwechsel
   aus.

## Danach

Feature-Branch erstellen (`feature/week-swipe-navigation`), alle drei Tasks
committen (bereits während der Tasks passiert), finale Whole-Branch-Review
dispatchen, Findings beheben, mergen nach `main` — wie bei den vorherigen
Phasen (`superpowers:finishing-a-development-branch`-Muster).
