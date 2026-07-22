package de.fs.timeplan.grid

import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import de.fs.timeplan.R
import de.fs.timeplan.drawing.DrawingThumbnailTextView

private const val VIEW_TYPE_WORKER = 0
private const val VIEW_TYPE_SEPARATOR = 1

class WeekGridAdapter(private var rows: List<WeekRow> = emptyList()) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var onCellClick: ((workerId: String, dateIndex: Int) -> Unit)? = null
    var dragFillController: DragFillController? = null

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
                .bind(row.workerId, row.displayName, row.cellTexts, compact = false, onCellClick, dragFillController, row.cellDrawings)
            is WeekRow.Azubi -> (holder as WorkerRowViewHolder)
                .bind(row.workerId, row.displayName, row.cellTexts, compact = true, onCellClick, dragFillController, emptyList())
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
                val hasContent = !text.isNullOrBlank() || cellDrawings.getOrNull(index) != null

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
                        compact && hasContent -> {
                            gravity = Gravity.CENTER
                            setBackgroundResource(R.drawable.bg_chip_assigned)
                            setTextColor(ContextCompat.getColor(context, R.color.ink))
                        }
                        hasContent -> {
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
}
