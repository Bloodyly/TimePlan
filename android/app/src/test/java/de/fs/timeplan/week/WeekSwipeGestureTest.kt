package de.fs.timeplan.week

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeekSwipeGestureTest {

    @Test
    fun `leftward swipe past threshold is NEXT`() {
        assertEquals(SwipeDirection.NEXT, WeekSwipeGesture.detect(-150f, 10f, 200L, 100f))
    }

    @Test
    fun `rightward swipe past threshold is PREV`() {
        assertEquals(SwipeDirection.PREV, WeekSwipeGesture.detect(150f, 10f, 200L, 100f))
    }

    @Test
    fun `too short a distance is ignored`() {
        assertNull(WeekSwipeGesture.detect(-50f, 10f, 200L, 100f))
    }

    @Test
    fun `too slow a movement is ignored`() {
        assertNull(WeekSwipeGesture.detect(-150f, 10f, 800L, 100f))
    }

    @Test
    fun `movement that is not dominantly horizontal is ignored`() {
        assertNull(WeekSwipeGesture.detect(-150f, 120f, 200L, 100f))
    }

    @Test
    fun `pure vertical scroll is ignored`() {
        assertNull(WeekSwipeGesture.detect(0f, 200f, 200L, 100f))
    }

    @Test
    fun `mostly vertical scroll past the distance threshold is still ignored`() {
        // Isolates the dominant-axis (ratio) check specifically: |dx|=150 clears
        // the 100px distance gate on its own, so this only returns null if the
        // ratio check (|dx| <= |dy| * 1.5) correctly rejects it too.
        assertNull(WeekSwipeGesture.detect(-150f, 300f, 200L, 100f))
    }
}
