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

    init {
        // Halo behind the text glyphs (matches R.color.paper_bg) so the cell text stays
        // legible on top of a busy drawing, regardless of what's drawn underneath it.
        setShadowLayer(4f, 0f, 0f, Color.parseColor("#F9F1E3"))
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#201A10")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f
    }

    override fun onDraw(canvas: Canvas) {
        val content = drawingContent
        if (content != null && width > 0 && height > 0) {
            val bbox = boundingBoxWithPadding(content.strokes)
            if (bbox != null) {
                val scaleX = width / bbox.width
                val scaleY = height / bbox.height
                for (stroke in content.strokes) {
                    canvas.drawPath(scaledPathFor(stroke, bbox, scaleX, scaleY), paint)
                }
            }
        }
        // Draw the cell's text last so it always sits as the top-most layer, readable
        // regardless of what the handwriting underneath looks like.
        super.onDraw(canvas)
    }

    private fun scaledPathFor(stroke: Stroke, bbox: BoundingBox, scaleX: Float, scaleY: Float): Path {
        val path = Path()
        val points = stroke.points
        points.firstOrNull()?.let {
            path.moveTo((it.x - bbox.minX) * scaleX, (it.y - bbox.minY) * scaleY)
        }
        for (point in points.drop(1)) {
            path.lineTo((point.x - bbox.minX) * scaleX, (point.y - bbox.minY) * scaleY)
        }
        return path
    }
}
