# Android Demo-Modus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein Demo-Modus für die TimePlan-Android-App, der ohne laufenden Server funktioniert — mit 10 erfundenen Monteuren und 10 erfundenen Azubis, lokal editierbaren Text-Einträgen und einem sichtbaren „DEMO"-Badge, damit das App-Design auf einem beliebigen Testgerät beurteilt werden kann.

**Architecture:** `DemoApi` ist ein neues, rein lokales Kotlin-Singleton, das exakt das bestehende `TimePlanApi`-Interface aus Meilenstein 3 implementiert — `WeekPresenter`, `WeekGridBuilder` und `WeekGridAdapter` bleiben dadurch unverändert in ihrer Kernlogik. `WeekActivity` wählt beim Start zwischen `DemoApi` (keine Konfiguration gespeichert) und dem echten `TimePlanApiClient` (Konfiguration vorhanden). Eine neue Klick-Fähigkeit im `WeekGridAdapter` (optionaler Callback, Default `null`) öffnet im Demo-Modus einen einfachen Text-Dialog pro Zelle.

**Tech Stack:** Kotlin, dieselbe Android-Toolchain wie Meilenstein 3 (Gradle 8.9, AGP 8.5.2, JBR aus dem Android-Studio-Flatpak), JUnit4 + Robolectric für alle Tests — kein Server, kein Gerät, kein Emulator nötig.

## Global Constraints

- Paketname bleibt `de.fs.timeplan`; alle neuen Dateien fügen sich in die bestehende Schichtung `model`/`net`/`config`/`grid`/`settings`/`week` ein
- **10 Monteure, 10 Azubis** (nicht 12 — folgt der bestehenden, serverseitig erzwungenen Grenze)
- `DemoApi` ist ein Kotlin-`object` (Singleton) — dadurch überlebt der Zustand eine Activity-Neuerstellung (z. B. Rotation) innerhalb desselben App-Prozesses, setzt sich aber bei jedem echten App-Neustart (neuer Prozess) zurück. **Jeder Test, der `DemoApi`-Zustand berührt, MUSS in einem `@Before`-Setup `DemoApi.reset()` aufrufen** — sonst leckt Zustand zwischen Testfällen (Singleton-Objekte sind JVM-weit, Gradle kann Testklassen im selben Prozess ausführen)
- Tap-to-Edit ist **ausschließlich im Demo-Modus aktiv**; der vernetzte (echte) Modus bleibt unverändert nur lesend
- Vor jedem Gradle-Aufruf:
  ```bash
  export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
  ```
- Tests: `cd /home/admin/Projekte/TimePlan/android && ./gradlew :app:testDebugUnitTest --console=plain`
- Alle Pfadangaben relativ zu `/home/admin/Projekte/TimePlan/`

---

### Task 1: DemoApi (Seed-Daten + lokale TimePlanApi-Implementierung)

**Files:**
- Create: `android/app/src/main/java/de/fs/timeplan/net/DemoApi.kt`
- Create: `android/app/src/test/java/de/fs/timeplan/net/DemoApiTest.kt`
- Modify: `android/app/src/test/java/de/fs/timeplan/week/WeekPresenterTest.kt`

**Interfaces:**
- Consumes: `de.fs.timeplan.net.{TimePlanApi,ApiResult,WeekId}` (bestehend), `de.fs.timeplan.model.{Worker,Week,Entry,WorkersResponse,WeekBundle,StatusResponse,textOrNull}` (bestehend)
- Produces: `object DemoApi : TimePlanApi` mit `getStatus()/getWorkers()/getWeek(weekId)` (Interface-Implementierung), zusätzlich `fun putEntry(cellId: String, text: String)` (leerer/blank Text löscht den Entry), `fun textFor(cellId: String): String?`, `fun reset()` (setzt alle Entries zurück, nur für Tests). Von Task 3 (WeekActivity) direkt als konkreter Typ verwendet (nicht nur über das Interface), da `putEntry`/`textFor` kein Teil von `TimePlanApi` sind.

- [ ] **Step 1: Failing Tests schreiben**

