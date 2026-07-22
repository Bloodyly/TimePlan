package de.fs.timeplan

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.fs.timeplan.config.ConfigRepository
import de.fs.timeplan.drawing.DrawingContent
import de.fs.timeplan.drawing.DrawingContentCodec
import de.fs.timeplan.drawing.DrawingView
import de.fs.timeplan.drawing.scaleStrokes
import de.fs.timeplan.grid.AzubiStatus
import de.fs.timeplan.grid.WeekGridAdapter
import de.fs.timeplan.model.Entry
import de.fs.timeplan.model.Worker
import de.fs.timeplan.model.textOrNull
import de.fs.timeplan.net.ApiResult
import de.fs.timeplan.net.DemoApi
import de.fs.timeplan.net.TimePlanApi
import de.fs.timeplan.net.TimePlanApiClient
import de.fs.timeplan.net.WeekId
import de.fs.timeplan.settings.SettingsActivity
import de.fs.timeplan.ui.enableImmersiveFullscreen
import de.fs.timeplan.week.WeekLoadResult
import de.fs.timeplan.week.WeekPresenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDate

private val WEEKDAY_LABELS = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

class WeekActivity : AppCompatActivity() {

    private lateinit var configRepository: ConfigRepository
    private lateinit var weekLabel: TextView
    private lateinit var demoBadge: TextView
    private lateinit var errorLabel: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var dayHeaderCells: LinearLayout
    private lateinit var weekContentContainer: de.fs.timeplan.grid.SwipeInterceptLayout
    private lateinit var dragFillOverlay: de.fs.timeplan.grid.DragFillOverlay
    private lateinit var dragFillController: de.fs.timeplan.grid.DragFillController
    private val adapter = WeekGridAdapter()
    private var currentWeekId: String = WeekId.currentWeekId()
    private var isDemoMode: Boolean = false
    private var currentDates: List<String> = emptyList()
    private var currentWorkers: List<Worker> = emptyList()
    private var currentEntries: List<Entry> = emptyList()
    private var isDialogShowing: Boolean = false
    private var isSwipeAnimating: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveFullscreen()
        setContentView(R.layout.activity_week)
        configRepository = ConfigRepository(applicationContext)

