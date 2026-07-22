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
