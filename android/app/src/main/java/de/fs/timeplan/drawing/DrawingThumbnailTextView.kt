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
