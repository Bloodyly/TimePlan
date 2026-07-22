# Stift-/Handeingabe (S-Pen) für Monteur-Zellen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Monteur-Zellen im echten (nicht-Demo) Android-Modus lassen sich antippen, um eine nahezu vollflächige Zeichenfläche zu öffnen (S-Pen und Finger), die als Vektordaten über die bereits vorhandene REST-API persistiert wird — sichtbar als Live-Vorschau sowohl im Android-Wochenraster als auch im WebUI.

**Architecture:** Server-seitig existieren Schema, Validierung und die komplette REST-API für den `drawing`-Entry-Typ bereits vollständig und werden nicht verändert. Neu ist ausschließlich Client-seitiges: eine isolierte, Android-freie Vektor-/Undo-Redo-Logik (Task 1), eine dünne Android-Zeichenfläche darüber (Task 2), Schreib-Methoden im bisher rein lesenden `TimePlanApiClient` (Task 3), die Verdrahtung in `WeekActivity` inkl. Speichern-Fluss (Task 4), eine Live-Vorschau im Android-Grid (Task 5) und eine Live-SVG-Vorschau im WebUI-Template (Task 6).

**Tech Stack:** Kotlin/Android (custom View, kotlinx.serialization, OkHttp — alles bereits vorhandene Dependencies), Python/FastAPI/Jinja2 (nur Task 6, reine Template-Änderung, kein Python-Code-Change).

## Global Constraints

- Gilt ausschließlich für Monteur-Zellen. Azubi-Zellen bleiben unverändert beim Status-Picker (Server lehnt `drawing` bei Azubis bereits ab — `entries_repo.create_entry`).
- S-Pen **und** Finger werden akzeptiert (kein Palm-Rejection-Sonderfall nötig).
- Stiftfarbe und -stärke sind fest: Farbe `#201A10` (Projekt-`ink`-Farbe), Strichstärke `4f`. Kein Auswahl-UI.
- Kein Offline-Queue, keine Room-Datenbank, kein automatischer Retry. Schlägt der Upload fehl, bleibt die Zeichenfläche mit den gezeichneten Strichen offen und zeigt eine Fehlermeldung.
- Kein separates gerendertes Vorschaubild (WebP/PNG). Vorschau wird live aus den Vektordaten gerendert (Android: `Canvas.scale()`, WebUI: Inline-`<svg>` mit `viewBox`).
- Vorhandener WebUI-Text und Handschrift werden in derselben Zelle gestapelt angezeigt (beide sichtbar, keine Priorisierung).
- Demo-Modus (`DemoApi`, `TimePlanApi`-Interface) bleibt vollständig unangetastet — dieses Feature ist ausschließlich für den echten Server-Modus.
- Vektorschema (server-seitig bereits validiert, exakt einzuhalten):
  `{"canvas_width": int, "canvas_height": int, "strokes": [{"color": str, "base_width": float, "points": [{"x": float, "y": float, "pressure": float, "time": int}]}]}`
- Bestehende Server-API (unverändert, nur zu nutzen): `POST /api/v1/entries` `{"cell_id","type","content"}` → `201 {"entry": Entry}`; `PUT /api/v1/entries/{id}` `{"content","base_revision"}` → `200 {"entry": Entry}` / `409` Konflikt; Device-Token-Auth via `Authorization: Bearer <token>`.

---

### Task 1: Vektor-/Undo-Redo-Modell (isolierte, Android-freie Logik)

**Files:**
- Create: `android/app/src/main/java/de/fs/timeplan/drawing/DrawingModel.kt`
- Test: `android/app/src/test/java/de/fs/timeplan/drawing/DrawingModelTest.kt`

**Interfaces:**
- Consumes: nichts (reines Kotlin, keine Android-Abhängigkeit — wie `WeekSwipeGesture`/`DragFillRange`).
- Produces (für Task 2, 4, 5):
  - `data class StrokePoint(val x: Float, val y: Float, val pressure: Float, val time: Long)`
  - `data class Stroke(val color: String, val base_width: Float, val points: List<StrokePoint>)`
  - `data class DrawingContent(val canvas_width: Int, val canvas_height: Int, val strokes: List<Stroke>)`
  - `object DrawingContentCodec { fun encode(content: DrawingContent): JsonElement; fun decode(element: JsonElement): DrawingContent? }`
  - `class StrokeHistory { fun strokes(): List<Stroke>; fun push(stroke: Stroke); fun undo(); fun redo(); fun clear(); fun canUndo(): Boolean; fun canRedo(): Boolean; fun load(initial: List<Stroke>) }`
  - `fun scaleStrokes(strokes: List<Stroke>, sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): List<Stroke>`

- [ ] **Step 1: Write the failing tests**

Create `android/app/src/test/java/de/fs/timeplan/drawing/DrawingModelTest.kt`:

```kotlin
package de.fs.timeplan.drawing

import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class DrawingModelTest {

    @Test
    fun `encode produces the exact server schema field names`() {
        val content = DrawingContent(
            canvas_width = 100, canvas_height = 50,
            strokes = listOf(Stroke("#201A10", 4f, listOf(StrokePoint(1f, 2f, 0.5f, 0L))))
        )
        val jsonText = DrawingContentCodec.encode(content).toString()
        assertTrue(jsonText.contains("\"canvas_width\":100"))
        assertTrue(jsonText.contains("\"canvas_height\":50"))
        assertTrue(jsonText.contains("\"base_width\":4.0"))
        assertTrue(jsonText.contains("\"pressure\":0.5"))
    }

    @Test
    fun `decode round-trips through encode`() {
        val content = DrawingContent(
            80, 40,
            listOf(Stroke("#201A10", 4f, listOf(StrokePoint(0f, 0f, 1f, 0L), StrokePoint(5f, 5f, 1f, 12L))))
        )
        val decoded = DrawingContentCodec.decode(DrawingContentCodec.encode(content))
        assertEquals(content, decoded)
    }

    @Test
    fun `decode returns null for malformed content`() {
        val bad = Json.parseToJsonElement("""{"not":"a drawing"}""")
        assertNull(DrawingContentCodec.decode(bad))
    }

    @Test
    fun `push adds a stroke and clears redo history`() {
        val history = StrokeHistory()
        history.push(Stroke("#201A10", 4f, listOf(StrokePoint(0f, 0f, 1f, 0L))))
        history.undo()
        history.push(Stroke("#201A10", 4f, listOf(StrokePoint(1f, 1f, 1f, 0L))))
        assertFalse(history.canRedo())
        assertEquals(1, history.strokes().size)
    }

    @Test
    fun `undo then redo restores the stroke`() {
        val history = StrokeHistory()
        val stroke = Stroke("#201A10", 4f, listOf(StrokePoint(0f, 0f, 1f, 0L)))
        history.push(stroke)
        history.undo()
        assertTrue(history.strokes().isEmpty())
        assertTrue(history.canRedo())
        history.redo()
        assertEquals(listOf(stroke), history.strokes())
    }

    @Test
    fun `load replaces strokes and clears redo history`() {
        val history = StrokeHistory()
        history.push(Stroke("#201A10", 4f, listOf(StrokePoint(9f, 9f, 1f, 0L))))
        history.undo()
        val loaded = listOf(Stroke("#201A10", 4f, listOf(StrokePoint(1f, 1f, 1f, 0L))))
        history.load(loaded)
        assertEquals(loaded, history.strokes())
        assertFalse(history.canRedo())
    }

    @Test
    fun `clear empties strokes and redo history`() {
        val history = StrokeHistory()
        history.push(Stroke("#201A10", 4f, listOf(StrokePoint(0f, 0f, 1f, 0L))))
        history.undo()
        history.clear()
        assertTrue(history.strokes().isEmpty())
        assertFalse(history.canRedo())
        assertFalse(history.canUndo())
    }

    @Test
    fun `scaleStrokes maps points into the target coordinate space`() {
        val strokes = listOf(Stroke("#201A10", 4f, listOf(StrokePoint(10f, 20f, 1f, 0L))))
        val scaled = scaleStrokes(strokes, sourceWidth = 100, sourceHeight = 200, targetWidth = 50, targetHeight = 100)
        assertEquals(5f, scaled[0].points[0].x, 0.001f)
        assertEquals(10f, scaled[0].points[0].y, 0.001f)
    }

    @Test
    fun `scaleStrokes is a no-op when dimensions already match`() {
        val strokes = listOf(Stroke("#201A10", 4f, listOf(StrokePoint(10f, 20f, 1f, 0L))))
        val scaled = scaleStrokes(strokes, 100, 200, 100, 200)
        assertEquals(strokes, scaled)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "de.fs.timeplan.drawing.DrawingModelTest"`
