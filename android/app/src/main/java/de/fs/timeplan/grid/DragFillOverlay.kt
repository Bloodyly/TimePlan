package de.fs.timeplan.grid

import android.view.View
import android.widget.TextView

class DragFillOverlay(
    private val container: View,
    private val shaft: View,
    private val head: TextView
) {
    fun show(originX: Float, centerY: Float) {
        update(originX, centerY, originX)
        shaft.visibility = View.VISIBLE
        head.visibility = View.VISIBLE
    }

    fun update(originX: Float, centerY: Float, currentX: Float) {
        val offset = IntArray(2)
        container.getLocationOnScreen(offset)
        val localOriginX = originX - offset[0]
        val localY = centerY - offset[1]
        val localCurrentX = currentX - offset[0]

        shaft.translationX = localOriginX
        shaft.translationY = localY - shaft.layoutParams.height / 2f
        shaft.layoutParams = shaft.layoutParams.apply {
            width = maxOf((localCurrentX - localOriginX).toInt(), 0)
        }
        shaft.requestLayout()

        head.translationX = localCurrentX - head.width / 2f
        head.translationY = localY - head.height / 2f
    }

    fun hide() {
        shaft.visibility = View.INVISIBLE
        head.visibility = View.INVISIBLE
    }
}
