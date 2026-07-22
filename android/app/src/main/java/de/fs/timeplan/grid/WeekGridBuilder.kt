package de.fs.timeplan.grid

import de.fs.timeplan.drawing.DrawingContent
import de.fs.timeplan.drawing.DrawingContentCodec
import de.fs.timeplan.model.Entry
import de.fs.timeplan.model.Worker
import de.fs.timeplan.model.textOrNull
import de.fs.timeplan.net.WeekId

object WeekGridBuilder {
    fun build(weekId: String, dates: List<String>, workers: List<Worker>, entries: List<Entry>): List<WeekRow> {
        val byCell: Map<String, List<Entry>> = entries.groupBy { it.cell_id }

        fun cellText(workerId: String, date: String): String? {
            val cellEntries = byCell[WeekId.makeCellId(weekId, workerId, date)].orEmpty()
            val texts = cellEntries.mapNotNull { it.textOrNull() }
            return texts.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }

        fun cellDrawing(workerId: String, date: String): DrawingContent? {
            val cellEntries = byCell[WeekId.makeCellId(weekId, workerId, date)].orEmpty()
            val drawingEntry = cellEntries.firstOrNull { it.type == "drawing" } ?: return null
            return DrawingContentCodec.decode(drawingEntry.content)
        }

        val active = workers.filter { it.active }
        val monteure = active.filter { it.isMonteur }.sortedBy { it.position }
        val azubis = active.filter { it.isAzubi }.sortedBy { it.position }

        val rows = mutableListOf<WeekRow>()
        for (w in monteure) {
            rows += WeekRow.Monteur(
                w.id, w.displayName,
                dates.map { cellText(w.id, it) },
                dates.map { cellDrawing(w.id, it) }
            )
        }
        rows += WeekRow.Separator
        for (w in azubis) {
            rows += WeekRow.Azubi(w.id, w.displayName, dates.map { cellText(w.id, it) })
        }
        return rows
    }
}
