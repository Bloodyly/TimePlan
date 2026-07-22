package de.fs.timeplan.net

import java.time.LocalDate
import java.time.temporal.ChronoField
import java.time.temporal.WeekFields

data class CellRef(val weekId: String, val workerId: String, val date: String)

object WeekId {
    private val WEEK_ID_REGEX = Regex("""^(\d{4})-W(\d{2})$""")

    fun parse(weekId: String): Pair<Int, Int> {
        val match = WEEK_ID_REGEX.matchEntire(weekId)
            ?: throw IllegalArgumentException("invalid week id: $weekId")
        val year = match.groupValues[1].toInt()
        val week = match.groupValues[2].toInt()
        val wf = WeekFields.ISO
        val jan4 = LocalDate.of(year, 1, 4)
        val monday = jan4.with(ChronoField.DAY_OF_WEEK, 1L).plusWeeks((week - 1).toLong())
        if (monday.get(wf.weekBasedYear()) != year || monday.get(wf.weekOfWeekBasedYear()) != week) {
            throw IllegalArgumentException("invalid week id: $weekId")
        }
        return year to week
    }

    fun weekDates(weekId: String): List<LocalDate> {
        val (year, week) = parse(weekId)
        val jan4 = LocalDate.of(year, 1, 4)
        val monday = jan4.with(ChronoField.DAY_OF_WEEK, 1L).plusWeeks((week - 1).toLong())
        return (0..6).map { monday.plusDays(it.toLong()) }
    }

    fun currentWeekId(today: LocalDate = LocalDate.now()): String {
        val wf = WeekFields.ISO
        val year = today.get(wf.weekBasedYear())
        val week = today.get(wf.weekOfWeekBasedYear())
        return "%04d-W%02d".format(year, week)
    }

    fun adjacentWeekId(weekId: String, delta: Int): String {
        val monday = weekDates(weekId)[0].plusWeeks(delta.toLong())
        return currentWeekId(monday)
    }

    fun makeCellId(weekId: String, workerId: String, date: LocalDate): String =
        "${weekId}_${workerId}_${date}"

    fun makeCellId(weekId: String, workerId: String, dateIso: String): String =
        "${weekId}_${workerId}_${dateIso}"

    fun parseCellId(cellId: String): CellRef {
        val parts = cellId.split("_")
        require(parts.size == 3) { "invalid cell id: $cellId" }
        return CellRef(parts[0], parts[1], parts[2])
    }
}
