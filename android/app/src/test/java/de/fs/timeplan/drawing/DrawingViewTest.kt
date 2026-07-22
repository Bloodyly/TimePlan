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