`android/app/src/test/java/de/fs/timeplan/net/DemoApiTest.kt`:
```kotlin
package de.fs.timeplan.net

import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class DemoApiTest {

    @Before
    fun setUp() {
        DemoApi.reset()
    }

    @Test
    fun `seeds ten monteure and ten azubis`() {
        val result = DemoApi.getWorkers()
        require(result is ApiResult.Success)
        val workers = result.data.workers
        assertEquals(10, workers.count { it.isMonteur })
        assertEquals(10, workers.count { it.isAzubi })
        assertTrue(workers.all { it.active })
    }

    @Test
    fun `getWeek returns only entries for the requested week`() {
        val monteurId = (DemoApi.getWorkers() as ApiResult.Success).data.workers.first { it.isMonteur }.id
        val cellThisWeek = WeekId.makeCellId("2027-W10", monteurId, "2027-03-08")
        val cellOtherWeek = WeekId.makeCellId("2027-W11", monteurId, "2027-03-15")
        DemoApi.putEntry(cellThisWeek, "Baustelle A")
        DemoApi.putEntry(cellOtherWeek, "Baustelle B")

        val result = DemoApi.getWeek("2027-W10")
        require(result is ApiResult.Success)
        assertEquals(1, result.data.entries.size)
        assertEquals(cellThisWeek, result.data.entries.first().cell_id)
    }

    @Test
    fun `putEntry with blank text removes the entry`() {
        val monteurId = (DemoApi.getWorkers() as ApiResult.Success).data.workers.first { it.isMonteur }.id
        val cellId = WeekId.makeCellId("2027-W20", monteurId, "2027-05-17")
        DemoApi.putEntry(cellId, "Notiz")
        assertEquals("Notiz", DemoApi.textFor(cellId))

        DemoApi.putEntry(cellId, "   ")
        assertNull(DemoApi.textFor(cellId))
    }

    @Test
    fun `putEntry replaces existing text and bumps revision`() {
        val monteurId = (DemoApi.getWorkers() as ApiResult.Success).data.workers.first { it.isMonteur }.id
        val cellId = WeekId.makeCellId("2027-W22", monteurId, "2027-05-31")
        DemoApi.putEntry(cellId, "Erste Version")
        val firstRevision = (DemoApi.getWeek("2027-W22") as ApiResult.Success).data.entries.first().revision

        DemoApi.putEntry(cellId, "Zweite Version")
        assertEquals("Zweite Version", DemoApi.textFor(cellId))
        val updatedRevision = (DemoApi.getWeek("2027-W22") as ApiResult.Success).data.entries.first().revision
        assertTrue(updatedRevision > firstRevision)
    }
}
```

- [ ] **Step 2: Test rot laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: FAIL (`DemoApi` existiert noch nicht).

- [ ] **Step 3: Implementierung**

`android/app/src/main/java/de/fs/timeplan/net/DemoApi.kt`:
```kotlin
package de.fs.timeplan.net

import de.fs.timeplan.model.Entry
import de.fs.timeplan.model.StatusResponse
import de.fs.timeplan.model.Week
import de.fs.timeplan.model.WeekBundle
import de.fs.timeplan.model.Worker
import de.fs.timeplan.model.WorkersResponse
import de.fs.timeplan.model.textOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val DEMO_MONTEURE = listOf(
    "101" to "Wagner", "102" to "Krüger", "103" to "Zimmermann", "104" to "Neumann",
    "105" to "Fuchs", "106" to "Braun", "107" to "Krause", "108" to "Meyer",
    "109" to "Lehmann", "110" to "Schwarz"
)

private val DEMO_AZUBIS = listOf(
    "501" to "Winkler", "502" to "Herrmann", "503" to "König", "504" to "Vogt",
    "505" to "Stein", "506" to "Busch", "507" to "Kramer", "508" to "Voss",
    "509" to "Engel", "510" to "Pauli"
)

object DemoApi : TimePlanApi {

    private val seedWorkers: List<Worker> = buildList {
        DEMO_MONTEURE.forEachIndexed { index, (number, name) ->
            add(Worker("demo-m-$number", number, name, "monteur", index + 1, true, 1))
        }
        DEMO_AZUBIS.forEachIndexed { index, (number, name) ->
            add(Worker("demo-a-$number", number, name, "azubi", index + 1, true, 1))
        }
    }

    private val entries = mutableMapOf<String, Entry>()
    private var nextEntryId = 1

    override fun getStatus(): ApiResult<StatusResponse> = ApiResult.Success(StatusResponse("ok", 0))

    override fun getWorkers(): ApiResult<WorkersResponse> = ApiResult.Success(WorkersResponse(seedWorkers))

    override fun getWeek(weekId: String): ApiResult<WeekBundle> {
        val dates = WeekId.weekDates(weekId).map { it.toString() }
        val weekEntries = entries.values.filter { it.cell_id.startsWith("${weekId}_") }
        return ApiResult.Success(WeekBundle(Week(weekId, "OPEN", 0), dates, weekEntries))
    }

    fun textFor(cellId: String): String? = entries[cellId]?.textOrNull()

    fun putEntry(cellId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            entries.remove(cellId)
            return
        }
        val previous = entries[cellId]
        entries[cellId] = Entry(
            id = previous?.id ?: "demo-entry-${nextEntryId++}",
            cell_id = cellId,
            type = "text",
            author_type = "tablet",
            author_id = "demo",
            content = buildJsonObject { put("text", trimmed) },
            conflict_of = null,
            created_at = previous?.created_at ?: "demo",
            updated_at = "demo",
            revision = (previous?.revision ?: 0) + 1
        )
    }

    fun reset() {
        entries.clear()
        nextEntryId = 1
    }
}
```

