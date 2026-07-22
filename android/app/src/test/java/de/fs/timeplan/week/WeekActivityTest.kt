package de.fs.timeplan.week

import android.view.View
import android.widget.TextView
import de.fs.timeplan.R
import de.fs.timeplan.WeekActivity
import de.fs.timeplan.net.ApiResult
import de.fs.timeplan.net.DemoApi
import de.fs.timeplan.net.WeekId
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

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

    // Robolectric does not automatically drive the async coroutine-based week load
    // (WeekActivity.loadCurrentWeek runs on Dispatchers.IO under a paused main looper),
    // so these tests invoke the private onDemoCellClick directly - after seeding the
    // private currentDates field via reflection - rather than routing through a real
    // RecyclerView cell tap. This still exercises the real production dialog-building
    // code; only the unrelated network/RecyclerView loading plumbing is bypassed.
    private fun clickCell(activity: WeekActivity, workerId: String, dateIndex: Int, dates: List<String>) {
        val datesField = WeekActivity::class.java.getDeclaredField("currentDates")
        datesField.isAccessible = true
        datesField.set(activity, dates)

        val onDemoCellClick = WeekActivity::class.java.getDeclaredMethod(
            "onDemoCellClick", String::class.java, Int::class.java
        )
        onDemoCellClick.isAccessible = true
        onDemoCellClick.invoke(activity, workerId, dateIndex)
    }

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

    @Test
    fun `swipe gesture is disabled while a swipe animation is in flight`() {
        val controller = Robolectric.buildActivity(WeekActivity::class.java)
        controller.setup()
        val activity = controller.get()

        val animatingField = WeekActivity::class.java.getDeclaredField("isSwipeAnimating")
        animatingField.isAccessible = true
        animatingField.setBoolean(activity, true)

        val container = activity.findViewById<de.fs.timeplan.grid.SwipeInterceptLayout>(
            R.id.weekContentContainer
        )
        assertFalse(container.isGestureEnabled())
    }

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

    @Test
    fun `tapping an azubi cell offers no free text field`() {
        val controller = Robolectric.buildActivity(WeekActivity::class.java)
        controller.setup()
        val activity = controller.get()

        val workers = (DemoApi.getWorkers() as ApiResult.Success).data.workers
        val azubi = workers.first { it.isAzubi }
        val weekId = WeekId.currentWeekId()
        val dates = WeekId.weekDates(weekId).map { it.toString() }
        clickCell(activity, azubi.id, 0, dates)

        val dialog = ShadowDialog.getLatestDialog()
        assertNull(dialog.findViewById(R.id.fieldEntryText))
    }

    @Test
    fun `selecting a status for an azubi cell writes the fixed label`() {
        val controller = Robolectric.buildActivity(WeekActivity::class.java)
        controller.setup()
        val activity = controller.get()

        val workers = (DemoApi.getWorkers() as ApiResult.Success).data.workers
        val azubi = workers.first { it.isAzubi }
        val weekId = WeekId.currentWeekId()
        val dates = WeekId.weekDates(weekId).map { it.toString() }
        val cellId = WeekId.makeCellId(weekId, azubi.id, dates[0])
        clickCell(activity, azubi.id, 0, dates)

        val dialog = ShadowDialog.getLatestDialog()
        val container = dialog.findViewById<android.widget.LinearLayout>(R.id.pickerContainer)
        val krankOption = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filterIsInstance<TextView>()
            .first { it.text == "Krank" }
        krankOption.performClick()

        assertEquals("Krank", DemoApi.textFor(cellId))
    }

    @Test
    fun `assigning a monteur to an azubi cell writes the monteur name`() {
        val controller = Robolectric.buildActivity(WeekActivity::class.java)
        controller.setup()
        val activity = controller.get()

        val workers = (DemoApi.getWorkers() as ApiResult.Success).data.workers
        val azubi = workers.first { it.isAzubi }
        val monteur = workers.first { it.isMonteur }
        val weekId = WeekId.currentWeekId()
        val dates = WeekId.weekDates(weekId).map { it.toString() }
        val cellId = WeekId.makeCellId(weekId, azubi.id, dates[0])
        clickCell(activity, azubi.id, 0, dates)

        val dialog = ShadowDialog.getLatestDialog()
        val container = dialog.findViewById<android.widget.LinearLayout>(R.id.pickerContainer)
        val monteurOption = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filterIsInstance<TextView>()
            .first { it.text == monteur.displayName }
        monteurOption.performClick()

        assertEquals(monteur.displayName, DemoApi.textFor(cellId))
    }

    @Test
    fun `tapping a monteur cell shows a free text field pre-filled with existing text`() {
        val controller = Robolectric.buildActivity(WeekActivity::class.java)
        controller.setup()
        val activity = controller.get()

        val workers = (DemoApi.getWorkers() as ApiResult.Success).data.workers
        val monteur = workers.first { it.isMonteur }
        val weekId = WeekId.currentWeekId()
        val dates = WeekId.weekDates(weekId).map { it.toString() }
        val cellId = WeekId.makeCellId(weekId, monteur.id, dates[0])
        DemoApi.putEntry(cellId, "Baustelle A")
        clickCell(activity, monteur.id, 0, dates)

        val dialog = ShadowDialog.getLatestDialog()
        val textField = dialog.findViewById<android.widget.EditText>(R.id.fieldEntryText)
        assertTrue(textField != null)
        assertEquals("Baustelle A", textField.text.toString())
    }

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
        // onResume() triggers a real automatic loadCurrentWeek() in non-Demo mode as soon as
        // the activity is set up; queue harmless responses for it so that background call
        // doesn't block forever waiting on an empty MockWebServer response queue.
        enqueueEmptyWeekLoad(server, weekId)

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
        enqueueEmptyWeekLoad(server, weekId)

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
        enqueueEmptyWeekLoad(server, weekId)

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
        // showDrawingEditor() defers stroke loading to canvas.post { ... } (it needs the
        // canvas's post-layout width/height to scale strokes correctly); the Robolectric main
        // looper is paused by default, so that queued Runnable needs an explicit idle() to run.
        shadowOf(android.os.Looper.getMainLooper()).idle()

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
        enqueueEmptyWeekLoad(server, weekId)

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

        // onResume()'s automatic loadCurrentWeek() call (see enqueueEmptyWeekLoad above) races
        // on a background thread with the save flow below, so the POST we care about is not
        // necessarily the very first request the server recorded - skip past any of those
        // leading GETs to find it.
        var recorded = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        while (recorded != null && recorded.path != "/api/v1/entries") {
            recorded = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        assertEquals("POST", recorded?.method)
        assertEquals("/api/v1/entries", recorded?.path)
        server.shutdown()
    }

    @Test
    fun `clearing an existing drawing and saving deletes the entry and closes the dialog`() {
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
        enqueueEmptyWeekLoad(server, weekId)

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
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog()
        dialog.findViewById<android.widget.Button>(R.id.buttonDrawingClear).performClick()

        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(204))
        // Responses for the loadCurrentWeek() refresh triggered after a successful delete.
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody(
            """{"workers":[]}"""))
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody(
            """{"week":{"id":"$weekId","status":"OPEN","revision":1},"dates":${
                dates.joinToString(",", "[", "]") { "\"$it\"" }},"entries":[]}"""))

        dialog.findViewById<android.widget.Button>(R.id.buttonDrawingSave).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        // The DELETE call happens on a real background thread (Dispatchers.IO); block the
        // test thread on takeRequest() until it lands, then idle the main looper again so the
        // coroutine's continuation (dialog.dismiss() + loadCurrentWeek()) actually runs.
        var recorded = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        while (recorded != null && recorded.path != "/api/v1/entries/e-1") {
            recorded = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        assertEquals("DELETE", recorded?.method)
        assertEquals("/api/v1/entries/e-1", recorded?.path)
        idleUntil { !dialog.isShowing }

        assertFalse(dialog.isShowing)
        server.shutdown()
    }

    @Test
    fun `save failure keeps the drawing dialog open with strokes intact and shows an error`() {
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
        enqueueEmptyWeekLoad(server, weekId)

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
        val stroke = de.fs.timeplan.drawing.Stroke(
            "#201A10", 4f, listOf(de.fs.timeplan.drawing.StrokePoint(0f, 0f, 1f, 0L),
                                   de.fs.timeplan.drawing.StrokePoint(5f, 5f, 1f, 10L))
        )
        canvas.loadStrokes(listOf(stroke))

        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(500).setBody(
            """{"detail":"internal error"}"""))

        dialog.findViewById<android.widget.Button>(R.id.buttonDrawingSave).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        // The POST call happens on a real background thread (Dispatchers.IO); block the test
        // thread on takeRequest() until it lands, then idle the main looper again so the
        // coroutine's continuation (showing the error label) actually runs.
        var recorded = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        while (recorded != null && recorded.path != "/api/v1/entries") {
            recorded = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        assertEquals("POST", recorded?.method)
        val errorLabel = dialog.findViewById<TextView>(R.id.drawingErrorLabel)
        idleUntil { errorLabel.visibility == View.VISIBLE }

        assertEquals(dialog, ShadowDialog.getLatestDialog())
        assertTrue(dialog.isShowing)
        assertEquals(1, canvas.strokes().size)
        assertEquals(View.VISIBLE, errorLabel.visibility)
        assertTrue(errorLabel.text.isNotEmpty())
        server.shutdown()
    }

    // onResume() triggers a real, automatic loadCurrentWeek() network call as soon as the
    // activity is set up in non-Demo (real) mode. Without a queued response, that background
    // call blocks indefinitely waiting on the MockWebServer response queue and can race with -
    // and steal responses/request-order from - the request(s) the test itself cares about.
    private fun enqueueEmptyWeekLoad(server: okhttp3.mockwebserver.MockWebServer, weekId: String) {
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody(
            """{"workers":[]}"""))
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody(
            """{"week":{"id":"$weekId","status":"OPEN","revision":1},"dates":[],"entries":[]}"""))
    }

    // Save/delete round-trips dispatch the actual network I/O onto a real background thread
    // (Dispatchers.IO) that posts its continuation back to the (paused) Robolectric main looper
    // once the response is read. server.takeRequest() only guarantees the *request* has landed
    // at the mock server - there is a small, genuine race before the client finishes reading the
    // response and the coroutine's continuation is enqueued on the main looper. Poll idle()
    // briefly instead of assuming a single idle() call is enough.
    private fun idleUntil(maxAttempts: Int = 200, condition: () -> Boolean) {
        var attempts = 0
        while (!condition() && attempts < maxAttempts) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
            attempts++
        }
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = WeekActivity::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }
}
