package de.fs.timeplan.week

import kotlin.math.abs

enum class SwipeDirection { PREV, NEXT }

object WeekSwipeGesture {
    private const val MAX_DURATION_MS = 600L
    private const val MIN_RATIO = 1.5f

    fun detect(dx: Float, dy: Float, durationMs: Long, minDistancePx: Float): SwipeDirection? {
        if (durationMs > MAX_DURATION_MS) return null
        if (abs(dx) < minDistancePx) return null
        if (abs(dx) <= abs(dy) * MIN_RATIO) return null
        return if (dx < 0) SwipeDirection.NEXT else SwipeDirection.PREV
    }
}