In `android/app/src/test/java/de/fs/timeplan/week/WeekPresenterTest.kt` den Import-Block ergänzen (nach der bestehenden letzten Import-Zeile `import org.junit.Assert.assertTrue`):
```kotlin
import de.fs.timeplan.net.DemoApi
```
Und am Ende der Klasse `WeekPresenterTest` (vor der schließenden `}` der Klasse) diese neue Testmethode einfügen:
```kotlin

    @Test
    fun `works end-to-end with DemoApi`() {
        DemoApi.reset()
        val result = WeekPresenter(DemoApi).loadWeek("2027-W30")
        require(result is WeekLoadResult.Success)
        assertEquals(21, result.rows.size)
    }
```
(21 = 10 Monteur-Zeilen + 1 Separator + 10 Azubi-Zeilen.)

- [ ] **Step 4: Test grün laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: PASS (4 neue DemoApiTest + 1 neue WeekPresenterTest + 26 bestehende = 31 Tests).

- [ ] **Step 5: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add android/app/src/main/java/de/fs/timeplan/net/DemoApi.kt \
  android/app/src/test/java/de/fs/timeplan/net/DemoApiTest.kt \
  android/app/src/test/java/de/fs/timeplan/week/WeekPresenterTest.kt
git commit -m "feat(android): DemoApi mit 10 Monteuren/10 Azubis als lokale TimePlanApi-Implementierung"
```

---

### Task 2: WeekGridAdapter – klickbare Zellen (optionaler Callback)

**Files:**
- Modify: `android/app/src/main/java/de/fs/timeplan/grid/WeekGridAdapter.kt`
- Modify: `android/app/src/test/java/de/fs/timeplan/grid/WeekGridAdapterTest.kt`

**Interfaces:**
- Consumes: `de.fs.timeplan.grid.WeekRow` (bestehend, unverändert)
- Produces: `WeekGridAdapter` bekommt ein neues mutables Feld `var onCellClick: ((workerId: String, dateIndex: Int) -> Unit)? = null`. Default `null` erhält das bestehende, rein lesende Verhalten unverändert (bestehender Test bleibt ohne Anpassung grün). Von Task 3 (WeekActivity) gesetzt.

- [ ] **Step 1: Failing Test schreiben**

In `android/app/src/test/java/de/fs/timeplan/grid/WeekGridAdapterTest.kt` den Import-Block ergänzen (nach `import android.widget.TextView`):
```kotlin
import android.widget.LinearLayout
```
Und am Ende der Klasse `WeekGridAdapterTest` (vor der schließenden `}`) diese neue Testmethode einfügen:
```kotlin

    @Test
    fun `invokes onCellClick with workerId and date index when a cell is tapped`() {
        val rows = listOf(
            WeekRow.Monteur("w-1", "144 Albrecht", listOf(null, null, null, null, null, null, null))
        )
        val adapter = WeekGridAdapter(rows)
        var clicked: Pair<String, Int>? = null
        adapter.onCellClick = { workerId, dateIndex -> clicked = workerId to dateIndex }

        val parent = FrameLayout(ApplicationProvider.getApplicationContext())
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0)) as WeekGridAdapter.WorkerRowViewHolder
        adapter.onBindViewHolder(holder, 0)

        val cellsContainer = holder.itemView.findViewById<LinearLayout>(R.id.rowCellsContainer)
        cellsContainer.getChildAt(3).performClick()

        assertEquals("w-1" to 3, clicked)
    }