Expected: FAIL (compile error — `DrawingModel.kt` doesn't exist yet)

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/de/fs/timeplan/drawing/DrawingModel.kt`:

```kotlin
package de.fs.timeplan.drawing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class StrokePoint(val x: Float, val y: Float, val pressure: Float, val time: Long)

@Serializable
data class Stroke(val color: String, val base_width: Float, val points: List<StrokePoint>)

@Serializable
data class DrawingContent(val canvas_width: Int, val canvas_height: Int, val strokes: List<Stroke>)

object DrawingContentCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(content: DrawingContent): JsonElement =
        json.encodeToJsonElement(DrawingContent.serializer(), content)

    fun decode(element: JsonElement): DrawingContent? =
        try {
            json.decodeFromJsonElement(DrawingContent.serializer(), element)
        } catch (e: Exception) {
            null
        }
}

class StrokeHistory {
    private val strokes = mutableListOf<Stroke>()
    private val redoStack = mutableListOf<Stroke>()

    fun strokes(): List<Stroke> = strokes.toList()

    fun push(stroke: Stroke) {
        strokes.add(stroke)
        redoStack.clear()
    }

    fun undo() {
        if (strokes.isNotEmpty()) redoStack.add(strokes.removeAt(strokes.size - 1))
    }

    fun redo() {
        if (redoStack.isNotEmpty()) strokes.add(redoStack.removeAt(redoStack.size - 1))
    }

    fun clear() {
        strokes.clear()
        redoStack.clear()
    }

    fun canUndo(): Boolean = strokes.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun load(initial: List<Stroke>) {
        strokes.clear()
        strokes.addAll(initial)
        redoStack.clear()
    }
}

