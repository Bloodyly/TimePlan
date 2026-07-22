package de.fs.timeplan.grid

import de.fs.timeplan.drawing.DrawingContent

sealed class WeekRow {
    data class Monteur(
        val workerId: String,
        val displayName: String,
        val cellTexts: List<String?>,
        val cellDrawings: List<DrawingContent?> = emptyList()
    ) : WeekRow()
    data class Azubi(val workerId: String, val displayName: String, val cellTexts: List<String?>) : WeekRow()
    object Separator : WeekRow()
}