```

- [ ] **Step 2: Test rot laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: FAIL (`onCellClick` existiert noch nicht auf `WeekGridAdapter`).

- [ ] **Step 3: Implementierung**

`android/app/src/main/java/de/fs/timeplan/grid/WeekGridAdapter.kt` komplett ersetzen durch:
```kotlin
package de.fs.timeplan.grid

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import de.fs.timeplan.R

private const val VIEW_TYPE_WORKER = 0
private const val VIEW_TYPE_SEPARATOR = 1

class WeekGridAdapter(private var rows: List<WeekRow> = emptyList()) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var onCellClick: ((workerId: String, dateIndex: Int) -> Unit)? = null

    fun submitRows(newRows: List<WeekRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is WeekRow.Separator -> VIEW_TYPE_SEPARATOR
        else -> VIEW_TYPE_WORKER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_SEPARATOR) {
            SeparatorViewHolder(inflater.inflate(R.layout.row_week_separator, parent, false))
        } else {
            WorkerRowViewHolder(inflater.inflate(R.layout.row_week_worker, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is WeekRow.Monteur -> (holder as WorkerRowViewHolder)
                .bind(row.workerId, row.displayName, row.cellTexts, compact = false, onCellClick)
            is WeekRow.Azubi -> (holder as WorkerRowViewHolder)
                .bind(row.workerId, row.displayName, row.cellTexts, compact = true, onCellClick)
            WeekRow.Separator -> Unit
        }
    }

    class SeparatorViewHolder(view: View) : RecyclerView.ViewHolder(view)

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
            cellTexts.forEachIndexed { index, text ->
                val cell = TextView(context).apply {
                    this.text = text.orEmpty()
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    maxLines = if (compact) 1 else 4
                    setPadding(8, 8, 8, 8)
                    if (onCellClick != null) {
                        isClickable = true
                        setOnClickListener { onCellClick(workerId, index) }
                    }
                }
                cellsContainer.addView(cell)
            }
        }
    }
}
```

- [ ] **Step 4: Test grün laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: PASS (1 neuer Test + 31 bestehende = 32 Tests). Der bestehende Test
`renders monteur rows, separator, and azubi rows` bleibt unverändert grün.

- [ ] **Step 5: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add android/app/src/main/java/de/fs/timeplan/grid/WeekGridAdapter.kt \
  android/app/src/test/java/de/fs/timeplan/grid/WeekGridAdapterTest.kt
git commit -m "feat(android): WeekGridAdapter unterstützt optionalen Zell-Klick-Callback"
```

---

### Task 3: WeekActivity – Demo-Modus, Badge und Zellbearbeitungs-Dialog

**Files:**
- Modify: `android/app/src/main/java/de/fs/timeplan/WeekActivity.kt`
- Modify: `android/app/src/main/res/layout/activity_week.xml`
- Create: `android/app/src/main/res/layout/dialog_cell_edit.xml`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/test/java/de/fs/timeplan/week/WeekActivityTest.kt`

**Interfaces:**
- Consumes: `de.fs.timeplan.net.DemoApi` (Task 1: `getWorkers()`, `putEntry`, `textFor`), `de.fs.timeplan.grid.WeekGridAdapter.onCellClick` (Task 2), `de.fs.timeplan.net.{ApiResult,TimePlanApi,TimePlanApiClient,WeekId}` (bestehend), `de.fs.timeplan.week.{WeekPresenter,WeekLoadResult}` (bestehend), `de.fs.timeplan.config.ConfigRepository` (bestehend)
- Produces: `WeekActivity` startet nicht mehr automatisch `SettingsActivity`, wenn keine Konfiguration vorliegt — stattdessen läuft sie im Demo-Modus weiter (Badge sichtbar, Zellen antippbar).

- [ ] **Step 1: Failing Tests schreiben**

`android/app/src/test/java/de/fs/timeplan/week/WeekActivityTest.kt` komplett ersetzen durch:
```kotlin
package de.fs.timeplan.week

