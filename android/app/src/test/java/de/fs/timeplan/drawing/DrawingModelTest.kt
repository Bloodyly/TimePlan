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
