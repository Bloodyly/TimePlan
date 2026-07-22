package de.fs.timeplan.net

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import java.time.LocalDate

class WeekIdTest {

    @Test
    fun `week dates match iso week`() {
        val dates = WeekId.weekDates("2026-W31")
        assertEquals(LocalDate.of(2026, 7, 27), dates[0])
        assertEquals(LocalDate.of(2026, 8, 2), dates[6])
    }

    @Test
    fun `adjacent week crosses year boundary`() {
        assertEquals("2025-W52", WeekId.adjacentWeekId("2026-W01", -1))
        assertEquals("2026-W01", WeekId.adjacentWeekId("2025-W52", 1))
    }

    @Test
    fun `parse rejects invalid ids`() {
        assertThrows(IllegalArgumentException::class.java) { WeekId.parse("2026-31") }
        assertThrows(IllegalArgumentException::class.java) { WeekId.parse("2026-W60") }
    }

    @Test
    fun `cell id round trip`() {
        val cellId = WeekId.makeCellId("2026-W31", "w-abc12345", LocalDate.of(2026, 7, 30))
        assertEquals("2026-W31_w-abc12345_2026-07-30", cellId)
        val ref = WeekId.parseCellId(cellId)
        assertEquals("2026-W31", ref.weekId)
        assertEquals("w-abc12345", ref.workerId)
        assertEquals("2026-07-30", ref.date)
    }
}