        weekLabel = findViewById(R.id.weekLabel)
        demoBadge = findViewById(R.id.demoBadge)
        errorLabel = findViewById(R.id.errorLabel)
        dayHeaderCells = findViewById(R.id.dayHeaderCells)
        weekContentContainer = findViewById(R.id.weekContentContainer)
        weekContentContainer.isGestureEnabled = { !isDialogShowing && !isSwipeAnimating }
        weekContentContainer.onSwipe = { direction -> onSwipeDetected(direction) }
        recyclerView = findViewById(R.id.weekRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        val dragFillContainer = findViewById<android.widget.FrameLayout>(R.id.weekGridOverlayContainer)
        val dragFillShaft = findViewById<View>(R.id.dragFillArrowShaft)
        val dragFillHead = findViewById<TextView>(R.id.dragFillArrowHead)
        dragFillOverlay = de.fs.timeplan.grid.DragFillOverlay(dragFillContainer, dragFillShaft, dragFillHead)
        dragFillController = de.fs.timeplan.grid.DragFillController(dragFillOverlay, ::onDragFillCommit)

        val divider = DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        ContextCompat.getDrawable(this, R.drawable.divider_paper_rule)?.let { divider.setDrawable(it) }
        recyclerView.addItemDecoration(divider)

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
            { workerId, dateIndex -> onMonteurCellClick(workerId, dateIndex) }
        }
        adapter.dragFillController = if (isDemoMode) dragFillController else null
        loadCurrentWeek()
    }

    override fun onStop() {
        super.onStop()
        // Falls die Aktivität mitten in einer Wisch-Animation pausiert/gestoppt
        // wird, läuft withEndAction ggf. nie - ohne diesen Reset bliebe die
        // Geste für den Rest der Activity-Instanz gesperrt.
        isSwipeAnimating = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveFullscreen()
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
                currentWorkers = result.workers
                currentEntries = result.entries
                renderDayHeader(result.dates)
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

    private fun onSwipeDetected(direction: de.fs.timeplan.week.SwipeDirection) {
        isSwipeAnimating = true
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
                weekContentContainer.animate()
                    .translationX(0f)
                    .setDuration(200)
                    .withEndAction { isSwipeAnimating = false }
                    .start()
            }
            .start()
    }

    private fun onDemoCellClick(workerId: String, dateIndex: Int) {
        val dateIso = currentDates.getOrNull(dateIndex) ?: return
        val workers = (DemoApi.getWorkers() as? ApiResult.Success)?.data?.workers.orEmpty()
        val worker = workers.firstOrNull { it.id == workerId } ?: return
        val cellId = WeekId.makeCellId(currentWeekId, workerId, dateIso)

        if (worker.isAzubi) {
            showAzubiPicker(worker, cellId, workers)
        } else {
            showMonteurEditor(worker, cellId)
        }
    }

    private fun onMonteurCellClick(workerId: String, dateIndex: Int) {
        val worker = currentWorkers.firstOrNull { it.id == workerId } ?: return
        if (!worker.isMonteur) return
        val dateIso = currentDates.getOrNull(dateIndex) ?: return
        val cellId = WeekId.makeCellId(currentWeekId, workerId, dateIso)
        val existingDrawing = currentEntries.firstOrNull { it.cell_id == cellId && it.type == "drawing" }
        val existingText = currentEntries.firstOrNull { it.cell_id == cellId && it.type == "text" }?.textOrNull()
        showDrawingEditor(worker, cellId, existingDrawing, existingText)
    }

    private fun showDrawingEditor(worker: Worker, cellId: String, existingEntry: Entry?, backgroundText: String? = null) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_drawing, null)
        val canvas = dialogView.findViewById<DrawingView>(R.id.drawingCanvas)
        val backgroundTextView = dialogView.findViewById<TextView>(R.id.drawingBackgroundText)
        if (!backgroundText.isNullOrBlank()) {
            backgroundTextView.text = backgroundText
            backgroundTextView.visibility = View.VISIBLE
        }
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
                if (existingEntry == null) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                errorLabel.visibility = View.GONE
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        apiClient.deleteEntry(existingEntry.id)
                    }
                    when (result) {
                        is ApiResult.Success -> {
                            dialog.dismiss()
                            loadCurrentWeek()
                        }
                        is ApiResult.Error -> {
                            errorLabel.text = "Löschen fehlgeschlagen (${result.code})"
                            errorLabel.visibility = View.VISIBLE
                        }
                        is ApiResult.NetworkFailure -> {
                            errorLabel.text = "Server nicht erreichbar: ${result.message}"
                            errorLabel.visibility = View.VISIBLE
                        }
                    }
                }
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

    private fun showAzubiPicker(worker: Worker, cellId: String, workers: List<Worker>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_azubi_picker, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.pickerContainer)

        isDialogShowing = true
        val dialog = MaterialAlertDialogBuilder(this)
            .setBackground(ContextCompat.getDrawable(this, R.drawable.bg_dialog_paper))
            .setTitle(worker.displayName)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnDismissListener { isDialogShowing = false }

        fun apply(value: String) {
            DemoApi.putEntry(cellId, value)
            loadCurrentWeek()
            dialog.dismiss()
        }

        container.addView(pickerSectionLabel(getString(R.string.azubi_picker_title_status), topMargin = 0))
        for (status in AzubiStatus.entries) {
            container.addView(pickerOption(status.label, status.chipBackground, R.color.on_status) { apply(status.label) })
        }

        val monteure = workers.filter { it.isMonteur && it.active }
        container.addView(pickerSectionLabel(getString(R.string.azubi_picker_title_assign), topMargin = 16))
        for (monteur in monteure) {
            container.addView(pickerOption(monteur.displayName, R.drawable.bg_chip_assigned, R.color.ink) { apply(monteur.displayName) })
        }

        container.addView(pickerSectionLabel("", topMargin = 12))
        container.addView(pickerOption(getString(R.string.azubi_picker_clear), null, R.color.ink_muted) { apply("") })

        dialog.show()
    }

    private fun pickerSectionLabel(text: String, topMargin: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@WeekActivity, R.color.ink_muted))
        textSize = 12f
        typeface = Typeface.create(typeface, Typeface.BOLD)
        letterSpacing = 0.06f
        setPadding(dp(4), dp(topMargin), dp(4), dp(6))
    }

    private fun pickerOption(
        label: String,
        background: Int?,
        textColorRes: Int,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        setTextColor(ContextCompat.getColor(this@WeekActivity, textColorRes))
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        minHeight = dp(48)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setBackgroundResource(background ?: R.drawable.bg_cell_empty)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
        isClickable = true
        foreground = resolveSelectableForeground(this@WeekActivity)
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun resolveSelectableForeground(context: Context): android.graphics.drawable.Drawable? {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
        return ContextCompat.getDrawable(context, typedValue.resourceId)
    }
}