import android.view.View
import android.widget.TextView
import de.fs.timeplan.R
import de.fs.timeplan.WeekActivity
import de.fs.timeplan.net.DemoApi
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

@RunWith(RobolectricTestRunner::class)
class WeekActivityTest {

    @Before
    fun setUp() {
        DemoApi.reset()
    }

    @Test
    fun `does not redirect to settings when unconfigured`() {
        val controller = Robolectric.buildActivity(WeekActivity::class.java)
        controller.setup()
        val activity = controller.get()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `shows demo badge when unconfigured`() {
        val controller = Robolectric.buildActivity(WeekActivity::class.java)
        controller.setup()
        val activity = controller.get()
        assertEquals(View.VISIBLE, activity.findViewById<TextView>(R.id.demoBadge).visibility)
    }
}
```

- [ ] **Step 2: Test rot laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: FAIL (`R.id.demoBadge` existiert noch nicht; alter Redirect-Test ist bereits ersetzt, neuer Test erwartet KEINEN Redirect, aktueller Code redirected noch → `does not redirect to settings when unconfigured` schlägt fehl).

- [ ] **Step 3: Implementierung**

In `android/app/src/main/res/values/strings.xml` zwei neue Zeilen ergänzen (innerhalb `<resources>`, nach `<string name="settings">Einstellungen</string>`):
```xml
    <string name="demo_badge">DEMO</string>
    <string name="cancel">Abbrechen</string>
```

`android/app/src/main/res/layout/activity_week.xml`: die `<TextView android:id="@+id/weekLabel" .../>` bleibt unverändert; direkt danach (vor dem `<Button android:id="@+id/buttonNextWeek" .../>`) folgende neue `TextView` einfügen:
```xml
        <TextView
            android:id="@+id/demoBadge"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginEnd="8dp"
            android:text="@string/demo_badge"
            android:textColor="#FFFFFF"
            android:textStyle="bold"
            android:background="#DC2626"
            android:paddingStart="8dp"
            android:paddingEnd="8dp"
            android:paddingTop="2dp"
            android:paddingBottom="2dp"
            android:visibility="gone" />
```

`android/app/src/main/res/layout/dialog_cell_edit.xml` (neu):
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="16dp">

    <Spinner
        android:id="@+id/spinnerMonteurQuickpick"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:visibility="gone" />

    <EditText
        android:id="@+id/fieldEntryText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:minLines="3"
        android:gravity="top"
        android:inputType="textMultiLine" />

</LinearLayout>
```

`android/app/src/main/java/de/fs/timeplan/WeekActivity.kt` komplett ersetzen durch:
```kotlin
package de.fs.timeplan

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.fs.timeplan.config.ConfigRepository
import de.fs.timeplan.grid.WeekGridAdapter
import de.fs.timeplan.net.ApiResult
import de.fs.timeplan.net.DemoApi
import de.fs.timeplan.net.TimePlanApi
import de.fs.timeplan.net.TimePlanApiClient
import de.fs.timeplan.net.WeekId
import de.fs.timeplan.settings.SettingsActivity
import de.fs.timeplan.week.WeekLoadResult
import de.fs.timeplan.week.WeekPresenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WeekActivity : AppCompatActivity() {

    private lateinit var configRepository: ConfigRepository
    private lateinit var weekLabel: TextView
    private lateinit var demoBadge: TextView
    private lateinit var errorLabel: TextView
    private lateinit var recyclerView: RecyclerView
    private val adapter = WeekGridAdapter()
    private var currentWeekId: String = WeekId.currentWeekId()
    private var isDemoMode: Boolean = false
    private var currentDates: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_week)
        configRepository = ConfigRepository(applicationContext)

        weekLabel = findViewById(R.id.weekLabel)
        demoBadge = findViewById(R.id.demoBadge)
        errorLabel = findViewById(R.id.errorLabel)
        recyclerView = findViewById(R.id.weekRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<View>(R.id.buttonPrevWeek).setOnClickListener {
            currentWeekId = WeekId.adjacentWeekId(currentWeekId, -1)
            loadCurrentWeek()
        }
        findViewById<View>(R.id.buttonNextWeek).setOnClickListener {
            currentWeekId = WeekId.adjacentWeekId(currentWeekId, 1)
            loadCurrentWeek()
        }
        findViewById<View>(R.id.buttonToday).setOnClickListener {
            currentWeekId = WeekId.currentWeekId()
            loadCurrentWeek()
        }
        findViewById<View>(R.id.buttonSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        isDemoMode = configRepository.load() == null
        demoBadge.visibility = if (isDemoMode) View.VISIBLE else View.GONE
        adapter.onCellClick = if (isDemoMode) {
            { workerId, dateIndex -> onDemoCellClick(workerId, dateIndex) }
        } else {
            null
        }
        loadCurrentWeek()
    }

    private fun currentApi(): TimePlanApi =
        if (isDemoMode) DemoApi else TimePlanApiClient(configRepository.load()!!)

    private fun loadCurrentWeek() {
        weekLabel.text = currentWeekId
        errorLabel.visibility = View.GONE
        val api = currentApi()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                WeekPresenter(api).loadWeek(currentWeekId)
            }
            render(result)
        }
    }

    private fun render(result: WeekLoadResult) {
        when (result) {
            is WeekLoadResult.Success -> {
                currentDates = result.dates
                errorLabel.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                adapter.submitRows(result.rows)
            }
            is WeekLoadResult.Failure -> {
                recyclerView.visibility = View.GONE
                errorLabel.visibility = View.VISIBLE
                errorLabel.text = result.message
            }
        }
    }

    private fun onDemoCellClick(workerId: String, dateIndex: Int) {
        val dateIso = currentDates.getOrNull(dateIndex) ?: return
        val workersResult = DemoApi.getWorkers()
        val workers = (workersResult as? ApiResult.Success)?.data?.workers.orEmpty()
        val worker = workers.firstOrNull { it.id == workerId } ?: return
        val cellId = WeekId.makeCellId(currentWeekId, workerId, dateIso)

        val dialogView = layoutInflater.inflate(R.layout.dialog_cell_edit, null)
        val textField = dialogView.findViewById<EditText>(R.id.fieldEntryText)
        val quickpick = dialogView.findViewById<Spinner>(R.id.spinnerMonteurQuickpick)
        textField.setText(DemoApi.textFor(cellId).orEmpty())

        if (worker.isAzubi) {
            val monteurNames = workers.filter { it.isMonteur && it.active }.map { it.displayName }
            quickpick.visibility = View.VISIBLE
            quickpick.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, monteurNames)
            quickpick.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    textField.setText(monteurNames[position])
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        } else {
            quickpick.visibility = View.GONE
        }

        AlertDialog.Builder(this)
            .setTitle(worker.displayName)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                DemoApi.putEntry(cellId, textField.text.toString())
                loadCurrentWeek()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
```

- [ ] **Step 4: Test grün laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: PASS (2 Tests in `WeekActivityTest` + 32 bestehende = 34 Tests).

- [ ] **Step 5: Build verifizieren**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:assembleDebug --console=plain
```
Erwartet: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add android/app/src/main/java/de/fs/timeplan/WeekActivity.kt \
  android/app/src/main/res/layout/activity_week.xml \
  android/app/src/main/res/layout/dialog_cell_edit.xml \
  android/app/src/main/res/values/strings.xml \
  android/app/src/test/java/de/fs/timeplan/week/WeekActivityTest.kt
git commit -m "feat(android): Demo-Modus in WeekActivity mit Badge und Zellbearbeitungs-Dialog"
```

---

### Task 4: SettingsActivity – „Konfiguration löschen"

**Files:**
- Modify: `android/app/src/main/java/de/fs/timeplan/settings/SettingsActivity.kt`
- Modify: `android/app/src/main/res/layout/activity_settings.xml`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/test/java/de/fs/timeplan/settings/SettingsActivityTest.kt`

**Interfaces:**
- Consumes: `de.fs.timeplan.config.ConfigRepository.clear()` (bestehend, aus Meilenstein 3)
- Produces: neuer Button in `SettingsActivity`, der die gespeicherte Konfiguration löscht und die Activity beendet — `WeekActivity` erkennt beim nächsten `onResume()` die fehlende Konfiguration und zeigt automatisch den Demo-Modus (Task 3).

- [ ] **Step 1: Failing Test schreiben**

In `android/app/src/test/java/de/fs/timeplan/settings/SettingsActivityTest.kt` am Ende der Klasse `SettingsActivityTest` (vor der schließenden `}`) diese neue Testmethode einfügen:
```kotlin

    @Test
    fun `clearing config removes saved config and finishes activity`() {
        ConfigRepository(ApplicationProvider.getApplicationContext())
            .save(ServerConfig("http://x:8000", "tablet-09", "tok"))
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()

        activity.findViewById<Button>(R.id.buttonClearConfig).performClick()

        val saved = ConfigRepository(ApplicationProvider.getApplicationContext()).load()
        assertEquals(null, saved)
        assertTrue(activity.isFinishing)
    }
