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

data class BoundingBox(val minX: Float, val minY: Float, val width: Float, val height: Float)

fun boundingBoxWithPadding(strokes: List<Stroke>, paddingFraction: Float = 0.1f): BoundingBox? {
    val points = strokes.flatMap { it.points }
    if (points.isEmpty()) return null
    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }
    val rawWidth = (maxX - minX).coerceAtLeast(1f)
    val rawHeight = (maxY - minY).coerceAtLeast(1f)
    val padX = rawWidth * paddingFraction
    val padY = rawHeight * paddingFraction
    return BoundingBox(minX - padX, minY - padY, rawWidth + 2 * padX, rawHeight + 2 * padY)
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
