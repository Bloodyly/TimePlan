package de.fs.timeplan.grid

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import de.fs.timeplan.week.SwipeDirection
import de.fs.timeplan.week.WeekSwipeGesture
import kotlin.math.abs

class SwipeInterceptLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onSwipe: ((SwipeDirection) -> Unit)? = null
    var isGestureEnabled: () -> Boolean = { true }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minDistancePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 60f, resources.displayMetrics
    )
    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L
    private var intercepting = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isGestureEnabled()) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                startTime = System.currentTimeMillis()
                intercepting = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - startX
                val dy = ev.y - startY
                if (!intercepting && abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f) {
                    intercepting = true
                }
            }
        }
        return intercepting
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - startX
                val dy = ev.y - startY
                val duration = System.currentTimeMillis() - startTime
                WeekSwipeGesture.detect(dx, dy, duration, minDistancePx)?.let { onSwipe?.invoke(it) }
                intercepting = false
            }
            MotionEvent.ACTION_CANCEL -> intercepting = false
        }
        return true
    }
}