```

- [ ] **Step 2: Test rot laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: FAIL (`R.id.buttonClearConfig` existiert noch nicht).

- [ ] **Step 3: Implementierung**

In `android/app/src/main/res/values/strings.xml` eine neue Zeile ergänzen (innerhalb `<resources>`, nach `<string name="cancel">Abbrechen</string>`):
```xml
    <string name="clear_config">Konfiguration löschen</string>
```

In `android/app/src/main/res/layout/activity_settings.xml` nach dem bestehenden `<Button android:id="@+id/buttonSave" .../>` (vor dem schließenden `</LinearLayout>`) folgenden neuen Button einfügen:
```xml
    <Button
        android:id="@+id/buttonClearConfig"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="end"
        android:text="@string/clear_config" />
```

In `android/app/src/main/java/de/fs/timeplan/settings/SettingsActivity.kt` nach der bestehenden Zeile
`findViewById<Button>(R.id.buttonSave).setOnClickListener { onSave() }` (innerhalb von `onCreate`, vor der schließenden `}` der Methode) folgendes ergänzen:
```kotlin

        findViewById<Button>(R.id.buttonClearConfig).setOnClickListener {
            configRepository.clear()
            finish()
        }
```

- [ ] **Step 4: Test grün laufen lassen**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew :app:testDebugUnitTest --console=plain
```
Erwartet: PASS (1 neuer Test + 34 bestehende = 35 Tests).

