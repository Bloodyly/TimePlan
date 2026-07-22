package de.fs.timeplan.net

import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class DemoApiTest {

    @Before
    fun setUp() {
        DemoApi.reset()
    }

    @Test
    fun `seeds ten monteure and ten azubis`() {
        val result = DemoApi.getWorkers()
        require(result is ApiResult.Success)
        val workers = result.data.workers
        assertEquals(10, workers.count { it.isMonteur })
        assertEquals(10, workers.count { it.isAzubi })
        assertTrue(workers.all { it.active })
    }

    @Test
    fun `getWeek returns only entries for the requested week`() {
        val monteurId = (DemoApi.getWorkers() as ApiResult.Success).data.workers.first { it.isMonteur }.id
        val cellThisWeek = WeekId.makeCellId("2027-W10", monteurId, "2027-03-08")
        val cellOtherWeek = WeekId.makeCellId("2027-W11", monteurId, "2027-03-15")
        DemoApi.putEntry(cellThisWeek, "Baustelle A")
        DemoApi.putEntry(cellOtherWeek, "Baustelle B")

        val result = DemoApi.getWeek("2027-W10")
        require(result is ApiResult.Success)
        assertEquals(1, result.data.entries.size)
        assertEquals(cellThisWeek, result.data.entries.first().cell_id)
    }

    @Test
    fun `putEntry with blank text removes the entry`() {
        val monteurId = (DemoApi.getWorkers() as ApiResult.Success).data.workers.first { it.isMonteur }.id
        val cellId = WeekId.makeCellId("2027-W20", monteurId, "2027-05-17")
        DemoApi.putEntry(cellId, "Notiz")
        assertEquals("Notiz", DemoApi.textFor(cellId))

        DemoApi.putEntry(cellId, "   ")
        assertNull(DemoApi.textFor(cellId))
    }

    @Test
    fun `putEntry replaces existing text and bumps revision`() {
        val monteurId = (DemoApi.getWorkers() as ApiResult.Success).data.workers.first { it.isMonteur }.id
        val cellId = WeekId.makeCellId("2027-W22", monteurId, "2027-05-31")
        DemoApi.putEntry(cellId, "Erste Version")
        val firstRevision = (DemoApi.getWeek("2027-W22") as ApiResult.Success).data.entries.first().revision

        DemoApi.putEntry(cellId, "Zweite Version")
        assertEquals("Zweite Version", DemoApi.textFor(cellId))
        val updatedRevision = (DemoApi.getWeek("2027-W22") as ApiResult.Success).data.entries.first().revision
        assertTrue(updatedRevision > firstRevision)
    }
}
