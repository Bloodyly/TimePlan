package de.fs.timeplan.week

import org.junit.Assert.assertEquals
import org.junit.Test

class DragFillRangeTest {

    private val centers = listOf(100f, 200f, 300f)

    @Test
    fun `no movement covers nothing`() {
        assertEquals(emptyList<Int>(), DragFillRange.coveredIndices(centers, 0, 100f))
    }

    @Test
    fun `crossing one cell center covers it`() {
        assertEquals(listOf(1), DragFillRange.coveredIndices(centers, 0, 250f))
    }

    @Test
    fun `crossing past the last cell covers all remaining`() {
        assertEquals(listOf(1, 2), DragFillRange.coveredIndices(centers, 0, 350f))
    }

    @Test
    fun `origin in the middle only considers cells after it`() {
        assertEquals(listOf(2), DragFillRange.coveredIndices(centers, 1, 350f))
    }

    @Test
    fun `retracting before a center uncoveres it again`() {
        assertEquals(emptyList<Int>(), DragFillRange.coveredIndices(centers, 0, 150f))
    }
}