- [ ] **Step 5: Finaler Build-Check**

```bash
export JAVA_HOME=$(find /var/lib/flatpak/app/com.google.AndroidStudio -maxdepth 6 -type d -iname jbr | head -1)
cd /home/admin/Projekte/TimePlan/android
./gradlew clean :app:testDebugUnitTest :app:assembleDebug --console=plain
```
Erwartet: `BUILD SUCCESSFUL`, alle 35 Tests grün.

- [ ] **Step 6: Commit**

```bash
cd /home/admin/Projekte/TimePlan
git add android/app/src/main/java/de/fs/timeplan/settings/SettingsActivity.kt \
  android/app/src/main/res/layout/activity_settings.xml \
  android/app/src/main/res/values/strings.xml \
  android/app/src/test/java/de/fs/timeplan/settings/SettingsActivityTest.kt
git commit -m "feat(android): Button 'Konfiguration löschen' führt zurück in den Demo-Modus"
```

---

## Nach diesem Plan

Der Demo-Modus ist einsatzbereit: App auf einem beliebigen Tablet installieren,
nichts konfigurieren → Wochenraster mit 10 Monteuren + 10 Azubis, Zellen
antippbar, Texteingaben bleiben für die laufende Sitzung erhalten. Damit ist
die Grundlage für die anstehende Design-Überarbeitung (mit `impeccable`)
gelegt. Nicht Teil dieses Plans: die echte S-Pen-Zeichenfläche (weiterhin
Meilenstein 5) und Schreibzugriff im vernetzten Modus.
