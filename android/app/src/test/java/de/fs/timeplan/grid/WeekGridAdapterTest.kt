package de.fs.timeplan.grid

import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import de.fs.timeplan.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Assert.assertEquals

@RunWith(RobolectricTestRunner::class)
class WeekGridAdapterTest {

    @Test
    fun `renders monteur rows, separator, and azubi rows`() {
        val rows = listOf(
            WeekRow.Monteur("w-1", "144 Albrecht", listOf("Baustelle A", null, null, null, null, null, null)),
            WeekRow.Separator,
            WeekRow.Azubi("w-2", "501 Petersen", listOf(null, "144 Albrecht", null, null, null, null, null))
        )
        val adapter = WeekGridAdapter(rows)
        assertEquals(3, adapter.itemCount)
        assertEquals(0, adapter.getItemViewType(0))
        assertEquals(1, adapter.getItemViewType(1))
        assertEquals(0, adapter.getItemViewType(2))

        val parent = FrameLayout(ApplicationProvider.getApplicationContext())
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0)) as WeekGridAdapter.WorkerRowViewHolder
        adapter.onBindViewHolder(holder, 0)
        val nameView = holder.itemView.findViewById<TextView>(R.id.rowWorkerName)
        assertEquals("144 Albrecht", nameView.text.toString())
    }

    @Test
    fun `invokes onCellClick with workerId and date index when a cell is tapped`() {
        val rows = listOf(
            WeekRow.Monteur("w-1", "144 Albrecht", listOf(null, null, null, null, null, null, null))
        )
        val adapter = WeekGridAdapter(rows)
        var clicked: Pair<String, Int>? = null
        adapter.onCellClick = { workerId, dateIndex -> clicked = workerId to dateIndex }

        val parent = FrameLayout(ApplicationProvider.getApplicationContext())
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0)) as WeekGridAdapter.WorkerRowViewHolder
        adapter.onBindViewHolder(holder, 0)

        val cellsContainer = holder.itemView.findViewById<LinearLayout>(R.id.rowCellsContainer)
        cellsContainer.getChildAt(3).performClick()

        assertEquals("w-1" to 3, clicked)
    }

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

    @Test
    fun `a monteur cell with a drawing but no text uses the filled-cell style, not the empty-cell style`() {
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
        val firstCell = cellsContainer.getChildAt(0) as TextView
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val emptyState = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_cell_empty)
            ?.constantState
        val filledState = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_cell_filled)
            ?.constantState

        assertEquals(filledState, firstCell.background.constantState)
        org.junit.Assert.assertNotEquals(emptyState, firstCell.background.constantState)
    }
}
