package de.fs.timeplan.grid

import de.fs.timeplan.drawing.DrawingContentCodec
import de.fs.timeplan.model.Entry
import de.fs.timeplan.model.Worker
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class WeekGridBuilderTest {

    private val json = Json

    private fun textEntry(cellId: String, text: String) = Entry(
        id = "e-1", cell_id = cellId, type = "text", author_type = "web", author_id = "web-admin",
        content = json.parseToJsonElement("""{"text":"$text"}"""),
        conflict_of = null, created_at = "now", updated_at = "now", revision = 1
    )

    private fun drawingEntry(cellId: String) = Entry(
        id = "e-2", cell_id = cellId, type = "drawing", author_type = "tablet", author_id = "tablet-01",
        content = json.parseToJsonElement(
            """{"canvas_width":100,"canvas_height":50,
                "strokes":[{"color":"#201A10","base_width":4.0,
                            "points":[{"x":1.0,"y":2.0,"pressure":0.5,"time":0}]}]}"""),
        conflict_of = null, created_at = "now", updated_at = "now", revision = 1
    )

    @Test
    fun `builds monteur rows, separator, and azubi rows with cell text placed correctly`() {
        val monteur = Worker("w-1", "144", "Albrecht", "monteur", 1, true, 1)
        val azubi = Worker("w-2", "501", "Petersen", "azubi", 1, true, 1)
        val dates = listOf("2026-07-27", "2026-07-28", "2026-07-29", "2026-07-30", "2026-07-31", "2026-08-01", "2026-08-02")
        val entries = listOf(textEntry("2026-W31_w-1_2026-07-30", "Baustelle A"))

        val rows = WeekGridBuilder.build("2026-W31", dates, listOf(monteur, azubi), entries)

        assertEquals(3, rows.size)
        val monteurRow = rows[0] as WeekRow.Monteur
        assertEquals("144 Albrecht", monteurRow.displayName)
        assertEquals("Baustelle A", monteurRow.cellTexts[3])
        assertEquals(null, monteurRow.cellTexts[0])
        assertTrue(rows[1] is WeekRow.Separator)
        val azubiRow = rows[2] as WeekRow.Azubi
        assertEquals("501 Petersen", azubiRow.displayName)
    }

    @Test
    fun `ignores inactive workers`() {
        val inactiveMonteur = Worker("w-1", "144", "Albrecht", "monteur", 1, false, 1)
        val rows = WeekGridBuilder.build("2026-W31", List(7) { "2026-07-27" }, listOf(inactiveMonteur), emptyList())
        assertEquals(1, rows.size)
        assertTrue(rows[0] is WeekRow.Separator)
    }

    @Test
    fun `handles a 5-day week without saturday and sunday columns`() {
        val monteur = Worker("w-1", "144", "Albrecht", "monteur", 1, true, 1)
        val dates = listOf("2026-07-27", "2026-07-28", "2026-07-29", "2026-07-30", "2026-07-31")

        val rows = WeekGridBuilder.build("2026-W31", dates, listOf(monteur), emptyList())

        val monteurRow = rows[0] as WeekRow.Monteur
        assertEquals(5, monteurRow.cellTexts.size)
    }

    @Test
    fun `a drawing entry is exposed as cellDrawings and not mixed into cellTexts`() {
        val monteur = Worker("w-1", "144", "Albrecht", "monteur", 1, true, 1)
        val dates = listOf("2026-07-27", "2026-07-28", "2026-07-29", "2026-07-30", "2026-07-31", "2026-08-01", "2026-08-02")
        val entries = listOf(drawingEntry("2026-W31_w-1_2026-07-30"))

        val rows = WeekGridBuilder.build("2026-W31", dates, listOf(monteur), entries)

        val monteurRow = rows[0] as WeekRow.Monteur
        assertNull(monteurRow.cellTexts[3])
        val drawing = monteurRow.cellDrawings[3]
        assertEquals(100, drawing?.canvas_width)
        assertEquals(1, drawing?.strokes?.size)
    }

    @Test
    fun `a monteur cell can show both a web text and a drawing stacked`() {
        val monteur = Worker("w-1", "144", "Albrecht", "monteur", 1, true, 1)
        val dates = listOf("2026-07-27", "2026-07-28", "2026-07-29", "2026-07-30", "2026-07-31", "2026-08-01", "2026-08-02")
        val entries = listOf(
            textEntry("2026-W31_w-1_2026-07-30", "Hinweis"),
            drawingEntry("2026-W31_w-1_2026-07-30")
        )

        val rows = WeekGridBuilder.build("2026-W31", dates, listOf(monteur), entries)

        val monteurRow = rows[0] as WeekRow.Monteur
        assertEquals("Hinweis", monteurRow.cellTexts[3])
        assertEquals(100, monteurRow.cellDrawings[3]?.canvas_width)
    }
}