fun scaleStrokes(
    strokes: List<Stroke>,
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int
): List<Stroke> {
    if (sourceWidth <= 0 || sourceHeight <= 0 ||
        (sourceWidth == targetWidth && sourceHeight == targetHeight)
    ) {
        return strokes
    }
    val scaleX = targetWidth.toFloat() / sourceWidth
    val scaleY = targetHeight.toFloat() / sourceHeight
    return strokes.map { stroke ->
        stroke.copy(points = stroke.points.map { it.copy(x = it.x * scaleX, y = it.y * scaleY) })
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "de.fs.timeplan.drawing.DrawingModelTest"`
Expected: PASS (9 tests)

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/de/fs/timeplan/drawing/DrawingModel.kt android/app/src/test/java/de/fs/timeplan/drawing/DrawingModelTest.kt
git commit -m "feat: add pure vector/undo-redo model for handwriting entries"
```

---

### Task 2: DrawingView (Android-Zeichenfläche)

**Files:**
- Create: `android/app/src/main/java/de/fs/timeplan/drawing/DrawingView.kt`
- Test: `android/app/src/test/java/de/fs/timeplan/drawing/DrawingViewTest.kt`

**Interfaces:**
- Consumes: `Stroke`, `StrokePoint`, `StrokeHistory` (Task 1)
- Produces (für Task 4):
  - `class DrawingView(context: Context, attrs: AttributeSet? = null) : View(context, attrs)` mit:
    `fun loadStrokes(strokes: List<Stroke>)`, `fun strokes(): List<Stroke>`,
    `fun undo()`, `fun redo()`, `fun clear()`,
    `fun canUndo(): Boolean`, `fun canRedo(): Boolean`

- [ ] **Step 1: Write the failing tests**

Create `android/app/src/test/java/de/fs/timeplan/drawing/DrawingViewTest.kt`:

```kotlin
package de.fs.timeplan.drawing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

@RunWith(RobolectricTestRunner::class)
class DrawingViewTest {

    private fun newView(): DrawingView =
        DrawingView(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `loadStrokes then strokes returns the same data`() {
        val view = newView()
        val strokes = listOf(Stroke("#201A10", 4f, listOf(StrokePoint(1f, 2f, 0.5f, 0L))))
        view.loadStrokes(strokes)
        assertEquals(strokes, view.strokes())
    }

    @Test
    fun `clear removes all strokes and disables undo`() {
        val view = newView()
        view.loadStrokes(listOf(Stroke("#201A10", 4f, listOf(StrokePoint(0f, 0f, 1f, 0L)))))
        view.clear()
        assertTrue(view.strokes().isEmpty())
        assertFalse(view.canUndo())
    }

    @Test
    fun `undo then redo restores the loaded stroke`() {
        val view = newView()
        val stroke = Stroke("#201A10", 4f, listOf(StrokePoint(0f, 0f, 1f, 0L), StrokePoint(5f, 5f, 1f, 10L)))
        view.loadStrokes(listOf(stroke))
        view.undo()
        assertTrue(view.strokes().isEmpty())
        view.redo()
        assertEquals(listOf(stroke), view.strokes())
    }

    @Test
    fun `canUndo and canRedo reflect history state`() {
        val view = newView()
        assertFalse(view.canUndo())
        assertFalse(view.canRedo())
        view.loadStrokes(listOf(Stroke("#201A10", 4f, listOf(StrokePoint(0f, 0f, 1f, 0L)))))
        assertTrue(view.canUndo())
        assertFalse(view.canRedo())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "de.fs.timeplan.drawing.DrawingViewTest"`
Expected: FAIL (compile error — `DrawingView` doesn't exist yet)

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/de/fs/timeplan/drawing/DrawingView.kt`:

```kotlin
package de.fs.timeplan.drawing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

private const val PEN_COLOR = "#201A10"
private const val PEN_BASE_WIDTH = 4f

class DrawingView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val history = StrokeHistory()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(PEN_COLOR)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = PEN_BASE_WIDTH
    }

    private var currentPoints: MutableList<StrokePoint>? = null
    private var strokeStartTime = 0L

    fun loadStrokes(strokes: List<Stroke>) {
        history.load(strokes)
        invalidate()
    }

    fun strokes(): List<Stroke> = history.strokes()

    fun undo() {
        history.undo()
        invalidate()
    }

    fun redo() {
        history.redo()
        invalidate()
    }

    fun clear() {
        history.clear()
        invalidate()
    }

    fun canUndo(): Boolean = history.canUndo()
    fun canRedo(): Boolean = history.canRedo()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 1) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                strokeStartTime = event.eventTime
                currentPoints = mutableListOf(StrokePoint(event.x, event.y, event.pressure, 0L))
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                currentPoints?.add(
                    StrokePoint(event.x, event.y, event.pressure, event.eventTime - strokeStartTime)
                )
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                currentPoints?.let { points ->
                    if (points.size > 1) {
                        history.push(Stroke(PEN_COLOR, PEN_BASE_WIDTH, points.toList()))
                    }
                }
                currentPoints = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                currentPoints = null
                invalidate()
            }
            else -> return false
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (stroke in history.strokes()) {
            canvas.drawPath(pathFor(stroke.points), paint)
        }
        currentPoints?.let { points ->
            if (points.size > 1) canvas.drawPath(pathFor(points), paint)
        }
    }

    private fun pathFor(points: List<StrokePoint>): Path {
        val path = Path()
        points.firstOrNull()?.let { path.moveTo(it.x, it.y) }
        for (point in points.drop(1)) path.lineTo(point.x, point.y)
        return path
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "de.fs.timeplan.drawing.DrawingViewTest"`
Expected: PASS (4 tests)

Note: die eigentliche Touch-Zeichnung (`onTouchEvent`/`onDraw`) ist wie bei den bisherigen Gesten-Features nur manuell im Emulator verifizierbar (kein MotionEvent-Simulationstest in diesem Projekt üblich).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/de/fs/timeplan/drawing/DrawingView.kt android/app/src/test/java/de/fs/timeplan/drawing/DrawingViewTest.kt
git commit -m "feat: add DrawingView for stylus/finger handwriting capture"
```

---

### Task 3: TimePlanApiClient Schreib-Methoden

**Files:**
- Modify: `android/app/src/main/java/de/fs/timeplan/net/TimePlanApiClient.kt`
- Test: `android/app/src/test/java/de/fs/timeplan/net/TimePlanApiClientTest.kt`

**Interfaces:**
- Consumes: `Entry` (bereits vorhanden in `de.fs.timeplan.model.Models.kt`), `ApiResult` (bereits vorhanden)
- Produces (für Task 4):
  - `TimePlanApiClient.createEntry(cellId: String, type: String, content: JsonElement): ApiResult<Entry>`
  - `TimePlanApiClient.updateEntry(entryId: String, content: JsonElement, baseRevision: Int): ApiResult<Entry>`
  - Diese Methoden sind bewusst **nicht** Teil des `TimePlanApi`-Interfaces (das `DemoApi` ebenfalls implementiert) — das Feature ist real-mode-only, `DemoApi` bleibt unverändert.

- [ ] **Step 1: Write the failing tests**

Append to `android/app/src/test/java/de/fs/timeplan/net/TimePlanApiClientTest.kt` (vor der letzten schließenden `}`):

```kotlin
    @Test
    fun `createEntry posts cell_id type content and parses the returned entry`() {
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"entry":{"id":"e-1","cell_id":"2026-W31_w-1_2026-07-27","type":"drawing",
                "author_type":"tablet","author_id":"tablet-01",
                "content":{"canvas_width":100,"canvas_height":50,"strokes":[]},
                "conflict_of":null,"created_at":"t","updated_at":"t","revision":1}}"""
        ))
        val content = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"canvas_width":100,"canvas_height":50,"strokes":[]}""")
        val result = client.createEntry("2026-W31_w-1_2026-07-27", "drawing", content)
        require(result is ApiResult.Success)
        assertEquals("e-1", result.data.id)
        assertEquals(1, result.data.revision)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("Bearer testtoken", recorded.getHeader("Authorization"))
        assertTrue(recorded.body.readUtf8().contains("\"cell_id\":\"2026-W31_w-1_2026-07-27\""))
    }

    @Test
    fun `updateEntry puts content and base_revision and parses the returned entry`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"entry":{"id":"e-1","cell_id":"c","type":"drawing",
                "author_type":"tablet","author_id":"tablet-01",
                "content":{"canvas_width":1,"canvas_height":1,"strokes":[]},
                "conflict_of":null,"created_at":"t","updated_at":"t","revision":2}}"""
        ))
        val content = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"canvas_width":1,"canvas_height":1,"strokes":[]}""")
        val result = client.updateEntry("e-1", content, 1)
        require(result is ApiResult.Success)
        assertEquals(2, result.data.revision)
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertTrue(recorded.body.readUtf8().contains("\"base_revision\":1"))
    }

    @Test
    fun `createEntry returns Error on 422 validation failure`() {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"invalid"}"""))
        val result = client.createEntry("bad", "drawing", kotlinx.serialization.json.Json.parseToJsonElement("{}"))
        require(result is ApiResult.Error)
        assertEquals(422, result.code)
    }

    @Test
    fun `updateEntry returns Error on 409 conflict`() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":{"error":"revision_conflict"}}"""))
        val result = client.updateEntry("e-1", kotlinx.serialization.json.Json.parseToJsonElement("{}"), 1)
        require(result is ApiResult.Error)
        assertEquals(409, result.code)
    }

    @Test
    fun `createEntry returns NetworkFailure when server unreachable`() {
        server.shutdown()
        val result = client.createEntry("c", "drawing", kotlinx.serialization.json.Json.parseToJsonElement("{}"))
        assertTrue(result is ApiResult.NetworkFailure)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "de.fs.timeplan.net.TimePlanApiClientTest"`
Expected: FAIL (compile error — `createEntry`/`updateEntry` don't exist yet)

- [ ] **Step 3: Write the implementation**

Modify `android/app/src/main/java/de/fs/timeplan/net/TimePlanApiClient.kt` — add imports and two new methods plus a shared POST/PUT helper. Full new file content:

```kotlin
package de.fs.timeplan.net

import de.fs.timeplan.config.ServerConfig
import de.fs.timeplan.model.Entry
import de.fs.timeplan.model.StatusResponse
import de.fs.timeplan.model.WeekBundle
import de.fs.timeplan.model.WorkersResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Serializable
private data class CreateEntryRequest(val cell_id: String, val type: String, val content: JsonElement)

@Serializable
private data class UpdateEntryRequest(val content: JsonElement, val base_revision: Int)

@Serializable
private data class EntryEnvelope(val entry: Entry)

class TimePlanApiClient(
    private val config: ServerConfig,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : TimePlanApi {

    private fun request(path: String): Request =
        Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}$path")
            .header("Authorization", "Bearer ${config.token}")
            .get()
            .build()

    private inline fun <reified T> execute(path: String): ApiResult<T> {
        return try {
            client.newCall(request(path)).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    ApiResult.Success(json.decodeFromString<T>(body))
                } else {
                    ApiResult.Error(response.code, body)
                }
            }
        } catch (e: IOException) {
            ApiResult.NetworkFailure(e.message ?: "network error")
        } catch (e: Exception) {
            ApiResult.NetworkFailure(e.message ?: "unerwarteter Fehler")
        }
    }

    private fun executeWithBody(path: String, method: String, body: String): ApiResult<Entry> {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val httpRequest = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}$path")
            .header("Authorization", "Bearer ${config.token}")
            .method(method, body.toRequestBody(mediaType))
            .build()
        return try {
            client.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    ApiResult.Success(json.decodeFromString(EntryEnvelope.serializer(), responseBody).entry)
                } else {
                    ApiResult.Error(response.code, responseBody)
                }
            }
        } catch (e: IOException) {
            ApiResult.NetworkFailure(e.message ?: "network error")
        } catch (e: Exception) {
            ApiResult.NetworkFailure(e.message ?: "unerwarteter Fehler")
        }
    }

    override fun getStatus(): ApiResult<StatusResponse> = execute("/api/v1/status")

    override fun getWorkers(): ApiResult<WorkersResponse> = execute("/api/v1/workers")

    override fun getWeek(weekId: String): ApiResult<WeekBundle> = execute("/api/v1/weeks/$weekId")

    fun createEntry(cellId: String, type: String, content: JsonElement): ApiResult<Entry> {
        val body = json.encodeToString(CreateEntryRequest.serializer(), CreateEntryRequest(cellId, type, content))
        return executeWithBody("/api/v1/entries", "POST", body)
    }

    fun updateEntry(entryId: String, content: JsonElement, baseRevision: Int): ApiResult<Entry> {
        val body = json.encodeToString(UpdateEntryRequest.serializer(), UpdateEntryRequest(content, baseRevision))
        return executeWithBody("/api/v1/entries/$entryId", "PUT", body)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "de.fs.timeplan.net.TimePlanApiClientTest"`
Expected: PASS (9 tests: 4 existing + 5 new)

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/de/fs/timeplan/net/TimePlanApiClient.kt android/app/src/test/java/de/fs/timeplan/net/TimePlanApiClientTest.kt
git commit -m "feat: add createEntry/updateEntry write methods to TimePlanApiClient"
```

---

### Task 4: Verdrahtung in WeekActivity (Trigger, Zeichen-Dialog, Speichern-Fluss)

**Files:**
- Modify: `android/app/src/main/java/de/fs/timeplan/week/WeekPresenter.kt`
- Modify: `android/app/src/test/java/de/fs/timeplan/week/WeekPresenterTest.kt`
- Modify: `android/app/src/main/java/de/fs/timeplan/WeekActivity.kt`
- Modify: `android/app/src/test/java/de/fs/timeplan/week/WeekActivityTest.kt`
- Create: `android/app/src/main/res/layout/dialog_drawing.xml`
- Modify: `android/app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `DrawingView`, `Stroke`, `DrawingContent`, `DrawingContentCodec`, `scaleStrokes` (Task 1+2); `TimePlanApiClient.createEntry`/`updateEntry` (Task 3)
- Produces: nichts für spätere Tasks (Endpunkt der Interaktionskette). Task 5 ist unabhängig (baut nur auf Task 1 + `Entry`/`WeekGridBuilder` auf).

- [ ] **Step 1: Write the failing test for `WeekPresenter`**

`WeekLoadResult.Success` muss zusätzlich die geladenen `workers` und rohen `entries` mitführen, damit `WeekActivity` beim Zellen-Tap nachschlagen kann, ob für die Zelle bereits eine Zeichnung existiert — ohne dafür einen zusätzlichen (blockierenden) Netzwerkaufruf zu machen.

Modify `android/app/src/test/java/de/fs/timeplan/week/WeekPresenterTest.kt`, im ersten Test:

```kotlin
    @Test
    fun `success combines workers and week into rows`() {
        val api = FakeApi(
            workersResult = ApiResult.Success(WorkersResponse(listOf(monteur))),
            weekResult = ApiResult.Success(WeekBundle(Week("2026-W31", "OPEN", 5), dates, emptyList()))
        )
        val result = WeekPresenter(api).loadWeek("2026-W31")
        require(result is WeekLoadResult.Success)
        assertEquals("2026-W31", result.week.id)
        assertEquals(2, result.rows.size)
        assertEquals(listOf(monteur), result.workers)
        assertEquals(emptyList<de.fs.timeplan.model.Entry>(), result.entries)
    }
```

(Die restlichen drei Tests in dieser Datei bleiben unverändert — sie prüfen nur `WeekLoadResult.Failure`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "de.fs.timeplan.week.WeekPresenterTest"`
Expected: FAIL (compile error — `result.workers`/`result.entries` don't exist yet)

- [ ] **Step 3: Extend `WeekLoadResult.Success`**

Modify `android/app/src/main/java/de/fs/timeplan/week/WeekPresenter.kt` — full new file content:

```kotlin
package de.fs.timeplan.week

import de.fs.timeplan.grid.WeekGridBuilder
import de.fs.timeplan.grid.WeekRow
import de.fs.timeplan.model.Entry
import de.fs.timeplan.model.Week
import de.fs.timeplan.model.Worker
import de.fs.timeplan.net.ApiResult
import de.fs.timeplan.net.TimePlanApi

sealed class WeekLoadResult {
    data class Success(
        val week: Week,
        val dates: List<String>,
        val rows: List<WeekRow>,
        val workers: List<Worker>,
        val entries: List<Entry>
    ) : WeekLoadResult()
    data class Failure(val message: String) : WeekLoadResult()
}

class WeekPresenter(private val api: TimePlanApi) {

    fun loadWeek(weekId: String): WeekLoadResult {
        val workersResult = api.getWorkers()
        val workers = when (workersResult) {
            is ApiResult.Success -> workersResult.data.workers
            is ApiResult.Error -> return WeekLoadResult.Failure(
                "Monteure konnten nicht geladen werden (${workersResult.code})")
            is ApiResult.NetworkFailure -> return WeekLoadResult.Failure(
                "Server nicht erreichbar: ${workersResult.message}")
        }

        val weekResult = api.getWeek(weekId)
        return when (weekResult) {
            is ApiResult.Success -> {
                val bundle = weekResult.data
                WeekLoadResult.Success(
                    week = bundle.week,
                    dates = bundle.dates,
                    rows = WeekGridBuilder.build(weekId, bundle.dates, workers, bundle.entries),
                    workers = workers,
                    entries = bundle.entries
                )
            }
            is ApiResult.Error -> WeekLoadResult.Failure(
                "Woche konnte nicht geladen werden (${weekResult.code})")
            is ApiResult.NetworkFailure -> WeekLoadResult.Failure(
                "Server nicht erreichbar: ${weekResult.message}")
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests "de.fs.timeplan.week.WeekPresenterTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Add strings**

Modify `android/app/src/main/res/values/strings.xml` — add these three lines anywhere inside `<resources>`:

```xml
    <string name="drawing_clear">Löschen</string>
    <string name="drawing_undo">Rückgängig</string>
    <string name="drawing_redo">Wiederholen</string>
```

- [ ] **Step 6: Create the drawing dialog layout**

Create `android/app/src/main/res/layout/dialog_drawing.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="@color/paper_surface">

    <de.fs.timeplan.drawing.DrawingView
        android:id="@+id/drawingCanvas"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:background="@color/paper_bg" />

    <TextView
        android:id="@+id/drawingErrorLabel"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="8dp"
        android:gravity="center"
        android:textColor="@color/status_sick"
        android:textSize="13sp"
        android:visibility="gone" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:padding="12dp"
        android:gravity="center_vertical">

        <Button
            android:id="@+id/buttonDrawingClear"
            style="@style/TimePlan.Button.Text"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/drawing_clear" />

        <Button
            android:id="@+id/buttonDrawingUndo"
            style="@style/TimePlan.Button.Text"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/drawing_undo" />

        <Button
            android:id="@+id/buttonDrawingRedo"
            style="@style/TimePlan.Button.Text"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/drawing_redo" />

        <View
            android:layout_width="0dp"
            android:layout_height="1dp"
            android:layout_weight="1" />

        <Button
            android:id="@+id/buttonDrawingCancel"
            style="@style/TimePlan.Button.Text"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/cancel" />

        <Button
            android:id="@+id/buttonDrawingSave"
            style="@style/TimePlan.Button.Text"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/save" />

    </LinearLayout>

</LinearLayout>
```

- [ ] **Step 7: Write the failing tests for `WeekActivity`**

Append to `android/app/src/test/java/de/fs/timeplan/week/WeekActivityTest.kt`, inside the class body (before the final closing `}`). These tests configure real (non-Demo) mode by saving a `ServerConfig` pointed at a `MockWebServer`, mirroring `TimePlanApiClientTest`'s setup:

```kotlin
    @Test
    fun `tapping an azubi cell in real mode does nothing`() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        de.fs.timeplan.config.ConfigRepository(
            org.robolectric.RuntimeEnvironment.getApplication()
        ).save(de.fs.timeplan.config.ServerConfig(
            baseUrl = server.url("/").toString().trimEnd('/'),
            deviceId = "tablet-01", token = "testtoken"
        ))
        val azubi = de.fs.timeplan.model.Worker("w-2", "501", "Petersen", "azubi", 1, true, 1)
        val monteur = de.fs.timeplan.model.Worker("w-1", "144", "Albrecht", "monteur", 1, true, 1)
        val weekId = WeekId.currentWeekId()
        val dates = WeekId.weekDates(weekId).map { it.toString() }

        val controller = Robolectric.buildActivity(WeekActivity::class.java)
        controller.setup()
        val activity = controller.get()

        setField(activity, "currentWorkers", listOf(monteur, azubi))
        setField(activity, "currentEntries", emptyList<de.fs.timeplan.model.Entry>())
        setField(activity, "currentDates", dates)
        setField(activity, "currentWeekId", weekId)

        val method = WeekActivity::class.java.getDeclaredMethod(
            "onMonteurCellClick", String::class.java, Int::class.java
        )
        method.isAccessible = true
        method.invoke(activity, azubi.id, 0)

        assertNull(ShadowDialog.getLatestDialog())
        server.shutdown()
    }

    @Test
    fun `tapping a monteur cell in real mode opens an empty drawing canvas when no entry exists`() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        de.fs.timeplan.config.ConfigRepository(
            org.robolectric.RuntimeEnvironment.getApplication()
        ).save(de.fs.timeplan.config.ServerConfig(
            baseUrl = server.url("/").toString().trimEnd('/'),
            deviceId = "tablet-01", token = "testtoken"
        ))
        val monteur = de.fs.timeplan.model.Worker("w-1", "144", "Albrecht", "monteur", 1, true, 1)
        val weekId = WeekId.currentWeekId()
        val dates = WeekId.weekDates(weekId).map { it.toString() }

        val controller = Robolectric.buildActivity(WeekActivity::class.java)
        controller.setup()
        val activity = controller.get()

        setField(activity, "currentWorkers", listOf(monteur))
        setField(activity, "currentEntries", emptyList<de.fs.timeplan.model.Entry>())
        setField(activity, "currentDates", dates)
        setField(activity, "currentWeekId", weekId)

        val method = WeekActivity::class.java.getDeclaredMethod(
            "onMonteurCellClick", String::class.java, Int::class.java
        )
        method.isAccessible = true
        method.invoke(activity, monteur.id, 0)

        val dialog = ShadowDialog.getLatestDialog()
        val canvas = dialog.findViewById<de.fs.timeplan.drawing.DrawingView>(R.id.drawingCanvas)
        assertTrue(canvas.strokes().isEmpty())
        server.shutdown()
    }

    @Test
    fun `tapping a monteur cell with an existing drawing entry pre-loads its strokes`() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        de.fs.timeplan.config.ConfigRepository(
            org.robolectric.RuntimeEnvironment.getApplication()
        ).save(de.fs.timeplan.config.ServerConfig(
            baseUrl = server.url("/").toString().trimEnd('/'),
            deviceId = "tablet-01", token = "testtoken"
        ))
        val monteur = de.fs.timeplan.model.Worker("w-1", "144", "Albrecht", "monteur", 1, true, 1)
        val weekId = WeekId.currentWeekId()
        val dates = WeekId.weekDates(weekId).map { it.toString() }
        val cellId = WeekId.makeCellId(weekId, monteur.id, dates[0])
        val existingContent = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"canvas_width":10,"canvas_height":10,"strokes":[{"color":"#201A10","base_width":4.0,
                "points":[{"x":1.0,"y":1.0,"pressure":1.0,"time":0}]}]}"""
        )
        val existingEntry = de.fs.timeplan.model.Entry(
            id = "e-1", cell_id = cellId, type = "drawing",
            author_type = "tablet", author_id = "tablet-01",
            content = existingContent, conflict_of = null,
            created_at = "t", updated_at = "t", revision = 3
        )

        val controller = Robolectric.buildActivity(WeekActivity::class.java)
        controller.setup()
        val activity = controller.get()

        setField(activity, "currentWorkers", listOf(monteur))
        setField(activity, "currentEntries", listOf(existingEntry))
        setField(activity, "currentDates", dates)
        setField(activity, "currentWeekId", weekId)

        val method = WeekActivity::class.java.getDeclaredMethod(
            "onMonteurCellClick", String::class.java, Int::class.java
        )
        method.isAccessible = true
        method.invoke(activity, monteur.id, 0)

        val dialog = ShadowDialog.getLatestDialog()
        val canvas = dialog.findViewById<de.fs.timeplan.drawing.DrawingView>(R.id.drawingCanvas)
        assertEquals(1, canvas.strokes().size)
        server.shutdown()
    }

    @Test
    fun `saving a new drawing posts to the server and closes the dialog`() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        de.fs.timeplan.config.ConfigRepository(
            org.robolectric.RuntimeEnvironment.getApplication()
        ).save(de.fs.timeplan.config.ServerConfig(
            baseUrl = server.url("/").toString().trimEnd('/'),
            deviceId = "tablet-01", token = "testtoken"
        ))
        val monteur = de.fs.timeplan.model.Worker("w-1", "144", "Albrecht", "monteur", 1, true, 1)
        val weekId = WeekId.currentWeekId()
        val dates = WeekId.weekDates(weekId).map { it.toString() }

        val controller = Robolectric.buildActivity(WeekActivity::class.java)
        controller.setup()
        val activity = controller.get()

        setField(activity, "currentWorkers", listOf(monteur))
        setField(activity, "currentEntries", emptyList<de.fs.timeplan.model.Entry>())
        setField(activity, "currentDates", dates)
        setField(activity, "currentWeekId", weekId)

        val method = WeekActivity::class.java.getDeclaredMethod(
            "onMonteurCellClick", String::class.java, Int::class.java
        )
        method.isAccessible = true
        method.invoke(activity, monteur.id, 0)

        val dialog = ShadowDialog.getLatestDialog()
        val canvas = dialog.findViewById<de.fs.timeplan.drawing.DrawingView>(R.id.drawingCanvas)
        canvas.loadStrokes(listOf(de.fs.timeplan.drawing.Stroke(
            "#201A10", 4f, listOf(de.fs.timeplan.drawing.StrokePoint(0f, 0f, 1f, 0L),
                                   de.fs.timeplan.drawing.StrokePoint(5f, 5f, 1f, 10L))
        )))

        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(201).setBody(
            """{"entry":{"id":"e-9","cell_id":"c","type":"drawing","author_type":"tablet",
                "author_id":"tablet-01","content":{"canvas_width":1,"canvas_height":1,"strokes":[]},
                "conflict_of":null,"created_at":"t","updated_at":"t","revision":1}}"""
        ))
        // Second response for the loadCurrentWeek() refresh triggered after a successful save.
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody(
            """{"workers":[]}"""))
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody(
            """{"week":{"id":"$weekId","status":"OPEN","revision":1},"dates":${
                dates.joinToString(",", "[", "]") { "\"$it\"" }},"entries":[]}"""))

        dialog.findViewById<android.widget.Button>(R.id.buttonDrawingSave).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/entries", recorded.path)
        server.shutdown()
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = WeekActivity::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }
```

- [ ] **Step 8: Run tests to verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "de.fs.timeplan.week.WeekActivityTest"`
Expected: FAIL (compile error — `onMonteurCellClick`, `currentWorkers`, `currentEntries`, `R.id.drawingCanvas` etc. don't exist yet)

- [ ] **Step 9: Wire it up in `WeekActivity`**

Modify `android/app/src/main/java/de/fs/timeplan/WeekActivity.kt`:

Add imports (after the existing `import de.fs.timeplan.settings.SettingsActivity` line, keeping alphabetical order among the `de.fs.timeplan.*` block):

```kotlin
import de.fs.timeplan.drawing.DrawingContent
import de.fs.timeplan.drawing.DrawingContentCodec
import de.fs.timeplan.drawing.DrawingView
import de.fs.timeplan.drawing.scaleStrokes
import de.fs.timeplan.model.Entry
```

Add `import android.widget.Button` next to the other `android.widget.*` imports.

Add two new fields next to the existing `currentDates`/`isDialogShowing` fields:

```kotlin
    private var currentWorkers: List<Worker> = emptyList()
    private var currentEntries: List<Entry> = emptyList()
```

In `render()`, inside the `is WeekLoadResult.Success ->` branch, add two lines after `currentDates = result.dates`:

```kotlin
                currentDates = result.dates
                currentWorkers = result.workers
                currentEntries = result.entries
```

Wire the real-mode click handler. Find the `onResume()` block:

```kotlin
        adapter.onCellClick = if (isDemoMode) {
            { workerId, dateIndex -> onDemoCellClick(workerId, dateIndex) }
        } else {
            null
        }
```

Replace the `null` branch:

```kotlin
        adapter.onCellClick = if (isDemoMode) {
            { workerId, dateIndex -> onDemoCellClick(workerId, dateIndex) }
        } else {
            { workerId, dateIndex -> onMonteurCellClick(workerId, dateIndex) }
        }
```

Add the two new private methods (place them right after `onDemoCellClick`, before `onDragFillCommit`):

```kotlin
    private fun onMonteurCellClick(workerId: String, dateIndex: Int) {
        val worker = currentWorkers.firstOrNull { it.id == workerId } ?: return
        if (!worker.isMonteur) return
        val dateIso = currentDates.getOrNull(dateIndex) ?: return
        val cellId = WeekId.makeCellId(currentWeekId, workerId, dateIso)
        val existingDrawing = currentEntries.firstOrNull { it.cell_id == cellId && it.type == "drawing" }
        showDrawingEditor(worker, cellId, existingDrawing)
    }

    private fun showDrawingEditor(worker: Worker, cellId: String, existingEntry: Entry?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_drawing, null)
        val canvas = dialogView.findViewById<DrawingView>(R.id.drawingCanvas)
        val errorLabel = dialogView.findViewById<TextView>(R.id.drawingErrorLabel)
        val undoButton = dialogView.findViewById<Button>(R.id.buttonDrawingUndo)
        val redoButton = dialogView.findViewById<Button>(R.id.buttonDrawingRedo)

        fun refreshUndoRedo() {
            undoButton.isEnabled = canvas.canUndo()
            redoButton.isEnabled = canvas.canRedo()
        }

        val apiClient = TimePlanApiClient(configRepository.load()!!)

        isDialogShowing = true
        val dialog = MaterialAlertDialogBuilder(this)
            .setBackground(ContextCompat.getDrawable(this, R.drawable.bg_dialog_paper))
            .setTitle(worker.displayName)
            .setView(dialogView)
            .create()
        dialog.setOnDismissListener { isDialogShowing = false }
        dialog.show()
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )

        canvas.post {
            val existingContent = existingEntry?.let { DrawingContentCodec.decode(it.content) }
            if (existingContent != null) {
                val scaled = scaleStrokes(
                    existingContent.strokes,
                    existingContent.canvas_width, existingContent.canvas_height,
                    canvas.width, canvas.height
                )
                canvas.loadStrokes(scaled)
            }
            refreshUndoRedo()
        }

        dialogView.findViewById<Button>(R.id.buttonDrawingClear).setOnClickListener {
            canvas.clear()
            refreshUndoRedo()
        }
        undoButton.setOnClickListener { canvas.undo(); refreshUndoRedo() }
        redoButton.setOnClickListener { canvas.redo(); refreshUndoRedo() }
        dialogView.findViewById<Button>(R.id.buttonDrawingCancel).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<Button>(R.id.buttonDrawingSave).setOnClickListener {
            val strokes = canvas.strokes()
            if (strokes.isEmpty()) {
                dialog.dismiss()
                return@setOnClickListener
            }
            val content = DrawingContent(canvas.width, canvas.height, strokes)
            val contentJson = DrawingContentCodec.encode(content)
            errorLabel.visibility = View.GONE
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    if (existingEntry != null) {
                        apiClient.updateEntry(existingEntry.id, contentJson, existingEntry.revision)
                    } else {
                        apiClient.createEntry(cellId, "drawing", contentJson)
                    }
                }
                when (result) {
                    is ApiResult.Success -> {
                        dialog.dismiss()
                        loadCurrentWeek()
                    }
                    is ApiResult.Error -> {
                        errorLabel.text = "Speichern fehlgeschlagen (${result.code})"
                        errorLabel.visibility = View.VISIBLE
                    }
                    is ApiResult.NetworkFailure -> {
                        errorLabel.text = "Server nicht erreichbar: ${result.message}"
                        errorLabel.visibility = View.VISIBLE
                    }
                }
            }
        }
    }
```

- [ ] **Step 10: Run tests to verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "de.fs.timeplan.week.WeekActivityTest"`
Expected: PASS (all existing tests + 4 new ones)

- [ ] **Step 11: Run the full Android test suite and build**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 12: Commit**

```bash
git add android/app/src/main/java/de/fs/timeplan/week/WeekPresenter.kt \
        android/app/src/test/java/de/fs/timeplan/week/WeekPresenterTest.kt \
        android/app/src/main/java/de/fs/timeplan/WeekActivity.kt \
        android/app/src/test/java/de/fs/timeplan/week/WeekActivityTest.kt \
        android/app/src/main/res/layout/dialog_drawing.xml \
        android/app/src/main/res/values/strings.xml
git commit -m "feat: wire handwriting drawing editor into real-mode Monteur cell taps"
```

---

### Task 5: Live-Vorschau im Android-Wochenraster

**Files:**
- Modify: `android/app/src/main/java/de/fs/timeplan/grid/WeekRow.kt`
- Modify: `android/app/src/main/java/de/fs/timeplan/grid/WeekGridBuilder.kt`
- Modify: `android/app/src/test/java/de/fs/timeplan/grid/WeekGridBuilderTest.kt`
- Create: `android/app/src/main/java/de/fs/timeplan/drawing/DrawingThumbnailTextView.kt`
- Modify: `android/app/src/main/java/de/fs/timeplan/grid/WeekGridAdapter.kt`
- Modify: `android/app/src/test/java/de/fs/timeplan/grid/WeekGridAdapterTest.kt`

**Interfaces:**
- Consumes: `DrawingContent`, `DrawingContentCodec` (Task 1)
- Produces: nichts für spätere Tasks.

- [ ] **Step 1: Write the failing test for `WeekGridBuilder`**

Modify `android/app/src/test/java/de/fs/timeplan/grid/WeekGridBuilderTest.kt` — add a `drawingEntry` helper and a new test, plus extend the existing first test's assertions:

```kotlin
package de.fs.timeplan.grid

import de.fs.timeplan.drawing.DrawingContentCodec
import de.fs.timeplan.model.Entry
import de.fs.timeplan.model.Worker
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class WeekGridBuilderTest {

    private val json = Json

    private fun textEntry(cellId: String, text: String) = Entry(
        id = "e-1", cell_id = cellId, type = "text", author_type = "web", author_id = "web-admin",
        content = json.parseToJsonElement("""{"text":"$text"}"""),
        conflict_of = null, created_at = "now", updated_at = "now", revision = 1
    )

    private fun drawingEntry(cellId: String) = Entry(
        id = "e-2", cell_id = cellId, type = "drawing", author_type = "tablet", author_id = "tablet-01",
        content = json.parseToJsonElement(
            """{"canvas_width":100,"canvas_height":50,
                "strokes":[{"color":"#201A10","base_width":4.0,
                            "points":[{"x":1.0,"y":2.0,"pressure":0.5,"time":0}]}]}"""),
        conflict_of = null, created_at = "now", updated_at = "now", revision = 1
    )

    @Test
    fun `builds monteur rows, separator, and azubi rows with cell text placed correctly`() {
        val monteur = Worker("w-1", "144", "Albrecht", "monteur", 1, true, 1)
        val azubi = Worker("w-2", "501", "Petersen", "azubi", 1, true, 1)
        val dates = listOf("2026-07-27", "2026-07-28", "2026-07-29", "2026-07-30", "2026-07-31", "2026-08-01", "2026-08-02")
        val entries = listOf(textEntry("2026-W31_w-1_2026-07-30", "Baustelle A"))

        val rows = WeekGridBuilder.build("2026-W31", dates, listOf(monteur, azubi), entries)

        assertEquals(3, rows.size)
        val monteurRow = rows[0] as WeekRow.Monteur
        assertEquals("144 Albrecht", monteurRow.displayName)
        assertEquals("Baustelle A", monteurRow.cellTexts[3])
        assertEquals(null, monteurRow.cellTexts[0])
        assertTrue(rows[1] is WeekRow.Separator)
        val azubiRow = rows[2] as WeekRow.Azubi
        assertEquals("501 Petersen", azubiRow.displayName)
    }

    @Test
    fun `ignores inactive workers`() {
        val inactiveMonteur = Worker("w-1", "144", "Albrecht", "monteur", 1, false, 1)
        val rows = WeekGridBuilder.build("2026-W31", List(7) { "2026-07-27" }, listOf(inactiveMonteur), emptyList())
        assertEquals(1, rows.size)
        assertTrue(rows[0] is WeekRow.Separator)
    }

    @Test
    fun `handles a 5-day week without saturday and sunday columns`() {
        val monteur = Worker("w-1", "144", "Albrecht", "monteur", 1, true, 1)
        val dates = listOf("2026-07-27", "2026-07-28", "2026-07-29", "2026-07-30", "2026-07-31")

        val rows = WeekGridBuilder.build("2026-W31", dates, listOf(monteur), emptyList())

        val monteurRow = rows[0] as WeekRow.Monteur
        assertEquals(5, monteurRow.cellTexts.size)
    }

    @Test
    fun `a drawing entry is exposed as cellDrawings and not mixed into cellTexts`() {
        val monteur = Worker("w-1", "144", "Albrecht", "monteur", 1, true, 1)
        val dates = listOf("2026-07-27", "2026-07-28", "2026-07-29", "2026-07-30", "2026-07-31", "2026-08-01", "2026-08-02")
        val entries = listOf(drawingEntry("2026-W31_w-1_2026-07-30"))

        val rows = WeekGridBuilder.build("2026-W31", dates, listOf(monteur), entries)

        val monteurRow = rows[0] as WeekRow.Monteur
        assertNull(monteurRow.cellTexts[3])
        val drawing = monteurRow.cellDrawings[3]
        assertEquals(100, drawing?.canvas_width)
        assertEquals(1, drawing?.strokes?.size)
    }

    @Test
    fun `a monteur cell can show both a web text and a drawing stacked`() {
        val monteur = Worker("w-1", "144", "Albrecht", "monteur", 1, true, 1)
        val dates = listOf("2026-07-27", "2026-07-28", "2026-07-29", "2026-07-30", "2026-07-31", "2026-08-01", "2026-08-02")
        val entries = listOf(
            textEntry("2026-W31_w-1_2026-07-30", "Hinweis"),
            drawingEntry("2026-W31_w-1_2026-07-30")
        )

        val rows = WeekGridBuilder.build("2026-W31", dates, listOf(monteur), entries)

        val monteurRow = rows[0] as WeekRow.Monteur
        assertEquals("Hinweis", monteurRow.cellTexts[3])
        assertEquals(100, monteurRow.cellDrawings[3]?.canvas_width)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "de.fs.timeplan.grid.WeekGridBuilderTest"`
Expected: FAIL (compile error — `WeekRow.Monteur.cellDrawings` doesn't exist yet)

- [ ] **Step 3: Extend `WeekRow`**

Modify `android/app/src/main/java/de/fs/timeplan/grid/WeekRow.kt` — full new content:

```kotlin
package de.fs.timeplan.grid

import de.fs.timeplan.drawing.DrawingContent

sealed class WeekRow {
    data class Monteur(
        val workerId: String,
        val displayName: String,
        val cellTexts: List<String?>,
        val cellDrawings: List<DrawingContent?> = emptyList()
    ) : WeekRow()
    data class Azubi(val workerId: String, val displayName: String, val cellTexts: List<String?>) : WeekRow()
    object Separator : WeekRow()
}
```

- [ ] **Step 4: Update `WeekGridBuilder`**

Modify `android/app/src/main/java/de/fs/timeplan/grid/WeekGridBuilder.kt` — full new content:

```kotlin
package de.fs.timeplan.grid

import de.fs.timeplan.drawing.DrawingContent
import de.fs.timeplan.drawing.DrawingContentCodec
import de.fs.timeplan.model.Entry
import de.fs.timeplan.model.Worker
import de.fs.timeplan.model.textOrNull
import de.fs.timeplan.net.WeekId

object WeekGridBuilder {
    fun build(weekId: String, dates: List<String>, workers: List<Worker>, entries: List<Entry>): List<WeekRow> {
        val byCell: Map<String, List<Entry>> = entries.groupBy { it.cell_id }

        fun cellText(workerId: String, date: String): String? {
            val cellEntries = byCell[WeekId.makeCellId(weekId, workerId, date)].orEmpty()
            val texts = cellEntries.mapNotNull { it.textOrNull() }
            return texts.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }

        fun cellDrawing(workerId: String, date: String): DrawingContent? {
            val cellEntries = byCell[WeekId.makeCellId(weekId, workerId, date)].orEmpty()
            val drawingEntry = cellEntries.firstOrNull { it.type == "drawing" } ?: return null
            return DrawingContentCodec.decode(drawingEntry.content)
        }

        val active = workers.filter { it.active }
        val monteure = active.filter { it.isMonteur }.sortedBy { it.position }
        val azubis = active.filter { it.isAzubi }.sortedBy { it.position }

        val rows = mutableListOf<WeekRow>()
        for (w in monteure) {
            rows += WeekRow.Monteur(
                w.id, w.displayName,
                dates.map { cellText(w.id, it) },
                dates.map { cellDrawing(w.id, it) }
            )
        }
        rows += WeekRow.Separator
        for (w in azubis) {
            rows += WeekRow.Azubi(w.id, w.displayName, dates.map { cellText(w.id, it) })
        }
        return rows
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "de.fs.timeplan.grid.WeekGridBuilderTest"`
Expected: PASS (5 tests)

- [ ] **Step 6: Create `DrawingThumbnailTextView`**

Create `android/app/src/main/java/de/fs/timeplan/drawing/DrawingThumbnailTextView.kt`:

```kotlin
package de.fs.timeplan.drawing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.widget.TextView

class DrawingThumbnailTextView(context: Context) : TextView(context) {

    var drawingContent: DrawingContent? = null
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#201A10")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val content = drawingContent ?: return
        if (content.canvas_width <= 0 || content.canvas_height <= 0 || width <= 0 || height <= 0) return
        val scaleX = width.toFloat() / content.canvas_width
        val scaleY = height.toFloat() / content.canvas_height
        canvas.save()
        canvas.scale(scaleX, scaleY)
        for (stroke in content.strokes) {
            val path = Path()
            stroke.points.firstOrNull()?.let { path.moveTo(it.x, it.y) }
            for (point in stroke.points.drop(1)) path.lineTo(point.x, point.y)
            canvas.drawPath(path, paint)
        }
        canvas.restore()
    }
}
```

- [ ] **Step 7: Write the failing test for `WeekGridAdapter`**

Append to `android/app/src/test/java/de/fs/timeplan/grid/WeekGridAdapterTest.kt`, inside the class body:

```kotlin
    @Test
    fun `passes the cell drawing content to the thumbnail view`() {
        val drawing = de.fs.timeplan.drawing.DrawingContent(
            10, 10, listOf(de.fs.timeplan.drawing.Stroke(
                "#201A10", 4f, listOf(de.fs.timeplan.drawing.StrokePoint(1f, 1f, 1f, 0L))
            ))
        )
        val rows = listOf(
            WeekRow.Monteur(
                "w-1", "144 Albrecht",
                listOf(null, null, null, null, null, null, null),
                listOf(drawing, null, null, null, null, null, null)
            )
        )
        val adapter = WeekGridAdapter(rows)

        val parent = FrameLayout(ApplicationProvider.getApplicationContext())
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0)) as WeekGridAdapter.WorkerRowViewHolder
        adapter.onBindViewHolder(holder, 0)

        val cellsContainer = holder.itemView.findViewById<LinearLayout>(R.id.rowCellsContainer)
        val firstCell = cellsContainer.getChildAt(0) as de.fs.timeplan.drawing.DrawingThumbnailTextView
        assertEquals(drawing, firstCell.drawingContent)
    }
```

- [ ] **Step 8: Run tests to verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "de.fs.timeplan.grid.WeekGridAdapterTest"`
Expected: FAIL (compile error — cells aren't `DrawingThumbnailTextView` yet)

- [ ] **Step 9: Wire the thumbnail into `WeekGridAdapter`**

Modify `android/app/src/main/java/de/fs/timeplan/grid/WeekGridAdapter.kt`:

Add import: `import de.fs.timeplan.drawing.DrawingThumbnailTextView`

In `onBindViewHolder`, replace the two `bind(...)` call sites:

```kotlin
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is WeekRow.Monteur -> (holder as WorkerRowViewHolder)
                .bind(row.workerId, row.displayName, row.cellTexts, compact = false, onCellClick, dragFillController, row.cellDrawings)
            is WeekRow.Azubi -> (holder as WorkerRowViewHolder)
                .bind(row.workerId, row.displayName, row.cellTexts, compact = true, onCellClick, dragFillController, emptyList())
            WeekRow.Separator -> Unit
        }
    }
```

In `WorkerRowViewHolder`, change the `bind` signature (add the trailing parameter) and the cell-construction block:

```kotlin
        fun bind(
            workerId: String,
            name: String,
            cellTexts: List<String?>,
            compact: Boolean,
            onCellClick: ((String, Int) -> Unit)?,
            dragFillController: DragFillController?,
            cellDrawings: List<de.fs.timeplan.drawing.DrawingContent?> = emptyList()
        ) {
            nameView.text = name
            cellsContainer.removeAllViews()
            val context = cellsContainer.context
            val minHeightPx = dp(context, if (compact) 40 else 64)
            val marginPx = dp(context, 3)

            val cells = cellTexts.mapIndexed { index, text ->
                val status = if (compact) AzubiStatus.from(text) else null
                val hasText = !text.isNullOrBlank()

                DrawingThumbnailTextView(context).apply {
                    this.text = text.orEmpty()
                    this.drawingContent = cellDrawings.getOrNull(index)
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
```

(Nur der Typ von `TextView(context)` → `DrawingThumbnailTextView(context)` und die neue `this.drawingContent = ...`-Zeile ändern sich; der Rest bleibt exakt wie zuvor. `attachDragFillTouchHandling` und `rowCells: List<TextView>` bleiben unverändert kompatibel, da `DrawingThumbnailTextView : TextView`.)

- [ ] **Step 10: Run tests to verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "de.fs.timeplan.grid.WeekGridAdapterTest"`
Expected: PASS (3 tests)

- [ ] **Step 11: Run the full Android test suite and build**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 12: Commit**

```bash
git add android/app/src/main/java/de/fs/timeplan/grid/WeekRow.kt \
        android/app/src/main/java/de/fs/timeplan/grid/WeekGridBuilder.kt \
        android/app/src/test/java/de/fs/timeplan/grid/WeekGridBuilderTest.kt \
        android/app/src/main/java/de/fs/timeplan/drawing/DrawingThumbnailTextView.kt \
        android/app/src/main/java/de/fs/timeplan/grid/WeekGridAdapter.kt \
        android/app/src/test/java/de/fs/timeplan/grid/WeekGridAdapterTest.kt
git commit -m "feat: render live handwriting thumbnails in the Android week grid"
```

---

### Task 6: Live-Vorschau im WebUI (Inline-SVG)

**Files:**
- Modify: `server/app/templates/partials/cell.html`
- Modify: `server/app/static/style.css`
- Modify: `server/tests/test_web_week.py`

**Interfaces:**
- Consumes: nichts Neues — `entry.content` ist für `drawing`-Entries bereits als geparstes Dict im Template verfügbar (`entries_repo._row_to_dict` parst `content` bereits beim Lesen aus der DB).
- Produces: nichts für spätere Tasks (letzter Task).

- [ ] **Step 1: Write the failing test**

Append to `server/tests/test_web_week.py`:

```python
def test_week_page_renders_drawing_as_inline_svg(admin):
    conn = admin.app.state.db
    m = workers.create_worker(conn, "144", "Albrecht", "monteur")
    cell_id = f"2026-W31_{m['id']}_2026-07-30"
    entries_repo.create_entry(
        conn, cell_id, "drawing",
        {"canvas_width": 300, "canvas_height": 120,
         "strokes": [{"color": "#201A10", "base_width": 4.0,
                      "points": [{"x": 10.0, "y": 20.0, "pressure": 0.5, "time": 0},
                                 {"x": 15.0, "y": 22.0, "pressure": 0.6, "time": 12}]}]},
        "tablet", "tablet-01", admin.app.state.settings)
    r = admin.get("/week/2026-W31")
    assert r.status_code == 200
    assert '<svg class="entry-drawing"' in r.text
    assert 'viewBox="0 0 300 120"' in r.text
    assert "10.0,20.0" in r.text
    assert "Zeichnung" not in r.text


def test_week_page_stacks_text_and_drawing_in_the_same_cell(admin):
    conn = admin.app.state.db
    m = workers.create_worker(conn, "144", "Albrecht", "monteur")
    cell_id = f"2026-W31_{m['id']}_2026-07-30"
    entries_repo.create_entry(
        conn, cell_id, "text", {"text": "Hinweis"}, "web", "web-admin", admin.app.state.settings)
    entries_repo.create_entry(
        conn, cell_id, "drawing",
        {"canvas_width": 50, "canvas_height": 50,
         "strokes": [{"color": "#201A10", "base_width": 4.0,
                      "points": [{"x": 1.0, "y": 1.0, "pressure": 1.0, "time": 0}]}]},
        "tablet", "tablet-01", admin.app.state.settings)
    r = admin.get("/week/2026-W31")
    assert r.status_code == 200
    assert "Hinweis" in r.text
    assert '<svg class="entry-drawing"' in r.text
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd server && .venv/bin/python -m pytest tests/test_web_week.py -k drawing -v`
Expected: FAIL (`'<svg class="entry-drawing"' in r.text` is False — template still renders the placeholder)

- [ ] **Step 3: Update the template**

Modify `server/app/templates/partials/cell.html` — replace the `{% else %}` branch:

```html
{% if worker.category == "azubi" %}
<td class="cell" id="cell-{{ cell_id }}"
    hx-get="/web/cells/{{ cell_id }}/edit" hx-trigger="click"
    hx-target="#cell-dialog-body" hx-swap="innerHTML">
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
    {% elif entry.type == "drawing" %}
      <svg class="entry-drawing"
           viewBox="0 0 {{ entry.content.canvas_width }} {{ entry.content.canvas_height }}"
           preserveAspectRatio="xMidYMid meet">
        {% for stroke in entry.content.strokes %}
        <polyline points="{% for p in stroke.points %}{{ p.x }},{{ p.y }} {% endfor %}"
                  fill="none" stroke="{{ stroke.color }}" stroke-width="{{ stroke.base_width }}"
                  stroke-linecap="round" stroke-linejoin="round" />
        {% endfor %}
      </svg>
    {% endif %}
  {% endfor %}
</td>
```

- [ ] **Step 4: Update the CSS**

Modify `server/app/static/style.css` — replace the `.entry-drawing` rule:

```css
.entry-text { color: var(--ink); }
.entry-drawing { display: block; width: 100%; height: 34px; margin-top: 2px; }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd server && .venv/bin/python -m pytest tests/test_web_week.py -v`
Expected: PASS (all tests in the file, including the 2 new ones)

- [ ] **Step 6: Run the full server test suite**

Run: `cd server && .venv/bin/python -m pytest -q`
Expected: all tests pass, no regressions

- [ ] **Step 7: Commit**

```bash
git add server/app/templates/partials/cell.html server/app/static/style.css server/tests/test_web_week.py
git commit -m "feat: render handwriting entries as inline SVG in the WebUI grid"
```

---

## Nach Abschluss aller Tasks

Finale Whole-Branch-Review (per `subagent-driven-development`), danach `finishing-a-development-branch`. Manuelle Live-Verifikation im Emulator (S-Pen ist im Emulator nicht simulierbar, aber Finger-Touch via `adb shell input swipe`/Maus-Drag deckt denselben Code-Pfad ab, da beide Eingabearten identisch behandelt werden):

- Server lokal starten, Emulator mit `SettingsActivity` auf den lokalen Server + einen gültigen Device-Token konfigurieren (kein Demo-Modus).
- Monteur-Zelle antippen → Zeichenfläche öffnet sich nahezu vollflächig.
- Zeichnen (Maus-Drag im Emulator simuliert Finger), Rückgängig/Wiederholen/Löschen prüfen.
- Speichern → Zelle zeigt die Zeichnung als Vorschau, Dialog schließt.
- Zelle erneut antippen → vorhandene Zeichnung wird geladen und ist weiterbearbeitbar.
- WebUI im Browser öffnen → dieselbe Woche zeigt die Zeichnung als Inline-SVG in der Zelle.
- Azubi-Zelle antippen → weiterhin nur der bestehende Status-Picker, kein Zeichnen möglich.
- Server stoppen/URL falsch konfigurieren, erneut speichern versuchen → Fehlermeldung erscheint, Zeichnung bleibt in der offenen Fläche erhalten (kein Datenverlust).
